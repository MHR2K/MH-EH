/*
 * SMB 文件访问辅助类
 * 用于读写 SMB 路径上的文件（.thumb, .ehviewer 等）
 */
package com.hippo.ehviewer.smb

import android.util.Log
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.streampipe.InputStreamPipe
import com.hippo.lib.yorozuya.IOUtils
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object SmbFileHelper {
    private const val TAG = "SmbFileHelper"

    /**
     * 获取 SMB 文件的输入流管道
     * 优先使用 SMB 映射，其次尝试自动检测，最后返回 null
     */
    @JvmStatic
    fun getSmbFileInputStream(
        gid: Long,
        filename: String,
        galleryInfo: GalleryInfo? = null
    ): InputStreamPipe? {
        try {
            // Step 1: 检查显式 SMB 映射
            val mapping = SmbMappingStore.get(gid)
            if (mapping != null) {
                val rel = if (mapping.basePathInShare.isEmpty()) filename else mapping.basePathInShare + "\\" + filename
                val target = Client.Target(mapping.authority, mapping.share, rel)
                return openSmbInputStreamPipe(target)
            }

            // Step 2: 尝试自动检测 SMB 路径
            if (galleryInfo != null) {
                val autoTarget = tryAutoDetectSmbTarget(galleryInfo, filename)
                if (autoTarget != null) {
                    return openSmbInputStreamPipe(autoTarget)
                }
            }

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "No SMB mapping or auto-detect found for gid=$gid, file=$filename")
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error accessing SMB file: gid=$gid, file=$filename", e)
            }
        }
        return null
    }

    /**
     * 写入文件到 SMB
     * 返回是否成功
     */
    @JvmStatic
    fun writeSmbFile(
        gid: Long,
        filename: String,
        data: ByteArray,
        galleryInfo: GalleryInfo? = null
    ): Boolean {
        return try {
            // Step 1: 检查显式 SMB 映射
            val mapping = SmbMappingStore.get(gid)
            if (mapping != null) {
                val rel = if (mapping.basePathInShare.isEmpty()) filename else mapping.basePathInShare + "\\" + filename
                val target = Client.Target(mapping.authority, mapping.share, rel)
                writeSmbData(target, data)
                return true
            }

            // Step 2: 尝试自动检测 SMB 路径
            if (galleryInfo != null) {
                val autoTarget = tryAutoDetectSmbTarget(galleryInfo, filename)
                if (autoTarget != null) {
                    writeSmbData(autoTarget, data)
                    return true
                }
            }

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "No SMB mapping for writing: gid=$gid, file=$filename")
            }
            false
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error writing SMB file: gid=$gid, file=$filename", e)
            }
            false
        }
    }

    /**
     * 尝试自动检测 SMB 路径
     * 逻辑与 SpiderDen.tryAutoDetectAndOpenSmbInputStreamPipe 类似
     */
    private fun tryAutoDetectSmbTarget(
        galleryInfo: GalleryInfo,
        filename: String
    ): Client.Target? {
        try {
            // 获取本地下载目录（用于获取目录名）
            val localDir = SpiderDen.getGalleryDownloadDir(galleryInfo)
            val localDirName = localDir?.getName() ?: return null

            // 获取第一个已保存的 SMB 服务器
            val server = SmbServerStore.list().firstOrNull() ?: return null
            val relativePath = server.relativePath ?: ""
            
            // 从 relativePath 解析出 share 和 basePathInShare
            val normalized = relativePath.trim()
                .replace('/', '\\')
                .trim('\\')
            val firstSep = normalized.indexOf('\\')
            val share = if (firstSep == -1) normalized else normalized.substring(0, firstSep)
            val basePathInShare = if (firstSep == -1) "" else normalized.substring(firstSep + 1)

            // 列出共享中的目录，使用 Client.withTempPassword 临时提供密码
            val entries = try {
                Client.withTempPassword(server.authority, server.password) {
                    Client.listDirectory(
                        Client.Target(server.authority, share, basePathInShare)
                    )
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Auto-detect: failed to list directory: $e")
                }
                return null
            }

            // 查找匹配的目录
            for (entry in entries) {
                if (!entry.isDirectory) continue
                val name = entry.name ?: continue

                if (name.equals(localDirName, ignoreCase = true)) {
                    val detectedPath = if (basePathInShare.isEmpty()) {
                        name + "\\" + filename
                    } else {
                        basePathInShare + "\\" + name + "\\" + filename
                    }

                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Auto-detect SMB path found: $detectedPath")
                    }

                    return Client.Target(server.authority, share, detectedPath)
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Auto-detect SMB path failed", e)
            }
        }
        return null
    }

    /**
     * 打开 SMB 文件的输入流管道
     */
    private fun openSmbInputStreamPipe(target: Client.Target): InputStreamPipe? {
        return try {
            val inputStream = Client.openInputStream(target)
            object : InputStreamPipe {
                private var stream: InputStream? = inputStream

                override fun obtain() {
                    // no-op
                }

                override fun release() {
                    // no-op
                }

                override fun open(): InputStream {
                    return stream ?: throw IOException("Failed to open SMB input stream")
                }

                override fun close() {
                    IOUtils.closeQuietly(stream)
                    stream = null
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Failed to open SMB input stream", e)
            }
            null
        }
    }

    /**
     * 写入数据到 SMB
     */
    private fun writeSmbData(target: Client.Target, data: ByteArray) {
        try {
            val bais = java.io.ByteArrayInputStream(data)
            Client.upload(target, bais, overwrite = true)
            IOUtils.closeQuietly(bais)
        } catch (e: Exception) {
            throw IOException("Failed to write SMB file", e)
        }
    }
}
