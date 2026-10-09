package com.couli.feature.trade

import com.couli.core.tradejump.BaichuanAuthResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 2 轮评审 R2-05 / R2-06 在授权管理页上的回归测试：30104 按 reason 恢复（重新取授权上下文、换方式、新 state、新幂等键），绑定的
 * 5xxxx 带 trace6、不当成授权失败。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthManageReviewR2Test {
    private val api = FakeTradeApi()
    private val ports = FakePorts()
    private val web = FakeWebCode()
    private val baichuan = FakeBaichuan()
    private var keySeq = 0

    private fun TestScope.controller(): AuthManageController {
        val executor = TradeJumpExecutor(ports, baichuan, { }, { 0L }, { TradeFixtures.NOW })
        return AuthManageController(
            api,
            executor,
            ports,
            web,
            baichuan,
            backgroundScope,
            {
                keySeq += 1
                "bind-key-$keySeq"
            },
            { testScheduler.currentTime },
        )
    }

    private fun binds() = api.calls.filter { it.call.operationId == TradeOperations.BIND_UNION }

    private fun body(pending: FakeTradeApi.Pending) = Json.parseToJsonElement(pending.call.body!!).jsonObject

    private fun TestScope.authorizeTaobao(
        c: AuthManageController,
        methods: String,
    ) {
        c.load()
        runCurrent()
        api.succeed(api.calls.single(), """{"items":[{"platform":"taobao","status":"unbound"}]}""")
        runCurrent()
        c.authorize("taobao")
        runCurrent()
        api.succeed(
            api.last(TradeOperations.GET_UNION_AUTH_URL),
            """{"auth_url":"https://oauth.example.test/a","state":"st_01","auth_methods":$methods}""",
        )
        runCurrent()
    }

    @Test
    fun r2_05_credentialInvalidSwitchesToTheNextMethodWithANewStateAndKey() =
        runTest {
            baichuan.sdkToken = true
            baichuan.auth = BaichuanAuthResult.Token("fixture-sdk-token", 3600)
            val c = controller()
            authorizeTaobao(c, """["sdk_token","web_code"]""")
            val first = binds().single()
            assertEquals(JsonPrimitive("sdk_token"), body(first)["auth_method"])
            api.fail(first, 30104, """{"reason":"credential_invalid"}""")
            runCurrent()
            assertEquals(2, api.calls.count { it.call.operationId == TradeOperations.GET_UNION_AUTH_URL })
            api.succeed(
                api.last(TradeOperations.GET_UNION_AUTH_URL),
                """{"auth_url":"https://oauth.example.test/b","state":"st_02","auth_methods":["sdk_token","web_code"]}""",
            )
            runCurrent()
            val second = binds().last()
            assertEquals(JsonPrimitive("web_code"), body(second)["auth_method"])
            assertEquals(JsonPrimitive("st_02"), body(second)["state"])
            assertNotEquals(first.call.idempotencyKey, second.call.idempotencyKey)
            api.succeed(second, """{"platform":"taobao","status":"active"}""")
            runCurrent()
            assertEquals(
                "绑定成功后刷新本页",
                TradeOperations.LIST_UNION_BINDINGS,
                api.calls
                    .last()
                    .call.operationId,
            )
            assertTrue("不重放任何 open", api.calls.none { it.call.operationId == TradeOperations.OPEN_LINK })
            assertNull(c.state.value.busyPlatform)
        }

    @Test
    fun r2_05_methodNotAllowedRetriesOnlyWithTheNewList() =
        runTest {
            val c = controller()
            authorizeTaobao(c, """["web_code"]""")
            api.fail(binds().single(), 30104, """{"reason":"method_not_allowed"}""")
            runCurrent()
            api.succeed(api.last(TradeOperations.GET_UNION_AUTH_URL), """{"state":"st_02","auth_methods":["sdk_token"]}""")
            runCurrent()
            assertEquals("新列表本端执行不了：不再绑定", 1, binds().size)
            assertEquals(
                AuthManageController.AUTH_FAILED_KEY,
                c.state.value.toast
                    ?.key,
            )
        }

    @Test
    fun r2_06_aServerErrorOnBindingShowsTheErrorWithTrace6() =
        runTest {
            val c = controller()
            authorizeTaobao(c, """["web_code"]""")
            api.fail(binds().single(), 50001)
            runCurrent()
            assertEquals(
                "error.50001",
                c.state.value.toast
                    ?.key,
            )
            assertEquals(
                FakeTradeApi.TRACE,
                c.state.value.toast
                    ?.traceId,
            )
        }
}
