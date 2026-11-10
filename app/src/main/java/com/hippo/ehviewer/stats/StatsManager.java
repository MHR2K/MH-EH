package com.hippo.ehviewer.stats;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class StatsManager {

    private static final String TAG = "StatsManager";

    private static volatile StatsManager sInstance;
    private final StatsDatabase mDb;
    private final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    public interface OnRecordSavedCallback {
        void onRecordSaved(long recordId);
    }

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
        String date = getTodayDateString();
        long now = System.currentTimeMillis();
        mExecutor.execute(() -> {
            try {
                SQLiteDatabase db = mDb.getWritableDatabase();
                db.execSQL(
                        "INSERT INTO " + StatsDatabase.TABLE_DAILY_STATS +
                                " (" + StatsDatabase.COL_DATE + ", " + column + ", " + StatsDatabase.COL_UPDATE_TIME + ") " +
                                "VALUES (?, 1, ?) " +
                                "ON CONFLICT(" + StatsDatabase.COL_DATE + ") DO UPDATE SET " +
                                column + " = " + column + " + 1, " +
                                StatsDatabase.COL_UPDATE_TIME + " = ?",
                        new Object[]{date, now, now}
                );
            } catch (Exception e) {
                Log.e(TAG, "Failed to increment stats field: " + column, e);
            }
        });
    }

    /** 累加总使用时间（秒） */
    public void addTotalTime(long seconds) {
        if (seconds <= 0) return;
        addTimeField(StatsDatabase.COL_TOTAL_TIME_SECONDS, seconds);
    }

    /** 累加阅读时间（秒） */
    public void addReadTime(long seconds) {
        if (seconds <= 0) return;
        addTimeField(StatsDatabase.COL_READ_TIME_SECONDS, seconds);
    }

    private void addTimeField(String column, long seconds) {
        String date = getTodayDateString();
        long now = System.currentTimeMillis();
        mExecutor.execute(() -> {
            try {
                SQLiteDatabase db = mDb.getWritableDatabase();
                db.execSQL(
                        "INSERT INTO " + StatsDatabase.TABLE_DAILY_STATS +
                                " (" + StatsDatabase.COL_DATE + ", " + column + ", " + StatsDatabase.COL_UPDATE_TIME + ") " +
                                "VALUES (?, ?, ?) " +
                                "ON CONFLICT(" + StatsDatabase.COL_DATE + ") DO UPDATE SET " +
                                column + " = " + column + " + ?, " +
                                StatsDatabase.COL_UPDATE_TIME + " = ?",
                        new Object[]{date, seconds, now, seconds, now}
                );
            } catch (Exception e) {
                Log.e(TAG, "Failed to add stats time: " + column, e);
            }
        });
    }

    /** 等待所有后台异步写入操作完成 */
    public void flushSync() {
        try {
            mExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    /** 获取今日统计 */
    public DailyStats getTodayStats() {
        return getStatsByDate(getTodayDateString());
    }

    /** 按日期获取统计 */
    public DailyStats getStatsByDate(String date) {
        flushSync();
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
        flushSync();
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
        flushSync();
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
        stats.totalTimeSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(StatsDatabase.COL_TOTAL_TIME_SECONDS));
        stats.readTimeSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(StatsDatabase.COL_READ_TIME_SECONDS));
        stats.updateTime = cursor.getLong(cursor.getColumnIndexOrThrow(StatsDatabase.COL_UPDATE_TIME));
        return stats;
    }

    // ==================== 画廊独立阅读流水记录相关 ====================

    /**
     * 同步保存或更新阅读记录。
     * @return 记录 ID（如果是新增则返回新生成的 _id，更新则返回原有 recordId）
     */
    public long saveOrUpdateReadingRecordSync(long recordId, long gid, @Nullable String title,
                                             long startTime, long endTime, int durationSeconds,
                                             int pageStart, int pageEnd, int totalPages, boolean completed) {
        try {
            SQLiteDatabase db = mDb.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(StatsDatabase.COL_RECORD_GID, gid);
            values.put(StatsDatabase.COL_RECORD_TITLE, title);
            values.put(StatsDatabase.COL_RECORD_START_TIME, startTime);
            values.put(StatsDatabase.COL_RECORD_END_TIME, endTime);
            values.put(StatsDatabase.COL_RECORD_DURATION, durationSeconds);
            values.put(StatsDatabase.COL_RECORD_PAGE_START, pageStart);
            values.put(StatsDatabase.COL_RECORD_PAGE_END, pageEnd);
            values.put(StatsDatabase.COL_RECORD_TOTAL_PAGES, totalPages);
            values.put(StatsDatabase.COL_RECORD_COMPLETED, completed ? 1 : 0);

            if (recordId > 0) {
                db.update(StatsDatabase.TABLE_READING_RECORD, values,
                        StatsDatabase.COL_RECORD_ID + " = ?", new String[]{String.valueOf(recordId)});
                return recordId;
            } else {
                // 防重检查：如果同 GID 在极短时间（2秒）内有相同 start_time 的记录，复用该记录而不是重复插入
                Cursor cursor = db.query(StatsDatabase.TABLE_READING_RECORD,
                        new String[]{StatsDatabase.COL_RECORD_ID},
                        StatsDatabase.COL_RECORD_GID + " = ? AND ABS(" + StatsDatabase.COL_RECORD_START_TIME + " - ?) < 2000",
                        new String[]{String.valueOf(gid), String.valueOf(startTime)},
                        null, null, StatsDatabase.COL_RECORD_ID + " DESC", "1");
                try {
                    if (cursor.moveToFirst()) {
                        long existingId = cursor.getLong(0);
                        db.update(StatsDatabase.TABLE_READING_RECORD, values,
                                StatsDatabase.COL_RECORD_ID + " = ?", new String[]{String.valueOf(existingId)});
                        return existingId;
                    }
                } finally {
                    cursor.close();
                }
                return db.insert(StatsDatabase.TABLE_READING_RECORD, null, values);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save or update reading record for gid=" + gid, e);
            return recordId;
        }
    }

    /**
     * 异步保存或更新阅读记录。
     */
    public void saveOrUpdateReadingRecordAsync(long recordId, long gid, @Nullable String title,
                                              long startTime, long endTime, int durationSeconds,
                                              int pageStart, int pageEnd, int totalPages, boolean completed,
                                              @Nullable OnRecordSavedCallback callback) {
        mExecutor.execute(() -> {
            long id = saveOrUpdateReadingRecordSync(recordId, gid, title, startTime, endTime,
                    durationSeconds, pageStart, pageEnd, totalPages, completed);
            if (callback != null) {
                callback.onRecordSaved(id);
            }
        });
    }

    /**
     * 获取指定画廊的所有阅读记录，按开始时间倒序。
     */
    public List<ReadingRecord> getReadingRecords(long gid) {
        flushSync();
        SQLiteDatabase db = mDb.getReadableDatabase();
        Cursor cursor = db.query(
                StatsDatabase.TABLE_READING_RECORD,
                null,
                StatsDatabase.COL_RECORD_GID + " = ?",
                new String[]{String.valueOf(gid)},
                null, null,
                StatsDatabase.COL_RECORD_START_TIME + " DESC, " + StatsDatabase.COL_RECORD_ID + " DESC"
        );
        try {
            List<ReadingRecord> list = new ArrayList<>();
            List<Long> duplicateIdsToDelete = new ArrayList<>();
            long lastStartTime = -1;
            while (cursor.moveToNext()) {
                ReadingRecord record = cursorToReadingRecord(cursor);
                // 过滤误点秒关的无效记录（用时<=1秒且未翻页）
                if (record.durationSeconds <= 1 && record.pageStart == record.pageEnd) {
                    duplicateIdsToDelete.add(record.id);
                    continue;
                }
                if (lastStartTime > 0 && Math.abs(lastStartTime - record.startTime) < 2000) {
                    duplicateIdsToDelete.add(record.id);
                    continue;
                }
                lastStartTime = record.startTime;
                list.add(record);
            }
            if (!duplicateIdsToDelete.isEmpty()) {
                mExecutor.execute(() -> {
                    try {
                        SQLiteDatabase writeDb = mDb.getWritableDatabase();
                        for (Long id : duplicateIdsToDelete) {
                            writeDb.delete(StatsDatabase.TABLE_READING_RECORD,
                                    StatsDatabase.COL_RECORD_ID + " = ?", new String[]{String.valueOf(id)});
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
            return list;
        } finally {
            cursor.close();
        }
    }

    /**
     * 获取指定画廊的阅读次数。
     */
    public int getReadingCount(long gid) {
        flushSync();
        SQLiteDatabase db = mDb.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM " + StatsDatabase.TABLE_READING_RECORD +
                        " WHERE " + StatsDatabase.COL_RECORD_GID + " = ?",
                new String[]{String.valueOf(gid)}
        );
        try {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
            return 0;
        } finally {
            cursor.close();
        }
    }

    /**
     * 获取指定画廊的累计阅读时长（秒）。
     */
    public long getTotalReadingDurationForGallery(long gid) {
        flushSync();
        SQLiteDatabase db = mDb.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT SUM(" + StatsDatabase.COL_RECORD_DURATION + ") FROM " +
                        StatsDatabase.TABLE_READING_RECORD +
                        " WHERE " + StatsDatabase.COL_RECORD_GID + " = ?",
                new String[]{String.valueOf(gid)}
        );
        try {
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
            return 0;
        } finally {
            cursor.close();
        }
    }

    /**
     * 删除单条阅读记录。
     */
    public boolean deleteReadingRecord(long recordId) {
        flushSync();
        try {
            SQLiteDatabase db = mDb.getWritableDatabase();
            return db.delete(StatsDatabase.TABLE_READING_RECORD,
                    StatsDatabase.COL_RECORD_ID + " = ?",
                    new String[]{String.valueOf(recordId)}) > 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete reading record id=" + recordId, e);
            return false;
        }
    }

    /**
     * 清空指定画廊的所有阅读记录。
     */
    public boolean clearReadingRecords(long gid) {
        flushSync();
        try {
            SQLiteDatabase db = mDb.getWritableDatabase();
            return db.delete(StatsDatabase.TABLE_READING_RECORD,
                    StatsDatabase.COL_RECORD_GID + " = ?",
                    new String[]{String.valueOf(gid)}) > 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear reading records for gid=" + gid, e);
            return false;
        }
    }

    private ReadingRecord cursorToReadingRecord(Cursor cursor) {
        ReadingRecord record = new ReadingRecord();
        record.id = cursor.getLong(cursor.getColumnIndexOrThrow(StatsDatabase.COL_RECORD_ID));
        record.gid = cursor.getLong(cursor.getColumnIndexOrThrow(StatsDatabase.COL_RECORD_GID));
        record.title = cursor.getString(cursor.getColumnIndexOrThrow(StatsDatabase.COL_RECORD_TITLE));
        record.startTime = cursor.getLong(cursor.getColumnIndexOrThrow(StatsDatabase.COL_RECORD_START_TIME));
        record.endTime = cursor.getLong(cursor.getColumnIndexOrThrow(StatsDatabase.COL_RECORD_END_TIME));
        record.durationSeconds = cursor.getInt(cursor.getColumnIndexOrThrow(StatsDatabase.COL_RECORD_DURATION));
        record.pageStart = cursor.getInt(cursor.getColumnIndexOrThrow(StatsDatabase.COL_RECORD_PAGE_START));
        record.pageEnd = cursor.getInt(cursor.getColumnIndexOrThrow(StatsDatabase.COL_RECORD_PAGE_END));
        record.totalPages = cursor.getInt(cursor.getColumnIndexOrThrow(StatsDatabase.COL_RECORD_TOTAL_PAGES));
        record.completed = cursor.getInt(cursor.getColumnIndexOrThrow(StatsDatabase.COL_RECORD_COMPLETED)) == 1;
        return record;
    }

    /**
     * 格式化时长为可读字符串。
     * 例如：0s, 5m, 1h 30m
     */
    public static String formatDuration(long totalSeconds) {
        if (totalSeconds <= 0) {
            return "0m";
        }
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        } else {
            return minutes + "m";
        }
    }
}
