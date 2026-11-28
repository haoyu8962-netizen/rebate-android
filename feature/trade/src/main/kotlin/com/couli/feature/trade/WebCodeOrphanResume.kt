// 错误码是契约取值（contracts/error-codes.yaml），按码直接写
@file:Suppress("MagicNumber")

package com.couli.feature.trade

import com.couli.core.network.ApiResult
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * 「绑定变化」信号（设计稿 §3.2 AuthManage、§6.4 第 5 步 active 行、§6.5）：冷启动 / 晚到回跳绑定成功后发出，授权管理页收到后再 load() 一次。
 * 进程里一份（App 的 AppTrade 持有）；只在主线程使用（订阅表可在通知途中增删）。
 */
class BindingChanges {
    /** 每次订阅一个独立的登记项（同一个 listener 订阅两次也各自退订）。 */
    private class Entry(
        val listener: () -> Unit,
    )

    private val entries = CopyOnWriteArrayList<Entry>()

    /** 绑定变了：同步通知全部订阅者。 */
    fun notifyChanged() {
        entries.forEach { it.listener() }
    }

    /** 登记订阅；返回取消订阅。 */
    fun subscribe(listener: () -> Unit): () -> Unit {
        val entry = Entry(listener)
        entries += entry
        return { entries.remove(entry) }
    }
}

