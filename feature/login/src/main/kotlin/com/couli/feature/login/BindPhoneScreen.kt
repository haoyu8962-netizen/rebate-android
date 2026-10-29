package com.couli.feature.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.couliCard
import com.couli.core.network.ResultSignals
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** UI 测试与冒烟用的标记。 */
object BindPhoneTags {
    const val PAGE = "bind_phone_page"
    const val BACK = "bind_phone_back"
    const val PHONE = "bind_phone_phone"
    const val PHONE_CLEAR = "bind_phone_phone_clear"
    const val PHONE_ERROR = "bind_phone_phone_error"
    const val CODE = "bind_phone_code"
    const val CODE_ERROR = "bind_phone_code_error"
    const val GET_CODE = "bind_phone_get_code"
    const val SUBMIT = "bind_phone_submit"
    const val MERGE_OK = "bind_phone_merge_ok"
    const val MERGE_CANCEL = "bind_phone_merge_cancel"
    const val GUIDE_GO = "bind_phone_guide_go"
    const val GUIDE_SKIP = "bind_phone_guide_skip"
}

/** 绑手机页的出口（App 给：返回、客服、绑定完成）。 */
class BindPhonePageActions(
    /** 返回（没有绑定就离开：App 作废在途请求、按「未绑定离开」恢复暂缓的待办）。 */
    val onBack: () -> Unit = {},
    /** 风控弹窗【联系客服申诉】（统一客服出口）。 */
    val onCustomerService: () -> Unit = {},
    /** 经 Router 打开页面（10007 stage=cooling → DeleteAccount，APP-05b 共性第 10 条）。 */
    val onOpenRoute: (route: String) -> Unit = {},
    /** 绑定成功、原页面许可仍有效（APP-13a R1-05）：App 只出栈这一个绑手机页，再完成前置、恢复待办。 */
    val onBound: () -> Unit = {},
    /**
     * 页面重新有效（回到前台、覆盖页出栈）时共享状态已是已绑手机（FU-AND-0716，iOS R1-08）：进页补查在页面被覆盖 / 进后台期间查明已绑、
     * 那次没有导航的，App 只出栈这一个绑手机页（它须是栈顶），按 resumeOnly 恢复待办（不算完成前置、不重置计时）。返回是否已退出本页
     * （不是那种情况——如已绑账号经 10005 引导或待确认卡进来——返回 false，页面照常）。
     */
    val onAlreadyBound: () -> Boolean = { false },
)

/**
 * 绑手机页配置。[providerName]：当前第三方登录方式的名称（并号确认正文 error.30411.mergeable 的 {provider_name}；不知道时用微信，
 * Android 只有微信）。
 */
class BindPhonePageConfig(
    val service: BindPhoneService,
    val texts: LoginTexts,
    val providerName: String,
    val clock: () -> Long = System::currentTimeMillis,
    val actions: BindPhonePageActions = BindPhonePageActions(),
)

/** 绑手机页上的弹窗。 */
enum class BindPhoneDialog {
    /** BindPhoneMerge：30411 mergeable。 */
    MERGE,

    /** 44001：风控拦截（BR-TEXT-14 表 A：给申诉入口）。 */
    RISK,
}

/**
 * 绑手机页的一个表单实例（APP-13a R1-01、R1-02、R2-01，共性第 6、16、36 条）：页面实例号 + 构造时一次取得的完整固定身份（账号、登录世代、作用域、
 * 隐私 / 环境世代）。渲染时作为普通值被回调捕获，执行时只与 ViewModel 当前的实例比较；会话换代换新实例（表单子树按实例号重建），旧回调一律不受理。
 */
class BindPhoneBinding internal constructor(
    val instance: Long,
    val owner: BindPhoneOwner,
)

/**
 * 绑手机页的界面状态。手机号与验证码只在绑定固定身份的内存里（[binding] 的这一份状态），不进 SavedStateHandle 等系统保存状态（APP-13a R1-01、共性第 29 条）：
 * 换代或进程恢复后清空，恢复页面只展示未决记录里的脱敏摘要（未决原体只在 SecurePendingKeyBackend）。
 */
data class BindPhoneUiState(
    /** 这份状态所属的表单实例（渲染时取出、回调捕获）。 */
    val binding: BindPhoneBinding,
    val phone: String = "",
    val code: String = "",
    val phoneError: String? = null,
    val codeError: String? = null,
    /** 字段错误的序号：每次新的字段错误加 1（读屏即时播报一次、焦点移到错误提示，APP-05b 共性第 6 条）。 */
    val errorSeq: Int = 0,
    val sending: Boolean = false,
    val submitting: Boolean = false,
    val resendSec: Int = 0,
    val sendLimitedSec: Int = 0,
    val bindLimitedSec: Int = 0,
    val dialog: BindPhoneDialog? = null,
    val mergeMasked: String = "",
    val toast: String? = null,
    val toastId: Int = 0,
    /** 读屏播报（发码成功播报一次脱敏的发送对象；倒计时结束、可重发时播报一次；APP-05b 共性第 11 条）。 */
    val announce: String? = null,
    val announceId: Int = 0,
    /** APP-13a G07：结果未知的上一次提交（待确认卡）；null = 没有。 */
    val pendingKey: String? = null,
    val pendingMasked: String = "",
    val pendingCreatedAt: Long = 0L,
    val pendingBusy: Boolean = false,
    val pendingNotice: String? = null,
    /**
     * 待确认的确认 / 放弃返回前置码（10001 / 10004 / 10005）而原页面许可已失效时保留的补办入口（R2-07，共性第 41 条）：属于这个表单实例，点击经 Router 打开；
     * 补办回来记录仍是待确认，不自动提交。
     */
    val pendingGuide: String? = null,
    /** 共享 phoneGate（bindPhone 的提交、确认、放弃共用的 42901 截止时间）未到期：【绑定】与待确认的确认、放弃、二次确认都置灰（R1-06、共性第 8 条）。 */
    val pendingLimited: Boolean = false,
    /** 放弃二次确认框的一次性编号（0 = 没有）。 */
    val abandonDialog: Int = 0,
    /** 未决记录读不出（安全存储故障）：【绑定】置灰（lessons 38）。 */
    val pendingUnreadable: Boolean = false,
    /**
     * 写请求结果的页面结算序号：发码、绑定、确认、放弃的等待结束、页面按许可落地或丢弃之后加 1（不论结果、不论实例是否已换代）。
     * 不参与渲染；集成测试据此等「流程已交回结论」（F-AND-36，FU-AND-0716 R1-02），不靠网络层空闲推测。
     */
    val resultSeq: Int = 0,
)

