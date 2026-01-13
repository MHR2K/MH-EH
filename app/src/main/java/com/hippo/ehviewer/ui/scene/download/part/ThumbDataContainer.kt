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

    private fun ensureFile() {
        if (mFile == null) {
            val dir = SpiderDen.getGalleryDownloadDir(mInfo)
            if (dir != null && dir.isDirectory()) {
                mFile = dir.createFile(".thumb")
            }
        }
    }

    override fun isEnabled(): Boolean {
        // 优先检查 SMB
        if (mSmbPipe == null && mFile == null) {
            // 尝试获取 SMB 文件
            mSmbPipe = SmbFileHelper.getSmbFileInputStream(
                mInfo.gid,
                ".thumb",
                mInfo
            )
        }
        if (mSmbPipe != null) {
            return true
        }

        // 检查本地文件
        ensureFile()
        return mFile != null
    }

    override fun onUrlMoved(requestUrl: String?, responseUrl: String?) {
    }

    override fun save(
        `is`: InputStream,
        length: Long,
        mediaType: String?,
        notify: ProgressNotifier?
    ): Boolean {
        // 从输入流读取数据
        val baos = ByteArrayOutputStream()
        try {
            IOUtils.copy(`is`, baos)
            val data = baos.toByteArray()

            // Step 1: 尝试保存到 SMB
            if (SmbFileHelper.writeSmbFile(mInfo.gid, ".thumb", data, mInfo)) {
                return true
            }

            // Step 2: 降级到本地文件
            ensureFile()
            if (mFile == null) {
                return false
            }

            var os: OutputStream? = null
            try {
                os = mFile!!.openOutputStream()
                os.write(data)
                os.flush()
                return true
            } finally {
                IOUtils.closeQuietly(os)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        }
    }

    override fun get(): InputStreamPipe? {
        // Step 1: 尝试从 SMB 获取
        if (mSmbPipe == null) {
            mSmbPipe = SmbFileHelper.getSmbFileInputStream(
                mInfo.gid,
                ".thumb",
                mInfo
            )
        }
        if (mSmbPipe != null) {
            return mSmbPipe
        }

        // Step 2: 降级到本地文件
        ensureFile()
        return if (mFile != null) {
            UniFileInputStreamPipe(mFile)
        } else {
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
