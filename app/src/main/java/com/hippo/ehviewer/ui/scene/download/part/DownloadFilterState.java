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
import android.view.View;
import android.widget.Spinner;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.sync.DownloadListInfosExecutor;
import com.hippo.ehviewer.ui.dialog.DownloadFilterDialog;
import com.hippo.ehviewer.widget.MyEasyRecyclerView;
import com.hippo.widget.ProgressView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 下载列表筛选状态与筛选应用（本地独有：组合筛选、阅读进度筛选、
 * 分类筛选互斥、筛选状态记忆、筛选后滚动位置恢复）。
 *
 * 与 {@link DownloadSearchController} 共用 {@link DownloadSearchController.Host}，
 * 两者由 DownloadsScene 同时构造。
 */
public class DownloadFilterState {

    @Nullable
    private final DownloadSearchController.Host mHost;

    // 组合筛选的选中过滤器状态
    private Set<Integer> mSelectedStatusFilters = new HashSet<>();
    private Set<Integer> mSelectedProgressFilters = new HashSet<>();
    // 标记组合筛选是否被用户主动应用（区分从Settings加载 vs 用户手动启用）
    private boolean mCombinedFilterActive = false;
    // 记录当前应用的过滤ID（状态/分类/排序等），用于编辑后重新应用
    private int mCurrentFilterId = -1;

    public DownloadFilterState(@Nullable DownloadSearchController.Host host) {
        mHost = host;
    }

    /*---------------
     State accessors
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

    /*---------------
     State persistence
     ---------------*/

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

    /*---------------
     Filter lifecycle
     ---------------*/

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
        mHost.setGroupedSearchResults(null);
        mHost.getCollapsedLabels().clear();
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
}