/** 绑手机页的状态机（BindPhone / BindPhoneMerge；APP-05b）。网络与会话都经 [BindPhoneService]。 */
@Suppress("TooManyFunctions")
class BindPhoneViewModel(
    initial: BindPhonePageConfig,
    saved: SavedStateHandle,
) : ViewModel() {
    private var config = initial

    /** 此刻（待确认卡「提交时间」按 BR-TEXT-11 相对现在格式化）。 */
    internal fun now(): Long = config.clock()

    private val service: BindPhoneService get() = config.service
    private val texts: LoginTexts get() = config.texts

    /** 页面级绑定限流（Retry-After）：按表单实例的会话归属隔离，会话换代时换新（FU-AND-0716，iOS R1-09 / APP-05b 共性第 23 条）。 */
    private var bindLimit = LoginRateLimits()

    private var instances = 0L

    /** 当前表单实例：构造时一次取得固定身份；会话换代后换新实例、清空输入与倒计时（共性第 6、16 条）。 */
    private var binding = BindPhoneBinding(++instances, initial.service.owner())

    private val mutableState = MutableStateFlow(BindPhoneUiState(binding = binding))
    val state: StateFlow<BindPhoneUiState> = mutableState.asStateFlow()

    private var dialogIds = 0
    private var pendingBusyLocal = false

    /** 页面可见性与可见世代（页面许可，共性第 11、23 条）：异步结果导航前核对仍可见、且发起后没有被覆盖或进后台。 */
    private var visible = false
    private var viewGen = 0L

    /** 共性第 29 条（FU-AND-0716 第 2 轮）：验证码修订号，输入框里的验证码每变一次加 1；提交时捕获，迟到的验证码字段错误只在未变时显示。 */
    private var codeRevision = 0L
    private var cleared = false

    /** 按固定身份订阅的共享未决通知（R1-04）；换代时换订阅，页面清除时随 viewModelScope 取消。 */
    private var pendingWatch: Job? = null

    /** 会话同步出口的订阅（R2-01）：真实会话、作用域、隐私、环境切换时在同一条主线程消息里换代；页面清除时取消。 */
    private val sessionWatch: () -> Unit = initial.service.addSessionListener { syncOwner() }

    init {
        // R1-01：手机号、验证码不进系统保存状态；清掉旧版本可能留下的原文
        saved.remove<String>(KEY_PHONE)
        saved.remove<String>(KEY_CODE)
        viewModelScope.launch {
            while (isActive) {
                syncOwner()
                tick()
                delay(TICK_MILLIS)
            }
        }
        // R1-06：订阅共享 phoneGate，限流期间待确认按钮与【绑定】置灰，截止时间到期自动恢复
        service.shared?.phoneGate?.let { gate ->
            viewModelScope.launch { gate.blocked.collect { blocked -> mutableState.update { it.copy(pendingLimited = blocked) } } }
        }
        watchPending(binding)
    }

    override fun onCleared() {
        cleared = true
        doneWhileHidden = null
        pendingWatch = null
        sessionWatch()
    }

    fun bind(latest: BindPhonePageConfig) {
        config = latest
    }

    /** 渲染时捕获的实例仍是本页当前实例、页面没被清除、固定身份仍是当前会话（只比较，不赋值）。 */
    private fun live(b: BindPhoneBinding): Boolean = !cleared && b === binding && service.owns(b.owner)

    /**
     * 隐藏期间绑定成功的延后完成记录（FU-AND-0716，APP-05b 共性第 25、26 条）：发起时的表单实例（含固定身份）。只在同一实例、身份仍是当前会话、
     * 共享状态已绑时消费；会话换代（[syncOwner] 换实例）与页面清除时作废。
     */
    private var doneWhileHidden: BindPhoneBinding? = null

    /**
     * 绑定（或确认上一次提交）成功后的页面收尾。原页面许可有效 → 出栈完成；许可已失效（发起后被覆盖 / 进后台）但原实例此刻已重新可见
     * （「页面先恢复、成功后到」）→ 按原实例 + 固定身份 + 共享已绑状态立即完成一次；页面仍不可见（「成功先到」）→ 记下延后完成，
     * 页面重新有效时消费（共性第 25 条）。共享结算（守卫已绑、释放暂缓、getMe）此前已在服务里做完。
     */
    private fun completeBound(
        b: BindPhoneBinding,
        permit: Long,
    ) {
        when {
            navigable(b, permit) -> config.actions.onBound()
            !live(b) -> Unit
            visible && service.knownBound() -> config.actions.onBound()
            else -> doneWhileHidden = b
        }
    }

    /** 页面许可（导航、打开别的页面）：[live] 且页面可见、发起后没有被覆盖或进后台（[permit] 是发起时的可见世代）。 */
    private fun navigable(
        b: BindPhoneBinding,
        permit: Long,
    ): Boolean = live(b) && visible && viewGen == permit

    /** 手机号输入：原样保存（不先删字符，APP-05a 共性第 15 条）；换号作废在途请求的页面结果与并号凭据。 */
    fun onPhoneChange(
        raw: String,
        b: BindPhoneBinding = binding,
    ) {
        if (!live(b)) return
        if (PhoneNumber.normalize(raw).normalized != PhoneNumber.normalize(mutableState.value.phone).normalized) service.invalidate()
        mutableState.update { it.copy(phone = raw, phoneError = null) }
        // 共性第 11、17 条：换号、清空只按新号码重算倒计时，不触发结束播报；切回仍在倒计时的原号码时重新跟踪它真实的截止时间
        tick(announceEnd = false)
    }

    fun onClearPhone(b: BindPhoneBinding = binding) = onPhoneChange("", b)

    fun onCodeChange(
        raw: String,
        b: BindPhoneBinding = binding,
    ) {
        if (!live(b)) return
        val code = CodeInput.sanitize(raw)
        if (code != mutableState.value.code) codeRevision += 1
        mutableState.update { it.copy(code = code, codeError = null) }
    }

    fun onPageVisible() {
        if (!visible) viewGen += 1
        visible = true
        syncOwner()
        // 共性第 25、26 条：隐藏期间本页绑定成功（成功先到）→ 回来时核对原实例与固定身份、共享已绑，完成一次出栈
        val done = doneWhileHidden
        doneWhileHidden = null
        val doneHere = done != null && done === binding && live(done)
        if (doneHere && service.knownBound()) {
            config.actions.onBound()
            return
        }
        // FU-AND-0716（iOS R1-08）：每次页面重新有效先看共享已绑状态——进页补查在页面被覆盖 / 进后台期间查明已绑时，那次结果不导航，
        // 回来由这里只退出当前这一个页面并恢复待办
        if (live(binding) && service.knownBound() && config.actions.onAlreadyBound()) return
        tick()
        refreshPending()
    }

    /** 页面被覆盖、进后台：可见世代推进，之前发起的请求的结果不再导航（共性第 23 条）。 */
    fun onPageHidden() {
        if (visible) viewGen += 1
        visible = false
    }

    /**
     * 会话换代（另一账号登录、退出后重新登录、同账号刷新改变作用域、撤回同意、切换环境）：作废在途结果的页面落地、清空输入、倒计时与待确认卡，换新的表单实例（按新身份
     * 重建，共性第 6、36 条）。旧回调捕获的旧实例之后一律不受理。由会话同步出口同步调用（R2-01）；回到前台与计时检查只作兜底。
     */
    private fun syncOwner() {
        val now = service.owner()
        if (now == binding.owner || cleared) return
        service.invalidate()
        binding = BindPhoneBinding(++instances, now)
        bindLimit = LoginRateLimits()
        doneWhileHidden = null
        pendingBusyLocal = false
        watched = null
        val previous = mutableState.value
        mutableState.value =
            BindPhoneUiState(
                binding = binding,
                toastId = previous.toastId,
                announceId = previous.announceId,
                resultSeq = previous.resultSeq,
                errorSeq = previous.errorSeq,
                pendingLimited = service.shared?.phoneGate?.active == true,
            )
        watchPending(binding)
    }

    /** R1-04：按固定身份订阅共享未决通知；同账号的 bindPhone 结算（别处提交超时变 unknown、确认、放弃）后从存储刷新待确认卡。 */
    private fun watchPending(b: BindPhoneBinding) {
        pendingWatch?.cancel()
        val shared = service.shared ?: return
        val account = b.owner.account ?: return
        pendingWatch =
            viewModelScope.launch {
                shared.pendingSignals.collect { settlement ->
                    if (settlement.operationId == BindPhoneAction.BIND_OPERATION && settlement.account == account &&
                        live(b)
                    ) {
                        refreshPending()
                    }
                }
            }
    }

    /** 按本账号 bindPhone 的未决记录恢复待确认卡（进入、回前台、登录成功、共享通知；G07）。 */
    @Suppress("ReturnCount") // 没有账号、不是本会话、读不出三个出口
    fun refreshPending() {
        val b = binding
        val account = b.owner.account ?: return
        if (!live(b)) return
        val record =
            try {
                service.pendingCard(account)
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception,
            ) {
                mutableState.update { it.copy(pendingUnreadable = true) }
                return
            }
        mutableState.update {
            if (record == null) {
                it.copy(
                    pendingKey = null,
                    pendingNotice = null,
                    pendingGuide = null,
                    abandonDialog = 0,
                    pendingUnreadable = false,
                    pendingBusy = false,
                )
            } else {
                it.copy(
                    pendingKey = record.key,
                    pendingMasked = record.summary,
                    pendingCreatedAt = record.createdAtMillis,
                    pendingBusy = record.stateRaw == com.couli.core.network.PendingKeyStore.IN_FLIGHT || pendingBusyLocal,
                    pendingUnreadable = false,
                )
            }
        }
    }

    private fun gateActive(): Boolean = service.shared?.phoneGate?.active == true

    /** 一次写请求的页面结算：[block] 结束（落地、丢弃或被取消）后推进 [BindPhoneUiState.resultSeq]。 */
    private suspend fun settling(block: suspend () -> Unit) {
        try {
            block()
        } finally {
            mutableState.update { it.copy(resultSeq = it.resultSeq + 1) }
        }
    }

    /** 【确认上一次提交】（渲染时的键与表单实例）。 */
    fun onConfirmPending(
        key: String,
        b: BindPhoneBinding,
    ) = resolve(key, b, abandon = false)

    /** 【放弃上一次提交】：先弹二次确认（一次性编号）；限流期间不弹（R1-06）。 */
    fun onAskAbandon(
        key: String,
        b: BindPhoneBinding,
    ) {
        val current = mutableState.value
        if (!live(b) || current.pendingKey != key || current.pendingBusy) return
        if (gateActive()) {
            mutableState.update { it.copy(pendingNotice = accountText(RETRY_LATER_KEY)) }
            return
        }
        mutableState.update { it.copy(abandonDialog = ++dialogIds) }
    }

    fun onCancelAbandon(dialog: Int) {
        if (mutableState.value.abandonDialog == dialog) mutableState.update { it.copy(abandonDialog = 0) }
    }

    /** 【确定放弃】：作废接口（action=phone_change）。 */
    fun onAbandon(
        dialog: Int,
        b: BindPhoneBinding,
    ) {
        val current = mutableState.value
        if (current.abandonDialog != dialog) return
        mutableState.update { it.copy(abandonDialog = 0) }
        current.pendingKey?.let { resolve(it, b, abandon = true) }
    }

    private fun resolve(
        key: String,
        b: BindPhoneBinding,
        abandon: Boolean,
    ) {
        val current = mutableState.value
        if (!live(b) || current.pendingKey != key || current.pendingBusy) return
        if (gateActive()) {
            mutableState.update { it.copy(pendingNotice = accountText(RETRY_LATER_KEY)) }
            return
        }
        val permit = viewGen
        pendingBusyLocal = true
        mutableState.update { it.copy(pendingBusy = true, pendingNotice = null, pendingGuide = null) }
        // 结果与共享结算在会话级任务里（离页不截断，R1-03、共性第 21 条）；页面只在原实例仍有效时落地，导航另核对页面许可（共性第 23 条）
        viewModelScope.launch {
            settling {
                val outcome = service.resolvePending(key, b.owner, abandon)
                if (!live(b)) return@settling
                pendingBusyLocal = false
                mutableState.update { it.copy(pendingBusy = false) }
                onResolved(outcome, b, permit)
            }
        }
    }

    private fun onResolved(
        outcome: com.couli.core.me.account.PhoneChangeOutcome?,
        b: BindPhoneBinding,
        permit: Long,
    ) {
        refreshPending()
        if (outcome == null) {
            // FU-AND-0716 R1-03（共性第 22 条）：「没有发出」的提示同样只在发起时的页面许可仍有效时落地；记录保持待确认（上面已按共享状态刷新）
            if (navigable(b, permit)) mutableState.update { it.copy(pendingNotice = accountText(RETRY_LATER_KEY)) }
            return
        }
        when (outcome.nextStateRaw) {
            com.couli.core.me.account.PhoneChangeOutcome.DONE -> {
                if (outcome.alreadyDone && navigable(b, permit)) toast(accountText(com.couli.core.me.account.AccountRaw.ALREADY_DONE_KEY))
                // R1-05：出栈与恢复待办只在原页面许可仍有效时做（只出栈这一个页面，不按路由名找）；失效时见 [completeBound]
                completeBound(b, permit)
            }

            com.couli.core.me.account.PhoneChangeOutcome.STEP1 -> {
                // 已放弃 / 20903：清空验证码，可以重新绑定（页面更新与提示只在发起时的页面许可仍有效时落地，FU-AND-0716 ④）
                if (navigable(b, permit)) {
                    mutableState.update { it.copy(code = "") }
                    toast(accountText(outcome.textKey ?: com.couli.core.me.account.AccountRaw.ABANDONED_KEY))
                }
            }

            com.couli.core.me.account.PhoneChangeOutcome.STEP2 -> {
                onResolvedEnded(outcome, navigable(b, permit))
            }

            else -> {
                // R2-08：待确认提示用统一错误格式化（正文 + 独立附加的 trace6），不只读 textKey
                // FU-AND-0716 R1-03（共性第 22 条）：42901、5xxxx、无法确认等非终结结果的提示只在发起时的页面许可仍有效时落地；失效时只做共享结算
                // （未决记录、phoneGate 截止时间已在服务里记下），补办入口照 R2-07 保留
                if (navigable(b, permit)) {
                    mutableState.update { it.copy(pendingNotice = outcome.textKey?.let { key -> pendingMessage(key, outcome.trace6) }) }
                }
                outcome.guideRoute?.let { route -> guidePending(route, b, permit) }
            }
        }
    }

    /**
     * 待确认的确认 / 放弃以键结束的业务错误收尾（30411 等）：mergeable 沿用 APP-05b 并号确认，其余提示原错误。[permitted]：发起时的页面许可
     * 仍有效；失效时丢掉并号凭据、不留着等页面回来补弹（FU-AND-0716 ④，iOS R1-04）。
     */
    private fun onResolvedEnded(
        outcome: com.couli.core.me.account.PhoneChangeOutcome,
        permitted: Boolean,
    ) {
        when {
            outcome.reason == "mergeable" && permitted -> mutableState.update { it.copy(dialog = BindPhoneDialog.MERGE) }
            outcome.reason == "mergeable" -> service.decideMerge(MergeDecision.CANCEL)
            permitted -> toast(accountText(outcome.errorKey ?: outcome.textKey ?: LoginService.GENERIC_KEY))
            else -> Unit
        }
    }

    /**
     * R2-07：待确认的确认 / 放弃得到前置码（10001 → Login、10004 → 协议等）的补办引导：原页面许可有效经 Router 打开；否则保留绑定本实例的手动入口。
     * 补办回来记录仍是待确认，不自动提交。
     */
    private fun guidePending(
        route: String,
        b: BindPhoneBinding,
        permit: Long,
    ) {
        if (navigable(b, permit)) {
            config.actions.onOpenRoute(route)
        } else {
            mutableState.update { it.copy(pendingGuide = route) }
        }
    }

    /** 待确认卡的补办入口（R2-07；渲染时的表单实例）：经 Router 打开，记录保持待确认。 */
    fun onPendingGuide(b: BindPhoneBinding) {
        if (!live(b)) return
        val route = mutableState.value.pendingGuide ?: return
        mutableState.update { it.copy(pendingGuide = null) }
        config.actions.onOpenRoute(route)
    }

    /** 待确认提示的统一格式化（R2-08，与轻提示同一函数）：文案（{trace6} 占位符填上）+ 独立附加的 trace_id 末 6 位；没有 trace 原样。 */
    private fun pendingMessage(
        key: String,
        trace6: String?,
    ): String {
        val trace = trace6.orEmpty()
        val drafts = com.couli.core.me.account.AccountDraftTexts
        val text = if (drafts.has(key)) drafts.format(key, mapOf("trace6" to trace)) else texts.format(key, mapOf("trace6" to trace))
        return ResultSignals.withTrace6(text, trace)
    }

    private fun accountText(key: String): String =
        com.couli.core.me.account.AccountDraftTexts
            .text(key)
            .ifEmpty { texts.text(key) }

    fun onGetCode(b: BindPhoneBinding = binding) {
        if (!live(b) || service.busy) return
        val phone = mutableState.value.phone
        val permit = viewGen
        mutableState.update { it.copy(sending = true) }
        viewModelScope.launch {
            settling {
                val outcome = service.sendCode(phone)
                if (!live(b)) return@settling
                mutableState.update { it.copy(sending = false) }
                onSent(outcome, b, permit)
            }
        }
    }

    private fun onSent(
        outcome: SendCodeOutcome,
        b: BindPhoneBinding,
        permit: Long,
    ) {
        tick()
        // FU-AND-0716 ④（iOS R1-04、APP-13a 共性第 23 条）：播报、弹窗、提示与字段错误只在发起时的页面许可仍有效时落地；
        // 失效时只留共享结算（倒计时、限流已在服务里记下），不存着等页面回来补弹
        if (!navigable(b, permit)) return
        when (outcome.stateRaw) {
            SmsCountdown.STATE_SENT -> {
                // 共性第 11 条：发码成功播报一次脱敏的发送对象（换号、离页后的旧结果是 stale，不播报）
                outcome.phone?.let { announce(texts.format("login.code.sent_to", mapOf("phone" to PhoneNumber.masked(it)))) }
            }

            LoginService.STATE_COUNTDOWN, BindOutcome.STALE, BindOutcome.BUSY -> {
                Unit
            }

            // 共性第 10、16 条：发码收到 10007 stage=cooling → 经 Router 打开注销进度页（不弹提示）；页面许可已在上面核对
            BindOutcome.OPEN_ROUTE -> {
                outcome.route?.let(config.actions.onOpenRoute)
            }

            SmsCountdown.STATE_RATE_LIMITED -> {
                toast(text(outcome.textKey))
            }

            else -> {
                // 字段错误是输入框旁的提示（不附追踪编号）；其余轻提示正文与追踪编号统一生成
                when {
                    outcome.textKey == RISK_KEY -> mutableState.update { it.copy(dialog = BindPhoneDialog.RISK) }
                    outcome.field == LoginErrorMapper.FIELD_PHONE -> fieldError(phone = text(outcome.textKey))
                    else -> toast(formatted(outcome.textKey, outcome.trace6))
                }
            }
        }
    }

    fun onSubmit(b: BindPhoneBinding = binding) {
        val current = mutableState.value
        if (!live(b) || current.pendingKey != null || current.pendingUnreadable) return
        if (service.busy || bindLimit.loginBlocked(config.clock()) || gateActive()) return
        val permit = viewGen
        val revision = codeRevision
        mutableState.update { it.copy(submitting = true, codeError = null, phoneError = null) }
        viewModelScope.launch {
            settling {
                // 只用渲染时实例的固定身份发出（R1-02）；发送与共享结算在会话级任务里（R1-03）
                val outcome = service.bind(current.phone, current.code, b.owner)
                if (!live(b)) return@settling
                mutableState.update { it.copy(submitting = false) }
                onBound(outcome, b, permit, revision, current.code, current.phone)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongParameterList") // 结局逐项落地；发起时捕获的许可、修订号与输入逐项交回
    private fun onBound(
        outcome: BindOutcome,
        b: BindPhoneBinding,
        permit: Long,
        revision: Long = codeRevision,
        /** 发起时输入框里的码；与 [BindOutcome.sentCode] 不一致（held 重发了原提交的码）时验证码反馈不落到输入框（共性第 35 条）。 */
        typed: String? = null,
        /** 发起时输入框里的号码（F-X-15）：与 [BindOutcome.sentPhone] 规范化后不一致（held 重发了原提交的号码）时号码 / 验证码类反馈不落地。 */
        typedPhone: String? = null,
    ) {
        // F-X-15（FU-AND-0717）：held 重发原请求体时，号码 / 验证码类反馈按实际发出的号码归属；改了号码的不落到当前表单（输入保留、共享结算照常），
        // 并号凭据一并作废。非字段结局（5xxxx、42901 等）照常提示（APP-05b 共性 36）
        val foreign = typedPhone != null && outcome.sentPhone != null && outcome.sentPhone != PhoneNumber.normalize(typedPhone).normalized
        if (foreign && fieldClass(outcome)) {
            if (outcome.actionRaw == BindPhoneAction.MERGE_CONFIRM) service.decideMerge(MergeDecision.CANCEL)
            return
        }
        when (outcome.actionRaw) {
            // R1-05：绑定成功的出栈与恢复待办只在原页面许可仍有效时做
            BindPhoneAction.DONE -> {
                completeBound(b, permit)
            }

            BindOutcome.STALE, BindOutcome.BUSY, BindOutcome.CODE_INCOMPLETE -> {
                Unit
            }

            // G07：结果未知 → 待确认卡（不换键、不自动重发）
            BindOutcome.PENDING -> {
                refreshPending()
            }

            BindOutcome.OPEN_ROUTE -> {
                if (navigable(b, permit)) outcome.route?.let(config.actions.onOpenRoute)
            }

            // FU-AND-0716 ④（iOS R1-04）：并号框只在发起时的页面许可仍有效时弹；失效（进后台、被覆盖）时丢掉这次的并号凭据，
            // 不留着等页面回来补弹已失效请求的并号框
            BindPhoneAction.MERGE_CONFIRM -> {
                if (navigable(b, permit)) {
                    mutableState.update { it.copy(dialog = BindPhoneDialog.MERGE, mergeMasked = outcome.maskedPhone) }
                } else {
                    service.decideMerge(MergeDecision.CANCEL)
                }
            }

            BindPhoneAction.RETRY_AFTER -> {
                // 限流截止时间是本会话表单的结算，照记；提示只在页面许可有效时
                bindLimit.limitLogin(config.clock(), outcome.disableMillis.takeIf { it > 0 } ?: DEFAULT_LIMIT_MILLIS)
                tick()
                if (navigable(b, permit)) toast(text(outcome.textKey))
            }

            else -> {
                // 弹窗、字段错误与提示同样只在页面许可有效时落地（FU-AND-0716 ④）
                if (!navigable(b, permit)) return
                // 字段错误不附追踪编号；轻提示正文与追踪编号统一生成
                when {
                    outcome.textKey == RISK_KEY -> {
                        mutableState.update { it.copy(dialog = BindPhoneDialog.RISK) }
                    }

                    outcome.field == LoginErrorMapper.FIELD_PHONE || outcome.actionRaw == BindOutcome.PHONE_INVALID -> {
                        fieldError(
                            phone = text(outcome.textKey),
                        )
                    }

                    // 共性第 29 条：换码后迟到的 20002 / 20003 不显示在新码上
                    // 共性第 35 条：修订号绑定实际发出的提交——held 沿用原请求体时发出的是原提交的码，与发起时的输入不同就不落地
                    outcome.field == LoginErrorMapper.FIELD_CODE -> {
                        val sentTyped = typed == null || outcome.sentCode == null || outcome.sentCode == typed
                        if (revision == codeRevision && sentTyped) fieldError(code = text(outcome.textKey))
                    }

                    else -> {
                        toast(formatted(outcome.textKey, outcome.trace6))
                    }
                }
            }
        }
    }

    /** 号码 / 验证码类反馈（F-X-15）：字段错误、本地号码无效、并号（30411）、风控弹窗（44001）、其余 3xxxx。 */
    @Suppress("ReturnCount") // 逐类提前返回
    private fun fieldClass(outcome: BindOutcome): Boolean {
        if (outcome.field != null || outcome.actionRaw == BindOutcome.PHONE_INVALID ||
            outcome.actionRaw == BindPhoneAction.MERGE_CONFIRM
        ) {
            return true
        }
        if (outcome.textKey == RISK_KEY) return true
        val code = outcome.textKey?.removePrefix("error.")?.toIntOrNull() ?: return false
        return code / CODE_CLASS == BUSINESS_CLASS
    }

    /** 并号确认框【并入】：锁定契约没有并号接口——不发请求、不并号，给明确的「暂不可用」（TODO(N-29) MERGE-SUCCESS）。 */
    fun onMergeConfirm() {
        val result = service.decideMerge(MergeDecision.CONFIRM)
        mutableState.update { it.copy(dialog = null) }
        if (result.resultRaw == MergeDecision.UNAVAILABLE) toast(texts.text(BindPhoneService.MERGE_UNAVAILABLE_KEY))
    }

    /** 并号确认框【取消】、遮罩、返回：不并号、不保存会话，留在本页，输入保留。 */
    fun onMergeCancel() {
        service.decideMerge(MergeDecision.CANCEL)
        mutableState.update { it.copy(dialog = null) }
    }

    fun onDismissDialog() = mutableState.update { it.copy(dialog = null) }

    fun customerService() {
        onDismissDialog()
        config.actions.onCustomerService()
    }

    fun onBack() = config.actions.onBack()

    fun onToastShown(id: Int) = mutableState.update { if (it.toastId == id) it.copy(toast = null) else it }

    val providerName: String get() = config.providerName

    private fun fieldError(
        phone: String? = null,
        code: String? = null,
    ) = mutableState.update { it.copy(phoneError = phone, codeError = code, errorSeq = it.errorSeq + 1) }

    /**
     * 正在跟踪的重发倒计时：规范化号码与它真实的截止时刻（共性第 17 条）。只在这个号码仍是输入框里的号码、且真实截止时刻已到时播报一次
     * 「可以重新获取」；换号、清空时改跟踪新号码（没有倒计时就不跟踪），旧号码的倒计时结束不播报。
     */
    private var watched: Pair<String, Long>? = null

    private fun tick(announceEnd: Boolean = true) {
        val now = config.clock()
        mutableState.update {
            it.copy(
                resendSec = service.resendRemainingSec(it.phone),
                sendLimitedSec = service.sendLimitedRemainingSec(it.phone),
                bindLimitedSec = bindLimit.loginRemainingSec(now),
            )
        }
        val current = mutableState.value
        val phone = PhoneNumber.normalize(current.phone).normalized
        val deadline = service.resendDeadline(current.phone)
        val tracked = watched
        when {
            // 这个号码正在倒计时：跟踪它真实的截止时刻（重新发码会更新）
            phone != null && deadline != null && current.resendSec > 0 -> {
                watched = phone to deadline
            }

            // 换号、清空：不播报，改跟踪新号码（没有倒计时）
            tracked == null || tracked.first != phone || !announceEnd -> {
                watched = null
            }

            // 同一号码、真实截止时刻已到：播报一次
            now >= tracked.second -> {
                watched = null
                announce(texts.text("bind_phone.can_resend"))
            }
        }
    }

    private fun announce(message: String) {
        if (message.isEmpty()) return
        mutableState.update { it.copy(announce = message, announceId = it.announceId + 1) }
    }

    private fun toast(message: String) {
        if (message.isEmpty()) return
        mutableState.update { it.copy(toast = message, toastId = it.toastId + 1) }
    }

    private fun text(key: String?): String = key?.let(texts::text).orEmpty().ifEmpty { texts.text(LoginService.GENERIC_KEY) }

    private fun formatted(
        key: String?,
        trace6: String,
    ): String = ResultSignals.withTrace6(texts.format(key ?: LoginService.GENERIC_KEY, mapOf("trace6" to trace6)), trace6)

    private companion object {
        const val RETRY_LATER_KEY = "pending_confirm.retry_later"
        const val KEY_PHONE = "bind_phone.phone"
        const val KEY_CODE = "bind_phone.code"
        const val TICK_MILLIS = 1000L
        const val DEFAULT_LIMIT_MILLIS = 5_000L
        const val RISK_KEY = "error.44001"
        const val CODE_CLASS = 10_000
        const val BUSINESS_CLASS = 3
    }
}

/**
 * 绑手机页（BindPhone / BindPhoneMerge；APP-05b）：返回 + 居中标题、浅底说明条、白卡片里手机号（+86 / 清除）与验证码（右侧「获取验证码 /
 * N 秒后重新获取」）、底部【绑定】。底部主操作随键盘上移（整页按输入法让位，内容区可滚动；APP-05b 共性第 7 条）；字段错误即时播报一次、
 * 焦点移到错误提示、错误关联到输入框、输入保留（共性第 6 条）。
 */
@Composable
fun BindPhoneScreen(
    config: BindPhonePageConfig,
    modifier: Modifier = Modifier,
) {
    val model: BindPhoneViewModel = viewModel { BindPhoneViewModel(config, createSavedStateHandle()) }
    model.bind(config)
    val state by model.state.collectAsStateWithLifecycle()
    val texts = config.texts
    BackHandler { model.onBack() }
    LifecycleResumeEffect(model) {
        model.onPageVisible()
        onPauseOrDispose { model.onPageHidden() }
    }
    // 渲染时取出的表单实例（普通值，回调捕获它）；会话换代换新实例，整个表单子树按实例号重建（APP-13a R1-02）
    val binding = state.binding
    key(binding.instance) { BindPhoneBody(model, state, binding, texts, modifier) }
    val view = LocalView.current
    LaunchedEffect(state.announceId) {
        @Suppress("DEPRECATION") // 与 CouliToast 同：需要立即读出
        state.announce?.let(view::announceForAccessibility)
    }
}

@Composable
private fun BindPhoneBody(
    model: BindPhoneViewModel,
    state: BindPhoneUiState,
    binding: BindPhoneBinding,
    texts: LoginTexts,
    modifier: Modifier,
) {
    // APP-13v G-D17 / 编排 10-29 ①、评审 r1 R1-01 / R1-02 / R1-03：窄屏大字号时顶部区让位——手机号框、验证码、获取验证码与【绑定】须不滚动就在屏内。
    // 档位只随窗口尺寸与字号重置；只在键盘收起、没有待确认卡、没有字段错误时，按格下一行（未滚动位置）下沿是否超出可视区逐档让位：
    // 并号提示条挪到卡片下方 → 收起「验证码」小标签 → 副标题挪到卡片下方（挪走的仍在页面里、滚动可达，标题始终在上）。
    val scroll = rememberScrollState()
    val fold = rememberIntroFold()
    val calm = !imeOpen() && state.pendingKey == null
    val measurable = calm && state.codeError == null && state.phoneError == null
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(CouliColors.backgroundCanvas)
                .safeDrawingPadding()
                .testTag(BindPhoneTags.PAGE),
    ) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = CONTENT_MAX_WIDTH.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .imePadding(),
        ) {
            BindTopBar(model, texts)
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .onGloballyPositioned(fold::onViewport)
                        .verticalScroll(scroll)
                        .padding(horizontal = CouliSpacing.space4)
                        .padding(top = CouliSpacing.space4, bottom = CouliSpacing.space4),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space6),
            ) {
                // APP-13v D35 / G-D17：标题移入内容区（22/600 读屏标题）+ 副标题 .benefit + 并号提示条 .merge_hint（两条草稿）；契约旧句 .body 本页不再渲染。
                // 评审 r1 R1-04：标题在待确认卡之前（按标题跳读先到页面标题）
                val level = fold.level
                BindIntroAbove(texts, level)
                if (state.pendingKey != null) BindPendingCard(model, state, binding)
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .couliCard()
                            .padding(CouliSpacing.space4),
                    verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4),
                ) {
                    BindPhoneField(model, state, binding, texts)
                    BindCodeField(model, state, binding, texts, showLabel = level < INTRO_NO_CODE_LABEL) { bottomInRoot ->
                        fold.onCodeRow(bottomInRoot + scroll.value, level, measurable)
                    }
                }
                // 让位挪下来的说明（仍可滚动到、读屏可达）
                BindIntroBelow(texts, level)
            }
            CouliButton(
                text = texts.text("bind_phone.submit"),
                onClick = { model.onSubmit(binding) },
                enabled =
                    PhoneNumber.normalize(state.phone).valid && CodeInput.accept(state.code).complete && state.bindLimitedSec == 0 &&
                        state.pendingKey == null && !state.pendingUnreadable && !state.pendingLimited,
                loading = state.submitting,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3)
                        .testTag(BindPhoneTags.SUBMIT),
            )
        }
        state.toast?.let { message ->
            val id = state.toastId
            LaunchedEffect(id) {
                delay(TOAST_MILLIS)
                model.onToastShown(id)
            }
            CouliToast(
                message = message,
                icon = null,
                announcementId = id,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = CouliSpacing.space12, start = CouliSpacing.space4, end = CouliSpacing.space4),
            )
        }
    }
    BindPhoneDialogs(model, state, texts)
    BindAbandonDialog(model, state, binding)
}

