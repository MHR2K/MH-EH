package com.hippo.ehviewer.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.hierynomus.smbj.share.Share
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import com.hippo.ehviewer.BuildConfig
import jcifs.context.SingletonContext
import java.io.IOException
import java.net.Inet4Address
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 轻量 SMB 客户端：支持连接、列出共享/目录、上传、删除、重命名。
 * 仅实现当前需求最小集合，便于快速落地。
 */
object Client {
    @Volatile
    lateinit var authenticator: Authenticator

    // 配置 SMB 客户端超时：增加超时时间以提高稳定性
    private val smbConfig = SmbConfig.builder()
        .withTimeout(10000, TimeUnit.MILLISECONDS)  // Socket 超时 10秒（原3秒）
        .withSoTimeout(15000, TimeUnit.MILLISECONDS) // SO 超时 15秒（原3秒）
        .build()
    
    // 重试配置
    private const val MAX_RETRY_COUNT = 3       // 最大重试次数
    private const val RETRY_DELAY_MS = 1000L   // 重试间隔（毫秒）
    
    private val client = SMBClient(smbConfig)
    
    // 连接池管理：添加会话活动和超时清理
    private val sessions = mutableMapOf<Authority, Session>()
    private val sessionActivity = mutableMapOf<Authority, Long>()
    private val sessionsLock = Any()

    // DiskShare 缓存：避免每次 openInputStream 都重新 connectShare
    private val shares = mutableMapOf<Pair<Authority, String>, DiskShare>()
    private val sharesLock = Any()
    
    // DNS 缓存：避免重复解析
    private val dnsCache = mutableMapOf<String, String>()
    private val dnsCacheLock = Any()
    
    // 会话超时：5分钟无活动则清理
    private val SESSION_TIMEOUT = 5 * 60 * 1000L
    // 为测试/一次性调用提供的临时密码（按线程隔离）
    private val tempPasswords = ThreadLocal<MutableMap<Authority, String>?>()

    // region 对外数据模型
    data class RemoteDirEntry(
        val name: String,
        val isDirectory: Boolean
    )

    data class Target(
        val authority: Authority,
        val share: String,
        val pathInShare: String // 以 \\ 分隔的 Windows 风格相对路径，可为空字符串
    )
    // endregion

    // region 目录/共享 列举
    @Throws(IOException::class)
    fun listShares(authority: Authority): List<String> {
        val session = getSession(authority)
        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
        val serverService = ServerService(transport)
        return serverService.shares1
            .filter { info ->
                // 仅磁盘共享，过滤打印/IPC 等
                val type = info.type.toLong()
                val STYPE_PRINTQ = 0x00000001
                val STYPE_DEVICE = 0x00000002
                val STYPE_IPC = 0x00000003
                type != STYPE_PRINTQ.toLong() && type != STYPE_DEVICE.toLong() && type != STYPE_IPC.toLong()
            }
            .map { it.netName }
    }

    @Throws(IOException::class)
    fun listDirectory(target: Target): List<RemoteDirEntry> {
        val share = getDiskShare(getSession(target.authority), target.share)
        val dir: com.hierynomus.smbj.share.Directory = try {
            share.openDirectory(
                target.pathInShare,
                setOf(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES),
                // 使用 NORMAL 代替 DIRECTORY，避免潜在实现中过滤出文件条目
                setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
        } catch (e: SMBRuntimeException) {
            throw IOException(e)
        }
        val raw = dir.list()
        if (BuildConfig.DEBUG) {
            try {
                android.util.Log.d("Client", "SMB listDirectory: path=" + target.pathInShare + ", entries=" + raw.size)
                raw.take(10).forEach { android.util.Log.d("Client", "  entry=" + it.fileName + " attr=" + it.fileAttributes) }
            } catch (_: Throwable) {}
        }
        return raw
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                val fa: Any? = it.fileAttributes
                val isDir = when (fa) {
                    is java.util.Set<*> -> fa.contains(FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                    is Int -> (fa and 0x10) != 0
                    is Long -> (fa and 0x10L) != 0L
                    else -> false
                }
                RemoteDirEntry(it.fileName, isDir)
            }
    }
    // endregion

    // region 上传/删除/重命名
    // 上传缓冲区大小：256KB，提升大文件传输性能
    private val UPLOAD_BUFFER_SIZE = 256 * 1024

    @Throws(IOException::class)
    fun upload(target: Target, input: java.io.InputStream, overwrite: Boolean = true) {
        val share = getDiskShare(getSession(target.authority), target.share)
        val disposition = if (overwrite) SMB2CreateDisposition.FILE_OVERWRITE_IF else SMB2CreateDisposition.FILE_CREATE
        val file: File = try {
            share.openFile(
                target.pathInShare,
                setOf(AccessMask.FILE_WRITE_DATA, AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_WRITE_ATTRIBUTES),
                setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                disposition,
                null
            )
        } catch (e: SMBRuntimeException) {
            throw IOException(e)
        }
        file.use { f ->
            val buffer = ByteArray(UPLOAD_BUFFER_SIZE)
            var offset = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                try {
                    f.write(buffer, offset, 0, read)
                } catch (e: SMBRuntimeException) {
                    throw IOException(e)
                }
                offset += read
            }
        }
    }

