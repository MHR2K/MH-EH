/*
 * Copyright 2026 Hippo Seven
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

package com.hippo.ehviewer.ui.dialog;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.stats.ReadingRecord;
import com.hippo.ehviewer.stats.StatsManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReadingRecordsDialog {

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static final int PAGE_SIZE = 5;

    private final Context mContext;
    private final long mGid;
    private final String mTitle;
    private AlertDialog mDialog;

    private TextView mGalleryTitleView;
    private TextView mSummaryTextView;
    private View mBottomDivider;
    private TextView mOperationTipView;
    private TextView mEmptyTipView;
    private RecyclerView mRecyclerView;
    private RecordAdapter mAdapter;

    private View mPaginationPanel;
    private TextView mBtnPrevPage;
    private TextView mPageIndicatorView;
    private TextView mBtnNextPage;
    private int mCurrentPage = 0;

    private final List<ReadingRecord> mRecords = new ArrayList<>();
    private final SimpleDateFormat mTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private final SimpleDateFormat mClockFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    public static void show(@NonNull Context context, @NonNull GalleryInfo info) {
        String title = EhUtils.getSuitableTitle(info);
        show(context, info.gid, title);
    }

    public static void show(@NonNull Context context, long gid, @Nullable String title) {
        new ReadingRecordsDialog(context, gid, title).show();
    }

    private ReadingRecordsDialog(@NonNull Context context, long gid, @Nullable String title) {
        mContext = context;
        mGid = gid;
        mTitle = (title != null && !title.isEmpty()) ? title : ("GID: " + gid);
    }

    private void show() {
        LayoutInflater inflater = LayoutInflater.from(mContext);
        View dialogView = inflater.inflate(R.layout.dialog_reading_records, null);

        mGalleryTitleView = dialogView.findViewById(R.id.gallery_title);
        mSummaryTextView = dialogView.findViewById(R.id.summary_text);
        mBottomDivider = dialogView.findViewById(R.id.bottom_divider);
        mOperationTipView = dialogView.findViewById(R.id.operation_tip);
        mEmptyTipView = dialogView.findViewById(R.id.empty_tip);
        mRecyclerView = dialogView.findViewById(R.id.recycler_view);

        mPaginationPanel = dialogView.findViewById(R.id.pagination_panel);
        mBtnPrevPage = dialogView.findViewById(R.id.btn_prev_page);
        mPageIndicatorView = dialogView.findViewById(R.id.page_indicator);
        mBtnNextPage = dialogView.findViewById(R.id.btn_next_page);

        mBtnPrevPage.setOnClickListener(v -> {
            if (mCurrentPage > 0) {
                mCurrentPage--;
                updatePaginationUI();
                mAdapter.notifyDataSetChanged();
                mRecyclerView.scrollToPosition(0);
            }
        });

        mBtnNextPage.setOnClickListener(v -> {
            int totalPages = getTotalPages();
            if (mCurrentPage < totalPages - 1) {
                mCurrentPage++;
                updatePaginationUI();
                mAdapter.notifyDataSetChanged();
                mRecyclerView.scrollToPosition(0);
            }
        });

        mGalleryTitleView.setText(mTitle);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        mAdapter = new RecordAdapter();
        mRecyclerView.setAdapter(mAdapter);

        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        builder.setTitle(R.string.reading_records_dialog_title);
        builder.setView(dialogView);
        builder.setPositiveButton(android.R.string.ok, null);
        builder.setNeutralButton(R.string.reading_records_clear_all, (dialog, which) -> showClearConfirmDialog());

        mDialog = builder.create();
        mDialog.show();

        loadData();
    }

    private void loadData() {
        sExecutor.execute(() -> {
            StatsManager sm = StatsManager.getInstance(mContext);
            List<ReadingRecord> list = sm.getReadingRecords(mGid);
            sMainHandler.post(() -> {
                if (mDialog == null || !mDialog.isShowing()) {
                    return;
                }
                mRecords.clear();
                if (list != null) {
                    mRecords.addAll(list);
                }
                updateUI();
            });
        });
    }

    private int getTotalPages() {
        return (mRecords.size() + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    private void updatePaginationUI() {
        int totalPages = getTotalPages();
        if (totalPages <= 1) {
            mPaginationPanel.setVisibility(View.GONE);
            return;
        }
        mPaginationPanel.setVisibility(View.VISIBLE);
        mPageIndicatorView.setText(mContext.getString(R.string.reading_records_page_format, mCurrentPage + 1, totalPages));

        boolean canPrev = mCurrentPage > 0;
        mBtnPrevPage.setEnabled(canPrev);
        mBtnPrevPage.setAlpha(canPrev ? 1.0f : 0.35f);

        boolean canNext = mCurrentPage < totalPages - 1;
        mBtnNextPage.setEnabled(canNext);
        mBtnNextPage.setAlpha(canNext ? 1.0f : 0.35f);
    }

    private void updateUI() {
        if (mRecords.isEmpty()) {
            mEmptyTipView.setVisibility(View.VISIBLE);
            mRecyclerView.setVisibility(View.GONE);
            mPaginationPanel.setVisibility(View.GONE);
            if (mBottomDivider != null) {
                mBottomDivider.setVisibility(View.GONE);
            }
            mOperationTipView.setVisibility(View.GONE);
            mSummaryTextView.setText(R.string.reading_records_empty);
            if (mDialog != null && mDialog.getButton(DialogInterface.BUTTON_NEUTRAL) != null) {
                mDialog.getButton(DialogInterface.BUTTON_NEUTRAL).setVisibility(View.GONE);
            }
        } else {
            mEmptyTipView.setVisibility(View.GONE);
            mRecyclerView.setVisibility(View.VISIBLE);
            if (mBottomDivider != null) {
                mBottomDivider.setVisibility(View.VISIBLE);
            }
            mOperationTipView.setVisibility(View.VISIBLE);
            if (mDialog != null && mDialog.getButton(DialogInterface.BUTTON_NEUTRAL) != null) {
                mDialog.getButton(DialogInterface.BUTTON_NEUTRAL).setVisibility(View.VISIBLE);
            }

            int count = mRecords.size();
            long totalDuration = 0;
            for (ReadingRecord r : mRecords) {
                totalDuration += r.durationSeconds;
            }
            long firstTime = mRecords.get(mRecords.size() - 1).startTime;

            String summaryPart1 = mContext.getString(R.string.reading_records_summary, count, formatDetailedDuration(totalDuration));
            String summaryPart2 = mContext.getString(R.string.reading_records_first_read, mDateFormat.format(new Date(firstTime)));
            mSummaryTextView.setText(summaryPart1 + " · " + summaryPart2);

            int totalPages = getTotalPages();
            if (mCurrentPage >= totalPages) {
                mCurrentPage = Math.max(0, totalPages - 1);
            }
            updatePaginationUI();
            mAdapter.notifyDataSetChanged();
        }
    }

    private void showClearConfirmDialog() {
        new AlertDialog.Builder(mContext)
                .setTitle(R.string.reading_records_clear_all)
                .setMessage(R.string.reading_records_clear_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    sExecutor.execute(() -> {
                        StatsManager.getInstance(mContext).clearReadingRecords(mGid);
                        sMainHandler.post(this::loadData);
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDeleteSingleRecordDialog(ReadingRecord record) {
        new AlertDialog.Builder(mContext)
                .setTitle(R.string.reading_records_delete)
                .setMessage(mTimeFormat.format(new Date(record.startTime)) + "\n" +
                        formatDetailedDuration(record.durationSeconds))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    sExecutor.execute(() -> {
                        StatsManager.getInstance(mContext).deleteReadingRecord(record.id);
                        sMainHandler.post(this::loadData);
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public static String formatDetailedDuration(long totalSeconds) {
        if (totalSeconds <= 0) {
            return "< 1s";
        }
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        } else if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        } else {
            return seconds + "s";
        }
    }

    private class RecordHolder extends RecyclerView.ViewHolder {
        final TextView date;
        final TextView time;
        final TextView badge;
        final TextView progress;
        final TextView duration;

        RecordHolder(View itemView) {
            super(itemView);
            date = itemView.findViewById(R.id.record_date);
            time = itemView.findViewById(R.id.record_time);
            badge = itemView.findViewById(R.id.record_badge);
            progress = itemView.findViewById(R.id.record_progress);
            duration = itemView.findViewById(R.id.record_duration);

            itemView.setOnLongClickListener(v -> {
                int pos = getAdapterPosition();
                int realIndex = mCurrentPage * PAGE_SIZE + pos;
                if (pos >= 0 && realIndex < mRecords.size()) {
                    showDeleteSingleRecordDialog(mRecords.get(realIndex));
                    return true;
                }
                return false;
            });
        }
    }

    private class RecordAdapter extends RecyclerView.Adapter<RecordHolder> {

        @NonNull
        @Override
        public RecordHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_reading_record, parent, false);
            return new RecordHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecordHolder holder, int position) {
            int realIndex = mCurrentPage * PAGE_SIZE + position;
            ReadingRecord record = mRecords.get(realIndex);

            Date startDate = new Date(record.startTime);
            holder.date.setText(mDateFormat.format(startDate));
            holder.time.setText(mClockFormat.format(startDate));

            if (record.completed) {
                holder.badge.setVisibility(View.VISIBLE);
            } else {
                holder.badge.setVisibility(View.GONE);
            }

            if (record.totalPages > 0) {
                holder.progress.setText(mContext.getString(R.string.reading_records_progress,
                        record.pageStart, record.pageEnd, record.totalPages));
            } else {
                holder.progress.setText(mContext.getString(R.string.reading_records_duration,
                        "P. " + record.pageStart + " - " + record.pageEnd));
            }

            holder.duration.setText(mContext.getString(R.string.reading_records_duration,
                    formatDetailedDuration(record.durationSeconds)));
        }

        @Override
        public int getItemCount() {
            int total = mRecords.size();
            int start = mCurrentPage * PAGE_SIZE;
            if (start >= total) {
                return 0;
            }
            return Math.min(PAGE_SIZE, total - start);
        }
    }
}

