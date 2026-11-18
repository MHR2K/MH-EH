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

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class GalleryPreviewAlignHelperTest {

    @Test
    public void testHammingDistance() {
        assertEquals(0, GalleryPreviewAlignHelper.hammingDistance(0L, 0L));
        assertEquals(0, GalleryPreviewAlignHelper.hammingDistance(0x123456789ABCDEF0L, 0x123456789ABCDEF0L));
        assertEquals(1, GalleryPreviewAlignHelper.hammingDistance(0L, 1L));
        assertEquals(64, GalleryPreviewAlignHelper.hammingDistance(0L, -1L));
        assertEquals(4, GalleryPreviewAlignHelper.hammingDistance(0b1111L, 0b0000L));
    }

    @Test
    public void testToGray() {
        assertEquals(0, GalleryPreviewAlignHelper.toGray(0xFF000000));
        assertEquals(255, GalleryPreviewAlignHelper.toGray(0xFFFFFFFF));
    }

    @Test
    public void testFindAccurateOffset_ShiftForward() {
        // 模拟场景：旧版 20 页，新版 22 页（新版前面增加了 2 页彩页）
        // 旧版 P0, P1, P2, P3 对应新版 P2, P3, P4, P5
        Map<Integer, Long> oldHashes = new HashMap<>();
        Map<Integer, Long> newHashes = new HashMap<>();

        long hashA = 0xAAAAAAAAAAAAAAAAL;
        long hashB = 0xBBBBBBBBBBBBBBBBL;
        long hashC = 0xCCCCCCCCCCCCCCCCL;
        long hashD = 0xDDDDDDDDDDDDDDDDL;

        // 新版前 2 页是新增彩页
        newHashes.put(0, 0x1111111111111111L);
        newHashes.put(1, 0x2222222222222222L);

        // 对应内容依次排列
        oldHashes.put(0, hashA);
        oldHashes.put(1, hashB);
        oldHashes.put(2, hashC);
        oldHashes.put(3, hashD);

        newHashes.put(2, hashA);
        newHashes.put(3, hashB);
        newHashes.put(4, hashC);
        newHashes.put(5, hashD);

        Integer offset = GalleryPreviewAlignHelper.findAccurateOffset(oldHashes, newHashes, 20, 22);
        assertNotNull(offset);
        assertEquals(2, offset.intValue());
    }

    @Test
    public void testFindAccurateOffset_FirstPageDiff_AdTrap() {
        // 模拟陷阱场景：旧版第 0 页是旧汉化组广告，新版第 0 页是新汉化组广告
        // 但从第 1 页开始（正文），旧版 P1, P2, P3, P4 对应新版 P1, P2, P3, P4（Offset = 0）
        Map<Integer, Long> oldHashes = new HashMap<>();
        Map<Integer, Long> newHashes = new HashMap<>();

        oldHashes.put(0, 0x1111111111111111L); // 旧广告
        newHashes.put(0, 0x9999999999999999L); // 新广告

        long hashP1 = 0xABABABABABABABABL;
        long hashP2 = 0xCDCDCDCDCDCDCDCDL;
        long hashP3 = 0xEFEFEFEFEFEFEFEFL;
        long hashP4 = 0x1234567812345678L;

        oldHashes.put(1, hashP1);
        oldHashes.put(2, hashP2);
        oldHashes.put(3, hashP3);
        oldHashes.put(4, hashP4);

        newHashes.put(1, hashP1);
        newHashes.put(2, hashP2);
        newHashes.put(3, hashP3);
        newHashes.put(4, hashP4);

        Integer offset = GalleryPreviewAlignHelper.findAccurateOffset(oldHashes, newHashes, 30, 30);
        assertNotNull(offset);
        assertEquals(0, offset.intValue());
    }

    @Test
    public void testFindAccurateOffset_Tolerance() {
        // 模拟允许汉明距离轻微差异（如嵌字、轻微调色导致距离在 1~3 位以内）
        Map<Integer, Long> oldHashes = new HashMap<>();
        Map<Integer, Long> newHashes = new HashMap<>();

        long hashBase1 = 0xAAAAAAAAAAAAAAAAL;
        long hashTolerated1 = 0xAAAAAAAAAAAAAAAAL ^ 0b101L; // 汉明距离为 2

        long hashBase2 = 0xBBBBBBBBBBBBBBBBL;
        long hashTolerated2 = 0xBBBBBBBBBBBBBBBBL ^ 0b11L;  // 汉明距离为 2

        long hashBase3 = 0xCCCCCCCCCCCCCCCCL;
        long hashTolerated3 = 0xCCCCCCCCCCCCCCCCL ^ 0b1L;   // 汉明距离为 1

        oldHashes.put(0, hashBase1);
        oldHashes.put(1, hashBase2);
        oldHashes.put(2, hashBase3);

        newHashes.put(1, hashTolerated1);
        newHashes.put(2, hashTolerated2);
        newHashes.put(3, hashTolerated3);

        Integer offset = GalleryPreviewAlignHelper.findAccurateOffset(oldHashes, newHashes, 50, 51);
        assertNotNull(offset);
        assertEquals(1, offset.intValue());
    }

    @Test
    public void testFindAccurateOffset_NoMatch() {
        // 完全不相关的两个画廊
        Map<Integer, Long> oldHashes = new HashMap<>();
        Map<Integer, Long> newHashes = new HashMap<>();

        oldHashes.put(0, 0x1111111111111111L);
        oldHashes.put(1, 0x2222222222222222L);
        oldHashes.put(2, 0x3333333333333333L);

        newHashes.put(0, 0xAAAAAAAAAAAAAAAAL);
        newHashes.put(1, 0xBBBBBBBBBBBBBBBBL);
        newHashes.put(2, 0xCCCCCCCCCCCCCCCCL);

        Integer offset = GalleryPreviewAlignHelper.findAccurateOffset(oldHashes, newHashes, 20, 20);
        assertNull(offset);
    }

    @Test
    public void testEvaluateMatchStatus() {
        long hash = 0x123456789ABCDEF0L;
        // 距离 0 -> IDENTICAL
        assertEquals(GalleryPreviewAlignHelper.PageMatchStatus.IDENTICAL,
                GalleryPreviewAlignHelper.evaluateMatchStatus(hash, hash));

        // 距离 2 -> IDENTICAL
        assertEquals(GalleryPreviewAlignHelper.PageMatchStatus.IDENTICAL,
                GalleryPreviewAlignHelper.evaluateMatchStatus(hash, hash ^ 0b11L));

        // 距离 5 (<= 7) -> IDENTICAL (抗噪容许范围内)
        assertEquals(GalleryPreviewAlignHelper.PageMatchStatus.IDENTICAL,
                GalleryPreviewAlignHelper.evaluateMatchStatus(hash, hash ^ 0b11111L));

        // 距离 10 (8..16) -> MODIFIED (局部修图/嵌字)
        assertEquals(GalleryPreviewAlignHelper.PageMatchStatus.MODIFIED,
                GalleryPreviewAlignHelper.evaluateMatchStatus(hash, hash ^ 0x3FFL));

        // 距离 20 (> 16) -> DIFFERENT (显著差异/不同页)
        assertEquals(GalleryPreviewAlignHelper.PageMatchStatus.DIFFERENT,
                GalleryPreviewAlignHelper.evaluateMatchStatus(hash, hash ^ 0xFFFFFL));

        // 0L -> UNKNOWN
        assertEquals(GalleryPreviewAlignHelper.PageMatchStatus.UNKNOWN,
                GalleryPreviewAlignHelper.evaluateMatchStatus(0L, hash));
    }
}
