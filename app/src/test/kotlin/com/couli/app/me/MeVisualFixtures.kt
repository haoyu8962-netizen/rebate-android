package com.couli.app.me

import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.ReplayMode
import java.io.File
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object MeVisualFixtures {
    val root: File =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "specs/me-vectors.json").isFile }
    val visual =
        Json
            .parseToJsonElement(File(root, "specs/me-vectors.json").readText())
            .jsonObject
            .getValue("visualMe")
            .jsonObject
    val now = OffsetDateTime.parse(visual.getValue("clock").jsonPrimitive.content).toInstant().toEpochMilli()
    const val TRACE = "me-visual-a1b2c3"

    fun profile(name: String = "me_member") =
        visual
            .getValue("profiles")
            .jsonObject
            .getValue(name)
            .jsonObject

    fun routes(public: Boolean = false) =
        visual
            .getValue(if (public) "routes_public" else "routes_beta")
            .jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()

    fun ok(data: JsonObject = profile()): ApiResult = ApiResult.Success(data, TRACE, null)

    fun offline(): ApiResult = ApiResult.TransportFailure("disconnect", null, null, pendingKeyUnknown = false)

    fun failure(code: Int = 50001): ApiResult =
        ApiResult.Failure(
            code,
            "",
            JsonObject(emptyMap()),
            TRACE,
            ErrorEffect(code, ErrorEffectKind.GENERIC_ERROR, ReplayMode.NONE, false, null),
            null,
        )
}
