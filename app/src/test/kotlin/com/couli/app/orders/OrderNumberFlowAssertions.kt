package com.couli.app.orders

import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollTo
import com.couli.app.numwrap.NumCopy
import com.couli.app.numwrap.NumOverflow
import com.couli.app.numwrap.NumReach
import com.couli.app.numwrap.NumWrap
import com.couli.app.product.textLayout
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * 订单号 +【复制】（FU-AND-NUM-WRAP #24 ⑨，测试交回 §7.3；D10-1265 / 判据 1）：
 * ① [NumCopy.assertCopyClear]：按滚动视口判【复制】一行、完整可见、不压在可见号码上、可点且命中区 ≥48dp；
 * ② 流式位置（M10 原判据保留，改按可视窗口量）：
 *    - 号码不在横滑态：同旧——与末行同基线时距末行**实际文字末端** ≤16dp（不能被 weight 推到行尾）；否则整体换到号码下一行、紧接（≤16dp）、
 *      回到号码列起点（≤16dp）；
 *    - 号码独占一行仍放不下、框内横滑（末端在视口外）：【复制】必须整体换到号码下一行，不许与横滑号码同一行；紧接（≤16dp）、回到横滑视口左端（≤16dp）。
 * [run] 是号码本身（不含「订单号」标签），[copyTag] 是【复制】的 testTag。
 */
internal fun SemanticsNodeInteraction.assertCopyClearOfNumber(
    rule: ComposeContentTestRule,
    run: String,
    copyTag: String,
    what: String,
) {
    NumCopy.assertCopyClear(rule, this, run, copyTag, what)
    val copy = rule.onAllNodes(NumWrap.HAS_LAYOUT and NumWrap.within(copyTag), useUnmergedTree = true)[0]
    if (scrolling(fetchSemanticsNode())) assertCopyBelowScrollingNumber(copy, what) else assertCopyFollowsLastLine(copy)
}

/**
 * 换掉旧「必须实际形成多行，不能用短号绕过末行检查」：号码独占一行仍放不下时，必须**实际处在框内横滑态**——单行、所在横滑框滚动范围 > 0、
 * 初始停在开头，共用整段数字件声明的 AmountOverflow 三项（横滑 / 末端渐隐 / 指示器）全为 true。必须在字段断言（会把横滑框滚到两端）之前调用。
 */
internal fun SemanticsNodeInteraction.assertNumberScrollsFromStart(what: String) {
    val node = fetchSemanticsNode()
    assertEquals("$what：独占一行仍放不下时号码单行、框内横滑（不折行）", 1, textLayout().lineCount)
    val scroller = NumReach.horizontalScroller(node)
    assertNotNull("$what：号码必须实际处在框内横滑态（不能用短号绕过）", scroller)
    val axis = checkNotNull(scroller).config[SemanticsProperties.HorizontalScrollAxisRange]
    assertTrue("$what：横滑范围必须 > 0（实际 ${axis.maxValue()}）", axis.maxValue() > 0f)
    assertEquals("$what：横滑初始停在开头", 0f, axis.value(), .5f)
    val state = NumOverflow.read(node)
    assertNotNull("$what：没有走共用整段数字件（找不到语义 ${NumOverflow.KEY}）", state)
    val flags = checkNotNull(state)
    assertTrue("$what：横滑须声明 scrollable / fadeEnd / indicator（实际 $flags）", flags.scrollable && flags.fadeEnd && flags.indicator)
}

/**
 * 先滚外层页面把号码露出来：对号码所在整段数字件的外壳（声明 AmountOverflow 的节点，不在号码自己的横滑框里）performScrollTo。
 * 不对号码本身 performScrollTo——号码横滑时那只会滚它自己的横滑框，外层页面不动。
 */
internal fun ComposeContentTestRule.revealNumber(number: SemanticsNodeInteraction) {
    val shell = generateSequence(number.fetchSemanticsNode()) { it.parent }.firstOrNull { it.declaresOverflow() }
    assertNotNull("号码没有走共用整段数字件（号码及其祖先上找不到语义 ${NumOverflow.KEY}）", shell)
    val id = checkNotNull(shell).id
    onNode(SemanticsMatcher("号码整段数字件外壳") { it.id == id }, useUnmergedTree = true).performScrollTo()
}

private fun SemanticsNode.declaresOverflow(): Boolean = config.any { it.key.name == NumOverflow.KEY }

