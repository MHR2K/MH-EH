package com.hippo.ehviewer.sync;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.callBack.DownloadSearchCallback;
import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.GalleryTags;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.util.ChineseConverterHelper;
import com.hippo.unifile.UniFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.Nullable;

public class DownloadListInfosExecutor {
    private static final int sortByIdAsc = 1;
    private static final int sortByIdDesc = 2;
    private static final int sortByCreateTimeAsc = 3;
    private static final int sortByCreateTimeDesc = 4;
    private static final int sortByRatingAsc = 5;
    private static final int sortByRatingDesc = 6;
    private static final int sortByFileSizeAsc = 7;
    private static final int sortByFileSizeDesc = 8;


    private final String TAG = "DownloadSearchingExecutor";

    ExecutorService service = Executors.newSingleThreadExecutor();
    Handler handler = new Handler(Looper.getMainLooper());

    private DownloadSearchCallback mDownloadSearchCallback;

    @Nullable
    private final List<DownloadInfo> mList;

    private List<DownloadInfo> resultList;

    private final String mSearchKey;

    private DownloadManager mDownloadManager;
    private Map<Long, SpiderInfo> mSpiderInfoMap;
    private boolean mFuzzySearch = false;
    private boolean mIgnoreCase = true;
    private boolean mEnableChineseConversion = true;
    private boolean mSortByRelevance = false;
    private boolean mGroupByLabel = false;
    private Map<String, List<DownloadInfo>> mGroupedResults;
    private long mStartTime = System.currentTimeMillis();

    public void setStartTime(long startTime) {
        this.mStartTime = startTime;
    }

    public long getStartTime() {
        return mStartTime;
    }

    public DownloadListInfosExecutor(@Nullable List<DownloadInfo> mList, String searchKey) {
        this.mList = mList != null ? new ArrayList<>(mList) : null;
        this.mSearchKey = searchKey;
    }

    public DownloadListInfosExecutor(@Nullable List<DownloadInfo> mList, String searchKey, boolean fuzzySearch, boolean ignoreCase) {
        this(mList, searchKey, fuzzySearch, ignoreCase, Settings.getEnableChineseConversion());
    }

    public DownloadListInfosExecutor(@Nullable List<DownloadInfo> mList, String searchKey, boolean fuzzySearch, boolean ignoreCase, boolean enableChineseConversion) {
        this.mList = mList != null ? new ArrayList<>(mList) : null;
        this.mSearchKey = searchKey;
        this.mFuzzySearch = fuzzySearch;
        this.mIgnoreCase = ignoreCase;
        this.mEnableChineseConversion = enableChineseConversion;
    }

    public DownloadListInfosExecutor(@Nullable List<DownloadInfo> mList, DownloadManager downloadManager) {
        this.mList = mList != null ? new ArrayList<>(mList) : null;
        this.mSearchKey = "";
    }

    public DownloadListInfosExecutor(@Nullable List<DownloadInfo> mList, DownloadManager downloadManager, Map<Long, SpiderInfo> spiderInfoMap) {
        this.mList = mList != null ? new ArrayList<>(mList) : null;
        this.mSearchKey = "";
        mDownloadManager = downloadManager;
        mSpiderInfoMap = spiderInfoMap;
    }

    public void setSearchOptions(boolean fuzzySearch, boolean ignoreCase, boolean enableChineseConversion) {
        this.mFuzzySearch = fuzzySearch;
        this.mIgnoreCase = ignoreCase;
        this.mEnableChineseConversion = enableChineseConversion;
    }

    public void setSearchOptions(boolean fuzzySearch, boolean ignoreCase) {
        setSearchOptions(fuzzySearch, ignoreCase, Settings.getEnableChineseConversion());
    }
    
    public void enableSortByRelevance(boolean enable) {
        this.mSortByRelevance = enable;
    }

    public void setGroupByLabel(boolean groupByLabel) {
        this.mGroupByLabel = groupByLabel;
    }

    public Map<String, List<DownloadInfo>> getGroupedResults() {
        return mGroupedResults;
    }

    public void setDownloadSearchingListener(DownloadSearchCallback downloadSearchCallback) {
        mDownloadSearchCallback = downloadSearchCallback;
    }

    /**
     * 执行异步搜索，结果通过回调返回
     */
    public void executeSearching() {
        service.execute(() -> {
            final long tBgStart = System.currentTimeMillis();
            android.util.Log.e("EhSearch", "[2.BG-Start] 后台搜索线程启动 (线程调度延迟=" + (tBgStart - mStartTime) + "ms)");
            if (mGroupByLabel) {
                resultList = searchingInBackgroundGrouped();
            } else {
                resultList = searchingInBackground();
            }
            final long tBgEnd = System.currentTimeMillis();
            android.util.Log.e("EhSearch", "[6.BG-Done] 后台搜索处理完成: 后台总耗时=" + (tBgEnd - tBgStart) + "ms (距发起=" + (tBgEnd - mStartTime) + "ms), 结果数=" + (resultList != null ? resultList.size() : 0) + ", 正在派发到主线程...");

            handler.post(() -> {
                if (mDownloadSearchCallback == null) {
                    return;
                }
                mDownloadSearchCallback.onDownloadSearchSuccess(resultList);
            });
        });
    }

    /**
     * 执行同步搜索，直接返回结果
     * @return 搜索结果列表
     */
    public List<DownloadInfo> executeSearchingSync() {
        return searchingInBackgroundInternal();
    }

