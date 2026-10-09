// 错误码与金额阈值是契约取值（contracts/error-codes.yaml），按码直接写
@file:Suppress("MagicNumber")

package com.couli.feature.trade

import com.couli.core.network.ErrorActionMapper
import com.couli.generated.DefaultTexts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/*
 * APP-06b 购买流程的纯逻辑（三端同名；向量 specs/open-trade-vectors.json，测试 OpenTradeVectorTest）。执行器（OpenPurchaseController、
 * TradeJumpExecutor）直接调用这些函数，向量测到的就是生产判定。规则空白按保守处理（不外跳、不放行），标 TODO(N-28)。
 */

/**
 * 一次购买尝试的 Idempotency-Key 生命周期（拍板第二批 TRADE-22、BR-ID-10 细则「恢复执行时用哪个幂等键」）：
 * - [start]：一次新的点击（含取消、离开后再点）→ 新键；
 * - [timeout] / [retry]：8 秒超时后的【重试】是同一次点击的重发 → 原键；
 * - [authRequired]（30101 / 30102 / 30111）后 [resendAfterAuth]：授权后再发是新请求 → 新键（3xxxx 已写入幂等记录）；
 * - 10001 登录后的重放沿用原键（1xxxx 不写幂等记录），用 [retry]；
 * - [abandon] / [cancel] 只是本机放弃：open 不属于作废接口的四类 step-up 操作，不调 abandonIdempotencyKey（[abandonCalled] 恒为 false）。
 */
class OpenAttempt(
    private val keyGenerator: () -> String,
) {
    /** 当前（最近一次发送用的）键；还没点过为 null。 */
    var currentKey: String? = null
        private set

    /** 是否调了服务端作废接口：open 不在作废接口的四类操作里，恒为 false（向量 abandon_open_is_local_only）。 */
    val abandonCalled: Boolean get() = false

    /** 被授权码拦下、等授权完成（之后 [resendAfterAuth] 换新键）。 */
    var awaitingAuth: Boolean = false
        private set

    /** 新的点击：新键。 */
    fun start(): String = fresh()

    /** 8 秒没有响应：键不变（等用户点【重试】）。 */
    fun timeout() = Unit

    /** 同一请求的重发（超时【重试】、登录后的重放）：沿用原键；还没有键时等同 [start]。 */
    fun retry(): String = currentKey ?: fresh()

    /** 被 30101 / 30102 / 30111 拦下。 */
    fun authRequired(
        @Suppress("UnusedParameter") code: Int,
    ) {
        awaitingAuth = true
    }

    /** 授权完成后重新发起同一 link 的 open：新请求、新键。 */
    fun resendAfterAuth(): String {
        awaitingAuth = false
        return fresh()
    }

    /** 用户取消：只挡本机外跳，服务端的点击证据与幂等结果照常保留（03 §4.5），不作废键。 */
    fun cancel() = Unit

    /** 本机放弃（超时后不重试）：不调作废接口。 */
    fun abandon() = Unit

    private fun fresh(): String = keyGenerator().also { currentKey = it }
}

/** open 成功响应 → 客户端动作（03 §7.4 表）。[actionRaw] 是三端统一的接线标签。 */
data class OpenResponseOutcome(
    val actionRaw: String,
    /** 带 new_link_id 时立即替换卡片（不等确认 / 取消，BR-PRICE-12）。 */
    val replaceLinkId: String?,
    /** requote_failed=true：照常外跳，同时提示价格以下单页为准（BR-PRICE-13）。 */
    val requoteNotice: Boolean,
    /** 返利由 > 0 变为 0（new_rebate_max_fen = 0，BR-PRICE-13）：改价 / 券失效的确认框里追加无返利说明。 */
    val rebateGone: Boolean = false,
    val oldFinalFen: Long? = null,
    val newFinalFen: Long? = null,
)

/** open 响应动作标签。 */
object OpenActions {
    const val JUMP = "jump"
    const val CONFIRM_PRICE = "confirm_price"
    const val CONFIRM_REBATE_GONE = "confirm_rebate_gone"
    const val CONFIRM_COUPON = "confirm_coupon"
    const val COMPARE_NO_REBATE = "compare_no_rebate"

    /** availability=off_shelf：不外跳、卡片置灰（30141 同义；向量未覆盖，G07）。 */
    const val OFF_SHELF = "off_shelf"
}

