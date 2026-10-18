package com.kazumi.port.data.repository

import com.google.gson.JsonParser
import com.kazumi.port.data.model.ApiEndpoints
import com.kazumi.port.data.model.Plugin
import com.kazumi.port.data.model.PluginHTTPItem
import com.kazumi.port.network.RulesRepoClient
import com.kazumi.port.util.FileLogger

/**
 * 规则仓库 API
 * 对应原版 lib/request/apis/plugin_catalog_api.dart
 * 数据来源：<PLUGIN_SHOP>index.json（条目列表）+ <PLUGIN_SHOP><name>.json（单个规则）
 */
object PluginCatalogApi {

    /**
     * 拉取规则仓库条目列表
     * @param mirror 是否优先使用镜像仓库（对应原版 enableGitProxy）。
     *   mirror 只影响尝试顺序：开启则先镜像后直连，关闭则先直连后镜像；
     *   两个源都会依次尝试，任一成功即返回，避免"开镜像只走镜像、镜像不通就全挂"。
     */
    fun getPluginList(mirror: Boolean): List<PluginHTTPItem> {
        val bases = if (mirror) {
            listOf(ApiEndpoints.PLUGIN_SHOP_MIRROR, ApiEndpoints.PLUGIN_SHOP)
        } else {
            listOf(ApiEndpoints.PLUGIN_SHOP, ApiEndpoints.PLUGIN_SHOP_MIRROR)
        }
        var lastError: Exception? = null
        for (base in bases) {
            try {
                val raw = RulesRepoClient.getText(base + "index.json")
                return parsePluginList(raw)
            } catch (e: Exception) {
                lastError = e
                // 直连失败时自动回退镜像仓库
            }
        }
        throw lastError ?: IllegalArgumentException("规则仓库不可用")
    }

    /** 拉取并解析单个规则（与 getPluginList 相同，双源依次尝试，mirror 只影响顺序） */
    fun getPlugin(name: String, mirror: Boolean): Plugin {
        val bases = if (mirror) {
            listOf(ApiEndpoints.PLUGIN_SHOP_MIRROR, ApiEndpoints.PLUGIN_SHOP)
        } else {
            listOf(ApiEndpoints.PLUGIN_SHOP, ApiEndpoints.PLUGIN_SHOP_MIRROR)
        }
        var lastError: Exception? = null
        for (base in bases) {
            try {
                FileLogger.i("RuleRepo", "getPlugin: 尝试 $base$name.json (mirror=$mirror)")
                val raw = RulesRepoClient.getText(base + "$name.json")
                FileLogger.d("RuleRepo", "getPlugin: $base 返回 len=${raw.length} head=${raw.take(120)}")
                val json = JsonParser.parseString(raw)
                if (!json.isJsonObject) {
                    throw IllegalArgumentException("规则 $name 必须是 JSON 对象")
                }
                val plugin = Plugin.fromJson(json.asJsonObject)
                FileLogger.i("RuleRepo", "getPlugin OK: name=${plugin.name} version=${plugin.version} via $base")
                return plugin
            } catch (e: Exception) {
                lastError = e
                FileLogger.w("RuleRepo", "getPlugin: $base 失败 ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        throw lastError ?: IllegalArgumentException("规则 $name 不存在")
    }

    /** 解析 index.json 文本为条目列表，跳过无效项 */
    fun parsePluginList(raw: String): List<PluginHTTPItem> {
        val json = JsonParser.parseString(raw)
        if (!json.isJsonArray) {
            throw IllegalArgumentException("规则仓库根节点必须是 JSON 数组")
        }
        val items = mutableListOf<PluginHTTPItem>()
        val array = json.asJsonArray
        for (i in 0 until array.size()) {
            try {
                val element = array[i]
                if (element.isJsonObject) {
                    items.add(PluginHTTPItem.fromJson(element.asJsonObject))
                }
            } catch (_: Exception) {
                // 跳过无效项，与原版 skippedItems 语义一致
            }
        }
        if (array.size() > 0 && items.isEmpty()) {
            throw IllegalArgumentException("规则仓库中没有有效条目")
        }
        return items
    }
}