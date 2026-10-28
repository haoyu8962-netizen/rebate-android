package com.couli.core.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 可信 WebView 看到的会话身份（F-X-25；取自 ApiClient.sessionIdentity 的同一份快照）。[known] 为 false 表示此刻读不出（凭据门关闭、
 * 读取失败），不等于没有会话；[account] 为 null 是游客。[loginEpoch] 是本进程的登录世代。
 */
data class WebAuthIdentity(
    val known: Boolean,
    val account: String?,
    val loginEpoch: Long,
)

/** 一次身份变化对可信页面的动作：不动、只通知（A）、通知并拆旧页换存储再建（A+B）。 */
enum class WebAuthAction { NONE, NOTIFY, NOTIFY_RELOAD }

/**
 * 触发判定（F-X-25 三端口径，vectors.md D01–D11）：任一侧未知不动（冷启动未知→已知、暂时读不出都不是身份事件）；账号变化（含登录、
 * 登出）A+B；同账号只换登录世代只做 A；游客到游客、仅刷新令牌（账号与世代都不变）不动。scope 不参与。
 */
object WebAuthChange {
    fun decide(
        previous: WebAuthIdentity,
        next: WebAuthIdentity,
    ): WebAuthAction =
        when {
            !previous.known || !next.known -> WebAuthAction.NONE
            previous.account != next.account -> WebAuthAction.NOTIFY_RELOAD
            previous.account == null -> WebAuthAction.NONE
            previous.loginEpoch != next.loginEpoch -> WebAuthAction.NOTIFY
            else -> WebAuthAction.NONE
        }
}

/**
 * 「Web 数据待清理」的持久事务（I3）。[readPending] 为 null 只表示可靠地读到「没有标记」；读不出、损坏必须抛错。[markPending] /
 * [clearPending] 失败返回 false 或抛错。标记只存旧身份的归属，不存任何令牌。
 */
interface WebDataJournal {
    fun readPending(): WebAuthIdentity?

    fun markPending(owner: WebAuthIdentity): Boolean

    fun clearPending(): Boolean
}

/** 可信 Web 数据的归属（只存账号与持久化登录世代分区，不存令牌）。比较只看 [account]；null 为游客。 */
data class WebDataOwner(
    val account: String?,
    val partition: String? = null,
)

/**
 * 「可信 Web 数据归属」的持久记录（S0-02 启动基线、S1-01 崩溃窗口兜底）。[read] 的 null = 可靠读到「没有记录」（首次安装）；
 * 读不出必须抛错（按不符处理）；[write] 失败返回 false 或抛错。
 */
interface WebDataOwnerRecord {
    fun read(): WebDataOwner?

    fun write(owner: WebDataOwner): Boolean
}

/**
 * 协调器登记的一个可信页面槽位（跨多次 rebuild 的同一个页；实现：feature:web 的 TrustedWebPage）。
 * - [isolate]：同步进入隔离（任何线程）——桥调用一律拒绝、不交付新的文档令牌、拦截导航、新加载与全部子资源请求；
 * - [notifyAuthChanged]：专用通道把 auth.changed 交给当前文档，[completed] 的 true 表示事件脚本已在该文档里执行；
 * - [retire]：拆掉当前 WebView（stopLoading → about:blank 提交 → 下树 → 传输层 dispose → destroy），true 只在确认销毁后给；
 *   没有 WebView 的（从未加载、已拆）立即 true。期间槽位强持有 WebView；
 * - [rebuild]：在存储 [store] 上新建 WebView 与新控制器并加载入口，true = 新文档已提交且令牌已绑定；
 * - [releaseAfterRebuild]：协调器确认后解除隔离（只对 rebuild 出的新文档）。
 */
interface TrustedAuthDocument {
    val currentTrusted: Boolean
    val tokenDelivered: Boolean
    val destroyed: Boolean

    fun isolate()

