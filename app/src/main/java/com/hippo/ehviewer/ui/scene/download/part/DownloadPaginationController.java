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

import static com.hippo.ehviewer.spider.SpiderInfo.getSpiderInfo;
import static com.hippo.ehviewer.ui.scene.download.DownloadsScene.LOCAL_GALLERY_INFO_CHANGE;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.view.View;

import androidx.activity.result.ActivityResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.sync.DownloadSpiderInfoExecutor;
import com.hippo.ehviewer.widget.MyEasyRecyclerView;
import com.sxj.paginationlib.PaginationIndicator;
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 下载列表分页与阅读进度。
 */
public class DownloadPaginationController {

    public interface Host {
        @Nullable
        List<DownloadInfo> getList();

        @Nullable
        RecyclerView.Adapter getNotifyAdapter();

        @Nullable
        MyEasyRecyclerView getRecyclerView();

        @Nullable
        AutoStaggeredGridLayoutManager getLayoutManager();

        /** 清除 Repository 内存缓存，强制重新加载最新阅读进度。 */
        void invalidateSpiderInfoCache(long gid);
    }

    @NonNull
    private final Host mHost;

    private int indexPage = 1;
    private int pageSize = 1;
    private boolean canPagination = true;
    private final int paginationSize = 500;
    //    private final int paginationSize = 5;
    private final int[] perPageCountChoices = {50, 100, 200, 300, 500};
    //    private final int[] perPageCountChoices = {1, 2, 3, 4, 5};

    private MyPageChangeListener myPageChangeListener;
    @Nullable
    private PaginationIndicator mPaginationIndicator;

    private final Map<Long, SpiderInfo> mSpiderInfoMap = new HashMap<>();

    private long mRestoreScrollGid = -1;
    private boolean doNotScroll = false;
    private boolean needInitPage = false;
    private boolean needInitPageSize = false;

    public DownloadPaginationController(@NonNull Host host) {
        mHost = host;
    }

    public int getIndexPage() {
        return indexPage;
    }

