package com.hippo.ehviewer.download;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhEngine;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryUpdateDialog;
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryUpdateDialog.UpdateRequest;
import com.hippo.ehviewer.util.CbzUtils;
import com.hippo.lib.yorozuya.FileUtils;
import com.hippo.unifile.UniFile;

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
                    return doUpdate(oldInfo, request);
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
                            @NonNull UpdateRequest request) throws Exception {

        // ===== 阶段一：验证和准备 =====

        // 1. 获取新版本的 GalleryDetail
        Log.i(TAG, "Step 1: Fetching new version info, url=" + request.newVersion.versionUrl);
        String[] urlParts = request.newVersion.versionUrl.split("/");
        if (urlParts.length < 2) {
            Log.e(TAG, "Invalid version URL: " + request.newVersion.versionUrl);
            return "Invalid version URL";
        }
        long newGid = Long.parseLong(urlParts[urlParts.length - 2]);
        String newToken = urlParts[urlParts.length - 1];
        Log.i(TAG, "New version: gid=" + newGid + ", token=" + newToken);

        GalleryDetail newDetail = fetchGalleryDetail(newGid, newToken);
        if (newDetail == null) {
            Log.e(TAG, "Failed to fetch new version detail");
            return "Failed to fetch new version details";
        }
        if (cancelled) return "Cancelled";

        int oldPages = oldInfo.pages;
        int newPages = newDetail.pages;
        Log.i(TAG, "Pages: old=" + oldPages + ", new=" + newPages);

        // 2. 获取旧的下载目录
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

        // 3. 解压 CBZ（如果有）— 必须在模式处理之前，否则页面文件不可见
        Log.i(TAG, "Step 3: Checking CBZ in " + oldDirname);
        if (CbzUtils.isCbzMode(oldDir)) {
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

        // 4. 根据模式处理文件操作（页面重编号等）
        Log.i(TAG, "Step 4: Processing mode=" + request.mode);
        switch (request.mode) {
            case GalleryUpdateDialog.MODE_UPDATE_BACK:
                if (!handleUpdateBack(oldDir, oldPages, newPages)) {
                    return "Failed to process files for update-back mode";
                }
                break;
            case GalleryUpdateDialog.MODE_UPDATE_FRONT:
                if (!handleUpdateFront(oldDir, oldPages, newPages)) {
                    return "Failed to process files for update-front mode";
                }
                break;
            case GalleryUpdateDialog.MODE_FULL_REPLACE:
                if (!handleFullReplace(oldDir, oldInfo)) {
                    return "Failed to delete old directory";
                }
                break;
            case GalleryUpdateDialog.MODE_CUSTOM:
                if (!handleCustom(oldDir, request, oldPages, newPages)) {
                    return "Failed to process files for custom mode";
                }
                break;
            default:
                return "Unknown update mode";
        }
        if (cancelled) return "Cancelled";

        // ===== 阶段二：重命名目录 =====

        // 5. 重命名目录（全部替换模式已删除目录，跳过）
        String newTitle = com.hippo.ehviewer.client.EhUtils.getSuitableTitle(newDetail);
        String newDirname = FileUtils.sanitizeFilename(newGid + "-" + newTitle);

        if (request.mode != GalleryUpdateDialog.MODE_FULL_REPLACE) {
            String currentDirName = oldDir.getName();
            if (currentDirName == null || !currentDirName.startsWith(String.valueOf(oldInfo.gid))) {
                Log.e(TAG, "Safety check failed: dir='" + currentDirName + "', gid=" + oldInfo.gid);
                return "Safety check failed: directory '" + currentDirName + "' does not belong to gid " + oldInfo.gid;
            }

            if (!oldDirname.equals(newDirname)) {
                UniFile newDir = downloadDir.subFile(newDirname);
                if (newDir != null && newDir.isDirectory()) {
                    Log.e(TAG, "Target dir already exists: " + newDirname);
                    return "Target directory already exists: " + newDirname + ". Update aborted to prevent data loss.";
                }

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

        Intent startIntent = new Intent(context, DownloadService.class);
        startIntent.setAction(DownloadService.ACTION_START);
        startIntent.putExtra(DownloadService.KEY_GALLERY_INFO, newDetail);
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
     * 更新后方：前N页相同，只需下载新增的后几页
     */
    private boolean handleUpdateBack(UniFile dir, int oldPages, int newPages) {
        Log.i(TAG, "Update back: old=" + oldPages + " pages, new=" + newPages + " pages");
        return true;
    }

    /**
     * 更新前方：新版前面多了页，需要重编号已有的旧页
     */
    private boolean handleUpdateFront(UniFile dir, int oldPages, int newPages) {
        int addedPages = newPages - oldPages;
        if (addedPages <= 0) {
            return true;
        }
        Log.i(TAG, "Update front: " + addedPages + " pages added, renumbering " + oldPages + " old pages");

        // 预检查：确认所有待重命名的源文件都存在
        for (int i = 1; i <= oldPages; i++) {
            if (!findPageFile(dir, i)) {
                Log.e(TAG, "Pre-check failed: page " + i + " not found");
                return false;
            }
        }

        // 从后往前重命名，避免文件名冲突
        // 失败时高位文件已在新位置，低位文件原位不动，不会冲突
        for (int i = oldPages; i >= 1; i--) {
            if (cancelled) return false;
            if (!renamePageFile(dir, i, i + addedPages)) {
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
    private boolean handleCustom(UniFile dir, UpdateRequest request, int oldPages, int newPages) {
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
        if (oldEnd > oldPages) {
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
            if (!findPageFile(dir, i)) {
                Log.e(TAG, "Pre-check failed: page " + i + " not found");
                return false;
            }
        }

        if (offset != 0) {
            if (offset > 0) {
                for (int i = oldEnd; i >= oldStart; i--) {
                    if (cancelled) return false;
                    if (!renamePageFile(dir, i, i + offset)) {
                        Log.e(TAG, "Failed to rename page " + i + " to " + (i + offset));
                        return false;
                    }
                }
            } else {
                for (int i = oldStart; i <= oldEnd; i++) {
                    if (cancelled) return false;
                    if (!renamePageFile(dir, i, i + offset)) {
                        Log.e(TAG, "Failed to rename page " + i + " to " + (i + offset));
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * 查找页面文件，返回 [UniFile, 扩展名]，找不到返回 null
     */
    @Nullable
    private String findPageFileExt(UniFile dir, int pageIndex) {
        String name = String.format("%08d", pageIndex);
        String[] extensions = {".jpg", ".png", ".gif", ".webp", ".bmp"};
        for (String ext : extensions) {
            UniFile f = dir.findFile(name + ext);
            if (f != null && f.isFile()) {
                return ext;
            }
        }
        // 回退：遍历目录查找以 name 开头的文件
        try {
            for (UniFile child : dir.listFiles()) {
                if (child.isFile() && child.getName() != null &&
                        child.getName().startsWith(name)) {
                    return child.getName().substring(name.length());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to list files", e);
        }
        return null;
    }

    /**
     * 检查页面文件是否存在
     */
    private boolean findPageFile(UniFile dir, int pageIndex) {
        return findPageFileExt(dir, pageIndex) != null;
    }

    /**
     * 重命名页面文件
     */
    private boolean renamePageFile(UniFile dir, int oldIndex, int newIndex) {
        if (oldIndex == newIndex) return true;

        String oldName = String.format("%08d", oldIndex);
        String newName = String.format("%08d", newIndex);

        String ext = findPageFileExt(dir, oldIndex);
        if (ext == null) {
            Log.e(TAG, "Page file not found: " + oldName + ".*");
            return false;
        }

        UniFile oldFile = dir.findFile(oldName + ext);
        if (oldFile == null) {
            Log.e(TAG, "Page file disappeared: " + oldName + ext);
            return false;
        }

        String newFileName = newName + ext;
        if (!oldFile.renameTo(newFileName)) {
            Log.e(TAG, "Rename failed: " + oldName + ext + " -> " + newFileName);
            return false;
        }
        return true;
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
