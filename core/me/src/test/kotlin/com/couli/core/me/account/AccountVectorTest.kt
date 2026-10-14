/*
 * APP-13a 实现前测试；只读产出，未编译运行。
 * 从 user.dir 向上找 contract.lock，读取 specs/account-vectors.json。
 *
 * 接线约定：
 * - 生产类型位于 com.couli.core.me.account，使用任务约定的 API 名称。
 * - JSON 接线为 JsonElement/JsonObject，返回 JsonObject；强类型实现提供薄
 *   JSON 重载，不在测试适配器重写业务判定。
 * - AccountJson.parseDeletion 返回 resultRaw/deletion；成功 deletion 的 JSON
 *   投影保留 status、can_cancel、cancel_reason 原值。
 * - DeletionPresentation 接收原始对象、JsonNull 或对应失败字符串。
 * - StepUpTokenPolicy.reuse 接收 cached/request 的 JsonObject；测试仅将
 *   expireAtIso/nowIso 转为 expireAtMs/nowMs，金额与时钟均为 Long。
 * - 缺失 expect 字段不裁定；显式 null 要求无值；textKeys 是必含集合。
 * - Outcome 的 nextStateRaw 指处理/补办返回后的状态；10405 夹具已满足
 *   “本机版本过低”前提。autoResendSameKey 表示验证后最多同键重放一次。
 * - FundRouteParams.filter 沿用 APP-09 接线；按 G43 暴露真实 Router 观察值
 *   opened、openedParams、dropped、event。opened 不得硬编码。
 *   路由可用性覆盖只用于 Debug 测试，不修改 routes.json/since。
 * - 测试依赖已有 :core:wallet、:core:designsystem、:core:generated。
 * - 不写文件、不访问网络或安全存储；失败只输出用例名/字段路径。
 *   不能替代 vectors.gaps 中的真实页面、持久化和执行层集成验收。
 */

package com.couli.core.me.account

