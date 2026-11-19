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

package com.hippo.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class ContentLayoutTest {

    private static class DummyItem implements Parcelable {
        int id;

        DummyItem(int id) {
            this.id = id;
        }

        protected DummyItem(Parcel in) {
            id = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(id);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<DummyItem> CREATOR = new Creator<DummyItem>() {
            @Override
            public DummyItem createFromParcel(Parcel in) {
                return new DummyItem(in);
            }

            @Override
            public DummyItem[] newArray(int size) {
                return new DummyItem[size];
            }
        };
    }

    private static class TestHelper extends ContentLayout.ContentHelper<DummyItem> {
        boolean firstRefreshCalled = false;

        TestHelper() {}

        @Override
        protected void getPageData(int taskId, int type, int page) {}

        @Override
        protected void getPageData(int taskId, int type, int page, String append) {}

        @Override
        protected void getExPageData(int pageAction, int taskId, int page) {}

        @Override
        protected Context getContext() {
            return null;
        }

        @Override
        protected void notifyDataSetChanged() {}

        @Override
        protected void notifyItemRangeRemoved(int positionStart, int itemCount) {}

        @Override
        protected void notifyItemRangeInserted(int positionStart, int itemCount) {}

        @Override
        protected boolean isDuplicate(DummyItem d1, DummyItem d2) {
            return d1.id == d2.id;
        }

        @Override
        public void firstRefresh() {
            firstRefreshCalled = true;
        }
    }

    @Test
    public void testSubscriptionPaginationEndPageIncrement() throws Exception {
        TestHelper helper = new TestHelper();

        Method onTypeRefresh = ContentLayout.ContentHelper.class.getDeclaredMethod("onTypeRefresh", int.class, int.class, List.class);
        onTypeRefresh.setAccessible(true);

        Method onTypeNextPage = ContentLayout.ContentHelper.class.getDeclaredMethod("onTypeNextPage", int.class, int.class, List.class);
        onTypeNextPage.setAccessible(true);

        Method pageDividerMatchesWindow = ContentLayout.ContentHelper.class.getDeclaredMethod("pageDividerMatchesWindow");
        pageDividerMatchesWindow.setAccessible(true);

        Field mEndPageField = ContentLayout.ContentHelper.class.getDeclaredField("mEndPage");
        mEndPageField.setAccessible(true);

        Field mStartPageField = ContentLayout.ContentHelper.class.getDeclaredField("mStartPage");
        mStartPageField.setAccessible(true);

        Field mPagesField = ContentLayout.ContentHelper.class.getDeclaredField("mPages");
        mPagesField.setAccessible(true);

        // First page in subscription mode: pages = -1, nextPage = -1
        List<DummyItem> page1 = new ArrayList<>();
        page1.add(new DummyItem(1));
        page1.add(new DummyItem(2));
        onTypeRefresh.invoke(helper, -1, -1, page1);

        assertEquals(0, mStartPageField.getInt(helper));
        assertEquals(1, mEndPageField.getInt(helper));
        assertEquals(-1, mPagesField.getInt(helper));
        assertTrue((Boolean) pageDividerMatchesWindow.invoke(helper));

        // Load second page in subscription mode: pages = -1, nextPage = -1
        List<DummyItem> page2 = new ArrayList<>();
        page2.add(new DummyItem(3));
        page2.add(new DummyItem(4));
        onTypeNextPage.invoke(helper, -1, -1, page2);

        // mEndPage should now be 2, NOT remaining 1
        assertEquals(2, mEndPageField.getInt(helper));
        assertEquals(-1, mPagesField.getInt(helper));
        assertTrue((Boolean) pageDividerMatchesWindow.invoke(helper));

        // Load third page
        List<DummyItem> page3 = new ArrayList<>();
        page3.add(new DummyItem(5));
        onTypeNextPage.invoke(helper, -1, -1, page3);

        assertEquals(3, mEndPageField.getInt(helper));
        assertEquals(-1, mPagesField.getInt(helper));
        assertTrue((Boolean) pageDividerMatchesWindow.invoke(helper));
    }

    @Test
    public void testSubscriptionPaginationPrevPageKeepsPagesMinusOne() throws Exception {
        TestHelper helper = new TestHelper();

        Method onTypeRefresh = ContentLayout.ContentHelper.class.getDeclaredMethod("onTypeRefresh", int.class, int.class, List.class);
        onTypeRefresh.setAccessible(true);

        Method onTypeRpePage = ContentLayout.ContentHelper.class.getDeclaredMethod("onTypeRpePage", int.class, List.class);
        onTypeRpePage.setAccessible(true);

        Field mPagesField = ContentLayout.ContentHelper.class.getDeclaredField("mPages");
        mPagesField.setAccessible(true);

        Field mStartPageField = ContentLayout.ContentHelper.class.getDeclaredField("mStartPage");
        mStartPageField.setAccessible(true);

        List<DummyItem> page1 = new ArrayList<>();
        page1.add(new DummyItem(1));
        onTypeRefresh.invoke(helper, -1, -1, page1);
        assertEquals(-1, mPagesField.getInt(helper));

        List<DummyItem> prevPage = new ArrayList<>();
        prevPage.add(new DummyItem(0));
        onTypeRpePage.invoke(helper, -1, prevPage);

        // In subscription mode, mPages should remain -1
        assertEquals(-1, mPagesField.getInt(helper));
        assertEquals(-1, mStartPageField.getInt(helper));
    }

    @Test
    public void testPageDividerMatchesWindowAutoHeal() throws Exception {
        TestHelper helper = new TestHelper();

        Method onTypeRefresh = ContentLayout.ContentHelper.class.getDeclaredMethod("onTypeRefresh", int.class, int.class, List.class);
        onTypeRefresh.setAccessible(true);

        Method pageDividerMatchesWindow = ContentLayout.ContentHelper.class.getDeclaredMethod("pageDividerMatchesWindow");
        pageDividerMatchesWindow.setAccessible(true);

        Field mEndPageField = ContentLayout.ContentHelper.class.getDeclaredField("mEndPage");
        mEndPageField.setAccessible(true);

        Field mPagesField = ContentLayout.ContentHelper.class.getDeclaredField("mPages");
        mPagesField.setAccessible(true);

        List<DummyItem> page1 = new ArrayList<>();
        page1.add(new DummyItem(1));
        onTypeRefresh.invoke(helper, -1, -1, page1);

        // Artificially corrupt mEndPage (simulate older buggy state where mEndPage remained 0 or 1)
        mEndPageField.setInt(helper, 0);
        mPagesField.setInt(helper, -1);

        // In cursor/subscription mode (mPages <= 0), auto heal should fix mEndPage and return true
        assertTrue((Boolean) pageDividerMatchesWindow.invoke(helper));
        assertEquals(1, mEndPageField.getInt(helper));
    }
}
