package com.couli.app.me

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-0728 U6（F-AND-72 ②，APP-12w-a 评审 r2 S2 / 变异 M27「白底栏不铺底部安全区」）：Robolectric 宿主默认没有系统栏，safeDrawing 底部 inset 为 0，
 * 原用例测不出 M27。这里在宿主根视图上注入合成导航条 inset（照 ProductVisualTestHost.navigationInset：WindowInsetsCompat → rootView.dispatchApplyWindowInsets），
 * 先证明注入到达页面（【保存】随之上移），再量窗口最底下那条（导航条区域）是白底栏的 surface，而不是画布色。
 * 底版（MeUi.kt bottomSurface 分支：外层只取上 + 左右、白底栏自己 windowInsetsPadding(Bottom)）：绿。变异 M27（回到四边 safeDrawingPadding）：导航条区域露出画布色 → 红。
 * 本类不改产品；用来补上 M27 的覆盖（若注入到不了 Compose，「先正」断言红并说明，不放宽）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Fu0728NicknameInsetTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SettingsVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    @Test fun whiteBottomBarRunsUnderTheNavigationBar() {
        host.launch()
        host.openNickname()
        val d = host.node(MeTags.NICKNAME_SAVE).density()
        val before = host.node(MeTags.NICKNAME_SAVE).box().bottom
        navigationInset(NAV_DP)
        host.idle()
        val save = host.node(MeTags.NICKNAME_SAVE).box()
        assertTrue(
            "先正：合成导航条 inset 到达页面（【保存】上移 ≥ ${NAV_DP * 0.8f}dp，实际 ${(before - save.bottom) / d}dp）",
            (before - save.bottom) / d >= NAV_DP * 0.8f,
        )
        val px = rule.pixels()
        val surface = host.palette.surface.toArgb()
        val canvas = host.palette.canvas.toArgb()
        val bottom = px.height - 1
        val navTop = (px.height - NAV_DP * d).toInt()
        // 导航条区域（窗口最底 48dp）与【保存】下方的栏内留白：左右边距处与正中都取样
        val xs = listOf(4 * d, px.width / 2f, px.width - 4 * d).map { it.toInt() }
        val ys = listOf(navTop + 2, (navTop + bottom) / 2, bottom - 2, (save.bottom + 4 * d).toInt())
        val wrong =
            xs.flatMap { x -> ys.map { y -> Triple(x, y, px.getPixel(x, y)) } }.filterNot { (_, _, c) -> near(c, surface) }
        assertTrue(
            "白底栏铺到导航条下（导航条区域应为 surface，不得露出画布色）：${wrong.map { (x, y, c) -> "($x,$y)=#${Integer.toHexString(c)}" }}",
            wrong.isEmpty(),
        )
        assertTrue("先正：surface 与画布色可区分", !near(surface, canvas))
    }

    /** 注入导航条底部 inset（照 ProductVisualTestHost.navigationInset 的写法）。 */
    private fun navigationInset(bottomDp: Int) {
        val node = rule.onRoot().fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        val pixels = (bottomDp * node.layoutInfo.density.density).toInt()
        val insets =
            WindowInsetsCompat
                .Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, pixels))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .build()
        rule.runOnUiThread { view.rootView.dispatchApplyWindowInsets(checkNotNull(insets.toWindowInsets())) }
        rule.waitForIdle()
    }

    private fun near(
        a: Int,
        b: Int,
    ): Boolean = listOf(16, 8, 0).all { abs((a ushr it and 255) - (b ushr it and 255)) <= TOLERANCE }

    private companion object {
        const val NAV_DP = 48
        const val TOLERANCE = 3
    }
}
