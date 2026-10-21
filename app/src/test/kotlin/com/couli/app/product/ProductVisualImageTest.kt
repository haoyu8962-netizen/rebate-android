package com.couli.app.product

import android.app.Application
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.ProductImageLoader
import com.couli.feature.product.ProductTags
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** D2 / D3 / D6：真实宽位图像素，不能用标签字符串假装已经 contain / 去色。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProductVisualImageTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { ProductVisualTestHost(rule) }
    private val description get() =
        "商品图片，" +
            ProductVisualFixtures
                .card()
                .getValue("title")
                .jsonPrimitive.content

    @After fun close() = host.close()

    @Test fun heroContainsBothEdgesAndKeepsPlaceholderAboveAndBelow() = assertContain(false)

    @Test fun darkHeroContainsBothEdgesAndKeepsPlaceholder() = assertContain(true)

    private fun assertContain(dark: Boolean) {
        host.launch(dark = dark)
        val bounds = rule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
        val bitmap = rule.productPixels()
        try {
            val center = bounds.center
            assertEquals("上部留底", host.placeholder.toArgb(), bitmap.getPixel(center.x.toInt(), (bounds.top + bounds.height * .1f).toInt()))
            assertEquals(
                "下部留底",
                host.placeholder.toArgb(),
                bitmap.getPixel(center.x.toInt(), (bounds.bottom - bounds.height * .1f).toInt()),
            )
            assertEquals("左红边完整", Color.RED, bitmap.getPixel((bounds.left + bounds.width * .1f).toInt(), center.y.toInt()))
            assertEquals("右蓝边完整", Color.BLUE, bitmap.getPixel((bounds.right - bounds.width * .1f).toInt(), center.y.toInt()))
            assertEquals(Color.GREEN, bitmap.getPixel(center.x.toInt(), center.y.toInt()))
        } finally {
            bitmap.recycle()
        }
        rule.onAllNodes(hasContentDescription(description)).assertCountEquals(1)
        val density =
            rule
                .onNodeWithTag(ProductTags.PAGE)
                .fetchSemanticsNode()
                .layoutInfo.density.density
        assertEquals("画板头图 358×262", 358f / 262f, bounds.width / bounds.height, .02f)
        assertEquals(358f, bounds.width / density, 1f)
    }

    @Test fun failedImageStillHasPlaceholderAndTheSameAccessibleName() {
        host.imageLoader = ProductImageLoader { null }
        host.launch()
        val bounds = rule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
        val bitmap = rule.productPixels()
        try {
            assertEquals(host.placeholder.toArgb(), bitmap.getPixel((bounds.left + 20).toInt(), (bounds.top + 20).toInt()))
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun adLabelIsVerbatimAtTopLeftAndReadBeforeImageName() {
        host.launch("ad_label")
        val label = "广告 · 平台原文"
        rule.onAllNodesWithText(label, useUnmergedTree = true).assertCountEquals(1)
        val ad = rule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
        val hero = rule.onNode(hasContentDescription(description, substring = true)).fetchSemanticsNode()
        val density = hero.layoutInfo.density.density
        assertEquals("左上角标 left=8dp（文字有胶囊内边距）", 8f, (ad.boundsInRoot.left - hero.boundsInRoot.left) / density, 8f)
        assertTrue(ad.boundsInRoot.top >= hero.boundsInRoot.top + 7 * density)
        assertTrue(ad.boundsInRoot.top <= hero.boundsInRoot.top + 16 * density)
        rule.onNode(hasContentDescription(description, substring = true)).assert(
            SemanticsMatcher("角标在头图读屏名前") {
                val spoken = it.config[SemanticsProperties.ContentDescription].joinToString("，")
                spoken.indexOf(label) >= 0 && spoken.indexOf(label) < spoken.indexOf(description)
            },
        )
    }

    @Test fun loadedHeroMergedNodeHasImageRole() {
        host.launch()
        assertMergedImageRole()
    }

    @Test fun failedHeroMergedNodeHasImageRole() {
        host.imageLoader = ProductImageLoader { null }
        host.launch()
        assertMergedImageRole()
    }

    @Test fun adLabelHeroMergedNodeHasImageRole() {
        host.launch("ad_label")
        assertMergedImageRole()
    }

    private fun assertMergedImageRole() {
        // 默认 merged tree，断言最终读屏项；只给内部装饰 Image 补角色不算通过。
        rule.onAllNodes(hasContentDescription(description, substring = true)).assertCountEquals(1)
        rule
            .onNode(hasContentDescription(description, substring = true))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
    }

    @Test fun nullAdLabelDoesNotInventPromotion() {
        host.launch()
        listOf("推广", "广告", "广告 · 平台原文").forEach { rule.onNodeWithText(it).assertDoesNotExist() }
        rule.onNodeWithContentDescription(description).assertExists()
    }

    @Test fun offShelfUsesMutedCapsuleAndSecondaryTitle() {
        host.launch("tb_off_shelf")
        val status = rule.onNodeWithText("商品已下架", useUnmergedTree = true).fetchSemanticsNode()
        val capsule = outerBounds(checkNotNull(status.layoutInfo.parentInfo))
        val density = status.layoutInfo.density.density
        val pixels = rule.productPixels()
        try {
            assertEquals(
                "下架灰胶囊",
                host.muted.toArgb(),
                pixels.getPixel((capsule.left + 4 * density).toInt(), capsule.center.y.toInt()),
            )
        } finally {
            pixels.recycle()
        }
        val title =
            ProductVisualFixtures
                .card()
                .getValue("title")
                .jsonPrimitive.content
        assertEquals(
            "下架标题次要色",
            host.secondary,
            rule
                .onNodeWithText(title, substring = true, useUnmergedTree = true)
                .textLayout()
                .layoutInput.style.color,
        )
    }

    @Test fun offShelfImageReallyLosesSaturation() = assertGrayscale(false)

    @Test fun darkOffShelfImageReallyLosesSaturation() = assertGrayscale(true)

    private fun assertGrayscale(dark: Boolean) {
        host.launch("tb_off_shelf", dark = dark)
        val bounds = rule.onNode(hasContentDescription(description, substring = true)).fetchSemanticsNode().boundsInRoot
        val bitmap = rule.productPixels()
        try {
            val c = bitmap.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt())
            assertEquals("绿色 fixture 必须去色", Color.red(c).toFloat(), Color.green(c).toFloat(), 1f)
            assertEquals(Color.green(c).toFloat(), Color.blue(c).toFloat(), 1f)
        } finally {
            bitmap.recycle()
        }
    }
}
