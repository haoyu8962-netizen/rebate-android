package com.couli.core.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * APP-10b Android：两组向量（timeouts、ownershipCancel）驱动真实 StreamClient / 归属协调器的脚本端口、虚拟流与硬编码帧（鸿蒙 R1-04，S0；共性第 13、25 条）。
 * 只在测试源码集：生产 Core 只保留接线入口（[AgentTimeoutPolicy.of] / [AgentOwnershipStop.of] 经 ServiceLoader 找到这里；META-INF/services 也只在
 * src/test/resources），Release / Staging / Debug 制品里都没有这些类与帧数据（release-scan 的 [TEST_ONLY_MARKER] 反向核对）。
 */
class TestAgentVectorWiring : AgentVectorWiring {
    override fun timeouts(
        input: JsonObject,
        nowMs: Long,
    ): JsonObject = TimeoutWiring.of(input)

    override fun ownership(
        input: JsonObject,
        nowMs: Long,
    ): JsonObject = OwnershipWiring.of(input)

    companion object {
        /** release-scan 查这个标记（只许出现在测试源码集；Release / Debug / Staging 制品里都不得出现）。 */
        const val TEST_ONLY_MARKER = "COULI_AGENT_VECTOR_PROBE_TESTONLY_APP10B"
    }
}

/** 虚拟时钟计时器（向量接线与集成测试用；按到期顺序执行；只在测试源码集）。 */
class AgentVirtualTimer : AgentTimer {
    var now: Long = 0
    private var serial = 0L
    private val tasks = linkedMapOf<Long, Pair<Long, () -> Unit>>()

    override fun after(
        delayMs: Long,
        block: () -> Unit,
    ): () -> Unit {
        serial += 1
        val id = serial
        tasks[id] = (now + delayMs) to block
        return { tasks.remove(id) }
    }

    val pending: Int get() = tasks.size

    /** 推进到 [at]（含 at 时刻到期的任务）。 */
    fun advanceTo(at: Long) {
        var guard = 0
        while (guard < GUARD) {
            guard += 1
            val next = tasks.entries.filter { it.value.first <= at }.minWithOrNull(compareBy({ it.value.first }, { it.key })) ?: break
            tasks.remove(next.key)
            now = maxOf(now, next.value.first)
            next.value.second()
        }
        now = maxOf(now, at)
    }

    private companion object {
        const val GUARD = 10_000
    }
}

/** 只记次数、不保存正文的脚本端口：把 sink 留给探针逐块喂字节。 */
private open class ProbePort : AgentContractPort {
    var sink: AgentStreamSink? = null
    var cancels = 0
    var cancelledByPage = false

    override suspend fun createSession(call: AgentCall): AgentHttpResult = AgentHttpResult.notSentResult()

    override suspend fun currentSession(call: AgentCall): AgentHttpResult = AgentHttpResult.notSentResult()

    override fun sendMessage(
        sessionId: String,
        body: String,
        call: AgentCall,
        sink: AgentStreamSink,
    ): AgentStreamHandle {
        this.sink = sink
        return AgentStreamHandle {}
    }

    override suspend fun cancelRun(
        runId: String,
        call: AgentCall,
    ): AgentHttpResult {
        if (call.sendAllowed()) cancels += 1
        // 不回答（结果由向量接线按 cancelResult 结算）
        CompletableDeferred<Unit>().await()
        return AgentHttpResult.notSentResult()
    }

    override suspend fun listMessages(
        sessionId: String,
        cursor: String?,
        limit: Int,
        call: AgentCall,
        autoRetry: Boolean,
    ): AgentHttpResult = AgentHttpResult.notSentResult()

    override suspend fun setFeedback(
        messageId: String,
        value: String,
        call: AgentCall,
    ): AgentHttpResult = AgentHttpResult.notSentResult()

    override suspend fun report(
        messageId: String,
        reason: String,
        call: AgentCall,
    ): AgentHttpResult = AgentHttpResult.notSentResult()

    /** 页面归属的请求才在这里取消；cancel 由应用级协调器持有、不登记页面归属（pageOwned=false），所以从不取消它。 */
    override fun cancelOwned(identityKey: Any) = Unit
}

private object TimeoutWiring {
    /** 「正常结束」用的两帧（合 schema 的 meta + done）。 */
    private const val DONE_TEXT =
        "event: meta\nid: 1\ndata: {\"session_id\":\"s\",\"run_id\":\"r\",\"message_id\":\"m\",\"prompt_version\":\"p\"," +
            "\"model_label\":\"m\",\"ai_label\":\"a\"}\n\nevent: done\nid: 2\ndata: {\"finish_reason\":\"stop\",\"quota_left\":1}\n\n"

