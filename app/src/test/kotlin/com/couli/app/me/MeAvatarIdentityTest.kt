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
class MeAvatarIdentityTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { MeAvatarTestHost(rule) }

    @After fun close() = host.close()

    @Test fun switchingAccountsClearsDisplayedImageBeforeNextLoadCompletes() {
        loadedA()
        host.changeAccount()
        assertEquals(listOf(MeAvatarImages.A, MeAvatarImages.B), host.images.requests.map { it.url })
        rule.assertAvatarFallback(host)
        host.finish(1, Color.CYAN)
        rule.assertAvatarImage(host, Color.CYAN)
    }

    @Test fun sameUrlAcrossAccountsMustNotKeepPreviousAccountsImageState() {
        loadedA(MeAvatarImages.SHARED)
        host.changeAccount(MeAvatarImages.SHARED)
        assertEquals(listOf(MeAvatarImages.SHARED, MeAvatarImages.SHARED), host.images.requests.map { it.url })
        rule.assertAvatarFallback(host)
        host.finish(1, Color.CYAN)
        rule.assertAvatarImage(host, Color.CYAN)
    }

    @Test fun logoutClearsAlreadyDisplayedImageAndDoesNotLoadForGuest() {
        loadedA()
        host.logout()
        rule.assertAvatarFallback(host, guest = true)
        rule.assertAvatarDecorative(guest = true)
        assertEquals(listOf(MeAvatarImages.A), host.images.requests.map { it.url })
    }

    @Test fun lateOldAccountResultCannotReplaceNewAccountImage() {
        host.images.ignoreCancellation = true
        host.launch()
        assertEquals(listOf(MeAvatarImages.A), host.images.requests.map { it.url })
        host.changeAccount()
        assertEquals(listOf(MeAvatarImages.A, MeAvatarImages.B), host.images.requests.map { it.url })
        rule.assertAvatarFallback(host)
        host.finish(1, Color.CYAN)
        rule.assertAvatarImage(host, Color.CYAN)
        host.finish(0, Color.MAGENTA)
        rule.assertAvatarImage(host, Color.CYAN)
    }

    @Test fun lateOldAccountResultCannotFillNewAccountsLoadingAvatar() {
        host.images.ignoreCancellation = true
        host.launch()
        assertEquals(listOf(MeAvatarImages.A), host.images.requests.map { it.url })
        host.changeAccount()
        assertEquals(listOf(MeAvatarImages.A, MeAvatarImages.B), host.images.requests.map { it.url })
        host.finish(0, Color.MAGENTA)
        rule.assertAvatarFallback(host)
        host.finish(1, null)
        rule.assertAvatarFallback(host)
    }

    @Test fun lateResultAfterLogoutCannotFillGuestAvatar() {
        host.images.ignoreCancellation = true
        host.launch()
        assertEquals(listOf(MeAvatarImages.A), host.images.requests.map { it.url })
        host.logout()
        rule.assertAvatarFallback(host, guest = true)
        host.finish(0, Color.MAGENTA)
        rule.assertAvatarFallback(host, guest = true)
        assertEquals(listOf(MeAvatarImages.A), host.images.requests.map { it.url })
    }

    private fun loadedA(url: String = MeAvatarImages.A) {
        host.launch(url)
        assertEquals(listOf(url), host.images.requests.map { it.url })
        host.finish(0, Color.MAGENTA)
        rule.assertAvatarImage(host, Color.MAGENTA)
    }
}
