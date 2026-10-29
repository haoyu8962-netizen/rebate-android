@file:Suppress("MagicNumber")

package com.couli.app.account

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText as textMatcher
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import com.couli.app.login.LoginDraftTexts
import com.couli.app.me.box
import com.couli.app.me.density
import com.couli.app.me.glyphBox
import com.couli.app.me.sameColor
import com.couli.core.me.account.AccountDraftTexts
import com.couli.feature.login.BindPhonePendingTags
import com.couli.feature.login.BindPhoneTags
import com.couli.feature.me.AccountTags
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * APP-13v-b（换手机号 + 绑手机号）的进入方式与量具（只读）。复用 a 段的 [AccountVisualDeletionHarness] / [AccountVisualHarness]（真 AppShell + Router +
 * AppMe / AppAccount / AppLogin + ApiClient，只换传输层；冻结的 AccountHarness 不改、不继承）。进入一律经真实设置页「手机号」行：
 * phone_bound = true → 页内子页 PhoneChange（进入零请求）；phone_bound = false → Router BindPhone（非登录入口）。待确认一族经冷启动种入 bindPhone 在途记录
 * （原体只在本宿主内存里拼，fixture 不放原文）。一次性 / 持续回答的取舍沿用 a 段测试 v2 T1：写请求（POST）只发一次，用一次性回答；进入期读取用持续回答。
 * 量具沿用 APP-12w-a / b / c 与 a 段：量外框不量内容盒；居中排版量字形；格子、外框按像素从内部向外扫（与令牌色比，不写色值）。
 */
@Suppress("TooManyFunctions", "UnnecessaryAbstractClass")
abstract class AccountVisualPhoneHarness : AccountVisualDeletionHarness() {
    // ---------------------------------------------------------------- 文案与 fixture

    /** 登录一侧的草稿 / 契约文案（绑号页用 LoginDraftTexts：契约字典 → 草稿）。 */
    protected fun lt(key: String): String = LoginDraftTexts.text(key)

    protected fun ltf(
        key: String,
        vars: Map<String, String>,
    ): String = LoginDraftTexts.format(key, vars)

    protected fun phone(name: String): String = AccountVisualFixtures.phone(name)

    protected fun segmentB(): JsonObject = AccountVisualFixtures.obj("segmentB")

    protected fun draftB(key: String): String = AccountVisualFixtures.text(key, AccountVisualFixtures.obj("drafts", segmentB()))

    protected fun keptB(key: String): String = AccountVisualFixtures.text(key, AccountVisualFixtures.obj("draftsKept", segmentB()))

    /** 验证码输入框的读屏名（step_up.code_spoken「验证码，已输入 n 位，共 6 位」）。 */
    protected fun codeSpoken(n: Int): String = AccountDraftTexts.format("step_up.code_spoken", mapOf("n" to n.toString()))

    /** 换号 / 绑号未决记录的原体（只在宿主内存里拼，phone + code 两个字段）。 */
    protected fun pendingBody(phone: String): String = """{"phone":"$phone","code":"$CODE_OK"}"""

    // ---------------------------------------------------------------- 进入：换号

    /** 第 1 步（PhoneChange-v2）；[sent] = 已点【获取验证码】并进入倒计时（PhoneChangeCode）。 */
    protected fun enterStep1(sent: Boolean = false) {
        launch()
        openPhoneChange()
        if (sent) sendStep1()
    }

    protected fun sendStep1() {
        val before = all("POST", SMS).size
        click(AccountTags.PHONE_STEP1_SEND)
        idleUntil("第 1 步发码结算（进入倒计时）") { all("POST", SMS).size > before && hasText(STEP2_COUNTDOWN_TEXT) }
    }

