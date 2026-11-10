package com.couli.core.designsystem

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-NAV-BAR（D10-824 / D10-826 / D10-827）先红测试：组件库 [TabBar] 换成 M3 NavigationBar / NavigationBarItem。
 * 任务书 §3 第 1（结构）、2（语义）、3（组件级无状态）、4（配色走主题）、6（200% 可按文字找到并点击）、7（角标）条。
 * 现行手写实现上：第 1、4 条红在断言上；第 2、3、6、7 条绿（回归 / 杀变异）。不断言 CollectionInfo（裁定 1）、不断言 200% 不裁切（裁定 2）。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class FuNavBarTabBarTest {
    @get:Rule val rule = createComposeRule()

    private val labels = listOf("首页", "AI 助手", "订单", "我的")

    private fun items(badgeOn: Int? = null): List<TabItem> {
        val icons = listOf(CouliIcons.home, CouliIcons.chat, CouliIcons.orders, CouliIcons.user)
        return labels.mapIndexed { i, label -> TabItem(icons[i], label, badgeDescription = if (i == badgeOn) BADGE else null) }
    }

    // ---------- 第 1 条：结构（源码扫描，现行红） ----------

    @Test
    fun tabBarBodyIsMaterialNavigationBarWithoutHandwrittenSelection() {
        val body = tabBarBody()
        val required =
            listOf(
                "NavigationBar(",
                "NavigationBarItem(",
                "NavigationBarItemDefaults.colors(",
                "MaterialTheme.colorScheme",
                "alwaysShowLabel = true",
                "BadgedBox(",
                "CouliBadge(",
                "onSelect(",
            )
        val forbidden =
            listOf(
                ".selectable(",
                "selectableGroup",
                "collectionInfo",
                "collectionItemInfo",
                "CollectionInfo(",
                "CollectionItemInfo(",
                "couliFocusRing",
                "CouliColors.",
                "couliColorTokens(",
                "Color(0x",
                "captionSemibold",
                "FontWeight",
                "mutableStateOf",
                "mutableIntStateOf",
                "rememberSaveable",
            )
        val missing = required.filterNot { it in body }
        val present = forbidden.filter { it in body }
        assertTrue(
            "TabBar 体内应有 M3 NavigationBar / NavigationBarItem、只经 NavigationBarItemDefaults.colors + MaterialTheme.colorScheme 取色、" +
                "BadgedBox + CouliBadge 角标、onClick 走 onSelect；缺：$missing",
            missing.isEmpty(),
        )
        assertTrue(
            "TabBar 体内不得再有手写 selectable / 集合信息 / 自绘焦点环 / 直接读 CouliColors / 选中加粗 / 本地选中态；仍有：$present",
            present.isEmpty(),
        )
    }

    @Test
    fun navigationBarColorsPassEveryRoleExplicitlyFromColorScheme() {
        val body = tabBarBody()
        val colorsCall = body.substringAfter("NavigationBarItemDefaults.colors(", missingDelimiterValue = "")
        assertTrue("TabBar 体内缺 NavigationBarItemDefaults.colors(", colorsCall.isNotEmpty())
        val args = colorsCall.substring(0, closingParen(colorsCall))
        // M3 默认 selectedTextColor 不是 onPrimaryContainer（brand.emphasis），必须显式传。
        // 认可写法：MaterialTheme.colorScheme.X；本函数内 `val 名 = MaterialTheme.colorScheme` 后的 名.X；with(MaterialTheme.colorScheme) 内的裸 X。
        val prefixes = colorSchemePrefixes(body)
        val expected =
            mapOf(
                "selectedIconColor" to "primary",
                "selectedTextColor" to "onPrimaryContainer",
                "indicatorColor" to "secondaryContainer",
                "unselectedIconColor" to "onSurfaceVariant",
                "unselectedTextColor" to "onSurfaceVariant",
            )
        val wrong = expected.filterNot { (name, role) -> passesRole(args, name, role, prefixes) }
        assertTrue("NavigationBarItemDefaults.colors 须显式传：$wrong；实际参数：$args", wrong.isEmpty())
        assertTrue(
            "容器色取 colorScheme.surface：NavigationBar(containerColor = MaterialTheme.colorScheme.surface)",
            passesRole(body, "containerColor", "surface", prefixes),
        )
    }

    @Test
    fun everyItemCarriesIndexedTestTagOnTheTabNode() {
        render(selected = 1)
        rule.onAllNodesWithTag("tab_bar_item_0").assertCountEquals(1)
        labels.forEachIndexed { index, label ->
            val node = rule.onNodeWithTag("tab_bar_item_$index").fetchSemanticsNode()
            assertEquals("tab_bar_item_$index 应挂在 Role.Tab 的那一项上", Role.Tab, node.config.getOrNull(SemanticsProperties.Role))
            val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
            val text = texts.joinToString { it.text }
            assertTrue("tab_bar_item_$index 应合并文字「$label」，实际「$text」", label in text)
        }
    }

    @Test
    fun selectedLabelIsNotBoldedAndUsesCaption() {
        render(selected = 0)
        val selected = labelLayout(labels[0]).layoutInput.style
        val unselected = labelLayout(labels[1]).layoutInput.style
        assertEquals("选中不再加粗（M3 靠指示条区分选中）", unselected.fontWeight, selected.fontWeight)
        // 显式取 caption（不靠 M3 默认 labelMedium：字号虽同为 12，字重 / 行高不同）
        listOf("选中" to selected, "未选" to unselected).forEach { (name, style) ->
            assertEquals("$name 文字字号取 caption", CouliTypography.caption.fontSize, style.fontSize)
            assertEquals("$name 文字字重取 caption", CouliTypography.caption.fontWeight, style.fontWeight)
            assertEquals("$name 文字行高取 caption", CouliTypography.caption.lineHeight, style.lineHeight)
        }
    }

    // ---------- 第 2 条：语义（现行绿；不断言 CollectionInfo） ----------

    @Test
    fun fourTabsWithRoleSelectionReadableTextAndClick() {
        render(selected = 2)
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(4)
        labels.forEachIndexed { index, label ->
            val tab = rule.onNodeWithText(label)
            tab.assertHasClickAction()
            if (index == 2) tab.assertIsSelected() else tab.assertIsNotSelected()
            assertEquals("「$label」角色为 Tab", Role.Tab, tab.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        }
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertCountEquals(1)
    }

    // ---------- 第 3 条：组件级无状态（现行绿；杀「本地选中态」「onClick 绕过 onSelect」） ----------

    @Test
    fun clickReportsIndexButSelectionOnlyFollowsSelectedIndex() {
        val calls = mutableListOf<Int>()
        var selected by mutableIntStateOf(0)
        rule.setContent {
            CouliTheme(darkTheme = false) {
                TabBar(items = items(), selectedIndex = selected, onSelect = { calls += it })
            }
        }
        rule.onNodeWithText(labels[2]).performClick()
        rule.waitForIdle()
        assertEquals("点击只回调 onSelect(index)", listOf(2), calls)
        rule.onNodeWithText(labels[0]).assertIsSelected()
        rule.onNodeWithText(labels[2]).assertIsNotSelected()

        rule.onNodeWithText(labels[0]).performClick()
        rule.onNodeWithText(labels[3]).performClick()
        rule.waitForIdle()
        assertEquals(listOf(2, 0, 3), calls)
        rule.onNodeWithText(labels[0]).assertIsSelected()
        rule.onNodeWithText(labels[3]).assertIsNotSelected()

        // 外部改 selectedIndex（Router 放行后 onOpenTab 写入）：选中立即跟随，不被组件内记忆挡住
        rule.runOnIdle { selected = 3 }
        rule.waitForIdle()
        rule.onNodeWithText(labels[3]).assertIsSelected()
        rule.onNodeWithText(labels[0]).assertIsNotSelected()
        rule.runOnIdle { selected = 1 }
        rule.waitForIdle()
        rule.onNodeWithText(labels[1]).assertIsSelected()
        rule.onNodeWithText(labels[3]).assertIsNotSelected()
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertCountEquals(1)
        assertEquals("改 selectedIndex 不回调 onSelect", listOf(2, 0, 3), calls)
    }

    // ---------- 第 4 条：配色走主题（探针色像素，现行红：无指示条） ----------

    @Test
    fun colorsComeFromThemeProbesLight() = assertProbeColors(dark = false)

    @Test
    fun colorsComeFromThemeProbesDark() = assertProbeColors(dark = true)

    private fun assertProbeColors(dark: Boolean) {
        rule.setContent {
            CouliTheme(darkTheme = dark, colorOverrides = PROBES) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth())
                    TabBar(items = items(), selectedIndex = 0, onSelect = {})
                }
            }
        }
        rule.waitForIdle()
        val px = pixels()
        val selectedItem = rule.onNodeWithText(labels[0]).fetchSemanticsNode().boundsInRoot
        val selectedLabel = rule.onNodeWithText(labels[0], useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val unselectedItem = rule.onNodeWithText(labels[1]).fetchSemanticsNode().boundsInRoot
        val unselectedLabel = rule.onNodeWithText(labels[1], useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val selectedIcon = Rect(selectedItem.left, selectedItem.top, selectedItem.right, selectedLabel.top)
        val unselectedIcon = Rect(unselectedItem.left, unselectedItem.top, unselectedItem.right, unselectedLabel.top)

        val sIcon = count(px, selectedIcon)
        val sLabel = count(px, selectedLabel)
        val uIcon = count(px, unselectedIcon)
        val uLabel = count(px, unselectedLabel)
        val report =
            "dark=$dark 选中图标区=${named(sIcon)} 选中文字区=${named(sLabel)} " +
                "未选图标区=${named(uIcon)} 未选文字区=${named(uLabel)}"
        assertTrue("指示条取 colorScheme.secondaryContainer（brand.subtle 探针）：$report", sIcon.getValue(SUBTLE) >= MIN_INDICATOR_PX)
        assertTrue("选中图标取 colorScheme.primary（brand.primary 探针）：$report", sIcon.getValue(PRIMARY) >= MIN_ICON_PX)
        assertTrue("选中文字取 colorScheme.onPrimaryContainer（brand.emphasis 探针）：$report", sLabel.getValue(EMPHASIS) >= MIN_TEXT_PX)
        assertEquals("选中文字不得是 M3 默认 secondary（brand.secondary 探针）：$report", 0, sLabel.getValue(SECONDARY))
        assertTrue("未选图标取 colorScheme.onSurfaceVariant（text.secondary 探针）：$report", uIcon.getValue(VARIANT) >= MIN_ICON_PX)
        assertTrue("未选文字取 colorScheme.onSurfaceVariant（text.secondary 探针）：$report", uLabel.getValue(VARIANT) >= MIN_TEXT_PX)
        assertEquals("未选项无指示条、无选中色：$report", 0, uIcon.getValue(SUBTLE) + uIcon.getValue(PRIMARY))
        assertTrue("容器取 colorScheme.surface（background.surface 探针，无 tonal 叠色）：$report", uIcon.getValue(SURFACE) >= MIN_INDICATOR_PX)
    }

    /** 任务书 §5：图标 brand.primary 落在指示条 brand.subtle 上，深浅两主题非文字对比度 ≥ 3:1（现行令牌：浅约 4.33、深约 3.02）。 */
    @Test
    fun indicatorContrastAtLeastThreeToOneInBothThemes() {
        listOf("light" to CouliThemeTokens.light, "dark" to CouliThemeTokens.dark).forEach { (name, tokens) ->
            val ratio = contrast(tokens.getValue("brand.primary"), tokens.getValue("brand.subtle"))
            assertTrue("$name：brand.primary / brand.subtle 对比度 $ratio < 3", ratio >= 3.0)
        }
    }

    // ---------- 第 6 条：200% 字号仍可按文字找到并点击（不断言不裁切，裁定 2） ----------

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun largeFontTabsStillFoundByTextAndClickable() {
        val calls = mutableListOf<Int>()
        rule.setContent {
            CouliTheme(darkTheme = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    TabBar(items = items(badgeOn = 2), selectedIndex = 0, onSelect = { calls += it })
                }
            }
        }
        labels.forEach { rule.onNodeWithText(it).assertHasClickAction().performClick() }
        rule.waitForIdle()
        assertEquals("200% 下 4 项都能按文字点到", listOf(0, 1, 2, 3), calls)
    }

    // ---------- 第 7 条：角标（现行绿） ----------

    @Test
    fun badgeDescriptionIsReadOnTheTabAndBadgeIsMaterialBadge() {
        render(selected = 0, badgeOn = 2)
        val tab = rule.onNodeWithText(labels[2]).fetchSemanticsNode().config
        val spoken =
            tab.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                tab.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                listOfNotNull(tab.getOrNull(SemanticsProperties.StateDescription))
        assertTrue(
            "角标说明「$BADGE」须在「${labels[2]}」这一项的读屏内容里（注意 NavigationBarItem 有文字时会 clearAndSetSemantics 图标槽）：$spoken",
            spoken.any { BADGE in it },
        )
        val other = rule.onNodeWithText(labels[1]).fetchSemanticsNode().config
        val otherDescriptions = other.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        val otherSpoken = otherDescriptions + listOfNotNull(other.getOrNull(SemanticsProperties.StateDescription))
        assertTrue("无角标的项不读角标说明：$otherSpoken", otherSpoken.none { BADGE in it })
        val badgeSource = source("CouliLists.kt").substringAfter("fun CouliBadge(").substringBefore("\n}\n")
        assertTrue("CouliBadge 是 M3 Badge", "Badge(" in badgeSource)
        assertTrue("TabBar 角标用 CouliBadge", "CouliBadge(" in tabBarBody())
    }

    // ---------- 工具 ----------

    private fun render(
        selected: Int,
        badgeOn: Int? = null,
    ) {
        rule.setContent {
            CouliTheme(darkTheme = false) { TabBar(items = items(badgeOn), selectedIndex = selected, onSelect = {}) }
        }
        rule.waitForIdle()
    }

    private fun labelLayout(label: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertNotNull("取不到「$label」的文字排版", results.firstOrNull())
        return results.first()
    }

    private fun source(name: String): String = File("src/main/kotlin/com/couli/core/designsystem/$name").readText(Charsets.UTF_8)

    /** `fun TabBar(` 起到下一个顶格 `}` 为止的函数体。 */
    private fun tabBarBody(): String {
        val text = source("NavigationComponents.kt")
        val start = text.indexOf("fun TabBar(")
        assertTrue("NavigationComponents.kt 须保留 fun TabBar(items, selectedIndex, onSelect, modifier)", start >= 0)
        val end = text.indexOf("\n}\n", start).let { if (it < 0) text.length else it }
        return text.substring(start, end)
    }

    private fun colorSchemePrefixes(body: String): List<String> {
        val aliases =
            Regex("""val\s+(\w+)\s*=\s*MaterialTheme\.colorScheme\b""")
                .findAll(body)
                .map { "${it.groupValues[1]}." }
                .toList()
        val bare = if ("with(MaterialTheme.colorScheme)" in body) listOf("") else emptyList()
        return listOf("MaterialTheme.colorScheme.") + aliases + bare
    }

    private fun passesRole(
        text: String,
        name: String,
        role: String,
        prefixes: List<String>,
    ): Boolean = prefixes.any { Regex("""\b$name\s*=\s*${Regex.escape(it + role)}\b""").containsMatchIn(text) }

    private fun closingParen(afterOpen: String): Int {
        var depth = 1
        afterOpen.forEachIndexed { i, c ->
            if (c == '(') depth++
            if (c == ')' && --depth == 0) return i
        }
        return afterOpen.length
    }

    /** Robolectric 里 captureToImage 的 PixelCopy 给空白图，照仓库已有写法用 View.draw 取像素。 */
    private fun pixels(): Bitmap {
        val view = (rule.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun count(
        px: Bitmap,
        area: Rect,
    ): Map<Color, Int> {
        val result = PROBES.values.associateWith { 0 }.toMutableMap()
        val x0 = area.left.toInt().coerceIn(0, px.width - 1)
        val x1 = area.right.toInt().coerceIn(0, px.width)
        val y0 = area.top.toInt().coerceIn(0, px.height - 1)
        val y1 = area.bottom.toInt().coerceIn(0, px.height)
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val c = px.getPixel(x, y)
                PROBES.values.firstOrNull { near(c, it.toArgb()) }?.let { result[it] = result.getValue(it) + 1 }
            }
        }
        return result
    }

    private fun named(counts: Map<Color, Int>): String =
        PROBES.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}=${counts[it.value]}" }

    private fun near(
        a: Int,
        b: Int,
    ): Boolean = listOf(16, 8, 0).all { abs((a ushr it and 255) - (b ushr it and 255)) <= TOLERANCE }

    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return (maxOf(la, lb) / minOf(la, lb)).toDouble()
    }

    private companion object {
        const val BADGE = "有未读"
        const val TOLERANCE = 6
        const val MIN_ICON_PX = 200
        const val MIN_TEXT_PX = 50
        const val MIN_INDICATOR_PX = 300
        val PRIMARY = Color(0xFF19CBE1)
        val SUBTLE = Color(0xFFE137AC)
        val VARIANT = Color(0xFF2E7D32)
        val EMPHASIS = Color(0xFF6A1B9A)
        val SECONDARY = Color(0xFFFFC400)
        val SURFACE = Color(0xFFD8E8FF)
        val PROBES =
            mapOf(
                "brand.primary" to PRIMARY,
                "brand.subtle" to SUBTLE,
                "text.secondary" to VARIANT,
                "brand.emphasis" to EMPHASIS,
                "brand.secondary" to SECONDARY,
                "background.surface" to SURFACE,
            )
    }
}
