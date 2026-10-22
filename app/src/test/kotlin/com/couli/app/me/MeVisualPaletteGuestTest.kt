package com.couli.app.me

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.commonLayout
import com.couli.app.product.outerBounds
import com.couli.app.product.productPixels
import com.couli.app.product.textLayout
import com.couli.core.me.MeLevel
import com.couli.feature.me.MeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1032dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeVisualPaletteGuestTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { MeVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun m13GuestPaddingTitleAndButtonGapMatchDrawing() {
        host.env.identityFlow.value = host.env.identity().copy(level = MeLevel.GUEST, account = null)
        host.launch()
        val title = rule.onNodeWithText("还没有登录", useUnmergedTree = true)
        assertEquals(
            17f,
            title
                .textLayout()
                .layoutInput.style.fontSize.value,
            0f,
        )
        assertEquals(
            600,
            title
                .textLayout()
                .layoutInput.style.fontWeight!!
                .weight,
        )
        val button = rule.meTag(MeTags.ME_LOGIN)
        val column = commonLayout(title.fetchSemanticsNode().layoutInfo, button.fetchSemanticsNode().layoutInfo)
        val row = column.meChildren().first()
        assertMeDp(16f, button.meBounds().top - outerBounds(row).bottom, title.meDensity())
        assertMeDp(16f, button.meBounds().left - outerBounds(column).left, title.meDensity())
        host.assertReads(0)
    }

    @Test fun m19DarkHeroRetainsSolidAndInverseButtonAndTextTokens() {
        host.launch(dark = true)
        val hero = rule.meTag(MeTags.ME_BALANCE)
        val button = rule.meTag(MeTags.ME_WITHDRAW)
        val pixels = rule.productPixels()
        val d = hero.meDensity()
        val h = hero.meBounds()
        val b = button.meBounds()
        assertEquals(host.solid.toArgb(), pixels.getPixel(h.center.x.toInt(), (h.top + 4 * d).toInt()))
        assertEquals(android.graphics.Color.WHITE, pixels.getPixel((b.left + 16 * d).toInt(), (b.top + 8 * d).toInt()))
        assertEquals(
            host.primary,
            rule
                .meTag(MeTags.ME_NICKNAME)
                .textLayout()
                .layoutInput.style.color,
        )
        assertEquals(
            host.secondary,
            rule
                .meTag(MeTags.ME_INVITER)
                .textLayout()
                .layoutInput.style.color,
        )
        assertEquals(
            androidx.compose.ui.graphics.Color.White,
            rule
                .onNodeWithText("¥12.34", useUnmergedTree = true)
                .textLayout()
                .layoutInput.style.color,
        )
    }
}
