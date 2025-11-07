/*
 * Copyright 2023 EhViewer Team
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

/**
 * 中文简繁体转换辅助类
 * 
 * 此类是对OpenCC库的封装，提供简单的中文简繁体转换接口
 * 需要添加OpenCC依赖：
 * implementation 'com.github.houbb:opencc4j:1.7.2'
 */
public class ChineseConverterHelper {

    private static final String TAG = "ChineseConverter";
    private static volatile boolean initialized = false;
    private static boolean openccAvailable = false;

    /**
     * 初始化转换器
     * 在应用启动时调用一次
     */
    public static void init() {
        if (!initialized) {
            synchronized (ChineseConverterHelper.class) {
                if (!initialized) {
                    try {
                        // 尝试调用OpenCC库方法来检查是否可用
                        String testResult = com.github.houbb.opencc4j.util.ZhConverterUtil.toTraditional("测试");
                        openccAvailable = testResult != null && !testResult.equals("测试");
                        Log.d(TAG, "OpenCC库初始化" + (openccAvailable ? "成功" : "失败") + 
                              "，测试结果: '测试' -> '" + testResult + "'");
                    } catch (Throwable e) {
                        // 捕获任何异常，包括NoClassDefFoundError等
                        openccAvailable = false;
                        Log.e(TAG, "OpenCC库初始化失败，将使用内置简单转换表", e);
                    }
                    initialized = true;
                }
            }
        }
    }

    /**
     * 检查转换库是否可用
     * @return 是否可以进行简繁体转换
     */
    public static boolean isAvailable() {
        return initialized && openccAvailable;
    }

    /**
     * 简体中文转繁体中文
     *
     * @param simplified 简体中文文本
     * @return 转换后的繁体中文文本
     */
    public static String toTraditional(String simplified) {
        if (simplified == null || simplified.isEmpty()) {
            return simplified;
        }
        
        try {
            String result;
            // 使用OpenCC进行转换
            if (openccAvailable) {
                result = com.github.houbb.opencc4j.util.ZhConverterUtil.toTraditional(simplified);
                // 始终记录转换结果，强制使用ERROR级别确保输出
                Log.e(TAG, "简体->繁体: '" + simplified + "' -> '" + result + "'");
                return result;
            } else {
                // 使用简单转换表进行转换
                result = simpleConvertToTraditional(simplified);
                Log.e(TAG, "简体->繁体(备用): '" + simplified + "' -> '" + result + "'");
                return result;
            }
        } catch (Exception e) {
            Log.e(TAG, "简体转繁体失败: " + simplified, e);
            return simplified; // 转换失败，返回原文本
        }
    }

    /**
     * 繁体中文转简体中文
     *
     * @param traditional 繁体中文文本
     * @return 转换后的简体中文文本
     */
    public static String toSimplified(String traditional) {
        if (traditional == null || traditional.isEmpty()) {
            return traditional;
        }
        
        try {
            String result;
            // 使用OpenCC进行转换
            if (openccAvailable) {
                result = com.github.houbb.opencc4j.util.ZhConverterUtil.toSimple(traditional);
                // 始终记录转换结果，强制使用ERROR级别确保输出
                Log.e(TAG, "繁体->简体: '" + traditional + "' -> '" + result + "'");
                return result;
            } else {
                // 使用简单转换表进行转换
                result = simpleConvertToSimplified(traditional);
                Log.e(TAG, "繁体->简体(备用): '" + traditional + "' -> '" + result + "'");
                return result;
            }
        } catch (Exception e) {
            Log.e(TAG, "繁体转简体失败: " + traditional, e);
            return traditional; // 转换失败，返回原文本
        }
    }

    /**
     * 使用简单转换表将简体中文转换为繁体中文
     * 仅包含常用字符，作为OpenCC不可用时的备用方案
     */
    private static String simpleConvertToTraditional(String simplified) {
        if (simplified == null) return null;
        StringBuilder sb = new StringBuilder(simplified.length());
        for (int i = 0; i < simplified.length(); i++) {
            char c = simplified.charAt(i);
            Character t = S_TO_T_MAP.get(c);
            sb.append(t != null ? t : c);
        }
        return sb.toString();
    }

    /**
     * 使用简单转换表将繁体中文转换为简体中文
     * 仅包含常用字符，作为OpenCC不可用时的备用方案
     */
    private static String simpleConvertToSimplified(String traditional) {
        if (traditional == null) return null;
        StringBuilder sb = new StringBuilder(traditional.length());
        for (int i = 0; i < traditional.length(); i++) {
            char c = traditional.charAt(i);
            Character s = T_TO_S_MAP.get(c);
            sb.append(s != null ? s : c);
        }
        return sb.toString();
    }

