// 错误码是契约取值（contracts/error-codes.json），按码直接写
@file:Suppress("MagicNumber")

package com.couli.feature.wallet

import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.IdentityVerdict
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.SessionIdentity
import com.couli.core.wallet.RealNameForm
import com.couli.core.wallet.RealNameInput
import com.couli.core.wallet.SendProbe
import com.couli.core.wallet.SensitiveOutcome
import com.couli.core.wallet.WalletError
import com.couli.core.wallet.WalletErrors
import com.couli.generated.DefaultTexts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 实名页状态。[name]、[idNo] 是用户填写的原文（只在内存；不进 SavedStateHandle、日志与截图）。 */
data class RealNameSnapshot(
    val name: String = "",
    val idNo: String = "",
    val consentOpen: Boolean = false,
    val consentChecked: Boolean = false,
    val submitting: Boolean = false,
    val error: Notice? = null,
    val fieldErrors: List<String> = emptyList(),
    /** 字段错误事件编号（APP-09 共性第 24 条）。 */
    val fieldErrorId: Long = 0,
    /** 30410 当日次数用完：按钮置灰到本页退出。 */
    val lockedToday: Boolean = false,
    val done: Notice? = null,
    /** 本表单所属的账号与登录世代（APP-09 共性第 52 条）：按钮回调带上它，执行前核对仍是当前会话。 */
    val owner: FormOwner = FormOwner(null, Long.MIN_VALUE),
) {
    override fun toString(): String = "RealNameSnapshot(submitting=$submitting, locked=$lockedToday)"
}

/**
 * 实名认证执行器（RealName 路由；BR-ID-25，D10-887）：姓名去首尾空白；身份证号只收数字与 X，18 位，小写 x 转大写，不做校验位计算。提交前先弹单独同意（默认不勾选，点遮罩不关闭），
 * 勾选后先记同意（code 0 之后才发实名提交）。后端为 null 时【提交认证】置灰，显示「实名认证暂未开放」，不发任何请求。
 *
 * 幂等键与规范化后的请求体绑定，同一个键绝不配另一份请求体：
 * - 结果未知（断连）、10001 / 10005、10004 id_verification（重开单独同意，同意后原键重放）：保留原键，表单不变再提交沿用；表单改了旧键作废（D10-887 ③）。
 * - 40901：不是终态，保持在途，退避后原键原体自动重发、不重记同意（连首发 6 秒内最多 4 次，D10-887 ⑤）；用完停下、出 error.40901、保留原键。
 * - 结果未知后再拿到 10405 / 5xxxx / 42901 不结束键（D10-1018，核验按次计费）；其余错误结束本次键，下次用新键。50301 回落 error.50001 + trace6（D10-887 ②）。
 *
 * 成功后本会话按已实名放行、复查 GET /v1/me、清掉已填内容、出栈回发起页（G05，不重放）；结果只给表单固定的身份。只在主线程使用。
 */
