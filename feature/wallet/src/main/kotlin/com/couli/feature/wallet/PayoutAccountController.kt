package com.couli.feature.wallet

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.wallet.PayoutAccountDisplay
import com.couli.core.wallet.PayoutAccountForm
import com.couli.core.wallet.PayoutAccountView
import com.couli.core.wallet.PayoutForm
import com.couli.core.wallet.PayoutPendingSummary
import com.couli.core.wallet.SendProbe
import com.couli.core.wallet.SensitiveOutcome
import com.couli.core.wallet.WalletError
import com.couli.core.wallet.WalletErrors
import com.couli.core.wallet.WalletJson
import com.couli.core.wallet.WalletRoutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 收款账号页的一次状态快照。[form] 是用户填写的原文（只在内存，不进 SavedStateHandle、日志与截图）。 */
data class PayoutSnapshot(
    val phase: String = LOADING,
    val account: PayoutAccountDisplay? = null,
    val editing: Boolean = false,
    val form: PayoutForm = PayoutForm(),
    /** 本地校验没过的字段（必填为空等）。 */
    val localErrors: List<String> = emptyList(),
    /** 本地校验错误事件编号（每次【保存】校验不过换新，APP-09 共性第 24 条）。 */
    val localErrorId: Long = 0,
    val loadError: Notice? = null,
    val version: Long = 0,
    /** 保存成功、停在本页时的轻提示。 */
    val toast: Notice? = null,
    /** 读取得到 30304 而页面当时不可见：不自动打开实名页，保留目标并给手动入口（APP-09 共性第 51 条）。 */
    val guideRoute: String? = null,
) {
    companion object {
        const val LOADING = "loading"
        const val CONTENT = "content"
        const val ERROR = "error"
    }
}

/**
 * 收款账号页执行器（PayoutAccount 路由；BR-WDR-02，FUND-03，brief §2.7，G12–G15）：已绑定显示服务端脱敏字段与「本月还能变更 {n} 次」+【更换收款账号】；
 * 未绑定直接显示表单（支付宝 / 本人借记卡，不显示微信零钱）。【保存】经 [SensitiveFlow]（savePayoutAccount、action=payout_account_change，首次绑定也要
 * 二次验证），未决键规则与提现相同；待确认摘要本机按 BR-ID-33 细则脱敏（G15）。成功后显示服务端返回的脱敏串，清空表单与内存中的完整输入，再按
 * App 的判断出栈回发起页（从 Withdraw 进来）或停在本页。只在主线程使用。
 */
