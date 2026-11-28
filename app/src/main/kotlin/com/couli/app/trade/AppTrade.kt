package com.couli.app.trade

import android.os.Handler
import android.os.Looper
import com.couli.app.routing.AppRouting
import com.couli.core.clientcore.BundledConfig
import com.couli.core.clientcore.ClientEvents
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clientcore.LinkJumpFailureReason
import com.couli.core.clientcore.LinkJumpInstalled
import com.couli.core.clientcore.LinkJumpPhase
import com.couli.core.clientcore.LinkJumpResult
import com.couli.core.clientcore.LinkJumpStep
import com.couli.core.clientcore.LinkJumpUnionPlatform
import com.couli.core.clientcore.TrackerQueue
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.tradejump.BaichuanAdapter
import com.couli.feature.trade.AuthManageController
import com.couli.feature.trade.AuthSheetCounter
import com.couli.feature.trade.BindingChanges
import com.couli.feature.trade.ColdAuthResume
import com.couli.feature.trade.JumpPorts
import com.couli.feature.trade.JumpTipService
import com.couli.feature.trade.JumpTypes
import com.couli.feature.trade.LinkJumpSink
import com.couli.feature.trade.OpenPurchaseController
import com.couli.feature.trade.PlatformStatusOverrides
import com.couli.feature.trade.PurchaseEnv
import com.couli.feature.trade.PurchasePending
import com.couli.feature.trade.PurchaseToast
import com.couli.feature.trade.TaobaoAuthFlow
import com.couli.feature.trade.TradeJumpExecutor
import com.couli.feature.trade.TradeTexts
import com.couli.feature.trade.UnavailableWebCodeAuthorizer
import com.couli.feature.trade.WebCodeAttemptStore
import com.couli.feature.trade.WebCodeAuthorizer
import com.couli.feature.trade.WebCodeCallback
import com.couli.feature.trade.WebCodeInbox
import com.couli.feature.trade.WebCodeOrphanGate
import com.couli.feature.trade.WebCodeOrphanHandler
import com.couli.feature.trade.WebCodeOrphanOverlay
import com.couli.feature.trade.WebCodeOrphanStartup
import com.couli.feature.trade.WebCodeOrphanUi
import com.couli.feature.trade.webCodeAccountTag
import java.util.UUID
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * App 侧的购买、外跳与授权接线（APP-06b）：
 * - 每个发起页（商品详情、搜索结果）一份 [OpenPurchaseController]（[purchaseController]），共用本进程的百川适配层、外跳执行器、AuthSheet
 *   计数、JumpTip 已读与购买 pending_action（Router 的同一份本地记录）；
 * - link_jump 经 Tracker 本地队列（ClientEvents 生成函数；上报 sink 仍为 TODO(N-20)）；
 * - 登录完成（会话由游客变为已登录、待办没有暂缓）时，让登记了购买待办的发起页自动执行一次 open（BR-ID-10）；
 * - 基本模式下点购买：发起页登记待办后经 [requestConsent] 弹补同意；隐私变为已同意时让发起页恢复一次（BR-ID-11、03 §4.1，A-R1-04）；
 * - 平台购买状态：点击结果确认的 50301（[overrides]，新配置到达前有效，A-R1-05 / 06）优先，其次生效配置的 features.platform_status
 *   （current → LKG → 包内，ConfigStore 的快照）。
 * 只在主线程使用。
 */
