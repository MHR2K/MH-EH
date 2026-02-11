/*
 * Copyright 2024 Hippo Seven
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

package com.hippo.ehviewer.backup;

import android.content.Context;
import android.util.Log;
import com.hippo.ehviewer.EhDB;
import com.hippo.lib.yorozuya.IOUtils;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class BackupManager {

    private static final String TAG = "BackupManager";

    // 备份文件中的条目名称
    private static final String ENTRY_EH_DB = "eh.db";
    private static final String ENTRY_SETTINGS = "settings";
    // 注意：SMB 数据通过 shared_prefs 目录备份，包含：
    // - smb_gallery_mappings.xml (SmbMappingStore)
    // - smb_servers.xml (SmbServerStore)
    // - smb_secure_prefs.xml (SmbServerStore 加密数据)
    private static final String ENTRY_COOKIES = "cookies.db";
    private static final String ENTRY_SPIDER_INFO = "spider_info.db";

    /**
     * 创建完整备份 zip 文件
     */
    public static boolean createFullBackup(Context context, File outputFile) {
        Log.i(TAG, "Creating full backup: " + outputFile.getAbsolutePath());

        File tempDir = null;
        ZipOutputStream zos = null;

        try {
            tempDir = new File(context.getCacheDir(), "backup_temp");
            if (!tempDir.mkdirs()) {
                Log.e(TAG, "Failed to create temp directory");
                return false;
            }

            // 导出数据库
            File ehDbFile = new File(tempDir, ENTRY_EH_DB);
            if (!EhDB.exportDB(context, ehDbFile)) {
                Log.e(TAG, "Failed to export eh.db");
                return false;
            }

            // 导出 SharedPreferences（包含 SMB 数据）
            File settingsDir = new File(tempDir, ENTRY_SETTINGS);
            exportSharedPreferences(context, settingsDir);

            // 复制 Cookie 数据库（OkHttp CookieStore 数据库）
            File cookieDbFile = context.getDatabasePath("okhttp3-cookie.db");
            File cookieDbDest = new File(tempDir, ENTRY_COOKIES);
            copyFile(cookieDbFile, cookieDbDest);

            // 复制 SpiderInfo 数据库（GreenDAO 数据库）
            File spiderDbFile = context.getDatabasePath("spider_info.db");
            if (spiderDbFile.exists()) {
                File spiderDbDest = new File(tempDir, ENTRY_SPIDER_INFO);
                copyFile(spiderDbFile, spiderDbDest);
            }

            // 创建 zip 文件
            zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outputFile)));

            // 添加所有文件到 zip
            addFileToZip(zos, ehDbFile, ENTRY_EH_DB);
            addDirectoryToZip(zos, settingsDir, ENTRY_SETTINGS + "/");
            addFileToZip(zos, cookieDbDest, ENTRY_COOKIES);

            File spiderDest = new File(tempDir, ENTRY_SPIDER_INFO);
            if (spiderDest.exists()) {
                addFileToZip(zos, spiderDest, ENTRY_SPIDER_INFO);
            }

            Log.i(TAG, "Full backup created successfully: " + outputFile.getAbsolutePath());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to create full backup", e);
            return false;
        } finally {
            IOUtils.closeQuietly(zos);
            deleteDirectory(tempDir);
        }
    }

    /**
     * 从完整备份 zip 文件恢复
     */
    public static boolean restoreFullBackup(Context context, File backupFile) {
        Log.i(TAG, "Restoring full backup: " + backupFile.getAbsolutePath());

        File tempDir = null;
        ZipFile zipFile = null;

        try {
            tempDir = new File(context.getCacheDir(), "restore_temp");
            if (!tempDir.mkdirs()) {
                Log.e(TAG, "Failed to create temp directory");
                return false;
            }

            // 解压 zip 文件
            zipFile = new ZipFile(backupFile);
            extractAll(zipFile, tempDir);

            // 恢复数据库
            File ehDbFile = new File(tempDir, ENTRY_EH_DB);
            if (ehDbFile.exists()) {
                String error = EhDB.importDB(context, ehDbFile, null);
                if (error != null) {
                    Log.e(TAG, "Failed to import eh.db: " + error);
                }
            }

            // 恢复 SharedPreferences（包含 SMB 数据）
            File settingsDir = new File(tempDir, ENTRY_SETTINGS);
            if (settingsDir.exists() && settingsDir.isDirectory()) {
                importSharedPreferences(context, settingsDir);
            }

            // 恢复 Cookie 数据库（OkHttp CookieStore 数据库）
            File cookieDbSource = new File(tempDir, ENTRY_COOKIES);
            if (cookieDbSource.exists()) {
                File cookieDbDest = context.getDatabasePath("okhttp3-cookie.db");
                copyFile(cookieDbSource, cookieDbDest);
            }

            // 恢复 SpiderInfo 数据库（GreenDAO 数据库）
            File spiderDbSource = new File(tempDir, ENTRY_SPIDER_INFO);
            if (spiderDbSource.exists()) {
                File spiderDbDest = context.getDatabasePath("spider_info.db");
                copyFile(spiderDbSource, spiderDbDest);
            }

            Log.i(TAG, "Full backup restored successfully");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to restore full backup", e);
            return false;
        } finally {
            IOUtils.closeQuietly(zipFile);
            deleteDirectory(tempDir);
        }
    }

    // 不需要备份的 SharedPreferences 文件（系统或第三方库生成，应用设置除外）
    private static final String[] SKIP_SHARED_PREFS = {
        "AwOriginVisitLoggerPrefs.xml",           // Chrome WebView 统计
        "com.google.android.gms.measurement.prefs.xml",  // Firebase Analytics
        "WebViewChromiumPrefs.xml",              // WebView 设置
        "archiver_cache.xml"                     // Archiver 缓存（无需备份）
    };

    /**
     * 导出 SharedPreferences 到目录
     */
    private static void exportSharedPreferences(Context context, File outputDir) {
        try {
            File sharedPrefsDir = new File(context.getFilesDir().getParent(), "shared_prefs");
            if (sharedPrefsDir.exists() && sharedPrefsDir.isDirectory()) {
                if (!outputDir.mkdirs()) {
                    Log.e(TAG, "Failed to create settings directory");
                    return;
                }
                copyDirectoryExcluding(sharedPrefsDir, outputDir, SKIP_SHARED_PREFS);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to export settings", e);
        }
    }

    /**
     * 复制目录（排除指定文件）
     */
    private static void copyDirectoryExcluding(File sourceDir, File destDir, String[] excludeFiles) {
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            return;
        }
        if (!destDir.exists()) {
            destDir.mkdirs();
        }
        File[] files = sourceDir.listFiles();
        if (files != null) {
            java.util.Set<String> excludeSet = new java.util.HashSet<>();
            for (String name : excludeFiles) {
                excludeSet.add(name);
            }
            for (File file : files) {
                if (excludeSet.contains(file.getName())) {
                    Log.d(TAG, "Skipping shared pref: " + file.getName());
                    continue;
                }
                File destFile = new File(destDir, file.getName());
                if (file.isDirectory()) {
                    copyDirectoryExcluding(file, destFile, excludeFiles);
                } else {
                    copyFile(file, destFile);
                }
            }
        }
    }

    /**
     * 恢复 SharedPreferences
     */
    private static void importSharedPreferences(Context context, File settingsDir) {
        try {
            File sharedPrefsDir = new File(context.getFilesDir().getParent(), "shared_prefs");
            if (settingsDir.exists() && settingsDir.isDirectory()) {
                copyDirectory(settingsDir, sharedPrefsDir);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to import settings", e);
        }
    }

    /**
     * 复制文件
     */
    private static void copyFile(File source, File dest) {
        if (!source.exists()) {
            return;
        }
        try (InputStream is = new BufferedInputStream(new FileInputStream(source));
             OutputStream os = new BufferedOutputStream(new FileOutputStream(dest))) {
            IOUtils.copy(is, os);
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy file: " + source.getAbsolutePath(), e);
        }
    }

    /**
     * 复制目录
     */
    private static void copyDirectory(File sourceDir, File destDir) {
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            return;
        }
        if (!destDir.exists()) {
            destDir.mkdirs();
        }
        File[] files = sourceDir.listFiles();
        if (files != null) {
            for (File file : files) {
                File destFile = new File(destDir, file.getName());
                if (file.isDirectory()) {
                    copyDirectory(file, destFile);
                } else {
                    copyFile(file, destFile);
                }
            }
        }
    }

    /**
     * 添加文件到 zip
     */
    private static void addFileToZip(ZipOutputStream zos, File file, String entryName) throws IOException {
        if (!file.exists()) {
            return;
        }
        byte[] buffer = new byte[8192];
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            ZipEntry entry = new ZipEntry(entryName);
            zos.putNextEntry(entry);
            int len;
            while ((len = bis.read(buffer)) > 0) {
                zos.write(buffer, 0, len);
            }
            zos.closeEntry();
        }
    }

    /**
     * 添加目录到 zip（递归）
     */
    private static void addDirectoryToZip(ZipOutputStream zos, File dir, String baseEntryName) throws IOException {
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                String entryName = baseEntryName + file.getName();
                if (file.isDirectory()) {
                    addDirectoryToZip(zos, file, entryName + "/");
                } else {
                    addFileToZip(zos, file, entryName);
                }
            }
        }
    }

    /**
     * 解压所有文件
     */
    private static void extractAll(ZipFile zip, File destDir) throws IOException {
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        byte[] buffer = new byte[8192];
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            File file = new File(destDir, entry.getName());
            if (entry.isDirectory()) {
                file.mkdirs();
            } else {
                file.getParentFile().mkdirs();
                try (InputStream is = zip.getInputStream(entry);
                     BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file))) {
                    int len;
                    while ((len = is.read(buffer)) > 0) {
                        bos.write(buffer, 0, len);
                    }
                }
            }
        }
    }

    /**
     * 删除目录及其内容
     */
    private static void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }
}