/** 顶部区在卡片之上的部分：标题始终在；副标题、并号提示条按让位档位决定是否还在上面。 */
@Composable
private fun BindIntroAbove(
    texts: LoginTexts,
    level: Int,
) {
    BindIntro(
        title = texts.text("invite.bind_phone_guide.title"),
        benefit = if (level < INTRO_TITLE_ONLY) texts.text("invite.bind_phone_guide.benefit") else null,
    )
    if (level == INTRO_FULL) BindInfoStrip(texts.text("invite.bind_phone_guide.merge_hint"))
}

/** 让位挪到卡片下方的说明（副标题在前、并号提示条在后）。 */
@Composable
private fun BindIntroBelow(
    texts: LoginTexts,
    level: Int,
) {
    if (level == INTRO_TITLE_ONLY) BindMovedBenefit(texts.text("invite.bind_phone_guide.benefit"))
    if (level > INTRO_FULL) BindInfoStrip(texts.text("invite.bind_phone_guide.merge_hint"))
}

/** 顶栏（BindPhone-v2：返回 + 空标题栏；标题移到内容区，G-D17）。 */
@Composable
private fun BindTopBar(
    model: BindPhoneViewModel,
    texts: LoginTexts,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .padding(horizontal = CouliSpacing.space1),
        contentAlignment = Alignment.CenterStart,
    ) {
        CouliIconButton(
            icon = CouliIcons.back,
            label = texts.text("login.back"),
            onClick = model::onBack,
            modifier = Modifier.testTag(BindPhoneTags.BACK),
        )
    }
}

