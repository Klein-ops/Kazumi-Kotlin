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

    suspend fun getRecent(limit: Int = 20): List<SearchHistory> =
        dao.getRecent(limit).map { it.toDomain() }

    /**
     * 记录一次搜索
     * 同关键词覆盖旧记录（时间戳刷新）
     */
    suspend fun record(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
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