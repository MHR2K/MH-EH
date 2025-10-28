package com.hippo.ehviewer.smb

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 轻量存储：使用 SharedPreferences 保存 SMB 服务器列表。
 * 生产可替换为数据库或加密存储。
 */
object SmbServerStore {
    private const val PREF = "smb_servers"
    private const val KEY_SERVERS = "servers"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    fun list(): List<SmbServer> {
        val json = sp.getString(KEY_SERVERS, "[]") ?: "[]"
        val arr = JSONArray(json)
        val out = mutableListOf<SmbServer>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += fromJson(o)
        }
        return out
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
        val arr = JSONArray()
        val existing = list().toMutableList()
        val idx = existing.indexOfFirst { it.id == server.id }
        if (idx >= 0) existing[idx] = server else existing.add(server)
        existing.forEach { arr.put(toJson(it)) }
        sp.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    fun remove(server: SmbServer) {
        val arr = JSONArray()
        list().filter { it.id != server.id }.forEach { arr.put(toJson(it)) }
        sp.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    private fun toJson(s: SmbServer): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("host", s.authority.host)
        put("port", s.authority.port)
        put("username", s.authority.username)
        put("domain", s.authority.domain)
        put("password", s.password)
        put("relativePath", s.relativePath)
    }

    private fun fromJson(o: JSONObject): SmbServer {
        val authority = Authority(
            host = o.getString("host"),
            port = o.optInt("port", Authority.DEFAULT_PORT),
            username = o.getString("username"),
            domain = o.optString("domain").takeIf { it.isNotBlank() }
        )
        return SmbServer(
            id = o.getLong("id"),
            name = o.optString("name").takeIf { it.isNotBlank() },
            authority = authority,
            password = o.getString("password"),
            relativePath = o.optString("relativePath", "")
        )
    }
}