/**
 * 冷启动恢复槽（设计稿 §6.4 第 6 步；D10-3681 C-4）：只在内存，只放 1 张 [Ticket]。
 * - [offer]：登记（新的覆盖旧的）；
 * - [claim]：卡片的 link_id 或 product_key 与槽位一致、且没到 deadline → 取走并返回（同一张只能被认领一次），否则 null；
 * - [takeExpired]：过了 deadline 还没人认领 → 取走并返回一次（调用方据此只提示 auth.taobao.completed_tap_again，不自动打开任何页面）；
 * - [peek]：没到期的那张；[clear]：退出登录、切换账号、切换环境时作废。
 * 「到期」= 此刻晚于 deadline（deadline 那一毫秒仍可认领）。线程安全。
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

    private val lock = Any()
    private var ticket: Ticket? = null

    fun offer(ticket: Ticket) {
        synchronized(lock) { this.ticket = ticket }
    }

    fun peek(): Ticket? = synchronized(lock) { ticket?.takeIf { !expired(it) } }

    fun claim(
        linkId: String?,
        productKey: String?,
    ): Ticket? =
        synchronized(lock) {
            val held = ticket?.takeIf { !expired(it) && matches(it, linkId, productKey) }
            if (held != null) ticket = null
            held
        }

    fun takeExpired(): Ticket? =
        synchronized(lock) {
            val held = ticket?.takeIf(::expired)
            if (held != null) ticket = null
            held
        }

    fun clear() {
        synchronized(lock) { ticket = null }
    }

    private fun expired(ticket: Ticket): Boolean = clock() > ticket.deadlineMillis

    private fun matches(
        ticket: Ticket,
        linkId: String?,
        productKey: String?,
    ): Boolean = (linkId != null && linkId == ticket.linkId) || (productKey != null && productKey == ticket.productKey)
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
 * 不经 Router 打开任何页面，不调 open。待办只认淘宝授权待办（kind=auth、platform=taobao）；晚到回跳一律不动待办。
 * 两条表外的口径：200 但不是 active → 按授权未完成，删冷启动的有效待办、提示 error.20004；表里没有的其它业务码 → 保留待办、提示该码文案。
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
    @Suppress("ReturnCount") // 闸门、没有记录、记录不符三个出口
    suspend fun handle(callback: WebCodeCallback) {
        // 第 1 步：启动闸门（D10-3681 B-4：版本仍未知也丢弃）
        if (gate.await(VERSION_WAIT_MILLIS) != WebCodeOrphanStartup.READY) return
        // 第 2 步：核对尝试记录（没有标签的记录与任何账号都不符）
        val attempt = attempts.current() ?: return
        val hash = webCodeStateSha256(callback.state)
        val tag = attempt.accountTag
        if (attempt.stateSha256 != hash || tag == null || tag != accountTag()) return
        attempts.clearIfCurrent(hash)
        // 冷启动：记录发起于本进程启动之前；否则是本进程那次尝试结束后才到的晚到回跳
        val cold = attempt.startedAtMillis < processStartedAtMillis
        when (callback) {
            is WebCodeCallback.Incomplete -> {
                // 第 3 步：晚到回跳什么都不做（那次尝试已按取消或失败处理过）
                if (cold && taobaoAuthPending() != null) {
                    pending.clear()
                    ui.toast(INCOMPLETE_KEY)
                }
            }

            is WebCodeCallback.Code -> {
                submit(callback, cold)
            }
        }
    }

    /** 第 4、5 步：确认浮层下提交 bindings，按结果表处理。 */
    private suspend fun submit(
        callback: WebCodeCallback.Code,
        cold: Boolean,
    ) {
        ui.confirming(true)
        val result =
            try {
                flow.submit(TAOBAO, callback.state, AuthCredential.WebCode(callback.code))
            } finally {
                ui.confirming(false)
            }
        // 晚到回跳一定落到「没有有效待办」（设计稿 §6.4 第 7 步）
        val record = if (cold) taobaoAuthPending() else null
        when (result) {
            is ApiResult.Success -> {
                bound(result, record)
            }

            is ApiResult.Failure -> {
                failed(result, record)
            }

            else -> {
                // 传输失败等没有业务结果：保留待办
                ui.toast(OpenPurchaseController.NETWORK_KEY)
            }
        }
    }

    private fun bound(
        result: ApiResult.Success,
        record: JsonObject?,
    ) {
        if ((result.data as? JsonObject)?.text("status") != ACTIVE) {
            // 200 但不是 active：按授权未完成
            if (record != null) pending.clear()
            ui.toast(INCOMPLETE_KEY)
            return
        }
        bindingChanges.notifyChanged()
        val linkId = record?.text("link_id")
        if (record == null || linkId == null) {
            // 没有有效待办：只提示，不重放 open（03 §4.5 ②）
            ui.toast(COMPLETED_TAP_AGAIN_KEY)
            return
        }
        pending.stepDone()
        val ticket = ColdAuthResume.Ticket(linkId, record.text("product_key"), TAOBAO, clock() + RESUME_WINDOW_MILLIS)
        resume.offer(ticket)
        watchExpiry(ticket)
    }

    /** 到期还没人认领：只提示再点一次（D10-3681 C-4：不自动打开商品页）。deadline 那一毫秒仍可认领，所以多等 1 毫秒。 */
    private fun watchExpiry(ticket: ColdAuthResume.Ticket) {
        scope.launch {
            delay((ticket.deadlineMillis - clock()).coerceAtLeast(0L) + 1L)
            if (resume.takeExpired() != null) ui.toast(COMPLETED_TAP_AGAIN_KEY)
        }
    }

    private fun failed(
        failure: ApiResult.Failure,
        record: JsonObject?,
    ) {
        val code = failure.code
        val key: String
        val dropPending: Boolean
        when {
            code == TaobaoAuthFlow.STATE_OR_CREDENTIAL || code == 20001 -> {
                // state 过期或已用：不自动拉起授权
                key = STATE_EXPIRED_KEY
                dropPending = true
            }

            code == 30151 || code == 30153 -> {
                key = "error.$code"
                dropPending = true
            }

            (code == 30101 || code == 30102) && failure.data.text("reason") == AUTH_UNAVAILABLE -> {
                key = "error.$code.$AUTH_UNAVAILABLE.auth_manage"
                dropPending = true
            }

            code == 42901 -> {
                key = OpenPurchaseController.RATE_LIMITED_KEY
                dropPending = false
            }

            else -> {
                // 5xxxx 与表外的码：保留待办（用户再点购买时 open 按服务端实际的绑定状态处理）
                key = OpenErrorAction.map(code, failure.data).textKey ?: OpenPurchaseController.GENERIC_KEY
                dropPending = false
            }
        }
        if (dropPending && record != null) pending.clear()
        ui.toast(key)
    }

    /** 此刻有效的淘宝授权待办（kind=auth、platform=taobao，未过期）；没有、或待办被暂缓（不读不清，FU-AND-0716 ①）为 null。 */
    private fun taobaoAuthPending(): JsonObject? =
        pending
            .takeIf { !it.held }
            ?.peek()
            ?.takeIf { it.text("kind") == OpenPurchaseController.PENDING_AUTH && it.text("platform") == TAOBAO }

    companion object {
        /** 版本检查最多等 5 秒（03 §4.4；D10-3681 B-4）。 */
        const val VERSION_WAIT_MILLIS: Long = 5_000L

        /** 恢复槽的有效期：待办续时之后再加 10 分钟。 */
        const val RESUME_WINDOW_MILLIS: Long = 10 * 60 * 1000L

        /** 「正在确认授权结果」（D10-3680，BR-TEXT-14；契约补键前走 TradeDraftTexts 草稿，TODO(N-28)）。 */
        const val CONFIRMING_KEY: String = "auth.confirming"

        /** 「淘宝授权已完成，请再点一次购买」（D10-3680 / D10-3577）。 */
        const val COMPLETED_TAP_AGAIN_KEY: String = "auth.taobao.completed_tap_again"

        /** 「授权未完成，请重新授权」。 */
        private const val INCOMPLETE_KEY = "error.20004"
        private const val STATE_EXPIRED_KEY = "error.30104"
        private const val AUTH_UNAVAILABLE = "auth_unavailable"
        private const val ACTIVE = "active"
        private const val TAOBAO = "taobao"
    }
}
