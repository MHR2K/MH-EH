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

package com.hippo.ehviewer.preference;

import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.util.AttributeSet;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.backup.BackupManager;
import com.hippo.unifile.UniFile;
import java.io.File;

public class FullRestorePreference extends Preference {

    private AsyncTask<Void, Object, Object> mTask;

    public FullRestorePreference(Context context) {
        super(context);
    }

    public FullRestorePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FullRestorePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onClick() {
        super.onClick();
        showWarningDialog();
    }

    private void showWarningDialog() {
        new AlertDialog.Builder(getContext())
            .setTitle(R.string.settings_advanced_full_backup_restore)
            .setMessage(R.string.settings_advanced_full_backup_restore_warning)
            .setPositiveButton(R.string.settings_advanced_full_backup_restore_confirm, (dialog, which) -> {
                showFilePicker();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showFilePicker() {
        Context context = getContext();
        
        // 获取外部数据目录
        File dir = AppConfig.getExternalDataDir();
        if (dir == null) {
            Toast.makeText(context, R.string.cant_get_data_dir, Toast.LENGTH_SHORT).show();
            return;
        }

        // 转换为 UniFile
        UniFile uniDir = UniFile.fromFile(dir);
        if (uniDir == null) {
            Toast.makeText(context, R.string.cant_get_data_dir, Toast.LENGTH_SHORT).show();
            return;
        }

        // 显示文件列表
        UniFile[] files = uniDir.listFiles();
        if (files == null || files.length == 0) {
            Toast.makeText(context, R.string.cant_find_any_data, Toast.LENGTH_SHORT).show();
            return;
        }

        // 筛选 zip 文件
        java.util.List<UniFile> zipFiles = new java.util.ArrayList<>();
        for (UniFile f : files) {
            if (f != null && f.getName() != null && f.getName().endsWith(".zip")) {
                zipFiles.add(f);
            }
        }

        if (zipFiles.isEmpty()) {
            Toast.makeText(context, R.string.cant_find_any_data, Toast.LENGTH_SHORT).show();
            return;
        }

        String[] items = new String[zipFiles.size()];
        for (int i = 0; i < zipFiles.size(); i++) {
            items[i] = zipFiles.get(i).getName();
        }

        new AlertDialog.Builder(context)
            .setTitle(R.string.settings_advanced_full_backup_restore)
            .setItems(items, (dialog, which) -> {
                UniFile selectedFile = zipFiles.get(which);
                // 获取真实文件路径
                performRestore(getFileFromUniFile(selectedFile));
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private File getFileFromUniFile(UniFile uniFile) {
        if (uniFile == null) {
            return null;
        }
        // UniFile 可能包装的是 Uri，需要获取真实路径
        // 这里我们使用一个简单的方法：查找对应名称的 File
        File dir = AppConfig.getExternalDataDir();
        if (dir != null) {
            File target = new File(dir, uniFile.getName());
            if (target.exists()) {
                return target;
            }
        }
        // 如果找不到，返回一个新文件（restoreFullBackup 会处理）
        return new File(AppConfig.getExternalDataDir(), uniFile.getName());
    }

    private void performRestore(File backupFile) {
        if (mTask != null || backupFile == null) {
            return;
        }

        final Context context = getContext();
        final ProgressDialog progressDialog = new ProgressDialog(context);
        progressDialog.setTitle(R.string.settings_advanced_full_backup_restore);
        progressDialog.setMessage(context.getString(R.string.please_wait));
        progressDialog.setCancelable(false);
        progressDialog.show();

        mTask = new AsyncTask<Void, Object, Object>() {
            @Override
            protected Object doInBackground(Void... voids) {
                boolean success = BackupManager.restoreFullBackup(context, backupFile);
                return success;
            }

            @Override
            protected void onPostExecute(Object result) {
                mTask = null;
                progressDialog.dismiss();
                boolean success = Boolean.TRUE.equals(result);
                if (success) {
                    Toast.makeText(context,
                        R.string.settings_advanced_full_backup_restore_success_message,
                        Toast.LENGTH_LONG).show();
                    // 提示用户重启应用以应用所有设置
                    showRestartDialog(context);
                } else {
                    Toast.makeText(context,
                        R.string.settings_advanced_full_backup_restore_failed_message,
                        Toast.LENGTH_SHORT).show();
                }
            }

        }.execute();
    }

    /**
     * 显示重启提示对话框
     */
    private void showRestartDialog(Context context) {
        new AlertDialog.Builder(context)
            .setTitle(R.string.settings_advanced_full_backup_restore)
            .setMessage(R.string.settings_advanced_full_backup_restore_restart_message)
            .setPositiveButton(R.string.restart_now, (dialog, which) -> {
                // 重启应用
                android.os.Process.killProcess(android.os.Process.myPid());
            })
            .setNegativeButton(R.string.restart_later, null)
            .show();
    }
}
