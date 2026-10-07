package com.couli.core.bridge

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 一个能力的结果：成功带 data（缺省 {}），失败带桥错误码。 */
sealed interface BridgeOutcome {
    /**
     * [afterReply]：回复交出且在页面里执行完之后才执行的动作（APP-04 共性第 59、60 条）——会让本容器出栈、推页面或清栈的导航放这里。
     * BridgeHost 等传输层确认回复脚本已执行，再复核这次调用所属的页面世代、会话与隐私，仍有效才执行；导航本身用执行那一刻的状态
     * 重新过 Router 守卫。没有为 null。
     */
    data class Ok(
        val data: JsonObject = JsonObject(emptyMap()),
        val afterReply: (() -> Unit)? = null,
        /**
         * 这次调用自己引起了会话变化（auth.login 完成登录，APP-04 共性第 66 条）：结算时会话核对改为「此刻的登录世代仍是这次登录保存的
         * [boundSessionEpoch]」，不再要求与调用开始时同一会话——本次登录引起的会话变化不把这次调用作废；其后又变了（登出、再换号）
         * 照样不交回。没有为 null（按调用开始时的会话核对）。
         */
        val boundSessionEpoch: Long? = null,
    ) : BridgeOutcome

    data class Fail(
        val code: Int,
    ) : BridgeOutcome
}

/** 强更状态（03 §4.4 统一入口守卫的四种状态；检查中由宿主最多等 5 秒，到时按未知）。 */
object BridgeUpdateState {
    const val SUPPORTED = "supported"
    const val CHECKING = "checking"
    const val UNKNOWN = "unknown"
    const val BELOW = "below"
}

/**
 * 每次调用开始时取的调用方状态。
 * @property privacy [BridgePrivacy]。
 * @property accountKey 当前会话账号（游客、隔离中为 null），只用于判断是否已登录。
 * @property sessionEpoch 登录世代（SessionTokens.loginEpoch：每次登录、退出都加 1，刷新令牌不变）。调用的归属绑定它而不是账号
 *   （APP-04 三端共性第 15 条：同一账号退出后重新登录也是新的世代）；开始与结束时不同，结果不交回页面。
 * @property fromForceUpdatePage 容器是从强更页打开的（隐私政策等）：低于最低版本时只读方法照常（03 §4.4 状态表）。
 */
data class BridgeCallerState(
    val privacy: String,
    val accountKey: String?,
    val update: String,
    val sessionEpoch: Long = 0L,
    val fromForceUpdatePage: Boolean = false,
) {
    val loggedIn: Boolean get() = accountKey != null

    /** 与 [other] 是同一个会话（同一登录世代、同一账号）。 */
    fun sameSession(other: BridgeCallerState): Boolean = sessionEpoch == other.sessionEpoch && accountKey == other.accountKey
}

/**
 * 一次桥调用的有效性（APP-04 三端共性第 14 条）：隐私、环境（宿主世代）、会话世代、页面世代都没变。能力实现要一路带进内部链路
 * （如 PermissionRequester），在出队时、用途说明结束时、调系统接口前分别检查。
 */
fun interface BridgeCallValidity {
    fun stillValid(): Boolean

    /**
     * 整次调用的截止时刻（[System.nanoTime] 口径；APP-04 Android 第 2 轮评审 R2-01、共性第 45 条）：异步方法从调用开始按契约
     * timeout_ms 计，强更等待、HTTP、退避、恢复都在预算内。能力实现把它交给 ApiClient（CallOwnership.deadlineNanos），
     * OkHttp 的 Call 到期即被取消；到期之后 [stillValid] 为 false。同步方法没有截止时间（null）。
     */
    fun deadlineNanos(): Long? = null
}

/**
 * 业务能力（App 实现：Router、ApiClient、Tracker、PermissionRequester 等）。没有接上的能力返回 90001，不臆造实现。
 * 所有方法在主线程上调用；挂起的方法自己切线程。
 */
@Suppress("TooManyFunctions") // 一个桥方法一个入口
interface BridgeServices {
    /** app.getEnv 的 result（不含任何「审核中」「隐藏返利」类标记）。 */
    fun env(): JsonObject

    /** app.getConfig：只返回给 H5 白名单里的键。 */
    fun config(keys: List<String>): BridgeOutcome

