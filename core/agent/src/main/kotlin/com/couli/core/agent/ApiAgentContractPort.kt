package com.couli.core.agent

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.ApiStreamHandle
import com.couli.core.network.ApiStreamRequest
import com.couli.core.network.ApiStreamSink
import com.couli.core.network.CallOwnership
import java.net.URLEncoder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject

/**
 * APP-10b §2 第 3 条（F-X-04）：真实端口——七个 agent operation 全部经 [ApiClient]（公共头、可选 Authorization、不签名、调用归属、每次实际写出前的
 * admit / sendAllowed、单飞刷新、10402、10405、撤回 / 切环境的同步取消；凭据只经 ApiClient 在 CredentialReadBarrier 里读，端口自己不读令牌、设备号或
 * install_secret，G43）。Debug / Release 同一份（放 main 源码集）；Debug / Staging 的本机假服务在 ApiClient 的实际传输入口按 DebugMockGate 换入。
 *
 * - **归属**：[AgentCall.identityKey]（控制器的固定身份）→ [CallOwnership]（隐私 / 环境世代取自固定身份；[CallOwnership.stillOwned] = 端口没作废它 且
 *   [AgentCall.owner]；[CallOwnership.sendAllowed] = 应用级准入 [allowed]（身份变更在途、AI 同意失效、配置关闭）且 [AgentCall.sendAllowed]）。会员的请求另带
 *   固定账号（[ApiCall.expectedAccount]，账号变了不读凭据、不发送）。页面归属的请求按 identityKey 登记，[cancelOwned] / [cancelOwnedExcept] / [cancelScope] 同步作废：
 *   归属核对立即失效、在途 OkHttp Call 立即取消（[ApiClient.cancelOwned]）、流的句柄立即关闭。
 * - **不挂页面归属**（[AgentCall.pageOwned] = false：【停止】与身份事件的 cancel）：不登记、不受控制器作废影响，也不经应用级准入（捕获的那一次 cancel 在身份变更
 *   在途时照发，鸿蒙 R1-03）；[AgentCall.singleShot] 为单次发送（不刷新、不重注册、不重放、不导航）。
 * - **流**：sendAgentMessage 经 [ApiClient.stream]（OkHttp ResponseBody.source() 在 IO 线程逐块读）；字节按到达顺序切回 [main] 交给 [AgentStreamSink]，
 *   句柄已关闭的一律丢弃（[delivered] / [dropped] 供抓包断言）。
 * 端口方法只在主线程调用；挂起函数在调用方的协程上下文里恢复。
 */
