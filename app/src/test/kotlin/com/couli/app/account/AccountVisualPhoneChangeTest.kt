@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText as textMatcher
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.me.box
import com.couli.app.me.density
import com.couli.app.me.glyphBox
import com.couli.app.me.iconBoxes
import com.couli.app.me.ink
import com.couli.app.me.regionAround
import com.couli.app.me.sameColor
import com.couli.app.me.scrollUnder
import com.couli.app.me.textBox
import com.couli.app.me.textStyle
import com.couli.app.me.touch
import com.couli.feature.me.AccountTags
import com.couli.feature.me.MeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-13v-b 换手机号子页视觉（brief-android D21–D34；gaps G-D04 / G-D05 / G-D08 / G-D13 / G-D18）。画板 PhoneChange-v2 1127 高。
 * 结构不改（G-D04）：第 1 步同页验证码 + 底部【下一步】、第 2 步同页新号 + 验证码 + 底部【提交】；只换写法：步骤条、提示条、6 格 CodeInput、格下一行倒计时、
 * 链接、手机号框、待确认卡、确认框、完成图标块、轻提示图标。底版上视觉断言应红（先正后反，量外框不量内容盒），回调 / 次数由 AccountVisualPhoneRedLineTest 守。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1127dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions", "LargeClass")
class AccountVisualPhoneChangeTest : AccountVisualPhoneHarness() {
    // ---------------------------------------------------------------- D21 内容边距（回归）

    @Test fun d21ContentStartsSixteenFromTheLeftAndTop() {
        enterStep1()
        val d = densityOf(AccountTags.PHONE_PAGE)
        val steps = boxOf(AccountTags.PHONE_STEPS)
        val page = boxOf(AccountTags.PHONE_PAGE)
        assertEquals("D21 左 16", 16f, (steps.left - page.left) / d, 1f)
        val scroll = composeRule.scrollUnder(AccountTags.PHONE_PAGE).box()
        assertEquals("D21 上 16（步骤条是滚动区首块）", 16f, (steps.top - scroll.top) / d, 2f)
    }

    // ---------------------------------------------------------------- D22 步骤条

    @Test fun d22Step1BarHasACurrentFilledCircleAnUpcomingRingAndAConnector() {
        enterStep1()
        val d = densityOf(AccountTags.PHONE_STEPS)
        val circles = node(AccountTags.PHONE_STEPS).allSquares(22f, 26f).sortedBy { it.left }
        assertEquals("D22 两个 24 圆（当前 / 未到）：$circles", 2, circles.size)
        val px = pixels()
        val (current, upcoming) = circles
        assertTrue(
            "当前步 brand.primary 实心",
            sameColor(px.getPixel((current.left + 3f * d).toInt(), current.center.y.toInt()), palette.brand, 6),
        )
        assertFalse(
            "未到步不是实心（描边圆）",
            sameColor(px.getPixel((upcoming.left + 4f * d).toInt(), upcoming.center.y.toInt()), palette.brand, 6),
        )
        assertNotNull("未到步 1px border.control 描边", px.colorBox(upcoming, palette.control, 24))
        val line = px.runs(current.center.y, current.right, upcoming.left) { sameColor(it, palette.subtle, 6) }
        assertTrue("D22 中间 1px border.subtle 连线 ≥16：$line", line.any { (it.last - it.first + 1) / d >= 15.5f })
        val label1 = textUnder(AccountTags.PHONE_STEPS, t("phone_change.step1"))
        val label2 = textUnder(AccountTags.PHONE_STEPS, t("phone_change.step2"))
        assertFont("当前步文字 14", 14f, label1)
        assertWeight("当前步 600", 600, label1)
        assertColor("当前步主文字", palette.primary, label1.textStyle().color)
        assertFont("未到步文字 14", 14f, label2)
        assertWeight("未到步 400", 400, label2)
        assertColor("未到步次文字", palette.secondary, label2.textStyle().color)
    }

