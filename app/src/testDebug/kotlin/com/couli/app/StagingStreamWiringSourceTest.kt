package com.couli.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-X-STAGING-HOSTS-android 测试 v2（D10-598 ①）源码接线核对：App 构造 ApiClient 时把 streamBaseUrl 接到变体的 currentStreamBaseUrl，
 * 且与 baseUrl 一样受环境闸门约束；release 变体同签名、只取 ProdEndpoints（PROD 不设流式基址，行为不变）。行为由
 * core:network 的 StagingStreamRoutingTest 与 StagingStreamVariantTest 钉住，这里只防「加了能力却没接上」。
 */
class StagingStreamWiringSourceTest {
    private val root: File by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
    }

    private fun read(path: String) = File(root, path).readText()

    /** 去掉空白，便于对表达式做形状匹配。 */
    private fun compact(text: String) = text.replace(Regex("""\s+"""), "")

    @Test fun w01AppModulePassesTheGatedStreamBaseToApiClient() {
        val module = compact(read("app/src/main/kotlin/com/couli/app/AppModule.kt"))
        assertTrue(
            "AppModule 构造 ApiClient 时须传 streamBaseUrl，且与 baseUrl 同样只在环境闸门已定时取值",
            module.contains("streamBaseUrl={if(environmentGate.isSettled)currentStreamBaseUrl(context,cleaners)elsenull}"),
        )
        assertTrue("baseUrl 的接线不变", module.contains("baseUrl={if(environmentGate.isSettled)currentApiBaseUrl(context,cleaners)elsenull}"))
    }

    @Test fun w02ReleaseVariantReturnsTheProdStreamBaseOnly() {
        val release = read("app/src/release/kotlin/com/couli/app/VariantEnvironment.kt")
        assertTrue("release 须有同签名的 currentStreamBaseUrl", Regex("""internal fun currentStreamBaseUrl\(""").containsMatchIn(release))
        assertTrue("release 只取 ProdEndpoints", compact(release).contains("ProdEndpoints.endpoints.streamBaseUrl"))
        assertFalse("release 不得出现 staging 主机", release.contains("-stg."))
    }

    @Test fun w03DebugFeaturesVariantReadsTheSwitcherEndpoints() {
        val variant = compact(read("app/src/debugFeatures/kotlin/com/couli/app/VariantEnvironment.kt"))
        assertTrue(
            "debug / staging 的 currentStreamBaseUrl 与 currentApiBaseUrl 取同一个 EnvironmentSwitcher 的 endpoints",
            variant.contains("EnvironmentSwitcher(context,buildDefaultEnvironment(),cleaners).endpoints.streamBaseUrl"),
        )
    }
}
