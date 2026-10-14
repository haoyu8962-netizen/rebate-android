package com.couli.app.agent

import android.content.Context
import com.couli.core.agent.AgentContractPort

/**
 * Release：Agent 页没有端口（G01：锁定契约没有任何 /v1/agent/… 接口，四个待定 operation 只由 debug / staging 的本地假服务实现）。Agent 页显示「未开放」、
 * 不显示输入框、不弹同意框、不发任何 agent 请求；设置页不显示【撤回 AI 服务同意】。同名文件在 src/debugFeatures。TODO(N-36)：契约补上后由 ApiClient 管线实现同一端口。
 */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant") // 与 debug / staging 源码集同一签名
internal fun variantAgentPort(context: Context): AgentContractPort? = null

@Suppress("UnusedParameter") // 与 debug / staging 源码集同一签名
internal fun variantAgentConsentRecorded(
    context: Context,
    accepted: Boolean,
) = Unit
