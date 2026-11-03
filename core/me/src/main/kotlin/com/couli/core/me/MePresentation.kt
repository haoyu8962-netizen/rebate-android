// 错误码、时长是契约与规则取值，按值直接写
@file:Suppress("MagicNumber", "TooManyFunctions", "ReturnCount", "LongParameterList", "ComplexCondition") // 三端同名的判定函数逐项提前返回；向量接线的签名固定

package com.couli.core.me

import com.couli.core.designsystem.CouliFormat
import com.couli.core.me.MeJson.bool
import com.couli.core.me.MeJson.fen
import com.couli.core.me.MeJson.string
import com.couli.generated.DefaultTexts
import java.text.BreakIterator
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

// APP-12「我的」、设置、隐私中心、关于、封禁说明与申诉、客服入口的纯规则（三端同名；向量 specs/me-vectors.json）。只做判定与数据整理，不发请求、
// 不碰系统接口；页面与执行器（feature:me、App）按这里的结论渲染与分流。规则空白一律按 NATIVE/APP-12/rule-gaps.md「现在的做法」，标 TODO(N-33)。

private const val HOUR_MS = 60L * 60 * 1000
private const val DAY_MS = 24 * HOUR_MS

internal fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*pairs))

internal fun String?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

internal fun Boolean.json(): JsonElement = JsonPrimitive(this)

internal fun List<String>.json(): JsonArray = JsonArray(map(::JsonPrimitive))

/** 身份档（「我的」、设置、隐私中心按它裁剪条目）。 */
object MeLevel {
    const val BASIC = "basic"
    const val GUEST = "guest"
    const val MEMBER = "member"
}

/** 端。 */
object MePlatform {
    const val IOS = "ios"
    const val ANDROID = "android"
    const val HARMONY = "harmony"
}

/** 客户端时刻（client_at）：上海时区的 ISO 8601（「2026-10-10T12:00:00+08:00」）。 */
fun shanghaiIso(epochMillis: Long): String =
    DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
        .format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.ofHours(8)))

// ---------------------------------------------------------------- 「我的」页

/** 宫格的一格（向量 grid 的项）：group / item / route（客服格 route=null、cs=true）。 */
data class GridCell(
    val group: String,
    val item: String,
    val route: String?,
) {
    val cs: Boolean get() = route == null

    fun json(): JsonObject =
        if (route == null) {
            obj("group" to JsonPrimitive(group), "item" to JsonPrimitive(item), "route" to JsonNull, "cs" to JsonPrimitive(true))
        } else {
            obj("group" to JsonPrimitive(group), "item" to JsonPrimitive(item), "route" to JsonPrimitive(route))
        }
}

/** 「我的」页的显示结论（页面用）。 */
data class MeViewModel(
    val loginPrompt: Boolean,
    val inviteCode: String?,
    val inviterTextKey: String?,
    val withdrawableFen: Long?,
    val estimatedFen: Long?,
    val masked: Boolean,
    val bell: Boolean,
    val grid: List<GridCell>,
)

/**
 * 「我的」页显示规则（brief §2.2，G04、G05、G06、G31、G36）。输出只有 loginPrompt、inviteCodeBlock、inviterTextKey、balance、bell、grid 六项：
 * 不显示等级与邀请人任何信息（OPS-20）。
 */
object MeView {
    const val GROUP_ORDERS = "orders_wallet"
    const val GROUP_PROMO = "promo"
    const val GROUP_MORE = "more"
    const val INVITER_BOUND_KEY = "me.inviter_bound"
    const val INVITER_UNBOUND_KEY = "me.inviter_unbound"
    const val MASK = "****"
    const val EARNINGS = "Earnings"
    const val MESSAGES = "Messages"
    const val WALLET = "Wallet"

    /** 宫格顺序照 Me 稿（收藏夹、分享赚不在路由表，不显示，G31）。item 是稿上的名字，界面经 [MeDraftTexts.gridKey] 取键。 */
    val MEMBER_GRID: List<GridCell> =
        listOf(
            GridCell(GROUP_ORDERS, "我的订单", "OrderList"),
            GridCell(GROUP_ORDERS, "钱包", "Wallet"),
            GridCell(GROUP_ORDERS, "余额流水", "Ledger"),
            GridCell(GROUP_ORDERS, "找回订单", "FindOrder"),
            GridCell(GROUP_PROMO, "邀请好友", "InviteShare"),
            GridCell(GROUP_PROMO, "收益看板", EARNINGS),
            GridCell(GROUP_MORE, "帮助中心", "Help"),
            GridCell(GROUP_MORE, "联系客服", null),
            GridCell(GROUP_MORE, "授权管理", "AuthManage"),
            GridCell(GROUP_MORE, "设置", "Settings"),
        )
    val GUEST_GRID: List<GridCell> = listOf(GridCell(GROUP_MORE, "帮助中心", "Help"), GridCell(GROUP_MORE, "设置", "Settings"))

    private fun grid(
        specs: List<GridCell>,
        routes: Collection<String>,
        earnings: Boolean?,
    ): List<GridCell> = specs.filter { (it.route != EARNINGS || earnings == true) && (it.route == null || it.route in routes) }

