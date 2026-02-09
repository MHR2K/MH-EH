package com.hippo.ehviewer.spider;

import android.content.Context;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.smb.SmbFileHelper;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.util.ExceptionUtils;
import com.hippo.unifile.UniFile;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Central repository for spider progress.
 * Layered strategy: memory -> DB -> local file -> SMB (async, 3s timeout).
 */
public class SpiderInfoRepository {

    private static final String SOURCE_DB = "DB";
    private static final String SOURCE_LOCAL = "LOCAL";
    private static final String SOURCE_SMB = "SMB";

    private final SpiderInfoDatabase database;
    private final LruCache<Long, SpiderInfo> memoryCache = new LruCache<>(200);
    private final ExecutorService smbExecutor = Executors.newFixedThreadPool(2);
    private final ConcurrentHashMap<Long, Future<SpiderInfo>> smbFutures = new ConcurrentHashMap<>();

    public SpiderInfoRepository(@NonNull Context context) {
        this.database = new SpiderInfoDatabase(context.getApplicationContext());
    }

    /**
     * Get spider info from DB only (fast, for initial list load).
     */
    @Nullable
    public SpiderInfo getFromDbOnly(long gid) {
        // Layer 1: memory cache
        SpiderInfo cached = memoryCache.get(gid);
        if (cached != null) {
            return cached;
        }

        // Layer 2: database
        SpiderInfo dbInfo = database.get(gid);
        if (dbInfo != null) {
            memoryCache.put(gid, dbInfo);
            return dbInfo;
        }

        return null;
    }

    /**
     * Get spider info with full fallback (DB -> local -> SMB).
     */
    @Nullable
    public SpiderInfo get(@NonNull GalleryInfo info) {
        return get(info, true);
    }

    /**
     * Get spider info. allowSmb controls whether to attempt SMB.
     */
    @Nullable
    public SpiderInfo get(@NonNull GalleryInfo info, boolean allowSmb) {
        // Layer 1: memory cache
        SpiderInfo cached = memoryCache.get(info.gid);
        if (cached != null) {
            return cached;
        }

        // Layer 2: database (prefer), but verify against local to avoid staleness
        SpiderInfo dbInfo = database.get(info.gid);
        if (dbInfo != null) {
            // Try local file: if it differs, prefer local and persist
            SpiderInfo localVerify = readFromLocal(info);
            if (localVerify != null &&
                    (localVerify.startPage != dbInfo.startPage || localVerify.pages != dbInfo.pages)) {
                save(localVerify, SOURCE_LOCAL);
                return localVerify;
            }
            memoryCache.put(info.gid, dbInfo);
            return dbInfo;
        }

        // Layer 3: local file
        SpiderInfo local = readFromLocal(info);
        if (local != null) {
            save(local, SOURCE_LOCAL);
            return local;
        }

        // Layer 4: SMB (async with timeout)
        if (allowSmb) {
            SpiderInfo smb = readFromSmbWithTimeout(info, 3, TimeUnit.SECONDS);
            if (smb != null) {
                save(smb, SOURCE_SMB);
                return smb;
            }
        }

        return null;
    }

    /**
     * Save spider info to memory + DB.
     */
    public void save(@NonNull SpiderInfo info, @NonNull String source) {
        memoryCache.put(info.gid, info);
        database.upsert(info, source);
    }

    /**
     * Invalidate (clear) cache for a specific gid.
     * Use this when you know the data has changed and needs to be reloaded.
     */
    public void invalidate(long gid) {
        memoryCache.remove(gid);
    }

    /**
     * Force refresh from database, bypassing memory cache.
     * Used after external updates (e.g., user finished reading).
     */
    @Nullable
    public SpiderInfo refreshFromDb(long gid) {
        memoryCache.remove(gid);  // Clear stale cache
        SpiderInfo dbInfo = database.get(gid);
        if (dbInfo != null) {
            memoryCache.put(gid, dbInfo);  // Update cache with fresh data
        }
        return dbInfo;
    }

    @Nullable
    private SpiderInfo readFromLocal(@NonNull GalleryInfo info) {
        try {
            UniFile dir = SpiderDen.getGalleryDownloadDir(info);
            if (dir != null && dir.isDirectory()) {
                UniFile file = dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME);
                return SpiderInfo.read(file);
            }
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
        }
        return null;
    }

    @Nullable
    private SpiderInfo readFromSmbWithTimeout(@NonNull GalleryInfo info, long timeout, TimeUnit unit) {
        try {
            Future<SpiderInfo> future = smbFutures.computeIfAbsent(info.gid, gid ->
                    smbExecutor.submit(() -> readFromSmb(info))
            );
            return future.get(timeout, unit);
        } catch (Throwable e) {
            return null;
        } finally {
            smbFutures.remove(info.gid);
        }
    }

    @Nullable
    private SpiderInfo readFromSmb(@NonNull GalleryInfo info) {
        // 注意：SmbFileHelper 现在只使用显式映射
        // 自动检测由 SpiderDen 处理
        // 此处仅当存在显式映射时才尝试 SMB 读取
        try {
            InputStreamPipe pipe = SmbFileHelper.getSmbFileInputStream(
                    info.gid,
                    SpiderQueen.SPIDER_INFO_FILENAME
            );
            if (pipe != null) {
                pipe.obtain();
                try {
                    SpiderInfo spiderInfo = SpiderInfo.read(pipe.open());
                    if (spiderInfo != null && spiderInfo.gid == info.gid &&
                            spiderInfo.token.equals(info.token)) {
                        return spiderInfo;
                    }
                } finally {
                    pipe.close();
                    pipe.release();
                }
            }
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
        }
        return null;
    }
}
