@file:Suppress("MagicNumber")

package com.couli.app.account

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText as textMatcher
import com.couli.app.me.box
import com.couli.app.me.density
import com.couli.app.me.iconBoxes
import com.couli.app.me.pvColorBox
import com.couli.app.me.regionAround
import com.couli.app.me.rootBounds
import com.couli.app.me.sameColor
import com.couli.app.me.subtree
import com.couli.app.me.textBox
import com.couli.app.me.textStyle
import com.couli.feature.me.AccountTags
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * APP-13v 注销页的进入方式与量具（只读）。进入一律经真实设置页「注销账号」行 / AppAccount 的真实来源交接 / 真实强更页次要入口，
 * 状态由假服务（fixture 报文）与真实点击造出；量具沿用 APP-12w-a / b / c 的写法：
 * - 卡片 / 提示条 / 底栏 / 胶囊：从文字左缘外一点按令牌色向四周扫出外框（量外框不量内容盒）；卡片取样列退到卡左缘内 14dp（圆角 16 在 2dp 处深约 8dp）；
 * - 图标：真实 LayoutNode 正方形叶子（iconBoxes），按尺寸与位置筛；
 * - 文字：真实 TextLayoutResult 的 style（字号、字重、颜色）；居中排版量字形。
 * 视觉断言先正后反：每个「没有」都与同一量具量出的「有」对照。
 */
@Suppress("TooManyFunctions", "UnnecessaryAbstractClass")
abstract class AccountVisualDeletionHarness : AccountVisualHarness() {
    // ---------------------------------------------------------------- 进入

    /** 说明与确认（deletion none + wallet funds）：经设置页「注销账号」行进入，等资金区落地。 */
    protected fun enterInitial(checked: Boolean = false) {
        serverState.deletion = deletionFixture("none")
        serverState.wallet = walletFixture("funds")
        launch()
        openDeleteFromSettings()
        awaitFunds()
        if (checked) checkWaiver()
    }

    /** 进页负余额（wallet negative：可提现 0、待抵扣 567）。 */
    protected fun enterNegative() {
        serverState.deletion = deletionFixture("none")
        serverState.wallet = walletFixture("negative")
        launch()
        openDeleteFromSettings()
        idleUntil("负余额句") { has(AccountTags.DELETE_NEGATIVE_NOTICE) }
        awaitFunds()
    }

    /** 冷静期（can_cancel true / false）。 */
    protected fun enterCooling(expired: Boolean = false) {
        serverState.deletion = deletionFixture(if (expired) "cooling_expired" else "cooling")
        launch()
        openDeleteFromSettings()
        idleUntil("冷静期") { has(AccountTags.DELETE_COOLING) }
    }

    /** 待确认（种入 2026-10-12 10:00 的注销未决，冷启动恢复为结果未知）。返回种入的键。 */
    protected fun enterPending(): String {
        val serial = "visual-deletion-1"
        seedDeletionPending(serial)
        serverState.deletion = deletionFixture("none")
        serverState.wallet = walletFixture("funds")
        launch()
        openDeleteFromSettings()
        idleUntil("待确认卡") { has(AccountTags.PENDING_CARD) }
        return serial
    }

    /** 申请得到 30412（勾选保留）：DeleteAccountStates ①。 */
    protected fun enter30412() {
        serverState.requireStepUp = false
        enterInitial()
        enqueueCode("POST", DELETION, 30412)
        checkWaiverAndSubmit()
        idleUntil("30412 页首提示") { has(AccountTags.DELETE_ERROR) }
    }

