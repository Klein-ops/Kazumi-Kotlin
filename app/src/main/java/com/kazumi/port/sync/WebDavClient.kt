package com.kazumi.port.sync

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 轻量 WebDAV 客户端（OkHttp 实现）
 *
 * 对应原版 webdav_client 包的核心操作：
 * - MKCOL 建目录
 * - PUT 上传
 * - GET 下载
 * - DELETE 删除
 * - PROPFIND 列目录
 * - MOVE 重命名（含覆盖）
 */
class WebDavClient(
    baseUrl: String,
    username: String,
    password: String
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val authHeader = Credentials.basic(username, password)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 远程条目（PROPFIND 结果） */
    data class RemoteEntry(
        val name: String,
        val isDir: Boolean,
        val size: Long = 0
    )

    /** 连接测试（OPTIONS 或 PROPFIND 根目录） */
    fun ping(): Boolean {
        return try {
            val request = Request.Builder()
                .url(baseUrl)
                .header("Authorization", authHeader)
                .method("OPTIONS", null)
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /** 建目录（存在则忽略） */
    fun mkdir(path: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(fullUrl(path))
                .header("Authorization", authHeader)
                .method("MKCOL", null)
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful || it.code == 405 }
        } catch (_: Exception) {
            false
        }
    }

    /** 列出目录内容 */
    fun readDir(path: String): List<RemoteEntry> {
        val body = """
            <?xml version="1.0"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname/>
                <d:resourcetype/>
                <d:getcontentlength/>
              </d:prop>
            </d:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(fullUrl(path))
            .header("Authorization", authHeader)
            .header("Depth", "1")
            .method("PROPFIND", body)
            .build()

        return try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                parsePropfind(resp.body?.string().orEmpty(), path)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parsePropfind(xml: String, basePath: String): List<RemoteEntry> {
        val entries = mutableListOf<RemoteEntry>()
        // 用正则从 XML 中提取 response 块（简化解析）
        val responseRegex = Regex("<d:response>.*?</d:response>", RegexOption.DOT_MATCHES_ALL)
        for (match in responseRegex.findAll(xml)) {
            val block = match.value
            val href = Regex("<d:href>(.*?)</d:href>", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)?.trim() ?: continue
            val displayName = Regex("<d:displayname>(.*?)</d:displayname>", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)?.trim() ?: href.substringAfterLast('/')
            val isDir = block.contains("<d:collection")
            val size = Regex("<d:getcontentlength>(.*?)</d:getcontentlength>")
                .find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            entries.add(RemoteEntry(displayName, isDir, size))
        }
        // 过滤掉 basePath 自身
        return entries.filter { it.name.isNotEmpty() && it.name != basePath.substringAfterLast('/') }
    }

    /** 上传文件到远程路径 */
    fun upload(sourceFile: File, remotePath: String): Boolean {
        return try {
            val body = sourceFile.readBytes().toRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder()
                .url(fullUrl(remotePath))
                .header("Authorization", authHeader)
                .put(body)
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /** 上传字节到远程路径 */
    fun uploadBytes(bytes: ByteArray, remotePath: String): Boolean {
        return try {
            val body = bytes.toRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder()
                .url(fullUrl(remotePath))
                .header("Authorization", authHeader)
                .put(body)
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /** 下载远程文件到本地 */
    fun download(remotePath: String, localFile: File): Boolean {
        return try {
            val request = Request.Builder()
                .url(fullUrl(remotePath))
                .header("Authorization", authHeader)
                .get()
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return false
                localFile.parentFile?.mkdirs()
                localFile.writeBytes(resp.body?.bytes() ?: ByteArray(0))
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 下载远程文件内容为字符串 */
    fun downloadText(remotePath: String): String? {
        return try {
            val request = Request.Builder()
                .url(fullUrl(remotePath))
                .header("Authorization", authHeader)
                .get()
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 删除远程文件/目录 */
    fun delete(remotePath: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(fullUrl(remotePath))
                .header("Authorization", authHeader)
                .delete()
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful || it.code == 404 }
        } catch (_: Exception) {
            false
        }
    }

    /** 重命名/移动（overwrite=true 覆盖目标） */
    fun rename(sourcePath: String, targetPath: String, overwrite: Boolean = false): Boolean {
        return try {
            val request = Request.Builder()
                .url(fullUrl(sourcePath))
                .header("Authorization", authHeader)
                .header("Destination", fullUrl(targetPath))
                .header("Overwrite", if (overwrite) "T" else "F")
                .method("MOVE", null)
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /** 远程路径是否存在 */
    fun exists(path: String): Boolean {
        return readDir(path.substringBeforeLast('/', "/")).any { it.name == path.substringAfterLast('/') }
    }

    private fun fullUrl(path: String): String {
        val p = path.trimStart('/')
        return if (baseUrl.isEmpty()) "/$p" else "$baseUrl/$p"
    }
}