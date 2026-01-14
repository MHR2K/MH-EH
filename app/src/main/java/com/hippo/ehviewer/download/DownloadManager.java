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

package com.hippo.ehviewer.download;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.lib.image.Image;
//import com.hippo.lib.image.Image1;
import com.hippo.unifile.UniFile;
import com.hippo.util.IoThreadPoolExecutor;
import com.hippo.lib.yorozuya.ConcurrentPool;
import com.hippo.lib.yorozuya.MathUtils;
import com.hippo.lib.yorozuya.ObjectUtils;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.lib.yorozuya.collect.LongList;
import com.hippo.lib.yorozuya.collect.SparseIJArray;
import com.hippo.lib.yorozuya.collect.SparseJLArray;
import com.hippo.ehviewer.util.CrashlyticsUtils;
import android.util.SparseArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class DownloadManager implements SpiderQueen.OnSpiderListener {

    private static final String TAG = DownloadManager.class.getSimpleName();

    public static final String DOWNLOAD_INFO_FILENAME = ".ehviewer";
    public static final String DOWNLOAD_INFO_HEADER = "gid,token,title,title_jpn,thumb,category,posted,uploader,rating,rated,simple_lang,simple_tags,thumb_width,thumb_height,span_size,span_index,span_group_index,favorite_slot,favorite_name,pages";

    private final Context mContext;

    // All download info list
    private final LinkedList<DownloadInfo> mAllInfoList;
    // All download info map
    private final SparseJLArray<DownloadInfo> mAllInfoMap;
    // label and info list map, without default label info list
    private final Map<String, LinkedList<DownloadInfo>> mMap;

    private final Map<String, Long> mLabelCountMap;
    // All labels without default label
    private final List<DownloadLabel> mLabelList;
    // Store download info with default label
    private final LinkedList<DownloadInfo> mDefaultInfoList;
    // Store download info wait to start
    private final LinkedList<DownloadInfo> mWaitList;

    private final SpeedReminder mSpeedReminder;

    @Nullable
    private DownloadListener mDownloadListener;
    private final List<DownloadInfoListener> mDownloadInfoListeners;

    @Nullable
    private DownloadInfo mCurrentTask;
    @Nullable
    private SpiderQueen mCurrentSpider;

    private final ConcurrentPool<NotifyTask> mNotifyTaskPool = new ConcurrentPool<>(5);

    public DownloadManager(Context context) {
        mContext = context;

        // Get all labels
        List<DownloadLabel> labels = EhDB.getAllDownloadLabelList();
        mLabelList = labels;

        // Create list for each label
        HashMap<String, LinkedList<DownloadInfo>> map = new HashMap<>();
        mMap = map;
        for (DownloadLabel label : labels) {
            map.put(label.getLabel(), new LinkedList<>());
        }

        // Create default for non tag
        mDefaultInfoList = new LinkedList<>();

        // Get all info
        List<DownloadInfo> allInfoList = EhDB.getAllDownloadInfo();
        mAllInfoList = new LinkedList<>(allInfoList);

        // Create all info map
        SparseJLArray<DownloadInfo> allInfoMap = new SparseJLArray<>(allInfoList.size() + 10);
        mAllInfoMap = allInfoMap;

        for (int i = 0, n = allInfoList.size(); i < n; i++) {
            DownloadInfo info = allInfoList.get(i);

            if (info.archiveUri != null && info.archiveUri.startsWith("content://")) {
                try {
                    Uri uri = Uri.parse(info.archiveUri);
                    mContext.getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception e) {
                    // Permission might already be taken or URI might be invalid
                    android.util.Log.w("DownloadManager", "Failed to restore URI permission for " + info.archiveUri, e);
                }
            }

            // Add to all info map
            allInfoMap.put(info.gid, info);

            // Add to each label list
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list == null) {
                // Can't find the label in label list
                list = new LinkedList<>();
                map.put(info.label, list);
                if (!containLabel(info.label)) {
                    // Add label to DB and list
                    labels.add(EhDB.addDownloadLabel(info.label));
                }
            }
            list.add(info);
        }

        mLabelCountMap = new HashMap<>();

        for (Map.Entry<String, LinkedList<DownloadInfo>> entry : map.entrySet()) {
            mLabelCountMap.put(entry.getKey(), (long) entry.getValue().size());
        }

        mWaitList = new LinkedList<>();
        mSpeedReminder = new SpeedReminder();
        mDownloadInfoListeners = new ArrayList<>();
    }

    public void replaceInfo(DownloadInfo newInfo, DownloadInfo oldInfo) {
        replaceInfo(newInfo, oldInfo, false, -1);
    }

    /**
     * 替换下载信息，支持可选的阅读进度更新
     * @param newInfo 新的下载信息
     * @param oldInfo 旧的下载信息
     * @param updateStartPage 是否更新阅读进度
     * @param startPage 新的阅读进度（仅当 updateStartPage 为 true 时生效）
     */
    public void replaceInfo(DownloadInfo newInfo, DownloadInfo oldInfo, boolean updateStartPage, int startPage) {

        for (int i = 0; i < mAllInfoList.size(); i++) {
            if (oldInfo.gid == mAllInfoList.get(i).gid) {
                mAllInfoList.set(i, newInfo);
                break;
            }
        }
        final List<DownloadInfo> infoList = getInfoListForLabel(oldInfo.label);
        if (infoList != null) {
            for (int i = 0; i < infoList.size(); i++) {
                if (oldInfo.gid == infoList.get(i).gid) {
                    infoList.set(i, newInfo);
                    break;
                }
            }
        }

        mAllInfoMap.remove(oldInfo.gid);
        mAllInfoMap.put(newInfo.gid, newInfo);

        // 保存到数据库
        EhDB.putDownloadInfo(newInfo);

        // 同步更新SpiderInfo的页数字段
        if (newInfo.pages != oldInfo.pages) {
            updateSpiderInfoPages(newInfo, updateStartPage, startPage);
        }

        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onReplace(newInfo, oldInfo);
        }
    }

    private void updateSpiderInfoPages(DownloadInfo info) {
        updateSpiderInfoPages(info, false, -1);
    }

    private void updateSpiderInfoPages(DownloadInfo info, boolean updateStartPage, int startPage) {
        // 异步更新SpiderInfo的页数字段
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                try {
                    GalleryInfo galleryInfo = new GalleryInfo();
                    galleryInfo.gid = info.gid;
                    galleryInfo.token = info.token;
                    galleryInfo.title = info.title;
                    galleryInfo.thumb = info.thumb;
                    galleryInfo.category = info.category;
                    galleryInfo.posted = info.posted;
                    galleryInfo.uploader = info.uploader;
                    galleryInfo.rating = info.rating;

                    UniFile downloadDir = SpiderDen.getGalleryDownloadDir(galleryInfo);
                    if (downloadDir != null && downloadDir.isDirectory()) {
                        // 首先检查目录中的.ehviewer文件
                        UniFile file = downloadDir.findFile(".ehviewer");
                        if (file != null) {
                            SpiderInfo spiderInfo = SpiderInfo.read(file);
                            if (spiderInfo != null) {
                                // 保存旧的页数信息
                                int oldPages = spiderInfo.pages;
                                
                                // 更新页数字段
                                spiderInfo.pages = info.pages;
                                
                                // 如果需要更新阅读进度，则设置 startPage
                                if (updateStartPage && startPage >= 0) {
                                    spiderInfo.startPage = startPage;
                                    Log.i(TAG, "Updated SpiderInfo: pages=" + info.pages + ", startPage=" + startPage + " (old pages=" + oldPages + ") for gid=" + info.gid);
                                } else {
                                    Log.i(TAG, "Updated SpiderInfo pages from " + oldPages + " to: " + info.pages + " for gallery: " + info.gid);
                                }
                                
                                // 重新写入文件
                                spiderInfo.write(file.openOutputStream());
                                
                                // 更新 SpiderQueen 内存中的 SpiderInfo，防止被覆盖回旧值
                                try {
                                    SpiderQueen queen = SpiderQueen.obtainSpiderQueenIfExists(info.gid);
                                    if (queen != null) {
                                        if (updateStartPage && startPage >= 0) {
                                            // 更新 pages 和 startPage
                                            queen.updateSpiderInfoPages(info.pages, startPage);
                                            Log.d(TAG, "[updateSpiderInfoPages] Updated SpiderQueen memory: pages=" + info.pages + ", startPage=" + startPage + " for gid=" + info.gid);
                                        } else {
                                            // 只更新 pages，保留当前的 startPage
                                            int currentStartPage = queen.getStartPage();
                                            queen.updateSpiderInfoPages(info.pages, currentStartPage);
                                            Log.d(TAG, "[updateSpiderInfoPages] Updated SpiderQueen memory pages to " + info.pages + ", kept startPage=" + currentStartPage + " for gid=" + info.gid);
                                        }
                                    } else {
                                        Log.d(TAG, "[updateSpiderInfoPages] No SpiderQueen instance for gid=" + info.gid);
                                    }
                                } catch (Exception ex) {
                                    Log.w(TAG, "[updateSpiderInfoPages] Exception updating SpiderQueen memory for gid=" + info.gid, ex);
                                }

                                // 如果页数减少，删除多余的图片
                                if (info.pages < oldPages) {
                                    deleteExcessImages(downloadDir, info.pages);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to update SpiderInfo pages", e);
                }
                return null;
            }
        }.executeOnExecutor(IoThreadPoolExecutor.getInstance());
    }

    /**
     * 删除文件夹中多余的图片文件
     * @param downloadDir 下载目录
     * @param newPages 新的页数
     */
    private void deleteExcessImages(UniFile downloadDir, int newPages) {
        if (downloadDir == null || !downloadDir.isDirectory()) {
            return;
        }
        
        try {
            // 获取目录中的所有文件
            UniFile[] files = downloadDir.listFiles();
            if (files == null) {
                return;
            }
            
            // 支持的图片扩展名
            String[] supportedExtensions = {".jpg", ".jpeg", ".png", ".gif", ".webp"};
            
            for (UniFile file : files) {
                if (file.isFile()) {
                    String filename = file.getName();
                    if (filename != null) {
                        // 检查文件是否是图片文件
                        boolean isImageFile = false;
                        for (String ext : supportedExtensions) {
                            if (filename.toLowerCase().endsWith(ext)) {
                                isImageFile = true;
                                break;
                            }
                        }
                        
                        if (isImageFile) {
                            // 尝试从文件名中提取页码
                            try {
                                // 文件名格式为: 00000001.jpg, 00000002.png 等
                                String numberPart = filename.substring(0, 8);
                                int pageNumber = Integer.parseInt(numberPart);
                                
                                // 如果页码大于新的页数，删除该文件
                                if (pageNumber > newPages) {
                                    if (file.delete()) {
                                        Log.i(TAG, "Deleted excess image file: " + filename);
                                    } else {
                                        Log.w(TAG, "Failed to delete excess image file: " + filename);
                                    }
                                }
                            } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
                                // 文件名格式不符合预期，跳过
                                Log.d(TAG, "Skipping file with unexpected name format: " + filename);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error deleting excess images", e);
        }
    }

    @Nullable
    private LinkedList<DownloadInfo> getInfoListForLabel(String label) {
        if (label == null) {
            return mDefaultInfoList;
        } else {
            return mMap.get(label);
        }
    }

    public boolean containLabel(String label) {
        if (label == null) {
            return false;
        }

        for (DownloadLabel raw : mLabelList) {
            if (label.equals(raw.getLabel())) {
                return true;
            }
        }

        return false;
    }

    public boolean containDownloadInfo(long gid) {
        return mAllInfoMap.indexOfKey(gid) >= 0;
    }

    @NonNull
    public List<DownloadLabel> getLabelList() {
        return mLabelList;
    }

    @Nullable
    public long getLabelCount(String label) {
        try {
            if (mLabelCountMap.containsKey(label)) {
                return mLabelCountMap.get(label);
            } else {
                return 0;
            }
        } catch (NullPointerException e) {
            CrashlyticsUtils.record(e);
            return 0;
        }
    }

    public List<DownloadInfo> getAllDownloadInfoList() {
        return mAllInfoList;
    }

    @NonNull
    public List<DownloadInfo> getDefaultDownloadInfoList() {
        return mDefaultInfoList;
//        List<DownloadInfo> infoList = new ArrayList<>();
//        int i = 0;
//        while (infoList.size() < 30000) {
//            if (i == mDefaultInfoList.size()) {
//                i = 0;
//            }
//            infoList.add(mDefaultInfoList.get(i));
//            i++;
//        }
//        return infoList;
    }

    @Nullable
    public List<DownloadInfo> getLabelDownloadInfoList(String label) {
        return mMap.get(label);
    }

    public List<GalleryInfo> getDownloadInfoList() {
        return new ArrayList<>(mAllInfoList);
    }

    @Nullable
    public DownloadInfo getDownloadInfo(long gid) {
        return mAllInfoMap.get(gid);
    }

    @Nullable
    public DownloadInfo getNoneDownloadInfo(long gid) {
        if (mCurrentTask != null && mCurrentTask.gid == gid) {
            // Stop current
            stopCurrentDownloadInternal();
        } else {
            // Remove wait
            for (Iterator<DownloadInfo> iterator = mWaitList.iterator(); iterator.hasNext(); ) {
                DownloadInfo info = iterator.next();
                if (info.gid == gid) {
                    info.state = DownloadInfo.STATE_NONE;
                    // Remove from wait list
                    iterator.remove();
                    break;
                }
            }
        }
        return mAllInfoMap.get(gid);
    }

    public int getDownloadState(long gid) {
        DownloadInfo info = mAllInfoMap.get(gid);
        if (null != info) {
            return info.state;
        } else {
            return DownloadInfo.STATE_INVALID;
        }
    }

    public void addDownloadInfoListener(@Nullable DownloadInfoListener downloadInfoListener) {
        mDownloadInfoListeners.add(downloadInfoListener);
    }

    public void removeDownloadInfoListener(@Nullable DownloadInfoListener downloadInfoListener) {
        mDownloadInfoListeners.remove(downloadInfoListener);
    }

    public void setDownloadListener(@Nullable DownloadListener listener) {
        mDownloadListener = listener;
    }

    private void ensureDownload() {
        if (mCurrentTask != null) {
            // Only one download
            return;
        }

        // Get download from wait list
        if (!mWaitList.isEmpty()) {
            DownloadInfo info = mWaitList.removeFirst();
            ensureLocalScaffold(info);
            SpiderQueen spider = SpiderQueen.obtainSpiderQueen(mContext, info, SpiderQueen.MODE_DOWNLOAD);
            mCurrentTask = info;
            mCurrentSpider = spider;
            spider.addOnSpiderListener(this);
            info.state = DownloadInfo.STATE_DOWNLOAD;
            info.speed = -1;
            info.remaining = -1;
            info.total = -1;
            info.finished = 0;
            info.downloaded = 0;
            info.legacy = -1;
            // Update in DB
            EhDB.putDownloadInfo(info);
            // Start speed count
            mSpeedReminder.start();
            // Notify start downloading
            if (mDownloadListener != null) {
                mDownloadListener.onStart(info);
            }
            // Notify state update
            List<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                for (DownloadInfoListener l : mDownloadInfoListeners) {
                    l.onUpdate(info, list, mWaitList);
                }
            }
        }
    }

    /**
     * Ensure download directory and a minimal .ehviewer file exist even if the remote gallery is gone.
     * This only relies on stored metadata in DownloadInfo and does not touch the network.
     */
    private void ensureLocalScaffold(@NonNull DownloadInfo info) {
        // Without gid/token/pages we can't build a valid spider info file.
        if (info.gid <= 0 || info.token == null || info.pages <= 0) {
            return;
        }

        try {
            SpiderDen spiderDen = new SpiderDen(info);
            // Switch to download mode so the directory is created if missing.
            spiderDen.setMode(SpiderQueen.MODE_DOWNLOAD);

            com.hippo.unifile.UniFile dir = spiderDen.getDownloadDir();
            if (dir == null) {
                return;
            }

            if (dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME) != null) {
                return;
            }

            SpiderInfo spiderInfo = new SpiderInfo();
            spiderInfo.gid = info.gid;
            spiderInfo.token = info.token;
            spiderInfo.pages = info.pages;
            spiderInfo.previewPages = 0;
            spiderInfo.previewPerPage = 0;
            spiderInfo.pTokenMap = new SparseArray<>(0);
            spiderInfo.startPage = 0;

            // Write both to download dir and to cache for consistency.
            spiderInfo.writeNewSpiderInfoToLocal(spiderDen, mContext);
        } catch (Throwable t) {
            Log.w(TAG, "ensureLocalScaffold failed", t);
        }
    }

    void startDownload(GalleryInfo galleryInfo, @Nullable String label) {
        if (mCurrentTask != null && mCurrentTask.gid == galleryInfo.gid) {
            // It is current task
            return;
        }

        // Do nothing in the case of a local compressed file.
        if (galleryInfo instanceof DownloadInfo downloadInfo) {
            if (downloadInfo.archiveUri != null && downloadInfo.archiveUri.startsWith("content://")){
                return;
            }
        }

        // Check in download list
        DownloadInfo info = mAllInfoMap.get(galleryInfo.gid);

        if (info != null) { // Get it in download list
            if (info.state != DownloadInfo.STATE_WAIT) {
                // Set state DownloadInfo.STATE_WAIT
                info.state = DownloadInfo.STATE_WAIT;
                // Add to wait list
                mWaitList.add(info);
                // Update in DB
                EhDB.putDownloadInfo(info);
                // Notify state update
                List<DownloadInfo> list = getInfoListForLabel(info.label);
                if (list != null) {
                    for (DownloadInfoListener l : mDownloadInfoListeners) {
                        l.onUpdate(info, list, mWaitList);
                    }
                }
                // Make sure download is running
                ensureDownload();
            }
        } else {
            // It is new download info
            info = new DownloadInfo(galleryInfo);
            info.label = label;
            info.state = DownloadInfo.STATE_WAIT;
            info.time = System.currentTimeMillis();

            // Add to label download list
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list == null) {
                Log.e(TAG, "Can't find download info list with label: " + label);
                return;
            }
            list.addFirst(info);

            // Add to all download list and map
            mAllInfoList.addFirst(info);
            mAllInfoMap.put(galleryInfo.gid, info);

            // Add to wait list
            mWaitList.add(info);

            // Save to
            EhDB.putDownloadInfo(info);

            // Notify
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onAdd(info, list, list.size() - 1);
            }
            // Make sure download is running
            ensureDownload();

            // Add it to history
            EhDB.putHistoryInfo(info);
        }
    }

    void startRangeDownload(LongList gidList) {
        boolean update = false;
        boolean downloadOrder = Settings.getDownloadOrder();
        if (downloadOrder) {
            for (int i = 0, n = gidList.size(); i < n; i++) {
                long gid = gidList.get(i);
                DownloadInfo info = mAllInfoMap.get(gid);
                if (null == info) {
                    Log.d(TAG, "Can't get download info with gid: " + gid);
                    continue;
                }

                if (info.state == DownloadInfo.STATE_NONE ||
                        info.state == DownloadInfo.STATE_FAILED ||
                        info.state == DownloadInfo.STATE_FINISH) {
                    update = true;
                    // Set state DownloadInfo.STATE_WAIT
                    info.state = DownloadInfo.STATE_WAIT;
                    // Add to wait list
                    mWaitList.add(info);
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                }
            }
        } else {
            for (int i = gidList.size(), n = 0; i > n; i--) {
                long gid = gidList.get(i - 1);
                DownloadInfo info = mAllInfoMap.get(gid);
                if (null == info) {
                    Log.d(TAG, "Can't get download info with gid: " + gid);
                    continue;
                }

                if (info.state == DownloadInfo.STATE_NONE ||
                        info.state == DownloadInfo.STATE_FAILED ||
                        info.state == DownloadInfo.STATE_FINISH) {
                    update = true;
                    // Set state DownloadInfo.STATE_WAIT
                    info.state = DownloadInfo.STATE_WAIT;
                    // Add to wait list
                    mWaitList.add(info);
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                }
            }
        }


        if (update) {
            // Notify Listener
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdateAll();
            }
            // Ensure download
            ensureDownload();
        }
    }

    void startAllDownload() {
        boolean update = false;
        // Start all STATE_NONE and STATE_FAILED item
        LinkedList<DownloadInfo> allInfoList = mAllInfoList;
        LinkedList<DownloadInfo> waitList = mWaitList;
        boolean downloadOrder = Settings.getDownloadOrder();
        if (downloadOrder) {
            for (DownloadInfo info : allInfoList) {
                if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED) {
                    update = true;
                    // Set state DownloadInfo.STATE_WAIT
                    info.state = DownloadInfo.STATE_WAIT;
                    // Add to wait list
                    waitList.add(info);
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                }
            }
        } else {
            for (DownloadInfo info : allInfoList) {
                if (info.state == DownloadInfo.STATE_NONE || info.state == DownloadInfo.STATE_FAILED) {
                    update = true;
                    // Set state DownloadInfo.STATE_WAIT
                    info.state = DownloadInfo.STATE_WAIT;
                    // Add to wait list
                    waitList.addFirst(info);
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                }
            }
        }


        if (update) {
            // Notify Listener
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onUpdateAll();
            }
            // Ensure download
            ensureDownload();
        }
    }

    public void addDownload(List<DownloadInfo> downloadInfoList) {
        for (DownloadInfo info : downloadInfoList) {
            if (containDownloadInfo(info.gid)) {
                // Contain
                continue;
            }

            // Ensure download state
            if (DownloadInfo.STATE_WAIT == info.state ||
                    DownloadInfo.STATE_DOWNLOAD == info.state) {
                info.state = DownloadInfo.STATE_NONE;
            }

            // Add to label download list
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (null == list) {
                // Can't find the label in label list
                list = new LinkedList<>();
                mMap.put(info.label, list);
                if (!containLabel(info.label)) {
                    // Add label to DB and list
                    mLabelList.add(EhDB.addDownloadLabel(info.label));
                }
            }
            list.add(info);
            // Sort
            Collections.sort(list, DATE_DESC_COMPARATOR);

            // Add to all download list and map
            mAllInfoList.add(info);
            mAllInfoMap.put(info.gid, info);

            // Save to
            EhDB.putDownloadInfo(info);
        }

        // Sort all download list
        Collections.sort(mAllInfoList, DATE_DESC_COMPARATOR);

        // Notify
        new Handler(Looper.getMainLooper()).post(() -> {
            for (DownloadInfoListener l : mDownloadInfoListeners) {
                l.onReload();
            }
        });
    }

    public void addDownloadLabel(List<DownloadLabel> downloadLabelList) {
        if (downloadLabelList == null || downloadLabelList.isEmpty()) {
            return;
        }
        
        // 保存现有标签对应的下载列表
        Map<String, LinkedList<DownloadInfo>> tempMap = new HashMap<>(mMap);
        
        // 清除现有标签
        mLabelList.clear();
        mMap.clear();
        EhDB.removeAllDownloadLabels();
        
        // 按导入标签的顺序添加标签
        for (DownloadLabel label : downloadLabelList) {
            String labelString = label.getLabel();
            // 使用原始时间戳添加标签，保证排序
            DownloadLabel newLabel = EhDB.addDownloadLabelWithTime(label);
            // 恢复原有标签对应的下载列表，或创建新的空列表
            LinkedList<DownloadInfo> infoList = tempMap.get(labelString);
            if (infoList == null) {
                infoList = new LinkedList<>();
            }
            mMap.put(labelString, infoList);
            mLabelList.add(newLabel);
        }
        
        // 处理没有对应标签的下载项，移动到默认列表
        for (Map.Entry<String, LinkedList<DownloadInfo>> entry : tempMap.entrySet()) {
            if (!mMap.containsKey(entry.getKey())) {
                // 如果原标签不再存在，将其对应的下载项移动到默认列表
                for (DownloadInfo info : entry.getValue()) {
                    info.label = null;
                    mDefaultInfoList.add(info);
                    EhDB.putDownloadInfo(info);
                }
            }
        }
    }

    public void addDownload(GalleryInfo galleryInfo, @Nullable String label, int state) {
        if (containDownloadInfo(galleryInfo.gid)) {
            // Contain
            return;
        }

        // It is new download info
        DownloadInfo info = new DownloadInfo(galleryInfo);
        info.label = label;
        info.state = state;
        info.time = System.currentTimeMillis();

        // Add to label download list
        LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
        if (!mLabelCountMap.containsKey(label)) {
            mLabelCountMap.put(label, 1L);
        } else {
            long value = mLabelCountMap.get(label) + 1L;
            mLabelCountMap.put(label, value);
        }
        if (list == null) {
            Log.e(TAG, "Can't find download info list with label: " + label);
            return;
        }
        list.addFirst(info);

        // Add to all download list and map
        mAllInfoList.addFirst(info);
        mAllInfoMap.put(galleryInfo.gid, info);

        // Save to
        EhDB.putDownloadInfo(info);

        // Notify
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onAdd(info, list, list.size() - 1);
        }
    }

    public void addDownload(GalleryInfo galleryInfo, @Nullable String label) {
        addDownload(galleryInfo, label, DownloadInfo.STATE_NONE);
    }

    public void addDownloadInfo(GalleryInfo galleryInfo, @Nullable String label) {
        if (containDownloadInfo(galleryInfo.gid)) {
            // Contain
            return;
        }

        // It is new download info
        DownloadInfo info = new DownloadInfo(galleryInfo);
        info.label = label;
        info.state = DownloadInfo.STATE_NONE;
        if (info.time == 0) {
            info.time = System.currentTimeMillis();
        }

        // Add to label download list
        LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
        if (list == null) {
            Log.e(TAG, "Can't find download info list with label: " + label);
            return;
        }
        list.addFirst(info);

        // Save to
        EhDB.putDownloadInfo(info);
        mAllInfoMap.put(galleryInfo.gid, info);
    }


    public void stopDownload(long gid) {
        DownloadInfo info = stopDownloadInternal(gid);
        if (info != null) {
            // Update listener
            List<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                for (DownloadInfoListener l : mDownloadInfoListeners) {
                    l.onUpdate(info, list, mWaitList);
                }
            }
            // Ensure download
            ensureDownload();
        }
    }

    void stopCurrentDownload() {
        DownloadInfo info = stopCurrentDownloadInternal();
        if (info != null) {
            // Update listener
            List<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                for (DownloadInfoListener l : mDownloadInfoListeners) {
                    l.onUpdate(info, list, mWaitList);
                }
            }
            // Ensure download
            ensureDownload();
        }
    }

    public void stopRangeDownload(LongList gidList) {
        stopRangeDownloadInternal(gidList);

        // Update listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateAll();
        }

        // Ensure download
        ensureDownload();
    }

    public void stopAllDownload() {
        // Stop all in wait list
        for (DownloadInfo info : mWaitList) {
            info.state = DownloadInfo.STATE_NONE;
            // Update in DB
            EhDB.putDownloadInfo(info);
        }
        mWaitList.clear();

        // Stop current
        stopCurrentDownloadInternal();

        // Notify mDownloadInfoListener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateAll();
        }
    }

    public void deleteDownload(long gid) {
        stopDownloadInternal(gid);
        DownloadInfo info = mAllInfoMap.get(gid);
        if (info != null) {
            // Remove from DB
            EhDB.removeDownloadInfo(info.gid);

            // Remove all list and map
            mAllInfoList.remove(info);
            mAllInfoMap.remove(info.gid);

            // Remove label list
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                int index = list.indexOf(info);
                if (index >= 0) {
                    list.remove(info);
                    // Update listener
                    for (DownloadInfoListener l : mDownloadInfoListeners) {
                        l.onRemove(info, list, index);
                    }
                }
            }

            // Ensure download
            ensureDownload();
        }
    }

    public void deleteRangeDownload(LongList gidList) {
        stopRangeDownloadInternal(gidList);

        for (int i = 0, n = gidList.size(); i < n; i++) {
            long gid = gidList.get(i);
            DownloadInfo info = mAllInfoMap.get(gid);
            if (null == info) {
                Log.d(TAG, "Can't get download info with gid: " + gid);
                continue;
            }

            // Remove from DB
            EhDB.removeDownloadInfo(info.gid);

            // Remove from all info map
            mAllInfoList.remove(info);
            mAllInfoMap.remove(info.gid);

            // Remove from label list
            LinkedList<DownloadInfo> list = getInfoListForLabel(info.label);
            if (list != null) {
                list.remove(info);
            }
        }

        // Update listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onReload();
        }

        // Ensure download
        ensureDownload();
    }

    @SuppressLint("StaticFieldLeak")
    public void resetAllReadingProgress() {
        LinkedList<DownloadInfo> list = new LinkedList<>(mAllInfoList);

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                GalleryInfo galleryInfo = new GalleryInfo();
                for (DownloadInfo downloadInfo : list) {
                    galleryInfo.gid = downloadInfo.gid;
                    galleryInfo.token = downloadInfo.token;
                    galleryInfo.title = downloadInfo.title;
                    galleryInfo.thumb = downloadInfo.thumb;
                    galleryInfo.category = downloadInfo.category;
                    galleryInfo.posted = downloadInfo.posted;
                    galleryInfo.uploader = downloadInfo.uploader;
                    galleryInfo.rating = downloadInfo.rating;

                    UniFile downloadDir = SpiderDen.getGalleryDownloadDir(galleryInfo);
                    if (downloadDir == null) {
                        continue;
                    }
                    UniFile file = downloadDir.findFile(".ehviewer");
                    if (file == null) {
                        continue;
                    }
                    SpiderInfo spiderInfo = SpiderInfo.read(file);
                    if (spiderInfo == null) {
                        continue;
                    }
                    spiderInfo.startPage = 0;

                    try {
                        spiderInfo.write(file.openOutputStream());
                    } catch (IOException e) {
                        Log.e(TAG, "Can't write SpiderInfo", e);
                    }
                }
                return null;
            }
        }.executeOnExecutor(IoThreadPoolExecutor.getInstance());
    }

    // Update in DB
    // Update listener
    // No ensureDownload
    private DownloadInfo stopDownloadInternal(long gid) {
        // Check current task
        if (mCurrentTask != null && mCurrentTask.gid == gid) {
            // Stop current
            return stopCurrentDownloadInternal();
        }

        for (Iterator<DownloadInfo> iterator = mWaitList.iterator(); iterator.hasNext(); ) {
            DownloadInfo info = iterator.next();
            if (info.gid == gid) {
                // Remove from wait list
                iterator.remove();
                // Update state
                info.state = DownloadInfo.STATE_NONE;
                // Update in DB
                EhDB.putDownloadInfo(info);
                return info;
            }
        }
        return null;
    }

    // Update in DB
    // Update mDownloadListener
    private DownloadInfo stopCurrentDownloadInternal() {
        DownloadInfo info = mCurrentTask;
        SpiderQueen spider = mCurrentSpider;
        // Release spider
        if (spider != null) {
            spider.removeOnSpiderListener(DownloadManager.this);
            SpiderQueen.releaseSpiderQueen(spider, SpiderQueen.MODE_DOWNLOAD);
        }
        mCurrentTask = null;
        mCurrentSpider = null;
        // Stop speed reminder
        mSpeedReminder.stop();
        if (info == null) {
            return null;
        }

        // Update state
        info.state = DownloadInfo.STATE_NONE;
        // Update in DB
        EhDB.putDownloadInfo(info);
        // Listener
        if (mDownloadListener != null) {
            mDownloadListener.onCancel(info);
        }
        return info;
    }

    // Update in DB
    // Update mDownloadListener
    private void stopRangeDownloadInternal(LongList gidList) {
        // Two way
        if (gidList.size() < mWaitList.size()) {
            for (int i = 0, n = gidList.size(); i < n; i++) {
                stopDownloadInternal(gidList.get(i));
            }
        } else {
            // Check current task
            if (mCurrentTask != null && gidList.contains(mCurrentTask.gid)) {
                // Stop current
                stopCurrentDownloadInternal();
            }

            // Check all in wait list
            for (Iterator<DownloadInfo> iterator = mWaitList.iterator(); iterator.hasNext(); ) {
                DownloadInfo info = iterator.next();
                if (gidList.contains(info.gid)) {
                    // Remove from wait list
                    iterator.remove();
                    // Update state
                    info.state = DownloadInfo.STATE_NONE;
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                }
            }
        }
    }

    /**
     * @param label Not allow new label
     */
    public void changeLabel(List<DownloadInfo> list, String label) {
        if (null != label && !containLabel(label)) {
            Log.e(TAG, "Not exits label: " + label);
            return;
        }

        List<DownloadInfo> dstList = getInfoListForLabel(label);
        if (dstList == null) {
            Log.e(TAG, "Can't find label with label: " + label);
            return;
        }

        for (DownloadInfo info : list) {
            if (ObjectUtils.equal(info.label, label)) {
                continue;
            }

            List<DownloadInfo> srcList = getInfoListForLabel(info.label);
            if (srcList == null) {
                Log.e(TAG, "Can't find label with label: " + info.label);
                continue;
            }

            srcList.remove(info);
            dstList.add(info);
            info.label = label;
            Collections.sort(dstList, DATE_DESC_COMPARATOR);

            // Save to DB
            EhDB.putDownloadInfo(info);
        }

        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onReload();
        }
    }

    public void addLabel(String label) {
        if (label == null || containLabel(label)) {
            return;
        }

        mLabelList.add(EhDB.addDownloadLabel(label));
        mMap.put(label, new LinkedList<>());

        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateLabels();
        }
    }

    public void addLabelInSyncThread(String label) {
        if (label == null || containLabel(label)) {
            return;
        }

        mLabelList.add(EhDB.addDownloadLabel(label));
        mMap.put(label, new LinkedList<>());
    }

    public void moveLabel(int fromPosition, int toPosition) {
        final DownloadLabel item = mLabelList.remove(fromPosition);
        mLabelList.add(toPosition, item);
        EhDB.moveDownloadLabel(fromPosition, toPosition);

        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onUpdateLabels();
        }
    }

    public void renameLabel(@NonNull String from, @NonNull String to) {
        // Find in label list
        boolean found = false;
        for (DownloadLabel raw : mLabelList) {
            if (from.equals(raw.getLabel())) {
                found = true;
                raw.setLabel(to);
                // Update in DB
                EhDB.updateDownloadLabel(raw);
                break;
            }
        }
        if (!found) {
            return;
        }

        LinkedList<DownloadInfo> list = mMap.remove(from);
        if (list == null) {
            return;
        }

        // Update info label
        for (DownloadInfo info : list) {
            info.label = to;
            // Update in DB
            EhDB.putDownloadInfo(info);
        }
        // Put list back with new label
        mMap.put(to, list);

        // Notify listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onRenameLabel(from, to);
        }
    }

    public void deleteLabel(@NonNull String label) {
        // Find in label list and remove
        boolean found = false;
        for (Iterator<DownloadLabel> iterator = mLabelList.iterator(); iterator.hasNext(); ) {
            DownloadLabel raw = iterator.next();
            if (label.equals(raw.getLabel())) {
                found = true;
                iterator.remove();
                EhDB.removeDownloadLabel(raw);
                break;
            }
        }
        if (!found) {
            return;
        }

        LinkedList<DownloadInfo> list = mMap.remove(label);
        if (list == null) {
            return;
        }

        // Update info label
        for (DownloadInfo info : list) {
            info.label = null;
            // Update in DB
            EhDB.putDownloadInfo(info);
            mDefaultInfoList.add(info);
        }

        // Sort
        Collections.sort(mDefaultInfoList, DATE_DESC_COMPARATOR);

        // Notify listener
        for (DownloadInfoListener l : mDownloadInfoListeners) {
            l.onChange();
        }
    }

    boolean isIdle() {
        return mCurrentTask == null && mWaitList.isEmpty();
    }

    @Override
    public void onGetPages(int pages) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setOnGetPagesData(pages);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onGet509(int index) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setOnGet509Data(index);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onPageDownload(int index, long contentLength, long receivedSize, int bytesRead) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setOnPageDownloadData(index, contentLength, receivedSize, bytesRead);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onPageSuccess(int index, int finished, int downloaded, int total) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setOnPageSuccessData(index, finished, downloaded, total);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onPageFailure(int index, String error, int finished, int downloaded, int total) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setOnPageFailureDate(index, error, finished, downloaded, total);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onFinish(int finished, int downloaded, int total) {
        NotifyTask task = mNotifyTaskPool.pop();
        if (task == null) {
            task = new NotifyTask();
        }
        task.setOnFinishDate(finished, downloaded, total);
        SimpleHandler.getInstance().post(task);
    }

    @Override
    public void onGetImageSuccess(int index, Image image) {
        // Ignore
    }

    @Override
    public void onGetImageFailure(int index, String error) {
        // Ignore
    }

    private class NotifyTask implements Runnable {

        public static final int TYPE_ON_GET_PAGES = 0;
        public static final int TYPE_ON_GET_509 = 1;
        public static final int TYPE_ON_PAGE_DOWNLOAD = 2;
        public static final int TYPE_ON_PAGE_SUCCESS = 3;
        public static final int TYPE_ON_PAGE_FAILURE = 4;
        public static final int TYPE_ON_FINISH = 5;

        private int mType;
        private int mPages;
        private int mIndex;
        private long mContentLength;
        private long mReceivedSize;
        private int mBytesRead;
        @SuppressWarnings("unused")
        private String mError;
        private int mFinished;
        private int mDownloaded;
        private int mTotal;

        public void setOnGetPagesData(int pages) {
            mType = TYPE_ON_GET_PAGES;
            mPages = pages;
        }

        public void setOnGet509Data(int index) {
            mType = TYPE_ON_GET_509;
            mIndex = index;
        }

        public void setOnPageDownloadData(int index, long contentLength, long receivedSize, int bytesRead) {
            mType = TYPE_ON_PAGE_DOWNLOAD;
            mIndex = index;
            mContentLength = contentLength;
            mReceivedSize = receivedSize;
            mBytesRead = bytesRead;
        }

        public void setOnPageSuccessData(int index, int finished, int downloaded, int total) {
            mType = TYPE_ON_PAGE_SUCCESS;
            mIndex = index;
            mFinished = finished;
            mDownloaded = downloaded;
            mTotal = total;
        }

        public void setOnPageFailureDate(int index, String error, int finished, int downloaded, int total) {
            mType = TYPE_ON_PAGE_FAILURE;
            mIndex = index;
            mError = error;
            mFinished = finished;
            mDownloaded = downloaded;
            mTotal = total;
        }

        public void setOnFinishDate(int finished, int downloaded, int total) {
            mType = TYPE_ON_FINISH;
            mFinished = finished;
            mDownloaded = downloaded;
            mTotal = total;
        }

        @Override
        public void run() {
            switch (mType) {
                case TYPE_ON_GET_PAGES: {
                    DownloadInfo info = mCurrentTask;
                    if (info == null) {
                        Log.e(TAG, "Current task is null, but it should not be");
                    } else {
                        info.total = mPages;
                        List<DownloadInfo> list = getInfoListForLabel(info.label);
                        if (list != null) {
                            for (DownloadInfoListener l : mDownloadInfoListeners) {
                                l.onUpdate(info, list, mWaitList);
                            }
                        }
                    }
                    break;
                }
                case TYPE_ON_GET_509: {
                    if (mDownloadListener != null) {
                        mDownloadListener.onGet509();
                    }
                    break;
                }
                case TYPE_ON_PAGE_DOWNLOAD: {
                    mSpeedReminder.onDownload(mIndex, mContentLength, mReceivedSize, mBytesRead);
                    break;
                }
                case TYPE_ON_PAGE_SUCCESS: {
                    mSpeedReminder.onDone(mIndex);
                    DownloadInfo info = mCurrentTask;
                    if (info == null) {
                        Log.e(TAG, "Current task is null, but it should not be");
                    } else {
                        info.finished = mFinished;
                        info.downloaded = mDownloaded;
                        info.total = mTotal;
                        if (mDownloadListener != null) {
                            mDownloadListener.onGetPage(info);
                        }
                        List<DownloadInfo> list = getInfoListForLabel(info.label);
                        if (list != null) {
                            for (DownloadInfoListener l : mDownloadInfoListeners) {
                                l.onUpdate(info, list, mWaitList);
                            }
                        }
                    }
                    break;
                }
                case TYPE_ON_PAGE_FAILURE: {
                    mSpeedReminder.onDone(mIndex);
                    DownloadInfo info = mCurrentTask;
                    if (info == null) {
                        Log.e(TAG, "Current task is null, but it should not be");
                    } else {
                        info.finished = mFinished;
                        info.downloaded = mDownloaded;
                        info.total = mTotal;
                        List<DownloadInfo> list = getInfoListForLabel(info.label);
                        if (list != null) {
                            for (DownloadInfoListener l : mDownloadInfoListeners) {
                                l.onUpdate(info, list, mWaitList);
                            }
                        }
                    }
                    break;
                }
                case TYPE_ON_FINISH: {
                    mSpeedReminder.onFinish();
                    // Download done
                    DownloadInfo info = mCurrentTask;
                    mCurrentTask = null;
                    SpiderQueen spider = mCurrentSpider;
                    mCurrentSpider = null;
                    // Release spider
                    if (spider != null) {
                        spider.removeOnSpiderListener(DownloadManager.this);
                        SpiderQueen.releaseSpiderQueen(spider, SpiderQueen.MODE_DOWNLOAD);
                    }
                    // Check null
                    if (info == null || spider == null) {
                        Log.e(TAG, "Current stuff is null, but it should not be");
                        break;
                    }
                    // Stop speed count
                    mSpeedReminder.stop();
                    // Update state
                    info.finished = mFinished;
                    info.downloaded = mDownloaded;
                    info.total = mTotal;
                    info.legacy = mTotal - mFinished;
                    if (info.legacy == 0) {
                        info.state = DownloadInfo.STATE_FINISH;
                    } else {
                        info.state = DownloadInfo.STATE_FAILED;
                    }
                    // Update in DB
                    EhDB.putDownloadInfo(info);
                    // Notify
                    if (mDownloadListener != null) {
                        mDownloadListener.onFinish(info);
                    }
                    List<DownloadInfo> list = getInfoListForLabel(info.label);
                    if (list != null) {
                        for (DownloadInfoListener l : mDownloadInfoListeners) {
                            l.onUpdate(info, list, mWaitList);
                        }
                    }
                    // 自动打包为 CBZ（高级设置开启且当前目录未处于 CBZ 模式）
                    if (info.state == DownloadInfo.STATE_FINISH && Settings.isAutoCbzAfterDownloadEnabled()) {
                        final DownloadInfo finalInfo = info;
                        IoThreadPoolExecutor.getInstance().execute(() -> {
                            try {
                                com.hippo.unifile.UniFile dir = SpiderDen.getGalleryDownloadDir(finalInfo);
                                if (dir != null && dir.isDirectory() && !com.hippo.ehviewer.util.CbzUtils.isCbzMode(dir)) {
                                    boolean ok = com.hippo.ehviewer.util.CbzUtils.createCbzWithComicInfo(
                                            dir,
                                            finalInfo,
                                            finalInfo.total > 0 ? finalInfo.total : finalInfo.pages,
                                            finalInfo.simpleTags,
                                            true
                                    );
                                    if (!ok) {
                                        new Handler(Looper.getMainLooper()).post(() -> {
                                            android.widget.Toast.makeText(mContext, mContext.getString(R.string.auto_cbz_failed), android.widget.Toast.LENGTH_SHORT).show();
                                        });
                                    }
                                }
                            } catch (Throwable ignore) {}
                        });
                    }
                    // Start next download
                    ensureDownload();
                    break;
                }
            }

            mNotifyTaskPool.push(this);
        }
    }


    class SpeedReminder implements Runnable {

        private boolean mStop = true;

        private long mBytesRead;
        private long oldSpeed = -1;

        private final SparseIJArray mContentLengthMap = new SparseIJArray();
        private final SparseIJArray mReceivedSizeMap = new SparseIJArray();

        public void start() {
            if (mStop) {
                mStop = false;
                SimpleHandler.getInstance().post(this);
            }
        }

        public void stop() {
            if (!mStop) {
                mStop = true;
                mBytesRead = 0;
                oldSpeed = -1;
                mContentLengthMap.clear();
                mReceivedSizeMap.clear();
                SimpleHandler.getInstance().removeCallbacks(this);
            }
        }

        public void onDownload(int index, long contentLength, long receivedSize, int bytesRead) {
            mContentLengthMap.put(index, contentLength);
            mReceivedSizeMap.put(index, receivedSize);
            mBytesRead += bytesRead;
        }

        public void onDone(int index) {
            mContentLengthMap.delete(index);
            mReceivedSizeMap.delete(index);
        }

        public void onFinish() {
            mContentLengthMap.clear();
            mReceivedSizeMap.clear();
        }

        @Override
        public void run() {
            DownloadInfo info = mCurrentTask;
            if (info != null) {
                long newSpeed = mBytesRead / 2;
                if (oldSpeed != -1) {
                    newSpeed = (long) MathUtils.lerp(oldSpeed, newSpeed, 0.75f);
                }
                oldSpeed = newSpeed;
                info.speed = newSpeed;

                // Calculate remaining
                if (info.total <= 0) {
                    info.remaining = -1;
                } else if (newSpeed == 0) {
                    info.remaining = 300L * 24L * 60L * 60L * 1000L; // 300 days
                } else {
                    int downloadingCount = 0;
                    long downloadingContentLengthSum = 0;
                    long totalSize = 0;
                    for (int i = 0, n = Math.max(mContentLengthMap.size(), mReceivedSizeMap.size()); i < n; i++) {
                        long contentLength = mContentLengthMap.valueAt(i);
                        long receivedSize = mReceivedSizeMap.valueAt(i);
                        downloadingCount++;
                        downloadingContentLengthSum += contentLength;
                        totalSize += contentLength - receivedSize;
                    }
                    if (downloadingCount != 0) {
                        totalSize += downloadingContentLengthSum * (info.total - info.downloaded - downloadingCount) / downloadingCount;
                        info.remaining = totalSize / newSpeed * 1000;
                    }
                }
                if (mDownloadListener != null) {
                    mDownloadListener.onDownload(info);
                }
                List<DownloadInfo> list = getInfoListForLabel(info.label);
                if (list != null) {
                    for (DownloadInfoListener l : mDownloadInfoListeners) {
                        l.onUpdate(info, list, mWaitList);
                    }
                }
            }

            mBytesRead = 0;

            if (!mStop) {
                SimpleHandler.getInstance().postDelayed(this, 2000);
            }
        }
    }

    private static final Comparator<DownloadInfo> DATE_DESC_COMPARATOR = new Comparator<>() {
        @Override
        public int compare(DownloadInfo lhs, DownloadInfo rhs) {
            long dif = lhs.time - rhs.time;
            if (dif > 0) {
                return -1;
            } else if (dif < 0) {
                return 1;
            } else {
                return 0;
            }
//            return  > 0 ? -1 : 1;
        }
    };

    public interface DownloadInfoListener {

        /**
         * Add the special info to the special position
         */
        void onAdd(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, int position);

        /**
         * delete Old replace new
         */
        void onReplace(@NonNull DownloadInfo newInfo, @NonNull DownloadInfo oldInfo);

        /**
         * The special info is changed
         */
        void onUpdate(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, LinkedList<DownloadInfo> mWaitList);

        /**
         * Maybe all data is changed, but size is the same
         */
        void onUpdateAll();

        /**
         * Maybe all data is changed, maybe list is changed
         */
        void onReload();

        /**
         * The list is gone, use default list please
         */
        void onChange();

        /**
         * Rename label
         */
        void onRenameLabel(String from, String to);

        /**
         * Remove the special info from the special position
         */
        void onRemove(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, int position);

        void onUpdateLabels();
    }

    public interface DownloadListener {

        /**
         * Get 509 error
         */
        void onGet509();

        /**
         * Start download
         */
        void onStart(DownloadInfo info);

        /**
         * Update download speed
         */
        void onDownload(DownloadInfo info);

        /**
         * Update page downloaded
         */
        void onGetPage(DownloadInfo info);

        /**
         * Download done
         */
        void onFinish(DownloadInfo info);

        /**
         * Download done
         */
        void onCancel(DownloadInfo info);
    }

}
