/*
 * 实现前测试；只读输出，未编译运行。
 *
 * 接线约定：
 * - 下列生产类型位于 com.couli.core.wallet，JSON 对象边界均为 JsonObject。
 *   可以由强类型模型提供同名 JSON 接线，测试适配器不得实现业务判定。
 * - WalletSummaryView.of(summary: JsonObject, masked: Boolean, nowMs: Long)
 *   -> JsonObject；每个可见金额节点含 fen、可选 maskedText，隐藏时均为 "****"。
 * - WithdrawAmountInput.parse(text: String): Long?；
 *   WithdrawAmountInput.display(fen: Long): String。
 * - WithdrawRulesView.of(rules: JsonObject, inputFen: Long?, slaText: String?)
 *   -> JsonObject。
 * - WithdrawEstimateGate.shouldApply(request: JsonObject, current: JsonObject): Boolean。
 * - WithdrawBlockGuide.of(blockCode: Int?, blockReason: String?): JsonObject。
 * - WithdrawConditionText.of(reason: String?): JsonObject。
 * - SensitiveOutcome.of(op: String, phase: String, keyEverUnknown: Boolean,
 *   result: JsonObject): JsonObject。result 是已解包判定输入，不是 HTTP 外壳；
 *   kind=ok 可省略与判定无关的 data。op=payout_account 对应
 *   服务端 action=payout_account_change。
 * - StepUpTokenCache()；put(action, account, token, expireAtMs: Long)；
 *   take(action, account, nowMs: Long): String?；onSuccess(action)；
 *   onSessionChanged()；on10003(action)。take 不核销服务端 token。
 * - 以下返回 JsonObject：
 *   WithdrawalText.of(item, slaText: String?)；TradeNoRow.of(detail)；
 *   PayoutAccountView.of(data)；PayoutPendingSummary.of(form)；
 *   PayoutAccountForm.body(form)；RealNameForm.normalize(input)；
 *   FundRouteParams.filter(route: String, params: JsonObject)。
 *   PayoutAccountForm.fieldErrors 是字段名数组。
 * - EarningsEntry.visible(flag: Boolean?, routeSince: String?, appVersion: String)
 *   -> Boolean；WalletStaleCache.isCacheable(kind: String) /
 *   clearsOn(event: String) -> Boolean。
 * - 时钟只由夹具 +08:00 ISO 注入为毫秒，金额为 Long 整数分。
 *   draft 键与 values 接线见 vectors.gaps，实际金额渲染复用 CouliFormat。
 * - expect 缺字段不判定；显式 null 要求无值；数组长度和顺序精确；
 *   body、openedParams、subLines.values 精确比较。
 * - anyMaskedAmountShowsDigits 由测试观察实际 maskedText 得到，不要求生产提供。
 *   内部 fen 保留，月份数字不属于隐藏金额扫描。
 * - JUnit4、kotlinx.serialization.json 使用仓库已有依赖，不添加新依赖。
 *   不访问网络、密钥库、系统剪贴板，不做真实提现，不读取系统时钟。
 * - 失败消息只含用例名和字段路径，禁止打印姓名、账号、身份证号或请求体。
 *   gaps 中的执行层、页面与持久化验收需另做。
 */

package com.couli.core.wallet

