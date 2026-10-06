// 实现前接线约定：
// - JUnit4 + kotlinx.serialization.json；无需 Android API、MockWebServer 或真实网络。
// - core:network 的测试依赖 core:generated；ApiOperations.byId(id) 返回 ApiOperation?。
// - NetworkRequestState / ErrorActionState / ErrorActionInput 参数见调用；data 为 JsonObject，
//   headers 为 Map<String, String>；状态使用 JSON 中的 raw 字符串。
// - RetryPolicy.forOperation(operation) 返回 autoRetries:Int、backoffMillis/timeoutMillis:Long；
//   ErrorEffect.disableMillis 为 Long?，其余输出字段名与任务指定一致。
// - RefreshRequest(id, operation, key, body)，body:String 保留原始字节。
//   RefreshCoordinator 的 refresh: ((String?) -> Unit) -> Unit，null=失败，非 null=新 token；
//   replay: (RefreshRequest, String) -> Unit；clearSession: () -> Unit；clock: () -> Long。
//   run(requests, forceUpdate) 非阻塞接纳已收到 10002 的请求，回调串行交付；
//   暴露 refreshCalls:Int、replayed:Int、effectRaw:String。
//   假刷新延迟完成，使多个 run 真正共享同一未完成窗口，而非顺序完成后计数。
// - MemoryPendingKeyBackend.read(account, key) 返回含 key/operationId/body/account 的可选记录。
//   PendingKeyStore(backend, clock)；beforeSend(...)、confirm(key)、abandon(key) 返回 Boolean。
//   confirm/abandon 仅开始用户操作；abandon 不能当作本地删除。
//   onResponse(key, kind) 用 raw 字符串；abandoned 表示已解包确认 outcome=abandoned。
//   coldStart(account) 只恢复该账号，不发送；遗留 in_flight 变 unknown。
//   state(key).stateRaw=none/in_flight/unknown/done；终结后后端记录实际删除。
// - JSON 中可选期望缺省不限定该字段，显式 null 要求为空。
// - Hypium、Swift 同样展开六种 consent_type、三个鉴权前置码和三个普通 2xxxx。
// - 这些策略测试不证明 ApiClient 已接线；实际 HTTP 测试应注入假传输。
//   实现前只读产出，尚未接线编译执行。

package com.couli.core.network

import com.couli.core.generated.ApiOperation
import com.couli.core.generated.ApiOperations
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

private class VectorClock {
    var nowMillis = 0L
}

private class FakeRefresh {
    var calls = 0
        private set
    private val completions = mutableListOf<(String?) -> Unit>()

    fun start(completion: (String?) -> Unit) {
        calls += 1
        completions += completion
    }

    fun finish(token: String?) {
        val waiting = completions.toList()
        completions.clear()
        waiting.forEach { it(token) }
    }
}

