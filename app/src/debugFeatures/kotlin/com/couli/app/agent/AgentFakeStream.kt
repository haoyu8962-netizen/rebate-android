// 构造样例的价格、返利照 normal fixture 的合成数据改写（向量里的构造形态），按值直接写
@file:Suppress("MagicNumber", "ReturnCount", "TooManyFunctions", "ComplexCondition")

package com.couli.app.agent

import com.couli.core.agent.AgentCall
import com.couli.core.agent.AgentContractPort
import com.couli.core.agent.AgentHttpResult
import com.couli.core.agent.AgentJson
import com.couli.core.agent.AgentSse
import com.couli.core.agent.AgentStreamHandle
import com.couli.core.agent.AgentStreamSink
import com.couli.core.agent.AgentTimer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** release-scan 查这个标记（Release 制品里不得出现，APP-10 §2 第 4 条）。 */
internal const val AGENT_FAKE_MARKER = "COULI_FAKE_AGENT_SERVER_APP10_6b1c47"

/** 一次 sendAgentMessage 的回答脚本。 */
@Suppress("LongParameterList") // 一次回答的脚本字段逐项
internal class AgentFakeScript(
    /** fixture 原文（ndjson）；为 null 时按 [errorCode] 回 JSON。 */
    val ndjson: String? = null,
    /** 每行之间的间隔（毫秒）。 */
    val intervalMs: Long = 60,
    /** 在第 n 行（从 1 起，计 ping）之后断开（连接正常结束、没有终止帧）；null = 不断开。 */
    val disconnectAfterLine: Int? = null,
    /** 在第 n 行之后挂起不发（不结束，测 30 秒断线）；null = 不挂起。 */
    val hangAfterLine: Int? = null,
    val errorCode: Int = 0,
    val errorStatus: Int = 422,
    val errorData: JsonObject = JsonObject(emptyMap()),
    val retryAfter: String? = null,
    val traceId: String = AgentFakeStream.FAKE_TRACE,
    /** SSE 响应头 X-Trace-Id（空串不带；流内 error 帧没有 trace_id，5xxxx 文案用它）。 */
    val headerTraceId: String = "",
)

/**
 * Debug / Staging 专用：Agent 四个待定 operation 的本机假服务（APP-10 §2 第 4 条，G01；src/debugFeatures，Release 不编译）。不连任何地址、不做真实账号操作；
 * 数据是锁定契约的 15 个 fixture（随 debug / staging 打进 assets，Release 没有）与由它改出的构造样例。
 *
 * **两道边界在实际传输入口统一判断**（APP-11 第 10、16 条）：每一次实际「发包」（建会话、取当前会话、发消息、停止）都核对「假服务开关文件在 + 当前环境允许
 * MOCK_DATA」（prod 一律不允许）与调用方的 sendAllowed；不通过就不发（notSent / 传输失败），测试包连 prod 时不注入假流。
 * fixture 的 ndjson 逐行转成 SSE 文本（event / id / data 三行 + 空行，ping → `: ping`），可配置每帧间隔、在某行后断开、在某行后挂起不发（测 30 秒）。
 * 只记发出次数与在途流的归属键，不保存任何请求正文（鸿蒙 R1-12 / H25）；同一 client_msg_id 重发回 duplicate 两帧（只记 id，撤回 / 换号 / 切环境随归属作废清掉）。
 */
