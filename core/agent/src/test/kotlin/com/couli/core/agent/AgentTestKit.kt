package com.couli.core.agent

import com.couli.core.clientcore.ClientEvent
import com.couli.core.network.CallEpochs
import com.couli.core.router.RouteRequest
import com.couli.core.wallet.WalletRateGate
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * core:agent 执行层测试的替身（不访问网络、不落盘、不连真实 Router）：手动推进的计时器、可脚本化的待定 operation 端口（记录每次「实际发出」）、固定身份。
 * fixture 从仓库根（向上找 contract.lock）的 contracts/snapshot/contracts/fixtures/agent-streams/ 读，原样不改。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AgentTestKit {
    val scheduler = TestCoroutineScheduler()
    val dispatcher = UnconfinedTestDispatcher(scheduler)
    val scope = CoroutineScope(dispatcher)
    var now = START_MS
    val timer = ManualTimer { now }
    val gate = WalletRateGate(scope) { now }
    val port = ScriptPort()
    val mask = MutableStateFlow(false)
    val opens = mutableListOf<RouteRequest>()
    val toasts = mutableListOf<String>()
    val tracks = mutableListOf<ClientEvent>()
    val logs = mutableListOf<String>()
    val buys = mutableListOf<Pair<JsonObject, AgentProductRef>>()
    var services = 0
    var forced = 0
    var cancelledPurchases = 0
    var consentReply: suspend (Boolean) -> AgentHttpResult = { ok() }
    val consentCalls = mutableListOf<Boolean>()

    // ---- APP-10b：应用级接线（缺省与 APP-10 旧测试相同的行为）

    /** 三组闸门（null = 退回共享闸门 [gate]）。 */
    var gates: AgentGates? = null
    val ledger = AgentCancelLedger()

    /** 控制器上报的应用级「AI 同意失效」（归属键）。 */
    val lapses = mutableListOf<Any>()
    var allowed = true
    var foreground = true
    var randomUnit = 0.5
    var consentLapsed = false

    /** 此刻的身份（测试改它模拟换号、撤回、切环境）。 */
    var identity: AgentIdentity? = member("user-a")

    fun controller(
        fixed: AgentIdentity = identity!!,
        contract: AgentContractPort? = port,
        context: JsonObject? = null,
        examples: List<String> = emptyList(),
        visible: Boolean = true,
    ): AgentController =
        AgentController(
            AgentDeps(
                identity = fixed,
                currentIdentity = { identity },
                contract = contract,
                gate = gate,
                scope = scope,
                timer = timer,
                clock = { now },
                masked = mask,
                open = { opens += it },
                buy = { p, r -> buys += p to r },
                customerService = { services += 1 },
                recordConsent = { accepted ->
                    consentCalls += accepted
                    consentReply(accepted)
                },
                track = { tracks += it },
                toast = { toasts += it },
                forceUpdate = { forced += 1 },
                cancelPurchase = { cancelledPurchases += 1 },
                log = { logs += it },
                examples = { examples },
                context = context,
                gates = gates,
                ledger = ledger,
                aiConsentLost = { lapses += it },
                consentLapsed = consentLapsed,
                allowed = { allowed },
                foreground = { foreground },
                random = { randomUnit },
            ),
        ).also { c ->
            c.setVisible(visible)
            c.start()
        }

    fun advance(ms: Long) {
        now += ms
        timer.fire()
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    /** 手动推进的计时器。 */
    class ManualTimer(
        private val clock: () -> Long,
    ) : AgentTimer {
        private val pending = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()

        override fun after(
            delayMs: Long,
            block: () -> Unit,
        ): () -> Unit {
            val cancelled = BooleanArray(1)
            pending += Triple(clock() + delayMs, block, cancelled)
            return { cancelled[0] = true }
        }

        fun fire() {
            while (true) {
                val due = pending.firstOrNull { !it.third[0] && it.first <= clock() } ?: return
                pending -= due
                due.second()
            }
        }

        val armed: Int get() = pending.count { !it.third[0] }
    }

    /** 一条在途流：测试按需下发响应头、数据块、结束。 */
    class Stream(
        val sessionId: String,
        val body: String,
        val call: AgentCall,
        val sink: AgentStreamSink,
    ) : AgentStreamHandle {
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }

        /** 端口在 cancel 之后仍可能回调（StreamClient 必须丢弃）。 */
        fun headers(
            trace: String = "",
            type: String = "text/event-stream",
            status: Int = 200,
            retryAfter: String? = null,
        ) = sink.onHeaders(
            status,
            buildMap {
                put("Content-Type", type)
                if (trace.isNotEmpty()) put("X-Trace-Id", trace)
                retryAfter?.let { put("Retry-After", it) }
            },
        )

        fun line(ndjson: String) = sink.onData(AgentSse.sseTextOfNdjsonLine(ndjson))

        /** 原始字节（APP-10b 字节入口）。 */
        fun bytes(chunk: ByteArray) = sink.onBytes(chunk)

        fun fixture(
            name: String,
            upTo: Int = Int.MAX_VALUE,
        ) = fixtureLines(name).take(upTo).forEach(::line)

        fun end() = sink.onEnd()

        val clientMsgId: String? get() = AgentJson.stringOf(AgentJson.objectOf(AgentJson.parse(body))?.get("client_msg_id"))

        val bodyJson: JsonObject get() = AgentJson.objectOf(AgentJson.parse(body)) ?: AgentJson.EMPTY
    }

    /** 可脚本化的待定 operation 端口：每次「实际发出」（sendAllowed 通过）才记一次；回答可挂起。 */
    class ScriptPort : AgentContractPort {
        val streams = mutableListOf<Stream>()
        val notSent = mutableListOf<String>()
        var createReply: suspend () -> AgentHttpResult = {
            ok(
                JsonObject(mapOf("session" to JsonObject(mapOf("id" to JsonPrimitive("s1"))))),
            )
        }
        var currentReply: suspend () -> AgentHttpResult = { ok(JsonObject(mapOf("session" to kotlinx.serialization.json.JsonNull))) }
        var cancelReply: suspend () -> AgentHttpResult = { ok() }
        var historyReply: suspend (String?) -> AgentHttpResult = { ok(emptyHistory()) }
        var feedbackReply: suspend () -> AgentHttpResult = { ok() }
        var reportReply: suspend () -> AgentHttpResult = { ok() }
        var creates = 0
        var currents = 0
        val cancels = mutableListOf<Pair<String, AgentCall>>()
        val histories = mutableListOf<Pair<String?, AgentCall>>()
        val feedbacks = mutableListOf<Pair<String, String>>()
        val reports = mutableListOf<Pair<String, String>>()
        val owned = mutableListOf<Any>()
        val ownedExcept = mutableListOf<Pair<Any, String>>()
        val scopes = mutableListOf<Pair<Any, String>>()
        var hold: CompletableDeferred<Unit>? = null

        override suspend fun createSession(call: AgentCall): AgentHttpResult {
            if (!call.sendAllowed()) return notSent("create")
            creates += 1
            hold?.await()
            return createReply()
        }

        override suspend fun currentSession(call: AgentCall): AgentHttpResult {
            if (!call.sendAllowed()) return notSent("current")
            currents += 1
            hold?.await()
            return currentReply()
        }

        override fun sendMessage(
            sessionId: String,
            body: String,
            call: AgentCall,
            sink: AgentStreamSink,
        ): AgentStreamHandle {
            val s = Stream(sessionId, body, call, sink)
            if (!call.sendAllowed()) {
                notSent += "send"
                s.cancelled = true
                return s
            }
            streams += s
            return s
        }

        override suspend fun cancelRun(
            runId: String,
            call: AgentCall,
        ): AgentHttpResult {
            if (!call.sendAllowed()) return notSent("cancel")
            cancels += runId to call
            return cancelReply()
        }

        override suspend fun listMessages(
            sessionId: String,
            cursor: String?,
            limit: Int,
            call: AgentCall,
            autoRetry: Boolean,
        ): AgentHttpResult {
            if (!call.sendAllowed()) return notSent("history")
            histories += cursor to call
            return historyReply(cursor)
        }

        override suspend fun setFeedback(
            messageId: String,
            value: String,
            call: AgentCall,
        ): AgentHttpResult {
            if (!call.sendAllowed()) return notSent("feedback")
            feedbacks += messageId to value
            return feedbackReply()
        }

        override suspend fun report(
            messageId: String,
            reason: String,
            call: AgentCall,
        ): AgentHttpResult {
            if (!call.sendAllowed()) return notSent("report")
            reports += messageId to reason
            return reportReply()
        }

        /** 只记录（不替控制器关流）：「同步关连接」必须由控制器自己做，测试才能看出来。 */
        override fun cancelOwned(identityKey: Any) {
            owned += identityKey
        }

        override fun cancelOwnedExcept(
            identityKey: Any,
            keepScope: String,
        ) {
            ownedExcept += identityKey to keepScope
        }

        override fun cancelScope(
            identityKey: Any,
            scope: String,
        ) {
            scopes += identityKey to scope
        }

        private fun notSent(op: String): AgentHttpResult {
            notSent += op
            return AgentHttpResult.notSentResult()
        }

        val last: Stream get() = streams.last()
    }

    companion object {
        const val START_MS = 1_791_259_200_000L // 2026-10-06T10:00:00+08:00 左右（只用于月份年份比较）

        fun member(
            account: String,
            env: String = "test",
            aiConsent: Long = 0,
            page: Long = 1,
        ): AgentIdentity = AgentIdentity(env, AgentIdentity.MEMBER, account, 1, CallEpochs(0, 0), aiConsent, page)

        fun guest(): AgentIdentity = AgentIdentity("test", AgentIdentity.GUEST, null, 0, CallEpochs(0, 0), 0, 1)

        fun basic(): AgentIdentity = AgentIdentity("test", AgentIdentity.BASIC, null, 0, CallEpochs(0, 0), 0, 1)

        fun ok(data: JsonObject = JsonObject(emptyMap())): AgentHttpResult =
            AgentHttpResult(true, 200, 0, "ok", data, "trace-ok-000001", null)

        fun fail(
            code: Int,
            data: JsonObject = JsonObject(emptyMap()),
            trace: String = "0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a6b",
            retryAfter: String? = null,
            http: Int = 422,
        ): AgentHttpResult = AgentHttpResult(false, http, code, "error", data, trace, retryAfter)

        fun consentData(): JsonObject = JsonObject(mapOf("consent_type" to JsonPrimitive("ai_third_party")))

        /** 空的历史首页（items 空、没有更早、未过期）。 */
        fun emptyHistory(): JsonObject =
            JsonObject(
                mapOf(
                    "items" to kotlinx.serialization.json.JsonArray(emptyList()),
                    "next_cursor" to kotlinx.serialization.json.JsonNull,
                    "expired" to JsonPrimitive(false),
                ),
            )

        private val root: File by lazy {
            val start = File(System.getProperty("user.dir") ?: error("缺少 user.dir")).canonicalFile
            generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        }

        fun fixtureLines(name: String): List<String> =
            File(root, "contracts/snapshot/contracts/fixtures/agent-streams/$name.ndjson").readText().split('\n').filter { it.isNotBlank() }

        /** 一行 JSON 帧（构造用）。 */
        fun frameLine(
            event: String,
            id: Int,
            data: String,
        ): String = """{"event":"$event","id":$id,"data":$data}"""

        val META: String = fixtureLines("normal").first()

        fun errorLine(
            id: Int,
            code: Int,
            retryable: Boolean = false,
        ): String = frameLine("error", id, """{"code":$code,"msg":"m","retryable":$retryable,"fallback":null}""")
    }
}