    /**
     * [levelRaw]：basic / guest / member（basic 在本任务没有自己的「我的」页，按游客口径给）；[me]：已校验的 Me 快照（可空：加载中、失败）；
     * [availableRoutes]：本端路由表里有、since 满足的路由；[earningsFlag]：features.flags 的收益看板开关。
     */
    fun model(
        levelRaw: String,
        me: JsonObject?,
        masked: Boolean,
        availableRoutes: Collection<String>,
        earningsFlag: Boolean?,
    ): MeViewModel {
        if (levelRaw != MeLevel.MEMBER) {
            return MeViewModel(true, null, null, null, null, masked, bell = false, grid = grid(GUEST_GRID, availableRoutes, null))
        }
        val cells = grid(MEMBER_GRID, availableRoutes, earningsFlag)
        val bell = MESSAGES in availableRoutes
        if (me == null) return MeViewModel(false, null, null, null, null, masked, bell, cells)
        val balance = me["balance"] as? JsonObject
        return MeViewModel(
            loginPrompt = false,
            inviteCode = me.string("invite_code"),
            inviterTextKey = if (me.bool("inviter_bound") == true) INVITER_BOUND_KEY else INVITER_UNBOUND_KEY,
            withdrawableFen = balance?.fen("withdrawable_fen") ?: 0L,
            estimatedFen = balance?.fen("estimated_total_fen") ?: 0L,
            masked = masked,
            bell = bell,
            grid = cells,
        )
    }

    /** 向量接线。 */
    fun of(
        levelRaw: String,
        me: JsonObject?,
        masked: Boolean,
        availableRoutes: List<String>,
        earningsFlag: Boolean?,
    ): JsonObject {
        val m = model(levelRaw, me, masked, availableRoutes, earningsFlag)

        fun amount(fen: Long): JsonObject =
            if (masked) obj("fen" to JsonPrimitive(fen), "maskedText" to JsonPrimitive(MASK)) else obj("fen" to JsonPrimitive(fen))
        val balance =
            if (m.withdrawableFen == null || m.estimatedFen == null) {
                JsonNull
            } else {
                obj(
                    "withdrawable" to amount(m.withdrawableFen),
                    "estimated" to amount(m.estimatedFen),
                    "withdrawRoute" to JsonPrimitive(WALLET),
                )
            }
        return obj(
            "loginPrompt" to JsonPrimitive(m.loginPrompt),
            "inviteCodeBlock" to (m.inviteCode?.let { obj("code" to JsonPrimitive(it)) } ?: JsonNull),
            "inviterTextKey" to m.inviterTextKey.json(),
            "balance" to balance,
            "bell" to if (m.bell) obj("route" to JsonPrimitive(MESSAGES), "unreadDot" to JsonPrimitive(false)) else JsonNull,
            "grid" to JsonArray(m.grid.map(GridCell::json)),
        )
    }
}

/**
 * 「我的」页通知引导条（G28，BR-ID-13 细则）：已登录 && 系统通知未开启（拒绝过）&& 距上次被拒 ≥ 48 小时 && 距上次关闭引导条 ≥ 7 天。
 * 从未被拒（not_determined / not_asked）、系统已允许、未登录都不出现。
 */
object NotifyGuideBar {
    const val DENY_WAIT_MS = 48 * HOUR_MS
    const val CLOSE_WAIT_MS = 7 * DAY_MS

    @Suppress("ReturnCount")
    fun visible(
        loggedIn: Boolean,
        systemRaw: String,
        lastDeniedMs: Long?,
        lastClosedMs: Long?,
        nowMs: Long,
    ): Boolean {
        if (!loggedIn || (systemRaw != "denied" && systemRaw != "permanently_denied") || lastDeniedMs == null) return false
        if (nowMs - lastDeniedMs < DENY_WAIT_MS) return false
        return lastClosedMs == null || nowMs - lastClosedMs >= CLOSE_WAIT_MS
    }
}

// ---------------------------------------------------------------- 设置页

/** 设置页的一行：[id] 稿上的名字（界面经 [MeDraftTexts.settingsKey] 取键），[route] 经 Router 打开的路由（页内子页、半屏、开关为 null）。 */
data class SettingsRow(
    val id: String,
    val route: String?,
    val clickable: Boolean = true,
    val choices: List<Pair<String, JsonObject>>? = null,
    val serviceSwitchAvailable: Boolean? = null,
)

data class SettingsGroup(
    val name: String,
    val rows: List<SettingsRow>,
)

data class SettingsModel(
    val groups: List<SettingsGroup>,
    val footer: String,
)

/**
 * 设置页条目（brief §2.3，G01、G02、G07、G30、G32）。groups 只描述顶层行（group 空串 = 未分组）；footer：basic → enable_full，guest →
 * login_prompt，member → logout。修改昵称、通知设置、协议选择半屏是页内子页（route 为 null）。
 */
object SettingsItems {
    const val ID_PRIVACY = "隐私中心"
    const val ID_AGREEMENT = "用户协议与隐私政策"
    const val ID_ABOUT = "关于凑狸"
    const val ID_HELP = "帮助中心"
    const val ID_CLEAR_CACHE = "清除缓存"
    const val ID_CLIPBOARD = "打开 App 自动识别剪贴板"
    const val ID_NICKNAME = "昵称"
    const val ID_PHONE = "手机号"
    const val ID_AUTH = "授权管理"
    const val ID_DELETE = "注销账号"
    const val ID_NOTIFY = "通知设置"
    const val ID_RESET_TIP = "重新显示下单须知"
    const val GROUP_ACCOUNT = "账号与安全"
    const val GROUP_PRIVACY = "隐私"
    const val GROUP_GENERAL = "通用"
    const val FOOTER_ENABLE_FULL = "enable_full"
    const val FOOTER_LOGIN = "login_prompt"
    const val FOOTER_LOGOUT = "logout"
    const val AGREEMENT = "Agreement"