internal class AgentFakeStream(
    private val switchOn: () -> Boolean,
    private val mockAllowed: () -> Boolean,
    private val fixtures: (String) -> String?,
    private val state: () -> Map<String, String>,
    private val timer: AgentTimer,
) : AgentContractPort {
    val marker: String = AGENT_FAKE_MARKER

    /** 本机假的「AI 同意已记录」（recordConsent 成功后由 App 告知；撤回清零）。状态文件 agent_consent=pending 时从未同意开始。 */
    var consentGranted: Boolean = state()["agent_consent"] != "pending"

    /** 实际「发出」的次数（冒烟与测试核对）。 */
    var sent: Int = 0
        private set
    var cancels: Int = 0
        private set

    private val streams = mutableListOf<FakeStream>()
    private val seen = mutableMapOf<Any, MutableSet<String>>()
    private var sessionSeq = 0

    /** 假服务此刻可用：开关文件在 + 当前环境允许 MOCK_DATA。 */
    fun enabled(): Boolean = runCatching { switchOn() && mockAllowed() }.getOrDefault(false)

    /** 此刻能不能由本机假服务回答（与传输入口同一道检查）。 */
    private fun allowed(call: AgentCall): Boolean = enabled() && runCatching(call.sendAllowed).getOrDefault(false)

    override suspend fun createSession(call: AgentCall): AgentHttpResult {
        if (!allowed(call)) return AgentHttpResult.notSentResult()
        sent += 1
        sessionSeq += 1
        return answer(0, obj("session" to obj("id" to JsonPrimitive("fake-session-$sessionSeq"))))
    }

    override suspend fun currentSession(call: AgentCall): AgentHttpResult {
        if (!allowed(call)) return AgentHttpResult.notSentResult()
        sent += 1
        if (state()["agent_load"] == "10004" && !consentGranted) return answer(10004, obj("consent_type" to JsonPrimitive(CONSENT_AI)), 403)
        if (state()["agent_load"] == "fail") return answer(50001, JsonObject(emptyMap()), 500)
        return answer(0, obj("session" to JsonNull))
    }

    override fun sendMessage(
        sessionId: String,
        body: String,
        call: AgentCall,
        sink: AgentStreamSink,
    ): AgentStreamHandle {
        val stream = FakeStream(call.identityKey, sink)
        if (!allowed(call)) {
            stream.later(0) { sink.onFailure() }
            return stream
        }
        sent += 1
        streams += stream
        if (!consentGranted) {
            json(stream, sink, 403, 10004, obj("consent_type" to JsonPrimitive(CONSENT_AI)), null, FAKE_TRACE)
            return stream
        }
        val msgId = AgentJson.stringOf(AgentJson.objectOf(AgentJson.parse(body))?.get("client_msg_id"))
        val firstTime = msgId == null || seen.getOrPut(call.identityKey) { mutableSetOf() }.add(msgId)
        val script = if (firstTime) script() else AgentFakeScript(ndjson = fixtures("duplicate-ended-done"))
        val ndjson = script.ndjson
        if (ndjson == null) {
            json(
                stream,
                sink,
                script.errorStatus,
                if (script.errorCode ==
                    0
                ) {
                    50001
                } else {
                    script.errorCode
                },
                script.errorData,
                script.retryAfter,
                script.traceId,
            )
            return stream
        }
        val lines = ndjson.split('\n').filter { it.isNotBlank() }
        val headers =
            buildMap {
                put("content-type", "text/event-stream; charset=utf-8")
                if (script.headerTraceId.isNotEmpty()) put("x-trace-id", script.headerTraceId)
            }
        stream.later(0) { sink.onHeaders(200, headers) }
        var at = 0L
        lines.forEachIndexed { i, line ->
            at += script.intervalMs
            val text = AgentSse.sseTextOfNdjsonLine(line)
            stream.later(at) { sink.onData(text) }
            val n = i + 1
            if (script.hangAfterLine != null && n >= script.hangAfterLine) return stream
            if (script.disconnectAfterLine != null && n >= script.disconnectAfterLine) {
                stream.later(at + 1) { sink.onEnd() }
                return stream
            }
        }
        stream.later(at + 1) { sink.onEnd() }
        return stream
    }

    override suspend fun cancelRun(
        runId: String,
        call: AgentCall,
    ): AgentHttpResult {
        if (!allowed(call)) return AgentHttpResult.notSentResult()
        sent += 1
        cancels += 1
        return answer(0, JsonObject(emptyMap()))
    }

    override fun cancelOwned(identityKey: Any) {
        streams.filter { it.owner == identityKey }.forEach { it.cancel() }
        streams.removeAll { it.closed }
        seen.remove(identityKey)
    }

    /** 状态文件 agent_fixture=<名字>：下一条消息回答哪个 fixture（缺省 normal）；构造样例与 json_<错误码> 见 [scriptOf]。 */
    private fun script(): AgentFakeScript = scriptOf(state()["agent_fixture"] ?: "normal")

    fun scriptOf(name: String): AgentFakeScript {
        if (name.startsWith("json_")) {
            val code = name.removePrefix("json_").toIntOrNull() ?: 50001
            return AgentFakeScript(
                errorCode = code,
                errorStatus =
                    if (code == 42901) {
                        429
                    } else if (code >= 50000) {
                        503
                    } else {
                        422
                    },
                retryAfter = if (code == 42901) "12" else null,
            )
        }
        fixtures(name)?.let { raw ->
            return when (name) {
                // 历史形状的收益卡只有一行 card 帧：截图时包成一轮流（meta + card + done），按 SSE 来源「恰好合历史形状 → 历史渲染」显示（G13，鸿蒙 H12）
                "normal-earnings-history" -> AgentFakeScript(ndjson = wrapHistory(raw))

                // 最后一行后挂起（不结束、不再发 ping）：30 秒后客户端判断线
                "disconnected" -> AgentFakeScript(ndjson = raw, hangAfterLine = raw.split('\n').count { it.isNotBlank() })

                else -> AgentFakeScript(ndjson = raw)
            }
        }
        return AgentFakeScript(ndjson = constructed(fixtures("normal").orEmpty(), name))
    }

    private fun answer(
        code: Int,
        data: JsonElement,
        status: Int = 200,
    ): AgentHttpResult =
        AgentHttpResult.of(
            status,
            emptyMap(),
            obj(
                "code" to JsonPrimitive(code),
                "msg" to
                    JsonPrimitive(
                        if (code ==
                            0
                        ) {
                            "ok"
                        } else {
                            "error"
                        },
                    ),
                "data" to data,
                "trace_id" to JsonPrimitive(FAKE_TRACE),
            ).toString(),
        )

    @Suppress("LongParameterList")
    private fun json(
        stream: FakeStream,
        sink: AgentStreamSink,
        status: Int,
        code: Int,
        data: JsonObject,
        retryAfter: String?,
        traceId: String,
    ) {
        val headers =
            buildMap {
                put("content-type", "application/json")
                retryAfter?.let { put("retry-after", it) }
            }
        val env = obj("code" to JsonPrimitive(code), "msg" to JsonPrimitive("error"), "data" to data, "trace_id" to JsonPrimitive(traceId))
        stream.later(0) {
            sink.onHeaders(status, headers)
            sink.onData(env.toString())
            sink.onEnd()
        }
    }

    private inner class FakeStream(
        val owner: Any,
        private val sink: AgentStreamSink,
    ) : AgentStreamHandle {
        private val cancels = mutableListOf<() -> Unit>()
        var closed = false
            private set

        /**
         * 排一帧。每一帧交付前再过一次闸门（F-X-02 等待期）：流进行中切到 prod / 开始切换环境的，剩下的帧一律不交，流按传输失败结束
         * （只报一次 onFailure）。
         */
        fun later(
            ms: Long,
            run: () -> Unit,
        ) {
            if (closed) return
            cancels +=
                timer.after(ms) {
                    if (closed) return@after
                    if (!enabled()) {
                        cancel()
                        sink.onFailure()
                        return@after
                    }
                    run()
                }
        }

        override fun cancel() {
            closed = true
            cancels.forEach { it() }
            cancels.clear()
        }
    }

    companion object {
        private const val CONSENT_AI = "ai_third_party"
        const val FAKE_TRACE = "0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a6b"

        private fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*pairs))

        private fun wrapHistory(raw: String): String {
            val meta =
                """{"event":"meta","id":1,"data":{"session_id":"019a0000-0000-7000-8000-000000000001",""" +
                    """"run_id":"019a0000-0000-7000-8000-000000000109","message_id":"019a0000-0000-7000-8000-000000000209",""" +
                    """"prompt_version":"demo-prompt-v1","model_label":"demo","ai_label":"demo"}}"""
            val done = """{"event":"done","id":9,"data":{"finish_reason":"stop","quota_left":20}}"""
            return meta + "\n" + raw.trim() + "\n" + done + "\n"
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
