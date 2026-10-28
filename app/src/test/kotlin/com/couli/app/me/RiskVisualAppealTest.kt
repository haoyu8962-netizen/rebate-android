package com.couli.app.me

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.textLayout
import com.couli.feature.me.AccountTags
import com.couli.feature.me.MeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 申诉 Appeal 的版式（brief-c-android R11–R27，G-R10–G-R14 / G-R17）。底版上视觉断言应红（见交回 §5）。
 * 卡片一律「卡内标题 17/600 + 行内『标签 值』14」；数据只取已有字段（「申诉内容 · 原因」与「提现 · 已恢复」按 G-R11 不加）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1600dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions", "LargeClass")
class RiskVisualAppealTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { RiskVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    private fun t(key: String) = AppMeTexts.text(key)

    private val page get() = host.node(MeTags.APPEAL_PAGE)
    private val d get() = page.density()

    // ---------------------------------------------------------------- 提交（R11–R15）

    @Test fun r11SubmitContentStartsAt8WithSides16() {
        host.launchAppeal()
        val card = cardOf(host.pageHeading(t("appeal.target")))
        val scrollTop = rule.scrollUnder(MeTags.APPEAL_PAGE).box().top
        assertDp("内容上 8", 8f, card.top - scrollTop, d)
        assertDp("左右 16", 16f, card.left, d)
        assertDp("右 16", 16f, page.box().right - card.right, d)
    }

    @Test fun r12TargetCardHasAnInsideTitleAndInlineRows() {
        host.launchAppeal()
        val title = host.pageHeading(t("appeal.target"))
        val card = cardOf(title)
        assertCardTitle(title, card)
        inline(t("appeal.type"), t("appeal.type.account"))
        inline(t("appeal.reason"), t("risk_reason.malicious_rights"))
        inline(t("appeal.current_state"), t("risk.banned.title"), pillValue = true)
        val type = host.pageText(t("appeal.type")).textBox()
        val state = host.pageText(t("appeal.current_state")).textBox()
        val reason = host.pageText(t("appeal.reason")).textBox()
        assertTrue("类型 → 当前状态 → 原因", type.top < state.top && state.top < reason.top)
        assertDp("行标签左对齐卡内 16", 16f, state.left - card.left, d)
    }

    @Test fun r12BannedCurrentStateIsAnErrorPillWithTriangle() {
        host.launchAppeal()
        pill(t("risk.banned.title"), host.palette.errorBackground, host.palette.errorText, icon = true)
    }

    @Test fun r12FrozenTargetShowsUntilInline() {
        host.launchAppeal(risk = "risk_frozen")
        inline(t("appeal.frozen_until"), RiskVisualFixtures.expect("frozenUntilText"))
        pill(t("risk.frozen.title"), host.palette.warningBackground, host.palette.warningText, icon = true)
    }

    @Test fun r13InputIsAMultilineCouliTextFieldInsideACardWithPlaceholder() {
        host.launchAppeal()
        val label = host.pageHeading(t("appeal.content_label"))
        assertCardTitle(label, cardOf(label))
        val field = host.editable()
        val desc =
            field
                .fetchSemanticsNode()
                .config
                .getOrElseNullable(SemanticsProperties.ContentDescription) { null }
                .orEmpty()
        assertTrue("读屏名称 = 申诉说明（accessibilityLabel，可见标签在框外）：$desc", desc.contains(t("appeal.content_label")))
        val px = host.pixels(page)
        val node = host.node(MeTags.APPEAL_INPUT).box()
        val area = Rect(node.left - 4f * d, node.top - 4f * d, node.right + 4f * d, node.bottom + 4f * d)
        val border = assertBox("输入框边框 border.control", px.pvColorBox(area, host.palette.control, 10))
        assertAtLeastDp("输入框 min 160", 160f, border.height, d)
        assertDp("输入框通栏于卡内 16", 16f, border.left - cardOf(label).left, d, 1.5f)
        val placeholder = host.pageText(RiskVisualFixtures.draft("appeal.placeholder"))
        assertColor("占位次文字色（G-R14）", host.palette.secondary, placeholder.color())
        assertFont("占位 17", 17f, placeholder)
        host.input(RiskVisualFixtures.input)
        assertEquals("有输入后占位消失", 0, host.count(RiskVisualFixtures.draft("appeal.placeholder")))
    }

