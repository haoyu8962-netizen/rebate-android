package com.couli.feature.web

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 鸿蒙 APP-05a 第 1 轮评审带来的共性条目，在 Android 真实系统 WebView 上自查：
 * - 第 51 条：可信容器主框架的同地址重载（`location.href = location.href`、`location.reload()`）同样是文档导航，旧页面的在途调用
 *   立即作废；只有同一文档内的片段跳转不作废；
 * - 第 53 条：关闭本容器的那次调用（nav.open 清栈）在破坏性拆除之前把回复交给页面：容器拆除（拆传输层、销毁 WebView）等这次调用
 *   结算之后才做，传输层不丢这条回复。
 * 页面内容由测试替身在本机给出（可信源 https，不连任何网络）。
 */
@RunWith(AndroidJUnit4::class)
class WebContainersLessonsTest {
    private lateinit var scenario: ActivityScenario<WebTestHostActivity>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Before
    fun launch() {
        scenario = ActivityScenario.launch(WebTestHostActivity::class.java)
    }

    @After
    fun close() {
        scope.cancel()
        if (::scenario.isInitialized) scenario.close()
    }

    private fun <T> onMain(block: (WebTestHostActivity) -> T): T {
        var value: T? = null
        scenario.onActivity { value = block(it) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun probe(webView: WebView) = PageProbe(webView) { block -> onMain { block() } }

    /** 页面已稳定（没有在途导航）时读一次值。 */
    private fun evaluate(
        webView: WebView,
        script: String,
    ): String = probe(webView).read(script)

    private fun host(
        services: BridgeServices,
        ui: BridgeUi,
    ) = BridgeHost(
        protocol = { BridgeProtocol(setOf(TRUSTED), JsonObject(emptyMap()), emptySet()) },
        caller = { BridgeCallerState(BridgePrivacy.CONSENTED, accountKey = "account-a", update = BridgeUpdateState.SUPPORTED) },
        services = services,
        ui = ui,
        awaitUpdate = { BridgeUpdateState.SUPPORTED },
        scope = scope,
    )

    /** 建可信容器并加载 [PAGE_URL]（页面由 [LocalPages] 给出）；返回控制器与 WebView。 */
    private fun open(
        webView: WebView,
        host: BridgeHost,
    ): TrustedWebViewController {
        onMain { activity -> activity.root.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH)) }
        val controller =
            onMain { TrustedWebViewController(webView, TrustedWebConfig(setOf(TRUSTED), emptySet(), "1.0.0"), host, NoTrusted) }
        assumeTrue("system WebView supports the transport", controller.bridgeInstalled)
        onMain {
            webView.webViewClient = LocalPages(webView.webViewClient)
            assertTrue(controller.load(PAGE_URL))
        }
        waitUntilReady(webView, PAGE_URL)
        return controller
    }

    /**
     * 等 [url] 的文档就绪。只认没有 [OLD_DOCUMENT] 记号的文档：文档导航（reload、给 location.href 赋值）发出后，
     * 旧文档在新文档提交前仍然满足 ready、桥在、地址相同（reload 到 `page#part` 时旧文档也是 `page#part`），
     * 不排除它就会在旧文档上「就绪」，随后的导航在旧文档里发出、被 reload 的提交盖掉（F-AND-56：页面停在 #part）。
     */
    private fun waitUntilReady(
        webView: WebView,
        url: String,
    ) = probe(webView).await(
        "page $url ready",
        "window.ready === true && window.$OLD_DOCUMENT !== true && !!window.__REBATE_BRIDGE__ && location.href",
    ) {
        it == "\"$url\""
    }

    /** 发一次文档导航：同一段脚本里先给当前文档做记号，[waitUntilReady] 之后只认新文档。 */
    private fun navigate(
        webView: WebView,
        script: String,
    ) = probe(webView).fire("window.$OLD_DOCUMENT = true; $script")

    /** 发一条 auth.getUser（桥消息）：不等回调，由随后等 [Recorded.validities] 确认到达原生。 */
    private fun startGetUser(webView: WebView) {
        probe(webView).fire("window.__REBATE_BRIDGE__.postMessage({v:1,id:'u-' + Date.now(),method:'auth.getUser',params:{}})")
    }

    // ---------- 第 51 条 ----------

