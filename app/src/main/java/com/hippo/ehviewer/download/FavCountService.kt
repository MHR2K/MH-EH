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

package com.hippo.ehviewer.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.scene.download.DownloadsScene
import com.hippo.ehviewer.util.FavCountStore
import com.hippo.scene.StageActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 收藏数后台刷新前台服务
 */
class FavCountService : Service() {

    companion object {
        const val ACTION_START = "com.hippo.ehviewer.download.action.FAV_COUNT_START"
        const val ACTION_CANCEL = "com.hippo.ehviewer.download.action.FAV_COUNT_CANCEL"

        const val EXTRA_GIDS = "extra_gids"

        private const val REQUEST_CANCEL = 2101
        private const val REQUEST_CLICK = 2102

        private const val NOTIFICATION_ID_RUNNING = 2101
        private const val NOTIFICATION_ID_DONE = 2102
        private const val CHANNEL_ID = "fav_count_channel"

        private val PENDING_INTENT_FLAGS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        fun start(context: Context, gids: LongArray?) {
            val intent = Intent(context, FavCountService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_GIDS, gids)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, FavCountService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    private var notifyManager: NotificationManager? = null
    private var workerThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        notifyManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CANCEL) {
            FavCountHolder.cancel()
            workerThread?.interrupt()
            stopForegroundCompat(true)
            notifyManager?.cancel(NOTIFICATION_ID_RUNNING)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            if (FavCountHolder.isRunning) {
                return START_NOT_STICKY
            }

            FavCountHolder.clear()
            FavCountHolder.isRunning = true

            val gids = intent.getLongArrayExtra(EXTRA_GIDS)

            // 立即启动前台服务，满足 Android 8+ 的 5 秒限制
            val initialNotification = buildRunningNotification(0, gids?.size ?: 0, true)
            startForegroundCompat(NOTIFICATION_ID_RUNNING, initialNotification)

            workerThread = Thread({
                executeBatchTask(gids)
            }, "FavCountWorker").apply {
                start()
            }
        }

        return START_NOT_STICKY
    }

