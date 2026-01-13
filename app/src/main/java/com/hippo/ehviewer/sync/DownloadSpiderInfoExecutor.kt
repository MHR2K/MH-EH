package com.hippo.ehviewer.sync

import android.os.Handler
import android.os.Looper
import com.hippo.ehviewer.callBack.SpiderInfoReadCallBack
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.smb.SmbFileHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DownloadSpiderInfoExecutor(
    private val mList: MutableList<DownloadInfo>,
    private val callBack: SpiderInfoReadCallBack?
) {
    var handler: Handler = Handler(Looper.getMainLooper())
    // 优化：使用 4 线程并行加载，替代单线程串行
    private val service: ExecutorService = Executors.newFixedThreadPool(4)
    // 单独的线程池用于 SMB 操作，避免与主加载竞争
    private val smbService: ExecutorService = Executors.newFixedThreadPool(2)

    val resultMap: MutableMap<Long?, SpiderInfo?> = HashMap<Long?, SpiderInfo?>()

    fun execute() {
        service.execute {
            try {
                // 使用 invokeAll 提交所有任务并等待完成
                val callables = mList.map { info ->
                    java.util.concurrent.Callable {
                        getSpiderInfo(info)
                    }
                }

                // 最多等待 30 秒完成所有任务
                val results = service.invokeAll(callables, 30, TimeUnit.SECONDS)

                // 收集结果
                for (i in mList.indices) {
                    try {
                        val spiderInfo = results[i].get()
                        resultMap[mList[i].gid] = spiderInfo
                    } catch (e: Exception) {
                        // 任务超时或执行失败
                    }
                }
            } catch (e: Exception) {
                // 异常处理
            }

            // 回调结果
            handler.post {
                callBack?.resultCallBack(resultMap)
            }
        }
    }

    private fun getSpiderInfo(info: GalleryInfo): SpiderInfo? {
        var spiderInfo: SpiderInfo?

        // Step 1: 尝试从 SMB 读取（带 3 秒超时）
        spiderInfo = readFromSmb(info)
        if (spiderInfo != null) {
            return spiderInfo
        }

        // Step 2: 降级到本地文件系统
        val mDownloadDir = SpiderDen.getGalleryDownloadDir(info)
        if (mDownloadDir != null && mDownloadDir.isDirectory()) {
            val file = mDownloadDir.findFile(SpiderQueen.SPIDER_INFO_FILENAME)
            spiderInfo = SpiderInfo.read(file)
            if (spiderInfo != null && spiderInfo.gid == info.gid &&
                spiderInfo.token == info.token
            ) {
                return spiderInfo
            }
        }
        return null
    }

    /**
     * 尝试从 SMB 读取 SpiderInfo，带 3 秒超时控制
     * 使用单独的 SMB 线程池，避免与主加载线程竞争
     */
    private fun readFromSmb(info: GalleryInfo): SpiderInfo? {
        return try {
            val smbTask = smbService.submit<SpiderInfo?> {
                try {
                    val pipe = SmbFileHelper.getSmbFileInputStream(
                        info.gid,
                        SpiderQueen.SPIDER_INFO_FILENAME,
                        info
                    )
                    if (pipe != null) {
                        pipe.obtain()
                        val `is` = pipe.open()
                        val spiderInfo = SpiderInfo.read(`is`)
                        pipe.close()
                        pipe.release()

                        if (spiderInfo != null && spiderInfo.gid == info.gid &&
                            spiderInfo.token == info.token
                        ) {
                            spiderInfo
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }

            // 等待最多 3 秒，超时则返回 null
            smbTask.get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // 超时或其他异常，返回 null，后续降级到本地文件
            null
        }
    }
}

