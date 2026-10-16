@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.login.BindPhoneTags
import com.couli.feature.me.AccountTags
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-0717（Android 跟进合集六）在真实 AppShell + AppLogin + AppMe + AppAccount 上的流程（[AccountHarness]，与 AppModule 同一套接线）：
 * - F-X-13 / APP-05b 共性 34：发码在途时编辑号码（清空再粘回、等价格式），共享倒计时 / 限流仍按当前规范化号码重算启动，只丢弃旧表单的反馈；
 *   42901 到期自动恢复。换号第 2 步（本任务改：新号修订号）与绑手机页（已满足：每秒按当前号码重算，换号作废旧结果）各一组。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class AccountFollowupAnd0717Test : AccountHarness() {
    /** 一次挂住的应答：请求到达后等测试放行再答 [answer]。 */
    private inner class Held(
        private val answer: () -> MockResponse,
    ) {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun respond(): MockResponse {
            arrived.countDown()
            release.await(com.couli.app.FlowWait.CEILING_MS, TimeUnit.MILLISECONDS)
            return answer()
        }

        fun awaitArrived() = assertTrue("挂住的请求已到达假服务", arrived.await(com.couli.app.FlowWait.CEILING_MS, TimeUnit.MILLISECONDS))
    }

    /** 号码类字段错误（20001 phone_invalid，fields 指向 phone）。 */
    private fun phoneInvalid() = envelope("""{"fields":["phone"],"reason":"phone_invalid"}""", 20001)

    private fun rateLimited(seconds: Int) = envelope("null", 42901, "trace-429001").setHeader("Retry-After", seconds.toString())

    // ---------------------------------------------------------------- F-X-13：换号第 2 步

    /** 进第 2 步、输入 [typed]，挂住第 2 步发码（应答 [answer]），点【获取验证码】，等请求到达；随后测试编辑号码再放行。 */
    private fun step2SendHeld(
        typed: String,
        answer: () -> MockResponse,
    ): Held {
        launch()
        openPhoneChange()
        passStep1()
        type(AccountTags.PHONE_NEW, typed)
        val held = Held(answer)
        enqueue("POST", SMS) { held.respond() }
        click(AccountTags.PHONE_STEP2_SEND)
        held.awaitArrived()
        return held
    }

    /** 放行挂住的发码，等它连同页面续体结算（三个会话级作用域里放行前起的协程都结束）。 */
    private fun releaseAndSettle(
        held: Held,
        before: WorkSnapshot,
    ) {
        held.release.countDown()
        workSince(before, "第 2 步发码结算")
    }

    /** F-X-13 ①：A → 空 → A（清空再粘回，表单换代）期间发码成功：旧表单的反馈丢弃，但倒计时按当前规范化号码 A 启动。 */
    @Test
    fun fuand0717_x13_phoneChangeClearAndPasteBackWhileSendingStartsTheTimerForTheCurrentPhone() {
        val held = step2SendHeld(PHONE_NEW) { envelope("""{"resend_after_sec":60,"expires_in_sec":300}""") }
        val before = workSnapshot()
        type(AccountTags.PHONE_NEW, "")
        type(AccountTags.PHONE_NEW, PHONE_NEW)
        releaseAndSettle(held, before)
        idleUntil("倒计时按当前号码启动") { hasText(STEP2_COUNTDOWN_TEXT) && !enabled(AccountTags.PHONE_STEP2_SEND) }
        assertEquals("只发了一次第 2 步发码", 2, all("POST", SMS).size)
    }

    /** F-X-13 ②：A → 空 → A 期间旧结果是 20001（字段错误）：不落到新表单；等价格式（规范化相同）对照照常显示。 */
    @Test
    fun fuand0717_x13_phoneChangeOldFieldErrorIsDroppedAfterClearAndPasteBack() {
        val held = step2SendHeld(PHONE_NEW) { envelope("null", 20001) }
        val before = workSnapshot()
        type(AccountTags.PHONE_NEW, "")
        type(AccountTags.PHONE_NEW, PHONE_NEW)
        releaseAndSettle(held, before)
        idleUntil("发码已结束（按钮恢复可点）") { enabled(AccountTags.PHONE_STEP2_SEND) }
        assertFalse("旧表单的字段错误不落到新表单", has(AccountTags.PHONE_FIELD_ERROR))
        assertTrue("输入保留", hasText(PHONE_NEW))
    }

    @Test
    fun fuand0717_x13_control_phoneChangeEquivalentFormatKeepsTheFeedback() {
        val held = step2SendHeld(PHONE_NEW) { envelope("null", 20001) }
        val before = workSnapshot()
        // 等价写法（加空格）：规范化号码不变，表单不换代
        type(AccountTags.PHONE_NEW, "139 0000 1234")
        releaseAndSettle(held, before)
        idleUntil("等价写法照常显示字段错误") { has(AccountTags.PHONE_FIELD_ERROR) }
    }

    /** F-X-13 ③：A → 空 → A 期间旧结果是 42901：限流计时按当前号码生效（按钮置灰，旧提示丢弃），Retry-After 到期自动恢复可点。 */
    @Test
    fun fuand0717_x13_phoneChangeRateLimitAfterClearAndPasteBackLocksAndAutoRecovers() {
        val held = step2SendHeld(PHONE_NEW) { rateLimited(30) }
        val before = workSnapshot()
        type(AccountTags.PHONE_NEW, "")
        type(AccountTags.PHONE_NEW, PHONE_NEW)
        releaseAndSettle(held, before)
        idleUntil("限流按当前号码生效") { hasText(STEP2_COUNTDOWN_TEXT) && !enabled(AccountTags.PHONE_STEP2_SEND) }
        assertFalse("旧表单的限流提示丢弃", has(AccountTags.PHONE_ERROR))
        now += 31_000L
        // 页面每秒重算的计时在 Compose 的时钟上
        composeRule.mainClock.advanceTimeBy(31_000L)
        idleUntil("Retry-After 到期自动恢复可点") { enabled(AccountTags.PHONE_STEP2_SEND) && !hasText(STEP2_COUNTDOWN_TEXT) }
    }

    // ---------------------------------------------------------------- F-X-13：绑手机页（已满足，补证据）

    private fun openBindPhone() {
        serverState.phoneBound = false
        launch()
        open(SETTINGS)
        idleUntil { has(PHONE_ROW) && enabled(PHONE_ROW) }
        click(PHONE_ROW)
        idleUntil("BindPhone") { has(BindPhoneTags.PAGE) }
    }

    /** 绑手机页：输入 [typed]、挂住发码、点【获取验证码】并等请求到达。 */
    private fun bindSendHeld(
        typed: String,
        answer: () -> MockResponse,
    ): Held {
        openBindPhone()
        type(BindPhoneTags.PHONE, typed)
        val held = Held(answer)
        enqueue("POST", SMS) { held.respond() }
        tap(BindPhoneTags.GET_CODE)
        held.awaitArrived()
        return held
    }

    /** 放行并等页面结算（resultSeq 推进）。 */
    private fun releaseBind(held: Held) {
        val model = bindPhonePage()
        val seq = model.state.value.resultSeq
        held.release.countDown()
        idleUntil("绑手机发码结算") { model.state.value.resultSeq > seq }
    }

    @Test
    fun fuand0717_x13_bindPhoneClearAndPasteBackWhileSendingStartsTheTimerAndDropsTheOldError() {
        val held = bindSendHeld(PHONE_NEW) { envelope("""{"resend_after_sec":60,"expires_in_sec":300}""") }
        type(BindPhoneTags.PHONE, "")
        type(BindPhoneTags.PHONE, PHONE_NEW)
        releaseBind(held)
        val model = bindPhonePage()
        idleUntil("倒计时按当前号码启动") { model.state.value.resendSec > 0 }
        assertFalse(
            "旧结果不播报发送对象",
            model.state.value.announce
                ?.contains("1234") == true,
        )
        // 旧结果是字段错误：换代后不落地
        val held2 = Held { phoneInvalid() }
        type(BindPhoneTags.PHONE, PHONE_OLD)
        enqueue("POST", SMS) { held2.respond() }
        tap(BindPhoneTags.GET_CODE)
        held2.awaitArrived()
        type(BindPhoneTags.PHONE, "")
        type(BindPhoneTags.PHONE, PHONE_OLD)
        releaseBind(held2)
        assertEquals("旧字段错误不落到新表单", null, model.state.value.phoneError)
    }

    @Test
    fun fuand0717_x13_control_bindPhoneEquivalentFormatKeepsTheFeedback() {
        val held = bindSendHeld(PHONE_NEW) { phoneInvalid() }
        type(BindPhoneTags.PHONE, "139 0000 1234")
        releaseBind(held)
        idleUntil("等价写法照常显示字段错误") { bindPhonePage().state.value.phoneError != null }
    }

    @Test
    fun fuand0717_x13_bindPhoneRateLimitAfterClearAndPasteBackLocksAndAutoRecovers() {
        val held = bindSendHeld(PHONE_NEW) { rateLimited(2) }
        type(BindPhoneTags.PHONE, "")
        type(BindPhoneTags.PHONE, PHONE_NEW)
        releaseBind(held)
        val model = bindPhonePage()
        idleUntil("限流按当前号码生效") { model.state.value.sendLimitedSec > 0 && !enabled(BindPhoneTags.GET_CODE) }
        // 绑手机页的发码截止按真实时钟（LoginService）：等 Retry-After 到期，每秒刷新自动恢复
        idleUntil("Retry-After 到期自动恢复可点") { model.state.value.sendLimitedSec == 0 && enabled(BindPhoneTags.GET_CODE) }
    }

    // ---------------------------------------------------------------- F-X-15：held 原体重发的字段反馈归属

    private val edited = "13900009999"

    /** 换号第 2 步：A 提交得 50001（键 held，原体为 A）→ 输入框改成 [edit]，再点【提交】（网络层按原键原体重发 A），应答 [code] / [data]；等它结算。 */
    private fun phoneChangeHeldResend(
        edit: String,
        code: Int,
        data: String = "null",
    ) {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        enqueueCode("POST", BIND, 50001)
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("第一次提交 50001") { has(AccountTags.PHONE_ERROR) }
        assertEquals(com.couli.core.network.PendingKeyStore.HELD, records().single().stateRaw)
        type(AccountTags.PHONE_NEW, edit)
        // 重发的应答用另一条 trace（与第一次 50001 的 123456 区分：对照用例只认这一次的提示）
        enqueueCode("POST", BIND, code, data, trace = RESEND_TRACE)
        val before = workSnapshot()
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("held 重发已发出") { all("POST", BIND).size == 2 }
        workSince(before, "held 重发结算")
        val posts = all("POST", BIND)
        assertEquals("原键", posts[0].key, posts[1].key)
        assertEquals("原体（号码 A）", posts[0].body, posts[1].body)
    }

    /** F-X-15：改号后 held 重发的号码 / 验证码类反馈（20001 / 20002 / 30411 / 44001）不落到新号表单，输入保留。 */
    @Test
    fun fuand0717_x15_phoneChangeHeldResendAfterEditingDropsPhoneAndCodeFeedback() {
        phoneChangeHeldResend(edited, 20001, """{"fields":["phone"],"reason":"phone_invalid"}""")
        assertFalse("20001 不落到新号", has(AccountTags.PHONE_FIELD_ERROR))
        assertFalse(has(AccountTags.PHONE_ERROR))
        assertTrue("新号保留", hasText(edited))
    }

    @Test
    fun fuand0717_x15_phoneChangeHeldResendAfterEditingDropsTheSameCode20002() {
        phoneChangeHeldResend(edited, 20002)
        assertFalse("改号但验证码不变：20002 不落到新号表单", has(AccountTags.PHONE_FIELD_ERROR))
        assertTrue("验证码保留", hasText(CODE_OK))
        assertTrue(hasText(edited))
    }

    @Test
    fun fuand0717_x15_phoneChangeHeldResendAfterEditingDrops30411And44001() {
        phoneChangeHeldResend(edited, 30411, """{"reason":"mergeable","merge_ticket":"TEST_ONLY"}""")
        assertFalse("30411 不落到新号表单", has(AccountTags.PHONE_ERROR))
        assertFalse(hasText(t("error.30411")))
        assertTrue(hasText(edited))
    }

    @Test
    fun fuand0717_x15_phoneChangeHeldResendAfterEditingDrops44001() {
        phoneChangeHeldResend(edited, 44001, """{"risk_msg_code":"test_unmapped"}""")
        assertFalse("44001 风控弹窗不对新号弹出", has(AccountTags.PHONE_RISK_APPEAL))
        assertTrue("验证码保留", hasText(CODE_OK))
    }

    /** 共性 36 对照：改号后 held 重发得 50001 / 50401（非字段结局）照常显示提示与 trace6。 */
    @Test
    fun fuand0717_x15_control_phoneChangeHeldResend50001StillShowsTheErrorWithTrace6() {
        phoneChangeHeldResend(edited, 50001)
        idleUntil("50001 照常提示（本次 trace6）") { has(AccountTags.PHONE_ERROR) && hasText(RESEND_TRACE6) }
    }

    @Test
    fun fuand0717_x15_control_phoneChangeHeldResend50401StillShowsTheErrorWithTrace6() {
        phoneChangeHeldResend(edited, 50401)
        idleUntil("50401 照常提示（本次 trace6）") { has(AccountTags.PHONE_ERROR) && hasText(RESEND_TRACE6) }
    }

    /** 对照：等价写法（规范化后仍是 A）重发得 20002：照常落到表单。 */
    @Test
    fun fuand0717_x15_control_phoneChangeHeldResendWithAnEquivalentFormatKeepsTheCodeError() {
        phoneChangeHeldResend("139 0000 1234", 20002)
        idleUntil("等价写法照常显示验证码错误") { has(AccountTags.PHONE_FIELD_ERROR) }
    }

    /** 绑手机页：A 绑定得 50001（held）→ 号码改成 [edit]、再提交（原键原体重发 A），应答 [code] / [data]；等页面结算。返回页面 ViewModel。 */
    private fun bindHeldResend(
        edit: String,
        code: Int,
        data: String = "null",
    ): com.couli.feature.login.BindPhoneViewModel {
        openBindPhone()
        type(BindPhoneTags.PHONE, PHONE_NEW)
        sendBindCode()
        type(BindPhoneTags.CODE, CODE_OK)
        val model = bindPhonePage()
        enqueueCode("POST", BIND, 50001)
        val first = model.state.value.resultSeq
        submitBindIfNotStarted(requireNoRequest = false)
        idleUntil("第一次绑定 50001 结算") { model.state.value.resultSeq > first && all("POST", BIND).size == 1 }
        assertEquals(com.couli.core.network.PendingKeyStore.HELD, records().single().stateRaw)
        type(BindPhoneTags.PHONE, edit)
        enqueueCode("POST", BIND, code, data, trace = RESEND_TRACE)
        val seq = model.state.value.resultSeq
        tap(BindPhoneTags.SUBMIT)
        idleUntil("held 重发结算") { model.state.value.resultSeq > seq && all("POST", BIND).size == 2 }
        val posts = all("POST", BIND)
        assertEquals("原键", posts[0].key, posts[1].key)
        assertEquals("原体（号码 A）", posts[0].body, posts[1].body)
        return model
    }

    @Test
    fun fuand0717_x15_bindPhoneHeldResendAfterEditingDropsPhoneFieldErrors() {
        val model = bindHeldResend(edited, 20001, """{"fields":["phone"],"reason":"phone_invalid"}""")
        assertNull("20001 不落到新号", model.state.value.phoneError)
        assertEquals(edited, model.state.value.phone)
    }

    @Test
    fun fuand0717_x15_bindPhoneHeldResendAfterEditingDropsTheSameCode20003() {
        val model = bindHeldResend(edited, 20003, """{"fields":["code"]}""")
        assertNull("改号但验证码不变：20003 不落到新号表单", model.state.value.codeError)
        assertEquals("验证码保留", CODE_OK, model.state.value.code)
    }

    @Test
    fun fuand0717_x15_bindPhoneHeldResendAfterEditingDropsTheMergeConfirm() {
        val model = bindHeldResend(edited, 30411, """{"reason":"mergeable","merge_ticket":"TEST_ONLY"}""")
        assertNull("30411 并号框不对新号弹出", model.state.value.dialog)
    }

    @Test
    fun fuand0717_x15_control_bindPhoneHeldResend50001StillShowsTheErrorWithTrace6() {
        bindHeldResend(edited, 50001)
        idleUntil("50001 照常提示（带本次 trace6）") { announcedText(RESEND_TRACE6) }
    }

    @Test
    fun fuand0717_x15_control_bindPhoneHeldResend50401StillShowsTheErrorWithTrace6() {
        bindHeldResend(edited, 50401)
        idleUntil("50401 照常提示（带本次 trace6）") { announcedText(RESEND_TRACE6) }
    }

    @Test
    fun fuand0717_x15_control_bindPhoneHeldResendWithAnEquivalentFormatKeepsTheCodeError() {
        val model = bindHeldResend("139 0000 1234", 20003, """{"fields":["code"]}""")
        idleUntil("等价写法照常显示验证码错误") { model.state.value.codeError != null }
    }

    private companion object {
        /** held 重发那一次应答的 trace（第一次 50001 用缺省的 trace-123456）。 */
        const val RESEND_TRACE = "trace-654321"
        const val RESEND_TRACE6 = "654321"
    }
}
