/*
 * Copyright 2016 Hippo Seven
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
package com.hippo.ehviewer.ui.scene.download.part

import android.util.Log
import com.hippo.conaco.DataContainer
import com.hippo.conaco.ProgressNotifier
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.smb.SmbFileHelper
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.io.UniFileInputStreamPipe
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.streampipe.InputStreamPipe
import com.hippo.unifile.UniFile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 缩略图数据容器
 * 支持 SMB 和本地存储
 */
class ThumbDataContainer(private val mInfo: DownloadInfo) : DataContainer {
    private var mFile: UniFile? = null
    private var mSmbPipe: InputStreamPipe? = null
    private var mSmbCheckInitiated = false  // 标记 SMB 检查是否已启动
    private var mSmbChecked = false          // 标记 SMB 检查是否已完成
    private var mSmbFailed = false           // 标记 SMB 检查失败，避免重复尝试
    private val mSmbCheckStartTime = System.currentTimeMillis()  // SMB 检查开始时间

    companion object {
        private const val TAG = "ThumbDataContainer"
        private const val RETRY_DELAY_MS = 100L
        private const val SMB_CHECK_TIMEOUT_MS = 500L  // SMB 检查超时时间（500ms）
    }

    private fun ensureFile() {
        if (mFile == null) {
            try {
                val dir = SpiderDen.getGalleryDownloadDir(mInfo)
                if (dir == null) {
                    Log.w(TAG, "Failed to get download directory for gid: ${mInfo.gid}")
                    return
                }
                if (!dir.isDirectory()) {
                    Log.w(TAG, "Download directory is not a directory for gid: ${mInfo.gid}")
                    return
                }
                mFile = dir.createFile(".thumb")
                if (mFile == null) {
                    Log.w(TAG, "createFile() returned null for .thumb, gid: ${mInfo.gid}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in ensureFile() for gid: ${mInfo.gid}", e)
            }
        }
    }

    override fun isEnabled(): Boolean {
        // 如果 SMB 检查尚未启动，在后台线程中启动
        if (!mSmbCheckInitiated && !mSmbFailed) {
            mSmbCheckInitiated = true
            Thread {
                try {
                    // 在后台线程中尝试获取 SMB 文件
                    val smbPipe = SmbFileHelper.getSmbFileInputStream(
                        mInfo.gid,
                        ".thumb",
                        mInfo
                    )
                    if (smbPipe != null) {
                        mSmbPipe = smbPipe
                        Log.d(TAG, "Successfully got SMB pipe for gid: ${mInfo.gid}")
                    } else {
                        mSmbFailed = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get SMB file input stream for gid: ${mInfo.gid}", e)
                    mSmbFailed = true
                } finally {
                    mSmbChecked = true
                }
            }.start()
        }

        // 如果 SMB 检查超时（超过500ms），标记为失败，优先使用本地文件
        if (!mSmbChecked && (System.currentTimeMillis() - mSmbCheckStartTime) > SMB_CHECK_TIMEOUT_MS) {
            Log.w(TAG, "SMB check timeout for gid: ${mInfo.gid}, falling back to local file")
            mSmbFailed = true
            mSmbChecked = true
        }

        // 如果 SMB 已检查且存在，立即返回 true
        if (mSmbChecked && mSmbPipe != null) {
            return true
        }

        // 检查本地文件 - 这个操作很快，不会阻塞
        ensureFile()
        return mFile != null
    }

    override fun onUrlMoved(requestUrl: String?, responseUrl: String?) {
    }

    override fun save(`is`: InputStream?, length: Long, mediaType: String?, notify: ProgressNotifier?): Boolean {
        if (`is` == null) {
            Log.e(TAG, "InputStream is null for .thumb, gid: ${mInfo.gid}")
            return false
        }

        // Step 1: 读取数据到字节数组
        val baos = ByteArrayOutputStream()
        val data: ByteArray
        try {
            IOUtils.copy(`is`, baos)
            data = baos.toByteArray()
            Log.d(TAG, "Read ${data.size} bytes for .thumb file, gid: ${mInfo.gid}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read input stream for .thumb, gid: ${mInfo.gid}", e)
            return false
        }

        // Step 2: 检测漫画存储位置，决定缩略图保存位置
        val storageLocation = StorageDetector.detect(mInfo)
        Log.d(TAG, "Detected storage location for gid ${mInfo.gid}: $storageLocation")
        
        // Step 3: 根据漫画位置保存缩略图
        when (storageLocation) {
            StorageDetector.StorageLocation.SMB, 
            StorageDetector.StorageLocation.BOTH -> {
                // 漫画在SMB或同时存在，优先保存到SMB
                try {
                    if (SmbFileHelper.writeSmbFile(mInfo.gid, ".thumb", data, mInfo)) {
                        Log.d(TAG, "Successfully saved .thumb to SMB, gid: ${mInfo.gid}")
                        // 如果是BOTH情况，也同时保存到本地
                        if (storageLocation == StorageDetector.StorageLocation.BOTH) {
                            saveToLocalFile(data)
                        }
                        return true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save .thumb to SMB, gid: ${mInfo.gid}", e)
                }
                // SMB保存失败，降级到本地
                Log.w(TAG, "SMB save failed, falling back to local for gid: ${mInfo.gid}")
            }
            StorageDetector.StorageLocation.LOCAL -> {
                // 漫画在本地，直接保存到本地
                Log.d(TAG, "Manga is local, saving .thumb to local file, gid: ${mInfo.gid}")
            }
            StorageDetector.StorageLocation.UNKNOWN -> {
                // 未知位置，尝试SMB优先，然后本地
                Log.w(TAG, "Storage location unknown for gid: ${mInfo.gid}, trying SMB first")
                try {
                    if (SmbFileHelper.writeSmbFile(mInfo.gid, ".thumb", data, mInfo)) {
                        Log.d(TAG, "Successfully saved .thumb to SMB (unknown location), gid: ${mInfo.gid}")
                        return true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save .thumb to SMB (unknown location), gid: ${mInfo.gid}", e)
                }
            }
        }

        // Step 4: 保存到本地文件（带重试机制）
        return saveToLocalFile(data)
    }

    /**
     * 保存数据到本地文件（带重试机制）
     */
    private fun saveToLocalFile(data: ByteArray): Boolean {
        var retryCount = 0
        while (retryCount < 2) {
            try {
                ensureFile()
                if (mFile == null) {
                    if (retryCount == 0) {
                        Log.w(TAG, "ensureFile() returned null, attempting retry for gid: ${mInfo.gid}")
                        // 重置 mFile 状态，准备重试
                        Thread.sleep(RETRY_DELAY_MS)
                        retryCount++
                        continue
                    } else {
                        Log.e(TAG, "Failed to create .thumb file after retry, gid: ${mInfo.gid}")
                        return false
                    }
                }

                // 尝试写入文件
                var os: OutputStream? = null
                try {
                    os = mFile!!.openOutputStream()
                    if (os == null) {
                        Log.e(TAG, "openOutputStream() returned null for .thumb, gid: ${mInfo.gid}")
                        if (retryCount == 0) {
                            mFile = null // 重置状态
                            Thread.sleep(RETRY_DELAY_MS)
                            retryCount++
                            continue
                        }
                        return false
                    }
                    os.write(data)
                    os.flush()
                    Log.d(TAG, "Successfully saved .thumb to local file, gid: ${mInfo.gid}")
                    return true
                } finally {
                    IOUtils.closeQuietly(os)
                }
            } catch (e: IOException) {
                Log.e(TAG, "IOException while saving .thumb (attempt ${retryCount + 1}/2), gid: ${mInfo.gid}", e)
                if (retryCount == 0) {
                    mFile = null // 重置状态
                    Thread.sleep(RETRY_DELAY_MS)
                    retryCount++
                } else {
                    return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error while saving .thumb, gid: ${mInfo.gid}", e)
                return false
            }
        }
        return false
    }

    override fun get(): InputStreamPipe? {
        // Step 1: 优先使用已成功检查的 SMB pipe
        if (mSmbPipe != null) {
            Log.d(TAG, "Using SMB pipe for gid: ${mInfo.gid}")
            return mSmbPipe
        }

        // Step 2: 如果 SMB 检查尚未完成且未超时，等待一小段时间
        // 但不要等太久，避免阻塞加载
        if (!mSmbChecked && !mSmbFailed) {
            val elapsed = System.currentTimeMillis() - mSmbCheckStartTime
            if (elapsed < SMB_CHECK_TIMEOUT_MS) {
                // 最多等待剩余的超时时间
                val waitTime = minOf(50L, SMB_CHECK_TIMEOUT_MS - elapsed)
                try {
                    Thread.sleep(waitTime)
                } catch (e: InterruptedException) {
                    // 忽略中断
                }
            }
            // 再次检查 SMB pipe
            if (mSmbPipe != null) {
                Log.d(TAG, "SMB pipe available after wait for gid: ${mInfo.gid}")
                return mSmbPipe
            }
        }

        // Step 3: 降级到本地文件
        ensureFile()
        return if (mFile != null) {
            Log.d(TAG, "Using local file for gid: ${mInfo.gid}")
            UniFileInputStreamPipe(mFile)
        } else {
            Log.w(TAG, "No file available for gid: ${mInfo.gid}")
            null
        }
    }

    override fun remove() {
        // 尝试删除 SMB 中的文件（如果可能）
        // 注：当前 SmbFileHelper 不支持删除，后续可扩展

        // 删除本地文件
        if (mFile != null) {
            mFile!!.delete()
        }
    }
}
