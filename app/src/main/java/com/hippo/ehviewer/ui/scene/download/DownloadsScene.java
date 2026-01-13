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

import static com.hippo.ehviewer.spider.SpiderDen.getGalleryDownloadDir;
import static com.hippo.ehviewer.spider.SpiderInfo.getSpiderInfo;
import static com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter.DRAG_ENABLE;
import static com.hippo.util.FileUtils.getFileName;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
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

import com.github.amlcurran.showcaseview.ShowcaseView;
import com.github.amlcurran.showcaseview.SimpleShowcaseEventListener;
import com.github.amlcurran.showcaseview.targets.PointTarget;
import com.github.amlcurran.showcaseview.targets.ViewTarget;
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
import com.hippo.ehviewer.ui.scene.ToolbarScene;
import com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter;
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
        FabLayout.OnClickFabListener, FabLayout.OnExpandListener, FastScroller.OnDragHandlerListener, SearchBar.Helper, SearchBarMover.Helper, SearchBar.OnStateChangeListener, DownloadAdapter.DownloadAdapterCallback {

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
    // 记录当前应用的过滤ID（状态/分类/排序等），用于编辑后重新应用
    private int mCurrentFilterId = -1;
    // 记录过滤/搜索前的锚点 gid，用于恢复滚动位置
    private long mRestoreScrollGid = -1;

    /*---------------
     List pagination
     ---------------*/
    private int indexPage = 1;
    private int pageSize = 1;
    private boolean canPagination = true;
    private final int paginationSize = 500;
    //    private final int paginationSize = 5;
    private final int[] perPageCountChoices = {50, 100, 200, 300, 500};
//    private final int[] perPageCountChoices = {1, 2, 3, 4, 5};

    private MyPageChangeListener myPageChangeListener;

    private final Map<Long, SpiderInfo> mSpiderInfoMap = new HashMap<>();

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

    private ShowcaseView mShowcaseView;

    private ProgressView mProgressView;

    private AlertDialog mSearchDialog;
    private SearchBar mSearchBar;
    private CheckBox mFuzzySearchCheckbox;
    private CheckBox mIgnoreCaseCheckbox;
    private CheckBox mChineseConversionCheckbox;
    private CheckBox mSortByRelevanceCheckbox;
    @Nullable
    private PaginationIndicator mPaginationIndicator;

    private DownloadLabelDraw downloadLabelDraw;
    @Nullable
    @ViewLifeCircle
    private SearchBarMover mSearchBarMover;
    private boolean mSearchMode = false;
    public String searchKey = null;

    private int mInitPosition = -1;

    public boolean searching = false;
    private boolean doNotScroll = false;

    private boolean needInitPage = false;
    private boolean needInitPageSize = false;

    @Nullable
    private Spinner mCategorySpinner;
    private int mSelectedCategory = EhUtils.ALL_CATEGORY;

    @NonNull
    private final ActivityResultLauncher<Intent> galleryActivityLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::updateReadProcess
    );

    @NonNull
    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::handleSelectedFile
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
        canPagination = Settings.getDownloadPagination();
        if (savedInstanceState == null) {
            onInit();
        } else {
            onRestore(savedInstanceState);
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
//        filterByCategory();
        updateTitle();
        updatePaginationIndicator();
        Settings.putRecentDownloadLabel(mLabel);
        queryUnreadSpiderInfo();
    }

    private void updatePaginationIndicator() {
        if (mPaginationIndicator == null || mList == null) {
            return;
        }
        if (mList.size() < paginationSize || !canPagination) {
            mPaginationIndicator.setVisibility(View.GONE);
            return;
        }
        mPaginationIndicator.setVisibility(View.VISIBLE);
        needInitPageSize = true;
        mPaginationIndicator.initPaginationIndicator(pageSize, perPageCountChoices, mList.size(), indexPage);
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

    @SuppressLint("StringFormatMatches")
    private void updateTitle() {
        try {
            setTitle(getString(R.string.scene_download_title,
                    Integer.toString(mList == null ? 0 : mList.size()),
                    mLabel != null ? mLabel : getString(R.string.default_download_label_name)));
        } catch (Exception e) {
            e.printStackTrace();
            CrashlyticsUtils.record(e);
            setTitle(getString(R.string.scene_download_title,
                    mLabel != null ? mLabel : getString(R.string.default_download_label_name)));
        }
    }

    private void onInit() {
        if (!handleArguments(getArguments())) {
            mLabel = Settings.getRecentDownloadLabel();
            updateForLabel();
        }
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
        View content = ViewUtils.$$(view, R.id.content);
        mRecyclerView = (MyEasyRecyclerView) ViewUtils.$$(content, R.id.recycler_view);
        FastScroller fastScroller = (FastScroller) ViewUtils.$$(content, R.id.fast_scroller);
        mFabLayout = (FabLayout) ViewUtils.$$(view, R.id.fab_layout);
        TextView tip = (TextView) ViewUtils.$$(view, R.id.tip);
        if (mPaginationIndicator != null) {
            needInitPage = true;
        }
        mPaginationIndicator = (PaginationIndicator) ViewUtils.$$(view, R.id.indicator);

        mPaginationIndicator.setPerPageCountChoices(perPageCountChoices, getPageSizePos(pageSize));

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
        myPageChangeListener = new MyPageChangeListener(indexPage, pageSize, needInitPage, doNotScroll, mOriginalAdapter, mRecyclerView);

        // 设置分页监听器的回调
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
        mRecyclerView.setCustomCheckedListener(new DownloadChoiceListener());
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

        if (mInitPosition >= 0 && indexPage != 1) {
            initPage(mInitPosition);
            mRecyclerView.scrollToPosition(listIndexInPage(mInitPosition));
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

        updateView();

        guide();
        updatePaginationIndicator();
        return view;
    }

    private void guide() {
        if (Settings.getGuideDownloadThumb() && null != mRecyclerView) {
            mRecyclerView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    if (Settings.getGuideDownloadThumb()) {
                        guideDownloadThumb();
                    }
                    if (null != mRecyclerView) {
                        ViewUtils.removeOnGlobalLayoutListener(mRecyclerView.getViewTreeObserver(), this);
                    }
                }
            });
        } else {
            guideDownloadLabels();
        }
    }

    private void guideDownloadThumb() {
        MainActivity activity = getActivity2();
        if (null == activity || !Settings.getGuideDownloadThumb() || null == mLayoutManager || null == mRecyclerView) {
            guideDownloadLabels();
            return;
        }
        int position = mLayoutManager.findFirstCompletelyVisibleItemPositions(null)[0];
        if (position < 0) {
            guideDownloadLabels();
            return;
        }
        RecyclerView.ViewHolder holder = mRecyclerView.findViewHolderForAdapterPosition(position);
        if (null == holder) {
            guideDownloadLabels();
            return;
        }

        mShowcaseView = new ShowcaseView.Builder(activity)
                .withMaterialShowcase()
                .setStyle(R.style.Guide)
                .setTarget(new ViewTarget(((DownloadAdapter.DownloadHolder) holder).thumb))
                .blockAllTouches()
                .setContentTitle(R.string.guide_download_thumb_title)
                .setContentText(R.string.guide_download_thumb_text)
                .replaceEndButton(R.layout.button_guide)
                .setShowcaseEventListener(new SimpleShowcaseEventListener() {
                    @Override
                    public void onShowcaseViewDidHide(ShowcaseView showcaseView) {
                        mShowcaseView = null;
                        ViewUtils.removeFromParent(showcaseView);
                        Settings.putGuideDownloadThumb(false);
                        guideDownloadLabels();
                    }
                }).build();
    }

    private void guideDownloadLabels() {
        MainActivity activity = getActivity2();
        if (null == activity || !Settings.getGuideDownloadLabels()) {
            return;
        }

        Display display = activity.getWindowManager().getDefaultDisplay();
        Point point = new Point();
        display.getSize(point);

        mShowcaseView = new ShowcaseView.Builder(activity)
                .withMaterialShowcase()
                .setStyle(R.style.Guide)
                .setTarget(new PointTarget(point.x, point.y / 3))
                .blockAllTouches()
                .setContentTitle(R.string.guide_download_labels_title)
                .setContentText(R.string.guide_download_labels_text)
                .replaceEndButton(R.layout.button_guide)
                .setShowcaseEventListener(new SimpleShowcaseEventListener() {
                    @Override
                    public void onShowcaseViewDidHide(ShowcaseView showcaseView) {
                        mShowcaseView = null;
                        ViewUtils.removeFromParent(showcaseView);
                        Settings.puttGuideDownloadLabels(false);
                        openDrawer(Gravity.RIGHT);
                    }
                }).build();
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        updateTitle();
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (null != mShowcaseView) {
            ViewUtils.removeFromParent(mShowcaseView);
            mShowcaseView = null;
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
                Intent intent = new Intent(activity, DownloadService.class);
                intent.setAction(DownloadService.ACTION_START_ALL);
                activity.startService(intent);
                return true;
            }
            case R.id.action_stop_all: {
                if (null != mDownloadManager) {
                    mDownloadManager.stopAllDownload();
                }
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
                            if (mDownloadManager != null) {
                                mDownloadManager.resetAllReadingProgress();
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
                importLocalArchive();
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
                    StorageLocation loc = StorageDetector.detect(di);
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
                    StorageLocation loc = StorageDetector.detect(di);
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
            case R.id.action_convert_storage: {
                // 必须在选择模式
                if (!mRecyclerView.isInCustomChoice()) return false;
                SparseBooleanArray array = mRecyclerView.getCheckedItemPositions();
                List<DownloadInfo> targets = new ArrayList<>();
                if (mList != null) {
                    for (int i = 0; i < array.size(); i++) {
                        if (array.valueAt(i)) {
                            int pos = positionInList(array.keyAt(i));
                            if (pos >=0 && pos < mList.size()) targets.add(mList.get(pos));
                        }
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

        }
        return false;
    }

    private void gotoSearch(Context context) {
        if (mSearchDialog != null) {
            mSearchDialog.show();
            return;
        }
        LayoutInflater layoutInflater = LayoutInflater.from(context);

        Drawable drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_download);

        LinearLayout linearLayout = (LinearLayout) layoutInflater.inflate(R.layout.download_search_dialog, null);
        mSearchBar = linearLayout.findViewById(R.id.download_search_bar);
        mSearchBar.setHelper(this);
        mSearchBar.setIsComeFromDownload(true);
        mSearchBar.setEditTextHint(R.string.download_search_hint);
        mSearchBar.setLeftDrawable(drawable);
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
        
        // 初始化当前设置状态，从 Settings 加载所有搜索相关设置
        mFuzzySearchCheckbox.setChecked(Settings.getEnableFuzzySearch());
        mIgnoreCaseCheckbox.setChecked(Settings.getEnableIgnoreCase());
        mChineseConversionCheckbox.setChecked(Settings.getEnableChineseConversion());
        mSortByRelevanceCheckbox.setChecked(Settings.getEnableSortByRelevance());
        
        // 设置"按相关性排序"复选框的启用状态，只有在模糊搜索启用时才可用
        updateSortByRelevanceState();
        
        // 添加模糊搜索复选框的点击监听器
        mFuzzySearchCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateSortByRelevanceState();
        });
        
        mSearchBarMover = new SearchBarMover(this, mSearchBar);
        mSearchDialog = new AlertDialog.Builder(context)
                .setMessage(R.string.download_search_gallery)
                .setView(linearLayout)
                .setCancelable(true)
                .setOnDismissListener(this::onSearchDialogDismiss)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    searchKey = null;
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

    private void convertBatchAsync(List<DownloadInfo> infos, boolean toCbz) {
        Context ctx = getEHContext();
        if (ctx == null) return;
        Toast.makeText(ctx, getString(R.string.convert_in_progress, 0, infos.size()), Toast.LENGTH_SHORT).show();
        new AsyncTask<Void, Integer, Integer>() {
            final List<String> failed = new ArrayList<>();
            @Override protected Integer doInBackground(Void... voids) {
                int done=0;
                for (DownloadInfo di : infos) {
                    try {
                        UniFile dir = getGalleryDownloadDir(di);
                        if (dir == null || !dir.isDirectory()) { failed.add(di.title); continue; }
                        if (toCbz) {
                            // 若已有 CBZ 跳过
                            if (com.hippo.ehviewer.util.CbzUtils.findCbzFile(dir)!=null) { done++; publishProgress(done); continue; }
                            GalleryInfo gi = new GalleryInfo();
                            gi.gid = di.gid; gi.title = di.title; gi.titleJpn = di.titleJpn; gi.category = di.category; gi.thumb = di.thumb;
                            int pages = di.total > 0 ? di.total : di.pages;
                            String[] tags = di.simpleTags != null ? di.simpleTags : new String[0];
                            com.hippo.ehviewer.util.CbzUtils.createCbzWithComicInfo(dir, gi, pages, tags, true);
                        } else {
                            // 解包：若无 CBZ 则跳过
                            UniFile cbz = com.hippo.ehviewer.util.CbzUtils.findCbzFile(dir);
                            if (cbz==null) { done++; publishProgress(done); continue; }
                            com.hippo.ehviewer.util.CbzUtils.extractCbz(dir, true);
                        }
                        done++; publishProgress(done);
                    } catch (Throwable t) {
                        failed.add(di.title);
                    }
                }
                return done;
            }
            @Override protected void onProgressUpdate(Integer... values) {
                if (ctx!=null) Toast.makeText(ctx, getString(R.string.convert_in_progress, values[0], infos.size()), Toast.LENGTH_SHORT).show();
            }
            @Override protected void onPostExecute(Integer result) {
                if (ctx!=null) {
                    if (failed.isEmpty()) {
                        Toast.makeText(ctx, getString(R.string.convert_done, result, infos.size()), Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(ctx, getString(R.string.convert_done, result, infos.size()), Toast.LENGTH_LONG).show();
                        for (String f : failed) {
                            Toast.makeText(ctx, getString(R.string.convert_failed_for, f), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
                // 退出选择模式并刷新
                if (mRecyclerView!=null) mRecyclerView.outOfCustomChoiceMode();
                updateForLabel();
                updateView();
            }
        }.executeOnExecutor(IoThreadPoolExecutor.getInstance());
    }

    private void onSearchDialogDismiss(DialogInterface dialog) {
        mSearchMode = false;
    }

    /**
     * 更新"按相关性排序"复选框的启用状态
     * 只有在"模糊搜索"启用时才允许使用
     */
    private void updateSortByRelevanceState() {
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

    private void enterSearchMode(boolean animation) {
        if (mSearchMode || mSearchBar == null || mSearchBarMover == null) {
            return;
        }
        mSearchMode = true;
        mSearchBar.setState(SearchBar.STATE_SEARCH_LIST, animation);

        mSearchBarMover.returnSearchBarPosition(animation);

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
        if (null != mShowcaseView) {
            return;
        }

        if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
            mRecyclerView.outOfCustomChoiceMode();
        } else {
            super.onBackPressed();
        }
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
            List<DownloadInfo> list = mList;
            if (list == null) {
                return false;
            }
            if (position < 0 || position >= list.size()) {
                return false;
            }

            DownloadInfo downloadInfo = list.get(positionInList(position));
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
        
        int posInList = positionInList(position);
        if (posInList < 0 || posInList >= mList.size()) {
            return false;
        }
        
        // 获取下载项信息
        DownloadInfo info = mList.get(posInList);
        
        // 创建弹出菜单，如果提供了锚点视图，则使用它；否则使用RecyclerView中的项目视图
        View menuAnchor = (anchorView != null) ? anchorView : mRecyclerView.getChildAt(position);
        android.widget.PopupMenu popupMenu = new android.widget.PopupMenu(context, menuAnchor);
        android.view.MenuInflater inflater = popupMenu.getMenuInflater();
        inflater.inflate(R.menu.download_item_menu, popupMenu.getMenu());
        
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
                    if (mFuzzySearchCheckbox != null) mFuzzySearchCheckbox.setChecked(false);
                    // 启用不区分大小写，提高搜索效果
                    if (mIgnoreCaseCheckbox != null) mIgnoreCaseCheckbox.setChecked(true);
                    // 启用简繁体转换，提高中文作者名搜索效果
                    if (mChineseConversionCheckbox != null) mChineseConversionCheckbox.setChecked(true);
                    
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
        if (mBackList == null || searchKey == null || searchKey.isEmpty()) {
            return false;
        }
        
        // 创建一个临时结果集
        List<DownloadInfo> tempResults = new ArrayList<>();
        
        // 使用当前的搜索设置
        boolean fuzzySearch = mFuzzySearchCheckbox != null && mFuzzySearchCheckbox.isChecked();
        boolean ignoreCase = mIgnoreCaseCheckbox != null && mIgnoreCaseCheckbox.isChecked();
        boolean enableChineseConversion = mChineseConversionCheckbox != null && mChineseConversionCheckbox.isChecked();
        boolean sortByRelevance = fuzzySearch && mSortByRelevanceCheckbox != null && mSortByRelevanceCheckbox.isChecked();
        
        // 执行搜索
        DownloadListInfosExecutor executor = new DownloadListInfosExecutor(mBackList, searchKey, 
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
        Context context = getEHContext();
        Activity activity = getActivity2();
        MyEasyRecyclerView recyclerView = mRecyclerView;
        if (null == context || null == activity || null == recyclerView) {
            return;
        }

        if (0 == position) {
            recyclerView.checkAll();
        } else {
            List<DownloadInfo> list = mList;
            if (list == null) {
                return;
            }

            LongList gidList = null;
            List<DownloadInfo> downloadInfoList = null;
            boolean collectGid = position == 1 || position == 2 || position == 3; // Start, Stop, Delete
            boolean collectDownloadInfo = position == 3 || position == 4 || position == 7; // Delete or Move (Change Label) or Move to SMB
            if (collectGid) {
                gidList = new LongList();
            }
            if (collectDownloadInfo) {
                downloadInfoList = new LinkedList<>();
            }

            SparseBooleanArray stateArray = recyclerView.getCheckedItemPositions();
            for (int i = 0, n = stateArray.size(); i < n; i++) {
                if (stateArray.valueAt(i)) {
                    DownloadInfo info = list.get(positionInList(stateArray.keyAt(i)));
                    if (collectDownloadInfo) {
                        downloadInfoList.add(info);
                    }
                    if (collectGid) {
                        gidList.add(info.gid);
                    }
                }
            }

            switch (position) {
                case 1: { // Start
                    if (gidList.isEmpty()) {
                        break;
                    }
                    Intent intent = new Intent(activity, DownloadService.class);
                    intent.setAction(DownloadService.ACTION_START_RANGE);
                    intent.putExtra(DownloadService.KEY_GID_LIST, gidList);
                    activity.startService(intent);
                    // Cancel check mode
                    recyclerView.outOfCustomChoiceMode();
                    break;
                }
                case 2: { // Stop
                    if (gidList.isEmpty()) {
                        break;
                    }
                    if (null != mDownloadManager) {
                        mDownloadManager.stopRangeDownload(gidList);
                    }
                    // Cancel check mode
                    recyclerView.outOfCustomChoiceMode();
                    break;
                }
                case 3: { // Delete
                    if (downloadInfoList.isEmpty()) {
                        break;
                    }
                    // 恢复使用“复选框”样式：保留原有“删除图片文件”勾选，同时增加“仅删除图片文件（不移除下载项）”
                    final LongList selectedGidList = gidList; // for lambda
                    final List<DownloadInfo> selectedInfoList = downloadInfoList; // for lambda

                    // 动态构建包含两个复选框的视图
                    LinearLayout container = new LinearLayout(context);
                    container.setOrientation(LinearLayout.VERTICAL);
                    int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
                    container.setPadding(pad, pad, pad, 0);

                    final CheckBox cbDeleteImages = new CheckBox(context);
                    cbDeleteImages.setText(R.string.download_remove_dialog_check_text);
                    cbDeleteImages.setChecked(Settings.getRemoveImageFiles());
                    container.addView(cbDeleteImages);

                    final CheckBox cbImagesOnly = new CheckBox(context);
                    cbImagesOnly.setText(R.string.download_remove_option_images_only);
                    cbImagesOnly.setChecked(false);
                    container.addView(cbImagesOnly);

                    // 逻辑：若勾选“仅删除图片文件”，则禁用“删除图片文件”的复选框（避免语义冲突）
                    cbImagesOnly.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        cbDeleteImages.setEnabled(!isChecked);
                    });

                    new AlertDialog.Builder(context)
                            .setTitle(R.string.download_remove_dialog_title)
                            .setMessage(getString(R.string.download_remove_dialog_message_2, selectedGidList.size()))
                            .setView(container)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(android.R.string.ok, (d, w) -> {
                                // 退出选择模式
                                if (mRecyclerView != null) {
                                    mRecyclerView.outOfCustomChoiceMode();
                                }

                                boolean imagesOnly = cbImagesOnly.isChecked();
                                boolean deleteImages = cbDeleteImages.isChecked();
                                // 记住“删除图片文件”选项
                                Settings.putRemoveImageFiles(deleteImages);

                                if (!imagesOnly) {
                                    // 普通模式：删除下载项
                                    if (mDownloadManager != null) {
                                        mDownloadManager.deleteRangeDownload(selectedGidList);
                                    }
                                }

                                if (imagesOnly || deleteImages) {
                                    // 需要删除图片文件（仅删图 或 附带删图）
                                    List<UniFile> fileList = new ArrayList<>();
                                    for (DownloadInfo info : selectedInfoList) {
                                        UniFile dir = getGalleryDownloadDir(info);
                                        if (dir != null) {
                                            fileList.add(dir);
                                        }
                                        // 清除路径映射
                                        EhDB.removeDownloadDirname(info.gid);

                                        if (imagesOnly) {
                                            // 仅删除图片文件保留下载项：重置状态以便重新下载
                                            info.state = DownloadInfo.STATE_NONE;
                                            info.finished = 0;
                                            info.downloaded = 0;
                                            info.speed = 0;
                                            info.remaining = 0;
                                            if (info.total < 0) info.total = 0;
                                            EhDB.putDownloadInfo(info);
                                        }
                                    }
                                    if (!fileList.isEmpty()) {
                                        deleteFileAsync(fileList.toArray(new UniFile[0]));
                                    }
                                }

                                // 刷新界面
                                updateForLabel();
                                updateView();
                            })
                            .show();
                    break;
                }
                case 4: { // Move (Change Label)
                    if (downloadInfoList.isEmpty()){
                        break;
                    }
                    List<DownloadLabel> labelRawList = EhApplication.getDownloadManager(context).getLabelList();
                    List<String> labelList = new ArrayList<>(labelRawList.size() + 1);
                    labelList.add(getString(R.string.default_download_label_name));
                    for (int i = 0, n = labelRawList.size(); i < n; i++) {
                        labelList.add(labelRawList.get(i).getLabel());
                    }
                    String[] labels = labelList.toArray(new String[labelList.size()]);

                    MoveDialogHelper helper = new MoveDialogHelper(labels, downloadInfoList);

                    new AlertDialog.Builder(context)
                            .setTitle(R.string.download_move_dialog_title)
                            .setItems(labels, helper)
                            .show();
                    break;
                }
                case 5: { // Random
                    if (mList == null || mList.isEmpty()) {
                        return;
                    }
                    onClickPrimaryFab(mFabLayout, null);
                    viewRandom();
                    break;
                }
                case 6: { // Toggle drag-and-drop
                    setDragEnable(fab);
                    break;
                }
                case 7: { // Move to SMB (now last)
                    if (downloadInfoList.isEmpty()){
                        break;
                    }
                    java.util.List<com.hippo.ehviewer.smb.SmbServer> servers = com.hippo.ehviewer.smb.SmbServerStore.INSTANCE.list();
                    if (servers == null || servers.isEmpty()) {
                        Toast.makeText(context, "请先在 设置>高级>添加 SMB 服务器", Toast.LENGTH_LONG).show();
                        break;
                    }
                    CharSequence[] choices = new CharSequence[servers.size()];
                    for (int i = 0; i < servers.size(); i++) {
                        com.hippo.ehviewer.smb.SmbServer s = servers.get(i);
                        com.hippo.ehviewer.smb.Authority a = s.getAuthority();
                        String userPart = (a.getDomain() != null && !a.getDomain().isEmpty()) ? (a.getDomain() + "\\\\" + a.getUsername()) : a.getUsername();
                        String portPart = (a.getPort() != com.hippo.ehviewer.smb.Authority.DEFAULT_PORT) ? (":" + a.getPort()) : "";
                        String path = s.getRelativePath();
                        String pathPart = (path == null || path.isEmpty()) ? "" : "/" + path.replace('\\', '/');
                        String url = "smb://" + userPart + ":" + s.getPassword() + "@" + a.getHost() + portPart + pathPart;
                        choices[i] = (s.getName() != null ? (s.getName() + ": ") : "") + url;
                    }

                    final Context ctxFinal = context;
                    final List<DownloadInfo> infosFinal = downloadInfoList;
                    new AlertDialog.Builder(context)
                            .setTitle("选择目标 SMB 服务器")
                            .setItems(choices, (d, whichIdx) -> {
                                com.hippo.ehviewer.smb.SmbServer server = servers.get(whichIdx);
                                migrateToSmbAsync(ctxFinal, infosFinal, server);
                            })
                            .show();
                    break;
                }
                default:
                    break;
            }
        }
    }

    private void setDragEnable(FloatingActionButton fab) {
        DRAG_ENABLE = !DRAG_ENABLE;
        Settings.setDragDownloadGallery(DRAG_ENABLE);
        Context context = getEHContext();
        if (null == context) return;
        if (DRAG_ENABLE) {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_x24, context.getTheme()));
        } else {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_off_x24, context.getTheme()));
        }
//        mDragDropManager.cancelDrag(dragEnable);
    }

    private void migrateToSmbAsync(Context context, List<DownloadInfo> infos, com.hippo.ehviewer.smb.SmbServer server) {
        // 取消选择模式
        if (mRecyclerView != null) {
            mRecyclerView.outOfCustomChoiceMode();
        }
        new android.os.AsyncTask<Void, Integer, Integer>() {
            @Override protected Integer doInBackground(Void... voids) {
                int okCount = 0;
                for (DownloadInfo info : infos) {
                    try {
                        com.hippo.unifile.UniFile dir = getGalleryDownloadDir(info);
                        if (dir == null || !dir.isDirectory()) continue;
                        String dirname = dir.getName();
                        com.hippo.ehviewer.smb.Client.Target base = server.toTarget();
                        if (base == null) continue;
                        String basePath = joinPath(base.getPathInShare(), dirname);
                        com.hippo.ehviewer.smb.Client.Target targetBase = new com.hippo.ehviewer.smb.Client.Target(base.getAuthority(), base.getShare(), basePath);
                        boolean success = com.hippo.ehviewer.smb.Client.INSTANCE.withTempPassword(targetBase.getAuthority(), server.getPassword(), () -> {
                            try {
                                com.hippo.ehviewer.smb.Client.INSTANCE.mkdirs(targetBase);
                                com.hippo.unifile.UniFile[] children = dir.listFiles();
                                if (children != null) {
                                    for (com.hippo.unifile.UniFile child : children) {
                                        if (child == null || child.isDirectory()) continue;
                                        String name = child.getName();
                                        java.io.InputStream is = null;
                                        try {
                                            is = child.openInputStream();
                                            com.hippo.ehviewer.smb.Client.INSTANCE.upload(
                                                    new com.hippo.ehviewer.smb.Client.Target(targetBase.getAuthority(), targetBase.getShare(), joinPath(targetBase.getPathInShare(), name)),
                                                    is,
                                                    true
                                            );
                                        } finally {
                                            com.hippo.lib.yorozuya.IOUtils.closeQuietly(is);
                                        }
                                    }
                                }
                                return true;
                            } catch (Throwable t) {
                                t.printStackTrace();
                                return false;
                            }
                        });
                        if (success) {
                            // 写入 SMB 映射
                            com.hippo.ehviewer.smb.SmbMappingStore.INSTANCE.put(
                                    new com.hippo.ehviewer.smb.SmbMappingStore.Mapping(
                                            info.gid,
                                            targetBase.getAuthority(),
                                            targetBase.getShare(),
                                            targetBase.getPathInShare()
                                    )
                            );
                            // 删除本地目录以实现“移动”效果
                            dir.delete();
                            okCount++;
                        }
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
                return okCount;
            }

            @Override protected void onPostExecute(Integer okCount) {
                Toast.makeText(context, "已移动漫画到 SMB：" + okCount + "/" + infos.size(), Toast.LENGTH_LONG).show();
                // 刷新界面
                updateForLabel();
                updateView();
            }
        }.executeOnExecutor(com.hippo.util.IoThreadPoolExecutor.getInstance());
    }

    private static String joinPath(String base, String name) {
        if (base == null) base = "";
        if (name == null) name = "";
        base = base.trim();
        name = name.trim();
        if (base.isEmpty()) return name;
        if (name.isEmpty()) return base;
        char sep = '\\';
        String b = base.replace('/', sep).replaceAll("\\\\+$", "");
        String n = name.replace('/', sep).replaceAll("^\\\\+", "");
        return b + sep + n;
    }

    private void viewRandom() {
        List<DownloadInfo> list = mList;
        if (list == null) {
            return;
        }
        int position = (int) (Math.random() * list.size());
        if (position < 0 || position >= list.size()) {
            return;
        }
        Activity activity = getActivity2();
        if (null == activity || null == mRecyclerView) {
            return;
        }

        Intent intent = new Intent(activity, GalleryActivity.class);
        intent.setAction(GalleryActivity.ACTION_EH);
        intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, list.get(position));
        galleryActivityLauncher.launch(intent);
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
        final int savedFilterId = mCurrentFilterId;
        final long restoreScrollGid = captureFirstVisibleGid();

        updateForLabel();

        // 如果之前处于某种过滤（例如"已下载"），则重新应用过滤，保持过滤视图
        if (savedFilterId != -1) {
            // 直接设置保存的滚动位置，不在 gotoFilterAndSort 中重新捕获
            mRestoreScrollGid = restoreScrollGid;
            // 设置标志避免滚动到顶部，保持当前位置
            doNotScroll = true;
            if (myPageChangeListener != null) {
                myPageChangeListener.setDoNotScroll(true);
            }
            // 使用 false 参数表示不需要重新捕获滚动位置
            gotoFilterAndSortWithScroll(savedFilterId, false);
            return; // 异步刷新，后续由回调处理
        }

        updateView();

        int index = mList.indexOf(newInfo);
        if (index >= 0 && mAdapter != null) {
//            mSpiderInfoMap.put(info.gid,getSpiderInfo(info));
            mAdapter.notifyItemChanged(listIndexInPage(index));
        }
        List<DownloadInfo> infos = new ArrayList<>();
        infos.add(newInfo);
        DownloadSpiderInfoExecutor executor = new DownloadSpiderInfoExecutor(infos, this::spiderInfoResultCallBack);
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
        mLabel = null;
        updateForLabel();
        updateView();
    }

    @Override
    public void onRenameLabel(String from, String to) {
        if (!ObjectUtils.equal(mLabel, from)) {
            return;
        }

        mLabel = to;
        updateForLabel();
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
        return indexPage;
    }

    @Override
    public int getPageSize() {
        return pageSize;
    }

    @Override
    public int getPaginationSize() {
        return paginationSize;
    }

    @Override
    public boolean isCanPagination() {
        return canPagination;
    }

    @Override
    public int positionInList(int position) {
        if (mList != null && mList.size() > paginationSize && canPagination) {
            return position + pageSize * (indexPage - 1);
        }
        return position;
    }

    @Override
    public int listIndexInPage(int position) {
        if (mList != null && mList.size() > paginationSize && canPagination) {
            return position % pageSize;
        }
        return position;
    }

    @Override
    public List<DownloadInfo> getList() {
        return mList;
    }

    @Override
    public Map<Long, SpiderInfo> getSpiderInfoMap() {
        return mSpiderInfoMap;
    }

    @Override
    public DownloadManager getDownloadManager() {
        return mDownloadManager;
    }

    @Override
    public MyEasyRecyclerView getRecyclerView() {
        return mRecyclerView;
    }


    private static void deleteFileAsync(UniFile... files) {
        new AsyncTask<UniFile, Void, Void>() {
            @Override
            protected Void doInBackground(UniFile... params) {
                for (UniFile file : params) {
                    if (file != null) {
                        file.delete();
                    }
                }
                return null;
            }
        }.executeOnExecutor(IoThreadPoolExecutor.getInstance(), files);
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
        mSearchBar.applySearch(true);
    }

    @Override
    public void onSearchEditTextClick() {

    }


    @Override
    public void onApplySearch(String query) {
        searchKey = query;
        mSearchBar.hideKeyBoard();
        searching = true;
        startSearching();
    }

    protected void startSearching() {
        mProgressView.setVisibility(View.VISIBLE);
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.GONE);
        }

        if (mSearchMode) {
            mSearchMode = false;
            mSearchBar.setTitle(searchKey);
            mSearchBar.setState(SearchBar.STATE_NORMAL);
        }

        // 添加null检查，避免空指针异常
        if (mSearchDialog != null) {
            mSearchDialog.dismiss();
        }

        // 只有当没有预先设置搜索结果时，才更新标签（避免覆盖已有的搜索结果）
        if (!searching) {
            updateForLabel();
        }

        DownloadListInfosExecutor executor = new DownloadListInfosExecutor(mList, searchKey);

        executor.setDownloadSearchingListener(this);

        executor.executeSearching();
    }

    private void gotoFilterAndSort(int id) {
        // 记录当前过滤ID，便于后续（如编辑信息）重新应用
        mCurrentFilterId = id;
        gotoFilterAndSortWithScroll(id, true);
    }

    private void gotoFilterAndSortWithScroll(int id, boolean captureScroll) {
        // 仅在需要时捕获滚动位置（新过滤操作时）
        // 当从 onReplace 调用时，滚动位置已保存，不需重新捕获
        if (captureScroll) {
            mRestoreScrollGid = captureFirstVisibleGid();
        }
        mProgressView.setVisibility(View.VISIBLE);
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.GONE);
        }

        DownloadListInfosExecutor executor = new DownloadListInfosExecutor(mBackList, mDownloadManager);

        executor.setDownloadSearchingListener(this);

        executor.executeFilterAndSort(id);
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
        if (mRecyclerView != null) {
            mRecyclerView.setAdapter(mAdapter);
        }
    }

    /**
     * 捕获当前第一个可见项的 gid，用于过滤/搜索后恢复滚动位置。
     */
    private long captureFirstVisibleGid() {
        if (mRecyclerView == null || mLayoutManager == null || mList == null) {
            return -1;
        }
        try {
            int spanCount = mLayoutManager.getSpanCount();
            int[] firsts = new int[spanCount];
            mLayoutManager.findFirstVisibleItemPositions(firsts);
            int min = Arrays.stream(firsts).filter(p -> p >= 0).min().orElse(-1);
            if (min < 0) return -1;
            int listPos = positionInList(min);
            if (listPos >= 0 && listPos < mList.size()) {
                return mList.get(listPos).gid;
            }
        } catch (Throwable ignore) {
            // 容错处理，无法获取时返回 -1
        }
        return -1;
    }

    /**
     * 过滤/搜索完成后，根据之前记录的 gid 恢复到相邻位置，避免回到顶部。
     */
    private void restoreScrollPositionIfNeeded() {
        if (mRestoreScrollGid == -1 || mList == null || mRecyclerView == null) {
            return;
        }
        int targetIndex = -1;
        for (int i = 0; i < mList.size(); i++) {
            if (mList.get(i).gid == mRestoreScrollGid) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex >= 0) {
            int adapterPos = listIndexInPage(targetIndex);
            mRecyclerView.scrollToPosition(adapterPos);
        }
        mRestoreScrollGid = -1;
    }

    @Override
    public void onSearchEditTextBackPressed() {
        if (mSearchMode) {
            mSearchMode = false;
        }
        mSearchBar.setState(SearchBar.STATE_NORMAL, true);
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
        return mRecyclerView;
    }

    @Override
    public boolean forceShowSearchBar() {
        return false;
    }

    @Override
    public void onDownloadSearchSuccess(List<DownloadInfo> list) {
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
        mProgressView.setVisibility(View.GONE);
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.VISIBLE);
        }
        // 在 UI 更新后延迟恢复滚动位置，避免被布局计算覆盖
        mRecyclerView.post(this::restoreScrollPositionIfNeeded);
        searching = false;
        queryUnreadSpiderInfo();
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
        mProgressView.setVisibility(View.GONE);
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
                    Activity activity = getActivity2();
                    if (activity != null) {
                        EhApplication.getSpiderInfoRepository(activity).invalidate(info.gid);
                    }
                    SpiderInfo spiderInfo = getSpiderInfo(info);
                    if (spiderInfo != null) {
                        mSpiderInfoMap.put(info.gid, spiderInfo);
                    }
                }

