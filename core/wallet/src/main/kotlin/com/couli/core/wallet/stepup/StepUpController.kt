// 错误码是契约取值（contracts/error-codes.json），按码直接写
@file:Suppress("MagicNumber")

package com.couli.core.wallet.stepup

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.ResultSignals
import com.couli.core.wallet.SendProbe
import com.couli.core.wallet.SensitiveOutcome
import com.couli.core.wallet.WalletError
import com.couli.core.wallet.WalletErrors
import com.couli.core.wallet.WalletJson
import com.couli.core.wallet.WalletRateGate
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

/**
 * 二次验证面板的状态（WithdrawStepUp、PhoneChange 面板、注销面板；BR-ID-08、BR-WDR-01）。[phase]：sending（正在发码）、ready（可输入）、no_phone（本机取不到
 * 号码，不发码，Android 空白 A01 / APP-13a G02）、verifying（正在验证）、scope_denied（受限会话下非 account_deletion 的 action，本地拒绝，不发请求）。
 * [resendLeftSec] 为 0 时可重新获取；[smsLimited] = 本槽位（账号 + 号码 + action）在 42901 禁用期内（【重新获取】置灰）。
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
    val smsLimited: Boolean = false,
) {
    val canVerify: Boolean get() = phase == READY && code.length == CODE_LENGTH

    companion object {
        const val SENDING = "sending"
        const val READY = "ready"
        const val NO_PHONE = "no_phone"
        const val VERIFYING = "verifying"
        const val SCOPE_DENIED = "scope_denied"
        const val CODE_LENGTH = 6
    }
}

/**
 * 二次验证执行器（短信方式；第三方方式只给注销，不在 APP-13a，G10）：打开即对当前 action 发码（sendSmsCode {phone, purpose: step_up, action}），号码由
 * App 按账号归属给（[StepUpEnv.stepUpPhone]，取不到不发码、不让用户另填）；倒计时按 resend_after_sec（未返回按 60 秒）；输满 6 位才能
 * 【验证并提交】→ stepUp {action, code}，成功把 token 交给发起方（发起方沿用原键重放）。20002 / 20003 清空输入并 assertive 播报。
 * 发码的 42901 按「账号 + 号码 + action」同一把键（[SmsDeadlines.rateLimit]）；APP-09 的两个钱包 action 另沿用会话级 [StepUpDeps.smsGate]
 * （既有口径与测试），APP-13a 的 phone_change / account_deletion 不写也不读这把全局闸门（共性第 15 条）。验证的 42901 用 [StepUpDeps.verifyGate]
 * （与发码分开，共性第 26 条）。受限会话下只允许 account_deletion（其他 action 本地拒绝，不发请求）。面板出现期间属于敏感浮层，关闭后迟到的结果只丢弃。
 * 只在主线程使用。从 feature/wallet 挪来（APP-13a brief §3 Android），钱包与「我的」共用。
 */
