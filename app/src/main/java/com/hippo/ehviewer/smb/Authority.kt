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

    companion object {
        const val DEFAULT_PORT: Int = 445
    }
}
