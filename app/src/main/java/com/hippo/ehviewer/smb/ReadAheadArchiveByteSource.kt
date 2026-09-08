package com.hippo.ehviewer.smb

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Windowed readahead decorator for [ArchiveByteSource].
 * - 8 MiB sequential window, 64 KiB random window
 * - Tail-extend: never re-GETs bytes already in window
 * - Background prefetch on bounded thread pool
 */
class ReadAheadArchiveByteSource(
    private val inner: ArchiveByteSource,
    private val sequentialWindow: Int = ArchiveByteSource.DEFAULT_SEQUENTIAL_WINDOW,
    private val randomWindow: Int = ArchiveByteSource.DEFAULT_RANDOM_WINDOW,
) : ArchiveByteSource {

    override val size: Long get() = inner.size

    // Current cache window
    private var win: ByteArray? = null
    private var winStart: Long = 0
    private var winLen: Int = 0

    // Completed prefetch buffer
    private var pref: ByteArray? = null
    private var prefStart: Long = 0
    private var prefLen: Int = 0

    private val lock = Object()
    private var closed = false
    private val prefEpoch = AtomicInteger(0)

    // Background prefetch thread pool
    private val prefetchExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (closed) return -1
        if (len == 0) return 0

        synchronized(lock) {
            // Fast path: try to serve from current window or completed prefetch
            val served = serveLocked(offset, buf, off, len)
            if (served > 0) return served
        }

        // Slow path: fetch from inner source
        return fetchAndServe(offset, buf, off, len)
    }

    override fun warm(offset: Long, length: Int) {
        if (closed) return
        try {
            prefetchExecutor.submit {
                try {
                    doPrefetch(offset, length)
                } catch (_: Exception) {
                    // Ignore prefetch errors
                }
            }
        } catch (_: Exception) {
            // Executor shut down
        }
    }

    override fun dropQueuedReads() {
        prefEpoch.incrementAndGet()
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            win = null
            pref = null
            winLen = 0
            prefLen = 0
            lock.notifyAll()
        }
        try {
            inner.close()
        } catch (_: Exception) {}
        try {
            prefetchExecutor.shutdownNow()
        } catch (_: Exception) {}
    }

    private fun serveLocked(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        // Try current window
        if (win != null && offset >= winStart && offset < winStart + winLen) {
            val winOff = (offset - winStart).toInt()
            val avail = winLen - winOff
            val toCopy = minOf(len, avail)
            System.arraycopy(win!!, winOff, buf, off, toCopy)
            // Promote prefetch if it continues from window
            maybePromotePrefetchLocked()
            return toCopy
        }

        // Try completed prefetch
        if (pref != null && offset >= prefStart && offset < prefStart + prefLen) {
            val prefOff = (offset - prefStart).toInt()
            val avail = prefLen - prefOff
            val toCopy = minOf(len, avail)
            System.arraycopy(pref!!, prefOff, buf, off, toCopy)
            // Promote prefetch to window
            promotePrefetchLocked()
            return toCopy
        }

        return 0
    }

    private fun fetchAndServe(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        val fetchSize = chooseFetchSize(offset, len)
        val fetchBuf = ByteArray(fetchSize)

        val bytesRead = inner.readAt(offset, fetchBuf, 0, fetchSize)
        if (bytesRead <= 0) return bytesRead

        // Copy requested portion to caller
        val toCopy = minOf(len, bytesRead)
        System.arraycopy(fetchBuf, 0, buf, off, toCopy)

        // Update window
        synchronized(lock) {
            if (!closed) {
                win = fetchBuf
                winStart = offset
                winLen = bytesRead
                // Kick prefetch for next sequential block
                maybeKickPrefetchLocked()
            }
        }

        return toCopy
    }

    private fun chooseFetchSize(offset: Long, len: Int): Int {
        // Small request and not sequential: fetch random window
        if (len <= randomWindow && !isSequentialAt(offset)) {
            return minOf(randomWindow, (size - offset).toInt())
        }
        // Large request: fetch exact size
        if (len > randomWindow) {
            return minOf(len, (size - offset).toInt())
        }
        // Sequential: fetch full sequential window
        return minOf(sequentialWindow, (size - offset).toInt())
    }

    private fun isSequentialAt(offset: Long): Boolean {
        synchronized(lock) {
            if (win != null && offset >= winStart && offset <= winStart + winLen + sequentialWindow) {
                return true
            }
            return false
        }
    }

    private fun maybePromotePrefetchLocked() {
        if (pref != null && win != null && prefStart == winStart + winLen) {
            // Prefetch continues from window: append
            val newLen = winLen + prefLen
            val newWin = ByteArray(newLen)
            System.arraycopy(win!!, 0, newWin, 0, winLen)
            System.arraycopy(pref!!, 0, newWin, winLen, prefLen)
            win = newWin
            winLen = newLen
            pref = null
            prefLen = 0
        }
    }

    private fun promotePrefetchLocked() {
        if (pref != null) {
            win = pref
            winStart = prefStart
            winLen = prefLen
            pref = null
            prefLen = 0
        }
    }

    private fun maybeKickPrefetchLocked() {
        if (win == null || closed) return
        val nextOffset = winStart + winLen
        if (nextOffset >= size) return

        val epoch = prefEpoch.get()
        prefetchExecutor.submit {
            try {
                doPrefetch(nextOffset, sequentialWindow)
            } catch (_: Exception) {
                // Ignore prefetch errors
            }
            if (epoch != prefEpoch.get()) return@submit
        }
    }

    private fun doPrefetch(offset: Long, length: Int) {
        if (closed) return
        val fetchSize = minOf(length, (size - offset).toInt())
        if (fetchSize <= 0) return

        val fetchBuf = ByteArray(fetchSize)
        val bytesRead = inner.readAt(offset, fetchBuf, 0, fetchSize)
        if (bytesRead <= 0) return

        synchronized(lock) {
            if (closed) return
            // Check if this is a continuation of the current window
            if (win != null && offset == winStart + winLen) {
                // Append to window
                val newLen = winLen + bytesRead
                val newWin = ByteArray(newLen)
                System.arraycopy(win!!, 0, newWin, 0, winLen)
                System.arraycopy(fetchBuf, 0, newWin, winLen, bytesRead)
                win = newWin
                winLen = newLen
                // Trim window if too large
                val maxWinSize = sequentialWindow * 2
                if (winLen > maxWinSize) {
                    val drop = winLen - maxWinSize
                    val trimmed = ByteArray(maxWinSize)
                    System.arraycopy(win!!, drop, trimmed, 0, maxWinSize)
                    win = trimmed
                    winStart += drop
                    winLen = maxWinSize
                }
            } else {
                // Store as prefetch
                pref = fetchBuf
                prefStart = offset
                prefLen = bytesRead
            }
            lock.notifyAll()
        }
    }
}
