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

package com.hippo.ehviewer.spider;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Looper;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.BuildConfig;
import com.hippo.beerbelly.SimpleDiskCache;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.gallery.GalleryProvider2;

import com.hippo.ehviewer.smb.Client;
import com.hippo.ehviewer.smb.SmbPathResolver;
import com.hippo.io.UniFileInputStreamPipe;
import com.hippo.io.UniFileOutputStreamPipe;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;
import com.hippo.unifile.FilenameFilter;
import com.hippo.unifile.UniFile;
import com.hippo.lib.yorozuya.FileUtils;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.lib.yorozuya.MathUtils;
import com.hippo.lib.yorozuya.Utilities;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class SpiderDen {

    @NonNull
    private final GalleryInfo mGalleryInfo;
    private final Object mDownloadDirLock = new Object();
    @Nullable
    private UniFile mDownloadDir;
    private volatile int mMode = SpiderQueen.MODE_READ;


    private long mGid;

    // SMB 最近一次错误信息（含完整路径），供上层显示
    @Nullable
    private volatile String mLastSmbError;

    @Nullable
    private static SimpleDiskCache sCache;

    // SMB 扩展名缓存：gid -> 已发现的文件扩展名（如 ".jpg"），避免重复探测
    private static final ConcurrentHashMap<Long, String> sSmbExtCache = new ConcurrentHashMap<>();

    public static void initialize(Context context) {
        sCache = new SimpleDiskCache(new File(context.getCacheDir(), "image"),
                MathUtils.clamp(Settings.getReadCacheSize(), 40, 640) * 1024 * 1024);
    }

    public static class StartWithFilenameFilter implements FilenameFilter {

        private final String mPrefix;

        public StartWithFilenameFilter(String prefix) {
            mPrefix = prefix;
        }

        @Override
        public boolean accept(UniFile dir, String filename) {
            return filename.startsWith(mPrefix);
        }
    }

    @Nullable
    private static String findDownloadDirname(GalleryInfo galleryInfo, UniFile parentDir) {
        String dirname = EhDB.getDownloadDirname(galleryInfo.gid);
        if (null != dirname) {
            // Some dirname may be invalid in some version
            dirname = FileUtils.sanitizeFilename(dirname);
            EhDB.putDownloadDirname(galleryInfo.gid, dirname);
            return dirname;
        }

        // Directory listing is slow on SAF and must not run on the main thread.
        if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
            return null;
        }

        try {
            UniFile[] files = parentDir.listFiles(new StartWithFilenameFilter(galleryInfo.gid + "-"));
            if (null != files) {
                // Get max-length-name dir
                int maxLength = -1;
                for (UniFile file : files) {
                    if (file.isDirectory()) {
                        String name = file.getName();
                        int length = name.length();
                        if (length > maxLength) {
                            maxLength = length;
                            dirname = name;
                        }
                    }
                }
                if (null != dirname) {
                    EhDB.putDownloadDirname(galleryInfo.gid, dirname);
                }
            }
        } catch (Exception e) {
            // Failed to list files, maybe storage is unavailable or permission lost
            android.util.Log.w("SpiderDen", "Failed to list files in download directory", e);
        }
        return dirname;
    }

    /**
     * Returns the gallery download folder if it already exists or is known in DB.
     * Never creates a new folder. Safe to call from the UI thread when deleting downloads.
     */
    @Nullable
    public static UniFile getExistingGalleryDownloadDir(GalleryInfo galleryInfo) {
        UniFile dir = Settings.getDownloadLocation();
        if (dir == null) {
            return null;
        }
        String dirname = findDownloadDirname(galleryInfo, dir);
        return dirname != null ? dir.subFile(dirname) : null;
    }

    public static UniFile getGalleryDownloadDir(GalleryInfo galleryInfo) {
        UniFile dir = Settings.getDownloadLocation();
        if (dir != null) {
            String dirname = findDownloadDirname(galleryInfo, dir);

            // Create it
            if (null == dirname) {
                dirname = FileUtils.sanitizeFilename(galleryInfo.gid + "-" + EhUtils.getSuitableTitle(galleryInfo));
                EhDB.putDownloadDirname(galleryInfo.gid, dirname);
            }

            return dir.subFile(dirname);
        } else {
            return null;
        }
    }

    public SpiderDen(GalleryInfo galleryInfo) {
        mGalleryInfo = galleryInfo;
        mGid = galleryInfo.gid;
    }

    /**
     * Resolves {@link #mDownloadDir}. Must be called with {@link #mDownloadDirLock} held.
     * On the main thread, skips SAF directory listing when the DB has no folder name yet
     * (that listing runs on a worker via {@link #prepareDownloadStorage()}).
     */
    @Nullable
    private UniFile resolveDownloadDirLocked() {
        if (mDownloadDir != null) {
            return mDownloadDir;
        }
        if (Looper.getMainLooper().getThread() == Thread.currentThread()
                && EhDB.getDownloadDirname(mGalleryInfo.gid) == null) {
            return null;
        }
        mDownloadDir = getGalleryDownloadDir(mGalleryInfo);
        // 阅读模式下只检查目录是否存在，不创建目录
        if (mDownloadDir != null && mMode == SpiderQueen.MODE_DOWNLOAD) {
            mDownloadDir.ensureDir();
        }
        return mDownloadDir;
    }

    /**
     * Ensures the gallery download folder exists. Call from downloader worker threads only.
     */
    public boolean prepareDownloadStorage() {
        if (mMode != SpiderQueen.MODE_DOWNLOAD) {
            return true;
        }
        synchronized (mDownloadDirLock) {
            if (mDownloadDir == null) {
                mDownloadDir = getGalleryDownloadDir(mGalleryInfo);
            }
            return mDownloadDir != null && mDownloadDir.ensureDir();
        }
    }

    public void setMGid(long mGid) {
        this.mGid = mGid;
    }

    public void setMode(@SpiderQueen.Mode int mode) {
        mMode = mode;
    }

    public boolean isDownloadMode() {
        return mMode == SpiderQueen.MODE_DOWNLOAD;
    }

    private boolean shouldSyncDownloadWhileReading() {
        return mMode == SpiderQueen.MODE_READ && Settings.getSyncDownloadWhileReading();
    }

    public boolean shouldWriteToDownloadDir() {
        return isDownloadMode() || shouldSyncDownloadWhileReading();
    }

    private boolean ensureDownloadDirExists() {
        synchronized (mDownloadDirLock) {
            if (mDownloadDir == null) {
                mDownloadDir = getGalleryDownloadDir(mGalleryInfo);
            }
            return mDownloadDir != null && mDownloadDir.ensureDir();
        }
    }

    public boolean isReady() {
        switch (mMode) {
            case SpiderQueen.MODE_READ:
                return sCache != null;
            case SpiderQueen.MODE_DOWNLOAD:
                synchronized (mDownloadDirLock) {
                    return mDownloadDir != null && mDownloadDir.isDirectory();
                }
            default:
                return false;
        }
    }

    @Nullable
    public UniFile getDownloadDir() {
        UniFile dir;
        synchronized (mDownloadDirLock) {
            dir = resolveDownloadDirLocked();
        }
        return dir != null && dir.isDirectory() ? dir : null;
    }

    @Nullable
    public UniFile getDownloadDirName() {
        synchronized (mDownloadDirLock) {
            return resolveDownloadDirLocked();
        }
    }

    private boolean containInCache(int index) {
        if (sCache == null) {
            return false;
        }

        String key = EhCacheKeyFactory.getImageKey(mGid, index);
        return sCache.contain(key);
    }

    /**
     * @param extension with dot
     */
    public static String generateImageFilename(int index, String extension) {
        return String.format(Locale.US, "%08d%s", index + 1, extension);
    }

    /**
     * 在 ZipInputStream 中查找匹配的图片条目（支持模糊匹配）
     * 
     * 匹配策略（按优先级）：
     * 1. 精确匹配 "00000001.jpg"（8位前导零 + 索引）
     * 2. 去除前导零匹配 "1.jpg"
     * 3. 1-indexed 匹配 "2.jpg"（index+1，有些漫画是1-indexed）
     * 4. 简短数字匹配 "01.jpg" 或 "001.jpg"
     * 5. 返回第一个图片文件（作为首页兜底）
     * 
     * @param zis Zip输入流（当前位置之后的条目）
     * @param index 图片索引（0-based）
     * @param exts 支持的图片扩展名数组
     * @return 匹配的图片条目名称，未找到返回 null
     */
    @Nullable
    private static String findMatchingEntryInZip(ZipInputStream zis, int index, String[] exts) {
        String exactExpect = generateImageFilename(index, ""); // "00000001"
        String zeroStripped = String.valueOf(index + 1);       // "1"
        String oneIndexed = String.valueOf(index + 2);          // "2" (1-indexed fallback)
        
        String firstImage = null;
        ZipEntry entry;
        try {
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String en = entry.getName();
                if (en == null) continue;
                
                // 检查扩展名是否支持
                String lowerEn = en.toLowerCase(java.util.Locale.US);
                boolean hasValidExt = false;
                for (String ext : exts) {
                    if (lowerEn.endsWith(ext.toLowerCase(java.util.Locale.US))) {
                        hasValidExt = true;
                        break;
                    }
                }
                if (!hasValidExt) continue;
                
                // 记录第一个图片文件作为兜底
                if (firstImage == null) {
                    firstImage = en;
                }
                
                // 提取文件名部分（不含扩展名）
                int lastDot = en.lastIndexOf('.');
                String namePart = lastDot > 0 ? en.substring(0, lastDot) : en;
                String nameLower = namePart.toLowerCase(java.util.Locale.US);
                
                // 策略1: 精确匹配 "00000001"
                if (nameLower.equals(exactExpect.toLowerCase(java.util.Locale.US))) {
                    return en;
                }
                
                // 策略2: 去除前导零匹配 "1"
                String zeroStrippedLower = nameLower.replaceFirst("^0+", "");
                if (zeroStrippedLower.equals(zeroStripped)) {
                    return en;
                }
                
                // 策略3: 1-indexed 匹配 "2"
                if (nameLower.equals(oneIndexed)) {
                    return en;
                }
                
                // 策略4: 简短数字匹配 "01", "001"
                if (nameLower.matches("^0+" + (index + 1) + "$")) {
                    return en;
                }
            }
        } catch (IOException e) {
            // 忽略 IO 异常
        }
        
        // 兜底：返回第一个图片文件
        return firstImage;
    }

    @Nullable
    public static UniFile findImageFile(UniFile dir, int index) {
        for (String extension : GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            String filename = generateImageFilename(index, extension);
            UniFile file = dir.findFile(filename);
            if (file != null) {
                return file;
            }
        }
        return null;
    }

    private boolean containInDownloadDir(int index) {
        UniFile dir = getDownloadDir();
        if (dir == null) {
            return false;
        }

        // Find image file in download dir
        UniFile img = findImageFile(dir, index);
        if (img != null) return true;
        // 若是 CBZ 模式：检查压缩包内是否包含对应条目
        UniFile cbz = com.hippo.ehviewer.util.CbzUtils.findCbzFile(dir);
        if (cbz == null) return false;
        java.io.InputStream base = null; ZipInputStream zis = null;
        try {
            base = cbz.openInputStream();
            zis = new ZipInputStream(base);
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name == null) continue;
                for (String ext : com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
                    String expect = generateImageFilename(index, ext);
                    if (expect.equalsIgnoreCase(name)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignore) {
            // 忽略解析失败，视为不存在
        } finally {
            com.hippo.lib.yorozuya.IOUtils.closeQuietly(zis);
            com.hippo.lib.yorozuya.IOUtils.closeQuietly(base);
        }
        return false;
    }

    /**
     * @param extension with dot
     */
    private String fixExtension(String extension) {
        if (Utilities.contain(GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS, extension)) {
            return extension;
        } else {
            return GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS[0];
        }
    }

    private boolean copyFromCacheToDownloadDir(int index) {
        if (sCache == null) {
            return false;
        }
        UniFile dir = getDownloadDir();
        if (dir == null) {
            return false;
        }
        // Find image file in cache
        String key = EhCacheKeyFactory.getImageKey(mGid, index);
        InputStreamPipe pipe = sCache.getInputStreamPipe(key);
        if (pipe == null) {
            return false;
        }

        OutputStream os = null;
        try {
            // Get extension
            String extension;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            pipe.obtain();
            BitmapFactory.decodeStream(pipe.open(), null, options);
            pipe.close();
            extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(options.outMimeType);
            if (extension != null) {
                extension = '.' + extension;
            } else {
                return false;
            }
            // Fix extension
            extension = fixExtension(extension);
            // Copy from cache to download dir
            UniFile file = dir.createFile(generateImageFilename(index, extension));
            if (file == null) {
                return false;
            }
            os = file.openOutputStream();
            IOUtils.copy(pipe.open(), os);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            IOUtils.closeQuietly(os);
            pipe.close();
            pipe.release();
        }
    }

    public boolean contain(int index) {
        if (mMode == SpiderQueen.MODE_READ) {
            if (containInCache(index) || containInDownloadDir(index)) {
                return true;
            }
            // 扩展名缓存命中意味着该gallery已在SMB上成功读取过，无需再次探测
            if (sSmbExtCache.containsKey(mGid)) {
                return true;
            }
            // 首次访问：SMB 探测可读性（打开即视为存在，立即关闭）
            InputStreamPipe smbProbe = openSmbInputStreamPipe(index);
            if (smbProbe != null) {
                try {
                    smbProbe.obtain();
                    java.io.InputStream tmp = smbProbe.open();
                    // 成功打开即认为存在
                    return true;
                } catch (Throwable ignore) {
                    // 视为不存在
                } finally {
                    try { smbProbe.close(); } catch (Throwable ignore) {}
                    try { smbProbe.release(); } catch (Throwable ignore) {}
                }
            }
            return false;
        } else if (mMode == SpiderQueen.MODE_DOWNLOAD) {
            return containInDownloadDir(index) || copyFromCacheToDownloadDir(index);
        } else {
            return false;
        }
    }

    private boolean removeFromCache(int index) {
        if (sCache == null) {
            return false;
        }

        String key = EhCacheKeyFactory.getImageKey(mGid, index);
        return sCache.remove(key);
    }

    private boolean removeFromDownloadDir(int index) {
        UniFile dir = getDownloadDir();
        if (dir == null) {
            return false;
        }

        boolean result = false;
        for (int i = 0, n = GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS.length; i < n; i++) {
            String filename = generateImageFilename(index, GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS[i]);
            UniFile file = dir.subFile(filename);
            if (file != null) {
                result |= file.delete();
            }
        }
        return result;
    }

    public boolean remove(int index) {
        boolean result = removeFromCache(index);
        result |= removeFromDownloadDir(index);
        return result;
    }

    @Nullable
    private OutputStreamPipe openCacheOutputStreamPipe(int index) {
        if (sCache == null) {
            return null;
        }

        String key = EhCacheKeyFactory.getImageKey(mGid, index);
        return sCache.getOutputStreamPipe(key);
    }

    /**
     * @param extension without dot
     */
    @Nullable
    private OutputStreamPipe openDownloadOutputStreamPipe(int index, @Nullable String extension) {
        UniFile dir = getDownloadDir();
        if (dir == null) {
            return null;
        }
        if (extension==null||!extension.contains(".")){
            extension = fixExtension('.' + extension);
        }else {
            extension = fixExtension(extension);
        }

        UniFile file = dir.createFile(generateImageFilename(index, extension));
        if (file != null) {
            return new UniFileOutputStreamPipe(file);
        } else {
            return null;
        }
    }

    @Nullable
    public OutputStreamPipe openOutputStreamPipe(int index, @Nullable String extension) {
        if (mMode == SpiderQueen.MODE_READ) {
            if (shouldSyncDownloadWhileReading() && ensureDownloadDirExists()) {
                OutputStreamPipe pipe = openDownloadOutputStreamPipe(index, extension);
                if (pipe != null) {
                    return pipe;
                }
            }
            return openCacheOutputStreamPipe(index);
        } else if (mMode == SpiderQueen.MODE_DOWNLOAD) {
            return openDownloadOutputStreamPipe(index, extension);
        } else {
            return null;
        }
    }


    @Nullable
    private InputStreamPipe openCacheInputStreamPipe(int index) {
        if (sCache == null) {
            return null;
        }

        String key = EhCacheKeyFactory.getImageKey(mGid, index);
        return sCache.getInputStreamPipe(key);
    }

    @Nullable
    public InputStreamPipe openDownloadInputStreamPipe(int index) {
        UniFile dir = getDownloadDir();
        if (dir == null) {
            return null;
        }

        for (int i = 0; i < 2; i++) {
            UniFile file = findImageFile(dir, index);
            if (file != null) {
                return new UniFileInputStreamPipe(file);
            } else if (!copyFromCacheToDownloadDir(index)) {
                // 未找到物理图片，尝试从 CBZ 中读取
                UniFile cbz = com.hippo.ehviewer.util.CbzUtils.findCbzFile(dir);
                if (cbz != null) {
                    final String[] exts = com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS;
                    // 返回一个 InputStreamPipe：每次 open 时新建 ZipInputStream，定位到目标条目
                    return new InputStreamPipe() {
                        private ZipInputStream mZis;
                        private java.io.InputStream mBase;
                        @Override public void obtain() { }
                        @Override public void release() { }
                        @Override public java.io.InputStream open() throws IOException {
                            mBase = cbz.openInputStream();
                            mZis = new ZipInputStream(mBase);
                            ZipEntry entry;
                            // 依次尝试不同扩展名
                            String name1 = null; ZipEntry found = null;
                            outer: while ((entry = mZis.getNextEntry()) != null) {
                                String en = entry.getName();
                                if (en == null || entry.isDirectory()) continue;
                                for (String ext : exts) {
                                    String expect = generateImageFilename(index, ext);
                                    if (expect.equalsIgnoreCase(en)) { found = entry; name1 = en; break outer; }
                                }
                            }
                            if (found == null) {
                                // 未找到，关闭并返回空
                                close();
                                throw new IOException("Entry not found in CBZ for index=" + index);
                            }
                            // 直接返回当前 ZipInputStream（指向该 entry 数据段）
                            return mZis;
                        }
                        @Override public void close() {
                            com.hippo.lib.yorozuya.IOUtils.closeQuietly(mZis);
                            com.hippo.lib.yorozuya.IOUtils.closeQuietly(mBase);
                            mZis = null; mBase = null;
                        }
                    };
                }
                return null;
            }
        }

        return null;
    }

    /**
     * 获取最近一次 SMB 访问的错误信息（含完整路径），供上层显示。
     * 调用 openInputStreamPipe 后若返回 null，可检查此值。
     */
    @Nullable
    public String getLastSmbError() {
        return mLastSmbError;
    }

    private InputStreamPipe openDownloadInputStreamPipeReadOnly(int index) {
        UniFile dir = getDownloadDir();
        if (dir == null) {
            return null;
        }
        UniFile file = findImageFile(dir, index);
        if (file != null) {
            return new UniFileInputStreamPipe(file);
        }
        // 尝试从 CBZ 中读取
        UniFile cbz = com.hippo.ehviewer.util.CbzUtils.findCbzFile(dir);
        if (cbz == null) {
            return null;
        }
        final String[] exts = GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS;
        final UniFile cbzFinal = cbz;
        return new InputStreamPipe() {
            private ZipInputStream mZis;
            private java.io.InputStream mBase;
            @Override public void obtain() { }
            @Override public void release() { }
            @Override public java.io.InputStream open() throws IOException {
                mBase = cbzFinal.openInputStream();
                mZis = new ZipInputStream(mBase);
                ZipEntry entry;
                while ((entry = mZis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String en = entry.getName();
                    if (en == null) continue;
                    for (String ext : exts) {
                        String expect = generateImageFilename(index, ext);
                        if (expect.equalsIgnoreCase(en)) {
                            return mZis;
                        }
                    }
                }
                close();
                throw new IOException("Entry not found in CBZ for index=" + index);
            }
            @Override public void close() {
                com.hippo.lib.yorozuya.IOUtils.closeQuietly(mZis);
                com.hippo.lib.yorozuya.IOUtils.closeQuietly(mBase);
                mZis = null; mBase = null;
            }
        };
    }

    @Nullable
    public InputStreamPipe openInputStreamPipe(int index) {
        if (mMode == SpiderQueen.MODE_READ) {
            if (shouldSyncDownloadWhileReading()) {
                InputStreamPipe pipe = openDownloadInputStreamPipe(index);
                if (pipe == null) {
                    pipe = openCacheInputStreamPipe(index);
                }
                return pipe;
            }
            // 1) 缓存
            InputStreamPipe pipe = openCacheInputStreamPipe(index);
            if (pipe != null) return pipe;

            // 2) 本地下载目录（只读）
            pipe = openDownloadInputStreamPipeReadOnly(index);
            if (pipe != null) return pipe;

            // 3) SMB（在网络之前）
            InputStreamPipe smbPipe = openSmbInputStreamPipe(index);
            if (smbPipe != null) return smbPipe;

            // 4) 返回 null 触发网络
            return null;
        } else if (mMode == SpiderQueen.MODE_DOWNLOAD) {
            return openDownloadInputStreamPipe(index);
        } else {
            return null;
        }
    }


    /**
     * 尝试使用流式读取 CBZ（只解析 ZIP CD + 按需解压单个 entry）。
     * 比下载整个 CBZ 快得多，尤其适合大文件。
     * @return InputStreamPipe 如果成功，null 如果需要回退到其他方法
     */
    @Nullable
    private InputStreamPipe tryOpenSmbStreamArchive(int index, Client.Target resolved) {
        try {
            // 确定 CBZ 路径
            String normBase = resolved.getPathInShare().replace('/', '\\').replaceAll("^\\\\+|\\\\+$", "");
            String cbzPath;
            if (normBase.toLowerCase(java.util.Locale.US).endsWith(".cbz")) {
                cbzPath = normBase;
            } else {
                String gidCbz = mGid + ".cbz";
                cbzPath = normBase.isEmpty() ? gidCbz : (normBase + "\\" + gidCbz);
            }

            android.util.Log.i("SpiderDen", "tryOpenSmbStreamArchive: gid=" + mGid + ", index=" + index + ", cbzPath=" + cbzPath);

            // 查询文件大小
            long fileSize = -1;
            try {
                Client.Target cbzTarget = new Client.Target(resolved.getAuthority(), resolved.getShare(), cbzPath);
                fileSize = Client.INSTANCE.getFileSize(cbzTarget);
            } catch (Throwable e) {
                android.util.Log.w("SpiderDen", "tryOpenSmbStreamArchive: getFileSize failed", e);
            }
            android.util.Log.i("SpiderDen", "tryOpenSmbStreamArchive: fileSize=" + fileSize);
            if (fileSize <= 0) return null;

            // 检查页面缓存
            String cacheKey = com.hippo.ehviewer.smb.ArchiveStreamPageCache.INSTANCE.generateCacheKey(
                resolved.getAuthority(), resolved.getShare(), cbzPath);

            // 尝试从缓存获取扩展名
            String ext = com.hippo.ehviewer.smb.ArchiveStreamPageCache.INSTANCE.getCachedPage(cacheKey, index, "jpg") != null ? "jpg" :
                         com.hippo.ehviewer.smb.ArchiveStreamPageCache.INSTANCE.getCachedPage(cacheKey, index, "png") != null ? "png" :
                         com.hippo.ehviewer.smb.ArchiveStreamPageCache.INSTANCE.getCachedPage(cacheKey, index, "webp") != null ? "webp" : null;

            if (ext != null) {
                java.io.File cachedPage = com.hippo.ehviewer.smb.ArchiveStreamPageCache.INSTANCE.getCachedPage(cacheKey, index, ext);
                if (cachedPage != null) {
                    android.util.Log.i("SpiderDen", "Stream archive cache HIT for page " + index);
                    final java.io.File pageFile = cachedPage;
                    return new InputStreamPipe() {
                        private java.io.InputStream mIs;
                        @Override public void obtain() { /* no-op */ }
                        @Override public void release() { /* no-op */ }
                        @Override public java.io.InputStream open() throws IOException {
                            if (mIs != null) return mIs;
                            mIs = new java.io.FileInputStream(pageFile);
                            return mIs;
                        }
                        @Override public void close() {
                            com.hippo.lib.yorozuya.IOUtils.closeQuietly(mIs);
                            mIs = null;
                        }
                    };
                }
            }

            // 缓存未命中，使用流式读取
            android.util.Log.i("SpiderDen", "Stream archive: opening " + cbzPath + " for page " + index);
            com.hippo.ehviewer.smb.SmbStreamArchiveReader.OpenResult openResult =
                com.hippo.ehviewer.smb.SmbStreamArchiveReader.openArchive(
                    resolved.getAuthority(), resolved.getShare(), cbzPath, fileSize);

            if (openResult == null) {
                android.util.Log.w("SpiderDen", "Stream archive open failed, falling back");
                return null;
            }

            // 获取页面扩展名
            String pageExt = com.hippo.ehviewer.smb.SmbStreamArchiveReader.getPageExtension(index);
            if (pageExt == null || pageExt.isEmpty()) pageExt = "jpg";

            // 提取页面
            final String finalExt = pageExt;
            final com.hippo.ehviewer.smb.SmbStreamArchiveReader.OpenResult finalResult = openResult;
            java.nio.ByteBuffer buffer = com.hippo.ehviewer.smb.SmbStreamArchiveReader.extractPage(
                openResult, index, pageExt);

            if (buffer != null) {
                android.util.Log.i("SpiderDen", "Stream archive: extracted page " + index + " successfully");
                // 页面已写入缓存，从缓存读取
                java.io.File cachedPage = com.hippo.ehviewer.smb.ArchiveStreamPageCache.INSTANCE.getCachedPage(cacheKey, index, pageExt);
                if (cachedPage != null) {
                    final java.io.File pageFile = cachedPage;
                    return new InputStreamPipe() {
                        private java.io.InputStream mIs;
                        @Override public void obtain() { /* no-op */ }
                        @Override public void release() { /* no-op */ }
                        @Override public java.io.InputStream open() throws IOException {
                            if (mIs != null) return mIs;
                            mIs = new java.io.FileInputStream(pageFile);
                            return mIs;
                        }
                        @Override public void close() {
                            com.hippo.lib.yorozuya.IOUtils.closeQuietly(mIs);
                            mIs = null;
                            // Don't close the archive here — it's shared across pages
                        }
                    };
                }
                // Fallback: return ByteBuffer directly
                final java.nio.ByteBuffer bufRef = buffer;
                return new InputStreamPipe() {
                    private java.io.InputStream mIs;
                    @Override public void obtain() { /* no-op */ }
                    @Override public void release() { /* no-op */ }
                    @Override public java.io.InputStream open() throws IOException {
                        if (mIs != null) return mIs;
                        byte[] data = new byte[bufRef.remaining()];
                        bufRef.get(data);
                        bufRef.rewind();
                        mIs = new java.io.ByteArrayInputStream(data);
                        return mIs;
                    }
                    @Override public void close() {
                        com.hippo.lib.yorozuya.IOUtils.closeQuietly(mIs);
                        mIs = null;
                    }
                };
            } else {
                android.util.Log.w("SpiderDen", "Stream archive: extract failed for page " + index);
                com.hippo.ehviewer.smb.SmbStreamArchiveReader.closeArchive(openResult);
                return null;
            }
        } catch (Throwable e) {
            android.util.Log.e("SpiderDen", "tryOpenSmbStreamArchive failed", e);
            return null;
        }
    }

    @Nullable
    private InputStreamPipe openSmbInputStreamPipe(int index) {
        mLastSmbError = null;
        // 通过 dirname 直接推导 SMB 路径
        Client.Target resolved = SmbPathResolver.resolve(mGid);
        if (resolved == null) {
            return null;
        }
        String smbFullPath = "\\\\" + resolved.getAuthority().getHost() + "\\" + resolved.getShare()
            + "\\" + resolved.getPathInShare();
        String normBase = resolved.getPathInShare().replace('/', '\\').replaceAll("^\\\\+|\\\\+$", "");
        boolean isDirectCbz = !normBase.isEmpty() && normBase.toLowerCase(java.util.Locale.US).endsWith(".cbz");
        android.util.Log.i("SpiderDen", "openSmbInputStreamPipe: gid=" + mGid + ", index=" + index
            + ", normBase=" + normBase + ", isDirectCbz=" + isDirectCbz);

        // 优先使用流式读取 CBZ（只解析 ZIP CD + 按需解压，无需下载整个文件）
        InputStreamPipe streamPipe = tryOpenSmbStreamArchive(index, resolved);
        if (streamPipe != null) {
            return streamPipe;
        }

        // 尝试所有支持的扩展名（base 为目录场景）
        android.util.Log.i("SpiderDen", "falling back to individual SMB files: gid=" + mGid + ", normBase=" + normBase);
        // 先查缓存，命中则只尝试该扩展名
        String cachedExt = sSmbExtCache.get(mGid);
        String[] extsToTry = cachedExt != null
            ? new String[]{cachedExt}
            : GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS;
        for (String ext : extsToTry) {
            String filename = generateImageFilename(index, ext);
            String rel = normBase.isEmpty() ? filename : (normBase + "\\" + filename);
            Client.Target target = new Client.Target(resolved.getAuthority(), resolved.getShare(), rel);
            try {
                java.io.InputStream is = Client.INSTANCE.openInputStream(target);
                // 首次成功时缓存扩展名
                if (cachedExt == null) {
                    sSmbExtCache.put(mGid, ext);
                }
                return new InputStreamPipe() {
                    private java.io.InputStream mIs;
                    @Override public void obtain() { /* no-op */ }
                    @Override public void release() { /* no-op */ }
                    @Override public java.io.InputStream open() { mIs = is; return mIs; }
                    @Override public void close() { com.hippo.lib.yorozuya.IOUtils.closeQuietly(mIs); mIs = null; }
                };
            } catch (Throwable e) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SpiderDen", "SMB open failed: auth=" + resolved.getAuthority() + ", share=" + resolved.getShare() + ", rel=" + rel + ", err=" + e);
                }
                mLastSmbError = "SMB: " + smbFullPath + "\\" + filename + " — " + getShortError(e);
                // 尝试下一个扩展名
            }
        }
        // 兜底：列目录查找（处理远端大小写差异或不一致扩展名）
        try {
            // 此处 base 已规范化为 normBase
            Client.Target dirTarget = new Client.Target(resolved.getAuthority(), resolved.getShare(), normBase);
            java.util.List<Client.RemoteDirEntry> entries = Client.INSTANCE.listDirectory(dirTarget);
            String indexPrefix = String.format(java.util.Locale.US, "%08d", index + 1);
            Client.RemoteDirEntry match = null;
            outer: for (Client.RemoteDirEntry e : entries) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name == null) continue;
                if (!name.toLowerCase(java.util.Locale.US).startsWith(indexPrefix)) continue;
                // 检查扩展名是否受支持（忽略大小写）
                for (String ext : GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
                    String expect = (indexPrefix + ext).toLowerCase(java.util.Locale.US);
                    if (name.toLowerCase(java.util.Locale.US).equals(expect)) {
                        match = e; break outer;
                    }
                }
            }
            if (match != null) {
                String rel = normBase.isEmpty() ? match.getName() : (normBase + "\\" + match.getName());
                Client.Target target = new Client.Target(resolved.getAuthority(), resolved.getShare(), rel);
                java.io.InputStream is = Client.INSTANCE.openInputStream(target);
                return new InputStreamPipe() {
                    private java.io.InputStream mIs;
                    @Override public void obtain() { /* no-op */ }
                    @Override public void release() { /* no-op */ }
                    @Override public java.io.InputStream open() { mIs = is; return mIs; }
                    @Override public void close() { com.hippo.lib.yorozuya.IOUtils.closeQuietly(mIs); mIs = null; }
                };
            }

            // 未匹配到单张图片时，尝试发现唯一的 .cbz 并从中读取对应条目
            String cbzName = null; int cbzCount = 0;
            String prefer = (mGid > 0 ? (mGid + ".cbz") : null);
            for (Client.RemoteDirEntry e : entries) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name == null) continue;
                if (name.toLowerCase(java.util.Locale.US).endsWith(".cbz")) {
                    cbzCount++;
                    // 优先匹配 gid 命名的 CBZ
                    if (prefer != null && name.equalsIgnoreCase(prefer)) {
                        cbzName = name; break;
                    }
                    if (cbzName == null) cbzName = name; // 记录第一个，作为回退
                    if (cbzCount > 1) break;
                }
            }
            if ((cbzCount == 1 && cbzName != null) || (cbzCount > 1 && cbzName != null && prefer != null && cbzName.equalsIgnoreCase(prefer))) {
                final String relCbz = normBase.isEmpty() ? cbzName : (normBase + "\\" + cbzName);
                final Client.Target cbzTarget = new Client.Target(resolved.getAuthority(), resolved.getShare(), relCbz);
                final String[] exts = GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS;
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SpiderDen", "SMB directory CBZ selected: gid=" + mGid + ", cbz=" + relCbz + ", count=" + cbzCount + ", prefer=" + prefer);
                }
                // 返回一个按需打开的 Zip 流：每次 open() 重新打开远端 CBZ 并定位到目标条目
                // 改进：使用模糊匹配支持多种文件名格式
                return new InputStreamPipe() {
                    private java.io.InputStream mBase;
                    private ZipInputStream mZis;
                    @Override public void obtain() { /* no-op */ }
                    @Override public void release() { /* no-op */ }
                    @Override public java.io.InputStream open() throws IOException {
                        mBase = Client.INSTANCE.openInputStream(cbzTarget);
                        mZis = new ZipInputStream(mBase);
                        
                        // 使用模糊匹配查找条目
                        String matchedEntry = findMatchingEntryInZip(mZis, index, exts);
                        
                        if (matchedEntry != null) {
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("SpiderDen", "SMB directory CBZ fuzzy match hit: gid=" + mGid + ", index=" + (index+1) + ", name=" + matchedEntry);
                            }
                            // 重置流以从头读取
                            close();
                            mBase = Client.INSTANCE.openInputStream(cbzTarget);
                            mZis = new ZipInputStream(mBase);
                            
                            // 再次遍历直到找到目标条目
                            ZipEntry entry;
                            while ((entry = mZis.getNextEntry()) != null) {
                                if (entry.isDirectory()) continue;
                                String en = entry.getName();
                                if (en != null && en.equals(matchedEntry)) {
                                    return mZis;
                                }
                            }
                        }
                        
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("SpiderDen", "SMB directory CBZ fuzzy match miss: gid=" + mGid + ", index=" + (index+1) + ", cbz=" + relCbz);
                        }
                        // 未找到目标 entry
                        close();
                        throw new IOException("Entry not found in remote CBZ for index=" + index);
                    }
                    @Override public void close() {
                        com.hippo.lib.yorozuya.IOUtils.closeQuietly(mZis);
                        com.hippo.lib.yorozuya.IOUtils.closeQuietly(mBase);
                        mZis = null; mBase = null;
                    }
                };
            }

            if (BuildConfig.DEBUG) {
                android.util.Log.d("SpiderDen", "SMB directory scan: no match for index=" + (index + 1) + ", base=" + normBase + ", gid=" + mGid);
            }
        } catch (Throwable e) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("SpiderDen", "SMB directory scan failed for gid=" + mGid + ": " + e);
            }
            mLastSmbError = "SMB: " + smbFullPath + " — " + getShortError(e);
        }
        if (mLastSmbError == null) {
            mLastSmbError = "SMB: " + smbFullPath + " — 找不到第 " + (index + 1) + " 页";
        }
        return null;
    }

    /**
     * 使用 ZipFile 随机访问从 CBZ 中读取指定图片条目 (O(1) 复杂度)
     *
     * @param zipFile 已打开的 ZipFile
     * @param index 图片索引 (0-based)
     * @param exts 支持的图片扩展名数组
     * @return 包含图片数据的 InputStream，未找到返回 null
     */


    private static String getShortError(Throwable e) {
        if (e instanceof com.hierynomus.mssmb2.SMBApiException) {
            com.hierynomus.mserref.NtStatus status = ((com.hierynomus.mssmb2.SMBApiException) e).getStatus();
            if (status == com.hierynomus.mserref.NtStatus.STATUS_NO_SUCH_FILE) return "文件不存在";
            if (status == com.hierynomus.mserref.NtStatus.STATUS_ACCESS_DENIED) return "访问被拒绝";
            if (status == com.hierynomus.mserref.NtStatus.STATUS_LOGON_FAILURE) return "认证失败";
            return "SMB错误: " + status;
        }
        if (e instanceof java.net.ConnectException) return "连接被拒绝";
        if (e instanceof java.net.SocketTimeoutException) return "连接超时";
        if (e instanceof java.net.UnknownHostException) return "无法解析主机名";
        String msg = e.getMessage();
        return msg != null ? msg : e.getClass().getSimpleName();
    }
}
