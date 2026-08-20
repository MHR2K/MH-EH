package com.hippo.ehviewer.callBack;

import com.hippo.ehviewer.dao.DownloadInfo;

import java.util.List;

public interface DownloadSearchCallback {

    void onDownloadSearchSuccess(List<DownloadInfo> mList);

    void onDownloadListHandleSuccess(List<DownloadInfo> mList);

    void onDownloadSearchFailed(List<DownloadInfo> mList);

    /**
     * 搜索进度回调（用于全部标签搜索模式）
     * @param scanned 已扫描数量
     * @param total 总数量
     */
    default void onSearchProgress(int scanned, int total) {}
}
