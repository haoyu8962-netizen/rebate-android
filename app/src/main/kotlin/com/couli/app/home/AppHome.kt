package com.couli.app.home

import android.os.Handler
import android.os.Looper
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.HomeDestination
import com.couli.core.bridge.WebConfigReader
import com.couli.core.clientcore.BundledHome
import com.couli.core.clientcore.ClientEvent
import com.couli.core.clientcore.ClientEvents
import com.couli.core.designsystem.ProductCardTexts
import com.couli.core.designsystem.ProductImageLoader
import com.couli.core.home.CardErrorDedupe
import com.couli.core.home.HomeContractPort
import com.couli.core.home.HomeIdentity
import com.couli.core.home.HomeLevel
import com.couli.core.home.HomeLkgStore
import com.couli.core.home.HomePrefsStore
import com.couli.core.home.RouteTarget
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiClientPageApi
import com.couli.core.network.PageApi
import com.couli.core.network.SessionIdentity
import com.couli.core.network.SessionTokens
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.wallet.WalletRateGate
import com.couli.feature.home.HomeController
import com.couli.feature.home.HomeDeps
import com.couli.feature.home.HomeHost
import com.couli.feature.home.HomeSignals
import com.couli.feature.home.HomeTimers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** 主线程 Handler 计时（生产）。 */
class MainHandlerTimers : HomeTimers {
    private val main = Handler(Looper.getMainLooper())

    override fun after(
        delayMs: Long,
        block: () -> Unit,
    ): () -> Unit {
        val runnable = Runnable(block)
        main.postDelayed(runnable, delayMs)
        return { main.removeCallbacks(runnable) }
    }
}

/**
 * App 侧的首页（APP-11）：身份、控制器的建立与作废、信号、清理与诊断。
 *
 * - **身份**（[identity]）：隐私结论 + 会话状态 + 账号 + 登录世代 + 隐私 / 环境世代 + 环境名，现算只用于比较。会话、隐私、环境变化时 [sync] 在同一个同步出口里
 *   作废旧控制器（在途请求、已排程的弹窗、数据源分页一并作废，显示中的弹窗立即关闭且不计数），并按新身份**重建**、重新走三级兜底（新作用域的 LKG → 包内默认）；
 *   退出登录、换账号、会话失效时清掉旧账号作用域的 LKG（G16 ②）。
 * - **可见性**（共性第 18、21、22 条）：选中首页 Tab（外壳在提交切 Tab 的同步出口里先写目标值）、首页之上没有别的页面（回退栈的同步出口）、App 在前台；
 *   正式首页与每个 HomePreview 实例各自订阅，可以共存。
 * - **阻挡项**（G14 ④）：登记的来源现算；任一来源变化都让 [blockerChanges] +1，正式首页与每个预览实例据此复核弹窗。
 * - **42901**：首页三个接口共享一个截止时间；身份变化时重置（旧会话的 42901 不限流新会话，APP-12 第 34 条）。
 * 只在主线程使用（[identity] 也可在网络线程读）。
 */
