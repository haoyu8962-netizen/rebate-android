package com.couli.feature.wallet

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorActionMapper
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PendingTarget
import com.couli.core.network.ResultSignals
import com.couli.core.wallet.Outcome
import com.couli.core.wallet.OutcomeInput
import com.couli.core.wallet.SendProbe
import com.couli.core.wallet.SensitiveOutcome
import com.couli.core.wallet.WalletErrors
import com.couli.core.wallet.WalletRateGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** 待确认的上一笔（取自本机未决记录，按账号隔离；不按时间解除）。 */
data class PendingView(
    val key: String,
    /** 未决记录里的展示摘要（提现：{"amount_fen":…}；收款账号：本机脱敏的 {"method","account","payee"}，G15）。 */
    val summary: String,
    val createdAtMillis: Long,
    /** 本进程正在确认或作废（两个按钮都置灰，WithdrawPendingBusy）。 */
    val inFlight: Boolean,
)

/** 44001 弹窗（WithdrawBlocked / PayoutAccountBlocked）：标题先查 risk_msg.<code>，回落 error.44001。 */
data class RiskDialog(
    val titleKey: String,
    val id: Long,
)

/**
 * 敏感写操作的页面状态（提现、收款账号变更共用）。[phase]：editing（可编辑、可提交）/ submitting（提交中，含二次验证后的重放）/ pending（待确认）。
 * [pendingVersion] 每次进入或离开待确认加 1：一个键结束后，其余在途请求的响应只用于核对，不再改变页面（BR-ID-10 细则）。
 */
data class FlowState(
    val phase: String = EDITING,
    val pending: PendingView? = null,
    val pendingVersion: Long = 0,
    /** 待确认卡片里的提示（retry_later、cannot_confirm、abandon_busy、正在处理）。 */
    val pendingNotice: Notice? = null,
    /** 编辑态的错误条（键结束的业务错误、5xxxx + trace6、42901）。 */
    val error: Notice? = null,
    /** 20001 的字段（提现 amount_fen；收款账号表单字段）。 */
    val fieldErrors: List<String> = emptyList(),
    val fieldErrorKey: String? = null,
    /** 字段错误事件编号：每次 20001 换新（滚到首个错误、移焦点并播报一次，APP-09 共性第 24 条）。 */
    val fieldErrorId: Long = 0,
    val riskDialog: RiskDialog? = null,
    val abandonConfirm: Boolean = false,
    /** 未决记录读不出（安全存储故障）：不能确认未决状态，【提交】置灰（lessons 38）。 */
    val pendingUnreadable: Boolean = false,
    /** 一次性轻提示（已放弃、已经处理完成、已保存）。 */
    val toast: Notice? = null,
    /**
     * 被拦下的自动引导（APP-09 共性第 51 条）：结果要求前置补办、但页面当时不可见或导航许可已变，不自动打开；保留目标路由，页面上给可点的手动入口
     * （[SensitiveFlow.openGuide]，绑定本页与当时的会话），已填内容保留、不自动重提。
     */
    val guideRoute: String? = null,
    /** 本表单所属的账号与登录世代（APP-09 共性第 52 条）：按钮回调带上它，执行前核对仍是当前会话。 */
    val owner: FormOwner = FormOwner(null, Long.MIN_VALUE),
) {
    val canSubmit: Boolean get() = phase == EDITING && pending == null && !pendingUnreadable

    companion object {
        const val EDITING = "editing"
        const val SUBMITTING = "submitting"
        const val PENDING = "pending"
    }
}

/** 发起页接线：成功、引导补办、键结束后的刷新。 */
interface FlowHost {
    /** 业务成功（首次提交、确认上一笔、放弃得到 completed 且原结果成功）：[data] 为原操作的 data。 */
    fun onSucceeded(
        data: JsonObject?,
        alreadyDone: Boolean,
    )

    /** 键结束或待确认解除后：重取页面数据（rules、收款账号）。 */
    fun onSettled() = Unit

    /** 前置补办（RealName、PayoutAccount、LaborAgreement、BindPhone、Login）：补办完回到发起页，不重放。 */
    fun onGuide(route: String) = Unit

    /** 键结束的业务错误（提现 30306 等，APP-09 共性第 10 条）。 */
    fun onBusinessError(code: Int) = Unit

    /**
     * 业务成功的共享结算（APP-09 共性第 40 条）：按点击时的会话归属执行，发起页已出栈、被覆盖也照常（提现：标记钱包摘要需要刷新）；
     * 页面上的反馈与导航另由 [onSucceeded] 按页面是否有效处理。[account]、[epoch] 是结算时已核对过的点击时会话（共性第 61 条，Android RC-03）：
     * 原样交给出口（钱包失效通知），出口不重新读取当前会话。
     */
    fun onSharedSuccess(
        account: String,
        epoch: Long,
    ) = Unit
}