//                mSpiderInfoMap.remove(info.gid);
//                SpiderInfo spiderInfo = getSpiderInfo(info);
                int position = -1;
                if (mList == null || mAdapter == null || info == null) {
                    return;
                }
                for (int i = 0; i < mList.size(); i++) {
                    if (mList.get(i).gid == info.gid) {
                        position = listIndexInPage(i);
                        break;
                    }
                }
                if (position != -1) {
                    mAdapter.notifyItemChanged(position);
                } else {
                    mAdapter.notifyDataSetChanged();
                }

            }
        }
    }

    private void queryUnreadSpiderInfo() {
        if (mList == null) {
            return;
        }
        List<DownloadInfo> requestList = new ArrayList<>();
        for (int i = 0; i < mList.size(); i++) {
            DownloadInfo info = mList.get(i);
            if (!mSpiderInfoMap.containsKey(info.gid) || mSpiderInfoMap.get(info.gid) == null) {
                requestList.add(info);
            }
        }
        DownloadSpiderInfoExecutor executor = new DownloadSpiderInfoExecutor(requestList, this::spiderInfoResultCallBack);
        executor.execute();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void spiderInfoResultCallBack(Map<Long, SpiderInfo> resultMap) {
        mSpiderInfoMap.putAll(resultMap);
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void updateDownloadLabels(SomethingNeedRefresh somethingNeedRefresh) {
        if (somethingNeedRefresh.isDownloadLabelDrawNeed()) {
            downloadLabelDraw.updateDownloadLabels();
        }
    }


    @SuppressLint("NotifyDataSetChanged")
    private void initPage(int position) {
        if (mList != null && mList.size() > paginationSize && canPagination) {
            indexPage = position / pageSize + 1;
        }
        doNotScroll = true;
        if (mPaginationIndicator != null) {
            mPaginationIndicator.skip2Pos(indexPage);
        }
        mRecyclerView.scrollToPosition(listIndexInPage(position));
    }


    private int getPageSizePos(int pageSize) {
        int index = 0;
        for (int i = 0; i < perPageCountChoices.length; i++) {
            if (pageSize == perPageCountChoices[i]) {
                index = i;
                break;
            }
        }
        return index;
    }

    private void importLocalArchive() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/x-zip-compressed",
                "application/x-rar-compressed",
                "application/vnd.rar",
                "application/x-rar",
                "application/rar",
                "application/x-cbz",
                "application/x-cbr"
        });
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // CRITICAL: Add flags to enable persistent URI permissions
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        try {
            filePickerLauncher.launch(Intent.createChooser(intent, getString(R.string.import_archive_title)));
        } catch (Exception e) {
            Context context = getEHContext();
            if (context != null) {
                Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void handleSelectedFile(ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            return;
        }

        Uri uri = result.getData().getData();
        if (uri == null) {
            return;
        }

        Context context = getEHContext();
        if (context == null) {
            return;
        }

        // CRITICAL: Request persistent URI permission IMMEDIATELY when file is selected
        // This is the key to solving the permission loss issue after app restart
        try {
            context.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Log.d(TAG, "Successfully obtained persistent URI permission for: " + uri);
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to obtain persistent URI permission for: " + uri, e);
            Toast.makeText(context, R.string.archive_permission_lost, Toast.LENGTH_LONG).show();
            return;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error when obtaining URI permission for: " + uri, e);
            Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        // Show processing dialog
        Toast.makeText(context, R.string.import_archive_processing, Toast.LENGTH_LONG).show();

        // Process the archive file in background
        new Thread(() -> processArchiveFile(uri)).start();
    }

    private void processArchiveFile(Uri uri) {
        Context context = getEHContext();
        if (context == null) {
            return;
        }

        try {
            // Verify URI accessibility (permission should already be granted)
            try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
                if (inputStream == null) {
                    runOnUiThread(() ->
                            Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
                    );
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Cannot access file even with persistent permission", e);
                runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Get file name
            String fileName = getFileName(context, uri);
            if (fileName == null) {
                fileName = "imported_archive_" + System.currentTimeMillis();
            }

            // Validate file format
            if (!isValidArchiveFormat(fileName)) {
                runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_invalid_format, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Create DownloadInfo for the archive
            DownloadInfo downloadInfo = createArchiveDownloadInfo(context, uri, fileName);
            if (downloadInfo == null) {
                runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Check if already imported
            if (mDownloadManager != null && mDownloadManager.containDownloadInfo(downloadInfo.gid)) {
                runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_already_imported, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Add to download manager
            if (mDownloadManager != null) {
                List<DownloadInfo> downloadList = new ArrayList<>();
                downloadList.add(downloadInfo);
                mDownloadManager.addDownload(downloadList);
                runOnUiThread(() -> {
                    Toast.makeText(context, R.string.import_archive_success, Toast.LENGTH_SHORT).show();
                    updateForLabel();
                    updateView();
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to process archive file", e);
            runOnUiThread(() ->
                    Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
            );
        }
    }

    private boolean isValidArchiveFormat(String fileName) {
        if (fileName == null) return false;
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".zip") || lowerName.endsWith(".rar") ||
                lowerName.endsWith(".cbz") || lowerName.endsWith(".cbr");
    }


    public void runOnUiThread(Runnable runnable) {
        Activity activity = getActivity2();
        if (activity != null) {
            activity.runOnUiThread(runnable);
        }
    }

    private DownloadInfo createArchiveDownloadInfo(Context context, Uri uri, String fileName) {
        try {
            DownloadInfo downloadInfo = new DownloadInfo();
            downloadInfo.gid = System.currentTimeMillis(); // Use timestamp as unique ID
            downloadInfo.token = "";
            downloadInfo.title = fileName.replaceAll("\\.[^.]*$", ""); // Remove extension
            downloadInfo.titleJpn = null;
            downloadInfo.thumb = null; // No thumbnail for imported archives
            downloadInfo.category = EhUtils.UNKNOWN; // Keep as UNKNOWN, will be handled in display logic
            downloadInfo.posted = null;
            downloadInfo.uploader = "Local Archive";
            downloadInfo.rating = -1.0f; // Keep default rating to not affect other downloads
            downloadInfo.state = DownloadInfo.STATE_FINISH;
            downloadInfo.legacy = 0;
            downloadInfo.time = System.currentTimeMillis();
            downloadInfo.label = null;
            downloadInfo.total = 0; // Will be set by archive provider
            downloadInfo.finished = 0;

            // Store the URI in the archiveUri field - this is the key identifier
            downloadInfo.archiveUri = uri.toString();

            return downloadInfo;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create DownloadInfo", e);
            return null;
        }
    }

    private class DeleteDialogHelper implements DialogInterface.OnClickListener {

        private final GalleryInfo mGalleryInfo;
        private final CheckBoxDialogBuilder mBuilder;

        public DeleteDialogHelper(GalleryInfo galleryInfo, CheckBoxDialogBuilder builder) {
            mGalleryInfo = galleryInfo;
            mBuilder = builder;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which != DialogInterface.BUTTON_POSITIVE) {
                return;
            }

            // Delete
            if (null != mDownloadManager) {
                mDownloadManager.deleteDownload(mGalleryInfo.gid);
            }

            // Delete image files
            boolean checked = mBuilder.isChecked();
            Settings.putRemoveImageFiles(checked);
            if (checked) {
                // Remove download path
                EhDB.removeDownloadDirname(mGalleryInfo.gid);
                // Delete file
                UniFile file = getGalleryDownloadDir(mGalleryInfo);
                deleteFileAsync(file);
            }
        }
    }

    private class DeleteRangeDialogHelper implements DialogInterface.OnClickListener {

        private final List<DownloadInfo> mDownloadInfoList;
        private final LongList mGidList;
        private final CheckBoxDialogBuilder mBuilder;

        public DeleteRangeDialogHelper(List<DownloadInfo> downloadInfoList,
                                       LongList gidList, CheckBoxDialogBuilder builder) {
            mDownloadInfoList = downloadInfoList;
            mGidList = gidList;
            mBuilder = builder;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which != DialogInterface.BUTTON_POSITIVE) {
                return;
            }

            // Cancel check mode
            if (mRecyclerView != null) {
                mRecyclerView.outOfCustomChoiceMode();
            }

            // Delete
            if (null != mDownloadManager) {
                mDownloadManager.deleteRangeDownload(mGidList);
            }

            // Delete image files
            boolean checked = mBuilder.isChecked();
            Settings.putRemoveImageFiles(checked);
            if (checked) {
                UniFile[] files = new UniFile[mDownloadInfoList.size()];
                int i = 0;
                for (DownloadInfo info : mDownloadInfoList) {
                    // Remove download path
                    EhDB.removeDownloadDirname(info.gid);
                    // Put file
                    files[i] = getGalleryDownloadDir(info);
                    i++;
                }
                // Delete file
                deleteFileAsync(files);
            }
        }
    }

    private class MoveDialogHelper implements DialogInterface.OnClickListener {

        private final String[] mLabels;
        private final List<DownloadInfo> mDownloadInfoList;

        public MoveDialogHelper(String[] labels, List<DownloadInfo> downloadInfoList) {
            mLabels = labels;
            mDownloadInfoList = downloadInfoList;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            // Cancel check mode
            Context context = getEHContext();
            if (null == context) {
                return;
            }
            if (null != mRecyclerView) {
                mRecyclerView.outOfCustomChoiceMode();
            }

            String label;
            if (which == 0) {
                label = null;
            } else {
                label = mLabels[which];
            }
            EhApplication.getDownloadManager(context).changeLabel(mDownloadInfoList, label);
        }
    }

//    /**
//     * 更新thumb的可见性（拖拽功能已直接附加到thumb上）
//     * @param isSelectionMode 是否处于选择模式
//     */
//    private void updateThumbVisibility(boolean isSelectionMode) {
//        if (mRecyclerView == null) {
//            return;
//        }
//
//        for (int i = 0; i < mRecyclerView.getChildCount(); i++) {
//            RecyclerView.ViewHolder holder = mRecyclerView.getChildViewHolder(mRecyclerView.getChildAt(i));
//            if (holder instanceof DownloadAdapter.DownloadHolder) {
//                DownloadAdapter.DownloadHolder downloadHolder = (DownloadAdapter.DownloadHolder) holder;
//                // thumb 始终可见，拖拽功能已直接附加到thumb上
//                downloadHolder.thumb.setVisibility(View.VISIBLE);
//            }
//        }
//    }

    private class DownloadChoiceListener implements EasyRecyclerView.CustomChoiceListener {

        @Override
        public void onIntoCustomChoice(EasyRecyclerView view) {
            if (mRecyclerView != null) {
                mRecyclerView.setOnItemLongClickListener(null);
                mRecyclerView.setLongClickable(false);
            }
            if (mFabLayout != null) {
                mFabLayout.setExpanded(true);
            }
            // Lock drawer
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT);
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT);

//            // 进入选择模式时，thumb保持可见（拖拽功能已直接附加到thumb上）
//            updateThumbVisibility(true);
        }

        @Override
        public void onOutOfCustomChoice(EasyRecyclerView view) {
            if (mRecyclerView != null) {
                mRecyclerView.setOnItemLongClickListener(DownloadsScene.this);
            }
            if (mFabLayout != null) {
                mFabLayout.setExpanded(false);
            }
            // Unlock drawer
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.LEFT);
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT);

//            // 退出选择模式时，thumb保持可见（拖拽功能已直接附加到thumb上）
//            updateThumbVisibility(false);
        }

        @Override
        public void onItemCheckedStateChanged(EasyRecyclerView view, int position, long id, boolean checked) {
            if (view.getCheckedItemCount() == 0) {
                view.outOfCustomChoiceMode();
            }
        }
    }

    private void filterByCategory() {
        if (mBackList == null) {
            return;
        }
        if (mSelectedCategory == EhUtils.ALL_CATEGORY) {
            mList = new ArrayList<>(mBackList);
        } else {
            mList = new ArrayList<>();
            for (DownloadInfo info : mBackList) {
                if (info.category == mSelectedCategory) {
                    mList.add(info);
                }
            }
        }
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
        updateTitle();
        updatePaginationIndicator();
        updateView();
        queryUnreadSpiderInfo();
    }
}
