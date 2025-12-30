package com.hippo.ehviewer.smb

/**
 * 提供密码的回调，用于 Client 获取凭据。
 */
fun interface Authenticator {
    fun getPassword(authority: Authority): String?
}
