package com.couli.app.agent

import com.couli.core.agent.AgentCancelLedger
import com.couli.core.agent.AgentConsentGate
import com.couli.core.agent.AgentConsentLapse
import com.couli.core.agent.AgentController
import com.couli.core.agent.AgentDeps
import com.couli.core.agent.AgentFeedbackValue
import com.couli.core.agent.AgentGates
import com.couli.core.agent.AgentIdentity
import com.couli.core.agent.AgentOperation
import com.couli.core.agent.AgentPhase
import com.couli.core.agent.AgentReport
import com.couli.core.agent.AgentSse
import com.couli.core.agent.AgentStatus
import com.couli.core.agent.AgentTimer
import com.couli.core.agent.ApiAgentContractPort
import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.InFlightCalls
import com.couli.core.network.LocalExchange
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.SessionScopeRaw
import com.couli.core.network.UpdateRaw
import com.couli.core.router.RouteRequest
import com.couli.core.wallet.WalletRateGate
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-10b Android 第 3 轮窄修 R3-01（S0；共性第 33、35、45 条）：同一 ownerKey 的页面实例销毁并重建时，旧页面发出的请求的 HTTP 结果已在 IO 线程处理完、正等待回到
 * 主线程——旧控制器已作废（`!alive()`），结果里的跨页面共享事实（AI 10004、42901）仍须按发起时不含页面实例的 ownerKey 结算：AI 10004 → 应用级失效恰 1 次并同步停下
 * 重建页的新流、锁同意闸门；42901 → 按三组闸门归属开启；重建页的消息、提示、导航不受旧结果影响。
 *
 * 管线是真实的：AgentController → ApiAgentContractPort → ApiClient → HttpTransport（真实 OkHttp 拦截链与写出前核对），只有传输最末端换成本地替身
 * （[LocalExchange]，与 Debug 假服务同一入口）。应用级接线按 AppAgent 原样组装（AgentGates.create 按此刻 ownerKey、AgentConsentLapse 以此刻控制器为目标、
 * 端口与控制器准入都核对 consentLapse）。旧页面与重建页的协程各自在一个手动排空的「主线程」队列上恢复，这样能确定地把旧回包停在调度窗口里。
 */
@Suppress("LargeClass", "TooManyFunctions")
class AgentFixR3RebuildSettleTest {
    /** 手动排空的主线程队列（只在测试线程上执行）。 */
    private class MainQueue : Executor {
        private val q = LinkedBlockingQueue<Runnable>()

        override fun execute(command: Runnable) {
            q.add(command)
        }

        val pending: Int get() = q.size

        /** 只执行此刻已排队的（之后才排进来的留在队列里：用来把回包停在调度窗口）。 */
        fun runPending() {
            repeat(q.size) { q.poll()?.run() }
        }

        fun drain() {
            while (true) {
                (q.poll() ?: return).run()
            }
        }
    }

    private enum class Op { CURRENT, HISTORY, CREATE, SEND, FEEDBACK, REPORT, CANCEL, OTHER }