    @Test fun r15SubmitSitsInTheWhiteFixedBottomBar() {
        host.launchAppeal()
        val button = host.node(MeTags.APPEAL_SUBMIT).box()
        val px = host.pixels(page)
        assertTrue("提交按钮旁是白底栏（surface）", sameColor(px.at(4f * d, button.center.y), host.palette.surface, 2))
        val barTop = px.edgeUp(4f * d, button.center.y, host.palette.surface)
        assertTrue("栏上沿 1px border.subtle", sameColor(px.at(4f * d, barTop - 1f), host.palette.subtle, 6))
        assertDp("栏内上 12", 12f, button.top - barTop, d, 1.5f)
        assertDp("按钮左 16", 16f, button.left, d)
    }

    // ---------------------------------------------------------------- 处理中（R16–R19）

    @Test fun r16ProgressStatusCardHoldsPillHeadingAndDesc() {
        host.launchAppeal(risk = "risk_appealing_banned", appeals = "appeals_processing_banned")
        val heading = host.pageHeading(t("appeal.progress.heading"))
        val card = cardOf(heading)
        assertDp("卡内左 16", 16f, heading.textBox().left - card.left, d)
        assertFont("标题 22", 22f, heading)
        assertEquals(FontWeight.SemiBold, heading.weight())
        val pillText = host.pageTexts(t("appeal.progress.badge")).first()
        val pill = pill(pillText, host.palette.pendingBackground, host.palette.pendingText, icon = true)
        assertTrue("胶囊在卡内", card.contains(pill.center))
        assertDp("胶囊 → 标题 8", 8f, heading.textBox().top - pill.bottom, d, 1.5f)
        val desc = host.pageText(t("appeal.progress.desc.banned"))
        assertDp("标题 → 说明 8", 8f, desc.textBox().top - heading.textBox().bottom, d, 1.5f)
        assertFont("说明 14", 14f, desc)
        assertColor("说明次文字色", host.palette.secondary, desc.color())
    }

    @Test fun r17ProgressTimelineHasIconsConnectorsAndStepStyles() {
        host.launchAppeal(risk = "risk_appealing_banned", appeals = "appeals_processing_banned")
        val submitted = host.pageText(t("appeal.timeline.submitted"))
        val processing = host.pageTexts(t("appeal.timeline.processing")).last()
        val result = host.pageText(t("appeal.timeline.result"))
        listOf(submitted, processing, result).forEach { assertFont("步骤标题 17", 17f, it) }
        assertEquals("当前步 600", FontWeight.SemiBold, processing.weight())
        assertColor("当前步主文字色", host.palette.primary, processing.color())
        assertColor("未到步次文字色", host.palette.secondary, result.color())
        val created = host.pageText(RiskVisualFixtures.expect("bannedCreatedText"))
        assertFont("说明 14", 14f, created)
        assertColor("说明次文字色", host.palette.secondary, created.color())
        assertFont("处理中说明 14", 14f, host.pageText(t("appeal.timeline.processing_desc.banned")))
        val px = host.pixels(page)
        val done = stepIcon(submitted, 18f, 22f)
        assertTrue("已提交 = status.success 圆勾", px.countNear(done, host.palette.successText, 12) > 0)
        val current = stepIcon(processing, 18f, 22f)
        assertTrue("当前步 = 主文字色时钟", px.countNear(current, host.palette.primary, 12) > 0)
        val pending = stepIcon(result, 8f, 22f)
        assertTrue("未到 = 10 圆圈 border.control（圆圈可画在 20 的图标列格里）", px.countNear(pending, host.palette.control, 12) > 0)
        assertDp("图标列 → 标题 12", 12f, submitted.textBox().left - done.right, d, 1.5f)
        val line = Rect(done.center.x - 2f * d, done.bottom, done.center.x + 2f * d, current.top)
        assertTrue("步骤间 2px 连线 border.subtle", px.countNear(line, host.palette.subtle, 6) > 0)
        cardOf(submitted, 40f)
        val merged = rule.onRoot().fetchSemanticsNode().pvFlatten()
        assertTrue(
            "每步合并读「标题，说明」",
            merged.any { n ->
                n.spokenParts().contains(t("appeal.timeline.submitted")) &&
                    n.spokenParts().contains(RiskVisualFixtures.expect("bannedCreatedText"))
            },
        )
    }

