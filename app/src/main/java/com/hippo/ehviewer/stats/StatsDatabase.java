package com.hippo.ehviewer.stats;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class StatsDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "stats.db";
    private static final int DB_VERSION = 2;

    static final String TABLE_DAILY_STATS = "daily_stats";
    static final String COL_DATE = "date";
    static final String COL_READ_COUNT = "read_count";
    static final String COL_DOWNLOAD_ADDED = "download_added";
    static final String COL_DOWNLOAD_COMPLETED = "download_completed";
    static final String COL_TOTAL_TIME_SECONDS = "total_time_seconds";
    static final String COL_READ_TIME_SECONDS = "read_time_seconds";
    static final String COL_UPDATE_TIME = "update_time";

    private static final String SQL_CREATE =
            "CREATE TABLE " + TABLE_DAILY_STATS + " (" +
                    COL_DATE + " TEXT PRIMARY KEY NOT NULL, " +
                    COL_READ_COUNT + " INTEGER NOT NULL DEFAULT 0, " +
                    COL_DOWNLOAD_ADDED + " INTEGER NOT NULL DEFAULT 0, " +
                    COL_DOWNLOAD_COMPLETED + " INTEGER NOT NULL DEFAULT 0, " +
                    COL_TOTAL_TIME_SECONDS + " INTEGER NOT NULL DEFAULT 0, " +
                    COL_READ_TIME_SECONDS + " INTEGER NOT NULL DEFAULT 0, " +
                    COL_UPDATE_TIME + " INTEGER NOT NULL)";

    public StatsDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_DAILY_STATS +
                    " ADD COLUMN " + COL_TOTAL_TIME_SECONDS + " INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_DAILY_STATS +
                    " ADD COLUMN " + COL_READ_TIME_SECONDS + " INTEGER NOT NULL DEFAULT 0");
        }
    }
}
