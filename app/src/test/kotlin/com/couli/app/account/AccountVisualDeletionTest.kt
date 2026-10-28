@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.hasText as textMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.me.box
import com.couli.app.me.density
import com.couli.app.me.edgeUp
import com.couli.app.me.iconBoxes
import com.couli.app.me.sameColor
import com.couli.app.me.scrollUnder
import com.couli.app.me.textBox
import com.couli.app.me.textStyle
import com.couli.app.me.touch
import com.couli.core.designsystem.CouliFormat
import com.couli.feature.me.AccountTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-13v-a 注销页「说明与确认 / 负余额 / 30412 / 风控来源 / 受限会话 / 无稿态」的结构与外观（brief-android D1–D13、D19；gaps G-D07 / G-D09 / G-D11 / G-D12 / G-D14）。
 * 画板 DeleteAccount-v2 1164 高，视口取 w390dp-h1164dp：整页不滚动即可量。底版上视觉断言应红（新写法尚未实现），先正的对照断言应绿。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1164dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions", "LargeClass")
class AccountVisualDeletionTest : AccountVisualDeletionHarness() {
    // ---------------------------------------------------------------- D1 内容边距

    /** D1：内容 上 8 左右 16（SETTINGS_CONTENT_PADDING）；处理中态结构不改（G-D25），用它量首块到滚动区顶的距离。 */
    @Test fun d1ContentPaddingIsTop8Sides16() {
        serverState.deletion = deletionFixture("processing")
        launch()
        openDeleteFromSettings()
        idleUntil { has(AccountTags.DELETE_PROCESSING) }
        val scroll = composeRule.scrollUnder(AccountTags.DELETE_PAGE).box()
        val first = node(AccountTags.DELETE_PROCESSING).box()
        val d = pageDensity()
        assertDpNear("先正：左 16", 16f, first.left - scroll.left, d, 1f)
        assertDpNear("D1 上 8（现 16）", 8f, first.top - scroll.top, d, 1f)
    }

    // ---------------------------------------------------------------- D2 页首警告提示条

    @Test fun d2IntroIsAWarningStripWithAlertIcon() {
        enterInitial()
        val intro = node(AccountTags.DELETE_INTRO)
        assertEquals("G-D07 页首句换定稿", AccountVisualFixtures.draft("deletion.intro"), textOf(AccountTags.DELETE_INTRO))
        assertFont("14 号", 14f, intro)
        assertColor("警告字色", palette.warningText, intro.textStyle().color)
        val strip = assertBox("status.warning 底的提示条", pixels().blockOf(intro, palette.warningBackground))
        val d = pageDensity()
        assertDpNear("提示条通栏（390 - 16 × 2）", 358f, strip.width, d, 2f)
        val icons = iconsIn(strip, 18f, 22f)
        assertEquals("条内恰一个 20 警告三角", 1, icons.size)
        assertTrue("图标在文字左侧", icons.single().right <= intro.textBox().left + d)
        assertDpNear("提示条内边距 12（图标左距条左）", 12f, icons.single().left - strip.left, d, 1.5f)
    }

    // ---------------------------------------------------------------- D3 统一错误（30412）

    @Test fun d3Error30412IsAWarningStripAtTheTopAndStillAssertive() {
        enter30412()
        val error = node(AccountTags.DELETE_ERROR)
        assertEquals("先正：30412 句原样", t("error.30412"), textOf(AccountTags.DELETE_ERROR))
        assertEquals("读屏 assertive 一次（原样）", LiveRegionMode.Assertive, liveRegion(AccountTags.DELETE_ERROR))
        assertColor("D3 警告字色（不再是红字）", palette.warningText, error.textStyle().color)
        val strip = assertBox("D3 status.warning 提示条", pixels().blockOf(error, palette.warningBackground))
        assertEquals("条内一个 20 三角", 1, iconsIn(strip, 18f, 22f).size)
        assertTrue("在资金卡之前（页首）", strip.bottom <= boxOf(AccountTags.DELETE_FUNDS).top)
        assertTrue("勾选保留（G14，行为原样）", toggle(AccountTags.DELETE_WAIVER) == ToggleableState.On)
    }

