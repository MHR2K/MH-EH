/*
 * Copyright 2016 Hippo Seven
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

package com.hippo.ehviewer.ui.scene.download.part

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Resources
import android.os.AsyncTask
import android.util.Log
import android.util.SparseBooleanArray
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager
import com.hippo.app.CheckBoxDialogBuilder
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.DownloadLabel
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadService
import com.hippo.ehviewer.util.FavCountStore
import com.hippo.ehviewer.smb.Client
import com.hippo.ehviewer.smb.SmbPathResolver
import com.hippo.ehviewer.spider.SpiderDen.getExistingGalleryDownloadDir
import com.hippo.ehviewer.spider.SpiderDen.getGalleryDownloadDir
import com.hippo.ehviewer.smb.SmbServer
import com.hippo.ehviewer.smb.SmbServerStore
import com.hippo.ehviewer.smb.SmbStorageTracker
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.ui.scene.download.part.StorageDetector.StorageLocation
import com.hippo.ehviewer.widget.MyEasyRecyclerView
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.lib.yorozuya.ObjectUtils
import com.hippo.lib.yorozuya.collect.LongList
import com.hippo.unifile.UniFile
import com.hippo.util.IoThreadPoolExecutor
import com.hippo.widget.FabLayout
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 下载页 FAB 多选批量操作、删除/移动对话框、SMB 迁移与随机阅读。
 * 本地超集：批量获取收藏数、批量 CBZ 转换、GID 移动位置、
 * 仅删图片/仅添加下载项、删除阅读进度、SMB 三选项删除、全部失败。
 */
class DownloadBatchActions(private val mHost: Host?) {

    interface Host {
        fun getEHContext(): Context?
        val activity2: Activity?
        val recyclerView: MyEasyRecyclerView?
        val list: List<DownloadInfo>?
        val backList: MutableList<DownloadInfo>?
        val downloadManager: DownloadManager?
        val fabLayout: FabLayout?
        val dragDropManager: RecyclerViewDragDropManager?
        val spiderInfoMap: MutableMap<Long, SpiderInfo>
        val resources: Resources
        var label: String?

        fun getDownloadInfoAtAdapterPosition(adapterPosition: Int): DownloadInfo?
        fun getString(resId: Int): String
        fun getString(resId: Int, vararg formatArgs: Any?): String
        fun updateForLabel()
        fun updateView()
        fun updateAdapter()
        fun launchGalleryActivity(intent: Intent)
        fun onClickPrimaryFab(view: FabLayout, fab: FloatingActionButton?)
        fun onFavCountRefreshFinished()
    }

