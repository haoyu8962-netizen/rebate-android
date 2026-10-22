package com.couli.app.purchase

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.orders.PendingTrackService
import com.couli.core.orders.PendingTrackTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
class PurchaseVisualPendingTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PurchaseVisualTestHost(rule) }
    private val pending by lazy { PurchaseVisualPending(host, rule) }

    @After fun close() = host.close()

    private fun open(
        claim: Boolean = false,
        decided: Boolean = false,
    ) {
        pending.permission = if (decided) "granted" else "not_determined"
        host.extraBanner = { PurchaseVisualPendingSlot(pending) }
        host.launch()
        pending.show(claim)
        rule.assertPurchasePendingSlot()
        host.counts(open = 1, jumps = 1)
    }

    @Test fun notifyOnlyRequestsPermissionOnceAndDoesNotReopenOrAuthorize() {
        open()
        val button = rule.onNodeWithTag(PendingTrackTags.NOTIFY).assertIsDisplayed()
        assertTrue(
            button.fetchSemanticsNode().boundsInRoot.height /
                button
                    .fetchSemanticsNode()
                    .layoutInfo.density.density >= 48f,
        )
        button.performClick()
        rule.waitForIdle()
        assertEquals(1, pending.notifications)
        rule.onNodeWithTag(PendingTrackTags.NOTIFY_ROW).assertDoesNotExist()
        host.counts(open = 1, jumps = 1)
        assertEquals(1, host.count(PendingTrackService.LIST_OPERATION))
    }

    @Test fun decidedPermissionHidesRowAndDoesNotCallPermissionAutomatically() {
        open(decided = true)
        rule.onNodeWithTag(PendingTrackTags.NOTIFY_ROW).assertDoesNotExist()
        assertEquals(0, pending.notifications)
        host.counts(open = 1, jumps = 1)
    }

    @Test fun claimUsesExactlyOneCallbackAndNeverReopens() {
        open(claim = true, decided = true)
        rule.onNodeWithTag(PendingTrackTags.CLAIM).performClick()
        rule.waitForIdle()
        assertEquals(1, pending.claims)
        assertEquals(0, pending.notifications)
        host.counts(open = 1, jumps = 1)
    }

    @Test fun closeTargetsRenderedAttemptAndIsSingleFlight() {
        open(decided = true)
        rule.onNodeWithTag(PendingTrackTags.CLOSE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(PendingTrackTags.CLOSE).performClick()
        rule.waitForIdle()
        val calls = host.api.calls("dismissPendingTrack")
        assertEquals(1, calls.size)
        assertEquals("visual-link", calls.single().call.pathParams["link_id"])
        assertEquals("{\"attempt_id\":\"visual-attempt\"}", calls.single().call.body)
        host.counts(open = 1, jumps = 1)
    }
}
