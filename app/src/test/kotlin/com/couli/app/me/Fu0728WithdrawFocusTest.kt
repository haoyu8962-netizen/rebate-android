package com.couli.app.me

import android.app.Application
import android.os.Looper
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * FU-AND-0728 U8（F-AND-73，APP-12w-b brief-b §4 第 2 条）：撤回同意确认框关闭后焦点回到【撤回同意】——【取消】、系统返回、【确认撤回】三种关闭都要；
 * 点遮罩不关闭（红线 ①，dismissOnClickOutside = false），点完遮罩再【取消】同样回焦。
 * 打开确认框用按钮节点的语义点击（不经触摸按下，避免「触摸即聚焦」掩盖缺陷）；焦点断言前推进 Compose 主时钟与主线程 Looper 各 [SETTLE_MS]
 * （CouliDialog 标题焦点 150ms、组件库 RestoreFocusOnDispose 300ms 均在内）。测试环境的 withdrawConsent 只记账、不进入撤回中，【撤回同意】仍可聚焦。
 * 另守「冻结确认框块之外实现」：PrivacyCenter.kt 里 controller::askWithdraw 恰 1 处、cancelWithdraw(confirm) 恰 2 处、confirmWithdraw(confirm) 恰 1 处，
 * 确认框块（`val confirm = state.confirm` 起到 CouliDialog 调用结束）与底版逐字一致。
 * 底版：四条焦点用例红（关闭后无焦点），源码条款绿；实现后全绿。变异：去掉按钮处的焦点请求 → 焦点用例红。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Fu0728WithdrawFocusTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PrivacyVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    @Test fun cancelReturnsFocusToTheWithdrawButton() {
        openDialog()
        host.tap(rule.dialogButton(CANCEL))
        assertFalse("先正：【取消】关闭确认框", host.has(MeTags.WITHDRAW_DIALOG))
        assertFocusOnWithdraw("【取消】后")
        assertEquals("零动作", 0, host.env.withdrawals.size)
    }

    @Test fun systemBackReturnsFocusToTheWithdrawButton() {
        openDialog()
        @Suppress("DEPRECATION")
        rule.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        host.idle()
        assertFalse("先正：系统返回关闭确认框", host.has(MeTags.WITHDRAW_DIALOG))
        assertFocusOnWithdraw("系统返回后")
        assertEquals("零动作", 0, host.env.withdrawals.size)
    }

    @Test fun confirmReturnsFocusToTheWithdrawButton() {
        openDialog()
        host.tap(rule.dialogButton(CONFIRM))
        assertFalse("先正：【确认撤回】关闭确认框", host.has(MeTags.WITHDRAW_DIALOG))
        assertEquals("先正：撤回恰一次", 1, host.env.withdrawals.size)
        assertFocusOnWithdraw("【确认撤回】后")
    }

    @Test fun scrimTapKeepsTheDialogThenCancelReturnsFocus() {
        openDialog()
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
        assertTrue("先正：点遮罩不关闭（红线 ①）", host.has(MeTags.WITHDRAW_DIALOG))
        settle()
        assertFalse("确认框开着时焦点不被拉回页面", focused().any { it.isWithdraw() })
        host.tap(rule.dialogButton(CANCEL))
        assertFalse(host.has(MeTags.WITHDRAW_DIALOG))
        assertFocusOnWithdraw("点遮罩后再【取消】")
    }

    @Test fun implementationStaysOutsideTheFrozenDialogBlock() {
        val text = File(root(), PRIVACY_CENTER).readText()
        assertEquals("controller::askWithdraw 引用次数不变", 1, text.split("controller::askWithdraw").size - 1)
        assertEquals("cancelWithdraw(confirm) 次数不变", 2, text.split("controller.cancelWithdraw(confirm)").size - 1)
        assertEquals("confirmWithdraw(confirm) 次数不变", 1, text.split("controller.confirmWithdraw(confirm)").size - 1)
        assertTrue("确认框块逐字不变（焦点只能在块外实现）", text.contains(FROZEN_DIALOG_BLOCK))
    }

    // ---------------------------------------------------------------- 辅助

    private fun openDialog() {
        host.launchPrivacy()
        val button =
            rule.onNode(
                hasClickAction() and (hasTestTag(MeTags.PRIVACY_WITHDRAW) or hasAnyAncestor(hasTestTag(MeTags.PRIVACY_WITHDRAW))),
                useUnmergedTree = true,
            )
        if (button.insideScroll()) button.performScrollTo()
        button.performSemanticsAction(SemanticsActions.OnClick)
        host.idle()
        assertTrue("先正：出确认框", host.has(MeTags.WITHDRAW_DIALOG))
        settle()
        host.dialogText(AppMeTexts.text("privacy.withdraw.confirm.title")).assertIsFocused()
        assertFalse("先正：确认框打开时【撤回同意】不持有焦点", focused().any { it.isWithdraw() })
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        shadowOf(Looper.getMainLooper()).idleFor(SETTLE_MS, TimeUnit.MILLISECONDS)
        host.idle()
    }

    private fun assertFocusOnWithdraw(what: String) {
        settle()
        val now = focused()
        assertTrue(
            "$what 焦点应回到【撤回同意】（实际持焦节点：${now.map { it.describe() }}）",
            now.any { it.isWithdraw() },
        )
    }

    private fun focused(): List<SemanticsNode> =
        rule
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true), useUnmergedTree = true)
            .fetchSemanticsNodes()

    private fun SemanticsNode.isWithdraw(): Boolean =
        generateSequence(this) { it.parent }.any { it.config.getOrNull(SemanticsProperties.TestTag) == MeTags.PRIVACY_WITHDRAW } ||
            children.any { child -> child.config.getOrNull(SemanticsProperties.TestTag) == MeTags.PRIVACY_WITHDRAW }

    private fun SemanticsNode.describe(): String =
        config.getOrNull(SemanticsProperties.TestTag)
            ?: config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            ?: "#$id"

    private fun root(): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile && File(it, PRIVACY_CENTER).isFile }

    private companion object {
        const val SETTLE_MS = 600L
        const val PRIVACY_CENTER = "feature/me/src/main/kotlin/com/couli/feature/me/PrivacyCenter.kt"
        val CANCEL = AppMeTexts.text("privacy.withdraw.confirm.cancel")
        val CONFIRM = AppMeTexts.text("privacy.withdraw.confirm.ok")

        /** 底版 373cb22 PrivacyCenter.kt 的确认框块（D10-403 ③ 冻结，只许改外观；本任务不许动）。 */
        val FROZEN_DIALOG_BLOCK =
            """
            |    val confirm = state.confirm
            |    if (confirm != null) {
            |        // 确认框生成时捕获执行器与一次性编号（共性第 7、20 条）；【取消】主按钮、【确认撤回】次按钮（BR-TEXT-14 表 D）；点遮罩不执行、系统返回 = 取消
            |        CouliDialog(
            |            title = texts.text("privacy.withdraw.confirm.title"),
            |            message = texts.text("privacy.withdraw.confirm.body"),
            |            confirmText = texts.text("privacy.withdraw.confirm.cancel"),
            |            onConfirm = { controller.cancelWithdraw(confirm) },
            |            dismissText = texts.text("privacy.withdraw.confirm.ok"),
            |            onDismiss = { controller.confirmWithdraw(confirm) },
            |            onDismissRequest = { controller.cancelWithdraw(confirm) },
            |            dismissOnClickOutside = false,
            |            dismissTag = MeTags.WITHDRAW_DIALOG,
            |            titleAlign = TextAlign.Center,
            |            scrim = CouliColors.overlayScrim,
            |        )
            |    }
            """.trimMargin()
    }
}
