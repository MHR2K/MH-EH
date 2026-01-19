package com.hippo.ehviewer.smb

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.client.data.GalleryInfo

/**
 * SMB 映射扫描器：遍历 SMB 服务器或本地下载目录，查找匹配并创建映射。
 * 
 * 性能优化：
 * - 预先构建本地目录缓存（仅加载一次77000+条下载项）
 * - 缓存映射表供后续快速查询，避免重复加载
 */
object SmbMappingScanner {
    private const val TAG = "SmbMappingScanner"

    data class ScanResult(
        val totalSmbDirs: Int,           // 扫描的SMB目录总数
        val successCount: Int,           // 成功创建的映射数
        val skippedCount: Int,           // 跳过的数量（已存在或无匹配）
        val failedCount: Int,            // 失败的数量
        val details: List<String>        // 扫描日志
    )

    /**
     * 遍历 SMB 服务器上的目录，在本地下载项中查找匹配并创建映射（反向扫描）
     * 适用于 SMB 目录较少的场景
     * 
     * 流程：
     * 1. 预先构建本地目录→下载项映射表（仅加载一次）
     * 2. 遍历所有 SMB 服务器
     * 3. 对每个 SMB 目录，从缓存查找匹配的下载项
     * 4. 找到则创建映射
     */
    fun scanAndCreateMappings(context: Context, onProgress: (current: Int, total: Int, message: String?) -> Unit = { _, _, _ -> }): ScanResult {
        val details = mutableListOf<String>()
        var successCount = 0
        var skippedCount = 0
        var failedCount = 0
        var totalScanned = 0

        try {
            Log.d(TAG, "Starting SMB scan (reverse scan with optimization)...")
            details.add("📍 开始扫描 SMB 服务器目录...")
            val startTime = System.currentTimeMillis()

            // ===== 关键优化：预先构建本地目录名→下载项映射表（仅加载一次）=====
            details.add("⏳ 正在构建本地下载目录缓存...")
            // 通知 UI 当前阶段
            try {
                onProgress(0, 0, "正在构建本地下载目录缓存...")
            } catch (e: Exception) {
                Log.d(TAG, "onProgress callback failed: ${e.message}")
            }
            Log.d(TAG, "Building local directory cache...")
            val cacheStartTime = System.currentTimeMillis()
            val localDirCache = buildLocalDirectoryCache(context)
            val cacheBuildTime = System.currentTimeMillis() - cacheStartTime
            val cacheSize = localDirCache.size
            details.add("✅ 缓存构建完成: ${cacheSize} 个本地下载目录 (耗时${cacheBuildTime}ms)")
            Log.d(TAG, "Local directory cache built in ${cacheBuildTime}ms with ${cacheSize} entries")

            // ===== 新增优化：一次性读取所有下载并构建 gid -> DownloadInfo 映射（O(N)）=====
            val allDownloadsOnce: List<DownloadInfo> = try {
                EhDB.getAllDownloadInfo()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read all downloads: ${e.message}")
                emptyList()
            }
            val gidMap: Map<Long, DownloadInfo> = try {
                allDownloadsOnce.associateBy { it.gid }
            } catch (e: Exception) {
                emptyMap()
            }

            // 获取所有 SMB 服务器
            val servers = try {
                SmbServerStore.list()
            } catch (e: Exception) {
                details.add("❌ 读取 SMB 服务器列表失败: ${e.message}")
                return ScanResult(0, 0, 0, 1, details)
            }

            if (servers.isEmpty()) {
                details.add("⚠️ 未配置任何 SMB 服务器，无法扫描")
                return ScanResult(0, 0, 0, 0, details)
            }

            details.add("🖥️ 开始扫描 ${servers.size} 个 SMB 服务器上的目录...")
            Log.d(TAG, "Starting to scan ${servers.size} SMB servers")

            try {
                onProgress(0, 0, "开始扫描 SMB 服务器...")
            } catch (e: Exception) {
                Log.d(TAG, "onProgress callback failed: ${e.message}")
            }

            // 遍历每个 SMB 服务器（单次遍历，边列出边处理）
            for ((serverIndex, server) in servers.withIndex()) {
                try {
                    val target = server.toTarget() ?: continue
                    val authority = server.authority
                    val share = target.share
                    val basePath = target.pathInShare
                    val serverName = server.name ?: authority.toString()
                    
                    Log.d(TAG, "[$serverIndex/${servers.size}] Listing directory on $serverName (share=$share, path=$basePath)")

                    // 列举服务器上的所有目录（单次列出并处理）
                    val entries = try {
                        Client.listDirectory(target)
                    } catch (e: Exception) {
                        details.add("⚠️ 无法列举 $serverName 的目录: ${e.message}")
                        Log.w(TAG, "Failed to list $serverName: ${e.message}")
                        failedCount++
                        continue
                    }

                    Log.d(TAG, "Listed ${entries.size} entries on $serverName, processing directories...")

                    var dirCountOnThisServer = 0
                    for (entry in entries) {
                        if (!entry.isDirectory) continue

                        val smbDirName = entry.name
                        totalScanned++
                        dirCountOnThisServer++

                        // 使用已扫描目录数作为进度（不需要总数）
                        try {
                            onProgress(totalScanned, 0, null)
                        } catch (e: Exception) {
                            Log.d(TAG, "onProgress callback failed: ${e.message}")
                        }

                        Log.d(TAG, "[$serverIndex] Processing SMB dir: $smbDirName")

                        // ===== 优化点1：先尝试通过 gid 匹配（更可靠）=====
                        val extractedGid = extractGidFromDirName(smbDirName)
                        var matchedDownload: DownloadInfo? = null
                        if (extractedGid != null) {
                            Log.d(TAG, "Extracted gid=$extractedGid from '$smbDirName', querying gidMap...")
                            matchedDownload = gidMap[extractedGid]
                            if (matchedDownload != null) {
                                Log.d(TAG, "Found match by gid: ${matchedDownload.gid}")
                            }
                        }
                        
                        // ===== 优化点2：如果 gid 匹配失败，尝试目录名匹配 =====
                        if (matchedDownload == null) {
                            val cacheKey = smbDirName.lowercase()
                            Log.d(TAG, "No gid match, trying cache lookup with key: '$cacheKey'")
                            matchedDownload = try {
                                localDirCache[cacheKey]
                            } catch (e: Exception) {
                                Log.e(TAG, "Error querying cache: ${e.message}")
                                null
                            }
                        }

                        if (matchedDownload == null) {
                            Log.w(TAG, "No match found for: $smbDirName (gid=$extractedGid)")
                            details.add("⏭️ '$smbDirName': 本地无匹配下载项")
                            skippedCount++
                            continue
                        }

                        Log.d(TAG, "Found matching download for $smbDirName: gid=${matchedDownload.gid}")

                        // 检查是否已有映射
                        val existingMapping = try {
                            SmbMappingStore.get(matchedDownload.gid)
                        } catch (e: Exception) {
                            null
                        }

                        if (existingMapping != null) {
                            Log.w(TAG, "Mapping already exists for gid=${matchedDownload.gid}, skipping")
                            details.add("⏭️ '$smbDirName': gid=${matchedDownload.gid} 已存在映射，跳过")
                            skippedCount++
                            continue
                        }

                        // 创建映射
                        try {
                            val matchedPath = if (basePath.isEmpty()) {
                                smbDirName
                            } else {
                                "$basePath\\$smbDirName"
                            }

                            val mapping = SmbMappingStore.Mapping(
                                gid = matchedDownload.gid,
                                authority = authority,
                                share = share,
                                basePathInShare = matchedPath
                            )
                            SmbMappingStore.put(mapping)
                            details.add("✅ '$smbDirName': 成功创建映射 (gid=${matchedDownload.gid}, server=$serverName)")
                            successCount++
                            Log.d(TAG, "Successfully created mapping for gid=${matchedDownload.gid}")
                        } catch (e: Exception) {
                            details.add("❌ '$smbDirName': 创建映射失败 - ${e.message}")
                            failedCount++
                            Log.e(TAG, "Failed to create mapping for $smbDirName", e)
                        }
                    }
                    
                    details.add("📍 $serverName: 处理了 $dirCountOnThisServer 个目录")
                } catch (e: Exception) {
                    Log.e(TAG, "Error scanning server ${server.name}: ${e.message}")
                    failedCount++
                }
            }

            val elapsedTime = System.currentTimeMillis() - startTime
            details.add("")
            details.add("📊 扫描完成 (耗时 ${elapsedTime}ms): 扫描了 $totalScanned 个 SMB 目录，成功=$successCount, 跳过=$skippedCount, 失败=$failedCount")
            Log.d(TAG, "Scan completed in ${elapsedTime}ms: success=$successCount, skipped=$skippedCount, failed=$failedCount")

            return ScanResult(totalScanned, successCount, skippedCount, failedCount, details)
        } catch (e: Throwable) {
            details.add("❌ 扫描过程异常: ${e.message}")
            Log.e(TAG, "Unexpected error during scan", e)
            return ScanResult(0, successCount, skippedCount, failedCount + 1, details)
        }
    }