import java.io.File
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletVectorTest {
    private val groups =
        listOf(
            "summaryView", "amountInput", "amountDisplay", "rulesView",
            "estimateGate", "blockGuide", "conditionText", "outcome",
            "stepUpToken", "withdrawalText", "tradeNo", "payoutView",
            "payoutPendingSummary", "payoutForm", "realNameForm",
            "earningsEntry", "walletCache", "walletCacheClear", "routeParams",
        )

    private val vectors: JsonObject by lazy {
        val start =
            File(System.getProperty("user.dir") ?: error("缺少 user.dir")).canonicalFile
        val root =
            generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "contract.lock").isFile }
                ?: error("从 user.dir 向上找不到 contract.lock")
        Json.parseToJsonElement(
            File(root, "specs/wallet-vectors.json").readText(Charsets.UTF_8),
        ).jsonObject
    }

    @Test
    fun manifest() {
        assertEquals(1, vectors.getValue("version").jsonPrimitive.int)
        assertTrue(vectors.array("sources").isNotEmpty())
        assertTrue(vectors.array("gaps").isNotEmpty())

        for (group in groups) {
            val rows = vectors.rows(group)
            assertTrue("$group 非空", rows.isNotEmpty())
            val names = rows.map { it.text("name") }
            assertTrue("$group 名称非空", names.all { it.isNotBlank() })
            assertEquals("$group 名称唯一", names.size, names.toSet().size)
            rows.forEach { assertTrue("$group.expect", it.obj("expect").isNotEmpty()) }
        }

        vectors.rows("summaryView").forEach {
            assertEquals("WalletSummary 字段数", 13, it.obj("summary").size)
        }
        vectors.rows("rulesView").forEach {
            assertEquals("WithdrawRules 字段数", 11, it.obj("rules").size)
        }
        vectors.rows("withdrawalText").forEach {
            assertEquals("WithdrawalSummary 字段数", 13, it.obj("item").size)
        }
    }

    @Test fun summaryView() = runGroup("summaryView")
    @Test fun amountInput() = runGroup("amountInput")
    @Test fun amountDisplay() = runGroup("amountDisplay")
    @Test fun rulesView() = runGroup("rulesView")
    @Test fun estimateGate() = runGroup("estimateGate")
    @Test fun blockGuide() = runGroup("blockGuide")
    @Test fun conditionText() = runGroup("conditionText")
    @Test fun outcome() = runGroup("outcome")
    @Test fun stepUpToken() = runGroup("stepUpToken")
    @Test fun withdrawalText() = runGroup("withdrawalText")
    @Test fun tradeNo() = runGroup("tradeNo")
    @Test fun payoutView() = runGroup("payoutView")
    @Test fun payoutPendingSummary() = runGroup("payoutPendingSummary")
    @Test fun payoutForm() = runGroup("payoutForm")
    @Test fun realNameForm() = runGroup("realNameForm")
    @Test fun earningsEntry() = runGroup("earningsEntry")
    @Test fun walletCache() = runGroup("walletCache")
    @Test fun walletCacheClear() = runGroup("walletCacheClear")
    @Test fun routeParams() = runGroup("routeParams")

    private fun runGroup(group: String) {
        val rows = vectors.rows(group)
        assertTrue("$group 非空", rows.isNotEmpty())
        for (row in rows) {
            val label = "$group.${row.text("name")}"
            try {
                match(evaluate(group, row, label), row.obj("expect"), label)
            } catch (_: Exception) {
                // 不把解析库或生产函数异常中的原始输入写入报告。
                throw AssertionError("$label：接线或类型错误")
            }
        }
    }

    private fun evaluate(
        group: String,
        row: JsonObject,
        label: String,
    ): JsonObject =
        when (group) {
            "summaryView" ->
                auditMask(
                    WalletSummaryView.of(
                        row.obj("summary"),
                        row.bool("masked"),
                        row.millis("nowIso"),
                    ),
                    row.bool("masked"),
                    label,
                )

            "amountInput" ->
                buildJsonObject {
                    val fen = WithdrawAmountInput.parse(row.text("text"))
                    put("fen", fen?.let(::JsonPrimitive) ?: JsonNull)
                }

            "amountDisplay" -> {
                val fen = row.integer("fen")
                val text = WithdrawAmountInput.display(fen)
                assertEquals(
                    "$label.roundTrip",
                    fen,
                    WithdrawAmountInput.parse(text),
                )
                buildJsonObject { put("text", text) }
            }

            "rulesView" ->
                WithdrawRulesView.of(
                    row.obj("rules"),
                    row.nullableInteger("inputFen"),
                    row.nullableText("slaText"),
                )

            "estimateGate" ->
                buildJsonObject {
                    put(
                        "apply",
                        WithdrawEstimateGate.shouldApply(
                            row.obj("request"),
                            row.obj("current"),
                        ),
                    )
                }

            "blockGuide" ->
                WithdrawBlockGuide.of(
                    row.nullableInteger("blockCode")?.toInt(),
                    row.nullableText("blockReason"),
                )

            "conditionText" -> WithdrawConditionText.of(row.nullableText("reason"))

            "outcome" ->
                SensitiveOutcome.of(
                    row.text("op"),
                    row.text("phase"),
                    row.bool("keyEverUnknown"),
                    row.obj("result"),
                )

            "stepUpToken" -> tokenResult(row, label)

            "withdrawalText" ->
                WithdrawalText.of(row.obj("item"), row.nullableText("slaText"))

            "tradeNo" -> TradeNoRow.of(row.obj("detail"))
            "payoutView" -> PayoutAccountView.of(row.obj("data"))
            "payoutPendingSummary" -> PayoutPendingSummary.of(row.obj("form"))
            "payoutForm" -> PayoutAccountForm.body(row.obj("form"))
            "realNameForm" -> RealNameForm.normalize(row.obj("input"))

            "earningsEntry" ->
                buildJsonObject {
                    put(
                        "show",
                        EarningsEntry.visible(
                            row.nullableBool("flag"),
                            row.nullableText("routeSinceForThisPlatform"),
                            row.text("appVersion"),
                        ),
                    )
                }

            "walletCache" ->
                buildJsonObject {
                    put("cacheable", WalletStaleCache.isCacheable(row.text("kind")))
                }

            "walletCacheClear" ->
                buildJsonObject {
                    put("clear", WalletStaleCache.clearsOn(row.text("event")))
                }

            "routeParams" ->
                FundRouteParams.filter(row.text("route"), row.obj("params"))

            else -> error("未登记的测试组")
        }

    private fun tokenResult(row: JsonObject, label: String): JsonObject {
        val cache = StepUpTokenCache()
        val request = row.obj("request")
        val action = request.text("action")
        val account = request.text("account")
        val now = request.millis("nowIso")
        val token = "fixture-token"

        row.nullableObject("cached")?.let {
            cache.put(
                it.text("action"),
                it.text("account"),
                token,
                it.millis("expireAtIso"),
            )
        }

        if (row.containsKey("events")) {
            for (value in row.array("events")) {
                check(value.jsonPrimitive.isString) { "events 类型错误" }
                when (value.jsonPrimitive.content) {
                    "success" -> cache.onSuccess(action)
                    "session_changed" -> cache.onSessionChanged()
                    "got_10003" -> cache.on10003(action)
                    else -> error("未登记的 token 事件")
                }
            }
        }

        val actual = cache.take(action, account, now)
        if (actual != null) {
            assertTrue("$label.tokenIdentity", actual == token)
            assertTrue(
                "$label.takeDoesNotConsume",
                cache.take(action, account, now) == token,
            )
        }
        return buildJsonObject { put("reuse", actual != null) }
    }

    private fun auditMask(
        actual: JsonObject,
        masked: Boolean,
        label: String,
    ): JsonObject {
        var hasDigits = false
        val amountNodes =
            listOf(
                "hero", "negativeLine", "frozen", "withdrawn",
                "estimated", "receivedLine", "pausedLine",
            )

        for (key in amountNodes) {
            val raw = actual[key] ?: continue
            if (raw == JsonNull) continue
            check(raw is JsonObject) { "金额节点类型错误" }
            if (masked) {
                val text = raw.text("maskedText")
                assertTrue("$label.$key.maskedText", text == "****")
                hasDigits = hasDigits || Regex("[0-9０-９]").containsMatchIn(text)
            }
        }

        return buildJsonObject {
            actual.forEach { (key, value) -> put(key, value) }
            put("anyMaskedAmountShowsDigits", hasDigits)
        }
    }

    private fun match(
        actual: JsonElement?,
        expected: JsonElement,
        path: String,
        exact: Boolean = false,
    ) {
        when (expected) {
            JsonNull -> assertTrue("$path 应无值", actual == null || actual == JsonNull)

            is JsonObject -> {
                assertTrue("$path 应为对象", actual is JsonObject)
                val value = actual as JsonObject
                if (exact) {
                    assertTrue("$path 字段集合", value.keys == expected.keys)
                }
                expected.forEach { (key, child) ->
                    match(
                        value[key],
                        child,
                        "$path.$key",
                        exact || key in setOf("body", "openedParams", "values"),
                    )
                }
            }

            is JsonArray -> {
                assertTrue("$path 应为数组", actual is JsonArray)
                val value = actual as JsonArray
                assertEquals("$path.length", expected.size, value.size)
                expected.indices.forEach { index ->
                    match(value[index], expected[index], "$path[$index]", exact)
                }
            }

            else -> {
                // JsonPrimitive 相等区分字符串、布尔与数字；失败不打印敏感值。
                assertTrue("$path 值或类型不符", actual == expected)
            }
        }
    }
}

