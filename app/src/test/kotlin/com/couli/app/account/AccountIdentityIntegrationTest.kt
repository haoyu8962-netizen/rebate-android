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
        tap(AccountTags.DELETE_WAIVER)
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
        tap(AccountTags.DELETE_WAIVER)
        tap(AccountTags.DELETE_SUBMIT)
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
        tap(AccountTags.DELETE_WAIVER)
        tap(AccountTags.DELETE_SUBMIT)
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
        tap(AccountTags.DELETE_WAIVER)
        tap(AccountTags.DELETE_SUBMIT)
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
        tap(AccountTags.DELETE_WAIVER)
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
        tap(AccountTags.DELETE_WAIVER)
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
        tap(BindPhoneTags.GET_CODE)
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
        tap(AccountTags.DELETE_WAIVER)
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil { has(AccountTags.DELETE_NEGATIVE_NOTICE) }
        idleUntil("钱包已重取") { all("GET", WALLET).size >= 2 }
        settle()
        val notice = textOf(AccountTags.DELETE_NEGATIVE_NOTICE)
        assertTrue("提示用本次的金额：$notice", notice.contains(CouliFormat.money(200)))
        assertFalse("不被重取到的 negative_fen 替换：$notice", notice.contains(CouliFormat.money(567)))
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
        tap(AccountTags.PHONE_SUBMIT)
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        open("Help")
        settle()
        release.countDown()
        settle()
        settle()
        assertFalse("被覆盖时不弹 44001 弹窗", has(AccountTags.PHONE_RISK_APPEAL))
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
        tap(AccountTags.DELETE_WAIVER)
        tap(AccountTags.DELETE_SUBMIT)
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
        click(AccountTags.PHONE_STEP1_SEND)
        idleUntil { all("POST", SMS).size == 1 }
        settle()
        assertFalse(enabled(AccountTags.PHONE_STEP1_SEND))
        now += 3_000L
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        composeRule.mainClock.advanceTimeBy(3_000L)
        settle()
        idleUntil("到期后自动解除") { enabled(AccountTags.PHONE_STEP1_SEND) }
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
        tap(BindPhoneTags.GET_CODE)
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            serverState.phoneBound = true
            envelope("{}")
        }
        type(BindPhoneTags.CODE, CODE_OK)
        if (all("POST", BIND).isEmpty() && has(BindPhoneTags.SUBMIT)) tap(BindPhoneTags.SUBMIT)
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
        tap(BindPhoneTags.GET_CODE)
        enqueueTimeout("POST", BIND)
        type(BindPhoneTags.CODE, CODE_OK)
        if (all("POST", BIND).isEmpty() && has(BindPhoneTags.SUBMIT)) tap(BindPhoneTags.SUBMIT)
        idleUntil { has(BindPhonePendingTags.CARD) }
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope("null", 10005)
        }
        tap(BindPhonePendingTags.CONFIRM)
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        val stackBefore = stack()
        release.countDown()
        settle()
        settle()
        assertEquals("离页后的补办结果不导航", stackBefore, stack())
        assertEquals(PendingKeyStore.UNKNOWN, records().single().stateRaw)
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
        tap(BindPhoneTags.GET_CODE)
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            serverState.phoneBound = true
            envelope("{}")
        }
        type(BindPhoneTags.CODE, CODE_OK)
        if (all("POST", BIND).isEmpty() && has(BindPhoneTags.SUBMIT)) tap(BindPhoneTags.SUBMIT)
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
        assertEquals("迟到的绑定成功不按路由名移除后来打开的页面", stackBefore, stack())
        assertTrue(has(MeTags.SETTINGS_PAGE) || top() == "Help")
    }
}
