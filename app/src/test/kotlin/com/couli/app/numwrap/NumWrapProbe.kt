package com.couli.app.numwrap

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

// FU-AND-NUM-WRAP 测试 v3 共用量具（v7.1 起全页扫描放行放大强调）。本文件在 app / feature:orders / core:designsystem / feature:login / feature:agent 五个测试源码集里逐字相同
// （只有 package 行不同；NumWrapProbeSelfTest 核对），正反例自测见 app 的 NumWrapProbeSelfTest。
//
// 判据 1（三端统一）：订单号、金额、手机号 / 脱敏账号、流水号在任何字号和宽度下都不能从中间断开；放不下时整段换行、上下排或框内横向滚动；不许缩字。

/** 期望的排版令牌：[fontSize] 为主题令牌字号，[fontScale] 为设定字号倍数；为空时不核令牌（全页扫描用，仍核区间有效字号不小于整体，放大强调放行）。 */
internal data class NumToken(
    val fontSize: TextUnit? = null,
    val fontScale: Float? = null,
)

/**
 * 一个字段断言：[scope]（具体字段的容器）之下完整值 [value] 的**出现次数**恰好 [count]；[exact] 为真时只认文字恰好等于 [value] 的节点
 * （区分「明细值」与含同一号码的说明句）。
 */
internal data class NumField(
    val value: String,
    val where: String,
    val scope: SemanticsMatcher,
    val token: NumToken = NumToken(),
    val count: Int = 1,
    val exact: Boolean = false,
)

/** 流水号断言：[row] 是明细整行（宽度基准），[copy] 是【复制】节点（可空）。 */
internal data class NumSerialSpec(
    val serial: String,
    val row: SemanticsNode,
    val copy: SemanticsNode?,
    val what: String,
    val token: NumToken,
)

/** 字段断言与数字串判定。 */
internal object NumWrap {
    /** 全页扫描用的数字串：脱敏号 / 金额 / 空格分段手机号 / 8 位以上连续数字。 */
    val RUN = Regex("""\d+\*{2,}\d+|[+\-−]?¥\d+(?:\.\d+)?|1\d{2} \d{4} \d{4}|\d{8,}""")

    val HAS_LAYOUT: SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)

    /** 标记为 [tag] 的节点自身或其后代（字段容器）。 */
    fun within(tag: String): SemanticsMatcher = hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))

    /**
     * 字段断言：
     * ① [NumField.scope] 之下（[NumField.exact] 时只认整串相等的节点）完整值的**出现次数**之和恰好 [NumField.count]；
     * ② 同一字段容器里不许另有被拆开的副本：容器内所有文字节点按树序拼接后，完整值出现次数必须等于 ① 的次数（exact 字段同样核）；
     *    所以 [NumField.scope] 必须是**字段容器**（按 tag 或 [segment] 按标签取的范围），不能用「已经含完整值的节点」来定义范围；
     * ③ 每一处出现都整段不断、不截断、不缩字、首尾可达。
     */
    fun assertField(
        rule: ComposeContentTestRule,
        field: NumField,
    ) {
        val value = field.value
        val textMatcher = if (field.exact) hasText(value) else hasText(value, substring = true)
        val all = rule.onAllNodes(textMatcher and HAS_LAYOUT and field.scope, useUnmergedTree = true)
        val nodes = List(all.fetchSemanticsNodes().size) { all[it] }
        val found = nodes.sumOf { occurrences(layout(it).layoutInput.text.text, value) }
        assertEquals("${field.where}：「$value」应恰好完整出现 ${field.count} 次（实际 $found；被拆进多个 Text 不算）", field.count, found)
        // exact 字段同样核：容器里一个整串节点 + 两个拼成同值的节点，拼接后是 2 处、整串节点只有 1 处
        val joined = joinedText(rule, field.scope)
        assertEquals(
            "${field.where}：同一字段容器里另有被拆开的「$value」（拼接后 ${occurrences(joined, value)} 处，完整节点里 $found 处）",
            found,
            occurrences(joined, value),
        )
        nodes.forEach { checkRun(rule, it, value, "${field.where}：「$value」", field.token) }
    }

    /** [node] 里 [run] 的每一处（不只第一处）都整段不断、不截断、不缩字、首尾可达。 */
    fun checkRun(
        rule: ComposeContentTestRule,
        node: SemanticsNodeInteraction,
        run: String,
        what: String,
        token: NumToken = NumToken(),
    ) {
        val text = layout(node).layoutInput.text.text
        val ranges = starts(text, run).map { it until it + run.length }
        assertTrue("$what：节点文字「$text」不含完整数字串", ranges.isNotEmpty())
        ranges.forEach { range ->
            val result = layout(node)
            val first = result.getLineForOffset(range.first)
            val last = result.getLineForOffset(range.last)
            assertEquals("$what 从中间断行（串首第 ${first + 1} 行、串尾第 ${last + 1} 行；各行=${lines(result)}）", first, last)
            assertNoTruncation(result, what)
            NumShrink.assertNotShrunk(node.fetchSemanticsNode(), result, range, what, token)
            NumReach.assertReachable(rule, node, range, what)
        }
    }

    /**
     * 长流水号（brief §2.3）：自然单行宽（按区间有效样式量）不超过整行 [NumSerialSpec.row] 宽时必须一行（可横向滚动）；超过时才允许
     * 「整段换到独占一行后按字符折行」：节点只显示这串号、从行首起排、左右边界与整行对齐、与【复制】不重叠、每个字符都在可视范围内。
     */
    fun checkSerial(
        rule: ComposeContentTestRule,
        node: SemanticsNodeInteraction,
        spec: NumSerialSpec,
    ) {
        val serial = spec.serial
        val result = layout(node)
        val shown = result.layoutInput.text.text
        val start = shown.indexOf(serial)
        assertTrue("${spec.what}：节点文字「$shown」不含流水号", start >= 0)
        val range = start until start + serial.length
        val natural = NumShrink.naturalWidth(result, range, spec.token)
        val row = spec.row.layoutInfo.coordinates.size.width
        val broken = result.getLineForOffset(range.first) != result.getLineForOffset(range.last)
        if (!broken || natural <= row) {
            checkRun(rule, node, serial, "${spec.what}（自然单行宽 ${natural.toInt()} px，整行 $row px）", spec.token)
        } else {
            NumSerial.assertOwnRow(node.fetchSemanticsNode(), result, range, spec)
            NumShrink.assertNotShrunk(node.fetchSemanticsNode(), result, range, spec.what, spec.token)
        }
    }

    /**
     * 全页扫描（只作补充，不能代替字段断言）：语义树里每个文字节点上的每一处数字串（[RUN]）都整段不断；[serials] 列出的流水号交给 [checkSerial]。
     * 返回检查的处数，调用方须断言不少于预期（防止空扫）。注意：带 clearAndSetSemantics 的文字（如 AmountGrid 数值）不在语义树里，扫不到。
     */
    fun scanPage(
        rule: ComposeContentTestRule,
        where: String,
        serials: Set<String> = emptySet(),
    ): Int {
        val all = rule.onAllNodes(HAS_LAYOUT and hasAnyText(), useUnmergedTree = true)
        var checked = 0
        repeat(all.fetchSemanticsNodes().size) { index ->
            val text = layout(all[index]).layoutInput.text.text
            val runs = RUN.findAll(text).map { it.value }
            runs.filter { it !in serials }.distinct().forEach { run ->
                checkRun(rule, all[index], run, "$where（全页扫描「$text」里的「$run」）")
                checked++
            }
        }
        return checked
    }

    /**
     * 没有 tag 的字段容器：[scope] 之下按树序排列的文字节点里，开始标签 [from] 之后、结束标签 [until] 之前的一段。范围按标签取，与要找的值无关。
     * - [from] 为 null 表示从第一个节点开始；不为 null 时这个标签在容器里必须**恰好 1 个**（重复字段要分别给出容器，不能只查第一段）；
     * - [until] 为 null 表示**显式取到容器末尾**；不为 null 时结束标签必须恰好 1 个、且在开始标签之后。
     */
    fun segment(
        rule: ComposeContentTestRule,
        scope: SemanticsMatcher,
        from: String?,
        until: String?,
    ): SemanticsMatcher {
        val nodes = rule.onAllNodes(hasAnyText() and scope, useUnmergedTree = true).fetchSemanticsNodes()
        val texts = nodes.map(::textOf)
        val start = from?.let { label -> uniqueIndex(texts, label, "开始") + 1 } ?: 0
        val end = until?.let { label -> uniqueIndex(texts, label, "结束") } ?: nodes.size
        assertTrue("字段标签顺序不对：结束标签「$until」不在开始标签「$from」之后", end >= start)
        val ids = nodes.subList(start, end).map { it.id }.toSet()
        return SemanticsMatcher("字段「${from ?: "开头"}」到「${until ?: "容器末尾"}」之间") { it.id in ids }
    }

    private fun uniqueIndex(
        texts: List<String>,
        label: String,
        role: String,
    ): Int {
        val hits = texts.indices.filter { texts[it] == label }
        assertEquals("字段${role}标签「$label」在容器里必须恰好 1 个（实际 ${hits.size}）", 1, hits.size)
        return hits.single()
    }

    /** [scope]（缺省为全树）之下唯一完整含 [value] 的文字节点。 */
    fun nodesFor(
        rule: ComposeContentTestRule,
        value: String,
        scope: SemanticsMatcher? = null,
    ): SemanticsNodeInteraction {
        val matcher = hasText(value, substring = true) and HAS_LAYOUT
        val all = rule.onAllNodes(scope?.let { matcher and it } ?: matcher, useUnmergedTree = true)
        assertEquals("「$value」应恰好在 1 个文字节点里完整出现", 1, all.fetchSemanticsNodes().size)
        return all[0]
    }

    fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    fun lines(result: TextLayoutResult): List<String> {
        val text = result.layoutInput.text.text
        return (0 until result.lineCount).map { text.substring(result.getLineStart(it), result.getLineEnd(it)) }
    }

    /** [text] 里 [value] 的非重叠出现次数。 */
    fun occurrences(
        text: String,
        value: String,
    ): Int = starts(text, value).size

    private fun starts(
        text: String,
        value: String,
    ): List<Int> {
        val next = { from: Int -> text.indexOf(value, from).takeIf { it >= 0 } }
        return generateSequence(next(0)) { prev -> next(prev + value.length) }.toList()
    }

    /** [scope] 之下所有文字节点（树序）的文字拼接，用来发现被拆进多个 Text 的副本。 */
    private fun joinedText(
        rule: ComposeContentTestRule,
        scope: SemanticsMatcher,
    ): String {
        val nodes = rule.onAllNodes(hasAnyText() and scope, useUnmergedTree = true).fetchSemanticsNodes()
        return nodes.joinToString("", transform = ::textOf)
    }

    private fun textOf(node: SemanticsNode): String {
        val parts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
        val editable = node.config.getOrNull(SemanticsProperties.EditableText)?.text
        return (parts.map { it.text } + listOfNotNull(editable)).joinToString("")
    }

    private fun hasAnyText(): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) or SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)

    private fun assertNoTruncation(
        result: TextLayoutResult,
        what: String,
    ) {
        assertFalse("$what 所在文字被省略号截断", (0 until result.lineCount).any(result::isLineEllipsized))
        assertFalse("$what 所在文字被 maxLines / 高度截掉", result.didOverflowHeight)
    }
}

