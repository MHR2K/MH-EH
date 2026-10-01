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

package com.hippo.ehviewer.ui.scene.download.part

import android.annotation.SuppressLint
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.recyclerview.widget.RecyclerView
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderInfo.getSpiderInfo
import com.hippo.ehviewer.sync.DownloadSpiderInfoExecutor
import com.hippo.ehviewer.ui.scene.download.DownloadsScene.LOCAL_GALLERY_INFO_CHANGE
import com.hippo.ehviewer.widget.MyEasyRecyclerView
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager
import com.sxj.paginationlib.PaginationIndicator

/**
 * 下载列表分页与阅读进度。
 */
class DownloadPaginationController(private val mHost: Host) {

    interface Host {
        val list: List<DownloadInfo>?
        val notifyAdapter: RecyclerView.Adapter<*>?
        val recyclerView: MyEasyRecyclerView?
        val layoutManager: AutoStaggeredGridLayoutManager?
        /** 清除 Repository 内存缓存，强制重新加载最新阅读进度。 */
        fun invalidateSpiderInfoCache(gid: Long)
    }

    var indexPage = 1
    var pageSize = 1
    var canPagination = true
    val paginationSize = 500
    //    val paginationSize = 5
    val perPageCountChoices = intArrayOf(50, 100, 200, 300, 500)
    //    val perPageCountChoices = intArrayOf(1, 2, 3, 4, 5)

    var myPageChangeListener: MyPageChangeListener? = null
        private set

    var paginationIndicator: PaginationIndicator? = null

    val spiderInfoMap: MutableMap<Long, SpiderInfo> = mutableMapOf()

    var restoreScrollGid: Long = -1
    var doNotScroll = false
    var needInitPage = false
    private var needInitPageSize = false

    fun bindPageChangeListener(adapter: RecyclerView.Adapter<*>?, recyclerView: MyEasyRecyclerView?) {
        myPageChangeListener = MyPageChangeListener(indexPage, pageSize, needInitPage, doNotScroll, adapter, recyclerView)
        // 注意：本地策略是一次查询全列表的 SpiderInfo（queryUnreadSpiderInfo 不分页），
        // 因此翻页/改页大小不重新查询——与上游的每页查询策略不同，保持本地行为。
        myPageChangeListener?.setPageChangeCallback(object : MyPageChangeListener.PageChangeCallback {
            override fun onPageChanged(newIndexPage: Int) {
                indexPage = newIndexPage
            }
            override fun onPageSizeChanged(newPageSize: Int) {
                pageSize = newPageSize
            }
        })
    }

    fun positionInList(position: Int): Int {
        val list = mHost.list
        return if (list != null && list.size > paginationSize && canPagination) {
            position + pageSize * (indexPage - 1)
        } else {
            position
        }
    }

    fun listIndexInPage(position: Int): Int {
        val list = mHost.list
        return if (list != null && list.size > paginationSize && canPagination) {
            position % pageSize
        } else {
            position
        }
    }

