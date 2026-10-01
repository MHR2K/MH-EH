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

package com.hippo.ehviewer.backup;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.Settings;
import com.hippo.util.ReadableTime;

import java.io.File;
import java.util.Arrays;
import java.util.Calendar;

/**
 * 自动备份 Worker
 * 在后台线程中执行完整数据备份，不阻塞主线程
 */
public class AutoBackupWorker extends Worker {
    private static final String TAG = AutoBackupWorker.class.getSimpleName();

    public AutoBackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!Settings.getAutoBackupEnabled()) {
            Log.i(TAG, "Auto backup is disabled");
            return Result.success();
        }

        long now = System.currentTimeMillis();

        // 清理上次中断留下的临时目录
        File staleTempDir = new File(getApplicationContext().getCacheDir(), "backup_temp");
        if (staleTempDir.exists()) {
            BackupManager.deleteDirectory(staleTempDir);
            Log.i(TAG, "Cleaned up stale temp directory");
        }

        // 去重检查：今天已备份则跳过
        long lastBackup = Settings.getLastBackupTime();
        if (isSameDay(lastBackup, now)) {
            Log.i(TAG, "Already backed up today, skipping");
            return Result.success();
        }

        try {
            File backupDir = AppConfig.getDirInExternalAppDir("backup");
            if (backupDir == null) {
                Log.e(TAG, "Backup directory creation failed");
                return Result.retry();
            }

            // 确保备份目录存在
            if (!backupDir.exists() && !backupDir.mkdirs()) {
                Log.e(TAG, "Failed to create backup directory: " + backupDir.getAbsolutePath());
                return Result.retry();
            }

            String filename = "backup_" + ReadableTime.getFilenamableTime(now) + ".zip";
            File backupFile = new File(backupDir, filename);

            if (BackupManager.createFullBackup(getApplicationContext(), backupFile)) {
                Settings.putLastBackupTime(now);
                // 清理旧备份文件（按天数）
                cleanupOldBackups(backupDir);
                Log.i(TAG, "Auto backup successful: " + backupFile.getAbsolutePath());
                return Result.success();
            } else {
                Log.e(TAG, "Auto backup failed: createFullBackup returned false");
                // 删除可能部分写入的文件
                if (backupFile.exists()) {
                    backupFile.delete();
                }
                return Result.retry();
            }
        } catch (Exception e) {
            Log.e(TAG, "Auto backup failed with exception", e);
            return Result.retry();
        }
    }

    /**
     * 判断两个时间戳是否在同一天
     */
    private static boolean isSameDay(long time1, long time2) {
        if (time1 == 0 || time2 == 0) {
            return false;
        }
        Calendar cal1 = Calendar.getInstance();
        Calendar cal2 = Calendar.getInstance();
        cal1.setTimeInMillis(time1);
        cal2.setTimeInMillis(time2);
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
                && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }

    /**
     * 按保留天数清理旧备份文件
     * 删除修改时间超过 retentionDays 天的备份
     */
    private void cleanupOldBackups(File backupDir) {
        try {
            int retentionDays = Settings.getBackupRetentionDays();
            long cutoffTime = System.currentTimeMillis() - (long) retentionDays * 24 * 60 * 60 * 1000;

            File[] backupFiles = backupDir.listFiles((dir, name) ->
                    (name.startsWith("backup_") && name.endsWith(".db"))
                    || (name.startsWith("backup_") && name.endsWith(".zip")));
            if (backupFiles == null || backupFiles.length == 0) {
                return;
            }

            for (File file : backupFiles) {
                if (file.lastModified() < cutoffTime) {
                    if (!file.delete()) {
                        Log.w(TAG, "Failed to delete old backup: " + file.getAbsolutePath());
                    } else {
                        Log.i(TAG, "Deleted old backup: " + file.getName());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cleaning up old backups", e);
        }
    }
}