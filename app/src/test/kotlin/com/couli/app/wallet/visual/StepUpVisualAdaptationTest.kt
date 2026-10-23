package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.wallet.WalletTags
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * W68 / brief §2.7：200% × 390 / 320（半屏窗口内真 200%：用系统 fontScale，不靠宿主 LocalDensity）、短视口 + 200% 的整块滚动降级（AdaptShortStepUp、
 * lesson33）、840 宽。判据：字号不缩、文字不裁切（尺寸与位置同时比）、新挪进中段 / 标题区的倒计时与说明都可达、验证仍恰好一次。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StepUpVisualAdaptationTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }
    private val kit by lazy { StepUpVisualKit(rule, host) }

    @After fun close() = host.close()

    @Test fun defaultSizeKeepsThreePartsWithCountdownVisibleWithoutScrolling() {
        kit.open()
        assertTrue("常规高度三段布局", has(WalletTags.SHEET_MIDDLE))
        assertFalse("常规高度不降级", has(WalletTags.SHEET_FALLBACK))
        kit.countdownNode().assertNotClipped("倒计时")
        kit.sentNode().assertNotClipped("说明")
        rule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi", fontScale = 2f)
    fun font200W390IsRealInsideTheSheetWindowAndNothingIsCut() = font200()

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi", fontScale = 2f)
    fun font200W320IsRealInsideTheSheetWindowAndNothingIsCut() = font200()

    @Test
    @Config(qualifiers = "w320dp-h360dp-xxhdpi", fontScale = 2f)
    fun shortWindowFont200FallsBackAndEveryPartIncludingTheNewRowsIsReachable() {
        kit.open()
        assertTrue("短窗口 + 200% 整块滚动降级（lesson33）", has(WalletTags.SHEET_FALLBACK))
        listOf(WalletTags.STEP_UP_CLOSE, WalletTags.STEP_UP_CODE, WalletTags.STEP_UP_SUBMIT).forEach {
            rule.onNodeWithTag(it, useUnmergedTree = true).scrollIfPossible().assertIsDisplayed()
        }
        reach(kit.sentNode().id, "说明")
        reach(kit.countdownNode().id, "倒计时")
        verifyOnce()
    }

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun wideSheetIsCenteredAndCellsStayInsideIt() {
        kit.open()
        val sheet = kit.tagged(WalletTags.STEP_UP_SHEET)
        val code = kit.tagged(WalletTags.STEP_UP_CODE)
        val d = sheet.density()
        val window =
            sheet.layoutInfo.coordinates
                .findRootCoordinates()
                .size.width
        assertEquals("半屏水平居中", window / 2f / d, sheet.boundsInRoot.center.x / d, 1f)
        assertTrue("半屏限宽（≤ 640dp）", sheet.boundsInRoot.width / d <= 640f + 1f)
        assertTrue("格子在半屏内", code.boundsInRoot.left >= sheet.boundsInRoot.left && code.boundsInRoot.right <= sheet.boundsInRoot.right)
        kit.countdownNode().assertNotClipped("倒计时")
    }

    private fun font200() {
        kit.open()
        val title = kit.titleNode()
        val layout = title.textLayout()
        assertEquals("半屏窗口内真 200%", 2f, layout.layoutInput.density.fontScale, .01f)
        assertEquals("标题不缩字", 17f, layout.layoutInput.style.fontSize.value, .01f)
        listOf(title to "标题", kit.sentNode() to "说明", kit.countdownNode() to "倒计时").forEach { (node, name) ->
            rule.onNode(byId(node.id), true).scrollIfPossible()
            val fresh = kit.node(byId(node.id), name)
            assertFalse("$name 不截断", fresh.textLayout().hasVisualOverflow)
            fresh.assertNotClipped(name)
        }
        verifyOnce()
    }

    private fun reach(
        id: Int,
        name: String,
    ) {
        rule.onNode(byId(id), true).scrollIfPossible().assertIsDisplayed()
        kit.node(byId(id), name).assertNotClipped(name)
    }

    private fun verifyOnce() {
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE, useUnmergedTree = true).scrollIfPossible().performTextReplacement("123456")
        assertEquals("输满不自动验证", 0, host.api.calls.count { it.operationId == "stepUp" })
        host.api.writeHold = CompletableDeferred()
        rule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT, useUnmergedTree = true).scrollIfPossible().performClick()
        rule.waitForIdle()
        assertEquals(
            "大字号下验证仍恰好一次并原键重放一次",
            listOf("createWithdrawal", "sendSmsCode", "stepUp", "createWithdrawal"),
            host.api.writes().map { it.operationId },
        )
        assertEquals(1, host.keys)
    }

    private fun has(tag: String) = rule.onAllNodesWithTag(tag, true).fetchSemanticsNodes().isNotEmpty()

    private fun byId(id: Int) = SemanticsMatcher("semantics id $id") { it.id == id }

    /** 降级时整块可滚；不降级时标题区 / 底部操作区本来固定可见，没有滚动祖先。 */
    private fun SemanticsNodeInteraction.scrollIfPossible(): SemanticsNodeInteraction = apply { runCatching { performScrollTo() } }
}