    fun notifyAuthChanged(
        data: JsonObject,
        completed: (Boolean) -> Unit,
    )

    fun retire(completed: (Boolean) -> Unit)

    fun rebuild(
        store: String,
        completed: (Boolean) -> Unit,
    )

    /**
     * 协调器在锁内选定本页为 rebuild 目标的同一时刻调用（评审 v2 r2 S1-01）：实现在此取「判定序号」作比较基准，随后的 [rebuild] 若发现
     * 基准之后又有判定，就补隔离、不加载。缺省什么都不做。
     */
    fun beginRebuild() = Unit

    fun releaseAfterRebuild()
}

/**
 * 切号时可信 WebView 的通知、拆除、换存储与重建（F-X-25 v2，D10-309 / D10-310；不变式 I1–I7 见 NATIVE/F-X-25/invariants.md）。
 * 一个 App 一份，所有可信页面槽位 [attach] 进来。
 *
 * 单一闸门：状态只有一份（锁内），「要不要隔离」只看两件事——身份还没和归属记录比对过（[confirmed] 为 null），或存在待清理的 Web 数据
 * （[dirty]）；不按入口路径分支。
 * - 启动基线（S0-02）：不需要手动基线。构造时读持久的待清标记与 Web 数据归属记录；进程内第一次拿到已知身份（[observe] 或
 *   [sessionCleared]）时与归属比对：一致 → 不发 A、不拆，把等待中的页在当前存储上建好；不符 / 读不出 / 有待清标记 → 进事务（不发 A，
 *   此时没有已加载的文档）；没有记录（首次安装）→ 写入当前身份。比对完成前登记的页一律隔离、不加载；比对等满 [timeoutMillis] 仍未
 *   完成按失败结算（[METRIC_IDENTITY_UNKNOWN]，D10-310 (4)），[retry] 重新等待。
 * - I1：账号变化、登出、明确清除时，在调用线程的同一同步段里依次隔离全部页面、把 A 入队、持久写入待清标记；之后异步一轮：
 *   A（每页限时 [timeoutMillis]，无论成败都继续，D10-310 (1)）→ 拆除全部登记页并等销毁确认 → [TrustedWebStore.rotate] →
 *   写归属记录 → 删标记 → 逐页 [TrustedAuthDocument.rebuild] 在新存储上新建，成功的逐页解除。旧文档上不做任何清理。
 * - I2：拆除、换存储、写归属、删标记、新建每一步都要真实的成功信号；false、抛错、超时让本轮失败，保持隔离、标记保留、不建新页；
 *   迟到的信号属于旧的尝试，不算数。rebuild 失败后 [retry] 重跑整事务（拆半成品 → 再换存储 → 再建）。
 * - I3：标记先于任何异步；构造时读到标记（或读不出）即按待清理，首个已知身份到来时补完同一事务。[beforeSessionSave] 在写新会话前
 *   先写标记（S1-01）；即使漏了，冷启动归属比对兜底。
 * - I4：事务中、失败后新登记的页 attach 即隔离，与其他页同一轮在新存储上建好；关闭一个页不影响其他页。
 * - I5：[sessionCleared] 不读凭据，直接登记待清理。
 * - I6：只经注入的 [TrustedWebStore]（P：可信配置文件；F：默认配置文件实例级接口）；外部容器不在这里。
 * - I7：A 只发给仍可信、已交付当前文档令牌、未销毁的文档，载荷只有 logged_in；隔离不影响这条专用投递（在拆之前发）。
 *
 * 执行模型：待办（待投递的 A、待清理、待重建）都记在状态里；每次有新判定就作废当前一轮、按最新状态重跑一轮（[epoch]）。
 */
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList") // 判定、登记、重试、结算与一轮里的各步逐项一个入口；端口逐个注入
class TrustedAuthSync(
    private val scope: CoroutineScope,
    private val journal: WebDataJournal,
    private val owner: WebDataOwnerRecord,
    private val store: TrustedWebStore,
    private val timeoutMillis: Long,
    private val metric: (String) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val entries = LinkedHashSet<Entry>()
    private val notices = mutableListOf<Notice>()
    private val waiters = mutableListOf<(Boolean) -> Unit>()

    /** 最近一次确认的身份（未知快照不覆盖，D08）；null = 本进程还没和归属记录比对过。 */
    private var confirmed: WebAuthIdentity? = null

    /** 启动时读到的归属记录（[OwnerAtStart.unreadable] = 读不出，按不符）。 */
    private val recorded: OwnerAtStart

    /** 存在待清理的 Web 数据（持久标记已写或该写）：为真时所有登记的页面都隔离。 */
    private var dirty = false

    /** 持久标记已确认写入（写失败时为 false，下一轮先补写）。 */
    private var marked = false

    /** 待清数据的归属（写进持久标记）。 */
    private var markOwner = UNKNOWN

    /** 上一轮以失败结束（或身份比对等满上限）：结算立即给 false，等 [retry] 或新的判定。 */
    private var failed = false

    /** 身份比对等满上限（D10-310 (4)）：之后即使身份到来也不自动加载，等 [retry]。 */
    private var identityStalled = false
    private var identityWait: Job? = null
    private var kick: Job? = null
    private var epoch = 0L
    private var worker: Job? = null
    private var closed = false

    private class Entry(
        val document: TrustedAuthDocument,
    ) {
        var needsRebuild = false
        var closed = false
    }

    /** 一次待投递的 A：[targets] 是判定那一刻已登记的页面。 */
    private class Notice(
        val payload: JsonObject,
        val targets: MutableSet<Entry>,
    )

    private class OwnerAtStart(
        val owner: WebDataOwner?,
        val unreadable: Boolean,
    )

    init {
        val pending = readPendingAtStartup()
        recorded = readOwnerAtStartup()
        synchronized(lock) {
            if (pending == null || pending.owner != null) {
                // 读到标记，或标记读不出、损坏：都不能当成「没有待清理」（I3）；读不出时归属未知，本轮先补写标记
                dirty = true
                marked = pending != null
                markOwner = pending?.owner ?: UNKNOWN
            }
        }
        // 冷启动：删掉上次留下的退役存储（P：旧配置文件；此时没有 WebView 用它）。删不掉不阻塞，只记埋点（D10-310 (5)）
        discardRetired()
    }

    /** 启动时读持久标记：可靠读到为 [Startup]（owner 为 null = 确实没有标记），读不出、损坏为 null。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 任何读取异常都按「读不出」，由调用方按有待清理处理（fail-closed）
    private fun readPendingAtStartup(): Startup? =
        try {
            Startup(journal.readPending())
        } catch (error: Exception) {
            null
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 归属读不出按「不符」（fail-closed）
    private fun readOwnerAtStartup(): OwnerAtStart =
        try {
            OwnerAtStart(owner.read(), unreadable = false)
        } catch (error: Exception) {
            OwnerAtStart(null, unreadable = true)
        }

    private class Startup(
        val owner: WebAuthIdentity?,
    )

    /**
     * 登记一个可信页面槽位（建好、首次加载之前调用）。身份尚未比对或存在待清理时在返回前把它隔离（不加载），之后由本类在当前存储上建好
     * （I1、I3、I4）。返回值关闭即注销这个页（只影响它自己）。
     */
    fun attach(document: TrustedAuthDocument): AutoCloseable {
        val entry = Entry(document)
        synchronized(lock) {
            if (closed) return AutoCloseable {}
            entries += entry
            if (dirty || confirmed == null) {
                entry.needsRebuild = true
                document.isolate()
            }
            if (confirmed == null) startIdentityWaitLocked()
        }
        return AutoCloseable { detach(entry) }
    }

    private fun detach(entry: Entry) {
        synchronized(lock) {
            if (entry.closed) return
            entry.closed = true
            entries -= entry
            notices.forEach { it.targets -= entry }
            notices.removeAll { it.targets.isEmpty() }
        }
    }

    /** 新的身份快照（会话保存、刷新、清除之后）。在调用线程同步判定；需要 B 时同步隔离全部页面并写标记（I1）。 */
    fun observe(identity: WebAuthIdentity) {
        val start =
            synchronized(lock) {
                if (closed || !identity.known) return
                val previous = confirmed ?: return@synchronized firstIdentityLocked(identity, explicitClear = false)
                confirmed = identity
                when (WebAuthChange.decide(previous, identity)) {
                    WebAuthAction.NONE -> {
                        null
                    }

                    WebAuthAction.NOTIFY -> {
                        enqueueNoticeLocked(identity.account != null)
                        restartLocked()
                    }

                    WebAuthAction.NOTIFY_RELOAD -> {
                        beginTransactionLocked(previous, loggedIn = identity.account != null, notify = true)
                    }
                }
            }
        start?.start()
    }

    /**
     * 明确清除了会话（退出登录、注销、切换环境、撤回同意、10404、刷新被拒等，I5）：不读凭据，直接登记待清理并隔离，按登出处理。
     * 已确认是游客时不重复处理（那次登出已登记过）。[reason] 只用于说明来源。
     */
    @Suppress("UnusedParameter") // reason 是调用处的来源说明，判定不看它（任何明确清除一视同仁）
    fun sessionCleared(reason: String) {
        val start =
            synchronized(lock) {
                if (closed) return
                val guest = WebAuthIdentity(true, null, confirmed?.loginEpoch ?: 0L)
                val previous = confirmed ?: return@synchronized firstIdentityLocked(guest, explicitClear = true)
                if (previous.account == null) return
                confirmed = guest
                beginTransactionLocked(previous, loggedIn = false, notify = true)
            }
        start?.start()
    }

    /**
     * 保存新会话之前（S1-01，ApiClient 在写新令牌前同步调用）：账号与当前身份（尚未比对时与归属记录）相同且没有待清时返回 true、什么都不做；
     * 否则作废正在跑的一轮、同步隔离全部页面并持久写入待清标记，返回写标记的结果（写失败如实 false，隔离照样生效）。不启动新一轮：随后的 [observe] 接着做；
     * 万一没有后续（会话没存成），[timeoutMillis] 后自行开始补清。
     */
    fun beforeSessionSave(account: String?): Boolean =
        synchronized(lock) {
            if (closed) return true
            val known = confirmed
            val same =
                when {
                    known != null -> known.account == account
                    recorded.unreadable -> false
                    else -> recorded.owner != null && recorded.owner.account == account
                }
            if (same && !dirty) return true
            // 同账号重新保存（已有待清在跑）：不是新的身份变化，不作废在跑的一轮，只确保标记已写（评审 v2 r2 S2-4）
            if (same) {
                if (!marked) marked = markQuietly(markOwner)
                return marked
            }
            // 作废正在跑的一轮（不启动新一轮）：它不能再走到删标记 / 解除，否则会删掉这里刚写的、比它新的标记（评审 v2 r1 S2-01）
            epoch += 1
            worker?.cancel()
            worker = null
            dirty = true
            failed = false
            isolateAllLocked()
            markOwner = known ?: WebAuthIdentity(!recorded.unreadable, recorded.owner?.account, 0L)
            marked = markQuietly(markOwner)
            scheduleKickLocked()
            marked
        }

    /** 失败后重试（错误层的【重试】）：身份未比对的重新等待；有未完成的待办且没有在跑的一轮时重跑整事务；正在跑时不打断。 */
    @Suppress("ReturnCount") // 已关闭、等身份比对、无事可做逐项返回
    fun retry() {
        val start =
            synchronized(lock) {
                if (closed) return
                identityStalled = false
                if (confirmed == null) {
                    failed = false
                    startIdentityWaitLocked()
                    return
                }
                if (worker != null || !outstandingLocked()) return
                restartLocked()
            }
        start?.start()
    }

    /**
     * 当前事务真正结算后回调一次：正在跑（含等身份比对、等事务开始）时等它结束；否则立即给出——没有任何待办为 true，有失败遗留的待办
     * 为 false。回调可能在任意线程（结算所在线程）上执行。
     */
    fun whenSettled(completed: (Boolean) -> Unit) {
        val now =
            synchronized(lock) {
                when {
                    closed -> {
                        false
                    }

                    worker != null || (confirmed == null && !identityStalled) -> {
                        waiters += completed
                        if (confirmed == null) startIdentityWaitLocked()
                        return
                    }

                    outstandingLocked() && !failed -> {
                        // 已登记待清、事务还没开始（保存新会话之前写了标记，等随后的身份快照）
                        waiters += completed
                        scheduleKickLocked()
                        return
                    }

                    else -> {
                        !outstandingLocked()
                    }
                }
            }
        completed(now)
    }

    /** 取消本协调器的在途工作；不清持久待清责任（下次启动照样补清）。 */
    override fun close() {
        val dropped =
            synchronized(lock) {
                if (closed) return
                closed = true
                epoch += 1
                worker?.cancel()
                worker = null
                identityWait?.cancel()
                identityWait = null
                kick?.cancel()
                kick = null
                entries.clear()
                notices.clear()
                drainWaitersLocked()
            }
        dropped.forEach { it(false) }
    }

    // ---- 判定后的状态变更（都在锁内，由调用方在锁外启动返回的那一轮）

    /** 进程内第一次已知身份：与归属记录比对（S0-02），不发 A。 */
    private fun firstIdentityLocked(
        identity: WebAuthIdentity,
        explicitClear: Boolean,
    ): Job? {
        identityWait?.cancel()
        identityWait = null
        confirmed = identity
        val ownerRecord = recorded.owner
        val mismatch =
            explicitClear || recorded.unreadable || (ownerRecord != null && ownerRecord.account != identity.account)
        val firstInstall = !recorded.unreadable && ownerRecord == null && !explicitClear
        return when {
            dirty || mismatch -> {
                beginTransactionLocked(markOwner.takeIf { dirty } ?: ownerIdentity(), loggedIn = false, notify = false)
            }

            firstInstall && !writeOwnerQuietly(identity) -> {
                // 首次安装写不进归属：下次启动无从比对，按待清理处理（fail-closed）
                beginTransactionLocked(ownerIdentity(), loggedIn = false, notify = false)
            }

            else -> {
                startRebuildLocked()
            }
        }
    }

    private fun ownerIdentity(): WebAuthIdentity = WebAuthIdentity(!recorded.unreadable, recorded.owner?.account, 0L)

    /** 与归属一致：把等待中的页在当前存储上建好；没有要建的就结算等待者。 */
    @Suppress("ReturnCount") // 有页要建、比对已等满上限、只结算三个出口
    private fun startRebuildLocked(): Job? {
        if (entries.any { it.needsRebuild && !it.closed } && !identityStalled) return restartLocked()
        if (identityStalled) return null
        val settled = drainWaitersLocked()
        val result = !outstandingLocked()
        if (settled.isNotEmpty()) scope.launch { settled.forEach { it(result) } }
        return null
    }

    private fun beginTransactionLocked(
        previous: WebAuthIdentity,
        loggedIn: Boolean,
        notify: Boolean,
    ): Job? {
        dirty = true
        failed = false
        // I1：先隔离全部已登记的页面（同一个同步步骤，任何持久 I/O 与异步之前）
        isolateAllLocked()
        if (notify) enqueueNoticeLocked(loggedIn)
        // I3：再持久写入待清理（归属旧身份）；写不进时保持隔离，本轮先补写，写成之前不拆不换
        markOwner = previous
        marked = markQuietly(previous)
        if (identityStalled) return null
        return restartLocked()
    }

    private fun isolateAllLocked() {
        entries.forEach { entry ->
            entry.needsRebuild = true
            entry.document.isolate()
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 写入抛错与返回 false 同样按「没写成」，保持隔离并在下一轮补写
    private fun markQuietly(identity: WebAuthIdentity): Boolean =
        try {
            journal.markPending(identity)
        } catch (error: Exception) {
            false
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 写入抛错与返回 false 同样按「没写成」
    private fun writeOwnerQuietly(identity: WebAuthIdentity?): Boolean =
        try {
            owner.write(WebDataOwner(identity?.account))
        } catch (error: Exception) {
            false
        }

    private fun enqueueNoticeLocked(loggedIn: Boolean) {
        val targets = entries.filterTo(LinkedHashSet()) { !it.closed }
        if (targets.isNotEmpty()) notices += Notice(buildJsonObject { put(LOGGED_IN, loggedIn) }, targets)
    }

    private fun outstandingLocked(): Boolean = dirty || notices.isNotEmpty() || entries.any { it.needsRebuild && !it.closed }

    private fun startIdentityWaitLocked() {
        if (identityWait != null || identityStalled || closed) return
        identityWait =
            scope.launch {
                delay(timeoutMillis)
                onIdentityTimeout()
            }
    }

    private fun onIdentityTimeout() {
        val dropped =
            synchronized(lock) {
                identityWait = null
                if (closed || confirmed != null) return
                identityStalled = true
                failed = true
                drainWaitersLocked()
            }
        metric(METRIC_IDENTITY_UNKNOWN)
        dropped.forEach { it(false) }
    }

    /** 保存新会话之前登记了待清、随后没有身份快照接上：[timeoutMillis] 后自行开始补清（不发 A）。 */
    private fun scheduleKickLocked() {
        if (kick != null || closed) return
        kick =
            scope.launch {
                delay(timeoutMillis)
                val start =
                    synchronized(lock) {
                        kick = null
                        if (!kickableLocked()) return@launch
                        restartLocked()
                    }
                start.start()
            }
    }

    /** 登记了待清、却没有在跑也没有失败、身份已比对：可以自行开始补清。 */
    private fun kickableLocked(): Boolean = !closed && dirty && worker == null && !failed && confirmed != null && !identityStalled

    /** 作废当前一轮，按最新状态建一轮（LAZY，锁外 start）。 */
    private fun restartLocked(): Job {
        epoch += 1
        val mine = epoch
        failed = false
        worker?.cancel()
        kick?.cancel()
        kick = null
        val job = scope.launch(start = CoroutineStart.LAZY) { runRound(mine) }
        worker = job
        return job
    }

    private fun drainWaitersLocked(): List<(Boolean) -> Unit> = waiters.toList().also { waiters.clear() }

    private fun ensureCurrent(mine: Long) {
        val current = synchronized(lock) { !closed && epoch == mine }
        if (!current) throw CancellationException("superseded by a newer identity transaction")
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 删除抛错 = 这次没删成，留待下次，只记埋点
    private fun discardRetired() {
        try {
            store.discardPending { ok -> if (!ok) metric(METRIC_DISCARD_DEFERRED) }
        } catch (error: Exception) {
            metric(METRIC_DISCARD_DEFERRED)
        }
    }

    // ---- 一轮

    private suspend fun runRound(mine: Long) {
        val success = round(mine)
        val settled =
            synchronized(lock) {
                if (closed || epoch != mine) return
                worker = null
                failed = !success
                drainWaitersLocked()
            }
        settled.forEach { it(success) }
    }

    @Suppress("ReturnCount") // 各步失败即结束本轮
    private suspend fun round(mine: Long): Boolean {
        deliverNotices(mine)
        val transaction = synchronized(lock) { dirty }
        if (transaction) {
            if (!ensureMarked(mine)) return false
            if (!retireAll(mine)) return false
            ensureCurrent(mine)
            if (!awaitSignal(timeoutMillis * ROTATE_TIMEOUT_FACTOR) { done -> store.rotate(done) }) return false
            if (!finishTransaction(mine)) return false
            discardRetired()
        }
        return rebuildAll(mine, transaction)
    }

    /**
     * 逐条投递 A（I7、D10-310 (1)）：按判定时的页面，只发给此刻仍可信、已交付当前文档令牌、未销毁的；每页限时 [timeoutMillis]，
     * 确认与否都继续（旧文档留着才是隐私风险），未确认只记埋点；投递过的 A 不再重发。
     */
    private suspend fun deliverNotices(mine: Long) {
        while (true) {
            ensureCurrent(mine)
            val (notice, targets) =
                synchronized(lock) {
                    val first = notices.firstOrNull() ?: return
                    first to first.targets.filter { !it.closed }
                }
            val results =
                coroutineScope {
                    targets.map { entry -> async { deliver(entry, notice.payload) } }.awaitAll()
                }
            synchronized(lock) {
                if (closed || epoch != mine) throw CancellationException("superseded")
                notices.remove(notice)
            }
            if (results.any { !it }) metric(METRIC_AUTH_UNCONFIRMED)
        }
    }

    private suspend fun deliver(
        entry: Entry,
        payload: JsonObject,
    ): Boolean {
        val document = entry.document
        // 当前主框架不可信、令牌没交到当前文档、已销毁：这份文档不收 A（新文档加载时自取会话），不算未确认
        val alive = !entry.closed && !document.destroyed
        val eligible = document.currentTrusted && document.tokenDelivered
        return if (alive && eligible) awaitSignal(timeoutMillis) { done -> document.notifyAuthChanged(payload, done) } else true
    }

    private fun ensureMarked(mine: Long): Boolean =
        synchronized(lock) {
            if (closed || epoch != mine) throw CancellationException("superseded")
            if (!marked) marked = markQuietly(markOwner)
            marked
        }

    /** 拆除全部登记页（含从未加载的、本轮期间新登记的），每页等销毁确认；全部确认才算成功（I1 先拆后换）。 */
    private suspend fun retireAll(mine: Long): Boolean {
        val attempted = HashSet<Entry>()
        while (true) {
            ensureCurrent(mine)
            val targets = synchronized(lock) { entries.filter { !it.closed && it !in attempted } }
            if (targets.isEmpty()) return true
            attempted += targets
            val results =
                coroutineScope {
                    targets.map { entry -> async { awaitSignal(timeoutMillis) { done -> entry.document.retire(done) } } }.awaitAll()
                }
            if (results.any { !it }) return false
        }
    }

    /** 换存储成功之后：写归属记录（当前身份）、删除持久标记；都与「是否仍是本轮」在同一把锁里，不会删掉新判定刚写的标记。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 删除抛错按没删成：本轮失败、保持隔离
    private fun finishTransaction(mine: Long): Boolean =
        synchronized(lock) {
            if (closed || epoch != mine) throw CancellationException("superseded")
            if (!writeOwnerQuietly(confirmed)) return false
            val cleared =
                try {
                    journal.clearPending()
                } catch (error: Exception) {
                    false
                }
            if (cleared) {
                dirty = false
                marked = false
                markOwner = UNKNOWN
            }
            cleared
        }

    /**
     * 在当前存储上建好全部待建的页（含本轮期间新登记的），新文档就绪的逐页解除隔离（I1、I4）。解除与「是否仍是本轮」在同一把锁里。
     * 事务后的新建失败：重新登记待清（[retry] 重跑整事务：拆半成品 → 再换存储 → 再建），全部页面重新隔离。
     */
    private suspend fun rebuildAll(
        mine: Long,
        transaction: Boolean,
    ): Boolean {
        val attempted = HashSet<Entry>()
        try {
            return rebuildBatches(mine, transaction, attempted)
        } catch (superseded: CancellationException) {
            // 本轮被新判定作废（评审 v2 r2 S1-01 兜底）：对本轮发起过 rebuild、尚未解除的页在锁内再隔离一次——子协程在主线程上同步
            // 执行到 rebuild() 返回（新控制器已发布）之后才会在挂起点收到取消，所以这一次一定看得到新控制器
            synchronized(lock) {
                if (!closed) attempted.filter { it.needsRebuild && !it.closed }.forEach { it.document.isolate() }
            }
            throw superseded
        }
    }

    private suspend fun rebuildBatches(
        mine: Long,
        transaction: Boolean,
        attempted: MutableSet<Entry>,
    ): Boolean {
        while (true) {
            ensureCurrent(mine)
            // 选定目标与各页的比较基准在同一把锁里取（S1-01）：之后的判定一定让该页的基准失效
            val targets =
                synchronized(lock) {
                    if (closed || epoch != mine) throw CancellationException("superseded")
                    entries.filter { it.needsRebuild && !it.closed && it !in attempted }.onEach { it.document.beginRebuild() }
                }
            if (targets.isEmpty()) return true
            attempted += targets
            val storeId = store.current()
            val results =
                coroutineScope {
                    targets
                        .map { entry ->
                            async {
                                entry to
                                    awaitSignal(timeoutMillis * REBUILD_TIMEOUT_FACTOR) { done -> entry.document.rebuild(storeId, done) }
                            }
                        }.awaitAll()
                }
            if (!settleRebuilds(mine, results, transaction)) return false
        }
    }

    private fun settleRebuilds(
        mine: Long,
        results: List<Pair<Entry, Boolean>>,
        transaction: Boolean,
    ): Boolean =
        synchronized(lock) {
            if (closed || epoch != mine) throw CancellationException("superseded")
            // 已关闭（注销或正在出栈）的页不再需要新文档：不计成败（评审 v2 r2 S2-2）
            val live = results.filter { !it.first.closed && !it.first.document.destroyed }
            if (live.all { it.second }) {
                live.forEach { (entry, _) ->
                    entry.needsRebuild = false
                    entry.document.releaseAfterRebuild()
                }
                return true
            }
            if (transaction) {
                // 新存储上的半成品不复用：重跑整事务前全部页重新隔离、标记重新登记
                dirty = true
                marked = false
                markOwner = confirmed ?: UNKNOWN
                isolateAllLocked()
            } else {
                live.filter { it.second }.forEach { (entry, _) ->
                    entry.needsRebuild = false
                    entry.document.releaseAfterRebuild()
                }
            }
            false
        }

    companion object {
        private const val LOGGED_IN = "logged_in"

        /** 换存储等待上限 = 单步上限 × 它（覆盖 F 路径的宽限期 + 四类逐个清理）。 */
        const val ROTATE_TIMEOUT_FACTOR = 10L

        /** 新建页（新 WebView 加载入口、令牌绑定）比单步慢（真实网络）：等待上限 = 单步上限 × 它。 */
        const val REBUILD_TIMEOUT_FACTOR = 10L

        /** A 未确认（超时或明确失败）：照样拆，只记埋点（D10-310 (1)）。 */
        const val METRIC_AUTH_UNCONFIRMED = "fx25_auth_unconfirmed"

        /** 退役存储删不掉（P：旧配置文件仍被占用），留待冷启动再删（D10-310 (5)）。 */
        const val METRIC_DISCARD_DEFERRED = "fx25_store_discard_deferred"

        /** 冷启动身份快照未知超过上限，可信页按失败显示可重试错误层（D10-310 (4)）。 */
        const val METRIC_IDENTITY_UNKNOWN = "fx25_identity_unknown"

        /** 归属未知（持久标记读不出、本进程从未确认过身份时的明确清除）。 */
        val UNKNOWN = WebAuthIdentity(false, null, 0L)
    }
}
