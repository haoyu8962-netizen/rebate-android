@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.network.PendingKeyStore
import com.couli.core.router.UpdateCheck
import com.couli.feature.login.BindPhonePendingTags
import com.couli.feature.login.BindPhoneTags
import com.couli.feature.me.AccountTags
import com.couli.feature.me.MeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 换手机号（设置页内子页，G22）与 BindPhone 待确认（G07）：主链、字段错误、30411 / 30414 / 44001、token 过期 10003、10405；BindPhone 首次绑定超时 → 待确认；
 * 共性第 17、24、26、27、28 条。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class PhoneChangeIntegrationTest : AccountHarness() {
    @Test
    fun mainChainStep1Step2SubmitDoneBackToSettingsRefreshesMeAndClearsAllTokens() {
        launch()
        openPhoneChange()
        assertFalse("不显示当前号码（G03）", hasText(PHONE_OLD))
        passStep1()
        val sms1 = all("POST", SMS).single()
        assertEquals("step_up", body(sms1)["purpose"].toString().trim('"'))
        assertEquals("phone_change", body(sms1)["action"].toString().trim('"'))
        assertEquals("phone_change", body(all("POST", STEP_UP).single())["action"].toString().trim('"'))
        // 先在缓存里放一个注销的 token：换号成功后全部 action 的 token 都要清掉
        account.tokensCache.put("account_deletion", USER_A, "tok-other", Long.MAX_VALUE, 0L, "full")
        fillStep2()
        val sms2 = all("POST", SMS).last()
        assertEquals("bind", body(sms2)["purpose"].toString().trim('"'))
        assertEquals(PHONE_NEW, body(sms2)["phone"].toString().trim('"'))
        val meReads = all("GET", ME).size
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("结果页") { has(AccountTags.PHONE_DONE) }
        assertTrue("脱敏新号", hasText("139****1234"))
        val bind = all("POST", BIND).single()
        assertNotNull("带第 1 步的 token", bind.stepUp)
        assertEquals(PHONE_NEW, body(bind)["phone"].toString().trim('"'))
        idleUntil("刷新 getMe") { all("GET", ME).size > meReads }
        assertNull("全部 action 的 token 清空（G04）", account.tokensCache.take("account_deletion", USER_A, now, 0L, "full"))
        assertNull(account.tokensCache.take("phone_change", USER_A, now, 0L, "full"))
        assertTrue("不退出登录（G04）", tokens.accessToken != null)
        tap(AccountTags.PHONE_DONE_OK)
        idleUntil("回设置页") { has(MeTags.SETTINGS_PAGE) && !has(AccountTags.PHONE_PAGE) }
        assertTrue(records().isEmpty())
    }

    @Test
    fun a20002KeepsStep2AndTheNewPhoneAndTheNextSubmitUsesANewKeyWithoutReverifying() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2(code = "222222")
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil { has(AccountTags.PHONE_FIELD_ERROR) }
        assertTrue(has(AccountTags.PHONE_STEP2))
        assertTrue("保留新号", hasText(PHONE_NEW))
        type(AccountTags.PHONE_STEP2_CODE, CODE_OK)
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil { has(AccountTags.PHONE_DONE) }
        val posts = all("POST", BIND)
        assertNotEquals("键结束，再提交用新键", posts[0].key, posts[1].key)
        assertEquals("token 不重新验证（BR-ID-08 例）", 1, all("POST", STEP_UP).size)
        assertEquals(posts[0].stepUp, posts[1].stepUp)
    }

    @Test
    fun a30411And30414ShowPageErrorsAndContactServiceWithoutMerge() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        enqueueCode("POST", BIND, 30411, """{"reason":"mergeable","merge_ticket":"TEST_ONLY"}""")
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil { has(AccountTags.PHONE_ERROR) }
        assertTrue(hasText(t("error.30411")))
        assertTrue("不进并号（G08）", all("POST", "/v1/auth/merge").isEmpty())
        type(AccountTags.PHONE_STEP2_CODE, CODE_OK)
        enqueueCode("POST", BIND, 30414)
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil { hasText(t("error.30414")) }
        assertTrue("【联系客服】（G05）", has(AccountTags.PHONE_CONTACT_CS))
        assertTrue(records().isEmpty())
    }

    @Test
    fun a44001OpensTheBlockedDialogKeepsTheNewPhoneClearsTheCodeAndAppealsToService() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        enqueueCode("POST", BIND, 44001, """{"risk_msg_code":"test_unmapped"}""")
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("44001 弹窗") { has(AccountTags.PHONE_RISK_APPEAL) }
        assertTrue(hasText(t("phone_change.risk_blocked.desc")))
        assertTrue(hasText(t("error.44001")))
        tap(AccountTags.PHONE_RISK_APPEAL)
        assertFalse(has(AccountTags.PHONE_RISK_APPEAL))
        assertTrue("新号保留", hasText(PHONE_NEW))
        assertTrue("留在第 2 步", has(AccountTags.PHONE_STEP2))
        assertFalse("码清空", hasText(CODE_OK))
        assertTrue("键结束（从未未知，G09）", records().isEmpty())
    }

    @Test
    fun anExpiredToken10003OpensTheSheetAndReplaysTheOriginalKeyOnce() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        serverState.validTokens.clear()
        tap(AccountTags.PHONE_SUBMIT)
        passStepUpSheet()
        idleUntil { has(AccountTags.PHONE_DONE) }
        val posts = all("POST", BIND)
        assertEquals(2, posts.size)
        assertEquals("沿用原键", posts[0].key, posts[1].key)
        assertEquals("原体", posts[0].body, posts[1].body)
        assertNotEquals(posts[0].stepUp, posts[1].stepUp)
    }

    @Test
    fun acS1_83_a10405OnSubmitEntersForceUpdateWithoutGeneratingANewKeyAndKeepsTheRecord() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        enqueueCode("POST", BIND, 10405, """{"min_supported_version":"9.0.0"}""")
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("进强更") { update.routerUpdate == UpdateCheck.BELOW_MIN }
        val record = records().single()
        assertEquals("10405 不结束键：保留为 held（共性第 7 条）", PendingKeyStore.HELD, record.stateRaw)
        assertEquals(all("POST", BIND).single().key, record.key)
    }

    @Test
    fun releaseWithoutAnOwnedPhoneKeepsThePhoneRowDisabled() {
        ownedPhone = null
        launch()
        open(SETTINGS)
        idleUntil { has(PHONE_ROW) }
        assertFalse("G02：取不到号码不可点", enabled(PHONE_ROW))
    }

    // ---------------------------------------------------------------- 共性

    @Test
    fun lesson17_heldResubmitAndDoneUseTheFixedOriginalBodyNotTheEditedForm() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        enqueueCode("POST", BIND, 50401)
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil { has(AccountTags.PHONE_ERROR) }
        assertEquals(PendingKeyStore.HELD, records().single().stateRaw)
        // 用户改了号码再点提交：held 键沿用原键原体（G59），结果页取实际发送的摘要
        type(AccountTags.PHONE_NEW, "13900009999")
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil { has(AccountTags.PHONE_DONE) }
        val posts = all("POST", BIND)
        assertEquals(posts[0].key, posts[1].key)
        assertEquals(posts[0].body, posts[1].body)
        assertTrue(hasText("139****1234"))
        assertFalse(hasText("139****9999"))
    }

    @Test
    fun lesson26_step1SendLimitAndVerifyLimitAreSeparate() {
        launch()
        openPhoneChange()
        enqueueCode("POST", SMS, 42901, headers = mapOf("Retry-After" to "30"))
        click(AccountTags.PHONE_STEP1_SEND)
        idleUntil { all("POST", SMS).size == 1 }
        settle()
        assertFalse("发码限流", enabled(AccountTags.PHONE_STEP1_SEND))
        type(AccountTags.PHONE_STEP1_CODE, CODE_OK)
        assertTrue("【下一步】只看验证限制", enabled(AccountTags.PHONE_STEP1_NEXT))
        enqueueCode("POST", STEP_UP, 42901, headers = mapOf("Retry-After" to "30"))
        tap(AccountTags.PHONE_STEP1_NEXT)
        idleUntil { all("POST", STEP_UP).size == 1 }
        settle()
        tap(AccountTags.PHONE_STEP1_NEXT)
        settle()
        assertEquals("验证限流在执行层拦下第二次", 1, all("POST", STEP_UP).size)
        assertEquals("发码限流期间不再发码", 1, all("POST", SMS).size)
    }

    @Test
    fun lesson27_step2BackReturnsToStep1AndOtherwiseExitsToSettings() {
        launch()
        openPhoneChange()
        passStep1()
        type(AccountTags.PHONE_NEW, PHONE_NEW)
        composeRule.runOnUiThread { routing.toString() }
        androidx.test.espresso.Espresso
            .pressBack()
        settle()
        idleUntil("系统返回回第 1 步") { has(AccountTags.PHONE_STEP1) }
        assertFalse("清空新号", hasText(PHONE_NEW))
        androidx.test.espresso.Espresso
            .pressBack()
        settle()
        idleUntil("第 1 步返回退出到设置") { has(MeTags.SETTINGS_PAGE) && !has(AccountTags.PHONE_PAGE) }
        assertTrue(all("POST", BIND).isEmpty())
    }

    @Test
    fun lesson28_aLateStep2SendResultDoesNotTouchTheReplacedForm() {
        launch()
        openPhoneChange()
        passStep1()
        type(AccountTags.PHONE_NEW, PHONE_NEW)
        val arrived = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        enqueue("POST", SMS) {
            arrived.countDown()
            release.await(WAIT_S, java.util.concurrent.TimeUnit.SECONDS)
            envelope("null", 20001)
        }
        click(AccountTags.PHONE_STEP2_SEND)
        assertTrue(arrived.await(WAIT_S, java.util.concurrent.TimeUnit.SECONDS))
        androidx.test.espresso.Espresso
            .pressBack()
        settle()
        idleUntil { has(AccountTags.PHONE_STEP1) }
        passStep1()
        release.countDown()
        settle()
        settle()
        assertFalse("迟到的发码结果不在新表单上标错", has(AccountTags.PHONE_FIELD_ERROR))
    }

    @Test
    fun lesson24_anAbandonSettledElsewhereReturnsThisPageToStep1() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        enqueueTimeout("POST", BIND)
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil { has(AccountTags.PENDING_CARD) }
        val key = records().single().key
        // 别处（BindPhone 页的放弃）结算了同一个键：服务端作废、本机记录结束，共享通知携带终结结果
        pendingBackend.delete(USER_A, key)
        composeRule.runOnUiThread {
            account.shared.notifyPending(
                com.couli.core.me.account.PendingSettlement(
                    USER_A,
                    tokens.loginEpoch,
                    "bindPhone",
                    key,
                    com.couli.core.me.account.PhoneChangeOutcome.STEP1,
                    "pending_confirm.abandoned",
                    "139****1234",
                ),
            )
        }
        settle()
        idleUntil("回第 1 步重新验证") { has(AccountTags.PHONE_STEP1) }
    }

    // ---------------------------------------------------------------- BindPhone 待确认（G07）

    private fun openBindPhone() {
        serverState.phoneBound = false
        launch()
        open(SETTINGS)
        idleUntil { has(PHONE_ROW) && enabled(PHONE_ROW) }
        click(PHONE_ROW)
        idleUntil("BindPhone") { has(BindPhoneTags.PAGE) }
    }

    private fun bindTimeout() {
        type(BindPhoneTags.PHONE, PHONE_NEW)
        tap(BindPhoneTags.GET_CODE)
        type(BindPhoneTags.CODE, CODE_OK)
        enqueueTimeout("POST", BIND)
        if (has(BindPhoneTags.SUBMIT) && enabled(BindPhoneTags.SUBMIT)) tap(BindPhoneTags.SUBMIT)
        idleUntil("BindPhone 待确认卡") { has(BindPhonePendingTags.CARD) }
    }

    @Test
    fun g07_firstBindTimeoutShowsPendingAndConfirmSucceeds() {
        openBindPhone()
        bindTimeout()
        val record = records().single()
        assertEquals(PendingKeyStore.UNKNOWN, record.stateRaw)
        assertNull("首次绑定不要 step-up", all("POST", BIND).single().stepUp)
        assertTrue(hasText("139****1234"))
        tap(BindPhonePendingTags.CONFIRM)
        idleUntil("确认成功（原键）") { records().isEmpty() }
        assertEquals(record.key, all("POST", BIND).last().key)
        assertTrue(serverState.phoneBound)
    }

    @Test
    fun g07_firstBindAbandonedAllowsBindingAgainWithANewKey() {
        openBindPhone()
        bindTimeout()
        val key = records().single().key
        tap(BindPhonePendingTags.ABANDON)
        idleUntil { has(BindPhonePendingTags.ABANDON_OK) }
        tap(BindPhonePendingTags.ABANDON_OK)
        idleUntil { records().isEmpty() && !has(BindPhonePendingTags.CARD) }
        assertEquals("phone_change", body(all("POST", ABANDON).single())["action"].toString().trim('"'))
        type(BindPhoneTags.CODE, CODE_OK)
        if (enabled(BindPhoneTags.SUBMIT)) tap(BindPhoneTags.SUBMIT)
        idleUntil { all("POST", BIND).size == 2 }
        assertNotEquals(key, all("POST", BIND).last().key)
    }

    @Test
    fun g07_theSameRecordIsShownWhenEnteringPhoneChangeFromSettings() {
        openBindPhone()
        bindTimeout()
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        // 服务端仍未确认；本机记下已绑定（假设别处已查清 phone_bound=true）：换号入口看到同一条未决记录
        serverState.phoneBound = true
        composeRule.runOnUiThread {
            me.deps.repo.refresh(
                me.computeIdentity(),
                com.couli.feature.me
                    .MeReader { true },
            )
        }
        settle()
        idleUntil { has(PHONE_ROW) && enabled(PHONE_ROW) }
        click(PHONE_ROW)
        idleUntil("换号子页直接显示待确认（跳过第 1 步）") { has(AccountTags.PENDING_CARD) }
        assertFalse(has(AccountTags.PHONE_STEP1))
    }
}
