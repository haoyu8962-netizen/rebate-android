package com.couli.app.search.submit

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.core.app.ApplicationProvider
import com.couli.app.clipboard.AppClipboard
import com.couli.app.clipboard.ClipboardPreferences
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.search.AppSearch
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.SystemClipboard
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ClientIdentity
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PageApi
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UnregisteredDevice
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.router.GuardDecision
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteNavigator
import com.couli.core.router.RouteRequest
import com.couli.core.router.UpdateCheck
import com.couli.feature.login.PendingInviteCode
import com.couli.feature.search.SearchSnapshot
import com.couli.feature.search.SearchViewModel
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 真实 App 配置、隐私结论、ViewModel、Controller、Router；仅系统 I/O 和网络用替身。
 *
 * v5（修复 r2，评审 S2-8）：[query] 以 `Search(q=…)` 路由参数打开本页（S2-5）；[clock] 驱动剪贴板与 42901 禁用期（S1-3，缺省固定为 [NOW]，
 * 与 v1–v4.1 相同）；[loginEpoch] 是剪贴板的登录世代来源（生产为 tokens.loginEpoch），配合 [switchAccount] / [switchEnvironment] /
 * [withdrawPrivacy] 按生产的通知顺序推进世代（S1-1）；[stack] 按 AppShell 的回退栈出口同步页面覆盖、返回与出栈（S1-2）。
 *
 * OWNER v1（FU-AND-SEARCH-LINK-OWNER，性质测试）：只加入口，不改既有入口的行为——
 * - [pushSearchStatus]：经真实 ConfigStore 收到一份新的 search_status（current 层），通知照生产走 AppSearch 的 main.post（测试要 idle 主 Looper）；
 *   没有推送时拉取器仍按原样抛错（= 拉取失败，与 v1–v6 相同）；
 * - [clearViewModel]：按生产的 ViewModelStore.clear 清理本页 ViewModel（viewModelScope 取消 + onCleared）。[model] 改由 ViewModelProvider 创建，
 *   对象与构造参数不变。
 *
 * OWNER v3（评审 r2 S2-1，保存恢复）：只加入口，缺省值下行为不变——
 * - 构造参数 savedState：本页 ViewModel 的 SavedStateHandle（缺省空，与 v1–v2 相同）；[saveState] 按生产经 Bundle / Parcel 往返取一份保存状态，
 *   用它构造新夹具 = 进程回收后重开（全部内存状态重来）；[rebuildPage] = 同进程重建（ViewModel 清理后按保存状态重建，App、剪贴板不变）；
 * - 构造参数 initialUpdate 与 [setUpdateCheck]：驱动恢复门（AppSearch.awaitUpdateCheck 等 routing.update 离开 CHECKING，最长 5 秒）。
 */
