package com.hippo.ehviewer.download;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryUpdateDialog;
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryUpdateDialog.UpdateRequest;
import com.hippo.ehviewer.util.CbzUtils;
import com.hippo.lib.yorozuya.FileUtils;
import com.hippo.unifile.UniFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.OkHttpClient;

/**
 * 漫画更新执行器
 * 处理已下载漫画的新版本更新逻辑
 *
 * 职责：文件操作（解压CBZ、重编号页面、重命名目录、删除旧文件）
 * 不负责：SpiderInfo、dirname映射、download entry 等下载系统内部状态
 */
public class GalleryUpdater {

    private static final String TAG = "GalleryUpdater";

    public interface UpdateCallback {
        void onUpdateStart();
        void onUpdateProgress(String message);
        void onUpdateQueued();
        void onUpdateFailed(String error);
    }

    private final Context context;
    private final DownloadManager downloadManager;
    private volatile boolean cancelled = false;

    private interface ProgressEmitter {
        void emit(String message);
    }

    public GalleryUpdater(Context context, DownloadManager downloadManager) {
        this.context = context;
        this.downloadManager = downloadManager;
    }

    public void cancel() {
        cancelled = true;
    }

    /**
     * 执行更新
     * @param oldInfo 旧的下载信息
     * @param oldDetail 当前的 GalleryDetail（包含 newVersions）
     * @param request 更新请求（模式 + 新版本信息）
     * @param callback 回调
     */
    public void executeUpdate(@NonNull DownloadInfo oldInfo,
                              @NonNull UpdateRequest request,
                              @NonNull UpdateCallback callback) {
        new AsyncTask<Void, String, Object>() {
            @Override
            protected void onPreExecute() {
                callback.onUpdateStart();
            }

            @Override
            protected Object doInBackground(Void... voids) {
                try {
                    return doUpdate(oldInfo, request, this::publishProgress);
                } catch (Exception e) {
                    Log.e(TAG, "Update failed", e);
                    return "Update failed: " + e.getMessage();
                }
            }

            @Override
            protected void onProgressUpdate(String... values) {
                if (values.length > 0) {
                    callback.onUpdateProgress(values[0]);
                }
            }

            @Override
            protected void onPostExecute(Object result) {
                if (result == null) {
                    callback.onUpdateQueued();
                } else if (result instanceof String) {
                    callback.onUpdateFailed((String) result);
                }
            }
        }.execute();
    }

