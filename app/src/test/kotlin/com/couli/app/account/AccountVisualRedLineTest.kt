@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliFormat
import com.couli.feature.me.AccountTags
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * APP-13v 敏感写页红线逐条（brief §0.1 / §5「红线逐条测试」，三端同名 redLine1…10；a 段 = 注销：①–⑥、⑩）。全部从真实页面的真实触摸出发计数，
 * 「旧回调」取自真实节点上已注册的点击动作（同一主线程任务里先换身份再调，见 AccountVisualHarness.invokeStaleAfterSwitch）。
 * 底版上应全绿（行为原样）；实现若改动发起条件、次数、顺序、去向或身份票据即红。⑦–⑨ 属 b 段（换号 / 绑号），由 APP-13v-b 测试补。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1463dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions", "LargeClass")
class AccountVisualRedLineTest : AccountVisualDeletionHarness() {
    // ---------------------------------------------------------------- ① 申请注销

    @Test fun redLine1_uncheckedSubmitIsDisabledAndTapSendsNothing() {
        enterInitial()
        assertFalse("未勾选不可点", enabled(AccountTags.DELETE_SUBMIT))
        tap(AccountTags.DELETE_SUBMIT)
        assertTrue("零请求", all("POST", DELETION).isEmpty() && all("POST", SMS).isEmpty())
        assertFalse("不出半屏", has(STEP_UP_SHEET))
    }

    @Test fun redLine1_oneTapOpensTheSheetAndAppliesOnceAfterVerifyWithTheSameKey() {
        enterInitial(checked = true)
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil("半屏（发码已有结论）") { stepUpReady() }
        val first = all("POST", DELETION)
        assertEquals("先发一次得 10003（现状），尚未验证", 1, first.size)
        assertNotNull("键非空", first.single().key)
        assertTrue("无请求体", first.single().body.isEmpty())
        assertEquals("半屏发码一次（account_deletion）", "account_deletion", body(all("POST", SMS).single())["action"].toString().trim('"'))
        passStepUpSheet()
        idleUntil("冷静期") { has(AccountTags.DELETE_COOLING) }
        val applies = all("POST", DELETION)
        assertEquals("验证后原键重放恰一次", 2, applies.size)
        assertEquals("同一个键", applies[0].key, applies[1].key)
        assertEquals("验证恰一次", 1, all("POST", STEP_UP).size)
        assertTrue("申请成功不退出登录（G19）", tokens.accessToken != null)
    }

    @Test fun redLine1_twoTapsWithTheFirstEndedUseDifferentKeys() {
        serverState.requireStepUp = false
        enterInitial(checked = true)
        enqueueCode("POST", DELETION, 30412)
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil { has(AccountTags.DELETE_ERROR) }
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil { has(AccountTags.DELETE_COOLING) }
        val keys = all("POST", DELETION).map { it.key }
        assertEquals(2, keys.size)
        assertNotEquals("每次点击新键", keys[0], keys[1])
    }

    @Test fun redLine1_tapWhileSubmittingSendsNothingMore() {
        serverState.requireStepUp = false
        enterInitial(checked = true)
        val (arrived, release) = hold("POST", DELETION) { envelope(deletionFixture("cooling")) }
        try {
            tap(AccountTags.DELETE_SUBMIT)
            assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
            assertFalse("提交中置灰", enabled(AccountTags.DELETE_SUBMIT))
            tap(AccountTags.DELETE_SUBMIT)
            assertEquals("提交中再点 0 次", 1, all("POST", DELETION).size)
        } finally {
            release.countDown()
        }
        idleUntil { has(AccountTags.DELETE_COOLING) }
        assertEquals(1, all("POST", DELETION).size)
    }

