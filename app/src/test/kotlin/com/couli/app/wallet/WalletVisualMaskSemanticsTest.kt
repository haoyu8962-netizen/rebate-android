package com.couli.app.wallet

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.AmountCell
import com.couli.core.designsystem.AmountGrid
import com.couli.core.designsystem.AmountMask
import com.couli.core.designsystem.AmountValue
import com.couli.core.designsystem.CouliTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletVisualMaskSemanticsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun w17HiddenReceivedIsAnIndependentReadoutInBothTrees() {
        WalletVisualTestHost(rule).use { host ->
            host.launch(hidden = true)
            rule.walletText("其中已收货 ****").assertExists()
            listOf(false, true).forEach { unmerged ->
                rule.assertWalletHiddenReadouts(listOf("其中已收货 金额已隐藏"), unmerged)
            }
            host.assertSummaryReads(1)
        }
    }

    @Test fun w17HiddenNegativeRetainsItsOwnReadout() {
        WalletVisualTestHost(rule).use { host ->
            host.launch("negative", hidden = true)
            listOf(false, true).forEach { unmerged ->
                rule.assertWalletHiddenReadouts(listOf("待抵扣 金额已隐藏"), unmerged)
            }
            host.assertSummaryReads(1)
        }
    }

    @Test fun sharedAmountCellDefaultsStillSuppressHiddenNoteAndRestoreVisibleNote() {
        val hidden = mutableStateOf(false)
        // 不传任何新增读屏参数；共享组件旧调用方的遮罩说明仍不单独读，钱包必须显式选择新读法。
        val cell =
            AmountCell.of(
                label = "预估收益",
                value = AmountValue.Amount(5678),
                note = "其中已收货 ¥34.56",
                hiddenNote = "其中已收货 ****",
            )
        rule.setContent {
            CouliTheme {
                AmountGrid(cells = listOf(cell), mask = AmountMask(hidden.value, "****", "金额已隐藏"))
            }
        }
        listOf(false, true).forEach { unmerged ->
            rule.onNodeWithText("其中已收货 ¥34.56", useUnmergedTree = unmerged).assertExists()
        }
        rule.runOnIdle { hidden.value = true }
        rule.walletText("其中已收货 ****").assertExists()
        listOf(false, true).forEach { unmerged ->
            rule.assertWalletHiddenReadouts(listOf("预估收益，金额已隐藏"), unmerged)
            rule.onNodeWithText("其中已收货 ****", useUnmergedTree = unmerged).assertDoesNotExist()
            rule.onNodeWithText("其中已收货 ¥34.56", useUnmergedTree = unmerged).assertDoesNotExist()
            rule.onNodeWithContentDescription("其中已收货 金额已隐藏", useUnmergedTree = unmerged).assertDoesNotExist()
        }
        rule.runOnIdle { hidden.value = false }
        listOf(false, true).forEach { unmerged ->
            rule.onNodeWithText("其中已收货 ¥34.56", useUnmergedTree = unmerged).assertExists()
        }
    }
}
