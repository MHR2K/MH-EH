package com.hippo.ehviewer.smb

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Disk cache for extracted page images from stream-opened archives.
 *
 * Layout:
 * {cacheDir}/archive_pages/{sha256(cacheKey)}/
 *   index.json          // member metadata (offset, compSize, method)
 *   0.jpg, 1.png, ...   // extracted page images
 */
object ArchiveStreamPageCache {
    private const val TAG = "ArchiveStreamPageCache"
    private const val CACHE_DIR_NAME = "archive_pages"
    private const val INDEX_FILE = "index.json"

    private lateinit var cacheDir: File
    private val pinnedKeys = ConcurrentHashMap.newKeySet<String>()

    /** Member metadata for one page in the archive. */
    data class Member(
        val index: Int,
        val name: String,
        val ext: String,
        val offset: Long,      // ZIP local header offset
        val compSize: Long,
        val uncSize: Long,
        val method: Int        // 0=store, 8=deflate
    ) {
        val hasSeek: Boolean get() = offset >= 0 && uncSize > 0
    }

    /** Index of all members in a cached archive. */
    data class Index(
        val cacheKey: String,
        val remoteSize: Long,
        val complete: Boolean,
        val members: List<Member>
    ) {
        fun hasFullSeekIndex(): Boolean = members.isNotEmpty() && members.all { it.hasSeek }
    }

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
        cacheDir.mkdirs()
    }

    /**
     * Check if all pages are cached and ready for offline use.
     * O(1) disk check: loads index, validates size, checks page files exist.
     */
    fun isCompleteAndReady(cacheKey: String, remoteSize: Long): Boolean {
        val dir = getCacheDir(cacheKey)
        val indexFile = File(dir, INDEX_FILE)
        if (!indexFile.exists()) return false

        try {
            val index = loadIndex(cacheKey) ?: return false
            // Size mismatch: cache is stale
            if (remoteSize > 0 && index.remoteSize != remoteSize) return false
            // Not marked complete
            if (!index.complete) return false
            // Verify at least first page file exists
            if (index.members.isEmpty()) return false
            val firstPage = File(dir, "0.${index.members[0].ext}")
            return firstPage.exists() && firstPage.length() > 0
        } catch (e: Exception) {
            Log.w(TAG, "isCompleteAndReady check failed", e)
            return false
        }
    }

    /**
     * Write an extracted page image to disk cache.
     */
    fun writePage(cacheKey: String, index: Int, ext: String, data: ByteArray) {
        val dir = getCacheDir(cacheKey)
        dir.mkdirs()
        val tmpFile = File(dir, "${index}.${ext}.tmp")
        val targetFile = File(dir, "${index}.${ext}")
        try {
            FileOutputStream(tmpFile).use { it.write(data) }
            tmpFile.renameTo(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "writePage failed for index=$index", e)
            tmpFile.delete()
        }
    }

    /**
     * Get a cached page file, or null if not cached.
     */
    fun getCachedPage(cacheKey: String, index: Int, ext: String): File? {
        val dir = getCacheDir(cacheKey)
        val file = File(dir, "${index}.${ext}")
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Load the index from disk, or null if not found.
     */
    fun loadIndex(cacheKey: String): Index? {
        val dir = getCacheDir(cacheKey)
        val indexFile = File(dir, INDEX_FILE)
        if (!indexFile.exists()) return null

        try {
            val json = JSONObject(indexFile.readText())
            val membersArray = json.getJSONArray("members")
            val members = (0 until membersArray.length()).map { i ->
                val m = membersArray.getJSONObject(i)
                Member(
                    index = m.getInt("i"),
                    name = m.getString("name"),
                    ext = m.getString("ext"),
                    offset = m.getLong("offset"),
                    compSize = m.getLong("compSize"),
                    uncSize = m.getLong("uncSize"),
                    method = m.getInt("method")
                )
            }
            return Index(
                cacheKey = json.getString("cacheKey"),
                remoteSize = json.getLong("remoteSize"),
                complete = json.getBoolean("complete"),
                members = members
            )
        } catch (e: Exception) {
            Log.e(TAG, "loadIndex failed", e)
            return null
        }
    }

    /**
     * Save index to disk.
     */
    fun saveIndex(index: Index) {
        val dir = getCacheDir(index.cacheKey)
        dir.mkdirs()
        val indexFile = File(dir, INDEX_FILE)
        val tmpFile = File(dir, "${INDEX_FILE}.tmp")

        try {
            val json = JSONObject().apply {
                put("cacheKey", index.cacheKey)
                put("remoteSize", index.remoteSize)
                put("complete", index.complete)
                put("members", JSONArray().apply {
                    index.members.forEach { m ->
                        put(JSONObject().apply {
                            put("i", m.index)
                            put("name", m.name)
                            put("ext", m.ext)
                            put("offset", m.offset)
                            put("compSize", m.compSize)
                            put("uncSize", m.uncSize)
                            put("method", m.method)
                        })
                    }
                })
            }
            tmpFile.writeText(json.toString(2))
            tmpFile.renameTo(indexFile)
        } catch (e: Exception) {
            Log.e(TAG, "saveIndex failed", e)
            tmpFile.delete()
        }
    }

    /**
     * Mark the cache as complete (all pages extracted).
     */
    fun markComplete(cacheKey: String) {
        val index = loadIndex(cacheKey) ?: return
        if (!index.complete) {
            saveIndex(index.copy(complete = true))
        }
    }

    /**
     * Invalidate cache if remote size changed.
     */
    fun invalidateIfSizeMismatch(cacheKey: String, remoteSize: Long) {
        val index = loadIndex(cacheKey) ?: return
        if (index.remoteSize != remoteSize) {
            purge(cacheKey)
        }
    }

    /**
     * Pin a cache key to prevent LRU eviction while a reader is active.
     */
    fun pin(cacheKey: String) {
        pinnedKeys.add(cacheKey)
    }

    /**
     * Unpin a cache key.
     */
    fun unpin(cacheKey: String) {
        pinnedKeys.remove(cacheKey)
    }

    /**
     * Purge all cached data for a given cache key.
     */
    fun purge(cacheKey: String) {
        val dir = getCacheDir(cacheKey)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    /**
     * Generate a cache key from SMB path components.
     */
    fun generateCacheKey(authority: Authority, share: String, path: String): String {
        val input = "${authority.host}:${authority.port}:$share:$path"
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun getCacheDir(cacheKey: String): File {
        return File(cacheDir, cacheKey)
    }
}
