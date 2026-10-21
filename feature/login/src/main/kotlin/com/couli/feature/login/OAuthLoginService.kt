// 秒数是业务规则取值（BR-ID-04 授权尝试 600 秒），按值直接写
@file:Suppress("MagicNumber", "ReturnCount") // 归属失效、各结果逐项提前返回

package com.couli.feature.login

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.ResultSignals
import com.couli.core.network.SessionScopeRaw
import com.couli.core.network.SessionTokens
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 第三方登录的结果。[stateRaw]：logged_in / cancelled（用户在第三方取消）/ unavailable（拉不起）/ timeout / stale（离页、换方式、迟到、
 * 归属变化）/ busy / agreement_required / 错误时为 [OAuthErrorResult.actionRaw]（reauthorize、provider_unavailable、risk_blocked、
 * force_update、service_error、retry_after、network_error、field_error、message）。[routeRaw] 登录成功时的分流（done /
 * bind_phone_guide）；[held] 待办已暂缓（等引导结束或 phone_bound 查清）；[login] 短信登录同一份结果（执行了的登录后计划）。
 */
data class OAuthLoginOutcome(
    val stateRaw: String,
    val routeRaw: String? = null,
    val textKey: String? = null,
    val trace6: String = "",
    val disableMillis: Long = 0L,
    val provider: String = "",
    val held: Boolean = false,
    val login: LoginOutcome? = null,
    /** [DEBUG_ONLY] 时要提示的原文（[ThirdPartyAuthAdapter.debugHint]）。 */
    val hint: String? = null,
) {
    companion object {
        const val LOGGED_IN = LoginOutcome.LOGGED_IN

        /** F-X-12：Debug / Staging 的第三方入口没开本机假服务：只提示、不发请求。 */
        const val DEBUG_ONLY = "debug_only"
        const val CANCELLED = "cancelled"
        const val UNAVAILABLE = "unavailable"
        const val TIMEOUT = "timeout"
        const val UNAVAILABLE_KEY = "login.wechat_unavailable"

        /**
         * F6（APP-17a 评审 r1 followup）：授权在途期间页面取消了同意——不提交、不提示，页面回到需重新同意的状态（同 S1-1 口径，不自动弹确认框）。
         */
        const val CONSENT_WITHDRAWN = "consent_withdrawn"
    }
}

/**
 * 第三方登录的执行器（APP-05b；BR-ID-04 细则；03 §4.2、§4.4）：
 * createOauthAttempt → 拉起第三方（nonce 作 state）→ 拿到授权凭证 → loginByWechat / loginByApple；只上送 attempt_id 与授权凭证
 * （加协议版本与同意时刻），不在客户端换 token。
 * - 归属（APP-05b 共性第 1 条）：开始时固定会话（登录世代）、隐私、环境世代——整个尝试的两个请求共用这一份归属，每次异步返回后、
 *   提交凭证前复核，变了就终止整个尝试，后续请求不会在新上下文里重新绑定（授权等待期间保存了另一账号的会话 → 不提交、不覆盖）。
 * - 与短信登录共用 [LoginService] 的尝试序号与保存同步区：离开登录页、换号、登出时 [LoginService.invalidateAttempt] 作废本次尝试并
 *   取消它的请求；本地授权尝试（[session]）随之按「离开」终止，等待立即结束，迟到的回调只丢弃；同一尝试只提交一次。
 * - 用户取消、超时（[timeoutMillis]，BR-ID-04 尝试 600 秒）同样终止。SDK 回传的 state 与本次 nonce 不一致：不提交。
 * - 登录保存沿用 APP-05a：ApiClient 原子保存与世代检查；保存同步区里把新会话记为 phone_bound 待查（共性第 2 条）。正常会话一律暂缓
 *   待办（登录这一步完成、计时重置，共性第 3 条）：新号等绑手机引导结束；老用户先 getMe 查清 phone_bound，查清后经 Router 守卫恢复，
 *   查询失败解除暂缓、经守卫按「未验证」恢复（只需登录的照常执行，需要手机号的保留，共性第 14 条）。
 * 页面在主线程调用；网络在 IO 线程。
 */
