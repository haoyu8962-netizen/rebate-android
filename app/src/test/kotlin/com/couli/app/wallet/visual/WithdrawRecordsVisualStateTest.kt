package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.wallet.RecordsSnapshot
import com.couli.feature.wallet.WalletTags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawRecordsVisualStateTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawRecordsVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test
    @Config(qualifiers = "w390dp-h2000dp-xhdpi")
    fun w20FocusIsFirstEvenWhenItsMonthAlreadyAppearsLaterAndNeverDuplicates() {
        host.launch("pinned", focus = "FOCUS")
        assertEquals(
            listOf("FOCUS", "PENDING", "FAILED", "PAID", "REJECTED"),
            host.controller.state.value.items
                .map { it.withdrawalId },
        )
        assertEquals(setOf("FOCUS"), host.controller.state.value.expanded)
        val expected = listOf("FOCUS", "PENDING", "FAILED", "PAID", "REJECTED")
        val actual =
            rule
                .onAllNodes(
                    androidx.compose.ui.test.SemanticsMatcher("记录行") { node ->
                        node.config.getOrNull(SemanticsProperties.TestTag) in
                            expected.map { "record_$it" }
                    },
                    true,
                ).fetchSemanticsNodes()
                .sortedBy { it.boundsInRoot.top }
                .map {
                    it.config[SemanticsProperties.TestTag].removePrefix("record_")
                }
        assertEquals("定位置顶独立，不能顺便把九月的其他行提到十月前", expected, actual)
        val focus = rule.record("FOCUS").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag(WalletTags.RECORDS_LIST).performScrollToNode(hasTestTag("record_PENDING"))
        assertTrue(
            "定位单必须在十月记录之前，不能被九月分组合走",
            focus.top <
                rule
                    .record("PENDING")
                    .fetchSemanticsNode()
                    .boundsInRoot.top,
        )
        host.assertCalls("listWithdrawals", "getWithdrawal")
    }

    @Test fun w20FocusedExistingIdOccursOnceAndStaysAheadOfOtherMonths() {
        host.launch("list", focus = "PAID")
        assertEquals(
            listOf("PAID", "PENDING", "FAILED", "REJECTED"),
            host.controller.state.value.items
                .map { it.withdrawalId },
        )
        assertEquals(1, rule.onAllNodes(hasTestTag("record_PAID"), true).fetchSemanticsNodes().size)
        host.assertCalls("listWithdrawals", "getWithdrawal")
    }

    @Test fun w20NotFoundFocusSilentlyShowsList() {
        host.api.detailAnswers["ABSENT"] = WithdrawRecordsVisualFixtures.failure(30701)
        host.launch("list", focus = "ABSENT")
        assertEquals(4, host.controller.state.value.items.size)
        assertTrue(
            host.controller.state.value.expanded
                .isEmpty(),
        )
        assertNull(host.controller.state.value.toast)
        host.assertCalls("listWithdrawals", "getWithdrawal")
    }

    @Test fun w20PaginationKeepsCursorDeduplicatesAndAppendsOnlyOneRequest() {
        host.launch("pagination")
        rule.waitForIdle()
        assertTrue(host.controller.state.value.loadingMore)
        assertEquals(
            "cursor=next%20%2F%3F%26%3D&limit=20",
            host.api.calls
                .last()
                .query,
        )
        rule.runOnIdle {
            host.controller.loadMore()
            host.api.nextHold!!.complete(Unit)
        }
        rule.waitForIdle()
        assertEquals(
            listOf("PENDING", "FOCUS"),
            host.controller.state.value.items
                .map { it.withdrawalId },
        )
        assertNull(host.controller.state.value.nextCursor)
        host.assertCalls("listWithdrawals", "listWithdrawals")
    }

    @Test fun w35MoreFailureRetryAddsExactlyOneReadAndPreservesRows() {
        host.api.nextHold = null
        host.api.nextAnswer = WithdrawRecordsVisualFixtures.failure()
        host.launch("pagination")
        rule.onNodeWithTag(WalletTags.RECORDS_LOAD_MORE).assertIsDisplayed()
        assertEquals(
            listOf("PENDING"),
            host.controller.state.value.items
                .map { it.withdrawalId },
        )
        host.api.nextAnswer = WithdrawRecordsVisualFixtures.ok(WithdrawRecordsVisualFixtures.page("next"))
        rule.onNodeWithTag(WalletTags.RECORDS_LOAD_MORE).performClick()
        rule.waitForIdle()
        assertEquals(
            listOf("PENDING", "FOCUS"),
            host.controller.state.value.items
                .map { it.withdrawalId },
        )
        host.assertCalls("listWithdrawals", "listWithdrawals", "listWithdrawals")
    }

    @Test fun w35InitialErrorRetryDoesNotBecomeEmptyOrZeroMoney() {
        host.api.answer = WithdrawRecordsVisualFixtures.failure()
        host.launch("pending")
        rule.onNodeWithTag(WalletTags.RECORDS_ERROR).assertIsDisplayed()
        rule.onNodeWithTag(WalletTags.RECORDS_EMPTY).assertDoesNotExist()
        assertTrue(rule.drawnTexts().none { it.contains("¥0") })
        host.api.answer = WithdrawRecordsVisualFixtures.ok(WithdrawRecordsVisualFixtures.page("pending"))
        rule.onNodeWithText("重试").performClick()
        rule.waitForIdle()
        rule.record("PENDING").assertIsDisplayed()
        host.assertCalls("listWithdrawals", "listWithdrawals")
    }

    @Test fun w20MalformedRefreshKeepsOldRowsCursorAndVisibleErrorThenRecovers() {
        host.launch("pagination")
        val before = host.controller.state.value
        host.refresh(WithdrawRecordsVisualFixtures.ok(JsonObject(emptyMap())))
        assertEquals(before.items, host.controller.state.value.items)
        assertEquals(before.nextCursor, host.controller.state.value.nextCursor)
        rule.onNodeWithTag(WalletTags.RECORDS_REFRESH_ERROR).assertIsDisplayed()
        host.refresh(WithdrawRecordsVisualFixtures.ok(WithdrawRecordsVisualFixtures.page("paid")))
        assertEquals(RecordsSnapshot.CONTENT, host.controller.state.value.phase)
        assertEquals(
            listOf("PAID"),
            host.controller.state.value.items
                .map { it.withdrawalId },
        )
        assertNull(host.controller.state.value.error)
        assertNull(host.controller.state.value.nextCursor)
        host.assertCalls("listWithdrawals", "listWithdrawals", "listWithdrawals", "listWithdrawals")
    }

    @Test fun w31EmptyStillSupportsRealPullToRefresh() {
        host.launch("empty")
        rule.onNodeWithTag(WalletTags.RECORDS_EMPTY).assertIsDisplayed()
        host.api.answer = WithdrawRecordsVisualFixtures.ok(WithdrawRecordsVisualFixtures.page("pending"))
        rule.onNodeWithTag(WalletTags.RECORDS_LIST).performTouchInput { swipeDown(startY = 30f, endY = height - 30f, durationMillis = 600) }
        rule.waitForIdle()
        rule.record("PENDING").assertIsDisplayed()
        host.assertCalls("listWithdrawals", "listWithdrawals")
    }

    @Test fun w35LoadingHasNoFabricatedAmountsOrEmptyMessage() {
        host.api.hold = CompletableDeferred()
        host.launch()
        assertEquals(RecordsSnapshot.LOADING, host.controller.state.value.phase)
        rule.onNodeWithTag(WalletTags.RECORDS_EMPTY).assertDoesNotExist()
        assertTrue(rule.drawnTexts().none { it.contains("¥") })
        host.assertCalls("listWithdrawals")
    }
}
