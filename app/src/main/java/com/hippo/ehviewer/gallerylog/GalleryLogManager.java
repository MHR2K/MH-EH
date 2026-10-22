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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.dao.DownloadInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 漫画操作与状态流水日志管理器
 */
public class GalleryLogManager {

    private static final String TAG = "GalleryLogManager";
    public static final long RETENTION_DAYS = 90L;
    private static final long RETENTION_MS = RETENTION_DAYS * 24L * 60L * 60L * 1000L;
    private static final int PRUNE_INTERVAL = 50;

    private static volatile GalleryLogManager sInstance;
    private final GalleryLogDatabase mDbHelper;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger mWriteCountSincePrune = new AtomicInteger(0);

    private GalleryLogManager(Context context) {
        mDbHelper = new GalleryLogDatabase(context.getApplicationContext());
    }

    public static GalleryLogManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (GalleryLogManager.class) {
                if (sInstance == null) {
                    sInstance = new GalleryLogManager(context);
                }
            }
        }
        return sInstance;
    }

    /**
     * 通用日志写入接口（异步写入，不阻塞调用方）
     */
    public void log(long gid, @Nullable String title, int category, @NonNull String action, @Nullable String detail) {
        long now = System.currentTimeMillis();
        mExecutor.execute(() -> {
            try {
                SQLiteDatabase db = mDbHelper.getWritableDatabase();
                ContentValues values = new ContentValues();
                values.put(GalleryLogDatabase.COL_TIMESTAMP, now);
                values.put(GalleryLogDatabase.COL_GID, gid);
                values.put(GalleryLogDatabase.COL_TITLE, title);
                values.put(GalleryLogDatabase.COL_CATEGORY, category);
                values.put(GalleryLogDatabase.COL_ACTION, action);
                values.put(GalleryLogDatabase.COL_DETAIL, detail);
                db.insert(GalleryLogDatabase.TABLE_NAME, null, values);

                // 定期修剪旧日志，清理超过 90 天的数据
                if (mWriteCountSincePrune.incrementAndGet() >= PRUNE_INTERVAL) {
                    mWriteCountSincePrune.set(0);
                    pruneOldLogs(db);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to write gallery log: " + action + ", gid=" + gid, t);
            }
        });
    }

    public void pruneOldLogs() {
        mExecutor.execute(() -> {
            try {
                SQLiteDatabase db = mDbHelper.getWritableDatabase();
                pruneOldLogs(db);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to prune old gallery logs", t);
            }
        });
    }

    private void pruneOldLogs(SQLiteDatabase db) {
        try {
            long cutoff = System.currentTimeMillis() - RETENTION_MS;
            db.delete(
                    GalleryLogDatabase.TABLE_NAME,
                    GalleryLogDatabase.COL_TIMESTAMP + " < ?",
                    new String[]{String.valueOf(cutoff)}
            );
        } catch (Throwable t) {
            Log.e(TAG, "Failed to prune old gallery logs", t);
        }
    }

    // ==================== 下载生命周期相关便利方法 ====================

    public void logDownloadAdd(@NonNull DownloadInfo info, @Nullable String source) {
        StringBuilder sb = new StringBuilder();
        String targetState = info.state == DownloadInfo.STATE_WAIT ? "排队中" : "未开始";
        sb.append("未入库 -> ").append(targetState);
        List<String> extras = new ArrayList<>();
        if (source != null && !source.isEmpty()) {
            extras.add("来源: " + source);
        }
        if (info.label != null && !info.label.isEmpty()) {
            extras.add("标签: " + info.label);
        }
        if (!extras.isEmpty()) {
            sb.append(" (").append(String.join(" | ", extras)).append(")");
        }
        log(info.gid, info.title, GalleryLogEntry.CATEGORY_DOWNLOAD, GalleryLogEntry.ACTION_DOWNLOAD_ADD, sb.toString());
    }

    public void logDownloadWait(@NonNull DownloadInfo info, int fromState, @Nullable String reason) {
        String fromStr = getStateReadableName(fromState, info.finished);
        if ("排队等待".equals(fromStr)) fromStr = "排队中";
        StringBuilder sb = new StringBuilder();
        sb.append(fromStr).append(" -> 排队中");
        List<String> extras = new ArrayList<>();
        if (reason != null && !reason.isEmpty()) {
            extras.add(reason);
        }
        if (info.finished > 0) {
            int total = info.total > 0 ? info.total : info.pages;
            extras.add("进度: " + info.finished + "/" + total + "页");
        }
        if (!extras.isEmpty()) {
            sb.append(" (").append(String.join(" | ", extras)).append(")");
        }
        log(info.gid, info.title, GalleryLogEntry.CATEGORY_DOWNLOAD, GalleryLogEntry.ACTION_DOWNLOAD_WAIT, sb.toString());
    }

    public void logDownloadWait(@NonNull DownloadInfo info, @Nullable String reason) {
        logDownloadWait(info, info.state, reason);
    }

    public void logDownloadStart(@NonNull DownloadInfo info, int originState, boolean isResume) {
        StringBuilder sb = new StringBuilder();
        sb.append("排队中 -> 下载中");
        List<String> extras = new ArrayList<>();
        if (originState != DownloadInfo.STATE_WAIT && originState != DownloadInfo.STATE_NONE) {
            String originStr = getStateReadableName(originState, info.finished);
            if ("排队等待".equals(originStr)) originStr = "排队中";
            extras.add("前状态: " + originStr);
        }
        int total = info.total > 0 ? info.total : info.pages;
        if (isResume && info.finished > 0) {
            extras.add("断点续传: 第" + (info.finished + 1) + "页起 (已下 " + info.finished + "/" + total + "页)");
        } else {
            extras.add("共 " + total + " 页");
        }
        if (!extras.isEmpty()) {
            sb.append(" (").append(String.join(" | ", extras)).append(")");
        }
        log(info.gid, info.title, GalleryLogEntry.CATEGORY_DOWNLOAD, GalleryLogEntry.ACTION_DOWNLOAD_START, sb.toString());
    }

    public void logDownloadStart(@NonNull DownloadInfo info, boolean isResume) {
        logDownloadStart(info, DownloadInfo.STATE_WAIT, isResume);
    }

    public void logDownloadPause(@NonNull DownloadInfo info, int fromState, @Nullable String reason) {
        String fromStr = getStateReadableName(fromState, info.finished);
        if ("排队等待".equals(fromStr)) fromStr = "排队中";
        StringBuilder sb = new StringBuilder();
        sb.append(fromStr).append(" -> 已暂停");
        List<String> extras = new ArrayList<>();
        if (reason != null && !reason.isEmpty()) {
            extras.add(reason);
        }
        int total = info.total > 0 ? info.total : info.pages;
        extras.add("进度: " + info.finished + "/" + total + "页");
        sb.append(" (").append(String.join(" | ", extras)).append(")");
        log(info.gid, info.title, GalleryLogEntry.CATEGORY_DOWNLOAD, GalleryLogEntry.ACTION_DOWNLOAD_PAUSE, sb.toString());
    }

    public void logDownloadPause(@NonNull DownloadInfo info, @Nullable String reason) {
        logDownloadPause(info, DownloadInfo.STATE_DOWNLOAD, reason);
    }

    public void logDownloadRetry(@NonNull DownloadInfo info, int attempt, int maxAttempt, @Nullable String reason) {
        StringBuilder sb = new StringBuilder("下载中 -> 排队重试");
        List<String> extras = new ArrayList<>();
        extras.add("第 " + attempt + "/" + maxAttempt + " 次");
        if (reason != null && !reason.isEmpty()) {
            extras.add("原因: " + reason);
        }
        sb.append(" (").append(String.join(" | ", extras)).append(")");
        log(info.gid, info.title, GalleryLogEntry.CATEGORY_DOWNLOAD, GalleryLogEntry.ACTION_DOWNLOAD_RETRY, sb.toString());
    }

    public void logDownloadFinish(@NonNull DownloadInfo info, long costTimeMs) {
        int pages = info.total > 0 ? info.total : (info.finished > 0 ? info.finished : info.pages);
        StringBuilder sb = new StringBuilder("下载中 -> 已完成");
        List<String> extras = new ArrayList<>();
        extras.add("共" + pages + "页");
        if (costTimeMs > 0) {
            long seconds = costTimeMs / 1000;
            if (seconds >= 60) {
                extras.add("耗时 " + (seconds / 60) + "分" + (seconds % 60) + "秒");
            } else {
                extras.add("耗时 " + seconds + "秒");
            }
        }
        sb.append(" (").append(String.join(" | ", extras)).append(")");
        log(info.gid, info.title, GalleryLogEntry.CATEGORY_DOWNLOAD, GalleryLogEntry.ACTION_DOWNLOAD_FINISH, sb.toString());
    }

    public void logDownloadFailed(@NonNull DownloadInfo info, @Nullable String error) {
        int total = info.total > 0 ? info.total : info.pages;
        StringBuilder sb = new StringBuilder("下载中 -> 下载失败");
        List<String> extras = new ArrayList<>();
        if (error != null && !error.isEmpty()) {
            extras.add("原因: " + error);
        }
        extras.add("进度: " + info.finished + "/" + total + "页");
        if (info.legacy > 0) {
            extras.add("剩余 " + info.legacy + " 页");
        }
        sb.append(" (").append(String.join(" | ", extras)).append(")");
        log(info.gid, info.title, GalleryLogEntry.CATEGORY_DOWNLOAD, GalleryLogEntry.ACTION_DOWNLOAD_FAILED, sb.toString());
    }

    // ==================== 列表与标签相关便利方法 ====================

    public void logItemDelete(long gid, @Nullable String title, int fromState, @Nullable String deleteType) {
        String fromStr = getStateReadableName(fromState, 0);
        if ("排队等待".equals(fromStr)) fromStr = "排队中";
        StringBuilder sb = new StringBuilder();
        sb.append(fromStr).append(" -> 已移除");
        if (deleteType != null && !deleteType.isEmpty()) {
            sb.append(" (").append(deleteType).append(")");
        }
        log(gid, title, GalleryLogEntry.CATEGORY_LIST, GalleryLogEntry.ACTION_ITEM_DELETE, sb.toString());
    }

    public void logItemDelete(long gid, @Nullable String title, @Nullable String deleteType) {
        logItemDelete(gid, title, DownloadInfo.STATE_NONE, deleteType);
    }

    public void logItemReorder(long gid, @Nullable String title, @NonNull String detail) {
        log(gid, title, GalleryLogEntry.CATEGORY_LIST, GalleryLogEntry.ACTION_ITEM_REORDER, detail);
    }

    public void logItemLabelChange(long gid, @Nullable String title, @Nullable String fromLabel, @Nullable String toLabel) {
        String from = (fromLabel == null || fromLabel.isEmpty()) ? "默认" : fromLabel;
        String to = (toLabel == null || toLabel.isEmpty()) ? "默认" : toLabel;
        String detail = from + " -> " + to;
        log(gid, title, GalleryLogEntry.CATEGORY_LIST, GalleryLogEntry.ACTION_ITEM_LABEL_CHANGE, detail);
    }

    // ==================== 存储与迁移相关便利方法 ====================

    public void logSmbMigrateToSmb(long gid, @Nullable String title, @Nullable String server, boolean success, @Nullable String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append(success ? "本地 -> SMB" : "本地 -> SMB 失败");
        List<String> extras = new ArrayList<>();
        if (server != null && !server.isEmpty()) {
            extras.add(server);
        }
        if (detail != null && !detail.isEmpty()) {
            extras.add(detail);
        }
        if (!extras.isEmpty()) {
            sb.append(" (").append(String.join(" | ", extras)).append(")");
        }
        log(gid, title, GalleryLogEntry.CATEGORY_STORAGE, GalleryLogEntry.ACTION_SMB_TO_SMB, sb.toString());
    }

    public void logSmbMigrateToLocal(long gid, @Nullable String title, boolean success, @Nullable String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append(success ? "SMB -> 本地" : "SMB -> 本地 失败");
        if (detail != null && !detail.isEmpty()) {
            sb.append(" (").append(detail).append(")");
        }
        log(gid, title, GalleryLogEntry.CATEGORY_STORAGE, GalleryLogEntry.ACTION_SMB_TO_LOCAL, sb.toString());
    }

    public void logArchiveCbz(long gid, @Nullable String title, boolean success, @Nullable String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append(success ? "CBZ 归档制作完成" : "CBZ 归档制作失败");
        if (detail != null && !detail.isEmpty()) {
            sb.append(" (").append(detail).append(")");
        }
        log(gid, title, GalleryLogEntry.CATEGORY_STORAGE, GalleryLogEntry.ACTION_ARCHIVE_CBZ, sb.toString());
    }

    // ==================== 查询与维护方法 ====================

    /**
     * 等待所有异步写入操作完成（便于单元测试或导出）
     */
    public void flushSync() {
        try {
            mExecutor.submit(() -> {}).get(2, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 获取指定条数的日志，若 limit <= 0 则获取 90 天内的全部日志
     */
    public List<GalleryLogEntry> getRecentLogs(int limit) {
        flushSync();
        List<GalleryLogEntry> list = new ArrayList<>();
        SQLiteDatabase db = mDbHelper.getReadableDatabase();
        Cursor cursor = db.query(
                GalleryLogDatabase.TABLE_NAME,
                null, null, null, null, null,
                GalleryLogDatabase.COL_TIMESTAMP + " DESC",
                limit > 0 ? String.valueOf(limit) : null
        );
        try {
            while (cursor.moveToNext()) {
                list.add(cursorToEntry(cursor));
            }
        } finally {
            cursor.close();
        }
        return list;
    }

    /**
     * 获取 90 天内的所有日志（无条数上限）
     */
    public List<GalleryLogEntry> getAllLogs() {
        return getRecentLogs(-1);
    }

    /**
     * 获取指定 GID 画廊的完整操作流水
     */
    public List<GalleryLogEntry> getLogsForGid(long gid, int limit) {
        flushSync();
        List<GalleryLogEntry> list = new ArrayList<>();
        SQLiteDatabase db = mDbHelper.getReadableDatabase();
        Cursor cursor = db.query(
                GalleryLogDatabase.TABLE_NAME,
                null,
                GalleryLogDatabase.COL_GID + " = ?",
                new String[]{String.valueOf(gid)},
                null, null,
                GalleryLogDatabase.COL_TIMESTAMP + " DESC",
                limit > 0 ? String.valueOf(limit) : null
        );
        try {
            while (cursor.moveToNext()) {
                list.add(cursorToEntry(cursor));
            }
        } finally {
            cursor.close();
        }
        return list;
    }

    /**
     * 导出所有日志到文件，并返回生成的 File。
     * 保存目录: AppConfig.getExternalLogcatDir()
     */
    @Nullable
    public File exportLogsToFile(Context context) {
        pruneOldLogs();
        flushSync();
        File logDir = com.hippo.ehviewer.AppConfig.getExternalLogcatDir();
        if (logDir == null && context != null) {
            logDir = context.getExternalFilesDir("logcat");
        }
        if (logDir == null) {
            return null;
        }

        List<GalleryLogEntry> logs = getAllLogs();
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
        java.text.SimpleDateFormat fileSdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault());
        String fileName = "gallery_log_" + fileSdf.format(new java.util.Date()) + ".txt";
        File file = new File(logDir, fileName);

        java.io.PrintWriter writer = null;
        try {
            writer = new java.io.PrintWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8));
            writer.println("================================================================================");
            writer.println("EhViewer 漫画操作与状态流水日志 (保留最近 90 天)");
            writer.println("导出时间: " + sdf.format(new java.util.Date()) + " | 记录总数: " + logs.size() + " 条");
            writer.println("================================================================================");
            writer.println();

            for (GalleryLogEntry entry : logs) {
                String timeStr = sdf.format(new java.util.Date(entry.timestamp));
                String actionStr = getActionReadableName(entry.action);

                // 单行格式: 时间 [动作] 《标题》(GID) 详情
                StringBuilder line = new StringBuilder();
                line.append(timeStr).append(" [").append(actionStr).append("]");
                if (entry.title != null && !entry.title.trim().isEmpty()) {
                    line.append(" 《").append(entry.title.trim()).append("》");
                }
                if (entry.gid > 0) {
                    line.append("(").append(entry.gid).append(")");
                }

                String detail = formatSingleLineDetail(entry.action, entry.detail);
                if (!detail.isEmpty()) {
                    line.append(" ").append(detail);
                }
                writer.println(line.toString());
            }
            writer.flush();
            return file;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to export gallery logs to file", t);
            return null;
        } finally {
            com.hippo.lib.yorozuya.IOUtils.closeQuietly(writer);
        }
    }

    public static String getCategoryReadableName(int category) {
        switch (category) {
            case GalleryLogEntry.CATEGORY_DOWNLOAD:
                return "下载流转";
            case GalleryLogEntry.CATEGORY_STORAGE:
                return "存储管理";
            case GalleryLogEntry.CATEGORY_LIST:
                return "列表操作";
            default:
                return "其他操作";
        }
    }

    public static String getActionReadableName(String action) {
        if (action == null) return "未知操作";
        switch (action) {
            case GalleryLogEntry.ACTION_DOWNLOAD_ADD:
                return "添加下载";
            case GalleryLogEntry.ACTION_DOWNLOAD_WAIT:
                return "排队等待";
            case GalleryLogEntry.ACTION_DOWNLOAD_START:
                return "开始下载";
            case GalleryLogEntry.ACTION_DOWNLOAD_PAUSE:
                return "暂停下载";
            case GalleryLogEntry.ACTION_DOWNLOAD_RETRY:
                return "自动重试";
            case GalleryLogEntry.ACTION_DOWNLOAD_FINISH:
                return "下载完成";
            case GalleryLogEntry.ACTION_DOWNLOAD_FAILED:
                return "下载失败";
            case GalleryLogEntry.ACTION_ITEM_DELETE:
                return "删除漫画";
            case GalleryLogEntry.ACTION_ITEM_REORDER:
                return "调整排序";
            case GalleryLogEntry.ACTION_ITEM_LABEL_CHANGE:
                return "变更标签";
            case GalleryLogEntry.ACTION_SMB_TO_SMB:
                return "迁移SMB";
            case GalleryLogEntry.ACTION_SMB_TO_LOCAL:
                return "迁回本地";
            case GalleryLogEntry.ACTION_ARCHIVE_CBZ:
                return "转换CBZ";
            default:
                return action;
        }
    }

    public static String getStateReadableName(int state, int finished) {
        switch (state) {
            case DownloadInfo.STATE_NONE:
                return finished > 0 ? "已暂停" : "未开始";
            case DownloadInfo.STATE_WAIT:
                return "排队等待";
            case DownloadInfo.STATE_DOWNLOAD:
                return "下载中";
            case DownloadInfo.STATE_FINISH:
                return "已完成";
            case DownloadInfo.STATE_FAILED:
                return "下载失败";
            case DownloadInfo.STATE_UPDATE:
                return "有新版本";
            case DownloadInfo.GOTO_NEW:
                return "转新版本";
            default:
                return finished > 0 ? "已暂停" : "未开始";
        }
    }

    public static String formatSingleLineDetail(String action, @Nullable String detail) {
        if (detail == null) return "";
        detail = detail.trim();
        if (detail.startsWith("状态变迁: ")) {
            detail = detail.substring("状态变迁: ".length()).trim();
            detail = detail.replace("[", "").replace("]", "").replace("➔", "->").replace("排队等待", "排队中");
            detail = detail.replace("当前进度: ", "进度: ").replace("当前已下: ", "进度: ");
        }
        detail = detail.replace("➔", "->");
        if (GalleryLogEntry.ACTION_ITEM_LABEL_CHANGE.equals(action) && detail.startsWith("标签变更: ")) {
            detail = detail.substring("标签变更: ".length()).trim();
        }
        if (GalleryLogEntry.ACTION_DOWNLOAD_ADD.equals(action) && detail.startsWith("添加到下载列表")) {
            detail = detail.substring("添加到下载列表".length()).trim();
            if (detail.startsWith("(") && detail.endsWith(")")) {
                detail = detail.substring(1, detail.length() - 1).trim();
            }
            if (detail.isEmpty()) {
                detail = "未入库 -> 排队中";
            }
        } else if (GalleryLogEntry.ACTION_DOWNLOAD_FINISH.equals(action) && detail.startsWith("下载完成")) {
            detail = detail.substring("下载完成".length()).trim();
            if (detail.startsWith("(") && detail.endsWith(")")) {
                detail = detail.substring(1, detail.length() - 1).trim();
            }
        }
        return detail;
    }

    private static String cleanDetail(String action, @Nullable String detail) {
        return formatSingleLineDetail(action, detail);
    }

    /**
     * 清空所有日志
     */
    public void clearAllLogs() {
        mExecutor.execute(() -> {
            try {
                SQLiteDatabase db = mDbHelper.getWritableDatabase();
                db.delete(GalleryLogDatabase.TABLE_NAME, null, null);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to clear gallery logs", t);
            }
        });
    }

    private GalleryLogEntry cursorToEntry(Cursor cursor) {
        GalleryLogEntry entry = new GalleryLogEntry();
        entry.id = cursor.getLong(cursor.getColumnIndexOrThrow(GalleryLogDatabase.COL_ID));
        entry.timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(GalleryLogDatabase.COL_TIMESTAMP));
        entry.gid = cursor.getLong(cursor.getColumnIndexOrThrow(GalleryLogDatabase.COL_GID));
        entry.title = cursor.getString(cursor.getColumnIndexOrThrow(GalleryLogDatabase.COL_TITLE));
        entry.category = cursor.getInt(cursor.getColumnIndexOrThrow(GalleryLogDatabase.COL_CATEGORY));
        entry.action = cursor.getString(cursor.getColumnIndexOrThrow(GalleryLogDatabase.COL_ACTION));
        entry.detail = cursor.getString(cursor.getColumnIndexOrThrow(GalleryLogDatabase.COL_DETAIL));
        return entry;
    }
}
