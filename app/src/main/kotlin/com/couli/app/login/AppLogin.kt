package com.couli.app.login

import android.annotation.SuppressLint
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.core.network.ApiClient
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.SessionScopeRaw
import com.couli.core.network.SessionTokens
import com.couli.core.router.RouteCatalog
import com.couli.core.router.RouteNames
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.core.wallet.WalletRateGate
import com.couli.feature.login.AfterLoginUpdate
import com.couli.feature.login.BindPhoneHost
import com.couli.feature.login.BindPhoneOwner
import com.couli.feature.login.BindPhonePageActions
import com.couli.feature.login.BindPhonePageConfig
import com.couli.feature.login.BindPhoneService
import com.couli.feature.login.InvitePasteSource
import com.couli.feature.login.LegalVersions
import com.couli.feature.login.LoginHost
import com.couli.feature.login.LoginOutcome
import com.couli.feature.login.LoginPageActions
import com.couli.feature.login.LoginPageConfig
import com.couli.feature.login.LoginService
import com.couli.feature.login.MeReadLimit
import com.couli.feature.login.OAuthLoginOutcome
import com.couli.feature.login.OAuthLoginRoute
import com.couli.feature.login.OAuthLoginService
import com.couli.feature.login.OAuthProvider
import com.couli.feature.login.PhoneBindingState
import com.couli.feature.login.ThirdPartyAuthAdapter
import com.couli.feature.login.UnavailableWechatAdapter
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 登录成功后在外壳上显示的提示（LoginInviteBound 轻提示、LoginInviteFailed 弹窗；BR-INV-06 细则）。 */
sealed interface LoginNotice {
    data object InviteBound : LoginNotice

    data class InviteFailed(
        val rateLimited: Boolean,
    ) : LoginNotice

    /** 登录页已关闭时的轻提示（如受限登录 10405 no_account 回到强更页后提示）。 */
    data class Message(
        val text: String,
    ) : LoginNotice

    /** 第三方新号登录成功：绑手机引导（LoginBindPhoneGuide，BR-INV-03 细则 ①），盖在恢复页之上。[provider]：登录方式。 */
    data class BindPhoneGuide(
        val provider: String,
    ) : LoginNotice
}

/**
 * 一次等待登录页结束的桥调用 auth.login（APP-04 共性第 66 条）。[entryId]：登记之后出现在回退栈里的那个 Login 条目（这次调用打开的
 * 登录页）；[result]：完成登录时为这次登录保存的登录世代，取消或离开登录页时为 null。只在主线程读写。
 */
class BridgeLoginWait internal constructor() {
    internal val result = CompletableDeferred<Long?>()
    internal var entryId: String? = null
}

/**
 * App 侧的登录（APP-05a）：组合 [LoginService]（ApiClient、会话令牌、本类作为 [LoginHost]），给登录页配置，给守卫会话状态，登出。
 * - 会话状态：按 ApiClient 的有效会话现算（经过隐私、撤回清理与会话隔离判断）：没有 → 游客；deletion_only → 受限；其余只有确认
 *   phone_bound = true（本次短信登录完成、getMe、绑定成功）才是已绑手机，未知（冷启动、待查）与 false 都是已登录未绑手机（APP-05b
 *   共性第 13 条）。未知时先查当前会话的 getMe（[verifyPhoneBinding]），主动打开与待办恢复两个入口都按守卫的状态走。
 * - 导航：登录页出栈的同步出口（回退栈一变就判断，[followBackStack]）立即作废本次登录尝试（共性第 9 条）；执行器的 closeLogin 明确
 *   把 Login 从回退栈移除（共性第 2 条，受限登录同样）。
 * - 网络层刷新失败清会话（clear_session_login）：同步会话状态、下次打开的登录页提示一次 error.10404（LoginSessionExpired）并打开登录页；
 *   强更状态下（clear_session_stay_force_update）只同步。登出期间不跳登录。
 * - 会话通知（共性第 14 条）：网络层保存、刷新、清理会话之后带世代通知（[onSessionStored]，已切到主线程），这里按最新会话同步 Router；
 *   受限会话（deletion_only）在同一登录世代里刷新成 full 时经守卫恢复待办——登录流程正在处理或已作废的那一代除外（[LoginService.ownsSession]）。
 * - 登出（共性第 13 条）：在 App 级的 [sessionScope]（主线程）里执行，设置页离开、重建都不打断本机清理。
 * 只在主线程使用。
 */
