package com.couli.feature.trade

import com.couli.core.designsystem.PriceTerms
import com.couli.core.designsystem.ProductCardMapper
import com.couli.core.designsystem.ProductCardTexts
import com.couli.core.network.ApiResult
import com.couli.generated.DefaultTexts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 3 轮评审（android-review-r3.md）R3-01 … R3-04 的回归测试（本地假服务 [FakeTradeApi]、假时钟）。卡片断言经过实际的卡片映射
 * （[ProductCardMapper.map]），授权管理错误态断言经过实际的契约文案（[DefaultTexts]）。撤掉对应修复时这些断言失败
 * （记录见 NATIVE/APP-06b/android-r3fix-revert-checks.txt）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenPurchaseReviewR3Test {
    private val api = FakeTradeApi()
    private val ports = FakePorts()
    private val baichuan = FakeBaichuan()
    private val web = FakeWebCode()
    private val env = FakeEnv()
    private val pending = MemoryPurchasePending()
    private val counter = AuthSheetCounter()
    private var keySeq = 0

    private fun TestScope.controller(): OpenPurchaseController {
        val executor = TradeJumpExecutor(ports, baichuan, { }, { testScheduler.currentTime }, { TradeFixtures.NOW })
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

    private fun queries() = api.calls.filter { it.call.operationId == TradeOperations.LIST_UNION_BINDINGS }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.fenOf(key: String): Long? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    private fun JsonObject.tags(): List<String> = (get("benefit_tags") as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()

    private fun JsonObject.cta(): String? = (get("cta") as? JsonObject)?.str("text_key")

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val texts = ProductCardTexts { key -> if (key == "platform.taobao") "淘宝" else "" }

    private fun mapped(card: JsonObject) = ProductCardMapper.map(card, texts, nowMillis = TradeFixtures.NOW)

    /** 权威重取到的卡（同一商品）。 */
    private fun quote(
        price: Long,
        coupon: Long,
        final: Long,
        min: Long = 123,
        max: Long = 234,
    ): JsonObject {
        val tags = if (coupon > 0) """["有券"]""" else "[]"
        val cta = if (coupon > 0) "btn.buy.coupon" else "btn.buy"
        return obj(
            """{"product_key":"tb:c1","item_ref":"v1.r","platform":"taobao","title":"milk","price_fen":$price,"coupon_fen":$coupon,""" +
                """"final_price_fen":$final,"est_net_price_fen":${final - min},"rebate_min_fen":$min,"rebate_max_fen":$max,""" +
                """"rebate_basis":"normal","no_rebate_cause":null,"link_id":"link-generic","cta":{"text_key":"$cta"},""" +
                """"benefit_tags":$tags,"disclaimer_keys":["price_basis","rebate_estimate"],"availability":"ok"}""",
        )
    }

    // ---------------------------------------------------------------- R3-01

    /** 拼多多购买 A：30111 → 授权跳转 → 回到前台，授权查询（listUnionBindings）在途。 */
    private fun TestScope.pddAuthQueryInFlight(c: OpenPurchaseController) {
        env.card = TradeFixtures.card(platform = "pdd")
        c.buy(env.card!!, null)
        runCurrent()
        api.fail(opens().single(), 30111, """{"auth_jump":${TradeFixtures.plan("h5" to "https://auth.example.test/pdd")}}""")
        runCurrent()
        c.authorize()
        runCurrent()
        c.onForeground()
        runCurrent()
        assertEquals("授权查询已发出", 1, queries().size)
        assertNull("查询在途时页面没有弹窗、没有加载层", c.state.value.dialog)
    }

    @Test
    fun r3_01_aLateBindingsQueryDoesNotCloseTheNewAttemptsRebateGoneConfirmation() =
        runTest {
            val c = controller()
            pddAuthQueryInFlight(c)
            val query = queries().single()
            val launchedBefore = ports.launched.toList()
            // 用户再次购买同一 link（购买 B）
            c.buy(env.card!!, null)
            runCurrent()
            assertEquals(2, opens().size)
            // 新点击先作废旧查询的归属再取消：取消到达传输层时归属已失效（不会被当成断连做 GET 重试）
            val owner = query.call.ownership!!
            assertTrue("旧查询被取消", owner in api.cancelled)
            assertFalse("取消时归属已失效", owner.stillOwned())
            assertNull("旧的授权待办已作废", pending.peek())
            // B：价格不变、返利归零 → 返利归零确认窗
            api.succeed(opens().last(), TradeFixtures.openResult(attempt = "attempt-b", newMin = 0, newMax = 0))
            runCurrent()
            val confirm = c.state.value.dialog
            assertTrue("B 显示返利归零确认", confirm is PurchaseDialog.ConfirmRebateGone)
            // A 的授权查询此刻才返回 active
            api.succeed(query, """{"items":[{"platform":"pdd","status":"active"}]}""")
            runCurrent()
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals("确认窗不被关", confirm, c.state.value.dialog)
            assertEquals("不自动 open", 2, opens().size)
            assertTrue("不取下单须知、不外跳", api.calls.none { it.call.operationId == TradeOperations.GET_TIPS })
            assertEquals("没有新的拉起", launchedBefore, ports.launched)
            // 用户取消确认窗：仍按 B 的确认处理
            c.dismissDialog()
            assertEquals(
                OpenPurchaseController.CANCELLED_REBATE_KEY,
                c.state.value.toast
                    ?.key,
            )
            assertEquals(2, opens().size)
        }

    @Test
    fun r3_01_aDeferredResumeAfterAuthIsDroppedWhenANewClickHappened() =
        runTest {
            val c = controller()
            pddAuthQueryInFlight(c)
            val query = queries().single()
            // 查询在途时页面被盖住；查询返回 active → 授权完成，恢复等回到前台
            c.onBackground()
            api.succeed(query, """{"items":[{"platform":"pdd","status":"active"}]}""")
            runCurrent()
            assertEquals("不在前台：先不 open", 1, opens().size)
            // 期间有了新的点击（购买 B，例如被盖住前一刻的点击经同步出口到达）；B 的响应在后台到达，只更新卡片
            c.buy(env.card!!, null)
            runCurrent()
            assertEquals(2, opens().size)
            api.succeed(opens().last(), TradeFixtures.openResult(attempt = "attempt-b", newMin = 0, newMax = 0))
            runCurrent()
            // 回到前台：旧授权的延迟恢复不再执行（不会以已归零的卡片发新键 open、绕过返利归零确认）
            c.onForeground()
            runCurrent()
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals("延迟恢复作废", 2, opens().size)
            assertTrue(api.calls.none { it.call.operationId == TradeOperations.GET_TIPS })
        }

    @Test
    fun r3_01_aCurrentQueryStillResumesTheAttemptOnce() =
        runTest {
            val c = controller()
            pddAuthQueryInFlight(c)
            api.succeed(queries().single(), """{"items":[{"platform":"pdd","status":"active"}]}""")
            runCurrent()
            assertEquals("仍属当前尝试：自动执行一次 open（新键）", 2, opens().size)
            assertTrue(opens()[0].call.idempotencyKey != opens()[1].call.idempotencyKey)
            assertNull(pending.peek())
            c.onForeground()
            runCurrent()
            assertEquals("只消费一次", 2, opens().size)
        }

    // ---------------------------------------------------------------- R3-02

    @Test
    fun r3_02_couponGoneWithANewCouponIsFilledFromTheRefetchNotInferredAsNoCoupon() =
        runTest {
            val c = controller()
            // 原卡：券前 5678、券 1000、券后 4678
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            // 原券失效、换成 500 分新券：券后 5178
            api.succeed(opens().single(), TradeFixtures.openResult(priceChanged = true, newFinal = 5178, availability = "coupon_gone"))
            runCurrent()
            val dialog = c.state.value.dialog as PurchaseDialog.ConfirmPrice
            assertTrue("券失效确认", dialog.coupon)
            val pendingCard = env.card!!
            assertNull("券额未知，不推断为 0", pendingCard.fenOf("coupon_fen"))
            assertNull("券前价未知，不推断为券后价", pendingCard.fenOf("price_fen"))
            assertEquals(5178L, pendingCard.fenOf("final_price_fen"))
            // 价格构成未知时不显示错误的口径（FU-AND-0607 按共性第 34 条改：已知新价照常显示，不带口径词、不给券前副行）
            assertEquals("价格构成未知时不带口径词", "", mapped(pendingCard).data.price?.mainLabel)
            assertNull("价格构成未知时不显示券前副行", mapped(pendingCard).data.price?.subLine)
            assertEquals(1, env.refetched)
            // 权威重取：券前 5678、券 500、券后 5178
            env.answerRefetch(quote(price = 5678, coupon = 500, final = 5178))
            val card = env.card!!
            assertEquals(5678L, card.fenOf("price_fen"))
            assertEquals(500L, card.fenOf("coupon_fen"))
            assertEquals(5178L, card.fenOf("final_price_fen"))
            assertTrue("同步「有券」", PriceTerms.HAS_COUPON in card.tags())
            assertEquals("同步按钮为领券购买", "btn.buy.coupon", mapped(card).ctaKey)
            assertNotNull("价格补齐后显示", mapped(card).data.price)
        }

    @Test
    fun r3_02_couponGoneThatReallyHasNoCouponEndsAsBuy() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(opens().single(), TradeFixtures.openResult(priceChanged = true, newFinal = 5678, availability = "coupon_gone"))
            runCurrent()
            env.answerRefetch(quote(price = 5678, coupon = 0, final = 5678))
            val card = env.card!!
            assertEquals(0L, card.fenOf("coupon_fen"))
            assertFalse(PriceTerms.HAS_COUPON in card.tags())
            assertEquals("btn.buy", mapped(card).ctaKey)
        }

    // ---------------------------------------------------------------- R3-03

    @Test
    fun r3_03_priceChangeWithRebateComingBackSyncsTheButtonOnceTheCouponIsKnown() =
        runTest {
            val c = controller()
            // 原卡无返利、有 1000 分券、券后 4678
            env.card =
                obj(
                    """{"card_id":"c1","product_key":"tb:c1","item_ref":"v1.r","platform":"taobao","title":"milk","price_fen":5678,""" +
                        """"coupon_fen":1000,"final_price_fen":4678,"est_net_price_fen":null,"rebate_min_fen":0,"rebate_max_fen":0,""" +
                        """"rebate_basis":"no_rebate","no_rebate_cause":null,"link_id":"link-1","cta":{"text_key":"btn.buy.no_rebate"},""" +
                        """"benefit_tags":["有券"],"disclaimer_keys":["price_basis"],"availability":"ok"}""",
                )
            c.buy(env.card!!, null)
            runCurrent()
            // 新券后价 4990，同时返利恢复为正
            api.succeed(opens().single(), TradeFixtures.openResult(priceChanged = true, newFinal = 4990, newMin = 100, newMax = 200))
            runCurrent()
            val provisional = env.card!!
            assertNull("券额未知", provisional.fenOf("coupon_fen"))
            assertTrue("不残留「去购买（无返利）」", provisional.cta() != "btn.buy.no_rebate")
            // 权威重取补回券额 1000：按钮同步为领券购买
            env.answerRefetch(quote(price = 5990, coupon = 1000, final = 4990, min = 100, max = 200))
            val card = env.card!!
            assertEquals(1000L, card.fenOf("coupon_fen"))
            assertEquals("有券 → 领券购买（03 §7.4）", "btn.buy.coupon", mapped(card).ctaKey)
            assertTrue(PriceTerms.HAS_COUPON in card.tags())
        }

    @Test
    fun r3_03_theOpenConfirmedNoRebateButtonAndCompareReasonSurviveTheCouponSync() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card(platform = "pdd")
            c.buy(env.card!!, null)
            runCurrent()
            // 券失效 + 返利归零 + 比价无返利
            api.succeed(
                opens().single(),
                TradeFixtures.openResult(
                    priceChanged = true,
                    newFinal = 4178,
                    newMin = 0,
                    newMax = 0,
                    cause = "price_compare",
                    availability = "coupon_gone",
                ),
            )
            runCurrent()
            env.answerRefetch(quote(price = 4678, coupon = 500, final = 4178))
            val card = env.card!!
            assertEquals(500L, card.fenOf("coupon_fen"))
            assertEquals("open 确认的无返利按钮保留（R2-02）", "btn.buy.no_rebate", card.cta())
            assertEquals("price_compare", card.str("no_rebate_cause"))
            assertEquals("no_rebate", card.str("rebate_basis"))
            assertTrue("有券标签按实际券额同步", PriceTerms.HAS_COUPON in card.tags())
        }

    // ---------------------------------------------------------------- R3-04

    private object ContractTexts : TradeTexts {
        override fun text(key: String): String = DefaultTexts.text(key) ?: key

        override fun format(
            key: String,
            vars: Map<String, String>,
        ): String = vars.entries.fold(text(key)) { acc, (name, value) -> acc.replace("{$name}", value) }
    }

    private fun TestScope.manage(): AuthManageController {
        val executor = TradeJumpExecutor(ports, baichuan, { }, { 0L }, { TradeFixtures.NOW })
        return AuthManageController(api, executor, ports, web, baichuan, backgroundScope, { "k" }, { testScheduler.currentTime })
    }

    @Test
    fun r3_04_aRateLimitedListShowsTheRateLimitTextAndHonoursRetryAfter() =
        runTest {
            val c = manage()
            c.load()
            runCurrent()
            api.fail(queries().single(), 42901, disableMillis = 30_000L)
            runCurrent()
            val state = c.state.value
            assertEquals(AuthManageState.PHASE_ERROR, state.phase)
            assertEquals("error.42901", state.errorKey)
            assertNull("限流不带 trace6", state.errorTraceId)
            assertEquals(DefaultTexts.text("error.42901"), AuthManageController.errorMessage(state, ContractTexts))
            assertEquals("操作太频繁，请稍后再试", AuthManageController.errorMessage(state, ContractTexts))
            assertTrue("Retry-After 期间【重试】置灰", state.rateLimited)
            c.load()
            runCurrent()
            assertEquals("执行层拦下", 1, queries().size)
            advanceTimeBy(30_001L)
            runCurrent()
            assertFalse(c.state.value.rateLimited)
            c.load()
            runCurrent()
            assertEquals(2, queries().size)
        }

    @Test
    fun r3_04_aServerErrorShowsItsTextWithTrace6() =
        runTest {
            val c = manage()
            c.load()
            runCurrent()
            api.fail(queries().single(), 50001)
            runCurrent()
            val state = c.state.value
            assertEquals("error.50001", state.errorKey)
            assertEquals(FakeTradeApi.TRACE, state.errorTraceId)
            assertEquals("出了点问题，请稍后再试（000321）", AuthManageController.errorMessage(state, ContractTexts))
        }

    @Test
    fun r3_04_aTransportFailureShowsTheNetworkText() =
        runTest {
            val c = manage()
            c.load()
            runCurrent()
            queries().single().result.complete(ApiResult.TransportFailure("io", null, null, false))
            runCurrent()
            val state = c.state.value
            assertEquals(AuthManageController.NETWORK_KEY, state.errorKey)
            assertNull(state.errorTraceId)
            assertEquals(DefaultTexts.text("buy.timeout"), AuthManageController.errorMessage(state, ContractTexts))
            assertFalse("不冒充服务端错误", AuthManageController.errorMessage(state, ContractTexts).contains("出了点问题"))
        }
}