private fun JsonObject.obj(key: String): JsonObject {
    val value = getValue(key)
    check(value is JsonObject) { "$key 应为对象" }
    return value
}

private fun JsonObject.nullableObject(key: String): JsonObject? {
    val value = getValue(key)
    return if (value == JsonNull) null else obj(key)
}

private fun JsonObject.array(key: String): JsonArray {
    val value = getValue(key)
    check(value is JsonArray) { "$key 应为数组" }
    return value
}

private fun JsonObject.rows(key: String): List<JsonObject> =
    array(key).map {
        check(it is JsonObject) { "$key 元素应为对象" }
        it
    }

private fun JsonObject.text(key: String): String {
    val value = getValue(key).jsonPrimitive
    check(value.isString) { "$key 应为字符串" }
    return value.content
}

private fun JsonObject.nullableText(key: String): String? =
    if (getValue(key) == JsonNull) null else text(key)

private fun JsonObject.integer(key: String): Long {
    val value = getValue(key).jsonPrimitive
    check(!value.isString) { "$key 应为整数" }
    val number = value.long
    check(number in -9_007_199_254_740_991L..9_007_199_254_740_991L) {
        "$key 超出三端安全整数范围"
    }
    return number
}

private fun JsonObject.nullableInteger(key: String): Long? =
    if (getValue(key) == JsonNull) null else integer(key)

private fun JsonObject.bool(key: String): Boolean {
    val value = getValue(key).jsonPrimitive
    check(!value.isString) { "$key 应为布尔值" }
    return value.boolean
}

private fun JsonObject.nullableBool(key: String): Boolean? =
    if (getValue(key) == JsonNull) null else bool(key)

private fun JsonObject.millis(key: String): Long {
    val value = text(key)
    check(value.endsWith("+08:00")) { "$key 必须带 +08:00" }
    return OffsetDateTime.parse(value).toInstant().toEpochMilli()
}
