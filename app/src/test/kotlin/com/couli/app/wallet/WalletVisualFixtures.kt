package com.couli.app.wallet

import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.ReplayMode
import java.io.File
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 只读新增 visualWallet；不复用/改写原判定组。 */
internal object WalletVisualFixtures {
    val root: File =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "specs/wallet-vectors.json").isFile }
    private val visual =
        Json
            .parseToJsonElement(File(root, "specs/wallet-vectors.json").readText())
            .jsonObject
            .getValue("visualWallet")
            .jsonObject
    val now: Long = instant("clock")
    val cachedAt: Long = instant("cachedAt")
    const val TRACE = "wallet-visual-000123"

    fun summary(name: String = "normal"): JsonObject =
        visual
            .getValue("summaries")
            .jsonObject
            .getValue(name)
            .jsonObject

    fun ok(data: JsonObject = summary()): ApiResult = ApiResult.Success(data, TRACE, null)

    fun offline(): ApiResult = ApiResult.TransportFailure("disconnect", null, null, pendingKeyUnknown = false)

    fun failure(): ApiResult =
        ApiResult.Failure(
            50001,
            "",
            JsonObject(emptyMap()),
            TRACE,
            ErrorEffect(50001, ErrorEffectKind.GENERIC_ERROR, ReplayMode.NONE, false, null),
            null,
        )

    private fun instant(key: String): Long = OffsetDateTime.parse(visual.getValue(key).jsonPrimitive.content).toInstant().toEpochMilli()
}
