package com.couli.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * APP-10 §2 第 2 条：`text/event-stream` 文本 → 条目（增量解析器，G07、G33）。
 *
 * 04 §8.1：一帧 `event:` / `id:` / `data:` 三行 + 空行；`: ping` 是心跳注释。按 SSE 通行规则：`\n` 与 `\r\n` 都认；字段名后的一个空格去掉；
 * 多行 data 用 `\n` 连接；空行结束一帧；`:` 开头是注释（`: ping` → ping，其他注释忽略）；未知字段名忽略。帧必须同时有 event、id、data，
 * 缺任一 → malformed（由 [AgentFrameDecoder] 丢弃）。跨网络分片的半行留到下一块。裸 CR、BOM、结束时没遇到空行的尾帧规划没写（向量 gaps G07）：
 * 尾帧不交出（SSE 规则：没有空行就不派发）。三端同一套判定（鸿蒙 AgentSse.ets）。
 *
 * 单帧上限（03 §7.2，Android R1-04）：一帧的 data（多行按 `\n` 连接后）按 UTF-8 累计超过 [FRAME_DATA_MAX_BYTES]（64 KiB），或还没遇到换行的半行
 * 超过同一上限，交出一条 too_large 并停止解析：之后喂入的块一律丢弃、不再累进内存（调用方关连接、结束本轮）。
 *
 * 条目形状：`{kindRaw: frame|ping|malformed|too_large, event?, id?, dataText?}`（id 是字符串）。只在调用方线程使用。
 */
class AgentSse {
    private val buffer = StringBuilder()
    private var event: String? = null
    private var id: String? = null
    private var data: MutableList<String>? = null

    /** 当前帧已累计的 data UTF-8 字节数（含多行之间的 `\n`）。 */
    private var dataBytes = 0L

    /** [buffer] 里还没遇到换行的半行的 UTF-8 字节数。 */
    private var pendingBytes = 0L

    /** 单帧超限之后停止解析（之后的块一律丢弃）。 */
    var overflowed: Boolean = false
        private set

    /** 收到的完整行数（字段行、注释行、空行；G33：每收到一行就重置 30 秒断线计时；半行不算）。 */
    var linesSeen: Long = 0
        private set

    /** 增量喂入一块文本，返回这一块里完成的条目。 */
    @Suppress("ReturnCount") // 已超限、逐行、半行超限依次返回
    fun feed(chunk: String): List<JsonObject> {
        if (overflowed) return emptyList()
        val out = mutableListOf<JsonObject>()
        buffer.append(chunk)
        pendingBytes += utf8Bytes(chunk)
        var at = buffer.indexOf("\n")
        while (at >= 0) {
            var line = buffer.substring(0, at)
            buffer.delete(0, at + 1)
            pendingBytes -= utf8Bytes(line) + 1
            if (line.endsWith('\r')) line = line.dropLast(1)
            linesSeen += 1
            line(line)?.let(out::add)
            if (dataBytes > FRAME_DATA_MAX_BYTES) return out + overflow()
            at = buffer.indexOf("\n")
        }
        // 没有换行的半行同样不得无限累积（一整行 data 超过 64 KiB 时还没遇到换行就截停）
        if (pendingBytes > FRAME_DATA_MAX_BYTES + LINE_PREFIX_SLACK) return out + overflow()
        return out
    }

    /** 单帧超限：清掉已累积的内容并停止解析。 */
    private fun overflow(): JsonObject {
        overflowed = true
        buffer.setLength(0)
        pendingBytes = 0
        reset()
        return tooLarge()
    }

    /** 连接正常结束：没遇到空行的尾帧不派发（只丢弃半行与未完成的帧）。 */
    fun finish(): List<JsonObject> {
        buffer.setLength(0)
        pendingBytes = 0
        reset()
        return emptyList()
    }

    private fun reset() {
        event = null
        id = null
        data = null
        dataBytes = 0
    }

