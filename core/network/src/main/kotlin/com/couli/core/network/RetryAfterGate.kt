package com.couli.core.network

/**
 * 页面执行器共用的结果信号（APP-06a；03 §4.2）：
 * - [disableMillis]：42901 的禁用时长，取网络层 [ErrorEffect.disableMillis]（Retry-After，缺省 5 秒）；不是禁用动作为 0；
 * - [trace6]：服务端 trace_id 后 6 位（通用错误态附注）；确实没有 trace_id 时为空串，文案按无编号的 fallback。
 */
object ResultSignals {
    private const val TRACE_TAIL = 6

    @Suppress("ReturnCount") // 非业务错误、有禁用动作、其余三个出口
    fun disableMillis(result: ApiResult): Long {
        val failure = result as? ApiResult.Failure ?: return 0L
        val effect = failure.effect
        if (effect.kind == ErrorEffectKind.DISABLE_BUTTON && effect.disableMillis != null) {
            return effect.disableMillis.takeIf { it > 0 } ?: ErrorActionMapper.DEFAULT_RETRY_AFTER_MILLIS
        }
        return if (failure.code == RATE_LIMITED) ErrorActionMapper.DEFAULT_RETRY_AFTER_MILLIS else 0L
    }

    fun trace6(traceId: String?): String = traceId?.takeLast(TRACE_TAIL).orEmpty()

    /**
     * 错误正文 + 追踪编号统一生成（APP-06a 共性第 6、9、12 条，03 §4.2）：不依赖文案键里恰好有 {trace6} 占位符。有 trace_id 时正文末尾
     * 带「（末六位）」，正文已经含这 6 位（字典自带占位符已填上）就不重复；确实没有 trace_id 时原样返回。
     */
    fun withTrace6(
        message: String,
        traceId: String?,
    ): String {
        val trace = trace6(traceId)
        return if (trace.isEmpty() || message.contains(trace)) message else "$message$TRACE_OPEN$trace$TRACE_CLOSE"
    }

    private const val TRACE_OPEN = "（"
    private const val TRACE_CLOSE = "）"

    /** 42901（error-codes：按 Retry-After 禁用触发的按钮）。 */
    const val RATE_LIMITED = 42901
}

/**
 * 限时禁用（42901，共性 H-R1-04）：记下截止时刻，判定一律按时钟现算；到期通知由调用方按 [remainingMillis] 自己排（协程 delay）。
 * 多次禁用取更晚的截止时刻。只在调用方线程使用。
 */
class RetryAfterGate(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var until = 0L

    val active: Boolean get() = clock() < until

    fun remainingMillis(): Long = (until - clock()).coerceAtLeast(0L)

    /** 开始禁用 [millis] 毫秒（不缩短已有的截止时刻）。 */
    fun start(millis: Long) {
        until = maxOf(until, clock() + millis.coerceAtLeast(0L))
    }
}
