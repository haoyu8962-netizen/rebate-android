package com.couli.feature.web

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.bridge.BridgeCallValidity
import com.couli.core.bridge.BridgeCallerState
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.BridgeOutcome
import com.couli.core.bridge.BridgePrivacy
import com.couli.core.bridge.BridgeProtocol
import com.couli.core.bridge.BridgeServices
import com.couli.core.bridge.BridgeUi
import com.couli.core.bridge.BridgeUpdateState
import com.couli.core.bridge.ExternalNavDecision
import com.couli.core.bridge.ExternalNavPolicy
import com.couli.core.clientcore.ClientEvent
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android APP-04 第 1 轮评审（R1-03、R1-06、R1-07）与三端共性第 27、29、31、35、37 条在真实系统 WebView 上的复现：
 * - R1-07 / 37：主框架表单 POST 去平台域（第三方页）或不可信源、编码过的授权页（可信页）被拦时，原页面的 DOM、表单内容、地址与
 *   历史长度都不变（204 不提交新文档；不再用空白 403 文档冒充取消）；
 * - R1-03 / 35：百分号编码的授权路径在两类容器的初始地址、主框架、子框架、新窗口都被隔离；
 * - R1-06 / 32：脚本发起的主框架跳转与 iframe.src 自动加载的子框架都进入 external_page_nav_host 抽样，同一次导航只报一次；
 * - 27：规则表在运行中变空，导航被取消并显示降级页，原页面不动；
 * - 29：可信页主框架一开始导航（还没提交、导航很慢）旧页面的在途调用就已失效，并以 90500 回给仍在的旧页面。
 * 测试页都用 loadDataWithBaseURL 给出 https 源（不连任何网络）。
 */
@RunWith(AndroidJUnit4::class)
@Suppress("LargeClass") // 每条评审项一个真实 WebView 场景
class WebContainersReviewR1Test {
    private lateinit var scenario: ActivityScenario<WebTestHostActivity>

    @Before
    fun launch() {
        scenario = ActivityScenario.launch(WebTestHostActivity::class.java)
    }

    @After
    fun close() {
        if (::scenario.isInitialized) scenario.close()
    }

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

    /** 页面已稳定（没有在途导航）时在主线程读一次值（测试代码读取页面状态用；容器自己从不这样做）。返回 JSON 编码的值。 */
    private fun evaluate(
        webView: WebView,
        script: String,
    ): String = probe(webView).read(script)

