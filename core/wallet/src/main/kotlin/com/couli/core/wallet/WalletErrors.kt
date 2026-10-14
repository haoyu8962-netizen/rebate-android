// 错误码是契约取值（contracts/error-codes.json），按码直接写
@file:Suppress("MagicNumber")

package com.couli.core.wallet

import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.ResultSignals
import com.couli.generated.DefaultTexts

/** 读请求（摘要、rules、记录、收款账号、详情）失败的显示结论。 */
data class WalletError(
    val kind: String,
    val textKey: String,
    val traceId: String? = null,
    val showTrace6: Boolean = false,
    val disableMillis: Long = 0L,
) {
    companion object {
        /** 传输层失败（断网、超时、网关错误页）：钱包摘要可用缓存（G22）。 */
        const val TRANSPORT = "transport"
        const val SERVER = "server"
        const val RETRY_AFTER = "retry_after"
        const val FORCE_UPDATE = "force_update"
        const val NOT_FOUND = "not_found"

        /** 本地拒绝且属于调用方作废（离页、换代）：不显示。 */
        const val CANCELLED = "cancelled"
        const val OTHER = "other"
    }
}

/**
 * 钱包类页面的错误消息（APP-06a 第 6、12 条，APP-07 第 9 条，APP-08 第 8、16 条）：5xxxx 由 [message] 独立追加 trace_id 末 6 位，不依赖文案里有
 * {trace6} 占位符；未收录的 5xxxx 回落 error.50001；5xxxx 不伪装成空结果或「余额为 0」。
 */
object WalletErrors {
    const val NETWORK_KEY = "wallet.network_error"
    private const val GENERIC_SERVER = 50_001
    private const val NOT_FOUND = 30701

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // 按结果类别逐项
    fun of(result: ApiResult): WalletError {
        when (result) {
            is ApiResult.TransportFailure -> {
                return WalletError(WalletError.TRANSPORT, NETWORK_KEY)
            }

            is ApiResult.LocalReject -> {
                return if (result.reason == LocalRejectReason.CALL_CANCELLED) {
                    WalletError(WalletError.CANCELLED, "")
                } else {
                    WalletError(WalletError.OTHER, NETWORK_KEY)
                }
            }

            is ApiResult.SessionEnded -> {
                return WalletError(WalletError.CANCELLED, "")
            }

            is ApiResult.Failure -> {
                val code = result.code
                return when {
                    code == ResultSignals.RATE_LIMITED -> {
                        WalletError(WalletError.RETRY_AFTER, "error.42901", disableMillis = ResultSignals.disableMillis(result))
                    }

                    // 10405 只消费网络层已确定的 Effect（APP-13a R2-02，共性第 12、37 条）：FORCE_UPDATE 才进强更；
                    // REFRESH_ONLY（遗留受限令牌已刷新）与刷新失败清会话沿用网络层结论，按可重试的读取失败显示，不按码重判强更
                    result.effect.kind == ErrorEffectKind.FORCE_UPDATE -> {
                        WalletError(WalletError.FORCE_UPDATE, "error.10405")
                    }

                    code == 10405 -> {
                        WalletError(WalletError.OTHER, NETWORK_KEY)
                    }

                    code == NOT_FOUND -> {
                        WalletError(WalletError.NOT_FOUND, "error.30701")
                    }

                    code / 10_000 == 5 -> {
                        WalletError(WalletError.SERVER, SensitiveOutcome.serverKey(code), result.traceId, showTrace6 = true)
                    }

                    else -> {
                        val key = result.effect.textKey?.takeIf { DefaultTexts.text(it) != null } ?: "error.$code"
                        WalletError(
                            WalletError.OTHER,
                            key.takeIf { DefaultTexts.text(it) != null } ?: "error.$GENERIC_SERVER",
                            result.traceId,
                        )
                    }
                }
            }

            else -> {
                // code=0 却解析不了（坏数据）：按服务端错误显示加载失败（不当成 0）
                val trace = (result as? ApiResult.Success)?.traceId
                return WalletError(WalletError.SERVER, "error.$GENERIC_SERVER", trace, showTrace6 = true)
            }
        }
    }

    /** 文案 + trace6（统一错误消息函数；没有 trace_id 时不追加）。 */
    fun message(
        texts: WalletTexts,
        key: String,
        traceId: String?,
        showTrace6: Boolean,
    ): String {
        val trace = ResultSignals.trace6(traceId)
        val text = texts.format(key, mapOf("trace6" to trace))
        return if (showTrace6) ResultSignals.withTrace6(text, traceId) else text
    }

    fun message(
        texts: WalletTexts,
        error: WalletError,
    ): String = message(texts, error.textKey, error.traceId, error.showTrace6)
}
