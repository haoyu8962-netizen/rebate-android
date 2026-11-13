package com.couli.app.numwrap

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.CouliTypography
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * D10-1265 金额横滑提示判据（[NumHint]，语义契约 + 像素粗检）的正反例。测试里用同名语义键「AmountOverflow」模拟共用金额件的声明。
 * 每条反例都必须因指定原因红；布局只存在于测试。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumWrapHintGuardTest {
    @get:Rule val rule = createComposeRule()

    private val style = CouliTypography.body

    // ---------------------------------------------------------------- 语义契约 + 像素粗检

    @Test fun extremeAmountWithTheFullContractPasses() {
        show(Hint(RUN, scroll = true, drawn = true, declared = Overflow(true, true, true)))
        judge(RUN, expect = true)
    }

    @Test fun fadeWithoutIndicatorIsRejected() =
        rejects("滚动指示器") {
            show(Hint(RUN, scroll = true, drawn = true, declared = Overflow(true, true, false)))
            judge(RUN, expect = true)
        }

    @Test fun indicatorWithoutFadeIsRejected() =
        rejects("渐隐") {
            show(Hint(RUN, scroll = true, drawn = true, declared = Overflow(true, false, true)))
            judge(RUN, expect = true)
        }

    @Test fun regularAmountWithoutHintPasses() {
        show(Hint(SHORT, scroll = false, drawn = false, declared = Overflow(false, false, false), width = 300.dp))
        judge(SHORT, expect = false)
    }

    @Test fun regularAmountWithAHintAddedIsRejected() =
        rejects("误加") {
            show(Hint(SHORT, scroll = false, drawn = true, declared = Overflow(false, true, true), width = 300.dp))
            judge(SHORT, expect = false)
        }

    @Test fun amountNotGoingThroughTheSharedComponentIsRejected() =
        rejects("共用金额件") {
            show(Hint(RUN, scroll = true, drawn = true, declared = null))
            judge(RUN, expect = true)
        }

    @Test fun declaredButNothingDrawnIsRejected() =
        rejects("像素粗检") {
            show(Hint(RUN, scroll = true, drawn = false, declared = Overflow(true, true, true)))
            judge(RUN, expect = true)
        }

    @Test fun semanticsDisagreeingWithTheRealScrollIsRejected() =
        rejects("不一致") {
            show(Hint(RUN, scroll = true, drawn = true, declared = Overflow(false, false, false)))
            judge(RUN, expect = null)
        }

    // ---------------------------------------------------------------- 先独占一行、用足整行、从开头露出

    @Test fun scrollingBesideAButtonIsRejected() =
        rejects("没有先独占一行") {
            show(Hint(RUN, scroll = true, drawn = true, declared = Overflow(true, true, true), button = true))
            judge(RUN, expect = true)
        }

    @Test fun labelInsideTheScrollingRowIsRejected() =
        rejects("没有先独占一行") {
            show(Hint(RUN, scroll = true, drawn = true, declared = Overflow(true, true, true), inlineLabel = true))
            judge(RUN, expect = true)
        }

    @Test fun narrowScrollerInAWideRowIsRejected() =
        rejects("没有用足整行") {
            show(Hint(RUN, scroll = true, drawn = true, declared = Overflow(true, true, true), width = 300.dp, viewport = 60.dp))
            judge(RUN, expect = true)
        }

    @Test fun scrollThatStartsAtTheEndIsRejected() =
        rejects("开头") {
            show(Hint(RUN, scroll = true, drawn = true, declared = Overflow(true, true, true), startAtEnd = true))
            judge(RUN, expect = true)
        }

    // ---------------------------------------------------------------- 读屏（合并后的无障碍树）

    @Test fun truncatedSpokenDescriptionIsRejected() =
        rejects("读屏") {
            show(Hint(RUN, scroll = true, drawn = true, declared = Overflow(true, true, true), spoken = Spoken.TRUNCATED))
            judge(RUN, expect = true)
        }

    @Test fun hiddenFromAccessibilityIsRejected() =
        rejects("读屏") {
            show(Hint(RUN, scroll = true, drawn = true, declared = Overflow(true, true, true), spoken = Spoken.HIDDEN))
            judge(RUN, expect = true)
        }

    @Test fun ancestorClearingTheSemanticsIsRejected() =
        rejects("读屏") {
            show(Hint(RUN, scroll = true, drawn = true, declared = Overflow(true, true, true), spoken = Spoken.CLEARED))
            judge(RUN, expect = true)
        }

    // ---------------------------------------------------------------- 布置

    /** 测试用的值对象：属性名与契约一致（scrollable / fadeEnd / indicator）。 */
    data class Overflow(
        val scrollable: Boolean,
        val fadeEnd: Boolean,
        val indicator: Boolean,
    )

    private enum class Spoken { FULL, TRUNCATED, HIDDEN, CLEARED }

    private data class Hint(
        val value: String,
        val scroll: Boolean,
        val drawn: Boolean,
        val declared: Overflow?,
        val width: Dp = 80.dp,
        val viewport: Dp? = null,
        val button: Boolean = false,
        val inlineLabel: Boolean = false,
        val startAtEnd: Boolean = false,
        val spoken: Spoken = Spoken.FULL,
    )

    private fun show(hint: Hint) {
        rule.setContent {
            CouliTheme {
                val cleared = if (hint.spoken == Spoken.CLEARED) Modifier.clearAndSetSemantics { contentDescription = "金额" } else Modifier
                val box = Modifier.width(hint.width).background(Color.White).testTag(BOX)
                Column(box.then(cleared)) {
                    Text("可提现余额", style = style, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.weight(1f)) { Amount(hint) }
                        if (hint.button) {
                            Text("提现", modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(role = Role.Button) {})
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Composable
    private fun Amount(hint: Hint) {
        val declared = hint.declared?.let { value -> Modifier.semantics { this[OverflowKey] = value } } ?: Modifier
        val faded = if (hint.drawn) Modifier.fadeEnd() else Modifier
        val spoken =
            when (hint.spoken) {
                Spoken.TRUNCATED -> Modifier.semantics { contentDescription = "¥9999999" }
                Spoken.HIDDEN -> Modifier.semantics { hideFromAccessibility() }
                else -> Modifier
            }
        Column(declared) {
            if (hint.scroll) {
                val state = rememberScrollState()
                if (hint.startAtEnd) LaunchedEffect(state.maxValue) { state.scrollTo(state.maxValue) }
                val width = hint.viewport?.let { Modifier.width(it) } ?: Modifier
                Row(width.then(faded).horizontalScroll(state)) {
                    if (hint.inlineLabel) Text("金额 ", style = style, softWrap = false)
                    Text(hint.value, style = style, softWrap = false, modifier = spoken)
                }
            } else {
                Box(faded) { Text(hint.value, style = style, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().then(spoken)) }
            }
            if (hint.drawn) Box(Modifier.width(32.dp).height(3.dp).background(Color.Gray))
        }
    }

    /** 末端 24dp 渐隐（DstOut 渐变擦除）。 */
    private fun Modifier.fadeEnd(): Modifier =
        this
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                val start = size.width - 24.dp.toPx()
                val erase = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = start, endX = size.width)
                drawRect(erase, blendMode = BlendMode.DstOut)
            }

    private fun judge(
        value: String,
        expect: Boolean?,
    ) {
        val field = NumField(value, "自测", NumWrap.within(BOX), NumToken(style.fontSize, 1f))
        NumHint.assertHint(rule, NumHintSpec(field, row = NumWrap.within(BOX), expect = expect))
    }

    private fun rejects(
        reason: String,
        block: () -> Unit,
    ) {
        val failure = assertThrows(AssertionError::class.java) { block() }
        assertTrue("必须因「$reason」被拦，实际：${failure.message}", failure.message.orEmpty().contains(reason))
    }

    private companion object {
        const val RUN = "¥999999999.99"
        const val SHORT = "¥12.34"
        const val BOX = "hint_box"
        val OverflowKey = SemanticsPropertyKey<Overflow>("AmountOverflow")
    }
}
