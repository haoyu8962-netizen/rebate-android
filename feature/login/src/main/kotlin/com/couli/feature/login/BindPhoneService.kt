// 错误码、秒数是契约 / 业务规则取值（error-codes、BR-ID-05），按值直接写
@file:Suppress("MagicNumber", "ReturnCount") // 本地校验、归属失效、各结果逐项提前返回

package com.couli.feature.login

import com.couli.core.me.account.AccountRaw
import com.couli.core.me.account.AccountResult
import com.couli.core.me.account.AccountShared
import com.couli.core.me.account.BindPhonePendingPolicy
import com.couli.core.me.account.PendingSettlement
import com.couli.core.me.account.PhoneChangeOutcome
import com.couli.core.me.account.PhoneChangeOutcomePolicy
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PendingTarget
import com.couli.core.network.ResultSignals
import com.couli.core.network.SessionTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 绑定的结果。[actionRaw]：[BindPhoneAction] 的 done / merge_confirm / retry_after / error，或 stale / busy / code_incomplete /
 * phone_invalid。[field]：要在旁边提示的字段（phone / code）；[maskedPhone] 并号确认里显示的手机号账号（脱敏）。
 */
data class BindOutcome(
    val actionRaw: String,
    val textKey: String? = null,
    val trace6: String = "",
    val disableMillis: Long = 0L,
    val field: String? = null,
    val maskedPhone: String = "",
    /** [OPEN_ROUTE] 时要经 Router 打开的页面（10007 stage=cooling → DeleteAccount，APP-05b 共性第 10 条）。 */
    val route: String? = null,
    /**
     * 这次实际发出的验证码（APP-05b 共性第 35 条）：held 键沿用原键原体时是原提交的码，不是调用方这次传入的输入；没有发出时为 null。
     * 页面只在它与发起时的输入一致时才把验证码相关的结果落到输入框。
     */
    val sentCode: String? = null,
) {
    companion object {
        const val OPEN_ROUTE = "open_route"

        /** APP-13a G07：结果未知（超时、断连、无外壳），页面按未决记录进入待确认卡。 */
        const val PENDING = "pending"
        const val STALE = "stale"
        const val BUSY = "busy"
        const val CODE_INCOMPLETE = "code_incomplete"
        const val PHONE_INVALID = "phone_invalid"
    }
}

/**
 * 绑手机页的完整固定身份（APP-13a R2-01，共性第 6、36 条）：账号 + 登录世代 + 会话作用域 + 隐私 / 环境世代。页面构造时一次取得，回调与结果只比较它；
 * 同账号刷新改变作用域、撤回同意、切换环境都是换代。
 */
data class BindPhoneOwner(
    val account: String?,
    val epoch: Long,
    val scope: String?,
    val epochs: CallEpochs,
)

/** [BindPhoneService] 要 App 提供的出口。 */
interface BindPhoneHost {
    /**
     * 绑定成功、getMe 已刷新（APP-13a R1-05：只做共享结算，不导航）：守卫的会话状态变为已绑手机、释放暂缓。出栈、提示与「完成前置」恢复待办
     * 由持有原页面许可的页面经 [BindPhonePageActions.onBound] 做，不按路由名关页（共性第 25 条，G66）。
     * [owner] 是发起绑定 / 确认时固定的完整身份（FU-AND-0716 R1-01）：服务在 getMe 返回后已核对过一次；App 收尾时须再核对它仍是当前会话，
     * 已换代（退出、另一账号登录）就什么都不做——不能解除新会话的暂缓。
     */
    fun phoneBound(owner: BindPhoneOwner)

    /** 没有绑定就离开绑手机页：暂缓的待办经守卫恢复（只恢复、不重置计时；仍需手机号的保留，不再自动拉起绑手机页）。 */
    fun leftUnbound() = Unit
}