    @Test fun d22Step2BarShowsADoneCheckThenTheCurrentStep() {
        enterStep2()
        val d = densityOf(AccountTags.PHONE_STEPS)
        val circles = node(AccountTags.PHONE_STEPS).allSquares(22f, 26f).sortedBy { it.left }
        assertEquals("D22 两个 24 圆（完成 / 当前）", 2, circles.size)
        val px = pixels()
        val (done, current) = circles
        assertNotNull("完成步 status.success 圆勾", px.colorBox(done, palette.successText, 24))
        assertTrue("完成步不是品牌实心", px.colorBox(done, palette.brand, 6) == null)
        assertTrue(
            "当前步 brand.primary 实心",
            sameColor(px.getPixel((current.left + 3f * d).toInt(), current.center.y.toInt()), palette.brand, 6),
        )
        val label1 = textUnder(AccountTags.PHONE_STEPS, t("phone_change.step1"))
        assertWeight("完成步 400", 400, label1)
        assertColor("完成步主文字", palette.primary, label1.textStyle().color)
    }

    @Test fun d22BarIsReadAsOneStepNode() {
        enterStep1()
        val merged = composeRule.onNode(hasTestTag(AccountTags.PHONE_STEPS), useUnmergedTree = false).fetchSemanticsNode()
        val spoken = merged.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        assertTrue(
            "读屏仍合并为「第 1 步，共 2 步」：$spoken",
            spoken.contains(t("phone_change.step_spoken", mapOf("n" to "1"))),
        )
        assertEquals("步骤条只有一个读屏节点", 1, composeRule.onAllNodes(hasTestTag(AccountTags.PHONE_STEPS)).fetchSemanticsNodes().size)
    }

    // ---------------------------------------------------------------- D23 第 1 步说明提示条

    @Test fun d23IntroIsAMutedStripWithAnInfoIcon() {
        enterStep1()
        val intro = textNode(t("phone_change.intro"))
        assertFont("说明 14", 14f, intro)
        assertColor("提示条内主文字", palette.primary, intro.textStyle().color)
        val block = assertBox("D23 muted 提示条", pixels().blockOf(intro, palette.muted))
        assertEquals("条内一个 20 info 图标", 1, iconsOn(AccountTags.PHONE_PAGE, block, 18f, 22f).size)
        assertTrue("先正：将发送至你绑定的手机号（G-D04 保留）", hasText(t("phone_change.current_hint")))
    }

    // ---------------------------------------------------------------- D24 / D25 第 1 步 6 格与格下一行

    @Test fun d24Step1CodeIsSixCellsFiftySixHighWithGapEight() {
        enterStep1()
        val d = densityOf(AccountTags.PHONE_STEP1_CODE)
        val runs = cellBorderRuns(AccountTags.PHONE_STEP1_CODE)
        assertEquals("D24 6 格（中线 12 段描边）：$runs", 12, runs.size)
        assertEquals("格高 56（标记挂在格子输入节点上）", 56f, boxOf(AccountTags.PHONE_STEP1_CODE).height / d, 1.5f)
        assertEquals("格间距 8", 8f, (runs[2].first - runs[1].last - 1) / d, 1.5f)
    }

    @Test fun d24CodeInputKeepsTheTagTheSpokenNameAndSetText() {
        enterStep1(sent = true)
        assertTrue("读屏名称 step_up.code_spoken 原样", descriptionOf(AccountTags.PHONE_STEP1_CODE).contains(codeSpoken(0)))
        type(AccountTags.PHONE_STEP1_CODE, "12")
        assertTrue("输入 2 位后读「已输入 2 位」", descriptionOf(AccountTags.PHONE_STEP1_CODE).contains(codeSpoken(2)))
        val config = node(AccountTags.PHONE_STEP1_CODE).fetchSemanticsNode().config
        assertEquals("标记挂在可 SetText 的输入节点（AccountHarness.type 不失效）", "12", config.getOrNull(SemanticsProperties.EditableText)?.text)
    }

