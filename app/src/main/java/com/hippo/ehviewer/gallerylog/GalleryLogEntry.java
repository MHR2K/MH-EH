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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 漫画状态与操作日志实体
 */
public class GalleryLogEntry {

    public static final int CATEGORY_DOWNLOAD = 1; // 下载生命周期流转
    public static final int CATEGORY_STORAGE = 2;  // 存储、归档与迁移
    public static final int CATEGORY_LIST = 3;     // 列表排序与标签管理

    public static final String ACTION_DOWNLOAD_ADD = "DOWNLOAD_ADD";
    public static final String ACTION_DOWNLOAD_WAIT = "DOWNLOAD_WAIT";
    public static final String ACTION_DOWNLOAD_START = "DOWNLOAD_START";
    public static final String ACTION_DOWNLOAD_PAUSE = "DOWNLOAD_PAUSE";
    public static final String ACTION_DOWNLOAD_RETRY = "DOWNLOAD_RETRY";
    public static final String ACTION_DOWNLOAD_FINISH = "DOWNLOAD_FINISH";
    public static final String ACTION_DOWNLOAD_FAILED = "DOWNLOAD_FAILED";

    public static final String ACTION_ITEM_DELETE = "ITEM_DELETE";
    public static final String ACTION_ITEM_REORDER = "ITEM_REORDER";
    public static final String ACTION_ITEM_LABEL_CHANGE = "ITEM_LABEL_CHANGE";

    public static final String ACTION_SMB_TO_SMB = "SMB_TO_SMB";
    public static final String ACTION_SMB_TO_LOCAL = "SMB_TO_LOCAL";
    public static final String ACTION_ARCHIVE_CBZ = "ARCHIVE_CBZ";

    public long id;
    public long timestamp;
    public long gid;
    @Nullable
    public String title;
    public int category;
    @NonNull
    public String action = "";
    @Nullable
    public String detail;

    public GalleryLogEntry() {
    }

    public GalleryLogEntry(long timestamp, long gid, @Nullable String title, int category,
                           @NonNull String action, @Nullable String detail) {
        this.timestamp = timestamp;
        this.gid = gid;
        this.title = title;
        this.category = category;
        this.action = action;
        this.detail = detail;
    }

    @NonNull
    @Override
    public String toString() {
        return "GalleryLogEntry{" +
                "id=" + id +
                ", timestamp=" + timestamp +
                ", gid=" + gid +
                ", title='" + title + '\'' +
                ", category=" + category +
                ", action='" + action + '\'' +
                ", detail='" + detail + '\'' +
                '}';
    }
}
