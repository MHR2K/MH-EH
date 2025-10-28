package com.hippo.ehviewer.smb

import android.content.Context
import android.content.Intent
import kotlinx.parcelize.Parcelize
import android.os.Parcelable

/**
 * SMB 服务器配置模型。
 * relativePath 形如："share" 或 "share\\subdir"。可为空字符串表示服务器根（仅用于连通性测试/列出共享）。
 */
@Parcelize
data class SmbServer(
    val id: Long,
    val name: String?,
    val authority: Authority,
    val password: String,
    val relativePath: String
) : Parcelable {
    fun toTarget(): Client.Target? {
        // 支持用户输入的 / 或 \\ 分隔符，统一为 \\，并去掉首尾分隔符
        val normalized = relativePath
            .trim()
            .replace('/', '\\')
            .trim('\\')
        if (normalized.isBlank()) return null
        val firstSep = normalized.indexOf('\\')
        val share = if (firstSep == -1) normalized else normalized.substring(0, firstSep)
        val pathInShare = if (firstSep == -1) "" else normalized.substring(firstSep + 1)
        return Client.Target(authority, share, pathInShare)
    }

    companion object {
        fun newId(): Long = java.util.UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
    }
}
