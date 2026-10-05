package com.couli.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.couli.generated.DesignTokens

/**
 * 6 级文字样式（caption … display）。字号用 sp，随系统字号缩放（03 §10.4）；行高为字号 × 行高倍数。
 * 字重：令牌只给了字重档位，没有规定各级用哪档；先按「正文常规、标题半粗、展示加粗」取，组件任务按设计稿再核。
 */
@Immutable
object CouliTypography {
    val caption: TextStyle = style("caption", "regular")
    val footnote: TextStyle = style("footnote", "regular")
    val body: TextStyle = style("body", "regular")
    val title: TextStyle = style("title", "semibold")
    val heading: TextStyle = style("heading", "semibold")
    val display: TextStyle = style("display", "bold")

    /** 级别名 → 样式（契约顺序）。 */
    val all: Map<String, TextStyle> =
        linkedMapOf(
            "caption" to caption,
            "footnote" to footnote,
            "body" to body,
            "title" to title,
            "heading" to heading,
            "display" to display,
        )

    private fun style(
        level: String,
        weight: String,
    ): TextStyle {
        val size = DesignTokens.fontSize.getValue(level)
        val lineHeight = DesignTokens.lineHeight.getValue(level)
        return TextStyle(
            fontSize = size.sp,
            lineHeight = (size * lineHeight).sp,
            fontWeight = FontWeight(DesignTokens.fontWeight.getValue(weight)),
        )
    }
}
