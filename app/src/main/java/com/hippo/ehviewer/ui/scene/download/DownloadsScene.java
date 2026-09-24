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

package com.hippo.ehviewer.ui.scene.download;

import static com.hippo.ehviewer.spider.SpiderDen.getExistingGalleryDownloadDir;
import static com.hippo.ehviewer.spider.SpiderDen.getGalleryDownloadDir;
import static com.hippo.ehviewer.spider.SpiderInfo.getSpiderInfo;
import static com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter.DRAG_ENABLE;
import static com.hippo.util.FileUtils.getFileName;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;

import java.util.concurrent.atomic.AtomicBoolean;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.h6ah4i.android.widget.advrecyclerview.animator.DraggableItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.animator.GeneralItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager;
import com.hippo.android.resource.AttrResources;
import com.hippo.app.CheckBoxDialogBuilder;
import com.hippo.drawable.AddDeleteDrawable;
import com.hippo.drawerlayout.DrawerLayout;
import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.easyrecyclerview.FastScroller;
import com.hippo.easyrecyclerview.HandlerDrawable;
import com.hippo.easyrecyclerview.MarginItemDecoration;
import com.hippo.ehviewer.Analytics;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.callBack.DownloadSearchCallback;
import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.DownloadService;
import com.hippo.ehviewer.event.SomethingNeedRefresh;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.sync.DownloadListInfosExecutor;
import com.hippo.ehviewer.sync.DownloadSpiderInfoExecutor;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.annotation.ViewLifeCircle;
import com.hippo.ehviewer.ui.dialog.DownloadFilterDialog;
import com.hippo.ehviewer.ui.scene.ToolbarScene;
import com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter;
import com.hippo.ehviewer.ui.scene.download.part.DownloadArchiveImporter;
import com.hippo.ehviewer.ui.scene.download.part.DownloadBatchActions;
import com.hippo.ehviewer.ui.scene.download.part.DownloadChoiceListener;
import com.hippo.ehviewer.ui.scene.download.part.DownloadFilterState;
import com.hippo.ehviewer.ui.scene.download.part.DownloadGuideHelper;
import com.hippo.ehviewer.ui.scene.download.part.DownloadPaginationController;
import com.hippo.ehviewer.ui.scene.download.part.DownloadSearchController;
import com.hippo.ehviewer.ui.scene.download.part.MyPageChangeListener;
import com.hippo.ehviewer.widget.MyEasyRecyclerView;
import com.hippo.ehviewer.widget.SearchBar;
import com.hippo.lib.yorozuya.AssertUtils;
import com.hippo.lib.yorozuya.ObjectUtils;
import com.hippo.lib.yorozuya.ViewUtils;
import com.hippo.lib.yorozuya.collect.LongList;
import com.hippo.ripple.Ripple;
import com.hippo.unifile.UniFile;
import com.hippo.util.DrawableManager;
import com.hippo.util.IoThreadPoolExecutor;
import com.hippo.view.ViewTransition;
import com.hippo.widget.FabLayout;
import com.hippo.widget.ProgressView;
import com.hippo.widget.SearchBarMover;
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager;
import com.sxj.paginationlib.PaginationIndicator;
import com.hippo.ehviewer.util.CrashlyticsUtils;
import com.hippo.ehviewer.ui.scene.download.part.MyPageChangeListener;
import com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter;
import com.hippo.ehviewer.ui.scene.download.part.StorageDetector;
import com.hippo.ehviewer.ui.scene.download.part.StorageDetector.StorageLocation;