/**
 * 提现与收款账号变更的提交、二次验证、未决键与待确认（brief §0 第 3、4 条；BR-ID-10 细则「敏感操作的幂等键」；BR-WDR-07 细则）：
 * - 每次用户点【提交】（【保存】）生成一个新的 Idempotency-Key（上一次结局要求沿用原键的除外，[SensitiveOutcome] 的 nextSubmitNewKey），首次发送前
 *   由网络层 PendingKeyStore 写入未决记录（键、operation、原请求体、脱敏摘要、账号；生产后端是 Keystore 加密的 SecurePendingKeyBackend）；
 * - 10003 → 二次验证面板，拿到 token 后沿用原键重放 1 次；10002 / 10402 网络层自动处理；补办类只引导、不重放；
 * - 结果未知 → 待确认：新的提交入口置灰，只有【确认上一笔】（原键原体重发，二次验证照常）与【放弃上一笔】（二次确认后作废）；
 * - 待确认状态的来源始终是本机未决记录（[refreshPending]），进入页面、回前台、登录成功、会话换代后都按当前账号重读；不按时间解除；别的账号的不读不发；
 * - 提交、确认、放弃离开页面不取消（未决记录兜底）：页面出栈后迟到的响应不改页面，结论只落到未决记录（网络层结算）。
 * - F-AND-37（FU-AND-0717）：复用的 step-up token 按账号 + 表单 / 待确认意图固定的登录世代 + 此刻的会话作用域 + action 核对（StepUpTokenCache，与 APP-13a 共用），
 *   换世代、换作用域后旧 token 不复用。
 * 只在主线程使用。
 */
