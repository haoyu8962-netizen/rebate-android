package com.couli.core.router

import com.couli.generated.RouteParamType
import com.couli.generated.Routes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CT-LOCK-02：routes.json 的 Agreement.params 改形状——type 枚举加 labor_agreement、新增可选 version（integer，minimum 1）。
 * 生成器转写 integer / minimum（原来遇到 minimum 直接失败），RouteParams 按 JSON Schema 校验整数与下限；
 * 本端调用方只传 type（agreement / privacy / sdk_list），行为不变；深链的值一律是字符串，integer 参数照旧不能从深链进入。
 */
class CtLock02AgreementRouteParamsTest {
    private val agreement = checkNotNull(Routes.spec("Agreement"))

    private fun params(vararg pairs: Pair<String, JsonPrimitive>) = JsonObject(pairs.toMap())

    private fun valid(vararg pairs: Pair<String, JsonPrimitive>) = RouteParams.isValid(agreement, params(*pairs))

    @Test
    fun generatedSpecCarriesIntegerVersionWithMinimum() {
        val type = agreement.params.single { it.name == "type" }
        assertEquals(RouteParamType.STRING, type.type)
        // FU-X-HOME-PAGE-API test-change（#24）：锁 00deded0ef6f 的 routes.json Agreement.type 追加 collect_list
        assertEquals(listOf("agreement", "privacy", "sdk_list", "labor_agreement", "collect_list"), type.enumValues)
        assertNull(type.minimum)
        val version = agreement.params.single { it.name == "version" }
        assertEquals(RouteParamType.INTEGER, version.type)
        assertFalse(version.required)
        assertEquals(1L, version.minimum)
        // 全部路由里只有 Agreement.version 带 minimum、是 integer
        val withMinimum = Routes.all.values.flatMap { spec -> spec.params.filter { it.minimum != null }.map { spec.name to it.name } }
        assertEquals(listOf("Agreement" to "version"), withMinimum)
        val integers = Routes.all.values.flatMap { spec -> spec.params.filter { it.type == RouteParamType.INTEGER }.map { spec.name } }
        assertEquals(listOf("Agreement"), integers)
    }

    @Test
    fun existingCallersAndNewContractValuesPass() {
        assertTrue("no params", valid())
        for (type in listOf("agreement", "privacy", "sdk_list", "labor_agreement")) {
            assertTrue(type, valid("type" to JsonPrimitive(type)))
        }
        assertFalse("unknown type", valid("type" to JsonPrimitive("terms")))
        assertFalse("extra param", valid("type" to JsonPrimitive("privacy"), "lang" to JsonPrimitive("zh")))
    }

    @Test
    fun versionMinimumAndIntegerAreEnforced() {
        for (ok in listOf(JsonPrimitive(1), JsonPrimitive(3), JsonPrimitive(2.0), JsonPrimitive(Long.MAX_VALUE))) {
            assertTrue("version $ok", valid("type" to JsonPrimitive("agreement"), "version" to ok))
        }
        val bad =
            listOf(
                JsonPrimitive(0),
                JsonPrimitive(-1),
                JsonPrimitive(0.0),
                JsonPrimitive(1.5),
                JsonPrimitive("3"),
                JsonPrimitive(true),
                JsonNull,
            )
        for (no in bad) {
            assertFalse("version $no", valid("type" to JsonPrimitive("agreement"), "version" to no))
        }
    }

    /** 复审 r1：科学计数法与极端指数不抛异常（原实现对 100e2147483647 去尾零时抛 ArithmeticException）。 */
    @Test
    fun scientificNotationAndExtremeExponentsDoNotThrow() {
        fun version(raw: String): Boolean {
            val parsed = Json.parseToJsonElement("""{"type":"agreement","version":$raw}""").jsonObject
            return RouteParams.isValid(agreement, parsed)
        }
        for (ok in listOf("100e2147483647", "1e2147483647", "1E+3", "1.5e1", "10e-1", "2.50e1")) {
            assertTrue("version $ok", version(ok))
        }
        for (no in listOf("1.5e0", "1e-2147483647", "1e-2147483648", "-1e2147483647", "-100e2147483647", "0e5", "1e-1")) {
            assertFalse("version $no", version(no))
        }
    }

    @Test
    fun deepLinkKeepsRejectingIntegerParams() {
        val config = DeepLinkConfig(schemes = listOf("couli"), hosts = listOf("m.example.test"))
        val ok = DeepLinkParser.parse("couli://r/Agreement?type=labor_agreement", config)
        assertNotNull(ok)
        assertEquals("labor_agreement", (ok!!.params["type"] as JsonPrimitive).content)
        assertNotNull(DeepLinkParser.parse("https://m.example.test/r/Agreement?type=privacy", config))
        // 查询串里的 version 是字符串 "3"，不是 integer：拒绝（与「非 string 参数不能从深链进入」的既有规则一致）
        assertNull(DeepLinkParser.parse("couli://r/Agreement?type=agreement&version=3", config))
    }
}
