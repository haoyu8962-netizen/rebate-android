// 错误码是契约取值（contracts/error-codes.json），按码直接写
@file:Suppress("MagicNumber")

package com.couli.feature.wallet

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.ResultSignals
import com.couli.core.wallet.SendProbe
import com.couli.core.wallet.SensitiveOutcome
import com.couli.core.wallet.WalletError
import com.couli.core.wallet.WalletErrors
import com.couli.core.wallet.WalletJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 面板里的一条提示（文案键 + trace；[id] 每次换新，读屏重新播报）。 */
data class Notice(
    val key: String,
    val id: Long,
    val traceId: String? = null,
    val showTrace6: Boolean = false,
    val assertive: Boolean = true,
)

/**
 * 二次验证面板的状态（WithdrawStepUp；BR-ID-08、BR-WDR-01）。[phase]：sending（正在发码）、ready（可输入）、no_phone（本机取不到号码，不发码，
 * Android 空白 A01）、verifying（正在验证）。[resendLeftSec] 为 0 时可重新获取。
 */
data class StepUpUiState(
    val action: String,
    val phase: String,
    val code: String = "",
    val resendLeftSec: Long = 0,
    val error: Notice? = null,
    val generation: Long = 0,
    /**
     * 打开面板的提交 / 确认所属的账号与登录世代（APP-09 共性第 60 条，Android RC-02）：发码、重新获取、验证、读取手机号、占用发码槽位、发包前都核对它
     * 仍是当前会话，并按它取号码、带账号；不重新绑定执行时的会话。
     */
    val owner: FormOwner = FormOwner(null, Long.MIN_VALUE),
) {
    val canVerify: Boolean get() = phase == READY && code.length == CODE_LENGTH

    companion object {
        const val SENDING = "sending"
        const val READY = "ready"
        const val NO_PHONE = "no_phone"
        const val VERIFYING = "verifying"
        const val CODE_LENGTH = 6
    }
}

/**
 * 二次验证执行器（短信方式；第三方方式只给注销，不在本任务）：打开即对当前 action 发码（sendSmsCode {phone, purpose: step_up, action}），号码由
 * App 按账号归属给（[WalletEnv.stepUpPhone]，取不到不发码、不让用户另填）；倒计时按 resend_after_sec（未返回按 60 秒）；输满 6 位才能
 * 【验证并提交】→ stepUp {action, code}，成功把 token 交给发起方（发起方沿用原键重放）。20002 / 20003 清空输入并 assertive 播报；发码与验证的
 * 42901 分开限流（执行层发送前闸门 + 页面置灰）。面板出现期间属于敏感浮层，关闭后迟到的结果只丢弃。只在主线程使用。
 */