class NetworkVectorTest {
    private val vectors: JsonObject by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        val root =
            generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "contract.lock").isFile }
                ?: error("Cannot find contract.lock above $start")
        Json.parseToJsonElement(
            File(root, "specs/network-vectors.json").readText(Charsets.UTF_8),
        ).jsonObject.also {
            assertEquals(1, it.int("version"))
            assertTrue(it.getValue("sources").jsonArray.isNotEmpty())
        }
    }

    @Test
    fun headers() {
        each("headers") { row, label ->
            val state = row.obj("state")
            val expected = row.obj("expect")
            val actual =
                RequestHeaderPolicy.decide(
                    NetworkRequestState(
                        privacy = state.string("privacy"),
                        sessionScope = state.string("sessionScope"),
                        hasAccessToken = state.bool("hasAccessToken"),
                        deviceId = state.nullableString("deviceId"),
                        environmentSettled = state.bool("environmentSettled"),
                        credentialsReadable = state.bool("credentialsReadable"),
                    ),
                    operation(row),
                )
            assertEquals(label, expected.bool("send"), actual.send)
            if ("localRejectReason" in expected) {
                assertEquals(label, expected.string("localRejectReason"), actual.localRejectReason)
            }
            val names = actual.headerNames.map { it.lowercase() }.toSet()
            expected.getValue("include").jsonArray.forEach {
                assertTrue("$label: missing $it", it.jsonPrimitive.content.lowercase() in names)
            }
            expected.getValue("exclude").jsonArray.forEach {
                assertFalse("$label: leaked $it", it.jsonPrimitive.content.lowercase() in names)
            }
        }
    }

    @Test
    fun retryPolicies() {
        each("retry") { row, label ->
            assertTrue(label, row.string("failure") in listOf("timeout", "disconnect", "http5xx"))
            val expected = row.obj("expect")
            val actual = RetryPolicy.forOperation(operation(row))
            assertEquals(label, expected.int("autoRetries"), actual.autoRetries)
            assertEquals(label, expected.long("timeoutMillis"), actual.timeoutMillis)
            if ("backoffMillis" in expected) {
                assertEquals(label, expected.long("backoffMillis"), actual.backoffMillis)
            }
        }
    }

    @Test
    fun errorActions() {
        val kinds = vectors.obj("effectKinds")
        each("errorAction") { row, baseLabel ->
            val expected = row.obj("expect")
            val kind = expected.string("effectKind")
            assertTrue("$baseLabel: undefined effect", kind in kinds)
            val variants =
                if (row.int("code") == 10004 && kind == "guide_only") {
                    listOf(
                        "privacy", "agreement", "ai_third_party",
                        "id_verification", "personalization", "labor_agreement",
                    )
                } else {
                    listOf("")
                }
            variants.forEach { consent ->
                val label = "$baseLabel $consent"
                val originalData = row.obj("data")
                val data =
                    if (consent.isEmpty()) {
                        originalData
                    } else {
                        JsonObject(originalData + ("consent_type" to JsonPrimitive(consent)))
                    }
                val state = row.obj("state")
                val actual =
                    ErrorActionMapper.map(
                        ErrorActionInput(
                            code = row.int("code"),
                            data = data,
                            operation = operation(row),
                            headers = row.obj("headers").mapValues { it.value.jsonPrimitive.content },
                            isReplay = row.bool("isReplay"),
                            state =
                                ErrorActionState(
                                    update = state.string("update"),
                                    sessionScope = state.string("sessionScope"),
                                    appVersion = state.string("appVersion"),
                                ),
                        ),
                    )
                assertEquals(label, kind, actual.effectKindRaw)
                assertEquals(label, expected.string("replay"), actual.replayRaw)
                assertEquals(label, expected.bool("registerPending"), actual.registerPending)
                if ("route" in expected) {
                    assertEquals(label, expected.nullableString("route"), actual.route)
                }
                if ("textKey" in expected) {
                    assertEquals(label, expected.nullableString("textKey"), actual.textKey)
                }
                if ("disableMillis" in expected) {
                    assertEquals(label, expected.long("disableMillis"), actual.disableMillis)
                }
                if ("trace6" in expected) {
                    assertEquals(label, expected.bool("trace6"), actual.includeTrace6)
                }
            }
        }
    }

    @Test
    fun refreshCoordinator() {
        each("refresh") { row, label ->
            val expected = row.obj("expect")
            val fake = FakeRefresh()
            val clock = VectorClock()
            val replayed = mutableListOf<RefreshRequest>()
            val replayTokens = mutableListOf<String>()
            var clearCalls = 0
            val coordinator =
                RefreshCoordinator(
                    refresh = { completion -> fake.start(completion) },
                    replay = { request, token ->
                        replayed += request
                        replayTokens += token
                    },
                    clearSession = { clearCalls += 1 },
                    clock = { clock.nowMillis },
                )
            val operation = checkNotNull(ApiOperations.byId("openLink"))
            val requests =
                List(row.int("concurrent")) { index ->
                    RefreshRequest(
                        id = "request-$index",
                        operation = operation,
                        key = "key-$index",
                        body = "{\"fixture\":$index}",
                    )
                }
            requests.forEach {
                coordinator.run(listOf(it), row.bool("forceUpdate"))
            }
            assertEquals("$label: actual refresh invocations", expected.int("refreshCalls"), fake.calls)
            assertEquals(label, expected.int("refreshCalls"), coordinator.refreshCalls)
            assertTrue("$label: replay before refresh finished", replayed.isEmpty())
            assertEquals(label, 0, clearCalls)

            clock.nowMillis = 1L
            fake.finish(if (row.string("refreshResult") == "ok") "fresh-access-token" else null)

            assertEquals(label, expected.int("refreshCalls"), fake.calls)
            assertEquals(label, expected.int("refreshCalls"), coordinator.refreshCalls)
            assertEquals(label, expected.int("replayed"), coordinator.replayed)
            assertEquals(label, expected.string("effect"), coordinator.effectRaw)
            assertEquals(label, expected.int("replayed"), replayed.size)
            assertEquals(label, if (row.string("refreshResult") == "fail") 1 else 0, clearCalls)
            if (row.string("refreshResult") == "ok") {
                assertEquals(label, requests.map { it.id }.sorted(), replayed.map { it.id }.sorted())
                replayed.forEachIndexed { index, actual ->
                    val original = requests.single { it.id == actual.id }
                    assertEquals(label, original.key, actual.key)
                    assertEquals(label, original.body, actual.body)
                    assertEquals(label, "fresh-access-token", replayTokens[index])
                }
            }
        }
    }

    @Test
    fun pendingKeys() {
        each("pending") { row, label ->
            val responses = row.getValue("steps").jsonArray.map { it.jsonObject["response"] }
            val authKinds =
                if (JsonPrimitive("envelope_10001") in responses) {
                    listOf("envelope_10001", "envelope_10004", "envelope_10005")
                } else {
                    listOf("envelope_10001")
                }
            val parameterKinds =
                if (JsonPrimitive("envelope_2xxxx") in responses) {
                    listOf("envelope_20001", "envelope_20901", "envelope_20003")
                } else {
                    listOf("envelope_20001")
                }
            authKinds.forEach { auth ->
                parameterKinds.forEach { parameter ->
                    checkPending(row, "$label $auth/$parameter", auth, parameter)
                }
            }
        }
    }

    @Test
    fun otherNonterminalResponsesKeepUnknownKey() {
        // BR-ID-10 明列的其余非终结响应，独立于 JSON 中 envelope_2xxxx 的分类。
        listOf("envelope_40901", "envelope_42901", "envelope_10006", "envelope_50001").forEach { kind ->
            val backend = MemoryPendingKeyBackend()
            val store = PendingKeyStore(backend = backend, clock = { 0L })
            store.coldStart("account-A")
            assertTrue(store.beforeSend("createWithdrawal", "key", "original", "account-A"))
            store.onResponse("key", "no_envelope")
            assertTrue(store.confirm("key"))
            store.onResponse("key", kind)
            assertEquals(kind, "unknown", store.state("key").stateRaw)
            assertNotNull(kind, backend.read("account-A", "key"))
            assertFalse(kind, store.beforeSend("createWithdrawal", "new-key", "changed", "account-A"))
        }
    }

    private fun checkPending(row: JsonObject, label: String, authKind: String, parameterKind: String) {
        val operationId = row.string("operationId")
        operation(row)
        val backend = MemoryPendingKeyBackend()
        val clock = VectorClock()
        var store = PendingKeyStore(backend = backend, clock = { clock.nowMillis })
        val key = "original-key"
        val body = "{\"fixture\":\"original\"}\n"
        var account = "account-A"
        var abandoning = false
        store.coldStart(account)
        val steps = row.getValue("steps").jsonArray
        assertTrue(label, steps.isNotEmpty())
        steps.forEachIndexed { index, element ->
            val step = element.jsonObject
            val expected = step.obj("expect")
            val op = step.string("op")
            val stepLabel = "$label step[$index] $op"
            when (op) {
                "send" -> assertTrue(stepLabel, store.beforeSend(operationId, key, body, account))
                "confirm", "abandon" -> {
                    val allowed = account == "account-A" && store.state(key).stateRaw == "unknown"
                    val accepted = if (op == "confirm") store.confirm(key) else store.abandon(key)
                    assertEquals(stepLabel, allowed, accepted)
                    if (accepted) abandoning = op == "abandon"
                }
                "result" -> {
                    var kind = step.string("response")
                    if (kind == "envelope_10001") kind = authKind
                    if (kind == "envelope_2xxxx") kind = parameterKind
                    if (abandoning && kind == "envelope_success") kind = "abandoned"
                    store.onResponse(key, kind)
                    abandoning = false
                }
                "loginOther" -> {
                    account = "account-B"
                    store.coldStart(account)
                }
                "coldStart" -> {
                    clock.nowMillis += 31L * 24 * 60 * 60 * 1000
                    account = "account-A"
                    store = PendingKeyStore(backend = backend, clock = { clock.nowMillis })
                    store.coldStart(account)
                    abandoning = false
                }
                else -> error("$stepLabel: unknown op")
            }
            val state = expected.string("state")
            assertEquals(stepLabel, state, store.state(key).stateRaw)
            val record = backend.read("account-A", key)
            assertEquals("$stepLabel: actual backend retention", expected.bool("keyKept"), record != null)
            if (record != null) {
                assertEquals(stepLabel, key, record.key)
                assertEquals(stepLabel, operationId, record.operationId)
                assertEquals(stepLabel, body, record.body)
                assertEquals(stepLabel, "account-A", record.account)
            }
            if (state == "unknown") {
                assertFalse(
                    "$stepLabel: unknown must reject a new submission",
                    store.beforeSend(operationId, "replacement-key", "{\"fixture\":\"changed\"}", account),
                )
                assertNull(stepLabel, backend.read(account, "replacement-key"))
                assertEquals(stepLabel, body, backend.read("account-A", key)?.body)
                assertEquals(stepLabel, "unknown", store.state(key).stateRaw)
            }
            if (state == "in_flight") {
                assertFalse("$stepLabel: duplicate confirm", store.confirm(key))
                assertFalse("$stepLabel: parallel abandon", store.abandon(key))
                assertEquals(stepLabel, "in_flight", store.state(key).stateRaw)
            }
        }
    }

    private fun operation(row: JsonObject): ApiOperation {
        val id = row.string("operationId")
        return ApiOperations.byId(id) ?: error("Missing generated operation: $id")
    }

    private fun each(group: String, check: (JsonObject, String) -> Unit) {
        val rows = vectors.getValue(group).jsonArray
        val names = mutableSetOf<String>()
        assertTrue("$group: empty vectors", rows.isNotEmpty())
        rows.forEach { element ->
            val row = element.jsonObject
            val name = row.string("name")
            val note = row.string("note")
            assertTrue(name.isNotBlank())
            assertTrue(note.isNotBlank())
            assertTrue("$group: duplicate $name", names.add(name))
            check(row, "$group/$name: $note")
        }
    }

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.nullableString(key: String): String? =
        if (getValue(key) == JsonNull) null else getValue(key).jsonPrimitive.content
}
