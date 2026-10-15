package com.couli.app.agent

import android.content.Context
import com.couli.core.network.LocalExchange

/**
 * Release（APP-10b §2 第 12 条）：Agent 页接真实端口（ApiAgentContractPort 在 main 源码集，与构建类型无关）；没有本机假服务、没有 fixture 资源（APP-10 A02），
 * ApiClient 的传输入口不接替身。同名文件在 src/debugFeatures。
 */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant") // 与 debug / staging 源码集同一签名
internal fun variantLocalExchange(context: Context): LocalExchange? = null

@Suppress("UnusedParameter") // 与 debug / staging 源码集同一签名
internal fun variantAgentConsentRecorded(
    context: Context,
    accepted: Boolean,
) = Unit
