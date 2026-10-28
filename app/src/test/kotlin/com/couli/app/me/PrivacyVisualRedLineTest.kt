package com.couli.app.me

import android.app.Application
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * brief-b §0.1 / §5「红线逐条测试」（隐私红线页，S0；三端同名 redLine1…redLine5）。底版上必须全绿（行为一字不动），任何回调 / 编号 / 主次 /
 * 遮罩 / 去向的回退都应变红（变异见交回 §4）。全部从真实控件的真实触摸出发计数；「旧回调」取自节点上已注册的点击动作，在关闭 / 换身份后再调。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h864dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class PrivacyVisualRedLineTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PrivacyVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    // ---------------------------------------------------------------- ① 撤回同意确认

    @Test fun redLine1CancelIsZeroAndDialogCloses() {
        host.launchPrivacy()
        host.tap(MeTags.PRIVACY_WITHDRAW)
        assertTrue("点【撤回同意】先出确认框", host.has(MeTags.WITHDRAW_DIALOG))
        val stale = host.clickAction(host.node(MeTags.WITHDRAW_DIALOG))
        host.tap(rule.dialogButton(CANCEL))
        assertFalse("【取消】关闭确认框", host.has(MeTags.WITHDRAW_DIALOG))
        assertEquals("【取消】= 零动作", 0, host.env.withdrawals.size)
        rule.runOnIdle { stale() }
        host.idle()
        assertEquals("关闭后旧【确认撤回】回调零动作（R1-02）", 0, host.env.withdrawals.size)
    }

    @Test fun redLine1SystemBackIsCancel() {
        host.launchPrivacy()
        host.tap(MeTags.PRIVACY_WITHDRAW)
        val stale = host.clickAction(host.node(MeTags.WITHDRAW_DIALOG))
        @Suppress("DEPRECATION")
        rule.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        host.idle()
        assertFalse("系统返回 = 取消（关闭）", host.has(MeTags.WITHDRAW_DIALOG))
        rule.runOnIdle { stale() }
        host.idle()
        assertEquals("返回零动作、旧回调零动作", 0, host.env.withdrawals.size)
    }

    @Test fun redLine1ScrimTapNeitherClosesNorWithdraws() {
        host.launchPrivacy()
        host.tap(MeTags.PRIVACY_WITHDRAW)
        rule.runOnUiThread {
            val dialog = ShadowDialog.getLatestDialog()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(0, 16, action, 4f, 4f, 0)
                try {
                    dialog.onTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
        host.idle()
        assertTrue("点遮罩不关闭", host.has(MeTags.WITHDRAW_DIALOG))
        assertEquals("点遮罩不执行", 0, host.env.withdrawals.size)
    }

    @Test fun redLine1ConfirmWithdrawsExactlyOnceForTheRenderIdentityAndReuseIsVoid() {
        host.launchPrivacy()
        host.tap(MeTags.PRIVACY_WITHDRAW)
        val button = rule.dialogButton(CONFIRM)
        val stale = host.clickAction(button)
        host.tap(button)
        assertEquals("【确认撤回】→ 应用级协调器恰好一次、渲染时的身份", listOf(PrivacyVisualEnv.member()), host.env.withdrawals)
        assertFalse("确认后确认框作废", host.has(MeTags.WITHDRAW_DIALOG))
        rule.runOnIdle { stale() }
        host.idle()
        assertEquals("同一确认框再点仍 1 次（编号用过即作废）", 1, host.env.withdrawals.size)
        assertTrue("撤回不导航、不发请求", host.env.opened.isEmpty() && host.api.calls.isEmpty())
    }

    @Test fun redLine1ConfirmTagIsTheSecondaryConfirmButton() {
        host.launchPrivacy()
        host.tap(MeTags.PRIVACY_WITHDRAW)
        val tagged = host.node(MeTags.WITHDRAW_DIALOG).fetchSemanticsNode()
        val confirm = rule.dialogButton(CONFIRM).fetchSemanticsNode()
        assertEquals("WITHDRAW_DIALOG 标记挂在【确认撤回】上（dismissTag，次按钮）", confirm.id, tagged.id)
    }

    @Test fun redLine1IdentityChangeVoidsTheOldConfirm() {
        host.launchPrivacy()
        host.tap(MeTags.PRIVACY_WITHDRAW)
        val stale = host.clickAction(rule.dialogButton(CONFIRM))
        rule.runOnIdle { host.env.identityFlow.value = PrivacyVisualEnv.member(PrivacyVisualEnv.ACCOUNT_B, 2) }
        host.idle()
        assertFalse("换身份：执行器重建，旧确认框作废", host.has(MeTags.WITHDRAW_DIALOG))
        rule.runOnIdle { stale() }
        host.idle()
        assertEquals("换身份后旧确认 0 次", 0, host.env.withdrawals.size)
    }

    @Test fun redLine1WithdrawingDisablesTheButtonAndOpensNoDialog() {
        host.launchPrivacy(withdrawing = true)
        val button = host.node(MeTags.PRIVACY_WITHDRAW).fetchSemanticsNode()
        assertTrue("撤回进行中不可点", button.config.contains(SemanticsProperties.Disabled))
        host.tap(MeTags.PRIVACY_WITHDRAW)
        assertFalse("撤回进行中不出确认框", host.has(MeTags.WITHDRAW_DIALOG))
        assertEquals(0, host.env.withdrawals.size)
    }

    @Test fun redLine1ForceUpdateContextKeepsTheSameWithdrawChain() {
        host.launchPrivacy(session = "privacy_force_update")
        host.tap(MeTags.PRIVACY_WITHDRAW)
        host.tap(rule.dialogButton(CANCEL))
        assertEquals(0, host.env.withdrawals.size)
        host.tap(MeTags.PRIVACY_WITHDRAW)
        host.tap(rule.dialogButton(CONFIRM))
        assertEquals("强更上下文撤回同样恰一次", 1, host.env.withdrawals.size)
    }

    // ---------------------------------------------------------------- ② 基本模式只读

    @Test fun redLine2BasicHasNoSwitchNoWithdrawAndZeroRequests() {
        host.launchPrivacy(session = "privacy_basic")
        assertFalse("基本模式无个性化开关", host.has(MeTags.PRIVACY_SWITCH))
        assertFalse("基本模式无【撤回同意】", host.has(MeTags.PRIVACY_WITHDRAW))
        assertEquals("基本模式不出现「撤回同意」字样", 0, host.count(AppMeTexts.text("privacy_center.withdraw")))
        assertEquals("基本模式不出现个性化推荐", 0, host.count(AppMeTexts.text("privacy_center.personalization")))
        host.idle()
        assertTrue("进入与停留：GET /v1/me、recordConsent、推送上报 0 次（零请求）：${host.api.calls}", host.api.calls.isEmpty())
        host.tap(MeTags.PRIVACY_ENABLE_FULL)
        assertEquals("【开启完整功能】→ 首启弹窗恰一次", 1, host.env.fullFunction)
        assertTrue("仍零请求", host.api.calls.isEmpty())
        assertTrue("不导航", host.env.opened.isEmpty())
    }

    // ---------------------------------------------------------------- ③ 无删除数据入口

    @Test fun redLine3NoClickableNodeLeadsToDeletionOnThePage() {
        host.launchPrivacy()
        val desc = host.pageText(AppMeTexts.text("privacy_center.withdraw_desc")).fetchSemanticsNode()
        assertFalse("「要删除账号请使用账号注销」是纯文字", desc.config.contains(SemanticsActions.OnClick))
        assertDeletionFree()
        listOf(
            MeTags.PRIVACY_LIST_PREFIX + "collect",
            MeTags.PRIVACY_LIST_PREFIX + "share",
            MeTags.PRIVACY_POLICY,
            MeTags.PRIVACY_AGREEMENT,
        ).forEach(host::tap)
        assertTrue("所有行只去 Agreement：${host.env.opened}", host.env.opened.all { it.first == "Agreement" })
        assertTrue("没有通往 DeleteAccount", host.env.opened.none { it.first == "DeleteAccount" })
    }

    @Test fun redLine3DialogBodyIsPlainTextAndHasNoDeletionEntry() {
        host.launchPrivacy()
        host.tap(MeTags.PRIVACY_WITHDRAW)
        val body = host.dialogText(AppMeTexts.text("privacy.withdraw.confirm.body")).fetchSemanticsNode()
        assertFalse("确认框说明句不可点", body.config.contains(SemanticsActions.OnClick))
        assertDeletionFree()
        assertEquals("确认框里只有【取消】【确认撤回】两个可点节点", 2, clickablesInDialog())
    }

    // ---------------------------------------------------------------- ④ 系统权限只查不申请

    @Test fun redLine4PermissionRowOpensAppSettingsOnceAndRequestsNothing() {
        // G-P15：Android 契约无条目；这里只为行为注入一条 android 条目（不截图、不冒充契约）
        host.env.entries = listOf(PrivacyVisualFixtures.behaviorPermissionEntry())
        host.launchPrivacy()
        host.tap(MeTags.PRIVACY_PERMISSION_PREFIX + "push")
        assertEquals("点权限行 → 系统设置恰一次", 1, host.env.appSettings)
        assertEquals("不申请权限、不经通知设置", 0, host.env.notifySettings)
        assertTrue("不导航、不发请求", host.env.opened.isEmpty() && host.api.calls.isEmpty())
    }

    @Test fun redLine4AndroidContractHasNoPermissionRowsButKeepsTheSection() {
        host.launchPrivacy()
        assertTrue("系统权限一节仍在（AC-S1-135）", host.has(MeTags.PRIVACY_PERMISSIONS))
        assertFalse("G-P15 不注入假条目", host.has(MeTags.PRIVACY_PERMISSION_PREFIX + "push"))
        assertFalse(host.has(MeTags.PRIVACY_PERMISSION_PREFIX + "photos"))
    }

    // ---------------------------------------------------------------- ⑤ 客服链接原样

    @Test fun redLine5LinkIsVerbatimKfUrlForRetryAndCopy() {
        host.launchCs("cs_open_failed")
        host.tap(MeTags.CS_RETRY)
        assertEquals("【重试】打开的是原样 kf.url", listOf(PrivacyVisualFixtures.kf), host.ports.opened)
        host.tap(MeTags.CS_COPY)
        assertEquals("复制同串（统一写入口一次）", listOf(PrivacyVisualFixtures.kf), host.ports.copies)
    }

    @Test fun redLine5TwoAccountsAndTwoEntriesGetTheSameLink() {
        host.launchCs("cs_handoff")
        host.tap(MeTags.CS_OPEN)
        rule.runOnUiThread { host.entry.open("Settings") }
        host.idle()
        host.tap(MeTags.CS_OPEN)
        assertEquals(2, host.ports.opened.size)
        assertEquals("逐字相同（AC-S1-134 ④）", host.ports.opened[0], host.ports.opened[1])
        assertEquals("原样 kf.url：不拼 uid / 手机号 / device_id / 邀请码 / 令牌 / 发起页", PrivacyVisualFixtures.kf, host.ports.opened[0])
        assertFalse("不带发起页路由名", host.ports.opened.any { it.contains("Settings") || it.contains("from") })
    }

    private fun clickablesInDialog(): Int =
        rule
            .onAllNodes(hasClickAction() and hasAnyAncestor(PrivacyVisualTestHost.PANE), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size

    /** 所有可点节点（含弹窗窗口）的读屏文字都不含「注销 / 删除」，即不存在通往注销 / 删除数据的可点入口。 */
    private fun assertDeletionFree() {
        val clickables = rule.onAllNodes(hasClickAction(), useUnmergedTree = false).fetchSemanticsNodes()
        assertTrue("有可点节点（先正）", clickables.isNotEmpty())
        clickables.forEach { node ->
            val spoken = node.spokenParts().joinToString(" ")
            assertFalse("可点节点不得指向注销 / 删除：$spoken", spoken.contains("注销") || spoken.contains("删除"))
        }
    }

    private companion object {
        val CANCEL = AppMeTexts.text("privacy.withdraw.confirm.cancel")
        val CONFIRM = AppMeTexts.text("privacy.withdraw.confirm.ok")
    }
}
