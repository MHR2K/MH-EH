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
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
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
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class BackupManager {

    private static final String TAG = "BackupManager";

    // 备份文件中的条目名称
    private static final String ENTRY_EH_DB = "eh.db";
    private static final String ENTRY_SETTINGS = "settings";
    // 注意：SMB 数据通过 shared_prefs 目录备份，包含：
    // - smb_servers.xml (SmbServerStore)
    // - smb_secure_prefs.xml (SmbServerStore 加密数据)
    private static final String ENTRY_COOKIES = "cookies.db";
    private static final String ENTRY_SPIDER_INFO = "spider_info.db";
    private static final String ENTRY_SEARCH_DATABASE = "search_database.db";
    private static final String ENTRY_HOSTS = "hosts.db";
    private static final String ENTRY_STATS = "stats.db";

    // 兼容支持历史单文件 .db 备份中的附加表
    private static final String TABLE_EXTRA_FILES = "_extra_files";
    private static final String EXTRA_PREFIX_SHARED_PREFS = "shared_prefs/";
    private static final String EXTRA_PREFIX_DATABASES = "databases/";

    // 不需要备份的 SharedPreferences 文件（系统或第三方库生成，应用设置除外）
    private static final String[] SKIP_SHARED_PREFS = {
        "AwOriginVisitLoggerPrefs.xml",                  // Chrome WebView 统计
        "com.google.android.gms.measurement.prefs.xml",  // Firebase Analytics
        "WebViewChromiumPrefs.xml",                      // WebView 设置
        "archiver_cache.xml"                             // Archiver 缓存（无需备份）
    };

    /**
     * 创建完整备份文件（标准 Zip 归档容器）
     *
     * 备份内容包括：
     * 1. 主数据库全部数据（通过 {@link EhDB#exportDB} 导出）
     * 2. SharedPreferences 全部配置文件（包含 SMB 服务配置）
     * 3. OkHttp CookieStore 数据库（{@code okhttp3-cookie.db}）
     * 4. SpiderInfo 阅读进度数据库（{@code spider_info.db}）
     * 5. 搜索历史数据库（{@code search_database.db}）
     * 6. 自定义与内置 Hosts 数据库（{@code hosts.db}）
     * 7. 阅读与使用统计数据库（{@code stats.db}）
     *
     * 在复制处于 WAL 模式的附加数据库前，会自动执行 checkpoint 刷盘，保证主文件包含最新数据。
     */
    public static boolean createFullBackup(Context context, File outputFile) {
        Log.i(TAG, "Creating full backup: " + outputFile.getAbsolutePath());

        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        File tmpZipFile = new File(parentDir, outputFile.getName() + ".tmp");
        File tempDir = null;
        ZipOutputStream zos = null;

        try {
            // 清理可能残留的临时文件与旧临时目录
            deleteStaleTempFiles(parentDir);
            deleteQuietly(tmpZipFile);

            tempDir = new File(context.getCacheDir(), "backup_temp");
            if (tempDir.exists()) {
                deleteDirectory(tempDir);
            }
            if (!tempDir.mkdirs()) {
                Log.e(TAG, "Failed to create temp directory");
                return false;
            }

            // 1. 导出主数据库
            File ehDbFile = new File(tempDir, ENTRY_EH_DB);
            if (!EhDB.exportDB(context, ehDbFile)) {
                Log.e(TAG, "Failed to export eh.db");
                return false;
            }

            // 2. 导出 SharedPreferences（包含 SMB 数据）
            File settingsDir = new File(tempDir, ENTRY_SETTINGS);
            exportSharedPreferences(context, settingsDir);

            // 3. 复制附加数据库（先执行 checkpoint 确保 WAL 缓存数据刷回主库）
            File cookieDb = context.getDatabasePath("okhttp3-cookie.db");
            checkpointDatabase(cookieDb);
            copyFile(cookieDb, new File(tempDir, ENTRY_COOKIES));

            File spiderDb = context.getDatabasePath("spider_info.db");
            if (spiderDb.exists()) {
                checkpointDatabase(spiderDb);
                copyFile(spiderDb, new File(tempDir, ENTRY_SPIDER_INFO));
            }

            File searchDb = context.getDatabasePath("search_database.db");
            if (searchDb.exists()) {
                checkpointDatabase(searchDb);
                copyFile(searchDb, new File(tempDir, ENTRY_SEARCH_DATABASE));
            }

            File hostsDb = context.getDatabasePath("hosts.db");
            if (hostsDb.exists()) {
                checkpointDatabase(hostsDb);
                copyFile(hostsDb, new File(tempDir, ENTRY_HOSTS));
            }

            // 统计数据库先等待异步写入完成并执行 checkpoint 刷盘
            com.hippo.ehviewer.stats.StatsManager.getInstance(context).flushSync();
            File statsDb = context.getDatabasePath("stats.db");
            if (statsDb.exists()) {
                checkpointDatabase(statsDb);
                copyFile(statsDb, new File(tempDir, ENTRY_STATS));
            }

            // 4. 打包进 Zip 临时文件
            zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmpZipFile)));
            addFileToZip(zos, ehDbFile, ENTRY_EH_DB);
            addDirectoryToZip(zos, settingsDir, ENTRY_SETTINGS + "/");
            addFileToZip(zos, new File(tempDir, ENTRY_COOKIES), ENTRY_COOKIES);

            File spiderDest = new File(tempDir, ENTRY_SPIDER_INFO);
            if (spiderDest.exists()) {
                addFileToZip(zos, spiderDest, ENTRY_SPIDER_INFO);
            }

            File searchDest = new File(tempDir, ENTRY_SEARCH_DATABASE);
            if (searchDest.exists()) {
                addFileToZip(zos, searchDest, ENTRY_SEARCH_DATABASE);
            }

            File hostsDest = new File(tempDir, ENTRY_HOSTS);
            if (hostsDest.exists()) {
                addFileToZip(zos, hostsDest, ENTRY_HOSTS);
            }

            File statsDest = new File(tempDir, ENTRY_STATS);
            if (statsDest.exists()) {
                addFileToZip(zos, statsDest, ENTRY_STATS);
            }

            zos.close();
            zos = null;

            // 5. 原子替换到目标输出文件
            deleteQuietly(outputFile);
            if (!tmpZipFile.renameTo(outputFile)) {
                copyFileChecked(tmpZipFile, outputFile);
            }

            if (!outputFile.exists() || outputFile.length() == 0) {
                Log.e(TAG, "Backup file not created");
                return false;
            }

            Log.i(TAG, "Full backup created successfully: " + outputFile.getAbsolutePath());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to create full backup", e);
            return false;
        } finally {
            IOUtils.closeQuietly(zos);
            deleteQuietly(tmpZipFile);
            deleteDirectory(tempDir);
        }
    }

    /**
     * 从完整备份文件恢复。
     * 支持标准 zip / ehbak 归档包，并向下兼容识别单文件 .db 备份。
     */
    public static boolean restoreFullBackup(Context context, File backupFile) {
        if (backupFile == null || !backupFile.exists()) {
            Log.e(TAG, "Backup file does not exist");
            return false;
        }
        if (isZip(backupFile)) {
            return restoreFullBackupZip(context, backupFile);
        }
        return restoreFullBackupDb(context, backupFile);
    }

    /**
     * 通过文件头判断是否为 zip 容器（PK 标识）
     */
    private static boolean isZip(File file) {
        try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
            byte[] magic = new byte[4];
            int read = is.read(magic);
            return read == 4 && magic[0] == 'P' && magic[1] == 'K'
                    && magic[2] == 3 && magic[3] == 4;
        } catch (IOException e) {
            Log.e(TAG, "Failed to detect backup format", e);
            return false;
        }
    }

    /**
     * 从标准 Zip / ehbak 完整备份文件恢复
     */
    private static boolean restoreFullBackupZip(Context context, File backupFile) {
        Log.i(TAG, "Restoring full backup (zip): " + backupFile.getAbsolutePath());

        File tempDir = null;
        ZipFile zipFile = null;

        try {
            tempDir = new File(context.getCacheDir(), "restore_temp");
            // 清理残留的旧临时目录
            if (tempDir.exists()) {
                deleteDirectory(tempDir);
            }
            if (!tempDir.mkdirs()) {
                Log.e(TAG, "Failed to create temp directory");
                return false;
            }

            // 解压 zip 文件
            zipFile = new ZipFile(backupFile);
            extractAll(zipFile, tempDir);

            // 1. 恢复主数据库
            File ehDbFile = new File(tempDir, ENTRY_EH_DB);
            if (ehDbFile.exists()) {
                String error = EhDB.importDB(context, ehDbFile, null);
                if (error != null) {
                    Log.e(TAG, "Failed to import eh.db: " + error);
                    return false;
                }
            }

            // 2. 恢复 SharedPreferences（包含 SMB 数据）
            File settingsDir = new File(tempDir, ENTRY_SETTINGS);
            if (settingsDir.exists() && settingsDir.isDirectory()) {
                importSharedPreferences(context, settingsDir);
            }

            // 3. 恢复附加数据库（覆盖前清理目标库的 -wal/-shm/-journal，避免损坏）
            restoreDatabaseFile(tempDir, ENTRY_COOKIES, context.getDatabasePath("okhttp3-cookie.db"));
            restoreDatabaseFile(tempDir, ENTRY_SPIDER_INFO, context.getDatabasePath("spider_info.db"));
            restoreDatabaseFile(tempDir, ENTRY_SEARCH_DATABASE, context.getDatabasePath("search_database.db"));
            restoreDatabaseFile(tempDir, ENTRY_HOSTS, context.getDatabasePath("hosts.db"));
            restoreDatabaseFile(tempDir, ENTRY_STATS, context.getDatabasePath("stats.db"));

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

    /**
     * 恢复单个数据库文件，覆盖前先删除目标位置现存的 SQLite 伴生文件（-journal / -wal / -shm）
     */
    private static void restoreDatabaseFile(File sourceDir, String entryName, File destDb) {
        File source = new File(sourceDir, entryName);
        if (!source.exists()) {
            return;
        }
        File parent = destDb.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        // 关键：删除旧数据库可能存在的 -wal / -shm，防止与新数据库不匹配导致 corrupted
        deleteSqliteSidecarFiles(destDb);
        copyFile(source, destDb);
    }

    /**
     * 从历史单文件数据库备份恢复（向下兼容）
     */
    private static boolean restoreFullBackupDb(Context context, File backupFile) {
        Log.i(TAG, "Restoring full backup (db): " + backupFile.getAbsolutePath());

        File localFile = new File(context.getCacheDir(), "restore_backup.db");
        try {
            if (localFile.exists()) {
                deleteQuietly(localFile);
                deleteSqliteSidecarFiles(localFile);
            }
            copyFileChecked(backupFile, localFile);
            if (!localFile.exists() || localFile.length() == 0) {
                Log.e(TAG, "Failed to copy backup file to local storage");
                return false;
            }

            // 1. 读取可能存在的附加数据
            Map<String, byte[]> extraFiles = readExtraFiles(localFile);

            // 2. 导入主数据库
            String error = EhDB.importDB(context, localFile, null);
            if (error != null) {
                Log.e(TAG, "Failed to import main database: " + error);
                return false;
            }

            // 3. 写回附加数据
            applyExtraFiles(context, extraFiles);

            Log.i(TAG, "Full backup restored successfully");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore full backup", e);
            return false;
        } finally {
            deleteQuietly(localFile);
            deleteSqliteSidecarFiles(localFile);
        }
    }

    /**
     * 读取历史单文件数据库备份中的 _extra_files 表
     */
    private static Map<String, byte[]> readExtraFiles(File dbFile) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            if (!tableExists(db, TABLE_EXTRA_FILES)) {
                return result;
            }
            try (Cursor cursor = db.query(TABLE_EXTRA_FILES, new String[]{"name", "data"},
                    null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(0);
                    byte[] data = cursor.getBlob(1);
                    if (name != null && data != null) {
                        result.put(name, data);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read extra files from backup", e);
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Exception ignored) {
                }
            }
        }
        return result;
    }

    private static boolean tableExists(SQLiteDatabase db, String tableName) {
        try (Cursor cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?", new String[]{tableName})) {
            return cursor.moveToFirst();
        }
    }

    private static void applyExtraFiles(Context context, Map<String, byte[]> extraFiles) {
        if (extraFiles == null || extraFiles.isEmpty()) {
            return;
        }
        File sharedPrefsDir = new File(context.getFilesDir().getParent(), "shared_prefs");
        for (Map.Entry<String, byte[]> entry : extraFiles.entrySet()) {
            String name = entry.getKey();
            byte[] data = entry.getValue();
            File dest;
            if (name.startsWith(EXTRA_PREFIX_SHARED_PREFS)) {
                String relative = name.substring(EXTRA_PREFIX_SHARED_PREFS.length());
                dest = new File(sharedPrefsDir, relative);
            } else if (name.startsWith(EXTRA_PREFIX_DATABASES)) {
                String dbName = name.substring(EXTRA_PREFIX_DATABASES.length());
                dest = context.getDatabasePath(dbName);
                deleteSqliteSidecarFiles(dest);
            } else {
                Log.w(TAG, "Unknown extra backup entry: " + name);
                continue;
            }
            try {
                File parent = dest.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(dest))) {
                    os.write(data);
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to restore file: " + dest.getAbsolutePath(), e);
            }
        }
    }

    /**
     * 将处于 WAL 模式的数据库检查点刷盘，保证主 .db 文件包含最新数据
     */
    private static void checkpointDatabase(File dbFile) {
        if (dbFile == null || !dbFile.exists()) {
            return;
        }
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            try (Cursor c = db.rawQuery("PRAGMA wal_checkpoint(FULL)", null)) {
                if (c != null) {
                    c.moveToFirst();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to checkpoint " + dbFile.getName() + ": " + t.getMessage());
        }
    }

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
            Set<String> excludeSet = new HashSet<>();
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
     * 复制文件（失败时抛出异常）
     */
    private static void copyFileChecked(File source, File dest) throws IOException {
        try (InputStream is = new BufferedInputStream(new FileInputStream(source));
             OutputStream os = new BufferedOutputStream(new FileOutputStream(dest))) {
            IOUtils.copy(is, os);
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
        Enumeration<? extends ZipEntry> entries = zip.entries();
        byte[] buffer = new byte[8192];
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            File file = new File(destDir, entry.getName());
            if (entry.isDirectory()) {
                file.mkdirs();
            } else {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
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
     * 静默删除单个文件
     */
    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Failed to delete file: " + file.getAbsolutePath());
        }
    }

    /**
     * 删除 sqlite 数据库的附属文件（-journal / -wal / -shm）
     */
    private static void deleteSqliteSidecarFiles(File dbFile) {
        if (dbFile == null) {
            return;
        }
        deleteQuietly(new File(dbFile.getPath() + "-journal"));
        deleteQuietly(new File(dbFile.getPath() + "-wal"));
        deleteQuietly(new File(dbFile.getPath() + "-shm"));
    }

    /**
     * 清理目录中残留的临时文件
     */
    private static void deleteStaleTempFiles(File dir) {
        if (dir == null) {
            return;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".tmp")
                || name.endsWith(".tmp-journal")
                || name.endsWith(".tmp-wal")
                || name.endsWith(".tmp-shm"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            Log.i(TAG, "Deleting stale temp file: " + file.getName());
            deleteQuietly(file);
        }
    }

    /**
     * 删除目录及其内容
     */
    static void deleteDirectory(File dir) {
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
