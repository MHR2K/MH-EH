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

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * 漫画操作流水 SQLiteOpenHelper
 */
public class GalleryLogDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "gallery_log.db";
    private static final int DB_VERSION = 1;

    public static final String TABLE_NAME = "gallery_operation_log";
    public static final String COL_ID = "_id";
    public static final String COL_TIMESTAMP = "timestamp";
    public static final String COL_GID = "gid";
    public static final String COL_TITLE = "title";
    public static final String COL_CATEGORY = "category";
    public static final String COL_ACTION = "action";
    public static final String COL_DETAIL = "detail";

    private static final String SQL_CREATE =
            "CREATE TABLE " + TABLE_NAME + " (" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_TIMESTAMP + " INTEGER NOT NULL, " +
                    COL_GID + " INTEGER NOT NULL, " +
                    COL_TITLE + " TEXT, " +
                    COL_CATEGORY + " INTEGER NOT NULL, " +
                    COL_ACTION + " TEXT NOT NULL, " +
                    COL_DETAIL + " TEXT)";

    private static final String SQL_CREATE_INDEX_TIME =
            "CREATE INDEX IF NOT EXISTS idx_gallery_log_time ON " + TABLE_NAME + " (" + COL_TIMESTAMP + ")";

    private static final String SQL_CREATE_INDEX_GID =
            "CREATE INDEX IF NOT EXISTS idx_gallery_log_gid ON " + TABLE_NAME + " (" + COL_GID + ")";

    public GalleryLogDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE);
        db.execSQL(SQL_CREATE_INDEX_TIME);
        db.execSQL(SQL_CREATE_INDEX_GID);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future upgrades will alter or migrate tables as needed
    }
}
