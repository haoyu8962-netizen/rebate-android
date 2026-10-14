package com.couli.feature.trade

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CT-LOCK-01 R1-01：购买链路（OpenPurchaseController + 假服务）上 30102 / 30111 带 data.reason=sharer_auth_invalid（生成表 retry_kind_by_reason=never）
 * 只提示：不弹授权、不登记授权待办、不重放、不进入【仍去购买（无返利）】分支（不发 no_rebate=true 请求）。
 * 对照：不带 reason 的 30102（缺 auth_url / state）照旧弹授权 → 授权失败 → 二选一 → 无返利新请求（既有流程不变）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CtLock01SharerAuthInvalidPurchaseTest {
    private val api = FakeTradeApi()
    private val ports = FakePorts()
    private val baichuan = FakeBaichuan()
    private val env = FakeEnv()
    private val pending = MemoryPurchasePending()
    private val events = mutableListOf<JsonObject>()
    private var keySeq = 0

    private fun TestScope.controller(): OpenPurchaseController {
        val executor = TradeJumpExecutor(ports, baichuan, { events += it }, { testScheduler.currentTime }, { TradeFixtures.NOW })
        return OpenPurchaseController(
            api = api,
            executor = executor,
            ports = ports,
            env = env,
            pending = pending,
            counter = AuthSheetCounter(),
            tips = JumpTipService(api) { "session" },
            webCode = FakeWebCode(),
            baichuan = baichuan,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            keyGenerator = {
                keySeq += 1
                "key-$keySeq"
            },
            browserNoticeMillis = 0L,
        )
    }

    private fun opens() = api.calls.filter { it.call.operationId == TradeOperations.OPEN_LINK }

    private fun noRebateRequests() = opens().filter { noRebate(it.call.body) }

    private fun noRebate(body: String?): Boolean {
        if (body == null) return false
        return Json.parseToJsonElement(body).jsonObject["no_rebate"] == JsonPrimitive(true)
    }

    private fun TestScope.assertPromptOnly(
        c: OpenPurchaseController,
        code: Int,
    ) {
        assertNull("$code 不弹授权、不给二选一", c.state.value.dialog)
        val toast = c.state.value.toast
        assertEquals("$code 只提示（error.<code>）", "error.$code", toast?.key)
        assertNull("$code 不登记授权待办", pending.peek())
        // 页面上再触发授权 / 回前台都不会拉起授权或重发
        c.authorize()
        c.onForeground()
        runCurrent()
        assertNull(c.state.value.dialog)
        assertEquals("$code 不重放", 1, opens().size)
        assertTrue("$code 不发 no_rebate=true 请求", noRebateRequests().isEmpty())
        assertTrue("$code 不外跳授权页", ports.launched.isEmpty())
        assertTrue("$code 不上报 link_jump", events.isEmpty())
    }

    @Test
    fun taobaoSharerAuthInvalidOnlyPrompts() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.fail(opens().single(), 30102, """{"reason":"sharer_auth_invalid"}""")
            runCurrent()
            assertPromptOnly(c, 30102)
        }

    @Test
    fun pddSharerAuthInvalidOnlyPrompts() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card(platform = "pdd")
            c.buy(env.card!!, null)
            runCurrent()
            api.fail(opens().single(), 30111, """{"reason":"sharer_auth_invalid"}""")
            runCurrent()
            assertPromptOnly(c, 30111)
        }

    @Test
    fun withoutReasonTheAuthorizationFlowAndNoRebateChoiceAreUnchanged() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            val first = opens().single()
            api.fail(first, 30102)
            runCurrent()
            assertEquals("弹授权", PurchaseDialog.AuthSheet("taobao"), c.state.value.dialog)
            assertNotNull("登记授权待办", pending.peek())
            // 缺 auth_url / state：本端执行不了任何授权方式 → 授权失败 → 二选一
            c.authorize()
            runCurrent()
            assertEquals(PurchaseDialog.AuthDeclined("taobao", failed = true), c.state.value.dialog)
            c.buyWithoutRebate()
            runCurrent()
            assertEquals(2, opens().size)
            assertEquals("【仍去购买（无返利）】发 no_rebate=true 新请求", 1, noRebateRequests().size)
        }
}
