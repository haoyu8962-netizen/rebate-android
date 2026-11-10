package com.couli.app.me

import android.app.Application
import android.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1032dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeAvatarRenderingTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { MeAvatarTestHost(rule) }

    @After fun close() = host.close()

    @Test fun httpsLoadsExactServerUrlAndDisplays56DpCircle() {
        host.launch()
        assertEquals(listOf(MeAvatarImages.A), host.images.requests.map { it.url })
        rule.assertAvatarFallback(host)
        host.finish(0, Color.MAGENTA)
        rule.assertAvatarImage(host, Color.MAGENTA)
        rule.assertAvatarDecorative()
    }

    @Test fun pendingLoadKeepsBuiltInPersonAndIsDecorative() {
        host.launch()
        assertEquals(listOf(MeAvatarImages.A), host.images.requests.map { it.url })
        rule.assertAvatarFallback(host)
        rule.assertAvatarDecorative()
        host.finish(0, Color.CYAN)
        rule.assertAvatarImage(host, Color.CYAN)
    }

    @Test fun failedLoadKeepsBuiltInPersonInsteadOfBlank() {
        host.launch()
        assertEquals(listOf(MeAvatarImages.A), host.images.requests.map { it.url })
        host.finish(0, null)
        rule.assertAvatarFallback(host)
        rule.assertAvatarDecorative()
    }

    @Test fun immediateFailureStillHasFallbackInDarkAt200Percent() {
        host.images.failImmediately = true
        host.launch(dark = true, fontScale = 2f)
        assertEquals(listOf(MeAvatarImages.A), host.images.requests.map { it.url })
        rule.assertAvatarFallback(host)
        rule.assertAvatarDecorative()
    }

    @Test fun serverImageKeepsItsColorsInDarkAt200Percent() {
        host.launch(dark = true, fontScale = 2f)
        assertEquals(listOf(MeAvatarImages.A), host.images.requests.map { it.url })
        host.finish(0, Color.MAGENTA)
        rule.assertAvatarImage(host, Color.MAGENTA)
        rule.assertAvatarDecorative()
    }

    /** 没有提供图片源（目录页、旧快照宿主 MeVisualTestHost）：一律兜底、不崩；保证现有「我的」整页快照零变化。 */
    @Test fun withoutLoaderKeepsBuiltInPerson() {
        host.launch(withLoader = false)
        rule.assertAvatarFallback(host)
        rule.assertAvatarDecorative()
    }

    @Test fun sameAccountRefreshToNewHttpsUrlShowsFallbackUntilNewImageArrives() {
        host.launch()
        assertEquals(listOf(MeAvatarImages.A), host.images.requests.map { it.url })
        host.finish(0, Color.MAGENTA)
        rule.assertAvatarImage(host, Color.MAGENTA)
        host.refresh(MeAvatarImages.B)
        assertEquals(listOf(MeAvatarImages.A, MeAvatarImages.B), host.images.requests.map { it.url })
        rule.assertAvatarFallback(host)
        host.finish(1, Color.CYAN)
        rule.assertAvatarImage(host, Color.CYAN)
    }

    @Test fun sameAccountRefreshToRejectedUrlDropsPreviousImage() {
        host.launch()
        assertEquals(listOf(MeAvatarImages.A), host.images.requests.map { it.url })
        host.finish(0, Color.MAGENTA)
        rule.assertAvatarImage(host, Color.MAGENTA)
        host.refresh("http://avatar.invalid/account-a.png")
        assertEquals(listOf(MeAvatarImages.A), host.images.requests.map { it.url })
        rule.assertAvatarFallback(host)
    }
}
