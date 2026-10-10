package com.couli.core.update

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * GET /v1/app-versions/check 的 data（契约 AppVersionCheck）→ [UpdateCheckResult]，只投影字段、不做判定（F-X-01，FU-AND-0710；
 * 真实检查器与 debug / staging 的假数据共用同一份投影）。
 *
 * - listed_version 为 null 的商店丢弃（rule-gaps G04）。
 * - recheck_interval_sec 不在 check 响应里（rule-gaps G01），由调用方按 /v1/config.app_update 给出（[UpdateConfigReader]），没有用包内默认。
 */
object UpdateCheckAdapter {
    /**
     * 判定要用的契约必填字段齐全（AppVersionCheck.required）：latest_version 是字符串，min_supported_version / recommended_version
     * 存在（字符串或 null），stores 是数组。缺了按检查失败（unknown），不把残缺响应当成「不低于」。
     */
    fun wellFormed(data: JsonObject): Boolean {
        if (data.text("latest_version") == null || data["stores"] !is JsonArray) return false
        return listOf("min_supported_version", "recommended_version").all { key ->
            val value = data[key] ?: return@all false
            value is JsonNull || (value as? JsonPrimitive)?.isString == true
        }
    }

    fun adapt(
        data: JsonObject,
        recheckIntervalSec: Int = UpdateGate.DEFAULT_RECHECK_INTERVAL_SEC,
    ): UpdateCheckResult =
        UpdateCheckResult(
            minSupported = data.text("min_supported_version"),
            recommended = data.text("recommended_version"),
            latest = data.text("latest_version"),
            defaultStore = data.text("default_store"),
            stores =
                (data["stores"] as? JsonArray).orEmpty().mapNotNull { entry ->
                    val item = entry as? JsonObject ?: return@mapNotNull null
                    val store = item.text("store")
                    val version = item.text("listed_version")
                    if (store != null && version != null) UpdateStoreListing(store, version) else null
                },
            recheckIntervalSec = recheckIntervalSec,
            updateTitle = data.text("update_title"),
            updateNotes = data.text("update_notes"),
        )

    /** 字符串字段；缺失、null 或不是字符串为 null（JsonNull 的 isString 为 false）。 */
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** /v1/config 生效配置（current → LKG → 包内）里与强更有关的取值。纯函数。 */
object UpdateConfigReader {
    /**
     * app_update.recheck_interval_sec（契约 ConfigAppUpdate：int32，minimum 1）；没有下发、不是正整数时为 null（调用方用包内默认，
     * rule-gaps G01）。
     */
    @Suppress("ReturnCount") // 逐层取值，缺一层即 null
    fun recheckIntervalSec(config: JsonObject?): Int? {
        val section = config?.get("app_update") as? JsonObject ?: return null
        val raw = section["recheck_interval_sec"] as? JsonPrimitive ?: return null
        if (raw.isString) return null
        return raw.intOrNull?.takeIf { it > 0 }
    }
}
