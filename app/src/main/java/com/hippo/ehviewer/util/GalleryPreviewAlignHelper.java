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

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;

/**
 * 新旧画廊版本缩略图比对与页面对齐辅助类
 * 采用 2D DCT 感知哈希 (pHash) + 页数差约束搜索区间 + 多探针锚点 + 连续锁链早停验证
 */
public final class GalleryPreviewAlignHelper {

    /** 判定两张缩略图完全相同（允许轻微压缩噪声/重采样微差）的汉明距离上限 */
    public static final int IDENTICAL_HAMMING_DISTANCE = 7;

    /** 判定两张缩略图相似/局部修改的最大汉明距离（超过则判定为显著差异/不同页） */
    public static final int MAX_HAMMING_DISTANCE = 16;

    /** 确认对齐所需的连续匹配页数 */
    public static final int REQUIRED_CONSECUTIVE_MATCHES = 3;

    /** 旧版本最多尝试作为锚点探针的前置页数（避免首页是广告/版权声明导致的误判） */
    public static final int MAX_PROBE_PAGES = 4;

    // 32 点 DCT 的预计算余弦表：COS_TABLE[u][x] = cos((2*x + 1) * u * PI / 64)
    private static final double[][] COS_TABLE = new double[8][32];
    static {
        for (int u = 0; u < 8; u++) {
            for (int x = 0; x < 32; x++) {
                COS_TABLE[u][x] = Math.cos(Math.PI * (2 * x + 1) * u / 64.0);
            }
        }
    }

    /**
     * 单页匹配状态
     */
    public enum PageMatchStatus {
        UNKNOWN,    // 尚未比对
        IDENTICAL,  // 完全相同 (汉明距离 <= 7)
        MODIFIED,   // 嵌字/修图/微调 (8 <= 汉明距离 <= 16)
        DIFFERENT   // 显著差异 / 插入页 (汉明距离 > 16)
    }

    /**
     * 全本比对统计结果
     */
    public static class FullCompareReport {
        public int totalPairs = 0;
        public int identicalCount = 0;
        public int modifiedCount = 0;
        public int differentCount = 0;
        public int firstDiffOldPos = -1;
        public int firstDiffNewPos = -1;

        public boolean hasDifference() {
            return differentCount > 0 || modifiedCount > 0;
        }
    }

    /**
     * 根据两张缩略图的 Hash 汉明距离评估匹配状态
     */
    @NonNull
    public static PageMatchStatus evaluateMatchStatus(long hash1, long hash2) {
        if (hash1 == 0L || hash2 == 0L) {
            return PageMatchStatus.UNKNOWN;
        }
        int dist = hammingDistance(hash1, hash2);
        if (dist <= IDENTICAL_HAMMING_DISTANCE) {
            return PageMatchStatus.IDENTICAL;
        } else if (dist <= MAX_HAMMING_DISTANCE) {
            return PageMatchStatus.MODIFIED;
        } else {
            return PageMatchStatus.DIFFERENT;
        }
    }

    private GalleryPreviewAlignHelper() {
    }

    /**
     * 计算 Bitmap 的 64 位感知哈希（DCT pHash）
     * 1. 缩放至 32x32 灰度图；
     * 2. 执行可分离二维离散余弦变换 (2D DCT) 提取低频宏观结构；
     * 3. 取左上角 8x8 能量系数（剔除高频压缩噪点与边缘微弱形变）；
     * 4. 计算 63 个 AC 交流系数均值，大于均值置 1，生成 64 位鲁棒指纹。
     *
     * @param src 输入 Bitmap
     * @return 64 位整型哈希指纹，输入无效时返回 0L
     */
    public static long calculatePHash(@Nullable Bitmap src) {
        if (src == null || src.isRecycled() || src.getWidth() <= 0 || src.getHeight() <= 0) {
            return 0L;
        }

        Bitmap scaled = Bitmap.createScaledBitmap(src, 32, 32, true);
        int[] pixels = new int[32 * 32];
        scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32);
        if (scaled != src) {
            scaled.recycle();
        }