    public void setIndexPage(int indexPage) {
        this.indexPage = indexPage;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public boolean isCanPagination() {
        return canPagination;
    }

    public void setCanPagination(boolean canPagination) {
        this.canPagination = canPagination;
    }

    public int getPaginationSize() {
        return paginationSize;
    }

    public int[] getPerPageCountChoices() {
        return perPageCountChoices;
    }

    public Map<Long, SpiderInfo> getSpiderInfoMap() {
        return mSpiderInfoMap;
    }

    public boolean isNeedInitPage() {
        return needInitPage;
    }

    public void setNeedInitPage(boolean needInitPage) {
        this.needInitPage = needInitPage;
    }

    public void setNeedInitPageSize(boolean needInitPageSize) {
        this.needInitPageSize = needInitPageSize;
    }

    public boolean isDoNotScroll() {
        return doNotScroll;
    }

    public void setDoNotScroll(boolean doNotScroll) {
        this.doNotScroll = doNotScroll;
    }

    public long getRestoreScrollGid() {
        return mRestoreScrollGid;
    }

    public void setRestoreScrollGid(long restoreScrollGid) {
        mRestoreScrollGid = restoreScrollGid;
    }

    @Nullable
    public PaginationIndicator getPaginationIndicator() {
        return mPaginationIndicator;
    }

    public void setPaginationIndicator(@Nullable PaginationIndicator paginationIndicator) {
        mPaginationIndicator = paginationIndicator;
    }

    @Nullable
    public MyPageChangeListener getMyPageChangeListener() {
        return myPageChangeListener;
    }

    public void bindPageChangeListener(@Nullable RecyclerView.Adapter adapter,
                                       @Nullable MyEasyRecyclerView recyclerView) {
        myPageChangeListener = new MyPageChangeListener(indexPage, pageSize, needInitPage, doNotScroll, adapter, recyclerView);
        // 注意：本地策略是一次查询全列表的 SpiderInfo（queryUnreadSpiderInfo 不分页），
        // 因此翻页/改页大小不重新查询——与上游的每页查询策略不同，保持本地行为。
        myPageChangeListener.setPageChangeCallback(new MyPageChangeListener.PageChangeCallback() {
            @Override
            public void onPageChanged(int newIndexPage) {
                indexPage = newIndexPage;
            }

            @Override
            public void onPageSizeChanged(int newPageSize) {
                pageSize = newPageSize;
            }
        });
    }

    public int positionInList(int position) {
        List<DownloadInfo> list = mHost.getList();
        if (list != null && list.size() > paginationSize && canPagination) {
            return position + pageSize * (indexPage - 1);
        }
        return position;
    }

    public int listIndexInPage(int position) {
        List<DownloadInfo> list = mHost.getList();
        if (list != null && list.size() > paginationSize && canPagination) {
            return position % pageSize;
        }
        return position;
    }

    public void updatePaginationIndicator() {
        List<DownloadInfo> list = mHost.getList();
        if (mPaginationIndicator == null || list == null) {
            return;
        }
        if (list.size() < paginationSize || !canPagination) {
            mPaginationIndicator.setVisibility(View.GONE);
            return;
        }
        mPaginationIndicator.setVisibility(View.VISIBLE);
        needInitPageSize = true;
        mPaginationIndicator.initPaginationIndicator(pageSize, perPageCountChoices, list.size(), indexPage);
//        mPaginationIndicator.setTotalCount();
        mPaginationIndicator.setListener(myPageChangeListener);

        // 同步分页监听器的状态
        if (myPageChangeListener != null) {
            myPageChangeListener.setIndexPage(indexPage);
            myPageChangeListener.setPageSize(pageSize);
            myPageChangeListener.setNeedInitPage(needInitPage);
            myPageChangeListener.setDoNotScroll(doNotScroll);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updateReadProcess(ActivityResult result) {
        if (result.getResultCode() == LOCAL_GALLERY_INFO_CHANGE) {
            Intent data = result.getData();
            if (data != null) {
                GalleryInfo info = data.getParcelableExtra("info");

                // Check if this is an imported archive - skip SpiderInfo processing
                boolean isImportedArchive = false;
                if (info instanceof DownloadInfo downloadInfo) {
                    isImportedArchive = downloadInfo.archiveUri != null &&
                            downloadInfo.archiveUri.startsWith("content://");
                }

                if (!isImportedArchive && info != null) {
                    // Only process SpiderInfo for regular downloads, not imported archives
                    mSpiderInfoMap.remove(info.gid);
                    // 清除Repository的内存缓存，强制重新加载最新进度
                    mHost.invalidateSpiderInfoCache(info.gid);
                    SpiderInfo spiderInfo = getSpiderInfo(info);
                    if (spiderInfo != null) {
                        mSpiderInfoMap.put(info.gid, spiderInfo);
                    }
                }

                int position = -1;
                List<DownloadInfo> list = mHost.getList();
                RecyclerView.Adapter adapter = mHost.getNotifyAdapter();
                if (list == null || adapter == null || info == null) {
                    return;
                }
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).gid == info.gid) {
                        position = listIndexInPage(i);
                        break;
                    }
                }
                if (position != -1) {
                    adapter.notifyItemChanged(position);
                } else {
                    adapter.notifyDataSetChanged();
                }

            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void resetReadingProgressInUi() {
        for (SpiderInfo spiderInfo : mSpiderInfoMap.values()) {
            if (spiderInfo != null) {
                spiderInfo.startPage = 0;
            }
        }
        RecyclerView.Adapter adapter = mHost.getNotifyAdapter();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    public void queryUnreadSpiderInfo() {
        List<DownloadInfo> list = mHost.getList();
        if (list == null) {
            return;
        }
        List<DownloadInfo> requestList = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            DownloadInfo info = list.get(i);
            if (!mSpiderInfoMap.containsKey(info.gid) || mSpiderInfoMap.get(info.gid) == null) {
                requestList.add(info);
            }
        }
        if (requestList.isEmpty()) {
            return;
        }
        DownloadSpiderInfoExecutor executor = new DownloadSpiderInfoExecutor(requestList, this::spiderInfoResultCallBack);
        executor.execute();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void spiderInfoResultCallBack(Map<Long, SpiderInfo> resultMap) {
        mSpiderInfoMap.putAll(resultMap);
        RecyclerView.Adapter adapter = mHost.getNotifyAdapter();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void initPage(int position) {
        List<DownloadInfo> list = mHost.getList();
        if (list != null && list.size() > paginationSize && canPagination) {
            indexPage = position / pageSize + 1;
        }
        doNotScroll = true;
        if (mPaginationIndicator != null) {
            mPaginationIndicator.skip2Pos(indexPage);
        }
        int scrollTo = listIndexInPage(position);
        int scrollTarget = Math.max(0, scrollTo - 1);
        MyEasyRecyclerView recyclerView = mHost.getRecyclerView();
        if (recyclerView != null) {
            recyclerView.post(() -> recyclerView.scrollToPosition(scrollTarget));
        }
    }

    public int getPageSizePos(int pageSize) {
        int index = 0;
        for (int i = 0; i < perPageCountChoices.length; i++) {
            if (pageSize == perPageCountChoices[i]) {
                index = i;
                break;
            }
        }
        return index;
    }

    /**
     * 捕获当前第一个可见项的 gid，用于过滤/搜索后恢复滚动位置。
     */
    public long captureFirstVisibleGid() {
        MyEasyRecyclerView recyclerView = mHost.getRecyclerView();
        AutoStaggeredGridLayoutManager layoutManager = mHost.getLayoutManager();
        List<DownloadInfo> list = mHost.getList();
        if (recyclerView == null || layoutManager == null || list == null) {
            return -1;
        }
        try {
            int spanCount = layoutManager.getSpanCount();
            int[] firsts = new int[spanCount];
            layoutManager.findFirstVisibleItemPositions(firsts);
            int min = Arrays.stream(firsts).filter(p -> p >= 0).min().orElse(-1);
            if (min < 0) return -1;
            int listPos = positionInList(min);
            if (listPos >= 0 && listPos < list.size()) {
                return list.get(listPos).gid;
            }
        } catch (Throwable ignore) {
            // 容错处理，无法获取时返回 -1
        }
        return -1;
    }

    /**
     * 过滤/搜索完成后，根据之前记录的 gid 恢复到相邻位置，避免回到顶部。
     */
    public void restoreScrollPositionIfNeeded() {
        List<DownloadInfo> list = mHost.getList();
        MyEasyRecyclerView recyclerView = mHost.getRecyclerView();
        if (mRestoreScrollGid == -1 || list == null || recyclerView == null) {
            return;
        }
        int targetIndex = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).gid == mRestoreScrollGid) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex >= 0) {
            int adapterPos = listIndexInPage(targetIndex);
            recyclerView.scrollToPosition(adapterPos);
        }
        mRestoreScrollGid = -1;
    }
}
