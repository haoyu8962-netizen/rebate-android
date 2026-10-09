package com.couli.core.tradejump

/**
 * 外跳：联盟 SDK 适配层（03 §3.1、§4.5；BR-ATTR-27 淘宝行）。执行器与 apps.json 判定在 feature:trade（APP-06b）。
 *
 * 百川（淘宝）SDK 本任务不引入（任务书 §2.3、§3）：这里只有接口与「不可用」实现。AppKey、回跳 scheme 负责人未给，
 * 用 [BaichuanAdapter.APP_KEY_PLACEHOLDER] 占位（TODO(N-28)，rule-gaps）。
 */
object TradeJumpModule

/** 百川打开结果。 */
enum class BaichuanOpenResult {
    /** 已拉起淘宝（或百川内的商品页）。 */
    LAUNCHED,

    /** 已初始化但拉起失败（含电商 SDK 未开通）。 */
    FAILED,

    /** 初始化失败（重试 1 次后仍失败），与 [FAILED] 分开上报 sdk_init_failed（BR-ATTR-27 淘宝行）。 */
    INIT_FAILED,
}

/** 百川授权结果（sdk_token，BR-ID-17 细则「授权方式」）。 */
sealed interface BaichuanAuthResult {
    /** SDK 返回的访问令牌与有效期（秒）：只交给 bindings，不落库、不进日志。 */
    class Token(
        val accessToken: String,
        val expiresIn: Long,
    ) : BaichuanAuthResult

    /** 用户取消。 */
    data object Cancelled : BaichuanAuthResult

    /** 授权失败或 SDK 不可用。 */
    data object Failed : BaichuanAuthResult
}

/**
 * 百川适配层（BR-ATTR-27 淘宝行、03 §4.5）：
 * - [initialize] 只由 PrivacyGate.onConsented 的「联盟」步骤调用（BR-ID-11，硬规则 5）；失败时记下；
 * - 下一次执行淘宝 sdk 步骤之前 [retryInitIfFailed] 重试初始化 1 次，仍失败就跳过 sdk 步骤、按 fallbacks 继续；
 * - [open] 原样交出服务端下发的 sdk 步骤 value，不改不补参数（BR-ATTR-05）。
 */
interface BaichuanAdapter {
    /** SDK 当前可用（已初始化成功）。 */
    val ready: Boolean

    /** 同意隐私政策后初始化（只在 PrivacyGate.onConsented 里调）。返回是否成功。 */
    fun initialize(): Boolean

    /** 初始化曾失败时重试 1 次（下一次执行 sdk 步骤之前）；返回重试后是否可用。没失败过（或从未初始化）不重试，按当前结论返回。 */
    fun retryInitIfFailed(): Boolean

    /** 执行 sdk 步骤（value 原样）。 */
    fun open(value: String): BaichuanOpenResult

    /** 能否执行 sdk_token 授权（AuthMethodPicker 的本端能力）。 */
    val supportsSdkToken: Boolean

    /** sdk_token 授权（挂起直到 SDK 回调）。 */
    suspend fun authorize(): BaichuanAuthResult

    companion object {
        /** 百川 AppKey 占位：负责人还没有提供（TODO(N-28)，任务书 §0「不臆造」）。 */
        const val APP_KEY_PLACEHOLDER = "TODO_N28_BAICHUAN_APP_KEY"
    }
}

/**
 * 不可用实现（生产与本任务的唯一实现）：没有 SDK、没有 AppKey，初始化一律失败；sdk 步骤报 [BaichuanOpenResult.INIT_FAILED]，
 * 执行器据此跳过 sdk 步骤、按下发的 fallbacks 继续，没有后续步骤时提示 jump.taobao_sdk_unavailable、不外跳（BR-ATTR-27）。
 * sdk_token 授权不可用（[supportsSdkToken] = false）。
 */
class UnavailableBaichuan(
    @Suppress("UnusedPrivateProperty") // 占位：接入 SDK 时用（TODO(N-28)）
    private val appKey: String = BaichuanAdapter.APP_KEY_PLACEHOLDER,
) : BaichuanAdapter {
    @Volatile
    private var initFailed = false

    /** 初始化尝试次数（同意后 1 次 + 每次 sdk 步骤前的重试），只用于观察。 */
    @Volatile
    var initAttempts: Int = 0
        private set

    override val ready: Boolean get() = false

    override fun initialize(): Boolean {
        initAttempts += 1
        initFailed = true
        return false
    }

    override fun retryInitIfFailed(): Boolean {
        if (!initFailed) return ready
        initAttempts += 1
        return false
    }

    override fun open(value: String): BaichuanOpenResult = BaichuanOpenResult.INIT_FAILED

    override val supportsSdkToken: Boolean get() = false

    override suspend fun authorize(): BaichuanAuthResult = BaichuanAuthResult.Failed
}
