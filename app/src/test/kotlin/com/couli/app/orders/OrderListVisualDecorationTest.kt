package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.outerBounds
import com.couli.app.product.textLayout
import com.couli.core.orders.OrderDraftTexts
import com.couli.feature.orders.OrderTags
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** O13/O14/O18：真实文字属性与像素，不以“节点存在”替代胶囊、占位和骨架结构。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderListVisualDecorationTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderListVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun allStatusPillsHave12SpTextCorrectNeutralOrSuccessToneLight() = statusPills(false)

    @Test fun allStatusPillsHave12SpTextCorrectNeutralOrSuccessToneDark() = statusPills(true)

    @Test fun otherProductUsesDocumentPlaceholderWithoutImageLabelOrRawTitle() {
        host.launch(OrderListVisualFixtures.one("B"))
        rule.cardText("商品图", "B").assertIsDisplayed()
        host.refresh(OrderListVisualFixtures.one("OTHER"))
        val title = rule.cardText("好友购买的其他商品", "OTHER").assertIsDisplayed().fetchSemanticsNode()
        rule.cardText("商品图", "OTHER").assertDoesNotExist()
        rule.onNodeWithTag(OrderTags.COPY_PREFIX + "OTHER").assertDoesNotExist()
        val card = rule.onNodeWithTag(OrderTags.CARD_PREFIX + "OTHER").fetchSemanticsNode()
        val d = card.layoutInfo.density.density
        val thumb = Rect(card.boundsInRoot.left, title.boundsInRoot.top, card.boundsInRoot.left + 64 * d, title.boundsInRoot.top + 64 * d)
        rule.withOrderPixels(card) { bitmap ->
            assertTrue("无商品图的方块内仍应有文档图标", bitmap.colorCount(thumb.insetBy(8 * d), host.secondary.toArgb()) > 20 * d)
        }
    }

    @Test fun loadingHasNewCardSkeletonGeometryAndNoFakeMoneyOrVisibleLoadingText() {
        host.api.hold = CompletableDeferred()
        host.launch()
        val skeletons = rule.onAllNodesWithTag(OrderTags.SKELETON).fetchSemanticsNodes()
        assertTrue(skeletons.isNotEmpty())
        val first = skeletons.first()
        assertEquals(listOf("加载中"), first.config[SemanticsProperties.ContentDescription])
        rule.onAllNodes(hasText("加载中"), useUnmergedTree = true).fetchSemanticsNodes().forEach {
            assertFalse("加载中只供读屏，不画文字", it.config.contains(SemanticsProperties.Text))
        }
        rule.onNodeWithTag(OrderTags.CARD_PREFIX + "A").assertDoesNotExist()
        val d = first.layoutInfo.density.density
        rule.withOrderPixels(first) { bitmap ->
            val blocks = orderSkeletonBlocks(bitmap, first.boundsInRoot, host.muted.toArgb(), d.toInt())
            assertTrue("头行2块、64图、右列3行、两列金额、胶囊：$blocks", blocks.size >= 9)
            assertTrue("64×64缩略图", blocks.any { abs(it.width / d - 64) < 2 && abs(it.height / d - 64) < 2 })
            val top = blocks.minOf { it.top }
            assertTrue("徽标对面的时间骨架", blocks.count { abs(it.top - top) <= 2 * d } >= 2)
            val thumb = blocks.first { abs(it.width / d - 64) < 2 && abs(it.height / d - 64) < 2 }
            assertTrue("图片右侧3行", blocks.count { it.left >= thumb.right && it.top >= thumb.top && it.bottom <= thumb.bottom + d } >= 3)
        }
    }

    private fun statusPills(dark: Boolean) {
        host.launch(OrderListVisualFixtures.statusPage("PAID"), dark = dark)
        val statuses =
            OrderListVisualFixtures.group
                .getValue("statusPages")
                .jsonObject.keys
        assertEquals(12, statuses.size)
        statuses.forEach { status ->
            host.refresh(OrderListVisualFixtures.statusPage(status))
            val normalized = if (status == "FUTURE_STATUS") "UNKNOWN" else status
            val label = OrderDraftTexts.format("order_status.$normalized.label", mapOf("z" to ""))
            val nodes =
                rule.onAllNodes(
                    hasText(label) and hasAnyAncestor(hasTestTag(OrderTags.CARD_PREFIX + "STATUS_$status")),
                    useUnmergedTree = true,
                )
            val count = nodes.fetchSemanticsNodes().size
            assertTrue("状态文案不能空缺：$status", count > 0)
            val interaction = nodes[count - 1]
            val node = interaction.fetchSemanticsNode()
            val style = interaction.textLayout().layoutInput.style
            assertEquals("$status 的胶囊字号", 12f, style.fontSize.value, .01f)
            val green = status == "CREDITED" || status == "CREDITED_PART_CLAWED"
            assertEquals("$status 的文字令牌", if (green) host.successText else host.secondary, style.color)
            val pill = outerBounds(pillOf(node))
            val d = node.layoutInfo.density.density
            assertEquals("胶囊视觉高度", 22f, pill.height / d, 2f)
            assertTrue("胶囊不拉成全宽状态行", pill.width <= node.boundsInRoot.width + 40 * d)
            rule.withOrderPixels(node) { bitmap ->
                val background = if (green) host.successBackground else host.muted
                assertTrue("$status 胶囊底色", bitmap.colorCount(pill.insetBy(d), background.toArgb()) > pill.width * d)
            }
        }
    }

    /**
     * 胶囊节点 = 状态文字往上第一个修饰链里有 background 的布局节点（#24 第 15 条，D10-1456：只改取节点的方式）。
     * bbde9ea：Text 的父节点就是胶囊 Row（heightIn + background + padding），第一个带底色的祖先即该 Row；
     * 现行：Text → WholeNumberLayout 内层 → 外层（只有语义）→ NumberLine（同一串 heightIn + background + padding），
     * 第一个带底色的祖先即该 NumberLine。
     */
    private fun pillOf(text: SemanticsNode): LayoutInfo {
        val ancestors = generateSequence(text.layoutInfo.parentInfo) { it.parentInfo }
        return checkNotNull(ancestors.firstOrNull { it.paintsBackground() }) { "状态文字之上找不到带底色的胶囊节点" }
    }

    private fun LayoutInfo.paintsBackground(): Boolean =
        getModifierInfo().any { info ->
            val modifier = info.modifier
            modifier::class.simpleName == "BackgroundElement" || (modifier as? InspectableValue)?.nameFallback == "background"
        }
}
