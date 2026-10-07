package com.couli.core.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 一个桥方法在契约里的登记（contracts/bridge.schema.json methods.*）。 */
@Suppress("LongParameterList") // 契约字段逐项
internal class BridgeMethodContract(
    val name: String,
    val level: String,
    val model: String,
    val timeoutMs: Long?,
    val sinceAndroid: String?,
    val gestureRequired: Boolean,
    val params: JsonObject,
) {
    /** L2，或非 L2 但 gesture_required（最近 1 秒内有用户点击）。 */
    val needsGesture: Boolean get() = level == LEVEL_L2 || gestureRequired

    /** L1、L2 要求登录。 */
    val needsLogin: Boolean get() = level == LEVEL_L1 || level == LEVEL_L2

    companion object {
        const val LEVEL_L1 = "L1"
        const val LEVEL_L2 = "L2"
    }
}

/** signed_paths 的一项。 */
internal class SignedPath(
    val method: String,
    val path: String,
)

/**
 * 桥契约（core:generated 的资源 bridge.schema.json，由 gen.py 从快照原样复制）。只读，进程内加载一次。
 * 方法表、错误码、signed_paths、not_exposed、events 都只来自这里，本模块不另存方法清单。
 */
internal object BridgeContract {
    private const val RESOURCE = "bridge.schema.json"

    private val root: JsonObject by lazy {
        val stream =
            BridgeContract::class.java.classLoader?.getResourceAsStream(RESOURCE)
                ?: error("missing resource $RESOURCE")
        stream.use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
    }

    val methods: Map<String, BridgeMethodContract> by lazy {
        root.getValue("methods").jsonObject.mapValues { (name, element) -> method(name, element.jsonObject) }
    }

    val errors: Map<Int, String> by lazy {
        root
            .getValue("errors")
            .jsonObject.entries
            .associate { (code, text) -> code.toInt() to (text as JsonPrimitive).content }
    }

    val signedPaths: List<SignedPath> by lazy {
        (root.getValue("signed_paths") as JsonArray).map {
            val item = it.jsonObject
            SignedPath(item.text("method").orEmpty(), item.text("path").orEmpty())
        }
    }

    val events: Set<String> by lazy { root.getValue("events").jsonObject.keys }

    val notExposed: Set<String> by lazy {
        (root.getValue("not_exposed") as JsonArray).mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
    }

    val defs: JsonObject by lazy { root["\$defs"] as? JsonObject ?: JsonObject(emptyMap()) }

    val version: Int by lazy { (root["version"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1 }

    /** share.open 的分享页路径（share_page_paths.*.path_pattern；null = 未定，TODO(N-22)）。 */
    val sharePagePatterns: List<Regex> by lazy {
        val pages = (root.getValue("methods").jsonObject["share.open"] as? JsonObject)?.get("share_page_paths") as? JsonObject
        pages
            ?.filterKeys { !it.startsWith("\$") }
            ?.values
            ?.mapNotNull { (it as? JsonObject)?.text("path_pattern") }
            ?.mapNotNull { runCatching { Regex(it) }.getOrNull() }
            .orEmpty()
    }

    private fun method(
        name: String,
        spec: JsonObject,
    ): BridgeMethodContract =
        BridgeMethodContract(
            name = name,
            level = spec.text("level").orEmpty(),
            model = spec.text("model").orEmpty(),
            timeoutMs = (spec["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull(),
            sinceAndroid = (spec["since"] as? JsonObject)?.text("android"),
            gestureRequired = (spec["gesture_required"] as? JsonPrimitive)?.content == "true",
            params = spec["params"] as? JsonObject ?: JsonObject(emptyMap()),
        )

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
