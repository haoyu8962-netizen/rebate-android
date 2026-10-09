package com.couli.feature.trade

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 契约 operationId（ApiOperations）。 */
object TradeOperations {
    const val OPEN_LINK = "openLink"
    const val GET_UNION_AUTH_URL = "getUnionAuthUrl"
    const val BIND_UNION = "bindUnion"
    const val LIST_UNION_BINDINGS = "listUnionBindings"
    const val GET_TIPS = "getTips"
    const val MARK_TIP_READ = "markTipRead"
}

/**
 * 购买的 pending_action（BR-ID-10）：App 用 Router 的同一份本地记录（只存 1 条、10 分钟、完成一步重新计时、退出登录清除）实现。
 * 动作名 [ACTION]；params 不含令牌、验证码（只有 link_id、平台、商品键与本次的幂等键）。
 */
interface PurchasePending {
    /** 登记（新的覆盖旧的）；失败返回 false。 */
    fun register(params: JsonObject): Boolean

    /** 有效的购买待办（未过期、动作名对）；没有为 null。不删除。 */
    fun peek(): JsonObject?

    /** 完成了一个前置步骤（登录、授权）：重新计时。 */
    fun stepDone()

    /** 删除（执行前、放弃、页面出栈）。 */
    fun clear()

    companion object {
        const val ACTION = "open_link"
    }
}

/** 内存实现（测试、没有持久化的场合）。 */
class MemoryPurchasePending(
    private val clock: () -> Long = System::currentTimeMillis,
) : PurchasePending {
    private var params: JsonObject? = null
    private var startedAt = 0L

    override fun register(params: JsonObject): Boolean {
        this.params = params
        startedAt = clock()
        return true
    }

    override fun peek(): JsonObject? = params?.takeIf { clock() - startedAt in 0..TTL_MILLIS }.also { if (it == null) params = null }

    override fun stepDone() {
        if (peek() != null) startedAt = clock()
    }

    override fun clear() {
        params = null
    }

    private companion object {
        const val TTL_MILLIS = 10 * 60 * 1000L
    }
}

/**
 * AuthSheet 自动弹出计数（BR-ID-18）：每次因 30101 / 30102 / 30111 自动弹出记 1 次，按平台存在内存；同一会话每个平台最多 2 次，第 3 次起
 * 点击购买直接给「未授权将无法获得返利」二选一。进程冷启动或在后台停留 ≥ 1800 秒后回前台时清零。App 里一个进程一份。
 */
class AuthSheetCounter(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val counts = mutableMapOf<String, Int>()
    private var backgroundAt: Long? = null

    /** 这次还能自动弹出：能就记 1 次并返回 true。 */
    @Synchronized
    fun tryAutoPop(platform: String): Boolean {
        val n = counts[platform] ?: 0
        if (n >= MAX_AUTO_POPS) return false
        counts[platform] = n + 1
        return true
    }

    @Synchronized
    fun count(platform: String): Int = counts[platform] ?: 0

    @Synchronized
    fun onBackground() {
        backgroundAt = clock()
    }

    @Synchronized
    fun onForeground() {
        val since = backgroundAt ?: return
        backgroundAt = null
        if (clock() - since >= RESET_AFTER_MILLIS) counts.clear()
    }

    companion object {
        const val MAX_AUTO_POPS = 2
        const val RESET_AFTER_MILLIS = 1_800_000L
    }
}

/**
 * JumpTip 已读（BR-ATTR-21：每个用户、每个平台首次外跳前展示一次，服务端记已读；getTips / markTipRead）。已读状态按会话缓存在内存，
 * 读取失败时按「未读」保守展示（多看一次须知不影响归属）。[sessionKey] 变了（换账号、退出）就重新读。
 */
