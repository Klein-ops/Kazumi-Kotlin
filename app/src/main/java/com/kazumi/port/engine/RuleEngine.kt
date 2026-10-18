package com.kazumi.port.engine

import com.kazumi.port.data.model.*
import com.kazumi.port.network.BadResponseException
import com.kazumi.port.network.NetworkException
import com.kazumi.port.network.NetworkExceptionType
import com.kazumi.port.network.PluginSiteClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 规则引擎
 * 对应 Dart RuleEngine
 *
 * 调度 XPath / API 两种规则策略，处理搜索与章节查询
 */
class RuleEngine(
    private val apiStrategy: ApiRuleStrategy = ApiRuleStrategy(),
    private val xpathStrategy: XPathRuleStrategy = XPathRuleStrategy()
) {

    /**
     * 搜索
     * 对应 search()
     */
    suspend fun search(
        config: RuleExecutionConfig,
        keyword: String
    ): RuleSearchTrace {
        // 整个搜索过程（含 XPath 解析）切到 IO 线程：
        // XPathEvaluator 的解析/匹配是 CPU 密集，若在 Main dispatcher 上同步执行，
        // 规则里的特殊 XPath（@、*、. 等）可能造成长时间自旋 → 主线程卡死 ANR
        return withContext(Dispatchers.IO) {
            val request: PreparedRuleRequest = try {
                if (config.searchMode == RuleMode.API) {
                    apiStrategy.prepareRequest(
                        config.searchApiConfig.request,
                        mapOf("keyword" to keyword)
                    )
                } else {
                    xpathStrategy.prepareSearchRequest(config, keyword)
                }
            } catch (e: Exception) {
                throw SearchErrorException(config.pluginName, e)
            }

            val raw: String = try {
                executeRequest(request, config)
            } catch (e: SearchErrorException) {
                if (isCaptchaChallengeResponse(e, config)) {
                    throw CaptchaRequiredException(config.pluginName)
                }
                throw e
            }

            try {
                val parsed = if (config.searchMode == RuleMode.API) {
                    apiStrategy.parseSearch(raw, config.searchApiConfig)
                } else {
                    xpathStrategy.parseSearch(raw, config)
                }
                if (parsed.items.isEmpty()) {
                    throw NoResultException(config.pluginName)
                }
                RuleSearchTrace(
                    rawResponse = raw,
                    response = PluginSearchResponse(
                        pluginName = config.pluginName,
                        data = parsed.items
                    ),
                    matchedFragments = parsed.matchedFragments,
                    diagnostics = parsed.diagnostics
                )
            } catch (e: CaptchaRequiredException) {
                throw e
            } catch (e: NoResultException) {
                throw e
            } catch (e: Exception) {
                throw SearchErrorException(config.pluginName, e)
            }
        }
    }

    /**
     * 查询章节（播放线路）
     * 对应 queryChapters()
     */
    suspend fun queryChapters(
        config: RuleExecutionConfig,
        source: String
    ): RuleChapterTrace {
        // 同 search：整个解析过程切到 IO，避免 XPath 解析卡主线程
        return withContext(Dispatchers.IO) {
            val request: PreparedRuleRequest = try {
                if (config.chapterMode == RuleMode.API) {
                    apiStrategy.prepareRequest(
                        config.chapterApiConfig.request,
                        mapOf("source" to source)
                    )
                } else {
                    xpathStrategy.prepareChapterRequest(config, source)
                }
            } catch (e: Exception) {
                throw ChapterErrorException(config.pluginName, e)
            }

            val raw: String = try {
                executeRequest(request, config)
            } catch (e: Exception) {
                throw ChapterErrorException(config.pluginName, e)
            }

            try {
                val parsed = if (config.chapterMode == RuleMode.API) {
                    apiStrategy.parseChapters(
                        raw,
                        config.chapterApiConfig,
                        source = source,
                        baseUrl = config.baseUrl
                    )
                } else {
                    xpathStrategy.parseChapters(raw, config)
                }
                if (parsed.roads.isEmpty()) {
                    throw ChapterErrorException(config.pluginName)
                }
                RuleChapterTrace(
                    rawResponse = raw,
                    roads = parsed.roads,
                    diagnostics = parsed.diagnostics
                )
            } catch (e: ChapterErrorException) {
                throw e
            } catch (e: Exception) {
                throw ChapterErrorException(config.pluginName, e)
            }
        }
    }
    /**
     * 解析 WebView 收割的搜索 HTML（验证码验证后复用页面）
     * 对应 tryParseHarvestedSearch
     */
    fun tryParseHarvestedSearch(
        config: RuleExecutionConfig,
        raw: String
    ): PluginSearchResponse? {
        if (raw.trim().isEmpty()) return null
        if (config.searchMode == RuleMode.API || config.usePost) return null
        return try {
            val parsed = xpathStrategy.parseSearch(raw, config)
            if (parsed.items.isEmpty()) return null
            PluginSearchResponse(
                pluginName = config.pluginName,
                data = parsed.items
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun executeRequest(
        request: PreparedRuleRequest,
        config: RuleExecutionConfig
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                PluginSiteClient.executeRuleRequest(request, config)
            } catch (e: BadResponseException) {
                // 保留响应体供验证码检测
                throw SearchErrorException(config.pluginName, e)
            } catch (e: NetworkException) {
                if (e.type == NetworkExceptionType.CANCEL) throw e
                throw SearchErrorException(config.pluginName, e)
            } catch (e: Exception) {
                throw SearchErrorException(config.pluginName, e)
            }
        }
    }

    /**
     * 检测失败的搜索请求是否为验证码挑战页
     * 对应 _isCaptchaChallengeResponse
     */
    private fun isCaptchaChallengeResponse(
        error: SearchErrorException,
        config: RuleExecutionConfig
    ): Boolean {
        if (config.searchMode != RuleMode.XPATH || !config.antiCrawlerConfig.enabled) {
            return false
        }
        val cause = error.cause
        if (cause !is BadResponseException) return false

        // Cloudflare 标记的挑战响应
        val cfMitigated = cause.headers["cf-mitigated"]?.firstOrNull()
        if (cfMitigated?.lowercase() == "challenge") return true

        val raw = cause.responseBody
        if (raw.trim().isEmpty()) return false

        return try {
            xpathStrategy.detectsCaptchaChallenge(raw, config.antiCrawlerConfig)
        } catch (e: Exception) {
            false
        }
    }
}