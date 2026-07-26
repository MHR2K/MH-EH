/*
 * SMB 文件访问辅助类
 * 用于读写 SMB 路径上的文件（.thumb, .ehviewer 等）
 * 
 * 注意：自动检测 SMB 路径逻辑已移除，统一由 SpiderDen 处理
 * 此处只负责使用显式映射访问 SMB 文件
 */
package com.hippo.ehviewer.smb

import android.util.Log
import com.hippo.ehviewer.BuildConfig
import com.hippo.streampipe.InputStreamPipe
import com.hippo.lib.yorozuya.IOUtils
import java.io.IOException
import java.io.InputStream

object SmbFileHelper {
    private const val TAG = "SmbFileHelper"

    /**
     * 获取 SMB 文件的输入流管道
     * 仅使用显式 SMB 映射，自动检测由 SpiderDen 统一处理
     */
    @JvmStatic
    fun getSmbFileInputStream(
        gid: Long,
        filename: String
    ): InputStreamPipe? {
        val dirTarget = SmbPathResolver.resolve(gid) ?: run {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "No SMB path resolved for gid=$gid")
            }
            return null
        }
        return try {
            val rel = if (dirTarget.pathInShare.isEmpty()) filename else dirTarget.pathInShare + "\\" + filename
            val target = Client.Target(dirTarget.authority, dirTarget.share, rel)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Opening SMB file: gid=$gid, target=${target.share}/${target.pathInShare}")
            }
            openSmbInputStreamPipe(target)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error accessing SMB file: gid=$gid, file=$filename, " +
                    "target=${dirTarget.authority.host}/${dirTarget.share}/${dirTarget.pathInShare}", e)
            }
            null
        }
    }

    /**
     * 写入文件到 SMB（仅使用显式映射）
     * 返回是否成功
     */
    @JvmStatic
    fun writeSmbFile(
        gid: Long,
        filename: String,
        data: ByteArray
    ): Boolean {
        val dirTarget = SmbPathResolver.resolve(gid) ?: return false
        return try {
            val rel = if (dirTarget.pathInShare.isEmpty()) filename else dirTarget.pathInShare + "\\" + filename
            val target = Client.Target(dirTarget.authority, dirTarget.share, rel)
            writeSmbData(target, data)
            true
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error writing SMB file: gid=$gid, file=$filename", e)
            }
            false
        }
    }

    /**
     * 删除 SMB 文件（仅使用显式映射）
     * @return 是否成功删除
     */
    @JvmStatic
    fun deleteSmbFile(
        gid: Long,
        filename: String
    ): Boolean {
        val dirTarget = SmbPathResolver.resolve(gid) ?: return false
        return try {
            val rel = if (dirTarget.pathInShare.isEmpty()) filename else dirTarget.pathInShare + "\\" + filename
            val target = Client.Target(dirTarget.authority, dirTarget.share, rel)
            Client.delete(target)
            true
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error deleting SMB file: gid=$gid, file=$filename", e)
            }
            false
        }
    }

    /**
     * 打开 SMB 文件的输入流管道
     * 支持多次打开，每次调用 open() 时重新创建输入流
     */
    private fun openSmbInputStreamPipe(target: Client.Target): InputStreamPipe? {
        return try {
            object : InputStreamPipe {
                private val targetRef = target
                private var currentStream: InputStream? = null

                override fun obtain() {
                    // no-op
                }

                override fun release() {
                    // no-op
                }

                override fun open(): InputStream {
                    // 关闭上一次的流，防止泄漏
                    currentStream?.let { IOUtils.closeQuietly(it) }
                    val stream = Client.openInputStream(targetRef)
                    currentStream = stream
                    return stream
                }

                override fun close() {
                    currentStream?.let { IOUtils.closeQuietly(it) }
                    currentStream = null
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
