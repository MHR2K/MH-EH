package com.hippo.ehviewer.ui.scene.gallery.detail;

import static com.hippo.ehviewer.util.ClipboardUtil.createAnnouncerFromClipboardUrl;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.android.resource.AttrResources;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.NewVersion;
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.ui.scene.download.DownloadsScene;
import com.hippo.scene.Announcer;

import java.util.ArrayList;
import java.util.List;

public class GalleryUpdateDialog {

    /**
     * 更新模式常量
     */
    public static final int MODE_UPDATE_BACK = 0;      // 更新后方：前N页相同，下载新增后几页
    public static final int MODE_UPDATE_FRONT = 1;     // 更新前方：新版前面多了页，重编号旧页
    public static final int MODE_FULL_REPLACE = 2;     // 全部替换：删旧目录，全量重下
    public static final int MODE_CUSTOM = 3;           // 自定义：用户指定页数映射

    /**
     * 更新请求数据
     */
    public static class UpdateRequest {
        public final int mode;
        public final NewVersion newVersion;
        public DownloadInfo targetOldInfo;
        // 自定义模式的页数范围
        public int customOldStart = 1;
        public int customOldEnd = 1;
        public int customNewStart = 1;
        public int customNewEnd = 1;

        public UpdateRequest(int mode, NewVersion newVersion) {
            this(mode, newVersion, null);
        }

        public UpdateRequest(int mode, NewVersion newVersion, DownloadInfo targetOldInfo) {
            this.mode = mode;
            this.newVersion = newVersion;
            this.targetOldInfo = targetOldInfo;
        }
    }

    public interface OnUpdateConfirmListener {
        void onUpdateConfirm(UpdateRequest request);
    }

    private final GalleryDetailScene detailScene;
    private final Context context;

    private GalleryDetail galleryDetail;
    private AlertDialog currentDialog;
    private OnUpdateConfirmListener updateConfirmListener;

    public GalleryUpdateDialog(GalleryDetailScene scene, Context context) {
        this.detailScene = scene;
        this.context = context;
    }

    public void setOnUpdateConfirmListener(OnUpdateConfirmListener listener) {
        this.updateConfirmListener = listener;
    }

