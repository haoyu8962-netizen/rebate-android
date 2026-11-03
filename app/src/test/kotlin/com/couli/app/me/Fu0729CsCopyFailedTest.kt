package com.couli.app.me

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-0729 ②（编排 11-02 15:14 ③：客服复制失败现为成功绿字，是缺陷；只改颜色，位置与播报不变）：客服半屏（未装微信 → 复制客服链接）里的复制结果，
 * 失败（me.copy_failed）用 `status.error.text`，成功（cs.copy_link.done）仍用 `status.success.text`；两者位置相同（操作区首行、与按钮左齐、到按钮 12）、
 * 无图标、liveRegion = Polite 且不叠加主动播报、只有一路提示、留在半屏；复制恰一次、写入原串。浅 / 深都量（令牌随主题取）。
 * 颜色先比排版样式，再比字形像素（错误色墨迹 ≥ 10、成功色 0，反之亦然），防止样式对了但画出来不是。
 * 底版 c8eac4a：失败态两项颜色用例红（一律成功色），其余（成功色、位置、播报、计数、无图标）绿；实现后全绿。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1032dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Fu0729CsCopyFailedTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PrivacyVisualTestHost(rule) }
    private val failed get() = AppMeTexts.text("me.copy_failed")
    private val copied get() = AppMeTexts.text("cs.copy_link.done")

    @After fun tearDown() = host.close()

    @Test fun copyFailureInTheSheetUsesTheErrorColor() {
        copy(FAILED_CASE)
        assertTint(failed, host.palette.error, host.palette.success, "复制失败用错误色（现为成功色即缺陷未修）")
    }

    @Test fun copyFailureInTheDarkSheetUsesTheErrorColor() {
        copy(FAILED_CASE, dark = true)
        assertTint(failed, host.palette.error, host.palette.success, "深色：复制失败用错误色")
    }

    @Test fun copySuccessStillUsesTheSuccessColor() {
        copy(OK_CASE)
        assertTint(copied, host.palette.success, host.palette.error, "复制成功仍用成功色")
    }

    @Test fun copySuccessInTheDarkSheetStillUsesTheSuccessColor() {
        copy(OK_CASE, dark = true)
        assertTint(copied, host.palette.success, host.palette.error, "深色：复制成功仍用成功色")
    }

    @Test fun copyFailureCopiesOnceStaysInTheSheetWithOneToast() {
        copy(FAILED_CASE)
        assertEquals("复制恰一次、写入原串", listOf(PrivacyVisualFixtures.kf), host.ports.copies)
        assertEquals("失败句出现一次", 1, host.count(failed))
        assertEquals("成功句不出现", 0, host.count(copied))
        assertTrue("提示后留在半屏", host.has(MeTags.CS_SHEET))
        assertEquals("只有一路提示（半屏内，不同时出页面轻提示）", 1, host.countTag(MeTags.TOAST))
        assertTrue("按钮不因失败消失、可再试", host.has(MeTags.CS_COPY))
        assertEquals("不打开、不去帮助", 0, host.ports.opened.size + host.ports.help)
    }

    @Test fun copyFailureIsPoliteOnceWithoutActiveAnnouncement() {
        copy(FAILED_CASE)
        val node = host.node(MeTags.TOAST).fetchSemanticsNode()
        assertEquals("polite 不变", LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
        val deliveries = host.announcements.sample().filter { it.text.contains(failed) }
        assertEquals("不叠加主动播报：$deliveries", 0, deliveries.count { it.channel == "active" })
        assertTrue("合计至多播一次（liveRegion 一次）：$deliveries", deliveries.size <= 1)
        assertTrue("不是 assertive：$deliveries", deliveries.none { it.priority == LiveRegionMode.Assertive })
    }

    @Test fun failureAndSuccessShareThePositionAndCarryNoIcon() {
        copy(FAILED_CASE)
        assertPositionAndNoIcon(failed)
    }

    @Test fun successPositionAndNoIconAreUnchanged() {
        copy(OK_CASE)
        assertPositionAndNoIcon(copied)
    }

    // ---------------------------------------------------------------- 工具

    private fun copy(
        case: String,
        dark: Boolean = false,
    ) {
        host.launchCs(case, dark = dark)
        host.tap(MeTags.CS_COPY)
        assertTrue("复制结果已出现", host.has(MeTags.TOAST))
    }

    private fun assertTint(
        message: String,
        expected: Color,
        other: Color,
        what: String,
    ) {
        assertNotEquals("先正：两种令牌色不同", expected, other)
        val text = rule.textUnder(MeTags.TOAST, message)
        assertEquals(what, expected, text.textStyle().color)
        val px = host.pixels(text)
        val glyph = text.glyphBox()
        assertTrue("$what：字形里有该色墨迹", px.countNear(glyph, expected, INK_TOLERANCE) >= MIN_INK)
        assertEquals("$what：字形里没有另一种状态色", 0, px.countNear(glyph, other, INK_TOLERANCE))
    }

    /** 位置：操作区首行，与【复制客服链接】左齐、到按钮上沿 12；文字左右（到按钮宽度内）没有任何墨迹（无图标）。 */
    private fun assertPositionAndNoIcon(message: String) {
        val text = rule.textUnder(MeTags.TOAST, message)
        val button = host.node(MeTags.CS_COPY).box()
        val box = text.textBox()
        val glyph = text.glyphBox()
        val d = text.density()
        assertDp("与按钮左齐", 0f, box.left - button.left, d, 1f)
        assertDp("到按钮 12（操作区 spacedBy 12）", 12f, button.top - box.bottom, d, 1f)
        val px = host.pixels(text)
        val before = Rect(button.left, box.top, glyph.left - 2, box.bottom)
        val after = Rect(glyph.right + 2, box.top, button.right, box.bottom)
        assertNull("文字左侧无图标墨迹", px.ink(before, host.palette.surface))
        assertNull("文字右侧无图标墨迹", px.ink(after, host.palette.surface))
    }

    private companion object {
        const val FAILED_CASE = "cs_wechat_missing_copy_failed"
        const val OK_CASE = "cs_wechat_missing"
        const val INK_TOLERANCE = 24
        const val MIN_INK = 10
    }
}
