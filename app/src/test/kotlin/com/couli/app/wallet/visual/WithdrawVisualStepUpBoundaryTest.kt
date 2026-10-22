package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.wallet.WalletTags
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawVisualStepUpBoundaryTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun sixDigitsDoNotSubmitUntilExplicitVerificationThenWriteOnce() {
        host.api.write = WithdrawVisualFixtures.failure(10003)
        host.launch(token = false)
        host.input()
        rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).performClick()
        rule.waitForIdle()
        assertEquals(listOf("createWithdrawal", "sendSmsCode"), host.api.writes().map { it.operationId })
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE).performTextReplacement("123456")
        assertEquals("填满不能自动验证", listOf("createWithdrawal", "sendSmsCode"), host.api.writes().map { it.operationId })
        val keyBeforeVerify =
            host.api
                .writes()
                .first()
                .idempotencyKey
        host.api.writeHold = CompletableDeferred()
        rule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT).performClick()
        rule.waitForIdle()
        assertEquals(listOf("createWithdrawal", "sendSmsCode", "stepUp", "createWithdrawal"), host.api.writes().map { it.operationId })
        assertEquals(
            keyBeforeVerify,
            host.api
                .writes()
                .last()
                .idempotencyKey,
        )
        assertEquals(
            "fixture-verified-token",
            host.api
                .writes()
                .last()
                .stepUpToken,
        )
        assertEquals(1, host.keys)
    }

    @Test fun first10003ReplaysSameKeyOnceAndSecond10003DoesNotLoop() {
        host.api.write = WithdrawVisualFixtures.failure(10003)
        host.launch()
        host.input()
        rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).performClick()
        rule.waitForIdle()
        val firstKey =
            host.api
                .writes()
                .first()
                .idempotencyKey
        assertEquals(listOf("createWithdrawal", "sendSmsCode"), host.api.writes().map { it.operationId })
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE).performTextReplacement("123456")
        rule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT).performClick()
        rule.waitForIdle()
        assertEquals(listOf("createWithdrawal", "sendSmsCode", "stepUp", "createWithdrawal"), host.api.writes().map { it.operationId })
        assertEquals(
            listOf(firstKey, firstKey),
            host.api
                .writes()
                .filter { it.operationId == "createWithdrawal" }
                .map { it.idempotencyKey },
        )
        assertEquals(1, host.keys)
        host.settleEstimate()
        assertEquals("二次10003不得自动再发码或验证", 4, host.api.writes().size)
    }
}