    /**
     * 同步搜索的内部实现，跳过回调检查
     */
    private List<DownloadInfo> searchingInBackgroundInternal() {
        android.util.Log.e("EhSearch", "[2.BG-Exec] 开始执行下载列表搜索: 关键词='" + mSearchKey + "'" +
                ", 模糊搜索=" + mFuzzySearch + 
                ", 忽略大小写=" + mIgnoreCase + 
                ", 简繁体转换=" + mEnableChineseConversion +
                ", 相关性排序=" + mSortByRelevance +
                ", 待搜数据量=" + (mList != null ? mList.size() : 0));

        if (mSearchKey == null || mSearchKey.isEmpty()) {
            android.util.Log.e("EhSearch", "搜索关键词为空，返回完整列表");
            return mList != null ? mList : new ArrayList<>();
        }
        if (mList == null || mList.isEmpty()) {
            android.util.Log.e("EhSearch", "下载列表为null或空，返回空列表");
            return new ArrayList<>();
        }

        // 1. 批量预加载所有未加载标签项，消除 N+1 单条 SQLite 查询
        preloadMissingTags(mList);

        // 2. 预解析搜索参数（包括简繁体、大小写、标签分词）
        final long tContextStart = System.currentTimeMillis();
        SearchContext ctx = buildSearchContext(mSearchKey);
        final long tContextEnd = System.currentTimeMillis();

        // 3. 收集所有匹配的结果及其相似度分数
        final long tScanStart = System.currentTimeMillis();
        List<DownloadInfo> cache = new ArrayList<>();
        final Map<DownloadInfo, Double> scoreMap = new HashMap<>();
        int total = mList.size();

        for (int i = 0; i < total; i++) {
            DownloadInfo info = mList.get(i);
            double score = calculateSimilarityScore(info, ctx);

            if (score > 0) {
                cache.add(info);
                scoreMap.put(info, score);
            }

            if (i % 200 == 0 && mDownloadSearchCallback != null) {
                final int scanned = i;
                handler.post(() -> mDownloadSearchCallback.onSearchProgress(scanned, total));
            }
        }

        if (mDownloadSearchCallback != null) {
            handler.post(() -> mDownloadSearchCallback.onSearchProgress(total, total));
        }
        final long tScanEnd = System.currentTimeMillis();
        android.util.Log.e("EhSearch", "[4.Scan] 扫描匹配完成: 遍历=" + total + "项, 命中=" + cache.size() +
                "项 | 扫描耗时=" + (tScanEnd - tScanStart) + "ms (分词与预处理=" + (tContextEnd - tContextStart) + "ms)");

        // 4. 根据相似度分数对结果进行排序（若启用相关性排序）
        final long tSortStart = System.currentTimeMillis();
        if (mSortByRelevance) {
            cache.sort((a, b) -> Double.compare(scoreMap.getOrDefault(b, 0.0), 
                                                scoreMap.getOrDefault(a, 0.0)));
        }
        final long tSortEnd = System.currentTimeMillis();
        android.util.Log.e("EhSearch", "[5.Sort] 排序整理完成: 耗时=" + (tSortEnd - tSortStart) + "ms" +
                (mSortByRelevance ? " (按相关性排序已启用)" : " (无需排序)"));

        return cache;
    }

    /**
     * 按标签分组的搜索实现，同时报告进度
     * 搜索结果按 DownloadInfo.label 分组，每组内按相似度分数排序
     */
    private List<DownloadInfo> searchingInBackgroundGrouped() {
        android.util.Log.e("EhSearch", "[2.BG-Exec] 开始执行全部标签分组搜索: 关键词='" + mSearchKey + "'" +
                ", 模糊搜索=" + mFuzzySearch + 
                ", 忽略大小写=" + mIgnoreCase + 
                ", 简繁体转换=" + mEnableChineseConversion +
                ", 相关性排序=" + mSortByRelevance +
                ", 待搜数据量=" + (mList != null ? mList.size() : 0));

        if (mSearchKey == null || mSearchKey.isEmpty()) {
            // 关键词为空时，返回全部列表（不按标签分组）
            mGroupedResults = null;
            return mList != null ? mList : new ArrayList<>();
        }
        if (mList == null || mList.isEmpty()) {
            mGroupedResults = null;
            return new ArrayList<>();
        }

        // 1. 批量预加载所有未加载标签项，消除 N+1 单条 SQLite 查询
        preloadMissingTags(mList);

        // 2. 预解析搜索参数（包括简繁体、大小写、标签分词）
        final long tContextStart = System.currentTimeMillis();
        SearchContext ctx = buildSearchContext(mSearchKey);
        final long tContextEnd = System.currentTimeMillis();

        // 分组结果：标签名 -> (DownloadInfo -> 分数)
        final long tScanStart = System.currentTimeMillis();
        Map<String, List<DownloadInfo>> grouped = new HashMap<>();
        Map<String, Map<DownloadInfo, Double>> groupScoreMaps = new HashMap<>();
        int total = mList.size();
        int matchedCount = 0;

        for (int i = 0; i < total; i++) {
            DownloadInfo info = mList.get(i);
            double score = calculateSimilarityScore(info, ctx);

            if (score > 0) {
                matchedCount++;
                String label = (info.label != null && !info.label.isEmpty())
                        ? info.label : "默认";
                grouped.computeIfAbsent(label, k -> new ArrayList<>()).add(info);
                groupScoreMaps.computeIfAbsent(label, k -> new HashMap<>()).put(info, score);
            }

            // 每200个报告一次进度
            if (i % 200 == 0 && mDownloadSearchCallback != null) {
                final int scanned = i;
                handler.post(() -> mDownloadSearchCallback.onSearchProgress(scanned, total));
            }
        }

        // 报告最终进度
        if (mDownloadSearchCallback != null) {
            handler.post(() -> mDownloadSearchCallback.onSearchProgress(total, total));
        }
        final long tScanEnd = System.currentTimeMillis();
        android.util.Log.e("EhSearch", "[4.Scan] 分组扫描匹配完成: 遍历=" + total + "项, 命中=" + matchedCount +
                "项, 分组数=" + grouped.size() + " | 扫描耗时=" + (tScanEnd - tScanStart) + "ms (分词与预处理=" + (tContextEnd - tContextStart) + "ms)");

        // 每个分组内部若开启按相关性排序，按相似度分数排序
        final long tSortStart = System.currentTimeMillis();
        if (mSortByRelevance) {
            for (Map.Entry<String, List<DownloadInfo>> entry : grouped.entrySet()) {
                String label = entry.getKey();
                List<DownloadInfo> items = entry.getValue();
                Map<DownloadInfo, Double> scoreMap = groupScoreMaps.get(label);
                if (scoreMap != null) {
                    items.sort((a, b) -> Double.compare(
                            scoreMap.getOrDefault(b, 0.0),
                            scoreMap.getOrDefault(a, 0.0)));
                }
            }
        }

        mGroupedResults = grouped;

        // 返回展平后的列表（兼容现有回调）
        List<DownloadInfo> flatResult = new ArrayList<>();
        for (List<DownloadInfo> items : grouped.values()) {
            flatResult.addAll(items);
        }
        final long tSortEnd = System.currentTimeMillis();
        android.util.Log.e("EhSearch", "[5.Sort] 分组排序与展平完成: 耗时=" + (tSortEnd - tSortStart) + "ms");

        return flatResult;
    }

