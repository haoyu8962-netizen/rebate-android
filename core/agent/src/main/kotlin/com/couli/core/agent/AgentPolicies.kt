package com.couli.core.agent

import com.couli.core.designsystem.CouliFormat
import com.couli.core.orders.CreditPeriodText
import com.couli.core.router.RouteSource
import com.couli.core.wallet.WalletDraftTexts
import com.couli.generated.RouteSpec
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// APP-10 纯策略（向量 specs/agent-vectors.json 的 pageGuideTap、earningsView、ticket、sendErrors、streamErrors、duplicate、clientMsgId、consent）。
// 这些只返回「该怎么做」的描述，执行层（AgentController / 页面）先比票据再产生副作用（向量 gaps 第 4 条）。三端同一套（鸿蒙 AgentPolicies.ets）。

// ---------------------------------------------------------------- 页面引导卡（D31，G09、G10）

object PageGuidePolicy {
    /**
     * 点击时的判定：只有包内 agent_guide 路由、data 只含 route 与 text_key、text_key 与 route 相符时才画按钮；按钮交 Router 的永远是
     * `{route, params: {}, source: agent_card}`（brief §0 第 1 条）。被拦下且 route 满足路由名正则 → 一次 route_entry_blocked{route, in_app}。
     */
    fun tap(
        card: JsonObject,
        routes: Map<String, RouteSpec>,
    ): JsonObject {
        val check = PageGuideCheck.of(AgentJson.objectOf(card["data"]), AgentJson.intOf(card["schema_version"]) ?: 1L, routes)
        return JsonObject(
            buildMap {
                put("showButton", JsonPrimitive(check.ok))
                if (check.ok) put("route", JsonPrimitive(check.route))
                put("params", AgentJson.EMPTY)
                put("sourceRaw", JsonPrimitive(RouteSource.AGENT_CARD.raw))
                put(
                    "blockedEvent",
                    if (!check.ok && check.blockedRoute != null) {
                        JsonObject(linkedMapOf("route" to JsonPrimitive(check.blockedRoute), "entry" to JsonPrimitive(ENTRY_IN_APP)))
                    } else {
                        JsonNull
                    },
                )
            },
        )
    }

    const val ENTRY_IN_APP = "in_app"
}

// ---------------------------------------------------------------- 收益卡（D32，G11–G14）

data class EarningsButton(
    val route: String,
    val textKey: String,
)

/**
 * 收益卡的展示值（只在内存；金额隐藏时三处都是「****」，不带 ¥、位数、正负号）。toString 不带金额（G15：不进日志）。
 */
@Suppress("LongParameterList") // 收益卡展示字段逐项
class EarningsView(
    val shape: String,
    val withdrawable: String?,
    val estimatedTotal: String?,
    val creditPeriod: String?,
    val creditOverdue: Boolean,
    val latestWithdrawalShown: Boolean,
    val latestTitleKey: String?,
    val latestTitle: String?,
    val appliedAmount: String?,
    val updatedAt: String?,
    val historyTextKey: String?,
    val buttons: List<EarningsButton>,
    val fallbackText: String?,
    val masked: Boolean,
) {
    /** 向量形状：只放要展示的字段；历史形状与 fallback 不出现任何金额键（G13）。 */
    fun json(): JsonObject =
        JsonObject(
            buildMap {
                put("shapeRaw", JsonPrimitive(shape))
                put("latestWithdrawalShown", JsonPrimitive(latestWithdrawalShown))
                put(
                    "buttons",
                    JsonArray(
                        buttons.map {
                            JsonObject(
                                linkedMapOf(
                                    "route" to JsonPrimitive(it.route),
                                    "textKey" to JsonPrimitive(it.textKey),
                                ),
                            )
                        },
                    ),
                )
                when (shape) {
                    EarningsShape.LIVE -> {
                        put("withdrawable", text(withdrawable))
                        put("estimatedTotal", text(estimatedTotal))
                        put("creditPeriod", text(creditPeriod))
                        put("creditOverdue", JsonPrimitive(creditOverdue))
                        put("updatedAt", text(updatedAt))
                        if (latestWithdrawalShown) {
                            put("latestTitleKey", text(latestTitleKey))
                            put("appliedAmount", text(appliedAmount))
                        }
                    }

                    EarningsShape.HISTORY -> {
                        put("textKey", text(historyTextKey))
                    }

                    else -> {
                        put("fallbackText", text(fallbackText))
                    }
                }
            },
        )

    private fun text(v: String?): JsonElement = v?.let(::JsonPrimitive) ?: JsonNull

    override fun toString(): String = "EarningsView(shape=$shape, masked=$masked)"
}

