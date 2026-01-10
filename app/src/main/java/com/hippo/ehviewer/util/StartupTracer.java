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

package com.hippo.ehviewer.util;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * 启动性能追踪工具
 * 用于监测应用启动的各个阶段耗时
 */
public class StartupTracer {
    private static final String TAG = StartupTracer.class.getSimpleName();
    private static final Map<String, Long> startTimes = new HashMap<>();
    private static final Map<String, Long> durations = new HashMap<>();

    /**
     * 标记起点
     */
    public static void begin(String tag) {
        startTimes.put(tag, System.currentTimeMillis());
        Log.d(TAG, "Startup phase started: " + tag);
    }

    /**
     * 标记终点并记录耗时
     */
    public static void end(String tag) {
        Long startTime = startTimes.remove(tag);
        if (startTime != null) {
            long duration = System.currentTimeMillis() - startTime;
            durations.put(tag, duration);
            Log.d(TAG, "Startup phase ended: " + tag + " (" + duration + "ms)");
        } else {
            Log.w(TAG, "No start time found for tag: " + tag);
        }
    }

    /**
     * 获取某个阶段的耗时
     */
    public static long getDuration(String tag) {
        return durations.getOrDefault(tag, -1L);
    }

    /**
     * 打印所有阶段的耗时统计
     */
    public static void printSummary() {
        StringBuilder summary = new StringBuilder("\n=== Startup Performance Summary ===\n");
        long totalTime = 0;
        for (Map.Entry<String, Long> entry : durations.entrySet()) {
            summary.append(entry.getKey()).append(": ").append(entry.getValue()).append("ms\n");
            totalTime += entry.getValue();
        }
        summary.append("Total: ").append(totalTime).append("ms\n");
        summary.append("=====================================\n");
        Log.i(TAG, summary.toString());
    }

    /**
     * 清空所有记录
     */
    public static void clear() {
        startTimes.clear();
        durations.clear();
    }
}