    @Test fun r18ProgressContentCardHasTypeAndTextButNoReasonRow() {
        host.launchAppeal(risk = "risk_appealing_banned", appeals = "appeals_processing_banned")
        val title = host.pageHeading(t("appeal.content_section"))
        assertCardTitle(title, cardOf(title))
        inline(t("appeal.type"), t("appeal.type.account"))
        inline(t("appeal.content"), RiskVisualFixtures.text("content", RiskVisualFixtures.latestAccount("appeals_processing_banned")!!))
        assertEquals("「原因」行按 G-R11 ⑦ 不加（申诉记录不带原因）", 0, host.count(t("appeal.reason")))
    }

    @Test fun r19ProgressFooterIsCenteredWithA14MediumLink() {
        host.launchAppeal(risk = "risk_appealing_banned", appeals = "appeals_processing_banned")
        val footer = host.pageText(t("appeal.footer"))
        assertFont("底部句 14", 14f, footer)
        assertColor("底部句次文字色", host.palette.secondary, footer.color())
        val link = host.node(MeTags.APPEAL_CS)
        val label = rule.textUnder(MeTags.APPEAL_CS, t("appeal.contact_cs"))
        assertFont("链接 14", 14f, label)
        assertEquals("链接 500", FontWeight.Medium, label.weight())
        assertColor("链接 text.link", host.palette.link, label.color())
        assertAtLeastDp("触摸盒 ≥ 48", 48f, link.touch().height, d)
        assertAtLeastDp("语义盒 ≥ 48", 48f, link.box().height, d)
        val left = footer.glyphBox().left
        val right = label.glyphBox().right
        assertDp("整句居中", page.box().center.x / d, (left + right) / 2f / d, 1f, 3f)
    }

    // ---------------------------------------------------------------- 结果 · 已解除（R20 / R21，G-R10 / G-R12 / G-R13）

    @Test fun r20LiftedHeaderIsASuccessBlockWithCheck() {
        host.launchAppeal(risk = "risk_normal", appeals = "appeals_revoked_banned", entered10006 = LIFTED_BOARD_ENTERED_10006)
        val title = host.pageHeading(t("appeal.lifted.title.banned"))
        assertFont("标题 22", 22f, title)
        val area = Rect(0f, title.box().top - 140f * d, page.box().right, title.box().top)
        val px = host.pixels(page)
        val block = assertBox("图标块 status.success", px.pvColorBox(area, host.palette.successBackground, 2))
        assertDp("图标块 96", 96f, block.width, d)
        val icons = page.iconsIn(block, 28f, 36f)
        assertEquals("圆勾 32", 1, icons.size)
        assertTrue(px.countNear(icons.single(), host.palette.successText, 12) > 0)
        assertDp("图标块 → 标题 16", 16f, title.textBox().top - block.bottom, d, 1.5f)
    }

