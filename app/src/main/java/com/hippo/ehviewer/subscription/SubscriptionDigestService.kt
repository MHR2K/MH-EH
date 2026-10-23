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

package com.hippo.ehviewer.subscription

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.parser.GalleryListParser
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.scene.gallery.list.SubscriptionDigestScene
import com.hippo.scene.StageActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random

class SubscriptionDigestService : Service() {

    companion object {
        private const val TAG = "SubscriptionDigestSvc"

        const val ACTION_START = "com.hippo.ehviewer.subscription.action.START"
        const val ACTION_CANCEL = "com.hippo.ehviewer.subscription.action.CANCEL"
        const val EXTRA_TARGET_DATE = "extra_target_date"

        private const val NOTIFICATION_ID_RUNNING = 2001
        private const val NOTIFICATION_ID_DONE = 2002
        private const val CHANNEL_ID = "subscription_digest_channel"

        private val PENDING_INTENT_FLAGS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        fun start(context: Context, targetDate: String) {
            val intent = Intent(context, SubscriptionDigestService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TARGET_DATE, targetDate)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, SubscriptionDigestService::class.java).apply {
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
            SubscriptionDigestHolder.cancel()
            workerThread?.interrupt()
            stopForeground(STOP_FOREGROUND_REMOVE_COMPAT)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val targetDate = intent.getStringExtra(EXTRA_TARGET_DATE)
                ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

            if (SubscriptionDigestHolder.isRunning) {
                return START_NOT_STICKY
            }

            startForeground(NOTIFICATION_ID_RUNNING, buildRunningNotification(
                getString(R.string.subscription_digest_fetching_list, targetDate), 0, 0, true
            ))

            workerThread = Thread({
                executeDigestTask(targetDate)
            }, "SubscriptionDigestWorker").apply {
                start()
            }
        }

        return START_NOT_STICKY
    }

