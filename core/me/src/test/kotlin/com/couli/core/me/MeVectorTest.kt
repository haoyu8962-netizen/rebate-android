/*
 * 实现前测试；只读输出，未编译运行。
 *
 * 接线约定：
 * - 生产类型位于 com.couli.core.me；PushReportPolicy 的纯策略也从该包提供。
 *   模块使用已有 JUnit4、kotlinx.serialization.json，并给测试接上已有
 *   :core:designsystem；不新增第三方库。
 * - JSON 边界为 JsonObject/JsonArray/JsonNull。生产强类型模型可提供同名
 *   JSON 重载，只转换数据，不在适配器重写业务判定。
 * - MeJson.parse(JsonObject): JsonObject?，成功保留契约 13 个 snake_case 字段。
 * - 以下返回 JsonObject，参数顺序与下方调用一致：
 *   MeView.of(level, me, masked, availableRoutes, earningsFlag)
 *   SettingsItems.of(level, platform, phoneBound, nicknameEditable,
 *                    serviceSwitchAvailable, deleteAccountAvailable)
 *   PrivacyCenterView.of(level, platform, permissionEntries, permissionStatus)
 *   NicknameInput.check(current, input)；NicknameOutcome.of(result)
 *   RiskNoticeView.of(risk, enteredBy10006, latestAccountAppeal, nowMs)
 *   AppealView.of(appeals, frozenLayout)；AppealForm.check(content)
 *   AppealOutcome.of(result)；CsEntryDecision.of(kfUrl, wechat, openResult)
 *   AboutView.of(filingText)；PrivacyWithdrawFlow.after(result)。
 * - NotifyGuideBar.visible(loggedIn, systemStatus, lastDeniedMs, lastClosedMs,
 *   nowMs): Boolean；RiskReasonText.key(category): String?。
 * - Personalization.consentBody(on, legalPrivacyVersion, nowMs, level): JsonObject?；
 *   Personalization.header(on, level): String?。
 * - PrivacyWithdrawFlow.request(deviceRegistered, consentedVersion, nowMs):
 *   JsonObject?；PendingWithdrawals.filter(List<JsonObject>): List<String>。
 * - PushReportPolicy()；on(event, state): JsonObject；inFlight/pending: Boolean。
 *   非响应 state 是补丁；响应 session 是原请求世代，不覆盖当前会话。
 *   退出/撤回后的实例终止，后续重新启用另建实例。
 * - CsLink.build(kfUrl, fromRoute, account): String；
 *   Diagnostics.lines(fields): List<String>；
 *   Route10006.decide(topRoute, forceUpdateShowing): String；
 *   MeLocalData.clearsOn(event, item): Boolean。
 * - nowMs 为 Long，夹具显式 +08:00；client_at 按同一时刻比较。
 *   昵称使用生产字素实现，不用 String.length 充当字符数。
 * - 金额渲染复用 CouliFormat.money；这里只断言整数分和隐藏串；
 *   非空恢复时间比较 CouliFormat.timeListFuture，不重复 format-vectors。
 * - 缺失期望字段不判定，显式 null 要求无值；数组长度一致。
 *   permissionRows 按 type 比较集合，其余业务数组比较顺序。
 * - 请求、params、宫格、设置分组、余额结构精确比较；申诉 body 只允许
 *   target_type/content。首尾空白用例不裁定最终 content 的值。
 * - 设置的 choices/serviceSwitchAvailable 是页内描述，不是接口字段；
 *   group/item/id/lists/buttons 是 DTO 标识，详见 vectors.gaps。
 *   openUrl 是按钮可打开的目标；不执行外跳。
 * - diagnostics.lines 按值标记、无序行和总行数断言，不猜英文字段名。
 * - 每端执行全部平台行；不用网络、SDK、系统权限、剪贴板、文件写入或 sleep。
 *   失败日志只含用例名/字段路径，不打印令牌、身份数据、诊断原文。
 * - 纯函数测试不能替代 vectors.gaps 的页面、执行层和 Release 制品验收。
 */

package com.couli.core.me

import com.couli.core.designsystem.CouliFormat
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

class MeVectorTest {
    private val groups =
        listOf(
            "meParse",
            "meView",
            "guideBar",
            "settingsItems",
            "privacyCenter",
            "personalization",
            "privacyWithdraw",
            "nickname",
            "nicknameOutcome",
            "riskNotice",
            "riskReason",
            "appealView",
            "appealSubmit",
            "appealOutcome",
            "pendingWithdrawals",
            "pushReport",
            "csEntry",
            "csLink",
            "diagnostics",
            "about",
            "route10006",
            "localDataClear",
        )

