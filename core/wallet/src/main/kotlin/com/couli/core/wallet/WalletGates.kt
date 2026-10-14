package com.couli.core.wallet

import com.couli.core.network.ApiResult
import com.couli.core.router.RouteParams
import com.couli.generated.RouteSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 钱包类请求的 42901 禁用期（brief §0「Retry-After」，APP-08 共性第 2、13、27 条）：一份截止时间，任一属于它的请求收到 42901 都按 Retry-After
 * （缺省或非法 5 秒）延长；页面按 [blocked] 同步置灰入口，执行层把 [active] 作为发送前闸门（CallOwnership.sendAllowed，网络层每次实际写出前
 * 都核对，含自动重试与重放）。按会话共享，会话换代时 [reset]。共享范围：提现提交 / 确认 / 放弃一份；收款账号保存 / 确认 / 放弃一份；发码一份；
 * 读请求（摘要、rules、记录、收款账号）一份。[active] 任意线程可读，其余只在主线程用。
 */
class WalletRateGate(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var deadline = 0L
    private var timer: Job? = null
    private val mutable = MutableStateFlow(false)

    val blocked: StateFlow<Boolean> = mutable.asStateFlow()

    val active: Boolean get() = clock() < deadline

    fun remainingMillis(): Long = (deadline - clock()).coerceAtLeast(0L)

    fun start(millis: Long) {
        if (millis <= 0) return
        deadline = maxOf(deadline, clock() + millis)
        mutable.value = true
        timer?.cancel()
        timer =
            scope.launch {
                while (active) delay((deadline - clock()).coerceAtLeast(1L))
                mutable.value = false
            }
    }

    fun reset() {
        timer?.cancel()
        timer = null
        deadline = 0L
        mutable.value = false
    }

    /** 给一次请求的发送前闸门（交给 CallOwnership.sendAllowed）：被它拦下时记下明确原因，见 [SendProbe]。 */
    fun probe(): SendProbe = SendProbe(this)
}

/**
 * 一次请求的发送前限流闸门（APP-09 共性第 62 条，Android RC-04）：网络层每次实际写出前调 [allowed]；禁用期内返回 false 并记下「本请求被发送前限流拒绝」。
 * 回调按 [gated] 结算（结束加载、显示 42901），不用回调时刻的禁用期反推——拒绝之后禁用期可能已到期。可否重试另按当前截止时间判断。
 * [allowed] 在网络线程调用，[gated] 在主线程读。
 */
class SendProbe internal constructor(
    private val limitedNow: () -> Boolean,
    private val extra: () -> Boolean = { true },
) {
    internal constructor(gate: WalletRateGate, extra: () -> Boolean = { true }) : this({ gate.active }, extra)

    @Volatile
    var rejected: Boolean = false
        private set

    /** 发送前闸门：禁用期内拒绝并记下；[extra] 是调用方另外的发送条件（不算限流）。 */
    fun allowed(): Boolean {
        if (limitedNow()) {
            rejected = true
            return false
        }
        return extra()
    }

    /** 这次结果是本请求被发送前限流拦下（本地拒绝 call_cancelled，且拒绝原因是禁用期）。 */
    fun gated(result: ApiResult): Boolean = rejected && WalletErrors.of(result).kind == WalletError.CANCELLED

    /** 同 [gated]，用已经分类好的错误。 */
    fun gated(error: WalletError?): Boolean = rejected && error?.kind == WalletError.CANCELLED

    companion object {
        /** 带调用方另外发送条件的闸门（例：发码还要求同一截止时间已过；那一条不算限流）。 */
        fun of(
            gate: WalletRateGate,
            extra: () -> Boolean,
        ): SendProbe = SendProbe(gate, extra)

        /** 限流判定由调用方给（二次验证发码按「账号 + 号码 + action」的槽位限流，APP-13a 共性第 15 条）。 */
        fun limitedBy(
            limited: () -> Boolean,
            extra: () -> Boolean,
        ): SendProbe = SendProbe(limited, extra)
    }
}

/** 会话级共享的几份禁用期（App 建一份，会话换代时整体 [reset]）。 */
class WalletGates(
    scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
) {
    val read = WalletRateGate(scope, clock)
    val withdraw = WalletRateGate(scope, clock)
    val payout = WalletRateGate(scope, clock)
    val sms = WalletRateGate(scope, clock)
    val verify = WalletRateGate(scope, clock)
    val realname = WalletRateGate(scope, clock)
    val labor = WalletRateGate(scope, clock)

    fun reset() = listOf(read, withdraw, payout, sms, verify, realname, labor).forEach(WalletRateGate::reset)
}

/** 帮助文章入口（G25、G26，照 APP-08 PriceCompareEntry）：help_links.<key> 已配置且目标路由与参数合法才给目标，未配置不显示。 */
object HelpLink {
    const val WITHDRAW_RULES = "withdraw_rules"
    private const val ARTICLE_ID = "article_id"

    data class Target(
        val route: String,
        val params: JsonObject,
    )

    @Suppress("ReturnCount") // 逐项校验
    fun target(
        helpLinks: JsonObject?,
        key: String,
        routes: Map<String, RouteSpec>,
    ): Target? {
        val entry = helpLinks?.get(key) as? JsonObject ?: return null
        val route = (entry["route"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val params = entry["params"] as? JsonObject ?: return null
        if (route != WalletRoutes.HELP) return null
        val article = (params[ARTICLE_ID] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (article.isNullOrEmpty()) return null
        val spec = routes[route] ?: return null
        if (!RouteParams.isValid(spec, params)) return null
        return Target(route, params)
    }
}