import com.couli.core.designsystem.CouliFormat
import com.couli.core.wallet.FundRouteParams
import java.io.File
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountVectorTest {
    private val groups =
        listOf(
            "deletionJson",
            "deletionView",
            "deletionOutcomes",
            "deletionCancel",
            "phoneChangeStep1",
            "phoneChangeSubmit",
            "pendingSummary",
            "stepUpToken",
            "entryTrim",
            "bindPhonePendingRestore",
            "routeParams",
        )

    private val root: File by lazy {
        val start = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "contract.lock").isFile }
            ?: throw AssertionError("找不到 contract.lock")
    }

    @Test
    fun allAccountVectors() {
        val manifest = read("specs/account-vectors.json")
        check(manifest.number("version") == 1L, "manifest.version")
        check(manifest.array("sources").isNotEmpty(), "manifest.sources")
        check(manifest.array("gaps").isNotEmpty(), "manifest.gaps")
        check(
            manifest.keys == (groups + listOf("version", "sources", "gaps")).toSet(),
            "manifest.groups",
        )

        for (group in groups) {
            val rows = manifest.rows(group)
            check(rows.isNotEmpty(), "$group.empty")
            check(rows.map { it.text("name") }.toSet().size == rows.size, "$group.names")
            for (row in rows) {
                val label = "$group/${row.text("name")}"
                check(row.text("name").isNotEmpty(), "$label.name")
                val actual =
                    try {
                        evaluate(group, row)
                    } catch (_: Exception) {
                        // 不拼接异常消息，解析异常可能包含输入或响应原文。
                        throw AssertionError("$label.evaluation")
                    }
                match(actual, row.obj("expect"), label)
                audit(group, row, actual, label)
            }
        }
    }

    @Test
    fun everyDeletionRequiredFieldIsRequired() {
        val rows = read("specs/account-vectors.json").rows("deletionJson")
        val seed =
            rows.single {
                it.text("name") == "cooling_all_required_nullable_fields_present"
            }.obj("json")
        val expected =
            rows.single { it.text("name") == "missing_required_status" }.obj("expect")
        val schema =
            read("contracts/snapshot/contracts/openapi.json")
                .obj("components").obj("schemas").obj("Deletion")
        for (field in schema.strings("required")) {
            val input = JsonObject(seed.filterKeys { it != field })
            match(
                AccountJson.parseDeletion(input),
                expected,
                "deletionJson/missing/$field",
            )
        }
    }

    @Test
    fun eventContractExcludesDeleteAccount() {
        val routes =
            read("contracts/snapshot/specs/events.json")
                .obj("events").obj("route_params_dropped")
                .obj("fields").obj("route").strings("values")
        check("DeleteAccount" !in routes, "route_params_dropped.route")
    }

    private fun evaluate(
        group: String,
        row: JsonObject,
    ): JsonObject =
        when (group) {
            "deletionJson" -> {
                val parsed = AccountJson.parseDeletion(row.getValue("json"))
                buildJsonObject {
                    put("resultRaw", parsed.text("resultRaw"))
                    if (parsed.text("resultRaw") == "ok") {
                        val deletion = parsed.obj("deletion")
                        put("status", deletion.text("status"))
                        put("canCancel", deletion.bool("can_cancel"))
                        val reason = deletion.optionalText("cancel_reason")
                        put(
                            "cancelReasonRaw",
                            when (reason) {
                                null -> "null"
                                "user", "negative_balance" -> "known"
                                else -> "unknown"
                            },
                        )
                    }
                }
            }

            "deletionView" ->
                DeletionPresentation.of(
                    row.getValue("deletion"),
                    row.getValue("wallet"),
                    row.text("phoneBoundRaw"),
                    row.text("pendingRaw"),
                    row.text("scopeRaw"),
                    row.text("sourceRaw"),
                    milliseconds(row.text("nowIso")),
                )

            "deletionOutcomes" ->
                DeletionOutcomePolicy.of(
                    row.text("phaseRaw"),
                    row.optionalInt("http"),
                    row.optionalInt("code"),
                    row["data"] ?: JsonNull,
                    row.optionalText("retryAfter"),
                    row.optionalText("traceId"),
                    row.optionalText("sourceRaw") ?: "settings",
                    row.optionalBool("hadUnknownResult") ?: (row.text("phaseRaw") != "apply"),
                )

            "deletionCancel" ->
                DeletionCancelPolicy.of(
                    row.optionalInt("http"),
                    row.optionalInt("code"),
                    row["data"] ?: JsonNull,
                    row.text("sourceRaw"),
                )

            "phoneChangeStep1" ->
                PhoneChangeStep1Policy.of(
                    row.text("eventRaw"),
                    row.optionalInt("http"),
                    row.optionalInt("code"),
                    row["data"] ?: JsonNull,
                    row.bool("phoneKnown"),
                )

            "phoneChangeSubmit" ->
                PhoneChangeOutcomePolicy.of(
                    row.text("phaseRaw"),
                    row.optionalInt("http"),
                    row.optionalInt("code"),
                    row["data"] ?: JsonNull,
                    row.optionalText("retryAfter"),
                    row.optionalText("traceId"),
                    row.optionalBool("tokenValid") ?: false,
                )

            "pendingSummary" ->
                buildJsonObject {
                    val masked = PhoneMask.summary(row.text("input"))
                    put("masked", masked?.let(::JsonPrimitive) ?: JsonNull)
                }

            "stepUpToken" -> {
                val cached =
                    row["cached"].takeUnless { it == null || it == JsonNull }?.let {
                        val source = it.jsonObject
                        JsonObject(
                            source.filterKeys { key -> key != "expireAtIso" } +
                                (
                                    "expireAtMs" to
                                        JsonPrimitive(milliseconds(source.text("expireAtIso")))
                                ),
                        )
                    }
                val source = row.obj("request")
                val request =
                    JsonObject(
                        source.filterKeys { it != "nowIso" } +
                            ("nowMs" to JsonPrimitive(milliseconds(source.text("nowIso")))),
                    )
                StepUpTokenPolicy.reuse(cached, request)
            }

            "entryTrim" ->
                AccountEntryPolicy.of(
                    row.text("sessionRaw"),
                    row.bool("forceUpdate"),
                    row.text("phoneBoundRaw"),
                    row.bool("deleteAccountAvailable"),
                    row["deletion"],
                )

            "bindPhonePendingRestore" ->
                BindPhonePendingPolicy.restore(
                    row.rows("pendingRecords"),
                    row.text("account"),
                    row.text("entryRaw"),
                )

            "routeParams" -> {
                val filtered = FundRouteParams.filter(row.text("route"), row.obj("params"))
                check(filtered.obj("openedParams").isEmpty(), "routeParams.openedParams")
                buildJsonObject {
                    put("opened", filtered.bool("opened"))
                    put("paramsDropped", filtered.number("dropped") == row.obj("params").size.toLong())
                    put(
                        "routeParamsDroppedEvent",
                        filtered.optionalText("event") == "route_params_dropped",
                    )
                }
            }

            else -> throw AssertionError("未知向量组")
        }

    private fun audit(
        group: String,
        row: JsonObject,
        actual: JsonObject,
        label: String,
    ) {
        if (group != "deletionView") return
        row.obj("expect").optionalText("coolingUntilText")?.let { expected ->
            val formatted =
                CouliFormat.timeListFuture(
                    milliseconds(row.obj("deletion").text("cooling_until")),
                    milliseconds(row.text("nowIso")),
                )
            check(expected == formatted, "$label.formatFixture")
            check(actual.optionalText("coolingUntilText") == formatted, "$label.formatWiring")
        }
        if (
            row.text("phoneBoundRaw") == "unknown" &&
            row["deletion"] == JsonNull &&
            row.text("pendingRaw") == "none"
        ) {
            val keys = actual.strings("textKeys")
            check(
                "deletion.cooling.title" !in keys &&
                    "pending_confirm.already_done" !in keys,
                "$label.noFalseSuccess",
            )
        }
    }

    private fun match(
        actual: JsonElement?,
        expected: JsonElement,
        path: String,
    ) {
        when (expected) {
            JsonNull -> check(actual == null || actual == JsonNull, "$path.null")
            is JsonObject -> {
                check(actual is JsonObject, "$path.object")
                val found = actual as JsonObject
                for ((key, wanted) in expected) {
                    if (key == "textKeys") {
                        check(
                            found.strings(key).containsAll(expected.strings(key)),
                            "$path.textKeys",
                        )
                    } else {
                        match(found[key], wanted, "$path.$key")
                    }
                }
            }

            is JsonArray -> {
                check(actual is JsonArray, "$path.array")
                val found = actual as JsonArray
                check(found.size == expected.size, "$path.size")
                expected.indices.forEach { index ->
                    match(found[index], expected[index], "$path[$index]")
                }
            }

            is JsonPrimitive -> check(actual == expected, "$path.scalar")
        }
    }

    private fun read(path: String): JsonObject =
        Json.parseToJsonElement(File(root, path).readText(Charsets.UTF_8)).jsonObject

    private fun milliseconds(iso: String): Long {
        check(iso.endsWith("+08:00"), "time.offset")
        return OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    }

    private fun JsonObject.obj(key: String): JsonObject =
        getValue(key) as? JsonObject ?: throw AssertionError("$key.object")

    private fun JsonObject.array(key: String): JsonArray =
        getValue(key) as? JsonArray ?: throw AssertionError("$key.array")

    private fun JsonObject.rows(key: String): List<JsonObject> =
        array(key).map { it as? JsonObject ?: throw AssertionError("$key.object") }

    private fun JsonObject.strings(key: String): List<String> =
        array(key).map {
            val primitive = it as? JsonPrimitive ?: throw AssertionError("$key.string")
            check(primitive.isString, "$key.stringType")
            primitive.content
        }

    private fun JsonObject.text(key: String): String {
        val value = getValue(key) as? JsonPrimitive ?: throw AssertionError("$key.string")
        check(value.isString, "$key.stringType")
        return value.content
    }

    private fun JsonObject.optionalText(key: String): String? =
        if (this[key] == null || this[key] == JsonNull) null else text(key)

    private fun JsonObject.number(key: String): Long {
        val value = getValue(key) as? JsonPrimitive ?: throw AssertionError("$key.number")
        check(!value.isString, "$key.numberType")
        return value.longOrNull ?: throw AssertionError("$key.integer")
    }

    private fun JsonObject.optionalInt(key: String): Int? {
        if (this[key] == null || this[key] == JsonNull) return null
        val value = number(key)
        check(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(), "$key.range")
        return value.toInt()
    }

    private fun JsonObject.bool(key: String): Boolean {
        val value = getValue(key) as? JsonPrimitive ?: throw AssertionError("$key.boolean")
        check(!value.isString, "$key.booleanType")
        return value.booleanOrNull ?: throw AssertionError("$key.boolean")
    }

    private fun JsonObject.optionalBool(key: String): Boolean? =
        if (this[key] == null || this[key] == JsonNull) null else bool(key)

    private fun check(
        condition: Boolean,
        path: String,
    ) {
        assertTrue(path, condition)
    }
}
