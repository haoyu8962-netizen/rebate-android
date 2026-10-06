package com.couli.core.network

import com.couli.core.generated.ApiOperation
import com.couli.core.generated.ApiOperations
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient

/**
 * 请求管线（03 §4.2「中间件顺序」）：公共头 → 签名（仅 x-signed）→ 发送（10 秒超时；读接口超时 / 断连重试 1 次）→
 * 解包 `{code, msg, data}` → [ErrorActionMapper]。
 *
 * - 本地放行与剥离（[RequestHeaderPolicy]）：环境未就绪不发；基本模式与撤回清理未完成时不读不带凭据，只放行白名单；
 *   受限会话（deletion_only）只发受限会话表里的接口（按请求体才接受的看请求体）。
 * - **调用归属**（A-R2-01..05、lessons 14、22–26、30）：一次逻辑调用开始时（任何屏障等待之前）绑定 [CallOwner]：账号、会话世代、
 *   隐私世代、环境世代。所有自动续发——GET 超时 / 断连重试、10002 刷新后重放、10402 重新注册后重放、10401 校时重签、
 *   确认未决键的重发——以及每次真正写出请求之前（网络拦截器），都走同一个 [ownerReject] 核对：世代变了（撤回同意、切换环境）
 *   作废；账号变了、会话换代了（退出、重新登录、切换账号）结束旧调用；只有同一会话里经确认的令牌刷新（刷新链）可以继续。
 *   每次实际发送都重新取状态、重新判定放行、重新组头与签名（R1-01）。在途的 OkHttp Call 登记在 [InFlightCalls]，撤回同意、
 *   切换环境时立即取消。底层 OkHttp 不重发、不跟随重定向（[HttpTransport]，R1-03、R1-05）。
 * - 10002：令牌单飞刷新（[RefreshCoordinator]）。检查本次所用令牌时一次取得完整会话快照，这份快照就是刷新对象，直接交给刷新任务
 *   （R3-01、A-R2-03）；刷新窗口按会话世代隔离（lessons 36）；登录保存、刷新保存与清会话都按世代 CAS，并与刷新入队同在
 *   refreshLock 下。成功后沿用原键、原请求体重放 1 次；被拒清会话并发出跳登录 Effect（强更状态下停在强更页）；超时、断连等暂时失败不清会话，原调用按网络错误结束
 *   （lessons 31）。刷新结果只在会话世代未变、凭据仍可读、环境未变时写入（R1-07）。
 * - 受限会话屏障（R1-10、lessons 19、31）：冷启动时存储的会话是 deletion_only，或强更状态从 below / unknown 变为受支持
 *   （[onUpdateStateChanged] 一收到就发起）时，先单飞刷新；其余带令牌的请求等它结束。被拒结束原调用，输出清会话的 Effect；
 *   暂时失败按网络错误结束、屏障重新到期，不带旧受限令牌发出。
 * - 10405：确需强更的调 [NetworkHooks.onForceUpdate]（Update.on10405）。登录 / 刷新成功时记下 session_scope；按处理响应那一刻的
 *   强更状态（A-R2-07），未知期间拿到 deletion_only 按「低于」处理（调 onForceUpdate）。登录响应只在请求发出时那一代会话、隐私与
 *   环境都未变时写入（A-R2-02）。
 * - 10401：响应头 Date 与本机时钟相差超过 300 秒时校正偏移，用新的 X-Timestamp、X-Nonce 重签再发 1 次（BR-ID-09）。校时重签单独
 *   计数，不占业务重放（R3-06）；单飞刷新请求自身遇到也校时重签 1 次，仍在同一个窗口里（R3-07）。
 * - 失败响应的 Effect 都经统一出口 [deliver]：调用归属已失效的旧响应不交付会话类副作用、不登记 pending_action（R3-03）。
 * - 登录：令牌保存成功才算登录成功，否则 [ApiResult.SessionNotSaved]（lessons 40）。
 * - 四个 step-up 操作：账号取自会话，取不到本地拒绝；未决记录写入成功才发送（R1-11）。未决键由整次逻辑调用占用（绑定调用标识），
 *   内部续发期间不释放，整次调用结束才结算（A-R2-05）。[confirmPending] / [abandonPending] 由用户点击触发；作废接口返回
 *   completed 时按原操作的结果给出业务结果与 Effect，abandoned 给 [ApiResult.Abandoned]（A-R2-06）。
 *
 * [execute] 是阻塞调用，不在主线程用；协程里用 [send]。
 */
