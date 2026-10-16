package com.couli.feature.login

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.ResultSignals

/** 第三方授权的结果种类。 */
object ThirdPartyAuthKind {
    /** 拿到授权凭证。 */
    const val CREDENTIAL = "credential"

    /** 用户在第三方取消。 */
    const val CANCELLED = "cancelled"

    /** 拉不起（未安装、没有 SDK / AppID）：提示 login.wechat_unavailable，保留其他方式。 */
    const val UNAVAILABLE = "unavailable"

    /** 第三方报错。 */
    const val FAILED = "failed"
}

/**
 * 第三方 SDK 带回的结果。凭证只在内存里，不落盘、不进日志（BR-ID-04 细则「最小化」）：[toString] 不输出凭证。
 * 微信：[code] + [state]（state 应等于本次尝试的 nonce，客户端自查）；Apple：[identityToken] + [authorizationCode]（Android 不提供）。
 */
class ThirdPartyAuthResult(
    val kind: String,
    val code: String? = null,
    val state: String? = null,
    val identityToken: String? = null,
    val authorizationCode: String? = null,
) {
    override fun toString(): String = "ThirdPartyAuthResult($kind)"

    companion object {
        fun of(kind: String): ThirdPartyAuthResult = ThirdPartyAuthResult(kind)
    }
}

/**
 * 第三方登录适配层（任务书 §2.2）：每个 provider 一个实现。
 * - [adapterAvailable]：适配器已允许启用（有 SDK、AppID 等并验证过）。Android 正式微信适配器恒为 false（不引入微信 SDK、没有 AppID）；
 *   Debug 假适配器为 true，只用于测试与截图。
 * - [installed]：本机是否装了对应 App——只查 contracts/apps.json 登记的包可见性查询项（Android `<queries>` 由 CT-05 从它生成，不手写），
 *   不读应用列表；没有登记、检测失败为 null（未知：BR-ID-04 细则按「照常显示」，再与 [adapterAvailable] 相与）。
 * - [authorize]：拉起第三方授权，[nonce] 作微信的 state。不得抛出；挂起直到第三方回来（离页、超时由执行器结束等待）。
 */
interface ThirdPartyAuthAdapter {
    val provider: String

    fun adapterAvailable(): Boolean

    fun installed(): Boolean?

    suspend fun authorize(nonce: String): ThirdPartyAuthResult

    /**
     * Debug / Staging 专用入口此刻不可用时给的提示原文（F-X-12，FU-AND-0717，三端统一口径）：非空 → 登录页照常显示按钮，点击只提示这段文字、不发任何请求
     * （不取授权尝试、不拉起授权、不要求勾选协议）。只有 Debug / Staging 源码集里的假适配器实现；Release 与正式适配器恒为 null（不显示 / 按开关）。
     */
    fun debugHint(): String? = null
}

/**
 * 本机是否装了某个 App（包可见性）。生产实现在 App 里用 PackageManager 按 apps.json 登记的 android_package 查询；没有登记的包名
 * 返回 null（未知）。
 */
fun interface InstalledAppProbe {
    /** [sourceSdk]：apps.json sdk_queries 的 source_sdk（如 wechat）。 */
    fun installed(sourceSdk: String): Boolean?
}

/**
 * Android 正式的微信适配器：不可用（任务书 §0、§2.2：不引入微信 SDK，无 AppID / 通用链接）。按钮不显示；万一被调用，按「拉不起」
 * 返回，不发任何请求。[installed]：按 [probe]（apps.json 的 sdk_queries 目前为空，没有微信的包名查询项 → 未知，TODO(N-29)
 * 【Android 实现发现】WECHAT-QUERY）。
 */
class UnavailableWechatAdapter(
    private val probe: InstalledAppProbe = InstalledAppProbe { null },
) : ThirdPartyAuthAdapter {
    override val provider: String = OAuthProvider.WECHAT

    override fun adapterAvailable(): Boolean = false

    override fun installed(): Boolean? = probe.installed(OAuthProvider.WECHAT)

    override suspend fun authorize(nonce: String): ThirdPartyAuthResult = ThirdPartyAuthResult.of(ThirdPartyAuthKind.UNAVAILABLE)
}

/**
 * 本机已知的「是否绑了手机」（守卫的会话状态用：第三方登录未绑手机 → LOGGED_IN，BR-ID-02）。按会话（登录世代）记在内存里：
 * 短信登录保存 → true；第三方新号登录 → false；第三方老用户登录 → 待查；getMe 回来按 phone_bound；bindPhone 成功 → true。
 * - 未知 = 未验证（APP-05b 共性第 2、8、13 条）：只有确认 true（短信登录完成、getMe phone_bound=true、绑定成功）才算已绑；已知 false、
 *   待查、没有记录（冷启动）守卫都按「已登录未绑手机」。
 * - 更新世代（共性第 12 条）：绑定成功时推进。getMe 发起时捕获（[generation]），回来时用 [applyIfCurrent] 核对——绑定前发出、绑定后才
 *   回来的旧查询（如 phone_bound=false）不能覆盖绑定结果。
 * 线程安全。
 */
