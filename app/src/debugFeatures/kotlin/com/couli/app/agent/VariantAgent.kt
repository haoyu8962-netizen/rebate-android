package com.couli.app.agent

import android.content.Context
import com.couli.app.DebugMockGate
import com.couli.core.network.LocalExchange
import java.io.File

/**
 * debug / staging：七个 agent operation 的本机假服务（APP-10b §2 第 13 条）挂在 ApiClient 的实际传输入口（[LocalExchange]，写出前核对之后）。每次回答现判
 * DebugMockGate（开关文件在 + 当前环境允许 MOCK_DATA + 环境就绪；prod 一律不允许），不允许时交真实网络（真实端口与 Release 同一份）。
 * 状态文件 files/[FAKE_AGENT_STATE_FILE]（每行 key=value，键见 [AgentFakeServer]）。
 */
internal fun variantLocalExchange(context: Context): LocalExchange? = agentFakeServer(context)

/** recordConsent 成功后：假服务记下「AI 同意已记录 / 已撤回」（只在内存）。 */
internal fun variantAgentConsentRecorded(
    context: Context,
    accepted: Boolean,
) {
    agentFakeServer(context).consentGranted = accepted
}

/** 进程内唯一的假 Agent 服务。 */
internal fun agentFakeServer(context: Context): AgentFakeServer =
    synchronized(FakeAgents) {
        FakeAgents.instance ?: run {
            val app = context.applicationContext
            val stateFile = File(DebugMockGate.stateDir(app), FAKE_AGENT_STATE_FILE)
            AgentFakeServer(
                // 统一闸门（F-X-02，DebugMockGate）：开关 + MOCK_DATA + 环境就绪；第二道只核对环境（双重核对，结果相同）
                gate = DebugMockGate.of(app),
                mockAllowed = { DebugMockGate.mockDataAllowed(app) },
                fixtures = { name ->
                    runCatching {
                        app.assets
                            .open("$FIXTURE_DIR/$name.ndjson")
                            .bufferedReader()
                            .use { it.readText() }
                    }.getOrNull()
                },
                state = { readState(stateFile) },
            ).also { FakeAgents.instance = it }
        }
    }

private fun readState(file: File): Map<String, String> =
    runCatching {
        file
            .takeIf(File::exists)
            ?.readLines()
            ?.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
            ?.toMap()
    }.getOrNull().orEmpty()

private object FakeAgents {
    var instance: AgentFakeServer? = null
}

internal const val FAKE_AGENT_STATE_FILE = "couli_fake_agent_state.txt"
private const val FIXTURE_DIR = "agent-streams"
