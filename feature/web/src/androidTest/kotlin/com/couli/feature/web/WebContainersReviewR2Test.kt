package com.couli.feature.web

import android.graphics.Bitmap
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
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android APP-04 第 2 轮评审与编排者裁定在真实系统 WebView 上的复现：
 * - 子框架裁定（03 §5.1 保守降级）：ExternalWebView 里任意子框架文档（同源、跨源、非平台）都取消加载，不提示、不给「去搜索」；
 *   命中平台规则的仍按第 31 条提示；主框架照常加载与导航；
 * - R2-04 / 48：pushState、replaceState、片段导航与历史返回之后，浏览器出口与标题栏的「当前地址」跟着变，点击时按当前地址再判；
 * - R2-06 / 49：只命中 promo 而放行的主框架导航，external_page_nav_host 的 hit 为 true；
 * - R2-07 / 50：id 为 __proto__ 的在途调用：第二条同 id 本地 90002、不发送，第一条照常收到原生结果。
 * 测试页用 loadDataWithBaseURL 给出 https 源；放行的请求由测试替身在本机给内容（不连任何网络）。
 */
@RunWith(AndroidJUnit4::class)
class WebContainersReviewR2Test {
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

    /** 页面已交回结论之后读一次值。 */
    private fun evaluate(
        webView: WebView,
        script: String,
    ): String = probe(webView).read(script)

    /** 动作脚本（导航、改 DOM、发桥消息）：不等回调，结果由随后的等待确认。 */
    private fun fire(
        webView: WebView,
        script: String,
    ) = probe(webView).fire(script)

    /** F-AND-31：等页面交回「脚本已执行、已是 [url] 这份文档」（探测不挂在导航上）。 */
    private fun awaitPage(
        webView: WebView,
        url: String,
        script: String = "window.ready === true && location.href",
    ) = probe(webView).await("page $url ready", script) { it == "\"$url\"" }

