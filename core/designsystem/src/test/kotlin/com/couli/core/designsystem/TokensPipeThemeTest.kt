package com.couli.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.generated.DesignTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** TOKENS-PIPE：在升到 f088f04 的副本跑；反射新表，让缺段表现为断言红而非编译失败。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TokensPipeThemeTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun bothThemesReadEveryGeneratedColorAndKeySemanticRole() {
        val light = generatedMap("getColors")
        val darkColors = generatedMap("getColorsDark")
        var dark by mutableStateOf(false)
        var actual = emptyMap<String, Color>()
        var semantic = emptyList<Color>()
        var material = emptyList<Color>()
        val roles = listOf("text.primary", "background.canvas", "brand.primary", "status.error.text", "overlay.scrim")
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                actual = couliColorTokens().toMap()
                semantic =
                    listOf(
                        CouliColors.textPrimary,
                        CouliColors.backgroundCanvas,
                        CouliColors.brandPrimary,
                        CouliColors.statusErrorText,
                        CouliColors.overlayScrim,
                    )
                material =
                    with(MaterialTheme.colorScheme) {
                        listOf(onBackground, background, primary, error, scrim)
                    }
            }
        }
        listOf(false, true, false).forEach { mode ->
            rule.runOnIdle { dark = mode }
            rule.waitForIdle()
            val generated = if (mode) darkColors else light
            generated.forEach { (path, hex) ->
                assertEquals("dark=$mode / $path", referenceColor(hex as String), actual.getValue(path))
            }
            val expectedRoles = roles.map { referenceColor(generated.getValue(it) as String) }
            assertEquals("语义 getter 要读当前生成表，dark=$mode", expectedRoles, semantic)
            assertEquals("M3 角色要读当前生成表，dark=$mode", expectedRoles, material)
            assertEquals(referenceColor(light.getValue("brand.subtle") as String), actual.getValue("fixed.container"))
            assertEquals(referenceColor(light.getValue("brand.emphasis") as String), actual.getValue("fixed.on-container"))
        }
    }

    @Test
    fun rgbaParserPreservesAlphaAndDoesNotTreatRgbaAsArgb() {
        mapOf(
            "#28251F" to Color(0xFF28251F),
            "#28251F66" to Color(0x6628251F),
            "#0000008C" to Color(0x8C000000),
            "#12345678" to Color(0x78123456),
            "#abcdef00" to Color(0x00ABCDEF),
            "#abcdefFF" to Color(0xFFABCDEF),
        ).forEach { (hex, expected) -> assertEquals(hex, expected, parseHexColor(hex)) }
    }

    @Test
    fun componentDimensionsAndFractionMatchGeneratedNumbers() {
        val generated = generatedMap("getComponent") + generatedMap("getComponentNumber")
        val actual =
            mapOf(
                "count-badge.size" to CouliComponentTokens.countBadgeSize.value,
                "field.min-height" to CouliComponentTokens.fieldMinHeight.value,
                "code-cell.height" to CouliComponentTokens.codeCellHeight.value,
                "checkbox.size" to CouliComponentTokens.checkboxSize.value,
                "checkbox.max-scale" to CouliComponentTokens.CHECKBOX_MAX_SCALE,
                "chip.height" to CouliComponentTokens.chipHeight.value,
                "tab-indicator.height" to CouliComponentTokens.tabIndicatorHeight.value,
                "icon.small" to CouliComponentTokens.iconSmall.value,
                "icon.inline" to CouliComponentTokens.iconInline.value,
                "icon.medium" to CouliComponentTokens.iconMedium.value,
                "tag.min-height" to CouliComponentTokens.tagMinHeight.value,
                "platform-badge.min-height" to CouliComponentTokens.platformBadgeMinHeight.value,
                "image.list" to CouliComponentTokens.imageList.value,
                "grid-cell.min-height" to CouliComponentTokens.gridCellMinHeight.value,
                "oauth.size" to CouliComponentTokens.oauthSize.value,
                "oauth.gap" to CouliComponentTokens.oauthGap.value,
            )
        actual.forEach { (path, value) -> assertEquals(path, (generated.getValue(path) as Number).toFloat(), value) }
        assertEquals(1.5f, actual.getValue("checkbox.max-scale"))
    }

    private fun generatedMap(getter: String): Map<String, Any> {
        // getMethod / invoke 也用于既有 Ds02OAuthAssetTest，避免引用尚未生成的 Kotlin 属性。
        val method = DesignTokens.javaClass.methods.singleOrNull { it.name == getter && it.parameterCount == 0 }
        assertTrue("缺少生成表 $getter；先升锁，再补生成器输出", method != null)
        val values = requireNotNull(method).invoke(DesignTokens) as Map<*, *>
        return values.entries.associate { (key, value) -> key as String to requireNotNull(value) }
    }

    private fun referenceColor(hex: String): Color {
        val bits = hex.substring(1).toLong(radix = 16)
        val argb = if (hex.length == 9) (bits ushr 8) or ((bits and 0xFF) shl 24) else 0xFF000000L or bits
        return Color(argb)
    }
}