    @Test fun r20LiftedCardHasAPassedPillAndTheNoteIsUnchanged() {
        host.launchAppeal(risk = "risk_normal", appeals = "appeals_revoked_banned", entered10006 = LIFTED_BOARD_ENTERED_10006)
        pill(t("appeal.passed"), host.palette.successBackground, host.palette.successText, icon = true)
        inline(t("appeal.closed_at"), RiskVisualFixtures.expect("bannedClosedText"), twoEnds = true)
        assertEquals("G-R10：解封提示句不改、不加条件（FU-APPEAL-REAUTH）", 1, host.count(RiskVisualFixtures.kept("appeal.lifted.note.banned")))
        assertEquals("G-R11 ⑧：不加「提现 · 已恢复」", 0, host.count(t("appeal.withdraw_ok")))
    }

    @Test fun r20LiftedFirstActionIsPrimaryAndOthersAreTextLinks() {
        host.launchAppeal(risk = "risk_normal", appeals = "appeals_revoked_banned", entered10006 = LIFTED_BOARD_ENTERED_10006)
        val start = host.node(MeTags.APPEAL_ACTION_PREFIX + "start")
        val messages = host.node(MeTags.APPEAL_ACTION_PREFIX + "view_messages")
        val px = host.pixels(page)
        assertTrue("【开始使用】主按钮（品牌填充）", px.countNear(start.box(), host.palette.buttonPrimary, 4) > 0)
        assertEquals("「查看消息」是文字链接：无品牌填充", 0, px.countNear(messages.box(), host.palette.buttonPrimary, 4))
        assertEquals("「查看消息」无控件边", 0, px.countNear(messages.box(), host.palette.control, 6))
        val label = rule.textUnder(MeTags.APPEAL_ACTION_PREFIX + "view_messages", t("appeal.view_messages"))
        assertFont("链接 14", 14f, label)
        assertColor("链接 text.link", host.palette.link, label.color())
        assertAtLeastDp("触摸盒 ≥ 48", 48f, messages.touch().height, d)
        assertAtLeastDp("语义盒 ≥ 48", 48f, messages.box().height, d)
        assertTrue("链接在主按钮之下", messages.box().top >= start.box().bottom - 1f)
        assertDp("链接居中", page.box().center.x / d, label.glyphBox().center.x / d, 1f, 2f)
    }

    @Test fun r21ResultAndProgressTitlesAreProgressAndBackIsKept() {
        host.launchAppeal(risk = "risk_normal", appeals = "appeals_revoked_banned", entered10006 = LIFTED_BOARD_ENTERED_10006)
        assertTrue("解除结果标题「申诉进度」（G-R12）", host.count(t("appeal.progress.title")) >= 1)
        assertEquals("不再是「申诉」", 0, host.count(t("appeal.title")))
        val back = rule.onNode(backMatcher(), useUnmergedTree = true)
        host.tap(back)
        assertEquals("返回保留（G-R12 后半不做）", 1, host.backs)
    }

    @Test fun r21SubmitTitleStaysAppeal() {
        host.launchAppeal()
        assertTrue(host.count(t("appeal.title")) >= 1)
    }

    // ---------------------------------------------------------------- 结果 · 维持（R22–R27）

    @Test fun r22UpheldStatusCardHasWarningPillHeadingAndInlineTime() {
        host.launchAppeal(appeals = "appeals_upheld_banned")
        assertTrue("维持结果标题「申诉进度」", host.count(t("appeal.progress.title")) >= 1)
        val heading = host.pageHeading(t("appeal.upheld.title"))
        val card = cardOf(heading)
        assertFont("标题 22", 22f, heading)
        val pill = pill(t("appeal.upheld.badge"), host.palette.warningBackground, host.palette.warningText, icon = true)
        assertTrue("胶囊在状态卡内", card.contains(pill.center))
        inline(t("appeal.closed_at"), RiskVisualFixtures.expect("bannedClosedText"))
    }