    @Nullable
    private Object doUpdate(@NonNull DownloadInfo oldInfo,
                            @NonNull UpdateRequest request,
                            @Nullable ProgressEmitter progress) throws Exception {

        // ===== 阶段一：验证和准备 =====

        // 1. 获取新版本的 GalleryDetail（优先复用已缓存的结果，避免重复联网）
        Log.i(TAG, "Step 1: Resolving new version info, url=" + request.newVersion.versionUrl);
        String[] urlParts = request.newVersion.versionUrl.split("/");
        if (urlParts.length < 2) {
            Log.e(TAG, "Invalid version URL: " + request.newVersion.versionUrl);
            return "Invalid version URL";
        }
        long newGid = Long.parseLong(urlParts[urlParts.length - 2]);
        String newToken = urlParts[urlParts.length - 1];
        Log.i(TAG, "New version: gid=" + newGid + ", token=" + newToken);

        if (downloadManager != null && newGid != oldInfo.gid && downloadManager.containDownloadInfo(newGid)) {
            DownloadInfo existingNewInfo = downloadManager.getDownloadInfo(newGid);
            if (existingNewInfo != null && existingNewInfo.state == DownloadInfo.STATE_FINISH) {
                Log.w(TAG, "New version already finished in download list: gid=" + newGid);
                return context.getString(R.string.gallery_update_error_already_finished, newGid);
            } else {
                Log.i(TAG, "New version exists in download list but not finished, stopping task before update: gid=" + newGid);
                downloadManager.stopDownload(newGid);
            }
        }

        GalleryDetail newDetail;
        if (request.cachedNewDetail != null
                && request.cachedNewDetail.gid == newGid
                && request.cachedNewDetail.pages > 0) {
            Log.i(TAG, "Reusing cached GalleryDetail for gid=" + newGid);
            newDetail = request.cachedNewDetail;
        } else {
            if (progress != null) {
                progress.emit(context.getString(R.string.gallery_update_progress_fetching));
            }
            newDetail = fetchGalleryDetail(newGid, newToken);
        }
        if (newDetail == null) {
            Log.e(TAG, "Failed to fetch new version detail");
            return "Failed to fetch new version details";
        }
        if (cancelled) return "Cancelled";

        // 2. 获取旧的下载目录并执行前置安全/冲突校验（在修改任何文件之前）
        UniFile downloadDir = Settings.getDownloadLocation();
        if (downloadDir == null) {
            Log.e(TAG, "Download location not set");
            return "Download location not set";
        }

        String oldDirname = EhDB.getDownloadDirname(oldInfo.gid);
        if (oldDirname == null) {
            Log.e(TAG, "Cannot find dirname for gid=" + oldInfo.gid);
            return "Cannot find old download directory";
        }
        UniFile oldDir = downloadDir.subFile(oldDirname);
        if (oldDir == null || !oldDir.isDirectory()) {
            Log.e(TAG, "Old dir not found: " + oldDirname);
            return "Old download directory not found";
        }

        String newTitle = com.hippo.ehviewer.client.EhUtils.getSuitableTitle(newDetail);
        String newDirname = FileUtils.sanitizeFilename(newGid + "-" + newTitle);
        if (request.mode != GalleryUpdateDialog.MODE_FULL_REPLACE) {
            String currentDirName = oldDir.getName();
            if (currentDirName == null || !currentDirName.startsWith(String.valueOf(oldInfo.gid))) {
                Log.e(TAG, "Safety check failed: dir='" + currentDirName + "', gid=" + oldInfo.gid);
                return "Safety check failed: directory '" + currentDirName + "' does not belong to gid " + oldInfo.gid;
            }
            if (!oldDirname.equals(newDirname)) {
                UniFile existingNewDir = downloadDir.subFile(newDirname);
                if (existingNewDir != null && existingNewDir.isDirectory()) {
                    DownloadInfo existingNewInfo = (downloadManager != null) ? downloadManager.getDownloadInfo(newGid) : null;
                    boolean isNewFinished = (existingNewInfo != null && existingNewInfo.state == DownloadInfo.STATE_FINISH);
                    if (isNewFinished) {
                        Log.e(TAG, "Target dir already exists and finished: " + newDirname);
                        return "Target directory already exists: " + newDirname + ". Update aborted to prevent data loss.";
                    } else {
                        Log.i(TAG, "Cleaning up unfinished existing target dir before renaming: " + newDirname);
                        existingNewDir.delete();
                    }
                }
            }
        }

        // 3. 解压 CBZ（如果有）— 必须在模式处理之前，否则页面文件不可见
        Log.i(TAG, "Step 3: Checking CBZ in " + oldDirname);
        if (CbzUtils.isCbzMode(oldDir)) {
            if (progress != null) {
                progress.emit(context.getString(R.string.gallery_update_progress_extracting_cbz));
            }
            Log.i(TAG, "Extracting CBZ...");
            if (!CbzUtils.extractCbz(oldDir, true)) {
                Log.e(TAG, "Failed to extract CBZ");
                return "Failed to extract CBZ file";
            }
            Log.i(TAG, "CBZ extracted successfully");
        } else {
            Log.i(TAG, "Not CBZ mode, skip extraction");
        }
        if (cancelled) return "Cancelled";

        int oldPages = resolveOldPages(oldInfo, oldDir);
        int newPages = newDetail.pages;
        Log.i(TAG, "Pages: old=" + oldPages + ", new=" + newPages);

        // 4. 一次性扫描目录建立页码索引，按模式处理页面文件
        if (progress != null) {
            progress.emit(context.getString(R.string.gallery_update_progress_processing_pages));
        }
        Log.i(TAG, "Step 4: Processing mode=" + request.mode);
        Map<Integer, UniFile> pageIndex = (request.mode == GalleryUpdateDialog.MODE_FULL_REPLACE)
                ? null : buildPageFileIndex(oldDir);
        switch (request.mode) {
            case GalleryUpdateDialog.MODE_UPDATE_BACK:
                if (!handleUpdateBack(pageIndex, oldPages, newPages)) {
                    return "Failed to process files for update-back mode";
                }
                break;
            case GalleryUpdateDialog.MODE_UPDATE_FRONT:
                if (!handleUpdateFront(pageIndex, oldPages, newPages)) {
                    return "Failed to process files for update-front mode";
                }
                break;
            case GalleryUpdateDialog.MODE_FULL_REPLACE:
                if (!handleFullReplace(oldDir, oldInfo)) {
                    return "Failed to delete old directory";
                }
                break;
            case GalleryUpdateDialog.MODE_CUSTOM:
                if (!handleCustom(pageIndex, request, oldPages, newPages)) {
                    return "Failed to process files for custom mode";
                }
                break;
            case GalleryUpdateDialog.MODE_SMART_INCREMENTAL:
                if (!handleSmartIncremental(oldDir, pageIndex, request, oldPages, newPages)) {
                    return "Failed to process files for smart incremental mode";
                }
                break;
            default:
                return "Unknown update mode";
        }
        if (cancelled) return "Cancelled";

        // ===== 阶段二：重命名目录 =====

        // 5. 重命名目录（全部替换模式已删除目录，跳过）
        if (progress != null) {
            progress.emit(context.getString(R.string.gallery_update_progress_renaming_dir));
        }
        if (request.mode != GalleryUpdateDialog.MODE_FULL_REPLACE) {
            if (!oldDirname.equals(newDirname)) {
                // 删除旧版本的 .ehviewer 和 .thumb 文件（下载系统会为新版本重新创建）
                deleteMetadataFiles(oldDir);

                Log.i(TAG, "Step 5: Renaming directory: " + oldDirname + " -> " + newDirname);
                if (!oldDir.renameTo(newDirname)) {
                    Log.e(TAG, "Rename failed: " + oldDirname + " -> " + newDirname);
                    return "Failed to rename directory from " + oldDirname + " to " + newDirname;
                }
            } else {
                Log.i(TAG, "Step 5: Directory name unchanged, skip rename");
                deleteMetadataFiles(oldDir);
            }
        } else {
            Log.i(TAG, "Step 5: Full replace mode, skip rename");
        }
        if (cancelled) return "Cancelled";

        // ===== 阶段三：触发新下载 =====

        // 6. 通过 DownloadService 删除旧条目、启动新下载
        //    与下载列表中点击"启动"按钮使用相同的机制
        Log.i(TAG, "Step 6: Delete old + start new via DownloadService, old gid=" + oldInfo.gid + " -> new gid=" + newGid);
        Intent deleteIntent = new Intent(context, DownloadService.class);
        deleteIntent.setAction(DownloadService.ACTION_DELETE);
        deleteIntent.putExtra(DownloadService.KEY_GID, oldInfo.gid);
        context.startService(deleteIntent);
        com.hippo.ehviewer.util.NewVersionStore.remove(oldInfo.gid);

        // 确保新画廊的目录名映射与 newDirname 保持一致
        EhDB.putDownloadDirname(newGid, newDirname);

        // 构建轻量级 GalleryInfo，避免携带大量 comments/tags/previews 导致 Binder TransactionTooLargeException
        GalleryInfo lightInfo = new GalleryInfo();
        lightInfo.gid = newDetail.gid;
        lightInfo.token = newDetail.token;
        lightInfo.title = newDetail.title;
        lightInfo.titleJpn = newDetail.titleJpn;
        lightInfo.category = newDetail.category;
        lightInfo.thumb = newDetail.thumb;
        lightInfo.uploader = newDetail.uploader;
        lightInfo.posted = newDetail.posted;
        lightInfo.rating = newDetail.rating;
        lightInfo.rated = newDetail.rated;
        lightInfo.pages = newDetail.pages;
        lightInfo.simpleLanguage = newDetail.simpleLanguage;
        lightInfo.simpleTags = newDetail.simpleTags;
        lightInfo.thumbWidth = newDetail.thumbWidth;
        lightInfo.thumbHeight = newDetail.thumbHeight;
        lightInfo.favoriteSlot = newDetail.favoriteSlot;
        lightInfo.favoriteName = newDetail.favoriteName;

        Intent startIntent = new Intent(context, DownloadService.class);
        startIntent.setAction(DownloadService.ACTION_START);
        startIntent.putExtra(DownloadService.KEY_GALLERY_INFO, lightInfo);
        startIntent.putExtra(DownloadService.KEY_LABEL, oldInfo.label);
        context.startService(startIntent);

        // startService 是异步的，下载任务已加入队列但尚未开始
        // 返回 null 表示"已入队"，由回调显示相应提示
        Log.i(TAG, "Update queued, new gid=" + newGid);
        return null;
    }

