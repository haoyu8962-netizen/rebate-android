package com.couli.app.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.ClipboardScreens
import com.couli.app.shell.HOME_ASK_AI_TAG
import com.couli.app.shell.HOME_SEARCH_ENTRY_TAG
import com.couli.core.clipboard.ClipboardUi
import com.couli.core.home.HomeDraftTexts
import com.couli.core.home.HomeLevel
import com.couli.core.router.RouteSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A2–A5：真实身份、Router 和剪贴板接线；截图以外的可交互约束。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeVisualNativeTest {
    @get:Rule
    val rule = createComposeRule()
    private val host by lazy { HomeVisualTestHost(rule) }

    @After
    fun close() = host.close()

    @Test
    fun guestValueCardDisappearsImmediatelyAfterSessionBecomesMember() {
        host.launch(level = HomeLevel.GUEST)
        rule.onNodeWithTag(HomeVisualFixtures.GUEST_CARD).assertIsDisplayed()
        rule.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(HomeVisualFixtures.GUEST_CARD))).assertCountEquals(1)
        assertEquals("下单前先在凑狸查返利", HomeDraftTexts.text("home.guest_value.desc"))
        assertEquals("登录领返利", HomeDraftTexts.text("home.guest_value.login"))
        host.port.hold = CompletableDeferred()
        host.login()
        rule.onNodeWithTag(HomeVisualFixtures.GUEST_CARD).assertDoesNotExist()
        rule.onNodeWithTag(HomeVisualFixtures.GUEST_LOGIN).assertDoesNotExist()
    }

    @Test
    fun memberHasNoValueCard() {
        host.launch()
        rule.onNodeWithTag(HomeVisualFixtures.GUEST_CARD).assertDoesNotExist()
    }

    @Test
    fun basicModeShowsGuestValueButAllHomeRequestsStayAnonymous() {
        host.tokens.save("stale-access", "stale-refresh", "full", "stale-user")
        host.launch(level = HomeLevel.BASIC)
        rule.onNodeWithTag(HomeVisualFixtures.GUEST_CARD).assertIsDisplayed()
        assertTrue(host.port.pageCalls.isNotEmpty())
        assertTrue(host.port.itemCalls.isNotEmpty())
        assertTrue(host.port.pageCalls.all { it.anonymous && it.expectedAccount == null })
        assertTrue(host.port.itemCalls.all { it.second.anonymous && it.second.expectedAccount == null })
    }

    @Test
    fun guestLoginUsesRouterWithInAppSource() {
        host.launch(level = HomeLevel.GUEST)
        rule.onNodeWithTag(HomeVisualFixtures.GUEST_LOGIN).performClick()
        host.idle()
        val outcome = checkNotNull(host.routing.router.lastOutcome.value)
        assertEquals("Login", outcome.request.route)
        assertEquals(RouteSource.IN_APP.raw, outcome.request.source)
    }

    @Test
    fun searchEntryUsesTheDraftKeyAndButtonSemantics() {
        host.launch()
        val text = HomeDraftTexts.text("search.placeholder")
        assertEquals("搜商品名，或粘贴商品链接", text)
        rule
            .onNodeWithTag(HOME_SEARCH_ENTRY_TAG)
            .assert(hasText(text))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(!SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
    }

    @Test
    fun askAiUsesSubtleSurfaceAndRemainsClickable() {
        host.launch()
        val node = rule.onNodeWithTag(HOME_ASK_AI_TAG).assert(hasClickAction()).fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            rule.runOnUiThread { view.draw(Canvas(bitmap)) }
            val bounds = node.boundsInRoot
            // 中央上缘内侧避开图标、文字和圆角，实心主按钮在这里会失败；坐标相对根 View。
            val pixel = bitmap.getPixel(((bounds.left + bounds.right) / 2).toInt(), (bounds.top + 4).toInt())
            assertEquals(host.aiBackground.toArgb(), pixel)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun closedAiEntryFlagHidesTheHomeButton() {
        host.config = Json.parseToJsonElement("""{"features":{"flags":{"agent.entry.visible":false}}}""").jsonObject
        host.launch()
        rule.onNodeWithTag(HOME_ASK_AI_TAG).assertDoesNotExist()
    }

    @Test
    fun automaticReadOffHasOnePasteCardAndOneButton() {
        host.launch(autoRead = false)
        assertPasteCard()
    }

    @Test
    fun automaticReadOnHasTheSameSinglePasteButton() {
        host.launch(autoRead = true)
        assertPasteCard()
    }

    private fun assertPasteCard() {
        val inCard = hasAnyAncestor(hasTestTag(ClipboardUi.HOME_PASTE_CARD))
        rule.onAllNodesWithTag(ClipboardUi.HOME_PASTE_CARD).assertCountEquals(1)
        rule.onNodeWithTag(ClipboardUi.HOME_PASTE_CARD).assertHasNoClickAction()
        rule.onAllNodesWithTag(ClipboardScreens.HOME_PASTE_BUTTON).assertCountEquals(1)
        rule.onNodeWithTag(ClipboardScreens.HOME_PASTE_BUTTON).assert(inCard).assertTextEquals("粘贴查返利")
        rule.onAllNodes(hasClickAction() and inCard).assertCountEquals(1)
        rule.onAllNodes(hasClickAction() and hasText("粘贴查返利")).assertCountEquals(1)
        val outsideIllustration = !hasAnyAncestor(hasTestTag(HomeVisualFixtures.PASTE_ILLUSTRATION))
        rule
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and inCard and outsideIllustration,
                useUnmergedTree = true,
            ).assertCountEquals(2)
        rule.onAllNodes(hasText("支持淘宝、京东、拼多多", substring = true), useUnmergedTree = true).assertCountEquals(0)
        rule
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and hasText("粘贴查返利") and inCard,
                useUnmergedTree = true,
            ).assertCountEquals(1)
        val illustration = rule.onNodeWithTag(HomeVisualFixtures.PASTE_ILLUSTRATION, useUnmergedTree = true)
        illustration
            .assertExists()
            .assertHasNoClickAction()
            .assert(!SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        rule
            .onAllNodes(
                hasAnyAncestor(hasTestTag(HomeVisualFixtures.PASTE_ILLUSTRATION)) and
                    (
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription) or
                            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)
                    ),
            ).assertCountEquals(0)
    }
}
