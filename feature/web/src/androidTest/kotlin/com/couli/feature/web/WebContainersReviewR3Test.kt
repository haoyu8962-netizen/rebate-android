package com.couli.feature.web

import android.os.SystemClock
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
import com.couli.core.bridge.ExternalNavDecision
import com.couli.core.bridge.ExternalNavPolicy
import com.couli.feature.web.LoopbackHttpsServer.Companion.LOCALHOST
import com.couli.feature.web.LoopbackHttpsServer.Companion.LOOPBACK
import com.couli.feature.web.LoopbackHttpsServer.Recorded
import com.couli.feature.web.LoopbackHttpsServer.Reply
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android APP-04 第 3 轮评审在真实系统 WebView 上、经**真实 HTTPS 服务器重定向**的复现（[LoopbackHttpsServer]：本机回环，
 * 测试专用 CA；`https://127.0.0.1:port` 与 `https://localhost:port` 是两个 origin）：
 * - R3-01（S0，共性第 56 条）：可信页里的跨源子框架，第一跳不是可信源，服务器 302 到可信源的独立子文档——可信容器在第一跳就取消
 *   网络子框架文档，跨源服务器与可信子文档都收不到请求，子文档没有载入，父页面的桥（auth.getH5Token）一次也没被调用；
 * - R3-02（共性第 57 条）：第三方页向允许的地址提交 POST，服务器 307（保持 POST）到平台域——外部容器在第一跳取消主框架 POST
 *   （204），平台域一个请求都没收到，原页面（地址、DOM）不变。
 */
@RunWith(AndroidJUnit4::class)
class WebContainersReviewR3Test {
    private lateinit var scenario: ActivityScenario<WebTestHostActivity>
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var server: LoopbackHttpsServer? = null

    @Before
    fun launch() {
        scenario = ActivityScenario.launch(WebTestHostActivity::class.java)
    }