    /** 第 1 步验证得到 20002（PhoneChangeCodeError）：格子标红、字段错误在格下。 */
    protected fun enterStep1Error() {
        enterStep1(sent = true)
        type(AccountTags.PHONE_STEP1_CODE, phone("wrongCode"))
        idleUntil("【下一步】可点") { enabled(AccountTags.PHONE_STEP1_NEXT) }
        tap(AccountTags.PHONE_STEP1_NEXT)
        idleUntil("20002 字段错误") { has(AccountTags.PHONE_FIELD_ERROR) }
    }

    /** 第 2 步（PhoneChangeNew-v2）：[typed] 已填新号；[sent] 已填新号 + 已发码 + 已输验证码（fillStep2）。 */
    protected fun enterStep2(
        typed: Boolean = false,
        sent: Boolean = false,
    ) {
        launch()
        openPhoneChange()
        passStep1()
        when {
            sent -> fillStep2()
            typed -> type(AccountTags.PHONE_NEW, PHONE_NEW)
            else -> Unit
        }
    }

    /** 第 2 步：填新号、点【获取验证码】、等倒计时（不输验证码；PhoneChangeNewCode）。 */
    protected fun sendStep2(newPhone: String = PHONE_NEW) {
        type(AccountTags.PHONE_NEW, newPhone)
        val before = all("POST", SMS).size
        click(AccountTags.PHONE_STEP2_SEND)
        idleUntil("第 2 步发码结算（进入倒计时）") { all("POST", SMS).size > before && hasText(STEP2_COUNTDOWN_TEXT) }
    }

    /** 第 2 步提交得到 [code]（一次性回答：提交只发一次）。 */
    protected fun submitStep2With(
        code: Int,
        data: String = "null",
    ) {
        enterStep2(sent = true)
        enqueueCode("POST", BIND, code, data)
        tap(AccountTags.PHONE_SUBMIT)
    }

    /** 44001（PhoneChangeNewBlocked-v2）：弹窗出现、新号保留。 */
    protected fun enterRisk44001() {
        submitStep2With(44001, """{"risk_msg_code":"test_unmapped"}""")
        idleUntil("44001 弹窗") { has(AccountTags.PHONE_RISK_OK) }
    }

    /** 提交成功（PhoneChangeDone）。 */
    protected fun enterDone() {
        enterStep2(sent = true)
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("完成页") { has(AccountTags.PHONE_DONE) }
    }

    /** 提交得到 10003 → 二次验证半屏（PhoneChangeStepUp，只对页面部分）。 */
    protected fun enterStepUpOnSubmit() {
        enterStep2(sent = true)
        serverState.validTokens.clear()
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("二次验证面板（发码已有结论）") { stepUpReady() }
    }

    /** 换号待确认（PhoneChangePendingConfirm-v2）：种入 2026-10-12 10:00 的 bindPhone 在途记录（phone_bound = true），冷启动恢复为结果未知。 */
    protected fun enterPhonePending(): String {
        serverState.requireStepUp = false
        seedInFlight(
            AccountVisualFixtures.pendingOperation("phone_change"),
            PHONE_PENDING_SERIAL,
            pendingBody(PHONE_NEW),
            AccountVisualFixtures.pendingSummary("phone_change"),
            createdAt = AccountVisualFixtures.pendingCreatedAt("phone_change"),
        )
        launch()
        openPhoneChange()
        idleUntil("换号待确认卡") { has(AccountTags.PENDING_CARD) }
        return PHONE_PENDING_SERIAL
    }

    // ---------------------------------------------------------------- 进入：绑号（非登录入口）

    /** 绑手机页（BindPhone-v2）：phone_bound = false 的设置页「手机号」行 → Router BindPhone。[typed] 填号；[sent] 填号并发码。 */
    protected fun enterBind(
        typed: Boolean = false,
        sent: Boolean = false,
    ) {
        serverState.phoneBound = false
        launch()
        open(SETTINGS)
        idleUntil("设置页手机号行") { has(PHONE_ROW) && enabled(PHONE_ROW) }
        click(PHONE_ROW)
        idleUntil("绑手机页") { has(BindPhoneTags.PAGE) }
        if (typed || sent) type(BindPhoneTags.PHONE, phone("bind"))
        if (sent) sendBindCode()
    }

