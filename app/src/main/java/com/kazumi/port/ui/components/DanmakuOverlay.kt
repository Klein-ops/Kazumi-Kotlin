package com.kazumi.port.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kazumi.port.data.model.DanmakuEntry
import com.kazumi.port.data.model.DanmakuUtils
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.random.Random

/**
 * 弹幕渲染层（对齐原版 canvas_danmaku 0.3.3 的绘制/轨道语义）
 *
 * 关键行为（与原版一致）：
 * - 轨道数 = floor(画面高度 * area / 轨道高度)，即 area 直接决定可用的弹幕行数，
 *   滚动/顶部弹幕都被约束在 area 区域内（原版 _calcTracks）。
 * - area 占满时顶部留一轨给字幕（原版 safeArea 行为）。
 * - 滚动弹幕从右边缘出发、向左完全移出画面（两端都会短暂处于画面外，这是滚动弹幕的正常表现）。
 * - 顶部弹幕从顶部向下排，底部弹幕从画面底部向上排。
 * - 轨道高度 = 字号 * 行间距，滚动轨道做“不重叠 + 不追尾”检测，避免同一行弹幕压在一起。
 * - 文本宽度用真实测量（Paint.measureText），不再用 字数*0.9 粗略近似。
 * - 静态弹幕（顶部/底部）存活 5s，滚动弹幕存活 durationSeconds。
 * - 整层裁剪到自身画布，绝不越出播放器画面。
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
    /** 当前播放倍速（弹幕时间轴按媒体时间推进，必须乘上这个倍速） */
    playbackSpeed: Float = 1f,
    /** 渲染代数：变化时清空所有活跃弹幕（seek/切集） */
    generation: Int = 0,
    /** 滚动弹幕持续时间（秒），由 DanmakuController.danmakuDurationForSpeed 提供 */
    durationSeconds: Double = 8.0,
    /** 静态弹幕（顶部/底部）持续时间（秒），对齐原版 staticDuration 默认 5s */
    staticDurationSeconds: Double = 5.0,
    /** 弹幕区域比例（0..1），对齐原版 danmakuArea 默认 1.0 */
    area: Float = 1.0f,
    /** 字体大小（sp），对齐原版默认 25 */
    fontSize: Int = 25,
    /** 字体字重（1..9） */
    fontWeight: Int = 4,
    /** 是否描边 */
    border: Boolean = true,
    /** 描边粗细，对齐原版默认 1.5 */
    borderSize: Float = 1.5f,
    /** 不透明度（0.1..1），对齐原版默认 1.0 */
    opacity: Float = 1.0f,
    /** 行高倍率，对齐原版默认 1.6 */
    lineHeight: Float = 1.6f,
    /** 是否显示滚动弹幕 */
    showScroll: Boolean = true,
    /** 是否显示顶部弹幕 */
    showTop: Boolean = true,
    /** 是否显示底部弹幕（原版默认关闭） */
    showBottom: Boolean = false,
    /** 是否海量模式（轨道占满时叠加绘制） */
    massive: Boolean = false,
    /** 为字幕留出余量：原版 DanmakuOption.safeArea 默认 true，Kazumi 未覆盖 */
    safeArea: Boolean = true
) {
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSize.dp.toPx() }

    // 复用 Paint，避免每帧每条弹幕都 new 两个 Paint
    val textPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val strokePaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }

    val active = remember { mutableStateListOf<ActiveDanmaku>() }
    var lastGeneration by remember { mutableStateOf(-1) }
    var lastBatch by remember { mutableStateOf(-1) }
    // 外观参数快照：显示区域/字号/行间距/开关等变化时，即使 batch 未变也要清空重排，
    // 让"播放器内弹幕设置"实时生效（否则只能靠跳转/重进触发 generation 变化才重排）
    var lastAppearance by remember { mutableStateOf<String?>(null) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    val lifeMsTotal = (durationSeconds * 1000).toLong().coerceAtLeast(1000L)
    val staticLifeMs = (staticDurationSeconds * 1000).toLong().coerceAtLeast(1000L)

    // 轨道高度：字号 * 行间距（原版用 TextPainter.height，Flutter 下即 fontSize*height）
    val trackHeight = (fontSizePx * lineHeight.coerceAtLeast(0.5f)).coerceAtLeast(1f)

    // 平滑时钟（弹幕时间轴，ms）。
    // 关键：弹幕时间轴是"媒体时间"，必须按 playbackSpeed 推进。
    // 之前只按真实毫秒推进，倍速下时钟持续落后于播放位置，只能靠 400ms 硬对齐去追，
    // 表现为弹幕一跳一跳往前；倍速切回时位置读数与时长变化又会让弹幕整体回弹。
    var clockMs by remember { mutableLongStateOf(playbackPositionMs) }
    val speedState = rememberUpdatedState(playbackSpeed)
    val positionState = rememberUpdatedState(playbackPositionMs)
    LaunchedEffect(playing, danmakuOn, visible) {
        if (!playing || !danmakuOn || !visible) return@LaunchedEffect
        var lastFrameNs = 0L
        var carry = 0.0
        while (true) {
            withFrameNanos { frameNs ->
                if (lastFrameNs != 0L) {
                    val deltaMs = (frameNs - lastFrameNs) / 1_000_000.0
                    // 过滤丢帧/切后台回来的巨大跳变，避免弹幕瞬移
                    if (deltaMs in 0.0..250.0) {
                        carry += deltaMs * speedState.value.toDouble()
                        val step = carry.toLong()
                        if (step != 0L) {
                            clockMs += step
                            carry -= step
                        }
                        // 与播放位置温和对齐：只追"落后"，不主动往回拉。
                        // playbackPositionMs 是 200ms 采样值，正常情况下 clockMs 会略微领先它，
                        // 此时若把 clock 拉回去，弹幕就会一顿一顿甚至回弹。
                        val drift = positionState.value - clockMs
                        when {
                            drift > 700L -> {
                                clockMs = positionState.value
                                carry = 0.0
                            }
                            drift < -1500L -> {
                                // 真正的大幅后退（seek）：直接跟随，避免与播放位置脱节
                                clockMs = positionState.value
                                carry = 0.0
                            }
                            drift > 250L -> clockMs += (drift * 0.1).toLong()
                        }
                    }
                }
                lastFrameNs = frameNs
            }
        }
    }
    // 暂停/不可见期间平滑时钟不推进：仅在位置明显跳变（seek）时直接跟随（通常伴随 generation 变化）
    LaunchedEffect(playbackPositionMs, playing) {
        val drift = playbackPositionMs - clockMs
        if (drift > 700L || drift < -1500L) clockMs = playbackPositionMs
    }
    // 代数变化（seek / 切集 / 换源）：丢弃所有已排期弹幕
    LaunchedEffect(generation) {
        if (lastGeneration != generation) {
            active.clear()
            clockMs = playbackPositionMs
            lastGeneration = generation
        }
    }

    /** 按当前画布高度与 area 计算轨道高度和轨道数（原版 _calcTracks） */
    fun calcTracks(height: Float): Pair<Float, Int> {
        if (height <= 0f) return trackHeight to 1
        val usableArea = area.coerceIn(0.1f, 1f)
        var count = floor(height * usableArea / trackHeight).toInt()
        // 原版 safeArea：area 占满时为字幕留一条轨（去掉最下面那条）
        if (safeArea && usableArea >= 0.999f) count -= 1
        return trackHeight to count.coerceAtLeast(1)
    }

    /**
     * 外观参数变化（显示区域/字号/行间距/开关）时，把在場弹幕按新轨道参数重排 lane。
     * 只重排 lane 不重建（弹幕内容、进度、存活期都保留），新弹幕会按新参数正常排布，
     * 避免"必须跳转/重进才生效"。
     */
    fun relaneActive(now: Long, w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        val (_, trackCount) = calcTracks(h)
        // 每条弹幕按当前规则重新找一条可用 lane：滚动优先空轨、静态按类型找空轨
        val reassigned = active
            .filter { item -> item.startMs + item.lifeMs > now }
            .map { item ->
                val isStatic = isStaticType(item.entry.type)
                var lane = -1
                if (isStatic) {
                    val firstLane = if (item.entry.type == 4 && safeArea) 1 else 0
                    for (i in firstLane until trackCount) {
                        val busy = active.any {
                            it.entry.type == item.entry.type && it.lane == i &&
                                it.startMs + it.lifeMs > now && it !== item
                        }
                        if (!busy) { lane = i; break }
                    }
                    if (lane < 0) lane = 0
                } else {
                    for (i in 0 until trackCount) {
                        if (scrollLaneFree(i, active, now, w, item.widthPx)) { lane = i; break }
                    }
                    if (lane < 0) lane = if (massive) Random.nextInt(trackCount.coerceAtLeast(1)) else 0
                }
                item.copy(lane = lane.coerceAtLeast(0))
            }
        // 原地替换（保持 active 引用不变，避免破坏 Canvas 闭包引用）
        active.clear()
        active.addAll(reassigned)
    }

    // 消费批次：currentDanmakus 是"新增弹幕"（增量），渲染层维护生命周期
    LaunchedEffect(batch, trackHeight, area, massive, safeArea, showScroll, showTop, showBottom) {
        // 影响轨道布局的外观参数快照（字号/行间距变化会体现在 trackHeight 上）
        val appearanceKey =
            "$trackHeight|$area|$massive|$safeArea|$showScroll|$showTop|$showBottom"
        if (batch == lastBatch) {
            // 批次未变：只有外观参数变化才需要处理——在場弹幕按新轨道参数重排 lane，
            // 立即生效（否则显示区域/字号改动要等跳转/重进才重排）
            if (appearanceKey != lastAppearance && canvasSize.width > 0f && canvasSize.height > 0f) {
                lastAppearance = appearanceKey
                relaneActive(clockMs, canvasSize.width, canvasSize.height)
            }
            return@LaunchedEffect
        }
        if (!visible || !danmakuOn) {
            lastBatch = batch
            return@LaunchedEffect
        }
        val w = canvasSize.width
        // 画布还没测量完：不消费本批次，等 onSizeChanged 后重试（否则进入播放器/seek 后的第一批弹幕会被吞掉）
        if (w <= 0f) return@LaunchedEffect
        lastBatch = batch
        lastAppearance = appearanceKey
        val now = clockMs
        active.removeAll { item -> item.startMs + item.lifeMs < now }
        // 文本测量准备
        textPaint.textSize = fontSizePx
        textPaint.typeface = typefaceFor(fontWeight)

        val (_, trackCount) = calcTracks(canvasSize.height)

        currentDanmakus.forEach { entry ->
            if (!shouldShowType(entry, showScroll, showTop, showBottom)) return@forEach
            val widthPx = textPaint.measureText(entry.message).toFloat() + borderSize.coerceAtLeast(0f)
            val type = entry.type
            // 存活时长在创建时冻结：倍速切换会改变 durationSeconds，
            // 若让在場弹幕跟着重算，progress 会突变导致整屏回弹。
            val lifeMs = if (isStaticType(type)) staticLifeMs else lifeMsTotal
            var lane = -1
            if (isStaticType(type)) {
                // 顶部/底部：找一条该类型空闲轨道（原版 _top/_bottomCanAddToTrack）
                // 底部弹幕额外遵守 safeArea：最下面那条轨留给字幕（原版 yPosition <= danmakuHeight 时不可用）
                val firstLane = if (type == 4 && safeArea) 1 else 0
                for (i in firstLane until trackCount) {
                    val busy = active.any {
                        it.entry.type == type && it.lane == i && it.startMs + it.lifeMs > now
                    }
                    if (!busy) {
                        lane = i
                        break
                    }
                }
                // 原版：静态弹幕轨道占满就直接丢弃，海量模式也不会叠加
                if (lane < 0) return@forEach
            } else {
                // 滚动：不重叠 + 不追尾（原版 _scrollCanAddToTrack）
                for (i in 0 until trackCount) {
                    if (scrollLaneFree(i, active, now, w, widthPx)) {
                        lane = i
                        break
                    }
                }
                // 原版：滚动弹幕轨道占满时，海量模式随机挑一条轨叠加，否则丢弃
                if (lane < 0) {
                    if (!massive) return@forEach
                    lane = Random.nextInt(trackCount)
                }
            }
            active.add(
                ActiveDanmaku(
                    entry = entry,
                    startMs = now,
                    lane = lane,
                    widthPx = widthPx,
                    lifeMs = lifeMs
                )
            )
        }
        // 缓冲上限：异常情况（如长时间暂停后恢复）不至于无限堆积
        if (active.size > 600) {
            repeat(active.size - 600) { active.removeAt(0) }
        }
    }

    // 周期淘汰过期弹幕；关闭弹幕或不可见时清空
    LaunchedEffect(danmakuOn, visible) {
        while (true) {
            delay(1000)
            if (!visible || !danmakuOn) {
                if (active.isNotEmpty()) active.clear()
            } else {
                val now = clockMs
                active.removeAll { item -> item.startMs + item.lifeMs < now }
            }
        }
    }

    Canvas(modifier = modifier.onSizeChanged {
        canvasSize = Size(it.width.toFloat(), it.height.toFloat())
    }) {
        if (!visible || !danmakuOn || active.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val (track, trackCount) = calcTracks(h)
        val now = clockMs
        // 整层裁剪到播放器画面内，绝不画到画面外
        clipRect(0f, 0f, w, h) {
            active.forEach { item ->
                val entry = item.entry
                val life = item.lifeMs
                val elapsed = now - item.startMs
                if (elapsed < 0L || elapsed > life) return@forEach
                if (item.lane >= trackCount) return@forEach
                val topY: Float
                val x: Float
                when (entry.type) {
                    5 -> { // 顶部：从顶部向下
                        topY = item.lane * track
                        x = (w - item.widthPx) / 2f
                    }
                    4 -> { // 底部：从画面底部向上
                        topY = h - (item.lane + 1) * track
                        x = (w - item.widthPx) / 2f
                    }
                    else -> { // 滚动：右 -> 左
                        val progress = elapsed.toFloat() / life.toFloat()
                        topY = item.lane * track
                        x = w - progress * (w + item.widthPx)
                    }
                }
                val color = if (entry.color != 0xFFFFFFL && entry.color != 0L) {
                    Color(DanmakuUtils.generateDanmakuColor(entry.color))
                } else {
                    Color.White
                }.copy(alpha = opacity.coerceIn(0.05f, 1f))
                drawDanmakuText(
                    text = entry.message,
                    x = x,
                    topY = topY,
                    color = color,
                    textSizePx = fontSizePx,
                    fontWeight = fontWeight,
                    border = border,
                    borderSize = borderSize,
                    textPaint = textPaint,
                    strokePaint = strokePaint
                )
            }
        }
    }
}

/** 滚动弹幕存活期内的 x 坐标（原版：起点 = 画面宽度，终点 = -文本宽度） */
private fun scrollX(item: ActiveDanmaku, now: Long, viewWidth: Float): Float {
    val progress = ((now - item.startMs).toFloat() / item.lifeMs.toFloat()).coerceIn(0f, 1f)
    return viewWidth - progress * (viewWidth + item.widthPx)
}

private fun isStaticType(type: Int): Boolean = type == 4 || type == 5

private fun typefaceFor(fontWeight: Int): android.graphics.Typeface =
    android.graphics.Typeface.create(
        android.graphics.Typeface.DEFAULT,
        if (fontWeight >= 6) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
    )

/** 活跃弹幕（已排期） */
private data class ActiveDanmaku(
    val entry: DanmakuEntry,
    val startMs: Long,
    val lane: Int,
    val widthPx: Float,
    val lifeMs: Long
)

private fun shouldShowType(entry: DanmakuEntry, scroll: Boolean, top: Boolean, bottom: Boolean): Boolean =
    when (entry.type) {
        4 -> bottom
        5 -> top
        else -> scroll
    }

/**
 * 某条滚动轨道能否放入新弹幕（原版 canvas_danmaku _scrollCanAddToTrack 的等价实现）
 *
 * 原版规则：
 * 1) 轨道上已有弹幕只要还有一部分露在画面右边缘（viewWidth - (x + width) < 0），该轨直接不可用；
 * 2) 若已有弹幕比新弹幕窄（速度快），还要保证新弹幕在它完全移出前追不上它。
 */
private fun scrollLaneFree(
    lane: Int,
    active: List<ActiveDanmaku>,
    now: Long,
    viewWidth: Float,
    newWidth: Float
): Boolean {
    for (item in active) {
        if (isStaticType(item.entry.type) || item.lane != lane) continue
        val existingX = scrollX(item, now, viewWidth)
        val existingEnd = existingX + item.widthPx
        if (viewWidth - existingEnd < 0f) return false
        if (item.widthPx < newWidth) {
            val existingProgress = 1f - ((viewWidth - existingX) / (item.widthPx + viewWidth))
            if (existingProgress > viewWidth / (viewWidth + newWidth)) return false
        }
    }
    return true
}

/**
 * 绘制单条弹幕文本（含描边）
 * @param topY 文本行顶部（不是 baseline）
 */
private fun DrawScope.drawDanmakuText(
    text: String,
    x: Float,
    topY: Float,
    color: Color,
    textSizePx: Float,
    fontWeight: Int,
    border: Boolean,
    borderSize: Float,
    textPaint: android.graphics.Paint,
    strokePaint: android.graphics.Paint
) {
    val typeface = typefaceFor(fontWeight)
    textPaint.apply {
        this.color = color.toArgb()
        textSize = textSizePx
        this.typeface = typeface
    }
    val baseline = topY - textPaint.fontMetrics.ascent
    val native = drawContext.canvas.nativeCanvas
    if (border && borderSize > 0f) {
        strokePaint.apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = borderSize * 2f
            this.color = android.graphics.Color.BLACK
            textSize = textSizePx
            this.typeface = typeface
        }
        native.drawText(text, x, baseline, strokePaint)
    }
    native.drawText(text, x, baseline, textPaint)
}