    /**
     * 遍历本地下载目录：对每个本地下载项，在 SMB 服务器上查找同名目录并创建映射
     * 适用于本地下载项较多的场景
     */
    fun scanByLocalDownloads(context: Context, onProgress: (current: Int, total: Int, message: String?) -> Unit = { _, _, _ -> }): ScanResult {
        val details = mutableListOf<String>()
        var successCount = 0
        var skippedCount = 0
        var failedCount = 0

        try {
            // 获取所有下载项
            val allDownloads = try {
                EhDB.getAllDownloadInfo()
            } catch (e: Exception) {
                details.add("❌ 读取下载列表失败: ${e.message}")
                return ScanResult(0, 0, 0, 1, details)
            }

            val totalCount = allDownloads.size
            details.add("📋 开始遍历 $totalCount 个本地下载项...")
            try {
                onProgress(0, totalCount, "开始遍历本地下载项...")
            } catch (e: Exception) {
                Log.d(TAG, "onProgress callback failed: ${e.message}")
            }
            Log.d(TAG, "Starting scan on $totalCount downloads")

            // 获取所有 SMB 服务器
            val servers = try {
                SmbServerStore.list()
            } catch (e: Exception) {
                details.add("❌ 读取 SMB 服务器列表失败: ${e.message}")
                return ScanResult(totalCount, 0, 0, 1, details)
            }

            if (servers.isEmpty()) {
                details.add("⚠️ 未配置任何 SMB 服务器，无法扫描")
                return ScanResult(totalCount, 0, totalCount, 0, details)
            }

            details.add("🖥️ 发现 ${servers.size} 个 SMB 服务器")

            // ===== 关键优化：对每台服务器只 listDirectory 一次，构建 lower->original 的目录名映射（O(M)）=====
            data class ServerDirIndex(
                val server: SmbServer,
                val lowerToOriginal: Map<String, String>,
                val basePath: String,
                val share: String
            )
            val serverDirIndices = mutableListOf<ServerDirIndex>()
            for (server in servers) {
                try {
                    val target = server.toTarget() ?: continue
                    val share = target.share
                    val basePath = target.pathInShare
                    val entries = try {
                        Client.listDirectory(target)
                    } catch (e: Exception) {
                        details.add("⚠️ 无法列举 ${server.name ?: server.authority.host} 的目录: ${e.message}")
                        Log.w(TAG, "Failed to list server ${server.name}: ${e.message}")
                        continue
                    }

                    val map = HashMap<String, String>(entries.size)
                    var dirCount = 0
                    for (entry in entries) {
                        if (entry.isDirectory) {
                            map[entry.name.lowercase()] = entry.name
                            dirCount++
                        }
                    }
                    serverDirIndices.add(ServerDirIndex(server, map, basePath, share))
                    details.add("📁 服务器 ${server.name ?: server.authority.host}: 预加载目录 ${dirCount} 个")
                } catch (e: Exception) {
                    Log.d(TAG, "Error preloading server dirs: ${e.message}")
                }
            }

            allDownloads.forEachIndexed { index, download ->
                try {
                    onProgress(index, totalCount, null)
                } catch (e: Exception) {
                    Log.d(TAG, "onProgress callback failed: ${e.message}")
                }

                // 检查是否已有映射
                val existingMapping = try {
                    SmbMappingStore.get(download.gid)
                } catch (e: Exception) {
                    null
                }

                if (existingMapping != null) {
                    details.add("⏭️ gid=${download.gid}: 已存在映射，跳过")
                    skippedCount++
                    return@forEachIndexed
                }

                // 获取本地目录名
                val localDirName = getLocalDirectoryName(download)
                if (localDirName == null) {
                    details.add("⚠️ gid=${download.gid}: 无本地下载目录")
                    skippedCount++
                    return@forEachIndexed
                }

                var foundMapping = false

                // 使用预加载的目录索引进行 O(1) 匹配
                val localLower = localDirName.lowercase()
                for (indexEntry in serverDirIndices) {
                    if (foundMapping) break
                    val original = indexEntry.lowerToOriginal[localLower]
                    if (original != null) {
                        try {
                            val matchedPath = if (indexEntry.basePath.isEmpty()) {
                                original
                            } else {
                                "${indexEntry.basePath}\\${original}"
                            }
                            val mapping = SmbMappingStore.Mapping(
                                gid = download.gid,
                                authority = indexEntry.server.authority,
                                share = indexEntry.share,
                                basePathInShare = matchedPath
                            )
                            SmbMappingStore.put(mapping)
                            details.add("✅ gid=${download.gid}: 在 ${indexEntry.server.name ?: indexEntry.server.authority.host} 找到匹配目录")
                            successCount++
                            foundMapping = true
                        } catch (e: Exception) {
                            Log.d(TAG, "Error creating mapping for gid=${download.gid}: ${e.message}")
                        }
                    }
                }

                if (!foundMapping) {
                    details.add("❌ gid=${download.gid}: 未在任何 SMB 服务器找到 '$localDirName'")
                    failedCount++
                }
            }

            details.add("")
            details.add("📊 扫描完成: 成功=$successCount, 跳过=$skippedCount, 失败=$failedCount")

            return ScanResult(totalCount, successCount, skippedCount, failedCount, details)
        } catch (e: Throwable) {
            details.add("❌ 扫描过程异常: ${e.message}")
            Log.e(TAG, "Unexpected error during scan", e)
            return ScanResult(0, successCount, skippedCount, failedCount + 1, details)
        }
    }

