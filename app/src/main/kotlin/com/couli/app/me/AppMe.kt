package com.couli.app.me

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import com.couli.app.BuildConfig
import com.couli.app.login.AppLogin
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.HomeDestination
import com.couli.app.routing.NavTickets
import com.couli.app.routing.RouteDestination
import com.couli.core.agent.AgentCancelDispatch
import com.couli.core.agent.AgentOwnershipEvent
import com.couli.core.clientcore.AmountMaskStore
import com.couli.core.clientcore.ConfigStore
import com.couli.core.me.CustomerServiceEntry
import com.couli.core.me.CustomerServicePorts
import com.couli.core.me.MeDraftTexts
import com.couli.core.me.MeLevel
import com.couli.core.me.MeLocalStore
import com.couli.core.me.MeTexts
import com.couli.core.me.PrivacyWithdrawFlow
import com.couli.core.me.Route10006
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiClientPageApi
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.network.SessionTokens
import com.couli.core.push.PushReportState
import com.couli.core.push.PushSessionTracker
import com.couli.core.push.PushTokenReporter
import com.couli.core.push.PushTokenSource
import com.couli.core.router.GuardState
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteNames
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.core.wallet.WalletRateGate
import com.couli.feature.me.MeDeps
import com.couli.feature.me.MeEnv
import com.couli.feature.me.MeGates
import com.couli.feature.me.MeIdentity
import com.couli.feature.me.NicknameBackend
import com.couli.feature.me.NotifyBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** 「我的」类页面要的系统能力（App 给；生产 [AndroidMeSystem]，测试替身）。都在主线程调用，[cacheBytes] / [clearCache] 在 IO 线程。 */
@Suppress("TooManyFunctions")
interface MeSystemPorts {
    /** push 权限的只读状态（PermissionRequester.status）：granted / denied / permanently_denied / not_asked / unavailable。 */
    fun pushStatus(): String

    /** PermissionRequester 记下的 push 最近被拒时刻。 */
    fun pushDeniedAt(): Long?

    fun openNotificationSettings()

    fun openAppSettings()

    /** 经系统打开外部链接（不在 App 内 WebView 加载）；拉不起为 false。 */
    fun openExternal(url: String): Boolean

    /** 微信是否已装：yes / no / unknown（只查生成物登记的查询项；Android 现为 unknown）。 */
    fun wechatInstalled(): String

    fun cacheBytes(): Long?

    fun clearCache(): Boolean

    /** WebView 的内存与磁盘资源缓存（WebView.clearCache(true)，R1-10）；主线程调用，返回是否成功。 */
    fun clearWebCache(): Boolean

    fun deviceId(): String?

    fun osVersion(): String?

    fun model(): String?
}

