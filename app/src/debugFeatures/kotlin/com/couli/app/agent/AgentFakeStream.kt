// 构造样例的价格、返利照 normal fixture 的合成数据改写（向量里的构造形态），按值直接写
@file:Suppress("MagicNumber", "ReturnCount", "TooManyFunctions", "ComplexCondition", "LargeClass")

package com.couli.app.agent

import com.couli.core.agent.AgentJson
import com.couli.core.agent.AgentSse
import com.couli.core.network.LocalExchange
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Pipe
import okio.Source
import okio.Timeout
import okio.buffer

/** release-scan 查这个标记（Release 制品里不得出现，APP-10 §2 第 4 条；APP-10b 的历史 / 反馈 / 举报假实现同一标记）。 */
internal const val AGENT_FAKE_MARKER = "COULI_FAKE_AGENT_SERVER_APP10_6b1c47"

/**
 * Debug / Staging 专用：七个 agent operation 的本机假服务（APP-10b §2 第 13 条；src/debugFeatures，Release 不编译）。不连任何地址、不做真实账号操作；数据是锁定契约
 * 的 15 个 fixture（随 debug / staging 打进 assets，Release 没有，APP-10 A02）与由它改出的构造样例（标「构造」，不冒充契约 fixture）。
 *
 * **在实际传输入口**（ApiClient → HttpTransport 的 [LocalExchange]，写出前核对——世代、调用归属、发送前闸门、放行判定——通过之后才问它）：每次回答现判
 * DebugMockGate（开关文件 + 当前环境允许 MOCK_DATA + 环境就绪；prod 一律不允许），不允许就交真实网络（测试包连 prod 不注入假流）。流式正文由后台线程按间隔写进
 * 管道，每一块交付前再核对一次闸门与调用是否已取消（流进行中切到 prod / 开始切换环境 / 被取消的，剩下的帧不交、按传输失败结束）。
 *
 * 状态文件（每行 key=value，与假登录服务同一个开关文件目录）：
 * - agent_fixture=<名字>：normal、tool-failed、disconnected、… 15 个 fixture；构造：login_required、grid、relaxed、spec_unconfirmed、
 *   price_compare_risk、off_shelf、coupon_gone、gap（缺 id=3）、frame70k（单帧 70 KiB）、frames401（401 条内容帧）；json_<码>（含 json_42901
 *   带 Retry-After、json_42901_bare 不带、json_10002 刷新后同体重放、json_50401、json_50001）、html_502（网关错误页）、json_slow（慢 JSON 正文）；
 * - agent_chunks=1,3,17（按字节循环切块；缺省按帧）、agent_bom=1（流首 BOM）、agent_crlf=1（行结束用 \r\n）、agent_interval_ms=<毫秒>；
 * - agent_history=none|active|expired|paged|earnings_bad|unknown_card|reload|30504|10004|fail|42901（历史首页 / 翻页）；
 * - agent_feedback / agent_report=ok|30504|20001|42901|10004|fail|slow；agent_cancel=ok|30505|42901|10004；
 * - agent_consent=pending（AI 同意未记录：发消息回 10004）；agent_load=10004|fail（取当前会话）；agent_session=none（取当前会话为空）。
 * 只记次数与最近几个 client_msg_id（同键重发回 duplicate 两帧；归属变了清掉），不保存任何请求正文（鸿蒙 R1-12 / H25，举报原因同样）。
 */
