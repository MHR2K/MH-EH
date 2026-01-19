package com.hippo.ehviewer.widget;

import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.hippo.ehviewer.R;

public class ProgressHelper {

    private static AlertDialog dialog = null;
    private static TextView tvText = null;
    private static String progressMessage = "";

    public static void showDialog(Context context, String message) {
        if (dialog == null) {
            int llPadding = 30;
            LinearLayout ll = new LinearLayout(context);
            ll.setOrientation(LinearLayout.HORIZONTAL);
            ll.setPadding(llPadding, llPadding, llPadding, llPadding);
            ll.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams llParam = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            llParam.gravity = Gravity.CENTER;
            ll.setLayoutParams(llParam);

            ProgressBar progressBar = new ProgressBar(context);
            progressBar.setIndeterminate(true);
            progressBar.setPadding(0, 0, llPadding, 0);
            progressBar.setLayoutParams(llParam);

            llParam = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            llParam.gravity = Gravity.CENTER;
            tvText = new TextView(context);
            progressMessage = message;
            tvText.setText(progressMessage);
            tvText.setTextColor(context.getColor(R.color.primary_drawable_light));
            tvText.setTextSize(20);
            tvText.setLayoutParams(llParam);

            ll.addView(progressBar);
            ll.addView(tvText);

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setCancelable(false);
            builder.setView(ll);

            dialog = builder.create();
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
                layoutParams.copyFrom(dialog.getWindow().getAttributes());
                layoutParams.width = LinearLayout.LayoutParams.WRAP_CONTENT;
                layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT;
                dialog.getWindow().setAttributes(layoutParams);
            }
        }
    }

    public static boolean setProgress(int progress) {
        if (tvText == null) {
            return false;
        }
        // 保持向后兼容：无具体计数时只显示百分比和保留消息
        String text = progress + "% " + progressMessage;
        tvText.setText(text);
        return true;
    }

    // 新增重载：显示百分比与当前/总数格式
    // 当 total = 0 时显示 "已扫描 N 个目录"（无需预知总数）
    public static boolean setProgress(int progress, int current, int total) {
        if (tvText == null) {
            return false;
        }
        String msg = (progressMessage != null && !progressMessage.isEmpty()) ? progressMessage : "扫描中 ···";
        String text;
        if (total > 0) {
            text = String.format("%d%% (%d/%d) %s", progress, current, total, msg);
        } else if (current > 0) {
            // 无总数时显示已扫描数量
            text = String.format("已扫描 %d 个目录", current);
        } else {
            text = msg;
        }
        tvText.setText(text);
        return true;
    }

    // 设置/更新正在做的事情文本（可在扫描内部阶段更新）
    public static void setMessage(String message) {
        progressMessage = message == null ? "" : message;
        if (tvText != null) {
            tvText.setText(progressMessage);
        }
    }

    public static boolean isDialogVisible() {
        if (dialog != null) {
            return dialog.isShowing();
        } else {
            return false;
        }
    }

    public static void dismissDialog() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        if (tvText != null) {
            tvText = null;
        }
    }
}
