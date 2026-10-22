package com.couli.app.wallet

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
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
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1200dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletVisualHelpTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WalletVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w15EstimatedHelpKeepsExactTextAndUses14By21Body() = help("estimated")

    @Test fun w15FrozenHelpKeepsExactTextAndUses14By21Body() = help("frozen", "frozen")

    @Test fun w15PausedHelpKeepsExactTextAndUses14By21Body() = help("pending_paused")

    @Test fun w12HelpTargetsAre48DpNonOverlappingAndExtendedEdgesActuallyOpenSheet() {
        host.launch()
        listOf("冻结中说明", "预估收益说明", "暂缓入账说明").forEach { name ->
            val help = rule.onNodeWithContentDescription(name, useUnmergedTree = true).fetchSemanticsNode()
            val touch = help.touchBoundsInRoot
            val density = help.layoutInfo.density.density
            assertTrue("$name 的实际命中范围", touch.width >= 48 * density && touch.height >= 48 * density)
            rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().filter { it.id != help.id }.forEach {
                assertFalse("$name 不侵入其他命中区", touch.overlaps(it.touchBoundsInRoot))
            }
            val origin =
                rule
                    .onRoot()
                    .fetchSemanticsNode()
                    .boundsInRoot.topLeft
            listOf(touch.top + density, touch.bottom - density).forEach { y ->
                rule.onRoot().performTouchInput { click(Offset(touch.center.x, y) - origin) }
                rule.walletTag(WalletTags.HELP_SHEET).assertExists()
                rule.walletTag(WalletTags.HELP_OK).performClick()
                rule.waitForIdle()
                rule.walletTag(WalletTags.HELP_SHEET).assertDoesNotExist()
            }
        }
        host.assertSummaryReads(1)
        assertTrue(host.env.opened.isEmpty())
    }

    @Test fun w15HelpOkHasSurfaceInteriorAndVisibleOutline() {
        host.launch()
        rule.onNodeWithContentDescription("预估收益说明").performClick()
        rule.waitForIdle()
        val button = rule.walletTag(WalletTags.HELP_OK).fetchSemanticsNode()
        val view = (button.root as ViewRootForTest).view
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bitmap)) }
        try {
            val bounds = button.boundsInRoot
            val density = button.layoutInfo.density.density
            val y = bounds.center.y.toInt()
            assertEquals(
                "知道了是描边按钮，不是实心主按钮",
                host.surface.toArgb(),
                bitmap.getPixel((bounds.left + 8 * density).toInt(), y),
            )
            assertTrue(
                "描边不能省略",
                (bounds.left.toInt() until (bounds.left + 2 * density).toInt()).any {
                    bitmap.getPixel(it, y) != host.surface.toArgb()
                },
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun help(
        key: String,
        summary: String = "normal",
    ) {
        host.launch(summary)
        rule.onNodeWithContentDescription(WalletDraftTexts.text("wallet.help.${key}_label")).performClick()
        val body = rule.walletTag(WalletTags.HELP_SHEET)
        assertEquals(
            WalletDraftTexts.text("wallet.help.$key"),
            body
                .walletTextLayout()
                .layoutInput.text.text,
        )
        assertEquals(
            14f,
            body
                .walletTextLayout()
                .layoutInput.style.fontSize.value,
        )
        assertEquals(
            21f,
            body
                .walletTextLayout()
                .layoutInput.style.lineHeight.value,
        )
        body.assertWalletTextWhole()
        rule.walletTag(WalletTags.HELP_OK).performClick()
        rule.waitForIdle()
        body.assertDoesNotExist()
        host.assertSummaryReads(1)
    }
}
