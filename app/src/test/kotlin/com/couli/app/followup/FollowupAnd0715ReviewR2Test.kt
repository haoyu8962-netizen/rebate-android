package com.couli.app.followup

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PageApi
import com.couli.core.network.ReplayMode
import com.couli.core.network.ResultSignals
import com.couli.core.push.PushReportState
import com.couli.core.push.PushTokenReporter
import com.couli.core.push.PushTokenSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-0715 第 2 轮评审 R2-01（S1）：旧会话的迟到 42901 不得覆盖新会话的限流计时。
 * 真实 [PushTokenReporter] + 内存异步端口（每条请求挂起，由用例决定何时、以什么结果返回）+ 可控计时（每次计时单独一个到期开关）。
 */
class FollowupAnd0715ReviewR2Test {
    private class AsyncApi : PageApi {
        val sent = mutableListOf<ApiCall>()
        val replies = mutableListOf<CompletableDeferred<ApiResult>>()

        override suspend fun send(call: ApiCall): ApiResult {
            if (call.ownership?.sendAllowed?.invoke() == false) return ApiResult.LocalReject("call_cancelled")
            sent += call
            val reply = CompletableDeferred<ApiResult>()
            replies += reply
            return reply.await()
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs(): CallEpochs = CallEpochs(0, 0)
    }

    private class Source : PushTokenSource {
        var token: String? = "tok-1"
        var listener: () -> Unit = {}

        override fun start() = Unit

        override fun stop() = Unit

        override fun current(): String? = token

        override fun subscribe(listener: () -> Unit): () -> Unit {
            this.listener = listener
            return {}
        }

        fun change(value: String) {
            token = value
            listener()
        }
    }

    private class State : PushReportState {
        var session = "s1"

        override fun level(): String = "member"

        override fun sessionScope(): String = "full"

        override fun forceUpdate(): String = "supported"

        override fun sessionKey(): String = session

        override fun account(): String = "user-$session"

        override fun onForceUpdate() = Unit
    }

    private class Rig {
        val api = AsyncApi()
        val source = Source()
        val state = State()
        val waits = mutableListOf<Long>()
        val timers = mutableListOf<CompletableDeferred<Unit>>()
        val reporter =
            PushTokenReporter(source, state, api, CoroutineScope(Dispatchers.Unconfined)) { ms ->
                waits += ms
                val timer = CompletableDeferred<Unit>()
                timers += timer
                timer.await()
            }

        fun tokenOf(index: Int): String =
            Json
                .parseToJsonElement(api.sent[index].body.orEmpty())
                .jsonObject["token"]!!
                .jsonPrimitive.content

        fun switchTo(session: String) {
            state.session = session
            reporter.sessionChanged()
            reporter.loginSucceeded()
        }
    }

    private fun limited(millis: Long) =
        ApiResult.Failure(
            code = ResultSignals.RATE_LIMITED,
            msg = "",
            data = JsonObject(emptyMap()),
            traceId = "trace-0123456789",
            effect =
                ErrorEffect(
                    ResultSignals.RATE_LIMITED,
                    ErrorEffectKind.DISABLE_BUTTON,
                    ReplayMode.NONE,
                    registerPending = false,
                    disableMillis = millis,
                ),
            idempotencyKey = null,
        )

    private val ok = ApiResult.Success(JsonObject(emptyMap()), "trace-ok", null)

    @Test
    fun r2_01_late_42901_of_the_old_session_after_the_new_sessions_42901_keeps_the_new_limit() {
        val rig = Rig()
        rig.reporter.coldStart()
        assertEquals("S1 上报在途", 1, rig.api.sent.size)
        // 切到 S2：新登录上报，收到 42901、Retry-After 60 秒
        rig.switchTo("s2")
        assertEquals(2, rig.api.sent.size)
        rig.api.replies[1].complete(limited(60_000L))
        assertTrue(rig.reporter.rateLimited)
        assertEquals(listOf(60_000L), rig.waits)
        // S2 禁用期内有新令牌：只记待报
        rig.source.change("tok-2")
        assertEquals(2, rig.api.sent.size)
        assertTrue(rig.reporter.pending)
        // S1 的迟到 42901（Retry-After 1 秒）：被 drop，不开始、不取消、不改计时
        rig.api.replies[0].complete(limited(1_000L))
        assertEquals("旧会话响应不开始新计时", listOf(60_000L), rig.waits)
        assertEquals(1, rig.timers.size)
        assertFalse("新会话的计时没被取消", rig.timers[0].isCancelled)
        assertTrue("新会话仍在禁用期", rig.reporter.rateLimited)
        assertTrue(rig.reporter.pending)
        assertEquals("禁用期内不提前上报", 2, rig.api.sent.size)
        // S2 的 60 秒到期：按当时的令牌补发一次
        rig.timers[0].complete(Unit)
        assertEquals(3, rig.api.sent.size)
        assertEquals("tok-2", rig.tokenOf(2))
        assertFalse(rig.reporter.rateLimited)
    }

    @Test
    fun r2_01_late_42901_of_the_old_session_while_the_new_report_is_in_flight_starts_no_limit() {
        val rig = Rig()
        rig.reporter.coldStart()
        rig.switchTo("s2")
        assertEquals(2, rig.api.sent.size)
        // S2 在途时 S1 的迟到 42901 返回：不进禁用期、不计时
        rig.api.replies[0].complete(limited(1_000L))
        assertFalse(rig.reporter.rateLimited)
        assertTrue(rig.waits.isEmpty())
        assertTrue("S2 在途不受影响", rig.reporter.inFlight)
        // S2 成功后，新触发照常发（没有被旧响应禁用）
        rig.api.replies[1].complete(ok)
        rig.source.change("tok-2")
        assertEquals(3, rig.api.sent.size)
        assertEquals("tok-2", rig.tokenOf(2))
    }

    @Test
    fun r2_01_an_expired_timer_of_an_older_limit_round_does_not_lift_the_newer_limit() {
        val rig = Rig()
        // 第一轮：S1 自己的 42901（5 秒）→ 会话换代清掉；S2 第二轮 42901（60 秒）
        rig.reporter.coldStart()
        rig.api.replies[0].complete(limited(5_000L))
        assertEquals(listOf(5_000L), rig.waits)
        rig.switchTo("s2")
        rig.api.replies[1].complete(limited(60_000L))
        rig.source.change("tok-2")
        assertEquals(listOf(5_000L, 60_000L), rig.waits)
        // 旧轮次的计时即使到期（例如取消没来得及生效）也不解除新禁用期
        if (!rig.timers[0].isCancelled) rig.timers[0].complete(Unit)
        assertTrue(rig.reporter.rateLimited)
        assertEquals(2, rig.api.sent.size)
        rig.timers[1].complete(Unit)
        assertEquals(3, rig.api.sent.size)
        assertEquals("tok-2", rig.tokenOf(2))
    }
}
