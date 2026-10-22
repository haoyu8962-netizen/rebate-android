package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.iconMaskDifference
import com.couli.core.designsystem.CouliIcons
import com.couli.core.orders.OrderDraftTexts
import com.couli.core.orders.OrderTexts
import com.couli.feature.orders.OrderTags
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderListVisualWiringTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderListVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun staleActuallyDraws20DpWifiOffInsteadOfAlertLight() = wifi(false)

    @Test fun staleActuallyDraws20DpWifiOffInsteadOfAlertDark() = wifi(true)

    @Test fun statusFindEntryAndMonthNoteUseDictionaryInsteadOfInlineChinese() {
        val keys = setOf("order_status.PAID.label", "order_list.find_entry", "order_list.filter.month_note")
        host.texts =
            object : OrderTexts {
                override fun text(key: String) = if (key in keys) "KEY[$key]" else OrderDraftTexts.text(key)

                override fun format(
                    key: String,
                    vars: Map<String, String>,
                ) = if (key in
                    keys
                ) {
                    "KEY[$key]"
                } else {
                    OrderDraftTexts.format(key, vars)
                }
            }
        host.launch(OrderListVisualFixtures.one())
        rule.onNodeWithText("KEY[order_list.find_entry]", useUnmergedTree = true).assertIsDisplayed()
        rule.cardText("KEY[order_status.PAID.label]").assertIsDisplayed()
        rule.cardText("已付款，返利待确认").assertDoesNotExist()
        rule.onNodeWithTag(OrderTags.FILTER_MONTH).performClick()
        rule.onNodeWithText("KEY[order_list.filter.month_note]", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun wifi(dark: Boolean) {
        val getter = CouliIcons::class.java.methods.singleOrNull { it.name == "getWifiOff" }
        assertNotNull("O3：共用图标新增 wifiOff，不能继续用 alert", getter)
        host.referenceIcon = getter!!.invoke(CouliIcons) as ImageVector
        host.stale(OrderListVisualFixtures.one())
        host.launch(OrderListVisualFixtures.one(), dark = dark)
        val text = rule.onNodeWithText("09:30", substring = true, useUnmergedTree = true).fetchSemanticsNode()
        val d = text.layoutInfo.density.density
        val actual =
            Rect(
                text.boundsInRoot.left - 28 * d,
                text.boundsInRoot.center.y - 10 * d,
                text.boundsInRoot.left - 8 * d,
                text.boundsInRoot.center.y + 10 * d,
            )
        val reference = rule.onNodeWithTag("orders_reference_icon", useUnmergedTree = true).fetchSemanticsNode()
        rule.withOrderPixels(text) { bitmap ->
            val size = (20 * d).toInt()
            val diff = bitmap.iconMaskDifference(actual, reference.boundsInRoot, size, host.warningBackground.toArgb())
            assertTrue("断网提示条必须画20dp wifiOff，轮廓差异=$diff", diff <= size * size * .02f)
        }
    }
}
