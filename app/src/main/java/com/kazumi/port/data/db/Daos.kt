package com.kazumi.port.data.db

import kotlinx.coroutines.flow.Flow

/**
 * 历史记录 DAO
 *
 * 注意：已将原 Room 注解接口改为纯 Kotlin 接口，由 SQLite 实现类提供实现
 * （见 SQLiteDaos.kt）。方法均为同步非 suspend，方便在任意线程调用
 * （DownloadManager 等普通函数可直接调用）。
 */
interface HistoryDao {

    fun observeAll(): Flow<List<HistoryEntity>>

    fun getAll(): List<HistoryEntity>

    fun getByKey(key: String): HistoryEntity?

    fun get(adapterName: String, bangumiId: Int, entryKind: String): HistoryEntity?

    fun upsert(entity: HistoryEntity)

    fun delete(entity: HistoryEntity)

    fun deleteByKey(key: String)

    fun clear()

    fun deleteOlderThan(threshold: Long)

    fun count(): Flow<Int>
}

/**
 * 收藏 DAO
 */
interface CollectDao {

    fun observeAll(): Flow<List<CollectedBangumiEntity>>

    fun observeByType(type: Int): Flow<List<CollectedBangumiEntity>>

    fun getAll(): List<CollectedBangumiEntity>

    fun getByBangumiId(bangumiId: Int): CollectedBangumiEntity?

    fun upsert(entity: CollectedBangumiEntity)

    fun delete(entity: CollectedBangumiEntity)

    fun deleteByBangumiId(bangumiId: Int)

    fun clear()
}

/**
 * 搜索历史 DAO
 */
interface SearchHistoryDao {

    fun observeAll(): Flow<List<SearchHistoryEntity>>

    fun getRecent(limit: Int): List<SearchHistoryEntity>

    fun upsert(entity: SearchHistoryEntity)

    fun deleteByKeyword(keyword: String)

    fun clear()
}

/**
 * 弹幕屏蔽词 DAO
 */
interface ShieldDao {

    fun getAll(): List<ShieldEntity>

    fun observeAll(): Flow<List<ShieldEntity>>

    fun insert(entity: ShieldEntity)

    fun delete(content: String)

    fun clear()
}

/**
 * 下载记录 DAO
 */
interface DownloadDao {

    fun observeAll(): Flow<List<DownloadRecordEntity>>

    fun getAll(): List<DownloadRecordEntity>

    fun getByKey(key: String): DownloadRecordEntity?

    fun upsert(entity: DownloadRecordEntity)

    fun deleteByKey(key: String)

    fun getAllEpisodes(recordKey: String): List<DownloadEpisodeEntity>

    fun observeEpisodes(recordKey: String): Flow<List<DownloadEpisodeEntity>>

    fun getEpisode(recordKey: String, episodeNumber: Int): DownloadEpisodeEntity?

    fun upsertEpisode(entity: DownloadEpisodeEntity)

    fun deleteEpisode(entity: DownloadEpisodeEntity)

    fun deleteEpisodes(recordKey: String)

    fun observeCompletedCount(recordKey: String): Flow<Int>
}