    private val agreement =
        SettingsRow(
            ID_AGREEMENT,
            null,
            choices = listOf("agreement", "privacy").map { AGREEMENT to obj("type" to JsonPrimitive(it)) },
        )

    @Suppress("UnusedParameter") // 剪贴板行三端都显示（me-vectors settingsItems）；platformRaw 保留签名供向量接线与调用方
    fun model(
        levelRaw: String,
        platformRaw: String,
        phoneBound: Boolean?,
        nicknameEditable: Boolean,
        serviceSwitchAvailable: Boolean,
        deleteAccountAvailable: Boolean,
    ): SettingsModel {
        if (levelRaw == MeLevel.BASIC) {
            // 基本模式只有五项（四行 +【开启完整功能】），不显示剪贴板开关（BR-ID-02、BR-ID-16）
            val rows =
                listOf(SettingsRow(ID_PRIVACY, "PrivacyCenter"), agreement, SettingsRow(ID_ABOUT, "About"), SettingsRow(ID_HELP, "Help"))
            return SettingsModel(listOf(SettingsGroup("", rows)), FOOTER_ENABLE_FULL)
        }
        if (levelRaw != MeLevel.MEMBER) {
            val rows = mutableListOf(SettingsRow(ID_PRIVACY, "PrivacyCenter"), agreement, SettingsRow(ID_ABOUT, "About"))
            rows += SettingsRow(ID_CLIPBOARD, null)
            rows += SettingsRow(ID_CLEAR_CACHE, null)
            rows += SettingsRow(ID_HELP, "Help")
            return SettingsModel(listOf(SettingsGroup("", rows)), FOOTER_LOGIN)
        }
        // G07：只按 phone_bound；未绑定可点 → BindPhone，已绑定不可点（换手机号不在本任务）
        val account =
            mutableListOf(
                SettingsRow(ID_NICKNAME, null, clickable = nicknameEditable),
                SettingsRow(ID_PHONE, if (phoneBound == false) "BindPhone" else null, clickable = phoneBound == false),
                SettingsRow(ID_AUTH, "AuthManage"),
            )
        if (deleteAccountAvailable) account += SettingsRow(ID_DELETE, "DeleteAccount")
        val general =
            mutableListOf(SettingsRow(ID_NOTIFY, null, serviceSwitchAvailable = serviceSwitchAvailable), SettingsRow(ID_RESET_TIP, null))
        general += SettingsRow(ID_CLIPBOARD, null)
        general += SettingsRow(ID_CLEAR_CACHE, null)
        general += SettingsRow(ID_HELP, "Help")
        general += SettingsRow(ID_ABOUT, "About")
        return SettingsModel(
            listOf(
                SettingsGroup(GROUP_ACCOUNT, account),
                SettingsGroup(GROUP_PRIVACY, listOf(SettingsRow(ID_PRIVACY, "PrivacyCenter"), agreement)),
                SettingsGroup(GROUP_GENERAL, general),
            ),
            FOOTER_LOGOUT,
        )
    }

    /** 向量接线。 */
    fun of(
        levelRaw: String,
        platformRaw: String,
        phoneBound: Boolean?,
        nicknameEditable: Boolean,
        serviceSwitchAvailable: Boolean,
        deleteAccountAvailable: Boolean,
    ): JsonObject {
        val model = model(levelRaw, platformRaw, phoneBound, nicknameEditable, serviceSwitchAvailable, deleteAccountAvailable)

        fun row(r: SettingsRow): JsonObject {
            val map = linkedMapOf<String, JsonElement>("id" to JsonPrimitive(r.id), "route" to r.route.json())
            if (r.id == ID_NICKNAME || r.id == ID_PHONE) map["clickable"] = JsonPrimitive(r.clickable)
            r.choices?.let { choices ->
                map["params"] =
                    obj("choices" to JsonArray(choices.map { (route, p) -> obj("route" to JsonPrimitive(route), "params" to p) }))
            }
            r.serviceSwitchAvailable?.let { map["params"] = obj("serviceSwitchAvailable" to JsonPrimitive(it)) }
            return JsonObject(map)
        }
        return obj(
            "groups" to JsonArray(model.groups.map { obj("group" to JsonPrimitive(it.name), "items" to JsonArray(it.rows.map(::row))) }),
            "footer" to JsonPrimitive(model.footer),
        )
    }
}

// ---------------------------------------------------------------- 隐私中心

/** 系统权限一节的一行。 */
data class PermissionRow(
    val type: String,
    val purposeKey: String?,
    val statusKey: String,
)

data class PrivacyCenterModel(
    val lists: List<String>,
    val personalizationSwitch: Boolean,
    val withdrawButton: Boolean,
    val enableFullButton: Boolean,
    val permissionSection: Boolean,
    val permissionRows: List<PermissionRow>,
    val forceUpdate: Boolean,
)

/**
 * 隐私中心分节（brief §2.4，G26）：两份清单；basic 只读（无个性化开关、无【撤回同意】，原位显示【开启完整功能】）；「系统权限」一节的条目只来自锁定
 * 契约的 specs/system-permissions.json 里本端的条目（Android 目前为空）。[forceUpdate]（03 §4.1「强更页的次要入口」，鸿蒙 R2-05）：从强更页
 * 进入时只显示两份清单、协议与【撤回同意】（已同意才有），不显示个性化开关、系统权限一节与【开启完整功能】。
 */
