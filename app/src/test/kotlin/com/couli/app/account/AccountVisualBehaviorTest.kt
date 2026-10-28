@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliFormat
import com.couli.feature.me.AccountTags
import com.couli.feature.me.MeTags
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-13v-a 跳转 / 请求 / 读屏回归（brief §4 第 2、4、5 条；G-D22 只做回归断言）：进入注销 = getDeletion + 钱包摘要 + getMe（现状），本任务新增零请求；
 * 勾选、打开 / 取消确认框零请求；待确认卡独立于读取（R3-04）；30416 金额取响应原值；返回去向原样；读屏顺序、装饰不读、确认框焦点进标题、错误不双播。
 * 底版上应全绿。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1463dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class AccountVisualBehaviorTest : AccountVisualDeletionHarness() {
    // ---------------------------------------------------------------- 请求

    @Test fun enteringReadsOnlyDeletionWalletAndMe() {
        serverState.deletion = deletionFixture("none")
        serverState.wallet = walletFixture("funds")
        launch()
        open(SETTINGS)
        idleUntil("设置页注销行") { has(DELETE_ROW) }
        settle()
        val before = all().size
        click(DELETE_ROW)
        idleUntil { has(AccountTags.DELETE_WITHDRAWABLE) }
        settle()
        val entered = all().drop(before).map { "${it.method} ${it.path}" }
        assertEquals("进入注销只读三样（本任务新增零请求）：$entered", setOf("GET $DELETION", "GET $WALLET", "GET $ME"), entered.toSet())
        assertTrue("各至少一次", listOf(DELETION, WALLET, ME).all { p -> entered.contains("GET $p") })
    }

    @Test fun togglingAndOpeningOrCancellingTheDialogSendNothing() {
        enterPending()
        settle()
        val before = all().size
        tap(AccountTags.PENDING_ABANDON)
        idleUntil { has(AccountTags.ABANDON_OK) }
        tap(AccountTags.ABANDON_CANCEL)
        settle()
        assertEquals("打开 / 取消确认框零请求", before, all().size)
    }

    @Test fun pendingCardShowsBeforeTheDeletionReadLands() {
        seedDeletionPending()
        serverState.wallet = walletFixture("funds")
        var gate: Pair<java.util.concurrent.CountDownLatch, java.util.concurrent.CountDownLatch>? = null
        try {
            enterVia { gate = holdAll("GET", DELETION) { envelope(deletionFixture("none")) } }
            assertTrue(gate!!.first.await(WAIT_S, TimeUnit.SECONDS))
            idleUntil("加载中也先显示待确认卡（R3-04）") { has(AccountTags.PENDING_CARD) }
            assertTrue("仍在加载", has(AccountTags.DELETE_LOADING))
        } finally {
            gate?.second?.countDown()
        }
        idleUntil { has(AccountTags.DELETE_SUBMIT) }
    }

    @Test fun a30416OnApplyShowsTheResponseAmount() {
        serverState.requireStepUp = false
        enterInitial(checked = true)
        enqueueCode("POST", DELETION, 30416, """{"amount_fen":${AccountVisualFixtures.amount30416()}}""")
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil { has(AccountTags.DELETE_NEGATIVE_NOTICE) }
        assertTrue(
            "金额取响应 amount_fen 原值",
            textOf(AccountTags.DELETE_NEGATIVE_NOTICE).contains(CouliFormat.money(AccountVisualFixtures.amount30416())),
        )
        assertTrue("键结束", records().isEmpty())
    }

    @Test fun backReturnsToSettingsOnce() {
        enterInitial()
        backButton().performClick()
        settle()
        idleUntil("回设置页") { top() == SETTINGS }
        assertEquals("栈里不再有注销页", 0, stack().count { it == DELETE_ACCOUNT })
    }

    // ---------------------------------------------------------------- 读屏（回归）

    @Test fun readingOrderFollowsTheBoard() {
        enterInitial()
        val spoken =
            mergedNodes().map { n ->
                (
                    n.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .map { it.text } +
                        n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                ).joinToString("，")
            }
        val order =
            listOf(
                t("deletion.title"),
                "请先确认下面的内容",
                t("deletion.funds.title"),
                t("deletion.withdrawable"),
                t("deletion.estimated"),
                t("deletion.go_withdraw"),
                t("deletion.notice.title"),
                t("deletion.notice.1").take(8),
                t("deletion.notice.6").take(8),
                t("deletion.waiver"),
                t("deletion.submit"),
                t("deletion.submit_hint"),
            )
        val index = order.map { want -> spoken.indexOfFirst { it.contains(want) } }
        assertTrue("每一项都读得到：${order.zip(index)}", index.all { it >= 0 })
        assertEquals("读屏顺序：标题 → 页首提示 → 资金卡 → 须知 → 勾选 → 申请 → 提示：${order.zip(index)}", index.sorted(), index)
    }

    @Test fun decorationsAreNotSpoken() {
        enterInitial()
        val described = mergedNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription) }.flatten()
        assertEquals("只有返回有内容描述（三角、时钟、圆点、勾选圆不读）：$described", 1, described.size)
        assertFalse(
            "圆点字符不进语义",
            mergedNodes().any { n ->
                n.config.getOrNull(SemanticsProperties.Text).orEmpty().any {
                    it.text.trim() in
                        setOf("•", "·", "●")
                }
            },
        )
    }

    @Test fun waiverReadsCheckboxWithState() {
        enterInitial()
        val config = node(AccountTags.DELETE_WAIVER).fetchSemanticsNode().config
        assertEquals(t("deletion.waiver.unchecked"), config.getOrNull(SemanticsProperties.StateDescription))
        assertTrue("文字读得到", spokenOf(AccountTags.DELETE_WAIVER).any { it.contains(t("deletion.waiver")) })
    }

    @Test fun abandonDialogMovesFocusToTheTitle() {
        enterPending()
        tap(AccountTags.PENDING_ABANDON)
        idleUntil { has(AccountTags.ABANDON_OK) }
        composeRule.mainClock.advanceTimeBy(FOCUS_DELAY_MS)
        settle()
        val pane = SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)
        val focused =
            composeRule
                .onAllNodes(
                    isFocused() and
                        androidx.compose.ui.test
                            .hasAnyAncestor(pane),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        assertTrue(
            "焦点进标题「${t("pending_confirm.abandon")}」：${focused.map { it.config }}",
            focused.any { n ->
                n.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .any { it.text == t("pending_confirm.abandon") }
            },
        )
    }

    @Test fun errorIsAnnouncedOnceWithoutAToast() {
        enter30412()
        assertEquals(LiveRegionMode.Assertive, liveRegion(AccountTags.DELETE_ERROR))
        assertFalse("统一错误不叠加轻提示（不双播）", has(MeTags.TOAST))
        val assertive =
            composeRule
                .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive), useUnmergedTree = true)
                .fetchSemanticsNodes()
        assertEquals("页面只有一个 assertive", 1, assertive.size)
    }

    private fun backButton() =
        composeRule.onNode(
            hasClickAction() and hasAnyDescendant(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)) and
                androidx.compose.ui.test
                    .hasAnyAncestor(
                        androidx.compose.ui.test
                            .hasTestTag(AccountTags.DELETE_PAGE),
                    ),
            useUnmergedTree = true,
        )

    private companion object {
        const val FOCUS_DELAY_MS = 200L
    }
}
