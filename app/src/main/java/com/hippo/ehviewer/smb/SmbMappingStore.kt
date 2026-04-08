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
 * - Map 索引 gid：O(1) 查询（替代原有的 O(n) List 查找）
 * - 线程安全：使用 synchronized 保护并发访问
 * - 批量操作：支持一次删除多个映射
 * - 错误处理：损坏数据自动跳过而非崩溃
 */
object SmbMappingStore {
    private const val PREF = "smb_gallery_mappings"
    private const val KEY_MAPPINGS = "mappings"
    private const val TAG = "SmbMappingStore"

    private lateinit var sp: SharedPreferences
    
    // 内存缓存，使用 Map 索引 gid 实现 O(1) 查询
    @Volatile
    private var cachedMappings: Map<Long, Mapping>? = null
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
                val out = mutableMapOf<Long, Mapping>()
                for (i in 0 until arr.length()) {
                    try {
                        val mapping = fromJson(arr.getJSONObject(i))
                        out[mapping.gid] = mapping
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse mapping at index $i", e)
                        // 跳过损坏的条目，继续处理
                    }
                }
                cachedMappings = out
                Log.d(TAG, "Cache reloaded: ${out.size} mappings")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reload cache", e)
                cachedMappings = emptyMap()
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
        return cachedMappings?.values?.toList() ?: emptyList()
    }

    /**
     * 根据 gid 查询映射
     * 性能：O(1)（使用 Map 索引）
     */
    @JvmStatic
    fun get(gid: Long): Mapping? {
        // 确保缓存已加载
        if (cachedMappings == null) {
            reloadCache()
        }
        return cachedMappings?.get(gid)
    }

    /**
     * 批量查询（减少遍历次数）
     * 性能：O(m)，其中 m=gids 数量
     */
    fun getAll(gids: Collection<Long>): List<Mapping> {
        if (cachedMappings == null) {
            reloadCache()
        }
        val map = cachedMappings ?: emptyMap()
        return gids.mapNotNull { map[it] }
    }

    /**
     * 检查是否存在映射
     * 性能：O(1)
     */
    fun exists(gid: Long): Boolean {
        return cachedMappings?.containsKey(gid) ?: run {
            if (cachedMappings == null) reloadCache()
            cachedMappings?.containsKey(gid) ?: false
        }
    }

    /**
     * 添加或更新映射
     * 性能：O(1)
     */
    @JvmStatic
    fun put(mapping: Mapping) {
        synchronized(cacheLock) {
            try {
                val updatedMap = (cachedMappings ?: emptyMap()).toMutableMap()
                updatedMap[mapping.gid] = mapping
                val arr = JSONArray()
                updatedMap.values.forEach { arr.put(toJson(it)) }
                sp.edit().putString(KEY_MAPPINGS, arr.toString()).apply()
                cachedMappings = updatedMap
                Log.d(TAG, "Mapping added/updated for gid=${mapping.gid}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to put mapping", e)
                throw RuntimeException("Failed to save SMB mapping", e)
            }
        }
    }

    /**
     * 删除一个映射
     * 性能：O(1)
     * 
     * @return 如果成功删除返回 true，如果映射不存在返回 false
     */
    @JvmStatic
    fun remove(gid: Long): Boolean {
        synchronized(cacheLock) {
            try {
                val current = cachedMappings ?: emptyMap()
                if (!current.containsKey(gid)) {
                    return false
                }
                val updatedMap = current.toMutableMap()
                updatedMap.remove(gid)
                val arr = JSONArray()
                updatedMap.values.forEach { arr.put(toJson(it)) }
                sp.edit().putString(KEY_MAPPINGS, arr.toString()).apply()
                cachedMappings = updatedMap
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
     * 性能：O(m + n)，通常用于删除多个下载项
     */
    fun removeAll(gids: Collection<Long>) {
        synchronized(cacheLock) {
            try {
                val gidSet = gids.toSet()
                val current = cachedMappings ?: emptyMap()
                val updatedMap = current.filterKeys { it !in gidSet }
                if (updatedMap.size == current.size) {
                    return
                }
                val arr = JSONArray()
                updatedMap.values.forEach { arr.put(toJson(it)) }
                sp.edit().putString(KEY_MAPPINGS, arr.toString()).apply()
                cachedMappings = updatedMap
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
                val repaired = mutableMapOf<Long, Mapping>()
                var corruptCount = 0
                for (i in 0 until arr.length()) {
                    try {
                        val mapping = fromJson(arr.getJSONObject(i))
                        repaired[mapping.gid] = mapping
                    } catch (e: Exception) {
                        corruptCount++
                        Log.w(TAG, "Skipping corrupt mapping at index $i", e)
                    }
                }
                if (corruptCount > 0) {
                    val repairedArr = JSONArray()
                    repaired.values.forEach { repairedArr.put(toJson(it)) }
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

    /**
     * 导出所有映射到 JSON 字符串（用于备份）
     */
    @JvmStatic
    fun exportToJson(): String {
        try {
            val arr = JSONArray()
            list().forEach { mapping ->
                arr.put(toJson(mapping))
            }
            return arr.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export mappings", e)
            return "[]"
        }
    }

    /**
     * 从 JSON 字符串导入映射（用于恢复）
     */
    @JvmStatic
    fun importFromJson(json: String) {
        if (json.isBlank()) return
        try {
            val arr = JSONArray(json)
            synchronized(cacheLock) {
                val updatedMap = mutableMapOf<Long, Mapping>()
                for (i in 0 until arr.length()) {
                    try {
                        val mapping = fromJson(arr.getJSONObject(i))
                        updatedMap[mapping.gid] = mapping
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse mapping at index $i", e)
                    }
                }
                // 保存到 SharedPreferences
                val saveArr = JSONArray()
                updatedMap.values.forEach { saveArr.put(toJson(it)) }
                sp.edit().putString(KEY_MAPPINGS, saveArr.toString()).apply()
                cachedMappings = updatedMap
                Log.d(TAG, "Imported ${updatedMap.size} mappings")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import mappings", e)
        }
    }

    // region 映射验证功能
    
    /**
     * 验证映射有效性结果
     */
    enum class ValidationStatus {
        VALID,           // 映射有效
        MISSING,         // 映射不存在
        SERVER_UNREACHABLE,  // 服务器不可达
        AUTH_FAILED,         // 认证失败
        PATH_NOT_FOUND,      // 路径不存在
        UNKNOWN_ERROR        // 未知错误
    }

    data class ValidationResult(
        val status: ValidationStatus,
        val message: String? = null,
        val exception: Exception? = null
    )

    /**
     * 验证单个映射是否有效
     */
    fun validateMapping(gid: Long): ValidationResult {
        val mapping = get(gid) ?: return ValidationResult(ValidationStatus.MISSING)
        return try {
            val target = Client.Target(
                authority = mapping.authority,
                share = mapping.share,
                pathInShare = mapping.basePathInShare
            )
            val exists = Client.exists(target)
            if (exists) {
                ValidationResult(ValidationStatus.VALID)
            } else {
                ValidationResult(ValidationStatus.PATH_NOT_FOUND, "目录不存在或无法访问")
            }
        } catch (e: Exception) {
            val status = when {
                e.message?.contains("timeout", ignoreCase = true) == true -> ValidationStatus.SERVER_UNREACHABLE
                e.message?.contains("auth", ignoreCase = true) == true -> ValidationStatus.AUTH_FAILED
                e.message?.contains("Access is denied", ignoreCase = true) == true -> ValidationStatus.AUTH_FAILED
                e.message?.contains("not found", ignoreCase = true) == true -> ValidationStatus.PATH_NOT_FOUND
                e.message?.contains("No password", ignoreCase = true) == true -> ValidationStatus.AUTH_FAILED
                e.message?.contains("unreachable", ignoreCase = true) == true -> ValidationStatus.SERVER_UNREACHABLE
                else -> ValidationStatus.UNKNOWN_ERROR
            }
            ValidationResult(status, e.message, e)
        }
    }

    /**
     * 批量验证所有映射
     */
    fun validateAllMappings(): Map<Long, ValidationResult> {
        return list().associate { it.gid to validateMapping(it.gid) }
    }

    /**
     * 获取无效映射列表
     */
    fun getInvalidMappings(): List<Pair<Long, ValidationResult>> {
        return validateAllMappings()
            .filter { it.value.status != ValidationStatus.VALID }
            .map { it.key to it.value }
    }

    // endregion

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