    private fun load(
        webView: WebView,
        url: String,
        html: String,
    ) {
        onMain { webView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url) }
        awaitPage(webView, url)
    }

    private fun external(
        webView: WebView,
        callbacks: Recorder,
        events: MutableList<ClientEvent> = Collections.synchronizedList(mutableListOf()),
    ): ExternalWebViewController =
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
            )
        }

    // ---------- 编排者裁定：ExternalWebView 子框架文档一律取消 ----------

    @Test
    fun everySubframeDocumentIsCancelledSilentlyWhileTheMainFrameLoads() {
        assumeTrue(ExternalWebViewController.cookieIsolationSupported())
        val webView = newWebView()
        val callbacks = Recorder()
        val controller = external(webView, callbacks)
        val server = onMain { LocalServer(webView.webViewClient).also { webView.webViewClient = it } }
        load(webView, "$THIRD_PARTY/page", FRAMES_HOST)

        val frames =
            listOf(
                "$THIRD_PARTY/same-origin-frame",
                "https://embed.other.test/frame",
                "https://redirector.other.test/hop?to=item",
            )
        frames.forEach { fire(webView, "frameTo('$it')") }
        WebWait.until("all subframe documents decided", detail = { "cancelled=${server.cancelled} served=${server.served}" }) {
            frames.all { it in server.cancelled || it in server.served }
        }
        // 负向观察窗（不是同步等待：只可能让「没加载 / 没提示」的核对变弱，不会造成偶发失败），保持原 1 秒
        Thread.sleep(NEGATIVE_WINDOW_MS)
        assertEquals("no subframe document is loaded", emptyList<String>(), frames.filter { it in server.served })
        assertEquals("the same-origin frame has no content", "\"\"", evaluate(webView, "frameText(0)"))
        assertTrue("cancelled non-platform subframes give no notice", callbacks.frames.isEmpty())

        // 命中平台规则的子框架：同样取消，并按第 31 条提示（子框架层）
        fire(webView, "frameTo('https://shop.taobao.example.test/frame')")
        WebWait.latch("platform subframe noticed", callbacks.blocked)
        assertEquals(listOf(NavFrame.SUBFRAME), callbacks.frames.toList())

        // 主框架照常：页面还在，主框架导航到普通页照常加载
        assertEquals("\"$THIRD_PARTY/page\"", evaluate(webView, "location.href"))
        fire(webView, "location.href = '$THIRD_PARTY/next'")
        awaitPage(webView, "$THIRD_PARTY/next", script = "location.href")
        assertTrue("$THIRD_PARTY/next" in server.served)
        onMain { controller.destroy() }
    }

    // ---------- R2-04 / 共性第 48 条 ----------

    @Test
    fun theBrowserExitFollowsSinglePageNavigations() {
        assumeTrue(ExternalWebViewController.cookieIsolationSupported())
        val webView = newWebView()
        val callbacks = Recorder()
        val controller = external(webView, callbacks)
        // 页面经真实的 https 请求载入（本机替身给内容），不用 loadDataWithBaseURL：后者的同文档跳转在 doUpdateVisitedHistory 里
        // 报的是 data: 地址，不是生产里第三方页面的情形
        onMain {
            webView.webViewClient = LocalServer(webView.webViewClient)
            controller.open("$THIRD_PARTY/help")
        }
        awaitPage(webView, "$THIRD_PARTY/help")
        WebWait.until("exit offered on /help") { callbacks.exit == true }
        assertEquals("$THIRD_PARTY/help", onMain { controller.browserTarget() })

        // pushState 到命中 promo 的同源地址：出口立即撤下，点击也不交出
        fire(webView, "history.pushState({}, '', '/promo/offer')")
        WebWait.until("exit withdrawn after pushState") { callbacks.exit == false }
        assertNull("no exit on a promo page", onMain { controller.browserTarget() })

        // 历史返回（同文档）：回到 /help，出口恢复，交出的是 /help
        fire(webView, "history.back()")
        WebWait.until("exit back after history.back") { callbacks.exit == true }
        assertEquals("$THIRD_PARTY/help", onMain { controller.browserTarget() })

        // replaceState 到 promo：撤下；片段导航回普通地址：恢复，交出的是带片段的当前地址
        fire(webView, "history.replaceState({}, '', '/promo/x')")
        WebWait.until("exit withdrawn after replaceState") { callbacks.exit == false }
        assertNull(onMain { controller.browserTarget() })
        fire(webView, "history.replaceState({}, '', '/faq')")
        WebWait.until("exit back after replaceState to a plain page") { callbacks.exit == true }
        assertEquals("$THIRD_PARTY/faq", onMain { controller.browserTarget() })
        fire(webView, "location.hash = 'q1'")
        WebWait.until("fragment navigation followed") { onMain { controller.browserTarget() } == "$THIRD_PARTY/faq#q1" }
        assertEquals(true, callbacks.exit)
        assertEquals("$THIRD_PARTY/faq#q1", onMain { controller.browserTarget() })
        onMain { controller.destroy() }
    }

    // ---------- R2-06 / 共性第 49 条 ----------

    @Test
    fun aPromoOnlyNavigationIsSampledAsAHit() {
        assumeTrue(ExternalWebViewController.cookieIsolationSupported())
        val webView = newWebView()
        val events: MutableList<ClientEvent> = Collections.synchronizedList(mutableListOf())
        val controller = external(webView, Recorder(), events)
        onMain { webView.webViewClient = LocalServer(webView.webViewClient) }
        load(webView, "https://brand.other.test/home", PLAIN_PAGE)

        // 先到一个不命中任何规则的普通页（hit=false），再从那里到只命中 promo 的地址
        fire(webView, "location.href = '$PLAIN_NEXT'")
        awaitPage(webView, PLAIN_NEXT)
        fire(webView, "location.href = '$THIRD_PARTY/promo/deal'")
        WebWait.until("promo navigation sampled", detail = { "hits=${hits(events)}" }) { hits(events).any { it.first == "example.test" } }
        assertEquals(
            "promo is loaded but still a hit",
            listOf("example.test" to "true"),
            hits(events).filter { it.first == "example.test" },
        )
        assertEquals("a plain page is not a hit", listOf("other.test" to "false"), hits(events).filter { it.first == "other.test" })
        onMain { controller.destroy() }
    }

    private fun hits(events: List<ClientEvent>): List<Pair<String, String>> =
        synchronized(events) { events.toList() }
            .filter { it.name == "external_page_nav_host" }
            .map { it.fields.getValue("registered_domain").content to it.fields.getValue("hit").content }

    // ---------- R2-07 / 共性第 50 条 ----------

    @Test
    fun aProtoIdStaysInFlightAndADuplicateIsAnsweredLocally() {
        assumeTrue("system WebView supports the transport", BridgeTransport.isSupported())
        val webView = newWebView()
        val release = CompletableDeferred<Unit>()
        val services = HeldUser(release)
        val host =
            BridgeHost(
                protocol = { BridgeProtocol(setOf(TRUSTED), JsonObject(emptyMap()), emptySet()) },
                caller = { BridgeCallerState(BridgePrivacy.CONSENTED, accountKey = "account-a", update = BridgeUpdateState.SUPPORTED) },
                services = services,
                ui = NoUi,
                awaitUpdate = { BridgeUpdateState.SUPPORTED },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
        val controller =
            onMain { TrustedWebViewController(webView, TrustedWebConfig(setOf(TRUSTED), emptySet(), "1.0.0"), host, NoTrusted) }
        onMain { webView.loadDataWithBaseURL("$TRUSTED/page", PLAIN_PAGE, "text/html", "utf-8", null) }
        probe(webView).await("bridge ready", "window.ready === true && !!window.__REBATE_BRIDGE__") { it == "true" }

        fire(webView, PROTO_CALLS)
        probe(webView).await("second call answered", "String(window.out && window.out.second)") { it != "\"undefined\"" }
        // 第一条调用到达原生是异步的（第二条在页面里本地应答，可能先于它）：等它到达再核对「只有一条」
        WebWait.until("first call reached native") { services.calls.get() >= 1 }
        assertEquals("duplicate answered locally with 90002", "\"90002\"", evaluate(webView, "String(window.out.second)"))
        assertEquals("the first call is still pending", "\"undefined\"", evaluate(webView, "String(window.out.first)"))
        assertEquals("only one message reached native", 1, services.calls.get())

        release.complete(Unit)
        probe(webView).await("first call settled", "String(window.out.first)") { it != "\"undefined\"" }
        assertEquals("the first call got the native result", "\"0\"", evaluate(webView, "String(window.out.first)"))
        assertEquals(1, services.calls.get())
        onMain { controller.destroy() }
    }

    // ---------- 替身 ----------

    /** 包住容器自己的 WebViewClient：容器放行的请求由这里在本机给内容，取消的原样交回；记下子框架文档的去向。 */
    private class LocalServer(
        private val inner: WebViewClient,
    ) : WebViewClient() {
        val cancelled: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val served: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url.toString()
            val decided = inner.shouldInterceptRequest(view, request)
            if (decided != null) {
                cancelled += url
                return decided
            }
            served += url
            return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(SERVED_PAGE.toByteArray()))
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = inner.shouldOverrideUrlLoading(view, request)

        override fun onPageStarted(
            view: WebView,
            url: String?,
            favicon: Bitmap?,
        ) = inner.onPageStarted(view, url, favicon)

        override fun doUpdateVisitedHistory(
            view: WebView,
            url: String?,
            isReload: Boolean,
        ) = inner.doUpdateVisitedHistory(view, url, isReload)
    }

    private class Recorder : ExternalWebCallbacks {
        val blocked = CountDownLatch(1)
        val frames: MutableList<NavFrame> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var exit: Boolean? = null

        override fun onBlocked(
            decision: ExternalNavDecision,
            frame: NavFrame,
        ) {
            frames += frame
            blocked.countDown()
        }

        override fun onProductIntent(
            url: String,
            platform: String?,
        ) = Unit

        override fun onMainFrameChanged(
            host: String,
            canOpenInBrowser: Boolean,
        ) {
            exit = canOpenInBrowser
        }

        override fun onLoadFailed() = Unit

        override fun onDownloadBlocked() = Unit

        override fun onUnavailable() = Unit
    }

    private object NoTrusted : TrustedWebCallbacks {
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

    /** auth.getUser 等到 [release] 才成功；记下原生收到几次。 */
    private class HeldUser(
        private val release: CompletableDeferred<Unit>,
    ) : BridgeServices {
        val calls = AtomicInteger()

        override suspend fun user(validity: BridgeCallValidity): BridgeOutcome {
            calls.incrementAndGet()
            release.await()
            return BridgeOutcome.Ok()
        }

        override fun env() = JsonObject(emptyMap())

        override fun config(keys: List<String>) = BridgeOutcome.Ok()

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
        const val PLAIN_NEXT = "https://shop.other.test/next"
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val NEGATIVE_WINDOW_MS = 1_000L
        const val FULL_SAMPLE_BP = 10_000

        const val PLAIN_PAGE = "<!doctype html><html><body><p>plain</p><script>window.ready = true;</script></body></html>"

        const val SERVED_PAGE = "<!doctype html><html><body><p>LOADED</p><script>window.ready = true;</script></body></html>"

        const val FRAMES_HOST =
            "<!doctype html><html><body><p>host</p><script>" +
                "window.frameTo = function (u) { var f = document.createElement('iframe'); f.src = u; document.body.appendChild(f); };" +
                "window.frameText = function (i) { var d = document.getElementsByTagName('iframe')[i].contentDocument;" +
                " return d && d.body ? d.body.textContent : ''; };" +
                "window.ready = true;</script></body></html>"

        /** 两次 id 为 __proto__ 的调用：第一次在途时发第二次。 */
        const val PROTO_CALLS =
            "(function(){var b=window.__REBATE_BRIDGE__;window.out={};" +
                "b.postMessage({v:1,id:'__proto__',method:'auth.getUser',params:{}}).then(function(x){window.out.first=x.code;});" +
                "b.postMessage({v:1,id:'__proto__',method:'auth.getUser',params:{}}).then(function(x){window.out.second=x.code;});" +
                "return true;})()"

        val PATTERNS: JsonObject =
            Json
                .parseToJsonElement(
                    """
                    {"version":"instrumented-r2","rules":[
                      {"platform":"taobao","category":"union_host","hosts":["taobao.example.test"],"path_patterns":[]},
                      {"platform":"taobao","category":"promo","hosts":["page.example.test"],"path_patterns":["^/promo/"]}
                    ]}
                    """.trimIndent(),
                ).jsonObject
    }
}
