package com.couli.app.clipboard

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.IntSize
import com.couli.app.home.HomeVisualFixtures
import com.couli.app.home.HomeVisualTestHost
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.ManualPasteAction
import com.couli.core.home.HomeLevel
import com.couli.core.network.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.robolectric.RuntimeEnvironment

/** 真实壳、真实识别服务与结果宿主；只替换图片、系统剪贴板和网络。 */
internal class ClipboardVisualDriver(
    val rule: ComposeContentTestRule,
) : AutoCloseable {
    val host = HomeVisualTestHost(rule)
    private val originalWindowFontScale = RuntimeEnvironment.getFontScale()

    fun launch(
        dark: Boolean = false,
        fontScale: Float = 1f,
        guest: Boolean = false,
        fresh: Boolean = false,
    ) {
        host.images = ClipboardVisualFixtures.images
        host.port.page = HomeVisualFixtures.page(if (guest) "guest_v3" else "main_v3")
        host.launch(
            level = if (guest) HomeLevel.GUEST else HomeLevel.MEMBER,
            dark = dark,
            fontScale = fontScale,
            statusBar = true,
            autoRead = if (fresh) null else false,
        )
        foreground()
    }

    fun popup(
        dark: Boolean = false,
        fontScale: Float = 1f,
        action: String = """{"route":"Search"}""",
        imageSize: IntSize = IntSize(326, 408),
    ) {
        // Dialog 独立窗口从 Resources 取密度：只改外层 LocalDensity 不会放大窗口内文字。
        RuntimeEnvironment.setFontScale(fontScale)
        host.images = ClipboardVisualFixtures.popupImages(imageSize.width, imageSize.height)
        host.port.page = ClipboardVisualFixtures.popupPage(action)
        host.launch(dark = dark, fontScale = fontScale, statusBar = true)
        foreground()
        assertNotNull(
            "真实控制器必须确实出弹窗",
            host.home.controller.value
                ?.state
                ?.value
                ?.popup,
        )
    }

    fun parse(response: ApiResult = ClipboardVisualFixtures.cards(ClipboardVisualFixtures.card())) {
        host.api.reply = { response }
        rule.runOnUiThread {
            host.system.content = ClipRead.Text(ClipboardVisualFixtures.LINK)
            host.clipboard.paste(ManualPasteAction.ENTRY_HOME)
        }
        host.idle()
        assertEquals(1, host.api.calls.count { it.operationId == "parseInput" })
        assertNotNull("禁止把空首页录作结果快照", host.clipboard.result.value)
    }

    fun invite() {
        rule.runOnUiThread {
            host.system.content = ClipRead.Text("邀请码 ${ClipboardVisualFixtures.INVITE}")
            host.clipboard.paste(ManualPasteAction.ENTRY_HOME)
        }
        host.idle()
        assertEquals(ClipboardVisualFixtures.INVITE, (host.clipboard.result.value as ClipboardResult.InviteConfirm).code)
    }

    private fun foreground() {
        rule.runOnUiThread {
            host.home.onForeground(true)
            host.clipboard.onForeground(cold = true, hasFocus = true)
        }
        host.idle()
    }

    override fun close() {
        try {
            host.close()
        } finally {
            RuntimeEnvironment.setFontScale(originalWindowFontScale)
        }
    }
}
