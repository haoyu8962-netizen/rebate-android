// 生成物，禁止手改；来源 contract.lock（tools/codegen/gen.py 由 contracts/snapshot/ 生成）
package com.couli.generated

/** 设计令牌原始常量（design/tokens/design-tokens.json）。语义映射在 core:designsystem。 */
object DesignTokens {
    /** metadata.version */
    const val version: kotlin.String = "0.3.0"

    /** color.* 点分路径（去掉 color. 前缀）→ #RRGGBB */
    val colors: kotlin.collections.Map<kotlin.String, kotlin.String> =
        linkedMapOf(
            "brand.primary" to "#C44820",
            "brand.emphasis" to "#A83B1F",
            "brand.secondary" to "#26735B",
            "brand.subtle" to "#FBEFE8",
            "background.canvas" to "#F2EEE6",
            "background.admin-canvas" to "#F6F3ED",
            "background.surface" to "#FFFFFF",
            "background.muted" to "#EFEAE2",
            "background.placeholder" to "#E9E2D9",
            "text.primary" to "#28251F",
            "text.secondary" to "#655E57",
            "text.inverse" to "#FFFFFF",
            "text.link" to "#A83B1F",
            "price.text" to "#28251F",
            "rebate.text" to "#A83B1F",
            "rebate.background" to "#FBEFE8",
            "rebate.solid.background" to "#C44820",
            "rebate.solid.text" to "#FFFFFF",
            "status.pending.text" to "#655E57",
            "status.pending.background" to "#EFEAE2",
            "status.success.text" to "#26735B",
            "status.success.background" to "#EAF4EE",
            "status.warning.text" to "#846020",
            "status.warning.background" to "#FBF2DC",
            "status.error.text" to "#B42345",
            "status.error.background" to "#FDECF0",
            "border.subtle" to "#E4DED5",
            "border.control" to "#8A8177",
            "focus.ring" to "#26735B",
            "source.text" to "#655E57",
            "source.background" to "#FFFFFF",
            "source.border" to "#E4DED5",
            "button.primary.default.background" to "#C44820",
            "button.primary.default.text" to "#FFFFFF",
            "button.primary.pressed.background" to "#A83B1F",
            "button.primary.pressed.text" to "#FFFFFF",
            "button.primary.disabled.background" to "#E9E2D9",
            "button.primary.disabled.text" to "#655E57",
        )

    /** space.* → 逻辑像素（dp） */
    val spacing: kotlin.collections.Map<kotlin.String, kotlin.Int> =
        linkedMapOf(
            "0" to 0,
            "1" to 4,
            "2" to 8,
            "3" to 12,
            "4" to 16,
            "5" to 20,
            "6" to 24,
            "8" to 32,
            "10" to 40,
            "12" to 48,
            "16" to 64,
        )

    /** radius.* → 逻辑像素（dp） */
    val radius: kotlin.collections.Map<kotlin.String, kotlin.Int> =
        linkedMapOf(
            "small" to 8,
            "button" to 12,
            "card" to 16,
            "panel" to 24,
            "pill" to 999,
        )

    /** font.size.* → 基础字号（sp，随系统字号缩放） */
    val fontSize: kotlin.collections.Map<kotlin.String, kotlin.Int> =
        linkedMapOf(
            "caption" to 12,
            "footnote" to 14,
            "body" to 17,
            "title" to 22,
            "heading" to 28,
            "display" to 34,
        )

    /** font.weight.* */
    val fontWeight: kotlin.collections.Map<kotlin.String, kotlin.Int> =
        linkedMapOf(
            "regular" to 400,
            "medium" to 500,
            "semibold" to 600,
            "bold" to 700,
        )

    /** font.line-height.* → 行高倍数 */
    val lineHeight: kotlin.collections.Map<kotlin.String, kotlin.Double> =
        linkedMapOf(
            "caption" to 1.5,
            "footnote" to 1.5,
            "body" to 1.5,
            "title" to 1.3,
            "heading" to 1.25,
            "display" to 1.2,
        )

    /** interaction.target.* → 最小命中区（逻辑像素） */
    val interactionTarget: kotlin.collections.Map<kotlin.String, kotlin.Int> =
        linkedMapOf(
            "minimum" to 44,
            "ios" to 44,
            "android" to 48,
            "harmony" to 48,
        )
}