    @Throws(IOException::class)
    fun delete(target: Target) {
        val share = getDiskShare(getSession(target.authority), target.share)
        try {
            share.rm(target.pathInShare)
        } catch (e: SMBRuntimeException) {
            throw IOException(e)
        }
    }

    @Throws(IOException::class)
    fun deleteDirectory(target: Target) {
        val share = getDiskShare(getSession(target.authority), target.share)
        try {
            share.rmdir(target.pathInShare, true)
        } catch (e: SMBRuntimeException) {
            throw IOException(e)
        }
    }

    @Throws(IOException::class)
    fun rename(target: Target, newPathInShare: String) {
        val share = getDiskShare(getSession(target.authority), target.share)
        val file = try {
            share.openFile(
                target.pathInShare,
                setOf(AccessMask.DELETE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
        } catch (e: SMBRuntimeException) {
            throw IOException(e)
        }
        try {
            file.rename(newPathInShare, true)
        } catch (e: SMBRuntimeException) {
            throw IOException(e)
        } finally {
            try { file.close() } catch (_: Throwable) {}
        }
    }

    /**
     * 读取文件，返回一个 InputStream。调用者需在读取结束后关闭流。
     * 优化：使用 256KB 缓冲提升大文件读取性能
     * 改进：添加重试机制提高稳定性
     */
    @Throws(IOException::class)
    fun openInputStream(target: Target): java.io.InputStream {
        var lastException: IOException? = null

        for (attempt in 1..MAX_RETRY_COUNT) {
            try {
                return doOpenInputStream(target)
            } catch (e: SMBApiException) {
                // 文件不存在直接失败，不重试（扩展名探测场景）
                if (e.status == NtStatus.STATUS_NO_SUCH_FILE) {
                    throw IOException("File not found: ${target.pathInShare}", e)
                }
                lastException = IOException(e)
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("Client", "openInputStream attempt $attempt SMB error: ${e.status}")
                }
            } catch (e: IOException) {
                lastException = e
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("Client", "openInputStream attempt $attempt failed: ${e.message}")
                }
            }
            if (attempt < MAX_RETRY_COUNT) {
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted during retry", ie)
                }
            }
        }

