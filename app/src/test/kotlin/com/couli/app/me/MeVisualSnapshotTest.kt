package com.couli.app.me

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.me.MeLevel
import com.couli.feature.me.MeTags
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1032dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { MeVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun me() = capture("Me-light")

    @Test fun earnings() {
        host.env.routes = MeVisualFixtures.routes(true)
        host.env.earnings = true
        capture("MeEarningsEntry-earnings-only-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun guest() {
        guestIdentity()
        capture("MeGuest-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1110dp-xhdpi")
    fun hidden() {
        host.env.guide = true
        capture("MeHidden-light", hidden = true)
    }

    @Test fun backfillIdentityOnly() = capture("MeInviteBackfill-v2-identity-GM06-light", "me_new_user")

    @Test fun boundCopyStyleOnly() {
        host.launch("me_bound_user", statusBar = true)
        rule.meTag(MeTags.ME_COPY).performClick()
        shot("MeInviteBound-copy-style-GM06-GM12-light")
    }

    @Test fun minorIdentityOnly() = capture("MeMinor-GM08-light", "me_no_invite_code")

    @Test fun statesNegative() = capture("MeStates-negative-GM03-light", "me_negative")

    // getMe不存在提现开关，不能造字段；这张是同一正常报文的版式对照。
    @Test fun statesNoSwitchField() = capture("MeStates-no-switch-field-GM03-light")

    @Test fun statesFrozen() = capture("MeStates-frozen-GM04-light", "me_frozen")

    @Test fun dark() = capture("MeDark-dark", dark = true)

    @Test fun error50001() {
        host.api.answer = MeVisualFixtures.failure()
        capture("StateLoadFailed-GM11-light")
    }

    @Test fun errorNetwork() {
        host.api.answer = MeVisualFixtures.offline()
        capture("StateNetworkError-GM11-light")
    }

    @Test fun loading() {
        host.api.hold = CompletableDeferred()
        capture("state-loading-light")
    }

    @Test fun loadingDark() {
        host.api.hold = CompletableDeferred()
        capture("state-loading-dark", dark = true)
    }

    @Test fun restricted() {
        host.env.scope = "deletion_only"
        capture("state-restricted-light")
    }

    @Test fun blocked() {
        host.api.answer = MeVisualFixtures.failure(42901)
        capture("state-blocked-retry-light")
    }

    @Test fun refreshError() {
        host.launch(statusBar = true)
        host.refresh(MeVisualFixtures.failure())
        shot("state-refresh-failed-toast-light")
    }

    @Test fun copyFailure() {
        host.env.copyOk = false
        host.launch(statusBar = true)
        rule.meTag(MeTags.ME_COPY).performClick()
        shot("state-copy-failed-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun font200() = capture("Me-font200-w390-light", "me_long", fontScale = 2f)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun font200Dark() = capture("Me-font200-w390-dark", "me_long", dark = true, fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200Narrow() = capture("Me-font200-w320-light", "me_long", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun guest200Narrow() {
        guestIdentity()
        capture("MeGuest-font200-w320-light", fontScale = 2f)
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun guest200() {
        guestIdentity()
        capture("MeGuest-font200-w390-light", fontScale = 2f)
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun hidden200() {
        host.env.guide = true
        capture("MeHidden-font200-w390-light", hidden = true, fontScale = 2f)
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun hidden200Narrow() {
        host.env.guide = true
        capture("MeHidden-font200-w320-light", hidden = true, fontScale = 2f)
    }

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun wide() = capture("Me-w840-light")

    private fun guestIdentity() {
        host.env.identityFlow.value = host.env.identity().copy(level = MeLevel.GUEST, account = null)
    }

    private fun capture(
        name: String,
        profile: String = "me_member",
        dark: Boolean = false,
        fontScale: Float = 1f,
        hidden: Boolean = false,
    ) {
        host.launch(profile, dark, fontScale, hidden, statusBar = true)
        when {
            !host.env.identity().member -> {
                rule.meTag(MeTags.ME_GUEST).assertExists()
                host.assertReads(0)
            }

            host.env.scope == "deletion_only" -> {
                rule.meTag(MeTags.ME_LOADING).assertExists()
                host.assertReads(0)
            }

            host.api.hold != null -> {
                rule.meTag(MeTags.ME_LOADING).assertExists()
                host.assertReads(1)
            }

            host.api.answer is com.couli.core.network.ApiResult.Success -> {
                rule.meTag(MeTags.ME_BALANCE).assertExists()
                host.assertReads(1)
            }

            else -> {
                rule.meTag(MeTags.ME_ERROR).assertExists()
                host.assertReads(1)
            }
        }
        shot(name)
    }

    private fun shot(name: String) {
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(300)
        rule.onRoot().captureRoboImage("src/test/snapshots/me-v/$name.png")
    }
}