/**
 * 不缩字（判据 1「不许缩小字号硬塞」），两类判定分开：
 * - **字号确实变了**（字号只留 0.001sp 浮点容差）：数字区间每个字符叠加后的有效样式（整体样式按顺序叠加覆盖该字符的 SpanStyle，后者覆盖前者）里，字号必须等于令牌，
 *   字距不得为负，横向压缩 scaleX 不得小于 1；整体字号不是令牌也算（autoSize 改了排版字号时在这里拦）。不传令牌字号（全页扫描）时只要求有效字号
 *   **不小于**整体字号（v7.1：放大强调如提现估算行 17sp 到账金额放行，比整体小仍算缩字）。
 * - **排版舍入容差**：用同源基准（保留区间的字体、字重、字体特性、非负字距，只把字号归到令牌、去掉负字距和横向压缩）单行量出自然宽，实际排出宽
 *   不得小于「自然宽 − 1.5 px − 0.5%」；不传令牌字号时基准保留区间自身的（已核过不小于整体的）字号。
 * 另核 localToRoot 的宽、高两个方向都没有被 graphicsLayer / scale 压缩（各留 1.5 px 舍入容差，所以不排除这个幅度以内的视觉缩放）。
 */
internal object NumShrink {
    private const val ROUND_PX = 1.5f
    private const val ROUND_RATIO = 0.005f

    fun assertNotShrunk(
        node: SemanticsNode,
        result: TextLayoutResult,
        range: IntRange,
        what: String,
        token: NumToken,
    ) {
        val style = result.layoutInput.style
        val target = token.fontSize ?: style.fontSize
        token.fontScale?.let { assertEquals("$what 没有按设定字号排版", it, result.layoutInput.density.fontScale, 0.001f) }
        range.forEach { offset ->
            val effective = effective(result, offset)
            val size = resolved(effective.fontSize, style.fontSize)
            val label = "$what 缩字：第 ${offset - range.first + 1} 个字符的有效字号 $size"
            if (token.fontSize == null) {
                assertTrue("$label < 整体字号 $target", size.value >= target.value - 0.001f)
            } else {
                assertEquals("$label ≠ 令牌 $target", target.value, size.value, 0.001f)
            }
            val spacing = effective.letterSpacing
            assertFalse("$what 缩字：第 ${offset - range.first + 1} 个字符负字距 $spacing", spacing.isSpecified && spacing.value < 0f)
            assertTrue("$what 缩字：横向压缩 scaleX", (effective.textGeometricTransform?.scaleX ?: 1f) >= 1f)
        }
        val natural = naturalWidth(result, range, token)
        val actual = drawnWidth(result, range)
        val slack = ROUND_PX + natural * ROUND_RATIO
        assertTrue(
            "$what 缩字：实际排出宽 $actual px < 同源自然宽 $natural px − 舍入容差 $slack px（区间字号 / 负字距 / scaleX / autoSize）",
            actual >= natural - slack,
        )
        assertNotTransformed(node.layoutInfo.coordinates, what)
    }

