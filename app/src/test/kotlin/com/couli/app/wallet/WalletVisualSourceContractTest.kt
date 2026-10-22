package com.couli.app.wallet

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 少量静态接线检查；位置、颜色、请求与命中另由真实 Compose 用例检查，不以源码代替渲染。 */
class WalletVisualSourceContractTest {
    private val wallet = source("feature/wallet/src/main/kotlin/com/couli/feature/wallet/WalletScreen.kt")
    private val components = source("core/designsystem/src/main/kotlin/com/couli/core/designsystem/WalletComponents.kt")
    private val ui = source("feature/wallet/src/main/kotlin/com/couli/feature/wallet/WalletUi.kt")

    @Test fun w2SharedHeroContentGapDefaultsToOriginalTwelveDp() {
        assertTrue("W2 新参数保留我的页默认12", signature(components, "WalletHeroCard").contains("contentGap: Dp = CouliSpacing.space3"))
    }

    @Test fun w12SharedAmountGridCompactHelpDefaultsOff() {
        assertTrue(signature(components, "AmountGrid").contains("compactHelpRow: Boolean = false"))
    }

    @Test fun w13SharedEntryPaddingAndItemModifierKeepOldDefaults() {
        assertTrue(signature(components, "EntryGrid").contains("topPadding: Dp = CouliSpacing.space4"))
        assertTrue(components.substringAfter("data class EntryItem(").substringBefore(")").contains("modifier: Modifier = Modifier"))
    }

    @Test fun w14EarningsUsesBarChartAndNotTrend() {
        assertTrue("W14 页面使用柱状图", wallet.contains("CouliIcons.barChart"))
        assertFalse("W14 收益入口不再用折线", wallet.contains("CouliIcons.trend"))
    }

    @Test fun w8OfflineUsesWifiOffAndW9EstimateHasClock() {
        assertTrue("W8 断网图标", wallet.contains("CouliIcons.wifiOff"))
        assertTrue("W9 预估标签钟表", wallet.contains("CouliIcons.clock"))
    }

    @Test fun w15SharedHelpKeepsOldBodyAndButtonDefaultsAndWalletOptsIntoNewStyle() {
        val signature = signature(ui, "HelpSheet")
        assertTrue(signature.contains("bodyStyle: TextStyle = CouliTypography.body"))
        assertTrue(signature.contains("okVariant: CouliButtonVariant = CouliButtonVariant.PRIMARY"))
        assertTrue(wallet.contains("bodyStyle = CouliTypography.footnote"))
        assertTrue(wallet.contains("okVariant = CouliButtonVariant.SECONDARY"))
    }

    @Test fun w6StatusBarUsesTwentyDpIconsAndKeepsOptionalClick() {
        val body = ui.substringAfter("internal fun StatusBar(").substringBefore("internal fun WalletToast(")
        assertTrue("两侧功能图标20dp", body.contains("CouliSizes.iconMedium"))
        assertFalse("不能沿用16dp图标", body.contains("CouliSizes.iconSmall"))
        assertTrue("不可点形态无箭头和点击动作", body.contains("onClick != null"))
    }

    private fun signature(
        source: String,
        name: String,
    ): String = source.substringAfter("fun $name(").substringBefore(") {").replace(Regex("\\s+"), " ")

    private fun source(path: String): String = File(WalletVisualFixtures.root, path).readText()
}
