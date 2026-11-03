@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)

package com.couli.app.me

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-0729 ② 截图入口（brief §6）：客服半屏复制失败（无稿状态，对照 CsWechatMissing-v2）浅 / 深 / 200% × w390 / 840，
 * 放 src/test/snapshots/privacy-v/cs-copy-failed-in-sheet-*。类名以 PrivacyVisualSnapshotTest 结尾：随 verify.sh 既有一步比对，不改 CI 脚本。
 * 截图前先核失败句是错误色，所以底版（成功色）在单测步就红。既有 cs-copied-in-sheet-light 等 0 变化。半屏是独立窗口，用 captureScreenRoboImage。
 * 只声明入口；测试作者不 record / verify、不生成基准。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1032dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Fu0729CsCopyFailedPrivacyVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PrivacyVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    @Test fun copyFailedLight() = failed("cs-copy-failed-in-sheet-light")

    @Test fun copyFailedDark() = failed("cs-copy-failed-in-sheet-dark", dark = true)

    @Test fun copyFailedFont200W390() = failed("cs-copy-failed-in-sheet-font200-w390-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun copyFailedWide() = failed("cs-copy-failed-in-sheet-w840-light")

    private fun failed(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        host.launchCs("cs_wechat_missing_copy_failed", dark = dark, fontScale = fontScale)
        host.tap(MeTags.CS_COPY)
        assertTrue(host.has(MeTags.TOAST))
        val text = rule.textUnder(MeTags.TOAST, AppMeTexts.text("me.copy_failed"))
        assertEquals("$name：复制失败用错误色", host.palette.error, text.textStyle().color)
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(SHEET_SETTLE_MS)
        captureScreenRoboImage("$DIR/$name.png")
    }

    private companion object {
        const val DIR = "src/test/snapshots/privacy-v"
        const val SHEET_SETTLE_MS = 600L
    }
}
