@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText as textMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.me.box
import com.couli.app.me.density
import com.couli.app.me.glyphBox
import com.couli.app.me.iconBoxes
import com.couli.app.me.textBox
import com.couli.app.me.textStyle
import com.couli.feature.me.AccountTags
import com.couli.feature.me.MeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-13v-a 注销待确认一族与放弃确认框、轻提示（brief-android D14 / D15 / D20；G-D13 / G-D18）。画板 DeleteAccountPendingConfirm-v2 1463 高。
 * 待确认卡：白卡 → 警告块（三角 20、标题 14/600 + 说明 14，警告字色，assertive 一次）→ 灰底明细（muted、圆角 12、内边距 12）→ 两等宽按钮 →
 * 在途状态行（只读 pending.inFlight，polite）→ 提示警告块 → 卡底注释 12。回调与 busy 判定原样（由 AccountVisualRedLineTest 守）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1463dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class AccountVisualPendingTest : AccountVisualDeletionHarness() {
    // ---------------------------------------------------------------- D14 待确认卡

    @Test fun d14CardIsWhiteNotWarningFilled() {
        enterPending()
        val card = boxOf(AccountTags.PENDING_CARD)
        val px = pixels()
        val d = pageDensity()
        assertTrue("D14 白卡（左缘内 14dp 取样为 surface，现为整卡警告底）", px.cardColorIs(card, palette.surface, d))
        assertFalse("不是整卡 status.warning 底", px.cardColorIs(card, palette.warningBackground, d))
    }

    @Test fun d14WarningBlockHasIconTitleAndDesc() {
        enterPending()
        val title = textUnder(AccountTags.PENDING_CARD, t("pending_confirm.title"))
        val desc = textUnder(AccountTags.PENDING_CARD, t("pending_confirm.deletion.desc"))
        assertFont("标题 14", 14f, title)
        assertWeight("标题 600", 600, title)
        assertColor("标题警告字色", palette.warningText, title.textStyle().color)
        assertFont("说明 14", 14f, desc)
        assertColor("说明警告字色", palette.warningText, desc.textStyle().color)
        val block = assertBox("警告块（status.warning 底）", pixels().blockOf(desc, palette.warningBackground))
        assertTrue("标题与说明在同一警告块里", block.contains(title.textBox().center))
        assertEquals("块内一个 20 三角", 1, iconsIn(block, 18f, 22f).size)
        val card = boxOf(AccountTags.PENDING_CARD)
        assertTrue("警告块在卡内、卡比块宽（白卡包着警告块）", block.width < card.width - 8f * pageDensity())
    }

    @Test fun d14AlertIsOneAssertiveNodeWithTitleAndDesc() {
        enterPending()
        val assertive =
            composeRule
                .onAllNodes(
                    SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive) and
                        hasAnyAncestor(hasTestTag(AccountTags.PENDING_CARD)),
                    useUnmergedTree = false,
                ).fetchSemanticsNodes()
        assertEquals("警告块 assertive 恰一个（只播一次）", 1, assertive.size)
        val spoken =
            assertive
                .single()
                .config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .joinToString { it.text }
        assertTrue("合并读标题 + 说明：$spoken", spoken.contains(t("pending_confirm.title")) && spoken.contains(t("pending_confirm.deletion.desc")))
    }

    @Test fun d14DetailsSitInAMutedBlockLabelLeftValueRight() {
        enterPending()
        val label = textUnder(AccountTags.PENDING_CARD, t(SUMMARY_OPERATION))
        val value = textUnder(AccountTags.PENDING_CARD, t("pending_confirm.summary.operation.deletion"))
        val block = assertBox("D14 灰底明细（muted）", pixels().blockOf(label, palette.muted))
        val d = pageDensity()
        assertDpNear("明细内边距 12", 12f, label.textBox().left - block.left, d, 1.5f)
        assertDpNear("值右对齐（块右内 12）", 12f, block.right - value.textBox().right, d, 2f)
        assertColor("标签次文字", palette.secondary, label.textStyle().color)
        assertColor("值主文字", palette.primary, value.textStyle().color)
        assertTrue(
            "先正：三行明细都在（deletionSummary 原样）",
            hasText(t("pending_confirm.summary.waiver")) && hasText(t("pending_confirm.summary.submitted_at")),
        )
    }

    @Test fun d14TwoEqualButtonsAndFooterNote() {
        enterPending()
        val abandon = boxOf(AccountTags.PENDING_ABANDON)
        val confirm = boxOf(AccountTags.PENDING_CONFIRM)
        val d = pageDensity()
        assertEquals("两按钮等宽", abandon.width / d, confirm.width / d, 1f)
        assertTrue("放弃在左、确认在右", abandon.right <= confirm.left)
        assertTrue("按钮高 ≥48", abandon.height / d >= 47.5f && confirm.height / d >= 47.5f)
        val note = textUnder(AccountTags.PENDING_CARD, t("deletion.pending_card_hint"))
        assertFont("卡底注释 12", 12f, note)
        assertColor("注释次文字", palette.secondary, note.textStyle().color)
        assertTrue("注释在按钮下", note.textBox().top >= confirm.bottom)
    }

    @Test fun d14PendingCardSitsAboveFundsAndHintSwitches() {
        enterPending()
        assertTrue("待确认卡在资金卡之前", boxOf(AccountTags.PENDING_CARD).bottom <= boxOf(AccountTags.DELETE_FUNDS).top)
        assertTrue("底栏提示换 pending_hint（原样）", hasText(t("deletion.pending_hint")))
        assertFalse("申请置灰（原样）", enabled(AccountTags.DELETE_SUBMIT))
    }

    // ---------------------------------------------------------------- G-D13 在途状态行

    @Test fun gd13InFlightShowsAStatusRowAndDisablesBothButtons() {
        enterPending()
        assertEquals("先反：不在途时没有「正在处理」行", 0, busyRows())
        val (arrived, release) = hold("POST", DELETION) { envelope(deletionFixture("cooling")) }
        try {
            tap(AccountTags.PENDING_CONFIRM)
            assertTrue("确认已到服务端", arrived.await(WAIT_S, java.util.concurrent.TimeUnit.SECONDS))
            settle()
            assertFalse("在途两按钮置灰（原样）", enabled(AccountTags.PENDING_CONFIRM))
            assertFalse(enabled(AccountTags.PENDING_ABANDON))
            assertEquals("G-D13 在途状态行恰一个", 1, busyRows())
            val row = textUnder(AccountTags.PENDING_CARD, t("error.40901"))
            assertColor("状态行次文字", palette.secondary, row.textStyle().color)
            assertFont("状态行 14", 14f, row)
            val polite =
                composeRule
                    .onAllNodes(
                        SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite) and
                            hasAnyAncestor(hasTestTag(AccountTags.PENDING_CARD)) and
                            androidx.compose.ui.test
                                .hasAnyDescendant(textMatcher(t("error.40901"))),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
            assertTrue("状态行 polite（role=status）", polite.isNotEmpty())
            val spinner =
                node(
                    AccountTags.PENDING_CARD,
                ).iconBoxes(14f, 18f).filter { it.center.y in row.textBox().top..row.textBox().bottom }
            assertEquals("转圈 16 在文字左侧", 1, spinner.size)
        } finally {
            release.countDown()
        }
        idleUntil("确认结算") { has(AccountTags.DELETE_COOLING) }
    }

    @Test fun gd13RateLimitGreysButtonsWithoutTheStatusRow() {
        enterPending()
        enqueueCode("POST", DELETION, 42901, headers = mapOf("Retry-After" to "30"))
        tap(AccountTags.PENDING_CONFIRM)
        idleUntil { hasText(t("pending_confirm.retry_later")) }
        assertFalse("限流两按钮置灰（busy 原样）", enabled(AccountTags.PENDING_CONFIRM))
        assertEquals("限流置灰时不显示「正在处理」（只读 inFlight）", 0, busyRows())
    }

    @Test fun pendingStillNoticeIsAWarningBlockInsideTheWhiteCard() {
        enterPending()
        enqueueCode("POST", DELETION, 40901)
        tap(AccountTags.PENDING_CONFIRM)
        idleUntil { hasText(t("pending_confirm.retry_later")) }
        val notice = textUnder(AccountTags.PENDING_CARD, t("pending_confirm.retry_later"))
        val block = assertBox("提示警告块", pixels().blockOf(notice, palette.warningBackground))
        assertEquals("块内一个 20 三角", 1, iconsIn(block, 18f, 22f).size)
        assertTrue("提示块在按钮之下", block.top >= boxOf(AccountTags.PENDING_CONFIRM).bottom)
        assertTrue("白卡", pixels().cardColorIs(boxOf(AccountTags.PENDING_CARD), palette.surface, pageDensity()))
    }

    @Test fun pendingStatesAbandonBusyIsAWarningBlock() {
        enterPending()
        enqueueCode("POST", ABANDON, 40901)
        tap(AccountTags.PENDING_ABANDON)
        idleUntil { has(AccountTags.ABANDON_OK) }
        tap(AccountTags.ABANDON_OK)
        idleUntil { hasText(t("pending_confirm.abandon_busy")) }
        val notice = textUnder(AccountTags.PENDING_CARD, t("pending_confirm.abandon_busy"))
        assertBox("40901 放弃忙碌：警告块", pixels().blockOf(notice, palette.warningBackground))
        assertTrue("保持待确认（原样）", has(AccountTags.PENDING_CARD))
    }

    // ---------------------------------------------------------------- D15 放弃确认框

    @Test fun d15AbandonDialogCentersTheTitle() {
        enterPending()
        tap(AccountTags.PENDING_ABANDON)
        idleUntil("放弃二次确认") { has(AccountTags.ABANDON_OK) }
        val pane = SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)
        val dialog = composeRule.onNode(pane, useUnmergedTree = true)
        val title = composeRule.onNode(textMatcher(t("pending_confirm.abandon")) and hasAnyAncestor(pane), useUnmergedTree = true)
        val box = dialog.box()
        val glyph = title.glyphBox()
        val d = title.density()
        assertEquals("D15 / G-D18 标题居中（字形中线 = 弹窗中线）", box.center.x / d, glyph.center.x / d, 2f)
        assertTrue("先正：正文、明细、注释原样", hasText(t("pending_confirm.abandon.confirm")) && hasText(t("pending_confirm.abandon.note.deletion")))
        assertTrue(hasText(t(SUMMARY_OPERATION)))
    }

    // ---------------------------------------------------------------- D20 轻提示

    @Test fun d20AbandonedToastCarriesACheck() {
        enterPending()
        tap(AccountTags.PENDING_ABANDON)
        idleUntil { has(AccountTags.ABANDON_OK) }
        tap(AccountTags.ABANDON_OK)
        idleUntil("已放弃轻提示") { has(MeTags.TOAST) }
        assertTrue("轻提示文案 pending_confirm.abandoned（原样）", hasText(t("pending_confirm.abandoned")))
        assertEquals("G-S06 成功类带对勾", 1, node(MeTags.TOAST).iconBoxes(16f, 24f).size)
        assertFalse("恢复初始态（原样）", has(AccountTags.PENDING_CARD))
        assertTrue("勾选重置为未勾选（原样）", !enabled(AccountTags.DELETE_SUBMIT))
    }

    @Test fun d20AlreadyDoneToastCarriesACheck() {
        val serial = enterPendingWithCompletedOriginal()
        idleUntil("已处理轻提示") { has(MeTags.TOAST) }
        assertTrue(hasText(t("pending_confirm.already_done")))
        assertEquals("G-S06 成功类带对勾", 1, node(MeTags.TOAST).iconBoxes(16f, 24f).size)
        assertTrue("冷静期（原样）", has(AccountTags.DELETE_COOLING))
        assertTrue("键结束", records().none { it.key == serial })
    }

    private fun enterPendingWithCompletedOriginal(): String {
        val serial = enterPending()
        serverState.results[serial] = 0 to deletionFixture("completed_after_abandon")
        serverState.deletion = deletionFixture("completed_after_abandon")
        tap(AccountTags.PENDING_ABANDON)
        idleUntil { has(AccountTags.ABANDON_OK) }
        tap(AccountTags.ABANDON_OK)
        return serial
    }

    private fun busyRows(): Int = countUnder(AccountTags.PENDING_CARD, textMatcher(t("error.40901")))
}
