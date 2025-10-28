package com.hippo.ehviewer.smb.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmbAddServerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 实时预览完整 SMB 路径（包含密码）
        fun updatePreview() {
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
            val server = SmbServer(SmbServer.newId(), name, authority, password, path)

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
                    Toast.makeText(this@SmbAddServerActivity, "连接成功，已保存", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@SmbAddServerActivity, "连接失败，请检查输入", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
