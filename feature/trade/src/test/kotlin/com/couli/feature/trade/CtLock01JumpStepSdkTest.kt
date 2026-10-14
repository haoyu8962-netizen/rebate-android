package com.couli.feature.trade

import com.couli.core.tradejump.BaichuanAdapter
import com.couli.core.tradejump.BaichuanAuthResult
import com.couli.core.tradejump.BaichuanOpenResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * CT-LOCK-01：JumpStep 新增可选 sdk（BaichuanOpen）、OpenLinkResult 的 jump 步骤可带它。手写解析一律忽略新字段、不当坏数据：
 * 方案步骤、执行结果、交给百川的值（仍是 value）、link_jump 上报字段与不带 sdk 时完全相同（按 sdk 执行归百川接入任务，G05）。
 * 30102 / 30111 的新 reason sharer_auth_invalid：open 错误动作按生成表 retry_kind_by_reason=never 只提示（notice，不弹授权、
 * 不给无返利购买）；不带 reason 时仍是授权动作（R1-01；购买链路见 CtLock01SharerAuthInvalidPurchaseTest）。
 */
class CtLock01JumpStepSdkTest {
    private val sdkObject =
        """{"provider":"baichuan","open_by":"code","page":"detail","item_id":"6543210987","sku_id":"1",""" +
            """"taoke":{"pid":"mm_1_2_3","relation_id":"r1"}}"""

    private fun plan(withSdk: Boolean): JsonObject {
        val sdk = if (withSdk) ""","sdk":$sdkObject""" else ""
        return Json
            .parseToJsonElement(
                """{"primary":{"type":"sdk","value":"6543210987"$sdk},""" +
                    """"fallbacks":[{"type":"scheme","value":"demo-app://item"},{"type":"h5","value":"https://h5.example.test/i"}],""" +
                    """"expire_at":"2026-10-02T09:45:00+08:00"}""",
            ).jsonObject
    }

    /** 记下交给 SDK 的值。 */
    private class RecordingBaichuan(
        private val result: BaichuanOpenResult,
    ) : BaichuanAdapter {
        val opened = mutableListOf<String>()
        override val ready: Boolean get() = true

        override fun initialize(): Boolean = true

        override fun retryInitIfFailed(): Boolean = true

        override fun open(value: String): BaichuanOpenResult {
            opened += value
            return result
        }

        override val supportsSdkToken: Boolean get() = false

        override suspend fun authorize(): BaichuanAuthResult = BaichuanAuthResult.Failed
    }

    @Test
    fun runnerStepsAndSimulationIgnoreTheSdkObject() {
        val installed = JsonObject(mapOf("app" to JsonPrimitive(true)))
        for (results in listOf(listOf("launched"), listOf("failed", "launched"), listOf("sdk_init_failed", "failed", "launched"))) {
            assertEquals(
                "results=$results",
                JumpPlanRunner.simulate(plan(false), installed, results),
                JumpPlanRunner.simulate(plan(true), installed, results),
            )
        }
        assertEquals(
            listOf("sdk", "scheme", "h5"),
            JumpPlanRunner.steps(plan(true)).map { (it["type"] as JsonPrimitive).content },
        )
    }

    @Test
    fun executorHandsValueToTheSdkAndReportsTheSameFields() =
        runTest {
            for (sdkResult in listOf(BaichuanOpenResult.LAUNCHED, BaichuanOpenResult.FAILED, BaichuanOpenResult.INIT_FAILED)) {
                val runs =
                    listOf(false, true).map { withSdk ->
                        val baichuan = RecordingBaichuan(sdkResult)
                        val ports = FakePorts(installed = "true", appLaunches = false)
                        val events = mutableListOf<JsonObject>()
                        val executor = TradeJumpExecutor(ports, baichuan, { events += it }, { 0L }, { TradeFixtures.NOW })
                        val outcome = executor.execute(plan(withSdk), "taobao", "true", PurchaseIdentity("attempt-1", "link-1"))
                        listOf(outcome, baichuan.opened, ports.launched, events)
                    }
                assertEquals("sdk=$sdkResult", runs[0], runs[1])
                @Suppress("UNCHECKED_CAST")
                assertEquals("value goes to the SDK, not sdk.*", listOf("6543210987"), runs[1][1] as List<String>)
                @Suppress("UNCHECKED_CAST")
                val events = runs[1][3] as List<JsonObject>
                assertFalse("no sdk_open_by reported yet", events.any { "sdk_open_by" in it || "sdk_error_code" in it })
            }
        }

    @Test
    fun openResultWithSdkInTheJumpMapsToTheSameAction() {
        val context = JsonObject(mapOf("currentRebateMaxFen" to JsonPrimitive(234L)))

        fun envelope(withSdk: Boolean) =
            JsonObject(
                mapOf(
                    "code" to JsonPrimitive(0),
                    "data" to Json.parseToJsonElement(TradeFixtures.openResult(plan = plan(withSdk).toString())).jsonObject,
                ),
            )
        assertEquals(OpenResponseAction.map(envelope(false), context), OpenResponseAction.map(envelope(true), context))
    }

    @Test
    fun sharerAuthInvalidReasonMakesTheOpenErrorActionPromptOnly() {
        val reason = JsonObject(mapOf("reason" to JsonPrimitive("sharer_auth_invalid")))
        for (code in listOf(30102, 30111)) {
            val shared = OpenErrorAction.map(code, reason)
            assertEquals("$code action", OpenErrorActions.NOTICE, shared.actionRaw)
            assertEquals("$code textKey", "error.$code", shared.textKey)
            assertFalse("$code no rebate offered", shared.offerNoRebate)
            assertFalse("$code no contact offered", shared.offerContact)
            assertFalse("$code no retry offered", shared.offerRetry)
        }
        // 不带 reason：原授权动作不变
        assertEquals(OpenErrorActions.AUTH_SHEET, OpenErrorAction.map(30102, JsonObject(emptyMap())).actionRaw)
        assertEquals(OpenErrorActions.AUTH_JUMP, OpenErrorAction.map(30111, JsonObject(emptyMap())).actionRaw)
        // 表里没有的 reason（30111 的 auth_unavailable 不在表里）照旧授权
        val unavailable = JsonObject(mapOf("reason" to JsonPrimitive("auth_unavailable")))
        assertEquals(OpenErrorActions.AUTH_JUMP, OpenErrorAction.map(30111, unavailable).actionRaw)
        assertEquals(OpenErrorActions.NOTICE, OpenErrorAction.map(30102, unavailable).actionRaw)
    }
}
