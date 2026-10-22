package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliTheme
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawVisualScrimTest {
    @get:Rule val rule = createComposeRule()
    private val standard = lazy { WithdrawVisualTestHost(rule) }
    private val host get() = standard.value
    private var scrim = Color.Unspecified

    @After fun close() {
        if (standard.isInitialized()) host.close()
    }

    @Test fun withdrawRiskLightUsesOverlayToken() = wallet(false, false, false)

    @Test fun withdrawRiskDarkUsesOverlayToken() = wallet(false, false, true)

    @Test fun withdrawAbandonLightUsesOverlayToken() = wallet(false, true, false)

    @Test fun withdrawAbandonDarkUsesOverlayToken() = wallet(false, true, true)

    @Test fun payoutRiskLightUsesOverlayToken() = wallet(true, false, false)

    @Test fun payoutRiskDarkUsesOverlayToken() = wallet(true, false, true)

    @Test fun payoutAbandonLightUsesOverlayToken() = wallet(true, true, false)

    @Test fun payoutAbandonDarkUsesOverlayToken() = wallet(true, true, true)

    @Test fun defaultDialogLightKeepsLegacyDimAndCallbacks() = legacy(false)

    @Test fun defaultDialogDarkKeepsLegacyDimAndCallbacks() = legacy(true)

    @Composable
    private fun ScrimSamples() {
        scrim = CouliColors.overlayScrim
        // 位于主窗口左上角、居中弹窗以外；深色底上 .55/.6 差异太小，必须同时取遮罩下白卡。
        Row {
            Box(Modifier.size(16.dp).background(CouliColors.backgroundCanvas).testTag("withdraw_scrim_canvas"))
            Box(Modifier.size(16.dp).background(Color.White).testTag("withdraw_scrim_white"))
        }
    }

    private fun measurements(): List<WithdrawScrimSample> =
        listOf("withdraw_scrim_canvas", "withdraw_scrim_white")
            .map {
                WithdrawVisualWindowProbe(rule).scrim(rule.onNodeWithTag(it).fetchSemanticsNode())
            }.also { assertEquals("白卡采样不能落在文字/透明底上", Color.White.toArgb(), it.last().before) }

    private fun wallet(
        payout: Boolean,
        abandon: Boolean,
        dark: Boolean,
    ) {
        host.reference = { ScrimSamples() }
        if (abandon) host.api.pending = WithdrawVisualFixtures.pending(payout)
        host.launch(payout = payout, dark = dark)
        if (abandon) tap(WalletTags.PENDING_ABANDON) else submit(payout)
        val measured = measurements()
        val beforeDismiss = host.api.events.toList()
        val closeKey = if (abandon) "pending_confirm.abandon.cancel" else "withdraw.risk_blocked.ok"
        rule.onNodeWithText(WalletDraftTexts.text(closeKey)).performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals(beforeDismiss, host.api.events)
        assertEquals(if (abandon) 0 else 1, host.api.writes().size)
        assertEquals(0, host.api.confirms.size + host.api.abandons.size + host.env.appeals.size)
        assertFalse(host.flow.state.value.abandonConfirm)
        assertEquals(null, host.flow.state.value.riskDialog)
        measured.asReversed().forEach { assertScrim(it, scrim) }
    }

    private fun submit(payout: Boolean) {
        if (payout) {
            tap(WalletTags.PAYOUT_CHANGE)
            rule.onNodeWithTag(WalletTags.PAYOUT_LOGON).performScrollTo().performTextReplacement("13800005678")
            rule.onNodeWithTag(WalletTags.PAYOUT_PAYEE).performScrollTo().performTextReplacement("张三")
            tap(WalletTags.PAYOUT_SAVE)
        } else {
            host.input()
            host.settleEstimate()
            tap(WalletTags.WITHDRAW_SUBMIT)
        }
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { click(center) }
        rule.waitForIdle()
    }

    private fun legacy(dark: Boolean) {
        var confirms = 0
        var dismisses = 0
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                    ScrimSamples()
                    // 不传未来新增的可选遮罩参数；旧调用仍走系统 dim，不能被钱包全局改掉。
                    CouliDialog("旧默认对照", "确认", { confirms += 1 }, { dismisses += 1 }, dismissText = "取消")
                }
            }
        }
        val measured = measurements()
        rule.onNodeWithText("确认").performTouchInput { click(center) }
        assertEquals(1, confirms)
        assertEquals(0, dismisses)
        rule.onNodeWithText("取消").performTouchInput { click(center) }
        assertEquals(1, confirms)
        assertEquals(1, dismisses)
        measured.forEach {
            assertEquals("不传遮罩参数仍为旧系统 dim", .6f, it.dim, .001f)
            assertScrim(it, Color.Black.copy(alpha = .6f))
        }
        val white = measured.last()
        assertFalse("白卡采样须能区分令牌与旧 dim", matches(white.after, scrim.compositeOver(Color(white.before)).toArgb()))
    }

    private fun assertScrim(
        sample: WithdrawScrimSample,
        overlay: Color,
    ) {
        val expected = overlay.compositeOver(Color(sample.before)).toArgb()
        val description =
            "before=${sample.before.toUInt().toString(16)} actual=${sample.after.toUInt().toString(16)} " +
                "expected=${expected.toUInt().toString(16)} dim=${sample.dim}"
        println("WITHDRAW_SCRIM $description")
        // 同 PurchaseVisualScrimTest：只容忍 8-bit alpha 合成/四舍五入，各 ARGB 通道最多差 2。
        assertTrue("遮罩 ARGB 各通道容差≤2；$description", matches(sample.after, expected))
    }

    private fun matches(
        actual: Int,
        expected: Int,
    ): Boolean = listOf(24, 16, 8, 0).all { shift -> abs((actual ushr shift and 255) - (expected ushr shift and 255)) <= 2 }
}
