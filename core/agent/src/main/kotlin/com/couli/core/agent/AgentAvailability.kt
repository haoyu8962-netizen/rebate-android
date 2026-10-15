package com.couli.core.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// APP-10b §2 第 11、12 条（F-X-04）：30502 的 data.next 与 Release 开放条件。三端同名（鸿蒙 AgentAvailability.ets）。
// - AgentQuotaPolicy（CT-LOCK-01 G08、G27）：next=login →【登录】；bind_phone →【绑定手机号】；none → 只有【去搜索】+「明天再来」；缺失或不认识 →
//   回落 APP-10 H23 / A14 的本地身份规则（游客 → 登录；会员未绑手机 → 绑定；已绑 → 只有搜索）。reset_at 不显示；输入框禁用。
// - AgentAvailability（G32–G34，编排者补充第 4 条）：Release 接真实端口（与构建类型无关）；Debug / Staging 只有「假服务打开 + 环境允许调试假数据」
//   （DebugMockGate 同一道）时由传输入口换成本机假服务。「未开放」只在 agent.entry.visible=false、config.agent.enabled=false、本次进入收到 30501 时显示，
//   都不发 agent 请求；entry.visible=false 时底部 Tab 换成「搜索」（首页 / 详情 / 订单详情的「问 AI」入口随之隐藏，G56）。基本模式、未同意当前隐私版本、
//   会员但账号未知不发任何 /v1/agent/*。planned 不作本地拒绝依据（G34）：404 / 无外壳按「加载失败」或「连接中断」。AI 同意版本与法务文案未定稿（G33）：
//   代码可开放，提审不允许（N-38）。

object AgentQuotaNext {
    const val LOGIN = "login"
    const val BIND_PHONE = "bind_phone"
    const val NONE = "none"
}

object AgentQuotaPolicy {
    const val IDENTITY_GUEST = "guest"
    const val IDENTITY_UNBOUND = "member_unbound"
    const val IDENTITY_BOUND = "member_bound"
    const val ROUTE_LOGIN = "Login"
    const val ROUTE_BIND_PHONE = "BindPhone"
    const val ROUTE_SEARCH = "Search"

    /** 额度页的按钮（路由名；Search 总在最后）。[identity]：guest / member_unbound / member_bound（未知手机按未绑）。 */
    fun buttons(
        next: String?,
        identity: String,
    ): List<String> =
        when (next) {
            AgentQuotaNext.LOGIN -> {
                listOf(ROUTE_LOGIN, ROUTE_SEARCH)
            }

            AgentQuotaNext.BIND_PHONE -> {
                listOf(ROUTE_BIND_PHONE, ROUTE_SEARCH)
            }

            AgentQuotaNext.NONE -> {
                listOf(ROUTE_SEARCH)
            }

            else -> {
                when (identity) {
                    IDENTITY_GUEST -> listOf(ROUTE_LOGIN, ROUTE_SEARCH)
                    IDENTITY_BOUND -> listOf(ROUTE_SEARCH)
                    else -> listOf(ROUTE_BIND_PHONE, ROUTE_SEARCH)
                }
            }
        }

    @Suppress("UnusedParameter") // nowMs 与向量入口同形
    fun of(
        input: JsonObject,
        nowMs: Long,
    ): JsonObject {
        val buttons = buttons(AgentJson.stringOf(input["nextRaw"]), AgentJson.stringOf(input["identityRaw"]) ?: IDENTITY_GUEST)
        return AgentJson.obj(
            "buttons" to buttons,
            "navigationRaw" to "in_app",
            "showTomorrow" to (buttons.size == 1),
            "showResetAt" to false,
            "inputEnabled" to false,
        )
    }
}

object AgentPageState {
    const val READY = "ready"
    const val UNAVAILABLE = "unavailable"
    const val CONSENT_REQUIRED = "consent_required"
    const val BLOCKED = "blocked"
    const val LOAD_FAILED = "load_failed"
    const val DISCONNECTED = "disconnected"
}

/** 一次进入的可用性判定的输入（App 层从 Config、构建、环境、身份取一次）。 */
data class AgentAvailabilityInput(
    val build: String = BUILD_RELEASE,
    val environment: String = ENV_PROD,
    val mockRequested: Boolean = false,
    val entryVisible: Boolean = true,
    val agentEnabled: Boolean = true,
    val received30501: Boolean = false,
    val basicMode: Boolean = false,
    val privacyCurrent: Boolean = true,
    val memberUnknown: Boolean = false,
    val aiConsentGranted: Boolean = true,
) {
    companion object {
        const val BUILD_RELEASE = "release"
        const val BUILD_DEBUG = "debug"
        const val BUILD_STAGING = "staging"
        const val ENV_PROD = "prod"
    }
}