    @Suppress("ReturnCount") // 空行、注释、字段依次处理
    private fun line(line: String): JsonObject? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(':')) {
            val comment = line.substring(1).removePrefix(" ")
            return if (comment == PING_COMMENT) ping() else null
        }
        val colon = line.indexOf(':')
        val name = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (name) {
            FIELD_EVENT -> {
                event = value
            }

            FIELD_ID -> {
                id = value
            }

            FIELD_DATA -> {
                val lines = data ?: mutableListOf<String>().also { data = it }
                dataBytes += utf8Bytes(value) + if (lines.isEmpty()) 0 else 1
                lines.add(value)
            }

            else -> {
                Unit
            }
        }
        return null
    }

    @Suppress("ReturnCount") // 空帧、缺字段、成帧依次返回
    private fun dispatch(): JsonObject? {
        val e = event
        val i = id
        val d = data
        reset()
        if (e == null && i == null && d == null) return null
        if (e == null || i == null || d == null) return malformed()
        return frame(e, i, d.joinToString("\n"))
    }

    companion object {
        const val KIND_FRAME = "frame"
        const val KIND_PING = "ping"
        const val KIND_MALFORMED = "malformed"

        /** 单帧 data 超过 64 KiB（03 §7.2）：协议错误，本地结束这一轮。 */
        const val KIND_TOO_LARGE = "too_large"

        /** 单帧 data 上限（UTF-8 字节，03 §7.2）。 */
        const val FRAME_DATA_MAX_BYTES = 64L * 1024

        /** 半行判定时给字段名与冒号空格留的余量（`data: `）。 */
        private const val LINE_PREFIX_SLACK = 16L
        private const val PING_COMMENT = "ping"
        private const val FIELD_EVENT = "event"
        private const val FIELD_ID = "id"
        private const val FIELD_DATA = "data"

        fun frame(
            event: String,
            id: String,
            dataText: String,
        ): JsonObject =
            JsonObject(
                linkedMapOf(
                    "kindRaw" to JsonPrimitive(KIND_FRAME),
                    "event" to JsonPrimitive(event),
                    "id" to JsonPrimitive(id),
                    "dataText" to JsonPrimitive(dataText),
                ),
            )

        fun ping(): JsonObject = JsonObject(mapOf("kindRaw" to JsonPrimitive(KIND_PING)))

        fun malformed(): JsonObject = JsonObject(mapOf("kindRaw" to JsonPrimitive(KIND_MALFORMED)))

        fun tooLarge(): JsonObject = JsonObject(mapOf("kindRaw" to JsonPrimitive(KIND_TOO_LARGE)))

        /** UTF-8 字节数（逐字符累加：代理对两半各算 2 字节，合起来是 4，跨块切开时也一致）。 */
        fun utf8Bytes(text: CharSequence): Long {
            var n = 0L
            for (ch in text) {
                n +=
                    when {
                        ch.code < UTF8_ONE -> 1L
                        ch.code < UTF8_TWO -> 2L
                        Character.isSurrogate(ch) -> 2L
                        else -> UTF8_THREE_BYTES
                    }
            }
            return n
        }

        private const val UTF8_ONE = 0x80
        private const val UTF8_TWO = 0x800
        private const val UTF8_THREE_BYTES = 3L

        /**
         * fixture 的一行（contracts/fixtures/agent-streams/ *.ndjson）→ 与 [feed] 同样的条目：`{"comment":"ping"}` → ping；`{event, id, data}` → frame
         * （id 转成十进制文本、data 转回 JSON 文本，与 SSE 线上形状一致）；不是 JSON 对象或缺三者之一 → malformed。Reducer 测试与 Debug 假流共用。
         */
        @Suppress("ReturnCount") // 解析失败、ping、缺字段依次返回
        fun fromNdjsonLine(line: String): JsonObject {
            val o = AgentJson.objectOf(AgentJson.parse(line)) ?: return malformed()
            if (o.keys == setOf("comment") && AgentJson.stringOf(o["comment"]) == PING_COMMENT) return ping()
            val event = AgentJson.stringOf(o["event"]) ?: return malformed()
            val id = o["id"] as? JsonPrimitive ?: return malformed()
            if (AgentJson.isNull(id)) return malformed()
            val data = o["data"] ?: return malformed()
            return frame(event, id.content, data.toString())
        }

        /** 一行 fixture → SSE 线上文本（Debug 假流用：event / id / data 三行 + 空行；ping → `: ping` + 空行）。不认识的行原样跳过。 */
        fun sseTextOfNdjsonLine(line: String): String {
            val item = fromNdjsonLine(line)
            return when (AgentJson.stringOf(item["kindRaw"])) {
                KIND_PING -> {
                    ": ping\n\n"
                }

                KIND_FRAME -> {
                    "event: ${AgentJson.stringOf(item["event"])}\nid: ${AgentJson.stringOf(item["id"])}\n" +
                        "data: ${AgentJson.stringOf(item["dataText"])}\n\n"
                }

                else -> {
                    ""
                }
            }
        }
    }
}