    /**
     * 删除旧版本的元数据文件（.ehviewer 和 .thumb）
     * 下载系统会为新版本重新创建这些文件
     */
    private void deleteMetadataFiles(UniFile dir) {
        String[] metadataFiles = {".ehviewer", ".thumb"};
        for (String name : metadataFiles) {
            UniFile f = dir.findFile(name);
            if (f != null && f.isFile()) {
                if (f.delete()) {
                    Log.i(TAG, "Deleted old metadata: " + name);
                } else {
                    Log.w(TAG, "Failed to delete old metadata: " + name);
                }
            }
        }
    }

    /**
     * 一次性扫描目录，建立「1-based 页码 -> 页面文件」的内存索引，避免在 SAF 存储上反复调用 findFile。
     */
    @NonNull
    private Map<Integer, UniFile> buildPageFileIndex(@Nullable UniFile dir) {
        Map<Integer, UniFile> index = new HashMap<>();
        if (dir == null || !dir.isDirectory()) {
            return index;
        }
        try {
            UniFile[] files = dir.listFiles();
            if (files != null) {
                for (UniFile f : files) {
                    if (f != null && f.isFile()) {
                        String name = f.getName();
                        if (name != null && name.length() > 8 && name.charAt(8) == '.') {
                            try {
                                int page = Integer.parseInt(name.substring(0, 8));
                                if (page >= 1) {
                                    index.put(page, f);
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to build page file index", t);
        }
        return index;
    }

    @Nullable
    private static String extractExtension(@NonNull UniFile file) {
        String name = file.getName();
        if (name == null) return null;
        int dot = name.indexOf('.');
        return dot >= 0 ? name.substring(dot) : null;
    }

    private boolean deleteIndexedPageFile(@NonNull Map<Integer, UniFile> pageIndex, int pageIndexNum) {
        UniFile f = pageIndex.remove(pageIndexNum);
        if (f != null && f.isFile()) {
            String name = f.getName();
            if (!f.delete()) {
                Log.w(TAG, "Failed to delete page file: " + name);
                return false;
            }
            Log.i(TAG, "Deleted page file: " + name);
        }
        return true;
    }

    private boolean renameIndexedPageFile(@NonNull Map<Integer, UniFile> pageIndex, int oldIndex, int newIndex) {
        if (oldIndex == newIndex) return true;
        UniFile oldFile = pageIndex.get(oldIndex);
        if (oldFile == null || !oldFile.isFile()) {
            Log.e(TAG, "Page file not found in index: " + String.format(Locale.US, "%08d", oldIndex));
            return false;
        }
        String ext = extractExtension(oldFile);
        if (ext == null) {
            Log.e(TAG, "Page file has no extension: " + oldFile.getName());
            return false;
        }
        String newFileName = String.format(Locale.US, "%08d", newIndex) + ext;
        if (!oldFile.renameTo(newFileName)) {
            Log.e(TAG, "Rename failed: " + oldFile.getName() + " -> " + newFileName);
            return false;
        }
        return true;
    }

    /**
     * 更新后方：前N页相同，只需下载新增的后几页
     */
    private boolean handleUpdateBack(Map<Integer, UniFile> pageIndex, int oldPages, int newPages) {
        Log.i(TAG, "Update back: old=" + oldPages + " pages, new=" + newPages + " pages");
        if (oldPages > newPages) {
            for (Map.Entry<Integer, UniFile> entry : new ArrayList<>(pageIndex.entrySet())) {
                if (cancelled) return false;
                if (entry.getKey() > newPages) {
                    deleteIndexedPageFile(pageIndex, entry.getKey());
                }
            }
        }
        return true;
    }

    /**
     * 更新前方：新版前面多了页，需要重编号已有的旧页
     */
    private boolean handleUpdateFront(Map<Integer, UniFile> pageIndex, int oldPages, int newPages) {
        int addedPages = newPages - oldPages;
        if (addedPages <= 0) {
            return true;
        }
        Log.i(TAG, "Update front: " + addedPages + " pages added, renumbering " + oldPages + " old pages");

        // 预检查：确认所有待重命名的源文件都存在
        for (int i = 1; i <= oldPages; i++) {
            if (!pageIndex.containsKey(i)) {
                Log.e(TAG, "Pre-check failed: page " + i + " not found");
                return false;
            }
        }

        // 从后往前重命名，避免文件名冲突
        // 失败时高位文件已在新位置，低位文件原位不动，不会冲突
        for (int i = oldPages; i >= 1; i--) {
            if (cancelled) return false;
            if (!renameIndexedPageFile(pageIndex, i, i + addedPages)) {
                Log.e(TAG, "Failed to rename page " + i + " to " + (i + addedPages));
                return false;
            }
        }
        return true;
    }

    /**
     * 全部替换：删除旧目录
     */
    private boolean handleFullReplace(UniFile dir, DownloadInfo oldInfo) {
        String dirName = dir.getName();
        if (dirName == null || !dirName.startsWith(String.valueOf(oldInfo.gid))) {
            Log.e(TAG, "Safety check failed: dir='" + dirName + "', gid=" + oldInfo.gid);
            return false;
        }
        Log.i(TAG, "Full replace: deleting " + dirName);
        if (!dir.delete()) {
            Log.e(TAG, "Failed to delete directory: " + dirName);
            return false;
        }
        return true;
    }

    /**
     * 自定义模式：根据用户指定的页数映射进行重编号
     */
    private boolean handleCustom(Map<Integer, UniFile> pageIndex, UpdateRequest request, int oldPages, int newPages) {
        // 页面文件从 1 开始编号（00000001.jpg），用户输入也是 1-based，不需要转换
        int oldStart = request.customOldStart;
        int oldEnd = request.customOldEnd;
        int newStart = request.customNewStart;
        int newEnd = request.customNewEnd;
        int oldCount = oldEnd - oldStart + 1;
        int newCount = newEnd - newStart + 1;

        if (oldStart < 1 || oldEnd < oldStart) {
            Log.e(TAG, "Invalid old range: " + oldStart + "-" + oldEnd);
            return false;
        }
        if (newStart < 1 || newEnd < newStart) {
            Log.e(TAG, "Invalid new range: " + newStart + "-" + newEnd);
            return false;
        }
        if (oldPages > 0 && oldEnd > oldPages) {
            Log.e(TAG, "Old range exceeds pages: " + oldEnd + " > " + oldPages);
            return false;
        }
        if (newEnd > newPages) {
            Log.e(TAG, "New range exceeds pages: " + newEnd + " > " + newPages);
            return false;
        }
        if (oldCount != newCount) {
            Log.e(TAG, "Count mismatch: old=" + oldCount + ", new=" + newCount);
            return false;
        }

        int offset = newStart - oldStart;
        Log.i(TAG, "Custom: old[" + oldStart + "-" + oldEnd + "] -> new[" + newStart + "-" + newEnd + "], offset=" + offset);

        // 预检查：确认所有待重命名的源文件都存在
        for (int i = oldStart; i <= oldEnd; i++) {
            if (!pageIndex.containsKey(i)) {
                Log.e(TAG, "Pre-check failed: page " + i + " not found");
                return false;
            }
        }

        // 删除旧画廊中不在映射范围内的页面文件（例如 oldStart 前和 oldEnd 后的旧页）
        // 避免新下载时检测到已有旧文件而跳过下载，也防止重命名时目标文件发生冲突
        for (Map.Entry<Integer, UniFile> entry : new ArrayList<>(pageIndex.entrySet())) {
            if (cancelled) return false;
            int p = entry.getKey();
            if (p < oldStart || p > oldEnd) {
                deleteIndexedPageFile(pageIndex, p);
            }
        }

        if (offset != 0) {
            if (offset > 0) {
                for (int i = oldEnd; i >= oldStart; i--) {
                    if (cancelled) return false;
                    if (!renameIndexedPageFile(pageIndex, i, i + offset)) {
                        Log.e(TAG, "Failed to rename page " + i + " to " + (i + offset));
                        return false;
                    }
                }
            } else {
                for (int i = oldStart; i <= oldEnd; i++) {
                    if (cancelled) return false;
                    if (!renameIndexedPageFile(pageIndex, i, i + offset)) {
                        Log.e(TAG, "Failed to rename page " + i + " to " + (i + offset));
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * 智能增量更新模式（供单元测试或无预建索引调用）
     */
    boolean handleSmartIncremental(UniFile dir, UpdateRequest request, int oldPages, int newPages) {
        return handleSmartIncremental(dir, buildPageFileIndex(dir), request, oldPages, newPages);
    }

    /**
     * 智能增量更新模式：根据逐页比对映射表，保留相同页并重命名为新版页码，删除差异页以触发下载补齐。
     * 采用两阶段重命名（先加临时前缀、再转正），彻底避免原地重命名时互相覆盖冲突。
     */
    boolean handleSmartIncremental(UniFile dir, Map<Integer, UniFile> pageIndex, UpdateRequest request, int oldPages, int newPages) {
        Map<Integer, Integer> mapping = request.smartPageMapping;
        if (mapping == null) {
            return false;
        }
        Log.i(TAG, "Smart incremental: reusing " + mapping.size() + " pages out of old=" + oldPages + ", new=" + newPages);

        // 1. 删除不在保留映射表中的所有旧页面文件（差异页、微改页、多余页）
        for (Map.Entry<Integer, UniFile> entry : new ArrayList<>(pageIndex.entrySet())) {
            if (cancelled) return false;
            if (!mapping.containsKey(entry.getKey())) {
                deleteIndexedPageFile(pageIndex, entry.getKey());
            }
        }

        // 2. 第一阶段重命名：将保留的旧页重命名为临时名字 __inc_tmp_%08d<ext>
        String tempPrefix = "__inc_tmp_";
        List<String> tempNames = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : mapping.entrySet()) {
            if (cancelled) return false;
            int oldIndex = entry.getKey();
            int newIndex = entry.getValue();
            if (newIndex < 1 || newIndex > newPages) {
                continue;
            }
            if (oldIndex == newIndex) {
                // 位置不变，无需重命名
                continue;
            }
            UniFile oldFile = pageIndex.get(oldIndex);
            if (oldFile == null || !oldFile.isFile()) {
                // 旧版局部页面本身缺失，跳过即可，下载器会自动补齐
                continue;
            }
            String ext = extractExtension(oldFile);
            if (ext == null) {
                continue;
            }
            String tempName = tempPrefix + String.format(Locale.US, "%08d", newIndex) + ext;
            if (!oldFile.renameTo(tempName)) {
                Log.e(TAG, "Smart incremental temp rename failed: " + oldFile.getName() + " -> " + tempName);
                return false;
            }
            tempNames.add(tempName);
        }

        // 3. 第二阶段重命名：去掉临时前缀，转正为最终的新版页码文件名
        for (String tempName : tempNames) {
            if (cancelled) return false;
            UniFile tempFile = dir.findFile(tempName);
            if (tempFile != null && tempFile.isFile()) {
                String finalName = tempName.substring(tempPrefix.length());
                if (!tempFile.renameTo(finalName)) {
                    Log.e(TAG, "Smart incremental final rename failed: " + tempName + " -> " + finalName);
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * 扫描目录中现存页面文件的最大页码
     */
    private int countMaxPageFromDir(UniFile dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        int maxPage = 0;
        try {
            UniFile[] files = dir.listFiles();
            if (files != null) {
                for (UniFile f : files) {
                    if (f != null && f.isFile()) {
                        String name = f.getName();
                        if (name != null && name.length() > 8 && name.charAt(8) == '.') {
                            try {
                                int page = Integer.parseInt(name.substring(0, 8));
                                if (page > maxPage) {
                                    maxPage = page;
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to count pages from dir", t);
        }
        return maxPage;
    }

    /**
     * 解析旧画廊的真实页数
     */
    private int resolveOldPages(@NonNull DownloadInfo oldInfo, @Nullable UniFile oldDir) {
        if (oldInfo.pages > 0) {
            return oldInfo.pages;
        }

        // 1. 尝试从 SpiderInfo 获取
        try {
            SpiderInfo spiderInfo = SpiderInfo.getSpiderInfo(oldInfo);
            if (spiderInfo != null && spiderInfo.pages > 0) {
                oldInfo.pages = spiderInfo.pages;
                Log.i(TAG, "Resolved oldPages from SpiderInfo: " + spiderInfo.pages);
                return spiderInfo.pages;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to get SpiderInfo for old gallery", t);
        }

        // 2. 尝试从旧目录下的 .ehviewer 文件读取
        if (oldDir != null && oldDir.isDirectory()) {
            try {
                UniFile ehFile = oldDir.findFile(DownloadManager.DOWNLOAD_INFO_FILENAME);
                if (ehFile != null && ehFile.isFile()) {
                    SpiderInfo si = SpiderInfo.readHeader(ehFile);
                    if (si != null && si.pages > 0) {
                        oldInfo.pages = si.pages;
                        Log.i(TAG, "Resolved oldPages from .ehviewer: " + si.pages);
                        return si.pages;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to read .ehviewer from oldDir", t);
            }
        }

        // 3. 尝试从网络拉取旧版本 GalleryDetail
        if (oldInfo.gid > 0 && oldInfo.token != null) {
            try {
                GalleryDetail oldDetail = fetchGalleryDetail(oldInfo.gid, oldInfo.token);
                if (oldDetail != null && oldDetail.pages > 0) {
                    oldInfo.pages = oldDetail.pages;
                    Log.i(TAG, "Resolved oldPages from network detail: " + oldDetail.pages);
                    return oldDetail.pages;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to fetch old gallery detail from network", t);
            }
        }

        // 4. 兜底：扫描目录中的页面文件计算最大页码
        if (oldDir != null && oldDir.isDirectory()) {
            int maxPage = countMaxPageFromDir(oldDir);
            if (maxPage > 0) {
                oldInfo.pages = maxPage;
                Log.i(TAG, "Resolved oldPages from disk files: " + maxPage);
                return maxPage;
            }
        }

        return oldInfo.pages;
    }

    /**
     * 获取 GalleryDetail
     */
    @Nullable
    private GalleryDetail fetchGalleryDetail(long gid, String token) {
        try {
            String url = EhUrl.getGalleryDetailUrl(gid, token);
            OkHttpClient client = EhApplication.getOkHttpClient(context);
            return EhEngine.getGalleryDetail(null, client, url);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to fetch gallery detail: gid=" + gid, e);
            return null;
        }
    }
}