object PrivacyCenterView {
    const val LIST_COLLECT = "个人信息收集清单"
    const val LIST_SHARE = "第三方共享清单"

    /** PermissionRequester 的状态 → 系统权限一节的状态（granted / denied / not_asked）。 */
    fun statusOf(raw: String): String =
        when (raw) {
            "granted" -> "granted"
            "denied", "permanently_denied" -> "denied"
            else -> "not_asked"
        }

    fun model(
        levelRaw: String,
        platformRaw: String,
        permissionEntries: List<JsonObject>,
        permissionStatus: Map<String, String>,
        forceUpdate: Boolean = false,
    ): PrivacyCenterModel {
        val basic = levelRaw == MeLevel.BASIC
        val rows =
            if (forceUpdate) {
                emptyList()
            } else {
                permissionEntries.mapNotNull { entry ->
                    val type = entry.string("type") ?: return@mapNotNull null
                    if (entry.string("platform") != platformRaw) return@mapNotNull null
                    PermissionRow(
                        type,
                        entry.string("purpose_key"),
                        "privacy.system_permissions.status." + statusOf(permissionStatus[type] ?: "not_asked"),
                    )
                }
            }
        return PrivacyCenterModel(
            lists = listOf(LIST_COLLECT, LIST_SHARE),
            personalizationSwitch = !basic && !forceUpdate,
            withdrawButton = !basic,
            enableFullButton = basic && !forceUpdate,
            permissionSection = !forceUpdate,
            permissionRows = rows,
            forceUpdate = forceUpdate,
        )
    }

    /** 向量接线：[permissionEntries] 是 system-permissions.json 的 permissions。 */
    fun of(
        levelRaw: String,
        platformRaw: String,
        permissionEntries: List<JsonObject>,
        permissionStatus: JsonObject,
    ): JsonObject {
        val status =
            permissionStatus
                .mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content }
                }.toMap()
        val m = model(levelRaw, platformRaw, permissionEntries, status)
        return obj(
            "lists" to m.lists.json(),
            "personalizationSwitch" to JsonPrimitive(m.personalizationSwitch),
            "withdrawButton" to JsonPrimitive(m.withdrawButton),
            "enableFullButton" to JsonPrimitive(m.enableFullButton),
            "permissionSection" to JsonPrimitive(m.permissionSection),
            "permissionRows" to
                JsonArray(
                    m.permissionRows.map {
                        obj(
                            "type" to JsonPrimitive(it.type),
                            "purposeKey" to it.purposeKey.json(),
                            "statusKey" to JsonPrimitive(it.statusKey),
                        )
                    },
                ),
        )
    }
}

/**
 * 个性化推荐开关（G03）：本机存、按设备；切换后记 recordConsent（channel=privacy_center）；关闭期间请求带 X-Personalization: off。
 * 基本模式没有开关、不记、不带头。
 */
object Personalization {
    const val HEADER_NAME = "X-Personalization"
    const val HEADER_OFF = "off"

    fun consentBody(
        on: Boolean,
        legalPrivacyVersion: Int,
        nowMs: Long,
        levelRaw: String,
    ): JsonObject? {
        if (levelRaw == MeLevel.BASIC) return null
        return obj(
            "type" to JsonPrimitive("personalization"),
            "version" to JsonPrimitive(legalPrivacyVersion),
            "accepted" to JsonPrimitive(on),
            "channel" to JsonPrimitive("privacy_center"),
            "client_at" to JsonPrimitive(shanghaiIso(nowMs)),
        )
    }

    fun header(
        on: Boolean,
        levelRaw: String,
    ): String? = if (levelRaw != MeLevel.BASIC && !on) HEADER_OFF else null
}

/**
 * 撤回同意（G24、G25）：设备已注册才发设备级 recordConsent(privacy, accepted=false)；无论结果（成功、失败、超时、未发）都做本地撤回、停推送上报、
 * 进入基本模式。
 */
object PrivacyWithdrawFlow {
    fun request(
        deviceRegistered: Boolean,
        consentedVersion: Int,
        nowMs: Long,
    ): JsonObject? {
        if (!deviceRegistered) return null
        return obj(
            "type" to JsonPrimitive("privacy"),
            "version" to JsonPrimitive(consentedVersion),
            "accepted" to JsonPrimitive(false),
            "channel" to JsonPrimitive("privacy_center"),
            "client_at" to JsonPrimitive(shanghaiIso(nowMs)),
        )
    }

    @Suppress("UnusedParameter") // 结果一律不影响本地撤回（G24）；参数留给三端同一签名
    fun after(result: JsonObject): JsonObject =
        obj("localWithdraw" to JsonPrimitive(true), "enterBasicMode" to JsonPrimitive(true), "stopPushReport" to JsonPrimitive(true))
}

// ---------------------------------------------------------------- 昵称

/** 扩展字素簇计数（G27，BR-ID-39）：Android 用 BreakIterator.getCharacterInstance()；出错时按 UTF-16 码元（保守）。 */
object Graphemes {
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun count(text: String): Int =
        try {
            val it = BreakIterator.getCharacterInstance(Locale.ROOT)
            it.setText(text)
            var n = 0
            while (it.next() != BreakIterator.DONE) n++
            n
        } catch (error: Exception) {
            text.length
        }
}

data class NicknameCheck(
    val trimmed: String,
    val length: Int,
    val saveEnabled: Boolean,
)

