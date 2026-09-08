package com.hippo.ehviewer.smb

import android.util.Log
import com.hierynomus.smbj.share.File
import java.io.IOException

/**
 * Wraps an SMB file as an [ArchiveByteSource] for random-access reads.
 * Holds one persistent smbj File handle for the reader session.
 * Auto-reconnects on share-closed errors.
 */
class SmbArchiveByteSource(
    private val authority: Authority,
    private val shareName: String,
    private val pathInShare: String,
) : ArchiveByteSource {

    private var fileSize: Long = -1
    private var file: File? = null
    private var closed = false

    override val size: Long
        get() {
            if (fileSize < 0) {
                fileSize = Client.getFileSize(Client.Target(authority, shareName, pathInShare))
            }
            return fileSize
        }

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (closed) return -1
        if (len == 0) return 0

        try {
            val f = ensureFile()
            // smbj native random read: File.read(buffer, fileOffset, bufferOffset, length)
            // Sends a single SMB2 READ request at the given offset, no skip() needed.
            val toRead = minOf(len, READ_BUFFER_SIZE)
            val bufForRead = if (off == 0 && toRead == buf.size) buf else ByteArray(toRead)
            val bytesRead = f.read(bufForRead, offset, 0, toRead)
            if (bytesRead <= 0) return -1
            if (bufForRead !== buf) {
                System.arraycopy(bufForRead, 0, buf, off, bytesRead)
            }
            return bytesRead
        } catch (e: IOException) {
            if (isShareClosedError(e)) {
                // Reset file handle, next call will reconnect
                closeFile()
            }
            Log.e(TAG, "readAt failed at offset=$offset len=$len", e)
            return -1
        } catch (e: Exception) {
            Log.e(TAG, "readAt failed at offset=$offset len=$len", e)
            return -1
        }
    }

    override fun close() {
        closed = true
        closeFile()
    }

    private fun ensureFile(): File {
        file?.let { return it }

        val target = Client.Target(authority, shareName, pathInShare)
        val f = Client.openFile(target)
        file = f
        return f
    }

    private fun closeFile() {
        try {
            file?.close()
        } catch (_: Exception) {}
        file = null
    }

    private fun isShareClosedError(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            val msg = cause.message?.lowercase() ?: ""
            if (msg.contains("diskshare has already been closed") ||
                msg.contains("connection closed") ||
                msg.contains("transport is closed") ||
                msg.contains("socket closed")
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    companion object {
        private const val TAG = "SmbArchiveByteSource"
        // 256 KB, matching Client.kt's SMB read buffer size
        private const val READ_BUFFER_SIZE = 256 * 1024
    }
}