    private fun executeDigestTask(targetDate: String) {
        val okHttpClient = EhApplication.getOkHttpClient(applicationContext)
        val candidates = ArrayList<GalleryInfo>()

        try {
            SubscriptionDigestHolder.clear()
            SubscriptionDigestHolder.isRunning = true
            SubscriptionDigestHolder.targetDate = targetDate

            var currentUrl: String? = "${EhUrl.getWatchedUrl()}?seek=$targetDate"
            var pageCount = 0
            val maxPages = 40

            updateNotification(
                getString(R.string.subscription_digest_fetching_list, targetDate), 0, 0, true
            )
            SubscriptionDigestHolder.notifyProgress(
                0, 0, getString(R.string.subscription_digest_fetching_list, targetDate)
            )

            // Step 1: 抓取目标日期的订阅列表页
            while (!SubscriptionDigestHolder.isCancelled && !currentUrl.isNullOrEmpty() && pageCount < maxPages) {
                pageCount++

                val result: GalleryListParser.Result = EhEngine.getGalleryList(
                    null, okHttpClient, currentUrl, ListUrlBuilder.MODE_SUBSCRIPTION
                )

                val list = result.galleryInfoList
                if (list.isNullOrEmpty()) {
                    break
                }

                var hasOlderThanTarget = false
                for (gi in list) {
                    val posted = gi.posted?.trim() ?: ""
                    val postDate = if (posted.length >= 10) posted.substring(0, 10) else posted

                    if (postDate == targetDate) {
                        var exists = false
                        for (c in candidates) {
                            if (c.gid == gi.gid) {
                                exists = true
                                break
                            }
                        }
                        if (!exists) {
                            candidates.add(gi)
                        }
                    } else if (postDate.isNotEmpty() && postDate < targetDate) {
                        hasOlderThanTarget = true
                    }
                }

                if (hasOlderThanTarget) {
                    break
                }

                currentUrl = result.nextHref
                Thread.sleep(600)
            }

            if (SubscriptionDigestHolder.isCancelled) {
                notifyManager?.cancel(NOTIFICATION_ID_RUNNING)
                return
            }

            if (candidates.isEmpty()) {
                SubscriptionDigestHolder.notifyError(getString(R.string.subscription_digest_empty))
                notifyFinishedEmpty(targetDate)
                return
            }

            // Step 2: 严格防 509 限速抓取画廊收藏数 (纯内存赋值，不持久化)
            val total = candidates.size
            var successCount = 0
            var consecutiveErrors = 0
            val random = Random()

            SubscriptionDigestHolder.notifyProgress(
                0, total, getString(R.string.subscription_digest_fetching_fav, 0, total)
            )

            for (i in 0 until total) {
                if (SubscriptionDigestHolder.isCancelled) {
                    break
                }

                val gi = candidates[i]

                // 保守安全延时：1500ms + 200~500ms 随机微扰抖动
                val delayMs = 1500L + random.nextInt(400)
                Thread.sleep(delayMs)

                try {
                    val detailUrl = EhUrl.getGalleryDetailUrl(gi.gid, gi.token)
                    val favCount = EhEngine.getGalleryFavoriteCount(null, okHttpClient, detailUrl)
                    if (favCount >= 0) {
                        gi.favoriteCount = favCount
                        successCount++
                        consecutiveErrors = 0
                    }
                } catch (e: Throwable) {
                    val msg = e.message ?: ""
                    if (msg.contains("509") || msg.contains("Sad Panda") || msg.contains("429")) {
                        Log.w(TAG, "Rate limit reached: $msg")
                        SubscriptionDigestHolder.notifyError(getString(R.string.subscription_digest_rate_limited))
                        break
                    }
                    consecutiveErrors++
                    if (consecutiveErrors >= 5) {
                        Log.w(TAG, "Too many consecutive errors, aborting fav fetch safely.")
                        break
                    }
                }

                val current = i + 1
                SubscriptionDigestHolder.notifyProgress(
                    current, total, getString(R.string.subscription_digest_fetching_fav, current, total)
                )

                if (current % 2 == 0 || current == total) {
                    updateNotification(
                        getString(R.string.subscription_digest_fetching_fav, current, total),
                        current, total, false
                    )
                }
            }

            if (SubscriptionDigestHolder.isCancelled) {
                notifyManager?.cancel(NOTIFICATION_ID_RUNNING)
                return
            }

            // Step 3: 全局降序排序（按收藏数高到低，同收藏按评分高到低）
            candidates.sortWith(
                compareByDescending<GalleryInfo> { it.favoriteCount }
                    .thenByDescending { it.rating }
            )

            SubscriptionDigestHolder.allList.clear()
            SubscriptionDigestHolder.allList.addAll(candidates)
            SubscriptionDigestHolder.notifyComplete(targetDate, candidates.size)

            val maxFav = candidates.firstOrNull()?.favoriteCount ?: 0
            notifyFinishedSuccess(targetDate, candidates.size, Math.max(0, maxFav))
        } catch (t: Throwable) {
            Log.e(TAG, "Digest task failed", t)
            SubscriptionDigestHolder.notifyError(t.message ?: "Unknown error")
            notifyFinishedError(t.message ?: "Error")
        } finally {
            SubscriptionDigestHolder.isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE_COMPAT)
            stopSelf()
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.subscription_digest_service_label),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "订阅精选后台抓取与排序服务"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notifyManager?.createNotificationChannel(channel)
        }
    }

    private fun buildRunningNotification(
        statusText: String,
        current: Int,
        total: Int,
        indeterminate: Boolean
    ): android.app.Notification {
        val cancelIntent = Intent(this, SubscriptionDigestService::class.java).apply {
            action = ACTION_CANCEL
        }
        val piCancel = PendingIntent.getService(this, 1, cancelIntent, PENDING_INTENT_FLAGS)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.v_heart_x24)
            .setContentTitle(getString(R.string.subscription_digest_running))
            .setContentText(statusText)
            .setOngoing(true)
            .setAutoCancel(false)
            .setProgress(total, current, indeterminate)
            .addAction(R.drawable.v_delete_dark_x24, getString(R.string.subscription_digest_cancel), piCancel)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun updateNotification(
        statusText: String,
        current: Int,
        total: Int,
        indeterminate: Boolean
    ) {
        val notification = buildRunningNotification(statusText, current, total, indeterminate)
        notifyManager?.notify(NOTIFICATION_ID_RUNNING, notification)
    }

    private fun notifyFinishedSuccess(targetDate: String, count: Int, maxFav: Int) {
        val clickIntent = Intent(this, MainActivity::class.java).apply {
            action = StageActivity.ACTION_START_SCENE
            putExtra(StageActivity.KEY_SCENE_NAME, SubscriptionDigestScene::class.java.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val piClick = PendingIntent.getActivity(this, NOTIFICATION_ID_DONE, clickIntent, PENDING_INTENT_FLAGS)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.v_heart_x24)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle(getString(R.string.subscription_digest_done_title, targetDate))
            .setContentText(getString(R.string.subscription_digest_done_text, count, maxFav))
            .setAutoCancel(true)
            .setContentIntent(piClick)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notifyManager?.notify(NOTIFICATION_ID_DONE, notification)
    }

    private fun notifyFinishedEmpty(targetDate: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.v_heart_x24)
            .setContentTitle(getString(R.string.subscription_digest_done_title, targetDate))
            .setContentText(getString(R.string.subscription_digest_empty))
            .setAutoCancel(true)
            .build()

        notifyManager?.notify(NOTIFICATION_ID_DONE, notification)
    }

    private fun notifyFinishedError(error: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.v_heart_x24)
            .setContentTitle(getString(R.string.subscription_digest_title))
            .setContentText(getString(R.string.subscription_digest_failed, error))
            .setAutoCancel(true)
            .build()

        notifyManager?.notify(NOTIFICATION_ID_DONE, notification)
    }

    private val STOP_FOREGROUND_REMOVE_COMPAT: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            STOP_FOREGROUND_REMOVE
        } else {
            1
        }
}
