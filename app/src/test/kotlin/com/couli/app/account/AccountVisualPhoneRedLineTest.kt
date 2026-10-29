@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import android.view.MotionEvent
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.me.touch
import com.couli.feature.login.BindPhonePendingTags
import com.couli.feature.login.BindPhoneTags
import com.couli.feature.me.AccountTags
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * APP-13v 敏感写页红线逐条（brief §0.1 / §5，三端同名 redLine1…10；b 段 = 换号 / 绑号：⑦ 换号第 1 步、⑧ 换号第 2 步、⑨ 绑号，另补换号 / 绑号的
 * ③ 确认、④ 放弃、⑥ 联系客服与 ⑩ 显示 / 可点集合）。全部从真实页面的真实触摸出发计数；「旧回调」取自真实节点上已注册的点击动作，
 * 在同一主线程任务里先换身份（或先返回第 1 步）再调（AccountVisualHarness.invokeStaleAfterSwitch，APP-12w-c 测试 v2 T4）。
 * 底版上应全绿（行为原样）；实现若改动发起条件、次数、顺序、去向或身份票据即红。G-D04：输满 6 位零请求（不接输满自动验证 / 提交 / 绑定）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1127dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions", "LargeClass")
class AccountVisualPhoneRedLineTest : AccountVisualPhoneHarness() {
    // ---------------------------------------------------------------- ⑦ 换号第 1 步

    @Test fun redLine7_enteringPhoneChangeSendsNoWriteAndNoCode() {
        launch()
        open(SETTINGS)
        idleUntil("设置页手机号行") { has(PHONE_ROW) && enabled(PHONE_ROW) }
        settle()
        val before = all().size
        click(PHONE_ROW)
        idleUntil("换号子页") { has(AccountTags.PHONE_PAGE) }
        settle()
        val after = all().drop(before)
        assertTrue("进入换号零写请求、零发码（brief §4 第 5 条）：$after", after.none { it.method == "POST" })
    }

    @Test fun redLine7_typingSixDigitsNeverVerifies() {
        enterStep1(sent = true)
        type(AccountTags.PHONE_STEP1_CODE, CODE_OK)
        composeRule.mainClock.advanceTimeBy(1_000L)
        settle()
        assertEquals("G-D04 输满 6 位不自动验证", 0, all("POST", STEP_UP).size)
        assertTrue("【下一步】可点（原样）", enabled(AccountTags.PHONE_STEP1_NEXT))
    }

    @Test fun redLine7_nextVerifiesOnceAndOpensStep2() {
        enterStep1(sent = true)
        type(AccountTags.PHONE_STEP1_CODE, CODE_OK)
        tap(AccountTags.PHONE_STEP1_NEXT)
        idleUntil("第 2 步") { has(AccountTags.PHONE_STEP2) }
        val verify = all("POST", STEP_UP)
        assertEquals("【下一步】一次 = 验证一次", 1, verify.size)
        assertEquals("phone_change", (body(verify.single())["action"] as JsonPrimitive).content)
        assertEquals(CODE_OK, (body(verify.single())["code"] as JsonPrimitive).content)
        assertEquals("第 1 步发码恰一次（step_up）", 1, all("POST", SMS).size)
    }

    @Test fun redLine7_sendIsInertDuringTheCountdown() {
        enterStep1(sent = true)
        assertFalse("倒计时内不可点（原样）", enabled(AccountTags.PHONE_STEP1_SEND))
        click(AccountTags.PHONE_STEP1_SEND)
        val touch = node(AccountTags.PHONE_STEP1_SEND).touch()
        tapAt(touch.center.x, touch.center.y)
        assertEquals("倒计时内 0 次发码", 1, all("POST", SMS).size)
    }

    @Test fun redLine7_staleSendAfterIdentitySwitchSendsNothing() {
        enterStep1()
        invokeStaleAfterSwitch(AccountTags.PHONE_STEP1_SEND)
        assertEquals("换身份后旧【获取验证码】0 次", 0, all("POST", SMS).size)
    }

    @Test fun redLine7_staleNextAfterIdentitySwitchVerifiesNothing() {
        enterStep1(sent = true)
        type(AccountTags.PHONE_STEP1_CODE, CODE_OK)
        invokeStaleAfterSwitch(AccountTags.PHONE_STEP1_NEXT)
        assertEquals("换身份后旧【下一步】0 次验证", 0, all("POST", STEP_UP).size)
    }

