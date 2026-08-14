package com.hippo.ehviewer.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.stats.DailyStats;

import java.util.List;

/**
 * 折线图控件，展示最近7天的统计数据
 */
public class StatsChartView extends View {

    private Paint mPaintText;
    private Paint mPaintLine;
    private Paint mPaintRead;
    private Paint mPaintDownloadAdded;
    private Paint mPaintDownloadCompleted;
    private Paint mPaintAxis;
    private Paint mPaintDot;

    private List<String> mDates;
    private List<Integer> mValues;
    private List<DailyStats> mAllStats;
    private boolean mIsAllMetricsMode = false;
    private int mMaxValue = 1;

    private float mTextSizeSmall;
    private float mTextSizeLarge;
    private float mPaddingLeft;
    private float mPaddingBottom;
    private float mPaddingTop;
    private float mPaddingRight;

    public StatsChartView(Context context) {
        super(context);
        init();
    }

    public StatsChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public StatsChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private void init() {
        mTextSizeSmall = dpToPx(9);
        mTextSizeLarge = dpToPx(14);
        mPaddingLeft = dpToPx(30);
        mPaddingBottom = dpToPx(30);
        mPaddingTop = dpToPx(20);
        mPaddingRight = dpToPx(15);

        int textColor = Color.DKGRAY;
        int[] attrs = {android.R.attr.textColorPrimary};
        android.content.res.TypedArray ta = getContext().obtainStyledAttributes(attrs);
        try {
            textColor = ta.getColor(0, Color.DKGRAY);
        } finally {
            ta.recycle();
        }

        mPaintText = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaintText.setColor(textColor);
        mPaintText.setTextSize(mTextSizeSmall);
        mPaintText.setTextAlign(Paint.Align.CENTER);

        mPaintLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaintLine.setColor(0xFF4CAF50);
        mPaintLine.setStyle(Paint.Style.STROKE);
        mPaintLine.setStrokeWidth(dpToPx(2));

        mPaintRead = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaintRead.setColor(0xFF4CAF50);
        mPaintRead.setStyle(Paint.Style.STROKE);
        mPaintRead.setStrokeWidth(dpToPx(2));

        mPaintDownloadAdded = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaintDownloadAdded.setColor(0xFF2196F3);
        mPaintDownloadAdded.setStyle(Paint.Style.STROKE);
        mPaintDownloadAdded.setStrokeWidth(dpToPx(2));

        mPaintDownloadCompleted = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaintDownloadCompleted.setColor(0xFFFF9800);
        mPaintDownloadCompleted.setStyle(Paint.Style.STROKE);
        mPaintDownloadCompleted.setStrokeWidth(dpToPx(2));

        mPaintDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaintDot.setStyle(Paint.Style.FILL);

        mPaintAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaintAxis.setColor(textColor);
        mPaintAxis.setStrokeWidth(dpToPx(0.5f));
        mPaintAxis.setAlpha(80);
    }

    public void setSingleMetricData(List<String> dates, List<Integer> values) {
        mDates = dates;
        mValues = values;
        mAllStats = null;
        mIsAllMetricsMode = false;
        mMaxValue = 1;
        if (values != null) {
            for (int v : values) {
                mMaxValue = Math.max(mMaxValue, v);
            }
        }
        invalidate();
    }

