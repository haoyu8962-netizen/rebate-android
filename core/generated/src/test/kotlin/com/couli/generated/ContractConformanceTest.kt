package com.couli.generated

import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手写一致性测试；预期值独立读取 contracts/snapshot，不从生成代码反推。
 * 运行：./gradlew :core:generated:testDebugUnitTest
 */
class ContractConformanceTest {
    private val repositoryRoot: File by lazy {
        val workingDirectory =
            System.getProperty("user.dir")
                ?: throw AssertionError("找不到系统属性 user.dir")
        val start = File(workingDirectory).canonicalFile
        generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "contract.lock").isFile }
            ?: throw AssertionError("从 $start 向上找不到含 contract.lock 的仓库根目录")
    }

    private val enumSnapshots: Map<String, List<String>> by lazy {
        readEnumSnapshots()
    }

    @Test
    fun enumRegistryMatchesEverySnapshotIncludingValueOrder() {
        assertMapEntries("ContractEnums.all", enumSnapshots, ContractEnums.all)
    }

    @Test
    fun enumApisPreserveKnownValuesAndSupportUnknownValues() {
        // 点检来源：rebate-platform@85af83a 的 contracts/enums/platform.yaml。
        assertEquals(
            "Platform.fromRaw(taobao)",
            Platform.TAOBAO,
            Platform.fromRaw("taobao"),
        )
        assertEquals("PlatformSearchStatus.ON.raw", "on", PlatformSearchStatus.ON.raw)

        assertEquals(
            "Platform.known 的 raw 值及顺序",
            enumSnapshots.getValue("platform"),
            Platform.known.map { it.raw },
        )
        assertEquals(
            "ClientPlatform.known 的 raw 值及顺序",
            enumSnapshots.getValue("client_platform"),
            ClientPlatform.known.map { it.raw },
        )
        assertFalse(
            "ClientPlatform.known 不应包含 UNKNOWN",
            ClientPlatform.UNKNOWN in ClientPlatform.known,
        )

        Platform.known.forEach { platform ->
            assertEquals(
                "Platform.fromRaw(${platform.raw})",
                platform,
                Platform.fromRaw(platform.raw),
            )
        }
        assertEquals(
            "Platform.fromRaw(no_such_value) 应返回 UNKNOWN",
            Platform.UNKNOWN,
            Platform.fromRaw("no_such_value"),
        )
    }

    @Test
    fun unknownPlatformDeserializesWithoutThrowing() {
        // 来源：Android 任务书 §5.1；no_such_value 是测试哨兵。
        val decoded =
            at("Platform serializer: [\"no_such_value\"]") {
                Json.decodeFromString<List<Platform>>("[\"no_such_value\"]")
            }

        assertEquals(
            "Platform serializer 应把未知字符串解码为 UNKNOWN",
            listOf(Platform.UNKNOWN),
            decoded,
        )
    }

    @Test
    fun errorCodesMatchEverySnapshotField() {
        val snapshot = readSnapshot("contracts/error-codes.json")
        val expected = linkedMapOf<Int, JsonObject>()

        snapshot.getValue("codes").jsonArray.forEachIndexed { index, element ->
            at("error-codes.json.codes[$index]") {
                val source = element.jsonObject
                val code = source.getValue("code").jsonPrimitive.int
                assertNull("快照包含重复错误码 $code", expected.put(code, source))
            }
        }

        assertKeys("ErrorCodes.all", expected.keys, ErrorCodes.all.keys)
        expected.forEach { (code, source) ->
            at("ErrorCodes[$code]") {
                val actual = ErrorCodes.all.getValue(code)
                assertEquals("ErrorCodes[$code].code", code, actual.code)
                assertEquals(
                    "ErrorCodes[$code].http",
                    source.getValue("http").jsonPrimitive.int,
                    actual.http,
                )
                assertEquals(
                    "ErrorCodes[$code].retryKind",
                    source.getValue("retry_kind").jsonPrimitive.content,
                    actual.retryKind.raw,
                )
                assertMapEntries(
                    "ErrorCodes[$code].retryKindByReason",
                    source.objectOrEmpty("retry_kind_by_reason")
                        .mapValues { (_, value) -> value.jsonPrimitive.content },
                    actual.retryKindByReason.mapValues { (_, value) -> value.raw },
                )
                assertEquals(
                    "ErrorCodes[$code].isDeprecated",
                    source.booleanOrFalse("deprecated"),
                    actual.isDeprecated,
                )
                assertEquals(
                    "ErrorCodes[$code].isP1",
                    source["phase"].nullableString() == "P1",
                    actual.isP1,
                )
                assertEquals("ErrorCodes.info($code)", actual, ErrorCodes.info(code))
            }
        }

        assertFalse("测试哨兵 99999 不应是快照错误码", 99999 in expected)
        assertNull("ErrorCodes.info(99999)", ErrorCodes.info(99999))
    }

    @Test
    fun routesMatchSnapshotAndSeparateDebugOnlyEntries() {
        val expected = readSnapshot("contracts/routes.json").getValue("routes").jsonObject
        val debugNames =
            expected.filterValues { it.jsonObject.booleanOrFalse("debug_only") }.keys
        val normalNames = expected.keys - debugNames

        assertKeys("Routes.all", normalNames, Routes.all.keys)
        assertKeys("DebugRoutes.all", debugNames, DebugRoutes.all.keys)

        expected.forEach { (name, element) ->
            at("routes.json.routes[$name]") {
                val source = element.jsonObject
                if (name in debugNames) {
                    assertFalse("Routes.all 不应包含调试路由 $name", name in Routes.all)
                    assertNull("Routes.spec($name) 不应返回调试路由", Routes.spec(name))
                    val actual = DebugRoutes.all.getValue(name)
                    assertTrue("DebugRoutes[$name].debugOnly", actual.debugOnly)
                    assertRoute(name, source, actual)
                } else {
                    assertFalse("DebugRoutes.all 不应包含普通路由 $name", name in DebugRoutes.all)
                    val actual = Routes.all.getValue(name)
                    assertFalse("Routes[$name].debugOnly", actual.debugOnly)
                    assertRoute(name, source, actual)
                    assertEquals("Routes.spec($name)", actual, Routes.spec(name))
                }
            }
        }

        assertFalse("测试哨兵 NoSuchRoute 不应是快照路由", "NoSuchRoute" in expected)
        assertNull("Routes.spec(NoSuchRoute)", Routes.spec("NoSuchRoute"))
    }

    @Test
    fun bridgeMethodsMatchSnapshotIncludingDefaults() {
        // methods 是“方法名 -> 定义”的对象，不是数组。
        val expected =
            readSnapshot("contracts/bridge.schema.json").getValue("methods").jsonObject

        assertKeys("BridgeMethods.all", expected.keys, BridgeMethods.all.keys)
        expected.forEach { (name, element) ->
            at("BridgeMethods[$name]") {
                val source = element.jsonObject
                val actual = BridgeMethods.all.getValue(name)
                assertEquals("BridgeMethods[$name].name", name, actual.name)
                assertEquals(
                    "BridgeMethods[$name].level",
                    source.getValue("level").jsonPrimitive.content,
                    actual.level,
                )
                assertEquals(
                    "BridgeMethods[$name].timeoutMs",
                    source["timeout_ms"].nullableInt(),
                    actual.timeoutMs,
                )
                assertEquals(
                    "BridgeMethods[$name].sinceAndroid",
                    source.androidVersion(),
                    actual.sinceAndroid,
                )
                // 任务书 §5.4：缺省为 false，不能由 level == L2 推导为 true。
                assertEquals(
                    "BridgeMethods[$name].gestureRequired",
                    source.booleanOrFalse("gesture_required"),
                    actual.gestureRequired,
                )
                assertEquals("BridgeMethods.spec($name)", actual, BridgeMethods.spec(name))
            }
        }
    }

    @Test
    fun bridgeErrorCodesMatchSnapshot() {
        // errors 是“十进制码字符串 -> 含义”的对象。
        val source =
            readSnapshot("contracts/bridge.schema.json").getValue("errors").jsonObject
        val expectedCodes =
            source.keys.map { code ->
                at("bridge.schema.json.errors[$code]") { code.toInt() }
            }

        assertEquals(
            "bridge.schema.json.errors 转为整数后不应出现重复码：$expectedCodes",
            expectedCodes.size,
            expectedCodes.toSet().size,
        )
        assertKeys("BridgeErrors.all", expectedCodes.toSet(), BridgeErrors.all.keys)
    }

    @Test
    fun defaultTextsAndFallbacksMatchEverySnapshotKey() {
        val snapshot = readSnapshot("contracts/texts.default.json")
        val texts = snapshot.getValue("texts").jsonObject
        val fallbacks = snapshot.getValue("fallbacks").jsonObject
        val actualKeys = DefaultTexts.allKeys
        val duplicates = actualKeys.groupingBy { it }.eachCount().filterValues { it > 1 }

        assertTrue("DefaultTexts.allKeys 包含重复键：$duplicates", duplicates.isEmpty())
        assertKeys("DefaultTexts.allKeys", texts.keys, actualKeys.toSet())

        (texts.keys + fallbacks.keys).forEach { key ->
            at("DefaultTexts[$key]") {
                assertEquals(
                    "DefaultTexts.text($key)",
                    texts[key].nullableString(),
                    DefaultTexts.text(key),
                )
                assertEquals(
                    "DefaultTexts.fallback($key)",
                    fallbacks[key].nullableString(),
                    DefaultTexts.fallback(key),
                )
            }
        }

        val missingKey =
            generateSequence("__missing_contract_text__") { "$it." }
                .first { it !in texts && it !in fallbacks }
        assertNull("DefaultTexts.text($missingKey)", DefaultTexts.text(missingKey))
        assertNull("DefaultTexts.fallback($missingKey)", DefaultTexts.fallback(missingKey))
    }

    @Test
    fun defaultTextsResourceIsAnExactSnapshotCopy() {
        // Android 任务书 §2.3：必须原样复制为 JVM classloader 可读取的资源。
        val resourceBytes =
            DefaultTexts::class.java.classLoader
                .getResourceAsStream("texts.default.json")
                ?.use { it.readBytes() }
                ?: throw AssertionError("classloader 找不到资源 texts.default.json")

        assertArrayEquals(
            "资源 texts.default.json 必须与 contracts/snapshot/contracts/texts.default.json 逐字节一致",
            snapshotFile("contracts/texts.default.json").readBytes(),
            resourceBytes,
        )
    }

    @Test
    fun designTokenVersionMatchesMetadata() {
        val snapshot = readSnapshot("design/design-tokens.json")
        assertEquals(
            "DesignTokens.version / metadata.version",
            snapshot.getValue("metadata").jsonObject.getValue("version").jsonPrimitive.content,
            DesignTokens.version,
        )
    }

    @Test
    fun colorTokensMatchEveryLeafAndPreserveRelativePaths() {
        val tokens = readSnapshot("design/design-tokens.json").getValue("tokens").jsonObject
        val expected =
            tokenLeaves(tokens.getValue("color").jsonObject)
                .mapValues { (key, value) ->
                    at("tokens.color.$key.value") { value.jsonPrimitive.content }
                }

        assertMapEntries("DesignTokens.colors", expected, DesignTokens.colors)
    }

    @Test
    fun numericTokensMatchEveryLeaf() {
        val tokens = readSnapshot("design/design-tokens.json").getValue("tokens").jsonObject
        assertMapEntries(
            "DesignTokens.spacing / tokens.space",
            integerTokenLeaves(tokens.getValue("space").jsonObject, "tokens.space"),
            DesignTokens.spacing,
        )
        assertMapEntries(
            "DesignTokens.radius / tokens.radius",
            integerTokenLeaves(tokens.getValue("radius").jsonObject, "tokens.radius"),
            DesignTokens.radius,
        )
        assertMapEntries(
            "DesignTokens.fontSize / tokens.font.size",
            integerTokenLeaves(
                tokens.getValue("font").jsonObject.getValue("size").jsonObject,
                "tokens.font.size",
            ),
            DesignTokens.fontSize,
        )
    }

    private fun assertRoute(
        name: String,
        source: JsonObject,
        actual: RouteSpec,
    ) {
        val context = "RouteSpec[$name]"
        val expectedKind =
            when (val kind = source.getValue("kind").jsonPrimitive.content) {
                "native" -> RouteKind.NATIVE
                "h5" -> RouteKind.H5
                else -> throw AssertionError("$context.kind 出现未支持的快照值：$kind")
            }
        val expectedEntries =
            source.getValue("entry").jsonArray.map { it.jsonPrimitive.content }
        val actualEntries: List<RouteEntry> = actual.entries

        assertEquals("$context.name", name, actual.name)
        assertEquals("$context.kind", expectedKind, actual.kind)
        assertEquals("$context.auth", source.getValue("auth").jsonPrimitive.content, actual.auth)
        assertEquals("$context.sinceAndroid", source.androidVersion(), actual.sinceAndroid)
        assertEquals("$context.entry 的 raw 值及顺序", expectedEntries, actualEntries.map { it.raw })
        expectedEntries.forEachIndexed { index, raw ->
            assertEquals(
                "$context.entry[$index]($raw) 应映射到 RouteEntry",
                RouteEntry.fromRaw(raw),
                actualEntries[index],
            )
        }
        assertEquals("$context.h5Path", source["h5_path"].nullableString(), actual.h5Path)
        assertEquals("$context.debugOnly", source.booleanOrFalse("debug_only"), actual.debugOnly)
    }

    private fun readEnumSnapshots(): Map<String, List<String>> {
        val directory = File(repositoryRoot, "contracts/snapshot/contracts/enums")
        assertTrue("枚举快照目录不存在：$directory", directory.isDirectory)
        val files =
            directory.listFiles()
                ?.filter { it.isFile && it.extension == "json" }
                ?.sortedBy { it.name }
                ?: throw AssertionError("无法列出枚举快照目录：$directory")
        assertTrue("枚举快照目录没有 JSON 文件：$directory", files.isNotEmpty())

        val result = linkedMapOf<String, List<String>>()
        files.forEach { file ->
            at("枚举快照 ${file.name}") {
                val enums =
                    readSnapshot("contracts/enums/${file.name}").getValue("enums").jsonObject
                assertTrue("${file.name}.enums 不能为空", enums.isNotEmpty())
                enums.forEach { (key, element) ->
                    at("${file.name}.enums[$key].values") {
                        // values 是有序对象：只取键，保持快照顺序，不排序、不取描述。
                        val values = element.jsonObject.getValue("values").jsonObject.keys.toList()
                        assertTrue("${file.name}.enums[$key].values 不能为空", values.isNotEmpty())
                        assertNull(
                            "枚举键 $key 在多个快照文件中重复，当前文件 ${file.name}",
                            result.put(key, values),
                        )
                    }
                }
            }
        }
        return result
    }

    private fun snapshotFile(relativePath: String): File {
        val file = File(repositoryRoot, "contracts/snapshot/$relativePath")
        assertTrue("快照文件不存在：$file", file.isFile)
        return file
    }

    private fun readSnapshot(relativePath: String): JsonObject =
        at("contracts/snapshot/$relativePath") {
            Json.parseToJsonElement(snapshotFile(relativePath).readText(Charsets.UTF_8)).jsonObject
        }

    private fun JsonElement?.nullableString(): String? =
        if (this == null || this == JsonNull) null else jsonPrimitive.content

    private fun JsonElement?.nullableInt(): Int? =
        if (this == null || this == JsonNull) null else jsonPrimitive.int

    private fun JsonObject.booleanOrFalse(key: String): Boolean =
        this[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.boolean ?: false

    private fun JsonObject.objectOrEmpty(key: String): JsonObject =
        this[key]?.takeUnless { it == JsonNull }?.jsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.androidVersion(): String? =
        objectOrEmpty("since")["android"].nullableString()

    private fun tokenLeaves(
        node: JsonObject,
        prefix: String = "",
    ): Map<String, JsonElement> {
        val result = linkedMapOf<String, JsonElement>()
        node.forEach { (name, element) ->
            val path = if (prefix.isEmpty()) name else "$prefix.$name"
            at("令牌叶子 $path") {
                val child = element.jsonObject
                if ("value" in child) {
                    result[path] = child.getValue("value")
                } else {
                    result.putAll(tokenLeaves(child, path))
                }
            }
        }
        return result
    }

    private fun integerTokenLeaves(
        node: JsonObject,
        context: String,
    ): Map<String, Int> =
        tokenLeaves(node).mapValues { (key, value) ->
            at("$context.$key.value") { value.jsonPrimitive.int }
        }

    private fun <K> assertKeys(
        context: String,
        expected: Set<K>,
        actual: Set<K>,
    ) {
        assertEquals(
            "$context 键集合不一致；缺少=${expected - actual}；多出=${actual - expected}",
            expected,
            actual,
        )
    }

    private fun <K, V> assertMapEntries(
        context: String,
        expected: Map<K, V>,
        actual: Map<K, V>,
    ) {
        assertKeys(context, expected.keys, actual.keys)
        expected.forEach { (key, value) ->
            assertEquals("$context[$key]", value, actual.getValue(key))
        }
    }

    private inline fun <T> at(
        context: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (exception: Exception) {
            throw AssertionError("$context：${exception.message}", exception)
        }
}
