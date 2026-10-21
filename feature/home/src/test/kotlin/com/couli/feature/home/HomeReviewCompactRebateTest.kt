package com.couli.feature.home

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.ProductCardKeys
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** r1 S2-5：画板首行 18px + 2dp 字体容差；视觉行高和实际触摸目标分别验收。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h2400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeReviewCompactRebateTest {
    @get:Rule
    val rule = createComposeRule()
    private val driver by lazy { HomeVisualTestDriver(rule) }

    @After
    fun close() = driver.close()

    @Test
    fun scrollRebateHeaderStaysCompact() = assertCompactHeader("scroll")

    @Test
    fun gridRebateHeaderStaysCompact() = assertCompactHeader("grid")

    @Test
    fun feedRebateHeaderStaysCompact() = assertCompactHeader("feed")

    @Test
    fun scrollInfoTargetIs48DpUsableAndDoesNotOverlapNeighbors() = assertTouchTarget("scroll")

    @Test
    fun gridInfoTargetIs48DpUsableAndDoesNotOverlapNeighbors() = assertTouchTarget("grid")

    @Test
    fun feedInfoTargetIs48DpUsableAndDoesNotOverlapNeighbors() = assertTouchTarget("feed")

    private fun assertCompactHeader(section: String) {
        driver.show(section)
        val data = HomeReviewFixtures.firstProduct(driver.controller).data
        val inTile = hasAnyAncestor(hasTestTag(HomeTags.tap(section, 1)))
        val amount = rule.onNode(hasText(data.rebate.amountSegments.first()) and inTile, useUnmergedTree = true).fetchSemanticsNode()
        val info = rule.onNode(hasContentDescription(data.infoLabel) and inTile, useUnmergedTree = true).fetchSemanticsNode()
        val block = commonLayout(amount.layoutInfo, info.layoutInfo)
        // 横滑的「预估返」本来就读屏隐藏；用金额行起点减返利块内容区起点量首行，不要求恢复重复读屏文字。
        val top = block.coordinates.boundsInRoot().top
        val height = amount.boundsInRoot.top - top
        val heightDp = height / block.density.density
        assertTrue("返利首行应 ≤18+2dp，实际 $heightDp", heightDp <= 20f)
        assertTrue("返利首行不能消失", heightDp > 0f)
        assertEquals("说明图标应在首行垂直居中", top + height / 2f, info.boundsInRoot.center.y, 2f * block.density.density)
    }

    private fun assertTouchTarget(section: String) {
        driver.show(section)
        val data = HomeReviewFixtures.firstProduct(driver.controller).data
        val info =
            rule
                .onNode(
                    hasContentDescription(data.infoLabel) and hasAnyAncestor(hasTestTag(HomeTags.tap(section, 1))),
                    useUnmergedTree = true,
                ).fetchSemanticsNode()
        val target = info.touchBoundsInRoot
        val density = info.layoutInfo.density.density
        assertTrue("口径说明触摸宽度 ≥48dp", target.width / density >= 48f)
        assertTrue("口径说明触摸高度 ≥48dp", target.height / density >= 48f)
        val ancestors = generateSequence(info) { it.parent }.map(SemanticsNode::id).toSet()
        rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().filter { it.id !in ancestors }.forEach {
            assertFalse("说明命中区与相邻可点区重叠：${it.config}", target.overlaps(it.touchBoundsInRoot))
        }
        // 四个方向距中心 23dp：不能用一个仅 20dp 可点击的 i 配上虚假的尺寸声明蒙混过关。
        val delta = 23f * density
        listOf(Offset(-delta, 0f), Offset(delta, 0f), Offset(0f, -delta), Offset(0f, delta)).forEach { offset ->
            rule.onRoot().performTouchInput { click(target.center + offset) }
            rule.onNodeWithText(driver.controller.cardTexts.text(ProductCardKeys.DISCLAIMER_TITLE)).assertIsDisplayed()
            rule.onNodeWithText(driver.controller.cardTexts.text(ProductCardKeys.DISCLAIMER_CLOSE)).performClick()
        }
    }

    private fun commonLayout(
        first: LayoutInfo,
        second: LayoutInfo,
    ): LayoutInfo {
        val ancestors = generateSequence(first) { it.parentInfo }.toList()
        return generateSequence(second) { it.parentInfo }.first { candidate -> ancestors.any { it === candidate } }
    }
}
