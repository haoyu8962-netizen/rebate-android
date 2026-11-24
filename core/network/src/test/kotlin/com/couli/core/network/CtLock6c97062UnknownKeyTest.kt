package com.couli.core.network

import com.couli.core.generated.ApiOperations
import com.couli.core.network.CtLock6c97062Fixture.Companion.ACCOUNT
import com.couli.core.network.CtLock6c97062Fixture.Companion.APPEAL
import com.couli.core.network.CtLock6c97062Fixture.Companion.BODIES
import com.couli.core.network.CtLock6c97062Fixture.Companion.PAYOUT
import com.couli.core.network.CtLock6c97062Fixture.Companion.UNKNOWN_OUTCOMES
import com.couli.core.network.CtLock6c97062Fixture.Companion.WITHDRAW
import com.couli.core.network.CtLock6c97062Fixture.Companion.envelope
import com.couli.core.network.CtLock6c97062Fixture.Companion.operation
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资金断言第 1 组（FU-AND-CONTRACT-LOCK-6c97062，D10-2888）：敏感操作的提交结果未知（超时、写出后断连、网关错误页——没有统一外壳）
 * → 幂等键登记为未决（PendingKeyStore 里查得到、状态 unknown），新的提交（换键）本地拒绝，下一次提交（【确认上一笔】）用原来的键、原来的请求体。
 * 契约：info.description「a key whose result is unknown is never replaced」、createWithdrawal「When the result is unknown the key is kept」。
 *
 * 判定依据是生成物的 sensitiveOperation（x-sensitive-operation），不是 stepUp：提现 stepUp = null 仍必须登记；对照 savePayoutAccount
 * （两者都有）同样登记；非敏感的幂等写（submitAppeal）不登记。
 */
class CtLock6c97062UnknownKeyTest {
    @Test
    fun withdrawalIsSensitiveWithoutStepUpInTheGeneratedTable() {
        val withdraw = operation(WITHDRAW)
        assertNull("前提：锁 6c97062 起提现没有 x-step-up", withdraw.stepUp)
        assertTrue("前提：提现带 x-sensitive-operation", withdraw.sensitiveOperation)
    }

    @Test
    fun everyOperationIsKeptPendingExactlyWhenItIsSensitive() {
        val store = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        store.coldStart(ACCOUNT)
        ApiOperations.all.values.forEach { op ->
            val id = op.operationId
            assertEquals("$id：isSensitive 跟 sensitiveOperation 走", op.sensitiveOperation, PendingKeyStore.isSensitive(id))
            assertTrue("$id：首次发送前登记放行", store.beforeSend(id, "key-$id", "{}", ACCOUNT))
            assertEquals("$id：只有敏感操作建未决记录", op.sensitiveOperation, store.pendingFor(id, ACCOUNT) != null)
        }
    }

    @Test
    fun withdrawUnknownResultKeepsTheKeyAndTheNextSubmissionReusesIt() {
        UNKNOWN_OUTCOMES.forEach { (case, response) -> unknownThenConfirm(WITHDRAW, case, response()) }
    }

    /** 对照：仍带 x-step-up 的敏感操作，同一套断言。 */
    @Test
    fun payoutAccountUnknownResultBehavesTheSame() {
        UNKNOWN_OUTCOMES.forEach { (case, response) -> unknownThenConfirm(PAYOUT, case, response()) }
    }

    /** 对照：非敏感的幂等写结果未知时不建未决记录，下次提交不被挡（判定不是「凡幂等写都登记」）。 */
    @Test
    fun nonSensitiveIdempotentWriteIsNotKeptPending() {
        val fixture = CtLock6c97062Fixture()
        try {
            assertFalse("前提：submitAppeal 不是敏感操作", operation(APPEAL).sensitiveOperation)
            assertTrue("前提：submitAppeal 是幂等写", operation(APPEAL).idempotent)
            fixture.server.enqueue(UNKNOWN_OUTCOMES.first { it.first == "disconnect" }.second())
            val failure = fixture.api.execute(ApiCall(APPEAL, body = BODIES.getValue(APPEAL))) as ApiResult.TransportFailure
            assertFalse(failure.pendingKeyUnknown)
            assertNull(fixture.record(APPEAL))
            fixture.server.enqueue(envelope(0, "{}"))
            assertTrue(fixture.api.execute(ApiCall(APPEAL, body = BODIES.getValue(APPEAL))) is ApiResult.Success)
        } finally {
            fixture.close()
        }
    }

    private fun unknownThenConfirm(
        operationId: String,
        case: String,
        unknown: MockResponse,
    ) {
        val fixture = CtLock6c97062Fixture()
        try {
            val label = "$operationId/$case"
            val body = BODIES.getValue(operationId)
            fixture.server.enqueue(unknown)
            val result = fixture.api.execute(ApiCall(operationId, body = body))
            assertTrue("$label：没有统一外壳 → TransportFailure，实际 $result", result is ApiResult.TransportFailure)
            val firstKey = checkNotNull(checkNotNull(fixture.nextRequest()).getHeader(HeaderNames.IDEMPOTENCY_KEY))

            val record = fixture.record(operationId)
            assertNotNull("$label：结果未知的键登记为未决（PendingKeyStore 查得到）", record)
            assertEquals("$label：登记的就是发出去的键", firstKey, record?.key)
            assertEquals("$label：状态为结果未知", PendingKeyStore.UNKNOWN, record?.stateRaw)
            assertEquals("$label：保存原请求体", body, record?.body)
            assertTrue("$label：页面据此进入待确认", (result as ApiResult.TransportFailure).pendingKeyUnknown)
            assertEquals(label, firstKey, result.idempotencyKey)

            // 换新键再提交：本地拒绝、不发（不得换键）
            val unresolved = ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED)
            val replaced = ApiCall(operationId, body = body, idempotencyKey = "replacement-key")
            assertEquals(label, unresolved, fixture.api.execute(replaced))
            assertEquals(label, unresolved, fixture.api.execute(ApiCall(operationId, body = body)))
            assertEquals("$label：被挡的提交没有发出", 1, fixture.server.requestCount)

            // 下一次提交 = 【确认上一笔】：原键、原请求体
            fixture.server.enqueue(envelope(0, "{}"))
            val confirmed = fixture.api.confirmPending(operationId)
            assertTrue("$label：确认成功，实际 $confirmed", confirmed is ApiResult.Success)
            val resent = checkNotNull(fixture.nextRequest())
            assertEquals("$label：沿用原键", firstKey, resent.getHeader(HeaderNames.IDEMPOTENCY_KEY))
            assertEquals("$label：原请求体", body, resent.body.readUtf8())
            assertEquals(label, operation(operationId).pathTemplate, resent.path)
            assertEquals("$label：结果已拿到，键结束", PendingKeyStore.DONE, fixture.pending.state(firstKey, ACCOUNT).stateRaw)
        } finally {
            fixture.close()
        }
    }
}
