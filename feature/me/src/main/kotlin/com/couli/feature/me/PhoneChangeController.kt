@file:Suppress("ReturnCount", "CyclomaticComplexMethod", "ComplexCondition", "MagicNumber")

package com.couli.feature.me

import com.couli.core.me.account.AccountRaw
import com.couli.core.me.account.AccountResult
import com.couli.core.me.account.BindPhonePendingPolicy
import com.couli.core.me.account.KeyedDeadlines
import com.couli.core.me.account.PendingSettlement
import com.couli.core.me.account.PhoneChangeOutcome
import com.couli.core.me.account.PhoneChangeOutcomePolicy
import com.couli.core.me.account.PhoneChangeStep1Policy
import com.couli.core.me.account.PhoneMask
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PendingTarget
import com.couli.core.wallet.SendProbe
import com.couli.core.wallet.WalletJson
import com.couli.core.wallet.stepup.SmsDeadlines
import com.couli.core.wallet.stepup.StepUpController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 换号子页的状态（PhoneChange、PhoneChangeNew、PhoneChangeDone、PhoneChangeNewBlocked、PhoneChangePending*）。[formGen] 是第 2 步表单世代：返回第 1 步、
 * 换代时推进，旧表单的发码 / 提交回调与迟到结果不再改它（共性第 27、28 条，F-HM-32）。
 */
data class PhoneChangeState(
    val step: String = STEP1,
    val code1: String = "",
    val sending1: Boolean = false,
    val verifying: Boolean = false,
    val step1Notice: MeNotice? = null,
    /** 「验证码已发送」polite 播报（倒计时不逐秒读，§10）。 */
    val sentNotice: MeNotice? = null,
    val newPhone: String = "",
    val code2: String = "",
    val sending2: Boolean = false,
    val submitting: Boolean = false,
    val fieldErrorRaw: String = PhoneChangeOutcome.FIELD_NONE,
    val fieldError: MeNotice? = null,
    val error: MeNotice? = null,
    /** 30414 等之后给【联系客服】（G05）。 */
    val showContact: Boolean = false,
    /** 44001 弹窗（标题键 + 一次性编号）。 */
    val riskDialog: MeDialog? = null,
    val pending: AccountPending? = null,
    val pendingVersion: Long = 0,
    val pendingNotice: MeNotice? = null,
    val pendingUnreadable: Boolean = false,
    val abandonDialog: MeDialog? = null,
    /** 别的页面（BindPhone）正在提交同一操作（G63）：【提交】置灰。 */
    val inFlightElsewhere: Boolean = false,
    /** Done 页的号码：实际发送的固定脱敏摘要（共性第 17 条）；取不到为 null。 */
    val doneMasked: String? = null,
    val guideRoute: String? = null,
    val toast: MeNotice? = null,
    val formGen: Long = 0,
    val version: Long = 0,
) {
    companion object {
        const val STEP1 = "step1"
        const val STEP2 = "step2"
        const val DONE = "done"
        const val PENDING = "pending"
    }
}

/**
 * 换号子页执行器（Settings 页内子页，不注册路由，G22；brief §2 第 5 项；BR-ID-06、BR-ID-08、BR-ID-10；flow-map §11.2）：
 * - 第 1 步：step_up 发码到本机按账号归属给的号码（取不到 → step_up.no_phone，G02）；发码与验证限流分开（共性第 26 条）；【下一步】stepUp phone_change，
 *   token 只放本流程实例的内存（G06、G23）。
 * - 第 2 步：新号原样去首尾空白（G33）；bind 发码按「账号 + 新号 + bind」限流；【提交】新键（held 沿用原键原体，G58）+ token（未过期才带）→ bindPhone。
 * - 结局按 PhoneChangeOutcomePolicy；成功走共享结算（清全部 token、刷新 getMe，共性第 9、13 条）。
 * - 待确认：确认（原键原体）/ 放弃（二次确认后作废）；别处结算按共享通知落地（共性第 24 条）。
 * - 身份固定（S0）：构造时取 [identity]；回调带渲染时的版本与表单世代，执行前只比较。写请求离页不取消，迟到结果不改已离开的页面。
 */
