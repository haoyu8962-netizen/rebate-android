package com.couli.core.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.generated.DesignTokens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CouliTokensTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun representativeColorsMatchTokens() {
        var actual = emptyList<Color>()
        rule.setContent {
            CouliTheme(darkTheme = false) {
                actual =
                    listOf(CouliColors.brandPrimary, CouliColors.backgroundCanvas, CouliColors.textInverse, CouliColors.statusErrorText)
            }
        }
        rule.waitForIdle()
        // 恢复原被测对象：四个语义 getter；不能用 all 源表代替属性接线。
        assertEquals(Color(0xFFC44820), actual[0])
        assertEquals(Color(0xFFF2EEE6), actual[1])
        assertEquals(Color(0xFFFFFFFF), actual[2])
        assertEquals(Color(0xFFB42345), actual[3])
    }

    @Test
    fun representativeSemanticPropertiesFollowTheirOwnTokenInBothThemes() {
        var dark by mutableStateOf(false)
        var actual = emptyList<Color>()
        val overrides =
            mapOf(
                "brand.primary" to Color(0xFF19CBE1),
                "background.canvas" to Color(0xFFE137AC),
                "text.inverse" to Color(0xFF73A42D),
                "status.error.text" to Color(0xFF8052D6),
            )
        rule.setContent {
            CouliTheme(darkTheme = dark, colorOverrides = overrides) {
                actual =
                    listOf(CouliColors.brandPrimary, CouliColors.backgroundCanvas, CouliColors.textInverse, CouliColors.statusErrorText)
            }
        }
        listOf(false, true).forEach { theme ->
            rule.runOnIdle { dark = theme }
            rule.waitForIdle()
            assertEquals("语义属性必须各自读取对应令牌，dark=$theme", overrides.values.toList(), actual)
        }
    }

    @Test
    fun everyColorTokenHasASemanticName() {
        assertEquals(DesignTokens.colors.keys, CouliColors.all.keys)
        DesignTokens.colors.forEach { (path, hex) ->
            assertEquals(path, parseHexColor(hex), CouliColors.all.getValue(path))
        }
    }

    @Test
    fun parsesHexAsOpaqueColor() {
        assertEquals(Color(red = 0x28, green = 0x25, blue = 0x1F), parseHexColor("#28251F"))
    }

    @Test
    fun spacingRadiusAndTouchTargetUseDp() {
        assertEquals(0.dp, CouliSpacing.space0)
        assertEquals(16.dp, CouliSpacing.space4)
        assertEquals(64.dp, CouliSpacing.space16)
        assertEquals(16.dp, CouliRadius.card)
        assertEquals(999.dp, CouliRadius.pill)
        assertEquals(48.dp, CouliInteraction.minTouchTarget)
    }

    @Test
    fun typographyHasSixScalableLevels() {
        assertEquals(listOf("caption", "footnote", "body", "title", "heading", "display"), CouliTypography.all.keys.toList())
        assertEquals(17.sp, CouliTypography.body.fontSize)
        assertEquals(25.5.sp, CouliTypography.body.lineHeight)
        assertEquals(34.sp, CouliTypography.display.fontSize)
        assertEquals(FontWeight.SemiBold, CouliTypography.title.fontWeight)
        CouliTypography.all.forEach { (level, style) ->
            assertEquals(level, DesignTokens.fontSize.getValue(level).sp, style.fontSize)
        }
    }
}