object EarningsCardView {
    val WALLET: EarningsButton = EarningsButton(AgentCardDecoder.EARNINGS_WALLET, "agent.earnings.open_wallet")
    val RECORDS: EarningsButton = EarningsButton(AgentCardDecoder.EARNINGS_RECORDS, "agent.earnings.open_records")
    const val HISTORY_KEY = "agent.earnings.history"
    private val HHMM = Regex("T([0-9]{2}):([0-9]{2})")

    /** 向量入口：卡片外壳 + 来源 + 金额隐藏 + 当前时刻（上海年份比较用）。金额按 BR-TEXT-10 只经 DesignSystem 的 [CouliFormat.money]。 */
    fun of(
        card: JsonObject,
        origin: String,
        amountMasked: Boolean,
        nowMs: Long,
    ): JsonObject = view(AgentCardDecoder.parse(card, origin, emptyMap()), amountMasked, nowMs).json()

    /** 已解码的卡 → 展示值。只有 known 且 shape=live 才计算金额；金额隐藏时不调用格式化（不产生带数字的中间串）。 */
    @Suppress("ReturnCount", "LongMethod") // fallback、历史、实时三个出口
    fun view(
        card: AgentCard,
        amountMasked: Boolean,
        nowMs: Long,
    ): EarningsView {
        val buttons = listOf(WALLET, RECORDS)
        if (!card.known) {
            return EarningsView(
                EarningsShape.FALLBACK,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                emptyList(),
                card.fallbackText,
                amountMasked,
            )
        }
        if (card.shape != EarningsShape.LIVE) {
            return EarningsView(
                EarningsShape.HISTORY,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                HISTORY_KEY,
                buttons,
                null,
                amountMasked,
            )
        }
        val d = card.data

        fun show(fen: JsonElement?): String = if (amountMasked) AgentDraftTexts.MASK else CouliFormat.money(AgentJson.intOf(fen) ?: 0L)
        val latest = AgentJson.objectOf(d["latest_withdrawal"])
        val titleKey = latest?.let { AgentJson.stringOf(it["title_key"]) }
        val title = titleKey?.let(::titleOf)
        val shown = latest != null && title != null
        return EarningsView(
            shape = EarningsShape.LIVE,
            withdrawable = show(d["withdrawable_fen"]),
            estimatedTotal = show(d["estimated_total_fen"]),
            creditPeriod = CreditPeriodText.monthText(AgentJson.stringOf(d["next_credit_period"]), nowMs),
            creditOverdue = AgentJson.boolOf(d["credit_overdue"]) == true,
            latestWithdrawalShown = shown,
            latestTitleKey = titleKey.takeIf { shown },
            latestTitle = title,
            appliedAmount = latest?.takeIf { shown }?.let { show(it["amount_fen"]) },
            updatedAt = hhmm(AgentJson.stringOf(d["as_of"])),
            historyTextKey = null,
            buttons = buttons,
            fallbackText = null,
            masked = amountMasked,
        )
    }

    /** 提现主状态标题：只做精确键查找（契约文案 → APP-09 钱包草稿表），查不到为 null（整行不显示，G11）。 */
    fun titleOf(key: String): String? = key.takeIf(WalletDraftTexts::has)?.let(WalletDraftTexts::text)?.takeIf(String::isNotEmpty)

    /** as_of（+08:00）→ 上海 HH:mm（G12「更新于 HH:mm」）。 */
    fun hhmm(asOf: String?): String? = asOf?.let(HHMM::find)?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }
}

// ---------------------------------------------------------------- 票据（brief §0 第 3 条，G06）

object AgentTicketEvent {
    const val SESSION_INVALID = "session_invalid"
    const val LOGOUT = "logout"
    const val ACCOUNT_SWITCH = "account_switch"
    const val PRIVACY_WITHDRAWN = "privacy_withdrawn"
    const val AI_CONSENT_WITHDRAWN = "ai_consent_withdrawn"
    const val ENV_SWITCH = "env_switch"
    const val PAGE_DESTROYED = "page_destroyed"
    const val NEW_CHAT = "new_chat"
    const val STOP = "stop"
}