    @Test fun redLine1_writeGateBlockedSendsNothing() {
        serverState.requireStepUp = false
        enterInitial(checked = true)
        enqueueCode("POST", DELETION, 42901, headers = mapOf("Retry-After" to "30"))
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil("42901 落地") { all("POST", DELETION).size == 1 && !enabled(AccountTags.DELETE_SUBMIT) }
        settle()
        assertFalse("写闸门禁用期【申请注销】置灰", enabled(AccountTags.DELETE_SUBMIT))
        tap(AccountTags.DELETE_SUBMIT)
        assertEquals("写闸门禁用期 0 次", 1, all("POST", DELETION).size)
    }

    // ---------------------------------------------------------------- ② 勾选

    @Test fun redLine2_eachTapTogglesOnceWithZeroRequests() {
        enterInitial()
        val before = all().size
        assertEquals("默认不勾选", ToggleableState.Off, toggle())
        tap(AccountTags.DELETE_WAIVER)
        assertEquals(ToggleableState.On, toggle())
        assertEquals("状态描述：已选中（原样）", t("deletion.waiver.checked"), stateText())
        tap(AccountTags.DELETE_WAIVER)
        assertEquals(ToggleableState.Off, toggle())
        assertEquals("勾选零请求", before, all().size)
    }

    @Test fun redLine2_staleToggleAfterIdentitySwitchDoesNothing() {
        enterInitial()
        invokeStaleAfterSwitch(AccountTags.DELETE_WAIVER)
        assertTrue("换身份后旧勾选零请求（无注销写）", all("POST", DELETION).isEmpty())
        idleUntil("新身份页面") { has(AccountTags.DELETE_WAIVER) }
        assertEquals("新身份默认未勾选（旧回调没有带过来）", ToggleableState.Off, toggle())
    }

    // ---------------------------------------------------------------- ③ 确认上一次提交

    @Test fun redLine3_confirmResendsTheOriginalKeyOnce() {
        // 测试 v2 T2：种入的未决不带二次验证票据，假服务缺省 requireStepUp 会回 10003 拉起半屏、走不到冷静期；
        // 本条只核「原键原体重发一次」，前置照 AccountPendingIntegrationTest / enter30412 关掉二次验证要求
        serverState.requireStepUp = false
        val serial = enterPending()
        tap(AccountTags.PENDING_CONFIRM)
        idleUntil("确认结算") { has(AccountTags.DELETE_COOLING) }
        val posts = all("POST", DELETION)
        assertEquals("一次点击 = 原键重发一次", 1, posts.size)
        assertEquals("原键", serial, posts.single().key)
        assertTrue("原体（无请求体）", posts.single().body.isEmpty())
        assertTrue("终结 → 键结束", records().none { it.key == serial })
    }

    @Test fun redLine3_inFlightDisablesBothButtonsAndASecondTapSendsNothing() {
        enterPending()
        val (arrived, release) = hold("POST", DELETION) { envelope(deletionFixture("cooling")) }
        try {
            tap(AccountTags.PENDING_CONFIRM)
            assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
            assertFalse(enabled(AccountTags.PENDING_CONFIRM))
            assertFalse(enabled(AccountTags.PENDING_ABANDON))
            tap(AccountTags.PENDING_CONFIRM)
            tap(AccountTags.PENDING_ABANDON)
            assertEquals("在途再点 0 次", 1, all("POST", DELETION).size)
            assertFalse("在途点放弃不出确认框", has(AccountTags.ABANDON_OK))
        } finally {
            release.countDown()
        }
        idleUntil { has(AccountTags.DELETE_COOLING) }
    }

    @Test fun redLine3_confirm20903EndsAndResetsTheWaiver() {
        val serial = enterPending()
        enqueueCode("POST", DELETION, 20903)
        tap(AccountTags.PENDING_CONFIRM)
        idleUntil { records().none { it.key == serial } && !has(AccountTags.PENDING_CARD) }
        assertEquals("回初始态、勾选重置", ToggleableState.Off, toggle())
    }

    @Test fun redLine3_staleConfirmAfterIdentitySwitchDoesNothing() {
        enterPending()
        invokeStaleAfterSwitch(AccountTags.PENDING_CONFIRM)
        assertTrue("换身份后旧确认 0 次", all("POST", DELETION).isEmpty())
    }

