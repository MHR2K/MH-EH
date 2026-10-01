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

import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.callBack.DownloadSearchCallback
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.sync.DownloadListInfosExecutor
import com.hippo.ehviewer.widget.MyEasyRecyclerView
import com.hippo.ehviewer.widget.SearchBar
import com.hippo.widget.SearchBarMover
import com.hippo.util.DrawableManager
import com.hippo.widget.ProgressView

/**
 * 下载页搜索与筛选（本地超集：组合筛选、阅读进度筛选、模糊/大小写/简繁、
 * 按相关性排序、全标签分组折叠、搜索相同作者）。
 */
class DownloadSearchController(private val mHost: Host?) : SearchBar.Helper,
    SearchBarMover.Helper, SearchBar.OnStateChangeListener {

    interface Host {
        fun getEHContext(): Context?
        var searchKey: String?
        var isSearching: Boolean
        val progressView: ProgressView?
        val searchProgressContainer: View?
        val recyclerView: MyEasyRecyclerView?
        var list: MutableList<DownloadInfo>?
        val backList: MutableList<DownloadInfo>?
        val downloadManager: DownloadManager?
        val notifyAdapter: RecyclerView.Adapter<*>?
        val originalAdapter: DownloadAdapter?
        val categorySpinner: Spinner?
        var selectedCategory: Int
        val spiderInfoMap: Map<Long, SpiderInfo>?
        fun captureFirstVisibleGid(): Long
        var restoreScrollGid: Long
        var doNotScroll: Boolean
        val myPageChangeListener: MyPageChangeListener?
        fun restoreScrollPositionIfNeeded()
        fun updateTitle()
        fun updateView()
        fun updateForLabel()
        fun updatePaginationIndicator()
        fun queryUnreadSpiderInfo()
        val downloadSearchCallback: DownloadSearchCallback?
        @JvmSuppressWildcards
        fun setGroupedSearchResults(grouped: Map<String, @JvmSuppressWildcards List<DownloadInfo>>?)
        val collapsedLabels: Set<String>
    }

    private var mSearchDialog: AlertDialog? = null
    private var mSearchBar: SearchBar? = null
    private var mSearchBarMover: SearchBarMover? = null
    private var mSearchMode = false

    private var mFuzzySearchCheckbox: CheckBox? = null
    private var mIgnoreCaseCheckbox: CheckBox? = null
    private var mChineseConversionCheckbox: CheckBox? = null
    private var mSortByRelevanceCheckbox: CheckBox? = null
    private var mSearchAllLabelsCheckbox: CheckBox? = null

    // 全标签搜索分组折叠状态
    var isSearchAllLabelsMode = false
    var groupedSearchResults: Map<String, List<DownloadInfo>>? = null
    val collapsedLabels: MutableSet<String> = mutableSetOf()
    var searchExecutor: DownloadListInfosExecutor? = null

    /**
     * "查找相同作者"入口的搜索选项：非模糊、忽略大小写、启用简繁转换。
     * 复选框可能尚未创建（搜索对话框未打开过），此时为 no-op，
     * searchForAuthorAndCollectResults 会按 null 复选框回退。
     */
    fun configureAuthorSearchOptions() {
        mFuzzySearchCheckbox?.isChecked = false
        // 启用不区分大小写，提高搜索效果
        mIgnoreCaseCheckbox?.isChecked = true
        // 启用简繁体转换，提高中文作者名搜索效果
        mChineseConversionCheckbox?.isChecked = true
    }

    /*---------------
     Search bar plumbing
     ---------------*/

    fun gotoSearch(context: Context, helper: SearchBar.Helper?, moverHelper: SearchBarMover.Helper?) {
        mSearchDialog?.let {
            it.show()
            return
        }
        val layoutInflater = android.view.LayoutInflater.from(context)
        val drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_download)

        val linearLayout = layoutInflater.inflate(R.layout.download_search_dialog, null) as LinearLayout
        mSearchBar = linearLayout.findViewById<SearchBar>(R.id.download_search_bar).apply {
            setHelper(helper ?: this@DownloadSearchController)
            setIsComeFromDownload(true)
            setEditTextHint(R.string.download_search_hint)
            setLeftDrawable(drawable)
            val searchKey = mHost?.searchKey
            setText(searchKey)
            if (!searchKey.isNullOrEmpty()) {
                setTitle(searchKey)
                cursorToEnd()
            } else {
                setTitle(R.string.download_search_hint)
            }
            setRightDrawable(DrawableManager.getVectorDrawable(context, R.drawable.v_magnify_x24))
        }

        // 初始化复选框
        mFuzzySearchCheckbox = linearLayout.findViewById(R.id.fuzzy_search_checkbox)
        mIgnoreCaseCheckbox = linearLayout.findViewById(R.id.ignore_case_checkbox)
        mChineseConversionCheckbox = linearLayout.findViewById(R.id.chinese_conversion_checkbox)
        mSortByRelevanceCheckbox = linearLayout.findViewById(R.id.sort_by_relevance_checkbox)
        mSearchAllLabelsCheckbox = linearLayout.findViewById(R.id.search_all_labels_checkbox)

        // 初始化当前设置状态，从 Settings 加载所有搜索相关设置
        mFuzzySearchCheckbox?.isChecked = Settings.getEnableFuzzySearch()
        mIgnoreCaseCheckbox?.isChecked = Settings.getEnableIgnoreCase()
        mChineseConversionCheckbox?.isChecked = Settings.getEnableChineseConversion()
        mSortByRelevanceCheckbox?.isChecked = Settings.getEnableSortByRelevance()
        mSearchAllLabelsCheckbox?.isChecked = false

        // 设置"按相关性排序"复选框的启用状态，只有在模糊搜索启用时才可用
        updateSortByRelevanceState()

        // 添加模糊搜索复选框的点击监听器
        mFuzzySearchCheckbox?.setOnCheckedChangeListener { _, _ -> updateSortByRelevanceState() }

        mSearchBarMover = SearchBarMover(moverHelper ?: this, mSearchBar)
        mSearchDialog = AlertDialog.Builder(context)
            .setMessage(R.string.download_search_gallery)
            .setView(linearLayout)
            .setCancelable(true)
            .setOnDismissListener { dialog -> onSearchDialogDismiss(dialog) }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                mHost?.searchKey = null
                mSearchBar?.setText(null)
                mSearchBar?.setTitle(null)
                mSearchBar?.applySearch(true)
                dialog.dismiss()
            }
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                mSearchBar?.applySearch(true)
                dialog.dismiss()
            }.show()
    }

    fun onSearchDialogDismiss(dialog: DialogInterface) {
        mSearchMode = false
        // 清除复选框引用，避免对话框关闭后 startSearching() 读到旧的勾选状态
        mSearchAllLabelsCheckbox = null
        // 重置 Settings，防止旧版残留值影响非对话框入口的搜索（如"查找相同作者"）
        Settings.putEnableSearchAllLabels(false)
    }

    /**
     * 更新"按相关性排序"复选框的启用状态
     * 只有在"模糊搜索"启用时才允许使用
     */
    fun updateSortByRelevanceState() {
        val sortByRelevance = mSortByRelevanceCheckbox ?: return
        val fuzzySearch = mFuzzySearchCheckbox ?: return

        val fuzzySearchEnabled = fuzzySearch.isChecked
        sortByRelevance.isEnabled = fuzzySearchEnabled

        // 如果禁用了模糊搜索，自动取消勾选"按相关性排序"
        if (!fuzzySearchEnabled) {
            sortByRelevance.isChecked = false
        }
    }

    fun enterSearchMode(animation: Boolean) {
        if (mSearchMode || mSearchBar == null || mSearchBarMover == null) return
        mSearchMode = true
        mSearchBar?.setState(SearchBar.STATE_SEARCH_LIST, animation)
        mSearchBarMover?.returnSearchBarPosition(animation)
    }

    override fun onClickTitle() {
        if (!mSearchMode) enterSearchMode(true)
    }

    override fun onClickLeftIcon() {}

    override fun onClickRightIcon() {
        mSearchBar?.applySearch(true)
    }

    override fun onSearchEditTextClick() {}

    override fun onApplySearch(query: String?) {
        val host = mHost ?: return
        host.searchKey = query
        mSearchBar?.hideKeyBoard()
        host.isSearching = true
        startSearching()
    }

    fun startSearching() {
        val host = mHost ?: return
        // 显示搜索进度容器
        host.searchProgressContainer?.visibility = View.VISIBLE
        host.progressView?.visibility = View.VISIBLE
        host.recyclerView?.visibility = View.GONE

        if (mSearchMode) {
            mSearchMode = false
            mSearchBar?.apply {
                setTitle(host.searchKey)
                setState(SearchBar.STATE_NORMAL)
            }
        }

        mSearchDialog?.dismiss()

        // 只有当没有预先设置搜索结果时，才更新标签（避免覆盖已有的搜索结果）
        if (!host.isSearching) {
            host.updateForLabel()
        }

        // 根据"在全部标签中搜索"复选框决定搜索范围（复选框为 null 时回退到 Settings）
        val searchAllLabels = mSearchAllLabelsCheckbox?.isChecked ?: Settings.getEnableSearchAllLabels()
        val dm = host.downloadManager
        val searchTarget: List<DownloadInfo>? = if (searchAllLabels && dm != null) {
            dm.allDownloadInfoList
        } else {
            host.list
        }

        val executor = DownloadListInfosExecutor(searchTarget, host.searchKey)

        // 设置搜索选项（复选框为 null 时回退到 Settings）
        val fuzzySearch = mFuzzySearchCheckbox?.isChecked ?: Settings.getEnableFuzzySearch()
        val ignoreCase = mIgnoreCaseCheckbox?.isChecked ?: Settings.getEnableIgnoreCase()
        val enableChineseConversion = mChineseConversionCheckbox?.isChecked ?: Settings.getEnableChineseConversion()
        val sortByRelevance = fuzzySearch && (mSortByRelevanceCheckbox?.isChecked ?: Settings.getEnableSortByRelevance())

        executor.setSearchOptions(fuzzySearch, ignoreCase, enableChineseConversion)
        if (sortByRelevance) {
            executor.enableSortByRelevance(true)
        }

        // 全部标签搜索时启用分组模式
        isSearchAllLabelsMode = searchAllLabels
        if (searchAllLabels) {
            executor.setGroupByLabel(true)
            collapsedLabels.clear()
        }

        executor.setDownloadSearchingListener(host.downloadSearchCallback)
        searchExecutor = executor
        executor.executeSearching()
    }

    override fun onSearchEditTextBackPressed() {
        if (mSearchMode) mSearchMode = false
        mSearchBar?.setState(SearchBar.STATE_NORMAL, true)
    }

    override fun onStateChange(searchBar: SearchBar?, newState: Int, oldState: Int, animation: Boolean) {}

    override fun isValidView(recyclerView: RecyclerView): Boolean = false

    override fun getValidRecyclerView(): RecyclerView? = mHost?.recyclerView

    override fun forceShowSearchBar(): Boolean = false

    /*---------------
     Author search
     ---------------*/

    fun searchForAuthorAndCollectResults(resultsCollection: MutableList<DownloadInfo>): Boolean {
        val host = mHost ?: return false
        val backList = host.backList ?: return false
        val searchKey = host.searchKey
        if (searchKey.isNullOrEmpty()) return false

        // 使用当前的搜索设置
        val fuzzySearch = mFuzzySearchCheckbox?.isChecked == true
        val ignoreCase = mIgnoreCaseCheckbox?.isChecked == true
        val enableChineseConversion = mChineseConversionCheckbox?.isChecked == true
        val sortByRelevance = fuzzySearch && mSortByRelevanceCheckbox?.isChecked == true

        // 执行搜索
        val executor = DownloadListInfosExecutor(backList, searchKey,
            fuzzySearch, ignoreCase, enableChineseConversion)

        if (fuzzySearch && sortByRelevance) {
            executor.enableSortByRelevance(true)
        }

        // 同步执行搜索，获取结果
        val tempResults = executor.executeSearchingSync()

        if (!tempResults.isNullOrEmpty()) {
            // 使用Set来避免重复添加相同的下载项
            val existingGids = resultsCollection.map { it.gid }.toMutableSet()

            // 添加新找到的项（不重复的）
            for (info in tempResults) {
                if (info.gid !in existingGids) {
                    resultsCollection.add(info)
                    existingGids.add(info.gid)
                }
            }
            return true
        }
        return false
    }

    companion object {
        /**
         * 从标题中提取作者名称
         * 一般作者名称格式为 [AAAA(BBBB)] 中的 BBBB，或者是第一个 [CCCC] 中的 CCCC
         */
        @JvmStatic
        fun extractAuthorFromTitle(title: String?): String? {
            if (title.isNullOrEmpty()) return null

            // 找到第一个 []
            val bracketMatcher = Regex("\\[([^\\]]+)\\]").find(title) ?: return null
            val bracketContent = bracketMatcher.groupValues[1]

            // 在 [] 内容中查找 ()
            val parenMatcher = Regex("\\(([^)]+)\\)").find(bracketContent)
            return parenMatcher?.groupValues?.get(1) ?: bracketContent
        }
    }
}