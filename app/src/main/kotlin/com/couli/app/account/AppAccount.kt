package com.couli.app.account

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import com.couli.app.login.AppLogin
import com.couli.app.me.AppMe
import com.couli.app.me.AppMeTexts
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.wallet.AppWallet
import com.couli.core.me.MeLevel
import com.couli.core.me.account.AccountDraftTexts
import com.couli.core.me.account.AccountEntryPolicy
import com.couli.core.me.account.AccountJson
import com.couli.core.me.account.AccountRaw
import com.couli.core.me.account.AccountShared
import com.couli.core.me.account.BindPhoneSettlePorts
import com.couli.core.me.account.DeletionRead
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiClientPageApi
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingTarget
import com.couli.core.network.SessionTokens
import com.couli.core.router.GuardState
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletRateGate
import com.couli.core.wallet.stepup.SmsDeadlines
import com.couli.core.wallet.stepup.StepUpDeps
import com.couli.feature.me.AccountApi
import com.couli.feature.me.AccountDeps
import com.couli.feature.me.AccountEnv
import com.couli.feature.me.AccountIdentity
import com.couli.feature.me.AccountTexts
import com.couli.feature.me.MeReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** 注销页、换号子页的文案（BR-TEXT-12）：AccountDraftTexts（契约字典 → 本任务草稿 → 钱包 → 「我的」）→ 「我的」与登录的已有草稿表。 */
internal object AppAccountTexts : AccountTexts {
    override fun text(key: String): String = AccountDraftTexts.text(key).ifEmpty { AppMeTexts.text(key) }

    override fun format(
        key: String,
        vars: Map<String, String>,
    ): String = if (AccountDraftTexts.has(key)) AccountDraftTexts.format(key, vars) else AppMeTexts.format(key, vars)

    override fun has(key: String): Boolean = AccountDraftTexts.has(key) || AppMeTexts.text(key).isNotEmpty()
}

/** 生产接线：ApiClient 的普通调用与未决键入口（与钱包 ApiClientWalletApi 同一做法）。 */
class ApiClientAccountApi(
    private val client: ApiClient,
) : AccountApi,
    PageApi by ApiClientPageApi(client) {
    override fun pendingFor(operationId: String): PendingKeyRecord? = client.pendingFor(operationId)

    override fun restorePending(): Boolean = client.restorePending()

    override suspend fun confirmPending(
        operationId: String,
        stepUpToken: String?,
        target: PendingTarget,
    ): ApiResult = withContext(Dispatchers.IO) { client.confirmPending(operationId, stepUpToken, target) }

    override suspend fun abandonPending(
        operationId: String,
        target: PendingTarget,
    ): ApiResult = withContext(Dispatchers.IO) { client.abandonPending(operationId, target) }
}

/**
 * App 侧的注销与换号（APP-13a）：
 * - 会话级共享状态（[shared]：bindPhone / 注销 / 撤回的 42901、bind 发码截止时间、未决结算通知、bindPhone 成功的共享结算），交给 BindPhone（AppLogin）与「我的」共用；
 * - 二次验证共用钱包的同一份发码截止时间、token 缓存与发码 / 验证闸门（[stepUp]，共性第 3、15 条）；
 * - 身份（[identity]）：「我的」身份 + 会话作用域；会话、作用域、隐私、环境变化时同步重算（页面按它重建控制器）；作用域变化清 token（G23）；
 * - 注销页来源交接（G54、G55）：打开 DeleteAccount 之前记下，页面首次组合取走一次；按导航栈条目保存；
 * - 强更页「注销账号 / 撤销注销」标签（[forceUpdateLabel]，G28）与次要入口（游客先受限登录，登录后打开 DeleteAccount 一次）；
 * - 路由可用性（G01）：AppRouting.availableRoutes（含 Debug 覆盖）。
 * 只在主线程使用。
 */
