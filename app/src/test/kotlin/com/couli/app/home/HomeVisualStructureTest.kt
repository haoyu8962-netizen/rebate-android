package com.couli.app.home

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.ClipboardResult
import com.couli.app.clipboard.ClipboardScreens
import com.couli.app.shell.HOME_SEARCH_ENTRY_TAG
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.ClipboardUi
import com.couli.core.clipboard.ManualPasteAction
import com.couli.core.home.HomeLevel
import com.couli.feature.home.HomeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A1/G-V02：用真实 AppShell 的语义树及触摸手势查单一滚动，不以调用 refresh() 代替手势。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeVisualStructureTest {
    @get:Rule
    val rule = createComposeRule()
    private val host by lazy { HomeVisualTestHost(rule) }
    private val inList = hasAnyAncestor(hasTestTag(HomeTags.LIST))

    @After
    fun close() = host.close()

    @Test
    fun promptGuestPasteSduiAndFooterShareOneVerticalListWhileSearchStaysPinned() {
        host.launch(level = HomeLevel.GUEST)
        showInvite()
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(1)
        listOf(ClipboardUi.PROMPT_BAR, HomeVisualFixtures.GUEST_CARD, ClipboardUi.HOME_PASTE_CARD).forEach {
            rule.onNodeWithTag(it).assert(inList)
        }
        val search = rule.onNodeWithTag(HOME_SEARCH_ENTRY_TAG)
        search.assert(!inList)
        val before = search.fetchSemanticsNode().boundsInRoot
        val prompt = rule.onNodeWithTag(ClipboardUi.PROMPT_BAR).fetchSemanticsNode().boundsInRoot
        val guest = rule.onNodeWithTag(HomeVisualFixtures.GUEST_CARD).fetchSemanticsNode().boundsInRoot
        val paste = rule.onNodeWithTag(ClipboardUi.HOME_PASTE_CARD).fetchSemanticsNode().boundsInRoot
        assertTrue("提示条 → 价值卡 → 粘贴卡", prompt.bottom <= guest.top && guest.bottom <= paste.top)
        val list = rule.onNodeWithTag(HomeTags.LIST)
        list.performScrollToNode(hasTestTag(HomeTags.CARD_PREFIX + "grid"))
        rule.onNodeWithTag(HomeTags.CARD_PREFIX + "grid").assert(inList)
        list.performScrollToNode(hasTestTag(HomeTags.DISCLAIMER))
        rule.onNodeWithTag(HomeTags.DISCLAIMER).assert(inList).assertIsDisplayed()
        search.assertIsDisplayed()
        assertEquals("搜索行吸顶", before, search.fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun pullFromNativeHeaderRefreshesOnlySduiAndKeepsInviteConfirmAndClipboardState() {
        host.launch(level = HomeLevel.GUEST)
        showInvite()
        val result = host.clipboard.result.value
        assertTrue("手动粘贴邀请码应显示内联确认条", result is ClipboardResult.InviteConfirm)
        val prompt = host.clipboard.prompt.value
        assertNull(prompt)
        val reads = host.system.reads
        val apiCalls = host.api.calls.size
        val before = host.port.pageCalls.size
        val itemsBefore = host.port.itemCalls.size
        rule.onNodeWithTag(HomeTags.LIST).performTouchInput { swipeDown(startY = 12f, endY = height * 0.9f, durationMillis = 600) }
        host.idle()
        assertEquals("从原生头部起手也要刷新", before + 1, host.port.pageCalls.size)
        assertTrue("商品区一并重载", host.port.itemCalls.size > itemsBefore)
        assertEquals(result, host.clipboard.result.value)
        assertEquals(prompt, host.clipboard.prompt.value)
        assertEquals(reads, host.system.reads)
        assertEquals(apiCalls, host.api.calls.size)
        rule.onNodeWithTag(HomeVisualFixtures.GUEST_CARD).assertExists()
        rule.onAllNodesWithTag(ClipboardScreens.HOME_PASTE_BUTTON).assertCountEquals(1)
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp-xhdpi")
    fun narrowFont200CanReachPasteAndFooterWithoutASecondScroller() {
        host.launch(level = HomeLevel.GUEST, fontScale = 2f)
        val list = rule.onNodeWithTag(HomeTags.LIST)
        list.performScrollToNode(hasTestTag(ClipboardScreens.HOME_PASTE_BUTTON))
        rule.onNodeWithTag(ClipboardScreens.HOME_PASTE_BUTTON).assertIsDisplayed()
        list.performScrollToNode(hasTestTag(HomeTags.DISCLAIMER))
        rule.onNodeWithTag(HomeTags.DISCLAIMER).assertIsDisplayed()
        rule.onNodeWithTag(HOME_SEARCH_ENTRY_TAG).assertIsDisplayed()
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(1)
    }

    @Test
    @Config(qualifiers = "w840dp-h1400dp-xhdpi")
    fun largeScreenCentersNativeAndSduiContentWithin600Dp() {
        host.launch(level = HomeLevel.GUEST)
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        listOf(ClipboardUi.HOME_PASTE_CARD, HomeVisualFixtures.GUEST_CARD, HomeTags.CARD_PREFIX + "icons").forEach { tag ->
            val bounds = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag 限宽 600dp", bounds.width.value <= 600f)
            assertEquals("$tag 水平居中", (root.left + root.right).value / 2, (bounds.left + bounds.right).value / 2, 1f)
        }
    }

    private fun showInvite() {
        rule.runOnUiThread {
            host.system.content = ClipRead.Text("邀请码 K7M2QX")
            host.clipboard.paste(ManualPasteAction.ENTRY_HOME)
        }
        host.idle()
    }
}