    @Test fun d24ErrorTurnsEveryCellRedAndTiesTheErrorToTheInput() {
        enterStep1Error()
        val red = cellBorderRuns(AccountTags.PHONE_STEP1_CODE, palette.error)
        assertEquals("PhoneChangeCodeError：6 格都是 status.error 描边：$red", 12, red.size)
        val message = textOf(AccountTags.PHONE_FIELD_ERROR)
        assertTrue("先正：有字段错误句", message.isNotEmpty())
        assertEquals("error 语义关联到格子输入节点", message, errorOf(AccountTags.PHONE_STEP1_CODE))
        assertTrue("D25 错误句紧贴格下", boxOf(AccountTags.PHONE_FIELD_ERROR).top >= boxOf(AccountTags.PHONE_STEP1_CODE).bottom - 1f)
    }

    @Test fun d24CountdownSitsUnderTheCellsRightAlignedInSecondaryText() {
        enterStep1(sent = true)
        val d = densityOf(AccountTags.PHONE_STEP1_CODE)
        val text = textIn(AccountTags.PHONE_STEP1_SEND)
        val words = textOfNode(text)
        assertTrue("G-D08 措辞维持「{n} 秒后重新获取」：$words", words.contains(STEP2_COUNTDOWN_TEXT) && !words.contains(FORBIDDEN_COUNTDOWN))
        assertFont("倒计时 14", 14f, text)
        assertColor("倒计时次文字", palette.secondary, text.textStyle().color)
        val cells = boxOf(AccountTags.PHONE_STEP1_CODE)
        val glyph = text.glyphBox()
        assertTrue("D24 倒计时在格下一行（不在框尾）", glyph.top >= cells.bottom - 0.5f)
        assertTrue("紧贴格下（≤16）", (glyph.top - cells.bottom) / d <= 16f)
        assertEquals("右对齐到格子右缘", cells.right / d, glyph.right / d, 4f)
    }

    @Test fun d24GetCodeIsALinkColouredWordUnderTheCells() {
        enterStep1()
        val text = textIn(AccountTags.PHONE_STEP1_SEND)
        assertEquals("可点时「获取验证码」", t("phone_change.get_code"), textOfNode(text))
        assertFont("14", 14f, text)
        assertColor("可点时 text.link", palette.link, text.textStyle().color)
        assertTrue("在格下一行", text.glyphBox().top >= boxOf(AccountTags.PHONE_STEP1_CODE).bottom - 0.5f)
    }

    @Test fun d24SendHitAreaIsFortyEightAndDoesNotCoverTheCells() {
        enterStep1()
        val send = node(AccountTags.PHONE_STEP1_SEND)
        val d = send.density()
        assertTrue("命中区 ≥48×48：${send.touch()}", send.touch().height / d >= 47.5f && send.touch().width / d >= 47.5f)
        assertTrue("语义盒也 ≥48", send.box().height / d >= 47.5f)
        assertSeparate("格下一行命中区不压格子", send.touch(), boxOf(AccountTags.PHONE_STEP1_CODE))
        assertTrue("视觉行不被命中区撑高：文字盒高 < 32", textIn(AccountTags.PHONE_STEP1_SEND).textBox().height / d < 32f)
    }

    @Test fun d24LowerEdgeRealTapSendsOnce() {
        enterStep1()
        val send = node(AccountTags.PHONE_STEP1_SEND)
        val d = send.density()
        val touch = send.touch()
        val glyph = textIn(AccountTags.PHONE_STEP1_SEND).glyphBox()
        assertTrue("先正：命中区下缘在文字之外", (touch.bottom - glyph.bottom) / d > 4f)
        tapAt(glyph.center.x, touch.bottom - 2f)
        idleUntil("下缘真实点击发码") { all("POST", SMS).isNotEmpty() }
        assertEquals("恰一次（滚动容器裁切不吃命中区，APP-09v-d r1 S2-1）", 1, all("POST", SMS).size)
    }

