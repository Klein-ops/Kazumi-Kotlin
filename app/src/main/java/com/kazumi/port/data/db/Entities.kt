package com.kazumi.port.data.db

/**
 * 历史记录实体
 * 对应 Dart History (Hive typeId: 1)
 */
data class HistoryEntity(
    val key: String,
    val bangumiId: Int,
    val bangumiJson: String,
    val lastWatchEpisode: Int,
    val lastWatchEpisodeName: String,
    val adapterName: String,
    val lastWatchTime: Long,
    val lastSrc: String,
    val entryKind: String,
    val episodePageUrl: String,
    val progressJson: String
)

/**
 * 收藏实体
 * 对应 Dart CollectedBangumi (Hive typeId: 3)
 */
data class CollectedBangumiEntity(
    val bangumiId: Int,
    val bangumiJson: String,
    val time: Long,
    /** 1=在看 2=想看 3=搁置 4=看过 5=抛弃 */
    val type: Int
)

/**
 * 搜索历史实体
 * 对应 Dart SearchHistory (Hive typeId: 6)
 */
data class SearchHistoryEntity(
    val timestamp: Long,
    val keyword: String
)

/**
 * 弹幕屏蔽词实体
 * 对应 Dart shieldList (Hive box)
 */
data class ShieldEntity(
    val content: String
)