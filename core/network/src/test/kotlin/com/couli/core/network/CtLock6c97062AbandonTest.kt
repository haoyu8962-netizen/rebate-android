package com.couli.core.network

import com.couli.core.network.CtLock6c97062Fixture.Companion.ABANDON
import com.couli.core.network.CtLock6c97062Fixture.Companion.ACCOUNT
import com.couli.core.network.CtLock6c97062Fixture.Companion.BODIES
import com.couli.core.network.CtLock6c97062Fixture.Companion.PAYOUT
import com.couli.core.network.CtLock6c97062Fixture.Companion.TRACE
import com.couli.core.network.CtLock6c97062Fixture.Companion.WITHDRAW
import com.couli.core.network.CtLock6c97062Fixture.Companion.envelope
import com.couli.core.network.CtLock6c97062Fixture.Companion.json
import com.couli.core.network.CtLock6c97062Fixture.Companion.operation
import com.couli.generated.DefaultTexts
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资金断言第 3 组（FU-AND-CONTRACT-LOCK-6c97062，D10-2888）：【放弃上一笔】与 20903。
 * - 对结果未知的提现点放弃：本地放行（不在本地拒成 pending_key_unresolved），请求走 POST /v1/idempotency-keys/abandon，`action` 取生成物的
 *   abandonAction（契约 abandonIdempotencyKey：「the withdrawal included, although it needs no step-up」）；环境未就绪时按「本来会发出」给
 *   environment_unsettled，不按「放弃不了」。
 * - 20903（键已作废）：契约只把它列在四个敏感操作上（abandon 接口自己的 x-error-codes 没有它），出现在带已作废的键再提交时。处理照
 *   error-codes 20903 的 action「结束该键的待确认状态，删除本机未决记录，恢复提交入口；不重放，由用户重新提交（新幂等键）」与现有映射：
 *   ErrorActionMapper 无专门分支 → 有文案键 error.20903 的 Toast；ApiClient 统一出口对 20903 不改成「暂时确认不了」；PendingKeyStore 按终结删记录。
 *
 * 未决记录直接写进存储（[CtLock6c97062Fixture.seedUnknown]：升级前遗留、或上一个进程留下的提现待确认），使这一组只取决于放弃 / 确认这两条路径。
 */
class CtLock6c97062AbandonTest {
    @Test
    fun abandoningAnUnknownWithdrawalIsSentWithTheGeneratedAction() = abandonAndResubmit(WITHDRAW)

    /** 对照：收款账号（stepUp 与 abandonAction 都有）同一套断言。 */
    @Test
    fun abandoningAnUnknownPayoutAccountChangeBehavesTheSame() = abandonAndResubmit(PAYOUT)

    @Test
    fun withdrawAbandonWhileTheEnvironmentIsUnsettledWaitsInsteadOfBeingRejected() = abandonWhileUnsettled(WITHDRAW)

    /** 对照：收款账号。 */
    @Test
    fun payoutAbandonWhileTheEnvironmentIsUnsettledBehavesTheSame() = abandonWhileUnsettled(PAYOUT)

    @Test
    fun withdrawConfirmAnswered20903EndsTheKeyAndTheNextSubmissionUsesANewKey() = confirmAnsweredAbandoned(WITHDRAW)

    /** 对照：收款账号。 */
    @Test
    fun payoutConfirmAnswered20903BehavesTheSame() = confirmAnsweredAbandoned(PAYOUT)

