package com.couli.feature.web

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.bridge.BridgeCallerState
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.BridgePrivacy
import com.couli.core.bridge.BridgeProtocol
import com.couli.core.bridge.BridgeServices
import com.couli.core.bridge.BridgeUi
import com.couli.core.bridge.BridgeUpdateState
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B2 / B6 的真实 WebView 补充：读取 document-start 注入的 methods，使用 H5 SDK nativeSupports 的同一判断式。
 * 不把 host.declaredMethods 的 Kotlin 断言冒充网页结果；H5 SDK 自身 has() 返回 Capability / null，测试布尔可用性。
 */
@RunWith(AndroidJUnit4::class)
class Fx24BridgeCapabilitiesTest {
    private lateinit var scenario: ActivityScenario<WebTestHostActivity>
    private var controller: TrustedWebViewController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Before
    fun start() {
        scenario = ActivityScenario.launch(WebTestHostActivity::class.java)
    }

    @After
    fun close() {
        if (::scenario.isInitialized) {
            scenario.onActivity { controller?.destroy() }
            scenario.close()
        }
        scope.cancel()
    }

    @Test
    fun b2DisabledMethodIsAbsentFromTheRealPageCapabilityProbe() {
        val probe = open(setOf("clipboard.read"))
        assertEquals("false", probe.read(supports("clipboard.read")))
        assertEquals("true", probe.read(supports("app.getEnv")))
    }

    @Test
    fun b6UnknownDisabledNameDoesNotHideOrInventPageCapabilities() {
        val probe = open(setOf("unknown.method"))
        assertEquals("true", probe.read(supports("clipboard.read")))
        assertEquals("false", probe.read(supports("unknown.method")))
    }

    private fun open(disabled: Set<String>): PageProbe {
        lateinit var webView: WebView
        scenario.onActivity { activity ->
            webView = WebView(activity)
            activity.root.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
            val host =
                BridgeHost(
                    protocol = {
                        BridgeProtocol(
                            trustedOrigins = setOf(ORIGIN),
                            linkPatterns = JsonObject(emptyMap()),
                            shareDomains = emptySet(),
                            disabledMethods = disabled,
                        )
                    },
                    caller = { BridgeCallerState(BridgePrivacy.CONSENTED, "fx24-user", BridgeUpdateState.SUPPORTED) },
                    services = noServices(),
                    ui = NoUi,
                    awaitUpdate = { BridgeUpdateState.SUPPORTED },
                    scope = scope,
                )
            val installed =
                TrustedWebViewController(webView, TrustedWebConfig(setOf(ORIGIN), emptySet(), "1.0.0"), host, NoCallbacks)
            controller = installed
            assertTrue("需要支持真实桥传输的系统 WebView，不能跳过后报绿", installed.bridgeInstalled)
            webView.webViewClient = LocalPage(webView.webViewClient)
            assertTrue(installed.load("$ORIGIN/fx24"))
        }
        return PageProbe(webView) { action -> scenario.onActivity { action() } }.also { probe ->
            probe.await("F-X-24 页面及原生桥已就绪", "document.readyState === 'complete' && !!window.__REBATE_BRIDGE__") { it == "true" }
        }
    }

    // 能力探测不应执行任何业务服务；Proxy 只省掉与本断言无关的接口样板，不模拟准入与传输。
    private fun noServices(): BridgeServices =
        Proxy.newProxyInstance(BridgeServices::class.java.classLoader, arrayOf(BridgeServices::class.java)) { _, method, _ ->
            error("能力探测不应执行处理器：${method.name}")
        } as BridgeServices

    private fun supports(method: String): String =
        "Array.isArray(window.__REBATE_BRIDGE__.methods) && window.__REBATE_BRIDGE__.methods.includes('$method')"

    private class LocalPage(
        private val delegate: WebViewClient,
    ) : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse =
            WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream("<!doctype html><title>F-X-24</title>".toByteArray()))

        override fun onPageStarted(
            view: WebView,
            url: String,
            favicon: Bitmap?,
        ) = delegate.onPageStarted(view, url, favicon)

        override fun onPageFinished(
            view: WebView,
            url: String,
        ) = delegate.onPageFinished(view, url)
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

    private object NoCallbacks : TrustedWebCallbacks {
        override fun onOpenExternal(url: String) = error("能力探测不应外跳")

        override fun onLoadFailed() = error("本地测试页加载失败")
    }

    private companion object {
        const val ORIGIN = "https://h5.example.test"
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
    }
}
