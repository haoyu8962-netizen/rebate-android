package com.couli.app.web

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.couli.feature.web.TrustedWebScreen
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 一致性页入口的固定标记：tools/ci/release-scan.sh 在 release 制品里查它，命中即失败。 */
const val BRIDGE_CONFORMANCE_MARKER = "COULI_BRIDGE_CONFORMANCE_PAGE_5d8e2b7a41c9"

/** adb 直接打开一致性页：`am start -n com.couli.app/.MainActivity --es couli.conformance 1`。 */
const val CONFORMANCE_EXTRA = "couli.conformance"

/** `--es couli.conformance logged_in`：以假定已登录的身份跑一致性页。 */
const val CONFORMANCE_LOGGED_IN = "logged_in"

private const val HARNESS_ACCOUNT = "conformance-harness"

/**
 * 一致性页的加载地址与本机源（[url] 的 origin 必须在 [loopbackOrigins] 里）。取值只在 debug 的 src/debug 里
 * （CONFORMANCE_SOURCE）；staging 为 null。
 */
internal data class ConformanceSource(
    val url: String,
    val loopbackOrigins: Set<String>,
)

/**
 * 一致性页用的链接形态表与分享域：取 contracts/openapi.yaml GET /v1/config 的 200 响应示例（cases.ts 注释：UI 套件加载这份示例，
 * 页面不编造真实平台域名）。包内配置的规则为空（TODO(N-21)），本机也没有服务端配置，所以只在这个调试入口里注入示例值。
 */
private val SAMPLE_LINK_PATTERNS: JsonObject =
    Json
        .parseToJsonElement(
            """{"version":"2026100401","rules":[""" +
                """{"platform":"taobao","category":"union_host","hosts":["taobao.example.test"],"path_patterns":[]}]}""",
        ).jsonObject

private val SAMPLE_SHARE_DOMAINS: Set<String> = setOf("s.example.test")

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ConformanceEntryPoint {
    fun appWeb(): AppWeb
}

private fun appWeb(context: Context): AppWeb =
    EntryPointAccessors.fromApplication(context.applicationContext, ConformanceEntryPoint::class.java).appWeb()

/**
 * 桥一致性页（03 §5.5）：可信容器加载本机 conformance 入口，桥按真实宿主接线，只多加本机源与示例规则。
 * [mode] 为 [CONFORMANCE_LOGGED_IN] 时以假定已登录的调用方身份跑（只改协议准入用的登录标志，不造会话、不造令牌）：本机没有登录
 * 能力（TODO(APP-05)），用它验证 L1 / L2 方法的手势与白名单判定。
 */
@Composable
internal fun DebugConformancePage(
    source: ConformanceSource,
    mode: String?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val web = remember(context) { appWeb(context) }
    TrustedWebScreen(
        url = source.url,
        config = remember(web, source) { web.trustedConfig(source.loopbackOrigins) },
        hostFactory = { ui, scope ->
            web.newHost(
                ui,
                scope,
                loopbackOrigins = source.loopbackOrigins,
                patternsOverride = SAMPLE_LINK_PATTERNS,
                shareDomainsOverride = SAMPLE_SHARE_DOMAINS,
                callerOverride = if (mode == CONFORMANCE_LOGGED_IN) ({ it.copy(accountKey = it.accountKey ?: HARNESS_ACCOUNT) }) else null,
            )
        },
        onOpenExternal = {},
        onClose = onClose,
    )
}
