package com.kazumi.port.player

/**
 * 播放器面板状态（对应原版 player_panel_controller.dart）
 *
 * 记录视频比例 / 亮度 / 锁屏 / 控制器显隐 / 各类 HUD 显示开关 / 手势状态。
 * 由 PlayerController 持有，Compose 通过 StateFlow 观察。
 */
data class PlayerPanelState(
    /** 视频比例模式 */
    val aspectRatioMode: PlayerAspectRatio = PlayerAspectRatio.Automatic,
    /** 手势调节中的亮度（0..1） */
    val brightness: Double = 0.0,
    /** 是否锁定面板（锁屏后隐藏全部控件） */
    val lockPanel: Boolean = false,
    /** 视频控制器（上下控制条）是否显示 */
    val showVideoController: Boolean = true,
    /** 是否显示 seek 进度 HUD */
    val showSeekTime: Boolean = false,
    /** seek 取消挂起（拖到边缘取消） */
    val seekCancelPending: Boolean = false,
    /** 是否显示亮度 HUD */
    val showBrightness: Boolean = false,
    /** 是否显示音量 HUD */
    val showVolume: Boolean = false,
    /** 是否显示倍速 HUD */
    val showPlaySpeed: Boolean = false,
    /** 正在手势调亮度 */
    val brightnessSeeking: Boolean = false,
    /** 正在手势调音量 */
    val volumeSeeking: Boolean = false,
    /** seek 方向：1 前进 / -1 后退 / 0 无 */
    val seekDirection: Int = 0,
    /** 是否允许隐藏面板（有拖拽/菜单持有时为 false） */
    val canHidePlayerPanel: Boolean = true
) {
    fun reset(): PlayerPanelState = PlayerPanelState()
}

/**
 * 视频比例（对应原版 player_aspect_ratio.dart）
 */
enum class PlayerAspectRatio(
    val storageValue: Int,
    val label: String
) {
    Automatic(1, "自动"),
    Crop(2, "裁切填充"),
    Stretch(3, "拉伸填充"),
    Ratio4x3(4, "4:3");

    companion object {
        fun fromStorageValue(value: Int): PlayerAspectRatio =
            entries.firstOrNull { it.storageValue == value } ?: Automatic
    }
}
