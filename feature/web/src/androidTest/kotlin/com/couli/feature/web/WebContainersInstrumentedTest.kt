package com.couli.feature.web

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.couli.core.bridge.BridgeCallValidity
import com.couli.core.bridge.BridgeCallerState
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.BridgeOutcome
import com.couli.core.bridge.BridgePrivacy
import com.couli.core.bridge.BridgeProtocol
import com.couli.core.bridge.BridgeServices
import com.couli.core.bridge.BridgeUi
import com.couli.core.bridge.BridgeUpdateState
import com.couli.core.bridge.ExternalNavAction
import com.couli.core.bridge.ExternalNavDecision
import com.couli.core.bridge.ExternalNavPolicy
import com.couli.core.clientcore.ClientEvent
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 测试宿主：一个空的 FrameLayout，真实 WebView 挂在窗口上。 */
class WebTestHostActivity : Activity() {
    lateinit var root: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        setContentView(root)
    }
}

/**
 * 真实系统 WebView 加载包内测试 HTML（androidTest assets）：
 * - TrustedWebView：可信源主框架能调用；子框架调用被拒（90403）；缺手势的 clipboard.write 被拒（90404），真实触摸后放行；
 *   不可信源的页面里没有桥对象（传输层根本不注入）。
 * - ExternalWebView：页面里没有桥对象与传输通道；平台域的主框架导航、子框架文档被拦；intent:// 取消并上报；商品页交识别。
 * 测试页用 loadDataWithBaseURL 给出 https 源（不连任何网络；平台域请求在发出前就被拦下）。
 */
@RunWith(AndroidJUnit4::class)
class WebContainersInstrumentedTest {
    private lateinit var scenario: ActivityScenario<WebTestHostActivity>
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun launch() {
        scenario = ActivityScenario.launch(WebTestHostActivity::class.java)
    }

    @After
    fun close() {
        scenario.close()
    }

    private fun asset(name: String): String =
        instrumentation.context.assets
            .open(name)
            .use { it.readBytes().decodeToString() }

