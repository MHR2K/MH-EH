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

class DownloadSpiderInfoExecutor(
    private val mList: MutableList<DownloadInfo>,
    private val callBack: SpiderInfoReadCallBack?
) {
    var handler: Handler = Handler(Looper.getMainLooper())
    private val service: ExecutorService = Executors.newSingleThreadExecutor()

    val resultMap: MutableMap<Long?, SpiderInfo?> = HashMap<Long?, SpiderInfo?>()


    fun execute() {
        service.execute(Runnable {
            for (i in mList.indices) {
                val info = mList.get(i)
                resultMap.put(info.gid, getSpiderInfo(info))
            }
            handler.post(Runnable {
                if (callBack == null) {
                    return@Runnable
                }
                callBack.resultCallBack(resultMap)
            })
        })
    }

    private fun getSpiderInfo(info: GalleryInfo): SpiderInfo? {
        var spiderInfo: SpiderInfo?

        // Step 1: 尝试从 SMB 读取
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
     * 尝试从 SMB 读取 SpiderInfo
     */
    private fun readFromSmb(info: GalleryInfo): SpiderInfo? {
        return try {
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
                    return spiderInfo
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
