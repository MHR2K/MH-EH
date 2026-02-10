package com.hippo.ehviewer.smb.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hippo.ehviewer.databinding.ActivitySmbToolsBinding
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
 * SMB 简易工具页：从已保存服务器选择后，测试上传/重命名/删除。
 */
class SmbToolsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySmbToolsBinding
    private val scope = CoroutineScope(Job() + Dispatchers.Main)

    private var servers: List<SmbServer> = emptyList()
    private var selected: SmbServer? = null
    private var pickedUri: Uri? = null

    companion object {
        private const val REQ_PICK_FILE = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmbToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        servers = SmbServerStore.list()
        if (servers.isEmpty()) {
            Toast.makeText(this, "请先保存至少一个 SMB 服务器", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val titles = servers.map { s ->
            val a = s.authority
            val user = if (!a.domain.isNullOrBlank()) "${a.domain}\\${a.username}" else a.username
            val portPart = if (a.port != Authority.DEFAULT_PORT) ":${a.port}" else ""
            val path = s.relativePath.replace('\\','/')
            val pathPart = if (path.isBlank()) "" else "/$path"
            val url = "smb://$user:${s.password}@${a.host}$portPart$pathPart"
            if (s.name != null) "${s.name}: $url" else url
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, titles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerServers.adapter = adapter
        binding.spinnerServers.setSelection(0)
        selected = servers.first()
        binding.spinnerServers.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selected = servers[position]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                selected = null
            }
        })

        binding.btnPickFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK_FILE)
        }

        binding.btnUpload.setOnClickListener {
            val srv = selected ?: return@setOnClickListener
            val uri = pickedUri
            if (uri == null) {
                Toast.makeText(this, "请先选择要上传的文件", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            val filename = queryDisplayName(uri) ?: "upload.bin"
            val targetBase = srv.toTarget() ?: run {
                Toast.makeText(this, "服务器未设置 relativePath（share 或 share\\subdir）", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            val target = targetBase.copy(pathInShare = joinPath(targetBase.pathInShare, filename))
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openInputStream(uri).use { input ->
                            if (input == null) throw IllegalStateException("无法打开所选文件")
                            Client.withTempPassword(target.authority, srv.password) {
                                Client.upload(target, input, overwrite = true)
                            }
                        }
                        true
                    } catch (t: Throwable) {
                        t.printStackTrace(); false
                    }
                }
                Toast.makeText(this@SmbToolsActivity, if (ok) "上传成功" else "上传失败", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRename.setOnClickListener {
            val srv = selected ?: return@setOnClickListener
            val oldName = binding.editOldName.text?.toString()?.trim().orEmpty()
            val newName = binding.editNewName.text?.toString()?.trim().orEmpty()
            if (oldName.isBlank() || newName.isBlank()) { Toast.makeText(this, "请输入旧/新文件名", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val targetBase = srv.toTarget() ?: run { Toast.makeText(this, "服务器未设置 relativePath", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val target = targetBase.copy(pathInShare = joinPath(targetBase.pathInShare, oldName))
            val newPath = joinPath(targetBase.pathInShare, newName)
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        Client.withTempPassword(target.authority, srv.password) {
                            Client.rename(target, newPath)
                        }
                        true
                    } catch (t: Throwable) { t.printStackTrace(); false }
                }
                Toast.makeText(this@SmbToolsActivity, if (ok) "重命名成功" else "重命名失败", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDelete.setOnClickListener {
            val srv = selected ?: return@setOnClickListener
            val name = binding.editDeleteName.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) { Toast.makeText(this, "请输入要删除的文件名", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val targetBase = srv.toTarget() ?: run { Toast.makeText(this, "服务器未设置 relativePath", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val target = targetBase.copy(pathInShare = joinPath(targetBase.pathInShare, name))
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        Client.withTempPassword(target.authority, srv.password) {
                            Client.delete(target)
                        }
                        true
                    } catch (t: Throwable) { t.printStackTrace(); false }
                }
                Toast.makeText(this@SmbToolsActivity, if (ok) "删除成功" else "删除失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_FILE && resultCode == Activity.RESULT_OK) {
            pickedUri = data?.data
            binding.textPicked.text = pickedUri?.toString() ?: ""
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return null
    }

    private fun joinPath(base: String, name: String): String {
        val b = base.trim('\n','\r','\t',' ').trim('/','\\')
        val n = name.trim('\n','\r','\t',' ').trim('/','\\')
        return if (b.isEmpty()) n else "$b\\$n"
    }
}
