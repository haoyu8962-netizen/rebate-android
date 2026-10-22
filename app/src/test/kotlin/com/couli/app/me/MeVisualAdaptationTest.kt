package com.couli.app.me

import android.app.Application
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.assertNoTextOverflow
import com.couli.app.product.textLayout
import com.couli.app.wallet.assertWalletTextWhole
import com.couli.app.wallet.walletRenderedText
import com.couli.core.me.MeView
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
class MeVisualAdaptationTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { MeVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun m20Font200At390PreservesTextSizeAndCopyFlow() = large()

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun m20Font200At320PreservesTextSizeAndCopyFlow() = large()

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun m20HiddenGuideAt200IsWhole() {
        host.env.guide = true
        host.launch(fontScale = 2f, hidden = true)
        rule
            .onNode(
                SemanticsMatcher("实际绘制的通知引导整句") { it.walletRenderedText()?.startsWith("通知没有开启") == true },
                useUnmergedTree = true,
            ).assertWalletTextWhole()
        val code = rule.meTag(MeTags.ME_INVITE_CODE).performScrollTo()
        code.assertNoTextOverflow()
        code.assertMeCopyFollows(rule.meTag(MeTags.ME_COPY))
    }

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun m20WideColumnIsSixHundredAndCenteredInSameRootCoordinates() {
        host.launch()
        val scroll = rule.meTag(MeTags.ME_SCROLL)
        val page = rule.meTag(MeTags.ME_PAGE)
        assertMeDp(600f, scroll.meBounds().width, scroll.meDensity())
        assertMeDp(0f, scroll.meBounds().center.x - page.meBounds().center.x, scroll.meDensity())
        for (tag in listOf(MeTags.ME_BALANCE, MeTags.ME_GRID_PREFIX + "orders_wallet", MeTags.ME_GRID_PREFIX + "more")) {
            val node = rule.meTag(tag).performScrollTo()
            assertMeDp(16f, node.meBounds().left - scroll.meBounds().left, scroll.meDensity())
            assertMeDp(16f, scroll.meBounds().right - node.meBounds().right, scroll.meDensity())
        }
    }

    private fun large() {
        host.launch("me_long", fontScale = 2f)
        val nick = rule.meTag(MeTags.ME_NICKNAME).performScrollTo()
        nick.assertNoTextOverflow()
        assertEquals(
            2f,
            nick
                .textLayout()
                .layoutInput.density.fontScale,
            0f,
        )
        assertEquals(
            17f,
            nick
                .textLayout()
                .layoutInput.style.fontSize.value,
            0f,
        )
        assertTrue(nick.textLayout().lineCount > 1)
        val code = rule.meTag(MeTags.ME_INVITE_CODE).performScrollTo()
        code.assertNoTextOverflow()
        code.assertMeCopyFollows(rule.meTag(MeTags.ME_COPY))
        for (text in listOf("¥99999.99", "¥999999.99")) {
            rule.onNodeWithText(text, useUnmergedTree = true).performScrollTo().assertNoTextOverflow()
        }
        for (cell in MeView.MEMBER_GRID.filter { it.route != "Earnings" }) {
            val item = rule.onNodeWithText(cell.item, useUnmergedTree = true).performScrollTo()
            item.assertNoTextOverflow()
            assertEquals(
                14f,
                item
                    .textLayout()
                    .layoutInput.style.fontSize.value,
                0f,
            )
        }
    }
}
