package com.couli.app.product

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.product.ProductTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** r1 S2-1：ProductPriceCompareCancelled-v2.dc.html 的胶囊 height:32px；
 * 链接 min-height:44px + margin:-6px 0，使视觉行仍32。Android 命中提升到48，不能把视觉行撑高。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProductVisualCompareLayoutTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { ProductVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun capsuleAndVisualRowStay32DpWithin2DpTolerance() {
        host.launch("pdd_price_compare")
        val row = rule.onNodeWithTag(ProductTags.COMPARE_REASON).fetchSemanticsNode()
        val reason = rule.onNodeWithText("这次被判为比价，没有返利", useUnmergedTree = true).fetchSemanticsNode()
        val capsule = outerBounds(checkNotNull(reason.layoutInfo.parentInfo))
        val density = row.layoutInfo.density.density
        // 1倍稿32px映射32dp；±2dp仅容纳像素取整/描边，不接受48dp视觉行。
        assertEquals("灰胶囊视觉高", 32f, capsule.height / density, 2f)
        assertEquals("相似入口命中区不得撑高视觉行", 32f, row.boundsInRoot.height / density, 2f)
        val label = rule.onNodeWithText("看看相似商品", useUnmergedTree = true).fetchSemanticsNode()
        assertContainsRect(row.boundsInRoot, label.boundsInRoot)
        assertEquals(capsule.center.y, label.boundsInRoot.center.y, 2 * density)
    }

    @Test fun similarHas48DpHitAreaWithoutOverlapAndBothExtendedEdgesWork() {
        host.launch("pdd_price_compare")
        val similar = rule.onNodeWithText("看看相似商品").fetchSemanticsNode()
        val touch = similar.touchBoundsInRoot
        val density = similar.layoutInfo.density.density
        assertTrue("实际命中区至少48×48dp", touch.width >= 48 * density && touch.height >= 48 * density)
        val reason = rule.onNodeWithText("这次被判为比价，没有返利", useUnmergedTree = true).fetchSemanticsNode()
        assertFalse("不能侵占灰胶囊", touch.overlaps(outerBounds(checkNotNull(reason.layoutInfo.parentInfo))))
        rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.id != similar.id }.forEach {
            assertFalse("不能与其他可点项的命中区重叠", touch.overlaps(it.touchBoundsInRoot))
        }
        // 真触摸上下边缘，不能只靠 semantics.performClick 绕过命中测试。
        val origin =
            rule
                .onRoot()
                .fetchSemanticsNode()
                .boundsInRoot.topLeft
        listOf(touch.top + density, touch.bottom - density).forEach { y ->
            rule.onRoot().performTouchInput { click(Offset(touch.center.x, y) - origin) }
        }
        assertEquals(List(2) { ProductVisualFixtures.card("pdd_price_compare") }, host.similar)
        assertTrue(host.buys.isEmpty())
    }
}
