package com.couli.app.wallet

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.productPixels
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletVisualSkeletonTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WalletVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w17LightLoadingHasVisibleHeroEstimateAndEntrySkeletons() = checkSkeleton(false)

    @Test fun w17DarkLoadingHasVisibleHeroEstimateAndEntrySkeletons() = checkSkeleton(true)

    @Test fun m11OnlyMissingHeaderPlaceholdersPassLegacyButFailRegionalOracle() {
        withSkeleton(false) { pixels, blocks, density ->
            assertWalletVisualSkeletonBlocks(pixels, blocks, host.muted.toArgb(), density)
            val header = blocks.take(2)
            val mutant = erase(pixels, header)
            try {
                // 只擦主卡首行两块，未触碰金额行、底部两格、预估或入口；这是 M11，不能拿全空白 M15 代替。
                assertPixelsOutsideUnchanged(pixels, mutant, header)
                assertWalletSkeletonGlobalCoverage(mutant, host.muted.toArgb(), density)
                assertWalletVisualSkeletonBlocks(mutant, blocks.drop(2), host.muted.toArgb(), density)
                header.forEach { block -> assertMissing(mutant, block, density) }
            } finally {
                mutant.recycle()
            }
        }
    }

    @Test fun everyHeroPlaceholderIsIndividuallyRequiredAtItsOwnPosition() {
        withSkeleton(false) { pixels, blocks, density ->
            assertWalletVisualSkeletonBlocks(pixels, blocks, host.muted.toArgb(), density)
            blocks.forEach { block ->
                val mutant = erase(pixels, listOf(block))
                try {
                    assertMissing(mutant, block, density)
                    assertWalletVisualSkeletonBlocks(mutant, blocks - block, host.muted.toArgb(), density)
                } finally {
                    mutant.recycle()
                }
                // 像素存在也必须在对应位置；不能用页面别处的骨架抵数。
                val displaced = block.copy(bounds = block.bounds.translate(0f, 12 * density))
                assertThrows(AssertionError::class.java) {
                    assertWalletVisualSkeletonBlocks(pixels, listOf(displaced), host.muted.toArgb(), density)
                }
            }
        }
    }

    private fun checkSkeleton(dark: Boolean) {
        withSkeleton(dark) { pixels, blocks, density ->
            // test-change r1 S2-3：v1 总条数/总高度漏掉 M11；保留整体检查，追加每块位置及可见面积。
            assertWalletSkeletonGlobalCoverage(pixels, host.muted.toArgb(), density)
            assertWalletVisualSkeletonBlocks(pixels, blocks, host.muted.toArgb(), density)
        }
    }

    private fun withSkeleton(
        dark: Boolean,
        check: (Bitmap, List<WalletVisualSkeletonBlock>, Float) -> Unit,
    ) {
        host.api.hold = CompletableDeferred()
        host.launch(dark = dark)
        val density = rule.onRoot().walletDp()
        val loading = rule.onNodeWithContentDescription("加载中").walletBounds()
        val pixels = rule.productPixels()
        try {
            check(pixels, walletHeroSkeletonBlocks(loading, density), density)
        } finally {
            pixels.recycle()
        }
    }

    private fun assertMissing(
        pixels: Bitmap,
        block: WalletVisualSkeletonBlock,
        density: Float,
    ) {
        val failure =
            assertThrows(AssertionError::class.java) {
                assertWalletVisualSkeletonBlocks(pixels, listOf(block), host.muted.toArgb(), density)
            }
        assertTrue("必须由被删占位自己的像素断言拦住", failure.message.orEmpty().contains(block.name))
    }

    private fun erase(
        pixels: Bitmap,
        blocks: List<WalletVisualSkeletonBlock>,
    ): Bitmap {
        val mutant = pixels.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutant)
        val paint = Paint().apply { color = host.surface.toArgb() }
        blocks.forEach { block ->
            val b = block.bounds.inflate(1f)
            canvas.drawRect(b.left, b.top, b.right, b.bottom, paint)
        }
        return mutant
    }

    private fun assertPixelsOutsideUnchanged(
        original: Bitmap,
        mutant: Bitmap,
        erased: List<WalletVisualSkeletonBlock>,
    ) {
        var outsideChanges = 0
        for (y in 0 until original.height) {
            for (x in 0 until original.width) {
                val inside =
                    erased.any {
                        x + 0.5f in it.bounds.left - 1..it.bounds.right + 1 &&
                            y + 0.5f in it.bounds.top - 1..it.bounds.bottom + 1
                    }
                if (!inside && original.getPixel(x, y) != mutant.getPixel(x, y)) outsideChanges++
            }
        }
        assertEquals("M11 只删首行两块，其余画面必须原样", 0, outsideChanges)
    }
}
