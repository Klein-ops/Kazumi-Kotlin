package com.kazumi.port.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Kazumi 原生 SQLite DAO 实现
 *
 * 背景：CodeAssist 环境的 KSP 处理器会被系统 boot classpath 的旧版
 * commons-codec 抢占，Room 注解处理必崩。改为 Android 原生 SQLite，
 * 零注解处理，任何编译器均可编译。
 *
 * 本文件为 5 个 DAO 接口提供 5 个独立实现类（分开实现的原因：
 * 多个接口存在同签名但返回类型不同的方法，如 getAll()/getByKey()，
 * 单类同时实现会在 JVM 平台产生声明冲突，编译失败）。
 *
 * 观察流采用 StateFlow 方案：
 * - 初始化时全量加载一次；
 * - 每次写操作后刷新对应 StateFlow；
 * - UI 收集到的永远是内存中最新数据（与 Room 的 Flow 语义等效，
 *   只是不依赖数据库触发器）。
 *
 * 所有读写方法均为同步调用，Repository 层负责调度到 IO 线程。
 */
class KazumiSQLiteDaos(private val helper: KazumiSQLiteHelper) {
    private val db: SQLiteDatabase
        get() = helper.writableDatabase

    // ------------------------------------------------------------------
    // 5 个独立实现类
    // ------------------------------------------------------------------

    inner class HistoryDaoImpl : HistoryDao {
        private val historyFlow = MutableStateFlow<List<HistoryEntity>>(emptyList())
        private val historyCountFlow = MutableStateFlow(0)

        init {
            refresh()
        }

        override fun observeAll(): Flow<List<HistoryEntity>> = historyFlow

        override fun getAll(): List<HistoryEntity> = queryAll(
            "SELECT * FROM histories ORDER BY lastWatchTime DESC"
        ) { it.toHistoryEntity() }

        override fun getByKey(key: String): HistoryEntity? = queryOne(
            "SELECT * FROM histories WHERE key = ? LIMIT 1",
            arrayOf(key)
        ) { it.toHistoryEntity() }

        override fun get(adapterName: String, bangumiId: Int, entryKind: String): HistoryEntity? =
            queryOne(
                "SELECT * FROM histories WHERE adapterName = ? AND bangumiId = ? AND entryKind = ? LIMIT 1",
                arrayOf(adapterName, bangumiId.toString(), entryKind)
            ) { it.toHistoryEntity() }

        override fun upsert(entity: HistoryEntity) {
            db.insertWithOnConflict(
                "histories", null, entity.toHistoryValues(), SQLiteDatabase.CONFLICT_REPLACE
            )
            refresh()
        }

        override fun delete(entity: HistoryEntity) = deleteByKey(entity.key)

        override fun deleteByKey(key: String) {
            db.delete("histories", "key = ?", arrayOf(key))
            refresh()
        }

        override fun clear() {
            db.execSQL("DELETE FROM histories")
            refresh()
        }

        override fun deleteOlderThan(threshold: Long) {
            db.delete("histories", "lastWatchTime < ?", arrayOf(threshold.toString()))
            refresh()
        }

        override fun count(): Flow<Int> = historyCountFlow

        private fun refresh() {
            historyFlow.value = getAll()
            historyCountFlow.value = historyFlow.value.size
        }
    }

    inner class CollectDaoImpl : CollectDao {
        private val collectFlow = MutableStateFlow<List<CollectedBangumiEntity>>(emptyList())

        init {
            refresh()
        }

        override fun observeAll(): Flow<List<CollectedBangumiEntity>> = collectFlow

        override fun observeByType(type: Int): Flow<List<CollectedBangumiEntity>> =
            collectFlow.map { list -> list.filter { it.type == type } }

        override fun getAll(): List<CollectedBangumiEntity> = queryAll(
            "SELECT * FROM collectibles ORDER BY time DESC"
        ) { it.toCollectEntity() }

        override fun getByBangumiId(bangumiId: Int): CollectedBangumiEntity? = queryOne(
            "SELECT * FROM collectibles WHERE bangumiId = ? LIMIT 1",
            arrayOf(bangumiId.toString())
        ) { it.toCollectEntity() }

        override fun upsert(entity: CollectedBangumiEntity) {
            db.insertWithOnConflict(
                "collectibles", null, entity.toCollectValues(), SQLiteDatabase.CONFLICT_REPLACE
            )
            refresh()
        }

        override fun delete(entity: CollectedBangumiEntity) = deleteByBangumiId(entity.bangumiId)

        override fun deleteByBangumiId(bangumiId: Int) {
            db.delete("collectibles", "bangumiId = ?", arrayOf(bangumiId.toString()))
            refresh()
        }

        override fun clear() {
            db.execSQL("DELETE FROM collectibles")
            refresh()
        }

        private fun refresh() {
            collectFlow.value = getAll()
        }
    }

