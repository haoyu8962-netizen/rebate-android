package com.couli.core.push

import com.couli.core.me.PushAction
import com.couli.core.me.PushEvent
import com.couli.core.me.PushReportPolicy
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 推送令牌来源（G17）：当前令牌、令牌变化通知、启动 / 停止。真实实现接 MobPush Android SDK（未接入，TODO(N-33)）。
 * 令牌值只交给上报管线，不进日志、埋点、诊断信息与界面。
 */
interface PushTokenSource {
    /** PrivacyGate.onConsented 的推送步骤调用（Release 为空操作）。 */
    fun start()

    /** 撤回同意时停止（不再给令牌、不再通知）。 */
    fun stop()

    fun current(): String?

    /** 令牌值变化（含第一次拿到）；返回退订函数。 */
    fun subscribe(listener: () -> Unit): () -> Unit
}

/** Release 的令牌来源：恒为无令牌（不初始化任何推送 SDK、不上报、不报错）。 */
class NoPushTokenSource : PushTokenSource {
    override fun start() = Unit

    override fun stop() = Unit

    override fun current(): String? = null

    override fun subscribe(listener: () -> Unit): () -> Unit = {}
}

/** 上报管线读的 App 状态（App 组合处给；都在主线程调用）。 */
interface PushReportState {
    /** member（已同意且已登录）/ guest / basic（未同意、基本模式、撤回清理未完成）。 */
    fun level(): String

    /** full / deletion_only（没有会话为空串）。 */
    fun sessionScope(): String

    /** 强更结论：supported / below / unknown。 */
    fun forceUpdate(): String

    /** 当前会话世代的标识（登录世代 + 账号；没有会话为空串）。 */
    fun sessionKey(): String

    /** 当前会话的账号（请求带 expectedAccount）；没有为 null。 */
    fun account(): String?

    /** 10405：交已有的强更分流（不另弹提示）。 */
    fun onForceUpdate()
}

/**
 * 推送令牌上报执行器（会话级服务，不依赖页面；brief §2.13，G18）。按 [PushReportPolicy] 的结论发 POST /v1/devices/push-token {provider: mobpush, token}；
 * 请求绑定发起时的会话世代（归属：会话变了即作废，旧会话的响应只按原世代结算，不改新会话），带 expectedAccount。退出、撤回后终止并另建策略实例。
 * 只在主线程使用（[scope] 为主线程作用域）。
 */