    /** 受限会话（deletion_only）：冷启动强更 → 强更页次要入口 → 受限注销页（AccountEntryIntegrationTest 同一路径）。 */
    protected fun enterRestricted() {
        serverState.deletion = deletionFixture("none")
        serverState.wallet = walletFixture("funds")
        refreshScope = "deletion_only"
        launch(scope = "deletion_only", forced = true)
        idleUntil("强更页次要入口") { top() == FORCE_UPDATE && has(com.couli.app.update.FORCE_UPDATE_DELETE_TAG) }
        idleUntil("冷启动的屏障刷新已结束") { all("POST", "/v1/auth/refresh").isNotEmpty() }
        click(com.couli.app.update.FORCE_UPDATE_DELETE_TAG)
        idleUntil("受限注销页") { top() == DELETE_ACCOUNT && has(AccountTags.DELETE_SUBMIT) }
        awaitFunds()
    }

    /** 风控来源（risk_notice，full 会话）：经 AppAccount 的来源交接打开（DeleteAccountStates ②）。 */
    protected fun enterFromRisk() {
        serverState.deletion = deletionFixture("none")
        serverState.wallet = walletFixture("funds")
        launch()
        openDeleteFrom(com.couli.core.me.account.AccountRaw.RISK_NOTICE)
        awaitFunds()
    }

    /**
     * 经真实设置页「注销账号」行进入，[prepare] 在设置页已就绪、点击之前调用（设置页自己也会读注销进度：覆盖回答要在这之后设，
     * 才不落在设置页的读取上）。只等页面出现，不等读取。注销页进入时同一读取发两次（创建 load + OnResumed load，只收最新），
     * 所以 [prepare] 里用持续回答（[failAlways] / [holdAll]），不用一次性回答（测试 v2 T1）。
     */
    protected fun enterVia(prepare: () -> Unit = {}) {
        launch()
        open(SETTINGS)
        idleUntil("设置页注销行") { has(DELETE_ROW) }
        settle()
        prepare()
        click(DELETE_ROW)
        idleUntil("注销页") { top() == DELETE_ACCOUNT && has(AccountTags.DELETE_PAGE) }
    }

