package com.couli.feature.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.couli.core.bridge.AuthPages
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.BridgeProtocol
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.JsonObject

/**
 * 可信容器的来源配置。
 * @property trustedOrigins 可信 H5 源（`https://host[:port]`：routes 与契约登记的域，来自 /v1/config）。
 * @property loopbackOrigins Debug 构建另加的本机一致性测试源（可为 http）；Release 一律为空（release-scan 检查）。
 */
data class TrustedWebConfig(
    val trustedOrigins: Set<String>,
    val loopbackOrigins: Set<String>,
    val appVersion: String,
) {
    /** WebView allowedOriginRules（规范化后的完整 origin）。 */
    val originRules: Set<String>
        get() =
            trustedOrigins.mapNotNull { BridgeProtocol.normalizeOriginForRules(it, allowHttp = false) }.toSet() +
                loopbackOrigins.mapNotNull { BridgeProtocol.normalizeOriginForRules(it, allowHttp = true) }

    /** 地址的 origin 是否可信（只认 https；Debug 本机源逐字相等）。 */
    fun allows(url: String): Boolean {
        val origin = BridgeProtocol.originOf(url) ?: return false
        return origin in originRules
    }
}

/** TrustedWebView 交给外层（页面）的事件。 */
interface TrustedWebCallbacks {
    /** 跳到白名单外的 https 地址：在新的 ExternalWebView 打开，原容器不变（03 §5.1）。 */
    fun onOpenExternal(url: String)

    /** 主框架加载失败（连不上、证书错误被取消等）：显示原生错误页。 */
    fun onLoadFailed()

    fun onTitle(title: String) = Unit

    fun onPageStarted() = Unit

    /** 切号隔离已解除（F-X-25：换存储成功、已在新 WebView 上建好新账号的文档）：外层收起此前因补清失败显示的错误页。 */
    fun onAuthRecovered() = Unit
}

/**
 * 可信容器（TrustedWebView，03 §5.1）：只加载可信 H5 源的 https 主框架（Debug 本机源例外）；注入桥传输层（[BridgeTransport]）；
 * 证书错误一律取消（CSB-02）；非 https 的导航一律取消；跳到白名单外的 https 地址交给 [TrustedWebCallbacks.onOpenExternal]。
 * [host] 为 null 时不注入桥（系统 WebView 不支持传输层也一样）。
 *
 * 切号（F-X-25 v2，D10-309）：一个控制器只管一份 WebView、一份文档序列。生产里由 [TrustedWebPage] 建（WebView 出自
 * [TrustedWebViewFactory]）。[isolate] 之后（任何线程、同步）：桥调用一律拒绝、不交新令牌、[load] / [reload] / [goBack] / 页面发起的
 * 导航一律拦下、后台拦截入口对**全部** http(s) 请求（主框架 204、其余 403，含 beacon / keepalive）不放行——隔离不再解除，这份
 * WebView 随后由 [retire] 拆掉（about:blank 提交、下树、传输层 dispose、destroy），新文档一定建在新的 WebView 与新控制器上。
 */
