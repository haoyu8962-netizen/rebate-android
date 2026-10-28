package com.couli.feature.web

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.couli.core.bridge.BridgeCode
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.BridgeProtocol
import java.security.SecureRandom
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * TrustedWebView 的传输层（03 §5.2 Android 行）：
 * - H5 → 原生：`WebViewCompat.addWebMessageListener`，allowedOriginRules 只含可信源；回调给出系统判定的 sourceOrigin 与
 *   isMainFrame，原生桥层（[BridgeProtocol.decide]）再按 03 §5.2 的口径逐字判一次（机制本身不做逐字比较也不会放宽）。
 *   禁止 addJavascriptInterface。
 * - 原生 → H5：`JavaScriptReplyProxy.postMessage`（响应回到发起的那个框架；事件只发给主框架）。
 * - 启动脚本：`WebViewCompat.addDocumentStartJavaScript`，同一组 allowedOriginRules，只注入随包发布的 [bootstrapScript]
 *   （来源不是远程配置或接口，CSB-10），定义 `window.__REBATE_BRIDGE__ = { version, methods, postMessage, subscribe }`。
 * - 方法列表（F-X-24 三端口径 A）：`methods` 是只读访问器，返回冻结数组。生效配置变化（远程禁用名单增减）时
 *   [BridgeHost.watchDeclaredMethods] 交出新列表：① 换装启动脚本，之后的新文档（含刷新、前进后退）按新列表注入；② 用
 *   evaluateJavascript 把新列表推进当前主框架（只在主框架是可信源时推），页面加载完成时再推一次补上换装前后的竞态。推送经启动脚本
 *   闭包里的 `__couliBridgeMethods(token, list)`：令牌是本传输层随机生成、只拼进原生注入的脚本与原生执行的推送脚本，页面脚本拿不到，
 *   调用时令牌不符或列表不是字符串数组一律不改。页面能看到的列表只影响它自己的 has()，桥准入始终以原生生效名单为准。
 *
 * - 文档令牌（F-X-25）：启动脚本为每份文档生成随机 nonce，经握手交给原生；原生按当前主框架文档发一个随机令牌，用 evaluateJavascript 调
 *   `__couliBridgeBindDocument(nonce, token)` 绑定（nonce 不符、已绑定过都返回 false：旧文档的绑定脚本在新文档里重放无效）。主框架的
 *   桥调用带本文档令牌，令牌不是当前文档已发出的那一个（旧文档、还没绑定、伪造）一律 90500、不进宿主；绑定之前页面发起的调用在启动脚本里
 *   排队，绑定后再发。auth.changed 走专用通道 `__couliBridgeEvent(token, data)`：只有持当前令牌的文档执行，执行结果即投递确认。
 * - 切号隔离（F-X-25 I1，[isolate]）：宿主拒绝一切调用、隔离中的旧文档握手不再拿事件通道与令牌。v2 方案（D10-309）里隔离的传输层不再
 *   解除：旧 WebView 随后被拆除销毁（[dispose]），新文档一定建在新 WebView 与新的传输层上。
 *
 * 系统 WebView 不支持这两项能力时 [install] 返回 false：不注入任何东西，页面里没有 `__REBATE_BRIDGE__`，H5 SDK 自己返回 90001
 * （保守降级，不改用 addJavascriptInterface）。
 */
