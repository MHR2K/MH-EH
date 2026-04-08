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

import android.content.Context
import android.util.Log
import com.hippo.conaco.DataContainer
import com.hippo.conaco.ProgressNotifier
import com.hippo.ehviewer.EhApplication
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 缩略图数据容器
 * 支持 SMB 和本地存储
 * 优化：优先使用本地文件，避免 ANR
 */
@Suppress("UNUSED_VARIABLE", "ConditionAlwaysTrue", "UNNECESSARY_SAFE_CALL")
class ThumbDataContainer(
    private val mContext: Context,
    private val mInfo: DownloadInfo
) : DataContainer {
    private var mFile: UniFile? = null
    private var mSmbPipe: InputStreamPipe? = null
    private val mSmbCheckStarted = AtomicBoolean(false)  // 使用原子布尔值线程安全标记
    @Volatile private var mSmbChecked = false           // 标记 SMB 检查是否已完成
    @Volatile private var mSmbFailed = false             // 标记 SMB 检查失败，避免重复尝试
    private val mSmbCheckStartTime = System.currentTimeMillis()  // SMB 检查开始时间

    companion object {
        private const val TAG = "ThumbDataContainer"
        private const val RETRY_DELAY_MS = 100L
        // 改进：增加 SMB 检查超时时间，提高稳定性（原 200ms -> 2000ms）
        private const val SMB_CHECK_TIMEOUT_MS = 2000L  // SMB 检查超时时间（2秒）
        // 改进：增加 isEnabled() 中的最大等待时间（原 50ms -> 500ms）
        private const val SMB_WAIT_IN_ENABLED_MS = 500L // isEnabled() 中最大等待时间（500ms）
    }

    private fun ensureFile(): Boolean {
        if (mFile == null) {
            try {
                val dir = SpiderDen.getGalleryDownloadDir(mInfo)
                if (dir == null) {
                    Log.w(TAG, "Failed to get download directory for gid: ${mInfo.gid}")
                    return false
                }
                if (!dir.isDirectory()) {
                    Log.w(TAG, "Download directory is not a directory for gid: ${mInfo.gid}")
                    return false
                }
                mFile = dir.createFile(".thumb")
                if (mFile == null) {
                    Log.w(TAG, "createFile() returned null for .thumb, gid: ${mInfo.gid}")
                    return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in ensureFile() for gid: ${mInfo.gid}", e)
                return false
            }
        }
        return true
    }

    override fun isEnabled(): Boolean {
        // 策略：优先使用本地文件，避免主线程等待导致 ANR
        // Step 1: 先检查本地文件是否存在
        // 注意：ensureFile() 成功时会设置 mFile，所以只需检查 ensureFile() 返回值
        if (ensureFile()) {
            return true
        }

        // Step 2: 异步启动 SMB 检查（如果尚未启动且未失败）
        if (mSmbCheckStarted.compareAndSet(false, true)) {
            Thread {
                try {
                    // 在后台线程中尝试获取 SMB 文件
                    val smbPipe = SmbFileHelper.getSmbFileInputStream(mInfo.gid, ".thumb")
                    if (smbPipe != null) {
                        mSmbPipe = smbPipe
                        Log.d(TAG, "Successfully got SMB pipe for gid: ${mInfo.gid}")
                    } else {
                        Log.d(TAG, "SMB pipe is null for gid: ${mInfo.gid}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get SMB file input stream for gid: ${mInfo.gid}", e)
                    mSmbFailed = true
                } finally {
                    mSmbChecked = true
                }
            }.start()
        }

        // Step 3: 如果 SMB 检查已完成且成功使用 SMB pipe
        if (mSmbChecked && mSmbPipe != null) {
            return true
        }

        // Step 4: 如果 SMB 检查超时（超过200ms），标记为失败
        if (!mSmbChecked && (System.currentTimeMillis() - mSmbCheckStartTime) > SMB_CHECK_TIMEOUT_MS) {
            Log.w(TAG, "SMB check timeout for gid: ${mInfo.gid}")
            mSmbFailed = true
            mSmbChecked = true
        }

        // Step 5: 返回本地文件检查结果
        // 注意：ensureFile() 成功时会设置 mFile，所以无需再次检查 mFile != null
        val result = ensureFile()
        if (!result) {
            Log.d(TAG, "isEnabled() returning false for gid: ${mInfo.gid}")
        }
        return result
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
        var savedToFile = false
        when (storageLocation) {
            StorageDetector.StorageLocation.SMB -> {
                // 漫画在SMB，尝试保存到SMB
                try {
                    if (SmbFileHelper.writeSmbFile(mInfo.gid, ".thumb", data)) {
                        Log.d(TAG, "Successfully saved .thumb to SMB, gid: ${mInfo.gid}")
                        savedToFile = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save .thumb to SMB, gid: ${mInfo.gid}", e)
                }
                // SMB保存失败，降级到本地
                if (!savedToFile) {
                    Log.w(TAG, "SMB save failed, falling back to local for gid: ${mInfo.gid}")
                    savedToFile = saveToLocalFile(data)
                }
            }
            StorageDetector.StorageLocation.BOTH -> {
                // 漫画同时存在，同时保存到SMB和本地
                val savedToSmb = SmbFileHelper.writeSmbFile(mInfo.gid, ".thumb", data)
                val savedToLocal = saveToLocalFile(data)
                Log.d(TAG, "BOTH: SMB=$savedToSmb, Local=$savedToLocal for gid: ${mInfo.gid}")
                savedToFile = savedToSmb || savedToLocal
            }
            StorageDetector.StorageLocation.LOCAL -> {
                // 漫画在本地，直接保存到本地
                Log.d(TAG, "Manga is local, saving .thumb to local file, gid: ${mInfo.gid}")
                savedToFile = saveToLocalFile(data)
            }
            StorageDetector.StorageLocation.UNKNOWN -> {
                // 未知位置，直接保存到本地
                Log.w(TAG, "Storage location unknown for gid: ${mInfo.gid}, saving to local")
                savedToFile = saveToLocalFile(data)
            }
        }

        return savedToFile
    }

    /**
     * 保存数据到本地文件（带重试机制）
     */
    @Suppress("UNNECESSARY_SAFE_CALL")
    private fun saveToLocalFile(data: ByteArray): Boolean {
        var retryCount = 0
        while (retryCount < 2) {
            try {
                if (!ensureFile()) {
                    if (retryCount == 0) {
                        Log.w(TAG, "ensureFile() failed, attempting retry for gid: ${mInfo.gid}")
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

    @Suppress("UNNECESSARY_SAFE_CALL")
    override fun get(): InputStreamPipe? {
        // Step 1: 优先使用 SMB pipe（如果已准备好）
        if (mSmbPipe != null) {
            Log.d(TAG, "Using SMB pipe for gid: ${mInfo.gid}")
            return mSmbPipe
        }

        // Step 2: 如果 SMB 检查尚未完成，等待一小段时间（最多50ms）
        if (!mSmbChecked) {
            val elapsed = System.currentTimeMillis() - mSmbCheckStartTime
            val remainingTime = SMB_CHECK_TIMEOUT_MS - elapsed
            if (remainingTime > 0 && remainingTime <= SMB_WAIT_IN_ENABLED_MS) {
                try {
                    Thread.sleep(remainingTime)
                } catch (e: InterruptedException) {
                    // 忽略中断
                }
                // 等待后再次检查 SMB pipe
                mSmbPipe?.let {
                    Log.d(TAG, "SMB pipe available after wait for gid: ${mInfo.gid}")
                    return it
                }
            }
        }

        // Step 3: 检查本地文件（调用 ensureFile() 设置 mFile，忽略返回值）
        ensureFile()
        return mFile?.let {
            Log.d(TAG, "Using local file for gid: ${mInfo.gid}")
            UniFileInputStreamPipe(it)
        }
    }

    override fun remove() {
        // 删除本地文件
        if (mFile != null) {
            mFile!!.delete()
        }
    }
}