/**
 * 绑手机页的执行器（APP-05b；BR-ID-05、BR-ID-06；openapi bindPhone、sendSmsCode purpose=bind、getMe）：
 * - 发码：purpose=bind；与短信登录共用按规范化号码的截止时间（成功 60 秒、42901 Retry-After），同一号码截止前不再发出（APP-05a 共性
 *   第 5 条）；50401 按错误提示（trace6）。
 * - 绑定：手机号 + 6 位码。首次绑定不带 step-up（契约：step-up 只用于换号）。成功 → 刷新 getMe（phone_bound）→ 通知会话状态变化并
 *   恢复待办；30411 mergeable（带 merge_ticket）→ 并号确认（不自动并号，ticket 只在内存）；其余按 [BindPhoneAction]。
 * - 并号：取消 → 不发请求、不并号、不保存会话；确认 → 锁定契约没有并号接口（TODO(N-29) MERGE-SUCCESS），不伪造请求，给出明确的
 *   「暂不可用」结果。
 * - 归属：页面离开、换号时 [invalidate]，迟到结果不改页面（请求不取消，共享结算照常，APP-13a R1-03）；发码与绑定在入口固定登录世代，首次派发与每次写出前都核对
 *   （另一账号的会话已保存 → 不发出，共性第 15 条）；绑定结果只记到发起时的那个会话。
 * 页面在主线程调用。
 */