data class AgentAvailabilityResult(
    val port: String,
    val pageState: String,
    val sendsRequests: Boolean,
    val settingsRowVisible: Boolean,
    val tab: String,
)

object AgentAvailability {
    const val PORT_FAKE = "fake"
    const val PORT_REAL = "real"
    const val TAB_AGENT = "agent"
    const val TAB_SEARCH = "search"

    /** Debug / Staging 构建 + 假服务打开 + 环境允许调试假数据（prod 不允许，DebugMockGate MOCK_DATA）。 */
    fun fakeAllowed(
        build: String,
        environment: String,
        mockRequested: Boolean,
    ): Boolean =
        (build == AgentAvailabilityInput.BUILD_DEBUG || build == AgentAvailabilityInput.BUILD_STAGING) && mockRequested &&
            environment != AgentAvailabilityInput.ENV_PROD

    /** 「未开放」（不发任何 agent 请求）：入口开关关、agent.enabled 关、本次进入收到 30501。 */
    fun unavailable(
        entryVisible: Boolean,
        agentEnabled: Boolean,
        received30501: Boolean,
    ): Boolean = !entryVisible || !agentEnabled || received30501

    fun decide(i: AgentAvailabilityInput): AgentAvailabilityResult {
        val port = if (fakeAllowed(i.build, i.environment, i.mockRequested)) PORT_FAKE else PORT_REAL
        val tab = if (i.entryVisible) TAB_AGENT else TAB_SEARCH
        return when {
            unavailable(
                i.entryVisible,
                i.agentEnabled,
                i.received30501,
            ) -> AgentAvailabilityResult(port, AgentPageState.UNAVAILABLE, false, false, tab)

            i.basicMode -> AgentAvailabilityResult(port, AgentPageState.BLOCKED, false, false, tab)

            !i.privacyCurrent || i.memberUnknown -> AgentAvailabilityResult(port, AgentPageState.BLOCKED, false, true, tab)

            // 同意闸门锁住：不发（由服务端 10004 触发同意框之后）
            !i.aiConsentGranted -> AgentAvailabilityResult(port, AgentPageState.CONSENT_REQUIRED, false, true, tab)

            else -> AgentAvailabilityResult(port, AgentPageState.READY, true, true, tab)
        }
    }

    /** 向量接线（availability 组）。 */
    @Suppress("UnusedParameter") // nowMs 与向量入口同形
    fun of(
        input: JsonObject,
        nowMs: Long,
    ): JsonObject {
        if (AgentJson.stringOf(input["modeRaw"]) == MODE_CONSENT_LOOP) {
            // G33：recordConsent 成功后服务端仍连续回 AI 10004：保持禁发、不循环弹框，显示「同意没有保存成功」
            val n = AgentJson.intOf(input["successfulConsentThenAi10004Count"]) ?: 0L
            return AgentJson.obj(
                "sendsRequests" to false,
                "lockAiGate" to true,
                "automaticConsentDialogs" to 0,
                "message" to if (n >= CONSENT_LOOP_LIMIT) AgentDraftTexts.text(NOT_SAVED_KEY) else "",
            )
        }
        val i =
            AgentAvailabilityInput(
                build = AgentJson.stringOf(input["buildRaw"]) ?: AgentAvailabilityInput.BUILD_RELEASE,
                environment = AgentJson.stringOf(input["environmentRaw"]) ?: AgentAvailabilityInput.ENV_PROD,
                mockRequested = AgentJson.boolOf(input["mockRequested"]) == true,
                entryVisible = AgentJson.boolOf(input["entryVisible"]) != false,
                agentEnabled = AgentJson.boolOf(input["agentEnabled"]) != false,
                // 30501 只锁本次进入：重新进入（新的页面实例与控制器）重新判断
                received30501 = AgentJson.boolOf(input["received30501"]) == true && AgentJson.boolOf(input["newEntry"]) != true,
                basicMode = AgentJson.boolOf(input["basicMode"]) == true,
                privacyCurrent = AgentJson.boolOf(input["privacyCurrent"]) != false,
                memberUnknown = AgentJson.stringOf(input["identityRaw"]) == AgentEntryPlan.MEMBER_UNKNOWN,
                aiConsentGranted = AgentJson.boolOf(input["aiConsentGranted"]) != false,
            )
        val r = decide(i)
        val o = linkedMapOf<String, JsonElement>()
        o["portRaw"] = JsonPrimitive(r.port)
        o["pageStateRaw"] = JsonPrimitive(r.pageState)
        o["sendsRequests"] = JsonPrimitive(r.sendsRequests)
        o["settingsRowVisible"] = JsonPrimitive(r.settingsRowVisible)
        o["tabRaw"] = JsonPrimitive(r.tab)
        if (AgentJson.intOf(input["responseHttp"]) != null && AgentJson.boolOf(input["hasEnvelope"]) == false) {
            // planned 期间真实后端回 404 / 网关页：没有统一外壳（G34）。进入页面 → 加载失败 +【重试】；发送 → 连接中断、同键【重试】
            if (AgentJson.stringOf(input["phaseRaw"]) == "entry") {
                o["pageStateRaw"] = JsonPrimitive(AgentPageState.LOAD_FAILED)
                o["retryVisible"] = JsonPrimitive(true)
            } else {
                o["pageStateRaw"] = JsonPrimitive(AgentPageState.DISCONNECTED)
                o["reuseClientMsgId"] = JsonPrimitive(true)
            }
        }
        // G33：AI 同意版本键与法务定稿缺失——Release 代码路径可开放，提审 / 对外发布不允许（上线检查单 N-38）
        o["releaseSubmissionAllowed"] =
            JsonPrimitive(
                AgentJson.boolOf(input["aiConsentVersionPublished"]) == true && AgentJson.boolOf(input["legalTextApproved"]) == true,
            )
        o["needs"] = JsonArray(listOf(JsonPrimitive("N-38")))
        return JsonObject(o)
    }