    @Suppress("CyclomaticComplexMethod", "LongMethod") // 按分支逐条对照规则；步骤逐项
    fun of(input: JsonObject): JsonObject {
        val timer = AgentVirtualTimer()
        val port = ProbePort()
        var disconnectAt: Long? = null
        var reason: String? = null
        var client: StreamClient<Int>? = null
        val c =
            StreamClient(port, timer, 1) { _, e ->
                when {
                    e.kind == AgentStreamEventKind.TIMEOUT || e.kind == AgentStreamEventKind.ROUND_LIMIT -> {
                        disconnectAt = timer.now
                        reason = if (e.kind == AgentStreamEventKind.TIMEOUT) "idle" else "round_limit"
                    }

                    // 控制器收到终止帧即关闭本轮（closeActive）
                    e.kind == AgentStreamEventKind.FRAME && (e.frame?.event == AgentEvent.DONE || e.frame?.event == AgentEvent.ERROR) -> {
                        client?.cancel()
                    }
                }
            }
        client = c
        timer.now = AgentJson.intOf(input["requestAtMs"]) ?: 0L
        c.start("session", "{}", AgentCall("timeout", true, { true }, { true }))
        var headersSent = false

        fun headers(type: String) {
            port.sink?.onHeaders(HTTP_OK, mapOf("content-type" to type))
            headersSent = true
        }
        for (v in AgentJson.arrayOf(input["events"]).orEmpty()) {
            val e = AgentJson.objectOf(v) ?: AgentJson.EMPTY
            timer.advanceTo(AgentJson.intOf(e["atMs"]) ?: 0L)
            when (AgentJson.stringOf(e["kindRaw"])) {
                "headers" -> {
                    headers(AgentJson.stringOf(e["contentType"]) ?: EVENT_STREAM)
                }

                "bytes", "json_chunk" -> {
                    if (!headersSent) headers(EVENT_STREAM)
                    val text =
                        if (AgentJson.stringOf(e["kindRaw"]) ==
                            "json_chunk"
                        ) {
                            "{\"code\":"
                        } else {
                            AgentJson.stringOf(e["text"]).orEmpty()
                        }
                    port.sink?.onBytes(text.toByteArray(Charsets.UTF_8))
                }

                "done" -> {
                    if (!headersSent) headers(EVENT_STREAM)
                    port.sink?.onBytes(DONE_TEXT.toByteArray(Charsets.UTF_8))
                }

                "invalidate" -> {
                    c.cancel()
                }
                // background / foreground：流照常计时；断线后在后台不发起任何重试（控制器不自动重连、不自动拉历史）
            }
        }
        return AgentJson.obj(
            "disconnectAtMs" to disconnectAt,
            "reasonRaw" to reason,
            "close" to (disconnectAt != null),
            "retryVisible" to (disconnectAt != null),
            "automaticReconnects" to 0,
            "historyRequests" to 0,
            "activeTimers" to timer.pending,
        )
    }

    private const val HTTP_OK = 200
    private const val EVENT_STREAM = "text/event-stream"
}