    inner class SearchHistoryDaoImpl : SearchHistoryDao {
        private val searchHistoryFlow = MutableStateFlow<List<SearchHistoryEntity>>(emptyList())

        init {
            refresh()
        }

        override fun observeAll(): Flow<List<SearchHistoryEntity>> = searchHistoryFlow

        override fun getRecent(limit: Int): List<SearchHistoryEntity> = queryAll(
            "SELECT * FROM search_history ORDER BY timestamp DESC LIMIT ?",
            arrayOf(limit.toString())
        ) { it.toSearchHistoryEntity() }

        override fun upsert(entity: SearchHistoryEntity) {
            db.insertWithOnConflict(
                "search_history", null, entity.toSearchHistoryValues(), SQLiteDatabase.CONFLICT_REPLACE
            )
            refresh()
        }

        override fun deleteByKeyword(keyword: String) {
            db.delete("search_history", "keyword = ?", arrayOf(keyword))
            refresh()
        }

        override fun clear() {
            db.execSQL("DELETE FROM search_history")
            refresh()
        }

        private fun refresh() {
            searchHistoryFlow.value = queryAll(
                "SELECT * FROM search_history ORDER BY timestamp DESC"
            ) { it.toSearchHistoryEntity() }
        }
    }

    inner class ShieldDaoImpl : ShieldDao {
        private val shieldFlow = MutableStateFlow<List<ShieldEntity>>(emptyList())

        init {
            refresh()
        }

        override fun getAll(): List<ShieldEntity> = queryAll(
            "SELECT * FROM danmaku_shield"
        ) { it.toShieldEntity() }

        override fun observeAll(): Flow<List<ShieldEntity>> = shieldFlow

        override fun insert(entity: ShieldEntity) {
            db.insertWithOnConflict(
                "danmaku_shield", null, entity.toShieldValues(), SQLiteDatabase.CONFLICT_REPLACE
            )
            refresh()
        }

        override fun delete(content: String) {
            db.delete("danmaku_shield", "content = ?", arrayOf(content))
            refresh()
        }

        override fun clear() {
            db.execSQL("DELETE FROM danmaku_shield")
            refresh()
        }

        private fun refresh() {
            shieldFlow.value = getAll()
        }
    }

    inner class DownloadDaoImpl : DownloadDao {
        private val downloadFlow = MutableStateFlow<List<DownloadRecordEntity>>(emptyList())

        /** recordKey -> episodes */
        private val episodesFlow =
            MutableStateFlow<Map<String, List<DownloadEpisodeEntity>>>(emptyMap())

        /** recordKey -> completed 数量 */
        private val completedCountFlow = MutableStateFlow<Map<String, Int>>(emptyMap())

        init {
            refresh()
            refreshEpisodes()
        }

        override fun observeAll(): Flow<List<DownloadRecordEntity>> = downloadFlow

        override fun getAll(): List<DownloadRecordEntity> = queryAll(
            "SELECT * FROM downloads ORDER BY createdAt DESC"
        ) { it.toDownloadRecordEntity() }

        override fun getByKey(key: String): DownloadRecordEntity? = queryOne(
            "SELECT * FROM downloads WHERE key = ? LIMIT 1",
            arrayOf(key)
        ) { it.toDownloadRecordEntity() }

        override fun upsert(entity: DownloadRecordEntity) {
            db.insertWithOnConflict(
                "downloads", null, entity.toDownloadRecordValues(), SQLiteDatabase.CONFLICT_REPLACE
            )
            refresh()
        }

        override fun deleteByKey(key: String) {
            db.delete("downloads", "key = ?", arrayOf(key))
            refresh()
        }

        override fun getAllEpisodes(recordKey: String): List<DownloadEpisodeEntity> = queryAll(
            "SELECT * FROM download_episodes WHERE recordKey = ? ORDER BY episodeNumber ASC",
            arrayOf(recordKey)
        ) { it.toDownloadEpisodeEntity() }

        override fun observeEpisodes(recordKey: String): Flow<List<DownloadEpisodeEntity>> =
            episodesFlow.map { map -> map[recordKey] ?: emptyList() }

        override fun getEpisode(recordKey: String, episodeNumber: Int): DownloadEpisodeEntity? =
            queryOne(
                "SELECT * FROM download_episodes WHERE recordKey = ? AND episodeNumber = ? LIMIT 1",
                arrayOf(recordKey, episodeNumber.toString())
            ) { it.toDownloadEpisodeEntity() }

        override fun upsertEpisode(entity: DownloadEpisodeEntity) {
            if (entity.id > 0) {
                val values = entity.toDownloadEpisodeValues()
                values.put("id", entity.id)
                db.insertWithOnConflict(
                    "download_episodes", null, values, SQLiteDatabase.CONFLICT_REPLACE
                )
            } else {
                // id = 0：让 SQLite 自动分配自增 id
                db.insert("download_episodes", null, entity.toDownloadEpisodeValues())
            }
            refreshEpisodes()
        }

        override fun deleteEpisode(entity: DownloadEpisodeEntity) {
            db.delete(
                "download_episodes",
                "recordKey = ? AND episodeNumber = ?",
                arrayOf(entity.recordKey, entity.episodeNumber.toString())
            )
            refreshEpisodes()
        }

        override fun deleteEpisodes(recordKey: String) {
            db.delete("download_episodes", "recordKey = ?", arrayOf(recordKey))
            refreshEpisodes()
        }

        override fun observeCompletedCount(recordKey: String): Flow<Int> =
            completedCountFlow.map { map -> map[recordKey] ?: 0 }

        private fun refresh() {
            downloadFlow.value = getAll()
        }

        private fun refreshEpisodes() {
            val all = queryAll(
                "SELECT * FROM download_episodes"
            ) { it.toDownloadEpisodeEntity() }
            episodesFlow.value = all.groupBy { it.recordKey }
            completedCountFlow.value = all
                .filter { it.status == DownloadStatus.COMPLETED }
                .groupBy { it.recordKey }
                .mapValues { (_, list) -> list.size }
        }
    }

