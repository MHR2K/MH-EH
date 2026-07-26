/*
 * Copyright 2024 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.smb

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * CBZ远程文件缓存管理器
 *
 * 功能：
 * - 将远程CBZ文件缓存到本地
 * - 使用LRU策略管理缓存数量
 * - 自动清理过期缓存
 *
 * @author Hippo Seven
 */
object CbzCacheManager {
    private const val TAG = "CbzCacheManager"

    // 缓存配置
    private const val CACHE_DIR_NAME = "smb_cbz_cache"
    private const val MAX_CACHED_CBZ = 3  // 最多缓存3个CBZ文件
    private const val MAX_CACHE_SIZE_MB = 100L  // 最大缓存大小100MB
    private const val CACHE_VERSION = 1  // 缓存版本，变更时自动清除旧缓存

    // 线程池
    private val executor = Executors.newSingleThreadExecutor()

    // 上下文（需要初始化）
    private lateinit var cacheDir: File
    private lateinit var context: Context

    // LRU追踪：记录访问时间（文件路径 -> 最后访问时间）
    private val accessTimes = ConcurrentHashMap<String, Long>()

    // 当前缓存大小
    private val currentCacheSize = AtomicLong(0)

    // 初始化
    fun init(ctx: Context) {
        context = ctx.applicationContext
        cacheDir = File(ctx.cacheDir, CACHE_DIR_NAME)

        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        // 检查缓存版本，不一致则清除
        val versionFile = File(cacheDir, ".version")
        val currentVersion = if (versionFile.exists()) versionFile.readText().toIntOrNull() ?: 0 else 0
        if (currentVersion != CACHE_VERSION) {
            clearAllCache()
            versionFile.writeText(CACHE_VERSION.toString())
        }

        // 扫描现有缓存大小
        scanCacheSize()

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "CbzCacheManager initialized, cacheDir: ${cacheDir.absolutePath}")
            Log.d(TAG, "Current cache size: ${currentCacheSize.get()} bytes")
        }
    }

    /**
     * 获取CBZ文件的本地缓存输入流
     *
     * @param authority SMB服务器标识
     * @param share 共享名
     * @param pathInShare CBZ在共享中的路径
     * @return 本地缓存文件的输入流，如果获取失败返回null
     */
    fun getCachedInputStream(
        authority: Authority,
        share: String,
        pathInShare: String
    ): FileInputStream? {
        val cacheKey = generateCacheKey(authority, share, pathInShare)
        val cachedFile = File(cacheDir, cacheKey)

        // 命中缓存
        if (cachedFile.exists()) {
            // 更新访问时间
            accessTimes[cacheKey] = System.currentTimeMillis()

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "CBZ cache hit: $pathInShare")
            }

            return try {
                FileInputStream(cachedFile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open cached CBZ", e)
                // 缓存文件损坏，删除它
                cachedFile.delete()
                null
            }
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "CBZ cache miss: $pathInShare")
        }

        return null
    }

    /**
     * 检查CBZ是否已缓存
     */
    fun isCached(authority: Authority, share: String, pathInShare: String): Boolean {
        val cacheKey = generateCacheKey(authority, share, pathInShare)
        return File(cacheDir, cacheKey).exists()
    }

    /**
     * 获取缓存的 CBZ 文件（如存在）
     */
    fun getCachedFile(authority: Authority, share: String, pathInShare: String): File? {
        val cacheKey = generateCacheKey(authority, share, pathInShare)
        val file = File(cacheDir, cacheKey)
        return if (file.exists()) file else null
    }

    /**
     * 打开缓存的 CBZ 文件为 ZipFile，支持 O(1) 随机访问
     *
     * @return ZipFile 实例，未缓存或打开失败返回 null
     */
    fun openCachedZipFile(authority: Authority, share: String, pathInShare: String): java.util.zip.ZipFile? {
        val cacheKey = generateCacheKey(authority, share, pathInShare)
        val cachedFile = File(cacheDir, cacheKey)

        if (!cachedFile.exists() || cachedFile.length() == 0L) {
            // 文件不存在或为空（损坏的残留），清理并返回 null
            if (cachedFile.exists()) cachedFile.delete()
            return null
        }

        // 更新访问时间
        accessTimes[cacheKey] = System.currentTimeMillis()

        return try {
            java.util.zip.ZipFile(cachedFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open cached CBZ as ZipFile", e)
            // 缓存文件损坏，删除它
            cachedFile.delete()
            null
        }
    }

    /**
     * 缓存CBZ文件到本地
     *
     * @param authority SMB服务器标识
     * @param share 共享名
     * @param pathInShare CBZ在共享中的路径
     * @param inputStream 远程CBZ的输入流（调用方需要关闭）
     * @param size 文件大小（可选，用于LRU计算）
     */
    fun cacheCbz(
        authority: Authority,
        share: String,
        pathInShare: String,
        inputStream: java.io.InputStream,
        size: Long = -1
    ): File? {
        val cacheKey = generateCacheKey(authority, share, pathInShare)
        val cachedFile = File(cacheDir, cacheKey)

        // 已存在则直接返回
        if (cachedFile.exists()) {
            accessTimes[cacheKey] = System.currentTimeMillis()
            return cachedFile
        }

        // 确保有足够空间
        val actualSize = if (size > 0) size else inputStream.available().toLong().coerceAtLeast(1024 * 1024)
        ensureCacheSpace(actualSize)

        // 写入缓存文件
        return try {
            FileOutputStream(cachedFile).use { fos ->
                inputStream.copyTo(fos, bufferSize = 64 * 1024)
            }

            // 更新元数据
            accessTimes[cacheKey] = System.currentTimeMillis()
            currentCacheSize.addAndGet(cachedFile.length())

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "CBZ cached: $pathInShare (${cachedFile.length()} bytes)")
            }

            cachedFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache CBZ: $pathInShare", e)
            cachedFile.delete()
            null
        }
    }

    /**
     * CBZ 下载进度监听器
     */
    interface ProgressListener {
        /**
         * @param bytesRead 已下载字节数
         * @param totalBytes 文件总字节数（-1 表示未知）
         * @param speedBps 当前速度（字节/秒）
         */
        fun onProgress(bytesRead: Long, totalBytes: Long, speedBps: Long)
        fun onComplete(file: File)
        fun onError(e: Exception)
    }

    // 防止同一线程重复下载的锁
    private val downloadLocks = ConcurrentHashMap<String, Any>()

    /**
     * 带进度回调的 CBZ 下载
     * 替代 cacheCbz() 用于需要显示进度的场景
     * 线程安全：多个线程同时请求同一文件时，只有一个执行下载，其他等待
     */
    fun downloadWithProgress(
        authority: Authority,
        share: String,
        pathInShare: String,
        inputStream: java.io.InputStream,
        totalBytes: Long = -1,
        listener: ProgressListener
    ) {
        val cacheKey = generateCacheKey(authority, share, pathInShare)
        val cachedFile = File(cacheDir, cacheKey)

        // 获取 per-file 锁，防止多线程同时下载同一文件
        val lock = downloadLocks.computeIfAbsent(cacheKey) { Any() }
        synchronized(lock) {
            try {
                // 已存在且有效则直接返回（检查文件大小 > 0）
                if (cachedFile.exists() && cachedFile.length() > 0) {
                    accessTimes[cacheKey] = System.currentTimeMillis()
                    listener.onComplete(cachedFile)
                    return
                }

                // 文件存在但无效（0字节或损坏），删除后重新下载
                if (cachedFile.exists()) {
                    cachedFile.delete()
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Deleted invalid cached CBZ: $pathInShare (size=${cachedFile.length()})")
                    }
                }

                // 确保有足够空间
                val actualSize = if (totalBytes > 0) totalBytes else inputStream.available().toLong().coerceAtLeast(1024 * 1024)
                ensureCacheSpace(actualSize)

                // 手动读取循环，报告进度
                try {
                    FileOutputStream(cachedFile).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        var bytesRead = 0L
                        var lastReportTime = System.currentTimeMillis()
                        var lastReportBytes = 0L

                        while (true) {
                            val read = inputStream.read(buf)
                            if (read == -1) break
                            fos.write(buf, 0, read)
                            bytesRead += read

                            // 每 200ms 报告一次进度
                            val now = System.currentTimeMillis()
                            val elapsed = now - lastReportTime
                            if (elapsed >= 200) {
                                val speedBps = if (elapsed > 0) (bytesRead - lastReportBytes) * 1000 / elapsed else 0
                                listener.onProgress(bytesRead, totalBytes, speedBps)
                                lastReportTime = now
                                lastReportBytes = bytesRead
                            }
                        }
                        fos.flush()

                        // 最终进度报告
                        val totalElapsed = System.currentTimeMillis() - lastReportTime + 1
                        val finalSpeed = if (totalElapsed > 0) (bytesRead - lastReportBytes) * 1000 / totalElapsed else 0
                        listener.onProgress(bytesRead, totalBytes, finalSpeed)
                    }

                    // 验证下载结果：文件必须存在且大小 > 0
                    if (!cachedFile.exists() || cachedFile.length() == 0L) {
                        cachedFile.delete()
                        listener.onError(java.io.IOException("Downloaded CBZ file is empty or missing"))
                        return
                    }

                    // 更新元数据
                    accessTimes[cacheKey] = System.currentTimeMillis()
                    currentCacheSize.addAndGet(cachedFile.length())

                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "CBZ downloaded with progress: $pathInShare (${cachedFile.length()} bytes)")
                    }

                    listener.onComplete(cachedFile)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download CBZ: $pathInShare", e)
                    cachedFile.delete()
                    listener.onError(e)
                }
            } finally {
                downloadLocks.remove(cacheKey)
            }
        }
    }

    /**
     * 异步缓存CBZ
     */
    fun cacheCbzAsync(
        authority: Authority,
        share: String,
        pathInShare: String,
        inputStream: java.io.InputStream,
        size: Long = -1,
        callback: ((File?) -> Unit)? = null
    ) {
        executor.execute {
            val result = cacheCbz(authority, share, pathInShare, inputStream, size)
            callback?.let { cb ->
                android.os.Handler(context.mainLooper).post { cb(result) }
            }
        }
    }

    /**
     * 清除指定CBZ的缓存
     */
    fun invalidate(authority: Authority, share: String, pathInShare: String) {
        val cacheKey = generateCacheKey(authority, share, pathInShare)
        val cachedFile = File(cacheDir, cacheKey)

        if (cachedFile.exists()) {
            val fileSize = cachedFile.length()
            if (cachedFile.delete()) {
                accessTimes.remove(cacheKey)
                currentCacheSize.addAndGet(-fileSize)

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "CBZ cache invalidated: $pathInShare")
                }
            }
        }
    }

    /**
     * 清除所有缓存
     */
    fun clearAllCache() {
        synchronized(this) {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name != ".version") {
                    file.delete()
                }
            }
            accessTimes.clear()
            currentCacheSize.set(0)

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "All CBZ cache cleared")
            }
        }
    }

    /**
     * 清除最少使用的缓存，直到有足够空间
     */
    private fun ensureCacheSpace(requiredSize: Long) {
        val maxSize = MAX_CACHE_SIZE_MB * 1024 * 1024

        while (currentCacheSize.get() + requiredSize > maxSize || accessTimes.size >= MAX_CACHED_CBZ) {
            val oldest = accessTimes.minByOrNull { it.value }
            if (oldest != null) {
                val file = File(cacheDir, oldest.key)
                if (file.exists()) {
                    val fileSize = file.length()
                    if (file.delete()) {
                        accessTimes.remove(oldest.key)
                        currentCacheSize.addAndGet(-fileSize)

                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "LRU evicted: ${oldest.key}")
                        }
                    }
                } else {
                    accessTimes.remove(oldest.key)
                }
            } else {
                break
            }
        }
    }

    /**
     * 扫描并计算当前缓存大小
     */
    private fun scanCacheSize() {
        var totalSize = 0L
        accessTimes.clear()

        cacheDir.listFiles()?.forEach { file ->
            if (file.name != ".version" && file.isFile) {
                totalSize += file.length()
                // 使用文件修改时间作为初始访问时间
                accessTimes[file.name] = file.lastModified()
            }
        }

        currentCacheSize.set(totalSize)

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Cache scan complete: ${accessTimes.size} files, ${totalSize} bytes")
        }
    }

    /**
     * 生成缓存键
     */
    private fun generateCacheKey(authority: Authority, share: String, pathInShare: String): String {
        val input = "${authority.host}:${authority.port}:$share:$pathInShare"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + ".cbz"
    }

    /**
     * 获取缓存统计信息
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            cachedCount = accessTimes.size,
            totalSizeBytes = currentCacheSize.get(),
            maxCount = MAX_CACHED_CBZ,
            maxSizeBytes = MAX_CACHE_SIZE_MB * 1024 * 1024
        )
    }

    data class CacheStats(
        val cachedCount: Int,
        val totalSizeBytes: Long,
        val maxCount: Int,
        val maxSizeBytes: Long
    ) {
        fun getTotalSizeMB(): Double = totalSizeBytes / (1024.0 * 1024.0)
        fun getMaxSizeMB(): Double = maxSizeBytes / (1024.0 * 1024.0)
        fun getUsagePercent(): Double = (totalSizeBytes.toDouble() / maxSizeBytes) * 100
    }
}