    @Test fun r23UpheldTimelineEndsWithAWarningStep() {
        host.launchAppeal(appeals = "appeals_upheld_banned")
        val px = host.pixels(page)
        val submitted = host.pageText(t("appeal.timeline.submitted"))
        assertTrue("已提交 ✓", px.countNear(stepIcon(submitted, 18f, 22f), host.palette.successText, 12) > 0)
        val processing = host.pageTexts(t("appeal.timeline.processing")).last()
        assertTrue("处理中 ✓", px.countNear(stepIcon(processing, 18f, 22f), host.palette.successText, 12) > 0)
        // 画板 AppealResultUpheld-v2 与 brief-c-android R23「改法」：第 3 步标题「处理结果」17/600 主文字 + 警告三角，
        // 说明 = appeal.upheld.result_line「维持原处理结果 · 时间」14 次文字（v1 把说明句当 600 标题核，测试 v2 按画板改；评审 r1 S2-5）
        val time = RiskVisualFixtures.expect("bannedClosedText")
        val result = host.pageTexts(t("appeal.timeline.result")).last()
        assertFont("结果步标题 17", 17f, result)
        assertEquals("结果步 600", FontWeight.SemiBold, result.weight())
        assertColor("结果步标题主文字色", host.palette.primary, result.color())
        assertTrue("结果步 = 警告三角", px.countNear(stepIcon(result, 18f, 22f), host.palette.warningText, 12) > 0)
        val line = host.pageText(AppMeTexts.format("appeal.upheld.result_line", mapOf("time" to time)))
        assertFont("结果步说明 14", 14f, line)
        assertColor("结果步说明次文字色", host.palette.secondary, line.color())
        assertTrue("说明在结果步标题之下", line.textBox().top >= result.textBox().bottom - 1f)
        assertEquals("已提交时间取 created_at", 1, host.count(RiskVisualFixtures.expect("bannedCreatedText")))
    }

    @Test fun r24BannedCurrentStateCardReadsOnlyThisGetMe() {
        host.launchAppeal(appeals = "appeals_upheld_banned")
        val title = host.pageHeading(t("appeal.current_account_state"))
        assertCardTitle(title, cardOf(title))
        inline(t("appeal.state"), t("risk.banned.title"), pillValue = true)
        pill(t("risk.banned.title"), host.palette.errorBackground, host.palette.errorText, icon = true)
        inline(t("appeal.reason"), t("risk_reason.malicious_rights"))
        inline(t("risk.limit"), t("risk.limit.long"))
        assertEquals("封禁无预计恢复", 0, host.count(t("appeal.frozen_until")))
    }

    @Test fun r24FrozenCurrentStateCardShowsUntilNotLimit() {
        host.launchAppeal(risk = "risk_frozen", appeals = "appeals_upheld_frozen")
        val title = host.pageHeading(t("appeal.current_frozen_state"))
        assertCardTitle(title, cardOf(title))
        pill(t("risk.frozen.title"), host.palette.warningBackground, host.palette.warningText, icon = true)
        inline(t("appeal.reason"), t("risk_reason.abnormal_trade"))
        inline(t("appeal.frozen_until"), RiskVisualFixtures.expect("frozenUntilText"))
        assertEquals("冻结无限制期限", 0, host.count(t("risk.limit")))
    }

    @Test fun r25BannedNextStepsAreABulletCard() {
        host.launchAppeal(appeals = "appeals_upheld_banned", public = true)
        next(
            listOf(
                t("appeal.next.again"),
                t("appeal.next.cs"),
                RiskVisualFixtures.draft("appeal.next.logout.with_deletion"),
            ),
        )
    }

    @Test fun r25FrozenNextStepsPutTheFrozenLineFirst() {
        host.launchAppeal(risk = "risk_frozen", appeals = "appeals_upheld_frozen")
        next(listOf(t("appeal.next.frozen"), t("appeal.next.again"), t("appeal.next.cs")))
        assertEquals("冻结组无退出句", 0, host.count(t("appeal.next.logout")))
    }

    @Test fun r26UpheldHasTheContentCard() {
        host.launchAppeal(appeals = "appeals_upheld_banned")
        val title = host.pageHeading(t("appeal.content_section"))
        assertCardTitle(title, cardOf(title))
        inline(t("appeal.content"), RiskVisualFixtures.text("content", RiskVisualFixtures.latestAccount("appeals_upheld_banned")!!))
    }

