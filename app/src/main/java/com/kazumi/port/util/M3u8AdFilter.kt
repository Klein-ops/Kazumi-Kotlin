package com.kazumi.port.util

import com.kazumi.port.util.M3u8Parser.M3u8Segment

/**
 * M3U8 广告过滤
 *
 * 对应原版 lib/utils/m3u8_ad_filter.dart。
 * 模拟 FFmpeg hls_ad_filter 行为：基于 discontinuityGroup 分组，
 * 识别并剔除广告分片组。
 */
object M3u8AdFilter {

    /**
     * 过滤广告分片。
     * 规则：
     * - 仅一组分片 -> 无广告，原样返回
     * - 相对主内容时长 < 30% 的组判定为广告
     * - 首/末组且时长 < 30s 判定为广告
     * - 时长 < 10s 的组几乎肯定是广告
     */
    fun filterAds(segments: List<M3u8Segment>): List<M3u8Segment> {
        if (segments.isEmpty()) return segments

        // 按 discontinuityGroup 分组
        val groups = LinkedHashMap<Int, MutableList<M3u8Segment>>()
        for (seg in segments) {
            groups.getOrPut(seg.discontinuityGroup) { mutableListOf() }.add(seg)
        }

        // 只有一组说明没有广告
        if (groups.size <= 1) return segments

        // 计算每组的时长
        val groupDurations = HashMap<Int, Double>()
        for ((groupId, segs) in groups) {
            groupDurations[groupId] = segs.sumOf { it.duration }
        }

        // 最长组作为"主内容"参考
        var maxDuration = 0.0
        for (d in groupDurations.values) {
            if (d > maxDuration) maxDuration = d
        }

        // 识别广告组
        val adGroups = mutableSetOf<Int>()
        val sortedKeys = groups.keys.sorted()

        for (groupId in sortedKeys) {
            val groupDuration = groupDurations[groupId] ?: continue

            // 跳过主内容组
            if (groupDuration == maxDuration) continue

            var isAd = false

            // 相对主内容过短 (< 30%)
            if (groupDuration < maxDuration * 0.3) {
                isAd = true
            }

            // 首或末组且时长 < 30s
            if ((groupId == sortedKeys.first() || groupId == sortedKeys.last()) &&
                groupDuration < 30.0
            ) {
                isAd = true
            }

            // 极短分片 (< 10s) 几乎肯定是广告
            if (groupDuration < 10.0) {
                isAd = true
            }

            if (isAd) {
                adGroups.add(groupId)
            }
        }

        if (adGroups.isEmpty()) return segments

        // 移除广告分片
        return segments.filter { !adGroups.contains(it.discontinuityGroup) }
    }

    /** 过滤后重新计算 target duration（最大分片时长） */
    fun calculateTargetDuration(segments: List<M3u8Segment>): Double {
        if (segments.isEmpty()) return 0.0
        var maxSegDuration = 0.0
        for (seg in segments) {
            if (seg.duration > maxSegDuration) {
                maxSegDuration = seg.duration
            }
        }
        return maxSegDuration
    }
}