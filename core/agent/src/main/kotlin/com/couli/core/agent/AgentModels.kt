package com.couli.core.agent

import com.couli.core.network.CallEpochs
import com.couli.core.router.RouteSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 控制器构造时固定的身份（brief §0 第 3 条；APP-09 第 63、65 条）：环境 + 身份档（basic / guest / member）+ 账号 + 登录世代 + 隐私 / 环境世代 +
 * AI 同意世代 + 页面实例。不含凭据；之后只用来**比较**（是否仍等于此刻的身份），任何地方都不读当前身份来赋值或绑定。
 */
data class AgentIdentity(
    val env: String,
    val level: String,
    val account: String?,
    val loginEpoch: Long,
    val epochs: CallEpochs,
    /** AI 服务同意世代（设置页撤回成功 +1）。 */
    val aiConsent: Long,
    /** 页面实例编号（Agent 页真正销毁后 +1；配置重建不算）。 */
    val page: Long,
) {
    /** 归属键（不含页面实例）：换号、撤回、切环境、撤回 AI 同意才变（情境入口的交接按它核对，鸿蒙 R1-04）。 */
    val ownerKey: AgentIdentity get() = copy(page = 0)

    /** 基本模式（未同意当前隐私版本）：不发任何 /v1/agent/… 请求（BR-ID-11、BR-AI-13）。 */
    val basic: Boolean get() = level == BASIC

    val member: Boolean get() = level == MEMBER

    /** 不带账号的描述（不进日志的东西不放进 toString）。 */
    override fun toString(): String = "AgentIdentity(level=$level, page=$page)"

    companion object {
        const val BASIC = "basic"
        const val GUEST = "guest"
        const val MEMBER = "member"
    }
}

/** 一次 run 的票据（发起时生成，不可变）。 */
data class AgentRunTicket(
    val controller: Long,
    val conversation: Long,
    val run: String,
    val clientMsgId: String,
) {
    fun json(): JsonObject =
        JsonObject(
            linkedMapOf(
                "controller" to JsonPrimitive(controller),
                "conversation" to JsonPrimitive(conversation),
                "run" to JsonPrimitive(run),
            ),
        )
}

/** 页面级动作（发送、新对话、重新同意、重试加载）的渲染时票据。 */
data class AgentPageTicket(
    val controller: Long,
    val conversation: Long,
)

object AgentTapKind {
    const val GUIDE = "guide"
    const val EARNINGS = "earnings"
    const val RULE = "rule"
    const val ORDER = "order"
    const val PRODUCT = "product"
    const val AUTH = "auth"
    const val HANDOFF = "handoff"
    const val SEARCH = "search"
    const val LOGIN = "login"
    const val BIND_PHONE = "bind_phone"
}

/**
 * 卡片按钮的渲染时票据：{run 票据, card_id, route, params, 卡片动作世代}（APP-09 第 63 条：执行时只比较，不读当前控制器或消息列表）。
 * [label]：读屏完整名称（如「去看看，提现记录」）；[text]：按钮上显示的文字。
 */
data class AgentTap(
    val controller: Long,
    val conversation: Long,
    val run: String,
    val cardId: String,
    val kind: String,
    val route: String,
    val params: JsonObject,
    val source: RouteSource,
    val label: String,
    val text: String = label,
    val resumeLinkId: String? = null,
    val epoch: Long = 0,
)

/** 购买按钮的渲染时票据：{run 票据, card_id, link_id, 渲染时的卡片快照, 卡片动作世代}。toString 不带卡片（不进日志）。 */
@Suppress("LongParameterList") // 渲染时票据逐项
class AgentBuyTap(
    val controller: Long,
    val conversation: Long,
    val run: String,
    val cardId: String,
    val itemCardId: String,
    val linkId: String,
    val product: JsonObject,
    val epoch: Long,
) {
    /** 这一次点击所属逻辑卡片的不可变引用（Android R2-01）。 */
    val ref: AgentProductRef get() = AgentProductRef(controller, conversation, run, cardId, itemCardId, linkId, epoch)

    override fun toString(): String = "AgentBuyTap(card=$cardId#$itemCardId)"
}

/**
 * 快捷追问（suggestions、notice 的 actions、「换一批」、示例问题、「查订单」）：{对话世代, send_text}。卡片上的胶囊带卡片动作世代 [epoch]，
 * 对话级胶囊（追问、示例、查订单）为 -1，不比较（鸿蒙 R1-03）。
 */
data class AgentChipTap(
    val controller: Long,
    val conversation: Long,
    val text: String,
    val sendText: String,
    val epoch: Long = -1,
)

