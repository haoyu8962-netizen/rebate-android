package com.couli.core.network

import com.couli.core.generated.ApiOperations

/**
 * 一条未决记录（BR-ID-10 细则「敏感操作的幂等键」）：四个 step-up 操作首次发送前写入，按账号隔离。
 * [body] 为原请求体的原始文本（含账号或手机号，生产后端放 Keystore 加密的存储）；[summary] 为发起页展示的摘要。
 * [stateRaw]：in_flight（在途）或 unknown（结果未知）；[everUnknown] = 这个键出现过结果未知。
 */
data class PendingKeyRecord(
    val key: String,
    val operationId: String,
    val body: String,
    val account: String,
    val summary: String = "",
    val stateRaw: String = PendingKeyStore.IN_FLIGHT,
    val everUnknown: Boolean = false,
    val createdAtMillis: Long = 0L,
)

/** 未决记录的持久化（生产：[SecurePendingKeyBackend]；测试：[MemoryPendingKeyBackend]）。 */
interface PendingKeyBackend {
    fun read(
        account: String,
        key: String,
    ): PendingKeyRecord?

    fun list(account: String): List<PendingKeyRecord>

    fun write(record: PendingKeyRecord)

    fun delete(
        account: String,
        key: String,
    )
}

/** 内存后端（测试与不落盘的场景）。 */
class MemoryPendingKeyBackend : PendingKeyBackend {
    private val records = linkedMapOf<Pair<String, String>, PendingKeyRecord>()

    @Synchronized
    override fun read(
        account: String,
        key: String,
    ): PendingKeyRecord? = records[account to key]

    @Synchronized
    override fun list(account: String): List<PendingKeyRecord> = records.values.filter { it.account == account }

    @Synchronized
    override fun write(record: PendingKeyRecord) {
        records[record.account to record.key] = record
    }

    @Synchronized
    override fun delete(
        account: String,
        key: String,
    ) {
        records.remove(account to key)
    }
}

/** 一个键的当前状态（none / in_flight / unknown / done）。 */
data class PendingKeyState(
    val stateRaw: String,
    val record: PendingKeyRecord? = null,
)

/**
 * 未决键的状态与存储（BR-ID-10 细则「敏感操作的幂等键」；03 §4.2「未决键」）。只做存储与状态，页面在 APP-09。
 *
 * - [beforeSend]：四个 step-up 操作首次发送前写入 in_flight 记录；当前账号同一操作已有在途或结果未知的键时拒绝新的提交
 *   （不得换新键）。其他操作不建记录，直接放行。
 * - [onResponse]：没有统一外壳（超时、断连、网关错误页）→ unknown；拿到业务结果（成功、3xxxx，含作废接口带回的原结果）
 *   或已作废（abandoned、20903）→ 结束并删除记录；其余（10001/10004/10005、20903 以外的 2xxxx、10405、40901、42901、
 *   5xxxx 等）出现过结果未知的保持待确认，从未结果未知的删除记录（升级后或补办后由用户重新提交，用新键）。
 * - [confirm] / [abandon]：只在 unknown 时由用户点击开始（置为 in_flight），两者在途时都不能再点；[abandon] 不是本地删除，
 *   作废接口返回 abandoned 后由 [onResponse] 结束。
 * - [coldStart]：冷启动、登录成功、切回前台时调用，只恢复该账号的记录；上个进程遗留的 in_flight 变 unknown。本进程（本个
 *   Store）仍在执行的请求与用户操作（[active]）保持 in_flight，不因同进程回前台被改成 unknown（R1-15）。记录不按时间过期。
 * - [release]：本次操作在发出之前就结束了（本地拒绝、发送前异常）：在途的键退回 unknown（从未结果未知的删除），R1-13。
 * - APP-13a 的两个操作（[strictOperation]：requestDeletion、bindPhone）口径更严（brief §0 第 1 条，APP-13a 共性第 7 条、G58）：从未结果未知的键
 *   只在 42901、明确的 40901、20001 / 20002 / 20003、44001 与前置补办（10001、10004、10005）后不再使用；其余非终结响应（10007、5xxxx、10405、
 *   10003 用尽、表外 1xxxx / 2xxxx / 4xxxx）保留原记录为 [HELD]：不进待确认，下一次用户提交只放行同一个键（原键原体，[beforeSend] 拒绝别的键）；
 *   这次没有发出或没有结果（[release]）时回到 held，不删除。held 不能确认、不能作废（只对 unknown）。
 *
 * 线程安全（方法加锁）。
 */
