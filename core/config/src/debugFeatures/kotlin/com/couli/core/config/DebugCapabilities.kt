package com.couli.core.config

/** 调试能力（03 §3.6）。只在 debug、staging 构建里编译。首页草稿预览不属于调试能力（独立的只读能力，APP-11）。 */
enum class DebugCapability {
    SWITCH_SERVER,
    CLOCK_OFFSET,
    BRIDGE_CONFORMANCE,
    DEBUG_ROUTES,
    DIAGNOSTICS_PANEL,
    RESULT_INJECTION,
    MOCK_DATA,
}

/**
 * 当前环境是 prod 时，只保留「切换服务器地址」，其余调试能力一律关闭（03 §3.6、02 §3.2）。
 * 由客户端按当前环境自行判断，不依赖服务端开关。
 */
class DebugCapabilities(
    private val currentEnvironment: () -> AppEnvironment,
) {
    fun isAvailable(capability: DebugCapability): Boolean =
        capability == DebugCapability.SWITCH_SERVER || currentEnvironment() != AppEnvironment.PROD
}