@Composable
private fun BindPhoneField(
    model: BindPhoneViewModel,
    state: BindPhoneUiState,
    binding: BindPhoneBinding,
    texts: LoginTexts,
) {
    val label = texts.text("login.phone.label")
    val prefix = texts.text("login.phone.prefix")
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        Text(text = label, style = CouliTypography.footnote, color = CouliColors.textSecondary, modifier = Modifier.clearAndSetSemantics {})
        BindInputBox(error = state.phoneError != null) {
            // APP-13v D36：+86 次文字色、去掉竖分隔线
            Text(
                text = prefix,
                style = CouliTypography.body,
                color = CouliColors.textSecondary,
                modifier = Modifier.semantics { hideFromAccessibility() },
            )
            Box(modifier = Modifier.weight(1f).padding(start = CouliSpacing.space2), contentAlignment = Alignment.CenterStart) {
                if (state.phone.isEmpty()) {
                    // 占位只给视觉（读屏读输入框自己的名称），节点保留文字以便按字核对
                    Text(
                        text = texts.text("login.phone.placeholder"),
                        style = CouliTypography.body,
                        color = CouliColors.textSecondary,
                        modifier = Modifier.semantics { hideFromAccessibility() },
                    )
                }
                BasicTextField(
                    value = state.phone,
                    onValueChange = { model.onPhoneChange(it, binding) },
                    singleLine = true,
                    textStyle = CouliTypography.body.copy(color = CouliColors.textPrimary),
                    cursorBrush = SolidColor(CouliColors.textPrimary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = CouliSpacing.space2)
                            .testTag(BindPhoneTags.PHONE)
                            .semantics {
                                contentDescription = "$label $prefix"
                                // 错误说明关联到输入框（共性第 6 条）
                                state.phoneError?.let { error(it) }
                            },
                )
            }
            if (state.phone.isNotEmpty()) {
                CouliIconButton(
                    icon = CouliIcons.close,
                    label = texts.text("login.phone.clear"),
                    onClick = { model.onClearPhone(binding) },
                    tint = CouliColors.textSecondary,
                    iconSize = CouliSizes.iconSmall,
                    modifier = Modifier.testTag(BindPhoneTags.PHONE_CLEAR),
                )
            }
        }
        FieldError(state.phoneError, state.errorSeq, BindPhoneTags.PHONE_ERROR)
    }
}

