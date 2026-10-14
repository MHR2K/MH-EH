/*
 * Copyright 2026 Hippo Seven
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

package com.hippo.ehviewer.util;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.Settings;
import com.hippo.lib.yorozuya.FileUtils;
import com.hippo.unifile.UniFile;
import com.hippo.util.IoThreadPoolExecutor;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StorageStatsHelper {

    private static final String TAG = "StorageStatsHelper";

    private StorageStatsHelper() {}

    public static class StorageStats {
        public long deviceTotalBytes = -1;
        public long deviceAvailableBytes = -1;
        public long downloadTotalBytes = 0;
        public int downloadGalleryCount = 0;
        public long thumbnailCacheBytes = 0;
        @NonNull
        public String downloadPathDisplay = "";
        public boolean success = false;
    }

    public interface Callback {
        void onCalculated(@NonNull StorageStats stats);
    }

    /**
     * Asynchronously calculate storage statistics on the background IO thread pool.
     * The callback is always invoked on the main UI thread.
     */
    public static AtomicBoolean calculateStorageStatsAsync(@NonNull Context context, @NonNull Callback callback) {
        final Context appContext = context.getApplicationContext();
        final AtomicBoolean cancelFlag = new AtomicBoolean(false);
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        IoThreadPoolExecutor.getInstance().execute(() -> {
            StorageStats stats = calculateStorageStats(appContext, cancelFlag);
            if (!cancelFlag.get()) {
                mainHandler.post(() -> {
                    if (!cancelFlag.get()) {
                        callback.onCalculated(stats);
                    }
                });
            }
        });

        return cancelFlag;
    }

    /**
     * Synchronously calculate storage statistics. Should be called on background thread.
     */
    @NonNull
    public static StorageStats calculateStorageStats(@NonNull Context context, @Nullable AtomicBoolean cancelFlag) {
        StorageStats stats = new StorageStats();
        try {
            UniFile downloadDir = Settings.getDownloadLocation();
            if (downloadDir != null) {
                stats.downloadPathDisplay = getReadablePath(downloadDir);
            }

            // 1. Device Storage
            computeDeviceStorage(context, stats, downloadDir);

            if (cancelFlag != null && cancelFlag.get()) {
                return stats;
            }

            // 2. Download Location Storage
            computeDownloadStorage(downloadDir, stats, cancelFlag);

            if (cancelFlag != null && cancelFlag.get()) {
                return stats;
            }

            // 3. Thumbnail / App Cache Storage
            computeThumbnailCache(context, stats, cancelFlag);

            stats.success = true;
        } catch (Throwable t) {
            Log.w(TAG, "Error calculating storage stats", t);
        }
        return stats;
    }

    /**
     * Compute device total & available storage using StatFs.
     */
    private static void computeDeviceStorage(Context context, StorageStats stats, @Nullable UniFile downloadDir) {
        File path = null;
        try {
            if (downloadDir != null && downloadDir.getUri() != null
                    && "file".equalsIgnoreCase(downloadDir.getUri().getScheme())) {
                String p = downloadDir.getUri().getPath();
                if (p != null) {
                    File f = new File(p);
                    if (f.exists()) {
                        path = f;
                    }
                }
            }
            if (path == null) {
                path = context.getFilesDir();
            }
            if (path == null || !path.exists()) {
                path = Environment.getDataDirectory();
            }
            if (path != null && path.exists()) {
                StatFs stat = new StatFs(path.getPath());
                stats.deviceTotalBytes = stat.getTotalBytes();
                stats.deviceAvailableBytes = stat.getAvailableBytes();
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to compute StatFs for path: " + path, t);
        }

        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            stats.deviceTotalBytes = stat.getTotalBytes();
            stats.deviceAvailableBytes = stat.getAvailableBytes();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Compute download location storage size and gallery folder count.
     */
    private static void computeDownloadStorage(@Nullable UniFile downloadDir, StorageStats stats,
                                               @Nullable AtomicBoolean cancelFlag) {
        if (downloadDir == null || !downloadDir.exists() || !downloadDir.isDirectory()) {
            return;
        }

        // Fast-path for RawFile (file:// URI)
        if (downloadDir.getUri() != null && "file".equalsIgnoreCase(downloadDir.getUri().getScheme())) {
            String path = downloadDir.getUri().getPath();
            if (path != null) {
                File rawDir = new File(path);
                if (rawDir.exists() && rawDir.isDirectory()) {
                    computeRawDownloadStorage(rawDir, stats, cancelFlag);
                    return;
                }
            }
        }

        // SAF / TreeDocumentFile path
        UniFile[] subDirs = downloadDir.listFiles();
        if (subDirs == null) {
            return;
        }

        int galleryCount = 0;
        long totalBytes = 0;

        for (UniFile item : subDirs) {
            if (cancelFlag != null && cancelFlag.get()) {
                return;
            }
            if (item == null) continue;

            if (item.isDirectory()) {
                galleryCount++;
                totalBytes += getUniDirectorySize(item, cancelFlag);
            } else if (item.isFile()) {
                totalBytes += Math.max(0L, item.length());
            }
        }

        stats.downloadGalleryCount = galleryCount;
        stats.downloadTotalBytes = totalBytes;
    }

    private static long getUniDirectorySize(UniFile dir, @Nullable AtomicBoolean cancelFlag) {
        if (cancelFlag != null && cancelFlag.get()) return 0;
        long size = 0;
        UniFile[] children = dir.listFiles();
        if (children != null) {
            for (UniFile child : children) {
                if (cancelFlag != null && cancelFlag.get()) return size;
                if (child == null) continue;
                if (child.isDirectory()) {
                    size += getUniDirectorySize(child, cancelFlag);
                } else {
                    size += Math.max(0L, child.length());
                }
            }
        }
        return size;
    }

    private static void computeRawDownloadStorage(File rawDir, StorageStats stats,
                                                  @Nullable AtomicBoolean cancelFlag) {
        File[] files = rawDir.listFiles();
        if (files == null) return;

        int galleryCount = 0;
        long totalBytes = 0;

        for (File file : files) {
            if (cancelFlag != null && cancelFlag.get()) return;
            if (file == null) continue;

            if (file.isDirectory()) {
                galleryCount++;
                totalBytes += getRawDirectorySize(file, cancelFlag);
            } else if (file.isFile()) {
                totalBytes += Math.max(0L, file.length());
            }
        }

        stats.downloadGalleryCount = galleryCount;
        stats.downloadTotalBytes = totalBytes;
    }

    private static long getRawDirectorySize(File dir, @Nullable AtomicBoolean cancelFlag) {
        if (cancelFlag != null && cancelFlag.get()) return 0;
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (cancelFlag != null && cancelFlag.get()) return size;
                if (file.isDirectory()) {
                    size += getRawDirectorySize(file, cancelFlag);
                } else {
                    size += Math.max(0L, file.length());
                }
            }
        }
        return size;
    }

    /**
     * Compute thumbnail disk cache size.
     */
    private static void computeThumbnailCache(Context context, StorageStats stats,
                                              @Nullable AtomicBoolean cancelFlag) {
        long size = 0;
        File thumbDir = new File(context.getCacheDir(), "thumb");
        if (thumbDir.exists() && thumbDir.isDirectory()) {
            size += getRawDirectorySize(thumbDir, cancelFlag);
        }
        stats.thumbnailCacheBytes = size;
    }

    /**
     * Clear thumbnail cache on background thread and invoke callback on UI thread.
     */
    public static void clearThumbnailCache(@NonNull Context context, @Nullable Runnable onCleared) {
        final Context appContext = context.getApplicationContext();
        IoThreadPoolExecutor.getInstance().execute(() -> {
            try {
                EhApplication.getConaco(appContext).getBeerBelly().clear();
                File thumbDir = new File(appContext.getCacheDir(), "thumb");
                if (thumbDir.exists()) {
                    FileUtils.delete(thumbDir);
                    thumbDir.mkdirs();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to clear thumbnail cache", t);
            }
            if (onCleared != null) {
                new Handler(Looper.getMainLooper()).post(onCleared);
            }
        });
    }

    /**
     * Format a UniFile to a clean, user-friendly display path.
     */
    @NonNull
    public static String getReadablePath(@Nullable UniFile file) {
        if (file == null) {
            return "";
        }
        Uri uri = file.getUri();
        if (uri == null) {
            return "";
        }
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath() != null ? uri.getPath() : "";
        }
        // content URI
        try {
            String path = Uri.decode(uri.toString());
            int treeIdx = path.indexOf("/tree/");
            if (treeIdx != -1) {
                String sub = path.substring(treeIdx + "/tree/".length());
                int colonIdx = sub.indexOf(':');
                if (colonIdx != -1) {
                    return "/" + sub.substring(colonIdx + 1);
                }
                return "/" + sub;
            }
            int docIdx = path.indexOf("/document/");
            if (docIdx != -1) {
                String sub = path.substring(docIdx + "/document/".length());
                int colonIdx = sub.indexOf(':');
                if (colonIdx != -1) {
                    return "/" + sub.substring(colonIdx + 1);
                }
                return "/" + sub;
            }
            return uri.getPath() != null ? uri.getPath() : uri.toString();
        } catch (Throwable t) {
            return uri.toString();
        }
    }
}
