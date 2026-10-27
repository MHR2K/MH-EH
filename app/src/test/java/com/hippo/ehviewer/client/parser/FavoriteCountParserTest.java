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

package com.hippo.ehviewer.client.parser;

import static org.junit.Assert.assertEquals;

import com.hippo.ehviewer.client.EhEngine;
import com.hippo.lib.yorozuya.IOUtils;

import org.junit.Test;

import java.io.InputStream;

public class FavoriteCountParserTest {

    @Test
    public void testParseFavoriteCountNever() {
        String html = "<tr><td class=\"gdt1\">Favorited:</td><td class=\"gdt2\" id=\"favcount\">Never</td></tr>";
        assertEquals(0, EhEngine.parseFavoriteCountFromHtml(html));
    }

    @Test
    public void testParseFavoriteCountOnce() {
        String html = "<tr><td class=\"gdt1\">Favorited:</td><td class=\"gdt2\" id=\"favcount\">Once</td></tr>";
        assertEquals(1, EhEngine.parseFavoriteCountFromHtml(html));
    }

    @Test
    public void testParseFavoriteCountNumber() {
        String html = "<tr><td class=\"gdt1\">Favorited:</td><td class=\"gdt2\" id=\"favcount\">2225 times</td></tr>";
        assertEquals(2225, EhEngine.parseFavoriteCountFromHtml(html));
    }

    @Test
    public void testParseFavoriteCountWithComma() {
        String html = "<tr><td class=\"gdt1\">Favorited:</td><td class=\"gdt2\" id=\"favcount\">12,345 times</td></tr>";
        assertEquals(12345, EhEngine.parseFavoriteCountFromHtml(html));
    }

    @Test
    public void testParseFavoriteCountFallbackWithoutId() {
        String html = "<tr><td class=\"gdt1\">Favorited:</td><td class=\"gdt2\">567 times</td></tr>";
        assertEquals(567, EhEngine.parseFavoriteCountFromHtml(html));
    }

    @Test
    public void testParseFavoriteCountInvalid() {
        assertEquals(-1, EhEngine.parseFavoriteCountFromHtml(null));
        assertEquals(-1, EhEngine.parseFavoriteCountFromHtml("<div>No favorite count here</div>"));
    }

    @Test
    public void testParseFromRealResources() throws Exception {
        InputStream is = getClass().getResourceAsStream("/com/hippo/ehviewer/client/parser/GalleryDetail.html");
        if (is != null) {
            String html = IOUtils.readString(is, "UTF-8");
            assertEquals(2225, EhEngine.parseFavoriteCountFromHtml(html));
        }

        InputStream isSpider = getClass().getResourceAsStream("/com/hippo/ehviewer/client/parser/spiderInfo.html");
        if (isSpider != null) {
            String html = IOUtils.readString(isSpider, "UTF-8");
            assertEquals(10, EhEngine.parseFavoriteCountFromHtml(html));
        }

        InputStream isNew = getClass().getResourceAsStream("/com/hippo/ehviewer/client/parser/newPage.html");
        if (isNew != null) {
            String html = IOUtils.readString(isNew, "UTF-8");
            assertEquals(23177, EhEngine.parseFavoriteCountFromHtml(html));
        }
    }

    @Test
    public void testParseTitleJpnNormal() {
        String html = "<h1 id=\"gj\">[Studio] 作品名 (日本語)</h1>";
        assertEquals("[Studio] 作品名 (日本語)", EhEngine.parseTitleJpnFromHtml(html));
    }

    @Test
    public void testParseTitleJpnEntities() {
        String html = "<h1 id=\"gj\">&lt;Author&gt; &quot;Title&quot; &amp; &#039;Sub&#039;</h1>";
        assertEquals("<Author> \"Title\" & 'Sub'", EhEngine.parseTitleJpnFromHtml(html));
    }