    // ------------------------------------------------------------------
    // 全局单例（5 个实现）
    // ------------------------------------------------------------------

    val historyDao: HistoryDao by lazy { HistoryDaoImpl() }
    val collectDao: CollectDao by lazy { CollectDaoImpl() }
    val searchHistoryDao: SearchHistoryDao by lazy { SearchHistoryDaoImpl() }
    val shieldDao: ShieldDao by lazy { ShieldDaoImpl() }
    val downloadDao: DownloadDao by lazy { DownloadDaoImpl() }

    // ------------------------------------------------------------------
    // 通用查询助手
    // ------------------------------------------------------------------

    private fun <T> queryAll(sql: String, args: Array<String>? = null, mapper: (Cursor) -> T): List<T> {
        val cursor = db.rawQuery(sql, args)
        val result = mutableListOf<T>()
        cursor.use { c ->
            while (c.moveToNext()) {
                result.add(mapper(c))
            }
        }
        return result
    }

    private fun <T> queryOne(sql: String, args: Array<String>? = null, mapper: (Cursor) -> T): T? {
        val cursor = db.rawQuery(sql, args)
        cursor.use { c ->
            if (c.moveToFirst()) return mapper(c)
        }
        return null
    }

    private fun Cursor.getLongOrNull(columnName: String): Long? {
        val idx = getColumnIndex(columnName)
        return if (idx >= 0 && !isNull(idx)) getLong(idx) else null
    }

    // ------------------------------------------------------------------
    // 元组 -> Entity
    // ------------------------------------------------------------------

    private fun Cursor.toHistoryEntity(): HistoryEntity = HistoryEntity(
        key = getString(getColumnIndexOrThrow("key")),
        bangumiId = getInt(getColumnIndexOrThrow("bangumiId")),
        bangumiJson = getString(getColumnIndexOrThrow("bangumiJson")),
        lastWatchEpisode = getInt(getColumnIndexOrThrow("lastWatchEpisode")),
        lastWatchEpisodeName = getString(getColumnIndexOrThrow("lastWatchEpisodeName")),
        adapterName = getString(getColumnIndexOrThrow("adapterName")),
        lastWatchTime = getLong(getColumnIndexOrThrow("lastWatchTime")),
        lastSrc = getString(getColumnIndexOrThrow("lastSrc")),
        entryKind = getString(getColumnIndexOrThrow("entryKind")),
        episodePageUrl = getString(getColumnIndexOrThrow("episodePageUrl")),
        progressJson = getString(getColumnIndexOrThrow("progressJson"))
    )

    private fun HistoryEntity.toHistoryValues(): ContentValues = ContentValues().apply {
        put("key", key)
        put("bangumiId", bangumiId)
        put("bangumiJson", bangumiJson)
        put("lastWatchEpisode", lastWatchEpisode)
        put("lastWatchEpisodeName", lastWatchEpisodeName)
        put("adapterName", adapterName)
        put("lastWatchTime", lastWatchTime)
        put("lastSrc", lastSrc)
        put("entryKind", entryKind)
        put("episodePageUrl", episodePageUrl)
        put("progressJson", progressJson)
    }

