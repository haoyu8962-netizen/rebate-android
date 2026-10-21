package com.couli.app.home

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.home.HomeLevel
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** APP-11v-a：整页真实接线，54dp 状态栏留白；只声明基准路径，由实现者实现后录图。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h2359dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeVisualSnapshotTest {
    @get:Rule
    val rule = createComposeRule()
    private val host by lazy { HomeVisualTestHost(rule) }

    @After
    fun close() = host.close()

    @Test
    fun mainRankEntryLayout() = capture("MainRankEntry-v3-light", rank = true)

    @Test
    fun mainLight() = capture("Main-v3-light")

    @Test
    @Config(qualifiers = "w390dp-h2823dp-xhdpi")
    fun guestLight() = capture("HomeGuest-v3-light", guest = true)

    @Test
    @Config(qualifiers = "w390dp-h1199dp-xhdpi")
    fun mainDarkBoard() = capture("MainDark-v3-dark", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h2823dp-xhdpi")
    fun guestDark() = capture("Guest-v3-dark", guest = true, dark = true)

    @Test
    fun mainDarkFullPage() = capture("Main-v3-dark", dark = true)

    @Test
    fun mainFont200At390() = capture("Main-v3-font200-w390-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h2359dp-xhdpi")
    fun mainFont200At320() = capture("Main-v3-font200-w320-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w840dp-h1400dp-xhdpi")
    fun mainLarge() = capture("Main-v3-w840-light")

    private fun capture(
        name: String,
        guest: Boolean = false,
        dark: Boolean = false,
        fontScale: Float = 1f,
        rank: Boolean = false,
    ) {
        host.port.page = if (rank) HomeVisualFixtures.rankPage() else HomeVisualFixtures.page(if (guest) "guest_v3" else "main_v3")
        host.launch(level = if (guest) HomeLevel.GUEST else HomeLevel.MEMBER, dark = dark, fontScale = fontScale, statusBar = true)
        val cards = checkNotNull(host.home.controller.value).state.value.cards
        assertEquals(listOf("icons", "scroll", "activities", "grid", "feed"), cards.map { it.id })
        assertEquals(3, (cards[1] as com.couli.feature.home.HomeCardUi.ProductScroll).items.size)
        assertEquals(2, (cards[3] as com.couli.feature.home.HomeCardUi.ProductGrid).items.size)
        assertEquals(4, (cards[4] as com.couli.feature.home.HomeCardUi.Feed).items.size)
        rule.onRoot().captureRoboImage("src/test/snapshots/home-v/$name.png")
    }
}