object AgentRetryMode {
    /** 沿用同一 client_msg_id（50401、断线、duplicate 30506）。 */
    const val SAME = "same"

    /** 新消息、新 client_msg_id（流内 error 后的人工重试）。 */
    const val NEW = "new"
}

/** 【重试】：{run 票据, client_msg_id}。 */
data class AgentRetryTap(
    val controller: Long,
    val conversation: Long,
    val run: String,
    val clientMsgId: String,
    val mode: String,
) {
    constructor(run: AgentRunTicket, mode: String) : this(run.controller, run.conversation, run.run, run.clientMsgId, mode)
}

/** 同意弹窗：{控制器编号, 弹窗编号, 待重放请求}（进入页面时的 10004 没有待重放请求）。 */
data class AgentConsentTicket(
    val controller: Long,
    val serial: Long,
    val pendingRun: String?,
)

/**
 * 购买所属的逻辑卡片（不可变；只替换这一张卡：controller + conversation + run + 外层 card_id + 商品 card_id + 点击时的 link_id + 卡片动作世代）。
 * card_id 只在一轮之内唯一，跨轮可以重复（Android R2-01，共性第 34 条）：认「同一张卡」一律比较整个引用，不能只比 card_id。
 */
data class AgentProductRef(
    val controller: Long,
    val conversation: Long,
    val run: String,
    val cardId: String,
    val itemCardId: String,
    val linkId: String,
    val epoch: Long,
)

/** 一件商品的展示：渲染时的商品 JSON + 详情票据 + 购买票据 + 「换一批」（30141 下架、coupon_gone，G25）。 */
class AgentProductView(
    val json: JsonObject,
    val detail: AgentTap?,
    val buy: AgentBuyTap?,
    val refresh: AgentChipTap?,
    val couponGone: Boolean,
) {
    val itemCardId: String get() = AgentJson.stringOf(json["card_id"]).orEmpty()

    override fun toString(): String = "AgentProductView($itemCardId)"
}

/** 一张卡的展示（视图只展示；所有可点的东西都带票据；文字已在 Core 取好）。 */
@Suppress("LongParameterList") // 卡片展示字段逐项
class AgentCardView(
    val card: AgentCard,
    val layout: String = LAYOUT_LIST,
    val products: List<AgentProductView> = emptyList(),
    val guideText: String = "",
    val guide: AgentTap? = null,
    val earnings: EarningsView? = null,
    val earningsTaps: List<AgentTap> = emptyList(),
    val rule: AgentTap? = null,
    val order: AgentTap? = null,
    val auth: AgentTap? = null,
    val handoff: AgentTap? = null,
    val chips: List<AgentChipTap> = emptyList(),
    val title: String = "",
    val lines: List<String> = emptyList(),
    val amountLabel: String = "",
    /** 订单卡的预估返（受金额隐藏约束，与订单页同一偏好，G14）；没有为 null。 */
    val amountText: String? = null,
) {
    override fun toString(): String = "AgentCardView(${card.type}#${card.cardId})"

    companion object {
        const val LAYOUT_LIST = "list"
        const val LAYOUT_GRID = "grid"
        const val LAYOUT_SINGLE = "single"
    }
}

object AgentTurnPhase {
    const val CONNECTING = "connecting"
    const val STREAMING = "streaming"
    const val ENDED = "ended"

    /** JSON 错误（发送失败）。 */
    const val FAILED = "failed"
    const val AWAITING_CONSENT = "awaiting_consent"
}

/** 一条回复的错误区：统一文案（5xxxx 带 trace6）+ 【重试】/【新对话】/【去搜索】/【登录】【绑定手机号】。 */
data class AgentErrorView(
    val text: String,
    val retry: AgentRetryTap? = null,
    val newChat: AgentPageTicket? = null,
    val search: AgentTap? = null,
    val searchHint: String = "",
    val upgrade: AgentTap? = null,
    val quotaHint: String = "",
)

/** 一轮对话（用户这一条 + assistant 回复）的展示。toString 不带对话内容。 */
@Suppress("LongParameterList") // 一轮对话展示字段逐项
class AgentTurnView(
    val key: String,
    val run: AgentRunTicket,
    val userText: String,
    val phase: String,
    val status: String,
    val blocks: List<AgentTextBlock>,
    val tools: List<AgentTool>,
    val cards: List<AgentCardView>,
    val chips: List<AgentChipTap>,
    /** 已结束的 assistant 回复底部 AiLabel（meta.ai_label，缺则 ai_label 键；error 收尾也有，鸿蒙 R1-08）。 */
    val aiLabel: String?,
    val error: AgentErrorView?,
    val streaming: Boolean,
) {
    override fun toString(): String = "AgentTurnView($key, $phase, $status)"
}