    /**
     * 显示入口对话框：查看新版本详情 / 更新下载
     * 当点击"有新版本"按钮时调用
     * @param isDownloaded 当前 gallery 是否已下载
     */
    public void showEntryDialog(GalleryDetail galleryDetail, boolean isDownloaded) {
        this.galleryDetail = galleryDetail;
        if (galleryDetail == null || galleryDetail.newVersions == null) {
            return;
        }

        String[] versionNames = galleryDetail.getUpdateVersionName();
        // 如果只有一个新版本，直接显示选项
        if (versionNames.length == 1) {
            showActionChoiceDialog(galleryDetail.newVersions[0], isDownloaded);
            return;
        }
        // 多个新版本，先选择版本
        if (currentDialog != null) {
            currentDialog.dismiss();
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.gallery_update_select_version)
                .setSingleChoiceItems(versionNames, -1, (dialog, which) -> {
                    dialog.dismiss();
                    showActionChoiceDialog(galleryDetail.newVersions[which], isDownloaded);
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss());
        currentDialog = builder.create();
        currentDialog.show();
    }

    /**
    /**
     * 显示旧版本入口对话框：支持多个旧版本选择及单版本直接操作
     * @param currentDetail 当前画廊的 GalleryDetail
     * @param oldVersions 发现的旧版本列表
     */
    /**
     * 获取下载状态对应的多语言文本
     */
    public static String getDownloadStateString(@NonNull Context context, int state) {
        switch (state) {
            case DownloadInfo.STATE_FINISH:
                return context.getString(R.string.download_state_downloaded);
            case DownloadInfo.STATE_NONE:
                return context.getString(R.string.download_state_none);
            case DownloadInfo.STATE_WAIT:
                return context.getString(R.string.download_state_wait);
            case DownloadInfo.STATE_DOWNLOAD:
                return context.getString(R.string.download_state_downloading);
            case DownloadInfo.STATE_FAILED:
                return context.getString(R.string.download_state_failed);
            default:
                return context.getString(R.string.gallery_old_version_not_downloaded);
        }
    }

    /**
     * 获取下载状态对应的标签显示颜色（适配深色与浅色主题）
     */
    public static int getDownloadStateColor(int state, boolean isDarkTheme) {
        switch (state) {
            case DownloadInfo.STATE_FINISH:
                return isDarkTheme ? 0xFF66BB6A : 0xFF2E7D32;
            case DownloadInfo.STATE_DOWNLOAD:
                return isDarkTheme ? 0xFF42A5F5 : 0xFF1976D2;
            case DownloadInfo.STATE_WAIT:
                return isDarkTheme ? 0xFFFFA726 : 0xFFF57C00;
            case DownloadInfo.STATE_FAILED:
                return isDarkTheme ? 0xFFEF5350 : 0xFFD32F2F;
            case DownloadInfo.STATE_NONE:
                return isDarkTheme ? 0xFFBA68C8 : 0xFF7B1FA2;
            default:
                return isDarkTheme ? 0xFF9E9E9E : 0xFF757575;
        }
    }

    /**
     * 将旧版本信息格式化为包含标题、上传时间与彩色状态标签的富文本
     */
    @NonNull
    private CharSequence formatOldVersionItem(@NonNull GalleryDetailScene.OldVersion ov, boolean isDarkTheme) {
        String title = ov.title != null && !ov.title.isEmpty() ? ov.title : ("GID: " + ov.gid);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(title);
        sb.append("\n");

        int subStart = sb.length();
        String postedStr = ov.posted != null && !ov.posted.isEmpty()
                ? ov.posted : context.getString(R.string.gallery_old_version_unknown_posted);
        sb.append(context.getString(R.string.gallery_old_version_posted, postedStr));
        sb.append("   |   ");
        sb.append(context.getString(R.string.gallery_old_version_status_prefix));

        int stateStart = sb.length();
        String stateStr = getDownloadStateString(context, ov.downloadState);
        sb.append(stateStr);
        int stateEnd = sb.length();

        sb.setSpan(new RelativeSizeSpan(0.82f), subStart, stateEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int mutedColor = isDarkTheme ? 0xFFB0BEC5 : 0xFF757575;
        sb.setSpan(new ForegroundColorSpan(mutedColor), subStart, stateStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        int stateColor = getDownloadStateColor(ov.downloadState, isDarkTheme);
        sb.setSpan(new ForegroundColorSpan(stateColor), stateStart, stateEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), stateStart, stateEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        return sb;
    }

    /**
     * 显示旧版本入口对话框：支持多个旧版本选择及单版本直接操作
     * @param currentDetail 当前画廊的 GalleryDetail
     * @param oldVersions 发现的旧版本列表
     */
    public void showOldVersionDialog(@NonNull GalleryDetail currentDetail,
                                     @NonNull List<GalleryDetailScene.OldVersion> oldVersions) {
        if (oldVersions.isEmpty()) {
            return;
        }

        // 刷新所有旧版本的实时下载状态和元数据
        Context appCtx = context.getApplicationContext();
        DownloadManager dm = EhApplication.getDownloadManager(appCtx);
        for (GalleryDetailScene.OldVersion ov : oldVersions) {
            if (dm != null) {
                int state = dm.getDownloadState(ov.gid);
                ov.downloadState = state;
                ov.isDownloaded = (state == DownloadInfo.STATE_FINISH);
                DownloadInfo di = dm.getDownloadInfo(ov.gid);
                if (di != null) {
                    ov.downloadInfo = di;
                    if (ov.posted == null || ov.posted.isEmpty()) {
                        ov.posted = di.posted;
                    }
                    if (ov.title == null || ov.title.isEmpty() || ov.title.startsWith("GID: ")) {
                        ov.title = EhUtils.getSuitableTitle(di);
                    }
                }
            }
            if (ov.posted == null && ov.galleryDetail != null) {
                ov.posted = ov.galleryDetail.posted;
            }
            if ((ov.title == null || ov.title.isEmpty() || ov.title.startsWith("GID: ")) && ov.galleryDetail != null) {
                ov.title = EhUtils.getSuitableTitle(ov.galleryDetail);
            }
        }

        if (oldVersions.size() == 1) {
            showSingleOldVersionActions(currentDetail, oldVersions.get(0));
            return;
        }

        if (currentDialog != null) {
            currentDialog.dismiss();
        }

        boolean isDarkTheme = !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme);
        CharSequence[] items = new CharSequence[oldVersions.size()];
        for (int i = 0; i < oldVersions.size(); i++) {
            items[i] = formatOldVersionItem(oldVersions.get(i), isDarkTheme);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<CharSequence>(context, R.layout.item_select_dialog, items) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                int paddingVertical = (int) (10 * parent.getResources().getDisplayMetrics().density);
                int paddingLeft = view.getPaddingLeft();
                int paddingRight = view.getPaddingRight();
                view.setPadding(paddingLeft, paddingVertical, paddingRight, paddingVertical);
                return view;
            }
        };

        builder.setTitle(R.string.gallery_update_select_old_version)
                .setAdapter(adapter, (dialog, which) -> {
                    dialog.dismiss();
                    showSingleOldVersionActions(currentDetail, oldVersions.get(which));
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss());
        currentDialog = builder.create();
        currentDialog.show();
    }

    private void showSingleOldVersionActions(@NonNull GalleryDetail currentDetail,
                                             @NonNull GalleryDetailScene.OldVersion oldVersion) {
        if (currentDialog != null) {
            currentDialog.dismiss();
        }

        List<String> itemList = new ArrayList<>();
        List<Integer> actionList = new ArrayList<>();

        itemList.add(context.getString(R.string.gallery_old_version_view_detail));
        actionList.add(0);

        if (oldVersion.downloadInfo != null) {
            itemList.add(context.getString(R.string.gallery_old_version_view_in_download));
            actionList.add(1);
        }

        if (oldVersion.isDownloaded && oldVersion.downloadInfo != null) {
            itemList.add(context.getString(R.string.gallery_old_version_update_old));
            actionList.add(2);
        }

        String[] items = itemList.toArray(new String[0]);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        boolean isDarkTheme = !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme);
        CharSequence dialogTitle = formatOldVersionItem(oldVersion, isDarkTheme);

        builder.setTitle(dialogTitle)
                .setItems(items, (dialog, which) -> {
                    dialog.dismiss();
                    int action = actionList.get(which);
                    if (action == 0) {
                        // 查看旧版本详情 — 跳转到旧版本页面
                        Announcer announcer = createAnnouncerFromClipboardUrl(oldVersion.versionUrl);
                        if (announcer != null) {
                            detailScene.startScene(announcer);
                        }
                    } else if (action == 1 && oldVersion.downloadInfo != null) {
                        // 在下载列表中查看
                        Bundle args = new Bundle();
                        args.putLong(DownloadsScene.KEY_GID, oldVersion.downloadInfo.gid);
                        detailScene.startScene(new Announcer(DownloadsScene.class).setArgs(args));
                    } else if (action == 2 && oldVersion.downloadInfo != null) {
                        // 更新旧版本
                        GalleryDetail oldDetail = oldVersion.galleryDetail;
                        if (oldDetail == null) {
                            oldDetail = new GalleryDetail();
                            oldDetail.gid = oldVersion.downloadInfo.gid;
                            oldDetail.token = oldVersion.downloadInfo.token;
                            oldDetail.title = oldVersion.downloadInfo.title;
                            oldDetail.pages = oldVersion.downloadInfo.pages;
                            oldDetail.thumb = oldVersion.downloadInfo.thumb;
                            oldDetail.category = oldVersion.downloadInfo.category;
                            oldDetail.uploader = oldVersion.downloadInfo.uploader;
                            oldDetail.posted = oldVersion.downloadInfo.posted;
                        }

                        NewVersion newVersion = new NewVersion();
                        newVersion.versionUrl = EhUrl.getGalleryDetailUrl(currentDetail.gid, currentDetail.token);
                        newVersion.versionName = EhUtils.getSuitableTitle(currentDetail);

                        showUpdateModeDialog(oldDetail, newVersion, oldVersion.downloadInfo, currentDetail);
                    }
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss());
        currentDialog = builder.create();
        currentDialog.show();
    }

    public void showOldVersionDialog(@NonNull GalleryDetail currentDetail,
                                     @NonNull String parentUrl,
                                     @Nullable DownloadInfo oldDownloadInfo) {
        GalleryDetailScene.OldVersion ov = new GalleryDetailScene.OldVersion();
        ov.versionUrl = parentUrl;
        GalleryDetailUrlParser.Result r = GalleryDetailUrlParser.parse(parentUrl, false);
        if (r != null) {
            ov.gid = r.gid;
            ov.token = r.token;
        }
        ov.downloadInfo = oldDownloadInfo;
        ov.isDownloaded = oldDownloadInfo != null && oldDownloadInfo.state == DownloadInfo.STATE_FINISH;
        ov.downloadState = oldDownloadInfo != null ? oldDownloadInfo.state : DownloadInfo.STATE_INVALID;
        if (oldDownloadInfo != null) {
            ov.title = EhUtils.getSuitableTitle(oldDownloadInfo);
            ov.posted = oldDownloadInfo.posted;
        }
        showSingleOldVersionActions(currentDetail, ov);
    }

    /**
     * 显示操作选择对话框：查看新版本详情 / 更新下载
     */
    private void showActionChoiceDialog(NewVersion newVersion, boolean isDownloaded) {
        if (currentDialog != null) {
            currentDialog.dismiss();
        }
        // 如果未下载，只显示"查看详情"选项
        String[] items;
        if (isDownloaded) {
            items = new String[]{
                    context.getString(R.string.gallery_update_view_detail),
                    context.getString(R.string.gallery_update_download)
            };
        } else {
            items = new String[]{
                    context.getString(R.string.gallery_update_view_detail)
            };
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.new_version)
                .setItems(items, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) {
                        // 查看新版本详情 — 跳转到新版本页面
                        Announcer announcer = createAnnouncerFromClipboardUrl(newVersion.versionUrl);
                        detailScene.startScene(announcer);
                    } else if (which == 1 && isDownloaded) {
                        // 更新下载 — 显示更新模式选择
                        showUpdateModeDialog(newVersion);
                    }
                });
        currentDialog = builder.create();
        currentDialog.show();
    }

