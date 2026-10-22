package com.couli.app.purchase

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.productPixels
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.CouliToast
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** T1 正反控制：真实 CouliToast + 同一像素判据；错误只注入测试参数，不变异产品源码。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PurchaseVisualToastOracleTest {
    @get:Rule val rule = createComposeRule()

    @Test fun correctInfoPasses() = checkIcon(CouliIcons.info, CouliIcons.info)

    @Test fun correctWarningPasses() = checkIcon(CouliIcons.alert, CouliIcons.alert)

    @Test fun correctCopyPasses() = checkIcon(CouliIcons.copy, CouliIcons.copy)

    @Test fun missingIconFailsInkGuard() = checkIcon(null, CouliIcons.info, failure = "轻提示缺指定颜色图标")

    @Test fun secondaryWarningColorFailsInkGuard() =
        checkIcon(
            CouliIcons.alert,
            CouliIcons.alert,
            wrongColor = true,
            failure = "轻提示缺指定颜色图标",
        )

    @Test fun secondaryWarningColorFailsInkGuardInDark() =
        checkIcon(CouliIcons.alert, CouliIcons.alert, wrongColor = true, failure = "轻提示缺指定颜色图标", dark = true)

    @Test fun copyInsteadOfInfoFailsContour() = checkIcon(CouliIcons.copy, CouliIcons.info, failure = "图标轮廓不符")

    @Test fun infoInsteadOfWarningFailsContour() = checkIcon(CouliIcons.info, CouliIcons.alert, failure = "图标轮廓不符")

    @Test fun infoInsteadOfCopyFailsContour() = checkIcon(CouliIcons.info, CouliIcons.copy, failure = "图标轮廓不符")

    @Test fun channelToleranceStopsAtTwoInBothDirections() {
        // 各通道分别碰边界，防止只查 RGB、误用通道和/平均值，或无意把容差扩大到 3。
        val tint = 0x80808080.toInt()
        for (shift in listOf(24, 16, 8, 0)) {
            for (delta in -2..2) {
                assertTrue("shift=$shift delta=$delta", (tint + (delta shl shift)).matchesToastIconTint(tint))
            }
            for (delta in listOf(-3, 3)) {
                assertFalse("shift=$shift delta=$delta", (tint + (delta shl shift)).matchesToastIconTint(tint))
            }
        }
        assertTrue(0x7e82817f.matchesToastIconTint(tint))
    }

    @Test fun twoChannelUnitsAndOnePixelShiftStillPassTogether() {
        val tint = 0xff6080a0.toInt()
        val actual = Rect(4f, 4f, 44f, 44f)
        val expected = Rect(48f, 4f, 88f, 44f)
        val shifts = (-1..1).flatMap { dx -> (-1..1).map { dy -> dx to dy } }
        for ((dx, dy) in shifts) {
            for (color in listOf(0xff5e7e9e.toInt(), 0xff6282a2.toInt())) {
                val pixels = Bitmap.createBitmap(96, 48, Bitmap.Config.ARGB_8888)
                try {
                    // 黑底会暴露参考框外一圈漏归一；纯色方块使 ±2 边界不受矢量抗锯齿影响。
                    pixels.eraseColor(android.graphics.Color.BLACK)
                    val canvas = Canvas(pixels)
                    val paint = Paint()
                    paint.color = tint
                    canvas.drawRect(58f, 14f, 68f, 24f, paint)
                    paint.color = color
                    canvas.drawRect(14f + dx, 14f + dy, 24f + dx, 24f + dy, paint)
                    pixels.assertToastIcon(actual, expected, tint)
                } finally {
                    pixels.recycle()
                }
            }
        }
    }

    private fun checkIcon(
        actualIcon: ImageVector?,
        expectedIcon: ImageVector,
        wrongColor: Boolean = false,
        failure: String? = null,
        dark: Boolean = false,
    ) {
        var tint = Color.Unspecified
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                tint = if (expectedIcon == CouliIcons.alert) CouliColors.statusWarningText else CouliColors.textSecondary
                Box(Modifier.fillMaxSize().padding(4.dp)) {
                    CouliToast(
                        "样例提示",
                        modifier = Modifier.align(Alignment.TopStart).testTag("oracle_toast"),
                        icon = actualIcon,
                        iconTint = if (wrongColor) CouliColors.textSecondary else tint,
                    )
                    Icon(
                        expectedIcon,
                        null,
                        tint = tint,
                        modifier = Modifier.align(Alignment.BottomEnd).size(20.dp).testTag("oracle_reference"),
                    )
                }
            }
        }
        val actual = rule.onNodeWithTag("oracle_toast").fetchSemanticsNode().toastIconBounds()
        val expected = rule.onNodeWithTag("oracle_reference").fetchSemanticsNode().boundsInRoot
        val pixels = rule.productPixels()
        try {
            if (failure == null) {
                pixels.assertToastIcon(actual, expected, tint.toArgb())
            } else {
                val error = assertThrows(AssertionError::class.java) { pixels.assertToastIcon(actual, expected, tint.toArgb()) }
                assertTrue("预期 $failure，实际 ${error.message}", error.message.orEmpty().startsWith(failure))
                println("EXPECTED_TOAST_REJECTION ${error.message}")
            }
        } finally {
            pixels.recycle()
        }
    }
}