    @SuppressLint("NonConstantResourceId")
    public void executeFilterAndSort(int id) {
        service.execute(() -> {
            switch (id) {

                case R.id.download_done:
                    resultList = filterDownloadState(DownloadInfo.STATE_FINISH);
                    break;
                case R.id.not_started:
                    resultList = filterDownloadState(DownloadInfo.STATE_NONE);
                    break;
                case R.id.waiting:
                    resultList = filterDownloadState(DownloadInfo.STATE_WAIT);
                    break;
                case R.id.downloading:
                    resultList = filterDownloadState(DownloadInfo.STATE_DOWNLOAD);
                    break;
                case R.id.failed:
                    resultList = filterDownloadState(DownloadInfo.STATE_FAILED);
                    break;
                case R.id.sort_by_gallery_id_asc:
                case R.id.sort_by_gallery_id_desc:
                case R.id.sort_by_create_time_asc:
                case R.id.sort_by_create_time_desc:
                case R.id.sort_by_rating_asc:
                case R.id.sort_by_rating_desc:
                case R.id.sort_by_name_asc:
                case R.id.sort_by_name_desc:
                case R.id.sort_by_file_size_asc:
                case R.id.sort_by_file_size_desc:
                case R.id.sort_by_fav_count_asc:
                case R.id.sort_by_fav_count_desc:
                    resultList = sortByType(id);
                    break;
                case R.id.all_kind:
                case R.id.misc:
                case R.id.doujinshi:
                case R.id.manga:
                case R.id.artist_cg:
                case R.id.game_cg:
                case R.id.image_set:
                case R.id.cosplay:
                case R.id.asian_porn:
                case R.id.non_h:
                case R.id.western:
                case R.id.unknown:
                    resultList = filterDownloadKind(id);
                    break;
                case R.id.local_import:
                    resultList = filterLocalImport();
                    break;
                case R.id.filter_has_new_version:
                    resultList = filterHasNewVersion();
                    break;
                case R.id.all:
                case R.id.sort_by_default:
                default:
                    resultList = mList;
                    break;
            }

            handler.post(() -> {
                if (mDownloadSearchCallback == null) {
                    return;
                }
                mDownloadSearchCallback.onDownloadSearchSuccess(resultList);
            });
        });
    }

    private static final int STATUS_DONE = 1;
    private static final int STATUS_NOT_STARTED = 2;
    private static final int STATUS_WAITING = 3;
    private static final int STATUS_DOWNLOADING = 4;
    private static final int STATUS_FAILED = 5;

    private static final int PROGRESS_NOT_STARTED = 10;
    private static final int PROGRESS_IN_PROGRESS = 11;
    private static final int PROGRESS_FINISHED = 12;

    public void executeCombinedFilter(java.util.Set<Integer> statusFilters, java.util.Set<Integer> progressFilters) {
        service.execute(() -> {
            resultList = filterByStatusAndProgress(statusFilters, progressFilters);

            handler.post(() -> {
                if (mDownloadSearchCallback == null) {
                    return;
                }
                mDownloadSearchCallback.onDownloadSearchSuccess(resultList);
            });
        });
    }

    private List<DownloadInfo> filterByStatusAndProgress(java.util.Set<Integer> statusFilters, java.util.Set<Integer> progressFilters) {
        if (mList == null) {
            return new ArrayList<>();
        }
        if (statusFilters.isEmpty() && progressFilters.isEmpty()) {
            return new ArrayList<>(mList);
        }

        List<DownloadInfo> result = new ArrayList<>();
        for (DownloadInfo info : mList) {
            boolean matchStatus = statusFilters.isEmpty() || matchesStatusFilter(statusFilters, info.state);
            boolean matchProgress = true;

            if (!progressFilters.isEmpty()) {
                SpiderInfo spiderInfo = getSpiderInfo(info.gid);
                int startPage = spiderInfo != null ? spiderInfo.startPage : 0;
                int pages = spiderInfo != null ? spiderInfo.pages : 0;
                boolean hasSpiderInfo = spiderInfo != null;

                matchProgress = false;
                for (int progressFilter : progressFilters) {
                    if (matchesProgressFilter(progressFilter, startPage, pages, hasSpiderInfo)) {
                        matchProgress = true;
                        break;
                    }
                }
            }

            if (matchStatus && matchProgress) {
                result.add(info);
            }
        }
        return result;
    }

