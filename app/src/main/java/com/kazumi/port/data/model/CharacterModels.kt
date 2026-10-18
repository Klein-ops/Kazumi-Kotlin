package com.kazumi.port.data.model

import com.google.gson.JsonObject

/**
 * 角色头像
 * 对应 Dart CharacterAvator
 */
data class CharacterAvatar(
    val small: String = "",
    val medium: String = "",
    val grid: String = "",
    val large: String = ""
) {
    companion object {
        fun fromJson(json: JsonObject): CharacterAvatar = CharacterAvatar(
            small = json.get("small")?.takeIf { !it.isJsonNull }?.asString ?: "",
            medium = json.get("medium")?.takeIf { !it.isJsonNull }?.asString ?: "",
            grid = json.get("grid")?.takeIf { !it.isJsonNull }?.asString ?: "",
            large = json.get("large")?.takeIf { !it.isJsonNull }?.asString ?: ""
        )
    }
}

/**
 * 声优头像
 * 对应 Dart ActorAvator
 */
data class ActorAvatar(
    val small: String = "",
    val medium: String = "",
    val grid: String = "",
    val large: String = ""
) {
    companion object {
        fun fromJson(json: JsonObject): ActorAvatar = ActorAvatar(
            small = json.get("small")?.takeIf { !it.isJsonNull }?.asString ?: "",
            medium = json.get("medium")?.takeIf { !it.isJsonNull }?.asString ?: "",
            grid = json.get("grid")?.takeIf { !it.isJsonNull }?.asString ?: "",
            large = json.get("large")?.takeIf { !it.isJsonNull }?.asString ?: ""
        )
    }
}

/**
 * 声优条目
 * 对应 Dart ActorItem
 */
data class ActorItem(
    val id: Int = 0,
    val type: Int = 0,
    val name: String = "",
    val avatar: ActorAvatar = ActorAvatar()
) {
    companion object {
        fun fromJson(json: JsonObject): ActorItem = ActorItem(
            id = json.get("id")?.asInt ?: 0,
            type = json.get("type")?.asInt ?: 0,
            name = json.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "",
            avatar = json.getAsJsonObject("images")?.let { ActorAvatar.fromJson(it) } ?: ActorAvatar()
        )
    }
}

/**
 * 角色额外信息（原名/简介）
 * 对应 Dart CharacterExtraInfo
 */
data class CharacterExtraInfo(
    val nameCn: String = "",
    val summary: String = ""
) {
    companion object {
        fun fromJson(json: JsonObject): CharacterExtraInfo {
            var nameCn = ""
            val infobox = json.getAsJsonArray("infobox")
            if (infobox != null && infobox.size() > 0) {
                val first = infobox[0]
                if (first.isJsonObject) {
                    val key = first.asJsonObject.get("key")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    if (key == "简体中文名") {
                        nameCn = first.asJsonObject.get("value")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    }
                }
            }
            return CharacterExtraInfo(
                nameCn = nameCn,
                summary = json.get("summary")?.takeIf { !it.isJsonNull }?.asString ?: ""
            )
        }
    }
}

/**
 * 角色条目
 * 对应 Dart CharacterItem
 */
data class CharacterItem(
    val id: Int = 0,
    val type: Int = 0,
    val name: String = "",
    val relation: String = "未知",
    val avatar: CharacterAvatar = CharacterAvatar(),
    val actorList: List<ActorItem> = emptyList(),
    val info: CharacterExtraInfo = CharacterExtraInfo()
) {
    companion object {
        fun fromJson(json: JsonObject): CharacterItem {
            val actors = mutableListOf<ActorItem>()
            val rawActors = json.getAsJsonArray("actors")
            if (rawActors != null) {
                for (e in rawActors) {
                    if (e.isJsonObject) {
                        try {
                            actors.add(ActorItem.fromJson(e.asJsonObject))
                        } catch (_: Exception) {}
                    }
                }
            }
            return CharacterItem(
                id = json.get("id")?.asInt ?: 0,
                type = json.get("type")?.asInt ?: 0,
                name = json.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "",
                relation = json.get("relation")?.takeIf { !it.isJsonNull }?.asString ?: "未知",
                avatar = json.getAsJsonObject("images")?.let { CharacterAvatar.fromJson(it) } ?: CharacterAvatar(),
                actorList = actors,
                info = CharacterExtraInfo()
            )
        }
    }
}

