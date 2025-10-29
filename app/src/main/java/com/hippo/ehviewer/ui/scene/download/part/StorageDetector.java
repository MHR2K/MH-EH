package com.hippo.ehviewer.ui.scene.download.part;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.smb.SmbMappingStore;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.unifile.UniFile;

/**
 * 判定下载项是存于本地还是 SMB。
 * 快速、无网络 I/O：只基于本地目录是否存在代表文件 + 是否存在 SMB 映射。
 */
public final class StorageDetector {

    private StorageDetector() {}

    public enum StorageLocation {
        LOCAL,      // 仅本地存在
        SMB,        // 仅 SMB（存在映射）
        BOTH,       // 同时存在（迁移残留或手工拷贝）
        UNKNOWN     // 无法判断
    }

    /**
     * 基于 DownloadInfo 快速判定。
     */
    @NonNull
    public static StorageLocation detect(@NonNull DownloadInfo info) {
        boolean smbMapped = SmbMappingStore.INSTANCE.get(info.gid) != null;

        // 构造最小 GalleryInfo 以复用本地目录定位逻辑
        GalleryInfo gi = new GalleryInfo();
        gi.gid = info.gid;
        gi.title = info.title;
        gi.titleJpn = info.titleJpn;

        boolean localPresent = false;
        @Nullable UniFile localDir = SpiderDen.getGalleryDownloadDir(gi);
        if (localDir != null && localDir.isDirectory()) {
            localPresent = true;
        }

        if (smbMapped && localPresent) return StorageLocation.BOTH;
        if (smbMapped) return StorageLocation.SMB;
        if (localPresent) return StorageLocation.LOCAL;
        return StorageLocation.UNKNOWN;
    }
}