    /** 让下一次 [method] [path] 停在服务端，直到返回的闸门放行（放行后按 [answer] 回答）。 */
    protected fun hold(
        method: String,
        path: String,
        answer: () -> okhttp3.mockwebserver.MockResponse,
    ): Pair<CountDownLatch, CountDownLatch> {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue(method, path) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            answer()
        }
        return arrived to release
    }

    /** 此后每一次 [method] [path] 都回错误码 [code]（进入期读取失败；与读取次数无关，测试 v2 T1）。 */
    protected fun failAlways(
        method: String,
        path: String,
        code: Int,
        trace: String = "trace-123456",
    ) = answerAlways(method, path) { envelope("null", code, trace) }

    /**
     * 此后每一次 [method] [path] 都停在服务端，直到返回的闸门放行（放行后按 [answer] 回答）；第一次到达即放开「已到达」闸门。
     * 进入期「加载中」用它：页面的每一次读取都停住，不会被另一次读取先落地盖掉（测试 v2 T1）。
     */
    protected fun holdAll(
        method: String,
        path: String,
        answer: () -> okhttp3.mockwebserver.MockResponse,
    ): Pair<CountDownLatch, CountDownLatch> {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        answerAlways(method, path) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            answer()
        }
        return arrived to release
    }

    // ---------------------------------------------------------------- 量具

    protected fun t(
        key: String,
        vars: Map<String, String>,
    ): String =
        com.couli.core.me.account.AccountDraftTexts
            .format(key, vars)

    protected fun dp(
        px: Float,
        node: SemanticsNodeInteraction,
    ): Float = px / node.density()

    protected fun pageDensity(): Float = node(AccountTags.DELETE_PAGE).density()

    /** [tag] 之下（含自身）文字恰为 [text] 的未合并节点。 */
    protected fun textUnder(
        tag: String,
        text: String,
    ): SemanticsNodeInteraction =
        composeRule.onNode(textMatcher(text) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)

    protected fun countUnder(
        tag: String,
        matcher: SemanticsMatcher,
    ): Int =
        composeRule
            .onAllNodes(matcher and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size

    /**
     * 文字所在色块（提示条 / 卡片 / 明细块）的外框：取样点在文字左缘外 [insetDp]（默认 3dp：提示条图标与文字之间、卡片内边距里）、
     * 文字上沿之上 1.5dp（落在色块上内边距里，横向扫描不穿过图标与文字）。卡片标题用 insetDp = 2（取样列落在卡左缘内 14dp，圆角不影响）。
     */
    protected fun Bitmap.blockOf(
        text: SemanticsNodeInteraction,
        color: Color,
        insetDp: Float = 3f,
    ): Rect? {
        val box = text.textBox()
        val dd = text.density()
        return regionAround(box.left - insetDp * dd, box.top - 1.5f * dd, color)
    }

    /** 外框的左缘内 14dp 一列、竖直中线一点的颜色是否为 [color]（卡片底色取样，APP-12w-b v2 T1）。 */
    protected fun Bitmap.cardColorIs(
        card: Rect,
        color: Color,
        density: Float,
    ): Boolean = sameColor(getPixel((card.left + RISK_SAMPLE_DP * density).toInt(), card.center.y.toInt()), color, 2)

    protected fun assertColor(
        message: String,
        expected: Color,
        actual: Color,
    ) = assertTrue("$message：期望 $expected 实际 $actual", sameColor(actual.toArgb(), expected, 2))

    protected fun assertFont(
        message: String,
        expectedSp: Float,
        node: SemanticsNodeInteraction,
    ) = assertEquals(message, expectedSp, node.textStyle().fontSize.value, 0.01f)

    protected fun assertWeight(
        message: String,
        expected: Int,
        node: SemanticsNodeInteraction,
    ) = assertEquals(message, expected, node.textStyle().fontWeight?.weight ?: 400)

    protected fun assertDpNear(
        message: String,
        expected: Float,
        actualPx: Float,
        density: Float,
        tolerance: Float = 1.5f,
    ) = assertEquals(message, expected, actualPx / density, tolerance)

    protected fun assertBox(
        message: String,
        box: Rect?,
    ): Rect {
        assertNotNull("$message：没有量到", box)
        return box!!
    }

    /** 页面子树里与 [area] 相交、边长在 [min, max] dp 的正方形叶子（图标 / 圆点 / 勾选圆）。 */
    protected fun iconsIn(
        area: Rect,
        min: Float,
        max: Float,
    ): List<Rect> = node(AccountTags.DELETE_PAGE).iconBoxes(min, max).filter { it.overlaps(area) }

    /** [rect] 内颜色接近 [color] 的像素外接框。 */
    protected fun Bitmap.colorBox(
        rect: Rect,
        color: Color,
        tolerance: Int = 6,
    ): Rect? = pvColorBox(rect, color, tolerance)

    protected fun liveRegion(tag: String) = node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)

    /** 合并树里 [tag] 节点读出的全部片段。 */
    protected fun spokenOf(tag: String): List<String> {
        val merged = composeRule.onNodeWithTagMerged(tag).fetchSemanticsNode().config
        return merged.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            merged.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(merged.getOrNull(SemanticsProperties.StateDescription))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithTagMerged(tag: String) =
        onNode(hasTestTag(tag), useUnmergedTree = false)

    protected fun boxOf(tag: String): Rect = node(tag).box()

    /** 节点子树里边长在 [min, max] dp 的正方形布局（含有子节点的盒，如带白勾的勾选圆）。 */
    protected fun SemanticsNodeInteraction.allSquares(
        min: Float,
        max: Float,
    ): List<Rect> {
        val n = fetchSemanticsNode()
        val dd = n.layoutInfo.density.density
        return n.layoutInfo
            .subtree()
            .map { it.rootBounds() }
            .filter { kotlin.math.abs(it.width - it.height) <= dd && it.width / dd in min..max }
            .distinct()
    }

    companion object {
        /** 卡片取样列：卡左缘内 14dp。 */
        const val RISK_SAMPLE_DP = 14f

        /** 待确认摘要标签（G24）。 */
        const val SUMMARY_OPERATION = "pending_confirm.summary.operation"
    }
}
