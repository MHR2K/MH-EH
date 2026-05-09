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

    public DownloadListInfosExecutor(@Nullable List<DownloadInfo> mList, String searchKey) {
        this.mList = mList;
        this.mSearchKey = searchKey;
    }

    public DownloadListInfosExecutor(@Nullable List<DownloadInfo> mList, String searchKey, boolean fuzzySearch, boolean ignoreCase) {
        this(mList, searchKey, fuzzySearch, ignoreCase, Settings.getEnableChineseConversion());
    }

    public DownloadListInfosExecutor(@Nullable List<DownloadInfo> mList, String searchKey, boolean fuzzySearch, boolean ignoreCase, boolean enableChineseConversion) {
        this.mList = mList;
        this.mSearchKey = searchKey;
        this.mFuzzySearch = fuzzySearch;
        this.mIgnoreCase = ignoreCase;
        this.mEnableChineseConversion = enableChineseConversion;
    }

    public DownloadListInfosExecutor(@Nullable List<DownloadInfo> mList, DownloadManager downloadManager) {
        this.mList = mList;
        this.mSearchKey = "";
        mDownloadManager = downloadManager;
    }

    public DownloadListInfosExecutor(@Nullable List<DownloadInfo> mList, DownloadManager downloadManager, Map<Long, SpiderInfo> spiderInfoMap) {
        this.mList = mList;
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

    public void setDownloadSearchingListener(DownloadSearchCallback downloadSearchCallback) {
        mDownloadSearchCallback = downloadSearchCallback;
    }

    /**
     * 执行异步搜索，结果通过回调返回
     */
    public void executeSearching() {
        service.execute(() -> {
            resultList = searchingInBackground();
            
            // 如果启用按相关性排序，对搜索结果进行排序
            if (mSortByRelevance && mSearchKey != null && !mSearchKey.isEmpty() && resultList != null && !resultList.isEmpty()) {
                resultList = sortByRelevance(resultList, mSearchKey);
            }

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
        List<DownloadInfo> results = searchingInBackgroundInternal();
        
        // 如果启用按相关性排序，对搜索结果进行排序
        if (mSortByRelevance && mSearchKey != null && !mSearchKey.isEmpty() && results != null && !results.isEmpty()) {
            results = sortByRelevance(results, mSearchKey);
        }
        
        return results;
    }
    
    /**
     * 同步搜索的内部实现，跳过回调检查
     */
    private List<DownloadInfo> searchingInBackgroundInternal() {
        android.util.Log.d("EhSearch", "开始执行同步下载列表搜索: 关键词=" + mSearchKey + 
                        ", 模糊搜索=" + mFuzzySearch + 
                        ", 忽略大小写=" + mIgnoreCase + 
                        ", 简繁体转换=" + mEnableChineseConversion);
        
        if (mSearchKey == null || mSearchKey.isEmpty()) {
            android.util.Log.d("EhSearch", "搜索关键词为空，返回完整列表");
            return mList;
        }
        if (mList == null) {
            android.util.Log.d("EhSearch", "下载列表为null，返回空列表");
            return new ArrayList<>();
        }
        
        // 创建结果列表和相似度分数映射
        List<DownloadInfo> cache = new ArrayList<>();
        final Map<DownloadInfo, Double> scoreMap = new HashMap<>();

        // 首先收集所有匹配的结果及其相似度分数
        for (int i = 0; i < mList.size(); i++) {
            DownloadInfo info = mList.get(i);
            double score = calculateSimilarityScore(info, mSearchKey);
            
            if (score > 0) {
                cache.add(info);
                scoreMap.put(info, score);
            }
        }
        
        // 根据相似度分数对结果进行排序
        cache.sort((a, b) -> Double.compare(scoreMap.getOrDefault(b, 0.0), 
                                        scoreMap.getOrDefault(a, 0.0)));

        return cache;
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
        mList.toArray(arr);

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
        return Arrays.asList(arr);
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
        
        // 创建副本以避免修改原列表
        List<DownloadInfo> result = new ArrayList<>(list);
        
        // 创建相似度分数映射
        final Map<DownloadInfo, Double> scoreMap = new HashMap<>();
        
        // 计算每个下载项与搜索关键词的相似度分数
        for (DownloadInfo info : result) {
            double score = calculateSimilarityScore(info, searchKey);
            scoreMap.put(info, score);
        }
        
        // 根据相似度分数排序（高分在前）
        result.sort((a, b) -> Double.compare(
            scoreMap.getOrDefault(b, 0.0),
            scoreMap.getOrDefault(a, 0.0)
        ));
        
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


    // 计算标题与查询词的相似度分数
    private double calculateSimilarityScore(DownloadInfo info, String searchKey) {
        String title = info.title;
        String titleJpn = info.titleJpn;
        
        // 合并标题
        String fullTitle = (titleJpn != null ? titleJpn : "") + " " + (title != null ? title : "");
        
        // 精确匹配给予最高分
        if ((mIgnoreCase && fullTitle.toLowerCase().contains(searchKey.toLowerCase())) ||
            (!mIgnoreCase && fullTitle.contains(searchKey))) {
            return 1.5; // 超过1的分数，确保精确匹配排在最前面
        }
        
        // 如果启用模糊搜索，使用Jaro-Winkler相似度计算
        if (mFuzzySearch) {
            String titleToMatch = mIgnoreCase ? fullTitle.toLowerCase() : fullTitle;
            String keyToMatch = mIgnoreCase ? searchKey.toLowerCase() : searchKey;
            double similarity = EhUtils.calculateJaroWinklerSimilarity(titleToMatch, keyToMatch);
            
            // 如果相似度超过阈值，将分数放大到0.7-1.0范围内
            if (similarity >= 0.7) {
                return 0.7 + similarity * 0.3;
            }
        }
        
        // 匹配标签给予一定分数
        if (matchTag(searchKey, info)) {
            return 0.6;  // 标签匹配的相似度较低
        }
        
        return 0.0;  // 不匹配
    }

    protected List<DownloadInfo> searchingInBackground() {
        android.util.Log.d("EhSearch", "开始执行下载列表搜索: 关键词=" + mSearchKey + 
                          ", 模糊搜索=" + mFuzzySearch + 
                          ", 忽略大小写=" + mIgnoreCase + 
                          ", 简繁体转换=" + mEnableChineseConversion);
        
        if (mDownloadSearchCallback == null) {
            android.util.Log.d("EhSearch", "搜索回调为null，改用内部搜索实现");
            // 使用不依赖回调的内部搜索实现
            return searchingInBackgroundInternal();
        }
        
        // 使用内部搜索实现执行搜索
        return searchingInBackgroundInternal();
    }

    private boolean matchTag(String mSearchKey, DownloadInfo info) {
        ArrayList<String> searchableTags = getSearchableTags(info);
        if (searchableTags.isEmpty()) {
            return false;
        }

        String[] searchTags = splitSearchTags(mSearchKey);
        if (searchTags.length == 0) {
            return false;
        }

        for (String searchTag : searchTags) {
            boolean matched = false;
            for (String tag : searchableTags) {
                if (matchSingleTag(tag, searchTag)) {
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

    private ArrayList<String> getSearchableTags(DownloadInfo info) {
        if (info.tgList != null && !info.tgList.isEmpty()) {
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
        return tags.toArray(new String[0]);
    }

    private static boolean matchSingleTag(String tag, String searchTag) {
        if (tag == null || searchTag == null) {
            return false;
        }

        String normalizedTag = tag.trim().toLowerCase(Locale.ROOT);
        String normalizedSearchTag = searchTag.trim().toLowerCase(Locale.ROOT);
        if (normalizedTag.isEmpty() || normalizedSearchTag.isEmpty()) {
            return false;
        }

        int tagIndex = normalizedTag.indexOf(':');
        String tagNamespace = tagIndex >= 0 ? normalizedTag.substring(0, tagIndex) : null;
        String tagName = tagIndex >= 0 ? normalizedTag.substring(tagIndex + 1) : normalizedTag;

        int searchTagIndex = normalizedSearchTag.indexOf(':');
        String searchNamespace = searchTagIndex >= 0 ? normalizedSearchTag.substring(0, searchTagIndex) : null;
        String searchName = searchTagIndex >= 0 ? normalizedSearchTag.substring(searchTagIndex + 1) : normalizedSearchTag;

        if (searchNamespace != null && (tagNamespace == null || !tagNamespace.equals(searchNamespace))) {
            return false;
        }

        if (searchName.isEmpty()) {
            return false;
        }

        if (tagName.equals(searchName)) {
            return true;
        }

        // Search hint is "keyword", so allow contains match on tag name.
        return tagName.contains(searchName);
    }


    private ArrayList<String> searchTagList(long gid) {
        GalleryTags tags = EhDB.queryGalleryTags(gid);

        if (tags == null) {
            return null;
        }

        ArrayList<String> tagList = new ArrayList<>();

        tagList.addAll(parserList("artist", tags.artist));
        tagList.addAll(parserList("rows", tags.rows));
        tagList.addAll(parserList("cosplayer", tags.cosplayer));
        tagList.addAll(parserList("character", tags.character));
        tagList.addAll(parserList("female", tags.female));
        tagList.addAll(parserList("group", tags.group));
        tagList.addAll(parserList("language", tags.language));
        tagList.addAll(parserList("male", tags.male));
        tagList.addAll(parserList("misc", tags.misc));
        tagList.addAll(parserList("mixed", tags.mixed));
        tagList.addAll(parserList("other", tags.other));
        tagList.addAll(parserList("parody", tags.parody));
        tagList.addAll(parserList("reclass", tags.reclass));

        return tagList;
    }

    private ArrayList<String> parserList(String name, String content) {
        if (name == null || content == null) {
            return new ArrayList<>();
        }
        ArrayList<String> list = new ArrayList<>();

        String[] tagNames = content.split(",");

        for (String s : tagNames) {
            String normalized = s == null ? null : s.trim();
            if (normalized == null || normalized.isEmpty()) {
                continue;
            }
            list.add(name + ":" + normalized);
        }

        return list;
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
