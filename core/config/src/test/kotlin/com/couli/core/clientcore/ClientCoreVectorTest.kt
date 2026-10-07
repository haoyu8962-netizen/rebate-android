/*
 * 实现前测试；只读会话未写文件、未编译或运行。
 *
 * 接线约定：
 * - 被测纯内核由 com.couli.core.clientcore 导出：
 *   DeviceIdPolicy、ConfigResolver、ConfigStore、ConfigReply、TrackerQueue、
 *   ClientEvents、PermissionRequester；上层模块可委托这些内核。
 *   不允许测试适配器另行实现业务判断或读取 expect。
 * - DeviceIdPolicy.evaluate(platform, rawId: String?, privacy)
 *   返回 register、reasonRaw；不锁死未定义的 reasonRaw。
 * - ConfigResolver 接收 current/lkg: String?、bundled: String，均为配置 data
 *   JSON。resolve(..., key) 返回值的 JSON 字符串；searchStatus(..., platformKey)
 *   返回状态字符串；linkPatterns(...) 返回完整 link_patterns JSON。
 *   配置片段用于内核测试，HTTP schema 校验另测。
 * - ConfigStore(readFile, writeFile, fetcher, clock, bundled)：
 *   readFile: () -> String?，writeFile: (String?) -> Unit，存储字节不透明；
 *   fetcher: (String?, (ConfigReply) -> Unit) -> Unit，第一个参数是实际
 *   If-None-Match；clock: () -> Long，毫秒。
 *   ConfigReply(kindRaw, bodyJson, etag)，kindRaw=ok/not_modified/failure；
 *   onColdStart() 同步读盘并开始请求，onForeground() 开始请求；
 *   snapshotJson、sourceRaw 暴露当前 data 与 current/lkg/bundled 来源。
 *   测试回调串行、同步注入，不启动真实网络。
 * - TrackerQueue(readFile, writeFile, sink, privacy, userId, deviceId, platform,
 *   appVersion, sessionId, clock, canUpload)；
 *   sink: (List<String>) -> Boolean，接收真实上传事件 JSON，true=全部成功；
 *   userId: () -> String?；track(event): Boolean 表示接受；
 *   flush()、onPrivacyChanged(state)、queueSize。
 *   ClientEvents.appLaunch(page) 必须来自 events.yaml 的生成构造器。
 * - PermissionRequester(readFile, writeFile, clock, platform, status,
 *   showRationale, systemRequest, showSettingsGuide)：
 *   status: (String) -> String，未申请为 not_asked；
 *   showRationale: suspend (String, String?) -> Unit，参数为用途键、继续按钮键；
 *   Android 继续按钮键为空，回调完成表示顶部说明已展示；
 *   systemRequest: suspend (String) -> String；
 *   showSettingsGuide: (String?, String) -> Unit，参数为提示键、去设置按钮键；
 *   suspend request(permission) 返回含 resultRaw 的结果。
 *   systemResult 预置下一次系统申请结果；advance 后重建 Requester，
 *   复用存储、时钟及系统状态，验证节流持久化。
 * - 使用仓库已有 JUnit4、kotlinx.serialization.json、kotlinx.coroutines。
 *   不用 Robolectric、Thread.sleep、真实网络、权限框或文件写入。
 * - DeviceRegistry 的真实来源读取、无效值不哈希/不上报、安全存储与清理
 *   由实现任务集成测试补齐。完整 device_hash 测试直接读取锁定快照
 *   contracts/snapshot/specs/device-hash.vectors.json，不复制预期哈希。
 *   清缓存、环境切换与计时批量上报的宿主接线也需实现任务验证。
 */

package com.couli.core.clientcore

