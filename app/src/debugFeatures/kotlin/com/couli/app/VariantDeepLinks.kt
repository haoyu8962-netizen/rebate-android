package com.couli.app

import com.couli.app.catalog.CATALOG_EXTRA
import com.couli.core.router.DeepLinkConfig

/**
 * debug / staging 的入站深链：只有一个测试用自定义 scheme（AndroidManifest 只在 src/debug、src/staging 声明）。
 * 正式 scheme 与 l. 子域未定（needs.md N-05、N-14 ④），apps.json inbound 为空；release 不声明任何深链。
 */
internal const val DEBUG_DEEP_LINK_SCHEME = "couli-dev"

internal fun variantDeepLinkConfig(): DeepLinkConfig = DeepLinkConfig(schemes = listOf(DEBUG_DEEP_LINK_SCHEME), hosts = emptyList())

/** 启动 Intent 清洗后保留的字符串 extra（R1-01）：只有组件目录的截图入口（couli.catalog）。 */
internal fun variantLaunchExtraAllowlist(): Set<String> = setOf(CATALOG_EXTRA)
