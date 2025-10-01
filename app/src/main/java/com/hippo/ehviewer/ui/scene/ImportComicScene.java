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

package com.hippo.ehviewer.ui.scene;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.scene.download.DownloadsScene;
import com.hippo.unifile.UniFile;
import com.hippo.util.IoThreadPoolExecutor;
import com.hippo.lib.yorozuya.AssertUtils;
import com.hippo.lib.yorozuya.FileUtils;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.scene.Announcer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 导入漫画压缩包的场景
 */
public class ImportComicScene extends ToolbarScene implements View.OnClickListener {

    private static final int REQUEST_CODE_CHOOSE_ARCHIVE = 0;
    private static final Pattern PATTERN_GID_TOKEN = Pattern.compile("(\\d+)-(\\w+)");

    private TextView mTipText;
    private Button mPickArchiveButton;
    private View mImportProgress;

    @Nullable
    @Override
    public View onCreateView3(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_import_comic, container, false);
        mTipText = view.findViewById(R.id.tip_text);
        mPickArchiveButton = view.findViewById(R.id.pick_archive_button);
        mImportProgress = view.findViewById(R.id.import_progress);
        
        mPickArchiveButton.setOnClickListener(this);
        
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setTitle(R.string.import_comic);
    }

    @Override
    public void onClick(View v) {
        if (v == mPickArchiveButton) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("application/zip");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_CODE_CHOOSE_ARCHIVE);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_CHOOSE_ARCHIVE && resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null) {
                importArchive(data.getData());
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @SuppressLint("StaticFieldLeak")
    private void importArchive(Uri archiveUri) {
        mPickArchiveButton.setEnabled(false);
        mImportProgress.setVisibility(View.VISIBLE);
        mTipText.setText(R.string.importing_comic);

        new AsyncTask<Void, Void, Boolean>() {
            private String errorMessage;
            private DownloadInfo downloadInfo;

            // 工具方法：标准化扩展名，确保以点开头
            private String normalizeExtension(String ext) {
                if (ext == null) return ".jpg";
                return ext.startsWith(".") ? ext : "." + ext;
            }

            @Override
            protected Boolean doInBackground(Void... voids) {
                // 1. 解析文件名、gid、token
                String fileName = getFileName(archiveUri);
                if (fileName == null) {
                    errorMessage = getString(R.string.error_invalid_archive);
                    return false;
                }
                Matcher matcher = PATTERN_GID_TOKEN.matcher(fileName);
                long gid;
                String token;
                if (matcher.find()) {
                    gid = Long.parseLong(matcher.group(1));
                    token = matcher.group(2);
                } else {
                    // 以999开头，后面拼接16位随机数字，保证总长度19位以内
                    long randomPart = Math.abs(UUID.randomUUID().getLeastSignificantBits()) % 10000000000000000L; // 16位
                    gid = 999000000000000000L + randomPart;
                    token = UUID.randomUUID().toString().substring(0, 10);
                }

                // 2. 创建下载信息对象
                downloadInfo = new DownloadInfo(gid);
                downloadInfo.token = token;
                downloadInfo.title = FileUtils.getNameFromFilename(fileName);
                downloadInfo.titleJpn = downloadInfo.title; // 日文标题同title
                downloadInfo.category = 2; // 分区为DOUJINSHI
                downloadInfo.rating = 5.0f; // 评分满分
                downloadInfo.state = DownloadInfo.STATE_FINISH;
                downloadInfo.time = System.currentTimeMillis();

                // 3. 创建下载目录
                UniFile downloadDir = SpiderDen.getGalleryDownloadDir(downloadInfo);
                if (downloadDir == null || !downloadDir.ensureDir()) {
                    errorMessage = getString(R.string.error_create_download_dir);
                    return false;
                }

                // 4. 一次遍历zip流，收集图片条目，写入磁盘，记录第一张图片数据用于缩略图
                java.util.List<String> sortedEntryNames = new java.util.ArrayList<>();
                java.util.Map<String, String> entryNameToFileName = new java.util.HashMap<>();
                byte[] firstImageData = null;
                String firstEntryName = null;
                int count = 0;
                try (InputStream is = requireContext().getContentResolver().openInputStream(archiveUri);
                     ZipInputStream zis = new ZipInputStream(is)) {
                    ZipEntry zipEntry;
                    byte[] buffer = new byte[8192];
                    // 4.1 收集所有图片条目名
                    while ((zipEntry = zis.getNextEntry()) != null) {
                        String entryName = zipEntry.getName();
                        if (!zipEntry.isDirectory() && isImageFile(entryName)) {
                            sortedEntryNames.add(entryName);
                        }
                    }
                } catch (Exception e) {
                    errorMessage = getString(R.string.error_reading_archive);
                    return false;
                }

                // 4.2 按文件名排序
                sortedEntryNames.sort(String::compareToIgnoreCase);
                if (sortedEntryNames.isEmpty()) {
                    errorMessage = getString(R.string.error_no_images_found);
                    return false;
                }

                // 4.3 再次遍历zip流，按排序后顺序写入磁盘，并记录第一张图片数据
                try (InputStream is = requireContext().getContentResolver().openInputStream(archiveUri);
                     ZipInputStream zis = new ZipInputStream(is)) {
                    ZipEntry zipEntry;
                    byte[] buffer = new byte[8192];
                    int imgIndex = 0;
                    while ((zipEntry = zis.getNextEntry()) != null) {
                        String entryName = zipEntry.getName();
                        if (!zipEntry.isDirectory() && isImageFile(entryName)) {
                            int sortedIndex = sortedEntryNames.indexOf(entryName);
                            if (sortedIndex == -1) continue;
                            String extension = normalizeExtension(FileUtils.getExtensionFromFilename(entryName));
                            String imageName = String.format(Locale.US, "%08d%s", sortedIndex + 1, extension);
                            entryNameToFileName.put(entryName, imageName);
                            UniFile imageFile = downloadDir.createFile(imageName);
                            if (imageFile != null) {
                                OutputStream os = null;
                                try {
                                    os = imageFile.openOutputStream();
                                    java.io.ByteArrayOutputStream baos = null;
                                    if (sortedIndex == 0) baos = new java.io.ByteArrayOutputStream();
                                    int read;
                                    while ((read = zis.read(buffer)) != -1) {
                                        os.write(buffer, 0, read);
                                        if (baos != null) baos.write(buffer, 0, read);
                                    }
                                    if (baos != null) {
                                        firstImageData = baos.toByteArray();
                                        firstEntryName = entryName;
                                    }
                                } finally {
                                    IOUtils.closeQuietly(os);
                                }
                            }
                            count++;
                        }
                    }
                } catch (Exception e) {
                    errorMessage = getString(R.string.error_reading_archive);
                    return false;
                }

                // 5. 生成缩略图（取第一张图片，缩放为160x240，保存为.thumb文件，并赋值thumb字段，PNG无损）
                try {
                    if (firstImageData != null) {
                        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(firstImageData, 0, firstImageData.length);
                        if (bitmap != null) {
                            android.graphics.Bitmap thumbBmp = android.graphics.Bitmap.createScaledBitmap(bitmap, 160, 240, true);
                            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                            thumbBmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos);
                            byte[] thumbBytes = baos.toByteArray();
                            UniFile thumbFile = downloadDir.createFile(".thumb");
                            if (thumbFile != null) {
                                OutputStream tos = null;
                                try {
                                    tos = thumbFile.openOutputStream();
                                    tos.write(thumbBytes);
                                    tos.flush();
                                    downloadInfo.thumb = ".thumb";
                                } finally {
                                    IOUtils.closeQuietly(tos);
                                }
                            }
                            thumbBmp.recycle();
                            bitmap.recycle();
                        }
                    }
                } catch (Throwable t) {
                    // 忽略缩略图生成异常
                }

                // 6. 创建 SpiderInfo 文件
                SpiderInfo spiderInfo = new SpiderInfo();
                spiderInfo.gid = gid;
                spiderInfo.token = token;
                spiderInfo.pages = count;
                spiderInfo.previewPages = 0;
                spiderInfo.previewPerPage = 0;
                spiderInfo.pTokenMap = new android.util.SparseArray<>(count);
                for(int i = 0; i < count; i++) {
                    spiderInfo.pTokenMap.put(i, "imported" + i);
                }
                UniFile spiderInfoFile = downloadDir.createFile(SpiderQueen.SPIDER_INFO_FILENAME);
                if (spiderInfoFile != null) {
                    OutputStream os = null;
                    try {
                        os = spiderInfoFile.openOutputStream();
                        spiderInfo.write(os);
                    } catch (Exception e) {
                        // 写入SpiderInfo文件时发生异常，忽略或可记录日志
                        e.printStackTrace();
                    } finally {
                        IOUtils.closeQuietly(os);
                    }
                }

                // 7. 只设置总页数，状态设为未启动，让下载管理器重新处理
                downloadInfo.total = count;
                downloadInfo.finished = 0;
                downloadInfo.downloaded = 0;
                // 设置为等待中，确保下载管理器能自动拉起任务
                downloadInfo.state = DownloadInfo.STATE_WAIT;

                // 8. 先删除同gid任务，确保addDownload能生效
                EhDB.removeDownloadInfo(downloadInfo.gid);
                EhDB.putDownloadInfo(downloadInfo);

                return true;
            }

            @Override
            protected void onPostExecute(Boolean success) {
                mImportProgress.setVisibility(View.GONE);
                mPickArchiveButton.setEnabled(true);

                if (success && downloadInfo != null) {
                    mTipText.setText(R.string.import_comic_success);
                    Toast.makeText(requireContext(), getString(R.string.import_comic_success), Toast.LENGTH_SHORT).show();

                    // 通知下载管理器，addDownload后用反射强制调用startDownload，确保立即拉起
                    DownloadManager manager = EhApplication.getDownloadManager(requireContext());
                    if (manager != null) {
                        manager.addDownload(downloadInfo, null);
                        try {
                            java.lang.reflect.Method m = manager.getClass().getDeclaredMethod("startDownload", com.hippo.ehviewer.client.data.GalleryInfo.class, String.class);
                            m.setAccessible(true);
                            m.invoke(manager, downloadInfo, null);
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    }

                    // 返回下载页面
                    Activity activity = getActivity();
                    if (activity instanceof MainActivity && activity != null) {
                        try {
                            ((MainActivity) activity).navtoDownloadsScene();
                        } catch (Exception e) {
                            // 如果导航失败，记录错误并尝试直接启动场景
                            e.printStackTrace();
                            try {
                                startScene(new Announcer(DownloadsScene.class));
                            } catch (Exception ex) {
                                // 忽略，不中断流程
                                ex.printStackTrace();
                            }
                        }
                    }
                } else {
                    mTipText.setText(errorMessage != null ? errorMessage : getString(R.string.error_importing_comic));
                }
            }
        }.executeOnExecutor(IoThreadPoolExecutor.getInstance());
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try {
                String[] projection = {android.provider.MediaStore.MediaColumns.DISPLAY_NAME};
                try (android.database.Cursor cursor = requireContext().getContentResolver().query(uri, projection, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIndex = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME);
                        if (nameIndex >= 0) {
                            result = cursor.getString(nameIndex);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    private boolean isImageFile(String fileName) {
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || 
               lowerName.endsWith(".png") || lowerName.endsWith(".gif") ||
               lowerName.endsWith(".webp");
    }
}