    /** 同源基准：数字区间按有效字体属性（字号归到令牌、去掉负字距与横向压缩）单行量出的自然宽（px）。 */
    fun naturalWidth(
        result: TextLayoutResult,
        range: IntRange,
        token: NumToken,
    ): Float {
        val input = result.layoutInput
        val run = input.text.text.substring(range.first, range.last + 1)
        val spans =
            input.text.spanStyles
                .filter { it.start <= range.last && it.end > range.first }
                .map { span ->
                    val from = maxOf(span.start, range.first) - range.first
                    val to = minOf(span.end, range.last + 1) - range.first
                    AnnotatedString.Range(neutral(span.item, keepSize = token.fontSize == null), from, to)
                }
        val style = input.style
        val spacing = style.letterSpacing.takeUnless { it.isSpecified && it.value < 0f } ?: TextUnit.Unspecified
        val transform = style.textGeometricTransform?.takeIf { it.scaleX >= 1f }
        val reference = style.copy(fontSize = token.fontSize ?: style.fontSize, letterSpacing = spacing, textGeometricTransform = transform)
        val measurer = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection)
        val measured = measurer.measure(AnnotatedString(run, spans), reference, softWrap = false, maxLines = 1)
        return measured.multiParagraph.maxIntrinsicWidth
    }

    /** 数字区间在排版里实际占的横向宽（多行时各行段相加）。 */
    fun drawnWidth(
        result: TextLayoutResult,
        range: IntRange,
    ): Float {
        val lines = result.getLineForOffset(range.first)..result.getLineForOffset(range.last)
        val total =
            lines.sumOf { line ->
                val from = maxOf(range.first, result.getLineStart(line))
                val to = minOf(range.last, result.getLineEnd(line) - 1)
                val left = minOf(result.getBoundingBox(from).left, result.getBoundingBox(to).left)
                val right = maxOf(result.getBoundingBox(from).right, result.getBoundingBox(to).right)
                (right - left).toDouble()
            }
        return total.toFloat()
    }

    /** 字符 [offset] 处叠加后的有效样式：整体样式依次叠加覆盖它的 SpanStyle（后面的覆盖前面的）。 */
    private fun effective(
        result: TextLayoutResult,
        offset: Int,
    ): SpanStyle =
        result.layoutInput.text.spanStyles
            .filter { offset >= it.start && offset < it.end }
            .fold(result.layoutInput.style.toSpanStyle()) { acc, span -> acc.merge(span.item) }

    /** em 字号按整体字号换算成 sp。 */
    private fun resolved(
        size: TextUnit,
        base: TextUnit,
    ): TextUnit = if (size.isSpecified && size.isEm) TextUnit(base.value * size.value, base.type) else size

    /** 去掉字号（[keepSize] 为真时保留）、负字距与横向压缩，保留字体、字重、字体特性等。 */
    private fun neutral(
        style: SpanStyle,
        keepSize: Boolean,
    ): SpanStyle {
        val spacing = style.letterSpacing.takeUnless { it.isSpecified && it.value < 0f } ?: TextUnit.Unspecified
        val transform = style.textGeometricTransform?.takeIf { it.scaleX >= 1f }
        val size = if (keepSize) style.fontSize else TextUnit.Unspecified
        return style.copy(fontSize = size, letterSpacing = spacing, textGeometricTransform = transform)
    }

    private fun assertNotTransformed(
        coordinates: LayoutCoordinates,
        what: String,
    ) {
        val w = coordinates.size.width.toFloat()
        val h = coordinates.size.height.toFloat()
        val origin = coordinates.localToRoot(Offset.Zero)
        val dx = coordinates.localToRoot(Offset(w, 0f)).x - origin.x
        val dy = coordinates.localToRoot(Offset(0f, h)).y - origin.y
        assertTrue("$what 缩字：graphicsLayer / scale 视觉压缩（布局 $w×$h，绘制 $dx×$dy）", abs(dx - w) <= 1.5f && abs(dy - h) <= 1.5f)
    }
}

/** 首尾可达（判据 1「框内横向滚动」须真能滚到）：可视窗口 = 各级布局祖先横向范围的交集（含内层裁切层）。 */
internal object NumReach {
    private const val BIG = 100_000f
    private const val TOLERANCE_PX = 2f
    private const val CURSOR_DP = 3f
    private const val HIGHLIGHT_SHARE = 0.5f

    fun assertReachable(
        rule: ComposeContentTestRule,
        node: SemanticsNodeInteraction,
        range: IntRange,
        what: String,
    ) {
        val semantics = node.fetchSemanticsNode()
        val scroller = horizontalScroller(semantics)
        when {
            scroller != null -> {
                val handle = rule.onNode(byId(scroller.id), useUnmergedTree = true)
                handle.performSemanticsAction(SemanticsActions.ScrollBy) { it(-BIG, 0f) }
                rule.waitForIdle()
                assertCharVisible(node, range.first, "$what 横向滚到最左后串首仍不可见")
                handle.performSemanticsAction(SemanticsActions.ScrollBy) { it(BIG, 0f) }
                rule.waitForIdle()
                assertCharVisible(node, range.last, "$what 横向滚到最右后串尾仍不可见（滚动语义是假的，或内层还有裁切）")
            }

            semantics.config.contains(SemanticsProperties.EditableText) && !fullyVisible(node, range) -> {
                assertFieldReaches(rule, node, range, what)
            }

            else -> {
                assertCharVisible(node, range.first, "$what 串首不可见：横向超出可视范围（且不在可滚动容器里）")
                assertCharVisible(node, range.last, "$what 串尾不可见：横向超出可视范围（且不在可滚动容器里）")
            }
        }
    }

    /** 可视窗口（root 坐标的横向区间）：自身与各级布局祖先横向范围的交集。 */
    fun window(coordinates: LayoutCoordinates): ClosedFloatingPointRange<Float> {
        var left = Float.NEGATIVE_INFINITY
        var right = Float.POSITIVE_INFINITY
        var cursor: LayoutCoordinates? = coordinates
        while (cursor != null) {
            val x = cursor.positionInRoot().x
            left = maxOf(left, x)
            right = minOf(right, x + cursor.size.width)
            cursor = cursor.parentLayoutCoordinates
        }
        return left..right
    }

    /** 纵向可视窗口（root 坐标）：自身与各级布局祖先纵向范围的交集。 */
    fun windowY(coordinates: LayoutCoordinates): ClosedFloatingPointRange<Float> {
        var top = Float.NEGATIVE_INFINITY
        var bottom = Float.POSITIVE_INFINITY
        var cursor: LayoutCoordinates? = coordinates
        while (cursor != null) {
            val y = cursor.positionInRoot().y
            top = maxOf(top, y)
            bottom = minOf(bottom, y + cursor.size.height)
            cursor = cursor.parentLayoutCoordinates
        }
        return top..bottom
    }

    fun assertCharVisible(
        node: SemanticsNodeInteraction,
        offset: Int,
        message: String,
    ) {
        val semantics = node.fetchSemanticsNode()
        val box = NumWrap.layout(node).getBoundingBox(offset)
        val x = coordinatesOf(semantics).positionInRoot().x
        val visible = window(semantics.layoutInfo.coordinates)
        assertTrue(
            "$message（字符 ${(x + box.left).toInt()}..${(x + box.right).toInt()}，" +
                "可视 ${visible.start.toInt()}..${visible.endInclusive.toInt()} px）",
            x + box.left >= visible.start - 1f && x + box.right <= visible.endInclusive + 1f,
        )
    }

    fun coordinatesOf(node: SemanticsNode): LayoutCoordinates = node.layoutInfo.coordinates

    private fun fullyVisible(
        node: SemanticsNodeInteraction,
        range: IntRange,
    ): Boolean {
        val semantics = node.fetchSemanticsNode()
        val result = NumWrap.layout(node)
        val x = coordinatesOf(semantics).positionInRoot().x
        val visible = window(semantics.layoutInfo.coordinates)
        return x + result.getBoundingBox(range.first).left >= visible.start - 1f &&
            x + result.getBoundingBox(range.last).right <= visible.endInclusive + 1f
    }