@Suppress("TooManyFunctions", "LongParameterList") // v5：世代与回退栈的推进逐项一个入口；v3：保存状态与恢复门两个构造入口
internal class SearchSubmitFixture(
    scope: CoroutineScope,
    basic: Boolean = false,
    searchEnabled: Boolean = true,
    private val query: String? = null,
    clock: () -> Long = { NOW },
    savedState: SavedStateHandle = SavedStateHandle(),
    initialUpdate: UpdateCheck = UpdateCheck.OK,
) : AutoCloseable {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferenceName = "search-submit-${UUID.randomUUID()}"
    private val disk = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    val privacy =
        AppPrivacy(
            // v5：撤回同意的本地凭据清理在本夹具里没有凭据可清（网络层是替身），接成空操作，[withdrawPrivacy] 才能走真实撤回
            PrivacyGate(MemoryPrivacy(basic), clock = { NOW }, bundledPolicyVersion = "1").apply { withdrawalCleanup = {} },
            environmentSettled = { true },
            clock = { NOW },
        ).also { it.coldStart() }
    val api = SubmitApi()

    /** 剪贴板的登录世代来源（生产 AppModule：sessionEpoch = { tokens.loginEpoch }）；[switchAccount] 推进。 */
    var loginEpoch = 0L
        private set
    val navigations = mutableListOf<Pair<String, JsonObject>>()
    private val navigator =
        object : RouteNavigator {
            override fun open(
                route: String,
                params: JsonObject,
                readonly: Boolean,
            ) {
                navigations += route to params
            }

            override fun showUpgradePrompt(request: RouteRequest) = error("提交不应打开升级提示")

            override fun requestConsent(request: RouteRequest) = error("提交不应要求补同意")

            override fun blocked(
                request: RouteRequest,
                decision: GuardDecision,
            ) = error("测试路由被阻断：$decision")
        }
    private val routing =
        AppRouting(
            context,
            EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() },
            clock = { NOW },
            initialPrivacy = privacy.conclusion,
            initialUpdate = initialUpdate,
        ).also { it.attach(navigator) }
    private val config =
        json(
            """
            {
              "clipboard":{"consent_version":"1"},
              "features":{"flags":{},"search_status":{"taobao":"${if (searchEnabled) "on" else "off"}","jd":"off","pdd":"off"}}
            }
            """.trimIndent(),
        )
    private var nextConfig: ConfigReply? = null
    private var configTag = 0
    private val store =
        ConfigStore(
            readFile = { null },
            writeFile = {},
            // OWNER v1：有 [pushSearchStatus] 排着的配置就同步交回；没有仍抛错（ConfigStore 按拉取失败结算，与 v1–v6 相同）
            fetcher = { _, done -> done(nextConfig?.also { nextConfig = null } ?: error("这些场景不应刷新配置")) },
            clock = { NOW },
            bundled = config.toString(),
        )
    private val system =
        object : SystemClipboard {
            override fun read(): ClipRead = error("输入框提交不得读取系统剪贴板")

            override fun write(
                text: String,
                label: String,
            ): Boolean = error("输入框提交不得写系统剪贴板")
        }
    val preferences = ClipboardPreferences(disk)
    val clipboard =
        AppClipboard(
            system = system,
            api = api,
            routing = routing,
            privacy = { privacy.conclusion },
            promptShowing = { privacy.promptShowing },
            configData = { config },
            prefs = preferences,
            pendingInvite = PendingInviteCode(),
            sessionEpoch = { loginEpoch },
            clock = clock,
            workScope = scope,
        )
    private val app =
        AppSearch(
            client = client(),
            store = store,
            routing = routing,
            privacy = { privacy.conclusion },
            api = api,
            clipboard = clipboard,
        )
    var pageConfig = newPageConfig()
        private set
    private var viewModels = ViewModelStore()
    private var savedHandle = savedState
    var model: SearchViewModel = newModel()
        private set

    private fun newPageConfig() =
        app.searchConfig(
            RouteDestination("Search", query?.let { JsonObject(mapOf("q" to JsonPrimitive(it))).toString() } ?: "{}", readonly = false),
            ENTRY,
            {},
        )

    private fun newModel(): SearchViewModel =
        ViewModelProvider.create(
            viewModels,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: KClass<T>,
                    extras: CreationExtras,
                ): T = SearchViewModel(pageConfig, savedHandle) as T
            },
        )[SearchViewModel::class]

    /** OWNER v3：此刻的保存状态，按生产经 Bundle / Parcel 往返（不可序列化的值在这里就暴露）。 */
    @SuppressLint("RestrictedApi")
    fun saveState(): SavedStateHandle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(savedHandle.savedStateProvider().saveState())
            val bytes = parcel.marshall()
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            SavedStateHandle.createHandle(requireNotNull(parcel.readBundle(javaClass.classLoader)), null)
        } finally {
            parcel.recycle()
        }
    }

    /** OWNER v3：同进程重建本页：旧 ViewModel 清理后，按 [restored] 建新的 ViewModel / Controller；App、剪贴板、网络替身不变。 */
    fun rebuildPage(restored: SavedStateHandle) {
        clearViewModel()
        viewModels = ViewModelStore()
        savedHandle = restored
        pageConfig = newPageConfig()
        model = newModel()
    }

    /** OWNER v3：写入版本检查结论（恢复门：离开 CHECKING 即放行）。 */
    fun setUpdateCheck(check: UpdateCheck) {
        routing.update.value = check
    }

    /** OWNER v1：按生产清理本页 ViewModel（ViewModelStore.clear → viewModelScope 取消、onCleared）。 */
    fun clearViewModel() = viewModels.clear()

    /**
     * OWNER v1：配置中心下发一份新的 search_status（current 层，taobao / jd，pdd 关），经真实 ConfigStore 结算并通知订阅方。AppSearch 把通知
     * main.post 到主线程：调用方随后 idle 主 Looper，搜索页才收到 configChanged。
     */
    fun pushSearchStatus(
        taobao: String,
        jd: String,
    ) {
        configTag += 1
        val body = """{"features":{"search_status":{"taobao":"$taobao","jd":"$jd","pdd":"off"}}}"""
        nextConfig = ConfigReply(ConfigReply.OK, body, "owner-v1-$configTag")
        store.onForeground()
    }

    fun submit(text: String) {
        model.onInput(text)
        model.onSubmit()
    }

    /**
     * 回退栈变了（与 AppShell 的 onBackStack 同一顺序、同一同步出口）：先 AppSearch.followBackStack，再剪贴板的当前前台界面。
     * [top] 栈顶路由名（首页 Tab 外壳为 null），[entryId] 栈顶条目，[ids] 栈里全部条目。
     */
    fun stack(
        top: String?,
        entryId: String,
        ids: Set<String>,
    ) {
        app.followBackStackIds(ids)
        clipboard.onBackStack(top, entryId, ids)
    }

    /** 本页（搜索结果页）在栈顶。 */
    fun onSearchPage() = stack("Search", ENTRY, setOf(HOME, ENTRY))

    /** 在搜索页上推入详情（搜索页仍在回退栈里，只是被覆盖）。 */
    fun coverWithDetail() = stack("ProductDetail", DETAIL_ENTRY, setOf(HOME, ENTRY, DETAIL_ENTRY))

    /** 搜索页出栈，回到首页。 */
    fun leaveSearch() = stack(null, HOME, setOf(HOME))

    /** 切换账号（同一身份状态下 A 换 B）：登录世代先变，再按生产的会话通知（routing.session / onSessionStored → onSessionChanged）。 */
    fun switchAccount() {
        bumpLoginEpoch()
        notifySessionChanged()
    }

    /**
     * v6（评审 r2 S2-4）：只推进登录世代、不发会话通知。生产的 onSessionStored 经 main.post 投递，世代已变而通知未到的窗口真实存在；
     * 之后可用 [notifySessionChanged] 补发。
     */
    fun bumpLoginEpoch() {
        loginEpoch += 1
    }

    /** v6：补发会话通知（[bumpLoginEpoch] 之后，按生产顺序晚到）。 */
    fun notifySessionChanged() = clipboard.onSessionChanged()

    /** 切换环境：InFlightCalls 先推进环境世代，再跑环境切换清理（剪贴板登记的 onEnvironmentSwitched）。 */
    fun switchEnvironment() {
        bumpEnvironmentEpoch()
        notifyEnvironmentSwitched()
    }

    /** v6（评审 r2 S2-4）：只推进环境世代（InFlightCalls），环境切换清理尚未跑。 */
    fun bumpEnvironmentEpoch() = api.bumpEnvironment()

    /** v6：补跑环境切换清理（剪贴板登记的 onEnvironmentSwitched）。 */
    fun notifyEnvironmentSwitched() = clipboard.onEnvironmentSwitched()

    /** v6：本页执行器此刻的状态（结果是否被消费看 shownSeq / phase）。 */
    val page: SearchSnapshot get() = model.state.value

    /** 撤回隐私同意：InFlightCalls 先推进隐私世代，再改隐私结论，再按 connect() 的口径通知剪贴板（已同意 → 基本模式 = 真实撤回）。 */
    fun withdrawPrivacy() {
        api.bumpPrivacy()
        privacy.withdraw()
        clipboard.onPrivacyChanged(privacy.conclusion, withdrawn = true)
    }

    override fun close() {
        app.followBackStackIds(setOf("home"))
        model.viewModelScope.cancel()
        routing.detach(navigator)
        context.deleteSharedPreferences(preferenceName)
    }

    private fun client(): ApiClient =
        ApiClient(
            identity = ClientIdentity("couli", "android", "1.0.0", "1", "official"),
            baseUrl = { error("测试不允许真实网络；应使用注入的 PageApi") },
            context = {
                NetworkContext(
                    if (privacy.conclusion == PrivacyState.CONSENTED) PrivacyRaw.FULL else PrivacyRaw.BASIC,
                    environmentSettled = true,
                    credentialsReadable = true,
                    update = UpdateRaw.SUPPORTED,
                )
            },
            device = UnregisteredDevice,
            tokens = MemorySessionTokens(),
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { NOW },
            sleeper = {},
        )

    private class MemoryPrivacy(
        basic: Boolean,
    ) : PrivacyStore {
        override var decisionRaw: String = if (basic) "basic_mode" else "consented"
        override var consentedVersion: String? = if (basic) null else "1"
        override var consentedAtMillis: Long? = if (basic) null else 0L
        override var basicMode: Boolean = basic
    }

    companion object {
        const val ENTRY = "search-submit"
        const val HOME = "home"
        const val DETAIL_ENTRY = "detail-over-search"
        const val NOW = 1_800_000_000_000L

        fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
    }
}

