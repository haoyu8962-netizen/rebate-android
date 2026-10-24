package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.productPixels
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.RealNameController
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

/** W89–W92、W95：实名页版式与错误条；同意记录与实名提交的发起条件、次数、身份票据不变（G-W24 仍为 Debug 手写接口）。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RealNameVisualTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PayoutIdentityVisualHost(rule) }
    private val p by lazy { PayoutIdentityProbe(rule) }

    @After fun close() = host.close()

    @Test fun w89HeaderHasFortyBrandSubtleTileWithShieldAndSeventeenSemiboldTitle() {
        host.launch(PayoutIdentityPage.REALNAME)
        val d = density()
        val title = t("realname.intro")
        val titleStyle = p.style(title)
        assertEquals(17f, titleStyle.fontSize.value, .01f)
        assertEquals(FontWeight.W600, titleStyle.fontWeight)
        val desc = p.style(t("realname.intro_desc"))
        assertEquals("W89 说明 14", 14f, desc.fontSize.value, .01f)
        assertEquals(host.color("text.secondary"), desc.color)
        assertEquals("W89 标题与说明间 4", 4f, (p.bounds(t("realname.intro_desc")).top - p.bounds(title).bottom) / d, 1f)
        val left = p.glyphLeft(title)
        val top = p.bounds(title).top
        val pixels = rule.productPixels()
        val tile = pixels.boxOf(Rect(16 * d, top - 8 * d, left, top + 48 * d), host.color("brand.subtle").argb(), 3)
        assertNotNull("W89 标题左侧 brand.subtle 方块", tile)
        tile!!
        assertEquals("W89 方块 40 宽", 40f, tile.width / d, 1f)
        assertEquals("W89 方块 40 高", 40f, tile.height / d, 1f)
        assertEquals("W89 方块与标题间 12", 12f, (left - tile.right) / d, 1.5f)
        assertEquals("W89 方块在卡内左缘（16+16）", 32f, tile.left / d, 1f)
        assertTrue("W89 方块里有品牌色盾牌图标", pixels.countNear(tile, host.color("brand.emphasis").argb(), 40) > 20 * d)
    }

    @Test fun w90LabelToBoxEightAndFieldsSixteenApart() {
        host.launch(PayoutIdentityPage.REALNAME)
        val d = density()
        val nameBox = p.fieldBox(WalletTags.REALNAME_NAME)
        assertEquals("W90 标签与框 8", 8f, (nameBox.top - p.bounds(t("realname.name")).bottom) / d, 1f)
        val idLabel = p.bounds(t("realname.id_no"))
        assertEquals("W90 字段间 16", 16f, (idLabel.top - nameBox.bottom) / d, 1f)
        assertEquals(8f, (p.fieldBox(WalletTags.REALNAME_ID).top - idLabel.bottom) / d, 1f)
    }

    @Test fun w91BothNotesAreInfoBars() {
        host.launch(PayoutIdentityPage.REALNAME)
        rule.assertInfoBar(p, host, t("realname.note_fixed"))
        rule.assertInfoBar(p, host, t("realname.note_age"))
        assertTrue("两条说明在输入卡之后", p.bounds(t("realname.note_fixed")).top > p.fieldBox(WalletTags.REALNAME_ID).bottom)
    }

    @Test fun w92FailureBarIsRedErrorToneBelowTheFormAndReadAfterFields() {
        host.reference = { ReferenceAlertIcon() }
        host.backends.submitAnswer = PayoutIdentityVisualFixtures.realnameError("verifyFailed")
        submitOnce()
        val message = t("error.30406")
        p.tag(WalletTags.REALNAME_ERROR).assertExists()
        val bar = p.tag(WalletTags.REALNAME_ERROR).fetchSemanticsNode().boundsInRoot
        assertTrue("W92 错误条在表单下方（输入卡之后）", bar.top > p.fieldBox(WalletTags.REALNAME_ID).bottom)
        assertTrue("W92 不再夹在说明卡与输入卡之间", bar.top > p.bounds(t("realname.name")).bottom)
        rule.assertErrorTone(p, host.colors, WalletTags.REALNAME_ERROR, message)
        assertTrue(
            "W92 读屏顺序随之变化：先字段后错误",
            rule.traversalIndex(WalletTags.REALNAME_ERROR) > rule.traversalIndex(WalletTags.REALNAME_ID),
        )
        assertEquals("同意后实名提交恰一次", 1, host.backends.submits.size)
        assertEquals("已填内容保留", PayoutIdentityVisualFixtures.realnameForm("name"), editable(WalletTags.REALNAME_NAME))
        assertEquals("11010119900101123X", editable(WalletTags.REALNAME_ID))
        assertEquals(0, host.backs)
    }

    @Test fun dailyLimitKeepsSubmitDisabledAfterError() {
        host.backends.submitAnswer = PayoutIdentityVisualFixtures.realnameError("dailyLimit")
        submitOnce()
        p.tag(WalletTags.REALNAME_SUBMIT).assertIsNotEnabled().performTouchInput { click(center) }
        rule.waitForIdle()
        p.tag(WalletTags.CONSENT_SHEET).assertDoesNotExist()
        assertEquals("30410 当日置灰，不再提交", 1, host.backends.submits.size)
    }

    @Test fun fieldErrorsStayUnderTheirFieldsAndAnnounceOnce() {
        host.backends.submitAnswer = PayoutIdentityVisualFixtures.realnameError("fields")
        submitOnce()
        val state = host.realnameModel.controller.state.value
        assertEquals(listOf(RealNameController.FIELD_NAME, RealNameController.FIELD_ID), state.fieldErrors)
        val message = t("error.20001")
        val name = p.fieldBox(WalletTags.REALNAME_NAME)
        val nameError =
            p
                .tag(WalletTags.REALNAME_NAME)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Error)
        assertEquals("错误关联到姓名输入框", message, nameError)
        assertEquals(
            message,
            p
                .tag(WalletTags.REALNAME_ID)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Error),
        )
        assertTrue(name.bottom < p.fieldBox(WalletTags.REALNAME_ID).top)
        rule.mainClock.advanceTimeBy(300)
        rule.waitForIdle()
        assertEquals("字段错误只播报一次", 1, host.spoken.count { it == message })
        assertEquals(1, host.backends.submits.size)
    }

    @Test fun w95BottomBarHasNoSurfaceBackground() {
        host.launch(PayoutIdentityPage.REALNAME)
        val d = density()
        val submit = p.tag(WalletTags.REALNAME_SUBMIT).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "W95 底栏无底色",
            near(rule.productPixels().pixel(8 * d, submit.center.y), host.color("background.canvas").argb(), 4),
        )
    }

    @Test fun w95ReleaseKeepsUnavailableSentenceAndSendsNothing() {
        host.release = true
        host.launch(PayoutIdentityPage.REALNAME)
        host.fillRealName()
        p.tag(WalletTags.REALNAME_UNAVAILABLE).assertExists()
        p.tag(WalletTags.REALNAME_SUBMIT).assertIsNotEnabled().performTouchInput { click(center) }
        rule.waitForIdle()
        p.tag(WalletTags.CONSENT_SHEET).assertDoesNotExist()
        assertEquals(0, host.backends.submits.size)
        assertEquals(0, host.keys)
    }

    private fun submitOnce() {
        host.launch(PayoutIdentityPage.REALNAME)
        host.fillRealName()
        p.tag(WalletTags.REALNAME_SUBMIT).performTouchInput { click(center) }
        rule.waitForIdle()
        p.tag(WalletTags.CONSENT_CHECK).performTouchInput { click(center) }
        rule.waitForIdle()
        p.tag(WalletTags.CONSENT_OK).performTouchInput { click(center) }
        rule.waitForIdle()
        p.tag(WalletTags.CONSENT_SHEET).assertDoesNotExist()
    }

    private fun editable(tag: String) =
        p
            .tag(tag)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text

    private fun density(): Float = p.layoutOf(WalletTags.REALNAME_PAGE).density.density

    private fun t(key: String) = WalletDraftTexts.text(key)
}