    /**
     * 单行输入框放不下（不凭 EditableText 放行）：①只有一行；②输入框自身没被外层裁切；③直接读滚动后的字符位置——聚焦后先把光标收在字符之后
     * （框内滚到能看见它），截图；再只选中这一个字符，截图。两图的差异**只许是这一个字符的选区高亮（加上消失的光标）**：
     * 所有差异列必须连成一段（断开不超过 1 列），段宽不小于字符宽 − 2 px、不大于字符宽 + 光标余量（3dp + 2 px）——装饰底色随选区变化、文字随选区
     * 平移等其它绘制差异都会让差异超出这一段而被拦下；段内差异像素里占多数的颜色（选区高亮色）必须覆盖其中至少一半。
     * 串首、串尾两个字符都要满足。看不到高亮（滚不到）、被框边截掉、或差异不是单个字符高亮都拦下。
     */
    private fun assertFieldReaches(
        rule: ComposeContentTestRule,
        node: SemanticsNodeInteraction,
        range: IntRange,
        what: String,
    ) {
        val semantics = node.fetchSemanticsNode()
        val result = NumWrap.layout(node)
        assertEquals("$what 输入框放不下时必须是单行（框内横向滚动）", 1, result.lineCount)
        val visible = window(semantics.layoutInfo.coordinates)
        val own = coordinatesOf(semantics).positionInRoot().x
        val ownRight = own + semantics.layoutInfo.coordinates.size.width
        assertTrue("$what 输入框本身被外层裁切", own >= visible.start - 1f && ownRight <= visible.endInclusive + 1f)
        runCatching { node.performClick() }
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        val allowance = CURSOR_DP * semantics.layoutInfo.density.density + TOLERANCE_PX
        // 只需证明「光标在串尾时尾字符可见」（编排 r5 取舍）；串首由单行输入框初始从开头显示保证。
        // 已知边界：旧版 BasicTextField 的框内滚动偏移在测试里拿不到，只能用选区差分；局部装饰冒充高亮这类伪造交给实现评审核对。
        val glyph = result.getBoundingBox(range.last).width
        val shots = Shots(semantics.boundsInRoot, range.last, result.layoutInput.text.length, tail = true)
        val problem = highlightProblem(rule, node, shots, glyph..(glyph + allowance))
        assertTrue("$what 光标移到串尾后串尾仍不在框内（$problem）", problem == null)
    }

    /**
     * 选中第 [offset] 个字符前后两张框内截图的差异是否恰好是这个字符的选区高亮：null 表示是；否则返回原因。[width] 是允许的高亮宽度
     * （下限再让 2 px 舍入）。
     */
    private fun highlightProblem(
        rule: ComposeContentTestRule,
        node: SemanticsNodeInteraction,
        shots: Shots,
        width: ClosedFloatingPointRange<Float>,
    ): String? {
        val offset = shots.offset
        val select = { start: Int, end: Int ->
            node.performSemanticsAction(SemanticsActions.SetSelection) { it(start, end, false) }
            rule.waitForIdle()
            NumPixels.crop(NumPixels.of(rule, node.fetchSemanticsNode()), shots.box)
        }
        // 输入框滚动跟随「变了的那一端」：串首先从末尾选中该字符（跟随起点、滚到开头），再收成光标在其后（光标仍在视口内，不再滚动）；
        // 串尾先从开头把光标放到它之后（跟随终点、滚到末尾），再选中它（起点仍在视口内，不再滚动）。两张图因此是同一滚动位置。
        val (collapsed, selected) =
            if (shots.tail) {
                select(0, 0)
                select(offset + 1, offset + 1) to select(offset, offset + 1)
            } else {
                select(shots.length, shots.length)
                val picked = select(offset, offset + 1)
                select(offset + 1, offset + 1) to picked
            }
        val columns = NumPixels.differingColumns(collapsed, selected)
        val gaps = columns.zipWithNext().count { (a, b) -> b - a > 2 }
        val span = if (columns.isEmpty()) 0 else columns.last() - columns.first() + 1
        val share = NumPixels.dominantShare(collapsed, selected)
        return when {
            columns.isEmpty() -> "选中该字符后框内没有任何高亮"
            gaps > 0 -> "差异不连成一段（断开 $gaps 处，列 ${columns.first()}..${columns.last()}），不是单个字符的高亮"
            span < width.start - TOLERANCE_PX -> "高亮宽 $span px < 字符宽 ${width.start} px，被框边截掉"
            span > width.endInclusive -> "差异宽 $span px > 字符宽 + 光标余量 ${width.endInclusive} px，还有别的绘制随选区变化"
            share < HIGHLIGHT_SHARE -> "差异像素里高亮色只占 ${"%.2f".format(share)}，不像选区高亮"
            else -> null
        }
    }

    /** 一次选区截图的布置：[box] 框内区域，[offset] 目标字符，[length] 全文长度，[tail] 是否串尾。 */
    private class Shots(
        val box: Rect,
        val offset: Int,
        val length: Int,
        val tail: Boolean,
    )

    fun horizontalScroller(node: SemanticsNode): SemanticsNode? {
        var cursor: SemanticsNode? = node
        while (cursor != null && cursor.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) == null) cursor = cursor.parent
        return cursor
    }

    private fun byId(id: Int): SemanticsMatcher = SemanticsMatcher("semantics id $id") { it.id == id }
}

/** 流水号「独占整行后按字符折行」放宽口径的严格证明。 */
internal object NumSerial {
    fun assertOwnRow(
        node: SemanticsNode,
        result: TextLayoutResult,
        range: IntRange,
        spec: NumSerialSpec,
    ) {
        val what = spec.what
        val row = unclipped(spec.row)
        val copy = spec.copy?.let(::unclipped)
        val text = result.layoutInput.text.text
        assertEquals("$what 折行时节点只能显示这串号（各行=${NumWrap.lines(result)}）", text.substring(range.first, range.last + 1), text.trim())
        assertEquals("$what 折行必须从行首起排", result.getLineStart(result.getLineForOffset(range.first)), range.first)
        val box = unclipped(node)
        val d = node.layoutInfo.density.density
        assertTrue(
            "$what 在中间断行且没有独占整行（号码 ${box.left.toInt()}..${box.right.toInt()}，整行 ${row.left.toInt()}..${row.right.toInt()} px；" +
                "各行=${NumWrap.lines(result)}）",
            abs(box.left - row.left) <= d && abs(box.right - row.right) <= d,
        )
        copy?.let { assertFalse("$what 与【复制】重叠（号码 $box，复制 $it）", box.overlaps(it)) }
        val origin = NumReach.coordinatesOf(node).positionInRoot().x
        val visible = NumReach.window(node.layoutInfo.coordinates)
        (range.first..range.last).forEach { offset ->
            val glyph = result.getBoundingBox(offset)
            assertTrue(
                "$what 第 ${offset - range.first + 1} 个字符不在可视范围内",
                origin + glyph.left >= visible.start - 1f && origin + glyph.right <= visible.endInclusive + 1f,
            )
        }
    }

    /** 未被祖先裁切的布局框（root 坐标）：号码在屏外时 boundsInRoot 会被裁成空框。 */
    fun unclipped(node: SemanticsNode): Rect {
        val coordinates = node.layoutInfo.coordinates
        val origin = coordinates.positionInRoot()
        return Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
    }
}

/** 「复制」与号码的相对位置：按滚动视口（横向、纵向可视窗口）和文字真实排版框判断，不用已被裁切的 boundsInRoot，不钉旧几何关系。 */
internal object NumCopy {
    private const val MIN_TARGET_DP = 47.5f