class PhoneBindingState {
    private val lock = Any()
    private val known = mutableMapOf<Long, Boolean>()
    private val unverified = mutableSetOf<Long>()
    private var updateGeneration = 0L

    /** 当前更新世代（getMe 发起时捕获）。 */
    fun generation(): Long = synchronized(lock) { updateGeneration }

    fun set(
        loginEpoch: Long,
        bound: Boolean,
    ) = synchronized(lock) {
        known[loginEpoch] = bound
        unverified -= loginEpoch
    }

    /** 绑定成功：记为已绑并推进更新世代，使绑定前发出的 getMe 回包作废。 */
    fun bindSucceeded(loginEpoch: Long) =
        synchronized(lock) {
            updateGeneration += 1
            known[loginEpoch] = true
            unverified -= loginEpoch
        }

    /** getMe 的结果：发起时捕获的更新世代 [generation] 仍是当前的才记下。返回是否记下。 */
    fun applyIfCurrent(
        loginEpoch: Long,
        generation: Long,
        bound: Boolean,
    ): Boolean =
        synchronized(lock) {
            if (generation != updateGeneration) return@synchronized false
            known[loginEpoch] = bound
            unverified -= loginEpoch
            true
        }

    /** 这个会话的 phone_bound 待查（第三方老用户登录）。 */
    fun markUnverified(loginEpoch: Long) =
        synchronized(lock) {
            known -= loginEpoch
            unverified += loginEpoch
        }

    fun isUnverified(loginEpoch: Long): Boolean = synchronized(lock) { loginEpoch in unverified }

    /** 守卫能不能把这个会话当作已绑手机：只有确认 true 才能；已知 false、待查、没有记录（冷启动）都不能。 */
    fun treatAsUnbound(loginEpoch: Long): Boolean = synchronized(lock) { known[loginEpoch] != true }

    fun get(loginEpoch: Long): Boolean? = synchronized(lock) { known[loginEpoch] }
}

/**
 * Me 读取（GET /v1/me）的会话级共享禁用期（APP-12 Android R2-05，共性第 32 条；brief §0「Me 读取一个截止时间」）：登录校验 phone_bound 的 getMe 与「我的」类
 * 页面的 getMe 共用**同一个**截止时间（App 把「我的」的读取闸门交进来）。两条路径都在每次实际写出前（[CallOwnership.sendAllowed]）核对 [active]，收到 42901
 * 都按 Retry-After（缺省或非法 5 秒）[start]；各自的请求归属不变。没有接「我的」（更早任务的单测）时是 [NONE]。
 */
interface MeReadLimit {
    /** 此刻在禁用期内（任意线程可读）。 */
    val active: Boolean

    /** 收到 42901：按 Retry-After 启动 / 延长禁用期。 */
    fun start(millis: Long)

    companion object {
        val NONE: MeReadLimit =
            object : MeReadLimit {
                override val active: Boolean get() = false

                override fun start(millis: Long) = Unit
            }

        /**
         * 登录侧的一次 getMe（OAuthLoginService / BindPhoneService 共用）：禁用期内不发（页面层 + 执行层两处），写出前每次核对同一截止时间；收到 42901 立即启动
         * 共享禁用期。[stillOwned] 是调用方自己的请求归属（会话世代）。
         */
        suspend fun sendGetMe(
            client: ApiClient,
            limit: MeReadLimit,
            stillOwned: () -> Boolean,
        ): ApiResult {
            if (limit.active) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
            val owner = CallOwnership(client.inFlight.epochs(), sendAllowed = { !limit.active }, stillOwned = stillOwned)
            val result = client.send(ApiCall(OAuthLoginService.GET_ME_OPERATION, ownership = owner))
            settle(result, owner, client.inFlight::epochs, limit::start)
            return result
        }

        /**
         * getMe 的限流结算（Android R3-02，共性第 34 条）：只有 42901、且结算这一刻发起时的归属（[CallOwnership.stillOwned]：会话世代 / 发起页 / 桥调用）
         * 与隐私、环境世代（[CallOwnership.epochs] 对 [epochsNow]）都没变，才写入共享截止时间；网络结果已返回、主线程结算之前换了账号、撤回同意、切了环境的
         * 旧结果一律不改当前闸门（旧会话的 42901 不限流新会话）。返回是否写入。登录、「我的」之外的 getMe 路径（钱包实名查询、桥 auth.getUser）同样经它。
         */
        fun settle(
            result: ApiResult,
            owner: CallOwnership,
            epochsNow: () -> com.couli.core.network.CallEpochs,
            start: (Long) -> Unit,
        ): Boolean {
            val limited = result is ApiResult.Failure && result.code == ResultSignals.RATE_LIMITED
            val live = limited && owner.stillOwned() && epochsNow() == owner.epochs
            if (live) start(ResultSignals.disableMillis(result))
            return live
        }
    }
}
