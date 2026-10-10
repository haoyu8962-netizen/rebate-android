package com.couli.feature.wallet

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.wallet.EstimateLine
import com.couli.core.wallet.HelpLink
import com.couli.core.wallet.PayoutAccountDisplay
import com.couli.core.wallet.PayoutAccountView
import com.couli.core.wallet.SendProbe
import com.couli.core.wallet.SensitiveOutcome
import com.couli.core.wallet.WalletError
import com.couli.core.wallet.WalletErrors
import com.couli.core.wallet.WalletJson
import com.couli.core.wallet.WalletRoutes
import com.couli.core.wallet.WithdrawAmountInput
import com.couli.core.wallet.WithdrawBlockGuide
import com.couli.core.wallet.WithdrawEstimateGate
import com.couli.core.wallet.WithdrawRules
import com.couli.core.wallet.WithdrawRulesDisplay
import com.couli.core.wallet.WithdrawRulesView
import com.couli.generated.Routes
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 估算行的界面状态：隐藏、计算中（不播报）、结果（只读结果）。 */
sealed interface EstimateUi {
    data object Hidden : EstimateUi

    data object Calculating : EstimateUi

    data class Shown(
        val line: EstimateLine,
        val amountFen: Long,
    ) : EstimateUi
}

/** 提现页的一次状态快照。[version] 每次换 rules 数据加 1：按钮回调带渲染时的版本，执行时核对仍是它。 */
data class WithdrawSnapshot(
    val phase: String = LOADING,
    val loadError: Notice? = null,
    val rules: WithdrawRules? = null,
    val amountText: String = "",
    val estimate: EstimateUi = EstimateUi.Hidden,
    val payout: PayoutAccountDisplay? = null,
    val helpTarget: HelpLink.Target? = null,
    val slaText: String? = null,
    val version: Long = 0,
    /** 页内读取（估算、收款账号）失败的提示：42901、5xxxx + trace6（APP-09 共性第 3 条）；整页已有内容时不换成错误态。 */
    val readError: Notice? = null,
    /** 收款账号行读取失败的提示（与 rules 的 [readError] 分开保存）。 */
    val payoutError: Notice? = null,
    /** 提交得到 30306 后本页记下的服务端阻断（共性第 10 条）：只有之后成功取得、明确允许的 rules 才解除。 */
    val serverBlocked: Boolean = false,
    /** 提交已成功、等页面回到可见再进提现记录（共性第 41 条）：期间【提交】不可用。 */
    val successPending: Boolean = false,
) {
    val inputFen: Long? get() = WithdrawAmountInput.parse(amountText)

    fun display(): WithdrawRulesDisplay? = rules?.let { WithdrawRulesView.of(it, inputFen, slaText) }

    fun guide(): WithdrawBlockGuide.View =
        rules?.takeIf { !it.canWithdraw }?.let { WithdrawBlockGuide.view(it.blockCode, it.blockReason) } ?: NO_GUIDE

    companion object {
        const val LOADING = "loading"
        const val CONTENT = "content"
        const val ERROR = "error"
        private val NO_GUIDE = WithdrawBlockGuide.View(WithdrawBlockGuide.NONE)
    }
}

/** 一次性事件（读屏播报、收起键盘）。 */
sealed interface WithdrawEvent {
    data class Announce(
        val key: String,
        val vars: Map<String, Long> = emptyMap(),
    ) : WithdrawEvent

    data object HideKeyboard : WithdrawEvent
}

/**
 * 提现申请页执行器（Withdraw 路由；BR-WDR-04 / 07 细则，brief §2.4、§2.5）：
 * - 每次进入、回到前台都取 rules（同一页面实例，序号递增，带上当前合法金额；G05）；页头、上限、档位只取已应用过的最新序号，旧序号不覆盖；
 * - 输入变化立即「计算中…」，合法金额停顿约 0.5 秒再带 amount_fen 取 rules；估算响应四项都对得上（[WithdrawEstimateGate]）才显示；
 *   清空或不合法隐藏且不发请求；请求失败或 est_net_fen 为 null 隐藏；
 * - can_withdraw=false 时按 block_code 引导（[WithdrawBlockGuide]，用户点按钮才打开前置页），【提交】置灰；
 * - 提交与待确认由 [SensitiveFlow]（createWithdrawal、action=withdraw）；成功用 Router 以 WithdrawRecords {withdrawal_id} 替换本页（G19），
 *   并标记钱包需要刷新。
 * 请求绑定页面实例与会话世代；页面出栈（同步出口）后读请求作废，迟到的响应不改页面。只在主线程使用。
 */