@Suppress("TooManyFunctions", "LongParameterList")
class AppAccount(
    private val client: ApiClient,
    private val tokens: SessionTokens,
    private val routing: AppRouting,
    private val me: AppMe,
    wallet: AppWallet?,
    private val login: AppLogin?,
    private val stepUpPhone: (String) -> String?,
    private val updateCheck: () -> UpdateCheck,
    private val serverForced: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    api: AccountApi = ApiClientAccountApi(client),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var navController: NavController? = null

    /** 二次验证 token 缓存（与钱包同一份；没接钱包的单测自建）。 */
    val tokensCache: StepUpTokenCache = wallet?.tokens ?: StepUpTokenCache()

    /** 验证（stepUp）的 42901 截止时间：钱包、二次验证面板与换号第 1 步共用这一份（R1-14、共性第 31 条）。 */
    private val verifyGate: WalletRateGate = wallet?.gates?.verify ?: WalletRateGate(scope, clock)

    val shared: AccountShared =
        AccountShared(scope, clock) { account, epoch -> tokens.loginEpoch == epoch && client.activeUserId() == account }

    private val mutableIdentity = MutableStateFlow(computeIdentity())
    val identity: StateFlow<AccountIdentity> = mutableIdentity.asStateFlow()

    private val mutableLabel = MutableStateFlow<String?>(null)

    /** 强更页「注销账号 / 撤销注销」的文案键（G28）；null = 不显示（DeleteAccount 不可用、基本模式，G01、G48）。 */
    val forceUpdateLabel: StateFlow<String?> = mutableLabel.asStateFlow()

    /** 打开 DeleteAccount 之前记下的来源（下一次 DeleteAccount 条目取走一次）。 */
    private var pendingSource: String? = null

    /** 已交接的来源：回退栈条目 id → 来源（配置重建沿用；出栈清掉）。 */
    private val sources = mutableMapOf<String, String>()

    /** 强更页游客点「注销账号」：受限登录成功后打开 DeleteAccount 一次（03 §4.1）。 */
    private var openAfterLogin = false

    val env: AccountEnv = Env()

    val stepUp: StepUpDeps =
        StepUpDeps(
            api = ApiClientPageApi(client),
            env = env,
            smsGate = wallet?.gates?.sms ?: WalletRateGate(scope, clock),
            verifyGate = verifyGate,
            sms = wallet?.deps?.sms ?: SmsDeadlines(),
            tokens = tokensCache,
            clock = clock,
            service = scope,
            texts = AppAccountTexts,
            // R3-01、共性第 43 条：二次验证面板的 token 失效世代并入 bindPhone 成功 / 换代的共享失效世代（两者都只增不减，和变了即任一变了）
            tokenGeneration = { shared.tokenGeneration + tokensCache.generation },
        )

    val deps: AccountDeps =
        AccountDeps(
            me = me.deps,
            api = api,
            env = env,
            stepUp = stepUp,
            shared = shared,
            texts = AppAccountTexts,
            walletRead = wallet?.gates?.read ?: WalletRateGate(scope, clock),
            deletionRead = WalletRateGate(scope, clock),
            clock = clock,
            service = scope,
        )

    init {
        // bindPhone 成功的共享结算（共性第 9、13 条，编排者补充 5）：清全部 action 的 token；经「我的」统一 getMe 结算刷新（共享读闸门）
        shared.ports =
            object : BindPhoneSettlePorts {
                override fun clearAllStepUpTokens() = tokensCache.clearAll()

                override fun refreshMe(
                    account: String,
                    epoch: Long,
                ) {
                    val current = me.computeIdentity()
                    if (current.account != account || current.epoch != epoch) return
                    me.deps.repo.refresh(current, MeReader { true })
                }
            }
        login?.bindPhone?.shared = shared
        // 强更页游客：受限登录成功后打开 DeleteAccount 一次（登录完成的已有出口上串接）
        login?.let { appLogin ->
            val previous = appLogin.onLoginCompleted
            appLogin.onLoginCompleted = { epoch ->
                previous(epoch)
                if (openAfterLogin) {
                    openAfterLogin = false
                    if (updateCheck() == UpdateCheck.BELOW_MIN) {
                        // 登录完成的通知在关登录页、同步会话状态之前发出：等守卫看到已登录（且仍是这次登录的会话）再打开，
                        // 否则守卫按游客又拉起一次登录页（APP-13a 第 4 阶段集成测试抓到）
                        scope.launch {
                            routing.session.first { it != SessionState.GUEST }
                            // 让登录后计划（关登录页、进强更页）在本条主线程消息里做完，再打开注销页（强更页之上）
                            kotlinx.coroutines.yield()
                            if (tokens.loginEpoch == epoch &&
                                updateCheck() == UpdateCheck.BELOW_MIN
                            ) {
                                openDeletionFrom(AccountRaw.FORCE_UPDATE)
                            }
                        }
                    }
                }
            }
        }
        scope.launch { me.identity.collect { sync() } }
        scope.launch { routing.update.collect { refreshForceUpdateLabel() } }
        // R1-12：getDeletion 读取限流到期后（仍在强更状态）重取强更页标签
        scope.launch {
            var wasBlocked = false
            deps.deletionRead.blocked.collect { blocked ->
                if (wasBlocked && !blocked && forced()) refreshForceUpdateLabel()
                wasBlocked = blocked
            }
        }
    }

    // ---------------------------------------------------------------- 身份

    private fun scopeNow(): String = client.activeSessionScope()?.takeIf { it.isNotEmpty() } ?: AccountRaw.FULL

    fun computeIdentity(): AccountIdentity = AccountIdentity(me.computeIdentity(), scopeNow())

    /** 会话、作用域、隐私、环境变化后同步（AppMe.syncIdentity 之后调用）：身份变了 → 发布（页面重建控制器）；作用域变了清 token；换代清共享截止时间。 */
    fun sync() {
        // R2-01：会话、作用域、隐私、环境切换的同步出口——打开着的绑手机页在这条消息里按完整固定身份换代、清空敏感状态
        login?.bindPhone?.syncSession()
        val next = computeIdentity()
        val previous = mutableIdentity.value
        if (next == previous) return
        if (next.scope != previous.scope) tokensCache.onScopeChanged()
        if (next.account != previous.account || next.epoch != previous.epoch) {
            shared.reset()
            verifyGate.reset()
            sources.clear()
            pendingSource = null
        }
        mutableIdentity.value = next
        refreshForceUpdateLabel()
    }

    /** 撤回同意、切换环境：清空会话级截止时间、来源交接与强更页标签（未决记录按账号保留，不在这里）。 */
    fun reset() {
        login?.bindPhone?.syncSession()
        shared.reset()
        verifyGate.reset()
        sources.clear()
        pendingSource = null
        openAfterLogin = false
        mutableIdentity.value = computeIdentity()
        refreshForceUpdateLabel()
    }

    // ---------------------------------------------------------------- 导航

    fun attach(controller: NavController) {
        navController = controller
    }

    fun detach(controller: NavController) {
        if (navController === controller) navController = null
    }

    /** 回退栈变化：出栈的 DeleteAccount 条目的来源清掉；回到强更页时重取标签。 */
    fun followBackStack(entries: List<NavBackStackEntry>) {
        val ids = entries.map { it.id }.toSet()
        sources.keys.retainAll(ids)
        if (entries.lastOrNull()?.let(::routeName) == FORCE_UPDATE) refreshForceUpdateLabel()
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

    private fun guardState(fromForceUpdate: Boolean): GuardState =
        routing.guardState().let { if (fromForceUpdate) it.copy(fromForceUpdatePage = true) else it }

    private fun forced(): Boolean = updateCheck() == UpdateCheck.BELOW_MIN

    private fun open(
        route: String,
        params: JsonObject = JsonObject(emptyMap()),
    ) {
        routing.router.open(RouteRequest(route, params, RouteSource.IN_APP), guardState(forced()))
    }

    /** 经 Router 打开 DeleteAccount（来源交接一次）。从强更页进入的带「从强更页进入」标记（守卫按 login 要求先进受限登录）。 */
    fun openDeletionFrom(source: String) {
        pendingSource = source
        val fromForce = source == AccountRaw.FORCE_UPDATE || forced()
        routing.router.open(RouteRequest(DELETE_ACCOUNT, JsonObject(emptyMap()), RouteSource.IN_APP), guardState(fromForce))
    }

    /** 强更页次要入口：一律经 Router 打开 DeleteAccount；游客由守卫先进受限登录，登录后打开一次（去掉「游客 → Login」的分支写法）。 */
    fun onForceUpdateDeletion() {
        if (DELETE_ACCOUNT !in routing.availableRoutes()) return
        if (routing.guardState().sessionState == SessionState.GUEST) openAfterLogin = true
        openDeletionFrom(AccountRaw.FORCE_UPDATE)
    }

    // ---------------------------------------------------------------- 强更页标签（G28）

    private var labelSeq = 0L

    /**
     * 已登录（含 deletion_only）且已同意时取一次 getDeletion，绑定会话世代：cooling 且 can_cancel → 撤销注销；其余、游客、读取失败 → 注销账号。
     * 与注销页共用 getDeletion 的读取闸门（[AccountDeps.deletionRead]，R1-12、共性第 32 条）：发送前检查、发包前再核对，42901 按 Retry-After 结算；
     * 限流期间用兜底标签，到期后（仍在强更状态）再取一次。
     */
    @Suppress("CyclomaticComplexMethod") // 会话分类、可用性、闸门、归属逐项判定
    fun refreshForceUpdateLabel() {
        val available = DELETE_ACCOUNT in routing.availableRoutes()
        val id = me.computeIdentity()
        val session =
            when {
                routing.privacy.value != PrivacyState.CONSENTED || id.level == MeLevel.BASIC -> AccountEntryPolicy.BASIC
                !id.member -> AccountEntryPolicy.GUEST
                scopeNow() == AccountRaw.DELETION_ONLY -> AccountEntryPolicy.DELETION_ONLY
                else -> AccountEntryPolicy.MEMBER
            }
        val mine = ++labelSeq

        fun publish(read: DeletionRead?) {
            if (mine != labelSeq) return
            mutableLabel.value = AccountEntryPolicy.entries(session, true, null, available, read, false).forceUpdateLabelKey
        }
        publish(null)
        if (!available || !id.member || session == AccountEntryPolicy.BASIC) return
        val gate = deps.deletionRead
        if (gate.active) return
        val epoch = id.epoch
        // R2-02：发起时的完整身份（「我的」身份 + 作用域）；共享副作用前复核
        val issued = computeIdentity()
        val probe = gate.probe()
        val owner =
            CallOwnership(client.inFlight.epochs(), sendAllowed = probe::allowed) { mine == labelSeq && tokens.loginEpoch == epoch }
        scope.launch {
            val result = client.send(ApiCall(GET_DELETION, ownership = owner, expectedAccount = id.account))
            if (tokens.loginEpoch != epoch || computeIdentity() != issued) return@launch
            // 读取失败与注销页同一套结算（Retry-After 写进共享的 deletionRead；10405 只认网络层 Effect：REFRESH_ONLY / 刷新失败不进强更，R2-02；标签按兜底）
            if (result !is ApiResult.Success) {
                com.couli.feature.me.MeReadFailure
                    .handle(result, probe, gate, me.deps.env) { 0L }
            }
            publish((result as? ApiResult.Success)?.let { AccountJson.read(it.data) })
        }
    }

    // ---------------------------------------------------------------- 环境

    private inner class Env : AccountEnv {
        override fun accountIdentity(): AccountIdentity = computeIdentity()

        override val accountIdentityFlow: StateFlow<AccountIdentity> get() = identity

        override fun sessionEpoch(): Long = tokens.loginEpoch

        override fun account(): String? = client.activeUserId()

        override fun stepUpPhone(account: String): String? = this@AppAccount.stepUpPhone(account)

        override fun forceUpdate() = serverForced()

        override fun sessionScope(): String = scopeNow()

        override fun open(
            route: String,
            params: JsonObject,
        ) = this@AppAccount.open(route, params)

        override fun customerService(fromRoute: String) = me.cs.open(fromRoute)

        override fun availableRoutes(): Set<String> = routing.availableRoutes()

        override fun openDeletion(source: String) = openDeletionFrom(source)

        override fun takeDeletionSource(entryId: String?): String {
            entryId?.let { id -> sources[id]?.let { return it } }
            val taken = pendingSource ?: implicitSource()
            pendingSource = null
            entryId?.let { sources[it] = taken }
            return taken
        }

        /** 没有经 [openDeletionFrom] 交接的打开：App 内（10007 cooling 的落点）为 error_10007，其余（推送）为 other。 */
        private fun implicitSource(): String {
            val last = routing.router.lastOutcome.value
            val inApp = last?.request?.route == DELETE_ACCOUNT && last.request.sourceKind == RouteSource.IN_APP
            return if (inApp) AccountRaw.ERROR_10007 else AccountRaw.OTHER
        }

        @Suppress("ReturnCount") // 没有导航、不是栈顶两个出口
        override fun finishDeletion(
            entryId: String?,
            source: String,
            toastKey: String?,
        ): Boolean {
            val controller = navController ?: return false
            // 只出栈发起的这一个注销页（APP-09 第 50 条）：它必须是栈顶，不按路由名找同名页
            if (entryId == null || controller.currentBackStackEntry?.id != entryId) return false
            controller.popBackStack()
            if (source == AccountRaw.SETTINGS && toastKey != null) deps.notices.toSettings(computeIdentity(), toastKey)
            refreshForceUpdateLabel()
            return true
        }

        override fun deletionChanged() = refreshForceUpdateLabel()

        override fun isTop(entryId: String?): Boolean = entryId == null || navController?.currentBackStackEntry?.id == entryId
    }

    companion object {
        const val DELETE_ACCOUNT = "DeleteAccount"
        const val FORCE_UPDATE = "ForceUpdate"
        const val GET_DELETION = "getDeletion"
    }
}
