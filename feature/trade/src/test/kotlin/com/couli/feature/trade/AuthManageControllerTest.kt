package com.couli.feature.trade

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-06b 授权管理（BR-ID-17 细则「授权管理页」）的集成测试（本地假服务 [FakeTradeApi]）：只列淘宝、拼多多；active 已授权、其余未授权；
 * blocked 不给去授权；【去授权】取 auth-url → 绑定（新键）→ 刷新本页、不重放 open；auth-url 30153 → 改为停用；拼多多走 auth_jump、不上报
 * link_jump，回到前台刷新。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthManageControllerTest {
    private val api = FakeTradeApi()
    private val ports = FakePorts()
    private val web = FakeWebCode()
    private val events = mutableListOf<Any>()

    private fun TestScope.controller(): AuthManageController {
        val executor = TradeJumpExecutor(ports, FakeBaichuan(), { events += it }, { 0L }, { TradeFixtures.NOW })
        return AuthManageController(
            api,
            executor,
            ports,
            web,
            FakeBaichuan(),
            backgroundScope,
            { "bind-key-${api.calls.size}" },
            { testScheduler.currentTime },
        )
    }

    /** R1-11：列表加载收到 42901 → 按 Retry-After 禁用【重试】，执行层也拦下；到期自动恢复。 */
    @Test
    fun r1_11_listRateLimitDisablesRetryUntilRetryAfterEnds() =
        runTest {
            val c = controller()
            c.load()
            runCurrent()
            api.fail(api.calls.single(), 42901, disableMillis = 30_000L)
            runCurrent()
            assertTrue(c.state.value.rateLimited)
            c.load()
            runCurrent()
            assertEquals("禁用期间不发", 1, api.calls.size)
            advanceTimeBy(30_001L)
            runCurrent()
            assertFalse(c.state.value.rateLimited)
            c.load()
            runCurrent()
            assertEquals(2, api.calls.size)
        }

    /** R1-11：【去授权】取 auth-url 收到 42901（没带 Retry-After）→ 缺省 5 秒内不能再点。 */
    @Test
    fun r1_11_authorizeRateLimitUsesTheDefaultFiveSeconds() =
        runTest {
            val c = controller()
            c.load()
            runCurrent()
            api.succeed(api.calls.single(), """{"items":[{"platform":"taobao","status":"unbound"}]}""")
            runCurrent()
            c.authorize("taobao")
            runCurrent()
            api.fail(api.last(TradeOperations.GET_UNION_AUTH_URL), 42901)
            runCurrent()
            assertTrue(c.state.value.rateLimited)
            c.authorize("taobao")
            runCurrent()
            assertEquals("5 秒内不再请求", 1, api.calls.count { it.call.operationId == TradeOperations.GET_UNION_AUTH_URL })
            advanceTimeBy(5_001L)
            runCurrent()
            assertFalse(c.state.value.rateLimited)
            c.authorize("taobao")
            runCurrent()
            assertEquals(2, api.calls.count { it.call.operationId == TradeOperations.GET_UNION_AUTH_URL })
        }

    @Test
    fun projectsOnlyTaobaoAndPddWithTwoWordings() {
        val rows =
            AuthManageController.rows(
                listOf(
                    Json.parseToJsonElement("""{"platform":"taobao","status":"invalid"}""").jsonObject,
                    Json.parseToJsonElement("""{"platform":"jd","status":"unbound"}""").jsonObject,
                    Json.parseToJsonElement("""{"platform":"pdd","status":"active"}""").jsonObject,
                ),
            )
        assertEquals(listOf("taobao", "pdd"), rows.map { it.platform })
        assertFalse("失效也只写未授权", rows[0].authorized)
        assertTrue(rows[0].canAuthorize)
        assertTrue(rows[1].authorized)
        assertFalse("已授权不给按钮", rows[1].canAuthorize)
        val blocked = AuthManageController.rows(listOf(Json.parseToJsonElement("""{"platform":"taobao","status":"blocked"}""").jsonObject))
        assertFalse("blocked 不给去授权", blocked[0].canAuthorize)
        assertTrue(blocked[0].blocked)
        val released =
            AuthManageController.rows(
                listOf(Json.parseToJsonElement("""{"platform":"taobao","status":"released"}""").jsonObject),
            )
        assertTrue("released 显示未授权并给去授权", released[0].canAuthorize)
    }

    @Test
    fun authorizeBindsWithAFreshKeyThenReloadsWithoutOpeningAnyLink() =
        runTest {
            val c = controller()
            c.load()
            runCurrent()
            val items = """{"items":[{"platform":"taobao","status":"unbound"},{"platform":"pdd","status":"unbound"}]}"""
            api.succeed(api.calls.single(), items)
            runCurrent()
            c.authorize("taobao")
            runCurrent()
            val url = api.last(TradeOperations.GET_UNION_AUTH_URL)
            assertEquals("installed=unknown", url.call.query)
            api.succeed(url, """{"auth_url":"https://oauth.example.test/a","state":"st_9","auth_methods":["web_code"]}""")
            runCurrent()
            val bind = api.last(TradeOperations.BIND_UNION)
            assertTrue(bind.call.idempotencyKey!!.startsWith("bind-key-"))
            api.succeed(bind, """{"platform":"taobao","status":"active"}""")
            runCurrent()
            assertEquals(
                TradeOperations.LIST_UNION_BINDINGS,
                api.calls
                    .last()
                    .call.operationId,
            )
            assertTrue("管理页授权后不重放 open", api.ops().none { it == TradeOperations.OPEN_LINK })
        }

    @Test
    fun blockedFromAuthUrlTurnsTheRowIntoContactOnly() =
        runTest {
            val c = controller()
            c.load()
            runCurrent()
            api.succeed(api.calls.single(), """{"items":[{"platform":"taobao","status":"unbound"}]}""")
            runCurrent()
            c.authorize("taobao")
            runCurrent()
            api.fail(api.last(TradeOperations.GET_UNION_AUTH_URL), 30153)
            runCurrent()
            val row =
                c.state.value.rows
                    .first { it.platform == "taobao" }
            assertTrue(row.blocked)
            assertFalse(row.canAuthorize)
        }

    @Test
    fun pddRunsTheAuthJumpWithoutLinkJumpAndRefreshesOnReturn() =
        runTest {
            val c = controller()
            c.load()
            runCurrent()
            api.succeed(api.calls.single(), """{"items":[]}""")
            runCurrent()
            c.authorize("pdd")
            runCurrent()
            api.succeed(
                api.last(TradeOperations.GET_UNION_AUTH_URL),
                """{"auth_url":"https://auth.example.test/p","state":"st_2",""" +
                    """"auth_jump":${TradeFixtures.plan("h5" to "https://auth.example.test/p")}}""",
            )
            runCurrent()
            assertEquals(listOf("browser:https://auth.example.test/p"), ports.launched)
            assertTrue("auth_jump 不上报 link_jump", events.isEmpty())
            val before = api.calls.size
            c.onForeground()
            runCurrent()
            assertEquals(before + 1, api.calls.size)
            assertEquals(
                TradeOperations.LIST_UNION_BINDINGS,
                api.calls
                    .last()
                    .call.operationId,
            )
        }
}