@Suppress("TooManyFunctions", "LongParameterList") // 依赖逐项注入（单测替身）
class BindPhoneService(
    private val client: ApiClient,
    private val tokens: SessionTokens,
    private val login: LoginService,
    private val phoneBinding: PhoneBindingState,
    private val host: BindPhoneHost,
    /** Me 读取的会话级共享禁用期（APP-12 R2-05）：绑定成功后的 getMe 与「我的」页面共用一个截止时间。 */
    private val meRead: MeReadLimit = MeReadLimit.NONE,
    /**
     * 会话级任务的作用域（APP-13a R1-03，App 给主线程的会话作用域）：发码、绑定、确认、放弃连同全部共享结算（42901、清全部 token、绑定状态、getMe、
     * 未决通知）都在这里执行，页面离开（ViewModel 清除）不截断；页面只等待结局，并按自己的页面许可更新界面与导航。没给时（APP-05b 单测）在调用方协程里执行。
     */
    private val sessionScope: CoroutineScope? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var serial = 0L

    /**
     * 换号、注销与 BindPhone 的会话级共享状态（APP-13a，App 接线后设置；没接时按 APP-05b 行为）：bindPhone 的提交、确认、放弃共用 [AccountShared.phoneGate]
     * （共性第 8 条）；结局按固定身份结算后通知（共性第 18 条）；成功走共享结算（清全部 token、刷新 getMe，共性第 9 条）。
     */
    var shared: AccountShared? = null

    @Volatile
    private var sending = false

    @Volatile
    private var binding = false

    /** 并号凭据（30411 mergeable 的 merge_ticket）：只在内存，取消、离页、换号时清除。 */
    @Volatile
    private var mergeTicket: String? = null

    /**
     * 离开页面、换号：在途结果对页面作废、并号凭据清除。请求本身不取消（R1-03、共性第 21 条）：发码按会话归属结算（服务端已受理的截止时间照记），
     * 绑定、确认、放弃由未决记录兜底并照常做共享结算。
     */
    fun invalidate() {
        serial += 1
        mergeTicket = null
    }

    /** 会话同步出口的订阅者（R2-01）：打开着的绑手机页。只在主线程读写。 */
    private val sessionListeners = mutableListOf<() -> Unit>()

    /** 订阅会话同步出口（页面构造时）；返回取消订阅（页面清除时）。 */
    fun addSessionListener(listener: () -> Unit): () -> Unit {
        sessionListeners += listener
        return { sessionListeners -= listener }
    }

    /**
     * 真实会话、作用域、隐私、环境切换的同步出口（R2-01，共性第 36 条）：App 在会话保存 / 刷新 / 清理的通知、身份同步、撤回同意与切换环境的清理里调用。
     * 打开着的绑手机页在同一条主线程消息里按完整固定身份比较，换代即作废旧实例、清空手机号 / 验证码 / 待确认卡，不等计时检查或回到前台。
     */
    fun syncSession() {
        sessionListeners.toList().forEach { it() }
    }

    /** 会话级任务（R1-03）：有会话作用域时在那里执行，调用方（页面协程）取消只停止等待，不截断任务。 */
    private suspend fun <T> session(block: suspend () -> T): T {
        val scope = sessionScope ?: return block()
        return scope.async { block() }.await()
    }

    val busy: Boolean get() = sending || binding

    /** 有待确认的并号（30411 mergeable）。 */
    val mergePending: Boolean get() = mergeTicket != null

    /** 这个号码（任意写法）距可以再发码还有几秒；0 = 可以发（与登录页同一份截止时间）。 */
    fun resendRemainingSec(rawPhone: String): Int = gate(rawPhone, SmsCountdown.STATE_SENT)

    /** 这个号码（任意写法）成功发码后可以再发的真实截止时刻；没有在倒计时为 null。 */
    fun resendDeadline(rawPhone: String): Long? {
        val phone = PhoneNumber.normalize(rawPhone).normalized ?: return null
        val (until, kind) = login.sendGate(phone) ?: return null
        return until.takeIf { kind == SmsCountdown.STATE_SENT }
    }

    /** 这个号码的发码 42901 限流还剩几秒。 */
    fun sendLimitedRemainingSec(rawPhone: String): Int = gate(rawPhone, SmsCountdown.STATE_RATE_LIMITED)

    private fun gate(
        rawPhone: String,
        kind: String,
    ): Int {
        val phone = PhoneNumber.normalize(rawPhone).normalized ?: return 0
        val (until, gateKind) = login.sendGate(phone) ?: return 0
        return if (gateKind == kind) ceilSec(until - clock()) else 0
    }

    /** 绑手机发码（purpose=bind）。 */
    suspend fun sendCode(rawPhone: String): SendCodeOutcome {
        val phone =
            PhoneNumber.normalize(rawPhone).normalized
                ?: return SendCodeOutcome(FIELD_ERROR, 0, PHONE_INVALID_KEY, LoginErrorMapper.FIELD_PHONE, null)
        login.sendGate(phone)?.let { (until, kind) ->
            val remaining = ceilSec(until - clock())
            return if (kind == SmsCountdown.STATE_SENT) {
                SendCodeOutcome(LoginService.STATE_COUNTDOWN, remaining, null, null, phone)
            } else {
                SendCodeOutcome(SmsCountdown.STATE_RATE_LIMITED, remaining, RATE_LIMITED_KEY, null, phone)
            }
        }
        if (sending) return SendCodeOutcome(BindOutcome.BUSY, 0, null, null, null)
        val issued = serial
        // 共性第 15 条：入口固定登录世代；首次派发与每次写出前核对，换了账号就不发出
        val epoch = tokens.loginEpoch
        val body = JsonObject(mapOf("phone" to JsonPrimitive(phone), "purpose" to JsonPrimitive(PURPOSE_BIND))).toString()
        // 发码按会话归属结算（共性第 21 条、APP-09 第 40 条）：不随页面离开、换号取消
        val owner = CallOwnership(client.inFlight.epochs()) { tokens.loginEpoch == epoch }
        sending = true
        return session {
            try {
                sent(client.send(ApiCall(LoginErrorMapper.SEND_SMS_CODE, body = body, ownership = owner)), phone, issued, epoch)
            } finally {
                sending = false
            }
        }
    }

    private fun sent(
        result: ApiResult,
        phone: String,
        issued: Long,
        epoch: Long,
    ): SendCodeOutcome {
        val stale = issued != serial || tokens.loginEpoch != epoch
        return when (result) {
            is ApiResult.Success -> {
                val countdown = SmsCountdown.fromMillis(0, 0L, result.data as? JsonObject ?: JsonObject(emptyMap()))
                // 已被服务端受理：不论页面是否还在，这个号码的截止时间都要记下（与登录页共用）
                login.recordSendGate(phone, clock() + countdown.countdownSec * MILLIS, SmsCountdown.STATE_SENT)
                if (stale) {
                    SendCodeOutcome(BindOutcome.STALE, 0, null, null, null)
                } else {
                    SendCodeOutcome(countdown.stateRaw, countdown.countdownSec, null, null, phone)
                }
            }

            is ApiResult.Failure -> {
                val countdown = SmsCountdown.fromMillis(result.code, result.effect.disableMillis ?: 0L, result.data)
                if (countdown.stateRaw == SmsCountdown.STATE_RATE_LIMITED) {
                    login.recordSendGate(phone, clock() + countdown.countdownSec * MILLIS, SmsCountdown.STATE_RATE_LIMITED)
                }
                if (stale) return SendCodeOutcome(BindOutcome.STALE, 0, null, null, null)
                // 共性第 10、16 条：注销冷静期（10007 stage=cooling）→ 保留导航动作，由页面经 Router 打开注销进度页；processing 只提示
                navigation(result)?.let { route ->
                    return SendCodeOutcome(BindOutcome.OPEN_ROUTE, 0, result.effect.textKey, null, phone, route = route)
                }
                val mapped = BindPhoneAction.ofEffect(result.effect)
                val field = result.effect.fields?.firstOrNull()
                SendCodeOutcome(
                    countdown.stateRaw,
                    countdown.countdownSec,
                    mapped.textKey,
                    field,
                    phone,
                    ResultSignals.trace6(result.traceId),
                )
            }

            else -> {
                if (stale || result.isStale()) {
                    SendCodeOutcome(BindOutcome.STALE, 0, null, null, null)
                } else {
                    SendCodeOutcome(NETWORK_ERROR, 0, LoginService.GENERIC_KEY, null, phone)
                }
            }
        }
    }

    /** 绑定（首次绑定，不带 step-up）。 */
    @Suppress("LongMethod", "CyclomaticComplexMethod") // 本地校验、发出、成功刷新、各错误分流按顺序写在一处
    suspend fun bind(
        rawPhone: String,
        code: String,
        /** 页面固定的完整身份（R1-02、R2-01）；给了就只用它，已不是当前会话则不发出。 */
        owner: BindPhoneOwner? = null,
    ): BindOutcome {
        val phone =
            PhoneNumber.normalize(rawPhone).normalized
                ?: return BindOutcome(BindOutcome.PHONE_INVALID, PHONE_INVALID_KEY, field = LoginErrorMapper.FIELD_PHONE)
        if (!CodeInput.accept(code).complete) return BindOutcome(BindOutcome.CODE_INCOMPLETE, field = LoginErrorMapper.FIELD_CODE)
        if (binding) return BindOutcome(BindOutcome.BUSY)
        if (owner != null && !owns(owner)) return BindOutcome(BindOutcome.STALE)
        shared
            ?.phoneGate
            ?.takeIf {
                it.active
            }?.let { return BindOutcome(BindPhoneAction.RETRY_AFTER, RATE_LIMITED_KEY, disableMillis = it.remainingMillis()) }
        mergeTicket = null
        val issued = serial
        // 绑定结果只记到发起时的那个会话（另一账号的会话在等待中保存 → 不记到别人身上）
        // 共性第 15 条：首次派发与每次写出前都核对仍是这个会话（另一账号的会话已保存 → 不发出，不能用别人的令牌绑定）
        val epoch = owner?.epoch ?: tokens.loginEpoch
        // R1-01：发起时的完整固定身份（页面给的，或此刻的当前会话；登录世代先于其余各项取得，同原来的顺序）；成功后的共享收尾在 getMe 返回后按它再核对
        val fixed = owner ?: owner().copy(epoch = epoch)
        val account = fixed.account
        // G58：本账号保留着（held）的键沿用原键原体（不重新生成）；结果未知的键只能确认或放弃（页面显示待确认卡）
        val record = readPending()
        if (record?.stateRaw == PendingKeyStore.UNKNOWN) return BindOutcome(BindOutcome.PENDING)
        val held = record?.takeIf { it.stateRaw == PendingKeyStore.HELD && it.account == account }
        val key =
            held?.key ?: java.util.UUID
                .randomUUID()
                .toString()
        val body = held?.body ?: JsonObject(mapOf("phone" to JsonPrimitive(phone), "code" to JsonPrimitive(code))).toString()
        // 共性第 35 条：held 重发的是原请求体里的码（读不出时按「不是这次的输入」处理，验证码反馈不落到输入框）
        val sentCode = if (held == null) code else heldCode(held.body)
        val summary = held?.summary ?: PhoneNumber.masked(phone)
        val gate = shared?.phoneGate
        // 写请求离开页面不取消（共性第 21 条）：归属只核对会话世代与 42901 截止时间（发送前闸门），不登记进页面的作废列表
        val writeOwner =
            CallOwnership(client.inFlight.epochs(), sendAllowed = { gate?.active != true }) { tokens.loginEpoch == epoch }
        binding = true
        // R1-03：发送与全部共享结算是一个会话级任务；页面离开只停止等待
        return session {
            try {
                val result =
                    client.send(
                        ApiCall(
                            BindPhoneAction.BIND_OPERATION,
                            body = body,
                            idempotencyKey = key,
                            summary = summary,
                            ownership = writeOwner,
                            expectedAccount = account,
                        ),
                    )
                settleBind(result, phone, fixed, epoch, key, summary, issued).copy(sentCode = sentCode)
            } finally {
                binding = false
            }
        }
    }

    /** held 记录原请求体里的验证码；解析不出为空串（与任何输入都不一致）。 */
    private fun heldCode(body: String): String =
        runCatching {
            ((Json.parseToJsonElement(body) as? JsonObject)?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.getOrNull().orEmpty()

    /** 绑定的结算（会话级任务里执行）：共享部分只按发起时的会话，页面部分（返回值）按 [issued] 判作废。 */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
    private suspend fun settleBind(
        result: ApiResult,
        phone: String,
        fixed: BindPhoneOwner,
        epoch: Long,
        key: String,
        summary: String,
        issued: Long,
    ): BindOutcome {
        val account = fixed.account
        settleShared(account, epoch, key, summary, result, AccountRaw.SUBMIT)
        if (result is ApiResult.Success) {
            // 绑定已在服务端完成：不论页面是否还在，这个会话已绑手机；推进更新世代，绑定前发出的 getMe 回包作废（共性第 12 条）
            if (tokens.loginEpoch == epoch) phoneBinding.bindSucceeded(epoch)
            refreshMe(epoch)
            // R1-05：会话侧的共享结算（守卫状态、释放暂缓）照常；出栈与恢复待办交给持有原页面许可的页面
            // FU-AND-0716 R1-01：getMe 等待期间可能已退出、另一账号登录（新会话的待办正暂缓）——返回后重新核对发起时的完整身份
            if (stillFixed(fixed)) host.phoneBound(fixed)
            if (issued != serial || tokens.loginEpoch != epoch) return BindOutcome(BindOutcome.STALE)
            return BindOutcome(BindPhoneAction.DONE)
        }
        if (issued != serial || tokens.loginEpoch != epoch) return BindOutcome(BindOutcome.STALE)
        // G07：没有统一外壳（超时、断连、网关错误页）→ 结果未知，页面按未决记录进入待确认卡（替换 APP-05b 的 BIND-PENDING 通用提示）
        if (result is ApiResult.TransportFailure && !result.isStale()) return BindOutcome(BindOutcome.PENDING)
        // 共性第 10 条：注销冷静期（10007 stage=cooling）→ 经 Router 打开可撤回的注销进度页；processing 只提示（下面按错误文案）
        if (result is ApiResult.Failure) {
            navigation(result)?.let { route -> return BindOutcome(BindOutcome.OPEN_ROUTE, result.effect.textKey, route = route) }
        }
        return when (result) {
            is ApiResult.Failure -> {
                val mapped =
                    if (result.code == MERGEABLE_CODE) {
                        BindPhoneAction.ofCode(BindPhoneAction.BIND_OPERATION, result.code, result.data)
                    } else {
                        BindPhoneAction.ofEffect(result.effect)
                    }
                if (mapped.actionRaw == BindPhoneAction.MERGE_CONFIRM) {
                    mergeTicket = BindPhoneAction.mergeTicket(result.data)
                    BindOutcome(
                        mapped.actionRaw,
                        mapped.textKey,
                        ResultSignals.trace6(result.traceId),
                        maskedPhone = PhoneNumber.masked(phone),
                    )
                } else {
                    BindOutcome(
                        mapped.actionRaw,
                        mapped.textKey,
                        ResultSignals.trace6(result.traceId),
                        ResultSignals.disableMillis(result),
                        field = fieldOf(result.code, result.effect.fields),
                    )
                }
            }

            else -> {
                // 未决键未结（G07）：按未决记录进入待确认卡；作废、换代的不提示；其余本地拒绝提示通用错误
                when {
                    result.isStale() -> {
                        BindOutcome(BindOutcome.STALE)
                    }

                    (result as? ApiResult.LocalReject)?.reason == LocalRejectReason.PENDING_KEY_UNRESOLVED -> {
                        BindOutcome(
                            BindOutcome.PENDING,
                        )
                    }

                    else -> {
                        BindOutcome(BindPhoneAction.ERROR, LoginService.GENERIC_KEY)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- APP-13a：待确认（G07）

    /** 本账号 bindPhone 的未决记录（含 held）；存储读不出抛错（不当成没有记录，lessons 38）。 */
    fun readPending(): PendingKeyRecord? = client.pendingFor(BindPhoneAction.BIND_OPERATION)

    /** 待确认卡要显示的记录（只认本账号、结果未知的，BindPhonePendingPolicy）。读不出时抛错。 */
    fun pendingCard(account: String): PendingKeyRecord? =
        BindPhonePendingPolicy
            .restoreRecords(listOfNotNull(readPending()), account, BindPhonePendingPolicy.ENTRY_BIND_PHONE)
            .record

    /** 当前会话的完整身份（页面构造时一次取得，之后只比较，共性第 6、16、36 条）。 */
    fun owner(): BindPhoneOwner =
        BindPhoneOwner(
            account = client.activeUserId(),
            epoch = tokens.loginEpoch,
            scope = client.activeSessionScope(),
            epochs = client.inFlight.epochs(),
        )

    /** [owner] 仍是当前会话（账号、登录世代、作用域、隐私 / 环境世代都没变）。 */
    fun owns(owner: BindPhoneOwner): Boolean = owner.account != null && owner() == owner

    /** 绑定发起时固定的身份仍是此刻的会话（没有页面固定身份的 APP-05b 调用方也按发起时取得的那一份比较）。 */
    private fun stillFixed(fixed: BindPhoneOwner): Boolean = owner() == fixed

    /**
     * 【确认上一次提交】（原键原体重发）/【放弃上一次提交】（作废接口，action=phone_change）：在会话级作用域执行，离开页面不取消；结局按 PhoneChangeOutcomePolicy
     * （首次绑定确认得 10003 按 retry_later，G53）。成功走 APP-05b 绑定成功的后续与共享结算。返回 null = 本地没有发出（记录保持待确认）。
     */
    suspend fun resolvePending(
        key: String,
        owner: BindPhoneOwner,
        abandon: Boolean,
    ): PhoneChangeOutcome? {
        val account = owner.account ?: return null
        val epoch = owner.epoch
        if (!owns(owner)) return null
        val gate = shared?.phoneGate
        val target =
            PendingTarget(
                account,
                key,
                CallOwnership(client.inFlight.epochs(), sendAllowed = { gate?.active != true }) { tokens.loginEpoch == epoch },
            )
        val summary = runCatching { readPending()?.takeIf { it.key == key }?.summary }.getOrNull()
        // R1-03：发送与全部共享结算（42901、成功的清 token / 绑定状态 / getMe、未决通知）是一个会话级任务；页面离开只停止等待
        val outcome =
            session {
                val result =
                    withContext(Dispatchers.IO) {
                        if (abandon) {
                            client.abandonPending(BindPhoneAction.BIND_OPERATION, target)
                        } else {
                            client.confirmPending(BindPhoneAction.BIND_OPERATION, null, target)
                        }
                    }
                val input = if (abandon) AccountResult.ofAbandon(result) else AccountResult.of(result)
                val phase = if (abandon) AccountRaw.ABANDON else AccountRaw.CONFIRM
                val settled = input?.let { PhoneChangeOutcomePolicy.of(phase, it) }
                settleShared(account, epoch, key, summary, result, phase, settled)
                if (settled?.nextStateRaw == PhoneChangeOutcome.DONE && tokens.loginEpoch == epoch) {
                    phoneBinding.bindSucceeded(epoch)
                    refreshMe(epoch)
                    // R1-05：只做会话侧共享结算；出栈、恢复待办由持有原页面许可的页面做
                    // FU-AND-0716 R1-01：refreshMe 等待期间退出 A、B 第三方新号登录并暂缓了待办——返回后重新核对发起时的完整身份（账号、
                    // 登录世代、作用域、隐私 / 环境世代），失效就不做共享收尾，免得解除 B 的暂缓、让购买页重放外跳
                    if (owns(owner)) host.phoneBound(owner)
                }
                settled
            }
        // G53：首次绑定不预先 step-up；确认得到 10003 按 retry_later，保留未决
        return if (outcome?.stepUpAction != null) {
            outcome.copy(autoResendSameKey = false, textKey = AccountRaw.RETRY_LATER_KEY)
        } else {
            outcome
        }
    }

    /** 结局的共享结算（只按固定身份）：42901 截止时间、成功的共享结算（清全部 token、刷新 getMe）、未决通知（共性第 8、9、18 条）。 */
    @Suppress("LongParameterList")
    private fun settleShared(
        account: String?,
        epoch: Long,
        key: String,
        summary: String?,
        result: ApiResult,
        phase: String,
        known: PhoneChangeOutcome? = null,
    ) {
        val shared = shared ?: return
        account ?: return
        val outcome = known ?: AccountResult.of(result)?.let { PhoneChangeOutcomePolicy.of(phase, it) }
        if (tokens.loginEpoch == epoch) outcome?.disableMs?.let(shared.phoneGate::start)
        if (outcome?.nextStateRaw == PhoneChangeOutcome.DONE && (result is ApiResult.Success || outcome.alreadyDone)) {
            shared.onBindPhoneSucceeded(account, epoch, key, summary, PhoneChangeOutcome.DONE, outcome.textKey)
        } else {
            shared.notifyPending(
                // RC-01、共性第 47 条：携带完整终结结局，换号页恢复的同键待确认按结局落地
                PendingSettlement(
                    account,
                    epoch,
                    BindPhoneAction.BIND_OPERATION,
                    key,
                    outcome?.nextStateRaw,
                    outcome?.textKey,
                    summary,
                    outcome,
                    (result as? ApiResult.Failure)?.traceId,
                ),
            )
        }
    }

    /**
     * getMe（契约：绑定成功后再取一次 /v1/me）：按 phone_bound 更新，只以发起时的会话发出、只记到那个会话；发起时捕获更新世代，回来时
     * 世代变了（又一次绑定成功）不记（共性第 12 条）。
     */
    private suspend fun refreshMe(epoch: Long) {
        if (tokens.loginEpoch != epoch) return
        val generation = phoneBinding.generation()
        val result = MeReadLimit.sendGetMe(client, meRead) { tokens.loginEpoch == epoch }
        if (result !is ApiResult.Success || tokens.loginEpoch != epoch) return
        val bound = ((result.data as? JsonObject)?.get("phone_bound") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (bound != null) phoneBinding.applyIfCurrent(epoch, generation, bound)
    }

    /** 失败里要经 Router 打开的页面（10007 stage=cooling → DeleteAccount）；没有为 null。 */
    private fun navigation(result: ApiResult.Failure): String? =
        result.effect.route?.takeIf { result.effect.kind == ErrorEffectKind.NAVIGATE }

    /**
     * 并号确认框的决定（BR-ID-06 OPS-04）：
     * - cancel：请求发出前取消，不并号、不保存会话，清除凭据；留在绑手机页（落点未规定，TODO(N-29) MERGE-CANCEL）。
     * - confirm：锁定契约（9cd1e6f）没有 POST /v1/auth/merge 的 operation 与成功 schema——不伪造接口、不发请求，返回 unavailable
     *   （页面提示「暂不可用」），凭据清除（TODO(N-29) MERGE-SUCCESS）。
     */
    fun decideMerge(decision: String): MergeResult {
        val hadTicket = mergeTicket != null
        mergeTicket = null
        return if (decision == MergeDecision.CONFIRM && hadTicket) {
            MergeDecision.apply(MergeDecision.CONFIRM, null)
        } else {
            MergeDecision.apply(MergeDecision.CANCEL, null)
        }
    }

    /** 离开绑手机页（没有绑定）：交给 App 恢复暂缓的待办。 */
    fun leftUnbound() = host.leftUnbound()

    /** 当前会话是否已知已绑手机（进页时查清已绑就不必再绑）。 */
    fun knownBound(): Boolean = phoneBinding.get(tokens.loginEpoch) == true

    private fun ApiResult.isStale(): Boolean =
        when (this) {
            is ApiResult.LocalReject -> reason in STALE_REASONS
            is ApiResult.TransportFailure -> kind == KIND_CANCELLED
            is ApiResult.SessionNotSaved -> true
            else -> false
        }

    companion object {
        const val PURPOSE_BIND = "bind"
        const val MERGE_UNAVAILABLE_KEY = "bind_phone.merge.unavailable"
        const val PHONE_INVALID_KEY = "error.20001.phone_invalid"
        const val FIELD_ERROR = "field_error"
        const val NETWORK_ERROR = "network_error"
        private const val RATE_LIMITED_KEY = "error.42901"
        private const val MERGEABLE_CODE = 30411
        private const val MILLIS = 1000L
        private const val KIND_CANCELLED = "cancelled"
        private val STALE_REASONS = setOf(LocalRejectReason.CALL_CANCELLED, LocalRejectReason.SESSION_CHANGED)

        /** 字段错误落在哪个输入框（20001 手机号；20002 / 20003 验证码，03 §10.5）。 */
        fun fieldOf(
            code: Int,
            fields: List<String>?,
        ): String? =
            when (code) {
                20002, 20003 -> LoginErrorMapper.FIELD_CODE
                20001 -> fields?.firstOrNull() ?: LoginErrorMapper.FIELD_PHONE
                else -> null
            }

        private fun ceilSec(millis: Long): Int = if (millis <= 0) 0 else ((millis + MILLIS - 1) / MILLIS).toInt()
    }
}
