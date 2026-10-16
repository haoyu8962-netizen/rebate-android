@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliFormat
import com.couli.core.network.PendingKeyStore
import com.couli.feature.login.BindPhonePendingTags
import com.couli.feature.login.BindPhoneTags
import com.couli.feature.me.AccountTags
import com.couli.feature.me.MeTags
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 身份固定（S0，brief §0 第 1 条）与迟到 / 作废（§2 第 13 项）：保留真实页面渲染出的旧回调，换号或作用域换代后执行，零请求、零写入；getDeletion 在途换账号；
 * 申请在途出栈；A 失效 → 登录 B → 返回旧页看不到 A 的东西；受限 → full 重建；42901 执行层拦截；鸿蒙三轮场景（共性第 6、8、10、11、13、15、16、18、20、21、
 * 23、25 条）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions", "LargeClass")
class AccountIdentityIntegrationTest : AccountHarness() {
    /** 真实页面上某个控件此刻渲染出的点击回调（保留下来，换代后再执行）。 */
    private fun clickOf(tag: String): () -> Unit {
        val node =
            composeRule
                .onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .first()
        val action = assertNotNullAndGet(node.config.getOrNull(SemanticsActions.OnClick)?.action)
        return { composeRule.runOnUiThread { action() } }
    }

    /** 真实输入框此刻渲染出的「设置文字」回调。 */
    private fun setTextOf(tag: String): (String) -> Unit {
        val node =
            composeRule
                .onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .first()
        val action = assertNotNullAndGet(node.config.getOrNull(SemanticsActions.SetText)?.action)
        return { text -> composeRule.runOnUiThread { action(AnnotatedString(text)) } }
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull("控件没有这个语义动作", value)
        return value!!
    }

    private fun writes(): List<Recorded> = all().filter { it.method == "POST" }

    // ---------------------------------------------------------------- S0：旧回调零请求零写入

