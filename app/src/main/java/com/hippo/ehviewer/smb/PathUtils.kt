package com.hippo.ehviewer.smb

/**
 * SMB 路径处理工具类
 * 
 * 提供统一的路径处理方法，避免重复代码
 */
object PathUtils {
    /**
     * 连接路径段，支持 / 和 \ 分隔符
     * @param base 基础路径（可为 null 或空）
     * @param name 要添加的名称
     * @return 连接后的路径
     */
    fun join(base: String?, name: String?): String {
        if (name.isNullOrBlank()) {
            return base?.trim('\n', '\r', '\t', ' ', '/', '\\') ?: ""
        }
        if (base.isNullOrBlank()) {
            return name.trim('\n', '\r', '\t', ' ', '/', '\\')
        }
        val cleanBase = base.trim('\n', '\r', '\t', ' ', '/', '\\')
        val cleanName = name.trim('\n', '\r', '\t', ' ', '/', '\\')
        return if (cleanBase.isEmpty()) cleanName else "$cleanBase\\$cleanName"
    }

    /**
     * 安全分割路径
     * @param path 要分割的路径
     * @return 路径段列表
     */
    fun split(path: String): List<String> {
        if (path.isBlank()) return emptyList()
        return path.trim('/', '\\')
            .split('\\', '/')
            .filter { it.isNotBlank() }
    }

    /**
     * 规范化路径：将所有分隔符统一为 \
     * @param path 原始路径
     * @return 规范化后的路径
     */
    fun normalize(path: String): String {
        if (path.isBlank()) return ""
        return path.replace('/', '\\').trim('\\')
    }

    /**
     * 获取文件名（路径最后一段）
     * @param path 路径
     * @return 文件名，不含路径分隔符
     */
    fun getFileName(path: String): String {
        if (path.isBlank()) return ""
        val normalized = normalize(path)
        val lastSep = normalized.lastIndexOf('\\')
        return if (lastSep >= 0) {
            normalized.substring(lastSep + 1)
        } else {
            normalized
        }
    }

    /**
     * 获取父路径
     * @param path 路径
     * @return 父路径，如果无父路径返回空字符串
     */
    fun getParent(path: String): String {
        if (path.isBlank()) return ""
        val normalized = normalize(path)
        val lastSep = normalized.lastIndexOf('\\')
        return if (lastSep > 0) {
            normalized.substring(0, lastSep)
        } else if (lastSep == 0) {
            "" // 根目录
        } else {
            "" // 无分隔符
        }
    }
}
