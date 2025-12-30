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
import com.hippo.ehviewer.databinding.ActivitySmbAddServerBinding
import com.hippo.ehviewer.smb.Authority
import com.hippo.ehviewer.smb.Client
import com.hippo.ehviewer.smb.SmbServer
import com.hippo.ehviewer.smb.SmbServerStore
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

            scope.launch {
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
                        false
                    }
                }
                if (ok) {
                    SmbServerStore.addOrReplace(server)
                    val msg = if (editingId != null) "连接成功，已更新" else "连接成功，已保存"
                    Toast.makeText(this@SmbAddServerActivity, msg, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@SmbAddServerActivity, "连接失败，请检查输入", Toast.LENGTH_SHORT).show()
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
}