/** 修改昵称的输入判定（G27）：去首尾空白后 1–16 个字素才可保存；与当前昵称相同不可保存。 */
object NicknameInput {
    const val MAX = 16

    fun model(
        current: String,
        input: String,
    ): NicknameCheck {
        val trimmed = input.trim()
        val length = if (trimmed.isEmpty()) 0 else Graphemes.count(trimmed)
        return NicknameCheck(trimmed, length, length in 1..MAX && trimmed != current.trim())
    }

    fun check(
        current: String,
        input: String,
    ): JsonObject =
        model(current, input).let {
            obj(
                "trimmed" to JsonPrimitive(it.trimmed),
                "length" to JsonPrimitive(it.length),
                "saveEnabled" to JsonPrimitive(it.saveEnabled),
            )
        }
}

/** 写操作的结果分流（昵称、申诉）。 */
data class WriteOutcome(
    val actionRaw: String,
    val textKey: String? = null,
    val keepInput: Boolean = false,
    val disableMs: Long? = null,
) {
    fun json(): JsonObject {
        val map = linkedMapOf<String, JsonElement>("actionRaw" to JsonPrimitive(actionRaw))
        textKey?.let { map["textKey"] = JsonPrimitive(it) }
        if (keepInput) map["keepInput"] = JsonPrimitive(true)
        disableMs?.let { map["disableMs"] = JsonPrimitive(it) }
        return JsonObject(map)
    }

    companion object {
        const val SAVED = "saved"
        const val PROGRESS = "progress"
        const val FIELD_ERROR = "field_error"
        const val SHOW_ERROR = "show_error"
        const val RETRY_AFTER = "retry_after"
        const val ERROR_TRACE6 = "error_trace6"
        const val FORCE_UPDATE = "force_update"
        const val RISK_NOTICE = "risk_notice"
    }
}

/** Retry-After 头（秒）→ 禁用毫秒；缺省或非法 5 秒。 */
fun retryAfterMs(raw: String?): Long {
    val sec = raw?.trim()?.takeIf { s -> s.isNotEmpty() && s.all(Char::isDigit) }?.toLongOrNull()
    return if (sec != null && sec > 0) sec * 1000 else 5_000L
}

/** 5xxxx 的文案键：字典里有 error.<code> 用它，否则回落 error.50001（APP-06a 第 12 条）。 */
fun serverErrorKey(code: Int): String = "error.$code".takeIf { DefaultTexts.text(it) != null } ?: "error.50001"

/** 表内业务码的文案键（字典没有为 null：调用方按服务端 msg →「操作未完成，请稍后再试」兜底，G13）。 */
fun codeTextKey(code: Int): String? = "error.$code".takeIf { DefaultTexts.text(it) != null }

/** 写操作结果的输入（向量形状）：kind ok / transport / error，code、data、headers、traceId。 */
@Suppress("ReturnCount")
private fun commonOutcome(result: JsonObject): WriteOutcome? {
    if (result.string("kind") == "transport") return WriteOutcome(WriteOutcome.SHOW_ERROR, MeDraftTexts.NETWORK_KEY, keepInput = true)
    val code = (result["code"] as? JsonPrimitive)?.intOrNull ?: -1
    return when {
        code == 42901 -> {
            val headers = result["headers"] as? JsonObject
            val raw = headers?.string("Retry-After") ?: headers?.string("retry-after")
            WriteOutcome(WriteOutcome.RETRY_AFTER, "error.42901", keepInput = true, disableMs = retryAfterMs(raw))
        }

        code in 50_000..59_999 -> {
            WriteOutcome(WriteOutcome.ERROR_TRACE6, serverErrorKey(code), keepInput = true)
        }

        // 只有网络层判定强更才进强更（F-X-09，FU-AND-0715）：输入带 forced=false（受限会话刷新、刷新失败的 10405）→ 统一错误文案 + 可重试；
        // 向量不带 forced，按原口径
        code == 10405 && (result["forced"] as? JsonPrimitive)?.content != "false" -> {
            WriteOutcome(WriteOutcome.FORCE_UPDATE)
        }

        code == 10405 -> {
            WriteOutcome(WriteOutcome.ERROR_TRACE6, "error.50001", keepInput = true)
        }

        code == 10006 -> {
            WriteOutcome(WriteOutcome.RISK_NOTICE)
        }

        else -> {
            null
        }
    }
}

private fun JsonObject.code(): Int = (this["code"] as? JsonPrimitive)?.intOrNull ?: -1

/** 保存昵称的结果分流（G01；不自动重试写请求）。 */
object NicknameOutcome {
    fun model(result: JsonObject): WriteOutcome {
        if (result.string("kind") == "ok") return WriteOutcome(WriteOutcome.SAVED)
        commonOutcome(result)?.let { return it }
        val code = result.code()
        if (code == 20001) {
            val sensitive = (result["data"] as? JsonObject)?.string("reason") == "nickname_sensitive"
            return WriteOutcome(
                WriteOutcome.FIELD_ERROR,
                if (sensitive) "error.20001.nickname_sensitive" else "error.20001",
                keepInput = true,
            )
        }
        return WriteOutcome(WriteOutcome.SHOW_ERROR, codeTextKey(code), keepInput = true)
    }

    fun of(result: JsonObject): JsonObject = model(result).json()
}

// ---------------------------------------------------------------- 封禁 / 冻结说明

