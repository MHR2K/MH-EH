package com.hippo.ehviewer.ui.dialog;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;

import java.util.HashSet;
import java.util.Set;

public class DownloadFilterDialog implements DialogInterface.OnClickListener {

    public interface FilterCallback {
        void onFilterApplied(Set<Integer> statusFilters, Set<Integer> progressFilters);
    }

    private static final int STATUS_DONE = 1;
    private static final int STATUS_NOT_STARTED = 2;
    private static final int STATUS_WAITING = 3;
    private static final int STATUS_DOWNLOADING = 4;
    private static final int STATUS_FAILED = 5;

    private static final int PROGRESS_NOT_STARTED = 10;
    private static final int PROGRESS_IN_PROGRESS = 11;
    private static final int PROGRESS_FINISHED = 12;

    private final Context context;
    private final Set<Integer> initialStatusFilters;
    private final Set<Integer> initialProgressFilters;
    private final FilterCallback callback;

    private AlertDialog dialog;
    private CheckBox cbStatusDone;
    private CheckBox cbStatusNotStarted;
    private CheckBox cbStatusWaiting;
    private CheckBox cbStatusDownloading;
    private CheckBox cbStatusFailed;
    private CheckBox cbProgressNotStarted;
    private CheckBox cbProgressInProgress;
    private CheckBox cbProgressFinished;

    public DownloadFilterDialog(Context context,
                                 Set<Integer> statusFilters,
                                 Set<Integer> progressFilters,
                                 FilterCallback callback) {
        this.context = context;
        this.initialStatusFilters = statusFilters != null ? new HashSet<>(statusFilters) : new HashSet<>();
        this.initialProgressFilters = progressFilters != null ? new HashSet<>(progressFilters) : new HashSet<>();
        this.callback = callback;
    }

