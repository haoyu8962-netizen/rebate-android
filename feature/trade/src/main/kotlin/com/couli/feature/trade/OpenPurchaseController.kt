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

    /**
     * 返利变为 0（ProductRebateGone）。[oldFen] / [newFen] 是本次 open 的原价与新价（价格变动未达阈值时可能不同，共性第 45 条：只有相同才写
     * 「价格没有变化」）。
     */
    data class ConfirmRebateGone(
        val priceFen: Long?,
        val oldFen: Long? = null,
        val newFen: Long? = priceFen,
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
    /**
     * 当前尝试那张卡的最新展示版本（open 响应写入、权威重取补齐之后）：确认框「现在」一行的价格口径词按它选（券额未补齐用不带口径词的
     * now_plain，共性第 30、37、45 条）。
     */
    val latest: JsonObject? = null,
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

    /**
     * 自动执行的动作（授权、登录、补同意后的恢复）遇到页面不在前台：回到前台再执行。按类型分槽（共性第 48 条）：同类后写覆盖先写，
     * 不同类互不覆盖；回前台按授权、登录、补同意的顺序逐槽执行，各自执行前仍核对待办类型、归属、尝试、有效期与 link_id。
     */
    private val deferred = linkedMapOf<DeferredResume, () -> Unit>()
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
     * 授权进行中（APP-07 剪贴板敏感浮层，BR-ID-16、G12）：AuthSheet 正在显示 → auth_sheet；已交出授权跳转、等回到前台确认（auth_jump 执行中）
     * → auth_jump；都不是为 null。
     */
    val authOverlay: String?
        get() =
            when {
                awaitingAuthReturn || (mutableState.value.dialog as? PurchaseDialog.AuthSheet)?.busy == true -> OVERLAY_AUTH_JUMP
                mutableState.value.dialog is PurchaseDialog.AuthSheet -> OVERLAY_AUTH_SHEET
                else -> null
            }

    /** AuthSheet 出现时同步调（App 接剪贴板的作废出口，APP-07 A-R1-04）；缺省什么都不做。 */
    var onAuthOverlay: () -> Unit = {}

    /**
     * 授权世代（A-R3-01，APP-06b 共性第 20 条）：每次发出授权状态查询、开始一次授权流程、授权完成被消费、每次发出新的 open 请求都加 1。
     * 授权查询的归属、授权流程的结果、授权完成后的延迟恢复都绑定发出时的世代与购买尝试；世代变了就作废（旧查询不得关掉新尝试的确认窗）。
     */
    private var authGen = 0L
    private var browserDecision: CompletableDeferred<Boolean>? = null

    /** 基本模式下点购买、等补同意的那张卡与 spm。 */
    private var consentCard: Pair<JsonObject, String?>? = null

    /**
     * 本页的待办归属标记（共性第 44 条）：本页登记的购买待办都带「页标记#点击编号」（[OWNER]），清除、续时、消费之前先核对归属，
     * 旧页面迟到的授权查询结果不会清掉新页面登记的待办。
     */
    private val pageTag: String = "page-${PAGE_SEQ.incrementAndGet()}"

    /**
     * 授权外跳许可（共性第 25、31 条）：用户在 AuthSheet 点【去授权】时发一张；进入后台、被覆盖、离开、新点击都作废，回到前台也不恢复。
     * 授权跳转每一步、30104 恢复后再次拉起授权之前都复核它与前台状态。授权回流的查询与它分开（只看授权上下文与世代）。
     */
    private var authPermit: AuthPermit? = null

    /** 授权跳转已发起、拉起结果还没回来（拉起的回调可能晚于回前台通知，共性第 38 条）。 */
    private var authJumpInFlight = false

    /** 授权跳转发起之后进过后台（已离开过 App）：拉起结果晚到且此刻在前台时立即查询授权状态。 */
    private var authLeftApp = false

    private class Permit(
        val attempt: PurchaseAttempt,
    ) {
        /** 允许开始下一步（进入后台、被覆盖、取消、新点击都作废）。 */
        var valid = true

        /** 用户取消、新点击、离开：已执行的步骤也不再上报（共性第 40 条：进入后台不算取消，已执行步骤照常上报）。 */
        var cancelled = false
    }

    private class AuthPermit {
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

    @Suppress("ReturnCount") // 忙、可购状态、限流、基本模式四个出口
    private fun buy(
        card: JsonObject,
        spm: String?,
        userAuthRetry: Boolean,
    ) {
        if (left || busy()) return
        val platform = card.text("platform").orEmpty()
        // 下架绑定这张卡（30141 / availability=off_shelf，A-R1-05）；发出购买意图前再核对一次平台购买状态（APP-06a 共性第 27 条；含点击
        // 结果确认的 50301）
        if (!purchasable(card)) return
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
        pending.register(pendingParams(card, linkId, key = null, kind = PENDING_CONSENT, noRebate = false, reason = null, owner = CONSENT))
        env.requestConsent()
    }

    /** 加载层【取消】：只挡本机外跳，之后到达的响应只更新卡片（03 §4.5）。 */
    fun cancelLoading() {
        if (mutableState.value.phase != PurchasePhase.LOADING) return
        interrupt = LateResponsePolicy.CANCEL
        revokePermit(cancel = true)
        keys.cancel()
        timer?.cancel()
        mutableState.value = mutableState.value.copy(phase = PurchasePhase.IDLE)
    }

    /**
     * 超时后的【重试】：完整重放原尝试（原路径、原请求体、原键，TRADE-22）；用户这一下重新发许可。重发前复核最新平台购买状态与这张卡的
     * 可购状态（共性第 27 条）：不可买就放弃本次（不再发同键请求），超时条收起。
     */
    @Suppress("ReturnCount") // 状态不对、没有尝试、不可购三个出口
    fun retryAfterTimeout() {
        if (left || mutableState.value.phase != PurchasePhase.TIMEOUT || gate.active) return
        val attempt = current ?: return
        if (!purchasable(latestCard(attempt))) {
            mutableState.value = mutableState.value.copy(phase = PurchasePhase.IDLE)
            return
        }
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
        // 所有发新 open 的入口（点击、授权后自动执行、无返利购买）都经同一个可购检查（共性第 27 条）
        if (!purchasable(card)) return
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
        mutableState.value = mutableState.value.copy(platform = attempt.platform, card = card, notInstalled = false, latest = card)
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
        val outcome = OpenResponseAction.map(envelope, openContext(attempt.card, noRebateConfirmed = attempt.noRebate))
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
                showDialog(
                    PurchaseDialog.ConfirmRebateGone(
                        priceFen = outcome.newFinalFen ?: outcome.oldFinalFen,
                        oldFen = outcome.oldFinalFen,
                        newFen = outcome.newFinalFen,
                    ),
                )
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
                    pendingParams(
                        attempt.card,
                        attempt.linkId,
                        attempt.key,
                        PENDING_LOGIN,
                        attempt.noRebate,
                        attempt.noRebateReason,
                        owner = ownerTag(attempt),
                    ),
                )
                env.open(LOGIN_ROUTE, JsonObject(emptyMap()))
            }

            OpenErrorActions.AUTH_SHEET, OpenErrorActions.AUTH_JUMP -> {
                keys.authRequired(failure.code)
                authContext = AuthContext(failure.code, attempt.linkId, failure.data, attempt)
                // 自动弹出每平台每会话最多 2 次（BR-ID-18）；用户在二选一里主动【重试】进来的不算自动弹出、不受限（A-R1-10）
                if (attempt.userAuthRetry || counter.tryAutoPop(platform)) {
                    pending.register(
                        pendingParams(
                            attempt.card,
                            attempt.linkId,
                            key = null,
                            kind = PENDING_AUTH,
                            noRebate = false,
                            reason = null,
                            owner = ownerTag(attempt),
                        ),
                    )
                    showDialog(PurchaseDialog.AuthSheet(platform))
                } else {
                    // 同一会话第 3 次起不再自动拉起授权，直接给二选一（BR-ID-18）
                    clearMine()
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

    /**
     * open 上下文：[base] 卡片的价格与返利上限（OpenResponseAction 判返利归零用）；[noRebateConfirmed] = 这次是用户已确认的无返利购买
     * （不再弹比价无返利确认，共性第 43 条）。
     */
    private fun openContext(
        base: JsonObject,
        noRebateConfirmed: Boolean,
    ): JsonObject {
        val unknown = base.text("rebate_basis") == AMOUNT_UNKNOWN
        val fields =
            linkedMapOf<String, JsonElement>(
                "currentPriceFen" to (base.fen("final_price_fen")?.let(::JsonPrimitive) ?: JsonNull),
                "currentRebateMaxFen" to (base.fen("rebate_max_fen")?.let(::JsonPrimitive) ?: JsonNull),
            )
        if (unknown) fields["amountUnknown"] = JsonPrimitive(true)
        if (noRebateConfirmed) fields["noRebateConfirmed"] = JsonPrimitive(true)
        return JsonObject(fields)
    }

    /** 尝试那张卡的最新展示版本。 */
    private fun latestCard(attempt: PurchaseAttempt): JsonObject = if (CardPatch.sameCard(card, attempt.card)) card else attempt.card

    /**
     * 用响应更新卡片（BR-PRICE-12：无论确认还是取消）；价格 / 返利字段给不全时重取权威卡片（A-R1-07 / 08）。补齐请求带补齐世代
     * （共性第 26 条）：之后又写入了不同的购买结果，旧补齐不填新结果；补齐世代只在真正发起新补齐时前进（共性第 33 条）——同键重放得到
     * 相同结果（写入后卡片不变）不作废进行中的补齐；不同结果一律作废旧补齐，仍有未知字段就为它重新发起补齐。
     */
    private fun patchCard(
        attempt: PurchaseAttempt,
        data: JsonObject,
    ) {
        val before = latestCard(attempt)
        cardBeforeResponse = attempt.card
        val applied = CardPatch.apply(before, data)
        val unchanged = CardPatch.sameResult(before, applied)
        val refetch = !unchanged && (CardPatch.needsRefetch(before, data) || CardPatch.awaitingFill(applied))
        if (!refetch) {
            val generation = if (unchanged) null else nextFillGeneration()
            updateCard(attempt) { c ->
                CardPatch.apply(c, data).let {
                    if (generation !=
                        null
                    ) {
                        it.with(CardPatch.FILL_GENERATION, JsonPrimitive(generation))
                    } else {
                        it
                    }
                }
            }
            return
        }
        val generation = nextFillGeneration()
        val write: (JsonObject) -> JsonObject = { CardPatch.apply(it, data).with(CardPatch.FILL_GENERATION, JsonPrimitive(generation)) }
        val after = CardPatch.bump(write(before))
        updateCard(attempt, write)
        // 只补齐价格 / 口径字段，不整卡替换：本次 open 确认的 link_id、返利状态、比价原因与按钮保留（A-R2-02，BR-PRICE-07 / 13）
        val fill = CardPatch.fillFor(generation)
        env.refetchCard(after) { current, fresh -> fill(current, fresh).also { noteLatest(attempt, it) } }
    }

    /** 补齐落到页面上的结果：仍是当前尝试的那张卡就同步给执行器与界面（确认框的价格口径词随之更新）。 */
    private fun noteLatest(
        attempt: PurchaseAttempt,
        merged: JsonObject,
    ) {
        if (left || current !== attempt || !CardPatch.sameCard(merged, attempt.card)) return
        if (CardPatch.sameCard(card, attempt.card)) card = merged
        mutableState.value = mutableState.value.copy(latest = merged)
    }

    /** 写入购买结果（open 响应、30141 下架）：卡片版本加 1，之前发出的重新加载结果到达时认出卡片已更新、不覆盖（A-R2-02）。 */
    private fun updateCard(
        attempt: PurchaseAttempt,
        transform: (JsonObject) -> JsonObject,
    ) {
        val versioned: (JsonObject) -> JsonObject = { CardPatch.bump(transform(it)) }
        if (CardPatch.sameCard(card, attempt.card)) {
            card = versioned(card)
            if (current === attempt) mutableState.value = mutableState.value.copy(latest = card)
        }
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
                // 继续之前复核最新平台购买状态与这张卡的可购状态（共性第 27、32 条）
                jump?.takeIf { it.attempt === current && purchasable(latestCard(it.attempt)) }?.let {
                    grantPermit(it.attempt)
                    proceed(it)
                } ?: run { ready = null }
            }

            is PurchaseDialog.JumpTip -> {
                hideDialog()
                // 已读请求的归属绑定这次购买尝试与请求世代（F-AND-19）：新点击取消它时归属已失效，不会被当成断连重试
                jump?.attempt?.let { attempt ->
                    val owner = attemptOwner(attempt)
                    scope.launch { tips.markRead(dialog.platform, owner) }
                }
                jump?.takeIf { it.attempt === current && purchasable(latestCard(it.attempt)) }?.let {
                    grantPermit(it.attempt)
                    execute(it)
                } ?: run { ready = null }
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
                clearMine()
                showDialog(PurchaseDialog.AuthDeclined(dialog.platform, failed = false))
            }

            is PurchaseDialog.AuthCheckFailed -> {
                // 放弃本次：删除待办（之后授权了也不自动执行）
                hideDialog()
                clearMine()
                authContext = null
            }

            else -> {
                hideDialog()
            }
        }
        ready = null
        revokePermit(cancel = true)
    }

    private fun proceed(jump: ReadyJump) {
        val granted = permit?.takeIf { it.attempt === jump.attempt } ?: return
        if (!env.loggedIn()) return execute(jump)
        // 须知请求的归属绑定这次购买尝试与请求世代（F-AND-19）
        val owner = attemptOwner(jump.attempt)
        scope.launch {
            val show = tips.shouldShow(jump.attempt.platform, owner)
            // 等待之后复核许可：期间进入后台、被覆盖、离开、再点了别的都不再继续（A-R1-03）
            if (!allowed(granted) || ready !== jump) return@launch
            // 等下单须知期间平台关闭 / 维护、商品下架：停止续执行（共性第 32 条）；按钮随配置与卡片状态置灰
            if (!purchasable(latestCard(jump.attempt))) {
                ready = null
                return@launch
            }
            if (show) showDialog(PurchaseDialog.JumpTip(jump.attempt.platform)) else execute(jump)
        }
    }

    @Suppress("ReturnCount") // 没有许可、许可失效、不可购三个出口
    private fun execute(jump: ReadyJump) {
        val granted = permit?.takeIf { it.attempt === jump.attempt } ?: return
        if (!allowed(granted)) return
        ready = null
        // 最终外跳前统一复核最新平台购买状态与这张卡的可购状态（共性第 32 条）：须知、确认框等异步等待之后的路径都经这里
        if (!purchasable(latestCard(jump.attempt))) return
        scope.launch {
            // 执行器的两种许可分开（共性第 40 条）：「允许开始下一步」每个新步骤前（含 scheme 等系统返回、浏览器提示停留之后）复核续执行
            // 许可 + 最新平台购买状态与可购状态；「允许上报已执行步骤」只看尝试与取消，已执行的步骤照常上报
            val outcome =
                executor.execute(
                    jump.plan,
                    jump.attempt.platform,
                    jump.attempt.installed,
                    jump.identity,
                    allowed = { continuable(granted) },
                    confirmBrowser = { confirmBrowser(granted) },
                    reportable = { reportable(granted) },
                )
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

    /**
     * AuthSheet【去授权】。用户这一下发授权外跳许可（共性第 25、31 条）：不在前台不拉起；进入后台、被覆盖、离开、新点击都作废，回前台
     * 也不恢复。授权跳转每一步、30104 恢复后再次拉起授权之前都复核它。
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "ComplexCondition", "LongMethod") // 拼多多跳转、淘宝两种方式与本端能力逐项判定
    fun authorize() {
        val context = authContext ?: return
        val dialog = mutableState.value.dialog as? PurchaseDialog.AuthSheet ?: return
        if (dialog.busy || !foreground) return
        mutableState.value = mutableState.value.copy(dialog = dialog.copy(busy = true))
        authGen += 1
        val gen = authGen
        revokeAuthPermit()
        val permit = AuthPermit().also { authPermit = it }
        resetAuthReturn()
        val authJump = context.data["auth_jump"] as? JsonObject
        if (authJump != null) {
            // 发起前就登记「外跳在途」：拉起的回调可能晚于回前台通知（共性第 38 条）
            authJumpInFlight = true
            scope.launch {
                val outcome =
                    executor.execute(
                        authJump,
                        context.platform,
                        ports.installed(context.platform),
                        identity = null,
                        allowed = { authJumpAllowed(permit, context, gen) },
                    )
                // 授权回流（回前台后查询）的归属与外跳许可分开：这里只看授权上下文仍是本次
                if (!authLive(context, gen)) return@launch
                authJumpInFlight = false
                val launched = outcome.run.final == JumpTypes.FINAL_OPENED || outcome.run.final == JumpTypes.FINAL_BROWSER
                if (launched || outcome.stopped) {
                    // 已拉起授权页，或期间进入后台 / 被覆盖（含系统弹出应用市场）而停在半路、不再执行下一步：回到前台后由服务端确认是否已授权
                    // （[onForeground] → listUnionBindings；查不到授权按「没完成就回来」给二选一，BR-ID-22 细则）
                    hideDialog()
                    awaitReturn(context, gen, returned = !launched || authLeftApp)
                } else {
                    authFailed(context.platform)
                }
            }
            return
        }
        val flow = taobaoFlow(context, gen, permit)
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
                    clearMine()
                    showDialog(PurchaseDialog.AuthDeclined(context.platform, failed = false))
                }

                TaobaoAuthOutcome.Failed -> {
                    authFailed(context.platform)
                }

                is TaobaoAuthOutcome.RequestFailed -> {
                    bindFailed(context, outcome.result)
                }

                TaobaoAuthOutcome.Interrupted -> {
                    // 30104 恢复后要再次拉起时，用户点击发出的许可已撤销（进过后台 / 被覆盖，共性第 31 条）：不再自动拉起；回到前台后向服务端
                    // 查询授权状态，已授权自动 open 一次，没授权删除待办给二选一（共性第 39 条）
                    hideDialog()
                    awaitReturn(context, gen, returned = true)
                }

                TaobaoAuthOutcome.Abandoned -> {
                    // 要拉起授权时已不在前台（还没拉起过）：不外跳，AuthSheet 回到可点状态，等用户在前台再点【去授权】
                    (mutableState.value.dialog as? PurchaseDialog.AuthSheet)?.takeIf { it.busy }?.let {
                        mutableState.value = mutableState.value.copy(dialog = it.copy(busy = false))
                    }
                }
            }
        }
    }

    /**
     * 授权外跳之后等回流（共性第 38 条）：[returned] = 已经离开过 App 又回到前台（或停在半路）——此刻在前台就立即查询；否则等
     * [onForeground]。
     */
    private fun awaitReturn(
        context: AuthContext,
        gen: Long,
        returned: Boolean,
    ) {
        authJumpInFlight = false
        if (returned && foreground) {
            awaitingAuthReturn = false
            authLeftApp = false
            if (authLive(context, gen)) queryBindings(context, afterBind = false)
        } else {
            awaitingAuthReturn = true
        }
    }

    private fun resetAuthReturn() {
        awaitingAuthReturn = false
        authJumpInFlight = false
        authLeftApp = false
    }

    /** 授权外跳此刻可以继续：许可仍有效、授权上下文与世代仍是本次、页面在前台（共性第 25 条）。 */
    private fun authJumpAllowed(
        permit: AuthPermit,
        context: AuthContext,
        gen: Long,
    ): Boolean = permit.valid && authPermit === permit && authLive(context, gen) && foreground

    private fun revokeAuthPermit() {
        authPermit?.valid = false
        authPermit = null
    }

    private fun taobaoFlow(
        context: AuthContext,
        gen: Long,
        permit: AuthPermit,
    ): TaobaoAuthFlow =
        TaobaoAuthFlow(
            api,
            webCode,
            baichuan,
            keyGenerator,
            owner = { authOwner(context, gen) },
            alive = { authLive(context, gen) },
            installed = ports::installed,
            // 每次拉起授权（含 30104 恢复后的再次授权）之前：用户点【去授权】发出的许可仍有效、页面在前台（共性第 25、31 条）
            canJump = { authJumpAllowed(permit, context, gen) },
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
        revokeAuthPermit()
        resetAuthReturn()
        if (old != null) {
            pending
                .peek()
                ?.takeIf { it.text("kind") == PENDING_AUTH && it.text(OWNER) == ownerTag(old.attempt) }
                ?.let { pending.clear() }
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
                clearMine()
                val outcome = OpenErrorAction.map(failure.code, failure.data)
                showDialog(PurchaseDialog.Notice(outcome.textKey, null, noRebate = true, contact = true, retry = false))
            }

            failure != null && (failure.code == 30101 || failure.code == 30102) && failure.data.text("reason") == AUTH_UNAVAILABLE -> {
                clearMine()
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
                    // 查询成功但没有授权（含授权中断、30104 恢复被撤销后回前台查得未授权，共性第 39 条）：删除本次待办，给二选一
                    clearMine()
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
        // 按类型逐槽执行（共性第 48 条）：授权、登录、补同意的恢复各自核对，不互相覆盖
        for (kind in DeferredResume.entries) {
            deferred.remove(kind)?.invoke()
        }
        // 授权跳转的拉起结果还没回来：等它回来时（已离开过 App、此刻在前台）立即查询（共性第 38 条）
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
    @Suppress("ReturnCount") // 归属失效、待办不是本次、link 已换三个出口
    private fun authorized(
        context: AuthContext,
        gen: Long,
    ) {
        if (!authLive(context, gen)) return
        authContext = null
        authGen += 1
        val resumeGen = authGen
        // 先核归属再续时（共性第 44 条）：别的页面（或本页别的尝试）登记的待办不动
        if (!pendingMine(context)) return hideDialog()
        pending.stepDone()
        hideDialog()
        val target = latestCard(context.attempt)
        if (target.text("link_id") != context.linkId) {
            pending.clear()
            return
        }
        // 不在前台时延迟恢复，不先删待办（共性第 46 条）：执行前再核待办有效期（BR-ID-10，peek 过期即为 null）与归属，过期或被新待办取代
        // 就放弃；仍是本次的才删除并以新键自动执行一次
        whenForeground(DeferredResume.AUTH) {
            if (!pendingMine(context)) return@whenForeground
            pending.clear()
            if (resumeStillMine(context, resumeGen)) {
                startClick(target, keys.resendAfterAuth(), noRebate = false, noRebateReason = null, userAuthRetry = false)
            }
        }
    }

    /** 此刻的购买待办仍是 [context] 这次尝试登记的授权待办（未过期、本页、这次点击）。 */
    private fun pendingMine(context: AuthContext): Boolean {
        val record = pending.peek() ?: return false
        return record.text("kind") == PENDING_AUTH && record.text("link_id") == context.linkId &&
            record.text(OWNER) == ownerTag(context.attempt)
    }

    /** 授权完成后的延迟恢复仍属当前尝试：页面没离开、世代没变（期间没有新点击）、尝试仍是那次、没有在途请求或弹窗（A-R3-01）。 */
    private fun resumeStillMine(
        context: AuthContext,
        gen: Long,
    ): Boolean = !left && authGen == gen && current === context.attempt && !busy()

    private fun authFailed(platform: String) {
        clearMine()
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
        if (!foreground) return defer(DeferredResume.LOGIN, ::resumeAfterLogin)
        if (busy()) return
        val record = pending.peek() ?: return
        val attempt = current ?: return
        if (record.text("kind") != PENDING_LOGIN || record.text("link_id") != attempt.linkId) return
        if (record.text("idempotency_key") != attempt.key || record.text(OWNER) != ownerTag(attempt)) return
        pending.clear()
        // 重放前复核最新平台购买状态与这张卡的可购状态（共性第 27 条）：不可买就不发
        if (!purchasable(latestCard(attempt))) return
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
        if (!foreground) return defer(DeferredResume.CONSENT, ::resumeAfterConsent)
        val (target, targetSpm) = consentCard ?: return
        val record = pending.peek() ?: return
        if (record.text("kind") != PENDING_CONSENT || record.text("link_id") != target.text("link_id")) return
        if (record.text(OWNER) != "$pageTag#$CONSENT") return
        pending.clear()
        consentCard = null
        if (env.basicMode()) return
        buy(target, targetSpm)
    }

    /** 发起页被盖住或 App 进入后台（ON_STOP）：作废续执行许可；在途响应之后只更新卡片（03 §4.5，A-R1-03）。 */
    fun onBackground() {
        foreground = false
        revokePermit()
        // 授权外跳许可同样作废（共性第 25、31 条）；授权跳转在途或等回流时记下「离开过 App」（共性第 38 条）
        revokeAuthPermit()
        if (authJumpInFlight || awaitingAuthReturn) authLeftApp = true
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
        revokePermit(cancel = true)
        revokeAuthPermit()
        deferred.clear()
        timer?.cancel()
        gateTimer?.cancel()
        browserDecision?.complete(false)
        cancelOwners()
        // 只删本页登记的（共性第 44 条）：别的页面登记的待办（哪怕同一个 link）不动
        clearMine()
    }

    fun consumeToast() {
        mutableState.value = mutableState.value.copy(toast = null)
    }

    // ---------------------------------------------------------------- 工具

    private fun busy(): Boolean = mutableState.value.phase == PurchasePhase.LOADING || mutableState.value.dialog != null

    private fun grantPermit(attempt: PurchaseAttempt) {
        permit?.let {
            it.valid = false
            it.cancelled = true
        }
        permit = Permit(attempt)
    }

    /** 作废续执行许可；[cancel]：用户取消、离开（已执行的步骤也不再上报）；进入后台不算取消。 */
    private fun revokePermit(cancel: Boolean = false) {
        permit?.let {
            it.valid = false
            if (cancel) it.cancelled = true
        }
        permit = null
    }

    /** 续执行许可仍有效：是当前尝试的、没作废、页面没离开且在前台。 */
    private fun allowed(granted: Permit): Boolean =
        granted.valid && permit === granted && granted.attempt === current && !left && foreground

    /** 外跳执行器「允许开始下一步」（共性第 40 条）：许可仍有效，且这张卡此刻仍可购买（平台状态、下架；不重复提示）。 */
    private fun continuable(granted: Permit): Boolean = allowed(granted) && purchasable(latestCard(granted.attempt), notify = false)

    /** 外跳执行器「允许上报已执行步骤」（共性第 40 条）：只看尝试仍是当前的、没被取消、页面没离开（进入后台照常上报）。 */
    private fun reportable(granted: Permit): Boolean = !granted.cancelled && granted.attempt === current && !left

    /**
     * 此刻这张卡能发 open / 继续外跳（共性第 27、32 条）：所有发 open 与继续外跳的入口都经这里——点击、超时同键重试、授权后自动执行、
     * 登录后重放、补同意恢复、无返利购买、确认框与 JumpTip 的继续、外跳每一步。下架（30141 / availability=off_shelf，按卡）、平台
     * not_launched 不发；maintenance 提示后不发（含点击结果确认的 50301）。
     */
    private fun purchasable(
        card: JsonObject,
        notify: Boolean = true,
    ): Boolean {
        if (card.text("availability") == BuyBlock.OFF_SHELF) return false
        return when (env.platformStatus(card.text("platform").orEmpty())) {
            BuyBlock.MAINTENANCE -> {
                if (notify) toast(MAINTENANCE_KEY)
                false
            }

            BuyBlock.NOT_LAUNCHED -> {
                false
            }

            else -> {
                true
            }
        }
    }

    private fun permitted(attempt: PurchaseAttempt): Boolean = permit?.let { it.attempt === attempt && allowed(it) } == true

    /** 本页这次尝试的待办归属标记。 */
    private fun ownerTag(attempt: PurchaseAttempt): String = "$pageTag#${attempt.click}"

    /** 只删本页登记的购买待办（共性第 44 条）：别的页面登记的待办不动。 */
    private fun clearMine() {
        pending.peek()?.takeIf { it.text(OWNER)?.startsWith("$pageTag#") == true }?.let { pending.clear() }
    }

    /** 绑定这次购买尝试与请求世代的请求归属（F-AND-19）：新点击、重发、离开之后即失效。 */
    private fun attemptOwner(attempt: PurchaseAttempt): CallOwnership {
        val myRequest = request
        return CallOwnership(api.epochs()) { !left && current === attempt && myRequest == request }.also { owners += it }
    }

    private fun whenForeground(
        kind: DeferredResume,
        action: () -> Unit,
    ) {
        if (foreground) action() else defer(kind, action)
    }

    /** 写入 [kind] 的延迟槽位（共性第 48 条）：只覆盖同类，其他类型的延迟恢复保留。 */
    private fun defer(
        kind: DeferredResume,
        action: () -> Unit,
    ) {
        deferred[kind] = action
    }

    /** 延迟恢复的类型（共性第 48 条），声明顺序即回前台的执行顺序。 */
    private enum class DeferredResume { AUTH, LOGIN, CONSENT }

    @Suppress("LongParameterList") // 待办的字段逐项
    private fun pendingParams(
        target: JsonObject,
        linkId: String,
        key: String?,
        kind: String,
        noRebate: Boolean,
        reason: String?,
        owner: String,
    ): JsonObject {
        val fields =
            linkedMapOf<String, JsonElement>(
                "kind" to JsonPrimitive(kind),
                "link_id" to JsonPrimitive(linkId),
                "platform" to JsonPrimitive(target.text("platform").orEmpty()),
                // 归属：本页 + 这次尝试（共性第 44 条）；补同意还没有尝试，用页内固定标记
                OWNER to JsonPrimitive(if (owner == CONSENT) "$pageTag#$CONSENT" else owner),
            )
        target.text("product_key")?.let { fields["product_key"] = JsonPrimitive(it) }
        key?.let { fields["idempotency_key"] = JsonPrimitive(it) }
        if (noRebate) fields["no_rebate"] = JsonPrimitive(true)
        reason?.let { fields["no_rebate_reason"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    private fun showDialog(dialog: PurchaseDialog) {
        mutableState.value = mutableState.value.copy(dialog = dialog)
        // 授权浮层出现的同步出口（APP-07 剪贴板，A-R1-04）：作废发起页在途的 parse 与提示条
        if (dialog is PurchaseDialog.AuthSheet) onAuthOverlay()
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
        /** [authOverlay] 的取值（与剪贴板敏感清单同名）。 */
        const val OVERLAY_AUTH_SHEET = "auth_sheet"
        const val OVERLAY_AUTH_JUMP = "auth_jump"

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

        /** 待办里的归属字段（本页 + 这次尝试，共性第 44 条；不是令牌，不上送）。 */
        internal const val OWNER = "client_owner"
        private const val CONSENT = "consent"
        private val PAGE_SEQ =
            java.util.concurrent.atomic
                .AtomicLong()
        private val FILL_SEQ =
            java.util.concurrent.atomic
                .AtomicLong()

        /** 补齐世代（进程内递增）。 */
        private fun nextFillGeneration(): Long = FILL_SEQ.incrementAndGet()

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
@Suppress("TooManyFunctions") // 写卡、补齐、重载、版本与世代逐项
object CardPatch {
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "LongMethod") // 逐字段
    fun apply(
        card: JsonObject,
        data: JsonObject,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(card)
        data.text("new_link_id")?.let { out["link_id"] = JsonPrimitive(it) }
        val newFinal = data.fen("new_final_price_fen")
        if (priceChanged(card, data)) {
            out["final_price_fen"] = newFinal?.let(::JsonPrimitive) ?: JsonNull
            // 券前价与券额响应不带：一律置未知（coupon_gone 只说明原券失效，不等于当前无券，可能换了新券，A-R3-02），等重取补齐
            out["coupon_fen"] = JsonNull
            out["price_fen"] = JsonNull
            out["est_net_price_fen"] = JsonNull
            // 新价已知、只等补齐（APP-06b 共性第 34 条）：照常显示新价并可购买（映射不当「价格不可用」）
            if (newFinal != null) out[PRICE_PENDING] = JsonPrimitive(true) else out.remove(PRICE_PENDING)
            // 券额未知（券失效或普通改价都一样，不沿用旧券推断，共性第 21、37 条）：先去掉「有券」、按钮不再称「领券购买」；
            // 补齐后由 [fill] 按实际券额同步回来
            out["benefit_tags"] = JsonArray(card.texts("benefit_tags").filter { it != PriceTerms.HAS_COUPON }.map(::JsonPrimitive))
            (card["cta"] as? JsonObject)?.takeIf { it.text("text_key") == BUY_COUPON }?.let {
                out["cta"] = JsonObject(it + ("text_key" to JsonPrimitive(BUY)))
            }
        }
        val newMax = data.fen("new_rebate_max_fen")
        val newMin = data.fen("new_rebate_min_fen")
        val oldMax = card.fen("rebate_max_fen")
        if (newMax == 0L) {
            // 确定返利为 0（BR-PRICE-08，共性第 35、41 条）：不论原来是正数、0 还是未知（游客 login_required 登录后同键重放），一律写零金额、
            // no_rebate、「去购买（无返利）」与本次原因（price_compare 才写，空值或未知值清除旧原因）。是否弹确认另按前后返利判断
            // （OpenResponseAction），与写卡分开
            val alreadyNone = oldMax == 0L && card.fen("rebate_min_fen") == 0L && card.text("rebate_basis") == NO_REBATE
            out["rebate_min_fen"] = JsonPrimitive(0L)
            out["rebate_max_fen"] = JsonPrimitive(0L)
            out["rebate_basis"] = JsonPrimitive(NO_REBATE)
            out.remove(ProductCardMapper.PROVISIONAL_REBATE)
            // 原来不是确定的暂无返利：旧的预估返后按旧返利算，置未知；原来就是暂无返利则保留（同结果重放不改卡）
            if (!alreadyNone) out["est_net_price_fen"] = JsonNull
            out["no_rebate_cause"] =
                data["no_rebate_cause"]?.takeIf { it is JsonPrimitive && it.isString && it.content == PRICE_COMPARE } ?: JsonNull
            if ((card["cta"] as? JsonObject)?.text("text_key") != BUY_NO_REBATE) {
                out["cta"] = JsonObject(mapOf("text_key" to JsonPrimitive(BUY_NO_REBATE)))
            }
            out["disclaimer_keys"] = JsonArray(card.texts("disclaimer_keys").filter { !it.startsWith(REBATE_PREFIX) }.map(::JsonPrimitive))
        } else if (newMax != null && newMin != null && (newMax != oldMax || newMin != card.fen("rebate_min_fen"))) {
            out["rebate_min_fen"] = JsonPrimitive(newMin)
            out["rebate_max_fen"] = JsonPrimitive(newMax)
            out["est_net_price_fen"] = JsonNull
            if (newMax > 0L && (restored(card) || unknownRebate(card))) {
                // 返利从无到有，或原来未知（游客 login_required / 金额缺失，登录后同键重放给出正数，共性第 41 条）：不留「暂无返利 /
                // 登录查看返利 / 去购买（无返利）」与比价原因；口径响应没有 → 置未知并标临时展示状态，按服务端区间显示，等重取的权威卡片
                // 补口径；重取失败就一直按区间显示，不落入「暂无返利」（A-R1-08、A-R2-01）
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

    /** 写入这次 open 结果后卡片没有变化（同键重放得到相同结果，共性第 33 条）：卡片版本不算（每次写入都会加 1）。 */
    fun sameResult(
        before: JsonObject,
        after: JsonObject,
    ): Boolean = JsonObject(before - REVISION) == JsonObject(after - REVISION)

    /** 卡片仍有等补齐的字段：新价已知而券前价 / 券额未知，或返利口径仍是临时展示状态。 */
    fun awaitingFill(card: JsonObject): Boolean = card.flag(PRICE_PENDING) || card.flag(ProductCardMapper.PROVISIONAL_REBATE)

    /** 带世代的补齐（共性第 26 条）：此刻页面上的卡仍是 [generation] 那一次 open 写入的结果才 [fill]，否则原样保留。 */
    fun fillFor(generation: Long): (JsonObject, JsonObject) -> JsonObject =
        { current, fresh -> if (current.fen(FILL_GENERATION) == generation) fill(current, fresh) else current }

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
        // 价格三字段都已知：不再是「待补齐」（共性第 34 条）
        if (JsonObject(out).fen("price_fen") != null && JsonObject(out).fen("coupon_fen") != null) out.remove(PRICE_PENDING)
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

    /**
     * 卡片原来的返利未知：login_required（游客）或返利上下限缺失；open 给出确定正数后要清掉旧口径（不能保留「登录查看返利」，共性第 41 条）。
     * amount_unknown 由服务端口径决定，不在此列（open 返回 null 时保持原处理）。
     */
    private fun unknownRebate(card: JsonObject): Boolean {
        val basis = card.text("rebate_basis")
        if (basis == AMOUNT_UNKNOWN) return false
        return basis == LOGIN_REQUIRED || card.fen("rebate_max_fen") == null || card.fen("rebate_min_fen") == null
    }

    /** 卡片版本的客户端字段名（不来自接口，不上送）。 */
    const val REVISION = "client_card_rev"

    /**
     * 最近一次写入的 open 结果的补齐世代（客户端字段，共性第 26 条）：每次写入不同的 open 结果换新；补齐请求带发出时的世代，到达时卡片上
     * 已是别的世代（之后又写入了新的 open 结果、或 30144 整卡重载）就不补齐。
     */
    const val FILL_GENERATION = "client_fill_gen"

    /** open 已给出新券后价、券前价 / 券额待补齐（与 DesignSystem 的映射同名）。 */
    const val PRICE_PENDING = ProductCardMapper.PRICE_PENDING
    private const val AMOUNT_UNKNOWN = "amount_unknown"
    private const val LOGIN_REQUIRED = "login_required"
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
