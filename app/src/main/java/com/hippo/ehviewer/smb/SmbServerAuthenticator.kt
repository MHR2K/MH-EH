package com.hippo.ehviewer.smb

/**
 * 从存储中读取密码，作为 Client 的 Authenticator。
 */
object SmbServerAuthenticator : Authenticator {
    override fun getPassword(authority: Authority): String? =
        SmbServerStore.findByAuthority(authority)?.password
}