    @Test fun d24TappingTheCellsShowsOneActiveCell() {
        enterStep1(sent = true)
        node(AccountTags.PHONE_STEP1_CODE).performClick()
        settle()
        val runs = cellBorderRuns(AccountTags.PHONE_STEP1_CODE)
        assertEquals("先正：6 格", 12, runs.size)
        val widths = runs.map { it.last - it.first + 1 }
        val thin = widths.minOrNull() ?: 0
        val thick = widths.indices.filter { widths[it] >= thin * 3 / 2 && widths[it] > thin }
        assertEquals("当前格 2 border.active（第 1 格左右两段更粗）：$widths", listOf(0, 1), thick)
    }

    // ---------------------------------------------------------------- D26 「原手机号不能用了？联系客服 ›」

    @Test fun d26CantUseOldIsALinkWithAChevronAndA48Target() {
        enterStep1()
        val link = textUnder(AccountTags.PHONE_CANT_USE_OLD, t("phone_change.cant_use_old"))
        assertFont("14", 14f, link)
        assertWeight("500", 500, link)
        assertColor("text.link", palette.link, link.textStyle().color)
        val glyph = link.glyphBox()
        val d = link.density()
        val chevron =
            node(AccountTags.PHONE_PAGE).iconBoxes(12f, 20f).filter {
                it.center.y in glyph.top..glyph.bottom && it.left >= glyph.right - 1f && it.left - glyph.right <= 12f * d
            }
        assertEquals("文字右侧一个 chevron（12–20）", 1, chevron.size)
        val target = node(AccountTags.PHONE_CANT_USE_OLD)
        assertTrue("命中 ≥48", target.touch().height / d >= 47.5f)
        assertTrue("语义盒 ≥48", target.box().height / d >= 47.5f)
    }

    // ---------------------------------------------------------------- D28 第 2 步新号输入

    @Test fun d28PrefixIsSecondaryAndHasNoDivider() {
        enterStep2()
        val prefix = textUnder(AccountTags.PHONE_STEP2, t("phone_change.prefix"))
        assertColor("D28 +86 次文字色", palette.secondary, prefix.textStyle().color)
        assertFont("17 号", 17f, prefix)
        val input = boxOf(AccountTags.PHONE_NEW)
        val d = prefix.density()
        val glyph = prefix.glyphBox()
        val gap = Rect(glyph.right + 2f, input.center.y - 8f * d, input.left - 1f, input.center.y + 8f * d)
        if (gap.width > 0f) assertTrue("+86 与输入之间无分隔线", pixels().ink(gap, palette.surface) == null)
    }

    @Test fun d28PlaceholderShowsUntilTyped() {
        enterStep2()
        val placeholder = keptB("login.phone.placeholder")
        val hint = textUnder(AccountTags.PHONE_STEP2, placeholder)
        assertColor("占位次文字", palette.secondary, hint.textStyle().color)
        type(AccountTags.PHONE_NEW, PHONE_NEW)
        assertEquals("输入后占位消失", 0, textCount(placeholder))
        assertTrue("新号原文在输入框里（冻结测试 :74 依赖）", hasText(PHONE_NEW))
    }

    @Test fun d28FrameIsAtLeastFiftyTwoHighWithAControlBorder() {
        enterStep2()
        val frame = frameOf(AccountTags.PHONE_NEW)
        val d = densityOf(AccountTags.PHONE_NEW)
        assertTrue("D28 框高 ≥52（现 48）：${frame.height / d}", frame.height / d >= 51.5f)
        val px = pixels()
        val edge = px.getPixel((frame.right - FRAME_INSET_DP * d).toInt(), frame.top.toInt())
        assertTrue("上描边 border.control", sameColor(edge, palette.control, BORDER_TOLERANCE))
        val label = textUnder(AccountTags.PHONE_STEP2, t("phone_change.new_phone"))
        assertFont("「新手机号」标签 14", 14f, label)
        assertColor("标签次文字", palette.secondary, label.textStyle().color)
    }

    // ---------------------------------------------------------------- D29 / D30 第 2 步验证码与提示