        // 灰度化矩阵
        double[][] vals = new double[32][32];
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                vals[y][x] = toGray(pixels[y * 32 + x]);
            }
        }

        // 可分离 2D DCT：第一步 行变换 (Row DCT)
        double[][] temp = new double[32][8];
        for (int y = 0; y < 32; y++) {
            for (int u = 0; u < 8; u++) {
                double sum = 0.0;
                for (int x = 0; x < 32; x++) {
                    sum += vals[y][x] * COS_TABLE[u][x];
                }
                temp[y][u] = sum;
            }
        }

        // 可分离 2D DCT：第二步 列变换 (Column DCT)
        double[][] dct = new double[8][8];
        double sumAc = 0.0;
        for (int v = 0; v < 8; v++) {
            for (int u = 0; u < 8; u++) {
                double sum = 0.0;
                for (int y = 0; y < 32; y++) {
                    sum += temp[y][u] * COS_TABLE[v][y];
                }
                dct[v][u] = sum;
                if (v != 0 || u != 0) {
                    sumAc += sum;
                }
            }
        }

        // 计算 63 个 AC 系数的平均值（排除 DC 系数 (0,0)，消除全局曝光/明暗偏移）
        double avgAc = sumAc / 63.0;

        long hash = 0L;
        for (int v = 0; v < 8; v++) {
            for (int u = 0; u < 8; u++) {
                if (dct[v][u] > avgAc) {
                    hash |= (1L << (v * 8 + u));
                }
            }
        }

        if (hash == 0L) {
            hash = 1L; // 避免全 0 与 UNKNOWN 冲突
        }
        return hash;
    }

    /**
     * 兼容旧接口，内部委派至感知哈希 pHash
     */
    public static long calculateDHash(@Nullable Bitmap src) {
        return calculatePHash(src);
    }

    /**
     * 将 RGB 颜色转换为感知灰度值 (0-255)
     */
    public static int toGray(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (r * 30 + g * 59 + b * 11) / 100;
    }

    /**
     * 计算两个 64 位哈希指纹的汉明距离（不同位的数量）
     * 距离越小表示图片视觉上越相似，0 为完全一致
     */
    public static int hammingDistance(long hash1, long hash2) {
        return Long.bitCount(hash1 ^ hash2);
    }

    /**
     * 自动探测新旧版本的最佳对齐偏移量 (newIndex - oldIndex)
     *
     * @param oldHashes     旧版本已计算的缩略图哈希集合 (key: 0-indexed 页码)
     * @param newHashes     新版本已计算的缩略图哈希集合
     * @param oldTotalPages 旧版本总页数
     * @param newTotalPages 新版本总页数
     * @return 最佳偏移量；若未找到达到置信度的匹配序列则返回 null
     */
    @Nullable
    public static Integer findAccurateOffset(
            @NonNull Map<Integer, Long> oldHashes,
            @NonNull Map<Integer, Long> newHashes,
            int oldTotalPages,
            int newTotalPages) {

        if (oldHashes.isEmpty() || newHashes.isEmpty()) {
            return null;
        }

        int pageDiff = newTotalPages - oldTotalPages;
        int margin = 3; // 边界容错余量，允许两端均有少量增删页

        // 1. 根据总页数之差，严格缩小候选偏移搜索范围
        int minOffset = Math.min(-margin, pageDiff - margin);
        int maxOffset = Math.max(margin, pageDiff + margin);

        int maxProbes = Math.min(MAX_PROBE_PAGES, oldTotalPages);

        // 2. 依次用旧版的前几页作为探针（跳过首页广告/封面重绘差异）
        for (int probeOldIdx = 0; probeOldIdx < maxProbes; probeOldIdx++) {
            Long probeOldHash = oldHashes.get(probeOldIdx);
            if (probeOldHash == null) {
                continue;
            }

            for (int offset = minOffset; offset <= maxOffset; offset++) {
                int targetNewIdx = probeOldIdx + offset;
                if (targetNewIdx < 0 || targetNewIdx >= newTotalPages) {
                    continue;
                }

                Long targetNewHash = newHashes.get(targetNewIdx);
                if (targetNewHash == null) {
                    continue;
                }

                // 探针初筛：若汉明距离在阈值内，触发连续锁链验证
                if (hammingDistance(probeOldHash, targetNewHash) <= MAX_HAMMING_DISTANCE) {
                    if (verifyConsecutiveChain(oldHashes, newHashes, offset, probeOldIdx, oldTotalPages, newTotalPages)) {
                        return offset; // 验证成功，立即早停返回
                    }
                }
            }
        }

        return null;
    }

    /**
     * 连续锁链验证：从 startOldIdx 开始，依次比对后续页面是否保持相同的 offset
     */
    private static boolean verifyConsecutiveChain(
            @NonNull Map<Integer, Long> oldHashes,
            @NonNull Map<Integer, Long> newHashes,
            int offset,
            int startOldIdx,
            int oldTotalPages,
            int newTotalPages) {

        int consecutiveMatches = 0;
        int maxCheck = 10; // 最多向后校验 10 页
        int requiredMatches = Math.min(REQUIRED_CONSECUTIVE_MATCHES,
                Math.min(oldTotalPages - startOldIdx, newTotalPages - Math.max(0, startOldIdx + offset)));
        if (requiredMatches <= 0) {
            requiredMatches = 1;
        }

        for (int i = 0; i < maxCheck; i++) {
            int oldIdx = startOldIdx + i;
            int newIdx = oldIdx + offset;

            if (oldIdx >= oldTotalPages || newIdx >= newTotalPages || newIdx < 0) {
                break;
            }

            Long oldH = oldHashes.get(oldIdx);
            Long newH = newHashes.get(newIdx);

            if (oldH == null || newH == null) {
                // 当前页哈希尚未加载，跳过不中断；但若已累积足够匹配数则可提前确认
                if (consecutiveMatches >= requiredMatches) {
                    return true;
                }
                continue;
            }

            int dist = hammingDistance(oldH, newH);
            if (dist <= MAX_HAMMING_DISTANCE) {
                consecutiveMatches++;
                if (consecutiveMatches >= requiredMatches) {
                    return true;
                }
            } else {
                // 出现不匹配，锁链中断重置
                consecutiveMatches = 0;
            }
        }

        return consecutiveMatches >= requiredMatches;
    }
}