/**
 * open 成功响应 → 动作（03 §7.4、BR-PRICE-08 / 12 / 13 / 14）。多种同时成立时 off_shelf > coupon_gone > price_changed（03 §7.4），
 * 返利归零与改价、券失效合并在同一个确认框里（[OpenResponseOutcome.rebateGone]）；没有改价只有返利归零时：
 * no_rebate_cause=price_compare 弹比价无返利（BR-PRICE-08，替代通用无返利确认），其余（含未知原因，按 null）弹无返利确认。
 * amount_unknown（context.amountUnknown=true）不做价格复核提示（BR-PRICE-08）。金额读成 Long，null 保持未知、不补 0。
 *
 * context：{ currentPriceFen, currentRebateMaxFen, amountUnknown }（卡片当前的值）。
 */
object OpenResponseAction {
    private const val OFF_SHELF = "off_shelf"
    private const val COUPON_GONE = "coupon_gone"
    private const val PRICE_COMPARE = "price_compare"

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // 03 §7.4 表逐行；off_shelf、金额未知两个提前出口
    fun map(
        response: JsonObject,
        context: JsonObject,
    ): OpenResponseOutcome {
        val data = response["data"] as? JsonObject ?: JsonObject(emptyMap())
        val replace = data.text("new_link_id")
        val requote = data.flag("requote_failed")
        val oldFinal = data.fen("old_final_price_fen")
        val newFinal = data.fen("new_final_price_fen")
        val amountUnknown = context.flag("amountUnknown")
        val base = OpenResponseOutcome(OpenActions.JUMP, replace, requote, oldFinalFen = oldFinal, newFinalFen = newFinal)
        if (data.text("availability") == OFF_SHELF) return base.copy(actionRaw = OpenActions.OFF_SHELF)
        if (amountUnknown) return base
        val before = context.fen("currentRebateMaxFen")
        val after = data.fen("new_rebate_max_fen")
        val rebateGone = before != null && before > 0 && after == 0L
        val compare = rebateGone && data.text("no_rebate_cause") == PRICE_COMPARE
        return when {
            data.text("availability") == COUPON_GONE -> base.copy(actionRaw = OpenActions.CONFIRM_COUPON, rebateGone = rebateGone)
            data.flag("price_changed") -> base.copy(actionRaw = OpenActions.CONFIRM_PRICE, rebateGone = rebateGone)
            compare -> base.copy(actionRaw = OpenActions.COMPARE_NO_REBATE, rebateGone = true)
            rebateGone -> base.copy(actionRaw = OpenActions.CONFIRM_REBATE_GONE, rebateGone = true)
            else -> base
        }
    }
}

/** open 错误码 → 动作。 */
data class OpenErrorOutcome(
    val actionRaw: String,
    val textKey: String?,
    val showTrace6: Boolean,
    /** 50301 not_launched：购买按钮置灰、不弹 Toast（error-codes 50301）。 */
    val silent: Boolean = false,
    /** 给【仍去购买（无返利）】（30103、30121、30151、30153、50303，BR-ID-18、BR-PRICE-08）。 */
    val offerNoRebate: Boolean = false,
    /** 给【联系客服】（30103、30151、30153）。 */
    val offerContact: Boolean = false,
    /** 给【重试】（50303 主按钮）。 */
    val offerRetry: Boolean = false,
)

/** open 错误动作标签。 */
object OpenErrorActions {
    const val LOGIN = "login"
    const val AUTH_SHEET = "auth_sheet"
    const val AUTH_JUMP = "auth_jump"
    const val NOTICE = "notice"
    const val ERROR = "error"
    const val RETRY_AFTER = "retry_after"

    /** 30143：引导重新搜索（向量未覆盖，G08）。 */
    const val RESEARCH = "research"

    /** 30144：按卡片 product_key + item_ref 重取卡片，之后仍须用户再点（G08）。 */
    const val REFETCH = "refetch"

    /** 10405：强更 / 受限会话刷新由网络层与外壳处理，页面只结束加载（G08）。 */
    const val FORCE_UPDATE = "force_update"
}

/**
 * open 错误码 → 动作（error-codes.yaml、03 §7.4、BR-ID-17 / 18 / 22 / 24）。文案键 error.<code>.<reason> 回落 error.<code>
 * （BR-TEXT-14）；5xxxx 带 trace6。auth_unavailable 只提示，不拉起授权、不给无返利购买（BR-ID-24）。
 */
object OpenErrorAction {
    private const val AUTH_UNAVAILABLE = "auth_unavailable"
    private const val NOT_LAUNCHED = "not_launched"

