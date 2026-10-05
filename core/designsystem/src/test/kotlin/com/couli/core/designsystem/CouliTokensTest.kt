package com.couli.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.couli.generated.DesignTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class CouliTokensTest {
    @Test
    fun representativeColorsMatchTokens() {
        assertEquals(Color(0xFFC44820), CouliColors.brandPrimary)
        assertEquals(Color(0xFFF2EEE6), CouliColors.backgroundCanvas)
        assertEquals(Color(0xFFFFFFFF), CouliColors.textInverse)
        assertEquals(Color(0xFFB42345), CouliColors.statusErrorText)
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
