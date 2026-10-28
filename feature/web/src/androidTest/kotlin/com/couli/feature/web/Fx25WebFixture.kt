package com.couli.feature.web

import android.content.Context
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import com.couli.core.bridge.BridgeCallValidity
import com.couli.core.bridge.BridgeCallerState
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.BridgeOutcome
import com.couli.core.bridge.BridgePrivacy
import com.couli.core.bridge.BridgeProtocol
import com.couli.core.bridge.BridgeServices
import com.couli.core.bridge.BridgeUi
import com.couli.core.bridge.BridgeUpdateState
import com.couli.core.bridge.ClearingTrustedWebStore
import com.couli.core.bridge.ExternalNavPolicy
import com.couli.core.bridge.TrustedAuthDocument
import com.couli.core.bridge.TrustedAuthSync
import com.couli.core.bridge.TrustedWebDataKind
import com.couli.core.bridge.TrustedWebStore
import com.couli.core.bridge.WebAuthIdentity
import com.couli.core.bridge.WebDataOwner
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * v3（D10-309 / D10-310）：真实 TrustedWebPage / 控制器 / 宿主 / 传输 / 系统 WebView / 产品存储端口；只换业务服务、回环 HTTP 响应，
 * 并在 [Fx25RecordingStore]、[Fx25FaultCleaner] 处观察或注入。页对象不固定一个 WebView：每次 rebuild 换新实例（[Page.web] 是当前那份），
 * 所有可信 WebView 都经产品工厂 [TrustedWebViewFactory]（基底换成可观察的 [ObservedWebView]）。
 * 每次真实加载入口前（[ObservedWebView.beforeLoad]）核对：标记已删、归属已写、存储已换、切号前的旧 WebView 全部已由产品销毁、
 * P 路径新 WebView 挂在活动配置文件上。
 * v4：这些核对跑在主线程（产品加载 / 建 WebView 的调用栈里）；失败不再在主线程抛出（会让整个仪器进程崩溃），而是记入 [violations]，
 * 由测试线程在下一次 [onMain] / [settle] / [close] 以断言失败上报——核对内容与强度不变。
 */
