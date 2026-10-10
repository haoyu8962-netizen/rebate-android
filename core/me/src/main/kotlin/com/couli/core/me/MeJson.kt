package com.couli.core.me

import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 「我的」、封禁说明与申诉的手写解析（brief §2.1；照 WalletJson / OrderJson 的先例，模型未生成）。
 * 必填字段缺失或类型不符：整条按坏数据（null）——页面显示加载失败态与【重试】；不补默认值，不把缺失当 0、当「未绑定」、当 normal。
 * 不认识的 risk.state 也按坏数据（不猜是正常还是受限）。不认识的 appeal.status 按「处理中」显示（[AppealView]）。列表里坏的一条丢弃（只记个数）。
 */
@Suppress("TooManyFunctions", "ReturnCount") // 逐字段校验的小函数；逐项提前返回
object MeJson {
    /** contracts enums identity.risk_state。 */
    val RISK_STATES: Set<String> = setOf("normal", "frozen", "appealing", "banned")

    /** Me 的 13 个必填字段（openapi Me.required）。 */
    val FIELDS: List<String> =
        listOf(
            "user_id",
            "nickname",
            "avatar_url",
            "invite_code",
            "inviter_bound",
            "invite_backfill",
            "identity_level",
            "phone_bound",
            "balance",
            "union_bindings",
            "realname_status",
            "need_reconsent",
            "risk",
        )

    private val APPEAL_FIELDS = listOf("appeal_id", "target_type", "target_id", "status", "content", "created_at", "closed_at")

    /** GET /v1/me 的 data → 只含 13 个契约字段的副本；坏数据为 null。 */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "ComplexCondition") // 逐字段校验
    fun parse(value: JsonElement?): JsonObject? {
        val r = value as? JsonObject ?: return null
        if (!FIELDS.all { it in r }) return null
        val userId = r.string("user_id") ?: return null
        if (userId.isEmpty()) return null
        if (r.string("nickname") == null || r.string("avatar_url") == null || !r.nullableString("invite_code") ||
            r.bool("inviter_bound") == null || r.string("identity_level") == null || r.bool("phone_bound") == null ||
            r.string("realname_status") == null || r.bool("need_reconsent") == null || r["union_bindings"] !is JsonArray
        ) {
            return null
        }
        val backfill = r["invite_backfill"] as? JsonObject ?: return null
        if (backfill.bool("eligible") == null || !backfill.nullableInstant("deadline_at")) return null
        val balance = r["balance"] as? JsonObject ?: return null
        if (listOf("available_fen", "withdrawable_fen", "estimated_total_fen").any { balance.fen(it) == null }) return null
        risk(r["risk"]) ?: return null
        return JsonObject(FIELDS.associateWith { r.getValue(it) })
    }

    /** risk：state 必须是契约四值之一，reason_category 字符串或 null，frozen_until 可解析的时刻或 null。 */
    @Suppress("ReturnCount")
    fun risk(value: JsonElement?): JsonObject? {
        val risk = value as? JsonObject ?: return null
        val state = risk.string("state") ?: return null
        if (state !in RISK_STATES) return null
        if ("reason_category" !in risk || "frozen_until" !in risk) return null
        if (!risk.nullableString("reason_category") || !risk.nullableInstant("frozen_until")) return null
        return risk
    }

    /** 已校验的 Me 的强类型视图（页面用）；[parse] 通过后一定成功。 */
    fun me(value: JsonElement?): Me? {
        val r = parse(value) ?: return null
        val balance = r.getValue("balance") as JsonObject
        val risk = r.getValue("risk") as JsonObject
        return Me(
            raw = r,
            userId = r.string("user_id").orEmpty(),
            nickname = r.string("nickname").orEmpty(),
            inviteCode = r.string("invite_code"),
            inviterBound = r.bool("inviter_bound") == true,
            phoneBound = r.bool("phone_bound") == true,
            withdrawableFen = balance.fen("withdrawable_fen") ?: 0L,
            estimatedTotalFen = balance.fen("estimated_total_fen") ?: 0L,
            riskState = risk.string("state").orEmpty(),
            reasonCategory = risk.string("reason_category"),
            frozenUntil = risk.string("frozen_until"),
        )
    }

    /** 一条申诉（openapi Appeal 七个必填字段）；坏数据为 null。status 只要求是字符串（不认识的按「处理中」显示）。 */
    @Suppress("ReturnCount", "ComplexCondition")
    fun appeal(value: JsonElement?): JsonObject? {
        val r = value as? JsonObject ?: return null
        if (!APPEAL_FIELDS.all { it in r }) return null
        val id = r.string("appeal_id") ?: return null
        if (id.isEmpty() || r.string("target_type") == null || !r.nullableString("target_id") || r.string("status") == null ||
            r.string("content") == null || r.string("created_at")?.let(::instant) == null || !r.nullableInstant("closed_at")
        ) {
            return null
        }
        return r
    }

    /** GET /v1/me/appeals 的 data {items, next_cursor}：外壳坏了为 null（整页加载失败）；坏的一条丢弃（[AppealList.dropped] 只记个数）。 */
    @Suppress("ReturnCount")
    fun appealList(value: JsonElement?): AppealList? {
        val r = value as? JsonObject ?: return null
        val items = r["items"] as? JsonArray ?: return null
        if ("next_cursor" !in r || !r.nullableString("next_cursor")) return null
        val parsed = items.map(::appeal)
        return AppealList(parsed.filterNotNull(), parsed.count { it == null })
    }

    /** ISO 时刻 → 毫秒；不可解析为 null。 */
    fun instant(text: String): Long? = runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()

    internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.nullableString(key: String): Boolean {
        val v = this[key] ?: return false
        return v == JsonNull || (v is JsonPrimitive && v.isString)
    }

    internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    internal fun JsonObject.fen(key: String): Long? {
        val p = (this[key] as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
        if (p.content.contains('.') || p.content.contains('e') || p.content.contains('E')) return null
        return p.longOrNull
    }

    private fun JsonObject.nullableInstant(key: String): Boolean {
        val v = this[key] ?: return false
        if (v == JsonNull) return true
        val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        return instant(text) != null
    }
}

/** 申诉列表（新的在前）；[dropped] 是丢弃的坏条目个数（日志只记个数）。 */
data class AppealList(
    val items: List<JsonObject>,
    val dropped: Int,
)

/** 已校验的 Me 快照（页面用；只放本任务显示的字段，[raw] 是 13 个契约字段）。 */
data class Me(
    val raw: JsonObject,
    val userId: String,
    val nickname: String,
    val inviteCode: String?,
    val inviterBound: Boolean,
    val phoneBound: Boolean,
    val withdrawableFen: Long,
    val estimatedTotalFen: Long,
    val riskState: String,
    val reasonCategory: String?,
    val frozenUntil: String?,
) {
    val risk: JsonObject get() = raw.getValue("risk") as JsonObject
}