    // ---------------------------------------------------------------- D4 / D6 负余额

    @Test fun d4NegativeSentenceMovesToTheTopStripWithAnInlineContactLink() {
        enterNegative()
        val amount = CouliFormat.money(AccountVisualFixtures.walletFen("negative", "negative_fen"))
        val notice = node(AccountTags.DELETE_NEGATIVE_NOTICE)
        assertEquals(
            "句子与金额原样（error.30416 + CouliFormat.money）",
            t("error.30416", mapOf("amount" to amount)),
            textOf(AccountTags.DELETE_NEGATIVE_NOTICE),
        )
        assertEquals("polite 播报（原样）", LiveRegionMode.Polite, liveRegion(AccountTags.DELETE_NEGATIVE_NOTICE))
        val strip = assertBox("D4 status.warning 提示条", pixels().blockOf(notice, palette.warningBackground))
        assertTrue("D4 在资金卡之前", strip.bottom <= boxOf(AccountTags.DELETE_FUNDS).top)
        assertColor("警告字色", palette.warningText, notice.textStyle().color)
        val cs = boxOf(AccountTags.DELETE_CONTACT_CS)
        assertTrue("【联系客服】在条内（中心落在条的外框里）：$cs / $strip", strip.contains(cs.center))
        val label = textUnder(AccountTags.DELETE_CONTACT_CS, t("deletion.contact_cs"))
        assertColor("条内链接 text.link", palette.link, label.textStyle().color)
        assertFont("链接 14", 14f, label)
        assertLinkTarget(AccountTags.DELETE_CONTACT_CS)
    }

    @Test fun d6NegativeRowAndNote() {
        enterNegative()
        val amount = CouliFormat.money(AccountVisualFixtures.walletFen("negative", "negative_fen"))
        val label = textUnder(AccountTags.DELETE_NEGATIVE, t("deletion.negative"))
        val value = textUnder(AccountTags.DELETE_NEGATIVE, amount)
        val d = pageDensity()
        assertTrue("标签左 / 值右（同一行）", label.textBox().right < value.textBox().left)
        val card = assertBox("资金卡外框", pixels().blockOf(label, palette.surface, 2f))
        assertDpNear("值右对齐卡内 16", 16f, card.right - value.textBox().right, d, 2f)
        val note = textNode(AccountVisualFixtures.draft("deletion.negative.note"))
        assertFont("G-D07 说明句 14", 14f, note)
        assertColor("次文字", palette.secondary, note.textStyle().color)
        assertFalse("旧句不再出现", hasText("有订单被平台扣回返利"))
    }

    /** D11 ⑦ 不做：负余额态底栏的勾选行与提示句按现状置灰显示（行为相同）。 */
    @Test fun negativeKeepsTheDisabledWaiverAndHint() {
        enterNegative()
        assertTrue("勾选行仍在", has(AccountTags.DELETE_WAIVER))
        assertFalse("勾选置灰", enabled(AccountTags.DELETE_WAIVER))
        assertFalse("申请置灰", enabled(AccountTags.DELETE_SUBMIT))
        assertTrue("提示句仍在", hasText(t("deletion.submit_hint")))
        assertFalse("负余额无【去提现】（showGoWithdraw 原样）", has(AccountTags.DELETE_GO_WITHDRAW))
    }

    // ---------------------------------------------------------------- D5 资金卡两列大数

