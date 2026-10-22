package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.textLayout
import com.couli.feature.orders.OrderTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderDetailVisualDecorationTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderDetailVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun settledTitleHas20IconAndGreenHeadingInLight() = settled(false)

    @Test fun settledTitleHas20IconAndGreenHeadingInDark() = settled(true)

    private fun settled(dark: Boolean) {
        host.launch(OrderDetailVisualFixtures.detail("detail_settled"), dark = dark)
        val title = rule.detailText("已结算")
        assertTrue(title.fetchSemanticsNode().config.contains(SemanticsProperties.Heading))
        assertEquals(
            17f,
            title
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
        assertEquals(
            600,
            title
                .textLayout()
                .layoutInput.style.fontWeight
                ?.weight,
        )
        assertEquals(
            host.success,
            title
                .textLayout()
                .layoutInput.style.color,
        )
        val icon = rule.onNodeWithTag(DETAIL_STATUS_ICON, useUnmergedTree = true).fetchSemanticsNode()
        val d = icon.layoutInfo.density.density
        assertEquals(20f, icon.boundsInRoot.width / d, .5f)
        assertEquals(20f, icon.boundsInRoot.height / d, .5f)
        assertTrue(icon.boundsInRoot.right <= title.fetchSemanticsNode().boundsInRoot.left)
        rule.withOrderPixels(icon) { pixels -> assertTrue("图标必须实际绘成成功色", pixels.colorCount(icon.boundsInRoot, host.success.toArgb()) > 10) }
    }

    @Test fun waitingHintUses22MutedCapsuleAnd12SpText() {
        host.launch()
        val hint = rule.detailText("预计 10 月结算")
        val node = hint.fetchSemanticsNode()
        val d = node.layoutInfo.density.density
        assertEquals(
            12f,
            hint
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
        val outer =
            generateSequence(node.layoutInfo) { it.parentInfo }
                .map {
                    com.couli.app.product
                        .outerBounds(it)
                }.first { it.height >= 21 * d && it.width > node.boundsInRoot.width + 4 * d }
        assertEquals(22f, outer.height / d, 1f)
        rule.withOrderPixels(node) { pixels -> assertTrue(pixels.colorCount(outer, host.muted.toArgb()) > 40 * d) }
    }

    @Test fun singleCardActionIsOutlined40VisualAnd48Hit() {
        host.launch(OrderDetailVisualFixtures.detail("detail_blacklist"))
        val button = rule.onNodeWithTag(OrderTags.DETAIL_ACTION_PREFIX + "appeal").fetchSemanticsNode()
        val d = button.layoutInfo.density.density
        assertEquals(40f, button.boundsInRoot.height / d, 1f)
        button.assertTouch48()
        rule.withOrderPixels(button) { pixels ->
            assertEquals("单卡按钮不能是实心主按钮", 0, pixels.colorCount(button.boundsInRoot.insetBy(4 * d), host.brand.toArgb()))
            assertTrue("描边按钮需要实际边框", pixels.colorCount(button.boundsInRoot, host.control.toArgb()) > 0)
        }
    }

    @Test fun twoCardActionsUseFirstFilledSecondOutline() {
        host.launch(OrderDetailVisualFixtures.detail("detail_clawed_two"))
        val a = rule.onNodeWithTag(OrderTags.DETAIL_ACTION_PREFIX + "contact_cs").fetchSemanticsNode()
        val b = rule.onNodeWithTag(OrderTags.DETAIL_ACTION_PREFIX + "ledger").fetchSemanticsNode()
        rule.withOrderPixels(a) { pixels ->
            assertTrue(pixels.colorCount(a.boundsInRoot, host.brand.toArgb()) > 500)
            assertEquals(0, pixels.colorCount(b.boundsInRoot.insetBy(4f), host.brand.toArgb()))
        }
    }

    @Test fun timelineHasCollectionFutureReadingCurrentWeightAndActualConnector() {
        host.launch(OrderDetailVisualFixtures.detail("detail_settled"))
        val timeline = rule.onNodeWithTag(OrderTags.DETAIL_TIMELINE).fetchSemanticsNode()
        assertEquals(4, timeline.config[SemanticsProperties.CollectionInfo].rowCount)
        val current = rule.detailText("已结算", OrderTags.DETAIL_TIMELINE)
        assertEquals(
            600,
            current
                .textLayout()
                .layoutInput.style.fontWeight
                ?.weight,
        )
        assertEquals(
            host.success,
            current
                .textLayout()
                .layoutInput.style.color,
        )
        val paid = rule.detailText("付款", OrderTags.DETAIL_TIMELINE).fetchSemanticsNode()
        val received = rule.detailText("收货", OrderTags.DETAIL_TIMELINE).fetchSemanticsNode()
        val d = timeline.layoutInfo.density.density
        val track =
            androidx.compose.ui.geometry.Rect(
                timeline.boundsInRoot.left,
                paid.boundsInRoot.bottom,
                paid.boundsInRoot.left - d,
                received.boundsInRoot.top,
            )
        rule.withOrderPixels(timeline) { pixels ->
            assertTrue("节点间要有 borderSubtle 连接线", pixels.colorCount(track, host.border.toArgb()) >= 12 * d)
        }
        val future =
            rule
                .onAllNodes(hasAnyAncestor(hasTestTag(OrderTags.DETAIL_TIMELINE)), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .filter { it.config.getOrNull(SemanticsProperties.StateDescription) == "未发生" }
        assertEquals(1, future.size)
    }
}
