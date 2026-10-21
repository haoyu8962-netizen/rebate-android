package com.couli.core.clipboard

import com.couli.core.clientcore.BundledConfig
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CT-LOCK-02：/v1/config 的契约示例多了 clipboard.consent_version（示例值 2026-10-01）。本端自动读取的门槛是
 * 「配置缺 consent_version 一律不许读、不用包内值兜底」（G47）；包内默认配置由示例生成，带上它就会在没有 LKG 时
 * 让用户按示例版本做功能同意。所以 gen.py 把它从包内配置去掉（与 platform_icons 同理），服务端下发的照常读。
 */
class CtLock02BundledConsentVersionTest {
    private val repositoryRoot: File by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
    }

    private val exampleData: JsonObject by lazy {
        val openapi = Json.parseToJsonElement(File(repositoryRoot, "contracts/snapshot/contracts/openapi.json").readText()).jsonObject
        listOf("paths", "/v1/config", "get", "responses", "200", "content", "application/json", "example", "data")
            .fold(openapi) { node, key -> node.getValue(key).jsonObject }
    }

    @Test
    fun bundledConfigDoesNotShipTheExampleConsentVersion() {
        val example = (exampleData["clipboard"] as JsonObject)["consent_version"] as JsonPrimitive
        assertEquals("snapshot example carries consent_version", "2026-10-01", example.content)
        val bundled = Json.parseToJsonElement(BundledConfig.dataJson).jsonObject
        assertTrue("clipboard section is kept", bundled["clipboard"] is JsonObject)
        assertNull("bundled clipboard has no consent_version", (bundled["clipboard"] as JsonObject)["consent_version"])
        // 没有 LKG 用包内配置时：当前功能同意版本缺失 → 门槛②不满足（G47 行为不变）
        assertNull(ImportPreferenceStore.currentConsentVersion(bundled))
    }

    @Test
    fun serverConsentVersionIsStillRead() {
        assertEquals("2026-10-01", ImportPreferenceStore.currentConsentVersion(exampleData))
    }
}