@Suppress("TooManyFunctions") // 页面每个入口一个函数
class WithdrawController(
    private val deps: WalletDeps,
    private val scope: CoroutineScope,
    initialAmount: String = "",
    /** 本页的回退栈条目（成功替换只作用于它，共性第 50 条）。 */
    private val entryId: String? = null,
    /**
     * 本表单的固定身份（APP-09 共性第 65 条，Android RC-06）：与 [initialAmount] 来自同一次读取、成对传入（ViewModel 按它校验恢复的金额）；缺省时在
     * 构造这一刻一次性取得（此时没有恢复数据）。之后本页的估算、页头与收款账号读取、提交都只用它，当前会话只用于和它比较。
     */
    initialOwner: FormOwner = FormOwner.of(deps.env),
) : FlowHost {
    private var left = false

    /** 页面实例（去前置页再回来仍是同一实例；关闭后再进来是新实例）。 */
    val pageInstance: String = UUID.randomUUID().toString()
    private var seq = 0L
    private var appliedSeq = 0L
    private var estimateJob: Job? = null
    private val owners = mutableListOf<CallOwnership>()

    private val mutable = MutableStateFlow(WithdrawSnapshot(amountText = initialAmount))
    val state: StateFlow<WithdrawSnapshot> = mutable.asStateFlow()
    private val s: WithdrawSnapshot get() = mutable.value

    private val events = MutableSharedFlow<WithdrawEvent>(extraBufferCapacity = EVENT_BUFFER)
    val effects: SharedFlow<WithdrawEvent> = events.asSharedFlow()

    val flow =
        SensitiveFlow(
            deps,
            scope,
            operationId = OPERATION,
            action = ACTION,
            outcomeOp = SensitiveOutcome.WITHDRAW,
            gate = deps.gates.withdraw,
            host = this,
            alive = { !left },
            initialOwner = initialOwner,
        )

    val alive: Boolean get() = !left

    /** 读请求的共享禁用期（页内【重试】置灰）。 */
    val readBlocked: StateFlow<Boolean> get() = deps.gates.read.blocked

    /** 进入页面：取 rules（带上恢复的合法金额）、收款账号、未决记录。 */
    fun start() {
        if (left) return
        if (!deps.env.loggedIn()) return
        val config = deps.env.configData()
        update {
            it.copy(
                slaText = ((config?.get("texts") as? JsonObject)?.get(SLA_KEY) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content,
                helpTarget = HelpLink.target(config?.get(HELP_LINKS) as? JsonObject, HelpLink.WITHDRAW_RULES, Routes.all),
            )
        }
        runCatching { deps.api.restorePending() }
        flow.refreshPending()
        fetchRules(s.inputFen, initial = true)
        fetchPayout()
    }

    /** 回到前台（被前置页盖住后返回、App 回前台）：同一实例、保留金额，立即重取 rules（G05）与收款账号，重读未决记录。 */
    fun onResume() {
        if (left) return
        flow.refreshPending()
        fetchRules(s.inputFen, initial = s.rules == null)
        fetchPayout()
    }

    // ---------------------------------------------------------------- rules 与估算

    /**
     * 取 rules；带金额（估算）时整次请求绑定这张表单的身份 [form]（APP-09 共性第 64 条，Android RC-05）：发出前核对它仍是本表单身份且仍是当前会话，
     * 账号与世代取自 [form]（不重新读取执行时的会话），网络层按 expectedAccount 在读取凭据前再核对，迟到响应也按它核对。令牌已换、清空通知还没执行时，
     * 旧表单的金额不会以新会话的身份发出：估算行收起；首次加载改为不带金额读取页头（页头读取同样只用本表单的固定身份，共性第 65 条）。
     */
    private fun fetchRules(
        amount: Long?,
        initial: Boolean,
        form: FormOwner = flow.owner,
    ) {
        if (!deps.env.loggedIn() || formMismatch(amount, initial, form)) return
        if (deps.gates.read.active) {
            readGated(amount)
            return
        }
        val request = ++seq
        val bound = form.takeIf { amount != null }
        // 与金额无关的页头读取也只用本表单的固定身份（共性第 65 条）：账号与世代不读取执行时的当前会话；网络层按 expectedAccount 在读取凭据前核对，
        // 令牌已换、清空通知还没执行时不会以新账号的身份取得页头放进旧表单
        val page = bound ?: flow.owner
        val account = page.account
        val sendEpoch = page.epoch
        if (initial && s.rules == null) update { it.copy(phase = WithdrawSnapshot.LOADING, loadError = null) }
        val probe = deps.gates.read.probe()
        val owner = ownership(sendEpoch, probe, bound)
        scope.launch {
            val call =
                ApiCall(
                    RULES_OPERATION,
                    query = amount?.let { "amount_fen=$it" },
                    ownership = owner,
                    expectedAccount = account,
                )
            val result = deps.api.send(call)
            owners -= owner
            if (left || deps.env.sessionEpoch() != sendEpoch) return@launch
            if (bound != null && !bound.current(deps.env)) return@launch
            onRules(result, request, account, amount, probe)
        }
    }

    /**
     * 带金额的读取而 [form] 已不是本表单身份或当前会话（共性第 64 条）：不发；这次估算的「计算中」收起，首次加载改为不带金额读取页头。返回是否拦下。
     */
    private fun formMismatch(
        amount: Long?,
        initial: Boolean,
        form: FormOwner,
    ): Boolean {
        if (amount == null || ownsForm(form)) return false
        val calculating = amount == s.inputFen && s.estimate == EstimateUi.Calculating
        if (!left && form == flow.owner && calculating) update { it.copy(estimate = EstimateUi.Hidden) }
        if (initial) fetchRules(null, initial = true, form = form)
        return true
    }

    /** [owner] 是本表单当前的身份，且仍是当前会话（共性第 52、59、64 条）。 */
    private fun ownsForm(owner: FormOwner): Boolean = !left && owner == flow.owner && owner.current(deps.env)

    /**
     * rules / 估算被本地限流拦下（发送前闸门，或点击时已在禁用期；APP-09 共性第 45 条）：同样结算本次读取——没有页头的进加载失败态，已有页头的页内提示
     * 42901 与【重试】；这次估算（仍是当前金额的）隐藏，不留下没有在途请求的「计算中」。
     */
    private fun readGated(amount: Long?) {
        if (s.rules == null) {
            update { it.copy(phase = WithdrawSnapshot.ERROR, loadError = Notice("error.42901", nextId())) }
        } else {
            update { it.copy(readError = Notice("error.42901", nextId())) }
        }
        if (amount != null && amount == s.inputFen && s.estimate == EstimateUi.Calculating) update { it.copy(estimate = EstimateUi.Hidden) }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // 成功、失败两路；页头与估算各自核对
    private fun onRules(
        result: ApiResult,
        request: Long,
        account: String?,
        amount: Long?,
        probe: SendProbe,
    ) {
        val rules = (result as? ApiResult.Success)?.let { WalletJson.rules(it.data) }
        if (rules == null) {
            val error = WalletErrors.of(result)
            if (error.kind == WalletError.CANCELLED) {
                // 发送前被禁用期闸门拦下：按本地限流结算（共性第 45、62 条，按本请求记下的拒绝原因）；被新请求取代的不动
                if (probe.gated(error) && request == seq) readGated(amount)
                return
            }
            if (error.kind == WalletError.RETRY_AFTER) deps.gates.read.start(error.disableMillis)
            if (error.kind == WalletError.FORCE_UPDATE) deps.env.forceUpdate()
            // 估算请求失败：估算行隐藏（页头不变）；整页还没有 rules：加载失败态与【重试】
            if (s.rules == null && request > appliedSeq) {
                update {
                    it.copy(
                        phase = WithdrawSnapshot.ERROR,
                        loadError = Notice(error.textKey, nextId(), error.traceId, error.showTrace6),
                    )
                }
            } else if (request > appliedSeq) {
                // 已有页头时读取失败：页头不变，页内提示（42901 / 5xxxx 带 trace6）；读取失败不恢复被阻断的【提交】
                update { it.copy(readError = Notice(error.textKey, nextId(), error.traceId, error.showTrace6)) }
            }
            if (amount != null && estimateOwned(request, account, amount)) update { it.copy(estimate = EstimateUi.Hidden) }
            return
        }
        // 页头、上限、档位只取已应用过的最新序号（AC-S2-80 ⑩⑪⑫）
        if (request > appliedSeq) {
            appliedSeq = request
            // 30306 阻断只由阻断之后发出、明确允许的 rules 解除（旧序号的 rules 不得恢复）
            val unblock = request > blockedAtSeq && rules.canWithdraw
            update {
                it.copy(
                    phase = WithdrawSnapshot.CONTENT,
                    rules = rules,
                    loadError = null,
                    readError = null,
                    serverBlocked = it.serverBlocked && !unblock,
                    version = it.version + 1,
                )
            }
        }
        if (amount != null && estimateOwned(request, account, amount)) {
            val line = WithdrawRulesView.estimate(rules, amount)
            update { it.copy(estimate = if (line == EstimateLine.Hidden) EstimateUi.Hidden else EstimateUi.Shown(line, amount)) }
            if (line != EstimateLine.Hidden) announceEstimate(amount, line)
        }
    }

    /** 估算响应的归属四项（BR-WDR-04 细则）。 */
    private fun estimateOwned(
        request: Long,
        account: String?,
        amount: Long,
    ): Boolean =
        WithdrawEstimateGate.shouldApply(
            WithdrawEstimateGate.Request(account, pageInstance, request, amount),
            WithdrawEstimateGate.Current(deps.env.account(), pageInstance, seq, s.inputFen),
        )

    private var announcePending = false

    private fun announceEstimate(
        amount: Long,
        line: EstimateLine,
    ) {
        if (!announcePending) return
        announcePending = false
        val net =
            when (line) {
                is EstimateLine.NetOnly -> line.netFen
                is EstimateLine.Full -> line.netFen
                EstimateLine.Hidden -> return
            }
        events.tryEmit(WithdrawEvent.Announce(QUICK_SPOKEN_KEY, mapOf("amount" to amount, "net" to net)))
    }

    /**
     * 输入变化（键盘、粘贴、自动填充）：过滤（G18），立即「计算中…」，停顿 0.5 秒后带金额取 rules；不合法隐藏且不发请求。[owner] 是渲染这张表单时的
     * 身份（APP-09 共性第 59 条，Android RC-01）：不是本表单当前的身份、或已不是当前会话的旧回调不写入（返回 false，调用方也不按它保存金额）。
     */
    fun onAmountChange(
        text: String,
        owner: FormOwner = flow.owner,
    ): Boolean {
        if (!ownsForm(owner)) return false
        val next = WithdrawAmountInput.accept(s.amountText, text)
        if (next != s.amountText) setAmount(next, debounce = true, owner)
        return true
    }

    /**
     * 【全部提现】：只填 max_withdrawable_fen（G18 的写法）。按钮回调带渲染时的版本与表单身份 [owner]（APP-09 共性第 64 条，Android RC-05）：写金额前
     * 核对身份——令牌已换、清空通知还没执行时 rules 版本还没变，版本核对拦不住，靠身份核对拦下；同一身份继续交给估算请求。
     */
    fun fillAll(
        version: Long,
        owner: FormOwner,
    ) {
        val display = current(version)?.takeIf { ownsForm(owner) }?.display() ?: return
        val fen = display.allFen ?: return
        announcePending = true
        setAmount(WithdrawAmountInput.display(fen), debounce = false, owner)
    }

    /** 快捷档位：只填服务端给的档位；与【全部提现】同样携带并核对渲染时的表单身份 [owner]（共性第 64 条）。 */
    fun fillQuick(
        version: Long,
        fen: Long,
        owner: FormOwner,
    ) {
        val display = current(version)?.takeIf { ownsForm(owner) }?.display() ?: return
        if (fen !in display.quick) return
        announcePending = true
        setAmount(WithdrawAmountInput.display(fen), debounce = false, owner)
    }

    /** 写入金额并安排估算；[owner] 是写入这次金额的表单身份，延时估算到点时再核对，并原样交给 [fetchRules]（共性第 64 条）。 */
    private fun setAmount(
        text: String,
        debounce: Boolean,
        owner: FormOwner,
    ) {
        estimateJob?.cancel()
        // 输入一变，在途的估算就不再属于当前金额（序号前进）
        seq += 1
        val fen = WithdrawAmountInput.parse(text)
        // 是否带金额重取 rules 只看金额是否合法、输入是否允许（APP-09 共性第 23 条，BR-WDR-04、G29）：旧 rules 的 can_withdraw=false 不拦，
        // 服务端条件恢复后靠金额变化取得新结论；估算展示与【提交】仍只取最新服务端结果
        val requestable = fen != null && s.display()?.amountEditable == true
        update { it.copy(amountText = text, estimate = if (requestable) EstimateUi.Calculating else EstimateUi.Hidden) }
        flow.clearFieldErrors()
        if (!requestable || fen == null) return
        estimateJob =
            scope.launch {
                if (debounce) delay(ESTIMATE_DEBOUNCE_MILLIS)
                if (!left && s.inputFen == fen) fetchRules(fen, initial = false, form = owner)
            }
    }

    // ---------------------------------------------------------------- 收款账号行

    /** 收款账号读取的请求序号（APP-09 共性第 19 条）：进入、回流、刷新都推进并作废旧请求，只有最新一轮可更新账号行与提示。 */
    @Volatile private var payoutSeq = 0L
    private var payoutOwner: CallOwnership? = null

    @Suppress("CyclomaticComplexMethod") // 序号、归属、错误统一处理逐项
    private fun fetchPayout() {
        // 推进序号放在任何提前返回之前：这一刻之前发出的读取一律作废
        val request = ++payoutSeq
        payoutOwner?.let {
            owners -= it
            deps.api.cancelOwned(it)
        }
        payoutOwner = null
        // 重读开始即让旧账号行失效（共性第 44 条）：只有这一轮取得的账号才算当前；待确认说明在此期间用 fallback
        update { it.copy(payout = null) }
        if (!deps.env.loggedIn()) return
        if (deps.gates.read.active) {
            // 本地限流拦下：旧账号行已清，给 42901 与【重试】
            update { it.copy(payoutError = Notice("error.42901", nextId())) }
            return
        }
        // 收款账号读取绑定本表单的固定身份（共性第 65 条）：不读取执行时的当前会话，网络层按 expectedAccount 核对账号
        val form = flow.owner
        val sendEpoch = form.epoch
        // 本请求的发送前限流闸门（共性第 62 条）：被它拦下时记下原因，回调按原因结算
        val probe = deps.gates.read.probe()
        val owner =
            CallOwnership(deps.api.epochs(), sendAllowed = probe::allowed) {
                !left && deps.env.sessionEpoch() == sendEpoch && payoutSeq == request
            }.also {
                owners += it
                payoutOwner = it
            }
        scope.launch {
            val result = deps.api.send(ApiCall(PAYOUT_OPERATION, ownership = owner, expectedAccount = form.account))
            owners -= owner
            if (left || deps.env.sessionEpoch() != sendEpoch || payoutSeq != request) return@launch
            val account = (result as? ApiResult.Success)?.let { WalletJson.payoutAccount(it.data) }
            // 未绑定或取不到：不显示收款账号行（待确认句用 fallback）；读取错误统一处理（共性第 3 条），30304 由 rules 引导，不另提示
            val error = if (account == null) WalletErrors.of(result) else null
            if (error?.kind == WalletError.RETRY_AFTER) deps.gates.read.start(error.disableMillis)
            if (error?.kind == WalletError.FORCE_UPDATE) deps.env.forceUpdate()
            // 发送前被禁用期闸门拦下的同样提示 42901（共性第 44、62 条）：按本请求记下的拒绝原因，不看回调时刻的禁用期；30304 由 rules 引导，不另提示
            val gated = probe.gated(error)
            val notify =
                error != null && (error.kind != WalletError.CANCELLED || gated) && (result as? ApiResult.Failure)?.code != REALNAME_REQUIRED
            update {
                it.copy(
                    payout = account?.let(PayoutAccountView::view)?.takeIf { view -> view.bound },
                    // 收款账号读取错误单独保存（共性第 30 条）：rules 成功不清掉它，只有收款账号的下一次成功读取才清
                    payoutError =
                        when {
                            gated -> Notice("error.42901", nextId())
                            notify -> Notice(error.textKey, nextId(), error.traceId, error.showTrace6)
                            else -> null
                        },
                )
            }
        }
    }

    // ---------------------------------------------------------------- 提交与引导

    /**
     * 【提交】：先收起键盘；用当前合法金额生成新键（结局要求沿用的除外）提交。按钮回调带渲染时的版本与表单身份（[owner]，共性第 52 条）：
     * 身份不是当前会话的（会话已换、清空通知还没执行）由 [SensitiveFlow.submit] 在生成键、读取 token 前拦下，不发。
     */
    @Suppress("ReturnCount")
    fun submit(
        version: Long,
        owner: FormOwner = flow.owner,
    ) {
        val snapshot = current(version) ?: return
        val display = snapshot.display() ?: return
        val fen = snapshot.inputFen ?: return
        if (!display.submitEnabled || snapshot.serverBlocked || snapshot.successPending) return
        events.tryEmit(WithdrawEvent.HideKeyboard)
        val body = JsonObject(mapOf(AMOUNT_FEN to JsonPrimitive(fen))).toString()
        flow.submit(body, body, owner)
    }

    /** block_code 引导按钮（用户点了才打开前置页，不自动跳；G05）。 */
    fun openGuide(version: Long) {
        val guide = current(version)?.guide() ?: return
        val route = guide.route ?: return
        deps.env.open(route, EMPTY)
    }

    fun retry() {
        if (left || deps.gates.read.active) return
        fetchRules(s.inputFen, initial = true)
        fetchPayout()
    }

    fun openPayout() = deps.env.open(WalletRoutes.PAYOUT_ACCOUNT, EMPTY)

    fun openRecords() = deps.env.open(WalletRoutes.WITHDRAW_RECORDS, EMPTY)

    fun openRules() {
        val target = s.helpTarget ?: return
        deps.env.open(target.route, target.params)
    }

    /** 44001 弹窗的【联系客服申诉】：统一客服出口接入前经 Router 打开 Help（同 APP-08 G09）。 */
    fun appeal() {
        flow.dismissRisk()
        deps.env.contactService(WalletRoutes.WITHDRAW)
    }

    // ---------------------------------------------------------------- FlowHost

    /** 提交成功时页面不在栈顶可见：替换为提现记录的导航留到页面回到可见时再做（APP-09 共性第 37、41 条）。 */
    private var deferredSuccess: Pair<JsonObject?, Boolean>? = null

    /** 页面可见性（ON_RESUME / ON_PAUSE）。 */
    fun onVisibility(shown: Boolean) {
        flow.onVisibility(shown)
        if (!shown || left) return
        val (data, done) = deferredSuccess ?: return
        deferredSuccess = null
        navigateSucceeded(data, done)
    }

    /** 共享结算（不看页面是否还在）：钱包摘要需要刷新（G19、共性第 40 条）。 */
    override fun onSharedSuccess(
        account: String,
        epoch: Long,
    ) = deps.env.markWalletDirty(account, epoch)

    /**
     * 成功的页面反馈：「此刻可见」与「发出时的导航许可」分开判断（共性第 41 条）。成功导航是这次提交本身的结果，发起页此刻在栈顶可见就立即进提现记录
     * （发出之后被覆盖又回来也一样）；不可见时先记下，回到可见时消费，期间【提交】不可用。
     */
    override fun onSucceeded(
        data: JsonObject?,
        alreadyDone: Boolean,
    ) {
        if (!flow.visibleNow) {
            deferredSuccess = data to alreadyDone
            update { it.copy(successPending = true) }
            return
        }
        navigateSucceeded(data, alreadyDone)
    }

    private fun navigateSucceeded(
        data: JsonObject?,
        alreadyDone: Boolean,
    ) {
        val id = (data?.get(WalletRoutes.WITHDRAWAL_ID) as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!alreadyDone) events.tryEmit(WithdrawEvent.Announce(SUBMITTED_KEY))
        val params = id?.let { JsonObject(mapOf(WalletRoutes.WITHDRAWAL_ID to JsonPrimitive(it))) } ?: EMPTY
        deps.env.replaceTop(WalletRoutes.WITHDRAW_RECORDS, params, entryId)
    }

    override fun onSettled() {
        if (left) return
        fetchRules(s.inputFen, initial = false)
        fetchPayout()
    }

    override fun onBusinessError(code: Int) {
        if (code != FEATURE_OFF || left) return
        blockedAtSeq = seq
        update { it.copy(serverBlocked = true) }
    }

    /** 记下 30306 阻断时的请求序号：之后发出的 rules 才能解除。 */
    private var blockedAtSeq = Long.MAX_VALUE

    override fun onGuide(route: String) {
        if (!left) deps.env.open(route, EMPTY)
    }

    // ---------------------------------------------------------------- 会话、离页

    /**
     * 会话换代（App 在会话通知里同步调用）：同步清空已输入的金额、估算、rules 与收款账号行（不得在登录新账号后看到旧账号的余额或金额），作废在途读请求，
     * 再按新会话重取；未决记录按新账号重读。
     */
    fun onSessionChanged() {
        if (left) return
        deferredSuccess = null
        seq += 1
        appliedSeq = seq
        payoutSeq += 1
        estimateJob?.cancel()
        cancelAll()
        mutable.value = WithdrawSnapshot(version = s.version + 1)
        // 新表单（金额已清空）的固定身份在此一次性取得（共性第 65 条）
        flow.onSessionChanged(FormOwner.of(deps.env))
        start()
    }

    /** 页面出栈（同步出口）：作废读请求并取消传输；提交、确认、放弃不取消（未决记录兜底）。 */
    fun leave() {
        if (left) return
        left = true
        seq += 1
        payoutSeq += 1
        estimateJob?.cancel()
        cancelAll()
        flow.leave()
    }

    /** 渲染时的版本仍是当前、页面还在，才返回快照（按钮回调用）。 */
    private fun current(version: Long): WithdrawSnapshot? =
        s.takeIf {
            !left && it.version == version &&
                it.phase == WithdrawSnapshot.CONTENT
        }

    private fun ownership(
        sendEpoch: Long,
        probe: SendProbe,
        form: FormOwner? = null,
    ): CallOwnership =
        CallOwnership(deps.api.epochs(), sendAllowed = probe::allowed) {
            // 带表单身份的估算：每次写出前还核对账号与世代仍是表单的（共性第 64 条）
            !left && deps.env.sessionEpoch() == sendEpoch && (form == null || form.current(deps.env))
        }.also { owners += it }

    private fun cancelAll() {
        val cancelled = owners.toList()
        owners.clear()
        cancelled.forEach(deps.api::cancelOwned)
    }

    private var ids = 0L

    private fun nextId(): Long = ++ids

    private inline fun update(change: (WithdrawSnapshot) -> WithdrawSnapshot) {
        mutable.value = change(mutable.value)
    }

    companion object {
        const val OPERATION = "createWithdrawal"
        const val ACTION = "withdraw"
        const val RULES_OPERATION = "getWithdrawRules"
        const val PAYOUT_OPERATION = "getPayoutAccount"
        const val AMOUNT_FEN = "amount_fen"
        const val SLA_KEY = "withdraw.sla_text"
        const val HELP_LINKS = "help_links"
        const val SUBMITTED_KEY = "withdraw.submitted_spoken"
        const val QUICK_SPOKEN_KEY = "withdraw.spoken.quick"
        const val ESTIMATE_DEBOUNCE_MILLIS = 500L
        private const val FEATURE_OFF = 30_306
        private const val REALNAME_REQUIRED = 30_304
        private const val EVENT_BUFFER = 8
        val EMPTY = JsonObject(emptyMap())
    }
}