/**
 * App 侧的「我的」、设置、隐私中心、关于、封禁说明与申诉、推送上报与统一客服出口（APP-12）：
 * - 身份（[identity]）：隐私结论 + 会话状态 + 账号 + 登录世代 + 隐私 / 环境世代；会话、隐私、环境变化时同步重算（[syncIdentity]），页面按它重建执行器；
 *   账号或登录世代变了：清空 Me 快照、重置禁用期、把只能登录访问的页面（RiskNotice、Appeal）移出导航栈；
 * - 10006 统一落点（[on10006]，网络层一处）：调用仍有效、入口时的导航票据与此刻一致、强更不在显示、栈顶不是 RiskNotice → 清到起点再经 Router 打开 RiskNotice；
 * - 撤回同意的协调器（[withdrawConsent]，不随隐私中心出栈销毁，鸿蒙 R2-04）：同步停报推送 → 设备级 recordConsent(privacy, false)（设备未注册或禁用期跳过）→
 *   无论结果调已有的 AppPrivacy.withdraw() → 基本模式；落盘失败在当前界面提示并可重试；
 * - 退出登录（[logout]）：同步停报推送 → 已有 logout() → 按发起实例与导航票据回「我的」游客态（R1-09）；
 * - 推送令牌上报（[push]）：登录成功（只认 AppLogin 的登录完成）、已登录冷启动（等版本检查）、令牌变化；退出、撤回、基本模式、deletion_only 不报；
 * - 统一客服出口（[cs]）：原样 kf.url。
 * 只在主线程使用。
 */
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class AppMe(
    private val client: ApiClient,
    private val tokens: SessionTokens,
    private val routing: AppRouting,
    private val privacy: AppPrivacy,
    private val store: ConfigStore?,
    private val login: () -> AppLogin?,
    private val updateCheck: () -> UpdateCheck,
    private val serverForced: () -> Unit,
    private val writer: (String) -> Boolean,
    val local: MeLocalStore?,
    val mask: AmountMaskStore?,
    private val system: MeSystemPorts,
    pushSource: PushTokenSource,
    nickname: NicknameBackend?,
    notify: NotifyBackend?,
    private val tipsReset: () -> Unit = {},
    private val texts: MeTexts = AppMeTexts,
    private val clock: () -> Long = System::currentTimeMillis,
    private val api: PageApi = ApiClientPageApi(client),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * APP-10b G01（编排者补充第 1 条）：Agent 的应用级归属协调器。退出登录、撤回隐私同意在确认后的同步入口先调 [beginAgentIdentityChange]（进入「身份变更在途」、
     * 停流、以原身份尽力发 1 次 cancel），等 cancel 写出（不等结果）再发流程自己的请求；流程落定后调 [endAgentIdentityChange]。AppModule 在建好 AppAgent 后接上；
     * 没接时什么都不做。
     */
    var beginAgentIdentityChange: (String) -> AgentCancelDispatch = { AgentCancelDispatch.NONE }
    var endAgentIdentityChange: (String) -> Unit = {}
    private var navController: NavController? = null
    private val tickets: NavTickets get() = routing.navTickets

    /** 账号缓存（按登录世代；会话读取有解密开销，[identity] 调用频繁）。 */
    private var accountEpoch = Long.MIN_VALUE
    private var accountCache: String? = null

    private val mutableIdentity = MutableStateFlow(computeIdentity())
    val identity: StateFlow<MeIdentity> = mutableIdentity.asStateFlow()

    private val mutableWithdrawing = MutableStateFlow(false)

    /** 撤回同意本地落盘失败（编号；null = 没有）：在当前可见界面提示「撤回没有生效」并可重试（鸿蒙 R2-04）。 */
    private val mutableWithdrawFailed = MutableStateFlow<Long?>(null)
    val withdrawFailed: StateFlow<Long?> = mutableWithdrawFailed.asStateFlow()
    private var failures = 0L

    /**
     * 因 10006 进入过 RiskNotice 的那个身份（G10；Android R2-03，共性第 30 条）：10006 落点生效的那一刻固定下来的身份档 + 账号 + 登录世代 + 隐私 / 环境世代。
     * 「本次运行因 10006 进入」只对这一个身份成立（[enteredBy10006] 只比较归属）；换账号、退出、撤回同意、切换环境时同步清除，不把 A 账号的封禁依据带给 B。
     */
    @Volatile private var entered10006: MeIdentity? = null

    /** [identity] 在本次运行里因 10006 进入过 RiskNotice（G10）：只比较触发时固定的归属。 */
    fun enteredBy10006(identity: MeIdentity): Boolean = entered10006?.let { it == identity && identity.member } == true

    /**
     * 诊断信息「首页版本」的来源（APP-11 G31：AppHome.currentVersion，AppModule 接线）。接上之后只取它（没有显示中的页面整行省略，不再借用 h5_release.version）；
     * 没接首页（只测「我的」的单测）时保留 APP-12 的旧取值。
     */
    var homeVersion: (() -> String?)? = null

    /** 注销与换号（APP-13a，AppModule 接线后设置；只测「我的」的单测为 null）。 */
    var account: com.couli.app.account.AppAccount? = null

    /** 退出登录进行中（同一时刻一次）。 */
    private var loggingOut = false

    /** 回退栈条目 id → 页面的作废入口（出栈的同步出口）。 */
    private val leaves = mutableMapOf<String, () -> Unit>()

    /** Me 读取的截止时间与登录侧的 phone_bound 校验共用同一个闸门（[AppLogin.meReadGate]，R2-05）；没有登录模块（单测）时自建。 */
    val gates = MeGates(scope, clock, login()?.meReadGate ?: WalletRateGate(scope, clock))

    val cs: CustomerServiceEntry =
        CustomerServiceEntry(
            object : CustomerServicePorts {
                override fun kfUrl(): String? =
                    (
                        (
                            store
                                ?.snapshots
                                ?.value
                                ?.data
                                ?.get(KF) as? JsonObject
                        )?.get(URL) as? JsonPrimitive
                    )?.takeIf { it.isString }
                        ?.content

                override fun wechatInstalled(): String = system.wechatInstalled()

                override fun openExternal(url: String): Boolean = system.openExternal(url)

                override fun copy(text: String): Boolean = writer(text)

                override fun openHelp() = open(HELP)
            },
        )

    val env: MeEnv = Env()

    val deps: MeDeps =
        MeDeps(
            api = api,
            env = env,
            gates = gates,
            texts = texts,
            local = local,
            nickname = nickname,
            notify = notify,
            clock = clock,
            service = scope,
        )

    // ---------------------------------------------------------------- 推送上报

    val push: PushTokenReporter =
        PushTokenReporter(
            source = pushSource,
            state =
                object : PushReportState {
                    override fun level(): String = computeIdentity().level

                    override fun sessionScope(): String = client.activeSessionScope().orEmpty()

                    override fun forceUpdate(): String = updateRaw()

                    override fun sessionKey(): String = sessionKeyNow()

                    override fun account(): String? = computeIdentity().account

                    override fun onForceUpdate() = serverForced()
                },
            api = api,
            scope = scope,
        )

    val pushTracker: PushSessionTracker = PushSessionTracker(push, ::sessionKeyNow, { tokens.loginEpoch }, ::updateRaw)

    private fun sessionKeyNow(): String {
        val id = computeIdentity()
        return if (id.member) "${id.epoch}|${id.account}" else ""
    }

    private fun updateRaw(): String =
        when (updateCheck()) {
            UpdateCheck.BELOW_MIN -> BELOW
            UpdateCheck.OK, UpdateCheck.BELOW_RECOMMENDED -> SUPPORTED
            UpdateCheck.CHECKING, UpdateCheck.UNKNOWN -> UNKNOWN
        }

    // ---------------------------------------------------------------- 身份

    private fun account(): String? {
        val epoch = tokens.loginEpoch
        if (epoch != accountEpoch) {
            // 读不出（凭据暂时不可读、环境未就绪、会话读不出）不缓存：下次再读，读到之前身份按账号未建立（F-AND-28：member 为 false，不发布个人数据）
            accountCache = client.activeUserId() ?: return null
            accountEpoch = epoch
        }
        return accountCache
    }

    /** 此刻的身份（现算）。 */
    fun computeIdentity(): MeIdentity {
        val readable = !privacy.credentialAccess.cleanupPending
        val level =
            when {
                privacy.conclusion != PrivacyState.CONSENTED -> MeLevel.BASIC
                !readable || routing.session.value == SessionState.GUEST -> MeLevel.GUEST
                else -> MeLevel.MEMBER
            }
        val account = if (level == MeLevel.MEMBER) account() else null
        return MeIdentity(level, account, tokens.loginEpoch, client.inFlight.epochs())
    }

    /**
     * 会话、隐私、环境、版本状态变化后同步：身份变了 → 发布新身份（页面重建执行器）；账号或登录世代变了 → 清空 Me 快照、重置禁用期、移出只能登录访问的页面；
     * 推送会话跟踪同步。
     */
    fun syncIdentity() {
        accountEpoch = Long.MIN_VALUE
        val next = computeIdentity()
        val previous = mutableIdentity.value
        if (next != previous) {
            val sessionChanged = next.account != previous.account || next.epoch != previous.epoch || next.level != previous.level
            // 10006 版式依据绑定触发时的身份（R2-03）：身份任一部分变了（换账号、退出、撤回、切环境）同步清除
            if (entered10006 != null && entered10006 != next) entered10006 = null
            if (sessionChanged) {
                gates.reset()
                deps.repo.reset(next)
                if (previous.member) closeLoginOnlyPages()
            }
            if (next.epochs != previous.epochs) deps.repo.reset(next)
            mutableIdentity.value = next
        }
        pushTracker.sync()
        account?.sync()
    }

    /** 会话换代（会话失效、登出、换账号）：只能登录访问的页面移出导航栈（共性第 13 条的「移出导航栈」做法）。 */
    private fun closeLoginOnlyPages() {
        val controller = navController ?: return

        @Suppress("RestrictedApi")
        val stack = controller.currentBackStack.value
        val first = stack.firstOrNull { routeName(it) in LOGIN_ONLY } ?: return
        runCatching { controller.popBackStack(first.toRoute<RouteDestination>(), inclusive = true) }
    }

    private fun routeName(entry: NavBackStackEntry): String? =
        if (entry.destination.hasRoute(
                RouteDestination::class,
            )
        ) {
            runCatching { entry.toRoute<RouteDestination>().name }.getOrNull()
        } else {
            null
        }

    // ---------------------------------------------------------------- 导航

    fun attach(controller: NavController) {
        navController = controller
        tickets.attach(controller)
        account?.attach(controller)
    }

    fun detach(controller: NavController) {
        if (navController === controller) navController = null
        tickets.detach(controller)
        account?.detach(controller)
    }

    /**
     * 外壳提交切 Tab 的同步出口（AppShell.onOpenTab，改选中项之前；Android R2-01，共性第 28 条）：切到别的 Tab 时立即撤销「我的」这一次进入的读取许可并取消
     * 在途 getMe，不等下一次重组的 onDispose。
     */
    fun onTab(route: String) {
        if (route != ME) deps.tab.leave()
    }

    /** 回退栈变了（主线程、同一条消息）：离开回退栈的页面立即作废读请求（出栈的同步出口）；Tab 根被下一页盖住，「我的」这一次进入同步离开（R2-01）。 */
    fun followBackStack(entries: List<NavBackStackEntry>) {
        val pages = entries.filter { it.destination !is androidx.navigation.NavGraph }
        tickets.onBackStack(pages.lastOrNull()?.id)
        if (pages.lastOrNull()?.destination?.hasRoute(HomeDestination::class) == false) deps.tab.leave()
        val ids = pages.map { it.id }.toSet()
        leaves.keys.filter { it !in ids }.forEach { id -> leaves.remove(id)?.invoke() }
        account?.followBackStack(pages)
    }

    /** 页面登记出栈出口。 */
    fun bindLeave(
        entryId: String?,
        leave: () -> Unit,
    ) {
        if (entryId != null) leaves[entryId] = leave
    }

    private fun topRoute(): String? = navController?.currentBackStackEntry?.let(::routeName)

    private fun open(
        route: String,
        params: JsonObject = JsonObject(emptyMap()),
    ) {
        val request = RouteRequest(route, params, RouteSource.IN_APP)
        // 强更受限上下文（从强更页进入的隐私中心）：协议、清单按「从强更页进入」打开（03 §4.1，鸿蒙 R2-05）
        val state: GuardState = routing.guardState()
        val forced = updateCheck() == UpdateCheck.BELOW_MIN && route == AGREEMENT
        routing.router.open(request, if (forced) state.copy(fromForceUpdatePage = true) else state)
    }

    // ---------------------------------------------------------------- 10006

    /** 网络层每个逻辑调用入口取的导航票据。 */
    fun navigationTicket(): Any? = tickets.ticket()

    /**
     * 10006 → RiskNotice（G09，只此一处；已切到主线程）：调用仍有效、发起之后没有再导航（票据一致，空票据不放行）、强更不在显示、栈顶不是 RiskNotice → 清到起点再经
     * Router 打开 RiskNotice（栈里只留它），并记「本次运行因 10006 进入」（G10）。
     */
    @Suppress("ReturnCount")
    fun on10006(
        ticket: Any?,
        stillValid: () -> Boolean,
    ) {
        if (ticket == null || ticket != tickets.ticket() || !runCatching(stillValid).getOrDefault(false)) return
        val forced = updateCheck() == UpdateCheck.BELOW_MIN || topRoute() == RouteNames.FORCE_UPDATE
        if (Route10006.decide(topRoute().orEmpty(), forced) != Route10006.REPLACE) return
        // APP-13a（G73 Android 追加）：注销页在 BR-ID-31 白名单里（封禁用户可申请注销、作废结果未知的键），页上表外读取（钱包摘要）得到的 10006
        // 不把它换成 RiskNotice，页面按读取失败处理；TODO(N-37) 白名单是否补 getWalletSummary 待契约 / 规划
        if (topRoute() == DELETE_ACCOUNT) return
        val owner = computeIdentity()
        if (!owner.member) return
        entered10006 = owner
        navController?.let { runCatching { it.popBackStack(HomeDestination, inclusive = false) } }
        open(RISK_NOTICE)
    }

    // ---------------------------------------------------------------- 撤回同意与退出登录

    /**
     * 撤回同意（G24、G25，brief §0 第 1 条）。[identity] 是确认框生成时的身份；启动前核对，已合法启动的撤回一定完成本地清理（不随页面出栈取消）。
     */
    @Suppress("ReturnCount")
    fun withdrawConsent(identity: MeIdentity) {
        if (mutableWithdrawing.value || !identity.current(env) || identity.level == MeLevel.BASIC) return
        mutableWithdrawing.value = true
        // 确认生效的同步入口立即停报推送（共性第 2 条）；撤回停报闸门与会话无关，撤回完成前登录新会话也不恢复上报（Android R1-01，共性第 19 条）
        push.beginWithdraw()
        pushTracker.cancelColdStart()
        // APP-10b G01：此刻仍是完整模式——Agent 先停流并以原身份尽力发 1 次 cancel，早于撤回请求与本地清理
        val agentCancel = beginAgentIdentityChange(AgentOwnershipEvent.PRIVACY_WITHDRAWN)
        val version = privacyVersion()
        val body =
            PrivacyWithdrawFlow
                .request(system.deviceId() != null && version != null, version ?: 0, clock())
                ?.takeIf { !gates.consent.active }
        val epochs = client.inFlight.epochs()
        scope.launch {
            try {
                agentCancel.awaitWritten()
                if (body != null) {
                    // 有令牌也是设备级（服务端处理）；结果不影响本地撤回（G24）。超时上限照网络层写接口默认，这里另设上限防止卡住撤回
                    withTimeoutOrNull(WITHDRAW_TIMEOUT_MS) {
                        api.send(ApiCall(RECORD_CONSENT, body = body.toString(), ownership = CallOwnership(epochs) { true }))
                    }
                }
                val failure = runCatching { privacy.withdraw() }.exceptionOrNull()
                mutableWithdrawing.value = false
                onConsentRevoked()
                if (failure != null) mutableWithdrawFailed.value = ++failures
            } finally {
                // 撤回清理结束（含落盘失败）之后才解除撤回停报闸门：此时已是基本模式，上报仍按隐私结论不发
                mutableWithdrawing.value = false
                push.endWithdraw()
                endAgentIdentityChange(AgentOwnershipEvent.PRIVACY_WITHDRAWN)
            }
        }
    }

    /** 「撤回没有生效」的【重试】（提示框的一次性编号 [id]，R1-02）：再试一次本地清理（AppPrivacy 的凭据读取闸会重试撤回清理）。 */
    fun retryWithdrawCleanup(id: Long) {
        if (mutableWithdrawFailed.value != id) return
        mutableWithdrawFailed.value = null
        val ok = privacy.credentialAccess.readable()
        if (!ok) mutableWithdrawFailed.value = ++failures
        syncIdentity()
    }

    /** 【关闭】、系统返回（同一编号才生效）。 */
    fun dismissWithdrawFailed(id: Long) {
        if (mutableWithdrawFailed.value == id) mutableWithdrawFailed.value = null
    }

    /** 撤回同意的清理（撤回清理名单的一项，AppModule 生产注册；也在协调器里调一次）：Me 快照、草稿、推送上报状态、客服半屏。 */
    fun onConsentRevoked() {
        clearForConsentRevoked()
        syncIdentity()
    }

    /** 撤回清理名单里的一项（G29）：只清内存里的会话级数据（Me 快照、草稿随页面执行器重建作废、推送待报与在途、客服半屏、禁用期），不读隐私状态。 */
    fun clearForConsentRevoked() {
        entered10006 = null
        push.consentWithdrawn()
        pushTracker.cancelColdStart()
        cs.invalidate()
        gates.reset()
        deps.repo.reset()
        deps.tabScroll.reset()
        account?.reset()
    }

    /** 切换环境（清理名单的一项）：会话级数据清空（设备级偏好不清，G29）。 */
    fun onEnvironmentSwitched() {
        entered10006 = null
        push.loggedOut()
        pushTracker.cancelColdStart()
        cs.invalidate()
        gates.reset()
        deps.repo.reset()
        deps.tabScroll.reset()
        account?.reset()
    }

    /**
     * 退出登录（设置页、RiskNotice、申诉页的确认之后）：同步停报推送 → 已有 logout()（失败也完成本地退出，已有行为）→ 原会话确实结束、现在不是已登录、发起页面仍在
     * （或是被这次会话清理带走的只能登录访问的页面）、发起之后没有经 Router 再导航 → 经 Router 回「我的」游客态（R1-09，flow-map §23.1.9）。
     */
    @Suppress("ComplexCondition") // 原会话结束、不是已登录、发起页面仍在、没有再导航逐项
    fun logout(
        identity: MeIdentity,
        stillHere: () -> Boolean,
    ) {
        val appLogin = login() ?: return
        if (loggingOut || !identity.current(env) || !identity.member) return
        loggingOut = true
        val origin = topRoute()
        val ticket = tickets.routerTicket()
        push.halt()
        // APP-10b G01：确认后的同步入口——Agent 先停流并以原身份尽力发 1 次 cancel（单次发送），等它写出再发 logout
        val agentCancel = beginAgentIdentityChange(AgentOwnershipEvent.LOGOUT)
        scope.launch {
            val done =
                try {
                    agentCancel.awaitWritten()
                    appLogin.logout()
                } finally {
                    loggingOut = false
                    endAgentIdentityChange(AgentOwnershipEvent.LOGOUT)
                }
            syncIdentity()
            val here = runCatching(stillHere).getOrDefault(false) || origin in LOGIN_ONLY
            if (done && computeIdentity().level != MeLevel.MEMBER && here && tickets.routerTicket() == ticket) open(ME)
        }
    }

    private fun privacyVersion(): Int? =
        privacy.consentedVersion()?.toIntOrNull()
            ?: (
                (
                    store
                        ?.snapshots
                        ?.value
                        ?.data
                        ?.get(LEGAL) as? JsonObject
                )?.get(PRIVACY) as? JsonObject
            )?.get(VERSION)
                ?.let { (it as? JsonPrimitive)?.intOrNull }

    // ---------------------------------------------------------------- 环境

    private inner class Env : MeEnv {
        override fun identity(): MeIdentity = computeIdentity()

        override val identityFlow: StateFlow<MeIdentity> get() = identity

        // F-X-14（FU-AND-0717）：「我的」Tab 的自动同步按会话作用域决定是否读 getMe（与网络层同一份有效会话）
        override fun sessionScope(): String = client.activeSessionScope().orEmpty()

        override fun open(
            route: String,
            params: JsonObject,
        ) = this@AppMe.open(route, params)

        override fun copy(text: String): Boolean = writer(text)

        override fun forceUpdate() = serverForced()

        override fun configData(): JsonObject? = store?.snapshots?.value?.data

        // 路由可用性（APP-13a G01）：since 满足或 Debug 覆盖（Release 恒为空集），与守卫同一判定
        override fun availableRoutes(): Set<String> = routing.availableRoutes()

        override fun earningsFlag(): Boolean? {
            val flags = (configData()?.get(FEATURES) as? JsonObject)?.get(FLAGS) as? JsonObject
            return (flags?.get(EARNINGS_FLAG) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
        }

        override fun customerService(fromRoute: String) = cs.open(fromRoute)

        override fun notificationStatus(): String =
            when {
                system.pushStatus() == GRANTED -> GRANTED
                system.pushDeniedAt() != null -> DENIED
                else -> NOT_DETERMINED
            }

        override fun notificationDeniedAt(): Long? = system.pushDeniedAt()

        override fun openNotificationSettings() = system.openNotificationSettings()

        // G26：锁定契约的 system-permissions.json 没有 Android 条目，一节只显示标题与提示（TODO(N-33)：契约补条目后由生成物给，不手写清单）
        override fun permissionEntries(): List<JsonObject> = emptyList()

        override fun permissionStatus(type: String): String = if (type == PUSH) system.pushStatus() else NOT_ASKED

        override fun openAppSettings() = system.openAppSettings()

        override fun requestFullFunction() = privacy.requestFullFunction()

        override fun forceUpdateContext(): Boolean = updateCheck() == UpdateCheck.BELOW_MIN

        override fun enteredBy10006(identity: MeIdentity): Boolean = this@AppMe.enteredBy10006(identity)

        override fun deviceRegistered(): Boolean = system.deviceId() != null

        override fun consentedPrivacyVersion(): Int? = privacy.consentedVersion()?.toIntOrNull()

        override fun legalPrivacyVersion(): Int? =
            (
                (
                    configData()?.get(
                        LEGAL,
                    ) as? JsonObject
                )?.get(PRIVACY) as? JsonObject
            )?.get(VERSION)?.let { (it as? JsonPrimitive)?.intOrNull }
                ?: consentedPrivacyVersion()

        override fun cacheBytes(): Long? = system.cacheBytes()

        override fun clearCache(): Boolean = system.clearCache()

        override fun clearWebCache(): Boolean = system.clearWebCache()

        override fun appVersion(): String = BuildConfig.VERSION_NAME.substringBefore('-')

        override fun appBuild(): String = BuildConfig.VERSION_CODE.toString()

        override fun diagnosticsFields(): JsonObject {
            val data = configData()
            val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
            fields["version"] = JsonPrimitive(appVersion())
            fields["build"] = JsonPrimitive(appBuild())
            fields["channel"] = JsonPrimitive(com.couli.generated.InstallChannel.OFFICIAL.raw)
            system.osVersion()?.let { fields["system_version"] = JsonPrimitive(it) }
            system.model()?.let { fields["model"] = JsonPrimitive(it) }
            system.deviceId()?.let { fields["device_id"] = JsonPrimitive(it) }
            fields["push_reported"] = JsonPrimitive(push.reportedThisRun)
            // 最近 3 个 trace_id：网络层没有记录，整行省略（rule-gaps A 段）
            (data?.get(DICT_VERSION) as? JsonPrimitive)?.let { fields["config_version"] = it }
            // 首页版本（G31）：当前显示的首页 version 与来源级别（APP-11 HomeStore.currentVersion）；没有显示中的页面整行省略
            val home = homeVersion
            if (home != null) {
                home()?.let { fields["home_version"] = JsonPrimitive(it) }
            } else {
                ((data?.get(H5_RELEASE) as? JsonObject)?.get(VERSION) as? JsonPrimitive)?.let { fields["home_version"] = it }
            }
            return JsonObject(fields)
        }

        override fun logout(
            identity: MeIdentity,
            stillHere: () -> Boolean,
        ) = this@AppMe.logout(identity, stillHere)

        override fun withdrawConsent(identity: MeIdentity) = this@AppMe.withdrawConsent(identity)

        override val withdrawing: StateFlow<Boolean> get() = mutableWithdrawing.asStateFlow()

        override fun close(entryId: String?) {
            val controller = navController ?: return
            if (entryId == null || controller.currentBackStackEntry?.id == entryId) controller.popBackStack()
        }

        override fun tipsReset() = this@AppMe.tipsReset()
    }

    /** 接 Router 的会话状态、隐私结论、版本状态（AppModule 与集成测试共用）。 */
    fun connect(onUpdate: ((UpdateCheck) -> Unit) -> Unit) {
        privacy.onConclusionChanged { syncIdentity() }
        onUpdate { syncIdentity() }
        login()?.onLoginCompleted = { epoch ->
            pushTracker.loginCompleted(epoch)
        }
        scope.launch { routing.session.drop(1).collect { syncIdentity() } }
        syncIdentity()
    }

    companion object {
        const val ME = "Me"
        const val HELP = "Help"
        const val AGREEMENT = "Agreement"
        const val RISK_NOTICE = "RiskNotice"
        const val DELETE_ACCOUNT = "DeleteAccount"
        const val APPEAL = "Appeal"
        const val RECORD_CONSENT = "recordConsent"
        const val ME_SESSION_CLEANER = "me_session"
        val LOGIN_ONLY: Set<String> = setOf(RISK_NOTICE, APPEAL)
        private const val KF = "kf"
        private const val URL = "url"
        private const val LEGAL = "legal"
        private const val PRIVACY = "privacy"
        private const val VERSION = "version"
        private const val FEATURES = "features"
        private const val FLAGS = "flags"
        private const val EARNINGS_FLAG = "earnings.dashboard.visible"
        private const val DICT_VERSION = "dict_version"
        private const val H5_RELEASE = "h5_release"
        private const val PUSH = "push"
        private const val GRANTED = "granted"
        private const val DENIED = "denied"
        private const val NOT_DETERMINED = "not_determined"
        private const val NOT_ASKED = "not_asked"
        private const val BELOW = "below"
        private const val SUPPORTED = "supported"
        private const val UNKNOWN = "unknown"
        private const val WITHDRAW_TIMEOUT_MS = 15_000L
    }
}