    private fun load(
        webView: WebView,
        url: String,
        html: String,
    ) {
        // 旧文档的 ready 先清掉：等到的一定是新提交的文档（同地址重新加载时不会误判）。
        // 这里用 read 等回调而不是 fire：页面此时稳定，确认清标记先于下面的加载执行，不会落到新文档上
        evaluate(webView, "window.ready = false; true")
        onMain { webView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url) }
        // F-AND-31：等页面交回「新文档的脚本已执行」；没就绪时回答带上地址与 readyState，超时信息里可见
        probe(webView).await("page $url ready", READY_OR_STATE) { it == "\"ready\"" }
    }

    @Suppress("LongParameterList")
    private fun external(
        webView: WebView,
        callbacks: Recorder,
        events: MutableList<ClientEvent> = Collections.synchronizedList(mutableListOf()),
        policy: ExternalNavPolicy = ExternalNavPolicy(PATTERNS),
        patterns: () -> JsonObject? = { PATTERNS },
        bp: Int = 0,
    ): ExternalWebViewController =
        onMain {
            ExternalWebViewController(
                webView,
                policy,
                linkPatterns = patterns,
                flags = { ExternalNavFlags(consented = true, productIntercept = true, navHostSampleBp = bp) },
                track = { events += it },
                callbacks = callbacks,
                appVersion = "1.0.0",
                roll = { 0 },
            )
        }

    // ---------- R1-07 / 共性第 37 条 ----------

    @Test
    fun externalMainFramePostToAPlatformIsCancelledAndTheOriginalPageStays() {
        assumeTrue(ExternalWebViewController.cookieIsolationSupported())
        val webView = newWebView()
        val callbacks = Recorder()
        val controller = external(webView, callbacks)
        load(webView, "$THIRD_PARTY/form", formPage("https://taobao.example.test/cart"))
        val historyBefore = evaluate(webView, "history.length")

        probe(webView).fire("document.getElementById('q').value = 'typed'; document.getElementById('f').submit()")

        WebWait.latch("platform POST blocked", callbacks.blocked)
        assertEquals(NavFrame.MAIN, callbacks.frames.first())
        // 负向观察窗（不是同步等待：只可能让核对变弱，不会造成偶发失败），保持原 1 秒：核对原页面没被替换
        Thread.sleep(NEGATIVE_WINDOW_MS)
        assertOriginalPageKept(webView, "$THIRD_PARTY/form", historyBefore)
        onMain { controller.destroy() }
    }

    @Test
    fun trustedMainFramePostToAnUntrustedOriginOrAnEncodedAuthPageIsCancelledAndThePageStays() {
        val webView = newWebView()
        val opened = Collections.synchronizedList(mutableListOf<String>())
        val controller =
            onMain {
                TrustedWebViewController(webView, TrustedWebConfig(setOf(TRUSTED), emptySet(), "1.0.0"), null, Opener(opened))
            }
        val recorder = onMain { MainFrameRecorder(webView.webViewClient).also { webView.webViewClient = it } }
        listOf("https://taobao.example.test/cart", "$TRUSTED/%6fauth2/%61uthorize").forEach { action ->
            load(webView, "$TRUSTED/form", formPage(action))
            val historyBefore = evaluate(webView, "history.length")
            probe(webView).fire("document.getElementById('q').value = 'typed'; document.getElementById('f').submit()")
            WebWait.until("POST to $action reached the container", detail = { "decided=${recorder.decided.toList()}" }) {
                recorder.decided.any { it.first == action }
            }
            assertEquals("POST to $action not sent: answered 204", 204, recorder.decided.first { it.first == action }.second)
            // 负向观察窗（不是同步等待：只可能让核对变弱，不会造成偶发失败），保持原 1 秒：核对原页面没被替换
            Thread.sleep(NEGATIVE_WINDOW_MS)
            assertOriginalPageKept(webView, "$TRUSTED/form", historyBefore)
        }
        assertTrue("a POST is never handed to ExternalWebView", opened.isEmpty())
        onMain { controller.destroy() }
    }

    private fun assertOriginalPageKept(
        webView: WebView,
        url: String,
        historyBefore: String,
    ) {
        assertEquals("address unchanged", "\"$url\"", evaluate(webView, "location.href"))
        assertEquals("original DOM still there", "\"original\"", evaluate(webView, "document.getElementById('marker').textContent"))
        assertEquals("form content kept", "\"typed\"", evaluate(webView, "document.getElementById('q').value"))
        assertEquals("no new history entry", historyBefore, evaluate(webView, "history.length"))
    }

    // ---------- R1-03 / 共性第 35 条 ----------

    @Test
    fun encodedAuthPathsAreIsolatedInTheExternalContainerAtEveryEntry() {
        assumeTrue(ExternalWebViewController.cookieIsolationSupported())
        // 初始地址
        val first = newWebView()
        val initial = Recorder()
        external(first, initial).let { controller -> onMain { controller.open(ENCODED_AUTH) } }
        WebWait.latch("encoded auth page refused at the initial URL", initial.blocked)
        assertEquals(NavFrame.INITIAL, initial.frames.first())
        assertTrue("never loaded", onMain { first.url }?.contains("login.example.test") != true)

        // 主框架、子框架、新窗口
        val webView = newWebView()
        val callbacks = Recorder()
        val controller = external(webView, callbacks)
        onMain { webView.settings.javaScriptCanOpenWindowsAutomatically = true }
        load(webView, "$THIRD_PARTY/page", PLAIN_PAGE)
        listOf(
            "location.href = '$ENCODED_AUTH'" to NavFrame.MAIN,
            "frameTo('$ENCODED_AUTH_2')" to NavFrame.SUBFRAME,
            "window.open('$ENCODED_AUTH_3')" to NavFrame.NEW_WINDOW,
        ).forEach { (script, frame) ->
            callbacks.reset()
            probe(webView).fire(script)
            WebWait.latch("encoded auth page blocked ($frame)", callbacks.blocked)
            assertEquals(frame, callbacks.frames.first())
            assertEquals(ExternalWebViewController.REASON_AUTH_PAGE, callbacks.reasons.first())
            // 负向观察窗（不是同步等待：只可能让核对变弱，不会造成偶发失败），保持原 200 毫秒：核对原页面没跳走
            Thread.sleep(SHORT_NEGATIVE_WINDOW_MS)
            assertEquals("original page stays", "\"$THIRD_PARTY/page\"", evaluate(webView, "location.href"))
        }
        onMain { controller.destroy() }
    }

    @Test
    fun encodedAuthPathsAreIsolatedInTheTrustedContainerAtEveryEntry() {
        val webView = newWebView()
        val opened = Collections.synchronizedList(mutableListOf<String>())
        val controller =
            onMain {
                TrustedWebViewController(webView, TrustedWebConfig(setOf(TRUSTED), emptySet(), "1.0.0"), null, Opener(opened))
            }
        assertFalse("encoded auth page refused as the initial URL", controller.load("$TRUSTED/%6fauth2/%61uthorize"))

        val recorder = onMain { FrameRecorder(webView.webViewClient).also { webView.webViewClient = it } }
        load(webView, "$TRUSTED/page", framePage("https://foreign.example.test/%6Fauth2/x"))
        WebWait.until("subframe decided", detail = { "cancelled=${recorder.cancelled.toList()} passed=${recorder.passed.toList()}" }) {
            recorder.cancelled.isNotEmpty() || recorder.passed.isNotEmpty()
        }
        assertTrue("encoded auth page subframe cancelled: ${recorder.passed}", recorder.cancelled.any { "%6Fauth2" in it })

        probe(webView).fire("location.href = 'https://foreign.example.test/%6Fauth2/authorize'")
        // 负向观察窗（不是同步等待：只可能让核对变弱，不会造成偶发失败），保持原 1 秒：被拦的导航没有可等的正向回调，
        // 核对的是「没加载、没交给 ExternalWebView」
        Thread.sleep(NEGATIVE_WINDOW_MS)
        assertTrue("an auth page is neither loaded nor handed to ExternalWebView: $opened", opened.isEmpty())
        assertEquals("\"$TRUSTED/page\"", evaluate(webView, "location.href"))
        onMain { controller.destroy() }
    }

    // ---------- R1-06 / 共性第 32 条 ----------

    @Test
    fun navHostSamplingCoversScriptNavigationsAndAutoLoadedSubframesExactlyOnce() {
        assumeTrue(ExternalWebViewController.cookieIsolationSupported())
        val webView = newWebView()
        val events: MutableList<ClientEvent> = Collections.synchronizedList(mutableListOf())
        val controller = external(webView, Recorder(), events, bp = FULL_SAMPLE_BP)
        load(webView, "$THIRD_PARTY/page", PLAIN_PAGE)

        // iframe.src 自动加载（没有任何用户手势）
        probe(webView).fire("frameTo('https://embed.other.test/frame')")
        WebWait.until("subframe sampled", detail = { "samples=${samples(events)}" }) { samples(events).any { it.second == "subframe" } }
        // 脚本发起的主框架跳转（没有手势）：shouldOverrideUrlLoading 与 shouldInterceptRequest 都会看到它，只报一次
        probe(webView).fire("location.href = 'https://next.brand.test/x'")
        WebWait.until("script navigation sampled", detail = { "samples=${samples(events)}" }) {
            samples(events).any { it.first == "brand.test" }
        }
        // 负向观察窗（不是同步等待：只可能让核对变弱，不会造成偶发失败），保持原 1 秒：核对同一次导航没有多报
        Thread.sleep(NEGATIVE_WINDOW_MS)

        val all = samples(events)
        assertEquals("one sample for the auto-loaded subframe", listOf("other.test" to "subframe"), all.filter { it.first == "other.test" })
        assertEquals("one sample for the script navigation", listOf("brand.test" to "main"), all.filter { it.first == "brand.test" })
        onMain { controller.destroy() }
    }

    private fun samples(events: List<ClientEvent>): List<Pair<String, String>> =
        synchronized(events) { events.toList() }
            .filter { it.name == "external_page_nav_host" }
            .map { it.fields.getValue("registered_domain").content to it.fields.getValue("frame").content }

    // ---------- 共性第 27 条 ----------

    @Test
    fun rulesThatBecomeEmptyWhileRunningCancelTheNavigationAndShowTheDegradedPage() {
        assumeTrue(ExternalWebViewController.cookieIsolationSupported())
        val webView = newWebView()
        val callbacks = Recorder()
        val empty = JsonObject(mapOf("version" to JsonPrimitive("empty"), "rules" to JsonArray(emptyList())))
        val rulesOn = AtomicBoolean(true)
        val controller =
            external(webView, callbacks, policy = ExternalNavPolicy(empty), patterns = { if (rulesOn.get()) PATTERNS else empty })
        load(webView, "$THIRD_PARTY/page", PLAIN_PAGE)

        rulesOn.set(false)
        probe(webView).fire("location.href = 'https://brand.example.test/next'")

        WebWait.latch("degraded page shown", callbacks.unavailable)
        assertEquals("not treated as an ordinary block", 1L, callbacks.blocked.count)
        // 负向观察窗（不是同步等待：只可能让核对变弱，不会造成偶发失败），保持原 200 毫秒：核对导航没提交
        Thread.sleep(SHORT_NEGATIVE_WINDOW_MS)
        assertEquals("navigation cancelled", "\"$THIRD_PARTY/page\"", evaluate(webView, "location.href"))
        onMain { controller.destroy() }
    }

    // ---------- 共性第 29 条 ----------

    @Test
    fun trustedCallsAreInvalidatedAsSoonAsAMainFrameNavigationStarts() {
        assumeTrue(BridgeTransport.isSupported())
        val webView = newWebView()
        val validity = CompletableDeferred<BridgeCallValidity>()
        val host =
            BridgeHost(
                protocol = { BridgeProtocol(setOf(TRUSTED), JsonObject(emptyMap()), emptySet()) },
                caller = { BridgeCallerState(BridgePrivacy.CONSENTED, accountKey = "account-a", update = BridgeUpdateState.SUPPORTED) },
                services = PendingUser(validity),
                ui = NoUi,
                awaitUpdate = { BridgeUpdateState.SUPPORTED },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
        val controller =
            onMain {
                TrustedWebViewController(webView, TrustedWebConfig(setOf(TRUSTED), emptySet(), "1.0.0"), host, Opener(mutableListOf()))
            }
        val slow = onMain { SlowNavigation(webView.webViewClient, "$TRUSTED/slow").also { webView.webViewClient = it } }
        load(webView, "$TRUSTED/page", CALLER_PAGE)
        probe(webView).await("bridge injected", "typeof window.__REBATE_BRIDGE__") { it == "\"object\"" }

        probe(webView).fire("call('auth.getUser', 'user')")
        WebWait.until("call in flight") { validity.isCompleted }
        val inFlight = validity.getCompleted()
        assertTrue(inFlight.stillValid())

        probe(webView).fire("location.href = '$TRUSTED/slow'")
        WebWait.latch("navigation request reached the (slow) server", slow.arrived)
        // 新文档还没提交（服务器还没回）：旧页面的调用已经失效
        assertFalse("old call invalidated before the new document commits", inFlight.stillValid())
        slow.release.countDown()

        // 慢导航最终没有提交（204）：旧页面还在，被作废的调用以 90500 回给它
        probe(webView).await("old page got its answer", "String(window.results && window.results.user)") { it == "\"90500\"" }
        assertEquals("\"$TRUSTED/page\"", evaluate(webView, "location.href"))
        onMain { controller.destroy() }
    }

    // ---------- 替身 ----------

    private class Recorder : ExternalWebCallbacks {
        @Volatile
        var blocked = CountDownLatch(1)
        val unavailable = CountDownLatch(1)
        val frames: MutableList<NavFrame> = Collections.synchronizedList(mutableListOf())
        val reasons: MutableList<String?> = Collections.synchronizedList(mutableListOf())

        fun reset() {
            blocked = CountDownLatch(1)
            frames.clear()
            reasons.clear()
        }

        override fun onBlocked(
            decision: ExternalNavDecision,
            frame: NavFrame,
        ) {
            frames += frame
            reasons += decision.reason
            blocked.countDown()
        }

        override fun onProductIntent(
            url: String,
            platform: String?,
        ) = Unit

        override fun onMainFrameChanged(
            host: String,
            canOpenInBrowser: Boolean,
        ) = Unit

        override fun onLoadFailed() = Unit

        override fun onDownloadBlocked() = Unit

        override fun onUnavailable() = unavailable.countDown()
    }

    private class Opener(
        private val opened: MutableList<String>,
    ) : TrustedWebCallbacks {
        override fun onOpenExternal(url: String) {
            opened += url
        }

        override fun onLoadFailed() = Unit
    }

    /** 把容器自己的 WebViewClient 包一层，只记录子框架请求被容器取消还是放行（不连网络）。 */
    private class FrameRecorder(
        private val inner: WebViewClient,
    ) : WebViewClient() {
        val cancelled: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val passed: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val decided = inner.shouldInterceptRequest(view, request)
            if (request.isForMainFrame) return decided
            (if (decided != null) cancelled else passed) += request.url.toString()
            // 放行的子框架不连网络：给空内容
            return decided ?: ExternalWebViewController.noContentResponse()
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = inner.shouldOverrideUrlLoading(view, request)

        override fun onPageStarted(
            view: WebView,
            url: String?,
            favicon: android.graphics.Bitmap?,
        ) = inner.onPageStarted(view, url, favicon)
    }

    /** 记录容器对主框架请求的判定（地址 → 回应的状态码，放行为 0）。 */
    private class MainFrameRecorder(
        private val inner: WebViewClient,
    ) : WebViewClient() {
        val decided: MutableList<Pair<String, Int>> = Collections.synchronizedList(mutableListOf())

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val response = inner.shouldInterceptRequest(view, request)
            if (request.isForMainFrame) decided += request.url.toString() to (response?.statusCode ?: 0)
            return response
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = inner.shouldOverrideUrlLoading(view, request)

        override fun onPageStarted(
            view: WebView,
            url: String?,
            favicon: android.graphics.Bitmap?,
        ) = inner.onPageStarted(view, url, favicon)
    }

    /** 慢服务器替身：容器放行的 [url] 主框架请求在这里挂住，直到测试放行，最后回 204（导航不提交）。 */
    private class SlowNavigation(
        private val inner: WebViewClient,
        private val url: String,
    ) : WebViewClient() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val decided = inner.shouldInterceptRequest(view, request)
            if (decided != null || !request.isForMainFrame || request.url.toString() != url) return decided
            arrived.countDown()
            // 保险上限（测试放行前一直压着响应）；不是等待后断言
            release.await(WebWait.CEILING_MS, TimeUnit.MILLISECONDS)
            return ExternalWebViewController.noContentResponse()
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = inner.shouldOverrideUrlLoading(view, request)

        override fun onPageStarted(
            view: WebView,
            url: String?,
            favicon: android.graphics.Bitmap?,
        ) = inner.onPageStarted(view, url, favicon)
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

    /** auth.getUser 一直挂着（交出这次调用的有效性），其余直接成功。 */
    private class PendingUser(
        private val validity: CompletableDeferred<BridgeCallValidity>,
    ) : BridgeServices {
        override fun env() = JsonObject(emptyMap())

        override fun config(keys: List<String>) = BridgeOutcome.Ok()

        override suspend fun user(validity: BridgeCallValidity): BridgeOutcome {
            this.validity.complete(validity)
            CompletableDeferred<Unit>().await()
            return BridgeOutcome.Ok()
        }

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
        const val THIRD_PARTY = "https://page.example.test"
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val NEGATIVE_WINDOW_MS = 1_000L
        const val SHORT_NEGATIVE_WINDOW_MS = 200L

        /** 就绪回答 'ready'；没就绪时回答地址与 readyState（[PageProbe.await] 超时信息里的 last）。 */
        const val READY_OR_STATE = "window.ready === true ? 'ready' : location.href + ' ' + document.readyState"
        const val FULL_SAMPLE_BP = 10_000

        /** 授权路径的编码变体（%6f = o、%61 = a、%4F = O）：规范化后都是 /oauth2/authorize。 */
        const val ENCODED_AUTH = "https://login.example.test/%6fauth2/%61uthorize?x=1"
        const val ENCODED_AUTH_2 = "https://login.example.test/%4Fauth2/start"
        const val ENCODED_AUTH_3 = "https://login.example.test/a/%2e%2e/%6fauth/x"

        const val PLAIN_PAGE =
            "<!doctype html><html><body><p>plain</p><script>" +
                "window.frameTo = function (u) { var f = document.createElement('iframe'); f.src = u; document.body.appendChild(f); };" +
                "window.ready = true;</script></body></html>"

        const val CALLER_PAGE =
            "<!doctype html><html><body><script>window.results = {};" +
                "window.call = function (method, key) {" +
                " window.__REBATE_BRIDGE__.postMessage({ v: 1, id: key, method: method, params: {} })" +
                ".then(function (r) { window.results[key] = r.code; }); };" +
                "window.ready = true;</script></body></html>"

        fun formPage(action: String): String =
            "<!doctype html><html><body><div id=\"marker\">original</div>" +
                "<form id=\"f\" method=\"post\" action=\"$action\"><input id=\"q\" name=\"q\"></form>" +
                "<script>window.ready = true;</script></body></html>"

        fun framePage(src: String): String =
            "<!doctype html><html><body><iframe src=\"$src\"></iframe><script>window.ready = true;</script></body></html>"

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
