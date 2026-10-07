package com.couli.feature.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.couli.core.bridge.AuthPages
import com.couli.core.bridge.ExternalNavAction
import com.couli.core.bridge.ExternalNavDecision
import com.couli.core.bridge.ExternalNavPolicy
import com.couli.core.bridge.NavHostSample
import com.couli.core.bridge.ProductIntentUrl
import com.couli.core.bridge.WebConfigReader
import com.couli.core.clientcore.ClientEvent
import com.couli.core.clientcore.ClientEvents
import com.couli.core.clientcore.ExternalPageBlockedNavFrame
import com.couli.core.clientcore.ExternalPageBlockedNavKind
import com.couli.core.clientcore.ExternalPageNavHostFrame
import com.couli.core.clientcore.ExternalPageUnionHostCategory
import com.couli.core.clientcore.ExternalPageUnionHostFrame
import com.couli.core.clientcore.ExternalPageUnionHostOutcome
import com.couli.core.clientcore.ExternalPageUnionHostUnionPlatform
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.serialization.json.JsonObject

/** 导航发生在哪一层（与 events.yaml 的 frame 同名）。 */
enum class NavFrame(
    val raw: String,
) {
    INITIAL("initial"),
    MAIN("main"),
    SUBFRAME("subframe"),
    NEW_WINDOW("new_window"),
}

/**
 * 判定之外的接线条件（ExternalNavPolicy 的签名里没有，rule-gaps 第 5 条）：
 * @property consented 已同意隐私政策（基本模式与未决定时商品页不交识别，只拦下）；
 * @property productIntercept /v1/config features.flags `external_page.product_intercept`（关闭时商品页只拦下）。
 * `external_page.union_host_block` 关闭时是否放行平台页面：TODO(N-22) 未定，保守地照拦不误。
 */
data class ExternalNavFlags(
    val consented: Boolean,
    val productIntercept: Boolean,
    /** /v1/config external_page.nav_host_sample_bp（万分比；没有下发时 1000，显式 0 = 不报，共性第 39 条）。 */
    val navHostSampleBp: Int = WebConfigReader.DEFAULT_NAV_HOST_SAMPLE_BP,
)

/** ExternalWebView 交给外层（页面）的事件；都在主线程回调。 */
interface ExternalWebCallbacks {
    /** 拦下（平台页面或子框架里的商品页）：在容器自己的提示层提示，原页面不动。 */
    fun onBlocked(
        decision: ExternalNavDecision,
        frame: NavFrame,
    )

    /**
     * 商品页交原生识别（Router 意图；识别成功后 ProductDetail 压在容器之上）。[url] 已去掉片段；[platform] 是命中规则的平台。
     * 发不出去或识别不了时由外层给出明确的失败提示（不能只写日志）。
     */
    fun onProductIntent(
        url: String,
        platform: String?,
    )

    /** 主框架完成一次导航：标题栏显示 [host]，[canOpenInBrowser] 决定是否显示【在浏览器打开】。 */
    fun onMainFrameChanged(
        host: String,
        canOpenInBrowser: Boolean,
    )

    fun onLoadFailed()

    /** 下载被取消（[canOpenInBrowser] 时提示去浏览器下载，否则只说明不能下载）。 */
    fun onDownloadBlocked()

    /** 容器无法保证规则（不支持 Cookie 隔离、规则表为空）：不在 App 内打开第三方页面（保守降级）。 */
    fun onUnavailable()

    /** 页面里的视频进入全屏（第 25 条）：外层隐藏标题栏与系统栏、铺满显示 [view]；[exit] 由外层在返回键时调用以退出全屏。 */
    fun onEnterFullscreen(
        view: View,
        exit: () -> Unit,
    ) = exit()

    /** 退出全屏：外层恢复标题栏与布局。 */
    fun onExitFullscreen() = Unit
}

