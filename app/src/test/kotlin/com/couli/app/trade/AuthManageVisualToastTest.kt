@file:Suppress("MagicNumber")

package com.couli.app.trade

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.me.firstFromLeft
import com.couli.app.me.firstFromRight
import com.couli.app.me.textBox
import com.couli.app.me.textStyle
import com.couli.app.trade.AuthManageVisualHost.Companion.BIND
import com.couli.app.trade.AuthManageVisualHost.Companion.GRANT
import com.couli.app.trade.AuthManageVisualHost.Companion.UNAUTHORIZED_KEY
import com.couli.core.designsystem.CouliIcons
import com.couli.core.network.ResultSignals
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-06w 轻提示（A12、G-A07 / D10-414 ④）：本页失败类轻提示一律带警告三角（status.warning.text）、位置维持页顶、2.5 秒、播报一次；
 * 文案 / trace6 拼接 / 消费时机不变。键表是 AuthManageParts.kt 的纯函数 authManageToastIcon(key)（反射取，不让本测试依赖新符号编译）。
 * 底版应红：三角 / 键表；应绿：文案、页顶、播报一次、消费（回归）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class AuthManageVisualToastTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { AuthManageVisualHost(rule) }

    @After fun tearDown() = host.close()

    private val d get() = host.density()

    @Test fun g07AuthUnavailable30101() = grantFailure("c30101")

    @Test fun g07AuthUnavailable30102() = grantFailure("c30102")

    @Test fun g07RateLimited42901() = grantFailure("c42901")

    @Test fun g07Generic20001() = grantFailure("c20001")

    @Test fun g07ServerError50001CarriesTrace6() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.failure("c50001")
        host.launch("taobao_unbound")
        val message = ResultSignals.withTrace6(host.text("error.50001"), AuthManageVisualFixtures.traceId)
        assertTrue("5xxxx 轻提示带 trace6", message.contains(AuthManageVisualFixtures.trace6))
        host.toastAfter(message) { host.tap(host.grantTag("taobao")) }
        assertWarningToast(message)
    }

    @Test fun g07Bind30151() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.grant("taobao_web_code")
        host.api.sticky[BIND] = AuthManageVisualFixtures.failure("c30151")
        host.launch("taobao_unbound")
        val message = host.text(AuthManageVisualFixtures.wording("c30151"))
        host.toastAfter(message) { host.tap(host.grantTag("taobao")) }
        assertWarningToast(message)
    }

    @Test fun g07LocalMethodUnsupportedIsCredentialInvalid() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.grant("taobao_sdk")
        host.launch("taobao_unbound")
        val message = host.text("error.30104.credential_invalid")
        host.toastAfter(message) { host.tap(host.grantTag("taobao")) }
        assertWarningToast(message)
        assertEquals("本端执行不了 sdk_token：不拉起任何授权", 0, host.ports.launches)
    }

    @Test fun g07OfflineIsBuyTimeout() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.offline()
        host.launch("taobao_unbound")
        val message = host.text(AuthManageVisualFixtures.wording("offline"))
        host.toastAfter(message) { host.tap(host.grantTag("taobao")) }
        assertWarningToast(message)
    }

    /** 页顶、2.5 秒、播报一次：位置与时长不变（G-A07 位置不对照画板卡下的设计证据）。 */
    @Test fun g07ToastStaysOnTopTwoAndAHalfSecondsAndSpeaksOnce() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.failure("c30101")
        host.launch("taobao_unbound")
        val message = host.text(AuthManageVisualFixtures.wording("c30101"))
        host.toastAfter(message) { host.tap(host.grantTag("taobao")) }
        val toast = host.textNode(message).textBox()
        val name = host.inRow("taobao", host.name("taobao")).textBox()
        assertTrue("轻提示在页顶（在第一行平台名之上）", toast.top < name.top)
        host.advance(1_000)
        assertEquals("播报恰一次（announcementId = toast.id）", 1, host.spoken.count { it == message })
        assertEquals("1 秒时仍在", 1, host.countText(message))
        host.advance(2_000)
        assertEquals("2.5 秒后消费", 0, host.countText(message))
        assertEquals("消费后不再播报", 1, host.spoken.count { it == message })
        assertEquals("仍是未授权", 1, host.countInRow("taobao", host.text(UNAUTHORIZED_KEY)))
    }

    /** G-A07 键表（纯函数）：本页全部失败键与未知键 → 警告三角。 */
    @Test fun g07KeyTableGivesTheWarningTriangleForEveryFailureKey() {
        val fn = toastIconFunction()
        val keys =
            listOf(
                "error.30101.auth_unavailable",
                "error.30102.auth_unavailable",
                "error.30151",
                "error.30104.credential_invalid",
                "buy.timeout",
                "error.50001",
                "error.42901",
                "error.20001",
                "error.30153",
                "error.99999.unknown",
            )
        keys.forEach { assertEquals("键 $it → 警告三角（CouliIcons.alert）", CouliIcons.alert, fn(it)) }
        assertTrue("不是组件库默认对勾（反）", keys.none { fn(it) == CouliIcons.check })
    }

    // ---------------------------------------------------------------- 量具

    private fun grantFailure(name: String) {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.failure(name)
        host.launch("taobao_unbound")
        val message = host.text(AuthManageVisualFixtures.wording(name))
        host.toastAfter(message) { host.tap(host.grantTag("taobao")) }
        assertWarningToast(message)
        assertEquals("$name：不拉起授权、不外跳", 0, host.ports.launches)
    }

    private fun assertWarningToast(message: String) {
        val text = host.textNode(message)
        assertEquals("轻提示 14", 14f, text.textStyle().fontSize.value, 0.01f)
        val row = assertSome("轻提示文字的父布局", text.fetchSemanticsNode().layoutInfo.parentInfo)
        val icons = row.squareLeaves(d, 18f, 22f).filter { it.right <= text.textBox().left + d }
        assertEquals("A12 / G-A07：轻提示文字前恰一个 20 图标（底版 icon = null，红）：$icons", 1, icons.size)
        val icon = icons.single()
        val px = host.pixels()
        assertTrue("图标是 status.warning.text（警告三角色）", px.inkOf(icon, host.palette.warningText) > 0)
        assertEquals("不是成功绿对勾（反）", 0, px.inkOf(icon, host.palette.successText, 6))
        assertNear("图标与文字间距 8", 8f, text.textBox().left - icon.right, d, 1f)
        val y = text.textBox().top - 6 * d
        val left = px.firstFromLeft(y, host.palette.surface)
        val right = px.firstFromRight(y, host.palette.surface)
        val outer = Rect(left, y, right, y + 1)
        assertTrue("轻提示白底外框在文字两侧：$outer", outer.left < icon.left && outer.right > text.textBox().right)
        assertTrue("左右至少留 16（长句不贴边）", outer.left / d >= 15f && (host.rootWidth() - outer.right) / d >= 15f)
    }

    @Suppress("UNCHECKED_CAST")
    private fun toastIconFunction(): (String) -> ImageVector? {
        val cls =
            runCatching { Class.forName("com.couli.feature.trade.AuthManagePartsKt") }.getOrNull()
                ?: error("缺 feature/trade/AuthManageParts.kt（G-A03 新视图件文件；底版红）")
        val method =
            cls.declaredMethods.firstOrNull { m ->
                m.name.startsWith("authManageToastIcon") && m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java
            } ?: error("AuthManageParts.kt 缺纯函数 authManageToastIcon(key: String): ImageVector?（brief-android A12）")
        method.isAccessible = true
        return { key -> method.invoke(null, key) as ImageVector? }
    }
}