    private fun abandonAndResubmit(operationId: String) {
        val fixture = CtLock6c97062Fixture()
        try {
            val key = "pending-$operationId"
            val action = operation(operationId).abandonAction
            assertNotNull("前提：生成物给了 $operationId 的放弃动作", action)
            fixture.seedUnknown(operationId, key, BODIES.getValue(operationId))
            fixture.server.enqueue(envelope(0, """{"outcome":"abandoned","original":null}"""))

            val result = fixture.api.abandonPending(operationId)
            assertEquals("$operationId：本地放行、作废接口回 abandoned", ApiResult.Abandoned(key, TRACE), result)
            val request = checkNotNull(fixture.nextRequest())
            assertEquals(operation(ABANDON).method, request.method)
            assertEquals(operation(ABANDON).pathTemplate, request.path)
            val body = json(request.body.readUtf8())
            assertEquals("$operationId：action 取生成物 abandonAction", action, body.getValue("action").jsonPrimitive.content)
            assertEquals("$operationId：放弃的是原键", key, body.getValue("idempotency_key").jsonPrimitive.content)
            assertNull("作废接口不带 Idempotency-Key", request.getHeader(HeaderNames.IDEMPOTENCY_KEY))
            assertNull("$operationId：记录结束", fixture.record(operationId))
            assertEquals(PendingKeyStore.DONE, fixture.pending.state(key, ACCOUNT).stateRaw)

            // 表单恢复：再提交不被挡、用新键
            fixture.server.enqueue(envelope(0, "{}"))
            assertTrue(fixture.api.execute(ApiCall(operationId, body = BODIES.getValue(operationId))) is ApiResult.Success)
            val resubmit = checkNotNull(fixture.nextRequest())
            assertEquals(operation(operationId).pathTemplate, resubmit.path)
            assertNotEquals("$operationId：新键", key, resubmit.getHeader(HeaderNames.IDEMPOTENCY_KEY))
        } finally {
            fixture.close()
        }
    }

    private fun abandonWhileUnsettled(operationId: String) {
        val fixture = CtLock6c97062Fixture()
        try {
            val key = "pending-$operationId"
            fixture.seedUnknown(operationId, key, BODIES.getValue(operationId))
            // 进页面时读过一次会话（绑定归属），之后进入环境未就绪窗口
            assertEquals(ACCOUNT, fixture.api.activeUserId())
            fixture.state = CtLock6c97062Fixture.FULL_SUPPORTED.copy(environmentSettled = false)

            assertEquals(
                "$operationId：有效的待确认记录按「本来会发出」给 environment_unsettled",
                ApiResult.LocalReject(LocalRejectReason.ENVIRONMENT_UNSETTLED),
                fixture.api.abandonPending(operationId),
            )
            assertEquals("未就绪时不发", 0, fixture.server.requestCount)
            assertEquals("记录保持待确认", PendingKeyStore.UNKNOWN, fixture.pending.state(key, ACCOUNT).stateRaw)
        } finally {
            fixture.close()
        }
    }

    private fun confirmAnsweredAbandoned(operationId: String) {
        val fixture = CtLock6c97062Fixture()
        try {
            val key = "pending-$operationId"
            val body = BODIES.getValue(operationId)
            assertTrue("前提：契约把 20903 列在 $operationId 上", 20903 in operation(operationId).errorCodes)
            assertNotNull("前提：字典里有 error.20903", DefaultTexts.text("error.20903"))
            fixture.seedUnknown(operationId, key, body)
            // 上一笔已在别处（或作废响应丢失后）作废：带原键确认，服务端在幂等检查一步回 20903
            fixture.server.enqueue(envelope(20903))

            val result = fixture.api.confirmPending(operationId)
            assertTrue("$operationId：业务错误码，实际 $result", result is ApiResult.Failure)
            val failure = result as ApiResult.Failure
            assertEquals(20903, failure.code)
            assertEquals("$operationId：确认带的是原键", key, checkNotNull(fixture.nextRequest()).getHeader(HeaderNames.IDEMPOTENCY_KEY))
            assertNull("$operationId：20903 结束该键，删除本机未决记录", fixture.record(operationId))
            assertEquals(PendingKeyStore.DONE, fixture.pending.state(key, ACCOUNT).stateRaw)
            assertEquals("提示用 error.20903（不改成「暂时确认不了」）", "error.20903", failure.effect.textKey)
            assertEquals(ErrorEffectKind.TOAST, failure.effect.kind)
            assertEquals("不重放", ReplayMode.NONE, failure.effect.replay)
            assertFalse("不登记 pending_action", failure.effect.registerPending)
            assertFalse("键已结束，不再待确认", failure.effect.pendingKeyUnknown)
            assertEquals("没有自动续发", 1, fixture.server.requestCount)

            // 恢复提交入口：用户重新提交，新键
            fixture.server.enqueue(envelope(0, "{}"))
            assertTrue(fixture.api.execute(ApiCall(operationId, body = body)) is ApiResult.Success)
            assertNotEquals("$operationId：新键", key, checkNotNull(fixture.nextRequest()).getHeader(HeaderNames.IDEMPOTENCY_KEY))
        } finally {
            fixture.close()
        }
    }
}
