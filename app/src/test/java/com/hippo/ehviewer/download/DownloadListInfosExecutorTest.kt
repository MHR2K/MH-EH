package com.hippo.ehviewer.download

import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.sync.DownloadListInfosExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayList

class DownloadListInfosExecutorTest {

    private fun createInfo(gid: Long, title: String, titleJpn: String?, label: String?, tags: Array<String>?): DownloadInfo {
        val info = DownloadInfo(gid)
        info.title = title
        info.titleJpn = titleJpn
        info.label = label
        info.simpleTags = tags
        return info
    }

    @Test
    fun testTitleSearchMatchesDirectly() {
        val list = ArrayList<DownloadInfo>()
        list.add(createInfo(1L, "Touhou Project Book 1", "[東方] Touhou JPN", "LabelA", null))
        list.add(createInfo(2L, "Kantai Collection Book", null, "LabelA", null))
        list.add(createInfo(3L, "Fate Grand Order", "FGO JPN", "LabelB", null))

        val executor = DownloadListInfosExecutor(list, "touhou", false, true)
        val results = executor.executeSearchingSync()

        assertEquals(1, results.size)
        assertEquals(1L, results[0].gid)
    }

    @Test
    fun testTitleJpnSearchMatchesDirectly() {
        val list = ArrayList<DownloadInfo>()
        list.add(createInfo(1L, "English Title", "日文标题", "LabelA", null))
        list.add(createInfo(2L, "Other Title", "其他画廊", "LabelB", null))

        val executor = DownloadListInfosExecutor(list, "日文", false, true)
        val results = executor.executeSearchingSync()

        assertEquals(1, results.size)
        assertEquals(1L, results[0].gid)
    }

    @Test
    fun testTagSearchMatchesSimpleTags() {
        val list = ArrayList<DownloadInfo>()
        list.add(createInfo(1L, "Book One", null, "LabelA", arrayOf("artist:test_artist", "female:maid")))
        list.add(createInfo(2L, "Book Two", null, "LabelA", arrayOf("artist:other_artist", "male:teacher")))

        val executor = DownloadListInfosExecutor(list, "maid", false, true)
        val results = executor.executeSearchingSync()

        assertEquals(1, results.size)
        assertEquals(1L, results[0].gid)
        // Verify tgList was populated in-memory
        assertNotNull(list[0].tgList)
        assertTrue(list[0].tgList!!.isNotEmpty())
    }

    @Test
    fun testNamespaceTagSearch() {
        val list = ArrayList<DownloadInfo>()
        list.add(createInfo(1L, "Book One", null, "LabelA", arrayOf("artist:homura", "female:maid")))
        list.add(createInfo(2L, "Book Two", null, "LabelA", arrayOf("character:homura", "female:swimsuit")))

        // Search artist:homura should only match Book One
        val executor = DownloadListInfosExecutor(list, "artist:homura", false, true)
        val results = executor.executeSearchingSync()

        assertEquals(1, results.size)
        assertEquals(1L, results[0].gid)
    }

    @Test
    fun testGroupedResults() {
        val list = ArrayList<DownloadInfo>()
        list.add(createInfo(1L, "Naruto Vol 1", null, "LabelA", null))
        list.add(createInfo(2L, "Naruto Vol 2", null, "LabelB", null))
        list.add(createInfo(3L, "Bleach Vol 1", null, "LabelA", null))

        val executor = DownloadListInfosExecutor(list, "Naruto", false, true)
        executor.setGroupByLabel(true)
        val results = executor.executeSearchingSync()

        assertEquals(2, results.size)
    }

    @Test
    fun testEmptyAndNoMatchHandledSafely() {
        val list = ArrayList<DownloadInfo>()
        list.add(createInfo(1L, "No match", null, null, null))

        val executor = DownloadListInfosExecutor(list, "nonexistent", false, true)
        val results = executor.executeSearchingSync()

        assertEquals(0, results.size)
        // Verify empty tag list does not cause NPE or infinite re-query
        assertNotNull(list[0].tgList)
        assertEquals(0, list[0].tgList!!.size)
    }

    @Test
    fun testGalleryTagsDirectMatch() {
        val list = ArrayList<DownloadInfo>()
        val item1 = createInfo(10L, "Sample Book 1", null, "LabelA", null)
        val tags1 = com.hippo.ehviewer.dao.GalleryTags(10L)
        tags1.artist = "kantoku,karory"
        tags1.female = "big breasts,maid"
        item1.galleryTags = tags1
        list.add(item1)

        val item2 = createInfo(20L, "Sample Book 2", null, "LabelA", null)
        val tags2 = com.hippo.ehviewer.dao.GalleryTags(20L)
        tags2.artist = "other_artist"
        tags2.female = "swimsuit"
        item2.galleryTags = tags2
        list.add(item2)

        // 1. General tag search: "maid" should match item1 through tags1.female directly
        val executor1 = DownloadListInfosExecutor(list, "maid", false, true)
        val results1 = executor1.executeSearchingSync()
        assertEquals(1, results1.size)
        assertEquals(10L, results1[0].gid)

        // 2. Namespace search: "artist:kantoku" should match item1
        val executor2 = DownloadListInfosExecutor(list, "artist:kantoku", false, true)
        val results2 = executor2.executeSearchingSync()
        assertEquals(1, results2.size)
        assertEquals(10L, results2[0].gid)

        // 3. Namespace search mismatch: "artist:maid" should NOT match item1
        val executor3 = DownloadListInfosExecutor(list, "artist:maid", false, true)
        val results3 = executor3.executeSearchingSync()
        assertEquals(0, results3.size)

        // 4. Lazy getTgList() on demand
        val tgList = item1.getTgList()
        assertNotNull(tgList)
        assertTrue(tgList!!.contains("artist:kantoku"))
        assertTrue(tgList.contains("female:maid"))
    }
}
