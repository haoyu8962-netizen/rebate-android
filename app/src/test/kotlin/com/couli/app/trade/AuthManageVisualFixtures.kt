@file:Suppress("MagicNumber")

package com.couli.app.trade

import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.ReplayMode
import java.io.File
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * APP-06w fixture 读取（specs/auth-manage-visual-vectors.json，G-A13：三端同一份、不往 open-trade-vectors.json 追加）。
 * 授权地址、state、凭证都不在 fixture 里：由这里按固定短串生成（gitleaks / 安全，brief §6）；平台中文名只经文案字典。
 */
internal object AuthManageVisualFixtures {
    val root: File =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "specs/open-trade-vectors.json").isFile }
    val file: File = File(root, "specs/auth-manage-visual-vectors.json")
    val data: JsonObject = Json.parseToJsonElement(file.readText()).jsonObject
    private val clock: JsonObject = data.getValue("clock").jsonObject
    val zone: String = clock.getValue("zone").jsonPrimitive.content
    val now: Long = OffsetDateTime.parse(clock.getValue("now").jsonPrimitive.content).toInstant().toEpochMilli()
    val retryAfterMillis: Long = clock.getValue("retryAfterSeconds").jsonPrimitive.int * 1_000L
    val recoverProbeMillis: Long =
        clock
            .getValue("recoverProbeMillis")
            .jsonPrimitive.int
            .toLong()
    private val codes: JsonObject = data.getValue("codes").jsonObject
    val traceId: String = code("c50001").getValue("traceId").jsonPrimitive.content
    val trace6: String = code("c50001").getValue("trace6").jsonPrimitive.content

    fun code(name: String): JsonObject = codes.getValue(name).jsonObject

    fun wording(name: String): String = code(name).getValue("wording").jsonPrimitive.content

    fun bindings(name: String): JsonArray =
        data
            .getValue("bindings")
            .jsonObject
            .getValue(name)
            .jsonArray

    /** 投影：can_grant / authorized / blocked。 */
    fun projection(name: String): Map<String, String> =
        data
            .getValue("projection")
            .jsonObject
            .getValue(name)
            .jsonObject
            .mapValues { it.value.jsonPrimitive.content }

    fun snapshots(): List<String> = data.getValue("snapshots").jsonArray.map { it.jsonPrimitive.content }

    fun list(name: String): ApiResult = ok(JsonObject(mapOf("items" to bindings(name))))

    /** 取授权地址的成功应答：taobao_web_code / taobao_sdk / pdd_jump（地址与 state 是固定短串，不来自 fixture）。 */
    fun grant(name: String): ApiResult {
        val spec =
            data
                .getValue("grantUrls")
                .jsonObject
                .getValue(name)
                .jsonObject
        val platform = spec.getValue("platform").jsonPrimitive.content
        val url = "https://grant.example.test/$platform"
        val state = "st-fixture-$platform"
        val methods = spec["methods"]
        val body =
            if (methods != null) {
                mapOf("auth_url" to JsonPrimitive(url), "state" to JsonPrimitive(state), "auth_methods" to methods)
            } else {
                val plan =
                    JsonObject(
                        mapOf(
                            "primary" to
                                JsonObject(mapOf("type" to spec.getValue("jumpType"), "value" to JsonPrimitive(url))),
                            "fallbacks" to JsonArray(emptyList()),
                            // 正式 AppTrade 的跳转执行器按墙钟判过期：只把方案有效期放远
                            "expire_at" to JsonPrimitive("2099-10-29T11:00:00+08:00"),
                        ),
                    )
                mapOf("state" to JsonPrimitive(state), "auth_jump" to plan)
            }
        return ok(JsonObject(body))
    }

    fun bound(platform: String = "taobao"): ApiResult =
        ok(JsonObject(mapOf("platform" to JsonPrimitive(platform), "status" to JsonPrimitive("active"))))

    /** 业务错误：fixture codes 组的 [name]（reason / traceId / Retry-After 照 fixture）。 */
    fun failure(
        name: String,
        retryAfterMillis: Long? = null,
    ): ApiResult.Failure {
        val c = code(name)
        val code = c.getValue("code").jsonPrimitive.int
        val reason = c["reason"]?.jsonPrimitive?.content
        val trace = c["traceId"]?.jsonPrimitive?.content ?: "amv-trace-000000"
        val data = JsonObject(reason?.let { mapOf("reason" to JsonPrimitive(it)) }.orEmpty())
        val disable =
            if (code == 42901) {
                retryAfterMillis ?: (c.getValue("retryAfterSeconds").jsonPrimitive.int * 1_000L)
            } else {
                null
            }
        val effect =
            ErrorEffect(
                code,
                if (disable != null) ErrorEffectKind.DISABLE_BUTTON else ErrorEffectKind.PAGE_ACTION,
                ReplayMode.NONE,
                false,
                disableMillis = disable,
            )
        return ApiResult.Failure(code, "", data, trace, effect, null)
    }

    /** 断网 / 超时（传输失败，不带统一外壳）。 */
    fun offline(): ApiResult = ApiResult.TransportFailure(code("offline").getValue("transport").jsonPrimitive.content, null, null, false)

    private fun ok(data: JsonObject): ApiResult = ApiResult.Success(data, "amv-trace-ok", null)
}
