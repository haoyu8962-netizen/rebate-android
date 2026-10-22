package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawVisualPendingTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w56PendingRemainsActionableDuringRulesLoading() {
        host.api.readHold = CompletableDeferred()
        pending()
        rule.onNodeWithTag(WalletTags.PENDING_CONFIRM).performClick()
        assertEquals(1, host.api.confirms.size)
        assertEquals(0, host.api.writes().size)
    }

    @Test fun w56PendingRemainsActionableDuringRulesError() {
        host.api.rules = WithdrawVisualFixtures.failure()
        pending()
        rule.onNodeWithTag(WalletTags.WITHDRAW_LOAD_ERROR).assertIsDisplayed()
        rule.onNodeWithTag(WalletTags.PENDING_ABANDON).performClick()
        assertTrue(host.flow.state.value.abandonConfirm)
        assertEquals(0, host.api.confirms.size + host.api.abandons.size)
    }

    @Test fun w57NoteAndRecordsShareBottomRowAndClickOnlyNavigates() {
        pending()
        val note = rule.onNodeWithText(WalletDraftTexts.text("pending_confirm.withdraw.note"), useUnmergedTree = true).fetchSemanticsNode()
        val link = rule.onNodeWithTag(WalletTags.PENDING_RECORDS).fetchSemanticsNode()
        assertTrue("默认字号注释与链接同行", note.boundsInRoot.top < link.boundsInRoot.bottom && link.boundsInRoot.top < note.boundsInRoot.bottom)
        val before = host.api.events.toList()
        rule.onNodeWithTag(WalletTags.PENDING_RECORDS).performClick()
        assertEquals(listOf("WithdrawRecords"), host.env.opened.map { it.first })
        assertEquals(before, host.api.events)
    }

    @Test fun pendingRecordsRealTouchOnlyOpensRecordsWithoutConfirmOrSubmit() {
        pending()
        val before = host.api.events.toList()
        rule.onNodeWithTag(WalletTags.PENDING_RECORDS).performScrollTo().performTouchInput { click(center) }
        assertEquals(listOf("WithdrawRecords"), host.env.opened.map { it.first })
        assertEquals(before, host.api.events)
    }

    @Test fun w58BusyDisablesBothAndShowsSpinnerWithoutWriting() {
        host.api.pending = WithdrawVisualFixtures.pending(busy = true)
        host.launch()
        rule.onNodeWithTag(WalletTags.PENDING_CONFIRM).assertIsNotEnabled()
        rule.onNodeWithTag(WalletTags.PENDING_ABANDON).assertIsNotEnabled()
        val progress = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), true).fetchSemanticsNodes()
        assertTrue("忙碌待确认卡须有进度圈", progress.isNotEmpty())
        assertEquals(0, host.api.writes().size + host.api.confirms.size + host.api.abandons.size)
    }

    @Test fun w59StillPendingNoticeKeepsOnePoliteRegionAndOriginalTicket() {
        pending()
        rule.onNodeWithTag(WalletTags.PENDING_CONFIRM).performClick()
        rule.onNodeWithTag(WalletTags.PENDING_NOTICE).performScrollTo().assertIsDisplayed()
        assertEquals(1, host.api.confirms.size)
        assertEquals(
            "visual-withdraw-original",
            host.api.confirms
                .single()
                .key,
        )
        assertEquals(0, host.api.writes().size)
        assertEquals(
            LiveRegionMode.Polite,
            rule.onNodeWithTag(WalletTags.PENDING_NOTICE).fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
        )
        assertEquals(
            1,
            rule
                .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
                .fetchSemanticsNodes()
                .size,
        )
    }

    @Test fun payoutPendingHasNoWithdrawalRecordsLink() {
        host.api.pending = WithdrawVisualFixtures.pending(payout = true)
        host.launch(payout = true)
        rule.onNodeWithTag(WalletTags.PENDING_CARD).assertIsDisplayed()
        rule.onNodeWithTag(WalletTags.PENDING_RECORDS).assertDoesNotExist()
        rule.onNodeWithTag(WalletTags.PAYOUT_CHANGE).assertIsNotEnabled()
        assertEquals(0, host.api.writes().size)
    }

    private fun pending() {
        host.api.pending = WithdrawVisualFixtures.pending()
        host.launch()
        rule.onNodeWithTag(WalletTags.PENDING_CARD).assertIsDisplayed()
        assertEquals(0, host.api.writes().size)
    }
}