    @Test fun r27UpheldAgainIsASecondaryButtonInTheWhiteBarWithTheExitRow() {
        host.launchAppeal(appeals = "appeals_upheld_banned", public = true)
        val again = host.node(MeTags.APPEAL_AGAIN)
        val px = host.pixels(page)
        assertTrue("【再次申诉】在白底固定栏", sameColor(px.at(4f * d, again.box().center.y), host.palette.surface, 2))
        assertEquals("次按钮：无品牌填充", 0, px.countNear(again.box(), host.palette.buttonPrimary, 4))
        assertTrue("次按钮：有控件边", px.countNear(again.box(), host.palette.control, 12) > 0)
        val links = listOf(MeTags.APPEAL_CS, AccountTags.APPEAL_DELETE, MeTags.APPEAL_LOGOUT).map { host.node(it).box() }
        assertTrue("出口行 客服 | 注销 | 退出", links[0].right <= links[1].left && links[1].right <= links[2].left)
        assertTrue("出口行在【再次申诉】之下", links.all { it.top >= again.box().bottom - 1f })
        assertTrue("出口行也在白底栏", sameColor(px.at(4f * d, links[0].center.y), host.palette.surface, 2))
        listOf(MeTags.APPEAL_CS, MeTags.APPEAL_LOGOUT).forEach { tag ->
            val label = rule.textUnder(tag, if (tag == MeTags.APPEAL_CS) t("appeal.contact_cs") else t("risk.logout"))
            assertFont("链接 14", 14f, label)
            assertColor("链接 text.link", host.palette.link, label.color())
        }
    }

    @Test fun r27FrozenUpheldBarHasOnlyContactCs() {
        host.launchAppeal(risk = "risk_frozen", appeals = "appeals_upheld_frozen")
        assertTrue(host.has(MeTags.APPEAL_CS))
        assertFalse("冻结组无退出 / 注销", host.has(MeTags.APPEAL_LOGOUT) || host.has(AccountTags.APPEAL_DELETE))
        val px = host.pixels(page)
        assertTrue(
            "【再次申诉】在白底固定栏",
            sameColor(
                px.at(
                    4f * d,
                    host
                        .node(MeTags.APPEAL_AGAIN)
                        .box()
                        .center.y,
                ),
                host.palette.surface,
                2,
            ),
        )
    }

    // ---------------------------------------------------------------- G-R09 / G-R17

    @Test fun gr09UpheldNextAgainUsesTheFinalDraft() {
        host.launchAppeal(appeals = "appeals_upheld_banned")
        assertEquals(1, host.count(RiskVisualFixtures.draft("appeal.next.again")))
        val spoken =
            rule
                .onRoot()
                .fetchSemanticsNode()
                .pvFlatten()
                .spokenAll()
        RiskVisualFixtures.retired.forEach { phrase -> assertTrue("旧句「$phrase」不再出现", spoken.none { it.contains(phrase) }) }
    }

    @Test fun gr17InternalUpheldNextStepDropsDeletion() {
        host.launchAppeal(appeals = "appeals_upheld_banned", public = false)
        assertEquals("内测版第 3 条只留退出", 1, host.count(RiskVisualFixtures.kept("appeal.next.logout")))
        assertEquals(0, host.count(RiskVisualFixtures.draft("appeal.next.logout.with_deletion")))
        assertFalse(host.has(AccountTags.APPEAL_DELETE))
    }

    @Test fun gr17PublicUpheldNextStepMentionsDeletionWithTheLink() {
        host.launchAppeal(appeals = "appeals_upheld_banned", public = true)
        assertEquals("公开版第 3 条含注销（与注销链接同判据）", 1, host.count(RiskVisualFixtures.draft("appeal.next.logout.with_deletion")))
        assertTrue(host.has(AccountTags.APPEAL_DELETE))
    }

    // ---------------------------------------------------------------- 深色

