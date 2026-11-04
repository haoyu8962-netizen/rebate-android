package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
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
 * FU-AND-0730 新增截图入口（只登记、测试作者不录基准；类名以 WithdrawRecordsVisualSnapshotTest 结尾，随 verify.sh 既有
 * `--tests "*WithdrawRecordsVisualSnapshotTest"` 一并核对）：
 * - K1 手续费 0 的已到账展开卡（paid 样例，fee_fen=0）；
 * - K2 未通过（REJECTED，支付宝）展开卡（含客服行）。
 * 既有基准一张不改。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Fu0730WithdrawRecordsVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawRecordsVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun paidFeeZeroExpanded() = page("WithdrawRecordsPaid-fee0-FU0730-light", "paid", "PAID")

    @Test fun rejectedExpanded() = page("WithdrawRecordsRejected-expanded-FU0730-light", "rejected", "REJECTED")

    private fun page(
        name: String,
        fixture: String,
        expand: String,
    ) {
        host.launch(fixture, shell = true)
        host.toggle(expand)
        rule.onNodeWithTag(WalletTags.RECORDS_LIST).performScrollToIndex(0)
        rule.onNodeWithTag(WalletTags.RECORD_DETAIL_PREFIX + expand).assertIsDisplayed()
        rule.onNodeWithTag(WalletTags.RECORDS_PAGE).assertIsDisplayed()
        rule.onRoot().captureRoboImage("src/test/snapshots/records-v/$name.png")
    }
}
