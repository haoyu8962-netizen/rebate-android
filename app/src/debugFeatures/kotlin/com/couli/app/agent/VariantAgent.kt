package com.couli.app.agent

import android.content.Context
import com.couli.app.DebugMockGate
import com.couli.core.agent.AgentContractPort
import java.io.File

/**
 * debug / staging：Agent 四个待定 operation 的端口（G01）。只有本机假服务打开（与假登录服务同一个开关文件）且当前环境允许 MOCK_DATA（prod 不允许，F-X-02）时
 * 才给端口；否则与 Release 一样为 null（Agent 页「未开放」、不发请求）。每次实际发包由 [AgentFakeStream] 再核对同一道闸（APP-11 第 16 条）。
 * 状态文件 files/[FAKE_AGENT_STATE_FILE]（每行 key=value）：
 * - agent_fixture=<名字>：normal、tool-failed、cancelled、disconnected、unknown-card、unknown-card-page-guide、error、error-search-fallback、fallback、
 *   normal-page-guide、normal-earnings、normal-earnings-history、duplicate-ended-done 等；构造：login_required、grid、relaxed、spec_unconfirmed、
 *   price_compare_risk、off_shelf、coupon_gone；json_<错误码>；
 * - agent_consent=pending（AI 同意未记录：发消息回 10004）；agent_load=10004|fail。
 */
internal fun variantAgentPort(context: Context): AgentContractPort? = agentFakeStream(context).takeIf { it.enabled() }

/** recordConsent 成功后：假服务记下「AI 同意已记录 / 已撤回」（只在内存）。 */
internal fun variantAgentConsentRecorded(
    context: Context,
    accepted: Boolean,
) {
    agentFakeStream(context).consentGranted = accepted
}

/** 进程内唯一的假 Agent 服务。 */
internal fun agentFakeStream(context: Context): AgentFakeStream =
    synchronized(FakeAgents) {
        FakeAgents.instance ?: run {
            val app = context.applicationContext
            val stateFile = File(DebugMockGate.stateDir(app), FAKE_AGENT_STATE_FILE)
            AgentFakeStream(
                // 统一闸门（F-X-02，DebugMockGate）：开关 + MOCK_DATA + 环境就绪；第二道只核对环境（双重核对，结果相同）
                switchOn = DebugMockGate.of(app),
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
                timer = MainAgentTimer(),
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
    var instance: AgentFakeStream? = null
}

internal const val FAKE_AGENT_STATE_FILE = "couli_fake_agent_state.txt"
private const val FIXTURE_DIR = "agent-streams"