@Suppress("TooManyFunctions", "LargeClass") // 测试动作与观察入口逐项保留，禁止把断言藏进通用路径
internal class Fx25WebFixture(
    val mode: Fx25WebMode = Fx25WebMode.PROFILE,
    baseline: Boolean = true,
) : AutoCloseable {
    private val scenario = ActivityScenario.launch(WebTestHostActivity::class.java)

    /** 主线程核对（[beforeLoad] / [beforeBlank] / [adopt]）的失败：测试线程据此以断言失败上报，不让主线程崩进程。 */
    private val violations = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    val key = "fx25_" + UUID.randomUUID().toString().replace("-", "")
    private val cachePath = "$key.js"
    private val html =
        instrumentation.context.assets
            .open("fx25/auth-page.html")
            .bufferedReader()
            .use { it.readText() }
            .replace("__KEY__", key)
            .replace("__CACHE__", cachePath)
    private val server = LoopbackHttpsServer(instrumentation.context, ::respond)
    val origin = "https://127.0.0.1:${server.port}"
    val historyOrigin = "https://localhost:${server.port}"
    val profiles = mode == Fx25WebMode.PROFILE
    val faults = onMain { Fx25FaultCleaner(AndroidTrustedWebDataCleaner(context)) }
    var store = onMain { Fx25RecordingStore(productStore()) }
        private set
    val created = mutableListOf<ObservedWebView>()
    val webViews: TrustedWebViewFactory get() =
        TrustedWebViewFactory(
            store,
            profiles,
        ) { ctx -> createObserved(ctx) }

    /**
     * 评审 v2 r1 S1-01：一次性钩子。产品下一次经工厂建 WebView 时（[TrustedWebPage] 已读过隔离位、控制器还没发布）在主线程上调用，
     * 用来在这个窗口里确定性地插入一次并发判定（见 [judgeFromOtherThread]）。
     */
    var onNextCreate: ((ObservedWebView) -> Unit)? = null

    /** 评审 v2 r1 S2-04：为真时下一次工厂建 WebView 失败（模拟配置文件挂不上：工厂拿不到可用的可信 WebView）。 */
    var failNextCreate = false

    /** 产品回调：错误层（onLoadFailed）出现次数。 */
    val callbacks = Callbacks()
    val pages = mutableListOf<Page>()
    val metrics = mutableListOf<String>()
    private val externalControllers = mutableListOf<ExternalWebViewController>()
    var journal = onMain { AndroidWebDataJournal(context, key) }
        private set
    var owner = onMain { AndroidWebDataOwnerRecord(context, key) }
        private set
    var sync = onMain { newCoordinator() }
        private set
    private var transactionStarted = false

    /** 本次加载前是否必须已有一次成功的换存储（切号后为真；[restart] 后按持久待清标记重算）。 */
    private var rotationRequired = false

    /** [rotationRequired] 的基准：当时 [store]（这一份包装）的成功次数。[restart] 换新包装后归零。 */
    private var rotationsAtSwitch = 0

    /** 当前产品存储用的持久 key（[restart] 可换）。 */
    var storeKey = key
        private set
    private var aliveAtSwitch: List<ObservedWebView> = emptyList()
    var identity = A
        private set
    var trusted = true
    var cacheVersion = "user-a"
    var tokenHandlerEntries = 0
    var envHandlerEntries = 0
    var tokenGate: CompletableDeferred<BridgeOutcome>? = null

    // 迟到写入：切号后到达的 /fx25-late 请求扣住到 rotate 成功之后才带 Set-Cookie 放出
    @Volatile private var holdLate = false
    private val lateRelease = CountDownLatch(1)
    val lateBeforeSwitch = AtomicInteger()
    val lateHeld = AtomicInteger()
    val lateServed = AtomicInteger()

    init {
        if (baseline) {
            onMain {
                if (owner.read() == null) assertTrue(owner.write(WebDataOwner(A.account)))
                sync.observe(identity)
            }
        }
    }

    private fun productStore(): TrustedWebStore =
        when (mode) {
            Fx25WebMode.PROFILE -> AndroidTrustedWebStore(context, key, TIMEOUT_MS) { true }
            Fx25WebMode.CLEARING -> AndroidTrustedWebStore(context, key, TIMEOUT_MS) { false }
            Fx25WebMode.CLEARING_INJECTED -> ClearingTrustedWebStore(scope, faults, TIMEOUT_MS)
        }

    private fun newCoordinator() = TrustedAuthSync(scope, journal, owner, store, TIMEOUT_MS) { metrics += it }

    private fun respond(request: LoopbackHttpsServer.Recorded): LoopbackHttpsServer.Reply {
        val path = request.path.substringBefore('?')
        return when {
            path == "/$cachePath" -> {
                LoopbackHttpsServer.Reply(
                    200,
                    html = "window.fx25CacheVersion = ${JsonPrimitive(cacheVersion)};",
                    cacheControl = "public, max-age=3600",
                    contentType = "text/javascript; charset=utf-8",
                )
            }

            path == LATE_PATH -> {
                if (holdLate) {
                    lateHeld.incrementAndGet()
                    lateRelease.await(SETTLE_CEILING_MS, TimeUnit.MILLISECONDS)
                } else {
                    lateBeforeSwitch.incrementAndGet()
                }
                lateServed.incrementAndGet()
                LoopbackHttpsServer.Reply(200, headers = mapOf("Set-Cookie" to "${key}_srv=late; Path=/; Secure; SameSite=Lax"))
            }

            else -> {
                LoopbackHttpsServer.Reply(200, html = html)
            }
        }
    }

    /**
     * v5：[beforeLoad] / [afterLoad] 与产品 `load` 在同一个主线程消息里执行（中间不让出主线程，协调器的新一轮插不进来），[afterLoad]
     * 拿到 `load` 的返回值，用来在产品返回的那一刻检查页面与控制器的状态。
     */
    @Suppress("LongParameterList") // 既有参数 + v5 的两个同步检查点
    fun open(
        name: String = "foreground",
        hidden: Boolean = false,
        holdToken: Boolean = false,
        expectLoaded: Boolean = true,
        beforeLoad: () -> Unit = {},
        afterLoad: (Page, Boolean) -> Unit = { _, _ -> },
    ): Page {
        val page = Page(name)
        onMain {
            page.product =
                TrustedWebPage(
                    context,
                    webViews,
                    TrustedWebConfig(setOf(origin, historyOrigin), emptySet(), "1.0.0"),
                    { host() },
                    callbacks,
                    sync,
                ) { web -> adopt(page, web as ObservedWebView, hidden, holdToken) }
            pages += page
            beforeLoad()
            val loaded = page.product.load("$origin/$name")
            afterLoad(page, loaded)
        }
        if (expectLoaded) awaitDocument(page)
        return page
    }

    /** 产品每建一份新 WebView（及其控制器）、加载之前交来：挂到视图树、加审计监听、装加载前核对。 */
    private fun adopt(
        page: Page,
        web: ObservedWebView,
        hidden: Boolean,
        holdToken: Boolean,
    ) = guarded {
        assertTrue("交来的 WebView 必须出自产品工厂", web in created)
        if (profiles) {
            web.profileName = WebViewCompat.getProfile(web).name
            assertEquals("工厂建好即挂在活动配置文件上", store.current(), web.profileName)
        }
        page.webs += web
        web.holdToken = holdToken
        scenario.onActivity { activity ->
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            activity.root.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        WebViewCompat.addWebMessageListener(web, AUDIT, setOf(origin, historyOrigin)) { _, message, _, main, _ ->
            if (main) page.receipts += Json.parseToJsonElement(checkNotNull(message.data)).jsonObject
        }
        web.beforeLoad = { guarded { beforeLoad(web) } }
        web.beforeBlank = { guarded { beforeBlank(web) } }
        if (hidden) web.visibility = View.GONE
    }

    private fun beforeLoad(web: ObservedWebView) {
        if (!transactionStarted) return
        assertNull("rebuild 前待清标记已删", journal.readPending())
        assertEquals("rebuild 前归属已写为当前身份", identity.account, owner.read()?.account)
        if (rotationRequired) {
            assertTrue(
                "rebuild 前存储已成功更换 / 清理（成功 ${store.successfulRotations} 次，基准 $rotationsAtSwitch）",
                store.successfulRotations > rotationsAtSwitch,
            )
        }
        val alive = aliveAtSwitch.filter { !it.destroyedByProduct }
        assertTrue("新建页加载前切号前的旧可信 WebView 已全部销毁（剩 ${alive.size}）", alive.isEmpty())
        assertFalse("新文档不能在切号前的旧 WebView 上加载", web in aliveAtSwitch)
        if (profiles) assertEquals("新 WebView 在新配置文件上", store.current(), WebViewCompat.getProfile(web).name)
        if (rotationRequired && mode == Fx25WebMode.CLEARING_INJECTED) {
            assertEquals("F 路径四类均已确认", TrustedWebDataKind.entries.toSet(), faults.confirmed)
        }
    }

    /** 产品拆旧页（about:blank）时：可信、已交付令牌的旧文档必须先被投递过 A（是否确认不论，D10-310 (1)）。 */
    private fun createObserved(ctx: Context): ObservedWebView {
        if (failNextCreate) {
            failNextCreate = false
            error("fx25：配置文件挂不上（测试注入）")
        }
        val web = ObservedWebView(ctx).also { created += it }
        onNextCreate?.let { hook ->
            onNextCreate = null
            guarded { hook(web) }
        }
        return web
    }

    /**
     * 在**另一个线程**上做一次身份判定（[TrustedAuthSync.observe]），调用线程（主线程，在 [onNextCreate] 里）等它同步返回：
     * 判定的同步部分（隔离、写标记）一定落在「产品读过隔离位之后、发布控制器之前」。等待有上限（满足即返回），不靠 sleep。
     */
    @Suppress("TooGenericExceptionCaught") // 判定线程上的任何异常都要带回调用线程上报
    fun judgeFromOtherThread(next: WebAuthIdentity) {
        markSwitch(next)
        val done = CountDownLatch(1)
        val failure = arrayOfNulls<Throwable>(1)
        Thread {
            try {
                sync.observe(next)
            } catch (error: Throwable) {
                failure[0] = error
            } finally {
                done.countDown()
            }
        }.start()
        WebWait.latch("另一线程上的身份判定同步返回", done)
        failure[0]?.let { throw it }
    }

    /** v5：已经由 [judgeOnNextStoreRead] 注入的判定次数（主线程读写）。 */
    var injectedJudgements = 0
        private set

    /**
     * v5（评审 v2 r2 S2-1）：下一次读活动存储时（主线程，产品调用栈里）先在另一个线程上判定切到 [next]（见 [judgeFromOtherThread]）。
     * 钩子里的失败走 [guarded]，报成断言失败。
     */
    fun judgeOnNextStoreRead(next: WebAuthIdentity) {
        store.onNextCurrent = {
            injectedJudgements += 1
            guarded { judgeFromOtherThread(next) }
        }
    }

    /**
     * v5（评审 v2 r2 S1-01 页面一半）：协调器登记的、属于 [page] 的那份 [TrustedAuthDocument]。只读反射：按类型在协调器的集合字段里
     * 找元素上类型为 [TrustedAuthDocument] 的字段，再按外层对象认出属于 [page.product] 的那份（不依赖字段名）。主线程。
     */
    fun documentOf(page: Page): TrustedAuthDocument {
        val found =
            TrustedAuthSync::class.java.declaredFields
                .flatMap { field ->
                    field.isAccessible = true
                    (field.get(sync) as? Iterable<*>)?.toList().orEmpty()
                }.flatMap { element -> element?.let(::documentsIn).orEmpty() }
                .distinct()
                .filter { document ->
                    document.javaClass.declaredFields.any { field ->
                        field.isAccessible = true
                        field.get(document) === page.product
                    }
                }
        return checkNotNull(found.singleOrNull()) { "${page.name} 在协调器里应当恰好登记一份文档（找到 ${found.size}）" }
    }

    private fun documentsIn(element: Any): List<TrustedAuthDocument> =
        element.javaClass.declaredFields.mapNotNull { field ->
            field.isAccessible = true
            field.get(element) as? TrustedAuthDocument
        }

    private fun markSwitch(next: WebAuthIdentity) {
        if (identity.account != next.account) {
            transactionStarted = true
            rotationRequired = true
            rotationsAtSwitch = store.successfulRotations
            aliveAtSwitch = created.filter { !it.destroyedByProduct }
            faults.confirmed.clear()
            holdLate = true
        }
        identity = next
    }

    private fun beforeBlank(web: ObservedWebView) {
        val eligible = trusted && web.bindingHashes.isNotEmpty() && !web.holdToken && !web.rendererGone
        if (transactionStarted && eligible) assertTrue("拆之前先发 A", web.eventAttempts > 0)
    }

    private fun host(): BridgeHost {
        val unused =
            Proxy.newProxyInstance(BridgeServices::class.java.classLoader, arrayOf(BridgeServices::class.java)) { _, method, _ ->
                if (method.name == "reportRejected") Unit else error("unexpected handler ${method.name}")
            } as BridgeServices
        val services =
            object : BridgeServices by unused {
                override fun env(): JsonObject {
                    envHandlerEntries += 1
                    return JsonObject(emptyMap())
                }

                override suspend fun h5Token(
                    readOnly: Boolean,
                    validity: BridgeCallValidity,
                ): BridgeOutcome {
                    tokenHandlerEntries += 1
                    return tokenGate?.await() ?: BridgeOutcome.Ok(buildJsonObject { put("token", "test-new-account-token") })
                }
            }
        val ui =
            Proxy.newProxyInstance(BridgeUi::class.java.classLoader, arrayOf(BridgeUi::class.java)) { _, _, _ ->
                error("unexpected UI call")
            } as BridgeUi
        return BridgeHost(
            { BridgeProtocol(if (trusted) setOf(origin, historyOrigin) else emptySet(), JsonObject(emptyMap()), emptySet()) },
            { BridgeCallerState(BridgePrivacy.CONSENTED, identity.account, BridgeUpdateState.SUPPORTED, identity.loginEpoch) },
            services,
            ui,
            { BridgeUpdateState.SUPPORTED },
            scope,
        )
    }

    fun probe(page: Page) = PageProbe(checkNotNull(onMain { page.web }) { "${page.name} 当前没有 WebView" }) { action -> onMain(action) }

    fun probe(web: WebView) = PageProbe(web) { action -> onMain(action) }

    /** 等页面当前那份 WebView 上出现一份与 [previous] 不同的、加载完成的文档。 */
    fun awaitDocument(
        page: Page,
        previous: String = "null",
    ) {
        WebWait.until("${page.name} 有一份产品建好的存活 WebView") { onMain { page.web?.destroyedByProduct == false } }
        probe(page).await(
            "${page.name} 真实新文档加载完成",
            "document.readyState === 'complete' && !!window.fx25Document && window.fx25Document !== $previous",
        ) { it == "true" }
    }

    fun switch(next: WebAuthIdentity = B) =
        onMain {
            markSwitch(next)
            sync.observe(next)
        }

    fun sessionCleared(reason: String) =
        onMain {
            transactionStarted = true
            rotationRequired = true
            rotationsAtSwitch = store.successfulRotations
            aliveAtSwitch = created.filter { !it.destroyedByProduct }
            holdLate = true
            identity = WebAuthIdentity(true, null, identity.loginEpoch + 1)
            sync.sessionCleared(reason)
        }

    /** 切号后到达的迟到请求只在 rotate 成功之后放出（[Fx25RecordingStore.onRotated]）。 */
    fun releaseLateAfterRotation() =
        onMain {
            store.onRotated = { lateRelease.countDown() }
        }

    /**
     * 「重启」：关掉协调器与全部页面，用同一 key 重建产品存储（持久状态）、journal、归属记录与协调器；不 observe。
     * v4：换了新的 [Fx25RecordingStore] 包装（成功次数从 0 起），加载前核对的基准随之复位：重启时仍有持久待清标记 → 冷启动必须先在
     * 新包装上成功换存储一次才可加载；没有待清（上一轮已结算）→ 冷启动不该再换存储，只核对标记已删、归属一致、旧 WebView 已销毁、
     * 挂在活动配置文件上。
     */
    fun restart(
        profilesSupported: () -> Boolean = { profiles },
        storeKey: String = key,
    ) = onMain {
        this.storeKey = storeKey
        sync.close()
        pages.forEach { it.product.destroy() }
        pages.clear()
        faults.confirmed.clear()
        store =
            Fx25RecordingStore(
                if (mode == Fx25WebMode.CLEARING_INJECTED) {
                    ClearingTrustedWebStore(scope, faults, TIMEOUT_MS)
                } else {
                    AndroidTrustedWebStore(context, storeKey, TIMEOUT_MS, profilesSupported)
                },
            )
        journal = AndroidWebDataJournal(context, key)
        owner = AndroidWebDataOwnerRecord(context, key)
        rotationsAtSwitch = 0
        rotationRequired = journal.readPending() != null
        if (rotationRequired) {
            transactionStarted = true
            aliveAtSwitch = (aliveAtSwitch + created.filter { !it.destroyedByProduct }).distinct()
        }
        sync = newCoordinator()
    }

    fun settle(): Boolean {
        val latch = CountDownLatch(1)
        var success = false
        onMain {
            sync.whenSettled {
                success = it
                latch.countDown()
            }
        }
        WebWait.latch("身份事务真正结算", latch, SETTLE_CEILING_MS)
        assertNoViolations()
        return success
    }

    fun authEvents(page: Page): List<JsonObject> = onMain { page.receipts.filter { it["type"]?.jsonPrimitive?.content == "auth" } }

    fun replyCount(
        page: Page,
        id: String,
    ): Int =
        onMain {
            page.receipts.count {
                it["type"]?.jsonPrimitive?.content == "reply" &&
                    it["reply"]
                        ?.jsonObject
                        ?.get("id")
                        ?.jsonPrimitive
                        ?.content == id
            }
        }

    fun assertBridgeRejected(
        page: Page,
        id: String = "isolated",
    ) {
        val entries = onMain { tokenHandlerEntries to envHandlerEntries }
        probe(page).fire("window.fx25Call('$id-token','auth.getH5Token'); window.fx25Call('$id-env','app.getEnv');")
        WebWait.until("两条真实桥调用都已结算") { replyCount(page, "$id-token") == 1 && replyCount(page, "$id-env") == 1 }
        assertEquals(entries, onMain { tokenHandlerEntries to envHandlerEntries })
        val replies =
            onMain {
                page.receipts.filter {
                    it["reply"]
                        ?.jsonObject
                        ?.get("id")
                        ?.jsonPrimitive
                        ?.content
                        ?.startsWith(id) == true
                }
            }
        replies.forEach {
            assertTrue(
                it
                    .getValue("reply")
                    .jsonObject
                    .getValue("code")
                    .jsonPrimitive.content in setOf("90500", "90401"),
            )
        }
    }

    /** 隔离中：页面层 load 不加载；当前若有 WebView，再走它真实的 shouldOverrideUrlLoading 与后台拦截入口。 */
    fun assertNavigationBlocked(page: Page) =
        onMain {
            val loads = page.loads()
            val request = request("$origin/forbidden-navigation")
            assertFalse("隔离中页面层 load 不加载", page.product.load(request.url.toString()))
            page.web?.let { web ->
                assertTrue("真实 shouldOverrideUrlLoading 必须拦截", web.webViewClient.shouldOverrideUrlLoading(web, request))
                val response = web.webViewClient.shouldInterceptRequest(web, request)
                assertTrue("POST/程序加载的后台入口也要拦", response != null && response.statusCode in setOf(204, 403))
            }
            assertEquals(loads, page.loads())
        }

    fun seed(web: WebView) {
        val probe = probe(web)
        probe.fire("window.fx25Seed()")
        probe.await("Cookie/local/session/IndexedDB 已写入并提交", "window.fx25Seeded") { it == "true" }
        assertEquals(STORED, readStorage(web))
    }

    fun readStorage(web: WebView): JsonObject {
        val probe = probe(web)
        probe.fire("window.fx25Storage = null; window.fx25Read()")
        return Json.parseToJsonElement(probe.await("IndexedDB 事务完成后读取", "window.fx25Storage") { it != "null" }).jsonObject
    }

    /** 迟到写入的全部痕迹（属性赋值 / setItem 写的 local、session，document.cookie，IndexedDB，服务端迟到 Set-Cookie）。 */
    fun readLate(web: WebView): JsonObject {
        val probe = probe(web)
        probe.fire("window.fx25Late = null; window.fx25ReadLate()")
        return Json.parseToJsonElement(probe.await("迟到写入读取完成", "window.fx25Late") { it != "null" }).jsonObject
    }

    /** 活动可信存储的 Cookie 罐（P：活动配置文件；F：默认配置文件）里本用例的 Cookie。 */
    fun trustedCookies(): String =
        onMain {
            val manager =
                if (profiles) {
                    checkNotNull(ProfileStore.getInstance().getProfile(store.current())) { "活动配置文件必须存在" }.cookieManager
                } else {
                    CookieManager.getInstance()
                }
            manager.flush()
            manager
                .getCookie(origin)
                .orEmpty()
                .split("; ")
                .filter { it.startsWith(key) }
                .joinToString("; ")
        }

    fun cacheRequests(): Int = synchronized(server.requests) { server.requests.count { it.path.substringBefore('?') == "/$cachePath" } }

    fun openExternal(): WebView =
        onMain {
            val web = WebView(context)
            scenario.onActivity { it.root.addView(web, FrameLayout.LayoutParams(MATCH, MATCH)) }
            val patterns =
                Json
                    .parseToJsonElement(
                        """{"version":1,"rules":[{"platform":"taobao","category":"union_host","hosts":["blocked.test"],""" +
                            """"path_patterns":[]}]}""",
                    ).jsonObject
            val callbacks =
                Proxy.newProxyInstance(
                    ExternalWebCallbacks::class.java.classLoader,
                    arrayOf(ExternalWebCallbacks::class.java),
                ) { _, method, _ ->
                    check(method.name !in setOf("onUnavailable", "onLoadFailed")) { "external control unavailable: ${method.name}" }
                    Unit
                } as ExternalWebCallbacks
            val controller =
                ExternalWebViewController(
                    web,
                    ExternalNavPolicy(patterns),
                    { patterns },
                    { ExternalNavFlags(true, false) },
                    {},
                    callbacks,
                    "1.0.0",
                )
            assertTrue("外部 profile 必须可用，不能跳过隔离对照", controller.available)
            externalControllers += controller
            controller.open("$origin/external")
            web
        }.also { web ->
            probe(web).await("外部文档加载完成", "document.readyState === 'complete' && !!window.fx25Document") { it == "true" }
        }

    /**
     * P 路径产品存储的持久状态（plan ①：{active, retired[]}）：noBackupFilesDir 下以 [storeKey] 结尾、含 active 与 retired 的那一份 JSON。
     * 只读，用来证明「旧配置文件名已持久记入 retired[]」（同进程删不掉时冷启动靠它删，D10-310 (5)）。
     */
    fun persistedStoreState(): JsonObject =
        onMain {
            val candidates =
                context.noBackupFilesDir
                    .listFiles()
                    .orEmpty()
                    .filter { it.isFile && it.name.endsWith("$storeKey.json") }
                    .mapNotNull { file -> parseStoreState(file) }
            assertEquals("恰有一份含 active / retired 的持久存储状态（key=$storeKey）", 1, candidates.size)
            candidates.single()
        }

    /** 持久状态里的 retired[]。 */
    fun retiredOnDisk(): List<String> = (persistedStoreState().getValue("retired") as JsonArray).map { it.jsonPrimitive.content }

    /** 「退役存储删不掉、留待冷启动」埋点的累计次数。 */
    fun deferredDiscards(): Int = onMain { metrics.count { it == TrustedAuthSync.METRIC_DISCARD_DEFERRED } }

    fun <T> onMain(action: () -> T): T = onMainUnchecked(action).also { assertNoViolations() }

    /** 主线程核对的失败以断言失败上报（带首个失败为 cause）。 */
    fun assertNoViolations() {
        val first = violations.firstOrNull() ?: return
        throw AssertionError("夹具主线程核对失败（共 ${violations.size} 处，首个）：${first.message}", first)
    }

    private fun <T> onMainUnchecked(action: () -> T): T {
        var result: Result<T>? = null
        scenario.onActivity { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    /** 主线程上的核对：失败记入 [violations]，不抛出（抛在产品的主线程调用栈里会让仪器进程崩溃）。 */
    @Suppress("TooGenericExceptionCaught") // 任何核对失败（断言、checkNotNull、查询配置文件出错）都要转交测试线程上报
    private fun guarded(check: () -> Unit) {
        try {
            check()
        } catch (failure: Throwable) {
            violations += failure
        }
    }

    override fun close() {
        try {
            onMainUnchecked {
                sync.close()
                pages.forEach { it.product.destroy() }
                externalControllers.forEach { it.destroy() }
            }
        } finally {
            lateRelease.countDown()
            scope.cancel()
            server.close()
            scenario.close()
        }
        assertNoViolations()
    }

    /** 测试侧的页面槽位：[product] 是产品页面对象；[webs] 是它先后用过的全部 WebView（rebuild 一次多一份）。 */
    class Page(
        val name: String,
    ) {
        lateinit var product: TrustedWebPage
        val webs = mutableListOf<ObservedWebView>()
        val receipts = mutableListOf<JsonObject>()

        /** 产品此刻挂的那份 WebView（拆后到建前为 null）。 */
        val web: ObservedWebView? get() = product.webView as ObservedWebView?

        fun loads(): Int = webs.sumOf { it.loads }
    }

    class ObservedWebView(
        context: Context,
    ) : WebView(context) {
        var loads = 0
            private set
        var blankLoads = 0
            private set
        var beforeLoad: () -> Unit = {}
        var beforeBlank: () -> Unit = {}
        var holdToken = false
        var holdEvent = false
        var holdBlank = false

        /** 测试终止了它的渲染进程（[beforeBlank] 不再要求先发 A：渲染进程已没有，A 无处执行）。 */
        var rendererGone = false
        var destroyedByProduct = false
            private set

        /** 建好交来时所在的配置文件（P 路径；销毁后不能再查询）。 */
        var profileName: String? = null
        var eventAttempts = 0
            private set
        val eventExecutions = mutableListOf<String>()
        val heldEvents = mutableListOf<Pair<String, ValueCallback<String>?>>()
        val heldBindings = mutableListOf<Pair<String, ValueCallback<String>?>>()
        val bindingHashes = mutableListOf<Int>()
        val bindingScripts = mutableListOf<String>()

        override fun loadUrl(url: String) {
            if (url == ABOUT_BLANK) {
                beforeBlank()
                blankLoads += 1
                if (holdBlank) return // 模拟 about:blank 迟迟不提交：拆除拿不到确认
                super.loadUrl(url)
                return
            }
            beforeLoad()
            loads += 1
            super.loadUrl(url)
        }

        override fun reload() {
            beforeLoad()
            loads += 1
            super.reload()
        }

        override fun destroy() {
            destroyedByProduct = true
            super.destroy()
        }

        @Suppress("ReturnCount") // 各个故障闸门必须在交给系统执行前结束。
        override fun evaluateJavascript(
            script: String,
            resultCallback: ValueCallback<String>?,
        ) {
            val event = script.contains(BridgeTransport.AUTH_EVENT) || script.contains("auth.changed")
            if (event) {
                eventAttempts += 1
                if (holdEvent) {
                    heldEvents += script to resultCallback
                    return
                }
                // 审计 WebMessage 的到达顺序与 evaluate callback 不绑定；在真实执行确认处记录。
                super.evaluateJavascript(script) { result ->
                    if (result == "true") eventExecutions += script
                    resultCallback?.onReceiveValue(result)
                }
                return
            }
            if (script.contains(BridgeTransport.BIND_DOCUMENT)) {
                if (holdToken) {
                    heldBindings += script to resultCallback
                    return
                }
                bindingHashes += script.hashCode()
                bindingScripts += script
            }
            super.evaluateJavascript(script, resultCallback)
        }

        fun flushEvents() {
            val held = heldEvents.toList()
            heldEvents.clear()
            holdEvent = false
            held.forEach { (script, callback) -> evaluateJavascript(script, callback) }
        }

        /** 放开被扣住的 about:blank：真正提交一次，让产品等着的拆除确认到来。 */
        fun flushBlank() {
            holdBlank = false
            loadUrl(ABOUT_BLANK)
        }

        fun flushBindings() {
            val held = heldBindings.toList()
            heldBindings.clear()
            holdToken = false
            held.forEach { (script, callback) -> evaluateJavascript(script, callback) }
        }
    }

    class Callbacks : TrustedWebCallbacks {
        /** 错误层出现次数（主线程写）。 */
        @Volatile var loadFailures = 0
            private set

        override fun onOpenExternal(url: String) = error("隔离文档不能触发外跳")

        override fun onLoadFailed() {
            loadFailures += 1 // 失败是被测状态，由 settle(false)、错误层计数与真实入口断言验证。
        }
    }

    companion object {
        val A = WebAuthIdentity(true, "user-a", 1L)
        val B = WebAuthIdentity(true, "user-b", 2L)
        val GUEST = WebAuthIdentity(true, null, 3L)
        val STORED = Json.parseToJsonElement("""{"cookie":true,"local":"user-a","session":"user-a","indexed":"user-a"}""").jsonObject
        val EMPTY = Json.parseToJsonElement("""{"cookie":false,"local":null,"session":null,"indexed":null}""").jsonObject
        val LATE_EMPTY =
            Json
                .parseToJsonElement(
                    """{"cookie":false,"server":false,"localProperty":null,"localSetItem":null,""" +
                        """"sessionProperty":null,"sessionSetItem":null,"indexed":null}""",
                ).jsonObject
        const val LATE_PATH = "/fx25-late"
        private const val ABOUT_BLANK = "about:blank"

        /**
         * 注入协调器与产品存储的单步上限：不低于生产步上限（10 s），另加 2 s 余量（测试 v2 口径不变）。A 的限时、拆除确认、未知快照等待
         * 均按它；F 路径整轮清理与 rebuild 等待为它的倍数。
         */
        internal const val TIMEOUT_MS = 12_000L

        /** [settle] 的等待上限：不低于产品一轮的最坏耗时（满足即返回，只防产品挂死）。 */
        internal val SETTLE_CEILING_MS =
            TIMEOUT_MS * (2 + TrustedAuthSync.ROTATE_TIMEOUT_FACTOR + TrustedAuthSync.REBUILD_TIMEOUT_FACTOR) +
                ClearingTrustedWebStore.GRACE_MS + WebWait.CEILING_MS
        private const val AUDIT = "__fx25Audit"
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT

        @Suppress("TooGenericExceptionCaught", "SwallowedException") // 不是存储状态的文件（journal、归属记录、临时文件）一律跳过
        private fun parseStoreState(file: File): JsonObject? =
            try {
                Json.parseToJsonElement(file.readText()).jsonObject.takeIf { it["active"] != null && it["retired"] is JsonArray }
            } catch (error: Exception) {
                null
            }

        fun request(url: String): WebResourceRequest =
            object : WebResourceRequest {
                override fun getUrl(): Uri = Uri.parse(url)

                override fun isForMainFrame() = true

                override fun isRedirect() = false

                override fun hasGesture() = true

                override fun getMethod() = "GET"

                override fun getRequestHeaders(): Map<String, String> = mapOf("Accept" to "text/html")
            }
    }
}