@Suppress("TooManyFunctions") // 面板每个入口一个函数
class StepUpController(
    private val deps: StepUpDeps,
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
    private var limitTimer: Job? = null
    private var resendUntil = 0L
    private val owners = mutableListOf<CallOwnership>()

    val showing: Boolean get() = mutable.value != null

    /**
     * 打开面板并发码。[owner] 是打开它的提交 / 确认的身份（共性第 60 条）：面板之后的发码、验证都沿用它。[onToken] 拿到 token 后由发起方沿用原键重放；
     * [onClosed] 用户关闭面板。受限会话下 action 不是 account_deletion：面板显示「当前不能进行这项验证」，不发码、不验证。
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
        deps.env.sensitiveOverlay(true)
        if (!scopeAllows(action)) {
            mutable.value =
                StepUpUiState(
                    action,
                    StepUpUiState.SCOPE_DENIED,
                    generation = generation,
                    owner = owner,
                )
            return
        }
        mutable.value = StepUpUiState(action, StepUpUiState.SENDING, generation = generation, owner = owner)
        send()
    }

    /** 受限会话（deletion_only）只接受 account_deletion 的发码与验证（BR-ID-01 细则「受限会话」、StepUpTokenPolicy 的 scope_denied）。 */
    private fun scopeAllows(action: String): Boolean =
        deps.env.sessionScope() != StepUpEnv.SCOPE_DELETION_ONLY || action == ACCOUNT_DELETION

    /** 面板上的回调都带渲染时面板的身份（共性第 60 条）：不是这个面板的、或已不是当前会话的不受理。 */
    private fun owns(
        current: StepUpUiState,
        owner: FormOwner,
    ): Boolean = owner == current.owner && owner.current(deps.env)

    @Suppress("ReturnCount") // 没有面板、身份不符、不可输入逐项
    fun onCodeChange(
        text: String,
        owner: FormOwner? = null,
    ) {
        val current = mutable.value ?: return
        if (!owns(current, owner ?: current.owner) || current.phase == StepUpUiState.VERIFYING) return
        if (current.phase == StepUpUiState.SCOPE_DENIED) return
        val digits = text.filter { it in '0'..'9' }.take(StepUpUiState.CODE_LENGTH)
        mutable.value = current.copy(code = digits)
    }

    /** APP-09 的两个钱包 action 沿用会话级发码闸门（既有口径）；APP-13a 的 action 只按槽位限流。 */
    private fun legacy(action: String): Boolean = action != PHONE_CHANGE && action != ACCOUNT_DELETION

    private fun globalSmsBlocked(action: String): Boolean = legacy(action) && deps.smsGate.active

    /** 【重新获取】（倒计时结束、发码禁用期外）。 */
    fun resend(owner: FormOwner? = null) {
        val current = mutable.value ?: return
        // 不是这个面板的回调、会话已换的不受理（共性第 60 条）；发码中（含别的面板在途）不再受理：连点只发一次（APP-09 共性第 17 条）
        val idle =
            current.resendLeftSec == 0L && !globalSmsBlocked(current.action) && !current.smsLimited &&
                current.phase == StepUpUiState.READY
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
            // 本机取不到当前账号的号码：不编造、不让用户另填，不发码（Android 空白 A01，G31；APP-13a G02）
            mutable.value = current.copy(phase = StepUpUiState.NO_PHONE)
            return
        }
        val slot = SmsDeadlines.Slot(account, phone, current.action)
        // 同一账号、号码、action 在 42901 禁用期内（同一把键，APP-13a 共性第 15 条），或钱包 action 的会话级闸门：不发，提示限流并在到期时刷新
        val limitedLeft = deps.sms.limitedLeft(slot, deps.clock())
        if (limitedLeft > 0 || globalSmsBlocked(current.action)) {
            mutable.value = current.copy(phase = StepUpUiState.READY, error = notice(RATE_KEY), smsLimited = limitedLeft > 0)
            if (limitedLeft > 0) watchLimit(slot)
            return
        }
        // 同一账号、号码、action 的发码截止时间还没到（重开面板、别的页面刚发过）：不再发，恢复倒计时（共性第 6 条）
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
        val probe =
            SendProbe.limitedBy({ globalSmsBlocked(slot.action) || deps.sms.limitedLeft(slot, deps.clock()) > 0 }) {
                deps.sms.remaining(slot, deps.clock()) == 0L
            }
        val owner =
            CallOwnership(deps.api.epochs(), sendAllowed = probe::allowed) {
                deps.env.sessionEpoch() == epoch
            }
        deps.service.launch {
            var deadline: Long? = null
            try {
                val result = deps.api.send(ApiCall(SEND_OPERATION, body = body, ownership = owner, expectedAccount = slot.account))
                if (deps.env.ownedBy(slot.account, epoch)) {
                    // 有效发码结果更新共享倒计时与限流截止时间，面板已关闭、页面已出栈也不丢（共性第 6、17、40 条）
                    if (result is ApiResult.Success) {
                        val seconds = WalletJson.sendSmsCode(result.data)?.resendAfterSec?.takeIf { it > 0 } ?: DEFAULT_RESEND_SEC
                        deadline = (deps.clock() + seconds * MILLIS).also { deps.sms.start(slot, it) }
                    } else {
                        settleSmsLimit(result, slot)
                    }
                }
                if (!valid(gen, epoch)) return@launch
                onSent(result, deadline, probe, slot)
            } finally {
                deps.sms.release(slot, ticket)
            }
        }
    }

    /** 发码 42901 按归属写入同一槽位的限流（面板、页面是否还在都写）；钱包 action 另写会话级闸门（既有口径）。 */
    private fun settleSmsLimit(
        result: ApiResult,
        slot: SmsDeadlines.Slot,
    ) {
        val error = WalletErrors.of(result)
        if (error.kind != WalletError.RETRY_AFTER) return
        val millis = error.disableMillis.takeIf { it > 0 } ?: SensitiveOutcome.DEFAULT_RETRY_MS
        deps.sms.rateLimit(slot, deps.clock() + millis)
        if (legacy(slot.action)) deps.smsGate.start(millis)
    }

    /** 验证 42901 按归属写入验证禁用期。 */
    private fun settleRetryAfter(
        result: ApiResult,
        gate: WalletRateGate,
    ) {
        val error = WalletErrors.of(result)
        if (error.kind == WalletError.RETRY_AFTER) gate.start(error.disableMillis.takeIf { it > 0 } ?: SensitiveOutcome.DEFAULT_RETRY_MS)
    }

    /** 本槽位的限流到期时刷新面板（共性第 20 条：恢复已有限流立即安排到期刷新，不依赖全局闸门）。 */
    private fun watchLimit(slot: SmsDeadlines.Slot) {
        val gen = generation
        limitTimer?.cancel()
        limitTimer =
            scope.launch {
                while (true) {
                    val left = deps.sms.limitedLeft(slot, deps.clock())
                    if (left <= 0) break
                    delay(left.coerceAtLeast(1L))
                }
                if (gen != generation) return@launch
                val current = mutable.value ?: return@launch
                mutable.value = current.copy(smsLimited = false)
            }
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
            val limited = deps.sms.limitedLeft(slot, deps.clock()) > 0
            mutable.value = current.copy(phase = StepUpUiState.READY, smsLimited = limited)
            if (limited) watchLimit(slot)
            if (left > 0) countdownTo(deps.clock() + left)
        }
    }

    private fun onSent(
        result: ApiResult,
        deadline: Long?,
        probe: SendProbe,
        slot: SmsDeadlines.Slot,
    ) {
        val current = mutable.value ?: return
        if (result is ApiResult.Success && deadline != null) {
            mutable.value = current.copy(phase = StepUpUiState.READY, error = null)
            countdownTo(deadline)
            return
        }
        val limited = deps.sms.limitedLeft(slot, deps.clock()) > 0
        mutable.value = current.copy(phase = StepUpUiState.READY, error = failure(result, probe), smsLimited = limited)
        if (limited) watchLimit(slot)
    }

    /** 【验证并提交】。 */
    @Suppress("ReturnCount") // 没有面板、位数不够、限流逐项
    fun verify(owner: FormOwner? = null) {
        val current = mutable.value ?: return
        // 面板的身份（共性第 60 条）：不是这个面板的回调、或会话已换（清空通知还没执行）的不验证，更不以新会话的身份发出旧面板的验证码
        if (!owns(current, owner ?: current.owner)) return
        if (!current.canVerify) return
        if (!scopeAllows(current.action)) return
        if (deps.verifyGate.active) {
            mutable.value = current.copy(error = notice(RATE_KEY))
            return
        }
        val account = current.owner.account ?: return
        val gen = generation
        val epoch = current.owner.epoch
        mutable.value = current.copy(phase = StepUpUiState.VERIFYING, error = null)
        val body = JsonObject(mapOf("action" to JsonPrimitive(current.action), "code" to JsonPrimitive(current.code))).toString()
        val probe = deps.verifyGate.probe()
        // R3-01、共性第 43 条：验证请求发起时记下 token 失效世代；回包写缓存、交付 onToken 之前复核
        val tokenGen = deps.tokenGeneration()
        // 发包前由网络层按 expectedAccount 核对账号、按面板世代核对归属
        val callOwner = ownership(gen, epoch, probe::allowed)
        scope.launch {
            // 带点击时的账号（共性第 39 条）：会话已换成别的账号的不发
            val result = deps.api.send(ApiCall(VERIFY_OPERATION, body = body, ownership = callOwner, expectedAccount = account))
            owners -= callOwner
            // 42901 先按归属写入共享禁用期，再看面板是否有效（共性第 27、40 条）
            if (deps.env.ownedBy(account, epoch)) settleRetryAfter(result, deps.verifyGate)
            if (!valid(gen, epoch)) return@launch
            onVerified(result, account, current.action, epoch, probe, tokenGen)
        }
    }

    @Suppress("LongParameterList", "ReturnCount") // 固定身份与发起时的失效世代逐项；没有面板、失效丢弃、成功逐项返回
    private fun onVerified(
        result: ApiResult,
        account: String,
        action: String,
        epoch: Long,
        probe: SendProbe,
        tokenGen: Long,
    ) {
        val current = mutable.value ?: return
        val data = (result as? ApiResult.Success)?.let { WalletJson.stepUp(it.data) }
        if (data != null && deps.tokenGeneration() != tokenGen) {
            // 验证在途期间别处已让 token 整体失效（换手机号成功、会话 / 作用域换代）：迟到的 token 不写缓存、不交付，面板留着让用户重新验证（G94）
            mutable.value = current.copy(phase = StepUpUiState.READY, code = "", error = notice(STALE_TOKEN_KEY))
            return
        }
        if (data != null) {
            // APP-13a：按账号、会话世代、作用域、action 隔离（StepUpTokenPolicy）；F-AND-37（FU-AND-0717）起钱包 action 同口径，不再走 APP-09 旧入口
            deps.tokens.put(action, account, data.token, data.expireAtMillis, epoch, deps.env.sessionScope())
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
        limitTimer?.cancel()
        limitTimer = null
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
                if (probe.gated(error)) notice(RATE_KEY) else null
            }

            // 禁用期已在结果回来时按归属写入，这里只给提示
            WalletError.RETRY_AFTER -> {
                notice(RATE_KEY)
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
        const val PHONE_CHANGE = "phone_change"
        const val ACCOUNT_DELETION = "account_deletion"
        const val DEFAULT_RESEND_SEC = 60L
        const val RATE_KEY = "error.42901"

        /** 迟到的验证成功因 token 已整体失效被丢弃时的提示（G94：复用 10003「请先完成短信验证」）。 */
        const val STALE_TOKEN_KEY = "error.10003"

        /** 受限会话下非 account_deletion 的本地拒绝（草稿键，APP-13a AccountDraftTexts 提供；缺键回落 step_up.no_phone）。 */
        const val SCOPE_DENIED_KEY = "step_up.scope_denied"
        private const val WRONG_CODE = 20002
        private const val EXPIRED_CODE = 20003
        private const val MILLIS = 1_000L
        private const val TICK_MILLIS = 1_000L

        /** 读屏用的 trace（测试接线）。 */
        fun trace6(traceId: String?): String = ResultSignals.trace6(traceId)

        /** 面板标题的文案键：`step_up.title.<action>`，缺键回落 `step_up.title`（APP-13a §2 第 7 项）。 */
        fun titleKey(
            action: String,
            has: (String) -> Boolean,
        ): String = "step_up.title.$action".takeIf(has) ?: "step_up.title"
    }
}