/**
 * 在请求入口计数，识别响应由测试放行；替身没有识别或回落逻辑，不替产品阻止多发。
 *
 * v5（评审 S2-8）：每个 parseInput 请求按发出顺序编号（0 起），各有自己的响应（[parseReply] 即第 0 个，v1–v4.1 的用法不变），可乱序放行，
 * 也可给单个请求加放行后的延迟（[delayParse]）；[epochs] 是可推进的隐私 / 环境世代（生产为 InFlightCalls）；[cancelled] 记录产品调用
 * cancelOwned 的归属（按调用顺序）。
 *
 * v6（评审 r2 S1-1、S1-2、S2-3）：把「交给网络层」与「真正写出」分开，按 ApiClient 的写出前守卫建模：
 * - [calls] 仍是交给 [send] 的全部请求（v1–v5 的断言不变）；[written] 只记写出前守卫放行、真正写出的请求；
 * - 写出前守卫 = 归属绑定的世代仍等于 [epochs]、[CallOwnership.stillOwned]、[CallOwnership.sendAllowed] 都放行，且这份归属没有被
 *   cancelOwned；任一不满足即本地拒绝（[ApiResult.LocalReject] call_cancelled），不写出、不调 onWritten；
 * - 放行时先调 [CallOwnership.onWritten]（true），再等响应——与 ApiClient 的 onCommitted 同一时点；
 * - [holdWrite]：第 n 个某操作在写出前停住（生产里是切到 IO、尚未绑定会话的那一段），放行后才过守卫；[rejectWrite]：网络层本地拒绝；
 *   [holdReply]：searchProducts 写出后、响应返回前停住；[pauseGuardOnIo]：在真实 IO 线程上的写出前守卫处停住，由测试线程交错。
 * 编号按操作分别计（第 n 个 parseInput / 第 n 个 searchProducts，0 起）。
 */