object AgentPhase {
    /** Release / 无接口 / 30501：「未开放」（不显示输入框、不弹同意框、不发请求，G01）。 */
    const val UNAVAILABLE = "unavailable"

    /** 基本模式：不发请求（EntryGuard 已拦 AgentChat，这里只防 Tab 根页被挂载时发请求）。 */
    const val BLOCKED = "blocked"
    const val IDLE = "idle"
    const val LOADING = "loading"
    const val READY = "ready"
    const val LOAD_FAILED = "load_failed"

    /** 【暂不使用】、同意记录失败、迟到的 AI 10004 之后：说明 +【重新同意】（G17）。 */
    const val CONSENT_DECLINED = "consent_declined"
}

/**
 * AI 同意的发送闸门（BR-ID-14、BR-AI-13；APP-10 共性第 1、16、22 条，S0）：待同意 → 提交中 → 已记录。只有 recordConsent 成功才回到 clear；
 * 提交中、记录失败、暂不使用、关闭弹窗、任何一次 AI 10004（HTTP 与 SSE 一视同仁）都禁发。发送、快捷追问、示例、【重试】、自动重放与实际发包
 * （[AgentCall.sendAllowed]）统一核对它。
 */
object AgentConsentGate {
    const val CLEAR = "clear"

    /** 收到 10004(ai_third_party)，等用户在弹窗里选择。 */
    const val AWAITING = "awaiting"

    /** 已点【同意并使用】，recordConsent 还没有结果。 */
    const val SUBMITTING = "submitting"

    /** 暂不使用、关闭弹窗、记录失败、流内 10004：仍禁发，【重新同意】再弹。 */
    const val REFUSED = "refused"
}

/** 四个待定 operation 共用错误规则的结论（[AgentController] 内部）。 */
internal object AgentSharedError {
    const val RATE_LIMITED = "rate_limited"
    const val FORCE_UPDATE = "force_update"
    const val CONSENT = "consent"
    const val UNIFIED = "unified"
    const val UNAVAILABLE = "unavailable"
    const val SILENT = "silent"
    const val OTHER = "other"
}

/** 页面快照（不可变）。toString 不带对话内容与金额。 */
data class AgentSnapshot(
    val owner: Long = 0,
    val conversation: Long = 0,
    val phase: String = AgentPhase.IDLE,
    val turns: List<AgentTurnView> = emptyList(),
    /** 发送中（发送按钮与胶囊禁用并显示【停止】）。 */
    val sending: Boolean = false,
    /** 【停止】的票据（当前活动 run）。 */
    val stop: AgentRunTicket? = null,
    /** 42901 / 30506 禁用期（Retry-After，缺省 5 秒）。 */
    val limited: Boolean = false,
    /** 30502：今日额度用完（输入框禁用，以服务端为准）。 */
    val quotaExhausted: Boolean = false,
    /** 最近一次非 duplicate 流的 done.quota_left（只在内存，G28；设计稿没有位置，不显示）。 */
    val quotaLeft: Long? = null,
    val consent: AgentConsentTicket? = null,
    val consentGate: String = AgentConsentGate.CLEAR,
    /** 同意记录失败（仍禁发；显示失败说明与【重新同意】）。 */
    val consentFailed: Boolean = false,
    val page: AgentPageTicket = AgentPageTicket(0, 0),
    val examples: List<AgentChipTap> = emptyList(),
    val ordersChip: AgentChipTap? = null,
    val filingText: String = "",
    /** 情境入口的提示文案键（G27：附在用户发的第一条上，不自动发送）。 */
    val contextLabelKey: String? = null,
    val loadErrorText: String = "",
    /** 数据变化的版本号（自动滚动与读屏播报用）。 */
    val revision: Long = 0,
    /** 最近一条结束的回复的纯文字（读屏开启时用无障碍播报；不含卡片与金额）。 */
    val announcement: String = "",
    /** 金额隐藏偏好此刻的值（收益卡、订单卡已按它取好展示值；页面只用于读屏说明）。 */
    val amountMasked: Boolean = false,
) {
    /** 发送与胶囊此刻可用（限流期、AI 同意没记录成功、额度用完、发送中都不可用；执行层另有同一闸门）。 */
    val canCompose: Boolean
        get() = phase == AgentPhase.READY && !sending && !limited && !quotaExhausted && consentGate == AgentConsentGate.CLEAR

    override fun toString(): String = "AgentSnapshot(owner=$owner, conversation=$conversation, phase=$phase, turns=${turns.size})"
}
