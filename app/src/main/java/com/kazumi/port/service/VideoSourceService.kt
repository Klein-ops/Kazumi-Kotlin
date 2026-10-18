package com.kazumi.port.service

/**
 * 视频源类型
 * 对应原版 video_source_format.dart / video_source_service.dart
 */
enum class VideoSourceType {
    /** 在线解析（WebView） */
    ONLINE,

    /** 本地缓存 */
    CACHED
}

/** 解析视频源时确认的媒体格式提示 */
enum class VideoSourceFormat {
    AUTO,
    HLS
}

/** 视频源解析结果 */
data class VideoSource(
    /** 视频 URL (M3U8/MP4/本地路径) */
    val url: String,
    /** 播放偏移量（秒） */
    val offset: Int,
    /** 视频源类型 */
    val type: VideoSourceType,
    /** 媒体格式提示 */
    val format: VideoSourceFormat = VideoSourceFormat.AUTO
) {
    override fun toString(): String =
        "VideoSource(url: $url, offset: $offset, type: $type, format: $format)"
}

/** 视频源未找到异常 */
class VideoSourceNotFoundException(
    message: String = "Video source not found"
) : Exception(message)

/** 视频源解析超时异常 */
class VideoSourceTimeoutException(
    val timeoutMillis: Long
) : Exception("Timed out after ${timeoutMillis / 1000}s")

/** 视频源解析取消异常 */
class VideoSourceCancelledException : Exception("Resolution was cancelled")

/** WebView 解析器上报的事件 */
data class VideoParserEvent(
    val url: String,
    val offset: Int,
    val format: VideoSourceFormat = VideoSourceFormat.AUTO
)

/**
 * 视频源解析服务接口
 *
 * 抽象视频源的获取方式，支持多种实现：
 * - WebView 解析（在线）
 * - 本地缓存读取
 * - 组合策略（优先缓存，回退 WebView）
 */
interface IVideoSourceService {

    /**
     * 解析视频源 URL
     *
     * @param episodeUrl 集数页面 URL
     * @param useLegacyParser 是否使用旧版解析器（iframe 监听）
     * @param offset 播放偏移量（秒）
     * @param timeoutMillis 解析超时时间（毫秒）
     *
     * @return [VideoSource] 包含解析后的视频 URL 和元数据
     *
     * 可能抛出：
     * - [VideoSourceNotFoundException] 未找到视频源
     * - [VideoSourceTimeoutException] 解析超时
     * - [VideoSourceCancelledException] 解析被取消
     */
    suspend fun resolve(
        episodeUrl: String,
        useLegacyParser: Boolean,
        offset: Int = 0,
        timeoutMillis: Long = 15_000
    ): VideoSource

    /** 取消当前正在进行的解析 */
    fun cancel()

    /** 释放资源 */
    suspend fun dispose()
}
