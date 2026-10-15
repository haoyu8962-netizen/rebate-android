package com.couli.app.agent

import com.couli.core.agent.AgentJson
import com.couli.core.agent.AgentSse
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Debug 假 Agent 服务（APP-10b §2 第 13 条，原 AgentFakeStreamTest 随端口改为传输入口替身重写）：两道边界（开关 + MOCK_DATA）任一不满足 → 不回答（交真实网络），
 * 测试包连 prod 时不注入假流；允许时按 fixture 原文逐帧下发 SSE 字节；同键重发回 duplicate 两帧；历史 / 反馈 / 举报按状态文件回答；不保存请求正文。
 */
class AgentFakeServerTest {
    private var switchOn = true
    private var mockAllowed = true
    private var state = mutableMapOf("agent_fixture" to "normal-page-guide", "agent_interval_ms" to "1")

    private val root: File by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").canonicalFile
        generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
    }

    private val fake =
        AgentFakeServer(
            gate = { switchOn },
            mockAllowed = { mockAllowed },
            fixtures = { name ->
                File(root, "contracts/snapshot/contracts/fixtures/agent-streams/$name.ndjson").takeIf(File::exists)?.readText()
            },
            state = { state },
        )

    private fun post(
        path: String,
        body: String = "{}",
    ): Request =
        Request
            .Builder()
            .url("https://api.example.test$path")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer a")
            .build()

    private fun get(path: String): Request =
        Request
            .Builder()
            .url("https://api.example.test$path")
            .header("Authorization", "Bearer a")
            .build()

    private fun text(request: Request): String =
        fake
            .answer(request) { false }!!
            .body
            .string()

    @Test
    fun prodEnvironmentOrSwitchOffNeverAnswers() {
        mockAllowed = false
        assertTrue(fake.handles(get("/v1/agent/sessions/current")))
        assertNull(fake.answer(get("/v1/agent/sessions/current")) { false })
        mockAllowed = true
        switchOn = false
        assertNull(fake.answer(post("/v1/agent/sessions")) { false })
        assertFalse(fake.enabled())
        assertEquals(0, fake.sent)
        // 不是 agent 路径：不认领
        assertFalse(fake.handles(get("/v1/me")))
    }

    @Test
    fun replaysFixtureAsSseAndRepeatsAsDuplicate() {
        val body = """{"client_msg_id":"m0000001","text":"提现进度"}"""
        val first = fake.answer(post("/v1/agent/sessions/s1/messages", body)) { false }!!
        assertEquals("text/event-stream; charset=utf-8", first.header("Content-Type"))
        val expected =
            File(root, "contracts/snapshot/contracts/fixtures/agent-streams/normal-page-guide.ndjson").readLines().filter {
                it.isNotBlank()
            }
        assertEquals(expected.joinToString("") { AgentSse.sseTextOfNdjsonLine(it) }, first.body.string())
        // 同一 client_msg_id 重发：duplicate 两帧（BR-AI-23 ⑥）
        assertTrue(text(post("/v1/agent/sessions/s1/messages", body)).contains("\"duplicate\":true"))
        assertTrue(fake.marker.startsWith("COULI_FAKE_AGENT_SERVER_APP10"))
    }

    @Test
    fun historyFeedbackReportAndJsonErrorsAnswerByState() {
        state["agent_history"] = "paged"
        val page1 = AgentJson.objectOf(AgentJson.parse(text(get("/v1/agent/sessions/s1/messages?limit=20"))))!!
        val data1 = page1["data"] as JsonObject
        assertEquals("fake-cursor-2", AgentJson.stringOf(data1["next_cursor"]))
        val page2 = AgentJson.objectOf(AgentJson.parse(text(get("/v1/agent/sessions/s1/messages?limit=20&cursor=fake-cursor-2"))))!!
        assertTrue(AgentJson.isNull((page2["data"] as JsonObject)["next_cursor"]))
        state["agent_feedback"] = "42901"
        val fb = fake.answer(post("/v1/agent/messages/m1/feedback", """{"feedback":"up"}""")) { false }!!
        assertEquals("5", fb.header("Retry-After"))
        state["agent_report"] = "20001"
        assertTrue(text(post("/v1/agent/messages/m1/report", """{"reason":"其他问题"}""")).contains("\"reason\""))
        state["agent_fixture"] = "json_42901_bare"
        val limited = fake.answer(post("/v1/agent/sessions/s1/messages", """{"client_msg_id":"m0000009","text":"x"}""")) { false }!!
        assertNull(limited.header("Retry-After"))
        assertEquals(429, limited.code)
        state["agent_fixture"] = "html_502"
        assertEquals(
            502,
            fake.answer(post("/v1/agent/sessions/s1/messages", """{"client_msg_id":"m0000010","text":"x"}""")) { false }!!.code,
        )
    }

    @Test
    fun streamStopsWhenCanceledOrGateClosesMidway() {
        state["agent_interval_ms"] = "200"
        var canceled = false
        val r = fake.answer(post("/v1/agent/sessions/s1/messages", """{"client_msg_id":"m0000020","text":"x"}""")) { canceled }!!
        val source = r.body.source()
        val buffer = okio.Buffer()
        assertTrue(source.read(buffer, 8192) > 0)
        canceled = true
        val failure = runCatching { while (source.read(buffer, 8192) >= 0) Unit }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is IOException)
    }
}
