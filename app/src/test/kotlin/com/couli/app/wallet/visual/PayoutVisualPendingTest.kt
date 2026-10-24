package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.productPixels
import com.couli.core.network.ApiResult
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** W83 / W85：收款账号页的待确认一族（c 的共用件），e 核对读取各态下仍可操作、摘要只用本地脱敏值、已完成提示用信息图标；计数与原键不变。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PayoutVisualPendingTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PayoutIdentityVisualHost(rule) }
    private val p by lazy { PayoutIdentityProbe(rule) }

    @After fun close() = host.close()

    @Test fun w83PendingConfirmWorksWhilePayoutStillLoading() {
        host.api.readHold = CompletableDeferred()
        launch()
        tap(WalletTags.PENDING_CONFIRM)
        assertEquals("确认恰一次", 1, host.api.confirms.size)
        assertEquals(
            "原键",
            "visual-payout-original",
            host.api.confirms
                .single()
                .key,
        )
        assertEquals("确认不生成新键、不发保存", 0, host.api.writes().size)
        assertEquals(0, host.keys)
    }

    @Test fun w83PendingAbandonWorksAfterPayoutLoadFailedAndCancelWritesNothing() {
        launch(WithdrawVisualFixtures.failure())
        p.tag(WalletTags.PAYOUT_LOAD_ERROR).assertExists()
        tap(WalletTags.PENDING_ABANDON)
        rule.onNodeWithText(WalletDraftTexts.text("pending_confirm.abandon.cancel")).performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals("再想想零请求", 0, host.api.abandons.size + host.api.confirms.size)
        tap(WalletTags.PENDING_ABANDON)
        rule.onNodeWithText(WalletDraftTexts.text("pending_confirm.abandon.ok")).performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals("确定放弃恰一次", 1, host.api.abandons.size)
        assertEquals(
            "visual-payout-original",
            host.api.abandons
                .single()
                .key,
        )
        assertEquals(0, host.api.confirms.size + host.api.writes().size)
    }

    @Test fun w83PendingSummaryShowsOnlyLocalMaskedValues() {
        launch()
        val drawn = rule.drawnTexts().joinToString("\n")
        assertTrue("待确认摘要显示本地脱敏账号", drawn.contains("138****5678"))
        assertFalse("不得显示请求体里的完整登录号（BR-ID-33）", drawn.contains("13800005678"))
        assertFalse("不得显示请求体里的完整姓名", drawn.lines().any { it.contains("张三") })
    }

    @Test fun w85CompletedNoticeUsesSecondaryInfoIconNotSuccessGreen() {
        launch()
        tap(WalletTags.PENDING_ABANDON)
        host.api.pendingHold = CompletableDeferred()
        host.api.pendingAnswer = PayoutIdentityVisualFixtures.ok()
        rule.onNodeWithText(WalletDraftTexts.text("pending_confirm.abandon.ok")).performTouchInput { click(center) }
        rule.runOnIdle {
            host.api.pending = null
            host.api.pendingHold!!.complete(Unit)
        }
        rule.waitForIdle()
        toastIcon(completed = true)
    }

    @Test fun w85AbandonedNoticeKeepsSuccessGreen() {
        launch()
        tap(WalletTags.PENDING_ABANDON)
        host.api.pendingHold = CompletableDeferred()
        host.api.pendingAnswer = ApiResult.Abandoned("visual-payout-original", PayoutIdentityVisualFixtures.TRACE)
        rule.onNodeWithText(WalletDraftTexts.text("pending_confirm.abandon.ok")).performTouchInput { click(center) }
        rule.runOnIdle {
            host.api.pending = null
            host.api.pendingHold!!.complete(Unit)
        }
        rule.waitForIdle()
        toastIcon(completed = false)
    }

    private fun toastIcon(completed: Boolean) {
        val node = rule.onNodeWithTag(WalletTags.TOAST).fetchSemanticsNode()
        val d = node.layoutInfo.density.density
        val b = node.boundsInRoot
        val icon = Rect(b.left + 12 * d, b.center.y - 10 * d, b.left + 32 * d, b.center.y + 10 * d)
        val image = rule.productPixels()
        val success = host.color("status.success.text").argb()
        val secondary = host.color("text.secondary").argb()
        if (completed) {
            assertEquals("W85 已完成不再画成功绿", 0, image.recordColorCount(icon, success))
            assertTrue("W85 次文字色信息图标", image.recordColorCount(icon, secondary) > 8)
        } else {
            assertTrue("已放弃仍是成功绿对勾", image.recordColorCount(icon, success) > 8)
        }
        assertEquals("放弃恰一次", 1, host.api.abandons.size)
        assertEquals(0, host.api.confirms.size + host.api.writes().size)
    }

    private fun launch(payout: ApiResult = PayoutIdentityVisualFixtures.ok(PayoutIdentityVisualFixtures.payout("boundAlipay"))) {
        host.api.pending = WithdrawVisualFixtures.pending(payout = true)
        host.api.payout = payout
        host.launch(PayoutIdentityPage.PAYOUT)
    }

    private fun tap(tag: String) {
        rule
            .onNodeWithTag(tag)
            .scrollToIfScrollable()
            .performTouchInput { click(center) }
        rule.waitForIdle()
    }
}