@Suppress("TooManyFunctions") // 加载、生命周期事件、返回、回弹、切号隔离、拆除与销毁逐项一个入口
class TrustedWebViewController(
    private val webView: WebView,
    private val config: TrustedWebConfig,
    host: BridgeHost?,
    private val callbacks: TrustedWebCallbacks,
    /** 手势时钟的单调时钟（生产 uptimeMillis；仪器测试注入固定时刻，F-AND-31：1 秒手势窗口不随调度延迟漂移）。 */
    gestureNow: () -> Long = SystemClock::uptimeMillis,
) {
    private val gesture = GestureClock(gestureNow)
    private val main = Handler(Looper.getMainLooper())
    private val transport: BridgeTransport? = host?.let { BridgeTransport(webView, it, gesture) }

    /** 桥是否已注入（系统 WebView 不支持 WEB_MESSAGE_LISTENER / DOCUMENT_START_SCRIPT 时为 false）。 */
    val bridgeInstalled: Boolean

    /** 切号隔离中（F-X-25 I1）；任何线程写，后台拦截线程读。隔离后不再解除。 */
    @Volatile private var isolated = false
    private var destroyed = false

    /** 等新文档就绪的那次加载（[loadDocument]）；只在主线程读写。 */
    private var awaiting: Awaiting? = null

    /** 拆除中等 about:blank 提交的回调（[retire]）；只在主线程读写。 */
    private var retiring: ((Boolean) -> Unit)? = null

    private class Awaiting(
        val completed: (Boolean) -> Unit,
    ) {
        /** 这次加载提交的可信文档的世代（提交前为 null）。 */
        var generation: Long? = null
    }

    init {
        webView.applyContainerSettings(config.appVersion)
        gesture.attach(webView)
        webView.webViewClient = Client()
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onReceivedTitle(
                    view: WebView,
                    title: String?,
                ) {
                    title?.let(callbacks::onTitle)
                }
            }
        bridgeInstalled = transport?.install(config.originRules) == true
        transport?.onDocumentBound = ::onDocumentBound
    }

    /** 加载 [url]：只接受可信源，授权页不进容器（初始地址同样检查，APP-04 三端共性第 16 条）；其余不加载并返回 false。隔离中不加载。 */
    fun load(url: String): Boolean {
        if (!loadable(url)) return false
        webView.loadUrl(url)
        return true
    }

    fun reload() {
        if (isolated || destroyed) return
        transport?.onNavigationStarting()
        webView.reload()
    }

    /** ui.setNavBar bounce：页面滚到边缘时是否有回弹（Android 为越界滚动效果）。 */
    fun setBounce(enabled: Boolean) {
        webView.overScrollMode = if (enabled) View.OVER_SCROLL_ALWAYS else View.OVER_SCROLL_NEVER
    }

    /** 页面回到前台 / 退到后台：下发 app.resume / app.pause 与 page.visible（data 不带令牌、剪贴板与设备标识）。 */
    fun onResume() {
        transport?.emit(EVENT_RESUME)
        transport?.emit(EVENT_VISIBLE, visible(true))
    }

    fun onPause() {
        transport?.emit(EVENT_VISIBLE, visible(false))
        transport?.emit(EVENT_PAUSE)
    }

    fun canGoBack(): Boolean = !isolated && !destroyed && webView.canGoBack()

    fun goBack() {
        if (isolated || destroyed) return
        transport?.onNavigationStarting()
        webView.goBack()
    }

    /** 路由出栈的第一步（立即）：作废全部在途调用（正在触发这次导航的那一次先结算，第 43 条）、停止加载。 */
    fun invalidateCalls() {
        transport?.onNavigationStarting()
        if (!destroyed) webView.stopLoading()
    }

    /**
     * 最终拆除：拆传输层、销毁 WebView。关闭本容器的那次桥调用（nav.open 清栈、nav.close）还没把回复交给页面时，等它交出之后再拆
     * （共性第 53 条）——传输层不能因为先拆掉而丢掉这条回复。只拆一次。拆除中、等新文档中的协调器请求随之结束（销毁即已拆，新文档不会来）。
     */
    fun destroy(afterRelease: () -> Unit = {}) {
        if (destroyed) return afterRelease()
        destroyed = true
        // 拆除中途出栈：WebView 真正销毁之后才确认拆除（评审 v2 r2 S2-3(b)）
        val pendingRetire = retiring
        retiring = null
        failAwaiting()
        val release = {
            transport?.dispose()
            webView.stopLoading()
            webView.destroy()
            pendingRetire?.invoke(true)
            afterRelease()
        }
        transport?.afterClosingCallReplied(release) ?: release()
    }

    /** 渲染进程已退出（评审 v2 r1 S2-05）：这份文档已不存在，拆除不必等 about:blank（等不到），直接下树销毁并确认。 */
    @Volatile private var rendererGone = false

    // ---- 切号（F-X-25 v2；由 TrustedWebPage 调用）

    /** 当前主框架仍可信（按本容器的规则与当前生效配置）；没有文档、已销毁为 false。主线程。 */
    internal val currentTrusted: Boolean get() = !destroyed && transport?.currentTrustedOrigin() != null

    /** 当前文档的令牌已确认绑定。主线程。 */
    internal val tokenDelivered: Boolean get() = !destroyed && transport?.documentBound == true

    /** 同步进入隔离（任何线程，I1）：宿主拒绝一切调用并作废在途调用、握手不再交令牌、导航与全部请求拦下。不再解除。 */
    internal fun isolate() {
        isolated = true
        transport?.isolate()
        if (Looper.myLooper() == Looper.getMainLooper()) failAwaiting() else main.post { failAwaiting() }
    }

    /** auth.changed 的专用投递（I7，主线程）：交给持当前令牌的文档执行，[completed] 的 true 为已执行。 */
    internal fun notifyAuthChanged(
        data: JsonObject,
        completed: (Boolean) -> Unit,
    ) {
        val channel = transport
        if (destroyed || channel == null) return completed(false)
        channel.emitAuthChanged(data, completed)
    }

    /**
     * 新建页加载入口（主线程）：[completed] 的 true 只在这次加载提交了可信新文档、且它的令牌已确认绑定时给出（没有桥时提交即就绪）；
     * 加载失败、落到别处、渲染进程退出、被隔离或销毁为 false。
     */
    internal fun loadDocument(
        url: String,
        completed: (Boolean) -> Unit,
    ) {
        failAwaiting()
        if (!loadable(url)) return completed(false)
        awaiting = Awaiting(completed)
        webView.loadUrl(url)
    }

    /**
     * 拆掉这份 WebView（主线程，I1 先拆后清）：停止加载 → 加载 about:blank 并等它提交完成（旧文档的 pagehide 在这之前执行完，期间的
     * 请求被隔离拦下）→ 从父视图摘下 → 传输层 dispose → destroy，之后 [completed] 才给 true。about:blank 一直不提交就不回调（协调器按
     * 超时失败处理，旧 WebView 保持隔离、不强制销毁）。只在隔离之后调用。
     */
    internal fun retire(completed: (Boolean) -> Unit) {
        if (destroyed) return completed(true)
        retiring = completed
        if (rendererGone) return finishRetire()
        webView.stopLoading()
        webView.loadUrl(ABOUT_BLANK)
    }

    /** 没有隔离、没有销毁，地址是可信源且不是授权页（APP-04 共性第 16 条）。 */
    private fun loadable(url: String): Boolean = !isolated && !destroyed && config.allows(url) && !AuthPages.isAuthPage(url)

    private fun finishRetire() {
        val pending = retiring ?: return
        retiring = null
        destroyed = true
        failAwaiting()
        (webView.parent as? ViewGroup)?.removeView(webView)
        transport?.dispose()
        webView.destroy()
        pending(true)
    }

    private fun failAwaiting() {
        val attempt = awaiting ?: return
        awaiting = null
        attempt.completed(false)
    }

    /** 新文档的令牌已确认绑定：它就是这次加载要等的那份文档时，就绪。 */
    private fun onDocumentBound(generation: Long) {
        val attempt = awaiting ?: return
        if (attempt.generation == generation) {
            awaiting = null
            attempt.completed(true)
        }
    }

    /** 等待中的加载提交了主框架：可信新文档等令牌绑定（没有桥时提交即就绪）；落到别处按失败。 */
    private fun onDocumentCommitted(
        attempt: Awaiting,
        url: String?,
    ) {
        val trusted = url != null && config.allows(url) && !AuthPages.isAuthPage(url)
        if (!trusted) return failAwaiting()
        val channel = transport
        if (channel == null || !bridgeInstalled) {
            awaiting = null
            attempt.completed(true)
            return
        }
        attempt.generation = channel.generation
    }

    /** [target] 与当前地址只差片段（同一文档内的跳转）。 */
    private fun sameDocument(
        current: String?,
        target: String,
    ): Boolean = current != null && '#' in target && current.substringBefore('#') == target.substringBefore('#')

    private fun visible(value: Boolean) = JsonObject(mapOf("visible" to kotlinx.serialization.json.JsonPrimitive(value)))

    // onRenderProcessGone 已实现（返回 true 并显示错误页）；该检查认不出 Kotlin 内部类里的覆写
    @SuppressLint("MissingOnRenderProcessGone")
    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            // 切号隔离（F-X-25 I1）：页面发起的导航一律取消
            if (isolated) return true
            val url = request.url.toString()
            val https = url.startsWith(HTTPS_PREFIX, ignoreCase = true)
            return when {
                // 子框架的网络文档一律取消（R3-01、共性第 56 条：拿不到每一跳重定向回调，跨源子框架可能被重定向进可信源、
                // 再借父页面的桥）；about:blank、srcdoc 不是网络导航，不受影响
                !request.isForMainFrame -> {
                    true
                }

                // 授权页不进容器，也不转交 ExternalWebView（第 16 条）：取消，原页面不动
                AuthPages.isAuthPage(url) -> {
                    true
                }

                config.allows(url) -> {
                    // 主框架一开始导航就作废旧页面的调用（第 29 条）；同一文档内只换片段的不算换文档
                    if (!sameDocument(view.url, url)) transport?.onNavigationStarting()
                    false
                }

                https -> {
                    callbacks.onOpenExternal(url)
                    true
                }

                else -> {
                    true
                }
            }
        }

        /**
         * 发出前的最后一道（后台线程）。子框架的 http(s) 导航不经 shouldOverrideUrlLoading，只能在这里拦；而这里只看得到第一跳，
         * 服务器重定向的后续各跳不再回调。所以非主框架的文档请求（Accept 含 text/html 或 Sec-Fetch-Dest 为 iframe / frame）**一律**
         * 在第一跳取消（R3-01、编排者裁定「看不到每一跳就在第一跳取消」(a)、共性第 56 条）：不发出、给空的 403，子框架里不会载入任何
         * 网络内容——跨源子框架就不可能经 302 进入可信源、再借 parent 的桥（同源子框架第 13 条、授权页第 16 条随之覆盖）。
         * about:blank、srcdoc 子框架不是网络请求、内容由父页面写入（与主页面同一主体），不经过这里。
         * 主框架：授权页、不可信源（含平台域）的请求——包括不经 shouldOverrideUrlLoading 的表单 POST——不发出，回 **204 No Content**：
         * 204 的导航响应不提交新文档，原页面（DOM、表单、地址、历史）保持不动（R1-07、共性第 37 条；不用替代文档冒充取消）。
         * 放行的主框架请求（前进后退、刷新、POST、初始地址）在发出前就作废旧页面的调用（第 29 条）。
         * 切号隔离中（F-X-25 I1）：一律不放行——主框架 204，其余（脚本、XHR / fetch、sendBeacon、keepalive）403，旧文档不能再借
         * 网络响应的 Set-Cookie 写回（没有「重载期放行」）。
         */
        @Suppress("ReturnCount") // 非网络、隔离、主框架拦下、主框架放行、子框架
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url.toString()
            if (!url.startsWith(HTTP_PREFIX, ignoreCase = true)) return null
            if (isolated) return if (request.isForMainFrame) ExternalWebViewController.noContentResponse() else blockedResponse()
            if (request.isForMainFrame) {
                if (AuthPages.isAuthPage(url) || !config.allows(url)) return ExternalWebViewController.noContentResponse()
                main.post { transport?.onNavigationStarting() }
                return null
            }
            return if (isDocumentRequest(request.requestHeaders)) blockedResponse() else null
        }

        override fun onPageStarted(
            view: WebView,
            url: String?,
            favicon: Bitmap?,
        ) {
            transport?.onMainFrameNavigation()
            // 切换页面：之前页面上的点击不再算作这个页面的手势（APP-04 三端共性第 5 条）
            gesture.clear()
            if (url == ABOUT_BLANK && retiring != null) return
            // 新文档提交（如渲染进程退出后【重试】reload 起了新进程）：之后的拆除照常走 about:blank（评审 v2 r2 S2-3(a)）
            if (url != ABOUT_BLANK) rendererGone = false
            awaiting?.let { onDocumentCommitted(it, url) }
            // 兜底：没经过 shouldOverrideUrlLoading 的主框架导航（如表单 POST）落到不可信源时停止加载
            if (url != null && !config.allows(url) && url != ABOUT_BLANK) {
                view.stopLoading()
                callbacks.onLoadFailed()
                return
            }
            callbacks.onPageStarted()
        }

        override fun onPageFinished(
            view: WebView,
            url: String?,
        ) {
            // 拆除：about:blank 已提交完成（旧文档的 pagehide 已执行完）
            if (url == ABOUT_BLANK && retiring != null) return finishRetire()
            // 方法列表在页面打开后可能变过（远程禁用名单）：加载完成时按当前生效列表补推一次（F-X-24 三端口径 A）
            transport?.onPageFinished()
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError,
        ) {
            // CSB-02：证书错误一律取消，不提供「继续访问」
            handler.cancel()
            failAwaiting()
            callbacks.onLoadFailed()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) {
                failAwaiting()
                callbacks.onLoadFailed()
            }
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            rendererGone = true
            failAwaiting()
            // 拆除正等 about:blank 时渲染进程没了：文档已不存在，按已拆确认（S2-05）
            if (retiring != null) finishRetire()
            callbacks.onLoadFailed()
            return true
        }
    }

    companion object {
        private const val HTTPS_PREFIX = "https://"
        private const val HTTP_PREFIX = "http"
        private const val FORBIDDEN = 403
        private val DOCUMENT_DESTINATIONS = setOf("iframe", "frame", "document")

        /** 看起来是文档（框架导航）的请求：Accept 含 text/html，或 Sec-Fetch-Dest 为 iframe / frame / document。 */
        internal fun isDocumentRequest(headers: Map<String, String>?): Boolean {
            val lower = headers.orEmpty().mapKeys { it.key.lowercase() }
            val accept = lower["accept"].orEmpty()
            val destination = lower["sec-fetch-dest"].orEmpty().lowercase()
            return accept.contains("text/html", ignoreCase = true) || destination in DOCUMENT_DESTINATIONS
        }

        private fun blockedResponse(): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8", FORBIDDEN, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

        private const val ABOUT_BLANK = "about:blank"
        const val EVENT_RESUME = "app.resume"
        const val EVENT_PAUSE = "app.pause"
        const val EVENT_VISIBLE = "page.visible"
        const val EVENT_AUTH = "auth.changed"
    }
}