@Suppress("LongParameterList", "TooManyFunctions") // 各能力的来源逐项注入；生命周期与工厂各一个入口
class AppTrade(
    private val api: PageApi,
    private val store: ConfigStore?,
    private val routing: AppRouting,
    private val tracker: Provider<TrackerQueue>,
    val baichuan: BaichuanAdapter,
    private val webCode: WebCodeAuthorizer,
    private val ports: JumpPorts,
    val texts: TradeTexts = TradeDraftTexts,
    private val browserNoticeMillis: Long = OpenPurchaseController.BROWSER_NOTICE_MILLIS,
    /** 弹补同意（首启弹窗的完整功能说明；App 给 AppPrivacy.requestFullFunction）。 */
    private val consentPrompt: () -> Unit = {},
    /** 当前会员账号（App 给 ApiClient.activeUserId；游客、读不出为 null）：open 发出时固定它（03 §4.2）。 */
    private val sessionAccount: () -> String? = { null },
    /**
     * 冷启动 / 晚到回跳（FU-AND-TB-WEBCODE-B，设计稿 §6.4）的装配。四项都有缺省，用 UnavailableWebCodeAuthorizer 构造本类的旧测试不用改：
     * [webCodeAttempts] / [webCodeInbox] 为与真实授权器共用的那一份（AppModule 给；没接为 null，回跳一律丢弃、登出不清）；
     * [webCodeGate] 是启动闸门（缺省保守：版本未知，不提交）；[processStartedAtMillis] 用来区分冷启动回跳与本进程的晚到回跳——缺省取本类构造
     * 时刻：真实授权器与本类在同一处装配，本进程记下的尝试一定不早于它。
     */
    private val webCodeAttempts: WebCodeAttemptStore? = null,
    private val webCodeInbox: WebCodeInbox? = null,
    private val webCodeGate: WebCodeOrphanGate = WebCodeOrphanGate { WebCodeOrphanStartup.VERSION_UNKNOWN },
    private val processStartedAtMillis: Long = System.currentTimeMillis(),
) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val counter = AuthSheetCounter()
    val sink = LinkJumpSink(::track)
    val executor = TradeJumpExecutor(ports, baichuan, sink)
    val tips = JumpTipService(api) { sessionKey() }
    val pending: PurchasePending = RouterPurchasePending()

    /** 统一客服出口（APP-12 brief §2.9；AppModule 接 AppMe）。没接时打开帮助中心。 */
    var customerService: (fromRoute: String) -> Unit = { open(HELP_ROUTE, JsonObject(emptyMap())) }

    /**
     * 活着的发起页执行器（登录完成后的自动恢复只交给它们）。主线程在登记与出栈的同步出口整体替换成新的不可变列表（A-R2-04）：任何线程
     * 读到的都是一份完整快照，遍历时不会被并发修改。
     */
    @Volatile
    private var live: List<OpenPurchaseController> = emptyList()

    /** 「绑定变化」信号（FU-AND-TB-WEBCODE-B）：冷启动 / 晚到回跳绑定成功后发出；授权管理页订阅（[authManageController]）。 */
    val bindingChanges = BindingChanges()

    /** 冷启动恢复槽（FU-AND-TB-WEBCODE-B，设计稿 §6.4 第 6 步）：进程里一份，交给每个发起页的执行器（[purchaseController]）。 */
    val coldResume = ColdAuthResume()

    private val mutableWebCodeOverlay = MutableStateFlow(WebCodeOrphanOverlay())
    private var webCodeToastSeq = 0L

    /**
     * 冷启动 / 晚到回跳的前台浮层（FU-AND-TB-WEBCODE-B）：提交 bindings 期间 confirming = true（不可关闭的「正在确认授权结果」），结束后 false；
     * 结果提示放 toast（[consumeWebCodeToast] 消费）。
     */
    val webCodeOverlay: StateFlow<WebCodeOrphanOverlay> = mutableWebCodeOverlay.asStateFlow()

    /** 界面已显示过 [webCodeOverlay] 的轻提示。 */
    fun consumeWebCodeToast() {
        mutableWebCodeOverlay.value = mutableWebCodeOverlay.value.copy(toast = null)
    }

    /**
     * 无人认领回跳的处理器（设计稿 §6.4）：没接尝试记录（旧式构造）就没有，回跳一律丢弃。bindings 只经 [TaobaoAuthFlow.submit] 发（不拉起授权，
     * 所以授权器给不可用实现），每次新键；请求归属不随页面（冷启动时还没有发起页）。
     */
    private val orphanHandler: WebCodeOrphanHandler? =
        webCodeAttempts?.let { attempts ->
            WebCodeOrphanHandler(
                flow =
                    TaobaoAuthFlow(
                        api,
                        UnavailableWebCodeAuthorizer,
                        baichuan,
                        keyGenerator = { UUID.randomUUID().toString() },
                        owner = { CallOwnership(api.epochs()) { true } },
                        alive = { true },
                        installed = ports::installed,
                    ),
                attempts = attempts,
                pending = pending,
                gate = webCodeGate,
                accountTag = { sessionAccount()?.let(::webCodeAccountTag) },
                resume = coldResume,
                bindingChanges = bindingChanges,
                ui = OverlayUi(),
                scope = scope,
                processStartedAtMillis = processStartedAtMillis,
            )
        }

    /**
     * 没有等待者认领的回跳（WebCodeInboundDispatcher 在下一轮主线程循环交来）：交给 WebCodeOrphanHandler（设计稿 §6.4）。主线程调用。
     */
    fun onUnclaimedWebCode(callback: WebCodeCallback) {
        val handler = orphanHandler ?: return
        scope.launch { handler.handle(callback) }
    }

    /**
     * 退出登录、切换账号（设计稿 §8；甲评审 S3 移交）：清尝试记录、清空收件箱、作废恢复槽。会话由已登录变为游客时本类自己调；切换账号由 App 调
     * （AppRouting.onAccountSwitched 仍是 TODO(APP-05b)，本线不接）。
     */
    fun onSessionEnded() {
        webCodeAttempts?.clear()
        webCodeInbox?.clear()
        coldResume.clear()
    }

    /** orphan 处理器的前台提示 → [webCodeOverlay]（主线程）。 */
    private inner class OverlayUi : WebCodeOrphanUi {
        override fun confirming(shown: Boolean) {
            mutableWebCodeOverlay.value = mutableWebCodeOverlay.value.copy(confirming = shown)
        }

        override fun toast(key: String) {
            webCodeToastSeq += 1
            mutableWebCodeOverlay.value = mutableWebCodeOverlay.value.copy(toast = PurchaseToast(key, id = webCodeToastSeq))
        }
    }

    /** 点击结果确认的平台购买状态（50301），进程里一份；分界之后发出并生效的配置覆盖它。 */
    val overrides = PlatformStatusOverrides(issuedMark = { store?.issuedFetchId ?: 0L }, appliedMark = { store?.appliedFetchId ?: 0L })

    init {
        scope.launch {
            // 补同意完成：登记了购买待办的发起页恢复一次（不同意时 Router 已删除待办，这里不会走到）
            routing.privacy.drop(1).collect { state ->
                if (state == PrivacyState.CONSENTED) main.post { live.forEach(OpenPurchaseController::resumeAfterConsent) }
            }
        }
        scope.launch {
            routing.session.drop(1).collect { state ->
                // 退出登录（会话变为游客）：web_code 的尝试记录、收件箱与恢复槽一并作废（设计稿 §8）
                if (state == SessionState.GUEST) onSessionEnded()
                // 登录完成：待办暂缓期间（绑手机引导、phone_bound 未查清）不恢复；执行入口 resumeAfterLogin 本身也查暂缓（投递之后才开始的暂缓、
                // 延后到回前台的续执行同样挡住）
                if (state.loggedIn() &&
                    !routing.router.pendingHeld
                ) {
                    main.post { live.forEach(OpenPurchaseController::resumeAfterLogin) }
                }
            }
        }
        scope.launch {
            // FU-AND-0716 ①（iOS R1-01、APP-05b 共性第 19 条）：暂缓明确释放后通知活着的发起页，各自重新核对归属、前置与可购状态，再恢复一次
            // （待办执行前即删除，重复通知不会重放第二次）
            routing.router.heldReleases.drop(1).collect {
                if (routing.session.value.loggedIn()) main.post { live.forEach(OpenPurchaseController::resumeAfterLogin) }
            }
        }
    }

    /** 百川初始化（只由 PrivacyGate.onConsented 的「联盟」步骤调用）：失败时 phase=startup 上报 sdk_init_failed（BR-ATTR-27）。 */
    fun initializeUnionSdk() {
        if (!baichuan.initialize()) main.post { executor.reportStartupInitFailed() }
    }

    /** 一个发起页的购买执行器；页面出栈时调 [release]。 */
    fun purchaseController(
        env: PurchaseEnv,
        scope: CoroutineScope,
    ): OpenPurchaseController =
        OpenPurchaseController(
            api = api,
            executor = executor,
            ports = ports,
            env = env,
            pending = pending,
            counter = counter,
            tips = tips,
            webCode = webCode,
            baichuan = baichuan,
            scope = scope,
            browserNoticeMillis = browserNoticeMillis,
            coldResume = coldResume,
        ).also {
            it.onAuthOverlay = { onSensitiveOverlay() }
            it.onJumpingChanged = ::refreshJumpInProgress
            live = live + it
            refreshJumpInProgress()
        }

    private val mutableJumpInProgress = MutableStateFlow(false)

    /**
     * 外跳进行中（FU-AND-0724 G10 / G28：剪贴板自动识别的普通互斥模态 trade_jump）：任一活着的发起页执行器正在执行购买外跳（含「将通过浏览器
     * 打开」提示的停留，见 [OpenPurchaseController.jumping]）。执行器状态变化时同步汇总（不经 Handler / 协程二次投递）；发起页出栈即不再计入。
     */
    val jumpInProgress: StateFlow<Boolean> = mutableJumpInProgress.asStateFlow()

    private fun refreshJumpInProgress() {
        mutableJumpInProgress.value = live.any { it.jumping.value }
    }

    /** 敏感浮层（AuthSheet）出现的同步出口（APP-07：AppModule 接剪贴板的作废，A-R1-04）。 */
    var onSensitiveOverlay: () -> Unit = {}

    /** 授权进行中的敏感浮层（APP-07 剪贴板：AuthSheet、auth_jump 执行中时回前台不读）；没有为 null。遍历的是不可变快照 [live]。 */
    fun sensitiveOverlay(): String? = live.firstNotNullOfOrNull { it.authOverlay }

    fun release(controller: OpenPurchaseController) {
        controller.leave()
        live = live - controller
        refreshJumpInProgress()
    }

    fun authManageController(scope: CoroutineScope): AuthManageController =
        AuthManageController(api, executor, ports, webCode, baichuan, scope, bindingChanges = bindingChanges)

    /** App 回到前台 / 进入后台（AuthSheet 计数：后台 ≥ 1800 秒清零，BR-ID-18）。 */
    fun onAppForeground() = counter.onForeground()

    fun onAppBackground() = counter.onBackground()

    /** 有登录会话（open 对游客返回 10001）。 */
    fun loggedIn(): Boolean = routing.session.value.loggedIn()

    /** 此刻的会员账号（[PurchaseEnv.account]）。 */
    fun account(): String? = sessionAccount()

    /** 此刻的平台购买状态：点击结果确认的 50301 优先，其次 features.platform_status（生效配置：当前 → LKG → 包内）。 */
    fun platformStatus(platform: String): String? = overrides.status(platform) ?: platformStatusIn(store, platform)

    /** 记下点击结果确认的平台状态（50301）：列表、详情按它重新映射卡片（订阅见 [subscribeStatus]）。 */
    fun markPlatformStatus(
        platform: String,
        status: String,
    ) = overrides.mark(platform, status)

    /** 点击结果确认的平台状态变了（主线程）；返回取消订阅。 */
    fun subscribeStatus(listener: () -> Unit): () -> Unit = overrides.subscribe(listener)

    /** 基本模式下点购买：弹补同意。 */
    fun requestConsent() = consentPrompt()

    /** /v1/config.help_links.<key>：{route, params}（没有配置为 null）。 */
    @Suppress("ReturnCount") // 逐层取值，缺一层即 null
    fun helpLink(key: String): Pair<String, JsonObject>? {
        val links =
            store
                ?.snapshots
                ?.value
                ?.data
                ?.get("help_links") as? JsonObject ?: return null
        val entry = links[key] as? JsonObject ?: return null
        val route = (entry["route"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return route to (entry["params"] as? JsonObject ?: JsonObject(emptyMap()))
    }

    /** 经 Router 以 App 内来源打开（路由名都在 routes.json：Login、Search、Help、AuthManage）。 */
    fun open(
        route: String,
        params: JsonObject,
    ) {
        routing.router.open(RouteRequest(route, params, RouteSource.IN_APP))
    }

    private fun sessionKey(): Any? = if (loggedIn()) routing.router.generation else null

    private fun SessionState.loggedIn(): Boolean = this == SessionState.LOGGED_IN || this == SessionState.PHONE_BOUND

    /** link_jump → Tracker（业务代码只能调生成函数，03 §4.7）。字段已按契约校验，取值不在枚举里的不上报。 */
    @Suppress("ReturnCount") // 逐字段解析
    private fun track(fields: JsonObject) {
        fun text(key: String) = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val phase = LinkJumpPhase.entries.firstOrNull { it.raw == text("phase") } ?: return
        val platform = LinkJumpUnionPlatform.entries.firstOrNull { it.raw == text("union_platform") } ?: return
        val step = LinkJumpStep.entries.firstOrNull { it.raw == text("step") } ?: return
        val (result, failureReason) = linkJumpResult(text("result")) ?: return
        val installed = LinkJumpInstalled.entries.firstOrNull { it.raw == text("installed") }
        val duration = (fields["duration_ms"] as? JsonPrimitive)?.content?.toLongOrNull()
        val event =
            runCatching {
                ClientEvents.linkJump(
                    page = PAGE,
                    phase = phase,
                    unionPlatform = platform,
                    step = step,
                    result = result,
                    attemptId = text("attempt_id"),
                    linkId = text("link_id"),
                    failureReason = failureReason,
                    durationMs = duration,
                    installed = installed,
                )
            }.getOrNull() ?: return
        tracker.get().track(event)
    }

    /**
     * 外跳步骤结果 → events.yaml link_jump 的 result 与 failure_reason（events version 6，03 §4.5）。步骤执行器仍用自己的结果词
     * （launched / copied / failed / sdk_init_failed，购买流程的判定靠它们），只在这里换成契约取值：拉起与已复制口令都记 requested；
     * 已初始化但这一步明确失败记 failed + step_failed；百川初始化失败记 failed + sdk_init_failed。失败一律带 failure_reason、绝不记成 requested；
     * 契约的 requested、unknown 原样认；其余取值不上报。
     * TODO(N-20)：接上报 sink 时由交易模块直接产契约新取值（含 unknown），并删掉本映射。
     */
    private fun linkJumpResult(raw: String?): Pair<LinkJumpResult, LinkJumpFailureReason?>? =
        when (raw) {
            JumpTypes.LAUNCHED, JumpTypes.COPIED -> LinkJumpResult.REQUESTED to null
            JumpTypes.FAILED -> LinkJumpResult.FAILED to LinkJumpFailureReason.STEP_FAILED
            JumpTypes.SDK_INIT_FAILED -> LinkJumpResult.FAILED to LinkJumpFailureReason.SDK_INIT_FAILED
            LinkJumpResult.REQUESTED.raw -> LinkJumpResult.REQUESTED to null
            LinkJumpResult.UNKNOWN_VALUE.raw -> LinkJumpResult.UNKNOWN_VALUE to null
            else -> null
        }

    /** 购买 pending_action：Router 的同一份本地记录（BR-ID-10：只存 1 条、10 分钟、完成一步重新计时、退出登录清除）。 */
    private inner class RouterPurchasePending : PurchasePending {
        override fun register(params: JsonObject): Boolean = routing.router.registerAction(PurchasePending.ACTION, params)

        override fun peek(): JsonObject? = routing.router.pendingAction(PurchasePending.ACTION)?.params

        override fun stepDone() {
            if (routing.router.pendingAction(PurchasePending.ACTION) != null) routing.router.onPrerequisiteCompleted(resume = false)
        }

        override fun clear() = routing.router.clearAction(PurchasePending.ACTION)

        override val held: Boolean get() = routing.router.pendingHeld
    }

    companion object {
        private const val PAGE = "ProductDetail"
        private const val HELP_ROUTE = "Help"

        /** 生效配置（ConfigStore 的快照：冷启动是 LKG 或包内，拉取成功后是当前）里的平台购买状态；快照缺这一项时取包内。 */
        fun platformStatusIn(
            store: ConfigStore?,
            platform: String,
        ): String? {
            val current = store?.snapshots?.value?.data
            val bundled = runCatching { Json.parseToJsonElement(BundledConfig.dataJson) as? JsonObject }.getOrNull()
            return listOfNotNull(current, bundled).firstNotNullOfOrNull { layer ->
                (((layer["features"] as? JsonObject)?.get("platform_status") as? JsonObject)?.get(platform) as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
            }
        }
    }
}