/** 作废事件 → 作废到哪一层、是否发 cancel。 */
data class AgentTicketAction(
    val dropController: Boolean,
    val dropConversation: Boolean,
    val dropRun: Boolean,
    val sendsCancel: Boolean,
    val clearsMessages: Boolean,
) {
    fun json(): JsonObject =
        JsonObject(
            linkedMapOf(
                "dropController" to JsonPrimitive(dropController),
                "dropConversation" to JsonPrimitive(dropConversation),
                "dropRun" to JsonPrimitive(dropRun),
                "sendsCancel" to JsonPrimitive(sendsCancel),
                "clearsMessages" to JsonPrimitive(clearsMessages),
            ),
        )
}

object AgentTicketPolicy {
    /**
     * 归属变化（会话失效、退出、换号、撤回隐私、撤回 AI 同意、切环境）与页面销毁：作废整个控制器（含其下对话与 run）、清空消息、不发 cancel
     * （cancel 会带新身份的令牌，主体对不上，G06）；【新对话】：作废对话与 run、清空消息，不换控制器、不发 cancel；【停止】：只作废 run、
     * 保留内容，发 cancel（带 run 票据里的原身份）。
     */
    fun action(eventRaw: String): AgentTicketAction =
        when (eventRaw) {
            AgentTicketEvent.NEW_CHAT -> AgentTicketAction(false, true, true, false, true)
            AgentTicketEvent.STOP -> AgentTicketAction(false, false, true, true, false)
            else -> AgentTicketAction(true, true, true, false, true)
        }

    fun onEvent(eventRaw: String): JsonObject = action(eventRaw).json()

    /** 票据比较：控制器编号、对话世代、run 票据任一不同（或控制器已作废）→ 丢弃。 */
    fun accept(
        issued: JsonObject,
        current: JsonObject?,
    ): Boolean = current != null && TICKET_KEYS.all { issued[it] != null && issued[it] == current[it] }

    private val TICKET_KEYS = listOf("controller", "conversation", "run")
}

// ---------------------------------------------------------------- 错误（G24、G26）

object AgentSendAction {
    const val UNAVAILABLE = "unavailable"
    const val QUOTA = "quota"
    const val NEW_CHAT = "new_chat"
    const val RUN_IN_PROGRESS = "run_in_progress"
    const val RATE_LIMITED = "rate_limited"
    const val RETRY_SAME_MESSAGE = "retry_same_message"
    const val AGENT_CONSENT = "agent_consent"
    const val UNIFIED_CONSENT = "unified_consent"
    const val UNIFIED_ERROR = "unified_error"
    const val FORCE_UPDATE = "force_update"
    const val TEXT_TOO_LONG = "text_too_long"
}

data class AgentSendDecision(
    val action: String,
    val disableMs: Long = 0,
    val reuseClientMsgId: Boolean = false,
    val trace6: String? = null,
) {
    fun json(): JsonObject =
        JsonObject(
            buildMap {
                put("actionRaw", JsonPrimitive(action))
                if (disableMs > 0) put("disableMs", JsonPrimitive(disableMs))
                put("reuseClientMsgId", JsonPrimitive(reuseClientMsgId))
                put("trace6", trace6?.let(::JsonPrimitive) ?: JsonNull)
            },
        )
}

/** 错误码是契约取值（contracts/error-codes.json），按码列表直接写。 */
@Suppress("MagicNumber")
object AgentErrorCodes {
    const val CONSENT = 10004
    const val LOGIN = 10001
    const val BIND_PHONE = 10005
    const val FORCE_UPDATE = 10405
    const val TEXT_INVALID = 20001
    const val UNAVAILABLE = 30501
    const val QUOTA = 30502
    const val ROUND_LIMIT = 30504
    const val RUN_MISSING = 30505
    const val RUN_IN_PROGRESS = 30506
    const val RATE_LIMITED = 42901
    const val GENERIC = 50001
    const val SEARCH_FALLBACK = 50302
    const val TIMEOUT = 50401
    const val SERVER_MIN = 50000
    const val SERVER_MAX = 59999
    const val CONSENT_AI = "ai_third_party"

    fun server(code: Int): Boolean = code in SERVER_MIN..SERVER_MAX
}

object AgentSendErrorPolicy {
    const val DEFAULT_DISABLE_MS = 5_000L
    private const val MS_PER_SECOND = 1_000L
    private val SECONDS = Regex("^[0-9]{1,6}$")
    private const val TRACE_TAIL = 6