    /** 绑号：填号、发码、输码后点【绑定】，绑定得到 [code]（一次性回答）。 */
    protected fun bindSubmitWith(
        code: Int,
        data: String = "null",
    ) {
        enterBind(sent = true)
        type(BindPhoneTags.CODE, CODE_OK)
        enqueueCode("POST", BIND, code, data)
        idleUntil("【绑定】可点") { enabled(BindPhoneTags.SUBMIT) }
        tap(BindPhoneTags.SUBMIT)
    }

    /** 并号确认（BindPhoneMerge-v2，只对确认框）：30411 mergeable。 */
    protected fun enterBindMerge() {
        bindSubmitWith(30411, MERGEABLE_DATA)
        idleUntil("并号确认框") { hasText(lt("bind_phone.merge.title")) }
    }

    protected fun enterBind44001() {
        bindSubmitWith(44001)
        idleUntil("绑号 44001 弹窗") { hasText(lt("cs.appeal_entry")) }
    }

    /** 绑号待确认：种入 bindPhone 在途记录（phone_bound = false），冷启动恢复为结果未知。 */
    protected fun enterBindPending(): String {
        serverState.phoneBound = false
        seedInFlight(
            AccountVisualFixtures.pendingOperation("bind_phone"),
            BIND_PENDING_SERIAL,
            pendingBody(phone("bind")),
            AccountVisualFixtures.pendingSummary("bind_phone"),
            createdAt = AccountVisualFixtures.pendingCreatedAt("bind_phone"),
        )
        launch()
        open(SETTINGS)
        idleUntil("设置页手机号行") { has(PHONE_ROW) && enabled(PHONE_ROW) }
        click(PHONE_ROW)
        idleUntil("绑号待确认卡") { has(BindPhonePendingTags.CARD) }
        return BIND_PENDING_SERIAL
    }

    // ---------------------------------------------------------------- 量具

    protected fun densityOf(tag: String): Float = node(tag).density()

