package com.couli.core.config

/**
 * 全部环境的地址。本文件只在 debug、staging 构建里编译（src/debugFeatures），release 制品里没有
 * local / test / staging 的地址（03 §3.6；tools/ci/release-scan.sh 检查）。
 * 真实域名未定，先用 .invalid 占位（../couli-runs/NATIVE/needs.md N-05）。
 */
object EnvironmentEndpointsCatalog {
    fun endpoints(environment: AppEnvironment): EnvironmentEndpoints =
        when (environment) {
            AppEnvironment.LOCAL -> {
                EnvironmentEndpoints(
                    environment = environment,
                    apiBaseUrl = "https://api.local.couli.invalid",
                    h5BaseUrl = "https://h5.local.couli.invalid",
                )
            }

            AppEnvironment.TEST -> {
                EnvironmentEndpoints(
                    environment = environment,
                    apiBaseUrl = "https://api.test.couli.invalid",
                    h5BaseUrl = "https://h5.test.couli.invalid",
                )
            }

            AppEnvironment.STAGING -> {
                EnvironmentEndpoints(
                    environment = environment,
                    apiBaseUrl = "https://api.staging.couli.invalid",
                    h5BaseUrl = "https://h5.staging.couli.invalid",
                )
            }

            AppEnvironment.PROD -> {
                ProdEndpoints.endpoints
            }
        }
}
