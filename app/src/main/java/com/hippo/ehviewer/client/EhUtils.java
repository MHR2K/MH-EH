/*
 * Copyright 2016 Hippo Seven
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

package com.hippo.ehviewer.client;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.util.AppHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

public class EhUtils {

    public static final int NONE = -1; // Use it for homepage
    public static final int UNKNOWN = 0x400;

    public static final int ALL_CATEGORY = EhUtils.UNKNOWN - 1;
    //DOUJINSHI|MANGA|ARTIST_CG|GAME_CG|WESTERN|NON_H|IMAGE_SET|COSPLAY|ASIAN_PORN|MISC;

    public static final int BG_COLOR_DOUJINSHI = 0xfff44336;
    public static final int BG_COLOR_MANGA = 0xffff9800;
    public static final int BG_COLOR_ARTIST_CG = 0xfffbc02d;
    public static final int BG_COLOR_GAME_CG = 0xff4caf50;
    public static final int BG_COLOR_WESTERN = 0xff8bc34a;
    public static final int BG_COLOR_NON_H = 0xff2196f3;
    public static final int BG_COLOR_IMAGE_SET = 0xff3f51b5;
    public static final int BG_COLOR_COSPLAY = 0xff9c27b0;
    public static final int BG_COLOR_ASIAN_PORN = 0xff9575cd;
    public static final int BG_COLOR_MISC = 0xfff06292;
    public static final int BG_COLOR_UNKNOWN = Color.BLACK;

    // Remove [XXX], (XXX), {XXX}, ~XXX~ stuff
    public static final Pattern PATTERN_TITLE_PREFIX = Pattern.compile(
            "^(?:(?:\\([^\\)]*\\))|(?:\\[[^\\]]*\\])|(?:\\{[^\\}]*\\})|(?:~[^~]*~)|\\s+)*");
    // Remove [XXX], (XXX), {XXX}, ~XXX~ stuff and something like ch. 1-23
    public static final Pattern PATTERN_TITLE_SUFFIX = Pattern.compile(
            "(?:\\s+ch.[\\s\\d-]+)?(?:(?:\\([^\\)]*\\))|(?:\\[[^\\]]*\\])|(?:\\{[^\\}]*\\})|(?:~[^~]*~)|\\s+)*$",
            Pattern.CASE_INSENSITIVE);
            
    // 模糊搜索相似度阈值
    private static final double FUZZY_SEARCH_THRESHOLD = 0.8;

    private static final int[] CATEGORY_VALUES = {
            EhConfig.MISC,
            EhConfig.DOUJINSHI,
            EhConfig.MANGA,
            EhConfig.ARTIST_CG,
            EhConfig.GAME_CG,
            EhConfig.IMAGE_SET,
            EhConfig.COSPLAY,
            EhConfig.ASIAN_PORN,
            EhConfig.NON_H,
            EhConfig.WESTERN,
            UNKNOWN };

    private static final String[][] CATEGORY_STRINGS = {
            new String[] { "misc" },
            new String[] { "doujinshi" },
            new String[] { "manga" },
            new String[] { "artistcg", "Artist CG Sets", "Artist CG" },
            new String[] { "gamecg", "Game CG Sets", "Game CG" },
            new String[] { "imageset", "Image Sets", "Image Set" },
            new String[] { "cosplay" },
            new String[] { "asianporn", "Asian Porn" },
            new String[] { "non-h" },
            new String[] { "western" },
            new String[] { "unknown" }
    };

    public static int getCategory(String type) {
        int i;
        for (i = 0; i < CATEGORY_STRINGS.length - 1; i++) {
            for (String str : CATEGORY_STRINGS[i])
                if (str.equalsIgnoreCase(type))
                    return CATEGORY_VALUES[i];
        }

        return CATEGORY_VALUES[i];
    }

    public static String getCategory(int type) {
        int i;
        for (i = 0; i < CATEGORY_VALUES.length - 1; i++) {
            if (CATEGORY_VALUES[i] == type)
                break;
        }
        return CATEGORY_STRINGS[i][0];
    }

    public static int getCategoryColor(int category) {
        switch (category) {
            case EhConfig.DOUJINSHI:
                return BG_COLOR_DOUJINSHI;
            case EhConfig.MANGA:
                return BG_COLOR_MANGA;
            case EhConfig.ARTIST_CG:
                return BG_COLOR_ARTIST_CG;
            case EhConfig.GAME_CG:
                return BG_COLOR_GAME_CG;
            case EhConfig.WESTERN:
                return BG_COLOR_WESTERN;
            case EhConfig.NON_H:
                return BG_COLOR_NON_H;
            case EhConfig.IMAGE_SET:
                return BG_COLOR_IMAGE_SET;
            case EhConfig.COSPLAY:
                return BG_COLOR_COSPLAY;
            case EhConfig.ASIAN_PORN:
                return BG_COLOR_ASIAN_PORN;
            case EhConfig.MISC:
                return BG_COLOR_MISC;
            default:
                return BG_COLOR_UNKNOWN;
        }
    }

    public static void signOut(Context context) {
        EhApplication.getEhCookieStore(context).signOut();
        Settings.putAvatar(null);
        Settings.putDisplayName(null);
        Settings.putNeedSignIn(true);
    }

    public static boolean needSignedIn(Context context) {
        return Settings.getNeedSignIn() && !EhApplication.getEhCookieStore(context).hasSignedIn();
    }

    public static String getSuitableTitle(GalleryInfo gi) {
        if (Settings.getShowJpnTitle()) {
            return TextUtils.isEmpty(gi.titleJpn) ? gi.title : gi.titleJpn;
        } else {
            return TextUtils.isEmpty(gi.title) ? gi.titleJpn : gi.title;
        }
    }

    /**
     * 判断画廊信息是否符合搜索关键词（默认精确搜索，区分大小写）
     * @param gi 画廊信息对象
     * @param key 搜索关键词
     * @return 是否匹配
     */
    public static boolean judgeSuitableTitle(GalleryInfo gi, String key) {
        return judgeSuitableTitle(gi, key, false, true);
    }
    
    public static boolean judgeSuitableTitle(GalleryInfo gi, String key, boolean fuzzySearch, boolean caseSensitive) {
        return judgeSuitableTitle(gi, key, fuzzySearch, caseSensitive, Settings.getEnableChineseConversion());
    }

    /**
     * 计算字符串与查询词的相似度分数
     * @param text 要比较的文本
     * @param query 查询词
     * @param fuzzySearch 是否进行模糊搜索
     * @param caseSensitive 是否区分大小写
     * @param enableChineseConversion 是否启用中文转换
     * @return 相似度分数，范围0.0-1.0，值越大表示越相似
     */
    public static double calculateSimilarityScore(String text, String query, boolean fuzzySearch, 
                                                 boolean caseSensitive, boolean enableChineseConversion) {
        // 处理大小写敏感选项
        if (!caseSensitive) {
            text = text.toLowerCase();
            query = query.toLowerCase();
        }
        
        // 精确匹配给予最高分
        if (text.contains(query)) {
            return 1.0;
        }
        
        // 即使不进行模糊搜索，也对精确匹配支持简繁体转换
        if (enableChineseConversion && containsChinese(query)) {
            // 添加日志检查转换功能是否可用
            // 不检查日志级别，强制输出日志
            android.util.Log.e("ChineseSearch", "开始简繁体转换匹配，查询词: " + query + ", 文本: " + (text.length() > 50 ? text.substring(0, 50) + "..." : text));
            android.util.Log.e("ChineseSearch", "转换库可用状态: " + 
                com.hippo.ehviewer.util.ChineseConverterHelper.isAvailable());
            
            // 尝试简繁体转换后进行精确匹配
            
            // 方案1：查询转繁体，文本保持原样
            String traditionalQuery = com.hippo.ehviewer.util.ChineseConverterHelper.toTraditional(query);
            // 强制输出日志，不考虑日志级别
            android.util.Log.e("ChineseSearch", "方案1 - 查询转繁体: " + traditionalQuery);
            if (!traditionalQuery.equals(query) && text.contains(traditionalQuery)) {
                android.util.Log.e("ChineseSearch", "方案1成功: 查询转繁体后匹配成功");
                return 1.0;
            }
            
            // 方案2：查询转简体，文本保持原样
            String simplifiedQuery = com.hippo.ehviewer.util.ChineseConverterHelper.toSimplified(query);
            // 强制输出日志，不考虑日志级别
            android.util.Log.e("ChineseSearch", "方案2 - 查询转简体: " + simplifiedQuery);
            if (!simplifiedQuery.equals(query) && text.contains(simplifiedQuery)) {
                android.util.Log.e("ChineseSearch", "方案2成功: 查询转简体后匹配成功");
                return 1.0;
            }
            
            // 方案3：查询保持原样，文本转繁体
            String traditionalText = com.hippo.ehviewer.util.ChineseConverterHelper.toTraditional(text);
            // 强制输出日志，不考虑日志级别
            android.util.Log.e("ChineseSearch", "方案3 - 文本转繁体: " + traditionalText);
            if (!traditionalText.equals(text) && traditionalText.contains(query)) {
                android.util.Log.e("ChineseSearch", "方案3成功: 文本转繁体后匹配成功");
                return 1.0;
            }
            
            // 方案4：查询保持原样，文本转简体
            String simplifiedText = com.hippo.ehviewer.util.ChineseConverterHelper.toSimplified(text);
            // 强制输出日志，不考虑日志级别
            android.util.Log.e("ChineseSearch", "方案4 - 文本转简体: " + simplifiedText);
            if (!simplifiedText.equals(text) && simplifiedText.contains(query)) {
                android.util.Log.e("ChineseSearch", "方案4成功: 文本转简体后匹配成功");
                return 1.0;
            }
            
            // 记录失败情况，强制输出日志
            android.util.Log.e("ChineseSearch", "所有简繁体转换方案均未匹配成功");
        }
        
        if (fuzzySearch) {
            // 使用模糊匹配相似度计算
            if (optimizedMultiLanguageFuzzyContains(text, query, enableChineseConversion)) {
                // 估算相似度分数 - 使用改进的相似度计算
                return calculateImprovedSimilarity(text, query);
            }
        }
        
        return 0.0; // 不匹配
    }

    /**
     * 增强版标题匹配方法，支持多语言（简体中文、繁体中文、日语、英语）的模糊查询
     * 
     * @param gi 画廊信息对象
     * @param key 搜索关键词
     * @param fuzzySearch 是否进行模糊搜索
     * @param caseSensitive 是否区分大小写
     * @return 是否匹配
     */
    public static boolean judgeSuitableTitle(GalleryInfo gi, String key, boolean fuzzySearch, boolean caseSensitive, boolean enableChineseConversion) {
        // 合并日文和英文标题以提高搜索命中率
        String titleB = (gi.titleJpn != null ? gi.titleJpn : "") + " " + (gi.title != null ? gi.title : "");
        
        // 处理大小写敏感选项
        if (!caseSensitive) {
            titleB = titleB.toLowerCase();
            key = key.toLowerCase();
        }
        
        // 如果查询词为空或标题为空，直接返回
        if (key.isEmpty() || titleB.isEmpty()) {
            return false;
        }
        
        // 处理多个关键词的情况（以空格分隔）
        String[] keywords = key.trim().split("\\s+");
        if (keywords.length > 1) {
            // 多关键词模式：所有关键词都必须匹配
            for (String keyword : keywords) {
                if (keyword.isEmpty()) continue;
                
                boolean matched = fuzzySearch 
                    ? optimizedFuzzyContains(titleB, keyword, FUZZY_SEARCH_THRESHOLD)
                    : fastContains(titleB, keyword);  // 使用高效算法
                
                if (!matched) return false;
            }
            return true;
        } else if (fuzzySearch) {
            // 单关键词模糊搜索
            return optimizedMultiLanguageFuzzyContains(titleB, key, enableChineseConversion);
        } else {
            // 单关键词精确搜索
            // 先尝试直接匹配
            if (titleB.contains(key)) {
                return true;
            }
            // 如果启用了简繁体转换，就使用计算相似度的方法（内部包含了简繁体转换逻辑）
            if (enableChineseConversion && containsChinese(key)) {
                return calculateSimilarityScore(titleB, key, false, true, true) > 0;
            }
            return false;
        }
    }

    /**
     * 优化的模糊匹配算法
     * 使用LRU缓存减少重复计算开销，提高搜索性能
     */
    // LRU缓存实现，默认大小128，访问顺序排序
    private static final int FUZZY_CACHE_SIZE = 256; // 增大缓存大小以提高命中率
    private static final Map<String, Boolean> fuzzyCache = new LinkedHashMap<String, Boolean>(FUZZY_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > FUZZY_CACHE_SIZE;
        }
    };

    /**
     * 针对多语言优化的模糊搜索入口
     * 优化了简体中文、繁体中文、日语、英语的搜索体验
     * 
     * @param text 搜索文本
     * @param query 查询关键词
     * @return 是否匹配
     */
    public static boolean optimizedMultiLanguageFuzzyContains(String text, String query) {
        return optimizedMultiLanguageFuzzyContains(text, query, true);
    }
    
    /**
     * 针对多语言优化的模糊搜索入口
     * 优化了简体中文、繁体中文、日语、英语的搜索体验
     * 
     * @param text 搜索文本
     * @param query 查询关键词
     * @param enableChineseConversion 是否启用简繁体转换
     * @return 是否匹配
     */
    public static boolean optimizedMultiLanguageFuzzyContains(String text, String query, boolean enableChineseConversion) {
        // 应用默认阈值进行模糊搜索
        if (optimizedFuzzyContains(text, query, FUZZY_SEARCH_THRESHOLD)) {
            return true;
        }
        
        // 尝试简繁体转换匹配（如果启用）
        if (enableChineseConversion) {
            boolean hasChineseQuery = containsChinese(query);
            boolean hasChineseText = containsChinese(text);
            
            if (hasChineseQuery || hasChineseText) {
                // 使用专业转换库进行简繁体转换
                // 同时转换查询和文本，增加匹配可能性
                
                // 方案1：查询转繁体，文本保持原样
                String traditionalQuery = com.hippo.ehviewer.util.ChineseConverterHelper.toTraditional(query);
                if (!traditionalQuery.equals(query) && optimizedFuzzyContains(text, traditionalQuery, FUZZY_SEARCH_THRESHOLD)) {
                    return true;
                }
                
                // 方案2：查询转简体，文本保持原样
                String simplifiedQuery = com.hippo.ehviewer.util.ChineseConverterHelper.toSimplified(query);
                if (!simplifiedQuery.equals(query) && optimizedFuzzyContains(text, simplifiedQuery, FUZZY_SEARCH_THRESHOLD)) {
                    return true;
                }
                
                // 方案3：查询保持原样，文本转繁体
                String traditionalText = com.hippo.ehviewer.util.ChineseConverterHelper.toTraditional(text);
                if (!traditionalText.equals(text) && optimizedFuzzyContains(traditionalText, query, FUZZY_SEARCH_THRESHOLD)) {
                    return true;
                }
                
                // 方案4：查询保持原样，文本转简体
                String simplifiedText = com.hippo.ehviewer.util.ChineseConverterHelper.toSimplified(text);
                if (!simplifiedText.equals(text) && optimizedFuzzyContains(simplifiedText, query, FUZZY_SEARCH_THRESHOLD)) {
                    return true;
                }
                
                // 方案5：查询和文本都转繁体
                if (!traditionalQuery.equals(query) && !traditionalText.equals(text) && 
                    optimizedFuzzyContains(traditionalText, traditionalQuery, FUZZY_SEARCH_THRESHOLD)) {
                    return true;
                }
                
                // 方案6：查询和文本都转简体
                if (!simplifiedQuery.equals(query) && !simplifiedText.equals(text) && 
                    optimizedFuzzyContains(simplifiedText, simplifiedQuery, FUZZY_SEARCH_THRESHOLD)) {
                    return true;
                }
            }
        }
        
        // 尝试罗马音匹配 (针对日语)
        if (containsJapanese(text)) {
            String romajiQuery = toRomaji(query);
            if (!romajiQuery.equals(query) && optimizedFuzzyContains(text, romajiQuery, FUZZY_SEARCH_THRESHOLD)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 优化的模糊匹配算法
     */
    public static boolean optimizedFuzzyContains(String text, String query, double threshold) {
        if (text == null || query == null || query.isEmpty()) {
            return false;
        }

        // 归一化和预处理
        text = normalizeText(text);
        query = normalizeText(query);

        // 缓存key
        String cacheKey = text + "" + query + "" + threshold;
        synchronized (fuzzyCache) {
            if (fuzzyCache.containsKey(cacheKey)) {
                return fuzzyCache.get(cacheKey);
            }
        }

        // 阈值动态调整
        double dynamicThreshold = getDynamicThreshold(query, threshold);

        boolean result;
        // 1. 先尝试精确匹配（最快）
        if (text.contains(query)) {
            result = true;
        } else if (query.length() <= 3) {
            // 2. 短查询词使用改进的模糊匹配
            result = shortQueryFuzzyMatch(text, query, dynamicThreshold);
        } else {
            // 3. 长查询词使用更高效的算法
            result = longQueryFuzzyMatch(text, query, dynamicThreshold);
        }

        // 拼音支持：如原始匹配失败且含有汉字，则转拼音再匹配
        if (!result && (containsChinese(text) || containsChinese(query))) {
            String textPinyin = toPinyin(text);
            String queryPinyin = toPinyin(query);
            if (!textPinyin.isEmpty() && !queryPinyin.isEmpty()) {
                // 拼音再次缓存
                String pyCacheKey = textPinyin + "" + queryPinyin + "" + dynamicThreshold;
                synchronized (fuzzyCache) {
                    if (fuzzyCache.containsKey(pyCacheKey)) {
                        return fuzzyCache.get(pyCacheKey);
                    }
                }
                boolean pyResult;
                if (textPinyin.contains(queryPinyin)) {
                    pyResult = true;
                } else if (queryPinyin.length() <= 3) {
                    pyResult = shortQueryFuzzyMatch(textPinyin, queryPinyin, dynamicThreshold);
                } else {
                    pyResult = longQueryFuzzyMatch(textPinyin, queryPinyin, dynamicThreshold);
                }
                synchronized (fuzzyCache) {
                    fuzzyCache.put(pyCacheKey, pyResult);
                }
                result = pyResult;
            }
        }

        synchronized (fuzzyCache) {
            fuzzyCache.put(cacheKey, result);
        }
        return result;
    }

    /**
     * 判断字符串是否包含汉字
     */
    private static boolean containsChinese(String s) {
        return s != null && s.matches(".*[\\u4e00-\\u9fa5].*");
    }
    
    /**
     * 检测文本是否包含日语字符
     */
    private static boolean containsJapanese(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.codePoints().anyMatch(codepoint -> {
            return (codepoint >= 0x3040 && codepoint <= 0x309F) || // Hiragana
                   (codepoint >= 0x30A0 && codepoint <= 0x30FF);   // Katakana
        });
    }
    
    // 简繁体转换方法已迁移到 com.hippo.ehviewer.util.ChineseConverterHelper
    // 请使用 ChineseConverterHelper.toTraditional() 和 ChineseConverterHelper.toSimplified() 方法代替
    
    /**
     * 将日语转为罗马音
     * 注：实际应用中应使用专业的转换库，此处为示例
     */
    private static String toRomaji(String japanese) {
        // 实际应用中应使用成熟的日语罗马音转换库
        if (japanese == null || japanese.isEmpty()) {
            return japanese;
        }
        
        HashMap<Character, String> jpToRomajiMap = new HashMap<>();
        // 平假名
        jpToRomajiMap.put('あ', "a"); jpToRomajiMap.put('い', "i"); jpToRomajiMap.put('う', "u");
        jpToRomajiMap.put('え', "e"); jpToRomajiMap.put('お', "o"); jpToRomajiMap.put('か', "ka");
        jpToRomajiMap.put('き', "ki"); jpToRomajiMap.put('く', "ku"); jpToRomajiMap.put('け', "ke");
        jpToRomajiMap.put('こ', "ko"); jpToRomajiMap.put('さ', "sa"); jpToRomajiMap.put('し', "shi");
        jpToRomajiMap.put('す', "su"); jpToRomajiMap.put('せ', "se"); jpToRomajiMap.put('そ', "so");
        jpToRomajiMap.put('た', "ta"); jpToRomajiMap.put('ち', "chi"); jpToRomajiMap.put('つ', "tsu");
        jpToRomajiMap.put('て', "te"); jpToRomajiMap.put('と', "to"); jpToRomajiMap.put('な', "na");
        jpToRomajiMap.put('に', "ni"); jpToRomajiMap.put('ぬ', "nu"); jpToRomajiMap.put('ね', "ne");
        jpToRomajiMap.put('の', "no"); jpToRomajiMap.put('は', "ha"); jpToRomajiMap.put('ひ', "hi");
        jpToRomajiMap.put('ふ', "fu"); jpToRomajiMap.put('へ', "he"); jpToRomajiMap.put('ほ', "ho");
        
        // 片假名
        jpToRomajiMap.put('ア', "a"); jpToRomajiMap.put('イ', "i"); jpToRomajiMap.put('ウ', "u");
        jpToRomajiMap.put('エ', "e"); jpToRomajiMap.put('オ', "o"); jpToRomajiMap.put('カ', "ka");
        jpToRomajiMap.put('キ', "ki"); jpToRomajiMap.put('ク', "ku"); jpToRomajiMap.put('ケ', "ke");
        jpToRomajiMap.put('コ', "ko"); jpToRomajiMap.put('サ', "sa"); jpToRomajiMap.put('シ', "shi");
        jpToRomajiMap.put('ス', "su"); jpToRomajiMap.put('セ', "se"); jpToRomajiMap.put('ソ', "so");
        jpToRomajiMap.put('タ', "ta"); jpToRomajiMap.put('チ', "chi"); jpToRomajiMap.put('ツ', "tsu");
        jpToRomajiMap.put('テ', "te"); jpToRomajiMap.put('ト', "to"); jpToRomajiMap.put('ナ', "na");
        jpToRomajiMap.put('ニ', "ni"); jpToRomajiMap.put('ヌ', "nu"); jpToRomajiMap.put('ネ', "ne");
        jpToRomajiMap.put('ノ', "no"); jpToRomajiMap.put('ハ', "ha"); jpToRomajiMap.put('ヒ', "hi");
        jpToRomajiMap.put('フ', "fu"); jpToRomajiMap.put('ヘ', "he"); jpToRomajiMap.put('ホ', "ho");
        
        StringBuilder result = new StringBuilder();
        for (char c : japanese.toCharArray()) {
            String romaji = jpToRomajiMap.get(c);
            result.append(romaji != null ? romaji : c);
        }
        
        return result.toString();
    }

    /**
     * 获取动态调整的阈值
     */
    private static double getDynamicThreshold(String query, double threshold) {
        // 阈值动态调整
        double dynamicThreshold = threshold;
        if (threshold <= 0) {
            // 动态调整规则：短词要求更高相似度，长词适当放宽
            int qlen = query.length();
            if (qlen == 1) {
                dynamicThreshold = 1.0;
            } else if (qlen == 2) {
                dynamicThreshold = 0.85;
            } else if (qlen <= 4) {
                dynamicThreshold = 0.8;
            } else if (qlen <= 8) {
                dynamicThreshold = 0.75;
            } else {
                dynamicThreshold = 0.7;
            }
            // CJK字符比例高时适当放宽
            int cjkCount = query.replaceAll("[^\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]", "").length();
            if (qlen > 2 && cjkCount * 1.0 / qlen > 0.5) {
                dynamicThreshold -= 0.05;
            }
            if (dynamicThreshold < 0.6) dynamicThreshold = 0.6;
        }
        return dynamicThreshold;
    }

    /**
     * 简单汉字转拼音首字母（无外部依赖，遇到非汉字原样保留）
     * 如需完整拼音可集成 TinyPinyin 等库
     */
    private static String toPinyin(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 0x4e00 && c <= 0x9fa5) {
                sb.append(getPinyinInitial(c));
            } else if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase();
    }

    /**
     * 汉字转拼音首字母（仅支持常用汉字，简化版）
     */
    private static char getPinyinInitial(char c) {
        // 按区间粗略映射，适合模糊检索
        final String py = "abcdefghjklmnopqrstuwwxyz";
        final int[] pyValue = {
            45217,45253,45761,46318,46826,47010,47297,47614,48119,49062,
            49324,49896,50371,50614,50622,50906,51387,51446,52218,52698,
            52980,53689,54481,55290
        };
        try {
            byte[] bytes = (String.valueOf(c)).getBytes("GB2312");
            if (bytes.length < 2) return c;
            int code = ((bytes[0] & 0xFF) << 8) + (bytes[1] & 0xFF);
            for (int i = 0; i < pyValue.length - 1; i++) {
                if (code >= pyValue[i] && code < pyValue[i + 1]) {
                    return py.charAt(i);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return c;
    }

    /**
     * 文本归一化与预处理
     * 处理步骤包括：
     * 1. 全角转半角（中日文标点和字符）
     * 2. 去除特殊符号（保留字母、数字、CJK字符）
     * 3. 大小写统一（转为小写）
     * 4. Unicode NFC 归一化（组合字符标准化）
     * 5. 空白处理（压缩多余空白）
     * 
     * @param input 输入文本
     * @return 归一化后的文本
     */
    private static String normalizeText(String input) {
        if (input == null) return "";
        
        // 估计结果字符串长度，提高StringBuilder性能
        StringBuilder sb = new StringBuilder(input.length());
        
        // 1. 全角转半角
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            
            // 全角空格转半角空格
            if (c == 12288) {
                sb.append(' ');
            }
            // 其他全角字符转半角
            else if (c >= 65281 && c <= 65374) {
                sb.append((char)(c - 65248));
            }
            // 保持其他字符不变
            else {
                sb.append(c);
            }
        }
        
        String s = sb.toString();
        
        // 2. 去除特殊符号，仅保留常用字符和 CJK
        // 使用更精确的正则表达式，保留搜索关键信息
        s = s.replaceAll("[\\p{Punct}\\p{S}]", "");
        
        // 3. 大小写统一转为小写
        s = s.toLowerCase();
        
        // 4. Unicode NFC 归一化（将组合字符规范化）
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC);
        
        // 5. 去除多余空白：去除首尾空白和压缩中间连续空白为单个空格
        s = s.trim().replaceAll("\\s+", " ");
        
        return s;
    }

    /**
     * CJK智能分词方法 - 高性能实现
     * 该方法针对中日韩文本和英文文本采用不同策略：
     * - 中文、日文、韩文等CJK字符逐字分割
     * - 英文和数字保持连续形式
     * - 空白字符作为分隔符
     * 
     * 实现采用直接字符遍历而非正则表达式，显著提高性能
     * 
     * @param s 输入字符串
     * @return 分词结果数组
     */
    private static String[] cjkSmartSplit(String s) {
        // 检查空字符串
        if (s == null || s.isEmpty()) {
            return new String[0];
        }
        
        // 使用StringBuilder缓存当前分词
        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        boolean lastIsCJK = false;
        
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean isCJK = isCJKCharacter(c);
            boolean isSpace = Character.isWhitespace(c);
            
            if (isSpace) {
                // 空格处理：添加当前缓冲分词并重置
                if (currentToken.length() > 0) {
                    tokens.add(currentToken.toString());
                    currentToken.setLength(0);
                }
                continue;
            }
            
            if (currentToken.length() == 0) {
                // 当前为空，直接添加字符
                currentToken.append(c);
            } else if (isCJK != lastIsCJK) {
                // CJK与非CJK字符交替时，分割
                tokens.add(currentToken.toString());
                currentToken.setLength(0);
                currentToken.append(c);
            } else if (isCJK) {
                // 如果是连续CJK字符，也逐字分割
                tokens.add(currentToken.toString());
                currentToken.setLength(0);
                currentToken.append(c);
            } else {
                // 连续的非CJK字符，直接添加
                currentToken.append(c);
            }
            
            lastIsCJK = isCJK;
        }
        
        // 添加最后一个分词
        if (currentToken.length() > 0) {
            tokens.add(currentToken.toString());
        }
        
        return tokens.toArray(new String[0]);
    }
    
    /**
     * 检查字符是否是CJK字符（中日韩文字符）
     * 高效实现，使用Unicode区间直接判断而非正则表达式
     * 
     * @param c 要检查的字符
     * @return 是否为CJK字符
     */
    private static boolean isCJKCharacter(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        
        // 中日韩文字符范围
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
               block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
               block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
               block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C ||
               block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D ||
               block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
               block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT ||
               block == Character.UnicodeBlock.HIRAGANA ||
               block == Character.UnicodeBlock.KATAKANA ||
               block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
               block == Character.UnicodeBlock.HANGUL_JAMO ||
               block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO;
    }

    /**
     * 短查询模糊匹配算法的简化实现
     * 采用清晰的决策树结构，按照复杂度递增的顺序进行检查
     *
     * @param text 目标文本
     * @param query 短查询词
     * @param threshold 相似度阈值
     * @return 是否匹配
     */
    private static boolean shortQueryFuzzyMatch(String text, String query, double threshold) {
        // 完全匹配检查（使用高效算法）
        if (fastContains(text, query)) {
            return true;
        }
        
        // 特殊情况处理
        switch (query.length()) {
            case 0: // 空查询处理
                return false;
                
            case 1: // 单字符查询
                return text.indexOf(query.charAt(0)) >= 0;
                
            case 2: // 双字符查询
                char c1 = query.charAt(0);
                char c2 = query.charAt(1);
                
                // 如果两个字符都不存在，直接返回失败
                if (text.indexOf(c1) < 0 && text.indexOf(c2) < 0) {
                    return false;
                }
                
                // 如果两个字符距离较近，认为匹配
                int pos1 = text.indexOf(c1);
                int pos2 = text.indexOf(c2);
                if (pos1 >= 0 && pos2 >= 0 && Math.abs(pos1 - pos2) <= 2) {
                    return true;
                }
                break;
        }
        
        // 根据查询长度使用不同策略
        if (query.length() <= 3) {
            // 短查询（<=3字符）：直接使用编辑距离
            // 字符少，编辑距离算法更高效
            return optimizedEditDistanceMatch(text, query, threshold);
        }
        
        // 检测是否包含CJK字符，如果是CJK文本使用分词方式
        boolean isCjk = containsHighCJKRatio(query);
        
        if (isCjk) {
            // 对CJK文本使用分词方式计算匹配率
            String[] textTokens = cjkSmartSplit(text);
            String[] queryTokens = cjkSmartSplit(query);
            
            // 计算匹配率
            int matches = 0;
            int validTokens = 0;
            
            for (String qt : queryTokens) {
                if (qt.isEmpty() || qt.trim().isEmpty()) continue;
                
                validTokens++;
                for (String tt : textTokens) {
                    if (tt.contains(qt)) {
                        matches++;
                        break;
                    }
                }
            }
            
            // 计算匹配率并决定是否需要进一步计算
            double matchRatio = validTokens > 0 ? (double)matches / validTokens : 0.0;
            if (matchRatio >= 0.5) {
                // 匹配率足够高时，再进行编辑距离判断
                return optimizedEditDistanceMatch(text, query, threshold);
            }
            
            // 分词匹配率过低
            return false;
        } else {
            // 非CJK文本，先检查是否有共同子串
            if (!hasCommonSubstring(text, query, 3)) {
                return false;
            }
            
            // 有共同子串后再进行编辑距离计算
            return optimizedEditDistanceMatch(text, query, Math.max(threshold - 0.05, 0.6));
        }
    }

    /**
     * 长查询词模糊匹配算法
     * 采用多级过滤策略，从简到精，最大化效率：
     * 1. 先检查简单情况（完全匹配、包含关系）
     * 2. 使用公共子串检测快速排除明显不相关的文本
     * 3. 基于查询词长度和规律自适应调整阈值
     * 4. 最终使用精确的相似度算法做判定
     * 
     * 该方法对长查询词进行了特别优化，即使面对大文本也能高效匹配
     * 
     * @param text 目标文本
     * @param query 长查询词
     * @param threshold 相似度阈值
     * @return 是否匹配
     */
    private static boolean longQueryFuzzyMatch(String text, String query, double threshold) {
        // 1. 快速路径：完全包含查询词或反过来查询词包含文本
        if (fastContains(text, query)) {
            return true;
        }
        
        // 对于几乎相等长度的文本，检查查询词是否包含目标文本
        if (text.length() <= query.length() * 1.2 && fastContains(query, text)) {
            return true;
        }
        
        // 2. 公共子串快速筛选
        // 长文本必须至少有一定长度的公共子串，不然很可能不相关
        int minCommonLength = 3;  // 基础最小公共子串长度
        
        // 对于非常长的查询，增加公共子串长度要求
        if (query.length() > 30) {
            minCommonLength = 4;  // 更长的查询词要求更长的公共子串
        }
        
        if (!hasCommonSubstring(text, query, minCommonLength)) {
            return false;
        }
        
        // 3. 动态阈值调整，根据不同情况自适应
        double adjustedThreshold = threshold;
        
        // 3.1 超长查询阈值调整（长查询难以完全匹配）
        if (query.length() > 20) {
            // 超长查询适当降低阈值要求
            adjustedThreshold -= 0.05 * Math.min(0.2, (query.length() - 20) / 100.0);
        }
        
        // 3.2 CJK字符比例阈值调整（中日韩文字更难准确匹配）
        if (containsHighCJKRatio(query) && threshold > 0.65) {
            adjustedThreshold = Math.max(0.6, adjustedThreshold - 0.05);
        }
        
        // 3.3 确保阈值不会过低
        adjustedThreshold = Math.max(0.55, adjustedThreshold);
        
        // 4. 使用适合长文本的相似度算法进行精确评估
        double similarity = calculateImprovedSimilarity(text, query);
        
        // 5. 返回最终判断结果，基于调整后的阈值
        return similarity >= adjustedThreshold;
    }

    /**
     * 计算优化的相似度分数
     * 直接根据文本特性选择最适合的相似度算法，提高计算效率：
     * - 短文本使用 Jaro-Winkler 算法（适合小错误容错）
     * - CJK文本优先使用基于分词的相似度
     * - 长文本使用余弦相似度算法
     * 
     * @param text 目标文本
     * @param query 查询词
     * @return 相似度分数，范围为0-1
     */
    private static double calculateImprovedSimilarity(String text, String query) {
        // 快速路径：相等文本
        if (text.equals(query)) {
            return 1.0;
        }
        
        // 预处理输入文本，转为小写以忽略大小写差异
        String normalizedText = text.toLowerCase();
        String normalizedQuery = query.toLowerCase();
        
        // 根据文本特性选择最合适的算法
        boolean isCjkHeavy = containsHighCJKRatio(query);
        int queryLen = query.length();
        int textLen = text.length();
        
        // 1. 短文本特殊处理（长度小于5的词汇精细匹配）
        if (queryLen <= 5 && textLen <= 100) {
            // 短文本使用 Jaro-Winkler，对拼写错误宽容性好
            return calculateFastJaroWinkler(normalizedText, normalizedQuery);
        }
        
        // 2. CJK文本优先使用基于分词的相似度
        if (isCjkHeavy) {
            // 对于CJK字符占比高的文本，使用基于分词的相似度
            return calculateTokenBasedSimilarity(normalizedText, normalizedQuery);
        }
        
        // 3. 长文本使用余弦相似度算法（更高效）
        if (textLen > 100 || queryLen > 20) {
            return calculateCosineSimilarity(normalizedText, normalizedQuery);
        }
        
        // 4. 其他情况使用Jaro-Winkler和分词算法的加权平均
        // 这是为了保持向后兼容性和处理边缘情况
        double jaroWinklerSimilarity = calculateFastJaroWinkler(normalizedText, normalizedQuery);
        double tokenSimilarity = calculateTokenBasedSimilarity(normalizedText, normalizedQuery);
        
        // 默认权重
        return jaroWinklerSimilarity * 0.4 + tokenSimilarity * 0.6;
    }
    
    /**
     * 检查字符串中CJK字符的比例
     * 
     * @param s 输入字符串
     * @return CJK字符比例是否较高
     */
    private static boolean containsHighCJKRatio(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        
        int cjkCount = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // 检查是否为中文、日文或韩文字符
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                Character.UnicodeBlock.of(c) == Character.UnicodeBlock.HIRAGANA ||
                Character.UnicodeBlock.of(c) == Character.UnicodeBlock.KATAKANA ||
                Character.UnicodeBlock.of(c) == Character.UnicodeBlock.HANGUL_SYLLABLES) {
                cjkCount++;
            }
        }
        
        // 如果CJK字符占比超过30%，则认为是CJK字符比例较高的文本
        return (double) cjkCount / s.length() >= 0.3;
    }

    /**
     * 快速Jaro-Winkler相似度算法实现
     * Jaro-Winkler是一种字符串相似度度量，特别适合短文本和人名比较
     * 本实现针对性能进行了优化，减少了不必要的计算
     * 
     * 算法原理：
     * 1. 计算在一定距离内匹配的字符数
     * 2. 计算字符转置次数（匹配但顺序不同）
     * 3. 基于匹配数和转置计算Jaro距离
     * 4. 考虑共同前缀给予额外加权（Winkler修正）
     * 
     * @param s1 第一个字符串
     * @param s2 第二个字符串
     * @return 0.0到1.0之间的相似度，1.0表示完全相同
     */
    private static double calculateFastJaroWinkler(String s1, String s2) {
        // 快速路径：完全相同
        if (s1.equals(s2)) return 1.0;
        
        int len1 = s1.length(), len2 = s2.length();
        // 快速路径：空字符串处理
        if (len1 == 0 || len2 == 0) return 0.0;
        
        // 长度差距太大，直接判定为不相似
        if (Math.abs(len1 - len2) > Math.min(len1, len2) / 2) {
            return 0.0;
        }
        
        // 计算匹配窗口大小 - Jaro算法核心参数
        // 定义在多远的距离内认为字符可能匹配
        int matchDistance = Math.max(len1, len2) / 2 - 1;
        if (matchDistance < 0) matchDistance = 0;
        
        // 记录哪些字符已匹配
        boolean[] s1Matches = new boolean[len1];
        boolean[] s2Matches = new boolean[len2];
        int matches = 0;
        
        // 第一步：找出所有在窗口内匹配的字符
        for (int i = 0; i < len1; i++) {
            // 优化：根据匹配窗口计算查找范围
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, len2);
            
            for (int j = start; j < end; j++) {
                // 如果s2中的字符未被匹配，且与s1当前字符相同
                if (!s2Matches[j] && s1.charAt(i) == s2.charAt(j)) {
                    s1Matches[i] = true;
                    s2Matches[j] = true;
                    matches++;
                    break;
                }
            }
        }
        
        // 如果没有匹配字符，直接返回0
        if (matches == 0) return 0.0;
        
        // 第二步：计算转置数（匹配字符的顺序差异）
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (s1Matches[i]) {
                // 找到s2中下一个匹配的字符
                while (!s2Matches[k]) k++;
                // 如果匹配的字符不同，增加转置计数
                if (s1.charAt(i) != s2.charAt(k)) transpositions++;
                k++;
            }
        }
        
        // 第三步：计算Jaro距离
        // Jaro距离是三个比率的平均值：匹配率(s1)、匹配率(s2)和顺序匹配率
        double jaro = ((double) matches / len1 + 
                    (double) matches / len2 + 
                    (double) (matches - transpositions / 2) / matches) / 3.0;
        
        // 第四步：应用Winkler修正 - 对具有共同前缀的字符串给予奖励
        int prefix = 0;
        int maxPrefix = Math.min(4, Math.min(len1, len2));  // 最多考虑4个前缀字符
        for (int i = 0; i < maxPrefix; i++) {
            if (s1.charAt(i) == s2.charAt(i)) prefix++;
            else break;
        }
        
        // Winkler系数为0.1，表示每个共同前缀字符增加的相似度权重
        return jaro + prefix * 0.1 * (1 - jaro);
    }
    
    /**
     * 计算基于分词的相似度
     * 该方法将文本分解为词元，然后计算共同词元的比例
     * 特别适合文本中词序位置发生变化的情况
     * 
     * @param s1 第一个字符串
     * @param s2 第二个字符串
     * @return 0.0到1.0之间的相似度值
     */
    private static double calculateTokenBasedSimilarity(String s1, String s2) {
        // 快速路径：相同字符串
        if (s1.equals(s2)) return 1.0;
        
        // 使用CJK智能分词将文本分解为有意义的单元
        String[] tokens1 = cjkSmartSplit(s1);
        String[] tokens2 = cjkSmartSplit(s2);
        
        // 快速路径：没有分词结果
        if (tokens1.length == 0 || tokens2.length == 0) {
            return 0.0;
        }
        
        // 将分词结果存入Set以去重并加快查找
        Set<String> set1 = new HashSet<>();
        Set<String> set2 = new HashSet<>();
        
        // 添加不为空的分词
        for (String token : tokens1) {
            if (!token.isEmpty()) set1.add(token);
        }
        
        for (String token : tokens2) {
            if (!token.isEmpty()) set2.add(token);
        }
        
        // 再次检查集合是否为空
        if (set1.isEmpty() || set2.isEmpty()) {
            return 0.0;
        }
        
        // 计算共同分词的交集
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        
        // 计算Dice系数 = 2 * |X ∩ Y| / (|X| + |Y|)
        // 这个公式衡量两个集合的重叠程度，范围0-1
        double dice = 2.0 * intersection.size() / (set1.size() + set2.size());
        
        // 返回分词相似度
        return dice;
    }

    /**
     * 余弦相似度算法（特别适合长文本和CJK文本比较）
     * 基于N-gram分词，将字符串转换为向量空间模型，然后计算向量夹角余弦值
     * 相比编辑距离算法，余弦相似度在长文本上计算效率更高
     * 
     * @param s1 第一个字符串
     * @param s2 第二个字符串
     * @return 0.0到1.0之间的相似度，1.0表示完全匹配
     */
    private static double calculateCosineSimilarity(String s1, String s2) {
        // 快速路径：完全相同
        if (s1.equals(s2)) return 1.0;
        
        // 对于非常短的文本，使用2-gram；较长文本使用3-gram提高准确性
        int ngramSize = (s1.length() + s2.length() > 100) ? 3 : 2;
        
        // 为两个字符串创建n-gram频率向量
        Map<String, Integer> profile1 = getNGramProfile(s1, ngramSize);
        Map<String, Integer> profile2 = getNGramProfile(s2, ngramSize);
        
        // 计算两个向量的交集（共有的n-gram）
        Set<String> intersection = new HashSet<>(profile1.keySet());
        intersection.retainAll(profile2.keySet());
        
        // 如果没有共同的n-gram，相似度为0
        if (intersection.isEmpty()) return 0.0;
        
        // 计算向量点积和范数
        double dotProduct = 0, norm1 = 0, norm2 = 0;
        
        // 计算点积：共有n-gram的频率乘积之和
        for (String gram : intersection) {
            dotProduct += profile1.get(gram) * profile2.get(gram);
        }
        
        // 计算向量1的范数
        for (int count : profile1.values()) {
            norm1 += count * count;
        }
        
        // 计算向量2的范数
        for (int count : profile2.values()) {
            norm2 += count * count;
        }
        
        // 计算余弦值：点积除以范数乘积的平方根
        // 余弦值范围为0-1，值越大表示越相似
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    /**
     * 获取字符串的N-gram特征向量
     * N-gram是将文本分解为连续N个字符的子串，用于文本相似度比较
     * 
     * @param s 输入字符串
     * @param n N-gram大小，通常为2或3
     * @return N-gram到频率的映射
     */
    private static Map<String, Integer> getNGramProfile(String s, int n) {
        // 对于过短的字符串，直接返回空映射
        if (s.length() < n) {
            return new HashMap<>();
        }
        
        // 使用HashMap存储N-gram及其出现频率
        Map<String, Integer> profile = new HashMap<>((int)(s.length() * 1.5));
        
        // 滑动窗口提取所有N-gram及其频率
        for (int i = 0; i <= s.length() - n; i++) {
            String gram = s.substring(i, i + n);
            profile.put(gram, profile.getOrDefault(gram, 0) + 1);
        }
        
        return profile;
    }
    
    /**
     * Boyer-Moore字符串匹配算法实现
     * 该算法比原生的Java indexOf 和 contains 方法更高效，
     * 尤其适用于大文本中查找较长的模式串
     * 
     * @param text 被搜索的文本
     * @param pattern 要查找的模式
     * @return 匹配开始的位置索引，或-1表示没有找到
     */
    private static int boyerMooreSearch(String text, String pattern) {
        // 快速路径：模式串为空或模式串长度超过文本长度
        if (pattern == null || pattern.isEmpty()) {
            return 0;
        }
        if (text == null || text.isEmpty() || pattern.length() > text.length()) {
            return -1;
        }
        
        // 对于短模式串使用原生方法，避免额外开销
        if (pattern.length() <= 3) {
            return text.indexOf(pattern);
        }
        
        int m = pattern.length();
        int n = text.length();
        
        // 为坏字符规则创建跳过表
        Map<Character, Integer> badCharSkip = new HashMap<>();
        for (int i = 0; i < m - 1; i++) {
            badCharSkip.put(pattern.charAt(i), m - i - 1);
        }
        
        // 搜索过程
        int i = m - 1;  // 文本中比较的当前位置
        while (i < n) {
            int j = m - 1;  // 模式串中比较的当前位置
            int k = i;     // 文本中比较的当前位置
            
            // 从右向左比较字符
            while (j >= 0 && text.charAt(k) == pattern.charAt(j)) {
                k--;
                j--;
            }
            
            // 如果比较完整个模式字符串，则找到了匹配
            if (j < 0) {
                return k + 1;  // 返回匹配开始的位置
            }
            
            // 使用坏字符规则优化跳过距离
            Integer badCharSkipValue = badCharSkip.get(text.charAt(k));
            int skip = (badCharSkipValue != null) ? badCharSkipValue : m;
            
            i += Math.max(1, skip);
        }
        
        return -1;  // 没有找到匹配
    }
    
    /**
     * 使用 Boyer-Moore 算法来执行字符串包含检查的高效实现
     * 对于长文本和长查询模式，比原生 contains 方法更有效率
     * 
     * @param text 被搜索的文本
     * @param pattern 要查找的模式
     * @return 文本是否包含模式
     */
    private static boolean fastContains(String text, String pattern) {
        // 对于短文本或短模式，使用Java原生实现，避免额外开销
        if (text.length() < 100 || pattern.length() < 4) {
            return text.contains(pattern);
        }
        
        // 对于长文本和长模式，使用高效的Boyer-Moore算法
        return boyerMooreSearch(text, pattern) != -1;
    }

    /**
     * 检查两个字符串是否存在公共子串
     * 该方法采用滑动窗口技术，用于快速过滤明显不相关的文本
     * 
     * @param s1 字符串1
     * @param s2 字符串2
     * @param minLength 最小公共子串长度
     * @return 是否存在指定长度及以上的公共子串
     */
    /**
     * KMP算法的模式串前缀表构建
     * 用于快速定位字符串匹配失败时的回退位置
     * 
     * @param pattern 要搜索的模式串
     * @return 前缀表数组
     */
    private static int[] computeKMPTable(String pattern) {
        int m = pattern.length();
        int[] table = new int[m];
        
        // 初始化：第一个字符的前缀长度为0
        table[0] = 0;
        
        // 构建其他位置的前缀表
        for (int i = 1, j = 0; i < m; i++) {
            // 当前缀不匹配时，回退 j 到前一个匹配位置
            while (j > 0 && pattern.charAt(i) != pattern.charAt(j)) {
                j = table[j - 1];
            }
            
            // 如果字符匹配，增加前缀长度
            if (pattern.charAt(i) == pattern.charAt(j)) {
                j++;
            }
            
            // 记录当前位置的前缀长度
            table[i] = j;
        }
        
        return table;
    }
    
    /**
     * 使用KMP算法在文本中查找模式串
     * 相比暴力算法更高效，尤其在大文本中
     * 
     * @param text 被搜索的文本
     * @param pattern 要查找的模式
     * @return 匹配的索引位置，如果未找到返回-1
     */
    private static int kmpSearch(String text, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return 0;
        }
        if (text == null || text.isEmpty()) {
            return -1;
        }
        
        // 对于短模式串，直接使用indexOf更高效
        if (pattern.length() <= 2) {
            return text.indexOf(pattern);
        }
        
        int n = text.length();
        int m = pattern.length();
        
        // 如果模式比文本长，不可能匹配
        if (m > n) {
            return -1;
        }
        
        // 计算KMP算法的前缀表
        int[] table = computeKMPTable(pattern);
        
        // KMP搜索算法
        for (int i = 0, j = 0; i < n; i++) {
            // 当字符不匹配时，根据前缀表回退 j
            while (j > 0 && text.charAt(i) != pattern.charAt(j)) {
                j = table[j - 1];
            }
            
            // 如果字符匹配，前移模式串匹配位置
            if (text.charAt(i) == pattern.charAt(j)) {
                j++;
            }
            
            // 如果完全匹配了模式串，返回开始位置
            if (j == m) {
                return i - m + 1;
            }
        }
        
        // 没有匹配
        return -1;
    }
    
    private static boolean hasCommonSubstring(String s1, String s2, int minLength) {
        // 参数有效性检查
        if (s1 == null || s2 == null || minLength <= 0) {
            return false;
        }
        
        // 快速路径：直接包含关系
        if (s1.contains(s2) || s2.contains(s1)) {
            return true;
        }

        // 确保 s1 是较短的字符串，以减少循环次数和提高效率
        if (s1.length() > s2.length()) {
            String temp = s1;
            s1 = s2;
            s2 = temp;
        }

        // 如果较短的字符串长度小于最小公共子串长度，直接返回失败
        if (s1.length() < minLength) {
            return false;
        }
        
        // 优化：对于很小的minLength，使用Set方式可能更快
        if (minLength <= 3 && s2.length() > 200) {
            Set<String> s2Substrings = new HashSet<>();
            for (int i = 0; i <= s2.length() - minLength; i++) {
                s2Substrings.add(s2.substring(i, i + minLength));
            }
            
            for (int i = 0; i <= s1.length() - minLength; i++) {
                String window = s1.substring(i, i + minLength);
                if (s2Substrings.contains(window)) {
                    return true;
                }
            }
            return false;
        }
        
        // 使用KMP算法检查较短字符串的子串是否存在于较长字符串中
        for (int i = 0; i <= s1.length() - minLength; i++) {
            String window = s1.substring(i, i + minLength);
            if (kmpSearch(s2, window) != -1) {
                return true;
            }
        }

        return false;
    }

    /**
     * 优化的编辑距离匹配方法
     * 基于阈值计算允许的最大错误数，并使用带限制的Levenshtein距离计算
     * 
     * @param text 目标文本
     * @param query 查询词
     * @param threshold 相似度阈值（0-1之间）
     * @return 是否匹配
     */
    private static boolean optimizedEditDistanceMatch(String text, String query, double threshold) {
        // 特殊情况处理
        if (text.equals(query)) {
            return true;
        }
        
        if (text.contains(query) || query.contains(text)) {
            return true;
        }
        
        // 计算基于阈值的最大允许错误数
        // 例如，对于长度10的查询词和0.8的阈值，允许最多2个错误
        int maxErrors = (int) Math.ceil((1.0 - threshold) * query.length());
        
        // 调用带提前终止的Levenshtein距离计算
        return levenshteinDistanceWithinLimit(text, query, maxErrors);
    }

    /**
     * 带提前终止的Levenshtein编辑距离计算
     * Levenshtein距离是两个字符串之间的最小编辑操作数（插入、删除、替换）
     * 此优化版本设置了最大错误阈值，如果发现编辑距离肯定超出阈值，则提前终止计算
     * 
     * @param s1 第一个字符串
     * @param s2 第二个字符串
     * @param maxErrors 允许的最大编辑距离，超过此值判定为不匹配
     * @return 如果编辑距离在maxErrors范围内返回true，否则返回false
     */
    private static boolean levenshteinDistanceWithinLimit(String s1, String s2, int maxErrors) {
        // 快速路径：如果字符串长度差本身就超过最大错误数，必然不匹配
        if (Math.abs(s1.length() - s2.length()) > maxErrors) {
            return false;
        }
        
        // 快速路径：完全相同的字符串
        if (s1.equals(s2)) {
            return true;
        }
        
        // 优化：确保s1是较短的字符串，减少内层循环次数
        if (s1.length() > s2.length()) {
            String temp = s1;
            s1 = s2;
            s2 = temp;
        }
        
        // 使用两个数组交替，减少内存占用
        int[] prev = new int[s2.length() + 1];
        int[] curr = new int[s2.length() + 1];
        
        // 初始化第一行（空字符串到s2的转换需要j次插入）
        for (int j = 0; j <= s2.length(); j++) {
            prev[j] = j;
        }
        
        // 动态规划填表过程
        for (int i = 1; i <= s1.length(); i++) {
            curr[0] = i;  // 从空字符串到s1[0...i-1]需要i次操作
            int minInRow = i;  // 跟踪当前行的最小值
            
            char c1 = s1.charAt(i - 1);  // 当前比较的s1字符
            
            for (int j = 1; j <= s2.length(); j++) {
                // 判断字符是否相等，相等时不需要替换操作
                int cost = (c1 == s2.charAt(j - 1)) ? 0 : 1;
                
                // 动态规划核心逻辑：取三种操作的最小值
                // curr[j-1] + 1: 插入操作
                // prev[j] + 1: 删除操作
                // prev[j-1] + cost: 替换或不操作
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                
                // 更新当前行的最小编辑距离
                minInRow = Math.min(minInRow, curr[j]);
            }
            
            // 提前终止条件：如果当前行的最小值已超过阈值，后续行只会更大，可提前返回
            if (minInRow > maxErrors) {
                return false;
            }
            
            // 交换两个数组引用，避免重新分配内存
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }
        
        // 最终编辑距离是最后一行的最后一个元素
        return prev[s2.length()] <= maxErrors;
    }

    @Nullable
    public static String extractTitle(String title) {
        if (null == title) {
            return null;
        }
        title = PATTERN_TITLE_PREFIX.matcher(title).replaceFirst("");
        title = PATTERN_TITLE_SUFFIX.matcher(title).replaceFirst("");
        // Sometimes title is combined by romaji and english translation.
        // Only need romaji.
        // TODO But not sure every '|' means that
        int index = title.indexOf('|');
        if (index >= 0) {
            title = title.substring(0, index);
        }
        if (title.isEmpty()) {
            return null;
        } else {
            return title;
        }
    }

    public static String handleThumbUrlResolution(String url) {
        if (null == url) {
            return null;
        }

        String resolution;
        switch (Settings.getThumbResolution()) {
            default:
            case 0: // Auto
                return url;
            case 1: // 250
                resolution = "250";
                break;
            case 2: // 300
                resolution = "300";
                break;
        }

        int index1 = url.lastIndexOf('_');
        int index2 = url.lastIndexOf('.');
        if (index1 >= 0 && index2 >= 0 && index1 < index2) {
            return url.substring(0, index1 + 1) + resolution + url.substring(index2);
        } else {
            return url;
        }
    }
}
