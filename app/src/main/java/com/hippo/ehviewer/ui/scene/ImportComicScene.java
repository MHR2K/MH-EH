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
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
    private RadioGroup mModeGroup;
    private RadioButton mRbCbz;
    private RadioButton mRbImages;
    private TextView mProgressText;
    private TextView mFailureText;

    @Nullable
    @Override
    public View onCreateView3(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_import_comic, container, false);
        mTipText = view.findViewById(R.id.tip_text);
        mPickArchiveButton = view.findViewById(R.id.pick_archive_button);
        mImportProgress = view.findViewById(R.id.import_progress);
        mModeGroup = view.findViewById(R.id.import_mode_group);
        mRbCbz = view.findViewById(R.id.rb_import_cbz);
        mRbImages = view.findViewById(R.id.rb_import_images);
        mProgressText = view.findViewById(R.id.progress_text);
        mFailureText = view.findViewById(R.id.failure_text);
        
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
            // 放宽类型，使用扩展名再校验
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/zip",
                    "application/x-zip",
                    "application/x-zip-compressed",
                    "application/octet-stream",
                    "application/x-cbz"
            });
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQUEST_CODE_CHOOSE_ARCHIVE);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_CHOOSE_ARCHIVE && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                java.util.List<Uri> uris = new java.util.ArrayList<>();
                if (data.getClipData() != null) {
                    android.content.ClipData clipData = data.getClipData();
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        Uri u = clipData.getItemAt(i).getUri();
                        if (u != null) uris.add(u);
                    }
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
                if (!uris.isEmpty()) {
                    boolean asCbz = mRbCbz != null && mRbCbz.isChecked();
                    importArchives(uris, asCbz);
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @SuppressLint("StaticFieldLeak")
    private void importArchives(java.util.List<Uri> archiveUris, boolean importAsCbz) {
        mPickArchiveButton.setEnabled(false);
        mImportProgress.setVisibility(View.VISIBLE);
        mTipText.setText(R.string.importing_comic);

        new AsyncTask<Void, Integer, Boolean>() {
            private String errorMessage;
            private int successCount;
            private int failCount;
            private java.util.List<String> failedDetails = new java.util.ArrayList<>();
            private int total;

            // 工具方法：标准化扩展名，确保以点开头
            private String normalizeExtension(String ext) {
                if (ext == null) return ".jpg";
                return ext.startsWith(".") ? ext : "." + ext;
            }

            @Override
            protected Boolean doInBackground(Void... voids) {
                total = archiveUris.size();
                for (int i = 0; i < archiveUris.size(); i++) {
                    Uri uri = archiveUris.get(i);
                    String name = getFileName(uri);
                    boolean ok = importSingle(uri, importAsCbz);
                    if (ok) successCount++; else failCount++;
                    if (!ok) {
                        String reason = errorMessage != null ? errorMessage : getString(R.string.error_importing_comic);
                        String showName = name != null ? name : String.valueOf(uri);
                        failedDetails.add(showName + ": " + reason);
                    }
                    publishProgress(i + 1, total);
                }
                return successCount > 0 && failCount == 0;
            }

            

            private boolean importSingle(Uri archiveUri, boolean asCbz) {
                String fileName = getFileName(archiveUri);
                if (fileName == null || !com.hippo.ehviewer.util.ArchiveSupportUtils.isSupportedArchiveName(fileName)) {
                    errorMessage = getString(R.string.error_invalid_archive);
                    return false;
                }

                Matcher matcher = PATTERN_GID_TOKEN.matcher(fileName);
                long gid; String token;
                if (matcher.find()) {
                    gid = Long.parseLong(matcher.group(1));
                    token = matcher.group(2);
                } else {
                    // 使用当前时间戳作为gid，避免随机生成
                    gid = System.currentTimeMillis();
                    token = UUID.randomUUID().toString().substring(0, 10);
                }

                DownloadInfo downloadInfo = new DownloadInfo(gid);
                downloadInfo.token = token;
                downloadInfo.title = FileUtils.getNameFromFilename(fileName);
                downloadInfo.titleJpn = downloadInfo.title;
                downloadInfo.category = 2;
                downloadInfo.rating = 5.0f;
                downloadInfo.time = System.currentTimeMillis();

                UniFile downloadDir = SpiderDen.getGalleryDownloadDir(downloadInfo);
                if (downloadDir == null || !downloadDir.ensureDir()) {
                    errorMessage = getString(R.string.error_create_download_dir);
                    return false;
                }

                java.util.List<String> sortedEntryNames = new java.util.ArrayList<>();
                byte[] firstImageData = null;
                int count;
                try (InputStream is = requireContext().getContentResolver().openInputStream(archiveUri);
                     ZipInputStream zis = new ZipInputStream(is)) {
                    ZipEntry zipEntry;
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
                sortedEntryNames.sort(String::compareToIgnoreCase);
                if (sortedEntryNames.isEmpty()) {
                    errorMessage = getString(R.string.error_no_images_found);
                    return false;
                }
                count = sortedEntryNames.size();

                if (asCbz) {
                    java.util.Map<String, Integer> nameToIndex = new java.util.HashMap<>();
                    for (int i = 0; i < sortedEntryNames.size(); i++) nameToIndex.put(sortedEntryNames.get(i), i);
                    UniFile cbzFile = downloadDir.createFile(gid + ".cbz");
                    if (cbzFile == null) {
                        errorMessage = getString(R.string.error_importing_comic);
                        return false;
                    }
                    java.util.zip.ZipOutputStream zos = null; InputStream is2 = null; ZipInputStream zis2 = null;
                    try {
                        OutputStream osCbz = cbzFile.openOutputStream();
                        zos = new java.util.zip.ZipOutputStream(osCbz);
                        is2 = requireContext().getContentResolver().openInputStream(archiveUri);
                        zis2 = new ZipInputStream(is2);
                        ZipEntry ze; byte[] buf = new byte[8192];
                        while ((ze = zis2.getNextEntry()) != null) {
                            String en = ze.getName();
                            if (ze.isDirectory() || !isImageFile(en)) continue;
                            Integer sortedIdx = nameToIndex.get(en);
                            if (sortedIdx == null) continue;
                            String ext = normalizeExtension(FileUtils.getExtensionFromFilename(en));
                            String outName = String.format(Locale.US, "%08d%s", sortedIdx + 1, ext);
                            java.util.zip.ZipEntry out = new java.util.zip.ZipEntry(outName);
                            zos.putNextEntry(out);
                            int r; java.io.ByteArrayOutputStream firstBaos = null;
                            if (sortedIdx == 0) firstBaos = new java.io.ByteArrayOutputStream();
                            while ((r = zis2.read(buf)) != -1) {
                                zos.write(buf, 0, r);
                                if (firstBaos != null) firstBaos.write(buf, 0, r);
                            }
                            zos.closeEntry();
                            if (firstBaos != null) firstImageData = firstBaos.toByteArray();
                        }
                        zos.finish();
                    } catch (Exception e) {
                        errorMessage = getString(R.string.error_importing_comic);
                        return false;
                    } finally {
                        IOUtils.closeQuietly(zos);
                        IOUtils.closeQuietly(zis2);
                        IOUtils.closeQuietly(is2);
                    }
                } else {
                    java.util.Map<String, Integer> nameToIndex = new java.util.HashMap<>();
                    for (int i = 0; i < sortedEntryNames.size(); i++) nameToIndex.put(sortedEntryNames.get(i), i);
                    try (InputStream is = requireContext().getContentResolver().openInputStream(archiveUri);
                         ZipInputStream zis = new ZipInputStream(is)) {
                        ZipEntry zipEntry; byte[] buffer = new byte[8192];
                        while ((zipEntry = zis.getNextEntry()) != null) {
                            String en = zipEntry.getName();
                            if (zipEntry.isDirectory() || !isImageFile(en)) continue;
                            Integer idx = nameToIndex.get(en);
                            if (idx == null) continue;
                            String ext = normalizeExtension(FileUtils.getExtensionFromFilename(en));
                            String imageName = String.format(Locale.US, "%08d%s", idx + 1, ext);
                            UniFile imageFile = downloadDir.createFile(imageName);
                            if (imageFile == null) continue;
                            OutputStream os = null;
                            try {
                                os = imageFile.openOutputStream();
                                int read; java.io.ByteArrayOutputStream firstBaos = null;
                                if (idx == 0) firstBaos = new java.io.ByteArrayOutputStream();
                                while ((read = zis.read(buffer)) != -1) {
                                    os.write(buffer, 0, read);
                                    if (firstBaos != null) firstBaos.write(buffer, 0, read);
                                }
                                if (firstBaos != null) firstImageData = firstBaos.toByteArray();
                            } finally { IOUtils.closeQuietly(os); }
                        }
                    } catch (Exception e) {
                        errorMessage = getString(R.string.error_reading_archive);
                        return false;
                    }
                }

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
                                try { tos = thumbFile.openOutputStream(); tos.write(thumbBytes); tos.flush(); } finally { IOUtils.closeQuietly(tos); }
                                // 记录封面相对路径，供下载页加载
                                downloadInfo.thumb = ".thumb";
                            }
                            thumbBmp.recycle();
                            bitmap.recycle();
                        }
                    }
                } catch (Throwable t) { }

                SpiderInfo spiderInfo = new SpiderInfo();
                spiderInfo.gid = gid;
                spiderInfo.token = token;
                spiderInfo.pages = count;
                spiderInfo.previewPages = 0;
                spiderInfo.previewPerPage = 0;
                spiderInfo.pTokenMap = new android.util.SparseArray<>(count);
                for (int i = 0; i < count; i++) {
                    spiderInfo.pTokenMap.put(i, "imported" + i);
                }
                UniFile spiderInfoFile = downloadDir.createFile(SpiderQueen.SPIDER_INFO_FILENAME);
                if (spiderInfoFile != null) {
                    OutputStream os = null;
                    try { os = spiderInfoFile.openOutputStream(); spiderInfo.write(os); } catch (Exception ignored) { } finally { IOUtils.closeQuietly(os); }
                }

                // 导入完成后直接标记为已完成，避免卡在“等待中”
                downloadInfo.total = count;
                downloadInfo.finished = count;
                downloadInfo.downloaded = count;
                downloadInfo.state = DownloadInfo.STATE_FINISH;

                DownloadManager manager = EhApplication.getDownloadManager(requireContext());
                if (manager != null) {
                    // 通过 GalleryInfo 接口添加，并设置为已完成
                    GalleryInfo gi = new GalleryInfo();
                    gi.gid = gid; gi.token = token; gi.title = downloadInfo.title; gi.titleJpn = downloadInfo.titleJpn;
                    gi.category = downloadInfo.category; gi.thumb = downloadInfo.thumb;
                    manager.addDownload(gi, null, DownloadInfo.STATE_FINISH);
                    // 用 DB 更新计数信息（总页数/完成数）
                    EhDB.putDownloadInfo(downloadInfo);
                } else {
                    // 后备：直接写入数据库
                    EhDB.putDownloadInfo(downloadInfo);
                }

                return true;
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                if (mProgressText != null) {
                    mProgressText.setVisibility(View.VISIBLE);
                    if (values != null && values.length >= 2) {
                        mProgressText.setText(getString(R.string.import_progress_fmt, values[0], values[1]));
                    }
                }
            }

            @Override
            protected void onPostExecute(Boolean success) {
                mImportProgress.setVisibility(View.GONE);
                mPickArchiveButton.setEnabled(true);

                if (successCount > 0) {
                    mTipText.setText(R.string.import_comic_success);
                    Toast.makeText(requireContext(), getString(R.string.import_summary, successCount, failCount), Toast.LENGTH_SHORT).show();

                    if (failCount > 0 && mFailureText != null) {
                        mFailureText.setVisibility(View.VISIBLE);
                        StringBuilder sb = new StringBuilder();
                        sb.append(getString(R.string.import_failed_header)).append('\n');
                        for (String line : failedDetails) sb.append("- ").append(line).append('\n');
                        mFailureText.setText(sb.toString());
                        // 有失败则不自动跳转，便于查看详情
                    } else {
                        Activity activity = getActivity();
                        if (activity instanceof MainActivity && activity != null) {
                            try { ((MainActivity) activity).navtoDownloadsScene(); }
                            catch (Exception e) { try { startScene(new Announcer(DownloadsScene.class)); } catch (Exception ignored) {} }
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