    // ---------------------------------------------------------------- ④ 放弃上一次提交

    @Test fun redLine4_thinkAgainBackAndScrimAreZeroActions() {
        enterPending()
        openAbandonDialog()
        tap(AccountTags.ABANDON_CANCEL)
        assertFalse("【再想想】关闭", has(AccountTags.ABANDON_OK))
        openAbandonDialog()
        touchOutsideDialog()
        assertTrue("点遮罩不关闭", has(AccountTags.ABANDON_OK))
        @Suppress("DEPRECATION")
        composeRule.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        settle()
        assertFalse("系统返回 = 再想想", has(AccountTags.ABANDON_OK))
        assertTrue("三种方式都 0 次作废", all("POST", ABANDON).isEmpty())
        assertTrue("仍待确认", has(AccountTags.PENDING_CARD))
    }

    @Test fun redLine4_okAbandonsOnceAndTheStaleOkDoesNothing() {
        val serial = enterPending()
        openAbandonDialog()
        val stale = clickAction(node(AccountTags.ABANDON_OK))
        tap(AccountTags.ABANDON_OK)
        idleUntil { records().none { it.key == serial } }
        assertEquals("【确定放弃】恰一次", 1, all("POST", ABANDON).size)
        assertEquals("作废原键", serial, body(all("POST", ABANDON).single())["idempotency_key"].toString().trim('"'))
        assertEquals("action = account_deletion", "account_deletion", body(all("POST", ABANDON).single())["action"].toString().trim('"'))
        composeRule.runOnIdle { stale() }
        settle()
        assertEquals("同框再点（旧编号）仍 1 次", 1, all("POST", ABANDON).size)
    }

    @Test fun redLine4_staleOkAfterIdentitySwitchDoesNothing() {
        enterPending()
        openAbandonDialog()
        invokeStaleAfterSwitch(AccountTags.ABANDON_OK)
        assertTrue("换身份后旧框 0 次", all("POST", ABANDON).isEmpty())
    }

    // ---------------------------------------------------------------- ⑤ 撤回注销

    @Test fun redLine5_cancelOnceWithoutPendingRecordOrAutoResend() {
        enterCooling()
        val work = workSnapshot()
        tap(AccountTags.DELETE_CANCEL)
        idleUntil("回设置页") { top() == SETTINGS }
        workSince(work, "撤回结算")
        assertEquals("撤回恰一次", 1, all("POST", CANCEL).size)
        assertTrue("不写未决记录", records().none { it.operationId == "cancelDeletion" })
    }

    @Test fun redLine5_cancelTimeoutNeverResends() {
        enterCooling()
        enqueueTimeout("POST", CANCEL)
        val work = workSnapshot()
        tap(AccountTags.DELETE_CANCEL)
        workSince(work, "撤回超时与核对读取")
        assertEquals("不自动重发", 1, all("POST", CANCEL).size)
        assertTrue("仍可人工再点", has(AccountTags.DELETE_CANCEL))
    }

    // ---------------------------------------------------------------- ⑥ 去提现 / 联系客服 / 去绑定 / 补办引导

    @Test fun redLine6_goWithdrawOpensWithdrawOnce() {
        enterInitial()
        val before = lastRoute()
        val cs = csOpens()
        click(AccountTags.DELETE_GO_WITHDRAW)
        val after = lastRoute()
        assertNotSame("经 Router 打开一次", before, after)
        assertEquals("去向 Withdraw（原样）", "Withdraw", after?.request?.route)
        assertTrue("栈里至多一个 Withdraw：${stack()}", stack().count { it == "Withdraw" } <= 1)
        assertEquals("不顺带开客服", cs, csOpens())
        assertTrue("零写请求", all("POST", DELETION).isEmpty())
    }

