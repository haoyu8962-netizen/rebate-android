package com.couli.feature.login

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 邀请链接拉起 App 时的判定输入（R08，APP-17c）。[rawCode]：链接里取出的原始码（未规范化）；[consented]：此刻已同意隐私政策；
 * [guest]：此刻是游客；[confirmedMe]：已登录时一次已确认的 GET /v1/me 的 data（本人码与 invite_backfill.eligible 必须取自同一份，
 * 鸿蒙评审 S2-5）；游客或拿不到时为 null。
 */
data class InviteLinkInput(
    val rawCode: String,
    val consented: Boolean,
    val guest: Boolean,
    val confirmedMe: JsonObject?,
)

/** 分流结论：[kindRaw] 为 drop / login / invite_backfill / home；只有 login 与 invite_backfill 带规范化后的 [code]。 */
data class InviteLinkDecision(
    val kindRaw: String,
    val code: String?,
) {
    companion object {
        const val DROP = "drop"
        const val LOGIN = "login"
        const val INVITE_BACKFILL = "invite_backfill"
        const val HOME = "home"
    }
}

/**
 * 邀请链接的解析端口（G03）：链接路径与参数名由契约线定，现在没有实现，也不接系统深链、不改生成的路由表。实现返回链接里的原始码
 * （交给 [InviteLinkDispatcher.dispatch]，格式在那里校验），不是邀请链接返回 null。
 */
fun interface InviteLinkParser {
    fun codeOf(link: String): String?
}

/**
 * 邀请链接分流（R08 ①–⑤，APP-17c；对标 iOS fe53a11、鸿蒙 f7c4648）。顺序：统一入口守卫 → 同意 → 格式 → 本人码 → 游客 → eligible。
 * - drop：守卫拒绝、未同意、格式不合格（复用 [InviteField]）、或是本人码：什么都不做（不导航、不动待绑定码）；
 * - login（游客）：先写待绑定码（替换旧码），再打开 Login（无参数）。打开失败（例如守卫后来拒绝）码仍保留：按 R08 ③「记为待绑定码」可以接受；
 * - invite_backfill（已登录且 eligible）：只打开 InviteBackfill 并带 prefill，不动待绑定码、不发绑定请求（确认由用户点）；
 * - home（已登录不可补填，或拿不到已确认的 getMe、读取失败）：只打开 Home（无参数），不提示、不动待绑定码。
 * 不碰网络之外的任何状态；端口全部注入（[getMe] 是唯一的读取，已登录时只调一次）。只做决策与导航，不自动提交。
 */
class InviteLinkDispatcher(
    private val pending: PendingInviteCode,
    /** 统一入口守卫（启动意图排队、隐私弹窗显示中等）：拒绝时不碰其余任何端口。 */
    private val entryAllowed: () -> Boolean,
    private val consented: () -> Boolean,
    private val guest: () -> Boolean,
    /** 已登录时读一次已确认的 GET /v1/me data；拿不到返回 null（抛错同样按拿不到）。 */
    private val getMe: suspend () -> JsonObject?,
    /** 经 Router 打开页面（路由名、参数）；返回是否打开。 */
    private val open: (route: String, params: Map<String, String>) -> Boolean,
) {
    @Suppress("ReturnCount") // 守卫、同意、格式各自提前结束
    suspend fun dispatch(rawCode: String): InviteLinkDecision {
        if (!entryAllowed()) return DROP_DECISION
        if (!consented()) return DROP_DECISION
        if (!InviteField.isValidCode(InviteField.normalize(rawCode))) return DROP_DECISION
        val isGuest = guest()
        val me = if (isGuest) null else confirmedMe()
        val decision = decide(InviteLinkInput(rawCode, consented = true, guest = isGuest, confirmedMe = me))
        val code = decision.code
        when (decision.kindRaw) {
            InviteLinkDecision.LOGIN -> {
                if (code != null) pending.set(code)
                open(ROUTE_LOGIN, emptyMap())
            }

            InviteLinkDecision.INVITE_BACKFILL -> {
                if (code != null) open(ROUTE_INVITE_BACKFILL, mapOf(PARAM_PREFILL to code))
            }

            InviteLinkDecision.HOME -> {
                open(ROUTE_HOME, emptyMap())
            }

            else -> {
                // drop：本人码等，不导航、不动待绑定码
            }
        }
        return decision
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 读取失败一律按「拿不到」去 Home；取消照常向上传
    private suspend fun confirmedMe(): JsonObject? =
        try {
            getMe()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            null
        }

    companion object {
        /** 路由名（Login、Home 已在路由表；InviteBackfill 等契约线补，G03 先按名字本地使用）。 */
        const val ROUTE_LOGIN = "Login"
        const val ROUTE_HOME = "Home"
        const val ROUTE_INVITE_BACKFILL = "InviteBackfill"
        const val PARAM_PREFILL = "prefill"

        private val DROP_DECISION = InviteLinkDecision(InviteLinkDecision.DROP, null)

        /** 纯判定（三端同口径）：同意 → 格式 → 本人码 → 游客 → eligible。已登录而 [InviteLinkInput.confirmedMe] 为 null 时去 Home。 */
        @Suppress("ReturnCount")
        fun decide(input: InviteLinkInput): InviteLinkDecision {
            if (!input.consented) return DROP_DECISION
            val code = InviteField.normalize(input.rawCode)
            if (!InviteField.isValidCode(code)) return DROP_DECISION
            val me = input.confirmedMe
            // 本人码未知（快照里没有或为 null）时不按本人码丢弃；服务端最终拒绝自绑
            val own = (me?.get("invite_code") as? JsonPrimitive)?.contentOrNull?.let(InviteField::normalize)
            if (own == code) return DROP_DECISION
            if (input.guest) return InviteLinkDecision(InviteLinkDecision.LOGIN, code)
            if (me == null) return InviteLinkDecision(InviteLinkDecision.HOME, null)
            val eligible = ((me["invite_backfill"] as? JsonObject)?.get("eligible") as? JsonPrimitive)?.booleanOrNull == true
            return if (eligible) {
                InviteLinkDecision(InviteLinkDecision.INVITE_BACKFILL, code)
            } else {
                InviteLinkDecision(InviteLinkDecision.HOME, null)
            }
        }
    }
}