    /** [tag] 之下（含自身）第一个有文字的未合并节点（按钮的文字在子节点上；自绘文字按钮可能就在节点本身）。 */
    protected fun textIn(tag: String): SemanticsNodeInteraction =
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
            useUnmergedTree = true,
        )[0]

    protected fun textOfNode(node: SemanticsNodeInteraction): String =
        node
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .joinToString("") { it.text }

    protected fun errorOf(tag: String): String? = node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error)

    protected fun descriptionOf(tag: String): String =
        node(tag)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ContentDescription)
            .orEmpty()
            .joinToString()

    /** 一行像素里满足 [hit] 的连续段（允许 1px 断点，抗锯齿不拆段）。 */
    protected fun Bitmap.runs(
        y: Float,
        x0: Float,
        x1: Float,
        hit: (Int) -> Boolean,
    ): List<IntRange> {
        val out = mutableListOf<IntRange>()
        val row = y.toInt().coerceIn(0, height - 1)
        var start = -1
        var last = -1
        for (x in x0.toInt().coerceAtLeast(0) until x1.toInt().coerceAtMost(width)) {
            if (hit(getPixel(x, row))) {
                if (start < 0) start = x
                last = x
            } else if (start >= 0 && x - last > 1) {
                out += start..last
                start = -1
            }
        }
        if (start >= 0) out += start..last
        return out
    }

    /** 一列像素里满足 [hit] 的连续段。 */
    protected fun Bitmap.columnRuns(
        x: Float,
        y0: Float,
        y1: Float,
        hit: (Int) -> Boolean,
    ): List<IntRange> {
        val out = mutableListOf<IntRange>()
        val col = x.toInt().coerceIn(0, width - 1)
        var start = -1
        var last = -1
        for (y in y0.toInt().coerceAtLeast(0) until y1.toInt().coerceAtMost(height)) {
            if (hit(getPixel(col, y))) {
                if (start < 0) start = y
                last = y
            } else if (start >= 0 && y - last > 1) {
                out += start..last
                start = -1
            }
        }
        if (start >= 0) out += start..last
        return out
    }

    /**
     * 验证码输入节点（标记挂在可 SetText 的输入节点上）横向中线上的描边段：6 格 = 12 段（每格左右各一段）。[color] 为 null 时数「非白底」像素
     * （空格子里只有描边），否则只数接近 [color] 的像素（错误态 status.error：格内数字是主文字色，不计入）。
     */
    protected fun cellBorderRuns(
        tag: String,
        color: Color? = null,
    ): List<IntRange> {
        val box = boxOf(tag)
        val px = pixels()
        return if (color == null) {
            px.runs(box.center.y, box.left, box.right) { !sameColor(it, palette.surface, SURFACE_TOLERANCE) }
        } else {
            px.runs(box.center.y, box.left, box.right) { sameColor(it, color, BORDER_TOLERANCE) }
        }
    }

    /**
     * 输入框外框（量外框不量内容盒）：从输入节点右缘沿竖直中线往右走到第一个非白底像素（外框右描边），再退回外框内 14dp（避开圆角）
     * 沿竖直方向上下各走到第一个非白底像素（上下描边）。只在输入框右侧没有尾部控件时用（换号新号框；绑号空号时）。
     */
    protected fun frameOf(inputTag: String): Rect {
        val box = boxOf(inputTag)
        val px = pixels()
        val dd = node(inputTag).density()
        val y = box.center.y.toInt()
        val inked = { x: Int, yy: Int -> !sameColor(px.getPixel(x, yy), palette.surface, SURFACE_TOLERANCE) }
        // 测试 v2 T4：从输入节点右缘内 1px 起扫（输入节点贴着描边时，右缘那一列已是描边；v1 从右缘外起扫会越过描边量到卡片）
        var right = (box.right.toInt() - 1).coerceIn(0, px.width - 1)
        while (right < px.width - 1 && !inked(right, y)) right++
        assertTrue("外框右描边紧贴输入节点（≤16dp），量的是输入框不是卡片：${(right - box.right) / dd}dp", right - box.right <= FRAME_GAP_DP * dd)
        // 描边在框内侧（Modifier.border），外框 = 描边外沿：从内沿再穿过描边（至多 STROKE_MAX_DP）走到外沿；v1 只到内沿，xhdpi 下 1dp 描边上下各少 1px（52 → 51）
        val reach = (STROKE_MAX_DP * dd).toInt()

        fun stroke(
            from: Int,
            step: Int,
            ok: (Int) -> Boolean,
        ): Int {
            var p = from
            var n = 0
            while (n < reach && ok(p + step)) {
                p += step
                n++
            }
            return p
        }
        right = stroke(right, 1) { it < px.width && inked(it, y) }
        val x = (right - FRAME_INSET_DP * dd).toInt()
        var top = y
        while (top > 0 && !inked(x, top)) top--
        top = stroke(top, -1) { it >= 0 && inked(x, it) }
        var bottom = y
        while (bottom < px.height - 1 && !inked(x, bottom)) bottom++
        bottom = stroke(bottom, 1) { it < px.height && inked(x, it) }
        return Rect(box.left, top.toFloat(), right + 1f, bottom + 1f)
    }

    /** 文字节点的 TextLayoutResult（逐行宽 / 每行字数）。 */
    protected fun SemanticsNodeInteraction.layoutResult(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(results)
        return results.first()
    }

    /** 弹窗（paneTitle）节点。 */
    protected val pane: SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)

    /** 弹窗标题居中（字形中线 = 弹窗中线，G-D18）。 */
    protected fun assertDialogTitleCentered(
        message: String,
        title: String,
    ) {
        val dialog = composeRule.onNode(pane, useUnmergedTree = true)
        val text = composeRule.onNode(textMatcher(title) and hasAnyAncestor(pane), useUnmergedTree = true)
        val dd = text.density()
        assertEquals("$message：标题居中（字形中线 = 弹窗中线）", dialog.box().center.x / dd, text.glyphBox().center.x / dd, 2f)
    }

    /** 弹窗里的按钮（可点 ∧ 后代文字恰为 [text] ∧ 祖先有 paneTitle，APP-12w-b / c 测试 v2 T2）。 */
    protected fun dialogButtonOf(text: String): SemanticsNodeInteraction =
        composeRule.onNode(hasClickAction() and hasAnyDescendant(textMatcher(text)) and hasAnyAncestor(pane), useUnmergedTree = true)

    /** 换号子页的 NavBar 返回（可点 ∧ 自身或后代有返回描述，在换号页之内）。 */
    protected fun phoneBack(): SemanticsNodeInteraction {
        val label =
            com.couli.app.me.AppMeTexts
                .text("me.back")
        return composeRule.onNode(
            hasClickAction() and hasAnyAncestor(hasTestTag(AccountTags.PHONE_PAGE)) and
                (hasContentDescription(label) or hasAnyDescendant(hasContentDescription(label))),
            useUnmergedTree = true,
        )
    }

    /** 在根坐标 (x, y) 上真实点一下（命中区下缘 / 上缘的真实触摸，APP-09v-d r1 S2-1）；页面上没有弹窗时用。 */
    protected fun tapAt(
        x: Float,
        y: Float,
    ) {
        composeRule.onRoot(useUnmergedTree = true).performTouchInput { click(Offset(x, y)) }
        settle()
    }

    /**
     * 页面上（弹窗外）可点节点的种类：有标记的取标记；无标记且自身或后代有内容描述的是返回（NavBar）。置灰仍是同一个入口。控件类型替换不改变种类，
     * 新入口（「收不到验证码？」等）会多出一种而变红（G-D09）。
     */
    protected fun clickableKinds(): Set<String> =
        clickableNodes()
            .filter { n -> n.config.getOrNull(SemanticsProperties.PaneTitle) == null && !n.inPane() }
            .map { n ->
                n.config.getOrNull(SemanticsProperties.TestTag)
                    ?: if (n.described()) BACK_KIND else "未标记可点：" + n.allText()
            }.toSet()

    private fun SemanticsNode.inPane(): Boolean {
        var p = parent
        while (p != null) {
            if (p.config.getOrNull(SemanticsProperties.PaneTitle) != null) return true
            p = p.parent
        }
        return false
    }

    private fun SemanticsNode.described(): Boolean =
        config.getOrNull(SemanticsProperties.ContentDescription)?.isNotEmpty() == true || children.any { it.described() }

    private fun SemanticsNode.allText(): String =
        config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text } + children.joinToString { it.allText() }

    companion object {
        const val PHONE_PENDING_SERIAL = "visual-phone-change-1"
        const val BIND_PENDING_SERIAL = "visual-bind-phone-1"
        const val MERGE = "/v1/auth/merge"
        const val BACK_KIND = "返回"
        const val MERGEABLE_DATA = """{"reason":"mergeable","merge_ticket":"ticket-visual-0246"}"""
        const val SURFACE_TOLERANCE = 24
        const val BORDER_TOLERANCE = 40
        const val FRAME_INSET_DP = 14f
        const val FRAME_GAP_DP = 16f
        const val STROKE_MAX_DP = 1.5f

        /** G-D08：换号 / 绑号页上不得出现的倒计时措辞（三份冻结测试依赖「秒后重新获取」）。 */
        const val FORBIDDEN_COUNTDOWN = "秒后可重新获取"
    }
}
