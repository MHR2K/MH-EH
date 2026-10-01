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

import android.annotation.SuppressLint
import android.app.AlertDialog.Builder
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.hippo.android.resource.AttrResources
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhCacheKeyFactory
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadService
import com.hippo.ehviewer.gallery.A7ZipArchive
import com.hippo.ehviewer.gallery.Pipe
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.ui.scene.TransitionNameFactory
import com.hippo.ehviewer.ui.scene.download.DownloadsScene
import com.hippo.ehviewer.ui.scene.download.part.StorageDetector.StorageLocation
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.ehviewer.ui.scene.gallery.list.EnterGalleryDetailTransaction
import com.hippo.ehviewer.util.CbzUtils
import com.hippo.ehviewer.util.CrashlyticsUtils
import com.hippo.lib.yorozuya.AssertUtils
import com.hippo.lib.yorozuya.FileUtils
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.ripple.Ripple
import com.hippo.scene.Announcer
import com.hippo.unifile.UniFile
import com.hippo.unifile.UniRandomAccessFile
import com.hippo.util.NaturalComparator
import com.hippo.widget.LoadImageView
import com.h6ah4i.android.widget.advrecyclerview.draggable.DraggableItemAdapter
import com.h6ah4i.android.widget.advrecyclerview.draggable.ItemDraggableRange
import com.h6ah4i.android.widget.advrecyclerview.utils.AbstractDraggableItemViewHolder
import kotlin.concurrent.thread

/**
 * 下载列表适配器
 */
