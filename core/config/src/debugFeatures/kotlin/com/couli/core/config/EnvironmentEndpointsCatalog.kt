package com.couli.core.config

/**
 * 全部环境的地址。本文件只在 debug、staging 构建里编译（src/debugFeatures），release 制品里没有
 * local / test / staging 的地址（03 §3.6；tools/ci/release-scan.sh 检查）。
 * STAGING 连真实 staging 主机（FU-X-STAGING-HOSTS，编排 2026-11-04 00:06 派工、D10-598）：api-stg / h5-stg / stream-stg.couliapp.cn。
 * streamBaseUrl 由 ApiClient 用于 Agent 接口（ApiEntryRouting，D10-598 ①）。LOCAL / TEST 仍用 .invalid 占位（needs.md N-05）。
 * 深链 l-stg 在 app 的 VariantDeepLinks.kt；桥白名单 h5-stg、分享 s-stg 由 staging /v1/config 下发，客户端不写死。
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
                    apiBaseUrl = "https://api-stg.couliapp.cn",
                    h5BaseUrl = "https://h5-stg.couliapp.cn",
                    streamBaseUrl = "https://stream-stg.couliapp.cn",
                )
            }

            AppEnvironment.PROD -> {
                ProdEndpoints.endpoints
            }
        }
}
