package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.numwrap.NumField
import com.couli.app.numwrap.NumHint
import com.couli.app.numwrap.NumHintSpec
import com.couli.app.numwrap.NumReach
import com.couli.app.numwrap.NumToken
import com.couli.app.numwrap.NumWrap
import com.couli.app.product.textLayout
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliTextStyles
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
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawVisualAdaptationTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w55Font200At390KeepsMoneyAndSubmitAccessible() = large(scrolls = false)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun w55Font200At320KeepsMoneyAndSubmitAccessible() = large(scrolls = true)

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun w55WideKeeps600CenteredPageAndFullColumnSubmit() {
        host.launch()
        val page = rule.onNodeWithTag(WalletTags.WITHDRAW_PAGE).fetchSemanticsNode()
        val submit = rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).fetchSemanticsNode()
        val d = page.layoutInfo.density.density
        assertEquals("画布是完整窗口", 840f, page.boundsInRoot.width / d, 1f)
        assertEquals("600dp内容列左右各16内边距", 136f, submit.boundsInRoot.left / d, 1f)
        assertEquals(568f, submit.boundsInRoot.width / d, 1f)
        assertEquals(page.boundsInRoot.center.x, submit.boundsInRoot.center.x, d)
    }

    @Test fun w41QuickTargetsAllFourEdgesOnlyFillRequestedServerAmount() {
        host.launch()
        repeat(4) { edge ->
            rule.onNodeWithTag(WalletTags.WITHDRAW_QUICK_PREFIX + "500").performScrollTo()
            rule.touchRecordEdge(WalletTags.WITHDRAW_QUICK_PREFIX + "500", edge)
            assertEquals("5", host.model.controller.state.value.amountText)
            assertEquals(0, host.api.writes().size)
            assertEquals(0, host.env.opened.size)
        }
    }

    /**
     * #24 ⑤（FU-AND-NUM-WRAP，D10-1265）：页头 ¥999999999.99 在 w320×200% 独占一行仍放不下，框内横滑，布局宽 > 可见框（[scrolls] = true）；
     * w390 放得下、不横滑（[scrolls] = false，常规口径：不许误加横滑与提示）。「整段落在可见框内」换成：① NumHint——先独占一行、横滑从开头露出、
     * 末端渐隐 + 横向滚动指示器（语义 + 像素粗检）、读屏读完整金额；② 整段不断、不截断、不缩字（amountDisplay 令牌）、真滚到首尾；纵向不裁切照旧。
     */
    private fun large(scrolls: Boolean) {
        host.api.rules = WithdrawVisualFixtures.ok(WithdrawVisualFixtures.rules("stress"))
        host.launch(fontScale = 2f)
        // 先滚外层页面到页头（不对金额文字 performScrollTo：横滑时那只会滚它自己的横滑框）
        rule.onNodeWithTag(WalletTags.WITHDRAW_BALANCE, useUnmergedTree = true).performScrollTo()
        val header = NumWrap.within(WalletTags.WITHDRAW_BALANCE)
        val where = "提现页头（200%，${if (scrolls) "w320" else "w390"}）"
        val field = NumField(CouliFormat.money(STRESS_FEN), where, header, DISPLAY, exact = true)
        NumHint.assertHint(rule, NumHintSpec(field, row = header, spoken = CouliFormat.moneySpoken(STRESS_FEN), expect = scrolls))
        NumWrap.assertField(rule, field)
        val amount = rule.onNode(hasText(CouliFormat.money(STRESS_FEN), substring = true), true)
        amount.assertNotClippedVertically(where)
        assertEquals(
            2f,
            amount
                .textLayout()
                .layoutInput.density.fontScale,
            .01f,
        )
        assertTrue(
            "不能靠缩字过大字验收",
            amount
                .textLayout()
                .layoutInput.style.fontSize.value >= 34f,
        )
        val submit = rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).fetchSemanticsNode()
        assertTrue(submit.touchBoundsInRoot.height >= 48 * submit.layoutInfo.density.density)
        assertEquals(0, host.api.writes().size)
    }

    /** 旧 assertNoTextOverflow 的纵向部分（换判据后仍保留）：文字上下都在可视范围内。 */
    private fun SemanticsNodeInteraction.assertNotClippedVertically(what: String) {
        val node = fetchSemanticsNode()
        val height = textLayout().size.height
        val top = NumReach.coordinatesOf(node).positionInRoot().y
        val rows = NumReach.windowY(node.layoutInfo.coordinates)
        assertTrue(
            "$what 金额上下被裁切（文字 ${top.toInt()}..${(top + height).toInt()}，可视 ${rows.start.toInt()}..${rows.endInclusive.toInt()} px）",
            top >= rows.start - 1f && top + height <= rows.endInclusive + 1f,
        )
    }

    private companion object {
        /** specs visualWithdraw.rules.stress.withdrawable_fen → ¥999999999.99。 */
        const val STRESS_FEN = 99_999_999_999L
        val DISPLAY = NumToken(CouliTextStyles.amountDisplay.fontSize, 2f)
    }
}
