package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.wallet.WalletTags
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawRecordsVisualBehaviorTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawRecordsVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w28AllFourChangeButtonEdgesOpenOnlyPayoutExactlyOnceEach() {
        host.launch("failed", focus = "FAILED")
        val failures = mutableListOf<String>()
        repeat(4) { edge ->
            rule.touchRecordEdge(WalletTags.RECORD_CHANGE_ACCOUNT, edge)
            if (host.env.opened.size != edge + 1) failures += "edge=$edge opened=${host.env.opened.size}"
            if (host.controller.state.value.expanded != setOf("FAILED")) failures += "edge=$edge 抢到了收起点击"
            host.assertCalls("listWithdrawals", "getWithdrawal")
        }
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals(List(4) { "PayoutAccount" to JsonObject(emptyMap()) }, host.env.opened)
        assertTrue(host.env.contacts.isEmpty())
        assertTrue(host.env.copies.isEmpty())
    }

    @Test fun w28ChangeTargetAndSummaryTargetDoNotOverlap() {
        host.launch("failed", focus = "FAILED")
        val summary =
            rule
                .onNode(
                    hasClickAction() and hasAnyAncestor(hasTestTag("record_FAILED")) and
                        androidx.compose.ui.test.SemanticsMatcher
                            .keyIsDefined(SemanticsProperties.StateDescription),
                    true,
                ).fetchSemanticsNode()
        val change = rule.onNodeWithTag(WalletTags.RECORD_CHANGE_ACCOUNT).fetchSemanticsNode()
        assertRecordTargetsSeparate(summary.touchBoundsInRoot, change.touchBoundsInRoot)
    }

    @Test fun w27FourCopyEdgesUseUniformWriterWithoutRequestsOrCollapse() {
        host.launch("paid", focus = "PAID")
        repeat(4) { edge ->
            rule.touchRecordEdge(WalletTags.RECORD_COPY, edge)
            assertEquals(List(edge + 1) { "20260928200040011100" }, host.env.copies)
            assertEquals(setOf("PAID"), host.controller.state.value.expanded)
            host.assertCalls("listWithdrawals", "getWithdrawal")
        }
        assertTrue(host.env.opened.isEmpty())
    }

    @Test fun w27CopyFailureShowsFailureAndNeverClaimsSuccess() {
        host.env.copyAccepted = false
        host.launch("paid", focus = "PAID")
        rule.onNodeWithTag(WalletTags.RECORD_COPY).performScrollTo().performClick()
        assertEquals(
            "withdraw_detail.copy_failed",
            host.controller.state.value.toast
                ?.key,
        )
        assertEquals(1, host.env.copies.size)
        host.assertCalls("listWithdrawals", "getWithdrawal")
    }

    @Test fun w29ServiceClickKeepsOriginalSourceAndOneCallback() {
        host.launch("pending", focus = "PENDING")
        rule.onNodeWithTag(WalletTags.RECORD_CS).performScrollTo().performClick()
        assertEquals(listOf("WithdrawRecords"), host.env.contacts)
        assertTrue(host.env.opened.isEmpty())
        host.assertCalls("listWithdrawals", "getWithdrawal")
    }

    @Test fun w20ExpandCollapseReexpandDoesNotRefetchOrChangeKeys() {
        host.launch("same_month")
        val ids =
            host.controller.state.value.items
                .map { it.withdrawalId }
        host.toggle("FAILED")
        host.toggle("FAILED")
        host.toggle("FAILED")
        assertEquals(
            ids,
            host.controller.state.value.items
                .map { it.withdrawalId },
        )
        assertEquals(setOf("FAILED"), host.controller.state.value.expanded)
        host.assertCalls("listWithdrawals", "getWithdrawal")
    }

    @Test fun w28CapturedCallbackCannotActAfterSessionEpochChanges() {
        host.launch("failed", focus = "FAILED")
        val callback =
            rule
                .onNodeWithTag(WalletTags.RECORD_CHANGE_ACCOUNT)
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        rule.runOnIdle {
            host.env.epoch++
            callback()
        }
        assertTrue(host.env.opened.isEmpty())
        host.assertCalls("listWithdrawals", "getWithdrawal")
    }

    @Test fun w28CapturedCallbackCannotActAfterRefreshChangesVersion() {
        host.launch("failed", focus = "FAILED")
        val callback =
            rule
                .onNodeWithTag(WalletTags.RECORD_CHANGE_ACCOUNT)
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        val version = host.controller.state.value.version
        // 同一UI事件中刷新快照后立即调用，尚未重组；不能保存会指向新版onClick的Compose语义包装器再跨帧调用。
        rule.runOnIdle {
            host.api.answer = WithdrawRecordsVisualFixtures.ok(WithdrawRecordsVisualFixtures.page("failed"))
            host.controller.refresh()
            assertTrue("先确认快照已换版", host.controller.state.value.version > version)
            callback()
        }
        rule.waitForIdle()
        assertTrue("旧画面回调不得打开收款账号", host.env.opened.isEmpty())
        host.assertCalls("listWithdrawals", "getWithdrawal", "listWithdrawals", "getWithdrawal")
    }

    @Test fun gw18RecordsEntryDoesNotInventCompletedToastOrExtraRead() {
        host.launch("paid", focus = "PAID")
        rule.mainClock.advanceTimeBy(3000)
        rule.onNodeWithTag(WalletTags.TOAST).assertDoesNotExist()
        rule.onNodeWithTag(WalletTags.RECORD_DETAIL_PREFIX + "PAID").assertIsDisplayed()
        host.assertCalls("listWithdrawals", "getWithdrawal")
    }
}
