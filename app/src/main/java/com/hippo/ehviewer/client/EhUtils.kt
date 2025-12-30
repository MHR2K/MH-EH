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
package com.hippo.ehviewer.client

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import java.util.regex.Pattern

object EhUtils {
    @JvmField
    val NONE: Int = -1 // Use it for homepage
    const val UNKNOWN: Int = 0x400

    @JvmField
    val ALL_CATEGORY: Int = UNKNOWN - 1

    //DOUJINSHI|MANGA|ARTIST_CG|GAME_CG|WESTERN|NON_H|IMAGE_SET|COSPLAY|ASIAN_PORN|MISC;
    const val BG_COLOR_DOUJINSHI: Int = -0xbbcca
    const val BG_COLOR_MANGA: Int = -0x6800
    const val BG_COLOR_ARTIST_CG: Int = -0x43fd3
    const val BG_COLOR_GAME_CG: Int = -0xb350b0
    const val BG_COLOR_WESTERN: Int = -0x743cb6
    const val BG_COLOR_NON_H: Int = -0xde690d
    const val BG_COLOR_IMAGE_SET: Int = -0xc0ae4b
    const val BG_COLOR_COSPLAY: Int = -0x63d850
    const val BG_COLOR_ASIAN_PORN: Int = -0x6a8a33
    const val BG_COLOR_MISC: Int = -0xf9d6e
    val BG_COLOR_UNKNOWN: Int = Color.BLACK

    // Remove [XXX], (XXX), {XXX}, ~XXX~ stuff
    val PATTERN_TITLE_PREFIX: Pattern = Pattern.compile(
        "^(?:(?:\\([^\\)]*\\))|(?:\\[[^\\]]*\\])|(?:\\{[^\\}]*\\})|(?:~[^~]*~)|\\s+)*"
    )

    // Remove [XXX], (XXX), {XXX}, ~XXX~ stuff and something like ch. 1-23
    val PATTERN_TITLE_SUFFIX: Pattern = Pattern.compile(
        "(?:\\s+ch.[\\s\\d-]+)?(?:(?:\\([^\\)]*\\))|(?:\\[[^\\]]*\\])|(?:\\{[^\\}]*\\})|(?:~[^~]*~)|\\s+)*$",
        Pattern.CASE_INSENSITIVE
    )

    private val CATEGORY_VALUES = intArrayOf(
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
        UNKNOWN
    )

    private val CATEGORY_STRINGS = arrayOf<Array<String>?>(
        arrayOf<String>("misc"),
        arrayOf<String>("doujinshi"),
        arrayOf<String>("manga"),
        arrayOf<String>("artistcg", "Artist CG Sets", "Artist CG"),
        arrayOf<String>("gamecg", "Game CG Sets", "Game CG"),
        arrayOf<String>("imageset", "Image Sets", "Image Set"),
        arrayOf<String>("cosplay"),
        arrayOf<String>("asianporn", "Asian Porn"),
        arrayOf<String>("non-h"),
        arrayOf<String>("western"),
        arrayOf<String>("unknown")
    )

    @JvmStatic
    fun getCategory(type: String?): Int {
        var i: Int
        i = 0
        while (i < CATEGORY_STRINGS.size - 1) {
            for (str in CATEGORY_STRINGS[i]!!) if (str.equals(
                    type,
                    ignoreCase = true
                )
            ) return CATEGORY_VALUES[i]
            i++
        }

        return CATEGORY_VALUES[i]
    }

    @JvmStatic
    fun getCategory(type: Int): String? {
        var i: Int
        i = 0
        while (i < CATEGORY_VALUES.size - 1) {
            if (CATEGORY_VALUES[i] == type) break
            i++
        }
        return CATEGORY_STRINGS[i]!![0]
    }

    @JvmStatic
    fun getCategoryColor(category: Int): Int {
        when (category) {
            EhConfig.DOUJINSHI -> return BG_COLOR_DOUJINSHI
            EhConfig.MANGA -> return BG_COLOR_MANGA
            EhConfig.ARTIST_CG -> return BG_COLOR_ARTIST_CG
            EhConfig.GAME_CG -> return BG_COLOR_GAME_CG
            EhConfig.WESTERN -> return BG_COLOR_WESTERN
            EhConfig.NON_H -> return BG_COLOR_NON_H
            EhConfig.IMAGE_SET -> return BG_COLOR_IMAGE_SET
            EhConfig.COSPLAY -> return BG_COLOR_COSPLAY
            EhConfig.ASIAN_PORN -> return BG_COLOR_ASIAN_PORN
            EhConfig.MISC -> return BG_COLOR_MISC
            else -> return BG_COLOR_UNKNOWN
        }
    }

    @JvmStatic
    fun signOut(context: Context) {
        EhApplication.getEhCookieStore(context).signOut()
        Settings.putAvatar(null)
        Settings.putDisplayName(null)
        Settings.putNeedSignIn(true)
    }

    @JvmStatic
    fun needSignedIn(context: Context): Boolean {
        return Settings.getNeedSignIn() && !EhApplication.getEhCookieStore(context).hasSignedIn()
    }

