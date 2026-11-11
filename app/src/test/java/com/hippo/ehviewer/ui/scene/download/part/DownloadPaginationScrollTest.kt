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

package com.hippo.ehviewer.ui.scene.download.part

import androidx.recyclerview.widget.RecyclerView
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.widget.MyEasyRecyclerView
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPaginationScrollTest {

    @Test
    fun testDoNotScrollConsumedAndNotified() {
        val listener = MyPageChangeListener(1, 50, false, true, null, null)
        var consumed = false
        listener.setPageChangeCallback(object : MyPageChangeListener.PageChangeCallback {
            override fun onPageChanged(newIndexPage: Int) {}
            override fun onPageSizeChanged(newPageSize: Int) {}
            override fun onDoNotScrollConsumed() {
                consumed = true
            }
        })

        assertTrue(listener.isDoNotScroll)
        listener.notifyAdapter()
        assertFalse(listener.isDoNotScroll)
        assertTrue(consumed)
    }

    @Test
    fun testNeedInitPageConsumedAndNotified() {
        val listener = MyPageChangeListener(1, 50, true, false, null, null)
        var consumed = false
        var pageChanged = false
        listener.setPageChangeCallback(object : MyPageChangeListener.PageChangeCallback {
            override fun onPageChanged(newIndexPage: Int) {
                pageChanged = true
            }
            override fun onPageSizeChanged(newPageSize: Int) {}
            override fun onNeedInitPageConsumed() {
                consumed = true
            }
        })

        assertTrue(listener.isNeedInitPage)
        listener.onPageSelectedChanged(2, 1, 5, 250)
        assertFalse(listener.isNeedInitPage)
        assertTrue(consumed)
        assertTrue(pageChanged)
        assertEquals(2, listener.indexPage)
    }

    @Test
    fun testPaginationControllerSyncFlags() {
        val host = object : DownloadPaginationController.Host {
            override val list: List<DownloadInfo>? = emptyList()
            override val notifyAdapter: RecyclerView.Adapter<*>? = null
            override val recyclerView: MyEasyRecyclerView? = null
            override val layoutManager: AutoStaggeredGridLayoutManager? = null
            override fun invalidateSpiderInfoCache(gid: Long) {}
        }

        val controller = DownloadPaginationController(host)
        controller.bindPageChangeListener(null, null)

        controller.doNotScroll = true
        assertTrue(controller.doNotScroll)
        assertTrue(controller.myPageChangeListener?.isDoNotScroll == true)

        controller.needInitPage = true
        assertTrue(controller.needInitPage)
        assertTrue(controller.myPageChangeListener?.isNeedInitPage == true)

        // Consume doNotScroll via notifyAdapter
        controller.myPageChangeListener?.notifyAdapter()
        assertFalse(controller.myPageChangeListener?.isDoNotScroll == true)
        assertFalse(controller.doNotScroll)
    }

    @Test
    fun testInitPageResetsDoNotScroll() {
        val host = object : DownloadPaginationController.Host {
            override val list: List<DownloadInfo>? = emptyList()
            override val notifyAdapter: RecyclerView.Adapter<*>? = null
            override val recyclerView: MyEasyRecyclerView? = null
            override val layoutManager: AutoStaggeredGridLayoutManager? = null
            override fun invalidateSpiderInfoCache(gid: Long) {}
        }

        val controller = DownloadPaginationController(host)
        controller.bindPageChangeListener(null, null)

        // Calling initPage without indicator should still safely clean up doNotScroll
        controller.initPage(10)
        assertFalse(controller.doNotScroll)
        assertFalse(controller.myPageChangeListener?.isDoNotScroll == true)
    }
}

