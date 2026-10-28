package com.couli.app.me

import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.ReplayMode
import java.io.File
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * APP-12w-c 账号状态 / 申诉的截图与视图数据：只读新文件 specs/risk-appeal-visual-vectors.json（G-R15：三端同一份，不往 me-vectors.json 追加）。
 * 只拼报文（getMe 的 13 个契约字段 + risk、listAppeals、listWithdrawals、submitAppeal 回包）与本地态（10006 进入标记、注销路由可用性）；
 * 不在这里实现任何产品规则。不带 CT-26e 字段（G-R01 / G-R18）。
 */
@Suppress("TooManyFunctions") // 逐类报文一个入口
internal object RiskVisualFixtures {
    val root: File = MeVisualFixtures.root
    val file: File = File(root, "specs/risk-appeal-visual-vectors.json")
    val data: JsonObject = Json.parseToJsonElement(file.readText()).jsonObject
    val now: Long = OffsetDateTime.parse(text("clock")).toInstant().toEpochMilli()

    fun text(
        name: String,
        from: JsonObject = data,
    ): String = from.getValue(name).jsonPrimitive.content

    fun obj(
        name: String,
        from: JsonObject = data,
    ): JsonObject = from.getValue(name).jsonObject

    fun list(
        name: String,
        from: JsonObject = data,
    ): List<String> = from.getValue(name).jsonArray.map { it.jsonPrimitive.content }

    // ---------------------------------------------------------------- 画板

    fun boards(): Map<String, JsonObject> = obj("boards").mapValues { it.value.jsonObject }

    fun board(name: String): JsonObject = boards().getValue(name)

    fun height(name: String): Int = board(name).getValue("height").jsonPrimitive.int

    fun dark(name: String): Boolean = board(name)["dark"]?.jsonPrimitive?.boolean == true

    // ---------------------------------------------------------------- getMe.risk 与进入标记

    fun riskCase(name: String): JsonObject = obj("risks").getValue(name).jsonObject

    fun risk(name: String): JsonObject = obj("risk", riskCase(name))

    fun riskState(name: String): String = text("state", risk(name))

    fun reasonCategory(name: String): String? = (risk(name)["reason_category"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun entered10006(name: String): Boolean = riskCase(name).getValue("entered10006").jsonPrimitive.boolean

    /** GET /v1/me 的 data：13 个契约字段（profile 12 个 + risk）。 */
    fun me(riskName: String): JsonObject = JsonObject(obj("profile") + ("risk" to risk(riskName)))

    // ---------------------------------------------------------------- 申诉与提现

    fun appealItems(name: String): List<JsonObject> = obj("appeals").getValue(name).jsonArray.map { it.jsonObject }

    /** 列表里 target_type=account 的最新一条（只为测试取期望值；产品口径在 AppealView.latestAccount）。 */
    fun latestAccount(name: String): JsonObject? = appealItems(name).firstOrNull { text("target_type", it) == "account" }

    fun appeals(name: String): JsonObject = JsonObject(mapOf("items" to JsonArray(appealItems(name)), "next_cursor" to JsonNull))

    fun withdrawalItems(name: String): List<JsonObject> = obj("withdrawals").getValue(name).jsonArray.map { it.jsonObject }

    fun withdrawals(name: String): JsonObject = JsonObject(mapOf("items" to JsonArray(withdrawalItems(name)), "next_cursor" to JsonNull))

    fun expect(name: String): String = text(name, obj("expect"))

    fun expectFen(name: String): Long =
        obj("expect")
            .getValue(name)
            .jsonPrimitive.content
            .toLong()

    // ---------------------------------------------------------------- 文案与路由

    /** G-R09 / G-R14 / G-R17 的草稿定稿值（键 → 句子）。 */
    fun draft(name: String): String = text(name, obj("drafts"))

    fun kept(name: String): String = text(name, obj("draftsKept"))

    val retired: List<String> get() = list("retiredPhrases")

    val forbidden: List<String> get() = list("forbiddenPhrases")

    fun routes(public: Boolean): Set<String> = list(if (public) "public" else "internal", obj("routes")).toSet()

    // ---------------------------------------------------------------- 提交

    private val submit: JsonObject get() = obj("submit")
    val input: String get() = text("input", submit)
    val inputPadded: String get() = text("inputPadded", submit)
    val blank: String get() = text("blank", submit)
    val trace: String get() = text("trace", submit)
    val retryAfterSeconds: Int get() = submit.getValue("retryAfterSeconds").jsonPrimitive.int

    fun code(name: String): Int = submit.getValue(name).jsonPrimitive.int

    /** submitAppeal 成功回包：新的一条处理中账户申诉（content = 去首尾空白后的正文）。 */
    fun submitted(content: String): JsonObject =
        JsonObject(
            mapOf(
                "appeal_id" to JsonPrimitive(text("newAppealId", submit)),
                "target_type" to JsonPrimitive("account"),
                "target_id" to JsonNull,
                "status" to JsonPrimitive("processing"),
                "content" to JsonPrimitive(content),
                "created_at" to JsonPrimitive(text("createdAt", submit)),
                "closed_at" to JsonNull,
            ),
        )

    fun ok(payload: JsonElement?): ApiResult = ApiResult.Success(payload, trace, null)

    /** 失败回包：42901 带 Retry-After（DISABLE_BUTTON + disableMillis，与网络层一致）；其余统一错误。 */
    fun failure(code: Int = code("serverCode")): ApiResult {
        val rate = code == code("rateCode")
        val effect =
            ErrorEffect(
                code,
                if (rate) ErrorEffectKind.DISABLE_BUTTON else ErrorEffectKind.GENERIC_ERROR,
                ReplayMode.NONE,
                false,
                null,
                disableMillis = if (rate) retryAfterSeconds * MILLIS else null,
                includeTrace6 = code in SERVER_CODES,
            )
        return ApiResult.Failure(code, "", JsonObject(emptyMap()), trace, effect, null)
    }

    private const val MILLIS = 1_000L
    private val SERVER_CODES = 50_000..59_999
}
