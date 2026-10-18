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
    /** 本页写操作从发起到结果采用完毕的独立锁，不由未决记录是否存在决定。 */
    val operationInFlight: Boolean = false,
    /**
     * 本页自己写过、结果仍未知的键，其未决记录在本页没有采用明确终态时消失了（别处确认 / 放弃、记录被删；FU-AND-0721 review-r1 S0）：
     * 原活页面保守锁住提交，与重建后按保存标记锁住同口径。会话换代会清掉本字段，但提现控制器换代时保留提交锁（successPending），活页仍锁住；
     * 离开后新开的表单才解除（review-r2 第 5 条更正）。
     */
    val unresolvedLock: Boolean = false,
) {
    val canSubmit: Boolean get() = phase == EDITING && pending == null && !pendingUnreadable && !operationInFlight && !unresolvedLock

    companion object {
        const val EDITING = "editing"
        const val SUBMITTING = "submitting"
        const val PENDING = "pending"
    }
}

/**
 * 本表单持久化的键关联（FU-AND-0721 不变式的守卫数据来源，提现：发起页从 SavedStateHandle 同步读出）：[succeeded] 成功锁；[inFlight] 本页交给网络层、
 * 尚未采用「没有业务结果 / 明确不出款」结局的第一个键（[inFlightKey] 为该键，旧版本的 Boolean 标记没有键）；[unknownKey] 本页写过而结果未知、或本页观察到
 * 其待确认 / 忙碌卡的键，只在本页采用其终态时清掉。没有持久关联的宿主（收款账号）给 [NONE]。
 */
data class PersistedKeys(
    val succeeded: Boolean = false,
    val inFlight: Boolean = false,
    val inFlightKey: String? = null,
    val unknownKey: String? = null,
) {
    /** 本表单关联、而本机记录 [recordKey] 不是它的键（没有记录为 null）：该键在本页未采用终态时已消失，视为未解决。 */
    fun unresolvedAgainst(recordKey: String?): String? =
        when {
            unknownKey != null && unknownKey != recordKey -> unknownKey
            inFlight && recordKey == null -> inFlightKey.orEmpty()
            else -> null
        }

    companion object {
        val NONE = PersistedKeys()
    }
}

/** 发起页接线：成功、引导补办、键结束后的刷新。 */
@Suppress("TooManyFunctions") // 每个结局 / 标记出口一个回调，均有空实现，旧宿主无需实现
interface FlowHost {
    /** 本表单此刻持久化的键关联（守卫只读它与本机记录判定，不读内存态）；旧宿主 / 收款账号没有持久关联。 */
    fun persistedKeys(): PersistedKeys = PersistedKeys.NONE

    /** 写请求发出之前的同步出口，供表单保存重建后的保守提交锁；旧页面接线无需实现。 */
    fun onWriteStarted() = Unit

    /** 带本次固定的幂等键；保留无参出口，每次写发起仍通知旧宿主。 */
    fun onWriteStarted(key: String) = onWriteStarted()

    /** 本表单明确不出款的结局已采用，同步释放重建用的在途标记；操作锁仍持有到采用完毕。 */
    fun onWriteReleased() = Unit

    /** 只释放本次写所用的键，不从此刻的未决卡片反推。 */
    fun onWriteReleased(key: String) = onWriteReleased()

    /** 本页结果未知的 [key] 的未决记录消失、本页未采用其终态：页面保守锁住提交（[FlowState.unresolvedLock]），在途标记保留。 */
    fun onWriteUnresolved(key: String) = Unit

