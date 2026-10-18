package com.kazumi.port.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kazumi.port.data.db.DownloadDao
import com.kazumi.port.data.db.DownloadEpisodeEntity
import com.kazumi.port.data.db.DownloadRecordEntity
import com.kazumi.port.data.db.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 下载记录领域模型（对应原版 DownloadRecord）
 */
data class DownloadRecord(
    val key: String,
    val bangumiId: Int,
    val bangumiName: String,
    val bangumiCover: String,
    val pluginName: String,
    val createdAt: Long,
    val episodes: Map<Int, DownloadEpisode>
)

/**
 * 下载分集领域模型（对应原版 DownloadEpisode）
 */
data class DownloadEpisode(
    val episodeNumber: Int,
    val episodeName: String,
    val road: Int,
    val status: Int,
    val progressPercent: Double,
    val totalSegments: Int,
    val downloadedSegments: Int,
    val localM3u8Path: String,
    val downloadDirectory: String,
    val networkM3u8Url: String,
    val completedAt: Long?,
    val errorMessage: String,
    val totalBytes: Int,
    val episodePageUrl: String,
    val danmakuData: String = "",
    val danDanBangumiID: Int = 0
)

/**
 * 下载仓库
 *
 * 对应原版 lib/repositories/download_repository.dart。
 * 将 Room DAO 封装为 UI/控制器可用的领域接口；
 * 提供「记录 + 集数」的组合读写与 Flow 监听。
 */
