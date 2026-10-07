/*
 * 实现前测试；只读会话未写文件、未编译运行。
 *
 * 接线约定：
 * - BridgeProtocol(trustedOrigins: Set<String>, linkPatterns: JsonObject,
 *   shareDomains: Set<String>) 使用不可变注入配置，不能读取生产域或全局状态。
 * - companion 静态入口 parseEnvelope(raw: String) 返回 valid: Boolean、
 *   errorCode: Int?、method/id: String?、params: JsonObject?，非法输入不抛异常。
 *   未知方法可通过信封结构校验，再由 decide 返回 90001；缺省 params 为 {}。
 * - 实例 decide(ctx: BridgeContext)；Context 构造字段为 method、origin、
 *   isMainFrame、userGesture、privacy、loggedIn、params，params 使用 JsonObject。
 * - signedPathAllowed(method, path)、eventAllowed(name) 是静态布尔入口。
 * - ExternalNavPolicy(bundledLinkPatterns: JsonObject)；
 *   decide(url, isMainFrame, isNewWindow, linkPatterns: JsonObject?)
 *   返回 actionRaw: String、reason: String?。
 * - 缺少 linkPatterns 时直接传 null，由被测策略回落；测试不能预先替换。
 * - fixture_current/fixture_bundled 只提供输入，不从 expect 推导配置。
 * - allow 仅指协议准入，since/实际能力/Host 暂不支持另验，详见 vectors.gaps。
 * - 成功 errorCode=null；拒绝且未登记期望码时不比较，不能臆造隐私错误码。
 * - 适配层只转型转发，不得复制规则；无需 Android API 或 Robolectric。
 * - 模块需接入现有版本目录中的 JUnit4 与 kotlinx.serialization.json 测试依赖。
 */