    @Test fun d5FundsCardHasTitleInsideAndTwoColumns() {
        enterInitial()
        val title = textNode(t("deletion.funds.title"))
        assertTrue(
            "D5 卡内标题（不再是卡外 GroupLabel）",
            countUnder(AccountTags.DELETE_FUNDS, textMatcher(t("deletion.funds.title"))) == 1,
        )
        assertFont("标题 17", 17f, title)
        assertWeight("标题 600", 600, title)
        val w = boxOf(AccountTags.DELETE_WITHDRAWABLE)
        val e = boxOf(AccountTags.DELETE_ESTIMATED)
        val d = pageDensity()
        assertEquals("两列同一行（顶对齐）", w.top / d, e.top / d, 1.5f)
        assertTrue("可提现在左、预估收益在右", w.right <= e.left + d)
        assertEquals("两列等宽（各 weight 1）", w.width / d, e.width / d, 2f)
        assertTrue("标题在两列之上", title.textBox().bottom <= w.top + d)
    }

    @Test fun d5AmountsAre28WithWeightsAndEmphasis() {
        enterInitial()
        val withdrawable =
            textUnder(AccountTags.DELETE_WITHDRAWABLE, CouliFormat.money(AccountVisualFixtures.walletFen("funds", "withdrawable_fen")))
        val estimated =
            textUnder(AccountTags.DELETE_ESTIMATED, CouliFormat.money(AccountVisualFixtures.walletFen("funds", "estimated_total_fen")))
        assertFont("可提现 28", 28f, withdrawable)
        assertWeight("可提现 700", 700, withdrawable)
        assertColor("可提现主文字", palette.primary, withdrawable.textStyle().color)
        assertFont("预估收益 28", 28f, estimated)
        assertWeight("预估收益 600", 600, estimated)
        assertColor("预估收益 brand.emphasis（GUIDE §7.1，G-D24）", palette.emphasis, estimated.textStyle().color)
        val label = textUnder(AccountTags.DELETE_WITHDRAWABLE, t("deletion.withdrawable"))
        assertFont("标签 12", 12f, label)
        assertColor("标签次文字", palette.secondary, label.textStyle().color)
        assertTrue("标签在金额之上", label.textBox().bottom <= withdrawable.textBox().top + pageDensity())
        assertEquals(
            "预估收益列内一个 14 装饰时钟",
            1,
            node(AccountTags.DELETE_ESTIMATED).iconBoxes(12f, 16f).size,
        )
        assertEquals("可提现列无图标（先正后反的对照）", 0, node(AccountTags.DELETE_WITHDRAWABLE).iconBoxes(12f, 16f).size)
    }

    @Test fun d5EachColumnReadsAsOneNode() {
        enterInitial()
        val spoken = spokenOf(AccountTags.DELETE_WITHDRAWABLE)
        assertTrue("每列合并「标签，金额」：$spoken", spoken.any { it.contains(t("deletion.withdrawable")) })
        assertTrue("每列合并「标签，金额」：$spoken", spoken.any { it.contains(CouliFormat.money(1234)) })
    }

    @Test fun d5FundsCardPaddingIs16() {
        enterInitial()
        val title = textNode(t("deletion.funds.title"))
        val card = assertBox("资金卡（白卡）", pixels().blockOf(title, palette.surface, 2f))
        val d = pageDensity()
        assertDpNear("卡左距屏 16", 16f, card.left, d, 1f)
        assertDpNear("卡内左 16", 16f, title.textBox().left - card.left, d, 1.5f)
        assertDpNear("卡内上 16", 16f, title.textBox().top - card.top, d, 2f)
    }

    // ---------------------------------------------------------------- D7 / D8 提示句与【去提现】

    @Test fun d7HintAndGoWithdrawLinkShareOneRow() {
        enterInitial()
        val hint = textNode(t("deletion.withdraw_hint"))
        assertFont("提示句 14", 14f, hint)
        assertColor("提示句次文字", palette.secondary, hint.textStyle().color)
        val link = textUnder(AccountTags.DELETE_GO_WITHDRAW, t("deletion.go_withdraw"))
        assertColor("D7 文字链接 text.link", palette.link, link.textStyle().color)
        assertFont("链接 14", 14f, link)
        assertWeight("链接 500", 500, link)
        val d = pageDensity()
        val row = hint.textBox()
        assertTrue("句子与链接同一行（链接中线落在句子首行 24dp 内）", link.textBox().center.y in row.top..(row.top + 24f * d))
        assertTrue("链接在句子右侧", link.textBox().left >= hint.textBox().right - d)
        assertLinkTarget(AccountTags.DELETE_GO_WITHDRAW)
    }

