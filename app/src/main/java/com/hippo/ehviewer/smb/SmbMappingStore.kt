package com.hippo.ehviewer.smb

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 记录每个画廊 gid 在 SMB 上的映射目录（authority/share/basePathInShare）。
 * 
 * 优化：
 * - 内存缓存：避免每次都重新解析 JSON
 * - 线程安全：使用 synchronized 保护并发访问
 * - 批量操作：支持一次删除多个映射
 * - 错误处理：损坏数据自动跳过而非崩溃
 */
object SmbMappingStore {
    private const val PREF = "smb_gallery_mappings"
    private const val KEY_MAPPINGS = "mappings"
    private const val TAG = "SmbMappingStore"

    private lateinit var sp: SharedPreferences
    
    // 内存缓存，减少每次查询的 JSON 反序列化开销
    @Volatile
    private var cachedMappings: List<Mapping>? = null
    private val cacheLock = Any()

    data class Mapping(
        val gid: Long,
        val authority: Authority,
        val share: String,
        val basePathInShare: String
    )

    fun init(context: Context) {
        sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        // 初始化时预热缓存
        reloadCache()
    }

    /**
     * 重新加载缓存（在手动修改 SP 时调用）
     * 性能：O(n)，其中 n=映射总数
     */
    private fun reloadCache() {
        synchronized(cacheLock) {
            try {
                val json = sp.getString(KEY_MAPPINGS, "[]") ?: "[]"
                val arr = JSONArray(json)
                val out = mutableListOf<Mapping>()
                for (i in 0 until arr.length()) {
                    try {
                        out += fromJson(arr.getJSONObject(i))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse mapping at index $i", e)
                        // 跳过损坏的条目，继续处理
                    }
                }
                cachedMappings = out
                Log.d(TAG, "Cache reloaded: ${out.size} mappings")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reload cache", e)
                cachedMappings = emptyList()
            }
        }
    }

    /**
     * 清除缓存（用于强制刷新）
     */
    fun invalidateCache() {
        synchronized(cacheLock) {
            cachedMappings = null
        }
    }

    /**
     * 获取所有映射
     * 性能：O(1)（缓存命中）或 O(n)（缓存缺失）
     */
    fun list(): List<Mapping> {
        if (cachedMappings == null) {
            reloadCache()
        }
        return cachedMappings ?: emptyList()
    }

    /**
     * 根据 gid 查询映射
     * 性能：O(n)，但通常 n 很小（<100）
     */
    @JvmStatic
    fun get(gid: Long): Mapping? = list().firstOrNull { it.gid == gid }

    /**
     * 批量查询（减少遍历次数）
     * 性能：O(n)，适合查询多个 gid
     */
    fun getAll(gids: Collection<Long>): List<Mapping> {
        val gidSet = gids.toSet()
        return list().filter { it.gid in gidSet }
    }

    /**
     * 检查是否存在映射
     * 性能：O(n)，但比 get() 早停止
     */
    fun exists(gid: Long): Boolean {
        return list().any { it.gid == gid }
    }

    /**
     * 添加或更新映射
     * 性能：O(n) 读 + O(1) 写
     */
    @JvmStatic
    fun put(mapping: Mapping) {
        synchronized(cacheLock) {
            try {
                val updated = (cachedMappings ?: emptyList()).toMutableList()
                val idx = updated.indexOfFirst { it.gid == mapping.gid }
                if (idx >= 0) {
                    updated[idx] = mapping
                } else {
                    updated.add(mapping)
                }
                val arr = JSONArray()
                updated.forEach { arr.put(toJson(it)) }
                sp.edit().putString(KEY_MAPPINGS, arr.toString()).apply()
                cachedMappings = updated // 更新缓存
                Log.d(TAG, "Mapping added/updated for gid=${mapping.gid}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to put mapping", e)
                throw RuntimeException("Failed to save SMB mapping", e)
            }
        }
    }

    /**
     * 删除一个映射
     * 性能：O(n)
     * 
     * @return 如果成功删除返回 true，如果映射不存在返回 false
     */
    @JvmStatic
    fun remove(gid: Long): Boolean {
        synchronized(cacheLock) {
            try {
                val current = cachedMappings ?: emptyList()
                if (!current.any { it.gid == gid }) {
                    return false // 无需删除
                }
                val arr = JSONArray()
                val updated = current.filter { it.gid != gid }
                updated.forEach { arr.put(toJson(it)) }
                sp.edit().putString(KEY_MAPPINGS, arr.toString()).apply()
                cachedMappings = updated // 更新缓存
                Log.d(TAG, "Mapping removed for gid=$gid")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove mapping", e)
                return false
            }
        }
    }

    /**
     * 批量删除（删除下载时常用）
     * 性能：O(n)，通常用于删除多个下载项
     */
    fun removeAll(gids: Collection<Long>) {
        synchronized(cacheLock) {
            try {
                val gidSet = gids.toSet()
                val current = cachedMappings ?: emptyList()
                val updated = current.filter { it.gid !in gidSet }
                if (updated.size == current.size) {
                    return // 无需删除
                }
                val arr = JSONArray()
                updated.forEach { arr.put(toJson(it)) }
                sp.edit().putString(KEY_MAPPINGS, arr.toString()).apply()
                cachedMappings = updated
                Log.d(TAG, "Removed mappings for ${gids.size} gids")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove mappings", e)
            }
        }
    }

    /**
     * 清除所有损坏的映射，保留有效的
     * 
     * @return 删除的损坏条目数，-1 表示修复过程出错
     */
    fun repair(): Int {
        synchronized(cacheLock) {
            try {
                val json = sp.getString(KEY_MAPPINGS, "[]") ?: "[]"
                val arr = JSONArray(json)
                val repaired = mutableListOf<Mapping>()
                var corruptCount = 0
                for (i in 0 until arr.length()) {
                    try {
                        repaired += fromJson(arr.getJSONObject(i))
                    } catch (e: Exception) {
                        corruptCount++
                        Log.w(TAG, "Skipping corrupt mapping at index $i", e)
                    }
                }
                if (corruptCount > 0) {
                    val repairedArr = JSONArray()
                    repaired.forEach { repairedArr.put(toJson(it)) }
                    sp.edit().putString(KEY_MAPPINGS, repairedArr.toString()).apply()
                    cachedMappings = repaired
                    Log.i(TAG, "Repaired: removed $corruptCount corrupt mappings")
                }
                return corruptCount
            } catch (e: Exception) {
                Log.e(TAG, "Repair failed", e)
                return -1
            }
        }
    }

    private fun toJson(m: Mapping): JSONObject = JSONObject().apply {
        put("gid", m.gid)
        put("host", m.authority.host)
        put("port", m.authority.port)
        put("username", m.authority.username)
        put("domain", m.authority.domain)
        put("share", m.share)
        put("basePathInShare", m.basePathInShare)
    }

    private fun fromJson(o: JSONObject): Mapping {
        val authority = Authority(
            host = o.getString("host"),
            port = o.optInt("port", Authority.DEFAULT_PORT),
            username = o.getString("username"),
            domain = o.optString("domain").takeIf { it.isNotBlank() }
        )
        return Mapping(
            gid = o.getLong("gid"),
            authority = authority,
            share = o.getString("share"),
            basePathInShare = o.optString("basePathInShare", "")
        )
    }
}