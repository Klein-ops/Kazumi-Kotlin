package com.kazumi.port.network

import okhttp3.Request
import java.io.IOException

/**
 * 规则仓库 HTTP 客户端
 * 对应原版 lib/request/clients/rules_repo_client.dart
 */
object RulesRepoClient {

    /** GET 文本内容（规则仓库 index.json / 单个规则 JSON） */
    fun getText(url: String): String {
        // UA 由 HttpClientFactory 全局拦截器注入随机浏览器 UA（对齐原版 getRandomUA）
        val request = Request.Builder()
            .url(url)
            .build()
        HttpClientFactory.getClient().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("规则仓库请求失败: HTTP ${resp.code}")
            }
            return resp.body?.string() ?: ""
        }
    }
}