@Suppress("TooManyFunctions") // v6：写出前停住、拒绝、响应停住、IO 线程交错逐项一个入口
internal class SubmitApi : PageApi {
    val calls = mutableListOf<ApiCall>()

    /** 真正写出（过了写出前守卫）的请求，按写出顺序。 */
    val written = mutableListOf<ApiCall>()
    private val parseReplies = mutableMapOf<Int, CompletableDeferred<ApiResult>>()
    private val parseDelays = mutableMapOf<Int, Long>()
    private val counts = mutableMapOf<String, Int>()
    private val writeHolds = mutableMapOf<Pair<String, Int>, CompletableDeferred<Unit>>()
    private val writeRejects = mutableSetOf<Pair<String, Int>>()
    private val replyHolds = mutableMapOf<Pair<String, Int>, CompletableDeferred<Unit>>()
    private val ioPauses = mutableMapOf<Pair<String, Int>, IoGuardPause>()
    private val keywordHolds = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val morePages = mutableSetOf<Int>()
    private val ioNotices = mutableMapOf<Pair<String, Int>, IoWriteNotice>()

    @Volatile private var currentEpochs = CallEpochs(0, 0)
    val cancelled: MutableList<CallOwnership> = Collections.synchronizedList(mutableListOf())

    /** 第 0 个 parse 的响应（v1–v4.1 沿用）。 */
    val parseReply: CompletableDeferred<ApiResult> get() = reply(0)

