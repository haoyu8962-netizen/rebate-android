package com.couli.feature.trade

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-TB-WEBCODE-B 测试：授权管理页订阅「绑定变化」信号（设计稿 §3.2 AuthManage、§6.5）。冷启动 / 晚到回跳绑定成功后发出信号，本页可见时
 * 再 load() 一次（防止 load() 早于 bindings 完成而显示「未授权」），不重放任何 open；离开后不再响应。热路径（Bound / Cancelled / Failed）
 * 行为不变，已有用例照旧（AuthManageControllerTest、AuthManageReviewR2Test）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthManageBindingChangeTest {
    private val api = FakeTradeApi()
    private val ports = FakePorts()
    private val web = FakeWebCode()
    private val changes = BindingChanges()

    private fun TestScope.controller(): AuthManageController {
        val executor = TradeJumpExecutor(ports, FakeBaichuan(), { }, { 0L }, { TradeFixtures.NOW })
        return AuthManageController(
            api,
            executor,
            ports,
            web,
            FakeBaichuan(),
            backgroundScope,
            { "bind-key-${api.calls.size}" },
            { testScheduler.currentTime },
            bindingChanges = changes,
        )
    }

    private fun lists() = api.calls.count { it.call.operationId == TradeOperations.LIST_UNION_BINDINGS }

    private fun TestScope.loaded(c: AuthManageController) {
        c.load()
        runCurrent()
        api.succeed(api.calls.last(), """{"items":[{"platform":"taobao","status":"unbound"}]}""")
        runCurrent()
    }

    private fun assertNoReplay() {
        assertTrue("不重放任何 open：${api.ops()}", TradeOperations.OPEN_LINK !in api.ops())
        val authOps = setOf(TradeOperations.GET_UNION_AUTH_URL, TradeOperations.BIND_UNION)
        assertTrue("不取授权地址、不绑定：${api.ops()}", api.ops().none { it in authOps })
    }

    @Test
    fun m01_aBindingChangeReloadsTheVisiblePageOnceWithoutReplayingOpen() =
        runTest {
            val c = controller()
            loaded(c)
            assertEquals(1, lists())
            changes.notifyChanged()
            runCurrent()
            assertEquals("收到绑定变化：再 load() 一次", 2, lists())
            api.succeed(api.calls.last(), """{"items":[{"platform":"taobao","status":"active"}]}""")
            runCurrent()
            assertTrue(
                "刷新后显示已授权",
                c.state.value.rows
                    .first { it.platform == "taobao" }
                    .authorized,
            )
            assertNoReplay()
        }

    @Test
    fun m02_aHiddenPageReloadsOnceWhenItIsVisibleAgain() =
        runTest {
            val c = controller()
            loaded(c)
            c.onBackground()
            changes.notifyChanged()
            runCurrent()
            assertEquals("不可见时先不刷新", 1, lists())
            c.onForeground()
            runCurrent()
            assertEquals("回到可见时刷新一次", 2, lists())
            c.onBackground()
            c.onForeground()
            runCurrent()
            assertEquals("只刷新一次", 2, lists())
            assertNoReplay()
        }

    @Test
    fun m03_aLeftPageIgnoresBindingChanges() =
        runTest {
            val c = controller()
            loaded(c)
            c.leave()
            changes.notifyChanged()
            runCurrent()
            assertEquals("离开后不再刷新", 1, lists())
        }

    @Test
    fun m04_hotPathBoundStillReloadsOnceAndReplaysNothing() =
        runTest {
            val c = controller()
            loaded(c)
            c.authorize("taobao")
            runCurrent()
            api.succeed(
                api.last(TradeOperations.GET_UNION_AUTH_URL),
                """{"auth_url":"https://oauth.taobao.com/a","state":"st_01","auth_methods":["web_code"]}""",
            )
            runCurrent()
            api.succeed(api.last(TradeOperations.BIND_UNION), """{"platform":"taobao","status":"active"}""")
            runCurrent()
            assertEquals("热路径 Bound：刷新本页一次（行为不变）", 2, lists())
            assertTrue("不重放 open", TradeOperations.OPEN_LINK !in api.ops())
        }
}