    private val root: File by lazy {
        val start =
            File(
                System.getProperty("user.dir") ?: throw AssertionError("找不到 user.dir"),
            ).canonicalFile
        generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "contract.lock").isFile }
            ?: throw AssertionError("找不到 contract.lock")
    }

    private fun read(relative: String): JsonObject = Json.parseToJsonElement(File(root, relative).readText(Charsets.UTF_8)).jsonObject

    @Test
    fun allMeVectors() {
        val vectors = read("specs/me-vectors.json")
        val permissions =
            read("contracts/snapshot/specs/system-permissions.json").rows("permissions")
        val routes = read("contracts/snapshot/contracts/routes.json").getValue("routes").jsonObject

        check(vectors.number("version") == 1, "manifest.version")
        check(vectors.getValue("sources").jsonArray.isNotEmpty(), "manifest.sources")
        check(vectors.getValue("gaps").jsonArray.isNotEmpty(), "manifest.gaps")
        check(
            // APP-12v：只新增的截图报文组由 MeVisualFixtureTest 校验；原业务组及断言保持。
            vectors.keys - "visualMe" == (groups + listOf("version", "sources", "gaps")).toSet(),
            "manifest.groups",
        )

        for (group in groups) {
            val cases = vectors.rows(group)
            check(cases.isNotEmpty(), "$group.empty")
            check(cases.map { it.text("name") }.toSet().size == cases.size, "$group.duplicate")
            for (row in cases) {
                val label = "$group/${row.text("name")}"
                when (group) {
                    "pushReport" -> {
                        runPush(row, label)
                    }

                    "diagnostics" -> {
                        runDiagnostics(row, label)
                    }

                    else -> {
                        val expected = row.getValue("expect").jsonObject
                        val actual = evaluate(group, row, permissions, label)
                        match(actual, expected, label)
                        checkRoutes(actual, routes, label)

                        if (group == "meView") {
                            check(
                                actual.keys.all {
                                    it in
                                        setOf(
                                            "loginPrompt",
                                            "inviteCodeBlock",
                                            "inviterTextKey",
                                            "balance",
                                            "bell",
                                            "grid",
                                        )
                                },
                                "$label.noExtraProfileOrLevel",
                            )
                        }
                        if (group == "appealSubmit" && expected.flag("enabled")) {
                            val body = actual.getValue("body").jsonObject
                            check(body.keys == setOf("target_type", "content"), "$label.body.keys")
                            check(body.getValue("content").jsonPrimitive.isString, "$label.body.content.type")
                        }
                        if (group == "riskNotice") {
                            val until = row.getValue("risk").jsonObject.optionalText("frozen_until")
                            if (until != null && expected.text("layoutRaw").startsWith("frozen")) {
                                match(
                                    actual["frozenUntilText"],
                                    JsonPrimitive(
                                        CouliFormat.timeListFuture(
                                            epochMillis = millis(until),
                                            nowEpochMillis = row.now(),
                                        ),
                                    ),
                                    "$label.frozenUntilText",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun evaluate(
        group: String,
        r: JsonObject,
        permissions: List<JsonObject>,
        label: String,
    ): JsonObject =
        when (group) {
            "meParse" -> {
                val wire = r.getValue("json").jsonObject
                val me = MeJson.parse(wire)
                if (me == null) {
                    buildJsonObject { put("ok", false) }
                } else {
                    match(me, wire, "$label.allFields", exact = true)
                    val balance = me.getValue("balance").jsonObject
                    val risk = me.getValue("risk").jsonObject
                    buildJsonObject {
                        put("ok", true)
                        put("nickname", me.getValue("nickname"))
                        put("inviteCode", me.getValue("invite_code"))
                        put("inviterBound", me.getValue("inviter_bound"))
                        put("withdrawableFen", balance.getValue("withdrawable_fen"))
                        put("estimatedTotalFen", balance.getValue("estimated_total_fen"))
                        put("riskStateRaw", risk.getValue("state"))
                        put("reasonCategory", risk.getValue("reason_category"))
                        put("frozenUntil", risk.getValue("frozen_until"))
                    }
                }
            }

            "meView" -> {
                val wire = r.optionalObject("me")
                val me = wire?.let { MeJson.parse(it) }
                check(wire == null || me != null, "$label.validMe")
                MeView.of(
                    r.text("levelRaw"),
                    me,
                    r.flag("masked"),
                    r.strings("availableRoutes"),
                    r.optionalFlag("earningsFlag"),
                )
            }

            "guideBar" -> {
                buildJsonObject {
                    put(
                        "show",
                        NotifyGuideBar.visible(
                            r.flag("loggedIn"),
                            r.text("systemRaw"),
                            r.optionalText("lastDeniedIso")?.let(::millis),
                            r.optionalText("lastClosedIso")?.let(::millis),
                            r.now(),
                        ),
                    )
                }
            }

            "settingsItems" -> {
                SettingsItems.of(
                    r.text("levelRaw"),
                    r.text("platformRaw"),
                    r.optionalFlag("phoneBound"),
                    r.flag("nicknameEditable"),
                    r.flag("serviceSwitchAvailable"),
                    r.flag("deleteAccountAvailable"),
                )
            }

            "privacyCenter" -> {
                PrivacyCenterView.of(
                    r.text("levelRaw"),
                    r.text("platformRaw"),
                    permissions,
                    r.getValue("permissionStatus").jsonObject,
                )
            }

            "personalization" -> {
                buildJsonObject {
                    put(
                        "consentBody",
                        Personalization.consentBody(
                            r.flag("on"),
                            r.number("legalPrivacyVersion"),
                            r.now(),
                            r.text("levelRaw"),
                        ) ?: JsonNull,
                    )
                    put(
                        "header",
                        Personalization
                            .header(r.flag("on"), r.text("levelRaw"))
                            ?.let(::JsonPrimitive) ?: JsonNull,
                    )
                }
            }

            "privacyWithdraw" -> {
                val after = PrivacyWithdrawFlow.after(r.getValue("result").jsonObject)
                JsonObject(
                    after + (
                        "request" to (
                            PrivacyWithdrawFlow.request(
                                r.flag("deviceRegistered"),
                                r.number("consentedVersion"),
                                r.now(),
                            ) ?: JsonNull
                        )
                    ),
                )
            }

            "nickname" -> {
                NicknameInput.check(r.text("current"), r.text("input"))
            }

            "nicknameOutcome" -> {
                NicknameOutcome.of(r.getValue("result").jsonObject)
            }

            "riskNotice" -> {
                RiskNoticeView.of(
                    r.getValue("risk").jsonObject,
                    r.flag("enteredBy10006"),
                    r.optionalObject("latestAccountAppeal"),
                    r.now(),
                )
            }

            "riskReason" -> {
                buildJsonObject {
                    put(
                        "key",
                        RiskReasonText
                            .key(r.optionalText("category"))
                            ?.let(::JsonPrimitive) ?: JsonNull,
                    )
                }
            }

            "appealView" -> {
                AppealView.of(r.rows("appeals"), r.flag("frozenLayout"))
            }

            "appealSubmit" -> {
                AppealForm.check(r.text("content"))
            }

            "appealOutcome" -> {
                AppealOutcome.of(r.getValue("result").jsonObject)
            }

            "pendingWithdrawals" -> {
                buildJsonObject {
                    put("shownIds", JsonArray(PendingWithdrawals.filter(r.rows("items")).map(::JsonPrimitive)))
                }
            }

            "csEntry" -> {
                CsEntryDecision.of(
                    r.optionalText("kfUrl"),
                    r.text("wechatRaw"),
                    r.optionalText("openResult"),
                )
            }

            "csLink" -> {
                buildJsonObject {
                    put(
                        "url",
                        CsLink.build(
                            r.text("kfUrl"),
                            r.text("fromRoute"),
                            r.getValue("account").jsonObject,
                        ),
                    )
                }
            }

            "about" -> {
                AboutView.of(r.optionalText("filingText"))
            }

            "route10006" -> {
                buildJsonObject {
                    put("actionRaw", Route10006.decide(r.text("topRoute"), r.flag("forceUpdateShowing")))
                }
            }

            "localDataClear" -> {
                buildJsonObject {
                    put("clear", MeLocalData.clearsOn(r.text("event"), r.text("item")))
                }
            }

            else -> {
                throw AssertionError("$label.unhandledGroup")
            }
        }

    private fun runPush(
        row: JsonObject,
        label: String,
    ) {
        val policy = PushReportPolicy()
        val steps = row.rows("steps")
        check(steps.isNotEmpty(), "$label.steps")
        steps.forEachIndexed { index, step ->
            val decision =
                policy.on(
                    step.text("event"),
                    step.optionalObject("state") ?: JsonObject(emptyMap()),
                )
            val actual =
                JsonObject(
                    decision +
                        mapOf(
                            "inFlight" to JsonPrimitive(policy.inFlight),
                            "pending" to JsonPrimitive(policy.pending),
                        ),
                )
            match(actual, step.getValue("expect"), "$label[$index]")
        }
    }

    private fun runDiagnostics(
        row: JsonObject,
        label: String,
    ) {
        val actual = Diagnostics.lines(row.getValue("fields").jsonObject)
        val expected = row.getValue("expect").jsonObject
        val markers = expected.strings("lines")
        check(actual.size == markers.size, "$label.lineCount")
        check(
            actual.all { it.isNotEmpty() && '\n' !in it && '\r' !in it },
            "$label.oneItemPerLine",
        )
        val used = mutableSetOf<Int>()
        markers.forEachIndexed { index, marker ->
            val pieces = marker.split('|')
            val found =
                actual.indices.firstOrNull { candidate ->
                    candidate !in used && pieces.all { actual[candidate].contains(it) }
                }
            check(found != null, "$label.valueGroup[$index]")
            if (found != null) used += found
        }
        val joined = actual.joinToString("\n")
        expected.strings("forbiddenAbsent").forEachIndexed { index, forbidden ->
            check(!joined.contains(forbidden), "$label.forbidden[$index]")
        }
    }

    private fun match(
        actual: JsonElement?,
        expected: JsonElement,
        path: String,
        exact: Boolean = false,
    ) {
        when (expected) {
            JsonNull -> {
                check(actual == null || actual == JsonNull, "$path.null")
            }

            is JsonObject -> {
                check(actual is JsonObject, "$path.object")
                if (actual !is JsonObject) return
                if (exact) check(actual.keys == expected.keys, "$path.keys")
                val exactChildren =
                    setOf(
                        "request",
                        "consentBody",
                        "params",
                        "grid",
                        "groups",
                        "balance",
                        "bell",
                        "inviteCodeBlock",
                        "permissionRows",
                    )
                for ((key, value) in expected) {
                    match(actual[key], value, "$path.$key", exact || key in exactChildren)
                }
            }

            is JsonArray -> {
                check(actual is JsonArray, "$path.array")
                if (actual !is JsonArray) return
                val a =
                    if (path.endsWith(".permissionRows")) {
                        actual.sortedBy { it.jsonObject.text("type") }
                    } else {
                        actual.toList()
                    }
                val e =
                    if (path.endsWith(".permissionRows")) {
                        expected.sortedBy { it.jsonObject.text("type") }
                    } else {
                        expected.toList()
                    }
                check(a.size == e.size, "$path.count")
                if (a.size != e.size) return
                e.indices.forEach { match(a[it], e[it], "$path[$it]", exact) }
            }

            else -> {
                if (path.endsWith(".client_at")) {
                    check(actual is JsonPrimitive && actual.isString, "$path.type")
                    if (actual is JsonPrimitive && actual.isString) {
                        check(
                            millis(actual.content) == millis(expected.jsonPrimitive.content),
                            "$path.instant",
                        )
                    }
                } else {
                    check(actual == expected, "$path.value")
                }
            }
        }
    }

    private fun checkRoutes(
        value: JsonElement,
        routes: JsonObject,
        path: String,
    ) {
        when (value) {
            is JsonObject -> {
                value.forEach { (key, child) ->
                    if (key in setOf("route", "withdrawRoute") && child != JsonNull) {
                        check(child is JsonPrimitive && child.isString, "$path.$key.type")
                        if (child is JsonPrimitive && child.isString) {
                            check(routes.containsKey(child.content), "$path.$key.registered")
                        }
                    }
                    checkRoutes(child, routes, "$path.$key")
                }
            }

            is JsonArray -> {
                value.forEachIndexed { index, child ->
                    checkRoutes(child, routes, "$path[$index]")
                }
            }

            else -> {
                Unit
            }
        }
    }

    private fun check(
        condition: Boolean,
        path: String,
    ) = assertTrue(path, condition)

    private fun JsonObject.text(key: String): String {
        val value = getValue(key).jsonPrimitive
        if (!value.isString) throw AssertionError("$key.string")
        return value.content
    }

    private fun JsonObject.optionalText(key: String): String? =
        this[key]?.takeUnless { it == JsonNull }?.let {
            if (it !is JsonPrimitive || !it.isString) throw AssertionError("$key.string")
            it.contentOrNull
        }

    private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.optionalFlag(key: String): Boolean? = this[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.boolean

    private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.rows(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

    private fun JsonObject.strings(key: String): List<String> =
        getValue(key).jsonArray.map {
            if (!it.jsonPrimitive.isString) throw AssertionError("$key.strings")
            it.jsonPrimitive.content
        }

    private fun JsonObject.optionalObject(key: String): JsonObject? = this[key]?.takeUnless { it == JsonNull }?.jsonObject

    private fun millis(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun JsonObject.now(): Long {
        val iso = text("nowIso")
        check(iso.endsWith("+08:00"), "nowIso.timezone")
        return millis(iso)
    }
}