    /** 第 [index] 个 parse 的响应（可在请求发出前或后放行）。 */
    fun reply(index: Int): CompletableDeferred<ApiResult> = parseReplies.getOrPut(index) { CompletableDeferred() }

    /** 第 [index] 个 parse 放行后再等 [millis]（虚拟时间）才返回。 */
    fun delayParse(
        index: Int,
        millis: Long,
    ) {
        parseDelays[index] = millis
    }

    /** 第 [index] 个 [operation] 交到网络层后、写出前守卫之前停住；complete 后才过守卫。 */
    fun holdWrite(
        operation: String,
        index: Int = 0,
    ): CompletableDeferred<Unit> = writeHolds.getOrPut(operation to index) { CompletableDeferred() }

    /** 第 [index] 个 [operation] 被网络层本地拒绝（不写出、不调 onWritten）。 */
    fun rejectWrite(
        operation: String,
        index: Int = 0,
    ) {
        writeRejects += operation to index
    }

    /** 第 [index] 个 searchProducts 写出后、响应返回前停住；complete 后才返回空结果页。 */
    fun holdReply(index: Int = 0): CompletableDeferred<Unit> = replyHolds.getOrPut(SEARCH to index) { CompletableDeferred() }

    /** 第 [index] 个 [operation] 的写出前守卫改在真实 IO 线程上求值，并在求值前停住（见 [IoGuardPause]）。 */
    fun pauseGuardOnIo(
        operation: String,
        index: Int = 0,
    ): IoGuardPause = ioPauses.getOrPut(operation to index) { IoGuardPause() }

    /**
     * OWNER v2（评审 r1 S0-2）：第 [index] 个 searchProducts 的成功响应还有下一页（next_cursor + has_more=true），本页可以翻页（loadMore，
     * 即 AutoLoadMore 的入口）。不调时响应与 v1–v6 相同（到底）。
     */
    fun hasMore(index: Int = 0) {
        morePages += index
    }

    /** OWNER v1：q 为 [keyword] 的 searchProducts 写出后、响应返回前停住（不论第几个）；complete 后才返回空结果页。 */
    fun holdKeyword(keyword: String): CompletableDeferred<Unit> = keywordHolds.getOrPut(keyword) { CompletableDeferred() }

    /**
     * OWNER v1（Z14、历史离页）：第 [index] 个 [operation] 写出时，写出通知（onWritten(true)）改在另一条真实线程上调（生产里 onCommitted 在 OkHttp
     * 线程上调），发送协程不等它；见 [IoWriteNotice]。
     */
    fun notifyWrittenOnIo(
        operation: String,
        index: Int = 0,
    ): IoWriteNotice = ioNotices.getOrPut(operation to index) { IoWriteNotice() }

    /** 已发出的 parse 请求（按发出顺序）。 */
    val parseCalls: List<ApiCall> get() = calls.filter { it.operationId == PARSE }

    /** 真正写出的请求的操作名（按写出顺序）。 */
    val writtenOperations: List<String> get() = written.map { it.operationId }

    /** 第 [index] 个 parse 请求绑定的归属。 */
    fun ownerOf(index: Int): CallOwnership = checkNotNull(parseCalls[index].ownership) { "第 $index 个 parse 没有绑定归属" }

    fun bumpPrivacy() {
        currentEpochs = currentEpochs.copy(privacy = currentEpochs.privacy + 1)
    }

    fun bumpEnvironment() {
        currentEpochs = currentEpochs.copy(environment = currentEpochs.environment + 1)
    }