@Suppress("LongParameterList", "TooManyFunctions") // 依赖逐项注入；授权、提交、分流各一段
class OAuthLoginService(
    private val client: ApiClient,
    private val tokens: SessionTokens,
    private val login: LoginService,
    adapters: List<ThirdPartyAuthAdapter>,
    val phoneBinding: PhoneBindingState,
    private val legal: LegalVersions = LegalVersions(1, 1),
    private val timeoutMillis: Long = ATTEMPT_TIMEOUT_MILLIS,
    /** 绑手机引导是否开启（/v1/config.invite.bind_phone_guide，缺省 on，BR-INV-21；配置任务接入前恒为 true）。 */
    private val guideEnabled: () -> Boolean = { true },
    /**
     * 登录完成之后的后续（老用户查 phone_bound、查清后恢复待办）在这里执行：登录页随即关闭，页面的协程作用域会被取消，后续不能跟着页面
     * 走。App 给主线程的 App 级作用域；缺省是独立的后台作用域（单测）。
     */
    private val followUpScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Me 读取的会话级共享禁用期（APP-12 R2-05）：phone_bound 校验的 getMe 与「我的」页面共用一个截止时间。 */
    private val meRead: MeReadLimit = MeReadLimit.NONE,
) {
    private val adapters: Map<String, ThirdPartyAuthAdapter> = adapters.associateBy { it.provider }

    /** 本地授权尝试（观测：submitted / dropped）。 */
    val session = OAuthAttemptSession()

    /** 第三方授权在这里等（SDK 回调可能很晚才来；离页、超时由 [abandon] 结束等待，不等 SDK）。 */
    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running = false
    private var callbackSeq = 0

    @Volatile
    private var abandon: CompletableDeferred<Unit>? = null

    init {
        login.onAttemptInvalidated(::leave)
    }

    val busy: Boolean get() = running

    /** 登录页要显示的第三方按钮（provider 名）。Android 只有微信候选（Apple 按向量 appleButton.android = false，BR-ID-04）。 */
    fun visibleProviders(): List<String> {
        val out = mutableListOf<String>()
        adapters[OAuthProvider.WECHAT]?.let { wechat ->
            // BR-ID-04 细则：检测失败、结果未知时照常显示（再与适配器可用性相与）
            val installed = runCatching { wechat.installed() }.getOrNull() ?: true
            // F-X-12：Debug / Staging 的假适配器没开假服务时也显示（点击只提示，[loginWith]）
            if (ThirdPartyButtons.wechatVisible(PLATFORM, installed, available(wechat) || hint(wechat) != null)) out += OAuthProvider.WECHAT
        }
        adapters[OAuthProvider.APPLE]?.let { apple ->
            if (ThirdPartyButtons.appleVisible(PLATFORM) && available(apple)) out += OAuthProvider.APPLE
        }
        return out
    }

    /** 离开登录页 / 换号 / 登出（[LoginService.invalidateAttempt] 同步调用）：本地授权尝试终止，等待立即结束。 */
    private fun leave() {
        session.leave()
        abandon?.complete(Unit)
    }

    /** F-X-12：[provider] 是 Debug / Staging 专用入口且此刻不可用（点击只提示、不发请求）。 */
    fun debugOnly(provider: String): Boolean = adapters[provider]?.let { !available(it) && hint(it) != null } == true

    /**
     * [consentAtMillis]：勾选或确认框同意的时刻（同短信登录，BR-ID-04）。[consentNow]：页面此刻的同意时刻（未同意为 null）——授权在途期间
     * 用户可能取消勾选（F6），拉起第三方前、提交凭证前各复核一次：取消了就终止尝试（[OAuthLoginOutcome.CONSENT_WITHDRAWN]），
     * 不以开始时取走的同意时刻登录；仍同意时请求带此刻的同意时刻。
     */
    suspend fun loginWith(
        provider: String,
        consentAtMillis: Long?,
        consentNow: () -> Long? = { consentAtMillis },
    ): OAuthLoginOutcome {
        val adapter = adapters[provider]
        val operationId = OAuthProvider.loginOperation(provider)
        // F-X-12：Debug / Staging 入口此刻不可用（没开本机假服务）——先于协议确认与任何请求，只提示
        if (adapter != null && !available(adapter)) {
            hint(adapter)?.let { return OAuthLoginOutcome(OAuthLoginOutcome.DEBUG_ONLY, provider = provider, hint = it) }
        }
        if (adapter == null || operationId == null || !available(adapter)) {
            return OAuthLoginOutcome(OAuthLoginOutcome.UNAVAILABLE, textKey = OAuthLoginOutcome.UNAVAILABLE_KEY, provider = provider)
        }
        if (consentAtMillis == null || consentAtMillis <= 0L) return OAuthLoginOutcome(LoginOutcome.AGREEMENT_REQUIRED, provider = provider)
        if (running || login.busy) return OAuthLoginOutcome(LoginOutcome.BUSY, provider = provider)
        running = true
        login.thirdPartyBusy = true
        // 共性第 1 条：开始这一刻固定会话（登录世代）与隐私、环境世代
        val startEpoch = tokens.loginEpoch
        val startEpochs = client.inFlight.epochs()
        val bound = { tokens.loginEpoch == startEpoch && client.inFlight.epochs() == startEpochs }
        val ticket = login.beginThirdParty(bound) { epoch -> phoneBinding.markUnverified(epoch) }
        try {
            return run(provider, operationId, adapter, consentNow, ticket, bound).copy(provider = provider)
        } finally {
            running = false
            login.thirdPartyBusy = false
            login.releaseTicket(ticket)
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // 一次尝试从取尝试到分流的完整顺序，逐步复核归属
    private suspend fun run(
        provider: String,
        operationId: String,
        adapter: ThirdPartyAuthAdapter,
        consentNow: () -> Long?,
        ticket: AttemptTicket,
        bound: () -> Boolean,
    ): OAuthLoginOutcome {
        // 1. 取一次性的授权尝试（BR-ID-04 细则「授权尝试」）
        val createBody = JsonObject(mapOf("provider" to JsonPrimitive(provider), "purpose" to JsonPrimitive("login"))).toString()
        val created = client.send(ApiCall(CREATE_OPERATION, body = createBody, ownership = ticket.owner))
        if (!login.ticketValid(ticket) || !bound()) return OAuthLoginOutcome(LoginOutcome.STALE)
        if (created !is ApiResult.Success) return failure(created)
        val data = created.data as? JsonObject
        val attemptId = data?.text("attempt_id")?.takeIf { it.isNotEmpty() }
        val nonce = data?.text("nonce")?.takeIf { it.isNotEmpty() }
        if (attemptId == null || nonce == null) {
            return OAuthLoginOutcome(
                OAuthLoginErrorAction.SERVICE_ERROR,
                textKey = LoginService.GENERIC_KEY,
                trace6 = trace6(created.traceId),
            )
        }
        // F6：取尝试期间取消了同意——不拉起第三方
        if (!consentHeld(consentNow)) return OAuthLoginOutcome(OAuthLoginOutcome.CONSENT_WITHDRAWN)
        // 2. 拉起第三方；迟到的回调（取消、离页、超时之后）只交给 session 记为丢弃
        val handle = session.create()
        session.launch(handle)
        callbackSeq += 1
        val seq = callbackSeq
        val waiting = CompletableDeferred<Unit>().also { abandon = it }
        if (!login.ticketValid(ticket)) waiting.complete(Unit)
        val authorized = authScope.async { safeAuthorize(adapter, nonce) }
        val outcome =
            try {
                withTimeoutOrNull(timeoutMillis) {
                    select<ThirdPartyAuthResult?> {
                        authorized.onAwait { it }
                        waiting.onAwait { null }
                    }
                }
            } finally {
                abandon = null
            }
        val timedOut = outcome == null && !waiting.isCompleted
        if (outcome == null) {
            lateCallback(authorized, handle, seq)
            if (timedOut) session.timeout() else session.leave()
        }
        val accepted = outcome?.takeIf { it.kind != ThirdPartyAuthKind.CREDENTIAL || session.callback(handle, seq) }
        if (!login.ticketValid(ticket) || (outcome != null && !session.isLive(handle))) return OAuthLoginOutcome(LoginOutcome.STALE)
        if (!bound()) {
            // 等待授权期间归属变了（另一账号的会话已保存、登出、撤回同意、切换环境）：整个尝试终止，凭证不提交
            session.leave()
            return OAuthLoginOutcome(LoginOutcome.STALE)
        }
        if (timedOut) return OAuthLoginOutcome(OAuthLoginOutcome.TIMEOUT, textKey = REAUTHORIZE_KEY)
        if (accepted == null) {
            session.leave()
            return OAuthLoginOutcome(LoginOutcome.STALE)
        }
        when (accepted.kind) {
            ThirdPartyAuthKind.CANCELLED -> {
                session.cancel()
                return OAuthLoginOutcome(OAuthLoginOutcome.CANCELLED)
            }

            ThirdPartyAuthKind.UNAVAILABLE -> {
                session.cancel()
                return OAuthLoginOutcome(OAuthLoginOutcome.UNAVAILABLE, textKey = OAuthLoginOutcome.UNAVAILABLE_KEY)
            }

            ThirdPartyAuthKind.CREDENTIAL -> {
                Unit
            }

            else -> {
                session.cancel()
                return OAuthLoginOutcome(OAuthLoginErrorAction.REAUTHORIZE, textKey = REAUTHORIZE_KEY)
            }
        }
        // F6：授权在途期间取消了同意——本次尝试终止，凭证不提交；页面重新同意后从入口重新发起
        val consentAt = consentNow()?.takeIf { it > 0L }
        if (consentAt == null) {
            session.cancel()
            return OAuthLoginOutcome(OAuthLoginOutcome.CONSENT_WITHDRAWN)
        }
        // 3. 客户端自查：SDK 回传的 state 必须等于本次 nonce（微信 state；Apple 由服务端核对 nonce）
        val body = loginBody(provider, attemptId, accepted, consentAt)
        if ((provider == OAuthProvider.WECHAT && accepted.state != nonce) || body == null) {
            session.cancel()
            return OAuthLoginOutcome(OAuthLoginErrorAction.REAUTHORIZE, textKey = REAUTHORIZE_KEY)
        }
        // 4. 唯一提交边界：同一尝试只提交一次；请求沿用开始时固定的归属（ApiClient 写出前、保存前都核对）
        if (!session.submit(handle)) return OAuthLoginOutcome(LoginOutcome.STALE)
        val result = client.send(ApiCall(operationId, body = body, ownership = ticket.owner))
        if (result !is ApiResult.Success) {
            if (!login.ticketValid(ticket)) return OAuthLoginOutcome(LoginOutcome.STALE)
            return failure(result)
        }
        return loggedIn(result, ticket, provider)
    }

    /** 登录响应已保存会话：分流、暂缓待办、老用户查 phone_bound。 */
    @Suppress("CyclomaticComplexMethod") // 新号 / 老用户 / 受限会话 / 引导开关逐项
    private suspend fun loggedIn(
        result: ApiResult.Success,
        ticket: AttemptTicket,
        provider: String,
    ): OAuthLoginOutcome {
        val data = result.data as? JsonObject ?: JsonObject(emptyMap())
        val route = OAuthLoginRoute.ofData(data)
        val epoch = login.lastSavedEpoch() ?: tokens.loginEpoch
        val isNew = (data["is_new_user"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
        val full = ((data["tokens"] as? JsonObject)?.text("session_scope") ?: SessionScopeRaw.FULL) != SessionScopeRaw.DELETION_ONLY
        // 第三方新号没有手机号（BR-ID-04：第三方首次登录即注册、不强制绑手机）；老用户保存时已记为待查
        if (isNew) phoneBinding.set(epoch, false)
        val guide = route.routeRaw == OAuthLoginRoute.BIND_PHONE_GUIDE && guideEnabled()
        // 上次登录方式（G05）：与短信同一份存储，由执行器在会话保存且尝试有效后写，带授权开始时捕获的修订号（ticket，G29）
        val finished = login.finishThirdPartyLogin(result, ticket, hold = full, provider = provider)
        if (finished.stateRaw != LoginOutcome.LOGGED_IN) {
            return OAuthLoginOutcome(finished.stateRaw, textKey = finished.textKey, trace6 = finished.trace6, login = finished)
        }
        val held = LoginService.HOLD_PENDING_THIRD_PARTY in finished.actions
        if (!isNew && full) {
            // 共性第 2 条：先取得当前会话的 phone_bound；查清（且仍是这次登录的会话）才经 Router 守卫恢复待办。
            // 登录页此时已关闭（页面作用域会被取消）：在 App 级作用域里继续
            // 共性第 14 条：查询失败（或回包不是布尔）解除这次登录的暂缓——只需登录的待办照常恢复，需要手机号的按未验证保留
            followUpScope.launch {
                val known = refreshMe(epoch)
                if (!held || tokens.loginEpoch != epoch) return@launch
                if (known) login.resumeHeldPending() else login.releaseHeldUnverified()
            }
        } else if (isNew && !guide && held) {
            // 引导关闭（配置）：没有引导可等，按「暂不」直接经守卫恢复
            login.resumeHeldPending()
        }
        val routeRaw = if (guide) OAuthLoginRoute.BIND_PHONE_GUIDE else OAuthLoginRoute.DONE
        return OAuthLoginOutcome(LoginOutcome.LOGGED_IN, routeRaw = routeRaw, held = held, login = finished)
    }

    /** 正在进行的 getMe（同一会话、同一更新世代的查询合并成一次）。 */
    private val meLock = Any()
    private var meInFlight: MeQuery? = null

    private class MeQuery(
        val loginEpoch: Long,
        val generation: Long,
        val result: CompletableDeferred<Boolean>,
    )

    /**
     * 拉取 getMe，按 phone_bound 更新本机会话状态（APP-05b 共性第 2、8、12 条）。发起时捕获登录世代与手机号绑定状态的更新世代：
     * 请求只以这个会话发出（换了账号就不发、不写出，[CallOwnership.stillOwned]）；回来时登录世代与更新世代都没变才记下——绑定前发出的旧
     * 查询不覆盖绑定结果。phone_bound 不是布尔按没查清。同一会话、同一更新世代已在查时合并到那一次。返回是否查清并记下。
     */
    suspend fun refreshMe(loginEpoch: Long = tokens.loginEpoch): Boolean {
        if (client.activeSessionScope() == null || tokens.loginEpoch != loginEpoch) return false
        val generation = phoneBinding.generation()
        val mine = CompletableDeferred<Boolean>()
        val joined =
            synchronized(meLock) {
                val running = meInFlight?.takeIf { it.loginEpoch == loginEpoch && it.generation == generation && it.result.isActive }
                if (running == null) meInFlight = MeQuery(loginEpoch, generation, mine)
                running?.result
            }
        if (joined != null) return joined.await()
        var known = false
        try {
            known = fetchMe(loginEpoch, generation)
        } finally {
            mine.complete(known)
            synchronized(meLock) { if (meInFlight?.result === mine) meInFlight = null }
        }
        return known
    }

    private suspend fun fetchMe(
        loginEpoch: Long,
        generation: Long,
    ): Boolean {
        // 与「我的」共用 Me 读取的截止时间（R2-05）：禁用期内不发（每次实际写出前核对），42901 启动共享禁用期；请求归属仍是发起时的会话
        val result = MeReadLimit.sendGetMe(client, meRead) { tokens.loginEpoch == loginEpoch }
        if (result !is ApiResult.Success || tokens.loginEpoch != loginEpoch) return false
        val bound =
            ((result.data as? JsonObject)?.get("phone_bound") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return false
        return phoneBinding.applyIfCurrent(loginEpoch, generation, bound)
    }

    /** 授权已不再等待：之后才回来的凭证只记为丢弃。 */
    private fun lateCallback(
        authorized: Deferred<ThirdPartyAuthResult>,
        handle: String,
        seq: Int,
    ) {
        authorized.invokeOnCompletion { error ->
            if (error != null) return@invokeOnCompletion
            @Suppress("OPT_IN_USAGE")
            if (authorized.getCompleted().kind == ThirdPartyAuthKind.CREDENTIAL) session.callback(handle, seq)
        }
    }

    /** 登录请求体：只放规定的凭证字段（BR-ID-04 细则「请求体」）+ 协议版本与同意时刻。 */
    private fun loginBody(
        provider: String,
        attemptId: String,
        credential: ThirdPartyAuthResult,
        consentAtMillis: Long,
    ): String? {
        val fields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>("attempt_id" to JsonPrimitive(attemptId))
        when (provider) {
            OAuthProvider.APPLE -> {
                fields["identity_token"] = JsonPrimitive(credential.identityToken?.takeIf { it.isNotEmpty() } ?: return null)
                fields["authorization_code"] = JsonPrimitive(credential.authorizationCode?.takeIf { it.isNotEmpty() } ?: return null)
            }

            else -> {
                fields["code"] = JsonPrimitive(credential.code?.takeIf { it.isNotEmpty() } ?: return null)
            }
        }
        fields["legal_versions"] =
            JsonObject(mapOf("privacy" to JsonPrimitive(legal.privacy), "agreement" to JsonPrimitive(legal.agreement)))
        fields["consent_at"] = JsonPrimitive(Instant.ofEpochMilli(consentAtMillis).toString())
        return JsonObject(fields).toString()
    }

    private fun failure(result: ApiResult): OAuthLoginOutcome =
        when (result) {
            is ApiResult.Failure -> {
                val mapped = OAuthLoginErrorAction.ofEffect(result.code, result.effect)
                OAuthLoginOutcome(
                    mapped.actionRaw,
                    textKey = mapped.textKey,
                    trace6 = trace6(result.traceId),
                    disableMillis = mapped.disableMillis,
                )
            }

            is ApiResult.LocalReject -> {
                if (result.reason in STALE_REASONS) {
                    OAuthLoginOutcome(LoginOutcome.STALE)
                } else {
                    OAuthLoginOutcome(OAuthLoginErrorAction.SERVICE_ERROR, textKey = LoginService.GENERIC_KEY)
                }
            }

            // 登录响应缺令牌或令牌没能保存：不算登录成功；作废、换代后到达的不提示
            is ApiResult.SessionNotSaved -> {
                if (result.reason in STALE_REASONS) {
                    OAuthLoginOutcome(LoginOutcome.STALE)
                } else {
                    OAuthLoginOutcome(
                        OAuthLoginErrorAction.SERVICE_ERROR,
                        textKey = LoginService.GENERIC_KEY,
                        trace6 = trace6(result.traceId),
                    )
                }
            }

            is ApiResult.TransportFailure -> {
                if (result.kind == KIND_CANCELLED) {
                    OAuthLoginOutcome(LoginOutcome.STALE)
                } else {
                    OAuthLoginOutcome(OAuthLoginErrorAction.NETWORK_ERROR, textKey = LoginService.GENERIC_KEY)
                }
            }

            else -> {
                OAuthLoginOutcome(OAuthLoginErrorAction.SERVICE_ERROR, textKey = LoginService.GENERIC_KEY)
            }
        }

    private fun consentHeld(consentNow: () -> Long?): Boolean = (consentNow() ?: 0L) > 0L

    private fun available(adapter: ThirdPartyAuthAdapter): Boolean = runCatching { adapter.adapterAvailable() }.getOrDefault(false)

    private fun hint(adapter: ThirdPartyAuthAdapter): String? = runCatching { adapter.debugHint() }.getOrNull()?.takeIf { it.isNotEmpty() }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 适配器抛错一律按第三方报错
    private suspend fun safeAuthorize(
        adapter: ThirdPartyAuthAdapter,
        nonce: String,
    ): ThirdPartyAuthResult =
        try {
            adapter.authorize(nonce)
        } catch (error: Exception) {
            ThirdPartyAuthResult.of(ThirdPartyAuthKind.FAILED)
        }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        /** 授权等待上限（BR-ID-04：尝试 600 秒过期；配置 auth.oauth_attempt_ttl_sec 未接入时按缺省，rule-gaps OAUTH-TIMEOUT）。 */
        const val ATTEMPT_TIMEOUT_MILLIS = 600_000L
        const val CREATE_OPERATION = "createOauthAttempt"
        const val GET_ME_OPERATION = "getMe"
        const val PLATFORM = ThirdPartyButtons.PLATFORM_ANDROID
        const val REAUTHORIZE_KEY = "error.20004"
        private const val KIND_CANCELLED = "cancelled"
        private val STALE_REASONS = setOf(LocalRejectReason.CALL_CANCELLED, LocalRejectReason.SESSION_CHANGED)

        private fun trace6(traceId: String?): String = ResultSignals.trace6(traceId)
    }
}
