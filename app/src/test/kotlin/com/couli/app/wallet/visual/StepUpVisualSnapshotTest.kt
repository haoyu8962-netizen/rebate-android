package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.wallet.WalletTags
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-09v-d 截图入口（§1.1 D：WithdrawStepUp-v2 / WithdrawStepUpDark + 状态矩阵「空 / 输入中 / 错误 + 倒计时 / 提交中 / 深色 / 短视口 200%」+
 * 200% w390 / w320 + w840 + 宿主页）。只登记入口，测试作者不 record；半屏在独立窗口，一律 captureScreenRoboImage（含遮罩）。
 * 换手机号 / 注销两宿主的入口在 account 包的 StepUpVisualAccountTest（要真实设置页链路）。基准目录 src/test/snapshots/stepup-v/。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StepUpVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }
    private val kit by lazy { StepUpVisualKit(rule, host) }

    @After fun close() = host.close()

    /** 画板态：已输 2 位（「38」）、倒计时中、提交置灰。 */
    @Test fun board() = shot("WithdrawStepUp-v2-light") { typing() }

    @Test
    @Config(qualifiers = "w390dp-h1119dp-xhdpi")
    fun boardDark() = shot("WithdrawStepUpDark-dark", dark = true) { typing() }

    @Test fun empty() = shot("WithdrawStepUp-v2-empty-light")

    @Test fun emptyDark() = shot("WithdrawStepUp-v2-empty-dark", dark = true)

    @Test fun errorWithCountdown() = shot("WithdrawStepUp-v2-error-light", wrong = true) { error() }

    @Test fun errorWithCountdownDark() = shot("WithdrawStepUp-v2-error-dark", dark = true, wrong = true) { error() }

    @Test fun verifying() = shot("WithdrawStepUp-v2-verifying-light") { startVerifying() }

    @Test fun resendAvailable() = shot("WithdrawStepUp-v2-resend-light", smsFails = true)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi", fontScale = 2f)
    fun font200W390() = shot("WithdrawStepUp-v2-font200-w390-light") { typing() }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi", fontScale = 2f)
    fun font200W320() = shot("WithdrawStepUp-v2-font200-w320-light") { typing() }

    @Test
    @Config(qualifiers = "w320dp-h360dp-xxhdpi", fontScale = 2f)
    fun font200Short() = shot("WithdrawStepUp-v2-font200-short-light") { typing() }

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun wide() = shot("WithdrawStepUp-v2-w840-light") { typing() }

    @Test fun hostPayout() = shot("WithdrawStepUp-v2-host-payout-light", payout = true) { typing() }

    @Suppress("LongParameterList") // 场景开关逐项
    private fun shot(
        name: String,
        dark: Boolean = false,
        payout: Boolean = false,
        wrong: Boolean = false,
        smsFails: Boolean = false,
        then: () -> Unit = {},
    ) {
        if (wrong) host.api.stepUpAnswers += WithdrawVisualFixtures.failure(WRONG_CODE)
        if (smsFails) host.api.smsAnswers += WithdrawVisualFixtures.failure()
        kit.open(payout = payout, dark = dark)
        then()
        rule.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/stepup-v/$name.png")
    }

    private fun typing() {
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE).performTextReplacement("38")
    }

    private fun error() {
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE).performTextReplacement("000000")
        rule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT, useUnmergedTree = true).performClick()
    }

    private fun startVerifying() {
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE).performTextReplacement("123456")
        host.api.stepUpHold = CompletableDeferred()
        rule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT, useUnmergedTree = true).performClick()
    }

    private companion object {
        const val WRONG_CODE = 20002
    }
}
