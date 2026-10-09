// 错误码是契约取值（contracts/error-codes.yaml），按码直接写
@file:Suppress("MagicNumber")

package com.couli.feature.trade

import com.couli.core.designsystem.PriceTerms
import com.couli.core.designsystem.ProductCardMapper
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.network.ResultSignals
import com.couli.core.network.RetryAfterGate
import com.couli.core.tradejump.BaichuanAdapter
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 购买浮层的阶段。 */
object PurchasePhase {
    const val IDLE = "idle"

    /** open 请求在途：可取消的加载层（ProductBuyOpening，03 §4.5）。 */
    const val LOADING = "loading"

    /** 8 秒没有响应（ProductBuyTimeout，TRADE-22）：【重试】沿用原键。 */
    const val TIMEOUT = "timeout"
}

/** 购买流程的弹层（每种对应设计稿一个画板）。 */
sealed interface PurchaseDialog {
    /** 价格变动（ProductPriceChanged）/ 券失效（[coupon]）确认；[rebateGone] 时合并无返利说明（ProductPriceChangedRebateGone）。 */
    data class ConfirmPrice(
        val oldFen: Long?,
        val newFen: Long?,
        val rebateGone: Boolean,
        val coupon: Boolean,
    ) : PurchaseDialog

    /** 返利变为 0（ProductRebateGone）。 */
    data class ConfirmRebateGone(
        val priceFen: Long?,
    ) : PurchaseDialog

    /** 比价无返利（ProductPriceCompareNoRebate，BR-PRICE-08）。 */
    data object CompareNoRebate : PurchaseDialog

    /** 平台授权说明半屏（ProductAuthSheet）。 */
    data class AuthSheet(
        val platform: String,
        val busy: Boolean = false,
    ) : PurchaseDialog

    /** 关闭授权（ProductAuthDeclined）/ 授权失败（[failed]，ProductAuthFailed）：【重试】【仍去购买（无返利）】（BR-ID-18）。 */
    data class AuthDeclined(
        val platform: String,
        val failed: Boolean,
    ) : PurchaseDialog

    /**
     * 授权结果查不到（A-R2-06，03 §4.2）：授权回流后的查询（listUnionBindings）、淘宝绑定或重新取授权地址请求失败（42901、5xxxx、传输失败），
     * 不知道到底授权了没有，不能当成用户没授权。标题是对应错误（5xxxx 带 trace6）；【重试】重新查询授权状态（42901 Retry-After 期间置灰、
     * 执行层也拦下）；【取消】放弃本次购买待办。[afterBind] 为淘宝绑定之后（查到未授权即授权失败），否则是拼多多授权回来（查到未授权即未完成授权）。
     */
    data class AuthCheckFailed(
        val platform: String,
        val textKey: String?,
        val traceId: String?,
        val afterBind: Boolean,
    ) : PurchaseDialog

    /** 站长授权不可用（ProductAuthUnavailable，BR-ID-24）：只提示，不给无返利购买。 */
    data class AuthUnavailable(
        val textKey: String?,
    ) : PurchaseDialog

    /** 下单须知（ProductJumpTip，BR-ATTR-21）。 */
    data class JumpTip(
        val platform: String,
    ) : PurchaseDialog

    /** 暂时无法打开淘宝（ProductJumpSdkFailed，BR-ATTR-27 淘宝行）。 */
    data object SdkFailed : PurchaseDialog

    /** 带按钮的错误提示（30103 / 30121 / 30151 / 30153 / 50303）。 */
    data class Notice(
        val textKey: String?,
        val traceId: String?,
        val noRebate: Boolean,
        val contact: Boolean,
        val retry: Boolean,
    ) : PurchaseDialog
}

/** 轻提示（文案键 + 变量 + trace6）。[id] 每次递增（同一句再次提示也重新播报）。 */
data class PurchaseToast(
    val key: String,
    val traceId: String? = null,
    val id: Long = 0,
)

/**
 * 点击结果带来的禁用原因（50301 / 30141，03 §7.4）。按对象绑定（A-R1-05）：下架写进那张卡（availability=off_shelf），平台停用经
 * [PurchaseEnv.markPlatformStatus] 按平台记下、新配置到达即被覆盖；不再有页级禁用标志。
 */
object BuyBlock {
    const val MAINTENANCE = "maintenance"
    const val NOT_LAUNCHED = "not_launched"
    const val OFF_SHELF = "off_shelf"
}

/** 购买流程的界面状态（不可变）。 */
data class PurchaseState(
    val phase: String = PurchasePhase.IDLE,
    val platform: String = "",
    /** 最近一次点购买的卡（搜索页只给这张卡加按钮覆盖）。 */
    val card: JsonObject? = null,
    val dialog: PurchaseDialog? = null,
    val toast: PurchaseToast? = null,
    /** 42901 限时禁用购买按钮（Retry-After，缺省 5 秒）。 */
    val rateLimited: Boolean = false,
    /** 全部路径失败、安装状态明确为 false：「安装X后下单才有返利」（ProductJumpNotInstalled，BR-ATTR-27 ②；unknown 不算，A-R1-13）。 */
    val notInstalled: Boolean = false,
    /** 即将交系统浏览器：「将通过浏览器打开X」+【取消】（ProductJumpBrowser）。 */
    val browserNotice: Boolean = false,
)

/** 购买流程要的页面环境（App 给）。 */
interface PurchaseEnv {
    /** 有登录会话（游客 open 返回 10001）。 */
    fun loggedIn(): Boolean

    /** 基本模式（没有同意完整隐私政策）：购买先走补同意，同意后按 pending_action 恢复一次（BR-ID-11、03 §4.1，A-R1-04）。 */
    fun basicMode(): Boolean = false

    /** 弹出补同意（首启弹窗的「开启完整功能」）。 */
    fun requestConsent() = Unit

    /** 此刻的平台购买状态（点击结果确认的 50301 优先，其次 features.platform_status；on / maintenance / not_launched，缺失为 null）。 */
    fun platformStatus(platform: String): String?

    /**
     * 点击结果确认的平台状态（50301 maintenance / not_launched，03 §7.4「点击时以响应为准」）：按平台记下，列表、详情的卡片映射与读屏都按它
     * （not_launched 隐藏返利金额与预估返后）；之后发出并生效的新配置覆盖它（A-R1-05、A-R1-06）。
     */
    fun markPlatformStatus(
        platform: String,
        status: String,
    ) = Unit

    /** 用 open 响应更新 [target] 这张卡（按 card_id，没有时按 product_key 认；无论确认还是取消，BR-PRICE-12）。 */
    fun updateCard(
        target: JsonObject,
        transform: (JsonObject) -> JsonObject,
    )

    /** 经 Router 打开路由（Login、Search、Help，routes.json 的路由名）。 */
    fun open(
        route: String,
        params: JsonObject,
    )

    /**
     * 按 [target] 的 product_key + item_ref 重取权威商品卡（getProduct；之后仍须用户再点），结果到达时用 [merge]（此刻页面上的那张卡,
     * 重取到的卡）→ 新卡 决定怎么落到页面上（A-R2-02，APP-06b 共性第 17 条）：
     * - 补齐价格字段（open 响应给不全价格、返利口径时，A-R1-07 / 08）：[CardPatch.fill]，只补此刻仍未知、且与本次 open 确认的价格 / 区间一致的
     *   字段，保留 open 确认的 link_id、返利状态、比价原因与按钮；
     * - 重新加载（30144 链接失效，A-R1-09）：[CardPatch.reload]，整卡换成权威卡，但卡片版本已变（之后又有购买结果写进来）时不覆盖。
     * 失败、页面已离开时什么都不做（卡片保持原样）。
     */
    fun refetchCard(
        target: JsonObject,
        merge: (current: JsonObject, fresh: JsonObject) -> JsonObject,
    )

    /** 帮助入口（/v1/config.help_links.<key>：{route, params}），没有配置为 null（不显示入口）。 */
    fun helpLink(key: String): Pair<String, JsonObject>?
}

/**
 * 一次购买尝试（不可变，A-R1-01 / 02）：用户这一次点击的原卡快照（改价、返利归零确认的判断基准）、完整的原请求（路径里的 link_id、请求体、
 * 幂等键）。超时【重试】、登录后的重放都只重放它；迟到响应可以更新展示的卡片（含 new_link_id），但不改它，所以不会消除确认要求，也不会让
 * 重试打到别的路径。新点击、授权完成后才建新的尝试。
 */
