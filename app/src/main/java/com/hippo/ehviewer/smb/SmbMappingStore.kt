package com.hippo.ehviewer.smb

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 记录每个画廊 gid 在 SMB 上的映射目录（authority/share/basePathInShare）。
 */
object SmbMappingStore {
    private const val PREF = "smb_gallery_mappings"
    private const val KEY_MAPPINGS = "mappings"

    private lateinit var sp: SharedPreferences

    data class Mapping(
        val gid: Long,
        val authority: Authority,
        val share: String,
        val basePathInShare: String
    )

    fun init(context: Context) {
        sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    fun list(): List<Mapping> {
        val json = sp.getString(KEY_MAPPINGS, "[]") ?: "[]"
        val arr = JSONArray(json)
        val out = mutableListOf<Mapping>()
        for (i in 0 until arr.length()) {
            out += fromJson(arr.getJSONObject(i))
        }
        return out
    }

    @JvmStatic
    fun get(gid: Long): Mapping? = list().firstOrNull { it.gid == gid }

    @JvmStatic
    fun put(mapping: Mapping) {
        val updated = list().toMutableList()
        val idx = updated.indexOfFirst { it.gid == mapping.gid }
        if (idx >= 0) updated[idx] = mapping else updated.add(mapping)
        val arr = JSONArray()
        updated.forEach { arr.put(toJson(it)) }
        sp.edit().putString(KEY_MAPPINGS, arr.toString()).apply()
    }

    @JvmStatic
    fun remove(gid: Long) {
        val arr = JSONArray()
        list().filter { it.gid != gid }.forEach { arr.put(toJson(it)) }
        sp.edit().putString(KEY_MAPPINGS, arr.toString()).apply()
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