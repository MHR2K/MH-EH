package com.hippo.ehviewer.stats;

public class DailyStats {
    public String date;            // "2026-08-14", 主键
    public int readCount;          // 阅读完成数
    public int downloadAdded;      // 添加下载数
    public int downloadCompleted;  // 下载完成数
    public long updateTime;        // 更新时间

    public DailyStats() {
    }

    public DailyStats(String date) {
        this.date = date;
        this.readCount = 0;
        this.downloadAdded = 0;
        this.downloadCompleted = 0;
        this.updateTime = System.currentTimeMillis();
    }
}
