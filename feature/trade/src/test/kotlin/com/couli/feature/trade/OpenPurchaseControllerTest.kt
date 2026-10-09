package com.couli.feature.trade

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-06b 购买执行器的集成测试（本地假服务 [FakeTradeApi]，假时钟）：8 秒超时与同键重试、取消 / 离页 / 后台之后的迟到响应只更新卡片不外跳、
 * 再次点击后旧响应丢弃、授权后新键自动执行一次、改价确认、比价无返利、跳转方案按顺序执行且每步 link_jump 带同一 attempt_id、
 * auth_jump 不上报 link_jump、登录后同键恢复、平台状态与限流。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenPurchaseControllerTest {
    private val api = FakeTradeApi()
    private val ports = FakePorts()
    private val baichuan = FakeBaichuan()
    private val web = FakeWebCode()
    private val env = FakeEnv()
    private val pending = MemoryPurchasePending()
    private val counter = AuthSheetCounter()
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
            counter = counter,
            tips = JumpTipService(api) { "session" },
            webCode = web,
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

    /** 回答 JumpTip 已读查询（已读 → 不弹须知）。 */
    private fun TestScope.answerTips(read: Boolean = true) {
        runCurrent()
        val tips = api.calls.lastOrNull { it.call.operationId == TradeOperations.GET_TIPS && !it.result.isCompleted } ?: return
        val readTips = """{"jump_tip":{"taobao":"2026-10-01T00:00:00+08:00","pdd":"2026-10-01T00:00:00+08:00"},"inviter_before_buy":null}"""
        api.succeed(tips, if (read) readTips else """{"jump_tip":{},"inviter_before_buy":null}""")
        runCurrent()
    }

    private fun opens() = api.calls.filter { it.call.operationId == TradeOperations.OPEN_LINK }

    @Test
    fun eightSecondsTimeoutThenRetryKeepsTheSameKey() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, "product_detail.buy_bar.1")
            runCurrent()
            assertEquals(PurchasePhase.LOADING, c.state.value.phase)
            val first = opens().single()
            assertEquals(mapOf("link_id" to "link-1"), first.call.pathParams)
            assertEquals(
                "unknown",
                Json
                    .parseToJsonElement(first.call.body!!)
                    .jsonObject["installed"]!!
                    .jsonPrimitive.content,
            )
            advanceTimeBy(OpenPurchaseController.TIMEOUT_MILLIS - 1)
            runCurrent()
            assertEquals("7999 毫秒还在加载", PurchasePhase.LOADING, c.state.value.phase)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("8000 毫秒超时", PurchasePhase.TIMEOUT, c.state.value.phase)
            assertEquals("不自动重试", 1, opens().size)
            c.retryAfterTimeout()
            runCurrent()
            val second = opens().last()
            assertEquals("重试沿用原键", first.call.idempotencyKey, second.call.idempotencyKey)
            // 旧请求迟到：丢弃（同键的新请求会拿到同一个结果）
            api.succeed(first, TradeFixtures.openResult(attempt = "attempt-same"))
            runCurrent()
            assertTrue("旧请求的响应不外跳", ports.launched.isEmpty())
            api.succeed(second, TradeFixtures.openResult(attempt = "attempt-same"))
            answerTips()
            assertEquals(listOf("scheme:demo-app://item"), ports.launched)
            assertEquals("attempt-same", events.single()["attempt_id"]!!.jsonPrimitive.content)
        }

    @Test
    fun cancelledThenLateResponseOnlyUpdatesTheCardAndNeverJumps() =
        runTest {
            env.loggedIn = false
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            c.cancelLoading()
            assertEquals(PurchasePhase.IDLE, c.state.value.phase)
            api.succeed(opens().single(), TradeFixtures.openResult(priceChanged = true, newFinal = 4990, newLink = "link-2"))
            runCurrent()
            assertEquals("卡片换成新 link", "link-2", env.card!!["link_id"]!!.jsonPrimitive.content)
            assertEquals(
                4990L,
                env.card!!["final_price_fen"]!!
                    .jsonPrimitive.content
                    .toLong(),
            )
            assertNull("不弹确认", c.state.value.dialog)
            assertTrue("不外跳", ports.launched.isEmpty())
            assertTrue("不上报 link_jump", events.isEmpty())
        }

    @Test
    fun backgroundThenLateResponseOnlyUpdatesTheCard() =
        runTest {
            // 游客态不查 JumpTip：没有中断时这条响应会直接外跳，这里断言后台之后不外跳
            env.loggedIn = false
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            c.onBackground()
            api.succeed(opens().single(), TradeFixtures.openResult(newLink = "link-9"))
            runCurrent()
            assertEquals("link-9", env.card!!["link_id"]!!.jsonPrimitive.content)
            assertTrue(ports.launched.isEmpty())
            assertTrue(events.isEmpty())
        }

    @Test
    fun leavingCancelsTheRequestAndTheLateResponseChangesNothing() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            c.leave()
            assertEquals("离页取消在途请求", 1, api.cancelled.size)
            assertFalse("归属已失效", api.cancelled.single().stillOwned())
            api.succeed(opens().single(), TradeFixtures.openResult(newLink = "link-9"))
            runCurrent()
            assertEquals("link-1", env.card!!["link_id"]!!.jsonPrimitive.content)
            assertTrue(ports.launched.isEmpty())
        }

    @Test
    fun aClickAfterCancelDropsTheOlderResponse() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            c.cancelLoading()
            c.buy(env.card!!, null)
            runCurrent()
            val (old, new) = opens()
            assertNotEquals("再次点击是新键", old.call.idempotencyKey, new.call.idempotencyKey)
            api.succeed(old, TradeFixtures.openResult(newLink = "link-old"))
            runCurrent()
            assertEquals("旧点击的响应丢弃，不覆盖卡片", "link-1", env.card!!["link_id"]!!.jsonPrimitive.content)
            api.succeed(new, TradeFixtures.openResult(attempt = "attempt-new"))
            answerTips()
            assertEquals(1, ports.launched.size)
        }

    @Test
    fun fallbacksRunInServerOrderAndEveryStepReportsTheSameAttempt() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            val plan =
                TradeFixtures.plan(
                    "sdk" to "https://s.click.example.test/t",
                    "scheme" to "demo-app://x",
                    "h5" to "https://u.example.test/a",
                )
            api.succeed(
                opens().single(),
                TradeFixtures.openResult(attempt = "attempt-7", plan = plan, newLink = "link-new", priceChanged = false),
            )
            answerTips()
            assertEquals("sdk 初始化失败重试 1 次", 1, baichuan.retries)
            assertEquals(listOf("scheme:demo-app://x", "browser:https://u.example.test/a"), ports.launched)
            assertEquals(listOf("sdk", "scheme", "h5"), events.map { it["step"]!!.jsonPrimitive.content })
            assertEquals(listOf("sdk_init_failed", "failed", "launched"), events.map { it["result"]!!.jsonPrimitive.content })
            assertTrue("同一个 attempt_id", events.all { it["attempt_id"]!!.jsonPrimitive.content == "attempt-7" })
            assertTrue("用替换后的 link_id", events.all { it["link_id"]!!.jsonPrimitive.content == "link-new" })
            assertTrue("不带推广 URL", events.none { "value" in it })
        }

    @Test
    fun priceChangeAsksFirstReplacesTheCardAndJumpsOnlyAfterConfirm() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(
                opens().single(),
                TradeFixtures.openResult(priceChanged = true, newFinal = 4990, newLink = "link-2", newMin = 0, newMax = 0),
            )
            runCurrent()
            val dialog = c.state.value.dialog as PurchaseDialog.ConfirmPrice
            assertTrue("改价且返利归零合并确认", dialog.rebateGone)
            assertEquals("确认之前就替换卡片", "link-2", env.card!!["link_id"]!!.jsonPrimitive.content)
            assertEquals("no_rebate", env.card!!["rebate_basis"]!!.jsonPrimitive.content)
            // 券前价、券额响应没有：置未知、不用旧券额反推，重取权威卡片（A-R1-07）
            assertEquals(JsonNull, env.card!!["price_fen"])
            assertEquals(
                "重取权威卡片",
                "link-2",
                env.refetchedCards
                    .single()["link_id"]!!
                    .jsonPrimitive.content,
            )
            assertTrue(ports.launched.isEmpty())
            c.dismissDialog()
            assertEquals(
                OpenPurchaseController.CANCELLED_PRICE_REBATE_KEY,
                c.state.value.toast
                    ?.key,
            )
            assertTrue("取消不外跳", ports.launched.isEmpty())
        }

    @Test
    fun rebateGoneConfirmJumpsAfterTheUserContinues() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(opens().single(), TradeFixtures.openResult(newMin = 0, newMax = 0))
            runCurrent()
            assertTrue(c.state.value.dialog is PurchaseDialog.ConfirmRebateGone)
            c.confirm()
            answerTips()
            assertEquals(1, ports.launched.size)
        }

    @Test
    fun priceCompareOffersSimilarItemsOnTheSamePlatform() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card(platform = "pdd")
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(opens().single(), TradeFixtures.openResult(newMin = 0, newMax = 0, cause = "price_compare"))
            runCurrent()
            assertEquals(PurchaseDialog.CompareNoRebate, c.state.value.dialog)
            c.similar()
            val (route, params) = env.opened.single()
            assertEquals("Search", route)
            assertEquals("pdd", params["platform"]!!.jsonPrimitive.content)
            assertEquals("milk", params["q"]!!.jsonPrimitive.content)
        }

    @Test
    fun taobaoAuthorizationResendsOnceWithANewKeyAfterTheServerBinds() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            val first = opens().single()
            val unbound = """{"auth_url":"https://oauth.example.test/a","state":"st_01","auth_methods":["sdk_token","web_code"]}"""
            api.fail(first, 30101, unbound)
            runCurrent()
            assertEquals(PurchaseDialog.AuthSheet("taobao"), c.state.value.dialog)
            assertTrue("登记了 pending_action", pending.peek() != null)
            c.authorize()
            runCurrent()
            assertEquals("按 auth_methods 取本端能执行的第一种（sdk_token 不可用 → web_code）", listOf("https://oauth.example.test/a"), web.urls)
            val bind = api.last(TradeOperations.BIND_UNION)
            assertEquals(mapOf("platform" to "taobao"), bind.call.pathParams)
            val body = Json.parseToJsonElement(bind.call.body!!).jsonObject
            assertEquals(setOf("state", "auth_method", "code"), body.keys)
            assertNotEquals("bindings 用新键", first.call.idempotencyKey, bind.call.idempotencyKey)
            api.succeed(bind, """{"platform":"taobao","status":"active"}""")
            runCurrent()
            val resend = opens().last()
            assertEquals("授权后自动执行一次 open", 2, opens().size)
            assertNotEquals("授权后是新请求、新键", first.call.idempotencyKey, resend.call.idempotencyKey)
            assertNull("待办已删除", pending.peek())
            api.succeed(resend, TradeFixtures.openResult())
            answerTips()
            assertEquals(1, ports.launched.size)
        }

    @Test
    fun closingTheSheetOffersNoRebatePurchaseAndRetry() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.fail(opens().single(), 30101, """{"auth_url":"https://oauth.example.test/a","state":"st_01","auth_methods":["web_code"]}""")
            runCurrent()
            c.dismissDialog()
            assertEquals(PurchaseDialog.AuthDeclined("taobao", failed = false), c.state.value.dialog)
            assertNull("关闭授权删除待办", pending.peek())
            c.buyWithoutRebate()
            runCurrent()
            val body = Json.parseToJsonElement(opens().last().call.body!!).jsonObject
            assertEquals(JsonPrimitive(true), body["no_rebate"])
            assertEquals("auth_declined", body["no_rebate_reason"]!!.jsonPrimitive.content)
        }

    @Test
    fun theThirdAuthorizationCodeInASessionGoesStraightToTheChoice() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            repeat(3) { round ->
                c.buy(env.card!!, null)
                runCurrent()
                api.fail(opens().last(), 30101, """{"auth_url":"https://oauth.example.test/a","state":"st","auth_methods":["web_code"]}""")
                runCurrent()
                val dialog = c.state.value.dialog
                if (round <
                    2
                ) {
                    assertTrue(dialog is PurchaseDialog.AuthSheet)
                } else {
                    assertEquals(PurchaseDialog.AuthDeclined("taobao", false), dialog)
                }
                c.dismissDialog()
                c.dismissDialog()
            }
        }

    @Test
    fun authUnavailableOnlyNotifies() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.fail(opens().single(), 30102, """{"reason":"auth_unavailable"}""")
            runCurrent()
            assertEquals(PurchaseDialog.AuthUnavailable("error.30102.auth_unavailable"), c.state.value.dialog)
            assertNull(pending.peek())
        }

    @Test
    fun pddAuthJumpUsesTheExecutorWithoutLinkJumpAndResumesAfterTheServerConfirms() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card(platform = "pdd")
            c.buy(env.card!!, null)
            runCurrent()
            val first = opens().single()
            val jump = TradeFixtures.plan("scheme" to "demo-pdd://auth", "h5" to "https://auth.example.test/pdd")
            api.fail(first, 30111, """{"auth_jump":$jump}""")
            runCurrent()
            assertEquals(PurchaseDialog.AuthSheet("pdd"), c.state.value.dialog)
            c.authorize()
            runCurrent()
            assertEquals("同一个执行器、h5 交系统浏览器", listOf("scheme:demo-pdd://auth", "browser:https://auth.example.test/pdd"), ports.launched)
            assertTrue("auth_jump 不上报 link_jump", events.isEmpty())
            c.onForeground()
            runCurrent()
            api.succeed(api.last(TradeOperations.LIST_UNION_BINDINGS), """{"items":[{"platform":"pdd","status":"active"}]}""")
            runCurrent()
            assertEquals(2, opens().size)
            assertNotEquals(first.call.idempotencyKey, opens().last().call.idempotencyKey)
        }

    @Test
    fun pddAuthNotFoundAfterReturningDropsThePendingAndOffersTheChoice() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card(platform = "pdd")
            c.buy(env.card!!, null)
            runCurrent()
            api.fail(opens().single(), 30111, """{"auth_jump":${TradeFixtures.plan("h5" to "https://auth.example.test/pdd")}}""")
            runCurrent()
            c.authorize()
            runCurrent()
            c.onForeground()
            runCurrent()
            api.succeed(api.last(TradeOperations.LIST_UNION_BINDINGS), """{"items":[{"platform":"pdd","status":"unbound"}]}""")
            runCurrent()
            assertEquals(1, opens().size)
            assertEquals(PurchaseDialog.AuthDeclined("pdd", failed = false), c.state.value.dialog)
            assertNull(pending.peek())
        }

    @Test
    fun loginRequiredRegistersThePendingAndReplaysWithTheSameKeyAfterLogin() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            val first = opens().single()
            api.fail(first, 10001)
            runCurrent()
            assertEquals("Login", env.opened.single().first)
            assertEquals(first.call.idempotencyKey, pending.peek()!!["idempotency_key"]!!.jsonPrimitive.content)
            c.resumeAfterLogin()
            runCurrent()
            assertEquals(2, opens().size)
            assertEquals("1xxxx 之后沿用原键", first.call.idempotencyKey, opens().last().call.idempotencyKey)
            assertNull(pending.peek())
        }

    @Test
    fun platformStatusIsCheckedAgainRightBeforeTheIntent() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            env.status = "maintenance"
            c.buy(env.card!!, null)
            runCurrent()
            assertTrue("维护中不发 open", opens().isEmpty())
            assertEquals(
                OpenPurchaseController.MAINTENANCE_KEY,
                c.state.value.toast
                    ?.key,
            )
            env.status = "not_launched"
            c.buy(env.card!!, null)
            runCurrent()
            assertTrue(opens().isEmpty())
        }

    @Test
    fun rateLimitedDisablesBuyingUntilRetryAfterEnds() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.fail(opens().single(), 42901, disableMillis = 10_000L)
            runCurrent()
            assertTrue(c.state.value.rateLimited)
            c.buy(env.card!!, null)
            runCurrent()
            assertEquals("限流期间不发", 1, opens().size)
            advanceTimeBy(10_001L)
            runCurrent()
            assertFalse(c.state.value.rateLimited)
        }

    @Test
    fun notLaunchedTapResultDisablesWithoutToastAndMaintenanceToasts() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.fail(opens().single(), 50301, """{"reason":"not_launched"}""")
            runCurrent()
            assertEquals("按平台记下（卡片映射与按钮按它）", mapOf("taobao" to BuyBlock.NOT_LAUNCHED), env.marked)
            assertNull(c.state.value.toast)
        }

    @Test
    fun sdkOnlyPlanThatCannotStartShowsTheSdkNoticeAndAllFailuresShowInstallHint() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(opens().single(), TradeFixtures.openResult(plan = TradeFixtures.plan("sdk" to "https://s.click.example.test/t")))
            answerTips()
            assertEquals(PurchaseDialog.SdkFailed, c.state.value.dialog)
            c.dismissDialog()
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(opens().last(), TradeFixtures.openResult(plan = TradeFixtures.plan("scheme" to "demo-app://x")))
            runCurrent()
            assertFalse("安装状态 unknown、全部路径失败：不当未安装（A-R1-13）", c.state.value.notInstalled)
            assertEquals(
                OpenPurchaseController.JUMP_FAILED_KEY,
                c.state.value.toast
                    ?.key,
            )
        }

    @Test
    fun unreadJumpTipIsShownBeforeTheFirstJumpAndMarkedRead() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(opens().single(), TradeFixtures.openResult())
            answerTips(read = false)
            assertEquals(PurchaseDialog.JumpTip("taobao"), c.state.value.dialog)
            assertTrue("确认之前不外跳", ports.launched.isEmpty())
            c.confirm()
            runCurrent()
            assertEquals(1, ports.launched.size)
            val mark = api.last(TradeOperations.MARK_TIP_READ)
            assertEquals(mapOf("tip_key" to "jump_tip"), mark.call.pathParams)
        }
}
