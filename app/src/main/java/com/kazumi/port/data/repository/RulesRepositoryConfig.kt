package com.kazumi.port.data.repository

import com.kazumi.port.data.model.ApiEndpoints
import java.net.URI
import java.net.URISyntaxException

/**
 * 规则仓库地址配置
 * 对应原版 lib/request/config/rules_repository_config.dart
 * （feat(plugin): support custom rule repositories, commit 85c5106f）
 *
 * 约定：空字符串 = 官方仓库；非空必须是可通过 HTTP(S) 访问的目录，
 * 或以 index.json 结尾的文件目录（地址仅支持目录，不支持单文件）。
 */
object RulesRepositoryConfig {

    /**
     * 规范化存储值：trim 后为空 → 空串（官方仓库）；
     * 规范化后与官方仓库地址相同 → 空串（回到官方仓库）；
     * 非法地址抛出 [IllegalArgumentException]（原版 FormatException 语义），
     * 由调用方捕获并展示错误信息。
     */
    fun normalizeForStorage(input: String): String {
        if (input.trim().isEmpty()) return ""
        val normalized = baseUri(input).toString()
        return if (normalized == ApiEndpoints.PLUGIN_SHOP) "" else normalized
    }

    /** 仓库目录 + index.json（目录地址，用于获取条目列表） */
    fun catalogUri(repository: String): String =
        baseUri(repository).resolve("index.json").toString()

    /** 仓库目录 + 规则名.json（用于获取单条规则） */
    fun ruleUri(repository: String, ruleName: String): String =
        baseUri(repository).resolve("$ruleName.json").toString()

    /**
     * 解析并规范化仓库基地址。空输入回退官方仓库。
     * 校验规则（对齐原版 _baseUri）：
     * - 必须是 http/https、带主机、不含用户名密码、不含 query/fragment
     * - 拒绝 github.com 网页地址（应使用 raw 地址）
     * - 地址要么是目录，要么以 index.json 结尾
     */
    private fun baseUri(repository: String): URI {
        val value = repository.trim()
        if (value.isEmpty()) return URI(ApiEndpoints.PLUGIN_SHOP)

        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("规则仓库地址必须是有效的 HTTP 或 HTTPS URL")
        }
        val scheme = try {
            uri.scheme
        } catch (_: Exception) {
            null
        }
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("规则仓库地址必须是有效的 HTTP 或 HTTPS URL")
        }
        if (uri.host.isNullOrEmpty()) {
            throw IllegalArgumentException("规则仓库地址必须是有效的 HTTP 或 HTTPS URL")
        }
        if (!uri.userInfo.isNullOrEmpty()) {
            throw IllegalArgumentException("规则仓库地址不能包含用户名或密码")
        }
        if (uri.query != null || uri.fragment != null) {
            throw IllegalArgumentException("规则仓库地址不能包含查询参数或片段")
        }
        if (uri.host.equals("github.com", ignoreCase = true)) {
            throw IllegalArgumentException("请使用 raw 文件地址，不要使用 GitHub 仓库网页地址")
        }

        // 去掉末尾 '/'，取最后一个段判断是文件还是目录
        var trimmed = uri.path ?: ""
        while (trimmed.endsWith("/")) trimmed = trimmed.dropLast(1)
        val lastSegment = trimmed.substringAfterLast('/')

        if (lastSegment.equals("index.json", ignoreCase = true)) {
            trimmed = trimmed.removeSuffix(lastSegment).trimEnd('/')
        } else if (lastSegment.contains('.') && !(uri.path ?: "").endsWith("/")) {
            // 形如 .../foo.json 或带点文件但又不是 index.json → 拒绝
            // （保留代理前缀里的空段，如 gh-proxy 的 /https://...）
            throw IllegalArgumentException("规则仓库地址必须是目录或以 index.json 结尾")
        }

        // 重新拼装：scheme://authority/目录/（末尾补斜杠，保持目录语义）
        val authority = if (uri.rawAuthority.isNullOrEmpty()) uri.host else uri.rawAuthority
        return URI("$scheme://$authority$trimmed/")
    }
}