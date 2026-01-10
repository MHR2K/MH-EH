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
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.util.ReadableTime;
import com.hippo.lib.yorozuya.FileUtils;

import java.io.File;
import java.util.Arrays;

/**
 * 自动备份 Worker
 * 在后台线程中执行数据库备份，不阻塞主线程
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

        // 若应用当前处于前台使用（存在顶层 Activity），避免在用户操作时触发备份造成卡顿
        try {
            com.hippo.ehviewer.EhApplication app = com.hippo.ehviewer.EhApplication.getInstance();
            if (app != null && app.getTopActivity() != null) {
                Log.i(TAG, "Skip auto backup: app in foreground");
                return Result.success();
            }
        } catch (Throwable ignored) {
            // 前台检测失败不影响后续逻辑
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

            String filename = "backup_" + ReadableTime.getFilenamableTime(System.currentTimeMillis()) + ".db";
            File backupFile = new File(backupDir, filename);

            if (EhDB.exportDB(getApplicationContext(), backupFile)) {
                Settings.putLastBackupTime(System.currentTimeMillis());
                // 清理旧备份文件
                cleanupOldBackups(backupDir);
                Log.i(TAG, "Auto backup successful: " + backupFile.getAbsolutePath());
                return Result.success();
            } else {
                Log.e(TAG, "Auto backup failed: exportDB returned false");
                return Result.retry();
            }
        } catch (Exception e) {
            Log.e(TAG, "Auto backup failed with exception", e);
            return Result.retry();
        }
    }

    private void cleanupOldBackups(File backupDir) {
        try {
            File[] backupFiles = backupDir.listFiles((dir, name) -> name.startsWith("backup_") && name.endsWith(".db"));
            if (backupFiles == null || backupFiles.length <= Settings.getBackupRetentionDays()) {
                return;
            }

            // 按修改时间排序，最新的在前面
            Arrays.sort(backupFiles, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

            // 删除超过保留天数的旧备份
            for (int i = Settings.getBackupRetentionDays(); i < backupFiles.length; i++) {
                if (!backupFiles[i].delete()) {
                    Log.w(TAG, "Failed to delete old backup: " + backupFiles[i].getAbsolutePath());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cleaning up old backups", e);
        }
    }
}
