package com.couli.feature.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 可信容器标题栏的状态（ui.setNavBar，契约 bridge.schema.json 的全部参数；APP-04 三端共性第 23 条）：
 * visible、title、bg_color、bg_gradient、text_color、status_bar_style、immersive、hide_close、hide_bottom_safe_area、bounce
 * 都在界面上生效（TrustedWebScreen 读取）；bg_image 需要原生拉取并绘制远程图片，本端还没有图片加载能力，[apply] 整次不生效、
 * 返回 false（桥回 90001，不假装成功）。参数已由协议层按 schema 校验；这里再防一次，任何一项解析不了也整次不生效。
 */
class NavBarState {
    var visible by mutableStateOf(true)
        private set
    var title by mutableStateOf<String?>(null)
        private set
    var background by mutableStateOf<List<Color>>(emptyList())
        private set
    var textColor by mutableStateOf<Color?>(null)
        private set

    /** light = 状态栏浅色图标（深色背景）、dark = 深色图标；null = 不改。 */
    var statusBarStyle by mutableStateOf<String?>(null)
        private set
    var immersive by mutableStateOf(false)
        private set
    var hideClose by mutableStateOf(false)
        private set
    var hideBottomSafeArea by mutableStateOf(false)
        private set

    /** null = 不改（WebView 默认）。 */
    var bounce by mutableStateOf<Boolean?>(null)
        private set

    /** 应用一次 ui.setNavBar；有本端不支持的参数或参数解析不了时什么都不改，返回 false。 */
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // 逐个参数解析
    fun apply(params: JsonObject): Boolean {
        if (params.keys.any { it !in SUPPORTED }) return false
        val newVisible = if ("visible" in params) params.bool("visible") ?: return false else visible
        val newTitle = if ("title" in params) params.text("title") ?: return false else title
        val solid = params["bg_color"]?.let { parseColor((it as? JsonPrimitive)?.content) ?: return false }
        val gradient =
            params["bg_gradient"]?.let { element ->
                val items = element as? JsonArray ?: return false
                items.map { parseColor((it as? JsonPrimitive)?.content) ?: return false }.takeIf { it.size in 2..MAX_STOPS } ?: return false
            }
        val text = params["text_color"]?.let { parseColor((it as? JsonPrimitive)?.content) ?: return false }
        val style =
            params["status_bar_style"]?.let {
                (params.text("status_bar_style") ?: return false).takeIf { it in STYLES }
                    ?: return false
            }
        val flags = BOOLEAN_KEYS.associateWith { key -> if (key in params) params.bool(key) ?: return false else null }

        visible = newVisible
        title = newTitle
        when {
            gradient != null -> background = gradient
            solid != null -> background = listOf(solid)
        }
        text?.let { textColor = it }
        style?.let { statusBarStyle = it }
        flags["immersive"]?.let { immersive = it }
        flags["hide_close"]?.let { hideClose = it }
        flags["hide_bottom_safe_area"]?.let { hideBottomSafeArea = it }
        flags["bounce"]?.let { bounce = it }
        return true
    }

    private companion object {
        const val MAX_STOPS = 4
        const val RGB = 6
        const val RGBA = 8
        const val HEX = 16
        const val BYTE_BITS = 8
        const val ALPHA_SHIFT = 24
        const val OPAQUE = 0xFFL
        val STYLES = setOf("light", "dark")
        val BOOLEAN_KEYS = listOf("immersive", "hide_close", "hide_bottom_safe_area", "bounce")
        val SUPPORTED =
            setOf("visible", "title", "bg_color", "bg_gradient", "text_color", "status_bar_style") + BOOLEAN_KEYS

        /** `#RRGGBB` 或 `#RRGGBBAA`（CSS 顺序，透明度在最后）。 */
        @Suppress("ReturnCount") // 格式不对、不是十六进制逐项返回
        fun parseColor(raw: String?): Color? {
            val hex = raw?.removePrefix("#")?.takeIf { raw.startsWith("#") && (it.length == RGB || it.length == RGBA) } ?: return null
            val value = hex.toLongOrNull(HEX) ?: return null
            val rgb = if (hex.length == RGBA) value shr BYTE_BITS else value
            val alpha = if (hex.length == RGBA) (value and OPAQUE) else OPAQUE
            return Color((alpha shl ALPHA_SHIFT) or rgb)
        }

        fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
    }
}