    @Test
    fun s0_oldDeletionCallbacksAfterASessionSwitchSendNothingAndWriteNothing() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        val toggle = clickOf(AccountTags.DELETE_WAIVER)
        checkWaiver()
        val apply = clickOf(AccountTags.DELETE_SUBMIT)
        switchSession(USER_B)
        idleUntil("按新身份重建") { has(AccountTags.DELETE_SUBMIT) }
        val before = writes().size
        toggle()
        apply()
        settle()
        assertEquals("旧回调零请求", before, writes().size)
        assertTrue("零写入（A、B 都没有未决记录）", records(USER_A).isEmpty() && records(USER_B).isEmpty())
        assertFalse("B 的页面没有被旧回调勾上", enabled(AccountTags.DELETE_SUBMIT))
    }

    @Test
    fun s0_oldPendingConfirmAndAbandonCallbacksAfterAScopeChangeSendNothing() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        enqueueTimeout("POST", DELETION)
        checkWaiverAndSubmit()
        idleUntil { has(AccountTags.PENDING_CARD) }
        val confirm = clickOf(AccountTags.PENDING_CONFIRM)
        val abandon = clickOf(AccountTags.PENDING_ABANDON)
        // 同一账号换了作用域（full → deletion_only）：也是换代
        switchSession(USER_A, "deletion_only")
        settle()
        val before = writes().size
        confirm()
        abandon()
        settle()
        assertEquals("旧确认、放弃回调零请求", before, writes().size)
        assertFalse("没有弹出旧卡的放弃确认", has(AccountTags.ABANDON_OK))
        assertEquals(PendingKeyStore.UNKNOWN, records().single().stateRaw)
    }

    @Test
    fun s0_anOldCancelCallbackAfterASessionSwitchSendsNothing() {
        serverState.deletion = deletionJson("cooling", canCancel = true)
        launch()
        openDeleteFromSettings()
        idleUntil { has(AccountTags.DELETE_CANCEL) }
        val cancel = clickOf(AccountTags.DELETE_CANCEL)
        switchSession(USER_B)
        settle()
        cancel()
        settle()
        assertTrue(all("POST", CANCEL).isEmpty())
    }

    @Test
    fun s0_oldPhoneChangeCallbacksAfterASessionSwitchSendNothing() {
        launch()
        openPhoneChange()
        type(AccountTags.PHONE_STEP1_CODE, CODE_OK)
        val next = clickOf(AccountTags.PHONE_STEP1_NEXT)
        val send1 = clickOf(AccountTags.PHONE_STEP1_SEND)
        passStep1()
        fillStep2()
        val setPhone = setTextOf(AccountTags.PHONE_NEW)
        val submit = clickOf(AccountTags.PHONE_SUBMIT)
        val send2 = clickOf(AccountTags.PHONE_STEP2_SEND)
        switchSession(USER_B)
        settle()
        val before = writes().size
        setPhone("13900005555")
        next()
        send1()
        send2()
        submit()
        settle()
        assertEquals("旧回调（下一步、发码、提交、新号输入）零请求", before, writes().size)
        assertTrue(records(USER_A).isEmpty() && records(USER_B).isEmpty())
        assertFalse("B 的设置页没有被旧输入写进去", hasText("13900005555"))
    }

    // ---------------------------------------------------------------- 迟到与作废

    @Test
    fun aGetDeletionInFlightWhenTheAccountSwitchesDoesNotLandOnTheNewAccount() {
        launch()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("GET", DELETION) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope(deletionJson("cooling", canCancel = true))
        }
        open(SETTINGS)
        idleUntil { has(DELETE_ROW) }
        click(DELETE_ROW)
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        switchSession(USER_B)
        release.countDown()
        settle()
        settle()
        idleUntil { has(AccountTags.DELETE_SUBMIT) }
        assertFalse("A 的冷静期不落到 B 的页面", has(AccountTags.DELETE_COOLING))
    }

    @Test
    fun anApplyInFlightWhenThePageLeavesKeepsThePendingRecordCorrectWithoutTouchingThePage() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", DELETION) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            okhttp3.mockwebserver
                .MockResponse()
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        checkWaiverAndSubmit()
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        release.countDown()
        idleUntil("写请求离页不取消，结论落到未决记录") { records().singleOrNull()?.stateRaw == PendingKeyStore.UNKNOWN }
        assertEquals(SETTINGS, top())
        click(DELETE_ROW)
        idleUntil("重进按记录显示待确认") { has(AccountTags.PENDING_CARD) }
    }

    @Test
    fun sessionExpiresThenBLogsInAndTheOldPagesShowNothingOfA() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        enqueueTimeout("POST", DELETION)
        checkWaiverAndSubmit()
        idleUntil { has(AccountTags.PENDING_CARD) }
        switchSession(null)
        switchSession(USER_B)
        idleUntil { has(AccountTags.DELETE_SUBMIT) }
        assertFalse("B 看不到 A 的待确认卡", has(AccountTags.PENDING_CARD))
        assertFalse("A 的勾选不带到 B", enabled(AccountTags.DELETE_SUBMIT))
    }

    @Test
    fun sessionExpiresThenBLogsInAndThePhoneSubPageShowsNothingOfA() {
        launch()
        openPhoneChange()
        passStep1()
        type(AccountTags.PHONE_NEW, PHONE_NEW)
        click(AccountTags.PHONE_STEP2_SEND)
        switchSession(null)
        switchSession(USER_B)
        settle()
        assertFalse("A 的新号不在页面上", hasText(PHONE_NEW))
        assertFalse("A 的第 2 步不在页面上", has(AccountTags.PHONE_STEP2))
    }

    @Test
    fun restrictedToFullRebuildsTheDeletionPage() {
        // 强更下的受限会话（不被屏障刷新）：从强更页进注销页，勾选后刷新为 full → 按新作用域重建（不沿用旧控制器，勾选清空）
        refreshScope = "deletion_only"
        launch(scope = "deletion_only", forced = true)
        idleUntil { top() == FORCE_UPDATE && has(com.couli.app.update.FORCE_UPDATE_DELETE_TAG) }
        click(com.couli.app.update.FORCE_UPDATE_DELETE_TAG)
        idleUntil { top() == DELETE_ACCOUNT && has(AccountTags.DELETE_SUBMIT) }
        assertFalse("受限会话无【去提现】", has(AccountTags.DELETE_GO_WITHDRAW))
        checkWaiver()
        assertTrue(enabled(AccountTags.DELETE_SUBMIT))
        val identity = account.identity.value
        composeRule.runOnUiThread {
            tokens.replaceIf(tokens.generation, "access-$USER_A", "refresh-$USER_A", "full")
            me.syncIdentity()
        }
        settle()
        idleUntil("作用域换代") { account.identity.value != identity }
        idleUntil("重建：勾选清空") { has(AccountTags.DELETE_SUBMIT) && !enabled(AccountTags.DELETE_SUBMIT) }
    }

    @Test
    fun a42901IsEnforcedAtTheExecutionLayerForApplyConfirmAbandonCancelAndVerify() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        checkWaiver()
        val apply = clickOf(AccountTags.DELETE_SUBMIT)
        enqueueCode("POST", DELETION, 42901, headers = mapOf("Retry-After" to "30"))
        apply()
        settle()
        idleUntil { all("POST", DELETION).size == 1 }
        apply()
        apply()
        settle()
        assertEquals("禁用期内旧回调也发不出（执行层）", 1, all("POST", DELETION).size)
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        // 撤回：冷静期页
        serverState.deletion = deletionJson("cooling", canCancel = true)
        click(DELETE_ROW)
        idleUntil { has(AccountTags.DELETE_CANCEL) }
        val cancel = clickOf(AccountTags.DELETE_CANCEL)
        enqueueCode("POST", CANCEL, 42901, headers = mapOf("Retry-After" to "30"))
        cancel()
        idleUntil { all("POST", CANCEL).size == 1 }
        cancel()
        settle()
        assertEquals(1, all("POST", CANCEL).size)
    }

    @Test
    fun a42901IsEnforcedAtTheExecutionLayerForSubmitSendAndVerifyOnPhoneChange() {
        launch()
        openPhoneChange()
        type(AccountTags.PHONE_STEP1_CODE, CODE_OK)
        val verify = clickOf(AccountTags.PHONE_STEP1_NEXT)
        enqueueCode("POST", STEP_UP, 42901, headers = mapOf("Retry-After" to "30"))
        verify()
        idleUntil { all("POST", STEP_UP).size == 1 }
        verify()
        settle()
        assertEquals("验证禁用期内不再发", 1, all("POST", STEP_UP).size)
    }

    // ---------------------------------------------------------------- 共性（鸿蒙三轮）

    @Test
    fun lesson06_bindPhonePageOldCallbacksAfterASessionSwitchSendNothing() {
        serverState.phoneBound = false
        launch()
        open(SETTINGS)
        idleUntil { has(PHONE_ROW) && enabled(PHONE_ROW) }
        click(PHONE_ROW)
        idleUntil { has(BindPhoneTags.PAGE) }
        type(BindPhoneTags.PHONE, PHONE_NEW)
        sendBindCode()
        type(BindPhoneTags.CODE, CODE_OK)
        val submit = runCatching { clickOf(BindPhoneTags.SUBMIT) }.getOrNull()
        switchSession(USER_B)
        settle()
        val before = all("POST", BIND).size
        submit?.invoke()
        settle()
        assertEquals("旧会话的绑定回调零请求", before, all("POST", BIND).size)
        assertTrue(records(USER_B).isEmpty())
    }

    @Test
    fun lesson08_bindPhoneAndPhoneChangeShareOnePhoneGate() {
        launch()
        assertSame("BindPhone 与换号共用同一份会话级状态", account.shared, login.bindPhone.shared)
        openPhoneChange()
        passStep1()
        fillStep2()
        enqueueCode("POST", BIND, 42901, headers = mapOf("Retry-After" to "30"))
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil { account.shared.phoneGate.active }
        assertFalse(enabled(AccountTags.PHONE_SUBMIT))
    }

    @Test
    fun lesson10_the30416PromptKeepsThisResponsesAmountEvenAfterTheWalletIsRefetched() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        enqueueCode("POST", DELETION, 30416, """{"amount_fen":200}""")
        serverState.negativeFen = 567
        val work = workSnapshot()
        checkWaiverAndSubmit()
        idleUntil { has(AccountTags.DELETE_NEGATIVE_NOTICE) }
        idleUntil("钱包已重取") { all("GET", WALLET).size >= 2 }
        settle()
        // RC-04：请求记下≠重取结果已落地；等申请与重取的续体都结束（重取到的 negative_fen 已有机会写进页面），再重新读提示核对
        workSince(work, "申请与钱包重取结算")
        val notice = textOf(AccountTags.DELETE_NEGATIVE_NOTICE)
        assertTrue("提示用本次的金额：$notice", notice.contains(CouliFormat.money(200)))
        assertFalse("不被重取到的 negative_fen 替换：$notice", notice.contains(CouliFormat.money(567)))
    }

    /**
     * FU-AND-0716 RC-04：30416 之后的钱包重取被服务端挂住（请求已记下、应答未出）。挂住期间这次重取的续体仍在跑（断言点不可达）；在 [workSince] 第一次核对时
     * 放行（显式握手），返回后重新读提示，断言金额仍是本次 30416 的、没有被重取到的 negative_fen 替换。
     */
    @Test
    fun fuand0716_rc04_aHeldWalletRefetchIsAwaitedBeforeReadingThe30416Prompt() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        enqueueCode("POST", DELETION, 30416, """{"amount_fen":200}""")
        serverState.negativeFen = 567
        val arrived = CountDownLatch(1)
        val held = CountDownLatch(1)
        enqueue("GET", WALLET) {
            arrived.countDown()
            held.await(WAIT_S, TimeUnit.SECONDS)
            envelope(walletJson(567))
        }
        try {
            val work = workSnapshot()
            checkWaiverAndSubmit()
            idleUntil { has(AccountTags.DELETE_NEGATIVE_NOTICE) }
            assertTrue("重取已到服务端、应答挂住", arrived.await(WAIT_S, TimeUnit.SECONDS))
            settle()
            assertTrue("挂住期间重取的续体仍在跑（原写法在这里就读提示断言了）", workPendingSince(work))
            var released = false
            workSince(work, "申请与钱包重取结算") {
                if (!released) {
                    released = true
                    held.countDown()
                }
            }
            assertTrue("等待开始轮询后才放行", released)
            val notice = textOf(AccountTags.DELETE_NEGATIVE_NOTICE)
            assertTrue("提示用本次的金额：$notice", notice.contains(CouliFormat.money(200)))
            assertFalse("不被重取到的 negative_fen 替换：$notice", notice.contains(CouliFormat.money(567)))
        } finally {
            held.countDown()
        }
    }

    @Test
    fun lesson11_a44001ArrivingWhileThePageIsCoveredDoesNotOpenTheDialog() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope("{}", 44001)
        }
        val work = workSnapshot()
        tap(AccountTags.PHONE_SUBMIT)
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        open("Help")
        settle()
        release.countDown()
        settle()
        settle()
        // RC-05：放行后 44001 应答与换号页回调仍在后台；等它们结束再现读弹窗
        workSince(work, "被覆盖期间 44001 结算")
        assertFalse("被覆盖时不弹 44001 弹窗", has(AccountTags.PHONE_RISK_APPEAL))
    }

    /**
     * FU-AND-0716 RC-05：lesson11 的握手对照。被覆盖期间提交的 44001 被服务端挂住时，[workSince] 不返回（提交任务仍在跑，断言点不可达）；第一次核对时才放行，
     * 返回后现读，断言被覆盖时不弹 44001 弹窗。
     */
    @Test
    fun fuand0716_rc05_aHeld44001WhileCoveredIsAwaitedBeforeCheckingTheDialog() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope("{}", 44001)
        }
        try {
            val work = workSnapshot()
            tap(AccountTags.PHONE_SUBMIT)
            assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
            open("Help")
            settle()
            assertTrue("挂住期间提交任务仍在跑（原写法放行后两轮 settle 就断言）", workPendingSince(work))
            var released = false
            workSince(work, "被覆盖期间 44001 结算") {
                if (!released) {
                    released = true
                    release.countDown()
                }
            }
            assertTrue("等待开始轮询后才放行", released)
            assertFalse("被覆盖时不弹 44001 弹窗", has(AccountTags.PHONE_RISK_APPEAL))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun lesson13_theGetMeAfterSuccessHonoursTheSharedMeReadGate() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        composeRule.runOnUiThread { me.gates.read.start(30_000L) }
        val reads = all("GET", ME).size
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil { has(AccountTags.PHONE_DONE) }
        settle()
        assertEquals("共享读闸门内不发 getMe", reads, all("GET", ME).size)
    }

    @Test
    fun lesson15_aStepUpSendLimitIsKeyedByAccountPhoneAndAction() {
        launch()
        openPhoneChange()
        enqueueCode("POST", SMS, 42901, headers = mapOf("Retry-After" to "30"))
        click(AccountTags.PHONE_STEP1_SEND)
        idleUntil { all("POST", SMS).size == 1 }
        androidx.test.espresso.Espresso
            .pressBack()
        settle()
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        serverState.requireStepUp = true
        openDeleteFromSettings()
        checkWaiverAndSubmit()
        idleUntil("account_deletion 面板照常发码") { all("POST", SMS).size == 2 }
        assertEquals("account_deletion", body(all("POST", SMS).last())["action"].toString().trim('"'))
    }

    @Test
    fun lesson18_bindPhoneSettlementsAreNotifiedToTheSharedSignals() {
        val seen = mutableListOf<com.couli.core.me.account.PendingSettlement>()
        launch()
        val job = launchCollect(account) { seen += it }
        openPhoneChange()
        passStep1()
        fillStep2()
        enqueueTimeout("POST", BIND)
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil { seen.any { it.operationId == "bindPhone" } }
        job.cancel()
    }

    private fun launchCollect(
        account: AppAccount,
        sink: (com.couli.core.me.account.PendingSettlement) -> Unit,
    ): kotlinx.coroutines.Job =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch {
            account.shared.pendingSignals.collect { sink(it) }
        }

    @Test
    fun lesson20_aRestoredKeyedSendLimitReleasesTheButtonWhenItExpires() {
        launch()
        openPhoneChange()
        enqueueCode("POST", SMS, 42901, headers = mapOf("Retry-After" to "2"))
        val work = workSnapshot()
        click(AccountTags.PHONE_STEP1_SEND)
        idleUntil { all("POST", SMS).size == 1 }
        settle()
        // RC-05：发送中本身就让按钮置灰，证明不了 42901 已结算；推进时钟前先等发码应答与限流结算结束（截止时间按推进前的时钟起算）
        workSince(work, "第 1 步发码 42901 结算")
        assertFalse(enabled(AccountTags.PHONE_STEP1_SEND))
        now += 3_000L
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        composeRule.mainClock.advanceTimeBy(3_000L)
        settle()
        idleUntil("到期后自动解除") { enabled(AccountTags.PHONE_STEP1_SEND) }
    }

    /**
     * FU-AND-0716 RC-05：lesson20 的握手对照。第 1 步发码的 42901 被服务端挂住时，按钮因「发送中」置灰，但限流还没结算；[workSince] 不返回，
     * 第一次核对时才放行；返回后再推进时钟，到期自动解除（截止时间按推进前的时钟起算）。
     */
    @Test
    fun fuand0716_rc05_aHeldStep1Send42901IsSettledBeforeTheClockMoves() {
        launch()
        openPhoneChange()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", SMS) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope("null", 42901).setHeader("Retry-After", "2")
        }
        try {
            val work = workSnapshot()
            click(AccountTags.PHONE_STEP1_SEND)
            assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
            settle()
            assertFalse("发送中按钮置灰", enabled(AccountTags.PHONE_STEP1_SEND))
            assertTrue("挂住期间发码任务仍在跑（置灰来自发送中，不是限流）", workPendingSince(work))
            var released = false
            workSince(work, "第 1 步发码 42901 结算") {
                if (!released) {
                    released = true
                    release.countDown()
                }
            }
            assertTrue("等待开始轮询后才放行", released)
            assertFalse(enabled(AccountTags.PHONE_STEP1_SEND))
            now += 3_000L
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            composeRule.mainClock.advanceTimeBy(3_000L)
            settle()
            idleUntil("到期后自动解除") { enabled(AccountTags.PHONE_STEP1_SEND) }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun lesson21_aFirstBindLeftInFlightIsNotCancelledWhenThePageLeaves() {
        serverState.phoneBound = false
        launch()
        open(SETTINGS)
        idleUntil { has(PHONE_ROW) && enabled(PHONE_ROW) }
        click(PHONE_ROW)
        idleUntil { has(BindPhoneTags.PAGE) }
        type(BindPhoneTags.PHONE, PHONE_NEW)
        sendBindCode()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            serverState.phoneBound = true
            envelope("{}")
        }
        type(BindPhoneTags.CODE, CODE_OK)
        submitBindIfNotStarted(requireNoRequest = true)
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        release.countDown()
        idleUntil("写请求离页不取消：结论落到未决记录（成功即结束）") { records().isEmpty() }
        assertEquals(1, all("POST", BIND).size)
    }

    @Test
    fun lesson23_aBindPhonePendingConfirmAfterLeavingDoesNotNavigate() {
        serverState.phoneBound = false
        launch()
        open(SETTINGS)
        idleUntil { has(PHONE_ROW) && enabled(PHONE_ROW) }
        click(PHONE_ROW)
        idleUntil { has(BindPhoneTags.PAGE) }
        type(BindPhoneTags.PHONE, PHONE_NEW)
        sendBindCode()
        enqueueTimeout("POST", BIND)
        type(BindPhoneTags.CODE, CODE_OK)
        submitBindIfNotStarted(requireNoRequest = true)
        idleUntil { has(BindPhonePendingTags.CARD) }
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope("null", 10005)
        }
        val jobs = loginJobs()
        tap(BindPhonePendingTags.CONFIRM)
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        val stackBefore = stack()
        release.countDown()
        settle()
        settle()
        // FU-AND-0716 窄修四：确认是会话级任务（离页不截断），放行后应答与记录结算仍在后台；等它在 AppLogin 会话作用域里结束再核对
        loginWorkSince(jobs, "离页后确认结算")
        assertEquals("离页后的补办结果不导航", stackBefore, stack())
        assertEquals(PendingKeyStore.UNKNOWN, records().single().stateRaw)
    }

    /**
     * FU-AND-0716 RC-04：lesson25 的握手对照。绑定成功被服务端挂住时，[workSince] 不会返回（绑定任务仍在跑，断言点不可达）；它第一次核对时才放行，
     * 返回后重新读导航栈，断言迟到的成功没有按路由名移除后来打开的页面。
     */
    @Test
    fun fuand0716_rc04_aHeldLateBindSuccessIsAwaitedBeforeReadingTheBackStack() {
        serverState.phoneBound = false
        launch()
        open(SETTINGS)
        idleUntil { has(PHONE_ROW) && enabled(PHONE_ROW) }
        click(PHONE_ROW)
        idleUntil { has(BindPhoneTags.PAGE) }
        type(BindPhoneTags.PHONE, PHONE_NEW)
        sendBindCode()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            serverState.phoneBound = true
            envelope("{}")
        }
        try {
            type(BindPhoneTags.CODE, CODE_OK)
            val bindWork = workSnapshot()
            submitBindIfNotStarted(requireNoRequest = true)
            assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
            composeRule.runOnUiThread { navController.popBackStack() }
            settle()
            assertEquals(SETTINGS, top())
            open("Help")
            settle()
            val stackBefore = stack()
            assertTrue("挂住期间绑定任务仍在跑（原写法放行后两轮 settle 就断言）", workPendingSince(bindWork))
            var released = false
            workSince(bindWork, "迟到绑定成功与收尾结算") {
                if (!released) {
                    released = true
                    release.countDown()
                }
            }
            assertTrue("等待开始轮询后才放行", released)
            assertEquals("迟到的绑定成功不按路由名移除后来打开的页面", stackBefore, stack())
            assertTrue(has(MeTags.SETTINGS_PAGE) || top() == "Help")
        } finally {
            release.countDown()
        }
    }

    @Test
    fun lesson25_aLateBindSuccessDoesNotPopALaterSameNamePage() {
        serverState.phoneBound = false
        launch()
        open(SETTINGS)
        idleUntil { has(PHONE_ROW) && enabled(PHONE_ROW) }
        click(PHONE_ROW)
        idleUntil { has(BindPhoneTags.PAGE) }
        type(BindPhoneTags.PHONE, PHONE_NEW)
        sendBindCode()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            serverState.phoneBound = true
            envelope("{}")
        }
        type(BindPhoneTags.CODE, CODE_OK)
        val bindWork = workSnapshot()
        submitBindIfNotStarted(requireNoRequest = true)
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        assertEquals(SETTINGS, top())
        open("Help")
        settle()
        val stackBefore = stack()
        release.countDown()
        settle()
        settle()
        // RC-04：等迟到的绑定成功连同其后的 getMe、共享收尾都结束，再重新读导航栈核对
        workSince(bindWork, "迟到绑定成功与收尾结算")
        assertEquals("迟到的绑定成功不按路由名移除后来打开的页面", stackBefore, stack())
        assertTrue(has(MeTags.SETTINGS_PAGE) || top() == "Help")
    }
}
