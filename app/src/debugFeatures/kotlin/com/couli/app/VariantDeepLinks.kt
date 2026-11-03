package com.couli.app

import com.couli.app.catalog.CATALOG_EXTRA
import com.couli.app.web.CONFORMANCE_EXTRA
import com.couli.core.router.DeepLinkConfig

/**
 * debug / staging 的入站深链：一个测试用自定义 scheme（AndroidManifest 只在 src/debug、src/staging 声明），
 * 以及 staging 深链主机 [STAGING_DEEP_LINK_HOST]（FU-X-STAGING-HOSTS）：只在解析层认这个确切主机，
 * 系统级 https 入站声明（App Links）按 CSB-11 由契约 apps.json 生成，另立 FU-X-APPLINKS-STAGING，清单里不手写；
 * 联调用 adb am start -d 显式拉起。正式 scheme 与 l. 子域未定（needs.md N-05、N-14 ④）；release 不声明任何深链。
 */
internal const val DEBUG_DEEP_LINK_SCHEME = "couli-dev"

internal const val STAGING_DEEP_LINK_HOST = "l-stg.couliapp.cn"

internal fun variantDeepLinkConfig(): DeepLinkConfig =
    DeepLinkConfig(schemes = listOf(DEBUG_DEEP_LINK_SCHEME), hosts = listOf(STAGING_DEEP_LINK_HOST))

/**
 * 启动 Intent 清洗后保留的字符串 extra（R1-01）：组件目录的截图入口（couli.catalog）、桥一致性页（couli.conformance，APP-04）与剪贴板样例
 * （couli.clip，APP-07 截图：模拟别的 App 复制样例文字）。
 */
internal fun variantLaunchExtraAllowlist(): Set<String> = setOf(CATALOG_EXTRA, CONFORMANCE_EXTRA, DEBUG_CLIP_EXTRA)