class JumpTipService(
    private val api: PageApi,
    private val sessionKey: () -> Any?,
) {
    private var cachedFor: Any? = null
    private var read: MutableSet<String>? = null

    suspend fun shouldShow(
        platform: String,
        owner: CallOwnership,
    ): Boolean {
        val key = sessionKey() ?: return false
        if (cachedFor != key || read == null) {
            val result = api.send(ApiCall(TradeOperations.GET_TIPS, ownership = owner))
            val tips = ((result as? ApiResult.Success)?.data as? JsonObject)?.get("jump_tip") as? JsonObject
            cachedFor = key
            read = tips?.filterValues { it is JsonPrimitive && it.isString }?.keys?.toMutableSet()
            if (result !is ApiResult.Success) read = null
        }
        return read?.contains(platform) != true
    }

    /** 用户点了【知道了，去X】：先本地记已读，再告诉服务端（失败不影响外跳）。 */
    suspend fun markRead(
        platform: String,
        owner: CallOwnership,
    ) {
        (read ?: mutableSetOf<String>().also { read = it }).add(platform)
        cachedFor = sessionKey()
        val body = JsonObject(mapOf("platform" to JsonPrimitive(platform))).toString()
        api.send(ApiCall(TradeOperations.MARK_TIP_READ, pathParams = mapOf("tip_key" to JUMP_TIP), body = body, ownership = owner))
    }

    private companion object {
        const val JUMP_TIP = "jump_tip"
    }
}

/** 授权凭证（bindings 请求体的一种，BR-ID-17 细则「授权方式」）。只交给 bindings，不保存、不进日志。 */
sealed interface AuthCredential {
    class WebCode(
        val code: String,
    ) : AuthCredential

    class SdkToken(
        val accessToken: String,
        val expiresIn: Long,
    ) : AuthCredential
}

/** 一次授权步骤（拉起授权页 / SDK）的结果。 */
sealed interface AuthStepResult {
    class Credential(
        val value: AuthCredential,
    ) : AuthStepResult

    data object Cancelled : AuthStepResult

    data object Failed : AuthStepResult
}

/**
 * 淘宝 web_code 授权的本端能力（App 给）：用系统浏览器（或淘宝官方授权页）打开 auth_url，等本 App 自己的回跳 scheme 带回授权码。
 * 回跳 scheme 在 apps.json inbound 登记前（为空，TODO(N-28)）生产实现 [available] = false。
 */
interface WebCodeAuthorizer {
    val available: Boolean

    suspend fun authorize(
        authUrl: String,
        state: String,
    ): AuthStepResult
}

/** 生产：回跳 scheme 未登记，web_code 不可执行（TODO(N-28)：apps.json inbound 补上后接系统浏览器 + 回跳）。 */
object UnavailableWebCodeAuthorizer : WebCodeAuthorizer {
    override val available: Boolean get() = false

    override suspend fun authorize(
        authUrl: String,
        state: String,
    ): AuthStepResult = AuthStepResult.Failed
}

/** bindings 请求体（BR-ID-17：只有 state、auth_method 与凭证字段）。 */
internal fun bindBody(
    state: String,
    credential: AuthCredential,
): String {
    val fields =
        linkedMapOf<String, JsonElement>("state" to JsonPrimitive(state))
    when (credential) {
        is AuthCredential.WebCode -> {
            fields["auth_method"] = JsonPrimitive(AuthMethodPicker.WEB_CODE)
            fields["code"] = JsonPrimitive(credential.code)
        }

        is AuthCredential.SdkToken -> {
            fields["auth_method"] = JsonPrimitive(AuthMethodPicker.SDK_TOKEN)
            fields["access_token"] = JsonPrimitive(credential.accessToken)
            fields["expires_in"] = JsonPrimitive(credential.expiresIn)
        }
    }
    return JsonObject(fields).toString()
}

/**
 * 点击结果确认的平台购买状态（50301 maintenance / not_launched；03 §7.4「点击时以响应为准」，A-R1-05 / 06）：按平台记下，进程里一份
 * （App 的 AppTrade），列表、详情的卡片映射与点击前核对都先读它。记下时取配置拉取的分界（[issuedMark]）；分界之后发出、已生效的配置
 * （[appliedMark] 超过分界）到达后以新配置为准，这条记录作废。只在主线程使用。
 */
class PlatformStatusOverrides(
    private val issuedMark: () -> Long,
    private val appliedMark: () -> Long,
) {
    private val marks = mutableMapOf<String, Pair<String, Long>>()
    private val listeners = mutableListOf<() -> Unit>()

    fun mark(
        platform: String,
        status: String,
    ) {
        marks[platform] = status to issuedMark()
        listeners.toList().forEach { it() }
    }

    /** 仍有效的记录；没有或已被新配置覆盖为 null。 */
    @Suppress("ReturnCount") // 没有记录、已被覆盖两个出口
    fun status(platform: String): String? {
        val (status, mark) = marks[platform] ?: return null
        if (appliedMark() > mark) {
            marks.remove(platform)
            return null
        }
        return status
    }

    /** 记录变化时通知（主线程）；返回取消订阅。 */
    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }
}
