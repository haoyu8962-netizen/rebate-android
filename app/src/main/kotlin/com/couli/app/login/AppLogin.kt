package com.couli.app.login

import android.annotation.SuppressLint
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
import com.couli.core.router.RouteNames
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.feature.login.AfterLoginUpdate
import com.couli.feature.login.LegalVersions
import com.couli.feature.login.LoginHost
import com.couli.feature.login.LoginOutcome
import com.couli.feature.login.LoginPageActions
import com.couli.feature.login.LoginPageConfig
import com.couli.feature.login.LoginService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * - 会话状态：按 ApiClient 的有效会话现算（经过隐私、撤回清理与会话隔离判断）：没有 → 游客；deletion_only → 受限；其余 → 已绑手机
 *   （TODO(APP-05b)：第三方登录的会话按 getMe.phone_bound 区分）。
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
    /** 受限会话按「低于」处理（AppUpdate.on10405(null)）。 */
    private val markBelow: () -> Unit,
    legal: LegalVersions,
    /** App 级（会话级）作用域：登出及其收尾在这里执行（主线程），不随页面取消。 */
    sessionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : LoginHost {
    val service: LoginService = LoginService(client, tokens, this, legal, sessionScope)

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

    init {
        publish(sessionState(), resumePending = false)
    }

    /** 守卫用的会话状态。 */
    fun sessionState(): SessionState =
        when (client.activeSessionScope()) {
            null -> SessionState.GUEST
            SessionScopeRaw.DELETION_ONLY -> SessionState.RESTRICTED
            else -> SessionState.PHONE_BOUND
        }

    /** 隐私结论、强更状态变化（撤回清理、受限会话刷新后）：只同步会话状态，不额外恢复待办（那两处自己会排恢复）。 */
    fun syncSession() {
        val state = sessionState()
        if (state != routing.session.value) publish(state, resumePending = false)
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
                state == SessionState.PHONE_BOUND &&
                epoch == restrictedEpoch &&
                !service.ownsSession(epoch)
        if (state != previous || upgraded) publish(state, resumePending = upgraded)
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

    /** 登录页配置。[restricted]：从强更页进入（APP-02c 已有标记：强更 below_min 时 Login 只能从强更页进入）。 */
    fun pageConfig(restricted: Boolean): LoginPageConfig {
        val expired = sessionExpiredPending
        sessionExpiredPending = false
        return LoginPageConfig(
            service = service,
            texts = LoginDraftTexts,
            restricted = restricted,
            sessionExpired = expired,
            // 第三方登录（APP-05b）：Android 只有微信，本机没装、或还没实现时不显示（任务书 §2）
            showThirdParty = false,
            actions =
                LoginPageActions(
                    onClose = ::closeLogin,
                    onSettings = { open(SETTINGS_ROUTE) },
                    // login.help_entry：help_links.login_help 未配置时打开帮助中心首页（BR-TEXT-14 表 C）。TODO(N-26)：接 help_links.login_help
                    onHelp = { open(HELP_ROUTE) },
                    onAgreement = { type -> openAgreement(type, restricted) },
                    // TODO(N-26)：客服入口（cs.open，/v1/config.kf）在客服任务实现；先打开帮助中心
                    onCustomerService = { open(HELP_ROUTE) },
                    onForceUpdate = { message ->
                        closeLogin()
                        if (message.isNotEmpty()) mutableNotice.value = LoginNotice.Message(message)
                        routing.drain()
                    },
                    onLoggedIn = ::onLoggedIn,
                ),
        )
    }

    fun consumeNotice() {
        mutableNotice.value = null
    }

    /**
     * 网络层的 Effect（ApiClient 的 NetworkHooks.onEffect，已切到主线程）：刷新失败清会话后跳登录。登出期间（logout 回 10404 等）只同步，
     * 登出本来就回到游客。
     */
    fun onNetworkEffect(effect: ErrorEffect) {
        val cleared =
            effect.kind == ErrorEffectKind.CLEAR_SESSION_LOGIN || effect.kind == ErrorEffectKind.CLEAR_SESSION_STAY_FORCE_UPDATE
        if (!cleared) return
        publish(sessionState(), resumePending = false)
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

    @SuppressLint("RestrictedApi") // 完整回退栈只有 currentBackStack 给得出（只读）
    override fun closeLogin() {
        val controller = navController ?: return
        val entry = controller.currentBackStack.value.lastOrNull(::isLogin) ?: return
        controller.popBackStack(entry.toRoute<RouteDestination>(), inclusive = true)
    }

    override fun resumePending() {
        routing.router.onPrerequisiteCompleted()
    }

    override fun discardPending() = routing.router.discardPendingAction()

    /** 受限会话进强更：强更结论还不是 below_min 时按「低于」处理（UpdateGate.on10405），外壳随之进入强更页。 */
    override fun enterForceUpdate() {
        if (updateCheck() != UpdateCheck.BELOW_MIN) markBelow()
        routing.drain()
    }

    override fun sessionChanged() = publish(sessionState(), resumePending = true)

    override fun sessionSynced() = publish(sessionState(), resumePending = false)

    override fun sessionEnded() = routing.router.onSessionEnded()

    /** 登录有效完成（关闭登录页之前）：等待中的桥调用 auth.login 以 logged_in = true 结算，绑定这次登录的登录世代。 */
    override fun loginCompleted(loginEpoch: Long) {
        // 拷贝一份再逐个结束：结束会就地恢复等待方（主线程 immediate），它的收尾会从列表里移除自己
        bridgeWaits.toList().forEach { it.result.complete(loginEpoch) }
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

    private fun isLogin(entry: NavBackStackEntry): Boolean =
        entry.destination.hasRoute(RouteDestination::class) &&
            runCatching { entry.toRoute<RouteDestination>().name == RouteNames.LOGIN }.getOrDefault(false)

    private companion object {
        const val SETTINGS_ROUTE = "Settings"
        const val HELP_ROUTE = "Help"
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