    fun assertCopyClear(
        rule: ComposeContentTestRule,
        number: SemanticsNodeInteraction,
        run: String,
        copyTag: String,
        what: String,
    ) {
        val copyText = rule.onAllNodes(NumWrap.HAS_LAYOUT and NumWrap.within(copyTag), useUnmergedTree = true)[0]
        val copyLayout = NumWrap.layout(copyText)
        assertEquals("$what：【复制】不拆行", 1, copyLayout.lineCount)
        runCatching { copyText.performScrollTo() }
        val copy = copyText.fetchSemanticsNode()
        val copyBox = glyphBox(copy, copyLayout, 0, copyLayout.layoutInput.text.length - 1)
        val copyWindow = NumReach.window(copy.layoutInfo.coordinates)
        val copyRows = NumReach.windowY(copy.layoutInfo.coordinates)
        assertTrue(
            "$what：【复制】文字上下被裁切（文字 ${copyBox.top.toInt()}..${copyBox.bottom.toInt()}，" +
                "可视 ${copyRows.start.toInt()}..${copyRows.endInclusive.toInt()} px）",
            copyBox.top >= copyRows.start - 1f && copyBox.bottom <= copyRows.endInclusive + 1f,
        )
        assertTrue(
            "$what：【复制】文字没有完整可见（文字 ${copyBox.left.toInt()}..${copyBox.right.toInt()}，" +
                "可视 ${copyWindow.start.toInt()}..${copyWindow.endInclusive.toInt()} px）",
            copyBox.left >= copyWindow.start - 1f && copyBox.right <= copyWindow.endInclusive + 1f,
        )
        val semantics = number.fetchSemanticsNode()
        val result = NumWrap.layout(number)
        val shown = result.layoutInput.text.text
        val start = shown.indexOf(run)
        val window = NumReach.window(semantics.layoutInfo.coordinates)
        val runGlyphs = glyphBox(semantics, result, start, start + run.length - 1)
        val runBox = Rect(maxOf(runGlyphs.left, window.start), runGlyphs.top, minOf(runGlyphs.right, window.endInclusive), runGlyphs.bottom)
        assertFalse("$what：【复制】压在可见的号码上（号码 $runBox，复制 $copyBox）", runBox.overlaps(copyBox))
        val target = generateSequence(copy) { it.parent }.firstOrNull { it.config.contains(SemanticsActions.OnClick) }
        assertNotNull("$what：【复制】不可点", target)
        val d = copy.layoutInfo.density.density
        assertTrue("$what：【复制】命中区 ≥48dp", checkNotNull(target).touchBoundsInRoot.height / d >= MIN_TARGET_DP)
    }

    /** 字符 [from]..[to] 的真实排版框（root 坐标，未裁切；同一行）。 */
    private fun glyphBox(
        node: SemanticsNode,
        result: TextLayoutResult,
        from: Int,
        to: Int,
    ): Rect {
        val origin = NumReach.coordinatesOf(node).positionInRoot()
        val a = result.getBoundingBox(from)
        val b = result.getBoundingBox(to)
        return Rect(origin.x + minOf(a.left, b.left), origin.y + a.top, origin.x + maxOf(a.right, b.right), origin.y + b.bottom)
    }
}

/**
 * 发码入口外观（判据 4，D10-1223 第 4 条）：只看入口节点自身，不向上借祖先的底色。判据**收窄为**：入口布局框内、文字框（外扩 2dp）之外的异色墨迹
 * 必须把文字四面围住、并且**连通闭合**（从文字外沿出发只走底色像素走不到入口框边缘），再满足「连续底色」（围住区域内异色像素 ≥80%）或
 * 「闭合描边」（外框上、下边去掉圆角段后连续 ≥90%，左、右边有直线段时同样要求）。
 * 它证明「入口自身画出了把文字围住的容器」，不证明配色、圆角、尺寸符合 M3 规范。
 */
internal object SendCodeLook {
    private const val MIN_TARGET_DP = 47.5f

    /** 入口 = 标记节点自身（可点）或其后代里的第一个可点节点；不向上找祖先。 */
    fun entry(tagged: SemanticsNode): SemanticsNode {
        val down = mutableListOf<SemanticsNode>()
        val queue = ArrayDeque(listOf(tagged))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next.config.contains(SemanticsActions.OnClick)) down += next
            queue.addAll(next.children)
        }
        assertTrue("发码入口自身及后代里没有可点节点（不向上借祖先）", down.isNotEmpty())
        return down.first()
    }

    fun assertButtonSemantics(
        tagged: SemanticsNode,
        where: String,
    ): SemanticsNode {
        val button = entry(tagged)
        assertEquals("$where：发码入口要有按钮语义 Role.Button", Role.Button, button.config.getOrNull(SemanticsProperties.Role))
        val d = button.layoutInfo.density.density
        val touch = button.touchBoundsInRoot
        assertTrue(
            "$where：发码入口命中区须 ≥48dp（实际 ${touch.width / d} × ${touch.height / d} dp）",
            touch.width / d >= MIN_TARGET_DP && touch.height / d >= MIN_TARGET_DP,
        )
        return button
    }

    /** 200% 下：按钮语义 + ≥48dp + 入口自身画出把文字围住的连续底色或闭合描边。 */
    fun assertLooksLikeButton(
        rule: ComposeContentTestRule,
        tagged: SemanticsNode,
        where: String,
    ) {
        val button = assertButtonSemantics(tagged, where)
        val reason = NumPixels.containerProblem(rule, button)
        assertTrue("$where：发码入口只是光文字——$reason", reason == null)
    }

    /**
     * 200% 下倒计时态（D10-1223，v7.1）：「N 秒后重新获取」也是按钮，不可点时用禁用按钮表示——外观同 [assertLooksLikeButton]
     * （Role.Button、命中区 ≥48、自身围住文字的底色或描边），入口还必须带 Disabled。
     */
    fun assertDisabledButton(
        rule: ComposeContentTestRule,
        tagged: SemanticsNode,
        where: String,
    ) {
        assertLooksLikeButton(rule, tagged, where)
        val disabled = entry(tagged).config.contains(SemanticsProperties.Disabled)
        assertTrue("$where：倒计时不可点，要用禁用按钮表示（入口没有 Disabled）", disabled)
    }

    /** 默认字号「外观不变」：仍没有把文字围住的连续底色或闭合描边（光文字链接）。 */
    fun assertPlainText(
        rule: ComposeContentTestRule,
        tagged: SemanticsNode,
        where: String,
    ) {
        assertTrue("$where：默认字号下外观变了（出现了把文字围住的底色或描边）", NumPixels.containerProblem(rule, entry(tagged)) != null)
    }
}

/** 像素采样（Robolectric 必须 View.draw；节点所在窗口各自取图，弹窗也能采）。 */
internal object NumPixels {
    private const val TOLERANCE = 12
    private const val RING_DP = 3
    private const val TEXT_PAD_DP = 2
    private const val EDGE_PX = 3
    private const val FILL_RATIO = 0.8f
    private const val EDGE_RATIO = 0.9f
    private const val CORNER_DP = 24
    private const val CONNECT_TOLERANCE = 3

