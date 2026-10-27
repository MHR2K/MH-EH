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

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 收藏数后台刷新状态持有与观察者管理
 */
object FavCountHolder {

    @Volatile
    var isRunning: Boolean = false

    @Volatile
    var isCancelled: Boolean = false

    @Volatile
    var progressCurrent: Int = 0

    @Volatile
    var progressTotal: Int = 0

    @Volatile
    var cancelAction: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    interface Listener {
        fun onProgress(current: Int, total: Int)
        fun onFinish(success: Int, total: Int, stopReason: Int)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun notifyProgress(current: Int, total: Int) {
        progressCurrent = current
        progressTotal = total
        mainHandler.post {
            for (l in listeners) {
                try {
                    l.onProgress(current, total)
                } catch (ignored: Throwable) {}
            }
        }
    }

    fun notifyFinish(success: Int, total: Int, stopReason: Int) {
        isRunning = false
        mainHandler.post {
            for (l in listeners) {
                try {
                    l.onFinish(success, total, stopReason)
                } catch (ignored: Throwable) {}
            }
        }
    }

    fun cancel() {
        isCancelled = true
        try {
            cancelAction?.invoke()
        } catch (ignored: Throwable) {}
    }

    fun clear() {
        isRunning = false
        isCancelled = false
        progressCurrent = 0
        progressTotal = 0
        cancelAction = null
    }
}
