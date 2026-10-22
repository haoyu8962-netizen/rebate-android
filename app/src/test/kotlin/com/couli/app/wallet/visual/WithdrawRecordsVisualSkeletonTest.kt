package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.productPixels
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawRecordsVisualSkeletonTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawRecordsVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w35InitialLoadingHasEachAmountStatusChannelAndHintBlockLight() = initial(false)

    @Test fun w35InitialLoadingHasEachAmountStatusChannelAndHintBlockDark() = initial(true)

    @Test fun w35LoadingMoreUsesTheSameFourPartRecordSkeleton() {
        host.launch("pagination")
        assertTrue(host.controller.state.value.loadingMore)
        assertBlocks("records_loading_more", 1)
        host.assertCalls("listWithdrawals", "listWithdrawals")
    }

    private fun initial(dark: Boolean) {
        host.api.hold = CompletableDeferred()
        host.launch(dark = dark)
        assertBlocks("records_loading", 3)
    }

    private fun assertBlocks(
        tag: String,
        rows: Int,
    ) {
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val d = node.layoutInfo.density.density
        val frame = node.boundsInRoot
        val pixels = rule.productPixels()
        val ink = host.colors.getValue("background.muted").toArgb()
        repeat(rows) { row ->
            val top = frame.top + (12 + row * 98) * d
            val left = frame.left + 16 * d
            val right = frame.right - 16 * d
            val areas =
                listOf(
                    "金额" to Rect(left, top, left + 92 * d, top + 22 * d),
                    "状态" to Rect(right - 70 * d, top, right, top + 22 * d),
                    "时间账号" to Rect(left, top + 30 * d, left + 200 * d, top + 48 * d),
                    "副文案" to Rect(left, top + 56 * d, left + 240 * d, top + 74 * d),
                )
            areas.forEach { (name, area) ->
                assertTrue(
                    "第${row + 1}行${name}不能只有空盒或一条细边",
                    pixels.recordColorCount(
                        area,
                        ink,
                    ) >= area.width * area.height * .75f,
                )
            }
        }
    }
}