    @Test fun d7GoWithdrawLinkHasNoButtonChrome() {
        enterInitial()
        val link = boxOf(AccountTags.DELETE_GO_WITHDRAW)
        val label = textUnder(AccountTags.DELETE_GO_WITHDRAW, t("deletion.go_withdraw")).textBox()
        val px = pixels()
        val around =
            androidx.compose.ui.geometry.Rect(
                link.left,
                label.top - 4f * pageDensity(),
                link.right,
                label.bottom + 4f * pageDensity(),
            )
        assertNull("链接周围没有控件描边（不是次按钮）", px.colorBox(around, palette.control, 2))
        assertTrue("先正：链接文字本身是 text.link 色", px.colorBox(label, palette.link, 24) != null)
    }

    /** G-D12：提示句按 view.showGoWithdraw 选两条草稿之一；「句子提到提现 ⇔ 页面有【去提现】」。 */
    @Test fun gd12SettingsSourceMentionsWithdrawAndHasTheLink() {
        enterInitial()
        assertTrue("有【去提现】", has(AccountTags.DELETE_GO_WITHDRAW))
        assertTrue("句子提到提现", hasText(AccountVisualFixtures.kept("deletion.withdraw_hint")))
    }

    /**
     * 测试 v2 T3：冻结规则 `canWithdraw = full && source != FORCE_UPDATE`，冻结向量 specs/account-vectors.json `G12_initial_risk_notice_full`
     * 期望 showGoWithdraw = true——风控来源 + full 会话仍有【去提现】，句子按 G-D12 是原句。brief「风控来源 showGoWithdraw=false」与规则不符，按规则核；
     * 「句子提到提现 ⇔ 页面有【去提现】」不变（无去提现的选句由 gd12Restricted 核）。
     */
    @Test fun gd12RiskSourceFullSessionKeepsTheWithdrawAdvice() {
        enterFromRisk()
        assertTrue("风控来源 + full 会话有【去提现】（规则原样）", has(AccountTags.DELETE_GO_WITHDRAW))
        assertTrue("句子提到提现（原句）", hasText(AccountVisualFixtures.kept("deletion.withdraw_hint")))
        assertEquals("不出 G-D12 新句", 0, textCount(AccountVisualFixtures.draft("deletion.withdraw_hint.no_withdraw")))
    }

    @Test fun gd12RestrictedSessionDropsTheWithdrawAdvice() {
        enterRestricted()
        assertFalse("deletion_only 无【去提现】（原样）", has(AccountTags.DELETE_GO_WITHDRAW))
        assertEquals("G-D12 新句恰一次", 1, textCount(AccountVisualFixtures.draft("deletion.withdraw_hint.no_withdraw")))
        assertFalse("不再出现「建议先提现」", hasText("建议先提现"))
    }

    // ---------------------------------------------------------------- D9 须知卡

    @Test fun d9NoticeCardHasTitleAndSixBulletsWithoutNumbers() {
        enterInitial()
        assertEquals("D9 卡内标题", 1, countUnder(AccountTags.DELETE_NOTICE, textMatcher(t("deletion.notice.title"))))
        val title = textUnder(AccountTags.DELETE_NOTICE, t("deletion.notice.title"))
        assertFont("标题 17", 17f, title)
        assertWeight("标题 600", 600, title)
        val lines =
            (1..AccountVisualFixtures.text("noticeCount").toInt()).map { i ->
                textUnder(AccountTags.DELETE_NOTICE, t("deletion.notice.$i"))
            }
        lines.forEach {
            assertFont("条目 14", 14f, it)
            assertColor("条目主文字", palette.primary, it.textStyle().color)
        }
        assertFalse("不再「1. 」编号", hasText("1. "))
        assertEquals("六个 6 圆点（装饰）", 6, node(AccountTags.DELETE_NOTICE).iconBoxes(5f, 7f).size)
        val d = pageDensity()
        lines.zipWithNext().forEach { (a, b) ->
            assertDpNear("条目纵向 8", 8f, b.textBox().top - a.textBox().bottom, d, 1.5f)
        }
        assertFalse("G-D09 不加「查看完整《注销须知》」", hasText("查看完整"))
    }

