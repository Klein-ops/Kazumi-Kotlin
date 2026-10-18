package com.kazumi.port.data.model

import com.google.gson.JsonObject

/**
 * 规则仓库条目
 * 对应原版 lib/modules/plugin/plugin_http_module.dart 的 PluginHTTPItem
 * 数据来源：规则仓库 index.json
 */
data class PluginHTTPItem(
    val name: String,
    val version: String,
    val useNativePlayer: Boolean,
    val author: String,
    val lastUpdate: Long = 0L,
    val antiCrawlerEnabled: Boolean = false
) {
    companion object {
        fun fromJson(json: JsonObject): PluginHTTPItem {
            // antiCrawlerConfig.enabled 优先，其次兼容顶层 antiCrawlerEnabled
            var antiCrawlerEnabled = false
            json.get("antiCrawlerConfig")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean
                ?.let { antiCrawlerEnabled = it }
            if (!antiCrawlerEnabled) {
                json.get("antiCrawlerEnabled")?.takeIf { !it.isJsonNull }?.asBoolean
                    ?.let { antiCrawlerEnabled = it }
            }
            return PluginHTTPItem(
                name = json.get("name")?.asString ?: "",
                version = json.get("version")?.asString ?: "",
                useNativePlayer = json.get("useNativePlayer")?.asBoolean ?: false,
                author = json.get("author")?.asString ?: "",
                lastUpdate = json.get("lastUpdate")?.asLong ?: 0L,
                antiCrawlerEnabled = antiCrawlerEnabled
            )
        }
    }
}

/**
 * 规则仓库条目相对本地的安装状态
 * 对应原版 enum PluginCatalogItemStatus { install, installed, update }
 */
enum class PluginCatalogItemStatus { INSTALL, INSTALLED, UPDATE }

/**
 * 规则更新状态（基于本地列表与仓库缓存判断）
 * 对应原版 enum PluginUpdateAvailability { unknown, notInCatalog, latest, updatable }
 */
enum class PluginUpdateAvailability { UNKNOWN, NOT_IN_CATALOG, LATEST, UPDATABLE }

/**
 * 单条规则更新结果
 * 对应原版 enum PluginUpdateResult { updated, requiresNewerClient, failed, notNewer }
 */
enum class PluginUpdateResult { UPDATED, REQUIRES_NEWER_CLIENT, FAILED, NOT_NEWER }