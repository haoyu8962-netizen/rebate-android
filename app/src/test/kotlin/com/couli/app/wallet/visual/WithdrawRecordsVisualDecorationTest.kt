package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.outerBounds
import com.couli.app.product.productPixels
import com.couli.app.product.textLayout
import com.couli.core.wallet.WalletDraftTexts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawRecordsVisualDecorationTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawRecordsVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w21AllStatusPillsUseTwelveSpIconAndTokenSurfaceLight() = pills(false)

    @Test fun w21AllStatusPillsUseTwelveSpIconAndTokenSurfaceDark() = pills(true)

    @Test fun w22ChevronIsTwentyDpAndFlipsWhenExpanded() {
        host.launch("pending")
        val tag = "record_arrow_PENDING"
        val before = arrowMask(tag)
        host.toggle("PENDING")
        val after = arrowMask(tag)
        assertTrue("展开箭头必须朝上，不能复用朝下", before != after)
        val reversed = before.reversed()
        assertTrue("展开为180度翻转", reversed.zip(after).count { (a, b) -> a != b } <= before.size / 12)
        host.assertCalls("listWithdrawals", "getWithdrawal")
    }

    private fun arrowMask(tag: String): List<Boolean> {
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val d = node.layoutInfo.density.density
        val bounds = node.boundsInRoot
        assertEquals(20f, bounds.width / d, .5f)
        assertEquals(20f, bounds.height / d, .5f)
        val pixels = rule.productPixels()
        val color = host.colors.getValue("text.secondary").toArgb()
        val size = bounds.width.toInt()
        return (0 until size * size)
            .map { n ->
                pixels.getPixel(
                    bounds.left.toInt() + n % size,
                    bounds.top.toInt() + n / size,
                ) == color
            }.also { assertTrue("箭头有实际墨迹", it.count { ink -> ink } > 10) }
    }

    private fun pills(dark: Boolean) {
        host.launch("empty", dark = dark)
        val cases = listOf("pending", "approved", "paying", "paid", "manual", "rejected", "failed", "unknown")
        cases.forEach { name ->
            host.refresh(WithdrawRecordsVisualFixtures.ok(WithdrawRecordsVisualFixtures.page(name)))
            val item =
                host.controller.state.value.items
                    .single()
            val status = if (name == "unknown") "UNKNOWN" else item.status
            val text = rule.recordText(WalletDraftTexts.text("withdrawal_status.$status.title"), item.withdrawalId)
            val style = text.textLayout().layoutInput.style
            val tone =
                when (name) {
                    "paid", "manual" -> "success"
                    "failed", "rejected" -> "warning"
                    else -> "pending"
                }
            assertEquals("$name 胶囊字号", 12f, style.fontSize.value, .01f)
            assertEquals("$name 胶囊字色", host.colors.getValue("status.$tone.text"), style.color)
            val node = text.fetchSemanticsNode()
            val pill = outerBounds(checkNotNull(node.layoutInfo.parentInfo))
            val d = node.layoutInfo.density.density
            assertEquals(22f, pill.height / d, 1f)
            val pixels = rule.productPixels()
            assertTrue(
                "$name 胶囊底色",
                pixels.recordColorCount(pill, host.colors.getValue("status.$tone.background").toArgb()) > pill.width * d * 3,
            )
            val icon =
                Rect(
                    node.boundsInRoot.left - 18 * d,
                    pill.center.y - 7 * d,
                    node.boundsInRoot.left - 4 * d,
                    pill.center.y + 7 * d,
                )
            assertTrue(
                "$name 必须有14dp状态图标，不能只靠颜色",
                pixels.recordColorCount(
                    icon,
                    host.colors.getValue("status.$tone.text").toArgb(),
                ) > 8 * d,
            )
        }
    }
}
