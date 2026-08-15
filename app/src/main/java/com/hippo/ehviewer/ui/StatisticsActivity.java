package com.hippo.ehviewer.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.stats.DailyStats;
import com.hippo.ehviewer.stats.StatsManager;
import com.hippo.ehviewer.widget.StatsChartView;

import java.util.ArrayList;
import java.util.List;

public class StatisticsActivity extends ToolbarActivity {

    private static final int METRIC_ALL = 0;
    private static final int METRIC_READ = 1;
    private static final int METRIC_DOWNLOAD_ADDED = 2;
    private static final int METRIC_DOWNLOAD_COMPLETED = 3;

    private TextView mTodayTotalTime;
    private TextView mTodayReadTime;
    private TextView mTodayDownloadTime;
    private TextView mTodayReadCount;
    private TextView mTodayDownloadAdded;
    private TextView mTodayDownloadCompleted;
    private StatsChartView mChartView;
    private RecyclerView mRecyclerView;
    private View mTip;
    private Spinner mSpinnerMetric;

    private StatsManager mStatsManager;
    private int mCurrentMetric = METRIC_ALL;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_statistics);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);

        mStatsManager = StatsManager.getInstance(this);

        mTodayTotalTime = findViewById(R.id.today_total_time);
        mTodayReadTime = findViewById(R.id.today_read_time);
        mTodayDownloadTime = findViewById(R.id.today_download_time);
        mTodayReadCount = findViewById(R.id.today_read_count);
        mTodayDownloadAdded = findViewById(R.id.today_download_added);
        mTodayDownloadCompleted = findViewById(R.id.today_download_completed);
        mChartView = findViewById(R.id.chart_view);
        mRecyclerView = findViewById(R.id.recycler_view);
        mTip = findViewById(R.id.tip);
        mSpinnerMetric = findViewById(R.id.spinner_metric);

        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        setupMetricSpinner();
    }

    private void setupMetricSpinner() {
        String[] metrics = {
                getString(R.string.statistics_all_metrics),
                getString(R.string.statistics_read_count),
                getString(R.string.statistics_download_added),
                getString(R.string.statistics_download_completed)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, metrics);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpinnerMetric.setAdapter(adapter);
        mSpinnerMetric.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mCurrentMetric != position) {
                    mCurrentMetric = position;
                    updateChart();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    private void loadData() {
        // 今日统计
        DailyStats today = mStatsManager.getTodayStats();
        if (today != null) {
            mTodayReadCount.setText(String.valueOf(today.readCount));
            mTodayDownloadAdded.setText(String.valueOf(today.downloadAdded));
            mTodayDownloadCompleted.setText(String.valueOf(today.downloadCompleted));
            mTodayTotalTime.setText(StatsManager.formatDuration(today.totalTimeSeconds));
            mTodayReadTime.setText(StatsManager.formatDuration(today.readTimeSeconds));
            long downloadTimeSeconds = today.totalTimeSeconds - today.readTimeSeconds;
            mTodayDownloadTime.setText(StatsManager.formatDuration(Math.max(0, downloadTimeSeconds)));
        } else {
            mTodayReadCount.setText("0");
            mTodayDownloadAdded.setText("0");
            mTodayDownloadCompleted.setText("0");
            mTodayTotalTime.setText("0m");
            mTodayReadTime.setText("0m");
            mTodayDownloadTime.setText("0m");
        }

        // 更新图表
        updateChart();

        // 所有历史数据（列表）
        List<DailyStats> allStats = mStatsManager.getAllStats();
        if (allStats.isEmpty()) {
            mRecyclerView.setVisibility(View.GONE);
            mTip.setVisibility(View.VISIBLE);
        } else {
            mRecyclerView.setVisibility(View.VISIBLE);
            mTip.setVisibility(View.GONE);
            mRecyclerView.setAdapter(new StatsAdapter(allStats));
        }
    }

    private void updateChart() {
        List<DailyStats> allStats = mStatsManager.getAllStats();

        // 取最近7天
        List<DailyStats> recent7 = allStats.size() > 7 ? allStats.subList(0, 7) : allStats;

        if (mCurrentMetric == METRIC_ALL) {
            // 三项全部显示
            mChartView.setAllMetricsData(recent7);
        } else {
            // 提取单项数据
            List<Integer> values = new ArrayList<>();
            for (DailyStats stat : recent7) {
                switch (mCurrentMetric) {
                    case METRIC_READ:
                        values.add(stat.readCount);
                        break;
                    case METRIC_DOWNLOAD_ADDED:
                        values.add(stat.downloadAdded);
                        break;
                    case METRIC_DOWNLOAD_COMPLETED:
                        values.add(stat.downloadCompleted);
                        break;
                }
            }

            // 提取日期
            List<String> dates = new ArrayList<>();
            for (DailyStats stat : recent7) {
                dates.add(stat.date);
            }

            mChartView.setSingleMetricData(dates, values);
        }
    }

    private static class StatsAdapter extends RecyclerView.Adapter<StatsAdapter.ViewHolder> {

        private final List<DailyStats> mData;

        StatsAdapter(List<DailyStats> data) {
            mData = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_stats_history, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            DailyStats stats = mData.get(position);
            holder.date.setText(stats.date);
            holder.readCount.setText(String.valueOf(stats.readCount));
            holder.downloadAdded.setText(String.valueOf(stats.downloadAdded));
            holder.downloadCompleted.setText(String.valueOf(stats.downloadCompleted));

            // 时间统计
            holder.totalTime.setText(StatsManager.formatDuration(stats.totalTimeSeconds));
            holder.readTime.setText(StatsManager.formatDuration(stats.readTimeSeconds));
            long downloadTimeSeconds = stats.totalTimeSeconds - stats.readTimeSeconds;
            holder.downloadTime.setText(StatsManager.formatDuration(Math.max(0, downloadTimeSeconds)));
        }

        @Override
        public int getItemCount() {
            return mData.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView date;
            TextView readCount;
            TextView downloadAdded;
            TextView downloadCompleted;
            TextView totalTime;
            TextView readTime;
            TextView downloadTime;

            ViewHolder(View view) {
                super(view);
                date = view.findViewById(R.id.stats_date);
                readCount = view.findViewById(R.id.stats_read_count);
                downloadAdded = view.findViewById(R.id.stats_download_added);
                downloadCompleted = view.findViewById(R.id.stats_download_completed);
                totalTime = view.findViewById(R.id.stats_total_time);
                readTime = view.findViewById(R.id.stats_read_time);
                downloadTime = view.findViewById(R.id.stats_download_time);
            }
        }
    }
}
