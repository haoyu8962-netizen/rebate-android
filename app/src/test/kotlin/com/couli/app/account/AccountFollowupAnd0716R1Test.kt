@file:Suppress("MagicNumber")

package com.couli.app.account

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.FlowWait
import com.couli.app.routing.RouteDestination
import com.couli.app.trade.AppTrade
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PageApi
import com.couli.core.network.ReplayMode
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.tradejump.UnavailableBaichuan
import com.couli.feature.login.BindPhonePendingTags
import com.couli.feature.login.BindPhoneTags
import com.couli.feature.login.BindPhoneViewModel
import com.couli.feature.trade.JumpPorts
import com.couli.feature.trade.OpenPurchaseController
import com.couli.feature.trade.PurchaseEnv
import com.couli.feature.trade.TradeOperations
import com.couli.feature.trade.UnavailableWebCodeAuthorizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-0716 第 1 轮评审修复在真实 AppShell + Router + AppLogin + AppAccount + BindPhone（+ AppTrade 购买执行器）上的交错回归：
 * - R1-01（S0）：A 的【确认上一次提交】成功后 getMe 挂起 → 期间退出 A、游客购买触发 B 第三方新号登录（待办暂缓）→ A 迟到返回：不解除 B 的暂缓、
 *   不重放购买、不外跳；编排者补充：同账号同登录世代作用域变成 deletion_only 也算归属失效，暂缓不变。
 * - R1-03：确认 / 放弃的非终结结果（42901、5xxxx）在页面隐藏期间、或页面先恢复之后到达：不写待确认提示，共享结算（未决记录、phoneGate）照常。
 * - R1-04：进页补查在途时页面先被覆盖、又先恢复，补查之后才查明已绑：页面已可见，立即只退出这一个绑手机页并恢复待办。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountFollowupAnd0716R1Test : AccountHarness() {
    // ---------------------------------------------------------------- 购买执行器（假接口，记录 open 与外跳）

    private class Call(
        val call: ApiCall,
        val result: CompletableDeferred<ApiResult>,
    )

    private val tradeCalls = mutableListOf<Call>()

    private val tradeApi =
        object : PageApi {
            override suspend fun send(call: ApiCall): ApiResult {
                val pending = Call(call, CompletableDeferred())
                synchronized(tradeCalls) { tradeCalls += pending }
                return pending.result.await()
            }

            override fun cancelOwned(owner: CallOwnership) = Unit

            override fun epochs(): CallEpochs = CallEpochs(0, 0)
        }

    private val launched = mutableListOf<String>()

    private val ports =
        object : JumpPorts {
            override fun installed(platform: String): String = "unknown"

            override fun launchApp(
                type: String,
                value: String,
            ): Boolean {
                launched += "$type:$value"
                return false
            }

            override fun openBrowser(url: String): Boolean {
                launched += "browser:$url"
                return true
            }

            override fun copy(text: String): Boolean = true
        }

    private val card =
        Json
            .parseToJsonElement(
                """{"card_id":"c1","product_key":"pdd:1","platform":"pdd","title":"milk","link_id":"link-1","availability":"ok"}""",
            ).jsonObject

    private fun opens(): List<Call> = synchronized(tradeCalls) { tradeCalls.filter { it.call.operationId == TradeOperations.OPEN_LINK } }

    private fun env(trade: AppTrade): PurchaseEnv =
        object : PurchaseEnv {
            override fun loggedIn(): Boolean = trade.loggedIn()

            override fun basicMode(): Boolean = false

            override fun requestConsent() = Unit

            override fun platformStatus(platform: String): String? = null

            override fun updateCard(
                target: JsonObject,
                transform: (JsonObject) -> JsonObject,
            ) = Unit

            override fun open(
                route: String,
                params: JsonObject,
            ) = Unit

            override fun refetchCard(
                target: JsonObject,
                merge: (JsonObject, JsonObject) -> JsonObject,
            ) = Unit

            override fun helpLink(key: String): Pair<String, JsonObject>? = null
        }

    private fun purchaseSetUp(): Pair<AppTrade, OpenPurchaseController> {
        lateinit var trade: AppTrade
        lateinit var controller: OpenPurchaseController
        composeRule.runOnUiThread {
            trade =
                AppTrade(
                    api = tradeApi,
                    store = null,
                    routing = routing,
                    tracker = Provider { error("not used") },
                    baichuan = UnavailableBaichuan(),
                    webCode = UnavailableWebCodeAuthorizer,
                    ports = ports,
                    browserNoticeMillis = 0L,
                )
            controller = trade.purchaseController(env(trade), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        }
        settle()
        return trade to controller
    }

    // ---------------------------------------------------------------- A 的待确认记录与挂起的确认

    /** A 上一次首次绑定结果未知（进程被杀后的冷启动恢复为待确认）。 */
    private fun launchWithUnknownBindOfA() {
        serverState.phoneBound = false
        seedInFlight(BIND_OPERATION, PENDING_KEY, """{"phone":"$PHONE_NEW","code":"$CODE_OK"}""", "139****1234")
        launch()
        idleUntil("冷启动的 phone_bound 已查清") { login.phoneBinding.get(tokens.loginEpoch) == false }
        composeRule.runOnUiThread { assertNotNull("A 有待确认记录", login.bindPhone.pendingCard(USER_A)) }
    }

    /**
     * 以 A 的固定身份【确认上一次提交】（共享执行器同页面一样走 [com.couli.feature.login.BindPhoneService.resolvePending]），确认成功后的 getMe 挂在假服务上；
     * 返回这次确认的任务与 getMe 的放行闸。
     */
    private fun confirmOfAWithGetMeHeld(): Pair<Job, CountDownLatch> {
        val hold = CountDownLatch(1)
        meHold = hold
        val before = all("GET", ME).size
        lateinit var job: Job
        composeRule.runOnUiThread {
            val owner = login.bindPhone.owner()
            job = CoroutineScope(Dispatchers.Main.immediate).launch { login.bindPhone.resolvePending(PENDING_KEY, owner, abandon = false) }
        }
        idleUntil("确认已成功、A 的 getMe 已到达") {
            all("POST", BIND).isNotEmpty() && all("GET", ME).drop(before).any { it.user == USER_A }
        }
        settle()
        assertFalse("确认任务仍在等成功后的 getMe", job.isCompleted)
        return job to hold
    }

    /**
     * R1-01：A 的确认迟到结算不解除 B 的暂缓、不让购买页重放外跳。
     *
     * 交错：A 的 getMe 在服务端挂住 → 放行，响应已由网络层交回（在途为 0），A 的确认任务的主线程续体已排队但还没执行 → 同一条主线程消息里依次发生
     * 「退出 A → 游客购买（10001 登记购买待办）→ B 第三方新号登录（待办暂缓）」→ 之后主线程才执行 A 的续体（迟到结算）。只等 getMe 挂在服务端时
     * 退出 A，getMe 会随会话清理立即作废、A 的结算在暂缓开始之前就跑完，碰不到这个窗口。
     */
    @Test
    fun fuand0716_r1_01_aLateConfirmOfAccountADoesNotReleaseTheHeldPurchaseOfTheNewThirdPartyAccountB() {
        launchWithUnknownBindOfA()
        val (trade, controller) = purchaseSetUp()
        val (job, hold) = confirmOfAWithGetMeHeld()
        // 放行 A 的 getMe：不泵主线程，等网络层交回（在途为 0），再给 IO 线程一点时间把续体投到主线程队列
        hold.countDown()
        meHold = null
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FlowWait.CEILING_MS)
        while (inFlight.size != 0 && System.nanoTime() < deadline) Thread.sleep(FlowWait.POLL_MS)
        assertEquals("A 的 getMe 已由网络层交回", 0, inFlight.size)
        Thread.sleep(LATE_POST_MS)
        var releases = -1L
        composeRule.runOnUiThread {
            assertFalse("A 的确认任务还没在主线程结算", job.isCompleted)
            // 退出 A → 游客购买 → 10001 登记购买待办
            tokens.clear()
            login.syncSession()
            me.syncIdentity()
            controller.buy(card, "product_detail.buy_bar.1")
            val first = opens().single()
            val effect = ErrorEffect(10001, ErrorEffectKind.PAGE_ACTION, ReplayMode.NONE, registerPending = false, disableMillis = null)
            first.result.complete(ApiResult.Failure(10001, "", JsonObject(emptyMap()), "trace-1", effect, first.call.idempotencyKey))
            assertNotNull("登记了购买待办", trade.pending.peek())
            // B 第三方新号登录：待办暂缓
            tokens.save("access-$USER_B", "refresh-$USER_B", "full", USER_B)
            login.phoneBinding.set(tokens.loginEpoch, false)
            login.prerequisiteDoneHeld()
            login.sessionSynced()
            login.onSessionStored(tokens.generation)
            me.syncIdentity()
            assertTrue("B 的待办暂缓", routing.router.pendingHeld)
            releases = routing.router.heldReleases.value
        }
        // 之后主线程才执行 A 的迟到结算
        idleUntil("A 的确认任务结束") { job.isCompleted }
        settle()
        assertTrue("B 的暂缓不被 A 解除", routing.router.pendingHeld)
        assertEquals("没有释放", releases, routing.router.heldReleases.value)
        assertNotNull("B 的购买待办仍在", trade.pending.peek())
        assertEquals("不重放购买", 1, opens().size)
        assertTrue("不外跳", launched.isEmpty())
        assertEquals("守卫仍是 B 的已登录未绑", SessionState.LOGGED_IN, routing.session.value)
        composeRule.runOnUiThread { trade.release(controller) }
    }

    /** R1-01（编排者补充，鸿蒙 RC-01 同类）：getMe 挂起期间同账号同登录世代作用域变成 deletion_only → 迟到结算不做共享收尾、本账号的暂缓不变。 */
    @Test
    fun fuand0716_r1_01_aLateConfirmAfterTheSameSessionTurnedDeletionOnlyKeepsTheHold() {
        launchWithUnknownBindOfA()
        composeRule.runOnUiThread { login.prerequisiteDoneHeld() }
        settle()
        assertTrue(routing.router.pendingHeld)
        val (job, hold) = confirmOfAWithGetMeHeld()
        val epoch = tokens.loginEpoch
        composeRule.runOnUiThread {
            assertTrue(tokens.replaceIf(tokens.generation, "access-$USER_A", "refresh-$USER_A", "deletion_only"))
            login.onSessionStored(tokens.generation)
            me.syncIdentity()
        }
        settle()
        assertEquals("同一登录世代", epoch, tokens.loginEpoch)
        assertTrue("作用域变化本身不解除暂缓", routing.router.pendingHeld)
        val releases = routing.router.heldReleases.value
        hold.countDown()
        meHold = null
        idleUntil("确认任务结束") { job.isCompleted }
        settle()
        assertTrue("暂缓不变", routing.router.pendingHeld)
        assertEquals("没有释放", releases, routing.router.heldReleases.value)
        assertEquals(SessionState.RESTRICTED, routing.session.value)
    }

    // ---------------------------------------------------------------- R1-03

    @SuppressLint("RestrictedApi")
    private fun bindModel(): BindPhoneViewModel {
        lateinit var model: BindPhoneViewModel
        composeRule.runOnUiThread {
            val entry =
                navController.currentBackStack.value.last {
                    it.destination.hasRoute(RouteDestination::class) && it.toRoute<RouteDestination>().name == BIND_PHONE
                }
            model = ViewModelProvider.create(entry)[BindPhoneViewModel::class]
        }
        return model
    }

    /** 首次绑定超时 → 待确认卡；返回页面模型。 */
    private fun openPendingCard(): BindPhoneViewModel {
        serverState.phoneBound = false
        launch()
        open(SETTINGS)
        idleUntil { has(PHONE_ROW) && enabled(PHONE_ROW) }
        click(PHONE_ROW)
        idleUntil("BindPhone") { has(BindPhoneTags.PAGE) }
        val model = bindModel()
        val seq = model.state.value.resultSeq
        type(BindPhoneTags.PHONE, PHONE_NEW)
        tap(BindPhoneTags.GET_CODE)
        idleUntil("发码") { all("POST", SMS).isNotEmpty() && model.state.value.resultSeq > seq }
        type(BindPhoneTags.CODE, CODE_OK)
        enqueueTimeout("POST", BIND)
        tap(BindPhoneTags.SUBMIT)
        idleUntil("待确认卡") { has(BindPhonePendingTags.CARD) }
        return model
    }

    /** 确认请求挂在假服务上，放行后回 [code]（42901 带 Retry-After）。返回到达闸与放行闸。 */
    private fun holdConfirm(code: Int): Pair<CountDownLatch, CountDownLatch> {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS)
            envelope("null", code, "trace-${code}1").apply { if (code == 42901) setHeader("Retry-After", "120") }
        }
        return arrived to release
    }

    private fun confirmWhileCovered(
        code: Int,
        backBeforeAnswer: Boolean,
    ): BindPhoneViewModel {
        val model = openPendingCard()
        val (arrived, release) = holdConfirm(code)
        val seq = model.state.value.resultSeq
        tap(BindPhonePendingTags.CONFIRM)
        assertTrue("确认请求已到达", arrived.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS))
        open("Help")
        idleUntil("Help 盖住") { top() == "Help" }
        if (backBeforeAnswer) {
            composeRule.runOnUiThread { navController.popBackStack() }
            idleUntil("页面先恢复") { top() == BIND_PHONE }
        }
        release.countDown()
        idleUntil("页面结算确认结果") { model.state.value.resultSeq > seq }
        return model
    }

    /** R1-03：42901 在页面被覆盖期间到达 → 不写待确认提示，回来也不补；phoneGate 照常记下，待确认记录保留。 */
    @Test
    fun fuand0716_r1_03_aRateLimitedConfirmAnswerWhileCoveredWritesNoNoticeButStillSettles() {
        val model = confirmWhileCovered(42901, backBeforeAnswer = false)
        assertNull("隐藏期间不写提示", model.state.value.pendingNotice)
        assertTrue("共享 phoneGate 已记下", model.state.value.pendingLimited)
        composeRule.runOnUiThread { navController.popBackStack() }
        idleUntil("回到 BindPhone") { top() == BIND_PHONE && has(BindPhonePendingTags.CARD) }
        assertNull("回来不补提示", model.state.value.pendingNotice)
        assertEquals("记录仍待确认", PENDING_STATE_UNKNOWN, records().single { it.operationId == BIND_OPERATION }.stateRaw)
    }

    /** R1-03：页面先恢复（许可已换代）、之后 50001 才到 → 不立即显示旧响应的提示。 */
    @Test
    fun fuand0716_r1_03_aServerErrorConfirmAnswerAfterThePageCameBackShowsNoStaleNotice() {
        val model = confirmWhileCovered(50001, backBeforeAnswer = true)
        assertNull("旧响应不在恢复后的页面提示", model.state.value.pendingNotice)
        assertFalse(hasText("500011"))
        assertTrue("待确认卡仍在", has(BindPhonePendingTags.CARD))
    }

    /** R1-03 对照：页面一直可见时同样的 50001 照常显示待确认提示（带 trace6）。 */
    @Test
    fun fuand0716_r1_03_theSameServerErrorWhileThePageStaysVisibleShowsTheNotice() {
        val model = openPendingCard()
        val (arrived, release) = holdConfirm(50001)
        val seq = model.state.value.resultSeq
        tap(BindPhonePendingTags.CONFIRM)
        assertTrue(arrived.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS))
        release.countDown()
        idleUntil("页面结算确认结果") { model.state.value.resultSeq > seq }
        assertNotNull("可见时照常提示", model.state.value.pendingNotice)
    }

    // ---------------------------------------------------------------- R1-04

    /**
     * R1-04：冷启动后 phone_bound 未知 → 主动打开手机号级页面，守卫先进 BindPhone、发起进页补查 → 补查在途时被 Help 盖住、又在补查返回之前回到
     * BindPhone（onPageVisible 时还不知道已绑）→ 补查之后查明已绑：页面已可见，立即只退出这一个绑手机页，待办经守卫恢复（resumeOnly）。
     */
    @Test
    fun fuand0716_r1_04_aRecheckThatFindsBoundAfterThePageAlreadyCameBackClosesItAtOnce() {
        serverState.phoneBound = false
        launch()
        idleUntil("冷启动的 phone_bound 已查清") { login.phoneBinding.get(tokens.loginEpoch) == false }
        val hold = CountDownLatch(1)
        meHold = hold
        switchSession(USER_A)
        assertNull(login.phoneBinding.get(tokens.loginEpoch))
        val checks = login.phoneChecksStarted
        open(PHONE_ROUTE)
        idleUntil("守卫先进 BindPhone、发起补查") { top() == BIND_PHONE && has(BindPhoneTags.PAGE) && login.phoneChecksStarted > checks }
        assertTrue("登记了待办", routing.router.hasPendingAction)
        open("Help")
        idleUntil("Help 盖住") { top() == "Help" }
        composeRule.runOnUiThread { navController.popBackStack() }
        idleUntil("页面先恢复") { top() == BIND_PHONE }
        settle()
        assertTrue("补查还没返回，页面照常", BIND_PHONE in stack())
        serverState.phoneBound = true
        hold.countDown()
        meHold = null
        idleUntil("补查之后立即只退出这一个绑手机页") { BIND_PHONE !in stack() && login.phoneChecksSettled() }
        assertEquals(SessionState.PHONE_BOUND, routing.session.value)
        idleUntil("待办经守卫恢复") {
            routing.router.lastOutcome.value?.let {
                it.request.route == PHONE_ROUTE && it.request.source == RouteSource.PENDING_ACTION.raw
            } == true
        }
        assertFalse("待办已消费", routing.router.hasPendingAction)
        assertFalse(routing.router.pendingHeld)
    }

    private companion object {
        const val BIND_PHONE = "BindPhone"
        const val PHONE_ROUTE = "InviteShare"
        const val BIND_OPERATION = "bindPhone"
        const val PENDING_KEY = "11111111-2222-4333-8444-555555555555"
        const val PENDING_STATE_UNKNOWN = "unknown"

        /** 网络层交回之后，给 IO 线程把续体投到主线程队列的时间（只影响能否碰到窗口，不影响判定）。 */
        const val LATE_POST_MS = 300L
    }
}
