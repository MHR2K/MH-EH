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
import android.content.SharedPreferences;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.lib.yorozuya.collect.LongList;

import java.util.List;

/**
 * 收藏数持久化存储（SharedPreferences，按 GID 为 key）
 */
public class FavCountStore {

    private static final String PREF_NAME = "fav_count";
    private static SharedPreferences sPrefs;

    public static void init(Context context) {
        if (context == null) return;
        init(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE));
    }

    public static void init(SharedPreferences prefs) {
        sPrefs = prefs;
    }

    public static int get(long gid) {
        if (sPrefs == null) return -1;
        return sPrefs.getInt(String.valueOf(gid), -1);
    }

    public static void put(long gid, int favCount) {
        if (sPrefs == null) return;
        sPrefs.edit().putInt(String.valueOf(gid), favCount).apply();
    }

    public static void remove(long gid) {
        if (sPrefs == null) return;
        sPrefs.edit().remove(String.valueOf(gid)).apply();
    }

    public static void removeRange(LongList gids) {
        if (sPrefs == null || gids == null || gids.isEmpty()) return;
        SharedPreferences.Editor editor = sPrefs.edit();
        for (int i = 0, n = gids.size(); i < n; i++) {
            editor.remove(String.valueOf(gids.get(i)));
        }
        editor.apply();
    }

    public static void clear() {
        if (sPrefs == null) return;
        sPrefs.edit().clear().apply();
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
