package com.hippo.ehviewer.smb

import com.hippo.ehviewer.EhDB

/**
 * 通过 DB 中的 dirname 直接推导 SMB 路径。
 *
 * 逻辑：EhDB.getDownloadDirname(gid) → 拼接 server.share + server.basePath + dirname
 * 本地和 SMB 保持一致的目录命名（{gid}-{title}），无需额外映射表。
 */
object SmbPathResolver {

    /**
     * 根据 gid 推导 SMB 路径。
     * 使用第一个配置的 SMB 服务器。
     * @return Client.Target 或 null（无dirname/无服务器/relativePath为空）
     */
    @JvmStatic
    fun resolve(gid: Long): Client.Target? {
        val server = SmbServerStore.list().firstOrNull() ?: return null
        return resolveForServer(gid, server)
    }

    /**
     * 根据 gid 和指定服务器推导 SMB 路径。
     */
    @JvmStatic
    fun resolveForServer(gid: Long, server: SmbServer): Client.Target? {
        val dirname = EhDB.getDownloadDirname(gid) ?: return null
        val baseTarget = server.toTarget() ?: return null
        val smbPath = if (baseTarget.pathInShare.isEmpty()) {
            dirname
        } else {
            baseTarget.pathInShare + "\\" + dirname
        }
        return Client.Target(baseTarget.authority, baseTarget.share, smbPath)
    }
}
