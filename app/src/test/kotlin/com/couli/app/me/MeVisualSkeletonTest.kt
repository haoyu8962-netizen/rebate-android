package com.couli.app.me

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.productPixels
import com.couli.feature.me.MeTags
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1032dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeVisualSkeletonTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { MeVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun m17LightHasEachIdentityBalanceAndGridPlaceholder() = skeleton(false)

    @Test fun m17DarkHasEachIdentityBalanceAndGridPlaceholder() = skeleton(true)

    private fun skeleton(dark: Boolean) {
        host.api.hold = CompletableDeferred()
        host.launch(dark = dark)
        val container = rule.meTag(MeTags.ME_LOADING)
        val origin = container.meBounds()
        val d = container.meDensity()
        val pixels = rule.productPixels()
        val avatar = rule.meTag("me_skeleton_avatar").meBounds()
        assertMeDp(56f, avatar.width, d)
        assertMeDp(56f, avatar.height, d)
        assertMeDp(16f, avatar.left - origin.left, d)
        assertMeDp(16f, avatar.top - origin.top, d)
        val name = rule.meTag("me_skeleton_name").meBounds()
        val invite = rule.meTag("me_skeleton_invite").meBounds()
        assertTrue(name.width >= 80 * d && name.height >= 12 * d)
        assertTrue(invite.width >= 120 * d && invite.height >= 12 * d)
        assertMeDp(12f, name.left - avatar.right, d)
        assertTrue(invite.top > name.bottom && invite.bottom <= avatar.bottom)
        val balance = rule.meTag("me_skeleton_balance").meBounds()
        val grid = rule.meTag("me_skeleton_grid").meBounds()
        assertTrue(balance.top >= avatar.bottom + 16 * d && balance.height >= 160 * d)
        assertTrue(grid.top >= balance.bottom + 12 * d - 1 && grid.height >= 100 * d)
        for ((label, rect) in listOf("头像" to avatar, "昵称" to name, "邀请码" to invite, "余额卡" to balance, "宫格" to grid)) {
            // 圆角/圆形只取中央半宽半高；逐块独立覆盖率，不能靠其他色块凑总量。
            val sample =
                Rect(
                    rect.center.x - rect.width / 4,
                    rect.center.y - rect.height / 4,
                    rect.center.x + rect.width / 4,
                    rect.center.y + rect.height / 4,
                )
            assertBlockInk(pixels, sample, host.muted.toArgb(), label)
        }
        host.assertReads(1)
    }

    private fun assertBlockInk(
        pixels: android.graphics.Bitmap,
        sample: Rect,
        color: Int,
        label: String,
    ) {
        var area = 0
        var ink = 0
        for (x in sample.left.toInt() until sample.right.toInt()) {
            for (y in sample.top.toInt() until sample.bottom.toInt()) {
                area += 1
                if (pixels.getPixel(x, y) == color) ink += 1
            }
        }
        assertTrue("${label}必须实际绘制muted占位，$ink/$area", area > 0 && ink.toFloat() / area >= 0.8f)
    }
}