/**
 * 验证码（APP-13v D37，G-D04）：组件库 6 格 CodeInput（标记与错误语义经 inputModifier 挂在可输入节点上，读屏名称「验证码」）→ 字段错误 →
 * 格下一行右侧【获取验证码 / {sec} 秒后重新获取】。结构不改、不接输满自动绑定：输入只交给 onCodeChange，【绑定】仍由用户点击。
 */
@Composable
private fun BindCodeField(
    model: BindPhoneViewModel,
    state: BindPhoneUiState,
    binding: BindPhoneBinding,
    texts: LoginTexts,
    showLabel: Boolean,
    onRowPlaced: (Float) -> Unit,
) {
    val label = texts.text("login.code.label")
    val waiting = state.resendSec > 0
    val limited = state.sendLimitedSec > 0
    Column {
        // 可见「验证码」小标签（评审 r1 R1-03，同手机号标签写法；读屏名称由输入节点给）；窄屏大字号让位时收起
        if (showLabel) {
            Text(
                text = label,
                style = CouliTypography.footnote,
                color = CouliColors.textSecondary,
                modifier = Modifier.padding(bottom = CouliSpacing.space2).clearAndSetSemantics {},
            )
        }
        com.couli.core.designsystem.CodeInput(
            value = state.code,
            onValueChange = { model.onCodeChange(it, binding) },
            accessibilityLabel = label,
            error = state.codeError != null,
            inputModifier =
                Modifier.testTag(BindPhoneTags.CODE).semantics {
                    // 错误说明关联到输入框（共性第 6 条）
                    state.codeError?.let { error(it) }
                },
        )
        if (state.codeError != null) BindErrorLine { FieldError(state.codeError, state.errorSeq, BindPhoneTags.CODE_ERROR) }
        val getCode =
            if (waiting) {
                texts.format("bind_phone.countdown", mapOf("sec" to state.resendSec.toString()))
            } else {
                texts.text("login.get_code")
            }
        val enabled = !waiting && !limited && !state.sending && PhoneNumber.normalize(state.phone).valid
        Box(modifier = Modifier.onGloballyPositioned { onRowPlaced(it.positionInRoot().y + it.size.height) }) {
            BindCodeSendRow(text = getCode, enabled = enabled, tag = BindPhoneTags.GET_CODE, onClick = { model.onGetCode(binding) })
        }
    }
}

