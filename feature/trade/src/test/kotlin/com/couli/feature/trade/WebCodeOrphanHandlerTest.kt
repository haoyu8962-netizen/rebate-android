package com.couli.feature.trade

import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import java.security.MessageDigest
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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

/** 带账号标签、按哈希删除的内存尝试记录（只给本线单测用）。 */
internal class TaggedMemoryAttempts : WebCodeAttemptStore {
    var attempt: WebCodeAttempt? = null

    override fun record(
        state: String,
        nowMillis: Long,
    ) = record(state, nowMillis, null)

    override fun record(
        state: String,
        nowMillis: Long,
        accountTag: String?,
    ) {
        attempt = WebCodeAttempt(orphanSha(state), nowMillis, accountTag)
    }

    override fun current(): WebCodeAttempt? = attempt

    override fun clear() {
        attempt = null
    }

    override fun clearIfCurrent(stateSha256: String) {
        if (attempt?.stateSha256 == stateSha256) attempt = null
    }
}

/** sha256（UTF-8，小写十六进制）：测试自己算，不复用被测代码。 */
internal fun orphanSha(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** 记下浮层与轻提示（按发生顺序）。 */
internal class RecordingOrphanUi : WebCodeOrphanUi {
    val events = mutableListOf<String>()

    val toasts: List<String> get() = events.filter { it.startsWith("toast:") }.map { it.removePrefix("toast:") }

    val confirming: Boolean get() = events.lastOrNull { it.startsWith("confirming:") } == "confirming:true"

    override fun confirming(shown: Boolean) {
        events += "confirming:$shown"
    }

    /** 每次提示带的 traceId（与 [toasts] 一一对应；不是 5xxxx 为 null）。 */
    val traces = mutableListOf<String?>()

    override fun toast(
        key: String,
        traceId: String?,
    ) {
        events += "toast:$key"
        traces += traceId
    }
}

/** 可以设成「被暂缓」的购买待办（FU-AND-0716 ①）：记下 peek / stepDone / clear 各被调了几次。 */
internal class HoldablePending(
    private val inner: MemoryPurchasePending,
    override var held: Boolean = true,
) : PurchasePending {
    var peeks = 0
    var stepDones = 0
    var clears = 0

    override fun register(params: JsonObject): Boolean = inner.register(params)

    override fun peek(): JsonObject? {
        peeks += 1
        return inner.peek()
    }

    override fun stepDone() {
        stepDones += 1
        inner.stepDone()
    }

    override fun clear() {
        clears += 1
        inner.clear()
    }
}

/** 启动闸门替身：记下每次问的版本等待时长。 */
internal class FakeOrphanGate(
    var result: WebCodeOrphanStartup = WebCodeOrphanStartup.READY,
) : WebCodeOrphanGate {
    val asked = mutableListOf<Long>()

    override suspend fun await(versionWaitMillis: Long): WebCodeOrphanStartup {
        asked += versionWaitMillis
        return result
    }
}

/**
 * FU-AND-TB-WEBCODE-B 测试：无人认领的 web_code 回跳（[WebCodeOrphanHandler]；设计稿 §6.4 第 1–7 步与结果表；D10-3681 B-4 / C-4；
 * D10-3680 文案键只认键名）。本地假服务 [FakeTradeApi]，虚拟时钟（[testScheduler]）。时间轴：本进程启动于 [PROCESS_START]；
 * 冷启动回跳的尝试记录发起于它之前（[COLD_AT]），晚到回跳的发起于它之后（[LATE_AT]）。
 * 原文的 Denied / Error 在甲定值里合成 error=1 → [WebCodeCallback.Incomplete]（D10-2717）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebCodeOrphanHandlerTest {
    private val api = FakeTradeApi()
    private val attempts = TaggedMemoryAttempts()
    private val gate = FakeOrphanGate()
    private val ui = RecordingOrphanUi()
    private var changed = 0
    private val changes = BindingChanges().also { it.subscribe { changed += 1 } }
    private val currentTag: String? = TAG_A
    private var keySeq = 0
    private lateinit var pending: MemoryPurchasePending
    private lateinit var resume: ColdAuthResume

    private fun TestScope.handler(port: PurchasePending? = null): WebCodeOrphanHandler {
        val clock = { testScheduler.currentTime }
        pending = if (::pending.isInitialized) pending else MemoryPurchasePending(clock)
        resume = if (::resume.isInitialized) resume else ColdAuthResume(clock)
        val flow =
            TaobaoAuthFlow(
                api = api,
                webCode = FakeWebCode(available = false),
                baichuan = FakeBaichuan(),
                keyGenerator = {
                    keySeq += 1
                    "orphan-key-$keySeq"
                },
                owner = { CallOwnership(api.epochs()) { true } },
                alive = { true },
                installed = { "unknown" },
            )
        return WebCodeOrphanHandler(
            flow = flow,
            attempts = attempts,
            pending = port ?: pending,
            gate = gate,
            accountTag = { currentTag },
            resume = resume,
            bindingChanges = changes,
            ui = ui,
            scope = backgroundScope,
            processStartedAtMillis = PROCESS_START,
            clock = clock,
        )
    }

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun binds() = api.calls.filter { it.call.operationId == TradeOperations.BIND_UNION }

    /** 购买待办（kind=auth，淘宝，待办里的 link_id 是 link-old）。 */
    private fun TestScope.registerPending(
        kind: String = "auth",
        platform: String = "taobao",
    ) {
        if (!::pending.isInitialized) pending = MemoryPurchasePending { testScheduler.currentTime }
        pending.register(
            obj(
                """{"kind":"$kind","link_id":"link-old","platform":"$platform","client_owner":"page-9#1","product_key":"tb:c1"}""",
            ),
        )
    }

    private fun record(
        startedAt: Long,
        state: String = STATE,
        tag: String? = TAG_A,
    ) = attempts.record(state, startedAt, tag)

    /** 跑到 bindings 发出为止（Code 回跳）。 */
    private fun TestScope.submitCode(
        startedAt: Long = COLD_AT,
        state: String = STATE,
    ): Deferred<Unit> {
        record(startedAt)
        val h = handler()
        val running = backgroundScope.async { h.handle(WebCodeCallback.Code(CODE, state)) }
        runCurrent()
        // 先断言再取 binds().single()：桩上在这里以 AssertionError 红，而不是 NoSuchElementException
        assertEquals("Code 回跳应提交一次 bindings：${api.ops()}", 1, binds().size)
        return running
    }

    private fun assertNoOpen() = assertTrue("不重放 open：${api.ops()}", TradeOperations.OPEN_LINK !in api.ops())

    // ---------------------------------------------------------------- 第 1 步：启动闸门

    @Test
    fun o01_basicModeLoggedOutOrBelowMinimumVersionDropsTheCallback() =
        runTest {
            listOf(WebCodeOrphanStartup.BASIC_MODE, WebCodeOrphanStartup.LOGGED_OUT, WebCodeOrphanStartup.BELOW_MIN_VERSION).forEach {
                gate.result = it
                gate.asked.clear()
                registerPending()
                record(COLD_AT)
                val h = handler()
                backgroundScope.async { h.handle(WebCodeCallback.Code(CODE, STATE)) }
                runCurrent()
                assertEquals("$it：先等启动闸门（版本检查最多 5 秒）", listOf(5_000L), gate.asked)
                assertTrue("$it：不发任何请求：${api.ops()}", api.calls.isEmpty())
                assertTrue("$it：没有浮层、没有提示：${ui.events}", ui.events.isEmpty())
                assertNotNull("$it：待办不动", pending.peek())
            }
        }

    @Test
    fun o02_versionStillUnknownAfterFiveSecondsDoesNotSubmitAndDropsTheCallback() =
        runTest {
            // D10-3681 B-4：冷启动回跳时版本检查 5 秒内仍未知 → 不提交 bindings、丢弃这次回跳
            gate.result = WebCodeOrphanStartup.VERSION_UNKNOWN
            registerPending()
            record(COLD_AT)
            val h = handler()
            val running = backgroundScope.async { h.handle(WebCodeCallback.Code(CODE, STATE)) }
            runCurrent()
            assertEquals("版本检查等 5 秒（03 §4.4）", listOf(WebCodeOrphanHandler.VERSION_WAIT_MILLIS), gate.asked)
            assertEquals(5_000L, WebCodeOrphanHandler.VERSION_WAIT_MILLIS)
            assertTrue("处理结束", running.isCompleted)
            assertTrue("不提交 bindings：${api.ops()}", binds().isEmpty())
            assertTrue("没有浮层、没有提示：${ui.events}", ui.events.isEmpty())
            assertNull("不登记恢复槽", resume.peek())
        }

    // ---------------------------------------------------------------- 第 2 步：核对尝试记录

    @Test
    fun o03_missingRecordWrongHashOrOtherAccountIsSilentlyDropped() =
        runTest {
            registerPending()
            val cases =
                listOf<Pair<String, () -> Unit>>(
                    "没有记录" to { attempts.clear() },
                    "sha256(state) 不一致" to { record(COLD_AT, state = "st_other") },
                    "账号标签不是当前账号" to { record(COLD_AT, tag = "tag-B") },
                    "记录没有账号标签" to { record(COLD_AT, tag = null) },
                )
            cases.forEach { (name, arrange) ->
                arrange()
                val before = attempts.current()
                val h = handler()
                backgroundScope.async { h.handle(WebCodeCallback.Code(CODE, STATE)) }
                backgroundScope.async { h.handle(WebCodeCallback.Incomplete(STATE)) }
                runCurrent()
                assertTrue("$name：不发任何请求：${api.ops()}", api.calls.isEmpty())
                assertTrue("$name：静默（没有浮层、没有提示）：${ui.events}", ui.events.isEmpty())
                assertNotNull("$name：待办不动", pending.peek())
                // 评审 r1 F3：核对不符不删记录（伪造的回跳删不掉真实尝试的记录）
                assertEquals("$name：尝试记录仍是原来那条", before, attempts.current())
            }
            // 评审 r1 F3：哈希不符、账号不符两种各自单独再核一遍，记录逐字段不变
            record(COLD_AT, state = "st_other")
            val wrongHash = attempts.current()
            assertNotNull("前提：有一条别的 state 的记录", wrongHash)
            val h1 = handler()
            backgroundScope.async { h1.handle(WebCodeCallback.Code(CODE, STATE)) }
            runCurrent()
            assertEquals("sha256(state) 不符：记录仍在", WebCodeAttempt(orphanSha("st_other"), COLD_AT, TAG_A), attempts.current())
            record(COLD_AT, tag = "tag-B")
            val h2 = handler()
            backgroundScope.async { h2.handle(WebCodeCallback.Code(CODE, STATE)) }
            backgroundScope.async { h2.handle(WebCodeCallback.Incomplete(STATE)) }
            runCurrent()
            assertEquals("账号标签不符：记录仍在", WebCodeAttempt(orphanSha(STATE), COLD_AT, "tag-B"), attempts.current())
            assertTrue("都不发请求：${api.ops()}", api.calls.isEmpty())
        }

    @Test
    fun o04_aMatchingRecordIsDeletedOnceChecked() =
        runTest {
            val running = submitCode(startedAt = LATE_AT)
            assertNull("核对通过就删记录", attempts.current())
            assertEquals("核对通过后才提交", 1, binds().size)
            api.succeed(binds().single(), ACTIVE)
            runCurrent()
            assertTrue(running.isCompleted)
        }

    // ---------------------------------------------------------------- 第 3 步：error=1（Denied / Error）

    @Test
    fun o05_coldStartIncompleteWithThePendingStillThereClearsItAndToastsOnce() =
        runTest {
            registerPending()
            record(COLD_AT)
            val h = handler()
            backgroundScope.async { h.handle(WebCodeCallback.Incomplete(STATE)) }
            runCurrent()
            assertEquals("提示一次 error.20004", listOf("error.20004"), ui.toasts)
            assertNull("删除待办", pending.peek())
            assertNull("删除记录", attempts.current())
            assertTrue("不发请求", api.calls.isEmpty())
            assertNull("不登记恢复槽", resume.peek())
        }

    @Test
    fun o06_lateIncompleteDoesNothingEvenWithAPendingAround() =
        runTest {
            registerPending()
            record(LATE_AT)
            val h = handler()
            backgroundScope.async { h.handle(WebCodeCallback.Incomplete(STATE)) }
            runCurrent()
            assertNull("核对通过：记录删掉", attempts.current())
            assertTrue("晚到回跳：不提示：${ui.events}", ui.events.isEmpty())
            assertNotNull("晚到回跳：待办不动", pending.peek())
            assertTrue("不发请求", api.calls.isEmpty())
        }

    // ---------------------------------------------------------------- 第 4 步：提交 bindings

    @Test
    fun o07_codeSubmitsBindingsWithThreeFieldsAndAFreshKeyBehindTheConfirmingOverlay() =
        runTest {
            registerPending()
            val running = submitCode()
            val call = binds().singleOrNull()?.call
            assertNotNull("Code → 提交 bindings：${api.ops()}", call)
            assertEquals(mapOf("platform" to "taobao"), call!!.pathParams)
            val body = obj(call.body!!)
            assertEquals("请求体恰好三项", setOf("state", "auth_method", "code"), body.keys)
            assertEquals(JsonPrimitive(STATE), body["state"])
            assertEquals(JsonPrimitive("web_code"), body["auth_method"])
            assertEquals(JsonPrimitive(CODE), body["code"])
            assertEquals("新的幂等键", "orphan-key-1", call.idempotencyKey)
            assertEquals("提交期间显示不可关闭的「正在确认」浮层：${ui.events}", listOf("confirming:true"), ui.events)
            api.succeed(binds().single(), ACTIVE)
            runCurrent()
            assertTrue(running.isCompleted)
            assertTrue("结束后浮层消失：${ui.events}", !ui.confirming && ui.events.contains("confirming:false"))
            assertEquals("只提交一次", 1, binds().size)
            assertNoOpen()
        }

    // ---------------------------------------------------------------- 第 5 步：结果表

    @Test
    fun o08_activeWithAValidPendingRenewsItAndOffersAResumeTicket() =
        runTest {
            registerPending()
            advanceTimeBy(5 * 60_000L)
            submitCode()
            val renewedAt = testScheduler.currentTime
            api.succeed(binds().single(), ACTIVE)
            runCurrent()
            assertEquals("发出「绑定变化」信号一次", 1, changed)
            assertEquals(
                "登记恢复槽：待办里的 link_id、product_key，deadline = 续时后 + 10 分钟",
                ColdAuthResume.Ticket("link-old", "tb:c1", "taobao", renewedAt + 600_000L),
                resume.peek(),
            )
            advanceTimeBy(9 * 60_000L)
            assertNotNull("stepDone 续时：原登记 14 分钟后待办仍在", pending.peek())
            assertTrue("交给页面认领，不提示：${ui.toasts}", ui.toasts.isEmpty())
            assertNoOpen()
        }

    @Test
    fun o09_activeWithoutAPendingOnlyToastsCompletedTapAgain() =
        runTest {
            submitCode()
            api.succeed(binds().single(), ACTIVE)
            runCurrent()
            assertEquals("发出「绑定变化」信号", 1, changed)
            assertEquals("只提示再点一次", listOf("auth.taobao.completed_tap_again"), ui.toasts)
            assertNull("不登记恢复槽", resume.peek())
            assertNoOpen()
        }

    @Test
    fun o10_activeWithAnExpiredPendingOnlyToasts() =
        runTest {
            registerPending()
            advanceTimeBy(10 * 60_000L + 1)
            submitCode()
            api.succeed(binds().single(), ACTIVE)
            runCurrent()
            assertEquals("待办过了 10 分钟：只提示", listOf("auth.taobao.completed_tap_again"), ui.toasts)
            assertNull("不登记恢复槽", resume.peek())
            assertNoOpen()
        }

    @Test
    fun o11_activeWithAPendingThatIsNotATaobaoAuthPendingOnlyToasts() =
        runTest {
            listOf("login" to "taobao", "auth" to "pdd").forEach { (kind, platform) ->
                ui.events.clear()
                api.calls.clear()
                registerPending(kind = kind, platform = platform)
                submitCode()
                api.succeed(binds().single(), ACTIVE)
                runCurrent()
                assertEquals("kind=$kind platform=$platform 不算有效待办：只提示", listOf("auth.taobao.completed_tap_again"), ui.toasts)
                assertNull("不登记恢复槽", resume.peek())
                assertNotNull("别的待办不动", pending.peek())
            }
        }

    @Test
    fun o12_anUnclaimedTicketExpiresIntoATapAgainToastWithoutOpeningAnything() =
        runTest {
            // D10-3681 C-4：到期无人认领不自动打开商品页，只提示 auth.taobao.completed_tap_again
            registerPending()
            submitCode()
            api.succeed(binds().single(), ACTIVE)
            runCurrent()
            assertNotNull("先登记了恢复槽", resume.peek())
            advanceTimeBy(WebCodeOrphanHandler.RESUME_WINDOW_MILLIS - 1)
            runCurrent()
            assertTrue("到期之前不提示：${ui.toasts}", ui.toasts.isEmpty())
            advanceTimeBy(2)
            runCurrent()
            assertEquals("到期无人认领：只提示一次", listOf("auth.taobao.completed_tap_again"), ui.toasts)
            assertNull("恢复槽作废", resume.peek())
            assertNoOpen()
            assertEquals("没有别的请求", listOf(TradeOperations.BIND_UNION), api.ops())
        }

    @Test
    fun o13_aClaimedTicketDoesNotToastAtTheDeadline() =
        runTest {
            registerPending()
            submitCode()
            api.succeed(binds().single(), ACTIVE)
            runCurrent()
            assertNotNull("页面认领", resume.claim("link-new", "tb:c1"))
            advanceTimeBy(WebCodeOrphanHandler.RESUME_WINDOW_MILLIS + 1)
            runCurrent()
            assertTrue("已认领：到期不提示：${ui.toasts}", ui.toasts.isEmpty())
        }

    @Test
    fun o14_expiredStateOr20001ClearsThePendingAndToasts30104WithoutReauthorizing() =
        runTest {
            listOf(30104, 20001).forEach { code ->
                ui.events.clear()
                api.calls.clear()
                registerPending()
                submitCode()
                api.fail(binds().single(), code)
                runCurrent()
                assertEquals("$code：提示 error.30104", listOf("error.30104"), ui.toasts)
                assertNull("$code：删除待办", pending.peek())
                assertEquals("$code：不自动拉起授权（不取 auth-url、不再绑定）", listOf(TradeOperations.BIND_UNION), api.ops())
                assertTrue("$code：浮层已收起", !ui.confirming)
                assertNull(resume.peek())
            }
        }

    @Test
    fun o15_sharerBlockedCodesClearThePendingAndToastTheirOwnText() =
        runTest {
            listOf(30151, 30153).forEach { code ->
                ui.events.clear()
                api.calls.clear()
                registerPending()
                submitCode()
                api.fail(binds().single(), code)
                runCurrent()
                assertEquals("$code：提示一次：${ui.toasts}", 1, ui.toasts.size)
                assertTrue("$code：提示该码的文案：${ui.toasts}", ui.toasts.single().startsWith("error.$code"))
                assertNull("$code：删除待办", pending.peek())
                assertTrue(!ui.confirming)
                assertNoOpen()
            }
        }

    @Test
    fun o16_authUnavailableClearsThePendingAndToastsTheAuthManageText() =
        runTest {
            listOf(30101, 30102).forEach { code ->
                ui.events.clear()
                api.calls.clear()
                registerPending()
                submitCode()
                api.fail(binds().single(), code, """{"reason":"auth_unavailable"}""")
                runCurrent()
                assertEquals("$code auth_unavailable：auth_manage 那类文案", listOf("error.$code.auth_unavailable.auth_manage"), ui.toasts)
                assertNull("$code：删除待办", pending.peek())
                assertTrue(!ui.confirming)
                assertNoOpen()
            }
        }

    @Test
    fun o17_rateLimitServerErrorOrTransportFailureKeepsThePendingAndToastsAnError() =
        runTest {
            val cases =
                listOf<Triple<String, (FakeTradeApi.Pending) -> Unit, (String) -> Boolean>>(
                    Triple("42901", { p -> api.fail(p, 42901, disableMillis = 5_000L) }, { it == "error.42901" }),
                    Triple("50001", { p -> api.fail(p, 50001) }, { it.startsWith("error.5") }),
                    Triple(
                        "传输失败",
                        { p -> p.result.complete(ApiResult.TransportFailure("timeout", null, p.call.idempotencyKey, false)) },
                        { it == "buy.timeout" },
                    ),
                )
            cases.forEach { (name, answer, expected) ->
                ui.events.clear()
                api.calls.clear()
                registerPending()
                submitCode()
                answer(binds().single())
                runCurrent()
                assertEquals("$name：提示一次：${ui.toasts}", 1, ui.toasts.size)
                assertTrue("$name：网络或服务错误文案：${ui.toasts}", expected(ui.toasts.single()))
                // 评审 r1 F6：5xxxx 带 trace6（与购买页同一个码一致）；42901、传输失败不带
                assertEquals(
                    "$name：traceId",
                    listOf(if (name == "50001") FakeTradeApi.TRACE else null),
                    ui.traces.takeLast(1),
                )
                assertNotNull("$name：保留待办", pending.peek())
                assertNull("$name：不登记恢复槽", resume.peek())
                assertTrue("$name：浮层已收起", !ui.confirming)
                assertEquals("$name：不重试、不重放", listOf(TradeOperations.BIND_UNION), api.ops())
            }
        }

    // ---------------------------------------------------------------- 第 7 步：晚到回跳

    @Test
    fun o18_lateCodeOnlyUpdatesTheBindingAndNeverReplaysOpen() =
        runTest {
            // 晚到回跳：本次尝试已按取消处理；就算还有一条淘宝授权待办，也一定落到「没有有效待办」
            registerPending()
            submitCode(startedAt = LATE_AT)
            assertEquals("晚到回跳也提交 bindings（只更新授权状态）", 1, binds().size)
            api.succeed(binds().single(), ACTIVE)
            runCurrent()
            assertEquals("发出「绑定变化」信号", 1, changed)
            assertNull("不登记恢复槽", resume.peek())
            assertEquals("只提示再点一次", listOf("auth.taobao.completed_tap_again"), ui.toasts)
            assertNoOpen()
        }

    // ---------------------------------------------------------------- 修改 2（评审 r1 F5、F6）

    @Test
    fun o19_successButNotActiveClearsTheColdPendingAndToasts20004() =
        runTest {
            // 自定口径 (1)：200 但 status 不是 active → 按授权未完成：删冷启动的有效待办、提示 error.20004
            registerPending()
            submitCode()
            api.succeed(binds().single(), """{"platform":"taobao","status":"pending"}""")
            runCurrent()
            assertEquals("提示 error.20004", listOf("error.20004"), ui.toasts)
            assertNull("删掉冷启动的有效待办", pending.peek())
            assertNull("不登记恢复槽", resume.peek())
            assertEquals("不发「绑定变化」信号", 0, changed)
            assertTrue("浮层已收起", !ui.confirming)
            assertNoOpen()
            advanceTimeBy(WebCodeOrphanHandler.RESUME_WINDOW_MILLIS + 1)
            runCurrent()
            assertEquals("之后也没有「再点一次」的提示", listOf("error.20004"), ui.toasts)
        }

    @Test
    fun o19b_lateSuccessButNotActiveToasts20004AndLeavesThePendingAlone() =
        runTest {
            registerPending()
            submitCode(startedAt = LATE_AT)
            api.succeed(binds().single(), """{"platform":"taobao"}""")
            runCurrent()
            assertEquals("没有 status 也按授权未完成", listOf("error.20004"), ui.toasts)
            assertNotNull("晚到回跳不动待办", pending.peek())
            assertNull(resume.peek())
            assertEquals(0, changed)
        }

    @Test
    fun o20_aHeldPendingCountsAsNoPendingForActive() =
        runTest {
            // 自定口径 (3)：待办被暂缓（FU-AND-0716 ①）时当作没有待办：不读、不续时、不删，只提示再点一次
            registerPending()
            val held = HoldablePending(pending)
            record(COLD_AT)
            val h = handler(held)
            backgroundScope.async { h.handle(WebCodeCallback.Code(CODE, STATE)) }
            runCurrent()
            assertEquals(1, binds().size)
            api.succeed(binds().single(), ACTIVE)
            runCurrent()
            assertEquals("发出「绑定变化」信号", 1, changed)
            assertEquals("只提示再点一次", listOf("auth.taobao.completed_tap_again"), ui.toasts)
            assertNull("不登记恢复槽", resume.peek())
            assertEquals("暂缓期间不读待办", 0, held.peeks)
            assertEquals("不 stepDone", 0, held.stepDones)
            assertEquals("不删", 0, held.clears)
            assertNotNull("待办原样留着", pending.peek())
            assertNoOpen()
        }

    @Test
    fun o20b_aHeldPendingIsNotClearedByIncompleteOrStateExpired() =
        runTest {
            registerPending()
            val held = HoldablePending(pending)
            record(COLD_AT)
            val h = handler(held)
            backgroundScope.async { h.handle(WebCodeCallback.Incomplete(STATE)) }
            runCurrent()
            assertTrue("暂缓的待办当作没有：error=1 不提示：${ui.events}", ui.events.isEmpty())
            record(COLD_AT)
            backgroundScope.async { h.handle(WebCodeCallback.Code(CODE, STATE)) }
            runCurrent()
            api.fail(binds().single(), 30104)
            runCurrent()
            assertEquals("30104 照样提示", listOf("error.30104"), ui.toasts)
            assertEquals("暂缓的待办不读", 0, held.peeks)
            assertEquals("暂缓的待办不删", 0, held.clears)
            assertNotNull("待办原样留着", pending.peek())
        }

    @Test
    fun o21_serverErrorsCarryTheSameTextAndTrace6AsThePurchasePage() =
        runTest {
            // 评审 r1 F6：冷启动回跳 bindings 返回 5xxxx → 提示与购买页同一个码的提示一致（同一文案键、带 trace6，A-R3-04）
            listOf(50001, 50303).forEach { code ->
                ui.events.clear()
                ui.traces.clear()
                api.calls.clear()
                registerPending()
                submitCode()
                api.fail(binds().single(), code)
                runCurrent()
                val page = purchasePageBindFailure(code)
                assertNotNull("$code：前提：购买页给的是 AuthCheckFailed", page)
                assertEquals("$code：与购买页同一个文案键", listOf(page!!.textKey), ui.toasts)
                assertEquals("$code：带 trace6（与购买页同一个 traceId）", listOf(page.traceId), ui.traces)
                assertEquals("$code：traceId 来自响应", FakeTradeApi.TRACE, ui.traces.single())
                assertNotNull("$code：保留待办", pending.peek())
            }
            listOf(30199, 42901).forEach { code ->
                ui.events.clear()
                ui.traces.clear()
                api.calls.clear()
                registerPending()
                submitCode()
                api.fail(binds().single(), code)
                runCurrent()
                assertEquals("$code：不是 5xxxx，不带 trace6", listOf<String?>(null), ui.traces)
            }
        }

    /** 购买页里同一个码的 bindings 失败给出的弹窗（热路径：open 30101 → AuthSheet → web_code → bindings 失败）。 */
    private fun TestScope.purchasePageBindFailure(code: Int): PurchaseDialog.AuthCheckFailed? {
        val pageApi = FakeTradeApi()
        val ports = FakePorts()
        val baichuan = FakeBaichuan()
        val env = FakeEnv()
        val executor = TradeJumpExecutor(ports, baichuan, { }, { testScheduler.currentTime }, { TradeFixtures.NOW })
        val c =
            OpenPurchaseController(
                api = pageApi,
                executor = executor,
                ports = ports,
                env = env,
                pending = MemoryPurchasePending { testScheduler.currentTime },
                counter = AuthSheetCounter(),
                tips = JumpTipService(pageApi) { "session" },
                webCode = FakeWebCode(),
                baichuan = baichuan,
                scope = backgroundScope,
                clock = { testScheduler.currentTime },
                keyGenerator = { "page-key-${pageApi.calls.size}" },
                browserNoticeMillis = 0L,
            )
        env.card = TradeFixtures.card()
        c.buy(env.card!!, null)
        runCurrent()
        pageApi.fail(
            pageApi.last(TradeOperations.OPEN_LINK),
            30101,
            """{"auth_url":"https://oauth.taobao.com/a","state":"st_01","auth_methods":["web_code"]}""",
        )
        runCurrent()
        c.authorize()
        runCurrent()
        pageApi.fail(pageApi.last(TradeOperations.BIND_UNION), code)
        runCurrent()
        return c.state.value.dialog as? PurchaseDialog.AuthCheckFailed
    }

    private companion object {
        const val STATE = "st_cold_1"
        const val CODE = "c0de_cold_1"
        const val TAG_A = "tag-A"
        const val PROCESS_START = 10_000L
        const val COLD_AT = 5_000L
        const val LATE_AT = 20_000L
        const val ACTIVE = """{"platform":"taobao","status":"active"}"""
    }
}