    @Test
    fun aSameAddressReloadInvalidatesTheOldCallsAndOnlyAFragmentJumpKeepsThem() {
        val services = Recorded()
        val webView = onMain { WebView(it) }
        val controller = open(webView, host(services, NoUi))

        // 片段跳转（同一文档）：在途调用仍有效
        startGetUser(webView)
        WebWait.until("first call in flight", detail = { "validities=${services.validities.size}" }) { services.validities.size == 1 }
        probe(webView).fire("location.hash = 'part'")
        probe(webView).await("fragment applied", "location.hash") { it == "\"#part\"" }
        // 负向观察窗（不是同步等待：只可能让核对变弱，不会造成偶发失败），保持原 800 毫秒：核对片段跳转之后调用没被作废
        Thread.sleep(NEGATIVE_WINDOW_MS)
        assertTrue("a fragment jump keeps the call", services.validities[0].stillValid())

        // location.reload()：同一地址重新载入，旧调用在放行时就作废——新文档的响应还压着、没有提交（onPageStarted 之前）
        val reload = hold()
        navigate(webView, "location.reload()")
        WebWait.latch("reload request reached the server", reload.requested)
        // 上限是被测语义：作废必须在 INVALIDATE_MS 之内发生（不能放宽到通用上限）
        WebWait.until("reload invalidates the call before the new document commits", INVALIDATE_MS) {
            !services.validities[0].stillValid()
        }
        reload.release.countDown()
        waitUntilReady(webView, "$PAGE_URL#part")

        // 回到没有片段的地址，再对同一地址赋值（location.href = location.href，不带片段）：同样是文档导航
        navigate(webView, "location.href = '$PAGE_URL'")
        waitUntilReady(webView, PAGE_URL)
        startGetUser(webView)
        WebWait.until("second call in flight", detail = { "validities=${services.validities.size}" }) { services.validities.size == 2 }
        val again = hold()
        navigate(webView, "location.href = location.href")
        WebWait.latch("the same address was requested again", again.requested)
        // 上限是被测语义：作废必须在 INVALIDATE_MS 之内发生（不能放宽到通用上限）
        WebWait.until("same-address navigation invalidates the call before it commits", INVALIDATE_MS) {
            !services.validities[1].stillValid()
        }
        again.release.countDown()
        waitUntilReady(webView, PAGE_URL)
        onMain { controller.destroy() }
    }

    // ---------- 第 53 条 ----------

    @Test
    fun theCallThatClosesTheContainerIsAnsweredBeforeTheTransportIsTornDown() {
        lateinit var controller: TrustedWebViewController
        val main = Handler(Looper.getMainLooper())
        val webView = onMain { HeldWebView(it) }
        // nav.open 清栈：容器路由出栈（与 WebContainerHolder.teardown 相同：立即作废、停止加载，下一条主线程消息里最终拆除）
        val services =
            Recorded {
                controller.invalidateCalls()
                main.post { controller.destroy() }
            }
        controller = open(webView, host(services, NoUi))
        probe(webView).fire(
            "window.out = {}; window.__REBATE_BRIDGE__.postMessage({v:1,id:'close-1',method:'nav.open',params:{route:'home'}})" +
                ".then(function (r) { window.out.close = r.code; })",
        )
        WebWait.until("the container was torn down", detail = { "navOpens=${services.navOpens}" }) { webView.destroyRequested }
        probe(webView).await("the page got the answer of the closing call", "String(window.out && window.out.close)") {
            it != "\"undefined\""
        }
        assertEquals("the closing call is answered as success", "\"0\"", evaluate(webView, "String(window.out.close)"))
        assertEquals(1, services.navOpens)
        onMain { webView.reallyDestroy() }
    }

    // ---------- 替身 ----------

    /** 压住下一次主框架响应：[requested] 在请求到达时归零，[release] 归零后才给内容。 */
    private class Held {
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    @Volatile
    private var held: Held? = null

    private fun hold(): Held = Held().also { held = it }

    /** 包住容器自己的 WebViewClient：容器放行的主框架请求由这里给内容。 */
    private inner class LocalPages(
        private val inner: WebViewClient,
    ) : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            inner.shouldInterceptRequest(view, request)?.let { return it }
            if (request.isForMainFrame) {
                held?.let { gate ->
                    held = null
                    gate.requested.countDown()
                    // 保险上限（测试放行前一直压着响应）；不是等待后断言
                    gate.release.await(WebWait.CEILING_MS, TimeUnit.MILLISECONDS)
                }
            }
            return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(PAGE.toByteArray()))
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
    }

    /** destroy 只记下「已请求拆除」，真正销毁留给测试结束时（拆除之后仍能读页面状态）。 */
    private class HeldWebView(
        context: Context,
    ) : WebView(context) {
        @Volatile
        var destroyRequested = false

        override fun destroy() {
            destroyRequested = true
        }

        fun reallyDestroy() = super.destroy()
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

    /** auth.getUser 记下有效性并一直不回；nav.open 先执行 [onNavOpen]（关掉容器），让出一次主线程后按成功返回。 */
    private class Recorded(
        private val onNavOpen: () -> Unit = {},
    ) : BridgeServices {
        val validities: MutableList<BridgeCallValidity> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var navOpens = 0

        override suspend fun user(validity: BridgeCallValidity): BridgeOutcome {
            validities += validity
            awaitCancellation()
        }

        override suspend fun navOpen(
            route: String,
            params: JsonObject,
            update: String,
        ): BridgeOutcome {
            navOpens += 1
            onNavOpen()
            // 关掉容器之后才回到这里（与 Router 推页面后再返回相同）：结算晚于已排队的拆除消息
            yield()
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
        const val PAGE_URL = "$TRUSTED/page"
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val NEGATIVE_WINDOW_MS = 800L

        /** 旧文档的记号（见 [waitUntilReady]）；新文档里没有这个全局变量。 */
        const val OLD_DOCUMENT = "__couliOldDocument"

        /** 作废要在这之内看到：远小于 auth.getUser 的契约超时（10 秒），不会因为调用到期而碰巧满足。 */
        const val INVALIDATE_MS = 1_500L
        const val PAGE = "<!doctype html><html><body><p>page</p><script>window.ready = true;</script></body></html>"
    }
}
