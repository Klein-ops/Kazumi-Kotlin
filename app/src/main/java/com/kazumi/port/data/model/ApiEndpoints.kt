package com.kazumi.port.data.model

/**
 * API 端点配置
 * 对应 Dart ApiEndpoints
 */
object ApiEndpoints {
    const val VERSION = "2.3.9"
    const val API_LEVEL = 9

    /** 规则仓库 */
    const val PLUGIN_SHOP = "https://raw.githubusercontent.com/Predidit/KazumiRules/main/"
    const val PLUGIN_SHOP_MIRROR = "https://raw.gitcode.com/gh_mirrors/ka/KazumiRules/raw/main/"

    /** Bangumi 镜像 */
    const val BANGUMI_MIRROR_DOMAIN = "https://api.kazumi.fyi"
    const val BANGUMI_MIRROR_POPULAR_SUBJECTS = "/kazumi/v1/popular/subjects"

    /**
     * 【已废弃，不再使用】旧方案：把评论/搜索分流到无需签名的镜像。
     * 实测该域名（api.bgmapi.com）在本机网络完全不可达（8s 超时，裸域名 DNS 不存在），
     * 分流过去只会先等一轮超时再回退直连，比 401 更糟。
     * 现方案：移植版内置官方发布包的签名凭据（network/BangumiMirrorCredentials.kt），
     * 评论/搜索与其它接口一样走 BANGUMI_MIRROR_DOMAIN（api.kazumi.fyi），
     * 由 BangumiClient.buildHeaders 在镜像模式下附加 X-AppId/X-Timestamp/X-Signature。
     */
    const val BANGUMI_COMMENT_MIRROR_DOMAIN = "https://api.bgmapi.com"

    /** 图标作者（对应 Dart iconUrl） */
    const val ICON_URL = "https://www.pixiv.net/users/66219277"

    /** 弹弹 */
    const val DANDAN_INDEX = "https://www.dandanplay.com/"
    const val BANGUMI_INDEX = "https://bangumi.tv/"
    const val BANGUMI_API_DOMAIN = "https://api.bgm.tv"
    const val BANGUMI_AUTH_API_MIRROR_DOMAIN = "https://api.bgmapi.com"

    // Bangumi v0
    const val BANGUMI_INFO_BY_ID = "/v0/subjects/{0}"
    const val BANGUMI_RELATIONS_BY_ID = "/v0/subjects/{0}/subjects"
    const val BANGUMI_RANK_SEARCH = "/v0/search/subjects?limit={0}&offset={1}"
    const val BANGUMI_CHARACTER_BY_ID = "/v0/subjects/{0}/characters"
    const val BANGUMI_STAFF_BY_ID = "/v0/subjects/{0}/persons"
    const val BANGUMI_EPISODE_BY_ID = "/v0/episodes"
    const val BANGUMI_USERNAME_BY_TOKEN = "/v0/me"
    const val BANGUMI_SET_COLLECTION = "/v0/users/-/collections/{0}"
    const val BANGUMI_GET_ALL_COLLECTIONS = "/v0/users/{0}/collections?subject_type=2&limit={1}&offset={2}"

    // Bangumi next
    const val BANGUMI_API_NEXT_DOMAIN = "https://next.bgm.tv"
    val bangumiPublicApiHosts = setOf("api.bgm.tv", "next.bgm.tv")

    const val BANGUMI_CALENDAR = "/p1/calendar"
    const val BANGUMI_TRENDS_NEXT = "/p1/trending/subjects"
    const val BANGUMI_MIRROR_SEASON_CALENDAR = "/kazumi/v1/calendar/season"
    const val BANGUMI_INFO_BY_ID_NEXT = "/p1/subjects/{0}"
    const val BANGUMI_COMMENTS_BY_ID_NEXT = "/p1/subjects/{0}/comments?limit={1}&offset={2}"
    const val BANGUMI_EPISODE_COMMENTS_BY_ID_NEXT = "/p1/episodes/{0}/comments"
    const val BANGUMI_CHARACTER_INFO_BY_CHARACTER_ID_NEXT = "/p1/characters/{0}"
    const val BANGUMI_CHARACTER_COMMENTS_BY_ID_NEXT = "/p1/characters/{0}/comments"

    // DanDanPlay
    const val DANDAN_API_DOMAIN = "https://api.dandanplay.net"
    const val DANDAN_API_COMMENT = "/api/v2/comment/"
    const val DANDAN_API_SEARCH_EPISODES = "/api/v2/search/episodes"
    const val DANDAN_API_INFO = "/api/v2/bangumi/"
    const val DANDAN_API_INFO_BY_BGM_BANGUMI_ID = "/api/v2/bangumi/bgmtv/{0}"

    // trace.moe 图片识别番剧
    const val TRACE_API = "https://api.trace.moe/search"

    fun formatUrl(url: String, params: List<Any>): String {
        var result = url
        params.forEachIndexed { index, param ->
            result = result.replace("{$index}", param.toString())
        }
        return result
    }
}

/**
 * HTTP 头工具
 * 对应 Dart utils/http_headers.dart
 */
object HttpHeaders {
    /** 随机 UA 列表 */
    private val userAgentsList = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.1"
    )

    private val acceptLanguageList = listOf(
        "zh-CN,zh;q=0.9",
        "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
        "zh-CN,zh-TW;q=0.9,zh;q=0.8,en-US;q=0.7,en;q=0.6"
    )

    fun randomUA(): String = userAgentsList.random()

    fun randomAcceptLanguage(): String = acceptLanguageList.random()

    /** Bangumi API 文档要求的 UA */
    fun bangumiHTTPHeader(): Map<String, String> = mapOf(
        "user-agent" to "Kazumi/${ApiEndpoints.VERSION} (Android)",
        "content-type" to "application/json"
    )
}