package com.kazumi.port.data.model

import com.google.gson.JsonObject

/**
 * 关联条目
 * 对应 Dart BangumiRelation
 */
data class BangumiRelation(
    val relation: String = "关联",
    val bangumiItem: BangumiItem = BangumiItem()
) {
    companion object {
        fun fromJson(json: JsonObject): BangumiRelation {
            val id = parseId(json.get("id")) ?: throw IllegalArgumentException("Bangumi relation is missing a valid id")
            val type = parseId(json.get("type")) ?: 0
            val name = (json.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "").trim()
            val nameCn = (json.get("name_cn")?.takeIf { !it.isJsonNull }?.asString ?: "").trim()
            val imagesRaw = json.getAsJsonObject("images")
            val images = mutableMapOf<String, String>()
            if (imagesRaw != null) {
                for ((k, v) in imagesRaw.entrySet()) {
                    images[k] = if (v.isJsonNull) "" else v.asString
                }
            }
            val relation = (json.get("relation")?.takeIf { !it.isJsonNull }?.asString ?: "").trim()
            return BangumiRelation(
                relation = if (relation.isEmpty()) "关联" else relation,
                bangumiItem = BangumiItem(
                    id = id,
                    type = type,
                    name = name,
                    nameCn = if (nameCn.isEmpty()) name else nameCn,
                    summary = "",
                    airDate = "",
                    airWeekday = 0,
                    rank = 0,
                    images = images,
                    tags = emptyList(),
                    alias = emptyList(),
                    ratingScore = 0.0,
                    votes = 0,
                    votesCount = emptyList(),
                    info = ""
                )
            )
        }

        private fun parseId(value: com.google.gson.JsonElement?): Int? {
            if (value == null || value.isJsonNull) return null
            return try {
                if (value.isJsonPrimitive) value.asInt else null
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * 筛选关联番剧（仅保留动画 type==2，排除自身，去重）
 * 对应 Dart selectRelatedAnime()
 */
fun selectRelatedAnime(
    relations: List<BangumiRelation>,
    currentSubjectId: Int
): List<BangumiRelation> {
    val seenIds = HashSet<Int>()
    return relations.filter { relation ->
        val item = relation.bangumiItem
        item.type == 2 && item.id != currentSubjectId && seenIds.add(item.id)
    }
}

private const val BANGUMI_PREQUEL_RELATION = "前传"
private const val BANGUMI_SEQUEL_RELATION = "续集"

private val BANGUMI_MAINLINE_RELATIONS = setOf(
    BANGUMI_PREQUEL_RELATION,
    BANGUMI_SEQUEL_RELATION
)

private data class RelationTraversalNode(
    val subjectId: Int,
    val direction: String
)

typealias BangumiRelationFetcher = suspend (Int) -> List<BangumiRelation>

/**
 * 解析关联番剧主链（前传/续集 BFS 遍历）
 * 对应 Dart resolveRelatedAnimeChain()
 * 顺序：前传链（倒序）→ 续集链（正序）→ 其他直接关联
 */
suspend fun resolveRelatedAnimeChain(
    currentSubjectId: Int,
    fetchRelations: BangumiRelationFetcher,
    maxDepth: Int = 12,
    maxFetchCount: Int = 20
): List<BangumiRelation> {
    require(maxDepth >= 1) { "maxDepth must be at least 1" }
    require(maxFetchCount >= 1) { "maxFetchCount must be at least 1" }

    var fetchCount = 1
    val rootRelations = fetchRelations(currentSubjectId)
    val directRelations = selectRelatedAnime(rootRelations, currentSubjectId)
    val relationById = HashMap<Int, BangumiRelation>()
    for (relation in directRelations) {
        relationById[relation.bangumiItem.id] = relation
    }
    val layersByDirection = linkedMapOf(
        BANGUMI_PREQUEL_RELATION to mutableListOf<List<BangumiRelation>>(),
        BANGUMI_SEQUEL_RELATION to mutableListOf<List<BangumiRelation>>()
    )
    val scheduledIds = HashSet<Int>()
    scheduledIds.add(currentSubjectId)
    var depth = 1
    var frontier = mutableListOf<RelationTraversalNode>()
    val directLayers = linkedMapOf(
        BANGUMI_PREQUEL_RELATION to mutableListOf<BangumiRelation>(),
        BANGUMI_SEQUEL_RELATION to mutableListOf<BangumiRelation>()
    )
    // 已分配主线方向的条目 id（避免重复加入层）
    val assignedDirectionIds = HashSet<Int>()
    for (relation in rootRelations) {
        val item = relation.bangumiItem
        val direction = relation.relation
        if (!BANGUMI_MAINLINE_RELATIONS.contains(direction) ||
            item.type != 2 ||
            item.id == currentSubjectId
        ) {
            continue
        }
        relationById.getOrPut(item.id) {
            BangumiRelation(relation = direction, bangumiItem = item)
        }
        if (assignedDirectionIds.add(item.id)) {
            directLayers[direction]!!.add(relationById[item.id]!!)
        }
        if (scheduledIds.add(item.id)) {
            frontier.add(RelationTraversalNode(item.id, direction))
        }
    }
    for (direction in BANGUMI_MAINLINE_RELATIONS) {
        val layer = directLayers[direction]!!
        if (layer.isNotEmpty()) layersByDirection[direction]!!.add(layer)
    }

    while (frontier.isNotEmpty() && depth < maxDepth && fetchCount < maxFetchCount) {
        val nextFrontier = mutableListOf<RelationTraversalNode>()
        val nextLayers = linkedMapOf(
            BANGUMI_PREQUEL_RELATION to mutableListOf<BangumiRelation>(),
            BANGUMI_SEQUEL_RELATION to mutableListOf<BangumiRelation>()
        )
        val nodesToFetch = frontier.take(maxFetchCount - fetchCount).toList()
        // 并发拉取每个节点的关联（失败隔离）
        val relationLists = nodesToFetch.map { node ->
            runCatching { fetchRelations(node.subjectId) }.getOrDefault(emptyList())
        }
        fetchCount += nodesToFetch.size
        for (index in nodesToFetch.indices) {
            val node = nodesToFetch[index]
            for (relation in relationLists[index]) {
                val item = relation.bangumiItem
                if (relation.relation != node.direction ||
                    item.type != 2 ||
                    item.id == currentSubjectId
                ) {
                    continue
                }
                relationById.getOrPut(item.id) {
                    BangumiRelation(relation = node.direction, bangumiItem = item)
                }
                if (!nextLayers[node.direction]!!.any { it.bangumiItem.id == item.id }) {
                    nextLayers[node.direction]!!.add(relationById[item.id]!!)
                }
                if (scheduledIds.add(item.id)) {
                    nextFrontier.add(RelationTraversalNode(item.id, node.direction))
                }
            }
        }
        for (direction in BANGUMI_MAINLINE_RELATIONS) {
            val layer = nextLayers[direction]!!
            if (layer.isNotEmpty()) layersByDirection[direction]!!.add(layer)
        }
        frontier = nextFrontier
        depth++
    }

    val result = mutableListOf<BangumiRelation>()
    for (layer in layersByDirection[BANGUMI_PREQUEL_RELATION]!!.asReversed()) {
        result.addAll(layer)
    }
    for (layer in layersByDirection[BANGUMI_SEQUEL_RELATION]!!) {
        result.addAll(layer)
    }
    // 直接关联但不在主线中的（如番外/其他）
    val mainlineIds = HashSet<Int>()
    layersByDirection.values.forEach { layerList ->
        layerList.forEach { layer ->
            layer.forEach { mainlineIds.add(it.bangumiItem.id) }
        }
    }
    result.addAll(directRelations.filter { !mainlineIds.contains(it.bangumiItem.id) })
    return result
}