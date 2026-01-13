package com.hippo.ehviewer.sync

import android.os.Handler
import android.os.Looper
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.callBack.SpiderInfoReadCallBack
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DownloadSpiderInfoExecutor(
    private val mList: MutableList<DownloadInfo>,
    private val callBack: SpiderInfoReadCallBack?
) {
    var handler: Handler = Handler(Looper.getMainLooper())
    // 主加载线程池：4 线程用于第二阶段的后台更新检查
    private val service: ExecutorService = Executors.newFixedThreadPool(4)
    private val repository = EhApplication.getSpiderInfoRepository(EhApplication.getInstance())    
    // 防抖动：延迟批量回调UI，避免频繁刷新
    private var pendingUiUpdate: Runnable? = null
    val resultMap: MutableMap<Long?, SpiderInfo?> = java.util.concurrent.ConcurrentHashMap<Long?, SpiderInfo?>()

    fun execute() {
        // ========== 第一阶段：快速从数据库加载 ==========
        for (info in mList) {
            val dbInfo = repository.getFromDbOnly(info.gid)
            if (dbInfo != null) {
                resultMap[info.gid] = dbInfo
            }
        }

        // 立即回调给 UI，显示数据库中的进度（瞬间）
        handler.post {
            callBack?.resultCallBack(resultMap)
        }

        // ========== 第二阶段：后台检查本地/SMB 更新 ==========
        service.execute {
            try {
                val updateTasks = mList.map { info ->
                    java.util.concurrent.Callable {
                        checkAndUpdateIfNewer(info)
                    }
                }

                // 最多等待 30 秒完成所有更新检查
                service.invokeAll(updateTasks, 30, TimeUnit.SECONDS)
            } catch (e: Exception) {
                // 异常处理
            }
        }
    }

    /**
     * 检查本地/SMB 有无更新，若有则更新结果和回调
     */
    private fun checkAndUpdateIfNewer(info: GalleryInfo): Boolean {
        try {
            // 获取最新的完整信息（包括本地和 SMB）
            val newerInfo: SpiderInfo? = repository.get(info, true)
            if (newerInfo == null) {
                return false
            }

            val currentInfo: SpiderInfo? = resultMap[info.gid]
            
            // 对比：如果新信息的页数或其他关键字段更新了，则刷新
            if (currentInfo == null || 
                newerInfo.startPage != currentInfo.startPage ||
                newerInfo.pages != currentInfo.pages) {
                
                resultMap[info.gid] = newerInfo

                // 防抖动：移除之前的回调，延迟200ms批量更新UI
                pendingUiUpdate?.let { handler.removeCallbacks(it) }
                pendingUiUpdate = Runnable {
                    callBack?.resultCallBack(resultMap)
                }
                handler.postDelayed(pendingUiUpdate!!, 200)
            }

            return true
        } catch (e: Exception) {
            return false
        }
    }
}

