// 生成物，禁止手改；来源 contract.lock（tools/codegen/gen.py 由 contracts/snapshot/ 生成）
package com.couli.generated

/** 设计令牌原始常量（design/tokens/design-tokens.json）。语义映射在 core:designsystem。 */
object DesignTokens {
    /** metadata.version */
    const val version: kotlin.String = "0.5.1"

    /** 阴影的一层（shadow.* 的数组元素）：偏移 / 模糊 / 扩展为逻辑像素，color 为 #RRGGBB(AA)，opacity 0–1。 */
    data class ShadowLayer(
        val x: kotlin.Int,
        val y: kotlin.Int,
        val blur: kotlin.Int,
        val spread: kotlin.Int,
        val color: kotlin.String,
        val opacity: kotlin.Double,
    )

    /** color.* 点分路径（去掉 color. 前缀）→ 浅色 #RRGGBB / #RRGGBBAA（末两位透明度） */
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
            "border.active" to "#28251F",
            "focus.ring" to "#26735B",
            "source.text" to "#655E57",
            "source.background" to "#FFFFFF",
            "source.border" to "#E4DED5",
            "platform.tag.taobao" to "#D53D00",
            "platform.tag.tmall" to "#EA0031",
            "platform.tag.jd" to "#E02018",
            "platform.tag.pdd" to "#EB0007",
            "button.primary.default.background" to "#C44820",
            "button.primary.default.text" to "#FFFFFF",
            "button.primary.pressed.background" to "#A83B1F",
            "button.primary.pressed.text" to "#FFFFFF",
            "button.primary.disabled.background" to "#E9E2D9",
            "button.primary.disabled.text" to "#655E57",
            "button.inverse.background" to "#FFFFFF",
            "button.inverse.text" to "#A83B1F",
            "overlay.scrim" to "#28251F66",
            "overlay.on-image" to "#0000008C",
            "control.checked" to "#C44820",
            "badge.background" to "#C44820",
        )

    /** color.*.dark → 深色 #RRGGBB / #RRGGBBAA（键与 colors 相同） */
    val colorsDark: kotlin.collections.Map<kotlin.String, kotlin.String> =
        linkedMapOf(
            "brand.primary" to "#C44820",
            "brand.emphasis" to "#F0956B",
            "brand.secondary" to "#6CC49F",
            "brand.subtle" to "#3A2219",
            "background.canvas" to "#171513",
            "background.admin-canvas" to "#171513",
            "background.surface" to "#24211E",
            "background.muted" to "#2F2B27",
            "background.placeholder" to "#36312C",
            "text.primary" to "#F2EEE6",
            "text.secondary" to "#B5ACA2",
            "text.inverse" to "#FFFFFF",
            "text.link" to "#F0956B",
            "price.text" to "#F2EEE6",
            "rebate.text" to "#F29A6E",
            "rebate.background" to "#3A2219",
            "rebate.solid.background" to "#C44820",
            "rebate.solid.text" to "#FFFFFF",
            "status.pending.text" to "#B5ACA2",
            "status.pending.background" to "#2F2B27",
            "status.success.text" to "#6CC49F",
            "status.success.background" to "#16302A",
            "status.warning.text" to "#E3B65C",
            "status.warning.background" to "#33290F",
            "status.error.text" to "#F28DA4",
            "status.error.background" to "#3B1A22",
            "border.subtle" to "#3A3530",
            "border.control" to "#8C8278",
            "border.active" to "#F2EEE6",
            "focus.ring" to "#5DBB97",
            "source.text" to "#B5ACA2",
            "source.background" to "#24211E",
            "source.border" to "#3A3530",
            "platform.tag.taobao" to "#D53D00",
            "platform.tag.tmall" to "#EA0031",
            "platform.tag.jd" to "#E02018",
            "platform.tag.pdd" to "#EB0007",
            "button.primary.default.background" to "#C44820",
            "button.primary.default.text" to "#FFFFFF",
            "button.primary.pressed.background" to "#A83B1F",
            "button.primary.pressed.text" to "#FFFFFF",
            "button.primary.disabled.background" to "#36312C",
            "button.primary.disabled.text" to "#A39A90",
            "button.inverse.background" to "#FFFFFF",
            "button.inverse.text" to "#A83B1F",
            "overlay.scrim" to "#0000008C",
            "overlay.on-image" to "#0000008C",
            "control.checked" to "#C44820",
            "badge.background" to "#C44820",
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
            "tag" to 4,
            "avatar" to 10,
            "button" to 12,
            "card" to 16,
            "panel" to 24,
            "pill" to 999,
        )

    /** font.family.* → 字体回退列表（只导出数据；原生仍用平台系统字体） */
    val fontFamily: kotlin.collections.Map<kotlin.String, kotlin.collections.List<kotlin.String>> =
        linkedMapOf(
            "system" to kotlin.collections.listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", "sans-serif"),
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

    /** focus.* → 焦点环线宽与外距（逻辑像素） */
    val focus: kotlin.collections.Map<kotlin.String, kotlin.Int> =
        linkedMapOf(
            "width" to 2,
            "offset" to 2,
        )

    /** shadow.* → 浅色阴影层（有序；空列表 = 无阴影） */
    val shadows: kotlin.collections.Map<kotlin.String, kotlin.collections.List<ShadowLayer>> =
        linkedMapOf(
            "none" to kotlin.collections.emptyList(),
            "card" to kotlin.collections.listOf(ShadowLayer(x = 0, y = 2, blur = 8, spread = 0, color = "#28251F", opacity = 0.06)),
            "floating" to kotlin.collections.listOf(ShadowLayer(x = 0, y = 8, blur = 24, spread = 0, color = "#28251F", opacity = 0.12)),
        )

    /** shadow.*.dark → 深色阴影层（键与 shadows 相同） */
    val shadowsDark: kotlin.collections.Map<kotlin.String, kotlin.collections.List<ShadowLayer>> =
        linkedMapOf(
            "none" to kotlin.collections.emptyList(),
            "card" to kotlin.collections.listOf(ShadowLayer(x = 0, y = 2, blur = 8, spread = 0, color = "#000000", opacity = 0.3)),
            "floating" to kotlin.collections.listOf(ShadowLayer(x = 0, y = 8, blur = 24, spread = 0, color = "#000000", opacity = 0.45)),
        )

    /** component.*（type=dimension）→ 逻辑像素（dp） */
    val component: kotlin.collections.Map<kotlin.String, kotlin.Int> =
        linkedMapOf(
            "button.min-height" to 48,
            "button.padding-inline" to 20,
            "button.padding-block" to 12,
            "button.gap" to 8,
            "control.min-height" to 44,
            "control.border-width" to 1,
            "icon.default" to 24,
            "icon.small" to 14,
            "icon.inline" to 16,
            "icon.medium" to 20,
            "field.min-height" to 52,
            "code-cell.height" to 56,
            "checkbox.size" to 18,
            "chip.height" to 32,
            "tab-indicator.height" to 2,
            "tag.min-height" to 22,
            "platform-badge.min-height" to 20,
            "count-badge.size" to 18,
            "image.list" to 96,
            "grid-cell.min-height" to 72,
            "oauth.size" to 48,
            "oauth.gap" to 32,
        )

    /** component.*（type=number）→ 无单位数值（如勾选框放大上限倍数） */
    val componentNumber: kotlin.collections.Map<kotlin.String, kotlin.Double> =
        linkedMapOf(
            "checkbox.max-scale" to 1.5,
        )
}
