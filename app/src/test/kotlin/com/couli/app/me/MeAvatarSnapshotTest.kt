package com.couli.app.me

import android.app.Application
import android.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 截图入口（有头像 / 兜底 × 浅深 × 100% / 200%）：只登记，不录基准；verify.sh 尚无本类的 verifyRoborazziDebug 步（见交回需决定事项）。
 * 图片来自内存假源（纯色位图，不联网）；参照图标在截图前移除。有头像四态在现行产品码上红在请求断言，兜底四态现在即可生成。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1032dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeAvatarSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { MeAvatarTestHost(rule) }

    @After fun close() = host.close()

    @Test fun imageLight() = capture(loaded = true, dark = false, fontScale = 1f)

    @Test fun fallbackLight() = capture(loaded = false, dark = false, fontScale = 1f)

    @Test fun imageDark() = capture(loaded = true, dark = true, fontScale = 1f)

    @Test fun fallbackDark() = capture(loaded = false, dark = true, fontScale = 1f)

    @Test fun imageFont200() = capture(loaded = true, dark = false, fontScale = 2f)

    @Test fun fallbackFont200() = capture(loaded = false, dark = false, fontScale = 2f)

    @Test fun imageDarkFont200() = capture(loaded = true, dark = true, fontScale = 2f)

    @Test fun fallbackDarkFont200() = capture(loaded = false, dark = true, fontScale = 2f)

    private fun capture(
        loaded: Boolean,
        dark: Boolean,
        fontScale: Float,
    ) {
        // 兜底态用「立即失败」：截图前只核兜底像素，不核请求（请求与加载由 MeAvatarRenderingTest 管）。
        host.images.failImmediately = !loaded
        host.launch(dark = dark, fontScale = fontScale)
        if (loaded) {
            assertEquals(listOf(MeAvatarImages.A), host.images.requests.map { it.url })
            host.finish(0, Color.MAGENTA)
            rule.assertAvatarImage(host, Color.MAGENTA)
        } else {
            rule.assertAvatarFallback(host)
        }
        rule.assertAvatarDecorative()
        rule.runOnIdle { host.showReference.value = false }
        rule.waitForIdle()
        val state = if (loaded) "image" else "fallback"
        val theme = if (dark) "dark" else "light"
        val scale = if (fontScale == 2f) "font200" else "font100"
        rule.onRoot().captureRoboImage("src/test/snapshots/me-avatar/MeAvatar-$state-$theme-$scale.png")
    }
}
