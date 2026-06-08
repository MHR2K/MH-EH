package com.hippo.ehviewer.ui.scene.download.part;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.smb.SmbStorageTracker;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.unifile.UniFile;
import com.hippo.util.IoThreadPoolExecutor;

import java.util.HashMap;
import java.util.Map;

/**
 * 判定下载项是存于本地还是 SMB。
 * 快速、无网络 I/O：只基于本地目录是否存在代表文件 + 是否存在 SMB 映射。
 */
public final class StorageDetector {

    private static final String TAG = "StorageDetector";

    private StorageDetector() {}

    public enum StorageLocation {
        LOCAL,      // 仅本地存在
        SMB,        // 仅 SMB（存在映射）
        BOTH,       // 同时存在（迁移残留或手工拷贝）
        UNKNOWN     // 无法判断
    }

    // 内存缓存
    @Nullable
    private static Map<Long, StorageLocation> sCache;

    // 预计算状态回调
    public interface PreloadCallback {
        void onPreloadComplete();
    }

    @Nullable
    private static PreloadCallback sPreloadCallback;

    /**
     * 基于 DownloadInfo 快速判定（原始版本，每次查库）。
     */
    @NonNull
    public static StorageLocation detect(@NonNull DownloadInfo info) {
        boolean smbMapped = SmbStorageTracker.INSTANCE.isOnSmb(info.gid);

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

    /**
     * 带缓存的检测版本，优先从内存缓存读取。
     */
    @NonNull
    public static StorageLocation detectCached(@NonNull DownloadInfo info) {
        if (sCache != null) {
            StorageLocation cached = sCache.get(info.gid);
            if (cached != null) {
                return cached;
            }
            // 缓存 miss：回退到真实检测，结果回填缓存
            StorageLocation detected = detect(info);
            sCache.put(info.gid, detected);
            return detected;
        }
        // 缓存不存在，fallback 到原始方法
        return detect(info);
    }

    /**
     * 异步预计算所有下载项的存储位置。
     * @param context 上下文
     * @param callback 预计算完成回调（可选）
     */
    public static void preloadAsync(@NonNull Context context, @Nullable PreloadCallback callback) {
        sPreloadCallback = callback;
        new AsyncTask<Void, Void, Map<Long, StorageLocation>>() {
            @Override
            protected Map<Long, StorageLocation> doInBackground(Void... voids) {
                return computeAll();
            }

            @Override
            protected void onPostExecute(Map<Long, StorageLocation> result) {
                if (sCache != null) {
                    // 合并：保留 updateCache 等运行时设置的值
                    for (Map.Entry<Long, StorageLocation> entry : sCache.entrySet()) {
                        if (!result.containsKey(entry.getKey())) {
                            result.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
                sCache = result;
                Log.d(TAG, "预计算完成，缓存 " + result.size() + " 项");
                if (sPreloadCallback != null) {
                    sPreloadCallback.onPreloadComplete();
                    sPreloadCallback = null;
                }
            }
        }.executeOnExecutor(IoThreadPoolExecutor.getInstance());
    }

    /**
     * 异步预计算（无回调版本）。
     */
    public static void preloadAsync(@NonNull Context context) {
        preloadAsync(context, null);
    }

    /**
     * 同步预计算所有下载项的存储位置（在后台线程调用）。
     */
    @NonNull
    private static Map<Long, StorageLocation> computeAll() {
        long startTime = System.currentTimeMillis();

        // 1. 批量加载所有 dirname 映射
        Map<Long, String> dirnameMap = EhDB.getAllDownloadDirnameMap();
        UniFile downloadRoot = Settings.getDownloadLocation();

        // 2. 获取所有 SMB 映射
        // SmbStorageTracker 的 isOnSmb 是单个查询，无法批量优化
        // 但至少减少了数据库查询次数

        Map<Long, StorageLocation> result = new HashMap<>();

        // 3. 遍历所有下载项计算存储位置
        // 注意：这里需要获取所有下载项的 gid
        // 由于 DownloadManager 的列表可能很大，我们直接遍历 dirnameMap 的 key
        // 对于 SMB 映射的项，需要单独处理

        // 先处理有 dirname 记录的项
        for (Map.Entry<Long, String> entry : dirnameMap.entrySet()) {
            long gid = entry.getKey();
            String dirname = entry.getValue();

            boolean smbMapped = SmbStorageTracker.INSTANCE.isOnSmb(gid);
            boolean localPresent = false;

            if (downloadRoot != null && dirname != null) {
                UniFile dir = downloadRoot.subFile(dirname);
                localPresent = dir != null && dir.isDirectory();
            }

            if (smbMapped && localPresent) {
                result.put(gid, StorageLocation.BOTH);
            } else if (smbMapped) {
                result.put(gid, StorageLocation.SMB);
            } else if (localPresent) {
                result.put(gid, StorageLocation.LOCAL);
            } else {
                result.put(gid, StorageLocation.UNKNOWN);
            }
        }

        // 4. 处理只有 SMB 映射但没有 dirname 的项
        // 这需要获取所有 SMB 映射的 gid，但 SmbStorageTracker 没有提供批量方法
        // 暂时跳过，这些项会在 detectCached 时 fallback 到 detect 方法

        long elapsed = System.currentTimeMillis() - startTime;
        Log.d(TAG, "预计算耗时: " + elapsed + "ms, 计算 " + result.size() + " 项");

        return result;
    }

    /**
     * 使缓存失效（在文件变化时调用）。
     */
    public static void invalidateCache() {
        sCache = null;
        Log.d(TAG, "缓存已失效");
    }

    /**
     * 更新缓存中单个条目的存储位置。
     * @param gid 漫画 ID
     * @param location 新的存储位置
     */
    public static void updateCache(long gid, StorageLocation location) {
        if (sCache != null) {
            sCache.put(gid, location);
            Log.d(TAG, "更新缓存: gid=" + gid + ", location=" + location);
        }
    }

    /**
     * 检查缓存是否可用。
     */
    public static boolean isCacheReady() {
        return sCache != null;
    }
}