    private boolean matchesStatusFilter(java.util.Set<Integer> statusFilters, int state) {
        for (int statusFilter : statusFilters) {
            int targetState = mapStatusToDownloadState(statusFilter);
            if (targetState == state) {
                return true;
            }
        }
        return false;
    }

    private static int mapStatusToDownloadState(int filterStatus) {
        switch (filterStatus) {
            case STATUS_DONE:
                return DownloadInfo.STATE_FINISH;
            case STATUS_NOT_STARTED:
                return DownloadInfo.STATE_NONE;
            case STATUS_WAITING:
                return DownloadInfo.STATE_WAIT;
            case STATUS_DOWNLOADING:
                return DownloadInfo.STATE_DOWNLOAD;
            case STATUS_FAILED:
                return DownloadInfo.STATE_FAILED;
            default:
                return -1;
        }
    }

    private static boolean matchesProgressFilter(int filterProgress, int startPage, int pages, boolean hasSpiderInfo) {
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

    private SpiderInfo getSpiderInfo(long gid) {
        if (mSpiderInfoMap != null) {
            return mSpiderInfoMap.get(gid);
        }
        return null;
    }

    private List<DownloadInfo> sortByType(int type) {
        if (mList == null) {
            return new ArrayList<>();
        }
        DownloadInfo[] arr = new DownloadInfo[mList.size()];
        mList.<DownloadInfo>toArray(arr);

        // 如果是按文件大小排序，先计算所有文件大小
        if (type == R.id.sort_by_file_size_asc || type == R.id.sort_by_file_size_desc) {
            for (DownloadInfo info : arr) {
                if (info.fileSize < 0) { // 未计算过
                    info.fileSize = calculateDownloadDirSize(info);
                }
            }
        }

        int n = arr.length;
        // 子数组的大小分别为1，2，4，8...
        // 刚开始合并的数组大小是1，接着是2，接着4....
        for (int i = 1; i < n; i += i) {
            //进行数组进行划分
            int left = 0;
            int mid = left + i - 1;
            int right = mid + i;
            //进行合并，对数组大小为 i 的数组进行两两合并
            while (right < n) {
                // 合并函数和递归式的合并函数一样
                merge(arr, left, mid, right, type);
                left = right + 1;
                mid = left + i - 1;
                right = mid + i;
            }
            // 还有一些被遗漏的数组没合并，千万别忘了
            // 因为不可能每个字数组的大小都刚好为 i
            if (left < n && mid < n) {
                merge(arr, left, mid, n - 1, type);
            }
        }
        return new ArrayList<>(Arrays.<DownloadInfo>asList(arr));
    }

    // 合并函数，把两个有序的数组合并起来
    // arr[left..mif]表示一个数组，arr[mid+1 .. right]表示一个数组
    @SuppressLint("NonConstantResourceId")
    private static void merge(DownloadInfo[] arr, int left, int mid, int right, int sortType) {
        //先用一个临时数组把他们合并汇总起来
        DownloadInfo[] a = new DownloadInfo[right - left + 1];
        int i = left;
        int j = mid + 1;
        int k = 0;
        while (i <= mid && j <= right) {
            switch (sortType) {
                case R.id.sort_by_gallery_id_asc:
                    if (arr[i].gid < arr[j].gid) {
                        a[k++] = arr[i++];
                    } else {
                        a[k++] = arr[j++];
                    }
                    break;
                case R.id.sort_by_gallery_id_desc:
                    if (arr[i].gid > arr[j].gid) {
                        a[k++] = arr[i++];
                    } else {
                        a[k++] = arr[j++];
                    }
                    break;
                case R.id.sort_by_create_time_asc:
                    if (arr[i].time < arr[j].time) {
                        a[k++] = arr[i++];
                    } else {
                        a[k++] = arr[j++];
                    }
                    break;
                case R.id.sort_by_create_time_desc:
                    if (arr[i].time > arr[j].time) {
                        a[k++] = arr[i++];
                    } else {
                        a[k++] = arr[j++];
                    }
                    break;
                case R.id.sort_by_rating_asc:
                    if (arr[i].rating < arr[j].rating) {
                        a[k++] = arr[i++];
                    } else {
                        a[k++] = arr[j++];
                    }
                    break;
                case R.id.sort_by_rating_desc:
                    if (arr[i].rating > arr[j].rating) {
                        a[k++] = arr[i++];
                    } else {
                        a[k++] = arr[j++];
                    }
                    break;
                case R.id.sort_by_name_asc: {
                    String titleI = arr[i].title;
                    String titleJ = arr[j].title;
                    // null 值排在最后
                    if (titleI == null && titleJ == null) {
                        a[k++] = arr[i++];
                    } else if (titleI == null) {
                        a[k++] = arr[j++];
                    } else if (titleJ == null) {
                        a[k++] = arr[i++];
                    } else {
                        // 使用 compareToIgnoreCase 进行不区分大小写的比较
                        if (titleI.compareToIgnoreCase(titleJ) < 0) {
                            a[k++] = arr[i++];
                        } else {
                            a[k++] = arr[j++];
                        }
                    }
                    break;
                }
                case R.id.sort_by_name_desc: {
                    String titleI = arr[i].title;
                    String titleJ = arr[j].title;
                    // null 值排在最后
                    if (titleI == null && titleJ == null) {
                        a[k++] = arr[i++];
                    } else if (titleI == null) {
                        a[k++] = arr[j++];
                    } else if (titleJ == null) {
                        a[k++] = arr[i++];
                    } else {
                        // 使用 compareToIgnoreCase 进行不区分大小写的比较
                        if (titleI.compareToIgnoreCase(titleJ) > 0) {
                            a[k++] = arr[i++];
                        } else {
                            a[k++] = arr[j++];
                        }
                    }
                    break;
                }
                case R.id.sort_by_file_size_asc:
                    // 未计算的文件大小(-1)排在最后
                    if (arr[i].fileSize < 0 && arr[j].fileSize < 0) {
                        a[k++] = arr[i++];
                    } else if (arr[i].fileSize < 0) {
                        a[k++] = arr[j++];
                    } else if (arr[j].fileSize < 0) {
                        a[k++] = arr[i++];
                    } else if (arr[i].fileSize < arr[j].fileSize) {
                        a[k++] = arr[i++];
                    } else {
                        a[k++] = arr[j++];
                    }
                    break;
                case R.id.sort_by_file_size_desc:
                    // 未计算的文件大小(-1)排在最后
                    if (arr[i].fileSize < 0 && arr[j].fileSize < 0) {
                        a[k++] = arr[i++];
                    } else if (arr[i].fileSize < 0) {
                        a[k++] = arr[j++];
                    } else if (arr[j].fileSize < 0) {
                        a[k++] = arr[i++];
                    } else if (arr[i].fileSize > arr[j].fileSize) {
                        a[k++] = arr[i++];
                    } else {
                        a[k++] = arr[j++];
                    }
                    break;
                case R.id.sort_by_fav_count_asc:
                    // 未获取的收藏数(-1)排在最后
                    if (arr[i].favoriteCount < 0 && arr[j].favoriteCount < 0) {
                        a[k++] = arr[i++];
                    } else if (arr[i].favoriteCount < 0) {
                        a[k++] = arr[j++];
                    } else if (arr[j].favoriteCount < 0) {
                        a[k++] = arr[i++];
                    } else if (arr[i].favoriteCount < arr[j].favoriteCount) {
                        a[k++] = arr[i++];
                    } else {
                        a[k++] = arr[j++];
                    }
                    break;
                case R.id.sort_by_fav_count_desc:
                    // 未获取的收藏数(-1)排在最后
                    if (arr[i].favoriteCount < 0 && arr[j].favoriteCount < 0) {
                        a[k++] = arr[i++];
                    } else if (arr[i].favoriteCount < 0) {
                        a[k++] = arr[j++];
                    } else if (arr[j].favoriteCount < 0) {
                        a[k++] = arr[i++];
                    } else if (arr[i].favoriteCount > arr[j].favoriteCount) {
                        a[k++] = arr[i++];
                    } else {
                        a[k++] = arr[j++];
                    }
                    break;
            }

        }
        while (i <= mid) a[k++] = arr[i++];
        while (j <= right) a[k++] = arr[j++];
        // 把临时数组复制到原数组
        for (i = 0; i < k; i++) {
            arr[left++] = a[i];
        }
    }

    private List<DownloadInfo> filterDownloadState(int state) {
        List<DownloadInfo> list = new ArrayList<>();
        if (mList == null) {
            return list;
        }
        for (int i = 0; i < mList.size(); i++) {
            DownloadInfo info = mList.get(i);
            if (info.state == state) {
                list.add(info);
            }
        }
        return list;
    }

    private List<DownloadInfo> filterHasNewVersion() {
        List<DownloadInfo> list = new ArrayList<>();
        if (mList == null) {
            return list;
        }
        for (DownloadInfo info : mList) {
            if (com.hippo.ehviewer.util.NewVersionStore.hasNewVersion(info.gid)) {
                list.add(info);
            }
        }
        return list;
    }
    
    /**
     * 根据搜索关键词与标题的相关性对下载项进行排序
     * 
     * @param list 待排序的下载项列表
     * @param searchKey 搜索关键词
     * @return 排序后的列表
     */
    private List<DownloadInfo> sortByRelevance(List<DownloadInfo> list, String searchKey) {
        if (list == null || list.isEmpty()) {
            return new ArrayList<>();
        }
        final long tStart = System.currentTimeMillis();
        List<DownloadInfo> result = new ArrayList<>(list);
        preloadMissingTags(result);
        SearchContext ctx = buildSearchContext(searchKey);
        
        final Map<DownloadInfo, Double> scoreMap = new HashMap<>(result.size());
        for (DownloadInfo info : result) {
            double score = calculateSimilarityScore(info, ctx);
            scoreMap.put(info, score);
        }
        
        result.sort((a, b) -> Double.compare(
            scoreMap.getOrDefault(b, 0.0),
            scoreMap.getOrDefault(a, 0.0)
        ));
        final long tEnd = System.currentTimeMillis();
        android.util.Log.e("EhSearch", "[RelevanceSort] 相关性排序耗时=" + (tEnd - tStart) + "ms, 项数=" + result.size());
        return result;
    }

    private List<DownloadInfo> filterDownloadKind(int state) {
        int kind = kindValue(state);
        List<DownloadInfo> list = new ArrayList<>();

        if (mList == null) {
            return null;
        }
        if (kind == EhUtils.ALL_CATEGORY) {
            return mList;
        }
        for(DownloadInfo info : mList){
            if (info.category == kind) {
                list.add(info);
            }
        }
        return list;
    }

    private List<DownloadInfo> filterLocalImport() {
        List<DownloadInfo> list = new ArrayList<>();
        if (mList == null) {
            return list;
        }
        for (DownloadInfo info : mList) {
            if (info.archiveUri != null && !info.archiveUri.isEmpty()) {
                list.add(info);
            }
        }
        return list;
    }


    /**
     * 批量预加载所有未加载标签项，消除单条 SQLite 查询瓶颈并避免缓存穿透
     */
    private void preloadMissingTags(List<DownloadInfo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        final long tPreloadStart = System.currentTimeMillis();
        int total = list.size();
        int alreadyCached = 0;
        int simpleTagsUsed = 0;
        List<Long> missingGids = null;
        for (int i = 0, size = list.size(); i < size; i++) {
            DownloadInfo info = list.get(i);
            if (info != null && info.galleryTags == null && info.tgList == null) {
                if (info.simpleTags != null && info.simpleTags.length > 0) {
                    ArrayList<String> tagList = new ArrayList<>(info.simpleTags.length);
                    for (String tag : info.simpleTags) {
                        if (tag != null && !tag.isEmpty()) {
                            tagList.add(tag);
                        }
                    }
                    info.tgList = tagList;
                    simpleTagsUsed++;
                } else {
                    if (missingGids == null) {
                        missingGids = new ArrayList<>();
                    }
                    missingGids.add(info.gid);
                }
            } else if (info != null) {
                alreadyCached++;
            }
        }

        int missingCount = missingGids != null ? missingGids.size() : 0;
        long tDbDuration = 0;
        long tParseDuration = 0;

        if (missingGids != null && !missingGids.isEmpty()) {
            final int missingTotal = missingGids.size();
            final long tDbStart = System.currentTimeMillis();
            Map<Long, GalleryTags> dbTagsMap = EhDB.queryGalleryTags(missingGids, (loaded, totalCount) -> {
                if (mDownloadSearchCallback != null) {
                    handler.post(() -> mDownloadSearchCallback.onSearchPrepareProgress(loaded, missingTotal));
                }
            });
            final long tDbEnd = System.currentTimeMillis();
            tDbDuration = tDbEnd - tDbStart;

            for (int i = 0, size = list.size(); i < size; i++) {
                DownloadInfo info = list.get(i);
                if (info != null && info.galleryTags == null && info.tgList == null) {
                    GalleryTags gt = dbTagsMap.get(info.gid);
                    if (gt != null) {
                        info.galleryTags = gt;
                    } else {
                        // 标记为空哨兵，避免重复查库
                        info.galleryTags = com.hippo.ehviewer.client.data.GalleryInfo.EMPTY_GALLERY_TAGS;
                        info.tgList = new ArrayList<>(0);
                    }
                }
            }
            final long tParseEnd = System.currentTimeMillis();
            tParseDuration = tParseEnd - tDbEnd;
        }

        final long tPreloadTotal = System.currentTimeMillis() - tPreloadStart;
        android.util.Log.e("EhSearch", "[3.PreloadTags] 标签预加载: 总项数=" + total +
                ", 内存已有=" + alreadyCached +
                ", 简单标签转换=" + simpleTagsUsed +
                ", SQLite批量查询=" + missingCount + "项 (DB耗时=" + tDbDuration + "ms, 引用赋值耗时=" + tParseDuration + "ms)" +
                " | 预加载总耗时=" + tPreloadTotal + "ms");
    }

    private SearchContext buildSearchContext(String searchKey) {
        return new SearchContext(searchKey, mIgnoreCase, mEnableChineseConversion);
    }

    // 计算标题与查询词的相似度分数（保留公共方法以兼容旧代码）
    public double calculateSimilarityScore(DownloadInfo info, String searchKey) {
        if (info == null || searchKey == null || searchKey.isEmpty()) {
            return 0.0;
        }
        return calculateSimilarityScore(info, buildSearchContext(searchKey));
    }

    // 计算标题与查询词的相似度分数（使用预解析好的 SearchContext，避免重复分配）
    private double calculateSimilarityScore(DownloadInfo info, SearchContext ctx) {
        if (info == null || ctx == null) {
            return 0.0;
        }

        // 1. 精确/包含匹配标题给予最高分（1.5分，确保精确匹配排在最前面）
        if (matchTitle(info, ctx)) {
            return 1.5;
        }

        // 2. 如果启用模糊搜索，使用 Jaro-Winkler 相似度计算
        if (mFuzzySearch) {
            String keyToMatch = mIgnoreCase ? ctx.searchKeyLower : ctx.searchKey;
            double sim1 = 0.0;
            if (info.title != null && !info.title.isEmpty()) {
                String t = mIgnoreCase ? info.title.toLowerCase(Locale.ROOT) : info.title;
                sim1 = EhUtils.calculateJaroWinklerSimilarity(t, keyToMatch);
            }
            double sim2 = 0.0;
            if (info.titleJpn != null && !info.titleJpn.isEmpty()) {
                String t = mIgnoreCase ? info.titleJpn.toLowerCase(Locale.ROOT) : info.titleJpn;
                sim2 = EhUtils.calculateJaroWinklerSimilarity(t, keyToMatch);
            }
            double similarity = Math.max(sim1, sim2);
            if (similarity >= 0.7) {
                return 0.7 + similarity * 0.3;
            }
        }

        // 3. 匹配标签给予一定分数
        if (matchTag(ctx.parsedSearchTags, info)) {
            return 0.6;
        }

        return 0.0;
    }

    private boolean matchTitle(DownloadInfo info, SearchContext ctx) {
        return containsTitleText(info.title, ctx) || containsTitleText(info.titleJpn, ctx);
    }

    private boolean containsTitleText(String title, SearchContext ctx) {
        if (title == null || title.isEmpty()) {
            return false;
        }
        if (!mIgnoreCase) {
            if (title.contains(ctx.searchKey)) return true;
            if (ctx.hasChineseVariants) {
                return title.contains(ctx.searchKeySimp) || title.contains(ctx.searchKeyTrad);
            }
            return false;
        }
        // 使用零内存分配的 containsIgnoreCase 代替 toLowerCase
        if (ParsedSearchTag.containsIgnoreCase(title, ctx.searchKeyLower)) return true;
        if (ctx.hasChineseVariants) {
            return ParsedSearchTag.containsIgnoreCase(title, ctx.searchKeySimp) ||
                   ParsedSearchTag.containsIgnoreCase(title, ctx.searchKeyTrad);
        }
        return false;
    }

    protected List<DownloadInfo> searchingInBackground() {
        return searchingInBackgroundInternal();
    }

    private boolean matchTag(ParsedSearchTag[] searchTags, DownloadInfo info) {
        if (searchTags == null || searchTags.length == 0 || info == null) {
            return false;
        }

        // 1. 如果有预加载的 GalleryTags，直接在 GalleryTags 原始字段上进行高速零分配匹配
        if (info.galleryTags != null) {
            if (info.galleryTags == com.hippo.ehviewer.client.data.GalleryInfo.EMPTY_GALLERY_TAGS) {
                return matchFallbackTags(searchTags, info);
            }
            for (ParsedSearchTag searchTag : searchTags) {
                if (!searchTag.matches(info.galleryTags)) {
                    return false;
                }
            }
            return true;
        }

        // 2. 回退到 simpleTags、tgList 或单条懒加载查询
        return matchFallbackTags(searchTags, info);
    }

    private boolean matchFallbackTags(ParsedSearchTag[] searchTags, DownloadInfo info) {
        if (info.simpleTags != null && info.simpleTags.length > 0) {
            for (ParsedSearchTag searchTag : searchTags) {
                boolean matched = false;
                for (String tag : info.simpleTags) {
                    if (searchTag.matches(tag)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    return false;
                }
            }
            return true;
        }

        if (info.tgList != null && !info.tgList.isEmpty()) {
            for (ParsedSearchTag searchTag : searchTags) {
                boolean matched = false;
                for (int i = 0, size = info.tgList.size(); i < size; i++) {
                    if (searchTag.matches(info.tgList.get(i))) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    return false;
                }
            }
            return true;
        }

        // 单条按需懒加载（处理未执行批量预加载的孤立调用）
        if (info.galleryTags == null && (info.tgList == null || info.tgList.isEmpty())) {
            GalleryTags dbTags = EhDB.queryGalleryTags(info.gid);
            info.galleryTags = dbTags != null ? dbTags : com.hippo.ehviewer.client.data.GalleryInfo.EMPTY_GALLERY_TAGS;
            if (dbTags != null) {
                for (ParsedSearchTag searchTag : searchTags) {
                    if (!searchTag.matches(dbTags)) {
                        return false;
                    }
                }
                return true;
            }
        }

        return false;
    }

    private ArrayList<String> getSearchableTags(DownloadInfo info) {
        if (info == null) {
            return new ArrayList<>(0);
        }
        if (info.tgList != null) {
            return info.tgList;
        }

        ArrayList<String> tagList = new ArrayList<>();
        if (info.simpleTags != null) {
            for (String tag : info.simpleTags) {
                if (tag != null && !tag.isEmpty()) {
                    tagList.add(tag);
                }
            }
        }

        if (tagList.isEmpty()) {
            ArrayList<String> dbTags = searchTagList(info.gid);
            if (dbTags != null && !dbTags.isEmpty()) {
                tagList.addAll(dbTags);
            }
        }

        info.tgList = tagList;
        return tagList;
    }

    private static String[] splitSearchTags(String searchKey) {
        if (searchKey == null) {
            return new String[0];
        }
        String normalized = searchKey.trim();
        if (normalized.isEmpty()) {
            return new String[0];
        }
        // Keep compatibility: double-space means multi-tag search.
        String[] rawTags = normalized.split("\\s{2,}");
        ArrayList<String> tags = new ArrayList<>(rawTags.length);
        for (String rawTag : rawTags) {
            String tag = rawTag == null ? null : rawTag.trim();
            if (tag != null && !tag.isEmpty()) {
                tags.add(tag);
            }
        }
        return tags.<String>toArray(new String[0]);
    }

    private static ParsedSearchTag[] parseSearchTags(String searchKey, boolean enableChinese) {
        String[] rawTags = splitSearchTags(searchKey);
        if (rawTags.length == 0) {
            return new ParsedSearchTag[0];
        }
        ParsedSearchTag[] result = new ParsedSearchTag[rawTags.length];
        for (int i = 0; i < rawTags.length; i++) {
            result[i] = new ParsedSearchTag(rawTags[i], enableChinese);
        }
        return result;
    }

    private static class ParsedSearchTag {
        final String searchNamespace; // lowercase or null
        final String searchName;      // lowercase
        final String searchNameSimp;  // lowercase
        final String searchNameTrad;  // lowercase
        final String prefix;          // "namespace:" or null
        final boolean hasChineseVariants;

        ParsedSearchTag(String raw, boolean enableChinese) {
            String normalized = raw.trim();
            int idx = normalized.indexOf(':');
            if (idx >= 0) {
                searchNamespace = normalized.substring(0, idx).trim().toLowerCase(Locale.ROOT);
                searchName = normalized.substring(idx + 1).trim().toLowerCase(Locale.ROOT);
                prefix = searchNamespace + ":";
            } else {
                searchNamespace = null;
                searchName = normalized.toLowerCase(Locale.ROOT);
                prefix = null;
            }
            if (enableChinese && ChineseConverterHelper.isAvailable()) {
                searchNameSimp = ChineseConverterHelper.toSimplified(searchName);
                searchNameTrad = ChineseConverterHelper.toTraditional(searchName);
                hasChineseVariants = (searchNameSimp != null && !searchNameSimp.equals(searchName)) ||
                                     (searchNameTrad != null && !searchNameTrad.equals(searchName));
            } else {
                searchNameSimp = searchName;
                searchNameTrad = searchName;
                hasChineseVariants = false;
            }
        }

        boolean matches(GalleryTags tags) {
            if (tags == null) {
                return false;
            }
            if (searchNamespace != null) {
                String field = getNamespaceField(tags, searchNamespace);
                return matchField(field);
            } else {
                return matchField(tags.female)
                    || matchField(tags.artist)
                    || matchField(tags.character)
                    || matchField(tags.parody)
                    || matchField(tags.group)
                    || matchField(tags.male)
                    || matchField(tags.language)
                    || matchField(tags.cosplayer)
                    || matchField(tags.mixed)
                    || matchField(tags.other)
                    || matchField(tags.misc)
                    || matchField(tags.rows)
                    || matchField(tags.reclass)
                    || matchField(tags.location);
            }
        }

        private static String getNamespaceField(GalleryTags tags, String ns) {
            switch (ns) {
                case "artist": return tags.artist;
                case "female": return tags.female;
                case "male": return tags.male;
                case "character": return tags.character;
                case "parody": return tags.parody;
                case "group": return tags.group;
                case "cosplayer": return tags.cosplayer;
                case "language": return tags.language;
                case "mixed": return tags.mixed;
                case "other": return tags.other;
                case "misc": return tags.misc;
                case "rows": return tags.rows;
                case "reclass": return tags.reclass;
                case "location": return tags.location;
                default: return null;
            }
        }

        boolean matches(String tag) {
            if (tag == null || searchName.isEmpty()) {
                return false;
            }
            String trimmedTag = tag.trim();
            if (trimmedTag.isEmpty()) {
                return false;
            }

            if (searchNamespace != null) {
                if (!trimmedTag.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    return false;
                }
                String tagName = trimmedTag.substring(prefix.length()).trim();
                return matchField(tagName);
            } else {
                int colon = trimmedTag.indexOf(':');
                String tagName = colon >= 0 ? trimmedTag.substring(colon + 1).trim() : trimmedTag;
                return matchField(tagName);
            }
        }

        private boolean matchField(String field) {
            if (field == null || field.isEmpty() || searchName.isEmpty()) {
                return false;
            }
            if (containsIgnoreCase(field, searchName)) {
                return true;
            }
            if (hasChineseVariants) {
                if (searchNameSimp != null && !searchNameSimp.equals(searchName) && containsIgnoreCase(field, searchNameSimp)) {
                    return true;
                }
                if (searchNameTrad != null && !searchNameTrad.equals(searchName) && containsIgnoreCase(field, searchNameTrad)) {
                    return true;
                }
            }
            return false;
        }

        public static boolean containsIgnoreCase(String src, String what) {
            if (src == null || what == null) {
                return false;
            }
            final int length = what.length();
            if (length == 0) {
                return true;
            }
            final int max = src.length() - length;
            for (int i = 0; i <= max; i++) {
                if (src.regionMatches(true, i, what, 0, length)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static class SearchContext {
        final String searchKey;
        final String searchKeyLower;
        final String searchKeySimp;
        final String searchKeyTrad;
        final boolean hasChineseVariants;
        final ParsedSearchTag[] parsedSearchTags;

        SearchContext(String searchKey, boolean ignoreCase, boolean enableChinese) {
            this.searchKey = searchKey != null ? searchKey : "";
            this.searchKeyLower = ignoreCase ? this.searchKey.toLowerCase(Locale.ROOT) : this.searchKey;
            if (enableChinese && ChineseConverterHelper.isAvailable()) {
                this.searchKeySimp = ChineseConverterHelper.toSimplified(this.searchKeyLower);
                this.searchKeyTrad = ChineseConverterHelper.toTraditional(this.searchKeyLower);
                this.hasChineseVariants = (searchKeySimp != null && !searchKeySimp.equals(searchKeyLower)) ||
                                          (searchKeyTrad != null && !searchKeyTrad.equals(searchKeyLower));
            } else {
                this.searchKeySimp = this.searchKeyLower;
                this.searchKeyTrad = this.searchKeyLower;
                this.hasChineseVariants = false;
            }
            this.parsedSearchTags = parseSearchTags(this.searchKey, enableChinese);
        }
    }

    private ArrayList<String> searchTagList(long gid) {
        GalleryTags tags = EhDB.queryGalleryTags(gid);
        if (tags == null) {
            return null;
        }
        return parseGalleryTags(tags);
    }

    public static ArrayList<String> parseGalleryTags(GalleryTags tags) {
        return com.hippo.ehviewer.client.data.GalleryInfo.parseGalleryTags(tags);
    }

    /**
     * 计算下载目录的总大小
     */
    private long calculateDownloadDirSize(DownloadInfo info) {
        try {
            UniFile downloadDir = SpiderDen.getGalleryDownloadDir(info);
            if (downloadDir == null || !downloadDir.isDirectory()) {
                return -1;
            }
            return calculateFolderSize(downloadDir);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 递归计算文件夹大小
     */
    private long calculateFolderSize(UniFile folder) {
        long totalSize = 0;
        UniFile[] files = folder.listFiles();

        if (files == null) {
            return 0;
        }

        for (UniFile file : files) {
            if (file.isFile()) {
                long fileSize = file.length();
                if (fileSize > 0) {
                    totalSize += fileSize;
                }
            } else if (file.isDirectory()) {
                totalSize += calculateFolderSize(file); // 递归计算子文件夹
            }
        }

        return totalSize;
    }


    private int kindValue(int id) {
        return switch (id) {
            case R.id.doujinshi -> EhConfig.DOUJINSHI;
            case R.id.manga -> EhConfig.MANGA;
            case R.id.artist_cg -> EhConfig.ARTIST_CG;
            case R.id.game_cg -> EhConfig.GAME_CG;
            case R.id.western -> EhConfig.WESTERN;
            case R.id.non_h -> EhConfig.NON_H;
            case R.id.image_set -> EhConfig.IMAGE_SET;
            case R.id.cosplay -> EhConfig.COSPLAY;
            case R.id.asian_porn -> EhConfig.ASIAN_PORN;
            case R.id.misc -> EhConfig.MISC;
            default -> EhUtils.ALL_CATEGORY;
        };
    }

}
