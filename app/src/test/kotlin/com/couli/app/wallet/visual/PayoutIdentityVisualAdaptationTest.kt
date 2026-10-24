package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.assertNoTextOverflow
import com.couli.app.product.textLayout
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 200% × 390 / 320（系统字号真放大，半屏 / 弹窗窗口一起放大）与 840 宽：不截断、不缩字、按钮可达；裁切同时比尺寸与位置。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PayoutIdentityVisualAdaptationTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PayoutIdentityVisualHost(rule) }
    private val p by lazy { PayoutIdentityProbe(rule) }

    @After fun close() = host.close()

    @Test
    @Config(fontScale = 2f)
    fun boundFont200At390KeepsAccountAndChangeReadable() = bound("boundAlipay", "138****5678")

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi", fontScale = 2f)
    fun boundFont200At320KeepsAccountAndChangeReadable() = bound("boundAlipay", "138****5678")

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi", fontScale = 2f)
    fun longEmailAccountWrapsInsteadOfClippingAt320() = bound("boundStress", "zhangsan.payout.visual.stress***@example-mail.com")

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi", fontScale = 2f)
    fun formFont200At320KeepsOptionsFieldsAndSave() {
        host.api.payout = PayoutIdentityVisualFixtures.ok(PayoutIdentityVisualFixtures.payout("unbound"))
        host.launch(PayoutIdentityPage.PAYOUT, fontScale = 2f)
        listOf("payout.alipay", "payout.alipay_desc", "payout.bank", "payout.bank_desc", "payout.payee_note").forEach {
            p.text(t(it)).performScrollTo().assertNoTextOverflow()
        }
        val title = p.text(t("payout.alipay"))
        assertEquals(
            "真 200%",
            2f,
            title
                .textLayout()
                .layoutInput.density.fontScale,
            .01f,
        )
        assertTrue(
            "不缩字",
            title
                .textLayout()
                .layoutInput.style.fontSize.value >= 17f,
        )
        val save = p.tag(WalletTags.PAYOUT_SAVE).fetchSemanticsNode()
        assertTrue(save.touchBoundsInRoot.height >= 48 * save.layoutInfo.density.density)
        p.text(t("payout.save")).assertNoTextOverflow()
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi", fontScale = 2f)
    fun realNameFont200At320KeepsHeaderNotesAndSubmit() {
        host.launch(PayoutIdentityPage.REALNAME, fontScale = 2f)
        listOf("realname.intro", "realname.intro_desc", "realname.note_fixed", "realname.note_age").forEach {
            p.text(t(it)).performScrollTo().assertNoTextOverflow()
        }
        p.text(t("realname.submit")).assertNoTextOverflow()
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi", fontScale = 2f)
    fun laborFont200At320KeepsFullTextScrollableAndCheckReadable() {
        host.launch(PayoutIdentityPage.LABOR, fontScale = 2f)
        val area = p.tag(WalletTags.LABOR_TEXT).performScrollTo().fetchSemanticsNode()
        assertTrue("200% 下全文仍可滚动", area.config.contains(SemanticsActions.ScrollBy))
        p.text(t("labor.check")).assertNoTextOverflow()
        val sign = p.tag(WalletTags.LABOR_SIGN).fetchSemanticsNode()
        assertTrue(sign.touchBoundsInRoot.height >= 48 * sign.layoutInfo.density.density)
    }

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun payoutWideKeeps600CenteredColumnAndFullColumnSave() {
        host.api.payout = PayoutIdentityVisualFixtures.ok(PayoutIdentityVisualFixtures.payout("unbound"))
        host.launch(PayoutIdentityPage.PAYOUT)
        wide(WalletTags.PAYOUT_PAGE, WalletTags.PAYOUT_SAVE)
        val d = p.layoutOf(WalletTags.PAYOUT_PAGE).density.density
        assertEquals("说明句随内容列左缩进 4", 140f, p.glyphLeft(t("payout.intro")) / d, 1f)
    }

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun realNameWideKeeps600CenteredColumn() {
        host.launch(PayoutIdentityPage.REALNAME)
        wide(WalletTags.REALNAME_PAGE, WalletTags.REALNAME_SUBMIT)
    }

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun laborWideKeeps600CenteredColumn() {
        host.launch(PayoutIdentityPage.LABOR)
        wide(WalletTags.LABOR_PAGE, WalletTags.LABOR_SIGN)
    }

    private fun wide(
        page: String,
        button: String,
    ) {
        val root = p.tag(page).fetchSemanticsNode()
        val action = p.tag(button).fetchSemanticsNode()
        val d = root.layoutInfo.density.density
        assertEquals("画布是完整窗口", 840f, root.boundsInRoot.width / d, 1f)
        assertEquals("600dp 内容列左右各 16", 136f, action.boundsInRoot.left / d, 1f)
        assertEquals(568f, action.boundsInRoot.width / d, 1f)
        assertEquals(root.boundsInRoot.center.x, action.boundsInRoot.center.x, d)
    }

    private fun bound(
        fixture: String,
        account: String,
    ) {
        host.api.payout = PayoutIdentityVisualFixtures.ok(PayoutIdentityVisualFixtures.payout(fixture))
        host.launch(PayoutIdentityPage.PAYOUT, fontScale = 2f)
        val text = p.text(account).performScrollTo()
        text.assertNoTextOverflow()
        val layout = text.textLayout()
        assertEquals("真 200%", 2f, layout.layoutInput.density.fontScale, .01f)
        assertTrue("账号不缩字（22 起）", layout.layoutInput.style.fontSize.value >= 22f)
        p.text("**三").performScrollTo().assertNoTextOverflow()
        p.text(t("payout.remaining_label")).assertNoTextOverflow()
        val change = p.tag(WalletTags.PAYOUT_CHANGE).performScrollTo()
        p.text(t("payout.change")).assertNoTextOverflow()
        val node = change.fetchSemanticsNode()
        assertTrue(node.touchBoundsInRoot.height >= 48 * node.layoutInfo.density.density)
        change.performTouchInput { click(center) }
        rule.waitForIdle()
        p.tag(WalletTags.PAYOUT_LOGON).assertExists()
        assertEquals("大字号下点击仍零写", 0, host.api.writes().size)
    }

    private fun t(key: String) = WalletDraftTexts.text(key)
}
