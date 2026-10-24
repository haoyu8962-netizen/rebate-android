package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.productPixels
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** W77 / W78 / W86：已绑定卡；更换按钮置灰只加拒绝（G-W21），change(shownVersion, account) 票据与零写由真实点击守住。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PayoutVisualBoundTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PayoutIdentityVisualHost(rule) }
    private val p by lazy { PayoutIdentityProbe(rule) }

    @After fun close() = host.close()

    @Test fun w69BoundCardStartsEightBelowNavbar() {
        bound("boundAlipay")
        val d = density()
        val card = p.tag(WalletTags.PAYOUT_BOUND).fetchSemanticsNode().boundsInRoot
        assertEquals(8f, (card.top - p.navBottom(t("payout.title"))) / d, 1f)
        assertEquals(16f, card.left / d, 1f)
    }

    @Test fun w77HeaderRowHasCurrentLabelAndNeutralMethodChipOnTheRight() = chip("boundAlipay", t("payout.channel.alipay"))

    @Test fun w77BankHeaderChipSaysBankCard() = chip("boundBank", t("payout.channel.bank_card"))

    @Test fun w77AccountIsTwentyTwoSemiboldTabular() {
        bound("boundAlipay")
        val style = p.style("138****5678")
        assertEquals("W77 账号 22", 22f, style.fontSize.value, .01f)
        assertEquals("W77 账号 600", FontWeight.W600, style.fontWeight)
        assertTrue("W77 账号等宽数字", style.fontFeatureSettings.orEmpty().contains("tnum"))
    }

    @Test fun w77BankAccountLineKeepsServerMaskedValues() {
        bound("boundBank")
        val style = p.style("招商银行 尾号 1234")
        assertEquals(22f, style.fontSize.value, .01f)
        assertTrue(style.fontFeatureSettings.orEmpty().contains("tnum"))
    }

    @Test fun w77HairlineSeparatesAccountFromDetails() {
        bound("boundAlipay")
        val d = density()
        val account = p.bounds("138****5678")
        val payee = p.bounds(t("payout.payee_label"))
        val line = rule.productPixels().boxOf(Rect(32 * d, account.bottom, 358 * d, payee.top), host.color("border.subtle").argb(), 4)
        assertNotNull("W77 账号与明细之间 1dp 分隔线", line)
        assertTrue("分隔线横贯卡内", line!!.width >= 324 * d)
        assertTrue(line.height <= 2 * d)
    }

    @Test fun w77DetailRowsAreJustifiedWithTabularValuesOnTheRight() {
        bound("boundAlipay")
        val d = density()
        assertEquals("标签靠左（卡 16 + 内距 16）", 32f, p.glyphLeft(t("payout.payee_label")) / d, 1f)
        listOf("**三", WalletDraftTexts.format("payout.remaining", mapOf("n" to "2"))).forEach {
            assertEquals("W77 值靠右：$it", 358f, p.glyphRight(it) / d, 1.5f)
            assertTrue(
                "W77 值等宽：$it",
                p
                    .style(it)
                    .fontFeatureSettings
                    .orEmpty()
                    .contains("tnum"),
            )
            assertEquals(host.color("text.primary"), p.style(it).color)
        }
        assertEquals(host.color("text.secondary"), p.style(t("payout.payee_label")).color)
        assertEquals(14f, p.style(t("payout.remaining_label")).fontSize.value, .01f)
    }

    @Test fun w77ChangeIsOutlinedSecondaryButton() {
        bound("boundAlipay")
        val d = density()
        val button = p.tag(WalletTags.PAYOUT_CHANGE).fetchSemanticsNode().boundsInRoot
        val pixels = rule.productPixels()
        assertTrue(
            "W77 【更换收款账号】是白底描边次按钮，不是品牌实心",
            near(pixels.pixel(button.left + 8 * d, button.center.y), host.color("background.surface").argb(), 4),
        )
        val edge = Rect(button.left, button.top + 12 * d, button.left + 2 * d, button.bottom - 12 * d)
        assertTrue("描边为控件边色", pixels.countNear(edge, host.color("border.control").argb(), 30) >= 8 * d)
        assertTrue("按钮高 ≥48", button.height >= 48 * d)
    }

    @Test fun w77CannotUnbindNoteIsInfoBarBelowTheCard() {
        bound("boundAlipay")
        val d = density()
        val note = t("payout.bound_note")
        val button = p.tag(WalletTags.PAYOUT_CHANGE).fetchSemanticsNode().boundsInRoot
        assertTrue("W77 说明在卡外：离按钮底 ≥ 卡内距 + 块间距", p.bounds(note).top - button.bottom >= 20 * d)
        val style = p.style(note)
        assertEquals(14f, style.fontSize.value, .01f)
        assertEquals(host.color("text.primary"), style.color)
        val pixels = rule.productPixels()
        assertTrue("INFO 浅灰底", near(pixels.pixel(18 * d, p.bounds(note).center.y), host.color("background.muted").argb(), 2))
        val left = p.glyphLeft(note)
        val top = p.bounds(note).top
        assertTrue(
            "INFO 次文字色图标",
            pixels.countNear(Rect(left - 30 * d, top, left - 4 * d, top + 22 * d), host.color("text.secondary").argb(), 40) > 12 * d,
        )
    }

    @Test fun w78ExhaustedChangeIsDisabledReadsReasonAndRealTouchWritesNothing() {
        bound("boundExhausted")
        val change = p.tag(WalletTags.PAYOUT_CHANGE)
        change.assertIsNotEnabled()
        val config = change.fetchSemanticsNode().config
        val spoken =
            (
                config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                    config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            ).joinToString(" ")
        val reason = WalletDraftTexts.format("payout.remaining", mapOf("n" to "0"))
        assertTrue("G-W21 置灰时读出原因（本月还能变更 0 次）：$spoken", spoken.contains(reason))
        change.performTouchInput { click(center) }
        rule.waitForIdle()
        p.tag(WalletTags.PAYOUT_LOGON).assertDoesNotExist()
        assertEquals(0, host.api.writes().size)
        assertEquals(0, host.keys)
    }

    @Test fun w78RemainingChangeOpensFormWithZeroWrites() {
        bound("boundAlipay")
        p.tag(WalletTags.PAYOUT_CHANGE).assertIsEnabled().performTouchInput { click(center) }
        rule.waitForIdle()
        p.tag(WalletTags.PAYOUT_LOGON).assertExists()
        assertEquals("更换只进表单，不发请求", 0, host.api.writes().size)
        assertEquals(0, host.keys)
        assertEquals(1, host.api.calls.count { it.operationId == "getPayoutAccount" })
    }

    @Test fun w86PendingKeepsBoundCardBelowWithChangeDisabled() {
        host.api.pending = WithdrawVisualFixtures.pending(payout = true)
        bound("boundAlipay")
        val pending = p.tag(WalletTags.PENDING_CONFIRM).fetchSemanticsNode().boundsInRoot
        val card = p.tag(WalletTags.PAYOUT_BOUND).fetchSemanticsNode().boundsInRoot
        assertTrue("待确认卡在已绑定卡之上", pending.bottom <= card.top)
        p.tag(WalletTags.PAYOUT_CHANGE).assertIsNotEnabled().performTouchInput { click(center) }
        rule.waitForIdle()
        p.tag(WalletTags.PAYOUT_LOGON).assertDoesNotExist()
        assertEquals(0, host.api.writes().size)
    }

    private fun chip(
        fixture: String,
        method: String,
    ) {
        bound(fixture)
        val d = density()
        val label = p.bounds(t("payout.current"))
        val labelStyle = p.style(t("payout.current"))
        assertEquals(14f, labelStyle.fontSize.value, .01f)
        assertEquals(host.color("text.secondary"), labelStyle.color)
        val chip = p.bounds(method)
        assertEquals("W77 方式胶囊与标题同一行", label.center.y, chip.center.y, 2 * d)
        assertEquals("W77 胶囊靠右（卡内右缘 358 − 内距 8）", 350f, p.glyphRight(method) / d, 2f)
        val style = p.style(method)
        assertEquals("W77 胶囊 12", 12f, style.fontSize.value, .01f)
        assertEquals(host.color("text.secondary"), style.color)
        assertTrue(
            "W77 胶囊中性浅灰底",
            near(rule.productPixels().pixel(p.glyphLeft(method) - 4 * d, chip.center.y), host.color("background.muted").argb(), 2),
        )
    }

    private fun bound(fixture: String) {
        host.api.payout = PayoutIdentityVisualFixtures.ok(PayoutIdentityVisualFixtures.payout(fixture))
        host.launch(PayoutIdentityPage.PAYOUT)
    }

    private fun density(): Float = p.layoutOf(WalletTags.PAYOUT_PAGE).density.density

    private fun t(key: String) = WalletDraftTexts.text(key)
}
