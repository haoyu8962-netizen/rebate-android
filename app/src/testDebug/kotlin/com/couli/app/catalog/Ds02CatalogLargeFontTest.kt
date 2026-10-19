package com.couli.app.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.couliColorTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 含弹窗独立窗口：检查所有 Text，不只样例外壳的 descendants。不录大字号基准。 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h1800dp-xxhdpi", fontScale = 2f)
class Ds02CatalogLargeFontTest(
    private val sample: Ds02Case,
    private val dark: Boolean,
) {
    @get:Rule val rule = createComposeRule()

    @Test
    fun bodyAndAmountsNeverTruncateWhileOnlyMoneyFreeProductTitlesMayEllipsize() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Box(Modifier.width(320.dp).background(couliColorTokens().getValue("background.canvas"))) {
                        CatalogComponentSample(sample.component, sample.variant, sample.state)
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(320)
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        val count = nodes.fetchSemanticsNodes().size
        val mustHaveText =
            sample.component in setOf("PriceTag", "Dialog", "CodeInput", "TabBar") ||
                (sample.component == "ProductCard" && sample.state == "long-price") ||
                (sample.component == "RebateTag" && sample.state == "wrap-at-dash")
        if (mustHaveText) {
            assertTrue("关键组件不能用空样例绕过文字检查：$sample", count > 0)
        }
        repeat(count) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertTrue(layouts.isNotEmpty())
            val allowedTitle = hasTestTag("ds02-ellipsis-allowed").matches(nodes[index].fetchSemanticsNode())
            layouts.forEach { layout ->
                assertEquals("样例覆盖了字号：$sample", 2f, layout.layoutInput.density.fontScale, 0.001f)
                if (allowedTitle) {
                    // 标签仅挂 BadgedTitle 的 Text，本节点完整原文仍不得含金额，不能给价格放白名单。
                    ds02AssertProductTitleMayEllipsize(layout.layoutInput.text.text, layout.layoutInput.maxLines)
                } else {
                    assertFalse("文字截断：$sample / ${layout.layoutInput.text}", ds02HasRealTextOverflow(layout))
                    repeat(layout.lineCount) { assertFalse("省略号：$sample", layout.isLineEllipsized(it)) }
                }
            }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}/dark={1}")
        fun parameters(): List<Array<Any>> = Ds02Inventory.cases.flatMap { sample -> listOf(false, true).map { arrayOf(sample, it) } }
    }
}

/**
 * String Text 的语义段落会按约束宽重建，并不总保留实际 softWrap / maxLines 的裁切结果。
 * 按原文字、样式、字体解析器、密度、换行方式和节点实际宽度重排完整内容；取消高度和行数限制，
 * 再比较容器高度 / maxLines / 实际行宽。1px 只容忍整数取整，任何省略号仍失败。
 */
internal fun ds02HasRealTextOverflow(layout: TextLayoutResult): Boolean {
    val input = layout.layoutInput
    val full =
        androidx.compose.ui.text
            .TextMeasurer(
                input.fontFamilyResolver,
                input.density,
                input.layoutDirection,
                cacheSize = 0,
            ).measure(
                text = input.text,
                style = input.style,
                softWrap = input.softWrap,
                maxLines = Int.MAX_VALUE,
                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                placeholders = input.placeholders,
                constraints =
                    androidx.compose.ui.unit
                        .Constraints(maxWidth = layout.size.width),
                skipCache = true,
            )
    return layout.didOverflowHeight || layout.multiParagraph.didExceedMaxLines ||
        (0 until layout.lineCount).any { layout.isLineEllipsized(it) } ||
        full.lineCount > input.maxLines || full.multiParagraph.height > layout.size.height + 1f ||
        (0 until full.lineCount).any { full.getLineRight(it) - full.getLineLeft(it) > layout.size.width + 1f }
}

/** 判据自证：合法 String / AnnotatedString 不误红，宽裁切 / 高裁切 / 超行 / 省略仍红。 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h1800dp-xxhdpi", fontScale = 2f)
class Ds02TextOverflowOracleTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun realTextLayoutsDistinguishSemanticWidthFromActualClipping() {
        rule.setContent {
            androidx.compose.foundation.layout.Column(Modifier.width(320.dp)) {
                androidx.compose.material3.Text("保存图片", Modifier.testTag("plain"))
                androidx.compose.material3.Text(
                    androidx.compose.ui.text
                        .AnnotatedString("保存图片"),
                    Modifier.testTag("annotated"),
                )
                androidx.compose.material3.Text("¥1234567890.99", Modifier.width(24.dp).testTag("width-clip"), softWrap = false)
                androidx.compose.material3.Text("¥123.45", Modifier.height(4.dp).testTag("height-clip"))
                androidx.compose.material3.Text("返利金额需要完整展示", Modifier.width(48.dp).testTag("max-lines"), maxLines = 1)
                androidx.compose.material3.Text(
                    "¥1234567890.99",
                    Modifier.width(48.dp).testTag("ellipsis"),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                androidx.compose.material3.Text("返利金额可以通过换行完整展示", Modifier.width(120.dp).testTag("wrapped"))
            }
        }
        listOf("plain", "annotated", "wrapped", "width-clip", "height-clip", "max-lines", "ellipsis").forEach { tag ->
            val layouts = mutableListOf<TextLayoutResult>()
            rule
                .onNodeWithTag(tag, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single()
            val expected = tag in setOf("width-clip", "height-clip", "max-lines", "ellipsis")
            println(
                "$tag: old=${layout.hasVisualOverflow}, actual=${ds02HasRealTextOverflow(layout)}, " +
                    "paragraphWidth=${layout.multiParagraph.width}, nodeWidth=${layout.size.width}",
            )
            assertEquals("真实裁切判据：$tag", expected, ds02HasRealTextOverflow(layout))
        }
    }
}