    public void setAllMetricsData(List<DailyStats> stats) {
        mAllStats = stats;
        mIsAllMetricsMode = true;
        mDates = null;
        mValues = null;
        mMaxValue = 1;
        if (stats != null) {
            for (DailyStats s : stats) {
                mMaxValue = Math.max(mMaxValue, s.readCount);
                mMaxValue = Math.max(mMaxValue, s.downloadAdded);
                mMaxValue = Math.max(mMaxValue, s.downloadCompleted);
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        boolean hasData = mIsAllMetricsMode ? (mAllStats != null && !mAllStats.isEmpty()) : (mValues != null && !mValues.isEmpty());
        if (!hasData) {
            mPaintText.setTextSize(mTextSizeLarge);
            canvas.drawText("暂无数据", getWidth() / 2f, getHeight() / 2f, mPaintText);
            return;
        }

        int width = getWidth();
        int height = getHeight();
        float chartWidth = width - mPaddingLeft - mPaddingRight;
        float chartHeight = height - mPaddingTop - mPaddingBottom;

        // 画坐标轴
        canvas.drawLine(mPaddingLeft, mPaddingTop, mPaddingLeft, height - mPaddingBottom, mPaintAxis);
        canvas.drawLine(mPaddingLeft, height - mPaddingBottom, width - mPaddingRight, height - mPaddingBottom, mPaintAxis);

        // 画网格线
        int gridLines = 4;
        for (int i = 1; i <= gridLines; i++) {
            float y = mPaddingTop + chartHeight * i / gridLines;
            canvas.drawLine(mPaddingLeft, y, width - mPaddingRight, y, mPaintAxis);
        }

        if (mIsAllMetricsMode) {
            drawAllMetricsLine(canvas, width, height, chartWidth, chartHeight);
        } else {
            drawSingleMetricLine(canvas, width, height, chartWidth, chartHeight);
        }
    }

    private void drawSingleMetricLine(Canvas canvas, int width, int height, float chartWidth, float chartHeight) {
        int count = mValues.size();
        if (count == 0) return;

        float pointSpacing = count > 1 ? chartWidth / (count - 1) : 0;
        Path path = new Path();
        boolean first = true;

        for (int i = 0; i < count; i++) {
            int value = mValues.get(count - 1 - i);
            float x = mPaddingLeft + i * pointSpacing;
            float y = height - mPaddingBottom - (value / (float) mMaxValue) * chartHeight;

            // 画点
            mPaintDot.setColor(0xFF4CAF50);
            canvas.drawCircle(x, y, dpToPx(3), mPaintDot);

            // 画数值
            if (value > 0) {
                mPaintText.setTextSize(mTextSizeSmall);
                canvas.drawText(String.valueOf(value), x, y - dpToPx(6), mPaintText);
            }

            // 连线
            if (first) {
                path.moveTo(x, y);
                first = false;
            } else {
                path.lineTo(x, y);
            }

            // 日期标签
            String dateLabel = mDates.get(count - 1 - i);
            if (dateLabel != null && dateLabel.length() > 5) {
                dateLabel = dateLabel.substring(5);
            }
            mPaintText.setTextSize(mTextSizeSmall);
            canvas.drawText(dateLabel, x, height - mPaddingBottom + dpToPx(14), mPaintText);
        }

        mPaintLine.setColor(0xFF4CAF50);
        canvas.drawPath(path, mPaintLine);
    }

    private void drawAllMetricsLine(Canvas canvas, int width, int height, float chartWidth, float chartHeight) {
        int count = mAllStats.size();
        if (count == 0) return;

        float pointSpacing = count > 1 ? chartWidth / (count - 1) : 0;

        // 图例
        float legendY = dpToPx(12);
        float legendX = mPaddingLeft;
        float legendSize = dpToPx(6);
        mPaintText.setTextSize(mTextSizeSmall);
        mPaintText.setTextAlign(Paint.Align.LEFT);

        String[] labels = {"阅读", "添加下载", "下载完成"};
        int[] colors = {0xFF4CAF50, 0xFF2196F3, 0xFFFF9800};
        float legendSpacing = chartWidth / 3;
        for (int i = 0; i < 3; i++) {
            float x = legendX + i * legendSpacing;
            mPaintDot.setColor(colors[i]);
            canvas.drawCircle(x + legendSize / 2, legendY - legendSize / 2, legendSize / 2, mPaintDot);
            canvas.drawText(labels[i], x + legendSize + dpToPx(3), legendY, mPaintText);
        }
        mPaintText.setTextAlign(Paint.Align.CENTER);

        // 画三条折线
        drawLine(canvas, count, pointSpacing, height, chartHeight, stat -> stat.readCount, mPaintRead, 0xFF4CAF50);
        drawLine(canvas, count, pointSpacing, height, chartHeight, stat -> stat.downloadAdded, mPaintDownloadAdded, 0xFF2196F3);
        drawLine(canvas, count, pointSpacing, height, chartHeight, stat -> stat.downloadCompleted, mPaintDownloadCompleted, 0xFFFF9800);

        // 日期标签
        for (int i = 0; i < count; i++) {
            float x = mPaddingLeft + i * pointSpacing;
            String dateLabel = mAllStats.get(count - 1 - i).date;
            if (dateLabel != null && dateLabel.length() > 5) {
                dateLabel = dateLabel.substring(5);
            }
            mPaintText.setTextSize(mTextSizeSmall);
            canvas.drawText(dateLabel, x, height - mPaddingBottom + dpToPx(14), mPaintText);
        }
    }

    private interface ValueExtractor {
        int getValue(DailyStats stat);
    }

    private void drawLine(Canvas canvas, int count, float pointSpacing, int height, float chartHeight,
                          ValueExtractor extractor, Paint linePaint, int dotColor) {
        Path path = new Path();
        boolean first = true;

        for (int i = 0; i < count; i++) {
            DailyStats stat = mAllStats.get(count - 1 - i);
            int value = extractor.getValue(stat);
            float x = mPaddingLeft + i * pointSpacing;
            float y = height - mPaddingBottom - (value / (float) mMaxValue) * chartHeight;

            // 画点
            mPaintDot.setColor(dotColor);
            canvas.drawCircle(x, y, dpToPx(3), mPaintDot);

            // 画数值
            if (value > 0) {
                mPaintText.setTextSize(mTextSizeSmall);
                canvas.drawText(String.valueOf(value), x, y - dpToPx(6), mPaintText);
            }

            if (first) {
                path.moveTo(x, y);
                first = false;
            } else {
                path.lineTo(x, y);
            }
        }

        canvas.drawPath(path, linePaint);
    }
}
