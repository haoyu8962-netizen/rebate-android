package com.couli.feature.trade

import com.couli.core.designsystem.BuyButtonState
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.ProductCardMapper
import com.couli.core.designsystem.ProductCardTexts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
 * FU-AND-0607：APP-06b 三端共性第 24–45 条在 Android 的回查（F-AND-20）与 F-AND-19 的回归测试。本地假服务 [FakeTradeApi]、假时钟；
 * 卡片断言经过实际的卡片映射（[ProductCardMapper.map]）；外跳经真实的 [TradeJumpExecutor]。撤掉对应修复时这些断言失败
 * （记录见 NATIVE/FU-AND-0607/revert-checks.txt）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass", "TooManyFunctions") // 共性逐条一个用例
class FollowUp0607Test {
    private val api = FakeTradeApi()
    private val ports = HookPorts()
    private val baichuan = FakeBaichuan()
    private val web = FakeWebCode()
    private val env = FakeEnv()
    private val pending = MemoryPurchasePending()
    private val counter = AuthSheetCounter()
    private val events = mutableListOf<JsonObject>()
    private var keySeq = 0

    /** 外跳端口替身：拉起时可以先执行一段「系统那边发生的事」（进入后台、配置变化）。 */
    private class HookPorts : JumpPorts {
        var installed = "unknown"
        var appLaunches = false
        var browserOpens = true
        val launched = mutableListOf<String>()
        var onLaunch: () -> Unit = {}

        override fun installed(platform: String): String = installed

        override fun launchApp(
            type: String,
            value: String,
        ): Boolean {
            launched += "$type:$value"
            onLaunch()
            return appLaunches
        }

        override fun openBrowser(url: String): Boolean {
            launched += "browser:$url"
            onLaunch()
            return browserOpens
        }

        override fun copy(text: String): Boolean {
            launched += "copy:$text"
            return true
        }
    }