@Suppress("TooManyFunctions") // 安装、导航与生命周期、方法列表同步逐项一个入口
internal class BridgeTransport(
    private val webView: WebView,
    private val host: BridgeHost,
    private val gesture: GestureClock,
) {
    private var mainFrameProxy: JavaScriptReplyProxy? = null
    private var script: ScriptHandler? = null

    /** 推送方法列表的令牌（每个传输层一份，只在原生侧与注入脚本的闭包里）。 */
    private val methodsToken: String = newToken()

    /** 安装时的可信源规则（换装启动脚本、推送前核对主框架来源用）。 */
    private var originRules: Set<String> = emptySet()

    /** 当前注入 / 推送给页面的方法列表。 */
    private var methods: List<String> = emptyList()

    /**
     * 当前列表还没推进当前主文档（变化时主文档按当前配置不可信，推送被跳过）：之后配置再变、主文档恢复可信时补推（来源恢复后不滞留
     * 旧列表）。列表与上次相同且没有欠推时不重复推送。
     */
    private var pushPending = false

    /** 方法列表变化的订阅（[dispose] 时取消）。 */
    private var methodsWatch: Job? = null

    /** 主框架每换一次文档加一：旧文档的响应与事件不再送出。 */
    private var documentGeneration = 0L

    /** 已为哪一份文档发出令牌（每份文档只发一次）与令牌本身；只在主线程读写。 */
    private var issuedGeneration = NO_GENERATION
    private var issuedToken: String? = null

    /** 令牌已确认绑定到哪一份文档（绑定脚本在该文档里执行并返回 true）。 */
    private var boundGeneration = NO_GENERATION

    /** 切号隔离中（F-X-25 I1）；任何线程写。 */
    @Volatile private var fenced = false

    /** 每份文档最近一次握手带来的 nonce（S2-03：旧文档迟到的握手抢先绑定失败后，按最新 nonce 补发）；只在主线程读写。 */
    private var latestNonce: Pair<Long, String>? = null

    /** 当前文档的令牌已确认绑定（主线程）。 */
    val documentBound: Boolean
        get() = !disposed && boundGeneration != NO_GENERATION && boundGeneration == documentGeneration

    /** 当前文档的令牌绑定确认时回调（主线程，参数为文档世代）；控制器据此判断新建页的新文档已就绪。 */
    var onDocumentBound: ((Long) -> Unit)? = null

    /** 当前文档世代（主线程）。 */
    val generation: Long get() = documentGeneration

    /** 安装传输层；[originRules] 为 `scheme://host[:port]` 形式的可信源（WebView 的 allowedOriginRules 语法）。 */
    fun install(originRules: Set<String>): Boolean {
        // 两项能力都在才装：缺一项就什么都不注入（不能只有消息通道没有启动脚本，反之亦然）
        if (originRules.isEmpty() || !isSupported()) return false
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, CHANNEL, originRules) { _, message, sourceOrigin, isMainFrame, proxy ->
                onMessage(message, sourceOrigin, isMainFrame, proxy)
            }
        }
        this.originRules = originRules
        methods = host.declaredMethods
        installScript()
        methodsWatch = host.watchDeclaredMethods { latest -> onMain { onMethodsChanged(latest) } }
        return true
    }

    private fun installScript() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        script?.remove()
        script =
            WebViewCompat.addDocumentStartJavaScript(webView, bootstrapScript(host.bridgeVersion, methods, methodsToken), originRules)
    }

    /**
     * 生效配置变了：方法列表变了就换装启动脚本（之后的新文档）并推进当前主框架（已打开的页面，has() 即时变）；列表没变但此前欠推
     * （当时主文档不可信）也再试一次。
     */
    private fun onMethodsChanged(latest: List<String>) {
        if (disposed) return
        if (latest != methods) {
            methods = latest
            installScript()
            pushPending = true
        }
        if (pushPending) pushMethods()
    }

    /**
     * 把当前列表推进主框架。只推给此刻仍可信的主文档：主框架 origin 须在安装时的规则里（有原生启动脚本）且按**当前**生效配置仍可信
     * （[BridgeHost.acceptsOrigin]：页面打开后 bridge_origins 撤掉它就不再推）。evaluateJavascript 是异步执行的，导航期间可能落到
     * 下一份文档：推送脚本在页面里先逐字核对 `location.origin` 等于这里核过的 origin，不等就不调用更新函数、令牌不出现在调用里。
     */
    private fun pushMethods() {
        val origin = webView.url?.let(BridgeProtocol::originOf)?.takeIf { it in originRules && host.acceptsOrigin(it) }
        if (disposed || origin == null) return
        pushPending = false
        webView.evaluateJavascript(updateMethodsScript(methodsToken, methods, origin), null)
    }

    /** 主框架页面加载完成：再推一次当前列表（文档若是在换装启动脚本之前创建的，也补成最新）。 */
    fun onPageFinished() {
        pushMethods()
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    /**
     * 主框架**开始**一次跨文档导航（APP-04 三端共性第 29 条）：立即作废旧页面的在途调用——不等新文档提交，导航很慢或最终失败
     * （被取消、204、连不上）时旧调用同样不能再发请求、走权限流程。被作废的调用立即以 90500 回给旧页面（导航没有提交时原页面还在，
     * 不让它一直等）；事件通道与文档世代留到新文档提交（[onMainFrameNavigation]）时再换。
     */
    fun onNavigationStarting() {
        host.invalidate()
    }

    /** 主框架提交了新文档：作废旧文档的在途调用与事件通道（lessons：归属与世代）。 */
    fun onMainFrameNavigation() {
        documentGeneration += 1
        mainFrameProxy = null
        host.invalidate()
    }

    /** 本传输层没有隔离（隔离后不再解除：随 WebView 一起拆除）。 */
    private fun admitted(): Boolean = !fenced

    /** 进入切号隔离（任何线程）：宿主拒绝一切调用并作废在途调用；之后旧文档的握手不再拿通道与令牌，普通事件不再下发。 */
    fun isolate() {
        fenced = true
        host.setDocumentIsolated(true)
    }

    /** 下发事件（只发 events 白名单里的、只发给已握手的主框架；隔离中的旧文档不发）。 */
    fun emit(
        name: String,
        data: JsonObject = JsonObject(emptyMap()),
    ) {
        if (!admitted()) return
        val body = BridgeProtocol.event(name, data) ?: return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) mainFrameProxy?.postMessage(body)
    }

    /**
     * auth.changed 的专用投递（F-X-25 A，主线程）：只发给令牌已确认绑定的当前文档，经 evaluateJavascript 交给持该令牌的文档执行；
     * [completed] 的 true 表示事件已在该文档里执行（交给了它的订阅者）。不受隔离影响（隔离前已绑定的旧文档照样收到）。
     */
    fun emitAuthChanged(
        data: JsonObject,
        completed: (Boolean) -> Unit,
    ) {
        val token = issuedToken
        val body = BridgeProtocol.event(TrustedWebViewController.EVENT_AUTH, data)
        // 源被远程撤下的文档也有令牌（S2-07），事件只给当前仍可信的
        val deliverable = documentBound && currentTrustedOrigin() != null
        if (body == null || token == null || !deliverable) return completed(false)
        webView.evaluateJavascript(eventScript(token, body)) { result -> completed(result == ReplyConfirmation.EXECUTED) }
    }

    /** 当前主框架的 origin：仍在安装时的可信源规则里、按当前生效配置仍可信时给出，否则 null（主线程）。 */
    fun currentTrustedOrigin(): String? =
        webView.url?.let(BridgeProtocol::originOf)?.takeIf { !disposed && it in originRules && host.acceptsOrigin(it) }

    /** 已拆（[dispose]）：之后不再向页面送出任何回复与事件。 */
    private var disposed = false

    /**
     * 关闭本容器的那次调用（nav.open 清栈、nav.close 等）结算并回给页面之后，在主线程上执行 [action]（共性第 53 条）；没有这样的
     * 调用时立即执行。容器的拆除（[dispose]、销毁 WebView）经这里，回复不会因为传输层先拆掉而被丢弃。
     */
    fun afterClosingCallReplied(action: () -> Unit) {
        host.afterNavigationSettled {
            if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
        }
    }

    private val main = Handler(Looper.getMainLooper())

    fun dispose() {
        disposed = true
        methodsWatch?.cancel()
        methodsWatch = null
        script?.remove()
        script = null
        mainFrameProxy = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(webView, CHANNEL)
        }
        host.dispose()
    }

    @Suppress("ReturnCount") // 握手、令牌不符、正常调用逐项返回
    private fun onMessage(
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        proxy: JavaScriptReplyProxy,
    ) {
        val data = message.data ?: return
        val origin = sourceOrigin.toString()
        if (data == HELLO || data.startsWith(HELLO_PREFIX)) {
            // 握手放到主线程队列末尾再处理：WebView 的 onPageStarted 是提交时投递的消息，而消息通道是同步回调，新文档的握手可能先于
            // 它的 onPageStarted 到达；排在后面处理，握手才按新文档的世代登记（否则令牌会记在旧世代上，新文档的调用被当成无令牌）
            main.post { onHello(data.removePrefix(HELLO).removePrefix(":"), origin, isMainFrame, proxy) }
            return
        }
        val generation = documentGeneration
        val current = { generation == documentGeneration && !disposed }
        val raw = callBody(data, isMainFrame, generation)
        if (raw == null) {
            // 主框架没带本文档的令牌（旧文档、还没绑定、伪造）：90500，不进宿主（F-X-25 P08）
            BridgeProtocol.parseStructure(data.substringAfterTokenPrefix()).id?.let { id ->
                if (current() && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    proxy.postMessage(BridgeProtocol.response(id, BridgeCode.NATIVE_ERROR))
                }
            }
            return
        }
        host.handle(
            raw,
            origin,
            isMainFrame,
            gesture.recent(),
            deliver = { reply, settled -> deliverAndConfirm(reply, current(), settled) },
        ) { reply ->
            if (current() && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) proxy.postMessage(reply)
        }
    }

    /**
     * 握手：只认主框架。事件通道只给当前生效配置仍可信的源（不会落到子框架或别的 origin）；文档令牌只证明「调用出自这份主框架文档」，
     * 不授予任何能力——源被远程撤下的文档同样拿令牌，它的调用照旧进宿主、由协议准入按 90403 立即拒绝（评审 v2 r1 S2-07：不再让调用
     * 在页内排队永远挂着）。隔离中的旧文档不拿通道、不拿令牌（F-X-25 I1：hello 不能绕过隔离）；每份文档只发一次令牌，带 nonce 的握手才发。
     */
    @Suppress("ReturnCount") // 不可信 / 隔离 / 已拆、没带 nonce、这份文档已发过令牌逐项返回
    private fun onHello(
        nonce: String,
        origin: String,
        isMainFrame: Boolean,
        proxy: JavaScriptReplyProxy,
    ) {
        val generation = documentGeneration
        if (!isMainFrame || !admitted() || disposed) return
        if (host.acceptsOrigin(origin)) mainFrameProxy = proxy
        if (nonce.isEmpty()) return
        latestNonce = generation to nonce
        if (issuedGeneration == generation) return
        bind(generation, nonce)
    }

    /** 给 [generation] 这份文档发令牌并绑定 [nonce]（主线程）。 */
    private fun bind(
        generation: Long,
        nonce: String,
    ) {
        val token = newToken()
        issuedGeneration = generation
        issuedToken = token
        webView.evaluateJavascript(bindScript(nonce, token)) { result ->
            if (result == ReplyConfirmation.EXECUTED && generation == documentGeneration && !disposed) {
                boundGeneration = generation
                onDocumentBound?.invoke(generation)
            } else if (issuedGeneration == generation && token == issuedToken) {
                // 没绑上（nonce 不是这份文档的、文档已换）：作废这次发出的令牌。S2-03：旧文档迟到的握手抢在前面用掉了这一代的发放时，
                // 这份文档自己的握手已被跳过——按这一代最近一次握手的 nonce 补发一次
                issuedGeneration = NO_GENERATION
                issuedToken = null
                val latest = latestNonce?.takeIf { it.first == generation && it.second != nonce }
                val stillThisDocument = generation == documentGeneration && admitted() && !disposed
                if (latest != null && stillThisDocument) bind(generation, latest.second)
            }
        }
    }

    /**
     * 取出调用正文：主框架必须带当前文档已发出的令牌（不符为 null）；子框架照旧原文交给宿主（宿主按非主框架 90403 拒绝）。
     */
    @Suppress("ReturnCount") // 子框架、格式不对、主框架核对令牌逐项返回
    private fun callBody(
        data: String,
        isMainFrame: Boolean,
        generation: Long,
    ): String? {
        if (!data.startsWith(CALL_PREFIX)) return if (isMainFrame) null else data
        val rest = data.substring(CALL_PREFIX.length)
        val separator = rest.indexOf(':')
        if (separator <= 0) return null
        val token = rest.substring(0, separator)
        val body = rest.substring(separator + 1)
        if (!isMainFrame) return body
        // 令牌只证明归属（是当前文档发的）；隔离由宿主准入统一判（隔离中的旧文档持有效令牌同样被宿主 90500 拒绝）
        return body.takeIf { issuedGeneration == generation && token == issuedToken }
    }

    private fun String.substringAfterTokenPrefix(): String =
        if (startsWith(CALL_PREFIX)) substring(CALL_PREFIX.length).substringAfter(':') else this

    /**
     * 会触发导航的那次调用的回复（APP-04 共性第 59、62 条，Android 在 APP-05a 补上）：JavaScriptReplyProxy.postMessage 没有「已执行」回调，
     * 这条回复改用 evaluateJavascript 交给主框架启动脚本里的接收函数（与消息通道同一份处理），见 [ReplyConfirmation]。文档已换、已拆时
     * 不交回复，按「没有执行」结束（放弃导航）。
     */
    private fun deliverAndConfirm(
        reply: String,
        current: Boolean,
        settled: (Boolean) -> Unit,
    ) {
        if (!current) return settled(false)
        ReplyConfirmation.deliver(
            script = receiveScript(reply),
            evaluate = { script, callback -> webView.evaluateJavascript(script) { value -> callback(value) } },
            schedule = { delay, task -> main.postDelayed(task, delay) },
            settled = settled,
        )
    }

    companion object {
        /** 传输对象名（只在可信源的框架里出现）。 */
        const val CHANNEL = "__couliBridgeChannel"

        /** 启动脚本定义的方法列表更新函数（原生推送用，F-X-24 三端口径 A）。 */
        const val UPDATE_METHODS = "__couliBridgeMethods"

        /** 启动脚本定义的回复接收函数（evaluateJavascript 交回复用，共性第 59 条）。 */
        const val RECEIVE = "__couliBridgeReceive"

        /** 启动脚本定义的文档令牌绑定函数（F-X-25；原生私有，不是桥方法或事件）。 */
        const val BIND_DOCUMENT = "__couliBridgeBindDocument"

        /** 启动脚本定义的 auth.changed 专用接收函数（F-X-25；只认当前文档令牌）。 */
        const val AUTH_EVENT = "__couliBridgeEvent"

        /** 主框架桥调用的前缀：`__couli_call__:<令牌>:<请求>`。 */
        private const val CALL_PREFIX = "__couli_call__:"
        private const val NO_GENERATION = -1L

        /** 绑定脚本：nonce 与令牌按 JSON 字面量嵌入；绑定函数不在、nonce 不符、已绑定过都得到 false。 */
        fun bindScript(
            nonce: String,
            token: String,
        ): String =
            "(function(){var f=window.$BIND_DOCUMENT;return typeof f==='function'&&f(" + JsonPrimitive(nonce).toString() + "," +
                JsonPrimitive(token).toString() + ")===true;})();"

        /** auth.changed 投递脚本：令牌与事件正文按 JSON 字面量嵌入；接收函数不在、令牌不是本文档的得到 false。 */
        fun eventScript(
            token: String,
            body: String,
        ): String =
            "(function(){var f=window.$AUTH_EVENT;return typeof f==='function'&&f(" + JsonPrimitive(token).toString() + "," +
                JsonPrimitive(body).toString() + ")===true;})();"

        /** 等回复脚本执行完的上限（之后按结束处理）。 */
        const val CONFIRM_TIMEOUT_MS = 3_000L

        /**
         * 交回复的脚本：回复文本按 JSON 字符串字面量嵌入（不拼接未转义内容）。接收函数在时执行它并得到 true（[ReplyConfirmation.EXECUTED]），
         * 不在（启动脚本没注入、文档已换）得到 false。
         */
        fun receiveScript(reply: String): String =
            "(function(){if(!window.$RECEIVE)return false;window.$RECEIVE(" + JsonPrimitive(reply).toString() + ");return true;})();"

        private const val HELLO = "__couli_hello__"
        private const val HELLO_PREFIX = "$HELLO:"
        private const val DUPLICATE_ID_PLACEHOLDER = ""

        private const val TOKEN_BYTES = 16
        private const val HEX_MASK = 0xff
        private val random = SecureRandom()

        private fun newToken(): String {
            val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and HEX_MASK) }
        }

        /**
         * 推送方法列表的脚本：令牌、列表与期望的文档 origin 都按 JSON 字面量嵌入。执行时的文档 origin（`location.origin`，页面改不了）
         * 不等于 [origin]（导航期间落到了别的文档）或更新函数不在（非原生注入的文档）时什么都不做。
         */
        fun updateMethodsScript(
            token: String,
            methods: List<String>,
            origin: String,
        ): String =
            "(function(){if(location.origin!==" + JsonPrimitive(origin).toString() + ")return false;" +
                "var f=window.$UPDATE_METHODS;return typeof f==='function'&&f(" +
                JsonPrimitive(token).toString() + "," + JsonArray(methods.map(::JsonPrimitive)).toString() + ")===true;})();"

        fun isSupported(): Boolean =
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        /**
         * 随包发布的桥启动脚本（03 §5.2）。拼入契约版本、本端声明的方法名（包内契约去掉远程禁用的名字，只会是契约方法名的子集）与
         * 推送令牌（[token]，原生随机生成），不含其他远程内容。
         */
        fun bootstrapScript(
            version: Int,
            methods: List<String>,
            token: String = newToken(),
        ): String {
            val methodList = JsonArray(methods.map(::JsonPrimitive)).toString()
            return BOOTSTRAP_TEMPLATE
                .replace("__VERSION__", version.toString())
                .replace("__METHODS__", methodList)
                .replace("__TOKEN__", JsonPrimitive(token).toString())
                .replace("__UPDATE_METHODS__", UPDATE_METHODS)
                .replace("__CHANNEL__", CHANNEL)
                .replace("__RECEIVE__", RECEIVE)
                .replace("__BIND__", BIND_DOCUMENT)
                .replace("__AUTH_EVENT__", AUTH_EVENT)
                .replace("__CALL__", CALL_PREFIX)
                .replace("__HELLO__", HELLO)
                .replace("__DUPLICATE__", BridgeProtocol.response(DUPLICATE_ID_PLACEHOLDER, BridgeCode.INVALID_PARAMS))
        }

        private val BOOTSTRAP_TEMPLATE =
            """
            (function () {
              'use strict';
              if (window.__REBATE_BRIDGE__) return;
              var channel = window.__CHANNEL__;
              if (!channel) return;
              var post = channel.postMessage.bind(channel);
              var isTop = window === window.top;
              // per-document nonce and token (F-X-25): native binds a token to exactly this document; main-frame calls carry it
              var docNonce = '';
              try {
                var nonceBytes = new Uint8Array(16);
                window.crypto.getRandomValues(nonceBytes);
                for (var b = 0; b < nonceBytes.length; b++) docNonce += (nonceBytes[b] + 256).toString(16).slice(1);
              } catch (error) {
                docNonce = String(Math.random()).slice(2) + String(Date.now());
              }
              var docToken = null;
              var outbox = [];
              function send(text) {
                if (!isTop) { post(text); return; }
                if (docToken === null) { outbox.push(text); return; }
                post('__CALL__' + docToken + ':' + text);
              }
              // no prototype: ids such as __proto__ or constructor are plain own keys (A-R2-07, lesson 50)
              var pending = Object.create(null);
              var listeners = [];
              // native-only method list updates (F-X-24): the token lives in this closure; builtins captured before page scripts run
              var methods = Object.freeze(__METHODS__);
              var methodsToken = __TOKEN__;
              var isArray = Array.isArray;
              var freeze = Object.freeze;
              var defineProperty = Object.defineProperty;
              var create = Object.create;
              Object.defineProperty(window, '__UPDATE_METHODS__', {
                value: function (token, list) {
                  if (token !== methodsToken || !isArray(list)) return false;
                  var next = [];
                  for (var i = 0; i < list.length; i++) {
                    if (typeof list[i] !== 'string') return false;
                    // own data property via the captured define (no inherited Array.prototype[i] setter runs); the descriptor has
                    // no prototype, so Object.prototype.get / set added by the page cannot leak into it
                    var item = create(null);
                    item.value = list[i];
                    item.writable = true;
                    item.enumerable = true;
                    item.configurable = true;
                    defineProperty(next, i, item);
                  }
                  methods = freeze(next);
                  return true;
                },
                writable: false,
                configurable: false
              });
              function receive(data) {
                var message;
                try { message = JSON.parse(data); } catch (error) { return; }
                if (!message || typeof message !== 'object') return;
                if (typeof message.event === 'string') {
                  listeners.slice().forEach(function (listener) {
                    try { listener(message); } catch (error) { setTimeout(function () { throw error; }); }
                  });
                  return;
                }
                if (typeof message.id === 'string' && (message.id in pending)) {
                  var resolve = pending[message.id];
                  delete pending[message.id];
                  resolve(message);
                }
              }
              channel.onmessage = function (event) { receive(event.data); };
              // replies of navigating calls arrive via evaluateJavascript, so the host knows when they have run (lesson 59)
              Object.defineProperty(window, '__RECEIVE__', { value: function (data) { receive(data); }, writable: false, configurable: false });
              Object.defineProperty(window, '__BIND__', {
                value: function (nonce, token) {
                  if (docToken !== null || nonce !== docNonce || typeof token !== 'string' || token.length === 0) return false;
                  docToken = token;
                  var queued = outbox;
                  outbox = [];
                  for (var q = 0; q < queued.length; q++) post('__CALL__' + token + ':' + queued[q]);
                  return true;
                },
                writable: false,
                configurable: false
              });
              Object.defineProperty(window, '__AUTH_EVENT__', {
                value: function (token, data) {
                  if (docToken === null || token !== docToken || typeof data !== 'string') return false;
                  receive(data);
                  return true;
                },
                writable: false,
                configurable: false
              });
              var bridge = {
                version: __VERSION__,
                get methods() { return methods; },
                postMessage: function (request) {
                  // duplicate in-flight id: answer 90002 locally, do not send, keep the pending call
                  if (request && typeof request.id === 'string' && (request.id in pending)) {
                    var duplicate = __DUPLICATE__;
                    duplicate.id = request.id;
                    return Promise.resolve(duplicate);
                  }
                  return new Promise(function (resolve) {
                    if (request && typeof request.id === 'string') pending[request.id] = resolve;
                    send(JSON.stringify(request));
                  });
                },
                subscribe: function (listener) {
                  listeners.push(listener);
                  return function () {
                    var index = listeners.indexOf(listener);
                    if (index >= 0) listeners.splice(index, 1);
                  };
                }
              };
              Object.defineProperty(window, '__REBATE_BRIDGE__', { value: Object.freeze(bridge), writable: false, configurable: false });
              if (isTop) post('__HELLO__:' + docNonce);
            })();
            """.trimIndent()
    }
}

