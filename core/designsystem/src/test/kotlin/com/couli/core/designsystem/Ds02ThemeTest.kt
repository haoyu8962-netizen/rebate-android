package com.couli.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.generated.DesignTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class Ds02ThemeTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun bothThemesHaveEveryColorAndExplicitMaterialMappings() {
        var dark by mutableStateOf(false)
        var overrides by mutableStateOf(emptyMap<String, Color>())
        var tokens = emptyMap<String, Color>()
        var scheme: ColorScheme? = null
        rule.setContent {
            CouliTheme(darkTheme = dark, colorOverrides = overrides) {
                tokens = couliColorTokens()
                scheme = MaterialTheme.colorScheme
            }
        }
        rule.waitForIdle()
        val light = tokens.toMap()
        checkMappings(tokens, requireNotNull(scheme))
        rule.runOnIdle { dark = true }
        rule.waitForIdle()
        checkMappings(tokens, requireNotNull(scheme))
        assertEquals(light.keys, tokens.keys)
        assertNotEquals("不能两套都指向浅色", light.getValue("background.canvas"), tokens.getValue("background.canvas"))
        assertNotEquals(light.getValue("text.primary"), tokens.getValue("text.primary"))
        // T1 唯一明确要求深色仍保持白底的品牌内按钮。
        assertEquals(Color.White, tokens.getValue("button.inverse.background"))
        val sentinel = Color(0xFF19CBE1)
        rule.runOnIdle { overrides = tokens.keys.associateWith { sentinel } }
        rule.waitForIdle()
        checkMappings(tokens, requireNotNull(scheme))
        assertTrue("override 必须覆盖全部 resolver 叶子", tokens.values.all { it == sentinel })
    }

    @Test
    fun allFixedRolesHaveIdenticalValuesInRealLightAndDarkThemes() {
        var light: ColorScheme? = null
        var dark: ColorScheme? = null
        rule.setContent {
            // 不传 colorOverrides：全键同色探针会掩盖 Fixed 跟着浅深主题变化的问题。
            CouliTheme(darkTheme = false) { light = MaterialTheme.colorScheme }
            CouliTheme(darkTheme = true) { dark = MaterialTheme.colorScheme }
        }
        rule.waitForIdle()
        val lightScheme = requireNotNull(light)
        val darkScheme = requireNotNull(dark)
        assertNotEquals("必须真的比较浅深两套主题", lightScheme.background, darkScheme.background)
        val lightFixed = materialRoles(lightScheme).filterKeys { "Fixed" in it }
        val darkFixed = materialRoles(darkScheme).filterKeys { "Fixed" in it }
        assertEquals("必须覆盖全部 12 个 Fixed 角色", 12, lightFixed.size)
        assertEquals(lightFixed.keys, darkFixed.keys)
        val changed = lightFixed.keys.filter { lightFixed.getValue(it) != darkFixed.getValue(it) }
        assertTrue("Fixed 角色不得随浅深变化：$changed", changed.isEmpty())
    }

    @Test
    fun allMaterialRolesConsumeEveryTokenOverrideInLight() = assertAllMaterialRolesConsumeOverrides(false)

    @Test
    fun allMaterialRolesConsumeEveryTokenOverrideInDark() = assertAllMaterialRolesConsumeOverrides(true)

    private fun assertAllMaterialRolesConsumeOverrides(dark: Boolean) {
        var overrides by mutableStateOf(emptyMap<String, Color>())
        var tokens = emptyMap<String, Color>()
        var scheme: ColorScheme? = null
        rule.setContent {
            CouliTheme(darkTheme = dark, colorOverrides = overrides) {
                tokens = couliColorTokens()
                scheme = MaterialTheme.colorScheme
            }
        }
        rule.waitForIdle()
        val failures = mutableListOf<String>()
        // 两轮不同探针：默认色或固定常量碰巧等于一轮探针，也不能冒充读取令牌。
        listOf(Color(0xFF19CBE1), Color(0xFFE137AC)).forEach { sentinel ->
            rule.runOnIdle { overrides = tokens.keys.associateWith { sentinel } }
            rule.waitForIdle()
            assertTrue("override 必须覆盖全部 resolver 叶子", tokens.values.all { it == sentinel })
            val roles = materialRoles(requireNotNull(scheme))
            assertEquals("锁定的 M3 1.4.0 有 48 个颜色角色", 48, roles.size)
            roles.filterValues { it != sentinel }.forEach { (role, color) ->
                failures += "dark=$dark / $role: expected=$sentinel, actual=$color"
            }
        }
        assertTrue("M3 角色没有消费令牌覆盖：\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private fun materialRoles(scheme: ColorScheme): Map<String, Color> =
        with(scheme) {
            mapOf(
                "primary" to primary,
                "onPrimary" to onPrimary,
                "primaryContainer" to primaryContainer,
                "onPrimaryContainer" to onPrimaryContainer,
                "inversePrimary" to inversePrimary,
                "secondary" to secondary,
                "onSecondary" to onSecondary,
                "secondaryContainer" to secondaryContainer,
                "onSecondaryContainer" to onSecondaryContainer,
                "tertiary" to tertiary,
                "onTertiary" to onTertiary,
                "tertiaryContainer" to tertiaryContainer,
                "onTertiaryContainer" to onTertiaryContainer,
                "background" to background,
                "onBackground" to onBackground,
                "surface" to surface,
                "onSurface" to onSurface,
                "surfaceVariant" to surfaceVariant,
                "onSurfaceVariant" to onSurfaceVariant,
                "surfaceTint" to surfaceTint,
                "inverseSurface" to inverseSurface,
                "inverseOnSurface" to inverseOnSurface,
                "error" to error,
                "onError" to onError,
                "errorContainer" to errorContainer,
                "onErrorContainer" to onErrorContainer,
                "outline" to outline,
                "outlineVariant" to outlineVariant,
                "scrim" to scrim,
                "surfaceBright" to surfaceBright,
                "surfaceDim" to surfaceDim,
                "surfaceContainerLowest" to surfaceContainerLowest,
                "surfaceContainerLow" to surfaceContainerLow,
                "surfaceContainer" to surfaceContainer,
                "surfaceContainerHigh" to surfaceContainerHigh,
                "surfaceContainerHighest" to surfaceContainerHighest,
                "primaryFixed" to primaryFixed,
                "primaryFixedDim" to primaryFixedDim,
                "onPrimaryFixed" to onPrimaryFixed,
                "onPrimaryFixedVariant" to onPrimaryFixedVariant,
                "secondaryFixed" to secondaryFixed,
                "secondaryFixedDim" to secondaryFixedDim,
                "onSecondaryFixed" to onSecondaryFixed,
                "onSecondaryFixedVariant" to onSecondaryFixedVariant,
                "tertiaryFixed" to tertiaryFixed,
                "tertiaryFixedDim" to tertiaryFixedDim,
                "onTertiaryFixed" to onTertiaryFixed,
                "onTertiaryFixedVariant" to onTertiaryFixedVariant,
            )
        }

    @Test
    @Config(qualifiers = "+night")
    fun defaultThemeFollowsSystemDark() = assertSystemDefault(true)

    @Test
    @Config(qualifiers = "+notnight")
    fun defaultThemeFollowsSystemLight() = assertSystemDefault(false)

    private fun assertSystemDefault(systemDark: Boolean) {
        var defaults = emptyMap<String, Color>()
        var explicitLight = emptyMap<String, Color>()
        var explicitDark = emptyMap<String, Color>()
        var observedSystem = !systemDark
        rule.setContent {
            observedSystem = androidx.compose.foundation.isSystemInDarkTheme()
            CouliTheme { defaults = couliColorTokens().toMap() }
            CouliTheme(darkTheme = false) { explicitLight = couliColorTokens().toMap() }
            CouliTheme(darkTheme = true) { explicitDark = couliColorTokens().toMap() }
        }
        rule.waitForIdle()
        assertEquals(systemDark, observedSystem)
        assertNotEquals(explicitLight.getValue("background.canvas"), explicitDark.getValue("background.canvas"))
        assertEquals(if (systemDark) explicitDark else explicitLight, defaults)
    }

    private fun checkMappings(
        tokens: Map<String, Color>,
        scheme: ColorScheme,
    ) {
        val required =
            DesignTokens.colors.keys +
                setOf(
                    "button.inverse.background",
                    "button.inverse.text",
                    "border.active",
                    "overlay.scrim",
                    "badge.background",
                    "control.checked",
                )
        assertTrue("缺令牌：${required - tokens.keys}", tokens.keys.containsAll(required))
        assertTrue(tokens.values.none { it == Color.Unspecified })
        val surfaces = setOf(tokens.getValue("background.surface"), tokens.getValue("background.muted"))
        listOf(
            scheme.surfaceContainerLowest,
            scheme.surfaceContainerLow,
            scheme.surfaceContainer,
            scheme.surfaceContainerHigh,
            scheme.surfaceContainerHighest,
        ).forEach { assertTrue(it in surfaces) }
        assertEquals(tokens.getValue("brand.subtle"), scheme.secondaryContainer)
        assertEquals(tokens.getValue("brand.emphasis"), scheme.onSecondaryContainer)
        assertEquals(tokens.getValue("overlay.scrim"), scheme.scrim)
        assertTrue("inverseSurface 必须显式映射令牌", scheme.inverseSurface in tokens.values)
        assertTrue("tertiary 必须显式映射令牌", scheme.tertiary in tokens.values)
    }
}
