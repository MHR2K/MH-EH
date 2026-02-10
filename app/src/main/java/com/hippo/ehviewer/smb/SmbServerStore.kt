package com.hippo.ehviewer.smb

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 轻量存储：使用 SharedPreferences 保存 SMB 服务器列表。
 * 
 * 优化：
 * - 内存缓存：避免每次都重新解析 JSON
 * - 线程安全：使用 synchronized 保护并发访问
 * - 密码加密：使用 SecureStorage 加密存储敏感信息
 */
object SmbServerStore {
    private const val PREF = "smb_servers"
    private const val KEY_SERVERS = "servers"

    @Volatile
    private lateinit var sp: SharedPreferences
    private lateinit var secureSp: SharedPreferences
    
    // 内存缓存，减少每次查询的 JSON 反序列化开销
    @Volatile
    private var cachedServers: List<SmbServer>? = null
    private val cacheLock = Any()

    // 标记是否已迁移到加密存储
    @Volatile
    private var isMigratedToEncrypted = false

    fun init(context: Context) {
        sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        secureSp = SecureStorage.getEncryptedPrefs(context)
        
        // 检查是否需要迁移到加密存储
        if (!isMigratedToEncrypted) {
            migrateToEncryptedIfNeeded(context)
        }
        
        // 初始化时预热缓存
        reloadCache()
    }

    /**
     * 迁移到加密存储（如果需要）
     */
    private fun migrateToEncryptedIfNeeded(context: Context) {
        try {
            val oldJson = sp.getString(KEY_SERVERS, null) ?: return
            if (oldJson.isEmpty() || oldJson == "[]") {
                isMigratedToEncrypted = true
                return
            }

            // 检查是否已迁移
            val newJson = secureSp.getString(KEY_SERVERS, null)
            if (newJson != null) {
                isMigratedToEncrypted = true
                return
            }

            // 执行迁移
            val arr = JSONArray(oldJson)
            val encryptedArr = JSONArray()
            
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    // 加密密码
                    val password = obj.getString("password")
                    val encryptedPassword = SecureStorage.encrypt(password, secureSp)
                    obj.put("password", encryptedPassword)
                    obj.put("encrypted", true) // 标记为已加密
                    encryptedArr.put(obj)
                } catch (e: Exception) {
                    // 跳过损坏的条目
                }
            }

            // 保存到加密存储
            secureSp.edit().putString(KEY_SERVERS, encryptedArr.toString()).apply()
            
            // 清除旧数据
            sp.edit().remove(KEY_SERVERS).apply()
            
            isMigratedToEncrypted = true
        } catch (e: Exception) {
            isMigratedToEncrypted = true // 即使迁移失败也标记为已尝试
        }
    }

    /**
     * 重新加载缓存（在手动修改 SP 时调用）
     */
    private fun reloadCache() {
        synchronized(cacheLock) {
            try {
                val json = secureSp.getString(KEY_SERVERS, "[]") ?: "[]"
                val arr = JSONArray(json)
                val out = mutableListOf<SmbServer>()
                for (i in 0 until arr.length()) {
                    try {
                        out += fromJson(arr.getJSONObject(i))
                    } catch (e: Exception) {
                        // 跳过损坏的条目
                    }
                }
                cachedServers = out
            } catch (e: Exception) {
                cachedServers = emptyList()
            }
        }
    }

    /**
     * 获取所有服务器列表（带缓存）
     * 性能：O(1)（缓存命中）或 O(n)（缓存缺失）
     */
    fun list(): List<SmbServer> {
        val cache = cachedServers
        return if (cache != null) {
            cache
        } else {
            reloadCache()
            cachedServers ?: emptyList()
        }
    }

    fun findByAuthority(authority: Authority): SmbServer? =
        list().firstOrNull { s ->
            val a = s.authority
            // 更健壮的匹配：host/domain 忽略大小写，端口精确匹配，用户名精确匹配，空/空串等价
            val hostEq = a.host.equals(authority.host, ignoreCase = true)
            val portEq = a.port == authority.port
            val userEq = a.username == authority.username
            val d1 = a.domain?.ifBlank { null }
            val d2 = authority.domain?.ifBlank { null }
            val domainEq = (d1 == null && d2 == null) || (d1 != null && d2 != null && d1.equals(d2, ignoreCase = true))
            hostEq && portEq && userEq && domainEq
        }

    fun addOrReplace(server: SmbServer) {
        synchronized(cacheLock) {
            val arr = JSONArray()
            val existing = (cachedServers ?: emptyList()).toMutableList()
            val idx = existing.indexOfFirst { it.id == server.id }
            if (idx >= 0) existing[idx] = server else existing.add(server)
            existing.forEach { arr.put(toJson(it)) }
            secureSp.edit().putString(KEY_SERVERS, arr.toString()).apply()
            cachedServers = existing
        }
    }

    fun remove(server: SmbServer) {
        synchronized(cacheLock) {
            val arr = JSONArray()
            val current = cachedServers ?: emptyList()
            val updated = current.filter { it.id != server.id }
            updated.forEach { arr.put(toJson(it)) }
            secureSp.edit().putString(KEY_SERVERS, arr.toString()).apply()
            cachedServers = updated
        }
    }

    private fun toJson(s: SmbServer): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("host", s.authority.host)
        put("port", s.authority.port)
        put("username", s.authority.username)
        put("domain", s.authority.domain)
        // 加密密码存储
        put("password", SecureStorage.encrypt(s.password, secureSp))
        put("encrypted", true)
        put("relativePath", s.relativePath)
    }

    private fun fromJson(o: JSONObject): SmbServer {
        val authority = Authority(
            host = o.getString("host"),
            port = o.optInt("port", Authority.DEFAULT_PORT),
            username = o.getString("username"),
            domain = o.optString("domain").takeIf { it.isNotBlank() }
        )
        
        // 获取并解密密码
        var password = o.getString("password")
        if (o.optBoolean("encrypted", false)) {
            password = SecureStorage.decrypt(password, secureSp)
        }
        
        return SmbServer(
            id = o.getLong("id"),
            name = o.optString("name").takeIf { it.isNotBlank() },
            authority = authority,
            password = password,
            relativePath = o.optString("relativePath", "")
        )
    }
}
