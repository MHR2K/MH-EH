package com.hippo.ehviewer.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.List;

/**
 * 收藏数持久化存储（SharedPreferences，按 GID 为 key）
 */
public class FavCountStore {

    private static final String PREF_NAME = "fav_count";
    private static SharedPreferences sPrefs;

    public static void init(Context context) {
        sPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static int get(long gid) {
        if (sPrefs == null) return -1;
        return sPrefs.getInt(String.valueOf(gid), -1);
    }

    public static void put(long gid, int favCount) {
        if (sPrefs == null) return;
        sPrefs.edit().putInt(String.valueOf(gid), favCount).apply();
    }

    /**
     * 批量应用已存储的收藏数到列表（不触发 UI 刷新，仅填充内存字段）
     */
    public static void applyTo(List<? extends GalleryInfo> items) {
        if (sPrefs == null || items == null) return;
        for (GalleryInfo gi : items) {
            int stored = sPrefs.getInt(String.valueOf(gi.gid), -1);
            if (stored >= 0) {
                gi.favoriteCount = stored;
            }
        }
    }

    /**
     * 批量保存列表中的收藏数到磁盘
     */
    public static void saveFrom(List<? extends GalleryInfo> items) {
        if (sPrefs == null || items == null) return;
        SharedPreferences.Editor editor = sPrefs.edit();
        for (GalleryInfo gi : items) {
            if (gi.favoriteCount >= 0) {
                editor.putInt(String.valueOf(gi.gid), gi.favoriteCount);
            }
        }
        editor.apply();
    }
}
