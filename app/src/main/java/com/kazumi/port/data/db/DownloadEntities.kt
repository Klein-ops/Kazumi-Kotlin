package com.kazumi.port.data.db

/**
 * 下载记录实体（一个番剧一条）
 * 对应 Dart DownloadRecord (Hive typeId: 7)
 */
data class DownloadRecordEntity(
    val key: String,
    val bangumiId: Int,
    val bangumiName: String,
    val bangumiCover: String,
    val pluginName: String,
    val episodesJson: String,
    val createdAt: Long
)

/**
 * 下载分集实体
 * 对应 Dart DownloadEpisode (Hive typeId: 8)
 */
data class DownloadEpisodeEntity(
    val id: Long,
    val recordKey: String,
    val episodeNumber: Int,
    val episodeName: String,
    val road: Int,
    /** 0=pending 1=resolving 2=downloading 3=completed 4=failed 5=paused */
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
    val danmakuData: String,
    val danDanBangumiID: Int
)

object DownloadStatus {
    const val PENDING = 0
    const val RESOLVING = 1
    const val DOWNLOADING = 2
    const val COMPLETED = 3
    const val FAILED = 4
    const val PAUSED = 5
}