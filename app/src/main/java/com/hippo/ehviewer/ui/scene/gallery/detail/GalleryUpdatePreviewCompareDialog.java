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

package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Pair;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryPreview;
import com.hippo.ehviewer.client.data.NewVersion;
import com.hippo.ehviewer.client.data.PreviewSet;
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser;
import com.hippo.ehviewer.widget.FixedThumb;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.util.CbzUtils;
import com.hippo.io.UniFileInputStreamPipe;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.unifile.UniFile;
import com.hippo.util.BitmapUtils;

import java.io.InputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import okhttp3.OkHttpClient;

/**
 * 新旧版本漫画页面缩略图对照器
 * 支持多行可展开标题、滑动浏览、锁定同步滑动、解锁独立微调对齐、点击对齐选中项、页面跳转以及一键应用映射
 */
public class GalleryUpdatePreviewCompareDialog {

    public interface OnMappingSelectedListener {
        void onMappingSelected(int oldStart, int oldEnd, int newStart, int newEnd);
    }

    private final Context context;
    private final GalleryDetail oldDetail;
    private final NewVersion newVersion;
    private final OnMappingSelectedListener listener;

    private AlertDialog dialog;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private void safeExecute(Runnable runnable) {
        if (executor.isShutdown()) {
            return;
        }
        try {
            executor.execute(runnable);
        } catch (RejectedExecutionException e) {
            Log.w("CompareDialog", "Task rejected: executor is shutdown", e);
        }
    }

    private GalleryDetail newDetail;
    private boolean isLocked = true;
    private boolean isSyncing = false;

    private int selectedNewPos = RecyclerView.NO_POSITION;
    private int selectedOldPos = RecyclerView.NO_POSITION;

    // View 控件
    private TextView tvNewLabel;
    private TextView tvNewTitle;
    private TextView tvOldLabel;
    private TextView tvOldTitle;
    private TextView tvAlignStatus;
    private View layoutLoading;
    private TextView btnLockToggle;
    private TextView btnAlignSelected;
    private TextView btnApply;
    private TextView btnCancel;
    private RecyclerView rvNew;
    private RecyclerView rvOld;

    // 跳转按钮
    private TextView btnNewFirst;
    private TextView btnNewLast;
    private TextView btnNewJump;
    private TextView btnOldFirst;
    private TextView btnOldLast;
    private TextView btnOldJump;

    // 数据缓存
    private final SparseArray<GalleryPreview> oldPreviews = new SparseArray<>();
    private final Set<Integer> oldLoadingPages = Collections.synchronizedSet(new HashSet<>());
    private int oldItemsPerPage = 20;
    private PreviewCompareAdapter oldAdapter;

    private final SparseArray<GalleryPreview> newPreviews = new SparseArray<>();
    private final Set<Integer> newLoadingPages = Collections.synchronizedSet(new HashSet<>());
    private int newItemsPerPage = 20;
    private PreviewCompareAdapter newAdapter;

    @Nullable
    private final DownloadInfo oldDownloadInfo;
    @Nullable
    private final SpiderDen spiderDen;
    @Nullable
    private UniFile oldDownloadDir;
    private final LruCache<Integer, Bitmap> localThumbCache = new LruCache<>(80);
    /** 对话框已关闭，不再打开 CBZ 句柄（避免关闭后残留的在途任务重新打开句柄） */
    private volatile boolean released;

    public GalleryUpdatePreviewCompareDialog(@NonNull Context context,
                                             @NonNull GalleryDetail oldDetail,
                                             @NonNull NewVersion newVersion,
                                             @Nullable OnMappingSelectedListener listener) {
        this(context, oldDetail, newVersion, null, null, listener);
    }

    public GalleryUpdatePreviewCompareDialog(@NonNull Context context,
                                             @NonNull GalleryDetail oldDetail,
                                             @NonNull NewVersion newVersion,
                                             @Nullable GalleryDetail newDetail,
                                             @Nullable OnMappingSelectedListener listener) {
        this(context, oldDetail, newVersion, newDetail, null, listener);
    }

