package com.couli.core.designsystem

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 组件级（FU-AND-NUM-WRAP / FU-AND-ADAPT-CHECK，测试 v2）：
 * - 缺陷 #2：[WalletHeroCard]（钱包、我的共用）的大字金额：卡片容器之下完整值恰好 1 处，200% 下首尾同一行（整体换行或框内横向滚动二选一，滚动须真能滚到），
 *   amountDisplay 令牌、不缩字。卡宽取真实页面：屏宽 − 左右各 16。
 * - 判据 4：[CodeResendRow]（CodeInput 下方的发码入口）在 200% 下入口自身有围住文字的底色或描边、Role.Button、命中区 ≥48dp、body 令牌。
 *   D10-1223 口径（v7.1）：倒计时态（countdown 不为空）在 200% 下也要有按钮外观，不可点时用禁用按钮表示（Role.Button 带 Disabled），
 *   倒计时文字沿用 captionAmount 令牌、不缩字。
 * - 共用 [AmountGrid]（订单列表 / 详情、钱包、我的）：数值文字带 clearAndSetSemantics、不在语义树里，只能像素判（[NumBands]，三条守卫：
 *   短值对照与压力值都恰好 4 条墨迹带——非空且没多出一行；数值墨迹带高与独立基准（同样式不受约束单行排出的同一串值）相差 ≤2px——长短值一起缩字也拦；
 *   数值墨迹宽不小于基准 −2px——逐格完整，单行裁切会被拦）。框内横向滚动这条像素证明不了，AmountGrid 请用整段换行或上下排。
 * - 测试 r7（D10-1513 / D10-1521 退路）：真实 [WholeNumberText] 在 w320 × 200% 下，含数字的超宽串整串单行横滑、AmountOverflow 三项 true、
 *   读屏完整、数字段不跨行——32 位流水号，以及字母与数字粘连、数字段放得下而整串放不下的串（AAAAAAAAAA1234567890）。
 *   每条先核前提（整串单行宽于视口；粘连串的数字段单行放得下）。带数字的脱敏邮箱 user2024***@mail.com 按判据 1 口径（D10-1538）：
 *   数字段 2024*** 不跨行、不省略、不缩字、读屏完整，不要求整串横滑（Robolectric 下断行器在「mail.」后可断，最长段放得下）。不含数字的账号串换行由收款账号绘制点负责，见 app 的 NumWrapPayoutEmailPageTest。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumWrapDesignSystemComponentTest {
    @get:Rule val rule = createComposeRule()

    @Test fun heroW320MaxAmountLight() = hero(MAX, 320, dark = false)

    @Test fun heroW320QaAmountLight() = hero(QA, 320, dark = false)

    @Test fun heroW390MaxAmountLight() = hero(MAX, 390, dark = false)

    @Test fun heroW390MaxAmountDark() = hero(MAX, 390, dark = true)

    @Test fun heroW320MaxAmountCompactHeaderDark() = hero(MAX, 320, dark = true, compact = true)

    // D10-1265：真实共用件 WalletHeroCard 上的横滑提示契约（语义 AmountOverflow + 像素粗检），极端值有提示、常规金额没有
    @Test fun heroHintExtremeW320() = heroHint(MAX, 320, expect = true)

    @Test fun heroHintRegularW320() = heroHint(REGULAR, 320, expect = false)

    @Test fun heroHintRegularW390() = heroHint(REGULAR, 390, expect = false)

    @Test fun resendRowW320Font200LightLooksLikeButton() = resend(320, dark = false)

    @Test fun resendRowW390Font200DarkLooksLikeButton() = resend(390, dark = true)

    @Test fun resendRowCountdownW320Font200LightIsADisabledButton() = countdown(320, dark = false)

    @Test fun resendRowCountdownW390Font200DarkIsADisabledButton() = countdown(390, dark = true)

    @Test fun amountGridBodyW320MaxAmount() = grid(AmountValueSize.BODY, MAX, 320)

    @Test fun amountGridTitleW320MaxAmount() = grid(AmountValueSize.TITLE, MAX, 320)

    @Test fun amountGridHeadingW320MaxAmount() = grid(AmountValueSize.HEADING, MAX, 320)

    @Test fun amountGridHeadingW390DetailStress() = grid(AmountValueSize.HEADING, "¥99999.99", 390)

    // ---------------------------------------------------------------- 测试 r7：含数字的超宽串整串横滑（w320 × 200%）

    @Test fun digitSerialStillScrollsWholeAtW320Font200() = scrolls(SERIAL_32, "32 位流水号")

    /** 字母与数字粘连（D10-1521）：10 位数字放得下、整串放不下时，整串单行横滑、AmountOverflow 三项 true、数字不跨行。 */
    @Test fun letterDigitGlueScrollsWholeAtW320Font200() = scrolls(GLUE, "字母数字粘连", digits = GLUE_DIGITS)

    /**
     * 脱敏邮箱里粘着数字（D10-1538，判据 1 口径）：数字段「2024***」不跨行、不省略、不缩字、首尾可达、读屏完整；
     * 整串换行还是横滑不钉（Robolectric 下断行器在「mail.」后可断）。数字段被劈开时由 [NumWrap.checkRun] 的「从中间断行」断言拦下。
     */
    @Test fun maskedEmailWithDigitsKeepsDigitRunWholeAtW320Font200() = keepsDigitRun(GLUE_EMAIL, "带数字的脱敏邮箱", GLUE_EMAIL_DIGITS)

    private fun hero(
        amount: String,
        screenDp: Int,
        dark: Boolean,
        compact: Boolean = false,
    ) {
        content(screenDp, dark) {
            WalletHeroCard(
                texts = WalletHeroTexts("可提现余额", amount, "可提现余额 $amount", "提现", "提现记录", "隐藏金额", "显示金额"),
                onWithdraw = {},
                onToggleHidden = {},
                onRecords = {},
                compactHeader = compact,
                modifier = Modifier.testTag(CARD),
            )
        }
        val where = "WalletHeroCard（w$screenDp×200%，${if (dark) "深" else "浅"}）"
        val token = NumToken(CouliTextStyles.amountDisplay.fontSize, FONT_SCALE)
        NumWrap.assertField(rule, NumField(amount, where, NumWrap.within(CARD), token, exact = true))
    }

    private fun heroHint(
        amount: String,
        screenDp: Int,
        expect: Boolean,
    ) {
        content(screenDp, dark = false) {
            WalletHeroCard(
                texts = WalletHeroTexts("可提现余额", amount, "可提现余额 $amount", "提现", "提现记录", "隐藏金额", "显示金额"),
                onWithdraw = {},
                onToggleHidden = {},
                onRecords = {},
                modifier = Modifier.testTag(CARD),
            )
        }
        val card = NumWrap.within(CARD)
        val token = NumToken(CouliTextStyles.amountDisplay.fontSize, FONT_SCALE)
        val field = NumField(amount, "WalletHeroCard 横滑提示（w$screenDp×200%）", card, token, exact = true)
        NumHint.assertHint(rule, NumHintSpec(field, row = card, expect = expect))
        NumWrap.assertField(rule, field)
    }

    private fun resend(
        screenDp: Int,
        dark: Boolean,
    ) {
        content(screenDp, dark) {
            CodeResendRow(hint = "收不到验证码？", resendText = "重新获取", onResend = {}, modifier = Modifier.testTag(CARD))
        }
        val where = "CodeResendRow 重新获取（w$screenDp×200%，${if (dark) "深" else "浅"}）"
        SendCodeLook.assertLooksLikeButton(rule, rule.onNodeWithTag(CARD, useUnmergedTree = true).fetchSemanticsNode(), where)
        val text = NumWrap.nodesFor(rule, "重新获取", NumWrap.within(CARD))
        // CouliButton TEXT / STANDARD 的字号令牌是 body（bodySemibold）；改成按钮外观也不许缩成更小的令牌
        val style = NumWrap.layout(text).layoutInput.style
        assertEquals("$where：不许缩字（body 令牌）", CouliTypography.body.fontSize, style.fontSize)
    }

    private fun countdown(
        screenDp: Int,
        dark: Boolean,
    ) {
        content(screenDp, dark) {
            CodeResendRow(hint = "收不到验证码？", resendText = "重新获取", onResend = {}, modifier = Modifier.testTag(CARD), countdown = COUNTDOWN)
        }
        val where = "CodeResendRow 倒计时（w$screenDp×200%，${if (dark) "深" else "浅"}）"
        SendCodeLook.assertDisabledButton(rule, rule.onNodeWithTag(CARD, useUnmergedTree = true).fetchSemanticsNode(), where)
        val style = NumWrap.layout(NumWrap.nodesFor(rule, COUNTDOWN, NumWrap.within(CARD))).layoutInput.style
        assertEquals("$where：不许缩字（captionAmount 令牌）", CouliTextStyles.captionAmount.fontSize, style.fontSize)
    }

    private fun grid(
        size: AmountValueSize,
        value: String,
        screenDp: Int,
    ) {
        content(screenDp, dark = false) {
            Column {
                AmountGrid(cells = cells("¥1.00"), valueSize = size, modifier = Modifier.testTag(CONTROL))
                Spacer(Modifier.height(24.dp))
                AmountGrid(cells = cells(value), valueSize = size, modifier = Modifier.testTag(STRESS))
                Spacer(Modifier.height(24.dp))
                // 独立字号基准：同一数值样式、不受宽度约束的单行排出
                Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true)) {
                    Text(value, style = size.style, softWrap = false, modifier = Modifier.testTag(REF))
                }
            }
        }
        val where = "AmountGrid ${size.name}（w$screenDp×200%，$value）"
        NumBands.assertAmountRows(rule, tagged(CONTROL) to tagged(STRESS), tagged(REF), where)
    }

    private fun content(
        screenDp: Int,
        dark: Boolean,
        body: @Composable () -> Unit,
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, FONT_SCALE)) {
                CouliTheme(darkTheme = dark) {
                    Column(
                        Modifier
                            .width(screenDp.dp)
                            .background(CouliColors.backgroundCanvas)
                            .padding(horizontal = 16.dp),
                    ) { body() }
                }
            }
        }
        rule.waitForIdle()
    }

    /** w320 × 200% 下排 [value]（含数字、整串放不下）：整串单行横滑、AmountOverflow 三项 true、读屏完整、数字段不跨行、不缩字。 */
    private fun scrolls(
        value: String,
        what: String,
        digits: String? = null,
    ) {
        content(320, dark = false) {
            Column {
                Box(Modifier.fillMaxWidth().testTag(CARD)) { WholeNumberText(value, style = STYLE) }
                // 前提用的独立基准：同样式、不受宽度约束的单行数字段
                if (digits != null) {
                    Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true)) {
                        Text(digits, style = STYLE, softWrap = false, modifier = Modifier.testTag(REF))
                    }
                }
            }
        }
        val viewport = tagged(CARD).size.width
        val natural =
            NumWrap
                .layout(textNode(value))
                .multiParagraph.intrinsics.maxIntrinsicWidth
        assertTrue("$what 前提：整串单行 ${natural.toInt()} px 须宽于视口 $viewport px", natural > viewport)
        if (digits != null) {
            val run = NumWrap.layout(rule.onNodeWithTag(REF, useUnmergedTree = true)).size.width
            assertTrue("$what 前提：数字段单行 $run px 须放得下视口 $viewport px", run <= viewport)
        }
        val field = NumField(value, "WholeNumberText $what（w320×200%）", NumWrap.within(CARD), TOKEN, exact = true)
        NumHint.assertHint(rule, NumHintSpec(field, row = NumWrap.within(CARD), expect = true))
        NumWrap.assertField(rule, field)
        digits?.let { NumWrap.checkRun(rule, textNode(value), it, "${field.where} 里的「$it」", TOKEN) }
    }

    /** w320 × 200% 下排 [value]：前提是数字段 [digits] 单行放得下视口；文字节点是完整串、读屏读得到（横滑与否只核一致），[digits] 整段不断、不缩字。 */
    private fun keepsDigitRun(
        value: String,
        what: String,
        digits: String,
    ) {
        content(320, dark = false) {
            Column {
                Box(Modifier.fillMaxWidth().testTag(CARD)) { WholeNumberText(value, style = STYLE) }
                // 前提用的独立基准：同样式、不受宽度约束的单行数字段
                Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true)) {
                    Text(digits, style = STYLE, softWrap = false, modifier = Modifier.testTag(REF))
                }
            }
        }
        val viewport = tagged(CARD).size.width
        val run = NumWrap.layout(rule.onNodeWithTag(REF, useUnmergedTree = true)).size.width
        assertTrue("$what 前提：数字段单行 $run px 须放得下视口 $viewport px", run <= viewport)
        val node = textNode(value)
        val where = "WholeNumberText $what（w320×200%）"
        assertEquals(
            "$where：文字节点须是完整串（读屏读得到整串）",
            value,
            NumWrap
                .layout(node)
                .layoutInput.text.text,
        )
        val field = NumField(value, where, NumWrap.within(CARD), TOKEN, exact = true)
        // 读屏完整（合并树里读得到整串）；横滑与否不钉，只核语义与实际一致、渐隐 / 指示器随横滑出现
        NumHint.assertHint(rule, NumHintSpec(field, row = NumWrap.within(CARD), expect = null))
        NumWrap.checkRun(rule, node, digits, "$where 里的「$digits」", TOKEN)
    }

    private fun textNode(value: String): SemanticsNodeInteraction =
        rule.onNode(hasText(value) and NumWrap.HAS_LAYOUT and NumWrap.within(CARD), useUnmergedTree = true)

    private fun tagged(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()

    private fun cells(value: String) =
        listOf(
            AmountCell(label = "冻结中", value = value, spoken = "冻结中 $value"),
            AmountCell(label = "已提现", value = value, spoken = "已提现 $value"),
        )

    private companion object {
        const val FONT_SCALE = 2f
        const val CARD = "numwrap_card"
        const val CONTROL = "numwrap_grid_control"
        const val STRESS = "numwrap_grid_stress"
        const val REF = "numwrap_grid_reference"

        /** ¥999999999.99（brief §3.1）。 */
        const val MAX = "¥999999999.99"

        /** ¥12.34：常规金额。 */
        const val REGULAR = "¥12.34"

        /** QA-11 截图里断成「¥9876543.2 / 1」的那串。 */
        const val QA = "¥9876543.21"

        /** CodeResendRow 倒计时文字（调用方给的成品串）。 */
        const val COUNTDOWN = "59 秒后重新获取"

        /** 测试 r7：横滑用例的样式（body）与令牌。 */
        val STYLE: TextStyle = CouliTypography.body
        val TOKEN = NumToken(CouliTypography.body.fontSize, FONT_SCALE)

        /** 32 位流水号。 */
        const val SERIAL_32 = "12345678901234567890123456789012"

        /** 字母与数字粘连：10 个字母 + 10 位数字。 */
        const val GLUE = "AAAAAAAAAA1234567890"
        const val GLUE_DIGITS = "1234567890"

        /** 带数字的脱敏邮箱。 */
        const val GLUE_EMAIL = "user2024***@mail.com"
        const val GLUE_EMAIL_DIGITS = "2024***"
    }
}
