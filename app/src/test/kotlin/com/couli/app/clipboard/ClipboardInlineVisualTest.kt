package com.couli.app.clipboard

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.network.ApiResult
import kotlinx.serialization.json.JsonPrimitive
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
class ClipboardInlineVisualTest {
    @get:Rule val rule = createComposeRule()
    private val driver by lazy { ClipboardVisualDriver(rule) }

    @After fun close() = driver.close()

    @Test fun unrecognizedHasLeftIconTextAndRightOutlineAction() {
        driver.launch()
        driver.parse(ClipboardVisualFixtures.unrecognized())
        val text = (driver.host.clipboard.result.value as ClipboardResult.Unrecognized).text
        // 锁 a89c1ec3a 的 error.30132「这个链接或口令暂时识别不了」在 390dp 与【用商品名搜索】放不下一行：按 ClipboardScreens 现有规则
        // 按钮整体换到下一行靠右（FU-X-CTA-KEY-DRIFT，#24，编排 D10-921 ①；保持一行属外观改动，归 FU-X-CLIPBOARD-30132-LAYOUT）
        assertInline(ClipboardScreens.UNRECOGNIZED, ClipboardScreens.SEARCH_BY_NAME, text, actionWrapped = true)
    }

    @Test fun networkFailureHasLeftIconTextAndRightOutlineAction() {
        driver.launch()
        driver.parse(ApiResult.TransportFailure("io", null, null, false))
        val text = (driver.host.clipboard.result.value as ClipboardResult.Notice).text
        assertInline(ClipboardScreens.RETRY, ClipboardScreens.RETRY_ACTION, text)
    }

    @Test fun retryReusesApprovedCandidateWithoutRereadingClipboard() {
        driver.launch()
        driver.parse(ApiResult.TransportFailure("io", null, null, false))
        val first =
            driver.host.api.calls
                .single { it.operationId == "parseInput" }
        val reads = driver.host.system.reads
        driver.host.api.reply = { ClipboardVisualFixtures.cards(ClipboardVisualFixtures.card()) }
        driver.host.system.content =
            com.couli.core.clipboard.ClipRead
                .Text("https://item.taobao.com/item.htm?id=999999")
        rule.onNodeWithTag(ClipboardScreens.RETRY_ACTION).performTouchInput { click() }
        driver.host.idle()
        val calls =
            driver.host.api.calls
                .filter { it.operationId == "parseInput" }
        assertEquals(2, calls.size)
        assertEquals(first.body, calls.last().body)
        assertEquals(reads, driver.host.system.reads)
        rule.onNodeWithTag(ClipboardScreens.CARD_PREFIX + 0).assertIsDisplayed()
    }

    @Test fun searchActionKeepsExistingPrefillAndDoesNotBuy() {
        driver.launch()
        driver.parse(ClipboardVisualFixtures.unrecognized())
        val before =
            driver.host.api.calls
                .toList()
        rule.onNodeWithTag(ClipboardScreens.SEARCH_BY_NAME).performTouchInput { click() }
        driver.host.idle()
        assertEquals(before, driver.host.api.calls)
        assertEquals(
            JsonPrimitive("全脂纯牛奶"),
            driver.host.routing.router.lastOutcome.value
                ?.request
                ?.params
                ?.get("q"),
        )
        assertEquals(
            "Search",
            driver.host.routing.lastTrace.value
                ?.route,
        )
    }

    private fun assertInline(
        tag: String,
        actionTag: String,
        text: String,
        actionWrapped: Boolean = false,
    ) {
        val root = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
        val body =
            rule
                .onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
        val action = rule.onNodeWithTag(actionTag).assertIsDisplayed().fetchSemanticsNode()
        assertTrue("图标在文字左边留位", body.boundsInRoot.left - root.boundsInRoot.left >= 24f * root.layoutInfo.density.density)
        if (actionWrapped) {
            val end = root.boundsInRoot.right - 16f * root.layoutInfo.density.density
            assertTrue("放不下时按钮换到文案下一行", action.boundsInRoot.top >= body.boundsInRoot.bottom)
            assertTrue("换行后按钮靠右", action.boundsInRoot.right >= end - 1f && action.boundsInRoot.right <= root.boundsInRoot.right)
        } else {
            assertTrue("文字与右按钮并排", body.boundsInRoot.right <= action.boundsInRoot.left)
            assertTrue(
                "按钮与文案垂直相交",
                body.boundsInRoot.top < action.boundsInRoot.bottom && action.boundsInRoot.top < body.boundsInRoot.bottom,
            )
        }
        val probe = OverlayVisualProbe(rule)
        probe.assertTarget48(action)
        probe.assertOutline(action, driver.host.surface.toArgb(), driver.host.border.toArgb())
        probe.bitmap(root) { image ->
            val density = root.layoutInfo.density.density
            val center =
                body.boundsInRoot.center.y
                    .toInt()
            val left = (root.boundsInRoot.left + 2f * density).toInt()
            val right = (body.boundsInRoot.left - 4f * density).toInt()
            val background = image.getPixel(left, (root.boundsInRoot.top + 2f * density).toInt())
            val ink =
                (left until right).sumOf { x ->
                    ((center - 8 * density).toInt()..(center + 8 * density).toInt()).count { image.getPixel(x, it) != background }
                }
            assertTrue("真实左图标必须有像素，空白占位不能过", ink >= 20)
        }
    }
}
