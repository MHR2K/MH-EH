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
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.sync.DownloadListInfosExecutor;
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

        void setGroupedSearchResults(Map<String, List<DownloadInfo>> grouped);

        Set<String> getCollapsedLabels();
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

    // 全标签搜索分组折叠状态
    private boolean mSearchAllLabelsMode = false;
    private Map<String, List<DownloadInfo>> mGroupedSearchResults;
    private final Set<String> mCollapsedLabels = new HashSet<>();
    private DownloadListInfosExecutor mSearchExecutor;

    public DownloadSearchController(@Nullable Host host) {
        mHost = host;
    }

    /*---------------
     Search state accessors
     ---------------*/

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