    @JvmStatic
    fun getSuitableTitle(gi: GalleryInfo): String? {
        if (Settings.getShowJpnTitle()) {
            return if (TextUtils.isEmpty(gi.titleJpn)) gi.title else gi.titleJpn
        } else {
            return if (TextUtils.isEmpty(gi.title)) gi.titleJpn else gi.title
        }
    }

    @JvmStatic
    fun judgeSuitableTitle(gi: GalleryInfo, key: String): Boolean {
        return judgeSuitableTitle(gi, key, true, true)
    }

    @JvmStatic
    fun judgeSuitableTitle(
        gi: GalleryInfo,
        key: String,
        fuzzySearch: Boolean,
        ignoreCase: Boolean
    ): Boolean {
        var titleB = gi.titleJpn + "" + gi.title
        var searchKey = key

        if (ignoreCase) {
            titleB = titleB.lowercase()
            searchKey = searchKey.lowercase()
        }

        return if (fuzzySearch) {
            fuzzyContains(titleB, searchKey, 0.7)
        } else {
            titleB.contains(searchKey)
        }
    }

    /**
     * 模糊匹配算法，使用Jaro-Winkler相似度
     * @param text 文本内容
     * @param query 查询关键词
     * @param threshold 相似度阈值 (0-1)
     * @return 是否匹配
     */
    @JvmStatic
    fun fuzzyContains(text: String?, query: String?, threshold: Double): Boolean {
        if (text == null || query == null || query.isEmpty()) {
            return false
        }

        // 如果查询词很短，使用精确匹配
        if (query.length <= 2) {
            return text.contains(query)
        }

        // 计算Jaro-Winkler相似度
        val similarity = calculateJaroWinklerSimilarity(text, query)
        return similarity >= threshold
    }

    /**
     * 计算Jaro-Winkler相似度
     */
    @JvmStatic
    fun calculateJaroWinklerSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) {
            return 1.0
        }

        val matches = getMatches(s1.toCharArray(), s2.toCharArray())
        val m = matches[0]

        if (m == 0) {
            return 0.0
        }

        val jaro = (m.toDouble() / s1.length + m.toDouble() / s2.length + 
                    (m - matches[1]).toDouble() / m) / 3.0

        // 计算Jaro-Winkler相似度
        var prefix = 0
        val maxPrefix = minOf(4, minOf(s1.length, s2.length))
        for (i in 0 until maxPrefix) {
            if (s1[i] == s2[i]) {
                prefix++
            } else {
                break
            }
        }

        return jaro + (prefix * 0.1 * (1 - jaro))
    }

    private fun getMatches(s1: CharArray, s2: CharArray): IntArray {
        val max: CharArray
        val min: CharArray
        if (s1.size > s2.size) {
            max = s1
            min = s2
        } else {
            max = s2
            min = s1
        }

        val range = maxOf(max.size / 2 - 1, 0)
        val matchIndexes = IntArray(min.size) { -1 }
        val matchFlags = BooleanArray(max.size)
        var matches = 0

        for (mi in min.indices) {
            val c1 = min[mi]
            val xStart = maxOf(mi - range, 0)
            val xEnd = minOf(mi + range + 1, max.size)
            for (xi in xStart until xEnd) {
                if (!matchFlags[xi] && c1 == max[xi]) {
                    matchIndexes[mi] = xi
                    matchFlags[xi] = true
                    matches++
                    break
                }
            }
        }

        val ms1 = CharArray(matches)
        val ms2 = CharArray(matches)
        var si = 0
        for (i in min.indices) {
            if (matchIndexes[i] != -1) {
                ms1[si] = min[i]
                si++
            }
        }
        si = 0
        for (i in max.indices) {
            if (matchFlags[i]) {
                ms2[si] = max[i]
                si++
            }
        }

        var transpositions = 0
        for (mi in ms1.indices) {
            if (ms1[mi] != ms2[mi]) {
                transpositions++
            }
        }

        var prefix = 0
        for (mi in min.indices) {
            if (s1[mi] == s2[mi]) {
                prefix++
            } else {
                break
            }
        }

        return intArrayOf(matches, transpositions / 2, prefix)
    }

    @JvmStatic
    fun extractTitle(title: String?): String? {
        var title = title
        if (null == title) {
            return null
        }
        title = PATTERN_TITLE_PREFIX.matcher(title).replaceFirst("")
        title = PATTERN_TITLE_SUFFIX.matcher(title).replaceFirst("")
        // Sometimes title is combined by romaji and english translation.
        // Only need romaji.
        // TODO But not sure every '|' means that
        val index = title.indexOf('|')
        if (index >= 0) {
            title = title.substring(0, index)
        }
        if (title.isEmpty()) {
            return null
        } else {
            return title
        }
    }

    @JvmStatic
    fun handleThumbUrlResolution(url: String?): String? {
        if (null == url) {
            return null
        }

        val resolution: String?
        when (Settings.getThumbResolution()) {
            0 -> return url
            1 -> resolution = "250"
            2 -> resolution = "300"
            else -> return url
        }

        val index1 = url.lastIndexOf('_')
        val index2 = url.lastIndexOf('.')
        if (index1 >= 0 && index2 >= 0 && index1 < index2) {
            return url.substring(0, index1 + 1) + resolution + url.substring(index2)
        } else {
            return url
        }
    }
}