@Suppress("TooManyFunctions", "LongParameterList")
class AppHome(
    private val client: ApiClient,
    private val tokens: SessionTokens,
    private val routing: AppRouting,
    private val privacy: AppPrivacy,
    /** 首页契约接口（getPage、listPageSectionItems）：生产经 ApiClient；debug / staging 只在假服务闸门打开时换成本机假服务。 */
    val port: HomeContractPort?,
    /** HomePreview 实例的草稿数据源（D10-607 D7：预览不动，仍是本机假服务）；缺省同 [port]。 */
    val previewPort: HomeContractPort? = port,
    private val lkg: HomeLkgStore?,
    private val prefs: HomePrefsStore?,
    private val images: ProductImageLoader?,
    private val envKey: () -> String,
    private val configData: () -> JsonObject?,
    private val track: (ClientEvent) -> Unit,
    private val serverForced: () -> Unit,
    private val cardTexts: ProductCardTexts,
    private val purchaseStatus: (String) -> String? = { null },
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timers: HomeTimers = MainHandlerTimers(),
    private val bundled: () -> String? = BundledHome::pageJson,
    private val api: PageApi = ApiClientPageApi(client),
    /** 本机文件读写的线程（测试可换成主线程）。 */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** listArticles、previewPage 经它（当前环境的 ApiClient）。 */
    val pageApi: PageApi get() = api

    /** 版本门槛比较用的本端版本（G09）。 */
    val appVersionName: String get() = appVersion
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 首页三个接口共享的 42901 截止时间。 */
    val gate = WalletRateGate(scope, clock)

    /** sdui_card_error 进程内去重（G20）。 */
    val dedupe = CardErrorDedupe()

    // ---------------------------------------------------------------- 信号

    private var selectedHome = true
    private var stackEmpty = true
    private val mutableForeground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = mutableForeground.asStateFlow()
    private val mutableVisible = MutableStateFlow(true)
    private val mutableReader = MutableStateFlow(false)
    val screenReader: StateFlow<Boolean> = mutableReader.asStateFlow()
    private val mutableBlockerChanges = MutableStateFlow(0L)
    val blockerChanges: StateFlow<Long> = mutableBlockerChanges.asStateFlow()
    private val blockerSources = mutableListOf<Pair<String, () -> Boolean>>()

    /** 回退栈栈顶条目 id（起点为 null）与回退栈变化的同步订阅者（HomePreview 实例的出栈出口）。 */
    private val mutableTop = MutableStateFlow<String?>(null)
    val backStackTop: StateFlow<String?> = mutableTop.asStateFlow()
    private val backStackListeners = mutableListOf<(Set<String>) -> Unit>()

    /** 置灰开关（G27）：配置变化时即时应用或移除。 */
    private val mutableGrayscale = MutableStateFlow(false)
    val grayscale: StateFlow<Boolean> = mutableGrayscale.asStateFlow()

    /** 正式首页的信号（共享给控制器，按真实状态同步）。 */
    val signals: HomeSignals =
        object : HomeSignals {
            override val visible: StateFlow<Boolean> get() = mutableVisible.asStateFlow()
            override val screenReader: StateFlow<Boolean> get() = mutableReader.asStateFlow()

            override fun blockers(): Set<String> = this@AppHome.blockers()

            override val blockerChanges: StateFlow<Long> get() = mutableBlockerChanges.asStateFlow()
        }

    /** 登记一个阻挡项来源：[active] 现算；[changes] 变化时通知复核（共性第 21 条）。 */
    fun addBlocker(
        name: String,
        active: () -> Boolean,
        changes: Flow<*>? = null,
    ) {
        blockerSources += name to active
        changes?.let { flow -> scope.launch { flow.drop(1).collect { onBlockersChanged() } } }
    }

    /** 不可观察的来源变化时（或测试）手动通知。 */
    fun onBlockersChanged() {
        mutableBlockerChanges.value += 1
    }

    fun blockers(): Set<String> =
        blockerSources.filter { (_, active) -> runCatching { active() }.getOrDefault(false) }.map { it.first }.toSet()

    /** 外壳提交切 Tab 的同步出口（改选中项之前，共性第 22、28 条）：写入目标值，首页暂停轮播、弹窗等待；不重发拉取。 */
    fun onTab(route: String) {
        selectedHome = route == HOME
        updateVisible()
    }

    /** 回退栈变了（主线程、同一条消息）：首页之上有没有别的页面；栈顶条目（预览实例的可见性）；通知订阅者（预览出栈的同步出口）。 */
    fun followBackStack(entries: List<NavBackStackEntry>) {
        val pages = entries.filter { it.destination !is NavGraph }
        val top = pages.lastOrNull()
        stackEmpty = top == null || top.destination.hasRoute(HomeDestination::class)
        mutableTop.value = top?.takeIf { !it.destination.hasRoute(HomeDestination::class) }?.id
        val ids = pages.map { it.id }.toSet()
        backStackListeners.toList().forEach { it(ids) }
        updateVisible()
    }

    private val identityListeners = mutableListOf<() -> Unit>()

    /** 会话、隐私、环境变化的同步通知（HomePreview 实例据此比较固定身份，变了立即作废，H19）。 */
    fun addIdentityListener(listener: () -> Unit): () -> Unit {
        identityListeners += listener
        return { identityListeners -= listener }
    }

    fun addBackStackListener(listener: (Set<String>) -> Unit): () -> Unit {
        backStackListeners += listener
        return { backStackListeners -= listener }
    }

    fun onForeground(value: Boolean) {
        mutableForeground.value = value
        updateVisible()
    }

    fun setScreenReader(on: Boolean) {
        mutableReader.value = on
    }

    private fun updateVisible() {
        mutableVisible.value = selectedHome && stackEmpty && mutableForeground.value
    }

    // ---------------------------------------------------------------- 身份

    @Volatile private var accountEpoch = Long.MIN_VALUE

    @Volatile private var accountCache: String? = null

    private fun account(): String? {
        val epoch = tokens.loginEpoch
        if (epoch != accountEpoch) {
            // 这一代还没有已确认的账号：取一份快照（FU-AND-0717 第 3 轮：与 [sync] 同一来源）；读不出不缓存（F-AND-28）：下次再读；读到之前按账号未知
            // （LKG 分区 member-unknown，只用于比较——控制器的写入因身份不一致被拦下，不删任何作用域）
            val snapshot = client.sessionIdentity()
            val account = snapshot.account?.takeIf { snapshot.known && snapshot.loginEpoch == epoch } ?: return null
            accountCache = account
            accountEpoch = epoch
        }
        return accountCache
    }

    /** 一份身份快照（FU-AND-0717 第 3 轮）：只在隐私已同意、凭据可读时读凭据，否则 null（不读）。 */
    private fun sessionSnapshot(): SessionIdentity? {
        if (privacy.conclusion != PrivacyState.CONSENTED || privacy.credentialAccess.cleanupPending) return null
        return client.sessionIdentity()
    }

    /**
     * 按一份快照 [snapshot] 算首页身份（FU-AND-0717 第 3 轮，第 2 轮评审 R2-06）：会员级别时账号与登录世代都取自这一份（并记进本代账号缓存，之后控制器的身份
     * 比较不再另读）；快照未知时返回 null——调用方不决策（不换控制器、不清任何作用域的 LKG）。非会员级别（基本模式、游客、凭据清理中）不读账号。
     */
    private fun identityFrom(snapshot: SessionIdentity?): HomeIdentity? {
        val level = level()
        val epochs = client.inFlight.epochs()
        val known = snapshot?.takeIf { it.known }
        return when {
            level != HomeLevel.MEMBER -> {
                HomeIdentity(level, null, tokens.loginEpoch, epochs, envKey())
            }

            known == null -> {
                null
            }

            else -> {
                known.account?.let {
                    accountCache = it
                    accountEpoch = known.loginEpoch
                }
                withKnownPartition(HomeIdentity(HomeLevel.MEMBER, known.account, known.loginEpoch, epochs, envKey(), known.partition))
            }
        }
    }

    private fun level(): String {
        val readable = !privacy.credentialAccess.cleanupPending
        return when {
            privacy.conclusion != PrivacyState.CONSENTED -> HomeLevel.BASIC
            !readable || routing.session.value == SessionState.GUEST -> HomeLevel.GUEST
            else -> HomeLevel.MEMBER
        }
    }

    /** 最近一次 [sync] 用的那份快照（[ensure] 建第一个控制器时沿用，不另读）。 */
    private var syncSnapshot: SessionIdentity? = null

    /** 此刻的身份（现算；只用于比较与新控制器的构造）。 */
    fun identity(): HomeIdentity {
        val level = level()
        val account = if (level == HomeLevel.MEMBER) account() else null
        return withKnownPartition(HomeIdentity(level, account, tokens.loginEpoch, client.inFlight.epochs(), envKey()))
    }

    /** 分区暂失只延后：仅基础身份仍一致时沿用已确认分区；首次确认、明确换代都进入原有作废重建出口。 */
    private fun withKnownPartition(identity: HomeIdentity): HomeIdentity =
        identity.copy(partition = identity.partition ?: target?.takeIf { it.copy(partition = null) == identity }?.partition)

    // ---------------------------------------------------------------- 控制器

    private val mutableController = MutableStateFlow<HomeController?>(null)
    val controller: StateFlow<HomeController?> = mutableController.asStateFlow()

    /** 首页根页已经要过内容（之后身份变化时立即按新身份重建，不等下次组合）。 */
    private var wanted = false

    /** 当前控制器（或等待建立的控制器）对应的身份：身份变化以它为准比较（控制器还在等旧作用域清理时也能识别下一次变化）。 */
    private var target: HomeIdentity? = null

    /**
     * 旧作用域的 LKG 清理（R2-02，共性第 33 条）：作用域 → 在 IO 上执行的 clearScope。失效在同步出口里**先登记**（这里有一项），清理完成之前不给这个作用域的
     * 新控制器发写入票据：新控制器等它完成、复核此刻的身份之后才建立（不给已有控制器重新绑定票据）。同一作用域的多次清理串行。
     */
    private val clearing = mutableMapOf<String, Job>()

    private val host: HomeHost =
        object : HomeHost {
            override fun identity(): HomeIdentity = this@AppHome.identity()

            override fun open(
                target: RouteTarget,
                spm: String,
            ) {
                routing.router.open(RouteRequest(target.route, target.routeParams, RouteSource.IN_APP))
            }

            override fun forceUpdate() = this@AppHome.forceUpdate()

            override fun reportCardError(
                type: String,
                version: Int?,
                pageVersion: Int,
                reason: String,
            ) {
                track(ClientEvents.sduiCardError(PAGE, type, pageVersion.toLong(), reason, version?.toLong()))
            }
        }

    /**
     * 首页根页组合时调用：没有控制器就按此刻的身份建一个并冷启动。这个身份的 LKG 作用域还在清理（R2-02）时返回 null：清理完成、复核身份后再建，
     * 期间首页区留空（清理只是删一个目录）。
     */
    fun ensure(): HomeController? {
        wanted = true
        sync()
        mutableController.value?.let { return it }
        // 第一个控制器按 [sync] 那一份快照的身份（未知时按会员、账号未知：只用于比较，不删任何作用域）
        if (target == null) target = identityFrom(syncSnapshot) ?: identity()
        return place()
    }

    /** 按 [target] 建控制器；它的作用域还有未完成的清理时先等清理完成，再复核身份（变了按变化处理）。 */
    @Suppress("ReturnCount") // 没有目标、无需等待、等待中依次返回
    private fun place(): HomeController? {
        val next = target ?: return null
        val pending = clearing[next.scope]?.takeIf { it.isActive } ?: return build(next)
        scope.launch {
            pending.join()
            if (!wanted || mutableController.value != null || target != next) return@launch
            // 一份快照复核（FU-AND-0717 第 3 轮）：仍是这个身份、或身份暂时未知（不决策，按已确认的目标建）都照建；确认变了按变化处理
            val now = identityFrom(sessionSnapshot())
            if (now == null || now == next) place() else sync()
        }
        return null
    }

    private fun build(identity: HomeIdentity): HomeController {
        target = identity
        val controller = HomeController(identity, deps(host, signals, preview = false))
        mutableController.value = controller
        controller.start()
        return controller
    }

    /** 登记并在 IO 上执行旧作用域的清理（同一作用域串行）；完成后才允许这个作用域的新控制器取票。 */
    private fun clearScopeLater(stale: String) {
        val before = clearing[stale]
        val job =
            scope.launch {
                before?.join()
                withContext(io) { runCatching { lkg?.clearScope(stale) } }
            }
        clearing[stale] = job
        job.invokeOnCompletion { if (clearing[stale] === job) clearing -= stale }
    }

    /** 给控制器的依赖（正式首页与 HomePreview 共用；预览不给本机存储）。 */
    fun deps(
        host: HomeHost,
        signals: HomeSignals,
        preview: Boolean,
    ): HomeDeps =
        HomeDeps(
            host = host,
            signals = signals,
            port = if (preview) previewPort else port,
            api = api,
            lkg = lkg.takeIf { !preview },
            prefs = prefs.takeIf { !preview },
            gate = gate,
            images = images,
            timers = timers,
            clock = clock,
            bundled = bundled,
            platform = PLATFORM,
            appVersion = appVersion,
            cardTexts = cardTexts,
            purchaseStatus = purchaseStatus,
            dedupe = dedupe,
            io = io,
        )

    /**
     * 会话、隐私、环境变化后同步（同一同步出口）：身份变了 → 作废旧控制器、重置共享禁用期、清旧账号作用域的 LKG（退出登录、换账号、会话失效），
     * 已显示过首页时立即按新身份重建。
     */
    @Suppress("ReturnCount") // 没有首页、身份未变、仍在等同一身份依次提前返回
    fun sync() {
        // 一份身份快照（FU-AND-0717 第 3 轮，R2-06）：世代对账、控制器身份比较与作用域删除都只用它，不再二次读账号
        val snapshot = sessionSnapshot()
        syncSnapshot = snapshot
        // 第 1 轮修复 R1-04：先按这份已确认的身份快照对账会员 LKG 的登录世代分区（同账号换代删旧世代；未知、世代缺失不动），在任何控制器读取之前
        reconcileLkgEpoch(snapshot)
        identityListeners.toList().forEach { it() }
        val current = mutableController.value
        val previous = current?.identity ?: target ?: return
        // 会员级别而身份暂时未知：不决策——原控制器、目标与各作用域的 LKG 原样保留，等下一次身份已知的同步
        val next = identityFrom(snapshot) ?: return
        if (current != null && current.alive && current.identity == next) return
        // 还在等旧作用域清理、身份没再变：照旧等
        if (current == null && previous == next) return
        current?.invalidate()
        gate.reset()
        mutableController.value = null
        if (previous.level == HomeLevel.MEMBER && (next.level != HomeLevel.MEMBER || next.account != previous.account)) {
            // 先登记旧作用域的失效（R2-02）：清理完成之前，这个作用域不会有新控制器取票
            clearScopeLater(previous.scope)
        }
        target = next
        if (wanted) place()
    }

    /** 会员 LKG 世代对账没完成（删不掉），下一次同步重试（第 1 轮修复 R1-04）。 */
    @Volatile var lkgReconcilePending: Boolean = false
        private set

    /**
     * 会员 LKG 的登录世代对账（R1-04）：一份身份快照；身份未知、没有会话、持久化世代缺失都不动；已知时删掉该账号会员作用域里不属于当前世代的子目录。
     * 隐私不是已同意时不读凭据、不动。
     */
    @Suppress("ReturnCount") // 没有存储、不读凭据、没有会话、分区未知逐项
    private fun reconcileLkgEpoch(snapshot: SessionIdentity?) {
        val store = lkg ?: return
        val id = snapshot ?: return
        val account = id.account ?: return
        if (!id.partitionKnown) return
        val scope = HomeIdentity(HomeLevel.MEMBER, account, id.loginEpoch, client.inFlight.epochs(), envKey()).scope
        lkgReconcilePending = !runCatching { store.reconcileEpoch(scope, id.partition) }.getOrDefault(false)
    }

    /** 切换环境开始（清理器第一步之前）：作废控制器（之后的同步按新环境重建）。 */
    fun onEnvironmentSwitching() = invalidateForCleanup()

    /**
     * 撤回同意（撤回清理名单里排在首页存储清理之前，R1-01）：先作废正式首页控制器与各预览实例，在途请求、已排程的写入与弹窗一并作废；
     * 隐私结论变化后的 [sync] 再按基本模式重建。存储层的写入世代票据是第二道闸。
     */
    fun onConsentWithdrawing() = invalidateForCleanup()

    private fun invalidateForCleanup() {
        mutableController.value?.invalidate()
        gate.reset()
        identityListeners.toList().forEach { it() }
    }

    /** 10405 的强更分流（正式首页与 HomePreview 实例共用，R1-04）。 */
    fun forceUpdate() = serverForced()

    /** 配置变化：置灰开关即时应用或移除（G27）。 */
    fun onConfigChanged() {
        mutableGrayscale.value = configData()?.let { WebConfigReader.flag(it, GRAYSCALE, false) } ?: false
    }

    /** 诊断信息「首页版本」（G31）：`<version> (<network|lkg|default>)`；没有显示中的页面为 null（整行省略）。 */
    fun currentVersion(): String? =
        mutableController.value
            ?.takeIf { it.alive }
            ?.currentVersion()
            ?.let { (version, level) -> "$version ($level)" }

    /** 接 Router 的会话状态与隐私结论（AppModule 与集成测试共用）。 */
    fun connect() {
        privacy.onConclusionChanged { sync() }
        scope.launch { routing.session.drop(1).collect { sync() } }
        onConfigChanged()
    }

    companion object {
        const val HOME = "Home"
        const val PAGE = "Home"
        const val PLATFORM = "android"
        const val GRAYSCALE = "ui.grayscale"
        const val HOME_LKG_CLEANER = "home_lkg"
        const val HOME_CONTROLLER_CLEANER = "home_controller"
        const val HOME_PREFS_CLEANER = "home_prefs"
    }
}