    /**
     * auth.getUser。凡是要发 ApiRequest 的能力都拿到 [validity]（APP-04 三端共性第 22 条）：作为请求的调用归属交给 ApiClient，
     * 等待结束、重试、真正发出之前都核对；页面关闭或换了文档之后不再发出。
     */
    suspend fun user(validity: BridgeCallValidity): BridgeOutcome

    /** auth.getH5Token：[readOnly] 时只申请只读作用域（强更检查未知，BR-ID-32 细则「只读作用域」）；令牌不缓存。 */
    suspend fun h5Token(
        readOnly: Boolean,
        validity: BridgeCallValidity,
    ): BridgeOutcome

    /**
     * auth.login：打开登录页同样以桥来源经 Router（[update] 同 [navOpen]），不当成原生主动操作。
     */
    suspend fun login(update: String): BridgeOutcome

    /**
     * auth.login 的完整流程（APP-04 共性第 66 条，bridge.schema.json auth.login「resolves when the user finishes or leaves」）：
     * 打开登录页，等用户完成登录（会话已保存，logged_in = true，结果带 [BridgeOutcome.Ok.boundSessionEpoch]）或取消、离开登录页
     * （logged_in = false）之后再结算。[opening] 包住「打开登录页」这一步：只有这一步享有导航调用的作废豁免（共性第 43 条），等待期间
     * 被作废（撤回同意、切换环境、页面换文档、容器销毁）照常取消。[validity] 是等待期间的归属核对（宿主世代与隐私；不含会话——
     * 会话会因这次登录而变化）。缺省实现沿用 [login]（不等待），供还没接入登录流程的宿主与测试替身使用。
     */
    suspend fun login(
        update: String,
        validity: BridgeCallValidity,
        opening: suspend (suspend () -> BridgeOutcome) -> BridgeOutcome,
    ): BridgeOutcome = opening { login(update) }

    /** net.signedRequest：已过 signed_paths 白名单；原生签名代发。 */
    suspend fun signedRequest(
        method: String,
        path: String,
        body: JsonElement?,
        validity: BridgeCallValidity,
    ): BridgeOutcome

    /**
     * nav.open：一律以桥来源（RouteSource.BRIDGE）经 Router 的统一入口守卫（APP-04 三端共性第 33 条，03 §4.4）——基本模式只放行
     * 基本模式页面、其余拒绝（不补同意），强更未知只开只读清单，判定都在 Router 产生任何副作用之前。[update] 是宿主等待结束后
     * 的强更状态（[BridgeUpdateState]：检查中等满 5 秒为 unknown），原样交给 Router，不再按「检查中」二次等待。
     * 强更未知而目标不在只读清单：以 90500 结束（不新增码）。
     */
    suspend fun navOpen(
        route: String,
        params: JsonObject,
        update: String,
    ): BridgeOutcome

    fun clipboardWrite(text: String): BridgeOutcome

    suspend fun clipboardRead(): BridgeOutcome

    fun setClipboardAutoDetect(enabled: Boolean): BridgeOutcome

    suspend fun pushStatus(): BridgeOutcome

    /** perm.request：只走 PermissionRequester；[validity] 一路带进去，出队、说明结束、调系统接口前各查一次（第 14 条）。 */
    suspend fun requestPermission(
        type: String,
        validity: BridgeCallValidity,
    ): BridgeOutcome

    /** track.event：只接受 Tracker 生成函数白名单里的事件；不在白名单的丢弃（不报错，TODO(N-22)）。 */
    fun track(
        name: String,
        props: JsonObject,
    ): BridgeOutcome

    /** 被拒的调用记埋点 bridge_call（只记契约方法名与错误码）。 */
    fun reportRejected(
        method: String,
        code: Int,
    )
}

/** 容器自身的界面能力（TrustedWebView 实现）。 */
interface BridgeUi {
    fun toast(
        text: String,
        long: Boolean,
    )

    fun showLoading(text: String?)

    fun hideLoading()

    /** ui.setNavBar：全部参数都生效时返回 true；有本端暂不支持的参数时什么都不改、返回 false（桥回 90001，第 23 条）。 */
    fun setNavBar(params: JsonObject): Boolean

    fun close()
}

