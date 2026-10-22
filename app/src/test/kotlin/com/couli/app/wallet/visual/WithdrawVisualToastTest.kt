package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.productPixels
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
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawVisualToastTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w62AbandonedRetainsGreenSuccessIconAndOneAbandon() = toast("abandoned")

    @Test fun w62CompletedUsesSecondaryInfoIconAndOriginalNavigation() = toast("completed")

    private fun toast(scene: String) {
        WithdrawVisualScenes(rule, host).show(scene)
        val node = rule.onNodeWithTag(WalletTags.TOAST).fetchSemanticsNode()
        val d = node.layoutInfo.density.density
        val bounds = node.boundsInRoot
        val icon = Rect(bounds.left + 12 * d, bounds.center.y - 10 * d, bounds.left + 32 * d, bounds.center.y + 10 * d)
        val image = rule.productPixels()
        val success = host.colors.getValue("status.success.text").toArgb()
        val secondary = host.colors.getValue("text.secondary").toArgb()
        if (scene == "completed") {
            assertEquals("已处理完成不能继续画成功绿", 0, image.recordColorCount(icon, success))
            assertTrue(image.recordColorCount(icon, secondary) > 8)
            val topArc = Rect(icon.left + 6 * d, icon.top, icon.right - 6 * d, icon.top + 4 * d)
            assertTrue("信息圆圈顶部应有弧线，不能仍用对勾", image.recordColorCount(topArc, secondary) > 2)
            assertEquals(listOf("WithdrawRecords"), host.env.replaced.map { it.first })
        } else {
            assertTrue(image.recordColorCount(icon, success) > 8)
            assertEquals(0, host.env.replaced.size)
        }
        assertEquals(1, host.api.abandons.size)
        assertEquals(0, host.api.confirms.size + host.api.writes().size)
    }
}