    @Test fun darkSubmitUsesDarkSurfaceForCardsInputAndBar() {
        host.launchAppeal(dark = true)
        assertFalse("深色 surface 不是浅色白", host.palette.surface == Color.White)
        cardOf(host.pageHeading(t("appeal.target")))
        val placeholder = host.pageText(RiskVisualFixtures.draft("appeal.placeholder"))
        assertColor("深色占位次文字色", host.palette.secondary, placeholder.color())
        val px = host.pixels(page)
        assertTrue(
            "深色底栏",
            sameColor(
                px.at(
                    4f * d,
                    host
                        .node(MeTags.APPEAL_SUBMIT)
                        .box()
                        .center.y,
                ),
                host.palette.surface,
                2,
            ),
        )
    }

    // ---------------------------------------------------------------- 辅助

    /**
     * 文字所在白卡的外框（测试 v2 T1）。v1 用一个取样点（文字左缘外 8dp）同时扫四边：上 / 下缘落在 16 圆角的圆弧里（卡顶量低约 2.1dp），
     * 右缘从文字前向右扫、碰到字形就停（卡右缘 ≈ 文字左缘）。v2 分开取：
     * ① 左缘：仍从文字左缘外 [inset] 向左扫（这一行在圆角之下，左缘可信；不在白卡里时为 null，底版卡外 GroupLabel 照旧红）；
     * ② 上 / 下缘：取样列在卡左缘内 [RISK_ROW_SAMPLE_DP]（避开圆弧，误差 < 0.2dp；仍在 16 内边距里，不碰内容）；
     * ③ 右缘：同一行从窗口右缘向左第一个 surface 像素（卡外是 canvas / 阴影，不是 surface），量真实外框，不用页宽 − 16 推算。
     */
    private fun cardOf(
        text: SemanticsNodeInteraction,
        inset: Float = 8f,
    ): Rect {
        val px = host.pixels(page)
        val surface = host.palette.surface
        val y = text.textBox().center.y
        val row = assertBox("文字在白卡里（底版卡外 GroupLabel 为 null）", px.cardOf(text, surface, inset))
        val column = assertBox("卡片取样列（卡左缘内 14dp）", px.regionAround(row.left + RISK_ROW_SAMPLE_DP * d, y, surface))
        val right = px.firstFromRight(y, surface)
        assertTrue("卡右缘在文字右侧", right > text.textBox().right)
        return Rect(row.left, column.top, right, column.bottom)
    }

    private fun assertCardTitle(
        title: SemanticsNodeInteraction,
        card: Rect,
    ) {
        assertFont("卡内标题 17", 17f, title)
        assertEquals("卡内标题 600", FontWeight.SemiBold, title.weight())
        assertColor("卡内标题主文字色", host.palette.primary, title.color())
        assertDp("卡内左 16", 16f, title.textBox().left - card.left, d)
        assertDp("卡内上 16", 16f, title.textBox().top - card.top, d, 1.5f)
    }

    /**
     * 行内「标签 值」：同一行、值紧跟标签 8（[twoEnds] = 信息卡两端对齐写法，只核同行与字号）。
     * [pillValue] = 值位是状态胶囊（R12「当前状态为胶囊」、R24「状态 = 胶囊」）：值文字按胶囊字 12 核（胶囊底色 / 高 / 图标由 [pill] 核），
     * 不再与「值 14」同时核同一个节点（测试 v2 T3）。
     */
    private fun inline(
        label: String,
        value: String,
        twoEnds: Boolean = false,
        pillValue: Boolean = false,
    ) {
        val l = host.pageTexts(label).first()
        val v = host.pageTexts(value).first()
        assertFont("标签 14：$label", 14f, l)
        assertColor("标签次文字色：$label", host.palette.secondary, l.color())
        if (pillValue) assertFont("值位胶囊字 12：$value", 12f, v) else assertFont("值 14：$value", 14f, v)
        // 首行对齐（画板 grid 行顶对齐：多行值时标签与值的第一行对齐，不与整段竖直居中；评审 r1 S2-4，测试 v2）
        assertDp("与值首行同一行：$label", firstLineCenter(l) / d, firstLineCenter(v) / d, 1f, 2f)
        if (!twoEnds) {
            val gap = v.textBox().left - l.textBox().right
            assertTrue("值紧跟标签（左对齐，不是两端对齐）：$label 间距 ${gap / d}", gap / d in 6f..40f)
        }
    }