class DownloadRepository(
    private val dao: DownloadDao,
    private val settings: SettingsManager
) {
    private val gson = Gson()

    /** 记录变化流（对应原版 changes） */
    fun observeRecords(): Flow<List<DownloadRecord>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getAllRecords(): List<DownloadRecord> =
        dao.getAll().map { it.toDomain() }

    suspend fun getRecord(key: String): DownloadRecord? =
        dao.getByKey(key)?.toDomain()

    suspend fun getRecordByBangumiId(bangumiId: Int, pluginName: String): DownloadRecord? =
        dao.getByKey("${pluginName}_$bangumiId")?.toDomain()

    /** 保存记录（含集数），对应原版 putRecord */
    suspend fun putRecord(record: DownloadRecord) {
        dao.upsert(
            DownloadRecordEntity(
                key = record.key,
                bangumiId = record.bangumiId,
                bangumiName = record.bangumiName,
                bangumiCover = record.bangumiCover,
                pluginName = record.pluginName,
                episodesJson = gson.toJson(record.episodes.values.map { it.toJson() }),
                createdAt = record.createdAt
            )
        )
        // 逐个持久化集数
        for (episode in record.episodes.values) {
            dao.upsertEpisode(episode.toEntity(record.key))
        }
    }

    suspend fun deleteRecord(key: String) {
        dao.deleteEpisodes(key)
        dao.deleteByKey(key)
    }

    /** 更新单集（对应原版 updateEpisode） */
    suspend fun updateEpisode(recordKey: String, episodeNumber: Int, episode: DownloadEpisode) {
        val existing = dao.getByKey(recordKey) ?: return
        val current = dao.getEpisode(recordKey, episodeNumber)
        dao.upsertEpisode(episode.toEntity(recordKey, id = current?.id ?: 0L))

        // 同步记录内的集数 JSON
        val episodes = parseEpisodes(existing.episodesJson).toMutableMap()
        episodes[episodeNumber] = episode
        dao.upsert(existing.copy(episodesJson = gson.toJson(episodes.values.map { it.toJson() })))
    }

    suspend fun deleteEpisode(recordKey: String, episodeNumber: Int) {
        val entity = dao.getEpisode(recordKey, episodeNumber) ?: return
        dao.deleteEpisode(entity)

        val existing = dao.getByKey(recordKey) ?: return
        val episodes = parseEpisodes(existing.episodesJson).toMutableMap()
        episodes.remove(episodeNumber)
        dao.upsert(
            existing.copy(
                episodesJson = gson.toJson(episodes.values.map { it.toJson() })
            )
        )
    }

    suspend fun getEpisode(bangumiId: Int, pluginName: String, episodeNumber: Int): DownloadEpisode? {
        val recordKey = "${pluginName}_$bangumiId"
        return dao.getEpisode(recordKey, episodeNumber)?.toDomain()
    }

    /** 直接取实体（供 DownloadManager.getLocalVideoPath 使用） */
    suspend fun getEpisodeEntity(bangumiId: Int, pluginName: String, episodeNumber: Int): DownloadEpisodeEntity? {
        val recordKey = "${pluginName}_$bangumiId"
        return dao.getEpisode(recordKey, episodeNumber)
    }

    suspend fun getEpisodeByUrl(
        bangumiId: Int,
        pluginName: String,
        episodePageUrl: String
    ): DownloadEpisode? {
        if (episodePageUrl.isEmpty()) return null
        val recordKey = "${pluginName}_$bangumiId"
        val episodes = dao.getAllEpisodes(recordKey)
        return episodes.firstOrNull { it.episodePageUrl == episodePageUrl }?.toDomain()
    }

    suspend fun getCompletedEpisodes(bangumiId: Int, pluginName: String): List<DownloadEpisode> {
        val recordKey = "${pluginName}_$bangumiId"
        return dao.getAllEpisodes(recordKey)
            .filter { it.status == DownloadStatus.COMPLETED }
            .map { it.toDomain() }
    }

    /** 强制广告拦截开关（对应原版 getForceAdBlocker） */
    suspend fun getForceAdBlocker(): Boolean =
        settings.getBoolean(SettingsManager.Player.FORCE_AD_BLOCKER, false)

    /** 某记录的集数变化流 */
    fun observeEpisodes(recordKey: String): Flow<List<DownloadEpisode>> =
        dao.observeEpisodes(recordKey).map { list -> list.map { it.toDomain() } }

    fun observeCompletedCount(recordKey: String): Flow<Int> =
        dao.observeCompletedCount(recordKey)

    // ------------------------------------------------------------------
    // 转换
    // ------------------------------------------------------------------

    private fun DownloadRecordEntity.toDomain(): DownloadRecord {
        val episodes = parseEpisodes(episodesJson)
        return DownloadRecord(
            key = key,
            bangumiId = bangumiId,
            bangumiName = bangumiName,
            bangumiCover = bangumiCover,
            pluginName = pluginName,
            createdAt = createdAt,
            episodes = episodes
        )
    }

    private fun DownloadEpisodeEntity.toDomain(): DownloadEpisode =
        DownloadEpisode(
            episodeNumber = episodeNumber,
            episodeName = episodeName,
            road = road,
            status = status,
            progressPercent = progressPercent,
            totalSegments = totalSegments,
            downloadedSegments = downloadedSegments,
            localM3u8Path = localM3u8Path,
            downloadDirectory = downloadDirectory,
            networkM3u8Url = networkM3u8Url,
            completedAt = completedAt,
            errorMessage = errorMessage,
            totalBytes = totalBytes,
            episodePageUrl = episodePageUrl,
            danmakuData = danmakuData,
            danDanBangumiID = danDanBangumiID
        )

    private fun DownloadEpisode.toEntity(recordKey: String, id: Long = 0): DownloadEpisodeEntity =
        DownloadEpisodeEntity(
            id = id,
            recordKey = recordKey,
            episodeNumber = episodeNumber,
            episodeName = episodeName,
            road = road,
            status = status,
            progressPercent = progressPercent,
            totalSegments = totalSegments,
            downloadedSegments = downloadedSegments,
            localM3u8Path = localM3u8Path,
            downloadDirectory = downloadDirectory,
            networkM3u8Url = networkM3u8Url,
            completedAt = completedAt,
            errorMessage = errorMessage,
            totalBytes = totalBytes,
            episodePageUrl = episodePageUrl,
            danmakuData = danmakuData,
            danDanBangumiID = danDanBangumiID
        )

    private fun DownloadEpisode.toJson(): Map<String, Any?> = mapOf(
        "episodeNumber" to episodeNumber,
        "episodeName" to episodeName,
        "road" to road,
        "status" to status,
        "progressPercent" to progressPercent,
        "totalSegments" to totalSegments,
        "downloadedSegments" to downloadedSegments,
        "localM3u8Path" to localM3u8Path,
        "downloadDirectory" to downloadDirectory,
        "networkM3u8Url" to networkM3u8Url,
        "completedAt" to completedAt,
        "errorMessage" to errorMessage,
        "totalBytes" to totalBytes,
        "episodePageUrl" to episodePageUrl,
        "danmakuData" to danmakuData,
        "danDanBangumiID" to danDanBangumiID
    )

    private fun parseEpisodes(json: String): Map<Int, DownloadEpisode> {
        return try {
            val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
            val list: List<Map<String, Any?>> = gson.fromJson(json, type) ?: emptyList()
            list.mapNotNull { map ->
                val number = (map["episodeNumber"] as? Double)?.toInt()
                    ?: (map["episodeNumber"] as? Int) ?: return@mapNotNull null
                number to DownloadEpisode(
                    episodeNumber = number,
                    episodeName = map["episodeName"] as? String ?: "",
                    road = (map["road"] as? Double)?.toInt() ?: 0,
                    status = (map["status"] as? Double)?.toInt() ?: DownloadStatus.PENDING,
                    progressPercent = (map["progressPercent"] as? Double) ?: 0.0,
                    totalSegments = (map["totalSegments"] as? Double)?.toInt() ?: 0,
                    downloadedSegments = (map["downloadedSegments"] as? Double)?.toInt() ?: 0,
                    localM3u8Path = map["localM3u8Path"] as? String ?: "",
                    downloadDirectory = map["downloadDirectory"] as? String ?: "",
                    networkM3u8Url = map["networkM3u8Url"] as? String ?: "",
                    completedAt = (map["completedAt"] as? Double)?.toLong(),
                    errorMessage = map["errorMessage"] as? String ?: "",
                    totalBytes = (map["totalBytes"] as? Double)?.toInt() ?: 0,
                    episodePageUrl = map["episodePageUrl"] as? String ?: "",
                    danmakuData = map["danmakuData"] as? String ?: "",
                    danDanBangumiID = (map["danDanBangumiID"] as? Double)?.toInt() ?: 0
                )
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }
}