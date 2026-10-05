// 生成物，禁止手改；来源 contract.lock（tools/codegen/gen.py 由 contracts/snapshot/ 生成）
package com.couli.generated

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 包内默认文案（BR-TEXT-12 唯一来源）。读取 Java 资源 texts.default.json（快照原样复制），
 * 由 classloader 加载，JVM 单测与 Robolectric 都能读到。
 */
object DefaultTexts {
    private const val RESOURCE = "texts.default.json"

    private class Loaded(
        val texts: kotlin.collections.Map<kotlin.String, kotlin.String>,
        val fallbacks: kotlin.collections.Map<kotlin.String, kotlin.String>,
    )

    private val loaded: Loaded by lazy {
        val stream =
            DefaultTexts::class.java.classLoader?.getResourceAsStream(RESOURCE)
                ?: error("missing resource $RESOURCE")
        val root = stream.use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
        Loaded(
            texts = root.getValue("texts").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content },
            fallbacks = root.getValue("fallbacks").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content },
        )
    }

    /** texts 里的值。 */
    fun text(key: kotlin.String): kotlin.String? = loaded.texts[key]

    /** fallbacks 里的值。 */
    fun fallback(key: kotlin.String): kotlin.String? = loaded.fallbacks[key]

    /** texts 的全部键（契约顺序）。 */
    val allKeys: kotlin.collections.List<kotlin.String> get() = loaded.texts.keys.toList()
}