/**
 * 第三方页容器（ExternalWebView，03 §5.1、BR-ATTR-29、CSB-03/10）：
 * - 不注入任何东西：不调用执行脚本、文档启动脚本、消息通道、样式注入的接口，不改写页面（本文件不得出现这些调用，有源码测试）；
 * - Cookie 隔离：放在独立的 WebView 配置文件里（androidx.webkit MULTI_PROFILE）；系统 WebView 不支持时不在 App 内打开；
 * - 导航判定：主框架导航与重定向的每一跳（shouldOverrideUrlLoading）、子框架里的非 https 跳转（同一回调）、子框架的 https 文档
 *   请求（shouldInterceptRequest，在发出前拦）都经 [ExternalNavPolicy]；新窗口请求（window.open、target=_blank）经
 *   onCreateWindow 交探针取出目标地址，按 NEW_WINDOW 类型判定与上报，不另开窗口（放行的在当前容器加载）；
 * - 只放行 https，其余取消并上报 external_page_blocked_nav（只记类别）；下载一律取消；证书错误一律取消（CSB-02）；
 * - 标题栏固定显示当前主框架的主机名；系统浏览器出口按 can_open_in_browser 每次导航后重算。
 */
@Suppress("TooManyFunctions", "LongParameterList") // 判定、规则表、开关、埋点、回调逐项注入
class ExternalWebViewController(
    private val webView: WebView,
    private val policy: ExternalNavPolicy,
    private val linkPatterns: () -> JsonObject?,
    private val flags: () -> ExternalNavFlags,
    private val track: (ClientEvent) -> Unit,
    private val callbacks: ExternalWebCallbacks,
    appVersion: String,
    /** external_page_nav_host 的抽样骰子：0 until 10000（测试可注入）。 */
    private val roll: () -> Int = { Random.nextInt(NavHostSample.ROLL_BOUND) },
) {
    private val main = Handler(Looper.getMainLooper())

    /** Cookie 隔离与规则表都可用；为 false 时 [open] 不加载任何页面。 */
    val available: Boolean

    @Volatile
    private var currentUrl: String? = null

    init {
        val isolated = isolateCookies(webView)
        if (isolated) {
            webView.applyContainerSettings(appVersion)
            // 新窗口请求（window.open、target=_blank）保留 NEW_WINDOW 类型交统一判定（APP-04 三端共性第 20 条）：系统把请求交给
            // onCreateWindow，由一个不发任何请求的探针 WebView 取出目标地址；不另开窗口，放行的目标在当前容器加载
            webView.settings.setSupportMultipleWindows(true)
            webView.webChromeClient = Chrome()
            webView.webViewClient = Client()
            webView.setDownloadListener { _, _, _, _, _ -> onDownload() }
        }
        available = isolated
    }

    /** 打开第一个 URL（同样经过判定，frame=initial）。 */
    fun open(url: String) {
        if (!available || policy.ruleCount(linkPatterns()) == 0) {
            // TODO(N-21)：包内快照的规则为空时没有可用规则表；保守地不在 App 内打开第三方页面（BR-ATTR-29 细则）
            callbacks.onUnavailable()
            return
        }
        val decision = onDocument(url, NavFrame.INITIAL)
        if (decision.actionRaw == ExternalNavAction.LOAD) webView.loadUrl(url) else apply(decision, url, NavFrame.INITIAL)
    }

    fun reload() = webView.reload()

    /** 错误页的【重试】：还没加载过任何页面（初始地址被拦、容器不可用）时重新走一遍 [open]（重新判规则），否则刷新。 */
    fun retry(url: String) {
        val current = webView.url
        if (current.isNullOrEmpty() || current == ABOUT_BLANK) open(url) else reload()
    }

    fun canGoBack(): Boolean = webView.canGoBack()

    fun goBack() = webView.goBack()

    /**
     * 【在浏览器打开】点击时（主线程）：交出的是主框架的当前地址——[doUpdateVisitedHistory] 等回调跟着 pushState、replaceState、
     * 片段导航与历史返回更新（R2-04、共性第 48 条）；点击时对它与 WebView 此刻报告的地址再判一次，都不命中才交出（不带任何归因参数）。
     */
    fun browserTarget(): String? {
        val committed = currentUrl ?: return null
        // 两处地址都要过：系统回调记下的最新地址（含页内跳转），与 WebView 此刻报告的地址；任一命中规则就不交出（宁可不给）
        val live = webView.url?.takeUnless { it.isEmpty() || it == ABOUT_BLANK }
        val patterns = linkPatterns()
        return committed.takeIf { listOfNotNull(committed, live).all { policy.canOpenInBrowser(it, patterns) } }
    }

    /** 主框架当前地址变了（开始导航、文档提交、页内跳转）：记下它，重算标题栏主机名与浏览器出口。 */
    private fun mainFrameAt(url: String?) {
        currentUrl = url
        val host = url?.let { runCatching { java.net.URI(it).host }.getOrNull() }.orEmpty()
        callbacks.onMainFrameChanged(host, url != null && policy.canOpenInBrowser(url, linkPatterns()))
    }

    fun destroy() {
        webView.stopLoading()
        webView.destroy()
    }

    private fun decide(
        url: String,
        frame: NavFrame,
    ): ExternalNavDecision {
        val decision = policy.decide(url, frame != NavFrame.SUBFRAME, frame == NavFrame.NEW_WINDOW, linkPatterns())
        val conditions = flags()
        val productAllowed = conditions.consented && conditions.productIntercept
        return when {
            // 授权页不进容器（初始地址与每一次导航；APP-04 三端共性第 7 条）：登记表未补齐时按内置表与 OAuth 路径保守拦下
            decision.actionRaw != ExternalNavAction.CANCEL_REPORT && AuthPages.isAuthPage(url) -> {
                ExternalNavDecision(ExternalNavAction.BLOCK, REASON_AUTH_PAGE, hit = decision.hit)
            }

            decision.actionRaw == ExternalNavAction.TO_NATIVE_PRODUCT && !productAllowed -> {
                decision.copy(actionRaw = ExternalNavAction.BLOCK)
            }

            else -> {
                decision
            }
        }
    }

    /**
     * 统一的文档导航入口（BR-ATTR-29；APP-04 三端共性第 12、32 条，Android 第 1 轮评审 R1-06）：判定，并对**每一次**文档导航按采样率
     * 上报 external_page_nav_host——初始地址、点击与脚本发起的跳转、服务器重定向的每一跳、子框架文档（含 iframe.src 自动加载）、
     * 新窗口，不看是否有用户手势。同一次导航可能先后经过 shouldOverrideUrlLoading 与 shouldInterceptRequest（主框架），用
     * [firstSighting] 去重；普通子资源不经这里（只按规则拦，不上报）。
     */
    private fun onDocument(
        url: String,
        frame: NavFrame,
    ): ExternalNavDecision {
        val decision = decide(url, frame)
        if (firstSighting(url, frame)) sampleNavHost(url, frame, decision)
        return decision
    }

    /** 同一框架层（主框架类：初始、主框架、新窗口；子框架）同一地址在 [DEDUPE_MS] 内只算一次文档导航。 */
    private fun firstSighting(
        url: String,
        frame: NavFrame,
    ): Boolean {
        val key = (if (frame == NavFrame.SUBFRAME) "sub|" else "main|") + url
        val now = SystemClock.uptimeMillis()
        synchronized(recentDocuments) {
            recentDocuments.entries.removeAll { now - it.value > DEDUPE_MS }
            if (recentDocuments.containsKey(key)) return false
            recentDocuments[key] = now
            return true
        }
    }

    /** 最近见过的文档导航（键 → 首次时刻，单调时钟）；三个回调线程共用。 */
    private val recentDocuments = LinkedHashMap<String, Long>()

    /** 按采样率上报 external_page_nav_host（只记注册域、框架类型、是否命中规则）。 */
    private fun sampleNavHost(
        url: String,
        frame: NavFrame,
        decision: ExternalNavDecision,
    ) {
        if (!NavHostSample.sampled(flags().navHostSampleBp, roll())) return
        val domain = NavHostSample.registeredDomain(runCatching { java.net.URI(url).host }.getOrNull()) ?: return
        val eventFrame = ExternalPageNavHostFrame.entries.first { it.raw == frame.raw }
        // hit 是「是否命中链接形态表」，与去向分开（R2-06）：只命中 promo 而放行的同样为 true
        track(ClientEvents.externalPageNavHost(PAGE, domain, eventFrame, hit = decision.hit))
    }

    /** 不加载时的处理（主线程）。 */
    private fun apply(
        decision: ExternalNavDecision,
        url: String,
        frame: NavFrame,
    ) {
        if (decision.reason == ExternalNavPolicy.RULES_UNAVAILABLE) {
            // 规则表在运行中变空或变坏（APP-04 三端共性第 27 条）：「没有匹配」不当放行——停止加载，整个容器换成降级页
            webView.stopLoading()
            callbacks.onUnavailable()
            return
        }
        when (decision.actionRaw) {
            ExternalNavAction.CANCEL_REPORT -> {
                reportBlockedNav(decision.reason, frame)
            }

            ExternalNavAction.TO_NATIVE_PRODUCT -> {
                reportUnionHost(decision, frame)
                callbacks.onProductIntent(ProductIntentUrl.withoutFragment(url), decision.match?.platform)
            }

            ExternalNavAction.BLOCK -> {
                reportUnionHost(decision, frame)
                callbacks.onBlocked(decision, frame)
            }
        }
    }

    /** 新窗口请求的目标（主线程）：按 NEW_WINDOW 判定；放行的在当前容器加载，不另开窗口。 */
    private fun onNewWindow(url: String) {
        val decision = onDocument(url, NavFrame.NEW_WINDOW)
        if (decision.actionRaw == ExternalNavAction.LOAD) webView.loadUrl(url) else apply(decision, url, NavFrame.NEW_WINDOW)
    }

    private fun onDownload() {
        reportBlockedNav(KIND_DOWNLOAD, NavFrame.MAIN)
        callbacks.onDownloadBlocked()
    }

    private fun reportBlockedNav(
        kind: String?,
        frame: NavFrame,
    ) {
        val eventKind = ExternalPageBlockedNavKind.entries.firstOrNull { it.raw == kind } ?: ExternalPageBlockedNavKind.OTHER_SCHEME
        val eventFrame = ExternalPageBlockedNavFrame.entries.first { it.raw == frame.raw }
        track(ClientEvents.externalPageBlockedNav(page = PAGE, kind = eventKind, frame = eventFrame))
    }

    @Suppress("ReturnCount")
    private fun reportUnionHost(
        decision: ExternalNavDecision,
        frame: NavFrame,
    ) {
        val match = decision.match ?: return
        val platform = ExternalPageUnionHostUnionPlatform.entries.firstOrNull { it.raw == match.platform } ?: return
        val category = ExternalPageUnionHostCategory.entries.firstOrNull { it.raw == match.category } ?: return
        val eventFrame = ExternalPageUnionHostFrame.entries.first { it.raw == frame.raw }
        // 交识别的结果（parse_ok / parse_failed）由识别流程另记；这里只记拦下
        if (decision.actionRaw == ExternalNavAction.BLOCK) {
            track(ClientEvents.externalPageUnionHost(PAGE, platform, category, eventFrame, ExternalPageUnionHostOutcome.BLOCK))
        }
    }

    /** 新窗口请求：不建可见窗口，交给探针取目标地址（[NewWindowProbe]）；视频全屏交外层（第 25 条）。 */
    private inner class Chrome : WebChromeClient() {
        override fun onShowCustomView(
            view: View,
            callback: CustomViewCallback,
        ) {
            callbacks.onEnterFullscreen(view) { callback.onCustomViewHidden() }
        }

        override fun onHideCustomView() {
            callbacks.onExitFullscreen()
        }

        @Suppress("ReturnCount") // 不是 WebViewTransport、探针放不进隔离配置文件、交出探针
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val probe = WebView(view.context)
            // 探针同样放进隔离的配置文件、不开脚本；它的任何请求都在发出前拦下
            if (!isolateCookies(probe)) {
                probe.destroy()
                return false
            }
            probe.settings.javaScriptEnabled = false
            val finished = AtomicBoolean(false)
            val finish = { if (finished.compareAndSet(false, true)) probe.destroy() }
            probe.webViewClient =
                NewWindowProbe { url ->
                    main.post {
                        onNewWindow(url)
                        finish()
                    }
                }
            transport.webView = probe
            resultMsg.sendToTarget()
            // 取不到地址（如 about:blank 后由脚本写入的窗口）：到时销毁，什么都不加载
            main.postDelayed({ finish() }, PROBE_TIMEOUT_MS)
            return true
        }
    }

    // 新窗口的探针：只取第一个目标地址，不加载、不发出任何请求。onRenderProcessGone 已实现；该检查认不出 Kotlin 内部类里的覆写
    @SuppressLint("MissingOnRenderProcessGone")
    private class NewWindowProbe(
        private val onTarget: (String) -> Unit,
    ) : WebViewClient() {
        private val taken = AtomicBoolean(false)

        private fun take(url: String?) {
            if (url.isNullOrEmpty() || url == ABOUT_BLANK) return
            if (taken.compareAndSet(false, true)) onTarget(url)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            take(request.url.toString())
            return true
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse {
            if (request.isForMainFrame) take(request.url.toString())
            return blockedResponse()
        }

        override fun onPageStarted(
            view: WebView,
            url: String?,
            favicon: Bitmap?,
        ) {
            view.stopLoading()
            take(url)
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError,
        ) = handler.cancel()

        /** 探针的渲染进程没了：什么都不做（探针只取地址，随后销毁）。 */
        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean = true
    }

    // onRenderProcessGone 已实现（返回 true 并显示错误页）；该检查认不出 Kotlin 内部类里的覆写
    @SuppressLint("MissingOnRenderProcessGone")
    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url.toString()
            val frame = if (request.isForMainFrame) NavFrame.MAIN else NavFrame.SUBFRAME
            // 点击、脚本跳转、服务器重定向的每一跳都经这里：一律走统一入口（判定 + 抽样），不看手势
            val decision = onDocument(url, frame)
            if (decision.actionRaw == ExternalNavAction.LOAD) return false
            apply(decision, url, frame)
            return true
        }

        /**
         * 发出前的最后一道（后台线程）：主框架文档请求（含没经过 shouldOverrideUrlLoading 的表单 POST、前进后退、刷新）与子框架的
         * https 请求。
         * - 主框架 **POST 一律取消**（R3-02、编排者裁定「看不到每一跳就在第一跳取消」(b)、共性第 57 条）：POST 导航不经
         *   shouldOverrideUrlLoading，这里也只回调第一跳，服务端 307 / 308 保持 POST 重定向进平台域或授权页时拿不到后续各跳的判定；
         *   所以不论第一跳是否允许都不发出，回 204、原页面保留。命中规则的仍按判定提示；允许地址的 POST 静默取消。
         * - 主框架被拦：回 **204 No Content**（R1-07、共性第 37 条）——按 HTML 导航规范，204 / 205 的导航响应不提交新文档，WebView
         *   放弃这次导航，原页面（DOM、表单、地址、历史）保持不动；不能用替代文档（如空白 403）冒充取消。仪器测试
         *   `externalMainFramePostToAPlatformIsCancelledAndTheOriginalPageStays` 证明原 DOM、地址与历史长度都不变。
         * - 子框架文档（看起来是文档：Accept 含 text/html 或 Sec-Fetch-Dest 为 iframe / frame）**一律取消**（编排者裁定，依据
         *   03 §5.1 与 BR-ATTR-29 的保守降级）：Android 拿不到子框架服务器重定向第二跳的回调，第一跳放行就可能被重定向进平台页面，
         *   所以不论命不命中都不发出，主框架照常。仍走统一入口抽样（iframe.src 自动加载也在这里，第 32 条）；只有命中平台规则
         *   （或授权页、非 https）的按判定处理——平台页面给提示与「去搜索」（第 31 条）；其余被取消的子框架不提示、不给「去搜索」。
         * - 子资源：命中平台规则的一律不发（宁可多拦），其余照常。
         */
        @Suppress("ReturnCount")
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url.toString()
            // 只管网络请求：data: 等非网络加载不经这里发出（页面发起的非 https 导航由 shouldOverrideUrlLoading 取消）
            if (!url.startsWith(HTTP_PREFIX, ignoreCase = true)) return null
            if (request.isForMainFrame) {
                val decision = onDocument(url, NavFrame.MAIN)
                // 主框架 POST（表单提交）一律在第一跳取消（R3-02、共性第 57 条）：POST 不经 shouldOverrideUrlLoading，这里又只看得到
                // 第一跳，307 / 308 保持 POST 重定向进平台域或授权页时拦不到后续各跳
                val post = !request.method.equals(METHOD_GET, ignoreCase = true)
                if (decision.actionRaw == ExternalNavAction.LOAD && !post) return null
                if (decision.actionRaw != ExternalNavAction.LOAD) main.post { apply(decision, url, NavFrame.MAIN) }
                return noContentResponse()
            }
            if (TrustedWebViewController.isDocumentRequest(request.requestHeaders)) {
                val decision = onDocument(url, NavFrame.SUBFRAME)
                if (decision.actionRaw != ExternalNavAction.LOAD) main.post { apply(decision, url, NavFrame.SUBFRAME) }
                return blockedResponse()
            }
            if (!url.startsWith(HTTPS_PREFIX, ignoreCase = true)) return null
            val decision = decide(url, NavFrame.SUBFRAME)
            return if (decision.actionRaw == ExternalNavAction.LOAD) null else blockedResponse()
        }

        override fun onPageStarted(
            view: WebView,
            url: String?,
            favicon: Bitmap?,
        ) = mainFrameAt(url)

        /**
         * 主框架每次提交历史条目都会回调（R2-04、共性第 48 条）：跨文档导航，也包括 pushState、replaceState、片段导航与历史返回这类
         * 同文档跳转（它们不触发 onPageStarted）。不向页面注入任何脚本，只按系统回调的地址重算。
         */
        override fun doUpdateVisitedHistory(
            view: WebView,
            url: String?,
            isReload: Boolean,
        ) {
            if (url != null && url != currentUrl) mainFrameAt(url)
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError,
        ) {
            // CSB-02：证书错误一律取消，不提供「继续访问」
            handler.cancel()
            callbacks.onLoadFailed()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) callbacks.onLoadFailed()
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            callbacks.onLoadFailed()
            return true
        }
    }

    companion object {
        /** 第三方页容器的 WebView 配置文件名（与默认配置文件隔离 Cookie、存储）。 */
        const val PROFILE = "couli_external"
        private const val PAGE = "ExternalPage"
        const val REASON_AUTH_PAGE = "auth_page"
        private const val KIND_DOWNLOAD = "download"
        private const val HTTPS_PREFIX = "https://"
        private const val HTTP_PREFIX = "http"
        private const val DEDUPE_MS = 3_000L
        private const val FORBIDDEN = 403
        private const val ABOUT_BLANK = "about:blank"
        private const val PROBE_TIMEOUT_MS = 5_000L

        /** 系统 WebView 是否支持多配置文件（Cookie 隔离的前提）。 */
        fun cookieIsolationSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

        private fun isolateCookies(webView: WebView): Boolean {
            if (!cookieIsolationSupported()) return false
            return runCatching {
                ProfileStore.getInstance().getOrCreateProfile(PROFILE)
                WebViewCompat.setProfile(webView, PROFILE)
            }.isSuccess
        }

        private fun blockedResponse(): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8", FORBIDDEN, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

        /** 主框架导航的取消：204 不提交新文档，原页面保持不动（R1-07）。 */
        internal fun noContentResponse(): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8", NO_CONTENT, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))

        private const val NO_CONTENT = 204
        private const val METHOD_GET = "GET"
    }
}