    fun of(
        rule: ComposeContentTestRule,
        node: SemanticsNode,
    ): Bitmap {
        val view = (node.root as ViewRootForTest).view
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    fun crop(
        bitmap: Bitmap,
        box: Rect,
    ): Bitmap {
        val left = box.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = box.top.toInt().coerceIn(0, bitmap.height - 1)
        val width = (box.right.toInt() - left).coerceIn(1, bitmap.width - left)
        val height = (box.bottom.toInt() - top).coerceIn(1, bitmap.height - top)
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    /** 图里出现最多的颜色（当作底色）。 */
    fun modeColor(bitmap: Bitmap): Int {
        val counts = HashMap<Int, Int>()
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) counts.merge(bitmap.getPixel(x, y), 1, Int::plus)
        return counts.maxByOrNull { it.value }?.key ?: 0
    }

    /** [b] 相对 [a] 变了的像素里，占多数的那种颜色（在 [b] 里的颜色）所占比例；没有差异时为 0。 */
    fun dominantShare(
        a: Bitmap,
        b: Bitmap,
    ): Float {
        val counts = HashMap<Int, Int>()
        for (y in 0 until minOf(a.height, b.height)) {
            for (x in 0 until minOf(a.width, b.width)) {
                if (!same(a.getPixel(x, y), b.getPixel(x, y))) counts.merge(b.getPixel(x, y), 1, Int::plus)
            }
        }
        val total = counts.values.sum()
        return if (total == 0) 0f else counts.values.max().toFloat() / total
    }

    /** 两张同尺寸图里有任一像素不同的列（升序）。 */
    fun differingColumns(
        a: Bitmap,
        b: Bitmap,
    ): List<Int> {
        val width = minOf(a.width, b.width)
        val height = minOf(a.height, b.height)
        return (0 until width).filter { x -> (0 until height).any { y -> !same(a.getPixel(x, y), b.getPixel(x, y)) } }
    }

    /** 入口外观的问题；null 表示入口自身画出了把文字围住的连续底色或闭合描边。 */
    fun containerProblem(
        rule: ComposeContentTestRule,
        button: SemanticsNode,
    ): String? {
        val pixels = of(rule, button)
        val d = button.layoutInfo.density.density
        val box = button.boundsInRoot
        val text = textBox(button).inflate(TEXT_PAD_DP * d)
        val background = ringColor(pixels, box, (RING_DP * d).toInt().coerceAtLeast(1))
        val inked = { x: Int, y: Int ->
            x in 0 until pixels.width && y in 0 until pixels.height && !same(pixels.getPixel(x, y), background)
        }
        val outside = { x: Int, y: Int -> !text.contains(Offset(x + 0.5f, y + 0.5f)) }
        val xs = box.left.toInt() until box.right.toInt()
        val ys = box.top.toInt() until box.bottom.toInt()
        val ink = ys.flatMap { y -> xs.filter { x -> outside(x, y) && inked(x, y) }.map { x -> x to y } }
        if (ink.isEmpty()) return "入口框内文字之外没有任何底色或描边"
        val left = ink.minOf { it.first }.toFloat()
        val top = ink.minOf { it.second }.toFloat()
        val outer = Rect(left, top, ink.maxOf { it.first } + 1f, ink.maxOf { it.second } + 1f)
        val encloses = outer.left < text.left && outer.right > text.right && outer.top < text.top && outer.bottom > text.bottom
        val ring = ring(outer, text)
        val fill = ring.count { (x, y) -> inked(x, y) }.toFloat() / ring.size.coerceAtLeast(1)
        val edges = edgeContinuity(outer, (CORNER_DP * d).toInt(), inked)
        val stroke = edges.all { it >= EDGE_RATIO }
        val closed = closedAround(pixels, box, text, background)
        val verdict = "四面围住=$encloses，连通闭合=$closed，底色覆盖 ${"%.2f".format(fill)}，描边连续 ${edges.map { "%.2f".format(it) }}"
        val looks = encloses && closed && (fill >= FILL_RATIO || stroke)
        return if (looks) null else "入口自身没有把文字围住的连续底色或闭合描边（$verdict）"
    }

    /**
     * 连通性：从文字框（外扩后）外沿紧邻的「底色」像素出发，只走与底色几乎相同（差 ≤3）的像素、四连通扩散；能走到入口布局框边缘就说明文字没有被
     * 一圈连续的墨迹围住（两条不相连的横线、零散点、单侧图标都会漏出去）。实底按钮外沿全是底色以外的墨迹，没有起点，算闭合。
     */
    private fun closedAround(
        pixels: Bitmap,
        box: Rect,
        text: Rect,
        background: Int,
    ): Boolean {
        val flood = Flood(pixels, box, background)
        val tl = text.left.toInt() - 1
        val tr = text.right.toInt()
        val tt = text.top.toInt() - 1
        val tb = text.bottom.toInt()
        (tl..tr).forEach { x -> listOf(tt, tb).forEach { y -> flood.push(x, y) } }
        (tt..tb).forEach { y -> listOf(tl, tr).forEach { x -> flood.push(x, y) } }
        return !flood.escapes()
    }

    /** 只走与底色几乎相同（差 ≤3）的像素的四连通扩散；[escapes] 判断能否走到 [box] 边缘。 */
    private class Flood(
        private val pixels: Bitmap,
        box: Rect,
        private val background: Int,
    ) {
        private val left = box.left.toInt().coerceAtLeast(0)
        private val top = box.top.toInt().coerceAtLeast(0)
        private val right = (box.right.toInt() - 1).coerceAtMost(pixels.width - 1)
        private val bottom = (box.bottom.toInt() - 1).coerceAtMost(pixels.height - 1)
        private val width = right - left + 1
        private val seen = BooleanArray(width * (bottom - top + 1))
        private val queue = ArrayDeque<Pair<Int, Int>>()

        fun push(
            x: Int,
            y: Int,
        ) {
            if (!inside(x, y)) return
            val index = (y - top) * width + (x - left)
            if (seen[index] || !NumPixels.same(pixels.getPixel(x, y), background, NumPixels.CONNECT_TOLERANCE)) return
            seen[index] = true
            queue.add(x to y)
        }

        fun escapes(): Boolean {
            var escaped = false
            while (queue.isNotEmpty() && !escaped) {
                val (x, y) = queue.removeFirst()
                escaped = onEdge(x, y)
                listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1).forEach { (nx, ny) -> push(nx, ny) }
            }
            return escaped
        }

        private fun inside(
            x: Int,
            y: Int,
        ): Boolean = x in left..right && y in top..bottom

        private fun onEdge(
            x: Int,
            y: Int,
        ): Boolean = x == left || x == right || y == top || y == bottom
    }

    private fun ring(
        outer: Rect,
        text: Rect,
    ): List<Pair<Int, Int>> =
        (outer.top.toInt() until outer.bottom.toInt()).flatMap { y ->
            (outer.left.toInt() until outer.right.toInt())
                .filter { x -> !text.contains(Offset(x + 0.5f, y + 0.5f)) }
                .map { x -> x to y }
        }

    /** 外框四边（去掉圆角段，长度不足的边不计）的连续率：边内侧 3 px 带里有墨迹的比例。 */
    private fun edgeContinuity(
        outer: Rect,
        cornerPx: Int,
        inked: (Int, Int) -> Boolean,
    ): List<Float> {
        val r = minOf(cornerPx, (outer.height / 2).toInt(), (outer.width / 2).toInt())
        val left = outer.left.toInt()
        val top = outer.top.toInt()
        val right = outer.right.toInt() - 1
        val bottom = outer.bottom.toInt() - 1
        val horizontal = (left + r)..(right - r)
        val vertical = (top + r)..(bottom - r)
        val out = mutableListOf<Float>()
        if (!horizontal.isEmpty()) {
            out += rate(horizontal) { x -> (0 until EDGE_PX).any { inked(x, top + it) } }
            out += rate(horizontal) { x -> (0 until EDGE_PX).any { inked(x, bottom - it) } }
        }
        if (!vertical.isEmpty()) {
            out += rate(vertical) { y -> (0 until EDGE_PX).any { inked(left + it, y) } }
            out += rate(vertical) { y -> (0 until EDGE_PX).any { inked(right - it, y) } }
        }
        return out.ifEmpty { listOf(0f) }
    }

    private fun rate(
        span: IntRange,
        hit: (Int) -> Boolean,
    ): Float = span.count(hit).toFloat() / span.count()

