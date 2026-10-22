package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.commonLayout
import com.couli.app.product.outerBounds
import com.couli.app.product.productPixels
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
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawRecordsVisualStructureTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawRecordsVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w18ListStartsEightDpBelowNavAndSixteenFromSides() {
        host.launch("same_month")
        val list = rule.onNodeWithTag(WalletTags.RECORDS_LIST).fetchSemanticsNode()
        val month = rule.onNodeWithText("2026 年 10 月", useUnmergedTree = true).fetchSemanticsNode()
        val d = month.layoutInfo.density.density
        assertEquals(8f, (month.boundsInRoot.top - list.boundsInRoot.top) / d, 1f)
        assertEquals(
            16f,
            (
                rule
                    .record("PENDING")
                    .fetchSemanticsNode()
                    .boundsInRoot.left - list.boundsInRoot.left
            ) / d,
            1f,
        )
    }

    @Test fun w19MonthIsTwelveRegularSecondaryAndInsetFour() {
        host.launch("same_month")
        val month = rule.onNodeWithText("2026 年 10 月", useUnmergedTree = true)
        val layout = month.textLayout()
        assertEquals(12f, layout.layoutInput.style.fontSize.value, .01f)
        assertEquals(
            400,
            layout.layoutInput.style.fontWeight
                ?.weight ?: 400,
        )
        assertEquals(host.colors.getValue("text.secondary"), layout.layoutInput.style.color)
        val node = month.fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsProperties.Heading))
        assertEquals(
            4f,
            (
                node.boundsInRoot.left -
                    rule
                        .record("PENDING")
                        .fetchSemanticsNode()
                        .boundsInRoot.left
            ) /
                node.layoutInfo.density.density,
            1f,
        )
    }

    @Test fun w20SameMonthRowsShareOneSurfaceWithoutTwelveDpCanvasGaps() {
        host.launch("same_month")
        val a = rule.record("PENDING").fetchSemanticsNode()
        val b = rule.record("FAILED").fetchSemanticsNode()
        val common = commonLayout(a.layoutInfo, b.layoutInfo)
        val list = rule.onNodeWithTag(WalletTags.RECORDS_LIST).fetchSemanticsNode()
        assertTrue("同月必须有列表以内的共同卡片", outerBounds(common).height < list.boundsInRoot.height)
        val d = a.layoutInfo.density.density
        assertTrue("收起行间只能有分隔线", b.boundsInRoot.top - a.boundsInRoot.bottom <= 2 * d)
        val title = rule.recordText("提现 ¥10", "PENDING").fetchSemanticsNode()
        assertEquals("收起行上留白12", 12f, (title.boundsInRoot.top - a.boundsInRoot.top) / d, 1f)
        val x = a.boundsInRoot.left + 20 * d
        val gap = Rect(x, a.boundsInRoot.bottom - d, x + 30 * d, b.boundsInRoot.top + d)
        val pixels = rule.productPixels()
        assertEquals("连接处不能露出画布色", 0, pixels.recordColorCount(gap, host.colors.getValue("background.canvas").toArgb()))
    }

    @Test fun w20MonthBoundaryIsShanghaiAndRowsHaveUniqueIds() {
        host.launch("boundary")
        rule.onNodeWithText("2026 年 10 月", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(
            listOf("BOUNDARY"),
            host.controller.state.value.items
                .map { it.withdrawalId },
        )
        host.assertCalls("listWithdrawals")
    }

    @Test fun w22CollapsedOnlyFirstLineButSpokenNameRetainsFrozenAmount() {
        host.launch("pending")
        val first = WalletDraftTexts.text("withdraw.sla_default")
        val item =
            host.controller.state.value.items
                .single()
        val lines =
            com.couli.core.wallet.WithdrawalText
                .render(
                    com.couli.core.wallet.WithdrawalText
                        .of(item, null),
                    WalletDraftTexts,
                )
        rule.recordText(lines.first(), "PENDING").assertIsDisplayed()
        assertTrue("冻结句只进读屏或展开", rule.drawnTexts().none { it == lines[1] })
        listOf(false, true).forEach { unmerged ->
            val names =
                rule
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasContentDescription(lines[1], substring = true),
                        unmerged,
                    ).fetchSemanticsNodes()
            assertEquals("整行完整读屏且不重复：$first", 1, names.size)
        }
    }

    @Test fun w22CollapsedFailureKeepsFirstRenderedSentenceIncludingSemicolon() {
        host.launch("failed")
        rule.recordText("收款支付宝账号状态异常；¥8 已退回可提现余额，可修改收款账号后重新提现", "FAILED").assertIsDisplayed()
        assertEquals(
            host.colors.getValue("text.primary"),
            rule
                .recordText(
                    "收款支付宝账号状态异常；¥8 已退回可提现余额，可修改收款账号后重新提现",
                    "FAILED",
                ).textLayout()
                .layoutInput.style.color,
        )
    }

    @Test fun w23ExpandedItemLeavesMonthSurfaceAndHasSeparateLargeHeader() {
        host.launch("same_month")
        host.toggle("FAILED")
        val card = rule.record("FAILED").fetchSemanticsNode()
        val previous = rule.record("PENDING").fetchSemanticsNode()
        val d = card.layoutInfo.density.density
        assertTrue("展开卡与上一个收起区隔开", card.boundsInRoot.top - previous.boundsInRoot.bottom >= 10 * d)
        val values =
            rule.onAllNodes(
                androidx.compose.ui.test
                    .hasText("¥8") and
                    androidx.compose.ui.test
                        .hasAnyAncestor(
                            androidx.compose.ui.test
                                .hasTestTag(WalletTags.RECORD_PREFIX + "FAILED"),
                        ),
                true,
            )
        assertTrue("金额头独立，不能仍拼申请金额", values.fetchSemanticsNodes().size >= 2)
        val style = values[0].textLayout().layoutInput.style
        assertEquals(28f, style.fontSize.value, .01f)
        assertEquals(700, style.fontWeight?.weight)
        assertTrue(style.fontFeatureSettings.orEmpty().contains("tnum"))
        val labels =
            rule.onAllNodes(
                androidx.compose.ui.test
                    .hasText("申请金额") and
                    androidx.compose.ui.test
                        .hasAnyAncestor(
                            androidx.compose.ui.test
                                .hasTestTag("record_FAILED"),
                        ),
                true,
            )
        assertEquals(
            "头部标签仍为12sp",
            12f,
            labels[0]
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
    }

    @Test fun w30EndLabelIsCenteredInList() {
        host.launch("pending")
        val end = rule.onNodeWithTag(WalletTags.RECORDS_END, useUnmergedTree = true).textLayout()
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, end.layoutInput.style.textAlign)
    }
}
