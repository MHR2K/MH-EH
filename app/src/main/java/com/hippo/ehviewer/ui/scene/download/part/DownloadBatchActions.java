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

import static com.hippo.ehviewer.spider.SpiderDen.getExistingGalleryDownloadDir;
import static com.hippo.ehviewer.spider.SpiderDen.getGalleryDownloadDir;
import static com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter.DRAG_ENABLE;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.text.TextUtils;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager;
import com.hippo.android.resource.AttrResources;
import com.hippo.app.CheckBoxDialogBuilder;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.smb.SmbServer;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.widget.MyEasyRecyclerView;
import com.hippo.unifile.UniFile;
import com.hippo.widget.FabLayout;

import android.app.ProgressDialog;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.res.ResourcesCompat;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.download.DownloadService;
import com.hippo.lib.yorozuya.ObjectUtils;
import com.hippo.lib.yorozuya.collect.LongList;
import com.hippo.util.IoThreadPoolExecutor;

import java.util.concurrent.atomic.AtomicBoolean;


import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 下载页 FAB 多选批量操作、删除/移动对话框、SMB 迁移与随机阅读。
 * 本地超集：批量获取收藏数、批量 CBZ 转换、GID 移动位置、
 * 仅删图片/仅添加下载项、删除阅读进度、SMB 三选项删除、全部失败。
 */
public class DownloadBatchActions {

    public interface Host {
        @Nullable
        Context getEHContext();

        @Nullable
        Activity getActivity2();

        @Nullable
        MyEasyRecyclerView getRecyclerView();

        @Nullable
        List<DownloadInfo> getList();

        @Nullable
        List<DownloadInfo> getBackList();

        @Nullable
        DownloadManager getDownloadManager();

        @Nullable
        FabLayout getFabLayout();

        @Nullable
        RecyclerViewDragDropManager getDragDropManager();

        Map<Long, SpiderInfo> getSpiderInfoMap();

        @Nullable
        DownloadInfo getDownloadInfoAtAdapterPosition(int adapterPosition);

        Resources getResources();

        String getString(int resId);

        String getString(int resId, Object... formatArgs);

        void updateForLabel();

        void updateView();

        void updateAdapter();

        void launchGalleryActivity(Intent intent);

        void onClickPrimaryFab(FabLayout view, FloatingActionButton fab);

        String getLabel();

        void setLabel(String label);
    }

    @Nullable
    private final Host mHost;

    public DownloadBatchActions(@Nullable Host host) {
        mHost = host;
    }

    public void showRefreshFavCountDialog() {
        Context ctx = mHost.getEHContext();
        if (ctx == null || mHost.getBackList() == null) return;
        int count = mHost.getBackList().size();
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.download_refresh_fav_count)
                .setMessage(mHost.getString(R.string.download_refresh_fav_count_message, count))
                .setPositiveButton(android.R.string.ok, (d, w) -> startBatchGetFavCount())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startBatchGetFavCount() {
        Context ctx = mHost.getEHContext();
        if (ctx == null || mHost.getBackList() == null) return;
        // 跳过已有收藏数的项目，跳过7天内的新本子（收藏数还不稳定）
        long sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        java.text.DateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);
        List<DownloadInfo> items = new ArrayList<>();
        int skippedNew = 0;
        for (DownloadInfo di : mHost.getBackList()) {
            if (di.favoriteCount > 0) continue; // 已有收藏数，跳过
            // 检查是否是7天内的新本子
            if (di.posted != null && !di.posted.isEmpty()) {
                try {
                    long postedTime = df.parse(di.posted).getTime();
                    if (postedTime > sevenDaysAgo) {
                        skippedNew++;
                        continue; // 新本子，跳过
                    }
                } catch (java.text.ParseException ignored) {
                    // 解析失败则不跳过，继续获取
                }
            }
            items.add(di);
        }
        Log.e("FavCount", "待获取: " + items.size() + ", 已有收藏数跳过: " + (mHost.getBackList().size() - items.size() - skippedNew) + ", 新本子跳过: " + skippedNew + ", 总计: " + mHost.getBackList().size());
        if (items.isEmpty()) {
            String msg = skippedNew > 0
                    ? mHost.getString(R.string.download_refresh_fav_count_done, 0) + "（跳过" + skippedNew + "个7天内新本子）"
                    : mHost.getString(R.string.download_refresh_fav_count_done, 0);
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
            return;
        }

        // 显示进度对话框（可取消）
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final List<GalleryInfo> galleryInfoList = new ArrayList<>(items);

        ProgressDialog progressDialog = new ProgressDialog(ctx);
        progressDialog.setTitle(R.string.download_refresh_fav_count);
        progressDialog.setMessage(mHost.getString(R.string.download_refresh_fav_count_progress, 0, items.size()));
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(items.size());
        progressDialog.setCancelable(true);
        progressDialog.setOnCancelListener(dialog -> cancelled.set(true));
        progressDialog.setButton(DialogInterface.BUTTON_NEGATIVE,
                ctx.getString(android.R.string.cancel),
                (dialog, which) -> dialog.cancel());
        progressDialog.show();

