package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 只登记截图入口；测试作者不得执行 record，浮层必须含独立窗口。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawVisualPayoutSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun pendingLight() = shot("pending", "PayoutAccount-shared-pending-light", false)

    @Test fun pendingDark() = shot("pending", "PayoutAccount-shared-pending-dark", true)

    @Test fun busyLight() = shot("busy", "PayoutAccount-shared-busy-light", false)

    @Test fun stillLight() = shot("still", "PayoutAccount-shared-still-light", false)

    @Test fun abandonLight() = shot("abandon", "PayoutAccount-shared-abandon-light", false)

    @Test fun abandonDark() = shot("abandon", "PayoutAccount-shared-abandon-dark", true)

    @Test fun abandonedLight() = shot("abandoned", "PayoutAccount-shared-abandoned-light", false)

    @Test fun completedLight() = shot("completed", "PayoutAccount-shared-completed-light", false)

    @Test fun riskLight() = shot("risk", "PayoutAccount-shared-risk-light", false)

    @Test fun riskDark() = shot("risk", "PayoutAccount-shared-risk-dark", true)

    @Test fun nameerrorLight() = shot("name_error", "PayoutAccount-shared-name_error-light", false)

    @Test fun accounterrorLight() = shot("account_error", "PayoutAccount-shared-account_error-light", false)

    private fun shot(
        scene: String,
        name: String,
        dark: Boolean,
    ) {
        val overlay = WithdrawVisualScenes(rule, host).show(scene, payout = true, dark = dark)
        val path = "src/test/snapshots/withdraw-v/$name.png"
        if (overlay) captureScreenRoboImage(path) else rule.onRoot().captureRoboImage(path)
    }
}
