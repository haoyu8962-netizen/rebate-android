package com.couli.app.numwrap

import android.app.Application
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.wallet.visual.PayoutIdentityPage
import com.couli.app.wallet.visual.PayoutIdentityVisualFixtures
import com.couli.app.wallet.visual.PayoutIdentityVisualHost
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * D10-1513 页面用例（测试 r7，按 D10-1521 退路调整）：收款账号页绑定后的当前账号（PayoutAccountScreen BoundCard，`payout_bound`），w320 × 200%。
 * 退路口径：WholeNumberText 回到 r6（最长不可断段放不下就整段横滑）；只在这个绘制点，不含任何数字的账号串改用普通可换行 Text。
 * - 夹具 boundStress 的脱敏邮箱（不含数字）：换行成多行、不横滑（没有 AmountOverflow，或有也三项 false；没有横滑范围）、不裁切（每个字符都在可视窗口内）、
 *   读屏整串（文字节点是完整串；合并树里读得到原串或「星号」读法）、不缩字（amountTitle 令牌、真 200%）。先核前提：最长不可断段宽于视口（走 r6 共用件必横滑）。
 * - 夹具 boundAlipay 的 138****5678（含数字）：照旧走共用整段数字件（AmountOverflow 语义存在），整段不断、不缩字、首尾可达、读屏完整。
 * 不改 PayoutIdentityVisualAdaptationTest。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w320dp-h844dp-xhdpi", fontScale = 2f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumWrapPayoutEmailPageTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PayoutIdentityVisualHost(rule) }

    @After fun close() = host.close()

    @Test fun emailAccountW320Font200WrapsWithoutScrollOrClip() {
        launch("boundStress")
        val node = account(EMAIL)
        val semantics = node.fetchSemanticsNode()
        val result = NumWrap.layout(node)
        val viewport = NumReach.window(semantics.layoutInfo.coordinates)
        val unbreakable = result.multiParagraph.intrinsics.minIntrinsicWidth
        assertTrue(
            "前提：邮箱最长不可断段 ${unbreakable.toInt()} px 须宽于视口 ${(viewport.endInclusive - viewport.start).toInt()} px" +
                "（否则杀不掉「绘制点仍用 WholeNumberText」）",
            unbreakable > viewport.endInclusive - viewport.start,
        )
        val where = "收款账号页当前账号 邮箱（w320×200%）"
        assertEquals("$where：文字节点不是完整的邮箱（读屏读不到整串）", EMAIL, result.layoutInput.text.text)
        assertTrue("$where：合并后的无障碍树里读不到整串（原串或「星号」读法）", heard(semantics, listOf(EMAIL, spoken(EMAIL))))
        val state = NumOverflow.read(semantics)
        assertTrue(
            "$where：不含数字的邮箱不许横滑（${NumOverflow.KEY} = $state）",
            state == null || state == NumOverflow.State(false, false, false),
        )
        val axis = NumReach.horizontalScroller(semantics)?.config?.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
        assertTrue("$where：不含数字的邮箱不许横滑（横滑范围 ${axis?.maxValue()}）", axis == null || axis.maxValue() <= 0f)
        assertTrue("$where：须换行成多行（实际 ${result.lineCount} 行：${NumWrap.lines(result)}）", result.lineCount > 1)
        assertFalse("$where：被省略号截断（裁切）", (0 until result.lineCount).any(result::isLineEllipsized))
        assertFalse("$where：横向溢出（裁切）", result.didOverflowWidth)
        assertFalse("$where：纵向被截（裁切）", result.didOverflowHeight)
        val origin = semantics.layoutInfo.coordinates.positionInRoot()
        val ys = NumReach.windowY(semantics.layoutInfo.coordinates)
        EMAIL.indices.forEach { offset ->
            val box = result.getBoundingBox(offset).translate(origin)
            assertTrue(
                "$where：第 ${offset + 1} 个字符「${EMAIL[offset]}」在可视范围外（裁切；字符 $box，可视 x $viewport、y $ys）",
                box.left >= viewport.start - 1f && box.right <= viewport.endInclusive + 1f && box.top >= ys.start - 1f &&
                    box.bottom <= ys.endInclusive + 1f,
            )
        }
        assertEquals("$where：缩字（字号须是 amountTitle 令牌）", CouliTextStyles.amountTitle.fontSize, result.layoutInput.style.fontSize)
        assertEquals("$where：须真 200%", FONT_SCALE, result.layoutInput.density.fontScale, 0.01f)
    }

    /** 防止绘制点被改成一律普通 Text：含数字的账号照旧走共用整段数字件（文字或其祖先上有 AmountOverflow 语义）。 */
    @Test fun digitAccountW320Font200StillUsesTheWholeNumberComponent() {
        launch("boundAlipay")
        val state = NumOverflow.read(account(MASKED).fetchSemanticsNode())
        assertNotNull("收款账号页当前账号 138****5678：含数字的账号必须走 WholeNumberText（找不到 ${NumOverflow.KEY}）", state)
    }

    @Test fun digitAccountW320Font200StaysWhole() {
        launch("boundAlipay")
        val where = "收款账号页当前账号 138****5678（w320×200%）"
        val field = NumField(MASKED, where, NumWrap.within(WalletTags.PAYOUT_BOUND), TOKEN, exact = true)
        // 横滑与否随宽度而定：只要求语义与实际一致、渐隐 / 指示器随横滑出现，读屏完整
        NumHint.assertHint(rule, NumHintSpec(field, row = NumWrap.within(WalletTags.PAYOUT_BOUND), spoken = spoken(MASKED), expect = null))
        NumWrap.assertField(rule, field)
    }

    private fun launch(fixture: String) {
        host.api.payout = PayoutIdentityVisualFixtures.ok(PayoutIdentityVisualFixtures.payout(fixture))
        host.launch(PayoutIdentityPage.PAYOUT, fontScale = FONT_SCALE)
        rule.onNodeWithTag(WalletTags.PAYOUT_BOUND, useUnmergedTree = true).performScrollTo()
    }

    private fun account(value: String): SemanticsNodeInteraction =
        rule
            .onNode(hasText(value) and NumWrap.HAS_LAYOUT and NumWrap.within(WalletTags.PAYOUT_BOUND), useUnmergedTree = true)
            .performScrollTo()

    /** 页面给脱敏账号的读屏写法：「*」读作「星号」（WholeNumberText 的 textModifier 上的 contentDescription）。 */
    private fun spoken(value: String): String =
        WalletDraftTexts.text("wallet.star_spoken").takeIf { it.isNotEmpty() }?.let { value.replace("*", it) } ?: value

    /** 合并后的无障碍树里，与 [text] 位置重叠、未隐藏的节点读出（contentDescription 优先，否则文字）含 [values] 之一。 */
    private fun heard(
        text: SemanticsNode,
        values: List<String>,
    ): Boolean =
        rule
            .onAllNodes(SemanticsMatcher("任意节点") { true }, useUnmergedTree = false)
            .fetchSemanticsNodes()
            .filter { it.boundsInRoot.overlaps(text.boundsInRoot) }
            .filterNot { node ->
                generateSequence(node) { it.parent }.any { it.config.contains(SemanticsProperties.HideFromAccessibility) }
            }.any { node ->
                val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                val read =
                    descriptions.ifEmpty {
                        node.config
                            .getOrNull(SemanticsProperties.Text)
                            .orEmpty()
                            .map { it.text }
                    }
                read.any { said -> values.any { said.contains(it) } }
            }

    private companion object {
        const val FONT_SCALE = 2f

        /** 夹具 boundStress 的脱敏邮箱（specs/wallet-vectors.json visualPayout.payout.boundStress.masked_account）。 */
        const val EMAIL = "zhangsan.payout.visual.stress***@example-mail.com"

        /** 夹具 boundAlipay 的脱敏手机号。 */
        const val MASKED = "138****5678"
        val TOKEN = NumToken(CouliTextStyles.amountTitle.fontSize, FONT_SCALE)
    }
}