    @Suppress("LongParameterList") // 与向量入口同形
    fun of(
        http: Int,
        code: Int,
        data: JsonObject?,
        retryAfter: String?,
        traceId: String?,
        consentType: String?,
    ): JsonObject = decide(http, code, data, retryAfter, traceId, consentType).json()

    /** Retry-After（秒，正整数）→ 毫秒；缺省、非法、非正数按 5 秒（日期形式、小数未定，向量 gaps G24）。 */
    fun retryAfterMs(raw: String?): Long {
        val s = raw?.trim()?.takeIf(SECONDS::matches)?.toLongOrNull() ?: return DEFAULT_DISABLE_MS
        return if (s > 0) s * MS_PER_SECOND else DEFAULT_DISABLE_MS
    }

    fun trace6(traceId: String?): String? = traceId?.takeIf(String::isNotEmpty)?.takeLast(TRACE_TAIL)

    @Suppress("UnusedParameter", "CyclomaticComplexMethod", "LongParameterList") // http 只为与向量入口同形；按码逐条
    fun decide(
        http: Int,
        code: Int,
        data: JsonObject?,
        retryAfter: String?,
        traceId: String?,
        consentType: String?,
    ): AgentSendDecision =
        when (code) {
            AgentErrorCodes.UNAVAILABLE -> {
                AgentSendDecision(AgentSendAction.UNAVAILABLE)
            }

            AgentErrorCodes.QUOTA -> {
                AgentSendDecision(AgentSendAction.QUOTA)
            }

            AgentErrorCodes.ROUND_LIMIT -> {
                AgentSendDecision(AgentSendAction.NEW_CHAT)
            }

            AgentErrorCodes.RUN_IN_PROGRESS -> {
                AgentSendDecision(AgentSendAction.RUN_IN_PROGRESS, DEFAULT_DISABLE_MS)
            }

            AgentErrorCodes.RATE_LIMITED -> {
                AgentSendDecision(AgentSendAction.RATE_LIMITED, retryAfterMs(retryAfter))
            }

            AgentErrorCodes.TIMEOUT -> {
                AgentSendDecision(AgentSendAction.RETRY_SAME_MESSAGE, 0, true, trace6(traceId))
            }

            AgentErrorCodes.GENERIC -> {
                AgentSendDecision(AgentSendAction.NEW_CHAT, 0, false, trace6(traceId))
            }

            AgentErrorCodes.CONSENT -> {
                if (consentType == AgentErrorCodes.CONSENT_AI) {
                    AgentSendDecision(AgentSendAction.AGENT_CONSENT, 0, true)
                } else {
                    AgentSendDecision(AgentSendAction.UNIFIED_CONSENT)
                }
            }

            AgentErrorCodes.FORCE_UPDATE -> {
                AgentSendDecision(AgentSendAction.FORCE_UPDATE)
            }

            AgentErrorCodes.TEXT_INVALID -> {
                val fields = AgentJson.arrayOf(data?.get("fields")).orEmpty().map { AgentJson.stringOf(it) }
                AgentSendDecision(if (FIELD_TEXT in fields) AgentSendAction.TEXT_TOO_LONG else AgentSendAction.UNIFIED_ERROR)
            }

            else -> {
                AgentSendDecision(AgentSendAction.UNIFIED_ERROR, 0, false, if (AgentErrorCodes.server(code)) trace6(traceId) else null)
            }
        }

    private const val FIELD_TEXT = "text"
}

/** 流内 error 帧的展示（[retry]：new_message = 【重试】按新消息、新 client_msg_id（04 §8.1 同 ID 只重放原终止帧）；none = 不给重试）。 */
data class AgentStreamErrorView(
    val retry: String,
    val searchButton: Boolean,
    val prefillQ: String?,
    val textKey: String,
    val code: Int,
) {
    fun json(): JsonObject =
        JsonObject(
            linkedMapOf(
                "retryRaw" to JsonPrimitive(retry),
                "searchButton" to JsonPrimitive(searchButton),
                "prefillQ" to (prefillQ?.let(::JsonPrimitive) ?: JsonNull),
                "textKey" to JsonPrimitive(textKey),
            ),
        )

    companion object {
        const val RETRY_NEW = "new_message"
        const val RETRY_NONE = "none"
    }
}