/**
 * 角色信息字段
 * 对应 Dart CharacterInfoField
 */
data class CharacterInfoField(
    val key: String = "",
    val value: String = ""
)

/**
 * 角色完整信息（角色详情弹窗数据）
 * 对应 Dart CharacterFullItem
 */
data class CharacterFullItem(
    val id: Int = 0,
    val infobox: List<CharacterInfoField> = emptyList(),
    val summary: String = "",
    val image: String = "",
    val name: String = "",
    val nameCn: String = "",
    val unstructuredInfo: String = ""
) {
    companion object {
        fun fromJson(json: JsonObject): CharacterFullItem {
            var fields = parseInfobox(json.get("infobox"))
            var unstructuredInfo = ""
            if (fields.isEmpty()) {
                val parsed = parseInfo(json.get("info")?.takeIf { !it.isJsonNull }?.asString ?: "")
                fields = parsed.first
                unstructuredInfo = parsed.second
            }
            var nameCn = json.get("nameCN")?.takeIf { !it.isJsonNull }?.asString ?: ""
            if (nameCn.isEmpty()) {
                nameCn = json.get("name_cn")?.takeIf { !it.isJsonNull }?.asString ?: ""
            }
            if (nameCn.isEmpty()) {
                nameCn = fields.firstOrNull { it.key == "简体中文名" }?.value ?: ""
            }
            val images = json.getAsJsonObject("images")
            val image = if (images != null) {
                listOf("large", "medium", "small")
                    .mapNotNull { size -> images.get(size)?.takeIf { !it.isJsonNull }?.asString?.trim() }
                    .firstOrNull { it.isNotEmpty() } ?: ""
            } else ""
            return CharacterFullItem(
                id = json.get("id")?.asInt ?: 0,
                infobox = fields,
                summary = json.get("summary")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: "",
                image = image,
                name = json.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: "",
                nameCn = nameCn,
                unstructuredInfo = unstructuredInfo
            )
        }

        private fun text(value: Any?): String =
            if (value is String || value is Number) value.toString().trim() else ""

        // /p1 使用 values；/v0 使用 value（含带标签别名）
        private fun parseInfobox(raw: Any?): List<CharacterInfoField> {
            if (raw !is com.google.gson.JsonElement || !raw.isJsonArray) return emptyList()
            val fields = mutableListOf<CharacterInfoField>()
            for (entry in raw.asJsonArray) {
                if (!entry.isJsonObject) continue
                val obj = entry.asJsonObject
                val key = obj.get("key")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                if (key.isEmpty()) continue
                val rawValues = obj.get("values") ?: obj.get("value")
                val values = if (rawValues != null && rawValues.isJsonArray) {
                    rawValues.asJsonArray.map { it }
                } else if (rawValues != null) {
                    listOf(rawValues)
                } else emptyList()
                val parts = mutableListOf<String>()
                for (value in values) {
                    val vObj = if (value.isJsonObject) value.asJsonObject else null
                    val vText = if (vObj != null) {
                        vObj.get("v")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    } else if (value.isJsonPrimitive) value.asString else ""
                    if (vText.trim().isEmpty()) continue
                    val label = if (vObj != null) {
                        vObj.get("k")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    } else ""
                    parts.add(if (label.isEmpty()) vText.trim() else "$label：${vText.trim()}")
                }
                if (parts.isEmpty()) continue
                fields.add(CharacterInfoField(key = key, value = parts.joinToString(" / ")))
            }
            return fields
        }

        // 镜像站把 infobox 替换为纯文本；无字段键的行保留为简介
        private fun parseInfo(info: String): Pair<List<CharacterInfoField>, String> {
            val fields = mutableListOf<CharacterInfoField>()
            val remaining = mutableListOf<String>()
            for (rawLine in info.split(Regex("\\r?\\n"))) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val separator = Regex("[:：]").find(line)?.range?.first ?: -1
                if (separator < 1) {
                    remaining.add(line)
                    continue
                }
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    fields.add(CharacterInfoField(key = key, value = value))
                }
            }
            return fields to remaining.joinToString("\n")
        }
    }
}