/**
 * 回复脚本的执行确认（APP-04 共性第 59、62 条；Android 第 1 轮评审 R1-05）：把 [script] 交给 evaluate，结束时调一次 settled——
 * - evaluate 的结果回调兑现且结果是 [EXECUTED]（接收函数确实在页面里执行完）→ settled(true)，BridgeHost 随后复核并导航；
 * - 回调结果不是 [EXECUTED]（接收函数不在）→ settled(false)；
 * - [timeoutMillis] 内没有回调（渲染线程繁忙、进程卡住）→ settled(false)：放弃这次导航，不当作执行成功；之后迟到的回调不再算数。
 */
internal object ReplyConfirmation {
    const val EXECUTED = "true"

    fun deliver(
        script: String,
        evaluate: (script: String, callback: (String?) -> Unit) -> Unit,
        schedule: (delayMillis: Long, task: () -> Unit) -> Unit,
        settled: (executed: Boolean) -> Unit,
        timeoutMillis: Long = BridgeTransport.CONFIRM_TIMEOUT_MS,
    ) {
        val once =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val finish = { executed: Boolean -> if (once.compareAndSet(false, true)) settled(executed) }
        evaluate(script) { value -> finish(value == EXECUTED) }
        schedule(timeoutMillis) { finish(false) }
    }
}