// 拖拽排序相关导入
import com.h6ah4i.android.widget.advrecyclerview.animator.DraggableItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.animator.GeneralItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager;
import android.graphics.drawable.NinePatchDrawable;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class DownloadsScene extends ToolbarScene
        implements DownloadManager.DownloadInfoListener, DownloadSearchCallback,
        EasyRecyclerView.OnItemClickListener,
        EasyRecyclerView.OnItemLongClickListener,
        FabLayout.OnClickFabListener, FabLayout.OnExpandListener, FastScroller.OnDragHandlerListener, SearchBar.Helper, SearchBarMover.Helper, SearchBar.OnStateChangeListener, DownloadAdapter.DownloadAdapterCallback, DownloadAdapter.GroupToggleCallback {

    private static final String TAG = DownloadsScene.class.getSimpleName();

    public static final String KEY_GID = "gid";

    public static final String KEY_ACTION = "action";
    private static final String KEY_LABEL = "label";

    public static final String ACTION_CLEAR_DOWNLOAD_SERVICE = "clear_download_service";

    public static final int LOCAL_GALLERY_INFO_CHANGE = 909;

    private static final long ANIMATE_TIME = 300L;

    @Nullable
    private AddDeleteDrawable mActionFabDrawable;


    /*---------------
         Whole life cycle
         ---------------*/
    @Nullable
    private DownloadManager mDownloadManager;
    @Nullable
    public String mLabel;
    @Nullable
    private List<DownloadInfo> mList;
    @Nullable
    private List<DownloadInfo> mBackList;

    /*---------------
     List pagination
     ---------------*/
    @NonNull
    private final DownloadPaginationController mPaginationController =
            new DownloadPaginationController(new DownloadPaginationController.Host() {
                @Nullable
                @Override
                public List<DownloadInfo> getList() {
                    return mList;
                }

                @Nullable
                @Override
                public RecyclerView.Adapter getNotifyAdapter() {
                    return mAdapter;
                }

                @Nullable
                @Override
                public MyEasyRecyclerView getRecyclerView() {
                    return mRecyclerView;
                }

                @Nullable
                @Override
                public AutoStaggeredGridLayoutManager getLayoutManager() {
                    return mLayoutManager;
                }

                @Override
                public void invalidateSpiderInfoCache(long gid) {
                    Activity activity = getActivity2();
                    if (activity != null) {
                        EhApplication.getSpiderInfoRepository(activity).invalidate(gid);
                    }
                }
            });

    @NonNull
    private final DownloadSearchController.Host mSearchHost =
            new DownloadSearchController.Host() {
                @Nullable
                @Override
                public Context getEHContext() {
                    return DownloadsScene.this.getEHContext();
                }

                @Override
                public String getSearchKey() {
                    return searchKey;
                }

                @Override
                public void setSearchKey(String key) {
                    searchKey = key;
                }

                @Override
                public boolean isSearching() {
                    return searching;
                }

                @Override
                public void setSearching(boolean value) {
                    searching = value;
                }

                @Nullable
                @Override
                public ProgressView getProgressView() {
                    return mProgressView;
                }

                @Nullable
                @Override
                public View getSearchProgressContainer() {
                    return mSearchProgressContainer;
                }

                @Nullable
                @Override
                public MyEasyRecyclerView getRecyclerView() {
                    return mRecyclerView;
                }

                @Nullable
                @Override
                public List<DownloadInfo> getList() {
                    return mList;
                }

                @Override
                public void setList(List<DownloadInfo> list) {
                    mList = list;
                }

                @Nullable
                @Override
                public List<DownloadInfo> getBackList() {
                    return mBackList;
                }

                @Nullable
                @Override
                public DownloadManager getDownloadManager() {
                    return mDownloadManager;
                }

                @Nullable
                @Override
                public RecyclerView.Adapter getNotifyAdapter() {
                    return mAdapter;
                }

                @Nullable
                @Override
                public DownloadAdapter getOriginalAdapter() {
                    return mOriginalAdapter;
                }

                @Nullable
                @Override
                public Spinner getCategorySpinner() {
                    return mCategorySpinner;
                }

                @Override
                public int getSelectedCategory() {
                    return mSelectedCategory;
                }

                @Override
                public void setSelectedCategory(int category) {
                    mSelectedCategory = category;
                }

                @Nullable
                @Override
                public Map<Long, SpiderInfo> getSpiderInfoMap() {
                    return mPaginationController.getSpiderInfoMap();
                }

                @Override
                public long captureFirstVisibleGid() {
                    return mPaginationController.captureFirstVisibleGid();
                }

                @Override
                public long getRestoreScrollGid() {
                    return mPaginationController.getRestoreScrollGid();
                }

                @Override
                public void setRestoreScrollGid(long gid) {
                    mPaginationController.setRestoreScrollGid(gid);
                }

                @Override
                public void setDoNotScroll(boolean doNotScroll) {
                    mPaginationController.setDoNotScroll(doNotScroll);
                }

                @Nullable
                @Override
                public MyPageChangeListener getMyPageChangeListener() {
                    return mPaginationController.getMyPageChangeListener();
                }

                @Override
                public void restoreScrollPositionIfNeeded() {
                    mPaginationController.restoreScrollPositionIfNeeded();
                }

                @Override
                public void updateTitle() {
                    DownloadsScene.this.updateTitle();
                }

                @Override
                public void updateView() {
                    DownloadsScene.this.updateView();
                }

                @Override
                public void updateForLabel() {
                    DownloadsScene.this.updateForLabel();
                }

                @Override
                public void updatePaginationIndicator() {
                    DownloadsScene.this.updatePaginationIndicator();
                }

                @Override
                public void queryUnreadSpiderInfo() {
                    DownloadsScene.this.queryUnreadSpiderInfo();
                }

                @Override
                public DownloadSearchCallback getDownloadSearchCallback() {
                    return DownloadsScene.this;
                }

                @Override
                public void setGroupedSearchResults(Map<String, List<DownloadInfo>> grouped) {
                    mSearchController.setGroupedSearchResults(grouped);
                }

                @Override
                public Set<String> getCollapsedLabels() {
                    return mSearchController.getCollapsedLabels();
                }
            };

    @NonNull
    private final DownloadSearchController mSearchController =
            new DownloadSearchController(mSearchHost);

    @NonNull
    private final DownloadFilterState mFilterState = new DownloadFilterState(mSearchHost);

    @NonNull
    private final DownloadBatchActions mBatchActions =
            new DownloadBatchActions(new DownloadBatchActions.Host() {
                @Nullable
                @Override
                public Context getEHContext() {
                    return DownloadsScene.this.getEHContext();
                }

                @Nullable
                @Override
                public Activity getActivity2() {
                    return DownloadsScene.this.getActivity2();
                }

                @Nullable
                @Override
                public MyEasyRecyclerView getRecyclerView() {
                    return mRecyclerView;
                }

                @Nullable
                @Override
                public List<DownloadInfo> getList() {
                    return mList;
                }

                @Nullable
                @Override
                public List<DownloadInfo> getBackList() {
                    return mBackList;
                }

                @Nullable
                @Override
                public DownloadManager getDownloadManager() {
                    return mDownloadManager;
                }

                @Nullable
                @Override
                public FabLayout getFabLayout() {
                    return mFabLayout;
                }

                @Nullable
                @Override
                public RecyclerViewDragDropManager getDragDropManager() {
                    return mDragDropManager;
                }

                @Override
                public Map<Long, SpiderInfo> getSpiderInfoMap() {
                    return mPaginationController.getSpiderInfoMap();
                }

                @Nullable
                @Override
                public DownloadInfo getDownloadInfoAtAdapterPosition(int adapterPosition) {
                    return DownloadsScene.this.getDownloadInfoAtAdapterPosition(adapterPosition);
                }

                @Override
                public Resources getResources() {
                    return DownloadsScene.this.getResources();
                }

                @Override
                public String getString(int resId) {
                    return DownloadsScene.this.getString(resId);
                }

                @Override
                public String getString(int resId, Object... formatArgs) {
                    return DownloadsScene.this.getString(resId, formatArgs);
                }

                @Override
                public void updateForLabel() {
                    DownloadsScene.this.updateForLabel();
                }

                @Override
                public void updateView() {
                    DownloadsScene.this.updateView();
                }

                @Override
                public void updateAdapter() {
                    DownloadsScene.this.updateAdapter();
                }

                @Override
                public void launchGalleryActivity(Intent intent) {
                    galleryActivityLauncher.launch(intent);
                }

                @Override
                public void onClickPrimaryFab(FabLayout view, FloatingActionButton fab) {
                    DownloadsScene.this.onClickPrimaryFab(view, fab);
                }

                @Override
                public String getLabel() {
                    return mLabel;
                }

                @Override
                public void setLabel(String label) {
                    mLabel = label;
                }
            });

    /*---------------
     View life cycle
     ---------------*/
    @Nullable
    private MyEasyRecyclerView mRecyclerView;
    @Nullable
    private ViewTransition mViewTransition;
    @Nullable
    private FabLayout mFabLayout;
    @Nullable
    private RecyclerView.Adapter mAdapter;
    @Nullable
    private DownloadAdapter mOriginalAdapter;
    @Nullable
    private AutoStaggeredGridLayoutManager mLayoutManager;

    // 拖拽管理器
    @Nullable
    private RecyclerViewDragDropManager mDragDropManager;

    @Nullable
    private DownloadGuideHelper mGuideHelper;

    private ProgressView mProgressView;

    private View mSearchProgressContainer;
    private TextView mSearchProgressText;

    private DownloadLabelDraw downloadLabelDraw;
    public String searchKey = null;

    private int mInitPosition = -1;

    public boolean searching = false;

    @Nullable
    private Spinner mCategorySpinner;
    private int mSelectedCategory = EhUtils.ALL_CATEGORY;

    @NonNull
    private final ActivityResultLauncher<Intent> galleryActivityLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::updateReadProcess
    );

    @NonNull
    private final DownloadArchiveImporter mArchiveImporter = new DownloadArchiveImporter(new DownloadArchiveImporter.Host() {
        @Nullable
        @Override
        public Context getEHContext() {
            return DownloadsScene.this.getEHContext();
        }

        @Override
        public String getString(int resId) {
            return DownloadsScene.this.getString(resId);
        }

        @Override
        public void runOnUiThread(Runnable runnable) {
            DownloadsScene.this.runOnUiThread(runnable);
        }

        @Override
        public void updateForLabel() {
            DownloadsScene.this.updateForLabel();
        }

        @Override
        public void updateView() {
            DownloadsScene.this.updateView();
        }

        @Nullable
        @Override
        public DownloadManager getDownloadManager() {
            return DownloadsScene.this.getDownloadManager();
        }
    });

    @NonNull
    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            mArchiveImporter::handleSelectedFile
    );

    @Override
    public int getNavCheckedItem() {
        return R.id.nav_downloads;
    }

    private boolean handleArguments(Bundle args) {
        if (null == args) {
            return false;
        }

        if (ACTION_CLEAR_DOWNLOAD_SERVICE.equals(args.getString(KEY_ACTION))) {
            DownloadService.Companion.clear();
        }

        long gid;
        if (null != mDownloadManager && -1L != (gid = args.getLong(KEY_GID, -1L))) {
            DownloadInfo info = mDownloadManager.getDownloadInfo(gid);
            if (null != info) {
                mLabel = info.getLabel();
                updateForLabel();
                updateView();

                // Get position
                if (null != mList) {
                    int position = mList.indexOf(info);
                    if (position >= 0 && null != mRecyclerView) {
                        initPage(position);
                    } else {
                        mInitPosition = position;
                    }
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void onNewArguments(@NonNull Bundle args) {
        handleArguments(args);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context context = getEHContext();
        AssertUtils.assertNotNull(context);
        mDownloadManager = EhApplication.getDownloadManager(context);
        mDownloadManager.addDownloadInfoListener(this);
        mPaginationController.setCanPagination(Settings.getDownloadPagination());

        // 保存分组搜索状态（onInit 中的 updateForLabel 会清除它们）
        Map<String, List<DownloadInfo>> savedGroupedResults = mSearchController.getGroupedSearchResults();
        Set<String> savedCollapsedLabels = new HashSet<>(mSearchController.getCollapsedLabels());
        String savedSearchKey = searchKey;

        if (savedInstanceState == null) {
            onInit();
        } else {
            onRestore(savedInstanceState);
        }

        // 恢复分组搜索状态
        if (savedGroupedResults != null && !savedGroupedResults.isEmpty()
                && savedSearchKey != null && !savedSearchKey.isEmpty()) {
            mSearchController.setGroupedSearchResults(savedGroupedResults);
            mSearchController.getCollapsedLabels().clear();
            mSearchController.getCollapsedLabels().addAll(savedCollapsedLabels);
            searchKey = savedSearchKey;
        }
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mDownloadManager != null) {
            mDownloadManager.removeDownloadInfoListener(this);
            mDownloadManager = null;
        } else {
            Log.e(TAG, "Can't removeDownloadInfoListener");
        }

        mList = null;
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        } else {
            updateAdapter();
        }

        restoreScrollPositionIfNeeded();
        mActionFabDrawable = null;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updateForLabel() {
        if (null == mDownloadManager) {
            return;
        }

        // 切换标签时清除分组搜索状态
        mSearchController.setGroupedSearchResults(null);
        mSearchController.getCollapsedLabels().clear();
        if (mOriginalAdapter != null) {
            mOriginalAdapter.clearGroupMode();
        }

        if (mLabel == null) {
            mList = mDownloadManager.getDefaultDownloadInfoList();
        } else {
            mList = mDownloadManager.getLabelDownloadInfoList(mLabel);
            if (mList == null) {
                mLabel = null;
                mList = mDownloadManager.getDefaultDownloadInfoList();
            }
        }

        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
        mBackList = mList;
        // 从本地存储恢复收藏数
        com.hippo.ehviewer.util.FavCountStore.applyTo(mBackList);
//        filterByCategory();
        updateTitle();
        updatePaginationIndicator();
        Settings.putRecentDownloadLabel(mLabel);
        queryUnreadSpiderInfo();
    }

    private void updatePaginationIndicator() {
        mPaginationController.updatePaginationIndicator();
    }

    @SuppressLint("StringFormatMatches")
    private void updateTitle() {
        try {
            // 全部标签搜索模式下，显示搜索结果总数量
            if (mSearchController.getGroupedSearchResults() != null && !mSearchController.getGroupedSearchResults().isEmpty()) {
                int totalCount = 0;
                for (List<DownloadInfo> items : mSearchController.getGroupedSearchResults().values()) {
                    totalCount += items.size();
                }
                setTitle(getString(R.string.search_all_labels) + " (" + totalCount + ")");
            } else {
                setTitle(getString(R.string.scene_download_title_new,
                        mLabel != null ? mLabel : getString(R.string.default_download_label_name),
                        Integer.toString(mList == null ? 0 : mList.size())));
            }
        } catch (Exception e) {
            e.printStackTrace();
            CrashlyticsUtils.record(e);
            setTitle(getString(R.string.scene_download_title_new,
                    mLabel != null ? mLabel : getString(R.string.default_download_label_name)));
        }
    }

    private void onInit() {
        if (!handleArguments(getArguments())) {
            mLabel = Settings.getRecentDownloadLabel();
            updateForLabel();
        }
        // 加载上次保存的组合筛选状态
        loadSavedFilterState();
    }

    /**
     * 从Settings中加载上次保存的组合筛选状态
     * 注意：仅加载保存的状态用于显示在筛选对话框中，不自动应用筛选
     */
    private void loadSavedFilterState() {
        mFilterState.loadSavedFilterState();
    }

    /**
     * 保存组合筛选状态到Settings
     */
    private void saveFilterState() {
        mFilterState.saveFilterState();
    }

    private void onRestore(@NonNull Bundle savedInstanceState) {
        mLabel = savedInstanceState.getString(KEY_LABEL);
        updateForLabel();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_LABEL, mLabel);
    }

    @Nullable
    @Override
    public View onCreateView3(LayoutInflater inflater,
                              @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_download, container, false);

        Context context = getEHContext();
        assert context != null;

        mCategorySpinner = (Spinner) ViewUtils.$$(view, R.id.category_spinner);
        // Initialize category spinner
        List<String> categoryList = new ArrayList<>();
        categoryList.add(getString(R.string.category_all)); // Add "All" option
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.DOUJINSHI)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.MANGA)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.ARTIST_CG)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.GAME_CG)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.WESTERN)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.NON_H)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.IMAGE_SET)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.COSPLAY)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.ASIAN_PORN)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.MISC)).toUpperCase(Locale.ROOT));
        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, categoryList);
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mCategorySpinner.setAdapter(categoryAdapter);
        mCategorySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int selectedCategory;
                switch (position) {
                    case 0:
                        selectedCategory = EhUtils.ALL_CATEGORY;
                        break;
                    case 1:
                        selectedCategory = EhConfig.DOUJINSHI;
                        break;
                    case 2:
                        selectedCategory = EhConfig.MANGA;
                        break;
                    case 3:
                        selectedCategory = EhConfig.ARTIST_CG;
                        break;
                    case 4:
                        selectedCategory = EhConfig.GAME_CG;
                        break;
                    case 5:
                        selectedCategory = EhConfig.WESTERN;
                        break;
                    case 6:
                        selectedCategory = EhConfig.NON_H;
                        break;
                    case 7:
                        selectedCategory = EhConfig.IMAGE_SET;
                        break;
                    case 8:
                        selectedCategory = EhConfig.COSPLAY;
                        break;
                    case 9:
                        selectedCategory = EhConfig.ASIAN_PORN;
                        break;
                    case 10:
                        selectedCategory = EhConfig.MISC;
                        break;
                    default:
                        selectedCategory = EhUtils.ALL_CATEGORY;
                        break;
                }
                if (selectedCategory != mSelectedCategory) {
                    mSelectedCategory = selectedCategory;
                    filterByCategory();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });
        // Set default selection
        mCategorySpinner.setSelection(0);

        mProgressView = (ProgressView) ViewUtils.$$(view, R.id.download_progress_view);
        mSearchProgressContainer = ViewUtils.$$(view, R.id.search_progress_container);
        mSearchProgressText = (TextView) ViewUtils.$$(view, R.id.search_progress_text);
        View content = ViewUtils.$$(view, R.id.content);
        mRecyclerView = (MyEasyRecyclerView) ViewUtils.$$(content, R.id.recycler_view);
        FastScroller fastScroller = (FastScroller) ViewUtils.$$(content, R.id.fast_scroller);
        mFabLayout = (FabLayout) ViewUtils.$$(view, R.id.fab_layout);
        TextView tip = (TextView) ViewUtils.$$(view, R.id.tip);
        if (mPaginationController.getPaginationIndicator() != null) {
            mPaginationController.setNeedInitPage(true);
        }
        PaginationIndicator indicator = (PaginationIndicator) ViewUtils.$$(view, R.id.indicator);
        mPaginationController.setPaginationIndicator(indicator);

        indicator.setPerPageCountChoices(mPaginationController.getPerPageCountChoices(),
                mPaginationController.getPageSizePos(mPaginationController.getPageSize()));

        mViewTransition = new ViewTransition(content, tip);

        Resources resources = context.getResources();

        Drawable drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_download);
        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        tip.setCompoundDrawables(null, drawable, null, null);
        // 初始化拖拽管理器
        mDragDropManager = new RecyclerViewDragDropManager();
        try {
            mDragDropManager.setDraggingItemShadowDrawable(
                    (NinePatchDrawable) context.getResources().getDrawable(R.drawable.shadow_8dp));
        } catch (Exception e) {
            // 忽略硬件位图相关错误
            android.util.Log.w("DownloadsScene", "Error setting drag shadow: " + e.getMessage());
        }


        mOriginalAdapter = new DownloadAdapter(this, this);
        mOriginalAdapter.setHasStableIds(true);
        mAdapter = mDragDropManager.createWrappedAdapter(mOriginalAdapter); // 包装适配器以支持拖拽
        mDragDropManager.setCheckCanDropEnabled(false);
        mRecyclerView.setAdapter(mAdapter);

        // 初始化分页监听器
        mPaginationController.bindPageChangeListener(mOriginalAdapter, mRecyclerView);
        mLayoutManager = new AutoStaggeredGridLayoutManager(0, StaggeredGridLayoutManager.VERTICAL);
        mLayoutManager.setColumnSize(resources.getDimensionPixelOffset(Settings.getDetailSizeResId()));
        mLayoutManager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_MIN_SIZE);

        // 设置拖拽动画器
        final GeneralItemAnimator animator = new DraggableItemAnimator();
        mRecyclerView.setItemAnimator(animator);

        mRecyclerView.setItemViewCacheSize(100);
        try {
            mRecyclerView.setDrawingCacheEnabled(true);
            mRecyclerView.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);
        } catch (Exception e) {
            // 忽略硬件位图相关错误
            android.util.Log.w("DownloadsScene", "Error setting drawing cache: " + e.getMessage());
        }
        mRecyclerView.setLayoutManager(mLayoutManager);
        mRecyclerView.setSelector(Ripple.generateRippleDrawable(context, !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme), new ColorDrawable(Color.TRANSPARENT)));
        mRecyclerView.setDrawSelectorOnTop(true);
        mRecyclerView.setClipToPadding(false);
        mRecyclerView.setOnItemClickListener(this);
        mRecyclerView.setOnItemLongClickListener(this);
        mRecyclerView.setChoiceMode(MyEasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM);
        mRecyclerView.setCustomCheckedListener(new DownloadChoiceListener(new DownloadChoiceListener.Host() {
            @Nullable
            @Override
            public MyEasyRecyclerView getRecyclerView() {
                return mRecyclerView;
            }

            @Nullable
            @Override
            public FabLayout getFabLayout() {
                return mFabLayout;
            }

            @Override
            public void setDrawerLockMode(int lockMode, int edgeGravity) {
                DownloadsScene.this.setDrawerLockMode(lockMode, edgeGravity);
            }

            @Override
            public MyEasyRecyclerView.OnItemLongClickListener getItemLongClickListener() {
                return DownloadsScene.this;
            }
        }));