    /**
     * 本页结果未知的键变了（[key] = null：本页已采用它的终态或会话换代）。发起页同步存进本表单的可恢复状态（SavedStateHandle），重建后交回
     * [SensitiveFlow] 的 initialUnknown（FU-AND-0721 review-r3 S0：观察到即保存，不等记录消失）；旧页面接线无需实现。
     */
    fun onUnknownChanged(key: String?) = Unit

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
    /** 同一表单重建时恢复的「本页结果未知的键」（[FlowHost.onUnknownChanged] 保存的那个）：重读记录时它已消失就保守锁住。 */
    initialUnknown: String? = null,
) {
    /** 本表单所属的账号与登录世代（共性第 52、65 条）：构造时由发起页传入，只在会话换代通知（[onSessionChanged]）里随新表单一起换成发起页传入的新身份。 */
    private var formOwner = initialOwner

    /** 当前表单的身份（按钮回调未显式传入渲染快照的身份时用它）。 */
    val owner: FormOwner get() = formOwner

    private val mutable = MutableStateFlow(FlowState(owner = formOwner))
    val state: StateFlow<FlowState> = mutable.asStateFlow()
    private val s: FlowState get() = mutable.value

    val stepUp = StepUpController(deps, scope, alive)

    /** 当前（或下次提交沿用的）键；null = 下次用新键。沿用时只发它首次固定的原请求体（[lastBody]），不与当前表单重组。 */
    private var reuseKey: String? = null

    /**
     * 本页结果未知的键（FU-AND-0721 review-r1 / r2 / r3 S0），只经 [trackUnknown] 改并同步交宿主持久化：本页写过而结果仍未知（进过待确认），或本页观察到
     * 它的待确认记录（渲染了待确认卡，含别页首次提交在途的忙碌卡（G17）；点击与否、点击途中提前返回都不影响）。内存里这份只用于沿用它时按「结果未知过」
     * 分类（确认口径）与换代豁免；锁与放行一律由守卫按宿主持久化的那份判定（[guard]、[lockIfUnresolvedVanished]）。本页采用了它的终态（键结束）才清掉。
     */
    private var ownUnknown: String? = initialUnknown

    /**
     * 会话换代时被替换的旧表单仍在途的那个键：旧表单这笔写收尾（迟到结局由 [previousFormSucceeded] 处理，F-AND-43 ① 不锁新表单）之前，它的记录
     * 不算新表单观察到；收尾后清掉，记录仍在（仍结果未知）就照常按观察登记。
     */
    private var replacedWriteKey: String? = null

    /** 沿用的键只收到过页面被覆盖时的 10003（没有业务结果）：用户改了表单再提交时结束它、换新键（G04②）。 */
    private var reuseEndable = false

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
    private var everVisible = false
    private var sentBeforeFirstVisible = false

    private class DeferredStepUp(
        val form: FormOwner,
        val phase: String,
        val intent: Intent?,
    )

    private var deferredStepUp: DeferredStepUp? = null

    fun onVisibility(shown: Boolean) {
        if (shown != visible) viewGen += 1
        visible = shown
        if (shown) {
            everVisible = true
            // 入场中发出的请求从首次可见起按正常可见请求处理；之后被覆盖，回来不补弹。
            if (sentBeforeFirstVisible) {
                sentViewGen = viewGen
                sentBeforeFirstVisible = false
            }
        }
        showDeferredStepUp()
    }

    /** 只补入场时从未可见的验证面板；曾可见后被覆盖的请求仍等用户主动点击。 */
    private fun showDeferredStepUp() {
        val deferred = deferredStepUp ?: return
        if (!visibleNow || localBusy) return
        deferredStepUp = null
        if (deferred.form !== formOwner || !formOwner.current(deps.env)) return
        if (deferred.intent != null && s.pending?.key != deferred.intent.key) return
        openStepUp(deferred.phase, deferred.intent)
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
        val form: FormOwner,
    )

    /**
     * 用户点【提交】（【保存】）。[body] 原始请求体（UTF-8 原文，签名与重放都用这份），[summary] 未决记录的展示摘要，[owner] 渲染这张表单时的
     * 账号与登录世代（共性第 52 条）。
     */
    @Suppress("ReturnCount") // 守卫不放行、限流两个出口
    fun submit(
        body: String,
        summary: String,
        owner: FormOwner = formOwner,
    ) {
        if (!alive()) return
        // FU-AND-0721 不变式：生成新键之前先过持久守卫（身份、本机记录、本表单保存的关联），不能判定的一律不放行；内存态只能再加拒绝
        val account = guard(owner) ?: return
        if (!s.canSubmit) return
        if (gate.active) {
            update { it.copy(error = notice("error.42901")) }
            return
        }
        val click = Click(account, owner.epoch, formOwner)
        val (key, sendBody, sendSummary) = nextRequest(body, summary)
        lastClick = click
        update { it.copy(phase = FlowState.SUBMITTING, error = null, fieldErrors = emptyList(), fieldErrorKey = null, guideRoute = null) }
        val token = deps.tokens.take(action, account, deps.clock(), click.epoch, deps.env.sessionScope())
        send(click, key, sendBody, sendSummary, token, false)
    }

    /**
     * 生成新键之前的持久守卫（FU-AND-0721 不变式：本地持久化里有未解决的键时，任何入口都必须先解决它才能生成新键）。只读持久记录判定：一份身份快照、
     * 本机未决记录（现读）、本表单保存的键关联（[FlowHost.persistedKeys]）；不读内存里的卡片或标志。每一步不能判定都返回 null（不放行），并把结论同步到
     * 页面（待确认卡、保守锁、读不出）。返回放行时本次提交所属的账号。
     */
    @Suppress("ReturnCount") // 身份、记录、关联、成功锁逐项不放行
    private fun guard(owner: FormOwner): String? {
        // ① 身份：一份快照既用于表单归属核对（共性第 52、65 条），也决定能否按本表单身份读记录；暂时未知、已换代都不放行。
        //    会话账号读不出（身份暂时未知、没有会话）：提示网络错误，不发（只做判断，不从当前会话取账号）
        val now = deps.env.identitySnapshot()
        if (!now.known || now.account == null) {
            update { it.copy(error = notice(WalletErrors.NETWORK_KEY)) }
            return null
        }
        if (owner != formOwner || !now.isOwnedBy(owner.account, owner.epoch)) return null
        val account = owner.account ?: return null
        // ② 现读本机记录并同步页面：读不出、身份此刻不是 SAME 都不放行
        val synced = sync(now) ?: return null
        // ③ 本操作仍有未决记录（本页或别页的键）：先经卡片确认 / 放弃、或等在途结束
        if (synced.record != null) return null
        // ④ 没有记录，而本表单保存的关联键未被本页采用终态：已在 [sync] 里锁住
        val keys = host.persistedKeys()
        if (keys.unresolvedAgainst(null) != null || keys.succeeded) return null
        return account
    }

    /**
     * 这次提交用的键、请求体与摘要。原键原体（BR-ID-10）：沿用的键只发它首次固定的请求体与摘要，不与当前表单重组（review-r1 S0 根因二）；
     * 只收到过覆盖期 10003 的原键（没有业务结果）而表单已改：结束它、释放在途标记，再用新键提交新表单（G04②）。
     */
    private fun nextRequest(
        body: String,
        summary: String,
    ): Triple<String, String, String> {
        val reused = reuseKey
        if (reused != null && reuseEndable && body != lastBody) {
            reuseKey = null
            host.onWriteReleased(reused)
        }
        val key = reuseKey ?: deps.newKey()
        val sendBody = if (key == reused) lastBody ?: body else body
        val sendSummary = if (key == reused) lastSummary else summary
        reuseKey = key
        reuseEndable = false
        lastBody = sendBody
        lastSummary = sendSummary
        return Triple(key, sendBody, sendSummary)
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
        if (click.form !== formOwner) return
        // 发送前核对：会话已换（二次验证期间、排队期间）的不发，键不用了（从未发出或只收到过 10003，下次新键）
        if (!owned(click.account, click.epoch)) {
            reuseKey = null
            if (alive()) update { it.copy(phase = FlowState.EDITING) }
            return
        }
        val write = beginWrite() ?: return
        sentViewGen = viewGen
        // 沿用结果未知过的键（review-r1 S0 根因三）：按确认口径分类，20901 / 5xxxx 等不把它当成首次提交的失败结束、换新键
        val everUnknown = key == ownUnknown
        // 提交离开页面不取消（未决记录兜底）；会话换代即作废（尚未写出的不发）；禁用期是独立的发送前闸门（APP-08 共性第 27 条），不进归属；
        // 被它拦下时记下原因（共性第 62 条），回调按原因结算
        val probe = gate.probe()
        val owner = CallOwnership(deps.api.epochs(), sendAllowed = probe::allowed) { deps.env.sessionEpoch() == click.epoch }
        host.onWriteStarted(key)
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
                        val phase = if (everUnknown) SensitiveOutcome.CONFIRM else SensitiveOutcome.SUBMIT
                        SensitiveOutcome.of(outcomeOp, phase, keyEverUnknown = everUnknown, input(result))
                    }
                settleShared(click.account, click.epoch, result, outcome, countToken = true)
                // 页面已出栈或会话已换代：不改页面（结论已由网络层落到未决记录，共享结算已做）
                if (!alive() || deps.env.sessionEpoch() != click.epoch) return@launch
                // 同账号分区换代后的旧成功只刷新、轻提示，不锁新表单或写回旧成功标记。
                if (click.form !== formOwner) {
                    previousFormSucceeded(click.account, outcome)
                    return@launch
                }
                onSubmitResult(result, outcome, replayed, probe, key)
            } finally {
                finishWrite(write)
                if (replacedWriteKey == key) replacedWriteKey = null
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
        key: String,
    ) {
        if (outcome == null) {
            localRejected(result, probe, key)
            return
        }
        apply(outcome, result, SensitiveOutcome.SUBMIT, replayed, null, key)
    }

    /** 没有发出（本地拒绝）：同一操作已有未决键 → 按记录进待确认；限流闸门 → 限流提示；其余提示网络错误，键不用了。 */
    private fun localRejected(
        result: ApiResult,
        probe: SendProbe,
        key: String,
    ) {
        reuseKey = null
        val reason = (result as? ApiResult.LocalReject)?.reason
        val errorKey =
            when {
                // 本请求被发送前限流拒绝（共性第 62 条）：按记下的原因，不看回调时刻的禁用期
                probe.gated(result) -> "error.42901"

                reason == LocalRejectReason.PENDING_KEY_UNRESOLVED -> null

                else -> WalletErrors.NETWORK_KEY
            }
        update { it.copy(phase = FlowState.EDITING, error = errorKey?.let(::notice)) }
        // 本地拒绝 = 这次写没有离开本机（网络层发送前拒绝，记录未写入或已退回）：本次键的在途标记同步释放；别的键的未决记录由下面的重读接管（观察登记）
        if (result is ApiResult.LocalReject) host.onWriteReleased(key)
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
        val form: FormOwner,
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
        if (!alive() || localBusy || pending.inFlight || !owns(owner)) return null
        val account = owner.account ?: return null
        return Intent(account, owner.epoch, pending.key, formOwner)
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
        val write = beginWrite() ?: return
        sentViewGen = viewGen
        host.onWriteStarted(intent.key)
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
                    // 待确认卡的版本可以过时，原表单原键的成功终态不能丢，否则记录消失会放开新提交。
                    recordSupersededSuccess(intent, result, outcome)
                    refreshPending()
                    return@launch
                }
                if (outcome == null) {
                    update { it.copy(pendingNotice = notice(SensitiveOutcome.RETRY_LATER_KEY)) }
                    refreshPending()
                    return@launch
                }
                apply(outcome, result, SensitiveOutcome.CONFIRM, intent.stepUpReplayed, intent, intent.key)
            } finally {
                finishWrite(write)
                if (replacedWriteKey == intent.key) replacedWriteKey = null
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
        if (localBusy || pending.inFlight || !owns(owner)) return
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
        val write = beginWrite() ?: return
        sentViewGen = viewGen
        host.onWriteStarted(intent.key)
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
                    recordSupersededSuccess(intent, result, outcome)
                    refreshPending()
                    return@launch
                }
                if (outcome == null) {
                    // 本地阻断（禁用期、会话已换、记录已换键）：原键保持待确认
                    update { it.copy(pendingNotice = notice(SensitiveOutcome.RETRY_LATER_KEY)) }
                    refreshPending()
                    return@launch
                }
                apply(outcome, result, SensitiveOutcome.ABANDON, replayed = true, intent = null, key = intent.key)
            } finally {
                finishWrite(write)
                if (replacedWriteKey == intent.key) replacedWriteKey = null
                // 结算通知放在本页处理完之后（共性第 1 条）：Main.immediate 下通知会同步执行，先通知会抢在本页之前改掉待确认版本
                deps.pending.notify(intent.account, operationId)
            }
        }
    }

    /**
     * 结果来自 target(intent) 固定的原键，不用此刻的待确认卡重新判归属；卡已消失或换键也不能吞掉原键的成功。
     * 原表单成功仍登记资金锁；换表单的成功仅刷新、轻提示，过时的错误和已放弃不更新页面。
     */
    private fun recordSupersededSuccess(
        intent: Intent,
        result: ApiResult,
        outcome: Outcome?,
    ) {
        if (formOwner !== intent.form) {
            previousFormSucceeded(intent.account, outcome)
            return
        }
        if (result !is ApiResult.Success || outcome == null) return
        val done = outcome.actionRaw == SensitiveOutcome.DONE
        val alreadyDone = outcome.actionRaw == SensitiveOutcome.ALREADY_DONE && outcome.textKey == SensitiveOutcome.ALREADY_DONE_KEY
        if (done || alreadyDone) host.onSucceeded(result.data as? JsonObject, alreadyDone)
    }

    /** 同账号换表单后的旧成功：只告知上一笔已提交，不走新表单的成功锁、错误条或导航。 */
    private fun previousFormSucceeded(
        account: String,
        outcome: Outcome?,
    ) {
        if (operationId != WithdrawController.OPERATION || formOwner.account != account) return
        val succeeded =
            outcome?.actionRaw == SensitiveOutcome.DONE ||
                (outcome?.actionRaw == SensitiveOutcome.ALREADY_DONE && outcome.textKey == SensitiveOutcome.ALREADY_DONE_KEY)
        if (!succeeded) return
        update { it.copy(toast = notice("withdraw.previous_submitted").copy(assertive = false)) }
        host.onSettled()
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
        key: String,
    ) {
        if (outcome.nextSubmitNewKey) reuseKey = null
        // 本页写过的键：结束了就不再是未决；首次提交进待确认、或确认 / 放弃后仍未结束，记为本页结果未知的键
        if (outcome.keyEnds) {
            if (ownUnknown == key) trackUnknown(null)
        } else if (phase != SensitiveOutcome.SUBMIT || outcome.actionRaw in PENDING_ACTIONS) {
            trackUnknown(key)
        }
        // F-AND-44（FU-AND-0721）：首次提交、从未结果未知的键，凡不是成功也不进待确认的结局（42901、44001、5xxxx、补办引导、强更、10003、
        // 10002 / 10402 预算用尽等），网络层都已删除它的记录、没有业务结果——在途标记与记录同生命周期，同步释放；成功另由成功锁保持，
        // 40901 / 超时 / 断连进待确认不释放。沿用原键再发时 [send] 重新设上。确认 / 放弃阶段不按结局码放锁（维持现状）。
        val settledWithoutResult =
            outcome.actionRaw != SensitiveOutcome.DONE && outcome.actionRaw != SensitiveOutcome.ALREADY_DONE &&
                outcome.actionRaw !in PENDING_ACTIONS
        if (phase == SensitiveOutcome.SUBMIT && key != ownUnknown && settledWithoutResult) host.onWriteReleased(key)
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
                    host.onWriteReleased(key)
                    host.onSettled()
                }
            }

            SensitiveOutcome.STEP_UP -> {
                // 10003 的在途标记已在上面释放：于是「有在途标记却没有记录」只剩进程死亡窗口，守卫按未决键锁住，不需要读内存态
                when {
                    replayed -> {
                        stepUpExhausted(phase, message, key)
                    }

                    // 页面被覆盖：不打开面板，保留原键（首次提交下次点击沿用；确认阶段保持待确认）
                    !onScreen -> {
                        if (sentBeforeFirstVisible) deferredStepUp = DeferredStepUp(formOwner, phase, intent)
                        stepUpDeferred(phase, message)
                    }

                    else -> {
                        openStepUp(phase, intent)
                    }
                }
            }

            SensitiveOutcome.ABANDONED -> {
                reuseKey = null
                endPending()
                update { it.copy(phase = FlowState.EDITING, toast = message?.copy(assertive = false), error = null) }
                host.onWriteReleased(key)
                host.onSettled()
            }

            SensitiveOutcome.SHOW_ERROR_KEY_ENDS, SensitiveOutcome.RETRY_AFTER, SensitiveOutcome.ERROR_TRACE6,
            SensitiveOutcome.BUSY_RETRY, SensitiveOutcome.AUTO_RESEND,
            -> {
                if (phase != SensitiveOutcome.SUBMIT) endPending()
                update { it.copy(phase = FlowState.EDITING, error = message ?: notice(WalletErrors.NETWORK_KEY)) }
                if (outcome.actionRaw == SensitiveOutcome.SHOW_ERROR_KEY_ENDS) host.onWriteReleased(key)
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
                host.onWriteReleased(key)
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
        key: String,
    ) {
        val text = message ?: notice("error.10003")
        if (phase == SensitiveOutcome.SUBMIT && key != ownUnknown) {
            reuseKey = null
            // 首次提交的键就此结束（F-AND-44）：释放重建用的在途标记
            host.onWriteReleased(key)
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
            // 原键只收到 10003（没有业务结果）：用户原样再点沿用原键原体，改了表单再点则结束它、换新键
            reuseEndable = reuseKey != null && reuseKey != ownUnknown
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
        // 首次提交的面板属于打开时沿用的原键：用户关闭面板时只释放这个键的在途标记
        val panelKey = reuseKey.takeIf { phase == SensitiveOutcome.SUBMIT && it != ownUnknown }
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
                if (phase == SensitiveOutcome.SUBMIT && panelKey != null) {
                    reuseKey = null
                    // 本次键结束（F-AND-44）：释放重建用的在途标记（不改操作锁，面板打开期间没有在途写）
                    panelKey?.let(host::onWriteReleased)
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
     * 本页写操作持锁期间不被记录的消失或换键打断，结果采用后再同步。
     */
    fun refreshPending() {
        sync(deps.env.identitySnapshot())
    }

    /** 一次按持久记录的同步结果：读到的本机记录（没有为 null）。 */
    private class Synced(
        val record: PendingKeyRecord?,
    )

    /**
     * 把持久记录同步到页面（[refreshPending] 与提交前守卫共用的唯一读取路径）：按本表单身份现读本机记录，重建待确认卡、登记观察、按保存的关联判保守锁、
     * 收起已失效的二次验证面板。返回 null = 这次没能读（页面已出栈、身份不是 SAME、记录读不出），守卫按不放行处理；界面保持原样或置 pendingUnreadable。
     */
    @Suppress("ReturnCount") // 出栈、身份、读不出、别的账号的记录逐项不读
    private fun sync(identity: com.couli.core.network.SessionIdentity): Synced? {
        if (!alive()) return null
        // 只把本表单身份的记录放进本表单（共性第 65 条）：当前会话已不是固定身份（令牌已换、清空通知还没执行）时不读，读到别的账号的记录也不放进来
        // 一份快照（FU-AND-0717 第 3 轮）：身份暂时未知、已换代都不读（不改已显示的待确认状态）；未知期取得的表单等接管（[adoptOwner]）之后再读
        val owner = formOwner
        if (owner.verdict(identity) != com.couli.core.network.IdentityVerdict.SAME) return null
        val record =
            try {
                deps.api.pendingFor(operationId)
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception,
            ) {
                update { it.copy(pendingUnreadable = true) }
                return null
            }
        if (record != null && record.account != owner.account) return null
        update { state -> reconcile(state, record?.let(::view)) }
        lockIfUnresolvedVanished(record)
        // 观察即未知（review-r3 S0）：本页采用了待确认记录（结果未知或曾未知），就把它记为本页结果未知的键并同步存进可恢复状态
        if (record != null && s.pending?.key == record.key && observable(record)) trackUnknown(record.key)
        // 确认流程的二次验证所属的键已在别处结算或被替换：同步收起面板（共性第 49 条）
        val intent = stepUpIntent
        if (intent != null && s.pending?.key != intent.key) {
            stepUpIntent = null
            stepUp.dismiss()
        }
        return Synced(record)
    }

    /**
     * 本页观察到的待确认记录：结果未知的记录，或在途的记录（曾未知而正被确认；或别页首次提交在途、本页显示忙碌卡——G17 裁定也算观察，服务端给出
     * 它的终态之前本页不生成新键）。本页自己持锁的写不算观察（它的结局由 [apply] 采用）；换代前本流程旧表单自己还在途的那个键除外（[replacedWriteKey]，
     * 只属于本流程，别页在途的卡片键不享有）。
     */
    private fun observable(record: PendingKeyRecord): Boolean =
        record.key != replacedWriteKey &&
            when (record.stateRaw) {
                PendingKeyStore.UNKNOWN -> true
                PendingKeyStore.IN_FLIGHT -> record.everUnknown || !localBusy
                else -> false
            }

    /**
     * 「本页结果未知的键」的唯一入口（review-r2 / r3 统一）：本页写出的键结果未知、本页观察到的待确认记录（[refreshPending]）都走这里，并同步交给
     * 宿主保存（[FlowHost.onUnknownChanged]）。已因原键消失而保守锁住时不再换成别的键（锁的依据不能被后来的记录冲掉）；传 null 只在本页采用其终态时（会话换代由 [resetFor] 清）。
     */
    private fun trackUnknown(key: String?) {
        if (key != null && s.unresolvedLock && ownUnknown != null) return
        if (ownUnknown == key) return
        ownUnknown = key
        host.onUnknownChanged(key)
    }

    /**
     * review-r1 S0 根因一：本表单持久关联的键（[FlowHost.persistedKeys]：本页写过 / 观察到而结果未知的键，或本页交给网络层的键）的记录消失或被别的键替换，
     * 而本页没有采用它的终态（别处确认成功 / 放弃、记录被删都分不清）——不回到可提交的编辑态，保守锁住，并通知宿主显示与重建后相同的提交锁；标记不动。
     * 只按持久关联判定（不读内存态）；本页写操作持锁期间（记录可能还没写入）不判。没有持久关联的宿主（收款账号）不会进入。
     */
    private fun lockIfUnresolvedVanished(record: PendingKeyRecord?) {
        if (localBusy) return
        val key = host.persistedKeys().unresolvedAgainst(record?.key) ?: return
        if (!s.unresolvedLock) {
            update { it.copy(unresolvedLock = true, phase = if (it.phase == FlowState.PENDING) FlowState.EDITING else it.phase) }
            host.onWriteUnresolved(key)
        }
    }

    /** 本页写操作结果采用前不让记录同步改阶段或版本；释放操作锁后再按最新记录重建。 */
    private fun reconcile(
        state: FlowState,
        view: PendingView?,
    ): FlowState {
        if (localBusy) return state.copy(pendingUnreadable = false)
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
        return state.copy(
            phase = FlowState.PENDING,
            pending = view,
            pendingVersion = if (fresh) state.pendingVersion + 1 else state.pendingVersion,
            pendingUnreadable = false,
        )
    }

    private fun view(record: PendingKeyRecord): PendingView =
        PendingView(record.key, record.summary, record.createdAtMillis, inFlight = record.stateRaw == PendingKeyStore.IN_FLIGHT)

    /** 独立于记录的本页操作锁；令牌防止旧表单的 finally 解除新表单的写锁。 */
    private var activeWrite: Any? = null
    private val localBusy: Boolean get() = s.operationInFlight

    private fun beginWrite(): Any? {
        if (localBusy) return null
        // 默认可见的控制器入口也算可见过；Compose 首帧会先同步实际可见性。
        if (visible) everVisible = true
        sentBeforeFirstVisible = !everVisible
        deferredStepUp = null
        val write = Any()
        activeWrite = write
        markInFlight(true)
        return write
    }

    /** 成功回调、错误或二次验证结局已采用之后，才放开入口并同步本机记录。 */
    private fun finishWrite(write: Any) {
        if (activeWrite !== write) return
        activeWrite = null
        markInFlight(false)
        refreshPending()
        showDeferredStepUp()
    }

    private fun markInFlight(busy: Boolean) {
        update { state ->
            state.copy(
                operationInFlight = busy,
                pending = state.pending?.copy(inFlight = busy),
                pendingNotice = if (busy && state.pending != null) notice(BUSY_KEY).copy(assertive = false) else state.pendingNotice,
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
        replacedWriteKey = if (localBusy) s.pending?.key ?: reuseKey else null
        formOwner = owner
        reuseKey = null
        if (ownUnknown != null) {
            ownUnknown = null
            host.onUnknownChanged(null)
        }
        reuseEndable = false
        lastBody = null
        lastSummary = ""
        lastClick = null
        stepUpIntent = null
        deferredStepUp = null
        guideOwner = null
        stepUp.dismiss()
        activeWrite = null
        mutable.value = FlowState(pendingVersion = s.pendingVersion + 1, owner = formOwner)
    }

    fun leave() {
        deferredStepUp = null
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

        /** 进入或保持待确认的结局（apply 的 else 分支）：首次提交收到它们，键结果未知。 */
        private val PENDING_ACTIONS =
            setOf(SensitiveOutcome.PENDING_CONFIRM, SensitiveOutcome.SHOW_ERROR_KEEP_PENDING, SensitiveOutcome.ABANDON_BUSY)
    }
}