/** 一轮在途的流 + 历史票据（协调器的作废对象）。 */
private class OwnershipProbe(
    private val hasRunId: Boolean,
    private val admitted: Boolean,
    val owner: String,
) : AgentOwnershipTarget {
    val port = ProbePort()
    private var ticket = 1
    var historyAccepted = false
    var mutations = 0
    var endedByTerminal = false
    val client: StreamClient<Int> =
        StreamClient(port, AgentVirtualTimer(), 1) { t, _ ->
            // 作废之后交来的事件（票据已变）：不改任何东西；交付次数由 StreamClient.delivered 计
            if (t != ticket) return@StreamClient
        }

    init {
        client.start("session", "{}", AgentCall(IDENTITY, false, { true }, { true }))
        port.sink?.onHeaders(HTTP_OK, mapOf("content-type" to "text/event-stream"))
        if (hasRunId) feed("event: meta\nid: 1\ndata: {}\n\n")
    }

    /** 作废前已交付的事件数（之后的变化才算「作废之后的交付」）。 */
    var deliveredAtStop = 0

    override fun stopForOwnership(event: String): AgentOwnedRun? {
        client.cancel()
        deliveredAtStop = client.delivered
        ticket += 1
        val allowed = admitted
        val call = AgentCall(IDENTITY, false, { allowed }, { allowed }, pageOwned = false)
        return AgentOwnedRun(if (hasRunId) RUN_ID else null, call, owner)
    }

    /** 隐藏期间的事件：流照常（帧到达、终止帧收尾），历史照常。 */
    fun hidden(kind: String) {
        if (kind == "done") endedByTerminal = true
    }

    fun late(kind: String) {
        val issued = 1
        if (kind == "history" || kind == "feedback" || kind == "report") {
            if (issued == ticket) {
                historyAccepted = true
                mutations += 1
            }
            return
        }
        val text =
            if (kind == "done") {
                "event: done\nid: 9\ndata: {\"finish_reason\":\"stop\",\"quota_left\":1}\n\n"
            } else {
                "event: $kind\nid: 2\ndata: {}\n\n"
            }
        feed(text)
    }

    private fun feed(text: String) {
        port.sink?.onBytes(text.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val IDENTITY = "probe"
        const val RUN_ID = "019a0000-0000-7000-8000-000000000121"
        const val HTTP_OK = 200
    }
}

private object OwnershipWiring {
    @Suppress("LongMethod") // 向量的观察投影逐项
    fun of(input: JsonObject): JsonObject {
        val event = AgentJson.stringOf(input["eventRaw"]).orEmpty()
        if (event == AgentOwnershipEvent.TAB_HIDDEN || event == AgentOwnershipEvent.PAGE_COVERED) return hidden(input)
        val probe =
            OwnershipProbe(
                AgentJson.boolOf(input["hasRunId"]) == true,
                AgentJson.boolOf(input["originalAdmitted"]) == true,
                AgentJson.stringOf(input["owner"]) ?: "A",
            )
        val gateWrites = mutableListOf<JsonElement>()
        var force = false
        var lock = false
        val handlers =
            AgentOwnershipHandlers(
                rateLimited = {
                    owner,
                    ms,
                    ->
                    gateWrites += AgentTransportErrorPolicy.gateWrite(AgentGateGroup.ACTION, owner.toString(), ms)
                },
                forceUpdate = { force = true },
                aiConsentLost = { lock = true },
            )
        val coordinator = AgentOwnershipStop(AgentCancelLedger(), handlers, CoroutineScope(Dispatchers.Unconfined))
        val decision = coordinator.before(event, probe)
        val synchronous = !probe.client.active
        val dispatch = coordinator.dispatch(decision, probe.port)
        val sent = dispatch !== AgentCancelDispatch.NONE
        // 页面实例销毁：只作废页面归属的请求；cancel 不挂页面归属，不被取消
        if (AgentJson.boolOf(input["pageDestroyedAfterSend"]) == true) probe.port.cancelOwned(OwnershipProbe.IDENTITY)
        // 作废之后旧连接上到达的字节、旧历史页、旧反馈 / 举报结果
        AgentJson.arrayOf(input["lateCallbacksRaw"]).orEmpty().forEach { probe.late(AgentJson.stringOf(it).orEmpty()) }
        AgentJson.objectOf(input["cancelResult"])?.let { res -> if (sent) coordinator.settle(AgentCancelPolicy.resultOf(res), probe.owner) }
        val o = linkedMapOf<String, JsonElement>()
        o["sendsCancel"] = JsonPrimitive(probe.port.cancels)
        o["cancelIdentityRaw"] = if (sent) JsonPrimitive("original") else JsonNull
        o["beforeOwnRequest"] = JsonPrimitive(sent)
        o["controllerSendsCancel"] = JsonPrimitive(0)
        o["localInvalidationSynchronous"] = JsonPrimitive(synchronous)
        o["lateReducerDeliveries"] = JsonPrimitive(probe.client.delivered - probe.deliveredAtStop)
        o["lateHistoryAccepted"] = JsonPrimitive(probe.historyAccepted)
        o["conversationMutations"] = JsonPrimitive(probe.mutations)
        o["quotaMutations"] = JsonPrimitive(0)
        o["analytics"] = JsonArray(emptyList())
        o["navigate"] = JsonPrimitive(false)
        o["showDialog"] = JsonPrimitive(false)
        o["retryCancel"] = JsonPrimitive(false)
        o["waitForCancelResult"] = JsonPrimitive(false)
        o["historyBackoffTimers"] = JsonPrimitive(0)
        o["historyReloads"] = JsonPrimitive(0)
        o["cancelOwnerRaw"] = JsonPrimitive("application")
        o["cancelledByPage"] = JsonPrimitive(probe.port.cancelledByPage)
        o["gateWrites"] = JsonArray(gateWrites)
        o["dispatchForceUpdate"] = JsonPrimitive(force)
        o["lockAiGate"] = JsonPrimitive(lock)
        return JsonObject(o)
    }

    /** 切 Tab / 同栈覆盖（G02）：流、计时、在途历史都保留；隐藏期间不更新界面，回来一次性应用。 */
    private fun hidden(input: JsonObject): JsonObject {
        val probe = OwnershipProbe(AgentJson.boolOf(input["hasRunId"]) == true, true, "A")
        val gate = AgentPublishGate()
        gate.setVisible(true)
        val before = gate.applied
        gate.setVisible(false)
        AgentJson.arrayOf(input["hiddenEventsRaw"]).orEmpty().forEach {
            probe.hidden(AgentJson.stringOf(it).orEmpty())
            gate.offer()
        }
        val hiddenApplied = gate.applied - before
        val visibleApply = if (AgentJson.boolOf(input["returnVisible"]) == true && gate.setVisible(true)) 1 else 0
        return AgentJson.obj(
            "sendsCancel" to probe.port.cancels,
            "connectionClosed" to (!probe.client.active && !probe.endedByTerminal),
            "historyCancelled" to false,
            "historyReloads" to 0,
            "hiddenUiUpdates" to hiddenApplied,
            "hiddenNavigation" to 0,
            "hiddenDialogs" to 0,
            "hiddenAnnouncements" to 0,
            "visibleApplyCount" to visibleApply,
            "duplicateExposureCount" to 0,
        )
    }
}
