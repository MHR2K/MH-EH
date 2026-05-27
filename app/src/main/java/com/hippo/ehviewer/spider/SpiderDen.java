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
import android.webkit.MimeTypeMap;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.BuildConfig;
import com.hippo.beerbelly.SimpleDiskCache;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.gallery.GalleryProvider2;
import com.hippo.ehviewer.smb.CbzCacheManager;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class SpiderDen {

    @Nullable
    private final UniFile mDownloadDir;
    private volatile int mMode = SpiderQueen.MODE_READ;


    private long mGid;

    @Nullable
    private static SimpleDiskCache sCache;

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

    public static UniFile getGalleryDownloadDir(GalleryInfo galleryInfo) {
        UniFile dir = Settings.getDownloadLocation();
        if (dir != null) {
            // Read from DB
            String dirname = EhDB.getDownloadDirname(galleryInfo.gid);
            if (null != dirname) {
                // Some dirname may be invalid in some version
                dirname = FileUtils.sanitizeFilename(dirname);
                EhDB.putDownloadDirname(galleryInfo.gid, dirname);
            }

            // Find it
            if (null == dirname) {
                try {
                    UniFile[] files = dir.listFiles(new StartWithFilenameFilter(galleryInfo.gid + "-"));
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
                    // Continue to create new directory
                    android.util.Log.w("SpiderDen", "Failed to list files in download directory", e);
                }
            }

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
        mGid = galleryInfo.gid;
        mDownloadDir = getGalleryDownloadDir(galleryInfo);
    }

    public void setMGid(long mGid) {
        this.mGid = mGid;
    }

    public void setMode(@SpiderQueen.Mode int mode) {
        mMode = mode;

        if (mode == SpiderQueen.MODE_DOWNLOAD) {
            ensureDownloadDir();
        }
    }

    private boolean ensureDownloadDir() {
        return mDownloadDir != null && mDownloadDir.ensureDir();
    }

    public boolean isReady() {
        switch (mMode) {
            case SpiderQueen.MODE_READ:
                return sCache != null;
            case SpiderQueen.MODE_DOWNLOAD:
                return mDownloadDir != null && mDownloadDir.isDirectory();
            default:
                return false;
        }
    }

    @Nullable
    public UniFile getDownloadDir() {
        return mDownloadDir != null && mDownloadDir.isDirectory() ? mDownloadDir : null;
    }

    public UniFile getDownloadDirName() {
        return mDownloadDir != null ? mDownloadDir : null;
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
            // SMB 上是否存在：探测可读性（打开即视为存在，立即关闭）
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
            // Return the download pipe is the gallery has been downloaded
            OutputStreamPipe pipe = openDownloadOutputStreamPipe(index, extension);
            if (pipe == null) {
                pipe = openCacheOutputStreamPipe(index);
            }
            return pipe;
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

    @Nullable
    public InputStreamPipe openInputStreamPipe(int index) {
        if (mMode == SpiderQueen.MODE_READ) {
            // 1) 本地下载目录
            InputStreamPipe pipe = openDownloadInputStreamPipe(index);
            if (pipe != null) return pipe;

            // 2) 缓存
            pipe = openCacheInputStreamPipe(index);
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
     * 从 SMB 路径打开图片文件
     * （内部方法，与显式映射的逻辑一致，支持直接文件和CBZ档案）
     */
    @Nullable
    private InputStreamPipe openSmbFileFromPath(
            com.hippo.ehviewer.smb.Authority authority,
            String share,
            String basePath,
            int index) {
        // 规范化 base，统一使用 '\\' 分隔，并移除首尾分隔符
        String normBase = basePath.replace('/', '\\').replaceAll("^\\\\+|\\\\+$", "");
        final String[] exts = GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS;

        // Step 1: 尝试 <gid>.cbz （若 base 是目录）
        if (!normBase.toLowerCase(java.util.Locale.US).endsWith(".cbz")) {
            String gidCbz = mGid + ".cbz";
            String relGidCbz = normBase.isEmpty() ? gidCbz : (normBase + "\\" + gidCbz);
            Client.Target gidCbzTarget = new Client.Target(authority, share, relGidCbz);
            
            // 尝试从本地缓存获取 CBZ
            try {
                java.io.FileInputStream cachedStream = CbzCacheManager.INSTANCE.getCachedInputStream(authority, share, relGidCbz);
                if (cachedStream != null) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("SpiderDen", "SMB gid.cbz cache hit: gid=" + mGid + ", path=" + relGidCbz);
                    }
                    // 从缓存的 CBZ 解压
                    final java.io.FileInputStream fis = cachedStream;
                    return new InputStreamPipe() {
                        private java.io.FileInputStream mFis;
                        private ZipInputStream mZis;
                        @Override public void obtain() { /* no-op */ }
                        @Override public void release() { /* no-op */ }
                        @Override public java.io.InputStream open() throws IOException {
                            mFis = fis;
                            mZis = new ZipInputStream(mFis);
                            ZipEntry entry;
                            while ((entry = mZis.getNextEntry()) != null) {
                                if (entry.isDirectory()) continue;
                                String en = entry.getName();
                                if (en == null) continue;
                                for (String ext : exts) {
                                    String expect = generateImageFilename(index, ext);
                                    if (expect.equalsIgnoreCase(en)) {
                                        if (BuildConfig.DEBUG) {
                                            android.util.Log.d("SpiderDen", "SMB gid.cbz cache hit entry: gid=" + mGid + ", index=" + (index+1) + ", name=" + en);
                                        }
                                        return mZis;
                                    }
                                }
                            }
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("SpiderDen", "SMB gid.cbz cache miss entry: gid=" + mGid + ", index=" + (index+1));
                            }
                            close();
                            throw new IOException("Entry not found in cached CBZ for index=" + index);
                        }
                        @Override public void close() {
                            com.hippo.lib.yorozuya.IOUtils.closeQuietly(mZis);
                            com.hippo.lib.yorozuya.IOUtils.closeQuietly(mFis);
                            mZis = null; mFis = null;
                        }
                    };
                }
            } catch (Throwable e) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SpiderDen", "Failed to get cached CBZ, falling back to SMB: " + e);
                }
            }
            
            // 缓存未命中，从 SMB 读取并异步缓存
            try {
                java.io.InputStream probe = Client.INSTANCE.openInputStream(gidCbzTarget);
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SpiderDen", "SMB auto-detect gid.cbz success: gid=" + mGid + ", path=" + relGidCbz);
                }
                // 异步缓存 CBZ
                final String relCbzFinal = relGidCbz;
                final java.io.InputStream probeFinal = probe;
                CbzCacheManager.INSTANCE.cacheCbzAsync(authority, share, relCbzFinal, probeFinal, -1L, null);
                
                // 同步从 SMB 读取并返回
                return new InputStreamPipe() {
                    private java.io.InputStream mBase;
                    private ZipInputStream mZis;
                    @Override public void obtain() { /* no-op */ }
                    @Override public void release() { /* no-op */ }
                    @Override public java.io.InputStream open() throws IOException {
                        mBase = Client.INSTANCE.openInputStream(new Client.Target(authority, share, relCbzFinal));
                        mZis = new ZipInputStream(mBase);
                        ZipEntry entry;
                        while ((entry = mZis.getNextEntry()) != null) {
                            if (entry.isDirectory()) continue;
                            String en = entry.getName();
                            if (en == null) continue;
                            for (String ext : exts) {
                                String expect = generateImageFilename(index, ext);
                                if (expect.equalsIgnoreCase(en)) {
                                    if (BuildConfig.DEBUG) {
                                        android.util.Log.d("SpiderDen", "SMB auto-detect gid.cbz hit entry: gid=" + mGid + ", index=" + (index+1) + ", name=" + en);
                                    }
                                    return mZis;
                                }
                            }
                        }
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("SpiderDen", "SMB auto-detect gid.cbz miss entry: gid=" + mGid + ", index=" + (index+1) + ", path=" + relCbzFinal);
                        }
                        close();
                        throw new IOException("Entry not found in remote gid.cbz for index=" + index);
                    }
                    @Override public void close() {
                        com.hippo.lib.yorozuya.IOUtils.closeQuietly(mZis);
                        com.hippo.lib.yorozuya.IOUtils.closeQuietly(mBase);
                        mZis = null; mBase = null;
                    }
                };
            } catch (Throwable ignore) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SpiderDen", "SMB auto-detect gid.cbz not found: gid=" + mGid + ", path=" + relGidCbz);
                }
            }
        }

        // Step 2: 若 base 直接指向 .cbz 文件
        if (!normBase.isEmpty() && normBase.toLowerCase(java.util.Locale.US).endsWith(".cbz")) {
            final Client.Target cbzTarget = new Client.Target(authority, share, normBase);
            if (BuildConfig.DEBUG) {
                android.util.Log.d("SpiderDen", "SMB auto-detect: direct CBZ mapping detected: gid=" + mGid + ", cbzPath=" + normBase);
            }
            return new InputStreamPipe() {
                private java.io.InputStream mBase;
                private ZipInputStream mZis;
                @Override public void obtain() { /* no-op */ }
                @Override public void release() { /* no-op */ }
                @Override public java.io.InputStream open() throws IOException {
                    mBase = Client.INSTANCE.openInputStream(cbzTarget);
                    mZis = new ZipInputStream(mBase);
                    ZipEntry entry;
                    while ((entry = mZis.getNextEntry()) != null) {
                        if (entry.isDirectory()) continue;
                        String en = entry.getName();
                        if (en == null) continue;
                        for (String ext : exts) {
                            String expect = generateImageFilename(index, ext);
                            if (expect.equalsIgnoreCase(en)) {
                                if (BuildConfig.DEBUG) {
                                    android.util.Log.d("SpiderDen", "SMB auto-detect direct CBZ hit entry: gid=" + mGid + ", index=" + (index+1) + ", name=" + en);
                                }
                                return mZis;
                            }
                        }
                    }
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("SpiderDen", "SMB auto-detect direct CBZ miss entry: gid=" + mGid + ", index=" + (index+1) + ", cbzPath=" + normBase);
                    }
                    close();
                    throw new IOException("Entry not found in remote CBZ (direct) for index=" + index);
                }
                @Override public void close() {
                    com.hippo.lib.yorozuya.IOUtils.closeQuietly(mZis);
                    com.hippo.lib.yorozuya.IOUtils.closeQuietly(mBase);
                    mZis = null; mBase = null;
                }
            };
        }

        // Step 3: 尝试所有支持的扩展名（base 为目录场景）
        for (String ext : exts) {
            String filename = generateImageFilename(index, ext);
            String rel = normBase.isEmpty() ? filename : (normBase + "\\" + filename);
            Client.Target target = new Client.Target(authority, share, rel);
            try {
                java.io.InputStream is = Client.INSTANCE.openInputStream(target);
                return new InputStreamPipe() {
                    private java.io.InputStream mIs;
                    @Override public void obtain() { /* no-op */ }
                    @Override public void release() { /* no-op */ }
                    @Override public java.io.InputStream open() { mIs = is; return mIs; }
                    @Override public void close() { com.hippo.lib.yorozuya.IOUtils.closeQuietly(mIs); mIs = null; }
                };
            } catch (Throwable ignore) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SpiderDen", "SMB auto-detect open failed: share=" + share + ", rel=" + rel + ", err=" + ignore);
                }
            }
        }

        // Step 4: 兜底：列目录查找（处理远端大小写差异或不一致扩展名）
        try {
            Client.Target dirTarget = new Client.Target(authority, share, normBase);
            java.util.List<Client.RemoteDirEntry> entries = Client.INSTANCE.listDirectory(dirTarget);
            String indexPrefix = String.format(java.util.Locale.US, "%08d", index + 1);
            Client.RemoteDirEntry match = null;
            outer: for (Client.RemoteDirEntry e : entries) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name == null) continue;
                if (!name.toLowerCase(java.util.Locale.US).startsWith(indexPrefix)) continue;
                for (String ext : exts) {
                    String expect = (indexPrefix + ext).toLowerCase(java.util.Locale.US);
                    if (name.toLowerCase(java.util.Locale.US).equals(expect)) {
                        match = e;
                        break outer;
                    }
                }
            }
            if (match != null) {
                String matchRel = normBase.isEmpty() ? match.getName() : (normBase + "\\" + match.getName());
                Client.Target matchTarget = new Client.Target(authority, share, matchRel);
                java.io.InputStream is = Client.INSTANCE.openInputStream(matchTarget);
                return new InputStreamPipe() {
                    private java.io.InputStream mIs;
                    @Override public void obtain() { /* no-op */ }
                    @Override public void release() { /* no-op */ }
                    @Override public java.io.InputStream open() { mIs = is; return mIs; }
                    @Override public void close() { com.hippo.lib.yorozuya.IOUtils.closeQuietly(mIs); mIs = null; }
                };
            }
            if (BuildConfig.DEBUG) {
                android.util.Log.d("SpiderDen", "SMB auto-detect directory scan: no match for index=" + (index+1) + ", base=" + normBase);
            }
        } catch (Throwable e) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("SpiderDen", "SMB auto-detect directory scan failed: " + e);
            }
        }
        return null;
    }


    @Nullable
    private InputStreamPipe openSmbInputStreamPipe(int index) {
        // 通过 dirname 直接推导 SMB 路径
        Client.Target resolved = SmbPathResolver.resolve(mGid);
        if (resolved == null) {
            return null;
        }
        String normBase = resolved.getPathInShare().replace('/', '\\').replaceAll("^\\\\+|\\\\+$", "");

        // Step 1: 直接尝试 <gid>.cbz （如果 base 是目录）
        if (!normBase.toLowerCase(java.util.Locale.US).endsWith(".cbz")) {
            String gidCbz = mGid + ".cbz";
            String relGidCbz = normBase.isEmpty() ? gidCbz : (normBase + "\\" + gidCbz);
            Client.Target gidCbzTarget = new Client.Target(resolved.getAuthority(), resolved.getShare(), relGidCbz);
            try {
                java.io.InputStream probe = Client.INSTANCE.openInputStream(gidCbzTarget);
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SpiderDen", "SMB pre-check gid.cbz success: gid=" + mGid + ", path=" + relGidCbz);
                }
                // 包装为 Zip 流读取所需条目
                final String relCbzFinal = relGidCbz;
                final String[] exts = GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS;
                return new InputStreamPipe() {
                    private java.io.InputStream mBase;
                    private ZipInputStream mZis;
                    @Override public void obtain() { /* no-op */ }
                    @Override public void release() { /* no-op */ }
                    @Override public java.io.InputStream open() throws IOException {
                        mBase = Client.INSTANCE.openInputStream(new Client.Target(resolved.getAuthority(), resolved.getShare(), relCbzFinal));
                        mZis = new ZipInputStream(mBase);
                        ZipEntry entry;
                        while ((entry = mZis.getNextEntry()) != null) {
                            if (entry.isDirectory()) continue;
                            String en = entry.getName();
                            if (en == null) continue;
                            for (String ext : exts) {
                                String expect = generateImageFilename(index, ext);
                                if (expect.equalsIgnoreCase(en)) {
                                    if (BuildConfig.DEBUG) {
                                        android.util.Log.d("SpiderDen", "SMB gid.cbz hit entry: gid=" + mGid + ", index=" + (index+1) + ", name=" + en);
                                    }
                                    return mZis;
                                }
                            }
                        }
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("SpiderDen", "SMB gid.cbz miss entry: gid=" + mGid + ", index=" + (index+1) + ", path=" + relCbzFinal);
                        }
                        close();
                        throw new IOException("Entry not found in remote gid.cbz for index=" + index);
                    }
                    @Override public void close() {
                        com.hippo.lib.yorozuya.IOUtils.closeQuietly(mZis);
                        com.hippo.lib.yorozuya.IOUtils.closeQuietly(mBase);
                        mZis = null; mBase = null;
                    }
                };
            } catch (Throwable ignore) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SpiderDen", "SMB pre-check gid.cbz not found: gid=" + mGid + ", path=" + relGidCbz + ", err=" + ignore);
                }
            }
        }

        // 若 base 直接指向 .cbz 文件，则直接从该 CBZ 读取
        if (!normBase.isEmpty() && normBase.toLowerCase(java.util.Locale.US).endsWith(".cbz")) {
            final Client.Target cbzTarget = new Client.Target(resolved.getAuthority(), resolved.getShare(), normBase);
            final String[] extsDirect = GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS;
            if (BuildConfig.DEBUG) {
                android.util.Log.d("SpiderDen", "SMB direct CBZ mapping detected: gid=" + mGid + ", cbzPath=" + normBase);
            }
            return new InputStreamPipe() {
                private java.io.InputStream mBase;
                private ZipInputStream mZis;
                @Override public void obtain() { /* no-op */ }
                @Override public void release() { /* no-op */ }
                @Override public java.io.InputStream open() throws IOException {
                    mBase = Client.INSTANCE.openInputStream(cbzTarget);
                    mZis = new ZipInputStream(mBase);
                    ZipEntry entry;
                    while ((entry = mZis.getNextEntry()) != null) {
                        if (entry.isDirectory()) continue;
                        String en = entry.getName();
                        if (en == null) continue;
                        for (String ext : extsDirect) {
                            String expect = generateImageFilename(index, ext);
                            if (expect.equalsIgnoreCase(en)) {
                                if (BuildConfig.DEBUG) {
                                    android.util.Log.d("SpiderDen", "SMB direct CBZ hit entry: gid=" + mGid + ", index=" + (index+1) + ", name=" + en);
                                }
                                return mZis;
                            }
                        }
                    }
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("SpiderDen", "SMB direct CBZ miss entry: gid=" + mGid + ", index=" + (index+1) + ", cbzPath=" + normBase);
                    }
                    close();
                    throw new IOException("Entry not found in remote CBZ (direct) for index=" + index);
                }
                @Override public void close() {
                    com.hippo.lib.yorozuya.IOUtils.closeQuietly(mZis);
                    com.hippo.lib.yorozuya.IOUtils.closeQuietly(mBase);
                    mZis = null; mBase = null;
                }
            };
        }

        // 尝试所有支持的扩展名（base 为目录场景）
        for (String ext : GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            String filename = generateImageFilename(index, ext);
            String rel = normBase.isEmpty() ? filename : (normBase + "\\" + filename);
            Client.Target target = new Client.Target(resolved.getAuthority(), resolved.getShare(), rel);
            try {
                java.io.InputStream is = Client.INSTANCE.openInputStream(target);
                return new InputStreamPipe() {
                    private java.io.InputStream mIs;
                    @Override public void obtain() { /* no-op */ }
                    @Override public void release() { /* no-op */ }
                    @Override public java.io.InputStream open() { mIs = is; return mIs; }
                    @Override public void close() { com.hippo.lib.yorozuya.IOUtils.closeQuietly(mIs); mIs = null; }
                };
            } catch (Throwable ignore) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("SpiderDen", "SMB open failed: auth=" + resolved.getAuthority() + ", share=" + resolved.getShare() + ", rel=" + rel + ", err=" + ignore);
                }
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
        }
        return null;
    }
}