    private fun <T> onMain(block: (WebTestHostActivity) -> T): T {
        var value: T? = null
        scenario.onActivity { value = block(it) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun newWebView(): WebView =
        onMain { activity ->
            WebView(activity).also { activity.root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
        }

    private fun probe(webView: WebView) = PageProbe(webView) { block -> onMain { block() } }

    /** 在主线程求值并等结果（测试代码读取页面状态用；容器自己从不这样做）。只在页面已交回结论之后读。 */
    private fun evaluate(
        webView: WebView,
        script: String,
    ): String = probe(webView).read(script)

    /** F-AND-31：等页面的 window.results 交回满足 [condition] 的结论（探测不挂在导航上，见 [PageProbe.await]）。 */
    private fun waitFor(
        webView: WebView,
        message: String,
        condition: (JsonObject) -> Boolean,
    ): JsonObject {
        val answer =
            probe(webView).await(message, RESULTS_SCRIPT) { raw ->
                parseResults(raw)?.let(condition) == true
            }
        return checkNotNull(parseResults(answer))
    }

    private fun parseResults(raw: String): JsonObject? =
        runCatching {
            val text = Json.parseToJsonElement(raw).jsonPrimitive.content
            Json.parseToJsonElement(text).jsonObject
        }.getOrNull()

    private fun tap(webView: WebView) =
        onMain {
            val now = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(now, now, action, 10f, 10f, 0)
                webView.dispatchTouchEvent(event)
                event.recycle()
            }
        }

    private fun trustedHost(): BridgeHost =
        BridgeHost(
            protocol = { BridgeProtocol(setOf(TRUSTED), JsonObject(emptyMap()), emptySet()) },
            caller = { BridgeCallerState(BridgePrivacy.CONSENTED, accountKey = null, update = BridgeUpdateState.SUPPORTED) },
            services = OkServices,
            ui = NoUi,
            awaitUpdate = { BridgeUpdateState.SUPPORTED },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )

    /**
     * 子框架都不得成功（原生 90403，或者根本没有桥）。APP-04 三端共性第 1 条：主框架身份来自 addWebMessageListener 的 isMainFrame，
     * 不看 URL——地址与主框架完全相同的子框架、sandbox 子框架同样被拒。
     */
    private fun assertSubframesRejected(results: JsonObject) {
        val subframe = results.getValue("subframe").jsonPrimitive.content
        assertTrue(
            "subframe call must not succeed (90403 from native, or no bridge at all): $subframe",
            subframe == "90403" || subframe == "absent",
        )
        // 能力核对用：子框架里到底有没有桥（有则原生 90403 拒收）
        android.util.Log.i(LOG_TAG, "subframe outcome=$subframe")
        // 同地址子框架：可信容器取消指向可信 bridge origin 的子框架文档（编排者裁定、第 13 条），它根本载不进内容、也就不会回报
        assertTrue("same-URL subframe never ran: ${results["sameUrl"]}", !results.containsKey("sameUrl"))
        val sandboxed = results.getValue("sandboxed").jsonPrimitive.content
        android.util.Log.i(LOG_TAG, "sandboxed subframe outcome=$sandboxed")
        assertTrue("sandboxed subframe must not succeed: $sandboxed", sandboxed == "90403" || sandboxed == "absent")
    }

    @Test
    @Suppress("LongMethod") // 一页里依次验证主框架、子框架、手势与在途 id
    fun trustedPageCallsFromTheMainFrameButSubframeAndMissingGestureAreRejected() {
        assumeTrue("system WebView supports the transport", BridgeTransport.isSupported())
        val webView = newWebView()
        // F-AND-31（FU-AND-0714 定格、FU-AND-0716 改为全程注入）：手势时钟只在测试推进时走动。点击到桥消息到达之间的调度延迟（负载约 60
        // 时可超过 1 秒）不属于被测逻辑；点击与这次调用读到同一时刻，验证的仍是「系统派发的真实触摸之后同一调用放行」，窗口判定照生产执行
        val clock = ManualClock()
        val controller =
            onMain {
                TrustedWebViewController(
                    webView,
                    TrustedWebConfig(setOf(TRUSTED), emptySet(), "1.0.0"),
                    trustedHost(),
                    NoTrustedCallbacks,
                    clock::now,
                )
            }
        assertTrue(controller.bridgeInstalled)
        val server =
            onMain { SameUrlFrameServer(webView.webViewClient, "$TRUSTED/test", SAME_URL_FRAME).also { webView.webViewClient = it } }
        onMain { webView.loadDataWithBaseURL("$TRUSTED/test", asset("bridge-trusted.html"), "text/html", "utf-8", null) }

        val results =
            waitFor(webView, "trusted page done with both subframes reported") {
                it["done"]?.jsonPrimitive?.content == "true" &&
                    it.containsKey("subframe") &&
                    it.containsKey("sandboxed")
            }
        // 原为固定 200 毫秒：改为等容器对同地址子框架文档交回判定
        WebWait.until("same-URL subframe document decided", detail = { "cancelled=${server.cancelled} passed=${server.passed}" }) {
            "$TRUSTED/test" in server.cancelled || "$TRUSTED/test" in server.passed
        }
        assertTrue("container cancelled the same-URL subframe document", "$TRUSTED/test" in server.cancelled)
        assertTrue("the test double never had to serve it", server.served.isEmpty())

        assertEquals("bridge present in the trusted main frame", "true", results.getValue("present").jsonPrimitive.content)
        assertEquals(
            "L0 call from the main frame succeeds",
            0,
            results
                .getValue("mainFrame")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            "clipboard.write without a gesture is rejected",
            BRIDGE_GESTURE_MISSING,
            results
                .getValue("noGesture")
                .jsonPrimitive.content
                .toInt(),
        )
        assertSubframesRejected(results)

        // 真实触摸（系统派发的 MotionEvent）之后 1 秒内，同一调用放行（点击与调用读同一注入时刻，见上）
        tap(webView)
        probe(webView).fire("callForTest('clipboard.write', {text: 'x'}, 'withGesture')")
        val after = waitFor(webView, "clipboard.write after a tap answered") { it.containsKey("withGesture") }
        assertEquals(
            0,
            after
                .getValue("withGesture")
                .jsonPrimitive.content
                .toInt(),
        )

        // 第 19 条：在途 id 重复——后来的那次由桥层直接 90002、不发送，也不覆盖原来那条待回应的调用
        probe(webView).fire(DUPLICATE_ID_SCRIPT)
        val duplicate = waitFor(webView, "both duplicate-id calls answered") { it.containsKey("dupFirst") && it.containsKey("dupSecond") }
        assertEquals(
            BRIDGE_INVALID_PARAMS,
            duplicate
                .getValue("dupSecond")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            "the first call got its own reply (L1 without login)",
            BRIDGE_NOT_LOGGED_IN,
            duplicate
                .getValue("dupFirst")
                .jsonPrimitive.content
                .toInt(),
        )
        onMain { controller.destroy() }
    }

    /**
     * 编排者裁定（同源子框架第 13 条；看不到每一跳就在第一跳取消 (a)、R3-01、共性第 56 条）与第 16 条：可信容器里的网络子框架文档
     * 一律取消——同源（不论路径）、授权页、跨源普通页都不发出（跨源子框架可能被服务器重定向进可信源，真实重定向见
     * WebContainersReviewR3Test）；授权页的主框架导航与初始地址同样不进容器。
     */
    @Test
    fun trustedContainerCancelsEveryNetworkSubframeDocument() {
        val webView = newWebView()
        val controller =
            onMain {
                TrustedWebViewController(webView, TrustedWebConfig(setOf(TRUSTED), emptySet(), "1.0.0"), trustedHost(), NoTrustedCallbacks)
            }
        val server =
            onMain { SameUrlFrameServer(webView.webViewClient, "$TRUSTED/test", SAME_URL_FRAME).also { webView.webViewClient = it } }
        onMain { webView.loadDataWithBaseURL("$TRUSTED/test", FRAMES_PAGE, "text/html", "utf-8", null) }
        // 原为「最多 10 秒等取消数到 3 + 固定 200 毫秒」（到时不判失败）：改为等三个子框架文档都由容器交回判定
        WebWait.until("all three subframe documents decided", detail = { "cancelled=${server.cancelled} passed=${server.passed}" }) {
            val decided = server.cancelled + server.passed
            decided.any { it == "$TRUSTED/other" } &&
                decided.any { it.startsWith("https://appleid.apple.com/") } &&
                decided.any { it.startsWith(FOREIGN) }
        }
        android.util.Log.i(LOG_TAG, "trusted subframes cancelled=${server.cancelled} passed=${server.passed}")
        assertTrue("same-origin subframe (other path) cancelled", "$TRUSTED/other" in server.cancelled)
        assertTrue("auth page subframe cancelled", server.cancelled.any { it.startsWith("https://appleid.apple.com/") })
        assertTrue("cross-origin subframe cancelled too (R3-01)", server.cancelled.any { it.startsWith(FOREIGN) })
        assertTrue("no frame document passed: ${server.passed}", server.passed.none { it.startsWith(FOREIGN) || "/other" in it })
        assertTrue("auth page as the initial URL is refused", !controller.load("https://appleid.apple.com/auth/authorize"))
        onMain { controller.destroy() }
    }

    @Test
    fun untrustedOriginGetsNoBridgeAtAll() {
        assumeTrue(BridgeTransport.isSupported())
        // 传输层本身：allowedOriginRules 只含可信源，非可信源的文档里既没有通道也没有启动脚本
        val webView = newWebView()
        val transport =
            onMain {
                webView.applyContainerSettings("1.0.0")
                BridgeTransport(webView, trustedHost(), GestureClock()).also { it.install(setOf(TRUSTED)) }
            }
        onMain { webView.loadDataWithBaseURL("$FOREIGN/test", asset("bridge-trusted.html"), "text/html", "utf-8", null) }

        val results = waitFor(webView, "untrusted page done") { it["done"]?.jsonPrimitive?.content == "true" }

        assertEquals("no __REBATE_BRIDGE__ on an untrusted origin", "false", results.getValue("present").jsonPrimitive.content)
        assertEquals("no transport channel either", "\"undefined\"", evaluate(webView, "typeof window.__couliBridgeChannel"))
        onMain { transport.dispose() }

        // 容器：非可信源不加载
        val other = newWebView()
        val controller =
            onMain {
                TrustedWebViewController(other, TrustedWebConfig(setOf(TRUSTED), emptySet(), "1.0.0"), trustedHost(), NoTrustedCallbacks)
            }
        assertTrue("container refuses an untrusted origin", !controller.load("$FOREIGN/x"))
        onMain { controller.destroy() }
    }

    @Test
    fun externalPageHasNoBridgeAndBlocksPlatformNavigation() {
        assumeTrue("system WebView supports multi-profile (cookie isolation)", ExternalWebViewController.cookieIsolationSupported())
        val webView = newWebView()
        val events: MutableList<ClientEvent> = Collections.synchronizedList(mutableListOf())
        val callbacks = RecordingExternalCallbacks()
        val controller =
            onMain {
                ExternalWebViewController(
                    webView,
                    ExternalNavPolicy(PATTERNS),
                    linkPatterns = { PATTERNS },
                    flags = { ExternalNavFlags(consented = true, productIntercept = true) },
                    track = { events += it },
                    callbacks = callbacks,
                    appVersion = "1.0.0",
                )
            }
        assertTrue(controller.available)
        onMain { webView.loadDataWithBaseURL("$THIRD_PARTY/page", asset("external-page.html"), "text/html", "utf-8", null) }
        val page = waitFor(webView, "external page reported") { it.containsKey("bridge") }
        assertEquals("no bridge object in a third-party page", "undefined", page.getValue("bridge").jsonPrimitive.content)
        assertEquals("no transport channel either", "undefined", page.getValue("channel").jsonPrimitive.content)

        // 子框架去平台域：文档请求在发出前被拦
        onMain { webView.evaluateJavascript("frameTo('https://shop.taobao.example.test/frame')", null) }
        WebWait.latch("platform document in a subframe blocked", callbacks.blocked)
        assertEquals(NavFrame.SUBFRAME, callbacks.frames.first())

        // 主框架去平台域：取消导航、原页面不动
        callbacks.reset()
        onMain { webView.evaluateJavascript("go('https://taobao.example.test/help')", null) }
        WebWait.latch("main-frame platform navigation blocked", callbacks.blocked)
        assertEquals(NavFrame.MAIN, callbacks.frames.first())
        // 负向观察窗（不是同步等待：只可能让「没跳走」的核对变弱，不会造成偶发失败），保持原 200 毫秒
        Thread.sleep(NEGATIVE_WINDOW_MS)
        assertEquals("original page stays", "\"$THIRD_PARTY/page\"", evaluate(webView, "location.href"))

        // 商品页交识别：意图带去掉片段的 URL 与命中平台（APP-04 三端共性第 8 条）
        onMain { webView.evaluateJavascript("go('https://item.taobao.example.test/item?id=1#sku')", null) }
        WebWait.latch("product page handed to native recognition", callbacks.product)
        assertEquals("https://item.taobao.example.test/item?id=1" to "taobao", callbacks.productIntent)

        // intent:// 取消并上报（只记类别）
        val before = events.size
        onMain { webView.evaluateJavascript("go('intent://item#Intent;scheme=example;end')", null) }
        // 原为最多 10 秒的轮询（到时不判失败，靠下一行 first 抛错）：改为等容器交回上报
        WebWait.until("intent:// navigation reported") { events.drop(before).any { it.name == "external_page_blocked_nav" } }
        val reported = events.drop(before).first { it.name == "external_page_blocked_nav" }
        assertEquals("intent", reported.fields.getValue("kind").content)
        assertTrue("platform hit tracked as external_page_union_host", events.any { it.name == "external_page_union_host" })
        onMain { controller.destroy() }
    }

    /** 第 20 条：新窗口请求（window.open、target=_blank）保留 NEW_WINDOW 类型交统一判定，埋点能区分出新窗口；不另开窗口。 */
    @Test
    fun externalNewWindowRequestsKeepTheirTypeAndAreDecidedByThePolicy() {
        assumeTrue(ExternalWebViewController.cookieIsolationSupported())
        val webView = newWebView()
        val events: MutableList<ClientEvent> = Collections.synchronizedList(mutableListOf())
        val callbacks = RecordingExternalCallbacks()
        val controller =
            onMain {
                ExternalWebViewController(
                    webView,
                    ExternalNavPolicy(PATTERNS),
                    linkPatterns = { PATTERNS },
                    flags = { ExternalNavFlags(consented = true, productIntercept = true) },
                    track = { events += it },
                    callbacks = callbacks,
                    appVersion = "1.0.0",
                )
            }
        // 测试里用脚本代替用户点击发起新窗口（容器默认只让用户手势打开新窗口）
        onMain { webView.settings.javaScriptCanOpenWindowsAutomatically = true }
        onMain { webView.loadDataWithBaseURL("$THIRD_PARTY/page", asset("external-page.html"), "text/html", "utf-8", null) }
        waitFor(webView, "external page reported") { it.containsKey("bridge") }

        onMain { webView.evaluateJavascript("window.open('https://shop.taobao.example.test/cart')", null) }
        WebWait.latch("platform new window blocked", callbacks.blocked)
        assertEquals(NavFrame.NEW_WINDOW, callbacks.frames.first())
        val tracked = events.first { it.name == "external_page_union_host" }
        assertEquals("new_window", tracked.fields.getValue("frame").content)
        assertEquals("original page stays", "\"$THIRD_PARTY/page\"", evaluate(webView, "location.href"))

        onMain { webView.evaluateJavascript("window.open('https://item.taobao.example.test/item?id=2#x')", null) }
        WebWait.latch("product new window handed to native recognition", callbacks.product)
        assertEquals("https://item.taobao.example.test/item?id=2" to "taobao", callbacks.productIntent)
        onMain { controller.destroy() }
    }

    @Test
    fun externalContainerRefusesToOpenWithoutRules() {
        assumeTrue(ExternalWebViewController.cookieIsolationSupported())
        val webView = newWebView()
        val callbacks = RecordingExternalCallbacks()
        val empty = JsonObject(mapOf("version" to JsonPrimitive("empty"), "rules" to kotlinx.serialization.json.JsonArray(emptyList())))
        onMain {
            ExternalWebViewController(
                webView,
                ExternalNavPolicy(empty),
                linkPatterns = { empty },
                flags = { ExternalNavFlags(consented = true, productIntercept = true) },
                track = {},
                callbacks = callbacks,
                appVersion = "1.0.0",
            ).open("https://example.com/")
        }
        WebWait.latch("no third-party page opens in the app without rules", callbacks.unavailable)
    }

    /** APP-04 三端共性第 7、12 条：授权页（初始地址）被拦下、不加载；用户触发的文档导航按采样率上报 external_page_nav_host。 */
    @Test
    fun externalContainerBlocksAuthPagesAndSamplesNavHost() {
        assumeTrue(ExternalWebViewController.cookieIsolationSupported())
        val webView = newWebView()
        val events: MutableList<ClientEvent> = Collections.synchronizedList(mutableListOf())
        val callbacks = RecordingExternalCallbacks()
        onMain {
            ExternalWebViewController(
                webView,
                ExternalNavPolicy(PATTERNS),
                linkPatterns = { PATTERNS },
                flags = { ExternalNavFlags(consented = true, productIntercept = true, navHostSampleBp = FULL_SAMPLE_BP) },
                track = { events += it },
                callbacks = callbacks,
                appVersion = "1.0.0",
                roll = { 0 },
            ).open("https://appleid.apple.com/auth/authorize?client_id=x")
        }
        WebWait.latch("auth page blocked at the initial URL", callbacks.blocked)
        assertEquals(NavFrame.INITIAL, callbacks.frames.first())
        // 负向观察窗（同上），保持原 200 毫秒
        Thread.sleep(NEGATIVE_WINDOW_MS)
        assertTrue("auth page never loaded", onMain { webView.url }?.contains("apple.com") != true)
        val sample = events.single { it.name == "external_page_nav_host" }
        assertEquals("apple.com", sample.fields.getValue("registered_domain").content)
        assertEquals("initial", sample.fields.getValue("frame").content)
        assertEquals("no path or query is recorded", setOf("registered_domain", "frame", "hit"), sample.fields.keys)
    }

    private class RecordingExternalCallbacks : ExternalWebCallbacks {
        @Volatile
        var blocked = CountDownLatch(1)
        val product = CountDownLatch(1)

        @Volatile
        var productIntent: Pair<String, String?>? = null
        val unavailable = CountDownLatch(1)
        val frames: MutableList<NavFrame> = Collections.synchronizedList(mutableListOf())

        fun reset() {
            blocked = CountDownLatch(1)
            frames.clear()
        }

        override fun onBlocked(
            decision: ExternalNavDecision,
            frame: NavFrame,
        ) {
            assertEquals(ExternalNavAction.BLOCK, decision.actionRaw)
            frames += frame
            blocked.countDown()
        }

        override fun onProductIntent(
            url: String,
            platform: String?,
        ) {
            productIntent = url to platform
            product.countDown()
        }

        override fun onMainFrameChanged(
            host: String,
            canOpenInBrowser: Boolean,
        ) = Unit

        override fun onLoadFailed() = Unit

        override fun onDownloadBlocked() = Unit

        override fun onUnavailable() = unavailable.countDown()
    }

    /**
     * 测试替身：把容器自己的 WebViewClient 包一层，只为「地址与主框架相同的子框架」提供内容（不连网络），其余回调原样转给容器。
     */
    private class SameUrlFrameServer(
        private val inner: android.webkit.WebViewClient,
        private val url: String,
        private val html: String,
    ) : android.webkit.WebViewClient() {
        /** 容器自己在发出前取消的子框架请求。 */
        val cancelled: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** 容器放行的子框架请求。 */
        val passed: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** 由测试替身提供了内容的请求（容器放行之后）。 */
        val served: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun shouldInterceptRequest(
            view: WebView,
            request: android.webkit.WebResourceRequest,
        ): android.webkit.WebResourceResponse? {
            // 先交给容器判定：容器取消的，原样返回它的空响应
            val decided = inner.shouldInterceptRequest(view, request)
            val target = request.url.toString()
            if (!request.isForMainFrame) (if (decided != null) cancelled else passed) += target
            if (decided != null) return decided
            return if (!request.isForMainFrame && target == url) {
                served += target
                android.webkit.WebResourceResponse("text/html", "utf-8", html.byteInputStream())
            } else {
                null
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: android.webkit.WebResourceRequest,
        ): Boolean = inner.shouldOverrideUrlLoading(view, request)

        override fun onPageStarted(
            view: WebView,
            url: String?,
            favicon: android.graphics.Bitmap?,
        ) = inner.onPageStarted(view, url, favicon)
    }

    private object NoTrustedCallbacks : TrustedWebCallbacks {
        override fun onOpenExternal(url: String) = Unit

        override fun onLoadFailed() = Unit
    }

    private object NoUi : BridgeUi {
        override fun toast(
            text: String,
            long: Boolean,
        ) = Unit

        override fun showLoading(text: String?) = Unit

        override fun hideLoading() = Unit

        override fun setNavBar(params: JsonObject) = true

        override fun close() = Unit
    }

    private object OkServices : BridgeServices {
        override fun env() = JsonObject(mapOf("platform" to JsonPrimitive("android")))

        override fun config(keys: List<String>) = BridgeOutcome.Ok()

        override suspend fun user(validity: BridgeCallValidity) = BridgeOutcome.Ok()

        override suspend fun h5Token(
            readOnly: Boolean,
            validity: BridgeCallValidity,
        ) = BridgeOutcome.Ok()

        override suspend fun login(update: String) = BridgeOutcome.Ok()

        override suspend fun signedRequest(
            method: String,
            path: String,
            body: JsonElement?,
            validity: BridgeCallValidity,
        ) = BridgeOutcome.Ok()

        override suspend fun navOpen(
            route: String,
            params: JsonObject,
            update: String,
        ) = BridgeOutcome.Ok()

        override fun clipboardWrite(text: String) = BridgeOutcome.Ok()

        override suspend fun clipboardRead() = BridgeOutcome.Ok()

        override fun setClipboardAutoDetect(enabled: Boolean) = BridgeOutcome.Ok()

        override suspend fun pushStatus() = BridgeOutcome.Ok()

        override suspend fun requestPermission(
            type: String,
            validity: BridgeCallValidity,
        ) = BridgeOutcome.Ok()

        override fun track(
            name: String,
            props: JsonObject,
        ) = BridgeOutcome.Ok()

        override fun reportRejected(
            method: String,
            code: Int,
        ) = Unit
    }

    private companion object {
        const val TRUSTED = "https://h5.example.test"
        const val FOREIGN = "https://foreign.example.test"
        const val THIRD_PARTY = "https://page.example.test"
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val NEGATIVE_WINDOW_MS = 200L
        const val RESULTS_SCRIPT = "JSON.stringify(window.results || null)"
        const val BRIDGE_GESTURE_MISSING = 90404
        const val BRIDGE_INVALID_PARAMS = 90002
        const val BRIDGE_NOT_LOGGED_IN = 90401

        /** 同一个 id 连发两次：第一次 auth.getUser（L1，游客 90401），第二次在第一次还在途时发出。 */
        const val DUPLICATE_ID_SCRIPT =
            "(function(){var b=window.__REBATE_BRIDGE__,r=window.results;" +
                "b.postMessage({v:1,id:'dup',method:'auth.getUser',params:{}}).then(function(x){r.dupFirst=x.code;});" +
                "b.postMessage({v:1,id:'dup',method:'app.getEnv',params:{}}).then(function(x){r.dupSecond=x.code;});})()"

        /** 可信页里的三个子框架：同源另一路径、授权页、跨源普通页。 */
        val FRAMES_PAGE: String =
            """
            <!doctype html><html><body>
            <iframe src="https://h5.example.test/other"></iframe>
            <iframe src="https://appleid.apple.com/auth/authorize?client_id=x"></iframe>
            <iframe src="https://foreign.example.test/embed"></iframe>
            </body></html>
            """.trimIndent()
        const val FULL_SAMPLE_BP = 10_000

        /** 同地址子框架的内容：调一次桥，把结果与自己的地址报给主框架。 */
        val SAME_URL_FRAME: String =
            """
            <!doctype html><html><body><script>
              (function () {
                var href = location.href;
                var bridge = window.__REBATE_BRIDGE__;
                if (!bridge) { parent.postMessage({ type: 'sameurl', outcome: 'absent', href: href }, '*'); return; }
                bridge.postMessage({ v: 1, id: 'same-1', method: 'app.getEnv', params: {} }).then(function (r) {
                  parent.postMessage({ type: 'sameurl', outcome: r.code, href: href }, '*');
                });
              })();
            </script></body></html>
            """.trimIndent()

        const val LOG_TAG = "WebContainersTest"

        val PATTERNS: JsonObject =
            Json
                .parseToJsonElement(
                    """
                    {"version":"instrumented","rules":[
                      {"platform":"taobao","category":"union_host","hosts":["taobao.example.test"],"path_patterns":[]},
                      {"platform":"taobao","category":"product","hosts":["item.taobao.example.test"],"path_patterns":["^/item$"]}
                    ]}
                    """.trimIndent(),
                ).jsonObject
    }
}