    @After
    fun close() {
        server?.close()
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

    private fun evaluate(
        webView: WebView,
        script: String,
    ): String {
        val latch = CountDownLatch(1)
        var result = "null"
        onMain {
            webView.evaluateJavascript(script) { value ->
                result = value
                latch.countDown()
            }
        }
        assertTrue("evaluateJavascript timed out", latch.await(WAIT_S, TimeUnit.SECONDS))
        return result
    }

    private fun waitUntil(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + WAIT_S * MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out: $message")
    }

    private fun serve(handler: (Recorded) -> Reply): LoopbackHttpsServer =
        LoopbackHttpsServer(instrumentation.context, handler).also { server = it }

    // ---------- R3-01 ----------

    @Test
    fun aCrossOriginSubframeRedirectedIntoTheTrustedOriginNeverLoadsAndNeverReachesTheParentBridge() {
        assumeTrue("system WebView supports the transport", BridgeTransport.isSupported())
        val services = TokenServices()
        lateinit var trusted: String
        val https =
            serve { request ->
                when {
                    request.path == "/page" -> Reply(OK, html = TRUSTED_PAGE.replace("{CROSS}", "https://$LOCALHOST:${server?.port}"))

                    // 跨源子框架的第一跳：真实的 302，指向可信源的独立子文档
                    request.host == LOCALHOST && request.path == "/hop" -> Reply(FOUND, mapOf("Location" to "$trusted/child"))

                    request.path == "/child" -> Reply(OK, html = CHILD_PAGE)

                    else -> Reply(NOT_FOUND)
                }
            }
        trusted = "https://$LOOPBACK:${https.port}"
        val webView = newWebView()
        val controller =
            onMain {
                TrustedWebViewController(webView, TrustedWebConfig(setOf(trusted), emptySet(), "1.0.0"), host(trusted, services), NoTrusted)
            }
        assertTrue(controller.bridgeInstalled)

        assertTrue(onMain { controller.load("$trusted/page") })
        // 主页面确实经真实 HTTPS 从可信源载入、桥在主框架里可用（测试装置本身成立）
        waitUntil("trusted page loaded over https") { evaluate(webView, "window.ready === true") == "true" }
        assertEquals("bridge present in the trusted main frame", "true", evaluate(webView, "typeof window.__REBATE_BRIDGE__ === 'object'"))
        Thread.sleep(SETTLE_MS)

        val requests = https.requests.toList()
        android.util.Log.i(LOG_TAG, "R3-01 requests=$requests h5Token=${services.h5Tokens.get()}")
        assertEquals("the cross-origin first hop is cancelled before it is sent", emptyList<Recorded>(), https.to(LOCALHOST))
        assertTrue("the trusted child document is never requested: $requests", requests.none { it.path == "/child" })
        assertEquals("the child never ran", "false", evaluate(webView, "window.childRan === true"))
        assertEquals("the parent bridge never handed out a token", 0, services.h5Tokens.get())
        onMain { controller.destroy() }
    }

    // ---------- R3-02 ----------

    @Test
    fun anExternalMainFramePostRedirectedByA307IntoAPlatformIsCancelledAtTheFirstHop() {
        assumeTrue(ExternalWebViewController.cookieIsolationSupported())
        lateinit var allowed: String
        val https =
            serve { request ->
                when {
                    request.host == LOOPBACK && request.path == "/form" -> {
                        Reply(OK, html = FORM_PAGE.replace("{ACTION}", "$allowed/submit"))
                    }

                    // 允许的地址收到 POST：307 保持 POST 重定向进平台域（localhost 在本测试的规则表里是联盟平台域）
                    request.host == LOOPBACK && request.path == "/submit" -> {
                        Reply(TEMPORARY_REDIRECT, mapOf("Location" to "https://$LOCALHOST:${server?.port}/cart"))
                    }

                    else -> {
                        Reply(OK, html = "<!doctype html><p id=\"marker\">platform</p>")
                    }
                }
            }
        allowed = "https://$LOOPBACK:${https.port}"
        val webView = newWebView()
        val callbacks = Recorder()
        val controller =
            onMain {
                ExternalWebViewController(
                    webView,
                    ExternalNavPolicy(PLATFORM_LOCALHOST),
                    linkPatterns = { PLATFORM_LOCALHOST },
                    flags = { ExternalNavFlags(consented = true, productIntercept = true) },
                    track = {},
                    callbacks = callbacks,
                    appVersion = "1.0.0",
                )
            }
        onMain { controller.open("$allowed/form") }
        waitUntil("form page loaded over https") { evaluate(webView, "window.ready === true") == "true" }
        val historyBefore = evaluate(webView, "history.length")

        evaluate(webView, "document.getElementById('q').value = 'typed'; document.getElementById('f').submit(); true")
        Thread.sleep(SETTLE_MS)

        val requests = https.requests.toList()
        android.util.Log.i(LOG_TAG, "R3-02 requests=$requests")
        assertEquals("the platform domain receives no request at all", emptyList<Recorded>(), https.to(LOCALHOST))
        assertTrue("the POST is cancelled at its first hop (never sent): $requests", requests.none { it.method == "POST" })
        assertEquals("address unchanged", "\"$allowed/form\"", evaluate(webView, "location.href"))
        assertEquals("original DOM still there", "\"original\"", evaluate(webView, "document.getElementById('marker').textContent"))
        assertEquals("form content kept", "\"typed\"", evaluate(webView, "document.getElementById('q').value"))
        assertEquals("no new history entry", historyBefore, evaluate(webView, "history.length"))
        onMain { controller.destroy() }
    }

    // ---------- 替身 ----------

    private fun host(
        trusted: String,
        services: BridgeServices,
    ): BridgeHost =
        BridgeHost(
            protocol = { BridgeProtocol(setOf(trusted), JsonObject(emptyMap()), emptySet()) },
            caller = { BridgeCallerState(BridgePrivacy.CONSENTED, accountKey = "account-A", update = BridgeUpdateState.SUPPORTED) },
            services = services,
            ui = NoUi,
            awaitUpdate = { BridgeUpdateState.SUPPORTED },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )

    private class Recorder : ExternalWebCallbacks {
        override fun onBlocked(
            decision: ExternalNavDecision,
            frame: NavFrame,
        ) = Unit

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

    /** 记下原生收到几次 auth.getH5Token（发令牌）。 */
    private class TokenServices : BridgeServices {
        val h5Tokens = AtomicInteger()

        override suspend fun h5Token(
            readOnly: Boolean,
            validity: BridgeCallValidity,
        ): BridgeOutcome {
            h5Tokens.incrementAndGet()
            return BridgeOutcome.Ok()
        }

        override fun env() = JsonObject(emptyMap())

        override fun config(keys: List<String>) = BridgeOutcome.Ok()

        override suspend fun user(validity: BridgeCallValidity) = BridgeOutcome.Ok()

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
        const val LOG_TAG = "WebContainersR3"
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val WAIT_S = 10L
        const val MILLIS = 1000L
        const val POLL_MS = 100L
        const val SETTLE_MS = 3_000L
        const val OK = 200
        const val FOUND = 302
        const val TEMPORARY_REDIRECT = 307
        const val NOT_FOUND = 404

        /** 可信页：嵌一个跨源子框架（第一跳在 localhost，服务器再 302 进可信源）。 */
        const val TRUSTED_PAGE =
            "<!doctype html><html><body><p>trusted</p>" +
                "<iframe id=\"x\" src=\"{CROSS}/hop\"></iframe>" +
                "<script>window.addEventListener('load', function () { window.ready = true; });</script></body></html>"

        /** 被重定向进可信源的独立子文档：与父页面同源，借 parent 的桥要 H5 令牌。 */
        const val CHILD_PAGE =
            "<!doctype html><html><body><script>" +
                "parent.childRan = true;" +
                "var b = parent.__REBATE_BRIDGE__;" +
                "if (b) b.postMessage({v: 1, id: 'child-1', method: 'auth.getH5Token', params: {}})" +
                ".then(function (r) { parent.childToken = r.code; });" +
                "</script></body></html>"

        const val FORM_PAGE =
            "<!doctype html><html><body><p id=\"marker\">original</p>" +
                "<form id=\"f\" method=\"post\" action=\"{ACTION}\"><input id=\"q\" name=\"q\"></form>" +
                "<script>window.ready = true;</script></body></html>"

        /** 本测试的规则表：localhost 是联盟平台域（union_host）。 */
        val PLATFORM_LOCALHOST: JsonObject =
            Json
                .parseToJsonElement(
                    """{"version":"r3","rules":[{"platform":"taobao","category":"union_host","hosts":["localhost"],"path_patterns":[]}]}""",
                ).jsonObject
    }
}