/** 原因类别字典（G08：BR-TEXT-23 细则表的五类；表外与 null 不显示原因行，不显示原始编码）。 */
object RiskReasonText {
    val CATEGORIES: List<String> = listOf("malicious_rights", "fraud_invite", "abnormal_trade", "account_security", "other")

    fun key(category: String?): String? = category?.takeIf { it in CATEGORIES }?.let { "risk_reason.$it" }
}

/** RiskNotice 的版式。 */
object RiskLayout {
    const val BANNED = "banned"
    const val BANNED_APPEALING = "banned_appealing"
    const val BANNED_UPHELD = "banned_upheld"
    const val FROZEN = "frozen"
    const val FROZEN_APPEALING = "frozen_appealing"
    const val FROZEN_UPHELD = "frozen_upheld"
    const val NORMAL = "normal"

    fun isFrozen(layout: String): Boolean = layout == FROZEN || layout == FROZEN_APPEALING || layout == FROZEN_UPHELD

    fun isBanned(layout: String): Boolean = layout == BANNED || layout == BANNED_APPEALING || layout == BANNED_UPHELD
}

data class RiskNoticeModel(
    val layoutRaw: String,
    val reasonKey: String?,
    val frozenUntilText: String?,
    val showBack: Boolean,
    val primaryRaw: String,
    val footer: List<String>,
    val upheldLine: Boolean,
)

/**
 * 封禁 / 冻结说明（G08、G09、G10、G30）：状态只按服务端 risk.state；appealing 时按「本次运行是否因 10006 进入」选封禁或冻结版式（只决定版式）；
 * 最近一次账户申诉 upheld 且仍受限时附「最近一次申诉：维持原处理结果」。不显示处理时限；注销按 G30 不显示。
 */
object RiskNoticeView {
    const val SUBMIT = "submit_appeal"
    const val VIEW_PROGRESS = "view_progress"
    const val BACK_HOME = "back_home"
    const val CONTACT_CS = "contact_cs"
    const val LOGOUT = "logout"

    fun model(
        risk: JsonObject,
        enteredBy10006: Boolean,
        latestAccountAppeal: JsonObject?,
        nowMs: Long,
    ): RiskNoticeModel {
        val state = risk.string("state") ?: "normal"
        val upheld = latestAccountAppeal?.string("status") == "upheld"
        val layout =
            when (state) {
                "banned" -> if (upheld) RiskLayout.BANNED_UPHELD else RiskLayout.BANNED
                "frozen" -> if (upheld) RiskLayout.FROZEN_UPHELD else RiskLayout.FROZEN
                "appealing" -> if (enteredBy10006) RiskLayout.BANNED_APPEALING else RiskLayout.FROZEN_APPEALING
                else -> RiskLayout.NORMAL
            }
        if (layout == RiskLayout.NORMAL) return RiskNoticeModel(layout, null, null, true, BACK_HOME, emptyList(), false)
        val until =
            risk.string("frozen_until")?.takeIf { RiskLayout.isFrozen(layout) }?.let(MeJson::instant)?.let {
                CouliFormat.timeListFuture(epochMillis = it, nowEpochMillis = nowMs)
            }
        val banned = RiskLayout.isBanned(layout)
        return RiskNoticeModel(
            layoutRaw = layout,
            reasonKey = RiskReasonText.key(risk.string("reason_category")),
            frozenUntilText = until,
            showBack = !banned,
            primaryRaw = if (state == "appealing") VIEW_PROGRESS else SUBMIT,
            footer = if (banned) listOf(CONTACT_CS, LOGOUT) else emptyList(),
            upheldLine = upheld,
        )
    }

    fun of(
        risk: JsonObject,
        enteredBy10006: Boolean,
        latestAccountAppeal: JsonObject?,
        nowMs: Long,
    ): JsonObject {
        val m = model(risk, enteredBy10006, latestAccountAppeal, nowMs)
        val map =
            linkedMapOf(
                "layoutRaw" to JsonPrimitive(m.layoutRaw),
                "showsDeadline" to JsonPrimitive(false),
                "reasonKey" to m.reasonKey.json(),
                "frozenUntilText" to m.frozenUntilText.json(),
                "primaryRaw" to JsonPrimitive(m.primaryRaw),
            )
        if (m.layoutRaw != RiskLayout.NORMAL) {
            map["showBack"] = JsonPrimitive(m.showBack)
            map["footer"] = m.footer.json()
            map["upheldLine"] = JsonPrimitive(m.upheldLine)
        }
        return JsonObject(map)
    }
}

/** 进行中的提现（G16）：只取 PENDING_REVIEW、APPROVED、PAYING。 */
object PendingWithdrawals {
    val STATUSES: Set<String> = setOf("PENDING_REVIEW", "APPROVED", "PAYING")

    fun filter(items: List<JsonObject>): List<String> =
        items.mapNotNull { item ->
            val id = item.string("withdrawal_id")
            id?.takeIf { item.string("status") in STATUSES }
        }
}

// ---------------------------------------------------------------- 申诉

/** 申诉页版式（G15）：只看列表里 target_type=account 的最新一条（列表新的在前）。 */
object AppealView {
    const val SUBMIT = "submit"
    const val PROGRESS = "progress"
    const val RESULT_UPHELD = "result_upheld"
    const val RESULT_LIFTED = "result_lifted"

    fun latestAccount(appeals: List<JsonObject>): JsonObject? = appeals.firstOrNull { it.string("target_type") == "account" }

