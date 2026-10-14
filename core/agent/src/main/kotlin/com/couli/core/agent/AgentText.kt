package com.couli.core.agent

import com.couli.core.clipboard.ClipboardRules
import com.couli.core.clipboard.TpwdExtractor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 受限渲染的块种类（03 §7.3：只有段落、无序列表项）。 */
object AgentTextBlockKind {
    const val PARAGRAPH = "paragraph"
    const val LIST_ITEM = "list_item"
}

data class AgentTextSpan(
    val text: String,
    val bold: Boolean,
)

data class AgentTextBlock(
    val kind: String,
    val spans: List<AgentTextSpan>,
) {
    /** 读屏与无障碍播报用的纯文字。 */
    val plain: String get() = spans.joinToString("") { it.text }
}

/**
 * APP-10 §2 第 6 条（G23，03 §7.3，BR-AI-06）：模型文本只做受限渲染，不执行任何下发内容。
 * - [sanitize]：剥离 `scheme://` 后的连续非空白串（含 http / https 与自定义 scheme）、`www.` 开头的连续非空白串、FU-CLIP 已识别的口令形态
 *   （复用 [TpwdExtractor]，不另写正则、不扩大京口令 / 拼多多口令范围）；只删匹配串本身，不删整句。
 * - [blocks]：只产出段落、加粗片段、无序列表项三种块；其余 Markdown 记号（#、[]()、`、>、| 等）原样当文字；文字一律不可点。
 * 空白修剪、单换行合并、嵌套 / 未闭合粗体规划没写（向量 gaps G23）：段内单换行保留在同一段，未闭合的 ** 原样当文字。三端同一套（鸿蒙 AgentText.ets）。
 */
object AgentText {
    private val SCHEME_URL = Regex("[A-Za-z][A-Za-z0-9+.-]*://\\S*")
    private val WWW = Regex("(?i)www\\.\\S+")
    private const val BOLD = "**"

    /** 剥离链接、scheme、www. 串与口令（G23）。 */
    fun sanitize(text: String): String {
        var out = text.replace(SCHEME_URL, "").replace(WWW, "")
        TpwdExtractor.spans(out, ClipboardRules.defaults).sortedByDescending { it.index }.forEach { span ->
            out = out.substring(0, span.index) + out.substring(span.index + span.raw.length)
        }
        return out
    }

    /** 已剥离的文字 → 块（JSON 形状：`{kindRaw, spans:[{text, bold}]}`）。 */
    fun blocks(sanitized: String): List<JsonObject> =
        parse(sanitized).map { block ->
            JsonObject(
                linkedMapOf(
                    "kindRaw" to JsonPrimitive(block.kind),
                    "spans" to
                        JsonArray(
                            block.spans.map { JsonObject(linkedMapOf("text" to JsonPrimitive(it.text), "bold" to JsonPrimitive(it.bold))) },
                        ),
                ),
            )
        }

    /** 剥离后再分块（视图只用这一个入口，保证先剥离再渲染）。 */
    fun render(text: String): List<AgentTextBlock> = parse(sanitize(text))

    fun parse(sanitized: String): List<AgentTextBlock> {
        val out = mutableListOf<AgentTextBlock>()
        val paragraph = mutableListOf<String>()

        fun flush() {
            if (paragraph.isEmpty()) return
            val joined = paragraph.joinToString("\n")
            if (joined.isNotBlank()) out += AgentTextBlock(AgentTextBlockKind.PARAGRAPH, spans(joined))
            paragraph.clear()
        }
        for (line in sanitized.replace("\r\n", "\n").split('\n')) {
            when {
                line.isBlank() -> {
                    flush()
                }

                line.startsWith("- ") || line.startsWith("* ") -> {
                    flush()
                    val item = line.substring(2)
                    if (item.isNotBlank()) out += AgentTextBlock(AgentTextBlockKind.LIST_ITEM, spans(item))
                }

                else -> {
                    paragraph += line
                }
            }
        }
        flush()
        return out
    }

    /** `**…**` 加粗；未闭合的 ** 原样当文字。 */
    private fun spans(text: String): List<AgentTextSpan> {
        val parts = text.split(BOLD).toMutableList()
        // 段数为偶数说明最后一个 ** 没有闭合：把它并回前一段原样显示
        if (parts.size % 2 == 0) {
            val last = parts.removeAt(parts.lastIndex)
            parts[parts.lastIndex] = parts[parts.lastIndex] + BOLD + last
        }
        return parts.mapIndexedNotNull { i, part -> part.takeIf { it.isNotEmpty() }?.let { AgentTextSpan(it, i % 2 == 1) } }
    }
}
