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

package com.hippo.ehviewer.ui.scene.download.part;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Spinner;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.callBack.DownloadSearchCallback;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.sync.DownloadListInfosExecutor;
import com.hippo.ehviewer.ui.dialog.DownloadFilterDialog;
import com.hippo.ehviewer.widget.MyEasyRecyclerView;
import com.hippo.ehviewer.widget.SearchBar;
import com.hippo.util.DrawableManager;
import com.hippo.widget.ProgressView;
import com.hippo.widget.SearchBarMover;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 下载页搜索与筛选（本地超集：组合筛选、阅读进度筛选、模糊/大小写/简繁、
 * 按相关性排序、全标签分组折叠、搜索相同作者）。
 */
public class DownloadSearchController implements SearchBar.Helper, SearchBarMover.Helper,
        SearchBar.OnStateChangeListener {

    public interface Host {
        @Nullable
        Context getEHContext();

        String getSearchKey();

        void setSearchKey(String searchKey);

        boolean isSearching();

        void setSearching(boolean searching);

        @Nullable
        ProgressView getProgressView();

        @Nullable
        View getSearchProgressContainer();

        @Nullable
        MyEasyRecyclerView getRecyclerView();

        @Nullable
        List<DownloadInfo> getList();

        void setList(List<DownloadInfo> list);

        @Nullable
        List<DownloadInfo> getBackList();

        @Nullable
        DownloadManager getDownloadManager();

        @Nullable
        RecyclerView.Adapter getNotifyAdapter();

        @Nullable
        DownloadAdapter getOriginalAdapter();

        @Nullable
        Spinner getCategorySpinner();

        int getSelectedCategory();

        void setSelectedCategory(int category);

        @Nullable
        Map<Long, SpiderInfo> getSpiderInfoMap();

        long captureFirstVisibleGid();

        long getRestoreScrollGid();

        void setRestoreScrollGid(long gid);

        void setDoNotScroll(boolean doNotScroll);

        @Nullable
        MyPageChangeListener getMyPageChangeListener();

        void restoreScrollPositionIfNeeded();

        void updateTitle();

        void updateView();

        void updateForLabel();

        void updatePaginationIndicator();

        void queryUnreadSpiderInfo();

        DownloadSearchCallback getDownloadSearchCallback();
    }

    @Nullable
    private final Host mHost;

    private AlertDialog mSearchDialog;
    private SearchBar mSearchBar;
    @Nullable
    private SearchBarMover mSearchBarMover;
    private boolean mSearchMode = false;

    private CheckBox mFuzzySearchCheckbox;
    private CheckBox mIgnoreCaseCheckbox;
    private CheckBox mChineseConversionCheckbox;
    private CheckBox mSortByRelevanceCheckbox;
    private CheckBox mSearchAllLabelsCheckbox;

    // 组合筛选的选中过滤器状态
    private Set<Integer> mSelectedStatusFilters = new HashSet<>();
    private Set<Integer> mSelectedProgressFilters = new HashSet<>();
    // 标记组合筛选是否被用户主动应用（区分从Settings加载 vs 用户手动启用）
    private boolean mCombinedFilterActive = false;
    // 记录当前应用的过滤ID（状态/分类/排序等），用于编辑后重新应用
    private int mCurrentFilterId = -1;

    // 全标签搜索分组折叠状态
    private boolean mSearchAllLabelsMode = false;
    private Map<String, List<DownloadInfo>> mGroupedSearchResults;
    private final Set<String> mCollapsedLabels = new HashSet<>();
    private DownloadListInfosExecutor mSearchExecutor;

    public DownloadSearchController(@Nullable Host host) {
        mHost = host;
    }

    /*---------------
     Filter state
     ---------------*/

    public Set<Integer> getSelectedStatusFilters() {
        return mSelectedStatusFilters;
    }

    public void setSelectedStatusFilters(Set<Integer> filters) {
        mSelectedStatusFilters = filters;
    }

    public Set<Integer> getSelectedProgressFilters() {
        return mSelectedProgressFilters;
    }

    public void setSelectedProgressFilters(Set<Integer> filters) {
        mSelectedProgressFilters = filters;
    }

    public boolean isCombinedFilterActive() {
        return mCombinedFilterActive;
    }

    public void setCombinedFilterActive(boolean active) {
        mCombinedFilterActive = active;
    }

    public int getCurrentFilterId() {
        return mCurrentFilterId;
    }

    public void setCurrentFilterId(int id) {
        mCurrentFilterId = id;
    }

    public boolean isSearchAllLabelsMode() {
        return mSearchAllLabelsMode;
    }

    public void setSearchAllLabelsMode(boolean mode) {
        mSearchAllLabelsMode = mode;
    }

    public Map<String, List<DownloadInfo>> getGroupedSearchResults() {
        return mGroupedSearchResults;
    }

    public void setGroupedSearchResults(Map<String, List<DownloadInfo>> grouped) {
        mGroupedSearchResults = grouped;
    }

    public Set<String> getCollapsedLabels() {
        return mCollapsedLabels;
    }

    @Nullable
    public DownloadListInfosExecutor getSearchExecutor() {
        return mSearchExecutor;
    }

    public void setSearchExecutor(@Nullable DownloadListInfosExecutor executor) {
        mSearchExecutor = executor;
    }

    /**
     * "查找相同作者"入口的搜索选项：非模糊、忽略大小写、启用简繁转换。
     * 复选框可能尚未创建（搜索对话框未打开过），此时为 no-op，
     * searchForAuthorAndCollectResults 会按 null 复选框回退。
     */
    public void configureAuthorSearchOptions() {
        if (mFuzzySearchCheckbox != null) mFuzzySearchCheckbox.setChecked(false);
        // 启用不区分大小写，提高搜索效果
        if (mIgnoreCaseCheckbox != null) mIgnoreCaseCheckbox.setChecked(true);
        // 启用简繁体转换，提高中文作者名搜索效果
        if (mChineseConversionCheckbox != null) mChineseConversionCheckbox.setChecked(true);
    }

    @Nullable
    public SearchBar getSearchBar() {
        return mSearchBar;
    }

    public boolean isSearchMode() {
        return mSearchMode;
    }

    public void setSearchMode(boolean searchMode) {
        mSearchMode = searchMode;
    }

    public void destroySearchDialog() {
        if (mSearchDialog != null) {
            mSearchDialog.dismiss();
            mSearchDialog = null;
        }
    }

    /**
     * 从Settings中加载上次保存的组合筛选状态
     * 注意：仅加载保存的状态用于显示在筛选对话框中，不自动应用筛选
     */
    public void loadSavedFilterState() {
        // 加载状态过滤器
        String savedStatus = Settings.getDownloadFilterStatus();
        if (savedStatus != null && !savedStatus.isEmpty()) {
            mSelectedStatusFilters = new HashSet<>();
            try {
                for (String part : savedStatus.split(",")) {
                    int value = Integer.parseInt(part.trim());
                    mSelectedStatusFilters.add(value);
                }
            } catch (NumberFormatException e) {
                // 忽略解析错误
            }
        }

        // 加载进度过滤器
        String savedProgress = Settings.getDownloadFilterProgress();
        if (savedProgress != null && !savedProgress.isEmpty()) {
            mSelectedProgressFilters = new HashSet<>();
            try {
                for (String part : savedProgress.split(",")) {
                    int value = Integer.parseInt(part.trim());
                    mSelectedProgressFilters.add(value);
                }
            } catch (NumberFormatException e) {
                // 忽略解析错误
            }
        }

        // 不再自动应用筛选条件，每次打开页面时显示所有项目
        // 用户可以通过菜单手动选择筛选条件
        mCombinedFilterActive = false;
    }

    /**
     * 保存组合筛选状态到Settings
     */
    public void saveFilterState() {
        // 保存状态过滤器
        if (mSelectedStatusFilters != null && !mSelectedStatusFilters.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Integer value : mSelectedStatusFilters) {
                if (sb.length() > 0) sb.append(",");
                sb.append(value);
            }
            Settings.putDownloadFilterStatus(sb.toString());
        } else {
            Settings.putDownloadFilterStatus("");
        }

        // 保存进度过滤器
        if (mSelectedProgressFilters != null && !mSelectedProgressFilters.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Integer value : mSelectedProgressFilters) {
                if (sb.length() > 0) sb.append(",");
                sb.append(value);
            }
            Settings.putDownloadFilterProgress(sb.toString());
        } else {
            Settings.putDownloadFilterProgress("");
        }
    }

    public boolean isFilterActive() {
        if (mHost == null) {
            return false;
        }
        // 菜单筛选（状态/排序/分类/存储/阅读进度等，任何修改了 mList 的筛选）
        if (mHost.getList() != mHost.getBackList()) {
            return true;
        }
        // 组合筛选
        if (mCombinedFilterActive) {
            return true;
        }
        // 搜索状态
        if (mHost.isSearching() || (mHost.getSearchKey() != null && !mHost.getSearchKey().isEmpty())) {
            return true;
        }
        return false;
    }

    /**
     * 清除所有筛选条件，恢复到筛选前的完整列表
     */
    public void clearAllFilters() {
        if (mHost == null) {
            return;
        }
        // 清除分类筛选 ID
        mCurrentFilterId = -1;
        // 清除组合筛选
        mCombinedFilterActive = false;
        mSelectedStatusFilters.clear();
        mSelectedProgressFilters.clear();
        saveFilterState();
        // 清除搜索状态
        mHost.setSearching(false);
        mHost.setSearchKey(null);
        // 清除分组搜索状态
        mGroupedSearchResults = null;
        mCollapsedLabels.clear();
        DownloadAdapter originalAdapter = mHost.getOriginalAdapter();
        if (originalAdapter != null) {
            originalAdapter.clearGroupMode();
        }
        // 重置 Spinner 为"全部"
        Spinner categorySpinner = mHost.getCategorySpinner();
        if (categorySpinner != null) {
            categorySpinner.setSelection(0);
        }
        mHost.setSelectedCategory(EhUtils.ALL_CATEGORY);
        // 恢复完整列表
        mHost.setList(mHost.getBackList());
        RecyclerView.Adapter adapter = mHost.getNotifyAdapter();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        ProgressView progressView = mHost.getProgressView();
        if (progressView != null) {
            progressView.setVisibility(View.GONE);
        }
        MyEasyRecyclerView recyclerView = mHost.getRecyclerView();
        if (recyclerView != null) {
            recyclerView.setVisibility(View.VISIBLE);
        }
        mHost.updateTitle();
        mHost.updatePaginationIndicator();
        mHost.updateView();
        mHost.queryUnreadSpiderInfo();
    }

    /*---------------
     Filter apply
     ---------------*/

    public void filterByCategory() {
        if (mHost == null || mHost.getBackList() == null) {
            return;
        }
        List<DownloadInfo> backList = mHost.getBackList();
        List<DownloadInfo> list;
        if (mHost.getSelectedCategory() == EhUtils.ALL_CATEGORY) {
            list = new ArrayList<>(backList);
        } else {
            list = new ArrayList<>();
            for (DownloadInfo info : backList) {
                if (info.category == mHost.getSelectedCategory()) {
                    list.add(info);
                }
            }
        }
        mHost.setList(list);
        RecyclerView.Adapter adapter = mHost.getNotifyAdapter();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        mHost.updateTitle();
        mHost.updatePaginationIndicator();
        mHost.updateView();
        mHost.queryUnreadSpiderInfo();
    }

    public void filterByReadingProgress(int filterId) {
        if (mHost == null || mHost.getBackList() == null) {
            return;
        }
        List<DownloadInfo> backList = mHost.getBackList();
        Map<Long, SpiderInfo> spiderInfoMap = mHost.getSpiderInfoMap();
        List<DownloadInfo> list;

        if (filterId == R.id.progress_all) {
            list = new ArrayList<>(backList);
        } else {
            list = new ArrayList<>();
            for (DownloadInfo info : backList) {
                SpiderInfo spiderInfo = spiderInfoMap != null ? spiderInfoMap.get(info.gid) : null;
                int startPage = spiderInfo != null ? spiderInfo.startPage : 0;
                int pages = spiderInfo != null ? spiderInfo.pages : 0;

                boolean shouldAdd = false;
                switch (filterId) {
                    case R.id.progress_not_started:
                        shouldAdd = (spiderInfo == null || startPage == 0);
                        break;
                    case R.id.progress_in_progress:
                        shouldAdd = (startPage > 0 && pages > 0 && startPage < pages - 1);
                        break;
                    case R.id.progress_finished:
                        shouldAdd = (pages > 0 && startPage >= pages - 1);
                        break;
                }

                if (shouldAdd) {
                    list.add(info);
                }
            }
        }

        mHost.setList(list);
        RecyclerView.Adapter adapter = mHost.getNotifyAdapter();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        mHost.updateTitle();
        mHost.updatePaginationIndicator();
        mHost.updateView();
    }

    public void showCombinedFilterDialog() {
        if (mHost == null) {
            return;
        }
        Context context = mHost.getEHContext();
        if (context == null || mHost.getBackList() == null) {
            return;
        }
        DownloadFilterDialog dialog = new DownloadFilterDialog(
                context,
                mSelectedStatusFilters,
                mSelectedProgressFilters,
                (statusFilters, progressFilters) -> {
                    mSelectedStatusFilters = statusFilters;
                    mSelectedProgressFilters = progressFilters;
                    mCombinedFilterActive = !statusFilters.isEmpty() || !progressFilters.isEmpty();
                    if (mCombinedFilterActive) {
                        mCurrentFilterId = -1; // 组合筛选与分类筛选互斥
                    }
                    applyCombinedFilter();
                    // 保存筛选状态
                    saveFilterState();
                }
        );
        dialog.show();
    }

    public void applyCombinedFilter() {
        applyCombinedFilterWithScroll(true);
    }

    /**
     * 应用组合筛选，可选是否捕获滚动位置
     * @param captureScroll 是否捕获滚动位置
     */
    public void applyCombinedFilterWithScroll(boolean captureScroll) {
        if (mHost == null || mHost.getBackList() == null) {
            return;
        }

        if (captureScroll) {
            mHost.setRestoreScrollGid(mHost.captureFirstVisibleGid());
        }

        if (mSelectedStatusFilters.isEmpty() && mSelectedProgressFilters.isEmpty()) {
            mHost.setList(new ArrayList<>(mHost.getBackList()));
            RecyclerView.Adapter adapter = mHost.getNotifyAdapter();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            mHost.updateTitle();
            mHost.updatePaginationIndicator();
            mHost.updateView();
            // 恢复滚动位置
            if (mHost.getRestoreScrollGid() != -1) {
                MyEasyRecyclerView recyclerView = mHost.getRecyclerView();
                if (recyclerView != null) {
                    recyclerView.post(mHost::restoreScrollPositionIfNeeded);
                }
            }
        } else {
            ProgressView progressView = mHost.getProgressView();
            if (progressView != null) {
                progressView.setVisibility(View.VISIBLE);
            }
            MyEasyRecyclerView recyclerView = mHost.getRecyclerView();
            if (recyclerView != null) {
                recyclerView.setVisibility(View.GONE);
            }
            DownloadListInfosExecutor executor = new DownloadListInfosExecutor(
                    mHost.getBackList(), mHost.getDownloadManager(), mHost.getSpiderInfoMap());
            executor.setDownloadSearchingListener(mHost.getDownloadSearchCallback());
            executor.executeCombinedFilter(mSelectedStatusFilters, mSelectedProgressFilters);
        }
    }

    /*---------------
     Filter dispatch
     ---------------*/

    public void gotoFilterAndSort(int id) {
        // 记录当前过滤ID，便于后续（如编辑信息）重新应用
        mCurrentFilterId = id;
        mCombinedFilterActive = false; // 分类筛选与组合筛选互斥
        gotoFilterAndSortWithScroll(id, true);
    }

    public void gotoFilterAndSortWithScroll(int id, boolean captureScroll) {
        if (mHost == null) {
            return;
        }
        // 仅在需要时捕获滚动位置（新过滤操作时）
        // 当从 onReplace 调用时，滚动位置已保存，不需重新捕获
        if (captureScroll) {
            mHost.setRestoreScrollGid(mHost.captureFirstVisibleGid());
        }
        ProgressView progressView = mHost.getProgressView();
        if (progressView != null) {
            progressView.setVisibility(View.VISIBLE);
        }
        MyEasyRecyclerView recyclerView = mHost.getRecyclerView();
        if (recyclerView != null) {
            recyclerView.setVisibility(View.GONE);
        }

        DownloadListInfosExecutor executor = new DownloadListInfosExecutor(mHost.getBackList(), mHost.getDownloadManager());

        executor.setDownloadSearchingListener(mHost.getDownloadSearchCallback());

        executor.executeFilterAndSort(id);
    }

    /*---------------
     Search bar plumbing
     ---------------*/

    public void gotoSearch(Context context, SearchBar.Helper helper, SearchBarMover.Helper moverHelper) {
        if (mSearchDialog != null) {
            mSearchDialog.show();
            return;
        }
        android.view.LayoutInflater layoutInflater = android.view.LayoutInflater.from(context);

        android.graphics.drawable.Drawable drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_download);

        android.widget.LinearLayout linearLayout = (android.widget.LinearLayout) layoutInflater.inflate(R.layout.download_search_dialog, null);
        mSearchBar = linearLayout.findViewById(R.id.download_search_bar);
        mSearchBar.setHelper(helper != null ? helper : this);
        mSearchBar.setIsComeFromDownload(true);
        mSearchBar.setEditTextHint(R.string.download_search_hint);
        mSearchBar.setLeftDrawable(drawable);
        String searchKey = mHost != null ? mHost.getSearchKey() : null;
        mSearchBar.setText(searchKey);
        if (searchKey != null && !searchKey.isEmpty()) {
            mSearchBar.setTitle(searchKey);
            mSearchBar.cursorToEnd();
        } else {
            mSearchBar.setTitle(R.string.download_search_hint);
        }

        mSearchBar.setRightDrawable(DrawableManager.getVectorDrawable(context, R.drawable.v_magnify_x24));

        // 初始化复选框
        mFuzzySearchCheckbox = linearLayout.findViewById(R.id.fuzzy_search_checkbox);
        mIgnoreCaseCheckbox = linearLayout.findViewById(R.id.ignore_case_checkbox);
        mChineseConversionCheckbox = linearLayout.findViewById(R.id.chinese_conversion_checkbox);
        mSortByRelevanceCheckbox = linearLayout.findViewById(R.id.sort_by_relevance_checkbox);
        mSearchAllLabelsCheckbox = linearLayout.findViewById(R.id.search_all_labels_checkbox);

        // 初始化当前设置状态，从 Settings 加载所有搜索相关设置
        mFuzzySearchCheckbox.setChecked(Settings.getEnableFuzzySearch());
        mIgnoreCaseCheckbox.setChecked(Settings.getEnableIgnoreCase());
        mChineseConversionCheckbox.setChecked(Settings.getEnableChineseConversion());
        mSortByRelevanceCheckbox.setChecked(Settings.getEnableSortByRelevance());
        mSearchAllLabelsCheckbox.setChecked(false);

        // 设置"按相关性排序"复选框的启用状态，只有在模糊搜索启用时才可用
        updateSortByRelevanceState();

        // 添加模糊搜索复选框的点击监听器
        mFuzzySearchCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateSortByRelevanceState();
        });

        mSearchBarMover = new SearchBarMover(moverHelper != null ? moverHelper : this, mSearchBar);
        mSearchDialog = new AlertDialog.Builder(context)
                .setMessage(R.string.download_search_gallery)
                .setView(linearLayout)
                .setCancelable(true)
                .setOnDismissListener(this::onSearchDialogDismiss)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    if (mHost != null) {
                        mHost.setSearchKey(null);
                    }
                    mSearchBar.setText(null);
                    mSearchBar.setTitle(null);
                    mSearchBar.applySearch(true);
                    dialog.dismiss();
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    mSearchBar.applySearch(true);
                    dialog.dismiss();
                }).show();
    }

    public void onSearchDialogDismiss(DialogInterface dialog) {
        mSearchMode = false;
        // 清除复选框引用，避免对话框关闭后 startSearching() 读到旧的勾选状态
        mSearchAllLabelsCheckbox = null;
        // 重置 Settings，防止旧版残留值影响非对话框入口的搜索（如"查找相同作者"）
        Settings.putEnableSearchAllLabels(false);
    }

    /**
     * 更新"按相关性排序"复选框的启用状态
     * 只有在"模糊搜索"启用时才允许使用
     */
    public void updateSortByRelevanceState() {
        if (mSortByRelevanceCheckbox == null || mFuzzySearchCheckbox == null) {
            return;
        }

        boolean fuzzySearchEnabled = mFuzzySearchCheckbox.isChecked();
        mSortByRelevanceCheckbox.setEnabled(fuzzySearchEnabled);

        // 如果禁用了模糊搜索，自动取消勾选"按相关性排序"
        if (!fuzzySearchEnabled) {
            mSortByRelevanceCheckbox.setChecked(false);
        }
    }

    public void enterSearchMode(boolean animation) {
        if (mSearchMode || mSearchBar == null || mSearchBarMover == null) {
            return;
        }
        mSearchMode = true;
        mSearchBar.setState(SearchBar.STATE_SEARCH_LIST, animation);

        mSearchBarMover.returnSearchBarPosition(animation);

    }

    @Override
    public void onClickTitle() {
        if (!mSearchMode) {
            enterSearchMode(true);
        }
    }

    @Override
    public void onClickLeftIcon() {

    }

    @Override
    public void onClickRightIcon() {
        if (mSearchBar != null) {
            mSearchBar.applySearch(true);
        }
    }

    @Override
    public void onSearchEditTextClick() {

    }

    @Override
    public void onApplySearch(String query) {
        if (mHost == null) {
            return;
        }
        mHost.setSearchKey(query);
        if (mSearchBar != null) {
            mSearchBar.hideKeyBoard();
        }
        mHost.setSearching(true);
        startSearching();
    }

    public void startSearching() {
        if (mHost == null) {
            return;
        }
        // 显示搜索进度容器
        View searchProgressContainer = mHost.getSearchProgressContainer();
        if (searchProgressContainer != null) {
            searchProgressContainer.setVisibility(View.VISIBLE);
        }
        ProgressView progressView = mHost.getProgressView();
        if (progressView != null) {
            progressView.setVisibility(View.VISIBLE);
        }
        MyEasyRecyclerView recyclerView = mHost.getRecyclerView();
        if (recyclerView != null) {
            recyclerView.setVisibility(View.GONE);
        }

        if (mSearchMode) {
            mSearchMode = false;
            if (mSearchBar != null) {
                mSearchBar.setTitle(mHost.getSearchKey());
                mSearchBar.setState(SearchBar.STATE_NORMAL);
            }
        }

        // 添加null检查，避免空指针异常
        if (mSearchDialog != null) {
            mSearchDialog.dismiss();
        }

        // 只有当没有预先设置搜索结果时，才更新标签（避免覆盖已有的搜索结果）
        if (!mHost.isSearching()) {
            mHost.updateForLabel();
        }

        // 根据"在全部标签中搜索"复选框决定搜索范围（复选框为 null 时回退到 Settings）
        boolean searchAllLabels = mSearchAllLabelsCheckbox != null
                ? mSearchAllLabelsCheckbox.isChecked()
                : Settings.getEnableSearchAllLabels();
        List<DownloadInfo> searchTarget;
        if (searchAllLabels && mHost.getDownloadManager() != null) {
            searchTarget = mHost.getDownloadManager().getAllDownloadInfoList();
        } else {
            searchTarget = mHost.getList();
        }

        DownloadListInfosExecutor executor = new DownloadListInfosExecutor(searchTarget, mHost.getSearchKey());

        // 设置搜索选项（复选框为 null 时回退到 Settings）
        boolean fuzzySearch = mFuzzySearchCheckbox != null
                ? mFuzzySearchCheckbox.isChecked() : Settings.getEnableFuzzySearch();
        boolean ignoreCase = mIgnoreCaseCheckbox != null
                ? mIgnoreCaseCheckbox.isChecked() : Settings.getEnableIgnoreCase();
        boolean enableChineseConversion = mChineseConversionCheckbox != null
                ? mChineseConversionCheckbox.isChecked() : Settings.getEnableChineseConversion();
        boolean sortByRelevance = fuzzySearch && (mSortByRelevanceCheckbox != null
                ? mSortByRelevanceCheckbox.isChecked() : Settings.getEnableSortByRelevance());

        executor.setSearchOptions(fuzzySearch, ignoreCase, enableChineseConversion);
        if (sortByRelevance) {
            executor.enableSortByRelevance(true);
        }

        // 全部标签搜索时启用分组模式
        mSearchAllLabelsMode = searchAllLabels;
        if (searchAllLabels) {
            executor.setGroupByLabel(true);
            mCollapsedLabels.clear();
        }

        executor.setDownloadSearchingListener(mHost.getDownloadSearchCallback());
        mSearchExecutor = executor;

        executor.executeSearching();
    }

    @Override
    public void onSearchEditTextBackPressed() {
        if (mSearchMode) {
            mSearchMode = false;
        }
        if (mSearchBar != null) {
            mSearchBar.setState(SearchBar.STATE_NORMAL, true);
        }
    }

    @Override
    public void onStateChange(SearchBar searchBar, int newState, int oldState, boolean animation) {

    }

    @Override
    public boolean isValidView(RecyclerView recyclerView) {
        return false;
    }

    @Nullable
    @Override
    public RecyclerView getValidRecyclerView() {
        return mHost != null ? mHost.getRecyclerView() : null;
    }

    @Override
    public boolean forceShowSearchBar() {
        return false;
    }

    /*---------------
     Author search
     ---------------*/

    public boolean searchForAuthorAndCollectResults(List<DownloadInfo> resultsCollection) {
        if (mHost == null || mHost.getBackList() == null || mHost.getSearchKey() == null
                || mHost.getSearchKey().isEmpty()) {
            return false;
        }
        String searchKey = mHost.getSearchKey();

        // 创建一个临时结果集
        List<DownloadInfo> tempResults = new ArrayList<>();

        // 使用当前的搜索设置
        boolean fuzzySearch = mFuzzySearchCheckbox != null && mFuzzySearchCheckbox.isChecked();
        boolean ignoreCase = mIgnoreCaseCheckbox != null && mIgnoreCaseCheckbox.isChecked();
        boolean enableChineseConversion = mChineseConversionCheckbox != null && mChineseConversionCheckbox.isChecked();
        boolean sortByRelevance = fuzzySearch && mSortByRelevanceCheckbox != null && mSortByRelevanceCheckbox.isChecked();

        // 执行搜索
        DownloadListInfosExecutor executor = new DownloadListInfosExecutor(mHost.getBackList(), searchKey,
                fuzzySearch, ignoreCase, enableChineseConversion);

        if (fuzzySearch && sortByRelevance) {
            executor.enableSortByRelevance(true);
        }

        // 同步执行搜索，获取结果
        tempResults = executor.executeSearchingSync();

        if (tempResults != null && !tempResults.isEmpty()) {
            // 使用Set来避免重复添加相同的下载项
            java.util.Set<Long> existingGids = new java.util.HashSet<>();

            // 记录已存在的gid
            for (DownloadInfo info : resultsCollection) {
                existingGids.add(info.gid);
            }

            // 添加新找到的项（不重复的）
            for (DownloadInfo info : tempResults) {
                if (!existingGids.contains(info.gid)) {
                    resultsCollection.add(info);
                    existingGids.add(info.gid);
                }
            }

            return true;
        }

        return false;
    }

    /**
     * 从标题中提取作者名称
     * 一般作者名称格式为 [AAAA(BBBB)] 中的 BBBB，或者是第一个 [CCCC] 中的 CCCC
     * @param title 标题
     * @return 作者名称，如果没有找到则返回null
     */
    public static String extractAuthorFromTitle(String title) {
        if (title == null || title.isEmpty()) {
            return null;
        }

        // 找到第一个 []
        java.util.regex.Matcher bracketMatcher = java.util.regex.Pattern.compile("\\[([^\\]]+)\\]").matcher(title);
        if (!bracketMatcher.find()) return null;

        String bracketContent = bracketMatcher.group(1);

        // 在 [] 内容中查找 ()
        java.util.regex.Matcher parenMatcher = java.util.regex.Pattern.compile("\\(([^)]+)\\)").matcher(bracketContent);

        return parenMatcher.find() ? parenMatcher.group(1) : bracketContent;
    }
}