//        mRecyclerView.setOnGenericMotionListener(this::onGenericMotion);
        // Cancel change animation
        RecyclerView.ItemAnimator itemAnimator = mRecyclerView.getItemAnimator();
        if (itemAnimator instanceof GeneralItemAnimator) {
            ((GeneralItemAnimator) itemAnimator).setSupportsChangeAnimations(false);
        }
        int interval = resources.getDimensionPixelOffset(R.dimen.gallery_list_interval);
        int paddingH = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_h);
        int paddingV = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_v);
        MarginItemDecoration decoration = new MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV);
        mRecyclerView.addItemDecoration(decoration);
        decoration.applyPaddings(mRecyclerView);

        // 将拖拽管理器附加到RecyclerView
        if (mDragDropManager != null) {
            try {
                mDragDropManager.attachRecyclerView(mRecyclerView);
            } catch (Exception e) {
                // 忽略硬件位图相关错误
                android.util.Log.w("DownloadsScene", "Error attaching drag manager: " + e.getMessage());
            }
        }

        if (mInitPosition >= 0) {
            initPage(mInitPosition);
            mInitPosition = -1;
        }

        fastScroller.attachToRecyclerView(mRecyclerView);
        HandlerDrawable handlerDrawable = new HandlerDrawable();
        handlerDrawable.setColor(AttrResources.getAttrColor(context, R.attr.widgetColorThemeAccent));
        fastScroller.setHandlerDrawable(handlerDrawable);
        fastScroller.setOnDragHandlerListener(this);

        mFabLayout.setExpanded(false, true);
        mFabLayout.setHidePrimaryFab(false);
        mFabLayout.setAutoCancel(false);
        mFabLayout.setOnClickFabListener(this);
        mFabLayout.setOnExpandListener(this);
        mActionFabDrawable = new AddDeleteDrawable(context, resources.getColor(R.color.primary_drawable_dark, null));
        mFabLayout.getPrimaryFab().setImageDrawable(mActionFabDrawable);
        FloatingActionButton fab = mFabLayout.getSecondaryFabAt(6);
        if (DRAG_ENABLE) {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_x24, context.getTheme()));
        } else {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_off_x24, context.getTheme()));
        }
        addAboveSnackView(mFabLayout);

        // 异步预计算存储位置，优化后续筛选和列表滚动性能
        StorageDetector.preloadAsync(context);

        updateView();

        mGuideHelper = new DownloadGuideHelper(new DownloadGuideHelper.Host() {
            @Nullable
            @Override
            public MainActivity getActivity2() {
                return DownloadsScene.this.getActivity2();
            }

            @Nullable
            @Override
            public MyEasyRecyclerView getRecyclerView() {
                return mRecyclerView;
            }

            @Nullable
            @Override
            public AutoStaggeredGridLayoutManager getLayoutManager() {
                return mLayoutManager;
            }

            @Override
            public void openDrawer(int gravity) {
                DownloadsScene.this.openDrawer(gravity);
            }
        });
        mGuideHelper.guide();
        updatePaginationIndicator();
        return view;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);

        // 从详情页返回时，如果有分组搜索结果，直接恢复（无需重新搜索）
        if (mSearchController.getGroupedSearchResults() != null && !mSearchController.getGroupedSearchResults().isEmpty()) {
            if (mOriginalAdapter == null) {
                updateAdapter();
            }
            if (mOriginalAdapter != null) {
                mOriginalAdapter.setGroupedData(mSearchController.getGroupedSearchResults(), mSearchController.getCollapsedLabels());
            }
            updateTitle();
        } else {
            updateTitle();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (null != mGuideHelper) {
            mGuideHelper.destroy();
            mGuideHelper = null;
        }
        if (null != mRecyclerView) {
            mRecyclerView.stopScroll();
            mRecyclerView = null;
        }
        if (null != mFabLayout) {
            removeAboveSnackView(mFabLayout);
            mFabLayout = null;
        }

        mRecyclerView = null;
        mViewTransition = null;
        mAdapter = null;
        mOriginalAdapter = null;
        mLayoutManager = null;
        mDragDropManager = null;
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onNavigationClick(View view) {
        onBackPressed();
    }

    @Override
    public int getMenuResId() {
        return R.menu.scene_download;
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public boolean onMenuItemClick(MenuItem item) {
        Activity activity = getActivity2();
        if (activity == null || mRecyclerView == null) return false;
        // 选择模式下仅允许执行转换操作
        if (mRecyclerView.isInCustomChoice() && item.getItemId() != R.id.action_convert_storage) {
            return false;
        }

        int id = item.getItemId();
        switch (id) {
            case R.id.action_start_all: {
                Context context = getEHContext();
                if (context == null) {
                    return false;
                }
                new AlertDialog.Builder(context)
                        .setMessage(R.string.download_start_all_message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            Intent intent = new Intent(activity, DownloadService.class);
                            intent.setAction(DownloadService.ACTION_START_ALL);
                            activity.startService(intent);
                        }).show();
                return true;
            }
            case R.id.action_stop_all: {
                Context context = getEHContext();
                if (context == null) {
                    return false;
                }
                new AlertDialog.Builder(context)
                        .setMessage(R.string.download_stop_all_message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            if (null != mDownloadManager) {
                                mDownloadManager.stopAllDownload();
                            }
                        }).show();
                return true;
            }
            case R.id.action_fail_all: {
                Context context = getEHContext();
                if (context == null) {
                    return false;
                }
                new AlertDialog.Builder(context)
                        .setMessage(R.string.download_fail_all_message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            if (null != mDownloadManager) {
                                mDownloadManager.failAllDownload();
                            }
                        }).show();
                return true;
            }
            case R.id.action_reset_reading_progress: {
                Context context = getEHContext();
                if (context == null) {
                    return false;
                }
                if (searching) {
                    Toast.makeText(context, R.string.download_searching, Toast.LENGTH_LONG).show();
                    return true;
                }
                new AlertDialog.Builder(context)
                        .setMessage(R.string.reset_reading_progress_message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            resetReadingProgressInUi();
                            if (mDownloadManager != null) {
                                Toast.makeText(context, R.string.reset_reading_progress_processing,
                                        Toast.LENGTH_SHORT).show();
                                mDownloadManager.resetAllReadingProgress(() ->
                                        Toast.makeText(context, R.string.reset_reading_progress_done,
                                                Toast.LENGTH_SHORT).show());
                            }
                        }).show();
                return true;
            }
            case R.id.search_download_gallery: {
                Context context = getEHContext();
                if (context == null) {
                    return false;
                }
                gotoSearch(context);
                return true;
            }
            case R.id.all:
            case R.id.sort_by_default:
            case R.id.download_done:
            case R.id.not_started:
            case R.id.waiting:
            case R.id.downloading:
            case R.id.failed:
            case R.id.sort_by_gallery_id_asc:
            case R.id.sort_by_gallery_id_desc:
            case R.id.sort_by_create_time_asc:
            case R.id.sort_by_create_time_desc:
            case R.id.sort_by_rating_asc:
            case R.id.sort_by_rating_desc:
            case R.id.sort_by_name_asc:
            case R.id.sort_by_name_desc:
            case R.id.sort_by_file_size_asc:
            case R.id.sort_by_file_size_desc:
            case R.id.sort_by_fav_count_asc:
            case R.id.sort_by_fav_count_desc:
            case R.id.all_kind:
            case R.id.misc:
            case R.id.doujinshi:
            case R.id.manga:
            case R.id.artist_cg:
            case R.id.game_cg:
            case R.id.image_set:
            case R.id.cosplay:
            case R.id.asian_porn:
            case R.id.non_h:
            case R.id.western:
            case R.id.unknown:
                gotoFilterAndSort(id);
                return true;
            case R.id.import_local_archive:
                mArchiveImporter.importLocalArchive(filePickerLauncher);
                return true;
