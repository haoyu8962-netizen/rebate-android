package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.assertNoTextOverflow
import com.couli.app.product.textLayout
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
class WithdrawRecordsVisualAdaptationTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawRecordsVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w35Font200At390KeepsLongAmountsAndTradeNumberComplete() = large(false)

    @Test fun w35Font200DarkAt390KeepsLongAmountsAndTradeNumberComplete() = large(true)

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun w35Font200At320KeepsLongAmountsAndTradeNumberComplete() = large(false)

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun w28Font200At320KeepsChangeAndSummaryTargetsSeparateAndClickable() {
        host.launch("failed", fontScale = 2f, focus = "FAILED")
        rule.onNodeWithTag(WalletTags.RECORD_CHANGE_ACCOUNT).performScrollTo()
        val button = rule.onNodeWithTag(WalletTags.RECORD_CHANGE_ACCOUNT).fetchSemanticsNode()
        val d = button.layoutInfo.density.density
        assertTrue(button.touchBoundsInRoot.width >= 48 * d && button.touchBoundsInRoot.height >= 48 * d)
        rule.recordText("修改收款账号", "FAILED").assertNoTextOverflow()
        repeat(4) { edge ->
            rule.touchRecordEdge(WalletTags.RECORD_CHANGE_ACCOUNT, edge)
            assertEquals(edge + 1, host.env.opened.size)
            assertEquals(setOf("FAILED"), host.controller.state.value.expanded)
        }
        host.assertCalls("listWithdrawals", "getWithdrawal")
    }

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun w35WidePageListMonthAndDetailsShareCenteredSixHundredColumn() {
        host.launch("paid", focus = "PAID")
        val page = rule.onNodeWithTag(WalletTags.RECORDS_PAGE).fetchSemanticsNode()
        val list = rule.onNodeWithTag(WalletTags.RECORDS_LIST).fetchSemanticsNode()
        val d = page.layoutInfo.density.density
        assertEquals(600f, list.boundsInRoot.width / d, 1f)
        assertEquals(page.boundsInRoot.center.x, list.boundsInRoot.center.x, d)
        val card = rule.record("PAID").fetchSemanticsNode()
        assertEquals(568f, card.boundsInRoot.width / d, 1f)
        assertEquals(list.boundsInRoot.center.x, card.boundsInRoot.center.x, d)
    }

    private fun large(dark: Boolean) {
        host.launch("stress", dark = dark, fontScale = 2f, focus = "STRESS")
        val detail = WalletTags.RECORD_DETAIL_PREFIX + "STRESS"
        listOf("¥9999999999.99", "¥1234567.89", "¥9876543.21", "¥8765432109.87").forEach { value ->
            val text = rule.onNode(hasText(value) and hasAnyAncestor(hasTestTag(detail)), true).performScrollTo()
            assertEquals(
                2f,
                text
                    .textLayout()
                    .layoutInput.density.fontScale,
                .001f,
            )
            assertEquals(
                14f,
                text
                    .textLayout()
                    .layoutInput.style.fontSize.value,
                .01f,
            )
            text.assertNoTextOverflow()
        }
        val number = rule.recordText("12345678901234567890123456789012", "STRESS").performScrollTo()
        number.assertNoTextOverflow()
        val layout = number.textLayout()
        assertTrue("32位号码200%必须换行", layout.lineCount >= 2)
        val copy = rule.onNodeWithTag(WalletTags.RECORD_COPY).performScrollTo().assertIsDisplayed()
        rule.recordText("复制", "STRESS").assertNoTextOverflow()
        val lastLineBottom = number.fetchSemanticsNode().boundsInRoot.top + layout.getLineBottom(layout.lineCount - 1)
        assertTrue("换行后的复制不能悬在号码中段", copy.fetchSemanticsNode().boundsInRoot.bottom >= lastLineBottom)
        rule.touchRecordEdge(WalletTags.RECORD_COPY, 3)
        assertEquals(listOf("12345678901234567890123456789012"), host.env.copies)
        host.assertCalls("listWithdrawals", "getWithdrawal")
    }
}
