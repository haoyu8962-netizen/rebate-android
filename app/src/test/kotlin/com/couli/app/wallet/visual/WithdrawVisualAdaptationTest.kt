package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.assertNoTextOverflow
import com.couli.app.product.textLayout
import com.couli.core.designsystem.CouliFormat
import com.couli.feature.wallet.WalletTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawVisualAdaptationTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w55Font200At390KeepsMoneyAndSubmitAccessible() = large()

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun w55Font200At320KeepsMoneyAndSubmitAccessible() = large()

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun w55WideKeeps600CenteredPageAndFullColumnSubmit() {
        host.launch()
        val page = rule.onNodeWithTag(WalletTags.WITHDRAW_PAGE).fetchSemanticsNode()
        val submit = rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).fetchSemanticsNode()
        val d = page.layoutInfo.density.density
        assertEquals("画布是完整窗口", 840f, page.boundsInRoot.width / d, 1f)
        assertEquals("600dp内容列左右各16内边距", 136f, submit.boundsInRoot.left / d, 1f)
        assertEquals(568f, submit.boundsInRoot.width / d, 1f)
        assertEquals(page.boundsInRoot.center.x, submit.boundsInRoot.center.x, d)
    }

    @Test fun w41QuickTargetsAllFourEdgesOnlyFillRequestedServerAmount() {
        host.launch()
        repeat(4) { edge ->
            rule.onNodeWithTag(WalletTags.WITHDRAW_QUICK_PREFIX + "500").performScrollTo()
            rule.touchRecordEdge(WalletTags.WITHDRAW_QUICK_PREFIX + "500", edge)
            assertEquals("5", host.model.controller.state.value.amountText)
            assertEquals(0, host.api.writes().size)
            assertEquals(0, host.env.opened.size)
        }
    }

    private fun large() {
        host.api.rules = WithdrawVisualFixtures.ok(WithdrawVisualFixtures.rules("stress"))
        host.launch(fontScale = 2f)
        val amount = rule.onNode(hasText(CouliFormat.money(99999999999), substring = true), true).performScrollTo()
        amount.assertNoTextOverflow()
        assertEquals(
            2f,
            amount
                .textLayout()
                .layoutInput.density.fontScale,
            .01f,
        )
        assertTrue(
            "不能靠缩字过大字验收",
            amount
                .textLayout()
                .layoutInput.style.fontSize.value >= 34f,
        )
        val submit = rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).fetchSemanticsNode()
        assertTrue(submit.touchBoundsInRoot.height >= 48 * submit.layoutInfo.density.density)
        assertEquals(0, host.api.writes().size)
    }
}
