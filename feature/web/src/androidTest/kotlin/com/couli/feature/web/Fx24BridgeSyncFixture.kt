package com.couli.feature.web

import android.content.Context
import android.graphics.Bitmap
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.couli.core.bridge.BridgeCallerState
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.BridgePrivacy
import com.couli.core.bridge.BridgeProtocol
import com.couli.core.bridge.BridgeServices
import com.couli.core.bridge.BridgeUi
import com.couli.core.bridge.BridgeUpdateState
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue

/** 真宿主、真传输、真 WebView；只替换页面响应和未使用的业务服务，推送观察后仍交系统执行。 */
@Suppress("TooManyFunctions") // 配置、导航、页面读取、主线程观察分别一个入口，测试不触碰产品私有字段。
internal class Fx24BridgeSyncFixture : AutoCloseable {
    private val scenario = ActivityScenario.launch(WebTestHostActivity::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val changes = MutableStateFlow(0)
    private var disabled: Set<String> = emptySet()
    private var trustedOrigins: Set<String> = setOf(ORIGIN)
    private var observedRevision = -1
    private var updateObserver: ScriptHandler? = null
    private lateinit var webView: ObservedWebView
    private lateinit var controller: TrustedWebViewController
    private lateinit var page: LocalPage
    lateinit var probe: PageProbe
        private set

    val pushes: Int get() = onMain { webView.methodPushes }

    fun open(
        initialDisabled: Set<String> = emptySet(),
        observeUpdates: Boolean = false,
    ) {
        disabled = initialDisabled
        scenario.onActivity { activity ->
            webView = ObservedWebView(activity)
            activity.root.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
            if (observeUpdates) observeUpdaterCalls()
            val host = newHost()
            controller =
                TrustedWebViewController(webView, TrustedWebConfig(setOf(ORIGIN), emptySet(), "1.0.0"), host, NoCallbacks)
            assertTrue("需要支持真实桥传输的系统 WebView，不能跳过后报绿", controller.bridgeInstalled)
            page = LocalPage(webView.webViewClient)
            webView.webViewClient = page
            assertTrue(controller.load("$ORIGIN/fx24"))
        }
        probe = PageProbe(webView) { action -> onMain(action) }
        awaitDocument("null", 0)
    }

    private fun newHost(): BridgeHost =
        BridgeHost(
            protocol = {
                observedRevision = changes.value
                BridgeProtocol(
                    trustedOrigins = trustedOrigins,
                    linkPatterns = JsonObject(emptyMap()),
                    shareDomains = emptySet(),
                    disabledMethods = disabled,
                )
            },
            caller = { BridgeCallerState(BridgePrivacy.CONSENTED, "fx24-user", BridgeUpdateState.SUPPORTED) },
            services =
                Proxy.newProxyInstance(BridgeServices::class.java.classLoader, arrayOf(BridgeServices::class.java)) { _, method, _ ->
                    error("能力探测不应执行处理器：${method.name}")
                } as BridgeServices,
            ui = NoUi,
            awaitUpdate = { BridgeUpdateState.SUPPORTED },
            scope = scope,
            configChanges = changes,
        )

    /** 同一次发布替换禁用名单和当前可信来源；等真实 protocol 消费 revision 及主线程同步回调结束。 */
    fun publish(
        next: Set<String>,
        nextTrustedOrigins: Set<String>? = null,
    ) {
        val revision =
            onMain {
                disabled = next
                if (nextTrustedOrigins != null) trustedOrigins = nextTrustedOrigins
                changes.value += 1
                changes.value
            }
        WebWait.until("BridgeHost 已消费配置 revision=$revision") { onMain { observedRevision == revision } }
    }

    /** 只给撤销来源用例加调用观察；先于产品启动脚本安装，计数后原样转交真实函数，不保存参数或令牌。 */
    private fun observeUpdaterCalls() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) error("调用观察需要 WebView 启动脚本支持")
        updateObserver =
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                """
                (function () {
                  var defineProperty = Object.defineProperty, apply = Reflect.apply;
                  window.fx24UpdateCalls = 0;
                  Object.defineProperty = function (target, name, descriptor) {
                    if (target === window && name === '${BridgeTransport.UPDATE_METHODS}') {
                      var update = descriptor.value;
                      descriptor.value = function () {
                        window.fx24UpdateCalls += 1;
                        return apply(update, this, arguments);
                      };
                      Object.defineProperty = defineProperty;
                    }
                    return defineProperty(target, name, descriptor);
                  };
                })();
                """.trimIndent(),
                setOf(ORIGIN),
            )
    }

    fun awaitSupports(
        method: String,
        expected: Boolean,
    ) {
        probe.await("当前文档 $method 可用性=$expected", supports(method)) { it == expected.toString() }
    }

    fun reload() = navigate { controller.reload() }

    fun load(path: String) = navigate { assertTrue(controller.load("$ORIGIN/$path")) }

    /** 故意绕过容器导航拦截，让真实传输面对不可信主框架，单独验证它的来源守卫。 */
    fun loadUntrusted() = navigate { webView.loadUrl("$UNTRUSTED/fx24-untrusted") }

    private fun navigate(action: () -> Unit) {
        val oldDocument = probe.read("window.fx24Document")
        val finishes = onMain { page.finishes }
        onMain(action)
        awaitDocument(oldDocument, finishes)
    }

    private fun awaitDocument(
        oldDocument: String,
        finishes: Int,
    ) {
        probe.await(
            "新文档首段脚本已执行且加载完成",
            "document.readyState === 'complete' && !!window.fx24Document && window.fx24Document !== $oldDocument",
        ) { it == "true" }
        // readyState=complete 可能早于 Android onPageFinished；等真实回调后才开始计数、发布配置。
        WebWait.until("控制器已收到 onPageFinished") { onMain { page.finishes > finishes } }
    }

    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        scenario.onActivity { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    override fun close() {
        try {
            onMain {
                updateObserver?.remove()
                if (::controller.isInitialized) controller.destroy()
            }
        } finally {
            scope.cancel()
            scenario.close()
        }
    }

    private class ObservedWebView(
        context: Context,
    ) : WebView(context) {
        var methodPushes = 0
            private set

        override fun evaluateJavascript(
            script: String,
            resultCallback: ValueCallback<String>?,
        ) {
            // PageProbe 的查询都带回调，不算进原生推送；不保存脚本或随机令牌。
            if (resultCallback == null && script.contains(BridgeTransport.UPDATE_METHODS)) methodPushes += 1
            super.evaluateJavascript(script, resultCallback)
        }
    }

    private class LocalPage(
        private val delegate: WebViewClient,
    ) : WebViewClient() {
        private val documents = AtomicInteger()
        var finishes = 0
            private set

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse {
            val html =
                """
                <!doctype html><script>
                window.fx24Document = 'document-${documents.incrementAndGet()}';
                window.fx24InitialMethods = window.__REBATE_BRIDGE__ ? window.__REBATE_BRIDGE__.methods.slice() : null;
                window.fx24UntrustedCalls = 0;
                if (!window.__couliBridgeMethods) {
                  window.__couliBridgeMethods = function () { window.fx24UntrustedCalls += 1; return true; };
                }
                </script><title>F-X-24 sync</title>
                """.trimIndent()
            return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
        }

        override fun onPageStarted(
            view: WebView,
            url: String,
            favicon: Bitmap?,
        ) {
            // 不可信页用例只隔离导航拦截；启动脚本来源规则、配置订阅和完成补推仍为真实产品逻辑。
            if (BridgeProtocol.originOf(url) == ORIGIN) delegate.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(
            view: WebView,
            url: String,
        ) {
            delegate.onPageFinished(view, url)
            finishes += 1
        }
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

    companion object {
        const val ORIGIN = "https://h5.example.test"
        const val UNTRUSTED = "https://untrusted.example.test"
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT

        fun supports(method: String): String =
            "Array.isArray(window.__REBATE_BRIDGE__.methods) && window.__REBATE_BRIDGE__.methods.includes('$method')"
    }
}
