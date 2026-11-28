package com.couli.feature.trade

import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-TB-WEBCODE-B 测试：[TaobaoAuthFlow] 的两处扩展（设计稿 §3.2 TaobaoAuthFlow ① ②、§6.1「确认中」、§6.4 第 4 步）。
 * - onCredential：拿到凭证之后、发 bindings 之前恰好调一次（每个凭证一次）；取消、失败不调；
 * - submit：单独调用只发一次 bindings，请求体恰好 state、auth_method、code 三项，每次一个新的幂等键，不取 auth-url、不拉起授权。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaobaoAuthFlowSubmitTest {
    private val api = FakeTradeApi()
    private val web = FakeWebCode()
    private var keySeq = 0

    /** 每次 onCredential 被调时，此刻已发出的 operationId（看它是否早于 bindings）。 */
    private val credentialSeen = mutableListOf<List<String>>()

    private fun flow(): TaobaoAuthFlow =
        TaobaoAuthFlow(
            api = api,
            webCode = web,
            baichuan = FakeBaichuan(),
            keyGenerator = {
                keySeq += 1
                "bind-key-$keySeq"
            },
            owner = { CallOwnership(api.epochs()) { true } },
            alive = { true },
            installed = { "unknown" },
            onCredential = { credentialSeen += api.ops() },
        )

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun binds() = api.calls.filter { it.call.operationId == TradeOperations.BIND_UNION }

    private fun TestScope.start(initial: String = INITIAL) = backgroundScope.async { flow().run("taobao", obj(initial)) }

    @Test
    fun f01_onCredentialIsCalledExactlyOnceAfterTheCredentialAndBeforeBindings() =
        runTest {
            val running = start()
            runCurrent()
            assertEquals("拿到凭证后恰好回调一次：$credentialSeen", 1, credentialSeen.size)
            assertTrue("回调早于 bindings：回调时已发出 ${credentialSeen.single()}", TradeOperations.BIND_UNION !in credentialSeen.single())
            assertEquals("回调之后才发 bindings", 1, binds().size)
            api.succeed(binds().single(), """{"platform":"taobao","status":"active"}""")
            runCurrent()
            assertEquals(TaobaoAuthOutcome.Bound, running.getCompleted())
            assertEquals("结束后也只调过一次", 1, credentialSeen.size)
        }

    @Test
    fun f02_cancelledOrFailedAuthorizationNeverCallsOnCredential() =
        runTest {
            listOf(AuthStepResult.Cancelled, AuthStepResult.Failed).forEach { step ->
                web.result = step
                val running = start()
                runCurrent()
                assertTrue("$step 已结束", running.isCompleted)
                assertTrue("$step 不回调：$credentialSeen", credentialSeen.isEmpty())
                assertTrue("$step 不发 bindings", binds().isEmpty())
            }
        }

    @Test
    fun f03_eachCredentialAfterA30104RecoveryGetsItsOwnCallbackBeforeItsBindings() =
        runTest {
            start()
            runCurrent()
            api.fail(binds().single(), 30104, """{"reason":"method_not_allowed"}""")
            runCurrent()
            api.succeed(
                api.last(TradeOperations.GET_UNION_AUTH_URL),
                """{"auth_url":"https://oauth.taobao.com/b","state":"st_02","auth_methods":["web_code"]}""",
            )
            runCurrent()
            assertEquals("第二个凭证再回调一次：$credentialSeen", 2, credentialSeen.size)
            assertEquals("第二次回调时只发过第一次 bindings", 1, credentialSeen[1].count { it == TradeOperations.BIND_UNION })
            assertEquals(2, binds().size)
        }

    @Test
    fun f04_submitSendsOneBindingsWithExactlyStateMethodAndCodeAndAFreshKeyEachTime() =
        runTest {
            val f = flow()
            val first = backgroundScope.async { f.submit("taobao", "st_cold", AuthCredential.WebCode("c0de_cold")) }
            runCurrent()
            assertEquals("submit 只发一次 bindings：${api.ops()}", listOf(TradeOperations.BIND_UNION), api.ops())
            val call = binds().single().call
            assertEquals(mapOf("platform" to "taobao"), call.pathParams)
            val body = obj(call.body!!)
            assertEquals("请求体恰好三项", setOf("state", "auth_method", "code"), body.keys)
            assertEquals(JsonPrimitive("st_cold"), body["state"])
            assertEquals(JsonPrimitive("web_code"), body["auth_method"])
            assertEquals(JsonPrimitive("c0de_cold"), body["code"])
            assertTrue("带幂等键", !call.idempotencyKey.isNullOrEmpty())
            api.succeed(binds().single(), """{"platform":"taobao","status":"active"}""")
            runCurrent()
            assertTrue("结果原样返回：${first.getCompleted()}", first.getCompleted() is ApiResult.Success)

            backgroundScope.async { f.submit("taobao", "st_cold", AuthCredential.WebCode("c0de_cold")) }
            runCurrent()
            assertEquals(2, binds().size)
            assertNotEquals("每次 submit 新的幂等键", binds()[0].call.idempotencyKey, binds()[1].call.idempotencyKey)
            assertTrue("submit 不拉起授权", web.urls.isEmpty())
            assertTrue("submit 不取 auth-url", TradeOperations.GET_UNION_AUTH_URL !in api.ops())
            assertTrue("submit 不调 onCredential（确认浮层由调用方管）", credentialSeen.isEmpty())
        }

    @Test
    fun f05_submitReturnsBusinessFailuresAsIsWithoutRecovery() =
        runTest {
            val running = backgroundScope.async { flow().submit("taobao", "st_cold", AuthCredential.WebCode("c0de_cold")) }
            runCurrent()
            assertEquals("submit 发出了 bindings", 1, binds().size)
            api.fail(binds().single(), 30104)
            runCurrent()
            val result = running.getCompleted()
            assertTrue("30104 原样返回：$result", result is ApiResult.Failure && result.code == 30104)
            assertEquals("不做 30104 恢复：没有再取 auth-url", listOf(TradeOperations.BIND_UNION), api.ops())
        }

    private companion object {
        const val INITIAL = """{"auth_url":"https://oauth.taobao.com/a","state":"st_01","auth_methods":["web_code"]}"""
    }
}
