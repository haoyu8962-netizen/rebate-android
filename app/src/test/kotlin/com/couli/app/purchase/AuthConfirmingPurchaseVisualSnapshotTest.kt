package com.couli.app.purchase

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.trade.TradeTags
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-TB-WEBCODE-B：AuthSheet「确认中」的整屏截图入口（新增状态，没有对应画板；基线由本线在锁内录，作者不运行、不录基准）。
 * 类名以 PurchaseVisualSnapshotTest 结尾，verify.sh 的 verifyRoborazziDebug 一步会带上它。不改已有截图用例与基线。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuthConfirmingPurchaseVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PurchaseVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun confirming() = capture("ProductAuthConfirming-light", dark = false)

    @Test fun confirmingDark() = capture("ProductAuthConfirming-dark", dark = true)

    private fun capture(
        name: String,
        dark: Boolean,
    ) {
        host.launch("tb_coupon_range", dark, shell = true)
        host.auth()
        rule.onNodeWithTag(TradeTags.AUTHORIZE).performClick()
        rule.waitForIdle()
        host.counts(open = 1, auth = 1 to 1)
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(300)
        captureScreenRoboImage("src/test/snapshots/product-v/purchase/$name.png")
    }
}