/**
 * BridgeHost（03 §5.3）：信封 → 协议准入（[BridgeProtocol.decide]）→ 能力是否在本端提供 → 强更守卫 → 分派到能力实现 → 标准响应。
 *
 * - 在途 id：与仍在处理中的调用相同的 id，后到的那次 90002、不执行（03 §5.3「信封 id」）。
 * - 本端提供的方法见 [IMPLEMENTED]；契约里有、since.android 为 null 或本任务不实现的（trade.*、share.open、media.*、ext.*、
 *   cs.open）在准入通过后返回 90001（TODO 见 [NOT_IMPLEMENTED_TASKS]）。
 * - 强更守卫（03 §4.4「未知状态下的桥调用」）：检查中最多等 5 秒按未知；未知时只读方法照常、nav.open 只开只读页面、其余 90500；
 *   低于最低版本时全部 90500（从强更页进入的页面里只读方法照常）。被拒的调用不排队、不补执行。
 * - 异步方法按契约 timeout_ms 结束（90003）。
 * - 归属（lessons 1、14、22）：每次调用开始时记下账号与宿主世代；[invalidate]（撤回同意、切换环境、容器销毁、页面换了文档）
 *   让世代加一并取消在途调用，晚到的结果一律不交回（回 90500）；会话世代变了同样不交回（第 15 条）。
 */
