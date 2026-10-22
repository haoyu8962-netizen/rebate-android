package com.couli.app.wallet.visual

import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 长列表仅复制既有 visual 数据，不碰业务向量；金额及枚举原封不动。 */
internal object WithdrawRecordsV2Fixtures {
    fun id(index: Int) = "V2_$index"

    fun rows(
        start: Int = 0,
        count: Int = 20,
        source: String = "failed",
    ): List<JsonObject> =
        (start until start + count).map { index ->
            JsonObject(
                WithdrawRecordsVisualFixtures.detail(source) +
                    mapOf(
                        "withdrawal_id" to JsonPrimitive(id(index)),
                        "created_at" to
                            JsonPrimitive(OffsetDateTime.parse("2026-10-12T10:00:00+08:00").minusMinutes(index.toLong()).toString()),
                    ),
            )
        }

    fun page(
        rows: List<JsonObject>,
        cursor: String? = null,
    ): JsonObject = JsonObject(mapOf("items" to JsonArray(rows), "next_cursor" to (cursor?.let(::JsonPrimitive) ?: JsonNull)))

    fun inSeptember(row: JsonObject): JsonObject = JsonObject(row + ("created_at" to JsonPrimitive("2026-09-20T10:00:00+08:00")))
}
