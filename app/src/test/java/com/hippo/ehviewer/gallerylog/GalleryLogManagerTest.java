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

package com.hippo.ehviewer.gallerylog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GalleryLogManagerTest {

    @Test
    public void testGalleryLogEntryCreation() {
        long now = System.currentTimeMillis();
        GalleryLogEntry entry = new GalleryLogEntry(
                now,
                123456L,
                "测试画廊",
                GalleryLogEntry.CATEGORY_DOWNLOAD,
                GalleryLogEntry.ACTION_DOWNLOAD_START,
                "开始下载 (共 30 页)"
        );

        assertEquals(123456L, entry.gid);
        assertEquals("测试画廊", entry.title);
        assertEquals(GalleryLogEntry.CATEGORY_DOWNLOAD, entry.category);
        assertEquals(GalleryLogEntry.ACTION_DOWNLOAD_START, entry.action);
        assertEquals("开始下载 (共 30 页)", entry.detail);
        assertEquals(now, entry.timestamp);

        String str = entry.toString();
        assertNotNull(str);
        assertTrue(str.contains("123456"));
        assertTrue(str.contains("DOWNLOAD_START"));
    }

    @Test
    public void testActionAndCategoryConstants() {
        assertEquals(1, GalleryLogEntry.CATEGORY_DOWNLOAD);
        assertEquals(2, GalleryLogEntry.CATEGORY_STORAGE);
        assertEquals(3, GalleryLogEntry.CATEGORY_LIST);

        assertEquals("DOWNLOAD_ADD", GalleryLogEntry.ACTION_DOWNLOAD_ADD);
        assertEquals("DOWNLOAD_WAIT", GalleryLogEntry.ACTION_DOWNLOAD_WAIT);
        assertEquals("DOWNLOAD_START", GalleryLogEntry.ACTION_DOWNLOAD_START);
        assertEquals("DOWNLOAD_PAUSE", GalleryLogEntry.ACTION_DOWNLOAD_PAUSE);
        assertEquals("DOWNLOAD_RETRY", GalleryLogEntry.ACTION_DOWNLOAD_RETRY);
        assertEquals("DOWNLOAD_FINISH", GalleryLogEntry.ACTION_DOWNLOAD_FINISH);
        assertEquals("DOWNLOAD_FAILED", GalleryLogEntry.ACTION_DOWNLOAD_FAILED);

        assertEquals("ITEM_DELETE", GalleryLogEntry.ACTION_ITEM_DELETE);
        assertEquals("ITEM_REORDER", GalleryLogEntry.ACTION_ITEM_REORDER);
        assertEquals("ITEM_LABEL_CHANGE", GalleryLogEntry.ACTION_ITEM_LABEL_CHANGE);

        assertEquals("SMB_TO_SMB", GalleryLogEntry.ACTION_SMB_TO_SMB);
        assertEquals("SMB_TO_LOCAL", GalleryLogEntry.ACTION_SMB_TO_LOCAL);
        assertEquals("ARCHIVE_CBZ", GalleryLogEntry.ACTION_ARCHIVE_CBZ);
    }

    @Test
    public void testReadableNames() {
        assertEquals("下载流转", GalleryLogManager.getCategoryReadableName(GalleryLogEntry.CATEGORY_DOWNLOAD));
        assertEquals("存储管理", GalleryLogManager.getCategoryReadableName(GalleryLogEntry.CATEGORY_STORAGE));
        assertEquals("列表操作", GalleryLogManager.getCategoryReadableName(GalleryLogEntry.CATEGORY_LIST));

        assertEquals("添加下载", GalleryLogManager.getActionReadableName(GalleryLogEntry.ACTION_DOWNLOAD_ADD));
        assertEquals("开始下载", GalleryLogManager.getActionReadableName(GalleryLogEntry.ACTION_DOWNLOAD_START));
        assertEquals("下载完成", GalleryLogManager.getActionReadableName(GalleryLogEntry.ACTION_DOWNLOAD_FINISH));
        assertEquals("下载失败", GalleryLogManager.getActionReadableName(GalleryLogEntry.ACTION_DOWNLOAD_FAILED));
        assertEquals("调整排序", GalleryLogManager.getActionReadableName(GalleryLogEntry.ACTION_ITEM_REORDER));
        assertEquals("迁移SMB", GalleryLogManager.getActionReadableName(GalleryLogEntry.ACTION_SMB_TO_SMB));
        assertEquals("迁回本地", GalleryLogManager.getActionReadableName(GalleryLogEntry.ACTION_SMB_TO_LOCAL));
        assertEquals("转换CBZ", GalleryLogManager.getActionReadableName(GalleryLogEntry.ACTION_ARCHIVE_CBZ));
    }

    @Test
    public void testFormatSingleLineDetail() {
        String oldDetail = "状态变迁: [下载失败] ➔ [排队等待] (全部开始) | 当前已下: 12/50页";
        String formatted = GalleryLogManager.formatSingleLineDetail(GalleryLogEntry.ACTION_DOWNLOAD_WAIT, oldDetail);
        assertEquals("下载失败 -> 排队中 (全部开始) | 进度: 12/50页", formatted);

        String oldLabel = "标签变更: 默认 ➔ 收藏待看";
        String formattedLabel = GalleryLogManager.formatSingleLineDetail(GalleryLogEntry.ACTION_ITEM_LABEL_CHANGE, oldLabel);
        assertEquals("默认 -> 收藏待看", formattedLabel);
    }

    @Test
    public void testStateReadableName() {
        assertEquals("未开始", GalleryLogManager.getStateReadableName(com.hippo.ehviewer.dao.DownloadInfo.STATE_NONE, 0));
        assertEquals("已暂停", GalleryLogManager.getStateReadableName(com.hippo.ehviewer.dao.DownloadInfo.STATE_NONE, 5));
        assertEquals("排队等待", GalleryLogManager.getStateReadableName(com.hippo.ehviewer.dao.DownloadInfo.STATE_WAIT, 0));
        assertEquals("下载中", GalleryLogManager.getStateReadableName(com.hippo.ehviewer.dao.DownloadInfo.STATE_DOWNLOAD, 5));
        assertEquals("已完成", GalleryLogManager.getStateReadableName(com.hippo.ehviewer.dao.DownloadInfo.STATE_FINISH, 50));
        assertEquals("下载失败", GalleryLogManager.getStateReadableName(com.hippo.ehviewer.dao.DownloadInfo.STATE_FAILED, 10));
        assertEquals("有新版本", GalleryLogManager.getStateReadableName(com.hippo.ehviewer.dao.DownloadInfo.STATE_UPDATE, 0));
        assertEquals("转新版本", GalleryLogManager.getStateReadableName(com.hippo.ehviewer.dao.DownloadInfo.GOTO_NEW, 0));
    }

    @Test
    public void testRetentionDays() {
        assertEquals(90L, GalleryLogManager.RETENTION_DAYS);
    }
}