object AgentStreamErrorPolicy {
    const val SEARCH_Q_MAX = 100

    fun of(data: JsonObject): JsonObject = view(data).json()

    fun view(data: JsonObject): AgentStreamErrorView {
        val code = AgentJson.intOf(data["code"])?.toInt() ?: AgentErrorCodes.GENERIC
        val search = AgentJson.stringOf(data["fallback"]) == SEARCH_PAGE
        val q = AgentJson.stringOf(data["fallback_q"])
        val prefill = q?.takeIf { search && AgentJson.codePoints(it) <= SEARCH_Q_MAX }
        val retry = if (AgentJson.boolOf(data["retryable"]) == true) AgentStreamErrorView.RETRY_NEW else AgentStreamErrorView.RETRY_NONE
        return AgentStreamErrorView(retry, search, prefill, "error.$code", code)
    }

    private const val SEARCH_PAGE = "search_page"
}

object AgentDuplicatePolicy {
    /** duplicate 流的终止帧 → 本地怎么收尾（G26）：done 结束；error 30506 结束 loading、给同键【重试】；其他 error 按流内 error。 */
    fun of(terminal: JsonObject): JsonObject {
        val data = AgentJson.objectOf(terminal["data"])
        return JsonObject(
            when {
                AgentJson.stringOf(terminal["event"]) == AgentEvent.DONE -> {
                    mapOf("localEndRaw" to JsonPrimitive("done"))
                }

                AgentJson.intOf(data?.get("code")) == AgentErrorCodes.RUN_IN_PROGRESS.toLong() -> {
                    linkedMapOf("localEndRaw" to JsonPrimitive("error_30506"), "retryReusesClientMsgId" to JsonPrimitive(true))
                }

                else -> {
                    linkedMapOf("localEndRaw" to JsonPrimitive("error"), "retryReusesClientMsgId" to JsonPrimitive(false))
                }
            },
        )
    }
}

// ---------------------------------------------------------------- client_msg_id（BR-AI-23，G02、G24、G26）

object ClientMsgId {
    val PATTERN: Regex = Regex("^[A-Za-z0-9_-]{8,64}$")
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val RANDOM_CHARS = 12
    private const val RADIX = 36
    private val counter = AtomicLong(0)
    private val random = SecureRandom()

    /** 新消息：时刻 + 进程内计数 + 随机，满足 `^[A-Za-z0-9_-]{8,64}$`。只在内存，不落盘。 */
    fun new(): String {
        val rand = (1..RANDOM_CHARS).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        return "m" + System.currentTimeMillis().toString(RADIX) + "_" + counter.incrementAndGet().toString(RADIX) + "_" + rand
    }

    /**
     * 重发：50401、断线【重试】、同意后重放、duplicate 30506 的【重试】沿用原 ID（服务端按 BR-AI-23 ⑥ 去重）；流内 error 后的人工重试与新消息
     * 用新 ID（04 §8.1：同 ID 只重放原终止帧）。
     */
    fun forRetry(
        situationRaw: String,
        previous: String,
    ): String = if (situationRaw in SAME_ID) previous else new()

    const val RETRY_50401 = "retry_50401"
    const val RETRY_DISCONNECTED = "retry_disconnected"
    const val REPLAY_AFTER_CONSENT = "replay_after_consent"
    const val RETRY_DUPLICATE_30506 = "retry_duplicate_30506"
    private val SAME_ID = setOf(RETRY_50401, RETRY_DISCONNECTED, REPLAY_AFTER_CONSENT, RETRY_DUPLICATE_30506)
}

// ---------------------------------------------------------------- AgentConsent（BR-ID-14、BR-AI-13，G16、G17）

object AgentConsentPolicy {
    /** 只在 10004 + consent_type=ai_third_party 时弹（客户端不自行判断主体或版本）；同意后重放原请求一次。 */
    @Suppress("UnusedParameter") // http 只为与向量入口同形
    fun of(
        http: Int,
        code: Int,
        consentType: String?,
    ): JsonObject {
        val show = shows(code, consentType)
        return JsonObject(linkedMapOf("showSheet" to JsonPrimitive(show), "replayCount" to JsonPrimitive(if (show) 1 else 0)))
    }

    fun shows(
        code: Int,
        consentType: String?,
    ): Boolean = code == AgentErrorCodes.CONSENT && consentType == AgentErrorCodes.CONSENT_AI
}