@Suppress("TooManyFunctions") // 面板每个入口一个函数
class StepUpController(
    private val deps: WalletDeps,
    private val scope: CoroutineScope,
    private val alive: () -> Boolean,
) {
    private val mutable = MutableStateFlow<StepUpUiState?>(null)
    val state: StateFlow<StepUpUiState?> = mutable.asStateFlow()

    private var generation = 0L
    private var noticeSerial = 0L
    private var onToken: ((String) -> Unit)? = null
    private var onClosed: (() -> Unit)? = null
    private var ticker: Job? = null
    private var resendUntil = 0L
    private val owners = mutableListOf<CallOwnership>()

    val showing: Boolean get() = mutable.value != null

    /**
     * 打开面板并发码。[owner] 是打开它的提交 / 确认的身份（共性第 60 条）：面板之后的发码、验证都沿用它。[onToken] 拿到 token 后由发起方沿用原键重放；
     * [onClosed] 用户关闭面板。
     */
    fun open(
        action: String,
        owner: FormOwner,
        onToken: (String) -> Unit,
        onClosed: () -> Unit,
    ) {
        generation += 1
        this.onToken = onToken
        this.onClosed = onClosed
        mutable.value = StepUpUiState(action, StepUpUiState.SENDING, generation = generation, owner = owner)
        deps.env.sensitiveOverlay(true)
        send()
    }

    /** 面板上的回调都带渲染时面板的身份（共性第 60 条）：不是这个面板的、或已不是当前会话的不受理。 */
    private fun owns(
        current: StepUpUiState,
        owner: FormOwner,
    ): Boolean = owner == current.owner && owner.current(deps.env)

    fun onCodeChange(
        text: String,
        owner: FormOwner? = null,
    ) {
        val current = mutable.value ?: return
        if (!owns(current, owner ?: current.owner) || current.phase == StepUpUiState.VERIFYING) return
        val digits = text.filter { it in '0'..'9' }.take(StepUpUiState.CODE_LENGTH)
        mutable.value = current.copy(code = digits)
    }

    /** 【重新获取】（倒计时结束、发码禁用期外）。 */
    fun resend(owner: FormOwner? = null) {
        val current = mutable.value ?: return
        // 不是这个面板的回调、会话已换的不受理（共性第 60 条）；发码中（含别的面板在途）不再受理：连点只发一次（APP-09 共性第 17 条）
        val idle = current.resendLeftSec == 0L && !deps.gates.sms.active && current.phase == StepUpUiState.READY
        if (!owns(current, owner ?: current.owner) || !idle) return
        send()
    }

    @Suppress("ReturnCount") // 没有面板、没有账号、没有号码、限流逐项
    private fun send() {
        val current = mutable.value ?: return
        // 按面板的身份读号码、占用槽位、发包（共性第 60 条）：会话已换（清空通知还没执行）的不读、不占、不发，等清空通知收起面板
        if (!current.owner.current(deps.env)) return
        val account = current.owner.account
        val phone = account?.let(deps.env::stepUpPhone)
        if (account == null || phone == null) {
            // 本机取不到当前账号的号码：不编造、不让用户另填，不发码（Android 空白 A01，G31）
            mutable.value = current.copy(phase = StepUpUiState.NO_PHONE)
            return
        }
        if (deps.gates.sms.active) {
            mutable.value = current.copy(phase = StepUpUiState.READY, error = notice("error.42901"))
            return
        }
        // 同一账号、号码、action 的发码截止时间还没到（重开面板、别的页面刚发过）：不再发，恢复倒计时（共性第 6 条）
        val slot = SmsDeadlines.Slot(account, phone, current.action)
        val left = deps.sms.remaining(slot, deps.clock())
        if (left > 0) {
            mutable.value = current.copy(phase = StepUpUiState.READY)
            countdownTo(deps.clock() + left)
            return
        }
        // 同一账号、号码、action 已有在途发码（别的面板、连点）：不再发，等它结束后按共享截止时间恢复（共性第 17 条）
        val ticket = deps.sms.acquire(slot)
        if (ticket == null) {
            mutable.value = current.copy(phase = StepUpUiState.SENDING, error = null)
            awaitOther(slot)
            return
        }
        val gen = generation
        val epoch = current.owner.epoch
        // 点击即进入 SENDING（【重新获取】置灰），结果回来之前不再受理
        mutable.value = current.copy(phase = StepUpUiState.SENDING, error = null)
        val body =
            JsonObject(
                mapOf(
                    "phone" to JsonPrimitive(phone),
                    "purpose" to JsonPrimitive(PURPOSE),
                    "action" to JsonPrimitive(current.action),
                ),
            ).toString()
        launchSend(slot, ticket, body, gen, epoch)
    }

    /**
     * 实际发码（共性第 6、17、26、27、40 条）：在会话级作用域执行（页面出栈、ViewModel 清除都不取消已发出的发码）；结果先按点击时的账号与会话归属写入
     * 共享倒计时 / 限流，再按面板是否有效更新界面；结束时只释放本次请求的占用（[ticket]）。请求带点击时的账号（共性第 39 条）。
     */
    private fun launchSend(
        slot: SmsDeadlines.Slot,
        ticket: Long,
        body: String,
        gen: Long,
        epoch: Long,
    ) {
        // 发码不随面板关闭取消（已发出的码要更新共享倒计时）；会话换代才作废。每次实际写出前核对禁用期与同一截止时间（共性第 6 条）
        // 发码禁用期是本请求的发送前限流闸门（共性第 62 条，记下拒绝原因）；同一截止时间未到另算，不是限流
        val probe = SendProbe.of(deps.gates.sms) { deps.sms.remaining(slot, deps.clock()) == 0L }
        val owner =
            CallOwnership(deps.api.epochs(), sendAllowed = probe::allowed) {
                deps.env.sessionEpoch() == epoch
            }
        deps.service.launch {
            var deadline: Long? = null
            try {
                val result = deps.api.send(ApiCall(SEND_OPERATION, body = body, ownership = owner, expectedAccount = slot.account))
                if (deps.env.sessionEpoch() == epoch && deps.env.account() == slot.account) {
                    // 有效发码结果更新共享倒计时与限流截止时间，面板已关闭、页面已出栈也不丢（共性第 6、17、40 条）
                    if (result is ApiResult.Success) {
                        val seconds = WalletJson.sendSmsCode(result.data)?.resendAfterSec?.takeIf { it > 0 } ?: DEFAULT_RESEND_SEC
                        deadline = (deps.clock() + seconds * MILLIS).also { deps.sms.start(slot, it) }
                    } else {
                        settleRetryAfter(result, deps.gates.sms)
                    }
                }
                if (!valid(gen, epoch)) return@launch
                onSent(result, deadline, probe)
            } finally {
                deps.sms.release(slot, ticket)
            }
        }
    }

    /** 42901 按归属写入共享禁用期（面板、页面是否还在都写）。 */
    private fun settleRetryAfter(
        result: ApiResult,
        gate: com.couli.core.wallet.WalletRateGate,
    ) {
        val error = WalletErrors.of(result)
        if (error.kind == WalletError.RETRY_AFTER) gate.start(error.disableMillis.takeIf { it > 0 } ?: SensitiveOutcome.DEFAULT_RETRY_MS)
    }

    /** 别的面板正在发码：等它结束，按共享截止时间恢复倒计时（不自动再发）。 */
    private fun awaitOther(slot: SmsDeadlines.Slot) {
        val gen = generation
        val epoch = mutable.value?.owner?.epoch ?: return
        scope.launch {
            deps.sms.changes.first { !deps.sms.busy(slot) }
            if (!valid(gen, epoch)) return@launch
            val current = mutable.value ?: return@launch
            val left = deps.sms.remaining(slot, deps.clock())
            mutable.value = current.copy(phase = StepUpUiState.READY)
            if (left > 0) countdownTo(deps.clock() + left)
        }
    }

    private fun onSent(
        result: ApiResult,
        deadline: Long?,
        probe: SendProbe,
    ) {
        val current = mutable.value ?: return
        if (result is ApiResult.Success && deadline != null) {
            mutable.value = current.copy(phase = StepUpUiState.READY, error = null)
            countdownTo(deadline)
            return
        }
        mutable.value = current.copy(phase = StepUpUiState.READY, error = failure(result, probe))
    }

    /** 【验证并提交】。 */
    @Suppress("ReturnCount") // 没有面板、位数不够、限流逐项
    fun verify(owner: FormOwner? = null) {
        val current = mutable.value ?: return
        // 面板的身份（共性第 60 条）：不是这个面板的回调、或会话已换（清空通知还没执行）的不验证，更不以新会话的身份发出旧面板的验证码
        if (!owns(current, owner ?: current.owner)) return
        if (!current.canVerify) return
        if (deps.gates.verify.active) {
            mutable.value = current.copy(error = notice("error.42901"))
            return
        }
        val account = current.owner.account ?: return
        val gen = generation
        val epoch = current.owner.epoch
        mutable.value = current.copy(phase = StepUpUiState.VERIFYING, error = null)
        val body = JsonObject(mapOf("action" to JsonPrimitive(current.action), "code" to JsonPrimitive(current.code))).toString()
        val probe = deps.gates.verify.probe()
        // 发包前由网络层按 expectedAccount 核对账号、按面板世代核对归属
        val callOwner = ownership(gen, epoch, probe::allowed)
        scope.launch {
            // 带点击时的账号（共性第 39 条）：会话已换成别的账号的不发
            val result = deps.api.send(ApiCall(VERIFY_OPERATION, body = body, ownership = callOwner, expectedAccount = account))
            owners -= callOwner
            // 42901 先按归属写入共享禁用期，再看面板是否有效（共性第 27、40 条）
            if (deps.env.sessionEpoch() == epoch && deps.env.account() == account) settleRetryAfter(result, deps.gates.verify)
            if (!valid(gen, epoch)) return@launch
            onVerified(result, account, current.action, probe)
        }
    }

    private fun onVerified(
        result: ApiResult,
        account: String,
        action: String,
        probe: SendProbe,
    ) {
        val current = mutable.value ?: return
        val data = (result as? ApiResult.Success)?.let { WalletJson.stepUp(it.data) }
        if (data != null) {
            deps.tokens.put(action, account, data.token, data.expireAtMillis)
            val deliver = onToken
            dismiss()
            deliver?.invoke(data.token)
            return
        }
        val code = (result as? ApiResult.Failure)?.code
        // 验证码错误或失效：清空输入、assertive 播报（焦点留在输入框）
        val clear = code == WRONG_CODE || code == EXPIRED_CODE
        mutable.value =
            current.copy(
                phase = StepUpUiState.READY,
                code = if (clear) "" else current.code,
                error = if (result is ApiResult.Success) notice("error.50001") else failure(result, probe),
            )
    }

    /** 用户点【关闭】（点遮罩、下滑不关闭）。 */
    fun close() {
        if (!showing) return
        val closed = onClosed
        dismiss()
        closed?.invoke()
    }

    /** 收起面板（拿到 token、会话换代、离页）：在途的发码与验证作废，迟到的结果只丢弃。 */
    fun dismiss() {
        generation += 1
        ticker?.cancel()
        ticker = null
        cancelAll()
        if (mutable.value != null) deps.env.sensitiveOverlay(false)
        mutable.value = null
        onToken = null
        onClosed = null
    }

    private fun failure(
        result: ApiResult,
        probe: SendProbe,
    ): Notice? {
        val error = WalletErrors.of(result)
        return when (error.kind) {
            WalletError.CANCELLED -> {
                // 本请求被发送前限流闸门拦下（本地拒绝，共性第 62 条）：按记下的原因提示限流，不看回调时刻的禁用期
                if (probe.gated(error)) notice("error.42901") else null
            }

            // 禁用期已在结果回来时按归属写入（[settleRetryAfter]），这里只给提示
            WalletError.RETRY_AFTER -> {
                notice("error.42901")
            }

            WalletError.FORCE_UPDATE -> {
                deps.env.forceUpdate()
                notice("error.10405")
            }

            else -> {
                Notice(error.textKey, ++noticeSerial, error.traceId, error.showTrace6)
            }
        }
    }

    private fun countdownTo(deadline: Long) {
        resendUntil = deadline
        ticker?.cancel()
        ticker =
            scope.launch {
                var left = 1L
                while (left > 0 && mutable.value != null) {
                    left = (((resendUntil - deps.clock()) + MILLIS - 1) / MILLIS).coerceAtLeast(0)
                    mutable.value = mutable.value?.copy(resendLeftSec = left)
                    if (left > 0) delay(TICK_MILLIS)
                }
            }
    }

    private fun notice(key: String) = Notice(key, ++noticeSerial)

    private fun valid(
        gen: Long,
        epoch: Long,
    ): Boolean = alive() && gen == generation && deps.env.sessionEpoch() == epoch && mutable.value != null

    private fun ownership(
        gen: Long,
        epoch: Long,
        sendAllowed: () -> Boolean,
    ): CallOwnership =
        CallOwnership(deps.api.epochs(), sendAllowed = sendAllowed) {
            alive() && gen == generation && deps.env.sessionEpoch() == epoch
        }.also { owners += it }

    private fun cancelAll() {
        val cancelled = owners.toList()
        owners.clear()
        cancelled.forEach(deps.api::cancelOwned)
    }

    companion object {
        const val SEND_OPERATION = "sendSmsCode"
        const val VERIFY_OPERATION = "stepUp"
        const val PURPOSE = "step_up"
        const val DEFAULT_RESEND_SEC = 60L
        private const val WRONG_CODE = 20002
        private const val EXPIRED_CODE = 20003
        private const val MILLIS = 1_000L
        private const val TICK_MILLIS = 1_000L

        /** 读屏用的 trace（测试接线）。 */
        fun trace6(traceId: String?): String = ResultSignals.trace6(traceId)
    }
}
