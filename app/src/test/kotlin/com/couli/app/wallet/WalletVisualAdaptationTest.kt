package com.couli.app.wallet

import android.app.Application
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.numwrap.NumField
import com.couli.app.numwrap.NumHint
import com.couli.app.numwrap.NumHintSpec
import com.couli.app.numwrap.NumReach
import com.couli.app.numwrap.NumToken
import com.couli.app.numwrap.NumWrap
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
class WalletVisualAdaptationTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WalletVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w17At390Font200AmountsStackAndEveryTextRemainsWhole() = large()

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun w17At320Font200AmountsStackAndEveryTextRemainsWhole() = large()

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun w17At320Font200LongAmountsNeverClipOrShrink() = longAmounts()

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun w17At840AllCardsShareCentered600DpColumn() {
        host.launch()
        listOf(WalletTags.WALLET_HERO, WalletTags.WALLET_ESTIMATED).forEach {
            val node = rule.walletTag(it)
            val bounds = node.walletBounds()
            assertEquals(568f, bounds.width / node.walletDp(), 1f)
            assertEquals(136f, bounds.left / node.walletDp(), 1f)
            assertEquals(420f, bounds.center.x / node.walletDp(), 1f)
        }
    }

    @Test fun w17DarkKeepsSolidWhiteMoneyAndRebateTokenOnlyOnEstimate() {
        host.launch(dark = true)
        assertEquals(
            host.solid,
            rule
                .walletText("¥12.34")
                .walletTextLayout()
                .layoutInput.style.color,
        )
        assertEquals(
            host.solid,
            rule
                .walletText("¥5")
                .walletTextLayout()
                .layoutInput.style.color,
        )
        assertEquals(
            host.rebate,
            rule
                .walletText("¥56.78")
                .walletTextLayout()
                .layoutInput.style.color,
        )
        assertEquals(
            host.primary,
            rule
                .walletText("10 月结算")
                .walletTextLayout()
                .layoutInput.style.color,
        )
    }

    private fun large() {
        host.launch(fontScale = 2f)
        val frozen = rule.walletText("冻结中")
        val withdrawn = rule.walletText("已提现")
        frozen.performScrollTo()
        val frozenTop = frozen.walletBounds().top
        // 同一视口里取两格坐标；不要分别滚动后比较两个不同滚动偏移。
        assertTrue("200% 主卡底部两格纵排", withdrawn.walletBounds().top > frozenTop)
        rule.walletTag(WalletTags.WALLET_ESTIMATED).performScrollTo()
        assertTrue("200% 预估与月份纵排", rule.walletText("最早预计").walletBounds().top > rule.walletText("¥56.78").walletBounds().top)
        listOf("¥12.34", "提现", "提现记录", "¥56.78", "10 月结算", "其中已收货 ¥34.56", "订单", "余额流水").forEach {
            rule.walletText(it).performScrollTo().assertWalletTextWhole()
        }
        host.assertSummaryReads(1)
    }

    private fun longAmounts() {
        host.launch("long_amounts", fontScale = 2f)
        // #24 ④（FU-AND-NUM-WRAP，D10-1265）：余额卡 ¥9876543.21 在 w320×200% 独占一行仍放不下，按 D10-1265 框内横滑，布局宽 > 可见框。
        // 「整段落在可见框内」换成：① 先独占一行、横滑从开头露出、末端渐隐 + 横向滚动指示器（语义 + 像素粗检）、读屏读完整金额（NumHint）；
        // ② 整段不断、不截断、不缩字（amountDisplay 令牌）、真滚到首尾（NumWrap.assertField → NumReach）；纵向不裁切照旧。
        // 先滚外层页面到余额卡（不对金额文字 performScrollTo：那只会滚它自己的横滑框）。
        rule.walletTag(WalletTags.WALLET_HERO).performScrollTo()
        val card = NumWrap.within(WalletTags.WALLET_HERO)
        val hero = NumField(HERO, "钱包余额卡（w320×200%）", card, DISPLAY, exact = true)
        NumHint.assertHint(rule, NumHintSpec(hero, row = card, spoken = CouliFormat.moneySpoken(HERO_FEN), expect = true))
        NumWrap.assertField(rule, hero)
        rule.walletText(HERO).assertNotClippedVertically()
        // 其余金额独占一行放得下，不横滑：旧判据照旧
        listOf("¥8765432.1", "¥7654321.09", "¥6543210.98", "其中已收货 ¥5432109.87", "其中 ¥4321098.76 暂缓入账").forEach {
            rule.walletText(it).performScrollTo().assertWalletTextWhole()
        }
        assertEquals(
            34f,
            rule
                .walletText(HERO)
                .walletTextLayout()
                .layoutInput.style.fontSize.value,
        )
    }

    /** 旧 assertWalletTextWhole 的纵向部分（横滑金额换判据后仍保留）：文字上下都在可视范围内。 */
    private fun SemanticsNodeInteraction.assertNotClippedVertically() {
        val node = fetchSemanticsNode()
        val height = walletTextLayout().size.height
        val top = NumReach.coordinatesOf(node).positionInRoot().y
        val rows = NumReach.windowY(node.layoutInfo.coordinates)
        assertTrue(
            "余额卡金额上下被裁切（文字 ${top.toInt()}..${(top + height).toInt()}，可视 ${rows.start.toInt()}..${rows.endInclusive.toInt()} px）",
            top >= rows.start - 1f && top + height <= rows.endInclusive + 1f,
        )
    }

    private companion object {
        /** wallet-vectors long_amounts.withdrawable_fen = 987654321。 */
        const val HERO_FEN = 987_654_321L
        const val HERO = "¥9876543.21"
        val DISPLAY = NumToken(CouliTextStyles.amountDisplay.fontSize, 2f)
    }
}
