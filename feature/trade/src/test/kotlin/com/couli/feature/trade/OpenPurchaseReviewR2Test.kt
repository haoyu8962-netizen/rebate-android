package com.couli.feature.trade

import com.couli.core.designsystem.ProductCardMapper
import com.couli.core.designsystem.ProductCardTexts
import com.couli.core.designsystem.ProductDetailState
import com.couli.core.designsystem.RebateKind
import com.couli.core.tradejump.BaichuanAuthResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 2 轮评审（android-review-r2.md）R2-01、R2-02、R2-05、R2-06 在购买执行器上的回归测试（本地假服务 [FakeTradeApi]、假时钟）。
 * 卡片断言都经过实际的卡片映射（[ProductCardMapper.map]、[ProductDetailState.map]），不只看 JSON 字段；撤掉对应修复时这些断言失败
 * （记录见 NATIVE/APP-06b/android-r2-revert-checks.txt「第 3 轮」）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenPurchaseReviewR2Test {
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

    private fun binds() = api.calls.filter { it.call.operationId == TradeOperations.BIND_UNION }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.fenOf(key: String): Long? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val texts = ProductCardTexts { key -> if (key == "platform.taobao") "淘宝" else "" }

    private fun mapped(card: JsonObject) = ProductCardMapper.map(card, texts, nowMillis = TradeFixtures.NOW)

    /** 暂无返利的卡（价格齐全）。 */
    private fun noRebateCard(platform: String = "taobao"): JsonObject =
        obj(
            """{"card_id":"c1","product_key":"tb:c1","item_ref":"v1.r","platform":"$platform","title":"milk","price_fen":5678,""" +
                """"coupon_fen":1000,"final_price_fen":4678,"est_net_price_fen":null,"rebate_min_fen":0,"rebate_max_fen":0,""" +
                """"rebate_basis":"no_rebate","no_rebate_cause":null,"link_id":"link-1","cta":{"text_key":"btn.buy.no_rebate"},""" +
                """"benefit_tags":["有券"],"disclaimer_keys":["price_basis"],"availability":"ok"}""",
        )

    /** 详情 / 搜索接口的通用报价（不带本次 open 的比价预判，返利为正）。 */
    private fun genericQuote(
        final: Long,
        link: String = "link-generic",
        min: Long = 123,
        max: Long = 234,
    ): JsonObject =
        obj(
            """{"product_key":"tb:c1","item_ref":"v1.r","platform":"taobao","title":"milk","price_fen":${final + 1000},""" +
                """"coupon_fen":1000,"final_price_fen":$final,"est_net_price_fen":${final - min},"rebate_min_fen":$min,""" +
                """"rebate_max_fen":$max,"rebate_basis":"normal","no_rebate_cause":null,"link_id":"$link",""" +
                """"cta":{"text_key":"btn.buy.coupon"},"benefit_tags":["有券"],"disclaimer_keys":["price_basis","rebate_estimate"],""" +
                """"availability":"ok"}""",
        )

    // ---------------------------------------------------------------- R2-01

    @Test
    fun r2_01_rebateComingBackShowsTheServerRangeThroughTheRealMappingEvenIfTheRefetchFails() =
        runTest {
            val c = controller()
            env.card = noRebateCard()
            c.buy(env.card!!, null)
            runCurrent()
            // 价格不变、返利由 0 变为 1.00–2.00（口径响应不带）
            api.succeed(opens().single(), TradeFixtures.openResult(newMin = 100, newMax = 200))
            runCurrent()
            val card = env.card!!
            val view = mapped(card)
            assertEquals("按服务端区间显示，不落入「暂无返利」", RebateKind.RANGE, view.data.rebate.kind)
            assertTrue(
                view.data.rebate.text,
                view.data.rebate.text
                    .contains("¥1") &&
                    view.data.rebate.text
                        .contains("¥2"),
            )
            assertTrue("列表不隐藏（返利强调色）", view.data.rebate.usesRebateAccent)
            assertEquals("按钮不是「去购买（无返利）」", "btn.buy.coupon", view.ctaKey)
            assertFalse("详情不当金额未知", ProductDetailState.map(card, obj("""{"loggedIn":true}""")).rebateUnknown)
            // 重取发出了（补口径），但失败 / 没有回答：卡片保持临时展示状态
            assertEquals(1, env.refetched)
            assertEquals(RebateKind.RANGE, mapped(env.card!!).data.rebate.kind)
            // 重取到的卡区间与 open 不一致（不是同一份报价）：不采用它的口径，仍按 open 的区间临时展示
            env.answerRefetch(genericQuote(final = 4678, min = 300, max = 400))
            val stillProvisional = mapped(env.card!!)
            assertEquals(RebateKind.RANGE, stillProvisional.data.rebate.kind)
            assertTrue(
                stillProvisional.data.rebate.text
                    .contains("¥2"),
            )
        }

    @Test
    fun r2_01_aMatchingRefetchFillsTheBasisAndLeavesTheProvisionalState() =
        runTest {
            val c = controller()
            env.card = noRebateCard()
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(opens().single(), TradeFixtures.openResult(newMin = 123, newMax = 234))
            runCurrent()
            env.answerRefetch(genericQuote(final = 4678))
            val card = env.card!!
            assertEquals("normal", card.str("rebate_basis"))
            assertNull("去掉临时标记", card[ProductCardMapper.PROVISIONAL_REBATE])
            assertEquals("补上预估返后（价格、区间、口径都一致）", 4555L, card.fenOf("est_net_price_fen"))
            assertEquals(RebateKind.RANGE, mapped(card).data.rebate.kind)
            assertEquals("link_id 仍是本次 open 确认的", "link-1", card.str("link_id"))
        }

    // ---------------------------------------------------------------- R2-02

    @Test
    fun r2_02_aGenericDetailQuoteThatStillHasRebateDoesNotOverrideTheOpenPriceComparison() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card(platform = "pdd")
            c.buy(env.card!!, null)
            runCurrent()
            // open：改价 + 返利归零 + 比价无返利 + 换链接
            api.succeed(
                opens().single(),
                TradeFixtures.openResult(
                    priceChanged = true,
                    newFinal = 3999,
                    newLink = "link-9",
                    newMin = 0,
                    newMax = 0,
                    cause = "price_compare",
                ),
            )
            runCurrent()
            val confirm = c.state.value.dialog as PurchaseDialog.ConfirmPrice
            assertTrue("改价 + 返利归零先确认", confirm.rebateGone)
            assertEquals("改价后发了补齐价格的重取", 1, env.refetched)
            // 详情接口的通用报价（同价、仍有返利、别的 link_id）到达
            env.answerRefetch(genericQuote(final = 3999))
            val card = env.card!!
            assertEquals("保留 open 确认的 link_id", "link-9", card.str("link_id"))
            assertEquals("no_rebate", card.str("rebate_basis"))
            assertEquals("price_compare", card.str("no_rebate_cause"))
            assertEquals("btn.buy.no_rebate", (card["cta"] as JsonObject).str("text_key"))
            assertEquals(0L, card.fenOf("rebate_max_fen"))
            assertEquals("券前价补齐", 4999L, card.fenOf("price_fen"))
            assertEquals("券额补齐", 1000L, card.fenOf("coupon_fen"))
            val view = mapped(card)
            assertEquals("经卡片映射仍是暂无返利", RebateKind.NONE, view.data.rebate.kind)
            assertNotNull("价格补齐后可显示", view.data.price)
            // 关闭确认框之后卡片仍是比价无返利
            c.dismissDialog()
            assertEquals("price_compare", env.card!!.str("no_rebate_cause"))
            assertEquals("link-9", env.card!!.str("link_id"))
        }

    @Test
    fun r2_02_aStaleReloadDoesNotOverwriteANewerPurchaseResult() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.fail(opens().single(), 30144)
            runCurrent()
            assertEquals("30144 发了重新加载", 1, env.refetched)
            // 重新加载还没回来，用户又点了一次：新的 open 结果写进卡片（换链接、返利变化）
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(opens().last(), TradeFixtures.openResult(newLink = "link-new", newMin = 100, newMax = 300))
            runCurrent()
            val newer = env.card!!
            assertEquals("link-new", newer.str("link_id"))
            // 旧的重新加载结果这时才到：卡片版本已变，不覆盖
            env.answerRefetch(genericQuote(final = 4678, link = "link-reloaded"), index = 0)
            assertEquals("link-new", env.card!!.str("link_id"))
            assertEquals(300L, env.card!!.fenOf("rebate_max_fen"))
        }

    @Test
    fun r2_02_aReloadWithoutNewerResultsReplacesTheCard() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.fail(opens().single(), 30144)
            runCurrent()
            env.answerRefetch(genericQuote(final = 4600, link = "link-reloaded"))
            assertEquals("link-reloaded", env.card!!.str("link_id"))
            assertEquals("card_id 沿用原卡", "c1", env.card!!.str("card_id"))
            assertEquals(4600L, env.card!!.fenOf("final_price_fen"))
        }

    // ---------------------------------------------------------------- R2-05

    private fun TestScope.taobaoAuthSheet(
        c: OpenPurchaseController,
        methods: String,
    ) {
        env.card = TradeFixtures.card()
        c.buy(env.card!!, null)
        runCurrent()
        api.fail(opens().single(), 30101, """{"auth_url":"https://oauth.example.test/a","state":"st_01","auth_methods":$methods}""")
        runCurrent()
        assertEquals(PurchaseDialog.AuthSheet("taobao"), c.state.value.dialog)
    }

    @Test
    fun r2_05_credentialInvalidRefreshesTheContextAndSwitchesToTheNextMethodWithANewKey() =
        runTest {
            baichuan.sdkToken = true
            baichuan.auth = BaichuanAuthResult.Token("fixture-sdk-token", 3600)
            val c = controller()
            taobaoAuthSheet(c, """["sdk_token","web_code"]""")
            c.authorize()
            runCurrent()
            assertEquals("先用 sdk_token", 1, baichuan.authorizations)
            val first = binds().single()
            assertEquals("sdk_token", obj(first.call.body!!).str("auth_method"))
            api.fail(first, 30104, """{"reason":"credential_invalid"}""")
            runCurrent()
            // 重新取授权上下文
            val refresh = api.last(TradeOperations.GET_UNION_AUTH_URL)
            assertEquals(mapOf("platform" to "taobao"), refresh.call.pathParams)
            api.succeed(refresh, """{"auth_url":"https://oauth.example.test/b","state":"st_02","auth_methods":["sdk_token","web_code"]}""")
            runCurrent()
            assertEquals("改用新列表里下一种方式（web_code，新的授权地址）", listOf("https://oauth.example.test/b"), web.urls)
            assertEquals("没有再走失效的 sdk_token", 1, baichuan.authorizations)
            val second = binds().last()
            val body = obj(second.call.body!!)
            assertEquals("web_code", body.str("auth_method"))
            assertEquals("新的 state", "st_02", body.str("state"))
            assertNotEquals("新的幂等键", first.call.idempotencyKey, second.call.idempotencyKey)
            api.succeed(second, """{"platform":"taobao","status":"active"}""")
            runCurrent()
            assertEquals("授权完成后自动执行一次 open", 2, opens().size)
            assertNull(c.state.value.dialog)
        }

    @Test
    fun r2_05_credentialInvalidWithoutAnotherMethodEndsAsAuthFailed() =
        runTest {
            val c = controller()
            taobaoAuthSheet(c, """["web_code"]""")
            c.authorize()
            runCurrent()
            api.fail(binds().single(), 30104, """{"reason":"credential_invalid"}""")
            runCurrent()
            api.succeed(
                api.last(TradeOperations.GET_UNION_AUTH_URL),
                """{"auth_url":"https://oauth.example.test/b","state":"st_02","auth_methods":["web_code"]}""",
            )
            runCurrent()
            assertEquals("没有可换的方式：不再绑定", 1, binds().size)
            assertEquals(PurchaseDialog.AuthDeclined("taobao", failed = true), c.state.value.dialog)
        }

    @Test
    fun r2_05_methodNotAllowedOnlyFollowsTheNewList() =
        runTest {
            val c = controller()
            taobaoAuthSheet(c, """["web_code"]""")
            c.authorize()
            runCurrent()
            val first = binds().single()
            api.fail(first, 30104, """{"reason":"method_not_allowed"}""")
            runCurrent()
            api.succeed(
                api.last(TradeOperations.GET_UNION_AUTH_URL),
                """{"auth_url":"https://oauth.example.test/c","state":"st_03","auth_methods":["web_code"]}""",
            )
            runCurrent()
            val second = binds().last()
            assertEquals(2, binds().size)
            assertEquals("st_03", obj(second.call.body!!).str("state"))
            assertNotEquals(first.call.idempotencyKey, second.call.idempotencyKey)
            api.succeed(second, """{"platform":"taobao","status":"active"}""")
            runCurrent()
            assertEquals(2, opens().size)
        }

    @Test
    fun r2_05_methodNotAllowedWithANewListThisDeviceCannotRunIsAuthFailed() =
        runTest {
            val c = controller()
            taobaoAuthSheet(c, """["web_code"]""")
            c.authorize()
            runCurrent()
            api.fail(binds().single(), 30104, """{"reason":"method_not_allowed"}""")
            runCurrent()
            api.succeed(api.last(TradeOperations.GET_UNION_AUTH_URL), """{"state":"st_04","auth_methods":["sdk_token"]}""")
            runCurrent()
            assertEquals(1, binds().size)
            assertEquals(PurchaseDialog.AuthDeclined("taobao", failed = true), c.state.value.dialog)
        }

    // ---------------------------------------------------------------- R2-06

    private fun TestScope.pddReturned(c: OpenPurchaseController) {
        env.card = TradeFixtures.card(platform = "pdd")
        c.buy(env.card!!, null)
        runCurrent()
        api.fail(opens().single(), 30111, """{"auth_jump":${TradeFixtures.plan("h5" to "https://auth.example.test/pdd")}}""")
        runCurrent()
        c.authorize()
        runCurrent()
        c.onForeground()
        runCurrent()
    }

    @Test
    fun r2_06_aRateLimitedBindingsQueryIsNotTreatedAsDeclinedAndHonoursRetryAfter() =
        runTest {
            val c = controller()
            pddReturned(c)
            api.fail(api.last(TradeOperations.LIST_UNION_BINDINGS), 42901, disableMillis = 30_000L)
            runCurrent()
            val dialog = c.state.value.dialog
            assertEquals(PurchaseDialog.AuthCheckFailed("pdd", "error.42901", null, afterBind = false), dialog)
            assertNotNull("待办保留（不当成用户取消授权）", pending.peek())
            assertTrue("按钮层禁用", c.state.value.rateLimited)
            // 执行层：Retry-After 期间【重试】不发请求
            c.recheckAuth()
            runCurrent()
            assertEquals(1, api.calls.count { it.call.operationId == TradeOperations.LIST_UNION_BINDINGS })
            advanceTimeBy(30_001L)
            runCurrent()
            assertFalse(c.state.value.rateLimited)
            c.recheckAuth()
            runCurrent()
            api.succeed(api.last(TradeOperations.LIST_UNION_BINDINGS), """{"items":[{"platform":"pdd","status":"active"}]}""")
            runCurrent()
            assertEquals("查到已授权：自动执行一次 open", 2, opens().size)
        }

    @Test
    fun r2_06_aServerErrorOnTheBindingsQueryShowsTheErrorWithTrace6() =
        runTest {
            val c = controller()
            pddReturned(c)
            api.fail(api.last(TradeOperations.LIST_UNION_BINDINGS), 50001)
            runCurrent()
            assertEquals(
                PurchaseDialog.AuthCheckFailed("pdd", "error.50001", FakeTradeApi.TRACE, afterBind = false),
                c.state.value.dialog,
            )
            assertNotNull(pending.peek())
            // 查询成功但确实没授权：才给二选一
            c.recheckAuth()
            runCurrent()
            api.succeed(api.last(TradeOperations.LIST_UNION_BINDINGS), """{"items":[{"platform":"pdd","status":"unbound"}]}""")
            runCurrent()
            assertEquals(PurchaseDialog.AuthDeclined("pdd", failed = false), c.state.value.dialog)
            assertNull(pending.peek())
        }

    @Test
    fun r2_06_taobaoBindRateLimitAndServerErrorAreNotSwallowed() =
        runTest {
            val c = controller()
            taobaoAuthSheet(c, """["web_code"]""")
            c.authorize()
            runCurrent()
            api.fail(binds().single(), 42901, disableMillis = 10_000L)
            runCurrent()
            assertEquals(PurchaseDialog.AuthCheckFailed("taobao", "error.42901", null, afterBind = true), c.state.value.dialog)
            assertTrue(c.state.value.rateLimited)
            assertNotNull(pending.peek())
            c.recheckAuth()
            runCurrent()
            assertTrue("限流期间不查", api.calls.none { it.call.operationId == TradeOperations.LIST_UNION_BINDINGS })
            advanceTimeBy(10_001L)
            runCurrent()
            c.recheckAuth()
            runCurrent()
            api.fail(api.last(TradeOperations.LIST_UNION_BINDINGS), 50002)
            runCurrent()
            assertEquals(
                PurchaseDialog.AuthCheckFailed("taobao", OpenPurchaseController.GENERIC_KEY, FakeTradeApi.TRACE, afterBind = true),
                c.state.value.dialog,
            )
            c.recheckAuth()
            runCurrent()
            api.succeed(api.last(TradeOperations.LIST_UNION_BINDINGS), """{"items":[{"platform":"taobao","status":"unbound"}]}""")
            runCurrent()
            assertEquals("绑定之后查到未授权：授权失败", PurchaseDialog.AuthDeclined("taobao", failed = true), c.state.value.dialog)
        }

    @Test
    fun r2_06_taobaoBindServerErrorKeepsTrace6() =
        runTest {
            val c = controller()
            taobaoAuthSheet(c, """["web_code"]""")
            c.authorize()
            runCurrent()
            api.fail(binds().single(), 50001)
            runCurrent()
            val dialog = c.state.value.dialog as PurchaseDialog.AuthCheckFailed
            assertEquals(FakeTradeApi.TRACE, dialog.traceId)
            assertEquals("error.50001", dialog.textKey)
            c.dismissDialog()
            assertNull("取消：放弃待办", pending.peek())
            assertNull(c.state.value.dialog)
            assertEquals(1, opens().size)
            assertTrue(env.card!!.str("link_id") == "link-1")
            assertTrue(ports.launched.isEmpty())
            assertEquals(JsonPrimitive("taobao"), env.card!!["platform"]!!.jsonPrimitive)
        }
}