    private fun TestScope.controller(
        env: PurchaseEnv = this@FollowUp0607Test.env,
        pending: PurchasePending = this@FollowUp0607Test.pending,
    ): OpenPurchaseController {
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

    private fun opens() = api.calls.filter { it.call.operationId == TradeOperations.OPEN_LINK }

    private fun queries() = api.calls.filter { it.call.operationId == TradeOperations.LIST_UNION_BINDINGS }

    private fun tipsCalls() = api.calls.filter { it.call.operationId == TradeOperations.GET_TIPS }

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.fenOf(key: String): Long? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    private fun JsonObject.cta(): String? = (get("cta") as? JsonObject)?.str("text_key")

    private val texts = ProductCardTexts { key -> if (key == "platform.taobao") "淘宝" else key }

    private fun mapped(card: JsonObject) = ProductCardMapper.map(card, texts, nowMillis = TradeFixtures.NOW)

    private fun TestScope.answerTips(read: Boolean = true) {
        runCurrent()
        val tips = api.calls.lastOrNull { it.call.operationId == TradeOperations.GET_TIPS && !it.result.isCompleted } ?: return
        val readTips = """{"jump_tip":{"taobao":"2026-10-01T00:00:00+08:00","pdd":"2026-10-01T00:00:00+08:00"},"inviter_before_buy":null}"""
        api.succeed(tips, if (read) readTips else """{"jump_tip":{},"inviter_before_buy":null}""")
        runCurrent()
    }

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

    /** 游客卡：返利口径 login_required、上下限未知。 */
    private fun guestCard(): JsonObject =
        obj(
            TradeFixtures
                .card()
                .toString()
                .replace("\"rebate_min_fen\":123", "\"rebate_min_fen\":null")
                .replace("\"rebate_max_fen\":234", "\"rebate_max_fen\":null")
                .replace("\"rebate_basis\":\"normal\"", "\"rebate_basis\":\"login_required\""),
        )

    // ---------------------------------------------------------------- 24

    /** 第 24 条：补同意期间页面上的卡换成了新 link、新价：恢复仍按原点击的 link_id 与报价基准发 open，改价确认照常出现。 */
    @Test
    fun c24_consentResumeKeepsTheOriginalQuoteAndLinkAsTheBaseline() =
        runTest {
            env.basic = true
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            assertTrue("没发 open", opens().isEmpty())
            // 同意期间详情刷新：页面上的卡已是新 link、新价
            env.card =
                obj(
                    env.card
                        .toString()
                        .replace("link-1", "link-2")
                        .replace("\"final_price_fen\":4678", "\"final_price_fen\":5990"),
                )
            env.basic = false
            c.resumeAfterConsent()
            runCurrent()
            assertEquals("按原点击的 link_id 发", mapOf("link_id" to "link-1"), opens().single().call.pathParams)
            assertEquals("判断基准是原点击的卡", 4678L, c.currentAttempt!!.card.fenOf("final_price_fen"))
            api.succeed(opens().single(), TradeFixtures.openResult(priceChanged = true, newFinal = 5990, newLink = "link-3"))
            runCurrent()
            assertTrue("改价先确认", c.state.value.dialog is PurchaseDialog.ConfirmPrice)
            assertTrue("没外跳", ports.launched.isEmpty())
        }

    // ---------------------------------------------------------------- 25 / 38

    private fun TestScope.pddAuthSheet(
        c: OpenPurchaseController,
        plan: String,
    ) {
        env.card = TradeFixtures.card(platform = "pdd")
        c.buy(env.card!!, null)
        runCurrent()
        api.fail(opens().single(), 30111, """{"auth_jump":$plan}""")
        runCurrent()
        assertTrue(c.state.value.dialog is PurchaseDialog.AuthSheet)
    }

    /** 第 25 条：授权跳转 scheme 失败前进入后台（系统弹出应用市场等）：不再执行下一步 h5；回到前台向服务端确认。 */
    @Test
    fun c25_authJumpStopsBeforeTheNextStepOnceTheAppGoesToTheBackground() =
        runTest {
            val c = controller()
            pddAuthSheet(c, TradeFixtures.plan("scheme" to "pdd-app://auth", "h5" to "https://auth.example.test/pdd"))
            ports.onLaunch = { c.onBackground() }
            c.authorize()
            runCurrent()
            assertEquals("只执行了 scheme，后台之后不打开浏览器", listOf("scheme:pdd-app://auth"), ports.launched)
            assertTrue("还没查询", queries().isEmpty())
            ports.onLaunch = {}
            c.onForeground()
            runCurrent()
            assertEquals("回到前台由服务端确认", 1, queries().size)
        }

    /** 第 25 条：不在前台时点【去授权】不拉起授权。 */
    @Test
    fun c25_authorizeDoesNothingInTheBackground() =
        runTest {
            val c = controller()
            pddAuthSheet(c, TradeFixtures.plan("h5" to "https://auth.example.test/pdd"))
            c.onBackground()
            c.authorize()
            runCurrent()
            assertTrue(ports.launched.isEmpty())
        }

    /** 第 38 条：授权跳转的拉起结果晚于回前台通知：结果到达时（已离开过 App、此刻在前台）立即查询授权状态。 */
    @Test
    fun c38_aLaunchResultAfterTheForegroundNoticeStillQueriesTheBinding() =
        runTest {
            val c = controller()
            pddAuthSheet(c, TradeFixtures.plan("h5" to "https://auth.example.test/pdd"))
            // 拉起期间：ON_STOP、ON_START 都先到了，拉起结果才回来
            ports.onLaunch = {
                c.onBackground()
                c.onForeground()
            }
            c.authorize()
            runCurrent()
            assertEquals("拉起结果回来时立即查询", 1, queries().size)
            api.succeed(queries().single(), """{"items":[{"platform":"pdd","status":"active"}]}""")
            runCurrent()
            assertEquals("已授权：以新键自动 open 一次", 2, opens().size)
        }

    // ---------------------------------------------------------------- 46

    /** 第 46 条：授权完成时不在前台（延迟恢复）不先删待办；回前台执行前再核有效期，已过期就不自动 open。 */
    @Test
    fun c46_aDeferredResumeAfterAuthorizationRechecksThePendingFirst() =
        runTest {
            val timed = MemoryPurchasePending { testScheduler.currentTime }
            val c = controller(pending = timed)
            pddAuthSheet(c, TradeFixtures.plan("h5" to "https://auth.example.test/pdd"))
            c.authorize()
            runCurrent()
            c.onBackground()
            c.onForeground()
            runCurrent()
            assertEquals(1, queries().size)
            // 查询还没回来又进了后台
            c.onBackground()
            api.succeed(queries().single(), """{"items":[{"platform":"pdd","status":"active"}]}""")
            runCurrent()
            assertNotNull("延迟恢复期间不先删待办", timed.peek())
            advanceTimeBy(10 * 60 * 1000L + 1)
            c.onForeground()
            runCurrent()
            assertEquals("待办已过期：不自动 open", 1, opens().size)
        }

    /** 第 46 条：延迟期间待办仍有效：回前台执行前核对通过，删除待办并以新键自动 open 一次。 */
    @Test
    fun c46_aDeferredResumeWithAValidPendingRunsOnce() =
        runTest {
            val timed = MemoryPurchasePending { testScheduler.currentTime }
            val c = controller(pending = timed)
            pddAuthSheet(c, TradeFixtures.plan("h5" to "https://auth.example.test/pdd"))
            c.authorize()
            runCurrent()
            c.onBackground()
            c.onForeground()
            runCurrent()
            c.onBackground()
            api.succeed(queries().single(), """{"items":[{"platform":"pdd","status":"active"}]}""")
            runCurrent()
            c.onForeground()
            runCurrent()
            assertEquals(2, opens().size)
            assertNull("执行时删除待办", timed.peek())
        }

    // ---------------------------------------------------------------- 31 / 39

    /** 第 31、39 条：淘宝绑定在途时进入后台，绑定返回 30104：不自动再拉起授权；回到前台查询，未授权删除待办给二选一。 */
    @Test
    fun c31_c39_aRecoveryAfterTheBackgroundDoesNotRelaunchAndUnboundOffersTheChoice() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.fail(
                opens().single(),
                30101,
                """{"auth_methods":["web_code"],"state":"s1","auth_url":"https://oauth.example.test/a?state=s1"}""",
            )
            runCurrent()
            assertNotNull("登记了授权待办", pending.peek())
            c.authorize()
            runCurrent()
            assertEquals("拉起了一次授权", 1, web.urls.size)
            val bind = api.last(TradeOperations.BIND_UNION)
            c.onBackground()
            api.fail(bind, 30104)
            runCurrent()
            val refresh = api.last(TradeOperations.GET_UNION_AUTH_URL)
            api.succeed(refresh, """{"auth_methods":["web_code"],"state":"s2","auth_url":"https://oauth.example.test/a?state=s2"}""")
            runCurrent()
            assertEquals("许可已撤销：不再自动拉起", 1, web.urls.size)
            c.onForeground()
            runCurrent()
            assertEquals("回到前台查询授权状态", 1, queries().size)
            assertEquals("回到前台也不自动拉起", 1, web.urls.size)
            api.succeed(queries().single(), """{"items":[{"platform":"taobao","status":"unbound"}]}""")
            runCurrent()
            val dialog = c.state.value.dialog
            assertTrue("未授权：二选一", dialog is PurchaseDialog.AuthDeclined && !dialog.failed)
            assertNull("删除本次待办", pending.peek())
        }

