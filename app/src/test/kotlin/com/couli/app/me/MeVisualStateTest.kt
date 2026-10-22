package com.couli.app.me

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.me.MeLevel
import com.couli.feature.me.MeTags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1032dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeVisualStateTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { MeVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun m18RetryHasItsTagAndSendsExactlyOneRead() {
        host.api.answer = MeVisualFixtures.failure()
        host.launch()
        rule.meTag(MeTags.ME_ERROR).assertExists()
        rule.onNodeWithText("a1b2c3", substring = true).assertExists()
        host.api.answer = MeVisualFixtures.ok()
        rule.meTag(MeTags.ME_RETRY).performClick()
        rule.meTag(MeTags.ME_BALANCE).assertExists()
        host.assertReads(2)
    }

    @Test fun m18RateGateDisablesRetryAndPull() {
        host.api.answer = MeVisualFixtures.failure(42901)
        host.launch()
        rule.onNodeWithText("重试").assertIsNotEnabled()
        rule.runOnIdle { assertFalse(host.controller.refresh()) }
        rule.meTag(MeTags.ME_PAGE).performTouchInput { swipeDown() }
        host.assertReads(1)
    }

    @Test fun invalidMoneyNeverBecomesZeroAndRefreshKeepsPreviousValues() {
        host.launch("me_no_sum")
        for (field in listOf("available_fen", "withdrawable_fen", "estimated_total_fen")) {
            for (bad in listOf(null, JsonNull, JsonPrimitive("12"))) {
                val profile = MeVisualFixtures.profile()
                val balance = profile.getValue("balance").jsonObject.toMutableMap()
                if (bad == null) balance.remove(field) else balance[field] = bad
                host.refresh(MeVisualFixtures.ok(JsonObject(profile + ("balance" to JsonObject(balance)))))
                rule.onNodeWithText("¥12.34", useUnmergedTree = true).assertExists()
                rule.onNodeWithText("¥56.78", useUnmergedTree = true).assertExists()
                rule.onNodeWithText("¥0", useUnmergedTree = true).assertDoesNotExist()
            }
        }
        host.assertReads(10)
        host.refresh(MeVisualFixtures.ok(MeVisualFixtures.profile("me_new_user")))
        assertEquals(
            0L,
            host.deps.repo.state.value.me!!
                .withdrawableFen,
        )
        host.assertReads(11)
    }

    @Test fun invalidFirstReadShowsErrorWithoutBalance() {
        host.api.answer = MeVisualFixtures.ok(JsonObject(emptyMap()))
        host.launch()
        rule.meTag(MeTags.ME_ERROR).assertExists()
        rule.meTag(MeTags.ME_BALANCE).assertDoesNotExist()
        rule.meTag(MeTags.ME_GUEST).assertDoesNotExist()
        host.assertReads(1)
    }

    @Test fun restrictedSessionNeverAutoReadsOnEntryResumeOrReentry() {
        host.env.scope = "deletion_only"
        host.launch()
        host.assertReads(0)
        resume()
        host.assertReads(0)
        rule.runOnIdle { host.deps.tab.leave() }
        rule.waitForIdle()
        host.assertReads(0)
    }

    @Test fun guestAndBasicNeverReadOnResume() {
        host.env.identityFlow.value = host.env.identity().copy(level = MeLevel.BASIC, account = null)
        host.launch()
        resume()
        host.assertReads(0)
    }

    @Test fun entryResumePullAndReturnEachReadOnceWithoutMaskAnnouncement() {
        host.launch(hidden = true)
        host.assertReads(1)
        resume()
        host.assertReads(2)
        rule.meTag(MeTags.ME_PAGE).performTouchInput { swipeDown() }
        rule.waitForIdle()
        host.assertReads(3)
        rule.runOnIdle { host.deps.tab.leave() }
        rule.waitForIdle()
        host.assertReads(4)
        assertTrue(host.spoken.isEmpty())
    }

    @Test fun leavingRevokesReadAndLateResultCannotRepopulateSnapshot() {
        host.api.hold = CompletableDeferred()
        host.launch()
        val controller = host.controller
        rule.runOnIdle {
            host.deps.tab.leave()
            host.visible.value = false
        }
        assertFalse(controller.reading())
        assertEquals(1, host.api.cancelled.size)
        rule.runOnIdle { host.api.hold!!.complete(Unit) }
        rule.waitForIdle()
        assertEquals(null, host.deps.repo.state.value.me)
        host.assertReads(1)
    }

    @Test
    @Config(qualifiers = "w390dp-h600dp-xhdpi")
    fun m20ScrollSurvivesSameIdentityReentryButNotDifferentEpoch() {
        host.launch()
        rule.onNodeWithText("设置").performScrollTo()
        val saved =
            host.deps.tabScroll
                .of(host.env.identity())
                .value
        assertTrue(saved > 0)
        rule.runOnIdle {
            host.deps.tab.leave()
            host.visible.value = false
        }
        rule.runOnIdle { host.visible.value = true }
        rule.waitForIdle()
        assertEquals(
            saved,
            host.deps.tabScroll
                .of(host.env.identity())
                .value,
        )
        rule.runOnIdle { host.env.identityFlow.value = host.env.identity().copy(epoch = 2) }
        rule.waitForIdle()
        assertEquals(
            0,
            host.deps.tabScroll
                .of(host.env.identity())
                .value,
        )
        assertTrue(host.spoken.isEmpty())
    }

    private fun resume() {
        rule.runOnIdle { (host.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        rule.runOnIdle { (host.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        rule.waitForIdle()
    }
}