    @Test fun gd07NoticeDraftsUseTheFinalWording() {
        enterInitial()
        assertEquals(AccountVisualFixtures.draft("deletion.notice.4"), t("deletion.notice.4"))
        assertEquals(AccountVisualFixtures.draft("deletion.notice.5"), t("deletion.notice.5"))
        assertTrue("须知 4 定稿在页上", hasText(AccountVisualFixtures.draft("deletion.notice.4")))
        assertTrue("须知 5 定稿在页上（180 天）", hasText(AccountVisualFixtures.draft("deletion.notice.5")))
        AccountVisualFixtures.list("retiredPhrases").forEach { assertFalse("旧句不出现：$it", hasText(it)) }
    }

    // ---------------------------------------------------------------- D10 白底固定底栏

    @Test fun d10BottomBarIsWhiteWithTopHairline() {
        enterInitial()
        val submit = boxOf(AccountTags.DELETE_SUBMIT)
        val d = pageDensity()
        val px = pixels()
        val y = submit.center.y
        assertTrue("D10 底栏白底（按钮左侧 4dp）", sameColor(px.getPixel((4f * d).toInt(), y.toInt()), palette.surface, 2))
        assertTrue("D10 底栏白底（按钮右侧 4dp）", sameColor(px.getPixel(px.width - (4f * d).toInt(), y.toInt()), palette.surface, 2))
        val top = px.edgeUp(4f * d, y, palette.surface)
        assertTrue("上沿 1px border.subtle", sameColor(px.getPixel((8f * d).toInt(), (top - 1f).toInt()), palette.subtle, 3))
        assertDpNear("栏内上 12（勾选行外框顶）", 12f, boxOf(AccountTags.DELETE_WAIVER).top - top, d, 1.5f)
        assertDpNear("按钮左 16", 16f, submit.left, d, 1f)
    }

    // ---------------------------------------------------------------- D11 圆形勾选行

    @Test fun d11WaiverIsARound18Check() {
        enterInitial()
        val circle = waiverCircle()
        val d = pageDensity()
        assertDpNear("D11 圆 18（现方 20）", 18f, circle.width, d, 0.75f)
        val px = pixels()
        val corner = px.getPixel((circle.left + 1.5f * d).toInt(), (circle.top + 1.5f * d).toInt())
        assertFalse("圆形：外接框四角不是描边色", sameColor(corner, palette.control, 12))
        val edge = px.getPixel((circle.left + 0.6f * d).toInt(), circle.center.y.toInt())
        assertTrue("先正：左缘中点是 border.control 描边", sameColor(edge, palette.control, 24))
        checkWaiver()
        val on = waiverCircle()
        val px2 = pixels()
        assertTrue(
            "勾选 brand.primary 实心（左缘内 2dp）",
            sameColor(px2.getPixel((on.left + 2f * d).toInt(), on.center.y.toInt()), palette.brand, 12),
        )
        assertFalse("勾选后四角仍不是品牌色（圆）", sameColor(px2.getPixel((on.left + 1f * d).toInt(), (on.top + 1f * d).toInt()), palette.brand, 12))
    }

    /** 测试 v3（评审 r1 S2-1）：浅色已勾选的勾是 text.inverse（浅色 text.inverse 与 background.surface 同为白，防回归）。 */
    @Test fun d11CheckMarkIsTextInverseLight() {
        enterInitial(checked = true)
        assertCheckMarkIsTextInverse()
    }