        AsyncTask<Void, Integer, int[]> task = new AsyncTask<Void, Integer, int[]>() {
            @Override
            protected int[] doInBackground(Void... voids) {
                return EhEngine.batchGetFavoriteCounts(
                        EhApplication.getOkHttpClient(ctx),
                        galleryInfoList,
                        3,    // 3并发
                        1000, // 每批间隔1秒
                        cancelled,
                        (current, total) -> publishProgress(current, total));
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                if (values.length >= 2) {
                    progressDialog.setProgress(values[0]);
                    progressDialog.setMessage(mHost.getString(R.string.download_refresh_fav_count_progress, values[0], values[1]));
                }
            }

            @Override
            protected void onPostExecute(int[] result) {
                progressDialog.dismiss();
                // 持久化到磁盘（包括中途中止时已获取的）
                if (mHost.getBackList() != null) {
                    com.hippo.ehviewer.util.FavCountStore.saveFrom(mHost.getBackList());
                }
                // 刷新列表显示
                if (mHost.getList() != null) {
                    mHost.updateAdapter();
                }
                if (result != null) {
                    int success = result[0];
                    int total = result[1];
                    if (cancelled.get()) {
                        Toast.makeText(ctx, "已中止，获取了 " + success + "/" + total + " 个", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(ctx, mHost.getString(R.string.download_refresh_fav_count_done, success), Toast.LENGTH_SHORT).show();
                    }
                }
            }
        };

        task.execute();
    }

