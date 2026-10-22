package com.couli.app.wallet.visual

import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.ReplayMode
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** 只读取新增 visual 组；不改既有判定向量，不制造缺契约字段。 */
internal object WithdrawRecordsVisualFixtures {
    val root: File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").canonicalFile) { it.parentFile }
            .first { File(it, "contract.lock").isFile }
    val group =
        Json
            .parseToJsonElement(File(root, "specs/wallet-vectors.json").readText())
            .jsonObject
            .getValue("visualWithdrawRecords")
            .jsonObject
    val now =
        group
            .getValue("clock")
            .jsonObject
            .getValue("nowMillis")
            .jsonPrimitive.long
    val details = group.getValue("details").jsonObject
    val pages = group.getValue("pages").jsonObject

    fun page(name: String = "list") = pages.getValue(name).jsonObject

    fun detail(name: String) = details.getValue(name).jsonObject

    fun id(name: String) = detail(name).getValue("withdrawal_id").jsonPrimitive.content

    fun list(item: JsonObject): JsonObject = JsonObject(mapOf("items" to JsonArray(listOf(item)), "next_cursor" to JsonNull))

    fun ok(data: JsonObject): ApiResult = ApiResult.Success(data, "records-visual-abc123", null)

    fun failure(code: Int = 50401): ApiResult =
        ApiResult.Failure(
            code,
            "",
            JsonObject(emptyMap()),
            "records-visual-abc123",
            ErrorEffect(code, ErrorEffectKind.GENERIC_ERROR, ReplayMode.NONE, false, null),
            null,
        )
}
