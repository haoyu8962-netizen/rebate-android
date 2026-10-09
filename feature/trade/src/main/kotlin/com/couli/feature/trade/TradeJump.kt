package com.couli.feature.trade

import com.couli.core.tradejump.BaichuanAdapter
import com.couli.core.tradejump.BaichuanOpenResult
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 外跳的系统能力（App 给 Android 实现；测试给替身）。只在用户点击触发的流程里调用（BR-ATTR-21：无点击不外跳）。
 */
interface JumpPorts {
    /**
     * 目标 App 安装状态（BR-ATTR-27 ①）："true" / "false" / "unknown"。只查 apps.json 声明的包名；apps.json 的 Android 包名为空
     * （candidate，未给值）时一律 unknown，检测失败也是 unknown，不能当 false（G05，TODO(N-28)）。
     */
    fun installed(platform: String): String

    /** scheme / universal_link 步骤：只拉起目标 App（不交给浏览器）。返回是否拉起。 */
    fun launchApp(
        type: String,
        value: String,
    ): Boolean

    /** h5 步骤：交系统浏览器（不进本 App 的两类容器，03 §4.5、AuthJumpStep；不借 App Links 拉起别的 App）。返回是否打开。 */
    fun openBrowser(url: String): Boolean

    /** copy_tpwd：复制口令（App 自己写入的内容记 hash，剪贴板识别不再提示，03 §4.6）。返回是否复制成功。 */
    fun copy(text: String): Boolean
}

/** link_jump 上报口（App 接 Tracker 本地队列，上报 sink 仍为 TODO(N-20)）：参数是 [LinkJumpEvents.build] 的一条业务字段。 */
fun interface LinkJumpSink {
    fun report(fields: JsonObject)
}

/** 这次外跳属于哪一次购买（open 响应的 attempt_id 与替换后的 link_id）。auth_jump 为 null（不上报 link_jump）。 */
data class PurchaseIdentity(
    val attemptId: String,
    val linkId: String,
)

/** 执行结果：[run] 的 final 外加用户在浏览器提示里取消（[cancelled]）、sdk 步骤初始化失败（[sdkUnavailable]）。 */
data class JumpOutcome(
    val run: JumpRun,
    val cancelled: Boolean = false,
    /** 执行过淘宝 sdk 步骤且初始化 / 拉起失败（没有后续步骤时提示 jump.taobao_sdk_unavailable，BR-ATTR-27 淘宝行）。 */
    val sdkUnavailable: Boolean = false,
    /** 方案里有复制口令的步骤且已复制。 */
    val copied: Boolean = false,
    /** 打开了系统浏览器。 */
    val browser: Boolean = false,
    /**
     * 方案已过 expire_at（或 expire_at 缺失、无法解析，保守按过期）：从这一步起不再执行、不上报（A-R1-12，契约 JumpPlan.expire_at）；
     * 调用方提示重新点购买取新方案。
     */
    val expired: Boolean = false,
    /** 续执行许可已作废（进入后台、被覆盖、离开、用户再点）：从这一步起不再执行、不上报（A-R1-03，03 §4.5）。 */
    val stopped: Boolean = false,
)

/**
 * 跳转方案执行器（03 §4.5、BR-ATTR-27）：购买外跳与授权跳转（AuthJumpPlan）共用。按 [JumpPlanRunner.run] 的同一个循环执行，
 * 每一步结果都上报 link_jump（[identity] 为 null 即授权跳转，不上报）；sdk 步骤先让百川按「失败过就重试 1 次」初始化（BR-ATTR-27），
 * 仍不可用记 sdk_init_failed 并继续下一步。h5 步骤打开系统浏览器之前，先经 [confirmBrowser] 给出「将通过浏览器打开」的提示（可取消；
 * BR-ATTR-27 京东行），取消即停止，之后的步骤不执行、不上报。
 */