/**
 * 字段错误提示（APP-05b 共性第 6 条，03 §10.5）：每次新的错误（[seq] 变化）读屏即时播报一次，无障碍焦点移到这条提示；提示本身是礼貌
 * 级以上的实时区域。输入框的 error 语义同时带上这句，读屏回到输入框时也能读到。
 */
@Composable
private fun FieldError(
    message: String?,
    seq: Int,
    tag: String,
) {
    if (message == null) return
    val focus = remember { FocusRequester() }
    val view = LocalView.current
    LaunchedEffect(seq) {
        @Suppress("DEPRECATION") // 与 CouliToast 同：错误态需要立即读出
        view.announceForAccessibility(message)
        delay(ERROR_FOCUS_DELAY_MILLIS)
        runCatching { focus.requestFocus() }
    }
    Text(
        text = message,
        style = CouliTypography.footnote,
        color = CouliColors.statusErrorText,
        modifier =
            Modifier
                .focusRequester(focus)
                .focusable()
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(tag),
    )
}

@Composable
private fun BindInputBox(
    error: Boolean,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = INPUT_MIN_HEIGHT.dp)
                .background(CouliColors.backgroundSurface, RoundedCornerShape(CouliRadius.button))
                .border(
                    CouliSizes.hairline,
                    if (error) CouliColors.statusErrorText else CouliColors.borderControl,
                    RoundedCornerShape(CouliRadius.button),
                ).padding(start = CouliSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun BindPhoneDialogs(
    model: BindPhoneViewModel,
    state: BindPhoneUiState,
    texts: LoginTexts,
) {
    when (state.dialog) {
        // APP-13v D38 / G-D18：标题居中、遮罩令牌；点遮罩关闭（= 取消，零请求）维持现状（编排 10-29 ②）
        BindPhoneDialog.MERGE -> {
            CouliDialog(
                title = texts.text("bind_phone.merge.title"),
                confirmText = texts.text("bind_phone.merge.ok"),
                onConfirm = model::onMergeConfirm,
                onDismissRequest = model::onMergeCancel,
                dismissText = texts.text("bind_phone.merge.cancel"),
                titleAlign = TextAlign.Center,
                scrim = CouliColors.overlayScrim,
                // 正文 error.30411.mergeable + 信息块（DesignSystem 弹窗给了 body 就不再画 message，正文放进 body）
                body = {
                    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4)) {
                        Text(
                            text = texts.format("error.30411.mergeable", mapOf("provider_name" to model.providerName)),
                            style = CouliTypography.footnote,
                            color = CouliColors.textPrimary,
                        )
                        MergeTarget(texts, state.mergeMasked)
                    }
                },
            )
        }

        // 44001：标题 error.44001，【知道了】与【联系客服申诉】（BR-TEXT-14 表 A；没有绑手机专用说明句，APP-05b 共性第 5 条）
        BindPhoneDialog.RISK -> {
            CouliDialog(
                title = texts.text("error.44001"),
                confirmText = texts.text("login.risk_blocked.ok"),
                onConfirm = model::onDismissDialog,
                onDismissRequest = model::onDismissDialog,
                dismissText = texts.text("cs.appeal_entry"),
                onDismiss = model::customerService,
                titleAlign = TextAlign.Center,
                scrim = CouliColors.overlayScrim,
            )
        }

        null -> {
            Unit
        }
    }
}

