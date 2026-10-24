package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.productPixels
import com.couli.core.designsystem.AGREEMENT_CIRCLE_TAG
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import com.couli.feature.wallet.message
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** W96–W101：劳务协议首签；toggle(owner) / sign(owner) 的发起条件、次数与签署凭证（版本 + 服务端 sha256 原样）不变。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LaborVisualTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PayoutIdentityVisualHost(rule) }
    private val p by lazy { PayoutIdentityProbe(rule) }

    @After fun close() = host.close()

    @Test fun w96IntroIsFourteenSecondaryIndentedFourOutsideCard() = footnote("labor.intro", 14f)

    @Test fun w101SignedNoteIsTwelveSecondaryIndentedFourOutsideCard() = footnote("labor.signed_note", 12f)

    @Test fun w98DocumentIconLeadsNameAndVersionLineIsTwelveSecondary() {
        host.launch(PayoutIdentityPage.LABOR)
        val d = density()
        val name = t("labor.name")
        val style = p.style(name)
        assertEquals(17f, style.fontSize.value, .01f)
        assertEquals(FontWeight.W600, style.fontWeight)
        val left = p.glyphLeft(name)
        val box = p.bounds(name)
        val version = WalletDraftTexts.format("labor.version", mapOf("version" to "v1"))
        val versionBounds = p.bounds(version)
        val icon = Rect(left - 38 * d, box.top - 4 * d, left - 8 * d, versionBounds.bottom + 4 * d)
        assertTrue(
            "W98 协议名左侧文档图标（次文字色，间距 12）",
            rule.productPixels().countNear(icon, host.color("text.secondary").argb(), 40) > 12 * d,
        )
        assertTrue("图标在卡内（卡左 16 + 内距 16）", left >= (32 + 20 + 12 - 1) * d)
        assertTrue("版本在协议名下方", versionBounds.top >= box.bottom - d)
        val v = p.style(version)
        assertEquals(12f, v.fontSize.value, .01f)
        assertEquals(host.color("text.secondary"), v.color)
    }

    @Test fun w99FullTextAreaIsThreeSixtyHighSixteenPaddedAndScrollable() {
        host.launch(PayoutIdentityPage.LABOR)
        val area = p.tag(WalletTags.LABOR_TEXT).fetchSemanticsNode()
        val d = area.layoutInfo.density.density
        assertEquals("W99 全文区高 360", 360f, area.boundsInRoot.height / d, 1f)
        val body =
            PayoutIdentityVisualFixtures
                .labor()
                .getValue("text")
                .jsonPrimitive.content
        assertEquals("W99 内边距 16", 16f, (p.glyphLeft(body) - area.boundsInRoot.left) / d, 1f)
        assertEquals("W99 顶部内边距 16", 16f, (p.bounds(body).top - area.boundsInRoot.top) / d, 1f)
        val range = area.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        assertTrue("全文可滚动", range != null && range.maxValue() > 0f)
        assertTrue(area.config.contains(SemanticsActions.ScrollBy))
        assertEquals(t("labor.fulltext_label"), area.config.getOrNull(SemanticsProperties.ContentDescription)?.single())
        assertTrue(
            "全文区浅灰底",
            near(
                rule.productPixels().pixel(area.boundsInRoot.left + 4 * d, area.boundsInRoot.center.y),
                host.color("background.muted").argb(),
                2,
            ),
        )
    }

    @Test fun w100CheckIsRoundFourteenPrimaryAndRowNotStretched() {
        host.launch(PayoutIdentityPage.LABOR)
        val check = p.tag(WalletTags.LABOR_CHECK).fetchSemanticsNode()
        val d = check.layoutInfo.density.density
        assertEquals(ToggleableState.Off, check.config.getOrNull(SemanticsProperties.ToggleableState))
        val circle =
            rule
                .onNode(hasTestTag(AGREEMENT_CIRCLE_TAG) and hasAnyAncestor(hasTestTag(WalletTags.LABOR_CHECK)), useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals("W100 圆形 18", 18f, circle.width / d, .5f)
        val style = p.style(t("labor.check"))
        assertEquals(14f, style.fontSize.value, .01f)
        assertEquals(host.color("text.primary"), style.color)
        assertTrue("W100 视觉行不被命中区撑高：${check.boundsInRoot.height / d}dp", check.boundsInRoot.height <= 26 * d)
        assertTrue(check.touchBoundsInRoot.height >= 48 * d)
        assertRecordTargetsSeparate(check.touchBoundsInRoot, p.tag(WalletTags.LABOR_SIGN).fetchSemanticsNode().touchBoundsInRoot)
    }

    @Test fun w100BottomBarHasNoSurfaceBackground() {
        host.launch(PayoutIdentityPage.LABOR)
        val d = density()
        val sign = p.tag(WalletTags.LABOR_SIGN).fetchSemanticsNode().boundsInRoot
        val check = p.tag(WalletTags.LABOR_CHECK).fetchSemanticsNode().boundsInRoot
        val pixels = rule.productPixels()
        assertTrue("W100 底栏无底色（按钮外侧）", near(pixels.pixel(8 * d, sign.center.y), host.color("background.canvas").argb(), 4))
        assertTrue("W100 底栏无底色（勾选行外侧）", near(pixels.pixel(8 * d, check.center.y), host.color("background.canvas").argb(), 4))
    }

    @Test fun w100SignNeedsCheckAndSendsServerVersionAndShaExactlyOnce() {
        host.launch(PayoutIdentityPage.LABOR)
        assertEquals("进入读取一次", 1, host.backends.reads)
        p.tag(WalletTags.LABOR_SIGN).assertIsNotEnabled().performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals("未勾选零签署", 0, host.backends.signs.size)
        p.tag(WalletTags.LABOR_CHECK).performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals(
            ToggleableState.On,
            p
                .tag(WalletTags.LABOR_CHECK)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.ToggleableState),
        )
        assertEquals("勾选零请求", 0, host.backends.signs.size)
        p.tag(WalletTags.LABOR_SIGN).assertIsEnabled().performTouchInput { click(center) }
        rule.waitForIdle()
        val first = PayoutIdentityVisualFixtures.labor()
        assertEquals(
            "签署恰一次，版本与 sha256 取服务端原值",
            listOf(first.getValue("version").jsonPrimitive.content to first.getValue("text_sha256").jsonPrimitive.content),
            host.backends.signs,
        )
        assertEquals("成功后出栈一次（回 Withdraw，不重放）", 1, host.backs)
        assertEquals(1, host.backends.reads)
        assertEquals("签署不走提现写接口", 0, host.api.writes().size)
    }

    @Test fun signFailureShowsErrorToneAndKeepsButtonForRetry() {
        host.reference = { ReferenceAlertIcon() }
        host.backends.signAnswer = PayoutIdentityVisualFixtures.laborSignFailure()
        host.launch(PayoutIdentityPage.LABOR)
        p.tag(WalletTags.LABOR_CHECK).performTouchInput { click(center) }
        rule.waitForIdle()
        p.tag(WalletTags.LABOR_SIGN).performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals(1, host.backends.signs.size)
        val notice = checkNotNull(host.laborModel.controller.state.value.error)
        rule.assertErrorTone(p, host.colors, WalletTags.LABOR_ERROR, notice.message(WalletDraftTexts))
        p.tag(WalletTags.LABOR_SIGN).assertIsEnabled()
        assertEquals(0, host.backs)
    }

    private fun footnote(
        key: String,
        size: Float,
    ) {
        host.launch(PayoutIdentityPage.LABOR)
        val d = density()
        val text = t(key)
        val style = p.style(text)
        assertEquals(size, style.fontSize.value, .01f)
        assertEquals(host.color("text.secondary"), style.color)
        assertEquals("缩进 4（16+4）", 20f, p.glyphLeft(text) / d, 1f)
        assertTrue(
            "在卡外：字左侧是画布色",
            near(rule.productPixels().pixel(18 * d, p.bounds(text).center.y), host.color("background.canvas").argb(), 4),
        )
    }

    private fun density(): Float = p.layoutOf(WalletTags.LABOR_PAGE).density.density

    private fun t(key: String) = WalletDraftTexts.text(key)
}
