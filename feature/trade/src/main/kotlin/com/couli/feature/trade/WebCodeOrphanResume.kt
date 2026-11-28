// FU-AND-TB-WEBCODE-B 接口桩：签名与 KDoc 是写测方定的口径，函数体全部是桩（实现方替换）。
@file:Suppress("UNUSED_PARAMETER", "UnusedParameter", "UnusedPrivateProperty", "FunctionOnlyReturningConstant")

package com.couli.feature.trade

import kotlinx.coroutines.CoroutineScope

/**
 * 「绑定变化」信号（设计稿 §3.2 AuthManage、§6.4 第 5 步 active 行、§6.5）：冷启动 / 晚到回跳绑定成功后发出，授权管理页收到后再 load() 一次。
 * 进程里一份（App 的 AppTrade 持有）；只在主线程使用。
 * TODO(FU-AND-TB-WEBCODE-B 接口桩)：[notifyChanged] 不通知任何人，[subscribe] 不登记。
 */
class BindingChanges {
    /** 绑定变了：同步通知全部订阅者。 */
    fun notifyChanged() = Unit

    /** 登记订阅；返回取消订阅。 */
    fun subscribe(listener: () -> Unit): () -> Unit = {}
}

/**
 * 冷启动恢复槽（设计稿 §6.4 第 6 步；D10-3681 C-4）：只在内存，只放 1 张 [Ticket]。
 * - [offer]：登记（新的覆盖旧的）；
 * - [claim]：卡片的 link_id 或 product_key 与槽位一致、且没到 deadline → 取走并返回（同一张只能被认领一次），否则 null；
 * - [takeExpired]：过了 deadline 还没人认领 → 取走并返回一次（调用方据此只提示 auth.taobao.completed_tap_again，不自动打开任何页面）；
 * - [peek]：没到期的那张；[clear]：退出登录、切换账号、切换环境时作废。
 * TODO(FU-AND-TB-WEBCODE-B 接口桩)：什么都不存。
 */
class ColdAuthResume(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [linkId] 是购买待办里的 link_id（认领后 open 打它，BR-ID-17「同一 link_id」）；[deadlineMillis] = 待办续时之后再加 10 分钟。 */
    data class Ticket(
        val linkId: String,
        val productKey: String?,
        val platform: String,
        val deadlineMillis: Long,
    )

    fun offer(ticket: Ticket) = Unit

    fun peek(): Ticket? = null

    fun claim(
        linkId: String?,
        productKey: String?,
    ): Ticket? = null

    fun takeExpired(): Ticket? = null

    fun clear() = Unit
}

/** 冷启动 / 晚到回跳的启动闸门结论（设计稿 §6.4 第 1 步）。 */
enum class WebCodeOrphanStartup {
    /** 环境 settled、隐私已同意、会话已恢复且已登录、版本检查有结果且不低于最低版本。 */
    READY,

    /** 基本模式（隐私没同意完整功能）。 */
    BASIC_MODE,

    /** 未登录。 */
    LOGGED_OUT,

    /** 低于最低版本（强更）。 */
    BELOW_MIN_VERSION,

    /** 等满 [WebCodeOrphanHandler.VERSION_WAIT_MILLIS] 版本检查仍没有结果（D10-3681 B-4：不提交 bindings、丢弃这次回跳）。 */
    VERSION_UNKNOWN,
}

/** 启动闸门（App 给）：等环境、隐私、会话、版本检查都有结论；版本检查最多等 [versionWaitMillis]，仍未知返回 VERSION_UNKNOWN。 */
fun interface WebCodeOrphanGate {
    suspend fun await(versionWaitMillis: Long): WebCodeOrphanStartup
}

/** 冷启动 / 晚到回跳在前台的提示（App 给；文案按键取，TradeTexts）。 */
interface WebCodeOrphanUi {
    /** 不可关闭的轻量浮层「正在确认授权结果」（auth.confirming）：提交 bindings 期间 true，结束后 false。 */
    fun confirming(shown: Boolean)