@Suppress("TooManyFunctions") // 三端测试接线约定的入口（beforeSend / onResponse / confirm / abandon / state / coldStart）加管线用的两个
class PendingKeyStore(
    private val backend: PendingKeyBackend,
    private val clock: () -> Long,
) {
    private var account: String? = null

    /** 本进程里已结束的键（done 只是观察值，持久记录已删除）。 */
    private val finished = mutableSetOf<String>()

    /**
     * 本进程（本个 Store）里正在执行的键 → 占用（发起时的账号 + 占用它的逻辑调用 [Hold.holder]）：首次发送、确认重发、作废，
     * 直到整次逻辑调用结算（[onResponse]）或 [release]。10002 刷新、10402 重新注册、10401 校时重签这类内部续发期间一直占用
     * （A-R2-05）；只有占用它的那次调用能结算或释放，旧调用的 finally 不会放掉新调用持有的键。
     * 响应结算按发起时的账号找记录（lessons 15）：在途期间切换了账号，结果仍记到原账号的记录上，不落到新账号、也不留在 in_flight。
     */
    private val active = mutableMapOf<String, Hold>()

    /** 本进程里由 held 转为在途的键（APP-13a 共性第 7 条）：这次没有业务结果（[release]）时回到 held，不删除。 */
    private val heldKeys = mutableSetOf<String>()

    private data class Hold(
        val account: String,
        val holder: String,
    )

    /** 当前账号。 */
    val currentAccount: String? @Synchronized get() = account

    @Synchronized
    fun coldStart(account: String) {
        backend.list(account).filter { it.stateRaw == IN_FLIGHT && it.key !in active.keys }.forEach {
            backend.write(it.copy(stateRaw = UNKNOWN, everUnknown = true))
        }
        this.account = account
    }

    /** 退出登录或切换账号：不删除记录（别的账号的记录不读不发），只停止读取。 */
    @Synchronized
    fun signOut() {
        account = null
    }

    /** 发送前登记。返回 false = 不得发送（同一操作已有未决的键）。 */
    @Suppress("LongParameterList", "ReturnCount") // 三端测试接线约定的参数，加一个可选的展示摘要；逐项提前返回
    @Synchronized
    fun beforeSend(
        operationId: String,
        key: String,
        body: String,
        account: String,
        summary: String = "",
        holder: String = ANONYMOUS_HOLDER,
    ): Boolean {
        if (!isSensitive(operationId)) return true
        if (this.account != account) coldStart(account)
        val blocking = backend.list(account).any { it.operationId == operationId }
        if (blocking) return false
        // 先持久化，写成功后再改内存（lessons 32）：写失败时抛出，调用方按 pending_store_failed 不发
        backend.write(
            PendingKeyRecord(
                key = key,
                operationId = operationId,
                body = body,
                account = account,
                summary = summary,
                stateRaw = IN_FLIGHT,
                everUnknown = false,
                createdAtMillis = clock(),
            ),
        )
        finished.remove(key)
        active[key] = Hold(account, holder)
        return true
    }

    /**
     * 管线发送前调用（一次逻辑调用只登记一次）：同一个键已有记录 → 由 [holder] 占用（用户刚点了【确认】时已由同一调用占用）
     * 后置为在途放行；键正被别的调用占用、或是别的进程遗留的在途记录，不放行。没有记录同 [beforeSend]。
     */
    @Suppress("LongParameterList", "ReturnCount") // 同 beforeSend
    @Synchronized
    fun prepareSend(
        operationId: String,
        key: String,
        body: String,
        account: String,
        summary: String = "",
        holder: String = ANONYMOUS_HOLDER,
    ): Boolean {
        if (!isSensitive(operationId)) return true
        if (this.account != account) coldStart(account)
        val existing = backend.read(account, key) ?: return beforeSend(operationId, key, body, account, summary, holder)
        val held = active[key]
        if (held != null && held.holder != holder) return false
        if (held == null && existing.stateRaw != UNKNOWN && existing.stateRaw != HELD) return false
        if (existing.stateRaw == UNKNOWN || existing.stateRaw == HELD) backend.write(existing.copy(stateRaw = IN_FLIGHT))
        if (existing.stateRaw == HELD) heldKeys += key
        active[key] = Hold(account, holder)
        return true
    }

    /**
     * 整次逻辑调用的结算（三端接线的 raw）：no_envelope、envelope_success、abandoned、envelope_3xxxx、envelope_<code>。
     * 不认识的 kind 按非终结处理（保守）。给了 [holder] 时只结算该调用占用的键（A-R2-05）：键已被别的调用占用或已释放则不动。
     */
    @Synchronized
    @Suppress("ReturnCount") // 占用不符、没有账号、没有记录都不动
    fun onResponse(
        key: String,
        kind: String,
        holder: String? = null,
    ) {
        val held = active[key]
        if (holder != null && held?.holder != holder) return
        // 先持久化结论，再改内存（lessons 32）；写失败也不留下本进程的占用（记录保持上次写入的状态，冷启动恢复为 unknown）
        try {
            val current = held?.account ?: account ?: return
            val record = backend.read(current, key) ?: return
            when (classify(kind)) {
                Outcome.NO_ENVELOPE -> {
                    backend.write(record.copy(stateRaw = UNKNOWN, everUnknown = true))
                }

                Outcome.TERMINAL -> {
                    backend.delete(current, key)
                    finished += key
                }

                Outcome.NON_TERMINAL -> {
                    when {
                        record.everUnknown -> backend.write(record.copy(stateRaw = UNKNOWN))

                        // APP-13a 两个操作的非终结响应保留原记录（共性第 7 条）：下一次用户提交沿用原键原体
                        holdsKey(record.operationId, kind) -> backend.write(record.copy(stateRaw = HELD))

                        else -> backend.delete(current, key)
                    }
                }
            }
        } finally {
            active.remove(key)
            heldKeys.remove(key)
        }
    }

    /** 用户点【确认】：用原键、原请求体重发。只在 unknown 时接受；由 [holder]（这次确认的逻辑调用）占用到结算。 */
    @Synchronized
    fun confirm(
        key: String,
        holder: String = ANONYMOUS_HOLDER,
    ): Boolean = account?.let { startUserAction(it, key, holder) } ?: false

    /**
     * 同 [confirm]，账号由调用方从可信会话取来、在读取之前确定（R3-04）：只读 [account] 名下的记录，不经过 Store 里可能过期的
     * 当前账号；Store 的当前账号不是它时先按它恢复（同 [coldStart]）。
     */
    @Synchronized
    fun confirm(
        key: String,
        holder: String,
        account: String,
    ): Boolean {
        useAccount(account)
        return startUserAction(account, key, holder)
    }

    /** 用户点【放弃】（二次确认后调作废接口）。只在 unknown 时接受；不是本地删除。 */
    @Synchronized
    fun abandon(
        key: String,
        holder: String = ANONYMOUS_HOLDER,
    ): Boolean = account?.let { startUserAction(it, key, holder) } ?: false

    /** 同 [abandon]，账号由调用方从可信会话取来（R3-04，见 [confirm]）。 */
    @Synchronized
    fun abandon(
        key: String,
        holder: String,
        account: String,
    ): Boolean {
        useAccount(account)
        return startUserAction(account, key, holder)
    }

    /**
     * 本次操作没有拿到结果就结束了（本地拒绝、发送前异常、不再重放；R1-13、lessons 18）：本进程占用的在途键退回 unknown
     * （出现过结果未知的）或删除（首次发送还没发出）。只动本进程正在执行的键：已由 [onResponse] 结算的、别的进程遗留的不动。
     */
    @Synchronized
    @Suppress("ReturnCount") // 不在本进程执行、占用不符、没有记录、不在途都不动
    fun release(
        key: String,
        holder: String? = null,
    ) {
        val held = active[key] ?: return
        if (holder != null && held.holder != holder) return
        try {
            val current = held.account
            val record = backend.read(current, key) ?: return
            if (record.stateRaw != IN_FLIGHT) return
            when {
                record.everUnknown -> backend.write(record.copy(stateRaw = UNKNOWN))

                // held 键这次没有发出或没有业务结果：回到 held，不删除（共性第 7 条）
                key in heldKeys -> backend.write(record.copy(stateRaw = HELD))

                else -> backend.delete(current, key)
            }
        } finally {
            active.remove(key)
            heldKeys.remove(key)
        }
    }

    @Synchronized
    fun state(key: String): PendingKeyState = stateOf(account, key)

    /** [account] 名下某个键的状态（账号在读取前确定，R3-04）。 */
    @Synchronized
    fun state(
        key: String,
        account: String,
    ): PendingKeyState = stateOf(account, key)

    private fun stateOf(
        current: String?,
        key: String,
    ): PendingKeyState {
        val record = current?.let { backend.read(it, key) }
        return when {
            record != null -> PendingKeyState(record.stateRaw, record)
            key in finished -> PendingKeyState(DONE)
            else -> PendingKeyState(NONE)
        }
    }

    /** 当前账号某个操作的未决记录（发起页按它进入待确认状态）。 */
    @Synchronized
    fun pendingFor(operationId: String): PendingKeyRecord? =
        account?.let { current ->
            backend.list(current).firstOrNull {
                it.operationId ==
                    operationId
            }
        }

    /**
     * [account] 名下某个操作的未决记录（R3-04）：账号由调用方从可信会话取来，存储层只按它读（[PendingKeyBackend.list] 按账号
     * 过滤），别的账号的记录不读、不解密。Store 的当前账号不是它时先按它恢复（同 [coldStart]）。读取失败抛错，不当成没有记录
     * （lessons 38）。
     */
    @Synchronized
    fun pendingFor(
        operationId: String,
        account: String,
    ): PendingKeyRecord? {
        useAccount(account)
        return backend.list(account).firstOrNull { it.operationId == operationId }
    }

    private fun useAccount(account: String) {
        if (this.account != account) coldStart(account)
    }

    /**
     * [confirm] / [abandon] 的只读预判（F-X-03：环境未就绪窗口里给出与真正占用时相同的结论，但不写、不占用）：[account] 名下 [key] 的记录
     * 是结果未知、且本进程没有在执行它。读取失败抛错（调用方按 pending_store_failed）。
     */
    @Synchronized
    fun wouldStartUserAction(
        key: String,
        account: String,
    ): Boolean {
        useAccount(account)
        val record = backend.read(account, key) ?: return false
        return record.stateRaw == UNKNOWN && key !in active
    }

    @Suppress("ReturnCount") // 没有记录、不是 unknown 都不接受
    private fun startUserAction(
        current: String,
        key: String,
        holder: String,
    ): Boolean {
        val record = backend.read(current, key) ?: return false
        if (record.stateRaw != UNKNOWN || key in active) return false
        backend.write(record.copy(stateRaw = IN_FLIGHT))
        active[key] = Hold(current, holder)
        return true
    }

    private enum class Outcome { NO_ENVELOPE, TERMINAL, NON_TERMINAL }

    @Suppress("ReturnCount") // 固定 kind 先判，再按码判
    private fun classify(kind: String): Outcome {
        if (kind == NO_ENVELOPE) return Outcome.NO_ENVELOPE
        if (kind == ENVELOPE_SUCCESS || kind == ABANDONED || kind == "envelope_3xxxx") return Outcome.TERMINAL
        val code = kind.removePrefix(ENVELOPE_PREFIX).takeIf { kind.startsWith(ENVELOPE_PREFIX) }?.toIntOrNull()
        return when {
            code == null -> Outcome.NON_TERMINAL
            code == ABANDONED_CODE || ErrorActionMapper.isBusinessCode(code) -> Outcome.TERMINAL
            else -> Outcome.NON_TERMINAL
        }
    }

    companion object {
        const val NONE = "none"
        const val IN_FLIGHT = "in_flight"
        const val UNKNOWN = "unknown"
        const val DONE = "done"

        /** APP-13a 严格操作从未结果未知的键收到非终结响应后保留（不进待确认，下次用户提交沿用原键原体）。 */
        const val HELD = "held"

        /** APP-13a 口径更严的操作（brief §0 第 1 条）：申请注销、绑定 / 更换手机号。 */
        fun strictOperation(operationId: String): Boolean = operationId == "requestDeletion" || operationId == "bindPhone"

        /** 从未结果未知后键不再使用的码（严格操作）：42901、明确 40901、20001–20003、44001、前置补办 10001 / 10004 / 10005。 */
        private val ENDING_CODES = setOf(42901, 40901, 20001, 20002, 20003, 44001, 10001, 10004, 10005)

        /** 从未结果未知的键收到非终结 [kind] 后是否保留为 held（只对 [strictOperation]）。 */
        @Suppress("ReturnCount") // 非严格操作、不是带外壳的码逐项
        fun holdsKey(
            operationId: String,
            kind: String,
        ): Boolean {
            if (!strictOperation(operationId) || !kind.startsWith(ENVELOPE_PREFIX)) return false
            val code = kind.removePrefix(ENVELOPE_PREFIX).toIntOrNull() ?: return false
            return code !in ENDING_CODES
        }

        const val NO_ENVELOPE = "no_envelope"
        const val ENVELOPE_SUCCESS = "envelope_success"
        const val ABANDONED = "abandoned"
        const val ENVELOPE_PREFIX = "envelope_"
        private const val ABANDONED_CODE = 20903

        /** 不区分调用的占用（三端测试接线直接调用 Store 时）。 */
        const val ANONYMOUS_HOLDER = ""

        /** 四个需要二次验证的操作（operation 的 x-step-up）。 */
        fun isSensitive(operationId: String): Boolean = ApiOperations.byId(operationId)?.stepUp != null

        /** 响应 → kind：有外壳时 envelope_success / envelope_<code>。 */
        fun envelopeKind(code: Int): String = if (code == 0) ENVELOPE_SUCCESS else "$ENVELOPE_PREFIX$code"
    }
}