import java.io.File
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientCoreVectorTest {
    private val platform = "android"
    private val deviceId = "0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a60"
    private val bundled =
        """{"dict_version":"bundled","link_patterns":{"version":"bundled-patterns","rules":[]}}"""

    private class FileBox {
        var value: String? = null

        fun read(): String? = value

        fun write(value: String?) {
            this.value = value
        }
    }

    private class Clock {
        var now: Long = 1_000_000L
    }

    private class FetchProbe {
        val sent = mutableListOf<String?>()
        var pending: ((ConfigReply) -> Unit)? = null

        fun fetch(etag: String?, completion: (ConfigReply) -> Unit) {
            check(pending == null) { "测试只提供一个在途请求" }
            sent += etag
            pending = completion
        }

        fun complete(reply: ConfigReply) {
            val completion = checkNotNull(pending) { "没有等待中的 fetch" }
            pending = null
            completion(reply)
        }
    }

    private class TrackerProbe {
        var online = true
        val attempted = mutableListOf<String>()
        val uploaded = mutableListOf<String>()

        fun send(events: List<String>): Boolean {
            attempted += events
            if (online) {
                uploaded += events
            }
            return online
        }
    }

    private class TrackerInputs(
        var privacy: String,
        var loggedIn: Boolean,
    ) {
        var canUpload = true
    }

    private data class RationaleCall(
        val purpose: String,
        val continueKey: String?,
    )

    private data class SettingsCall(
        val message: String?,
        val button: String,
    )

    private class PermissionProbe {
        val current = mutableMapOf<String, String>()
        var nextResult: String? = null
        val trace = mutableListOf<String>()
        val rationales = mutableListOf<RationaleCall>()
        val guides = mutableListOf<SettingsCall>()

        fun resetCalls() {
            trace.clear()
            rationales.clear()
            guides.clear()
        }

        fun status(permission: String): String {
            trace += "status"
            return current[permission] ?: "not_asked"
        }

        suspend fun rationale(purpose: String, continueKey: String?) {
            trace += "rationale"
            rationales += RationaleCall(purpose, continueKey)
        }

        suspend fun systemRequest(permission: String): String {
            trace += "system"
            val result = nextResult ?: "unexpected_unconfigured_system_call"
            nextResult = null
            current[permission] = result
            return result
        }

        fun settings(message: String?, button: String) {
            trace += "settings"
            guides += SettingsCall(message, button)
        }
    }

    private val vectors: JsonObject by lazy {
        val start =
            File(
                System.getProperty("user.dir")
                    ?: throw AssertionError("缺少 user.dir"),
            ).canonicalFile
        val root =
            generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "contract.lock").isFile }
                ?: throw AssertionError("从 $start 向上找不到 contract.lock")
        val file = File(root, "specs/client-core-vectors.json")
        Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject.also { data ->
            assertEquals(file.path, 1, data.getValue("version").jsonPrimitive.int)
            assertTrue(file.path, data.getValue("sources").jsonArray.isNotEmpty())
            assertTrue(file.path, data.getValue("gaps").jsonArray.isNotEmpty())
            for (group in listOf("device", "config", "configFetch", "tracker", "permission")) {
                val rows = data.rows(group)
                assertTrue(group, rows.isNotEmpty())
                val names = rows.map { it.text("name") }
                assertEquals("$group 名称重复", names.size, names.toSet().size)
                for (row in rows) {
                    assertTrue(group, row.text("name").isNotBlank())
                    assertTrue(group, row.text("note").isNotBlank())
                }
            }
        }
    }

    @Test
    fun deviceVectors() {
        for (row in vectors.rows("device")) {
            val actual =
                DeviceIdPolicy.evaluate(
                    platform = row.text("platform"),
                    rawId = row.optionalText("rawId"),
                    privacy = row.text("privacy"),
                )
            assertEquals(
                row.text("name"),
                row.objectAt("expect").flag("register"),
                actual.register,
            )
        }
    }

    @Test
    fun configResolverVectors() {
        for (row in vectors.rows("config")) {
            val label = row.text("name")
            val expected = row.objectAt("expect")
            val current = row.document("current")
            val lkg = row.document("lkg")
            val fallback = checkNotNull(row.document("bundled"))

            when {
                "value" in expected -> {
                    val actual =
                        ConfigResolver.resolve(
                            current = current,
                            lkg = lkg,
                            bundled = fallback,
                            key = "dict_version",
                        )
                    assertJson(label, expected.getValue("value").toString(), actual)
                }

                "searchStatus" in expected -> {
                    assertEquals(
                        label,
                        expected.text("searchStatus"),
                        ConfigResolver.searchStatus(
                            current = current,
                            lkg = lkg,
                            bundled = fallback,
                            platformKey = row.text("platformKey"),
                        ),
                    )
                }

                "linkPatternsVersion" in expected -> {
                    val actual =
                        checkNotNull(
                            ConfigResolver.linkPatterns(
                                current = current,
                                lkg = lkg,
                                bundled = fallback,
                            ),
                        ) { label }
                    val patterns = Json.parseToJsonElement(actual).jsonObject
                    assertEquals(label, expected.text("linkPatternsVersion"), patterns.text("version"))
                    patterns.getValue("rules").jsonArray
                }

                else -> throw AssertionError("$label：未知配置断言")
            }
        }
    }

    @Test
    fun configFetchVectors() {
        for (row in vectors.rows("configFetch")) {
            val label = row.text("name")
            val file = FileBox()
            val clock = Clock()
            val fetch = FetchProbe()

            fun makeStore(): ConfigStore =
                ConfigStore(
                    readFile = file::read,
                    writeFile = file::write,
                    fetcher = fetch::fetch,
                    clock = { clock.now },
                    bundled = bundled,
                )

            var store = makeStore()
            var lastSuccess: String? = null
            var successCount = 0

            for (step in row.rows("steps")) {
                when (val op = step.text("op")) {
                    "coldStart" -> {
                        assertNull(label, fetch.pending)
                        store = makeStore()
                        val before = fetch.sent.size
                        store.onColdStart()
                        assertEquals(label, before + 1, fetch.sent.size)
                        assertNotNull(label, fetch.pending)
                        assertJson(label, lastSuccess ?: bundled, store.snapshotJson)
                    }

                    "foreground" -> {
                        val before = fetch.sent.size
                        store.onForeground()
                        assertEquals(label, before + 1, fetch.sent.size)
                        assertNotNull(label, fetch.pending)
                    }

                    "fetchOk" -> {
                        successCount += 1
                        val body =
                            """{"dict_version":"network-$successCount","link_patterns":{"version":"patterns-$successCount","rules":[]}}"""
                        fetch.complete(
                            ConfigReply(
                                kindRaw = "ok",
                                bodyJson = body,
                                etag = step.optionalText("etag"),
                            ),
                        )
                        lastSuccess = body
                        assertNotNull("$label：成功响应必须持久化", file.value)
                        assertJson(label, body, store.snapshotJson)
                    }

                    "fetch304", "fetchFail" -> {
                        val before = store.snapshotJson
                        fetch.complete(
                            ConfigReply(
                                kindRaw = if (op == "fetch304") "not_modified" else "failure",
                                bodyJson = null,
                                etag = step.optionalText("etag"),
                            ),
                        )
                        assertJson(label, before, store.snapshotJson)
                    }

                    else -> throw AssertionError("$label：未知 op $op")
                }
            }

            val expected = row.objectAt("expect")
            assertEquals(label, expected.text("source"), store.sourceRaw)
            if ("sentIfNoneMatch" in expected) {
                assertTrue(label, fetch.sent.isNotEmpty())
                assertEquals(label, expected.optionalText("sentIfNoneMatch"), fetch.sent.last())
            }
        }
    }

    @Test
    fun trackerVectors() {
        for (row in vectors.rows("tracker")) {
            val label = row.text("name")
            val file = FileBox()
            val clock = Clock()
            val sink = TrackerProbe()
            val inputs = TrackerInputs(row.text("privacy"), row.flag("loggedIn"))

            fun makeQueue(): TrackerQueue =
                TrackerQueue(
                    readFile = file::read,
                    writeFile = file::write,
                    sink = sink::send,
                    privacy = inputs.privacy,
                    userId = { if (inputs.loggedIn) "user-test" else null },
                    deviceId = deviceId,
                    platform = platform,
                    appVersion = "1.0.0",
                    sessionId = "session-test",
                    clock = { clock.now },
                    canUpload = { inputs.canUpload },
                )

            var queue = makeQueue()
            var collected = 0

            for (step in row.rows("steps")) {
                when (val op = step.text("op")) {
                    "track" ->
                        repeat(step.getValue("count").jsonPrimitive.int) {
                            if (queue.track(ClientEvents.appLaunch(page = "Home"))) {
                                collected += 1
                            }
                            assertTrue("$label：队列不得为负", queue.queueSize >= 0)
                            assertTrue("$label：队列最多 500 条", queue.queueSize <= 500)
                        }

                    "flush" -> queue.flush()
                    "privacy" -> {
                        inputs.privacy = step.text("state")
                        queue.onPrivacyChanged(inputs.privacy)
                        if (inputs.privacy != "consented") {
                            assertEquals("$label：撤回立即清队列", 0, queue.queueSize)
                        }
                    }

                    "restart" -> queue = makeQueue()
                    "sinkFail" -> sink.online = false
                    "sinkOk" -> sink.online = true
                    "login" -> inputs.loggedIn = true
                    "uploadGate" -> inputs.canUpload = step.flag("allowed")
                    else -> throw AssertionError("$label：未知 op $op")
                }
            }

            val expected = row.objectAt("expect")
            assertCount(label, "collected", collected, expected)
            assertCount(label, "uploaded", sink.uploaded.size, expected)
            assertCount(label, "queueSize", queue.queueSize, expected)

            if (row.text("privacy") != "consented" && collected == 0) {
                assertTrue("$label：不得调用 sink", sink.attempted.isEmpty())
            }
            if (!inputs.canUpload) {
                assertTrue("$label：未知版本只入队", sink.attempted.isEmpty())
            }

            for (packet in sink.attempted) {
                assertCommonFields(label, packet)
            }
            if ("hasUserId" in expected) {
                assertTrue("$label：身份断言不能空跑", sink.uploaded.isNotEmpty())
                for (packet in sink.uploaded) {
                    val event = Json.parseToJsonElement(packet).jsonObject
                    assertEquals(
                        label,
                        expected.flag("hasUserId"),
                        !event.optionalText("user_id").isNullOrEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun permissionVectors() =
        runBlocking {
            for (row in vectors.rows("permission")) {
                val label = row.text("name")
                val permission = row.text("permission")
                val file = FileBox()
                val clock = Clock()
                val system = PermissionProbe()

                fun makeRequester(): PermissionRequester =
                    PermissionRequester(
                        readFile = file::read,
                        writeFile = file::write,
                        clock = { clock.now },
                        platform = platform,
                        status = system::status,
                        showRationale = system::rationale,
                        systemRequest = system::systemRequest,
                        showSettingsGuide = system::settings,
                    )

                var requester = makeRequester()
                var resultRaw: String? = null

                for (step in row.rows("steps")) {
                    when (val op = step.text("op")) {
                        "systemResult" -> system.nextResult = step.text("result")
                        "advance" -> {
                            clock.now += step.getValue("millis").jsonPrimitive.long
                            requester = makeRequester()
                        }

                        "request" -> {
                            val stateBefore = system.current[permission] ?: "not_asked"
                            system.resetCalls()
                            resultRaw = requester.request(permission).resultRaw

                            assertEquals(label, "status", system.trace.firstOrNull())
                            val systemCalls = system.trace.count { it == "system" }
                            assertTrue(label, systemCalls <= 1)
                            val systemIndex = system.trace.indexOf("system")
                            if (systemIndex >= 0) {
                                val rationaleIndex = system.trace.indexOf("rationale")
                                assertTrue(
                                    "$label：系统申请前必须先说明",
                                    rationaleIndex >= 0 && rationaleIndex < systemIndex,
                                )
                            }
                            if (stateBefore == "granted" || stateBefore == "permanently_denied") {
                                assertEquals(label, 0, systemCalls)
                                assertTrue(label, system.rationales.isEmpty())
                            }

                            val purpose =
                                if (permission == "push") {
                                    "perm.push.card_hint"
                                } else {
                                    "perm.photos.purpose"
                                }
                            for (call in system.rationales) {
                                assertEquals(label, purpose, call.purpose)
                                assertNull("$label：Android 用顶部说明浮层", call.continueKey)
                            }
                            for (call in system.guides) {
                                assertEquals(label, "perm.go_settings", call.button)
                                if (resultRaw == "permanently_denied") {
                                    val key =
                                        if (permission == "push") {
                                            "perm.push.denied_forever"
                                        } else {
                                            "perm.photos.denied_forever"
                                        }
                                    assertEquals(label, key, call.message)
                                }
                            }
                            if (resultRaw == "throttled") {
                                assertEquals(label, 0, systemCalls)
                                assertTrue(label, system.rationales.isEmpty())
                                assertFalse(label, system.guides.isEmpty())
                            }
                        }

                        else -> throw AssertionError("$label：未知 op $op")
                    }
                }

                val expected = row.objectAt("expect")
                assertEquals(label, expected.text("resultRaw"), resultRaw)
                assertEquals(label, expected.flag("showedRationale"), system.rationales.isNotEmpty())
                assertEquals(label, expected.flag("openSettingsGuide"), system.guides.isNotEmpty())
            }
        }

    private fun assertCommonFields(label: String, packet: String) {
        val event = Json.parseToJsonElement(packet).jsonObject
        for (
            key in listOf(
                "event_id", "name", "client_at", "session_id",
                "device_id", "platform", "app_version", "page",
            )
        ) {
            val value = event.getValue(key).jsonPrimitive
            assertTrue("$label：$key", value.isString && value.content.isNotEmpty())
        }
        assertEquals(label, "app_launch", event.text("name"))
        assertEquals(label, deviceId, event.text("device_id"))
        assertEquals(label, platform, event.text("platform"))
        assertEquals(label, "session-test", event.text("session_id"))
        assertEquals(label, "1.0.0", event.text("app_version"))
        assertEquals(label, "Home", event.text("page"))
        OffsetDateTime.parse(event.text("client_at"))
    }

    private fun assertCount(
        label: String,
        key: String,
        actual: Int,
        expected: JsonObject,
    ) {
        val value = expected.getValue(key).jsonPrimitive.int
        val bounds = expected["upperBounds"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        assertTrue(label, actual >= 0)
        if (key in bounds) {
            assertTrue("$label：$key 上界 $value，实际 $actual", actual <= value)
        } else {
            assertEquals("$label：$key", value, actual)
        }
    }

    private fun assertJson(label: String, expected: String, actual: String?) {
        assertNotNull(label, actual)
        assertEquals(
            label,
            Json.parseToJsonElement(expected),
            Json.parseToJsonElement(checkNotNull(actual)),
        )
    }
}

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.optionalText(key: String): String? {
    val value: JsonElement = get(key) ?: return null
    return if (value == JsonNull) null else value.jsonPrimitive.content
}

private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean

private fun JsonObject.objectAt(key: String): JsonObject = getValue(key).jsonObject

private fun JsonObject.rows(key: String): List<JsonObject> =
    getValue(key).jsonArray.map { it.jsonObject }

private fun JsonObject.document(key: String): String? =
    get(key)?.takeUnless { it == JsonNull }?.toString()
