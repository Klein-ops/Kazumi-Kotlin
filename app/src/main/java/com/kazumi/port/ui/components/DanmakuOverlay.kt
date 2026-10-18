package com.kazumi.port.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kazumi.port.data.model.DanmakuEntry
import com.kazumi.port.data.model.DanmakuUtils
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 弹幕渲染层（对应原版 canvas_danmaku 画布弹幕）
 *
 * 能力：
 * - 滚动弹幕（右→左）/ 顶部弹幕 / 底部弹幕
 * - 海量弹幕叠加（massive）
 * - 弹幕描边（border）与颜色
 * - 弹幕区域（area 0..1，从顶部开始占用比例）
 * - 字体大小 / 行高 / 不透明度 / 字体字重
 * - 倍速跟随（followSpeed 时加快滚动）
 * - 时间轴偏移由 DanmakuController 负责（resolveDanmakuSecond）
 * - seek/切集时由外部调用 clearAndInvalidate 丢弃旧弹幕
 */
@Composable
fun DanmakuOverlay(
    modifier: Modifier = Modifier,
    /** 当前播放位置（ms） */
    playbackPositionMs: Long,
    /** 是否显示弹幕 */
    visible: Boolean,
    /** 弹幕开关 */
    danmakuOn: Boolean,
    /** 新增弹幕（增量语义：由外部按批次追加，不是"当前秒快照"） */
    currentDanmakus: List<DanmakuEntry>,
    /** 弹幕批次号：自增表示有一批新弹幕到达，渲染层据此消费 */
    batch: Int = 0,
    /** 是否正在播放（平滑时钟只在播放中推进） */
    playing: Boolean = true,
    /** 渲染代数：变化时清空所有活跃弹幕（seek/切集） */
    generation: Int = 0,
    /** 弹幕持续时间（秒），由 DanmakuController.danmakuDurationForSpeed 提供 */
    durationSeconds: Double = 8.0,
    /** 弹幕区域比例（0..1） */
    area: Float = 0.5f,
    /** 字体大小（sp） */
    fontSize: Int = 24,
    /** 字体字重（1..9） */
    fontWeight: Int = 4,
    /** 是否描边 */
    border: Boolean = true,
    /** 描边粗细 */
    borderSize: Float = 1.0f,
    /** 不透明度（0.1..1） */
    opacity: Float = 0.8f,
    /** 行高倍率 */
    lineHeight: Float = 1.0f,
    /** 是否显示滚动弹幕 */
    showScroll: Boolean = true,
    /** 是否显示顶部弹幕 */
    showTop: Boolean = true,
    /** 是否显示底部弹幕 */
    showBottom: Boolean = true,
    /** 是否海量模式（叠加绘制） */
    massive: Boolean = false
) {
    val density = LocalDensity.current
    val textSizePx = with(density) { fontSize.dp.toPx() }

    // 活跃弹幕：每条含开始时间/类型/颜色/速度/轨道等
    data class ActiveDanmaku(
        val entry: DanmakuEntry,
        val startMs: Long,
        val lane: Int
    )

    val active = remember { mutableStateListOf<ActiveDanmaku>() }
    var lastGeneration by remember { mutableStateOf(-1) }
    var lastBatch by remember { mutableStateOf(-1) }

    // 轨道占用表：lane -> 当前占用该轨道的弹幕结束时间（ms）
    val laneBusyUntil = remember { ConcurrentHashMap<Int, Long>() }

    val lifeMsTotal = (durationSeconds * 1000).toLong()

    // 平滑时钟（弹幕时间轴，ms）。
    // 播放位置是 200ms 轮询刷新的，直接拿它算位移，滚动就是 5fps 的跳变；
    // 这里用真实帧时钟推进，再用播放位置做周期性对齐。
    var clockMs by remember { mutableLongStateOf(playbackPositionMs) }

    // 帧循环推进时钟（仅在播放中、可见、弹幕开启时运行）
    LaunchedEffect(playing, danmakuOn, visible) {
        if (!playing || !danmakuOn || !visible) return@LaunchedEffect
        var lastFrameNs = 0L
        while (true) {
            withFrameNanos { frameNs ->
                if (lastFrameNs != 0L) {
                    val deltaMs = (frameNs - lastFrameNs) / 1_000_000L
                    // 过滤丢帧/切后台回来的巨大跳变，避免弹幕瞬移
                    if (deltaMs in 0L..200L) clockMs += deltaMs
                }
                lastFrameNs = frameNs
            }
        }
    }

    // 与真实播放位置对齐（播放中每 200ms 会有一次位置更新）
    LaunchedEffect(playbackPositionMs) {
        if (abs(playbackPositionMs - clockMs) > 400L) clockMs = playbackPositionMs
    }

    // 代数变化（seek / 切集 / 换源）：丢弃所有已排期弹幕
    LaunchedEffect(generation) {
        if (lastGeneration != generation) {
            active.clear()
            laneBusyUntil.clear()
            clockMs = playbackPositionMs
            lastGeneration = generation
        }
    }

    // 消费批次：currentDanmakus 现在是"新增弹幕"（增量），不再是"当前秒快照"。
    // 渲染层自己维护活跃列表的生命周期：进来就排期，到期由下面的淘汰循环清理。
    LaunchedEffect(batch) {
        if (batch == lastBatch) return@LaunchedEffect
        lastBatch = batch
        if (!visible || !danmakuOn) return@LaunchedEffect
        val now = clockMs
        active.removeAll { it.startMs + lifeMsTotal < now }
        currentDanmakus.forEachIndexed { index, entry ->
            if (!shouldShowType(entry, showScroll, showTop, showBottom)) return@forEachIndexed
            // 轨道分配：滚动用多轨道，顶部/底部固定一条
            val lane = when (entry.type) {
                4, 5 -> 0
                else -> assignScrollLane(laneBusyUntil, now, massive)
            }
            active.add(
                ActiveDanmaku(
                    entry = entry,
                    startMs = now + stagger(index),
                    lane = lane
                )
            )
        }
        // 缓冲上限：异常情况（如长时间暂停后恢复）不至于无限堆积
        if (active.size > 400) {
            repeat(active.size - 400) { active.removeAt(0) }
        }
    }

    // 每秒淘汰过期弹幕；关闭弹幕或不可见时清空
    LaunchedEffect(danmakuOn, visible) {
        while (true) {
            delay(1000)
            if (!visible || !danmakuOn) {
                if (active.isNotEmpty()) active.clear()
                laneBusyUntil.clear()
            } else {
                val now = clockMs
                active.removeAll { it.startMs + lifeMsTotal < now }
            }
        }
    }

    Canvas(modifier = modifier) {
        if (!visible || !danmakuOn || active.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val areaHeight = h * area.coerceIn(0.1f, 1f)

        // 计算滚动速度：视口宽度 / 持续时间
        val scrollSpeed = w / (durationSeconds * 1000).toFloat()

        val now = clockMs
        active.forEach { item ->
            val entry = item.entry
            val elapsed = now - item.startMs
            if (elapsed < 0) return@forEach
            val lifeMs = (durationSeconds * 1000).toLong()
            if (elapsed > lifeMs) return@forEach

            val type = entry.type
            val y = when (type) {
                4 -> { // 底部
                    areaHeight + item.lane * textSizePx * lineHeight + textSizePx * 0.5f
                }
                5 -> { // 顶部
                    item.lane * textSizePx * lineHeight + textSizePx * 0.5f
                }
                else -> { // 滚动
                    item.lane * textSizePx * lineHeight + textSizePx * 0.5f
                }
            }
            val x = when (type) {
                4 -> w / 2f - textSizePx * entry.message.length * 0.5f
                5 -> w / 2f - textSizePx * entry.message.length * 0.5f
                else -> {
                    val progress = elapsed.toFloat() / lifeMs.toFloat()
                    w - progress * (w + textSizePx * entry.message.length)
                }
            }

            val color = if (entry.color != 0xFFFFFFL && entry.color != 0L) {
                Color(DanmakuUtils.generateDanmakuColor(entry.color))
            } else {
                Color.White
            }.copy(alpha = opacity)

            drawDanmakuText(
                text = entry.message,
                x = x,
                y = y,
                color = color,
                textSizePx = textSizePx,
                fontWeight = fontWeight,
                border = border,
                borderSize = borderSize
            )
        }
    }
}

private fun shouldShowType(entry: DanmakuEntry, scroll: Boolean, top: Boolean, bottom: Boolean): Boolean =
    when (entry.type) {
        4 -> bottom
        5 -> top
        else -> scroll
    }

/** 滚动轨道分配：在已占用轨道中找空闲，或开新轨（非海量时复用最空闲轨） */
private fun assignScrollLane(
    laneBusyUntil: ConcurrentHashMap<Int, Long>,
    now: Long,
    massive: Boolean
): Int {
    val maxLanes = 20
    // 找完全空闲
    for (lane in 0 until maxLanes) {
        val busy = laneBusyUntil[lane] ?: 0L
        if (busy <= now) {
            laneBusyUntil[lane] = now + 8_000L
            return lane
        }
    }
    // 海量模式：直接叠加在最短占用轨
    if (massive) {
        val lane = laneBusyUntil.entries.minByOrNull { it.value }?.key ?: 0
        laneBusyUntil[lane] = now + 8_000L
        return lane
    }
    // 非海量：占用最短的轨道
    val lane = laneBusyUntil.entries.minByOrNull { it.value }?.key ?: 0
    laneBusyUntil[lane] = now + 8_000L
    return lane
}

/** 弹幕错峰（同秒多条时按序号错开 0-1000ms） */
private fun stagger(index: Int): Long = (index % 10) * 80L

/**
 * 绘制单条弹幕文本（含描边）
 */
private fun DrawScope.drawDanmakuText(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    textSizePx: Float,
    fontWeight: Int,
    border: Boolean,
    borderSize: Float
) {
    // 绘制单条弹幕文本（含描边）：统一用 android.graphics.Paint + nativeCanvas 绘制
    val androidPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        textSize = textSizePx
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            if (fontWeight >= 6) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
    }
    val native = drawContext.canvas.nativeCanvas
    if (border) {
        val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = borderSize * 2f
            this.color = android.graphics.Color.BLACK
            textSize = textSizePx
            typeface = androidPaint.typeface
        }
        native.drawText(text, x, y, strokePaint)
    }
    native.drawText(text, x, y, androidPaint)
}

/** 弹幕文本近似宽度（供轨道/进度计算） */
internal fun danmakuTextWidth(text: String, textSizePx: Float): Float =
    text.length * textSizePx * 0.9f