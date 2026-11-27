package com.couli.feature.trade

/**
 * 淘宝 web_code 授权回跳（FU-AND-TB-WEBCODE-A；D10-2717 定值：`couli://auth/taobao?code=<c>&state=<s>`，
 * 出错时 `couli://auth/taobao?state=<s>&error=1`，一律按授权未完成）。只在内存里流转，不保存、不进日志；
 * [toString] 不带 code / state 原文（不用 data class）。
 *
 * TODO(FU-AND-TB-WEBCODE-A 实现)：接口桩，签名由测试写手先给，实现子代理替换。
 */
sealed interface WebCodeCallback {
    val state: String

    /** 授权完成：带回授权码。 */
    class Code(
        val code: String,
        override val state: String,
    ) : WebCodeCallback

    /** 中转页回跳了 error=1：授权未完成（按授权失败二选一处理）。 */
    class Incomplete(
        override val state: String,
    ) : WebCodeCallback
}

/** 回跳的接收方（中转 Activity 当场交给它；生产为进程内的 [WebCodeInbox]）。 */
fun interface WebCodeCallbackSink {
    fun deliver(callback: WebCodeCallback)
}

/**
 * 回跳解析（纯 Kotlin，不用 android.net.Uri；规则见设计稿 §5.3，按 D10-2717 改定值）：非法输入一律为 null。
 *
 * TODO(FU-AND-TB-WEBCODE-A 实现)：接口桩，现在恒返回 null。
 */
object WebCodeCallbackParser {
    const val SCHEME: String = "couli"
    const val HOST: String = "auth"
    const val PATH: String = "/taobao"

    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant") // 接口桩
    fun parse(uri: String): WebCodeCallback? = null
}