    override suspend fun send(call: ApiCall): ApiResult {
        calls += call
        val operation = call.operationId
        val index = counts[operation] ?: 0
        counts[operation] = index + 1
        val key = operation to index
        writeHolds[key]?.await()
        val pause = ioPauses[key]
        val allowed = if (pause == null) admits(call) else pause.evaluate { admits(call) }
        if (!allowed || key in writeRejects) return ApiResult.LocalReject("call_cancelled")
        written += call
        val notice = ioNotices[key]
        val onWritten = call.ownership?.onWritten
        if (notice == null) onWritten?.invoke(true) else notice.fire(onWritten)
        return when (operation) {
            PARSE -> {
                val result = reply(index).await()
                parseDelays[index]?.let { delay(it) }
                result
            }

            SEARCH -> {
                replyHolds[key]?.await()
                keywordHolds[keywordOf(call)]?.await()
                val page = if (index in morePages) """"next_cursor":"owner-v2-page-$index","has_more":true""" else NO_MORE
                ApiResult.Success(
                    SearchSubmitFixture.json("""{"items":[],"fallback_items":[],$page}"""),
                    "search-submit-000123",
                    null,
                )
            }

            else -> {
                error("提交不应额外调用 $operation")
            }
        }
    }

    /** ApiClient 写出前守卫的替身：世代、归属、发送前闸门、是否已被 cancelOwned。 */
    private fun admits(call: ApiCall): Boolean {
        val owner = call.ownership ?: return true
        val notCancelled = synchronized(cancelled) { cancelled.none { it === owner } }
        return owner.epochs == currentEpochs && owner.stillOwned() && owner.sendAllowed?.invoke() != false && notCancelled
    }

    override fun epochs(): CallEpochs = currentEpochs

    private fun keywordOf(call: ApiCall): String? = Uri.parse("https://fixture.invalid/?${call.query}").getQueryParameter("q")

    override fun cancelOwned(owner: CallOwnership) {
        cancelled += owner
    }

    private companion object {
        const val PARSE = "parseInput"
        const val SEARCH = "searchProducts"
        const val NO_MORE = """"next_cursor":null,"has_more":false"""
    }
}

/**
 * v6（评审 r2 S1-2）：写出前守卫在真实 IO 线程上求值。IO 线程到达守卫处先 [arrived] 计数、再等 [proceed]（测试线程在两者之间作废），
 * 之后才求值；[verdict] 是 IO 线程上守卫的结论（null 为还没求值），[resumed] 为发送协程已回到调用方的调度器。
 */
internal class IoGuardPause {
    val arrived = CountDownLatch(1)
    val proceed = CountDownLatch(1)
    val verdict = AtomicReference<Boolean?>(null)
    val ioThread = AtomicReference<String?>(null)
    val resumed = AtomicBoolean(false)

    suspend fun evaluate(guard: () -> Boolean): Boolean {
        val result =
            withContext(Dispatchers.IO) {
                ioThread.set(Thread.currentThread().name)
                arrived.countDown()
                check(proceed.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "测试线程没有放行 IO 线程上的守卫" }
                guard().also { verdict.set(it) }
            }
        resumed.set(true)
        return result
    }

    companion object {
        const val WAIT_SECONDS = 10L
    }
}

/**
 * OWNER v1：写出通知在另一条真实线程上调，等测试 [release] 之后才调。[fired] 在该线程调完 onWritten 之后计数；[thread] 是调用它的线程名；[hadCallback] 为这次请求的归属
 * 是否挂了 onWritten。
 */
internal class IoWriteNotice {
    private val go = CountDownLatch(1)
    val fired = CountDownLatch(1)
    val thread = AtomicReference<String?>(null)
    val hadCallback = AtomicBoolean(false)

    fun fire(onWritten: ((Boolean) -> Unit)?) {
        hadCallback.set(onWritten != null)
        Thread({
            check(go.await(IoGuardPause.WAIT_SECONDS, TimeUnit.SECONDS)) { "测试线程没有放行写出通知" }
            thread.set(Thread.currentThread().name)
            onWritten?.invoke(true)
            fired.countDown()
        }, THREAD).start()
    }

    /** 放行另一条线程去调写出通知（测试主线程排空之后再放，通知排进主线程的任务才不会被同一轮排空顺手执行）。 */
    fun release() = go.countDown()

    /** 等另一条线程调完写出通知（有上限）。 */
    fun await(): Boolean = fired.await(IoGuardPause.WAIT_SECONDS, TimeUnit.SECONDS)

    companion object {
        const val THREAD = "fixture-okhttp-writer"
    }
}