@Suppress("TooManyFunctions") // 入口、结算、守卫与一个方法一个分支的分派
class BridgeHost(
    private val protocol: () -> BridgeProtocol,
    private val caller: () -> BridgeCallerState,
    private val services: BridgeServices,
    private val ui: BridgeUi,
    private val awaitUpdate: suspend () -> String,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val inFlight = mutableMapOf<String, Job>()
    private var generation = 0L
    private val calls = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    /** 本端声明给 H5 的方法（`__REBATE_BRIDGE__.methods`）：契约里 since.android 不为 null 的方法。 */
    val declaredMethods: List<String>
        get() =
            BridgeContract.methods.values
                .filter { it.sinceAndroid != null }
                .map { it.name }

    val bridgeVersion: Int get() = BridgeContract.version

    /** origin 是否为当前可信源（传输层握手用；判定口径同 [BridgeProtocol.decide]）。 */
    fun acceptsOrigin(origin: String): Boolean = protocol().isTrustedOrigin(origin)

    /**
     * 处理一条 H5 消息（主线程）。[reply] 收到标准响应体；信封里没有合法 id 时无法配对，不回。
     * [origin]、[isMainFrame] 来自传输层（不是页面自报），[userGesture] 来自原生记录的触摸。
     */
    @Suppress("ReturnCount", "LongParameterList")
    fun handle(
        raw: String,
        origin: String,
        isMainFrame: Boolean,
        userGesture: Boolean,
        /**
         * 交出带 [BridgeOutcome.Ok.afterReply] 的回复：把回复交给页面，结束时调一次 settled——拿到「脚本已在页面里执行完」的确认为
         * settled(true)，等待超时、没有执行、文档已换为 settled(false)（共性第 59、62 条：只有 true 才导航，false 放弃这次导航）。
         * 缺省（单测、没有执行确认的宿主）= 交给 [reply] 后立即算执行完。
         */
        deliver: ((text: String, settled: (executed: Boolean) -> Unit) -> Unit)? = null,
        reply: (String) -> Unit,
    ) {
        val envelope = BridgeProtocol.parseStructure(raw)
        val id = envelope.id ?: return
        val method = envelope.method
        if (!envelope.structureValid || method == null) return reply(BridgeProtocol.response(id, BridgeCode.INVALID_PARAMS))
        val state = caller()
        val params = envelope.params ?: JsonObject(emptyMap())
        val ctx = BridgeContext(method, origin, isMainFrame, userGesture, state.privacy, state.loggedIn, params)
        val decision = protocol().decide(ctx)
        if (!decision.allow) return reject(id, method, decision.errorCode ?: BridgeCode.NOT_ALLOWED, reply)
        val contract = BridgeContract.methods.getValue(method)
        if (contract.sinceAndroid == null || method !in IMPLEMENTED) return reject(id, method, BridgeCode.NOT_SUPPORTED, reply)
        start(id, contract, params, state, Reply(reply, deliver))
    }

    /**
     * 撤回同意、切换环境、容器销毁或页面换了文档：作废并取消全部在途调用。
     * 例外（APP-04 三端共性第 43 条）：正在执行、由它自己触发了这次导航的那一次调用（nav.open、auth.login、nav.close 正在推页面 /
     * 清栈 / 关闭本容器）不取消，照常按结果结算后才算结束；其余在途调用照常作废。
     */
    fun invalidate() {
        val cancelled =
            synchronized(lock) {
                generation += 1
                exempt = navigating
                val jobs = inFlight.values.filter { it !== navigating }
                inFlight.values.removeAll { it !== navigating }
                jobs
            }
        cancelled.forEach { it.cancel() }
    }

    /** 容器销毁：取消全部调用，之后不再接受新调用（正在触发导航的那次调用先结算，第 43 条）。 */
    fun dispose() {
        invalidate()
        val pending = synchronized(lock) { navigating }
        if (pending == null) calls.cancel() else pending.invokeOnCompletion { calls.cancel() }
    }

    /** 正在执行、会触发导航的调用（[navigation]）。 */
    private var navigating: Job? = null

    /**
     * 触发过导航、还没结束（结算并回给页面之前）的调用（共性第 53 条）。与 [navigating] 不同：它在调用的协程完全结束时才移除，
     * 能力返回之后、结算之前（异步方法要从超时作用域恢复，可能隔一条主线程消息）也算在内。
     */
    private val closing = mutableSetOf<Job>()

    /**
     * 等触发导航的那次调用（nav.open 清栈、nav.close、auth.login）结算并交出回复之后再执行 [action]（共性第 53 条）：容器的破坏性
     * 拆除（拆传输层、销毁 WebView）放在这里，关闭本容器的那次调用的回复不会因为传输层先拆掉而丢失。没有这样的调用时立即执行；
     * 否则在最后一个这样的调用结束的线程上执行（调用跑在宿主的作用域里，App 里是主线程）。
     */
    fun afterNavigationSettled(action: () -> Unit) {
        val pending = synchronized(lock) { closing.toList() }
        if (pending.isEmpty()) return action()
        val remaining =
            java.util.concurrent.atomic
                .AtomicInteger(pending.size)
        pending.forEach { job -> job.invokeOnCompletion { if (remaining.decrementAndGet() == 0) action() } }
    }

    /** 作废时被豁免的那次导航调用：它的结果照常交回（第 43 条）。 */
    private var exempt: Job? = null

    /** 执行一次会触发导航（可能让本容器出栈）的能力调用：执行期间被作废也先结算本次结果。 */
    private suspend fun navigation(
        self: Job,
        action: suspend () -> BridgeOutcome,
    ): BridgeOutcome {
        synchronized(lock) {
            navigating = self
            if (closing.add(self)) self.invokeOnCompletion { synchronized(lock) { closing.remove(self) } }
        }
        try {
            return action()
        } finally {
            synchronized(lock) { if (navigating === self) navigating = null }
        }
    }

    private fun reject(
        id: String,
        method: String,
        code: Int,
        reply: (String) -> Unit,
    ) {
        if (method in BridgeContract.methods) services.reportRejected(method, code)
        reply(BridgeProtocol.response(id, code))
    }

    private fun start(
        id: String,
        contract: BridgeMethodContract,
        params: JsonObject,
        state: BridgeCallerState,
        replies: Reply,
    ) {
        val reply = replies.reply
        var startGeneration = 0L
        val job =
            calls.launch(start = CoroutineStart.LAZY) {
                var outcome: BridgeOutcome? = null
                try {
                    outcome = run(contract, params, state, startGeneration)
                } finally {
                    // 被取消（invalidate）的调用 outcome 为 null：同样回 90500，不让页面一直等
                    settle(id, coroutineContext.job, Owner(contract.name, startGeneration, state), outcome, replies)
                }
            }
        val accepted =
            synchronized(lock) {
                if (id in inFlight) {
                    false
                } else {
                    inFlight[id] = job
                    startGeneration = generation
                    true
                }
            }
        if (!accepted) {
            job.cancel()
            reply(BridgeProtocol.response(id, BridgeCode.INVALID_PARAMS))
            return
        }
        job.start()
    }

    /** 结算一次调用：世代与账号都没变才交回结果，否则 90500（不执行的结果不补交）。 */
    private fun settle(
        id: String,
        self: Job,
        owner: Owner,
        outcome: BridgeOutcome?,
        replies: Reply,
    ) {
        val reply = replies.reply
        val owned =
            synchronized(lock) {
                // 只移除自己的登记：invalidate 之后同一个 id 可能已被新的调用占用
                val mine = inFlight[id] === self
                if (mine) inFlight.remove(id)
                mine && (generation == owner.generation || self === exempt)
            } && sessionHolds(owner, outcome)
        val after = (outcome as? BridgeOutcome.Ok)?.afterReply
        when {
            !owned || outcome == null -> {
                reply(BridgeProtocol.response(id, BridgeCode.NATIVE_ERROR))
            }

            outcome is BridgeOutcome.Ok && after != null -> {
                replyThenNavigate(
                    BridgeProtocol.response(id, BridgeCode.OK, outcome.data),
                    owner,
                    after,
                    replies,
                )
            }

            outcome is BridgeOutcome.Ok -> {
                reply(BridgeProtocol.response(id, BridgeCode.OK, outcome.data))
            }

            outcome is BridgeOutcome.Fail -> {
                reject(id, owner.method, outcome.code, reply)
            }
        }
    }

    /**
     * 结算时的会话核对：通常要求仍是调用开始时的会话；结果自带 [BridgeOutcome.Ok.boundSessionEpoch]（这次调用自己完成了登录）时，
     * 要求此刻的登录世代仍是那次登录保存的世代（共性第 66 条）。
     */
    private fun sessionHolds(
        owner: Owner,
        outcome: BridgeOutcome?,
    ): Boolean {
        val bound = (outcome as? BridgeOutcome.Ok)?.boundSessionEpoch
        val now = caller()
        return if (bound != null) now.sessionEpoch == bound && now.privacy == owner.state.privacy else now.sameSession(owner.state)
    }

    /**
     * 共性第 59、60、62 条：先交出回复，等它在页面里执行完，再复核这次调用所属的宿主世代（撤回同意、切换环境、页面换文档、容器出栈都会
     * 推进它）、会话与隐私，仍有效才执行导航；导航本身由 [after] 用当时的状态重新过 Router 守卫。结束只算一次：传输层报「没有执行」
     * （回复脚本执行超时等）时放弃这次导航，不当作执行成功。
     */
    private fun replyThenNavigate(
        text: String,
        owner: Owner,
        after: () -> Unit,
        replies: Reply,
    ) {
        val done =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val settled = { executed: Boolean ->
            if (done.compareAndSet(false, true) && executed && stillCurrent(owner)) after()
        }
        val channel = replies.deliver
        if (channel == null) {
            replies.reply(text)
            settled(true)
        } else {
            channel(text, settled)
        }
    }

    /** 回复执行完之后的复核：宿主世代没变（没有被作废）、会话与隐私仍是调用开始时的。 */
    private fun stillCurrent(owner: Owner): Boolean {
        val sameGeneration = synchronized(lock) { generation == owner.generation }
        val now = caller()
        return sameGeneration && now.privacy == owner.state.privacy && now.sameSession(owner.state)
    }

    /** 一次调用的回复通道：[reply] 交出回复；[deliver] 交出并在结束时报告是否已在页面里执行完（共性第 59、62 条），没有为 null。 */
    private class Reply(
        val reply: (String) -> Unit,
        val deliver: ((String, (Boolean) -> Unit) -> Unit)?,
    )

    /** 一次调用的归属：方法名、开始时的宿主世代与调用方状态。 */
    private class Owner(
        val method: String,
        val generation: Long,
        val state: BridgeCallerState,
    )

    /**
     * 执行一次调用。异步方法（R2-01、共性第 45 条）有统一的截止时间：从这里开始按契约 timeout_ms 计，强更等待、能力执行（HTTP、
     * 退避、恢复）都在预算内。到期时：
     * - 调用的有效性立即撤销（[BridgeCallValidity.stillValid] 为 false），截止时刻同时交给 ApiClient，OkHttp 的 Call 到期即被取消，
     *   之后不再退避、不再续发；
     * - 原生立即以 90003 结算，不等阻塞中的能力实现返回（能力在单独的子协程里执行，到期只取消它，不等它结束）。
     */
    @Suppress("ReturnCount") // 同步方法、截止时间内、到期三个出口
    private suspend fun run(
        contract: BridgeMethodContract,
        params: JsonObject,
        state: BridgeCallerState,
        startGeneration: Long,
    ): BridgeOutcome {
        val timeout = contract.timeoutMs?.takeIf { contract.model == ASYNC }
        val deadline = timeout?.let { System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(it) }
        val expired = AtomicBoolean(false)
        val validity =
            object : BridgeCallValidity {
                override fun stillValid(): Boolean =
                    !expired.get() && (deadline == null || System.nanoTime() - deadline < 0) && stillOwned(startGeneration, state)

                override fun deadlineNanos(): Long? = deadline
            }
        // 这次调用自己的协程（launch 的那一个，不是超时作用域里的子协程）：第 43 条按它豁免
        val self = currentCoroutineContext().job
        if (timeout == null) return guarded(contract, params, state, startGeneration, validity, self)
        val work = CoroutineScope(currentCoroutineContext()).async { guarded(contract, params, state, startGeneration, validity, self) }
        val outcome = withTimeoutOrNull(timeout) { work.await() }
        if (outcome != null) return outcome
        expired.set(true)
        work.cancel()
        return BridgeOutcome.Fail(BridgeCode.TIMEOUT)
    }

    /** 强更等待 → 归属核对 → 强更守卫 → 分派。 */
    @Suppress("LongParameterList", "ReturnCount") // 归属失效、强更守卫、执行三个出口
    private suspend fun guarded(
        contract: BridgeMethodContract,
        params: JsonObject,
        state: BridgeCallerState,
        startGeneration: Long,
        validity: BridgeCallValidity,
        self: Job,
    ): BridgeOutcome {
        val update = if (state.update == BridgeUpdateState.CHECKING) awaitUpdate() else state.update
        // 等待之后、执行任何副作用之前再核对一次归属（APP-04 三端共性第 4 条）：宿主世代（撤回同意、切换环境、页面换文档、容器销毁）、
        // 隐私状态与账号都没变才执行；变了就不执行，以 90500 结束（不排队、不补执行）
        if (!stillOwned(startGeneration, state)) return BridgeOutcome.Fail(BridgeCode.NATIVE_ERROR)
        val guard = updateGuard(contract.name, update, state.fromForceUpdatePage)
        if (guard != null) return BridgeOutcome.Fail(guard)
        if (contract.name == AUTH_LOGIN) {
            // auth.login 等到登录页结束才结算（共性第 66 条）：等待期间核对宿主世代、隐私与截止时间，不核对会话（会因这次登录而变化）
            val waiting =
                BridgeCallValidity {
                    validity.deadlineNanos().let { it == null || System.nanoTime() - it < 0 } &&
                        synchronized(lock) { generation == startGeneration } &&
                        caller().privacy == state.privacy
                }
            return services.login(update, waiting) { step -> navigation(self, step) }
        }
        return dispatch(contract.name, params, update, validity, self)
    }

    private fun stillOwned(
        startGeneration: Long,
        state: BridgeCallerState,
    ): Boolean {
        val sameGeneration = synchronized(lock) { generation == startGeneration }
        val now = caller()
        return sameGeneration && now.privacy == state.privacy && now.sameSession(state)
    }

    /** 强更守卫（03 §4.4）：返回不执行时的错误码；照常执行为 null。 */
    private fun updateGuard(
        method: String,
        update: String,
        fromForceUpdatePage: Boolean,
    ): Int? =
        when (update) {
            BridgeUpdateState.SUPPORTED -> null

            BridgeUpdateState.BELOW -> if (fromForceUpdatePage && method in READ_ONLY_METHODS) null else BridgeCode.NATIVE_ERROR

            // 未知（含检查中等满 5 秒）：只读方法照常，打开页面类只开只读页面（在分派里），其余 90500
            else -> if (method in READ_ONLY_METHODS || method in OPEN_PAGE_METHODS) null else BridgeCode.NATIVE_ERROR
        }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // 一个方法一个分支
    private suspend fun dispatch(
        method: String,
        params: JsonObject,
        update: String,
        validity: BridgeCallValidity,
        self: Job,
    ): BridgeOutcome {
        val readOnlyMode = update != BridgeUpdateState.SUPPORTED
        return when (method) {
            "app.getEnv" -> {
                BridgeOutcome.Ok(services.env())
            }

            "app.getConfig" -> {
                services.config(params.strings("keys"))
            }

            "auth.getUser" -> {
                services.user(validity)
            }

            "auth.getH5Token" -> {
                services.h5Token(readOnly = readOnlyMode, validity = validity)
            }

            "net.signedRequest" -> {
                services.signedRequest(params.text("method").orEmpty(), params.text("path").orEmpty(), params["body"], validity)
            }

            "ui.toast" -> {
                ok { ui.toast(params.text("text").orEmpty(), params.text("duration") == "long") }
            }

            "ui.showLoading" -> {
                ok { ui.showLoading(params.text("text")) }
            }

            "ui.hideLoading" -> {
                ok { ui.hideLoading() }
            }

            "ui.setNavBar" -> {
                if (ui.setNavBar(params)) BridgeOutcome.Ok() else BridgeOutcome.Fail(BridgeCode.NOT_SUPPORTED)
            }

            "nav.open" -> {
                val target = params["params"] as? JsonObject ?: JsonObject(emptyMap())
                navigation(self) { services.navOpen(params.text("route").orEmpty(), target, update) }
            }

            "nav.close" -> {
                // 关闭本容器：回复在页面里执行完、复核归属之后才关闭（共性第 59、60 条）
                navigation(self) { BridgeOutcome.Ok(afterReply = ui::close) }
            }

            "clipboard.write" -> {
                services.clipboardWrite(params.text("text").orEmpty())
            }

            "clipboard.read" -> {
                services.clipboardRead()
            }

            "clipboard.setAutoDetect" -> {
                services.setClipboardAutoDetect((params["enabled"] as? JsonPrimitive)?.content == "true")
            }

            "perm.getPushStatus" -> {
                services.pushStatus()
            }

            "perm.request" -> {
                services.requestPermission(params.text("type").orEmpty(), validity)
            }

            "track.event" -> {
                services.track(params.text("name").orEmpty(), params["props"] as? JsonObject ?: JsonObject(emptyMap()))
            }

            else -> {
                BridgeOutcome.Fail(BridgeCode.NOT_SUPPORTED)
            }
        }
    }

    private inline fun ok(action: () -> Unit): BridgeOutcome {
        action()
        return BridgeOutcome.Ok()
    }

    companion object {
        private const val ASYNC = "async"
        private const val AUTH_LOGIN = "auth.login"

        /** 本任务实现的方法（APP-04 任务书 §2.2）。 */
        val IMPLEMENTED: Set<String> =
            setOf(
                "app.getEnv",
                "app.getConfig",
                "auth.getUser",
                "auth.getH5Token",
                "auth.login",
                "net.signedRequest",
                "ui.toast",
                "ui.showLoading",
                "ui.hideLoading",
                "ui.setNavBar",
                "nav.open",
                "nav.close",
                "clipboard.write",
                "clipboard.read",
                "clipboard.setAutoDetect",
                "perm.getPushStatus",
                "perm.request",
                "track.event",
            )

        /**
         * 本任务不实现、返回 90001 的方法与负责的任务（不臆造实现）：
         * TODO(APP-07) trade.openProduct、trade.convertAndOpen、trade.authorize、ext.openApp（TradeJump 与授权）；
         * TODO(APP-13) share.open、media.saveImage、media.previewImage（分享与图片）；TODO(APP-14) ext.openBrowser、cs.open；
         * P1：trade.openUnionActivity、ext.openMiniProgram、media.scan、media.uploadImage（since 全为 null，不声明）。
         */
        val NOT_IMPLEMENTED_TASKS: Map<String, String> =
            mapOf(
                "trade.openProduct" to "APP-07",
                "trade.convertAndOpen" to "APP-07",
                "trade.authorize" to "APP-07",
                "ext.openApp" to "APP-07",
                "share.open" to "APP-13",
                "media.saveImage" to "APP-13",
                "media.previewImage" to "APP-13",
                "ext.openBrowser" to "APP-14",
                "cs.open" to "APP-14",
            )

        /** 强更未知时照常执行的只读方法（03 §4.4「未知状态下的桥调用」第一类）。 */
        val READ_ONLY_METHODS: Set<String> =
            setOf(
                "app.getEnv",
                "app.getConfig",
                "auth.getUser",
                "auth.getH5Token",
                "ui.toast",
                "ui.showLoading",
                "ui.hideLoading",
                "ui.setNavBar",
                "nav.close",
                "media.previewImage",
                "clipboard.write",
                "clipboard.read",
                "clipboard.setAutoDetect",
                "perm.getPushStatus",
                "track.event",
            )

        /** 第二类：只打开只读页面。 */
        val OPEN_PAGE_METHODS: Set<String> = setOf("nav.open", "trade.openProduct")

        private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.strings(key: String): List<String> =
            (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
    }
}
