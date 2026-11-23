package com.couli.core.clientcore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N-22「上次可用白名单」状态机（照 iOS 任务书 §1.2(b)、§3.1 第 2 条；写法照 link_patterns 上次可用的先例
 * Fx24BridgeDisabledMethodsConfigTest：内存文件 + 假拉取 + 真 ConfigStore）。
 *
 * core:config 不依赖 core:bridge：这里注入桩验签闭包 [FakeVerifier]（真验签器由 core:bridge 的向量测试与 app 的接线测试覆盖）。
 * 桩口径：bridge_origins = {"jws": "ok|<version>|<origin>,<origin>"} 且 version ≥ 上次可用 version 才通过；其余一律拒。
 * LKG 文件字段名 bridge_origins_last_good，形状 {"version": n, "origins": [...]}（实现方接口桩交回 §1.2）。
 */
class ConfigStoreBridgeOriginsTest {
    private var disk: String? = null
    private val callbacks = mutableListOf<(ConfigReply) -> Unit>()
    private val verifier = FakeVerifier()

    private fun store(bundled: String = BUNDLED) =
        ConfigStore(
            readFile = { disk },
            writeFile = { disk = it },
            fetcher = { _, done -> callbacks += done },
            clock = { 0L },
            bundled = bundled,
            verifyBridgeOrigins = verifier::verify,
        )

    private fun reply(
        kind: String,
        body: String? = null,
    ) {
        assertTrue("前提：有在途拉取", callbacks.isNotEmpty())
        callbacks.removeAt(0)(ConfigReply(kind, body, if (kind == ConfigReply.OK) "\"etag-${++sequence}\"" else null))
    }

    private var sequence = 0

    private fun ok(body: String) = reply(ConfigReply.OK, body)

    private fun lastGoodOnDisk(): JsonObject? = disk?.let { Json.parseToJsonElement(it).jsonObject[KEY] as? JsonObject }

    private fun assertTrusted(
        what: String,
        store: ConfigStore,
        version: Long?,
        origins: List<String>,
    ) {
        assertEquals("$what：trustedOrigins", origins, store.trustedOrigins)
        assertEquals("$what：trustedOriginsVersion", version, store.trustedOriginsVersion)
    }

    private fun assertLastGoodOnDisk(
        what: String,
        version: Long,
        origins: List<String>,
    ) {
        assertEquals("$what：LKG 里的 bridge_origins_last_good", lastGood(version, origins), lastGoodOnDisk())
    }

    /** 冷启动（无 LKG）并收下一份验得过的 v3 [A]。 */
    private fun storeTrustingA(): ConfigStore =
        store().also {
            it.onColdStart()
            ok(body(token(3, A), "v3-a"))
            assertTrusted("前提：v3 [A] 通过", it, 3L, listOf(A))
        }

    // ---------------------------------------------------------------- 200

    @Test
    fun acceptedConfigBecomesLastGoodAndIsPersistedWithTheLkg() {
        val store = store()
        store.onColdStart()
        assertTrusted("还没有服务端配置", store, null, emptyList())
        ok(body(token(3, A, B), "v3-ab"))
        assertTrusted("验过", store, 3L, listOf(A, B))
        assertLastGoodOnDisk("随 LKG 落盘", 3L, listOf(A, B))
        val call = verifier.calls.last()
        assertEquals("验的是这次 200 的 data", "v3-ab", (call.first["config_version"] as JsonPrimitive).content)
        assertNull("没有上次可用时 lastGoodVersion = null", call.second)
    }