        throw lastException ?: IOException("Failed to open input stream after $MAX_RETRY_COUNT attempts")
    }
    
    /**
     * 实际执行文件打开操作
     */
    @Throws(IOException::class)
    private fun doOpenInputStream(target: Target): java.io.InputStream {
        val share = getDiskShare(getSession(target.authority), target.share)
        val file: File = try {
            share.openFile(
                target.pathInShare,
                setOf(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
        } catch (e: SMBRuntimeException) {
            throw IOException(e)
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Client", "SMB openInputStream success: path=${target.pathInShare}")
        }
        return object : java.io.InputStream() {
            private var offset = 0L
            private var closed = false
            // 使用 256KB 缓冲提升读取效率
            private val bufferSize = 256 * 1024

            override fun read(): Int {
                val b = ByteArray(1)
                val r = read(b, 0, 1)
                return if (r == -1) -1 else (b[0].toInt() and 0xFF)
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (closed) throw IOException("Stream already closed")
                if (len == 0) return 0
                return try {
                    val toRead = kotlin.math.min(len, bufferSize)
                    val buf = if (off == 0 && len == b.size && b.size == toRead) b else ByteArray(toRead)
                    var read = 0
                    try {
                        read = file.read(buf, offset, 0, toRead)
                    } catch (e: SMBRuntimeException) {
                        throw IOException(e)
                    }
                    if (read <= 0) -1 else {
                        if (buf !== b) {
                            System.arraycopy(buf, 0, b, off, read)
                        }
                        offset += read
                        read
                    }
                } catch (e: IOException) {
                    throw e
                }
            }

            override fun close() {
                if (!closed) {
                    closed = true
                    try { file.close() } catch (_: Throwable) {}
                }
            }
        }
    }

    /**
     * 查询 SMB 文件大小（字节）
     * 用于下载进度计算
     */
    @Throws(IOException::class)
    fun getFileSize(target: Target): Long {
        val share = getDiskShare(getSession(target.authority), target.share)
        val file: File = try {
            share.openFile(
                target.pathInShare,
                setOf(AccessMask.FILE_READ_ATTRIBUTES),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
        } catch (e: SMBRuntimeException) {
            throw IOException(e)
        }
        return try {
            val info = file.getFileInformation()
            val size = info.standardInformation.endOfFile
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Client", "SMB getFileSize: path=${target.pathInShare}, size=$size")
            }
            size
        } catch (e: SMBRuntimeException) {
            throw IOException(e)
        } finally {
            try { file.close() } catch (_: Throwable) {}
        }
    }

    /**
     * 递归创建目录 target.pathInShare
     */
    @Throws(IOException::class)
    fun mkdirs(target: Target) {
        val share = getDiskShare(getSession(target.authority), target.share)
        val p = target.pathInShare.trim('\n','\r','\t',' ').trim('/', '\\')
        if (p.isEmpty()) return
        val parts = p.split('\\', '/')
        var cur = ""
        for (part in parts) {
            cur = if (cur.isEmpty()) part else "$cur\\$part"
            try {
                if (!share.folderExists(cur)) {
                    share.mkdir(cur)
                }
            } catch (e: SMBRuntimeException) {
                // 可能存在并发创建或权限问题，二次校验
                try {
                    if (!share.folderExists(cur)) throw IOException(e)
                } catch (ee: SMBRuntimeException) {
                    throw IOException(ee)
                }
            }
        }
    }
    // endregion

    // region 会话/连接
    @Throws(IOException::class)
    private fun getSession(authority: Authority): Session {
        // 清理过期会话
        cleanupExpiredSessions()
        
        synchronized(sessionsLock) {
            sessions[authority]?.let { session ->
                if (session.connection.isConnected) {
                    // 更新会话活动时间
                    sessionActivity[authority] = System.currentTimeMillis()
                    return session
                }
                try { session.close() } catch (_: Throwable) {}
                try { session.connection.close() } catch (_: Throwable) {}
                sessions.remove(authority)
                sessionActivity.remove(authority)
            }
            val password = tempPasswords.get()?.get(authority)
                ?: authenticator.getPassword(authority)
                ?: run {
                    // 回退：按 host/port/domain 匹配（忽略用户名差异）尝试获取密码
                    val d1 = authority.domain?.ifBlank { null }
                    val fromStore = try {
                        SmbServerStore.list().firstOrNull { s ->
                            val a = s.authority
                            val hostEq = a.host.equals(authority.host, ignoreCase = true)
                            val portEq = a.port == authority.port
                            val d2 = a.domain?.ifBlank { null }
                            val domainEq = (d1 == null && d2 == null) || (d1 != null && d2 != null && d1.equals(d2, ignoreCase = true))
                            hostEq && portEq && domainEq
                        }
                    } catch (_: Throwable) { null }
                    fromStore?.password
                }
                ?: throw IOException("No password for $authority")
            val hostAddress = resolveHostNameWithCache(authority.host)
            val connection = try {
                client.connect(hostAddress, authority.port)
            } catch (e: IOException) {
                throw e
            }
            val auth = AuthenticationContext(authority.username, password.toCharArray(), authority.domain)
            val session = try {
                connection.authenticate(auth)
            } catch (e: SMBRuntimeException) {
                try { connection.close() } catch (_: Throwable) {}
                throw IOException(e)
            }
            sessions[authority] = session
            sessionActivity[authority] = System.currentTimeMillis()
            return session
        }
    }

    /**
     * 清理过期会话（5分钟无活动）
     */
    private fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        synchronized(sessionsLock) {
            val expired = sessionActivity.filter { (authority, lastActivity) ->
                now - lastActivity > SESSION_TIMEOUT
            }.keys
            for (authority in expired) {
                try {
                    sessions[authority]?.close()
                } catch (_: Throwable) {}
                try {
                    sessions[authority]?.connection?.close()
                } catch (_: Throwable) {}
                sessions.remove(authority)
                sessionActivity.remove(authority)
                // 清理该 authority 的 DiskShare 缓存
                synchronized(sharesLock) {
                    shares.keys.removeAll { it.first == authority }
                }
            }
        }
    }

    /**
     * 在当前线程作用域内，为指定 authority 提供一次性密码，执行 [block] 并在结束后清理。
     */
    fun <T> withTempPassword(authority: Authority, password: String, block: () -> T): T {
        val map = tempPasswords.get() ?: mutableMapOf<Authority, String>().also { tempPasswords.set(it) }
        map[authority] = password
        try {
            return block()
        } finally {
            map.remove(authority)
        }
    }

    @Throws(IOException::class)
    private fun resolveHostName(hostName: String): String {
        // 检查缓存
        synchronized(dnsCacheLock) {
            dnsCache[hostName]?.let { return it }
        }
        
        try {
            val nameServiceClient = SingletonContext.getInstance().nameServiceClient
            val addresses = nameServiceClient.getAllByName(hostName, false).mapNotNull { it.toInetAddress() }
            val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.first()
            val result = address.hostAddress ?: hostName
            
            // 存入缓存
            synchronized(dnsCacheLock) {
                dnsCache[hostName] = result
            }
            return result
        } catch (e: UnknownHostException) {
            // 回退到原始主机名
            return hostName
        }
    }

    /**
     * 带缓存的 DNS 解析（别名方法）
     */
    @Throws(IOException::class)
    private fun resolveHostNameWithCache(hostName: String): String = resolveHostName(hostName)

    @Throws(IOException::class)
    private fun getShare(session: Session, shareName: String): Share = try {
        session.connectShare(shareName)
    } catch (e: SMBRuntimeException) {
        throw IOException(e)
    }

    @Throws(IOException::class)
    private fun getDiskShare(session: Session, shareName: String): DiskShare {
        // 从当前活跃 session 对应的 authority 查找缓存
        val authority = sessions.entries.find { it.value === session }?.key
        if (authority != null) {
            val key = Pair(authority, shareName)
            synchronized(sharesLock) {
                shares[key]?.let { share ->
                    if (share.isConnected) return share
                    // 连接已断开，移除缓存
                    shares.remove(key)
                }
            }
        }
        val share = (getShare(session, shareName) as? DiskShare)
            ?: throw IOException("$shareName is not a DiskShare")
        if (authority != null) {
            synchronized(sharesLock) {
                shares[Pair(authority, shareName)] = share
            }
        }
        return share
    }
    // endregion

    // region 路径存在性检查
    
    /**
     * 检查 SMB 路径是否存在（文件或目录）
     * 改进：添加重试机制提高稳定性
     */
    @Throws(IOException::class)
    fun exists(target: Target): Boolean {
        var lastException: IOException? = null
        
        for (attempt in 1..MAX_RETRY_COUNT) {
            try {
                return doExists(target)
            } catch (e: IOException) {
                lastException = e
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("Client", "exists attempt $attempt failed: ${e.message}")
                }
                if (attempt < MAX_RETRY_COUNT) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Interrupted during retry", ie)
                    }
                }
            }
        }
        
        throw lastException ?: IOException("Failed to check path existence after $MAX_RETRY_COUNT attempts")
    }
    
    /**
     * 实际执行路径检查操作
     */
    @Throws(IOException::class)
    private fun doExists(target: Target): Boolean {
        val share = getDiskShare(getSession(target.authority), target.share)
        return try {
            share.fileExists(target.pathInShare) || share.folderExists(target.pathInShare)
        } catch (e: SMBRuntimeException) {
            throw IOException(e)
        }
    }
    
    /**
     * 检查目录是否存在
     */
    @Throws(IOException::class)
    fun folderExists(target: Target): Boolean {
        return try {
            val share = getDiskShare(getSession(target.authority), target.share)
            share.folderExists(target.pathInShare)
        } catch (e: SMBRuntimeException) {
            throw IOException(e)
        }
    }
    
    // endregion
}