    public GalleryUpdatePreviewCompareDialog(@NonNull Context context,
                                             @NonNull GalleryDetail oldDetail,
                                             @NonNull NewVersion newVersion,
                                             @Nullable GalleryDetail newDetail,
                                             @Nullable DownloadInfo oldDownloadInfo,
                                             @Nullable OnMappingSelectedListener listener) {
        this.context = context;
        this.oldDetail = oldDetail;
        this.newVersion = newVersion;
        this.newDetail = newDetail;
        this.oldDownloadInfo = oldDownloadInfo;
        this.listener = listener;
        GalleryInfo infoForDen = oldDownloadInfo != null ? oldDownloadInfo : oldDetail;
        this.spiderDen = new SpiderDen(infoForDen);
        this.oldDownloadDir = SpiderDen.getGalleryDownloadDir(infoForDen);
        if (oldDownloadInfo != null && oldDetail.pages <= 0 && oldDownloadInfo.pages > 0) {
            oldDetail.pages = oldDownloadInfo.pages;
        }
    }

    public void show() {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_gallery_preview_compare, null);
        initViews(view);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(view);
        dialog = builder.create();
        dialog.setOnDismissListener(d -> {
            if (rvNew != null) rvNew.stopScroll();
            if (rvOld != null) rvOld.stopScroll();
            executor.shutdownNow();
            // 释放本地 CBZ 的 ZipFile 句柄：本对话框的 SpiderDen 不归 SpiderQueen 管理，
            // 不主动关闭会一直滞留到 GC，最终在 finalizer 线程 close 失败导致进程崩溃
            released = true;
            if (spiderDen != null) {
                spiderDen.closeCbzZipFile();
            }
        });
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        initOldVersionData();
        if (newDetail != null) {
            onNewDetailLoaded(newDetail);
        } else {
            fetchNewVersionData();
        }
    }

    private void initViews(View view) {
        tvNewLabel = view.findViewById(R.id.tv_new_label);
        tvNewTitle = view.findViewById(R.id.tv_new_title);
        tvOldLabel = view.findViewById(R.id.tv_old_label);
        tvOldTitle = view.findViewById(R.id.tv_old_title);
        tvAlignStatus = view.findViewById(R.id.tv_align_status);
        layoutLoading = view.findViewById(R.id.layout_loading);
        btnLockToggle = view.findViewById(R.id.btn_lock_toggle);
        btnAlignSelected = view.findViewById(R.id.btn_align_selected);
        btnApply = view.findViewById(R.id.btn_compare_apply);
        btnCancel = view.findViewById(R.id.btn_compare_cancel);
        rvNew = view.findViewById(R.id.rv_new_previews);
        rvOld = view.findViewById(R.id.rv_old_previews);

        btnNewFirst = view.findViewById(R.id.btn_new_first);
        btnNewLast = view.findViewById(R.id.btn_new_last);
        btnNewJump = view.findViewById(R.id.btn_new_jump);
        btnOldFirst = view.findViewById(R.id.btn_old_first);
        btnOldLast = view.findViewById(R.id.btn_old_last);
        btnOldJump = view.findViewById(R.id.btn_old_jump);

        // 设置标题点击展开/收起（支持多行展示与完整展示）
        View.OnClickListener titleClickListener = v -> {
            if (v instanceof TextView tv) {
                boolean isExpanded = tv.getMaxLines() > 3;
                tv.setMaxLines(isExpanded ? 3 : Integer.MAX_VALUE);
            }
        };
        tvNewTitle.setOnClickListener(titleClickListener);
        tvOldTitle.setOnClickListener(titleClickListener);

        // 设置水平布局
        rvNew.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        rvOld.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));

        // 按钮监听
        btnLockToggle.setOnClickListener(v -> toggleLock());
        btnAlignSelected.setOnClickListener(v -> alignSelected());
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnApply.setOnClickListener(v -> applyCurrentMapping());

        // 跳转按钮监听
        btnNewFirst.setOnClickListener(v -> scrollToTarget(true, 0));
        btnNewLast.setOnClickListener(v -> {
            int last = (newDetail != null ? newDetail.pages : 1) - 1;
            scrollToTarget(true, Math.max(0, last));
        });
        btnNewJump.setOnClickListener(v -> showJumpDialog(true));

        btnOldFirst.setOnClickListener(v -> scrollToTarget(false, 0));
        btnOldLast.setOnClickListener(v -> {
            int last = oldDetail.pages - 1;
            scrollToTarget(false, Math.max(0, last));
        });
        btnOldJump.setOnClickListener(v -> showJumpDialog(false));

        updateLockUI();
        updateAlignSelectedButton();
        setupSyncScroll();
    }

    /**
     * 设置双 RecyclerView 联动监听
     */
    private void setupSyncScroll() {
        rvNew.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (isLocked && !isSyncing && dx != 0) {
                    isSyncing = true;
                    rvOld.scrollBy(dx, 0);
                    isSyncing = false;
                }
                updateAlignmentStatus();
            }
        });

        rvOld.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (isLocked && !isSyncing && dx != 0) {
                    isSyncing = true;
                    rvNew.scrollBy(dx, 0);
                    isSyncing = false;
                }
                updateAlignmentStatus();
            }
        });
    }

    /**
     * 切换锁定 / 解锁状态
     */
    private void toggleLock() {
        isLocked = !isLocked;
        updateLockUI();
    }

    private void updateLockUI() {
        if (isLocked) {
            btnLockToggle.setText(R.string.gallery_update_lock_sync);
            btnLockToggle.setTextColor(Color.parseColor("#ff009688"));
        } else {
            btnLockToggle.setText(R.string.gallery_update_unlock_sync);
            btnLockToggle.setTextColor(Color.parseColor("#ffff7043"));
        }
    }

    /**
     * 跳转到指定页
     * @param isNew 是否为新版
     * @param targetIndex 目标页码（0-indexed）
     */
    private void scrollToTarget(boolean isNew, int targetIndex) {
        RecyclerView sourceRv = isNew ? rvNew : rvOld;
        RecyclerView otherRv = isNew ? rvOld : rvNew;
        if (sourceRv == null || sourceRv.getLayoutManager() == null) return;

        LinearLayoutManager sourceLm = (LinearLayoutManager) sourceRv.getLayoutManager();

        if (!isLocked) {
            // 解锁状态：仅滚动目标行
            sourceLm.scrollToPositionWithOffset(targetIndex, 0);
        } else {
            // 锁定状态：计算移动差值，两排同步跳转，保持相对偏移
            int currentPos = findAnchorItemPosition(sourceRv);
            int delta = targetIndex - currentPos;

            sourceLm.scrollToPositionWithOffset(targetIndex, 0);
            if (otherRv != null && otherRv.getLayoutManager() != null) {
                LinearLayoutManager otherLm = (LinearLayoutManager) otherRv.getLayoutManager();
                int otherCurrent = findAnchorItemPosition(otherRv);
                int otherTotal = isNew ? oldDetail.pages : (newDetail != null ? newDetail.pages : oldDetail.pages);
                int otherTarget = Math.max(0, Math.min(otherTotal - 1, otherCurrent + delta));
                otherLm.scrollToPositionWithOffset(otherTarget, 0);
            }
        }

        mainHandler.postDelayed(this::updateAlignmentStatus, 150);
    }

    /**
     * 弹出页码跳转输入框
     */
    private void showJumpDialog(boolean isNew) {
        int total = isNew ? (newDetail != null ? newDetail.pages : 1) : oldDetail.pages;
        EditText editText = new EditText(context);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        int current = (isNew ? findAnchorItemPosition(rvNew) : findAnchorItemPosition(rvOld)) + 1;
        editText.setText(String.valueOf(current));
        editText.setSelection(editText.getText().length());
        editText.setGravity(Gravity.CENTER);

        new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.gallery_update_jump_dialog_title, total))
                .setView(editText)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String text = editText.getText().toString().trim();
                    try {
                        int page = Integer.parseInt(text);
                        if (page >= 1 && page <= total) {
                            scrollToTarget(isNew, page - 1);
                        } else {
                            Toast.makeText(context, context.getString(R.string.gallery_update_jump_invalid_page, total), Toast.LENGTH_SHORT).show();
                        }
                    } catch (NumberFormatException e) {
                        Toast.makeText(context, context.getString(R.string.gallery_update_jump_invalid_page, total), Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    /**
     * 将选中的新版页与旧版页垂直对齐
     */
    private void alignSelected() {
        if (selectedNewPos == RecyclerView.NO_POSITION || selectedOldPos == RecyclerView.NO_POSITION) {
            return;
        }

        LinearLayoutManager lmNew = (LinearLayoutManager) rvNew.getLayoutManager();
        LinearLayoutManager lmOld = (LinearLayoutManager) rvOld.getLayoutManager();
        if (lmNew != null && lmOld != null) {
            lmNew.scrollToPositionWithOffset(selectedNewPos, 0);
            lmOld.scrollToPositionWithOffset(selectedOldPos, 0);
        }

        // 对齐后自动锁定，以便继续同步检查
        isLocked = true;
        updateLockUI();
        mainHandler.postDelayed(this::updateAlignmentStatus, 150);
    }

    private void updateAlignSelectedButton() {
        boolean canAlign = (selectedNewPos != RecyclerView.NO_POSITION && selectedOldPos != RecyclerView.NO_POSITION);
        btnAlignSelected.setEnabled(canAlign);
        btnAlignSelected.setTextColor(Color.parseColor("#ff009688"));
        btnAlignSelected.setAlpha(canAlign ? 1.0f : 0.4f);
    }

    /**
     * 初始化旧版本数据
     */
    private void initOldVersionData() {
        tvOldLabel.setText(context.getString(R.string.gallery_update_old_version_label, oldDetail.pages));
        String oldTitle = EhUtils.getSuitableTitle(oldDetail);
        tvOldTitle.setText(oldTitle != null ? oldTitle : String.valueOf(oldDetail.gid));

        // 载入第 0 页 previewSet
        if (oldDetail.previewSet != null) {
            int size = oldDetail.previewSet.size();
            if (size > 0) {
                oldItemsPerPage = size;
            }
            for (int i = 0; i < size; i++) {
                GalleryPreview preview = oldDetail.previewSet.getGalleryPreview(oldDetail.gid, i);
                if (preview != null) {
                    oldPreviews.put(preview.getPosition(), preview);
                }
            }
        } else {
            // 旧版本没有 previewSet 时（例如传入的是 mock GalleryDetail），异步拉取完整详情
            fetchOldVersionData();
        }

        oldAdapter = new PreviewCompareAdapter(false, oldDetail.gid, oldDetail.token, oldDetail.pages, oldPreviews);
        rvOld.setAdapter(oldAdapter);
    }

    private void fetchOldVersionData() {
        if (oldDetail.gid <= 0 || oldDetail.token == null) return;
        layoutLoading.setVisibility(View.VISIBLE);
        safeExecute(() -> {
            try {
                String url = EhUrl.getGalleryDetailUrl(oldDetail.gid, oldDetail.token);
                OkHttpClient client = EhApplication.getOkHttpClient(context);
                GalleryDetail detail = EhEngine.getGalleryDetail(null, client, url);
                mainHandler.post(() -> {
                    if (dialog == null || !dialog.isShowing()) return;
                    layoutLoading.setVisibility(View.GONE);
                    if (detail != null) {
                        onOldDetailLoaded(detail);
                    }
                });
            } catch (Throwable e) {
                Log.w("CompareDialog", "Failed to fetch old gallery detail: " + e.getMessage());
                mainHandler.post(() -> {
                    if (dialog == null || !dialog.isShowing()) return;
                    layoutLoading.setVisibility(View.GONE);
                });
            }
        });
    }

    private void onOldDetailLoaded(@NonNull GalleryDetail detail) {
        if (detail.pages > 0) {
            oldDetail.pages = detail.pages;
        }
        tvOldLabel.setText(context.getString(R.string.gallery_update_old_version_label, oldDetail.pages));
        String oldTitle = EhUtils.getSuitableTitle(detail);
        if (oldTitle != null && !oldTitle.isEmpty()) {
            tvOldTitle.setText(oldTitle);
        }
        if (detail.previewSet != null) {
            int size = detail.previewSet.size();
            if (size > 0) {
                oldItemsPerPage = size;
            }
            for (int i = 0; i < size; i++) {
                GalleryPreview preview = detail.previewSet.getGalleryPreview(detail.gid, i);
                if (preview != null) {
                    oldPreviews.put(preview.getPosition(), preview);
                }
            }
        }
        if (oldAdapter != null) {
            oldAdapter.setTotalCount(oldDetail.pages);
        }
    }

    /**
     * 异步拉取新版本详情与第 0 页 PreviewSet
     */
    private void fetchNewVersionData() {
        layoutLoading.setVisibility(View.VISIBLE);
        tvNewLabel.setText(context.getString(R.string.gallery_update_new_version_label, 0));
        tvNewTitle.setText(newVersion.versionName);

        safeExecute(() -> {
            try {
                GalleryDetailUrlParser.Result parseResult = GalleryDetailUrlParser.parse(newVersion.versionUrl, false);
                long gid = parseResult != null ? parseResult.gid : -1;
                String token = parseResult != null ? parseResult.token : null;
                String url = (gid > 0 && token != null) ?
                        EhUrl.getGalleryDetailUrl(gid, token) : newVersion.versionUrl;

                OkHttpClient client = EhApplication.getOkHttpClient(context);
                GalleryDetail detail = EhEngine.getGalleryDetail(null, client, url);

                mainHandler.post(() -> {
                    if (dialog == null || !dialog.isShowing()) return;
                    layoutLoading.setVisibility(View.GONE);
                    if (detail != null) {
                        newDetail = detail;
                        onNewDetailLoaded(detail);
                    } else {
                        Toast.makeText(context, R.string.gallery_update_loading_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Throwable e) {
                mainHandler.post(() -> {
                    if (dialog == null || !dialog.isShowing()) return;
                    layoutLoading.setVisibility(View.GONE);
                    Toast.makeText(context, R.string.gallery_update_loading_failed, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void onNewDetailLoaded(@NonNull GalleryDetail detail) {
        tvNewLabel.setText(context.getString(R.string.gallery_update_new_version_label, detail.pages));
        String newTitle = EhUtils.getSuitableTitle(detail);
        if (newTitle == null || newTitle.isEmpty()) {
            newTitle = newVersion.versionName;
        }
        tvNewTitle.setText(newTitle);

        if (detail.previewSet != null) {
            int size = detail.previewSet.size();
            if (size > 0) {
                newItemsPerPage = size;
            }
            for (int i = 0; i < size; i++) {
                GalleryPreview preview = detail.previewSet.getGalleryPreview(detail.gid, i);
                if (preview != null) {
                    newPreviews.put(preview.getPosition(), preview);
                }
            }
        }

        newAdapter = new PreviewCompareAdapter(true, detail.gid, detail.token, detail.pages, newPreviews);
        rvNew.setAdapter(newAdapter);
        mainHandler.postDelayed(this::updateAlignmentStatus, 100);
    }

    /**
     * 计算当前屏幕最靠前可见的 Item 索引（0-indexed）
     */
    private int findAnchorItemPosition(RecyclerView rv) {
        if (rv == null || rv.getLayoutManager() == null) return 0;
        LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
        int pos = lm.findFirstVisibleItemPosition();
        return pos != RecyclerView.NO_POSITION ? pos : 0;
    }

    /**
     * 更新状态栏对齐信息
     */
    private void updateAlignmentStatus() {
        if (rvOld == null || rvNew == null) return;
        int oldAnchor = findAnchorItemPosition(rvOld) + 1;
        int newAnchor = findAnchorItemPosition(rvNew) + 1;
        int diff = newAnchor - oldAnchor;
        tvAlignStatus.setText(context.getString(R.string.gallery_update_align_status, oldAnchor, newAnchor, diff));
    }

    /**
     * 点击应用映射：自动计算对应页数范围并回填
     */
    private void applyCurrentMapping() {
        int oldAnchor = findAnchorItemPosition(rvOld) + 1;
        int newAnchor = findAnchorItemPosition(rvNew) + 1;
        int diff = newAnchor - oldAnchor;

        int oldTotal = oldDetail != null ? oldDetail.pages : 1;
        int newTotal = newDetail != null ? newDetail.pages : oldTotal;

        int oldStart, oldEnd, newStart, newEnd;
        if (diff >= 0) {
            oldStart = 1;
            newStart = 1 + diff;
            int count = Math.min(oldTotal, Math.max(1, newTotal - diff));
            oldEnd = oldStart + count - 1;
            newEnd = newStart + count - 1;
        } else {
            oldStart = 1 - diff;
            newStart = 1;
            int count = Math.min(Math.max(1, oldTotal + diff), newTotal);
            oldEnd = oldStart + count - 1;
            newEnd = newStart + count - 1;
        }

        if (listener != null) {
            listener.onMappingSelected(oldStart, oldEnd, newStart, newEnd);
        }
        dialog.dismiss();
    }

    /**
     * 按需后台加载后续 previewSet 分页
     */
    private void requestPreviewPage(boolean isNewVersion, long gid, String token, int previewPageIndex) {
        Set<Integer> loadingPages = isNewVersion ? newLoadingPages : oldLoadingPages;
        if (!loadingPages.add(previewPageIndex)) {
            return;
        }

        safeExecute(() -> {
            try {
                String url = EhUrl.getGalleryDetailUrl(gid, token, previewPageIndex, false);
                OkHttpClient client = EhApplication.getOkHttpClient(context);
                Pair<PreviewSet, Integer> pair = EhEngine.getPreviewSet(null, client, url);
                if (pair != null && pair.first != null) {
                    PreviewSet pSet = pair.first;
                    if (pSet.size() > 0) {
                        if (isNewVersion) {
                            newItemsPerPage = pSet.size();
                        } else {
                            oldItemsPerPage = pSet.size();
                        }
                    }
                    SparseArray<GalleryPreview> previews = isNewVersion ? newPreviews : oldPreviews;
                    for (int i = 0; i < pSet.size(); i++) {
                        GalleryPreview gp = pSet.getGalleryPreview(gid, i);
                        if (gp != null) {
                            previews.put(gp.getPosition(), gp);
                        }
                    }
                    mainHandler.post(() -> {
                        PreviewCompareAdapter adapter = isNewVersion ? newAdapter : oldAdapter;
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    });
                }
            } catch (Throwable ignore) {
            } finally {
                loadingPages.remove(previewPageIndex);
            }
        });
    }

    /**
     * 缩略图列表适配器
     */
    private class PreviewCompareAdapter extends RecyclerView.Adapter<PreviewCompareHolder> {

        private final boolean isNew;
        private final long gid;
        private final String token;
        private int totalCount;
        private final SparseArray<GalleryPreview> previewMap;

        public PreviewCompareAdapter(boolean isNew, long gid, String token, int totalCount,
                                     SparseArray<GalleryPreview> previewMap) {
            this.isNew = isNew;
            this.gid = gid;
            this.token = token;
            this.totalCount = totalCount;
            this.previewMap = previewMap;
        }

        public void setTotalCount(int totalCount) {
            this.totalCount = totalCount;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public PreviewCompareHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_gallery_compare_thumb, parent, false);
            return new PreviewCompareHolder(view);
        }

        @Override
        @SuppressLint("SetTextI18n")
        public void onBindViewHolder(@NonNull PreviewCompareHolder holder, int position) {
            holder.text.setText("P. " + (position + 1));

            // 选中高亮状态
            boolean isSelected = isNew ? (position == selectedNewPos) : (position == selectedOldPos);
            holder.itemView.setBackgroundResource(isSelected ?
                    R.drawable.bg_compare_thumb_selected : R.drawable.bg_compare_thumb_normal);

            // 点击选中切换
            holder.itemView.setOnClickListener(v -> {
                int adapterPos = holder.getBindingAdapterPosition();
                if (adapterPos == RecyclerView.NO_POSITION) return;

                if (isNew) {
                    selectedNewPos = (selectedNewPos == adapterPos) ? RecyclerView.NO_POSITION : adapterPos;
                } else {
                    selectedOldPos = (selectedOldPos == adapterPos) ? RecyclerView.NO_POSITION : adapterPos;
                }
                notifyDataSetChanged();
                updateAlignSelectedButton();
            });

            // 载入缩略图
            GalleryPreview preview = previewMap.get(position);
            if (preview != null) {
                holder.image.resetClip();
                preview.load(holder.image);
            } else if (!isNew) {
                loadLocalThumbnail(holder, position);
            } else {
                holder.image.load(null, null);
                int itemsPerPage = isNew ? newItemsPerPage : oldItemsPerPage;
                int previewPage = position / Math.max(1, itemsPerPage);
                requestPreviewPage(isNew, gid, token, previewPage);
            }
        }

        private void loadLocalThumbnail(PreviewCompareHolder holder, int position) {
            Bitmap cached = localThumbCache.get(position);
            if (cached != null && !cached.isRecycled()) {
                holder.image.unload();
                holder.image.resetClip();
                holder.image.setImageBitmap(cached);
                return;
            }

            holder.image.load(null, null);
            final int targetPosition = position;

            safeExecute(() -> {
                Bitmap bmp = decodeLocalPageThumbnail(targetPosition);
                if (bmp != null) {
                    localThumbCache.put(targetPosition, bmp);
                    mainHandler.post(() -> {
                        if (dialog != null && dialog.isShowing() && holder.getBindingAdapterPosition() == targetPosition) {
                            holder.image.unload();
                            holder.image.resetClip();
                            holder.image.setImageBitmap(bmp);
                        }
                    });
                } else {
                    // 本地没有该页，回退到在线画廊预览
                    mainHandler.post(() -> {
                        if (dialog != null && dialog.isShowing()) {
                            GalleryPreview preview = oldPreviews.get(targetPosition);
                            if (preview != null) {
                                if (holder.getBindingAdapterPosition() == targetPosition) {
                                    holder.image.resetClip();
                                    preview.load(holder.image);
                                }
                            } else {
                                int previewPage = targetPosition / Math.max(1, oldItemsPerPage);
                                requestPreviewPage(false, gid, token, previewPage);
                            }
                        }
                    });
                }
            });
        }

        @Nullable
        private Bitmap decodeLocalPageThumbnail(int position) {
            if (released) {
                return null;
            }
            if (spiderDen != null) {
                InputStreamPipe pipe = spiderDen.openDownloadInputStreamPipe(position);
                if (pipe != null) {
                    try {
                        Bitmap bmp = BitmapUtils.decodeStream(pipe, 250, 250);
                        if (bmp != null) {
                            return bmp;
                        }
                    } catch (Throwable t) {
                        Log.w("CompareDialog", "Failed to decode thumbnail from pipe for pos " + position, t);
                    }
                }
            }

            // 兜底：直接扫描物理目录
            final UniFile dir = oldDownloadDir;
            if (dir != null && dir.isDirectory()) {
                String name = String.format(Locale.US, "%08d", position + 1);
                String[] extensions = {".jpg", ".png", ".webp", ".gif", ".jpeg", ".bmp"};
                UniFile file = null;
                for (String ext : extensions) {
                    UniFile f = dir.findFile(name + ext);
                    if (f != null && f.isFile()) {
                        file = f;
                        break;
                    }
                }
                if (file == null) {
                    try {
                        UniFile[] children = dir.listFiles();
                        if (children != null) {
                            for (UniFile f : children) {
                                if (f.isFile() && f.getName() != null && f.getName().startsWith(name)) {
                                    file = f;
                                    break;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
                if (file != null) {
                    try {
                        Bitmap bmp = BitmapUtils.decodeStream(new UniFileInputStreamPipe(file), 250, 250);
                        if (bmp != null) {
                            return bmp;
                        }
                    } catch (Throwable ignored) {}
                }

                // CBZ 兜底
                if (CbzUtils.isCbzMode(dir)) {
                    UniFile cbzFile = CbzUtils.findCbzFile(dir);
                    if (cbzFile != null) {
                        try (ZipInputStream zis = new ZipInputStream(cbzFile.openInputStream())) {
                            ZipEntry entry;
                            while ((entry = zis.getNextEntry()) != null) {
                                String entryName = entry.getName();
                                if (entryName != null && entryName.startsWith(name)) {
                                    byte[] data = IOUtils.getAllByte(zis);
                                    if (data != null && data.length > 0) {
                                        BitmapFactory.Options opts = new BitmapFactory.Options();
                                        opts.inSampleSize = 2;
                                        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
            return null;
        }

        @Override
        public int getItemCount() {
            return totalCount;
        }
    }

    private static class PreviewCompareHolder extends RecyclerView.ViewHolder {
        public final FixedThumb image;
        public final TextView text;

        public PreviewCompareHolder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.thumb_image);
            text = itemView.findViewById(R.id.thumb_text);
        }
    }
}
