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
 *
 * 系统 WebView 不支持这两项能力时 [install] 返回 false：不注入任何东西，页面里没有 `__REBATE_BRIDGE__`，H5 SDK 自己返回 90001
 * （保守降级，不改用 addJavascriptInterface）。
 */
internal class BridgeTransport(
    private val webView: WebView,
    private val host: BridgeHost,
    private val gesture: GestureClock,
) {
    private var mainFrameProxy: JavaScriptReplyProxy? = null
    private var script: ScriptHandler? = null

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
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            script =
                WebViewCompat.addDocumentStartJavaScript(webView, bootstrapScript(host.bridgeVersion, host.declaredMethods), originRules)
        }
        return true
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
        host.handle(data, origin, isMainFrame, gesture.recent()) { reply ->
            val current = generation == documentGeneration && !disposed
            if (current && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) proxy.postMessage(reply)
        }
    }

    companion object {
        /** 传输对象名（只在可信源的框架里出现）。 */
        const val CHANNEL = "__couliBridgeChannel"
        private const val HELLO = "__couli_hello__"
        private const val DUPLICATE_ID_PLACEHOLDER = ""

        fun isSupported(): Boolean =
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        /** 随包发布的桥启动脚本（03 §5.2）。只拼入契约版本与本端声明的方法名（来自包内契约），不含任何远程内容。 */
        fun bootstrapScript(
            version: Int,
            methods: List<String>,
        ): String {
            val methodList = JsonArray(methods.map(::JsonPrimitive)).toString()
            return BOOTSTRAP_TEMPLATE
                .replace("__VERSION__", version.toString())
                .replace("__METHODS__", methodList)
                .replace("__CHANNEL__", CHANNEL)
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
              channel.onmessage = function (event) {
                var message;
                try { message = JSON.parse(event.data); } catch (error) { return; }
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
              };
              var bridge = {
                version: __VERSION__,
                methods: Object.freeze(__METHODS__),
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
