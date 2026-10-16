@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.update.FORCE_UPDATE_DELETE_TAG
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.UpdateRaw
import com.couli.feature.me.AccountTags
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * APP-13a Android 第 3 轮评审（android-review-r3.md，R3-01..R3-04 S1）的回归测试：走真实 AppShell、Router 与网络层管线，只把传输层换成本地假服务。
 * 用例名带评审编号。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class AccountReviewR3Test : AccountHarness() {
    /** 服务端签发 [token] 的验证成功回包（与假服务缺省回答同形）。 */
    private fun stepUpOk(token: String) =
        envelope("""{"step_up_token":"$token","expire_at":"2099-01-01T00:05:00+08:00"}""").also { serverState.validTokens += token }

    /** 当前缓存里某个 action 的 token（按当前账号、世代、作用域）。 */
    private fun cached(action: String): String? {
        val identity = account.identity.value
        return account.stepUp.tokens.take(action, USER_A, now, identity.epoch, identity.scope)
    }

    /** 换号第 2 步提交超时 → 待确认卡；返回未决键。 */
    private fun phonePending(): String {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        enqueueTimeout("POST", BIND)
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("换号待确认卡") { has(AccountTags.PENDING_CARD) }
        return records().single().key
    }

    // ---------------------------------------------------------------- R3-01

    /**
     * R3-01：实例 A 提交在途 → 返回设置进实例 B → B 第 1 步验证已在服务端签发 T2、回包延迟 → A 成功（共享失效世代推进、清缓存）→ B 收到 T2：
     * 不保存、不写共享缓存、不进第 2 步；B 重新验证后的提交不带 T2。
     */
    @Test
    fun r3_01_aLateStep1VerifySuccessAfterAPhoneChangeSuccessElsewhereIsNotKept() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        val arrivedA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrivedA.countDown()
            releaseA.await(WAIT_S, TimeUnit.SECONDS)
            envelope("{}")
        }
        tap(AccountTags.PHONE_SUBMIT)
        assertTrue(arrivedA.await(WAIT_S, TimeUnit.SECONDS))
        androidx.test.espresso.Espresso
            .pressBack()
        settle()
        idleUntil("A 退出到设置") { !has(AccountTags.PHONE_PAGE) && has(PHONE_ROW) }
        click(PHONE_ROW)
        idleUntil("实例 B") { has(AccountTags.PHONE_PAGE) && has(AccountTags.PHONE_STEP1) }
        click(AccountTags.PHONE_STEP1_SEND)
        type(AccountTags.PHONE_STEP1_CODE, CODE_OK)
        val arrivedB = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        enqueue("POST", STEP_UP) {
            arrivedB.countDown()
            releaseB.await(WAIT_S, TimeUnit.SECONDS)
            stepUpOk(LATE_T2)
        }
        val verifies = all("POST", STEP_UP).size
        tap(AccountTags.PHONE_STEP1_NEXT)
        assertTrue("B 的验证已到服务端", arrivedB.await(WAIT_S, TimeUnit.SECONDS))
        releaseA.countDown()
        idleUntil("A 成功（共享结算）") { records().isEmpty() }
        settle()
        releaseB.countDown()
        idleUntil("B 的迟到验证回包已处理") { hasText(t("error.10003")) || has(AccountTags.PHONE_STEP2) }
        settle()
        assertFalse("迟到的 T2 不进第 2 步", has(AccountTags.PHONE_STEP2))
        assertNull("T2 不写共享缓存", cached("phone_change"))
        // B 重新验证（拿到新 token）后提交：不带 T2
        type(AccountTags.PHONE_STEP1_CODE, CODE_OK)
        tap(AccountTags.PHONE_STEP1_NEXT)
        idleUntil("重新验证进第 2 步") { has(AccountTags.PHONE_STEP2) }
        assertTrue(all("POST", STEP_UP).size >= verifies + 2)
        fillStep2()
        idleUntil { enabled(AccountTags.PHONE_SUBMIT) }
        val posts = all("POST", BIND).size
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("B 提交已发出") { all("POST", BIND).size > posts }
        val sent = all("POST", BIND).last().stepUp
        assertNotNull(sent)
        assertNotEquals("提交不带已作废的 T2", LATE_T2, sent)
    }

    /**
     * R3-01 反向时序（共用 StepUpController）：注销二次验证面板的验证在途 → 先发生 bindPhone 成功的共享结算（清全部 token、推进失效世代）→ 后收到旧验证成功：
     * 不写缓存、不交付 onToken（不重放申请），面板留着提示重新验证。
     */
    @Test
    fun r3_01_aSheetVerifySuccessArrivingAfterTheTokensWereInvalidatedIsDiscarded() {
        launch()
        openDeleteFromSettings()
        checkWaiverAndSubmit()
        idleUntil("10003 → 二次验证面板（发码已有结论）") { stepUpReady() }
        type(STEP_UP_CODE, CODE_OK)
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", STEP_UP) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            stepUpOk(LATE_DELETION)
        }
        tap(STEP_UP_SUBMIT)
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        // 先清 token：别处换号成功的共享结算（任一页面、任一实例都走这一个出口）
        composeRule.runOnUiThread {
            assertTrue(account.shared.onBindPhoneSucceeded(USER_A, account.identity.value.epoch, null, null))
        }
        settle()
        release.countDown()
        idleUntil("迟到的验证成功已处理") { !has(STEP_UP_SHEET) || has("step_up_error") }
        settle()
        assertEquals("不交付 onToken：申请不重放", 1, all("POST", DELETION).size)
        assertNull("不写共享缓存", cached("account_deletion"))
        assertTrue("面板留着让用户重新验证", has(STEP_UP_SHEET))
    }

    // ---------------------------------------------------------------- R3-02

    /** R3-02：待确认【确认】得 30411、30414 → 恢复第 2 步（新号、验证码、token 保留，提交入口可用）；30414 另给【联系客服】。 */
    @Test
    fun r3_02_pendingConfirm30411And30414RestoreStep2WithTheNewPhoneCodeAndToken() {
        phonePending()
        val token = all("POST", BIND).last().stepUp
        assertNotNull(token)
        enqueueCode("POST", BIND, 30411, """{"reason":"mergeable","merge_ticket":"TEST_ONLY"}""")
        tap(AccountTags.PENDING_CONFIRM)
        idleUntil("30411 已结算") { records().isEmpty() && !has(AccountTags.PENDING_CARD) }
        settle()
        assertTrue("30411 → 恢复第 2 步", has(AccountTags.PHONE_STEP2))
        assertTrue(hasText(t("error.30411")))
        assertFalse("不回第 1 步", has(AccountTags.PHONE_STEP1))
        assertTrue("新号保留", hasText(PHONE_NEW))
        assertTrue("验证码保留、提交入口可用", enabled(AccountTags.PHONE_SUBMIT))

        // 再提交超时 → 待确认 → 【确认】得 30414
        enqueueTimeout("POST", BIND)
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("再次待确认") { has(AccountTags.PENDING_CARD) }
        assertEquals("token 保留（未重新验证）", token, all("POST", BIND).last().stepUp)
        enqueueCode("POST", BIND, 30414)
        tap(AccountTags.PENDING_CONFIRM)
        idleUntil("30414 → 恢复第 2 步") { has(AccountTags.PHONE_STEP2) && !has(AccountTags.PENDING_CARD) }
        assertTrue(hasText(t("error.30414")))
        assertTrue("【联系客服】", has(AccountTags.PHONE_CONTACT_CS))
        assertTrue("新号保留", hasText(PHONE_NEW))
        assertTrue(enabled(AccountTags.PHONE_SUBMIT))
    }

    /** R3-02：待确认【放弃】得 completed 且原结果是 30414 → 恢复第 2 步（新号保留、【联系客服】），不回第 1 步。 */
    @Test
    fun r3_02_pendingAbandonCompletedWithAnOriginal3xxxxRestoresStep2() {
        val key = phonePending()
        serverState.results[key] = 30414 to "null"
        tap(AccountTags.PENDING_ABANDON)
        idleUntil("放弃二次确认") { has(AccountTags.ABANDON_OK) }
        tap(AccountTags.ABANDON_OK)
        idleUntil("completed 原 30414 已结算") { records().isEmpty() && !has(AccountTags.PENDING_CARD) }
        settle()
        assertTrue("completed 原 30414 → 恢复第 2 步", has(AccountTags.PHONE_STEP2))
        assertFalse("不回第 1 步", has(AccountTags.PHONE_STEP1))
        assertTrue(hasText(t("error.30414")))
        assertTrue("【联系客服】", has(AccountTags.PHONE_CONTACT_CS))
        assertTrue("新号保留", hasText(PHONE_NEW))
    }

    // ---------------------------------------------------------------- R3-03

    /**
     * R3-03：deletion_only 注销页有未决记录 → 版本限制解除，网络层发起受限会话的屏障刷新（挂住）→ 点【确认】，请求等待刷新结束 → 刷新得到同账号、同登录世代的
     * full：旧页面的确认 0 次发出，未决记录保留（结果未知），等新实例里的用户操作。
     */
    @Test
    fun r3_03_aDeletionOnlyConfirmWaitingForTheRefreshIsNotSentAfterASameEpochFullRefresh() {
        seedInFlight("requestDeletion", KEY_R3_03, "", "", createdAt = now - DAY_MS)
        refreshScope = "deletion_only"
        launch(scope = "deletion_only", forced = true)
        idleUntil { top() == FORCE_UPDATE && has(FORCE_UPDATE_DELETE_TAG) }
        idleUntil("冷启动的屏障刷新已结束") { all("POST", REFRESH).isNotEmpty() }
        click(FORCE_UPDATE_DELETE_TAG)
        idleUntil("受限注销页的待确认卡") { top() == DELETE_ACCOUNT && has(AccountTags.PENDING_CARD) && enabled(AccountTags.PENDING_CONFIRM) }
        assertEquals("deletion_only", account.identity.value.scope)
        val epoch = tokens.loginEpoch
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", REFRESH) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope(
                """{"access_token":"access-$USER_A","access_expires_at":"2099-01-01T00:00:00+08:00","refresh_token":"refresh-$USER_A",""" +
                    """"refresh_expires_at":"2099-01-01T00:00:00+08:00","session_scope":"full"}""",
            )
        }
        try {
            // 版本限制解除：网络层立即发起受限会话的屏障刷新，其余带令牌的请求等它结束
            networkUpdate = UpdateRaw.SUPPORTED
            Thread { api.onUpdateStateChanged(UpdateRaw.SUPPORTED) }.start()
            assertTrue("屏障刷新已发出", arrived.await(WAIT_S, TimeUnit.SECONDS))
            tap(AccountTags.PENDING_CONFIRM)
            settle()
            assertTrue("确认在等刷新", all("POST", DELETION).isEmpty())
        } finally {
            release.countDown()
        }
        idleUntil("刷新为同世代的 full") { account.identity.value.scope == "full" }
        idleUntil("旧确认已结束、记录退回结果未知") { records().singleOrNull()?.stateRaw == PendingKeyStore.UNKNOWN }
        settle()
        settle()
        assertEquals("同一登录世代", epoch, tokens.loginEpoch)
        assertTrue("旧页面的确认 0 次发出 ${all()}", all("POST", DELETION).isEmpty())
        assertEquals("未决记录保留", KEY_R3_03, records().single().key)
        idleUntil("新实例仍显示待确认卡") { has(AccountTags.PENDING_CARD) }
    }

    // ---------------------------------------------------------------- R3-04

    /** R3-04：已有注销结果未知记录重进注销页、getDeletion 挂住 → 待确认卡与两个按钮立即可见可用（不等进度读取），【确认】用原键发出。 */
    @Test
    fun r3_04_anExistingUnknownRecordShowsAUsablePendingCardWhileGetDeletionIsStillLoading() {
        seedInFlight("requestDeletion", KEY_R3_04, "", "", createdAt = now - DAY_MS)
        serverState.requireStepUp = false
        val hold = CountDownLatch(1)
        repeat(HELD_READS) {
            enqueue("GET", DELETION) {
                hold.await(WAIT_S, TimeUnit.SECONDS)
                envelope("null")
            }
        }
        try {
            launch()
            open(SETTINGS)
            idleUntil("设置页注销行") { has(DELETE_ROW) }
            click(DELETE_ROW)
            idleUntil("注销页") { top() == DELETE_ACCOUNT && has(AccountTags.DELETE_PAGE) }
            idleUntil("进度读取挂着（加载态）") { has(AccountTags.DELETE_LOADING) }
            settle()
            assertTrue("进度仍在读取", has(AccountTags.DELETE_LOADING) && all("GET", DELETION).isNotEmpty())
            assertTrue("待确认卡立即可见（不等 getDeletion）", has(AccountTags.PENDING_CARD))
            assertTrue("【确认】可用", enabled(AccountTags.PENDING_CONFIRM))
            assertTrue("【放弃】可用", enabled(AccountTags.PENDING_ABANDON))
            click(AccountTags.PENDING_CONFIRM)
            idleUntil("确认用原键发出") { all("POST", DELETION).any { it.key == KEY_R3_04 } }
        } finally {
            hold.countDown()
        }
    }

    private companion object {
        const val LATE_T2 = "tok-phone_change-late-T2"
        const val LATE_DELETION = "tok-account_deletion-late"
        const val KEY_R3_03 = "key-r3-03-from-last-process"
        const val KEY_R3_04 = "key-r3-04-from-last-process"
        const val REFRESH = "/v1/auth/refresh"
        const val HELD_READS = 4
    }
}
