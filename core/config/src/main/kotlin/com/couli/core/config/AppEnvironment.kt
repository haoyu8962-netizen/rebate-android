package com.couli.core.config

/**
 * 运行环境（02 §3.2、03 §3.6）。Release 只内置 [PROD] 的地址，不能切换；
 * local / test / staging 的地址只在 debug、staging 构建的 src/debugFeatures 里（[ProdEndpoints] 之外没有地址）。
 */
enum class AppEnvironment(
    val key: String,
) {
    LOCAL("local"),
    TEST("test"),
    STAGING("staging"),
    PROD("prod"),
    ;

    companion object {
        fun fromKey(key: String?): AppEnvironment? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 一个环境的服务地址。[streamBaseUrl] 是流式（Agent SSE）入口；null 表示没有单独入口、缺省等于 [apiBaseUrl]
 * （agent-transport-vectors G15）。只有 debugFeatures 里的环境表会给它赋值（FU-X-STAGING-HOSTS）。
 */
data class EnvironmentEndpoints(
    val environment: AppEnvironment,
    val apiBaseUrl: String,
    val h5BaseUrl: String,
    val streamBaseUrl: String? = null,
)

/**
 * 生产环境地址。真实域名未定，先用 .invalid 占位（../couli-runs/NATIVE/needs.md N-05）。
 */
object ProdEndpoints {
    val endpoints: EnvironmentEndpoints =
        EnvironmentEndpoints(
            environment = AppEnvironment.PROD,
            apiBaseUrl = "https://api.prod.couli.invalid",
            h5BaseUrl = "https://h5.prod.couli.invalid",
        )
}
