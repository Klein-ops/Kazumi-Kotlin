package com.kazumi.port.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.kazumi.port.data.db.CollectDao
import com.kazumi.port.data.db.CollectedBangumiEntity
import com.kazumi.port.data.model.BangumiItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 收藏条目
 * 对应 Dart CollectedBangumi
 */
data class CollectedBangumi(
    val bangumiItem: BangumiItem,
    val time: Long,
    /** 1=在看 2=想看 3=搁置 4=看过 5=抛弃 */
    val type: Int
)

object CollectType {
    const val WATCHING = 1
    const val WANT_TO_WATCH = 2
    const val ON_HOLD = 3
    const val COMPLETED = 4
    const val DROPPED = 5
}

/**
 * 收藏仓库
 */
class CollectRepository(
    private val dao: CollectDao
) {
    private val gson = Gson()

    fun observeAll(): Flow<List<CollectedBangumi>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeByType(type: Int): Flow<List<CollectedBangumi>> =
        dao.observeByType(type).map { list -> list.map { it.toDomain() } }

    suspend fun getAll(): List<CollectedBangumi> =
        dao.getAll().map { it.toDomain() }

    suspend fun getByBangumiId(bangumiId: Int): CollectedBangumi? =
        dao.getByBangumiId(bangumiId)?.toDomain()

    suspend fun upsert(bangumiItem: BangumiItem, type: Int) {
        dao.upsert(
            CollectedBangumiEntity(
                bangumiId = bangumiItem.id,
                bangumiJson = gson.toJson(bangumiItem.toJson()),
                time = System.currentTimeMillis(),
                type = type
            )
        )
    }

    suspend fun updateType(bangumiId: Int, type: Int) {
        val existing = dao.getByBangumiId(bangumiId) ?: return
        dao.upsert(existing.copy(type = type, time = System.currentTimeMillis()))
    }

    suspend fun delete(bangumiId: Int) {
        dao.deleteByBangumiId(bangumiId)
    }

    suspend fun clear() {
        dao.clear()
    }

    private fun CollectedBangumiEntity.toDomain(): CollectedBangumi {
        val bangumi = try {
            BangumiItem.fromJson(JsonParser.parseString(bangumiJson).asJsonObject)
        } catch (e: Exception) {
            BangumiItem(id = bangumiId, name = "未知番剧")
        }
        return CollectedBangumi(
            bangumiItem = bangumi,
            time = time,
            type = type
        )
    }
}