    private fun executeBatchTask(gids: LongArray?) {
        val cancelled = AtomicBoolean(false)
        FavCountHolder.cancelAction = {
            cancelled.set(true)
        }

        val dm = EhApplication.getDownloadManager(applicationContext)
        val allList = dm.allDownloadInfoList
        val items = ArrayList<DownloadInfo>()

        if (gids != null && gids.isNotEmpty()) {
            val map = HashMap<Long, DownloadInfo>(allList.size)
            for (info in allList) {
                map[info.gid] = info
            }
            for (gid in gids) {
                val info = map[gid]
                if (info != null) {
                    items.add(info)
                }
            }
        } else {
            items.addAll(allList)
        }

        val total = items.size
        FavCountHolder.notifyProgress(0, total)

        if (total == 0 || FavCountHolder.isCancelled) {
            FavCountHolder.notifyFinish(0, 0, EhEngine.STOP_REASON_COMPLETED)
            stopForegroundCompat(true)
            notifyManager?.cancel(NOTIFICATION_ID_RUNNING)
            stopSelf()
            return
        }

        val okHttpClient = EhApplication.getOkHttpClient(applicationContext)
        var lastNotifyTime = 0L

        val result = EhEngine.batchGetFavoriteCounts(
            okHttpClient,
            ArrayList<GalleryInfo>(items),
            2,    // 2并发
            1000, // 批间间隔1秒
            cancelled
        ) { current, totalCount ->
            FavCountHolder.notifyProgress(current, totalCount)
            val now = System.currentTimeMillis()
            if (now - lastNotifyTime > 500 || current == totalCount) {
                lastNotifyTime = now
                updateRunningNotification(current, totalCount)
            }
        }

        val success = if (result.isNotEmpty()) result[0] else 0
        val totalCount = if (result.size >= 2) result[1] else total
        val stopReason = if (result.size >= 3) result[2] else EhEngine.STOP_REASON_COMPLETED

        // 持久化到磁盘
        FavCountStore.saveFrom(items)

        // 通知观察者
        FavCountHolder.notifyFinish(success, totalCount, stopReason)

        // 停止前台服务并移除运行中常驻通知
        stopForegroundCompat(true)
        notifyManager?.cancel(NOTIFICATION_ID_RUNNING)

        // 若不是被主动取消，显示完成或异常通知
        if (stopReason != EhEngine.STOP_REASON_CANCELLED && !FavCountHolder.isCancelled) {
            when (stopReason) {
                EhEngine.STOP_REASON_RATE_LIMIT -> {
                    notifyFinishedError(getString(R.string.download_refresh_fav_count_abort_509))
                }
                EhEngine.STOP_REASON_SAD_PANDA -> {
                    notifyFinishedError(getString(R.string.download_refresh_fav_count_abort_sad_panda))
                }
                EhEngine.STOP_REASON_CONSECUTIVE_FAILURES -> {
                    notifyFinishedError(getString(R.string.download_refresh_fav_count_abort_failed))
                }
                else -> {
                    notifyFinishedSuccess(success, totalCount)
                }
            }
        }

        stopSelf()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_refresh_fav_count_service_label),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.download_refresh_fav_count)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notifyManager?.createNotificationChannel(channel)
        }
    }

    private fun buildRunningNotification(
        current: Int,
        total: Int,
        indeterminate: Boolean
    ): Notification {
        val cancelIntent = Intent(this, FavCountService::class.java).apply {
            action = ACTION_CANCEL
        }
        val piCancel = PendingIntent.getService(this, REQUEST_CANCEL, cancelIntent, PENDING_INTENT_FLAGS)

        val clickIntent = Intent(this, MainActivity::class.java).apply {
            action = StageActivity.ACTION_START_SCENE
            putExtra(StageActivity.KEY_SCENE_NAME, DownloadsScene::class.java.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val piClick = PendingIntent.getActivity(this, REQUEST_CLICK, clickIntent, PENDING_INTENT_FLAGS)

        val contentText = if (total > 0) {
            getString(R.string.download_refresh_fav_count_progress, current, total)
        } else {
            getString(R.string.download_refresh_fav_count)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.v_heart_x24)
            .setContentTitle(getString(R.string.download_refresh_fav_count))
            .setContentText(contentText)
            .setContentIntent(piClick)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setProgress(total, current, indeterminate)
            .addAction(R.drawable.v_close_dark_x24, getString(android.R.string.cancel), piCancel)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun updateRunningNotification(current: Int, total: Int) {
        val notification = buildRunningNotification(current, total, false)
        notifyManager?.notify(NOTIFICATION_ID_RUNNING, notification)
    }

    private fun notifyFinishedSuccess(success: Int, total: Int) {
        val clickIntent = Intent(this, MainActivity::class.java).apply {
            action = StageActivity.ACTION_START_SCENE
            putExtra(StageActivity.KEY_SCENE_NAME, DownloadsScene::class.java.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val piClick = PendingIntent.getActivity(this, REQUEST_CLICK, clickIntent, PENDING_INTENT_FLAGS)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.v_heart_x24)
            .setContentTitle(getString(R.string.download_refresh_fav_count))
            .setContentText(getString(R.string.download_refresh_fav_count_done, success))
            .setAutoCancel(true)
            .setContentIntent(piClick)
            .build()

        notifyManager?.notify(NOTIFICATION_ID_DONE, notification)
    }

    private fun notifyFinishedError(error: String) {
        val clickIntent = Intent(this, MainActivity::class.java).apply {
            action = StageActivity.ACTION_START_SCENE
            putExtra(StageActivity.KEY_SCENE_NAME, DownloadsScene::class.java.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val piClick = PendingIntent.getActivity(this, REQUEST_CLICK, clickIntent, PENDING_INTENT_FLAGS)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.v_heart_x24)
            .setContentTitle(getString(R.string.download_refresh_fav_count))
            .setContentText(error)
            .setAutoCancel(true)
            .setContentIntent(piClick)
            .build()

        notifyManager?.notify(NOTIFICATION_ID_DONE, notification)
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }
}
