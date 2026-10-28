package com.couli.app.me

import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.ReplayMode
import java.io.File
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * APP-12w-b 隐私中心 / 关于 / 联系客服的截图与视图数据：只读新文件 specs/privacy-visual-vectors.json（G-P13：三端同一份，不往 me-vectors.json 追加）。
 * 只给本地状态与配置报文（身份档、个性化本机值、设备是否注册、强更上下文、filing_text、诊断字段、kf / 微信 / 拉起 / 复制结果）；不在这里实现任何产品规则。
 */
internal object PrivacyVisualFixtures {
    val root: File = MeVisualFixtures.root
    val file: File = File(root, "specs/privacy-visual-vectors.json")
    val data: JsonObject = Json.parseToJsonElement(file.readText()).jsonObject
    val now: Long = OffsetDateTime.parse(text("clock")).toInstant().toEpochMilli()
    const val TRACE = "trace-visual-0000a1b2c3"

    fun text(
        name: String,
        from: JsonObject = data,
    ): String = from.getValue(name).jsonPrimitive.content

    fun obj(
        name: String,
        from: JsonObject = data,
    ): JsonObject = from.getValue(name).jsonObject

    // ---------------------------------------------------------------- 隐私中心

    fun session(name: String): JsonObject = obj("sessions").getValue(name).jsonObject

    fun level(name: String): String = text("level", session(name))

    fun personalization(name: String): Boolean = session(name).getValue("personalization").jsonPrimitive.boolean

    fun deviceRegistered(name: String): Boolean = session(name).getValue("deviceRegistered").jsonPrimitive.boolean

    fun legalPrivacyVersion(name: String): Int = session(name).getValue("legalPrivacyVersion").jsonPrimitive.int

    fun forceUpdate(name: String): Boolean = session(name).getValue("forceUpdate").jsonPrimitive.boolean

    fun permissionStatus(
        name: String,
        type: String,
    ): String = obj("permissionStatus", session(name))[type]?.jsonPrimitive?.content ?: "not_asked"

    /** 红线 ④ 只用于行为（不截图、不冒充契约，G-P15）：一条 android 条目，字段名按 system-permissions.json。 */
    fun behaviorPermissionEntry(): JsonObject {
        val raw = obj("behaviorOnlyPermissionEntry")
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive(text("type", raw)),
                "platform" to JsonPrimitive(text("platform", raw)),
                "purpose_" + "key" to JsonPrimitive(text("purpose", raw)),
            ),
        )
    }

    // ---------------------------------------------------------------- 关于

    private val about: JsonObject get() = obj("about")
    val version: String get() = text("version", about)
    val build: String get() = text("build", about)
    val filed: String get() = text("filed", about)
    val unfiled: String get() = text("unfiled", about)
    val diagnostics: JsonObject get() = obj("diagnostics", about)
    val diagnosticsText: String get() = about.getValue("diagnosticsText").jsonArray.joinToString("\n") { it.jsonPrimitive.content }
    val diagnosticsForbidden: List<String> get() = about.getValue("diagnosticsMustNotContain").jsonArray.map { it.jsonPrimitive.content }

    /** /v1/config 的 agent.filing_text（null = 未配置整个 agent）。 */
    fun config(filing: String?): JsonObject? =
        filing?.let { JsonObject(mapOf("agent" to JsonObject(mapOf("filing_text" to JsonPrimitive(it))))) }

    // ---------------------------------------------------------------- 客服

    private val cs: JsonObject get() = obj("cs")
    val kf: String get() = text("kf", cs)
    val kfInsecure: String get() = text("kfInsecure", cs)
    val safetyDraft: String get() = text("safetyDraft", cs)
    val safetyOld: String get() = text("safetyOld", cs)

    fun case(name: String): JsonObject = obj("cases", cs).getValue(name).jsonObject

    fun caseKf(name: String): String? =
        when (text("kf", case(name))) {
            "https" -> kf
            "http" -> kfInsecure
            else -> null
        }

    fun caseBool(
        name: String,
        field: String,
    ): Boolean = case(name).getValue(field).jsonPrimitive.boolean

    fun caseButtons(name: String): List<String> = case(name).getValue("buttons").jsonArray.map { it.jsonPrimitive.content }

    // ---------------------------------------------------------------- 轻提示与看板

    /** 待显示轻提示：文案键 → 是否带对勾。 */
    fun notices(): List<Pair<String, Boolean>> =
        data.getValue("notices").jsonArray.map {
            val row = it.jsonObject
            text("text", row) to row.getValue("check").jsonPrimitive.boolean
        }

    fun boards(): Map<String, JsonObject> = obj("boards").mapValues { it.value.jsonObject }

    fun height(board: String): Int =
        boards()
            .getValue(board)
            .getValue("height")
            .jsonPrimitive.int

    fun ok(payload: JsonObject = JsonObject(emptyMap())): ApiResult = ApiResult.Success(payload, TRACE, null)

    fun failure(code: Int = obj("personalizationFailure").getValue("code").jsonPrimitive.int): ApiResult =
        ApiResult.Failure(
            code,
            "",
            JsonObject(emptyMap()),
            TRACE,
            ErrorEffect(code, ErrorEffectKind.GENERIC_ERROR, ReplayMode.NONE, false, null),
            null,
        )
}
