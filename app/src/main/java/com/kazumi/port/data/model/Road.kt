package com.kazumi.port.data.model

/**
 * 播放线路（包含多个剧集 URL）
 * 对应 Dart Road
 */
data class Road(
    var name: String,
    var data: List<String>,
    var identifier: List<String>
)