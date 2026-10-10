package com.couli.feature.trade

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-0710，APP-08 共性第 33 条（BR-ATTR-21）：平台未安装、但口令复制成功的结局也算成功外跳，要通知发起页「外跳后回前台拉待跟单卡」；
 * 安装提示照常。复制失败（全部路径失败）不算外跳。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowupAnd0710TradeTest {
    private val api = FakeTradeApi()
    private val ports = FakePorts()
    private val base = FakeEnv()

    /** 记下外跳通知（其余委托给同一个页面环境替身）。 */
    private class RecordingEnv(
        private val base: FakeEnv,
    ) : PurchaseEnv by base {
        val jumps = mutableListOf<String>()

        override fun jumped(platform: String) {
            jumps += platform
        }
    }

    private val env = RecordingEnv(base)

    private fun TestScope.controller(): OpenPurchaseController {
        val executor = TradeJumpExecutor(ports, FakeBaichuan(), { }, { testScheduler.currentTime }, { TradeFixtures.NOW })
        return OpenPurchaseController(
            api = api,
            executor = executor,
            ports = ports,
            env = env,
            pending = MemoryPurchasePending(),
            counter = AuthSheetCounter(),
            tips = JumpTipService(api) { "session" },
            webCode = FakeWebCode(),
            baichuan = FakeBaichuan(),
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            keyGenerator = { "key-1" },
            browserNoticeMillis = 0L,
        )
    }

    private fun TestScope.answerTips() {
        runCurrent()
        val tips = api.calls.lastOrNull { it.call.operationId == TradeOperations.GET_TIPS && !it.result.isCompleted } ?: return
        api.succeed(
            tips,
            """{"jump_tip":{"taobao":"2026-10-01T00:00:00+08:00","pdd":"2026-10-01T00:00:00+08:00"},"inviter_before_buy":null}""",
        )
        runCurrent()
    }

    @Test
    fun lesson33_notInstalledButTpwdCopiedCountsAsAJumpOut() =
        runTest {
            val c = controller()
            base.card = TradeFixtures.card()
            ports.installed = "false"
            c.buy(base.card!!, null)
            runCurrent()
            val open = api.calls.single { it.call.operationId == TradeOperations.OPEN_LINK }
            api.succeed(open, TradeFixtures.openResult(plan = TradeFixtures.plan("scheme" to "demo-app://x", "copy_tpwd" to "TPWD-1")))
            answerTips()
            assertTrue("口令已复制", ports.launched.contains("copy:TPWD-1"))
            assertTrue("安装提示照常", c.state.value.notInstalled)
            assertEquals("复制成功也算外跳：回前台拉待跟单卡", listOf("taobao"), env.jumps)
        }

    @Test
    fun lesson33_aFailedJumpWithoutCopyIsNotAJumpOut() =
        runTest {
            val c = controller()
            base.card = TradeFixtures.card()
            ports.installed = "false"
            c.buy(base.card!!, null)
            runCurrent()
            val open = api.calls.single { it.call.operationId == TradeOperations.OPEN_LINK }
            api.succeed(open, TradeFixtures.openResult(plan = TradeFixtures.plan("scheme" to "demo-app://x")))
            answerTips()
            assertTrue(c.state.value.notInstalled)
            assertTrue("没有复制成功：不算外跳", env.jumps.isEmpty())
        }
}