@Suppress("TooManyFunctions") // 执行、启动上报、有效期与各步骤
class TradeJumpExecutor(
    private val ports: JumpPorts,
    private val baichuan: BaichuanAdapter,
    private val sink: LinkJumpSink,
    private val elapsedMillis: () -> Long = System::currentTimeMillis,
    /** 墙上时钟（判 expire_at 用，A-R1-12）；测试注入。 */
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * 执行方案。每一步执行前（含 sdk 初始化、浏览器提示这些等待之后）都复核：[allowed]（「允许开始下一步」：本次点击的续执行许可仍有效、
     * 页面在前台，购买外跳还要平台此刻仍可购买）与 expire_at（[now] 已到或无法判定即停）；不满足即停止，这一步与之后的步骤不执行、不上报。
     * 已执行步骤的上报只看 [reportable]（「允许上报已执行步骤」：尝试仍是当前的、没被取消；进入后台、平台此刻关闭都照常上报，APP-06b
     * 共性第 40 条），缺省与 [allowed] 相同。
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod", "LongMethod") // 方案、平台、安装状态、归属、浏览器提示与许可逐项给
    suspend fun execute(
        plan: JsonObject,
        platform: String,
        installed: String,
        identity: PurchaseIdentity?,
        allowed: () -> Boolean = { true },
        confirmBrowser: suspend () -> Boolean = { true },
        reportable: () -> Boolean = allowed,
    ): JumpOutcome {
        var cancelled = false
        var sdkFailed = false
        var copied = false
        var browser = false
        var expired = false
        var stopped = false
        val app = installed.toBooleanStrictOrNull()
        val expireAt = expireAtMillis(plan)

        fun halted(): Boolean {
            if (cancelled || expired || stopped) return true
            if (!allowed()) {
                stopped = true
            } else if (expireAt == null || now() >= expireAt) {
                expired = true
            }
            return stopped || expired
        }
        val run =
            JumpPlanRunner.run(JumpPlanRunner.steps(plan), app) { _, step ->
                if (halted()) return@run JumpTypes.FAILED
                val type = step.text("type").orEmpty()
                val value = step.text("value").orEmpty()
                val started = elapsedMillis()
                val result =
                    when (type) {
                        JumpTypes.SDK -> {
                            sdk(value).also { if (it != JumpTypes.LAUNCHED) sdkFailed = true }
                        }

                        JumpTypes.SCHEME, JumpTypes.UNIVERSAL_LINK -> {
                            if (value.isNotEmpty() && ports.launchApp(type, value)) JumpTypes.LAUNCHED else JumpTypes.FAILED
                        }

                        JumpTypes.H5 -> {
                            if (!confirmBrowser()) {
                                cancelled = true
                                return@run JumpTypes.FAILED
                            }
                            // 提示停留期间进入后台、过期：不打开浏览器、不上报
                            if (halted()) return@run JumpTypes.FAILED
                            (if (value.isNotEmpty() && ports.openBrowser(value)) JumpTypes.LAUNCHED else JumpTypes.FAILED)
                                .also { browser = it == JumpTypes.LAUNCHED }
                        }

                        JumpTypes.COPY_TPWD -> {
                            (if (value.isNotEmpty() && ports.copy(value)) JumpTypes.COPIED else JumpTypes.FAILED)
                                .also { copied = it == JumpTypes.COPIED }
                        }

                        // 契约枚举以外的步骤：不执行（不臆造执行方式，G14），按失败继续
                        else -> {
                            JumpTypes.FAILED
                        }
                    }
                // 上报前再复核：尝试仍是当前的、没被取消（等待之后被取消的不上报；03 §4.5、共性第 40 条）
                if (identity != null && type in KNOWN_TYPES && reportable()) {
                    report(identity, platform, type, result, elapsedMillis() - started, installed)
                }
                result
            }
        return JumpOutcome(
            run,
            cancelled = cancelled,
            sdkUnavailable = sdkFailed,
            copied = copied,
            browser = browser,
            expired = expired,
            stopped = stopped,
        )
    }

    /** 方案此刻是否已过期（expire_at 缺失、无法解析按已过期，保守不外跳）。 */
    fun expired(plan: JsonObject): Boolean = expireAtMillis(plan)?.let { now() >= it } ?: true

    private fun expireAtMillis(plan: JsonObject): Long? =
        plan.text("expire_at")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    /** 百川初始化失败（同意隐私后的那次）：phase=startup 上报 sdk_init_failed（events.yaml link_jump，BR-ATTR-27 淘宝行）。 */
    fun reportStartupInitFailed() {
        LinkJumpEvents
            .build(
                attemptId = null,
                linkId = null,
                steps =
                    listOf(
                        JsonObject(
                            mapOf(
                                "phase" to JsonPrimitive(LinkJumpEvents.PHASE_STARTUP),
                                "union_platform" to JsonPrimitive(TAOBAO),
                                "step" to JsonPrimitive(JumpTypes.SDK),
                                "result" to JsonPrimitive(JumpTypes.SDK_INIT_FAILED),
                            ),
                        ),
                    ),
                isAuthJump = false,
            ).forEach(sink::report)
    }

    private fun sdk(value: String): String {
        // 下一次执行 sdk 步骤之前重试初始化 1 次（BR-ATTR-27 淘宝行）；仍不可用就跳过、记 sdk_init_failed
        if (!baichuan.ready && !baichuan.retryInitIfFailed()) return JumpTypes.SDK_INIT_FAILED
        return when (baichuan.open(value)) {
            BaichuanOpenResult.LAUNCHED -> JumpTypes.LAUNCHED
            BaichuanOpenResult.INIT_FAILED -> JumpTypes.SDK_INIT_FAILED
            BaichuanOpenResult.FAILED -> JumpTypes.FAILED
        }
    }

    @Suppress("LongParameterList") // link_jump 的字段逐项
    private fun report(
        identity: PurchaseIdentity,
        platform: String,
        type: String,
        result: String,
        duration: Long,
        installed: String,
    ) {
        val step =
            JsonObject(
                mapOf(
                    "phase" to JsonPrimitive(LinkJumpEvents.PHASE_PURCHASE),
                    "union_platform" to JsonPrimitive(platform),
                    "step" to JsonPrimitive(type),
                    "result" to JsonPrimitive(result),
                    "duration_ms" to JsonPrimitive(duration.coerceAtLeast(0L)),
                    "installed" to JsonPrimitive(installed),
                ),
            )
        LinkJumpEvents.build(identity.attemptId, identity.linkId, listOf(step), isAuthJump = false).forEach(sink::report)
    }

    private companion object {
        const val TAOBAO = "taobao"
        val KNOWN_TYPES = setOf(JumpTypes.SDK, JumpTypes.SCHEME, JumpTypes.UNIVERSAL_LINK, JumpTypes.H5, JumpTypes.COPY_TPWD)
    }
}