    @Test
    fun rejectedConfigKeepsTheLastGoodAndTheLkgNeverStoresTheFailedValue() {
        val store = storeTrustingA()
        listOf(
            body("bad|3|$B", "bad-signature") to "验不过（签名坏）",
            body(token(2, B), "rollback") to "版本回退",
            """{"config_version":"array","bridge_origins":["$B"]}""" to "旧的数组形状",
            """{"config_version":"none"}""" to "没有 bridge_origins",
        ).forEach { (json, what) ->
            store.onForeground()
            ok(json)
            assertEquals("$what：这份配置本身照常生效", ConfigSourceRaw.CURRENT, store.sourceRaw)
            assertTrusted(what, store, 3L, listOf(A))
            assertEquals("$what：用上次可用的 version 验", 3L, verifier.calls.last().second)
            assertLastGoodOnDisk("$what：LKG 照旧写原上次可用", 3L, listOf(A))
        }
        // 重启后读到的仍是原上次可用
        val restarted = store()
        restarted.onColdStart()
        assertTrusted("重启后", restarted, 3L, listOf(A))
    }

    @Test
    fun firstRejectedConfigWritesNoLastGoodField() {
        val store = store()
        store.onColdStart()
        ok(body("bad|1|$A", "bad"))
        assertTrusted("验不过", store, null, emptyList())
        assertTrue("LKG 照常写了 data", disk != null)
        assertFalse("没有上次可用时 LKG 不写 bridge_origins_last_good", Json.parseToJsonElement(checkNotNull(disk)).jsonObject.containsKey(KEY))
    }

    @Test
    fun equalVersionIsNotARollback() {
        val store = storeTrustingA()
        store.onForeground()
        ok(body(token(3, B), "v3-b"))
        assertEquals("用上次可用的 version 3 验", 3L, verifier.calls.last().second)
        assertTrusted("版本相等通过", store, 3L, listOf(B))
        assertLastGoodOnDisk("替换并落盘", 3L, listOf(B))
        store.onForeground()
        ok(body(token(4, C), "v4-c"))
        assertTrusted("更高版本通过", store, 4L, listOf(C))
    }

    // ---------------------------------------------------------------- 冷启动

    @Test
    fun coldStartUsesTheStoredLastGoodDirectly() {
        disk = lkg(data = """{"config_version":"old","bridge_origins":{"jws":"bad|9|$B"}}""", lastGood = lastGood(7, listOf(A)))
        val store = store()
        store.onColdStart()
        assertTrusted("拉取结算前就可用", store, 7L, listOf(A))
        reply(ConfigReply.FAILURE)
        assertTrusted("拉取失败不变", store, 7L, listOf(A))
        store.onForeground()
        ok(body(token(6, C), "v6"))
        assertEquals("读到的上次可用 version 参与回退判定", 7L, verifier.calls.last().second)
        assertTrusted("v6 < v7 不替换", store, 7L, listOf(A))
    }

    @Test
    fun lastGoodWrittenByOneProcessIsReadByTheNext() {
        storeTrustingA()
        val restarted = store()
        restarted.onColdStart()
        assertTrusted("重启", restarted, 3L, listOf(A))
    }

    @Test
    fun oldLkgWithoutTheFieldIsVerifiedOnceAtColdStart() {
        disk = lkg(data = body(token(4, A), "old-ok"))
        val store = store()
        store.onColdStart()
        assertTrusted("旧 LKG 现验通过", store, 4L, listOf(A))
        val call = verifier.calls.last()
        assertEquals("验的是 LKG 的 data", "old-ok", (call.first["config_version"] as JsonPrimitive).content)
        assertNull("旧 LKG 现验 lastGoodVersion = null", call.second)
    }

    @Test
    fun oldLkgThatFailsVerificationGivesAnEmptyWhitelist() {
        listOf(
            body("bad|4|$A", "old-bad"),
            """{"config_version":"old-array","bridge_origins":["$A"]}""",
        ).forEach { data ->
            disk = lkg(data = data)
            val store = store()
            store.onColdStart()
            assertTrusted("旧 LKG 验不过：$data", store, null, emptyList())
            callbacks.clear()
        }
    }

    // ---------------------------------------------------------------- 304 / 失败 / 切环境 / 包内