    // ---------------------------------------------------------------- ⑧ 换号第 2 步

    @Test fun redLine8_typingSixDigitsNeverSubmits() {
        enterStep2(sent = true)
        composeRule.mainClock.advanceTimeBy(1_000L)
        settle()
        assertEquals("G-D04 输满 6 位不自动提交", 0, all("POST", BIND).size)
        assertTrue("【提交】可点（原样）", enabled(AccountTags.PHONE_SUBMIT))
    }

    @Test fun redLine8_submitBindsOnceWithTheStepTicketAndAKey() {
        enterStep2(sent = true)
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("完成页") { has(AccountTags.PHONE_DONE) }
        val bind = all("POST", BIND)
        assertEquals("【提交】一次 = 提交一次", 1, bind.size)
        assertNotNull("带第 1 步的二次验证票据", bind.single().stepUp)
        assertNotNull("幂等键非空", bind.single().key)
        assertEquals(PHONE_NEW, (body(bind.single())["phone"] as JsonPrimitive).content)
    }

    @Test fun redLine8_eachSubmitUsesANewKey() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2(code = phone("wrongCode"))
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("20002") { has(AccountTags.PHONE_FIELD_ERROR) }
        type(AccountTags.PHONE_STEP2_CODE, CODE_OK)
        idleUntil("【提交】可点") { enabled(AccountTags.PHONE_SUBMIT) }
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("完成页") { has(AccountTags.PHONE_DONE) }
        val keys = all("POST", BIND).map { it.key }
        assertEquals("两次提交", 2, keys.size)
        assertNotEquals("每次提交新键（上一键已因 20002 结束）", keys[0], keys[1])
    }

    @Test fun redLine8_backReturnsToStep1WithoutSubmitting() {
        enterStep2(sent = true)
        phoneBack().performClick()
        settle()
        idleUntil("回第 1 步") { has(AccountTags.PHONE_STEP1) }
        assertEquals("返回 0 次提交", 0, all("POST", BIND).size)
        assertFalse("第 2 步瞬时状态清空（新号）", hasText(PHONE_NEW))
    }

    @Test fun redLine8_staleSubmitOfTheReplacedFormDoesNothing() {
        enterStep2(sent = true)
        val back = clickAction(phoneBack())
        val submit = clickAction(node(AccountTags.PHONE_SUBMIT))
        composeRule.runOnUiThread {
            back()
            submit()
        }
        settle()
        assertEquals("返回第 1 步后旧表单世代的【提交】0 次", 0, all("POST", BIND).size)
        idleUntil("回第 1 步") { has(AccountTags.PHONE_STEP1) }
    }

    @Test fun redLine8_staleSubmitAfterIdentitySwitchDoesNothing() {
        enterStep2(sent = true)
        invokeStaleAfterSwitch(AccountTags.PHONE_SUBMIT)
        assertEquals("换身份后旧【提交】0 次", 0, all("POST", BIND).size)
    }

    @Test fun redLine8_stepUpOnSubmitReplaysTheOriginalKeyAndBodyOnce() {
        enterStepUpOnSubmit()
        assertEquals("首发得 10003", 1, all("POST", BIND).size)
        passStepUpSheet()
        idleUntil("完成页") { has(AccountTags.PHONE_DONE) }
        val posts = all("POST", BIND)
        assertEquals("验证后原键重放恰一次", 2, posts.size)
        assertEquals("同一个键", posts[0].key, posts[1].key)
        assertEquals("原体", posts[0].body, posts[1].body)
    }

    @Test fun redLine8_riskDialogOkDoesNothingAndAppealOpensServiceOnce() {
        enterRisk44001()
        val cs = csOpens()
        tap(AccountTags.PHONE_RISK_OK)
        assertFalse("【知道了】关闭", has(AccountTags.PHONE_RISK_OK))
        assertEquals("【知道了】0 次客服", cs, csOpens())
        assertTrue("新号保留、留在第 2 步（原样）", hasText(PHONE_NEW) && has(AccountTags.PHONE_STEP2))
        type(AccountTags.PHONE_STEP2_CODE, CODE_OK)
        enqueueCode("POST", BIND, 44001, """{"risk_msg_code":"test_unmapped"}""")
        idleUntil("【提交】可点") { enabled(AccountTags.PHONE_SUBMIT) }
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("44001 再弹") { has(AccountTags.PHONE_RISK_APPEAL) }
        tap(AccountTags.PHONE_RISK_APPEAL)
        assertEquals("【联系客服申诉】恰一次", cs + 1, csOpens())
        assertEquals("提交共两次（每次一个点击）", 2, all("POST", BIND).size)
    }

    @Test fun redLine8_riskDialogScrimDoesNotCloseOrAct() {
        enterRisk44001()
        val cs = csOpens()
        touchOutsideDialog()
        assertTrue("点遮罩不关闭（原样）", has(AccountTags.PHONE_RISK_OK))
        assertEquals("点遮罩 0 次客服", cs, csOpens())
    }

    // ---------------------------------------------------------------- ⑨ 绑号

    @Test fun redLine9_typingSixDigitsNeverBinds() {
        enterBind(sent = true)
        type(BindPhoneTags.CODE, CODE_OK)
        composeRule.mainClock.advanceTimeBy(1_000L)
        settle()
        assertEquals("G-D04 输满不自动绑定", 0, all("POST", BIND).size)
        assertTrue("【绑定】可点（原样）", enabled(BindPhoneTags.SUBMIT))
    }

    @Test fun redLine9_bindOnceWithAKeyAndWithoutAStepTicket() {
        enterBind(sent = true)
        type(BindPhoneTags.CODE, CODE_OK)
        idleUntil("【绑定】可点") { enabled(BindPhoneTags.SUBMIT) }
        tap(BindPhoneTags.SUBMIT)
        idleUntil("绑定已发") { all("POST", BIND).isNotEmpty() }
        settle()
        val bind = all("POST", BIND)
        assertEquals("【绑定】一次 = 绑定一次", 1, bind.size)
        assertNotNull("幂等键", bind.single().key)
        assertEquals(phone("bind"), (body(bind.single())["phone"] as JsonPrimitive).content)
        assertEquals("发码恰一次（bind）", 1, all("POST", SMS).size)
    }

    @Test fun redLine9_getCodeIsInertDuringTheCountdown() {
        enterBind(sent = true)
        assertFalse("倒计时内不可点", enabled(BindPhoneTags.GET_CODE))
        tap(BindPhoneTags.GET_CODE)
        assertEquals("倒计时内 0 次发码", 1, all("POST", SMS).size)
    }

    @Test fun redLine9_mergeConfirmSendsNoMergeRequest() {
        enterBindMerge()
        val before = all().size
        dialogButtonOf(lt("bind_phone.merge.ok")).performClick()
        settle()
        assertTrue("【并入】不发并号请求（锁定契约现状）", all("POST", MERGE).isEmpty())
        assertTrue("零写请求", all().drop(before).none { it.method == "POST" })
        idleUntil("给「暂不可用」") { hasText(lt("bind_phone.merge.unavailable")) }
    }

    @Test fun redLine9_mergeCancelSendsNothingAndKeepsTheInput() {
        enterBindMerge()
        val before = all().size
        dialogButtonOf(lt("bind_phone.merge.cancel")).performClick()
        settle()
        assertTrue("【取消】零请求", all().drop(before).none { it.method == "POST" })
        assertTrue("号码保留", hasText(phone("bind")))
    }

    @Test fun redLine9_staleBindAfterIdentitySwitchDoesNothing() {
        enterBind(sent = true)
        type(BindPhoneTags.CODE, CODE_OK)
        idleUntil("【绑定】可点") { enabled(BindPhoneTags.SUBMIT) }
        invokeStaleAfterSwitch(BindPhoneTags.SUBMIT)
        assertEquals("换身份后旧【绑定】0 次", 0, all("POST", BIND).size)
    }

    @Test fun redLine9_riskAppealOpensServiceOnce() {
        val calls = mutableListOf<String>()
        enterBind44001()
        composeRule.runOnUiThread { login.customerService = { from -> calls += from } }
        dialogButtonOf(lt("cs.appeal_entry")).performClick()
        settle()
        assertEquals("【联系客服申诉】恰一次（统一客服出口）", 1, calls.size)
    }

    // ---------------------------------------------------------------- ③ ④ 换号待确认

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun redLine3_phoneChangeConfirmResendsTheOriginalKeyAndBodyOnce() {
        val key = enterPhonePending()
        tap(AccountTags.PENDING_CONFIRM)
        idleUntil("完成页") { has(AccountTags.PHONE_DONE) }
        val posts = all("POST", BIND)
        assertEquals("确认一次 = 原键原体重发一次", 1, posts.size)
        assertEquals("原键", key, posts.single().key)
        assertEquals("原体（新号）", PHONE_NEW, (body(posts.single())["phone"] as JsonPrimitive).content)
        assertTrue("完成页只显示本机脱敏新号", hasText(AccountVisualFixtures.pendingSummary("phone_change")))
    }

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun redLine3_phoneChangeSecondTapWhileInFlightSendsNothing() {
        enterPhonePending()
        val (arrived, release) = hold("POST", BIND) { envelope("{}") }
        try {
            tap(AccountTags.PENDING_CONFIRM)
            assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
            settle()
            tap(AccountTags.PENDING_CONFIRM)
            tap(AccountTags.PENDING_ABANDON)
            assertEquals("在途再点 0 次", 1, all("POST", BIND).size)
            assertTrue("在途不放弃", all("POST", ABANDON).isEmpty())
        } finally {
            release.countDown()
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h1071dp-xhdpi")
    fun redLine3_phoneChangeConfirm20903ReturnsToStep1() {
        val key = enterPhonePending()
        serverState.results[key] = 20903 to "null"
        tap(AccountTags.PENDING_CONFIRM)
        idleUntil("20903 回第 1 步重新验证") { has(AccountTags.PHONE_STEP1) }
        assertEquals("恰一次", 1, all("POST", BIND).size)
        assertFalse("待确认结束", has(AccountTags.PENDING_CARD))
    }

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun redLine3_phoneChangeStaleConfirmAfterIdentitySwitchDoesNothing() {
        enterPhonePending()
        invokeStaleAfterSwitch(AccountTags.PENDING_CONFIRM)
        assertEquals("换身份后旧【确认】0 次", 0, all("POST", BIND).size)
    }

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun redLine4_phoneChangeThinkAgainBackAndScrimAreZeroActions() {
        enterPhonePending()
        openAbandon(AccountTags.PENDING_ABANDON, AccountTags.ABANDON_OK)
        tap(AccountTags.ABANDON_CANCEL)
        assertFalse("【再想想】关闭", has(AccountTags.ABANDON_OK))
        openAbandon(AccountTags.PENDING_ABANDON, AccountTags.ABANDON_OK)
        touchOutsideDialog()
        assertTrue("点遮罩不关闭", has(AccountTags.ABANDON_OK))
        @Suppress("DEPRECATION")
        composeRule.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        settle()
        assertFalse("系统返回 = 再想想", has(AccountTags.ABANDON_OK))
        assertTrue("三种方式 0 次作废", all("POST", ABANDON).isEmpty())
        assertTrue("仍待确认", has(AccountTags.PENDING_CARD))
    }

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun redLine4_phoneChangeOkAbandonsOnceAndTheStaleOkDoesNothing() {
        val key = enterPhonePending()
        openAbandon(AccountTags.PENDING_ABANDON, AccountTags.ABANDON_OK)
        val stale = clickAction(node(AccountTags.ABANDON_OK))
        tap(AccountTags.ABANDON_OK)
        idleUntil("已放弃") { all("POST", ABANDON).isNotEmpty() && has(AccountTags.PHONE_STEP1) }
        composeRule.runOnUiThread { stale() }
        settle()
        val abandon = all("POST", ABANDON)
        assertEquals("【确定放弃】恰一次；同框再点 0 次", 1, abandon.size)
        assertEquals("作废原键", key, (body(abandon.single())["idempotency_key"] as JsonPrimitive).content)
        assertEquals("action = phone_change", "phone_change", (body(abandon.single())["action"] as JsonPrimitive).content)
    }

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun redLine4_phoneChangeStaleOkAfterIdentitySwitchDoesNothing() {
        enterPhonePending()
        openAbandon(AccountTags.PENDING_ABANDON, AccountTags.ABANDON_OK)
        val stale = clickAction(node(AccountTags.ABANDON_OK))
        composeRule.runOnUiThread {
            tokens.save("access-$USER_B", "refresh-$USER_B", "full", USER_B)
            stale()
            login.syncSession()
            me.syncIdentity()
        }
        settle()
        assertTrue("换身份后旧框 0 次作废", all("POST", ABANDON).isEmpty())
    }

    // ---------------------------------------------------------------- ③ ④ 绑号待确认

    @Test fun redLine3_bindConfirmResendsTheOriginalKeyOnce() {
        val key = enterBindPending()
        tap(BindPhonePendingTags.CONFIRM)
        idleUntil("确认已发") { all("POST", BIND).isNotEmpty() }
        settle()
        val posts = all("POST", BIND)
        assertEquals("确认一次 = 原键原体一次", 1, posts.size)
        assertEquals("原键", key, posts.single().key)
        assertEquals(phone("bind"), (body(posts.single())["phone"] as JsonPrimitive).content)
    }

    @Test fun redLine4_bindThinkAgainIsZeroAndOkAbandonsOnce() {
        val key = enterBindPending()
        openAbandon(BindPhonePendingTags.ABANDON, BindPhonePendingTags.ABANDON_OK)
        tap(BindPhonePendingTags.ABANDON_CANCEL)
        assertTrue("【再想想】0 次", all("POST", ABANDON).isEmpty())
        openAbandon(BindPhonePendingTags.ABANDON, BindPhonePendingTags.ABANDON_OK)
        touchOutsideDialog()
        assertTrue("点遮罩不关闭（原样 dismissOnClickOutside = false）", has(BindPhonePendingTags.ABANDON_OK))
        tap(BindPhonePendingTags.ABANDON_OK)
        idleUntil("作废已发") { all("POST", ABANDON).isNotEmpty() }
        settle()
        val abandon = all("POST", ABANDON)
        assertEquals("恰一次", 1, abandon.size)
        assertEquals("作废原键", key, (body(abandon.single())["idempotency_key"] as JsonPrimitive).content)
    }

    @Test fun redLine3_bindStaleConfirmAfterIdentitySwitchDoesNothing() {
        enterBindPending()
        invokeStaleAfterSwitch(BindPhonePendingTags.CONFIRM)
        assertEquals("换身份后旧【确认】0 次", 0, all("POST", BIND).size)
    }

    // ---------------------------------------------------------------- ⑥ 联系客服

    @Test fun redLine6_cantUseOldOpensTheUnifiedServiceOnce() {
        enterStep1()
        val cs = csOpens()
        click(AccountTags.PHONE_CANT_USE_OLD)
        assertEquals("「原手机号不能用了？」恰一次", cs + 1, csOpens())
        assertEquals("零请求", 0, all("POST", SMS).size)
    }

    @Test fun redLine6_inline30414ContactOpensTheUnifiedServiceOnce() {
        submitStep2With(30414)
        idleUntil("30414 页内错误 + 联系客服（G-D10 不改弹窗）") { has(AccountTags.PHONE_CONTACT_CS) }
        assertTrue("页内错误原样", hasText(t("error.30414")))
        val cs = csOpens()
        click(AccountTags.PHONE_CONTACT_CS)
        assertEquals("恰一次", cs + 1, csOpens())
    }

    @Test fun redLine6_staleCantUseOldAfterIdentitySwitchDoesNothing() {
        enterStep1()
        val cs = csOpens()
        invokeStaleAfterSwitch(AccountTags.PHONE_CANT_USE_OLD)
        assertEquals("换身份后旧链接 0 次", cs, csOpens())
    }

    // ---------------------------------------------------------------- ⑩ 显示与可点集合

    @Test fun redLine10_noCurrentPhoneInAnyPhoneChangeState() {
        launch()
        openPhoneChange()
        assertNoCurrentPhone("第 1 步")
        passStep1()
        assertNoCurrentPhone("第 2 步")
        fillStep2()
        assertNoCurrentPhone("第 2 步已发码")
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("完成页") { has(AccountTags.PHONE_DONE) }
        assertNoCurrentPhone("完成页")
        assertTrue("只出现本机脱敏新号", hasText(phone("freshMasked")))
        assertFalse("新号原文不在完成页", hasText(PHONE_NEW))
    }

    @Test fun redLine10_countdownWordingNeverSaysKeYi() {
        enterStep1(sent = true)
        assertTrue("先正：倒计时在", hasText(STEP2_COUNTDOWN_TEXT))
        assertFalse("G-D08 第 1 步", hasText(FORBIDDEN_COUNTDOWN))
        type(AccountTags.PHONE_STEP1_CODE, CODE_OK)
        tap(AccountTags.PHONE_STEP1_NEXT)
        idleUntil { has(AccountTags.PHONE_STEP2) }
        sendStep2()
        assertFalse("G-D08 第 2 步", hasText(FORBIDDEN_COUNTDOWN))
        assertFalse("G-D09 不加「收不到验证码？」", hasText("收不到验证码"))
    }

    @Test fun redLine10_bindCountdownWordingAndNoHelpEntry() {
        enterBind(sent = true)
        assertTrue("先正：倒计时在", hasText(STEP2_COUNTDOWN_TEXT))
        assertFalse("G-D08", hasText(FORBIDDEN_COUNTDOWN))
        assertFalse("G-D09", hasText("收不到验证码"))
    }

    @Test fun redLine10_clickableSetOnStep1IsUnchanged() {
        enterStep1()
        assertEquals(
            "第 1 步可点 = 返回 + 验证码 + 获取验证码 + 原手机号不能用了 + 下一步（不多出新入口）",
            setOf(
                BACK_KIND,
                AccountTags.PHONE_STEP1_CODE,
                AccountTags.PHONE_STEP1_SEND,
                AccountTags.PHONE_CANT_USE_OLD,
                AccountTags.PHONE_STEP1_NEXT,
            ),
            clickableKinds(),
        )
    }

    @Test fun redLine10_clickableSetOnStep2IsUnchanged() {
        enterStep2()
        assertEquals(
            "第 2 步可点 = 返回 + 新号 + 验证码 + 获取验证码 + 提交（新号框不加清除等新入口）",
            setOf(
                BACK_KIND,
                AccountTags.PHONE_NEW,
                AccountTags.PHONE_STEP2_CODE,
                AccountTags.PHONE_STEP2_SEND,
                AccountTags.PHONE_SUBMIT,
            ),
            clickableKinds(),
        )
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun redLine10_clickableSetOnTheDonePageIsUnchanged() {
        enterDone()
        assertEquals("完成页可点 = 返回 + 完成", setOf(BACK_KIND, AccountTags.PHONE_DONE_OK), clickableKinds())
    }

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun redLine10_clickableSetOnThePendingPageAddsNothing() {
        enterPhonePending()
        val kinds = clickableKinds()
        val core = setOf(BACK_KIND, AccountTags.PENDING_ABANDON, AccountTags.PENDING_CONFIRM)
        assertTrue("先正：返回 + 放弃 + 确认都在：$kinds", kinds.containsAll(core))
        val extra = kinds - core
        assertTrue("其余只能是置灰表单（_locked 标记）：$extra", extra.all { it.endsWith(AccountTags.LOCKED_SUFFIX) })
    }

    @Test fun redLine10_clickableSetOnTheBindPageIsUnchanged() {
        enterBind(typed = true)
        assertEquals(
            "绑号可点 = 返回 + 手机号 + 清除 + 验证码 + 获取验证码 + 绑定",
            setOf(
                BindPhoneTags.BACK,
                BindPhoneTags.PHONE,
                BindPhoneTags.PHONE_CLEAR,
                BindPhoneTags.CODE,
                BindPhoneTags.GET_CODE,
                BindPhoneTags.SUBMIT,
            ),
            clickableKinds(),
        )
    }

    // ---------------------------------------------------------------- 辅助

    private fun assertNoCurrentPhone(where: String) {
        assertFalse("$where 不显示当前手机号原文（G-D05）", hasText(phone("current")))
        assertFalse("$where 不显示当前手机号脱敏（不由本机缓存拼）", hasText("138****5678"))
    }

    private fun openAbandon(
        button: String,
        ok: String,
    ) {
        tap(button)
        idleUntil("放弃二次确认") { has(ok) }
    }

    private fun touchOutsideDialog() {
        composeRule.runOnUiThread {
            val dialog = ShadowDialog.getLatestDialog()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(0, 10L, action, 2f, 2f, 0)
                try {
                    dialog.onTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
        settle()
    }
}
