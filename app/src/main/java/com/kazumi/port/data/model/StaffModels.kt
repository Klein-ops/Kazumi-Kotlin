package com.kazumi.port.data.model

import com.google.gson.JsonObject

/**
 * 制作人员图片
 * 对应 Dart Images
 */
data class StaffImages(
    val large: String = "",
    val medium: String = "",
    val small: String = "",
    val grid: String = ""
) {
    companion object {
        fun fromJson(json: JsonObject): StaffImages = StaffImages(
            large = json.get("large")?.takeIf { !it.isJsonNull }?.asString ?: "",
            medium = json.get("medium")?.takeIf { !it.isJsonNull }?.asString ?: "",
            small = json.get("small")?.takeIf { !it.isJsonNull }?.asString ?: "",
            grid = json.get("grid")?.takeIf { !it.isJsonNull }?.asString ?: ""
        )
    }
}

/**
 * 制作人员
 * 对应 Dart Staff
 */
data class Staff(
    val id: Int = 0,
    val name: String = "",
    val nameCN: String = "",
    val images: StaffImages? = null
) {
    companion object {
        fun fromJson(json: JsonObject): Staff = Staff(
            id = json.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 0,
            name = json.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "",
            nameCN = json.get("nameCN")?.takeIf { !it.isJsonNull }?.asString ?: "",
            images = json.getAsJsonObject("images")?.let { StaffImages.fromJson(it) }
        )
    }
}

/**
 * 制作人员完整条目（人员 + 职务）
 * 对应 Dart StaffFullItem
 */
data class StaffFullItem(
    val staff: Staff = Staff(),
    val relations: List<String> = emptyList()
)

/**
 * 制作人员响应
 * 对应 Dart StaffResponse
 * 解析 GET /v0/subjects/{id}/persons（扁平 people × relation 列表，按人员 ID 合并）
 */
data class StaffResponse(
    val data: List<StaffFullItem> = emptyList()
) {
    companion object {
        private val STAFF_RELATION_PRIORITY = listOf(
            "动画制作", "原作", "总导演", "导演", "系列构成", "脚本", "人物设定",
            "总作画监督", "作画监督", "美术监督", "色彩设计", "音乐", "音响监督", "摄影监督"
        )

        private fun priorityOf(relation: String): Int {
            val index = STAFF_RELATION_PRIORITY.indexOf(relation)
            return if (index < 0) STAFF_RELATION_PRIORITY.size else index
        }

        fun fromJson(list: com.google.gson.JsonArray): StaffResponse {
            val staffById = LinkedHashMap<Int, Staff>()
            val relationsById = LinkedHashMap<Int, MutableList<String>>()
            for (item in list) {
                if (!item.isJsonObject) continue
                val json = item.asJsonObject
                try {
                    val staff = Staff.fromJson(json)
                    staffById.putIfAbsent(staff.id, staff)
                    relationsById.getOrPut(staff.id) { mutableListOf() }
                        .add(json.get("relation")?.takeIf { !it.isJsonNull }?.asString ?: "")
                } catch (_: Exception) {}
            }
            val data = staffById.entries.map { entry ->
                val relations = relationsById[entry.key]!!
                    .sortedWith(compareBy { priorityOf(it) })
                StaffFullItem(staff = entry.value, relations = relations)
            }.sortedBy { if (it.relations.isEmpty()) Int.MAX_VALUE else priorityOf(it.relations.first()) }
            return StaffResponse(data = data)
        }
    }
}