    private fun textBox(node: SemanticsNode): Rect {
        val boxes = mutableListOf<Rect>()
        val queue = ArrayDeque(listOf(node))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next.config.contains(SemanticsProperties.Text)) boxes += next.boundsInRoot
            queue.addAll(next.children)
        }
        assertTrue("发码入口里没有文字", boxes.isNotEmpty())
        return boxes.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
    }

    private fun ringColor(
        pixels: Bitmap,
        box: Rect,
        ring: Int,
    ): Int {
        val counts = HashMap<Int, Int>()
        val top = (box.top.toInt() - ring).coerceIn(0, pixels.height - 1)
        val bottom = (box.bottom.toInt() + ring - 1).coerceIn(0, pixels.height - 1)
        val left = (box.left.toInt() - ring).coerceIn(0, pixels.width - 1)
        val right = (box.right.toInt() + ring - 1).coerceIn(0, pixels.width - 1)
        for (x in left..right) listOf(top, bottom).forEach { counts.merge(pixels.getPixel(x, it), 1, Int::plus) }
        for (y in top..bottom) listOf(left, right).forEach { counts.merge(pixels.getPixel(it, y), 1, Int::plus) }
        return counts.maxByOrNull { it.value }?.key ?: 0
    }

    fun same(
        a: Int,
        b: Int,
        tolerance: Int = TOLERANCE,
    ): Boolean =
        abs(android.graphics.Color.alpha(a) - android.graphics.Color.alpha(b)) <= tolerance &&
            abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) <= tolerance &&
            abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) <= tolerance &&
            abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) <= tolerance
}

/**
 * AmountGrid 数值的像素量具（数值文字带 clearAndSetSemantics，不在语义树里）。三条守卫：
 * ① 非空：短值对照恰好 [rows] 条墨迹带（每格标签 + 数值），压力值同样多（多出来就是从中间断行，少了就是没画出来）；
 * ② 独立字号基准：数值墨迹带的高度与「同样式、不受约束单行排出的同一串值」（[reference]）相差 ≤2 px——长短值一起缩字也拦得住；
 * ③ 逐格完整：每格数值墨迹宽不小于基准墨迹宽 − 2 px——单行裁切、没有滚动会被拦。
 */
internal object NumBands {
    private const val SLACK_PX = 2

    data class Band(
        val top: Int,
        val height: Int,
        val width: Int,
    )

    fun assertAmountRows(
        rule: ComposeContentTestRule,
        grids: Pair<SemanticsNode, SemanticsNode>,
        reference: SemanticsNode,
        what: String,
        rows: Int = 4,
    ) {
        val control = bands(rule, grids.first)
        val stress = bands(rule, grids.second)
        val base = bands(rule, reference)
        assertEquals("$what：短值对照应恰好 $rows 条墨迹带（标签 + 数值），实际 ${control.size}", rows, control.size)
        assertEquals("$what：数值从中间断行或没画出来（墨迹带 ${stress.size}，短值对照 ${control.size}）", control.size, stress.size)
        assertEquals("$what：独立基准应是一条墨迹带", 1, base.size)
        stress.filterIndexed { i, _ -> i % 2 == 1 }.forEachIndexed { cell, band ->
            assertTrue(
                "$what：第 ${cell + 1} 格数值缩字（墨迹带高 ${band.height} px，独立基准 ${base[0].height} px）",
                abs(band.height - base[0].height) <= SLACK_PX,
            )
            assertTrue(
                "$what：第 ${cell + 1} 格数值不完整（墨迹宽 ${band.width} px < 独立基准 ${base[0].width} px，被裁切或缩字）",
                band.width >= base[0].width - SLACK_PX,
            )
        }
    }

    fun bands(
        rule: ComposeContentTestRule,
        node: SemanticsNode,
    ): List<Band> {
        val pixels = NumPixels.crop(NumPixels.of(rule, node), NumSerial.unclipped(node))
        val background = NumPixels.modeColor(pixels)
        val inkOf = { y: Int -> (0 until pixels.width).filter { x -> !NumPixels.same(pixels.getPixel(x, y), background) } }
        val rows = (0 until pixels.height).map(inkOf)
        val out = mutableListOf<Band>()
        var start = -1
        rows.forEachIndexed { y, xs ->
            if (xs.isNotEmpty() && start < 0) start = y
            val closes = xs.isEmpty() || y == rows.lastIndex
            if (closes && start >= 0) {
                val span = rows.subList(start, y + 1).filter { it.isNotEmpty() }
                out += Band(start, y - start, span.maxOf { it.last() } - span.minOf { it.first() } + 1)
                start = -1
            }
        }
        return out
    }
}

/**
 * 金额截断提示断言（D10-1265）：[field] 定位金额（字段定位范围），[row] 是「所在行检查范围」（含标签、相邻列，比字段范围大），[spoken] 是读屏写法，
 * [expect] 为 true（极端值，独占一行仍放不下）/ false（常规或中等长度，放得下）。
 */
internal data class NumHintSpec(
    val field: NumField,
    val row: SemanticsMatcher,
    val spoken: String? = null,
    val expect: Boolean? = null,
)

/**
 * 实现须提供的语义契约（交回 §4 / §12）：共用金额件在金额文字或其祖先上声明名为 [KEY] 的 SemanticsPropertyKey，值对象有三个布尔属性
 * `scrollable`、`fadeEnd`、`indicator`（Kotlin 属性，getter 为 getScrollable / getFadeEnd / getIndicator）。量具按名字和反射读取，不依赖实现的类型。
 * 已知边界：语义可以被伪造（声明了却没画），这一点交给实现评审核对产品代码；量具只用像素做粗检。
 */
internal object NumOverflow {
    const val KEY = "AmountOverflow"

    data class State(
        val scrollable: Boolean,
        val fadeEnd: Boolean,
        val indicator: Boolean,
    )

    /** 从 [node] 向上找第一个声明了 [KEY] 的节点，读出三个状态；找不到返回 null。 */
    fun read(node: SemanticsNode): State? =
        generateSequence(node) { it.parent }
            .mapNotNull { cursor -> cursor.config.firstOrNull { it.key.name == KEY }?.value }
            .firstOrNull()
            ?.let { value -> State(flag(value, "Scrollable"), flag(value, "FadeEnd"), flag(value, "Indicator")) }

    private fun flag(
        value: Any,
        name: String,
    ): Boolean = value.javaClass.getMethod("get$name").invoke(value) as Boolean
}

/**
 * 金额横滑口径（D10-1265，三端同口径），「语义契约 + 像素粗检」：
 * ① 语义：极端值 [NumOverflow] 三个状态全为 true，常规 / 中等长度全为 false；语义的 scrollable 必须与实际横滑（HorizontalScrollAxisRange.maxValue > 0）一致；
 * ② 先独占一行：实际横滑时，所在行检查范围里除金额文字及其祖先外，没有文字或可点节点（含横滑 Row 里的标签、相邻列）与横滑框同一行；横滑框宽度不小于
 *    该范围内容横向跨度的 75%（用足整行）；
 * ③ 横滑从开头露出（滚动位置 0、串首可见）；像素粗检：末端区域与正文有可见差异，或文字行下方到横滑框下沿 + 6dp 有非背景像素（不判渐变曲线和尺寸）；
 * ④ 读屏：在**合并后的无障碍树**里，与金额文字位置重叠、且自身和祖先都没有 HideFromAccessibility 的节点，读出的内容（有 contentDescription 时是它，
 *    否则是文字）含完整金额或其读屏写法；金额文字语义被清除时同样判读不到。
 * 必须在 [NumWrap.assertField] 之前调用（assertField 会把横滑框滚到两端）。
 */
internal object NumHint {
    private const val FADE_DP = 6
    private const val BELOW_DP = 6
    private const val INK = 24
    private const val ROW_SHARE = 0.75f
    private const val EDGE_RATIO = 0.9f

