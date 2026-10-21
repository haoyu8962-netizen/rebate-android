package com.couli.feature.login

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** C10/C11：真实 Compose 事件转发、焦点与语义；不接 DS-02、不改布局基准。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class LoginScreen17aTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var f: Login17aFixture
    private val owner =
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }

    @Before
    fun start() {
        f = Login17aFixture(useTestMain = false, pumpMain = { shadowOf(Looper.getMainLooper()).idle() })
        val factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = f.model as T
            }
        ViewModelProvider(owner, factory)[LoginViewModel::class.java]
        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { LoginScreen(f.config) }
        }
    }

    @After
    fun close() {
        owner.viewModelStore.clear()
        f.close()
    }

    @Test
    fun removedEntriesAreAbsentOnBothStepsAndOnlyOneHelpEntryRemains() {
        absentLegacyEntries()
        composeRule.onNodeWithTag(LoginTags.AGREE).assertContentDescriptionEquals("login.agree.text")
        enterCodeFromScreen()
        absentLegacyEntries()
        composeRule.onNodeWithTag(LoginTags.AGREE).assertDoesNotExist()
        composeRule.onNodeWithTag("login_submit").assertDoesNotExist()
        composeRule.onNodeWithText("login.help_entry").assertDoesNotExist()
        composeRule.onAllNodesWithText("login.code.help_entry").assertCountEquals(1)
    }

    @Test
    fun secondaryConfirmationButtonClosesWithoutSendingOrChangingStep() {
        composeRule.onNodeWithTag(LoginTags.GET_CODE).performClick()
        composeRule.onNodeWithText("login.agree_confirm.btn_disagree").performClick()
        f.settle()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("login.agree_confirm.title").assertDoesNotExist()
        assertEquals(LoginStep.PHONE, f.model.state.value.step)
        assertEquals(false, f.model.state.value.agreed)
        assertEquals(0, f.entries.get())
        enterCodeFromScreen()
        assertEquals(1, f.sends())
    }

    @Test
    fun realInputClearsOn20002And20003ButKeepsFocusAndErrorAcrossRecomposition() {
        enterCodeFromScreen()
        listOf(20002, 20003).forEachIndexed { index, code ->
            composeRule.onNodeWithTag(LoginTags.CODE).performTextReplacement(if (index == 0) "123456" else "654321")
            f.until("输入触发登录") { f.logins.size == index + 1 }
            f.respond(code)
            composeRule.waitForIdle()
            composeRule
                .onNodeWithTag(LoginTags.CODE)
                .assertTextEquals("")
                .assertIsFocused()
                .assertIsEnabled()
            composeRule.onNodeWithText("error.$code").assertIsDisplayed()
            composeRule.runOnIdle { f.model.onPageVisible() }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("error.$code").assertIsDisplayed()
            assertEquals(index + 1, f.logins.size)
        }
    }

    @Test
    fun resendKeepsInputEnabledAndFocusedDuringNetworkWait() {
        enterCodeFromScreen()
        composeRule.runOnIdle { f.advance(61_000) }
        val held = CountDownLatch(1)
        try {
            f.sendAnswer = {
                check(held.await(5, TimeUnit.SECONDS))
                Login17aFixture.sent()
            }
            composeRule.onNodeWithTag(LoginTags.RESEND).performClick()
            f.until("重发已到服务") { f.sends() == 2 }
            composeRule
                .onNodeWithTag(LoginTags.CODE)
                .assertIsEnabled()
                .assertIsFocused()
                .performTextReplacement("12")
            assertEquals("12", f.model.state.value.code)
        } finally {
            held.countDown()
        }
        f.settle()
    }

    private fun enterCodeFromScreen() {
        composeRule.onNodeWithTag(LoginTags.GET_CODE).performClick()
        composeRule.onNodeWithText("login.agree_confirm.btn_get_code").performClick()
        f.until("验证码页") { f.model.state.value.step == LoginStep.CODE }
        composeRule.waitForIdle()
    }

    private fun absentLegacyEntries() {
        listOf("login_settings", "login_invite_entry", "login_invite", "login_invite_paste").forEach {
            composeRule.onNodeWithTag(it).assertDoesNotExist()
        }
        composeRule.onNodeWithText("invite.notice_inviter").assertDoesNotExist()
    }
}
