package com.hippo.ehviewer.smb

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 轻量级记录哪些 gid 实际在 SMB 上。
 * 只存 gid 集合，路径推导交给 SmbPathResolver。
 */
object SmbStorageTracker {
    private const val TAG = "SmbStorageTracker"
    private const val PREF = "smb_storage_tracker"
    private const val KEY_GIDS = "gids_on_smb"

    private lateinit var sp: SharedPreferences
    private var cached: MutableSet<Long>? = null
    private val lock = Any()

    fun init(context: Context) {
        sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        reload()
    }

    private fun reload() {
        synchronized(lock) {
            try {
                val raw = sp.getStringSet(KEY_GIDS, null) ?: emptySet()
                cached = raw.mapNotNull { it.toLongOrNull() }.toMutableSet()
                Log.d(TAG, "Loaded ${cached?.size ?: 0} SMB gids")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reload", e)
                cached = mutableSetOf()
            }
        }
    }

    fun isOnSmb(gid: Long): Boolean {
        val set = cached ?: run { reload(); cached }
        return set?.contains(gid) == true
    }

    fun markOnSmb(gid: Long) {
        synchronized(lock) {
            val set = cached ?: mutableSetOf<Long>().also { cached = it }
            if (set.add(gid)) {
                persist(set)
            }
        }
    }

    fun markLocal(gid: Long) {
        synchronized(lock) {
            val set = cached ?: return
            if (set.remove(gid)) {
                persist(set)
            }
        }
    }

    fun markAllOnSmb(gids: Collection<Long>) {
        if (gids.isEmpty()) return
        synchronized(lock) {
            val set = cached ?: mutableSetOf<Long>().also { cached = it }
            val before = set.size
            set.addAll(gids)
            if (set.size != before) {
                persist(set)
                Log.d(TAG, "Marked ${set.size - before} new gids as SMB")
            }
        }
    }

    private fun persist(set: MutableSet<Long>) {
        try {
            sp.edit().putStringSet(KEY_GIDS, set.map { it.toString() }.toSet()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist", e)
        }
    }
}
