package com.couli.core.clientcore

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端与三端共用的设备哈希向量（BR-ID-09；直接读锁定快照 contracts/snapshot/specs/device-hash.vectors.json，不复制预期值）：
 * hash_cases 逐条核对 device_hash；invalid_identifier_cases 一律无效、不注册、不求哈希；invalid_hash_seeds 与生成的
 * DeviceHashSeeds 一致，种子原值算出的哈希等于登记值，且这些值都不注册。
 */
class DeviceHashVectorTest {
    private val vectors: JsonObject by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        Json.parseToJsonElement(File(root, "contracts/snapshot/specs/device-hash.vectors.json").readText()).jsonObject
    }

    private fun rows(key: String): List<JsonObject> = vectors.getValue(key).jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun hashCasesMatch() {
        val cases = rows("hash_cases")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val input = case.text("input")
            assertEquals(case.text("note"), case.text("device_hash"), DeviceIdPolicy.hash(input))
            assertTrue(case.text("note"), DeviceIdPolicy.isValid(input))
        }
    }

    @Test
    fun androidCasesRegisterWithAndroidIdSourceAndVectorHash() {
        val android = rows("hash_cases").filter { it.text("id_source") == "android_id" }
        assertTrue(android.isNotEmpty())
        for (case in android) {
            val decision = DeviceIdPolicy.evaluate(ClientPlatformRaw.ANDROID, case.text("input"), PrivacyStateRaw.CONSENTED)
            assertTrue(case.text("note"), decision.register)
            assertEquals("android_id", decision.idSource)
            assertEquals(case.text("device_hash"), decision.deviceHash)
        }
    }

    @Test
    fun invalidIdentifiersAreNeverHashedOrRegistered() {
        val cases = rows("invalid_identifier_cases")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            assertFalse(case.text("note"), case.getValue("valid").jsonPrimitive.boolean)
            val decision = DeviceIdPolicy.evaluate(ClientPlatformRaw.ANDROID, case.text("input"), PrivacyStateRaw.CONSENTED)
            assertFalse(case.text("note"), decision.register)
            assertNull("无效值不求哈希上报：${case.text("note")}", decision.deviceHash)
        }
    }

    @Test
    fun invalidHashSeedsMatchGeneratedListAndAreRejected() {
        val seeds = rows("invalid_hash_seeds")
        assertEquals(seeds.map { it.text("device_hash") }.toSet(), DeviceHashSeeds.invalidHashes)
        for (seed in seeds) {
            val value = seed.text("source_value")
            assertEquals(seed.text("note"), seed.text("device_hash"), DeviceIdPolicy.hash(value))
            val decision = DeviceIdPolicy.evaluate(ClientPlatformRaw.ANDROID, value, PrivacyStateRaw.CONSENTED)
            assertFalse(seed.text("note"), decision.register)
            assertNull(seed.text("note"), decision.deviceHash)
        }
    }

    @Test
    fun nothingRegistersBeforeConsent() {
        val valid = rows("hash_cases").first { it.text("id_source") == "android_id" }.text("input")
        for (privacy in listOf(PrivacyStateRaw.NOT_DECIDED, PrivacyStateRaw.BASIC_MODE)) {
            assertFalse(privacy, DeviceIdPolicy.evaluate(ClientPlatformRaw.ANDROID, valid, privacy).register)
        }
    }
}
