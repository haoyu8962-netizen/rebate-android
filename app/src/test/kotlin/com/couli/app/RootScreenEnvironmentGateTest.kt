package com.couli.app

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.generated.DefaultTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 启动清理失败 → 首屏只显示阻断页，不组合正常内容；【重试】成功后进入正常首屏（R2-01）。
 * 用普通 Application：闸门由测试直接构造，不经 Hilt。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class RootScreenEnvironmentGateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun blockedTextsComeFromTheContractDictionary() {
        val message = DefaultTexts.fallback(EnvironmentBlockedTexts.MESSAGE_FALLBACK_KEY)
        val retry = DefaultTexts.text(EnvironmentBlockedTexts.RETRY_TEXT_KEY)
        assertFalse(message.isNullOrBlank())
        assertFalse(retry.isNullOrBlank())
        assertEquals(message, EnvironmentBlockedTexts.message)
        assertEquals(retry, EnvironmentBlockedTexts.retry)
    }

    @Test
    fun failedCleanupShowsOnlyTheBlockedPageAndRetryEntersTheNormalScreen() {
        var failures = EnvironmentGate.STARTUP_ATTEMPTS + 1
        var attempts = 0
        val gate =
            EnvironmentGate {
                attempts++
                check(failures-- <= 0) { "cleanup failed" }
                EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED
            }
        assertTrue(gate.settleAtStartup() is EnvironmentGate.State.Blocked)
        assertEquals(EnvironmentGate.STARTUP_ATTEMPTS, attempts)

        composeRule.setContent {
            val state by gate.state.collectAsState()
            RootScreen(gateState = state, onRetry = { gate.retry() }, settledContent = { NormalContent() })
        }

        composeRule.onNodeWithTag(ENVIRONMENT_BLOCKED_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(NORMAL_CONTENT_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(EnvironmentBlockedTexts.message).assertIsDisplayed()
        composeRule.onNodeWithText(EnvironmentBlockedTexts.retry).assertIsDisplayed()

        // 第一次重试仍失败：留在阻断页
        composeRule.onNodeWithTag(ENVIRONMENT_RETRY_TAG).performClick()
        composeRule.waitForIdle()
        assertTrue(gate.state.value is EnvironmentGate.State.Blocked)
        composeRule.onNodeWithTag(ENVIRONMENT_BLOCKED_TAG).assertIsDisplayed()

        // 再次重试成功：进入正常首屏，阻断页消失
        composeRule.onNodeWithTag(ENVIRONMENT_RETRY_TAG).performClick()
        composeRule.waitForIdle()
        assertTrue(gate.isSettled)
        composeRule.onNodeWithTag(ENVIRONMENT_BLOCKED_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(NORMAL_CONTENT_TAG).assertIsDisplayed()
        assertEquals(EnvironmentGate.STARTUP_ATTEMPTS + 2, attempts)
    }

    @Test
    fun settledStateDoesNotShowTheBlockedPage() {
        composeRule.setContent {
            RootScreen(
                gateState = EnvironmentGate.State.Settled(EnvironmentStartupReconciler.Outcome.UNCHANGED),
                onRetry = {},
                settledContent = { NormalContent() },
            )
        }

        composeRule.onNodeWithTag(NORMAL_CONTENT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ENVIRONMENT_BLOCKED_TAG).assertDoesNotExist()
    }

    /** 代替 VariantRootContent（它经 Hilt 取依赖）的正常内容标记。 */
    @Composable
    private fun NormalContent() {
        Box(modifier = Modifier.fillMaxSize().testTag(NORMAL_CONTENT_TAG))
    }

    private companion object {
        const val NORMAL_CONTENT_TAG = "normal_content"
    }
}
