package com.kazumi.port.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.kazumi.port.data.db.HistoryDao
import com.kazumi.port.data.db.HistoryEntity
import com.kazumi.port.data.model.BangumiItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * 历史记录
 * 对应 Dart History + Progress
 */
data class Progress(
    val episode: Int,
    val road: Int,
    val progressMs: Long,
    val updatedAtMs: Long = 0
)

/**
 * 历史记录领域模型
 * 对应 Dart History
 */
data class History(
    val key: String,
    val bangumiItem: BangumiItem,
    val lastWatchEpisode: Int,
    val lastWatchEpisodeName: String,
    val adapterName: String,
    val lastWatchTime: Long,
    val lastSrc: String,
    val entryKind: String,
    val episodePageUrl: String,
    val progresses: Map<Int, Progress> = emptyMap()
) {
    companion object {
        const val KIND_ONLINE = "online"
        const val KIND_OFFLINE = "offline"

        fun normalizeKind(value: String): String =
            if (value == KIND_OFFLINE) KIND_OFFLINE else KIND_ONLINE

        fun scopedKey(adapterName: String, bangumiId: Int, entryKind: String): String =
            "${adapterName}${bangumiId}::${normalizeKind(entryKind)}"
    }
}

/**
 * 历史仓库
 */
class HistoryRepository(
    private val dao: HistoryDao
) {
    private val gson = Gson()

    fun observeAll(): Flow<List<History>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getAll(): List<History> =
        dao.getAll().map { it.toDomain() }

    suspend fun getByKey(key: String): History? =
        dao.getByKey(key)?.toDomain()

    suspend fun get(adapterName: String, bangumiId: Int, entryKind: String): History? =
        dao.get(adapterName, bangumiId, entryKind)?.toDomain()

    /**
     * 更新观看记录
     * 对应 Dart GStorage 中 history 的 put
     */
    suspend fun recordWatch(
        adapterName: String,
        bangumiItem: BangumiItem,
        episode: Int,
        episodeName: String,
        src: String,
        entryKind: String = History.KIND_ONLINE,
        episodePageUrl: String = "",
        progressMs: Long = 0,
        road: Int = 0
    ) {
        val kind = History.normalizeKind(entryKind)
        val key = History.scopedKey(adapterName, bangumiItem.id, kind)
        val now = System.currentTimeMillis()

        val existing = dao.getByKey(key)
        val progresses = existing?.let { parseProgress(it.progressJson) }
            ?.toMutableMap() ?: mutableMapOf()
        progresses[episode] = Progress(
            episode = episode,
            road = road,
            progressMs = progressMs,
            updatedAtMs = now / 1000
        )

        val entity = HistoryEntity(
            key = key,
            bangumiId = bangumiItem.id,
            bangumiJson = gson.toJson(bangumiItem.toJson()),
            lastWatchEpisode = episode,
            lastWatchEpisodeName = episodeName,
            adapterName = adapterName,
            lastWatchTime = now,
            lastSrc = src,
            entryKind = kind,
            episodePageUrl = episodePageUrl,
            progressJson = gson.toJson(progresses.values.toList())
        )
        dao.upsert(entity)
    }

    /**
     * 更新某集的播放进度
     */
    suspend fun updateProgress(
        key: String,
        episode: Int,
        progressMs: Long,
        road: Int
    ) {
        val existing = dao.getByKey(key) ?: return
        val progresses = parseProgress(existing.progressJson).toMutableMap()
        progresses[episode] = Progress(
            episode = episode,
            road = road,
            progressMs = progressMs,
            updatedAtMs = System.currentTimeMillis() / 1000
        )
        dao.upsert(existing.copy(progressJson = gson.toJson(progresses.values.toList())))
    }

    suspend fun delete(key: String) = dao.deleteByKey(key)

    suspend fun deleteOlderThan(thresholdMs: Long) = dao.deleteOlderThan(thresholdMs)

    suspend fun clear() = dao.clear()

    private fun HistoryEntity.toDomain(): History {
        val bangumi = try {
            BangumiItem.fromJson(JsonParser.parseString(bangumiJson).asJsonObject)
        } catch (e: Exception) {
            BangumiItem(id = bangumiId, name = "已删除的番剧")
        }
        return History(
            key = key,
            bangumiItem = bangumi,
            lastWatchEpisode = lastWatchEpisode,
            lastWatchEpisodeName = lastWatchEpisodeName,
            adapterName = adapterName,
            lastWatchTime = lastWatchTime,
            lastSrc = lastSrc,
            entryKind = entryKind,
            episodePageUrl = episodePageUrl,
            progresses = parseProgress(progressJson)
        )
    }

    private fun parseProgress(json: String): Map<Int, Progress> {
        return try {
            val type = object : TypeToken<List<Progress>>() {}.type
            val list: List<Progress> = gson.fromJson(json, type) ?: emptyList()
            list.associateBy { it.episode }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}