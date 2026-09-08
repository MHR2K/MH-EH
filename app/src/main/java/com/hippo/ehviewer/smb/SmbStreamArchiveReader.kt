package com.hippo.ehviewer.smb

import android.util.Log
import com.hippo.ehviewer.jni.closeArchive
import com.hippo.ehviewer.jni.extractToByteBuffer
import com.hippo.ehviewer.jni.extractStreamToByteBuffer
import com.hippo.ehviewer.jni.getExtension
import com.hippo.ehviewer.jni.getStreamMemberLength
import com.hippo.ehviewer.jni.getStreamMemberMethod
import com.hippo.ehviewer.jni.getStreamMemberOffset
import com.hippo.ehviewer.jni.getStreamMemberUncSize
import com.hippo.ehviewer.jni.openArchiveStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Helper for reading CBZ archives from SMB using streaming (ZIP CD index + on-demand extract).
 * Replaces the "download entire CBZ then open" approach with streaming reads.
 */
object SmbStreamArchiveReader {
    private const val TAG = "SmbStreamArchiveReader"

    /**
     * Result of opening a stream archive.
     */
    data class OpenResult(
        val pageCount: Int,
        val cacheKey: String,
        val bridge: ArchiveStreamBridge,
        val source: ArchiveByteSource,
    )

    /**
     * Open a CBZ archive from SMB for streaming reads.
     * Parses only the ZIP Central Directory (~64-128 KiB) instead of downloading the entire file.
     *
     * @return OpenResult with page count, or null if not a valid ZIP/CBZ
     */
    @JvmStatic
    fun openArchive(
        authority: Authority,
        share: String,
        path: String,
        fileSize: Long,
    ): OpenResult? {
        val cacheKey = ArchiveStreamPageCache.generateCacheKey(authority, share, path)

        // Check if cache is complete and ready for offline use
        if (ArchiveStreamPageCache.isCompleteAndReady(cacheKey, fileSize)) {
            Log.i(TAG, "Cache complete for $path, using cached pages")
            // Return a minimal result that indicates cache-only mode
            val index = ArchiveStreamPageCache.loadIndex(cacheKey)
            if (index != null && index.members.isNotEmpty()) {
                // Create a dummy source/bridge for cache-only mode
                val source = SmbArchiveByteSource(authority, share, path)
                val readAhead = ReadAheadArchiveByteSource(source)
                val bridge = ArchiveStreamBridge(readAhead)
                return OpenResult(index.members.size, cacheKey, bridge, readAhead)
            }
        }

        // Invalidate cache if remote size changed
        ArchiveStreamPageCache.invalidateIfSizeMismatch(cacheKey, fileSize)

        // Create streaming source
        val source = SmbArchiveByteSource(authority, share, path)
        val readAhead = ReadAheadArchiveByteSource(source)
        val bridge = ArchiveStreamBridge(readAhead)

        // Open archive via native stream I/O
        // Parameters: bridge, size, sortEntries, coverOnly, progressiveTar, maxScanBytes
        val pageCount = openArchiveStream(
            bridge,
            fileSize,
            true,   // sortEntries
            false,  // coverOnly
            false,  // progressiveTar
            0L,     // maxScanBytes (0 = unlimited for ZIP)
        )

        if (pageCount <= 0) {
            Log.w(TAG, "openArchiveStream returned $pageCount for $path")
            bridge.close()
            return null
        }

        Log.i(TAG, "Opened archive: $path, $pageCount pages, $fileSize bytes")

        // Save index for future cache hits
        saveIndex(cacheKey, fileSize, pageCount)

        return OpenResult(pageCount, cacheKey, bridge, readAhead)
    }

    /**
     * Extract a page image from the archive.
     * First checks the disk cache, then extracts from the archive if needed.
     *
     * @return ByteBuffer with the extracted image, or null on failure
     */
    @JvmStatic
    fun extractPage(
        result: OpenResult,
        index: Int,
        extension: String,
    ): ByteBuffer? {
        val cacheKey = result.cacheKey

        // Check disk cache first
        val cachedFile = ArchiveStreamPageCache.getCachedPage(cacheKey, index, extension)
        if (cachedFile != null) {
            Log.d(TAG, "Cache hit for page $index")
            return readFileToByteBuffer(cachedFile)
        }

        // Extract from archive
        try {
            val buffer = extractStreamToByteBuffer(index, result.bridge)
            if (buffer != null) {
                // Write to cache for future use
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                buffer.rewind()
                ArchiveStreamPageCache.writePage(cacheKey, index, extension, data)

                // Check if all pages are now cached
                val indexData = ArchiveStreamPageCache.loadIndex(cacheKey)
                if (indexData != null) {
                    val allCached = indexData.members.all { m ->
                        ArchiveStreamPageCache.getCachedPage(cacheKey, m.index, m.ext) != null
                    }
                    if (allCached) {
                        ArchiveStreamPageCache.markComplete(cacheKey)
                        Log.i(TAG, "All pages cached for $cacheKey")
                    }
                }
            }
            return buffer
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract page $index", e)
            return null
        }
    }

    /**
     * Get the file extension for a page index.
     */
    @JvmStatic
    fun getPageExtension(index: Int): String {
        return try {
            getExtension(index)
        } catch (e: Exception) {
            "jpg" // default
        }
    }

    /**
     * Close the archive and release resources.
     */
    @JvmStatic
    fun closeArchive(result: OpenResult?) {
        result?.bridge?.close()
        try {
            closeArchive()
        } catch (e: Exception) {
            Log.w(TAG, "closeArchive failed", e)
        }
    }

    /**
     * Get a cached page as an InputStream, or null if not cached.
     */
    @JvmStatic
    fun getCachedPageStream(cacheKey: String, index: Int, extension: String): InputStream? {
        val file = ArchiveStreamPageCache.getCachedPage(cacheKey, index, extension)
        return if (file != null) FileInputStream(file) else null
    }

    /**
     * Save the archive index (member metadata) to disk cache.
     */
    private fun saveIndex(cacheKey: String, remoteSize: Long, pageCount: Int) {
        try {
            val members = (0 until pageCount).map { i ->
                val ext = getExtension(i)
                val offset = getStreamMemberOffset(i)
                val compSize = getStreamMemberLength(i)
                val uncSize = getStreamMemberUncSize(i)
                val method = getStreamMemberMethod(i)
                ArchiveStreamPageCache.Member(
                    index = i,
                    name = "$i.$ext",
                    ext = ext,
                    offset = offset,
                    compSize = compSize,
                    uncSize = uncSize,
                    method = method,
                )
            }
            val index = ArchiveStreamPageCache.Index(
                cacheKey = cacheKey,
                remoteSize = remoteSize,
                complete = false,
                members = members,
            )
            ArchiveStreamPageCache.saveIndex(index)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save index", e)
        }
    }

    private fun readFileToByteBuffer(file: File): ByteBuffer {
        val data = file.readBytes()
        return ByteBuffer.wrap(data)
    }
}
