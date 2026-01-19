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

package com.hippo.ehviewer.ui.fragment;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.ui.wifi.WiFiClientActivity;
import com.hippo.ehviewer.ui.wifi.WiFiServerActivity;
import com.hippo.ehviewer.smb.ui.SmbAddServerActivity;
import com.hippo.ehviewer.widget.ProgressHelper;
import com.hippo.util.LogCat;
import com.hippo.util.ReadableTime;

import java.io.File;
import java.util.Arrays;

public class AdvancedFragment extends BasePreferenceFragmentCompat
        implements Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {
    public static final int DB_LOADING = 0;
    public static final int DB_LOAD_FINISH = 1;

    public static final String LOADING_STATUS = "loading_status";
    public static final String LOADING_PROGRESS = "loading_progress";

    private static final String KEY_DUMP_LOGCAT = "dump_logcat";
    private static final String KEY_CLEAR_MEMORY_CACHE = "clear_memory_cache";
    private static final String KEY_APP_LANGUAGE = "app_language";
    private static final String KEY_IMPORT_DATA = "import_data";
    private static final String KEY_WIFI_SERVER = "wifi_server";
    private static final String KEY_WIFI_CLIENT = "wifi_client";
    private static final String KEY_SMB_ADD_SERVER = "smb_add_server";
    private static final String KEY_SMB_SCAN_MAPPING = "smb_scan_mapping";

    private final DbSyncHandle dbSyncHandle = new DbSyncHandle(Looper.getMainLooper());

    private Context context;
    
    // 文件选择结果处理
    private androidx.activity.result.ActivityResultLauncher<String> selectCacheFileLauncher;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        context = getContext();
        addPreferencesFromResource(R.xml.advanced_settings);

        // 初始化文件选择 Launcher
        selectCacheFileLauncher = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    handleCacheFileSelected(uri);
                }
            }
        );

        Preference dumpLogcat = findPreference(KEY_DUMP_LOGCAT);
        Preference clearMemoryCache = findPreference(KEY_CLEAR_MEMORY_CACHE);
        Preference appLanguage = findPreference(KEY_APP_LANGUAGE);
        Preference importData = findPreference(KEY_IMPORT_DATA);
        Preference socketData = findPreference(KEY_WIFI_SERVER);
        Preference clientData = findPreference(KEY_WIFI_CLIENT);
        Preference smbAdd = findPreference(KEY_SMB_ADD_SERVER);
        Preference smbScan = findPreference(KEY_SMB_SCAN_MAPPING);

        dumpLogcat.setOnPreferenceClickListener(this);
        clearMemoryCache.setOnPreferenceClickListener(this);
        importData.setOnPreferenceClickListener(this);
        socketData.setOnPreferenceClickListener(this);
        clientData.setOnPreferenceClickListener(this);
        if (smbAdd != null) smbAdd.setOnPreferenceClickListener(this);
        if (smbScan != null) smbScan.setOnPreferenceClickListener(this);

        appLanguage.setOnPreferenceChangeListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        // 更新“添加 SMB 服务器”项的摘要为已保存服务器的 URL 列表（包含密码）
        Preference smbAdd = findPreference(KEY_SMB_ADD_SERVER);
        if (smbAdd != null) {
            try {
                java.util.List<com.hippo.ehviewer.smb.SmbServer> list = com.hippo.ehviewer.smb.SmbServerStore.INSTANCE.list();
                if (list == null || list.isEmpty()) {
                    smbAdd.setSummary("未保存任何 SMB 服务器");
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (com.hippo.ehviewer.smb.SmbServer s : list) {
                        com.hippo.ehviewer.smb.Authority a = s.getAuthority();
                        String userPart = (a.getDomain() != null && !a.getDomain().isEmpty()) ? (a.getDomain() + "\\\\" + a.getUsername()) : a.getUsername();
                        String portPart = (a.getPort() != com.hippo.ehviewer.smb.Authority.DEFAULT_PORT) ? (":" + a.getPort()) : "";
                        String path = s.getRelativePath();
                            String pathPart = (path == null || path.isEmpty()) ? "" : "/" + path.replace('\\', '/');
                        String url = "smb://" + userPart + ":" + s.getPassword() + "@" + a.getHost() + portPart + pathPart;
                        if (sb.length() > 0) sb.append('\n');
                        if (s.getName() != null) {
                            sb.append(s.getName()).append(": ");
                        }
                        sb.append(url);
                    }
                    smbAdd.setSummary(sb.toString());
                }
            } catch (Throwable t) {
                smbAdd.setSummary("读取保存的 SMB 服务器失败");
            }
        }
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        switch (key) {
            case KEY_DUMP_LOGCAT:
                return dumpLogcat();
            case KEY_CLEAR_MEMORY_CACHE:
                return clearMemoryCache();
            case KEY_IMPORT_DATA:
                importData(getActivity());
                getActivity().setResult(Activity.RESULT_OK);
                return true;
            case KEY_WIFI_SERVER:
                return gotoWiFiServerActivity();
            case KEY_WIFI_CLIENT:
                return gotoWiFiClientActivity();
            case KEY_SMB_ADD_SERVER:
                return gotoSmbAddServerActivity();
            case KEY_SMB_SCAN_MAPPING:
                return scanSmbMappings();
            default:
                return false;
        }
    }

    private boolean gotoSmbAddServerActivity() {
        final Activity activity = getActivity();
        if (activity == null) return false;
        try {
            java.util.List<com.hippo.ehviewer.smb.SmbServer> list = com.hippo.ehviewer.smb.SmbServerStore.INSTANCE.list();
            if (list == null || list.isEmpty()) {
                // 无已保存，直接进入新增
                SmbAddServerActivity.start(activity);
                return true;
            }

            // 构造选择列表：首项为“添加新服务器…”，其余为已保存条目
            CharSequence[] items = new CharSequence[list.size() + 1];
            items[0] = activity.getString(R.string.add) + "…";
            for (int i = 0; i < list.size(); i++) {
                com.hippo.ehviewer.smb.SmbServer s = list.get(i);
                com.hippo.ehviewer.smb.Authority a = s.getAuthority();
                String userPart = (a.getDomain() != null && !a.getDomain().isEmpty()) ? (a.getDomain() + "\\" + a.getUsername()) : a.getUsername();
                String portPart = (a.getPort() != com.hippo.ehviewer.smb.Authority.DEFAULT_PORT) ? (":" + a.getPort()) : "";
                        String path = s.getRelativePath();
                        String pathPart = (path == null || path.isEmpty()) ? "" : "/" + path.replace('\\', '/');
                String label = (s.getName() != null ? (s.getName() + ": ") : "") +
                        "smb://" + userPart + ":" + s.getPassword() + "@" + a.getHost() + portPart + pathPart;
                items[i + 1] = label;
            }

            new AlertDialog.Builder(activity)
                    .setTitle("选择 SMB 服务器")
                    .setItems(items, (dialog, which) -> {
                        dialog.dismiss();
                        if (which == 0) {
                            SmbAddServerActivity.start(activity);
                        } else {
                            long id = list.get(which - 1).getId();
                            SmbAddServerActivity.start(activity, id);
                        }
                    })
                    .show();
            return true;
        } catch (Throwable t) {
            // 出错时也允许进入新增
            SmbAddServerActivity.start(activity);
            return true;
        }
    }

    private boolean gotoWiFiClientActivity() {
        Activity activity = getActivity();
        Intent intent = new Intent(activity, WiFiClientActivity.class);
        activity.startActivity(intent);
        return false;
    }

    /**
     * 扫描 SMB 映射：弹出选择对话框，让用户选择扫描模式
     */
    private boolean scanSmbMappings() {
        final Activity activity = getActivity();
        if (activity == null) return false;

        // 改进的扫描模式选择对话框（添加第三个选项：导入目录缓存）
        CharSequence[] items = new CharSequence[]{
            "\n优先扫描SMB服务器\n        匹配方式：遍历SMB，在本地查找\n        适用于：SMB目录较少的场景",
            "\n优先扫描本地下载\n        匹配方式：遍历本地，在SMB查找\n        适用于：本地下载较少的场景",
            "\n导入SMB目录缓存文件\n        文件位置：SMB/本地 都可以\n        CMD命令：dir /b /ad > smb.txt"
        };
        
        new AlertDialog.Builder(activity)
            .setTitle("📁 选择 SMB 映射扫描模式")
            .setItems(items, (dialog, which) -> {
                dialog.dismiss();
                if (which == 2) {
                    // 导入目录缓存文件
                    importDirectoryCacheFile(activity);
                } else {
                    performScan(activity, which == 0);  // 0 = SMB, 1 = Local
                }
            })
            .setNegativeButton("取消", null)
            .show();

        return true;
    }

    /**
     * 导入目录缓存文件：先自动从 SMB 读取 smb.txt，失败则弹文件选择器
     */
    private void importDirectoryCacheFile(final Activity activity) {
        ProgressHelper.showDialog(activity, "尝试从 SMB 读取 smb.txt...");
        
        new Thread(() -> {
            try {
                // 1. 获取 SMB 服务器
                java.util.List<com.hippo.ehviewer.smb.SmbServer> smbServers = 
                    com.hippo.ehviewer.smb.SmbServerStore.INSTANCE.list();
                
                if (smbServers == null || smbServers.isEmpty()) {
                    activity.runOnUiThread(() -> {
                        ProgressHelper.dismissDialog();
                        selectCacheFileManually(activity);
                    });
                    return;
                }

                com.hippo.ehviewer.smb.SmbServer primaryServer = smbServers.get(0);
                com.hippo.ehviewer.smb.Authority authority = primaryServer.getAuthority();
                String relativePath = primaryServer.getRelativePath();
                if (relativePath == null) relativePath = "";

                // 解析 share 和 basePathInShare
                String share;
                String basePathInShare;
                String normalized = relativePath.trim()
                    .replace('/', '\\')
                    .replaceAll("^\\\\+|\\\\+$", "");
                int firstSep = normalized.indexOf('\\');
                if (firstSep == -1) {
                    share = normalized.isEmpty() ? "" : normalized;
                    basePathInShare = "";
                } else {
                    share = normalized.substring(0, firstSep);
                    basePathInShare = normalized.substring(firstSep + 1);
                }

                // 2. 尝试从 SMB 读取 smb.txt
                java.util.concurrent.atomic.AtomicReference<java.io.InputStream> smbStream = 
                    new java.util.concurrent.atomic.AtomicReference<>(null);
                
                com.hippo.ehviewer.smb.Client.INSTANCE.withTempPassword(
                    authority,
                    primaryServer.getPassword(),
                    () -> {
                        try {
                            com.hippo.ehviewer.smb.Client.Target baseTarget = 
                                new com.hippo.ehviewer.smb.Client.Target(authority, share, basePathInShare);
                            java.io.InputStream stream = com.hippo.ehviewer.smb.Client.INSTANCE.openInputStream(
                                new com.hippo.ehviewer.smb.Client.Target(authority, share, 
                                    basePathInShare.isEmpty() ? "smb.txt" : (basePathInShare + "\\smb.txt"))
                            );
                            smbStream.set(stream);
                        } catch (Exception e) {
                            android.util.Log.d("SmbCacheImport", "SMB 上未找到 smb.txt: " + e.getMessage());
                        }
                        return true;
                    }
                );

                if (smbStream.get() != null) {
                    // 成功读取，处理流
                    activity.runOnUiThread(() -> ProgressHelper.dismissDialog());
                    handleCacheFileStream(activity, smbStream.get());
                    return;
                }

                // 3. 未找到 smb.txt，弹文件选择器
                activity.runOnUiThread(() -> {
                    ProgressHelper.dismissDialog();
                    selectCacheFileManually(activity);
                });

            } catch (Exception e) {
                android.util.Log.e("SmbCacheImport", "读取 SMB 异常", e);
                activity.runOnUiThread(() -> {
                    ProgressHelper.dismissDialog();
                    selectCacheFileManually(activity);
                });
            }
        }).start();
    }

    /**
     * 手动选择缓存文件（弹文件选择器）
     */
    private void selectCacheFileManually(Activity activity) {
        selectCacheFileLauncher.launch("text/plain");
    }

    /**
     * 处理缓存文件流：从 InputStream 读取并解析 GID
     */
    public void handleCacheFileStream(Activity activity, java.io.InputStream inputStream) {
        ProgressHelper.showDialog(activity, "读取缓存文件...");

        new Thread(() -> {
            try {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(inputStream)
                );
                
                java.util.Set<String> cachedGids = new java.util.HashSet<>();
                int totalCachedDirs = 0;
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        // 解析格式：GID-Title 或纯 GID
                        int dashIndex = line.indexOf('-');
                        String gidPart;
                        if (dashIndex > 0) {
                            gidPart = line.substring(0, dashIndex).trim();
                        } else {
                            gidPart = line;
                        }
                        
                        try {
                            Long.parseLong(gidPart); // 验证是有效的数字 GID
                            cachedGids.add(gidPart);
                            totalCachedDirs++;
                        } catch (NumberFormatException e) {
                            // 跳过不符合格式的行
                        }
                    }
                }
                reader.close();
                inputStream.close();

                if (cachedGids.isEmpty()) {
                    activity.runOnUiThread(() -> {
                        ProgressHelper.dismissDialog();
                        Toast.makeText(activity, "缓存文件中没有有效的 GID 条目", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 继续执行匹配逻辑
                performImportWithCachedDirs(activity, cachedGids, totalCachedDirs);
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    ProgressHelper.dismissDialog();
                    Toast.makeText(activity, "读取文件失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
                android.util.Log.e("SmbCacheImport", "读取文件异常", e);
            }
        }).start();
    }

    /**
     * 处理用户选择的缓存文件
     */
    private void handleCacheFileSelected(android.net.Uri uri) {
        final Activity activity = getActivity();
        if (activity == null) return;

        new Thread(() -> {
            try {
                // 从 URI 读取文件内容
                java.io.InputStream inputStream = activity.getContentResolver().openInputStream(uri);
                if (inputStream == null) {
                    activity.runOnUiThread(() -> {
                        Toast.makeText(activity, "无法打开文件", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                activity.runOnUiThread(() -> handleCacheFileStream(activity, inputStream));

            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity, "打开文件失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
                android.util.Log.e("SmbCacheImport", "打开文件异常", e);
            }
        }).start();
    }

    /**
     * 执行导入和匹配：使用 GID 列表与本地下载匹配
     * 优化：直接用 GID 集合 O(1) 查询，无需远程验证（txt 来源于 SMB）
     */
    private void performImportWithCachedDirs(
            final Activity activity,
            java.util.Set<String> cachedGids,
            int totalCachedDirs) {
        
        activity.runOnUiThread(() -> ProgressHelper.showDialog(activity, "匹配并导入中..."));
        
        new Thread(() -> {
            try {
                // 获取所有本地下载信息
                java.util.List<com.hippo.ehviewer.dao.DownloadInfo> allDownloads = EhDB.getAllDownloadInfo();
                if (allDownloads == null) allDownloads = new java.util.ArrayList<>();

                // 获取已保存的 SMB 服务器
                java.util.List<com.hippo.ehviewer.smb.SmbServer> smbServers = 
                    com.hippo.ehviewer.smb.SmbServerStore.INSTANCE.list();
                if (smbServers == null || smbServers.isEmpty()) {
                    activity.runOnUiThread(() -> {
                        ProgressHelper.dismissDialog();
                        Toast.makeText(activity, "未配置 SMB 服务器，无法匹配", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                com.hippo.ehviewer.smb.SmbServer primaryServer = smbServers.get(0);
                com.hippo.ehviewer.smb.Authority authority = primaryServer.getAuthority();
                String relativePath = primaryServer.getRelativePath();
                if (relativePath == null) relativePath = "";

                // 解析 share 和 basePathInShare
                String share;
                String basePathInShare;
                String normalized = relativePath.trim()
                    .replace('/', '\\')
                    .replaceAll("^\\\\+|\\\\+$", "");
                int firstSep = normalized.indexOf('\\');
                if (firstSep == -1) {
                    share = normalized.isEmpty() ? "" : normalized;
                    basePathInShare = "";
                } else {
                    share = normalized.substring(0, firstSep);
                    basePathInShare = normalized.substring(firstSep + 1);
                }

                // 遍历本地下载，使用 GID 集合快速匹配
                int successCount = 0;
                int skippedCount = 0;

                for (com.hippo.ehviewer.dao.DownloadInfo info : allDownloads) {
                    // 检查本地下载的 GID 是否在缓存 GID 集合中
                    String gidStr = String.valueOf(info.gid);
                    if (!cachedGids.contains(gidStr)) {
                        skippedCount++;
                        continue;
                    }

                    // 检查是否已有 SMB 映射
                    if (com.hippo.ehviewer.smb.SmbMappingStore.INSTANCE.get(info.gid) != null) {
                        skippedCount++;
                        continue;
                    }

                    // 获取本地下载目录名（作为 SMB 上的目录名使用）
                    com.hippo.unifile.UniFile downloadDir = com.hippo.ehviewer.spider.SpiderDen.getGalleryDownloadDir(info);
                    if (downloadDir == null) {
                        skippedCount++;
                        continue;
                    }

                    String localDirName = downloadDir.getName();

                    // GID 匹配成功，直接保存映射（txt 文件来源于 SMB 服务器，无需验证）
                    try {
                        String smbPath = basePathInShare.isEmpty() 
                            ? localDirName 
                            : (basePathInShare + "\\" + localDirName);

                        com.hippo.ehviewer.smb.SmbMappingStore.Mapping mapping = 
                            new com.hippo.ehviewer.smb.SmbMappingStore.Mapping(
                                info.gid, authority, share, smbPath
                            );
                        com.hippo.ehviewer.smb.SmbMappingStore.INSTANCE.put(mapping);

                        successCount++;

                        if (com.hippo.ehviewer.BuildConfig.DEBUG) {
                            android.util.Log.d("SmbCacheImport", 
                                "导入映射成功: gid=" + info.gid + ", dir=" + localDirName);
                        }
                    } catch (Exception e) {
                        android.util.Log.e("SmbCacheImport", "创建映射失败: gid=" + info.gid, e);
                        skippedCount++;
                    }
                }

                final int finalSuccessCount = successCount;
                final int finalSkippedCount = skippedCount;
                final int finalTotalCached = totalCachedDirs;
                final int finalAllDownloadsSize = allDownloads.size();

                // 7. 显示结果
                activity.runOnUiThread(() -> {
                    ProgressHelper.dismissDialog();
                    String resultMessage = String.format(
                        "✅ 成功导入映射：%d\n" +
                        "⏭️ 跳过（无匹配/已有映射）：%d\n\n" +
                        "缓存文件中的 GID 数：%d\n" +
                        "本地下载总数：%d",
                        finalSuccessCount,
                        finalSkippedCount,
                        finalTotalCached,
                        finalAllDownloadsSize
                    );
                    new AlertDialog.Builder(activity)
                        .setTitle("📊 导入结果")
                        .setMessage(resultMessage)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    ProgressHelper.dismissDialog();
                    Toast.makeText(activity, "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
                android.util.Log.e("SmbCacheImport", "导入异常", e);
            }
        }).start();
    }

    /**
     * 执行扫描：根据模式选择不同的扫描方法
     */
    @SuppressWarnings("unchecked")
    private void performScan(final Activity activity, final boolean scanBySmbServer) {
        // 显示进度对话框
        ProgressHelper.showDialog(activity, "扫描中...");

        // 后台执行扫描
        new Thread(() -> {
            try {
                // 根据模式选择扫描方法
                com.hippo.ehviewer.smb.SmbMappingScanner.ScanResult result;
                if (scanBySmbServer) {
                    // 遍历 SMB 服务器（反向扫描）
                    result = com.hippo.ehviewer.smb.SmbMappingScanner.INSTANCE.scanAndCreateMappings(
                        activity,
                        (kotlin.jvm.functions.Function3<Integer, Integer, String, kotlin.Unit>) (current, total, message) -> {
                            int pct = 0;
                            // 当 total > 0 时计算百分比，否则使用 0（将显示"已扫描 N 个目录"）
                            if (total != null && total > 0 && current != null) {
                                pct = Math.max(0, Math.min(100, (int) ((current + 1) * 100.0 / total)));
                            }
                            final int progressValue = pct;
                            final int cur = (current != null) ? current : 0;
                            final int tot = (total != null) ? total : 0;
                            final String msg = message;
                            activity.runOnUiThread(() -> {
                                if (msg != null) ProgressHelper.setMessage(msg);
                                ProgressHelper.setProgress(progressValue, cur, tot);
                            });
                            return kotlin.Unit.INSTANCE;
                        }
                    );
                } else {
                    // 遍历本地下载目录
                    result = com.hippo.ehviewer.smb.SmbMappingScanner.INSTANCE.scanByLocalDownloads(
                        activity,
                        (kotlin.jvm.functions.Function3<Integer, Integer, String, kotlin.Unit>) (current, total, message) -> {
                            int pct = 0;
                            // 当 total > 0 时计算百分比，否则使用 0（将显示"已扫描 N 个目录"）
                            if (total != null && total > 0 && current != null) {
                                pct = Math.max(0, Math.min(100, (int) ((current + 1) * 100.0 / total)));
                            }
                            final int progressValue = pct;
                            final int cur = (current != null) ? current : 0;
                            final int tot = (total != null) ? total : 0;
                            final String msg = message;
                            activity.runOnUiThread(() -> {
                                if (msg != null) ProgressHelper.setMessage(msg);
                                ProgressHelper.setProgress(progressValue, cur, tot);
                            });
                            return kotlin.Unit.INSTANCE;
                        }
                    );
                }
                
                // 主线程显示结果
                activity.runOnUiThread(() -> {
                    ProgressHelper.dismissDialog();
                    
                    // 1. 保存详细日志到文件
                    saveDetailedLogToFile(result);
                    
                    // 2. 显示简化的结果对话框
                    String modeText = scanBySmbServer ? "SMB 服务器" : "本地下载";
                    String resultMessage = String.format(
                        "扫描模式：%s\n\n" +
                        "✅ 成功创建映射：%d\n" +
                        "⏭️ 跳过（已有或无匹配）：%d\n" +
                        "❌ 失败：%d\n\n" +
                        "💾 详细日志已保存到文件系统",
                        modeText,
                        result.getSuccessCount(),
                        result.getSkippedCount(),
                        result.getFailedCount()
                    );
                    
                    new AlertDialog.Builder(activity)
                        .setTitle("📊 SMB 映射扫描结果")
                        .setMessage(resultMessage)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    ProgressHelper.dismissDialog();
                    Toast.makeText(activity, "扫描出错: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
    
    /**
     * 将详细扫描日志保存到文件
     */
    private void saveDetailedLogToFile(com.hippo.ehviewer.smb.SmbMappingScanner.ScanResult result) {
        try {
            File logDir = AppConfig.getExternalLogcatDir();
            if (logDir == null) return;
            
            String timestamp = com.hippo.util.ReadableTime.getFilenamableTime(System.currentTimeMillis());
            File logFile = new File(logDir, "smb-scan-" + timestamp + ".txt");
            
            StringBuilder content = new StringBuilder();
            content.append("=== SMB 映射扫描详细日志 ===").append("\n\n");
            content.append("时间：").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())).append("\n\n");
            content.append("统计结果：\n");
            content.append("- 成功创建映射：").append(result.getSuccessCount()).append("\n");
            content.append("- 跳过：").append(result.getSkippedCount()).append("\n");
            content.append("- 失败：").append(result.getFailedCount()).append("\n");
            content.append("- 总扫描数：").append(result.getTotalSmbDirs()).append("\n\n");
            content.append("详细日志：\n");
            content.append("-".repeat(50)).append("\n");
            
            for (String detail : result.getDetails()) {
                content.append(detail).append("\n");
            }
            
            // 写入文件
            java.io.FileWriter writer = new java.io.FileWriter(logFile);
            writer.write(content.toString());
            writer.close();
            
            android.util.Log.d("SmbMappingScanner", "详细日志已保存到: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            android.util.Log.e("SmbMappingScanner", "保存日志文件失败: " + e.getMessage());
        }
    }

    private boolean gotoWiFiServerActivity() {
        Activity activity = getActivity();
        Intent intent = new Intent(activity, WiFiServerActivity.class);
        activity.startActivity(intent);
        return false;
    }

    private boolean clearMemoryCache() {
        ((EhApplication) getActivity().getApplication()).clearMemoryCache();
        Runtime.getRuntime().gc();
        return false;
    }

    private boolean dumpLogcat() {
        boolean ok;
        File file = null;
        File dir = AppConfig.getExternalLogcatDir();
        if (dir != null) {
            file = new File(dir, "logcat-" + ReadableTime.getFilenamableTime(System.currentTimeMillis()) + ".txt");
            ok = LogCat.save(file);
        } else {
            ok = false;
        }
        Resources resources = getResources();
        Toast.makeText(getActivity(),
                ok ? resources.getString(R.string.settings_advanced_dump_logcat_to, file.getPath()) :
                        resources.getString(R.string.settings_advanced_dump_logcat_failed), Toast.LENGTH_SHORT).show();
        return true;
    }

    private boolean importData(final Context context) {
        final File dir = AppConfig.getExternalDataDir();
        if (null == dir) {
            Toast.makeText(context, R.string.cant_get_data_dir, Toast.LENGTH_SHORT).show();
            return false;
        }
        final String[] files = dir.list();
        if (null == files || files.length <= 0) {
            Toast.makeText(context, R.string.cant_find_any_data, Toast.LENGTH_SHORT).show();
            return false;
        }
        Arrays.sort(files);
        new AlertDialog.Builder(context).setItems(files, (dialog, which) -> {
            dialog.dismiss();
            showProgress(context, dir, files, which);
        }).show();
        return false;
    }

    private void showProgress(final Context context, File dir, String[] files, int which) {

        File file = new File(dir, files[which]);
        ProgressHelper.showDialog(context, context.getString(R.string.loading_db_file));
        new Thread(
                () -> {
                    String error = EhDB.importDB(context, file, dbSyncHandle);
                    Message message = new Message();
                    Bundle bundle = new Bundle();
                    bundle.putString("error", error);
                    bundle.putInt(LOADING_STATUS, DB_LOAD_FINISH);
                    message.setData(bundle);
                    dbSyncHandle.sendMessage(message);
                }
        ).start();


    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        if (KEY_APP_LANGUAGE.equals(key)) {
            ((EhApplication) getActivity().getApplication()).recreate();
            return true;
        }
        return false;
    }

    private class DbSyncHandle extends Handler {
        public DbSyncHandle(Looper mainLooper) {
            super(mainLooper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            Bundle data = msg.getData();
            int state = data.getInt(LOADING_STATUS);
            if (state == DB_LOAD_FINISH){
                ProgressHelper.dismissDialog();
                String error = data.getString("error");
                if (context == null) {
                    return;
                }
                if (null == error) {
                    error = context.getString(R.string.settings_advanced_import_data_successfully);
                }
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show();
            } else if (state == DB_LOADING) {
                ProgressHelper.setProgress(data.getInt(LOADING_PROGRESS, 0));
            }

        }
    }
}