    @Suppress("CyclomaticComplexMethod") // 逐码对照 error-codes.yaml
    fun map(
        code: Int,
        data: JsonObject,
        hasText: (String) -> Boolean = { DefaultTexts.text(it) != null },
    ): OpenErrorOutcome {
        val reason = data.text("reason")
        val key = ErrorActionMapper.resolveTextKey(code, reason, hasText)
        val serverError = code in 50_000..59_999
        return when (code) {
            10001 -> {
                OpenErrorOutcome(OpenErrorActions.LOGIN, key, false)
            }

            30101, 30102 -> {
                val action = if (reason == AUTH_UNAVAILABLE) OpenErrorActions.NOTICE else OpenErrorActions.AUTH_SHEET
                OpenErrorOutcome(action, key, false)
            }

            30111 -> {
                OpenErrorOutcome(OpenErrorActions.AUTH_JUMP, key, false)
            }

            30103, 30151, 30153 -> {
                OpenErrorOutcome(OpenErrorActions.NOTICE, key, false, offerNoRebate = true, offerContact = true)
            }

            30121 -> {
                OpenErrorOutcome(OpenErrorActions.NOTICE, key, false, offerNoRebate = true)
            }

            30141 -> {
                OpenErrorOutcome(OpenErrorActions.ERROR, key, false)
            }

            30143 -> {
                OpenErrorOutcome(OpenErrorActions.RESEARCH, key, false)
            }

            30144 -> {
                OpenErrorOutcome(OpenErrorActions.REFETCH, key, false)
            }

            10405 -> {
                OpenErrorOutcome(OpenErrorActions.FORCE_UPDATE, null, false)
            }

            50301 -> {
                OpenErrorOutcome(OpenErrorActions.NOTICE, key, true, silent = reason == NOT_LAUNCHED)
            }

            50303 -> {
                OpenErrorOutcome(OpenErrorActions.ERROR, key, true, offerNoRebate = true, offerRetry = true)
            }

            42901 -> {
                OpenErrorOutcome(OpenErrorActions.RETRY_AFTER, key, false)
            }

            else -> {
                OpenErrorOutcome(if (serverError) OpenErrorActions.ERROR else OpenErrorActions.NOTICE, key, serverError)
            }
        }
    }
}

/** 迟到响应的处理：是否允许继续外跳流程、是否用它更新卡片。 */
data class LateDecision(
    val jump: Boolean,
    val updateCard: Boolean,
)

/**
 * 取消、离开发起页、App 进入后台之后才到达的 open 响应：只更新卡片（新价格、new_link_id、无返利状态），不外跳、不弹确认、
 * 不上报 link_jump（03 §4.5）。这里只判「仍属于最近一次点击」的响应；更早点击的响应由执行器按点击编号直接丢弃。
 */
object LateResponsePolicy {
    const val NONE = "none"
    const val CANCEL = "cancel"
    const val LEAVE = "leave"
    const val BACKGROUND = "background"

    fun decide(interrupt: String): LateDecision = LateDecision(jump = interrupt == NONE, updateCard = true)
}

/** 跳转方案的执行结果。 */
data class JumpRun(
    /** 实际执行到的步骤类型（按顺序，含初始化失败的 sdk）。 */
    val executed: List<String>,
    /** opened（拉起 App）/ browser（系统浏览器）/ copied / not_installed（未装、复制口令后仍提示安装）/ failed。 */
    val final: String,
)

/** 跳转方案步骤类型与结果（契约 jump_type、events.yaml link_jump.result）。 */
object JumpTypes {
    const val SDK = "sdk"
    const val SCHEME = "scheme"
    const val UNIVERSAL_LINK = "universal_link"
    const val H5 = "h5"
    const val COPY_TPWD = "copy_tpwd"

    const val LAUNCHED = "launched"
    const val FAILED = "failed"
    const val SDK_INIT_FAILED = "sdk_init_failed"
    const val COPIED = "copied"

    const val FINAL_OPENED = "opened"
    const val FINAL_BROWSER = "browser"
    const val FINAL_COPIED = "copied"
    const val FINAL_NOT_INSTALLED = "not_installed"
    const val FINAL_FAILED = "failed"
}

/**
 * 按 primary、fallbacks 顺序执行（03 §4.5、BR-ATTR-27；客户端不自行拼 scheme、不追加兜底）：
 * - 某步 launched → 停：h5 为 browser（系统浏览器），其余 opened；
 * - copy_tpwd copied → 停：明确未安装（installed=false）时 not_installed（复制口令后仍提示安装，BR-ATTR-27 未安装列），否则 copied；
 * - failed / sdk_init_failed → 下一步；全部失败 → failed。
 * 不读时钟、不按 expire_at 判过期（G14）。[run] 是执行器与向量共用的同一个循环。
 */
