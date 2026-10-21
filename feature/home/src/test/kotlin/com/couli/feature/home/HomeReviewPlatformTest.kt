package com.couli.feature.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntOffset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.PlatformIcon
import com.couli.core.designsystem.PlatformIconSize
import com.couli.core.designsystem.platformIconAssetPath
import com.couli.core.home.HomeIcon
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** r1 S2-3：实际首页平台格与 F-X-05a 的 HOME 原图在同一位置逐像素比较，不录基准。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeReviewPlatformTest {
    @get:Rule
    val rule = createComposeRule()
    private val driver by lazy { HomeVisualTestDriver(rule) }

    @After
    fun close() = driver.close()

    @Test
    fun taobaoCellUsesOfficialHomeIcon() = assertOfficial("taobao", "淘宝", 1, "taobao.png")

    @Test
    fun jdCellUsesOfficialHomeIcon() = assertOfficial("jd", "京东", 2, "jd.png")

    @Test
    fun pddCellUsesOfficialHomeIcon() = assertOfficial("pdd", "拼多多", 3, "pinduoduo.png")

    @Test
    fun missingOfficialPlatformKeepsItsVisibleTextAndSingleSpokenName() {
        assertNull(platformIconAssetPath("kuaishou"))
        // 现协议只准 taobao/jd/pdd。此例直接进入真实 homeCard 渲染层，不扩协议白名单。
        driver.show("icons", wrap = {
            val state by driver.controller.state.collectAsState()
            val card = state.cards.filterIsInstance<HomeCardUi.IconGrid>().firstOrNull()
            LazyColumn {
                if (card != null) {
                    val cells = card.cells.toMutableList()
                    cells[0] = cells[0].copy(icon = HomeIcon.Builtin("platform.kuaishou"), title = "快手")
                    homeCard(card.copy(cells = cells), driver.controller, active = true, reader = false, maxWidth = 600)
                }
            }
        })
        assertCellName(1, "快手")
        // F-X-05a 的缺图入口不占位；首页仍必须保留原格及可见文字，不能因没有官方图把格过滤掉。
        rule.onNodeWithTag(HomeTags.tap("icons", 1)).assertHasClickAction()
    }

    private fun assertOfficial(
        code: String,
        name: String,
        slot: Int,
        file: String,
    ) {
        assertEquals("platform/$file", platformIconAssetPath(code))
        assertEquals(44f, PlatformIconSize.HOME.dp.value, 0f)
        var referenceAt by mutableStateOf<Rect?>(null)
        driver.show("icons", wrap = { home ->
            val at = referenceAt
            if (at == null) {
                home()
            } else {
                Box(Modifier.fillMaxSize().background(CouliColors.backgroundSurface)) {
                    PlatformIcon(
                        platformCode = code,
                        size = PlatformIconSize.HOME,
                        modifier = Modifier.offset { IntOffset(at.left.roundToInt(), at.top.roundToInt()) }.testTag(REFERENCE),
                    )
                }
            }
        })
        assertCellName(slot, name)
        val actualNode = rule.onNodeWithTag(HomeTags.PLATFORM_CELL_PREFIX + "platform.$code", useUnmergedTree = true)
        val node = actualNode.assertIsDisplayed().fetchSemanticsNode()
        val at = node.boundsInRoot
        val density = node.layoutInfo.density.density
        assertEquals("HOME 宽度", 44f, at.width / density, 0.5f)
        assertEquals("HOME 高度", 44f, at.height / density, 0.5f)
        val actual = pixels(actualNode)
        try {
            rule.runOnUiThread { referenceAt = at }
            val reference = rule.onNodeWithTag(REFERENCE, useUnmergedTree = true)
            assertEquals("同一绝对位置取图", at, reference.fetchSemanticsNode().boundsInRoot)
            val expected = pixels(reference)
            try {
                assertSamePixels(actual, expected)
            } finally {
                expected.recycle()
            }
        } finally {
            actual.recycle()
        }
    }

    private fun assertCellName(
        slot: Int,
        name: String,
    ) {
        val tag = HomeTags.tap("icons", slot)
        rule.onNodeWithTag(tag).assertIsDisplayed().assertContentDescriptionEquals(name)
        val inCell = hasAnyAncestor(hasTestTag(tag))
        rule.onAllNodes((hasText(name) or hasContentDescription(name)) and (hasTestTag(tag) or inCell)).assertCountEquals(1)
        rule.onAllNodes(hasText(name) and inCell, useUnmergedTree = true).assertCountEquals(1)
        rule.onNode(hasText(name) and inCell, useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription) and inCell).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused) and inCell).assertCountEquals(0)
    }

    private fun pixels(interaction: SemanticsNodeInteraction): Bitmap {
        val node = interaction.fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val view = (node.root as ViewRootForTest).view
        val whole = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            rule.runOnUiThread { view.draw(Canvas(whole)) }
            return Bitmap.createBitmap(
                whole,
                bounds.left.roundToInt(),
                bounds.top.roundToInt(),
                bounds.width.roundToInt(),
                bounds.height.roundToInt(),
            )
        } finally {
            whole.recycle()
        }
    }

    private fun assertSamePixels(
        actual: Bitmap,
        expected: Bitmap,
    ) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        var different = 0
        val colors = mutableSetOf<Int>()
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                val a = actual.getPixel(x, y)
                val e = expected.getPixel(x, y)
                colors += e
                if (listOf(0, 8, 16, 24).any { abs((a ushr it and 255) - (e ushr it and 255)) > 2 }) different++
            }
        }
        assertTrue("官方图参照不能为空白", colors.size > 2)
        assertEquals("首页未渲染同一张官方 HOME 图", 0, different)
    }

    private companion object {
        const val REFERENCE = "home_review_platform_reference"
    }
}
