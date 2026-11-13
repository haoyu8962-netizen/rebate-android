package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.numwrap.NumCopy
import com.couli.app.numwrap.NumOverflow
import com.couli.app.numwrap.NumReach
import com.couli.app.numwrap.NumSerialSpec
import com.couli.app.numwrap.NumToken
import com.couli.app.numwrap.NumWrap
import com.couli.app.product.assertNoTextOverflow
import com.couli.app.product.textLayout
import com.couli.core.designsystem.CouliTypography
import com.couli.feature.wallet.WalletTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        // #24 ⑥（FU-AND-NUM-WRAP，判据 1 / 测试交回 §3.1）：32 位流水号 200% 下整行也放不下，取首选口径「一行、框内横滑」（不再按字符折行）。
        // 先滚外层列表把号码所在的整段数字件露出来——对号码本身 performScrollTo 只会滚它自己的横滑框（w320 曾因此停在屏外）。
        val number = rule.recordText(SERIAL, "STRESS")
        rule.revealOuter(number)
        val what = "交易流水号 32 位（200%）"
        number.assertNotClippedVertically(what)
        // 旧「32位号码200%必须换行」（证明压力值真把号码挤满）→ 新口径：必须实际处在横滑态（单行、滚动范围 > 0、从开头露出、渐隐 + 指示器）
        number.assertScrollsFromStart(what)
        // 旧「整段落在可见框内」→ NumWrap.checkSerial：自然宽放得下整行必须一行，否则一行可滚动；整段不断、不截断、不缩字（footnote）、真滚到首尾
        val row = rule.onNodeWithTag(WalletTags.RECORD_DETAIL_PREFIX + "STRESS", useUnmergedTree = true).fetchSemanticsNode()
        val copyNode = rule.onNodeWithTag(WalletTags.RECORD_COPY, useUnmergedTree = true).fetchSemanticsNode()
        NumWrap.checkSerial(rule, number, NumSerialSpec(SERIAL, row, copyNode, what, FOOTNOTE))
        rule.onNodeWithTag(WalletTags.RECORD_COPY).performScrollTo().assertIsDisplayed()
        rule.recordText("复制", "STRESS").assertNoTextOverflow()
        // 旧「换行后的复制不能悬在号码中段」→ NumCopy 可视窗口口径（不压在可见号码上、完整可见、可点 ≥48dp）+ 【复制】排在号码上一行（与标签同行），
        // 不与横滑号码同一行
        NumCopy.assertCopyClear(rule, number, SERIAL, WalletTags.RECORD_COPY, what)
        assertCopyAboveNumber(number, what)
        rule.touchRecordEdge(WalletTags.RECORD_COPY, 3)
        assertEquals(listOf(SERIAL), host.env.copies)
        host.assertCalls("listWithdrawals", "getWithdrawal")
    }

    /** 滚外层列表：对号码所在整段数字件的外壳（声明 AmountOverflow 的节点，不在号码自己的横滑框里）performScrollTo。 */
    private fun ComposeContentTestRule.revealOuter(number: SemanticsNodeInteraction) {
        val shell = generateSequence(number.fetchSemanticsNode()) { it.parent }.firstOrNull { it.declaresOverflow() }
        assertNotNull("流水号没有走共用整段数字件（号码及其祖先上找不到语义 ${NumOverflow.KEY}）", shell)
        val id = checkNotNull(shell).id
        onNode(SemanticsMatcher("流水号整段数字件外壳") { it.id == id }, useUnmergedTree = true).performScrollTo()
    }

    private fun SemanticsNode.declaresOverflow(): Boolean = config.any { it.key.name == NumOverflow.KEY }

    /** 旧 assertNoTextOverflow 的纵向部分（换判据后仍保留）：号码上下都在可视范围内（外层列表确实滚到了）。 */
    private fun SemanticsNodeInteraction.assertNotClippedVertically(what: String) {
        val node = fetchSemanticsNode()
        val height = textLayout().size.height
        val top = NumReach.coordinatesOf(node).positionInRoot().y
        val rows = NumReach.windowY(node.layoutInfo.coordinates)
        assertTrue(
            "$what 上下被裁切（文字 ${top.toInt()}..${(top + height).toInt()}，可视 ${rows.start.toInt()}..${rows.endInclusive.toInt()} px）",
            top >= rows.start - 1f && top + height <= rows.endInclusive + 1f,
        )
    }

    /** 实际处在框内横滑态：单行、横滑范围 > 0、初始停在开头，AmountOverflow 三项（横滑 / 渐隐 / 指示器）全为 true。须在 checkSerial（会滚到两端）之前调用。 */
    private fun SemanticsNodeInteraction.assertScrollsFromStart(what: String) {
        val node = fetchSemanticsNode()
        assertEquals("$what 整行也放不下时单行、框内横滑（不折行）", 1, textLayout().lineCount)
        val scroller = NumReach.horizontalScroller(node)
        assertNotNull("$what 必须实际处在框内横滑态", scroller)
        val axis = checkNotNull(scroller).config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("$what 横滑范围必须 > 0（实际 ${axis.maxValue()}）", axis.maxValue() > 0f)
        assertEquals("$what 横滑初始停在开头", 0f, axis.value(), .5f)
        val state = NumOverflow.read(node)
        assertNotNull("$what 没有走共用整段数字件（找不到语义 ${NumOverflow.KEY}）", state)
        val flags = checkNotNull(state)
        assertTrue("$what 横滑须声明 scrollable / fadeEnd / indicator（实际 $flags）", flags.scrollable && flags.fadeEnd && flags.indicator)
    }

    /** 【复制】文字整体在号码文字上方（与标签同行），不与横滑号码同一行、不悬在号码中段。 */
    private fun assertCopyAboveNumber(
        number: SemanticsNodeInteraction,
        what: String,
    ) {
        val copyText = rule.onAllNodes(NumWrap.HAS_LAYOUT and NumWrap.within(WalletTags.RECORD_COPY), useUnmergedTree = true)[0]
        val copyLayout = copyText.textLayout()
        val copyBottom = NumReach.coordinatesOf(copyText.fetchSemanticsNode()).positionInRoot().y + copyLayout.getLineBottom(0)
        val numberTop = NumReach.coordinatesOf(number.fetchSemanticsNode()).positionInRoot().y + number.textLayout().getLineTop(0)
        assertTrue("$what：【复制】不能与横滑号码同一行或悬在号码中段（复制bottom=$copyBottom，号码top=$numberTop）", copyBottom <= numberTop + 1f)
    }

    private companion object {
        /** specs visualWithdrawRecords.details.stress.channel_order_id。 */
        const val SERIAL = "12345678901234567890123456789012"
        val FOOTNOTE = NumToken(CouliTypography.footnote.fontSize, 2f)
    }
}
