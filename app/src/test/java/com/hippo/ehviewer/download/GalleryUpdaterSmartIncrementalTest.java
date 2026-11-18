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

package com.hippo.ehviewer.download;

import com.hippo.ehviewer.client.data.NewVersion;
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryUpdateDialog;
import com.hippo.unifile.UniFile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GalleryUpdaterSmartIncrementalTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testHandleSmartIncremental_ShiftAndDropDiffPages() throws IOException {
        File dirFile = tempFolder.newFolder("gallery_old");
        // 创建 5 个旧页面文件，写入独特内容以验证防覆盖重命名
        for (int i = 1; i <= 5; i++) {
            File f = new File(dirFile, String.format("%08d.jpg", i));
            Files.write(f.toPath(), ("old_page_" + i).getBytes(StandardCharsets.UTF_8));
        }

        UniFile uniDir = UniFile.fromFile(dirFile);
        GalleryUpdater updater = new GalleryUpdater(null, null);

        // 模拟：新版共 6 页，开头新增 1 页 (offset = +1)，且旧版第 3 页发生修改不保留
        Map<Integer, Integer> mapping = new HashMap<>();
        mapping.put(1, 2);
        mapping.put(2, 3);
        // 第 3 页不保留
        mapping.put(4, 5);
        mapping.put(5, 6);

        NewVersion nv = new NewVersion();
        nv.versionName = "v2";
        nv.versionUrl = "https://e-hentai.org/g/1/abc/";
        GalleryUpdateDialog.UpdateRequest request = new GalleryUpdateDialog.UpdateRequest(
                GalleryUpdateDialog.MODE_SMART_INCREMENTAL, nv);
        request.smartPageMapping = mapping;
        request.smartNewTotalPages = 6;

        boolean ok = updater.handleSmartIncremental(uniDir, request, 5, 6);
        assertTrue(ok);

        // 第 1 页和第 4 页应为空缺，等待下载器补齐
        assertFalse(new File(dirFile, "00000001.jpg").exists());
        assertFalse(new File(dirFile, "00000004.jpg").exists());

        // 验证保留下来的文件及其内容是否精准映射且无覆盖丢失
        assertTrue(new File(dirFile, "00000002.jpg").exists());
        assertEquals("old_page_1", new String(Files.readAllBytes(new File(dirFile, "00000002.jpg").toPath()), StandardCharsets.UTF_8));

        assertTrue(new File(dirFile, "00000003.jpg").exists());
        assertEquals("old_page_2", new String(Files.readAllBytes(new File(dirFile, "00000003.jpg").toPath()), StandardCharsets.UTF_8));

        assertTrue(new File(dirFile, "00000005.jpg").exists());
        assertEquals("old_page_4", new String(Files.readAllBytes(new File(dirFile, "00000005.jpg").toPath()), StandardCharsets.UTF_8));

        assertTrue(new File(dirFile, "00000006.jpg").exists());
        assertEquals("old_page_5", new String(Files.readAllBytes(new File(dirFile, "00000006.jpg").toPath()), StandardCharsets.UTF_8));
    }
}