package com.couli.core.bridge

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeVectorTest {
    private val vectors: JsonObject by lazy {
        val start =
            File(
                System.getProperty("user.dir")
                    ?: throw AssertionError("缺少 user.dir"),
            ).canonicalFile
        val root =
            generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "contract.lock").isFile }
                ?: throw AssertionError("从 $start 向上找不到 contract.lock")
        val file = File(root, "specs/bridge-vectors.json")
        Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject.also {
            assertEquals("version", 1, it.getValue("version").jsonPrimitive.int)
            assertTrue("sources", it.getValue("sources").jsonArray.isNotEmpty())
            assertTrue("gaps", it.getValue("gaps").jsonArray.isNotEmpty())
        }
    }

    @Test
    fun manifestAndMethodCoverage() {
        val methods =
            vectors.getValue("envelope").jsonArray
                .map { it.jsonObject.string("name") }
                .filter { it.startsWith("valid:") }
                .map { it.removePrefix("valid:") }
                .toSet()
        assertTrue("缺少登记方法向量", methods.isNotEmpty())

        val access = vectors.getValue("access").jsonArray.map { it.jsonObject }
        for (method in methods) {
            val matching = access.filter { it.string("method") == method }
            val allows = matching.filter { it.getValue("expect").jsonObject.boolean("allow") }
            val denies = matching.filterNot { it.getValue("expect").jsonObject.boolean("allow") }
            assertTrue("$method: 缺少拒绝向量", denies.isNotEmpty())
            if (method == "ext.openApp") {
                assertTrue("现有目标全是 trade_only，不能伪造允许", allows.isEmpty())
            } else {
                assertTrue("$method: 缺少协议准入向量", allows.isNotEmpty())
            }
        }
    }

    @Test
    fun envelopeVectors() {
        each("envelope") { row, label ->
            val raw = row.string("raw")
            val expected = row.getValue("expect").jsonObject
            val valid = expected.boolean("valid")
            val actual = BridgeProtocol.parseEnvelope(raw)

            assertEquals("$label: valid", valid, actual.valid)
            checkCode(actual.errorCode, expected, valid, label)

            if (valid && actual.valid) {
                val input = Json.parseToJsonElement(raw).jsonObject
                assertEquals("$label: method", input.string("method"), actual.method)
                assertEquals("$label: id", input.string("id"), actual.id)
                assertNotNull("$label: params", actual.params)
                assertEquals(
                    "$label: params 必须保留 JSON 类型和值",
                    input["params"]?.jsonObject ?: JsonObject(emptyMap()),
                    actual.params,
                )
            }
        }
    }

    @Test
    fun accessVectors() {
        val bridge = makeBridge()
        each("access") { row, label ->
            val expected = row.getValue("expect").jsonObject
            val allow = expected.boolean("allow")
            val actual =
                bridge.decide(
                    BridgeContext(
                        method = row.string("method"),
                        origin = row.string("origin"),
                        isMainFrame = row.boolean("isMainFrame"),
                        userGesture = row.boolean("userGesture"),
                        privacy = row.string("privacy"),
                        loggedIn = row.boolean("loggedIn"),
                        params = row["params"]?.jsonObject ?: JsonObject(emptyMap()),
                    ),
                )
            assertEquals("$label: allow", allow, actual.allow)
            checkCode(actual.errorCode, expected, allow, label)
        }
    }

    @Test
    fun trustedOriginsAreInjected() {
        val injected = "https://injected.example.test:8443"
        val request =
            BridgeContext(
                method = "app.getEnv",
                origin = injected,
                isMainFrame = true,
                userGesture = false,
                privacy = "consented",
                loggedIn = false,
                params = JsonObject(emptyMap()),
            )

        val allowed = makeBridge(setOf(injected)).decide(request)
        assertTrue("注入的完整 origin 应生效", allowed.allow)
        assertNull(allowed.errorCode)

        val denied = makeBridge().decide(request)
        assertFalse("不能使用硬编码测试域放行", denied.allow)
        assertEquals(90403, denied.errorCode)

        val empty = makeBridge(emptySet()).decide(request)
        assertFalse("空白名单不得放行", empty.allow)
        assertEquals(90403, empty.errorCode)
    }

    @Test
    fun signedPathVectors() {
        each("signedPaths") { row, label ->
            val expected = row.getValue("expect").jsonObject
            val actual =
                BridgeProtocol.signedPathAllowed(
                    method = row.string("method"),
                    path = row.string("path"),
                )
            assertEquals(label, expected.boolean("allow"), actual)
        }
    }

    @Test
    fun eventVectors() {
        each("events") { row, label ->
            val expected = row.getValue("expect").jsonObject
            assertEquals(
                label,
                expected.boolean("deliver"),
                BridgeProtocol.eventAllowed(row.string("event")),
            )
        }
    }

    @Test
    fun externalNavigationVectors() {
        val bundled = fixture("fixture_bundled")
        each("externalNav") { row, label ->
            // 每行重建，防止远程规则残留污染回落测试。
            val policy = ExternalNavPolicy(bundledLinkPatterns = bundled)
            val expected = row.getValue("expect").jsonObject
            val actual =
                policy.decide(
                    url = row.string("url"),
                    isMainFrame = row.boolean("isMainFrame"),
                    isNewWindow = row.boolean("isNewWindow"),
                    linkPatterns = row["linkPatterns"]?.jsonObject,
                )
            assertEquals("$label: action", expected.string("action"), actual.actionRaw)
            if (expected.containsKey("reason")) {
                assertEquals("$label: reason", expected.string("reason"), actual.reason)
            }
        }
    }

    private fun makeBridge(
        origins: Set<String> = setOf("https://h5.example.test"),
    ): BridgeProtocol =
        BridgeProtocol(
            trustedOrigins = origins,
            linkPatterns = fixture("fixture_current"),
            shareDomains = setOf("s.example.test"),
        )

    private fun fixture(name: String): JsonObject {
        val row =
            vectors.getValue("externalNav").jsonArray
                .map { it.jsonObject }
                .singleOrNull { it.string("name") == name }
                ?: throw AssertionError("缺少或重复的规则夹具 $name")
        return row.getValue("linkPatterns").jsonObject
    }

    private fun checkCode(
        actual: Int?,
        expected: JsonObject,
        success: Boolean,
        label: String,
    ) {
        if (success) {
            assertNull("$label: 成功不携带错误码", actual)
        } else if (expected.containsKey("errorCode")) {
            assertEquals(
                "$label: errorCode",
                expected.getValue("errorCode").jsonPrimitive.int,
                actual,
            )
        }
    }

    private fun each(
        group: String,
        check: (JsonObject, String) -> Unit,
    ) {
        val rows = vectors.getValue(group).jsonArray
        assertTrue("$group: 向量不能为空", rows.isNotEmpty())
        val names = mutableSetOf<String>()

        rows.forEachIndexed { index, element ->
            val row = element.jsonObject
            val name = row.string("name")
            val label = "$group[$index] $name"
            assertTrue("$label: name", name.isNotEmpty())
            assertTrue("$label: 重复名称", names.add(name))
            if (group == "access" || group == "externalNav") {
                assertTrue("$label: note", row.string("note").isNotEmpty())
            }
            try {
                check(row, label)
            } catch (failure: AssertionError) {
                throw AssertionError(label, failure)
            } catch (failure: Exception) {
                throw AssertionError(label, failure)
            }
        }
    }

    private fun JsonObject.string(key: String): String =
        getValue(key).jsonPrimitive.let {
            if (!it.isString) {
                throw AssertionError("$key 应为字符串")
            }
            it.content
        }

    private fun JsonObject.boolean(key: String): Boolean =
        getValue(key).jsonPrimitive.boolean
}
