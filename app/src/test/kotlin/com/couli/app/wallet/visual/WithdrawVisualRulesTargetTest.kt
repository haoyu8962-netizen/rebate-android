package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.textLayout
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawVisualRulesTargetTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun rulesVisualRowIs21NotThe48TouchHeight() = visual(false)

    @Test fun darkRulesVisualRowIs21NotThe48TouchHeight() = visual(true)

    @Test fun rulesTargetIs48AndDoesNotOverlapSubmitOrOtherActions() {
        launch()
        target()
    }

    @Test fun allFourActualRulesEdgesOnlyOpenConfiguredHelp() = edges()

    @Test
    @Config(qualifiers = "w320dp-h1100dp-xhdpi")
    fun narrowRulesEdgesStillReachOnlyHelp() = edges()

    private fun launch(dark: Boolean = false) {
        host.launch(dark = dark)
        host.input()
        host.settleEstimate()
        // 提交必须可用，不能靠禁用提交掩盖规则扩展区误命中资金写按钮。
        rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).assertIsEnabled()
    }

    private fun visual(dark: Boolean) {
        launch(dark)
        val link = rule.onNodeWithTag(WalletTags.WITHDRAW_RULES_LINK).fetchSemanticsNode()
        val d = link.layoutInfo.density.density
        val bounds = link.recordUnclippedBounds()
        // Withdraw.dc.html:60 的 14px/21px 字行 + brief-android W53（视觉21、Android命中48）。
        // 默认字号仅容忍测量取整±1dp；查外层未裁切布局，不能拿内层文字21冒充48按钮完成分离。
        assertEquals("提现规则实际视觉行21dp，48dp命中不能撑高布局", 21f, bounds.height / d, 1f)
        assertEquals("可见高度不能靠裁切伪装21dp", bounds.height, link.boundsInRoot.height, 1f)
        assertEquals("可见位置与未裁切位置一致", bounds.top, link.boundsInRoot.top, 1f)
        val text = rule.onNodeWithText(WalletDraftTexts.text("withdraw.rules_link"), true).textLayout()
        assertEquals("保留画板21sp字行，不靠缩字", 21f, text.layoutInput.style.lineHeight.value, .01f)
    }

    private fun target(): SemanticsNode {
        val target = rule.onNodeWithTag(WalletTags.WITHDRAW_RULES_LINK, true).fetchSemanticsNode()
        val b = target.touchBoundsInRoot
        val d = target.layoutInfo.density.density
        assertTrue("实际命中区至少48×48dp：$b", b.width >= 48 * d && b.height >= 48 * d)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("完整命中区不能越出页面", b.left >= root.left && b.top >= root.top && b.right <= root.right && b.bottom <= root.bottom)
        rule.onAllNodes(hasClickAction(), true).fetchSemanticsNodes().filter { it.id != target.id }.forEach {
            assertRecordTargetsSeparate(b, it.touchBoundsInRoot)
        }
        return target
    }

    private fun edges() {
        launch()
        val before = host.api.events.toList()
        val outsideVisual = mutableListOf<Boolean>()
        repeat(4) { edge ->
            val node = target()
            val b = node.touchBoundsInRoot
            val d = node.layoutInfo.density.density
            val point =
                when (edge) {
                    0 -> Offset(b.center.x, b.top + d)
                    1 -> Offset(b.center.x, b.bottom - d)
                    2 -> Offset(b.left + d, b.center.y)
                    else -> Offset(b.right - d, b.center.y)
                }
            // 按48dp目标取上/下边缘，21dp行正确实现后这两点必须真的在视觉行外。
            val visual = node.recordUnclippedBounds()
            if (edge < 2) outsideVisual += !visual.contains(point)
            val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
            rule.onRoot().performTouchInput { click(point - root.topLeft) }
            rule.waitForIdle()
            assertEquals(List(edge + 1) { "Help" }, host.env.opened.map { it.first })
            host.env.opened.forEach { assertEquals("\"withdraw-rules-fixture\"", it.second["article_id"].toString()) }
            assertEquals("边缘不能发提交/估算/二次验证", before, host.api.events)
            assertEquals(0, host.api.writes().size + host.api.confirms.size + host.api.abandons.size)
            assertEquals("规则跳转不能生成提现幂等键", 0, host.keys)
            assertTrue(host.env.appeals.isEmpty() && host.env.replaced.isEmpty())
        }
        println("RULES_EDGES help=${host.env.opened.size} writes=${host.api.writes().size} outsideVisual=$outsideVisual")
        assertTrue("上下真实边缘应覆盖视觉行外扩展区", outsideVisual.all { it })
    }
}
