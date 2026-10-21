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

    /** 下发事件（只发 events 白名单里的、只发给已握手的主框架）。 */
    fun emit(
        name: String,
        data: JsonObject = JsonObject(emptyMap()),
    ) {
        val body = BridgeProtocol.event(name, data) ?: return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) mainFrameProxy?.postMessage(body)
    }

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

    private fun onMessage(
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        proxy: JavaScriptReplyProxy,
    ) {
        val data = message.data ?: return
        val origin = sourceOrigin.toString()
        if (data == HELLO) {
            // 握手只认主框架与可信源：事件通道不会落到子框架或别的 origin
            if (isMainFrame && host.acceptsOrigin(origin)) mainFrameProxy = proxy
            return
        }
        val generation = documentGeneration
        val current = { generation == documentGeneration && !disposed }
        host.handle(
            data,
            origin,
            isMainFrame,
            gesture.recent(),
            deliver = { reply, settled -> deliverAndConfirm(reply, current(), settled) },
        ) { reply ->
            if (current() && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) proxy.postMessage(reply)
        }
    }

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

        /** 等回复脚本执行完的上限（之后按结束处理）。 */
        const val CONFIRM_TIMEOUT_MS = 3_000L

        /**
         * 交回复的脚本：回复文本按 JSON 字符串字面量嵌入（不拼接未转义内容）。接收函数在时执行它并得到 true（[ReplyConfirmation.EXECUTED]），
         * 不在（启动脚本没注入、文档已换）得到 false。
         */
        fun receiveScript(reply: String): String =
            "(function(){if(!window.$RECEIVE)return false;window.$RECEIVE(" + JsonPrimitive(reply).toString() + ");return true;})();"

        private const val HELLO = "__couli_hello__"
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
                    channel.postMessage(JSON.stringify(request));
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
              if (window === window.top) channel.postMessage('__HELLO__');
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