    private const val MODE_CONSENT_LOOP = "consent_loop"
    private const val CONSENT_LOOP_LIMIT = 2L
    const val NOT_SAVED_KEY = "agent.draft.consent.not_saved"
}

object AgentSyncAction {
    /** 控制器照旧（身份键与配置可用性都没变）。 */
    const val KEEP = "keep"

    /** 同步作废旧控制器，不重建（没有页面实例、身份变更流程在途）。 */
    const val INVALIDATE = "invalidate"

    /** 同步作废旧控制器，按此刻身份与配置重建（配置关闭时重建成「未开放」、不发请求）。 */
    const val REBUILD = "rebuild"
}

/**
 * AppAgent 每次状态同步（会话、隐私、环境、Config 变化、回到 Agent Tab）时的判定（鸿蒙 R1-03、R1-07，共性第 12、16 条）：
 * - 撤回 AI 同意 / 撤回隐私 / 退出登录的流程在途（[AgentIdentityChange.active]）：只作废、不重建——返回 Agent Tab 也不准入 current / 历史 / 发消息，流程结束后
 *   再按结果重建；
 * - 身份键没变但配置可用性（agent.enabled / agent.entry.visible）变了：同样作废并重建（关闭时立即停流、取消所属请求、切「未开放」），不因身份键相同而早退；
 * - 其余照旧：身份键与可用性都没变 → 不动；不需要页面实例 → 只作废。
 */
object AgentSyncPolicy {
    @Suppress("LongParameterList") // 判定输入逐项
    fun decide(
        oldKey: Any?,
        oldAvailable: Boolean,
        nowKey: Any,
        nowAvailable: Boolean,
        identityChangeInFlight: Boolean,
        pageWanted: Boolean,
    ): String =
        when {
            identityChangeInFlight -> AgentSyncAction.INVALIDATE
            oldKey != null && oldKey == nowKey && oldAvailable == nowAvailable -> AgentSyncAction.KEEP
            !pageWanted -> AgentSyncAction.INVALIDATE
            else -> AgentSyncAction.REBUILD
        }
}

/**
 * 应用级「身份变更在途」（鸿蒙 R1-03，S0；共性第 12、24 条）：撤回 AI 同意、撤回隐私同意、退出登录在用户确认后的同步入口 [begin]（先于归属协调器停流与 cancel），
 * 流程自己的请求结束、结果落定后 [end]。在途期间 [AgentSyncPolicy] 不重建控制器、端口出口与控制器准入都不放行（捕获的那一次 cancel 与流程自己的请求不经它们）。
 * 同一事件可重叠（计数）；只在内存；没有超时（G63）。只在主线程使用。
 */
class AgentIdentityChange {
    private val flows = mutableMapOf<String, Int>()

    fun begin(event: String) {
        flows[event] = (flows[event] ?: 0) + 1
    }

    /** 流程结束（成功、失败、被拒都调用）；没有 begin 过的 end 无效。 */
    fun end(event: String) {
        val n = flows[event] ?: 0
        if (n <= 1) flows -= event else flows[event] = n - 1
    }

    val active: Boolean get() = flows.isNotEmpty()
}
