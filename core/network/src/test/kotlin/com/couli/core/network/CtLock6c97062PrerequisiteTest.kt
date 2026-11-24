package com.couli.core.network

import com.couli.core.generated.ApiOperation
import com.couli.core.generated.ApiOperations
import com.couli.core.network.CtLock6c97062Fixture.Companion.BODIES
import com.couli.core.network.CtLock6c97062Fixture.Companion.PAYOUT
import com.couli.core.network.CtLock6c97062Fixture.Companion.WITHDRAW
import com.couli.core.network.CtLock6c97062Fixture.Companion.envelope
import com.couli.core.network.CtLock6c97062Fixture.Companion.json
import com.couli.core.network.CtLock6c97062Fixture.Companion.operation
import com.couli.generated.ConsentType
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资金断言第 2 组（FU-AND-CONTRACT-LOCK-6c97062，D10-2888）：敏感操作被前置条件 10001 / 10004（任何 consent_type）/ 10005 拦下 → 只引导：
 * 不登记 pending_action（registerPending = false）、不同键重放（replay = none），补完前置后不会自动用同一个键再发 POST /v1/withdrawals；
 * 用户回到提现页重新提交，用新键。契约：info.description「never replayed automatically after a precondition is completed」，createWithdrawal
 * 「Stopped by a precondition the user must complete (10001, 10004 of any consent_type, 10005 …): not replayed; after completing it the user
 * submits again on Withdraw with a new Idempotency-Key」。
 *
 * 要抓的错：97d0ffd 上网络层按 stepUp 判敏感，提现（stepUp = null）被当成普通操作 → replay = same_key、registerPending = true（补完自动同键重放）。
 */
class CtLock6c97062PrerequisiteTest {
    private fun map(
        code: Int,
        operation: ApiOperation,
        data: JsonObject = JsonObject(emptyMap()),
    ) = ErrorActionMapper.map(
        ErrorActionInput(
            code = code,
            data = data,
            operation = operation,
            headers = emptyMap(),
            isReplay = false,
            state = ErrorActionState(UpdateRaw.SUPPORTED, SessionScopeRaw.FULL, "1.2.0"),
        ),
    )

    /** 每个前置码 → 用例里的 data（10004 按每种 consent_type 各跑一遍）。 */
    private fun prerequisites(): List<Pair<Int, JsonObject>> =
        listOf(10001 to JsonObject(emptyMap()), 10005 to JsonObject(emptyMap())) +
            ConsentType.known.map { 10004 to json("""{"consent_type":"${it.raw}"}""") }

    @Test
    fun withdrawPrerequisiteOnlyGuidesAndIsNotReplayed() {
        prerequisites().forEach { (code, data) ->
            val effect = map(code, operation(WITHDRAW), data)
            val label = "$code $data"
            assertFalse("$label：不登记 pending_action（补完不自动重放）", effect.registerPending)
            assertEquals("$label：不同键重放", ReplayMode.NONE, effect.replay)
            assertEquals("$label：只引导", ErrorEffectKind.GUIDE_ONLY, effect.kind)
        }
    }

    /** 对照：提现的 Effect 与仍带 x-step-up 的收款账号逐字段相同。 */
    @Test
    fun withdrawPrerequisiteEffectEqualsThePayoutAccountOne() {
        prerequisites().forEach { (code, data) ->
            assertEquals("$code $data", map(code, operation(PAYOUT), data), map(code, operation(WITHDRAW), data))
        }
    }

    /** 判定依据是 sensitiveOperation：全部操作逐个比，敏感的只引导，其余照旧补完后同键恢复。 */
    @Test
    fun everyOperationIsGuidedExactlyWhenItIsSensitive() {
        ApiOperations.all.values.forEach { op ->
            prerequisites().forEach { (code, data) ->
                val effect = map(code, op, data)
                val label = "${op.operationId} $code $data"
                if (op.sensitiveOperation) {
                    assertEquals(label, ErrorEffectKind.GUIDE_ONLY, effect.kind)
                    assertEquals(label, ReplayMode.NONE, effect.replay)
                    assertFalse(label, effect.registerPending)
                } else {
                    assertEquals(label, ReplayMode.SAME_KEY, effect.replay)
                    assertTrue(label, effect.registerPending)
                }
            }
        }
    }

    @Test
    fun withdrawPrerequisiteThroughTheClientIsSentOnceAndTheNextSubmissionUsesANewKey() {
        prerequisites().forEach { (code, data) -> prerequisiteThroughClient(WITHDRAW, code, data) }
    }

    /** 对照：收款账号走同一条管线，同一套断言。 */
    @Test
    fun payoutPrerequisiteThroughTheClientBehavesTheSame() {
        prerequisites().forEach { (code, data) -> prerequisiteThroughClient(PAYOUT, code, data) }
    }

    private fun prerequisiteThroughClient(
        operationId: String,
        code: Int,
        data: JsonObject,
    ) {
        val fixture = CtLock6c97062Fixture()
        try {
            val label = "$operationId $code $data"
            val body = BODIES.getValue(operationId)
            fixture.server.enqueue(envelope(code, data.toString()))
            val result = fixture.api.execute(ApiCall(operationId, body = body))
            assertTrue("$label：业务错误码，实际 $result", result is ApiResult.Failure)
            val effect = (result as ApiResult.Failure).effect
            assertFalse("$label：不登记 pending_action", effect.registerPending)
            assertEquals("$label：不同键重放", ReplayMode.NONE, effect.replay)
            assertEquals("$label：只引导", ErrorEffectKind.GUIDE_ONLY, effect.kind)
            assertEquals("$label：交给页面的就是这一个 Effect", listOf(effect), fixture.effects)
            assertEquals("$label：没有自动续发", 1, fixture.server.requestCount)
            val firstKey = checkNotNull(fixture.nextRequest()).getHeader(HeaderNames.IDEMPOTENCY_KEY)
            assertNull("$label：从未结果未知的键不留未决记录", fixture.record(operationId))

            // 补完前置后用户再点提交：新键、照常发出
            fixture.server.enqueue(envelope(0, "{}"))
            assertTrue(label, fixture.api.execute(ApiCall(operationId, body = body)) is ApiResult.Success)
            val second = checkNotNull(fixture.nextRequest())
            assertNotEquals("$label：再次提交用新键", firstKey, second.getHeader(HeaderNames.IDEMPOTENCY_KEY))
            assertEquals(label, 2, fixture.server.requestCount)
        } finally {
            fixture.close()
        }
    }
}
