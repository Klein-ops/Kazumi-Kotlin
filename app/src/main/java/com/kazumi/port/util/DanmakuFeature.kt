package com.kazumi.port.util

/**
 * 弹幕功能总开关。
 *
 * 当前状态（2026-10-19）：**开启**。默认弹幕源为 Bilibili（免鉴权）。
 *
 * 各源现状：
 *  - BiliBili：番剧名搜索 → season → cid → 弹幕 XML。无需 AppId / cookie，实测可用，默认开。
 *  - 弹弹play：开放 API v2 已强制鉴权（无头 403 Missing Authentication Headers，
 *    AppId 无效 403 Invalid AppId）。只有用户在设置里填了自己的 AppId/AppSecret
 *    并打开「弹弹play」开关时才会被尝试。
 *  - Gamer（巴哈）：接口在当前网络不可达，仅保留开关。
 *
 * 设为 false 时的效果：播放页/设置页不再显示弹幕相关入口，播放也不发起弹幕请求
 * （底层实现全部保留，改回 true 即可恢复）。
 */
object DanmakuFeature {
    const val ENABLED: Boolean = true
}
