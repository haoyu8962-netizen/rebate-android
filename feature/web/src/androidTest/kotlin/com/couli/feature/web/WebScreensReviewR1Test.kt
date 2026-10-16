package com.couli.feature.web

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
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
import com.couli.core.bridge.ExternalNavPolicy
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 不连网络的 WebView 替身：loadUrl 换成按地址给内容的 loadDataWithBaseURL（地址、历史与真实导航一致）。 */
class OfflineWebView(
    context: Context,
) : WebView(context) {
    override fun loadUrl(url: String) {
        loadDataWithBaseURL(url, page(url), "text/html", "utf-8", url)
    }

    companion object {
        fun page(url: String): String =
            "<!doctype html><html><head><title>t</title></head><body><p id=\"where\">$url</p>" +
                "<input id=\"q\"><iframe id=\"slot\"></iframe><script>window.ready = true;</script></body></html>"
    }
}

/**
 * Android APP-04 第 1 轮评审 R1-04、R1-08 与三端共性第 28、31、36、38 条的界面行为（真实 WebView + Compose）：
 * - R1-04 / 36：ui.setNavBar 切换 immersive 不重建 WebView——同一个实例，地址、历史、表单内容与在途桥调用都保留；
 * - 28：容器被盖住（移出组合）不拆除，回来挂回同一个 WebView；路由真正出栈（回退栈条目清除）立即作废在途调用、拆除 WebView；
 * - R1-08 / 38：ExternalPage 标题栏左侧返回：有网页历史先后退，没有就关闭；右侧关闭直接关闭整个容器；
 * - 31：子框架里的平台页面被拦，同样给提示与「去搜索」，原页面不动。
 */
@RunWith(AndroidJUnit4::class)
class WebScreensReviewR1Test {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeHostActivity>()

