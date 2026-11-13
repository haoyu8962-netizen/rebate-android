package com.couli.app.numwrap

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
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
 * 测试 v3 量具守卫的正反例（Codex r2 复审 S1-1/2/3/4/6、S2-1）：每条反例必须因指定原因被拦（核对失败信息关键词），正例必须放行。布局只存在于测试。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumWrapProbeGuardTest {
    @get:Rule val rule = createComposeRule()

    private val style = CouliTypography.body
    private val token = NumToken(style.fontSize, 1f)

    // ---------------------------------------------------------------- S1-1 不缩字：同源基准与零容差字号

    @Test fun autoSizeJustBelowTheTokenIsRejected() =
        rejects("缩字") {
            checked {
                val measurer = rememberTextMeasurer()
                val width = with(LocalDensity.current) { (measurer.measure(RUN, style).size.width * 0.97f).toDp() }
                val auto = TextAutoSize.StepBased(minFontSize = style.fontSize * 0.85f, maxFontSize = style.fontSize, stepSize = 0.25.sp)
                Box(Modifier.width(width)) { BasicText(RUN, style = style, maxLines = 1, autoSize = auto) }
            }
        }

    @Test fun fontFallbackDigitsPass() {
        show { Text(FULLWIDTH, style = style) }
        NumWrap.assertField(rule, NumField(FULLWIDTH, "字体回退", NumWrap.within(BOX), token))
    }

    @Test fun monospaceBoldSpanOnDigitsPasses() = spanFont(FontFamily.Monospace)

    @Test fun serifSpanOnDigitsPasses() = spanFont(FontFamily.Serif)

    @Test fun tinyPositiveLetterSpacingPasses() = checked { Text(RUN, style = style.copy(letterSpacing = 0.002.em)) }

    @Test fun smallSpanOverriddenByALaterTokenSpanPasses() =
        checked {
            Text(
                buildAnnotatedString {
                    append("¥")
                    withStyle(SpanStyle(fontSize = 10.sp)) { withStyle(SpanStyle(fontSize = style.fontSize)) { append("999999999.99") } }
                },
                style = style,
            )
        }

    // ---------------------------------------------------------------- S1-2 输入框首尾

    @Test fun fieldThatMovesALittleButNeverReachesTheEndIsRejected() =
        rejects("串尾仍不在框内") {
            rule.setContent {
                CouliTheme {
                    Box(Modifier.width(300.dp).background(Color.White).testTag(BOX)) {
                        var value by remember { mutableStateOf(TextFieldValue(RUN)) }
                        BasicTextField(
                            value = value,
                            onValueChange = { value = it },
                            singleLine = true,
                            textStyle = style,
                            modifier = Modifier.width(60.dp).clipToBounds().testTag(FIELD),
                            decorationBox = { inner ->
                                val shift = if (value.selection.end >= RUN.length) (-8).dp else 0.dp
                                Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset(x = shift)) { inner() }
                            },
                        )
                    }
                }
            }
            rule.waitForIdle()
            field()
        }

    /**
     * 尾字符始终在框外（内容只左移 8dp），但输入框装饰底色随 selection.collapsed 变化：两张截图整框不同。差异不是单个字符的高亮，必须红。
     */
    @Test fun fieldWhoseDecorationRepaintsOnSelectionButNeverShowsTheTailIsRejected() =
        rejects("仍不在框内") {
            rule.setContent {
                CouliTheme {
                    Box(Modifier.width(300.dp).background(Color.White).testTag(BOX)) {
                        var value by remember { mutableStateOf(TextFieldValue(RUN)) }
                        BasicTextField(
                            value = value,
                            onValueChange = { value = it },
                            singleLine = true,
                            textStyle = style,
                            modifier = Modifier.width(60.dp).clipToBounds().testTag(FIELD),
                            decorationBox = { inner ->
                                val shift = if (value.selection.end >= RUN.length) (-8).dp else 0.dp
                                val paint = if (value.selection.collapsed) Color.White else Color(0xFFE8F0FE)
                                val moved = Modifier.background(paint).wrapContentWidth(Alignment.Start, unbounded = true)
                                Box(moved.offset(x = shift)) { inner() }
                            },
                        )
                    }
                }
            }
            rule.waitForIdle()
            field()
        }

    /** 很长的重复数字串（墨迹匹配会歧义的情形）：真实输入框正确滚到尾，必须放行。 */
    @Test fun longRepeatedDigitsFieldThatScrollsToTheEndPasses() {
        rule.setContent {
            CouliTheme {
                Box(Modifier.width(300.dp).background(Color.White).testTag(BOX)) {
                    var value by remember { mutableStateOf("") }
                    val modifier = Modifier.width(60.dp).testTag(FIELD)
                    BasicTextField(value, { value = it }, singleLine = true, textStyle = style, modifier = modifier)
                }
            }
        }
        rule.onNodeWithTag(FIELD).performTextReplacement(REPEATED)
        rule.waitForIdle()
        NumWrap.assertField(rule, NumField(REPEATED, "长重复数字输入框", NumWrap.within(BOX), token))
    }

    // ---------------------------------------------------------------- S1-3 同一容器里被拆开 + 旁边另有完整值

    @Test fun splitTargetBesideAnIntactCopyInTheSameContainerIsRejected() =
        rejects("被拆开") {
            checked {
                Row {
                    Text("¥99999", style = style)
                    Text("9999.99", style = style)
                    Text(RUN, style = style)
                }
            }
        }

    /** exact 分支：容器里一个整串节点 + 两个拼成同值的节点，必须红。 */
    @Test fun exactFieldWithASplitCopyBesideTheIntactValueIsRejected() =
        rejects("被拆开") {
            show {
                Row {
                    Text(RUN, style = style)
                    Text("¥99999", style = style)
                    Text("9999.99", style = style)
                }
            }
            NumWrap.assertField(rule, NumField(RUN, "自测", NumWrap.within(BOX), token, exact = true))
        }

    /** 没有 tag 的字段按标签取段：「新手机号」到「提交时间」之间整串恰好 1 处，放行。 */
    @Test fun segmentBetweenLabelsPasses() {
        show { DetailBlock(listOf(MASKED)) }
        NumWrap.assertField(rule, NumField(MASKED, "自测明细", detailField(), token, exact = true))
    }

    /** 按标签取段：段内号码被拆开（整串在段外的说明句里另有一份），必须红。 */
    @Test fun segmentWithTheValueSplitIsRejected() =
        rejects("应恰好") {
            show { DetailBlock(listOf("138**", "**5678")) }
            NumWrap.assertField(rule, NumField(MASKED, "自测明细", detailField(), token, exact = true))
        }

    /** 重复字段：同一容器里两段「新手机号 … 提交时间」，第二段的号码被拆开。按标签取段不能只查第一段：开始标签不唯一必须红。 */
    @Test fun repeatedFieldWithTheSecondOneBrokenIsRejected() =
        rejects("恰好 1 个") {
            show {
                Column {
                    DetailBlock(listOf(MASKED))
                    DetailBlock(listOf("138**", "**5678"))
                }
            }
            NumWrap.assertField(rule, NumField(MASKED, "自测明细", detailField(), token, exact = true))
        }

    /** 结束标签缺失：不许悄悄取到容器末尾。 */
    @Test fun missingEndLabelIsRejected() =
        rejects("恰好 1 个") {
            show {
                Column {
                    Text(LABEL_NEW, style = style)
                    Text(MASKED, style = style)
                }
            }
            NumWrap.assertField(rule, NumField(MASKED, "自测明细", detailField(), token, exact = true))
        }

    // ---------------------------------------------------------------- S1-4 AmountGrid 像素量具自身

    @Test fun fakeGridWithWholeValuesPasses() = bands(stressClip = null, valueStyle = style)

    @Test fun clippedSingleLineValueIsRejected() = rejects("不完整") { bands(stressClip = 60, valueStyle = style) }

    @Test fun valuesShrunkTogetherAreRejectedByTheIndependentBaseline() =
        rejects("缩字") { bands(stressClip = null, valueStyle = style.copy(fontSize = style.fontSize * 0.75f)) }

    // ---------------------------------------------------------------- S1-6 复制文字被部分裁切

    @Test fun partiallyClippedCopyIsRejected() =
        rejects("没有完整可见") {
            rule.setContent {
                CouliTheme {
                    Row(Modifier.width(360.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("订单号 $ORDER", style = style)
                        Box(Modifier.width(16.dp).clipToBounds()) {
                            Text(
                                "复制",
                                modifier =
                                    Modifier
                                        .wrapContentWidth(Alignment.Start, unbounded = true)
                                        .sizeIn(minHeight = 48.dp)
                                        .clickable(role = Role.Button) {}
                                        .testTag(COPY),
                            )
                        }
                    }
                }
            }
            NumCopy.assertCopyClear(rule, NumWrap.nodesFor(rule, ORDER), ORDER, COPY, "自测")
        }

    /** 复制文字上下被裁切（放进 10dp 高的裁切框）：必须红。 */
    @Test fun verticallyClippedCopyIsRejected() =
        rejects("上下被裁切") {
            rule.setContent {
                CouliTheme {
                    Row(Modifier.width(360.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("订单号 $ORDER", style = style)
                        Box(Modifier.height(10.dp).clipToBounds()) {
                            Text(
                                "复制",
                                modifier =
                                    Modifier
                                        .wrapContentHeight(Alignment.Top, unbounded = true)
                                        .clickable(role = Role.Button) {}
                                        .testTag(COPY),
                            )
                        }
                    }
                }
            }
            NumCopy.assertCopyClear(rule, NumWrap.nodesFor(rule, ORDER), ORDER, COPY, "自测")
        }

    /** 号码与复制起初在纵向滚动容器的屏外：量具先把复制滚进视口，应放行。 */
    @Test fun copyScrolledIntoViewPasses() {
        rule.setContent {
            CouliTheme {
                Column(Modifier.width(360.dp).height(200.dp).verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(600.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("订单号 $ORDER", style = style)
                        val copy =
                            Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .clickable(role = Role.Button) {}
                                .testTag(COPY)
                        Text("复制", modifier = copy)
                    }
                }
            }
        }
        NumCopy.assertCopyClear(rule, NumWrap.nodesFor(rule, ORDER), ORDER, COPY, "自测")
    }

    // ---------------------------------------------------------------- S2-1 按钮正例矩阵与零散装饰反例

    @Test fun buttonMatrixAt200PercentLight() = matrix(dark = false)

    @Test fun buttonMatrixAt200PercentDark() = matrix(dark = true)

    @Test fun scatteredDecorationDotsAroundPlainTextAreRejected() =
        rejects("光文字") {
            rule.setContent {
                CouliTheme {
                    Box(Modifier.padding(24.dp)) {
                        val entry =
                            Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .clickable(role = Role.Button) {}
                                .testTag(ENTRY)
                                .padding(12.dp)
                        Box(entry) {
                            Text(GET, modifier = Modifier.align(Alignment.Center).padding(8.dp))
                            DOTS.forEach { Box(Modifier.align(it).size(3.dp).background(Color.Red)) }
                        }
                    }
                }
            }
            rule.waitForIdle()
            SendCodeLook.assertLooksLikeButton(rule, tagged(ENTRY), "自测")
        }

    /** 只有上下两条横线、左右不相连：不算闭合描边。 */
    @Test fun twoHorizontalLinesWithoutSidesAreRejected() =
        rejects("光文字") {
            rule.setContent {
                CouliTheme {
                    Box(Modifier.background(Color.White).padding(24.dp)) {
                        val entry =
                            Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .clickable(role = Role.Button) {}
                                .testTag(ENTRY)
                        Box(entry) {
                            Text(GET, modifier = Modifier.align(Alignment.Center).padding(horizontal = 4.dp, vertical = 10.dp))
                            val line = Modifier.fillMaxWidth().height(2.dp).background(Color.DarkGray)
                            Box(Modifier.align(Alignment.TopCenter).then(line))
                            Box(Modifier.align(Alignment.BottomCenter).then(line))
                        }
                    }
                }
            }
            rule.waitForIdle()
            SendCodeLook.assertLooksLikeButton(rule, tagged(ENTRY), "自测")
        }

    // ---------------------------------------------------------------- 工具

    /** 待确认卡式的说明句 + 明细块：说明句里有整串号码，明细「新手机号」行的值由 [parts] 组成。 */
    @Composable
    private fun DetailBlock(parts: List<String>) {
        Column {
            Text("新号 $MASKED 待确认", style = style)
            Text(LABEL_NEW, style = style)
            Row { parts.forEach { Text(it, style = style) } }
            Text(LABEL_TIME, style = style)
            Text("2026-10-12 10:00", style = style)
        }
    }

    private fun detailField() = NumWrap.segment(rule, NumWrap.within(BOX), LABEL_NEW, LABEL_TIME)

    /** 只布置，不断言（调用方自己核对指定值）。 */
    private fun show(content: @Composable () -> Unit) {
        rule.setContent { CouliTheme { Box(Modifier.width(300.dp).background(Color.White).testTag(BOX)) { content() } } }
        rule.waitForIdle()
    }

    /** 布置后按 [RUN] 做字段断言。 */
    private fun checked(content: @Composable () -> Unit) {
        show(content)
        field()
    }

    private fun field() = NumWrap.assertField(rule, NumField(RUN, "自测", NumWrap.within(BOX), token))

    private fun spanFont(family: FontFamily) =
        checked {
            Text(
                buildAnnotatedString {
                    append("¥")
                    withStyle(SpanStyle(fontFamily = family, fontWeight = FontWeight.Bold)) { append("999999999.99") }
                },
                style = style,
            )
        }

    private fun bands(
        stressClip: Int?,
        valueStyle: TextStyle,
    ) {
        rule.setContent {
            CouliTheme {
                Column(Modifier.background(Color.White)) {
                    FakeGrid("¥1.00", CONTROL, null, valueStyle)
                    FakeGrid(RUN, STRESS, stressClip, valueStyle)
                    Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true)) {
                        Text(RUN, style = style, softWrap = false, modifier = Modifier.testTag(REF))
                    }
                }
            }
        }
        rule.waitForIdle()
        NumBands.assertAmountRows(rule, tagged(CONTROL) to tagged(STRESS), tagged(REF), "自测")
    }

    @Composable
    private fun FakeGrid(
        value: String,
        tag: String,
        clip: Int?,
        valueStyle: TextStyle,
    ) {
        Column(Modifier.width(260.dp).padding(vertical = 8.dp).testTag(tag)) {
            repeat(2) {
                Text("冻结中", style = CouliTypography.caption)
                val box = if (clip != null) Modifier.width(clip.dp).clipToBounds() else Modifier
                val loose = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                Box(box) { Text(value, style = valueStyle, softWrap = false, modifier = loose) }
            }
        }
    }

    private fun matrix(dark: Boolean) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                CouliTheme(darkTheme = dark) {
                    Column(Modifier.width(320.dp).padding(16.dp)) {
                        listOf(true, false).forEach { enabled ->
                            Button(onClick = {}, enabled = enabled, modifier = Modifier.testTag("filled_$enabled")) { Text(COUNTDOWN) }
                            OutlinedButton(onClick = {}, enabled = enabled, modifier = Modifier.testTag("outlined_$enabled")) {
                                Text(COUNTDOWN)
                            }
                            FilledTonalButton(onClick = {}, enabled = enabled, modifier = Modifier.testTag("tonal_$enabled")) {
                                Text(COUNTDOWN)
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        listOf("filled", "outlined", "tonal").forEach { kind ->
            listOf(true, false).forEach { enabled ->
                val where = "200% ${if (dark) "深" else "浅"} $kind enabled=$enabled"
                SendCodeLook.assertLooksLikeButton(rule, tagged("${kind}_$enabled"), where)
            }
        }
    }

    private fun tagged(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()

    private fun rejects(
        reason: String,
        block: () -> Unit,
    ) {
        val failure = assertThrows(AssertionError::class.java) { block() }
        assertTrue("必须因「$reason」被拦，实际：${failure.message}", failure.message.orEmpty().contains(reason))
    }

    private companion object {
        const val RUN = "¥999999999.99"
        const val FULLWIDTH = "¥９９９９９９.99"
        const val ORDER = "374123456789012345678901"
        const val GET = "获取验证码"
        const val COUNTDOWN = "60 秒后可重新获取"
        const val REPEATED = "111111111111111111111111111111"
        const val MASKED = "138****5678"
        const val LABEL_NEW = "新手机号"
        const val LABEL_TIME = "提交时间"
        const val BOX = "guard_box"
        const val FIELD = "guard_field"
        const val COPY = "guard_copy"
        const val ENTRY = "guard_entry"
        const val CONTROL = "guard_control"
        const val STRESS = "guard_stress"
        const val REF = "guard_ref"
        val DOTS =
            listOf(
                Alignment.TopStart,
                Alignment.TopCenter,
                Alignment.TopEnd,
                Alignment.CenterStart,
                Alignment.CenterEnd,
                Alignment.BottomStart,
                Alignment.BottomCenter,
                Alignment.BottomEnd,
            )
    }
}