    fun assertHint(
        rule: ComposeContentTestRule,
        spec: NumHintSpec,
    ) {
        val field = spec.field
        val what = "${field.where}：「${field.value}」"
        val matches = rule.onAllNodes(hasText(field.value, substring = true) and NumWrap.HAS_LAYOUT and field.scope, useUnmergedTree = true)
        val found = matches.fetchSemanticsNodes().size
        assertTrue("$what 读屏读不到完整金额（金额文字的语义被清除或隐藏，找不到文字节点）", found > 0)
        assertEquals("$what 应恰好在 1 个文字节点里完整出现", 1, found)
        val node = matches[0]
        val text = node.fetchSemanticsNode()
        assertSpoken(rule, spec, text, what)
        val state = NumOverflow.read(text)
        assertNotNull("$what 没有走共用金额件：金额文字及其祖先上找不到语义「${NumOverflow.KEY}」（见交回 §4）", state)
        assertContract(checkNotNull(state), spec.expect, what)
        val scroller = NumReach.horizontalScroller(text)
        val axis = scroller?.config?.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
        val scrollable = axis != null && axis.maxValue() > 0f
        assertEquals("$what 语义 scrollable=${state.scrollable} 与实际横滑 $scrollable 不一致", state.scrollable, scrollable)
        if (scrollable && scroller != null && axis != null) {
            assertOwnLine(rule, spec.row, text, scroller, what)
            assertEquals("$what 横滑初始位置须在开头（滚动位置 ${axis.value()}）", 0f, axis.value(), 0.5f)
            val laid = NumWrap.layout(node)
            val shown = laid.layoutInput.text.text
            NumReach.assertCharVisible(node, shown.indexOf(field.value), "$what 横滑初始没有从开头露出")
            assertTrue("$what 像素粗检：横滑末端看不出渐隐、也没有指示器墨迹（语义声明了但没画）", drawn(rule, text, scroller.boundsInRoot))
        }
    }

    private fun assertContract(
        state: NumOverflow.State,
        expect: Boolean?,
        what: String,
    ) {
        when (expect) {
            true -> {
                assertTrue("$what 极端值独占一行仍放不下，必须框内横滑（${NumOverflow.KEY}.scrollable）", state.scrollable)
                assertTrue("$what 横滑末端没有渐隐（${NumOverflow.KEY}.fadeEnd）", state.fadeEnd)
                assertTrue("$what 横滑没有横向滚动指示器（${NumOverflow.KEY}.indicator）", state.indicator)
            }

            false -> {
                assertFalse("$what 放得下的金额不该横滑，应先换行 / 上下排（${NumOverflow.KEY}.scrollable）", state.scrollable)
                assertFalse("$what 常规金额误加了渐隐（${NumOverflow.KEY}.fadeEnd）", state.fadeEnd)
                assertFalse("$what 常规金额误加了滚动指示器（${NumOverflow.KEY}.indicator）", state.indicator)
            }

            null -> {
                assertEquals("$what 渐隐必须随横滑出现（${NumOverflow.KEY}）", state.scrollable, state.fadeEnd)
                assertEquals("$what 指示器必须随横滑出现（${NumOverflow.KEY}）", state.scrollable, state.indicator)
            }
        }
    }

    private fun assertSpoken(
        rule: ComposeContentTestRule,
        spec: NumHintSpec,
        text: SemanticsNode,
        what: String,
    ) {
        val target = text.boundsInRoot
        val merged = rule.onAllNodes(SemanticsMatcher("任意节点") { true }, useUnmergedTree = false).fetchSemanticsNodes()
        val heard =
            merged
                .filter { it.boundsInRoot.overlaps(target) && !hidden(it) }
                .any { node ->
                    val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    val parts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
                    // 有 contentDescription 时读屏读它、不读文字；没有时读文字
                    val read = descriptions.ifEmpty { parts.map { it.text } }
                    read.any { it.contains(spec.field.value) || (spec.spoken != null && it.contains(spec.spoken)) }
                }
        assertTrue("$what 读屏读不到完整金额（合并后的无障碍树里，金额位置上没有读出完整金额的可见节点）", heard)
    }

    private fun hidden(node: SemanticsNode): Boolean {
        val chain = generateSequence(node) { it.parent }
        return chain.any { it.config.contains(SemanticsProperties.HideFromAccessibility) }
    }

    private fun assertOwnLine(
        rule: ComposeContentTestRule,
        row: SemanticsMatcher,
        text: SemanticsNode,
        scroller: SemanticsNode,
        what: String,
    ) {
        val view = NumSerial.unclipped(scroller)
        val clickable = SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)
        val texts = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)
        val chain = generateSequence(text) { it.parent }.map { it.id }.toSet()
        val others = rule.onAllNodes(row and (texts or clickable), useUnmergedTree = true).fetchSemanticsNodes().filter { it.id !in chain }
        val sharing =
            others.filter { other ->
                val box = NumSerial.unclipped(other)
                minOf(box.bottom, view.bottom) - maxOf(box.top, view.top) > 2f
            }
        assertTrue("$what 横滑前没有先独占一行（同一行还有 ${sharing.map(::label)}）", sharing.isEmpty())
        val boxes = others.map { NumSerial.unclipped(it) }
        val span = (boxes.maxOfOrNull { it.right } ?: view.right) - (boxes.minOfOrNull { it.left } ?: view.left)
        assertTrue("$what 横滑框没有用足整行（宽 ${view.width.toInt()} px < 行内容跨度 ${span.toInt()} px 的 75%）", view.width >= span * ROW_SHARE)
    }

    private fun label(node: SemanticsNode): String {
        val parts = node.config.getOrNull(SemanticsProperties.Text)
        return parts?.joinToString { it.text } ?: "可点节点 ${node.id}"
    }

    /** 文字行（第 0 行）在 root 坐标里的纵向范围。 */
    private fun line(text: SemanticsNode): ClosedFloatingPointRange<Float> {
        val results = mutableListOf<TextLayoutResult>()
        text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val result = results.single()
        val top = NumReach.coordinatesOf(text).positionInRoot().y
        return (top + result.getLineTop(0))..(top + result.getLineBottom(0))
    }

    /** 像素粗检：末端（视口最后 6dp）墨迹强度明显低于正文（< 90%），或文字行下沿到视口下沿 + 6dp 之间有非背景像素（别的文字节点框内不算）。 */
    private fun drawn(
        rule: ComposeContentTestRule,
        text: SemanticsNode,
        view: Rect,
    ): Boolean {
        val d = text.layoutInfo.density.density
        val rows = line(text)
        val pixels = NumPixels.of(rule, text)
        val strip = NumPixels.crop(pixels, Rect(view.left, rows.start, view.right, rows.endInclusive))
        val strengths = columnStrengths(strip)
        val window = (FADE_DP * d).toInt()
        val body = strengths.take((strengths.size * 0.6f).toInt()).maxOrNull() ?: 0
        val edge = strengths.takeLast(window).maxOrNull() ?: 0
        val area = Rect(view.left, rows.endInclusive, view.right, maxOf(view.bottom, rows.endInclusive) + BELOW_DP * d)
        return (body > 0 && edge < body * EDGE_RATIO) || inkBelow(rule, text, pixels, area)
    }

    private fun columnStrengths(crop: Bitmap): List<Int> {
        val background = NumPixels.modeColor(crop)
        return (0 until crop.width).map { x -> (0 until crop.height).maxOf { y -> distance(crop.getPixel(x, y), background) } }
    }

    private fun inkBelow(
        rule: ComposeContentTestRule,
        text: SemanticsNode,
        pixels: Bitmap,
        area: Rect,
    ): Boolean {
        val crop = NumPixels.crop(pixels, area)
        val background = NumPixels.modeColor(crop)
        val others =
            rule
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .filter { it.id != text.id }
                .map { NumSerial.unclipped(it) }
                .filter { it.overlaps(area) }
        val ink = { x: Int, y: Int ->
            val inside = others.any { it.contains(Offset(area.left + x + 0.5f, area.top + y + 0.5f)) }
            !inside && distance(crop.getPixel(x, y), background) > INK
        }
        return (0 until crop.height).any { y -> (0 until crop.width).any { x -> ink(x, y) } }
    }

    private fun distance(
        a: Int,
        b: Int,
    ): Int =
        maxOf(
            abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
            abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)),
            abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)),
        )
}