    /**
     * 测试 v3（评审 r1 S2-1）：深色已勾选的勾是 text.inverse（深色仍为白），不是 background.surface（深色 #24211E，压在橙色实心圆上看不清）。
     * 组件库同款 CheckboxGlyph 用 text.inverse。
     */
    @Test fun d11CheckMarkIsTextInverseDark() {
        dark = true
        enterInitial(checked = true)
        assertTrue(
            "先正：深色下 text.inverse 与 background.surface 可区分（否则本条判不出 S2-1）",
            !sameColor(palette.inverse.toArgb(), palette.surface, 24),
        )
        val area = assertCheckMarkIsTextInverse()
        val px = pixels()
        val surfaceLike = pixelsIn(px, area).count { sameColor(it, palette.surface, 24) }
        assertEquals("勾不是 background.surface 色", 0, surfaceLike)
    }

    @Test fun d11WaiverKeepsCheckboxSemanticsAndTouchTarget() {
        enterInitial()
        val waiver = node(AccountTags.DELETE_WAIVER)
        val config = waiver.fetchSemanticsNode().config
        assertEquals("复选框角色", Role.Checkbox, config.getOrNull(SemanticsProperties.Role))
        assertEquals("未选中", ToggleableState.Off, config.getOrNull(SemanticsProperties.ToggleableState))
        assertEquals("状态描述原样", t("deletion.waiver.unchecked"), config.getOrNull(SemanticsProperties.StateDescription))
        val d = pageDensity()
        assertTrue("命中 ≥48", waiver.touch().height / d >= 47.5f)
        assertTrue("语义盒 ≥48", waiver.box().height / d >= 47.5f)
        val text = textUnder(AccountTags.DELETE_WAIVER, t("deletion.waiver"))
        assertFont("14 主文字", 14f, text)
        assertDpNear("圆 → 文字 12", 12f, text.textBox().left - waiverCircle().right, d, 1f)
    }

    // ---------------------------------------------------------------- D12 / D13 原样

    @Test fun d12SubmitAndHintAreUnchangedInPlace() {
        enterInitial()
        assertFalse("未勾选置灰", enabled(AccountTags.DELETE_SUBMIT))
        val hint = textNode(t("deletion.submit_hint"))
        assertFont("提示 12", 12f, hint)
        assertColor("次文字", palette.secondary, hint.textStyle().color)
        assertTrue("提示句在按钮之下", hint.textBox().top >= boxOf(AccountTags.DELETE_SUBMIT).bottom - pageDensity())
    }

    @Test fun d13NoPhonePromptStaysInTheBottomBarWithBindButton() {
        serverState.phoneBound = false
        enterInitial()
        checkWaiverAndSubmit()
        idleUntil { has(AccountTags.DELETE_NO_PHONE) }
        assertEquals("句子原样", t("deletion.step_up_unsupported"), textOf(AccountTags.DELETE_NO_PHONE))
        assertTrue("G-D14 【去绑定手机号】仍在", has(AccountTags.DELETE_BIND_PHONE))
        assertTrue("位置不改：在【申请注销】之上", boxOf(AccountTags.DELETE_NO_PHONE).bottom <= boxOf(AccountTags.DELETE_SUBMIT).top)
        assertTrue(
            "在白底栏内",
            sameColor(
                pixels().getPixel((4f * pageDensity()).toInt(), boxOf(AccountTags.DELETE_NO_PHONE).center.y.toInt()),
                palette.surface,
                2,
            ),
        )
    }

    // ---------------------------------------------------------------- D19 无稿态结构不改

    @Test fun d19LoadFailedKeepsErrorStateAndRetry() {
        serverState.wallet = walletFixture("funds")
        enterVia { failAlways("GET", DELETION, 50001) }
        idleUntil { has(AccountTags.DELETE_LOAD_ERROR) }
        assertTrue("错误态 + 重试（结构不改）", hasText(t("deletion.load_failed")) && hasText(t("deletion.retry")))
        assertFalse("读取失败无底栏勾选", has(AccountTags.DELETE_WAIVER))
    }