/** 旧 assertNoTextOverflow 的纵向部分（换成判据 1 后仍保留）：号码文字上下都在可视范围内，没被外层裁掉。 */
internal fun SemanticsNodeInteraction.assertNumberNotClippedVertically(what: String) {
    val node = fetchSemanticsNode()
    val height = textLayout().size.height
    val top = NumReach.coordinatesOf(node).positionInRoot().y
    val rows = NumReach.windowY(node.layoutInfo.coordinates)
    assertTrue(
        "$what：号码上下被裁切（文字 ${top.toInt()}..${(top + height).toInt()}，可视 ${rows.start.toInt()}..${rows.endInclusive.toInt()} px）",
        top >= rows.start - 1f && top + height <= rows.endInclusive + 1f,
    )
}

private fun scrolling(node: SemanticsNode): Boolean {
    val axis = NumReach.horizontalScroller(node)?.config?.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
    return axis != null && axis.maxValue() > 0f
}

/** 号码框内横滑：末端在视口外，【复制】只能整体换到号码下一行、回到横滑视口左端。 */
private fun SemanticsNodeInteraction.assertCopyBelowScrollingNumber(
    copy: SemanticsNodeInteraction,
    what: String,
) {
    val number = fetchSemanticsNode()
    val numberLayout = textLayout()
    val copyLayout = copy.textLayout()
    val density = numberLayout.layoutInput.density.density
    assertEquals("$what：复制必须作为一个整体，不拆成两行", 1, copyLayout.lineCount)
    val bottom = NumReach.coordinatesOf(number).positionInRoot().y + numberLayout.getLineBottom(numberLayout.lineCount - 1)
    val copyOrigin = NumReach.coordinatesOf(copy.fetchSemanticsNode()).positionInRoot()
    val copyTop = copyOrigin.y + copyLayout.getLineTop(0)
    val copyLeft = copyOrigin.x + copyLayout.getLineLeft(0)
    val start = NumReach.window(number.layoutInfo.coordinates).start
    assertTrue("$what：号码框内横滑时复制不能与横滑号码同一行：复制top=$copyTop，号码bottom=$bottom", copyTop >= bottom - 1)
    assertTrue("$what：复制换行后必须紧接单号，不能留大段空白", copyTop - bottom <= 16 * density)
    assertTrue(
        "$what：复制整体换行后应回到横滑视口左端：复制left=$copyLeft，视口left=$start",
        copyLeft - start >= -1 && copyLeft - start <= 16 * density,
    )
}

/** M10（号码不在横滑态时的原判据，逐字保留）：按 TextLayoutResult 末行的真实文字右端量间距，绝不能用 weight 后的节点盒子右边。 */
private fun SemanticsNodeInteraction.assertCopyFollowsLastLine(copy: SemanticsNodeInteraction) {
    val numberLayout = textLayout()
    val copyLayout = copy.textLayout()
    val origin = fetchSemanticsNode().layoutInfo.coordinates.positionInRoot()
    val copyOrigin =
        copy
            .fetchSemanticsNode()
            .layoutInfo.coordinates
            .positionInRoot()
    val density = numberLayout.layoutInput.density.density
    val last = numberLayout.lineCount - 1
    assertEquals("复制必须作为一个整体，不拆成两行", 1, copyLayout.lineCount)
    val end = origin.x + numberLayout.getLineRight(last)
    val bottom = origin.y + numberLayout.getLineBottom(last)
    val baseline = origin.y + numberLayout.getLineBaseline(last)
    val copyLeft = copyOrigin.x + copyLayout.getLineLeft(0)
    val copyTop = copyOrigin.y + copyLayout.getLineTop(0)
    val copyBaseline = copyOrigin.y + copyLayout.getLineBaseline(0)
    if (abs(copyBaseline - baseline) <= 2 * density) {
        val gap = copyLeft - end
        assertTrue("复制距末行实际文字末端过远/重叠：gap=$gap，文字末端=$end", gap >= -1 && gap <= 16 * density)
    } else {
        assertTrue("复制不能停在多行单号中部：复制top=$copyTop，末行bottom=$bottom", copyTop >= bottom - 1)
        assertTrue("复制换行后必须紧接单号，不能留大段空白", copyTop - bottom <= 16 * density)
        val start = origin.x + numberLayout.getLineLeft(0)
        assertTrue("复制整体换行后应回到单号列起点", copyLeft - start >= -1 && copyLeft - start <= 16 * density)
    }
}
