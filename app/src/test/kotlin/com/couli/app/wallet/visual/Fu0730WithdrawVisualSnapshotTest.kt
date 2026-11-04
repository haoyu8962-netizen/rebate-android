package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.wallet.WalletTags
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-0730 K3 新增截图入口（只登记、测试作者不录基准；类名以 WithdrawVisualSnapshotTest 结尾，随 verify.sh 既有
 * `--tests "*WithdrawVisualSnapshotTest"` 一并核对）：金额框输入 12.34 后再追加被拒字符 5、a，框内应仍是 12.34（估算已回来）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Fu0730WithdrawVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun amountAfterRejectedChars() {
        host.launch(shell = true)
        val box = rule.onNodeWithTag(WalletTags.WITHDRAW_INPUT)
        box.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        box.performTextInput("12.34")
        box.performTextInput("5")
        box.performTextInput("a")
        host.settleEstimate()
        rule.onRoot().captureRoboImage("src/test/snapshots/withdraw-v/Withdraw-amount-rejected-FU0730-light.png")
    }
}
