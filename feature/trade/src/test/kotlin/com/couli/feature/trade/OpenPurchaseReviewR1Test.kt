package com.couli.feature.trade

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 1 轮评审（android-review-r1.md）R1-01..R1-13、R1-15 在购买执行器与卡片更新上的回归测试（本地假服务 [FakeTradeApi]、假时钟）。
 * 每条都按评审给的场景走一遍，断言修复后的行为；撤掉对应修复时这些断言失败（记录见 NATIVE/APP-06b/android-r2-revert-checks.txt）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenPurchaseReviewR1Test {
    private val api = FakeTradeApi()
    private val ports = FakePorts()
    private val baichuan = FakeBaichuan()
    private val web = FakeWebCode()
    private val env = FakeEnv()
    private val pending = MemoryPurchasePending()
    private val counter = AuthSheetCounter()
    private val events = mutableListOf<JsonObject>()
    private var keySeq = 0
    private var now = TradeFixtures.NOW

    private fun TestScope.controller(): OpenPurchaseController {
        val executor = TradeJumpExecutor(ports, baichuan, { events += it }, { testScheduler.currentTime }, { now })
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

    private fun opens() = api.calls.filter { it.call.operationId == TradeOperations.OPEN_LINK }

    private fun TestScope.answerTips(read: Boolean = true) {
        runCurrent()
        val tips = api.calls.lastOrNull { it.call.operationId == TradeOperations.GET_TIPS && !it.result.isCompleted } ?: return
        api.succeed(tips, if (read) """{"jump_tip":{"taobao":"2026-10-01T00:00:00+08:00","pdd":"x"}}""" else """{"jump_tip":{}}""")
        runCurrent()
    }

    private fun JsonObject.fenOf(key: String): Long? = (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()

    // ---------------------------------------------------------------- R1-01

    @Test
    fun r1_01_aLateRebateGoneResponseCannotEraseTheConfirmationOfTheRetry() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            advanceTimeBy(OpenPurchaseController.TIMEOUT_MILLIS)
            runCurrent()
            assertEquals(PurchasePhase.TIMEOUT, c.state.value.phase)
            // 超时后迟到的成功响应：价格不变、返利归零 → 只更新卡片
            val gone = TradeFixtures.openResult(attempt = "attempt-k", newMin = 0, newMax = 0)
            api.succeed(opens().single(), gone)
            runCurrent()
            assertEquals("迟到响应把卡片返利改成 0", 0L, env.card!!.fenOf("rebate_max_fen"))
            assertNull("迟到响应不弹确认", c.state.value.dialog)
            // 用户点超时条【重试】：同键重放拿到同一结果，仍须按原卡（返利 > 0）判断先问
            c.retryAfterTimeout()
            runCurrent()
            api.succeed(opens().last(), gone)
            answerTips()
            assertTrue("返利归零仍先确认（BR-PRICE-13）", c.state.value.dialog is PurchaseDialog.ConfirmRebateGone)
            assertTrue("确认之前不外跳", ports.launched.isEmpty())
            assertTrue(events.isEmpty())
        }

    // ---------------------------------------------------------------- R1-02

    @Test
    fun r1_02_theTimeoutRetryReplaysTheOriginalPathBodyAndKey() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card(linkId = "link-1")
            c.buy(env.card!!, "product_detail.buy_bar.1")
            runCurrent()
            advanceTimeBy(OpenPurchaseController.TIMEOUT_MILLIS)
            runCurrent()
            val first = opens().single()
            api.succeed(first, TradeFixtures.openResult(newLink = "link-2"))
            runCurrent()
            assertEquals("迟到响应的 new_link_id 只更新卡片", "link-2", env.card!!["link_id"]!!.jsonPrimitive.content)
            c.retryAfterTimeout()
            runCurrent()
            val second = opens().last()
            assertEquals("重放原路径 /link-1/open", mapOf("link_id" to "link-1"), second.call.pathParams)
            assertEquals("原键", first.call.idempotencyKey, second.call.idempotencyKey)
            assertEquals("原请求体", first.call.body, second.call.body)
        }

    // ---------------------------------------------------------------- R1-03

    @Test
    fun r1_03_goingToTheBackgroundWhileWaitingForTheJumpTipStatusStopsTheJump() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(opens().single(), TradeFixtures.openResult())
            runCurrent()
            // open 已成功、正在等 getTips：此时发起页被覆盖 / App 进入后台
            assertEquals(PurchasePhase.IDLE, c.state.value.phase)
            c.onBackground()
            answerTips(read = true)
            assertTrue("等待结束后复核许可：不外跳", ports.launched.isEmpty())
            assertTrue("不上报 link_jump", events.isEmpty())
            c.onForeground()
            runCurrent()
            assertTrue("回到前台也不自动外跳", ports.launched.isEmpty())
        }

    @Test
    fun r1_03_theExecutorStopsBeforeTheNextStepOnceThePermitIsGone() =
        runTest {
            var allowed = true
            val executor = TradeJumpExecutor(ports, baichuan, { events += it }, { 0L }, { now })
            ports.appLaunches = false
            val plan = Json.parseToJsonElement(TradeFixtures.plan("scheme" to "demo-app://a", "scheme" to "demo-app://b")).jsonObject
            // 第一步执行完（失败）后许可作废：第二步不执行、不上报
            val portsSpy =
                object : JumpPorts by ports {
                    override fun launchApp(
                        type: String,
                        value: String,
                    ): Boolean = ports.launchApp(type, value).also { allowed = false }
                }
            val outcome =
                TradeJumpExecutor(portsSpy, baichuan, { events += it }, { 0L }, { now })
                    .execute(plan, "taobao", "unknown", PurchaseIdentity("a1", "l1"), allowed = { allowed })
            assertTrue(outcome.stopped)
            assertEquals(listOf("scheme:demo-app://a"), ports.launched)
            assertTrue("作废之后到的上报也不发", events.isEmpty())
            assertFalse(executor.expired(plan))
        }

    // ---------------------------------------------------------------- R1-04

    @Test
    fun r1_04_basicModeAsksForConsentFirstThenResumesThePurchaseOnce() =
        runTest {
            val c = controller()
            env.basic = true
            env.card = TradeFixtures.card()
            c.buy(env.card!!, "search.result_list.1")
            runCurrent()
            assertTrue("基本模式不直接发 open", opens().isEmpty())
            assertEquals("弹补同意", 1, env.consentRequests)
            assertEquals(OpenPurchaseController.PENDING_CONSENT, pending.peek()!!["kind"]!!.jsonPrimitive.content)
            // 同意
            env.basic = false
            c.resumeAfterConsent()
            runCurrent()
            assertEquals("同意后恢复一次", 1, opens().size)
            assertNull("待办已删除", pending.peek())
            val body = Json.parseToJsonElement(opens().single().call.body!!).jsonObject
            assertEquals("search.result_list.1", body["spm"]!!.jsonPrimitive.content)
            c.resumeAfterConsent()
            runCurrent()
            assertEquals("只恢复一次", 1, opens().size)
        }

    @Test
    fun r1_04_declinedConsentDropsThePendingAndNothingIsSent() =
        runTest {
            val c = controller()
            env.basic = true
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            // 仍不同意：Router 删除待办（onConsentDeclined）
            pending.clear()
            c.resumeAfterConsent()
            runCurrent()
            assertTrue(opens().isEmpty())
        }

    // ---------------------------------------------------------------- R1-05

    @Test
    fun r1_05_offShelfIsBoundToThatProductAndPlatformStopsToThatPlatform() =
        runTest {
            val c = controller()
            val a = TradeFixtures.card(id = "a", linkId = "link-a")
            val b = TradeFixtures.card(id = "b", linkId = "link-b", platform = "pdd")
            val d = TradeFixtures.card(id = "d", linkId = "link-d")
            env.card = a
            env.others += listOf(b, d)
            c.buy(a, null)
            runCurrent()
            api.fail(opens().single(), 30141)
            runCurrent()
            assertEquals("下架写进 A 这张卡", "off_shelf", env.card!!["availability"]!!.jsonPrimitive.content)
            c.buy(env.others[0], null)
            runCurrent()
            assertEquals("同页的 B 照常购买", 2, opens().size)
            api.fail(opens().last(), 50301, """{"reason":"maintenance"}""")
            runCurrent()
            assertEquals(mapOf("pdd" to BuyBlock.MAINTENANCE), env.marked)
            c.consumeToast()
            c.buy(env.others[1], null)
            runCurrent()
            assertEquals("拼多多停用不挡淘宝的 D", 3, opens().size)
            api.fail(opens().last(), 10405)
            runCurrent()
            c.buy(env.card!!, null)
            runCurrent()
            assertEquals("A 仍是下架，不发", 3, opens().size)
            // 新配置覆盖：拼多多状态恢复后 B 可以再买
            env.marked.clear()
            c.buy(env.others[0], null)
            runCurrent()
            assertEquals(4, opens().size)
        }

    @Test
    fun r1_05_platformOverridesAreReplacedByTheNextAppliedConfig() {
        var issued = 3L
        var applied = 3L
        val overrides = PlatformStatusOverrides({ issued }, { applied })
        var notified = 0
        overrides.subscribe { notified += 1 }
        overrides.mark("taobao", BuyBlock.NOT_LAUNCHED)
        assertEquals(1, notified)
        assertEquals(BuyBlock.NOT_LAUNCHED, overrides.status("taobao"))
        assertNull("只按平台", overrides.status("pdd"))
        // 记下之前就在途的拉取生效：不覆盖
        applied = 3L
        assertEquals(BuyBlock.NOT_LAUNCHED, overrides.status("taobao"))
        // 记下之后发出的拉取生效：新配置为准
        issued = 4L
        applied = 4L
        assertNull(overrides.status("taobao"))
    }

    // ---------------------------------------------------------------- R1-07 / R1-08

    @Test
    fun r1_07_aNewCouponAmountIsNotDerivedFromTheOldOneAndTheCardIsRefetched() =
        runTest {
            val card =
                Json
                    .parseToJsonElement(
                        """{"card_id":"c1","product_key":"tb:1","platform":"taobao",""" +
                            """"price_fen":10000,"coupon_fen":1000,"final_price_fen":9000,""" +
                            """"est_net_price_fen":8800,"rebate_min_fen":200,"rebate_max_fen":200,""" +
                            """"rebate_basis":"normal","link_id":"l1"}""",
                    ).jsonObject
            val data =
                Json
                    .parseToJsonElement(
                        """{"price_changed":true,"old_final_price_fen":9000,"new_final_price_fen":8000,"new_rebate_min_fen":200,""" +
                            """"new_rebate_max_fen":200,"availability":"ok","new_link_id":"l2"}""",
                    ).jsonObject
            val patched = CardPatch.apply(card, data)
            assertEquals(8000L, patched.fenOf("final_price_fen"))
            assertEquals("券前价不反推", JsonNull, patched["price_fen"])
            assertEquals("券额不沿用旧值", JsonNull, patched["coupon_fen"])
            assertEquals(JsonNull, patched["est_net_price_fen"])
            assertTrue(CardPatch.needsRefetch(card, data))
            // 执行器：改价后按这张卡重取权威卡片
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(opens().single(), TradeFixtures.openResult(priceChanged = true, newFinal = 3999, newLink = "link-9"))
            runCurrent()
            assertEquals(
                "tb:c1",
                env.refetchedCards
                    .single()["product_key"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun r1_08_rebateComingBackClearsTheNoRebateStateReasonAndButton() {
        val card =
            Json
                .parseToJsonElement(
                    """{"card_id":"c1","product_key":"tb:1","platform":"taobao",""" +
                        """"price_fen":5678,"coupon_fen":1000,"final_price_fen":4678,""" +
                        """"rebate_min_fen":0,"rebate_max_fen":0,"rebate_basis":"no_rebate","no_rebate_cause":"price_compare",""" +
                        """"cta":{"text_key":"btn.buy.no_rebate"},"link_id":"l1"}""",
                ).jsonObject
        val data =
            Json
                .parseToJsonElement(
                    """{"price_changed":false,"old_final_price_fen":4678,"new_final_price_fen":4678,"new_rebate_min_fen":100,""" +
                        """"new_rebate_max_fen":200,"availability":"ok","no_rebate_cause":null}""",
                ).jsonObject
        val patched = CardPatch.apply(card, data)
        assertEquals(200L, patched.fenOf("rebate_max_fen"))
        assertEquals("不留「暂无返利」口径", JsonNull, patched["rebate_basis"])
        assertEquals("不留比价原因", JsonNull, patched["no_rebate_cause"])
        assertEquals(
            "不留「去购买（无返利）」",
            "btn.buy.coupon",
            (patched["cta"] as JsonObject)["text_key"]!!.jsonPrimitive.content,
        )
        assertTrue("信息不足，重取权威卡片", CardPatch.needsRefetch(card, data))
    }

    // ---------------------------------------------------------------- R1-09

    @Test
    fun r1_09_aStaleLinkRefetchesTheTappedCardItself() =
        runTest {
            val c = controller()
            val a = TradeFixtures.card(id = "a", linkId = "link-a")
            val b = TradeFixtures.card(id = "b", linkId = "link-b")
            env.card = a
            env.others += b
            c.buy(b, "search.result_list.2")
            runCurrent()
            api.fail(opens().single(), 30144)
            runCurrent()
            assertEquals(
                "按搜索卡 B 的 product_key 重取",
                "tb:b",
                env.refetchedCards
                    .single()["product_key"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(
                "v1.r",
                env.refetchedCards
                    .single()["item_ref"]!!
                    .jsonPrimitive.content,
            )
            assertTrue("不外跳", ports.launched.isEmpty())
        }

    // ---------------------------------------------------------------- R1-10

    @Test
    fun r1_10_aUserRetryAfterTheAutoPopLimitStillOpensTheAuthSheet() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            val unbound = """{"auth_url":"https://oauth.example.test/a","state":"st","auth_methods":["web_code"]}"""
            repeat(2) {
                c.buy(env.card!!, null)
                runCurrent()
                api.fail(opens().last(), 30101, unbound)
                runCurrent()
                assertTrue(c.state.value.dialog is PurchaseDialog.AuthSheet)
                c.dismissDialog()
                c.dismissDialog()
            }
            c.buy(env.card!!, null)
            runCurrent()
            api.fail(opens().last(), 30101, unbound)
            runCurrent()
            assertEquals("第 3 次不再自动弹出", PurchaseDialog.AuthDeclined("taobao", false), c.state.value.dialog)
            // 用户在二选一里主动点【重试】
            c.retryPurchase()
            runCurrent()
            api.fail(opens().last(), 30101, unbound)
            runCurrent()
            assertEquals("主动重试进入授权，不受自动弹出上限", PurchaseDialog.AuthSheet("taobao"), c.state.value.dialog)
            assertEquals("不占自动弹出次数", AuthSheetCounter.MAX_AUTO_POPS, counter.count("taobao"))
            c.authorize()
            runCurrent()
            assertEquals(listOf("https://oauth.example.test/a"), web.urls)
        }

    // ---------------------------------------------------------------- R1-12

    @Test
    fun r1_12_aPlanThatExpiresWhileTheUserIsConfirmingIsNotExecuted() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            val plan = TradeFixtures.plan("scheme" to "demo-app://x", expireAt = "2026-10-02T09:31:00+08:00")
            api.succeed(opens().single(), TradeFixtures.openResult(plan = plan, priceChanged = true, newFinal = 4990))
            runCurrent()
            assertTrue(c.state.value.dialog is PurchaseDialog.ConfirmPrice)
            // 用户停在确认框超过 expire_at
            now = TradeFixtures.NOW + 61_000L
            c.confirm()
            answerTips()
            assertTrue("过期方案不外跳", ports.launched.isEmpty())
            assertTrue("不上报", events.isEmpty())
            assertEquals(
                "提示重新购买",
                OpenPurchaseController.JUMP_EXPIRED_KEY,
                c.state.value.toast
                    ?.key,
            )
        }

    @Test
    fun r1_12_aPlanWithoutAReadableExpiryIsTreatedAsExpired() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(
                opens().single(),
                TradeFixtures.openResult(plan = TradeFixtures.plan("scheme" to "demo-app://x", expireAt = "soon")),
            )
            answerTips()
            assertTrue(ports.launched.isEmpty())
            assertEquals(
                OpenPurchaseController.JUMP_EXPIRED_KEY,
                c.state.value.toast
                    ?.key,
            )
        }

    // ---------------------------------------------------------------- R1-13

    @Test
    fun r1_13_onlyAnExplicitlyMissingAppShowsTheInstallButton() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            ports.installed = "unknown"
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(opens().single(), TradeFixtures.openResult(plan = TradeFixtures.plan("scheme" to "demo-app://x")))
            answerTips()
            assertFalse("unknown 的失败不当未安装", c.state.value.notInstalled)
            assertEquals(
                OpenPurchaseController.JUMP_FAILED_KEY,
                c.state.value.toast
                    ?.key,
            )
            assertTrue("按钮照常（没有覆盖）", purchaseButton(c.state.value, PlainTexts) == null)
            ports.installed = "false"
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(opens().last(), TradeFixtures.openResult(plan = TradeFixtures.plan("scheme" to "demo-app://x")))
            answerTips()
            assertTrue("明确未安装才提示安装", c.state.value.notInstalled)
        }

    // ---------------------------------------------------------------- R1-15

    @Test
    fun r1_15_similarItemsUseTheGivenProductEvenBeforeAnyPurchase() =
        runTest {
            val c = controller()
            c.similar(TradeFixtures.card(platform = "pdd"))
            val (route, params) = env.opened.single()
            assertEquals("Search", route)
            assertEquals("milk", params["q"]!!.jsonPrimitive.content)
            assertEquals("pdd", params["platform"]!!.jsonPrimitive.content)
        }

    private object PlainTexts : TradeTexts {
        override fun text(key: String): String = key

        override fun format(
            key: String,
            vars: Map<String, String>,
        ): String = key
    }
}
