package com.couli.app.inbound

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.couli.app.MainActivity
import com.couli.feature.trade.WebCodeCallbackParser
import com.couli.feature.trade.WebCodeCallbackSink
import com.couli.generated.InboundCallbacks

/** Application 实现它，把 couli://auth/taobao 回跳交给进程内收件箱（测试的 Application 注入假实现）。 */
interface WebCodeCallbackSinkProvider {
    fun webCodeCallbackSink(): WebCodeCallbackSink
}

/**
 * couli 入站中转（设计稿 §3.1、§5.3；仿 ShareReceiverActivity；intent-filter 由 tools/codegen 生成到 core/generated 的清单）。onCreate 里：
 * 1. 从最近任务恢复（FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY）、savedInstanceState 不为空、action 不是 VIEW、没有 data、scheme 不在
 *    [InboundCallbacks.ANDROID_CUSTOM_SCHEMES]：都不处理；
 * 2. `couli://auth/…`：交给 [WebCodeCallbackParser]（只认 `couli://auth/taobao`），解析得到的回跳当场交给 [WebCodeCallbackSinkProvider] 的收件箱，
 *    解析不过的丢弃；`couli://r/…`：原样转给 MainActivity（由 MainActivity 照旧交给路由）；其他 host 丢弃；
 * 3. 清掉自身 Intent；
 * 4. 以 NEW_TASK | CLEAR_TOP | SINGLE_TOP 拉起 MainActivity（把 App 带回前台、关掉叠在上面的 Custom Tab）——除 `couli://r/` 外**不带 data**，
 *    任何情况下都不带 code / state；然后 finish。
 * 只读 data，不读 extras；回跳地址不交给路由、不进路由轨迹、不打日志。
 */
class InboundSchemeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = intent
        val fromHistory = incoming != null && (incoming.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        val data = incoming?.takeIf { savedInstanceState == null && !fromHistory && it.action == Intent.ACTION_VIEW }?.data
        val forward = data?.takeIf(::isOwnScheme)?.let(::handle)
        // 回跳地址不留在本 Activity 的 Intent 里（noHistory、excludeFromRecents、persistNever 之外再清一次）
        intent = Intent()
        startActivity(mainIntent(forward))
        finish()
    }

    private fun isOwnScheme(data: Uri): Boolean {
        val scheme = data.scheme ?: return false
        return InboundCallbacks.ANDROID_CUSTOM_SCHEMES.any { it.equals(scheme, ignoreCase = true) }
    }

    /** 按 host 分流；返回要原样转给 MainActivity 的 data（只有路由深链），其余为 null。 */
    private fun handle(data: Uri): Uri? =
        when (data.host) {
            WebCodeCallbackParser.HOST -> {
                val callback = data.toString().let(WebCodeCallbackParser::parse)
                if (callback != null) (application as? WebCodeCallbackSinkProvider)?.webCodeCallbackSink()?.deliver(callback)
                null
            }

            ROUTE_HOST -> {
                data
            }

            else -> {
                null
            }
        }

    private fun mainIntent(data: Uri?): Intent =
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (data != null) setData(data) }

    private companion object {
        /** 路由深链的 host（`<scheme>://r/<路由名>`，DeepLinkParser）。 */
        const val ROUTE_HOST = "r"
    }
}