/** 并号确认里的信息块：并入图标、「并入到」、「手机号账号 138****5678」（设计稿 BindPhoneMerge）。 */
@Composable
private fun MergeTarget(
    texts: LoginTexts,
    masked: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3)
                .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Icon(CouliIcons.merge, contentDescription = null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSizes.iconMedium))
        Column {
            Text(text = texts.text("bind_phone.merge.into"), style = CouliTypography.caption, color = CouliColors.textSecondary)
            Text(
                text = texts.format("bind_phone.merge.account", mapOf("phone" to masked)),
                style = CouliTextStyles.bodyMedium,
                color = CouliColors.textPrimary,
            )
        }
    }
}

/**
 * 第三方新号绑手机引导（LoginBindPhoneGuide，BR-INV-03 细则 ①、F-INV-10）：标题与正文用契约 invite.bind_phone_guide.*，【暂不】/【去绑定】。
 * 登录页关闭后盖在恢复页之上（不新增路由，rule-gaps ROUTES / GUIDE-ROUTE）。点遮罩、返回按【暂不】处理。
 */
@Composable
fun BindPhoneGuideDialog(
    texts: LoginTexts,
    onSkip: () -> Unit,
    onGo: () -> Unit,
) {
    CouliDialog(
        title = texts.text("invite.bind_phone_guide.title"),
        message = texts.text("invite.bind_phone_guide.body"),
        confirmText = texts.text("invite.bind_phone_guide.go"),
        onConfirm = onGo,
        onDismissRequest = onSkip,
        dismissText = texts.text("invite.bind_phone_guide.skip"),
    )
}

