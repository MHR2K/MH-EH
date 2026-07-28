package com.hippo.ehviewer.ui.scene.gallery.detail;

import static com.hippo.ehviewer.util.ClipboardUtil.createAnnouncerFromClipboardUrl;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.NewVersion;
import com.hippo.scene.Announcer;

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
        // 自定义模式的页数范围
        public int customOldStart = 1;
        public int customOldEnd = 1;
        public int customNewStart = 1;
        public int customNewEnd = 1;

        public UpdateRequest(int mode, NewVersion newVersion) {
            this.mode = mode;
            this.newVersion = newVersion;
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

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.gallery_update_mode_title)
                .setView(dialogView)
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss())
                .setPositiveButton(R.string.gallery_update_next, (dialog, which) -> {
                    int mode = getSelectedMode(modeGroup);
                    UpdateRequest request = new UpdateRequest(mode, newVersion);
                    if (mode == MODE_CUSTOM) {
                        request.customOldStart = parseIntSafe(etOldStart.getText().toString(), 1);
                        request.customOldEnd = parseIntSafe(etOldEnd.getText().toString(), 1);
                        request.customNewStart = parseIntSafe(etNewStart.getText().toString(), 1);
                        request.customNewEnd = parseIntSafe(etNewEnd.getText().toString(), 1);
                        // 验证自定义模式的页数范围
                        String error = validateCustomRange(request);
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
                    showConfirmDialog(request);
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
    private void showConfirmDialog(UpdateRequest request) {
        if (currentDialog != null) {
            currentDialog.dismiss();
        }
        if (galleryDetail == null) return;

        int oldPages = galleryDetail.pages;
        String modeName = getModeName(request.mode);
        String desc = buildUpdateDescription(request, oldPages);

        String message = context.getString(R.string.gallery_update_confirm_message,
                galleryDetail.gid, EhUtils.getSuitableTitle(galleryDetail), oldPages,
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
    private String validateCustomRange(UpdateRequest request) {
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
        if (galleryDetail != null && request.customOldEnd > galleryDetail.pages) {
            return context.getString(R.string.gallery_update_error_old_exceeds, request.customOldEnd, galleryDetail.pages);
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
