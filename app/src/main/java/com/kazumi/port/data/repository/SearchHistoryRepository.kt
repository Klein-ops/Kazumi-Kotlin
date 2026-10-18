package com.kazumi.port.data.repository

import com.kazumi.port.data.db.SearchHistoryDao
import com.kazumi.port.data.db.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 搜索历史
 * 对应 Dart SearchHistory
 */
data class SearchHistory(
    val keyword: String,
    val timestamp: Long
)

/**
 * 搜索历史仓库
 */
class SearchHistoryRepository(
    private val dao: SearchHistoryDao
) {

    fun observeAll(): Flow<List<SearchHistory>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    /**
     * 最近搜索记录
     *
     * search_history 表的主键是 timestamp 而不是 keyword，历史版本里同一个关键词
     * 可能被写入多行（见 record 的历史实现）。这里多取一些再按关键词去重，
     * 保证返回的 keyword 唯一——调用方（搜索页）曾用 keyword 作为 LazyColumn 的 key，
     * 重复 key 会直接抛 “Key ... was already used”。
     */
    suspend fun getRecent(limit: Int = 20): List<SearchHistory> =
        dao.getRecent(limit * 3).map { it.toDomain() }
            .distinctBy { it.keyword }
            .take(limit)

    /**
     * 记录一次搜索
     * 同关键词覆盖旧记录（时间戳刷新）
     *
     * 注意：upsert 走的是 timestamp 主键，直接插入只会新增一行，
     * 因此必须先按关键词删除旧行，否则同一关键词会出现多条记录。
     */
    suspend fun record(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        dao.deleteByKeyword(kw)
        dao.upsert(
            SearchHistoryEntity(
                timestamp = System.currentTimeMillis(),
                keyword = kw
            )
        )
    }

    suspend fun delete(keyword: String) = dao.deleteByKeyword(keyword)

    suspend fun clear() = dao.clear()

    private fun SearchHistoryEntity.toDomain(): SearchHistory =
        SearchHistory(keyword = keyword, timestamp = timestamp)
}