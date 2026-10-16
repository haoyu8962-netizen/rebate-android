// 错误码是契约取值（contracts/error-codes.json），按码直接写
@file:Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod")

package com.couli.feature.me

import com.couli.core.me.account.AccountJson
import com.couli.core.me.account.AccountRaw
import com.couli.core.me.account.AccountResult
import com.couli.core.me.account.DeletionCancelOutcome
import com.couli.core.me.account.DeletionCancelPolicy
import com.couli.core.me.account.DeletionCancelReconcile
import com.couli.core.me.account.DeletionOutcome
import com.couli.core.me.account.DeletionOutcomePolicy
import com.couli.core.me.account.DeletionPresentation
import com.couli.core.me.account.DeletionRead
import com.couli.core.me.account.DeletionSummary
import com.couli.core.me.account.DeletionView
import com.couli.core.me.account.PendingSettlement
import com.couli.core.me.account.WalletFunds
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PendingTarget
import com.couli.core.wallet.SendProbe
import com.couli.core.wallet.WalletError
import com.couli.core.wallet.stepup.StepUpController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 本机未决记录在页面上的投影（按账号；不按时间解除，G25）。 */
data class AccountPending(
    val key: String,
    val createdAtMillis: Long,
    /** 本进程正在确认或作废（两个按钮都置灰，PendingBusy）。 */
    val inFlight: Boolean,
    /** 记录里的展示摘要（换号：本机脱敏新号；注销：固定摘要）。 */
    val summary: String,
)

/**
 * 注销页的状态（DeleteAccount*）。[read] 为 null 表示 getDeletion 还没有结论；[fundsLoaded] 为 false 表示钱包摘要还没有结论（[funds] 为 null 且已有结论 =
 * 读取失败）；[phoneBound] 为 null 表示未知。[pendingVersion] 每次进入或离开待确认加 1：一个键结束后，其余在途响应只用于核对（BR-ID-10）。
 */
data class DeleteAccountState(
    val read: DeletionRead? = null,
    val readError: MeNotice? = null,
    val funds: WalletFunds? = null,
    val fundsLoaded: Boolean = false,
    val fundsError: MeNotice? = null,
    val phoneBound: Boolean? = null,
    val pending: AccountPending? = null,
    val pendingVersion: Long = 0,
    val pendingUnreadable: Boolean = false,
    val pendingNotice: MeNotice? = null,
    /** 勾选（G14：不上传、不落盘、不进 SavedStateHandle）。 */
    val checked: Boolean = false,
    val submitting: Boolean = false,
    val cancelling: Boolean = false,
    val error: MeNotice? = null,
    /** 本次 30416 的金额（共性第 10 条：不被进页的 negative_fen 替换）；null 且 [submitNegative] 为 true = 缺金额，用 fallbacks 句。 */
    val submitNegativeFen: Long? = null,
    val submitNegative: Boolean = false,
    /** 点【申请注销】后发现未绑手机（G10）：只显示 BR-ID-08 原句，不发请求。 */
    val noPhonePrompt: Boolean = false,
    val abandonDialog: MeDialog? = null,
    /** 被拦下的前置补办（页面不可见或导航许可已变）：保留目标、页上给手动入口（APP-09 第 51 条）。 */
    val guideRoute: String? = null,
    val toast: MeNotice? = null,
    val version: Long = 0,
)