@Suppress("TooManyFunctions", "LongParameterList", "ReturnCount", "LargeClass") // 管线各步与未决键入口集中在一处；依赖逐项注入（单测替身）；各步逐项提前返回
class ApiClient(
    private val identity: ClientIdentity,
    private val baseUrl: () -> String?,
    private val context: () -> NetworkContext,
    private val device: DeviceCredentials,
    private val tokens: SessionTokens,
    private val pendingKeys: PendingKeyStore,
    private val hooks: NetworkHooks = NetworkHooks(),
    http: OkHttpClient = OkHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = Thread::sleep,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val nonce: () -> String = RequestSigner::nonce,
    private val retryPolicy: (ApiOperation) -> RetryDecision = RetryPolicy::forOperation,
    /** 在途调用登记（App 里与撤回同意、环境切换清理共用一份）。 */
    val inFlight: InFlightCalls = InFlightCalls(),
) {
    private val transport = HttpTransport(http, inFlight)
    private val refreshExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "couli-token-refresh").apply { isDaemon = true } }
    private val waiters = ConcurrentHashMap<String, CompletableFuture<WaitSignal>>()

    /** 单飞刷新的同步区：令牌检查、复用已完成结果、等待者入队、窗口结算、刷新链记录都在这把锁下（R1-06）。 */
    private val refreshLock = Any()

    /**
     * 刷新链：刷新前的 access_token → 新令牌（被拒为 null）。同一旧令牌晚到的 10002 直接复用（R1-06）；调用归属核对沿它判断
     * 会话换代是不是同一会话里的令牌刷新。暂时失败不记（之后的 10002 可以再刷新）。
     */
    private val refreshOutcomes =
        object : LinkedHashMap<String, RefreshOutcome>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RefreshOutcome>?): Boolean = size > OUTCOME_MEMORY
        }

    /** 正在结算的刷新开始时的会话世代：刷新被拒只清这一代的会话（R1-06、R1-07）。只在 [refreshLock] 下读写。 */
    private var settlingGeneration: Long? = null

    /** 正在结算的刷新是暂时失败（超时、断连、被取消）时为其 kind：不清会话，等待者按网络错误结束（lessons 31）。 */
    private var settlingTransient: String? = null

    /** 正在结算的刷新开始时的 access_token（被拒清会话后记下「本世代因刷新失败被清除」，lessons 37）。只在 [refreshLock] 下读写。 */
    private var settlingAccess: String? = null

    /**
     * 入队方在检查令牌的同一同步区里取得的完整会话快照（R3-01）：需要发起刷新时 [refreshCoordinator] 的 refresh 回调同步取走它，
     * 不再自己读取「当前会话」。只在 [refreshLock] 下读写。
     */
    private var nextRefreshStart: SessionSnapshot? = null

    /** 在途刷新窗口的刷新对象（会话快照）：窗口按会话世代隔离，别的世代不加入它（lessons 36）。只在 [refreshLock] 下读写。 */
    private var windowStart: SessionSnapshot? = null

    /** 受限会话屏障（R1-10）。只在 [barrierLock] 下读写；锁顺序 barrierLock → refreshLock。 */
    private val barrierLock = Any()
    private var barrierDue = true
    private var lastObservedUpdate: String? = null
    private var barrier: CompletableFuture<WaitSignal>? = null

    /** 清会话写盘失败：在清除成功之前一律按没有会话处理（不读不带令牌，R1-08）。 */
    @Volatile
    private var sessionQuarantined = false

    /**
     * 被隔离的那一代会话（清会话写盘失败后存储的世代）：隔离重试只按这一代 CAS 清除，会话已换代（新登录、别处已清）就停止清理、
     * 解除隔离，不误删之后的新会话（R4-01）。只在 [refreshLock] 下读写。
     */
    private var quarantinedGeneration: Long? = null

    /** 测试钩子：隔离重试取得 [refreshLock] 之前调用（控制两个重试与新登录的交错，R4-01）。 */
    internal var beforeQuarantineRetry: () -> Unit = {}

    /** 服务端时间 − 本机时间（毫秒），10401 校时后更新。 */
    @Volatile
    private var clockOffsetMillis = 0L

    /**
     * 一次刷新的结论：[token] 新令牌（被拒为 null）；[cleared] = 被拒后确实清掉了那一代会话（本世代因刷新失败被清除，
     * 晚到的 10002 复用它、返回会话已清；不是这种情况的「会话没了」按会话已换代处理，lessons 37）。
     */
    private class RefreshOutcome(
        val token: String?,
        val cleared: Boolean = false,
    )

    /** 等待单飞刷新的结论（等待者的 future）。 */
    private sealed interface WaitSignal {
        data class Token(
            val token: String,
        ) : WaitSignal

        /** 刷新被拒（会话已清）。 */
        data object Failed : WaitSignal

        /** 超时、断连、被取消：不清会话，等待者按网络错误结束。 */
        data class Transient(
            val kind: String,
        ) : WaitSignal
    }

    /** 一次刷新请求的结果（刷新线程上）。 */
    private sealed interface RefreshAttempt {
        data class Token(
            val token: String,
        ) : RefreshAttempt

        data object Rejected : RefreshAttempt

        data class Transient(
            val kind: String,
        ) : RefreshAttempt
    }

    /**
     * 调用归属（A-R2-01..05）：逻辑调用开始时（屏障等待之前，lessons 30）绑定。
     * @property epochs 隐私世代、环境世代。
     * @property generation 会话存储的世代（没有会话也有世代）；凭据不可读时为 null。
     * @property session 开始时可读的会话（账号、令牌、世代）；没有为 null。
     */
    private class CallOwner(
        val epochs: CallEpochs,
        val generation: Long?,
        val session: SessionSnapshot?,
    )

    /**
     * 一次逻辑调用：[owner] 归属；[holder] 占用未决键的调用标识；[pendingKey] 已登记的未决键（四个 step-up 操作）。
     */
    private class Logical(
        val owner: CallOwner,
        val holder: String,
        var pendingKey: String? = null,
        /** [pendingKey] 所属的账号（登记时取自可信会话，R3-04：查询状态按它，不用 Store 里可能过期的当前账号）。 */
        var pendingAccount: String? = null,
    )

    /**
     * 一次调用的选项。
     * @property abandonFor 这是放弃未决键的作废请求，原敏感操作（R1-14）。
     * @property pendingAccount 确认 / 放弃未决键：记录所属的账号；发出前当前会话账号必须仍是它（lessons 15）。
     */
    private data class RunOptions(
        val abandonFor: ApiOperation? = null,
        val pendingAccount: String? = null,
    )

    /** 10002 / 只刷新时等单飞刷新的结论。 */
    private sealed interface RefreshResult {
        /** 本次所用令牌所在的那一代会话已刷新，[token] 仍是当前令牌：沿用原键重放。 */
        data class Replay(
            val token: String,
        ) : RefreshResult

        /** 刷新被拒（会话已清）：清会话后跳登录，强更状态下停在强更页。 */
        data object Failed : RefreshResult

        /** 会话已换代（重新登录、切换账号）：不重放、不再刷新、不清新会话。 */
        data object Stale : RefreshResult

        /** 刷新超时、断连或被取消：会话不动，原调用按网络错误结束（lessons 31）。 */
        data class Transient(
            val kind: String,
        ) : RefreshResult
    }

    /**
     * 一次发送之后：结束（[Done]）或内部续发，都沿用原键、原请求体并保持未决占用：[Replay] 是业务重放（10002 刷新后、10402
     * 重新注册后，至多 1 次）；[Resign] 是 10401 校时后的重签（至多 1 次），单独计数，不占业务重放（R3-06、BR-ID-09）。
     */
    private sealed interface Step {
        data class Done(
            val result: ApiResult,
        ) : Step

        data object Replay : Step

        data object Resign : Step
    }

    /** 令牌单飞刷新（测试可观察 refreshCalls）。 */
    val refreshCoordinator =
        RefreshCoordinator(
            // 刷新对象是入队方检查令牌时取得的那份快照（R3-01、A-R2-03）：这里只取走它，不再读取当前会话；
            // 本回调由入队方在 refreshLock 下同步调用
            refresh = { completion ->
                val start = nextRefreshStart
                nextRefreshStart = null
                windowStart = start
                val epochs = inFlight.epochs()
                refreshExecutor.execute { finishRefresh(start, epochs, completion) }
            },
            replay = { request, token -> waiters.remove(request.id)?.complete(WaitSignal.Token(token)) },
            clearSession = { clearSettlingSession() },
            clock = clock,
            onFailure = { requests ->
                val signal = failureSignal()
                requests.forEach { waiters.remove(it.id)?.complete(signal) }
            },
            // 失败后停在强更页还是跳登录：按结算那一刻的强更状态（lessons 20）
            forceUpdateNow = { context().update == UpdateRaw.BELOW },
        )

    suspend fun send(call: ApiCall): ApiResult = withContext(Dispatchers.IO) { execute(call) }

    /** 阻塞执行一次调用。 */
    fun execute(call: ApiCall): ApiResult {
        val operation = requireNotNull(ApiOperations.byId(call.operationId)) { "未知 operationId：${call.operationId}" }
        return runLogical(call, operation, RunOptions(), newId())
    }

    /** 撤回同意（清理的第一步）：作废并取消全部在途请求，之后的内部续发不再发出（A-R2-01）。 */
    fun onPrivacyRevoked() = inFlight.onPrivacyRevoked()

    /** 切换环境（清理的第一步）：作废并取消全部在途请求（A-R2-01）。 */
    fun onEnvironmentSwitching() = inFlight.onEnvironmentSwitching()

    /**
     * 冷启动、登录成功、切回前台：按当前会话的账号恢复未决记录（只读，不发）。本进程仍在执行的请求保持在途（R1-15）。
     * 没有可读的会话账号返回 false。
     */
    fun restorePending(): Boolean {
        val account = session(context())?.userId ?: return false
        pendingKeys.coldStart(account)
        return true
    }

    /**
     * 当前会话账号某个 step-up 操作的未决记录（发起页据此进入待确认）。账号取自可信会话、在读取之前确定，只读这个账号的记录
     * （R3-04、lessons 15）；没有会话为 null。存储读不出（解不开、损坏）抛 [UnreadableRecordException]，不当成没有记录（lessons 38）。
     */
    fun pendingFor(operationId: String): PendingKeyRecord? {
        val account = session(context())?.userId ?: return null
        return pendingKeys.pendingFor(operationId, account)
    }

    /**
     * 强更状态变化（AppModule 接 AppUpdate 的 Router 状态源）：由「低于」或「未知」变为受支持时屏障到期，并立即发起受限会话的
     * 刷新（R1-10、lessons 19、31），不等下一个请求。请求发出前也会按当时的 [NetworkContext.update] 观察一次。
     */
    fun onUpdateStateChanged(update: String) {
        synchronized(barrierLock) {
            observeUpdate(update)
            armBarrier(context())
        }
    }

    /**
     * 用户点【确认】：用原键、原请求体重发（只在结果未知时接受）。结果未知后遇到 10003：完成二次验证后带新的 [stepUpToken]
     * 再调本入口，仍沿用原键与原请求体（R1-12）。本地拒绝或发出前异常时记录退回待确认（R1-13）。键由这次确认的逻辑调用
     * 占用到结算（A-R2-05）；置为在途写盘失败返回 pending_store_failed，记录保持待确认（lessons 32）。
     */
    fun confirmPending(
        operationId: String,
        stepUpToken: String? = null,
    ): ApiResult {
        val record =
            when (val owned = ownedPending(operationId)) {
                is Owned.Record -> owned.record
                is Owned.Rejected -> return ApiResult.LocalReject(owned.reason)
            }
        val operation = ApiOperations.byId(operationId) ?: return ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED)
        val holder = newId()
        when (startUserAction { pendingKeys.confirm(record.key, holder, record.account) }) {
            null -> return ApiResult.LocalReject(LocalRejectReason.PENDING_STORE_FAILED)
            false -> return ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED)
            true -> Unit
        }
        val call =
            ApiCall(operationId, body = record.body, idempotencyKey = record.key, stepUpToken = stepUpToken, summary = record.summary)
        try {
            return runLogical(call, operation, RunOptions(pendingAccount = record.account), holder)
        } finally {
            // 没拿到结果就结束的（本地拒绝、会话结束、账号不符等）：在途占用退回待确认；已由响应结算的不动（R1-13、lessons 18）
            pendingKeys.release(record.key, holder)
        }
    }

    /**
     * 用户点【放弃】（二次确认之后）：调 `POST /v1/idempotency-keys/abandon`。abandoned → 键结束，返回 [ApiResult.Abandoned]；
     * completed → 按原结果结束，并按原操作给出业务结果：原 code 0 为 [ApiResult.Success]（data 为原响应的 data），3xxxx 为
     * [ApiResult.Failure]（Effect 按原操作映射，A-R2-06）；没有外壳或其他码（如 40901）→ 保持待确认。10001 / 10004 / 10005 等
     * 前置码只引导，不登记 pending_action、不自动重放，键保持待确认，由用户补办后再点（R1-14）。
     */
    fun abandonPending(operationId: String): ApiResult {
        val record =
            when (val owned = ownedPending(operationId)) {
                is Owned.Record -> owned.record
                is Owned.Rejected -> return ApiResult.LocalReject(owned.reason)
            }
        val original = ApiOperations.byId(operationId) ?: return ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED)
        val action = original.stepUp ?: return ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED)
        val abandon = ApiOperations.byId(ABANDON) ?: return ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED)
        val holder = newId()
        when (startUserAction { pendingKeys.abandon(record.key, holder, record.account) }) {
            null -> return ApiResult.LocalReject(LocalRejectReason.PENDING_STORE_FAILED)
            false -> return ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED)
            true -> Unit
        }
        val body = JsonObject(mapOf("action" to JsonPrimitive(action), "idempotency_key" to JsonPrimitive(record.key))).toString()
        val result =
            try {
                val options = RunOptions(abandonFor = original, pendingAccount = record.account)
                runLogical(ApiCall(ABANDON, body = body), abandon, options, newId()).also { outcome ->
                    pendingKeys.onResponse(record.key, abandonOutcome(outcome), holder)
                }
            } finally {
                pendingKeys.release(record.key, holder)
            }
        return abandonResult(result, original, record)
    }

    /** 用户操作置为在途：true 已占用；false 不在待确认；null 写盘失败（记录保持待确认，lessons 32）。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 安全存储写入失败一律按明确的失败返回
    private fun startUserAction(start: () -> Boolean): Boolean? =
        try {
            start()
        } catch (error: Exception) {
            null
        }

    private sealed interface Owned {
        class Record(
            val record: PendingKeyRecord,
        ) : Owned

        class Rejected(
            val reason: String,
        ) : Owned
    }

    /**
     * 当前会话账号的未决记录（R1-11、R3-04）：账号取自可信会话，作为查询参数一路传到存储层（[PendingKeyBackend.list] / read），
     * 读取之前就确定，别的账号的记录不读不解密。没有会话账号或没有记录 → pending_key_unresolved；存储读不出 → pending_store_failed
     * （无法确认未决状态，不当成没有记录，lessons 38）。
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 安全存储读取失败一律按明确的失败返回
    private fun ownedPending(operationId: String): Owned {
        val account = session(context())?.userId ?: return Owned.Rejected(LocalRejectReason.PENDING_KEY_UNRESOLVED)
        val record =
            try {
                pendingKeys.pendingFor(operationId, account)
            } catch (error: Exception) {
                return Owned.Rejected(LocalRejectReason.PENDING_STORE_FAILED)
            }
        return record?.let(Owned::Record) ?: Owned.Rejected(LocalRejectReason.PENDING_KEY_UNRESOLVED)
    }

    /**
     * 一次逻辑调用：绑定归属（屏障等待之前）→ 屏障 → 账号核对 → 逐次发送（含内部续发）。未决键由本次调用占用，整次调用结束时
     * 统一结算（含抛错）：已由响应结算的不动，没拿到结果的在途占用退回 unknown（lessons 18、A-R2-05）。
     */
    private fun runLogical(
        call: ApiCall,
        operation: ApiOperation,
        options: RunOptions,
        holder: String,
    ): ApiResult {
        val logical = Logical(owner(context()), holder)
        try {
            return attempts(call, operation, options, logical)
        } finally {
            logical.pendingKey?.let { pendingKeys.release(it, holder) }
        }
    }

    /** 首次发送与内部续发（至多 1 次）的循环；每次发送前过屏障、核对调用归属、重新判定放行与组头签名。 */
    private fun attempts(
        call: ApiCall,
        operation: ApiOperation,
        options: RunOptions,
        logical: Logical,
    ): ApiResult {
        var current = call
        val budget = Budget()
        while (true) {
            passSessionBarrier(operation, logical.owner)?.let { return it }
            val state = context()
            val session = session(state)
            if (options.pendingAccount != null && session?.userId != options.pendingAccount) {
                return ApiResult.LocalReject(LocalRejectReason.ACCOUNT_MISMATCH)
            }
            val prepared =
                when (val outcome = prepare(current, operation, state, session)) {
                    is Prepared.Rejected -> return ApiResult.LocalReject(outcome.reason)
                    is Prepared.Ready -> outcome
                }
            ownerReject(logical.owner, state)?.let { return ApiResult.LocalReject(it) }
            val key = prepared.idempotencyKey
            holdPending(operation, key, current, session, logical)?.let { return ApiResult.LocalReject(it) }
            current = current.copy(idempotencyKey = key)
            when (val step = sendOnce(current, operation, budget, options, prepared, logical, state, session)) {
                is Step.Done -> return step.result
                Step.Replay -> budget.replayed = true
                Step.Resign -> budget.resigned = true
            }
        }
    }

    /**
     * 四个 step-up 操作的未决键（BR-ID-10）：账号只取自会话；写入成功才发送（R1-11）；整次逻辑调用只登记一次，由本调用占用
     * （A-R2-05）。返回本地拒绝原因或 null（可发）。
     */
    private fun holdPending(
        operation: ApiOperation,
        key: String?,
        call: ApiCall,
        session: SessionSnapshot?,
        logical: Logical,
    ): String? {
        if (logical.pendingKey != null || key == null || operation.stepUp == null) return null
        val account = session?.userId ?: return LocalRejectReason.ACCOUNT_UNKNOWN
        return when (registerPending(operation, key, call, account, logical.holder)) {
            false -> {
                LocalRejectReason.PENDING_KEY_UNRESOLVED
            }

            null -> {
                LocalRejectReason.PENDING_STORE_FAILED
            }

            true -> {
                logical.pendingKey = key
                logical.pendingAccount = account
                null
            }
        }
    }

    /** 整次逻辑调用的未决键结算（只结算本调用占用的键）。 */
    private fun settle(
        logical: Logical,
        kind: String,
    ) {
        logical.pendingKey?.let { pendingKeys.onResponse(it, kind, logical.holder) }
    }

    /** 一次逻辑调用的续发预算（R3-06）：业务重放（10002、10402 之后）与 10401 校时重签各至多 1 次，互不占用。 */
    private class Budget(
        var replayed: Boolean = false,
        var resigned: Boolean = false,
    )

    /** 一次发送与解包、Effect 分派；需要内部续发时返回 [Step.Replay] / [Step.Resign]（未决键保持占用，A-R2-05）。 */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList") // 解包后按 Effect 种类分派，逐条对照 03 §4.2
    private fun sendOnce(
        call: ApiCall,
        operation: ApiOperation,
        budget: Budget,
        options: RunOptions,
        prepared: Prepared.Ready,
        logical: Logical,
        state: NetworkContext,
        session: SessionSnapshot?,
    ): Step {
        val key = prepared.idempotencyKey
        val sent =
            when (val outcome = exchange(call, operation, prepared, logical)) {
                is Sent.Rejected -> return Step.Done(ApiResult.LocalReject(outcome.reason))
                is Sent.Done -> outcome
            }
        val exchange = sent.exchange
        if (exchange !is Exchange.Envelope) {
            val transport = exchange as Exchange.Transport
            settle(logical, PendingKeyStore.NO_ENVELOPE)
            return Step.Done(transportFailure(transport.kind, transport.httpStatus, key, logical))
        }
        val envelope = exchange
        if (envelope.code == 0) {
            settle(logical, PendingKeyStore.ENVELOPE_SUCCESS)
            // 登录：令牌保存成功才算登录成功（lessons 40）
            captureTokens(operation, envelope.data, logical, prepared)?.let { reason ->
                return Step.Done(ApiResult.SessionNotSaved(reason, envelope.traceId))
            }
            return Step.Done(ApiResult.Success(envelope.data, envelope.traceId, key))
        }
        // 10401 校时重签：单独计数，不占业务重放预算（R3-06）；之后的 10002 等仍按首次处理
        val resignable = envelope.code == INVALID_SIGNATURE && !budget.resigned && operation.signed
        if (resignable && correctClock(envelope.headers)) return Step.Resign
        val data = envelope.data as? JsonObject ?: JsonObject(emptyMap())
        var effect =
            ErrorActionMapper.map(
                ErrorActionInput(
                    code = envelope.code,
                    data = data,
                    operation = operation,
                    headers = envelope.headers,
                    isReplay = budget.replayed,
                    state = ErrorActionState(state.update, session.scopeOrFull(), identity.appVersion),
                    fromProductCard = call.fromProductCard,
                ),
            )
        val envelopeKind = PendingKeyStore.envelopeKind(envelope.code)
        // 处理响应这一刻的调用归属（R3-03）：由统一出口 [deliver] 据此拦下旧调用的会话类副作用
        val ownership = ownerReject(logical.owner, context())
        // 刷新类与清会话已按自己的结论核对过归属（刷新失败清的正是本世代，lessons 37）
        var resolved = false
        when (effect.kind) {
            ErrorEffectKind.REFRESH_REPLAY, ErrorEffectKind.REFRESH_ONLY -> {
                // 10002 刷新后重放；10405 + 受支持版本 + deletion_only 只刷新（R1-09）。旧调用不发起刷新：refreshAfter 只为本次所用
                // 令牌仍是当前令牌的会话刷新，会话已换代为 Stale，本世代被刷新失败清掉为 Failed
                cancelled(logical)?.let {
                    settle(logical, envelopeKind)
                    return Step.Done(it)
                }
                val request =
                    if (effect.kind == ErrorEffectKind.REFRESH_REPLAY) {
                        RefreshRequest(id = newId(), operation = operation, key = key, body = call.body.orEmpty())
                    } else {
                        null
                    }
                val refreshed = refreshAfter(sent.request.accessToken, request)
                if (refreshed is RefreshResult.Replay && request != null) return Step.Replay
                cancelled(logical)?.let {
                    settle(logical, envelopeKind)
                    return Step.Done(it)
                }
                resolved = true
                when (refreshed) {
                    RefreshResult.Failed -> {
                        effect = sessionEndedEffect(effect)
                    }

                    RefreshResult.Stale -> {
                        effect = staleSessionEffect(effect)
                    }

                    is RefreshResult.Transient -> {
                        settle(logical, envelopeKind)
                        return Step.Done(transportFailure(refreshed.kind, null, key, logical))
                    }

                    is RefreshResult.Replay -> {
                        // 只刷新成功：原调用结束，Effect 原样（refresh_only）
                        Unit
                    }
                }
            }

            ErrorEffectKind.REGISTER_DEVICE_REPLAY -> {
                if (ownership == null && hooks.reRegisterDevice()) return Step.Replay
                effect = effect.copy(kind = ErrorEffectKind.GENERIC_ERROR, replay = ReplayMode.NONE, includeTrace6 = true)
            }

            ErrorEffectKind.FORCE_UPDATE -> {
                // 版本闸门不属于某个会话；只有撤回同意、切换环境之后的旧响应不再触发
                if (ownership != LocalRejectReason.CALL_CANCELLED) hooks.onForceUpdate(data.text("min_supported_version"))
            }

            ErrorEffectKind.CLEAR_SESSION_LOGIN -> {
                // 10404：只清本次请求所属的那一代会话；旧世代的响应不动当前会话、不跳登录（lessons 34）
                resolved = true
                if (ownership != null || !clearOwnedSession(logical.owner)) effect = staleSessionEffect(effect)
            }

            else -> {
                // 其余 Effect 由页面消费
            }
        }
        settle(logical, envelopeKind)
        effect = deliver(effect, logical, options, ownership.takeUnless { resolved })
        return Step.Done(ApiResult.Failure(envelope.code, envelope.msg, data, envelope.traceId, effect, key))
    }

    /**
     * 统一出口（R3-03）：所有失败响应的 Effect 都从这里交给页面。依次：
     * 1. 调用归属已失效（[stale] 非 null：账号变了、会话换代、撤回同意、切换环境）→ 会话类副作用（跳登录、补办、同意、绑手机、
     *    step-up、授权、刷新、清会话、跳转）与 pending_action 登记一律不交付，按通用错误态结束（lessons 34、40）；
     *    旧响应只结算原调用的未决记录（[settle] 已在前面按原账号做完）；
     * 2. 作废请求被前置码拦下：只引导，不登记 pending_action、不自动重放（R1-14）；
     * 3. 键已结果未知：不换新键、不登记 pending_action（10003 例外，R1-12）；非终结的 2xxxx（20903 除外）统一为
     *    「暂时确认不了」的页面动作（lessons 39）。
     */
    private fun deliver(
        mapped: ErrorEffect,
        logical: Logical,
        options: RunOptions,
        stale: String?,
    ): ErrorEffect {
        var effect = mapped
        if (stale != null && sessionBound(effect)) effect = staleSessionEffect(effect)
        if (options.abandonFor != null) {
            val guide = if (effect.registerPending) ErrorEffectKind.GUIDE_ONLY else effect.kind
            effect = effect.copy(kind = guide, replay = ReplayMode.NONE, registerPending = false, pendingKeyUnknown = true)
        }
        val pendingKey = logical.pendingKey
        if (pendingKey != null && pendingUnknown(pendingKey, logical.pendingAccount)) {
            val replay = if (effect.kind == ErrorEffectKind.STEP_UP) effect.replay else ReplayMode.NONE
            effect = effect.copy(replay = replay, registerPending = false, pendingKeyUnknown = true)
            if (effect.code in CLIENT_ERROR_MIN..CLIENT_ERROR_MAX && effect.code != ABANDONED_CODE) {
                effect =
                    effect.copy(
                        kind = ErrorEffectKind.PAGE_ACTION,
                        replay = ReplayMode.NONE,
                        route = null,
                        textKey = CANNOT_CONFIRM,
                        fields = null,
                    )
            }
        }
        hooks.onEffect(effect)
        return effect
    }

    /** 会话类副作用（R3-03）：登记 pending_action、要重放、跳转，或属于登录 / 补办 / 刷新 / 清会话一类的 Effect。 */
    private fun sessionBound(effect: ErrorEffect): Boolean =
        effect.registerPending ||
            effect.replay != ReplayMode.NONE ||
            effect.kind in SESSION_BOUND_KINDS ||
            (effect.route != null && effect.kind != ErrorEffectKind.FORCE_UPDATE)

    /** 10404：在 [refreshLock] 下核对归属并按世代清会话（与刷新入队、登录保存同一把锁，R3-01）。返回是否清的是本调用的会话。 */
    private fun clearOwnedSession(owner: CallOwner): Boolean =
        synchronized(refreshLock) {
            val now = context()
            val current = rawSession(now)?.takeIf(::hasTokens) ?: return false
            if (ownerReject(owner, now) != null) return false
            clearSessionIf(current.generation)
        }

    private fun transportFailure(
        kind: String,
        httpStatus: Int?,
        key: String?,
        logical: Logical,
    ) = ApiResult.TransportFailure(
        kind,
        httpStatus,
        key,
        pendingKeyUnknown = logical.pendingKey?.let { pendingUnknown(it, logical.pendingAccount) } == true,
    )

    /** 撤回同意或切换环境后（世代已变）：内部续发作废，不再输出会话类 Effect。 */
    private fun cancelled(logical: Logical): ApiResult? =
        ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED).takeIf { inFlight.epochs() != logical.owner.epochs }

    /** 绑定调用归属（任何屏障等待之前，lessons 30）。 */
    private fun owner(state: NetworkContext): CallOwner {
        val epochs = inFlight.epochs()
        val raw = rawSession(state)
        return CallOwner(epochs, raw?.generation, raw?.takeIf(::hasTokens))
    }

    /**
     * 调用归属核对（所有自动续发与每次写出前共用，A-R2-01..05）：隐私 / 环境世代变了 → call_cancelled；凭据此刻不可读的只按匿名
     * 白名单发（不带凭据，放行判定负责）；开始时没有会话而现在有了、
     * 开始时的会话已没有、账号变了 → session_changed；会话世代变了，只有沿刷新链（同一会话里经确认的令牌刷新）走到当前令牌才放行。
     */
    private fun ownerReject(
        owner: CallOwner,
        state: NetworkContext,
    ): String? {
        if (inFlight.epochs() != owner.epochs) return LocalRejectReason.CALL_CANCELLED
        // 凭据此刻不可读：这次发送不读不带任何凭据（放行判定只放匿名白名单），不会替别的账号发出
        if (!readable(state)) return null
        val now = rawSession(state)?.takeIf(::hasTokens)
        val start = owner.session ?: return if (now == null) null else LocalRejectReason.SESSION_CHANGED
        if (now == null || now.userId != start.userId) return LocalRejectReason.SESSION_CHANGED
        if (now.generation == start.generation) return null
        return if (chainReaches(start.accessToken, now.accessToken)) null else LocalRejectReason.SESSION_CHANGED
    }

    /** 刷新链上从 [from] 能否走到 [to]（同一会话里经确认的令牌刷新）。 */
    private fun chainReaches(
        from: String?,
        to: String?,
    ): Boolean {
        if (from == null || to == null) return false
        synchronized(refreshLock) {
            var token: String = from
            repeat(OUTCOME_MEMORY + 1) {
                if (token == to) return true
                token = refreshOutcomes[token]?.token ?: return false
            }
        }
        return false
    }

    /** 写入未决记录：true 可发；false 同一操作已有未决的键（或键正被别的调用占用）；null 写入失败（不发）。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 安全存储写入失败一律不发
    private fun registerPending(
        operation: ApiOperation,
        key: String,
        call: ApiCall,
        account: String,
        holder: String,
    ): Boolean? =
        try {
            pendingKeys.prepareSend(operation.operationId, key, call.body.orEmpty(), account, call.summary, holder)
        } catch (error: Exception) {
            null
        }

    private sealed interface Sent {
        data class Rejected(
            val reason: String,
        ) : Sent

        class Done(
            val exchange: Exchange,
            val request: Prepared.Ready,
        ) : Sent
    }

    /**
     * 发送；读接口超时 / 断连按退避自动重试。每次重试前核对调用归属（A-R2-04：退避期间换了账号或重新登录，结束旧调用），
     * 再重新取状态、重新判定放行、重新组头与签名（R1-01）。每次写出前网络拦截器再核对一次（[guardFor]，A-R2-01）。
     */
    @Suppress("TooGenericExceptionCaught") // 发送中途的意外异常：键按结果未知处理后原样抛出
    private fun exchange(
        call: ApiCall,
        operation: ApiOperation,
        first: Prepared.Ready,
        logical: Logical,
    ): Sent {
        val retry = retryPolicy(operation)
        var request = first
        var attempt = 0
        while (true) {
            val result =
                try {
                    transport.send(request.request, retry.timeoutMillis, guardFor(logical.owner, operation, call, request))
                } catch (error: Exception) {
                    settle(logical, PendingKeyStore.NO_ENVELOPE)
                    throw error
                }
            if (result is Exchange.NotSent) {
                // 这次没有写出；之前的一次已发出、没有统一外壳：未决键结果未知
                if (attempt > 0) settle(logical, PendingKeyStore.NO_ENVELOPE)
                return Sent.Rejected(result.reason)
            }
            val retriable = result is Exchange.Transport && result.kind in RETRIABLE_KINDS
            if (!retriable || attempt >= retry.autoRetries) return Sent.Done(result, request)
            attempt += 1
            sleeper(retry.backoffMillis)
            val state = context()
            // 先按当前状态重新判定放行（撤回、环境未就绪的原因优先），再核对调用归属
            val rejection =
                when (val again = prepare(call, operation, state, session(state))) {
                    is Prepared.Rejected -> {
                        again.reason
                    }

                    is Prepared.Ready -> {
                        request = again
                        ownerReject(logical.owner, state)
                    }
                }
            if (rejection != null) {
                // 上一次已发出、没有统一外壳：未决键结果未知（不是「没发出」）
                settle(logical, PendingKeyStore.NO_ENVELOPE)
                return Sent.Rejected(rejection)
            }
        }
    }

    /** 写出前的核对（网络拦截器，连接建立之后）：调用归属 → 环境基址 → 放行判定 → 凭据仍可读（A-R2-01）。 */
    private fun guardFor(
        owner: CallOwner,
        operation: ApiOperation,
        call: ApiCall,
        prepared: Prepared.Ready,
    ): SendGuard =
        SendGuard(owner.epochs) {
            val now = context()
            ownerReject(owner, now) ?: admission(operation, call, prepared, now)
        }

    private fun admission(
        operation: ApiOperation,
        call: ApiCall,
        prepared: Prepared.Ready,
        state: NetworkContext,
    ): String? {
        if (baseUrl() != prepared.base) return LocalRejectReason.ENVIRONMENT_UNSETTLED
        val decision = decide(call, operation, state, session(state))
        if (!decision.send) return decision.localRejectReason ?: LocalRejectReason.ENVIRONMENT_UNSETTLED
        if (prepared.carriesCredentials && !readable(state)) return LocalRejectReason.BASIC_MODE_DENIED
        return null
    }

    private sealed interface Prepared {
        data class Rejected(
            val reason: String,
        ) : Prepared

        /** [base] 组 URL 时的环境基址；[carriesCredentials] 带了令牌、设备标识或签名。 */
        class Ready(
            val request: PreparedRequest,
            val idempotencyKey: String?,
            val accessToken: String?,
            val base: String,
            val carriesCredentials: Boolean,
        ) : Prepared
    }

    /** 放行判定（[RequestHeaderPolicy]）：凭据只在可读时读。 */
    private fun decide(
        call: ApiCall,
        operation: ApiOperation,
        state: NetworkContext,
        session: SessionSnapshot?,
    ): HeaderDecision {
        val deviceId = if (readable(state)) device.deviceId else null
        val bodyJson = call.body?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        return RequestHeaderPolicy.decide(
            NetworkRequestState(
                privacy = state.privacy,
                sessionScope = session.scopeOrFull(),
                hasAccessToken = session?.accessToken != null,
                deviceId = deviceId,
                environmentSettled = state.environmentSettled,
                credentialsReadable = state.credentialsReadable,
            ),
            operation,
            bodyJson,
        )
    }

    /** 公共头 → 签名：决定要带的头，读凭据（只在可读时），算签名。 */
    @Suppress("CyclomaticComplexMethod") // 逐项本地拒绝，再按决定逐个加头
    private fun prepare(
        call: ApiCall,
        operation: ApiOperation,
        state: NetworkContext,
        session: SessionSnapshot?,
    ): Prepared {
        val readable = readable(state)
        val accessToken = session?.accessToken
        val decision = decide(call, operation, state, session)
        if (!decision.send) return Prepared.Rejected(decision.localRejectReason ?: LocalRejectReason.ENVIRONMENT_UNSETTLED)
        val deviceId = if (decision.includeDeviceId) device.deviceId else null
        val base = baseUrl() ?: return Prepared.Rejected(LocalRejectReason.ENVIRONMENT_UNSETTLED)
        val url =
            HttpTransport.buildUrl(base, operation.pathTemplate, call.pathParams, call.query)
                ?: return Prepared.Rejected(LocalRejectReason.ENVIRONMENT_UNSETTLED)
        val body = call.body?.toByteArray(Charsets.UTF_8)
        val headers = linkedMapOf<String, String>()
        headers[HeaderNames.APP_ID] = identity.appId
        headers[HeaderNames.PLATFORM] = identity.platform
        headers[HeaderNames.APP_VERSION] = identity.appVersion
        headers[HeaderNames.BUILD] = identity.build
        headers[HeaderNames.CHANNEL] = identity.channel
        headers[HeaderNames.TRACE_ID] = newId()
        if (decision.includeAuthorization && accessToken != null) headers[HeaderNames.AUTHORIZATION] = "Bearer $accessToken"
        if (decision.includeDeviceId && deviceId != null) headers[HeaderNames.DEVICE_ID] = deviceId
        val key = if (decision.includeIdempotencyKey) call.idempotencyKey ?: newId() else null
        key?.let { headers[HeaderNames.IDEMPOTENCY_KEY] = it }
        if (readable && operation.stepUp != null) call.stepUpToken?.let { headers[HeaderNames.STEP_UP_TOKEN] = it }
        if (decision.includeSignature) {
            val secret = device.installSecret?.takeIf { readable } ?: return Prepared.Rejected(LocalRejectReason.DEVICE_NOT_READY)
            headers +=
                RequestSigner.headers(
                    installSecret = secret,
                    method = operation.method,
                    pathWithQuery = HttpTransport.signedPath(url),
                    body = body ?: ByteArray(0),
                    timestamp = RequestSigner.timestamp(clock() + clockOffsetMillis),
                    nonce = nonce(),
                )
        }
        val sentToken = accessToken.takeIf { decision.includeAuthorization }
        val credentials = HeaderNames.AUTHORIZATION in headers || HeaderNames.DEVICE_ID in headers || HeaderNames.SIGN in headers
        return Prepared.Ready(PreparedRequest(operation.method, url, headers, body), key, sentToken, base, credentials)
    }

    private fun readable(state: NetworkContext): Boolean = state.privacy == PrivacyRaw.FULL && state.credentialsReadable

    /** 会话存储（同一把锁下读出，含世代）；凭据不可读、清会话写盘失败还没清掉时为 null。 */
    private fun rawSession(state: NetworkContext): SessionSnapshot? {
        if (!readable(state)) return null
        if (sessionQuarantined && !retryQuarantineClear()) return null
        return tokens.snapshot()
    }

    private fun hasTokens(snapshot: SessionSnapshot): Boolean = snapshot.accessToken != null || snapshot.refreshToken != null

    /** 可读的会话；没有令牌为 null。 */
    private fun session(state: NetworkContext): SessionSnapshot? = rawSession(state)?.takeIf(::hasTokens)

    /** 会话作用域：凭据不可读或没有会话时按 full（不带令牌，接口照 full 判定）。 */
    private fun SessionSnapshot?.scopeOrFull(): String = this?.scope ?: SessionScopeRaw.FULL

    /**
     * 受限会话屏障（BR-ID-01 细则「受限会话」；R1-10、lessons 19、30、31）：冷启动后第一次能读到会话时、或强更状态从 below /
     * unknown 变为受支持时，存储的会话是 deletion_only → 先单飞刷新；刷新结束前，其余带令牌的请求（x-auth 不是 none）一律等待，
     * 不看目标接口在不在受限表里。结束后按完整结果结算：
     * - 刷新成功：核对调用归属（屏障之前已绑定，沿刷新链放行），会话已换代的结束旧调用，不改绑到当前账号；
     * - 刷新被拒：返回 [ApiResult.SessionEnded]（R1-09），原请求不发；Effect 按结算那一刻的强更状态（lessons 20）；
     * - 超时、断连等暂时失败：按网络错误结束（不带旧受限令牌发出），屏障重新到期，下一个请求再刷新。
     */
    private fun passSessionBarrier(
        operation: ApiOperation,
        owner: CallOwner,
    ): ApiResult? {
        val pending: CompletableFuture<WaitSignal>?
        synchronized(barrierLock) {
            val state = context()
            observeUpdate(state.update)
            armBarrier(state)
            pending = barrier
        }
        if (pending == null || operation.auth == AUTH_NONE) return null
        return when (val signal = waitFor(pending) ?: WaitSignal.Transient(HttpTransport.KIND_TIMEOUT)) {
            is WaitSignal.Token -> {
                ownerReject(owner, context())?.let { ApiResult.LocalReject(it) }
            }

            is WaitSignal.Transient -> {
                // 屏障已结算为暂时失败（或等不到）：按网络错误结束，不带旧受限令牌发出；屏障重新到期（R3-02）
                synchronized(barrierLock) {
                    settleBarrier()
                    if (barrier === pending) {
                        barrier = null
                        barrierDue = true
                    }
                }
                ApiResult.TransportFailure(signal.kind, null, null, pendingKeyUnknown = false)
            }

            WaitSignal.Failed -> {
                val now = context()
                when {
                    inFlight.epochs() != owner.epochs -> {
                        ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
                    }

                    // 刷新失败期间已登录了新会话：结束旧调用，不改绑到当前账号（lessons 30）
                    session(now) != null -> {
                        ApiResult.LocalReject(LocalRejectReason.SESSION_CHANGED)
                    }

                    else -> {
                        val effect =
                            ErrorEffect(
                                code = SESSION_REFRESH_FAILED,
                                kind = refreshFailureKind(now),
                                replay = ReplayMode.NONE,
                                registerPending = false,
                                route = refreshFailureRoute(now),
                            )
                        hooks.onEffect(effect)
                        ApiResult.SessionEnded(effect)
                    }
                }
            }
        }
    }

    /** 屏障到期且存储的会话是 deletion_only 时发起屏障刷新。只在 [barrierLock] 下调用。 */
    private fun armBarrier(state: NetworkContext) {
        settleBarrier()
        if (!barrierDue) return
        val session = session(state) ?: return
        barrierDue = false
        val restricted = session.scope == SessionScopeRaw.DELETION_ONLY && session.refreshToken != null
        if (restricted && barrier == null) barrier = startBarrierRefresh(state, session)
    }

    /**
     * 屏障的统一结算（R3-02，只在 [barrierLock] 下调用）：按完成的结果结算，不只看 isDone。只有刷新成功才解除屏障；
     * 暂时失败（超时、断连、被取消）重新置为待刷新——后续带令牌的请求先再刷新一次，不带旧受限令牌发出（lessons 31）；
     * 被拒时会话已清（之后的登录不需要屏障），同样重新到期，没有会话时不发起。
     */
    private fun settleBarrier() {
        val done = barrier?.takeIf { it.isDone } ?: return
        barrier = null
        val signal = runCatching { done.getNow(null) }.getOrNull()
        if (signal !is WaitSignal.Token) barrierDue = true
    }

    /** 记下强更状态；由 below / unknown 变为 supported 时屏障到期。只在 [barrierLock] 下调用。 */
    private fun observeUpdate(update: String) {
        val previous = lastObservedUpdate
        lastObservedUpdate = update
        if (previous != null && previous != UpdateRaw.SUPPORTED && update == UpdateRaw.SUPPORTED) barrierDue = true
    }

    /** 屏障刷新：刷新对象就是检查时读到的受限会话快照 [snapshot]（R3-01）。 */
    private fun startBarrierRefresh(
        state: NetworkContext,
        snapshot: SessionSnapshot,
    ): CompletableFuture<WaitSignal> {
        val future = CompletableFuture<WaitSignal>()
        val forceUpdate = state.update == UpdateRaw.BELOW
        synchronized(refreshLock) {
            enlist(snapshot, forceUpdate) {
                refreshCoordinator.awaitRefresh(forceUpdate) { token -> future.complete(token?.let(WaitSignal::Token) ?: failureSignal()) }
            }
        }
        return future
    }

    /**
     * 在 [refreshLock] 下把 [join] 放进 [snapshot] 那一代会话的刷新窗口（lessons 36）：
     * - 没有在途的刷新：[join] 发起一次，刷新对象就是 [snapshot]（refresh 回调同步取走 [nextRefreshStart]，不再读当前会话，R3-01）；
     * - 在途的刷新是同一代会话的：加入它；
     * - 在途的刷新属于别的世代：不共享它的结果，等它结束后再入队（仍以 [snapshot] 为刷新对象，保存与清理按它的世代 CAS）。
     */
    private fun enlist(
        snapshot: SessionSnapshot,
        forceUpdate: Boolean,
        join: () -> Unit,
    ) {
        if (refreshCoordinator.refreshing && windowStart?.generation != snapshot.generation) {
            refreshCoordinator.awaitRefresh(forceUpdate) { enlist(snapshot, forceUpdate, join) }
            return
        }
        nextRefreshStart = snapshot
        try {
            join()
        } finally {
            nextRefreshStart = null
        }
    }

    /** 刷新失败时交给等待者的信号：暂时失败或被拒。只在刷新结算（[refreshLock] 下）时调用。 */
    private fun failureSignal(): WaitSignal = settlingTransient?.let(WaitSignal::Transient) ?: WaitSignal.Failed

    /**
     * 等单飞刷新（[request] 非 null = 10002 后要重放的请求；null = 只刷新）。在 [refreshLock] 下一次完成（R1-06、R3-01）：
     * 本次所用令牌已有刷新结论 → 直接复用（lessons 37）；否则取得完整会话快照，令牌检查与刷新对象是同一份快照——当前令牌就是
     * 本次所用令牌才入队（必要时以这份快照发起刷新，[enlist]），不是则会话已换代（lessons 14）；没有会话也按换代（不是本世代
     * 因刷新失败被清，那种情况由刷新结论给出）。结束后按本次所用令牌的刷新结论判定。超时、断连或等不到结果 → 暂时失败（lessons 31）。
     */
    private fun refreshAfter(
        usedToken: String?,
        request: RefreshRequest?,
    ): RefreshResult {
        val waiter = CompletableFuture<WaitSignal>()
        synchronized(refreshLock) {
            outcomeFor(usedToken)?.let { return it }
            val snapshot = session(context()) ?: return RefreshResult.Stale
            if (usedToken == null || snapshot.accessToken != usedToken) return RefreshResult.Stale
            val forceUpdate = context().update == UpdateRaw.BELOW
            if (request != null) waiters[request.id] = waiter
            enlist(snapshot, forceUpdate) {
                if (request != null) {
                    refreshCoordinator.run(listOf(request), forceUpdate)
                } else {
                    refreshCoordinator.awaitRefresh(
                        forceUpdate,
                    ) { token -> waiter.complete(token?.let(WaitSignal::Token) ?: failureSignal()) }
                }
            }
        }
        val signal = waitFor(waiter) ?: WaitSignal.Transient(HttpTransport.KIND_TIMEOUT)
        request?.let { waiters.remove(it.id) }
        if (signal is WaitSignal.Transient) return RefreshResult.Transient(signal.kind)
        synchronized(refreshLock) {
            return outcomeFor(usedToken) ?: RefreshResult.Stale
        }
    }

    /**
     * 本次所用令牌那一代的刷新结论（只在 [refreshLock] 下调用；结论按令牌记下，不随之后的窗口变化，lessons 36）：沿刷新链
     * （旧令牌 → 新令牌）走到当前令牌才重放；链上被拒且那一代确实因此被清、现在也没有会话 → 会话已清（[RefreshResult.Failed]，
     * 不再刷新、不再清，lessons 37）；其余（期间重新登录、切换账号、退出）→ 会话已换代。没有记录为 null。
     */
    @Suppress("ReturnCount") // 沿刷新链逐步判定
    private fun outcomeFor(usedToken: String?): RefreshResult? {
        var outcome = usedToken?.let { refreshOutcomes[it] } ?: return null
        val current = session(context())
        repeat(OUTCOME_MEMORY) {
            val token = outcome.token ?: return if (outcome.cleared && current == null) RefreshResult.Failed else RefreshResult.Stale
            if (token == current?.accessToken) return RefreshResult.Replay(token)
            outcome = refreshOutcomes[token] ?: return RefreshResult.Stale
        }
        return RefreshResult.Stale
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught") // 等待超时或被中断：按暂时失败处理（不放行旧令牌）
    private fun waitFor(waiter: CompletableFuture<WaitSignal>): WaitSignal? =
        try {
            waiter.get(REFRESH_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            null
        }

    /** 刷新失败的 Effect：清会话后跳登录；强更状态下只清会话、停在强更页。按此刻的强更状态（lessons 20）。 */
    private fun sessionEndedEffect(effect: ErrorEffect): ErrorEffect {
        val now = context()
        return effect.copy(
            kind = refreshFailureKind(now),
            replay = ReplayMode.NONE,
            registerPending = false,
            route = refreshFailureRoute(now),
        )
    }

    /** 会话已换代：旧请求不替新会话重放、不动新会话，按通用错误态结束（lessons 14、34）。 */
    private fun staleSessionEffect(effect: ErrorEffect): ErrorEffect =
        effect.copy(
            kind = ErrorEffectKind.GENERIC_ERROR,
            replay = ReplayMode.NONE,
            registerPending = false,
            route = null,
            includeTrace6 = true,
        )

    private fun refreshFailureKind(state: NetworkContext): ErrorEffectKind =
        if (state.update == UpdateRaw.BELOW) ErrorEffectKind.CLEAR_SESSION_STAY_FORCE_UPDATE else ErrorEffectKind.CLEAR_SESSION_LOGIN

    private fun refreshFailureRoute(state: NetworkContext): String = if (state.update == UpdateRaw.BELOW) FORCE_UPDATE else LOGIN

    /**
     * 刷新线程上的一次刷新：刷新对象 [start] 与世代 [epochs] 在入队时已固定（A-R2-03），这里不再读取当前账号。无论正常返回还是
     * 抛错都结算窗口（R1-08）。在 [refreshLock] 下结算：被拒时记下结果（供晚到的同一旧令牌复用）并只清 [start] 那一代的会话
     * （R1-06、R1-07）；暂时失败不记、不清会话（lessons 31）。
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 任何异常都按刷新失败结算
    private fun finishRefresh(
        start: SessionSnapshot?,
        epochs: CallEpochs,
        completion: (String?) -> Unit,
    ) {
        val attempt =
            try {
                start?.let { refreshTokens(it, epochs) } ?: RefreshAttempt.Rejected
            } catch (error: Exception) {
                RefreshAttempt.Rejected
            }
        synchronized(refreshLock) {
            val access = start?.accessToken
            // 被拒：记下结论供晚到的同一旧令牌复用；保存失败时已按「本世代被清」记下的不覆盖
            if (attempt == RefreshAttempt.Rejected && access != null && refreshOutcomes[access]?.cleared != true) {
                refreshOutcomes[access] = RefreshOutcome(null)
            }
            // 窗口的刷新对象到此为止：结算期间（含回调里接着为别的世代发起的窗口）不再把它当作在途窗口
            windowStart = null
            settlingGeneration = start?.generation
            settlingAccess = access
            settlingTransient = (attempt as? RefreshAttempt.Transient)?.kind
            try {
                completion((attempt as? RefreshAttempt.Token)?.token)
            } finally {
                settlingGeneration = null
                settlingAccess = null
                settlingTransient = null
            }
        }
    }

    /**
     * RefreshCoordinator 的清会话（在 [refreshLock] 下）：只清刷新开始时那一代（按世代 CAS；之后的新登录、已清除的会话不动）；
     * 暂时失败不清。确实清掉了就把结论记为「本世代因刷新失败被清除」（lessons 37）。
     */
    private fun clearSettlingSession() {
        if (settlingTransient != null) return
        val generation = settlingGeneration ?: return
        if (clearSessionIf(generation)) settlingAccess?.let { refreshOutcomes[it] = RefreshOutcome(null, cleared = true) }
    }

    /** 按世代 CAS 清会话，返回是否清的是这一代。写盘失败：隔离会话（内存里继续阻断旧令牌），之后持续重试（lessons 38）。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun clearSessionIf(generation: Long): Boolean =
        try {
            tokens.clearIf(generation)
        } catch (error: Exception) {
            quarantine()
            true
        }

    /** 在 [refreshLock] 下隔离当前存储的这一代会话（写盘失败时世代照样前进，按失败之后的世代记，R4-01）。 */
    private fun quarantine() {
        quarantinedGeneration = tokens.generation
        sessionQuarantined = true
    }

    /**
     * 清会话写盘失败后的重试（与保存、刷新入队同一把锁）；清掉了解除隔离。锁内重新检查隔离状态，并且只按被隔离的那一代 CAS 清除：
     * 隔离已被别的重试解除、或会话已换代（新登录、别处已清）时不再清理，直接解除隔离（R4-01）。
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun retryQuarantineClear(): Boolean {
        beforeQuarantineRetry()
        return synchronized(refreshLock) {
            val generation = quarantinedGeneration
            if (!sessionQuarantined) return@synchronized true
            try {
                // 世代已变（clearIf 返回 false）说明被隔离的会话已不在存储里：不动新会话，解除隔离
                if (generation != null) tokens.clearIf(generation)
                sessionQuarantined = false
                quarantinedGeneration = null
                true
            } catch (error: Exception) {
                // 写盘失败时世代照样前进：继续按失败之后的世代隔离
                quarantine()
                false
            }
        }
    }

    /**
     * `POST /v1/auth/refresh`（在刷新线程上执行，不经过 ErrorActionMapper）。写出前核对世代、会话世代与环境（[SendGuard]）；响应回来时
     * 会话世代已变（撤回同意、切换环境、清会话、重新登录）、凭据已不可读或环境基址已变 → 丢弃结果（R1-07）。写入与刷新链记录在
     * [refreshLock] 下一起完成，调用归属核对不会看到「已换令牌、还没记链」的中间态。写入失败 → 隔离会话（R1-08）。
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "CyclomaticComplexMethod")
    private fun refreshTokens(
        start: SessionSnapshot,
        epochs: CallEpochs,
    ): RefreshAttempt {
        val operation = ApiOperations.byId(REFRESH) ?: return RefreshAttempt.Rejected
        val refreshToken = start.refreshToken ?: return RefreshAttempt.Rejected
        if (inFlight.epochs() != epochs) return RefreshAttempt.Transient(HttpTransport.KIND_CANCELLED)
        val body = JsonObject(mapOf("refresh_token" to JsonPrimitive(refreshToken))).toString()
        var resigned = false
        var prepared: Prepared.Ready
        var envelope: Exchange.Envelope
        while (true) {
            // 每次发送（含校时重签）都重新组头、重新签名；刷新对象始终是入队时的快照 start
            prepared =
                prepare(ApiCall(REFRESH, body = body), operation, context(), start) as? Prepared.Ready ?: return RefreshAttempt.Rejected
            envelope =
                when (
                    val exchange =
                        transport.send(
                            prepared.request,
                            retryPolicy(operation).timeoutMillis,
                            refreshGuard(start, epochs, prepared),
                        )
                ) {
                    is Exchange.Envelope -> exchange
                    is Exchange.Transport -> return RefreshAttempt.Transient(exchange.kind)
                    is Exchange.NotSent -> return notSentRefresh(exchange.reason)
                }
            // 刷新请求自身遇到可校时的 10401：校时后换 X-Timestamp、X-Nonce 重签 1 次，仍在同一个单飞窗口里（R3-07、BR-ID-09）
            if (envelope.code != INVALID_SIGNATURE || resigned || !correctClock(envelope.headers)) break
            resigned = true
        }
        if (envelope.code != 0) return RefreshAttempt.Rejected
        val pair = tokenPair(envelope.data as? JsonObject) ?: return RefreshAttempt.Rejected
        if (inFlight.epochs() != epochs) return RefreshAttempt.Transient(HttpTransport.KIND_CANCELLED)
        val now = context()
        // 撤回清理中、环境变了：刷新结果作废（被拒处理只清 start 那一代，已清除的会话不动）
        if (!readable(now) || !now.environmentSettled || baseUrl() != prepared.base) return RefreshAttempt.Rejected
        synchronized(refreshLock) {
            val saved =
                try {
                    tokens.replaceIf(start.generation, pair.access, pair.refresh, pair.scope)
                } catch (error: Exception) {
                    // 写盘失败：隔离并清掉这一代会话（内存里继续阻断旧令牌，lessons 38），结论是「本世代因刷新失败被清除」
                    quarantine()
                    retryQuarantineClear()
                    start.accessToken?.let { refreshOutcomes[it] = RefreshOutcome(null, cleared = true) }
                    return RefreshAttempt.Rejected
                }
            if (!saved) return RefreshAttempt.Rejected
            start.accessToken?.let { refreshOutcomes[it] = RefreshOutcome(pair.access) }
        }
        afterSessionSaved(pair.scope)
        return RefreshAttempt.Token(pair.access)
    }

    /** 刷新请求写出前的核对：世代、凭据可读、环境基址未变，会话仍是刷新对象 [start] 那一代。 */
    private fun refreshGuard(
        start: SessionSnapshot,
        epochs: CallEpochs,
        prepared: Prepared.Ready,
    ): SendGuard =
        SendGuard(epochs) {
            val now = context()
            when {
                !readable(now) || !now.environmentSettled || baseUrl() != prepared.base -> LocalRejectReason.CALL_CANCELLED
                rawSession(now)?.generation != start.generation -> LocalRejectReason.SESSION_CHANGED
                else -> null
            }
        }

    /** 刷新请求没写出：被作废取消的按暂时失败（会话由清理负责）；会话已换代等按被拒（只清 start 那一代，不动新会话）。 */
    private fun notSentRefresh(reason: String): RefreshAttempt =
        if (reason == LocalRejectReason.CALL_CANCELLED) RefreshAttempt.Transient(HttpTransport.KIND_CANCELLED) else RefreshAttempt.Rejected

    private class TokenPair(
        val access: String,
        val refresh: String,
        val scope: String,
    )

    /** TokenPair（openapi）：access_token、refresh_token、session_scope。 */
    private fun tokenPair(data: JsonObject?): TokenPair? {
        val pair = data ?: return null
        val access = pair.text("access_token") ?: return null
        val refresh = pair.text("refresh_token") ?: return null
        val scope = pair.text("session_scope") ?: return null
        return TokenPair(access, refresh, scope)
    }

    /**
     * 登录与刷新成功：记下令牌、session_scope（与登录的 user_id）。登录响应是 LoginResponse：`data.user_id` 与 `data.tokens`；
     * 刷新响应是 TokenPairResponse：令牌在 `data` 上（R1-04）。只在请求发出时那一代会话、隐私世代、环境世代都未变，凭据仍可读、
     * 环境基址未变时写入，迟到的响应直接丢弃（A-R2-02）；会话世代的核对与写入原子完成（[SessionTokens.saveIf]）。
     * 返回 null = 不是登录或已保存；否则为登录没有保存的原因（lessons 40：调用方返回 [ApiResult.SessionNotSaved]，不算登录成功）。
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException") // 逐项丢弃；写盘失败按明确的失败返回
    private fun captureTokens(
        operation: ApiOperation,
        data: JsonElement?,
        logical: Logical,
        prepared: Prepared.Ready,
    ): String? {
        if (operation.operationId !in TOKEN_OPERATIONS) return null
        val root = data as? JsonObject
        val owner = logical.owner
        if (operation.operationId == REFRESH) {
            // 页面直接调刷新接口（单飞刷新走 refreshTokens）：只在原世代时写入，迟到的丢弃
            val pair = tokenPair(root) ?: return null
            if (!stillOwned(owner.epochs, prepared.base)) return null
            val start = owner.session ?: return null
            synchronized(refreshLock) {
                if (!tokens.replaceIf(start.generation, pair.access, pair.refresh, pair.scope)) return null
                start.accessToken?.let { refreshOutcomes[it] = RefreshOutcome(pair.access) }
            }
            afterSessionSaved(pair.scope)
            return null
        }
        // 登录：令牌保存成功才算登录成功（lessons 40）；作废、换代后到达的不保存，也不算登录成功
        val pair = tokenPair(root?.get("tokens") as? JsonObject) ?: return LocalRejectReason.INVALID_SESSION_TOKENS
        if (!stillOwned(owner.epochs, prepared.base)) return LocalRejectReason.CALL_CANCELLED
        val generation = owner.generation ?: return LocalRejectReason.SESSION_CHANGED
        // 与刷新入队、清会话同一把锁，并按世代 CAS（R3-01）
        val saved =
            try {
                synchronized(refreshLock) { tokens.saveIf(generation, pair.access, pair.refresh, pair.scope, root?.text("user_id")) }
            } catch (error: Exception) {
                return LocalRejectReason.SESSION_SAVE_FAILED
            }
        if (!saved) return LocalRejectReason.SESSION_CHANGED
        // 刚登录拿到的会话是新的一代：不需要冷启动屏障再刷新一次
        synchronized(barrierLock) { barrierDue = false }
        afterSessionSaved(pair.scope)
        return null
    }

    /** 处理响应这一刻：世代未变、凭据可读、环境已就绪且基址未变（迟到的令牌响应据此丢弃）。 */
    private fun stillOwned(
        epochs: CallEpochs,
        base: String,
    ): Boolean {
        if (inFlight.epochs() != epochs) return false
        val now = context()
        return readable(now) && now.environmentSettled && baseUrl() == base
    }

    /**
     * 强更检查未知期间拿到 deletion_only，说明本机版本已低于最低支持版本：按「低于」处理（BR-ID-01 细则「受限会话」）。
     * 强更状态按此刻（处理响应时）读取，不用请求开始时的旧值（A-R2-07）。
     */
    private fun afterSessionSaved(scope: String) {
        if (scope == SessionScopeRaw.DELETION_ONLY && context().update == UpdateRaw.UNKNOWN) hooks.onForceUpdate(null)
    }

    /** 10401：响应头 Date 与本机时钟相差超过 300 秒时校正偏移（BR-ID-09）。 */
    private fun correctClock(headers: Map<String, String>): Boolean {
        val date = headers.entries.firstOrNull { it.key.equals(HeaderNames.DATE, ignoreCase = true) }?.value ?: return false
        val server = runCatching { httpDate().parse(date)?.time }.getOrNull() ?: return false
        val offset = server - clock()
        if (abs(offset) <= MAX_SKEW_MILLIS) return false
        clockOffsetMillis = offset
        return true
    }

    private fun httpDate() = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)

    /** 键在 [account]（登记时的可信会话账号）名下是否结果未知（R3-04：不按 Store 里可能过期的当前账号读）。 */
    private fun pendingUnknown(
        key: String,
        account: String?,
    ): Boolean = account != null && pendingKeys.state(key, account).stateRaw == PendingKeyStore.UNKNOWN

    /** 作废接口的结果 → 未决键的 kind。 */
    private fun abandonOutcome(result: ApiResult): String {
        val success =
            result as? ApiResult.Success ?: return if (result is ApiResult.TransportFailure) PendingKeyStore.NO_ENVELOPE else ABANDON_ERROR
        val data = success.data as? JsonObject ?: return ABANDON_ERROR
        return when (data.text("outcome")) {
            "abandoned" -> {
                PendingKeyStore.ABANDONED
            }

            "completed" -> {
                val original = data["original"] as? JsonObject
                val code = (original?.get("code") as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                if (code == null) ABANDON_ERROR else PendingKeyStore.envelopeKind(code)
            }

            else -> {
                ABANDON_ERROR
            }
        }
    }

    /**
     * 作废接口的结果 → 页面拿到的结果（A-R2-06）：abandoned → [ApiResult.Abandoned]；completed → 解包 original，按原操作
     * [original] 给出业务结果：code 0 为 [ApiResult.Success]（原响应的 data），3xxxx 为 [ApiResult.Failure]，Effect 由
     * [ErrorActionMapper] 按原操作映射（键已结束：不登记 pending_action）。其余结果原样返回（前置码只引导等）。
     */
    private fun abandonResult(
        result: ApiResult,
        original: ApiOperation,
        record: PendingKeyRecord,
    ): ApiResult {
        val key = record.key
        val success = result as? ApiResult.Success ?: return result
        val data = success.data as? JsonObject ?: return result
        return when (data.text("outcome")) {
            "abandoned" -> {
                ApiResult.Abandoned(key, success.traceId)
            }

            "completed" -> {
                val stored = data["original"] as? JsonObject ?: return result
                val code = (stored["code"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return result
                if (code == 0) return ApiResult.Success(stored["data"], success.traceId, key)
                val storedData = stored["data"] as? JsonObject ?: JsonObject(emptyMap())
                val now = context()
                val mapped =
                    ErrorActionMapper
                        .map(
                            ErrorActionInput(
                                code = code,
                                data = storedData,
                                operation = original,
                                headers = emptyMap(),
                                isReplay = false,
                                state = ErrorActionState(now.update, session(now).scopeOrFull(), identity.appVersion),
                            ),
                        ).copy(registerPending = false)
                // 统一出口的归属核对（R3-03）：作废期间换了账号，原操作的结果不触发当前会话的会话类动作
                val stale = LocalRejectReason.ACCOUNT_MISMATCH.takeIf { session(now)?.userId != record.account }
                val effect = if (stale != null && sessionBound(mapped)) staleSessionEffect(mapped) else mapped
                hooks.onEffect(effect)
                ApiResult.Failure(code, stored.text("msg").orEmpty(), storedData, success.traceId, effect, key)
            }

            else -> {
                result
            }
        }
    }

    private fun JsonObject.text(key: String): String? =
        (get(key)?.takeIf { it != JsonNull } as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        private const val REFRESH = "refreshToken"
        private const val ABANDON = "abandonIdempotencyKey"
        private const val LOGIN = "Login"
        private const val FORCE_UPDATE = "ForceUpdate"
        private const val AUTH_NONE = "none"
        private const val INVALID_SIGNATURE = 10401
        private const val MAX_SKEW_MILLIS = 300_000L
        private const val REFRESH_WAIT_MILLIS = 30_000L
        private const val OUTCOME_MEMORY = 8

        /** 2xxxx 非终结码的范围与 20903（已作废，终结）：未知键收到时统一为「暂时确认不了」（lessons 39）。 */
        private const val CLIENT_ERROR_MIN = 20000
        private const val CLIENT_ERROR_MAX = 29999
        private const val ABANDONED_CODE = 20903
        private const val CANNOT_CONFIRM = "pending_confirm.cannot_confirm"

        /** 会话类副作用的 Effect（R3-03：旧调用的响应不交付）。 */
        private val SESSION_BOUND_KINDS =
            setOf(
                ErrorEffectKind.LOGIN,
                ErrorEffectKind.BIND_PHONE,
                ErrorEffectKind.CONSENT,
                ErrorEffectKind.STEP_UP,
                ErrorEffectKind.AUTHORIZE,
                ErrorEffectKind.REAUTHORIZE,
                ErrorEffectKind.GUIDE_ONLY,
                ErrorEffectKind.NAVIGATE,
                ErrorEffectKind.REFRESH_REPLAY,
                ErrorEffectKind.REFRESH_ONLY,
                ErrorEffectKind.REGISTER_DEVICE_REPLAY,
                ErrorEffectKind.CLEAR_SESSION_LOGIN,
                ErrorEffectKind.CLEAR_SESSION_STAY_FORCE_UPDATE,
            )

        /** 读接口自动重试的传输失败：超时、断连（写出后被取消的不重试）。 */
        private val RETRIABLE_KINDS = setOf(HttpTransport.KIND_TIMEOUT, HttpTransport.KIND_DISCONNECT)

        /** 屏障刷新失败的 Effect 码：按 10404（refresh_token 失效：清会话，跳 Login）的动作。 */
        private const val SESSION_REFRESH_FAILED = 10404

        /** 作废接口没有给出可判定的结果（自身报错等）：非终结，键保持待确认。 */
        private const val ABANDON_ERROR = "envelope_abandon_unresolved"

        /** 响应为 LoginResponse（data.user_id + data.tokens）的接口。 */
        val LOGIN_OPERATIONS: Set<String> = setOf("loginBySms", "loginByWechat", "loginByApple", "loginByHuawei")

        /** 响应带令牌对与 session_scope 的接口。 */
        val TOKEN_OPERATIONS: Set<String> = LOGIN_OPERATIONS + REFRESH
    }
}