@Suppress("TooManyFunctions") // 每个触发、停报、结算各一个入口
class PushTokenReporter(
    private val source: PushTokenSource,
    private val state: PushReportState,
    private val api: PageApi,
    private val scope: CoroutineScope,
) {
    private var policy = PushReportPolicy()
    private val owners = mutableListOf<CallOwnership>()

    /**
     * 会话级停报（鸿蒙 R1-02，共性第 2 条，G18 ④）：撤回同意、退出登录在确认生效的同步入口调用 [halt] 记下当时的会话标识；这个会话里不再发送、不再结算
     * 补发。null = 没有停报。会话换成别的（新登录）后自然失效。
     */
    private var haltedSession: String? = null

    /**
     * 撤回同意进行中的停报闸门（Android R1-01，S0；共性第 19 条）：与会话标识无关——从撤回确认生效（[beginWithdraw]）到撤回清理结束（[endWithdraw]），
     * 期间登录新会话、令牌变化、冷启动都不触发上报；已排队的发送在写出前（[CallOwnership.sendAllowed]）与结算时同样被拦。
     */
    private var withdrawing = false

    /** 已登录冷启动在等隐私与版本检查（鸿蒙 R1-03，共性第 3 条）：期间令牌变化不单独触发，合并到这次冷启动上报。 */
    private var coldStartHeld = false

    /** 本次运行在当前会话里是否有一条上报成功（诊断信息「推送是否已上报」，AC-S1-136）。 */
    val reportedThisRun: Boolean get() = reportedSession?.let { it.isNotEmpty() && it == safeKey() } == true

    /**
     * 上报成功时的会话标识（Android R2-03 同类，共性第 30 条）：「已上报」是服务端信号推导出的标记，绑定成功那一刻的会话（登录世代 + 账号），使用时只比较；
     * 换账号、退出后不把上一个会话的上报结果算到当前会话。
     */
    private var reportedSession: String? = null

    /** 发出的条数（测试与诊断用；不含令牌）。 */
    var sent: Int = 0
        private set

    private val unsubscribe: () -> Unit = source.subscribe { tokenChanged() }

    val inFlight: Boolean get() = policy.inFlight
    val pending: Boolean get() = policy.pending

    /** 处于停报中：撤回同意进行中（不论会话），或当前会话已停报（退出登录进行中）。 */
    val halted: Boolean get() = withdrawing || haltedSession?.let { it == safeKey() } == true

    /** 撤回同意进行中（诊断与测试用）。 */
    val withdrawHalted: Boolean get() = withdrawing

    val holdingForColdStart: Boolean get() = coldStartHeld

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun safeKey(): String =
        try {
            state.sessionKey()
        } catch (error: Exception) {
            ""
        }

    /**
     * 撤回同意、退出登录在确认生效的同步入口调用：设会话级停报、清待报、作废在途请求（取消已交给传输层的、归属随之失效），之后本会话里的触发一律不发。
     */
    fun halt() {
        haltedSession = safeKey()
        coldStartHeld = false
        terminate()
    }

    /**
     * 撤回同意确认生效的同步入口：设撤回停报闸门（不随会话变化解除），并按 [halt] 清待报、作废在途。与 [endWithdraw] 成对调用。
     */
    fun beginWithdraw() {
        withdrawing = true
        halt()
    }

    /** 撤回清理结束（成功或落盘失败都调）：解除撤回停报闸门。之后是否上报仍按隐私结论与会话判断（基本模式不报）。 */
    fun endWithdraw() {
        withdrawing = false
    }

    fun holdForColdStart() {
        coldStartHeld = true
    }

    fun releaseColdStart() {
        coldStartHeld = false
    }

    private fun snapshot(): JsonObject {
        val token = source.current()
        return JsonObject(
            mapOf(
                "levelRaw" to JsonPrimitive(state.level()),
                "sessionScopeRaw" to JsonPrimitive(state.sessionScope()),
                "hasToken" to JsonPrimitive(!token.isNullOrEmpty()),
                "token" to JsonPrimitive(token.orEmpty()),
                "forceUpdateRaw" to JsonPrimitive(state.forceUpdate()),
                "session" to JsonPrimitive(safeKey()),
            ),
        )
    }

    /** 已登录冷启动（隐私闸门通过、强更检查不低于、会话 full 之后）；初始化期间的令牌变化合并到这一次。 */
    fun coldStart() {
        coldStartHeld = false
        if (halted) return
        run(policy.on(PushEvent.COLD_START, snapshot()))
    }

    /** 登录成功（新会话已保存）。 */
    fun loginSucceeded() {
        coldStartHeld = false
        if (halted) return
        run(policy.on(PushEvent.LOGIN_SUCCESS, snapshot()))
    }

    /** 会话换代（换账号）：作废在途与待报。 */
    fun sessionChanged() {
        coldStartHeld = false
        cancelAll()
        policy.on(PushEvent.SESSION_CHANGED, snapshot())
    }

    /** 退出登录、会话失效到游客：终止本实例并另建（之后的登录用新实例）。 */
    fun loggedOut() {
        coldStartHeld = false
        terminate()
    }

    /** 撤回同意：终止、停令牌来源、清待报；另建策略实例（重新同意并登录后再启用）。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun consentWithdrawn() {
        coldStartHeld = false
        terminate()
        reportedSession = null
        try {
            source.stop()
        } catch (error: Exception) {
            // 来源出错不影响撤回
        }
    }

    private fun terminate() {
        cancelAll()
        policy.on(PushEvent.LOGOUT, JsonObject(emptyMap()))
        policy = PushReportPolicy()
    }

    private fun cancelAll() {
        val cancelled = owners.toList()
        owners.clear()
        cancelled.forEach(api::cancelOwned)
    }

    private fun tokenChanged() {
        // 冷启动等待中：合并到冷启动上报；停报中：不发（共性第 2、3 条）
        if (coldStartHeld || halted) return
        run(policy.on(PushEvent.TOKEN_CHANGED, snapshot()))
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // 动作、停报、令牌、会话、账号逐项；结果按三类事件结算
    private fun run(action: JsonObject) {
        if ((action["actionRaw"] as? JsonPrimitive)?.content != PushAction.SEND || halted) return
        val token = (action["sendToken"] as? JsonPrimitive)?.content ?: return
        val session = (action["sendSession"] as? JsonPrimitive)?.content ?: return
        val account = state.account() ?: return settle(policy, PushEvent.RESPONSE_ERROR, session)
        val sending = policy
        // 发送前与收到响应后都核对：会话已换代就作废（不改绑到新会话）
        val owner =
            CallOwnership(api.epochs(), sendAllowed = { !withdrawing }) { policy === sending && !halted && safeKey() == session }
                .also { owners += it }
        val body = JsonObject(mapOf("provider" to JsonPrimitive(PROVIDER), "token" to JsonPrimitive(token))).toString()
        sent += 1
        scope.launch {
            val result = api.send(ApiCall(OPERATION, body = body, ownership = owner, expectedAccount = account))
            owners -= owner
            val event =
                when {
                    result is ApiResult.Success -> {
                        if (policy === sending && !halted && safeKey() == session) reportedSession = session
                        PushEvent.RESPONSE_OK
                    }

                    result is ApiResult.Failure && result.code == FORCE_UPDATE_CODE -> {
                        PushEvent.RESPONSE_10405
                    }

                    else -> {
                        PushEvent.RESPONSE_ERROR
                    }
                }
            settle(sending, event, session)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun settle(
        sending: PushReportPolicy,
        event: String,
        session: String,
    ) {
        val next = sending.on(event, JsonObject(mapOf("session" to JsonPrimitive(session))))
        if ((next["actionRaw"] as? JsonPrimitive)?.content == PushAction.FORCE_UPDATE) {
            // 10405 在后台得到：交已有分流（网络层已进强更时重复调用无害），不另弹提示
            try {
                state.onForceUpdate()
            } catch (error: Exception) {
                // 分流出错不影响上报管线
            }
            return
        }
        if (sending === policy && !halted) run(next)
    }

    /** 测试与进程结束：退订令牌来源。 */
    fun close() = unsubscribe()

    companion object {
        const val OPERATION = "reportPushToken"

        /** 04 push_tokens.provider（契约是字符串，G18）。 */
        const val PROVIDER = "mobpush"
        private const val FORCE_UPDATE_CODE = 10405
    }
}