    @Test fun d29Step2CodeIsSixCellsWithTheSendRowBelow() {
        enterStep2(typed = true)
        assertEquals("6 格", 12, cellBorderRuns(AccountTags.PHONE_STEP2_CODE).size)
        val cells = boxOf(AccountTags.PHONE_STEP2_CODE)
        val text = textIn(AccountTags.PHONE_STEP2_SEND)
        val d = densityOf(AccountTags.PHONE_STEP2_CODE)
        assertTrue("发码在格下一行", text.glyphBox().top >= cells.bottom - 0.5f)
        assertEquals("右对齐", cells.right / d, text.glyphBox().right / d, 4f)
        assertSeparate("命中区不压格子", node(AccountTags.PHONE_STEP2_SEND).touch(), cells)
    }

    @Test fun d30NewHintIsAMutedStripWithAnInfoIcon() {
        enterStep2()
        val hint = textNode(t("phone_change.new_hint"))
        assertFont("14", 14f, hint)
        assertColor("主文字", palette.primary, hint.textStyle().color)
        val block = assertBox("D30 muted 提示条", pixels().blockOf(hint, palette.muted))
        assertEquals("一个 20 info", 1, iconsOn(AccountTags.PHONE_PAGE, block, 18f, 22f).size)
    }

    @Test fun d30SubmitHintSitsUnderSubmitCenteredInTwelveSecondary() {
        enterStep2()
        val hint = textNode(t("phone_change.submit_hint"))
        val submit = boxOf(AccountTags.PHONE_SUBMIT)
        val d = hint.density()
        assertTrue("D30 提示在【提交】下", hint.textBox().top >= submit.bottom - 0.5f)
        assertFont("12", 12f, hint)
        assertColor("次文字", palette.secondary, hint.textStyle().color)
        assertEquals("居中（量字形）", submit.center.x / d, hint.glyphBox().center.x / d, 2f)
    }

