package com.hippo.ehviewer.smb

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * SMB 服务器标识（主机/端口/用户名/域）。
 */
@Parcelize
data class Authority(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val username: String,
    val domain: String? = null
) : Parcelable {
    override fun toString(): String {
        val userInfo = if (!domain.isNullOrBlank()) "$domain\\$username" else username
        val portPart = if (port != DEFAULT_PORT) ":$port" else ""
        return "$userInfo@$host$portPart"
    }

    /**
     * 自定义 hashCode 和 equals，确保与 SmbServerStore.findByAuthority 匹配逻辑一致
     */
    override fun hashCode(): Int {
        var result = host.lowercase().hashCode()
        result = 31 * result + port
        result = 31 * result + username.hashCode()
        result = 31 * result + (domain?.lowercase()?.hashCode() ?: 0)
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Authority
        return host.equals(other.host, ignoreCase = true) &&
                port == other.port &&
                username == other.username &&
                domain.equals(other.domain, ignoreCase = true)
    }

    companion object {
        const val DEFAULT_PORT: Int = 445
    }
}