    // ---------------------------------------------------------------- 26 / 33

    /** 第 26 条：第一次 open 的补齐迟到，第二次 open（券失效、券后价相同）之后才到：旧补齐不填新结果，新补齐照常补上。 */
    @Test
    fun c26_aStaleFillDoesNotFillANewerPurchaseResult() =
        runTest {
            env.loggedIn = false
            ports.appLaunches = true
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            // 第一次：只改返利 → 补齐 F1
            api.succeed(opens().single(), TradeFixtures.openResult(newMin = 100, newMax = 200))
            runCurrent()
            assertEquals(1, env.refetched)
            c.buy(env.card!!, null)
            runCurrent()
            // 第二次：原券失效、券后价不变 → 券前价与券额置未知，补齐 F2
            api.succeed(
                opens().last(),
                TradeFixtures.openResult(attempt = "attempt-2", newMin = 100, newMax = 200, availability = "coupon_gone"),
            )
            runCurrent()
            assertEquals(2, env.refetched)
            assertNull(env.card!!.fenOf("coupon_fen"))
            // F1 迟到：旧报价（旧券 1000）不得填进新结果
            env.answerRefetch(quote(price = 5678, coupon = 1000, final = 4678, min = 100, max = 200), index = 0)
            assertNull("旧补齐不填新结果", env.card!!.fenOf("coupon_fen"))
            // F2：新券 500
            env.answerRefetch(quote(price = 5178, coupon = 500, final = 4678, min = 100, max = 200), index = 1)
            assertEquals(500L, env.card!!.fenOf("coupon_fen"))
            assertEquals(5178L, env.card!!.fenOf("price_fen"))
        }