//            case R.id.misc:
//            case R.id.doujinshi:
//            case R.id.manga:
//            case R.id.artist_cg:
//            case R.id.game_cg:
//            case R.id.image_set:
//            case R.id.cosplay:
//            case R.id.asian_porn:
//            case R.id.non_h:
//            case R.id.western:
//            case R.id.unknown:
//
//                return true;

            case R.id.storage_all: {
                // 还原标题与分页
                mList = mBackList;
                updateAdapter();
                mProgressView.setVisibility(View.GONE);
                if (mRecyclerView != null) mRecyclerView.setVisibility(View.VISIBLE);
                updateTitle();
                return true;
            }
            case R.id.storage_archives_only: {
                if (mBackList == null) return false;
                List<DownloadInfo> result = new ArrayList<>();
                for (DownloadInfo di : mBackList) {
                    UniFile dir = getGalleryDownloadDir(di);
                    if (dir != null && dir.isDirectory()) {
                        if (com.hippo.ehviewer.util.CbzUtils.findCbzFile(dir) != null) {
                            result.add(di);
                        }
                    }
                }
                mList = result;
                updateAdapter();
                mProgressView.setVisibility(View.GONE);
                if (mRecyclerView != null) mRecyclerView.setVisibility(View.VISIBLE);
                updateTitle();
                return true;
            }
            case R.id.storage_images_only: {
                if (mBackList == null) return false;
                List<DownloadInfo> result = new ArrayList<>();
                for (DownloadInfo di : mBackList) {
                    UniFile dir = getGalleryDownloadDir(di);
                    if (dir != null && dir.isDirectory()) {
                        if (com.hippo.ehviewer.util.CbzUtils.findCbzFile(dir) == null) {
                            result.add(di);
                        }
                    }
                }
                mList = result;
                updateAdapter();
                mProgressView.setVisibility(View.GONE);
                if (mRecyclerView != null) mRecyclerView.setVisibility(View.VISIBLE);
                updateTitle();
                return true;
            }
            case R.id.storage_smb_only: {
                if (mBackList == null) return false;
                List<DownloadInfo> result = new ArrayList<>();
                for (DownloadInfo di : mBackList) {
                    StorageLocation loc = StorageDetector.detectCached(di);
                    if (loc == StorageLocation.SMB || loc == StorageLocation.BOTH) {
                        result.add(di);
                    }
                }
                mList = result;
                updateAdapter();
                mProgressView.setVisibility(View.GONE);
                if (mRecyclerView != null) mRecyclerView.setVisibility(View.VISIBLE);
                updateTitle();
                return true;
            }
            case R.id.storage_local_only: {
                if (mBackList == null) return false;
                List<DownloadInfo> result = new ArrayList<>();
                for (DownloadInfo di : mBackList) {
                    StorageLocation loc = StorageDetector.detectCached(di);
                    if (loc == StorageLocation.LOCAL) {
                        result.add(di);
                    }
                }
                mList = result;
                updateAdapter();
                mProgressView.setVisibility(View.GONE);
                if (mRecyclerView != null) mRecyclerView.setVisibility(View.VISIBLE);
                updateTitle();
                return true;
            }
            case R.id.progress_all:
            case R.id.progress_not_started:
            case R.id.progress_in_progress:
            case R.id.progress_finished:
                filterByReadingProgress(id);
                return true;
            case R.id.combined_filter:
                showCombinedFilterDialog();
                return true;
            case R.id.action_convert_storage: {
                // 必须在选择模式
                if (!mRecyclerView.isInCustomChoice()) return false;
                SparseBooleanArray array = mRecyclerView.getCheckedItemPositions();
                List<DownloadInfo> targets = new ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    if (array.valueAt(i)) {
                        DownloadInfo di = getDownloadInfoAtAdapterPosition(array.keyAt(i));
                        if (di != null) targets.add(di);
                    }
                }
                if (targets.isEmpty()) return true;
                // 构造对话框选项
                Context ctx = getEHContext();
                if (ctx == null) return true;
                CharSequence[] options = new CharSequence[]{getString(R.string.convert_to_cbz), getString(R.string.convert_to_images)};
                new AlertDialog.Builder(ctx)
                        .setTitle(R.string.download_convert_storage)
                        .setItems(options, (d, which) -> {
                            if (which == 0) {
                                convertBatchAsync(targets, true);
                            } else {
                                convertBatchAsync(targets, false);
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return true;
            }
            case R.id.action_refresh_fav_count: {
                if (mBackList == null || mBackList.isEmpty()) return false;
                showRefreshFavCountDialog();
                return true;
            }

        }
        return false;
    }

    private void gotoSearch(Context context) {
        mSearchController.gotoSearch(context, mSearchController, mSearchController);
    }

    public void showRefreshFavCountDialog() {
        mBatchActions.showRefreshFavCountDialog();
    }

    public void convertBatchAsync(List<DownloadInfo> infos, boolean toCbz) {
        mBatchActions.convertBatchAsync(infos, toCbz);
    }
    private void onSearchDialogDismiss(DialogInterface dialog) {
        mSearchController.onSearchDialogDismiss(dialog);
    }

    /**
     * 更新"按相关性排序"复选框的启用状态
     * 只有在"模糊搜索"启用时才允许使用
     */
    private void updateSortByRelevanceState() {
        mSearchController.updateSortByRelevanceState();
    }

    private void enterSearchMode(boolean animation) {
        mSearchController.enterSearchMode(animation);
    }

    public void updateView() {
        if (mViewTransition != null) {
            if (mList == null || mList.size() == 0) {
                mViewTransition.showView(1);
            } else {
                mViewTransition.showView(0);
            }
        }
    }

    @Override
    public View onCreateDrawerView(LayoutInflater inflater,
                                   @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (downloadLabelDraw == null) {
            downloadLabelDraw = new DownloadLabelDraw(inflater, container, this);
        }

        return downloadLabelDraw.createView();
    }

    @Override
    public void onBackPressed() {
        if (mGuideHelper != null && mGuideHelper.isShowing()) {
            return;
        }

        if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
            mRecyclerView.outOfCustomChoiceMode();
            return;
        }

        // 如果当前处于筛选/搜索状态，先清除筛选，返回到筛选前的完整列表
        if (isFilterActive()) {
            clearAllFilters();
            return;
        }

        super.onBackPressed();
    }

    /**
     * 判断当前是否有任何筛选条件处于激活状态
     */
    private boolean isFilterActive() {
        return mFilterState.isFilterActive();
    }

    /**
     * 清除所有筛选条件，恢复到筛选前的完整列表
     */
    private void clearAllFilters() {
        mFilterState.clearAllFilters();
    }

    @Override
    public void onStartDragHandler() {
        // Lock right drawer
        setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT);
    }

    @Override
    public void onEndDragHandler() {
        // Restore right drawer
        if (null != mRecyclerView && !mRecyclerView.isInCustomChoice()) {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT);
        }
    }

    @Override
    public boolean onItemClick(EasyRecyclerView parent, View view, int position, long id) {
        Activity activity = getActivity2();
        MyEasyRecyclerView recyclerView = mRecyclerView;
        if (null == activity || null == recyclerView) {
            return false;
        }

        if (recyclerView.isInCustomChoice()) {
            recyclerView.toggleItemChecked(position);
            return true;
        } else {
            // group-mode-aware：分组模式从 adapter 的 mFlatList 取，普通模式走原有逻辑
            DownloadInfo downloadInfo = getDownloadInfoAtAdapterPosition(position);
            if (downloadInfo == null) {
                return false;
            }
            Intent intent = new Intent(activity, GalleryActivity.class);
            // Check if this is an imported archive
            if (downloadInfo.archiveUri != null && downloadInfo.archiveUri.startsWith("content://")) {
                // This is an imported archive, ensure URI permission is available
                Uri archiveUri = Uri.parse(downloadInfo.archiveUri);
                try {
                    // Test if we can access the URI
                    try (InputStream testStream = getEHContext().getContentResolver().openInputStream(archiveUri)) {
                        if (testStream == null) {
                            Toast.makeText(getEHContext(), R.string.archive_not_accessible, Toast.LENGTH_SHORT).show();
                            return true;
                        }
                    }
                } catch (SecurityException e) {
                    // Try to restore permission
                    try {
                        getEHContext().getContentResolver().takePersistableUriPermission(archiveUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ex) {
                        Toast.makeText(getEHContext(), R.string.archive_permission_lost, Toast.LENGTH_LONG).show();
                        Analytics.recordException(ex);
                        return true;
                    }
                } catch (Exception e) {
                    Toast.makeText(getEHContext(), R.string.archive_not_accessible, Toast.LENGTH_SHORT).show();
                    return true;
                }
                intent.setAction(Intent.ACTION_VIEW);
                intent.setData(archiveUri);
            } else {
                // This is a normal download, use ACTION_EH
                intent.setAction(GalleryActivity.ACTION_EH);
                intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, downloadInfo);
            }
//            startActivity(intent);
            galleryActivityLauncher.launch(intent);
            return true;
        }
    }

    @Override
    public boolean onItemLongClick(EasyRecyclerView parent, View view, int position, long id) {
        MyEasyRecyclerView recyclerView = mRecyclerView;
        if (recyclerView == null) {
            return false;
        }
        
        if (!recyclerView.isInCustomChoice()) {
            recyclerView.intoCustomChoiceMode();
        }
        recyclerView.toggleItemChecked(position);

        return true;
    }
    
    /**
     * 处理编辑按钮的长按菜单
     * @param position 项目位置
     * @param view 被长按的视图
     * @return 如果处理了菜单操作返回true，否则返回false
     */
    public boolean handleItemLongClickMenuOnEditButton(int position, View anchorView) {
        Context context = getEHContext();
        if (context == null || mList == null) {
            return false;
        }
        
        // group-mode-aware：分组模式从 adapter 的 mFlatList 取，普通模式走原有逻辑
        DownloadInfo info = getDownloadInfoAtAdapterPosition(position);
        if (info == null) {
            return false;
        }
        // posInList 用于排序功能（showMoveToPositionDialog），在分组模式下需要从 mList 中查找
        int posInList = mList.indexOf(info);
        
        // 创建弹出菜单，如果提供了锚点视图，则使用它；否则使用RecyclerView中的项目视图
        View menuAnchor = (anchorView != null) ? anchorView : mRecyclerView.getChildAt(position);
        android.widget.PopupMenu popupMenu = new android.widget.PopupMenu(context, menuAnchor);
        android.view.MenuInflater inflater = popupMenu.getMenuInflater();
        inflater.inflate(R.menu.download_item_menu, popupMenu.getMenu());
        // 分组模式下隐藏"移动到位置"（搜索结果中排序无意义）
        if (mSearchController.getGroupedSearchResults() != null && !mSearchController.getGroupedSearchResults().isEmpty()) {
            android.view.Menu menu = popupMenu.getMenu();
            android.view.MenuItem moveItem = menu.findItem(R.id.menu_move_to_position);
            if (moveItem != null) moveItem.setVisible(false);
        }
        
        // 设置菜单项点击事件
        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.menu_find_same_author) {
                // 尝试从英文标题和日文标题中提取作者名称
                String englishAuthor = extractAuthorFromTitle(info.title);
                String japaneseAuthor = extractAuthorFromTitle(info.titleJpn);
                
                if ((englishAuthor != null && !englishAuthor.isEmpty()) || 
                    (japaneseAuthor != null && !japaneseAuthor.isEmpty())) {
                    // 设置搜索选项：非模糊搜索，不区分大小写
                    mSearchController.configureAuthorSearchOptions();
                    
                    // 分别搜索英文和日文作者名，然后合并结果
                    List<DownloadInfo> combinedResults = new ArrayList<>();
                    boolean hasResults = false;
                    
                    // 先搜索英文作者名
                    if (englishAuthor != null && !englishAuthor.isEmpty()) {
                        searchKey = englishAuthor;
                        hasResults = searchForAuthorAndCollectResults(combinedResults);
                    }
                    
                    // 再搜索日文作者名（如果与英文不同）
                    if (japaneseAuthor != null && !japaneseAuthor.isEmpty() && 
                        (englishAuthor == null || !japaneseAuthor.equals(englishAuthor))) {
                        searchKey = japaneseAuthor;
                        boolean japaneseResults = searchForAuthorAndCollectResults(combinedResults);
                        hasResults = hasResults || japaneseResults;
                    }
                    
                    // 显示合并后的结果
                    if (hasResults) {
                        // 构建显示用的作者名字符串
                        StringBuilder searchBuilder = new StringBuilder();
                        if (englishAuthor != null && !englishAuthor.isEmpty()) {
                            searchBuilder.append(englishAuthor);
                        }
                        if (japaneseAuthor != null && !japaneseAuthor.isEmpty() && 
                            (englishAuthor == null || !japaneseAuthor.equals(englishAuthor))) {
                            if (searchBuilder.length() > 0) {
                                searchBuilder.append(" 或 "); // 使用更清晰的分隔符
                            }
                            searchBuilder.append(japaneseAuthor);
                        }
                        
                        String authorSearch = searchBuilder.toString();
                        // 保存最后一次搜索关键词
                        searchKey = authorSearch;
                        
                        // 设置搜索状态
                        searching = true;
                        
                        // 直接更新UI显示结果
                        mList = combinedResults;
                        
                        // 更新UI
                        mProgressView.setVisibility(View.GONE);
                        if (mRecyclerView != null) {
                            mRecyclerView.setVisibility(View.VISIBLE);
                        }
                        
                        // 更新适配器
                        updateAdapter();
                        
                        // 显示搜索成功提示
                        android.widget.Toast.makeText(context, 
                            getString(R.string.searching_author, authorSearch), 
                            Toast.LENGTH_SHORT).show();
                        
                        // 查询未读信息
                        queryUnreadSpiderInfo();
                        
                        // 搜索结束
                        searching = false;
                    }
                } else {
                    android.widget.Toast.makeText(context, 
                        R.string.author_not_found, 
                        Toast.LENGTH_SHORT).show();
                }
                return true;
            } else if (itemId == R.id.menu_move_to_position) {
                // 显示输入对话框获取目标 gid
                showMoveToPositionDialog(context, info, posInList);
                return true;
            } else if (itemId == R.id.menu_reset_reading_progress) {
                new AlertDialog.Builder(context)
                        .setMessage(R.string.reset_reading_progress_message_single)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            EhApplication.getSpiderInfoRepository(context).delete(info.gid, context);
                            mPaginationController.getSpiderInfoMap().remove(info.gid);
                            // 同时停止下载，避免漫画仍在下载队列中被自动下载
                            if (mDownloadManager != null) {
                                mDownloadManager.stopDownload(info.gid);
                            }
                            if (mAdapter != null) {
                                mAdapter.notifyDataSetChanged();
                            }
                        }).show();
                return true;
            }
            return false;
        });
        
        popupMenu.show();
        return true;
    }
    
    /**
     * 搜索特定作者并将结果添加到结果集合中
     * @param resultsCollection 用于存储搜索结果的集合
     * @return 是否找到了至少一个结果
     */
    private boolean searchForAuthorAndCollectResults(List<DownloadInfo> resultsCollection) {
        return mSearchController.searchForAuthorAndCollectResults(resultsCollection);
    }
    
    /**
     * 从标题中提取作者名称
     * 一般作者名称格式为 [AAAA(BBBB)] 中的 BBBB，或者是第一个 [CCCC] 中的 CCCC
     * @param title 标题
     * @return 作者名称，如果没有找到则返回null
     */
    public static String extractAuthorFromTitle(String title) {
        return DownloadSearchController.extractAuthorFromTitle(title);
    }

    @SuppressLint("RtlHardcoded")
    @Override
    public void onExpand(boolean expanded) {
        if (null == mActionFabDrawable) {
            return;
        }

        if (expanded) {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT);
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT);
            mActionFabDrawable.setDelete(ANIMATE_TIME);
        } else {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.LEFT);
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT);
            mActionFabDrawable.setAdd(ANIMATE_TIME);
        }
    }

    @Override
    public void onClickPrimaryFab(FabLayout view, FloatingActionButton fab) {
        if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
            mRecyclerView.outOfCustomChoiceMode();
            return;
        }
        if (mRecyclerView != null && !mRecyclerView.isInCustomChoice()) {
            mRecyclerView.intoCustomChoiceMode();
            return;
        }
        view.toggle();
    }

    @Override
    public void onClickSecondaryFab(FabLayout view, FloatingActionButton fab, int position) {
        mBatchActions.onClickSecondaryFab(view, fab, position);
    }

    @Override
    public void onAdd(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, int position) {
        if (mList != list) {
            return;
        }
        if (mAdapter != null) {
            mAdapter.notifyItemInserted(position);
        }
        if (downloadLabelDraw != null) {
            downloadLabelDraw.updateDownloadLabels();
        }
        updateView();
    }

    @Override
    public void onReplace(@NonNull DownloadInfo newInfo, @NonNull DownloadInfo oldInfo) {
        if (mList == null) {
            return;
        }
        // 先保存过滤ID和滚动位置，因为后面的 updateForLabel 会改变列表
        final int savedFilterId = mFilterState.getCurrentFilterId();
        // 保存组合筛选的状态（用于恢复）
        final Set<Integer> savedStatusFilters = new HashSet<>(mFilterState.getSelectedStatusFilters());
        final Set<Integer> savedProgressFilters = new HashSet<>(mFilterState.getSelectedProgressFilters());
        final long restoreScrollGid = captureFirstVisibleGid();

        updateForLabel();

        // 如果之前处于某种过滤（例如"已下载"），则重新应用过滤，保持过滤视图
        if (savedFilterId != -1) {
            // 直接设置保存的滚动位置，不在 gotoFilterAndSort 中重新捕获
            mPaginationController.setRestoreScrollGid(restoreScrollGid);
            // 设置标志避免滚动到顶部，保持当前位置
            mPaginationController.setDoNotScroll(true);
            if (mPaginationController.getMyPageChangeListener() != null) {
                mPaginationController.getMyPageChangeListener().setDoNotScroll(true);
            }
            // 使用 false 参数表示不需要重新捕获滚动位置
            gotoFilterAndSortWithScroll(savedFilterId, false);
            return; // 异步刷新，后续由回调处理
        }

        // 如果之前处于组合筛选状态，也需要恢复
        if (mFilterState.isCombinedFilterActive() && (!savedStatusFilters.isEmpty() || !savedProgressFilters.isEmpty())) {
            // 恢复组合筛选状态
            mFilterState.setSelectedStatusFilters(savedStatusFilters);
            mFilterState.setSelectedProgressFilters(savedProgressFilters);
            // 直接设置保存的滚动位置
            mPaginationController.setRestoreScrollGid(restoreScrollGid);
            // 设置标志避免滚动到顶部，保持当前位置
            mPaginationController.setDoNotScroll(true);
            if (mPaginationController.getMyPageChangeListener() != null) {
                mPaginationController.getMyPageChangeListener().setDoNotScroll(true);
            }
            // 重新应用组合筛选
            applyCombinedFilterWithScroll(false);
            return;
        }

        updateView();

        int index = mList.indexOf(newInfo);
        if (index >= 0 && mAdapter != null) {
//            mPaginationController.getSpiderInfoMap().put(info.gid,getSpiderInfo(info));
            mAdapter.notifyItemChanged(listIndexInPage(index));
        }
        List<DownloadInfo> infos = new ArrayList<>();
        infos.add(newInfo);
        DownloadSpiderInfoExecutor executor = new DownloadSpiderInfoExecutor(infos, mPaginationController::spiderInfoResultCallBack);
        executor.execute();
    }

    @Override
    public void onUpdate(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, LinkedList<DownloadInfo> mWaitList) {
        if (mList != list && !mList.contains(info)) {
            return;
        }
        int index = mList.indexOf(info);
        if (index >= 0 && mAdapter != null) {
            mAdapter.notifyItemChanged(listIndexInPage(index));
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onUpdateAll() {
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onReload() {
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
        updateView();
    }

    @Override
    public void onChange() {
        // 保存组合筛选的状态（用于恢复）
        final Set<Integer> savedStatusFilters = new HashSet<>(mFilterState.getSelectedStatusFilters());
        final Set<Integer> savedProgressFilters = new HashSet<>(mFilterState.getSelectedProgressFilters());
        final long restoreScrollGid = captureFirstVisibleGid();

        mLabel = null;
        updateForLabel();

        // 恢复组合筛选状态
        if (mFilterState.isCombinedFilterActive() && (!savedStatusFilters.isEmpty() || !savedProgressFilters.isEmpty())) {
            mFilterState.setSelectedStatusFilters(savedStatusFilters);
            mFilterState.setSelectedProgressFilters(savedProgressFilters);
            // 设置标志避免滚动到顶部，保持当前位置
            mPaginationController.setDoNotScroll(true);
            if (mPaginationController.getMyPageChangeListener() != null) {
                mPaginationController.getMyPageChangeListener().setDoNotScroll(true);
            }
            // 重新应用组合筛选
            applyCombinedFilterWithScroll(false);
        }

        updateView();
    }

    @Override
    public void onRenameLabel(String from, String to) {
        if (!ObjectUtils.equal(mLabel, from)) {
            return;
        }

        // 保存组合筛选的状态（用于恢复）
        final Set<Integer> savedStatusFilters = new HashSet<>(mFilterState.getSelectedStatusFilters());
        final Set<Integer> savedProgressFilters = new HashSet<>(mFilterState.getSelectedProgressFilters());
        final long restoreScrollGid = captureFirstVisibleGid();

        mLabel = to;
        updateForLabel();

        // 恢复组合筛选状态
        if (mFilterState.isCombinedFilterActive() && (!savedStatusFilters.isEmpty() || !savedProgressFilters.isEmpty())) {
            mFilterState.setSelectedStatusFilters(savedStatusFilters);
            mFilterState.setSelectedProgressFilters(savedProgressFilters);
            // 设置标志避免滚动到顶部，保持当前位置
            mPaginationController.setDoNotScroll(true);
            if (mPaginationController.getMyPageChangeListener() != null) {
                mPaginationController.getMyPageChangeListener().setDoNotScroll(true);
            }
            // 重新应用组合筛选
            applyCombinedFilterWithScroll(false);
        }

        updateView();
    }

    @Override
    public void onRemove(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, int position) {
        if (mList != list) {
            return;
        }
        if (mAdapter != null) {
            mAdapter.notifyItemRemoved(listIndexInPage(position));
        }
        updateView();
    }

    @Override
    public void onUpdateLabels() {
        // TODO
    }

    @Nullable
    public DownloadManager getMDownloadManager() {
        return mDownloadManager;
    }

    // DownloadAdapterCallback 接口实现
    @Override
    public int getIndexPage() {
        return mPaginationController.getIndexPage();
    }

    @Override
    public int getPageSize() {
        return mPaginationController.getPageSize();
    }

    @Override
    public int getPaginationSize() {
        return mPaginationController.getPaginationSize();
    }

    @Override
    public boolean isCanPagination() {
        return mPaginationController.isCanPagination();
    }

    @Override
    public int positionInList(int position) {
        return mPaginationController.positionInList(position);
    }

    /**
     * 根据 adapter 位置获取 DownloadInfo（分组模式感知）。
     * 分组模式下从 adapter 的 mFlatList 中取，跳过头部；
     * 普通模式下走原有的 positionInList 逻辑。
     */
    @Nullable
    private DownloadInfo getDownloadInfoAtAdapterPosition(int adapterPosition) {
        if (mOriginalAdapter != null) {
            return mOriginalAdapter.getDownloadInfoAtAdapterPosition(adapterPosition);
        }
        // fallback：普通模式
        List<DownloadInfo> list = mList;
        if (list == null) return null;
        int pos = positionInList(adapterPosition);
        return (pos >= 0 && pos < list.size()) ? list.get(pos) : null;
    }

    @Override
    public int listIndexInPage(int position) {
        return mPaginationController.listIndexInPage(position);
    }

    @Override
    public List<DownloadInfo> getList() {
        return mList;
    }

    @Override
    public Map<Long, SpiderInfo> getSpiderInfoMap() {
        return mPaginationController.getSpiderInfoMap();
    }

    @Override
    public DownloadManager getDownloadManager() {
        return mDownloadManager;
    }

    @Override
    public MyEasyRecyclerView getRecyclerView() {
        return mRecyclerView;
    }

    @Override
    public void onSpiderInfoFromSmb(long gid, SpiderInfo spiderInfo) {
        new Handler(Looper.getMainLooper()).post(() -> {
            SpiderInfo existing = mPaginationController.getSpiderInfoMap().get(gid);
            if (existing != null && existing.startPage == spiderInfo.startPage
                    && existing.pages == spiderInfo.pages) {
                return; // 数据未变化，跳过刷新
            }
            mPaginationController.getSpiderInfoMap().put(gid, spiderInfo);
            // 持久化到 Repository，后续查询可直接命中缓存
            Activity activity = getActivity2();
            if (activity != null) {
                EhApplication.getSpiderInfoRepository(activity).save(spiderInfo, "SMB");
            }
            if (mAdapter != null) {
                mAdapter.notifyDataSetChanged();
            }
        });
    }



    public void onClickTitle() {
        mSearchController.onClickTitle();
    }

    @Override
    public void onClickLeftIcon() {
        mSearchController.onClickLeftIcon();
    }

    @Override
    public void onClickRightIcon() {
        mSearchController.onClickRightIcon();
    }

    @Override
    public void onSearchEditTextClick() {
        mSearchController.onSearchEditTextClick();
    }


    @Override
    public void onApplySearch(String query) {
        mSearchController.onApplySearch(query);
    }

    protected void startSearching() {
        mSearchController.startSearching();
    }

    private void gotoFilterAndSort(int id) {
        mFilterState.gotoFilterAndSort(id);
    }

    private void gotoFilterAndSortWithScroll(int id, boolean captureScroll) {
        mFilterState.gotoFilterAndSortWithScroll(id, captureScroll);
    }

    private void updateAdapter() {
        // 检查 Fragment 是否已附加，如果未附加则延迟创建适配器
        if (!isAdded()) {
            return;
        }
        mOriginalAdapter = new DownloadAdapter(this, this);
        mOriginalAdapter.setHasStableIds(true);
        // 避免重复创建包装适配器，直接使用原始适配器
        mAdapter = mOriginalAdapter;
        // 恢复分组搜索状态（从详情页返回等场景）
        if (mSearchController.getGroupedSearchResults() != null && !mSearchController.getGroupedSearchResults().isEmpty()) {
            mOriginalAdapter.setGroupedData(mSearchController.getGroupedSearchResults(), mSearchController.getCollapsedLabels());
        }
        if (mRecyclerView != null) {
            mRecyclerView.setAdapter(mAdapter);
        }
    }

    /**
     * 捕获当前第一个可见项的 gid，用于过滤/搜索后恢复滚动位置。
     */
    private long captureFirstVisibleGid() {
        return mPaginationController.captureFirstVisibleGid();
    }

    /**
     * 过滤/搜索完成后，根据之前记录的 gid 恢复到相邻位置，避免回到顶部。
     */
    private void restoreScrollPositionIfNeeded() {
        mPaginationController.restoreScrollPositionIfNeeded();
    }

    @Override
    public void onSearchEditTextBackPressed() {
        mSearchController.onSearchEditTextBackPressed();
    }

    @Override
    public void onStateChange(SearchBar searchBar, int newState, int oldState, boolean animation) {
        mSearchController.onStateChange(searchBar, newState, oldState, animation);
    }

    @Override
    public boolean isValidView(RecyclerView recyclerView) {
        return mSearchController.isValidView(recyclerView);
    }

    @Nullable
    @Override
    public RecyclerView getValidRecyclerView() {
        return mSearchController.getValidRecyclerView();
    }

    @Override
    public boolean forceShowSearchBar() {
        return mSearchController.forceShowSearchBar();
    }

    @Override
    public void onSearchProgress(int scanned, int total) {
        if (!isAdded()) return;
        if (mSearchProgressText != null) {
            mSearchProgressText.setText(getString(R.string.search_progress_format, scanned, total));
            mSearchProgressText.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onDownloadSearchSuccess(List<DownloadInfo> list) {
        // 检查 Fragment 是否已附加，如果未附加则忽略回调
        if (!isAdded()) {
            return;
        }

        // 隐藏搜索进度
        if (mSearchProgressContainer != null) {
            mSearchProgressContainer.setVisibility(View.GONE);
        }
        mProgressView.setVisibility(View.GONE);
        if (mSearchProgressText != null) {
            mSearchProgressText.setVisibility(View.GONE);
        }

        // 处理全部标签搜索的分组结果
        if (mSearchController.isSearchAllLabelsMode() && mSearchController.getSearchExecutor() != null) {
            mSearchController.setGroupedSearchResults(mSearchController.getSearchExecutor().getGroupedResults());
            if (mSearchController.getGroupedSearchResults() != null && !mSearchController.getGroupedSearchResults().isEmpty()) {
                mList = list;
                // 确保适配器已创建，然后设置分组数据
                if (mOriginalAdapter == null) {
                    updateAdapter();
                }
                if (mOriginalAdapter != null) {
                    mOriginalAdapter.setGroupedData(mSearchController.getGroupedSearchResults(), mSearchController.getCollapsedLabels());
                }
            } else {
                // 没有分组结果，回退到普通模式
                mList = list;
                if (mAdapter != null) {
                    if (mOriginalAdapter != null) {
                        mOriginalAdapter.clearGroupMode();
                    }
                    mAdapter.notifyDataSetChanged();
                } else {
                    updateAdapter();
                }
            }
            mSearchController.setSearchExecutor(null);
        } else {
            // 普通搜索模式
            mList = list;
            if (mOriginalAdapter != null) {
                mOriginalAdapter.clearGroupMode();
            }
            if (mAdapter != null) {
                mAdapter.notifyDataSetChanged();
            } else {
                updateAdapter();
            }
        }

        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.VISIBLE);
        }
        // 在 UI 更新后延迟恢复滚动位置，避免被布局计算覆盖
        mRecyclerView.post(this::restoreScrollPositionIfNeeded);
        // 更新标题和分页指示器
        updateTitle();
        updatePaginationIndicator();
        searching = false;
        mSearchController.setSearchAllLabelsMode(false);
        queryUnreadSpiderInfo();
    }

    @Override
    public void onToggleLabel(String label) {
        if (mOriginalAdapter != null) {
            mOriginalAdapter.toggleLabel(label);
        }
    }

    @Override
    public void onDownloadListHandleSuccess(List<DownloadInfo> list) {
        // 检查 Fragment 是否已附加，如果未附加则忽略回调
        if (!isAdded()) {
            return;
        }
        mList = list;
        // 避免重新设置 Adapter 导致滚动位置回到顶部
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        } else {
            updateAdapter();
        }
        if (mSearchProgressContainer != null) {
            mSearchProgressContainer.setVisibility(View.GONE);
        }
        mProgressView.setVisibility(View.GONE);
        if (mSearchProgressText != null) {
            mSearchProgressText.setVisibility(View.GONE);
        }
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.VISIBLE);
        }
        // 在 UI 更新后延迟恢复滚动位置，避免被布局计算覆盖
        mRecyclerView.post(this::restoreScrollPositionIfNeeded);
        queryUnreadSpiderInfo();
    }

    @Override
    public void onDownloadSearchFailed(List<DownloadInfo> list) {
        Toast.makeText(getEHContext(), R.string.download_searching_failed, Toast.LENGTH_LONG).show();
        mList = list;
        // 避免重新设置 Adapter 导致滚动位置回到顶部
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        } else {
            updateAdapter();
        }
        mProgressView.setVisibility(View.GONE);
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.VISIBLE);
        }
        // 在 UI 更新后延迟恢复滚动位置，避免被布局计算覆盖
        mRecyclerView.post(this::restoreScrollPositionIfNeeded);
        searching = false;
        queryUnreadSpiderInfo();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void updateReadProcess(ActivityResult result) {
        mPaginationController.updateReadProcess(result);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void resetReadingProgressInUi() {
        mPaginationController.resetReadingProgressInUi();
    }

    private void queryUnreadSpiderInfo() {
        mPaginationController.queryUnreadSpiderInfo();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void updateDownloadLabels(SomethingNeedRefresh somethingNeedRefresh) {
        if (somethingNeedRefresh.isDownloadLabelDrawNeed()) {
            downloadLabelDraw.updateDownloadLabels();
        }
    }


    @SuppressLint("NotifyDataSetChanged")
    private void initPage(int position) {
        mPaginationController.initPage(position);
    }


    private int getPageSizePos(int pageSize) {
        return mPaginationController.getPageSizePos(pageSize);
    }

    public void runOnUiThread(Runnable runnable) {
        Activity activity = getActivity2();
        if (activity != null) {
            activity.runOnUiThread(runnable);
        }
    }


//    }

    private void filterByCategory() {
        mFilterState.filterByCategory();
    }

    private void filterByReadingProgress(int filterId) {
        mFilterState.filterByReadingProgress(filterId);
    }

    private void showCombinedFilterDialog() {
        mFilterState.showCombinedFilterDialog();
    }

    private void applyCombinedFilter() {
        mFilterState.applyCombinedFilter();
    }

    /**
     * 应用组合筛选，可选是否捕获滚动位置
     * @param captureScroll 是否捕获滚动位置
     */
    private void applyCombinedFilterWithScroll(boolean captureScroll) {
        mFilterState.applyCombinedFilterWithScroll(captureScroll);
    }

    public void showMoveToPositionDialog(Context context, DownloadInfo sourceInfo, int sourcePosition) {
        mBatchActions.showMoveToPositionDialog(context, sourceInfo, sourcePosition);
    }
}