    @Test
    public void testParseTitleJpnEmptyAndInvalid() {
        org.junit.Assert.assertNull(EhEngine.parseTitleJpnFromHtml(null));
        org.junit.Assert.assertNull(EhEngine.parseTitleJpnFromHtml(""));
        org.junit.Assert.assertNull(EhEngine.parseTitleJpnFromHtml("<h1 id=\"gj\"></h1>"));
        org.junit.Assert.assertNull(EhEngine.parseTitleJpnFromHtml("<h1 id=\"gj\">   </h1>"));
        org.junit.Assert.assertNull(EhEngine.parseTitleJpnFromHtml("<div>No title jpn</div>"));
    }

    @Test
    public void testParseTitleJpnFromRealResources() throws Exception {
        InputStream is = getClass().getResourceAsStream("/com/hippo/ehviewer/client/parser/GalleryDetail.html");
        if (is != null) {
            String html = IOUtils.readString(is, "UTF-8");
            assertEquals("[DMM.com] FLOWER KNIGHT GIRL ～X指定～ 2022.11～", EhEngine.parseTitleJpnFromHtml(html));
        }

        InputStream isSpider = getClass().getResourceAsStream("/com/hippo/ehviewer/client/parser/spiderInfo.html");
        if (isSpider != null) {
            String html = IOUtils.readString(isSpider, "UTF-8");
            assertEquals("[Maze] MP7ズリ (ドールズフロントライン)", EhEngine.parseTitleJpnFromHtml(html));
        }

        InputStream isNew = getClass().getResourceAsStream("/com/hippo/ehviewer/client/parser/newPage.html");
        if (isNew != null) {
            String html = IOUtils.readString(isNew, "UTF-8");
            org.junit.Assert.assertNull(EhEngine.parseTitleJpnFromHtml(html));
        }
    }

    @Test
    public void testParseNewVersionUrlNormal() {
        String html = "<div id=\"gnd\"><p>New version:</p><a href=\"https://e-hentai.org/g/123/abc/\">Title v1</a></div>";
        assertEquals("https://e-hentai.org/g/123/abc/", EhEngine.parseNewVersionUrlFromHtml(html));
    }

    @Test
    public void testParseNewVersionUrlMultiple() {
        String html = "<div id=\"gnd\"><a href=\"https://e-hentai.org/g/1/a/\">v1</a><br/><a href=\"https://e-hentai.org/g/2/b/\">v2</a></div>";
        assertEquals("https://e-hentai.org/g/2/b/", EhEngine.parseNewVersionUrlFromHtml(html));
    }

    @Test
    public void testParseNewVersionUrlRelative() {
        String html = "<div id=\"gnd\"><a href=\"/g/3/c/\">v3</a></div>";
        assertEquals("https://e-hentai.org/g/3/c/", EhEngine.parseNewVersionUrlFromHtml(html));
    }

    @Test
    public void testParseNewVersionUrlEmptyAndNull() {
        org.junit.Assert.assertNull(EhEngine.parseNewVersionUrlFromHtml(null));
        org.junit.Assert.assertNull(EhEngine.parseNewVersionUrlFromHtml(""));
        org.junit.Assert.assertNull(EhEngine.parseNewVersionUrlFromHtml("<div>No new version</div>"));
        org.junit.Assert.assertNull(EhEngine.parseNewVersionUrlFromHtml("<div id=\"gnd\"></div>"));
    }

    @Test
    public void testParseNewVersionUrlFromRealResources() throws Exception {
        InputStream isDetail = getClass().getResourceAsStream("/com/hippo/ehviewer/client/parser/GalleryDetail.html");
        if (isDetail != null) {
            String html = IOUtils.readString(isDetail, "UTF-8");
            org.junit.Assert.assertNull(EhEngine.parseNewVersionUrlFromHtml(html));
        }

        InputStream isNew = getClass().getResourceAsStream("/com/hippo/ehviewer/client/parser/newPage.html");
        if (isNew != null) {
            String html = IOUtils.readString(isNew, "UTF-8");
            assertEquals("https://e-hentai.org/g/2582794/a49e40fb43/", EhEngine.parseNewVersionUrlFromHtml(html));
        }
    }
}