    // 简体到繁体的映射表（常用字）
    private static final java.util.Map<Character, Character> S_TO_T_MAP = new java.util.HashMap<>();
    // 繁体到简体的映射表（常用字）
    private static final java.util.Map<Character, Character> T_TO_S_MAP = new java.util.HashMap<>();

    static {
        // 初始化简繁体转换映射表（仅包含最常用的字符）
        final String[][] mappings = {
            {"国", "國"}, {"台", "臺"}, {"万", "萬"}, {"与", "與"}, {"东", "東"}, 
            {"专", "專"}, {"业", "業"}, {"丝", "絲"}, {"丢", "丟"}, {"两", "兩"}, 
            {"严", "嚴"}, {"丧", "喪"}, {"个", "個"}, {"临", "臨"}, {"为", "為"}, 
            {"举", "舉"}, {"义", "義"}, {"乐", "樂"}, {"乡", "鄉"}, {"书", "書"}, 
            {"买", "買"}, {"乱", "亂"}, {"了", "瞭"}, {"云", "雲"}, {"亚", "亞"}, 
            {"产", "產"}, {"亩", "畝"}, {"亲", "親"}, {"亿", "億"}, {"仅", "僅"}, 
            {"从", "從"}, {"会", "會"}, {"体", "體"}, {"余", "餘"}, {"来", "來"}, 
            {"电", "電"}, {"实", "實"}, {"写", "寫"}, {"军", "軍"}, {"农", "農"}, 
            {"冲", "衝"}, {"决", "決"}, {"况", "況"}, {"冻", "凍"}, {"凤", "鳳"}, 
            {"几", "幾"}, {"处", "處"}, {"虽", "雖"}, {"蜡", "蠟"}, {"蛮", "蠻"}, 
            {"术", "術"}, {"卫", "衛"}, {"历", "歷"}, {"发", "發"}, {"变", "變"}, 
            {"叶", "葉"}, {"号", "號"}, {"后", "後"}, {"只", "隻"}, {"岁", "歲"}, 
            {"朴", "樸"}, {"机", "機"}, {"权", "權"}, {"归", "歸"}, {"当", "當"}, 
            {"录", "錄"}, {"彻", "徹"}, {"忆", "憶"}, {"恒", "恆"}, {"恤", "卹"}, 
            {"悬", "懸"}, {"惊", "驚"}, {"惩", "懲"}, {"慑", "懾"}, {"懒", "懶"}, 
            {"战", "戰"}, {"户", "戶"}, {"执", "執"}, {"摄", "攝"}, {"敌", "敵"}, 
            {"敛", "斂"}, {"数", "數"}, {"文", "文"}, {"斋", "齋"}, {"断", "斷"}, 
            {"无", "無"}, {"时", "時"}, {"晓", "曉"}, {"晕", "暈"}, {"晖", "暉"}, 
            {"暂", "暫"}, {"术", "術"}, {"杀", "殺"}, {"杂", "雜"}, {"杯", "杯"}, 
            {"构", "構"}, {"极", "極"}, {"枪", "槍"}, {"柜", "櫃"}, {"柠", "檸"}, 
            {"柽", "檉"}, {"栖", "棲"}, {"栗", "栗"}, {"档", "檔"}, {"桥", "橋"}, 
            {"桦", "樺"}, {"梁", "梁"}, {"梦", "夢"}, {"梼", "檮"}, {"检", "檢"}, 
            {"桧", "檜"}, {"欢", "歡"}, {"权", "權"}, {"归", "歸"}, {"汤", "湯"}, 
            {"沈", "瀋"}, {"沟", "溝"}, {"没", "沒"}, {"注", "註"}, {"浏", "瀏"}, 
            {"济", "濟"}, {"涂", "塗"}, {"涛", "濤"}, {"渍", "漬"}, {"温", "溫"}, 
            {"游", "遊"}, {"满", "滿"}, {"滨", "濱"}, {"爱", "愛"}, {"烟", "煙"}, 
            {"烦", "煩"}, {"爷", "爺"}, {"牺", "犧"}, {"狮", "獅"}, {"电", "電"}, 
            {"画", "畫"}, {"畅", "暢"}, {"痴", "癡"}, {"发", "發"}, {"着", "著"}
        };
        
        // 填充简繁体转换映射表
        for (String[] pair : mappings) {
            if (pair.length == 2) {
                char s = pair[0].charAt(0);
                char t = pair[1].charAt(0);
                S_TO_T_MAP.put(s, t);
                T_TO_S_MAP.put(t, s);
            }
        }
    }
}