    // ---------------------------------------------------------------- D31 待确认（同注销写法）

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun d31PendingCardIsWhiteWithAWarningBlock() {
        enterPhonePending()
        val card = boxOf(AccountTags.PENDING_CARD)
        val d = densityOf(AccountTags.PENDING_CARD)
        val px = pixels()
        assertTrue("白卡", px.cardColorIs(card, palette.surface, d))
        assertFalse("不是整卡警告底", px.cardColorIs(card, palette.warningBackground, d))
        val title = textUnder(AccountTags.PENDING_CARD, t("pending_confirm.title"))
        val desc =
            textUnder(
                AccountTags.PENDING_CARD,
                t("pending_confirm.phone_change.desc", mapOf("phone" to AccountVisualFixtures.pendingSummary("phone_change"))),
            )
        assertFont("标题 14", 14f, title)
        assertWeight("标题 600", 600, title)
        assertColor("警告字色", palette.warningText, desc.textStyle().color)
        val block = assertBox("警告块", px.blockOf(desc, palette.warningBackground))
        assertTrue("标题与说明同块", block.contains(title.textBox().center))
    }

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun d31DetailsShowTheNewPhoneAndTimeButNeverTheCurrentPhone() {
        enterPhonePending()
        val label = textUnder(AccountTags.PENDING_CARD, t("pending_confirm.summary.new_phone"))
        val value = textUnder(AccountTags.PENDING_CARD, AccountVisualFixtures.pendingSummary("phone_change"))
        val block = assertBox("灰底明细", pixels().blockOf(label, palette.muted))
        val d = label.density()
        assertDpNear("值右对齐（块右内 12）", 12f, block.right - value.textBox().right, d, 2f)
        assertTrue("提交时间行（原样）", hasText(t("pending_confirm.summary.submitted_at")))
        assertFalse("G-D05 不显示当前号码", hasText(phone("current")) || hasText("138****5678"))
    }

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun d31TwoEqualButtonsHintAndTheLockedFormBelow() {
        enterPhonePending()
        val abandon = boxOf(AccountTags.PENDING_ABANDON)
        val confirm = boxOf(AccountTags.PENDING_CONFIRM)
        val d = densityOf(AccountTags.PENDING_CARD)
        assertEquals("等宽", abandon.width / d, confirm.width / d, 1f)
        assertTrue("≥48", confirm.height / d >= 47.5f)
        val hint = textUnder(AccountTags.PENDING_CARD, t("phone_change.pending_hint"))
        assertFont("卡底注释 12", 12f, hint)
        assertTrue("注释在按钮下", hint.textBox().top >= confirm.bottom)
        val locked = AccountTags.PHONE_STEP2 + AccountTags.LOCKED_SUFFIX
        assertTrue("置灰第 2 步原样（另一组标记）", has(locked) && has(AccountTags.PHONE_NEW + AccountTags.LOCKED_SUFFIX))
        assertTrue("卡在置灰表单之上", boxOf(AccountTags.PENDING_CARD).bottom <= boxOf(locked).top)
    }

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun gd13InFlightShowsTheStatusRowAndGreysBothButtons() {
        enterPhonePending()
        assertEquals("先反：不在途没有「正在处理」行", 0, busyRows())
        val (arrived, release) = hold("POST", BIND) { envelope("{}") }
        try {
            tap(AccountTags.PENDING_CONFIRM)
            assertTrue("确认已到服务端", arrived.await(WAIT_S, java.util.concurrent.TimeUnit.SECONDS))
            settle()
            assertFalse("在途置灰（原样）", enabled(AccountTags.PENDING_CONFIRM) || enabled(AccountTags.PENDING_ABANDON))
            assertEquals("G-D13 在途行恰一行", 1, busyRows())
            val row = textUnder(AccountTags.PENDING_CARD, t("error.40901"))
            assertColor("次文字", palette.secondary, row.textStyle().color)
            assertEquals("polite", androidx.compose.ui.semantics.LiveRegionMode.Polite, politeOf(row))
        } finally {
            release.countDown()
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun gd13RateLimitGreysWithoutTheStatusRow() {
        enterPhonePending()
        enqueueCode("POST", BIND, 42901, headers = mapOf("Retry-After" to "30"))
        tap(AccountTags.PENDING_CONFIRM)
        idleUntil("限流置灰") { !enabled(AccountTags.PENDING_CONFIRM) && all("POST", BIND).isNotEmpty() }
        settle()
        assertEquals("限流置灰不显示「正在处理」（只读 pending.inFlight）", 0, busyRows())
    }

    // ---------------------------------------------------------------- D32 确认框

    @Test fun d32RiskDialogCentersTheTitleAndKeepsTheButtons() {
        enterRisk44001()
        assertDialogTitleCentered("44001", t("error.44001"))
        assertTrue("正文原样", hasText(t("phone_change.risk_blocked.desc")))
        assertTrue("【知道了】【联系客服申诉】原样", has(AccountTags.PHONE_RISK_OK) && has(AccountTags.PHONE_RISK_APPEAL))
    }

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun d32AbandonDialogCentersTheTitle() {
        enterPhonePending()
        tap(AccountTags.PENDING_ABANDON)
        idleUntil("放弃二次确认") { has(AccountTags.ABANDON_OK) }
        assertDialogTitleCentered("放弃确认框", t("pending_confirm.abandon"))
        assertTrue("换号注释原样", hasText(t("pending_confirm.abandon.note.phone_change")))
    }

    // ---------------------------------------------------------------- D33 完成页

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun d33DoneHasANinetySixSuccessBlockFortyEightBelowTheBar() {
        enterDone()
        val icons = node(AccountTags.PHONE_DONE).iconBoxes(28f, 36f)
        assertEquals("D33 圆勾 32", 1, icons.size)
        val icon = icons.single()
        val d = densityOf(AccountTags.PHONE_DONE)
        // 测试 v2 T2：量图标块背景、避开居中圆勾——宽取图标上方 8dp 的横线（块内、圆角外），高取图标左侧 4dp 的竖线；两条线都不穿过图标
        val px = pixels()
        val across = assertBox("status.success 图标块（横）", px.regionAround(icon.center.x, icon.top - 8f * d, palette.successBackground))
        val down = assertBox("status.success 图标块（竖）", px.regionAround(icon.left - 4f * d, icon.center.y, palette.successBackground))
        val block = Rect(across.left, down.top, across.right, down.bottom)
        assertTrue("圆勾在块内", block.contains(icon.center) && icon.top >= block.top && icon.bottom <= block.bottom)
        assertEquals("块宽 96", 96f, block.width / d, 2f)
        assertEquals("块高 96", 96f, block.height / d, 2f)
        val page = boxOf(AccountTags.PHONE_PAGE)
        assertEquals("块居中", page.center.x / d, block.center.x / d, 2f)
        val scroll = composeRule.scrollUnder(AccountTags.PHONE_PAGE).box()
        assertEquals("内容上 48", 48f, (block.top - scroll.top) / d, 3f)
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun d33DoneTitleAndBodyAreCentered() {
        enterDone()
        val title = textUnder(AccountTags.PHONE_DONE, t("phone_change.done.title"))
        val body = textNode(t("phone_change.done.body", mapOf("phone" to phone("freshMasked"))))
        val d = title.density()
        val center = boxOf(AccountTags.PHONE_PAGE).center.x / d
        assertFont("标题 17", 17f, title)
        assertWeight("600", 600, title)
        assertEquals("标题居中", center, title.glyphBox().center.x / d, 2f)
        assertFont("说明 14", 14f, body)
        assertColor("说明次文字", palette.secondary, body.textStyle().color)
        assertEquals("说明居中", center, body.glyphBox().center.x / d, 2f)
    }

    // ---------------------------------------------------------------- D34 轻提示

    @Test
    @Config(qualifiers = "w390dp-h1029dp-xhdpi")
    fun d34AbandonedToastCarriesACheckAndReturnsToStep1() {
        enterPhonePending()
        tap(AccountTags.PENDING_ABANDON)
        idleUntil { has(AccountTags.ABANDON_OK) }
        tap(AccountTags.ABANDON_OK)
        idleUntil("已放弃轻提示") { has(MeTags.TOAST) }
        assertTrue("文案原样", hasText(t("pending_confirm.abandoned")))
        assertEquals("G-S06 成功类带对勾", 1, node(MeTags.TOAST).iconBoxes(16f, 24f).size)
        assertTrue("回第 1 步（原样）", has(AccountTags.PHONE_STEP1))
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun d34AlreadyDoneToastCarriesACheckOnTheDonePage() {
        val key = enterPhonePending()
        serverState.results[key] = 0 to "{}"
        tap(AccountTags.PENDING_ABANDON)
        idleUntil { has(AccountTags.ABANDON_OK) }
        tap(AccountTags.ABANDON_OK)
        idleUntil("已处理完成") { has(MeTags.TOAST) && has(AccountTags.PHONE_DONE) }
        assertTrue(hasText(t("pending_confirm.already_done")))
        assertEquals("带对勾", 1, node(MeTags.TOAST).iconBoxes(16f, 24f).size)
    }

    // ---------------------------------------------------------------- 辅助

    private fun iconsOn(
        tag: String,
        area: Rect,
        min: Float,
        max: Float,
    ): List<Rect> = node(tag).iconBoxes(min, max).filter { it.overlaps(area) }

    private fun busyRows(): Int = countUnder(AccountTags.PENDING_CARD, textMatcher(t("error.40901")))

    private fun politeOf(text: androidx.compose.ui.test.SemanticsNodeInteraction): androidx.compose.ui.semantics.LiveRegionMode? {
        // 在途行整行一个读屏元素（合并节点上带 liveRegion）；文字节点本身或其合并祖先带 polite 都算
        val own = text.fetchSemanticsNode()
        var n: androidx.compose.ui.semantics.SemanticsNode? = own
        while (n != null) {
            n.config.getOrNull(SemanticsProperties.LiveRegion)?.let { return it }
            n = n.parent
        }
        return null
    }

    private fun assertSeparate(
        message: String,
        first: Rect,
        second: Rect,
    ) {
        val i = first.intersect(second)
        assertTrue("$message：$first / $second", i.width <= 1f || i.height <= 1f)
    }
}
