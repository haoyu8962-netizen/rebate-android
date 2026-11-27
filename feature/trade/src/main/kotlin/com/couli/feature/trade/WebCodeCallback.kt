package com.couli.feature.trade

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 淘宝 web_code 授权回跳（FU-AND-TB-WEBCODE-A；D10-2717 定值：`couli://auth/taobao?code=<c>&state=<s>`，
 * 出错时 `couli://auth/taobao?state=<s>&error=1`，一律按授权未完成）。只在内存里流转，不保存、不进日志；
 * [toString] 不带 code / state 原文（不用 data class）。
 */
sealed interface WebCodeCallback {
    val state: String

    /** 授权完成：带回授权码。 */
    class Code(
        val code: String,
        override val state: String,
    ) : WebCodeCallback {
        override fun toString(): String = "WebCodeCallback.Code"
    }

    /** 中转页回跳了 error=1：授权未完成（按授权失败二选一处理）。 */
    class Incomplete(
        override val state: String,
    ) : WebCodeCallback {
        override fun toString(): String = "WebCodeCallback.Incomplete"
    }
}

/** 回跳的接收方（中转 Activity 当场交给它；生产为进程内的 [WebCodeInbox]）。 */
fun interface WebCodeCallbackSink {
    fun deliver(callback: WebCodeCallback)
}

/**
 * 回跳解析（纯 Kotlin，不用 android.net.Uri；规则见设计稿 §5.3，按 D10-2717 改定值）：非法输入一律为 null。
 * - 前缀必须逐字是 `couli://auth/taobao?`（scheme、host、path 精确匹配；因此不会有端口、userinfo、多余的 path 段）；不得有 fragment；整串 ≤ [MAX_LENGTH]。
 * - 查询串按 `&` 分段、`=` 分键值；任一键出现两次、百分号编码非法：整串作废。只认 code、state、error，其他键忽略。
 * - 取值（解码后）只认中转页同一白名单 `[A-Za-z0-9_-]`；state 1..128、code 1..4096（契约 BindUnionByWebCode）。
 * - 带 error：只有 `error=1` → [WebCodeCallback.Incomplete]（D10-2717），其他取值为 null；带 error 时一律不当成 [WebCodeCallback.Code]。
 * - 没有 error：code 合法 → [WebCodeCallback.Code]，否则 null。
 * 「state 与本机记录不符」「同一 state 第二次回跳」不归这里判（见 [WebCodeInbox] 与授权器）。
 */
object WebCodeCallbackParser {
    const val SCHEME: String = "couli"
    const val HOST: String = "auth"
    const val PATH: String = "/taobao"

    private const val MAX_LENGTH = 8192
    private const val STATE_MAX = 128
    private const val CODE_MAX = 4096
    private const val KEY_CODE = "code"
    private const val KEY_STATE = "state"
    private const val KEY_ERROR = "error"
    private const val ERROR_INCOMPLETE = "1"
    private const val PREFIX = "$SCHEME://$HOST$PATH?"
    private val VALUE = Regex("[A-Za-z0-9_-]*")

    @Suppress("ReturnCount") // 逐条规则判定，不合格即返回 null
    fun parse(uri: String): WebCodeCallback? {
        if (uri.length > MAX_LENGTH || !uri.startsWith(PREFIX) || '#' in uri) return null
        val query = query(uri.substring(PREFIX.length)) ?: return null
        val state = query[KEY_STATE]?.takeIf { it.length in 1..STATE_MAX } ?: return null
        val error = query[KEY_ERROR]
        if (error != null) return if (error == ERROR_INCOMPLETE) WebCodeCallback.Incomplete(state) else null
        val code = query[KEY_CODE]?.takeIf { it.length in 1..CODE_MAX } ?: return null
        return WebCodeCallback.Code(code, state)
    }

    /** 查询串 → 键值（只留 code / state / error）；重复键、非法编码、认的键取值不在白名单：null。 */
    @Suppress("ReturnCount")
    private fun query(raw: String): Map<String, String>? {
        val seen = HashSet<String>()
        val kept = HashMap<String, String>()
        for (segment in raw.split('&')) {
            if (segment.isEmpty()) continue
            val eq = segment.indexOf('=')
            val key = decode(if (eq < 0) segment else segment.substring(0, eq)) ?: return null
            val value = decode(if (eq < 0) "" else segment.substring(eq + 1)) ?: return null
            if (!seen.add(key)) return null
            if (key == KEY_CODE || key == KEY_STATE || key == KEY_ERROR) {
                if (!VALUE.matches(value)) return null
                kept[key] = value
            }
        }
        return kept
    }

    /** 百分号解码（不把 + 当空格）；编码截断、不是十六进制、解出的字节不是合法 UTF-8：null。 */
    @Suppress("ReturnCount", "MagicNumber")
    private fun decode(text: String): String? {
        if ('%' !in text) return text
        val bytes = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '%') {
                if (i + 2 >= text.length) return null
                val hi = Character.digit(text[i + 1], 16)
                val lo = Character.digit(text[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                text.substring(i, i + 1).toByteArray(Charsets.UTF_8).let { bytes.write(it, 0, it.size) }
                i += 1
            }
        }
        return runCatching {
            Charsets.UTF_8
                .newDecoder()
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        }.getOrNull()
    }
}
