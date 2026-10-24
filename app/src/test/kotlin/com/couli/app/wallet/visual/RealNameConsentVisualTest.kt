package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.assertNoTextOverflow
import com.couli.app.product.textLayout
import com.couli.core.designsystem.AGREEMENT_CIRCLE_TAG
import com.couli.core.designsystem.AgreementCheckRow
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * W93 / W94：单独同意半屏。同意记录与实名提交（agreeAndSubmit(owner)）、默认不勾选、遮罩不关闭、返回 = 暂不认证均不变。
 * 标题 22 / 把手 / 遮罩令牌依赖 APP-09v-d 给 LockedSheet 加的可选参数（本任务不碰 LockedSheet），d 合入并 rebase 前这三项预期红。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RealNameConsentVisualTest {
    @get:Rule val rule = createComposeRule()
    private val standard = lazy { PayoutIdentityVisualHost(rule) }
    private val host get() = standard.value
    private val p by lazy { PayoutIdentityProbe(rule) }

    @After fun close() {
        if (standard.isInitialized()) host.close()
    }

    @Test fun w93TitleIsTwentyTwoSemibold() {
        open()
        val style = p.style(t("realname.consent.title"))
        assertEquals("W93 标题 22（依赖 d 的 LockedSheet 标题样式参数）", 22f, style.fontSize.value, .01f)
        assertEquals(FontWeight.W600, style.fontWeight)
    }

    @Test fun w93DecorativeHandleIsDrawnAboveTitle() {
        open()
        val title = p.text(t("realname.consent.title")).fetchSemanticsNode()
        val d = title.layoutInfo.density.density
        val shot = rule.dialogPixels(title)
        val mapped = shot.map(title.boundsInRoot)
        val region = Rect(shot.image.width / 2f - 40 * d, mapped.top - 40 * d, shot.image.width / 2f + 40 * d, mapped.top - 2 * d)
        val handle = shot.image.boxOf(region, host.color("border.subtle").argb(), 4)
        assertNotNull("W93 标题上方画 36×4 把手（装饰，依赖 d 的 showHandle）", handle)
        assertEquals(36f, handle!!.width / d, 1.5f)
        assertEquals(4f, handle.height / d, 1f)
        assertEquals("把手水平居中", shot.image.width / 2f, handle.center.x, 2 * d)
    }

    @Test fun w93BulletsHaveTwentyIconsAndSeventeenText() {
        open()
        val title = p.text(t("realname.consent.title")).fetchSemanticsNode()
        val shot = rule.dialogPixels(title)
        listOf("realname.consent.item1", "realname.consent.item2", "realname.consent.item3").forEach {
            val text = t(it)
            val style = p.style(text)
            assertEquals("W93 要点 17：$it", 17f, style.fontSize.value, .01f)
            assertEquals(host.color("text.primary"), style.color)
            val node = p.text(text).fetchSemanticsNode()
            val d = node.layoutInfo.density.density
            val b = shot.map(node.boundsInRoot)
            val left = b.left + p.text(text).textLayout().getLineLeft(0)
            val icon = Rect(left - 34 * d, b.top, left - 8 * d, b.top + 24 * d)
            assertTrue("W93 要点左侧 20 次文字色图标：$it", shot.image.countNear(icon, host.color("text.secondary").argb(), 40) > 12 * d)
        }
    }

    @Test fun w93BodyBlockIsFourteenWithMediumTitle() {
        open()
        val head = p.style(t("realname.consent.body_title"))
        assertEquals(14f, head.fontSize.value, .01f)
        assertEquals(FontWeight.W500, head.fontWeight)
        assertEquals(host.color("text.primary"), head.color)
        val body = p.style(t("realname.consent.body"))
        assertEquals("W93 正文块说明 14（原 12）", 14f, body.fontSize.value, .01f)
        assertEquals(host.color("text.secondary"), body.color)
    }

    @Test fun w94CheckIsRoundEighteenFourteenPrimaryVisualRowNotStretchedByTarget() {
        open()
        val check = p.tag(WalletTags.CONSENT_CHECK).fetchSemanticsNode()
        val d = check.layoutInfo.density.density
        assertEquals("默认不勾选", ToggleableState.Off, check.config.getOrNull(SemanticsProperties.ToggleableState))
        val circle =
            rule
                .onNode(hasTestTag(AGREEMENT_CIRCLE_TAG) and hasAnyAncestor(hasTestTag(WalletTags.CONSENT_CHECK)), useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals("W94 圆形 18（DS AgreementCheckRow）", 18f, circle.width / d, .5f)
        val style = p.style(t("realname.consent.check"))
        assertEquals("W94 勾选句 14", 14f, style.fontSize.value, .01f)
        assertEquals("W94 勾选句主文字色", host.color("text.primary"), style.color)
        assertTrue("W94 视觉行不被 48 命中区撑高：${check.boundsInRoot.height / d}dp", check.boundsInRoot.height <= 26 * d)
        assertTrue("命中区 ≥48", check.touchBoundsInRoot.height >= 48 * d && check.touchBoundsInRoot.width >= 48 * d)
        val ok = p.tag(WalletTags.CONSENT_OK).fetchSemanticsNode().touchBoundsInRoot
        assertRecordTargetsSeparate(check.touchBoundsInRoot, ok)
    }

    @Test fun w93ButtonsStackPrimaryOverSecondaryTwelveApart() {
        open()
        val ok = p.tag(WalletTags.CONSENT_OK).fetchSemanticsNode()
        val cancel = p.tag(WalletTags.CONSENT_CANCEL).fetchSemanticsNode().boundsInRoot
        val d = ok.layoutInfo.density.density
        assertTrue("主按钮在上", ok.boundsInRoot.bottom <= cancel.top)
        assertEquals("纵排两按钮间 12", 12f, (cancel.top - ok.boundsInRoot.bottom) / d, 1f)
        assertEquals("同宽", ok.boundsInRoot.width, cancel.width, d)
        assertTrue(
            ok.boundsInRoot.top >
                p
                    .tag(WalletTags.CONSENT_CHECK)
                    .fetchSemanticsNode()
                    .boundsInRoot.bottom,
        )
    }

    @Test fun consentOkNeedsCheckAndSubmitsExactlyOnceWithNormalizedInput() {
        open()
        p.tag(WalletTags.CONSENT_OK).assertIsNotEnabled().performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals("未勾选零提交", 0, host.backends.submits.size)
        p.tag(WalletTags.CONSENT_CHECK).performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals(ToggleableState.On, toggle(WalletTags.CONSENT_CHECK))
        assertEquals("勾选不发请求", 0, host.backends.submits.size)
        p.tag(WalletTags.CONSENT_OK).assertIsEnabled().performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals("同意并继续恰一次", 1, host.backends.submits.size)
        val (name, id, key) = host.backends.submits.single()
        assertEquals(PayoutIdentityVisualFixtures.realnameForm("name"), name)
        assertEquals("11010119900101123X", id)
        assertEquals("visual-e-created-1", key)
        assertEquals(1, host.keys)
        p.tag(WalletTags.CONSENT_SHEET).assertDoesNotExist()
        assertEquals("成功后出栈一次", 1, host.backs)
    }

    @Test fun declineAndBackCloseWithoutSubmitting() {
        open()
        p.tag(WalletTags.CONSENT_CHECK).performTouchInput { click(center) }
        rule.waitForIdle()
        p.tag(WalletTags.CONSENT_CANCEL).performTouchInput { click(center) }
        rule.waitForIdle()
        p.tag(WalletTags.CONSENT_SHEET).assertDoesNotExist()
        assertEquals(0, host.backends.submits.size)
        val kept =
            p
                .tag(WalletTags.REALNAME_ID)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.EditableText)
                ?.text
        assertEquals("已填内容保留", "11010119900101123X", kept)
        p.tag(WalletTags.REALNAME_SUBMIT).performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals("再次打开仍默认不勾选", ToggleableState.Off, toggle(WalletTags.CONSENT_CHECK))
        rule.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        rule.waitForIdle()
        p.tag(WalletTags.CONSENT_SHEET).assertDoesNotExist()
        assertEquals("返回 = 暂不认证，零提交", 0, host.backends.submits.size)
        assertEquals(0, host.keys)
    }

    @Test fun w93ScrimUsesOverlayToken() {
        host.reference = { ScrimSample() }
        open()
        val sample = WithdrawVisualWindowProbe(rule).scrim(rule.onNodeWithTag(SCRIM_WHITE).fetchSemanticsNode())
        assertEquals("白卡采样", Color.White.toArgb(), sample.before)
        val expected = scrim.compositeOver(Color.White).toArgb()
        val ok = listOf(24, 16, 8, 0).all { abs((sample.after ushr it and 255) - (expected ushr it and 255)) <= 2 }
        assertTrue(
            "W93 同意半屏遮罩 = overlayScrim（依赖 d 的 LockedSheet 遮罩参数）：actual=${sample.after.toUInt().toString(16)} " +
                "expected=${expected.toUInt().toString(16)}",
            ok,
        )
    }

    @Test
    @Config(fontScale = 2f)
    fun w93Font200ContinuesIntoSheetWindow() {
        open(fontScale = 2f)
        val title = p.text(t("realname.consent.title"))
        assertEquals(
            "半屏接续 200% 字号",
            2f,
            title
                .textLayout()
                .layoutInput.density.fontScale,
            .01f,
        )
        title.assertNoTextOverflow()
        p.text(t("realname.consent.check")).assertNoTextOverflow()
        val ok = p.tag(WalletTags.CONSENT_OK).fetchSemanticsNode()
        assertTrue(ok.touchBoundsInRoot.height >= 48 * ok.layoutInfo.density.density)
    }

    @Test fun w94SharedAgreementRowDefaultsStayTwelveSecondary() {
        rule.setContent {
            CouliTheme {
                secondary = CouliColors.textSecondary
                AgreementCheckRow(checked = false, onCheckedChange = {}, text = AnnotatedString("默认对照"))
            }
        }
        val style = p.style("默认对照")
        assertEquals("登录 / 目录页沿用默认 12", 12f, style.fontSize.value, .01f)
        assertEquals("默认次文字色", secondary, style.color)
    }

    private var scrim = Color.Unspecified
    private var secondary = Color.Unspecified

    @Composable
    private fun ScrimSample() {
        scrim = CouliColors.overlayScrim
        Row {
            Box(Modifier.size(16.dp).background(Color.White).testTag(SCRIM_WHITE))
        }
    }

    private fun open(fontScale: Float = 1f) {
        host.launch(PayoutIdentityPage.REALNAME, fontScale = fontScale)
        host.fillRealName()
        p.tag(WalletTags.REALNAME_SUBMIT).performTouchInput { click(center) }
        rule.waitForIdle()
        p.tag(WalletTags.CONSENT_SHEET).assertExists()
        assertEquals("打开同意面板零请求", 0, host.backends.submits.size)
    }

    private fun toggle(tag: String) =
        p
            .tag(tag)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ToggleableState)

    private fun t(key: String) = WalletDraftTexts.text(key)

    private companion object {
        const val SCRIM_WHITE = "e_scrim_white"
    }
}
