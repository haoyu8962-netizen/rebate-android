// 错误码是契约取值（contracts/error-codes.json），按码直接写
@file:Suppress("MagicNumber")

package com.couli.feature.wallet

import com.couli.core.network.ApiResult
import com.couli.core.network.IdentityVerdict
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.SessionIdentity
import com.couli.core.wallet.RealNameForm
import com.couli.core.wallet.SensitiveOutcome
import com.couli.core.wallet.WalletError
import com.couli.core.wallet.WalletErrors
import com.couli.generated.DefaultTexts
import kotlinx.coroutines.CoroutineScope
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
 * 实名认证执行器（RealName 路由；BR-ID-25，G01）：姓名去首尾空白；身份证号只收数字与 X，18 位，小写 x 转大写，不做校验位计算。提交前先弹单独同意
 * （默认不勾选，点遮罩不关闭）。实名提交接口契约没有（G01）：只由 Debug 假服务实现（[RealNameBackend]）；Release 为 null，【提交认证】置灰，显示
 * 「实名认证暂未开放」，不发任何请求。成功后刷新 realname_status、出栈回发起页（G05，不重放）。只在主线程使用。
 */
@Suppress("TooManyFunctions") // 页面每个入口一个函数（含可见性）
class RealNameController(
    private val deps: WalletDeps,
    private val scope: CoroutineScope,
    private val onBack: () -> Unit,
) {
    private var left = false
    private var serial = 0L

    // 表单身份在构造时一次性取得（APP-09 共性第 65 条）；之后只比较、不再读取当前会话来赋值
    private val mutable = MutableStateFlow(RealNameSnapshot(owner = FormOwner.of(deps.env)))
    val state: StateFlow<RealNameSnapshot> = mutable.asStateFlow()
    private val s: RealNameSnapshot get() = mutable.value

    /** Release：契约补上实名接口之前不开放（G01）。 */
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
    }

    fun onIdChange(
        text: String,
        owner: FormOwner = s.owner,
    ) {
        if (left || !owns(owner)) return
        mutable.value = s.copy(idNo = RealNameForm.acceptIdNo(s.idNo, text), fieldErrors = s.fieldErrors - FIELD_ID, error = null)
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

    /** 单独同意里勾选后点【同意并继续】：同意记录与实名提交在 Debug 假服务里一起发生（Release 不调 /v1/consents）。 */
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
            val result = backend.submit(input.name, input.idNo, deps.newKey())
            if (left || deps.env.sessionEpoch() != sendEpoch) return@launch
            onResult(result)
        }
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

    private fun onResult(result: ApiResult) {
        if (result is ApiResult.Success) {
            // 实名完成：清掉已填的姓名与身份证号，刷新 realname_status，出栈回发起页
            mutable.value = RealNameSnapshot(done = Notice(DONE_KEY, ++serial, assertive = false), owner = s.owner)
            deps.env.realnameChanged()
            if (visible) onBack() else deferredBack = true
            return
        }
        val failure = result as? ApiResult.Failure
        val code = failure?.code
        val error = WalletErrors.of(result)
        if (error.kind ==
            WalletError.RETRY_AFTER
        ) {
            deps.gates.realname.start(error.disableMillis.takeIf { it > 0 } ?: SensitiveOutcome.DEFAULT_RETRY_MS)
        }
        if (error.kind == WalletError.FORCE_UPDATE) deps.env.forceUpdate()
        val fields =
            if (code == FIELD_ERROR) {
                (failure.data["fields"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            } else {
                emptyList()
            }
        val key =
            when {
                code == FIELD_ERROR -> "error.20001"
                code != null && DefaultTexts.text("error.$code") != null && code / 10_000 != 5 -> "error.$code"
                else -> error.textKey
            }
        mutable.value =
            s.copy(
                submitting = false,
                error = Notice(key, ++serial, error.traceId, error.showTrace6),
                fieldErrors = fields,
                fieldErrorId = if (fields.isEmpty()) s.fieldErrorId else serial,
                lockedToday = s.lockedToday || code == DAILY_LIMIT,
            )
    }

    /** 会话换代：同步清空已填的姓名与身份证号。 */
    fun onSessionChanged() = onSessionChanged(deps.env.identitySnapshot())

    /** 会话换代，按调用方那一份快照 [identity] 重建表单身份（FU-AND-0717 第 3 轮：App 下发的那份，不另读一次）。 */
    fun onSessionChanged(identity: SessionIdentity) {
        if (left) return
        deferredBack = false
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
        left = true
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
    }
}

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