    private val clock = 1_791_259_200_000L
    private val tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, "user-a")
    private val inFlight = InFlightCalls()

    /** 端口的主线程执行器（流式回调与登记释放）；旧 / 新页面的协程分别在 [q1] / [q2] 上恢复。 */
    private val portMain = MainQueue()
    private val q1 = MainQueue()
    private val q2 = MainQueue()

    // ---- 本地传输替身的脚本

    /** 这一类请求下一次回什么（一次性）；其余按常规回答。 */
    @Volatile private var failOp: Op? = null

    @Volatile private var failure: (Request) -> Response = { error("未设置") }

    @Volatile private var sessionNull = false

    /** 流式回答：false = 完整一轮（normal fixture）；true = 慢流（meta 先到，之后挂住直到调用被取消）。 */
    @Volatile private var slowStream = false
    private val requests = CopyOnWriteArrayList<Op>()

    /** 慢流已在传输层读取中（meta 已交出）/ 观察到调用被取消。 */
    private val slowStarted = CountDownLatch(1)
    private val slowCanceled = CountDownLatch(1)

    private val local =
        object : LocalExchange {
            override fun handles(request: Request): Boolean = request.url.encodedPath.startsWith("/v1/agent/")

            override fun answer(
                request: Request,
                canceled: () -> Boolean,
            ): Response {
                val op = opOf(request)
                requests += op
                if (op == failOp) {
                    failOp = null
                    return failure(request)
                }
                return when (op) {
                    Op.CURRENT -> {
                        json(request, if (sessionNull) """{"session":null}""" else """{"session":{"session_id":"s-1"}}""")
                    }

                    Op.HISTORY -> {
                        json(request, """{"items":[],"next_cursor":null,"expired":false}""")
                    }

                    Op.CREATE -> {
                        json(request, """{"session_id":"s-1"}""")
                    }

                    Op.SEND -> {
                        if (slowStream) slow(request, canceled) else sse(request, normalSse())
                    }

                    else -> {
                        json(request, "{}")
                    }
                }
            }
        }

    private val api =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
            baseUrl = { "http://agent.local.test/" },
            context = {
                NetworkContext(
                    PrivacyRaw.FULL,
                    environmentSettled = true,
                    credentialsReadable = true,
                    update = UpdateRaw.SUPPORTED,
                )
            },
            device =
                object : DeviceCredentials {
                    override val deviceId: String = "device-fixture-ABC123"
                    override val installSecret: String = "install-fixture"
                },
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { clock },
            inFlight = inFlight,
            sleeper = {},
            local = local,
        )

    // ---- 与 AppAgent 同一套应用级接线

    private var page = 1L
    private var current: AgentController? = null
    private val lapse = AgentConsentLapse()
    private val gates = AgentGates.create({ clock }, { identity().ownerKey })
    private val ledger = AgentCancelLedger()
    private val lapses = mutableListOf<Any>()
    private val opens = mutableListOf<RouteRequest>()
    private val toasts = mutableListOf<String>()
    private val port = ApiAgentContractPort(api, portMain, allowed = { !lapse.blocks(identity().ownerKey) })

    private val timer =
        object : AgentTimer {
            override fun after(
                delayMs: Long,
                block: () -> Unit,
            ): () -> Unit = {}
        }

    private fun identity(): AgentIdentity =
        AgentIdentity("test", AgentIdentity.MEMBER, "user-a", tokens.loginEpoch, inFlight.epochs(), 0, page)

    private fun build(queue: MainQueue): AgentController {
        val scope = CoroutineScope(queue.asCoroutineDispatcher())
        val id = identity()
        gates.retain(id.ownerKey)
        val c =
            AgentController(
                AgentDeps(
                    identity = id,
                    currentIdentity = ::identity,
                    contract = port,
                    gate = WalletRateGate(scope) { clock },
                    gates = gates,
                    ledger = ledger,
                    currentOwnerKey = { identity().ownerKey },
                    aiConsentLost = { owner ->
                        lapses += owner
                        lapse.lapse(owner, identity().ownerKey, current)
                    },
                    consentLapsed = lapse.blocks(id.ownerKey),
                    allowed = { !lapse.blocks(identity().ownerKey) },
                    scope = scope,
                    timer = timer,
                    clock = { clock },
                    open = { opens += it },
                    toast = { toasts += it },
                ),
            )
        current = c
        c.setVisible(true)
        c.start()
        return c
    }

    private fun pumpUntil(
        what: String,
        vararg queues: MainQueue,
        done: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (System.nanoTime() < deadline) {
            queues.forEach { it.drain() }
            if (done()) return
            Thread.sleep(POLL_MS)
        }
        error("等不到：$what")
    }

    /** 等旧请求的结果在 IO 线程处理完、排进主线程队列（不执行它）。 */
    private fun awaitQueued(queue: MainQueue) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (queue.pending == 0) {
            check(System.nanoTime() < deadline) { "旧回包没有排进主线程队列" }
            Thread.sleep(POLL_MS)
        }
    }

    /**
     * 旧页面的请求已发出（调用方已 [MainQueue.runPending] 启动它）→ 结果已排进主线程队列 → Activity 销毁（旧控制器作废）并以相同 ownerKey 重建 → 重建页开新一轮慢流 → 这时旧回包才回到主线程。返回重建后的控制器。
     */
    private fun rebuildThenDeliver(
        old: AgentController,
        oldQueue: MainQueue,
    ): AgentController {
        awaitQueued(oldQueue)
        old.dispose()
        page += 1
        assertEquals(old.boundOwnerKey(), identity().ownerKey)
        sessionNull = false
        slowStream = true
        val c2 = build(q2)
        // 旧回包在端口主线程队列里时（发送的 JSON 外壳），重建页准备期间不排空它
        val setup = if (oldQueue === portMain) arrayOf(q2) else arrayOf(q2, portMain)
        pumpUntil("重建页就绪", *setup) { c2.currentPhase == AgentPhase.READY }
        assertTrue(c2.send(c2.snapshot.value.page, "第二个问题"))
        assertTrue("重建页的新流已开始", slowStarted.await(WAIT_S, TimeUnit.SECONDS))
        // 旧回包这时才回到主线程（流式外壳在端口主线程队列里，HTTP 结果在旧页面的协程队列里）
        oldQueue.drain()
        q2.drain()
        portMain.drain()
        return c2
    }

    private fun assertAi10004Settled(c2: AgentController) {
        assertEquals("应用级失效恰 1 次", listOf<Any>(identity().ownerKey), lapses)
        assertTrue("重建页的新流被取消", slowCanceled.await(WAIT_S, TimeUnit.SECONDS))
        assertEquals(AgentConsentGate.REFUSED, c2.consentState)
        assertFalse(c2.canSend)
        assertNull("不弹同意框", c2.snapshot.value.consent)
        // 重建页的消息只有自己这一轮（旧结果不写进来），不导航、不提示
        assertEquals(
            listOf("第二个问题"),
            c2.snapshot.value.turns
                .map { it.userText },
        )
        assertTrue(opens.isEmpty())
        assertTrue(toasts.isEmpty())
        // 之后同归属请求一律不放行
        assertTrue(lapse.blocks(identity().ownerKey))
    }

    private fun assertRateLimitSettled(
        c2: AgentController,
        op: String,
    ) {
        assertTrue("$op 所属闸门开启", gates.of(op).active)
        assertTrue(lapses.isEmpty())
        assertEquals(AgentConsentGate.CLEAR, c2.consentState)
        assertEquals(1L, slowCanceled.count)
        val turn =
            c2.snapshot.value.turns
                .single()
        assertEquals("第二个问题", turn.userText)
        assertTrue(turn.status != AgentStatus.ERROR)
        assertTrue(opens.isEmpty())
        assertTrue(toasts.isEmpty())
    }

    // ---- 各 operation 的旧页面起点

    /** 旧页面取会话在途。 */
    private fun oldCurrent(): AgentController {
        val c1 = build(q1)
        q1.runPending()
        return c1
    }

    /** 旧页面取会话成功、历史首页在途。 */
    private fun oldHistory(): AgentController {
        val c1 = oldCurrent()
        awaitQueued(q1)
        // 处理取会话的结果（排进历史请求的协程），再启动它
        q1.runPending()
        q1.runPending()
        return c1
    }

    /** 旧页面没有会话、发第一条消息：建会话在途。 */
    private fun oldCreate(): AgentController {
        sessionNull = true
        val c1 = build(q1)
        pumpUntil("旧页面就绪", q1, portMain) { c1.currentPhase == AgentPhase.READY }
        assertTrue(c1.send(c1.snapshot.value.page, "帮我找纯牛奶"))
        q1.runPending()
        return c1
    }

    private fun oldReady(): AgentController {
        val c1 = build(q1)
        pumpUntil("旧页面就绪", q1, portMain) { c1.currentPhase == AgentPhase.READY }
        portMain.drain()
        return c1
    }

    private fun oldWithReply(): AgentController {
        val c1 = oldReady()
        assertTrue(c1.send(c1.snapshot.value.page, "帮我找纯牛奶"))
        pumpUntil("旧页面一轮结束", q1, portMain) {
            c1.snapshot.value.turns
                .singleOrNull()
                ?.actions != null
        }
        return c1
    }

    private fun fail(
        op: Op,
        code: Int,
    ) {
        failOp = op
        failure = { r ->
            if (code == AI_CONSENT) {
                json(r, """{"consent_type":"ai_third_party"}""", code = AI_CONSENT, status = HTTP_FORBIDDEN)
            } else {
                json(r, "{}", code = RATE_LIMITED, status = HTTP_TOO_MANY, retryAfter = "7")
            }
        }
    }

    // ---------------------------------------------------------------- 取会话

    @Test
    fun fixR3CurrentSessionAi10004AfterSameOwnerRebuildLapsesOnceAndStopsTheNewStream() {
        fail(Op.CURRENT, AI_CONSENT)
        val c2 = rebuildThenDeliver(oldCurrent(), q1)
        assertAi10004Settled(c2)
    }

    @Test
    fun fixR3CurrentSession42901AfterSameOwnerRebuildOpensTheReadGate() {
        fail(Op.CURRENT, RATE_LIMITED)
        val c2 = rebuildThenDeliver(oldCurrent(), q1)
        assertRateLimitSettled(c2, AgentOperation.CURRENT)
    }

    // ---------------------------------------------------------------- 历史首页

    @Test
    fun fixR3HistoryAi10004AfterSameOwnerRebuildLapsesOnceAndStopsTheNewStream() {
        fail(Op.HISTORY, AI_CONSENT)
        val c2 = rebuildThenDeliver(oldHistory(), q1)
        assertAi10004Settled(c2)
    }

    @Test
    fun fixR3History42901AfterSameOwnerRebuildOpensTheReadGate() {
        fail(Op.HISTORY, RATE_LIMITED)
        val c2 = rebuildThenDeliver(oldHistory(), q1)
        assertRateLimitSettled(c2, AgentOperation.HISTORY)
    }

    // ---------------------------------------------------------------- 建会话（发送第一条消息时）

    @Test
    fun fixR3CreateSessionAi10004AfterSameOwnerRebuildLapsesOnceAndStopsTheNewStream() {
        fail(Op.CREATE, AI_CONSENT)
        val c2 = rebuildThenDeliver(oldCreate(), q1)
        assertAi10004Settled(c2)
    }

    @Test
    fun fixR3CreateSession42901AfterSameOwnerRebuildOpensTheSendGate() {
        fail(Op.CREATE, RATE_LIMITED)
        val c2 = rebuildThenDeliver(oldCreate(), q1)
        assertRateLimitSettled(c2, AgentOperation.CREATE)
    }

    // ---------------------------------------------------------------- 发送（流式请求没成为流的 JSON 外壳）

    @Test
    fun fixR3SendJsonAi10004AfterSameOwnerRebuildLapsesOnceAndStopsTheNewStream() {
        val c1 = oldReady()
        fail(Op.SEND, AI_CONSENT)
        assertTrue(c1.send(c1.snapshot.value.page, "帮我找纯牛奶"))
        val c2 = rebuildThenDeliver(c1, portMain)
        assertAi10004Settled(c2)
    }

    @Test
    fun fixR3SendJson42901AfterSameOwnerRebuildOpensTheSendGate() {
        val c1 = oldReady()
        fail(Op.SEND, RATE_LIMITED)
        assertTrue(c1.send(c1.snapshot.value.page, "帮我找纯牛奶"))
        val c2 = rebuildThenDeliver(c1, portMain)
        assertRateLimitSettled(c2, AgentOperation.SEND)
    }

    // ---------------------------------------------------------------- 反馈 / 举报

    @Test
    fun fixR3FeedbackAi10004AfterSameOwnerRebuildLapsesOnceAndStopsTheNewStream() {
        val c1 = oldWithReply()
        fail(Op.FEEDBACK, AI_CONSENT)
        c1.feedback(
            c1.snapshot.value.turns
                .single()
                .actions!!
                .ticket,
            AgentFeedbackValue.UP,
        )
        q1.runPending()
        val c2 = rebuildThenDeliver(c1, q1)
        assertAi10004Settled(c2)
    }

    @Test
    fun fixR3Feedback42901AfterSameOwnerRebuildOpensTheActionGate() {
        val c1 = oldWithReply()
        fail(Op.FEEDBACK, RATE_LIMITED)
        c1.feedback(
            c1.snapshot.value.turns
                .single()
                .actions!!
                .ticket,
            AgentFeedbackValue.UP,
        )
        q1.runPending()
        val c2 = rebuildThenDeliver(c1, q1)
        assertRateLimitSettled(c2, AgentOperation.FEEDBACK)
    }

    @Test
    fun fixR3ReportAi10004AfterSameOwnerRebuildLapsesOnceAndStopsTheNewStream() {
        val c1 = oldWithReply()
        fail(Op.REPORT, AI_CONSENT)
        submitReport(c1)
        val c2 = rebuildThenDeliver(c1, q1)
        assertAi10004Settled(c2)
    }

    @Test
    fun fixR3Report42901AfterSameOwnerRebuildOpensTheActionGate() {
        val c1 = oldWithReply()
        fail(Op.REPORT, RATE_LIMITED)
        submitReport(c1)
        val c2 = rebuildThenDeliver(c1, q1)
        assertRateLimitSettled(c2, AgentOperation.REPORT)
    }

    /** 换身份（ownerKey 变了，这里是退出登录换了登录世代）后旧回包仍整体丢弃：不上报失效、不锁新归属。 */
    @Test
    fun fixR3OldAi10004AfterTheOwnerChangedIsStillDiscarded() {
        fail(Op.CURRENT, AI_CONSENT)
        val c1 = oldCurrent()
        awaitQueued(q1)
        c1.dispose()
        tokens.clear()
        assertTrue(c1.boundOwnerKey() != identity().ownerKey)
        q1.drain()
        assertTrue(lapses.isEmpty())
        assertFalse(lapse.blocks(identity().ownerKey))
    }

    @Test
    fun fixR3Old42901AfterTheOwnerChangedIsStillDiscarded() {
        fail(Op.CURRENT, RATE_LIMITED)
        val c1 = oldCurrent()
        awaitQueued(q1)
        c1.dispose()
        tokens.clear()
        q1.drain()
        assertFalse(gates.read.active)
    }

    private fun submitReport(c1: AgentController) {
        val ticket =
            c1.snapshot.value.turns
                .single()
                .actions!!
                .ticket
        c1.openReport(ticket)
        c1.selectReportReason(ticket, AgentReport.reasons().first())
        c1.submitReport(ticket)
        q1.runPending()
    }

    // ---- 应答

    private fun opOf(request: Request): Op {
        val path = request.url.encodedPath
        val get = request.method == "GET"
        return when {
            get && path == "/v1/agent/sessions/current" -> Op.CURRENT
            get && path.endsWith("/messages") -> Op.HISTORY
            !get && path == "/v1/agent/sessions" -> Op.CREATE
            !get && path.startsWith("/v1/agent/sessions/") && path.endsWith("/messages") -> Op.SEND
            path.endsWith("/feedback") -> Op.FEEDBACK
            path.endsWith("/report") -> Op.REPORT
            path.startsWith("/v1/agent/runs/") -> Op.CANCEL
            else -> Op.OTHER
        }
    }

    private fun json(
        request: Request,
        data: String,
        code: Int = 0,
        status: Int = HTTP_OK,
        retryAfter: String? = null,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("x")
            .header("Content-Type", "application/json")
            .header("X-Trace-Id", TRACE)
            .apply { retryAfter?.let { header("Retry-After", it) } }
            .body("""{"code":$code,"msg":"","data":$data,"trace_id":"$TRACE"}""".toResponseBody(JSON))
            .build()

    private fun sse(
        request: Request,
        text: String,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(HTTP_OK)
            .message("OK")
            .header("Content-Type", "text/event-stream; charset=utf-8")
            .header("X-Trace-Id", TRACE)
            .body(text.toResponseBody(SSE))
            .build()

    /** 慢流：meta 先到，之后挂住直到这次调用被取消（被取消即记下并结束）。 */
    private fun slow(
        request: Request,
        canceled: () -> Boolean,
    ): Response {
        val head = AgentSse.sseTextOfNdjsonLine(fixtureLines().first()).toByteArray()
        val source =
            object : Source {
                private var sent = false

                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    if (!sent) {
                        sent = true
                        sink.write(head)
                        return head.size.toLong()
                    }
                    // meta 已交出、传输层正在读后续字节：新流确实在途
                    slowStarted.countDown()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S * 2)
                    while (!canceled()) {
                        if (System.nanoTime() > deadline) return -1
                        Thread.sleep(POLL_MS)
                    }
                    slowCanceled.countDown()
                    throw IOException("canceled")
                }

                override fun timeout(): Timeout = Timeout.NONE

                override fun close() = Unit
            }
        val body =
            object : ResponseBody() {
                override fun contentType(): MediaType = SSE

                override fun contentLength(): Long = -1

                override fun source(): BufferedSource = source.buffer()
            }
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(HTTP_OK)
            .message("OK")
            .header("Content-Type", "text/event-stream; charset=utf-8")
            .header("X-Trace-Id", TRACE)
            .body(body)
            .build()
    }

    private fun normalSse(): String = fixtureLines().joinToString("") { AgentSse.sseTextOfNdjsonLine(it) }

    private fun fixtureLines(): List<String> {
        val start = File(System.getProperty("user.dir") ?: error("缺少 user.dir")).canonicalFile
        val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        return File(
            root,
            "contracts/snapshot/contracts/fixtures/agent-streams/normal.ndjson",
        ).readText().split('\n').filter { it.isNotBlank() }
    }

    private companion object {
        const val WAIT_S = 5L
        const val POLL_MS = 5L
        const val AI_CONSENT = 10004
        const val RATE_LIMITED = 42901
        const val HTTP_OK = 200
        const val HTTP_FORBIDDEN = 403
        const val HTTP_TOO_MANY = 429
        const val TRACE = "0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a6b"
        val JSON = "application/json".toMediaType()
        val SSE = "text/event-stream".toMediaType()
    }
}
