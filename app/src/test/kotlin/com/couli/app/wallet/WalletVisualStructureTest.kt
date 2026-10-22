package com.couli.app.wallet

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.assertContainsRect
import com.couli.app.product.commonLayout
import com.couli.app.product.outerBounds
import com.couli.feature.wallet.WalletTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1200dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletVisualStructureTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WalletVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w1FrozenAndWithdrawnAreInsideHeroWithWhiteBodyAmounts() {
        host.launch("frozen")
        val hero = rule.walletTag(WalletTags.WALLET_HERO).walletBounds()
        assertContainsRect(hero, rule.walletTag(WalletTags.WALLET_FROZEN).walletBounds())
        listOf("冻结中", "已提现", "¥10", "¥5").forEach { text ->
            assertContainsRect(hero, rule.walletText(text).walletBounds())
            assertEquals(
                host.solid,
                rule
                    .walletText(text)
                    .walletTextLayout()
                    .layoutInput.style.color,
            )
        }
        assertEquals(
            14f,
            rule
                .walletText("冻结中")
                .walletTextLayout()
                .layoutInput.style.fontSize.value,
        )
        assertEquals(
            17f,
            rule
                .walletText("¥10")
                .walletTextLayout()
                .layoutInput.style.fontSize.value,
        )
        assertEquals(
            600,
            rule
                .walletText("¥10")
                .walletTextLayout()
                .layoutInput.style.fontWeight
                ?.weight,
        )
    }

    @Test fun w2HeroHasSixteenDpBetweenHeaderAndAmountRow() {
        host.launch()
        val title = rule.walletText("可提现余额").fetchSemanticsNode()
        val records = rule.walletText("提现记录").fetchSemanticsNode()
        val header = outerBounds(commonLayout(title.layoutInfo, records.layoutInfo))
        val amount = rule.walletText("¥12.34").fetchSemanticsNode()
        val button = rule.walletTag(WalletTags.WALLET_WITHDRAW).fetchSemanticsNode()
        val amountRow = outerBounds(commonLayout(amount.layoutInfo, button.layoutInfo))
        assertWalletGap(16f, amountRow.top - header.bottom, title.layoutInfo.density.density)
    }

    @Test fun w3WithdrawIsAtLeast96WideAnd48HighWith17SemiboldText() {
        host.launch()
        val button = rule.walletTag(WalletTags.WALLET_WITHDRAW)
        val bounds = button.walletBounds()
        assertTrue("提现最小宽96", bounds.width / button.walletDp() >= 96f)
        assertTrue(bounds.height / button.walletDp() >= 48f)
        val style =
            rule
                .walletText("提现")
                .walletTextLayout()
                .layoutInput.style
        assertEquals(17f, style.fontSize.value)
        assertEquals(600, style.fontWeight?.weight)
    }

    @Test fun w9EstimateAndMonthUseTwoColumnsWithReceivedUnderLeft() {
        host.launch()
        val estimate = rule.walletText("¥56.78")
        val month = rule.walletText("10 月结算")
        val received = rule.walletText("其中已收货 ¥34.56")
        assertEquals(estimate.walletBounds().top, month.walletBounds().top, 2 * estimate.walletDp())
        assertTrue(month.walletBounds().left > estimate.walletBounds().right)
        assertTrue(received.walletBounds().top >= estimate.walletBounds().bottom)
        assertEquals(estimate.walletBounds().left, received.walletBounds().left, 2 * estimate.walletDp())
        assertEquals(
            host.rebate,
            estimate
                .walletTextLayout()
                .layoutInput.style.color,
        )
        assertEquals(
            host.primary,
            month
                .walletTextLayout()
                .layoutInput.style.color,
        )
        assertEquals(
            22f,
            estimate
                .walletTextLayout()
                .layoutInput.style.fontSize.value,
        )
        assertEquals(
            22f,
            month
                .walletTextLayout()
                .layoutInput.style.fontSize.value,
        )
        assertEquals(
            12f,
            received
                .walletTextLayout()
                .layoutInput.style.fontSize.value,
        )
    }

    @Test fun w10OverdueOccupiesRightProgressColumn() {
        host.launch("overdue")
        val estimate = rule.walletText("¥56.78")
        val overdue = rule.walletText("入账核对中")
        rule.walletText("结算进度").assertExists()
        assertEquals(
            22f,
            overdue
                .walletTextLayout()
                .layoutInput.style.fontSize.value,
        )
        assertEquals(estimate.walletBounds().top, overdue.walletBounds().top, 2 * estimate.walletDp())
        assertTrue(overdue.walletBounds().left > estimate.walletBounds().right)
        assertEquals(0, rule.walletTextCount("月结算"))
    }

    @Test fun w11ZeroReceivedRemainsVisibleAndNoMonthDoesNotInventProgress() {
        host.launch("received_zero")
        rule.walletText("其中已收货 ¥0").assertExists()
        assertEquals(0, rule.walletTextCount("最早预计"))
        assertEquals(0, rule.walletTextCount("月结算"))
        assertEquals(0, rule.walletTextCount("入账核对中"))
        assertEquals(0, rule.walletTextCount("暂缓入账"))
    }

    @Test fun w12HelpLabelRowStays18DpAndHelpFollowsActualTextEnd() {
        host.launch()
        val label = rule.walletText("预估收益")
        val help = rule.onNodeWithContentDescription("预估收益说明", useUnmergedTree = true)
        val row = outerBounds(commonLayout(label.fetchSemanticsNode().layoutInfo, help.fetchSemanticsNode().layoutInfo))
        assertEquals("48dp触控范围不撑高18dp视觉行", 18f, row.height / label.walletDp(), 2f)
        val glyph = label.walletLastGlyph()
        val helpIconLeft = help.walletBounds().center.x - 7 * label.walletDp()
        assertTrue("问号紧随文字末端，不随填充盒推到列尾", (helpIconLeft - glyph.right) / label.walletDp() in 0f..8f)
    }

    @Test fun w13TwoEntriesFillRowWithoutEmptyColumnsAndUseEightDpTopPadding() {
        host.launch()
        assertEntries(listOf(WalletTags.ENTRY_ORDERS, WalletTags.ENTRY_LEDGER))
    }

    @Test fun w13ThreeEntriesFillRowWhenBothEarningsGatesAllowIt() {
        host.env.earnings = true
        host.env.since = "1.0.0"
        host.launch()
        assertEntries(listOf(WalletTags.ENTRY_ORDERS, WalletTags.ENTRY_LEDGER, WalletTags.ENTRY_EARNINGS))
    }

    private fun assertEntries(tags: List<String>) {
        val entries = tags.map { rule.walletTag(it).fetchSemanticsNode() }
        val shared = commonLayout(entries.first().layoutInfo, entries.last().layoutInfo)
        val heroWidth = rule.walletTag(WalletTags.WALLET_HERO).walletBounds().width
        val card = generateSequence(shared) { it.parentInfo }.map(::outerBounds).first { it.width >= heroWidth - 1f }
        val density =
            entries
                .first()
                .layoutInfo.density.density
        assertWalletGap(8f, entries.first().boundsInRoot.top - card.top, density)
        assertWalletGap(8f, entries.first().boundsInRoot.left - card.left, density)
        assertWalletGap(8f, card.right - entries.last().boundsInRoot.right, density)
        entries.zipWithNext().forEach { (left, right) ->
            assertEquals(left.boundsInRoot.width, right.boundsInRoot.width, 1f)
            assertEquals(left.boundsInRoot.right, right.boundsInRoot.left, 1f)
            assertFalse(left.touchBoundsInRoot.overlaps(right.touchBoundsInRoot))
        }
    }
}