private const val TOAST_MILLIS = 2_000L
private const val INPUT_MIN_HEIGHT = 52
private const val CONTENT_MAX_WIDTH = 480
private const val ERROR_FOCUS_DELAY_MILLIS = 600L

/** 测试标记（APP-13a BindPhone 待确认，G07）。 */
object BindPhonePendingTags {
    const val CARD = "bind_phone_pending_card"
    const val SUBMITTED_AT = "bind_phone_pending_submitted_at"
    const val CONFIRM = "bind_phone_pending_confirm"
    const val ABANDON = "bind_phone_pending_abandon"
    const val NOTICE = "bind_phone_pending_notice"
    const val ABANDON_OK = "bind_phone_abandon_ok"
    const val ABANDON_CANCEL = "bind_phone_abandon_cancel"
    const val GUIDE = "bind_phone_pending_guide"
}

/** 补办入口的按钮文案（与注销页手动入口同一口径：Login → 去登录、BindPhone → 去绑定，其余 → 重试）。 */
private fun pendingGuideLabel(route: String): String =
    when (route) {
        "Login" -> "settings.go_login"
        "BindPhone" -> "deletion.bind_phone"
        else -> "me.retry"
    }

/**
 * BindPhone 首次绑定的待确认卡（G07；APP-13v D38 版式同注销 / 换号待确认：白卡 → 警告块（标题 + 说明，alert 一次）→ 灰底明细 → 两等宽按钮 →
 * 在途状态行（只读既有 pendingBusy，G-D13）→ 提示警告块 → 补办入口 → 卡底注释）。文案共用 pending_confirm.phone_change.*，{phone} 为本机脱敏的提交号码；
 * 【放弃上一次提交】【确认上一次提交】在途或共享 phoneGate 限流时都置灰（R1-06）。回调捕获渲染时的键与表单实例。
 */
@Composable
private fun BindPendingCard(
    model: BindPhoneViewModel,
    state: BindPhoneUiState,
    binding: BindPhoneBinding,
) {
    val key = state.pendingKey ?: return
    val texts = com.couli.core.me.account.AccountDraftTexts
    val usable = !state.pendingBusy && !state.pendingLimited
    val desc =
        if (state.pendingMasked.isNotEmpty()) {
            texts.format("pending_confirm.phone_change.desc", mapOf("phone" to state.pendingMasked))
        } else {
            texts.text("pending_confirm.phone_change.desc")
        }
    BindPendingFrame(tag = BindPhonePendingTags.CARD) {
        BindPendingWarning(title = texts.text("pending_confirm.title"), desc = desc)
        // 摘要（G24，与换号卡一致）：提交的新手机号（本机脱敏）+ 提交时间（记录创建时刻，BR-TEXT-11）
        if (state.pendingMasked.isNotEmpty() || state.pendingCreatedAt > 0L) {
            BindDetailBlock {
                if (state.pendingMasked.isNotEmpty()) {
                    BindDetailRow(texts.text("pending_confirm.summary.new_phone"), state.pendingMasked)
                }
                if (state.pendingCreatedAt > 0L) {
                    BindDetailRow(
                        texts.text("pending_confirm.summary.submitted_at"),
                        com.couli.core.me.account.DeletionSummary
                            .submittedAt(state.pendingCreatedAt, model.now()),
                        Modifier.testTag(BindPhonePendingTags.SUBMITTED_AT),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
            CouliButton(
                text = texts.text("pending_confirm.abandon"),
                onClick = { model.onAskAbandon(key, binding) },
                enabled = usable,
                variant = com.couli.core.designsystem.CouliButtonVariant.SECONDARY,
                size = com.couli.core.designsystem.CouliButtonSize.COMPACT,
                textStyle = CouliTextStyles.bodySemibold,
                modifier = Modifier.weight(1f).testTag(BindPhonePendingTags.ABANDON),
            )
            CouliButton(
                text = texts.text("pending_confirm.phone_change.action"),
                onClick = { model.onConfirmPending(key, binding) },
                enabled = usable,
                size = com.couli.core.designsystem.CouliButtonSize.COMPACT,
                textStyle = CouliTextStyles.bodySemibold,
                modifier = Modifier.weight(1f).testTag(BindPhonePendingTags.CONFIRM),
            )
        }
        // APP-13v G-D13：在途状态行只读既有 pendingBusy（确认 / 放弃发出后到结果落地前）
        if (state.pendingBusy) BindBusyRow(texts.text("error.40901"))
        state.pendingNotice?.let { BindPendingNotice(text = it, tag = BindPhonePendingTags.NOTICE) }
        // R2-07：原页面许可失效时保留的补办入口（绑定本表单实例；点击经 Router 打开，记录保持待确认）
        state.pendingGuide?.let { route ->
            CouliButton(
                text = texts.text(pendingGuideLabel(route)),
                onClick = { model.onPendingGuide(binding) },
                variant = com.couli.core.designsystem.CouliButtonVariant.SECONDARY,
                modifier = Modifier.fillMaxWidth().testTag(BindPhonePendingTags.GUIDE),
            )
        }
        Text(text = texts.text("bind_phone.pending_hint"), style = CouliTypography.caption, color = CouliColors.textSecondary)
    }
}

/** 放弃上一次提交的二次确认（点遮罩不关闭，系统返回 = 【再想想】；APP-13v G-D18：标题居中、遮罩令牌）。 */
@Composable
private fun BindAbandonDialog(
    model: BindPhoneViewModel,
    state: BindPhoneUiState,
    binding: BindPhoneBinding,
) {
    val dialog = state.abandonDialog
    if (dialog == 0) return
    val texts = com.couli.core.me.account.AccountDraftTexts
    com.couli.core.designsystem.CouliDialog(
        title = texts.text("pending_confirm.abandon"),
        message = texts.text("pending_confirm.abandon.confirm") + "\n" + texts.text("pending_confirm.abandon.note.phone_change"),
        confirmText = texts.text("pending_confirm.abandon.ok"),
        onConfirm = { model.onAbandon(dialog, binding) },
        // R1-06：二次确认的【确定放弃】同样随共享 phoneGate 置灰，到期恢复
        confirmEnabled = !state.pendingLimited && !state.pendingBusy,
        dismissText = texts.text("pending_confirm.abandon.cancel"),
        onDismiss = { model.onCancelAbandon(dialog) },
        onDismissRequest = { model.onCancelAbandon(dialog) },
        dismissOnClickOutside = false,
        confirmTag = BindPhonePendingTags.ABANDON_OK,
        dismissTag = BindPhonePendingTags.ABANDON_CANCEL,
        titleAlign = TextAlign.Center,
        scrim = CouliColors.overlayScrim,
    )
}
