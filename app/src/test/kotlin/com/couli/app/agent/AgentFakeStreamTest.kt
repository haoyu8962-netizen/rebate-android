package com.couli.app.agent

import com.couli.core.agent.AgentCall
import com.couli.core.agent.AgentHttpResult
import com.couli.core.agent.AgentSse
import com.couli.core.agent.AgentStreamSink
import com.couli.core.agent.AgentTimer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Debug 假 Agent 服务的两道边界（APP-11 第 10、16 条：在实际传输入口统一判断）：开关文件不在、当前环境不允许 MOCK_DATA（prod）、调用方 sendAllowed 为 false
 * 三者任一 → 不发（notSent / 传输失败），测试包连 prod 时不注入假流；允许时按 fixture 原文逐行下发 SSE；不保存请求正文。
 */
class AgentFakeStreamTest {
    private var switchOn = true
    private var mockAllowed = true
    private val timers = mutableListOf<() -> Unit>()
    private val timer =
        AgentTimer { _, block ->
            timers += block;
            {}
        }

    private val root: File by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").canonicalFile
        generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
    }

    private val fake =
        AgentFakeStream(
            switchOn = { switchOn },
            mockAllowed = { mockAllowed },
            fixtures = { name ->
                File(root, "contracts/snapshot/contracts/fixtures/agent-streams/$name.ndjson").takeIf(File::exists)?.readText()
            },
            state = { mapOf("agent_fixture" to "normal-page-guide") },
            timer = timer,
        )

    private fun call(allowed: Boolean = true) = AgentCall("owner-a", false, { allowed }, { true })

    private class Sink : AgentStreamSink {
        val chunks = StringBuilder()
        var failed = false
        var ended = false
        var status = 0

        override fun onHeaders(
            status: Int,
            headers: Map<String, String>,
        ) {
            this.status = status
        }

        override fun onData(chunk: String) {
            chunks.append(chunk)
        }

        override fun onEnd() {
            ended = true
        }

        override fun onFailure() {
            failed = true
        }
    }

    private fun drain() {
        while (timers.isNotEmpty()) timers.removeAt(0)()
    }

    @Test
    fun prodEnvironmentOrSwitchOffNeverAnswers() {
        mockAllowed = false
        val r: AgentHttpResult = runBlocking { fake.currentSession(call()) }
        assertTrue(r.notSent)
        val sink = Sink()
        fake.sendMessage("s", """{"client_msg_id":"m0000001","text":"x"}""", call(), sink)
        drain()
        assertTrue(sink.failed)
        assertEquals(0, fake.sent)
        mockAllowed = true
        switchOn = false
        assertTrue(runBlocking { fake.createSession(call()) }.notSent)
        assertFalse(fake.enabled())
        switchOn = true
        assertTrue(runBlocking { fake.createSession(call(allowed = false)) }.notSent)
        assertEquals(0, fake.sent)
    }

    @Test
    fun replaysFixtureAsSseAndRepeatsAsDuplicate() {
        val sink = Sink()
        fake.sendMessage("s", """{"client_msg_id":"m0000001","text":"提现进度"}""", call(), sink)
        drain()
        val expected =
            File(root, "contracts/snapshot/contracts/fixtures/agent-streams/normal-page-guide.ndjson").readLines().filter {
                it.isNotBlank()
            }
        assertEquals(expected.joinToString("") { AgentSse.sseTextOfNdjsonLine(it) }, sink.chunks.toString())
        assertTrue(sink.ended)
        // 同一 client_msg_id 重发：duplicate 两帧（BR-AI-23 ⑥）
        val again = Sink()
        fake.sendMessage("s", """{"client_msg_id":"m0000001","text":"提现进度"}""", call(), again)
        drain()
        assertTrue(again.chunks.contains("\"duplicate\":true"))
        // 归属作废后 id 记录清掉（不留对话痕迹）
        fake.cancelOwned("owner-a")
        val third = Sink()
        fake.sendMessage("s", """{"client_msg_id":"m0000001","text":"提现进度"}""", call(), third)
        drain()
        assertFalse(third.chunks.contains("\"duplicate\":true"))
        assertTrue(fake.marker.startsWith("COULI_FAKE_AGENT_SERVER_APP10"))
    }
}