    fun layout(appeals: List<JsonObject>): String {
        val latest = latestAccount(appeals) ?: return SUBMIT
        return when (latest.string("status")) {
            "upheld" -> RESULT_UPHELD
            "revoked" -> RESULT_LIFTED
            else -> PROGRESS
        }
    }

    @Suppress("UnusedParameter") // 冻结 / 封禁版式只决定文案组，不影响版式选择（G10）；参数留给三端同一签名
    fun of(
        appeals: List<JsonObject>,
        frozenLayout: Boolean,
    ): JsonObject {
        val latest = latestAccount(appeals) ?: return obj("layoutRaw" to JsonPrimitive(SUBMIT))
        return obj("layoutRaw" to JsonPrimitive(layout(appeals)), "appealId" to latest.string("appeal_id").json())
    }
}

/**
 * 申诉页里凡涉及「当前是否受限 / 已恢复」的标题、说明、提示条与按钮（鸿蒙 R2-01，共性第 12 条）：一律按本次 getMe.risk.state；申诉记录（revoked /
 * upheld）只作历史结论文字，版式（G10 的冻结 / 封禁）只决定文案组，不推断当前限制。
 */
object AppealCopy {
    fun restored(state: String?): Boolean = state == "normal"

    fun restricted(state: String?): Boolean = state == "banned" || state == "frozen" || state == "appealing"

    fun liftedTitle(
        frozenLayout: Boolean,
        state: String?,
    ): String =
        when {
            !restored(state) -> "appeal.lifted.title.history"
            frozenLayout -> "appeal.lifted.title.frozen"
            else -> "appeal.lifted.title.banned"
        }

    fun liftedDesc(state: String?): String = if (restored(state)) "appeal.lifted.desc" else "appeal.lifted.desc.history"

    fun liftedNote(
        frozenLayout: Boolean,
        state: String?,
    ): String? =
        when {
            frozenLayout -> "appeal.lifted.note.frozen"
            restored(state) -> "appeal.lifted.note.banned"
            else -> null
        }

    fun liftedActions(
        frozenLayout: Boolean,
        state: String?,
    ): List<String> =
        when {
            frozenLayout -> listOf("go_wallet")
            restored(state) -> listOf("start", "view_messages")
            else -> listOf("contact_cs")
        }

    fun submitNote(
        frozenLayout: Boolean,
        state: String?,
    ): String =
        when {
            !restricted(state) -> "appeal.note.neutral"
            frozenLayout -> "appeal.note.frozen"
            else -> "appeal.note.banned"
        }

    fun processingDesc(
        frozenLayout: Boolean,
        state: String?,
    ): String? =
        when {
            !restricted(state) -> null
            frozenLayout -> "appeal.timeline.processing_desc.frozen"
            else -> "appeal.timeline.processing_desc.banned"
        }

    fun upheldFrozenNext(
        frozenLayout: Boolean,
        state: String?,
    ): Boolean = frozenLayout && (state == "frozen" || state == "appealing")

    /** 「当前状态」一行（只按本次 risk.state）。 */
    fun currentStateKey(state: String?): String? =
        when (state) {
            "banned" -> "risk.banned.title"
            "frozen" -> "risk.frozen.title"
            "appealing" -> "appeal.state_appealing"
            "normal" -> "appeal.state_normal"
            else -> null
        }
}

/** 申诉提交表单（G11、G14）：说明去首尾空白后非空才可提交；请求体 {target_type: account, content}，不带 target_id。 */
object AppealForm {
    fun body(content: String): JsonObject? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null
        // TODO(N-33) vectors gaps「未定_申诉正文空白」：最终 content 是否去首尾空白未定，按去掉后的正文提交（与鸿蒙一致）
        return obj("target_type" to JsonPrimitive("account"), "content" to JsonPrimitive(trimmed))
    }

    fun check(content: String): JsonObject {
        val body = body(content) ?: return obj("enabled" to JsonPrimitive(false))
        return obj("enabled" to JsonPrimitive(true), "body" to body)
    }
}

/** 申诉提交结果分流（G13、G14）：成功 → 进度态；表外码走统一错误消息函数。 */
object AppealOutcome {
    fun model(result: JsonObject): WriteOutcome {
        if (result.string("kind") == "ok") return WriteOutcome(WriteOutcome.PROGRESS)
        commonOutcome(result)?.let { return it }
        val code = result.code()
        if (code == 20001) return WriteOutcome(WriteOutcome.FIELD_ERROR, "error.20001", keepInput = true)
        return WriteOutcome(WriteOutcome.SHOW_ERROR, codeTextKey(code), keepInput = true)
    }

    fun of(result: JsonObject): JsonObject = model(result).json()
}

// ---------------------------------------------------------------- 客服入口

data class CsDecision(
    val sheetRaw: String,
    val openUrl: String?,
    val copyText: String?,
    val buttons: List<String>,
)

/** 统一客服出口的决策（G20）：kf 缺失或不是 https → 只有帮助中心；未装微信 → 复制链接 + 帮助；已装或未知 → 打开微信（拉不起 → 打不开）。 */
object CsEntryDecision {
    const val HELP_ONLY = "help_only"
    const val HANDOFF = "handoff"
    const val OPEN_FAILED = "open_failed"
    const val WECHAT_MISSING = "wechat_missing"
    const val GO_HELP = "go_help"
    const val COPY_LINK = "copy_link"
    const val RETRY = "retry"
    const val OPEN_WECHAT = "open_wechat"
    private val HTTPS = Regex("^https://[^\\s/?#]+[^\\s]*$")

