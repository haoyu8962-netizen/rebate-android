package com.couli.app.me

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliFormat
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
 * 账号状态 RiskNotice 的版式（brief-c-android R1–R10，G-R04 / G-R06 / G-R07 / G-R08 / G-R09 / G-R17）。底版上视觉断言应红（见交回 §5 预期红清单）。
 * 量外框不量内容盒；卡片取样列退到行左缘内 14dp；浅色 muted 与 canvas 只差 3–4，muted 取色容差 1。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class RiskVisualRiskNoticeTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { RiskVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    private fun t(key: String) = AppMeTexts.text(key)

    private val page get() = host.node(MeTags.RISK_PAGE)
    private val d get() = page.density()

    // ---------------------------------------------------------------- R1 边距与块间

    @Test fun r1ContentStartsAt24SidesAre16AndBlocksAre24Apart() {
        host.launchRisk()
        val scrollTop = rule.scrollUnder(MeTags.RISK_PAGE).box().top
        val block = headerBlock()
        assertDp("内容上边距 24（图标块外框顶到滚动区顶）", 24f, block.top - scrollTop, d)
        val desc = host.pageText(t("risk.banned.desc")).textBox()
        val card = infoCard(MeTags.RISK_REASON)
        assertDp("头部 → 信息卡 24", 24f, card.top - desc.bottom, d)
        assertDp("信息卡左距 16", 16f, card.left, d)
        val note = assertBox("提示条", host.pixels(page).tintAround(host.pageText(t("risk.banned.note")), host.palette.muted, 60f, 40f, 1))
        assertDp("信息卡 → 提示条 24", 24f, note.top - card.bottom, d)
        assertDp("提示条左距 16", 16f, note.left, d)
    }

    // ---------------------------------------------------------------- R2 头部（G-R06）

    @Test fun r2BannedHeaderIs96BlockWith32IconAnd22Title() = header(banned = true)

    @Test fun r2FrozenHeaderIs96BlockWith32IconAnd22Title() = header(banned = false)

    @Test fun r2NormalHeaderFollowsTheSameSizes() {
        host.launchRisk(risk = "risk_normal", withdrawals = "withdrawals_none")
        assertEquals(t("risk.normal.title"), titleText())
        val block = headerBlock()
        assertDp("normal 图标块同为 96（无稿，只随尺寸）", 96f, block.width, d)
        // R10：normal 头部「只随 R2 改尺寸，图标与色维持现状」= 改前 else 分支的警告三角（CouliIcons.alert）、次文字色，不是封禁的锁（评审 r1 S2-1，测试 v2 补钉）
        val icons = page.iconsIn(block, 28f, 36f)
        assertEquals("normal 块内一个 32 图标", 1, icons.size)
        assertTrue("normal 图标次文字色（维持现状）", host.pixels(page).countNear(icons.single(), host.palette.secondary, 12) > 0)
        assertTrue("normal 图标维持现状 = 警告三角（不是锁 / 暂停圆）", isTriangle(icons.single()))
        assertFont("标题 22", TITLE_SP, host.node(MeTags.RISK_TITLE))
        assertFalse("normal 无信息卡与出口", host.has(MeTags.RISK_REASON) || host.has(MeTags.RISK_CS))
        assertEquals(1, host.count(t("risk.normal.back_home")))
    }

    // ---------------------------------------------------------------- R3 信息卡与行

    @Test fun r3InfoCardIsInset4x16AndRowsAre14Over14Medium() {
        host.launchRisk()
        val reason = host.node(MeTags.RISK_REASON).box()
        val card = infoCard(MeTags.RISK_REASON)
        assertDp("卡内上 4", 4f, reason.top - card.top, d)
        val last = host.node(MeTags.RISK_WITHDRAWALS).box()
        assertDp("卡内下 4", 4f, card.bottom - last.bottom, d)
        listOf(MeTags.RISK_REASON, MeTags.RISK_LIMIT, MeTags.RISK_WITHDRAWALS).forEach { tag ->
            val row = host.node(tag).box()
            assertDp("$tag 行高 48（min 44 视觉 + 48dp 命中区）", 48f, row.height, d)
            val label = rule.textUnder(tag, labelOf(tag))
            assertFont("$tag 标签 14", 14f, label)
            assertColor("$tag 标签次文字色", host.palette.secondary, label.color())
            assertDp("$tag 标签左距卡内 16", 16f, label.textBox().left - card.left, d)
        }
        val value = rule.textUnder(MeTags.RISK_LIMIT, t("risk.limit.long"))
        assertFont("值 14", 14f, value)
        assertEquals("值 500", FontWeight.Medium, value.weight())
        assertColor("值主文字色", host.palette.primary, value.color())
        assertDp("值右对齐到卡内 16", 16f, card.right - value.glyphBox().right, d, 1.5f)
    }

    @Test fun r3RowsAreSeparatedBySubtleLinesInsetToThePadding() {
        host.launchRisk()
        val reason = host.node(MeTags.RISK_REASON).box()
        val limit = host.node(MeTags.RISK_LIMIT).box()
        assertTrue("原因在限制期限之上", reason.bottom <= limit.top + 1f)
        val px = host.pixels(page)
        val card = infoCard(MeTags.RISK_REASON)
        val y = (reason.bottom + limit.top) / 2f
        assertTrue("行间 1px border.subtle", sameColor(px.at(card.left + 24f * d, y), host.palette.subtle, 6))
        assertTrue("分隔线不通到卡边（齐卡内边距 16）", sameColor(px.at(card.left + 8f * d, y), host.palette.surface, 2))
    }

    @Test fun r3RowOrderAndVisibilityAreUnchanged() {
        host.launchRisk(appeals = "appeals_upheld_banned")
        val tags = listOf(MeTags.RISK_REASON, MeTags.RISK_LIMIT, MeTags.RISK_UPHELD, MeTags.RISK_WITHDRAWALS)
        val order = tags.map { host.node(it).box().top }
        assertEquals("原因 → 限制期限 → 最近一次申诉 → 进行中的提现", order.sorted(), order)
        assertFalse("封禁不显示预计恢复", host.has(MeTags.RISK_UNTIL))
    }

    // ---------------------------------------------------------------- R4 胶囊（G-R07）

    @Test fun r4AppealingRowValueIsAPendingPillWithClockAndNoChevron() =
        pill("risk_appealing_banned", "appeals_processing_banned", t("risk.appeal.processing"), warning = false)

    @Test fun r4UpheldRowValueIsAWarningPillWithTriangleAndNoChevron() =
        pill("risk_banned", "appeals_upheld_banned", t("risk.upheld"), warning = true)

    @Test fun r4FrozenAppealingAlsoUsesThePendingPill() =
        pill("risk_appealing_frozen", "appeals_processing_frozen", t("risk.appeal.processing"), warning = false)

    // ---------------------------------------------------------------- R5 在途提现（G-R08）

    @Test fun r5WithdrawalRowShowsOnlyNonTerminalTitleWithoutSubline() {
        host.launchRisk()
        assertEquals("只一笔非终态（PAID_API 不显示）", 1, rule.onAllNodesWithTagCount(MeTags.RISK_WITHDRAWALS))
        val amount = CouliFormat.money(RiskVisualFixtures.expectFen("pendingWithdrawalAmount"))
        val terminal = CouliFormat.money(RiskVisualFixtures.expectFen("terminalWithdrawalAmount"))
        assertEquals("终态金额不显示", 0, host.count(terminal))
        val value = rule.textNode(amount, substring = true)
        assertFont("值 14", 14f, value)
        assertEquals("值 500", FontWeight.Medium, value.weight())
        val texts =
            rule
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasAnyAncestor(
                            androidx.compose.ui.test
                                .hasTestTag(MeTags.RISK_WITHDRAWALS),
                        ),
                    true,
                ).fetchSemanticsNodes()
                .count { it.config.contains(SemanticsProperties.Text) }
        assertEquals("副文案本任务不加（G-R08，FU-AND-RISK-WDR）：行内只有标签与值", 2, texts)
    }

    // ---------------------------------------------------------------- R6 提示条

    @Test fun r6NoteStripIsMutedWith20InfoAnd14PrimaryText() {
        host.launchRisk()
        val note = host.pageText(t("risk.banned.note"))
        assertFont("提示条 14", 14f, note)
        assertColor("主文字色", host.palette.primary, note.color())
        val strip = assertBox("muted 底", host.pixels(page).tintAround(note, host.palette.muted, 60f, 40f, 1))
        assertEquals("info 图标 20", 1, page.iconsIn(strip, 19f, 21f).size)
    }

    // ---------------------------------------------------------------- R7 / R8 底部（G-R04）

    @Test fun r7PrimaryIsFullWidthAndTheExitRowSitsBelowIt() {
        host.launchRisk()
        val button = host.node(MeTags.RISK_PRIMARY).box()
        assertDp("主按钮左 16", 16f, button.left, d)
        assertDp("主按钮右 16", 16f, page.box().right - button.right, d)
        val cs = host.node(MeTags.RISK_CS).box()
        assertTrue("出口行在主按钮下", cs.top >= button.bottom)
        assertTrue("底部区不是白底栏", sameColor(host.pixels(page).at(4f * d, button.center.y), host.palette.canvas, 2))
    }

    @Test fun r8ExitRowOrderIsCsDeleteLogoutWith14LinkText() {
        host.launchRisk(public = true)
        exitRow(MeTags.RISK_CS, AccountTags.RISK_DELETE, MeTags.RISK_LOGOUT)
    }

    @Test fun r8InternalBuildExitRowIsCsLogout() {
        host.launchRisk(public = false)
        val cs = host.node(MeTags.RISK_CS).box()
        val logout = host.node(MeTags.RISK_LOGOUT).box()
        assertTrue("客服在退出之前", cs.right <= logout.left)
        assertFalse(host.has(AccountTags.RISK_DELETE))
        assertNoSpokenSeparator()
    }

    @Test fun r8LoadErrorFallbackKeepsTheSameExitOrder() {
        host.api.answers[RiskVisualApi.GET_ME] = RiskVisualFixtures.failure()
        host.launchRisk()
        assertTrue("读取失败：错误态 + 兜底出口", host.has(MeTags.RISK_ERROR))
        exitRow(MeTags.RISK_CS, AccountTags.RISK_DELETE, MeTags.RISK_LOGOUT)
    }

    // ---------------------------------------------------------------- R9 退出确认框

    @Test fun r9LogoutDialogTitleIsCentered() {
        host.launchRisk()
        host.tap(MeTags.RISK_LOGOUT)
        val title = host.dialogText(t("settings.logout.confirm"))
        val window = host.rootOf(title).boundsInRoot
        assertDp("标题居中（G-S07）", 0f, title.glyphBox().center.x - window.center.x, title.density(), 2f)
    }

    // ---------------------------------------------------------------- G-R09 / G-R17 文案

    @Test fun gr09BannedLimitAndUpheldNoteUseTheFinalDrafts() {
        host.launchRisk(appeals = "appeals_upheld_banned")
        assertEquals("限制期限 = 定稿", 1, host.count(RiskVisualFixtures.draft("risk.limit.long")))
        assertEquals("维持后提示条 = 定稿（去掉自拟前提）", 1, host.count(RiskVisualFixtures.draft("risk.banned_upheld.note")))
        assertRetiredPhrasesAbsent()
    }

    @Test fun gr09FrozenWithoutUntilUsesTheFinalNote() {
        host.launchRisk(risk = "risk_frozen_no_until", withdrawals = "withdrawals_none")
        assertFalse("不定期：无预计恢复行", host.has(MeTags.RISK_UNTIL))
        assertEquals(1, host.count(RiskVisualFixtures.draft("risk.frozen.note_no_until")))
    }

    @Test fun gr09FrozenUpheldUsesTheFinalNote() {
        host.launchRisk(risk = "risk_frozen", appeals = "appeals_upheld_frozen", withdrawals = "withdrawals_none")
        assertEquals(1, host.count(RiskVisualFixtures.draft("risk.frozen_upheld.note")))
        assertRetiredPhrasesAbsent()
    }

    @Test fun gr17PublicBuildDescriptionMentionsDeletionAndHasTheLink() {
        host.launchRisk(public = true)
        assertEquals(1, host.count(RiskVisualFixtures.kept("risk.banned.desc")))
        assertTrue(host.has(AccountTags.RISK_DELETE))
    }

    @Test fun gr17InternalBuildDescriptionDropsDeletionTogetherWithTheLink() {
        host.launchRisk(public = false)
        assertEquals("说明句与注销入口同判据（G-R17）", 1, host.count(RiskVisualFixtures.draft("risk.banned.desc.no_deletion")))
        assertEquals("内测版页面不出现「注销账号」", 0, host.count(RiskVisualFixtures.kept("risk.banned.desc")))
        val spoken =
            rule
                .onRoot()
                .fetchSemanticsNode()
                .pvFlatten()
                .spokenAll()
        assertTrue("任何文字都不提注销：$spoken", spoken.none { it.contains(AppMeTexts.text("settings.delete_account")) })
    }

    // ---------------------------------------------------------------- 深色

    @Test fun darkFrozenUsesTheDarkTokens() {
        host.launchRisk(risk = "risk_frozen", withdrawals = "withdrawals_none", dark = true)
        val block = headerBlock()
        assertDp("深色图标块同为 96", 96f, block.width, d)
        val card = infoCard(MeTags.RISK_REASON)
        assertTrue("深色卡片 = 深色 surface", card.width > 0f)
        assertFalse("深色 surface 不是浅色白", host.palette.surface == androidx.compose.ui.graphics.Color.White)
        assertTrue("卡片取样 = 深色 surface", sameColor(host.pixels(page).at(card.left + 4f * d, card.center.y), host.palette.surface, 2))
    }

    // ---------------------------------------------------------------- 辅助

    private fun titleText(): String =
        host
            .node(MeTags.RISK_TITLE)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }

    private fun labelOf(tag: String): String =
        when (tag) {
            MeTags.RISK_REASON -> t("risk.reason")
            MeTags.RISK_LIMIT -> t("risk.limit")
            MeTags.RISK_UNTIL -> t("risk.frozen_until")
            else -> t("risk.withdrawals")
        }

    /** 标题上方 140dp 内的 muted 外接框 = 图标块外框（底版 80，画板 96）。 */
    private fun headerBlock(): Rect {
        val title = host.node(MeTags.RISK_TITLE).box()
        val area = Rect(0f, title.top - 140f * d, page.box().right, title.top)
        return assertBox("图标块（muted）", host.pixels(page).pvColorBox(area, host.palette.muted, 1))
    }

    /**
     * 头部图标是否是警告三角（CouliIcons.alert：三角 M12 4.5 21 19.5H3Z + 中竖「!」）：按 24 视口量墨迹——
     * ① 墨迹外接框宽 / 高 ≥ 1.08（三角 ≈ 1.18；锁 ≈ 0.86；暂停圆 ≈ 1.0）；② 中线 x = 12、y 11–13.5 有墨迹（「!」竖笔；锁体内、暂停圆两竖之间都是空的）。
     */
    private fun isTriangle(icon: Rect): Boolean {
        val px = host.pixels(page)
        val ink = px.ink(icon, host.palette.muted) ?: return false
        val unit = icon.width / ICON_VIEWPORT
        val stem = Rect(icon.left + 11.4f * unit, icon.top + 11f * unit, icon.left + 12.6f * unit, icon.top + 13.5f * unit)
        return ink.width / ink.height >= TRIANGLE_MIN_ASPECT && px.ink(stem, host.palette.muted) != null
    }

    /** 信息卡外框：取样列在 [rowTag] 行左缘内 14dp、行竖直中线（量外框，不量内容盒）。 */
    private fun infoCard(rowTag: String): Rect {
        val row = host.node(rowTag).box()
        val px = host.pixels(page)
        val region = assertBox("信息卡（surface）", px.regionAround(row.left + RISK_ROW_SAMPLE_DP * d, row.center.y, host.palette.surface))
        return Rect(region.left, region.top, row.right, region.bottom)
    }

    private fun header(banned: Boolean) {
        if (banned) {
            host.launchRisk()
        } else {
            host.launchRisk(risk = "risk_frozen", withdrawals = "withdrawals_none")
        }
        val block = headerBlock()
        assertDp("图标块宽 96", 96f, block.width, d)
        assertDp("图标块高 96", 96f, block.height, d)
        assertDp("图标块水平居中", page.box().center.x / d, block.center.x / d, 1f, 1f)
        val px = host.pixels(page)
        assertFalse("圆角 16：外角不是 muted", sameColor(px.at(block.left + 1f * d, block.top + 1f * d), host.palette.muted, 1))
        assertTrue("圆角内是 muted", sameColor(px.at(block.left + 6f * d, block.top + 6f * d), host.palette.muted, 1))
        val icons = page.iconsIn(block, 28f, 36f)
        assertEquals("块内一个 32 图标（G-R06 锁 / 暂停圆）", 1, icons.size)
        assertDp("图标 32", 32f, icons.single().width, d)
        assertTrue("图标次文字色", px.countNear(icons.single(), host.palette.secondary, 12) > 0)
        assertFalse("先正后反：封禁锁 / 冻结暂停圆不被三角量具认成三角", isTriangle(icons.single()))
        val title = host.node(MeTags.RISK_TITLE)
        assertEquals(t(if (banned) "risk.banned.title" else "risk.frozen.title"), titleText())
        assertFont("标题 22（title）", TITLE_SP, title)
        assertEquals("标题 600", FontWeight.SemiBold, title.weight())
        assertTrue("标题是读屏标题", title.fetchSemanticsNode().config.contains(SemanticsProperties.Heading))
        assertDp("图标块 → 标题 16", 16f, title.textBox().top - block.bottom, d, 1.5f)
        val desc = host.pageText(t(if (banned) "risk.banned.desc" else "risk.frozen.desc"))
        assertDp("标题 → 说明 8", 8f, desc.textBox().top - title.textBox().bottom, d, 1.5f)
        assertFont("说明 14", 14f, desc)
        assertColor("说明次文字色", host.palette.secondary, desc.color())
        assertDp("说明居中", page.box().center.x / d, desc.glyphBox().center.x / d, 1f, 2f)
    }

    private fun pill(
        risk: String,
        appeals: String,
        value: String,
        warning: Boolean,
    ) {
        host.launchRisk(risk = risk, appeals = appeals)
        val text = host.pageTexts(value).first()
        val px = host.pixels(page)
        val bg = if (warning) host.palette.warningBackground else host.palette.pendingBackground
        val fg = if (warning) host.palette.warningText else host.palette.pendingText
        val pill = assertBox("胶囊底色（${if (warning) "status.warning" else "status.pending"}）", px.tintAround(text, bg))
        assertTrue("胶囊包住文字", pill.contains(text.textBox().center))
        assertDp("胶囊高 22（min 22、内边距 2/8）", 22f, pill.height, d, 1.5f)
        assertFont("胶囊字 12", 12f, text)
        assertEquals("胶囊字 400", FontWeight.Normal, text.weight() ?: FontWeight.Normal)
        assertColor("胶囊字色", fg, text.color())
        val icons = page.iconsIn(pill, 12f, 16f)
        assertEquals("胶囊里一个 14 图标（时钟 / 警告三角）", 1, icons.size)
        assertTrue("图标同色", px.countNear(icons.single(), fg, 12) > 0)
        val row = Rect(0f, pill.top - 12f * d, page.box().right, pill.bottom + 12f * d)
        assertTrue("不画 chevron（G-R07）", page.iconsIn(row, 18f, 26f).isEmpty())
        val other = if (warning) host.palette.pendingBackground else host.palette.warningBackground
        assertNull("先正后反：另一种胶囊底色不出现", px.tintAround(text, other))
    }

    private fun exitRow(
        cs: String,
        delete: String,
        logout: String,
    ) {
        val links = listOf(cs, delete, logout).map { host.node(it) }
        val boxes = links.map { it.box() }
        assertTrue("顺序：联系客服 | 注销账号 | 退出登录（G-R04）：$boxes", boxes[0].right <= boxes[1].left && boxes[1].right <= boxes[2].left)
        boxes.zipWithNext().forEach { (a, b) -> assertDp("同一行", a.center.y / d, b.center.y / d, 1f, 1f) }
        links.forEachIndexed { i, link ->
            val label = rule.textUnder(listOf(cs, delete, logout)[i], textOf(link))
            assertFont("链接 14", 14f, label)
            assertColor("链接 text.link（不是 brand.primary）", host.palette.link, label.color())
            assertAtLeastDp("触摸盒高 ≥ 48", 48f, link.touch().height, d)
            assertAtLeastDp("触摸盒宽 ≥ 48", 48f, link.touch().width, d)
            assertAtLeastDp("语义盒 ≥ 48", 48f, link.box().height, d)
        }
        links.zipWithNext().forEach { (a, b) -> assertSeparate("相邻链接命中不重叠", a.touch(), b.touch()) }
        val px = host.pixels(page)
        boxes.zipWithNext().forEach { (a, b) ->
            val gap = Rect(a.right - 2f * d, a.center.y - 10f * d, b.left + 2f * d, a.center.y + 10f * d)
            assertTrue("分隔符 border.subtle", px.countNear(gap, host.palette.subtle, 8) > 0)
            assertEquals("分隔符不是 border.control", 0, px.countNear(gap, host.palette.control, 10))
        }
        assertNoSpokenSeparator()
    }

    private fun textOf(link: androidx.compose.ui.test.SemanticsNodeInteraction): String =
        rule
            .onAllNodes(
                androidx.compose.ui.test
                    .hasAnyAncestor(
                        androidx.compose.ui.test
                            .hasTestTag(tagOf(link)),
                    ),
                true,
            ).fetchSemanticsNodes()
            .flatMap { it.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty() }
            .first()
            .text

    private fun tagOf(link: androidx.compose.ui.test.SemanticsNodeInteraction): String =
        link.fetchSemanticsNode().config[SemanticsProperties.TestTag]

    private fun assertNoSpokenSeparator() {
        val spoken =
            rule
                .onRoot()
                .fetchSemanticsNode()
                .pvFlatten()
                .spokenAll()
        assertTrue("先正：出口链接被读出", spoken.contains(t("risk.contact_cs")))
        assertFalse("分隔符「|」不被读出", spoken.any { it.trim() == "|" })
    }

    private fun assertRetiredPhrasesAbsent() {
        val spoken =
            rule
                .onRoot()
                .fetchSemanticsNode()
                .pvFlatten()
                .spokenAll()
        RiskVisualFixtures.retired.forEach { phrase -> assertTrue("旧句「$phrase」不再出现（G-R09）", spoken.none { it.contains(phrase) }) }
    }

    private companion object {
        const val TITLE_SP = 22f
        const val ICON_VIEWPORT = 24f
        const val TRIANGLE_MIN_ASPECT = 1.08f
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
    onAllNodes(
        androidx.compose.ui.test
            .hasTestTag(tag),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().size
