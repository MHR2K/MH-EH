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

import static org.junit.Assert.assertEquals;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.lib.yorozuya.collect.LongList;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FavCountStoreTest {

    private InMemoryPreferences mPrefs;

    @Before
    public void setUp() {
        mPrefs = new InMemoryPreferences();
        FavCountStore.init(mPrefs);
    }

    @Test
    public void testPutGetRemove() {
        assertEquals(-1, FavCountStore.get(1001L));
        FavCountStore.put(1001L, 42);
        assertEquals(42, FavCountStore.get(1001L));

        FavCountStore.remove(1001L);
        assertEquals(-1, FavCountStore.get(1001L));
    }

    @Test
    public void testRemoveRange() {
        FavCountStore.put(1001L, 10);
        FavCountStore.put(1002L, 20);
        FavCountStore.put(1003L, 30);

        LongList list = new LongList();
        list.add(1001L);
        list.add(1003L);

        FavCountStore.removeRange(list);

        assertEquals(-1, FavCountStore.get(1001L));
        assertEquals(20, FavCountStore.get(1002L));
        assertEquals(-1, FavCountStore.get(1003L));
    }

    @Test
    public void testClear() {
        FavCountStore.put(1001L, 10);
        FavCountStore.put(1002L, 20);

        FavCountStore.clear();

        assertEquals(-1, FavCountStore.get(1001L));
        assertEquals(-1, FavCountStore.get(1002L));
    }

    @Test
    public void testApplyToAndSaveFrom() {
        GalleryInfo info1 = new GalleryInfo();
        info1.gid = 2001L;
        info1.favoriteCount = 123;

        GalleryInfo info2 = new GalleryInfo();
        info2.gid = 2002L;
        info2.favoriteCount = 456;

        List<GalleryInfo> list = new ArrayList<>();
        list.add(info1);
        list.add(info2);

        FavCountStore.saveFrom(list);

        assertEquals(123, FavCountStore.get(2001L));
        assertEquals(456, FavCountStore.get(2002L));

        // Test applyTo
        GalleryInfo info3 = new GalleryInfo();
        info3.gid = 2001L;
        info3.favoriteCount = -1;

        List<GalleryInfo> applyList = new ArrayList<>();
        applyList.add(info3);

        FavCountStore.applyTo(applyList);
        assertEquals(123, info3.favoriteCount);
    }

    private static class InMemoryPreferences implements SharedPreferences {
        private final Map<String, Object> mMap = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(mMap);
        }

        @Nullable
        @Override
        public String getString(String key, @Nullable String defValue) {
            Object val = mMap.get(key);
            return val instanceof String ? (String) val : defValue;
        }

        @Nullable
        @Override
        public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
            return defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object val = mMap.get(key);
            return val instanceof Integer ? (Integer) val : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object val = mMap.get(key);
            return val instanceof Long ? (Long) val : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object val = mMap.get(key);
            return val instanceof Float ? (Float) val : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object val = mMap.get(key);
            return val instanceof Boolean ? (Boolean) val : defValue;
        }

        @Override
        public boolean contains(String key) {
            return mMap.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new EditorImpl();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        private class EditorImpl implements Editor {
            private final Map<String, Object> mModified = new HashMap<>();
            private boolean mClear = false;

            @Override
            public Editor putString(String key, @Nullable String value) {
                mModified.put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, @Nullable Set<String> values) {
                mModified.put(key, values);
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                mModified.put(key, value);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                mModified.put(key, value);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                mModified.put(key, value);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                mModified.put(key, value);
                return this;
            }

            @Override
            public Editor remove(String key) {
                mModified.put(key, this);
                return this;
            }

            @Override
            public Editor clear() {
                mClear = true;
                return this;
            }

            @Override
            public boolean commit() {
                apply();
                return true;
            }

            @Override
            public void apply() {
                if (mClear) {
                    mMap.clear();
                }
                for (Map.Entry<String, Object> entry : mModified.entrySet()) {
                    if (entry.getValue() == this) {
                        mMap.remove(entry.getKey());
                    } else {
                        mMap.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
    }
}
