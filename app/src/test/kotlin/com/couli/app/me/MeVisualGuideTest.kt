package com.couli.app.me

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.productPixels
import com.couli.app.product.textLayout
import com.couli.app.wallet.walletRenderedText
import com.couli.app.wallet.walletTextLayout
import com.couli.core.me.MeDraftTexts
import com.couli.feature.me.MeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1032dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeVisualGuideTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { MeVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun m3GuideUsesNeutralSurfaceAndOnlyOpensNotificationSettings() {
        host.env.guide = true
        host.launch()
        val bounds = rule.meTag(MeTags.ME_GUIDE_BAR).meBounds()
        val density = rule.meTag(MeTags.ME_GUIDE_BAR).meDensity()
        val pixels = rule.productPixels()
        assertEquals(host.muted.toArgb(), pixels.getPixel(bounds.center.x.toInt(), (bounds.top + 2 * density).toInt()))
        val text = MeDraftTexts.text("perm.push.guide_bar")
        rule.onNode(hasClickAction() and (hasText(text, substring = true) or hasContentDescription(text))).performClick()
        assertEquals(1, host.env.settings)
        assertTrue(host.env.opened.isEmpty())
        host.assertReads(1)
        rule.meTag(MeTags.ME_GUIDE_CLOSE).performClick()
        assertEquals(MeVisualFixtures.now, host.deps.local!!.guideClosedAt())
        rule.meTag(MeTags.ME_GUIDE_BAR).assertDoesNotExist()
        assertEquals(1, host.env.settings)
        host.assertReads(1)
    }

    @Test fun m4LastSentenceIsLinkStyledAndReadAsOneButton() {
        host.env.guide = true
        host.guideText.value = "通知未开启。可能错过消息。去开启"
        host.launch()
        val full = host.guideText.value!!
        val layout = rule.onNode(SemanticsMatcher("绘制整句") { it.walletRenderedText() == full }, useUnmergedTree = true).walletTextLayout()
        val range =
            layout.layoutInput.text.spanStyles
                .single { it.item.textDecoration == TextDecoration.Underline }
        assertEquals("去开启", full.substring(range.start, range.end))
        assertEquals(FontWeight.SemiBold, range.item.fontWeight)
        assertEquals(host.link, range.item.color)
        val button = rule.onNode(hasClickAction() and (hasText(full, substring = true) or hasContentDescription(full))).fetchSemanticsNode()
        assertEquals(Role.Button, button.config.getOrNull(SemanticsProperties.Role))
        val spoken =
            button.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                button.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .map { it.text }
        assertEquals("整句只读一次", listOf(full), spoken)
    }

    @Test fun m4FailedSplitFallsBackToEntirePlainSentenceWithoutStaleSpan() {
        host.env.guide = true
        host.guideText.value = "通知未开启。去开启"
        host.launch()
        for (text in listOf("没有句号去开启", "通知未开启。", "通知未开启。一二三四五六七八九")) {
            rule.runOnIdle { host.guideText.value = text }
            val node = rule.onNode(SemanticsMatcher("绘制整句") { it.walletRenderedText() == text }, useUnmergedTree = true)
            assertTrue(
                "解析失败不残留链接：$text",
                node.walletTextLayout().layoutInput.text.spanStyles.none {
                    it.item.textDecoration == TextDecoration.Underline
                },
            )
            rule.onNode(hasClickAction() and (hasText(text, substring = true) or hasContentDescription(text))).performClick()
        }
        assertEquals(3, host.env.settings)
        host.assertReads(1)
    }

    @Test fun m4EightCharacterTailStillGetsLinkStyle() {
        host.env.guide = true
        host.guideText.value = "通知未开启。一二三四五六七八"
        host.launch()
        val text = host.guideText.value!!
        val layout = rule.onNode(SemanticsMatcher("绘制整句") { it.walletRenderedText() == text }, useUnmergedTree = true).walletTextLayout()
        assertTrue(
            layout.layoutInput.text.spanStyles
                .any { it.item.textDecoration == TextDecoration.Underline },
        )
    }
}