    @Test fun redLine6_staleGoWithdrawAfterIdentitySwitchDoesNothing() {
        enterInitial()
        val before = lastRoute()
        invokeStaleAfterSwitch(AccountTags.DELETE_GO_WITHDRAW)
        assertFalse("旧回调不开提现", lastRoute() !== before && lastRoute()?.request?.route == "Withdraw")
    }

    @Test fun redLine6_restrictedHasNoGoWithdrawNode() {
        enterRestricted()
        assertFalse("deletion_only 无【去提现】节点", has(AccountTags.DELETE_GO_WITHDRAW))
        assertTrue("先正：受限注销页其余在", has(AccountTags.DELETE_SUBMIT))
    }

    @Test fun redLine6_contactCsOpensTheUnifiedExitOnce() {
        enterNegative()
        val before = csOpens()
        click(AccountTags.DELETE_CONTACT_CS)
        assertEquals("统一客服出口恰一次", before + 1, csOpens())
        assertEquals("发起页 DeleteAccount（原样）", DELETE_ACCOUNT, csFrom())
    }

    @Test fun redLine6_staleContactCsAfterIdentitySwitchDoesNothing() {
        enterNegative()
        val before = csOpens()
        invokeStaleAfterSwitch(AccountTags.DELETE_CONTACT_CS)
        assertEquals("旧回调 0 次", before, csOpens())
    }

    @Test fun redLine6_bindPhoneOpensBindPhoneOnce() {
        serverState.phoneBound = false
        enterInitial()
        checkWaiverAndSubmit()
        idleUntil { has(AccountTags.DELETE_BIND_PHONE) }
        val before = lastRoute()
        tap(AccountTags.DELETE_BIND_PHONE)
        assertNotSame(before, lastRoute())
        assertEquals("去向 BindPhone（原样）", "BindPhone", lastRoute()?.request?.route)
        assertTrue("G10 零写请求", all("POST", DELETION).isEmpty() && all("POST", SMS).isEmpty())
    }

    @Test fun redLine6_guideOpensItsTargetOnceAndNeverResubmits() {
        serverState.requireStepUp = false
        enterInitial(checked = true)
        val (arrived, release) = hold("POST", DELETION) { envelope("null", 10005) }
        try {
            tap(AccountTags.DELETE_SUBMIT)
            assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
            open(SETTINGS)
            idleUntil("设置页盖住注销页") { top() == SETTINGS }
        } finally {
            release.countDown()
        }
        settle()
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil("回注销页：补办引导手动入口") { top() == DELETE_ACCOUNT && has(AccountTags.DELETE_GUIDE) }
        val before = lastRoute()
        tap(AccountTags.DELETE_GUIDE)
        assertNotSame("引导一次", before, lastRoute())
        assertEquals("去向 BindPhone（10005，原样）", "BindPhone", lastRoute()?.request?.route)
        assertEquals("补办引导不自动提交", 1, all("POST", DELETION).size)
    }

    // ---------------------------------------------------------------- ⑩ 显示与可点集合

    @Test fun redLine10_noCurrentPhoneNoLocalCountdownAmountsFromFixture() {
        enterInitial()
        val current = AccountVisualFixtures.phone("current")
        assertFalse("不出现当前手机号原文", hasText(current))
        assertFalse("不出现当前手机号尾号拼接", hasText(current.takeLast(4)))
        assertTrue("可提现 = fixture 原值格式化", hasText(CouliFormat.money(AccountVisualFixtures.walletFen("funds", "withdrawable_fen"))))
        assertTrue("预估收益 = fixture 原值格式化", hasText(CouliFormat.money(AccountVisualFixtures.walletFen("funds", "estimated_total_fen"))))
    }

    @Test fun redLine10_coolingTimesOnlyFormatServerFields() {
        enterCooling()
        val coolingObj = AccountVisualFixtures.obj("cooling", AccountVisualFixtures.obj("deletion"))
        val until = AccountVisualFixtures.millis(AccountVisualFixtures.text("cooling_until", coolingObj))
        assertTrue(hasText(CouliFormat.timeListFuture(until, now)))
        assertFalse("不倒计时（无「天后」「小时」等本地推算句）", hasText("天后") || hasText("小时后") || hasText("剩余"))
    }