    /** 文字第一行的竖直中线（root 坐标）：单行时 = 文字盒中线，多行时只看首行。 */
    private fun firstLineCenter(text: SemanticsNodeInteraction): Float {
        val layout = text.textLayout()
        return text.textBox().top + (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f
    }

    private fun pill(
        value: String,
        bg: Color,
        fg: Color,
        icon: Boolean,
    ): Rect = pill(host.pageTexts(value).first(), bg, fg, icon)

    private fun pill(
        text: SemanticsNodeInteraction,
        bg: Color,
        fg: Color,
        icon: Boolean,
    ): Rect {
        val px = host.pixels(page)
        val box = assertBox("胶囊底色", px.tintAround(text, bg))
        assertTrue("胶囊包住文字", box.contains(text.textBox().center))
        assertDp("胶囊高 22", 22f, box.height, d, 1.5f)
        assertFont("胶囊字 12", 12f, text)
        assertColor("胶囊字色", fg, text.color())
        val icons = page.iconsIn(box, 12f, 16f)
        assertEquals("胶囊图标 14：${if (icon) "有" else "无"}", if (icon) 1 else 0, icons.size)
        if (icon) assertTrue("图标同色", px.countNear(icons.single(), fg, 12) > 0)
        listOf(host.palette.errorBackground, host.palette.warningBackground, host.palette.successBackground, host.palette.pendingBackground)
            .filter { it != bg }
            .forEach { other -> assertNull("先正后反：别的胶囊底色不出现在这枚胶囊处", px.tintAround(text, other, 4f, 4f)) }
        return box
    }

    /** 步骤标题左侧图标列里、边长在 [min, max] 的图标（量真实布局叶子）。 */
    private fun stepIcon(
        title: SemanticsNodeInteraction,
        min: Float,
        max: Float,
    ): Rect {
        val box = title.textBox()
        val area = Rect(0f, box.top - 6f * d, box.left, box.bottom + 6f * d)
        val icons = page.iconsIn(area, min, max)
        assertEquals("步骤图标（${min.toInt()}–${max.toInt()}）", 1, icons.size)
        return icons.single()
    }

    private fun next(lines: List<String>) {
        val title = host.pageHeading(t("appeal.next"))
        val card = cardOf(title)
        assertCardTitle(title, card)
        val nodes = lines.map { line -> host.pageText(line) }
        val tops = nodes.map { it.textBox().top }
        assertEquals("项目符号逐条成行、顺序按画板", tops.sorted(), tops)
        nodes.forEach { node ->
            assertFont("条目 14", 14f, node)
            assertTrue("条目在卡内", card.contains(node.textBox().center))
            val dot = Rect(card.left, node.textBox().top, node.textBox().left, node.textBox().top + 20f * d)
            assertTrue("条目前有圆点（次文字色）", host.pixels(page).countNear(dot, host.palette.secondary, 12) > 0)
        }
        assertFalse("不再是提示条拼接", host.count(lines.joinToString("\n")) > 0)
    }

    private fun backMatcher() =
        androidx.compose.ui.test
            .hasClickAction() and
            androidx.compose.ui.test
                .hasAnyDescendant(
                    androidx.compose.ui.test
                        .hasContentDescription(t("me.back")),
                )

    private companion object {
        /** fixture 画板 `AppealResultLifted-v2` 登记的进入标记（G10：normal 且非 10006 进入走冻结组，只有【去钱包】）；测试 v2 T2。 */
        const val LIFTED_BOARD_ENTERED_10006 = true
    }
}