@Suppress("TooManyFunctions") // 页面每个入口一个函数
class PayoutAccountController(
    private val deps: WalletDeps,
    private val scope: CoroutineScope,
    /** 本页的回退栈条目（保存成功的出栈只作用于它，共性第 50 条）。 */
    private val entryId: String? = null,
    /** 本表单的固定身份（APP-09 共性第 65 条）：构造这一刻一次性取得，之后读取、表单与【保存】都只用它，当前会话只用于和它比较。 */
    initialOwner: FormOwner = FormOwner.of(deps.env),
) : FlowHost {
    private var left = false
    private var seq = 0L
    private val owners = mutableListOf<CallOwnership>()

    private val mutable = MutableStateFlow(PayoutSnapshot())
    val state: StateFlow<PayoutSnapshot> = mutable.asStateFlow()
    private val s: PayoutSnapshot get() = mutable.value

    val flow =
        SensitiveFlow(
            deps,
            scope,
            operationId = OPERATION,
            action = ACTION,
            outcomeOp = SensitiveOutcome.PAYOUT_ACCOUNT,
            gate = deps.gates.payout,
            host = this,
            alive = { !left },
            initialOwner = initialOwner,
        )

    val alive: Boolean get() = !left

    fun start() {
        if (left || !deps.env.loggedIn()) return
        runCatching { deps.api.restorePending() }
        flow.refreshPending()
        load()
    }

    /** 回到前台（实名补办后返回，G05）：保留已填内容，重读收款账号与未决记录。 */
    fun onResume() {
        if (left) return
        flow.refreshPending()
        load()
    }

    fun load() {
        if (left || !deps.env.loggedIn()) return
        if (deps.gates.read.active) {
            gatedLoad()
            return
        }
        val request = ++seq
        // 读取绑定本表单的固定身份（共性第 65 条）：不读取执行时的当前会话，网络层按 expectedAccount 核对账号
        val form = flow.owner
        val epoch = form.epoch
        // 读取触发的前置引导（30304）只在发出时的可见世代仍有效、页面此刻可见时才导航（共性第 47 条）
        val navGen = viewGen
        if (s.account == null) update { it.copy(phase = PayoutSnapshot.LOADING, loadError = null) }
        // 本请求的发送前限流闸门（共性第 62 条）：被它拦下时记下原因，回调按原因结算
        val probe = deps.gates.read.probe()
        val owner =
            CallOwnership(deps.api.epochs(), sendAllowed = probe::allowed) {
                !left && seq == request && deps.env.sessionEpoch() == epoch
            }.also { owners += it }
        scope.launch {
            val result = deps.api.send(ApiCall(GET_OPERATION, ownership = owner, expectedAccount = form.account))
            owners -= owner
            if (left || seq != request || deps.env.sessionEpoch() != epoch) return@launch
            onLoaded(result, navGen, probe)
        }
    }

    /** 本地限流拦下也结算本次读取（共性第 30、45、56 条）：没有内容的进加载失败态；已有账号的保留但不再算当前（独立提示 42901 与【重试】，不能发起更换）。 */
    private fun gatedLoad() {
        val notice = Notice("error.42901", ++ids)
        update { if (it.account == null) it.copy(phase = PayoutSnapshot.ERROR, loadError = notice) else it.copy(loadError = notice) }
    }

    private fun onLoaded(
        result: ApiResult,
        navGen: Long,
        probe: SendProbe,
    ) {
        val account = (result as? ApiResult.Success)?.let { WalletJson.payoutAccount(it.data) }
        if (account != null) {
            val view = PayoutAccountView.view(account)
            // 未绑定直接显示表单；已绑定显示账号，正在更换的保留表单
            update {
                it.copy(
                    phase = PayoutSnapshot.CONTENT,
                    account = view,
                    editing = it.editing || !view.bound,
                    loadError = null,
                    guideRoute = null,
                    version =
                        it.version + 1,
                )
            }
            return
        }
        val error = WalletErrors.of(result)
        if (error.kind == WalletError.CANCELLED) {
            // 本轮读取被发送前限流闸门拦下（APP-09 共性第 56、62 条，Android R3-05、RC-04）：按本请求记下的拒绝原因结算（不看回调时刻的禁用期，
            // 它可能已到期）——没有内容的结束加载、进 42901 与【重试】；已有账号的标记读取失败（不能发起更换）。归属失效的（出栈、换代、被新一轮取代）
            // 在调用方已丢弃
            if (probe.gated(error)) gatedLoad()
            return
        }
        if (error.kind == WalletError.RETRY_AFTER) deps.gates.read.start(error.disableMillis)
        if (error.kind == WalletError.FORCE_UPDATE) deps.env.forceUpdate()
        // 30304（未实名）：去实名，回来仍在本页（G05）。页面被覆盖或退到后台（可见世代已变）时只保存错误，不导航；回到本页的重读再决定（共性第 47 条）
        if ((result as? ApiResult.Failure)?.code == REALNAME_REQUIRED) {
            if (visible && viewGen == navGen) {
                deps.env.open(WalletRoutes.REAL_NAME, EMPTY)
            } else {
                // 记下引导所属的表单身份（共性第 65 条），不读取响应到达时的当前会话
                guideOwner = flow.owner
                update { it.copy(guideRoute = WalletRoutes.REAL_NAME) }
            }
        }
        val notice = Notice(error.textKey, ++ids, error.traceId, error.showTrace6)
        if (s.account == null) {
            update { it.copy(phase = PayoutSnapshot.ERROR, loadError = notice) }
        } else {
            // 已有内容时刷新失败（共性第 3、30 条）：保留内容，独立显示错误（42901 / 5xxxx + trace6）与【重试】，旧账号不能再发起更换
            update { it.copy(loadError = notice) }
        }
    }

    // ---------------------------------------------------------------- 表单

    /** 【更换收款账号】：[version] 与 [shown] 是构建这张卡时捕获的渲染版本与账号身份，点击时两者都仍匹配才受理（APP-09 共性第 25 条）。 */
    fun change(
        version: Long,
        shown: PayoutAccountDisplay,
    ) {
        val account = s.account?.takeIf { s.version == version && it == shown && !left && s.loadError == null } ?: return
        if (account.bound && !account.known) return
        update { it.copy(editing = true) }
    }

    /**
     * 表单的输入、自动填充与选择回调都带渲染这张表单时的身份 [owner]（APP-09 共性第 59 条，Android RC-01）：写入前核对它就是本表单当前的身份、且仍是
     * 当前会话；会话已换（清空通知执行前后）到达的旧回调不写入——旧账号填的卡号、姓名不得进换代后的表单。
     */
    fun chooseMethod(
        method: String,
        owner: FormOwner = flow.owner,
    ) {
        if (method != WalletJson.ALIPAY && method != WalletJson.BANK_CARD) return
        if (!ownsForm(owner)) return
        update { it.copy(form = it.form.copy(method = method), localErrors = emptyList()) }
        flow.clearFieldErrors()
    }

    fun onLogonChange(
        text: String,
        owner: FormOwner = flow.owner,
    ) = edit(owner) { it.copy(alipayLogonId = text.take(PayoutAccountForm.LOGON_MAX)) }

    fun onCardChange(
        text: String,
        owner: FormOwner = flow.owner,
    ) = edit(owner) { it.copy(cardNo = PayoutAccountForm.acceptCardNo(it.cardNo, text)) }

    fun onBankChange(
        text: String,
        owner: FormOwner = flow.owner,
    ) = edit(owner) { it.copy(bankName = text.take(PayoutAccountForm.NAME_MAX)) }

    fun onPayeeChange(
        text: String,
        owner: FormOwner = flow.owner,
    ) = edit(owner) { it.copy(payeeName = text.take(PayoutAccountForm.NAME_MAX)) }

    private fun ownsForm(owner: FormOwner): Boolean = !left && owner == flow.owner && owner.current(deps.env)

    private inline fun edit(
        owner: FormOwner,
        change: (PayoutForm) -> PayoutForm,
    ) {
        if (!ownsForm(owner)) return
        update { it.copy(form = change(it.form), localErrors = emptyList()) }
    }

    /**
     * 【保存】：只去首尾空白（G13）；字段不合格就地提示、不发请求；否则新键提交（首次绑定也要二次验证）。[owner] 是渲染这张表单时的账号与登录世代
     * （共性第 52 条）：不是当前会话的（会话已换、清空通知还没执行）由 [SensitiveFlow.submit] 在生成键、读取 token 前拦下，不发。
     */
    fun save(owner: FormOwner = flow.owner) {
        if (left || !flow.state.value.canSubmit) return
        val result = PayoutAccountForm.build(s.form)
        val body = result.body
        if (body == null) {
            update { it.copy(localErrors = result.fieldErrors, localErrorId = it.localErrorId + 1) }
            return
        }
        val summary = PayoutPendingSummary.summarize(s.form)
        val encoded =
            JsonObject(
                mapOf(
                    "method" to JsonPrimitive(summary.method),
                    "account" to (summary.account?.let(::JsonPrimitive) ?: JsonNull),
                    "payee" to (summary.payee?.let(::JsonPrimitive) ?: JsonNull),
                ),
            ).toString()
        flow.submit(body.toString(), encoded, owner)
    }

    /** 44001 弹窗的【联系客服申诉】：接入前经 Router 打开 Help。 */
    fun appeal() {
        flow.dismissRisk()
        deps.env.open(WalletRoutes.HELP, EMPTY)
    }

    // ---------------------------------------------------------------- FlowHost

    override fun onSucceeded(
        data: JsonObject?,
        alreadyDone: Boolean,
    ) {
        val saved = WalletJson.payoutAccount(data)?.let(PayoutAccountView::view)?.takeIf { it.bound }
        // 保存成功推进账号读取世代（共性第 43 条）：保存之前发起的读取一律作废并取消，旧响应不得把新账号覆盖回去
        seq += 1
        cancelAll()
        // 清空表单与内存中的完整输入
        update {
            it.copy(
                form = PayoutForm(),
                editing = false,
                account = saved ?: it.account,
                localErrors = emptyList(),
                loadError = if (saved != null) null else it.loadError,
                version =
                    it.version + 1,
            )
        }
        // 需要复查时另起保存之后的新一轮读取
        if (saved == null) load()
        // 出栈回发起页是导航：页面此刻不在栈顶可见时留到回到可见再做（共性第 37、41 条）
        if (!flow.visibleNow) {
            deferredSaved = alreadyDone
            return
        }
        afterSaved(alreadyDone)
    }

    /** 保存成功后的出栈或停在本页提示；页面不可见时延后。 */
    private var deferredSaved: Boolean? = null

    private fun afterSaved(alreadyDone: Boolean) {
        if (!alreadyDone && !deps.env.payoutSaved(entryId)) {
            // 停在本页：轻提示「收款账号已保存」
            update { it.copy(toast = Notice(SAVED_KEY, ++ids, assertive = false)) }
        }
    }

    private var guideOwner: FormOwner? = null

    /** 被拦下的引导的手动入口（共性第 51 条）：页面此刻可见、会话仍是记下时的才打开；已填内容保留。 */
    fun openGuide() {
        val route = s.guideRoute ?: return
        if (left || !visible) return
        update { it.copy(guideRoute = null) }
        if (guideOwner == flow.owner && flow.owner.current(deps.env)) deps.env.open(route, EMPTY)
    }

    /** 页面可见性（ON_RESUME / ON_PAUSE，共性第 37、47 条）：被覆盖或退到后台时可见世代加 1。 */
    private var visible = true
    private var viewGen = 0L

    fun onVisibility(shown: Boolean) {
        if (shown != visible) viewGen += 1
        visible = shown
        flow.onVisibility(shown)
        if (!shown || left) return
        val done = deferredSaved ?: return
        deferredSaved = null
        afterSaved(done)
    }

    fun consumeToast(id: Long) {
        if (s.toast?.id == id) update { it.copy(toast = null) }
    }

    override fun onSettled() {
        if (!left) load()
    }

    override fun onGuide(route: String) {
        // 收款账号页自己的错误（30307 等）留在本页显示，不重复压入同名页面
        if (!left && route != WalletRoutes.PAYOUT_ACCOUNT) deps.env.open(route, EMPTY)
    }

    // ---------------------------------------------------------------- 会话、离页

    /** 会话换代：同步清空已填内容（含姓名、卡号、登录号）与已显示的账号，作废在途请求，再按新会话重取。 */
    fun onSessionChanged() {
        if (left) return
        seq += 1
        deferredSaved = null
        cancelAll()
        mutable.value = PayoutSnapshot(version = s.version + 1)
        guideOwner = null
        // 新表单（已清空）的固定身份在此一次性取得（共性第 65 条）
        flow.onSessionChanged(FormOwner.of(deps.env))
        start()
    }

    fun leave() {
        if (left) return
        left = true
        seq += 1
        cancelAll()
        flow.leave()
        mutable.value = s.copy(form = PayoutForm())
    }

    private fun cancelAll() {
        val cancelled = owners.toList()
        owners.clear()
        cancelled.forEach(deps.api::cancelOwned)
    }

    private var ids = 0L

    private inline fun update(change: (PayoutSnapshot) -> PayoutSnapshot) {
        mutable.value = change(mutable.value)
    }

    companion object {
        const val OPERATION = "savePayoutAccount"
        const val ACTION = "payout_account_change"
        const val GET_OPERATION = "getPayoutAccount"
        private const val REALNAME_REQUIRED = 30_304
        const val SAVED_KEY = "payout.saved"
        val EMPTY = JsonObject(emptyMap())

        /** 待确认摘要（本机脱敏）的读取。 */
        fun pendingSummary(summary: String): Triple<String?, String?, String?> =
            runCatching {
                val o = Json.parseToJsonElement(summary) as JsonObject

                fun text(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                Triple(text("method"), text("account"), text("payee"))
            }.getOrDefault(Triple(null, null, null))
    }
}