    @Test fun redLine10_clickableSetOnTheInitialPageIsUnchanged() {
        enterInitial()
        assertEquals("说明页可点节点集合 = 返回 + 去提现 + 勾选 + 申请（不多出新入口，G-D09）", INITIAL_CLICKABLES, clickableKinds())
    }

    @Test fun redLine10_clickableSetOnTheNegativePageIsUnchanged() {
        enterNegative()
        assertEquals("负余额页可点节点集合 = 返回 + 联系客服 + 勾选 + 申请", NEGATIVE_CLICKABLES, clickableKinds())
    }

    @Test fun redLine10_clickableSetOnTheCoolingPageIsUnchanged() {
        enterCooling()
        assertEquals("冷静期可点节点集合 = 返回 + 撤回", setOf(BACK, AccountTags.DELETE_CANCEL), clickableKinds())
    }

    @Test fun redLine10_clickableSetOnThePendingPageIsUnchanged() {
        enterPending()
        // 测试 v2 T4：冻结规则 DeletionPresentation.view 在 PENDING_CONFIRM 下不给 goWithdraw（底版本来就没有【去提现】）；
        // 画板 PendingConfirm 有【去提现】属规则 / 画板差异（交编排），可点集合按规则核，不多不少
        assertEquals(
            "待确认可点集合 = 返回 + 放弃 + 确认 + 勾选 + 申请（规则不给【去提现】）",
            setOf(
                BACK,
                AccountTags.PENDING_ABANDON,
                AccountTags.PENDING_CONFIRM,
                AccountTags.DELETE_WAIVER,
                AccountTags.DELETE_SUBMIT,
            ),
            clickableKinds(),
        )
        assertFalse("待确认页无【去提现】节点（规则原样）", has(AccountTags.DELETE_GO_WITHDRAW))
    }

    // ---------------------------------------------------------------- 辅助

    private fun toggle(): ToggleableState? =
        node(AccountTags.DELETE_WAIVER).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)

    private fun stateText(): String? =
        node(AccountTags.DELETE_WAIVER).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun openAbandonDialog() {
        tap(AccountTags.PENDING_ABANDON)
        idleUntil("放弃二次确认") { has(AccountTags.ABANDON_OK) }
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

    /**
     * 页面上（弹窗外）可点节点的种类：有标记的取标记；无标记且后代有内容描述的是返回（NavBar）。可点 = 有点击动作（含置灰：置灰仍是同一个入口）。
     * 文字链接 / 控件类型替换不改变种类（去提现、联系客服仍是同一标记），新入口会多出一种而变红。
     */
    private fun clickableKinds(): Set<String> =
        clickableNodes()
            .filter { node -> node.config.getOrNull(SemanticsProperties.PaneTitle) == null }
            .map { node ->
                node.config.getOrNull(SemanticsProperties.TestTag)
                    ?: if (node.descendantDescribed()) BACK else "未标记可点：" + node.textAll()
            }.toSet()

    private fun androidx.compose.ui.semantics.SemanticsNode.descendantDescribed(): Boolean =
        config.getOrNull(SemanticsProperties.ContentDescription)?.isNotEmpty() == true ||
            children.any { it.descendantDescribed() }

    private fun androidx.compose.ui.semantics.SemanticsNode.textAll(): String =
        (config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }) + children.joinToString { it.textAll() }

    private companion object {
        const val BACK = "返回"
        val INITIAL_CLICKABLES = setOf(BACK, AccountTags.DELETE_GO_WITHDRAW, AccountTags.DELETE_WAIVER, AccountTags.DELETE_SUBMIT)
        val NEGATIVE_CLICKABLES = setOf(BACK, AccountTags.DELETE_CONTACT_CS, AccountTags.DELETE_WAIVER, AccountTags.DELETE_SUBMIT)
    }
}