/**
 * 推送上报的会话跟踪（App 每次会话 / 隐私 / 强更状态同步时调 [sync]；鸿蒙 H07 / H17 的 Android 对应，共性第 3 条）：区分「恢复已有会话」与「真实登录成功」。
 * - 真实登录成功只认 LoginHost.loginCompleted 给的登录世代（[loginCompleted]）：会话标识变成这个世代时报 login_success；
 * - 其余出现的会话（进程第一次同步就有、隐私结论或环境晚到后才可读的已存会话）一律按已登录冷启动：等隐私与版本检查有结果（不低于才报，低于丢弃），期间
 *   令牌变化合并到这次冷启动；
 * - 会话标识变空：退出 / 失效（策略实例终止并另建）。
 */
class PushSessionTracker(
    private val reporter: PushTokenReporter,
    private val sessionKey: () -> String,
    private val loginEpoch: () -> Long,
    /** 强更结论：supported / below / unknown。 */
    private val update: () -> String,
) {
    private var last: String? = null
    private var coldPending = false
    private var loginGeneration = -1L

    val coldStartPending: Boolean get() = coldPending

    /** 登录完成（会话已保存、尝试仍有效；早于或晚于状态同步都可能）。 */
    @Suppress("ComplexCondition") // 冷启动等待中、会话标识一致、登录世代一致逐项
    fun loginCompleted(epoch: Long) {
        loginGeneration = epoch
        val key = sessionKey()
        if (coldPending && key.isNotEmpty() && key == last && loginEpoch() == epoch) {
            // 状态同步先于完成通知到达：这个会话其实是这次登录，按登录成功报（不再等冷启动）
            coldPending = false
            reporter.loginSucceeded()
        }
    }

    /** 撤回、切换环境的清理第一步：不再等冷启动。 */
    fun cancelColdStart() {
        coldPending = false
        reporter.releaseColdStart()
    }

    fun sync() {
        val key = sessionKey()
        val previous = last
        last = key
        if (previous == null) {
            startCold(key.isNotEmpty())
        } else if (previous != key) {
            coldPending = false
            reporter.releaseColdStart()
            if (key.isEmpty()) {
                reporter.loggedOut()
            } else {
                if (previous.isNotEmpty()) reporter.sessionChanged()
                if (loginGeneration >= 0 && loginEpoch() == loginGeneration) reporter.loginSucceeded() else startCold(true)
            }
        }
        if (coldPending && key.isNotEmpty()) {
            when (update()) {
                BELOW -> {
                    coldPending = false
                    reporter.releaseColdStart()
                }

                SUPPORTED -> {
                    coldPending = false
                    reporter.coldStart()
                }
            }
        }
    }

    private fun startCold(pending: Boolean) {
        coldPending = pending
        if (pending) reporter.holdForColdStart()
    }

    private companion object {
        const val BELOW = "below"
        const val SUPPORTED = "supported"
    }
}