    public void convertBatchAsync(List<DownloadInfo> infos, boolean toCbz) {
        Context ctx = mHost.getEHContext();
        if (ctx == null) return;
        Toast.makeText(ctx, mHost.getString(R.string.convert_in_progress, 0, infos.size()), Toast.LENGTH_SHORT).show();
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
                if (ctx!=null) Toast.makeText(ctx, mHost.getString(R.string.convert_in_progress, values[0], infos.size()), Toast.LENGTH_SHORT).show();
            }
            @Override protected void onPostExecute(Integer result) {
                if (ctx!=null) {
                    if (failed.isEmpty()) {
                        Toast.makeText(ctx, mHost.getString(R.string.convert_done, result, infos.size()), Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(ctx, mHost.getString(R.string.convert_done, result, infos.size()), Toast.LENGTH_LONG).show();
                        for (String f : failed) {
                            Toast.makeText(ctx, mHost.getString(R.string.convert_failed_for, f), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
                // 退出选择模式并刷新
                if (mHost.getRecyclerView()!=null) mHost.getRecyclerView().outOfCustomChoiceMode();
                mHost.updateForLabel();
                mHost.updateView();
            }
        }.executeOnExecutor(IoThreadPoolExecutor.getInstance());
    }


    public void onClickSecondaryFab(FabLayout view, FloatingActionButton fab, int position) {
        Context context = mHost.getEHContext();
        Activity activity = mHost.getActivity2();
        MyEasyRecyclerView recyclerView = mHost.getRecyclerView();
        if (null == context || null == activity || null == recyclerView) {
            return;
        }

        if (0 == position) {
            recyclerView.checkAll();
        } else {
            List<DownloadInfo> list = mHost.getList();
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
                    DownloadInfo info = mHost.getDownloadInfoAtAdapterPosition(stateArray.keyAt(i));
                    if (info == null) continue;
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
                    if (null != mHost.getDownloadManager()) {
                        mHost.getDownloadManager().stopRangeDownload(gidList);
                    }
                    // Cancel check mode
                    recyclerView.outOfCustomChoiceMode();
                    break;
                }
                case 3: { // Delete
                    if (downloadInfoList.isEmpty()) {
                        break;
                    }
                    final LongList selectedGidList = gidList; // for lambda
                    final List<DownloadInfo> selectedInfoList = downloadInfoList; // for lambda

                    // 三个单选选项
                    final String[] options = new String[]{
                            mHost.getString(R.string.download_remove_option_remove_all),
                            mHost.getString(R.string.download_remove_option_keep_item),
                            mHost.getString(R.string.download_remove_option_images_only)
                    };

                    // 默认选中第一项
                    final int[] selectedOption = {0};

                    // 创建自定义布局
                    android.view.View dialogView = android.view.LayoutInflater.from(context)
                            .inflate(R.layout.dialog_delete_download, null);
                    android.widget.ListView listView = dialogView.findViewById(R.id.list_view);
                    android.widget.CheckBox deleteReadingProgressCheckBox = dialogView.findViewById(R.id.checkbox_delete_reading_progress);

                    // 设置单选列表
                    listView.setAdapter(new android.widget.ArrayAdapter<>(context,
                            R.layout.item_select_dialog_radio, options));
                    listView.setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE);
                    listView.setItemChecked(0, true);

                    // 设置复选框文本和默认状态（默认选中第一项时勾选）
                    deleteReadingProgressCheckBox.setText(R.string.download_remove_option_delete_reading_progress);
                    deleteReadingProgressCheckBox.setChecked(true); // 默认选中第一项，所以默认勾选

                    // 监听列表选择变化，动态更新复选框默认状态
                    listView.setOnItemClickListener((parent, itemView, itemPosition, id) -> {
                        selectedOption[0] = itemPosition;
                        // 更新复选框默认状态：选择第一项时默认勾选，其他选项默认不勾选
                        deleteReadingProgressCheckBox.setChecked(itemPosition == 0);
                    });

                    new AlertDialog.Builder(context)
                            .setTitle(R.string.download_remove_dialog_title)
                            .setView(dialogView)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(android.R.string.ok, (d, w) -> {
                                // 退出选择模式
                                if (mHost.getRecyclerView() != null) {
                                    mHost.getRecyclerView().outOfCustomChoiceMode();
                                }

                                int option = selectedOption[0];
                                boolean deleteReadingProgress = deleteReadingProgressCheckBox.isChecked();

                                // 0: 移除下载项并删除文件夹
                                // 1: 仅删除文件夹（保留下载项）
                                // 2: 仅删除本地图片

                                if (option == 0 || option == 1) {
                                    // 删除整个文件夹（本地 + SMB）
                                    List<UniFile> fileList = new ArrayList<>();
                                    List<DownloadInfo> smbInfoList = new ArrayList<>();

                                    for (DownloadInfo info : selectedInfoList) {
                                        // 处理本地文件
                                        UniFile dir = getGalleryDownloadDir(info);
                                        if (dir != null) {
                                            fileList.add(dir);
                                        }
                                        // 清除路径映射
                                        EhDB.removeDownloadDirname(info.gid);

                                        // 检查是否在 SMB 上
                                        com.hippo.ehviewer.smb.Client.Target smbCheck =
                                            com.hippo.ehviewer.smb.SmbPathResolver.resolve(info.gid);
                                        if (smbCheck != null) {
                                            smbInfoList.add(info);
                                        }

                                        if (option == 1) {
                                            // 仅删除文件夹（保留下载项）：重置状态以便重新下载
                                            info.state = DownloadInfo.STATE_NONE;
                                            info.finished = 0;
                                            info.downloaded = 0;
                                            info.speed = 0;
                                            info.remaining = 0;
                                            if (info.total < 0) info.total = 0;
                                            EhDB.putDownloadInfo(info);
                                            // 更新存储位置缓存：文件夹已删除
                                            StorageDetector.updateCache(info.gid, StorageDetector.StorageLocation.UNKNOWN);
                                        }
                                    }

                                    // 删除本地文件
                                    if (!fileList.isEmpty()) {
                                        deleteFileAsync(fileList.toArray(new UniFile[0]));
                                    }

                                    // 删除 SMB 文件（异步）
                                    if (!smbInfoList.isEmpty()) {
                                        final int finalOption = option;
                                        new android.os.AsyncTask<Void, Void, Integer>() {
                                            @Override protected Integer doInBackground(Void... voids) {
                                                int count = 0;
                                                for (DownloadInfo info : smbInfoList) {
                                                    com.hippo.ehviewer.smb.Client.Target target =
                                                        com.hippo.ehviewer.smb.SmbPathResolver.resolve(info.gid);
                                                    if (target == null) continue;

                                                    try {
                                                        // 获取服务器密码
                                                        com.hippo.ehviewer.smb.SmbServer server =
                                                            com.hippo.ehviewer.smb.SmbServerStore.INSTANCE.findByAuthority(target.getAuthority());
                                                        if (server == null) continue;

                                                        com.hippo.ehviewer.smb.Client.INSTANCE.withTempPassword(
                                                            target.getAuthority(),
                                                            server.getPassword(),
                                                            () -> {
                                                                try {
                                                                    deleteSmbDirectoryRecursively(target);
                                                                    return true;
                                                                } catch (Throwable t) {
                                                                    android.util.Log.e("DownloadsScene", "删除 SMB 目录失败: gid=" + info.gid, t);
                                                                    return false;
                                                                }
                                                            }
                                                        );

                                                        // option 0 或 option 1：已删除远端目录
                                                        com.hippo.ehviewer.smb.SmbStorageTracker.INSTANCE.markLocal(info.gid);

                                                        count++;
                                                    } catch (Exception e) {
                                                        android.util.Log.e("DownloadsScene", "删除 SMB 文件异常: gid=" + info.gid, e);
                                                    }
                                                }
                                                return count;
                                            }
                                            @Override protected void onPostExecute(Integer count) {
                                                if (count > 0) {
                                                    Toast.makeText(context, "已删除 " + count + " 个 SMB 目录", Toast.LENGTH_SHORT).show();
                                                }
                                            }
                                        }.executeOnExecutor(com.hippo.util.IoThreadPoolExecutor.getInstance());
                                    }
                                }

                                if (option == 2) {
                                    // 仅删除本地图片文件（保留 info.json 等其他文件）
                                    List<DownloadInfo> smbInfoList = new ArrayList<>();

                                    for (DownloadInfo info : selectedInfoList) {
                                        // 处理本地文件
                                        UniFile dir = getGalleryDownloadDir(info);
                                        if (dir != null) {
                                            UniFile[] files = dir.listFiles();
                                            if (files != null) {
                                                List<UniFile> imageFiles = new ArrayList<>();
                                                for (UniFile file : files) {
                                                    if (file.isFile()) {
                                                        String name = file.getName();
                                                        if (name != null && (name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                                                            name.endsWith(".png") || name.endsWith(".gif") ||
                                                            name.endsWith(".webp") || name.endsWith(".bmp"))) {
                                                            imageFiles.add(file);
                                                        }
                                                    }
                                                }
                                                if (!imageFiles.isEmpty()) {
                                                    deleteFileAsync(imageFiles.toArray(new UniFile[0]));
                                                }
                                            }
                                        }

                                        // 检查是否在 SMB 上
                                        com.hippo.ehviewer.smb.Client.Target smbCheck =
                                            com.hippo.ehviewer.smb.SmbPathResolver.resolve(info.gid);
                                        if (smbCheck != null) {
                                            smbInfoList.add(info);
                                        }

                                        // 重置下载状态
                                        info.state = DownloadInfo.STATE_NONE;
                                        info.finished = 0;
                                        info.downloaded = 0;
                                        info.speed = 0;
                                        info.remaining = 0;
                                        if (info.total < 0) info.total = 0;
                                        EhDB.putDownloadInfo(info);
                                    }

                                    // 删除 SMB 图片文件（异步）
                                    if (!smbInfoList.isEmpty()) {
                                        new android.os.AsyncTask<Void, Void, Integer>() {
                                            @Override protected Integer doInBackground(Void... voids) {
                                                int count = 0;
                                                for (DownloadInfo info : smbInfoList) {
                                                    com.hippo.ehviewer.smb.Client.Target target =
                                                        com.hippo.ehviewer.smb.SmbPathResolver.resolve(info.gid);
                                                    if (target == null) continue;

                                                    try {
                                                        // 获取服务器密码
                                                        com.hippo.ehviewer.smb.SmbServer server =
                                                            com.hippo.ehviewer.smb.SmbServerStore.INSTANCE.findByAuthority(target.getAuthority());
                                                        if (server == null) continue;

                                                        com.hippo.ehviewer.smb.Client.INSTANCE.withTempPassword(
                                                            target.getAuthority(),
                                                            server.getPassword(),
                                                            () -> {
                                                                try {
                                                                    deleteSmbImageFilesOnly(target);
                                                                    return true;
                                                                } catch (Throwable t) {
                                                                    android.util.Log.e("DownloadsScene", "删除 SMB 图片失败: gid=" + info.gid, t);
                                                                    return false;
                                                                }
                                                            }
                                                        );

                                                        // 保留 SMB 映射（用户可能想重新下载）
                                                        count++;
                                                    } catch (Exception e) {
                                                        android.util.Log.e("DownloadsScene", "删除 SMB 图片异常: gid=" + info.gid, e);
                                                    }
                                                }
                                                return count;
                                            }
                                            @Override protected void onPostExecute(Integer count) {
                                                if (count > 0) {
                                                    Toast.makeText(context, "已删除 " + count + " 个 SMB 目录的图片", Toast.LENGTH_SHORT).show();
                                                }
                                            }
                                        }.executeOnExecutor(com.hippo.util.IoThreadPoolExecutor.getInstance());
                                    }
                                }

                                if (option == 0) {
                                    // 移除下载项
                                    if (mHost.getDownloadManager() != null) {
                                        mHost.getDownloadManager().deleteRangeDownload(selectedGidList);
                                    }
                                }

                                // 删除阅读进度（如果用户勾选了）
                                if (deleteReadingProgress) {
                                    for (DownloadInfo info : selectedInfoList) {
                                        EhApplication.getSpiderInfoRepository(context).delete(info.gid, context);
                                        mHost.getSpiderInfoMap().remove(info.gid);
                                    }
                                }

                                // 刷新界面
                                mHost.updateForLabel();
                                mHost.updateView();
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
                    labelList.add(mHost.getString(R.string.default_download_label_name));
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
                    if (mHost.getList() == null || mHost.getList().isEmpty()) {
                        return;
                    }
                    mHost.onClickPrimaryFab(mHost.getFabLayout(), null);
                    viewRandom();
                    break;
                }
                case 6: { // Toggle drag-and-drop
                    setDragEnable(fab);
                    break;
                }
                case 7: { // SMB Migration (now last)
                    if (downloadInfoList.isEmpty()){
                        break;
                    }
                    
                    // 显示选择对话框：移动到SMB 或 从SMB移动到本地
                    final Context ctxFinal = context;
                    final List<DownloadInfo> infosFinal = downloadInfoList;
                    
                    CharSequence[] migrationOptions = new CharSequence[]{
                        "移动到 SMB 服务器",
                        "从 SMB 移动到本地"
                    };
                    
                    new AlertDialog.Builder(context)
                            .setTitle("SMB 存储迁移")
                            .setItems(migrationOptions, (d, which) -> {
                                if (which == 0) {
                                    // 移动到 SMB
                                    java.util.List<com.hippo.ehviewer.smb.SmbServer> servers = com.hippo.ehviewer.smb.SmbServerStore.INSTANCE.list();
                                    if (servers == null || servers.isEmpty()) {
                                        Toast.makeText(ctxFinal, "请先在 设置>高级>添加 SMB 服务器", Toast.LENGTH_LONG).show();
                                        return;
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

                                    new AlertDialog.Builder(ctxFinal)
                                            .setTitle("选择目标 SMB 服务器")
                                            .setItems(choices, (d2, whichIdx) -> {
                                                com.hippo.ehviewer.smb.SmbServer server = servers.get(whichIdx);
                                                migrateToSmbAsync(ctxFinal, infosFinal, server);
                                            })
                                            .show();
                                } else if (which == 1) {
                                    // 从 SMB 移动到本地
                                    // 筛选出在SMB上的下载项
                                    List<DownloadInfo> smbInfos = new ArrayList<>();
                                    for (DownloadInfo info : infosFinal) {
                                        com.hippo.ehviewer.smb.Client.Target smbCheck =
                                            com.hippo.ehviewer.smb.SmbPathResolver.resolve(info.gid);
                                        if (smbCheck != null) {
                                            smbInfos.add(info);
                                        }
                                    }
                                    
                                    if (smbInfos.isEmpty()) {
                                        Toast.makeText(ctxFinal, "所选漫画均不在 SMB 上", Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    
                                    new AlertDialog.Builder(ctxFinal)
                                            .setTitle("从 SMB 移动到本地")
                                            .setMessage("确定要将 " + smbInfos.size() + " 个漫画从 SMB 移动到本地存储吗？")
                                            .setNegativeButton(android.R.string.cancel, null)
                                            .setPositiveButton(android.R.string.ok, (d2, w) -> {
                                                moveFromSmbToLocalAsync(ctxFinal, smbInfos);
                                            })
                                            .show();
                                }
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
        Context context = mHost.getEHContext();
        if (null == context) return;
        if (DRAG_ENABLE) {
            fab.setImageDrawable(ResourcesCompat.getDrawable(mHost.getResources(), R.drawable.v_mobile_hand_left_x24, context.getTheme()));
        } else {
            fab.setImageDrawable(ResourcesCompat.getDrawable(mHost.getResources(), R.drawable.v_mobile_hand_left_off_x24, context.getTheme()));
        }
//        mHost.getDragDropManager().cancelDrag(dragEnable);
    }

    private void migrateToSmbAsync(Context context, List<DownloadInfo> infos, com.hippo.ehviewer.smb.SmbServer server) {
        // 取消选择模式
        if (mHost.getRecyclerView() != null) {
            mHost.getRecyclerView().outOfCustomChoiceMode();
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
                            // 删除本地目录以实现”移动”效果
                            dir.delete();
                            com.hippo.ehviewer.smb.SmbStorageTracker.INSTANCE.markOnSmb(info.gid);
                            // 更新存储位置缓存：从本地迁移到 SMB
                            StorageDetector.updateCache(info.gid, StorageDetector.StorageLocation.SMB);
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
                mHost.updateForLabel();
                mHost.updateView();
            }
        }.executeOnExecutor(com.hippo.util.IoThreadPoolExecutor.getInstance());
    }

    private void moveFromSmbToLocalAsync(Context context, List<DownloadInfo> infos) {
        // 取消选择模式
        if (mHost.getRecyclerView() != null) {
            mHost.getRecyclerView().outOfCustomChoiceMode();
        }
        
        Toast.makeText(context, "开始从 SMB 移动到本地...", Toast.LENGTH_SHORT).show();
        
        new android.os.AsyncTask<Void, Integer, Integer>() {
            @Override protected Integer doInBackground(Void... voids) {
                int okCount = 0;
                for (DownloadInfo info : infos) {
                    try {
                        // 通过 dirname 推导 SMB 路径
                        com.hippo.ehviewer.smb.Client.Target smbTarget =
                            com.hippo.ehviewer.smb.SmbPathResolver.resolve(info.gid);
                        if (smbTarget == null) continue;

                        // 获取对应的 SMB 服务器配置（用于密码）
                        com.hippo.ehviewer.smb.SmbServer server =
                            com.hippo.ehviewer.smb.SmbServerStore.INSTANCE.findByAuthority(smbTarget.getAuthority());
                        if (server == null) {
                            android.util.Log.e("DownloadsScene", "找不到匹配的 SMB 服务器配置");
                            continue;
                        }
                        
                        // 创建本地目标目录
                        com.hippo.unifile.UniFile localDir = Settings.getDownloadLocation();
                        if (localDir == null) continue;
                        
                        // 从 SMB 路径中提取文件夹名称
                        String dirname = smbTarget.getPathInShare();
                        if (dirname != null && dirname.contains("\\")) {
                            String[] parts = dirname.split("\\\\");
                            dirname = parts[parts.length - 1];
                        } else if (dirname != null && dirname.contains("/")) {
                            String[] parts = dirname.split("/");
                            dirname = parts[parts.length - 1];
                        }
                        
                        if (dirname == null || dirname.isEmpty()) {
                            dirname = String.valueOf(info.gid);
                        }
                        
                        com.hippo.unifile.UniFile targetDir = localDir.createDirectory(dirname);
                        if (targetDir == null || !targetDir.ensureDir()) {
                            android.util.Log.e("DownloadsScene", "无法创建本地目录: " + dirname);
                            continue;
                        }
                        
                        // 从 SMB 下载文件到本地
                        boolean success = com.hippo.ehviewer.smb.Client.INSTANCE.withTempPassword(
                            smbTarget.getAuthority(), 
                            server.getPassword(), 
                            () -> {
                                try {
                                    java.util.List<com.hippo.ehviewer.smb.Client.RemoteDirEntry> entries = 
                                        com.hippo.ehviewer.smb.Client.INSTANCE.listDirectory(smbTarget);
                                    if (entries == null || entries.isEmpty()) {
                                        return false;
                                    }
                                    
                                    for (com.hippo.ehviewer.smb.Client.RemoteDirEntry entry : entries) {
                                        // 只下载文件，跳过目录
                                        if (entry.isDirectory()) continue;
                                        
                                        String fileName = entry.getName();
                                        com.hippo.ehviewer.smb.Client.Target fileTarget = 
                                            new com.hippo.ehviewer.smb.Client.Target(
                                                smbTarget.getAuthority(),
                                                smbTarget.getShare(),
                                                joinPath(smbTarget.getPathInShare(), fileName)
                                            );
                                        
                                        com.hippo.unifile.UniFile localFile = targetDir.createFile(fileName);
                                        if (localFile == null) continue;
                                        
                                        java.io.InputStream is = null;
                                        java.io.OutputStream os = null;
                                        try {
                                            is = com.hippo.ehviewer.smb.Client.INSTANCE.openInputStream(fileTarget);
                                            os = localFile.openOutputStream();
                                            
                                            byte[] buffer = new byte[8192];
                                            int bytesRead;
                                            while ((bytesRead = is.read(buffer)) != -1) {
                                                os.write(buffer, 0, bytesRead);
                                            }
                                            os.flush();
                                        } finally {
                                            com.hippo.lib.yorozuya.IOUtils.closeQuietly(is);
                                            com.hippo.lib.yorozuya.IOUtils.closeQuietly(os);
                                        }
                                    }
                                    return true;
                                } catch (Throwable t) {
                                    t.printStackTrace();
                                    return false;
                                }
                            }
                        );
                        
                        if (success) {
                            // 更新下载路径
                            com.hippo.ehviewer.EhDB.putDownloadDirname(info.gid, dirname);
                            com.hippo.ehviewer.smb.SmbStorageTracker.INSTANCE.markLocal(info.gid);
                            // 更新存储位置缓存：从 SMB 迁移到本地
                            StorageDetector.updateCache(info.gid, StorageDetector.StorageLocation.LOCAL);
                            
                            // 可选：删除 SMB 上的文件（实现"移动"效果）
                            final String finalDirname = dirname;
                            try {
                                com.hippo.ehviewer.smb.Client.INSTANCE.withTempPassword(
                                    smbTarget.getAuthority(),
                                    server.getPassword(),
                                    () -> {
                                        try {
                                            deleteSmbDirectoryRecursively(smbTarget);
                                            return true;
                                        } catch (Throwable t) {
                                            android.util.Log.e("DownloadsScene", "删除 SMB 目录失败: " + finalDirname, t);
                                            return false;
                                        }
                                    }
                                );
                            } catch (Throwable t) {
                                // 删除失败不影响主流程
                                android.util.Log.e("DownloadsScene", "删除 SMB 目录异常: " + finalDirname, t);
                            }
                            
                            okCount++;
                        }
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
                return okCount;
            }
            
            @Override protected void onPostExecute(Integer okCount) {
                Toast.makeText(context, "已从 SMB 移动到本地：" + okCount + "/" + infos.size(), Toast.LENGTH_LONG).show();
                // 刷新界面
                mHost.updateForLabel();
                mHost.updateView();
            }
        }.executeOnExecutor(com.hippo.util.IoThreadPoolExecutor.getInstance());
    }

    /**
     * 递归删除 SMB 目录及其所有内容
     * 参考 MaterialFiles 的实现，先删除文件，再删除子目录，最后删除目录本身
     */
    private static void deleteSmbDirectoryRecursively(com.hippo.ehviewer.smb.Client.Target target) throws Exception {
        try {
            // 列出目录内容
            java.util.List<com.hippo.ehviewer.smb.Client.RemoteDirEntry> entries = 
                com.hippo.ehviewer.smb.Client.INSTANCE.listDirectory(target);
            
            if (entries != null && !entries.isEmpty()) {
                // 先删除所有文件和子目录
                for (com.hippo.ehviewer.smb.Client.RemoteDirEntry entry : entries) {
                    com.hippo.ehviewer.smb.Client.Target entryTarget = 
                        new com.hippo.ehviewer.smb.Client.Target(
                            target.getAuthority(),
                            target.getShare(),
                            joinPath(target.getPathInShare(), entry.getName())
                        );
                    
                    if (entry.isDirectory()) {
                        // 递归删除子目录
                        deleteSmbDirectoryRecursively(entryTarget);
                    } else {
                        // 删除文件
                        com.hippo.ehviewer.smb.Client.INSTANCE.delete(entryTarget);
                    }
                }
            }
            
            // 最后删除目录本身（此时目录应该已经为空）
            com.hippo.ehviewer.smb.Client.INSTANCE.deleteDirectory(target);
        } catch (Exception e) {
            android.util.Log.e("DownloadsScene", "删除 SMB 路径失败: " + target.getPathInShare(), e);
            throw e;
        }
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

    /**
     * 只删除 SMB 目录中的图片文件（保留其他文件如 info.json）
     */
    private static void deleteSmbImageFilesOnly(com.hippo.ehviewer.smb.Client.Target target) throws Exception {
        try {
            java.util.List<com.hippo.ehviewer.smb.Client.RemoteDirEntry> entries = 
                com.hippo.ehviewer.smb.Client.INSTANCE.listDirectory(target);
            
            if (entries != null && !entries.isEmpty()) {
                for (com.hippo.ehviewer.smb.Client.RemoteDirEntry entry : entries) {
                    if (!entry.isDirectory()) {
                        String name = entry.getName();
                        if (name != null && (name.endsWith(".jpg") || name.endsWith(".jpeg") || 
                            name.endsWith(".png") || name.endsWith(".gif") || 
                            name.endsWith(".webp") || name.endsWith(".bmp"))) {
                            com.hippo.ehviewer.smb.Client.Target fileTarget = 
                                new com.hippo.ehviewer.smb.Client.Target(
                                    target.getAuthority(),
                                    target.getShare(),
                                    joinPath(target.getPathInShare(), name)
                                );
                            com.hippo.ehviewer.smb.Client.INSTANCE.delete(fileTarget);
                        }
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.e("DownloadsScene", "删除 SMB 图片失败: " + target.getPathInShare(), e);
            throw e;
        }
    }

    private void viewRandom() {
        List<DownloadInfo> list = mHost.getList();
        if (list == null) {
            return;
        }
        int position = (int) (Math.random() * list.size());
        if (position < 0 || position >= list.size()) {
            return;
        }
        Activity activity = mHost.getActivity2();
        if (null == activity || null == mHost.getRecyclerView()) {
            return;
        }

        Intent intent = new Intent(activity, GalleryActivity.class);
        intent.setAction(GalleryActivity.ACTION_EH);
        intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, list.get(position));
        mHost.launchGalleryActivity(intent);
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
        }.executeOnExecutor(IoThreadPoolExecutor.Companion.getInstance(), files);
    }

    private static void deleteGalleryFilesAsync(List<? extends GalleryInfo> galleryInfoList) {
        new AsyncTask<List<? extends GalleryInfo>, Void, Void>() {
            @Override
            protected Void doInBackground(List<? extends GalleryInfo>... params) {
                for (GalleryInfo info : params[0]) {
                    UniFile file = getGalleryDownloadDir(info);
                    EhDB.removeDownloadDirname(info.gid);
                    if (file != null) {
                        file.delete();
                    }
                }
                return null;
            }
        }.executeOnExecutor(IoThreadPoolExecutor.Companion.getInstance(), galleryInfoList);
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
            if (null != mHost.getDownloadManager()) {
                mHost.getDownloadManager().deleteDownload(mGalleryInfo.gid);
            }

            // Delete image files
            boolean checked = mBuilder.isChecked();
            Settings.putRemoveImageFiles(checked);
            if (checked) {
                UniFile file = getExistingGalleryDownloadDir(mGalleryInfo);
                EhDB.removeDownloadDirname(mGalleryInfo.gid);
                if (file != null) {
                    deleteFileAsync(file);
                } else {
                    deleteGalleryFilesAsync(Collections.singletonList(mGalleryInfo));
                }
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
            if (mHost.getRecyclerView() != null) {
                mHost.getRecyclerView().outOfCustomChoiceMode();
            }

            // Delete
            if (null != mHost.getDownloadManager()) {
                mHost.getDownloadManager().deleteRangeDownload(mGidList);
            }

            // Delete image files
            boolean checked = mBuilder.isChecked();
            Settings.putRemoveImageFiles(checked);
            if (checked) {
                deleteGalleryFilesAsync(mDownloadInfoList);
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
            Context context = mHost.getEHContext();
            if (null == context) {
                return;
            }
            if (null != mHost.getRecyclerView()) {
                mHost.getRecyclerView().outOfCustomChoiceMode();
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
//        if (mHost.getRecyclerView() == null) {
//            return;
//        }
//
//        for (int i = 0; i < mHost.getRecyclerView().getChildCount(); i++) {
//            RecyclerView.ViewHolder holder = mHost.getRecyclerView().getChildViewHolder(mHost.getRecyclerView().getChildAt(i));
//            if (holder instanceof DownloadAdapter.DownloadHolder) {
//                DownloadAdapter.DownloadHolder downloadHolder = (DownloadAdapter.DownloadHolder) holder;
//                // thumb 始终可见，拖拽功能已直接附加到thumb上
//                downloadHolder.thumb.setVisibility(View.VISIBLE);
//            }
//        }

    /**
     * 显示"移动位置"对话框
     * @param context 上下文
     * @param sourceInfo 源下载项信息
     * @param sourcePosition 源项在列表中的位置
     */
    public void showMoveToPositionDialog(Context context, DownloadInfo sourceInfo, int sourcePosition) {
        // 使用对话框消息显示提示文本（支持自动换行），输入框只用于输入 gid
        android.widget.EditText editText = new android.widget.EditText(context);
        editText.setHint("");
        editText.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editText.setSingleLine(true);
        // 居中显示输入的数字
        editText.setGravity(Gravity.CENTER);
        editText.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        // 使输入框宽度填充对话框，便于居中显示
        editText.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(R.string.move_to_position_title)
                .setMessage(mHost.getString(R.string.move_to_position_hint))
                .setView(editText)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    // 先收起键盘，避免窗口重建导致布局异常
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(editText.getWindowToken(), 0);
                    }
                    String gidInput = editText.getText().toString().trim();
                    moveToPosition(context, sourceInfo, sourcePosition, gidInput);
                })
                .setNegativeButton(android.R.string.cancel, null);

        // 显示对话框
        AlertDialog dialog = builder.show();
        // 对话框关闭时确保键盘收起
        dialog.setOnDismissListener(d -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(editText.getWindowToken(), 0);
            }
        });
    }

    /**
     * 执行移动操作
     * @param context 上下文
     * @param sourceInfo 源下载项
     * @param sourcePosition 源项在列表中的位置
     * @param targetGidInput 目标gid输入（可为空或输入0）
     */
    private void moveToPosition(Context context, DownloadInfo sourceInfo, int sourcePosition, String targetGidInput) {
        if (mHost.getList() == null || mHost.getBackList() == null) {
            Toast.makeText(context, R.string.move_to_position_error, Toast.LENGTH_SHORT).show();
            return;
        }

        if (targetGidInput.isEmpty()) {
            // 留空，移动到默认下载标签的最前端（最新位置）
            if (mHost.getDownloadManager() != null) {
                // 使用用户设置的默认下载标签
                String defaultLabel = Settings.getDefaultDownloadLabel();

                // 先更新源项目的时间为当前时间，这样 changeLabel 排序后会在最前面
                sourceInfo.time = System.currentTimeMillis();
                EhDB.putDownloadInfo(sourceInfo);

                List<DownloadInfo> singleInfoList = new ArrayList<>();
                singleInfoList.add(sourceInfo);
                mHost.getDownloadManager().changeLabel(singleInfoList, defaultLabel);

                // 如果当前不在默认下载标签视图，切换到默认下载标签
                if (!ObjectUtils.equal(mHost.getLabel(), defaultLabel)) {
                    mHost.setLabel(defaultLabel);
                }
                mHost.updateForLabel();

                Toast.makeText(context, R.string.move_to_position_success, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, R.string.move_to_position_error, Toast.LENGTH_SHORT).show();
            }
            return;
        } else if (targetGidInput.equals("0")) {
            // 输入0，移到当前标签列表的最前面（该标签最新的位置）
            sourceInfo.time = System.currentTimeMillis() + 1;
            EhDB.putDownloadInfo(sourceInfo);
            mHost.getBackList().sort((a, b) -> Long.compare(b.time, a.time));
            if (mHost.getList() != mHost.getBackList()) {
                int srcIdx = -1;
                for (int i = 0; i < mHost.getList().size(); i++) {
                    if (mHost.getList().get(i).gid == sourceInfo.gid) { srcIdx = i; break; }
                }
                if (srcIdx != -1) mHost.getList().remove(srcIdx);
                mHost.getList().add(0, sourceInfo);
            }
            mHost.updateForLabel();
            Toast.makeText(context, R.string.move_to_position_success, Toast.LENGTH_SHORT).show();
            return;
        } else {
            // 输入GID，移到该项后面
            try {
                long targetGid = Long.parseLong(targetGidInput);

                // 在 mHost.getBackList() 中找到目标项和源项
                int targetIdx = -1;
                int sourceIdx = -1;
                for (int i = 0; i < mHost.getBackList().size(); i++) {
                    if (mHost.getBackList().get(i).gid == targetGid) {
                        targetIdx = i;
                    }
                    if (mHost.getBackList().get(i).gid == sourceInfo.gid) {
                        sourceIdx = i;
                    }
                }

                if (targetIdx == -1) {
                    Toast.makeText(context, R.string.move_to_position_error, Toast.LENGTH_SHORT).show();
                    return;
                }

                // 避免移到相邻位置（已在目标后面）
                if (sourceIdx == targetIdx + 1) {
                    return;
                }

                // 计算源项的新 time：使其排在目标项之后
                // 列表按 time 降序排列，源的 time 需要小于目标的 time
                long targetTime = mHost.getBackList().get(targetIdx).time;
                long newTime;
                if (targetIdx + 1 < mHost.getBackList().size()) {
                    // 目标有后继项：取目标和后继的 time 中间值
                    newTime = (targetTime + mHost.getBackList().get(targetIdx + 1).time) / 2;
                } else {
                    // 目标是最后一项：取目标 time - 1
                    newTime = targetTime - 1;
                }

                // 更新源项的 time 并写入数据库
                sourceInfo.time = newTime;
                EhDB.putDownloadInfo(sourceInfo);

                // mHost.getBackList() 按 time 降序重排
                mHost.getBackList().sort((a, b) -> Long.compare(b.time, a.time));

                // 同步更新 mHost.getList()（筛选列表）
                if (mHost.getList() != mHost.getBackList()) {
                    // 通过 GID 找到源项在 mHost.getList() 中的位置
                    int sourceIndexInList = -1;
                    for (int i = 0; i < mHost.getList().size(); i++) {
                        if (mHost.getList().get(i).gid == sourceInfo.gid) {
                            sourceIndexInList = i;
                            break;
                        }
                    }
                    if (sourceIndexInList != -1) {
                        mHost.getList().remove(sourceIndexInList);
                        // 用目标 GID 在 mHost.getList() 中定位插入点（插入到目标之后）
                        int insertIndex = mHost.getList().size();
                        for (int i = 0; i < mHost.getList().size(); i++) {
                            if (mHost.getList().get(i).gid == targetGid) {
                                insertIndex = i + 1;
                                break;
                            }
                        }
                        mHost.getList().add(insertIndex, sourceInfo);
                    }
                }
                mHost.updateForLabel();
                Toast.makeText(context, R.string.move_to_position_success, Toast.LENGTH_SHORT).show();
            } catch (NumberFormatException e) {
                Toast.makeText(context, R.string.move_to_position_error, Toast.LENGTH_SHORT).show();
            }
        }
    }
}