    fun showRefreshFavCountDialog(targets: List<DownloadInfo>? = null) {
        val ctx = mHost?.getEHContext() ?: return
        val candidateList = targets ?: mHost.backList ?: return
        val totalCount = candidateList.size
        if (totalCount == 0) {
            Toast.makeText(ctx, R.string.download_refresh_fav_count_none, Toast.LENGTH_SHORT).show()
            return
        }

        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        fun isNew(posted: String?): Boolean {
            if (posted.isNullOrEmpty()) return false
            return try {
                val t = df.parse(posted)?.time ?: 0L
                t > sevenDaysAgo
            } catch (e: Exception) {
                false
            }
        }

        var alreadyHave = 0
        var newItems = 0
        for (di in candidateList) {
            if (di.favoriteCount > 0) alreadyHave++
            if (isNew(di.posted)) newItems++
        }

        fun getItemsToFetch(force: Boolean, skipNew: Boolean): List<DownloadInfo> {
            val items = mutableListOf<DownloadInfo>()
            for (di in candidateList) {
                if (!force && di.favoriteCount > 0) continue
                if (skipNew && isNew(di.posted)) continue
                items.add(di)
            }
            return items
        }

        val dialogView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            val padTop = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, padTop, pad, 0)
        }

        val summaryTv = TextView(ctx).apply {
            textSize = 14f
            val initial = getItemsToFetch(false, true)
            text = mHost.getString(
                R.string.download_refresh_fav_count_summary,
                totalCount,
                initial.size,
                alreadyHave,
                newItems
            )
        }
        dialogView.addView(summaryTv)

        val forceCheckBox = CheckBox(ctx).apply {
            setText(R.string.download_refresh_fav_count_force)
            isChecked = false
        }
        dialogView.addView(forceCheckBox)

        val skipNewCheckBox = CheckBox(ctx).apply {
            setText(R.string.download_refresh_fav_count_skip_new)
            isChecked = true
        }
        dialogView.addView(skipNewCheckBox)

        val updateSummary = {
            val pending = getItemsToFetch(forceCheckBox.isChecked, skipNewCheckBox.isChecked)
            summaryTv.text = mHost.getString(
                R.string.download_refresh_fav_count_summary,
                totalCount,
                pending.size,
                alreadyHave,
                newItems
            )
        }

        forceCheckBox.setOnCheckedChangeListener { _, _ -> updateSummary() }
        skipNewCheckBox.setOnCheckedChangeListener { _, _ -> updateSummary() }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.download_refresh_fav_count)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val itemsToFetch = getItemsToFetch(forceCheckBox.isChecked, skipNewCheckBox.isChecked)
                if (itemsToFetch.isEmpty()) {
                    Toast.makeText(ctx, R.string.download_refresh_fav_count_none, Toast.LENGTH_SHORT).show()
                } else {
                    startBatchGetFavCount(itemsToFetch)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startBatchGetFavCount(items: List<DownloadInfo>) {
        val ctx = mHost?.getEHContext() ?: return
        val act = mHost.activity2 ?: return
        if (items.isEmpty()) {
            Toast.makeText(ctx, R.string.download_refresh_fav_count_none, Toast.LENGTH_SHORT).show()
            return
        }

        val cancelled = AtomicBoolean(false)
        val galleryInfoList = ArrayList<GalleryInfo>(items)

        val progressDialog = ProgressDialog(ctx).apply {
            setTitle(R.string.download_refresh_fav_count)
            setMessage(mHost.getString(R.string.download_refresh_fav_count_progress, 0, items.size))
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = items.size
            setCancelable(true)
            setOnCancelListener { cancelled.set(true) }
            setButton(
                DialogInterface.BUTTON_NEGATIVE,
                ctx.getString(android.R.string.cancel)
            ) { dialog, _ -> dialog.cancel() }
        }
        progressDialog.show()

        object : AsyncTask<Void, Int, IntArray>() {
            override fun doInBackground(vararg voids: Void): IntArray {
                return EhEngine.batchGetFavoriteCounts(
                    EhApplication.getOkHttpClient(ctx),
                    galleryInfoList,
                    2,    // 2并发（更平稳，降低风控封禁风险）
                    1000, // 批间间隔约1秒（带随机微扰抖动）
                    cancelled
                ) { current, total -> publishProgress(current, total) }
            }

            override fun onProgressUpdate(values: Array<out Int>) {
                if (values.size >= 2) {
                    val a = mHost.activity2
                    if (a != null && !a.isFinishing && !a.isDestroyed) {
                        progressDialog.progress = values[0]
                        progressDialog.setMessage(
                            mHost.getString(R.string.download_refresh_fav_count_progress, values[0], values[1])
                        )
                    }
                }
            }

            override fun onPostExecute(result: IntArray?) {
                val a = mHost.activity2
                if (a != null && !a.isFinishing && !a.isDestroyed) {
                    try {
                        progressDialog.dismiss()
                    } catch (ignored: Throwable) {
                    }
                }

                // 持久化到磁盘（即便单项获取时已写入内存/临时存储，此处执行全局对齐）
                if (mHost.backList != null) {
                    FavCountStore.saveFrom(mHost.backList)
                }

                // 通知完成，若当前处于收藏数排序则自动重排
                mHost.onFavCountRefreshFinished()

                if (result != null && result.isNotEmpty()) {
                    val success = result[0]
                    val total = result[1]
                    val stopReason = if (result.size >= 3) result[2] else EhEngine.STOP_REASON_COMPLETED

                    when (stopReason) {
                        EhEngine.STOP_REASON_RATE_LIMIT -> {
                            Toast.makeText(ctx, R.string.download_refresh_fav_count_abort_509, Toast.LENGTH_LONG).show()
                        }
                        EhEngine.STOP_REASON_SAD_PANDA -> {
                            Toast.makeText(ctx, R.string.download_refresh_fav_count_abort_sad_panda, Toast.LENGTH_LONG).show()
                        }
                        EhEngine.STOP_REASON_CONSECUTIVE_FAILURES -> {
                            Toast.makeText(ctx, R.string.download_refresh_fav_count_abort_failed, Toast.LENGTH_LONG).show()
                        }
                        EhEngine.STOP_REASON_CANCELLED -> {
                            Toast.makeText(ctx, "已中止，获取了 $success/$total 个", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            Toast.makeText(ctx, mHost.getString(R.string.download_refresh_fav_count_done, success), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }.execute()
    }

    fun convertBatchAsync(infos: List<DownloadInfo>, toCbz: Boolean) {
        val ctx = mHost?.getEHContext() ?: return
        Toast.makeText(ctx, mHost.getString(R.string.convert_in_progress, 0, infos.size), Toast.LENGTH_SHORT).show()
        object : AsyncTask<Void, Int, Int>() {
            val failed = mutableListOf<String>()

            override fun doInBackground(vararg voids: Void): Int {
                var done = 0
                for (di in infos) {
                    try {
                        val dir = getGalleryDownloadDir(di)
                        if (dir == null || !dir.isDirectory) { failed.add(di.title); done++; publishProgress(done); continue }
                        if (toCbz) {
                            // 若已有 CBZ 跳过
                            if (com.hippo.ehviewer.util.CbzUtils.findCbzFile(dir) != null) { done++; publishProgress(done); continue }
                            val gi = GalleryInfo()
                            gi.gid = di.gid; gi.title = di.title; gi.titleJpn = di.titleJpn; gi.category = di.category; gi.thumb = di.thumb
                            val pages = if (di.total > 0) di.total else di.pages
                            val tags = di.simpleTags ?: emptyArray<String>()
                            com.hippo.ehviewer.util.CbzUtils.createCbzWithComicInfo(dir, gi, pages, tags, true)
                        } else {
                            // 解包：若无 CBZ 则跳过
                            val cbz = com.hippo.ehviewer.util.CbzUtils.findCbzFile(dir)
                            if (cbz == null) { done++; publishProgress(done); continue }
                            com.hippo.ehviewer.util.CbzUtils.extractCbz(dir, true)
                        }
                        done++; publishProgress(done)
                    } catch (t: Throwable) {
                        failed.add(di.title)
                    }
                }
                return done
            }

            override fun onProgressUpdate(values: Array<out Int>) {
                Toast.makeText(ctx, mHost.getString(R.string.convert_in_progress, values[0], infos.size), Toast.LENGTH_SHORT).show()
            }

            override fun onPostExecute(result: Int?) {
                if (failed.isEmpty()) {
                    Toast.makeText(ctx, mHost.getString(R.string.convert_done, result!!, infos.size), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(ctx, mHost.getString(R.string.convert_done, result!!, infos.size), Toast.LENGTH_LONG).show()
                    for (f in failed) {
                        Toast.makeText(ctx, mHost.getString(R.string.convert_failed_for, f), Toast.LENGTH_SHORT).show()
                    }
                }
                // 退出选择模式并刷新
                mHost.recyclerView?.outOfCustomChoiceMode()
                mHost.updateForLabel()
                mHost.updateView()
            }
        }.executeOnExecutor(IoThreadPoolExecutor.instance)
    }

    fun onClickSecondaryFab(view: FabLayout, fab: FloatingActionButton, position: Int) {
        val context = mHost?.getEHContext() ?: return
        val activity = mHost.activity2 ?: return
        val recyclerView = mHost.recyclerView ?: return

        if (position == 0) {
            recyclerView.checkAll()
        } else {
            val list = mHost.list ?: return

            var gidList: LongList? = null
            var downloadInfoList: MutableList<DownloadInfo>? = null
            val collectGid = position == 1 || position == 2 || position == 3 // Start, Stop, Delete
            val collectDownloadInfo = position == 3 || position == 4 || position == 7 // Delete or Move (Change Label) or Move to SMB
            if (collectGid) {
                gidList = LongList()
            }
            if (collectDownloadInfo) {
                downloadInfoList = mutableListOf()
            }

            val stateArray: SparseBooleanArray = recyclerView.checkedItemPositions
            for (i in 0 until stateArray.size()) {
                if (stateArray.valueAt(i)) {
                    val info = mHost.getDownloadInfoAtAdapterPosition(stateArray.keyAt(i)) ?: continue
                    if (collectDownloadInfo) {
                        downloadInfoList!!.add(info)
                    }
                    if (collectGid) {
                        gidList!!.add(info.gid)
                    }
                }
            }

            when (position) {
                1 -> { // Start
                    if (gidList!!.isEmpty) return
                    val intent = Intent(activity, DownloadService::class.java)
                    intent.action = DownloadService.ACTION_START_RANGE
                    intent.putExtra(DownloadService.KEY_GID_LIST, gidList)
                    activity.startService(intent)
                    // Cancel check mode
                    recyclerView.outOfCustomChoiceMode()
                }
                2 -> { // Stop
                    if (gidList!!.isEmpty) return
                    mHost.downloadManager?.stopRangeDownload(gidList)
                    // Cancel check mode
                    recyclerView.outOfCustomChoiceMode()
                }
                3 -> { // Delete
                    if (downloadInfoList!!.isEmpty()) return
                    val selectedGidList = gidList!!
                    val selectedInfoList = downloadInfoList

                    // 三个单选选项
                    val options = arrayOf(
                        mHost.getString(R.string.download_remove_option_remove_all),
                        mHost.getString(R.string.download_remove_option_keep_item),
                        mHost.getString(R.string.download_remove_option_images_only)
                    )

                    // 默认选中第一项
                    val selectedOption = intArrayOf(0)

                    // 创建自定义布局
                    val dialogView = android.view.LayoutInflater.from(context)
                        .inflate(R.layout.dialog_delete_download, null)
                    val listView = dialogView.findViewById<ListView>(R.id.list_view)
                    val deleteReadingProgressCheckBox = dialogView.findViewById<CheckBox>(R.id.checkbox_delete_reading_progress)

                    // 设置单选列表
                    listView.setAdapter(ArrayAdapter(context, R.layout.item_select_dialog_radio, options))
                    listView.choiceMode = ListView.CHOICE_MODE_SINGLE
                    listView.setItemChecked(0, true)

                    // 设置复选框文本和默认状态（默认选中第一项时勾选）
                    deleteReadingProgressCheckBox.setText(R.string.download_remove_option_delete_reading_progress)
                    deleteReadingProgressCheckBox.isChecked = true // 默认选中第一项，所以默认勾选

                    // 监听列表选择变化，动态更新复选框默认状态
                    listView.setOnItemClickListener { _, _, itemPosition, _ ->
                        selectedOption[0] = itemPosition
                        // 更新复选框默认状态：选择第一项时默认勾选，其他选项默认不勾选
                        deleteReadingProgressCheckBox.isChecked = itemPosition == 0
                    }

                    val count = selectedInfoList.size
                    AlertDialog.Builder(context)
                        .setTitle(mHost.getString(R.string.download_remove_dialog_title_2, count))
                        .setView(dialogView)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            // 退出选择模式
                            mHost.recyclerView?.outOfCustomChoiceMode()

                            val option = selectedOption[0]
                            val deleteReadingProgress = deleteReadingProgressCheckBox.isChecked

                            // 0: 移除下载项并删除文件夹
                            // 1: 仅删除文件夹（保留下载项）
                            // 2: 仅删除本地图片

                            if (option == 0 || option == 1) {
                                // 删除整个文件夹（本地 + SMB）
                                val fileList = mutableListOf<UniFile>()
                                val smbTargets = mutableListOf<Pair<DownloadInfo, Client.Target>>()
                                val smbGidSet = mutableSetOf<Long>()

                                for (info in selectedInfoList) {
                                    // 处理本地文件
                                    val dir = getGalleryDownloadDir(info)
                                    if (dir != null) {
                                        fileList.add(dir)
                                    }

                                    // 检查是否在 SMB 上（必须在清除路径映射前推导 Target）
                                    val smbTarget = SmbPathResolver.resolve(info.gid)
                                    val isOnSmb = smbTarget != null && (SmbStorageTracker.isOnSmb(info.gid) || dir == null || !dir.exists())
                                    if (isOnSmb) {
                                        smbTargets.add(info to smbTarget)
                                        smbGidSet.add(info.gid)
                                    } else {
                                        // 纯本地漫画：清除路径映射
                                        EhDB.removeDownloadDirname(info.gid)

                                        if (option == 1) {
                                            // 仅删除文件夹（保留下载项）：重置状态以便重新下载
                                            info.state = DownloadInfo.STATE_NONE
                                            info.finished = 0
                                            info.downloaded = 0
                                            info.speed = 0
                                            info.remaining = 0
                                            if (info.total < 0) info.total = 0
                                            EhDB.putDownloadInfo(info)
                                            StorageDetector.updateCache(info.gid, StorageLocation.UNKNOWN)
                                        }
                                    }
                                }

                                // 纯本地漫画立即清理下载记录和阅读进度
                                val localGidList = LongList()
                                for (info in selectedInfoList) {
                                    if (!smbGidSet.contains(info.gid)) {
                                        localGidList.add(info.gid)
                                        if (deleteReadingProgress) {
                                            EhApplication.getSpiderInfoRepository(context).delete(info.gid, context)
                                            mHost.spiderInfoMap.remove(info.gid)
                                        }
                                    }
                                }
                                if (option == 0 && !localGidList.isEmpty) {
                                    mHost.downloadManager?.deleteRangeDownload(localGidList)
                                }

                                // 删除本地文件
                                if (fileList.isNotEmpty()) {
                                    deleteFileAsync(*fileList.toTypedArray())
                                }

                                // 删除 SMB 文件（异步）
                                if (smbTargets.isNotEmpty()) {
                                    object : AsyncTask<Void, Void, Pair<List<DownloadInfo>, List<DownloadInfo>>>() {
                                        override fun doInBackground(vararg voids: Void): Pair<List<DownloadInfo>, List<DownloadInfo>> {
                                            val succeeded = mutableListOf<DownloadInfo>()
                                            val failed = mutableListOf<DownloadInfo>()

                                            for ((info, target) in smbTargets) {
                                                try {
                                                    // 获取服务器密码
                                                    val server = SmbServerStore.findByAuthority(target.authority)
                                                    if (server == null) {
                                                        Log.e("DownloadsScene", "未找到 SMB 服务器配置: authority=${target.authority}")
                                                        failed.add(info)
                                                        continue
                                                    }

                                                    val success = Client.withTempPassword(
                                                        target.authority,
                                                        server.password
                                                    ) {
                                                        try {
                                                            deleteSmbDirectoryRecursively(target)
                                                            true
                                                        } catch (t: Throwable) {
                                                            Log.e("DownloadsScene", "删除 SMB 目录失败: gid=${info.gid}", t)
                                                            false
                                                        }
                                                    }

                                                    if (success == true) {
                                                        // 远端删除成功：清除远端标记和本地路径映射
                                                        SmbStorageTracker.markLocal(info.gid)
                                                        EhDB.removeDownloadDirname(info.gid)
                                                        succeeded.add(info)
                                                    } else {
                                                        failed.add(info)
                                                    }
                                                } catch (e: Exception) {
                                                    Log.e("DownloadsScene", "删除 SMB 文件异常: gid=${info.gid}", e)
                                                    failed.add(info)
                                                }
                                            }
                                            return Pair(succeeded, failed)
                                        }

                                        override fun onPostExecute(result: Pair<List<DownloadInfo>, List<DownloadInfo>>) {
                                            val (succeeded, failed) = result

                                            if (succeeded.isNotEmpty()) {
                                                if (option == 0) {
                                                    val succeededGids = LongList(succeeded.size)
                                                    for (info in succeeded) {
                                                        succeededGids.add(info.gid)
                                                    }
                                                    mHost.downloadManager?.deleteRangeDownload(succeededGids)
                                                } else if (option == 1) {
                                                    for (info in succeeded) {
                                                        info.state = DownloadInfo.STATE_NONE
                                                        info.finished = 0
                                                        info.downloaded = 0
                                                        info.speed = 0
                                                        info.remaining = 0
                                                        if (info.total < 0) info.total = 0
                                                        EhDB.putDownloadInfo(info)
                                                        StorageDetector.updateCache(info.gid, StorageLocation.UNKNOWN)
                                                    }
                                                }

                                                if (deleteReadingProgress) {
                                                    for (info in succeeded) {
                                                        EhApplication.getSpiderInfoRepository(context).delete(info.gid, context)
                                                        mHost.spiderInfoMap.remove(info.gid)
                                                    }
                                                }
                                            }

                                            // 失败的项保留在列表中（保持其 SMB 标记与下载记录）
                                            val okCount = succeeded.size
                                            val failCount = failed.size
                                            if (okCount > 0 && failCount == 0) {
                                                Toast.makeText(context, "已删除 $okCount 个 SMB 目录", Toast.LENGTH_SHORT).show()
                                            } else if (okCount > 0 && failCount > 0) {
                                                Toast.makeText(context, "已删除 $okCount 个 SMB 目录，${failCount} 个失败（已保留条目）", Toast.LENGTH_LONG).show()
                                            } else if (failCount > 0) {
                                                Toast.makeText(context, "删除 SMB 目录失败 (${failCount} 项，已保留条目)", Toast.LENGTH_LONG).show()
                                            }

                                            // 刷新界面
                                            mHost.updateForLabel()
                                            mHost.updateView()
                                        }
                                    }.executeOnExecutor(IoThreadPoolExecutor.instance)
                                }
                            }

                            if (option == 2) {
                                // 仅删除本地图片和归档文件（保留 info.json 等其他文件）
                                val smbTargets = mutableListOf<Pair<DownloadInfo, Client.Target>>()
                                val smbGidSet = mutableSetOf<Long>()

                                for (info in selectedInfoList) {
                                    // 处理本地文件
                                    val dir = getGalleryDownloadDir(info)
                                    if (dir != null) {
                                        val files = dir.listFiles()
                                        if (files != null) {
                                            val imageFiles = mutableListOf<UniFile>()
                                            for (file in files) {
                                                if (file.isFile && isComicImageOrArchive(file.name)) {
                                                    imageFiles.add(file)
                                                }
                                            }
                                            if (imageFiles.isNotEmpty()) {
                                                deleteFileAsync(*imageFiles.toTypedArray())
                                            }
                                        }
                                    }

                                    // 检查是否在 SMB 上
                                    val smbTarget = SmbPathResolver.resolve(info.gid)
                                    val isOnSmb = smbTarget != null && (SmbStorageTracker.isOnSmb(info.gid) || dir == null || !dir.exists())
                                    if (isOnSmb) {
                                        smbTargets.add(info to smbTarget)
                                        smbGidSet.add(info.gid)
                                    } else {
                                        // 纯本地漫画：重置下载状态
                                        info.state = DownloadInfo.STATE_NONE
                                        info.finished = 0
                                        info.downloaded = 0
                                        info.speed = 0
                                        info.remaining = 0
                                        if (info.total < 0) info.total = 0
                                        EhDB.putDownloadInfo(info)
                                        if (deleteReadingProgress) {
                                            EhApplication.getSpiderInfoRepository(context).delete(info.gid, context)
                                            mHost.spiderInfoMap.remove(info.gid)
                                        }
                                    }
                                }

                                // 删除 SMB 图片和归档文件（异步）
                                if (smbTargets.isNotEmpty()) {
                                    object : AsyncTask<Void, Void, Pair<List<DownloadInfo>, List<DownloadInfo>>>() {
                                        override fun doInBackground(vararg voids: Void): Pair<List<DownloadInfo>, List<DownloadInfo>> {
                                            val succeeded = mutableListOf<DownloadInfo>()
                                            val failed = mutableListOf<DownloadInfo>()

                                            for ((info, target) in smbTargets) {
                                                try {
                                                    // 获取服务器密码
                                                    val server = SmbServerStore.findByAuthority(target.authority)
                                                    if (server == null) {
                                                        failed.add(info)
                                                        continue
                                                    }

                                                    val success = Client.withTempPassword(
                                                        target.authority,
                                                        server.password
                                                    ) {
                                                        try {
                                                            deleteSmbImageFilesOnly(target)
                                                            true
                                                        } catch (t: Throwable) {
                                                            Log.e("DownloadsScene", "删除 SMB 图片失败: gid=${info.gid}", t)
                                                            false
                                                        }
                                                    }

                                                    if (success == true) {
                                                        succeeded.add(info)
                                                    } else {
                                                        failed.add(info)
                                                    }
                                                } catch (e: Exception) {
                                                    Log.e("DownloadsScene", "删除 SMB 图片异常: gid=${info.gid}", e)
                                                    failed.add(info)
                                                }
                                            }
                                            return Pair(succeeded, failed)
                                        }

                                        override fun onPostExecute(result: Pair<List<DownloadInfo>, List<DownloadInfo>>) {
                                            val (succeeded, failed) = result

                                            for (info in succeeded) {
                                                info.state = DownloadInfo.STATE_NONE
                                                info.finished = 0
                                                info.downloaded = 0
                                                info.speed = 0
                                                info.remaining = 0
                                                if (info.total < 0) info.total = 0
                                                EhDB.putDownloadInfo(info)
                                                if (deleteReadingProgress) {
                                                    EhApplication.getSpiderInfoRepository(context).delete(info.gid, context)
                                                    mHost.spiderInfoMap.remove(info.gid)
                                                }
                                            }

                                            val okCount = succeeded.size
                                            val failCount = failed.size
                                            if (okCount > 0 && failCount == 0) {
                                                Toast.makeText(context, "已删除 $okCount 个 SMB 目录的图片", Toast.LENGTH_SHORT).show()
                                            } else if (okCount > 0 && failCount > 0) {
                                                Toast.makeText(context, "已删除 $okCount 个 SMB 图片，${failCount} 个失败（已保留状态）", Toast.LENGTH_LONG).show()
                                            } else if (failCount > 0) {
                                                Toast.makeText(context, "删除 SMB 图片失败 (${failCount} 项，已保留状态)", Toast.LENGTH_LONG).show()
                                            }

                                            mHost.updateForLabel()
                                            mHost.updateView()
                                        }
                                    }.executeOnExecutor(IoThreadPoolExecutor.instance)
                                }
                            }

                            // 刷新界面
                            mHost.updateForLabel()
                            mHost.updateView()
                        }
                        .show()
                }
                4 -> { // Move (Change Label)
                    if (downloadInfoList!!.isEmpty()) return
                    val labelRawList: List<DownloadLabel> = EhApplication.getDownloadManager(context).labelList
                    val labelList = ArrayList<String>(labelRawList.size + 1)
                    labelList.add(mHost.getString(R.string.default_download_label_name))
                    for (i in labelRawList.indices) {
                        labelList.add(labelRawList[i].label)
                    }
                    val labels = labelList.toTypedArray()

                    val helper = MoveDialogHelper(labels, downloadInfoList)

                    AlertDialog.Builder(context)
                        .setTitle(R.string.download_move_dialog_title)
                        .setItems(labels, helper)
                        .show()
                }
                5 -> { // Random
                    if (mHost.list.isNullOrEmpty()) return
                    mHost.onClickPrimaryFab(mHost.fabLayout!!, null)
                    viewRandom()
                }
                6 -> { // Toggle drag-and-drop
                    setDragEnable(fab)
                }
                7 -> { // SMB Migration (now last)
                    if (downloadInfoList!!.isEmpty()) return

                    // 显示选择对话框：移动到SMB 或 从SMB移动到本地
                    val ctxFinal = context
                    val infosFinal = downloadInfoList

                    val migrationOptions = arrayOf<CharSequence>(
                        "移动到 SMB 服务器",
                        "从 SMB 移动到本地"
                    )

                    AlertDialog.Builder(context)
                        .setTitle("SMB 存储迁移")
                        .setItems(migrationOptions) { _, which ->
                            if (which == 0) {
                                // 移动到 SMB
                                val servers = SmbServerStore.list()
                                if (servers.isNullOrEmpty()) {
                                    Toast.makeText(ctxFinal, "请先在 设置>高级>添加 SMB 服务器", Toast.LENGTH_LONG).show()
                                    return@setItems
                                }
                                val choices = Array<CharSequence>(servers.size) { i ->
                                    val s = servers[i]
                                    val a = s.authority
                                    val userPart = if (!a.domain.isNullOrEmpty()) "${a.domain}\\\\${a.username}" else a.username
                                    val portPart = if (a.port != com.hippo.ehviewer.smb.Authority.DEFAULT_PORT) ":${a.port}" else ""
                                    val path = s.relativePath
                                    val pathPart = if (path.isNullOrEmpty()) "" else "/" + path.replace('\\', '/')
                                    val url = "smb://$userPart:${s.password}@${a.host}$portPart$pathPart"
                                    (if (s.name != null) "${s.name}: " else "") + url
                                }

                                AlertDialog.Builder(ctxFinal)
                                    .setTitle("选择目标 SMB 服务器")
                                    .setItems(choices) { _, whichIdx ->
                                        val server = servers[whichIdx]
                                        migrateToSmbAsync(ctxFinal, infosFinal, server)
                                    }
                                    .show()
                            } else if (which == 1) {
                                // 从 SMB 移动到本地
                                // 筛选出在SMB上的下载项
                                val smbInfos = mutableListOf<DownloadInfo>()
                                for (info in infosFinal) {
                                    val smbCheck = SmbPathResolver.resolve(info.gid)
                                    if (smbCheck != null) {
                                        smbInfos.add(info)
                                    }
                                }

                                if (smbInfos.isEmpty()) {
                                    Toast.makeText(ctxFinal, "所选漫画均不在 SMB 上", Toast.LENGTH_SHORT).show()
                                    return@setItems
                                }

                                AlertDialog.Builder(ctxFinal)
                                    .setTitle("从 SMB 移动到本地")
                                    .setMessage("确定要将 ${smbInfos.size} 个漫画从 SMB 移动到本地存储吗？")
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .setPositiveButton(android.R.string.ok) { _, _ ->
                                        moveFromSmbToLocalAsync(ctxFinal, smbInfos)
                                    }
                                    .show()
                            }
                        }
                        .show()
                }
            }
        }
    }

    private fun setDragEnable(fab: FloatingActionButton) {
        DownloadAdapter.DRAG_ENABLE = !DownloadAdapter.DRAG_ENABLE
        Settings.setDragDownloadGallery(DownloadAdapter.DRAG_ENABLE)
        val context = mHost?.getEHContext() ?: return
        if (DownloadAdapter.DRAG_ENABLE) {
            fab.setImageDrawable(ResourcesCompat.getDrawable(mHost.resources, R.drawable.v_mobile_hand_left_x24, context.theme))
        } else {
            fab.setImageDrawable(ResourcesCompat.getDrawable(mHost.resources, R.drawable.v_mobile_hand_left_off_x24, context.theme))
        }
    }

    private fun migrateToSmbAsync(context: Context, infos: List<DownloadInfo>, server: SmbServer) {
        // 取消选择模式
        mHost?.recyclerView?.outOfCustomChoiceMode()
        object : AsyncTask<Void, Int, Int>() {
            override fun doInBackground(vararg voids: Void): Int {
                var okCount = 0
                for (info in infos) {
                    try {
                        val dir = getGalleryDownloadDir(info) ?: continue
                        if (!dir.isDirectory) continue
                        val dirname = dir.name ?: continue
                        val base = server.toTarget() ?: continue
                        val basePath = joinPath(base.pathInShare, dirname)
                        val targetBase = Client.Target(base.authority, base.share, basePath)
                        val success = Client.withTempPassword(targetBase.authority, server.password) {
                            try {
                                Client.mkdirs(targetBase)
                                val children = dir.listFiles()
                                if (children != null) {
                                    for (child in children) {
                                        if (child == null || child.isDirectory) continue
                                        val name = child.name
                                        var ins: InputStream? = null
                                        try {
                                            ins = child.openInputStream()
                                            Client.upload(
                                                Client.Target(targetBase.authority, targetBase.share, joinPath(targetBase.pathInShare, name)),
                                                ins,
                                                true
                                            )
                                        } finally {
                                            IOUtils.closeQuietly(ins)
                                        }
                                    }
                                }
                                true
                            } catch (t: Throwable) {
                                t.printStackTrace()
                                false
                            }
                        }
                        if (success) {
                            // 删除本地目录以实现"移动"效果
                            dir.delete()
                            SmbStorageTracker.markOnSmb(info.gid)
                            // 更新存储位置缓存：从本地迁移到 SMB
                            StorageDetector.updateCache(info.gid, StorageLocation.SMB)
                            okCount++
                        }
                    } catch (t: Throwable) {
                        t.printStackTrace()
                    }
                }
                return okCount
            }

            override fun onPostExecute(okCount: Int?) {
                Toast.makeText(context, "已移动漫画到 SMB：$okCount/${infos.size}", Toast.LENGTH_LONG).show()
                // 刷新界面
                mHost?.updateForLabel()
                mHost?.updateView()
            }
        }.executeOnExecutor(IoThreadPoolExecutor.instance)
    }

    private fun moveFromSmbToLocalAsync(context: Context, infos: List<DownloadInfo>) {
        // 取消选择模式
        mHost?.recyclerView?.outOfCustomChoiceMode()

        Toast.makeText(context, "开始从 SMB 移动到本地...", Toast.LENGTH_SHORT).show()

        object : AsyncTask<Void, Int, Int>() {
            override fun doInBackground(vararg voids: Void): Int {
                var okCount = 0
                for (info in infos) {
                    try {
                        // 通过 dirname 推导 SMB 路径
                        val smbTarget = SmbPathResolver.resolve(info.gid) ?: continue

                        // 获取对应的 SMB 服务器配置（用于密码）
                        val server = SmbServerStore.findByAuthority(smbTarget.authority)
                        if (server == null) {
                            Log.e("DownloadsScene", "找不到匹配的 SMB 服务器配置")
                            continue
                        }

                        // 创建本地目标目录
                        val localDir = Settings.getDownloadLocation() ?: continue

                        // 从 SMB 路径中提取文件夹名称
                        var dirname: String? = smbTarget.pathInShare
                        if (dirname != null && dirname.contains("\\")) {
                            val parts = dirname.split("\\\\")
                            dirname = parts[parts.size - 1]
                        } else if (dirname != null && dirname.contains("/")) {
                            val parts = dirname.split("/")
                            dirname = parts[parts.size - 1]
                        }

                        if (dirname.isNullOrEmpty()) {
                            dirname = info.gid.toString()
                        }

                        val targetDir = localDir.createDirectory(dirname)
                        if (targetDir == null || !targetDir.ensureDir()) {
                            Log.e("DownloadsScene", "无法创建本地目录: $dirname")
                            continue
                        }

                        // 从 SMB 下载文件到本地
                        val success = Client.withTempPassword(
                            smbTarget.authority,
                            server.password
                        ) {
                            try {
                                val entries = Client.listDirectory(smbTarget)
                                if (entries.isNullOrEmpty()) {
                                    return@withTempPassword false
                                }

                                for (entry in entries) {
                                    // 只下载文件，跳过目录
                                    if (entry.isDirectory) continue

                                    val fileName = entry.name
                                    val fileTarget = Client.Target(
                                        smbTarget.authority,
                                        smbTarget.share,
                                        joinPath(smbTarget.pathInShare, fileName)
                                    )

                                    val localFile = targetDir.createFile(fileName) ?: continue

                                    var ins: InputStream? = null
                                    var os: OutputStream? = null
                                    try {
                                        ins = Client.openInputStream(fileTarget)
                                        os = localFile.openOutputStream()

                                        val buffer = ByteArray(8192)
                                        var bytesRead: Int
                                        while (ins.read(buffer).also { bytesRead = it } != -1) {
                                            os.write(buffer, 0, bytesRead)
                                        }
                                        os.flush()
                                    } finally {
                                        IOUtils.closeQuietly(ins)
                                        IOUtils.closeQuietly(os)
                                    }
                                }
                                true
                            } catch (t: Throwable) {
                                t.printStackTrace()
                                false
                            }
                        }

                        if (success) {
                            // 更新下载路径
                            EhDB.putDownloadDirname(info.gid, dirname)
                            SmbStorageTracker.markLocal(info.gid)
                            // 更新存储位置缓存：从 SMB 迁移到本地
                            StorageDetector.updateCache(info.gid, StorageLocation.LOCAL)

                            // 可选：删除 SMB 上的文件（实现"移动"效果）
                            try {
                                Client.withTempPassword(
                                    smbTarget.authority,
                                    server.password
                                ) {
                                    try {
                                        deleteSmbDirectoryRecursively(smbTarget)
                                        true
                                    } catch (t: Throwable) {
                                        Log.e("DownloadsScene", "删除 SMB 目录失败: $dirname", t)
                                        false
                                    }
                                }
                            } catch (t: Throwable) {
                                // 删除失败不影响主流程
                                Log.e("DownloadsScene", "删除 SMB 目录异常: $dirname", t)
                            }

                            okCount++
                        }
                    } catch (t: Throwable) {
                        t.printStackTrace()
                    }
                }
                return okCount
            }

            override fun onPostExecute(okCount: Int?) {
                Toast.makeText(context, "已从 SMB 移动到本地：$okCount/${infos.size}", Toast.LENGTH_LONG).show()
                // 刷新界面
                mHost?.updateForLabel()
                mHost?.updateView()
            }
        }.executeOnExecutor(IoThreadPoolExecutor.instance)
    }

    /**
     * 显示"移动位置"对话框
     * @param context 上下文
     * @param sourceInfo 源下载项信息
     * @param sourcePosition 源项在列表中的位置
     */
    fun showMoveToPositionDialog(context: Context, sourceInfo: DownloadInfo, sourcePosition: Int) {
        // 使用对话框消息显示提示文本（支持自动换行），输入框只用于输入 gid
        val editText = EditText(context)
        editText.hint = ""
        editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        editText.isSingleLine = true
        // 居中显示输入的数字
        editText.gravity = Gravity.CENTER
        editText.textAlignment = View.TEXT_ALIGNMENT_CENTER
        // 使输入框宽度填充对话框，便于居中显示
        editText.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val builder = AlertDialog.Builder(context)
            .setTitle(R.string.move_to_position_title)
            .setMessage(mHost!!.getString(R.string.move_to_position_hint))
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // 先收起键盘，避免窗口重建导致布局异常
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(editText.windowToken, 0)
                val gidInput = editText.text.toString().trim()
                moveToPosition(context, sourceInfo, sourcePosition, gidInput)
            }
            .setNegativeButton(android.R.string.cancel, null)

        // 显示对话框
        val dialog = builder.show()
        // 对话框关闭时确保键盘收起
        dialog.setOnDismissListener {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(editText.windowToken, 0)
        }
    }

    /**
     * 执行移动操作
     * @param context 上下文
     * @param sourceInfo 源下载项
     * @param sourcePosition 源项在列表中的位置
     * @param targetGidInput 目标gid输入（可为空或输入0）
     */
    private fun moveToPosition(context: Context, sourceInfo: DownloadInfo, sourcePosition: Int, targetGidInput: String) {
        val list = mHost?.list
        val backList = mHost?.backList
        if (list == null || backList == null) {
            Toast.makeText(context, R.string.move_to_position_error, Toast.LENGTH_SHORT).show()
            return
        }

        if (targetGidInput.isEmpty()) {
            // 留空，移动到默认下载标签的最前端（最新位置）
            if (mHost.downloadManager != null) {
                // 使用用户设置的默认下载标签
                val defaultLabel = Settings.getDefaultDownloadLabel()

                // 先更新源项目的时间为当前时间，这样 changeLabel 排序后会在最前面
                sourceInfo.time = System.currentTimeMillis()
                EhDB.putDownloadInfo(sourceInfo)

                val singleInfoList = mutableListOf(sourceInfo)
                mHost.downloadManager!!.changeLabel(singleInfoList, defaultLabel)

                // 如果当前不在默认下载标签视图，切换到默认下载标签
                if (!ObjectUtils.equal(mHost.label, defaultLabel)) {
                    mHost.label = defaultLabel
                }
                mHost.updateForLabel()

                Toast.makeText(context, R.string.move_to_position_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.move_to_position_error, Toast.LENGTH_SHORT).show()
            }
            return
        } else if (targetGidInput == "0") {
            // 输入0，移到当前标签列表的最前面（该标签最新的位置）
            sourceInfo.time = System.currentTimeMillis() + 1
            EhDB.putDownloadInfo(sourceInfo)
            backList.sortWith { a, b -> b.time.compareTo(a.time) }
            if (list !== backList) {
                val srcIdx = list.indexOfFirst { it.gid == sourceInfo.gid }
                if (srcIdx != -1) (list as MutableList).removeAt(srcIdx)
                (list as MutableList).add(0, sourceInfo)
            }
            mHost.updateForLabel()
            Toast.makeText(context, R.string.move_to_position_success, Toast.LENGTH_SHORT).show()
            return
        } else {
            // 输入GID，移到该项后面
            try {
                val targetGid = targetGidInput.toLong()

                // 在 backList 中找到目标项和源项
                var targetIdx = -1
                var sourceIdx = -1
                for (i in backList.indices) {
                    if (backList[i].gid == targetGid) {
                        targetIdx = i
                    }
                    if (backList[i].gid == sourceInfo.gid) {
                        sourceIdx = i
                    }
                }

                if (targetIdx == -1) {
                    Toast.makeText(context, R.string.move_to_position_error, Toast.LENGTH_SHORT).show()
                    return
                }

                // 避免移到相邻位置（已在目标后面）
                if (sourceIdx == targetIdx + 1) {
                    return
                }

                // 计算源项的新 time：使其排在目标项之后
                // 列表按 time 降序排列，源的 time 需要小于目标的 time
                val targetTime = backList[targetIdx].time
                val newTime = if (targetIdx + 1 < backList.size) {
                    // 目标有后继项：取目标和后继的 time 中间值
                    (targetTime + backList[targetIdx + 1].time) / 2
                } else {
                    // 目标是最后一项：取目标 time - 1
                    targetTime - 1
                }

                // 更新源项的 time 并写入数据库
                sourceInfo.time = newTime
                EhDB.putDownloadInfo(sourceInfo)

                // backList 按 time 降序重排
                backList.sortWith { a, b -> b.time.compareTo(a.time) }

                // 同步更新 list（筛选列表）
                if (list !== backList) {
                    // 通过 GID 找到源项在 list 中的位置
                    val sourceIndexInList = list.indexOfFirst { it.gid == sourceInfo.gid }
                    if (sourceIndexInList != -1) {
                        (list as MutableList).removeAt(sourceIndexInList)
                        // 用目标 GID 在 list 中定位插入点（插入到目标之后）
                        var insertIndex = list.size
                        for (i in list.indices) {
                            if (list[i].gid == targetGid) {
                                insertIndex = i + 1
                                break
                            }
                        }
                        (list as MutableList).add(insertIndex, sourceInfo)
                    }
                }
                mHost.updateForLabel()
                Toast.makeText(context, R.string.move_to_position_success, Toast.LENGTH_SHORT).show()
            } catch (e: NumberFormatException) {
                Toast.makeText(context, R.string.move_to_position_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun viewRandom() {
        val list = mHost?.list ?: return
        val position = (Math.random() * list.size).toInt()
        if (position < 0 || position >= list.size) return
        val activity = mHost.activity2 ?: return
        if (mHost.recyclerView == null) return

        val intent = Intent(activity, GalleryActivity::class.java)
        intent.action = GalleryActivity.ACTION_EH
        intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, list[position])
        mHost.launchGalleryActivity(intent)
    }

    private inner class DeleteDialogHelper(
        private val mGalleryInfo: GalleryInfo,
        private val mBuilder: CheckBoxDialogBuilder
    ) : DialogInterface.OnClickListener {

        override fun onClick(dialog: DialogInterface, which: Int) {
            if (which != DialogInterface.BUTTON_POSITIVE) return

            // Delete
            mHost?.downloadManager?.deleteDownload(mGalleryInfo.gid)

            // Delete image files
            val checked = mBuilder.isChecked
            Settings.putRemoveImageFiles(checked)
            if (checked) {
                val file = getExistingGalleryDownloadDir(mGalleryInfo)
                EhDB.removeDownloadDirname(mGalleryInfo.gid)
                if (file != null) {
                    deleteFileAsync(file)
                } else {
                    deleteGalleryFilesAsync(listOf(mGalleryInfo))
                }
            }
        }
    }

    private inner class DeleteRangeDialogHelper(
        private val mDownloadInfoList: List<DownloadInfo>,
        private val mGidList: LongList,
        private val mBuilder: CheckBoxDialogBuilder
    ) : DialogInterface.OnClickListener {

        override fun onClick(dialog: DialogInterface, which: Int) {
            if (which != DialogInterface.BUTTON_POSITIVE) return

            // Cancel check mode
            mHost?.recyclerView?.outOfCustomChoiceMode()

            // Delete
            mHost?.downloadManager?.deleteRangeDownload(mGidList)

            // Delete image files
            val checked = mBuilder.isChecked
            Settings.putRemoveImageFiles(checked)
            if (checked) {
                deleteGalleryFilesAsync(mDownloadInfoList)
            }
        }
    }

    private inner class MoveDialogHelper(
        private val mLabels: Array<String>,
        private val mDownloadInfoList: List<DownloadInfo>
    ) : DialogInterface.OnClickListener {

        override fun onClick(dialog: DialogInterface, which: Int) {
            // Cancel check mode
            val context = mHost?.getEHContext() ?: return
            mHost.recyclerView?.outOfCustomChoiceMode()

            val label: String? = if (which == 0) null else mLabels[which]
            EhApplication.getDownloadManager(context).changeLabel(mDownloadInfoList, label)
        }
    }

    companion object {

        /**
         * 递归删除 SMB 目录及其所有内容
         * 参考 MaterialFiles 的实现，先删除文件，再删除子目录，最后删除目录本身
         */
        @Throws(Exception::class)
        private fun deleteSmbDirectoryRecursively(target: Client.Target) {
            try {
                // 列出目录内容
                val entries = try {
                    Client.listDirectory(target)
                } catch (e: IOException) {
                    val cause = e.cause
                    if (cause is SMBApiException) {
                        val status = cause.status
                        if (status == NtStatus.STATUS_NO_SUCH_FILE ||
                            status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND ||
                            status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND
                        ) {
                            // 远端目录已不存在，视作已删除
                            return
                        }
                    }
                    throw e
                }

                if (!entries.isNullOrEmpty()) {
                    // 先删除所有文件和子目录
                    for (entry in entries) {
                        val entryTarget = Client.Target(
                            target.authority,
                            target.share,
                            joinPath(target.pathInShare, entry.name)
                        )

                        if (entry.isDirectory) {
                            // 递归删除子目录
                            deleteSmbDirectoryRecursively(entryTarget)
                        } else {
                            // 删除文件
                            Client.delete(entryTarget)
                        }
                    }
                }

                // 最后删除目录本身（此时目录应该已经为空）
                Client.deleteDirectory(target)
            } catch (e: Exception) {
                Log.e("DownloadsScene", "删除 SMB 路径失败: ${target.pathInShare}", e)
                throw e
            }
        }

        private fun joinPath(base: String?, name: String?): String {
            var b = base?.trim() ?: ""
            var n = name?.trim() ?: ""
            if (b.isEmpty()) return n
            if (n.isEmpty()) return b
            val sep = '\\'
            b = b.replace('/', sep).replace(Regex("\\\\+$"), "")
            n = n.replace('/', sep).replace(Regex("^\\\\+"), "")
            return "$b$sep$n"
        }

        /**
         * 判定文件是否为漫画正文图片或打包归档（.cbz, .zip, .cbr）
         */
        private fun isComicImageOrArchive(name: String?): Boolean {
            if (name == null) return false
            val lower = name.lowercase(Locale.ROOT)
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                    lower.endsWith(".png") || lower.endsWith(".gif") ||
                    lower.endsWith(".webp") || lower.endsWith(".bmp") ||
                    lower.endsWith(".cbz") || lower.endsWith(".zip") ||
                    lower.endsWith(".cbr")
        }

        /**
         * 只删除 SMB 目录中的图片与漫画归档文件（保留其他文件如 info.json, .thumb, .ehviewer）
         */
        @Throws(Exception::class)
        private fun deleteSmbImageFilesOnly(target: Client.Target) {
            try {
                val entries = try {
                    Client.listDirectory(target)
                } catch (e: IOException) {
                    val cause = e.cause
                    if (cause is SMBApiException) {
                        val status = cause.status
                        if (status == NtStatus.STATUS_NO_SUCH_FILE ||
                            status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND ||
                            status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND
                        ) {
                            return
                        }
                    }
                    throw e
                }

                if (!entries.isNullOrEmpty()) {
                    for (entry in entries) {
                        if (!entry.isDirectory && isComicImageOrArchive(entry.name)) {
                            val fileTarget = Client.Target(
                                target.authority,
                                target.share,
                                joinPath(target.pathInShare, entry.name)
                            )
                            Client.delete(fileTarget)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DownloadsScene", "删除 SMB 图片失败: ${target.pathInShare}", e)
                throw e
            }
        }

        private fun deleteFileAsync(vararg files: UniFile) {
            object : AsyncTask<UniFile, Void, Void>() {
                override fun doInBackground(vararg params: UniFile): Void? {
                    for (file in params) {
                        file.delete()
                    }
                    return null
                }
            }.executeOnExecutor(IoThreadPoolExecutor.instance, *files)
        }

        private fun deleteGalleryFilesAsync(galleryInfoList: List<GalleryInfo>) {
            @Suppress("UNCHECKED_CAST")
            object : AsyncTask<List<GalleryInfo>, Void, Void>() {
                override fun doInBackground(vararg params: List<GalleryInfo>): Void? {
                    for (info in params[0]) {
                        val file = getGalleryDownloadDir(info)
                        EhDB.removeDownloadDirname(info.gid)
                        file?.delete()
                    }
                    return null
                }
            }.executeOnExecutor(IoThreadPoolExecutor.instance, galleryInfoList)
        }
    }
}