@Suppress("LongParameterList") // 一次尝试的原请求字段逐项
class PurchaseAttempt internal constructor(
    val click: Long,
    /** 点击时的卡片快照。 */
    val card: JsonObject,
    /** 请求路径里的 link_id。 */
    val linkId: String,
    /** 请求体（JSON 文本）。 */
    val body: String,
    val key: String,
    val noRebate: Boolean,
    val noRebateReason: String?,
    val installed: String,
    /** 用户在二选一里主动点【重试】发起的（拦下时直接给授权，不占自动弹出次数，A-R1-10）。 */
    val userAuthRetry: Boolean,
) {
    val platform: String get() = card.text("platform").orEmpty()

    /** 同一请求的重放（登录后）：新的点击编号，其余不变。 */
    internal fun replay(click: Long): PurchaseAttempt =
        PurchaseAttempt(click, card, linkId, body, key, noRebate, noRebateReason, installed, userAuthRetry)
}

/**
 * 购买执行器（APP-06b；03 §4.5、§7.4，TRADE-03 / 22，BR-PRICE-08 / 12 / 13，BR-ATTR-21 / 27，BR-ID-10 / 11 / 17 / 18 / 22 / 24）。一个
 * 发起页一份，只在主线程使用。
 *
 * - 点击：基本模式先补同意（登记 pending_action，同意后恢复一次）；按此刻的平台状态再核对一次（maintenance 提示、not_launched 不发），
 *   卡片已下架不发；再建一次 [PurchaseAttempt] 发 open（新键、可取消的加载层）；
 * - 8 秒没有响应：结束加载，「网络不稳定，请重试」，【重试】完整重放原尝试（原路径、原请求体、原键）；不自动重试、不自动外跳；
 * - 续执行许可（A-R1-03）：每次点击（及用户在确认框、超时条上的继续）发一张许可；取消、离开、进入后台或被覆盖、再次点击都作废它。
 *   等 JumpTip 已读、浏览器提示、跳转每一步、上报 link_jump 之前都复核许可；作废之后到达的 open 响应只更新卡片，不外跳、不弹确认；
 * - 响应 → [OpenResponseAction]（判断基准是尝试的原卡快照）；卡片按响应更新，价格 / 返利字段给不全时重取权威卡片（不反推）；
 *   错误 → [OpenErrorAction]；
 * - 10001：登记 pending_action（沿用原键）进登录，登录后 [resumeAfterLogin] 自动重放一次（不在前台时等回到前台）；
 * - 30101 / 30102 / 30111：AuthSheet（每平台每会话最多自动弹 2 次，BR-ID-18；用户在二选一里主动【重试】不受限）→ 授权 → 服务端确认已
 *   授权后自动执行一次 open，用新键（BR-ID-10 / 22 细则）；取消、没完成或查不到授权 → 删除待办，给【仍去购买（无返利）】【重试】；
 * - 外跳：JumpTip（用户 × 平台首次）→ [TradeJumpExecutor]（每步上报 link_jump，同一个 attempt_id 与替换后的 link_id；expire_at 过期即停）。
 */
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList") // 一次购买的全部分支集中在一处，逐条对照 03 §7.4
class OpenPurchaseController(
    private val api: PageApi,
    private val executor: TradeJumpExecutor,
    private val ports: JumpPorts,
    private val env: PurchaseEnv,
    private val pending: PurchasePending,
    private val counter: AuthSheetCounter,
    private val tips: JumpTipService,
    private val webCode: WebCodeAuthorizer,
    private val baichuan: BaichuanAdapter,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val keyGenerator: () -> String = { UUID.randomUUID().toString() },
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    private val browserNoticeMillis: Long = BROWSER_NOTICE_MILLIS,
) {
    private val keys = OpenAttempt(keyGenerator)
    private val mutableState = MutableStateFlow(PurchaseState())
    val state: StateFlow<PurchaseState> = mutableState.asStateFlow()

    /** 点击编号：每次新的点击（含授权后、登录后的自动执行）加 1。 */
    private var click = 0L

    /** 请求编号：超时【重试】是同一尝试的重发，旧请求的响应丢弃（新请求同键，结果相同）。 */
    private var request = 0L
    private var interrupt = LateResponsePolicy.NONE
    private var left = false

    /** 发起页在前台（ON_START 之后、ON_STOP 之前）。 */
    private var foreground = true

    /** 自动执行的动作（登录、补同意后的恢复）遇到页面不在前台：回到前台再执行。 */
    private var deferred: (() -> Unit)? = null
    private val owners = mutableListOf<CallOwnership>()
    private var timer: Job? = null
    private var toastSeq = 0L
    private val gate = RetryAfterGate(clock)
    private var gateTimer: Job? = null

    /** 当前的购买尝试。 */
    private var current: PurchaseAttempt? = null

    /** 当前尝试那张卡的最新展示版本（按响应更新后的）。 */
    private var card: JsonObject = JsonObject(emptyMap())
    private var spm: String? = null

    /** 续执行许可（A-R1-03）。 */
    private var permit: Permit? = null

    /** 最近一次成功响应（确认后外跳、比价弹窗用）。 */
    private var ready: ReadyJump? = null

    /** 被授权码拦下的上下文。 */
    private var authContext: AuthContext? = null
    private var awaitingAuthReturn = false

    /**
     * 授权世代（A-R3-01，APP-06b 共性第 20 条）：每次发出授权状态查询、开始一次授权流程、授权完成被消费、每次发出新的 open 请求都加 1。
     * 授权查询的归属、授权流程的结果、授权完成后的延迟恢复都绑定发出时的世代与购买尝试；世代变了就作废（旧查询不得关掉新尝试的确认窗）。
     */
    private var authGen = 0L
    private var browserDecision: CompletableDeferred<Boolean>? = null

    /** 基本模式下点购买、等补同意的那张卡与 spm。 */
    private var consentCard: Pair<JsonObject, String?>? = null

    private class Permit(
        val attempt: PurchaseAttempt,
    ) {
        var valid = true
    }

    private class ReadyJump(
        val plan: JsonObject,
        val identity: PurchaseIdentity,
        val attempt: PurchaseAttempt,
    )

    private class AuthContext(
        val code: Int,
        val linkId: String,
        val data: JsonObject,
        val attempt: PurchaseAttempt,
    ) {
        val platform: String get() = attempt.platform
    }

    /** 当前卡片（测试观察、搜索页认卡用）。 */
    val currentCard: JsonObject get() = card

    /** 当前购买尝试（测试观察用）。 */
    val currentAttempt: PurchaseAttempt? get() = current

    /** 最近一次 open 响应对应尝试的原卡（确认框「之前」一行的价格口径词用）。 */
    var cardBeforeResponse: JsonObject = JsonObject(emptyMap())
        private set

    // ---------------------------------------------------------------- 点击与发送

    /** 用户点了【购买】。[spm] 为按钮的 页面.模块.坑位（03 §4.7）。 */
    fun buy(
        card: JsonObject,
        spm: String?,
    ) = buy(card, spm, userAuthRetry = false)

    @Suppress("ReturnCount") // 忙、下架、平台状态、限流、基本模式五个出口
    private fun buy(
        card: JsonObject,
        spm: String?,
        userAuthRetry: Boolean,
    ) {
        if (left || busy()) return
        val platform = card.text("platform").orEmpty()
        // 下架绑定这张卡（30141 / availability=off_shelf）：同页其它商品照常（A-R1-05）
        if (card.text("availability") == BuyBlock.OFF_SHELF) return
        // 发出购买意图前再核对一次平台购买状态（APP-06a 共性第 27 条；含点击结果确认的 50301）
        when (env.platformStatus(platform)) {
            BuyBlock.MAINTENANCE -> return toast(MAINTENANCE_KEY)
            BuyBlock.NOT_LAUNCHED -> return
        }
        if (gate.active) return
        this.card = card
        this.spm = spm
        mutableState.value = mutableState.value.copy(platform = platform, card = card, notInstalled = false)
        if (env.basicMode()) return requestConsentFor(card, spm)
        startClick(card, keys.start(), noRebate = false, noRebateReason = null, userAuthRetry = userAuthRetry)
    }

    /** 基本模式：登记购买待办（没有发请求，不带键）并弹补同意；同意后 [resumeAfterConsent] 恢复一次，仍不同意由 Router 删除待办。 */
    private fun requestConsentFor(
        card: JsonObject,
        spm: String?,
    ) {
        val linkId = card.text("link_id") ?: return toast(REF_EXPIRED_KEY)
        consentCard = card to spm
        pending.register(pendingParams(card, linkId, key = null, kind = PENDING_CONSENT, noRebate = false, reason = null))
        env.requestConsent()
    }

    /** 加载层【取消】：只挡本机外跳，之后到达的响应只更新卡片（03 §4.5）。 */
    fun cancelLoading() {
        if (mutableState.value.phase != PurchasePhase.LOADING) return
        interrupt = LateResponsePolicy.CANCEL
        revokePermit()
        keys.cancel()
        timer?.cancel()
        mutableState.value = mutableState.value.copy(phase = PurchasePhase.IDLE)
    }

    /** 超时后的【重试】：完整重放原尝试（原路径、原请求体、原键，TRADE-22）；用户这一下重新发许可。 */
    fun retryAfterTimeout() {
        if (left || mutableState.value.phase != PurchasePhase.TIMEOUT) return
        val attempt = current ?: return
        keys.retry()
        grantPermit(attempt)
        send(attempt)
    }

    private fun startClick(
        card: JsonObject,
        key: String,
        noRebate: Boolean,
        noRebateReason: String?,
        userAuthRetry: Boolean,
    ) {
        // 没有 link_id 的卡买不了（契约 openLink 只按 link_id）：保守不发、提示失效
        val linkId = card.text("link_id") ?: return toast(REF_EXPIRED_KEY)
        click += 1
        val installed = ports.installed(card.text("platform").orEmpty())
        val attempt =
            PurchaseAttempt(
                click = click,
                card = card,
                linkId = linkId,
                body = openBody(installed, noRebate, noRebateReason),
                key = key,
                noRebate = noRebate,
                noRebateReason = noRebateReason,
                installed = installed,
                userAuthRetry = userAuthRetry,
            )
        this.card = card
        current = attempt
        ready = null
        grantPermit(attempt)
        mutableState.value = mutableState.value.copy(platform = attempt.platform, card = card, notInstalled = false)
        send(attempt)
    }

    private fun send(attempt: PurchaseAttempt) {
        request += 1
        val myRequest = request
        interrupt = LateResponsePolicy.NONE
        // 先作废旧的授权归属（查询、授权流程、待办、延迟恢复），再取消在途请求：取消到达传输层时归属已失效，不会被当成断连重试（A-R3-01）
        invalidateAuth()
        cancelOwners()
        val owner = CallOwnership(api.epochs()) { !left && current === attempt && myRequest == request }
        owners += owner
        mutableState.value = mutableState.value.copy(phase = PurchasePhase.LOADING, dialog = null)
        timer?.cancel()
        timer =
            scope.launch {
                delay(timeoutMillis)
                if (current === attempt && myRequest == request && mutableState.value.phase == PurchasePhase.LOADING) {
                    keys.timeout()
                    interrupt = TIMEOUT
                    mutableState.value = mutableState.value.copy(phase = PurchasePhase.TIMEOUT)
                }
            }
        scope.launch {
            val result =
                api.send(
                    ApiCall(
                        TradeOperations.OPEN_LINK,
                        pathParams = mapOf("link_id" to attempt.linkId),
                        body = attempt.body,
                        idempotencyKey = attempt.key,
                        ownership = owner,
                    ),
                )
            // 等待之后复核归属：离开、再次点击、重发之后的旧响应只丢弃
            if (left || current !== attempt || myRequest != request) return@launch
            owners -= owner
            timer?.cancel()
            settle(result, attempt)
        }
    }

    private fun openBody(
        installed: String,
        noRebate: Boolean,
        noRebateReason: String?,
    ): String {
        val fields = linkedMapOf<String, JsonElement>("installed" to JsonPrimitive(installed))
        if (noRebate) {
            fields["no_rebate"] = JsonPrimitive(true)
            noRebateReason?.let { fields["no_rebate_reason"] = JsonPrimitive(it) }
        }
        spm?.takeIf { it.length <= SPM_MAX }?.let { fields["spm"] = JsonPrimitive(it) }
        return JsonObject(fields).toString()
    }

    // ---------------------------------------------------------------- 响应

    private fun settle(
        result: ApiResult,
        attempt: PurchaseAttempt,
    ) {
        val late = LateResponsePolicy.decide(if (interrupt == TIMEOUT) LateResponsePolicy.CANCEL else interrupt)
        if (!late.jump || !permitted(attempt)) {
            // 取消、离开、后台、超时之后才到：只更新卡片（新价格、new_link_id、无返利状态），不外跳、不弹确认、不进登录 / 授权
            ((result as? ApiResult.Success)?.data as? JsonObject)?.let { patchCard(attempt, it) }
            if (mutableState.value.phase == PurchasePhase.LOADING) mutableState.value = mutableState.value.copy(phase = PurchasePhase.IDLE)
            return
        }
        mutableState.value = mutableState.value.copy(phase = PurchasePhase.IDLE)
        when (result) {
            is ApiResult.Success -> {
                onSuccess(result, attempt)
            }

            is ApiResult.Failure -> {
                onFailure(result, attempt)
            }

            is ApiResult.TransportFailure -> {
                // 超时、断网、没有外壳：与 8 秒超时同样处理，【重试】重放原尝试
                interrupt = TIMEOUT
                mutableState.value = mutableState.value.copy(phase = PurchasePhase.TIMEOUT)
            }

            else -> {
                toast(GENERIC_KEY)
            }
        }
    }

    private fun onSuccess(
        result: ApiResult.Success,
        attempt: PurchaseAttempt,
    ) {
        val data = result.data as? JsonObject ?: JsonObject(emptyMap())
        val envelope = JsonObject(mapOf("code" to JsonPrimitive(0), "data" to data))
        // 判断基准是尝试的原卡快照：迟到响应更新过的展示卡片不能消除改价 / 返利归零的确认（A-R1-01，BR-PRICE-13）
        val outcome = OpenResponseAction.map(envelope, openContext(attempt.card))
        patchCard(attempt, data)
        val attemptId = data.text("attempt_id")
        val plan = data["jump"] as? JsonObject
        if (attemptId == null || plan == null) {
            // 契约必填字段缺失：保守不外跳
            toast(GENERIC_KEY, result.traceId)
            return
        }
        val jump = ReadyJump(plan, PurchaseIdentity(attemptId, outcome.replaceLinkId ?: attempt.linkId), attempt)
        ready = jump
        if (outcome.requoteNotice) toast(REQUOTE_KEY)
        when (outcome.actionRaw) {
            OpenActions.OFF_SHELF -> {
                toast(OFF_SHELF_KEY)
            }

            OpenActions.CONFIRM_PRICE, OpenActions.CONFIRM_COUPON -> {
                showDialog(
                    PurchaseDialog.ConfirmPrice(
                        oldFen = outcome.oldFinalFen,
                        newFen = outcome.newFinalFen,
                        rebateGone = outcome.rebateGone,
                        coupon = outcome.actionRaw == OpenActions.CONFIRM_COUPON,
                    ),
                )
            }

            OpenActions.CONFIRM_REBATE_GONE -> {
                showDialog(PurchaseDialog.ConfirmRebateGone(outcome.newFinalFen ?: outcome.oldFinalFen))
            }

            OpenActions.COMPARE_NO_REBATE -> {
                showDialog(PurchaseDialog.CompareNoRebate)
            }

            else -> {
                proceed(jump)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // 逐码对照 error-codes.yaml 与 03 §7.4
    private fun onFailure(
        failure: ApiResult.Failure,
        attempt: PurchaseAttempt,
    ) {
        val platform = attempt.platform
        val outcome = OpenErrorAction.map(failure.code, failure.data)
        val trace = failure.traceId.takeIf { outcome.showTrace6 }
        when (outcome.actionRaw) {
            OpenErrorActions.LOGIN -> {
                // 1xxxx：登录后沿用原键重放（BR-ID-10 细则），登记 pending_action 后进登录页
                pending.register(
                    pendingParams(attempt.card, attempt.linkId, attempt.key, PENDING_LOGIN, attempt.noRebate, attempt.noRebateReason),
                )
                env.open(LOGIN_ROUTE, JsonObject(emptyMap()))
            }

            OpenErrorActions.AUTH_SHEET, OpenErrorActions.AUTH_JUMP -> {
                keys.authRequired(failure.code)
                authContext = AuthContext(failure.code, attempt.linkId, failure.data, attempt)
                // 自动弹出每平台每会话最多 2 次（BR-ID-18）；用户在二选一里主动【重试】进来的不算自动弹出、不受限（A-R1-10）
                if (attempt.userAuthRetry || counter.tryAutoPop(platform)) {
                    pending.register(
                        pendingParams(attempt.card, attempt.linkId, key = null, kind = PENDING_AUTH, noRebate = false, reason = null),
                    )
                    showDialog(PurchaseDialog.AuthSheet(platform))
                } else {
                    // 同一会话第 3 次起不再自动拉起授权，直接给二选一（BR-ID-18）
                    pending.clear()
                    showDialog(PurchaseDialog.AuthDeclined(platform, failed = false))
                }
            }

            OpenErrorActions.NOTICE -> {
                when {
                    failure.code == 30101 || failure.code == 30102 -> {
                        showDialog(PurchaseDialog.AuthUnavailable(outcome.textKey))
                    }

                    failure.code == 50301 -> {
                        // 点击结果确认的平台状态：按平台记下（卡片映射隐藏返利、按钮禁用），新配置到达即被覆盖（A-R1-05 / 06）
                        val reason = if (outcome.silent) BuyBlock.NOT_LAUNCHED else BuyBlock.MAINTENANCE
                        env.markPlatformStatus(platform, reason)
                        if (!outcome.silent) toast(outcome.textKey ?: MAINTENANCE_KEY, trace)
                    }

                    outcome.offerNoRebate || outcome.offerContact -> {
                        showDialog(
                            PurchaseDialog.Notice(outcome.textKey, trace, outcome.offerNoRebate, outcome.offerContact, retry = false),
                        )
                    }

                    else -> {
                        toast(outcome.textKey ?: GENERIC_KEY, trace)
                    }
                }
            }

            OpenErrorActions.ERROR -> {
                when (failure.code) {
                    30141 -> {
                        // 下架绑定这张卡（A-R1-05）
                        updateCard(attempt) { it.with("availability", JsonPrimitive(BuyBlock.OFF_SHELF)) }
                        toast(outcome.textKey ?: OFF_SHELF_KEY)
                    }

                    50303 -> {
                        showDialog(PurchaseDialog.Notice(outcome.textKey, failure.traceId, noRebate = true, contact = false, retry = true))
                    }

                    else -> {
                        toast(outcome.textKey ?: GENERIC_KEY, failure.traceId)
                    }
                }
            }

            OpenErrorActions.RETRY_AFTER -> {
                startBlock(ResultSignals.disableMillis(failure))
                toast(outcome.textKey ?: RATE_LIMITED_KEY)
            }

            OpenErrorActions.RESEARCH -> {
                toast(outcome.textKey ?: REF_EXPIRED_KEY)
                env.open(SEARCH_ROUTE, JsonObject(emptyMap()))
            }

            OpenErrorActions.REFETCH -> {
                // 30144：按这张卡的 product_key + item_ref 重取（搜索卡与详情同样），替换后等用户再点（03 §4.2，A-R1-09）
                toast(outcome.textKey ?: REF_EXPIRED_KEY)
                val target = latestCard(attempt)
                env.refetchCard(target, CardPatch.reload(CardPatch.revision(target)))
            }

            OpenErrorActions.FORCE_UPDATE -> {
                Unit
            }

            else -> {
                toast(outcome.textKey ?: GENERIC_KEY, trace)
            }
        }
    }

    /** open 上下文：[base] 卡片的价格与返利上限（OpenResponseAction 判返利归零用）。 */
    private fun openContext(base: JsonObject): JsonObject {
        val unknown = base.text("rebate_basis") == AMOUNT_UNKNOWN
        val fields =
            linkedMapOf<String, JsonElement>(
                "currentPriceFen" to (base.fen("final_price_fen")?.let(::JsonPrimitive) ?: JsonNull),
                "currentRebateMaxFen" to (base.fen("rebate_max_fen")?.let(::JsonPrimitive) ?: JsonNull),
            )
        if (unknown) fields["amountUnknown"] = JsonPrimitive(true)
        return JsonObject(fields)
    }

    /** 尝试那张卡的最新展示版本。 */
    private fun latestCard(attempt: PurchaseAttempt): JsonObject = if (CardPatch.sameCard(card, attempt.card)) card else attempt.card

    /** 用响应更新卡片（BR-PRICE-12：无论确认还是取消）；价格 / 返利字段给不全时重取权威卡片（A-R1-07 / 08）。 */
    private fun patchCard(
        attempt: PurchaseAttempt,
        data: JsonObject,
    ) {
        val before = latestCard(attempt)
        cardBeforeResponse = attempt.card
        val after = CardPatch.bump(CardPatch.apply(before, data))
        updateCard(attempt) { CardPatch.apply(it, data) }
        // 只补齐价格 / 口径字段，不整卡替换：本次 open 确认的 link_id、返利状态、比价原因与按钮保留（A-R2-02，BR-PRICE-07 / 13）
        if (CardPatch.needsRefetch(before, data)) env.refetchCard(after, CardPatch::fill)
    }

    /** 写入购买结果（open 响应、30141 下架）：卡片版本加 1，之前发出的重新加载结果到达时认出卡片已更新、不覆盖（A-R2-02）。 */
    private fun updateCard(
        attempt: PurchaseAttempt,
        transform: (JsonObject) -> JsonObject,
    ) {
        val versioned: (JsonObject) -> JsonObject = { CardPatch.bump(transform(it)) }
        if (CardPatch.sameCard(card, attempt.card)) card = versioned(card)
        env.updateCard(attempt.card, versioned)
    }

    // ---------------------------------------------------------------- 确认与外跳

    /** 弹层的主按钮：改价 / 返利归零 / 券失效【继续购买】、比价【仍去购买（无返利）】、JumpTip【知道了，去X】。用户这一下重新发许可。 */
    fun confirm() {
        val dialog = mutableState.value.dialog ?: return
        val jump = ready
        when (dialog) {
            is PurchaseDialog.ConfirmPrice, is PurchaseDialog.ConfirmRebateGone, PurchaseDialog.CompareNoRebate -> {
                hideDialog()
                jump?.takeIf { it.attempt === current }?.let {
                    grantPermit(it.attempt)
                    proceed(it)
                }
            }

            is PurchaseDialog.JumpTip -> {
                hideDialog()
                val owner = owner()
                scope.launch { tips.markRead(dialog.platform, owner) }
                jump?.takeIf { it.attempt === current }?.let {
                    grantPermit(it.attempt)
                    execute(it)
                }
            }

            else -> {
                hideDialog()
            }
        }
    }

    /** 弹层的取消 / 关闭（按钮、遮罩、返回键都一样）。 */
    fun dismissDialog() {
        when (val dialog = mutableState.value.dialog) {
            is PurchaseDialog.ConfirmPrice -> {
                hideDialog()
                toast(if (dialog.rebateGone) CANCELLED_PRICE_REBATE_KEY else CANCELLED_PRICE_KEY)
            }

            is PurchaseDialog.ConfirmRebateGone, PurchaseDialog.CompareNoRebate -> {
                hideDialog()
                toast(CANCELLED_REBATE_KEY)
            }

            is PurchaseDialog.AuthSheet -> {
                // 用户关闭授权：删除待办，给二选一（BR-ID-18）
                if (dialog.busy) return
                pending.clear()
                showDialog(PurchaseDialog.AuthDeclined(dialog.platform, failed = false))
            }

            is PurchaseDialog.AuthCheckFailed -> {
                // 放弃本次：删除待办（之后授权了也不自动执行）
                hideDialog()
                pending.clear()
                authContext = null
            }

            else -> {
                hideDialog()
            }
        }
        ready = null
        revokePermit()
    }

    private fun proceed(jump: ReadyJump) {
        val granted = permit?.takeIf { it.attempt === jump.attempt } ?: return
        if (!env.loggedIn()) return execute(jump)
        val owner = owner()
        scope.launch {
            val show = tips.shouldShow(jump.attempt.platform, owner)
            // 等待之后复核许可：期间进入后台、被覆盖、离开、再点了别的都不再继续（A-R1-03）
            if (!allowed(granted) || ready !== jump) return@launch
            if (show) showDialog(PurchaseDialog.JumpTip(jump.attempt.platform)) else execute(jump)
        }
    }

    private fun execute(jump: ReadyJump) {
        val granted = permit?.takeIf { it.attempt === jump.attempt } ?: return
        if (!allowed(granted)) return
        ready = null
        scope.launch {
            val outcome =
                executor.execute(
                    jump.plan,
                    jump.attempt.platform,
                    jump.attempt.installed,
                    jump.identity,
                    allowed = { allowed(granted) },
                ) { confirmBrowser(granted) }
            if (left) return@launch
            afterJump(jump, outcome)
        }
    }

    private suspend fun confirmBrowser(granted: Permit): Boolean {
        if (!allowed(granted)) return false
        val decision = CompletableDeferred<Boolean>()
        browserDecision = decision
        mutableState.value = mutableState.value.copy(browserNotice = true)
        val result = withTimeoutOrNull(browserNoticeMillis) { decision.await() } ?: true
        browserDecision = null
        mutableState.value = mutableState.value.copy(browserNotice = false)
        return result && allowed(granted)
    }

    /** 「将通过浏览器打开」的【取消】。 */
    fun cancelBrowser() {
        browserDecision?.complete(false)
    }

    private fun afterJump(
        jump: ReadyJump,
        outcome: JumpOutcome,
    ) {
        if (outcome.cancelled || outcome.stopped) return
        if (outcome.expired) {
            // 方案过了 expire_at：不外跳，提示重新点购买取新方案（A-R1-12）
            toast(JUMP_EXPIRED_KEY)
            return
        }
        when (outcome.run.final) {
            JumpTypes.FINAL_OPENED, JumpTypes.FINAL_BROWSER -> {
                Unit
            }

            JumpTypes.FINAL_COPIED -> {
                toast(COPIED_KEY)
            }

            JumpTypes.FINAL_NOT_INSTALLED -> {
                mutableState.value = mutableState.value.copy(notInstalled = true)
                toast(NOT_INSTALLED_COPIED_KEY)
            }

            else -> {
                val onlySdk = outcome.run.executed.all { it == JumpTypes.SDK }
                when {
                    // 淘宝 sdk 步骤不可用且没有可执行的后续步骤：提示、不外跳（BR-ATTR-27 淘宝行）
                    outcome.sdkUnavailable && onlySdk -> showDialog(PurchaseDialog.SdkFailed)

                    // 明确未安装（installed=false）、全部路径失败：安装提示（BR-ATTR-27 ②）；unknown 的失败不能当未安装（A-R1-13）
                    jump.attempt.installed == INSTALLED_FALSE -> mutableState.value = mutableState.value.copy(notInstalled = true)

                    // 通用跳转失败，按钮照常可再点
                    else -> toast(JUMP_FAILED_KEY)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 授权

    /** AuthSheet【去授权】。 */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "ComplexCondition", "LongMethod") // 拼多多跳转、淘宝两种方式与本端能力逐项判定
    fun authorize() {
        val context = authContext ?: return
        val dialog = mutableState.value.dialog as? PurchaseDialog.AuthSheet ?: return
        if (dialog.busy) return
        mutableState.value = mutableState.value.copy(dialog = dialog.copy(busy = true))
        authGen += 1
        val gen = authGen
        val authJump = context.data["auth_jump"] as? JsonObject
        if (authJump != null) {
            scope.launch {
                val outcome =
                    executor.execute(
                        authJump,
                        context.platform,
                        ports.installed(context.platform),
                        identity = null,
                        allowed = { authLive(context, gen) },
                    )
                if (!authLive(context, gen)) return@launch
                val launched = outcome.run.final == JumpTypes.FINAL_OPENED || outcome.run.final == JumpTypes.FINAL_BROWSER
                if (launched) {
                    // 回到前台后由服务端确认已授权（[onForeground]）
                    awaitingAuthReturn = true
                    hideDialog()
                } else {
                    authFailed(context.platform)
                }
            }
            return
        }
        val flow = taobaoFlow(context, gen)
        if (flow.pick(context.platform, context.data) == null) {
            // 本端执行不了下发的任何授权方式（web_code 回跳未登记、百川不可用，TODO(N-28)）：按授权失败（BR-ID-17、BR-ID-18）
            authFailed(context.platform)
            return
        }
        scope.launch {
            // 含 30104 按 reason 的恢复：重新取授权上下文、换方式、新凭证与新幂等键（A-R2-05）
            val outcome = flow.run(context.platform, context.data)
            // 等待之后复核仍属本次授权（A-R3-01）
            if (!authLive(context, gen)) return@launch
            when (outcome) {
                TaobaoAuthOutcome.Bound -> {
                    authorized(context, gen)
                }

                TaobaoAuthOutcome.Cancelled -> {
                    pending.clear()
                    showDialog(PurchaseDialog.AuthDeclined(context.platform, failed = false))
                }

                TaobaoAuthOutcome.Failed -> {
                    authFailed(context.platform)
                }

                is TaobaoAuthOutcome.RequestFailed -> {
                    bindFailed(context, outcome.result)
                }

                TaobaoAuthOutcome.Abandoned -> {
                    Unit
                }
            }
        }
    }

    private fun taobaoFlow(
        context: AuthContext,
        gen: Long,
    ): TaobaoAuthFlow =
        TaobaoAuthFlow(
            api,
            webCode,
            baichuan,
            keyGenerator,
            owner = { authOwner(context, gen) },
            alive = { authLive(context, gen) },
            installed = ports::installed,
        )

    /** 授权归属仍有效（A-R3-01）：页面没离开、世代没变、授权上下文仍是它、购买尝试仍是当前的。 */
    private fun authLive(
        context: AuthContext,
        gen: Long,
    ): Boolean = !left && gen == authGen && authContext === context && current === context.attempt

    /** 授权请求的归属：绑定授权世代与购买尝试，作废后取消到达传输层时不会被当成断连重试。 */
    private fun authOwner(
        context: AuthContext,
        gen: Long,
    ): CallOwnership = CallOwnership(api.epochs()) { authLive(context, gen) }.also { owners += it }

    /**
     * 作废旧的授权归属（A-R3-01）：新的 open 请求发出前调用。世代加 1（在途查询、授权流程的结果与延迟恢复一律丢弃），清掉授权上下文、
     * 「等授权回来」，以及属于旧授权上下文的授权待办（之后不会被旧查询自动执行）。
     */
    private fun invalidateAuth() {
        authGen += 1
        val old = authContext
        authContext = null
        awaitingAuthReturn = false
        if (old != null) {
            pending.peek()?.takeIf { it.text("kind") == PENDING_AUTH && it.text("link_id") == old.linkId }?.let { pending.clear() }
        }
    }

    /** 淘宝绑定（或 30104 后重新取授权地址）没有拿到结果：按码处理，限流、服务端故障不当成用户没授权（A-R2-06）。 */
    private fun bindFailed(
        context: AuthContext,
        result: ApiResult,
    ) {
        val failure = result as? ApiResult.Failure
        when {
            failure != null && failure.code in setOf(30151, 30153) -> {
                pending.clear()
                val outcome = OpenErrorAction.map(failure.code, failure.data)
                showDialog(PurchaseDialog.Notice(outcome.textKey, null, noRebate = true, contact = true, retry = false))
            }

            failure != null && (failure.code == 30101 || failure.code == 30102) && failure.data.text("reason") == AUTH_UNAVAILABLE -> {
                pending.clear()
                showDialog(PurchaseDialog.AuthUnavailable(OpenErrorAction.map(failure.code, failure.data).textKey))
            }

            else -> {
                authCheckFailed(context, result, afterBind = true)
            }
        }
    }

    /**
     * 授权状态查询 / 绑定请求失败（A-R2-06）：42901 按 Retry-After（缺省 5 秒）禁用（购买按钮与【重试】置灰，执行层也拦下）；5xxxx 显示对应错误
     * 与 trace6；其它业务码与传输失败显示对应错误。都不清待办、不当成用户取消授权，给【重试】重新查询。业务码不是这几类的（3xxxx 等）按授权失败。
     */
    private fun authCheckFailed(
        context: AuthContext,
        result: ApiResult,
        afterBind: Boolean,
    ) {
        val failure = result as? ApiResult.Failure
        val code = failure?.code
        val millis = ResultSignals.disableMillis(result)
        if (millis > 0) startBlock(millis)
        if (code != null && code != RATE_LIMITED && code !in SERVER_ERRORS) {
            authFailed(context.platform)
            return
        }
        val textKey = if (failure != null) OpenErrorAction.map(failure.code, failure.data).textKey ?: GENERIC_KEY else NETWORK_KEY
        val trace = failure?.traceId?.takeIf { code in SERVER_ERRORS }
        authContext = context
        showDialog(PurchaseDialog.AuthCheckFailed(context.platform, textKey, trace, afterBind))
    }

    /** 授权结果查不到时的【重试】：重新查询授权状态（listUnionBindings）；42901 禁用期间执行层直接拦下。 */
    fun recheckAuth() {
        val dialog = mutableState.value.dialog as? PurchaseDialog.AuthCheckFailed
        val context = authContext.takeIf { !left && !gate.active }
        if (dialog == null || context == null) return
        hideDialog()
        queryBindings(context, dialog.afterBind)
    }

    /**
     * 查询授权状态（listUnionBindings）：查询成功且该平台 active → 自动执行一次 open（新键）；查询成功但没有授权 → 删除待办，给二选一
     * （拼多多回来：未完成授权；淘宝绑定之后：授权失败）；请求失败 → [authCheckFailed]（A-R2-06）。
     */
    private fun queryBindings(
        context: AuthContext,
        afterBind: Boolean,
    ) {
        // 每次查询一个新世代：同一授权上下文的旧查询（重试之前的）也作废（A-R3-01）
        authGen += 1
        val gen = authGen
        val owner = authOwner(context, gen)
        scope.launch {
            val result = api.send(ApiCall(TradeOperations.LIST_UNION_BINDINGS, ownership = owner))
            owners -= owner
            // 返回时核对仍属当前购买尝试与本次查询：新点击之后到达的旧查询只丢弃，不清待办、不关弹窗、不自动 open（A-R3-01）
            if (!authLive(context, gen)) return@launch
            if (result !is ApiResult.Success) {
                authCheckFailed(context, result, afterBind)
                return@launch
            }
            val items = (result.data as? JsonObject)?.get("items") as? JsonArray
            val active =
                items?.any {
                    (it as? JsonObject)?.let { row -> row.text("platform") == context.platform && row.text("status") == ACTIVE } ==
                        true
                }
            when {
                active == true -> {
                    authorized(context, gen)
                }

                afterBind -> {
                    authFailed(context.platform)
                }

                else -> {
                    pending.clear()
                    showDialog(PurchaseDialog.AuthDeclined(context.platform, failed = false))
                }
            }
        }
    }

    /**
     * 回到前台（发起页的生命周期 ON_START）：先执行等前台的自动动作（登录、补同意后的恢复）；拼多多授权跳转之后由服务端确认
     * （listUnionBindings）：确认已授权 → 自动执行一次 open（新键）；查询成功但未授权 → 删除待办，给二选一（BR-ID-22 细则）；查询失败
     * （42901、5xxxx、传输失败）→ [PurchaseDialog.AuthCheckFailed]（A-R2-06）。
     */
    @Suppress("ReturnCount") // 已离开、没有等授权回来、没有授权上下文三个出口
    fun onForeground() {
        if (left) return
        foreground = true
        deferred?.let {
            deferred = null
            it()
        }
        if (!awaitingAuthReturn) return
        awaitingAuthReturn = false
        val context = authContext ?: return
        // 由服务端确认：查询成功才区分已授权 / 未完成授权；限流、服务端故障另行提示，不伪装成用户没授权（A-R2-06）
        queryBindings(context, afterBind = false)
    }

    /**
     * 授权完成：前置完成即自动执行一次 open，用新键（BR-ID-10 / 22 细则）；待办已过期（> 10 分钟）就不执行，等用户再点。
     * 清待办、关弹窗之前核对仍属当前尝试与授权世代 [gen]，并只消费一次（授权上下文置空、世代加 1）；不在前台时延迟恢复，执行前再核对
     * 尝试与世代（期间有新点击就不执行，A-R3-01）。
     */
    private fun authorized(
        context: AuthContext,
        gen: Long,
    ) {
        if (!authLive(context, gen)) return
        authContext = null
        authGen += 1
        val resumeGen = authGen
        pending.stepDone()
        val record = pending.peek()
        pending.clear()
        hideDialog()
        val target = latestCard(context.attempt)
        val mine = record?.text("kind") == PENDING_AUTH && record.text("link_id") == context.linkId
        if (!mine || target.text("link_id") != context.linkId) return
        whenForeground {
            if (resumeStillMine(context, resumeGen)) {
                startClick(target, keys.resendAfterAuth(), noRebate = false, noRebateReason = null, userAuthRetry = false)
            }
        }
    }

    /** 授权完成后的延迟恢复仍属当前尝试：页面没离开、世代没变（期间没有新点击）、尝试仍是那次、没有在途请求或弹窗（A-R3-01）。 */
    private fun resumeStillMine(
        context: AuthContext,
        gen: Long,
    ): Boolean = !left && authGen == gen && current === context.attempt && !busy()

    private fun authFailed(platform: String) {
        pending.clear()
        showDialog(PurchaseDialog.AuthDeclined(platform, failed = true))
    }

    /** 二选一的【重试】、50303 的【重试】：一次新的点击（新键）；二选一里的【重试】是用户主动授权，不受自动弹出次数限制（A-R1-10）。 */
    fun retryPurchase() {
        val dialog = mutableState.value.dialog
        hideDialog()
        authContext = null
        val attempt = current
        buy(attempt?.let(::latestCard) ?: card, spm, userAuthRetry = dialog is PurchaseDialog.AuthDeclined)
    }

    /** 【仍去购买（无返利）】：no_rebate=true 的新请求（BR-ID-18、BR-PRICE-08）。 */
    fun buyWithoutRebate() {
        val dialog = mutableState.value.dialog
        hideDialog()
        val reason =
            when {
                dialog is PurchaseDialog.AuthDeclined && dialog.failed -> NO_REBATE_AUTH_FAILED
                dialog is PurchaseDialog.AuthDeclined -> NO_REBATE_AUTH_DECLINED
                else -> null
            }
        authContext = null
        if (left || busy() || gate.active) return
        val target = current?.let(::latestCard) ?: card
        startClick(target, keys.start(), noRebate = true, noRebateReason = reason, userAuthRetry = false)
    }

    /** 【联系客服】：统一客服出口未接入前经 Router 打开帮助中心（TODO(N-28)）。 */
    fun contactService() {
        hideDialog()
        env.open(HELP_ROUTE, JsonObject(emptyMap()))
    }

    /**
     * 【看看相似商品】：同平台、原样标题打开搜索（BR-PRICE-08）。[product] 是入口所在的那张卡（详情首次进入、没点过购买也有，A-R1-15）；
     * 比价弹窗里不给时用本次购买的卡。
     */
    fun similar(product: JsonObject? = null) {
        hideDialog()
        env.open(SEARCH_ROUTE, similarParams(product ?: card))
    }

    /** 比价说明【查看说明】：/v1/config.help_links.price_compare（没有配置不显示入口）。 */
    fun viewExplanation() {
        env.helpLink(HELP_PRICE_COMPARE)?.let { (route, params) -> env.open(route, params) }
    }

    /** 比价说明入口是否可见。 */
    val explanationAvailable: Boolean get() = env.helpLink(HELP_PRICE_COMPARE) != null

    // ---------------------------------------------------------------- 登录 / 同意恢复、生命周期

    /**
     * 登录完成后（App 在会话由游客变为已登录、待办没有被暂缓时调）：本页登记的购买待办仍有效 → 删除并自动重放一次原尝试（原路径、原请求体、
     * 原键：10001 不写幂等记录，BR-ID-10 细则）。外跳类动作恢复执行仍视为用户点击触发；页面不在前台（登录页还盖着）时等回到前台再执行。
     */
    @Suppress("ReturnCount") // 已离开、不在前台、没有待办、不是本页的出口
    fun resumeAfterLogin() {
        if (left) return
        if (!foreground) return defer(::resumeAfterLogin)
        if (busy()) return
        val record = pending.peek() ?: return
        val attempt = current ?: return
        if (record.text("kind") != PENDING_LOGIN || record.text("link_id") != attempt.linkId) return
        if (record.text("idempotency_key") != attempt.key) return
        pending.clear()
        click += 1
        val replay = attempt.replay(click)
        current = replay
        ready = null
        grantPermit(replay)
        send(replay)
    }

    /**
     * 补同意完成（隐私变为已同意时 App 调）：本页登记的购买待办仍有效 → 删除并按那张卡执行一次购买（还没发过请求，新键）；不在前台时等回到
     * 前台。仍不同意时 Router 删除待办，这里什么都不做（BR-ID-11、03 §4.1）。
     */
    @Suppress("ReturnCount") // 已离开、不在前台、没有待办、不是本页的出口
    fun resumeAfterConsent() {
        if (left) return
        if (!foreground) return defer(::resumeAfterConsent)
        val (target, targetSpm) = consentCard ?: return
        val record = pending.peek() ?: return
        if (record.text("kind") != PENDING_CONSENT || record.text("link_id") != target.text("link_id")) return
        pending.clear()
        consentCard = null
        if (env.basicMode()) return
        buy(target, targetSpm)
    }

    /** 发起页被盖住或 App 进入后台（ON_STOP）：作废续执行许可；在途响应之后只更新卡片（03 §4.5，A-R1-03）。 */
    fun onBackground() {
        foreground = false
        revokePermit()
        if (mutableState.value.phase == PurchasePhase.LOADING) {
            interrupt = LateResponsePolicy.BACKGROUND
            timer?.cancel()
            mutableState.value = mutableState.value.copy(phase = PurchasePhase.IDLE)
        }
        browserDecision?.complete(false)
    }

    /** 页面出栈（同步出口）：作废在途请求并取消传输；本页登记的购买待办删除（不能再自动执行）。 */
    fun leave() {
        if (left) return
        left = true
        interrupt = LateResponsePolicy.LEAVE
        revokePermit()
        deferred = null
        timer?.cancel()
        gateTimer?.cancel()
        browserDecision?.complete(false)
        cancelOwners()
        val mine = listOfNotNull(current?.linkId, consentCard?.first?.text("link_id"), card.text("link_id"))
        pending.peek()?.takeIf { it.text("link_id") in mine }?.let { pending.clear() }
    }

    fun consumeToast() {
        mutableState.value = mutableState.value.copy(toast = null)
    }

    // ---------------------------------------------------------------- 工具

    private fun busy(): Boolean = mutableState.value.phase == PurchasePhase.LOADING || mutableState.value.dialog != null

    private fun grantPermit(attempt: PurchaseAttempt) {
        permit?.valid = false
        permit = Permit(attempt)
    }

    private fun revokePermit() {
        permit?.valid = false
        permit = null
    }

    /** 续执行许可仍有效：是当前尝试的、没作废、页面没离开且在前台。 */
    private fun allowed(granted: Permit): Boolean =
        granted.valid && permit === granted && granted.attempt === current && !left && foreground

    private fun permitted(attempt: PurchaseAttempt): Boolean = permit?.let { it.attempt === attempt && allowed(it) } == true

    private fun whenForeground(action: () -> Unit) {
        if (foreground) action() else defer(action)
    }

    private fun defer(action: () -> Unit) {
        deferred = action
    }

    private fun owner(): CallOwnership = CallOwnership(api.epochs()) { !left }.also { owners += it }

    @Suppress("LongParameterList") // 待办的字段逐项
    private fun pendingParams(
        target: JsonObject,
        linkId: String,
        key: String?,
        kind: String,
        noRebate: Boolean,
        reason: String?,
    ): JsonObject {
        val fields =
            linkedMapOf<String, JsonElement>(
                "kind" to JsonPrimitive(kind),
                "link_id" to JsonPrimitive(linkId),
                "platform" to JsonPrimitive(target.text("platform").orEmpty()),
            )
        target.text("product_key")?.let { fields["product_key"] = JsonPrimitive(it) }
        key?.let { fields["idempotency_key"] = JsonPrimitive(it) }
        if (noRebate) fields["no_rebate"] = JsonPrimitive(true)
        reason?.let { fields["no_rebate_reason"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    private fun showDialog(dialog: PurchaseDialog) {
        mutableState.value = mutableState.value.copy(dialog = dialog)
    }

    private fun hideDialog() {
        mutableState.value = mutableState.value.copy(dialog = null)
    }

    private fun toast(
        key: String,
        traceId: String? = null,
    ) {
        toastSeq += 1
        mutableState.value = mutableState.value.copy(toast = PurchaseToast(key, traceId, toastSeq))
    }

    private fun startBlock(millis: Long) {
        gate.start(millis)
        mutableState.value = mutableState.value.copy(rateLimited = true)
        gateTimer?.cancel()
        gateTimer =
            scope.launch {
                while (gate.active) delay(gate.remainingMillis())
                if (!left) mutableState.value = mutableState.value.copy(rateLimited = false)
            }
    }

    private fun cancelOwners() {
        val cancelled = owners.toList()
        owners.clear()
        cancelled.forEach(api::cancelOwned)
    }

    companion object {
        /** TRADE-22：open 最多等 8 秒。 */
        const val TIMEOUT_MILLIS = 8_000L

        /** 「将通过浏览器打开」的提示停留时长（可取消）。 */
        const val BROWSER_NOTICE_MILLIS = 1_200L

        const val LOGIN_ROUTE = "Login"
        const val SEARCH_ROUTE = "Search"
        const val HELP_ROUTE = "Help"
        const val HELP_PRICE_COMPARE = "price_compare"

        const val INSTALLED_TRUE = "true"
        const val INSTALLED_FALSE = "false"
        const val INSTALLED_UNKNOWN = "unknown"
        const val NO_REBATE_AUTH_DECLINED = "auth_declined"
        const val NO_REBATE_AUTH_FAILED = "auth_failed"

        // 文案键（契约有的读契约，没有的在 App 的草稿表，TODO(N-28)）
        const val MAINTENANCE_KEY = "error.50301.maintenance"
        const val OFF_SHELF_KEY = "error.30141"
        const val REF_EXPIRED_KEY = "error.30144"
        const val RATE_LIMITED_KEY = "error.42901"
        const val GENERIC_KEY = "error.50001"
        const val COPIED_KEY = "share_page.copy_tpwd.done"
        const val NOT_INSTALLED_COPIED_KEY = "trade.not_installed.copied"
        const val REQUOTE_KEY = "trade.requote_notice"
        const val JUMP_FAILED_KEY = "trade.jump_failed"
        const val JUMP_EXPIRED_KEY = "trade.jump_expired"
        const val CANCELLED_PRICE_KEY = "trade.cancelled.price"
        const val CANCELLED_PRICE_REBATE_KEY = "trade.cancelled.price_rebate"
        const val CANCELLED_REBATE_KEY = "trade.cancelled.rebate"

        internal const val PENDING_LOGIN = "login"
        internal const val PENDING_AUTH = "auth"
        internal const val PENDING_CONSENT = "consent"
        const val NETWORK_KEY = "buy.timeout"
        private const val RATE_LIMITED = 42901
        private val SERVER_ERRORS = 50_000..59_999
        private const val TIMEOUT = "timeout"
        private const val AMOUNT_UNKNOWN = "amount_unknown"
        private const val AUTH_UNAVAILABLE = "auth_unavailable"
        private const val ACTIVE = "active"
        private const val SPM_MAX = 128
        private const val TITLE_MAX = 60

        /** 相似商品的搜索参数：同平台、原样标题（截前 60 字；排除同 product_key 待契约补参数，A04）。 */
        fun similarParams(product: JsonObject): JsonObject {
            val fields = linkedMapOf<String, JsonElement>()
            product.text("title")?.takeIf(String::isNotEmpty)?.let { fields["q"] = JsonPrimitive(it.take(TITLE_MAX)) }
            product.text("platform")?.takeIf(String::isNotEmpty)?.let { fields["platform"] = JsonPrimitive(it) }
            return JsonObject(fields)
        }
    }
}

/**
 * open 响应 → 卡片（BR-PRICE-12、03 §7.4「无论确认还是取消，都用新价格与 new_link_id 替换卡片」）。只写响应确定的字段，不反推（A-R1-07 / 08，
 * BR-PRICE-17）：
 * - new_link_id 替换 link_id；quoted_at 换新；availability=off_shelf 照写；
 * - 券后价变了：final_price_fen 换新，券前价与券额响应没有 → 置未知（null，不用旧券额反推）；coupon_gone 只说明原券失效、不等于当前无券
 *   （可能换了新券，BR-PRICE-14，A-R3-02）：同样置未知，先去掉「有券」、按钮「领券购买」改「去购买」，重取补齐券额后 [fill] 再同步回来；
 *   两种都把预估返后置未知；
 * - 返利上限由 > 0 变为 0 → 暂无返利（rebate_basis=no_rebate、按钮「去购买（无返利）」、no_rebate_cause 原样），去掉返利口径句；
 * - 返利由 0 变为正数：清掉暂无返利的状态、原因与「去购买（无返利）」（口径响应没有 → rebate_basis 置未知，并标临时展示状态
 *   [ProductCardMapper.PROVISIONAL_REBATE]：按服务端区间显示，A-R2-01）；其余返利变化换新区间；预估返后置未知。金额未知（null）保持未知、不补 0。
 * 改价、券失效、返利变化（不含变为 0）时 [needsRefetch]：调用方按 product_key + item_ref 重取权威卡片，用 [fill] 只补齐仍未知的字段（A-R2-02）；
 * 30144 用 [reload] 整卡替换但不覆盖之后写入的购买结果（卡片版本 [revision]）。
 */
object CardPatch {
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "LongMethod") // 逐字段
    fun apply(
        card: JsonObject,
        data: JsonObject,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(card)
        data.text("new_link_id")?.let { out["link_id"] = JsonPrimitive(it) }
        val couponGone = data.text("availability") == COUPON_GONE
        val newFinal = data.fen("new_final_price_fen")
        if (priceChanged(card, data)) {
            out["final_price_fen"] = newFinal?.let(::JsonPrimitive) ?: JsonNull
            // 券前价与券额响应不带：一律置未知（coupon_gone 只说明原券失效，不等于当前无券，可能换了新券，A-R3-02），等重取补齐
            out["coupon_fen"] = JsonNull
            out["price_fen"] = JsonNull
            out["est_net_price_fen"] = JsonNull
            if (couponGone) {
                // 原券已失效、新券未知：先去掉「有券」、按钮不再称「领券购买」；补齐后由 [fill] 按实际券额同步回来
                out["benefit_tags"] = JsonArray(card.texts("benefit_tags").filter { it != PriceTerms.HAS_COUPON }.map(::JsonPrimitive))
                (card["cta"] as? JsonObject)?.takeIf { it.text("text_key") == BUY_COUPON }?.let {
                    out["cta"] = JsonObject(it + ("text_key" to JsonPrimitive(BUY)))
                }
            }
        }
        val newMax = data.fen("new_rebate_max_fen")
        val newMin = data.fen("new_rebate_min_fen")
        val oldMax = card.fen("rebate_max_fen")
        if (newMax == 0L && (oldMax ?: 0L) > 0L) {
            out["rebate_min_fen"] = JsonPrimitive(0L)
            out["rebate_max_fen"] = JsonPrimitive(0L)
            out["rebate_basis"] = JsonPrimitive(NO_REBATE)
            out.remove(ProductCardMapper.PROVISIONAL_REBATE)
            out["est_net_price_fen"] = JsonNull
            out["no_rebate_cause"] =
                data["no_rebate_cause"]?.takeIf { it is JsonPrimitive && it.isString && it.content == PRICE_COMPARE } ?: JsonNull
            out["cta"] = JsonObject(mapOf("text_key" to JsonPrimitive(BUY_NO_REBATE)))
            out["disclaimer_keys"] = JsonArray(card.texts("disclaimer_keys").filter { !it.startsWith(REBATE_PREFIX) }.map(::JsonPrimitive))
        } else if (newMax != null && newMin != null && (newMax != oldMax || newMin != card.fen("rebate_min_fen"))) {
            out["rebate_min_fen"] = JsonPrimitive(newMin)
            out["rebate_max_fen"] = JsonPrimitive(newMax)
            out["est_net_price_fen"] = JsonNull
            if (newMax > 0L && restored(card)) {
                // 返利从无到有：不留「暂无返利 / 去购买（无返利）」与比价原因；口径响应没有 → 置未知并标临时展示状态，按服务端区间显示，
                // 等重取的权威卡片补口径；重取失败就一直按区间显示，不落入「暂无返利」（A-R1-08、A-R2-01）
                out["rebate_basis"] = JsonNull
                out[ProductCardMapper.PROVISIONAL_REBATE] = JsonPrimitive(true)
                out["no_rebate_cause"] = JsonNull
                // 券额未知（同时改价）与确定无券不同（A-R3-03）：未知时先给「去购买」，补齐后由 [fill] 按实际券额同步为「领券购买」
                val coupon = (out["coupon_fen"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
                out["cta"] = JsonObject(mapOf("text_key" to JsonPrimitive(if (coupon != null && coupon > 0L) BUY_COUPON else BUY)))
            }
        }
        data.text("quoted_at")?.let { out["quoted_at"] = JsonPrimitive(it) }
        data.text("availability")?.takeIf { it == OFF_SHELF }?.let { out["availability"] = JsonPrimitive(it) }
        return JsonObject(out)
    }

    /** 响应给不全卡片要的字段（券前价、券额、预估返后、返利口径），要按 product_key + item_ref 重取权威卡片（A-R1-07 / 08）。 */
    @Suppress("ReturnCount") // 改价、返利缺失、归零三个出口
    fun needsRefetch(
        card: JsonObject,
        data: JsonObject,
    ): Boolean {
        if (priceChanged(card, data)) return true
        val newMax = data.fen("new_rebate_max_fen") ?: return false
        val newMin = data.fen("new_rebate_min_fen") ?: return false
        if (newMax == 0L) return false
        return newMax != card.fen("rebate_max_fen") || newMin != card.fen("rebate_min_fen") || restored(card)
    }

    /** 同一张卡：card_id 相同；没有 card_id 时 product_key 相同（换了 link_id 也认得出）。 */
    fun sameCard(
        a: JsonObject,
        b: JsonObject,
    ): Boolean {
        val id = a.text("card_id")
        return if (id != null) id == b.text("card_id") else a.text("product_key") != null && a.text("product_key") == b.text("product_key")
    }

    private fun priceChanged(
        card: JsonObject,
        data: JsonObject,
    ): Boolean {
        val newFinal = data.fen("new_final_price_fen") ?: return false
        return newFinal != card.fen("final_price_fen") || data.text("availability") == COUPON_GONE
    }

    /**
     * 补齐价格字段（A-R2-02，APP-06b 共性第 17 条）：[current] 是此刻页面上的卡（已写入本次 open 确认的结果），[fresh] 是重取到的权威卡。
     * 详情 / 搜索接口的报价不带本次 open 的比价预判（BR-PRICE-07 / 13：比价预判只针对本次 open，不写入搜索、详情缓存），所以通用报价仍有返利
     * 是正常的，不能用它整卡替换：
     * - link_id、返利区间、返利口径（含 no_rebate）、no_rebate_cause、按钮、可购状态、取价时间一律保留 [current] 的；
     * - 券前价、券额只在此刻仍未知、且 [fresh] 的券后价与 [current] 一致时补上（价格对不上说明不是同一份报价，不补）；券额由未知补成已知时
     *   同步「有券」标签与普通购买按钮（领券购买 / 去购买，A-R3-02 / 03），「去购买（无返利）」不动；
     * - 临时展示状态：[fresh] 的区间与 [current] 一致且口径为 normal / price_compare_risk 时补上口径（连同返利口径句）、去掉临时标记；
     * - 预估返后只在仍未知、价格与区间都一致、口径相同时补上。
     * 不论卡片版本（之后又写进购买结果也一样）：只补未知字段、且以 [current] 为准，旧结果不会盖掉新的购买结果。
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "ComplexCondition") // 逐字段
    fun fill(
        current: JsonObject,
        fresh: JsonObject,
    ): JsonObject {
        val key = current.text("product_key")
        if (key != null && fresh.text("product_key") != null && fresh.text("product_key") != key) return current
        val out = LinkedHashMap<String, JsonElement>(current)
        val final = current.fen("final_price_fen")
        val samePrice = final != null && fresh.fen("final_price_fen") == final
        val missing = if (samePrice) listOf("price_fen", "coupon_fen").filter { current.fen(it) == null } else emptyList()
        missing.forEach { field -> fresh.fen(field)?.let { out[field] = JsonPrimitive(it) } }
        // 券额由未知补成已知：同步「有券」标签与普通购买按钮（A-R3-02 / 03）；open 确认的「去购买（无返利）」与比价原因不动（A-R2-02）
        if ("coupon_fen" in missing) fresh.fen("coupon_fen")?.let { syncCoupon(out, it > 0L) }
        val min = current.fen("rebate_min_fen")
        val max = current.fen("rebate_max_fen")
        val sameRebate = min != null && max != null && fresh.fen("rebate_min_fen") == min && fresh.fen("rebate_max_fen") == max
        val freshBasis = fresh.text("rebate_basis")
        if (ProductCardMapper.provisionalRebate(current) && sameRebate && freshBasis in RANGE_BASES) {
            out["rebate_basis"] = JsonPrimitive(freshBasis!!)
            out.remove(ProductCardMapper.PROVISIONAL_REBATE)
            val kept = current.texts("disclaimer_keys").filter { !it.startsWith(REBATE_PREFIX) }
            val rebateKeys = fresh.texts("disclaimer_keys").filter { it.startsWith(REBATE_PREFIX) }
            out["disclaimer_keys"] = JsonArray((kept + rebateKeys).map(::JsonPrimitive))
        }
        val basis = (out["rebate_basis"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (current.fen("est_net_price_fen") == null && samePrice && sameRebate && basis in RANGE_BASES && basis == freshBasis) {
            fresh.fen("est_net_price_fen")?.let { out["est_net_price_fen"] = JsonPrimitive(it) }
        }
        return JsonObject(out)
    }

    /**
     * 重新加载（30144，A-R1-09 / A-R2-02）：整卡换成权威卡（列表认卡用的 card_id 沿用原卡），但请求发出时卡片版本是 [expected]、到达时
     * 版本已变（之后又写进了购买结果）就不覆盖，保留新的。
     */
    fun reload(expected: Long): (JsonObject, JsonObject) -> JsonObject =
        { current, fresh ->
            if (revision(current) != expected) {
                current
            } else {
                val out = LinkedHashMap<String, JsonElement>(fresh)
                if (out["card_id"] == null) current["card_id"]?.let { out["card_id"] = it }
                out[REVISION] = JsonPrimitive(expected + 1)
                JsonObject(out)
            }
        }

    /** 卡片版本（客户端字段，每写入一次购买结果加 1；接口的卡没有 = 0）。 */
    fun revision(card: JsonObject): Long = card.fen(REVISION) ?: 0L

    /** 版本加 1。 */
    fun bump(card: JsonObject): JsonObject = card.with(REVISION, JsonPrimitive(revision(card) + 1))

    /** 按已确认的券额同步「有券」标签与普通购买按钮（「领券购买」↔「去购买」）；其它按钮（含「去购买（无返利）」）不动。 */
    private fun syncCoupon(
        out: MutableMap<String, JsonElement>,
        hasCoupon: Boolean,
    ) {
        val tags = JsonObject(out).texts("benefit_tags").filter { it != PriceTerms.HAS_COUPON }
        out["benefit_tags"] = JsonArray((if (hasCoupon) listOf(PriceTerms.HAS_COUPON) + tags else tags).map(::JsonPrimitive))
        val cta = out["cta"] as? JsonObject ?: return
        val key = cta.text("text_key")
        if (key == BUY || key == BUY_COUPON) {
            out["cta"] = JsonObject(cta + ("text_key" to JsonPrimitive(if (hasCoupon) BUY_COUPON else BUY)))
        }
    }

    /** 卡片原来是暂无返利。 */
    private fun restored(card: JsonObject): Boolean = card.text("rebate_basis") == NO_REBATE || card.fen("rebate_max_fen") == 0L

    /** 卡片版本的客户端字段名（不来自接口，不上送）。 */
    const val REVISION = "client_card_rev"
    private val RANGE_BASES = setOf("normal", "price_compare_risk")
    private const val BUY_COUPON = "btn.buy.coupon"
    private const val BUY = "btn.buy"
    private const val BUY_NO_REBATE = "btn.buy.no_rebate"
    private const val NO_REBATE = "no_rebate"
    private const val PRICE_COMPARE = "price_compare"
    private const val REBATE_PREFIX = "rebate_"
    private const val COUPON_GONE = "coupon_gone"
    private const val OFF_SHELF = "off_shelf"
}

internal fun JsonObject.with(
    key: String,
    value: JsonElement,
): JsonObject = JsonObject(this + (key to value))