@Suppress("TooManyFunctions") // 一处集中
class ApiAgentContractPort(
    private val client: ApiClient,
    /** 主线程执行器（流式回调切回主线程，保持到达顺序）。 */
    private val main: Executor,
    /** 应用级准入（每次实际写出前核对；线程安全、不阻塞）。 */
    private val allowed: () -> Boolean = { true },
    private val log: (String) -> Unit = {},
) : AgentContractPort {
    /** 页面归属的在途请求（identityKey → 登记项）。只在主线程改。 */
    private val owned = mutableMapOf<Any, MutableList<Entry>>()

    /** 流式回调切回主线程后交付的块数 / 句柄关闭后丢弃的块数（抓包断言用）。 */
    var delivered: Int = 0
        private set
    var dropped: Int = 0
        private set

    private class Entry(
        val scope: String,
        val ownership: CallOwnership,
        val revoked: AtomicBoolean,
        var stream: AgentStream? = null,
    )

    override suspend fun createSession(call: AgentCall): AgentHttpResult = http(ApiCall(AgentOperation.CREATE), call)

    override suspend fun currentSession(call: AgentCall): AgentHttpResult = http(ApiCall(AgentOperation.CURRENT), call)

    override suspend fun cancelRun(
        runId: String,
        call: AgentCall,
    ): AgentHttpResult = http(ApiCall(AgentOperation.CANCEL, pathParams = mapOf(PARAM_RUN_ID to runId), singleShot = call.singleShot), call)

    override suspend fun listMessages(
        sessionId: String,
        cursor: String?,
        limit: Int,
        call: AgentCall,
        autoRetry: Boolean,
    ): AgentHttpResult {
        // G08：limit 显式传（1–50，契约缺省 20）；游标原样（不透明，≤512）按 URL 编码
        val query = "limit=$limit" + cursor?.let { "&cursor=" + URLEncoder.encode(it, Charsets.UTF_8.name()) }.orEmpty()
        return http(ApiCall(AgentOperation.HISTORY, pathParams = mapOf(PARAM_ID to sessionId), query = query, autoRetry = autoRetry), call)
    }

    override suspend fun setFeedback(
        messageId: String,
        value: String,
        call: AgentCall,
    ): AgentHttpResult =
        http(
            ApiCall(
                AgentOperation.FEEDBACK,
                pathParams = mapOf(PARAM_MESSAGE_ID to messageId),
                body = AgentFeedback.body(value).toString(),
            ),
            call,
        )

    override suspend fun report(
        messageId: String,
        reason: String,
        call: AgentCall,
    ): AgentHttpResult =
        http(
            ApiCall(AgentOperation.REPORT, pathParams = mapOf(PARAM_MESSAGE_ID to messageId), body = AgentReport.body(reason).toString()),
            call,
        )

    override fun sendMessage(
        sessionId: String,
        body: String,
        call: AgentCall,
        sink: AgentStreamSink,
    ): AgentStreamHandle {
        val entry = entry(call)
        val stream = AgentStream(sink)
        entry?.stream = stream
        val handle =
            client.stream(
                ApiStreamRequest(
                    AgentOperation.SEND,
                    mapOf(PARAM_ID to sessionId),
                    body,
                    ownership(call, entry?.revoked),
                    expectedAccount(call),
                ),
                stream,
            )
        stream.bind(handle) { entry?.let(::release) }
        return stream
    }

    override fun cancelOwned(identityKey: Any) = cancelWhere(identityKey) { true }

    override fun cancelOwnedExcept(
        identityKey: Any,
        keepScope: String,
    ) = cancelWhere(identityKey) { it.scope != keepScope }

    override fun cancelScope(
        identityKey: Any,
        scope: String,
    ) = cancelWhere(identityKey) { it.scope == scope }

    private fun cancelWhere(
        identityKey: Any,
        which: (Entry) -> Boolean,
    ) {
        val entries = owned[identityKey] ?: return
        val hit = entries.filter(which)
        entries.removeAll(hit)
        if (entries.isEmpty()) owned -= identityKey
        // 先让归属核对失效，再同步取消在途的 Call 与流（之后的重试、续发、交付都由归属核对结束）
        hit.forEach { e ->
            e.revoked.set(true)
            e.stream?.cancel()
            client.cancelOwned(e.ownership)
        }
    }

    private suspend fun http(
        api: ApiCall,
        call: AgentCall,
    ): AgentHttpResult {
        val entry = entry(call)
        val ownership = entry?.ownership ?: ownership(call, null)
        // 不挂页面归属的应用级 cancel：把同步取消这次网络调用交给协调器（等写出超时后撤销，R2-03）
        call.onAbortable?.let { bind -> runCatching { bind { client.cancelOwned(ownership) } } }
        return try {
            client
                .send(
                    api.copy(ownership = ownership, expectedAccount = expectedAccount(call)),
                ).toAgentResult()
        } finally {
            entry?.let(::release)
        }
    }

    /** 页面归属的请求登记一项（不挂页面归属的返回 null）。 */
    private fun entry(call: AgentCall): Entry? {
        if (!call.pageOwned) return null
        val revoked = AtomicBoolean(false)
        val e = Entry(call.scope, ownership(call, revoked), revoked)
        owned.getOrPut(call.identityKey) { mutableListOf() } += e
        return e
    }

    private fun release(e: Entry) {
        main.execute {
            owned.entries.firstOrNull { it.value.remove(e) }?.let { if (it.value.isEmpty()) owned -= it.key }
        }
    }

    private fun ownership(
        call: AgentCall,
        revoked: AtomicBoolean?,
    ): CallOwnership {
        val identity = call.identityKey as? AgentIdentity
        val epochs = identity?.epochs ?: client.inFlight.epochs()
        // 页面归属的请求另过应用级准入；不挂页面归属的 cancel 只认原身份（AgentCall.sendAllowed 已核对原身份世代与动作闸门）
        val gate: () -> Boolean = if (call.pageOwned) ({ runCatching(allowed).getOrDefault(false) }) else ({ true })
        return CallOwnership(
            epochs = epochs,
            sendAllowed = { gate() && runCatching(call.sendAllowed).getOrDefault(false) },
            onWritten = call.onWritten,
        ) { revoked?.get() != true && runCatching(call.owner).getOrDefault(false) }
    }

    /** 会员的请求带固定账号（账号变了不读凭据、不发送）；游客、基本模式为 null。 */
    private fun expectedAccount(call: AgentCall): String? = (call.identityKey as? AgentIdentity)?.takeIf { it.member }?.account

    /** 一条流：IO 线程的回调按顺序切回主线程，句柄关闭后一律丢弃。 */
    private inner class AgentStream(
        private val sink: AgentStreamSink,
    ) : AgentStreamHandle,
        ApiStreamSink {
        private val closed = AtomicBoolean(false)
        private var api: ApiStreamHandle? = null
        private var done: () -> Unit = {}

        fun bind(
            handle: ApiStreamHandle,
            onDone: () -> Unit,
        ) {
            api = handle
            done = onDone
            if (closed.get()) handle.cancel()
        }

        override fun cancel() {
            if (!closed.compareAndSet(false, true)) return
            api?.cancel()
            done()
        }

        private fun post(
            terminal: Boolean,
            late: (() -> Unit)? = null,
            block: () -> Unit,
        ) {
            main.execute {
                if (closed.get()) {
                    dropped += 1
                    // 句柄关闭后才回到主线程的 JSON 外壳：仍交出去结算跨页面共享事实（R3-01），不当作这条流的结果
                    late?.invoke()
                    return@execute
                }
                delivered += 1
                if (terminal) {
                    closed.set(true)
                    done()
                }
                block()
            }
        }

        override fun onHeaders(
            status: Int,
            headers: Map<String, String>,
        ) = post(false) { sink.onHeaders(status, headers) }

        override fun onBytes(chunk: ByteArray) = post(false) { sink.onBytes(chunk) }

        override fun onEnd() = post(true) { sink.onEnd() }

        override fun onFailure(kind: String) {
            log("agent stream failed $kind")
            post(true) { sink.onFailure() }
        }

        override fun onEnvelope(result: ApiResult) {
            val r = result.toAgentResult()
            post(true, late = { sink.onLateEnvelope(r) }) { sink.onEnvelope(r) }
        }
    }

    companion object {
        private const val PARAM_ID = "id"
        private const val PARAM_RUN_ID = "run_id"
        private const val PARAM_MESSAGE_ID = "message_id"
    }
}

/** ApiClient 的结果 → Agent 端口的结果（保留错误码、trace_id、data、Retry-After、本地拒绝）。 */
fun ApiResult.toAgentResult(): AgentHttpResult =
    when (this) {
        is ApiResult.Success -> {
            AgentHttpResult(true, HTTP_OK, 0, "", (data as? JsonObject) ?: AgentJson.EMPTY, traceId.orEmpty(), null)
        }

        is ApiResult.Failure -> {
            val ms = effect.disableMillis
            val retryAfter = if (code == AgentErrorCodes.RATE_LIMITED && ms != null && ms > 0) ((ms + MS - 1) / MS).toString() else null
            AgentHttpResult(false, 0, code, msg, data, traceId.orEmpty(), retryAfter)
        }

        // 本地拒绝、刷新屏障失败（原请求没有发出）：notSent
        is ApiResult.LocalReject, is ApiResult.SessionEnded -> {
            AgentHttpResult.notSentResult()
        }

        else -> {
            AgentHttpResult.network()
        }
    }

private const val HTTP_OK = 200
private const val MS = 1_000L