@Suppress("TooManyFunctions", "LargeClass")
class PhoneChangeController(
    private val deps: AccountDeps,
    val identity: AccountIdentity,
    /** 退出子页回设置页（只关发起的这一个子页）。 */
    private val exit: (PhoneChangeController) -> Unit,
) {
    private var left = false
    private var ids = 0L
    private var dialogIds = 0L
    private val mutable = MutableStateFlow(PhoneChangeState())
    val state: StateFlow<PhoneChangeState> = mutable.asStateFlow()
    private val s: PhoneChangeState get() = mutable.value

    /** 10003 时的二次验证面板（phone_change，同一个共享发码服务）。 */
    val stepUp = StepUpController(deps.stepUp, deps.service) { alive() }

    /** 第 1 步拿到的 token：只在本流程实例内存（G23）。[tokenGen] 是取得时的共享失效世代（R2-05）：别处 bindPhone 成功后世代变了即失效。 */
    private var token: String? = null
    private var tokenExpire = 0L
    private var tokenGen = 0L

    /** 保存本流程私有 token（记下此刻的共享失效世代，R2-05、共性第 39 条）。 */
    private fun keepToken(
        value: String,
        expireAt: Long,
    ) {
        token = value
        tokenExpire = expireAt
        tokenGen = deps.shared.tokenGeneration
    }

    /** 当前（或下次提交沿用的）键、原体与摘要（共性第 17 条：重放用提交开始时固定的这一份）。 */
    private var reuseKey: String? = null
    private var lastBody: String? = null
    private var lastSummary: String? = null
    private var stepUpReplayed = false
    private var localBusy = false
    private var heldRecord: PendingKeyRecord? = null

    /** 未决结算通知的订阅（离开时取消）。 */
    private var watcher: kotlinx.coroutines.Job? = null

    private var visible = true
    private var viewGen = 0L
    private var sentViewGen = 0L

    /**
     * 第 2 步新号的修订号（F-X-13 / APP-05b 共性 34，FU-AND-0717；鸿蒙 FU-HM-0722 phoneRev 同法）：新号输入的规范化号码每变一次加 1（清空再粘回算两次，
     * 等价写法不变）。发码时捕获；结果回来时修订号变了 → 只丢弃旧表单的提示 / 字段错误 / 播报 / 风控弹窗，共享倒计时与限流照常按发送的规范化号码记下，
     * 页面每秒按当前新号重算（[step2ResendLeft]）。
     */
    private var phoneRev = 0L

    fun alive(): Boolean = !left && identity.current(deps.env)

    private val onScreen: Boolean get() = shown(sentViewGen)

    /** 页面许可：仍有效、可见、且 [permit]（发起时的可见世代）之后没有被覆盖或进后台。发码与提交各自捕获自己的许可（R2-06、共性第 40 条）。 */
    private fun shown(permit: Long): Boolean = alive() && visible && viewGen == permit

    init {
        watcher = deps.service.launch { deps.shared.pendingSignals.collect(::onSettlement) }
        refreshPending()
    }

    private fun bound(version: Long): Boolean = alive() && s.version == version

    private fun boundForm(
        version: Long,
        formGen: Long,
    ): Boolean = bound(version) && s.formGen == formGen

    /** 本机按账号归属给的号码（G02）；没有为 null。 */
    private fun ownedPhone(): String? = identity.account?.let(deps.env::stepUpPhone)

    val phoneKnown: Boolean get() = ownedPhone() != null

    // ---------------------------------------------------------------- 第 1 步

    private fun slot(): SmsDeadlines.Slot? {
        val account = identity.account ?: return null
        val phone = ownedPhone() ?: return null
        return SmsDeadlines.Slot(account, phone, AccountRaw.PHONE_CHANGE)
    }

    /** 第 1 步【获取验证码】还要等几秒（成功倒计时）。 */
    fun step1ResendLeft(): Long = slot()?.let { ceilSec(deps.stepUp.sms.remaining(it, deps.clock())) } ?: 0L

    /** 第 1 步发码在 42901 禁用期内（只看发码这一把键，共性第 15、26 条）。 */
    fun step1SendLimited(): Boolean = slot()?.let { deps.stepUp.sms.limitedLeft(it, deps.clock()) > 0 } == true

    fun onCode1(
        text: String,
        version: Long,
    ) {
        if (!bound(version) || s.verifying) return
        update { it.copy(code1 = text.filter { c -> c in '0'..'9' }.take(CODE_LENGTH), step1Notice = null) }
    }

    @Suppress("ReturnCount")
    fun sendCode1(version: Long) {
        if (!bound(version) || s.step != PhoneChangeState.STEP1 || s.sending1) return
        val slot = slot()
        if (slot == null) {
            val o = PhoneChangeStep1Policy.of(PhoneChangeStep1Policy.SEND_CODE, null, phoneKnown = false)
            update { it.copy(step1Notice = MeNotice(o.textKey ?: NO_PHONE_KEY, ++ids)) }
            return
        }
        val sms = deps.stepUp.sms
        if (sms.limitedLeft(slot, deps.clock()) > 0) {
            update { it.copy(step1Notice = MeNotice(RATE_KEY, ++ids)) }
            return
        }
        if (sms.remaining(slot, deps.clock()) > 0) return
        val ticket = sms.acquire(slot) ?: return
        val epoch = identity.epoch
        val body =
            JsonObject(
                mapOf(
                    "phone" to JsonPrimitive(slot.phone),
                    "purpose" to JsonPrimitive(StepUpController.PURPOSE),
                    "action" to JsonPrimitive(AccountRaw.PHONE_CHANGE),
                ),
            ).toString()
        update { it.copy(sending1 = true, step1Notice = null) }
        // 发码不随页面关闭取消，只按会话归属结算（APP-09 第 40 条）
        val owner =
            CallOwnership(deps.api.epochs(), sendAllowed = { sms.limitedLeft(slot, deps.clock()) == 0L }) {
                deps.env.sessionEpoch() == epoch
            }
        deps.service.launch {
            try {
                val result = deps.api.send(ApiCall(SEND_SMS, body = body, ownership = owner, expectedAccount = slot.account))
                val outcome = PhoneChangeStep1Policy.of(PhoneChangeStep1Policy.SEND_CODE, AccountResult.of(result), phoneKnown = true)
                if (deps.env.sessionEpoch() == epoch && deps.env.account() == slot.account) {
                    if (result is ApiResult.Success) {
                        val sec = WalletJson.sendSmsCode(result.data)?.resendAfterSec?.takeIf { it > 0 } ?: DEFAULT_RESEND_SEC
                        sms.start(slot, deps.clock() + sec * MILLIS)
                    }
                    // APP-13a 共性 49（FU-AND-0717）：限流写入前复核完整固定身份（含作用域）——同世代作用域刷新后迟到的 42901 不写进新作用域的闸门
                    if (ownsWrite()) outcome.disableMs?.let { sms.rateLimit(slot, deps.clock() + it) }
                }
                if (!alive()) return@launch
                update { it.copy(sending1 = false) }
                if (result is ApiResult.Success) {
                    update { it.copy(sentNotice = MeNotice(CODE_SENT_KEY, ++ids, assertive = false)) }
                } else if (result is ApiResult.LocalReject || result is ApiResult.SessionEnded) {
                    Unit
                } else {
                    forceIfNeeded(result)
                    val traceId = (result as? ApiResult.Failure)?.traceId
                    val key = if (AccountResult.refreshOnly(result)) AccountRaw.RETRY_LATER_KEY else outcome.textKey ?: NETWORK_KEY
                    update { it.copy(step1Notice = MeNotice(key, ++ids, traceId, outcome.trace6 != null)) }
                }
            } finally {
                sms.release(slot, ticket)
            }
        }
    }

    /** 第 1 步【下一步】：stepUp phone_change；成功 token 存本流程内存，进第 2 步。 */
    @Suppress("ReturnCount", "LongMethod")
    fun verify(version: Long) {
        if (!bound(version) || s.step != PhoneChangeState.STEP1 || s.verifying) return
        if (s.code1.length != CODE_LENGTH) return
        if (!phoneKnown) {
            update { it.copy(step1Notice = MeNotice(NO_PHONE_KEY, ++ids)) }
            return
        }
        // R1-14、共性第 31 条：第 1 步验证与所有 StepUpSheet 共用同一份验证截止时间（二次验证面板的那一份），页面订阅、42901 结算、发送前检查都指向它
        val gate = deps.stepUp.verifyGate
        if (gate.active) {
            update { it.copy(step1Notice = MeNotice(RATE_KEY, ++ids)) }
            return
        }
        val account = identity.account ?: return
        val epoch = identity.epoch
        val code = s.code1
        val body = JsonObject(mapOf("action" to JsonPrimitive(AccountRaw.PHONE_CHANGE), "code" to JsonPrimitive(code))).toString()
        val probe = gate.probe()
        val owner = CallOwnership(deps.api.epochs(), sendAllowed = probe::allowed) { alive() }
        // R3-01、共性第 43 条：验证请求发起时记下 token 失效世代；回包保存私有 token、写共享缓存之前复核
        val tokenGen = deps.stepUp.tokenGeneration()
        update { it.copy(verifying = true, step1Notice = null) }
        deps.service.launch {
            val result = deps.api.send(ApiCall(VERIFY, body = body, ownership = owner, expectedAccount = account))
            val outcome = PhoneChangeStep1Policy.of(PhoneChangeStep1Policy.VERIFY, AccountResult.of(result), phoneKnown = true)
            // 共性 49：验证限流写入前复核完整固定身份（含作用域）
            if (deps.env.sessionEpoch() == epoch && deps.env.account() == account && ownsWrite()) outcome.disableMs?.let(gate::start)
            if (!alive()) return@launch
            update { it.copy(verifying = false) }
            val data = outcome.token
            if (outcome.advancesToStep2 && data != null && deps.stepUp.tokenGeneration() != tokenGen) {
                // 验证在途期间别处换号成功（或换代）已让 token 整体失效：迟到的 token 不保存、不写缓存、不进第 2 步，留在第 1 步重新验证（G94）
                update { it.copy(code1 = "", step1Notice = MeNotice(StepUpController.STALE_TOKEN_KEY, ++ids)) }
                return@launch
            }
            if (outcome.advancesToStep2 && data != null) {
                keepToken(data.token, data.expireAtMillis)
                deps.stepUp.tokens.put(AccountRaw.PHONE_CHANGE, account, data.token, data.expireAtMillis, epoch, identity.scope)
                update {
                    it.copy(
                        step = PhoneChangeState.STEP2,
                        code1 = "",
                        newPhone = "",
                        code2 = "",
                        formGen = it.formGen + 1,
                        version = it.version + 1,
                        error = null,
                    )
                }
                return@launch
            }
            if (result is ApiResult.LocalReject && !probe.gated(result)) return@launch
            forceIfNeeded(result)
            val key =
                when {
                    probe.gated(result) -> RATE_KEY
                    AccountResult.refreshOnly(result) -> AccountRaw.RETRY_LATER_KEY
                    else -> outcome.textKey ?: NETWORK_KEY
                }
            val traceId = (result as? ApiResult.Failure)?.traceId
            update {
                it.copy(
                    code1 = if (outcome.clearsCode) "" else it.code1,
                    step1Notice = MeNotice(key, ++ids, traceId, outcome.trace6 != null, assertive = outcome.assertive),
                )
            }
        }
    }

    /** 「原手机号不能用了？联系客服」/【联系客服】/【联系客服申诉】：统一客服出口。 */
    fun contactCs(version: Long) {
        if (bound(version)) deps.env.customerService(SETTINGS)
    }

    // ---------------------------------------------------------------- 第 2 步

    private fun bindKey(phone: String = s.newPhone.trim()): String? {
        val account = identity.account ?: return null
        if (phone.isEmpty()) return null
        return KeyedDeadlines.bindKey(account, phone, PURPOSE_BIND)
    }

    /** 新号的规范化形式（与 [KeyedDeadlines.bindKey] 同一口径）：判断输入是否换了号码（[phoneRev]）。 */
    private fun phoneForm(raw: String): String = raw.trim().let { PhoneMask.normalized(it) ?: it.filter(Char::isDigit) }

    /** 第 2 步发码还要等几秒（成功倒计时与 42901 同一把键）。 */
    fun step2ResendLeft(): Long = bindKey()?.let { ceilSec(deps.shared.bindSms.remaining(it)) } ?: 0L

    fun onNewPhone(
        text: String,
        version: Long,
        formGen: Long,
    ) {
        if (!boundForm(version, formGen) || s.submitting) return
        if (phoneForm(text) != phoneForm(s.newPhone)) phoneRev += 1
        update { it.copy(newPhone = text, fieldErrorRaw = PhoneChangeOutcome.FIELD_NONE, fieldError = null) }
    }

    fun onCode2(
        text: String,
        version: Long,
        formGen: Long,
    ) {
        if (!boundForm(version, formGen) || s.submitting) return
        update { it.copy(code2 = text.filter { c -> c in '0'..'9' }.take(CODE_LENGTH), fieldErrorRaw = PhoneChangeOutcome.FIELD_NONE) }
    }

    /** 第 2 步【获取验证码】：sendSmsCode {phone: 新号, purpose: bind}；结果只结算共享倒计时，表单换代后不更新旧表单（共性第 28 条）。 */
    @Suppress("ReturnCount")
    fun sendCode2(
        version: Long,
        formGen: Long,
    ) {
        if (!boundForm(version, formGen) || s.step != PhoneChangeState.STEP2 || s.sending2) return
        val phone = s.newPhone.trim()
        val key = bindKey(phone) ?: return
        val shared = deps.shared.bindSms
        if (shared.remaining(key) > 0) return
        val account = identity.account ?: return
        val epoch = identity.epoch
        val body = JsonObject(mapOf("phone" to JsonPrimitive(phone), "purpose" to JsonPrimitive(PURPOSE_BIND))).toString()
        val owner =
            CallOwnership(deps.api.epochs(), sendAllowed = { shared.remaining(key) == 0L }) { deps.env.sessionEpoch() == epoch }
        // R2-06：本次发码单独捕获页面许可（发起时的可见世代），不复用提交请求的 sentViewGen
        val permit = viewGen
        val rev = phoneRev
        update { it.copy(sending2 = true, error = null) }
        deps.service.launch {
            val result = deps.api.send(ApiCall(SEND_SMS, body = body, ownership = owner, expectedAccount = account))
            val input = AccountResult.of(result)
            if (deps.env.sessionEpoch() == epoch && deps.env.account() == account) {
                if (result is ApiResult.Success) {
                    val sec = WalletJson.sendSmsCode(result.data)?.resendAfterSec?.takeIf { it > 0 } ?: DEFAULT_RESEND_SEC
                    shared.start(key, sec * MILLIS)
                } else if (input?.code == RATE_LIMITED && ownsWrite()) {
                    // 共性 49：发码限流写入前复核完整固定身份（含作用域）
                    shared.start(key, input.retryAfterMillis ?: DEFAULT_RETRY_MS)
                }
            }
            if (!alive() || s.formGen != formGen) return@launch
            update { it.copy(sending2 = false) }
            // F-X-13：发码在途时改过号码（含清空再粘回）→ 旧表单的反馈全部丢弃；倒计时 / 限流已在上面按发送的号码记下，页面按当前号码重算
            if (phoneRev != rev) return@launch
            when {
                result is ApiResult.Success -> {
                    update { it.copy(sentNotice = MeNotice(CODE_SENT_KEY, ++ids, assertive = false)) }
                }

                result is ApiResult.LocalReject || result is ApiResult.SessionEnded -> {
                    Unit
                }

                input?.code == RISK_BLOCKED -> {
                    openRisk(input.data, permit = permit)
                }

                input?.code == INVALID -> {
                    update { it.copy(fieldErrorRaw = PhoneChangeOutcome.FIELD_PHONE, fieldError = MeNotice(PHONE_INVALID_KEY, ++ids)) }
                }

                else -> {
                    forceIfNeeded(result)
                    val code = input?.code
                    val key2 =
                        code?.let { c -> if (c == RATE_LIMITED) RATE_KEY else "error.$c" }
                            ?: if (AccountResult.refreshOnly(result)) AccountRaw.RETRY_LATER_KEY else NETWORK_KEY
                    val traceId = (result as? ApiResult.Failure)?.traceId
                    update { it.copy(error = MeNotice(key2, ++ids, traceId, code != null && code / 10_000 == 5)) }
                }
            }
        }
    }

    /** 第 2 步【提交】：新键（held 沿用原键原体）+ 第 1 步 token（未过期才带）→ bindPhone。 */
    @Suppress("ReturnCount")
    fun submit(
        version: Long,
        formGen: Long,
    ) {
        if (!boundForm(version, formGen) || s.step != PhoneChangeState.STEP2 || s.submitting || s.inFlightElsewhere) return
        val phone = s.newPhone.trim()
        if (phone.isEmpty() || s.code2.length != CODE_LENGTH || s.pendingUnreadable) return
        if (deps.shared.phoneGate.active) {
            update { it.copy(error = MeNotice(RATE_KEY, ++ids)) }
            return
        }
        val account = identity.account ?: return
        val held = heldRecord?.takeIf { it.account == account }
        val key = held?.key ?: deps.newKey()
        // 共性第 17 条、G59：held 键沿用原体与原摘要（不回退到后来编辑的表单）
        val body = held?.body ?: JsonObject(mapOf("phone" to JsonPrimitive(phone), "code" to JsonPrimitive(s.code2))).toString()
        val summary = held?.summary ?: PhoneMask.summary(phone).orEmpty()
        reuseKey = key
        lastBody = body
        lastSummary = summary
        stepUpReplayed = false
        update {
            it.copy(
                submitting = true,
                error = null,
                fieldError = null,
                fieldErrorRaw = PhoneChangeOutcome.FIELD_NONE,
                guideRoute = null,
            )
        }
        send(account, key, body, summary, currentToken())
    }

    /** 未过期、且取得后没有发生 bindPhone 成功等共享失效（世代没变）才带（R2-05）。 */
    private fun currentToken(): String? = token?.takeIf { deps.clock() < tokenExpire && tokenGen == deps.shared.tokenGeneration }

    private fun send(
        account: String,
        key: String,
        body: String,
        summary: String,
        stepUpToken: String?,
    ) {
        if (!alive()) return
        sentViewGen = viewGen
        val epoch = identity.epoch
        val probe = deps.shared.phoneGate.probe()
        // R3-03、共性第 45 条：换号提交的持续归属是构造时的完整固定身份（离页不取消；作用域等任一分量变了尚未写出的不发）
        // RC-02：发送前比完整固定身份，已写出的同账号同登录世代照常结算
        val owner = writeOwnership(probe::allowed)
        val formGen = s.formGen
        deps.service.launch {
            val result =
                deps.api.send(
                    ApiCall(
                        BIND_PHONE,
                        body = body,
                        idempotencyKey = key,
                        stepUpToken = stepUpToken,
                        summary = summary,
                        ownership = owner,
                        expectedAccount = account,
                    ),
                )
            val outcome = AccountResult.of(result)?.let { PhoneChangeOutcomePolicy.of(AccountRaw.SUBMIT, it) }
            settleShared(account, epoch, key, summary, result, outcome)
            if (!alive()) return@launch
            update { it.copy(submitting = false) }
            if (outcome == null) {
                localRejected(result, probe)
                return@launch
            }
            applyOutcome(outcome, result, AccountRaw.SUBMIT, summary, null, formGen, sentPhone = sentPhoneOf(body))
        }
    }

    /** 这次实际发出的新号（请求体里的 phone；held 重发时是原提交的号码）。解析不出为空串（与任何输入都不一致）。 */
    private fun sentPhoneOf(body: String): String =
        runCatching {
            (
                (
                    kotlinx.serialization.json.Json
                        .parseToJsonElement(body) as? JsonObject
                )?.get("phone") as? JsonPrimitive
            )?.takeIf { it.isString }
                ?.content
        }.getOrNull().orEmpty()

    /**
     * 号码 / 验证码类的字段反馈（F-X-15 / APP-05b 共性 36、37）：字段错误（20001 / 20002 / 20003）、44001 风控弹窗、3xxxx（30411 / 30414 等）。5xxxx、42901、
     * 未完成等非字段结局不在此列（照常显示提示与 trace6）。
     */
    private fun fieldClass(outcome: PhoneChangeOutcome): Boolean {
        if (outcome.fieldErrorRaw != PhoneChangeOutcome.FIELD_NONE || outcome.nextStateRaw == PhoneChangeOutcome.BLOCKED_DIALOG) return true
        val code = outcome.textKey?.removePrefix("error.")?.toIntOrNull() ?: return false
        return code in FIELD_CODES || code / CODE_CLASS == BUSINESS_CLASS
    }

    /** 共享结算（只按点击时的会话归属）：禁用期、token、强更、成功的共享结算（清全部 token、刷新 getMe）、未决通知。 */
    @Suppress("LongParameterList")
    private fun settleShared(
        account: String,
        epoch: Long,
        key: String,
        summary: String?,
        result: ApiResult,
        outcome: PhoneChangeOutcome?,
    ) {
        val current = deps.env.sessionEpoch() == epoch && deps.env.account() == account
        if (current && outcome != null) {
            // 共性 49（FU-AND-0717）：42901 写闸门前复核完整固定身份（账号 + 登录世代 + 作用域 + 隐私 / 环境世代）；同账号同世代作用域刷新后迟到的
            // 旧请求只做下面的网络结算，不写新作用域的闸门
            if (ownsWrite()) outcome.disableMs?.let(deps.shared.phoneGate::start)
            if (outcome.stepUpAction != null) deps.stepUp.tokens.on10003(AccountRaw.PHONE_CHANGE)
            if (outcome.nextStateRaw == PhoneChangeOutcome.FORCE_UPDATE) deps.env.forceUpdate()
        }
        if (outcome?.nextStateRaw == PhoneChangeOutcome.DONE && (result is ApiResult.Success || outcome.alreadyDone)) {
            deps.shared.onBindPhoneSucceeded(account, epoch, key, summary, PhoneChangeOutcome.DONE, outcome.textKey)
        } else {
            // RC-01、共性第 47 条：通知携带完整终结结局与 trace_id，恢复同键待确认的其他实例按结局落地
            val traceId = (result as? ApiResult.Failure)?.traceId
            deps.shared.notifyPending(
                PendingSettlement(account, epoch, OPERATION, key, outcome?.nextStateRaw, outcome?.textKey, summary, outcome, traceId),
            )
        }
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

                // R1-07、G62：只刷新的 10405 不是业务结果（键由存储保留），提示稍后再试，不进强更
                AccountResult.refreshOnly(result) -> AccountRaw.RETRY_LATER_KEY

                else -> NETWORK_KEY
            }
        update { it.copy(error = key?.let { k -> MeNotice(k, ++ids) }) }
        refreshPending()
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
    private fun applyOutcome(
        outcome: PhoneChangeOutcome,
        result: ApiResult,
        phase: String,
        summary: String?,
        intent: Intent?,
        formGen: Long,
        /** 第 2 步提交实际发出的新号（[sentPhoneOf]）；确认 / 放弃为 null（不按输入框归属）。 */
        sentPhone: String? = null,
    ) {
        if (outcome.nextKeyRaw != AccountRaw.SAME) reuseKey = null
        if (!outcome.keepsToken) {
            token = null
            tokenExpire = 0L
        }
        val traceId = (result as? ApiResult.Failure)?.traceId
        val message =
            outcome.textKey?.let { MeNotice(it, ++ids, traceId, outcome.trace6 != null, serverMsg = outcome.serverMsg) }
                ?: outcome.serverMsg?.let { MeNotice(MeNotice.UNFINISHED_KEY, ++ids, serverMsg = it) }
        refreshPending()
        // F-X-15（FU-AND-0717，鸿蒙 FU-HM-0722 R1-05 / R2-02 同法）：held 重发的是原提交的号码；用户已改成别的号码时，号码 / 验证码类反馈按实际发出的号码归属，
        // 不落到当前输入（输入保留、共享结算已在上面照常完成）；非字段结局（5xxxx、42901 等）照常显示
        val foreign = sentPhone != null && phoneForm(sentPhone) != phoneForm(s.newPhone)
        if (foreign && outcome.nextStateRaw != PhoneChangeOutcome.DONE && fieldClass(outcome)) return
        when (outcome.nextStateRaw) {
            PhoneChangeOutcome.DONE -> {
                toDone(summary)
                if (outcome.alreadyDone) toast(ALREADY_DONE_KEY)
            }

            PhoneChangeOutcome.STEP1 -> {
                backToStep1(message?.takeIf { outcome.textKey != AccountRaw.ABANDONED_KEY })
                if (outcome.textKey == AccountRaw.ABANDONED_KEY) toast(AccountRaw.ABANDONED_KEY)
            }

            PhoneChangeOutcome.PENDING_CONFIRM -> {
                if (outcome.autoResendSameKey && outcome.stepUpAction != null) {
                    stepUpOrError(phase, message, intent)
                } else {
                    if (phase != AccountRaw.SUBMIT && outcome.textKey != AccountRaw.TITLE_KEY) update { it.copy(pendingNotice = message) }
                    guide(outcome.guideRoute)
                }
            }

            PhoneChangeOutcome.BLOCKED_DIALOG -> {
                if (s.formGen == formGen) update { it.copy(code2 = "") }
                openRisk(null, outcome.dialogTitleKey)
            }

            PhoneChangeOutcome.FORCE_UPDATE -> {
                if (phase != AccountRaw.SUBMIT) update { it.copy(pendingNotice = message) }
            }

            else -> {
                // step2：字段错误（20001 / 20002 / 20003）、30411、30414（+【联系客服】）、42901、5xxxx、补办码、10003（重放）
                if (outcome.autoResendSameKey && outcome.stepUpAction != null) {
                    stepUpOrError(phase, message, intent)
                    return
                }
                if (s.formGen != formGen && phase == AccountRaw.SUBMIT) return
                val field = outcome.fieldErrorRaw
                update {
                    it.copy(
                        // R3-02、共性第 44 条：待确认的确认 / 放弃得到终结业务错误（30411 / 30414、completed 原 3xxxx 等）→ 恢复第 2 步的提交入口，
                        // 新号 / 验证码 / token 按结局策略保留；不沿用 refreshPending 因记录结束而落到的第 1 步（只有 abandoned / 20903 回第 1 步）
                        step = if (phase != AccountRaw.SUBMIT && it.step != PhoneChangeState.DONE) PhoneChangeState.STEP2 else it.step,
                        code2 = if (outcome.clearsCode) "" else it.code2,
                        newPhone = if (outcome.keepsNewPhone) it.newPhone else "",
                        fieldErrorRaw = field,
                        fieldError = if (field != PhoneChangeOutcome.FIELD_NONE) message else null,
                        error = if (field == PhoneChangeOutcome.FIELD_NONE) message else null,
                        showContact = outcome.textKey == "error.30414",
                    )
                }
                if (outcome.alreadyDone) toast(ALREADY_DONE_KEY)
                guide(outcome.guideRoute)
            }
        }
    }

    private fun stepUpOrError(
        phase: String,
        message: MeNotice?,
        intent: Intent?,
    ) {
        val replayed = if (phase == AccountRaw.SUBMIT) stepUpReplayed else intent?.stepUpReplayed == true
        if (replayed || !onScreen) {
            if (phase == AccountRaw.SUBMIT) {
                reuseKey = null
                update { it.copy(error = message ?: MeNotice("error.10003", ++ids)) }
            } else {
                update { it.copy(pendingNotice = message ?: MeNotice("error.10003", ++ids)) }
            }
            return
        }
        val account = identity.account ?: return
        if (phase == AccountRaw.SUBMIT) update { it.copy(submitting = true) }
        stepUp.open(
            AccountRaw.PHONE_CHANGE,
            identity.owner,
            onToken = { fresh ->
                if (!alive()) return@open
                keepToken(fresh, deps.clock() + TOKEN_TTL_MS)
                if (phase == AccountRaw.SUBMIT) {
                    val key = reuseKey
                    val body = lastBody
                    if (key == null || body == null) {
                        update { it.copy(submitting = false) }
                    } else {
                        stepUpReplayed = true
                        send(account, key, body, lastSummary.orEmpty(), fresh)
                    }
                } else if (intent != null) {
                    refreshPending()
                    if (s.pending?.key != intent.key) return@open
                    intent.stepUpReplayed = true
                    runConfirm(intent, fresh)
                }
            },
            onClosed = {
                if (phase == AccountRaw.SUBMIT) {
                    reuseKey = null
                    update { it.copy(submitting = false) }
                }
                refreshPending()
            },
        )
    }

    private fun toDone(summary: String?) {
        token = null
        tokenExpire = 0L
        deferredRisk = null
        stepUp.dismiss()
        update {
            it.copy(
                step = PhoneChangeState.DONE,
                doneMasked = summary?.takeIf { s -> s.isNotEmpty() } ?: it.pending?.summary?.takeIf { s -> s.isNotEmpty() },
                code1 = "",
                code2 = "",
                pending = null,
                abandonDialog = null,
                riskDialog = null,
                version = it.version + 1,
            )
        }
    }

    /**
     * 回第 1 步：清空新号、验证码，丢弃 token，作废旧表单（共性第 24、27、28 条，G23）；第 2 步的全部瞬时状态（发码在途 sending2、字段错误、待展示风控结局）
     * 一并重置（R1-09、共性第 35 条）。旧表单的迟到回包只结算共享倒计时，不再改页面。
     */
    private fun backToStep1(notice: MeNotice?) {
        token = null
        tokenExpire = 0L
        reuseKey = null
        deferredRisk = null
        stepUp.dismiss()
        update {
            it.copy(
                step = PhoneChangeState.STEP1,
                code1 = "",
                newPhone = "",
                code2 = "",
                sending2 = false,
                fieldError = null,
                fieldErrorRaw = PhoneChangeOutcome.FIELD_NONE,
                error = null,
                step1Notice = notice,
                showContact = false,
                riskDialog = null,
                formGen = it.formGen + 1,
                version = it.version + 1,
            )
        }
    }

    private fun openRisk(
        data: kotlinx.serialization.json.JsonElement?,
        titleKey: String? = null,
        permit: Long = sentViewGen,
    ) {
        val title =
            titleKey ?: PhoneChangeOutcomePolicy.riskTitle(
                ((data as? JsonObject)?.get("risk_msg_code") as? JsonPrimitive)?.takeIf { it.isString }?.content,
            )
        // 异步打开弹窗前核对页面可见性与导航世代（共性第 11 条）：有效且可见立即展示；隐藏时保存绑定原身份、表单与页面实例的待展示结局，回到有效页面再展示一次（R1-10、R2-06）
        if (shown(permit)) {
            update { it.copy(riskDialog = MeDialog(title, ++dialogIds), code2 = "") }
        } else {
            deferredRisk = DeferredRisk(title, s.version, s.formGen)
            update { it.copy(code2 = "") }
        }
    }

    /** 隐藏期间收到的 44001（R1-10）：绑定发起时的页面版本与表单世代；出栈、换代（控制器作废）、回第 1 步、结果页时作废。 */
    private class DeferredRisk(
        val title: String,
        val version: Long,
        val formGen: Long,
    )

    private var deferredRisk: DeferredRisk? = null

    /** 回到有效页面：同一页面实例、同一表单仍在，才展示一次待展示的风控弹窗（完整说明与【联系客服申诉】）。 */
    private fun showDeferredRisk() {
        val deferred = deferredRisk ?: return
        if (!alive() || !visible) return
        deferredRisk = null
        if (s.version != deferred.version || s.formGen != deferred.formGen) return
        update { it.copy(riskDialog = MeDialog(deferred.title, ++dialogIds)) }
    }

    fun dismissRisk(dialog: MeDialog) {
        if (s.riskDialog == dialog) update { it.copy(riskDialog = null) }
    }

    fun appealRisk(
        dialog: MeDialog,
        version: Long,
    ) {
        if (s.riskDialog != dialog) return
        update { it.copy(riskDialog = null) }
        contactCs(version)
    }

    private fun guide(route: String?) {
        route ?: return
        if (onScreen) deps.env.open(route) else update { it.copy(guideRoute = route) }
    }

    fun openGuide(version: Long) {
        val route = s.guideRoute ?: return
        if (!bound(version)) return
        update { it.copy(guideRoute = null) }
        deps.env.open(route)
    }

    /** 只在网络层已判定进强更（Effect = FORCE_UPDATE）时调强更出口；REFRESH_ONLY / 刷新失败沿用网络层结论（R1-07、共性第 12 条）。 */
    private fun forceIfNeeded(result: ApiResult) {
        if (AccountResult.forcesUpdate(result)) deps.env.forceUpdate()
    }

    // ---------------------------------------------------------------- 待确认

    /**
     * 按本账号 bindPhone 的未决记录同步（进入、回前台、别处结算）：结果未知 → 待确认卡（[entry] 时跳过第 1 步，flow-map §11）；held → 下次提交沿用原键原体；
     * 别处在途（G63）→【提交】置灰。
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
        val restore =
            BindPhonePendingPolicy.restoreRecords(
                listOfNotNull(record),
                identity.account.orEmpty(),
                BindPhonePendingPolicy.ENTRY_PHONE_CHANGE,
            )
        val unknown = restore.record ?: record?.takeIf { it.stateRaw == PendingKeyStore.IN_FLIGHT && it.everUnknown }
        heldRecord = record?.takeIf { it.stateRaw == PendingKeyStore.HELD }
        val elsewhere = record?.stateRaw == PendingKeyStore.IN_FLIGHT && !record.everUnknown && !s.submitting
        update { state ->
            if (unknown == null) {
                val cleared = state.pending != null
                state.copy(
                    pending = null,
                    pendingVersion = if (cleared) state.pendingVersion + 1 else state.pendingVersion,
                    pendingNotice = if (cleared) null else state.pendingNotice,
                    abandonDialog = if (cleared) null else state.abandonDialog,
                    step = if (state.step == PhoneChangeState.PENDING) PhoneChangeState.STEP1 else state.step,
                    pendingUnreadable = false,
                    inFlightElsewhere = elsewhere,
                )
            } else {
                val fresh = state.pending?.key != unknown.key
                val busy = unknown.stateRaw == PendingKeyStore.IN_FLIGHT || (!fresh && localBusy)
                state.copy(
                    step = if (state.step == PhoneChangeState.DONE) state.step else PhoneChangeState.PENDING,
                    pending = AccountPending(unknown.key, unknown.createdAtMillis, busy, unknown.summary),
                    pendingVersion = if (fresh) state.pendingVersion + 1 else state.pendingVersion,
                    pendingUnreadable = false,
                    inFlightElsewhere = false,
                )
            }
        }
    }

    /**
     * 共享未决通知（共性第 24、47 条）：同一键在别处结算 → abandoned / 20903 回第 1 步重新验证；已完成 → 结果页（号码取通知里的摘要）；
     * 终结业务错误 → 恢复第 2 步（[restoreStep2Elsewhere]）。
     */
    private fun onSettlement(settlement: PendingSettlement) {
        if (settlement.operationId != OPERATION || !alive() || settlement.account != identity.account) return
        val mine = s.pending?.key
        if (settlement.key != null && settlement.key == mine && !localBusy) {
            when (settlement.nextStateRaw) {
                PhoneChangeOutcome.STEP1 -> {
                    backToStep1(null)
                    toast(AccountRaw.ABANDONED_KEY)
                    refreshPending()
                    return
                }

                PhoneChangeOutcome.DONE -> {
                    toDone(settlement.summary)
                    return
                }

                else -> {
                    if (restoreStep2Elsewhere(settlement)) return
                }
            }
        }
        refreshPending()
    }

    /**
     * RC-01、共性第 47 条：别处（另一个换号实例或 BindPhone 页）对本实例恢复的同一键得到终结业务错误（确认 30411 / 30414、放弃 completed 原 3xxxx 等，
     * 键已结束、落在第 2 步）→ 先按存储同步，再恢复第 2 步提交入口、错误提示（30414 给【联系客服】）与字段保留策略（G95：本实例从未填过的字段为空）。
     * 不沿用 refreshPending 因记录结束落到的第 1 步。处理了返回 true。
     */
    private fun restoreStep2Elsewhere(settlement: PendingSettlement): Boolean {
        val outcome = settlement.outcome ?: return false
        if (!outcome.keyEnds || outcome.nextStateRaw != PhoneChangeOutcome.STEP2) return false
        refreshPending()
        if (s.pending != null || s.step == PhoneChangeState.DONE) return true
        reuseKey = null
        if (!outcome.keepsToken) {
            token = null
            tokenExpire = 0L
        }
        val message =
            outcome.textKey?.let { MeNotice(it, ++ids, settlement.traceId, outcome.trace6 != null, serverMsg = outcome.serverMsg) }
                ?: outcome.serverMsg?.let { MeNotice(MeNotice.UNFINISHED_KEY, ++ids, serverMsg = it) }
        val field = outcome.fieldErrorRaw
        update {
            it.copy(
                step = PhoneChangeState.STEP2,
                code2 = if (outcome.clearsCode) "" else it.code2,
                newPhone = if (outcome.keepsNewPhone) it.newPhone else "",
                fieldErrorRaw = field,
                fieldError = if (field != PhoneChangeOutcome.FIELD_NONE) message else null,
                error = if (field == PhoneChangeOutcome.FIELD_NONE) message else null,
                showContact = outcome.textKey == "error.30414",
                pendingNotice = null,
            )
        }
        if (outcome.alreadyDone) toast(ALREADY_DONE_KEY)
        outcome.guideRoute?.let { route -> update { it.copy(guideRoute = route) } }
        return true
    }

    private class Intent(
        val account: String,
        val epoch: Long,
        val key: String,
        val summary: String,
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
        return Intent(account, identity.epoch, key, pending.summary)
    }

    private fun target(intent: Intent): PendingTarget =
        PendingTarget(
            intent.account,
            intent.key,
            // R3-03：确认 / 放弃同样持有完整固定身份；作用域变了不发，记录保持待确认
            // RC-02：已写出的确认 / 放弃同账号同登录世代照常结算
            writeOwnership { !deps.shared.phoneGate.active },
        )

    /** 写请求的持续归属（R3-03）：只比较构造时的完整固定身份，与页面可见性、离页无关。 */
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

    /** 【确认上一次提交】：原键、原体（含原验证码）重发。 */
    fun confirm(
        key: String,
        version: Long,
    ) {
        val intent = intentFor(key, version) ?: return
        if (deps.shared.phoneGate.active) {
            update { it.copy(pendingNotice = MeNotice(AccountRaw.RETRY_LATER_KEY, ++ids)) }
            return
        }
        runConfirm(intent, currentToken())
    }

    private fun runConfirm(
        intent: Intent,
        stepUpToken: String?,
    ) {
        if (!alive()) return
        val pendingVersion = s.pendingVersion
        sentViewGen = viewGen
        markInFlight(true)
        deps.service.launch {
            val result = deps.api.confirmPending(BIND_PHONE, stepUpToken, target(intent))
            val stale = s.pendingVersion != pendingVersion
            val outcome = AccountResult.of(result)?.let { PhoneChangeOutcomePolicy.of(AccountRaw.CONFIRM, it) }
            settleShared(intent.account, intent.epoch, intent.key, intent.summary, result, outcome)
            if (!alive()) return@launch
            markInFlight(false)
            if (stale && outcome?.nextStateRaw != PhoneChangeOutcome.DONE) {
                refreshPending()
                return@launch
            }
            if (outcome == null) {
                update { it.copy(pendingNotice = MeNotice(AccountRaw.RETRY_LATER_KEY, ++ids)) }
                refreshPending()
                return@launch
            }
            applyOutcome(outcome, result, AccountRaw.CONFIRM, intent.summary, intent, s.formGen)
        }
    }

    fun askAbandon(
        key: String,
        version: Long,
    ) {
        val pending = s.pending?.takeIf { it.key == key } ?: return
        if (!bound(version) || pending.inFlight) return
        if (deps.shared.phoneGate.active) {
            update { it.copy(pendingNotice = MeNotice(AccountRaw.RETRY_LATER_KEY, ++ids)) }
            return
        }
        update { it.copy(abandonDialog = MeDialog(key, ++dialogIds)) }
    }

    fun cancelAbandon(dialog: MeDialog) {
        if (s.abandonDialog == dialog) update { it.copy(abandonDialog = null) }
    }

    /** 【确定放弃】：作废接口（action=phone_change）。 */
    fun abandon(
        dialog: MeDialog,
        version: Long,
    ) {
        if (s.abandonDialog != dialog) return
        update { it.copy(abandonDialog = null) }
        val intent = intentFor(dialog.kind, version) ?: return
        if (deps.shared.phoneGate.active) {
            update { it.copy(pendingNotice = MeNotice(AccountRaw.RETRY_LATER_KEY, ++ids)) }
            return
        }
        val pendingVersion = s.pendingVersion
        sentViewGen = viewGen
        markInFlight(true)
        deps.service.launch {
            val result = deps.api.abandonPending(BIND_PHONE, target(intent))
            val stale = s.pendingVersion != pendingVersion
            val outcome = AccountResult.ofAbandon(result)?.let { PhoneChangeOutcomePolicy.of(AccountRaw.ABANDON, it) }
            settleShared(intent.account, intent.epoch, intent.key, intent.summary, result, outcome)
            if (!alive()) return@launch
            markInFlight(false)
            if (stale && outcome?.nextStateRaw !in setOf(PhoneChangeOutcome.DONE, PhoneChangeOutcome.STEP1)) {
                refreshPending()
                return@launch
            }
            if (outcome == null) {
                update { it.copy(pendingNotice = MeNotice(AccountRaw.RETRY_LATER_KEY, ++ids)) }
                refreshPending()
                return@launch
            }
            applyOutcome(outcome, result, AccountRaw.ABANDON, intent.summary, null, s.formGen)
        }
    }

    private fun markInFlight(busy: Boolean) {
        localBusy = busy
        update { state ->
            val pending = state.pending ?: return@update state
            state.copy(pending = pending.copy(inFlight = busy), pendingNotice = if (busy) null else state.pendingNotice)
        }
    }

    // ---------------------------------------------------------------- 返回、完成、离开

    /**
     * 返回（页面返回与系统返回同一处理，共性第 27 条、G67）：第 2 步且没在提交、没开二次验证 → 回第 1 步（清空、丢 token、重新验证）；其余退出到设置。
     */
    fun back(version: Long) {
        if (!bound(version)) return
        if (s.step == PhoneChangeState.STEP2 && !s.submitting && !stepUp.showing) {
            backToStep1(null)
            return
        }
        exit(this)
    }

    /** Done【完成】：回设置页（只关发起的这一个子页）。 */
    fun finish(version: Long) {
        if (bound(version) && s.step == PhoneChangeState.DONE) exit(this)
    }

    fun onVisibility(shown: Boolean) {
        if (shown != visible) viewGen += 1
        visible = shown
        if (shown) showDeferredRisk()
    }

    fun consumeToast(id: Long) {
        if (s.toast?.id == id) update { it.copy(toast = null) }
    }

    fun consumeSent(id: Long) {
        if (s.sentNotice?.id == id) update { it.copy(sentNotice = null) }
    }

    private fun toast(key: String) = update { it.copy(toast = MeNotice(key, ++ids, assertive = false)) }

    /** 离开子页、换代：收起面板、丢 token、清空输入（验证码与 token 不保存）；写请求不取消。 */
    fun leave() {
        if (left) return
        left = true
        token = null
        tokenExpire = 0L
        deferredRisk = null
        stepUp.dismiss()
        watcher?.cancel()
        watcher = null
        mutable.value = PhoneChangeState(version = s.version + 1, formGen = s.formGen + 1)
    }

    private inline fun update(change: (PhoneChangeState) -> PhoneChangeState) {
        mutable.value = change(mutable.value)
    }

    private fun ceilSec(millis: Long): Long = if (millis <= 0) 0 else (millis + MILLIS - 1) / MILLIS

    companion object {
        const val OPERATION = "bindPhone"
        const val BIND_PHONE = "bindPhone"
        const val SEND_SMS = "sendSmsCode"
        const val VERIFY = "stepUp"
        const val PURPOSE_BIND = "bind"
        const val SETTINGS = "Settings"
        const val CODE_LENGTH = 6
        const val DEFAULT_RESEND_SEC = 60L
        const val DEFAULT_RETRY_MS = 5_000L

        /** 面板验证后 token 的本机保留上限（服务端 5 分钟，BR-ID-08；以 expire_at 为准的入口在第 1 步）。 */
        const val TOKEN_TTL_MS = 300_000L
        const val MILLIS = 1_000L
        const val RATE_LIMITED = 42901
        const val RISK_BLOCKED = 44001
        const val INVALID = 20001
        const val FORCE_UPDATE = 10405
        const val RATE_KEY = "error.42901"
        const val NO_PHONE_KEY = "step_up.no_phone"
        const val CODE_SENT_KEY = "phone_change.code_sent"

        /** 号码 / 验证码类字段错误码（F-X-15）。 */
        private val FIELD_CODES = setOf(20001, 20002, 20003, 44001)
        private const val CODE_CLASS = 10_000
        private const val BUSINESS_CLASS = 3
        const val PHONE_INVALID_KEY = "error.20001.phone_invalid"
        const val ALREADY_DONE_KEY = "pending_confirm.already_done"
        const val NETWORK_KEY = com.couli.core.me.MeDraftTexts.NETWORK_KEY
    }
}
