package com.couli.app.wallet.visual

import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.ReplayMode
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal object WithdrawVisualFixtures {
    val root: File =
        generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "contract.lock").isFile }
    val group = obj(File(root, "specs/wallet-vectors.json").readText()).getValue("visualWithdraw").jsonObject
    val now =
        group
            .getValue("clock")
            .jsonObject
            .getValue("nowMillis")
            .jsonPrimitive.long

    fun obj(raw: String) = Json.parseToJsonElement(raw).jsonObject

    fun rules(name: String = "normal") =
        group
            .getValue("rules")
            .jsonObject
            .getValue(name)
            .jsonObject

    fun payout(name: String = "bound") =
        group
            .getValue("payout")
            .jsonObject
            .getValue(name)
            .jsonObject

    fun pending(
        payout: Boolean = false,
        busy: Boolean = false,
    ): PendingKeyRecord {
        val p =
            group
                .getValue("pending")
                .jsonObject
                .getValue(if (payout) "payout" else "withdraw")
                .jsonObject

        fun value(key: String) = p.getValue(key).jsonPrimitive.content
        return PendingKeyRecord(
            value("key"),
            value("operationId"),
            value("body"),
            value("account"),
            value("summary"),
            if (busy) "in_flight" else value("stateRaw"),
            everUnknown = true,
            createdAtMillis = now,
        )
    }

    fun ok(data: JsonObject = obj("{}")): ApiResult = ApiResult.Success(data, "visual-c-abc123", null)

    fun failure(
        code: Int = 50401,
        data: String = "{}",
    ): ApiResult =
        ApiResult.Failure(
            code,
            "",
            obj(data),
            "visual-c-abc123",
            ErrorEffect(code, ErrorEffectKind.GENERIC_ERROR, ReplayMode.NONE, false, null),
            null,
        )
}
