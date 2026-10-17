package com.hippo.ehviewer.smb.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hippo.ehviewer.databinding.ActivitySmbAddServerBinding
import com.hippo.ehviewer.smb.Authority
import com.hippo.ehviewer.smb.Client
import com.hippo.ehviewer.smb.SmbServer
import com.hippo.ehviewer.smb.SmbServerStore
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 极简“添加 SMB 服务器”页面：输入参数 -> 测试连接 -> 保存。
 */
class SmbAddServerActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySmbAddServerBinding
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private var editingId: Long? = null

    companion object {
        private const val EXTRA_SERVER_ID = "extra_server_id"

        @JvmStatic
        fun start(context: Context) {
            val intent = Intent(context, SmbAddServerActivity::class.java)
            context.startActivity(intent)
        }

        @JvmStatic
        fun start(context: Context, serverId: Long?) {
            val intent = Intent(context, SmbAddServerActivity::class.java)
            if (serverId != null) intent.putExtra(EXTRA_SERVER_ID, serverId)
            context.startActivity(intent)
        }
    }

    private fun updatePreview() {
        val host = binding.editHost.text?.toString()?.trim().orEmpty()
        val port = binding.editPort.text?.toString()?.toIntOrNull() ?: Authority.DEFAULT_PORT
        val username = binding.editUsername.text?.toString()?.trim().orEmpty()
        val domain = binding.editDomain.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
        val password = binding.editPassword.text?.toString() ?: ""
        val path = binding.editPath.text?.toString()?.trim().orEmpty()

        val userPart = if (!domain.isNullOrBlank()) "$domain\\$username" else username
        val portPart = if (port != Authority.DEFAULT_PORT) ":$port" else ""
        val pathPart = path.replace('\\', '/').let { if (it.isBlank()) "" else if (it.startsWith("/")) it else "/$it" }
        val preview = "smb://$userPart:$password@$host$portPart$pathPart"
        binding.textPreview.text = preview
    }

    private fun fillFields(s: SmbServer) {
        editingId = s.id
        binding.editHost.setText(s.authority.host)
        binding.editPort.setText(s.authority.port.toString())
        binding.editUsername.setText(s.authority.username)
        binding.editDomain.setText(s.authority.domain ?: "")
        binding.editPassword.setText(s.password)
        binding.editPath.setText(s.relativePath)
        binding.editName.setText(s.name ?: "")
        binding.btnTestAndSave.text = "测试连接并更新"
        binding.btnDelete.visibility = View.VISIBLE
        updatePreview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmbAddServerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 若带入 serverId，进入编辑模式；否则自动回填第一条（若存在）
        val incomingId = intent.getLongExtra(EXTRA_SERVER_ID, -1L).takeIf { it != -1L }
        val initialServer = when {
            incomingId != null -> SmbServerStore.list().firstOrNull { it.id == incomingId }
            else -> SmbServerStore.list().firstOrNull()
        }
        initialServer?.let { fillFields(it) }
        listOf(
            binding.editHost,
            binding.editPort,
            binding.editUsername,
            binding.editDomain,
            binding.editPassword,
            binding.editPath
        ).forEach { et ->
            et.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { updatePreview() }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        updatePreview()

        binding.btnTestAndSave.setOnClickListener {
            val host = binding.editHost.text?.toString()?.trim().orEmpty()
            val port = binding.editPort.text?.toString()?.toIntOrNull() ?: Authority.DEFAULT_PORT
            val username = binding.editUsername.text?.toString()?.trim().orEmpty()
            val domain = binding.editDomain.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
            val password = binding.editPassword.text?.toString() ?: ""
            val path = binding.editPath.text?.toString()?.trim().orEmpty()
            val name = binding.editName.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }

            if (host.isBlank() || username.isBlank()) {
                Toast.makeText(this, "主机与用户名不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val authority = Authority(host, port, username, domain)
            val serverId = editingId ?: SmbServer.newId()
            val server = SmbServer(serverId, name, authority, password, path)

            val originalBtnText = binding.btnTestAndSave.text
            binding.btnTestAndSave.isEnabled = false
            binding.btnTestAndSave.text = "正在测试连接..."

            scope.launch {
                var failureError: ConnectionErrorInfo? = null
                val ok = withContext(Dispatchers.IO) {
                    try {
                        val target = server.toTarget()
                        Client.withTempPassword(authority, password) {
                            if (target == null) {
                                // 未填写共享名：仅测试可否列出共享
                                Client.listShares(authority)
                            } else {
                                Client.listDirectory(target)
                            }
                        }
                        true
                    } catch (t: Throwable) {
                        t.printStackTrace()
                        Client.clearDnsCache(authority.host)
                        failureError = parseConnectionException(t, authority.host)
                        false
                    }
                }
                binding.btnTestAndSave.isEnabled = true
                binding.btnTestAndSave.text = originalBtnText

                if (ok) {
                    SmbServerStore.addOrReplace(server)
                    val msg = if (editingId != null) "连接成功，已更新" else "连接成功，已保存"
                    Toast.makeText(this@SmbAddServerActivity, msg, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    val err = failureError ?: ConnectionErrorInfo(
                        title = "连接失败",
                        summary = "无法连接到 SMB 服务器，请检查配置。",
                        details = listOf("请检查主机、用户名和密码是否正确。")
                    )
                    showErrorDialog(err)
                }
            }
        }

        binding.btnDelete.setOnClickListener {
            val id = editingId
            if (id == null) {
                Toast.makeText(this, "当前不是编辑模式", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("删除服务器")
                .setMessage("确定要删除此 SMB 服务器吗？")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("删除") { _, _ ->
                    val target = SmbServerStore.list().firstOrNull { it.id == id }
                    if (target != null) {
                        SmbServerStore.remove(target)
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "未找到该服务器，可能已删除", Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }
                .show()
        }
    }

    private data class ConnectionErrorInfo(
        val title: String,
        val summary: String,
        val details: List<String>
    )

    private fun parseConnectionException(throwable: Throwable, host: String): ConnectionErrorInfo {
        var current: Throwable? = throwable
        var connectEx: ConnectException? = null
        var timeoutEx: SocketTimeoutException? = null
        var unknownHostEx: UnknownHostException? = null
        var noRouteEx: NoRouteToHostException? = null
        var smbApiEx: SMBApiException? = null

        while (current != null) {
            when (current) {
                is ConnectException -> if (connectEx == null) connectEx = current
                is SocketTimeoutException -> if (timeoutEx == null) timeoutEx = current
                is UnknownHostException -> if (unknownHostEx == null) unknownHostEx = current
                is NoRouteToHostException -> if (noRouteEx == null) noRouteEx = current
                is SMBApiException -> if (smbApiEx == null) smbApiEx = current
            }
            current = current.cause
        }

        return when {
            connectEx != null -> {
                ConnectionErrorInfo(
                    title = "连接被拒绝 (端口 445 不通)",
                    summary = "目标主机拒绝了连接：${connectEx.message ?: "Connection refused"}",
                    details = listOf(
                        "请检查电脑是否已开启 SMB 文件共享服务。",
                        "Windows 防火墙可能拦截了 445 端口。若电脑插网线，请打开 Windows 设置 -> 网络和 Internet -> 以太网，将网络类型由『公用网络』切换为『专用网络』。",
                        "确认电脑未被第三方杀毒软件/防火墙禁止局域网访问。"
                    )
                )
            }
            timeoutEx != null -> {
                ConnectionErrorInfo(
                    title = "连接超时 (Host 无法响应)",
                    summary = "在规定时间内未收到电脑响应：${timeoutEx.message ?: "Socket timed out"}",
                    details = listOf(
                        "电脑插网线和连 Wi-Fi 时的 IP 地址通常不同，请在电脑 cmd 运行 ipconfig 确认以太网 IPv4 地址。",
                        "请确保手机与电脑处于同一局域网（避免处于光猫与二级路由的不同子网）。",
                        "确认路由器未开启 AP 隔离或访客网络（禁止 Wi-Fi 与有线互相访问）。",
                        "确认电脑未处于锁屏休眠或关机状态。"
                    )
                )
            }
            noRouteEx != null -> {
                ConnectionErrorInfo(
                    title = "网络不可达 (No route to host)",
                    summary = "无法路由到目标地址：${noRouteEx.message ?: "No route to host"}",
                    details = listOf(
                        "手机与电脑不在同一网段，请检查路由器和网络拓扑。",
                        "请检查手机是否已连接正确的 Wi-Fi。"
                    )
                )
            }
            unknownHostEx != null -> {
                ConnectionErrorInfo(
                    title = "无法解析计算机名",
                    summary = "未能解析主机名：$host",
                    details = listOf(
                        "路由器可能隔离了 Wi-Fi 与有线网口之间的 NetBIOS 广播。",
                        "建议直接在『主机』栏填写电脑的局域网 IPv4 地址（如 192.168.x.x）代替计算机名。"
                    )
                )
            }
            smbApiEx != null -> {
                when (smbApiEx.status) {
                    NtStatus.STATUS_LOGON_FAILURE -> {
                        ConnectionErrorInfo(
                            title = "认证失败 (账号或密码错误)",
                            summary = "用户名或密码不正确 (${smbApiEx.status})",
                            details = listOf(
                                "请核对输入的用户名和密码。",
                                "若使用微软在线账户登录 Windows，用户名请尝试输入微软账户完整邮箱，或在 Windows 中创建本地账户后重试。",
                                "若电脑属于工作组，可尝试在『域』栏填写 WORKGROUP。"
                            )
                        )
                    }
                    NtStatus.STATUS_ACCESS_DENIED -> {
                        ConnectionErrorInfo(
                            title = "访问被拒绝 (权限不足)",
                            summary = "已连接到服务器，但无权访问此共享目录 (${smbApiEx.status})",
                            details = listOf(
                                "请在电脑上右键共享文件夹 -> 属性 -> 共享 / 安全，将该账户添加为具有读取权限的用户。"
                            )
                        )
                    }
                    NtStatus.STATUS_BAD_NETWORK_NAME -> {
                        ConnectionErrorInfo(
                            title = "共享文件夹不存在",
                            summary = "未找到指定的共享文件夹 (${smbApiEx.status})",
                            details = listOf(
                                "共享路径的第一段必须是电脑上设置的『共享名』而非磁盘盘符（例如应填 'Anime' 而不是 'D:\\Anime'）。",
                                "可先清空『相对路径』栏，直接测试连接以验证基础连通性。"
                            )
                        )
                    }
                    NtStatus.STATUS_ACCOUNT_DISABLED -> {
                        ConnectionErrorInfo(
                            title = "账户受限或已禁用",
                            summary = "Windows 账户受到策略限制或已禁用 (${smbApiEx.status})",
                            details = listOf(
                                "Windows 默认禁止空密码账户通过局域网登录，请确保 Windows 账户已设置密码。",
                                "请确认该账户未被禁用且未达到锁定阈值。"
                            )
                        )
                    }
                    else -> {
                        ConnectionErrorInfo(
                            title = "SMB 协议错误",
                            summary = "错误码: ${smbApiEx.status} (${smbApiEx.message})",
                            details = listOf(
                                "请检查共享路径与权限设置是否正确。"
                            )
                        )
                    }
                }
            }
            else -> {
                val msg = throwable.localizedMessage ?: throwable.message ?: throwable.javaClass.simpleName
                ConnectionErrorInfo(
                    title = "连接失败",
                    summary = msg,
                    details = listOf(
                        "若电脑由 Wi-Fi 切换为网线，请检查以太网网络类型是否为『专用网络』。",
                        "建议直接使用电脑有线 IPv4 地址连接，并确保手机和电脑在同一局域网。"
                    )
                )
            }
        }
    }

    private fun showErrorDialog(error: ConnectionErrorInfo) {
        val message = buildString {
            append(error.summary)
            if (error.details.isNotEmpty()) {
                append("\n\n【排查建议】\n")
                error.details.forEachIndexed { i, tip ->
                    append("${i + 1}. ").append(tip).append("\n")
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(error.title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        (application as? com.hippo.ehviewer.EhApplication)?.onUserInteraction()
    }
}