@Suppress("TooManyFunctions", "LongParameterList", "ReturnCount", "ComplexCondition", "LargeClass") // 提交、二次验证、确认、放弃、恢复、接管各一组入口；依赖逐项给；逐项提前返回
class SensitiveFlow(
    private val deps: WalletDeps,
    private val scope: CoroutineScope,
    /** openapi operationId：createWithdrawal / savePayoutAccount。 */
    val operationId: String,
    /** step-up action：withdraw / payout_account_change。 */
    val action: String,
    /** 结局表的 op：withdraw / payout_account。 */
    private val outcomeOp: String,
    private val gate: WalletRateGate,
    private val host: FlowHost,
    private val alive: () -> Boolean,
    /**
     * 本表单的固定身份（APP-09 共性第 65 条，Android RC-06）：由发起页的执行器在构造时一次性取得、与恢复的数据（金额等）成对传入；本类不再读取当前会话
     * 来取得身份。
     */
    initialOwner: FormOwner,
) {
    /** 本表单所属的账号与登录世代（共性第 52、65 条）：构造时由发起页传入，只在会话换代通知（[onSessionChanged]）里随新表单一起换成发起页传入的新身份。 */
    private var formOwner = initialOwner

    /** 当前表单的身份（按钮回调未显式传入渲染快照的身份时用它）。 */
    val owner: FormOwner get() = formOwner

    private val mutable = MutableStateFlow(FlowState(owner = formOwner))
    val state: StateFlow<FlowState> = mutable.asStateFlow()
    private val s: FlowState get() = mutable.value

    val stepUp = StepUpController(deps, scope, alive)

    /** 当前（或下次提交沿用的）键；null = 下次用新键。 */
    private var reuseKey: String? = null

    /** 最近一次提交的请求体与摘要（二次验证后沿用原键、原体重放）。 */
    private var lastBody: String? = null
    private var lastSummary: String = ""

    /** 最近一次点击【提交】/【保存】时固定的账号与会话世代（二次验证后的原键重放沿用它，共性第 39 条）。 */
    private var lastClick: Click? = null
    private var serial = 0L

    val blocked: StateFlow<Boolean> get() = gate.blocked

    /**
     * 页面可见性与可见世代（APP-09 共性第 37 条）：页面被覆盖（ON_PAUSE）时世代加 1。异步结果要打开二次验证面板、风控弹窗或引导导航时，页面必须仍可见
     * 且世代与发出时相同；否则只结算状态、保留原键，不打开浮层、不导航。同一个流程同时只有一个提交 / 确认 / 放弃在途，发出时的世代记在 [sentViewGen]。
     */
    private var visible = true
    private var viewGen = 0L
    private var sentViewGen = 0L

    fun onVisibility(shown: Boolean) {
        if (shown != visible) viewGen += 1
        visible = shown
    }

    private val onScreen: Boolean get() = alive() && visible && viewGen == sentViewGen

    /** 页面此刻可见、且可见世代与发出时相同（原导航许可仍有效）。 */
    val showing: Boolean get() = alive() && visible && viewGen == sentViewGen

    /** 页面此刻在栈顶且可见（不论发出之后是否被覆盖过；延后的成功结果在这时安全消费，共性第 41 条）。 */
    val visibleNow: Boolean get() = alive() && visible

    init {
        // 未决记录结算后（别的页面、已出栈页面的迟到响应、本页）按本机记录重新同步当前页（APP-09 共性第 1 条）
        scope.launch {
            deps.pending.settled.collect { (account, op) ->
                // 只收本表单身份的结算（共性第 65 条）：与固定身份比较，不按当前会话的账号接收
                if (op == operationId && alive() && account == formOwner.account) refreshPending()
            }
        }
    }

    // ---------------------------------------------------------------- 提交

    /**
     * 点击【提交】/【保存】时固定的账号与会话世代（APP-09 共性第 39 条，Android R2-01）：读取 step-up token、网络层读取凭据与登记未决记录、实际发送前
     * 都核对仍是它们；会话换代后尚未发出的旧请求不发、不登记到新账号（已发出的写请求不因离页取消）。
     */
    private class Click(
        val account: String,
        val epoch: Long,
    )

    /**
     * 用户点【提交】（【保存】）。[body] 原始请求体（UTF-8 原文，签名与重放都用这份），[summary] 未决记录的展示摘要，[owner] 渲染这张表单时的
     * 账号与登录世代（共性第 52 条）。
     */
    @Suppress("ReturnCount") // 不能提交、表单身份、限流、没有会话账号几个出口
    fun submit(
        body: String,
        summary: String,
        owner: FormOwner = formOwner,
    ) {
        if (!alive() || !s.canSubmit) return
        if (gate.active) {
            update { it.copy(error = notice("error.42901")) }
            return
        }
        // 一份身份快照（FU-AND-0717 第 3 轮整体重理：不先读账号、再读世代与账号）。会话账号读不出（身份暂时未知、没有会话）：不能确定这次提交属于谁，
        // 不发（只做判断，不从当前会话取账号）
        val now = deps.env.identitySnapshot()
        if (!now.known || now.account == null) {
            update { it.copy(error = notice(WalletErrors.NETWORK_KEY)) }
            return
        }
        // 表单所属身份先于生成键、读取 token 核对（共性第 52 条，Android R3-01）：会话已换而清空通知还没执行时，旧表单不发、不登记到新账号；
        // 之后的键、token、未决记录与网络层 expectedAccount 都用这份固定身份（共性第 65 条：账号取自表单身份，不取自当前会话）
        if (owner != formOwner || !now.isOwnedBy(owner.account, owner.epoch)) return
        val account = owner.account ?: return
        val click = Click(account, owner.epoch)
        val key = reuseKey ?: deps.newKey()
        reuseKey = key
        lastBody = body
        lastSummary = summary
        lastClick = click
        update { it.copy(phase = FlowState.SUBMITTING, error = null, fieldErrors = emptyList(), fieldErrorKey = null, guideRoute = null) }
        send(click, key, body, summary, deps.tokens.take(action, account, deps.clock(), click.epoch, deps.env.sessionScope()), false)
    }

    /** 渲染快照的身份就是本流程当前的表单身份，且仍是当前会话（共性第 52 条）。 */
    private fun owns(owner: FormOwner): Boolean = owner == formOwner && owner.current(deps.env)

    /** 写请求结局的共享结算是否仍属于点击时的会话：本进程登录世代没变、一份快照没有确认换代（未知照常结算，F-X-22）。 */
    private fun settleable(
        account: String,
        epoch: Long,
    ): Boolean =
        deps.env.sessionEpoch() == epoch &&
            deps.env.identitySnapshot().verdictFor(account, epoch, null, trackPartition = false) !=
            com.couli.core.network.IdentityVerdict.CHANGED

    /** 点击时的会话与账号仍是当前的（同一账号重新登录也算换代）。 */
    private fun owned(
        account: String,
        epoch: Long,
    ): Boolean = deps.env.ownedBy(account, epoch)

    /**
     * [replayed]：这次是本次用户提交在二次验证之后的原键重放（10003 重放预算已用，APP-09 共性第 16 条）。请求在会话级作用域执行（共性第 40 条），
     * 带点击时的账号与会话归属（共性第 39 条）。
     */
    private fun send(
        click: Click,
        key: String,
        body: String,
        summary: String,
        token: String?,
        replayed: Boolean,
    ) {
        // 发送前核对：会话已换（二次验证期间、排队期间）的不发，键不用了（从未发出或只收到过 10003，下次新键）
        if (!owned(click.account, click.epoch)) {
            reuseKey = null
            if (alive()) update { it.copy(phase = FlowState.EDITING) }
            return
        }
        sentViewGen = viewGen
        // 提交离开页面不取消（未决记录兜底）；会话换代即作废（尚未写出的不发）；禁用期是独立的发送前闸门（APP-08 共性第 27 条），不进归属；
        // 被它拦下时记下原因（共性第 62 条），回调按原因结算
        val probe = gate.probe()
        val owner = CallOwnership(deps.api.epochs(), sendAllowed = probe::allowed) { deps.env.sessionEpoch() == click.epoch }
        deps.service.launch {
            try {
                val call =
                    ApiCall(
                        operationId,
                        body = body,
                        idempotencyKey = key,
                        stepUpToken = token,
                        summary = summary,
                        ownership = owner,
                        expectedAccount = click.account,
                    )
                val result = deps.api.send(call)
                val outcome =
                    if (result is ApiResult.LocalReject || result is ApiResult.SessionEnded) {
                        null
                    } else {
                        SensitiveOutcome.of(outcomeOp, SensitiveOutcome.SUBMIT, keyEverUnknown = false, input(result))
                    }
                settleShared(click.account, click.epoch, result, outcome, countToken = true)
                // 页面已出栈或会话已换代：不改页面（结论已由网络层落到未决记录，共享结算已做）
                if (!alive() || deps.env.sessionEpoch() != click.epoch) return@launch
                onSubmitResult(result, outcome, replayed, probe)
            } finally {
                // 结算通知放在本页处理完之后（共性第 1 条）：Main.immediate 下通知会同步执行，先通知会抢在本页之前改掉待确认版本
                deps.pending.notify(click.account, operationId)
            }
        }
    }

    /**
     * 结果的共享结算（APP-09 共性第 27、40 条）：只按点击时的会话归属（不看页面是否还在）——禁用期、step-up token 的成功与 10003 失效、强更、
     * 发起方的成功标记。之后才由页面按自己是否有效更新界面。
     */
    private fun settleShared(
        account: String,
        epoch: Long,
        result: ApiResult,
        outcome: Outcome?,
        countToken: Boolean,
    ) {
        // F-X-22（窄修三核对）：已发出的写请求的结局只在「确认换代」时不结算；身份暂时读不出（同一登录世代）照常结算——成功不能因一次读取失败
        // 丢掉共享结算（钱包失效通知、token 计数），页面结论另由 [onSubmitResult] 按本进程登录世代落地，不显示成可重试错误
        if (!settleable(account, epoch)) return
        if (countToken && result is ApiResult.Success) deps.tokens.onSuccess(action)
        outcome ?: return
        outcome.disableMillis?.let(gate::start)
        when (outcome.actionRaw) {
            SensitiveOutcome.STEP_UP -> deps.tokens.on10003(action)

            SensitiveOutcome.FORCE_UPDATE -> deps.env.forceUpdate()

            // 已固定的点击时账号与世代原样交出（共性第 61 条）：其间新会话的令牌可能已在 IO 线程保存，出口不得再读当前会话
            SensitiveOutcome.DONE -> host.onSharedSuccess(account, epoch)

            SensitiveOutcome.ALREADY_DONE -> if (outcome.textKey == SensitiveOutcome.ALREADY_DONE_KEY) host.onSharedSuccess(account, epoch)

            else -> Unit
        }
    }

    @Suppress("ReturnCount") // 本地拒绝的几种出口
    private fun onSubmitResult(
        result: ApiResult,
        outcome: Outcome?,
        replayed: Boolean,
        probe: SendProbe,
    ) {
        if (outcome == null) {
            localRejected(result, probe)
            return
        }
        apply(outcome, result, SensitiveOutcome.SUBMIT, replayed, null)
    }

    /** 没有发出（本地拒绝）：同一操作已有未决键 → 按记录进待确认；限流闸门 → 限流提示；其余提示网络错误，键不用了。 */
    private fun localRejected(
        result: ApiResult,
        probe: SendProbe,
    ) {
        reuseKey = null
        val reason = (result as? ApiResult.LocalReject)?.reason
        val key =
            when {
                // 本请求被发送前限流拒绝（共性第 62 条）：按记下的原因，不看回调时刻的禁用期
                probe.gated(result) -> "error.42901"

                reason == LocalRejectReason.PENDING_KEY_UNRESOLVED -> null

                else -> WalletErrors.NETWORK_KEY
            }
        update { it.copy(phase = FlowState.EDITING, error = key?.let(::notice)) }
        refreshPending()
    }

    // ---------------------------------------------------------------- 待确认：确认 / 放弃

    /**
     * 点击【确认上一笔】/【确定放弃】时的那条记录（APP-09 共性第 14、16 条）：账号、会话世代与原键在点击处固定，之后的读取、占用与发送都按它核对；
     * [stepUpReplayed] 是这次用户确认的 10003 重放预算（至多一次）。
     */
    private class Intent(
        val account: String,
        val epoch: Long,
        val key: String,
    ) {
        var stepUpReplayed = false
    }

    /** 点击时的归属：会话世代仍是点击时的（离开页面不取消，未决记录兜底）；共享禁用期是发送前闸门（首次发送与管线续发前都核对，共性第 15 条）。 */
    private fun target(intent: Intent): PendingTarget =
        PendingTarget(
            intent.account,
            intent.key,
            CallOwnership(deps.api.epochs(), sendAllowed = { !gate.active }) { deps.env.sessionEpoch() == intent.epoch },
        )

    /**
     * 点击处的那条记录（账号、会话世代、原键都取自本页渲染的待确认卡，[owner] 是渲染时的表单身份，共性第 52 条）；不是当前会话的、不是这张卡的键
     * 都不受理。
     */
    private fun intentFor(
        key: String,
        owner: FormOwner,
    ): Intent? {
        val pending = s.pending?.takeIf { it.key == key } ?: return null
        if (!alive() || pending.inFlight || !owns(owner)) return null
        val account = owner.account ?: return null
        return Intent(account, owner.epoch, pending.key)
    }

    /** 【确认上一笔】：原键、原请求体重发（二次验证照常）。 */
    fun confirm(
        key: String,
        owner: FormOwner = formOwner,
    ) {
        val intent = intentFor(key, owner) ?: return
        clearGuide()
        if (gate.active) {
            update { it.copy(pendingNotice = notice(SensitiveOutcome.RETRY_LATER_KEY)) }
            return
        }
        runConfirm(intent, deps.tokens.take(action, intent.account, deps.clock(), intent.epoch, deps.env.sessionScope()))
    }

    private fun runConfirm(
        intent: Intent,
        token: String?,
    ) {
        // 二次验证之后续发：页面、会话与账号仍是点击时的才发（共性第 14 条）
        if (!alive() || !deps.env.ownedBy(intent.account, intent.epoch)) return
        val version = s.pendingVersion
        sentViewGen = viewGen
        markInFlight(true)
        // 会话级作用域执行（共性第 40 条）：离页不取消已发出的确认，共享结算照常
        deps.service.launch {
            try {
                val result = deps.api.confirmPending(operationId, token, target(intent))
                val outcome =
                    if (result is ApiResult.LocalReject || result is ApiResult.SessionEnded) {
                        null
                    } else {
                        SensitiveOutcome.of(outcomeOp, SensitiveOutcome.CONFIRM, keyEverUnknown = true, input(result))
                    }
                settleShared(intent.account, intent.epoch, result, outcome, countToken = true)
                if (!alive() || deps.env.sessionEpoch() != intent.epoch) return@launch
                if (s.pendingVersion != version) {
                    // 键已经结束（别的在途请求先回来了）：这次的响应只用于核对，不再改变页面
                    refreshPending()
                    return@launch
                }
                markInFlight(false)
                if (outcome == null) {
                    update { it.copy(pendingNotice = notice(SensitiveOutcome.RETRY_LATER_KEY)) }
                    refreshPending()
                    return@launch
                }
                apply(outcome, result, SensitiveOutcome.CONFIRM, intent.stepUpReplayed, intent)
            } finally {
                // 结算通知放在本页处理完之后（共性第 1 条）：Main.immediate 下通知会同步执行，先通知会抢在本页之前改掉待确认版本
                deps.pending.notify(intent.account, operationId)
            }
        }
    }

    /** 【放弃上一笔】：先弹二次确认（绑定点击时的键）。 */
    fun askAbandon(
        key: String,
        owner: FormOwner = formOwner,
    ) {
        val pending = s.pending?.takeIf { it.key == key } ?: return
        if (pending.inFlight || !owns(owner)) return
        if (gate.active) {
            update { it.copy(pendingNotice = notice(SensitiveOutcome.RETRY_LATER_KEY)) }
            return
        }
        update { it.copy(abandonConfirm = true) }
    }

    fun cancelAbandon() = update { it.copy(abandonConfirm = false) }

    /** 二次确认里点【确定放弃】：调作废接口。 */
    fun abandon(
        key: String,
        owner: FormOwner = formOwner,
    ) {
        update { it.copy(abandonConfirm = false) }
        val intent = intentFor(key, owner) ?: return
        clearGuide()
        if (gate.active) {
            update { it.copy(pendingNotice = notice(SensitiveOutcome.RETRY_LATER_KEY)) }
            return
        }
        val version = s.pendingVersion
        sentViewGen = viewGen
        markInFlight(true)
        // 会话级作用域执行（共性第 40 条）：离页不取消已发出的作废，共享结算照常
        deps.service.launch {
            try {
                val result = deps.api.abandonPending(operationId, target(intent))
                val outcome =
                    if (result is ApiResult.LocalReject || result is ApiResult.SessionEnded) {
                        null
                    } else {
                        SensitiveOutcome.of(outcomeOp, SensitiveOutcome.ABANDON, keyEverUnknown = true, abandonInput(result))
                    }
                settleShared(intent.account, intent.epoch, result, outcome, countToken = false)
                if (!alive() || deps.env.sessionEpoch() != intent.epoch) return@launch
                if (s.pendingVersion != version) {
                    refreshPending()
                    return@launch
                }
                markInFlight(false)
                if (outcome == null) {
                    // 本地阻断（禁用期、会话已换、记录已换键）：原键保持待确认
                    update { it.copy(pendingNotice = notice(SensitiveOutcome.RETRY_LATER_KEY)) }
                    refreshPending()
                    return@launch
                }
                apply(outcome, result, SensitiveOutcome.ABANDON, replayed = true, intent = null)
            } finally {
                // 结算通知放在本页处理完之后（共性第 1 条）：Main.immediate 下通知会同步执行，先通知会抢在本页之前改掉待确认版本
                deps.pending.notify(intent.account, operationId)
            }
        }
    }

    private fun abandonInput(result: ApiResult): OutcomeInput =
        when (result) {
            is ApiResult.Abandoned -> {
                OutcomeInput(OutcomeInput.ABANDON_RESULT, abandonOutcome = SensitiveOutcome.ABANDONED)
            }

            is ApiResult.Success -> {
                OutcomeInput(OutcomeInput.ABANDON_RESULT, abandonOutcome = COMPLETED, originalCode = 0)
            }

            // 作废接口自己不回 3xxxx：拿到 3xxxx 就是 completed 的原结果（ApiClient 按原操作给出）
            is ApiResult.Failure -> {
                if (ErrorActionMapper.isBusinessCode(result.code)) {
                    OutcomeInput(OutcomeInput.ABANDON_RESULT, abandonOutcome = COMPLETED, originalCode = result.code, data = result.data)
                } else {
                    input(result)
                }
            }

            else -> {
                input(result)
            }
        }

    // ---------------------------------------------------------------- 结局

    @Suppress("CyclomaticComplexMethod", "LongMethod") // 按结局逐项
    private fun apply(
        outcome: Outcome,
        result: ApiResult,
        phase: String,
        replayed: Boolean,
        intent: Intent?,
    ) {
        if (outcome.nextSubmitNewKey) reuseKey = null
        // 禁用期、token 失效、强更已在 [settleShared] 按会话归属结算
        val traceId = (result as? ApiResult.Failure)?.traceId
        val message = outcome.textKey?.let { Notice(it, ++serial, traceId, outcome.showTrace6) }
        when (outcome.actionRaw) {
            SensitiveOutcome.DONE -> {
                reuseKey = null
                update { it.copy(phase = FlowState.EDITING, error = null, pendingNotice = null) }
                endPending()
                host.onSucceeded((result as? ApiResult.Success)?.data as? JsonObject, alreadyDone = false)
            }

            SensitiveOutcome.ALREADY_DONE -> {
                reuseKey = null
                endPending()
                if (outcome.textKey == SensitiveOutcome.ALREADY_DONE_KEY) {
                    update { it.copy(phase = FlowState.EDITING, toast = message?.copy(assertive = false)) }
                    host.onSucceeded((result as? ApiResult.Success)?.data as? JsonObject, alreadyDone = true)
                } else {
                    update { it.copy(phase = FlowState.EDITING, error = message, toast = notice(SensitiveOutcome.ALREADY_DONE_KEY)) }
                    host.onSettled()
                }
            }

            SensitiveOutcome.STEP_UP -> {
                when {
                    replayed -> stepUpExhausted(phase, message)

                    // 页面被覆盖：不打开面板，保留原键（首次提交下次点击沿用；确认阶段保持待确认）
                    !onScreen -> stepUpDeferred(phase, message)

                    else -> openStepUp(phase, intent)
                }
            }

            SensitiveOutcome.ABANDONED -> {
                reuseKey = null
                endPending()
                update { it.copy(phase = FlowState.EDITING, toast = message?.copy(assertive = false), error = null) }
                host.onSettled()
            }

            SensitiveOutcome.SHOW_ERROR_KEY_ENDS, SensitiveOutcome.RETRY_AFTER, SensitiveOutcome.ERROR_TRACE6,
            SensitiveOutcome.BUSY_RETRY, SensitiveOutcome.AUTO_RESEND,
            -> {
                if (phase != SensitiveOutcome.SUBMIT) endPending()
                update { it.copy(phase = FlowState.EDITING, error = message ?: notice(WalletErrors.NETWORK_KEY)) }
                (result as? ApiResult.Failure)?.code?.let(host::onBusinessError)
                if (outcome.keyEnds) host.onSettled()
                refreshPending()
            }

            SensitiveOutcome.FIELD_ERROR -> {
                val event = ++serial
                update {
                    it.copy(
                        phase = FlowState.EDITING,
                        fieldErrors = outcome.fields,
                        fieldErrorKey = outcome.textKey,
                        fieldErrorId = event,
                        error = null,
                    )
                }
                refreshPending()
            }

            SensitiveOutcome.RISK_BLOCKED -> {
                val title = outcome.riskMsgCode?.let { "risk_msg.$it" }?.takeIf(deps.texts::has) ?: "error.44001"
                // 页面被覆盖时不弹窗，只把结论留在页内错误条（共性第 37 条）
                if (onScreen) {
                    update { it.copy(phase = FlowState.EDITING, riskDialog = RiskDialog(title, ++serial)) }
                } else {
                    update { it.copy(phase = FlowState.EDITING, error = notice(title)) }
                }
                refreshPending()
            }

            SensitiveOutcome.GUIDE -> {
                update {
                    if (phase == SensitiveOutcome.SUBMIT) {
                        it.copy(phase = FlowState.EDITING, error = message)
                    } else {
                        it.copy(pendingNotice = message)
                    }
                }
                refreshPending()
                // 引导导航只在页面仍可见、可见世代未变时执行（共性第 37 条）；被拦下时保留目标、给手动入口（共性第 51 条）
                val route = outcome.route
                if (onScreen) {
                    route?.let(host::onGuide)
                } else if (route != null) {
                    // 记下引导所属的表单身份（共性第 65 条）：不读取结果到达时的当前会话
                    guideOwner = formOwner
                    update { it.copy(guideRoute = route) }
                }
            }

            SensitiveOutcome.FORCE_UPDATE -> {
                update { if (phase == SensitiveOutcome.SUBMIT) it.copy(phase = FlowState.EDITING) else it }
                refreshPending()
            }

            else -> {
                // pending_confirm / show_error_keep_pending / abandon_busy：进入或保持待确认（键不结束、不自动重发）
                update {
                    it.copy(
                        phase = FlowState.PENDING,
                        pendingNotice = message?.takeIf { outcome.textKey != SensitiveOutcome.TITLE_KEY },
                    )
                }
                refreshPending()
            }
        }
    }

    /**
     * 10003 的原键重放已用过一次又收到 10003（APP-09 共性第 16 条，03 §4.2）：不再弹面板、不再续发，提示 error.10003。首次提交的键从未结果未知，
     * 结束不用（下次点击用新键）；确认阶段的键曾结果未知，保持待确认。
     */
    private fun stepUpExhausted(
        phase: String,
        message: Notice?,
    ) {
        val text = message ?: notice("error.10003")
        if (phase == SensitiveOutcome.SUBMIT) {
            reuseKey = null
            update { it.copy(phase = FlowState.EDITING, error = text) }
        } else {
            update { it.copy(pendingNotice = text) }
        }
        refreshPending()
    }

    /** 页面被覆盖时收到 10003：只结算状态并保留原键，不弹面板（用户回到页面再点）。 */
    private fun stepUpDeferred(
        phase: String,
        message: Notice?,
    ) {
        val text = message ?: notice("error.10003")
        if (phase == SensitiveOutcome.SUBMIT) {
            update { it.copy(phase = FlowState.EDITING, error = text) }
        } else {
            update { it.copy(pendingNotice = text) }
        }
        refreshPending()
    }

    /**
     * 确认流程打开的二次验证属于哪个未决键（APP-09 共性第 49 条）：别处（其他页面、已出栈页面的迟到响应）结算了这个键，或记录换成了另一个键时，
     * 按未决记录同步收起面板，拿到的 token 也不再续发。
     */
    private var stepUpIntent: Intent? = null

    /**
     * 二次验证面板沿用打开它的提交 / 确认身份（共性第 60 条，Android RC-02）：确认取点击时的记录归属，首次提交取点击时固定的账号与世代。取不到
     * （不应发生：会话换代已清掉点击记录）时首次提交回到编辑态、键不用了，不开面板。
     */
    private fun stepUpOwner(
        phase: String,
        intent: Intent?,
    ): FormOwner? {
        val owner =
            intent?.let { FormOwner(it.account, it.epoch) }
                ?: lastClick?.takeIf { phase == SensitiveOutcome.SUBMIT }?.let { FormOwner(it.account, it.epoch) }
        if (owner == null && phase == SensitiveOutcome.SUBMIT) {
            reuseKey = null
            update { it.copy(phase = FlowState.EDITING) }
        }
        return owner
    }

    private fun openStepUp(
        phase: String,
        intent: Intent?,
    ) {
        val panelOwner = stepUpOwner(phase, intent) ?: return
        update { it.copy(phase = if (phase == SensitiveOutcome.SUBMIT) FlowState.SUBMITTING else it.phase) }
        stepUpIntent = intent
        stepUp.open(
            action,
            panelOwner,
            onToken = { token ->
                if (!alive()) return@open
                if (phase == SensitiveOutcome.SUBMIT) {
                    val key = reuseKey
                    val body = lastBody
                    val click = lastClick
                    if (key == null || body == null || click == null) {
                        update { it.copy(phase = FlowState.EDITING) }
                    } else {
                        // 原键重放沿用点击时的账号与会话世代（共性第 39 条）：发送前核对，换了会话的不发
                        send(click, key, body, lastSummary, token, replayed = true)
                    }
                } else if (intent != null) {
                    stepUpIntent = null
                    // 恢复前核对：本机未决记录仍是同一个键、未结束（共性第 49 条）
                    refreshPending()
                    if (s.pending?.key != intent.key) return@open
                    intent.stepUpReplayed = true
                    runConfirm(intent, token)
                }
            },
            onClosed = {
                stepUpIntent = null
                // 用户关闭面板：首次提交回到编辑态，本次键结束不用（从未结果未知，下次用新键）；待确认保持待确认
                if (phase == SensitiveOutcome.SUBMIT) {
                    reuseKey = null
                    update { it.copy(phase = FlowState.EDITING) }
                }
                refreshPending()
            },
        )
    }

    // ---------------------------------------------------------------- 被拦下的引导（共性第 51 条）

    private var guideOwner: FormOwner? = null

    /** 手动入口【去实名认证】等：页面此刻可见、会话仍是记下引导时的才打开；用过即清。 */
    fun openGuide() {
        val route = s.guideRoute ?: return
        if (!visibleNow) return
        update { it.copy(guideRoute = null) }
        if (guideOwner == formOwner && formOwner.current(deps.env)) host.onGuide(route)
    }

    private fun clearGuide() {
        if (s.guideRoute != null) update { it.copy(guideRoute = null) }
    }

    // ---------------------------------------------------------------- 恢复

    /**
     * 按当前会话账号的未决记录重建待确认状态（进入页面、冷启动、登录成功、回前台、键结束之后）。记录读不出 → 不能确认未决状态，【提交】置灰。
     * 本进程正在发送的首次提交（submitting）不被记录的 in_flight 打断。
     */
    fun refreshPending() {
        if (!alive()) return
        // 只把本表单身份的记录放进本表单（共性第 65 条）：当前会话已不是固定身份（令牌已换、清空通知还没执行）时不读，读到别的账号的记录也不放进来
        // 一份快照（FU-AND-0717 第 3 轮）：身份暂时未知、已换代都不读（不改已显示的待确认状态）；未知期取得的表单等接管（[adoptOwner]）之后再读
        val owner = formOwner
        if (owner.verdict(deps.env.identitySnapshot()) != com.couli.core.network.IdentityVerdict.SAME) return
        val record =
            try {
                deps.api.pendingFor(operationId)
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception,
            ) {
                update { it.copy(pendingUnreadable = true) }
                return
            }
        if (record != null && record.account != owner.account) return
        update { state -> reconcile(state, record?.let(::view)) }
        // 确认流程的二次验证所属的键已在别处结算或被替换：同步收起面板（共性第 49 条）
        val intent = stepUpIntent
        if (intent != null && s.pending?.key != intent.key) {
            stepUpIntent = null
            stepUp.dismiss()
        }
    }

    /** 按记录重建：没有记录 → 退出待确认；本次首次提交还在发送 → 不打断；有记录 → 待确认（换了键版本加 1，本进程在途的保持在途）。 */
    private fun reconcile(
        state: FlowState,
        view: PendingView?,
    ): FlowState {
        if (view == null) {
            val cleared =
                if (state.pending != null) {
                    state.copy(pending = null, pendingVersion = state.pendingVersion + 1, pendingNotice = null, pendingUnreadable = false)
                } else {
                    state.copy(pendingUnreadable = false)
                }
            return if (cleared.phase == FlowState.PENDING) cleared.copy(phase = FlowState.EDITING) else cleared
        }
        if (state.phase == FlowState.SUBMITTING && view.key == reuseKey && view.inFlight) return state.copy(pendingUnreadable = false)
        val fresh = state.pending?.key != view.key
        val busy = view.inFlight || (!fresh && localBusy)
        return state.copy(
            phase = FlowState.PENDING,
            pending = view.copy(inFlight = busy),
            pendingVersion = if (fresh) state.pendingVersion + 1 else state.pendingVersion,
            pendingUnreadable = false,
        )
    }

    private fun view(record: PendingKeyRecord): PendingView =
        PendingView(record.key, record.summary, record.createdAtMillis, inFlight = record.stateRaw == PendingKeyStore.IN_FLIGHT)

    /** 本页自己正在确认或作废（别的页面、已出栈页面的在途只看记录本身的状态）。 */
    private var localBusy = false

    private fun markInFlight(busy: Boolean) {
        localBusy = busy
        update { state ->
            val pending = state.pending ?: return@update state
            state.copy(
                pending = pending.copy(inFlight = busy),
                pendingNotice = if (busy) notice(BUSY_KEY).copy(assertive = false) else state.pendingNotice,
            )
        }
    }

    private fun endPending() {
        update { it.copy(pending = null, pendingVersion = it.pendingVersion + 1, pendingNotice = null, abandonConfirm = false) }
    }

    // ---------------------------------------------------------------- 会话、离页、提示

    /**
     * 会话换代（登录、退出、换账号、会话失效；App 在会话通知里同步调用）：清掉页面上的待确认与提示、收起二次验证面板（内存里的 token 由 App 清空），
     * 下次提交用新键；按新会话的账号重读未决记录（旧账号的键不发、不展示，原账号再登录时恢复）。
     */
    fun onSessionChanged(owner: FormOwner) {
        resetFor(owner)
        if (deps.env.loggedIn()) {
            runCatching { deps.api.restorePending() }
            refreshPending()
        }
    }

    /**
     * 身份暂时未知期间取得的表单第一次确认（FU-AND-0717 第 3 轮整体重理，R2-04）：按发起页交来的那份快照换成已确认身份——不清已填内容、不动禁用期与
     * 在途、不重放提交；只按新身份重读本机未决记录。返回是否换了。
     */
    fun adoptOwner(owner: FormOwner): Boolean {
        if (formOwner.account != null || owner.account == null || owner.epoch != formOwner.epoch) return false
        formOwner = owner
        update { it.copy(owner = owner) }
        if (deps.env.loggedIn()) {
            runCatching { deps.api.restorePending() }
            refreshPending()
        }
        return true
    }

    private fun resetFor(owner: FormOwner) {
        formOwner = owner
        reuseKey = null
        lastBody = null
        lastSummary = ""
        lastClick = null
        stepUpIntent = null
        guideOwner = null
        stepUp.dismiss()
        localBusy = false
        mutable.value = FlowState(pendingVersion = s.pendingVersion + 1, owner = formOwner)
    }

    fun leave() {
        stepUp.dismiss()
    }

    fun consumeToast(id: Long) {
        if (s.toast?.id == id) update { it.copy(toast = null) }
    }

    fun dismissRisk() = update { it.copy(riskDialog = null) }

    fun clearError() = update { it.copy(error = null) }

    fun clearFieldErrors() = update { it.copy(fieldErrors = emptyList(), fieldErrorKey = null) }

    private fun notice(key: String) = Notice(key, ++serial)

    private fun input(result: ApiResult): OutcomeInput =
        when (result) {
            is ApiResult.Success -> {
                OutcomeInput(OutcomeInput.OK)
            }

            is ApiResult.Failure -> {
                OutcomeInput(
                    OutcomeInput.ERROR,
                    code = result.code,
                    data = result.data,
                    retryAfterMillis = if (result.code == ResultSignals.RATE_LIMITED) ResultSignals.disableMillis(result) else null,
                    hasTrace = result.traceId != null,
                    forced = ResultSignals.forcesUpdate(result),
                )
            }

            else -> {
                OutcomeInput(OutcomeInput.TRANSPORT)
            }
        }

    private inline fun update(change: (FlowState) -> FlowState) {
        mutable.value = change(mutable.value)
    }

    companion object {
        const val COMPLETED = "completed"
        const val BUSY_KEY = "error.40901"
    }
}
