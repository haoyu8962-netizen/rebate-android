package com.couli.core.clientcore

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CT-LOCK-01：/v1/config 新增可选 platform_icons（CT-17f）。
 * - 包内默认配置不带它（gen.py BUNDLED_CONFIG_DROPPED_KEYS；示例里是测试域名）；
 * - 服务端带 platform_icons 的响应照常成为当前配置与 LKG，其余键（搜索开关、链接形态表）取值与不带时相同，不当坏数据；
 *   ConfigStore 只原样保存，不读它（平台标识下发接入归 F-X-05）。
 */
class CtLock01ConfigPlatformIconsTest {
    private val repositoryRoot: File by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
    }

    /** 契约快照里 GET /v1/config 200 示例的 data（含 platform_icons）。 */
    private val exampleData: JsonObject by lazy {
        val openapi = Json.parseToJsonElement(File(repositoryRoot, "contracts/snapshot/contracts/openapi.json").readText()).jsonObject
        val example =
            openapi["paths"]!!
                .jsonObject["/v1/config"]!!
                .jsonObject["get"]!!
                .jsonObject["responses"]!!
                .jsonObject["200"]!!
                .jsonObject["content"]!!
                .jsonObject["application/json"]!!
                .jsonObject["example"]!!
                .jsonObject
        example["data"]!!.jsonObject
    }

    @Test
    fun bundledDefaultConfigHasNoPlatformIcons() {
        assertTrue("snapshot example carries platform_icons", "platform_icons" in exampleData)
        val bundled = ConfigResolver.parseObject(BundledConfig.dataJson)
        assertNotNull(bundled)
        assertFalse("bundled config must not ship platform_icons", "platform_icons" in bundled!!)
    }

    private fun store(
        written: MutableList<String?>,
        replies: MutableList<(ConfigReply) -> Unit>,
    ) = ConfigStore(
        readFile = { null },
        writeFile = { written += it },
        fetcher = { _, done -> replies += done },
        clock = { 0L },
        bundled = BundledConfig.dataJson,
    )

    @Test
    fun responseWithPlatformIconsIsAcceptedAndOtherKeysUnaffected() {
        val withIcons = exampleData
        val withoutIcons = JsonObject(exampleData - "platform_icons")
        val results =
            listOf(withIcons, withoutIcons).map { body ->
                val written = mutableListOf<String?>()
                val replies = mutableListOf<(ConfigReply) -> Unit>()
                val store = store(written, replies)
                store.onColdStart()
                replies.single()(ConfigReply(ConfigReply.OK, body.toString(), "\"e1\""))
                assertEquals("accepted as current", ConfigSourceRaw.CURRENT, store.sourceRaw)
                assertEquals("LKG written", 1, written.size)
                Triple(store.linkPatterns(), store.searchStatusLayers(), store.searchStatus("taobao"))
            }
        assertEquals("link patterns", results[1].first, results[0].first)
        assertEquals("search status layers", results[1].second, results[0].second)
        assertEquals("search status", results[1].third, results[0].third)
        assertEquals(
            "other top-level keys resolve the same",
            ConfigResolver.resolve(withoutIcons.toString(), null, BundledConfig.dataJson, "features"),
            ConfigResolver.resolve(withIcons.toString(), null, BundledConfig.dataJson, "features"),
        )
    }
}
