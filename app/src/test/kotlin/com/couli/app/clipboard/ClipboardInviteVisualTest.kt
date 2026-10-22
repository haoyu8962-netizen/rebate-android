package com.couli.app.clipboard

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.home.HomeVisualFixtures
import com.couli.app.shell.HOME_SEARCH_ENTRY_TAG
import com.couli.core.clipboard.ClipboardUi
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
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClipboardInviteVisualTest {
    @get:Rule val rule = createComposeRule()
    private val driver by lazy { ClipboardVisualDriver(rule) }

    @After fun close() = driver.close()

    @Test fun bindCopyAndAccessibilityNameMatchTheBoard() {
        driver.launch(guest = true)
        driver.invite()
        assertEquals("绑定", ClipboardDraftTexts.text("clipboard.invite_prompt.action"))
        assertEquals("绑定邀请人", ClipboardDraftTexts.text("clipboard.invite_prompt.action_label"))
        rule.onNodeWithTag(ClipboardUi.PROMPT_ACTION).assert(hasContentDescription("绑定邀请人")).assertIsDisplayed()
    }

    @Test fun inviteStaysBetweenSearchAndGuestCardWith48DpClose() {
        driver.launch(guest = true)
        driver.invite()
        val search = rule.onNodeWithTag(HOME_SEARCH_ENTRY_TAG).fetchSemanticsNode().boundsInRoot
        val prompt =
            rule
                .onNodeWithTag(ClipboardUi.PROMPT_BAR)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val guest = rule.onNodeWithTag(HomeVisualFixtures.GUEST_CARD).fetchSemanticsNode().boundsInRoot
        assertTrue(search.bottom <= prompt.top && prompt.bottom <= guest.top)
        OverlayVisualProbe(rule).assertTarget48(rule.onNodeWithTag(ClipboardUi.PROMPT_CLOSE).assertIsDisplayed().fetchSemanticsNode())
    }

    @Test fun guestBindOnlyWritesPendingCodeWithoutLoginOrNetwork() {
        driver.launch(guest = true)
        driver.invite()
        rule.onNodeWithTag(ClipboardUi.PROMPT_ACTION).performTouchInput { click() }
        driver.host.idle()
        assertEquals(
            ClipboardVisualFixtures.INVITE,
            driver.host.clipboard.pendingInvite
                .peek(),
        )
        assertNull("APP-17c：不跳登录", driver.host.routing.lastTrace.value)
        assertEquals("不发绑定或识别请求", emptyList<Any>(), driver.host.api.calls)
        rule.onNodeWithTag(ClipboardUi.PROMPT_BAR).assertDoesNotExist()
    }

    @Test fun inviteQuestionDoesNotSplitAcrossLinesAndCloseDoesNotOverlapBind() {
        driver.launch(guest = true)
        driver.invite()
        val text = "检测到好友邀请码 ${ClipboardVisualFixtures.INVITE}，绑定为邀请人？"
        val node = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        rule.runOnUiThread { checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts) }
        assertTrue(layouts.isNotEmpty())
        val start = text.indexOf("绑定为邀请人？")
        layouts.forEach { assertEquals("问句整体换行", it.getLineForOffset(start), it.getLineForOffset(text.lastIndex)) }
        val action = rule.onNodeWithTag(ClipboardUi.PROMPT_ACTION).fetchSemanticsNode().touchBoundsInRoot
        val close = rule.onNodeWithTag(ClipboardUi.PROMPT_CLOSE).fetchSemanticsNode().touchBoundsInRoot
        assertTrue("两按钮点击区不重叠", action.right <= close.left)
    }

    @Test fun closingPromptKeepsPreviouslyConfirmedPendingCode() {
        driver.launch(guest = true)
        driver.host.clipboard.pendingInvite
            .set("AB23CD")
        driver.invite()
        rule.onNodeWithTag(ClipboardUi.PROMPT_CLOSE).performTouchInput { click() }
        driver.host.idle()
        assertEquals(
            "AB23CD",
            driver.host.clipboard.pendingInvite
                .peek(),
        )
        assertNull(driver.host.routing.lastTrace.value)
        assertTrue(
            driver.host.api.calls
                .isEmpty(),
        )
        rule.onNodeWithTag(ClipboardUi.PROMPT_BAR).assertDoesNotExist()
    }
}