@Suppress("TooManyFunctions") // 页面每个入口一个函数（含可见性）；键去留、同意先行、40901 重发各一个函数
class RealNameController(
    private val deps: WalletDeps,
    private val scope: CoroutineScope,
    private val onBack: () -> Unit,
) {
    /** 页面世代：离页时同步置位。网络线程经 [bindingFor] 的 stillOwned 读它，所以 @Volatile。 */
    @Volatile private var left = false
    private var serial = 0L

    /** 最近一次发送的归属（离页时取消已交给传输层的请求）。 */
    private var activeOwnership: CallOwnership? = null

    /** 保留的幂等键与它绑定的规范化请求体（只在内存）；null = 下次提交用新键。 */
    private var retained: RetainedSubmit? = null

    // 表单身份在构造时一次性取得（APP-09 共性第 65 条）；之后只比较、不再读取当前会话来赋值
    private val mutable = MutableStateFlow(RealNameSnapshot(owner = FormOwner.of(deps.env)))
    val state: StateFlow<RealNameSnapshot> = mutable.asStateFlow()
    private val s: RealNameSnapshot get() = mutable.value

    /** 没有实名后端（[WalletDeps.realname] 为 null）时不开放；生产接线是 [RemoteRealNameBackend]。 */
    val available: Boolean get() = deps.realname != null

    val submitEnabled: Boolean
        get() = available && !s.lockedToday && !s.submitting && RealNameForm.normalize(s.name, s.idNo).submitEnabled

    /**
     * 姓名、身份证号的输入与自动填充回调带渲染这张表单时的身份 [owner]（APP-09 共性第 59 条，Android RC-01）：写入前核对它就是本表单当前的身份、
     * 且仍是当前会话；会话已换（清空通知执行前后）到达的旧回调不写入——旧账号的身份证号不得进换代后的表单。
     */
    fun onNameChange(
        text: String,
        owner: FormOwner = s.owner,
    ) {
        if (left || !owns(owner)) return
        mutable.value = s.copy(name = text.take(NAME_MAX), fieldErrors = s.fieldErrors - FIELD_NAME, error = null)
        dropRetainedIfFormChanged()
    }

    fun onIdChange(
        text: String,
        owner: FormOwner = s.owner,
    ) {
        if (left || !owns(owner)) return
        mutable.value = s.copy(idNo = RealNameForm.acceptIdNo(s.idNo, text), fieldErrors = s.fieldErrors - FIELD_ID, error = null)
        dropRetainedIfFormChanged()
    }

    /** 表单改了：旧键作废，下次用新键（D10-887 ③；同一个键不配另一份请求体）。 */
    private fun dropRetainedIfFormChanged() {
        val held = retained ?: return
        if (RealNameForm.normalize(s.name, s.idNo) != held.input) retained = null
    }

    /** 渲染快照的身份就是本表单的身份，且仍是当前会话（共性第 52 条）。 */
    private fun owns(owner: FormOwner): Boolean = owner == s.owner && owner.current(deps.env)

    /** 【提交认证】：先弹单独同意（默认不勾选）。[owner] 是渲染这张表单时的身份（共性第 52 条）。 */
    fun submit(owner: FormOwner = s.owner) {
        if (left || !submitEnabled || deps.gates.realname.active) return
        if (!owns(owner)) return
        mutable.value = s.copy(consentOpen = true, consentChecked = false)
        deps.env.sensitiveOverlay(true)
    }

    fun toggleConsent(owner: FormOwner = s.owner) {
        if (s.consentOpen && owns(owner)) mutable.value = s.copy(consentChecked = !s.consentChecked)
    }

    fun declineConsent() {
        mutable.value = s.copy(consentOpen = false, consentChecked = false)
        deps.env.sensitiveOverlay(false)
    }

    /** 单独同意里勾选后点【同意并继续】：先记同意（code 0 之后才提交），再发实名提交。 */
    @Suppress("ReturnCount")
    fun agreeAndSubmit(owner: FormOwner = s.owner) {
        val backend = deps.realname ?: return
        if (!s.consentChecked || s.submitting || left) return
        // 表单所属身份（共性第 52 条）：先于生成键核对；会话已换而清空通知还没执行时，旧表单不发
        if (!owns(owner)) return
        val input = RealNameForm.normalize(s.name, s.idNo)
        if (!input.submitEnabled) return
        // 固定表单身份的账号与会话世代（共性第 39、52 条）：发送前再核对，会话已换的不发
        val account = owner.account ?: return
        val sendEpoch = owner.epoch
        mutable.value = s.copy(consentOpen = false, submitting = true, error = null)
        deps.env.sensitiveOverlay(false)
        scope.launch {
            // 一份快照核对（FU-AND-0717 第 3 轮）：会话已换或身份暂时确认不了都不发
            if (!deps.env.ownedBy(account, sendEpoch)) {
                if (!left) mutable.value = s.copy(submitting = false)
                return@launch
            }
            val consentGate = bindingFor(account, sendEpoch)
            val consent = backend.recordConsent(consentGate.binding)
            if (left || deps.env.sessionEpoch() != sendEpoch) return@launch
            if (consent !is ApiResult.Success) return@launch settleFailure(consent, consentGate.probe, keyApplies = false)
            // 身份暂时确认不了：不发实名提交，恢复按钮（同意记录已成，重发时再记一次）
            if (!deps.env.ownedBy(account, sendEpoch)) {
                mutable.value = s.copy(submitting = false)
                return@launch
            }
            submitWithBusyRetry(backend, input, account, sendEpoch)
        }
    }

    /** 实名提交：取键后发送，40901 保持在途、原键原体退避重发（F-AND-48，D10-887 ⑤）；重发用完交给失败结算，保留原键。 */
    @Suppress("ReturnCount")
    private suspend fun submitWithBusyRetry(
        backend: RealNameBackend,
        input: RealNameInput,
        account: String,
        sendEpoch: Long,
    ) {
        val key = claimKey(input)
        var resends = 0
        while (true) {
            val gate = bindingFor(account, sendEpoch)
            val result = backend.submit(input.name, input.idNo, key, gate.binding)
            if (left || deps.env.sessionEpoch() != sendEpoch) return
            if (result is ApiResult.Success) return onSubmitted()
            if ((result as? ApiResult.Failure)?.code != BUSY_CODE || resends == BUSY_RESEND_DELAYS_MS.size) {
                return settleFailure(result, gate.probe, keyApplies = true)
            }
            delay(BUSY_RESEND_DELAYS_MS[resends++])
            // 重发前再核对（离页、换号都不再发）；同意记录不重记
            if (left) return
            if (!deps.env.ownedBy(account, sendEpoch)) {
                mutable.value = s.copy(submitting = false)
                return
            }
        }
    }

    /** 表单没改就沿用保留的键（断连、10001、10005、40901 用完等），否则新键（同一个键不配另一份请求体）。 */
    private fun claimKey(input: RealNameInput): String {
        val kept = retained?.takeIf { it.input == input }
        val key = kept?.key ?: deps.newKey()
        retained = RetainedSubmit(input, key, unknown = kept?.unknown ?: false)
        return key
    }

    private class SendGate(
        val binding: RealNameCallBinding,
        val probe: SendProbe,
    )

    /**
     * 每次发送一份绑定：禁用期是发送前闸门（共性第 62 条），页面世代与会话世代进归属，账号由网络层在写出前核对（共性第 39 条）。网络层的各个等待点（10002 刷新屏障、10402 设备注册、写出前与写出后）
     * 都读同一个 stillOwned，所以离页只作废这里一处（评审 r1 S1）。
     */
    private fun bindingFor(
        account: String,
        sendEpoch: Long,
    ): SendGate {
        val probe = deps.gates.realname.probe()
        val ownership = CallOwnership(deps.api.epochs(), sendAllowed = probe::allowed) { !left && deps.env.sessionEpoch() == sendEpoch }
        activeOwnership = ownership
        return SendGate(RealNameCallBinding(ownership, account), probe)
    }

    /** 页面可见性（共性第 37、41 条）：实名完成后的出栈只在页面此刻可见时做，否则回到可见再做。 */
    private var visible = true
    private var deferredBack = false

    fun onVisibility(shown: Boolean) {
        visible = shown
        if (!shown || left || !deferredBack) return
        deferredBack = false
        onBack()
    }

    /** 实名成功：清掉已填的姓名与身份证号，按表单固定身份记已实名并复查 realname_status，出栈回发起页。 */
    private fun onSubmitted() {
        retained = null
        val owner = s.owner
        mutable.value = RealNameSnapshot(done = Notice(DONE_KEY, ++serial, assertive = false), owner = owner)
        deps.env.realnameSubmitted(owner)
        if (visible) onBack() else deferredBack = true
    }

    /** 同意记录或实名提交的失败结算。[keyApplies]：实名提交的结果才动保留的键（同意失败时键没用过）。 */
    private fun settleFailure(
        result: ApiResult,
        probe: SendProbe,
        keyApplies: Boolean,
    ) {
        val failure = result as? ApiResult.Failure
        val code = failure?.code
        val error = WalletErrors.of(result)
        if (error.kind == WalletError.RETRY_AFTER) {
            deps.gates.realname.start(error.disableMillis.takeIf { it > 0 } ?: SensitiveOutcome.DEFAULT_RETRY_MS)
        }
        if (error.kind == WalletError.FORCE_UPDATE) deps.env.forceUpdate()
        val reopenConsent = keyApplies && code == CONSENT_REQUIRED && failure.consentType() == CONSENT_ID_VERIFICATION
        if (keyApplies) retained = retained?.let { keyAfter(result, code, reopenConsent, it) }
        serial += 1
        if (reopenConsent) {
            // 10004 id_verification：重开单独同意（默认不勾选），用户同意后先记同意、再用原键重放；不自动重放
            mutable.value = s.copy(submitting = false, error = null, consentOpen = true, consentChecked = false)
            deps.env.sensitiveOverlay(true)
            return
        }
        val fields = if (keyApplies && code == FIELD_ERROR) failure.fieldNames() else emptyList()
        mutable.value =
            s.copy(
                submitting = false,
                // 带 fields 的 20001 由字段旁提示承担，页内错误条只给没有字段的错误（向量 e20001_field_*）
                error = noticeFor(error, code, probe).takeIf { fields.isEmpty() },
                fieldErrors = fields,
                fieldErrorId = if (fields.isEmpty()) s.fieldErrorId else serial,
                lockedToday = s.lockedToday || code == DAILY_LIMIT,
            )
    }

    /** 错误条：发送前被禁用期拦下出 42901；被作废的结果不显示；50301 回落 error.50001 + trace6（D10-887 ②）；其余按码。 */
    private fun noticeFor(
        error: WalletError,
        code: Int?,
        probe: SendProbe,
    ): Notice? {
        val gated = probe.gated(error)
        if (error.kind == WalletError.CANCELLED && !gated) return null
        val key =
            when {
                gated -> "error.42901"
                code == MAINTENANCE -> "error.50001"
                code == FIELD_ERROR -> "error.20001"
                code != null && DefaultTexts.text("error.$code") != null && code / 10_000 != 5 -> "error.$code"
                else -> error.textKey
            }
        return Notice(key, serial, error.traceId, error.showTrace6)
    }

    /** 请求体只认 id_no、name 两个字段；服务端若带了别的字段名，当作没有字段错误（走错误条，不让用户看不到反馈）。 */
    private fun ApiResult.Failure?.fieldNames(): List<String> =
        (this?.data?.get("fields") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.filter { it == FIELD_NAME || it == FIELD_ID }
            .orEmpty()

    private fun ApiResult.Failure?.consentType(): String? =
        this?.effect?.consentType ?: (this?.data?.get("consent_type") as? JsonPrimitive)?.content

    /** 会话换代：同步清空已填的姓名与身份证号。 */
    fun onSessionChanged() = onSessionChanged(deps.env.identitySnapshot())

    /** 会话换代，按调用方那一份快照 [identity] 重建表单身份（FU-AND-0717 第 3 轮：App 下发的那份，不另读一次）。 */
    fun onSessionChanged(identity: SessionIdentity) {
        if (left) return
        deferredBack = false
        retained = null
        if (s.consentOpen) deps.env.sensitiveOverlay(false)
        mutable.value = RealNameSnapshot(owner = FormOwner.of(identity))
    }

    /**
     * 统一的页面恢复入口（FU-AND-0717 第 3 轮）：[changed] 或已确认换代按这份快照清空重建；未知期取得的表单第一次确认按它接管表单身份——已填的姓名、
     * 身份证号、当日锁定与禁用期都保留，不重放提交；同一身份、未知不动。
     */
    fun onIdentity(
        identity: SessionIdentity,
        changed: Boolean = false,
    ) {
        if (left) return
        when (if (changed) IdentityVerdict.CHANGED else s.owner.verdict(identity)) {
            IdentityVerdict.CHANGED -> onSessionChanged(identity)
            IdentityVerdict.ADOPT -> mutable.value = s.copy(owner = FormOwner.of(identity))
            else -> Unit
        }
    }

    fun leave() {
        if (left) return
        // 先同步作废（stillOwned 立刻为 false，等待中的恢复链不再重放），再取消已交给传输层的请求
        left = true
        activeOwnership?.let(deps.api::cancelOwned)
        activeOwnership = null
        retained = null
        if (s.consentOpen) deps.env.sensitiveOverlay(false)
        mutable.value = RealNameSnapshot()
    }

    fun consumeDone(id: Long) {
        if (s.done?.id == id) mutable.value = s.copy(done = null)
    }

    companion object {
        const val FIELD_NAME = "name"
        const val FIELD_ID = "id_no"
        const val DONE_KEY = "realname.done"
        private const val FIELD_ERROR = 20001
        private const val DAILY_LIMIT = 30410
        private const val NAME_MAX = 64
        private const val BUSY_CODE = 40901
        private const val CONSENT_REQUIRED = 10004
        private const val MAINTENANCE = 50301
        private const val CONSENT_ID_VERIFICATION = "id_verification"

        /** 40901 后自动重发前的等待：发出时刻约 0、1、2.5、4.5 秒，连首发共 4 次、都在 6 秒内（D10-887 ⑤，三端一致）。 */
        private val BUSY_RESEND_DELAYS_MS = longArrayOf(1_000L, 1_500L, 2_000L)

        /** 补办后沿用原键的码（ErrorActionMapper 1xxxx 前置）与 40901。 */
        private val SAME_KEY_CODES = setOf(10001, 10005, BUSY_CODE)

        /**
         * 一次失败结果之后键的去留（null = 结束，下次用新键）：断连记下「出现过结果未知」并保留；没有响应外壳的（本地拒绝）没发出，保留；前置码、40901、10004 id_verification 保留；
         * 出现过结果未知时 10405 / 5xxxx / 42901 也保留（D10-1018）；其余码结束。
         */
        private fun keyAfter(
            result: ApiResult,
            code: Int?,
            reopenConsent: Boolean,
            held: RetainedSubmit,
        ): RetainedSubmit? {
            val next = if (result is ApiResult.TransportFailure) held.copy(unknown = true) else held
            if (code == null) return next
            val keep = reopenConsent || code in SAME_KEY_CODES || (next.unknown && keepsUnknownKey(code))
            return next.takeIf { keep }
        }

        private fun keepsUnknownKey(code: Int): Boolean = code == 10405 || code == 42901 || code / 10_000 == 5
    }
}

/** 保留的幂等键（只在内存）。[unknown]：这个键出现过结果未知（断连），之后 10405 / 5xxxx / 42901 也不结束键（D10-1018）。 */
private data class RetainedSubmit(
    val input: RealNameInput,
    val key: String,
    val unknown: Boolean,
)

/** 劳务协议页状态。 */
data class LaborSnapshot(
    val phase: String = LOADING,
    val version: String? = null,
    val text: String? = null,
    val sha256: String? = null,
    val checked: Boolean = false,
    val signing: Boolean = false,
    val error: Notice? = null,
    /** 这份协议内容所属的账号与登录世代（APP-09 共性第 52 条）：读取时绑定，【同意并签署】回调带上它。 */
    val owner: FormOwner = FormOwner(null, Long.MIN_VALUE),
) {
    companion object {
        const val LOADING = "loading"
        const val CONTENT = "content"
        const val ERROR = "error"
        const val UNAVAILABLE = "unavailable"
    }
}

/**
 * 劳务协议签署执行器（LaborAgreement 路由，只有 in_app；BR-WDR-31，FUND-05，G02）：全文可滚动、可被读屏完整读到；勾选默认不勾，勾选后
 * 【同意并签署】可用；签署成功后出栈回 Withdraw（不重放、由用户再点【提交】）。接口契约没有（G02）：只由 Debug 假服务实现（[LaborBackend]）；
 * Release 全文区显示「协议正文暂未提供」，【同意并签署】置灰，不发请求。sha256 取服务端返回的值原样回传。只在主线程使用。
 */
@Suppress("TooManyFunctions") // 页面每个入口一个函数（含统一的身份恢复入口）
class LaborAgreementController(
    private val deps: WalletDeps,
    private val scope: CoroutineScope,
    private val onBack: () -> Unit,
) {
    private var left = false
    private var serial = 0L
    private var loadSeq = 0L

    /**
     * 本页的固定身份（APP-09 共性第 65 条）：构造时一次性取得，只在会话换代通知里随新页面一起重建；协议读取与读到的内容都绑定它，【同意并签署】
     * 也只认它，不在读取时重新读取当前会话。
     */
    private var pageOwner = FormOwner.of(deps.env)

    private val mutable =
        MutableStateFlow(
            LaborSnapshot(
                phase =
                    if (deps.labor ==
                        null
                    ) {
                        LaborSnapshot.UNAVAILABLE
                    } else {
                        LaborSnapshot.LOADING
                    },
            ),
        )
    val state: StateFlow<LaborSnapshot> = mutable.asStateFlow()
    private val s: LaborSnapshot get() = mutable.value

    val signEnabled: Boolean get() = s.phase == LaborSnapshot.CONTENT && s.checked && !s.signing && !deps.gates.labor.active

    /**
     * 读取（进入、【重试】、会话换代后）。FU-AND-0717 第 3 轮：先取**一份**快照与本页固定身份比较——已确认换代不读（交给清空通知按新身份重读）；未知期
     * 取得的身份第一次确认按它接管；本页身份没建立（仍未知）：不读，进可重试的错误态（不再停在没有出口的加载中）；只是这次读不出（未知）而本页身份已知：照常
     * 按本页身份读。结果到达后只比较本进程登录世代与序号（账号变化在本进程一定伴随登录世代变化），不再另读账号。
     */
    @Suppress("ReturnCount") // 没有后端、已离页、身份三个出口
    fun load() {
        val backend = deps.labor ?: return
        if (left) return
        val now = deps.env.identitySnapshot()
        when (pageOwner.verdict(now)) {
            // 已确认换代（令牌已换、清空通知还没执行）：不读，内容不绑到别的账号；清空通知到达后按新身份重读
            IdentityVerdict.CHANGED -> return

            IdentityVerdict.ADOPT -> pageOwner = FormOwner.of(now)

            else -> Unit
        }
        val loadOwner = pageOwner
        val sendEpoch = loadOwner.epoch
        if (loadOwner.account == null) return identityHeld(loadOwner)
        // 读取请求序号（APP-09 共性第 19 条同类）：重试、会话换代各发一次时，只收最新一次
        val request = ++loadSeq
        mutable.value = s.copy(phase = LaborSnapshot.LOADING, error = null)
        scope.launch {
            val result = backend.current()
            if (left || loadSeq != request) return@launch
            // 仍是最新一次而本进程登录世代已变（换代还没确认）：不停在加载中
            if (deps.env.sessionEpoch() != sendEpoch) return@launch identityHeld(loadOwner)
            onLoaded(result, loadOwner)
        }
    }

    /** 协议读取的结果（已核对序号与本进程登录世代）：内容绑定读取时的身份 [loadOwner]。 */
    private fun onLoaded(
        result: ApiResult,
        loadOwner: FormOwner,
    ) {
        val data = (result as? ApiResult.Success)?.data as? JsonObject
        val version = data.text("version")
        val text = data.text("text")
        val sha = data.text("text_sha256")
        mutable.value =
            if (version != null && text != null && sha != null) {
                LaborSnapshot(LaborSnapshot.CONTENT, version, text, sha, owner = loadOwner)
            } else {
                // 读取错误统一处理（共性第 3 条）：42901 更新共享截止时间、10405 进强更、5xxxx 带 trace6
                val error = WalletErrors.of(result)
                if (error.kind == WalletError.RETRY_AFTER) deps.gates.read.start(error.disableMillis)
                if (error.kind == WalletError.FORCE_UPDATE) deps.env.forceUpdate()
                LaborSnapshot(
                    LaborSnapshot.ERROR,
                    error = Notice(error.textKey, ++serial, error.traceId, error.showTrace6),
                    owner = loadOwner,
                )
            }
    }

    /** 身份暂时确认不了：可重试的错误态（不发布协议内容）。 */
    private fun identityHeld(owner: FormOwner) {
        val error = WalletErrors.of(ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE))
        mutable.value = LaborSnapshot(LaborSnapshot.ERROR, error = Notice(error.textKey, ++serial, null, false), owner = owner)
    }

    /**
     * 统一的页面恢复入口（FU-AND-0717 第 3 轮）：[changed] 或已确认换代按这份快照清空重读；未知期取得的身份第一次确认按它接管，读取曾中止（错误态）的补读；
     * 同一身份而读取曾中止的补读；未知不动。
     */
    fun onIdentity(
        identity: SessionIdentity,
        changed: Boolean = false,
    ) {
        if (left) return
        when (if (changed) IdentityVerdict.CHANGED else pageOwner.verdict(identity)) {
            IdentityVerdict.CHANGED -> {
                onSessionChanged(identity)
            }

            IdentityVerdict.ADOPT -> {
                pageOwner = FormOwner.of(identity)
                if (s.phase == LaborSnapshot.ERROR) load()
            }

            IdentityVerdict.SAME -> {
                if (s.phase == LaborSnapshot.ERROR && s.owner == pageOwner) load()
            }

            IdentityVerdict.UNKNOWN -> {
                Unit
            }
        }
    }

    /** 勾选回调带渲染这份协议时的身份（共性第 59 条）：换代后到达的旧勾选不作用于新会话读到的协议。 */
    fun toggle(owner: FormOwner = s.owner) {
        if (owner != s.owner || !owner.current(deps.env)) return
        if (s.phase == LaborSnapshot.CONTENT && !s.signing) mutable.value = s.copy(checked = !s.checked)
    }

    /** [owner] 是渲染这份协议时的身份（共性第 52 条）：不是当前会话的（会话已换、清空通知还没执行）不签。 */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // 逐项提前返回；发送前核对与结果分支
    fun sign(owner: FormOwner = s.owner) {
        val backend = deps.labor ?: return
        if (!signEnabled || left) return
        if (owner != s.owner || !owner.current(deps.env)) return
        val version = s.version ?: return
        val sha = s.sha256 ?: return
        // 固定表单身份的账号与会话世代（共性第 39、52 条）：发送前再核对，会话已换的不发
        val account = owner.account ?: return
        val sendEpoch = owner.epoch
        mutable.value = s.copy(signing = true, error = null)
        scope.launch {
            // 一份快照核对（FU-AND-0717 第 3 轮）：会话已换或身份暂时确认不了都不发
            if (!deps.env.ownedBy(account, sendEpoch)) {
                if (!left) mutable.value = s.copy(signing = false)
                return@launch
            }
            val result = backend.sign(version, sha)
            if (left || deps.env.sessionEpoch() != sendEpoch) return@launch
            if (result is ApiResult.Success) {
                mutable.value = s.copy(signing = false)
                // 出栈回 Withdraw 是导航：页面此刻不可见时留到回到可见再做（共性第 37、41 条）
                if (visible) onBack() else deferredBack = true
                return@launch
            }
            onSignFailed(result)
        }
    }

    private fun onSignFailed(result: ApiResult) {
        val error = WalletErrors.of(result)
        if (error.kind == WalletError.RETRY_AFTER) {
            deps.gates.labor.start(error.disableMillis.takeIf { it > 0 } ?: SensitiveOutcome.DEFAULT_RETRY_MS)
        }
        if (error.kind == WalletError.FORCE_UPDATE) deps.env.forceUpdate()
        mutable.value = s.copy(signing = false, error = Notice(error.textKey, ++serial, error.traceId, error.showTrace6))
    }

    private var visible = true
    private var deferredBack = false

    /** 页面可见性（共性第 37、41 条）。 */
    fun onVisibility(shown: Boolean) {
        visible = shown
        if (!shown || left || !deferredBack) return
        deferredBack = false
        onBack()
    }

    fun onSessionChanged() = onSessionChanged(deps.env.identitySnapshot())

    /** 会话换代，按调用方那一份快照 [identity] 重建页面身份（FU-AND-0717 第 3 轮：App 下发的那份，不另读一次）。 */
    fun onSessionChanged(identity: SessionIdentity) {
        if (left) return
        deferredBack = false
        pageOwner = FormOwner.of(identity)
        mutable.value = LaborSnapshot(phase = if (deps.labor == null) LaborSnapshot.UNAVAILABLE else LaborSnapshot.LOADING)
        load()
    }

    fun leave() {
        left = true
        loadSeq += 1
    }

    private fun JsonObject?.text(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
