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

import androidx.annotation.Nullable;

import com.hippo.lib.yorozuya.collect.LongList;

/**
 * 新版本画廊信息持久化存储（SharedPreferences，以旧画廊 GID 为 key，value 为最新画廊 URL）
 */
public class NewVersionStore {

    private static final String PREF_NAME = "new_version_info";
    private static SharedPreferences sPrefs;

    public static void init(Context context) {
        if (context == null) return;
        init(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE));
    }

    public static void init(SharedPreferences prefs) {
        sPrefs = prefs;
    }

    public static boolean hasNewVersion(long gid) {
        if (sPrefs == null) return false;
        return sPrefs.contains(String.valueOf(gid));
    }

    @Nullable
    public static String get(long gid) {
        if (sPrefs == null) return null;
        return sPrefs.getString(String.valueOf(gid), null);
    }

    public static void put(long gid, String versionUrl) {
        if (sPrefs == null || versionUrl == null) return;
        sPrefs.edit().putString(String.valueOf(gid), versionUrl).apply();
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
}
