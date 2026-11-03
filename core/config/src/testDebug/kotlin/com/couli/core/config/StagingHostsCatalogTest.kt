package com.couli.core.config

import java.io.File
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-X-STAGING-HOSTS-android：staging 环境改连真实主机（规划/02 §3.4「staging 主机」，编排 2026-11-04 00:06 派工）。
 * 只在 debug / staging 构建的 src/debugFeatures 里有非 prod 地址，所以本测试放 testDebug。
 * - STAGING：api / h5 / stream 三个基址是 `https://<子域>-stg.couliapp.cn`，确切主机、无端口、无路径、无通配；
 * - LOCAL / TEST 仍是 `.invalid` 占位，逐字与底版 d16c686 相同；PROD 仍是 [ProdEndpoints]，取值不变。
 * stream 基址在底版没有配置点（流式请求缺省走 API 基址，agent-transport-vectors G15）；本任务新增 [EnvironmentEndpoints] 的
 * `streamBaseUrl`。用反射读，字段没加之前本类其余用例照常编译、各自判红绿（handback §2 实现约束 C2）。
 */
class StagingHostsCatalogTest {
    private fun endpoints(environment: AppEnvironment) = EnvironmentEndpointsCatalog.endpoints(environment)

    /** `streamBaseUrl` 的取值；字段不存在返回 null。 */
    private fun streamOf(endpoints: EnvironmentEndpoints): String? =
        EnvironmentEndpoints::class.java.methods
            .firstOrNull { it.name == "getStreamBaseUrl" && it.parameterCount == 0 }
            ?.invoke(endpoints) as String?

    private fun assertExactHttpsOrigin(
        what: String,
        url: String,
        host: String,
    ) {
        assertEquals(what, "https://$host", url)
        val uri = URI(url)
        assertEquals("$what scheme", "https", uri.scheme)
        assertEquals("$what 确切主机（不用通配）", host, uri.host)
        assertEquals("$what 不带端口", -1, uri.port)
        assertTrue("$what 不带路径 / 查询", uri.rawPath.isNullOrEmpty() && uri.rawQuery == null)
    }

    @Test fun s01StagingApiIsTheRealStagingHost() {
        assertExactHttpsOrigin("STAGING apiBaseUrl", endpoints(AppEnvironment.STAGING).apiBaseUrl, "api-stg.couliapp.cn")
    }

    @Test fun s02StagingH5IsTheRealStagingHost() {
        assertExactHttpsOrigin("STAGING h5BaseUrl", endpoints(AppEnvironment.STAGING).h5BaseUrl, "h5-stg.couliapp.cn")
    }

    @Test fun s03StagingStreamIsTheRealStagingStreamHost() {
        val stream = streamOf(endpoints(AppEnvironment.STAGING))
        assertNotNull("EnvironmentEndpoints 须有 streamBaseUrl（Agent SSE 走 stream-stg，规划/02 §3.4）", stream)
        assertExactHttpsOrigin("STAGING streamBaseUrl", stream!!, "stream-stg.couliapp.cn")
    }

    @Test fun s04StagingNoLongerUsesThePlaceholder() {
        val e = endpoints(AppEnvironment.STAGING)
        listOfNotNull(e.apiBaseUrl, e.h5BaseUrl, streamOf(e)).forEach {
            assertFalse("STAGING 不得再是 .invalid 占位：$it", it.contains(".invalid"))
            assertFalse("STAGING 不得用 prod 主域 couliapp.com：$it", it.contains("couliapp.com"))
        }
    }

    @Test fun s05LocalAndTestStayInvalidPlaceholders() {
        val local = endpoints(AppEnvironment.LOCAL)
        assertEquals("https://api.local.couli.invalid", local.apiBaseUrl)
        assertEquals("https://h5.local.couli.invalid", local.h5BaseUrl)
        val test = endpoints(AppEnvironment.TEST)
        assertEquals("https://api.test.couli.invalid", test.apiBaseUrl)
        assertEquals("https://h5.test.couli.invalid", test.h5BaseUrl)
        listOf(local, test).forEach { e ->
            streamOf(e)?.let { stream ->
                assertTrue("${e.environment} streamBaseUrl 仍须 .invalid 占位：$stream", URI(stream).host.endsWith(".invalid"))
            }
            listOfNotNull(e.apiBaseUrl, e.h5BaseUrl, streamOf(e)).forEach {
                assertFalse("${e.environment} 不得指向真实主机：$it", it.contains("couliapp."))
            }
        }
        assertEquals(AppEnvironment.LOCAL, local.environment)
        assertEquals(AppEnvironment.TEST, test.environment)
    }

    @Test fun s06ProdIsUnchanged() {
        val prod = endpoints(AppEnvironment.PROD)
        assertSame("PROD 仍取 ProdEndpoints.endpoints", ProdEndpoints.endpoints, prod)
        assertEquals("https://api.prod.couli.invalid", prod.apiBaseUrl)
        assertEquals("https://h5.prod.couli.invalid", prod.h5BaseUrl)
        streamOf(prod)?.let { assertEquals("PROD 流式基址仍缺省等于 API 基址（G15，prod 主机未定）", prod.apiBaseUrl, it) }
        AppEnvironment.entries.forEach { assertEquals("每个环境的 environment 字段仍是它自己", it, endpoints(it).environment) }
    }

    @Test fun s08CatalogCommentPointsToThisTaskInsteadOfUndecidedDomains() {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        val source =
            File(root, "core/config/src/debugFeatures/kotlin/com/couli/core/config/EnvironmentEndpointsCatalog.kt").readText()
        assertFalse("删去「真实域名未定」注释（brief-android §1）", source.contains("真实域名未定"))
        assertTrue("注释改指本任务 FU-X-STAGING-HOSTS", source.contains("FU-X-STAGING-HOSTS"))
    }
}