    fun updatePaginationIndicator() {
        val list = mHost.list ?: return
        val indicator = paginationIndicator ?: return
        if (list.size < paginationSize || !canPagination) {
            indicator.visibility = View.GONE
            return
        }
        indicator.visibility = View.VISIBLE
        needInitPageSize = true
        indicator.initPaginationIndicator(pageSize, perPageCountChoices, list.size, indexPage)
        //        indicator.setTotalCount()
        indicator.setListener(myPageChangeListener)

        // 同步分页监听器的状态
        myPageChangeListener?.let {
            it.setIndexPage(indexPage)
            it.setPageSize(pageSize)
            it.setNeedInitPage(needInitPage)
            it.setDoNotScroll(doNotScroll)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateReadProcess(result: ActivityResult) {
        if (result.resultCode == LOCAL_GALLERY_INFO_CHANGE) {
            val data = result.data ?: return
            val info: GalleryInfo? = data.getParcelableExtra("info")

            // Check if this is an imported archive - skip SpiderInfo processing
            var isImportedArchive = false
            if (info is DownloadInfo) {
                isImportedArchive = info.archiveUri != null &&
                        info.archiveUri.startsWith("content://")
            }

            if (!isImportedArchive && info != null) {
                // Only process SpiderInfo for regular downloads, not imported archives
                spiderInfoMap.remove(info.gid)
                // 清除Repository的内存缓存，强制重新加载最新进度
                mHost.invalidateSpiderInfoCache(info.gid)
                val spiderInfo = getSpiderInfo(info)
                if (spiderInfo != null) {
                    spiderInfoMap[info.gid] = spiderInfo
                }
            }

            val list = mHost.list ?: return
            val adapter = mHost.notifyAdapter ?: return
            if (info == null) return

            var position = -1
            for (i in list.indices) {
                if (list[i].gid == info.gid) {
                    position = listIndexInPage(i)
                    break
                }
            }
            if (position != -1) {
                adapter.notifyItemChanged(position)
            } else {
                adapter.notifyDataSetChanged()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun resetReadingProgressInUi() {
        for (spiderInfo in spiderInfoMap.values) {
            spiderInfo?.startPage = 0
        }
        mHost.notifyAdapter?.notifyDataSetChanged()
    }

    fun queryUnreadSpiderInfo() {
        val list = mHost.list ?: return
        val requestList = list.filter { !spiderInfoMap.containsKey(it.gid) || spiderInfoMap[it.gid] == null }.toMutableList()
        if (requestList.isEmpty()) return
        val executor = DownloadSpiderInfoExecutor(requestList) { resultMap -> spiderInfoResultCallBack(resultMap) }
        executor.execute()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun spiderInfoResultCallBack(resultMap: Map<Long, SpiderInfo>) {
        spiderInfoMap.putAll(resultMap)
        mHost.notifyAdapter?.notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun initPage(position: Int) {
        val list = mHost.list
        if (list != null && list.size > paginationSize && canPagination) {
            indexPage = position / pageSize + 1
        }
        doNotScroll = true
        paginationIndicator?.skip2Pos(indexPage)
        val scrollTo = listIndexInPage(position)
        val scrollTarget = maxOf(0, scrollTo - 1)
        mHost.recyclerView?.let { rv -> rv.post { rv.scrollToPosition(scrollTarget) } }
    }

    fun getPageSizePos(pageSize: Int): Int {
        for (i in perPageCountChoices.indices) {
            if (pageSize == perPageCountChoices[i]) return i
        }
        return 0
    }

    /**
     * 捕获当前第一个可见项的 gid，用于过滤/搜索后恢复滚动位置。
     */
    fun captureFirstVisibleGid(): Long {
        val recyclerView = mHost.recyclerView ?: return -1
        val layoutManager = mHost.layoutManager ?: return -1
        val list = mHost.list ?: return -1
        return try {
            val spanCount = layoutManager.spanCount
            val firsts = IntArray(spanCount)
            layoutManager.findFirstVisibleItemPositions(firsts)
            val min = firsts.filter { it >= 0 }.minOrNull() ?: -1
            if (min < 0) return -1
            val listPos = positionInList(min)
            if (listPos in 0 until list.size) list[listPos].gid else -1
        } catch (ignore: Throwable) {
            // 容错处理，无法获取时返回 -1
            -1
        }
    }

    /**
     * 过滤/搜索完成后，根据之前记录的 gid 恢复到相邻位置，避免回到顶部。
     */
    fun restoreScrollPositionIfNeeded() {
        val list = mHost.list ?: return
        val recyclerView = mHost.recyclerView ?: return
        if (restoreScrollGid == -1L) return
        val targetIndex = list.indexOfFirst { it.gid == restoreScrollGid }
        if (targetIndex >= 0) {
            val adapterPos = listIndexInPage(targetIndex)
            recyclerView.scrollToPosition(adapterPos)
        }
        restoreScrollGid = -1
    }
}