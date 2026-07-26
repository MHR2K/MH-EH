package com.hippo.ehviewer.smb

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * CBZ 下载状态跟踪器
 * 用于在 UI 层观察 SMB CBZ 下载进度
 */
object CbzDownloadTracker {

    data class DownloadState(
        val bytesRead: Long = 0,
        val totalBytes: Long = -1,
        val speedBps: Long = 0,
        val isActive: Boolean = false
    ) {
        val percent: Int
            get() = if (totalBytes > 0) (bytesRead * 100 / totalBytes).toInt().coerceIn(0, 100) else 0

        val speedText: String
            get() {
                if (speedBps <= 0) return ""
                return when {
                    speedBps >= 1024 * 1024 -> "%.1f MB/s".format(speedBps / (1024.0 * 1024.0))
                    speedBps >= 1024 -> "%.0f KB/s".format(speedBps / 1024.0)
                    else -> "$speedBps B/s"
                }
            }

        val sizeText: String
            get() {
                val downloaded = formatSize(bytesRead)
                if (totalBytes <= 0) return downloaded
                return "$downloaded / ${formatSize(totalBytes)}"
            }

        private fun formatSize(bytes: Long): String = when {
            bytes >= 1024 * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private val _state = MutableLiveData(DownloadState())
    val state: LiveData<DownloadState> = _state

    fun startDownload(totalBytes: Long) {
        _state.postValue(DownloadState(isActive = true, totalBytes = totalBytes))
    }

    fun updateProgress(bytesRead: Long, totalBytes: Long, speedBps: Long) {
        _state.postValue(DownloadState(
            bytesRead = bytesRead,
            totalBytes = totalBytes,
            speedBps = speedBps,
            isActive = true
        ))
    }

    fun finishDownload() {
        _state.postValue(DownloadState(isActive = false))
    }
}
