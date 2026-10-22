package com.couli.app.wallet

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.wallet.WalletTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletVisualAdaptationTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WalletVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w17At390Font200AmountsStackAndEveryTextRemainsWhole() = large()

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun w17At320Font200AmountsStackAndEveryTextRemainsWhole() = large()

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun w17At320Font200LongAmountsNeverClipOrShrink() = longAmounts()

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun w17At840AllCardsShareCentered600DpColumn() {
        host.launch()
        listOf(WalletTags.WALLET_HERO, WalletTags.WALLET_ESTIMATED).forEach {
            val node = rule.walletTag(it)
            val bounds = node.walletBounds()
            assertEquals(568f, bounds.width / node.walletDp(), 1f)
            assertEquals(136f, bounds.left / node.walletDp(), 1f)
            assertEquals(420f, bounds.center.x / node.walletDp(), 1f)
        }
    }

    @Test fun w17DarkKeepsSolidWhiteMoneyAndRebateTokenOnlyOnEstimate() {
        host.launch(dark = true)
        assertEquals(
            host.solid,
            rule
                .walletText("¥12.34")
                .walletTextLayout()
                .layoutInput.style.color,
        )
        assertEquals(
            host.solid,
            rule
                .walletText("¥5")
                .walletTextLayout()
                .layoutInput.style.color,
        )
        assertEquals(
            host.rebate,
            rule
                .walletText("¥56.78")
                .walletTextLayout()
                .layoutInput.style.color,
        )
        assertEquals(
            host.primary,
            rule
                .walletText("10 月结算")
                .walletTextLayout()
                .layoutInput.style.color,
        )
    }

    private fun large() {
        host.launch(fontScale = 2f)
        val frozen = rule.walletText("冻结中")
        val withdrawn = rule.walletText("已提现")
        frozen.performScrollTo()
        val frozenTop = frozen.walletBounds().top
        // 同一视口里取两格坐标；不要分别滚动后比较两个不同滚动偏移。
        assertTrue("200% 主卡底部两格纵排", withdrawn.walletBounds().top > frozenTop)
        rule.walletTag(WalletTags.WALLET_ESTIMATED).performScrollTo()
        assertTrue("200% 预估与月份纵排", rule.walletText("最早预计").walletBounds().top > rule.walletText("¥56.78").walletBounds().top)
        listOf("¥12.34", "提现", "提现记录", "¥56.78", "10 月结算", "其中已收货 ¥34.56", "订单", "余额流水").forEach {
            rule.walletText(it).performScrollTo().assertWalletTextWhole()
        }
        host.assertSummaryReads(1)
    }

    private fun longAmounts() {
        host.launch("long_amounts", fontScale = 2f)
        listOf("¥9876543.21", "¥8765432.1", "¥7654321.09", "¥6543210.98", "其中已收货 ¥5432109.87", "其中 ¥4321098.76 暂缓入账").forEach {
            rule.walletText(it).performScrollTo().assertWalletTextWhole()
        }
        assertEquals(
            34f,
            rule
                .walletText("¥9876543.21")
                .walletTextLayout()
                .layoutInput.style.fontSize.value,
        )
    }
}
