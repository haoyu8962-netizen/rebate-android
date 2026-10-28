@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.me.assertTextFully
import com.couli.app.me.box
import com.couli.app.me.density
import com.couli.app.me.fontScale
import com.couli.app.me.touch
import com.couli.core.designsystem.CouliFormat
import com.couli.feature.me.AccountTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-13v-a 大字号与大屏（brief §4 第 3、6、7 条；§2.1「大字号 200%」「大屏」）：200% × w390 / w320 文字不截断不缩小（裁切判据同时比宽度与位置，
 * APP-11v-b r2）、资金两列可整行换行、命中区 ≥48 且勾选行与【申请注销】不重叠、语义盒 ≥48；840 宽内容限宽 600 居中；底栏随字号增高、内容区可滚。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class AccountVisualAdaptationTest : AccountVisualDeletionHarness() {
    @Test fun font200W390InitialTextIsNotClipped() {
        fontScale = 2f
        enterInitial(checked = true)
        assertEquals("先正：200% 真的放大", 2f, node(AccountTags.DELETE_PAGE).fontScale(), 0.01f)
        fullyVisible(textNode(AccountVisualFixtures.draft("deletion.intro")))
        fullyVisible(textUnder(AccountTags.DELETE_WITHDRAWABLE, CouliFormat.money(1234)))
        fullyVisible(textUnder(AccountTags.DELETE_ESTIMATED, CouliFormat.money(5678)))
        fullyVisible(textNode(t("deletion.withdraw_hint")))
        fullyVisible(textUnder(AccountTags.DELETE_GO_WITHDRAW, t("deletion.go_withdraw")))
        (1..6).forEach { fullyVisible(textUnder(AccountTags.DELETE_NOTICE, t("deletion.notice.$it"))) }
    }

    @Test fun font200W390BottomBarTargetsDoNotOverlap() {
        fontScale = 2f
        enterInitial(checked = true)
        val waiver = node(AccountTags.DELETE_WAIVER)
        val submit = node(AccountTags.DELETE_SUBMIT)
        val d = waiver.density()
        assertTrue("勾选行命中 ≥48", waiver.touch().height / d >= 47.5f)
        assertTrue("申请命中 ≥48", submit.touch().height / d >= 47.5f)
        assertSeparateTargets("勾选行与【申请注销】", waiver.touch(), submit.touch())
        textUnder(AccountTags.DELETE_WAIVER, t("deletion.waiver")).assertTextFully()
        textNode(t("deletion.submit_hint")).assertTextFully()
        assertTrue("底栏在屏内", submit.box().bottom <= node(AccountTags.DELETE_PAGE).box().bottom + 1f)
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200W320FundsColumnsWrapWithoutClipping() {
        fontScale = 2f
        enterInitial()
        val w = textUnder(AccountTags.DELETE_WITHDRAWABLE, CouliFormat.money(1234))
        val e = textUnder(AccountTags.DELETE_ESTIMATED, CouliFormat.money(5678))
        fullyVisible(w)
        fullyVisible(e)
        val wb = boxOf(AccountTags.DELETE_WITHDRAWABLE)
        val eb = boxOf(AccountTags.DELETE_ESTIMATED)
        assertTrue("两列不重叠（可整行换行，不截断）", wb.right <= eb.left + 1f || wb.bottom <= eb.top + 1f)
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200W320NegativeStripAndLinkFit() {
        fontScale = 2f
        enterNegative()
        fullyVisible(node(AccountTags.DELETE_NEGATIVE_NOTICE))
        val cs = node(AccountTags.DELETE_CONTACT_CS)
        cs.performScrollTo()
        settle()
        val d = cs.density()
        assertTrue("条内链接命中 ≥48", cs.touch().height / d >= 47.5f)
        assertTrue("条内链接语义盒 ≥48", cs.box().height / d >= 47.5f)
        fullyVisible(textUnder(AccountTags.DELETE_CONTACT_CS, t("deletion.contact_cs")))
        fullyVisible(textNode(AccountVisualFixtures.draft("deletion.negative.note")))
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200W320CoolingTimelineFits() {
        fontScale = 2f
        enterCooling()
        listOf(
            "deletion.cooling.title",
            "deletion.cooling.timeline.apply",
            "deletion.cooling.timeline.cooling",
            "deletion.cooling.timeline.process_desc",
            "deletion.cooling.readonly",
        ).forEach { fullyVisible(textNode(t(it))) }
        fullyVisible(node(AccountTags.DELETE_COOLING_DEADLINE))
        val cancel = node(AccountTags.DELETE_CANCEL)
        assertTrue("【撤回注销】在屏内", cancel.box().bottom <= node(AccountTags.DELETE_PAGE).box().bottom + 1f)
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200W320PendingCardFits() {
        fontScale = 2f
        enterPending()
        listOf(
            "pending_confirm.title",
            "pending_confirm.deletion.desc",
            SUMMARY_OPERATION,
            "pending_confirm.summary.operation.deletion",
            "deletion.pending_card_hint",
        ).forEach { fullyVisible(textUnder(AccountTags.PENDING_CARD, t(it))) }
        val abandon = node(AccountTags.PENDING_ABANDON)
        val confirm = node(AccountTags.PENDING_CONFIRM)
        confirm.performScrollTo()
        settle()
        assertSeparateTargets("两按钮", abandon.touch(), confirm.touch())
    }

    @Test
    @Config(qualifiers = "w840dp-h1164dp-xhdpi")
    fun w840ContentIsCappedAt600AndCentered() {
        enterInitial()
        val funds = boxOf(AccountTags.DELETE_FUNDS)
        val page = boxOf(AccountTags.DELETE_PAGE)
        val d = pageDensity()
        assertTrue("限宽 600（卡宽 ≤ 600 - 32）：${funds.width / d}", funds.width / d <= 568.5f)
        assertEquals("居中", page.center.x / d, funds.center.x / d, 1f)
        val submit = boxOf(AccountTags.DELETE_SUBMIT)
        assertEquals("底栏按钮同样限宽居中", page.center.x / d, submit.center.x / d, 1f)
    }

    @Test
    @Config(qualifiers = "w840dp-h993dp-xhdpi")
    fun w840CoolingIsCentered() {
        enterCooling()
        val cooling = boxOf(AccountTags.DELETE_COOLING)
        val page = boxOf(AccountTags.DELETE_PAGE)
        val d = pageDensity()
        assertTrue(cooling.width / d <= 568.5f)
        assertEquals(page.center.x / d, cooling.center.x / d, 1f)
    }

    /** 滚到可见再判裁切（宽度与位置同时比，逐行宽、省略、纵向）。 */
    private fun fullyVisible(node: SemanticsNodeInteraction) {
        runCatching { node.performScrollTo() }
        settle()
        node.assertTextFully()
    }

    private fun assertSeparateTargets(
        message: String,
        first: Rect,
        second: Rect,
    ) {
        val i = first.intersect(second)
        assertTrue("$message：命中区重叠 $first / $second", i.width <= 0f || i.height <= 0f)
    }
}
