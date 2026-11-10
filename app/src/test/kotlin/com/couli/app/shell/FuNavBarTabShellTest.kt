package com.couli.app.shell

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTheme
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-NAV-BAR 任务书 §3 第 5 条（edge-to-edge，现行红）与第 8 条（agentAsSearch 回归，现行绿）。
 * 第 5 条：注入合成导航条底部 inset（照 Fu0728NicknameInsetTest / ProductVisualTestHost 的写法），先证明注入到达底栏（项上移），
 * 再断言底栏容器自身（项的某个祖先布局，位于底栏范围内）铺到根底边、导航条区域像素为 surface 探针；源码断言 TabShell 不再外包
 * `Box(background(surface).navigationBarsPadding())` 垫色条、内容区 consumeWindowInsets 保持。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FuNavBarTabShellTest {
    @get:Rule val rule = createComposeRule()

    // ---------- 第 5 条 ----------

    @Test
    fun tabShellSourceHasNoPaddedSurfaceStripAroundTabBar() {
        val text = File("src/main/kotlin/com/couli/app/shell/TabShell.kt").readText(Charsets.UTF_8)
        val start = text.indexOf("fun TabShell(")
        assertTrue("TabShell.kt 须保留 fun TabShell(", start >= 0)
        val body = text.substring(start, text.indexOf("\n}\n", start).let { if (it < 0) text.length else it })
        assertTrue("内容区 consumeWindowInsets(WindowInsets.navigationBars) 保持", "consumeWindowInsets(WindowInsets.navigationBars)" in body)
        assertTrue("TabShell 仍调用组件库 TabBar(", "TabBar(" in body)
        assertTrue(
            "TabShell 不得再有外包 navigationBarsPadding 的垫色 Box（由 NavigationBar 自带 windowInsets 铺到屏底）",
            "navigationBarsPadding" !in body && "windowInsetsPadding" !in body,
        )
        assertTrue("TabShell 不得再给底栏另垫 backgroundSurface 色条", "CouliColors.backgroundSurface" !in body)
    }

    @Test
    fun barContainerRunsToScreenBottomUnderInjectedNavigationInset() {
        rule.setContent {
            CouliTheme(darkTheme = false, colorOverrides = PROBES) {
                TabShell(selected = ShellTab.HOME, onSelect = {}, tabRoot = { Box(Modifier.fillMaxSize()) })
            }
        }
        rule.waitForIdle()
        val rootNode = rule.onRoot().fetchSemanticsNode()
        val d = rootNode.layoutInfo.density.density
        val before = homeItemBounds().bottom
        navigationInset(NAV_DP)
        val item = homeItemBounds()
        assertTrue(
            "先正：合成导航条 inset 到达底栏（项上移 ≥ ${NAV_DP * 0.8f}dp，实际 ${(before - item.bottom) / d}dp）",
            (before - item.bottom) / d >= NAV_DP * 0.8f,
        )
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val node = rule.onNodeWithText(ShellDraftTexts.TAB_HOME).fetchSemanticsNode()
        val barScope = item.top - BAR_SCOPE_DP * d
        val containers =
            generateSequence(node.layoutInfo) { it.parentInfo }
                .map { it.coordinates.boundsInRoot() }
                .filter { it.top >= barScope }
                .toList()
        assertTrue(
            "底栏容器自身应铺到根底边（${root.bottom}）：底栏范围内祖先布局的底边=${containers.map { it.bottom }}",
            containers.any { it.bottom >= root.bottom - 1f },
        )

        val px = pixels()
        val surface = SURFACE.toArgb()
        val navTop = (px.height - NAV_DP * d).toInt()
        val xs = listOf(4 * d, px.width / 2f, px.width - 4 * d).map { it.toInt() }
        val ys = listOf(navTop + 2, (navTop + px.height - 1) / 2, px.height - 3)
        val wrong = xs.flatMap { x -> ys.map { y -> Triple(x, y, px.getPixel(x, y)) } }.filterNot { (_, _, c) -> near(c, surface) }
        assertTrue(
            "导航条区域应为底栏容器色 surface（不露画布色）：${wrong.map { (x, y, c) -> "($x,$y)=#${Integer.toHexString(c)}" }}",
            wrong.isEmpty(),
        )
    }

    // ---------- 第 8 条：agentAsSearch 回归 ----------

    @Test
    fun agentAsSearchSecondItemOpensSearchNotSelect() {
        val selects = mutableListOf<ShellTab>()
        var searches = 0
        rule.setContent {
            CouliTheme(darkTheme = false) {
                TabShell(
                    selected = ShellTab.HOME,
                    onSelect = { selects += it },
                    agentAsSearch = true,
                    onSearch = { searches++ },
                    tabRoot = { Box(Modifier.fillMaxSize()) },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(ShellDraftTexts.TAB_AGENT).assertDoesNotExist()
        val search = rule.onNodeWithText(ShellDraftTexts.TAB_SEARCH)
        assertEquals("搜索项仍是 Tab", Role.Tab, search.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        search.performClick()
        rule.waitForIdle()
        assertEquals("点搜索走 onSearch", 1, searches)
        assertEquals("点搜索不走 onSelect", emptyList<ShellTab>(), selects)
        rule.onNodeWithText(ShellDraftTexts.TAB_HOME).assertIsSelected()
        rule.onNodeWithText(ShellDraftTexts.TAB_ORDERS).performClick()
        rule.waitForIdle()
        assertEquals(listOf(ShellTab.ORDERS), selects)
        assertEquals(1, searches)
    }

    @Test
    fun agentTabSelectsAgentWhenNotReplaced() {
        val selects = mutableListOf<ShellTab>()
        var searches = 0
        rule.setContent {
            CouliTheme(darkTheme = false) {
                TabShell(
                    selected = ShellTab.HOME,
                    onSelect = { selects += it },
                    onSearch = { searches++ },
                    tabRoot = { Box(Modifier.fillMaxSize()) },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(ShellDraftTexts.TAB_SEARCH).assertDoesNotExist()
        rule.onNodeWithText(ShellDraftTexts.TAB_AGENT).performClick()
        rule.waitForIdle()
        assertEquals(listOf(ShellTab.AGENT), selects)
        assertEquals(0, searches)
    }

    // ---------- 工具 ----------

    private fun homeItemBounds() = rule.onNodeWithText(ShellDraftTexts.TAB_HOME).fetchSemanticsNode().boundsInRoot

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

    private fun pixels(): Bitmap {
        val view = (rule.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun near(
        a: Int,
        b: Int,
    ): Boolean = listOf(16, 8, 0).all { abs((a ushr it and 255) - (b ushr it and 255)) <= TOLERANCE }

    private companion object {
        const val NAV_DP = 48
        const val BAR_SCOPE_DP = 40
        const val TOLERANCE = 3
        val SURFACE = Color(0xFFD8E8FF)
        val PROBES = mapOf("background.surface" to SURFACE, "background.canvas" to Color(0xFF7A1F1F))
    }
}
