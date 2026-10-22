package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.commonLayout
import com.couli.app.product.outerBounds
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
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawRecordsV2TargetsTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawRecordsVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun serviceVisualRowIs44AndTargetDoesNotRaiseItTo48() {
        host.launch("pending", focus = "PENDING")
        val hint = rule.recordText(host.texts.text("withdraw.irrevocable"), "PENDING").fetchSemanticsNode()
        val button = rule.onNodeWithTag(WalletTags.RECORD_CS).fetchSemanticsNode()
        val row = outerBounds(commonLayout(hint.layoutInfo, button.layoutInfo))
        val d = button.layoutInfo.density.density
        assertEquals("客服视觉行按画板44dp，命中扩展不得参与父行测量", 44f, row.height / d, 1f)
        assertEquals("联系客服视觉按钮44dp", 44f, button.recordUnclippedBounds().height / d, 1f)
        assertTargetSeparated(WalletTags.RECORD_CS)
    }

    @Test fun copyVisual44DoesNotInflateItsLabelAndNumberRow() {
        host.launch("paid", focus = "PAID")
        val label = rule.recordText(host.texts.text("withdraw_detail.trade_no"), "PAID").fetchSemanticsNode()
        val number = rule.onNodeWithTag(WalletTags.RECORD_TRADE_NO).fetchSemanticsNode()
        val copy = rule.onNodeWithTag(WalletTags.RECORD_COPY).fetchSemanticsNode()
        val row = outerBounds(commonLayout(label.layoutInfo, copy.layoutInfo))
        val textColumn = outerBounds(commonLayout(label.layoutInfo, number.layoutInfo))
        val d = copy.layoutInfo.density.density
        assertEquals("复制视觉按钮按画板44dp", 44f, copy.recordUnclippedBounds().height / d, 1f)
        assertEquals("流水号两行或44dp决定视觉行高，48dp命中区不参与", maxOf(44f, textColumn.height / d), row.height / d, 1f)
        assertTargetSeparated(WalletTags.RECORD_COPY)
    }

    @Test fun adjacentExpandedCardsHaveSeparateTargetsAndAllEdgesReachOnlyTheirOwnAction() {
        host.api.answer =
            WithdrawRecordsVisualFixtures.ok(
                WithdrawRecordsV2Fixtures.page(
                    listOf(WithdrawRecordsVisualFixtures.detail("paid"), WithdrawRecordsVisualFixtures.detail("failed")),
                ),
            )
        host.launch()
        host.toggle("PAID")
        host.toggle("FAILED")
        repeat(4) { edge ->
            assertTargetSeparated(WalletTags.RECORD_COPY)
            rule.touchRecordEdge(WalletTags.RECORD_COPY, edge)
            assertEquals(List(edge + 1) { "20260928200040011100" }, host.env.copies)
            assertEquals("复制边缘不得点中客服", edge, host.env.contacts.size)
            assertTargetSeparated(WalletTags.RECORD_CS)
            rule.touchRecordEdge(WalletTags.RECORD_CS, edge)
            assertEquals(List(edge + 1) { "WithdrawRecords" }, host.env.contacts)
            assertEquals("客服边缘不得点中复制", edge + 1, host.env.copies.size)
            assertEquals("不能抢到收起动作", setOf("PAID", "FAILED"), host.controller.state.value.expanded)
            assertTrue("不得误打开修改账号", host.env.opened.isEmpty())
            host.assertCalls("listWithdrawals", "getWithdrawal", "getWithdrawal")
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun largeTextNarrowServiceTargetKeeps48AndAllEdgesWithoutOverlap() {
        host.launch("failed", focus = "FAILED", fontScale = 2f)
        repeat(4) { edge ->
            assertTargetSeparated(WalletTags.RECORD_CS)
            rule.touchRecordEdge(WalletTags.RECORD_CS, edge)
            assertEquals(List(edge + 1) { "WithdrawRecords" }, host.env.contacts)
            assertEquals(setOf("FAILED"), host.controller.state.value.expanded)
            assertTrue(host.env.opened.isEmpty())
            assertTrue(host.env.copies.isEmpty())
            host.assertCalls("listWithdrawals", "getWithdrawal")
        }
    }

    private fun assertTargetSeparated(tag: String) {
        val target = rule.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().fetchSemanticsNode()
        val bounds = target.touchBoundsInRoot
        val d = target.layoutInfo.density.density
        assertTrue("$tag 真实命中区≥48×48dp", bounds.width >= 48 * d && bounds.height >= 48 * d)
        rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().filter { it.id != target.id }.forEach {
            assertRecordTargetsSeparate(bounds, it.touchBoundsInRoot)
        }
    }
}
