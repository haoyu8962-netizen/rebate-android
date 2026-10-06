package com.couli.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 第 3 轮评审修复的守护测试：
 * - R2-01 金额是不可拆分段：主价、返利区间每段在任何宽度下都整块单行、不被裁切；区间只在破折号处断；放不下时改布局（价格区整卡宽、网格改单列）。
 * - R2-02 「稍后再试」是禁用态：不可点、读屏报告不可用。
 * - R2-03 弹窗正文可滚、按钮行固定可见（320×568dp + 200%）。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class LayoutGuardTest {
    @get:Rule
    val rule = createComposeRule()

    /** 每段金额文字的排版结果（来自 [LocalAmountLayoutObserver]）。 */
    private val amountLayouts = mutableListOf<Pair<String, TextLayoutResult>>()

    private fun host(
        widthDp: Int,
        content: @Composable () -> Unit,
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalAmountLayoutObserver provides { text, result -> amountLayouts += text to result }) {
                CouliTheme {
                    Column(
                        modifier =
                            Modifier
                                .width(widthDp.dp)
                                .background(CouliColors.backgroundCanvas)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { content() }
                }
            }
        }
        rule.waitForIdle()
    }

    /** 每段金额：只有一行、没有被裁切（宽度溢出），文字是一个完整金额段；返回实际排出的段（去重）。 */
    private fun assertAmountsWhole(): Set<String> {
        assertTrue("应排出金额段", amountLayouts.isNotEmpty())
        val latest = amountLayouts.associate { it.first to it.second }
        latest.forEach { (text, layout) ->
            assertEquals("「$text」应在一行内（不在数字中间、小数点前断开）", 1, layout.lineCount)
            assertEquals("「$text」一行里应是整段", text, text.substring(layout.getLineStart(0), layout.getLineEnd(0)))
            assertFalse("「$text」不应被裁切", layout.didOverflowWidth)
            assertTrue("「$text」应是完整金额（¥ 开头、可带区间破折号）", WHOLE_AMOUNT.matches(text))
        }
        return latest.keys
    }

    private val longestRange = RebateTagModel.make("price_compare_risk", 8888888, 9999999, false)
    private val longestPrice = PriceTagModel.make("taobao", 9999999, 0, 9999999, true)

    /** 主价：宽度放不下「口径词 + 主价」时主价整块换到下一行，不拆数字（R2-01）。 */
    @Test
    @Config(qualifiers = "w320dp-h2400dp-xxhdpi", fontScale = 2f)
    fun priceTagMainNeverSplitsAmount() {
        host(widthDp = 320) {
            PriceTagMain(content = longestPrice, size = PriceTagSize.LIST)
            PriceTagMain(content = longestPrice, size = PriceTagSize.LARGE)
            PriceTagMain(content = PriceTagModel.make("pdd", 10099999, 100000, 9999999, false), size = PriceTagSize.AI)
        }
        val segments = assertAmountsWhole()
        assertEquals(setOf("¥99999.99"), segments)
    }

    /** 返利区间：放得下一行时整句一行；放不下时只在破折号处断成「¥88888.88」「–¥99999.99」两段，每段整块（R2-01）。 */
    @Test
    @Config(qualifiers = "w320dp-h2400dp-xxhdpi", fontScale = 2f)
    fun rebateTagBreaksOnlyAtDash() {
        host(widthDp = 320) {
            RebateTag(longestRange)
            RebateTag(longestRange, size = RebateTagSize.LARGE)
            // 窄到只能按段排：两段各自一行
            Box(Modifier.width(180.dp)) { RebateTag(longestRange) }
            RebateTag(RebateTagModel.make("normal", 12345, 12345, false))
        }
        val segments = assertAmountsWhole()
        assertEquals(setOf("¥88888.88", "–¥99999.99", "¥123.45"), segments)
    }

    /** 列表卡与网格（320dp + 200%）：所有金额整块不裁切；网格两列放不下时改单列（两张卡左边缘对齐、上下排）。 */
    @Test
    @Config(qualifiers = "w320dp-h2400dp-xxhdpi", fontScale = 2f)
    fun cardsAtNarrowLargeFontKeepAmountsWhole() {
        host(widthDp = 320) {
            ProductCardList(data = sampleCard(longestPrice, longestRange), onBuy = {})
            ProductCardList(data = sampleCard(longestPrice, longestRange), onBuy = {}, variant = ProductCardVariant.AI)
            ProductCardGridLayout(
                items =
                    listOf(
                        sampleCard(longestPrice, longestRange, title = "网格一"),
                        sampleCard(
                            PriceTagModel.make("taobao", 1334567, 100000, 1234567, false),
                            RebateTagModel.make("normal", 12345, 12345, false),
                            title = "网格二",
                        ),
                    ),
            )
        }
        assertAmountsWhole()
        val first = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("淘宝 网格一"))).fetchSemanticsNode()
        val second =
            rule
                .onNode(
                    SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("淘宝 网格二")),
                ).fetchSemanticsNode()
        assertEquals("网格应改为单列：两张卡左边缘相同", first.boundsInRoot.left, second.boundsInRoot.left, 1f)
        assertTrue("网格单列：第二张在第一张下方", second.boundsInRoot.top > first.boundsInRoot.bottom)
    }

    /** 411dp + 200%（压力页同宽）：金额整块不裁切。 */
    @Test
    @Config(fontScale = 2f)
    fun cardsAtPhoneLargeFontKeepAmountsWhole() {
        host(widthDp = 411) {
            ProductCardList(data = sampleCard(longestPrice, longestRange), onBuy = {})
            ProductCardGridLayout(items = listOf(sampleCard(longestPrice, longestRange), sampleCard(longestPrice, longestRange)))
        }
        assertAmountsWhole()
    }

    /** BR-TEXT-14：「稍后再试」是禁用态——不可点（点击不触发回调）、读屏报告不可用、不拼金额（R2-02）。 */
    @Test
    fun retryLaterIsDisabled() {
        var clicks = 0
        host(widthDp = 411) {
            BuyButton(
                base = "领券购买",
                rebate = RebateTagModel.make("normal", 269, 269, false),
                onClick = { clicks++ },
                state = BuyButtonState.RETRY_LATER,
                retryLaterText = "稍后再试",
            )
        }
        val node =
            rule.onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Text,
                    listOf(
                        androidx.compose.ui.text
                            .AnnotatedString("稍后再试"),
                    ),
                ),
                useUnmergedTree = true,
            )
        val button = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        button.assertIsNotEnabled()
        assertTrue("读屏应报告不可用", button.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
        button.performClick()
        rule.waitForIdle()
        assertEquals("稍后再试不可点", 0, clicks)
        node.assertIsDisplayed()
        assertFalse(
            "不拼金额",
            node
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString()
                .contains("¥"),
        )
    }

    /** 320×568dp + 200% + 长正文：按钮行完整可见，正文区可滚动并能滚到底（R2-03）。 */
    @Test
    @Config(qualifiers = "w320dp-h568dp-xxhdpi", fontScale = 2f)
    fun dialogBodyScrollsButtonsStayVisible() {
        rule.setContent {
            CouliTheme {
                // 与 CouliDialog 相同的外框：可视区四周 24
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                CouliColors.textPrimary.copy(alpha = CouliAlpha.SCRIM),
                            ).padding(CouliSpacing.space6),
                    contentAlignment = Alignment.Center,
                ) {
                    CouliDialogContent(
                        title = "价格已变动",
                        message = LONG_MESSAGE,
                        confirmText = "继续购买",
                        onConfirm = {},
                        dismissText = "取消",
                    )
                }
            }
        }
        rule.waitForIdle()
        val rootBottom =
            rule
                .onRoot()
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        listOf("继续购买", "取消").forEach { label ->
            val b =
                rule.onNode(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.Text,
                        listOf(
                            androidx.compose.ui.text
                                .AnnotatedString(label),
                        ),
                    ),
                    useUnmergedTree = true,
                )
            b.assertIsDisplayed()
            assertTrue("$label 应完整在屏内", b.fetchSemanticsNode().boundsInRoot.bottom <= rootBottom)
        }
        val body = rule.onNode(hasScrollAction())
        val range = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("正文应可滚动", range.maxValue() > 0f)
        rule.onRoot().captureRoboImage("src/test/snapshots/Dialog_w320h568_font200.png")
        body.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        rule.waitForIdle()
        assertEquals(
            "正文能滚到底",
            range.maxValue(),
            body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(),
            0.5f,
        )
        rule.onRoot().captureRoboImage("src/test/snapshots/Dialog_w320h568_font200_scrolled.png")
    }

    /** 半屏同查：正文可滚、操作固定可见，可滚时操作区上方有分隔线。 */
    @Test
    @Config(qualifiers = "w320dp-h568dp-xxhdpi", fontScale = 2f)
    fun sheetBodyScrollsActionsStayVisible() {
        rule.setContent {
            CouliTheme {
                Box(Modifier.fillMaxSize().background(CouliColors.backgroundSurface)) {
                    CouliSheetBody(
                        title = "授权淘宝账号",
                        actions = { CouliButton(text = "去授权", onClick = {}) },
                    ) {
                        androidx.compose.material3.Text(LONG_MESSAGE, style = CouliTypography.footnote)
                    }
                }
            }
        }
        rule.waitForIdle()
        val rootBottom =
            rule
                .onRoot()
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val action =
            rule.onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Text,
                    listOf(
                        androidx.compose.ui.text
                            .AnnotatedString("去授权"),
                    ),
                ),
                useUnmergedTree = true,
            )
        action.assertIsDisplayed()
        assertTrue(action.fetchSemanticsNode().boundsInRoot.bottom <= rootBottom)
        assertTrue(
            rule
                .onNode(hasScrollAction())
                .fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
                .maxValue() > 0f,
        )
        rule.onRoot().captureRoboImage("src/test/snapshots/Sheet_w320h568_font200.png")
    }

    private fun sampleCard(
        price: PriceTagContent,
        rebate: RebateTagContent,
        title: String = "全脂纯牛奶 250ml × 24 盒 整箱装",
    ) = ProductCardData(
        platformName = "淘宝",
        title = title,
        price = price,
        rebate = rebate,
        buyBase = "去购买",
        imageLabel = "商品图",
        estNet = EstNetModel.make("price_compare_risk", 8888888, 1111111),
        disclaimer = listOf(PriceTerms.PRICE_BASIS),
        infoLabel = "口径说明",
    )

    private companion object {
        /** 完整金额段：可选区间破折号 + ¥ + 整数 + 可选两位以内小数。 */
        val WHOLE_AMOUNT = Regex("–?¥[0-9]+(\\.[0-9]{1,2})?")
        val LONG_MESSAGE = (1..30).joinToString("\n") { "第 $it 行：价格与返利以下单页和平台结算为准，未结算或订单失效时不返。" }
    }
}
