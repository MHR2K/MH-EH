package com.hippo.ehviewer.smb

/**
 * Core abstraction for random-access byte sources used by stream archive opening.
 * All methods are blocking — callers must use archive/IO threads.
 */
interface ArchiveByteSource : AutoCloseable {
    /** Total file size in bytes. */
    val size: Long

    /**
     * Read up to [len] bytes at [offset].
     * @return bytes read (positive), 0 at EOF, or -1 on error.
     */
    fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int

    /** Optional readahead hint. Default no-op. */
    fun warm(offset: Long, length: Int = DEFAULT_SEQUENTIAL_WINDOW) {}

    /** Cancel queued (not in-flight) reads so a seek is not stuck behind prefetch. */
    fun dropQueuedReads() {}

    override fun close() {}

    companion object {
        const val DEFAULT_SEQUENTIAL_WINDOW = 8 * 1024 * 1024 // 8 MiB
        const val DEFAULT_RANDOM_WINDOW = 64 * 1024 // 64 KiB
    }
}