    /**
     * 构建本地下载目录名→下载项的映射表（仅加载一次）
     * 避免后续每次查询都重新加载77000+条记录和调用SpiderDen
     * 
     * 性能关键：这将 O(SMB目录数 * 下载项数) 降低到 O(下载项数 + SMB目录数)
     */
    private fun buildLocalDirectoryCache(context: Context): Map<String, DownloadInfo> {
        val cache = mutableMapOf<String, DownloadInfo>()
        val buildStartTime = System.currentTimeMillis()
        
        return try {
            val allDownloads = EhDB.getAllDownloadInfo()
            Log.d(TAG, "Building cache for ${allDownloads.size} downloads")
            
            for (download in allDownloads) {
                val localDir = getLocalDirectoryName(download)
                if (localDir != null) {
                    // 使用小写作为键以支持不区分大小写的查询
                    cache[localDir.lowercase()] = download
                }
            }
            
            val buildTime = System.currentTimeMillis() - buildStartTime
            Log.d(TAG, "Local directory cache built in ${buildTime}ms with ${cache.size} entries")
            
            // 调试：打印前5个缓存键
            if (cache.isNotEmpty()) {
                val sampleKeys = cache.keys.take(5).joinToString(", ")
                Log.d(TAG, "Sample cache keys: $sampleKeys")
            }
            
            cache
        } catch (e: Exception) {
            Log.e(TAG, "Error building local directory cache: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 从目录名中提取 gid（目录名格式通常为 "gid-title"）
     */
    private fun extractGidFromDirName(dirName: String): Long? {
        return try {
            // 尝试提取开头的数字部分（gid）
            val match = "^(\\d+)".toRegex().find(dirName)
            match?.groupValues?.get(1)?.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取下载项的本地目录名（不含绝对路径）
     */
    private fun getLocalDirectoryName(download: DownloadInfo): String? {
        return try {
            val gi = GalleryInfo().apply {
                gid = download.gid
                title = download.title
                titleJpn = download.titleJpn
            }
            val dir = SpiderDen.getGalleryDownloadDir(gi)
            if (dir != null && dir.isDirectory()) {
                dir.getName()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error getting local dir name for gid=${download.gid}: ${e.message}")
            null
        }
    }

    /**
     * 在指定 SMB 服务器上查找与 localDirName 匹配的目录（不区分大小写）
     */
    private fun findMatchingDirectory(
        localDirName: String,
        gid: Long,
        server: SmbServer
    ): SmbMappingStore.Mapping? {
        val target = server.toTarget() ?: return null
        val authority = server.authority
        val share = target.share
        val basePath = target.pathInShare

        return try {
            val entries = Client.listDirectory(target)
            for (entry in entries) {
                if (entry.isDirectory && localDirName.equals(entry.name, ignoreCase = true)) {
                    val matchedPath = if (basePath.isEmpty()) {
                        entry.name
                    } else {
                        "$basePath\\${entry.name}"
                    }
                    return SmbMappingStore.Mapping(
                        gid = gid,
                        authority = authority,
                        share = share,
                        basePathInShare = matchedPath
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "Error listing directory: ${e.message}")
            null
        }
    }
}
