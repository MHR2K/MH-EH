package com.hippo.ehviewer.stats;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class StatsDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "stats.db";
    private static final int DB_VERSION = 3;

    static final String TABLE_DAILY_STATS = "daily_stats";
    static final String COL_DATE = "date";
    static final String COL_READ_COUNT = "read_count";
    static final String COL_DOWNLOAD_ADDED = "download_added";
    static final String COL_DOWNLOAD_COMPLETED = "download_completed";
    static final String COL_TOTAL_TIME_SECONDS = "total_time_seconds";
    static final String COL_READ_TIME_SECONDS = "read_time_seconds";
    static final String COL_UPDATE_TIME = "update_time";

    static final String TABLE_READING_RECORD = "gallery_reading_record";
    static final String COL_RECORD_ID = "_id";
    static final String COL_RECORD_GID = "gid";
    static final String COL_RECORD_TITLE = "title";
    static final String COL_RECORD_START_TIME = "start_time";
    static final String COL_RECORD_END_TIME = "end_time";
    static final String COL_RECORD_DURATION = "duration_seconds";
    static final String COL_RECORD_PAGE_START = "page_start";
    static final String COL_RECORD_PAGE_END = "page_end";
    static final String COL_RECORD_TOTAL_PAGES = "total_pages";
    static final String COL_RECORD_COMPLETED = "completed";

    private static final String SQL_CREATE =
            "CREATE TABLE " + TABLE_DAILY_STATS + " (" +
                    COL_DATE + " TEXT PRIMARY KEY NOT NULL, " +
                    COL_READ_COUNT + " INTEGER NOT NULL DEFAULT 0, " +
                    COL_DOWNLOAD_ADDED + " INTEGER NOT NULL DEFAULT 0, " +
                    COL_DOWNLOAD_COMPLETED + " INTEGER NOT NULL DEFAULT 0, " +
                    COL_TOTAL_TIME_SECONDS + " INTEGER NOT NULL DEFAULT 0, " +
                    COL_READ_TIME_SECONDS + " INTEGER NOT NULL DEFAULT 0, " +
                    COL_UPDATE_TIME + " INTEGER NOT NULL)";

    private static final String SQL_CREATE_RECORD =
            "CREATE TABLE IF NOT EXISTS " + TABLE_READING_RECORD + " (" +
                    COL_RECORD_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_RECORD_GID + " INTEGER NOT NULL, " +
                    COL_RECORD_TITLE + " TEXT, " +
                    COL_RECORD_START_TIME + " INTEGER NOT NULL, " +
                    COL_RECORD_END_TIME + " INTEGER NOT NULL, " +
                    COL_RECORD_DURATION + " INTEGER NOT NULL DEFAULT 0, " +
                    COL_RECORD_PAGE_START + " INTEGER NOT NULL DEFAULT 1, " +
                    COL_RECORD_PAGE_END + " INTEGER NOT NULL DEFAULT 1, " +
                    COL_RECORD_TOTAL_PAGES + " INTEGER NOT NULL DEFAULT 0, " +
                    COL_RECORD_COMPLETED + " INTEGER NOT NULL DEFAULT 0)";

    private static final String SQL_CREATE_INDEX_RECORD_GID =
            "CREATE INDEX IF NOT EXISTS idx_reading_record_gid ON " + TABLE_READING_RECORD + " (" + COL_RECORD_GID + ")";

    private static final String SQL_CREATE_INDEX_RECORD_TIME =
            "CREATE INDEX IF NOT EXISTS idx_reading_record_start_time ON " + TABLE_READING_RECORD + " (" + COL_RECORD_START_TIME + ")";

    public StatsDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE);
        db.execSQL(SQL_CREATE_RECORD);
        db.execSQL(SQL_CREATE_INDEX_RECORD_GID);
        db.execSQL(SQL_CREATE_INDEX_RECORD_TIME);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_DAILY_STATS +
                    " ADD COLUMN " + COL_TOTAL_TIME_SECONDS + " INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_DAILY_STATS +
                    " ADD COLUMN " + COL_READ_TIME_SECONDS + " INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 3) {
            db.execSQL(SQL_CREATE_RECORD);
            db.execSQL(SQL_CREATE_INDEX_RECORD_GID);
            db.execSQL(SQL_CREATE_INDEX_RECORD_TIME);
        }
    }
}
