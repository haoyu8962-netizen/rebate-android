package com.couli.app.wallet

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** r1 S2-1：首行必须实际换行，随后分别验证几何与真实命中；几何失败不能挡住点击验证。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w320dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletVisualHeroHitTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WalletVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w12WrappedEyeAndRecordsHaveSeparate48DpTargets() {
        launchWrapped()
        val eye = eyeTarget()
        val records = recordsTarget()
        val density = rule.onRoot().walletDp()
        listOf("眼睛" to eye, "提现记录" to records).forEach { (name, bounds) ->
            assertTrue("$name 命中宽度 $bounds", bounds.width >= 48 * density)
            assertTrue("$name 命中高度 $bounds", bounds.height >= 48 * density)
        }
        assertFalse("换行后两个48dp命中区不能重叠：eye=$eye records=$records", eye.overlaps(records))
        host.assertSummaryReads(1)
    }

    @Test fun w12EyeEdgesOnlyToggleMaskIncludingEdgeFacingRecords() {
        launchWrapped()
        // 两区横向交集内的边缘才可暴露 r1 问题；只点中心/左边会漏掉相邻入口抢点。
        val density = rule.onRoot().walletDp()
        val bounds = eyeTarget()
        val x = facingEdgeX(bounds, recordsTarget())
        val edges =
            listOf(
                Offset(x, bounds.bottom - density),
                Offset(x, bounds.top + density),
                Offset(bounds.left + density, bounds.center.y),
                Offset(bounds.right - density, bounds.center.y),
            )
        val failures = mutableListOf<String>()
        edges.forEach { point ->
            val hidden = host.mask.hidden.value
            val routes = host.env.opened.toList()
            tap(point)
            if (host.mask.hidden.value != !hidden || host.env.opened != routes) {
                failures += "眼睛边缘 $point 应只切换遮罩：hidden=$hidden -> ${host.mask.hidden.value}，routes=$routes -> ${host.env.opened}"
            }
            host.assertSummaryReads(1)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun w12RecordsEdgesOnlyOpenRecordsIncludingEdgeFacingEye() {
        launchWrapped()
        val density = rule.onRoot().walletDp()
        val bounds = recordsTarget()
        val x = facingEdgeX(bounds, eyeTarget())
        val edges =
            listOf(
                Offset(x, bounds.top + density),
                Offset(x, bounds.bottom - density),
                Offset(bounds.left + density, bounds.center.y),
                Offset(bounds.right - density, bounds.center.y),
            )
        val failures = mutableListOf<String>()
        edges.forEach { point ->
            val hidden = host.mask.hidden.value
            val routes = host.env.opened.toList()
            tap(point)
            if (host.env.opened != routes + ("WithdrawRecords" to JsonObject(emptyMap())) || host.mask.hidden.value != hidden) {
                failures += "提现记录边缘 $point 应只打开一次原路由：routes=$routes -> ${host.env.opened}，hidden=$hidden -> ${host.mask.hidden.value}"
            }
            host.assertSummaryReads(1)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun headerCenterTapsProveTouchCoordinatesAndCallbacksWork() {
        launchWrapped()
        tap(eyeTarget().center)
        assertTrue(host.mask.hidden.value)
        assertTrue(host.env.opened.isEmpty())
        tap(recordsTarget().center)
        assertEquals(listOf("WithdrawRecords" to JsonObject(emptyMap())), host.env.opened)
        assertTrue(host.mask.hidden.value)
        host.assertSummaryReads(1)
    }

    private fun launchWrapped() {
        host.launch(fontScale = 2f)
        val label = rule.walletText("可提现余额").walletBounds()
        val records = rule.walletText("提现记录").walletBounds()
        assertEquals("必须在320dp运行", 320f, rule.onRoot().walletBounds().width / rule.onRoot().walletDp(), 1f)
        assertEquals(
            2f,
            rule
                .walletText("可提现余额")
                .fetchSemanticsNode()
                .layoutInfo.density.fontScale,
            0f,
        )
        assertTrue("前提：提现记录已换到标签下一行", records.top >= label.bottom)
    }

    private fun eyeTarget(): Rect =
        rule.onNodeWithContentDescription(if (host.mask.hidden.value) "显示金额" else "隐藏金额").fetchSemanticsNode().touchBoundsInRoot

    private fun recordsTarget(): Rect = rule.onNode(hasClickAction() and hasText("提现记录")).fetchSemanticsNode().touchBoundsInRoot

    private fun facingEdgeX(
        target: Rect,
        neighbor: Rect,
    ): Float {
        val left = maxOf(target.left, neighbor.left)
        val right = minOf(target.right, neighbor.right)
        // 修复若改为横向错开也允许；当前布局横向相交时，必须覆盖这个最易抢点的位置。
        return if (left < right) (left + right) / 2 else target.center.x
    }

    private fun tap(point: Offset) {
        val origin = rule.onRoot().walletBounds().topLeft
        rule.onRoot().performTouchInput { click(point - origin) }
        rule.waitForIdle()
    }
}