internal class AgentFakeServer(
    private val gate: () -> Boolean,
    private val mockAllowed: () -> Boolean,
    private val fixtures: (String) -> String?,
    private val state: () -> Map<String, String>,
    private val writer: ScheduledExecutorService = WRITER,
) : LocalExchange {
    val marker: String = AGENT_FAKE_MARKER

    /** 本机假的「AI 同意已记录」（recordConsent 成功后由 App 告知；撤回清零）。状态文件 agent_consent=pending 时从未同意开始。 */
    @Volatile
    var consentGranted: Boolean = state()["agent_consent"] != "pending"

    /** 实际回答的次数（冒烟与测试核对；不含正文）。 */
    @Volatile
    var sent: Int = 0
        private set

    @Volatile
    var cancels: Int = 0
        private set

    @Volatile
    var histories: Int = 0
        private set

    private val lock = Any()
    private val seen = ArrayDeque<String>()
    private var seenOwner: String? = null
    private var sessionSeq = 0
    private var refreshServed = false

    /** 假服务此刻可用：开关文件在 + 当前环境允许 MOCK_DATA + 环境就绪（两道核对，结果相同）。 */
    fun enabled(): Boolean = runCatching { gate() && mockAllowed() }.getOrDefault(false)

    override fun handles(request: Request): Boolean = request.url.encodedPath.startsWith(AGENT_PREFIX)

    @Suppress("CyclomaticComplexMethod") // 按分支逐条对照规则
    override fun answer(
        request: Request,
        canceled: () -> Boolean,
    ): Response? {
        if (!enabled()) return null
        sent += 1
        val path = request.url.encodedPath
        val owner = request.header("Authorization") ?: request.header("X-Device-Id") ?: "anonymous"
        synchronized(lock) {
            if (seenOwner != owner) {
                seen.clear()
                seenOwner = owner
            }
        }
        return when {
            request.method == "POST" && path == SESSIONS -> createSession(request)
            request.method == "GET" && path == CURRENT -> current(request)
            request.method == "GET" && MESSAGES.matches(path) -> history(request)
            request.method == "POST" && MESSAGES.matches(path) -> message(request, canceled)
            request.method == "POST" && CANCEL.matches(path) -> cancel(request)
            request.method == "POST" && FEEDBACK.matches(path) -> action(request, "agent_feedback")
            request.method == "POST" && REPORT.matches(path) -> action(request, "agent_report")
            else -> envelope(request, 50001, JsonNull, 404)
        }
    }

    // ---------------------------------------------------------------- 会话

    private fun createSession(request: Request): Response {
        val n = synchronized(lock) { ++sessionSeq }
        return ok(request, AgentJson.obj("session_id" to "fake-session-$n", "started_at" to NOW, "last_active_at" to NOW))
    }

    private fun current(request: Request): Response {
        val s = state()
        if (s["agent_load"] == "10004" && !consentGranted) return envelope(request, 10004, AgentJson.obj("consent_type" to CONSENT_AI), 403)
        if (s["agent_load"] == "fail") return envelope(request, 50001, JsonNull, 500)
        val session =
            if (s["agent_session"] == "none" || s["agent_history"] == null) {
                JsonNull
            } else {
                AgentJson.obj("session_id" to "fake-session-current", "started_at" to NOW, "last_active_at" to NOW)
            }
        return ok(request, AgentJson.obj("session" to session))
    }

    // ---------------------------------------------------------------- 历史（构造：openapi active / expired 示例的形状）

    private fun history(request: Request): Response {
        histories += 1
        val cursor = request.url.queryParameter("cursor")
        return when (val name = state()["agent_history"] ?: "none") {
            "30504" -> envelope(request, 30504, AgentJson.obj("reason" to "not_found"), 404)
            "10004" -> envelope(request, 10004, AgentJson.obj("consent_type" to CONSENT_AI), 403)
            "42901" -> envelope(request, 42901, JsonNull, 429, retryAfter = "2")
            "fail" -> envelope(request, 50001, JsonNull, 500)
            else -> ok(request, historyPage(name, cursor))
        }
    }

    /** 构造的历史页（只在内存；不含任何真实账号数据）。 */
    fun historyPage(
        name: String,
        cursor: String?,
    ): JsonObject {
        fun page(
            items: List<JsonObject>,
            next: String? = null,
            expired: Boolean = false,
        ) = AgentJson.obj("items" to JsonArray(items), "next_cursor" to next, "expired" to expired)
        return when (name) {
            "active" -> {
                page(listOf(user(221, "提现记录在哪看", 0), reply(222, "提现进度在提现记录里查看。", 3, listOf(GUIDE_CARD))), "opaque-cursor-older-page")
            }

            "expired" -> {
                page(listOf(user(331, "返利什么时候到账", 0), reply(332, "", 2, emptyList(), "cancelled")), expired = true)
            }

            "paged" -> {
                if (cursor == null) {
                    page(listOf(user(441, "现在有多少收益", 60), reply(442, "你的收益情况见下方卡片，以钱包页为准。", 63, listOf(EARNINGS_HISTORY))), "fake-cursor-2")
                } else {
                    page(listOf(user(431, "提现记录在哪看", 0), reply(432, "提现进度在提现记录里查看。", 3, listOf(GUIDE_CARD))))
                }
            }

            "earnings_bad" -> {
                page(listOf(user(551, "我现在有多少收益", 0), reply(552, "你的收益情况见下方卡片，以钱包页为准。", 3, listOf(EARNINGS_WITH_AMOUNTS))))
            }

            "unknown_card" -> {
                page(listOf(user(661, "有没有新功能", 0), reply(662, "", 3, listOf(UNKNOWN_CARD))))
            }

            "reload" -> {
                page(listOf(user(771, "帮我找纯牛奶", 0), reply(RELOAD_REPLY_ID, "为你找到以下商品，已经整理好了。", 3, emptyList())))
            }

            else -> {
                page(emptyList())
            }
        }
    }

    private fun user(
        n: Int,
        text: String,
        offset: Int,
    ) = AgentJson.obj(
        "message_id" to messageId(n),
        "role" to "user",
        "created_at" to at(offset),
        "text" to text,
        "cards" to JsonArray(emptyList()),
        "finish_reason" to null,
    )

    private fun reply(
        n: Int,
        text: String,
        offset: Int,
        cards: List<JsonObject>,
        finish: String = "stop",
    ) = AgentJson.obj(
        "message_id" to messageId(n),
        "role" to "assistant",
        "created_at" to at(offset),
        "text" to text,
        "cards" to JsonArray(cards),
        "finish_reason" to finish,
    )

    // ---------------------------------------------------------------- 发消息（SSE 或 JSON 外壳）

    private fun message(
        request: Request,
        canceled: () -> Boolean,
    ): Response {
        val s = state()
        if (!consentGranted) return envelope(request, 10004, AgentJson.obj("consent_type" to CONSENT_AI), 403)
        val body = request.body?.let { b -> Buffer().also(b::writeTo).readUtf8() }.orEmpty()
        // 只取 client_msg_id 判重（正文立即丢弃，不保存）
        val msgId = AgentJson.stringOf(AgentJson.objectOf(AgentJson.parse(body))?.get("client_msg_id"))
        val firstTime = msgId == null || synchronized(lock) { remember(msgId) }
        val name = if (firstTime) s["agent_fixture"] ?: "normal" else "duplicate-ended-done"
        jsonScript(request, name)?.let { return it }
        val ndjson = scriptOf(name) ?: return envelope(request, 50001, JsonNull, 500)
        return sse(request, ndjson, s, canceled)
    }

    private fun remember(id: String): Boolean {
        if (id in seen) return false
        seen.addLast(id)
        while (seen.size > SEEN_MAX) seen.removeFirst()
        return true
    }

    /** 非流回答（JSON 外壳、网关错误页、慢 JSON）；不是这类返回 null。 */
    @Suppress("CyclomaticComplexMethod") // 按分支逐条对照规则
    private fun jsonScript(
        request: Request,
        name: String,
    ): Response? {
        if (name == "html_502") return text(request, 502, "text/html", "<html><body>502 Bad Gateway</body></html>")
        if (name == "json_slow") return slowJson(request)
        if (!name.startsWith("json_")) return null
        val raw = name.removePrefix("json_")
        if (raw == "10002") {
            // 刷新后同体重放（G23）：第一次 10002，重放（刷新之后）按 normal 回答
            val first = synchronized(lock) { !refreshServed.also { refreshServed = !it } }
            return if (first) envelope(request, 10002, JsonNull, 401) else null
        }
        val code = raw.removeSuffix("_bare").toIntOrNull() ?: 50001
        val status =
            when {
                code == 42901 -> 429
                code >= 50000 -> 503
                else -> 422
            }
        val data =
            when (code) {
                30502 -> AgentJson.obj("reset_at" to "2026-10-15T00:00:00+08:00", "next" to (state()["agent_quota_next"] ?: "login"))
                30504 -> AgentJson.obj("reason" to "round_limit")
                10004 -> AgentJson.obj("consent_type" to CONSENT_AI)
                20001 -> AgentJson.obj("fields" to listOf("text"))
                else -> JsonNull
            }
        return envelope(request, code, data, status, retryAfter = if (code == 42901 && !raw.endsWith("_bare")) "12" else null)
    }

    /** fixture 或构造样例的 ndjson（json_ / html_ 之外）。 */
    fun scriptOf(name: String): String? {
        fixtures(name)?.let { raw ->
            return when (name) {
                // 历史形状的收益卡只有一行 card 帧：包成一轮流（meta + card + done），按 SSE 来源「恰好合历史形状 → 历史渲染」显示（G13，鸿蒙 H12）
                "normal-earnings-history" -> wrapHistory(raw)

                else -> raw
            }
        }
        val normal = fixtures("normal").orEmpty()
        return when (name) {
            "gap" -> {
                normal
                    .split('\n')
                    .filter { it.isNotBlank() }
                    .filterIndexed { i, _ -> i != 2 }
                    .joinToString("\n", postfix = "\n")
            }

            "frame70k" -> {
                frame70k()
            }

            "frames401" -> {
                frames401()
            }

            else -> {
                constructed(normal, name)
            }
        }
    }

    @Suppress("LongMethod") // 步骤逐项
    private fun sse(
        request: Request,
        ndjson: String,
        s: Map<String, String>,
        canceled: () -> Boolean,
    ): Response {
        val lines = ndjson.split('\n').filter { it.isNotBlank() }
        val name = s["agent_fixture"] ?: "normal"
        // 最后一行后挂起（不结束、不再发 ping）：30 秒后客户端判断线
        val hang = name == "disconnected"
        val crlf = s["agent_crlf"] == "1"
        var text = lines.joinToString("") { AgentSse.sseTextOfNdjsonLine(it) }
        if (crlf) text = text.replace("\n", "\r\n")
        var bytes = text.toByteArray(Charsets.UTF_8)
        if (s["agent_bom"] == "1") bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes
        val sizes = s["agent_chunks"]?.split(',')?.mapNotNull { it.trim().toIntOrNull()?.takeIf { n -> n > 0 } }.orEmpty()
        val chunks =
            if (sizes.isEmpty()) {
                // 按帧下发（每帧一块）
                val frameText = lines.map { l -> AgentSse.sseTextOfNdjsonLine(l).let { if (crlf) it.replace("\n", "\r\n") else it } }
                frameText.mapIndexed { i, t ->
                    (
                        if (i == 0 &&
                            s["agent_bom"] == "1"
                        ) {
                            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                        } else {
                            ByteArray(0)
                        }
                    ) +
                        t.toByteArray()
                }
            } else {
                split(bytes, sizes)
            }
        val interval = s["agent_interval_ms"]?.toLongOrNull() ?: DEFAULT_INTERVAL_MS
        val pipe = Pipe(PIPE_BYTES)
        val out = pipe.sink.buffer()
        var at = 0L
        chunks.forEach { chunk ->
            at += interval
            writer.schedule(
                {
                    runCatching {
                        if (canceled() || !enabled()) {
                            // 流进行中切到 prod / 开始切换环境 / 被取消：剩下的帧不交，按传输失败结束
                            pipe.cancel()
                        } else {
                            out.write(chunk)
                            out.flush()
                        }
                    }
                },
                at,
                TimeUnit.MILLISECONDS,
            )
        }
        if (!hang) writer.schedule({ runCatching { out.close() } }, at + interval, TimeUnit.MILLISECONDS)
        val source = CancellableSource(pipe.source, canceled).buffer()
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "text/event-stream; charset=utf-8")
            .header("Cache-Control", "no-cache")
            .header("X-Trace-Id", FAKE_TRACE)
            .body(StreamBody(source))
            .build()
    }

    /** 慢 JSON 正文（APP-10 H26：正文分块不重置 30 秒）。 */
    private fun slowJson(request: Request): Response {
        val env = AgentJson.obj("code" to 50401, "msg" to "error", "data" to JsonNull, "trace_id" to FAKE_TRACE).toString().toByteArray()
        val pipe = Pipe(PIPE_BYTES)
        val out = pipe.sink.buffer()
        split(env, listOf(8)).forEachIndexed { i, chunk ->
            writer.schedule({ runCatching { out.write(chunk).flush() } }, (i + 1) * SLOW_JSON_STEP_MS, TimeUnit.MILLISECONDS)
        }
        writer.schedule({ runCatching { out.close() } }, (env.size / 8 + 2) * SLOW_JSON_STEP_MS, TimeUnit.MILLISECONDS)
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(504)
            .message("OK")
            .header("Content-Type", "application/json")
            .body(StreamBody(pipe.source.buffer(), JSON))
            .build()
    }

    // ---------------------------------------------------------------- 停止、反馈、举报

    private fun cancel(request: Request): Response {
        cancels += 1
        return when (state()["agent_cancel"]) {
            "30505" -> envelope(request, 30505, JsonNull, 409)
            "42901" -> envelope(request, 42901, JsonNull, 429, retryAfter = "2")
            "10004" -> envelope(request, 10004, AgentJson.obj("consent_type" to CONSENT_AI), 403)
            else -> ok(request, AgentJson.EMPTY)
        }
    }

    private fun action(
        request: Request,
        key: String,
    ): Response {
        // 请求体（反馈值 / 举报原因）不读、不保存
        return when (state()[key]) {
            "30504" -> {
                envelope(request, 30504, AgentJson.obj("reason" to "not_found"), 404)
            }

            "20001" -> {
                envelope(request, 20001, AgentJson.obj("fields" to listOf(if (key == "agent_report") "reason" else "feedback")), 422)
            }

            "42901" -> {
                envelope(request, 42901, JsonNull, 429, retryAfter = "5")
            }

            "10004" -> {
                envelope(request, 10004, AgentJson.obj("consent_type" to CONSENT_AI), 403)
            }

            "fail" -> {
                envelope(request, 50001, JsonNull, 500)
            }

            "slow" -> {
                Thread.sleep(SLOW_ACTION_MS)
                ok(request, AgentJson.EMPTY)
            }

            else -> {
                ok(request, AgentJson.EMPTY)
            }
        }
    }

    // ---------------------------------------------------------------- 外壳

    private fun ok(
        request: Request,
        data: JsonElement,
    ) = envelope(request, 0, data, 200)

    private fun envelope(
        request: Request,
        code: Int,
        data: JsonElement,
        status: Int,
        retryAfter: String? = null,
    ): Response {
        val env = AgentJson.obj("code" to code, "msg" to if (code == 0) "ok" else "error", "data" to data, "trace_id" to FAKE_TRACE)
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("OK")
            .header("X-Trace-Id", FAKE_TRACE)
            .apply { retryAfter?.let { header("Retry-After", it) } }
            .body(env.toString().toResponseBody(JSON))
            .build()
    }

    private fun text(
        request: Request,
        status: Int,
        type: String,
        body: String,
    ) = Response
        .Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("Bad Gateway")
        .body(body.toResponseBody(type.toMediaType()))
        .build()

    /** 流式正文（长度未知）。 */
    private class StreamBody(
        private val source: BufferedSource,
        private val type: okhttp3.MediaType? = EVENT_STREAM,
    ) : ResponseBody() {
        override fun contentType(): okhttp3.MediaType? = type

        override fun contentLength(): Long = -1

        override fun source(): BufferedSource = source
    }

    /** 管道读端：每 100 毫秒核对一次调用是否已取消（被取消即按断连结束），不因为一时没有数据就报超时（挂起测 30 秒断线）。 */
    private class CancellableSource(
        private val delegate: Source,
        private val canceled: () -> Boolean,
    ) : Source {
        init {
            delegate.timeout().timeout(POLL_MS, TimeUnit.MILLISECONDS)
        }

        override fun read(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            while (true) {
                if (canceled()) throw IOException("canceled")
                try {
                    return delegate.read(sink, byteCount)
                } catch (timeout: InterruptedIOException) {
                    if (canceled()) throw IOException("canceled", timeout)
                }
            }
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = delegate.close()
    }

    companion object {
        private const val AGENT_PREFIX = "/v1/agent/"
        private const val SESSIONS = "/v1/agent/sessions"
        private const val CURRENT = "/v1/agent/sessions/current"
        private val MESSAGES = Regex("^/v1/agent/sessions/[^/]+/messages$")
        private val CANCEL = Regex("^/v1/agent/runs/[^/]+/cancel$")
        private val FEEDBACK = Regex("^/v1/agent/messages/[^/]+/feedback$")
        private val REPORT = Regex("^/v1/agent/messages/[^/]+/report$")
        private const val CONSENT_AI = "ai_third_party"
        const val FAKE_TRACE = "0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a6b"
        private const val NOW = "2026-10-14T10:00:00+08:00"
        private const val SEEN_MAX = 32
        private const val PIPE_BYTES = 256L * 1024
        private const val DEFAULT_INTERVAL_MS = 60L
        private const val POLL_MS = 100L
        private const val SLOW_JSON_STEP_MS = 400L
        private const val SLOW_ACTION_MS = 3_000L
        const val RELOAD_REPLY_ID = 204
        private val JSON = "application/json".toMediaType()
        private val EVENT_STREAM = "text/event-stream; charset=utf-8".toMediaType()

        /** 写流的后台线程（守护线程，只写管道）。 */
        private val WRITER: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "couli-fake-agent").apply { isDaemon = true } }

        fun messageId(n: Int): String = "019a0000-0000-7000-8000-%012d".format(n)

        /** 构造时刻：2026-10-14T10:00:00+08:00 之后 [offset] 秒（页内按时间正序）。 */
        private fun at(offset: Int): String = "2026-10-14T10:%02d:%02d+08:00".format(offset / 60 % 60, offset % 60)

        private val GUIDE_CARD =
            AgentJson.obj(
                "card_id" to "c1",
                "type" to "page_guide",
                "schema_version" to 1,
                "data" to AgentJson.obj("route" to "WithdrawRecords", "text_key" to "agent.guide.WithdrawRecords"),
                "fallback_text" to "提现进度在提现记录里查看。",
            )

        private val EARNINGS_ACTIONS =
            JsonArray(
                listOf(
                    AgentJson.obj("route" to "Wallet", "text_key" to "agent.earnings.open_wallet"),
                    AgentJson.obj("route" to "WithdrawRecords", "text_key" to "agent.earnings.open_records"),
                ),
            )

        private val EARNINGS_HISTORY =
            AgentJson.obj(
                "card_id" to "c2",
                "type" to "earnings_summary",
                "schema_version" to 1,
                "data" to AgentJson.obj("as_of" to "2026-10-14T10:01:03+08:00", "actions" to EARNINGS_ACTIONS),
                "fallback_text" to "请到钱包页查看收益与提现进度。",
            )

        /** 构造：历史收益卡误带金额（客户端必须只按历史形状显示，不保留金额）。 */
        private val EARNINGS_WITH_AMOUNTS =
            AgentJson.obj(
                "card_id" to "c2",
                "type" to "earnings_summary",
                "schema_version" to 1,
                "data" to
                    AgentJson.obj(
                        "as_of" to "2026-10-14T10:01:03+08:00",
                        "withdrawable_fen" to 1000,
                        "estimated_total_fen" to 5678,
                        "actions" to EARNINGS_ACTIONS,
                    ),
                "fallback_text" to "请到钱包页查看收益与提现进度。",
            )

        private val UNKNOWN_CARD =
            AgentJson.obj(
                "card_id" to "c3",
                "type" to "future_card",
                "schema_version" to 1,
                "data" to AgentJson.EMPTY,
                "fallback_text" to "暂不能展示这张卡片",
            )

        private fun split(
            bytes: ByteArray,
            sizes: List<Int>,
        ): List<ByteArray> {
            val out = mutableListOf<ByteArray>()
            var offset = 0
            var i = 0
            while (offset < bytes.size) {
                val end = minOf(bytes.size, offset + sizes[i % sizes.size])
                out += bytes.copyOfRange(offset, end)
                offset = end
                i += 1
            }
            return out
        }

        private const val META =
            """{"event":"meta","id":1,"data":{"session_id":"019a0000-0000-7000-8000-000000000001",""" +
                """"run_id":"019a0000-0000-7000-8000-000000000109","message_id":"019a0000-0000-7000-8000-000000000209",""" +
                """"prompt_version":"demo-prompt-v1","model_label":"demo","ai_label":"demo"}}"""

        private fun wrapHistory(raw: String): String {
            val card = raw.trim().replace("\"id\":4,", "\"id\":2,").replace("\"seq\":4,", "\"seq\":2,")
            val done = """{"event":"done","id":3,"data":{"finish_reason":"stop","quota_left":20}}"""
            return META + "\n" + card + "\n" + done + "\n"
        }

        /** 构造：单帧 data 70 KiB（AC-S1-174 ⑤）。 */
        private fun frame70k(): String {
            val text = "x".repeat(70 * 1024)
            return META + "\n" + """{"event":"text.delta","id":2,"data":{"seq":2,"delta":"$text"}}""" + "\n"
        }

        /** 构造：401 条 1 字 text.delta（AC-S1-174 ⑥）。 */
        private fun frames401(): String {
            val deltas = (2..402).joinToString("\n") { id -> """{"event":"text.delta","id":$id,"data":{"seq":$id,"delta":"字"}}""" }
            return META + "\n" + deltas + "\n" + """{"event":"done","id":403,"data":{"finish_reason":"stop","quota_left":20}}""" + "\n"
        }

        /** 构造样例（向量里的构造形态，标「构造」，不冒充契约 fixture）：改 normal 第一张 product_list 卡的第一件商品。 */
        fun constructed(
            normal: String,
            name: String,
        ): String {
            var done = false
            return normal.split('\n').filter { it.isNotBlank() }.joinToString("\n", postfix = "\n") { line ->
                val o = AgentJson.objectOf(AgentJson.parse(line))
                val data = AgentJson.objectOf(o?.get("data"))
                if (done || o == null || data == null || AgentJson.stringOf(data["type"]) != "product_list") return@joinToString line
                done = true
                val inner = AgentJson.objectOf(data["data"]) ?: return@joinToString line
                val items = AgentJson.arrayOf(inner["items"]).orEmpty().mapNotNull { AgentJson.objectOf(it) }
                val first = items.firstOrNull() ?: return@joinToString line
                val patched = patch(first, name)
                val layout = if (name == "grid") JsonPrimitive("grid") else inner["layout"] ?: JsonPrimitive("list")
                val gridItems = if (name == "grid") listOf(patched, patch(first, "grid2")) else listOf(patched) + items.drop(1)
                val newInner = JsonObject(inner + mapOf("items" to JsonArray(gridItems), "layout" to layout))
                JsonObject(o + ("data" to JsonObject(data + ("data" to newInner)))).toString()
            }
        }

        private fun patch(
            p: JsonObject,
            name: String,
        ): JsonObject {
            val m = p.toMutableMap()
            when (name) {
                "login_required" -> {
                    m["rebate_basis"] = JsonPrimitive("login_required")
                    m["rebate_min_fen"] = JsonNull
                    m["rebate_max_fen"] = JsonNull
                }

                "price_compare_risk" -> {
                    m["rebate_basis"] = JsonPrimitive("price_compare_risk")
                    m["rebate_min_fen"] = JsonPrimitive(120)
                    m["rebate_max_fen"] = JsonPrimitive(260)
                }

                "relaxed" -> {
                    m["match_tag"] = JsonPrimitive("relaxed")
                }

                "spec_unconfirmed" -> {
                    m["match_tag"] = JsonPrimitive("spec_unconfirmed")
                    m["spec_text"] = JsonNull
                }

                "off_shelf" -> {
                    m["availability"] = JsonPrimitive("off_shelf")
                }

                "coupon_gone" -> {
                    m["availability"] = JsonPrimitive("coupon_gone")
                }

                "grid2" -> {
                    m["card_id"] = JsonPrimitive("c9")
                    m["product_key"] = JsonPrimitive("jd:demo-0009")
                    m["link_id"] = JsonPrimitive("019a0000-0000-7000-8000-000000000309")
                }
            }
            return JsonObject(m)
        }
    }
}
