package com.couli.core.designsystem

import com.couli.generated.DefaultTexts
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * FU-X-CTA-KEY-DRIFT 共用向量 specs/cta-key-vectors.json（三端照抄、逐字节一致）的读取与 harness（向量 harness 字段）：
 * baseCard 逐字段被 card 覆盖，值为 null 表示删掉该字段；texts 三种写法见向量 textsModes。只读文件，不改向量。
 */
internal object CtaKeyVectors {
    /** 2026-11-07 11:30:00 +08:00，固定（与 iOS 同一时刻）。 */
    const val NOW = 1_794_022_200_000L

    /** D10-857：三端统一锁到 rebate-platform a89c1ec3a（CT-26t #2450）。 */
    const val LOCK_COMMIT = "a89c1ec3a7f4de0b069742af909eba6545358ea3"

    /** iOS 原件 worktrees/native/FU-X-CTA-KEY-DRIFT-ios/specs/cta-key-vectors.json 的 sha256（tests-ios-v1.sha256 末行）。 */
    const val SHARED_SHA256 = "d93b2905983bb07a8cca624f3a8af2a9a9b7220e49f34d43886270cb32f4bc40"

    val root: File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").canonicalFile) { it.parentFile }
            .first { File(it, "contract.lock").isFile }

    val file: File get() = File(root, "specs/cta-key-vectors.json")

    val data: JsonObject by lazy { Json.parseToJsonElement(file.readText()).jsonObject }

    val base: JsonObject get() = data.getValue("baseCard").jsonObject

    val cases: List<JsonObject> get() = data.getValue("cases").jsonArray.map { it.jsonObject }

    /** 向量的三种 texts 写法（textsModes）：contract 缺键空串、echo 缺键回键名（首页文案函数的写法）、none 一律空串。 */
    fun texts(mode: String): ProductCardTexts =
        when (mode) {
            "contract" -> ProductCardTexts { key -> DefaultTexts.text(key).orEmpty() }
            "echo" -> ProductCardTexts { key -> DefaultTexts.text(key) ?: key }
            "none" -> ProductCardTexts { "" }
            else -> error("未知 texts 模式：$mode")
        }

    /** [base] 逐字段被 [overrides] 覆盖；JsonNull 表示删掉该字段（harness）。 */
    fun merge(
        base: JsonObject,
        overrides: Map<String, JsonElement>,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(base)
        overrides.forEach { (key, value) ->
            if (value is JsonNull) {
                out.remove(key)
            } else {
                out[key] = value
            }
        }
        return JsonObject(out)
    }

    /** 这条 case 映射用的卡：baseCard + case.card。 */
    fun card(row: JsonObject): JsonObject = merge(base, row.getValue("card").jsonObject)

    /** baseCard，cta.text_key 换成 [ctaKey]，再用 [extra] 覆盖（值为 JsonNull 表示删掉）。 */
    fun card(
        ctaKey: String,
        extra: Map<String, JsonElement> = emptyMap(),
    ): JsonObject = merge(merge(base, mapOf("cta" to cta(ctaKey))), extra)

    fun cta(key: String): JsonObject = JsonObject(mapOf("text_key" to JsonPrimitive(key)))

    /** 这条 case 的 cta.text_key 原值（没有 cta 为空串）。 */
    fun ctaKey(row: JsonObject): String = ((card(row)["cta"] as? JsonObject)?.get("text_key") as? JsonPrimitive)?.content.orEmpty()

    fun label(row: JsonObject): String = "${row.text("id")}（${row.text("note")}）"

    fun buyEnabled(row: JsonObject): Boolean = (row["buyEnabled"] as? JsonPrimitive)?.booleanOrNull ?: true

    fun expect(row: JsonObject): JsonObject = row.getValue("expect").jsonObject

    /** expect.title：页面（list / detail / agent）→ normal 状态下按钮上的整行文字；缺的页面不判定。 */
    fun titles(row: JsonObject): Map<String, String> =
        (expect(row)["title"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()

    fun state(state: BuyButtonState): String =
        when (state) {
            BuyButtonState.NORMAL -> "normal"
            BuyButtonState.LOADING -> "loading"
            BuyButtonState.DISABLED -> "disabled"
            BuyButtonState.RETRY_LATER -> "retry_later"
        }

    fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    fun JsonObject.textOrNull(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
