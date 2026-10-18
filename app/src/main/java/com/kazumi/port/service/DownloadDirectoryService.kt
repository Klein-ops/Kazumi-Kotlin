package com.kazumi.port.service

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.kazumi.port.data.repository.SettingsManager
import java.io.File
import java.io.IOException

/**
 * 下载目录异常
 * 对应原版 DownloadDirectoryException
 */
class DownloadDirectoryException(message: String) : Exception(message)

/**
 * 下载目录服务
 *
 * 对应原版 lib/services/download/download_directory_service.dart +
 * Android 侧 DownloadDirectoryHandler.kt（SAF 树授权 + 本地卷路径解析）。
 *
 * Android 上自定义下载目录采用「SAF 授权 + 本地文件系统路径」方案：
 * 1. 用户先授予「所有文件访问」权限（Android 11+ 走 MANAGE_EXTERNAL_STORAGE，
 *    旧版本走 WRITE_EXTERNAL_STORAGE 运行时权限）；
 * 2. 通过 ACTION_OPEN_DOCUMENT_TREE 选择目录，将 content:// 树 URI 解析为
 *    本地卷上的真实路径并持久化；
 * 3. 下载/播放/删除一律使用普通文件系统路径（与原版行为一致）。
 */
class DownloadDirectoryService(
    private val context: Context,
    private val settings: SettingsManager
) {
    /** 当前自定义目录（为空表示使用默认目录） */
    suspend fun customDirectory(): String =
        settings.getString(SettingsManager.Download.DIRECTORY, "").trim()

    /** 下载根目录：优先自定义目录，否则默认目录 */
    suspend fun getDownloadDirectory(): String {
        val directory = customDirectory()
        return if (directory.isEmpty()) getDefaultDownloadDirectory() else directory
    }

    /** 默认下载目录（应用私有外部目录下的 downloads） */
    suspend fun getDefaultDownloadDirectory(): String {
        val base = context.getExternalFilesDir(null)
            ?: context.filesDir
        return File(base, "downloads").absolutePath
    }

    /**
     * 检查目录是否可写；不可写抛 [DownloadDirectoryException]。
     * 对应原版 ensureDirectoryWritable()。
     */
    suspend fun ensureDirectoryWritable(directoryPath: String) {
        val dir = File(directoryPath)
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            throw DownloadDirectoryException("无法创建下载目录: $directoryPath")
        }
        if (!dir.canWrite()) {
            throw DownloadDirectoryException("下载目录不可写: $directoryPath")
        }
    }

    /**
     * 可用空间字节数；无法确定时返回 -1。
     * 对应原版 MainActivity.getAvailableStorage() 的 StatFs 实现。
     */
    fun getAvailableStorage(path: String): Long {
        return try {
            val stat = StatFs(path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * 重置自定义目录（清空设置）。
     * 对应原版 resetDirectory()。
     */
    suspend fun resetDirectory() {
        settings.setString(SettingsManager.Download.DIRECTORY_BOOKMARK, "")
        settings.setString(SettingsManager.Download.DIRECTORY, "")
    }

    /**
     * 保存自定义目录。仅在目录可写后持久化。
     * Android 上无持久书签概念，DIRECTORY_BOOKMARK 保留为空串。
     */
    suspend fun setDirectory(directoryPath: String) {
        ensureDirectoryWritable(directoryPath)
        settings.setString(SettingsManager.Download.DIRECTORY, directoryPath)
        settings.setString(SettingsManager.Download.DIRECTORY_BOOKMARK, "")
    }

    /**
     * 是否已授予全盘文件访问权限。
     * 对应原版 DownloadDirectoryHandler.hasStorageAccess()。
     */
    fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            @Suppress("DEPRECATION")
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 将 SAF 树 URI（content://com.android.externalstorage.documents/tree/...）
     * 解析为本地卷上的真实路径；无法解析（云盘/虚拟卷）返回 null。
     * 对应原版 DownloadDirectoryHandler.resolveDirectory()。
     */
    fun resolveTreeUri(uri: android.net.Uri): String? {
        if (uri.scheme != "content" ||
            uri.authority != "com.android.externalstorage.documents" ||
            !android.provider.DocumentsContract.isTreeUri(uri)
        ) return null

        val documentId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        val separator = documentId.indexOf(':')
        if (separator < 0) return null

        val volumeId = documentId.substring(0, separator)
        val root = storageRoots().firstOrNull {
            it.first.equals(volumeId, ignoreCase = true)
        }?.second ?: return null

        val directory = File(root, documentId.substring(separator + 1))
        return if (contains(root, directory)) directory.absolutePath else null
    }

    /** 本地存储卷列表：(volumeId, root) */
    @Suppress("DEPRECATION")
    private fun storageRoots(): List<Pair<String, File>> {
        val roots = mutableListOf("primary" to Environment.getExternalStorageDirectory())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            manager.storageVolumes.forEach { volume ->
                val id = volume.uuid
                val directory = volume.directory
                if (id != null && directory != null) {
                    roots.add(id to directory)
                }
            }
        } else {
            // getExternalFilesDirs 暴露已挂载的移动卷（API < 30）
            context.getExternalFilesDirs(null).filterNotNull().forEach { directory ->
                val rootPath = directory.absolutePath.substringBefore("/Android/data/")
                val root = File(rootPath)
                if (rootPath != directory.absolutePath && root.name != "0") {
                    roots.add(root.name to root)
                }
            }
        }
        return roots
    }

    private fun contains(root: File, directory: File): Boolean {
        val rootPath = try {
            root.canonicalPath
        } catch (e: IOException) {
            root.absolutePath
        }
        val directoryPath = try {
            directory.canonicalPath
        } catch (e: IOException) {
            directory.absolutePath
        }
        return directoryPath == rootPath || directoryPath.startsWith(rootPath + File.separator)
    }
}