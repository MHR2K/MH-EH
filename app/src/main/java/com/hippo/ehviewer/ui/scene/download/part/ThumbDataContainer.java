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

package com.hippo.ehviewer.ui.scene.download.part;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.hippo.conaco.DataContainer;
import com.hippo.conaco.ProgressNotifier;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.io.UniFileInputStreamPipe;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.unifile.UniFile;
import com.hippo.ehviewer.smb.SmbMappingStore;
import com.hippo.ehviewer.smb.Client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 缩略图数据容器
 */
public class ThumbDataContainer implements DataContainer {

    private final DownloadInfo mInfo;
    @Nullable
    private UniFile mFile;

    public ThumbDataContainer(@NonNull DownloadInfo info) {
        mInfo = info;
    }

    private void ensureFile() {
        if (mFile == null) {
            UniFile dir = SpiderDen.getGalleryDownloadDir(mInfo);
            if (dir != null && dir.isDirectory()) {
                mFile = dir.createFile(".thumb");
            }
        }
    }

    @Override
    public boolean isEnabled() {
        ensureFile();
        if (mFile != null) return true;
        // 如果已迁移到 SMB，仍然启用以便从 SMB 读取 .thumb
        SmbMappingStore.Mapping mapping = SmbMappingStore.INSTANCE.get(mInfo.gid);
        return mapping != null;
    }

    @Override
    public void onUrlMoved(String requestUrl, String responseUrl) {
    }

    @Override
    public boolean save(InputStream is, long length, String mediaType, ProgressNotifier notify) {
        ensureFile();
        if (mFile == null) {
            return false;
        }

        OutputStream os = null;
        try {
            os = mFile.openOutputStream();
            IOUtils.copy(is, os);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            IOUtils.closeQuietly(os);
        }
    }

    @Override
    public InputStreamPipe get() {
        ensureFile();
        if (mFile != null) {
            return new UniFileInputStreamPipe(mFile);
        }
        // 本地不存在，尝试从 SMB 读取 .thumb（只读）
        SmbMappingStore.Mapping mapping = SmbMappingStore.INSTANCE.get(mInfo.gid);
        if (mapping == null) return null;
        String base = mapping.getBasePathInShare();
        if (base == null) base = "";
        String normBase = base.replace('/', '\\').replaceAll("^\\\\+|\\\\+$", "");
        String rel = normBase.isEmpty() ? ".thumb" : (normBase + "\\" + ".thumb");
        Client.Target target = new Client.Target(mapping.getAuthority(), mapping.getShare(), rel);
        try {
            final InputStream is = Client.INSTANCE.openInputStream(target);
            return new InputStreamPipe() {
                private InputStream mIs;
                @Override public void obtain() { /* no-op */ }
                @Override public void release() { /* no-op */ }
                @Override public InputStream open() { mIs = is; return mIs; }
                @Override public void close() { IOUtils.closeQuietly(mIs); mIs = null; }
            };
        } catch (Throwable ignore) {
            return null;
        }
    }

    @Override
    public void remove() {
        if (mFile != null) {
            mFile.delete();
        }
    }
}