object JumpPlanRunner {
    /** 方案里的步骤（primary + fallbacks），保留 value 给执行器；类型不在契约枚举里的步骤保守跳过（G14）。 */
    fun steps(plan: JsonObject): List<JsonObject> {
        val primary = plan["primary"] as? JsonObject
        val fallbacks = (plan["fallbacks"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        return listOfNotNull(primary) + fallbacks
    }

    /** 逐步执行：[execute] 返回该步结果（launched / failed / sdk_init_failed / copied）。 */
    @Suppress("ReturnCount") // 拉起、复制两个停止出口
    inline fun run(
        steps: List<JsonObject>,
        installedApp: Boolean?,
        execute: (index: Int, step: JsonObject) -> String,
    ): JumpRun {
        val executed = mutableListOf<String>()
        steps.forEachIndexed { index, step ->
            val type = (step["type"] as? JsonPrimitive)?.content.orEmpty()
            executed += type
            when (execute(index, step)) {
                JumpTypes.LAUNCHED -> {
                    return JumpRun(executed, if (type == JumpTypes.H5) JumpTypes.FINAL_BROWSER else JumpTypes.FINAL_OPENED)
                }

                JumpTypes.COPIED -> {
                    return JumpRun(executed, if (installedApp == false) JumpTypes.FINAL_NOT_INSTALLED else JumpTypes.FINAL_COPIED)
                }

                else -> {
                    Unit
                }
            }
        }
        return JumpRun(executed, JumpTypes.FINAL_FAILED)
    }

    /** 向量用：步骤结果按位置注入，未执行的结果不消费；installed.app 是注入的检测结果。 */
    fun simulate(
        plan: JsonObject,
        installed: JsonObject,
        stepResults: List<String>,
    ): JumpRun {
        val app = (installed["app"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        return run(steps(plan), app) { index, _ -> stepResults.getOrElse(index) { JumpTypes.FAILED } }
    }
}

/**
 * link_jump 的事件业务字段（specs/events.yaml；03 §4.5、§4.7）：每一步一条，不排序、不去重；phase=purchase 带本次 open 响应的
 * attempt_id 与（替换后的）link_id、duration_ms、installed；startup 只有 phase / union_platform / step / result。步骤的 value
 * （完整推广 URL、口令）一律不上报（02 §12.6）。auth_jump 不上报（AuthJumpPlan：不是购买外跳）。缺编号的 purchase 步骤不上报
 * （生成函数要求必填；正常流程不会出现）。
 */
object LinkJumpEvents {
    const val PHASE_PURCHASE = "purchase"
    const val PHASE_STARTUP = "startup"
    private val PURCHASE_FIELDS = listOf("union_platform", "step", "result", "duration_ms", "installed")
    private val STARTUP_FIELDS = listOf("union_platform", "step", "result")

    fun build(
        attemptId: String?,
        linkId: String?,
        steps: List<JsonObject>,
        isAuthJump: Boolean,
    ): List<JsonObject> {
        if (isAuthJump) return emptyList()
        return steps.mapNotNull { step ->
            when ((step["phase"] as? JsonPrimitive)?.content) {
                PHASE_PURCHASE -> {
                    if (attemptId == null || linkId == null) return@mapNotNull null
                    val fields =
                        linkedMapOf<String, JsonElement>(
                            "phase" to JsonPrimitive(PHASE_PURCHASE),
                            "attempt_id" to JsonPrimitive(attemptId),
                            "link_id" to JsonPrimitive(linkId),
                        )
                    PURCHASE_FIELDS.forEach { key -> step[key]?.let { fields[key] = it } }
                    JsonObject(fields)
                }

                PHASE_STARTUP -> {
                    val fields = linkedMapOf<String, JsonElement>("phase" to JsonPrimitive(PHASE_STARTUP))
                    STARTUP_FIELDS.forEach { key -> step[key]?.let { fields[key] = it } }
                    JsonObject(fields)
                }

                else -> {
                    null
                }
            }
        }
    }
}

/**
 * 淘宝授权方式（BR-ID-17 细则「授权方式」）：按服务端下发的 auth_methods 顺序取本端能执行的第一种，不自行增减；
 * [supported] 是本端此刻的能力（web_code：系统浏览器 + 已登记的回跳；sdk_token：百川可用）。其余平台不走授权方式（拼多多走 auth_jump）。
 */
object AuthMethodPicker {
    const val WEB_CODE = "web_code"
    const val SDK_TOKEN = "sdk_token"
    private const val TAOBAO = "taobao"

    fun pick(
        platform: String,
        authMethods: List<String>,
        supported: List<String>,
    ): String? = if (platform != TAOBAO) null else authMethods.firstOrNull { it in supported }
}

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

/** 整数分；null、缺失、字符串、小数都按未知（null），不补 0。 */
internal fun JsonObject.fen(key: String): Long? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

internal fun JsonObject.texts(key: String): List<String> =
    (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
