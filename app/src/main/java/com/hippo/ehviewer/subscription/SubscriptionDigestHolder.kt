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

import com.hippo.ehviewer.client.data.GalleryInfo
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 订阅精选纯内存状态管理（绝不持久化到磁盘/数据库/SharedPreferences）
 */
object SubscriptionDigestHolder {

    @Volatile
    var isRunning: Boolean = false

    @Volatile
    var isCancelled: Boolean = false

    @Volatile
    var targetDate: String = ""

    @Volatile
    var progressCurrent: Int = 0

    @Volatile
    var progressTotal: Int = 0

    @Volatile
    var statusText: String = ""

    // 内存中的结果列表，排序完成后放入
    val allList: MutableList<GalleryInfo> = Collections.synchronizedList(ArrayList())

    interface OnProgressListener {
        fun onProgress(current: Int, total: Int, status: String)
        fun onComplete(targetDate: String, count: Int)
        fun onError(error: String)
    }

    private val listeners = CopyOnWriteArrayList<OnProgressListener>()

    fun addListener(listener: OnProgressListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: OnProgressListener) {
        listeners.remove(listener)
    }

    fun notifyProgress(current: Int, total: Int, status: String) {
        progressCurrent = current
        progressTotal = total
        statusText = status
        for (l in listeners) {
            try {
                l.onProgress(current, total, status)
            } catch (ignored: Throwable) {}
        }
    }

    fun notifyComplete(targetDate: String, count: Int) {
        isRunning = false
        for (l in listeners) {
            try {
                l.onComplete(targetDate, count)
            } catch (ignored: Throwable) {}
        }
    }

    fun notifyError(error: String) {
        isRunning = false
        for (l in listeners) {
            try {
                l.onError(error)
            } catch (ignored: Throwable) {}
        }
    }

    fun cancel() {
        isCancelled = true
    }

    fun clear() {
        isRunning = false
        isCancelled = false
        progressCurrent = 0
        progressTotal = 0
        statusText = ""
        allList.clear()
    }
}