    private fun Cursor.toCollectEntity(): CollectedBangumiEntity = CollectedBangumiEntity(
        bangumiId = getInt(getColumnIndexOrThrow("bangumiId")),
        bangumiJson = getString(getColumnIndexOrThrow("bangumiJson")),
        time = getLong(getColumnIndexOrThrow("time")),
        type = getInt(getColumnIndexOrThrow("type"))
    )

    private fun CollectedBangumiEntity.toCollectValues(): ContentValues = ContentValues().apply {
        put("bangumiId", bangumiId)
        put("bangumiJson", bangumiJson)
        put("time", time)
        put("type", type)
    }

    private fun Cursor.toSearchHistoryEntity(): SearchHistoryEntity = SearchHistoryEntity(
        timestamp = getLong(getColumnIndexOrThrow("timestamp")),
        keyword = getString(getColumnIndexOrThrow("keyword"))
    )

    private fun SearchHistoryEntity.toSearchHistoryValues(): ContentValues = ContentValues().apply {
        put("timestamp", timestamp)
        put("keyword", keyword)
    }

    private fun Cursor.toShieldEntity(): ShieldEntity = ShieldEntity(
        content = getString(getColumnIndexOrThrow("content"))
    )

    private fun ShieldEntity.toShieldValues(): ContentValues = ContentValues().apply {
        put("content", content)
    }

    private fun Cursor.toDownloadRecordEntity(): DownloadRecordEntity = DownloadRecordEntity(
        key = getString(getColumnIndexOrThrow("key")),
        bangumiId = getInt(getColumnIndexOrThrow("bangumiId")),
        bangumiName = getString(getColumnIndexOrThrow("bangumiName")),
        bangumiCover = getString(getColumnIndexOrThrow("bangumiCover")),
        pluginName = getString(getColumnIndexOrThrow("pluginName")),
        episodesJson = getString(getColumnIndexOrThrow("episodesJson")),
        createdAt = getLong(getColumnIndexOrThrow("createdAt"))
    )

    private fun DownloadRecordEntity.toDownloadRecordValues(): ContentValues = ContentValues().apply {
        put("key", key)
        put("bangumiId", bangumiId)
        put("bangumiName", bangumiName)
        put("bangumiCover", bangumiCover)
        put("pluginName", pluginName)
        put("episodesJson", episodesJson)
        put("createdAt", createdAt)
    }

    private fun Cursor.toDownloadEpisodeEntity(): DownloadEpisodeEntity = DownloadEpisodeEntity(
        id = getLong(getColumnIndexOrThrow("id")),
        recordKey = getString(getColumnIndexOrThrow("recordKey")),
        episodeNumber = getInt(getColumnIndexOrThrow("episodeNumber")),
        episodeName = getString(getColumnIndexOrThrow("episodeName")),
        road = getInt(getColumnIndexOrThrow("road")),
        status = getInt(getColumnIndexOrThrow("status")),
        progressPercent = getDouble(getColumnIndexOrThrow("progressPercent")),
        totalSegments = getInt(getColumnIndexOrThrow("totalSegments")),
        downloadedSegments = getInt(getColumnIndexOrThrow("downloadedSegments")),
        localM3u8Path = getString(getColumnIndexOrThrow("localM3u8Path")),
        downloadDirectory = getString(getColumnIndexOrThrow("downloadDirectory")),
        networkM3u8Url = getString(getColumnIndexOrThrow("networkM3u8Url")),
        completedAt = getLongOrNull("completedAt"),
        errorMessage = getString(getColumnIndexOrThrow("errorMessage")),
        totalBytes = getInt(getColumnIndexOrThrow("totalBytes")),
        episodePageUrl = getString(getColumnIndexOrThrow("episodePageUrl")),
        danmakuData = getString(getColumnIndexOrThrow("danmakuData")),
        danDanBangumiID = getInt(getColumnIndexOrThrow("danDanBangumiID"))
    )

    private fun DownloadEpisodeEntity.toDownloadEpisodeValues(): ContentValues = ContentValues().apply {
        put("recordKey", recordKey)
        put("episodeNumber", episodeNumber)
        put("episodeName", episodeName)
        put("road", road)
        put("status", status)
        put("progressPercent", progressPercent)
        put("totalSegments", totalSegments)
        put("downloadedSegments", downloadedSegments)
        put("localM3u8Path", localM3u8Path)
        put("downloadDirectory", downloadDirectory)
        put("networkM3u8Url", networkM3u8Url)
        put("completedAt", completedAt)
        put("errorMessage", errorMessage)
        put("totalBytes", totalBytes)
        put("episodePageUrl", episodePageUrl)
        put("danmakuData", danmakuData)
        put("danDanBangumiID", danDanBangumiID)
    }
}