package com.couli.core.network

import com.couli.core.update.UpdateCheckAdapter
import com.couli.core.update.UpdateCheckOutcome
import com.couli.core.update.UpdateChecker
import com.couli.core.update.UpdateGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * 真实版本检查器（F-X-01，FU-AND-0710；Release 用，取代原来永远失败的占位）：经现有 [ApiClient] 发 GET /v1/app-versions/check
 * （契约 operationId checkAppVersion：x-auth none、不签名、没有 query；平台与渠道由 ApiClient 的公共头 X-Platform / X-Channel 给出；
 * x-error-codes 为空，只有公共码）。与鸿蒙 FU-HM-0709 ApiUpdateChecker 同义。
 *
 * - 只在 [allowed] 为真时发：App 接「环境闸门 settled 且已同意当前隐私政策」（基本模式、首启未同意都不发，03 §4.4、BR-ID-11）。
 *   同一条件也作为 [CallOwnership.sendAllowed] 与 [CallOwnership.stillOwned]，在每次实际写出前（含 GET 自动重试）与响应到达时再核对；
 *   网络层本身也在基本模式、未同意时本地拒绝 checkAppVersion（基本模式白名单里没有它）。
 * - 复用在途：已有一次检查没结束时，再次 [check] 等同一个结果，不另发请求（03 §4.2；AppUpdate 层也不重复发）。
 * - 成功（code 0、data 符合契约必填字段）→ [UpdateCheckAdapter] 投影；recheck_interval_sec 不在 check 响应里，取 [recheckIntervalSec]
 *   （App 接 /v1/config.app_update，没有为 null → 包内默认，rule-gaps G01）。
 * - 其余一律按失败（网络错误、业务错误、本地拒绝、被作废、data 不合契约）：UpdateGate 按 unknown 处理，不误拦用户。不抛出。
 */
class ApiUpdateChecker(
    private val client: () -> ApiClient,
    private val allowed: () -> Boolean,
    private val recheckIntervalSec: () -> Int? = { null },
) : UpdateChecker {
    private val lock = Any()
    private var inFlight: CompletableDeferred<UpdateCheckOutcome>? = null

    /** 实际交给 ApiClient 的检查次数（诊断与测试用）。 */
    @Volatile
    var requests: Int = 0
        private set

    /** 有检查在途。 */
    val isRunning: Boolean get() = synchronized(lock) { inFlight != null }

    @Suppress("ReturnCount") // 取不到客户端、不允许发、交付前复核逐项
    override suspend fun check(): UpdateCheckOutcome {
        // 这次检查开始时的隐私 / 环境世代（一次性取，之后只比较；R1-02）：结果交付前世代变了或已不允许，结果作废
        val start = safeEpochs() ?: return UpdateCheckOutcome.Failure
        var running: CompletableDeferred<UpdateCheckOutcome>? = null
        var owned: CompletableDeferred<UpdateCheckOutcome>? = null
        synchronized(lock) {
            running = inFlight
            if (running == null && safeAllowed()) {
                owned = CompletableDeferred<UpdateCheckOutcome>().also { inFlight = it }
                requests += 1
            }
        }
        val outcome =
            running?.await()
                ?: owned?.let { drive(it, start) }
                ?: return UpdateCheckOutcome.Failure
        return deliverable(outcome, start)
    }

    /**
     * 交给调用方之前复核（FU-AND-0710 R1-02）：响应已在 IO 线程处理完、等着回主线程时用户撤回同意 / 切了环境，请求已从在途登记里注销，
     * 取消传输撤不回待交付的结果——这里按检查开始时的世代与 [allowed] 再核一次，失效的一律 [UpdateCheckOutcome.Invalidated]（调用方不落盘、
     * 不改强更、不选店）。复用在途结果的调用方各按自己开始时的世代核对。
     */
    private fun deliverable(
        outcome: UpdateCheckOutcome,
        start: CallEpochs,
    ): UpdateCheckOutcome = if (safeAllowed() && safeEpochs() == start) outcome else UpdateCheckOutcome.Invalidated

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 取不到客户端按失败
    private fun safeEpochs(): CallEpochs? =
        try {
            client().inFlight.epochs()
        } catch (error: Exception) {
            null
        }

    private suspend fun drive(
        owned: CompletableDeferred<UpdateCheckOutcome>,
        start: CallEpochs,
    ): UpdateCheckOutcome {
        try {
            owned.complete(run(start))
        } finally {
            // 发起方被取消时也要结束在途，复用它的调用方按失败收到结果，下一次检查真正重发
            withContext(NonCancellable) {
                synchronized(lock) { if (inFlight === owned) inFlight = null }
                owned.complete(UpdateCheckOutcome.Failure)
            }
        }
        return owned.await()
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 任何异常都按检查失败（unknown），不误拦用户
    private fun safeAllowed(): Boolean =
        try {
            allowed()
        } catch (error: Exception) {
            false
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount") // ApiClient 不抛出、万一抛出按失败；各种失败逐项返回
    private suspend fun run(start: CallEpochs): UpdateCheckOutcome {
        val result =
            try {
                val api = client()
                val ownership = CallOwnership(start, sendAllowed = ::safeAllowed) { safeAllowed() }
                api.send(ApiCall(OPERATION, ownership = ownership))
            } catch (error: Exception) {
                return UpdateCheckOutcome.Failure
            }
        // send 返回之后（挂起恢复）先复核归属：失效的结果不解析、不交付（R1-02）
        if (!safeAllowed() || safeEpochs() != start) return UpdateCheckOutcome.Invalidated
        val data = (result as? ApiResult.Success)?.data as? JsonObject ?: return UpdateCheckOutcome.Failure
        if (!UpdateCheckAdapter.wellFormed(data)) return UpdateCheckOutcome.Failure
        val interval =
            try {
                recheckIntervalSec()?.takeIf { it > 0 }
            } catch (error: Exception) {
                null
            } ?: UpdateGate.DEFAULT_RECHECK_INTERVAL_SEC
        return UpdateCheckOutcome.Success(UpdateCheckAdapter.adapt(data, interval))
    }

    companion object {
        /** 契约 operationId（GET /v1/app-versions/check）。 */
        const val OPERATION: String = "checkAppVersion"
    }
}