    /** 第 33 条：迟到的改价结果发出补齐 F1，同键重试得到相同结果：不作废 F1，F1 到达照常补齐。 */
    @Test
    fun c33_aSameKeyReplayWithTheSameResultKeepsTheFillInFlight() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            advanceTimeBy(OpenPurchaseController.TIMEOUT_MILLIS)
            runCurrent()
            assertEquals(PurchasePhase.TIMEOUT, c.state.value.phase)
            val result = TradeFixtures.openResult(priceChanged = true, newFinal = 4990, newLink = "link-2")
            api.succeed(opens().single(), result)
            runCurrent()
            assertEquals("迟到的改价结果发出补齐", 1, env.refetched)
            c.retryAfterTimeout()
            runCurrent()
            api.succeed(opens().last(), result)
            runCurrent()
            assertEquals("相同结果不重发补齐", 1, env.refetched)
            env.answerRefetch(quote(price = 5990, coupon = 1000, final = 4990))
            assertEquals("F1 仍有效、补齐了券额", 1000L, env.card!!.fenOf("coupon_fen"))
            assertEquals(5990L, env.card!!.fenOf("price_fen"))
        }

    // ---------------------------------------------------------------- 27

    /** 第 27 条：超时后平台改为 not_launched：同键重试不再发 open，超时条收起。 */
    @Test
    fun c27_aTimeoutRetryChecksThePlatformStatusFirst() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            advanceTimeBy(OpenPurchaseController.TIMEOUT_MILLIS)
            runCurrent()
            env.status = "not_launched"
            c.retryAfterTimeout()
            runCurrent()
            assertEquals("不再发同键请求", 1, opens().size)
            assertEquals(PurchasePhase.IDLE, c.state.value.phase)
        }

    /** 第 27 条：登录后的重放、无返利购买同样先查平台状态（maintenance 提示、不发）。 */
    @Test
    fun c27_loginReplayAndNoRebatePurchaseCheckThePlatformStatus() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.fail(opens().single(), 10001)
            runCurrent()
            env.status = "maintenance"
            c.resumeAfterLogin()
            runCurrent()
            assertEquals("维护中：登录后不重放", 1, opens().size)
            assertEquals(
                OpenPurchaseController.MAINTENANCE_KEY,
                c.state.value.toast
                    ?.key,
            )
            // 授权二选一里的【仍去购买（无返利）】
            env.status = "on"
            c.buy(env.card!!, null)
            runCurrent()
            api.fail(opens().last(), 30121)
            runCurrent()
            assertTrue(c.state.value.dialog is PurchaseDialog.Notice)
            env.status = "not_launched"
            c.buyWithoutRebate()
            runCurrent()
            assertEquals("未开放：无返利购买也不发", 2, opens().size)
        }

    /** 第 27 条：授权完成后的自动执行同样先查平台状态。 */
    @Test
    fun c27_theAutomaticOpenAfterAuthorizationChecksThePlatformStatus() =
        runTest {
            val c = controller()
            pddAuthSheet(c, TradeFixtures.plan("h5" to "https://auth.example.test/pdd"))
            c.authorize()
            runCurrent()
            env.status = "not_launched"
            c.onForeground()
            runCurrent()
            api.succeed(queries().single(), """{"items":[{"platform":"pdd","status":"active"}]}""")
            runCurrent()
            assertEquals("未开放：授权后不自动 open", 1, opens().size)
        }

    // ---------------------------------------------------------------- 28

    /** 第 28 条：30141 下架写进这张卡后，卡片映射的购买按钮立即置灰（搜索卡与详情同一映射）。 */
    @Test
    fun c28_offShelfGreysTheCardsBuyButtonThroughTheMapping() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            assertEquals(BuyButtonState.NORMAL, mapped(env.card!!).buyState)
            c.buy(env.card!!, null)
            runCurrent()
            api.fail(opens().single(), 30141)
            runCurrent()
            assertEquals("下架即置灰", BuyButtonState.DISABLED, mapped(env.card!!).buyState)
        }

    // ---------------------------------------------------------------- 32 / 40

    /** 第 32 条：等下单须知（getTips）期间平台改为 not_launched：不外跳、不弹须知。 */
    @Test
    fun c32_aPlatformTurnedOffWhileWaitingForTheTipStopsTheJump() =
        runTest {
            ports.appLaunches = true
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(opens().single(), TradeFixtures.openResult())
            runCurrent()
            assertEquals("在等须知", 1, tipsCalls().size)
            env.status = "not_launched"
            answerTips(read = false)
            assertNull("不弹须知", c.state.value.dialog)
            assertTrue("不外跳", ports.launched.isEmpty())
        }

    /** 第 40 条：scheme 等待期间平台关闭：不执行回退步，但已执行的 scheme 照常上报；进入后台同样照常上报已执行的步骤。 */
    @Test
    fun c40_aPlatformTurnedOffBetweenStepsStopsTheFallbackButReportsTheExecutedStep() =
        runTest {
            env.loggedIn = false
            val c = controller()
            env.card = TradeFixtures.card()
            ports.onLaunch = { env.status = "not_launched" }
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(
                opens().single(),
                TradeFixtures.openResult(
                    plan =
                        TradeFixtures.plan(
                            "scheme" to "s://x",
                            "h5" to "https://h.example.test/x",
                        ),
                ),
            )
            runCurrent()
            assertEquals("回退步不执行", listOf("scheme:s://x"), ports.launched)
            assertEquals("已执行的 scheme 照常上报", listOf("scheme"), events.map { it.str("step") })
        }

    @Test
    fun c40_goingToTheBackgroundDuringAStepStillReportsIt() =
        runTest {
            env.loggedIn = false
            val c = controller()
            env.card = TradeFixtures.card()
            ports.onLaunch = { c.onBackground() }
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(
                opens().single(),
                TradeFixtures.openResult(
                    plan =
                        TradeFixtures.plan(
                            "scheme" to "s://x",
                            "h5" to "https://h.example.test/x",
                        ),
                ),
            )
            runCurrent()
            assertEquals("后台之后不执行下一步", listOf("scheme:s://x"), ports.launched)
            assertEquals("进入后台不算取消：已执行的步骤照常上报", listOf("scheme"), events.map { it.str("step") })
        }

    // ---------------------------------------------------------------- 34

    /** 第 34 条：open 已给出新券后价、券前价与券额待补齐：卡片照常显示新价并可购买（不当「价格不可用」）。 */
    @Test
    fun c34_aKnownNewPriceAwaitingFillStaysVisibleAndBuyable() =
        runTest {
            env.loggedIn = false
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(opens().single(), TradeFixtures.openResult(priceChanged = true, newFinal = 4990, newLink = "link-2"))
            runCurrent()
            c.dismissDialog()
            val card = mapped(env.card!!)
            assertFalse("不是价格不可用", card.priceUnavailable)
            assertEquals("新价照常显示", CouliFormat.money(4990), card.data.price?.mainAmount)
            assertEquals("不带口径词（券额未知）", "", card.data.price?.mainLabel)
            assertEquals("可以购买", BuyButtonState.NORMAL, card.buyState)
            assertEquals("券额未知：按钮不称领券购买", "btn.buy", env.card!!.cta())
            env.answerRefetch(quote(price = 5990, coupon = 1000, final = 4990))
            val filled = mapped(env.card!!)
            assertEquals("补齐后按券额给口径", "券后", filled.data.price?.mainLabel)
            assertEquals("btn.buy.coupon", env.card!!.cta())
            assertNull("补齐后不再是待补齐", env.card!![ProductCardMapper.PRICE_PENDING])
        }

    // ---------------------------------------------------------------- 35

    /** 第 35 条：零返利保持为零：按本次响应更新原因（空值清除 price_compare 与相似入口；新出现 price_compare 加上）。 */
    @Test
    fun c35_zeroStaysZeroStillUpdatesTheNoRebateCause() {
        val zero =
            CardPatch.apply(
                TradeFixtures.card(),
                obj(TradeFixtures.openResult(newMin = 0, newMax = 0, cause = "price_compare")),
            )
        assertEquals("price_compare", zero.str("no_rebate_cause"))
        val cleared = CardPatch.apply(zero, obj(TradeFixtures.openResult(newMin = 0, newMax = 0, cause = null)))
        assertNull("空值清除旧原因", cleared.str("no_rebate_cause"))
        val unknown = CardPatch.apply(zero, obj(TradeFixtures.openResult(newMin = 0, newMax = 0, cause = "future_reason")))
        assertNull("未知值清除旧原因", unknown.str("no_rebate_cause"))
        val added = CardPatch.apply(cleared, obj(TradeFixtures.openResult(newMin = 0, newMax = 0, cause = "price_compare")))
        assertEquals("新出现 price_compare 要加上", "price_compare", added.str("no_rebate_cause"))
    }

    // ---------------------------------------------------------------- 36

    /** 第 36 条：搜索页购买触发 42901：本页每一张卡的购买按钮都按 Retry-After 置灰。 */
    @Test
    fun c36_aRateLimitGreysEverySearchCard() =
        runTest {
            val c = controller()
            val tapped = TradeFixtures.card()
            val other = TradeFixtures.card(id = "c2", linkId = "link-9")
            c.buy(tapped, null)
            runCurrent()
            api.fail(opens().single(), 42901, disableMillis = 5_000L)
            runCurrent()
            val trade = KeyTexts
            assertEquals(BuyButtonState.DISABLED, searchPurchaseButton(c.state.value, trade, tapped, c.currentCard)?.state)
            assertEquals("同页别的卡同样置灰", BuyButtonState.DISABLED, searchPurchaseButton(c.state.value, trade, other, c.currentCard)?.state)
            advanceTimeBy(5_001L)
            runCurrent()
            assertNull("到期恢复", searchPurchaseButton(c.state.value, trade, other, c.currentCard))
        }

    // ---------------------------------------------------------------- 41 / 43

    /** 第 41、43 条：游客卡（返利未知）登录后同键重放得到零返利 + price_compare：弹比价确认，卡片写成确定的暂无返利。 */
    @Test
    fun c41_c43_aGuestReplayWithZeroRebateAndPriceCompareAsksAndWritesNoRebate() =
        runTest {
            val c = controller()
            env.card = guestCard()
            c.buy(env.card!!, null)
            runCurrent()
            api.fail(opens().single(), 10001)
            runCurrent()
            c.resumeAfterLogin()
            runCurrent()
            api.succeed(opens().last(), TradeFixtures.openResult(newMin = 0, newMax = 0, cause = "price_compare"))
            runCurrent()
            assertEquals("原返利未知也要比价确认", PurchaseDialog.CompareNoRebate, c.state.value.dialog)
            assertTrue("确认前不外跳", ports.launched.isEmpty())
            val card = env.card!!
            assertEquals("no_rebate", card.str("rebate_basis"))
            assertEquals(0L, card.fenOf("rebate_max_fen"))
            assertEquals("btn.buy.no_rebate", card.cta())
            assertEquals("price_compare", card.str("no_rebate_cause"))
        }

    /** 第 41 条：游客卡登录后得到正返利：清掉 login_required，先按服务端区间临时显示，再补齐口径。 */
    @Test
    fun c41_aGuestReplayWithAPositiveRebateClearsLoginRequired() {
        val card = CardPatch.apply(guestCard(), obj(TradeFixtures.openResult(newMin = 100, newMax = 300)))
        assertNull("不再是 login_required", card.str("rebate_basis"))
        assertTrue("临时展示状态", ProductCardMapper.provisionalRebate(card))
        assertEquals(300L, card.fenOf("rebate_max_fen"))
        assertTrue("要重取补口径", CardPatch.needsRefetch(guestCard(), obj(TradeFixtures.openResult(newMin = 100, newMax = 300))))
    }

    /** 第 43 条：用户已确认的无返利购买（no_rebate=true）返回 price_compare：不再重复弹比价确认。 */
    @Test
    fun c43_aConfirmedNoRebatePurchaseIsNotAskedAgain() {
        val response = obj("""{"code":0,"data":${TradeFixtures.openResult(newMin = 0, newMax = 0, cause = "price_compare")}}""")
        val unknown = obj("""{"currentPriceFen":4678,"currentRebateMaxFen":null}""")
        assertEquals(OpenActions.COMPARE_NO_REBATE, OpenResponseAction.map(response, unknown).actionRaw)
        val confirmed = obj("""{"currentPriceFen":4678,"currentRebateMaxFen":null,"noRebateConfirmed":true}""")
        assertEquals(OpenActions.JUMP, OpenResponseAction.map(response, confirmed).actionRaw)
        // 修订后的第 43 条：原返利为 0 也要确认（BR-PRICE-08 无豁免），只看这次是否已确认无返利购买
        val alreadyZero = obj("""{"currentPriceFen":4678,"currentRebateMaxFen":0}""")
        assertEquals("原返利为 0 同样确认", OpenActions.COMPARE_NO_REBATE, OpenResponseAction.map(response, alreadyZero).actionRaw)
    }

    // ---------------------------------------------------------------- 44

    /** 第 44 条：旧页面迟到的授权查询（未授权）不得清除新页面登记的购买待办。 */
    @Test
    fun c44_aLateQueryOfAnOldPageDoesNotClearTheNewPagesPending() =
        runTest {
            val shared = MemoryPurchasePending()
            val pageA = controller(env = env, pending = shared)
            val envB = FakeEnv()
            val pageB = controller(env = envB, pending = shared)
            env.card = TradeFixtures.card(platform = "pdd")
            pageA.buy(env.card!!, null)
            runCurrent()
            api.fail(opens().single(), 30111, """{"auth_jump":${TradeFixtures.plan("h5" to "https://auth.example.test/pdd")}}""")
            runCurrent()
            pageA.authorize()
            runCurrent()
            pageA.onBackground()
            pageA.onForeground()
            runCurrent()
            val queryA = queries().single()
            // 页面 B（同一个商品的详情）：30111 登记新的授权待办
            envB.card = TradeFixtures.card(platform = "pdd")
            pageB.buy(envB.card!!, null)
            runCurrent()
            api.fail(opens().last(), 30111, """{"auth_jump":${TradeFixtures.plan("h5" to "https://auth.example.test/pdd")}}""")
            runCurrent()
            val registered = shared.peek()
            assertNotNull(registered)
            // A 的旧查询此刻返回未授权
            api.succeed(queryA, """{"items":[{"platform":"pdd","status":"unbound"}]}""")
            runCurrent()
            assertEquals("B 的待办还在", registered, shared.peek())
            // A 出栈也不删 B 的待办
            pageA.leave()
            assertEquals(registered, shared.peek())
        }

    // ---------------------------------------------------------------- F-AND-19

    /** F-AND-19：下单须知的查询与已读请求归属绑定购买尝试与请求世代：新点击之后旧请求的归属立即失效（不会被当成断连重试）。 */
    @Test
    fun f19_tipRequestsAreOwnedByTheAttemptAndTheRequestGeneration() =
        runTest {
            ports.appLaunches = true
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.succeed(opens().single(), TradeFixtures.openResult())
            runCurrent()
            val getTips = tipsCalls().single()
            val owner = getTips.call.ownership!!
            assertTrue(owner.stillOwned())
            answerTips(read = false)
            assertTrue(c.state.value.dialog is PurchaseDialog.JumpTip)
            c.confirm()
            runCurrent()
            val mark = api.last(TradeOperations.MARK_TIP_READ)
            val markOwner = mark.call.ownership!!
            assertTrue("页面还在、尝试还是这次：有效", markOwner.stillOwned())
            // 用户再点一次购买（新尝试）
            c.buy(env.card!!, null)
            runCurrent()
            assertFalse("新点击之后旧的已读请求归属失效", markOwner.stillOwned())
            assertFalse("旧的须知查询归属失效", owner.stillOwned())
            assertTrue("取消时归属已失效", markOwner in api.cancelled)
        }

    // ---------------------------------------------------------------- AuthManage 25 / 31

    /** 第 25 条（授权管理）：取授权地址期间进入后台：回来也不自动拉起授权，按钮复原等用户再点。 */
    @Test
    fun c25_authManageDoesNotLaunchFromTheBackground() =
        runTest {
            val executor = TradeJumpExecutor(ports, baichuan, { }, { testScheduler.currentTime }, { TradeFixtures.NOW })
            val m = AuthManageController(api, executor, ports, web, baichuan, backgroundScope, keyGenerator = { "key" })
            m.load()
            runCurrent()
            api.succeed(api.last(TradeOperations.LIST_UNION_BINDINGS), """{"items":[{"platform":"pdd","status":"unbound"}]}""")
            runCurrent()
            m.authorize("pdd")
            runCurrent()
            m.onBackground()
            api.succeed(
                api.last(TradeOperations.GET_UNION_AUTH_URL),
                """{"auth_jump":${TradeFixtures.plan("h5" to "https://auth.example.test/pdd")}}""",
            )
            runCurrent()
            m.onForeground()
            runCurrent()
            assertTrue("不从后台拉起授权", ports.launched.isEmpty())
            assertNull("按钮复原", m.state.value.busyPlatform)
        }

    /** 测试用文案：键原样返回（断言看用了哪个键）。 */
    private object KeyTexts : TradeTexts {
        override fun text(key: String): String = key

        override fun format(
            key: String,
            vars: Map<String, String>,
        ): String = key + vars.entries.joinToString("") { "|${it.key}=${it.value}" }
    }
}
