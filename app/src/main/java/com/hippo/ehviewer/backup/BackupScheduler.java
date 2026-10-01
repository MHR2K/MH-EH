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

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.hippo.ehviewer.Settings;

import java.util.concurrent.TimeUnit;

/**
 * 自动备份调度器
 * 使用 WorkManager 进行定时备份，不阻塞主线程
 */
public class BackupScheduler {
    private static final String TAG = BackupScheduler.class.getSimpleName();
    private static final String BACKUP_WORK_NAME = "auto_backup_work";

    /**
     * 初始化自动备份任务
     * 备份频率：每24小时一次
     */
    public static void scheduleAutoBackup(Context context) {
        if (!Settings.getAutoBackupEnabled()) {
            // 如果禁用备份，取消现有的定时任务
            WorkManager.getInstance(context).cancelUniqueWork(BACKUP_WORK_NAME);
            Log.i(TAG, "Auto backup is disabled, cancelled scheduled work");
            return;
        }

        try {
            // 创建约束条件：仅在低电量时不执行
            Constraints constraints = new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build();

            // 创建定期备份任务：每24小时执行一次
            PeriodicWorkRequest backupRequest = new PeriodicWorkRequest.Builder(
                    AutoBackupWorker.class,
                    24, TimeUnit.HOURS,
                    30, TimeUnit.MINUTES  // 灵活间隔30分钟
            )
                    .setConstraints(constraints)
                    .addTag(BACKUP_WORK_NAME)
                    .build();

            // 使用 KEEP 策略：若已存在则保留现有调度，避免更新约束导致立即触发
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    BACKUP_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    backupRequest
            );

            Log.i(TAG, "Auto backup task scheduled successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling auto backup", e);
        }
    }

    /**
     * 取消自动备份任务
     */
    public static void cancelAutoBackup(Context context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(BACKUP_WORK_NAME);
            Log.i(TAG, "Auto backup task cancelled");
        } catch (Exception e) {
            Log.e(TAG, "Error cancelling auto backup", e);
        }
    }
}