    private val owner =
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }

    private fun count(tag: String): Int = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private fun <T> onUi(block: () -> T): T {
        var value: T? = null
        compose.runOnUiThread { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun webViews(): List<WebView> =
        onUi {
            generateSequence(listOf<View>(compose.activity.window.decorView)) { level ->
                level.flatMap { (it as? ViewGroup)?.let { g -> List(g.childCount, g::getChildAt) }.orEmpty() }
            }.takeWhile { it.isNotEmpty() }
                .flatten()
                .filterIsInstance<WebView>()
                .toList()
        }

    private fun probe(webView: WebView) = PageProbe(webView) { block -> compose.runOnUiThread { block() } }

    /** 页面已交回结论之后读一次值。 */
    private fun evaluate(
        webView: WebView,
        script: String,
    ): String = probe(webView).read(script)

    private fun waitUntil(
        message: String,
        condition: () -> Boolean,
    ) = WebWait.until(message, condition = condition)

    /**
     * 以页面里已提交的文档为准（webView.url 在新文档提交前就会变）。F-AND-31：原来在导航进行中逐次求值、单次回调 10 秒没回就判失败
     * （FU-AND-0715 编排复验 r3 的失败栈）；改为等页面交回「已是 [url] 这份文档且脚本已执行」，探测不挂在导航上（[PageProbe.await]）。
     */
    private fun ready(
        webView: WebView,
        url: String,
    ) = probe(webView).await("$url ready", "window.ready === true && location.href") { it == "\"$url\"" }

    /** 可信容器：宿主是真实 BridgeHost（auth.getUser 挂着，交出调用有效性），作用域是容器实例自己的。 */
    private inner class TrustedFixture {
        var ui: BridgeUi? = null
        var host: BridgeHost? = null
        val validity = CompletableDeferred<BridgeCallValidity>()
        val replies: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var shown by mutableStateOf(true)

        fun show() =
            compose.setContent {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalWebViewFactory provides { context -> OfflineWebView(context) },
                ) {
                    if (shown) {
                        TrustedWebScreen(
                            url = PAGE_A,
                            config = TrustedWebConfig(setOf(TRUSTED), emptySet(), "1.0.0"),
                            hostFactory = { captured, scope -> newHost(captured, scope) },
                            onOpenExternal = {},
                            onClose = {},
                        )
                    }
                }
            }

        private fun newHost(
            captured: BridgeUi,
            scope: CoroutineScope,
        ): BridgeHost {
            ui = captured
            return BridgeHost(
                protocol = { BridgeProtocol(setOf(TRUSTED), JsonObject(emptyMap()), emptySet()) },
                caller = { BridgeCallerState(BridgePrivacy.CONSENTED, accountKey = "account-a", update = BridgeUpdateState.SUPPORTED) },
                services = PendingUser(validity),
                ui = captured,
                awaitUpdate = { BridgeUpdateState.SUPPORTED },
                scope = scope,
            ).also { host = it }
        }

        /** 从原生侧发起一次在途的 auth.getUser（与页面发来的消息走同一个入口）。 */
        fun startCall() {
            compose.runOnUiThread {
                checkNotNull(host).handle("""{"v":1,"id":"in-flight","method":"auth.getUser","params":{}}""", TRUSTED, true, false) {
                    replies += it
                }
            }
            waitUntil("call in flight") { validity.isCompleted }
        }
    }

    /** 浏览 A → B 并在 B 的表单里输入内容；返回这个 WebView。 */
    private fun browseToBAndType(): WebView {
        waitUntil("web view created") { webViews().isNotEmpty() }
        val webView = webViews().single()
        ready(webView, PAGE_A)
        compose.runOnUiThread { webView.loadUrl(PAGE_B) }
        ready(webView, PAGE_B)
        evaluate(webView, "document.getElementById('q').value = 'typed'; true")
        return webView
    }

    private fun assertStateKept(webView: WebView) {
        assertEquals(PAGE_B, onUi { webView.url })
        assertTrue("history kept", onUi { webView.canGoBack() })
        assertEquals("form content kept", "\"typed\"", evaluate(webView, "document.getElementById('q').value"))
    }

    @Test
    fun immersiveToggleKeepsTheSameWebViewWithItsAddressHistoryFormAndInFlightCall() {
        val fixture = TrustedFixture()
        fixture.show()
        val webView = browseToBAndType()
        fixture.startCall()
        val call = fixture.validity.getCompleted()

        listOf(true, false).forEach { immersive ->
            assertTrue(onUi { checkNotNull(fixture.ui).setNavBar(Json.parseToJsonElement("""{"immersive":$immersive}""").jsonObject) })
            compose.waitForIdle()
            assertSame("immersive=$immersive does not recreate the WebView", webView, webViews().single())
            assertStateKept(webView)
            assertTrue("the in-flight call survives the layout change", call.stillValid())
        }
        assertTrue("no answer yet: the call is still in flight", fixture.replies.isEmpty())
    }

    @Test
    fun coveringKeepsTheContainerAndPoppingTheRouteTearsItDownAtOnce() {
        val fixture = TrustedFixture()
        fixture.show()
        val webView = browseToBAndType()
        fixture.startCall()
        val call = fixture.validity.getCompleted()

        // 被别的页面盖住：移出组合，回退栈条目（ViewModelStore）还在
        fixture.shown = false
        compose.waitForIdle()
        assertTrue("covered is not popped: the call stays valid", call.stillValid())
        fixture.shown = true
        compose.waitForIdle()
        assertSame("the same WebView comes back", webView, webViews().single())
        assertStateKept(webView)
        assertTrue(call.stillValid())

        // 路由真正出栈：回退栈条目清除
        compose.runOnUiThread { owner.viewModelStore.clear() }
        compose.waitForIdle()
        assertFalse("popping invalidates the in-flight call", call.stillValid())
        waitUntil("the call was answered 90500") { fixture.replies.any { "90500" in it } }
        assertNull("the WebView left the hierarchy", onUi { webView.parent })
    }

    private fun externalScreen(
        url: String,
        closes: AtomicInteger,
        searches: AtomicInteger = AtomicInteger(),
    ) = compose.setContent {
        CompositionLocalProvider(
            LocalViewModelStoreOwner provides owner,
            LocalWebViewFactory provides { context -> OfflineWebView(context) },
        ) {
            ExternalWebScreen(
                url = url,
                subtitle = null,
                navStyle = null,
                policy = ExternalNavPolicy(PATTERNS),
                linkPatterns = { PATTERNS },
                flags = { ExternalNavFlags(consented = true, productIntercept = true) },
                track = {},
                appVersion = "1.0.0",
                onProductIntent = { _, _, _ -> },
                onOpenBrowser = {},
                onSearch = { searches.incrementAndGet() },
                onClose = { closes.incrementAndGet() },
            )
        }
    }

    @Test
    fun externalTitleBarBackGoesBackInHistoryThenClosesAndCloseClosesAtOnce() {
        assumeTrue(ExternalWebViewController.cookieIsolationSupported())
        val closes = AtomicInteger()
        externalScreen(EXTERNAL_A, closes)
        waitUntil("web view created") { webViews().isNotEmpty() }
        val webView = webViews().single()
        ready(webView, EXTERNAL_A)
        compose.runOnUiThread { webView.loadUrl(EXTERNAL_B) }
        ready(webView, EXTERNAL_B)

        assertEquals("back button on the left of the title bar", 1, count(BACK_TAG))
        compose.onNodeWithTag(BACK_TAG).performClick()
        waitUntil("went back") { onUi { webView.url } == EXTERNAL_A }
        // 原来只等 webView.url（新文档提交前就会变）就点第二次返回：改为再等返回后的文档真正提交（历史随之更新）
        ready(webView, EXTERNAL_A)
        assertEquals("going back in history does not close", 0, closes.get())

        compose.onNodeWithTag(BACK_TAG).performClick()
        compose.waitForIdle()
        assertEquals("no history left: back closes the container", 1, closes.get())
    }

    @Test
    fun externalCloseButtonClosesTheWholeContainerEvenWithHistory() {
        assumeTrue(ExternalWebViewController.cookieIsolationSupported())
        val closes = AtomicInteger()
        externalScreen(EXTERNAL_A, closes)
        waitUntil("web view created") { webViews().isNotEmpty() }
        val webView = webViews().single()
        ready(webView, EXTERNAL_A)
        compose.runOnUiThread { webView.loadUrl(EXTERNAL_B) }
        ready(webView, EXTERNAL_B)

        compose.onNodeWithTag(CLOSE_TAG).performClick()
        compose.waitForIdle()
        assertEquals("one tap closes the whole container (no going back first)", 1, closes.get())
        assertNull("closing tears the container down at once", onUi { webView.parent })
    }

    @Test
    fun aPlatformPageBlockedInASubframeOffersTheNoticeWithGoToSearch() {
        assumeTrue(ExternalWebViewController.cookieIsolationSupported())
        val searches = AtomicInteger()
        externalScreen(EXTERNAL_A, AtomicInteger(), searches)
        waitUntil("web view created") { webViews().isNotEmpty() }
        val webView = webViews().single()
        ready(webView, EXTERNAL_A)

        evaluate(webView, "document.getElementById('slot').src = 'https://shop.taobao.example.test/frame'; true")
        compose.waitUntil(WebWait.CEILING_MS) { count(EXTERNAL_SEARCH_TAG) > 0 }
        assertEquals("original page stays", EXTERNAL_A, onUi { webView.url })
        compose.onNodeWithTag(EXTERNAL_SEARCH_TAG).performClick()
        compose.waitForIdle()
        assertEquals(1, searches.get())
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
        const val PAGE_A = "$TRUSTED/a"
        const val PAGE_B = "$TRUSTED/b"
        const val EXTERNAL_A = "https://page.example.test/a"
        const val EXTERNAL_B = "https://page.example.test/b"

        val PATTERNS: JsonObject =
            Json
                .parseToJsonElement(
                    """{"version":"instrumented","rules":[""" +
                        """{"platform":"taobao","category":"union_host","hosts":["taobao.example.test"],"path_patterns":[]}]}""",
                ).jsonObject
    }
}