    fun httpsUrl(kfUrl: String?): Boolean = kfUrl != null && HTTPS.matches(kfUrl)

    fun model(
        kfUrl: String?,
        wechatRaw: String,
        openResult: String?,
    ): CsDecision =
        when {
            kfUrl == null || !httpsUrl(kfUrl) -> CsDecision(HELP_ONLY, null, null, listOf(GO_HELP))
            wechatRaw == "no" -> CsDecision(WECHAT_MISSING, null, kfUrl, listOf(COPY_LINK, GO_HELP))
            openResult == "failed" -> CsDecision(OPEN_FAILED, kfUrl, kfUrl, listOf(RETRY, COPY_LINK, GO_HELP))
            else -> CsDecision(HANDOFF, kfUrl, null, listOf(OPEN_WECHAT, GO_HELP))
        }

    fun of(
        kfUrl: String?,
        wechatRaw: String,
        openResult: String?,
    ): JsonObject =
        model(kfUrl, wechatRaw, openResult).let {
            obj(
                "sheetRaw" to JsonPrimitive(it.sheetRaw),
                "openUrl" to it.openUrl.json(),
                "copyText" to it.copyText.json(),
                "buttons" to it.buttons.json(),
            )
        }
}

/** 客服链接（G20、AC-S1-134 ④）：原样 kf.url，不拼任何账号标识与发起页路由名。 */
object CsLink {
    @Suppress("UnusedParameter") // 发起页与账号刻意不进链接（G20）；参数留给三端同一签名与测试核对
    fun build(
        kfUrl: String,
        fromRoute: String,
        account: JsonObject,
    ): String = kfUrl
}

// ---------------------------------------------------------------- 关于

/**
 * 复制诊断信息（G23、AC-S1-136）：只含版本与构建号、渠道、系统版本、机型、device_id 后 6 位、推送是否已上报、最近 3 个 trace_id、配置版本、首页版本；
 * 固定英文字段名，每行一项，拿不到的整行省略；不含任何令牌、install_secret、完整 device_id、设备哈希、手机号、uid。
 */
object Diagnostics {
    private const val TAIL = 6
    private const val TRACES = 3

    private fun clean(value: JsonElement?): String? =
        (value as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.replace(Regex("[\\r\\n]+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    @Suppress("CyclomaticComplexMethod")
    fun lines(fields: JsonObject): List<String> {
        val out = mutableListOf<String>()
        val version = clean(fields["version"])
        val build = clean(fields["build"])
        when {
            version != null -> out += "app_version: $version" + (build?.let { " ($it)" } ?: "")
            build != null -> out += "app_build: $build"
        }
        clean(fields["channel"])?.let { out += "channel: $it" }
        clean(fields["system_version"])?.let { out += "os_version: $it" }
        clean(fields["model"])?.let { out += "device_model: $it" }
        clean(fields["device_id"])?.takeIf { it.length >= TAIL }?.let { out += "device_id_tail: " + it.takeLast(TAIL) }
        (fields["push_reported"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()?.let {
            out += "push_reported: " + if (it) "是" else "否"
        }
        (fields["trace_ids"] as? JsonArray)?.mapNotNull(::clean)?.take(TRACES)?.takeIf { it.isNotEmpty() }?.let {
            out += "recent_trace_ids: " + it.joinToString(", ")
        }
        clean(fields["config_version"])?.let { out += "config_version: $it" }
        clean(fields["home_version"])?.let { out += "home_version: $it" }
        return out
    }
}

/** 关于页（G22）：AI 公示只显示 agent.filing_text 原文（空串、未配置整节不显示）；备案号行不显示（TODO(N-33)）。 */
object AboutView {
    fun filing(filingText: String?): String? = filingText?.takeIf { it.isNotBlank() }

    fun of(filingText: String?): JsonObject = obj("filingSection" to filing(filingText).json(), "icpRow" to JsonPrimitive(false))
}

// ---------------------------------------------------------------- 10006 与本机数据

/** 10006 的统一落点（G09）：强更显示中不打开（强更优先）；RiskNotice 已在栈顶不重复打开；其余替换到 RiskNotice。 */
object Route10006 {
    const val REPLACE = "replace_risk_notice"
    const val NONE = "none"
    const val RISK_NOTICE = "RiskNotice"

    fun decide(
        topRoute: String,
        forceUpdateShowing: Boolean,
    ): String = if (forceUpdateShowing || topRoute == RISK_NOTICE) NONE else REPLACE
}

/**
 * 本任务新增的本机数据何时清（G29）：设备级（引导条关闭时刻、个性化）只在撤回同意时清；会话级（推送上报状态、Me 快照、昵称与申诉草稿）在退出、
 * 换账号、会话失效、撤回同意、切换环境时清；回后台都不清。
 */
object MeLocalData {
    val DEVICE_ITEMS: Set<String> = setOf("guide_bar_closed_at", "personalization")
    val SESSION_ITEMS: Set<String> = setOf("push_report_state", "me_snapshot", "nickname_draft", "appeal_draft")
    val SESSION_EVENTS: Set<String> = setOf("logout", "account_switch", "session_invalid", "consent_revoked", "env_switch")

    fun clearsOn(
        event: String,
        item: String,
    ): Boolean =
        when (item) {
            in DEVICE_ITEMS -> event == "consent_revoked"
            in SESSION_ITEMS -> event in SESSION_EVENTS
            else -> false
        }
}