    @Test
    fun notModifiedAndFailureLeaveTheLastGoodUnchanged() {
        val store = storeTrustingA()
        val calls = verifier.calls.size
        val before = disk
        store.onForeground()
        reply(ConfigReply.NOT_MODIFIED)
        assertTrusted("304", store, 3L, listOf(A))
        store.onForeground()
        reply(ConfigReply.FAILURE)
        assertTrusted("拉取失败", store, 3L, listOf(A))
        assertEquals("304 / 失败不再验签", calls, verifier.calls.size)
        assertEquals("304 / 失败不写 LKG", before, disk)
    }

    @Test
    fun environmentSwitchClearsTheLastGood() {
        val store = storeTrustingA()
        store.clearForEnvironmentSwitch()
        assertTrusted("切环境后", store, null, emptyList())
        assertNull("LKG 已删", disk)
        store.onColdStart()
        assertTrusted("新环境冷启动（无 LKG）", store, null, emptyList())
        ok(body(token(1, C), "new-env-v1"))
        assertNull("旧环境的 version 不再参与回退判定", verifier.calls.last().second)
        assertTrusted("新环境的 v1 通过", store, 1L, listOf(C))
    }

    @Test
    fun bundledPlaceholderGivesAnEmptyWhitelist() {
        val bundled = checkNotNull(ConfigResolver.parseObject(BundledConfig.dataJson))
        assertTrue("包内带 bridge_origins 占位（生成物）", bundled["bridge_origins"] is JsonObject)
        val store = store(bundled = BundledConfig.dataJson)
        assertTrusted("构造后", store, null, emptyList())
        store.onColdStart()
        reply(ConfigReply.FAILURE)
        assertEquals(ConfigSourceRaw.BUNDLED, store.sourceRaw)
        assertTrusted("只有包内配置", store, null, emptyList())
    }

    /** 桩验签：见类注释。记下每次调用的 (data, lastGoodVersion)。 */
    private class FakeVerifier {
        val calls = mutableListOf<Pair<JsonObject, Long?>>()

        @Suppress("ReturnCount") // 桩：按输入形状逐类返回
        fun verify(
            config: JsonObject,
            lastGoodVersion: Long?,
        ): BridgeOriginsLastGood? {
            calls += config to lastGoodVersion
            val jws = ((config["bridge_origins"] as? JsonObject)?.get("jws") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val parts = jws?.split('|')?.takeIf { it.size == 3 && it[0] == "ok" } ?: return null
            val version = parts[1].toLongOrNull() ?: return null
            if (lastGoodVersion != null && version < lastGoodVersion) return null
            return BridgeOriginsLastGood(version, parts[2].split(','))
        }
    }

    private companion object {
        const val KEY = "bridge_origins_last_good"
        const val A = "https://a.example.test"
        const val B = "https://b.example.test"
        const val C = "https://c.example.test"
        const val BUNDLED = """{"config_version":"bundled"}"""

        fun token(
            version: Long,
            vararg origins: String,
        ) = "ok|$version|${origins.joinToString(",")}"

        fun body(
            jws: String,
            tag: String,
        ) = """{"config_version":"$tag","bridge_origins":{"jws":"$jws"}}"""

        fun lastGood(
            version: Long,
            origins: List<String>,
        ) = JsonObject(mapOf("version" to JsonPrimitive(version), "origins" to JsonArray(origins.map(::JsonPrimitive))))

        /** 手写一份 LKG 文件（ConfigStore 的 format 1）；[lastGood] 为 null 时是升级前的旧 LKG（没有该字段）。 */
        fun lkg(
            data: String,
            lastGood: JsonObject? = null,
        ): String {
            val fields =
                linkedMapOf<String, JsonElement>(
                    "format" to JsonPrimitive(1),
                    "saved_at" to JsonPrimitive(0L),
                    "data" to Json.parseToJsonElement(data),
                )
            lastGood?.let { fields[KEY] = it }
            return JsonObject(fields).toString()
        }
    }
}