class DownloadAdapter(
    private val mScene: DownloadsScene,
    private val mCallback: DownloadAdapterCallback
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), DraggableItemAdapter<RecyclerView.ViewHolder> {

    companion object {
        private val TAG: String = DownloadAdapter::class.java.simpleName
        @JvmField
        var DRAG_ENABLE: Boolean = false

        private const val TYPE_ITEM = 0
        private const val TYPE_HEADER = 1
    }

    private val mInflater: LayoutInflater
    private val mListThumbWidth: Int
    private val mListThumbHeight: Int

    private var movedItem: View? = null

    private val thumbnailCache: MutableMap<String, Bitmap> = HashMap()

    // 分组模式数据
    private var mGroupMode: Boolean = false
    private var mGroupedData: Map<String, List<DownloadInfo>>? = null
    private var mSortedLabels: List<String>? = null
    private val mCollapsedLabels: MutableSet<String> = HashSet()
    private val mFlatList: MutableList<Any> = ArrayList()

    init {
        DRAG_ENABLE = Settings.getDragDownloadGallery()

        val inflater: LayoutInflater = try {
            mScene.getLayoutInflater2()
        } catch (e: NullPointerException) {
            val context = mScene.getContext()
            if (context != null) {
                LayoutInflater.from(context)
            } else {
                val activity = mScene.getActivity()
                    ?: throw IllegalStateException("Cannot get LayoutInflater: Fragment is not attached and Context/Activity is null")
                LayoutInflater.from(activity)
            }
        } catch (e: IllegalStateException) {
            val context = mScene.getContext()
            if (context != null) {
                LayoutInflater.from(context)
            } else {
                val activity = mScene.getActivity()
                    ?: throw IllegalStateException("Cannot get LayoutInflater: Fragment is not attached and Context/Activity is null")
                LayoutInflater.from(activity)
            }
        }
        mInflater = inflater
        AssertUtils.assertNotNull(mInflater)

        val calculator = mInflater.inflate(R.layout.item_gallery_list_thumb_height, null)
        ViewUtils.measureView(calculator, 1024, ViewGroup.LayoutParams.WRAP_CONTENT)
        mListThumbHeight = calculator.measuredHeight
        mListThumbWidth = mListThumbHeight * 2 / 3
    }

    interface GroupToggleCallback {
        fun onToggleLabel(label: String)
    }

    interface DownloadAdapterCallback {
        fun getIndexPage(): Int
        fun getPageSize(): Int
        fun getPaginationSize(): Int
        fun isCanPagination(): Boolean
        fun positionInList(position: Int): Int
        fun listIndexInPage(position: Int): Int
        fun getList(): List<DownloadInfo>?
        fun getSpiderInfoMap(): Map<Long, SpiderInfo>
        fun getDownloadManager(): DownloadManager?
        fun getRecyclerView(): EasyRecyclerView?
        fun onSpiderInfoFromSmb(gid: Long, spiderInfo: SpiderInfo) {}
    }

    // ========== 分组模式方法 ==========

    fun setGroupedData(groupedData: Map<String, List<DownloadInfo>>?, collapsedLabels: Set<String>?) {
        mGroupMode = groupedData != null && groupedData.isNotEmpty()
        mGroupedData = groupedData
        if (collapsedLabels != null) {
            mCollapsedLabels.clear()
            mCollapsedLabels.addAll(collapsedLabels)
        } else {
            mCollapsedLabels.clear()
        }
        if (mGroupMode) {
            rebuildFlatList()
        }
        notifyDataSetChanged()
    }

    fun toggleLabel(label: String) {
        if (mCollapsedLabels.contains(label)) {
            mCollapsedLabels.remove(label)
        } else {
            mCollapsedLabels.add(label)
        }
        rebuildFlatList()
        notifyDataSetChanged()
    }

    fun clearGroupMode() {
        mGroupMode = false
        mGroupedData = null
        mFlatList.clear()
    }

    private fun rebuildFlatList() {
        mFlatList.clear()
        val groupedData = mGroupedData ?: return
        val defaultLabel = mScene.getString(R.string.default_download_label_name)
        mSortedLabels = ArrayList(groupedData.keys).sortedWith { a, b ->
            val aDefault = a == defaultLabel
            val bDefault = b == defaultLabel
            if (aDefault && !bDefault) return@sortedWith -1
            if (!aDefault && bDefault) return@sortedWith 1
            a.compareTo(b, ignoreCase = true)
        }
        for (label in mSortedLabels!!) {
            mFlatList.add(label)  // TYPE_HEADER
            if (!mCollapsedLabels.contains(label)) {
                groupedData[label]?.let { mFlatList.addAll(it) }  // TYPE_ITEM
            }
        }
    }

    /**
     * 根据 adapter 位置获取 DownloadInfo（group-mode-aware）。
     * 分组模式下从 mFlatList 中取，跳过头部；
     * 普通模式下走原有的 mCallback.getList() + positionInList 逻辑。
     * @return DownloadInfo 或 null（当位置是头部或越界时）
     */
    fun getDownloadInfoAtAdapterPosition(adapterPosition: Int): DownloadInfo? {
        if (mGroupMode) {
            if (adapterPosition < 0 || adapterPosition >= mFlatList.size) {
                return null
            }
            val item = mFlatList[adapterPosition]
            return if (item is DownloadInfo) item else null
        }
        // 普通模式：走原有逻辑
        val list = mCallback.getList() ?: return null
        val pos = mCallback.positionInList(adapterPosition)
        return if (pos < 0 || pos >= list.size) null else list[pos]
    }

    override fun getItemId(position: Int): Long {
        if (mGroupMode) {
            val item = mFlatList[position]
            if (item is String) {
                // Header: use hash of label as ID
                return ("header:$item").hashCode().toLong()
            }
            return (item as DownloadInfo).gid
        }
        val posInList = mCallback.positionInList(position)
        val list = mCallback.getList()
        if (list == null || posInList < 0 || posInList >= list.size) {
            return 0
        }
        return list[posInList].gid
    }

    override fun getItemViewType(position: Int): Int {
        if (mGroupMode && mFlatList[position] is String) {
            return TYPE_HEADER
        }
        return TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_HEADER) {
            val view = mInflater.inflate(R.layout.item_download_label_header, parent, false)
            return LabelHeaderViewHolder(view)
        }
        val holder = DownloadHolder(mInflater.inflate(R.layout.item_download, parent, false))

        val lp = holder.thumb.layoutParams
        lp.width = mListThumbWidth
        lp.height = mListThumbHeight
        holder.thumb.layoutParams = lp

        return holder
    }

    override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
        if (mGroupMode) {
            val item = mFlatList[position]
            if (item is String) {
                // TYPE_HEADER
                val label = item
                val items = mGroupedData?.get(label)
                val headerHolder = viewHolder as LabelHeaderViewHolder
                headerHolder.bind(label, mCollapsedLabels.contains(label), items?.size ?: 0)
                return
            }
            // TYPE_ITEM in group mode
            val info = item as DownloadInfo
            bindDownloadItem(viewHolder as DownloadHolder, info)
        } else {
            // 普通模式
            val list = mCallback.getList() ?: return
            try {
                val pos = mCallback.positionInList(position)
                val info = list[pos]
                bindDownloadItem(viewHolder as DownloadHolder, info)
            } catch (e: Exception) {
                CrashlyticsUtils.record(e)
            }
        }
    }

    private fun bindDownloadItem(holder: DownloadHolder, info: DownloadInfo) {
        try {
            var title = EhUtils.getSuitableTitle(info)
            // Add special prefix for imported archives
            if (info.archiveUri != null && info.archiveUri.startsWith("content://")) {
                title = "📦 $title"
            }
            // Handle thumbnail loading for imported archives
            if (info.archiveUri != null && info.archiveUri.startsWith("content://")) {
                // For imported archives, extract first image as thumbnail
                loadArchiveThumbnail(holder.thumb, Uri.parse(info.archiveUri))
            } else {
                // Normal thumbnail loading for regular downloads
                holder.thumb.load(EhCacheKeyFactory.getThumbKey(info.gid), info.thumb,
                    ThumbDataContainer(mScene.getEHContext() ?: return, info) { spiderInfo ->
                        mCallback.onSpiderInfoFromSmb(info.gid, spiderInfo)
                    }, true, false)
            }

            holder.title.text = title
            holder.uploader.text = info.uploader

            // 显示收藏数
            if (info.favoriteCount > 0) {
                holder.favCount.text = "❤ ${info.favoriteCount}"
                holder.favCount.visibility = View.VISIBLE
            } else {
                holder.favCount.visibility = View.GONE
            }

            // Handle rating display for imported archives
            if (info.archiveUri != null && info.archiveUri.startsWith("content://")) {
                holder.rating.setRating(5.0f)
            } else {
                holder.rating.setRating(info.rating)
            }

            val spiderInfo = mCallback.getSpiderInfoMap()[info.gid]

            if (spiderInfo != null) {
                val startPage = spiderInfo.startPage + 1
                val readText = "$startPage/${spiderInfo.pages}"
                holder.readProgress.text = readText
            } else {
                holder.readProgress.text = ""
            }

            val category = holder.category
            val newCategoryText: String
            val categoryColor: Int
            if (info.archiveUri != null && info.archiveUri.startsWith("content://")) {
                newCategoryText = mScene.getString(R.string.imported_archive_category)
                categoryColor = -0xB350B // 0xFF4CAF50
            } else {
                newCategoryText = EhUtils.getCategory(info.category) ?: ""
                categoryColor = EhUtils.getCategoryColor(info.category)
            }
            if (newCategoryText != category.text.toString()) {
                category.text = newCategoryText
            }
            category.setBackgroundColor(categoryColor)
            bindForState(holder, info)

            // Update transition name
            ViewCompat.setTransitionName(holder.thumb, TransitionNameFactory.getThumbTransitionName(info.gid))
        } catch (e: Exception) {
            CrashlyticsUtils.record(e)
        }
    }

    override fun getItemCount(): Int {
        if (mGroupMode) {
            return mFlatList.size
        }
        val list = mCallback.getList() ?: return 0
        val listSize = list.size
        if (listSize < mCallback.getPaginationSize() || !mCallback.isCanPagination()) {
            return listSize
        }
        val count = listSize - mCallback.getPageSize() * (mCallback.getIndexPage() - 1)
        return count.coerceAtMost(mCallback.getPageSize())
    }

    private fun bindForState(holder: DownloadHolder, info: DownloadInfo) {
        val resources = mScene.getResources2() ?: return

        // Check if this is an imported archive - skip state judging
        val isImportedArchive = info.archiveUri != null &&
            info.archiveUri.startsWith("content://")
        if (isImportedArchive) {
            bindState(holder, info, resources.getString(R.string.download_state_finish))
            return
        }

        when (info.state) {
            DownloadInfo.STATE_NONE -> bindState(holder, info, resources.getString(R.string.download_state_none))
            DownloadInfo.STATE_WAIT -> bindState(holder, info, resources.getString(R.string.download_state_wait))
            DownloadInfo.STATE_DOWNLOAD -> bindProgress(holder, info)
            DownloadInfo.STATE_FAILED -> {
                val text = if (info.legacy <= 0) {
                    resources.getString(R.string.download_state_failed)
                } else {
                    resources.getString(R.string.download_state_failed_2, info.legacy)
                }
                bindState(holder, info, text)
            }
            DownloadInfo.STATE_FINISH -> bindState(holder, info, resources.getString(R.string.download_state_finish))
        }
    }

    private fun bindState(holder: DownloadHolder, info: DownloadInfo, state: String) {
        holder.uploader.visibility = View.VISIBLE
        holder.rating.visibility = View.VISIBLE
        holder.category.visibility = View.VISIBLE
        holder.readProgress.visibility = View.VISIBLE
        holder.state.visibility = View.VISIBLE
        // 存储位置指示仅在状态可见时显示
        val res0 = mScene.getResources2()
        if (res0 != null) {
            val loc = StorageDetector.detectCached(info)
            var label = when (loc) {
                StorageLocation.SMB -> res0.getString(R.string.storage_smb)
                StorageLocation.LOCAL -> res0.getString(R.string.storage_local)
                StorageLocation.BOTH -> res0.getString(R.string.storage_both)
                StorageLocation.UNKNOWN -> ""
                else -> ""
            }

            // 检测是否为CBZ格式
            var isCbz = false
            if (loc != StorageLocation.UNKNOWN && loc != StorageLocation.SMB) {
                // 仅对本地存储检测CBZ
                try {
                    val dir = SpiderDen.getGalleryDownloadDir(info)
                    if (dir != null) {
                        isCbz = CbzUtils.isCbzMode(dir)
                    }
                } catch (e: Exception) {
                    // 忽略检测错误
                }
            }

            // 如果是CBZ，添加📦emoji
            if (isCbz) {
                label = label + "📦"
            }

            if (label.isEmpty()) {
                holder.storageIndicator.visibility = View.GONE
            } else {
                holder.storageIndicator.visibility = View.VISIBLE
                holder.storageIndicator.text = label
            }
        } else {
            holder.storageIndicator.visibility = View.GONE
        }
        holder.edit.visibility = View.VISIBLE
        holder.progressBar.visibility = View.GONE
        holder.percent.visibility = View.GONE
        holder.speed.visibility = View.GONE
        if (info.state == DownloadInfo.STATE_WAIT || info.state == DownloadInfo.STATE_DOWNLOAD) {
            holder.start.visibility = View.GONE
            holder.stop.visibility = View.VISIBLE
        } else {
            holder.start.visibility = View.VISIBLE
            holder.stop.visibility = View.GONE
        }

        holder.state.text = state
    }

    @SuppressLint("SetTextI18n")
    private fun bindProgress(holder: DownloadHolder, info: DownloadInfo) {
        holder.uploader.visibility = View.GONE
        holder.rating.visibility = View.GONE
        holder.favCount.visibility = View.GONE
        holder.category.visibility = View.GONE
        holder.readProgress.visibility = View.GONE
        holder.state.visibility = View.GONE
        holder.storageIndicator.visibility = View.GONE
        holder.edit.visibility = View.VISIBLE
        holder.progressBar.visibility = View.VISIBLE
        holder.percent.visibility = View.VISIBLE
        holder.speed.visibility = View.VISIBLE
        if (info.state == DownloadInfo.STATE_WAIT || info.state == DownloadInfo.STATE_DOWNLOAD) {
            holder.start.visibility = View.GONE
            holder.stop.visibility = View.VISIBLE
        } else {
            holder.start.visibility = View.VISIBLE
            holder.stop.visibility = View.GONE
        }

        if (info.total <= 0 || info.finished < 0) {
            holder.percent.text = null
            holder.progressBar.isIndeterminate = true
        } else {
            holder.percent.text = "${info.finished}/${info.total}"
            holder.progressBar.isIndeterminate = false
            holder.progressBar.max = info.total
            holder.progressBar.progress = info.finished
        }
        var speed = info.speed
        if (speed < 0) {
            speed = 0
        }
        holder.speed.text = FileUtils.humanReadableByteCount(speed, false) + "/S"
    }


    // 拖拽排序相关方法实现
    override fun onCheckCanStartDrag(holder: RecyclerView.ViewHolder, position: Int, x: Int, y: Int): Boolean {
        if (!DRAG_ENABLE || mGroupMode) {
            return false
        }
        if (holder !is DownloadHolder) {
            return false
        }
        // 检查是否点击在thumb上
        return ViewUtils.isViewUnder(holder.thumb, x, y, 0)
    }

    override fun onGetItemDraggableRange(holder: RecyclerView.ViewHolder, position: Int): ItemDraggableRange? {
        return null
    }

    override fun onMoveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) {
            return
        }
        val list = mCallback.getList() ?: return

        // 计算在完整列表中的位置
        val fromPosInList = mCallback.positionInList(fromPosition)
        val toPosInList = mCallback.positionInList(toPosition)

        if (fromPosInList >= 0 && fromPosInList < list.size &&
            toPosInList >= 0 && toPosInList < list.size
        ) {
            // 先更新数据库中的顺序（通过 time 字段）
            EhDB.moveDownloadInfo(list, fromPosInList, toPosInList)

            // 再尝试更新当前列表的内存顺序
            // 某些场景下（如搜索结果列表）mList 可能是 Arrays.asList(...)
            // 这类列表不支持结构修改，直接 remove/add 会抛出 UnsupportedOperationException
            try {
                val mutableList = list as MutableList<DownloadInfo>
                val item = mutableList.removeAt(fromPosInList)
                mutableList.add(toPosInList, item)
            } catch (e: UnsupportedOperationException) {
                Log.w(TAG, "onMoveItem: list is unmodifiable, only DB order updated", e)
            }

            // 通知适配器刷新界面
            notifyDataSetChanged()
        }
    }

    override fun onCheckCanDrop(draggingPosition: Int, dropPosition: Int): Boolean {
        return DRAG_ENABLE && !mGroupMode
    }

    override fun onItemDragStarted(position: Int) {
        // 拖拽开始时的处理
        try {
            // 设置RecyclerView为软件渲染模式以避免硬件位图问题
            val recyclerView = mCallback.getRecyclerView()
            if (recyclerView != null) {
                movedItem = recyclerView.getChildAt(position)
                movedItem?.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                Log.d("DownloadAdapter", "onItemDragStarted: $position")
            }
        } catch (e: Exception) {
            // 忽略硬件位图相关错误
            Log.e("DownloadAdapter", "Error in onItemDragStarted: ${e.message}")
        }
    }

    override fun onItemDragFinished(fromPosition: Int, toPosition: Int, result: Boolean) {
        // 拖拽结束时的处理
        try {
            // 恢复RecyclerView为硬件加速模式
            val recyclerView = mCallback.getRecyclerView()
            if (recyclerView != null) {
//                if (recyclerView.getChildCount() >= toPosition + 1) {
//                    recyclerView.getChildAt(toPosition + 1).setLayerType(View.LAYER_TYPE_HARDWARE, null);
//                    Log.d("DownloadAdapter", "toPosition+1: " + (toPosition + 1));
//                }
//
//                if (toPosition >= 1) {
//                    recyclerView.getChildAt(toPosition - 1).setLayerType(View.LAYER_TYPE_HARDWARE, null);
//                    Log.d("DownloadAdapter", "toPosition-1: " + (toPosition - 1));
//                }
//                recyclerView.getChildAt(fromPosition).setLayerType(View.LAYER_TYPE_HARDWARE, null);
                val moved = movedItem
                if (moved != null) {
                    moved.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    Log.d("DownloadAdapter", "movedItem: $moved")
                } else {
                    recyclerView.getChildAt(toPosition).setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    Log.d("DownloadAdapter", "onItemDragFinished: $toPosition")
                }
            }
        } catch (e: Exception) {
            // 忽略硬件位图相关错误
            Log.e("DownloadAdapter", "Error in onItemDragFinished: ${e.message}")
        }
    }

    private fun loadArchiveThumbnail(thumb: LoadImageView, archiveUri: Uri) {
        val uriString = archiveUri.toString()

        // Check cache first
        if (thumbnailCache.containsKey(uriString)) {
            val cachedThumbnail = thumbnailCache[uriString]
            if (cachedThumbnail != null && !cachedThumbnail.isRecycled) {
                thumb.setImageBitmap(cachedThumbnail)
                return
            } else {
                // Remove invalid cached entry
                thumbnailCache.remove(uriString)
            }
        }

        // Set default icon immediately as fallback
        thumb.setImageResource(R.drawable.v_archive_hh_primary_x48)

        // Load thumbnail in background thread
        thread {
            try {
                val thumbnail = extractFirstImageFromArchive(archiveUri)
                mScene.runOnUiThread {
                    if (thumbnail != null && !thumbnail.isRecycled) {
                        // Cache the thumbnail
                        thumbnailCache[uriString] = thumbnail
                        thumb.setImageBitmap(thumbnail)
                    } else {
                        // If extraction fails, check if we have a previous cached thumbnail
                        val fallbackThumbnail = thumbnailCache[uriString]
                        if (fallbackThumbnail != null && !fallbackThumbnail.isRecycled) {
                            thumb.setImageBitmap(fallbackThumbnail)
                        }
                        // Otherwise keep the default archive icon that was already set
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load archive thumbnail for $uriString", e)
                // Keep the default icon that was already set - no need to change anything
            }
        }
    }

    private fun extractFirstImageFromArchive(archiveUri: Uri): Bitmap? {
        val context = mScene.getEHContext() ?: return null

        var uraf: UniRandomAccessFile? = null
        var archive: A7ZipArchive? = null

        try {
            // Verify URI accessibility first and try to restore permission if needed
            try {
                context.contentResolver.openInputStream(archiveUri)?.use { testStream ->
                    // Stream is accessible
                } ?: run {
                    Log.w(TAG, "Cannot access archive URI: $archiveUri")
                    return null
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "URI permission lost, attempting to restore: $archiveUri", e)
                // Try to restore the permission
                try {
                    context.contentResolver.takePersistableUriPermission(archiveUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    Log.d(TAG, "Successfully restored URI permission for: $archiveUri")
                    // Try again after restoring permission
                    context.contentResolver.openInputStream(archiveUri)?.use { retryStream ->
                        // OK
                    } ?: run {
                        Log.w(TAG, "Still cannot access URI after permission restore: $archiveUri")
                        return null
                    }
                } catch (restoreEx: Exception) {
                    Log.e(TAG, "Failed to restore URI permission for: $archiveUri", restoreEx)
                    return null
                }
            } catch (e: Exception) {
                Log.w(TAG, "URI not accessible: $archiveUri", e)
                return null
            }

            // Open the archive file
            val file = UniFile.fromUri(context, archiveUri)
            if (file == null || !file.exists()) {
                Log.w(TAG, "Archive file not found: $archiveUri")
                return null
            }

            uraf = file.createRandomAccessFile("r")
            if (uraf == null) {
                Log.w(TAG, "Cannot create random access file for: $archiveUri")
                return null
            }

            archive = A7ZipArchive.create(uraf)
            if (archive == null) {
                Log.w(TAG, "Cannot create archive reader for: $archiveUri")
                return null
            }

            val entries = archive.getArchiveEntries()
            if (entries.isEmpty()) {
                Log.w(TAG, "Archive is empty: $archiveUri")
                return null
            }

            // Sort entries by name (natural order)
            entries.sortWith(compareBy { NaturalComparator().compare(it.path, it.path) })

            // Find the first image file
            for (entry in entries) {
                val fileName = entry.path.lowercase()
                if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
                    fileName.endsWith(".png") || fileName.endsWith(".bmp") ||
                    fileName.endsWith(".gif") || fileName.endsWith(".webp")
                ) {

                    try {
                        // Create a pipe to extract the image
                        var pipe = Pipe(8 * 1024) // Increased buffer size

                        // Extract in another thread with timeout
                        var extractThread = thread {
                            try {
                                entry.extract(pipe.outputStream)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to extract image: $fileName", e)
                            }
                        }

                        // Decode the image with size limits
                        val options = BitmapFactory.Options()
                        options.inJustDecodeBounds = true
                        BitmapFactory.decodeStream(pipe.inputStream, null, options)

                        // Calculate sample size for thumbnail (smaller target size for better performance)
                        val thumbnailSize = 150
                        var sampleSize = 1
                        if (options.outHeight > thumbnailSize || options.outWidth > thumbnailSize) {
                            val halfHeight = options.outHeight / 2
                            val halfWidth = options.outWidth / 2
                            while (halfHeight / sampleSize >= thumbnailSize && halfWidth / sampleSize >= thumbnailSize) {
                                sampleSize *= 2
                            }
                        }

                        // Recreate pipe for actual decoding
                        pipe = Pipe(8 * 1024)
                        extractThread = thread {
                            try {
                                entry.extract(pipe.outputStream)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to extract image on second attempt: $fileName", e)
                            }
                        }

                        // Decode with sample size
                        options.inJustDecodeBounds = false
                        options.inSampleSize = sampleSize
                        options.inPreferredConfig = Bitmap.Config.RGB_565 // Use less memory
                        val bitmap = BitmapFactory.decodeStream(pipe.inputStream, null, options)

                        extractThread.join(3000) // Wait max 3 seconds (reduced from 5)

                        if (bitmap != null && !bitmap.isRecycled) {
                            Log.d(TAG, "Successfully extracted thumbnail from $fileName")
                            return bitmap
                        }

                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to extract thumbnail from $fileName", e)
                        // Continue to next image file
                    }
                }
            }

            Log.w(TAG, "No extractable images found in archive: $archiveUri")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process archive for thumbnail: $archiveUri", e)
        } finally {
            // Ensure resources are properly closed
            try {
                archive?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close archive", e)
            }
            try {
                uraf?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close file", e)
            }
        }

        return null
    }

    inner class DownloadHolder(itemView: View) : AbstractDraggableItemViewHolder(itemView), View.OnClickListener {

        val thumb: LoadImageView = itemView.findViewById(R.id.thumb)
        val title: TextView = itemView.findViewById(R.id.title)
        val uploader: TextView = itemView.findViewById(R.id.uploader)
        val rating: com.hippo.ehviewer.widget.SimpleRatingView = itemView.findViewById(R.id.rating)
        val category: TextView = itemView.findViewById(R.id.category)
        val readProgress: TextView = itemView.findViewById(R.id.read_progress)
        val start: View = itemView.findViewById(R.id.start)
        val stop: View = itemView.findViewById(R.id.stop)
        val edit: View = itemView.findViewById(R.id.edit)
        val state: TextView = itemView.findViewById(R.id.state)
        val storageIndicator: TextView = itemView.findViewById(R.id.storage_indicator)
        val progressBar: android.widget.ProgressBar = itemView.findViewById(R.id.progress_bar)
        val percent: TextView = itemView.findViewById(R.id.percent)
        val speed: TextView = itemView.findViewById(R.id.speed)
        val favCount: TextView = itemView.findViewById(R.id.fav_count)

        init {
            // TODO cancel on click listener when select items
            thumb.setOnClickListener(this)
            start.setOnClickListener(this)
            stop.setOnClickListener(this)
            edit.setOnClickListener(this)

            // 为编辑按钮添加长按监听器
            edit.setOnLongClickListener { v ->
                if (mScene != null) {
                    val context = mScene.getEHContext()
                    val recyclerView = mCallback.getRecyclerView()
                    if (context != null && recyclerView != null && !recyclerView.isInCustomChoice()) {
                        val position = adapterPosition
                        if (position != RecyclerView.NO_POSITION) {
                            return@setOnLongClickListener mScene.handleItemLongClickMenuOnEditButton(position, v)
                        }
                    }
                }
                false
            }

            val isDarkTheme = !AttrResources.getAttrBoolean(mScene.getEHContext()!!, androidx.appcompat.R.attr.isLightTheme)
            Ripple.addRipple(start, isDarkTheme)
            Ripple.addRipple(stop, isDarkTheme)
            Ripple.addRipple(edit, isDarkTheme)
        }

        override fun onClick(v: View) {
            val context = mScene.getEHContext()
            val recyclerView = mCallback.getRecyclerView()
            if (context == null || recyclerView == null || recyclerView.isInCustomChoice()) {
                return
            }
            val index = recyclerView.getChildAdapterPosition(itemView)
            if (index < 0) {
                return
            }

            // group-mode-aware：分组模式从 mFlatList 取，普通模式走原有逻辑
            val info = getDownloadInfoAtAdapterPosition(index) ?: return

            when (v) {
                thumb -> {
                    if (info.archiveUri != null && info.archiveUri.startsWith("content://")) {
                        // Show info dialog for imported archive
                        val message = mScene.getString(R.string.imported_archive_info_message) + "\n\n" + info.archiveUri
                        AlertDialog.Builder(context)
                            .setTitle(R.string.imported_archive_info_title)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    } else {
                        // Normal behavior for regular downloads
                        val args = android.os.Bundle()
                        args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_DOWNLOAD_GALLERY_INFO)
                        args.putParcelable(GalleryDetailScene.KEY_GALLERY_INFO, info)
                        val announcer = Announcer(GalleryDetailScene::class.java).setArgs(args)
                        announcer.setTranHelper(EnterGalleryDetailTransaction(thumb))
                        mScene.startScene(announcer)
                    }
                }
                start -> {
                    val intent = Intent(context, DownloadService::class.java)
                    intent.action = DownloadService.ACTION_START
                    intent.putExtra(DownloadService.KEY_GALLERY_INFO, info)
                    context.startService(intent)
                }
                stop -> {
                    val downloadManager = mCallback.getDownloadManager()
                    if (downloadManager != null) {
                        downloadManager.stopDownload(info.gid)
                    }
                }
                edit -> {
                    showEditDialog(info)
                }
            }
        }
    }

    /**
     * 标签分组头部 ViewHolder
     */
    inner class LabelHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val expandIcon: ImageView = itemView.findViewById(R.id.expand_icon)
        val labelName: TextView = itemView.findViewById(R.id.label_name)
        val labelCount: TextView = itemView.findViewById(R.id.label_count)

        fun bind(label: String, collapsed: Boolean, count: Int) {
            labelName.text = label
            labelCount.text = "($count)"
            // collapsed=true 时箭头朝右（-90度），展开时朝下（0度）
            expandIcon.rotation = if (collapsed) -90f else 0f
            itemView.setOnClickListener {
                if (mCallback is GroupToggleCallback) {
                    (mCallback as GroupToggleCallback).onToggleLabel(label)
                }
            }
        }
    }

    private fun showEditDialog(info: DownloadInfo) {
        val context = mScene.getEHContext() ?: return

        // 创建编辑对话框
        val builder = Builder(context)
        builder.setTitle(R.string.edit_download_info_title)

        // 加载布局
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_download_info, null)
        builder.setView(dialogView)

        // 获取视图引用
        val editGid = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_gid)
        val editTitle = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_title)
        val editTitleJpn = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_title_jpn)
        val editUploader = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_uploader)
        val editRatingNumber = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_rating_number)
        val editPosted = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_posted)
        val editPages = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_pages)

        // 设置当前值
        if (editGid != null) {
            try {
                editGid.setText(info.gid.toString())
            } catch (ignore: Exception) {
            }
        }
        editTitle.setText(info.title)
        editTitleJpn.setText(info.titleJpn)
        editUploader.setText(info.uploader)
        if (editRatingNumber != null) {
            try {
                editRatingNumber.setText(info.rating.toString())
            } catch (ignore: Exception) {
            }
        }
        editPosted.setText(info.posted)
        editPages.setText("")

        // 设置按钮
        builder.setPositiveButton(R.string.edit_download_save_changes) { _, _ ->
            // 保存修改
            var ratingValue = info.rating
            try {
                val cs = editRatingNumber?.text
                val s = cs?.toString()?.trim() ?: ""
                if (s.isNotEmpty()) {
                    ratingValue = java.lang.Float.parseFloat(s)
                }
            } catch (ignore: NumberFormatException) {
                // keep original rating
            }

            val newGidText = if (editGid != null) editGid.text.toString().trim() else ""

            saveDownloadInfoChanges(info, editTitle.text.toString(),
                editTitleJpn.text.toString(), editUploader.text.toString(),
                ratingValue, editPosted.text.toString(),
                editPages.text.toString(), newGidText)
        }

        builder.setNegativeButton(android.R.string.cancel, null)

        // 显示对话框
        builder.create().show()
    }

    private fun saveDownloadInfoChanges(oldInfo: DownloadInfo, newTitle: String?, newTitleJpn: String?,
                                        newUploader: String?, newRating: Float, newPosted: String?,
                                        newPages: String?, newGidText: String?) {
        val context = mScene.getEHContext() ?: return

        // 创建新的DownloadInfo对象，复制所有字段
        val newInfo = DownloadInfo()
        var newGid = oldInfo.gid
        if (newGidText != null && newGidText.isNotEmpty()) {
            try {
                newGid = java.lang.Long.parseLong(newGidText)
            } catch (e: NumberFormatException) {
                Toast.makeText(context, R.string.edit_download_gid_invalid, Toast.LENGTH_SHORT).show()
                return // 无效 gid 不保存
            }
        }
        val downloadManager = mCallback.getDownloadManager()
        if (downloadManager != null && newGid != oldInfo.gid) {
            val existed = downloadManager.getDownloadInfo(newGid)
            if (existed != null) {
                Toast.makeText(context, R.string.edit_download_gid_duplicate, Toast.LENGTH_SHORT).show()
                return // 重复 gid 不保存
            }
        }
        newInfo.gid = newGid
        newInfo.token = oldInfo.token
        newInfo.title = newTitle ?: ""
        newInfo.titleJpn = newTitleJpn ?: ""
        newInfo.thumb = oldInfo.thumb
        newInfo.category = oldInfo.category
        newInfo.posted = newPosted ?: ""
        newInfo.uploader = newUploader ?: ""
        newInfo.rating = newRating
        newInfo.simpleLanguage = oldInfo.simpleLanguage
        newInfo.state = oldInfo.state
        newInfo.legacy = oldInfo.legacy
        newInfo.time = oldInfo.time
        newInfo.label = oldInfo.label
        newInfo.speed = oldInfo.speed
        newInfo.remaining = oldInfo.remaining
        newInfo.finished = oldInfo.finished
        newInfo.downloaded = oldInfo.downloaded
        newInfo.total = oldInfo.total

        // 处理页数字段
        try {
            newInfo.pages = newPages!!.toInt()
        } catch (e: NumberFormatException) {
            newInfo.pages = oldInfo.pages // 如果解析失败，保持原值
        }

        // 如果 gid 发生变化，尝试迁移物理下载目录
        if (newInfo.gid != oldInfo.gid) {
            try {
                val root = Settings.getDownloadLocation()
                if (root != null && root.isDirectory) {
                    var oldDirName = EhDB.getDownloadDirname(oldInfo.gid)
                    if (oldDirName == null) {
                        // 扫描前缀 fallback
                        val arr = root.listFiles { _, filename -> filename != null && filename.startsWith("${oldInfo.gid}-") }
                        if (arr != null) {
                            var max = -1
                            var pick: String? = null
                            for (f in arr) {
                                if (f.isDirectory) {
                                    val len = f.name?.length ?: 0
                                    if (len > max) {
                                        max = len
                                        pick = f.name
                                    }
                                }
                            }
                            oldDirName = pick
                        }
                    }
                    if (oldDirName != null) {
                        val oldDir = root.subFile(oldDirName)
                        if (oldDir != null && oldDir.isDirectory) {
                            val gi = GalleryInfo()
                            gi.gid = newInfo.gid
                            gi.title = newInfo.title
                            var newDirName = FileUtils.sanitizeFilename("${newInfo.gid}-${EhUtils.getSuitableTitle(gi)}")
                            val conflict = root.subFile(newDirName)
                            if (conflict != null && conflict.exists() && conflict.name != oldDirName) {
                                newDirName = newDirName + "_" + System.currentTimeMillis()
                            }
                            val ok = oldDir.renameTo(newDirName)
                            if (ok) {
                                EhDB.updateDownloadDirname(oldInfo.gid, newInfo.gid, newDirName)
                                Toast.makeText(context, R.string.edit_download_dir_migrate_success, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, R.string.edit_download_dir_migrate_failed, Toast.LENGTH_SHORT).show()
                                return // 失败不提交更改
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Toast.makeText(context, R.string.edit_download_dir_migrate_failed, Toast.LENGTH_SHORT).show()
                return
            }
        }

        // 使用DownloadManager更新信息
        if (downloadManager != null) {
            downloadManager.replaceInfo(newInfo, oldInfo)
            Toast.makeText(context, R.string.edit_download_info_updated, Toast.LENGTH_SHORT).show()
        }
    }
}