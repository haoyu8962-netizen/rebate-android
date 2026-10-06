package com.couli.app

import com.couli.core.router.DeepLinkConfig

/**
 * release：apps.json 的入站回调目前为空（needs.md N-05），不认任何深链；AndroidManifest 也不声明。
 * TODO(N-05): 负责人给定 scheme 与 l. 子域后由 apps.json 生成。
 */
internal fun variantDeepLinkConfig(): DeepLinkConfig = DeepLinkConfig(schemes = emptyList(), hosts = emptyList())

/** 启动 Intent 清洗后保留的字符串 extra（R1-01）：release 一个都不保留。 */
internal fun variantLaunchExtraAllowlist(): Set<String> = emptySet()
