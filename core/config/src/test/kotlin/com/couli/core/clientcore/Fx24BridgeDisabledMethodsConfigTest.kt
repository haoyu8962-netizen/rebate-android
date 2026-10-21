package com.couli.core.clientcore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** F-X-24 A1–A11：经 ConfigStore 的生效快照解析，不逐项过滤，不用契约方法表筛掉未知名称。 */
@RunWith(Parameterized::class)
class Fx24BridgeDisabledMethodsConfigTest(
    private val vector: String,
    private val rawValue: String?,
    private val expected: Set<String>,
    private val shouldWarn: Boolean,
) {
    @Test
    fun effectiveCurrentAndPersistedLastGoodUseTheSameWholeValueValidation() {
        val warnings = mutableListOf<String>()
        val callbacks = mutableListOf<(ConfigReply) -> Unit>()
        var disk: String? = null
        val body =
            buildJsonObject {
                put("config_version", "fx24-$vector")
                if (rawValue != null) put(KEY, Json.parseToJsonElement(rawValue))
            }

        fun store() =
            ConfigStore(
                readFile = { disk },
                writeFile = { disk = it },
                fetcher = { _, done -> callbacks += done },
                clock = { 0L },
                // 与当前值不同的包内名单，防止把缺失 / 非法值误读为回落包内的非空名单。
                bundled = """{"bridge_disabled_methods":["app.getEnv"]}""",
            )

        val current = store()
        current.onColdStart()
        callbacks.removeAt(0)(ConfigReply(ConfigReply.OK, body.toString(), "\"fx24-$vector\""))
        assertEquals(ConfigSourceRaw.CURRENT, current.sourceRaw)
        assertParsed(current.snapshots.value.data, warnings)
        assertEquals("其他配置键不受影响", body["config_version"], current.snapshots.value.data["config_version"])

        // 冷启动从真实 ConfigStore 写出的 LKG 读取；拉取失败仍保留同一份生效配置。
        val restarted = store()
        restarted.onColdStart()
        assertEquals(ConfigSourceRaw.LKG, restarted.sourceRaw)
        callbacks.removeAt(0)(ConfigReply(ConfigReply.FAILURE, null, null))
        warnings.clear()
        assertParsed(restarted.snapshots.value.data, warnings)
    }

    private fun assertParsed(
        data: JsonObject,
        warnings: MutableList<String>,
    ) {
        val actual = ConfigResolver.bridgeDisabledMethodsIn(data, warn = { key: String -> warnings += key })
        assertEquals("$vector：整键生效集合", expected, actual)
        // 精确比较次数与完整消息，确保只有键名，没有原值、方法名或额外内容。
        assertEquals("$vector：warn 只含键名", if (shouldWarn) listOf(KEY) else emptyList<String>(), warnings)
    }

    companion object {
        private const val KEY = "bridge_disabled_methods"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun vectors(): List<Array<Any?>> =
            listOf(
                arrayOf("A1", null, emptySet<String>(), false),
                arrayOf("A2", "[]", emptySet<String>(), false),
                arrayOf("A3", """["clipboard.read"]""", setOf("clipboard.read"), false),
                arrayOf("A4", """["clipboard.read","net.signedRequest"]""", setOf("clipboard.read", "net.signedRequest"), false),
                arrayOf("A5", """["unknown.method"]""", setOf("unknown.method"), false),
                arrayOf("A6", """"clipboard.read"""", emptySet<String>(), true),
                arrayOf("A7", """["clipboard.read",1]""", emptySet<String>(), true),
                arrayOf("A8", """["clipboard.read","Bad.Name"]""", emptySet<String>(), true),
                arrayOf("A9", """["clipboard.read","clipboard.read"]""", emptySet<String>(), true),
                arrayOf("A10", "null", emptySet<String>(), true),
                arrayOf("A11", """["clipboardread"]""", emptySet<String>(), true),
            )
    }
}
