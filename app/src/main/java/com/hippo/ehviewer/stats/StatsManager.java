package com.hippo.ehviewer.stats;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class StatsManager {

    private static volatile StatsManager sInstance;
    private final StatsDatabase mDb;
    private final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    private StatsManager(Context context) {
        mDb = new StatsDatabase(context.getApplicationContext());
    }

    public static StatsManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (StatsManager.class) {
                if (sInstance == null) {
                    sInstance = new StatsManager(context);
                }
            }
        }
        return sInstance;
    }

    /**
     * 获取今日日期字符串，凌晨4点为一天的分界。
     * 00:00-03:59 算前一天。
     */
    public String getTodayDateString() {
        Calendar cal = Calendar.getInstance();
        if (cal.get(Calendar.HOUR_OF_DAY) < 4) {
            cal.add(Calendar.DAY_OF_MONTH, -1);
        }
        return mDateFormat.format(cal.getTime());
    }

    /** 增加阅读完成计数 */
    public void incrementReadCount() {
        incrementField(StatsDatabase.COL_READ_COUNT);
    }

    /** 增加下载添加计数 */
    public void incrementDownloadAdded() {
        incrementField(StatsDatabase.COL_DOWNLOAD_ADDED);
    }

    /** 增加下载完成计数 */
    public void incrementDownloadCompleted() {
        incrementField(StatsDatabase.COL_DOWNLOAD_COMPLETED);
    }

    private void incrementField(String column) {
        SQLiteDatabase db = mDb.getWritableDatabase();
        String date = getTodayDateString();
        long now = System.currentTimeMillis();

        db.execSQL(
                "INSERT INTO " + StatsDatabase.TABLE_DAILY_STATS +
                        " (" + StatsDatabase.COL_DATE + ", " + column + ", " + StatsDatabase.COL_UPDATE_TIME + ") " +
                        "VALUES (?, 1, ?) " +
                        "ON CONFLICT(" + StatsDatabase.COL_DATE + ") DO UPDATE SET " +
                        column + " = " + column + " + 1, " +
                        StatsDatabase.COL_UPDATE_TIME + " = ?",
                new Object[]{date, now, now}
        );
    }

    /** 获取今日统计 */
    public DailyStats getTodayStats() {
        return getStatsByDate(getTodayDateString());
    }

    /** 按日期获取统计 */
    public DailyStats getStatsByDate(String date) {
        SQLiteDatabase db = mDb.getReadableDatabase();
        Cursor cursor = db.query(
                StatsDatabase.TABLE_DAILY_STATS,
                null,
                StatsDatabase.COL_DATE + " = ?",
                new String[]{date},
                null, null, null
        );
        try {
            if (cursor.moveToFirst()) {
                return cursorToStats(cursor);
            }
            return null;
        } finally {
            cursor.close();
        }
    }

    /** 获取所有统计，按日期降序 */
    public List<DailyStats> getAllStats() {
        SQLiteDatabase db = mDb.getReadableDatabase();
        Cursor cursor = db.query(
                StatsDatabase.TABLE_DAILY_STATS,
                null, null, null,
                null, null,
                StatsDatabase.COL_DATE + " DESC"
        );
        try {
            List<DailyStats> list = new ArrayList<>();
            while (cursor.moveToNext()) {
                list.add(cursorToStats(cursor));
            }
            return list;
        } finally {
            cursor.close();
        }
    }

    /** 获取指定日期范围的统计 */
    public List<DailyStats> getStatsInRange(String startDate, String endDate) {
        SQLiteDatabase db = mDb.getReadableDatabase();
        Cursor cursor = db.query(
                StatsDatabase.TABLE_DAILY_STATS,
                null,
                StatsDatabase.COL_DATE + " BETWEEN ? AND ?",
                new String[]{startDate, endDate},
                null, null,
                StatsDatabase.COL_DATE + " DESC"
        );
        try {
            List<DailyStats> list = new ArrayList<>();
            while (cursor.moveToNext()) {
                list.add(cursorToStats(cursor));
            }
            return list;
        } finally {
            cursor.close();
        }
    }

    private DailyStats cursorToStats(Cursor cursor) {
        DailyStats stats = new DailyStats();
        stats.date = cursor.getString(cursor.getColumnIndexOrThrow(StatsDatabase.COL_DATE));
        stats.readCount = cursor.getInt(cursor.getColumnIndexOrThrow(StatsDatabase.COL_READ_COUNT));
        stats.downloadAdded = cursor.getInt(cursor.getColumnIndexOrThrow(StatsDatabase.COL_DOWNLOAD_ADDED));
        stats.downloadCompleted = cursor.getInt(cursor.getColumnIndexOrThrow(StatsDatabase.COL_DOWNLOAD_COMPLETED));
        stats.updateTime = cursor.getLong(cursor.getColumnIndexOrThrow(StatsDatabase.COL_UPDATE_TIME));
        return stats;
    }
}