    public void show() {
        View contentView = createContentView();
        dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.download_combined_filter)
                .setView(contentView)
                .setPositiveButton(R.string.filter_apply, this)
                .setNegativeButton(R.string.filter_cancel, null)
                .setNeutralButton(R.string.filter_reset, (dialog, which) -> resetFilters())
                .show();
    }

    private View createContentView() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(
                (int) (24 * context.getResources().getDisplayMetrics().density),
                (int) (16 * context.getResources().getDisplayMetrics().density),
                (int) (24 * context.getResources().getDisplayMetrics().density),
                (int) (16 * context.getResources().getDisplayMetrics().density)
        );

        TextView statusTitle = new TextView(context);
        statusTitle.setText(R.string.filter_status_title);
        statusTitle.setTextSize(16);
        statusTitle.setTextColor(context.getColor(android.R.color.primary_text_dark));
        statusTitle.setPadding(0, 0, 0, (int) (8 * context.getResources().getDisplayMetrics().density));
        layout.addView(statusTitle);

        cbStatusDone = createCheckBox(R.string.download_state_downloaded);
        cbStatusNotStarted = createCheckBox(R.string.download_state_none);
        cbStatusWaiting = createCheckBox(R.string.download_state_wait);
        cbStatusDownloading = createCheckBox(R.string.download_state_downloading);
        cbStatusFailed = createCheckBox(R.string.download_state_failed);

        LinearLayout statusGroup = new LinearLayout(context);
        statusGroup.setOrientation(LinearLayout.VERTICAL);
        statusGroup.addView(cbStatusDone);
        statusGroup.addView(cbStatusNotStarted);
        statusGroup.addView(cbStatusWaiting);
        statusGroup.addView(cbStatusDownloading);
        statusGroup.addView(cbStatusFailed);
        layout.addView(statusGroup);

        View divider = new View(context);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (1 * context.getResources().getDisplayMetrics().density)
        );
        dividerParams.setMargins(0, (int) (16 * context.getResources().getDisplayMetrics().density), 0, (int) (16 * context.getResources().getDisplayMetrics().density));
        divider.setLayoutParams(dividerParams);
        divider.setBackgroundColor(context.getColor(android.R.color.darker_gray));
        layout.addView(divider);

        TextView progressTitle = new TextView(context);
        progressTitle.setText(R.string.filter_progress_title);
        progressTitle.setTextSize(16);
        progressTitle.setTextColor(context.getColor(android.R.color.primary_text_dark));
        progressTitle.setPadding(0, 0, 0, (int) (8 * context.getResources().getDisplayMetrics().density));
        layout.addView(progressTitle);

        cbProgressNotStarted = createCheckBox(R.string.download_progress_not_started);
        cbProgressInProgress = createCheckBox(R.string.download_progress_in_progress);
        cbProgressFinished = createCheckBox(R.string.download_progress_finished);

        LinearLayout progressGroup = new LinearLayout(context);
        progressGroup.setOrientation(LinearLayout.VERTICAL);
        progressGroup.addView(cbProgressNotStarted);
        progressGroup.addView(cbProgressInProgress);
        progressGroup.addView(cbProgressFinished);
        layout.addView(progressGroup);

        restoreState();

        return layout;
    }

    private CheckBox createCheckBox(int textResId) {
        CheckBox checkBox = new CheckBox(context);
        checkBox.setText(textResId);
        checkBox.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        checkBox.setPadding(
                (int) (8 * context.getResources().getDisplayMetrics().density),
                (int) (4 * context.getResources().getDisplayMetrics().density),
                (int) (8 * context.getResources().getDisplayMetrics().density),
                (int) (4 * context.getResources().getDisplayMetrics().density)
        );
        return checkBox;
    }

    private void restoreState() {
        cbStatusDone.setChecked(initialStatusFilters.contains(STATUS_DONE));
        cbStatusNotStarted.setChecked(initialStatusFilters.contains(STATUS_NOT_STARTED));
        cbStatusWaiting.setChecked(initialStatusFilters.contains(STATUS_WAITING));
        cbStatusDownloading.setChecked(initialStatusFilters.contains(STATUS_DOWNLOADING));
        cbStatusFailed.setChecked(initialStatusFilters.contains(STATUS_FAILED));

        cbProgressNotStarted.setChecked(initialProgressFilters.contains(PROGRESS_NOT_STARTED));
        cbProgressInProgress.setChecked(initialProgressFilters.contains(PROGRESS_IN_PROGRESS));
        cbProgressFinished.setChecked(initialProgressFilters.contains(PROGRESS_FINISHED));
    }

    private void resetFilters() {
        cbStatusDone.setChecked(false);
        cbStatusNotStarted.setChecked(false);
        cbStatusWaiting.setChecked(false);
        cbStatusDownloading.setChecked(false);
        cbStatusFailed.setChecked(false);

        cbProgressNotStarted.setChecked(false);
        cbProgressInProgress.setChecked(false);
        cbProgressFinished.setChecked(false);
    }

    private Set<Integer> getSelectedStatusFilters() {
        Set<Integer> filters = new HashSet<>();
        if (cbStatusDone.isChecked()) filters.add(STATUS_DONE);
        if (cbStatusNotStarted.isChecked()) filters.add(STATUS_NOT_STARTED);
        if (cbStatusWaiting.isChecked()) filters.add(STATUS_WAITING);
        if (cbStatusDownloading.isChecked()) filters.add(STATUS_DOWNLOADING);
        if (cbStatusFailed.isChecked()) filters.add(STATUS_FAILED);
        return filters;
    }

    private Set<Integer> getSelectedProgressFilters() {
        Set<Integer> filters = new HashSet<>();
        if (cbProgressNotStarted.isChecked()) filters.add(PROGRESS_NOT_STARTED);
        if (cbProgressInProgress.isChecked()) filters.add(PROGRESS_IN_PROGRESS);
        if (cbProgressFinished.isChecked()) filters.add(PROGRESS_FINISHED);
        return filters;
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (callback != null) {
            callback.onFilterApplied(getSelectedStatusFilters(), getSelectedProgressFilters());
        }
    }

    public static int mapStatusToDownloadState(int filterStatus) {
        switch (filterStatus) {
            case STATUS_DONE:
                return 3;
            case STATUS_NOT_STARTED:
                return 0;
            case STATUS_WAITING:
                return 1;
            case STATUS_DOWNLOADING:
                return 2;
            case STATUS_FAILED:
                return 4;
            default:
                return -1;
        }
    }

    public static boolean matchesProgressFilter(int filterProgress, int startPage, int pages, boolean hasSpiderInfo) {
        switch (filterProgress) {
            case PROGRESS_NOT_STARTED:
                return !hasSpiderInfo || startPage == 0;
            case PROGRESS_IN_PROGRESS:
                return hasSpiderInfo && startPage > 0 && pages > 0 && startPage < pages - 1;
            case PROGRESS_FINISHED:
                return hasSpiderInfo && pages > 0 && startPage >= pages - 1;
            default:
                return false;
        }
    }
}
