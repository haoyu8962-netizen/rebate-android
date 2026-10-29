package com.couli.app.wallet

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-0728 U4（F-AND-70 ②）：三处 W92 错误条调用方的截图入口（浅 / 深各一张，共 6 张，新目录 src/test/snapshots/errorbar-v/，不动既有基准）。
 * 类名以 WalletVisualSnapshotTest 结尾：随 verify.sh 既有的 `--tests "*WalletVisualSnapshotTest"` 一步比对，不改 CI 脚本。
 * 只声明截图入口；测试作者不执行 record / verify、不生成基准（实现方录基准）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Fu0728ErrorBarsWalletVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val scenes by lazy { Fu0728ErrorBarScenes(rule) }

    @After fun close() = scenes.close()

    @Test fun walletRefreshErrorLight() = wallet("WalletRefreshError-W92-light", dark = false)

    @Test fun walletRefreshErrorDark() = wallet("WalletRefreshError-W92-dark", dark = true)

    @Test fun recordsRefreshErrorLight() = records("WithdrawRecordsRefreshError-W92-light", dark = false)

    @Test fun recordsRefreshErrorDark() = records("WithdrawRecordsRefreshError-W92-dark", dark = true)

    @Test fun openErrorLight() = open("WalletOpenError-W92-light", dark = false)

    @Test fun openErrorDark() = open("WalletOpenError-W92-dark", dark = true)

    private fun wallet(
        name: String,
        dark: Boolean,
    ) {
        scenes.walletRefreshError(dark)
        capture(name)
    }

    private fun records(
        name: String,
        dark: Boolean,
    ) {
        scenes.recordsRefreshError(dark)
        capture(name)
    }

    private fun open(
        name: String,
        dark: Boolean,
    ) {
        scenes.openError(dark)
        capture(name)
    }

    private fun capture(name: String) {
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.onRoot().captureRoboImage("src/test/snapshots/errorbar-v/$name.png")
    }

    private companion object {
        const val SETTLE_MS = 300L
    }
}
