/*
 * Copyright 2026 Hippo Seven
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

package com.hippo.ehviewer.ui.scene.gallery.list

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.hippo.drawable.TriangleDrawable
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.easyrecyclerview.MarginItemDecoration
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhCacheKeyFactory
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.subscription.SubscriptionDigestHolder
import com.hippo.ehviewer.ui.CommonOperations
import com.hippo.ehviewer.ui.scene.ToolbarScene
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.ehviewer.widget.FixedThumbNew
import com.hippo.ehviewer.widget.SimpleRatingView
import com.hippo.ripple.Ripple
import com.hippo.scene.Announcer
import com.hippo.widget.ProgressView
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager
import java.util.ArrayList

class SubscriptionDigestScene : ToolbarScene(), EasyRecyclerView.OnItemClickListener,
    EasyRecyclerView.OnItemLongClickListener, SubscriptionDigestHolder.OnProgressListener {

    private var recyclerView: EasyRecyclerView? = null
    private var progressView: ProgressView? = null
    private var tipView: TextView? = null
    private var adapter: DigestAdapter? = null

    private var downloadManager: DownloadManager? = null
    private var hideDownloaded = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = getEHContext() ?: return
        downloadManager = EhApplication.getDownloadManager(context)
    }

    override fun getMenuResId(): Int {
        return R.menu.scene_subscription_digest
    }

    override fun onMenuCreated(menu: Menu) {
        val item = menu.findItem(R.id.action_filter_downloaded)
        item?.isChecked = hideDownloaded
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_filter_downloaded) {
            hideDownloaded = !item.isChecked
            item.isChecked = hideDownloaded
            updateState()
            return true
        } else if (item.itemId == R.id.action_change_date) {
            val context = getEHContext() ?: return false
            SubscriptionDigestDialog(context).show()
            return true
        }
        return false
    }

    override fun onNavigationClick(view: View) {
        finish()
    }

    override fun onCreateView3(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.scene_subscription_digest, container, false)
        recyclerView = view.findViewById(R.id.recycler_view)
        progressView = view.findViewById(R.id.progress)
        tipView = view.findViewById(R.id.tip)

        val context = getEHContext() ?: return view
        val resources = context.resources

        val layoutManager = AutoStaggeredGridLayoutManager(0, StaggeredGridLayoutManager.VERTICAL)
        val columnWidth = resources.getDimensionPixelOffset(Settings.getDetailSizeResId())
        layoutManager.setColumnSize(columnWidth)
        layoutManager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_MIN_SIZE)

        val interval = resources.getDimensionPixelOffset(R.dimen.gallery_list_interval)
        val paddingH = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_h)
        val paddingV = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_v)
        val decoration = MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV)
        recyclerView?.addItemDecoration(decoration)
        decoration.applyPaddings(recyclerView)

        recyclerView?.layoutManager = layoutManager
        recyclerView?.setSelector(Ripple.generateRippleDrawable(context, false, ColorDrawable(Color.TRANSPARENT)))
        recyclerView?.setDrawSelectorOnTop(true)
        recyclerView?.setOnItemClickListener(this)
        recyclerView?.setOnItemLongClickListener(this)

        adapter = DigestAdapter(inflater)
        recyclerView?.adapter = adapter

        updateState()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val date = SubscriptionDigestHolder.targetDate
        setTitle(if (date.isNotEmpty()) "${getString(R.string.subscription_digest_title)} ($date)" else getString(R.string.subscription_digest_title))
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24)

        SubscriptionDigestHolder.addListener(this)
        updateState()
    }

    override fun onNewArguments(args: Bundle) {
        super.onNewArguments(args)
        val date = SubscriptionDigestHolder.targetDate
        setTitle(if (date.isNotEmpty()) "${getString(R.string.subscription_digest_title)} ($date)" else getString(R.string.subscription_digest_title))
        updateState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        SubscriptionDigestHolder.removeListener(this)
        recyclerView = null
        progressView = null
        tipView = null
        adapter = null
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun updateState() {
        if (SubscriptionDigestHolder.isRunning) {
            progressView?.visibility = View.VISIBLE
            tipView?.visibility = View.VISIBLE
            tipView?.text = SubscriptionDigestHolder.statusText
            recyclerView?.visibility = View.GONE
            setSubtitle(SubscriptionDigestHolder.statusText)
            return
        }

        progressView?.visibility = View.GONE
        val all = ArrayList(SubscriptionDigestHolder.allList)

        if (all.isEmpty()) {
            tipView?.visibility = View.VISIBLE
            tipView?.text = getString(R.string.subscription_digest_empty)
            recyclerView?.visibility = View.GONE
            setSubtitle(null)
            return
        }

        val dm = downloadManager
        val filtered = if (hideDownloaded && dm != null) {
            all.filter { dm.getDownloadState(it.gid) != DownloadInfo.STATE_FINISH }
        } else {
            all
        }

        val downloadedCount = all.size - filtered.size
        setSubtitle(getString(R.string.subscription_digest_subtitle, all.size, downloadedCount))

        if (filtered.isEmpty()) {
            tipView?.visibility = View.VISIBLE
            tipView?.text = getString(R.string.subscription_digest_empty)
            recyclerView?.visibility = View.GONE
        } else {
            tipView?.visibility = View.GONE
            recyclerView?.visibility = View.VISIBLE
            adapter?.setItems(filtered)
            adapter?.notifyDataSetChanged()
        }
    }

    override fun onItemClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean {
        val gi = adapter?.getItem(position) ?: return false
        val args = Bundle().apply {
            putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GALLERY_INFO)
            putParcelable(GalleryDetailScene.KEY_GALLERY_INFO, gi)
        }
        startScene(Announcer(GalleryDetailScene::class.java).setArgs(args))
        return true
    }

    override fun onItemLongClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean {
        val gi = adapter?.getItem(position) ?: return false
        val activity = activity2 ?: return false

        AlertDialog.Builder(activity)
            .setTitle(EhUtils.getSuitableTitle(gi))
            .setItems(
                arrayOf(
                    getString(R.string.download),
                    getString(R.string.favourite)
                )
            ) { _, which ->
                when (which) {
                    0 -> CommonOperations.startDownload(activity, gi, false)
                    1 -> CommonOperations.addToFavorites(activity, gi, null, false)
                }
            }
            .show()
        return true
    }

    override fun onProgress(current: Int, total: Int, status: String) {
        activity2?.runOnUiThread {
            if (recyclerView != null) {
                tipView?.text = status
                setSubtitle(status)
            }
        }
    }

    override fun onComplete(targetDate: String, count: Int) {
        activity2?.runOnUiThread {
            if (recyclerView != null) {
                setTitle("${getString(R.string.subscription_digest_title)} ($targetDate)")
                updateState()
            }
        }
    }

    override fun onError(error: String) {
        activity2?.runOnUiThread {
            if (recyclerView != null) {
                tipView?.text = error
                updateState()
            }
        }
    }

    inner class DigestAdapter(private val inflater: LayoutInflater) :
        RecyclerView.Adapter<DigestViewHolder>() {

        private val items = ArrayList<GalleryInfo>()

        fun setItems(newItems: List<GalleryInfo>) {
            items.clear()
            items.addAll(newItems)
        }

        fun getItem(position: Int): GalleryInfo? {
            return if (position in 0 until items.size) items[position] else null
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DigestViewHolder {
            val v = inflater.inflate(R.layout.item_gallery_list_new, parent, false)
            return DigestViewHolder(v)
        }

        override fun onBindViewHolder(holder: DigestViewHolder, position: Int) {
            val gi = items[position]

            holder.thumb.load(EhCacheKeyFactory.getThumbKey(gi.gid), gi.thumb)

            holder.title.text = EhUtils.getSuitableTitle(gi)
            holder.uploader.text = gi.uploader

            val color = EhUtils.getCategoryColor(gi.category)
            val drawable = holder.category.background
            if (drawable !is TriangleDrawable) {
                holder.category.setBackgroundColor(color)
            }
            holder.category.text = EhUtils.getCategory(gi.category)

            holder.rating.setRating(gi.rating)

            if (gi.favoriteCount >= 0) {
                holder.favCount.text = "❤ ${gi.favoriteCount}"
                holder.favCount.visibility = View.VISIBLE
            } else {
                holder.favCount.visibility = View.GONE
            }

            holder.posted.text = gi.posted

            holder.pages.text = "${gi.pages}P"
            holder.pages.visibility = View.VISIBLE

            if (gi.simpleLanguage.isNullOrEmpty()) {
                holder.simpleLanguage.visibility = View.GONE
            } else {
                holder.simpleLanguage.text = gi.simpleLanguage
                holder.simpleLanguage.visibility = View.VISIBLE
            }

            val dm = downloadManager
            val downloadState = if (dm != null) dm.getDownloadState(gi.gid) else DownloadInfo.STATE_INVALID
            if (downloadState == DownloadInfo.STATE_INVALID) {
                holder.downloaded.visibility = View.GONE
            } else {
                holder.downloaded.visibility = View.VISIBLE
                if (downloadState == DownloadInfo.STATE_FINISH) {
                    holder.downloaded.setImageResource(R.drawable.v_download_x16)
                } else {
                    holder.downloaded.setImageResource(R.drawable.v_download_outline_x16)
                }
            }
        }
    }

    class DigestViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumb: FixedThumbNew = itemView.findViewById(R.id.thumb_new)
        val title: TextView = itemView.findViewById(R.id.title)
        val uploader: TextView = itemView.findViewById(R.id.uploader)
        val rating: SimpleRatingView = itemView.findViewById(R.id.rating)
        val favCount: TextView = itemView.findViewById(R.id.fav_count)
        val category: TextView = itemView.findViewById(R.id.category)
        val posted: TextView = itemView.findViewById(R.id.posted)
        val pages: TextView = itemView.findViewById(R.id.pages)
        val simpleLanguage: TextView = itemView.findViewById(R.id.simple_language)
        val downloaded: ImageView = itemView.findViewById(R.id.downloaded)
    }
}