@Suppress("TooManyFunctions", "LongParameterList") // LoginHost 的各个出口与页面配置集中在一处；依赖逐项注入（单测替身）
class AppLogin(
    private val client: ApiClient,
    private val tokens: SessionTokens,
    private val routing: AppRouting,
    /** 强更检查进度（AppUpdate.routerUpdate）。 */
    private val updateCheck: () -> UpdateCheck,
    /** 受限会话按服务端已判定强更处理（AppUpdate.onServerForced）。 */
    private val markBelow: () -> Unit,
    legal: LegalVersions,
    /** App 级（会话级）作用域：登出及其收尾在这里执行（主线程），不随页面取消。 */
    private val sessionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** 第三方登录适配器（APP-05b）：Release 是恒不可用的微信适配器；Debug / Staging 在本机假服务打开时是假适配器。 */
    adapters: List<ThirdPartyAuthAdapter> = listOf(UnavailableWechatAdapter()),
    /**
     * Me 读取（GET /v1/me）的会话级共享禁用期（APP-12 Android R2-05，共性第 32 条）：冷启动 / 登录后校验 phone_bound 的 getMe、绑定成功后的 getMe 与「我的」类
     * 页面（AppMe 的 MeGates.read 就是这一个）共用同一个截止时间。AppLogin 先于 AppMe 建，所以由这里持有。
     */
    val meReadGate: WalletRateGate = WalletRateGate(sessionScope),
) : LoginHost,
    BindPhoneHost {
    val service: LoginService = LoginService(client, tokens, this, legal, sessionScope)

    /**
     * 登录任务进行中（FU-AND-0724 G28：剪贴板自动识别的普通互斥模态 login，首页等非登录页上发起的登录任务）：真实 [LoginService] 的发码、
     * 短信登录或第三方登录在途（[LoginService.busyState]），任务结束即为 false。登录页本身另按敏感页处理。
     */
    val taskActive: StateFlow<Boolean> get() = service.busyState

    /** 本机已知的「是否绑了手机」（按会话；第三方登录未绑 → 守卫按已登录未绑手机，APP-05b）。 */
    val phoneBinding = PhoneBindingState()

    /** 第三方登录执行器（APP-05b）。 */
    val oauth: OAuthLoginService =
        OAuthLoginService(client, tokens, service, adapters, phoneBinding, legal, followUpScope = sessionScope, meRead = meReadLimit())

    /** 绑手机执行器（APP-05b）。 */
    val bindPhone: BindPhoneService =
        BindPhoneService(client, tokens, service, phoneBinding, this, meRead = meReadLimit(), sessionScope = sessionScope)

    /** 登录侧看到的 Me 读取禁用期（与「我的」同一个 [meReadGate]）。 */
    private fun meReadLimit(): MeReadLimit =
        object : MeReadLimit {
            override val active: Boolean get() = meReadGate.active

            override fun start(millis: Long) = meReadGate.start(millis)
        }

    /** 最近一次第三方登录的方式（并号确认正文的 {provider_name}；Android 只有微信）。 */
    private var lastProvider: String = OAuthProvider.WECHAT

    /** 已处理过的最新会话世代（乱序到达的旧通知丢弃）。 */
    private var handledGeneration = -1L

    /** 最近一次看到受限会话时的登录世代（同一登录世代里刷新成 full 才算「受限转 full」）。 */
    private var restrictedEpoch: Long? = null
    private var navController: NavController? = null
    private var loginInStack = false
    private var sessionExpiredPending = false
    private val mutableNotice = MutableStateFlow<LoginNotice?>(null)

    /** 登录页关闭之后在外壳上显示的提示（邀请绑定结果、回到强更页的原因；显示后 [consumeNotice]）。 */
    val notice: StateFlow<LoginNotice?> = mutableNotice.asStateFlow()

    /** phone_bound 补查任务（[verifyPhoneBinding]、[recheckOnBindPage]）：查询连同收尾（守卫同步、出栈、恢复待办）都在任务里。只在主线程读写。须在 init 之前初始化（init 里就会发起冷启动补查）。 */
    private val phoneChecks = mutableListOf<Job>()

    /** 已发起的补查次数（F-AND-36 测试观察点：等「查询连同收尾都已结束」，不猜网络层何时空闲）。 */
    internal var phoneChecksStarted = 0
        private set

    /** 已发起的补查任务都已结束（含查询返回后的收尾）。 */
    internal fun phoneChecksSettled(): Boolean = phoneChecks.all { it.isCompleted }

    /** 在会话作用域里发起一次补查并登记；[context] 照各入口原来的调度（冷启动补查投递到下一轮主线程消息，进页补查沿用作用域的 immediate）。 */
    private fun trackPhoneCheck(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        phoneChecks.removeAll { it.isCompleted }
        phoneChecksStarted += 1
        phoneChecks += sessionScope.launch(context, block = block)
    }

    init {
        // APP-05a 共性第 25 条：pending_action 登记时记下此刻的登录世代，会话结束的清理只清原会话归属的待办
        routing.router.sessionTag = { tokens.loginEpoch }
        // APP-05b 共性第 8 条：恢复手机号级路由前，phone_bound 未知的会话（冷启动）先查清
        routing.router.resumeGate = ::phoneLevelReady
        // 短信登录的会话手机号已知已绑（保存同步区里记下）
        service.smsSessionSaved = { epoch -> phoneBinding.set(epoch, true) }
        publish(sessionState(), resumePending = false)
        // 共性第 13 条：冷启动有会话而 phone_bound 未知 → 守卫按已登录未绑手机，同时查当前会话的 getMe
        verifyPhoneBinding()
    }

    /** 正在查的会话（登录世代），避免重复查。 */
    private var meCheckEpoch: Long? = null

    /**
     * phone_bound 未知时查清（APP-05b 共性第 8、13 条）：当前是正常会话、这个会话没有确认的 phone_bound、也没有在查 → 下一轮主线程消息里
     * 查 getMe（不在 Router 的恢复调用里同步改状态）。结果只记到发起查询的会话（登录世代、更新世代都没变，[OAuthLoginService.refreshMe]），
     * 查清后同步守卫并按冷启动口径经 Router 恢复待办（已绑 → 执行；未绑 → 保留，不自动拉起绑手机页）；查询失败保留待办，下次会话同步、
     * 进绑手机页时再查（没有定时重查）。
     */
    private fun verifyPhoneBinding() {
        val epoch = tokens.loginEpoch
        // 第三方登录流程还在进行（刚保存的会话由它自己记 phone_bound：新号 false、老用户由它查 getMe）：不另查
        val needed =
            client.activeSessionScope() == SessionScopeRaw.FULL && phoneBinding.get(epoch) == null && meCheckEpoch != epoch && !oauth.busy
        if (!needed) return
        meCheckEpoch = epoch
        trackPhoneCheck(Dispatchers.Main) {
            val known =
                try {
                    oauth.refreshMe(epoch)
                } finally {
                    if (meCheckEpoch == epoch) meCheckEpoch = null
                }
            if (known && tokens.loginEpoch == epoch) {
                publish(sessionState(), resumePending = false)
                routing.router.resumePending(enterPrerequisite = false)
            }
        }
    }

    /**
     * 恢复前的核对（APP-05b 共性第 8 条）：目标是手机号级路由（auth=phone）、当前是正常会话、而这个会话的 phone_bound 还不知道（冷启动、
     * 待查）→ 按「未验证」处理：这次不恢复、保留待办（也不拉起绑手机页），并查当前会话的 getMe；查清后再经 Router 恢复。
     */
    @Suppress("ReturnCount") // 逐项放行
    private fun phoneLevelReady(request: RouteRequest): Boolean {
        if (RouteCatalog.spec(request.route, includeDebugRoutes = false)?.auth != AUTH_PHONE) return true
        if (client.activeSessionScope() != SessionScopeRaw.FULL) return true
        if (phoneBinding.get(tokens.loginEpoch) != null) return true
        verifyPhoneBinding()
        return false
    }

    /**
     * 守卫用的会话状态：没有有效会话 → 游客；deletion_only → 受限；确认 phone_bound = true（本次短信登录完成、getMe、绑定成功）→ 已绑
     * 手机；其余（第三方未绑、待查、冷启动没有记录）→ 已登录未绑手机（APP-05b 共性第 13 条：未知不当已绑，主动打开与待办恢复同一口径）。
     */
    fun sessionState(): SessionState =
        when (client.activeSessionScope()) {
            null -> SessionState.GUEST
            SessionScopeRaw.DELETION_ONLY -> SessionState.RESTRICTED
            else -> if (phoneBinding.treatAsUnbound(tokens.loginEpoch)) SessionState.LOGGED_IN else SessionState.PHONE_BOUND
        }

    /** 隐私结论、强更状态变化（撤回清理、受限会话刷新后）：只同步会话状态，不额外恢复待办（那两处自己会排恢复）。 */
    fun syncSession() {
        val state = sessionState()
        if (state != routing.session.value) publish(state, resumePending = false)
        dropStaleBoundWhileAway()
        // APP-13a R2-01：绑手机页的同步换代出口（撤回同意、强更状态变化后的会话同步）
        bindPhone.syncSession()
        verifyPhoneBinding()
    }

    /**
     * 网络层的会话通知（NetworkHooks.onSessionStored，已切到主线程；共性第 14 条）：会话保存、刷新、清理完成之后按最新会话同步 Router。
     * 受限会话在同一登录世代里刷新成 full（运营下调最低版本后网络层的异步刷新等），且这一代不归登录流程管时，经守卫恢复保留的待办
     * （Router 按当前状态重新判定）；其余（新登录、已作废登录尝试保存的会话、清除）只同步状态，待办由各自的流程处理。
     */
    fun onSessionStored(generation: Long) {
        if (generation < handledGeneration) return
        handledGeneration = generation
        val state = sessionState()
        val previous = routing.session.value
        val epoch = tokens.loginEpoch
        val upgraded =
            previous == SessionState.RESTRICTED &&
                (state == SessionState.PHONE_BOUND || state == SessionState.LOGGED_IN) &&
                epoch == restrictedEpoch &&
                !service.ownsSession(epoch)
        if (state != previous || upgraded) publish(state, resumePending = upgraded)
        dropStaleBoundWhileAway()
        // APP-13a R2-01：会话保存、刷新（作用域变化）、清理的同步出口——打开着的绑手机页在这条消息里按完整固定身份换代、清空敏感状态
        bindPhone.syncSession()
        verifyPhoneBinding()
        // 按登录世代（不是会话枚举）换代的订阅方：同一身份状态下的换号枚举不变（FU-AND-0724 代码复审 r1 S2-2）
        sessionStoredListener?.invoke()
    }

    /** 写入守卫的会话状态（并记下受限会话所在的登录世代）。 */
    private fun publish(
        state: SessionState,
        resumePending: Boolean,
    ) {
        if (state == SessionState.RESTRICTED) restrictedEpoch = tokens.loginEpoch
        routing.updateSession(state, resumePending)
    }

    fun attach(controller: NavController) {
        navController = controller
    }

    fun detach(controller: NavController) {
        if (navController === controller) navController = null
    }

    /**
     * 回退栈变了（主线程、同一条消息里）：Login 刚离开回退栈 → 立即作废本次登录尝试并取消它的请求（共性第 9 条）。等待中的桥调用
     * auth.login 绑定登记之后新出现的 Login 条目；那个条目离开回退栈（取消、返回、被清栈）即以 logged_in = false 结束（共性第 66 条）。
     */
    fun followBackStack(entries: List<NavBackStackEntry>) {
        if (entries.isEmpty()) return
        val logins = entries.filter(::isLogin).map { it.id }.toSet()
        val appeared = logins - loginEntries
        bridgeWaits.toList().forEach { wait ->
            val entry = wait.entryId
            if (entry == null) {
                wait.entryId = appeared.firstOrNull()
            } else if (entry !in logins) {
                wait.result.complete(null)
            }
        }
        loginEntries = logins
        val present = logins.isNotEmpty()
        if (loginInStack && !present) service.invalidateAttempt()
        loginInStack = present
        // 绑手机页出栈的同步出口（APP-05a 共性第 9 条同理）：作废在途请求与并号凭据；没有绑定就离开 → 暂缓的待办经守卫恢复
        val binding = entries.any { isRoute(it, RouteNames.BIND_PHONE) }
        // 进绑手机页时 phone_bound 还不知道（待查、冷启动后直接打开手机号级页面）：再查一次，查清已绑就关页并恢复待办（不算完成前置、
        // 不重置计时）
        if (!bindInStack && binding && phoneBinding.get(tokens.loginEpoch) == null) {
            recheckOnBindPage(entries.lastOrNull { isRoute(it, RouteNames.BIND_PHONE) }?.id)
        }
        if (bindInStack && !binding) {
            boundWhileAway = null
            bindPhone.invalidate()
            if (phoneBinding.get(tokens.loginEpoch) != true) bindPhone.leftUnbound()
        }
        bindInStack = binding
    }

    private var bindInStack = false

    /**
     * 进绑手机页时 phone_bound 未知的补查（APP-13a R2-03，共性第 25、38 条）：发起时捕获刚出现的这一个绑手机页条目 [entryId]、导航票据（导航次数、栈顶、Tab、
     * 进后台次数，即页面可见性与导航世代）与完整固定身份。查清已绑：绑定状态共享结算（守卫重算、释放暂缓）照常；出栈只针对这一个页面并恢复待办，不按路由名关页
     * （不会连带关掉覆盖页或后来打开的同名页）。查询返回时分三种（FU-AND-0716 R1-04，APP-05b 共性第 25 条两种顺序）：
     * - 票据没变（发起后没有被覆盖、没有进后台、没有再导航）→ 出栈；
     * - 票据变了，但原条目此刻已重新是栈顶且处于前台（「页面先恢复、查询后完成」：onPageVisible 已经跑过、不会再来）→ 核对固定身份、原条目、共享已绑后立即收尾；
     * - 原条目此刻仍不可见 → 记下绑原身份的延后记录，原条目重新有效时由页面经 [bindPageAlreadyBound] 补处理；换会话即清除（共性第 26 条）。
     */
    private fun recheckOnBindPage(entryId: String?) {
        val epoch = tokens.loginEpoch
        val owner = bindPhone.owner()
        val ticket = routing.navTickets.ticket()
        trackPhoneCheck {
            if (oauth.refreshMe(epoch) && bindPhone.owns(owner) && phoneBinding.get(epoch) == true) {
                val controller = navController
                val top = controller?.currentBackStackEntry
                val here = entryId != null && top?.id == entryId && routing.navTickets.ticket() == ticket
                if (controller != null && here) {
                    bindInStack = false
                    controller.popBackStack()
                    publish(sessionState(), resumePending = false)
                    routing.router.releaseHeldPending()
                    routing.router.resumePending(enterPrerequisite = false)
                    return@trackPhoneCheck
                }
                // 那次结果不按发起时的票据导航；原条目已重新可见就当场收尾，否则延后到它重新有效（FU-AND-0716，iOS R1-08 / R1-04）
                boundWhileAway = entryId?.let { BoundWhileAway(it, owner) }
                if (entryId != null && top?.id == entryId && top.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    bindPageAlreadyBound(entryId)
                    return@trackPhoneCheck
                }
                publish(sessionState(), resumePending = false)
                routing.router.releaseHeldPending()
            }
        }
    }

    /** 回退栈里此刻的 Login 条目。 */
    private var loginEntries: Set<String> = emptySet()

    /** 等待登录页结束的桥调用（主线程）。 */
    private val bridgeWaits = mutableListOf<BridgeLoginWait>()

    /** 桥调用 auth.login 打开登录页之前登记等待（之后出现的 Login 条目归它）。结束时必须 [endBridgeLogin]。 */
    fun beginBridgeLogin(): BridgeLoginWait = BridgeLoginWait().also { bridgeWaits += it }

    fun endBridgeLogin(wait: BridgeLoginWait) {
        bridgeWaits -= wait
        wait.result.complete(null)
    }

    /**
     * 等登录页结束（共性第 66 条）：完成登录返回这次登录保存的登录世代；取消、离开登录页、归属失效（[stillValid] 为 false：页面换文档、
     * 撤回同意、切换环境）、或登录页在 [ENTRY_WAIT_MS] 内没有出现，返回 null。
     */
    @Suppress("ReturnCount") // 归属失效、登录页没出现、结束三个出口
    suspend fun awaitBridgeLogin(
        wait: BridgeLoginWait,
        stillValid: () -> Boolean,
    ): Long? {
        val start = System.nanoTime()
        while (!wait.result.isCompleted) {
            if (!stillValid()) return null
            val waited = (System.nanoTime() - start) / NANOS_PER_MILLI
            if (wait.entryId == null && waited > ENTRY_WAIT_MS) return null
            withTimeoutOrNull(WAIT_POLL_MS) { wait.result.await() }
        }
        return wait.result.await()
    }

    /** 邀请码的剪贴板来源（APP-07，AppModule 接 AppClipboard）；没有时登录页不显示【粘贴】。 */
    var invite: InvitePasteSource? = null

    /** 会话保存、刷新、清理通知的转发（AppModule 接剪贴板，按登录世代判断换号）；在 Router 同步之后调。 */
    var sessionStoredListener: (() -> Unit)? = null

    /** 统一客服出口（APP-12 brief §2.9；AppModule 接 AppMe）。没接时打开帮助中心。 */
    var customerService: (fromRoute: String) -> Unit = { open(HELP_ROUTE) }

    /** 登录有效完成时的登录世代（APP-12 推送上报只认它为「真实登录成功」，共性第 3 条；AppModule 接 AppMe）。 */
    var onLoginCompleted: (loginEpoch: Long) -> Unit = {}

    /** 登录页配置。[restricted]：从强更页进入（APP-02c 已有标记：强更 below_min 时 Login 只能从强更页进入）。 */
    fun pageConfig(restricted: Boolean): LoginPageConfig {
        val expired = sessionExpiredPending
        sessionExpiredPending = false
        return LoginPageConfig(
            service = service,
            texts = LoginDraftTexts,
            restricted = restricted,
            sessionExpired = expired,
            // 第三方登录（APP-05b）：Android 只有微信；适配器不可用（正式包恒为不可用）或本机确知没装时不显示（任务书 §2.2）
            oauth = oauth,
            // 共性第 28 条（FU-AND-0716 第 2 轮）：受限登录也保留第三方入口——仅第三方身份的已有账号要能登进 deletion_only 去注销（BR-ID-01）
            thirdParty = oauth.visibleProviders(),
            // 邀请码的剪贴板来源（APP-07：游客邀请预填、邀请码行【粘贴】）；受限登录不用
            invite = if (restricted) null else invite,
            actions =
                LoginPageActions(
                    onClose = ::closeLogin,
                    onSettings = { open(SETTINGS_ROUTE) },
                    // login.help_entry：help_links.login_help 未配置时打开帮助中心首页（BR-TEXT-14 表 C）。TODO(N-26)：接 help_links.login_help
                    onHelp = { open(HELP_ROUTE) },
                    onAgreement = { type -> openAgreement(type, restricted) },
                    // 统一客服出口（APP-12 brief §2.9，G20）：原样 kf.url，未装微信给复制客服链接与帮助中心
                    onCustomerService = { customerService(RouteNames.LOGIN) },
                    onForceUpdate = { message ->
                        closeLogin()
                        if (message.isNotEmpty()) mutableNotice.value = LoginNotice.Message(message)
                        routing.drain()
                    },
                    onLoggedIn = ::onLoggedIn,
                    onOAuthLoggedIn = ::onOAuthLoggedIn,
                ),
        )
    }

    /** 绑手机页配置（APP-05b）。[entryId]：这一个绑手机页的回退栈条目（完成后只出栈它，APP-13a R1-05）。 */
    fun bindPageConfig(entryId: String? = null): BindPhonePageConfig =
        BindPhonePageConfig(
            service = bindPhone,
            texts = LoginDraftTexts,
            providerName = LoginDraftTexts.text("login.provider.$lastProvider"),
            actions =
                BindPhonePageActions(
                    onBack = { closeRoute(RouteNames.BIND_PHONE) },
                    // 统一客服出口（APP-12 brief §2.9，G20）
                    onCustomerService = { customerService(RouteNames.BIND_PHONE) },
                    // 10007 stage=cooling：经 Router 打开可撤回的注销进度页（APP-05b 共性第 10 条）
                    onOpenRoute = ::open,
                    // 绑定成功且页面许可仍有效（APP-13a R1-05）：只出栈发起的这一个绑手机页，再完成前置、恢复待办
                    onBound = { bindPageCompleted(entryId) },
                    // 回到页面时已是已绑手机（FU-AND-0716，iOS R1-08）：只出栈这一个绑手机页，按 resumeOnly 恢复待办
                    onAlreadyBound = { bindPageAlreadyBound(entryId) },
                ),
        )

    /**
     * 第三方登录成功（登录页已关闭）：第三方新号弹绑手机引导（盖在恢复页之上，不新增路由，rule-gaps GUIDE-ROUTE）。待办此时已暂缓：
     * 引导【暂不】/ 关闭只恢复不重置，【去绑定】进绑手机页，绑定成功再算一次完成前置（APP-05b 共性第 3 条）。
     */
    private fun onOAuthLoggedIn(outcome: OAuthLoginOutcome) {
        lastProvider = outcome.provider.ifEmpty { OAuthProvider.WECHAT }
        if (outcome.routeRaw == OAuthLoginRoute.BIND_PHONE_GUIDE) mutableNotice.value = LoginNotice.BindPhoneGuide(lastProvider)
    }

    /** 引导【暂不】、点遮罩、返回：暂缓的待办经 Router 守卫恢复（只恢复、不重置计时；仍需手机号的保留，不自动拉起绑手机页）。 */
    fun guideSkipped() {
        consumeNotice()
        publish(sessionState(), resumePending = false)
        routing.router.releaseHeldPending()
        routing.router.resumePending(enterPrerequisite = false)
    }

    /** 引导【去绑定】：经 Router 打开已登记的 BindPhone（待办继续暂缓，绑定成功后恢复）。 */
    fun guideGo() {
        consumeNotice()
        open(RouteNames.BIND_PHONE)
    }

    fun consumeNotice() {
        mutableNotice.value = null
    }

    /**
     * 网络层的 Effect（ApiClient 的 NetworkHooks.onEffect，已切到主线程）：刷新失败清会话后跳登录。登出期间（logout 回 10404 等）只同步，
     * 登出本来就回到游客。
     */
    @Suppress("ReturnCount") // 不是清会话、归属不符、不跳登录三个出口
    fun onNetworkEffect(effect: ErrorEffect) {
        val cleared =
            effect.kind == ErrorEffectKind.CLEAR_SESSION_LOGIN || effect.kind == ErrorEffectKind.CLEAR_SESSION_STAY_FORCE_UPDATE
        if (!cleared) return
        publish(sessionState(), resumePending = false)
        // APP-05a 共性第 26 条：Effect 所属的世代由网络层在清理发生的同步区里定下；之后已有新登录（或别的清理）就不是这次失效的会话，
        // 只同步状态，不跳登录、不提示失效。没有带世代的（清理没有发生）同样不跳
        if (effect.sessionEpoch == null || effect.sessionEpoch != tokens.loginEpoch) return
        if (effect.kind != ErrorEffectKind.CLEAR_SESSION_LOGIN || service.loggingOutNow || loginInStack) return
        sessionExpiredPending = true
        open(RouteNames.LOGIN)
    }

    /** 设置页【退出登录】确认之后。返回 true = 已回到游客。 */
    suspend fun logout(): Boolean = service.logout()

    // ---- LoginHost

    override fun updateState(): String =
        when (updateCheck()) {
            UpdateCheck.CHECKING -> AfterLoginUpdate.CHECKING
            UpdateCheck.UNKNOWN -> AfterLoginUpdate.UNKNOWN
            UpdateCheck.BELOW_MIN -> AfterLoginUpdate.BELOW
            UpdateCheck.OK, UpdateCheck.BELOW_RECOMMENDED -> AfterLoginUpdate.SUPPORTED
        }

    override fun hasPendingAction(): Boolean = routing.router.hasPendingAction

    override fun closeLogin() = closeRoute(RouteNames.LOGIN)

    /** 从回退栈移除最上面的 [name] 路由页（登录页、绑手机页）。 */
    @SuppressLint("RestrictedApi") // 完整回退栈只有 currentBackStack 给得出（只读）
    private fun closeRoute(name: String) {
        val controller = navController ?: return
        val entry = controller.currentBackStack.value.lastOrNull { isRoute(it, name) } ?: return
        controller.popBackStack(entry.toRoute<RouteDestination>(), inclusive = true)
    }

    override fun resumePending() {
        routing.router.onPrerequisiteCompleted()
    }

    override fun discardPending() = routing.router.discardPendingAction()

    /**
     * 受限会话进强更：服务端已判定强更（AppUpdate.onServerForced，落盘、不按缓存最低版本比较，F-AND-23），外壳随之进入强更页。
     * 已是 below_min 也记下（来自缓存最低版本的结论可被之后的检查改写，服务端判定要跨进程保留）。
     */
    override fun enterForceUpdate() {
        markBelow()
        routing.drain()
    }

    override fun sessionChanged() = publish(sessionState(), resumePending = true)

    override fun sessionSynced() = publish(sessionState(), resumePending = false)

    override fun sessionEnded() = routing.router.onSessionEnded()

    /** 登出收尾（APP-05a 共性第 25 条）：只清原会话（登录世代不晚于 [loginEpoch]）归属的待办。 */
    override fun sessionEndedThrough(loginEpoch: Long) = routing.router.onSessionEnded(throughSession = loginEpoch)

    /** 第三方登录完成：登录这一步完成（重置计时），待办暂缓（APP-05b 共性第 3 条）。 */
    override fun prerequisiteDoneHeld() {
        routing.router.onPrerequisiteCompleted(resume = false)
        // 共性第 9 条：暂缓是持续状态，所有自动恢复入口都遵守，直到明确释放
        routing.router.holdPending()
    }

    /** 第三方老用户 phone_bound 已查清：按最新会话状态同步守卫，再经守卫恢复暂缓的待办（不再重置计时）。 */
    override fun heldPendingReady() {
        publish(sessionState(), resumePending = false)
        routing.router.releaseHeldPending()
        routing.router.resumePending()
    }

    /**
     * 第三方老用户 phone_bound 没查清（APP-05b 共性第 14 条）：解除暂缓，待办经守卫按「未验证」恢复——只需登录的照常打开，需要手机号的
     * 保留（不自动拉起绑手机页，恢复前的核对 [phoneLevelReady] 也会再查一次）。
     */
    override fun heldPendingUnverified() {
        publish(sessionState(), resumePending = false)
        routing.router.releaseHeldPending()
        routing.router.resumePending(enterPrerequisite = false)
    }

    // ---- BindPhoneHost

    /**
     * 绑定成功、getMe 已刷新（会话侧共享结算，APP-13a R1-05、G66）：守卫变为已绑手机、释放暂缓。不按路由名关页、不恢复待办——那两件只由持有原页面
     * 许可的绑手机页经 [bindPageCompleted] 做；原页面已离开或被覆盖时不导航，之后的入口由守卫按已绑手机判定。
     */
    override fun phoneBound(owner: BindPhoneOwner) {
        // FU-AND-0716 R1-01：发起绑定 / 确认的固定身份（账号、登录世代、作用域、隐私 / 环境世代）已不是当前会话——getMe 等待期间退出、
        // 另一账号登录且待办正暂缓，或同账号同世代作用域变成 deletion_only——不同步、不释放暂缓
        if (!bindPhone.owns(owner)) return
        publish(sessionState(), resumePending = false)
        routing.router.releaseHeldPending()
    }

    /**
     * 绑手机页完成（页面持有原页面许可时调用，APP-13a R1-05，共性第 25 条）：只出栈发起的这一个绑手机页（它必须是栈顶，不按路由名找同名页），提示绑定成功，
     * 绑定这一前置步骤完成（重置计时）并经守卫恢复待办。
     */
    fun bindPageCompleted(entryId: String?) {
        val controller = navController ?: return
        if (entryId == null || controller.currentBackStackEntry?.id != entryId) return
        controller.popBackStack()
        mutableNotice.value = LoginNotice.Message(LoginDraftTexts.text("bind_phone.done"))
        routing.router.onPrerequisiteCompleted()
    }

    /** 进页补查查明已绑、那次没有导航的绑手机页条目与发起补查时的完整固定身份（FU-AND-0716，iOS R1-08，共性第 26 条）。 */
    private class BoundWhileAway(
        val entryId: String,
        val owner: BindPhoneOwner,
    )

    /** 延后记录（[recheckOnBindPage] 在原条目失去许可期间查明已绑）；条目出栈、换会话即失效。 */
    private var boundWhileAway: BoundWhileAway? = null

    /** 会话同步出口（保存、刷新、清理、撤回同意、切换环境）：延后记录的固定身份已不是当前会话就清除（共性第 26 条）。 */
    private fun dropStaleBoundWhileAway() {
        val away = boundWhileAway ?: return
        if (!bindPhone.owns(away.owner)) boundWhileAway = null
    }

    /**
     * 绑手机页重新有效时共享状态已是已绑手机（FU-AND-0716，iOS R1-08；进页补查 [recheckOnBindPage] 在页面被覆盖 / 进后台期间查明已绑、
     * 那次不导航的补处理）：只对那一个条目、同一登录世代、且它此刻是栈顶时出栈它，同步守卫、释放暂缓、按 resumeOnly 恢复待办（不算完成前置、
     * 不重置计时）。返回是否已退出。
     */
    @Suppress("ReturnCount") // 没有导航、不是那次补查的条目、换了会话或未知已绑、不是栈顶四个出口
    fun bindPageAlreadyBound(entryId: String?): Boolean {
        val controller = navController ?: return false
        val away = boundWhileAway ?: return false
        if (entryId == null || away.entryId != entryId) return false
        if (!bindPhone.owns(away.owner) || phoneBinding.get(away.owner.epoch) != true) {
            // 换了会话（共性第 26 条）：旧会话的延后记录作废
            boundWhileAway = null
            return false
        }
        if (controller.currentBackStackEntry?.id != entryId) return false
        boundWhileAway = null
        // 出栈同步出口照常作废在途请求与并号凭据；已绑离开不算「未绑定离开」（不走 leftUnbound）
        bindPhone.invalidate()
        bindInStack = false
        controller.popBackStack()
        publish(sessionState(), resumePending = false)
        routing.router.releaseHeldPending()
        routing.router.resumePending(enterPrerequisite = false)
        return true
    }

    /** 没有绑定就离开绑手机页：暂缓的待办经守卫恢复（只恢复、不重置；仍需手机号的保留，不再拉起绑手机页）。 */
    override fun leftUnbound() {
        if (client.activeSessionScope() == null) return
        routing.router.releaseHeldPending()
        routing.router.resumePending(enterPrerequisite = false)
    }

    /** 登录有效完成（关闭登录页之前）：等待中的桥调用 auth.login 以 logged_in = true 结算，绑定这次登录的登录世代。 */
    override fun loginCompleted(loginEpoch: Long) {
        // 拷贝一份再逐个结束：结束会就地恢复等待方（主线程 immediate），它的收尾会从列表里移除自己
        bridgeWaits.toList().forEach { it.result.complete(loginEpoch) }
        onLoginCompleted(loginEpoch)
    }

    private fun onLoggedIn(outcome: LoginOutcome) {
        mutableNotice.value =
            when (outcome.inviteResult) {
                INVITE_BOUND -> LoginNotice.InviteBound
                INVITE_FAILED -> LoginNotice.InviteFailed(rateLimited = outcome.inviteCode == RATE_LIMITED)
                else -> null
            }
    }

    private fun open(route: String) {
        routing.router.open(RouteRequest(route, JsonObject(emptyMap()), RouteSource.IN_APP))
    }

    /** 协议链接：受限登录（强更状态）以「从强更页进入」打开，否则守卫会把它重定向回强更页（03 §4.1）。 */
    private fun openAgreement(
        type: String,
        restricted: Boolean,
    ) {
        val request = RouteRequest(AGREEMENT_ROUTE, JsonObject(mapOf("type" to JsonPrimitive(type))), RouteSource.IN_APP)
        val state = routing.guardState()
        routing.router.open(request, if (restricted) state.copy(fromForceUpdatePage = true) else state)
    }

    private fun isLogin(entry: NavBackStackEntry): Boolean = isRoute(entry, RouteNames.LOGIN)

    private fun isRoute(
        entry: NavBackStackEntry,
        name: String,
    ): Boolean =
        entry.destination.hasRoute(RouteDestination::class) &&
            runCatching { entry.toRoute<RouteDestination>().name == name }.getOrDefault(false)

    private companion object {
        const val SETTINGS_ROUTE = "Settings"
        const val HELP_ROUTE = "Help"
        const val AUTH_PHONE = "phone"
        const val AGREEMENT_ROUTE = "Agreement"
        const val INVITE_BOUND = "bound"
        const val INVITE_FAILED = "failed"
        const val RATE_LIMITED = 42901

        /** 桥调用打开登录页后，等登录页出现在回退栈里的最长时间。 */
        const val ENTRY_WAIT_MS = 5_000L
        const val WAIT_POLL_MS = 100L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
