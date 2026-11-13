package com.couli.app.numwrap

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.me.MeVisualFixtures
import com.couli.app.me.MeVisualTestHost
import com.couli.app.wallet.WalletVisualFixtures
import com.couli.app.wallet.WalletVisualTestHost
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliTextStyles
import com.couli.feature.me.MeTags
import com.couli.feature.wallet.WalletTags
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 缺陷 #2（WalletHeroCard，钱包与「我的」共用）真实页面接线：可提现余额大字 34/700 在 200% 下整段不断（放不下时整体换行或框内横向滚动，
 * 由实现者选），字号保持 amountDisplay 令牌。字段断言限定在主卡容器（`wallet_hero` / `me_balance`）之下、
 * 完整值恰好 1 处；全页扫描只作补充。压力值 ¥999999999.99（brief §3.1），「我的」页的压力补到能测出问题的长度（原截图只到 ¥99999.99）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumWrapWalletMePageTest {
    @get:Rule val rule = createComposeRule()
    private var wallet: WalletVisualTestHost? = null
    private var me: MeVisualTestHost? = null

    @After fun close() {
        wallet?.close()
        me?.close()
    }

    // ---------------------------------------------------------------- 钱包（state-long-font200-w320-light）

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun walletW320Font200LightMaxAmount() = walletCase(MAX_FEN, dark = false)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun walletW320Font200LightQaAmount() = walletCase(QA_FEN, dark = false)

    @Test fun walletW390Font200LightMaxAmount() = walletCase(MAX_FEN, dark = false)

    @Test fun walletW390Font200DarkMaxAmount() = walletCase(MAX_FEN, dark = true)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun walletW320Font200WholePageScan() {
        launchWallet(MAX_FEN, dark = false)
        assertTrue("全页扫描至少要扫到余额", NumWrap.scanPage(rule, "钱包 w320×200%") >= 1)
    }

    // ---------------------------------------------------------------- 我的（Me-font200-*）

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun meW320Font200LightMaxAmount() = meCase(MAX_FEN, dark = false)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun meW320Font200LightQaAmount() = meCase(QA_FEN, dark = false)

    @Test fun meW390Font200LightMaxAmount() = meCase(MAX_FEN, dark = false)

    @Test fun meW390Font200DarkMaxAmount() = meCase(MAX_FEN, dark = true)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun meW320Font200WholePageScan() {
        launchMe(MAX_FEN, dark = false)
        assertTrue("全页扫描至少要扫到余额", NumWrap.scanPage(rule, "我的 w320×200%") >= 1)
    }

    private fun walletCase(
        fen: Long,
        dark: Boolean,
    ) {
        launchWallet(fen, dark)
        val field = NumField(CouliFormat.money(fen), "钱包主卡（${mode(dark)}）", NumWrap.within(WalletTags.WALLET_HERO), HERO, exact = true)
        NumWrap.assertField(rule, field)
    }

    private fun meCase(
        fen: Long,
        dark: Boolean,
    ) {
        launchMe(fen, dark)
        val field = NumField(CouliFormat.money(fen), "我的页余额卡（${mode(dark)}）", NumWrap.within(MeTags.ME_BALANCE), HERO, exact = true)
        NumWrap.assertField(rule, field)
    }

    private fun launchWallet(
        fen: Long,
        dark: Boolean,
    ) {
        val host = WalletVisualTestHost(rule).also { wallet = it }
        val summary = WalletVisualFixtures.summary("long_amounts")
        host.api.answer =
            WalletVisualFixtures.ok(
                JsonObject(summary + ("withdrawable_fen" to JsonPrimitive(fen)) + ("available_fen" to JsonPrimitive(fen))),
            )
        host.launch("long_amounts", dark = dark, fontScale = FONT_SCALE, statusBar = true)
    }

    private fun launchMe(
        fen: Long,
        dark: Boolean,
    ) {
        val host = MeVisualTestHost(rule).also { me = it }
        val profile = MeVisualFixtures.profile("me_long")
        val balance = profile.getValue("balance").jsonObject
        host.api.answer =
            MeVisualFixtures.ok(
                JsonObject(profile + ("balance" to JsonObject(balance + ("withdrawable_fen" to JsonPrimitive(fen))))),
            )
        host.launch("me_long", dark = dark, fontScale = FONT_SCALE, statusBar = true)
    }

    private fun mode(dark: Boolean) = if (dark) "深" else "浅"

    private companion object {
        const val FONT_SCALE = 2f
        val HERO = NumToken(CouliTextStyles.amountDisplay.fontSize, FONT_SCALE)

        /** ¥999999999.99（brief §3.1 金额压力值）。 */
        const val MAX_FEN = 99_999_999_999L

        /** ¥9876543.21：QA-11 截图 wallet-v/state-long-font200-w320-light 里断成「¥9876543.2 / 1」的那串。 */
        const val QA_FEN = 987_654_321L
    }
}