    /** 轻提示（文案键）。 */
    fun toast(key: String)
}

/** App 层的冷启动 / 晚到回跳浮层状态（AppTrade 对外；界面按它画不可关闭的「正在确认」浮层与轻提示）。 */
data class WebCodeOrphanOverlay(
    val confirming: Boolean = false,
    val toast: PurchaseToast? = null,
)

/**
 * 无人认领的 web_code 回跳（设计稿 §6.4；D10-3681 B-4 / C-4）：进程被回收后冷启动回跳，或本次尝试已结束后才到的晚到回跳。
 * [handle] 逐步：
 * 1. [gate]（版本检查最多等 [VERSION_WAIT_MILLIS]）：基本模式、未登录、低于最低版本、版本仍未知 → 丢弃，不发任何请求；
 * 2. 核对 [attempts]：记录存在、sha256(state) 一致、accountTag 等于 [accountTag]（当前账号标签）；不符静默丢弃；通过就删记录；
 * 3. [WebCodeCallback.Incomplete]（error=1，设计稿里的 Denied / Error）：冷启动（记录发起于 [processStartedAtMillis] 之前）且购买待办还在 →
 *    删待办、提示一次 error.20004；晚到回跳（本进程发起的）→ 什么都不做；
 * 4. [WebCodeCallback.Code]：[WebCodeOrphanUi.confirming] 显示，经 [flow] 的 submit 提交 bindings（新幂等键，请求体只有 state、auth_method、code），
 *    结束后隐藏；
 * 5. 结果：active → [bindingChanges] 通知；冷启动且有效待办（kind=auth、platform=taobao、[PurchasePending.peek] 不为 null）→ stepDone 续时、
 *    向 [resume] 登记一张 deadline = 续时后 + 10 分钟的票，到期没人认领只提示 [COMPLETED_TAP_AGAIN_KEY]；没有有效待办（含一切晚到回跳）→
 *    只提示 [COMPLETED_TAP_AGAIN_KEY]，不重放 open。30104 / 20001 → 删待办、提示 error.30104；30151 / 30153 → 删待办、提示该码文案；
 *    30101 / 30102 带 auth_unavailable → 删待办、提示 error.<码>.auth_unavailable.auth_manage；42901 / 5xxxx / 传输失败 → 保留待办、提示网络或服务错误。
 * 不经 Router 打开任何页面，不调 open。
 * TODO(FU-AND-TB-WEBCODE-B 接口桩)：[handle] 什么都不做。
 */
@Suppress("LongParameterList") // 依赖逐项注入
class WebCodeOrphanHandler(
    private val flow: TaobaoAuthFlow,
    private val attempts: WebCodeAttemptStore,
    private val pending: PurchasePending,
    private val gate: WebCodeOrphanGate,
    private val accountTag: () -> String?,
    private val resume: ColdAuthResume,
    private val bindingChanges: BindingChanges,
    private val ui: WebCodeOrphanUi,
    private val scope: CoroutineScope,
    private val processStartedAtMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun handle(callback: WebCodeCallback) = Unit

    companion object {
        /** 版本检查最多等 5 秒（03 §4.4；D10-3681 B-4）。 */
        const val VERSION_WAIT_MILLIS: Long = 5_000L

        /** 恢复槽的有效期：待办续时之后再加 10 分钟。 */
        const val RESUME_WINDOW_MILLIS: Long = 10 * 60 * 1000L

        /** 「正在确认授权结果」（D10-3680，BR-TEXT-14；契约补键前走 TradeDraftTexts 草稿，TODO(N-28)）。 */
        const val CONFIRMING_KEY: String = "auth.confirming"

        /** 「淘宝授权已完成，请再点一次购买」（D10-3680 / D10-3577）。 */
        const val COMPLETED_TAP_AGAIN_KEY: String = "auth.taobao.completed_tap_again"
    }
}
