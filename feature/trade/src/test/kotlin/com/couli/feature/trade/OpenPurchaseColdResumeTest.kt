package com.couli.feature.trade

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-TB-WEBCODE-B 测试：购买执行器的「确认中」与冷启动恢复槽认领（设计稿 §3.2 OpenPurchaseController ① ②、§6.1「确认中」、§6.4 第 6 步）。
 * - 拿到授权码后 AuthSheet 进入 confirming（文案键 auth.confirming 由界面按 confirming 取；不可关闭）；
 * - [OpenPurchaseController.claimColdAuthResume]：前台、没有在途请求或弹窗、卡片 link_id 或 product_key 与槽位一致才认领；用**待办里的 link_id**
 *   和新幂等键发一次 open，然后照常外跳；同一个槽只能认领一次；认领时删掉购买待办。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenPurchaseColdResumeTest {
    private val api = FakeTradeApi()
    private val ports = FakePorts()
    private val baichuan = FakeBaichuan()
    private val web = FakeWebCode()
    private val env = FakeEnv()
    private val counter = AuthSheetCounter()
    private var keySeq = 0
    private lateinit var pending: MemoryPurchasePending
    private lateinit var resume: ColdAuthResume

    private fun TestScope.controller(): OpenPurchaseController {
        if (!::pending.isInitialized) pending = MemoryPurchasePending { testScheduler.currentTime }
        if (!::resume.isInitialized) resume = ColdAuthResume { testScheduler.currentTime }
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
            coldResume = resume,
        )
    }

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun opens() = api.calls.filter { it.call.operationId == TradeOperations.OPEN_LINK }

    private fun binds() = api.calls.filter { it.call.operationId == TradeOperations.BIND_UNION }

    private fun TestScope.answerTips() {
        runCurrent()
        val tips = api.calls.lastOrNull { it.call.operationId == TradeOperations.GET_TIPS && !it.result.isCompleted } ?: return
        api.succeed(tips, """{"jump_tip":{"taobao":"2026-10-01T00:00:00+08:00"},"inviter_before_buy":null}""")
        runCurrent()
    }

    /** 冷启动恢复出来的状态：进程里只有恢复槽与（Router 持久化的）购买待办，页面执行器还没有任何尝试。 */
    private fun TestScope.coldResumeWaiting() {
        pending = MemoryPurchasePending { testScheduler.currentTime }
        resume = ColdAuthResume { testScheduler.currentTime }
        pending.register(
            obj("""{"kind":"auth","link_id":"link-old","platform":"taobao","client_owner":"page-dead#1","product_key":"tb:c1"}"""),
        )
        resume.offer(ColdAuthResume.Ticket("link-old", "tb:c1", "taobao", testScheduler.currentTime + 600_000L))
    }

    // ---------------------------------------------------------------- 确认中

    @Test
    fun c01_afterTheCodeArrivesTheAuthSheetIsConfirmingAndCannotBeDismissed() =
        runTest {
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.fail(opens().single(), 30101, """{"auth_url":"https://oauth.taobao.com/a","state":"st_01","auth_methods":["web_code"]}""")
            runCurrent()
            c.authorize()
            runCurrent()
            assertEquals("bindings 已发出、还没结果", 1, binds().size)
            assertEquals(
                "拿到授权码后进入确认中（界面取 auth.confirming）",
                PurchaseDialog.AuthSheet("taobao", busy = true, confirming = true),
                c.state.value.dialog,
            )
            c.dismissDialog()
            assertEquals("确认中不可关闭", PurchaseDialog.AuthSheet("taobao", busy = true, confirming = true), c.state.value.dialog)
            api.succeed(binds().single(), """{"platform":"taobao","status":"active"}""")
            runCurrent()
            assertNull("确认完成：弹层关掉", c.state.value.dialog)
            assertEquals("Bound 后新键 open 一次", 2, opens().size)
        }

    @Test
    fun c02_aCancelledAuthorizationNeverShowsConfirming() =
        runTest {
            web.result = AuthStepResult.Cancelled
            val c = controller()
            env.card = TradeFixtures.card()
            c.buy(env.card!!, null)
            runCurrent()
            api.fail(opens().single(), 30101, """{"auth_url":"https://oauth.taobao.com/a","state":"st_01","auth_methods":["web_code"]}""")
            runCurrent()
            val seen = mutableListOf<PurchaseDialog?>()
            c.authorize()
            seen += c.state.value.dialog
            runCurrent()
            seen += c.state.value.dialog
            assertFalse("取消不进确认中：$seen", seen.any { it is PurchaseDialog.AuthSheet && it.confirming })
            assertEquals(PurchaseDialog.AuthDeclined("taobao", failed = false), c.state.value.dialog)
        }

    // ---------------------------------------------------------------- 认领恢复槽

    @Test
    fun c03_claimOpensThePendingLinkIdWithAFreshKeyClearsThePendingThenJumps() =
        runTest {
            coldResumeWaiting()
            val c = controller()
            // 详情页重取后卡片的 link_id 换新了，product_key 不变
            val card = TradeFixtures.card(linkId = "link-new", id = "c1")
            env.card = card
            assertTrue("product_key 对上：认领", c.claimColdAuthResume(card))
            runCurrent()
            val open = opens().singleOrNull()
            assertNotNull("发一次 open：${api.ops()}", open)
            assertEquals("open 的是待办里的 link_id（BR-ID-17 同一 link_id）", mapOf("link_id" to "link-old"), open!!.call.pathParams)
            assertEquals("新的幂等键", "key-1", open.call.idempotencyKey)
            assertNull("认领时删掉购买待办", pending.peek())
            assertNull("槽位取走", resume.peek())
            api.succeed(open, TradeFixtures.openResult())
            answerTips()
            assertEquals("然后按 jump 外跳", listOf("scheme:demo-app://item"), ports.launched)
            assertEquals("只 open 一次", 1, opens().size)
        }

    @Test
    fun c04_claimAlsoMatchesByLinkId() =
        runTest {
            coldResumeWaiting()
            val c = controller()
            val card = TradeFixtures.card(linkId = "link-old", id = "c9")
            assertTrue("link_id 对上：认领", c.claimColdAuthResume(card))
            runCurrent()
            assertEquals(mapOf("link_id" to "link-old"), opens().single().call.pathParams)
        }

    @Test
    fun c05_aDifferentCardDoesNotClaim() =
        runTest {
            coldResumeWaiting()
            val c = controller()
            assertFalse("link_id、product_key 都不对", c.claimColdAuthResume(TradeFixtures.card(linkId = "link-x", id = "c9")))
            runCurrent()
            assertTrue("不发 open", opens().isEmpty())
            assertNotNull("待办不动", pending.peek())
            assertNotNull("槽位留着", resume.peek())
        }

    @Test
    fun c06_aBackgroundPageDoesNotClaimUntilItIsBackInTheForeground() =
        runTest {
            coldResumeWaiting()
            val c = controller()
            val card = TradeFixtures.card(linkId = "link-new", id = "c1")
            c.onBackground()
            assertFalse("不在前台不认领", c.claimColdAuthResume(card))
            runCurrent()
            assertTrue(opens().isEmpty())
            assertNotNull(resume.peek())
            c.onForeground()
            assertTrue("回到前台再认领", c.claimColdAuthResume(card))
            runCurrent()
            assertEquals(1, opens().size)
        }

    @Test
    fun c07_aBusyPageDoesNotClaim() =
        runTest {
            coldResumeWaiting()
            val c = controller()
            val other = TradeFixtures.card(platform = "pdd", linkId = "link-p", id = "p1")
            env.card = other
            c.buy(other, null)
            runCurrent()
            assertFalse("open 在途：不认领", c.claimColdAuthResume(TradeFixtures.card(linkId = "link-new", id = "c1")))
            api.fail(opens().single(), 30103)
            runCurrent()
            assertNotNull("弹窗在：前提", c.state.value.dialog)
            assertFalse("有弹窗：不认领", c.claimColdAuthResume(TradeFixtures.card(linkId = "link-new", id = "c1")))
            assertEquals("只有那次购买的 open", 1, opens().size)
            assertNotNull("槽位留着", resume.peek())
        }

    @Test
    fun c08_aTicketCanBeClaimedOnlyOnceAcrossPages() =
        runTest {
            coldResumeWaiting()
            val first = controller()
            val second = controller()
            val card = TradeFixtures.card(linkId = "link-new", id = "c1")
            assertTrue("第一个页面认领", first.claimColdAuthResume(card))
            assertFalse("第二个页面认领不到", second.claimColdAuthResume(card))
            assertFalse("同一页面也不能再认领", first.claimColdAuthResume(card))
            runCurrent()
            assertEquals("只 open 一次", 1, opens().size)
        }

    @Test
    fun c09_anExpiredTicketIsNotClaimed() =
        runTest {
            coldResumeWaiting()
            val c = controller()
            advanceTimeBy(600_001L)
            assertFalse("过了 deadline 不认领", c.claimColdAuthResume(TradeFixtures.card(linkId = "link-new", id = "c1")))
            runCurrent()
            assertTrue(opens().isEmpty())
        }
}