    /**
     * 显示更新模式选择对话框
     */
    private void showUpdateModeDialog(NewVersion newVersion) {
        showUpdateModeDialog(this.galleryDetail, newVersion, null, null);
    }

    private void showUpdateModeDialog(GalleryDetail targetOldDetail,
                                      NewVersion newVersion,
                                      @Nullable DownloadInfo targetOldInfo,
                                      @Nullable GalleryDetail newDetail) {
        if (currentDialog != null) {
            currentDialog.dismiss();
        }

        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_gallery_update_mode, null);
        RadioGroup modeGroup = dialogView.findViewById(R.id.update_mode_group);
        View customLayout = dialogView.findViewById(R.id.custom_mode_layout);
        EditText etOldStart = dialogView.findViewById(R.id.et_old_start);
        EditText etOldEnd = dialogView.findViewById(R.id.et_old_end);
        EditText etNewStart = dialogView.findViewById(R.id.et_new_start);
        EditText etNewEnd = dialogView.findViewById(R.id.et_new_end);

        // 默认选中"更新后方"
        modeGroup.check(R.id.mode_update_back);
        customLayout.setVisibility(View.GONE);

        // 切换自定义模式时显示/隐藏输入区域
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            customLayout.setVisibility(checkedId == R.id.mode_custom ? View.VISIBLE : View.GONE);
        });

        // 自动计算新版结束页：newEnd = newStart + (oldEnd - oldStart)
        TextWatcher autoCalcWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                int oldStart = parseIntSafe(etOldStart.getText().toString(), 0);
                int oldEnd = parseIntSafe(etOldEnd.getText().toString(), 0);
                int newStart = parseIntSafe(etNewStart.getText().toString(), 0);
                if (oldStart > 0 && oldEnd >= oldStart && newStart > 0) {
                    etNewEnd.setText(String.valueOf(newStart + (oldEnd - oldStart)));
                }
            }
        };
        etOldStart.addTextChangedListener(autoCalcWatcher);
        etOldEnd.addTextChangedListener(autoCalcWatcher);
        etNewStart.addTextChangedListener(autoCalcWatcher);

        View btnComparePreviews = dialogView.findViewById(R.id.btn_compare_previews);
        if (btnComparePreviews != null) {
            btnComparePreviews.setOnClickListener(v -> {
                if (targetOldDetail == null) return;
                DownloadManager dm = EhApplication.getDownloadManager(context);
                DownloadInfo resolvedOldInfo = targetOldInfo != null ? targetOldInfo :
                        (dm != null ? dm.getDownloadInfo(targetOldDetail.gid) : null);
                if (resolvedOldInfo != null) {
                    if (targetOldDetail.pages <= 0 && resolvedOldInfo.pages > 0) {
                        targetOldDetail.pages = resolvedOldInfo.pages;
                    }
                    if (targetOldDetail.token == null && resolvedOldInfo.token != null) {
                        targetOldDetail.token = resolvedOldInfo.token;
                    }
                }
                GalleryUpdatePreviewCompareDialog compareDialog = new GalleryUpdatePreviewCompareDialog(
                        context, targetOldDetail, newVersion, newDetail, resolvedOldInfo, (oldStart, oldEnd, newStart, newEnd) -> {
                    etOldStart.setText(String.valueOf(oldStart));
                    etOldEnd.setText(String.valueOf(oldEnd));
                    etNewStart.setText(String.valueOf(newStart));
                    etNewEnd.setText(String.valueOf(newEnd));
                });
                compareDialog.show();
            });
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.gallery_update_mode_title)
                .setView(dialogView)
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss())
                .setPositiveButton(R.string.gallery_update_next, (dialog, which) -> {
                    int mode = getSelectedMode(modeGroup);
                    UpdateRequest request = new UpdateRequest(mode, newVersion, targetOldInfo);
                    if (mode == MODE_CUSTOM) {
                        request.customOldStart = parseIntSafe(etOldStart.getText().toString(), 1);
                        request.customOldEnd = parseIntSafe(etOldEnd.getText().toString(), 1);
                        request.customNewStart = parseIntSafe(etNewStart.getText().toString(), 1);
                        request.customNewEnd = parseIntSafe(etNewEnd.getText().toString(), 1);
                        // 验证自定义模式的页数范围
                        String error = validateCustomRange(request, targetOldDetail != null ? targetOldDetail.pages : 0);
                        if (error != null) {
                            // 不关闭对话框，显示错误提示
                            new AlertDialog.Builder(context)
                                    .setMessage(error)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                            return;
                        }
                    }
                    dialog.dismiss();
                    showConfirmDialog(request, targetOldDetail);
                });
        currentDialog = builder.create();
        currentDialog.show();
    }

    private int getSelectedMode(RadioGroup group) {
        int checkedId = group.getCheckedRadioButtonId();
        if (checkedId == R.id.mode_update_back) return MODE_UPDATE_BACK;
        if (checkedId == R.id.mode_update_front) return MODE_UPDATE_FRONT;
        if (checkedId == R.id.mode_full_replace) return MODE_FULL_REPLACE;
        if (checkedId == R.id.mode_custom) return MODE_CUSTOM;
        return MODE_UPDATE_BACK;
    }

    /**
     * 显示预览确认对话框
     */
    private void showConfirmDialog(UpdateRequest request, GalleryDetail oldDetail) {
        if (currentDialog != null) {
            currentDialog.dismiss();
        }
        if (oldDetail == null) return;

        int oldPages = oldDetail.pages;
        String modeName = getModeName(request.mode);
        String desc = buildUpdateDescription(request, oldPages);

        String message = context.getString(R.string.gallery_update_confirm_message,
                oldDetail.gid, EhUtils.getSuitableTitle(oldDetail), oldPages,
                request.newVersion.versionName, modeName, desc);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.gallery_update_confirm_title)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss())
                .setPositiveButton(R.string.gallery_update_start, (dialog, which) -> {
                    dialog.dismiss();
                    if (updateConfirmListener != null) {
                        updateConfirmListener.onUpdateConfirm(request);
                    }
                });
        currentDialog = builder.create();
        currentDialog.show();
    }

    /**
     * 构建更新描述文本
     */
    private String buildUpdateDescription(UpdateRequest request, int oldPages) {
        switch (request.mode) {
            case MODE_UPDATE_BACK:
                return context.getString(R.string.gallery_update_desc_back, oldPages);
            case MODE_UPDATE_FRONT:
                return context.getString(R.string.gallery_update_desc_front, oldPages);
            case MODE_FULL_REPLACE:
                return context.getString(R.string.gallery_update_desc_full);
            case MODE_CUSTOM:
                return context.getString(R.string.gallery_update_desc_custom,
                        request.customOldStart, request.customOldEnd,
                        request.customNewStart, request.customNewEnd);
            default:
                return "";
        }
    }

    private String getModeName(int mode) {
        switch (mode) {
            case MODE_UPDATE_BACK: return context.getString(R.string.gallery_update_mode_back);
            case MODE_UPDATE_FRONT: return context.getString(R.string.gallery_update_mode_front);
            case MODE_FULL_REPLACE: return context.getString(R.string.gallery_update_mode_full);
            case MODE_CUSTOM: return context.getString(R.string.gallery_update_mode_custom);
            default: return "";
        }
    }

    /**
     * 验证自定义模式的页数范围
     * @return 错误信息，null 表示验证通过
     */
    private String validateCustomRange(UpdateRequest request, int oldPages) {
        if (request.customOldStart <= 0 || request.customOldEnd < request.customOldStart) {
            return context.getString(R.string.gallery_update_error_invalid_old_range);
        }
        if (request.customNewStart <= 0 || request.customNewEnd < request.customNewStart) {
            return context.getString(R.string.gallery_update_error_invalid_new_range);
        }
        int oldCount = request.customOldEnd - request.customOldStart + 1;
        int newCount = request.customNewEnd - request.customNewStart + 1;
        if (oldCount != newCount) {
            return context.getString(R.string.gallery_update_error_page_mismatch, oldCount, newCount);
        }
        // 检查旧版页数范围是否超出实际页数
        if (oldPages > 0 && request.customOldEnd > oldPages) {
            return context.getString(R.string.gallery_update_error_old_exceeds, request.customOldEnd, oldPages);
        }
        return null;
    }

    private int parseIntSafe(String s, int defaultVal) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    public void dismiss() {
        if (currentDialog != null) {
            currentDialog.dismiss();
            currentDialog = null;
        }
    }
}