/**
 * 注销页执行器（DeleteAccount 路由；brief §2 第 2–4 项；BR-ID-27、BR-ID-08、BR-ID-10；G10–G20、G24、G27）：
 * - 读取：getDeletion、getWalletSummary、getMe（phone_bound，经「我的」统一结算）。同类读取按序号只收最新；离页同步作废；申请 / 撤回成功先推进读取世代。
 * - 申请：勾选 → phone_bound 核对 → 新键（held 沿用原键原体，G58）→ requestDeletion（无请求体）；10003 → 二次验证（account_deletion）沿用原键重放 1 次。
 * - 待确认：确认（原键原体）/ 放弃（二次确认后作废）；在途两按钮置灰；不按时间解除。
 * - 撤回：不弹确认框（G16）；结果未知不自动重发，重取 getDeletion 核对（G17、G61）。
 * - 身份固定（S0）：构造时取 [identity]，所有回调带渲染时的版本，执行前只比较；写请求离页不取消，响应不改已出栈页面。
 * 只在主线程使用。
 */
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList")
class DeleteAccountController(
    private val deps: AccountDeps,
    val identity: AccountIdentity,
    /** 进入来源（settings / force_update / risk_notice / error_10007 / other，G54、G55）。 */
    val source: String,
    private val entryId: String?,
) : MeReader {
    private var left = false
    private var ids = 0L
    private var dialogIds = 0L
    private val mutable = MutableStateFlow(DeleteAccountState())
    val state: StateFlow<DeleteAccountState> = mutable.asStateFlow()
    private val s: DeleteAccountState get() = mutable.value

    /** 二次验证面板（account_deletion）。 */
    val stepUp = StepUpController(deps.stepUp, deps.service) { alive() }

    private val readOwners = mutableListOf<CallOwnership>()
    private var deletionSeq = 0L
    private var walletSeq = 0L

    /** 读取世代：申请 / 撤回成功后推进，之前发出的 getDeletion 不再覆盖页面（APP-12 第 29、35 条）。 */
    private var deletionGen = 0L

    /** 页面可见性与可见世代（APP-09 第 37 条）：异步结果打开浮层、导航前核对。 */
    private var visible = true
    private var viewGen = 0L
    private var sentViewGen = 0L
    private var localBusy = false

    fun alive(): Boolean = !left && identity.current(deps.env)

    override fun reading(): Boolean = alive()

    private val onScreen: Boolean get() = alive() && visible && viewGen == sentViewGen && deps.env.isTop(entryId)

    /** 订阅（未决结算通知、Me 快照）：离开时取消（共性第 19 条）。 */
    private val watchers = mutableListOf<kotlinx.coroutines.Job>()

    init {
        watchers +=
            deps.service.launch {
                deps.shared.pendingSignals.collect { settlement -> onSettlement(settlement) }
            }
        watchers +=
            deps.service.launch {
                deps.me.repo.state.collect { snapshot ->
                    if (!alive()) return@collect
                    val me = snapshot.me?.takeIf { snapshot.identity == identity.me }
                    if (me != null && me.phoneBound != s.phoneBound) update { it.copy(phoneBound = me.phoneBound) }
                }
            }
    }

    // ---------------------------------------------------------------- 显示

    /** 渲染时的显示规则（DeletionPresentation，三端同一套向量）。 */
    fun view(nowMs: Long = deps.clock()): DeletionView? {
        val read = s.read ?: return null
        val pendingRaw =
            when {
                s.pending != null -> DeletionPresentation.PENDING_UNKNOWN
                s.submitting -> DeletionPresentation.PENDING_IN_FLIGHT
                else -> DeletionPresentation.PENDING_NONE
            }
        val base = DeletionPresentation.view(read, s.funds, s.phoneBound, pendingRaw, identity.scope, source, nowMs)
        // 本次 30416（共性第 10 条）：显示用本次响应的金额，不被自动重取到的 negative_fen 替换。只覆盖可申请一族（初始、负余额、未绑手机）：
        // 待确认、读取失败、冷静期、处理中、资金读取失败仍按规定优先级展示（R1-08、共性第 34 条）；明确刷新（进入、回前台、重试）后按新读取更新资格
        return if (s.submitNegative && base.stateRaw in NEGATIVE_OVERRIDABLE) {
            base.copy(
                stateRaw = DeletionView.NEGATIVE,
                applyEnabledWhenChecked = false,
                showGoWithdraw = false,
                negativeAmountFen = s.submitNegativeFen,
                waiverEnabled = false,
                showContactService = true,
            )
        } else {
            base
        }
    }

    // ---------------------------------------------------------------- 读取

    /** 进入、回到本页、【重试】：取进度、钱包摘要与 phone_bound；按未决记录恢复待确认。这是明确刷新：钱包结论回来后按新读取更新申请资格（R1-08）。 */
    fun load() {
        if (!alive()) return
        refreshPending()
        loadDeletion()
        loadWallet(explicit = true)
        if (identity.member) deps.me.repo.refresh(identity.me, this)
    }

    fun retry(version: Long) {
        if (bound(version)) load()
    }

    private fun readOwner(
        probe: SendProbe,
        valid: () -> Boolean,
    ): CallOwnership = CallOwnership(deps.api.epochs(), sendAllowed = probe::allowed) { alive() && valid() }.also { readOwners += it }

    /**
     * 撤回结果未知后的核对上下文（G17、R1-11、共性第 33 条）：核对读取失败时保留，【重试】或下一次读取查明已撤回（不是系统撤销）再走撤回成功结算；
     * 读到其他结论（仍在冷静期等）即结束，按读取结果落地。
     */
    private var cancelReconcile = false

    private fun loadDeletion() {
        val mine = ++deletionSeq
        val gen = deletionGen
        val gate = deps.deletionRead
        if (gate.active) {
            // 核对撤回时不能沿用旧的冷静期快照（撤回仍可点）：按读取失败显示，申请、撤回都不可用
            update {
                it.copy(
                    read = if (cancelReconcile) DeletionRead.Bad else it.read ?: DeletionRead.Bad,
                    readError = MeNotice(RATE_KEY, ++ids),
                )
            }
            return
        }
        val probe = gate.probe()
        val owner = readOwner(probe) { deletionSeq == mine }
        deps.service.launch {
            val result = deps.api.send(ApiCall(GET_DELETION, ownership = owner, expectedAccount = identity.account))
            readOwners -= owner
            if (!alive() || mine != deletionSeq || gen != deletionGen) return@launch
            when (result) {
                is ApiResult.Success -> {
                    val read = AccountJson.read(result.data)
                    if (cancelReconcile && read != DeletionRead.Bad) {
                        cancelReconcile = false
                        if (DeletionCancelReconcile.cancelledByUser(read)) {
                            cancelSucceeded((read as? DeletionRead.Ok)?.deletion)
                            return@launch
                        }
                    }
                    update { it.copy(read = read, readError = null) }
                }

                else -> {
                    val notice = MeReadFailure.handle(result, probe, gate, deps.me.env) { ++ids } ?: return@launch
                    update { it.copy(read = DeletionRead.Bad, readError = notice) }
                }
            }
        }
    }

    /** [explicit]：进入、回前台、重试的明确刷新（结论回来后清掉本次 30416 的覆盖，按新读取更新资格，R1-08）；30416 之后的自动重取不清。 */
    private fun loadWallet(explicit: Boolean = false) {
        val mine = ++walletSeq
        val gate = deps.walletRead
        if (gate.active) {
            update { it.copy(fundsLoaded = true, funds = null, fundsError = MeNotice(RATE_KEY, ++ids)) }
            if (explicit) clearSubmitNegative()
            return
        }
        val probe = gate.probe()
        val owner = readOwner(probe) { walletSeq == mine }
        deps.service.launch {
            val result = deps.api.send(ApiCall(GET_WALLET, ownership = owner, expectedAccount = identity.account))
            readOwners -= owner
            if (!alive() || mine != walletSeq) return@launch
            if (explicit) clearSubmitNegative()
            val funds = (result as? ApiResult.Success)?.let { WalletFunds.of(it.data) }
            if (funds != null) {
                update { it.copy(funds = funds, fundsLoaded = true, fundsError = null) }
                return@launch
            }
            val notice =
                if (result is ApiResult.Success) {
                    MeNotice(BAD_DATA_KEY, ++ids)
                } else {
                    MeReadFailure.handle(result, probe, gate, deps.me.env) { ++ids } ?: return@launch
                }
            update { it.copy(funds = null, fundsLoaded = true, fundsError = notice) }
        }
    }

    /**
     * 读闸门到期（共性第 19 条）：页面订阅它依赖的读闸门（钱包摘要、注销进度、Me），到期时把因限流而失败的那一块重取一次；没有失败的不重取。
     */
    fun onReadGatesOpen() {
        if (!alive()) return
        if (s.fundsLoaded && s.funds == null && !deps.walletRead.active) loadWallet()
        if (s.read == DeletionRead.Bad && s.readError?.key == RATE_KEY && !deps.deletionRead.active) loadDeletion()
        if (s.phoneBound == null && identity.member && !deps.me.gates.read.active) deps.me.repo.refresh(identity.me, this)
    }

    private fun clearSubmitNegative() {
        if (s.submitNegative) update { it.copy(submitNegative = false, submitNegativeFen = null) }
    }

    /** 资金区【重试】（用户主动）：明确刷新（R2-04、共性第 42 条）——结论回来即按新读取更新申请资格，清掉本次 30416 的覆盖；30416 之后的自动重取仍不清。 */
    fun retryWallet(version: Long) {
        if (bound(version)) loadWallet(explicit = true)
    }

    /**
     * 按当前会话账号的未决记录重建待确认（进入、回前台、键结束之后、别处结算之后）。记录读不出 → 不能确认未决状态，【申请注销】置灰（lessons 38）。
     * 只放本身份的记录（S0：与固定身份比较，不读当前会话来赋值）。
     */
    fun refreshPending() {
        if (!alive()) return
        val record =
            try {
                deps.api.pendingFor(OPERATION)
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception,
            ) {
                update { it.copy(pendingUnreadable = true) }
                return
            }
        if (record != null && record.account != identity.account) return
        heldRecord = record?.takeIf { it.stateRaw == PendingKeyStore.HELD }
        val unknown =
            record?.takeIf {
                it.stateRaw == PendingKeyStore.UNKNOWN ||
                    (it.stateRaw == PendingKeyStore.IN_FLIGHT && it.everUnknown)
            }
        update { state ->
            if (unknown == null) {
                val cleared = state.pending != null
                state.copy(
                    pending = null,
                    pendingVersion = if (cleared) state.pendingVersion + 1 else state.pendingVersion,
                    pendingNotice = if (cleared) null else state.pendingNotice,
                    pendingUnreadable = false,
                    abandonDialog = if (cleared) null else state.abandonDialog,
                )
            } else {
                val fresh = state.pending?.key != unknown.key
                val busy = unknown.stateRaw == PendingKeyStore.IN_FLIGHT || (!fresh && localBusy)
                state.copy(
                    pending = AccountPending(unknown.key, unknown.createdAtMillis, busy, unknown.summary),
                    pendingVersion = if (fresh) state.pendingVersion + 1 else state.pendingVersion,
                    pendingUnreadable = false,
                )
            }
        }
    }

    /** 不进待确认、下一次提交沿用原键原体的记录（G58 held）。 */
    private var heldRecord: PendingKeyRecord? = null

    private fun onSettlement(settlement: PendingSettlement) {
        if (settlement.operationId != OPERATION || !alive() || settlement.account != identity.account) return
        refreshPending()
    }

    // ---------------------------------------------------------------- 勾选与申请

    private fun bound(version: Long): Boolean = alive() && s.version == version

    fun toggle(version: Long) {
        if (!bound(version) || s.submitting || s.pending != null) return
        update { it.copy(checked = !it.checked, noPhonePrompt = false) }
    }

    /** 当前（或下次提交沿用的）键；null = 下次用新键。 */
    private var reuseKey: String? = null
    private var stepUpReplayed = false

    /** 【申请注销】（渲染时的版本）。 */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun apply(version: Long) {
        if (!bound(version) || s.submitting) return
        val view = view() ?: return
        if (!view.applyEnabledWhenChecked || !s.checked || s.pendingUnreadable) return
        if (deps.shared.deletionGate.active) {
            update { it.copy(error = MeNotice(RATE_KEY, ++ids)) }
            return
        }
        when (s.phoneBound) {
            // G10：本端不支持任何第三方重新授权：只显示 BR-ID-08 原句，不发请求
            false -> {
                update { it.copy(noPhonePrompt = true) }
                return
            }

            // 未知：先重取 getMe（不发 requestDeletion）
            null -> {
                deps.me.repo.refresh(identity.me, this)
                update { it.copy(error = MeNotice(PHONE_UNKNOWN_KEY, ++ids)) }
                return
            }

            true -> {
                Unit
            }
        }
        val account = identity.account ?: return
        // G58：held 的键沿用原键原体（不重新生成）；否则新键
        val held = heldRecord?.takeIf { it.account == account }
        val key = held?.key ?: reuseKey ?: deps.newKey()
        reuseKey = key
        stepUpReplayed = false
        val token = deps.stepUp.tokens.take(AccountRaw.ACCOUNT_DELETION, account, deps.clock(), identity.epoch, identity.scope)
        update { it.copy(submitting = true, error = null, submitNegative = false, submitNegativeFen = null, guideRoute = null) }
        send(account, key, token)
    }

    private fun send(
        account: String,
        key: String,
        token: String?,
    ) {
        if (!alive()) {
            reuseKey = null
            return
        }
        sentViewGen = viewGen
        val probe = deps.shared.deletionGate.probe()
        val epoch = identity.epoch
        // 写请求离开页面不取消（未决记录兜底）；R3-03、共性第 45 条：持续归属是构造时的完整固定身份（账号 + 登录世代 + 作用域 + 隐私 / 环境世代），
        // 首次派发、等待刷新结束与每次实际写出前都比较，任一分量变了（含同账号同世代的受限 → full 刷新）尚未写出的不发
        // RC-02：发送前比完整固定身份，已写出的只要同账号同登录世代就照常结算
        val owner = writeOwnership(probe::allowed)
        deps.service.launch {
            val result =
                deps.api.send(
                    ApiCall(
                        REQUEST_DELETION,
                        idempotencyKey = key,
                        stepUpToken = token,
                        summary = DeletionSummary.RECORD,
                        ownership = owner,
                        expectedAccount = account,
                    ),
                )
            val input = AccountResult.of(result)
            val outcome = input?.let { DeletionOutcomePolicy.of(AccountRaw.APPLY, it, source, hadUnknownResult = false) }
            settleShared(account, epoch, result, outcome)
            deps.shared.notifyPending(PendingSettlement(account, epoch, OPERATION, key, outcome?.nextStateRaw, outcome?.textKey))
            if (!alive()) return@launch
            update { it.copy(submitting = false) }
            if (outcome == null) {
                localRejected(result, probe)
                return@launch
            }
            applyOutcome(outcome, result, AccountRaw.APPLY, intent = null)
        }
    }

    /** 共享结算（只按点击时的会话归属，不看页面是否还在）：禁用期、token 失效、强更、成功后的读取世代。 */
    private fun settleShared(
        account: String,
        epoch: Long,
        result: ApiResult,
        outcome: DeletionOutcome?,
    ) {
        if (deps.env.sessionEpoch() != epoch || deps.env.account() != account) return
        outcome ?: return
        // APP-13a 共性 49（FU-AND-0717）：42901 写闸门前复核完整固定身份（含作用域）；同账号同世代作用域刷新后迟到的旧请求不写新作用域的闸门，
        // 成功等网络结算照常
        if (ownsWrite()) outcome.disableMs?.let(deps.shared.deletionGate::start)
        if (result is ApiResult.Success && outcome.nextStateRaw == DeletionOutcome.COOLING) {
            deps.stepUp.tokens.onSuccess(AccountRaw.ACCOUNT_DELETION)
            deps.env.deletionChanged()
        }
        if (outcome.nextStateRaw == DeletionOutcome.STEP_UP) deps.stepUp.tokens.on10003(AccountRaw.ACCOUNT_DELETION)
        if (outcome.nextStateRaw == DeletionOutcome.FORCE_UPDATE) deps.env.forceUpdate()
    }

    private fun localRejected(
        result: ApiResult,
        probe: SendProbe,
    ) {
        reuseKey = null
        val reason = (result as? ApiResult.LocalReject)?.reason
        val key =
            when {
                probe.gated(result) -> RATE_KEY

                reason == LocalRejectReason.PENDING_KEY_UNRESOLVED -> null

                result is ApiResult.SessionEnded -> null

                // R1-07、G62：只刷新的 10405 不是业务结果（网络层已刷新会话，键由存储保留），提示稍后再试，不进强更
                AccountResult.refreshOnly(result) -> AccountRaw.RETRY_LATER_KEY

                else -> NETWORK_KEY
            }
        update { it.copy(submitting = false, error = key?.let { k -> MeNotice(k, ++ids) }) }
        refreshPending()
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun applyOutcome(
        outcome: DeletionOutcome,
        result: ApiResult,
        phase: String,
        intent: Intent?,
    ) {
        if (outcome.nextKeyRaw != AccountRaw.SAME) reuseKey = null
        val traceId = (result as? ApiResult.Failure)?.traceId
        val message =
            outcome.textKey?.let { MeNotice(it, ++ids, traceId, outcome.trace6 != null, serverMsg = outcome.serverMsg) }
                ?: outcome.serverMsg?.let { MeNotice(UNFINISHED_KEY, ++ids, serverMsg = it) }
        refreshPending()
        when (outcome.nextStateRaw) {
            DeletionOutcome.COOLING -> {
                // 成功：先推进读取世代，旧读取不得把冷静期覆盖回初始态
                deletionGen += 1
                outcome.deletion?.let { d -> update { it.copy(read = DeletionRead.Ok(d), checked = false, error = null) } }
                if (outcome.alreadyDone) toast(ALREADY_DONE_KEY)
                if (outcome.navigateRaw == AccountRaw.FORCE_UPDATE && onScreen) {
                    deps.env.finishDeletion(entryId, AccountRaw.FORCE_UPDATE, null)
                } else if (outcome.deletion == null || outcome.refetchDeletion) {
                    loadDeletion()
                }
            }

            DeletionOutcome.STEP_UP -> {
                if (stepUpReplayed || intent?.stepUpReplayed == true) {
                    // 原键重放已用过一次又收到 10003：不再弹面板（APP-09 第 16 条）
                    showError(phase, message ?: MeNotice("error.10003", ++ids))
                } else if (!onScreen) {
                    showError(phase, message ?: MeNotice("error.10003", ++ids))
                } else {
                    openStepUp(phase, intent)
                }
            }

            DeletionOutcome.NEGATIVE -> {
                update { it.copy(submitNegative = true, submitNegativeFen = outcome.negativeAmountFen, checked = false) }
                loadWallet()
            }

            DeletionOutcome.FORCE_UPDATE -> {
                if (phase != AccountRaw.APPLY) update { it.copy(pendingNotice = message) }
            }

            DeletionOutcome.PENDING_CONFIRM -> {
                if (phase != AccountRaw.APPLY && outcome.textKey != AccountRaw.TITLE_KEY) update { it.copy(pendingNotice = message) }
                if (outcome.refetchDeletion) loadDeletion()
                guide(outcome.guideRoute)
            }

            DeletionOutcome.UNCHANGED -> {
                // 10007（G27）：停在本页、键不结束；cooling 重取进度
                update { it.copy(error = message) }
                if (outcome.refetchDeletion) loadDeletion()
            }

            else -> {
                // initial：键结束的错误（30412 保留勾选；20903、abandoned 清空勾选，G14）或保留键的错误（5xxxx、补办码）
                val clear = outcome.textKey == "error.20903" || outcome.textKey == AccountRaw.ABANDONED_KEY
                if (phase == AccountRaw.ABANDON && outcome.textKey == AccountRaw.ABANDONED_KEY) {
                    update { it.copy(checked = false, error = null) }
                    toast(AccountRaw.ABANDONED_KEY)
                } else {
                    update { it.copy(error = message, checked = if (clear) false else it.checked) }
                }
                if (outcome.alreadyDone) toast(ALREADY_DONE_KEY)
                guide(outcome.guideRoute)
                if (outcome.keyEnds) loadDeletion()
            }
        }
    }

    private fun showError(
        phase: String,
        notice: MeNotice,
    ) {
        if (phase == AccountRaw.APPLY) {
            reuseKey = null
            update { it.copy(error = notice) }
        } else {
            update { it.copy(pendingNotice = notice) }
        }
    }

    /** 前置补办（10001 / 10004 / 10005）：只引导、不重放；页面不可见或许可已变时保留目标、给手动入口（APP-09 第 51 条）。 */
    private fun guide(route: String?) {
        route ?: return
        if (onScreen) deps.env.open(route) else update { it.copy(guideRoute = route) }
    }

    /** 手动入口（被拦下的引导）：页面此刻在栈顶才打开，用过即清。 */
    fun openGuide(version: Long) {
        val route = s.guideRoute ?: return
        if (!bound(version)) return
        update { it.copy(guideRoute = null) }
        deps.env.open(route)
    }

    private fun openStepUp(
        phase: String,
        intent: Intent?,
    ) {
        val account = identity.account ?: return
        update { it.copy(submitting = phase == AccountRaw.APPLY) }
        stepUp.open(
            AccountRaw.ACCOUNT_DELETION,
            identity.owner,
            onToken = { token ->
                if (!alive()) return@open
                if (phase == AccountRaw.APPLY) {
                    val key = reuseKey
                    if (key == null) {
                        update { it.copy(submitting = false) }
                    } else {
                        stepUpReplayed = true
                        update { it.copy(submitting = true) }
                        send(account, key, token)
                    }
                } else if (intent != null) {
                    refreshPending()
                    if (s.pending?.key != intent.key) return@open
                    intent.stepUpReplayed = true
                    runConfirm(intent, token)
                }
            },
            onClosed = {
                if (phase == AccountRaw.APPLY) {
                    // 首次提交的键从未结果未知：本次不用了（held 的记录仍由存储保留）
                    reuseKey = null
                    update { it.copy(submitting = false) }
                }
                refreshPending()
            },
        )
    }

    // ---------------------------------------------------------------- 待确认：确认 / 放弃

    private class Intent(
        val account: String,
        val epoch: Long,
        val key: String,
    ) {
        var stepUpReplayed = false
    }

    private fun intentFor(
        key: String,
        version: Long,
    ): Intent? {
        val pending = s.pending?.takeIf { it.key == key } ?: return null
        if (!bound(version) || pending.inFlight) return null
        val account = identity.account ?: return null
        return Intent(account, identity.epoch, key)
    }

    private fun target(intent: Intent): PendingTarget =
        PendingTarget(
            intent.account,
            intent.key,
            // R3-03：确认 / 放弃同样持有完整固定身份；作用域变了不发，记录保持待确认，等新实例里的用户操作
            // RC-02：发送前比完整固定身份；已写出的确认 / 放弃同账号同登录世代照常结算
            writeOwnership { !deps.shared.deletionGate.active },
        )

    /** 写请求的持续归属（R3-03）：只比较构造时的完整固定身份，与页面可见性、离页无关（离页不取消写请求）。 */
    private fun ownsWrite(): Boolean = identity.current(deps.env)

    /**
     * RC-02、共性第 48 条：写请求的归属分两层（G96「已写出的照常结算」）——
     * - 发送前闸门（sendAllowed）：构造时的完整固定身份（[ownsWrite]：账号、登录世代、作用域、隐私 / 环境世代）。首次发送、等待刷新结束后与每次实际写出前
     *   （含刷新后的续发、重签重发）都核对，任一分量变了（含同账号同世代的受限 → full）尚未写出的不发（本地拒绝 call_cancelled）。
     * - 已发请求的存活条件（stillOwned）：[writeAlive]，同账号 + 同登录世代（隐私 / 环境世代由 epochs 另行比较）。作用域变化不经 watchAbandoned 取消
     *   已写出的请求，迟到的结果照常共享结算；旧页面是否更新界面仍由 alive() 决定。
     */
    private fun writeOwnership(gate: () -> Boolean): CallOwnership =
        CallOwnership(deps.api.epochs(), sendAllowed = { gate() && ownsWrite() }, stillOwned = ::writeAlive)

    /** 已发写请求的存活条件（RC-02）：仍是同一账号、同一登录世代；与作用域、页面可见性、离页无关。 */
    private fun writeAlive(): Boolean = deps.env.account() == identity.account && deps.env.sessionEpoch() == identity.epoch

    /** 【确认上一次提交】：原键、原体重发（二次验证照常）。 */
    fun confirm(
        key: String,
        version: Long,
    ) {
        val intent = intentFor(key, version) ?: return
        if (deps.shared.deletionGate.active) {
            update { it.copy(pendingNotice = MeNotice(AccountRaw.RETRY_LATER_KEY, ++ids)) }
            return
        }
        val token =
            deps.stepUp.tokens.take(AccountRaw.ACCOUNT_DELETION, intent.account, deps.clock(), intent.epoch, identity.scope)
        runConfirm(intent, token)
    }

    private fun runConfirm(
        intent: Intent,
        token: String?,
    ) {
        if (!alive()) return
        val pendingVersion = s.pendingVersion
        sentViewGen = viewGen
        markInFlight(true)
        deps.service.launch {
            val result = deps.api.confirmPending(REQUEST_DELETION, token, target(intent))
            // 待确认版本在本次结算通知之前取：自己的通知会触发同步刷新，不能把自己的结果当成「别的在途响应」
            val stale = s.pendingVersion != pendingVersion
            val outcome = AccountResult.of(result)?.let { DeletionOutcomePolicy.of(AccountRaw.CONFIRM, it, source, true) }
            settleShared(intent.account, intent.epoch, result, outcome)
            deps.shared.notifyPending(
                PendingSettlement(intent.account, intent.epoch, OPERATION, intent.key, outcome?.nextStateRaw, outcome?.textKey),
            )
            if (!alive()) return@launch
            markInFlight(false)
            if (stale) {
                refreshPending()
                return@launch
            }
            if (outcome == null) {
                update { it.copy(pendingNotice = MeNotice(AccountRaw.RETRY_LATER_KEY, ++ids)) }
                refreshPending()
                return@launch
            }
            applyOutcome(outcome, result, AccountRaw.CONFIRM, intent)
        }
    }

    /** 【放弃上一次提交】：先弹二次确认（绑定点击时的键与一次性编号）。 */
    fun askAbandon(
        key: String,
        version: Long,
    ) {
        val pending = s.pending?.takeIf { it.key == key } ?: return
        if (!bound(version) || pending.inFlight) return
        if (deps.shared.deletionGate.active) {
            update { it.copy(pendingNotice = MeNotice(AccountRaw.RETRY_LATER_KEY, ++ids)) }
            return
        }
        update { it.copy(abandonDialog = MeDialog(key, ++dialogIds)) }
    }

    /** 【再想想】、系统返回（同一编号才生效）。 */
    fun cancelAbandon(dialog: MeDialog) {
        if (s.abandonDialog == dialog) update { it.copy(abandonDialog = null) }
    }

    /** 【确定放弃】：调作废接口（action=account_deletion）。 */
    fun abandon(
        dialog: MeDialog,
        version: Long,
    ) {
        if (s.abandonDialog != dialog) return
        update { it.copy(abandonDialog = null) }
        val intent = intentFor(dialog.kind, version) ?: return
        if (deps.shared.deletionGate.active) {
            update { it.copy(pendingNotice = MeNotice(AccountRaw.RETRY_LATER_KEY, ++ids)) }
            return
        }
        val pendingVersion = s.pendingVersion
        sentViewGen = viewGen
        markInFlight(true)
        deps.service.launch {
            val result = deps.api.abandonPending(REQUEST_DELETION, target(intent))
            val stale = s.pendingVersion != pendingVersion
            val outcome = AccountResult.ofAbandon(result)?.let { DeletionOutcomePolicy.of(AccountRaw.ABANDON, it, source, true) }
            settleShared(intent.account, intent.epoch, result, outcome)
            deps.shared.notifyPending(
                PendingSettlement(intent.account, intent.epoch, OPERATION, intent.key, outcome?.nextStateRaw, outcome?.textKey),
            )
            if (!alive()) return@launch
            markInFlight(false)
            if (stale) {
                refreshPending()
                return@launch
            }
            if (outcome == null) {
                update { it.copy(pendingNotice = MeNotice(AccountRaw.RETRY_LATER_KEY, ++ids)) }
                refreshPending()
                return@launch
            }
            applyOutcome(outcome, result, AccountRaw.ABANDON, null)
        }
    }

    private fun markInFlight(busy: Boolean) {
        localBusy = busy
        update { state ->
            val pending = state.pending ?: return@update state
            state.copy(pending = pending.copy(inFlight = busy), pendingNotice = if (busy) null else state.pendingNotice)
        }
    }

    // ---------------------------------------------------------------- 撤回

    /** 【撤回注销】（渲染时的版本与进度）：不弹确认框（G16），在途不可再点；结果未知不自动重发（G17）。 */
    @Suppress("ReturnCount")
    fun cancel(version: Long) {
        if (!bound(version) || s.cancelling) return
        val deletion = (s.read as? DeletionRead.Ok)?.deletion ?: return
        if (!deletion.canCancel) return
        if (deps.shared.cancelGate.active) {
            update { it.copy(error = MeNotice(RATE_KEY, ++ids)) }
            return
        }
        val account = identity.account ?: return
        val epoch = identity.epoch
        val probe = deps.shared.cancelGate.probe()
        // R3-03、RC-02：撤回同样发送前比完整固定身份，已写出的同账号同登录世代照常结算
        val owner = writeOwnership(probe::allowed)
        sentViewGen = viewGen
        update { it.copy(cancelling = true, error = null) }
        deps.service.launch {
            val result = deps.api.send(ApiCall(CANCEL_DELETION, ownership = owner, expectedAccount = account))
            val input = AccountResult.of(result)
            val outcome = input?.let { DeletionCancelPolicy.of(it, source) }
            if (deps.env.sessionEpoch() == epoch && deps.env.account() == account) {
                // 共性 49：限流写入前复核完整固定身份（含作用域）
                if (ownsWrite()) outcome?.disableMs?.let(deps.shared.cancelGate::start)
                if (result is ApiResult.Success) deps.env.deletionChanged()
            }
            if (!alive()) return@launch
            update { it.copy(cancelling = false) }
            if (outcome == null) {
                val key =
                    when {
                        probe.gated(result) -> RATE_KEY
                        AccountResult.refreshOnly(result) -> AccountRaw.RETRY_LATER_KEY
                        else -> NETWORK_KEY
                    }
                if (result !is ApiResult.SessionEnded) update { it.copy(error = MeNotice(key, ++ids)) }
                return@launch
            }
            onCancelled(outcome, result)
        }
    }

    private fun onCancelled(
        outcome: DeletionCancelOutcome,
        result: ApiResult,
    ) {
        if (result is ApiResult.Success) {
            cancelSucceeded(outcome.deletion)
            return
        }
        val traceId = (result as? ApiResult.Failure)?.traceId
        update { it.copy(error = MeNotice(outcome.textKey, ++ids, traceId, outcome.trace6 != null)) }
        if (outcome.refetchDeletion) {
            if (result is ApiResult.TransportFailure) reconcileCancel() else loadDeletion()
        }
    }

    /** 撤回成功的结算（G18）：推进读取世代，按来源去向；页面不在了只做共享部分。 */
    private fun cancelSucceeded(deletion: com.couli.core.me.account.Deletion?) {
        deletionGen += 1
        update { it.copy(read = deletion?.let(DeletionRead::Ok) ?: DeletionRead.None, checked = false, error = null) }
        when (source) {
            AccountRaw.SETTINGS, AccountRaw.FORCE_UPDATE -> {
                val done = onScreen && deps.env.finishDeletion(entryId, source, CANCELLED_TOAST)
                if (!done) toast(CANCELLED_TOAST)
            }

            else -> {
                toast(CANCELLED_TOAST)
            }
        }
        loadWallet()
    }

    /**
     * 撤回结果未知后的核对读取（G17、G61、共性第 14、33 条）：复用统一的 getDeletion 读取（发送前闸门、Retry-After 结算、读取失败标记与【重试】、trace6），
     * 保留核对上下文；查明 cancelled（不是系统撤销）走同一套成功结算，仍在冷静期按读取结果落地、保留人工重试，不自动重发撤回。
     */
    private fun reconcileCancel() {
        cancelReconcile = true
        loadDeletion()
    }

    // ---------------------------------------------------------------- 其他出口

    /** 【去提现】（G12：只在 full、不是从强更进入时显示）。 */
    fun goWithdraw(version: Long) {
        if (bound(version) && view()?.showGoWithdraw == true) deps.env.open(WITHDRAW)
    }

    /** 【去绑定手机号】（G10：只 full 会话）。 */
    fun goBindPhone(version: Long) {
        if (bound(version) && !identity.restricted) deps.env.open(BIND_PHONE)
    }

    /** 【联系客服】：统一客服出口。 */
    fun contactCs(version: Long) {
        if (bound(version)) deps.env.customerService(ROUTE)
    }

    fun onVisibility(shown: Boolean) {
        if (shown != visible) viewGen += 1
        visible = shown
    }

    fun consumeToast(id: Long) {
        if (s.toast?.id == id) update { it.copy(toast = null) }
    }

    private fun toast(key: String) = update { it.copy(toast = MeNotice(key, ++ids, assertive = false)) }

    /** 出栈、换代的同步出口：作废读请求（写请求不取消）、收起面板、清空勾选与资金区。 */
    fun leave() {
        if (left) return
        left = true
        stepUp.dismiss()
        watchers.forEach { it.cancel() }
        watchers.clear()
        deps.me.repo.release(this)
        val owners = readOwners.toList()
        readOwners.clear()
        owners.forEach(deps.api::cancelOwned)
        mutable.value = DeleteAccountState(version = s.version + 1)
        deps.env.deletionChanged()
    }

    private inline fun update(change: (DeleteAccountState) -> DeleteAccountState) {
        mutable.value = change(mutable.value)
    }

    companion object {
        const val ROUTE = "DeleteAccount"
        const val OPERATION = "requestDeletion"
        const val REQUEST_DELETION = "requestDeletion"
        const val GET_DELETION = "getDeletion"
        const val CANCEL_DELETION = "cancelDeletion"
        const val GET_WALLET = "getWalletSummary"
        const val WITHDRAW = "Withdraw"
        const val BIND_PHONE = "BindPhone"
        const val RATE_KEY = "error.42901"
        const val NETWORK_KEY = com.couli.core.me.MeDraftTexts.NETWORK_KEY
        const val BAD_DATA_KEY = "error.50001"
        const val UNFINISHED_KEY = MeNotice.UNFINISHED_KEY
        const val ALREADY_DONE_KEY = "pending_confirm.already_done"
        const val CANCELLED_TOAST = "deletion.cancelled_toast"
        const val PHONE_UNKNOWN_KEY = "me.load_failed"

        /** 本次 30416 的覆盖只作用于可申请一族（R1-08）。 */
        private val NEGATIVE_OVERRIDABLE = setOf(DeletionView.INITIAL, DeletionView.NEGATIVE, DeletionView.NO_PHONE)
    }
}