    @Test fun d19FundsFailedKeepsRetryInsideTheCard() {
        serverState.deletion = deletionFixture("none")
        enterVia { failAlways("GET", WALLET, 50001) }
        awaitFunds()
        assertTrue(has(AccountTags.DELETE_FUNDS_ERROR))
        assertTrue("资金重试按钮仍在资金卡内", has(AccountTags.DELETE_FUNDS_RETRY))
        assertTrue(boxOf(AccountTags.DELETE_FUNDS).contains(boxOf(AccountTags.DELETE_FUNDS_RETRY).center))
    }

    // ---------------------------------------------------------------- 辅助

    /**
     * 勾选圆中心 ±5dp 的正方形（四角距圆心 7.07dp，落在 18 圆半径 9 减描边 1.5 之内：避开圆周与其抗锯齿边，只含品牌实心与勾）。
     * 先正：区域里有品牌实心；再核勾——离 brand.primary 最远的像素（勾的描边中心，1.75px 描边有全覆盖像素）接近 text.inverse，
     * 且接近 text.inverse 的像素不少于 6 个（不是零星抗锯齿点）。返回取样区域。
     */
    private fun assertCheckMarkIsTextInverse(): androidx.compose.ui.geometry.Rect {
        assertEquals("已勾选", ToggleableState.On, toggle(AccountTags.DELETE_WAIVER))
        val circle = waiverCircle()
        val d = pageDensity()
        val area =
            androidx.compose.ui.geometry.Rect(
                circle.center.x - CHECK_PROBE_DP * d,
                circle.center.y - CHECK_PROBE_DP * d,
                circle.center.x + CHECK_PROBE_DP * d,
                circle.center.y + CHECK_PROBE_DP * d,
            )
        val px = pixels()
        val colors = pixelsIn(px, area)
        assertTrue("先正：取样区里有 brand.primary 实心", colors.count { sameColor(it, palette.brand, 12) } >= colors.size / 4)
        val brand = palette.brand.toArgb()
        val mark = colors.maxBy { distance(it, brand) }
        assertTrue(
            "勾的颜色是 text.inverse：期望 ${palette.inverse} 实际 #${Integer.toHexString(mark)}",
            sameColor(mark, palette.inverse, 40),
        )
        assertTrue("勾有成片的 text.inverse 像素", colors.count { sameColor(it, palette.inverse, 40) } >= 6)
        return area
    }

    private fun pixelsIn(
        px: android.graphics.Bitmap,
        area: androidx.compose.ui.geometry.Rect,
    ): List<Int> =
        (area.top.toInt()..area.bottom.toInt()).flatMap { y ->
            (area.left.toInt()..area.right.toInt()).map { x -> px.getPixel(x, y) }
        }

    private fun distance(
        a: Int,
        b: Int,
    ): Int = listOf(16, 8, 0).sumOf { s -> kotlin.math.abs((a ushr s and 255) - (b ushr s and 255)) }

    private fun toggle(tag: String): ToggleableState? = node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)

    /** 勾选行里的勾选圆（15–22dp 的正方形叶子或带勾的盒；取最靠左的那个）。 */
    private fun waiverCircle(): androidx.compose.ui.geometry.Rect {
        val boxes = node(AccountTags.DELETE_WAIVER).allSquares(15f, 22f)
        assertTrue("勾选行里找不到勾选框", boxes.isNotEmpty())
        return boxes.minBy { it.left }
    }

    /** 链接：触摸盒与语义盒都 ≥48（APP-12w-a r1 S2-4），可点（Role.Button 或点击动作）。 */
    private fun assertLinkTarget(tag: String) {
        val n = node(tag)
        val d = n.density()
        assertTrue("$tag 触摸盒 ≥48：${n.touch()}", n.touch().height / d >= 47.5f && n.touch().width / d >= 47.5f)
        assertTrue("$tag 语义盒 ≥48：${n.box()}", n.box().height / d >= 47.5f)
        assertTrue("$tag 可点", n.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick))
    }

    private companion object {
        /** 勾取样区半边长（dp）。 */
        const val CHECK_PROBE_DP = 5f
    }
}
