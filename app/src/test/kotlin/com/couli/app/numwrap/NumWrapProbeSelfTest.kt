package com.couli.app.numwrap

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.CouliTypography
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 测试 v2 量具的正反例（Codex 复审 S1 ①②③④⑥⑦）：每条护栏都有「应放行」与「应拦下」的实测布局，反例必须因指定原因被拦（核对失败信息关键词），
 * 不能靠别的异常冒充。布局只存在于测试，不改产品码。另核五个模块里的量具副本逐字相同。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumWrapProbeSelfTest {
    @get:Rule val rule = createComposeRule()

    private val style = CouliTypography.body
    private val token = NumToken(style.fontSize, 1f)

    // ---------------------------------------------------------------- ① 不缩字

    @Test fun plainFittingAmountPasses() {
        show { Text(RUN, style = style) }
        field()
    }

    @Test fun smallerWholeFontSizeIsRejected() = rejects("缩字") { show { Text(RUN, style = style.copy(fontSize = 10.sp)) } }

    @Test fun smallerSpanFontSizeOnDigitsIsRejected() =
        rejects("缩字") {
            show {
                Text(
                    buildAnnotatedString {
                        append("¥")
                        withStyle(SpanStyle(fontSize = 10.sp)) { append("999999999.99") }
                    },
                    style = style,
                )
            }
        }

    @Test fun negativeLetterSpacingIsRejected() = rejects("缩字") { show { Text(RUN, style = style.copy(letterSpacing = (-0.08).em)) } }

    @Test fun geometricScaleXIsRejected() =
        rejects("缩字") { show { Text(RUN, style = style.copy(textGeometricTransform = TextGeometricTransform(scaleX = 0.7f))) } }

    @Test fun graphicsLayerScaleYOnlyIsRejected() =
        rejects("缩字") { show { Text(RUN, style = style, modifier = Modifier.graphicsLayer(scaleY = 0.6f)) } }

    @Test fun scaleOnBothAxesIsRejected() = rejects("缩字") { show { Text(RUN, style = style, modifier = Modifier.scale(0.7f)) } }

    @Test fun autoSizeIsRejected() =
        rejects("缩字") {
            show(60.dp) {
                val auto = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = style.fontSize)
                BasicText(RUN, style = style, maxLines = 1, autoSize = auto)
            }
        }

    // ---------------------------------------------------------------- ①' 全页扫描（不传令牌）：放大强调放行，缩小仍拦（v7.1）

    @Test fun enlargedAmountPassesTheWholePageScan() = scan(ENLARGED)

    @Test fun shrunkAmountIsRejectedByTheWholePageScan() = assertRejected("缩字") { scan(SHRUNK) }

    // ---------------------------------------------------------------- ③ 字段定位：拆分、出现次数、节点内每一处

    @Test fun brokenAmountIsRejected() = rejects("从中间断行") { show(60.dp) { Text(RUN, style = style) } }

    @Test fun amountSplitIntoTwoTextsIsRejected() =
        rejects("应恰好") {
            show {
                Row {
                    Text("¥99999", style = style)
                    Text("9999.99", style = style)
                }
            }
        }

    @Test fun intactCopyElsewhereDoesNotMaskASplitField() {
        rule.setContent {
            CouliTheme {
                Column {
                    Row(Modifier.testTag(BOX)) {
                        Text("¥99999", style = style)
                        Text("9999.99", style = style)
                    }
                    Text(RUN, style = style)
                }
            }
        }
        assertRejected("应恰好") { field() }
    }

    /** 同一节点里 3 处完整值（按出现次数计 3 次）：第 3 处断开必须被拦，不能只核第一处。 */
    @Test fun laterOccurrenceInTheSameNodeIsChecked() =
        rejects("从中间断行") {
            rule.setContent {
                CouliTheme { Box(Modifier.width(150.dp).background(Color.White).testTag(BOX)) { Text("$RUN\n$RUN$RUN", style = style) } }
            }
            NumWrap.assertField(rule, NumField(RUN, "自测", NumWrap.within(BOX), token, count = 3))
        }

    // ---------------------------------------------------------------- ② 横向滚动须真能滚到首尾

    /** softWrap=false 在有界宽度里：语义取到的排版会按父约束重建并折行，所以拦下原因可能是「从中间断行」或「不可见」，两者都算。 */
    @Test fun softWrapFalseClippedIsRejected() =
        rejects("不可见", "从中间断行") {
            show(60.dp) { Text(RUN, style = style, softWrap = false, modifier = Modifier.clipToBounds()) }
        }

    @Test fun realHorizontalScrollPasses() {
        show { Row(Modifier.width(60.dp).horizontalScroll(rememberScrollState())) { Text(RUN, style = style, softWrap = false) } }
        field()
    }

    @Test fun innerClipInsideAScrollIsRejected() =
        rejects("不可见") {
            show {
                Row(Modifier.width(60.dp).horizontalScroll(rememberScrollState())) {
                    Box(Modifier.width(40.dp).clipToBounds()) {
                        Text(RUN, style = style, softWrap = false, modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true))
                    }
                }
            }
        }

    @Test fun fakeScrollSemanticsIsRejected() =
        rejects("不可见") {
            show {
                Box(
                    Modifier.width(60.dp).clipToBounds().semantics {
                        horizontalScrollAxisRange = ScrollAxisRange(value = { 0f }, maxValue = { 100f })
                        scrollBy { _, _ -> true }
                    },
                ) {
                    Text(RUN, style = style, softWrap = false, modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true))
                }
            }
        }

    /** 真实用法：用户输入后（输入框已聚焦）再核；未聚焦的输入框在 Robolectric 里设光标不会滚动。 */
    @Test fun singleLineFieldThatScrollsPasses() {
        rule.setContent {
            CouliTheme {
                Box(Modifier.width(300.dp).background(Color.White).testTag(BOX)) {
                    var value by remember { mutableStateOf("") }
                    val modifier = Modifier.width(60.dp).testTag(FIELD)
                    BasicTextField(value, { value = it }, singleLine = true, textStyle = style, modifier = modifier)
                }
            }
        }
        rule.onNodeWithTag(FIELD).performTextReplacement(RUN)
        rule.waitForIdle()
        field()
    }

    @Test fun fieldClippedByItsParentIsRejected() =
        rejects("裁切") {
            show {
                Box(Modifier.width(60.dp).clipToBounds()) {
                    BasicTextField(RUN, onValueChange = {}, singleLine = true, textStyle = style, modifier = Modifier.requiredWidth(100.dp))
                }
            }
        }

    // ---------------------------------------------------------------- ④ 流水号独占整行

    @Test fun serialOnItsOwnFullRowPasses() {
        serialRow {
            Column(Modifier.fillMaxWidth()) {
                Text(SERIAL_32, style = style, modifier = Modifier.fillMaxWidth().testTag(SERIAL))
                Text("复制", modifier = Modifier.clickable {}.testTag(COPY))
            }
        }
        checkSerial()
    }

    @Test fun fullWidthSerialUnderTheCopyButtonIsRejected() =
        rejects("重叠") {
            serialRow {
                Box(Modifier.fillMaxWidth()) {
                    Text(SERIAL_32, style = style, modifier = Modifier.fillMaxWidth().testTag(SERIAL))
                    Text("复制", modifier = Modifier.align(Alignment.BottomEnd).clickable {}.testTag(COPY))
                }
            }
            checkSerial()
        }

    @Test fun serialSqueezedBesideTheCopyButtonIsRejected() =
        rejects("没有独占整行") {
            serialRow { Squeezed(SERIAL_32) }
            checkSerial()
        }

    @Test fun serialThatFitsTheRowButIsWrappedIsRejected() =
        rejects("从中间断行") {
            serialRow { Squeezed(SERIAL_20) }
            checkSerial(SERIAL_20)
        }

    // ---------------------------------------------------------------- ⑦ 复制按视口判断

    @Test fun copyFixedRightOfAScrollingNumberPasses() {
        rule.setContent {
            CouliTheme {
                Row(Modifier.width(220.dp).testTag(BOX), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) { Text("订单号 $ORDER", style = style, softWrap = false) }
                    Text("复制", modifier = Modifier.linkLike(COPY))
                }
            }
        }
        val number = NumWrap.nodesFor(rule, ORDER)
        NumWrap.checkRun(rule, number, ORDER, "自测", token)
        NumCopy.assertCopyClear(rule, number, ORDER, COPY, "自测")
    }

    @Test fun copyDrawnOverTheNumberIsRejected() =
        rejects("压在可见的号码上") {
            rule.setContent {
                CouliTheme {
                    Box(Modifier.width(300.dp)) {
                        Text("订单号 $ORDER", style = style)
                        Text(
                            "复制",
                            modifier = Modifier.align(Alignment.TopCenter).linkLike(COPY),
                        )
                    }
                }
            }
            NumCopy.assertCopyClear(rule, NumWrap.nodesFor(rule, ORDER), ORDER, COPY, "自测")
        }

    // ---------------------------------------------------------------- ⑥ 发码入口外观只看入口自身

    @Test fun filledButtonLooksLikeAButton() = looks { Button(onClick = {}, modifier = Modifier.testTag(ENTRY)) { Text(GET) } }

    @Test fun outlinedButtonLooksLikeAButton() = looks { OutlinedButton(onClick = {}, modifier = Modifier.testTag(ENTRY)) { Text(GET) } }

    @Test fun disabledFilledButtonStillLooksLikeAButton() =
        looks { Button(onClick = {}, enabled = false, modifier = Modifier.testTag(ENTRY)) { Text(GET) } }

    @Test fun disabledOutlinedButtonStillLooksLikeAButton() =
        looks { OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.testTag(ENTRY)) { Text(GET) } }

    @Test fun countdownAsADisabledButtonPasses() =
        countdown { OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.testTag(ENTRY)) { Text(COUNTDOWN) } }

    @Test fun countdownThatStaysClickableIsRejected() =
        assertRejected("禁用按钮") { countdown { OutlinedButton(onClick = {}, modifier = Modifier.testTag(ENTRY)) { Text(COUNTDOWN) } } }

    @Test fun countdownAsPlainTextIsRejected() =
        assertRejected("可点节点") {
            countdown { Text(COUNTDOWN, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag(ENTRY)) }
        }

    @Test fun plainTextInsideAColouredContainerIsRejected() =
        rejects("光文字") {
            looks {
                Box(Modifier.background(Color(0xFFFFD8C2)).padding(16.dp)) {
                    Text(GET, modifier = Modifier.linkLike(ENTRY))
                }
            }
        }

    @Test fun textButtonIsRejected() =
        rejects("光文字") {
            looks { TextButton(onClick = {}, modifier = Modifier.testTag(ENTRY)) { Text(GET) } }
        }

    @Test fun leadingIconPlusPlainTextIsRejected() =
        rejects("光文字") {
            looks {
                Row(
                    Modifier.sizeIn(minHeight = 48.dp).clickable(role = Role.Button) {}.testTag(ENTRY),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(16.dp).background(Color.Red))
                    Text(GET)
                }
            }
        }

    @Test fun underlinedLinkIsRejected() =
        rejects("光文字") {
            looks {
                Text(
                    GET,
                    style = style.copy(textDecoration = TextDecoration.Underline),
                    modifier = Modifier.linkLike(ENTRY),
                )
            }
        }

    @Test fun borrowingAClickableColouredAncestorIsRejected() =
        rejects("没有可点节点") {
            looks {
                Box(Modifier.background(Color(0xFFFFD8C2)).clickable(role = Role.Button) {}.padding(16.dp)) {
                    Text(GET, modifier = Modifier.testTag(ENTRY))
                }
            }
        }

    @Test fun plainLinkKeepsTheDefaultLookAndAButtonDoesNot() {
        rule.setContent {
            CouliTheme {
                Column {
                    Text(GET, modifier = Modifier.linkLike(ENTRY))
                    Button(onClick = {}, modifier = Modifier.testTag(OTHER)) { Text(GET) }
                }
            }
        }
        SendCodeLook.assertPlainText(rule, tagged(ENTRY), "自测")
        assertRejected("外观变了") { SendCodeLook.assertPlainText(rule, tagged(OTHER), "自测") }
    }

    // ---------------------------------------------------------------- 副本一致

    @Test fun probeCopiesInAllModulesAreIdentical() {
        val start = File(System.getProperty("user.dir") ?: ".").canonicalFile
        val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        val bodies =
            PROBE_COPIES.map { path ->
                val file = File(root, path)
                assertTrue("量具副本缺失：$path", file.isFile)
                file.readLines().drop(1).joinToString("\n")
            }
        bodies.drop(1).forEachIndexed { i, body -> assertEquals("量具副本与 app 版不一致：${PROBE_COPIES[i + 1]}", bodies[0], body) }
    }

    // ---------------------------------------------------------------- 工具

    private fun show(
        width: Dp = 300.dp,
        content: @Composable () -> Unit,
    ) {
        rule.setContent { CouliTheme { Box(Modifier.width(width).background(Color.White).testTag(BOX)) { content() } } }
        rule.waitForIdle()
        field()
    }

    private fun field() = NumWrap.assertField(rule, NumField(RUN, "自测", NumWrap.within(BOX), token))

    private fun serialRow(content: @Composable () -> Unit) {
        rule.setContent { CouliTheme { Box(Modifier.width(200.dp).testTag(ROW)) { content() } } }
        rule.waitForIdle()
    }

    @Composable
    private fun Squeezed(serial: String) {
        Row(Modifier.fillMaxWidth()) {
            Text(serial, style = style, modifier = Modifier.weight(1f).testTag(SERIAL))
            Text("复制订单流水号", modifier = Modifier.clickable {}.testTag(COPY))
        }
    }

    private fun checkSerial(serial: String = SERIAL_32) {
        val row = tagged(ROW)
        val copy = tagged(COPY)
        NumWrap.checkSerial(rule, rule.onNodeWithTag(SERIAL, useUnmergedTree = true), NumSerialSpec(serial, row, copy, "自测流水号", token))
    }

    private fun looks(content: @Composable () -> Unit) {
        rule.setContent { CouliTheme { Box(Modifier.padding(24.dp)) { content() } } }
        rule.waitForIdle()
        SendCodeLook.assertLooksLikeButton(rule, tagged(ENTRY), "自测")
    }

    private fun Modifier.linkLike(tag: String): Modifier =
        this
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(role = Role.Button) {}
            .testTag(tag)

    private fun countdown(content: @Composable () -> Unit) {
        rule.setContent { CouliTheme { Box(Modifier.padding(24.dp)) { content() } } }
        rule.waitForIdle()
        SendCodeLook.assertDisabledButton(rule, tagged(ENTRY), "自测倒计时")
    }

    /** 全页扫描：整句 [style] 字号，金额用 [delta] sp 的强调 span 放大或缩小（仿提现估算行）。 */
    private fun scan(delta: Float) {
        rule.setContent {
            CouliTheme {
                Box(Modifier.width(300.dp).background(Color.White).testTag(BOX)) {
                    Text(
                        buildAnnotatedString {
                            append("预计到账 ")
                            withStyle(SpanStyle(fontSize = (style.fontSize.value + delta).sp)) { append(RUN) }
                            append("，以实际到账为准")
                        },
                        style = style,
                    )
                }
            }
        }
        rule.waitForIdle()
        assertEquals("全页扫描要扫到这一个金额", 1, NumWrap.scanPage(rule, "自测"))
    }

    private fun tagged(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()

    private fun rejects(
        vararg reasons: String,
        block: () -> Unit,
    ) {
        val failure = assertThrows(AssertionError::class.java) { block() }
        val message = failure.message.orEmpty()
        assertTrue("必须因「${reasons.joinToString("」或「")}」被拦，实际：$message", reasons.any { message.contains(it) })
    }

    private fun assertRejected(
        reason: String,
        block: () -> Unit,
    ) {
        val failure = assertThrows(AssertionError::class.java) { block() }
        assertTrue("必须因「$reason」被拦，实际：${failure.message}", failure.message.orEmpty().contains(reason))
    }

    private companion object {
        const val RUN = "¥999999999.99"
        const val ORDER = "374123456789012345678901"
        const val SERIAL_32 = "12345678901234567890123456789012"
        const val SERIAL_20 = "20261012100000123456"
        const val GET = "获取验证码"
        const val COUNTDOWN = "59 秒后重新获取"
        const val ENLARGED = 3f
        const val SHRUNK = -3f
        const val BOX = "self_box"
        const val ROW = "self_row"
        const val SERIAL = "self_serial"
        const val COPY = "self_copy"
        const val ENTRY = "self_entry"
        const val OTHER = "self_other"
        const val FIELD = "self_field"
        val PROBE_COPIES =
            listOf(
                "app/src/test/kotlin/com/couli/app/numwrap/NumWrapProbe.kt",
                "feature/orders/src/test/kotlin/com/couli/feature/orders/NumWrapProbe.kt",
                "core/designsystem/src/test/kotlin/com/couli/core/designsystem/NumWrapProbe.kt",
                "feature/login/src/test/kotlin/com/couli/feature/login/NumWrapProbe.kt",
                "feature/agent/src/test/kotlin/com/couli/feature/agent/NumWrapProbe.kt",
            )
    }
}
