package com.couli.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-X-STAGING-HOSTS-android 测试 v3（D10-598 ①「PROD 不设 stream、行为与底版一致」；实现 v2 变异 M6b 存活的补漏）：
 * release 变体的 currentStreamBaseUrl 只能原样返回 ProdEndpoints 的值（或直接 null），不得自己兜底任何地址；ProdEndpoints 不给
 * streamBaseUrl 赋值。testDebug 编不到 release 源码集，行为用例另在 app/src/testRelease（ReleaseStreamBaseTest），这里按源码核对。
 */
class StagingStreamReleaseSourceTest {
    private val root: File by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
    }

    private fun read(path: String) = File(root, path).readText()

    /** 去掉块注释、行注释与全部空白。 */
    private fun code(text: String) =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
            .replace(Regex("""\s+"""), "")

    private val release = "app/src/release/kotlin/com/couli/app/VariantEnvironment.kt"

    private companion object {
        /** 去空白后的签名（尾逗号可有可无），后接表达式体。 */
        const val SIGNATURE = """internalfuncurrentStreamBaseUrl\(context:Context,cleaners:EnvironmentSwitchCleanerRegistry,?\):String\?="""
        const val BODY_UNTIL_NEXT_DECLARATION = """(.*?)(?=@Suppress|internalfun|privatefun|fun|$)"""
    }

    @Test fun r01ReleaseStreamBaseOnlyReturnsTheProdValueOrNull() {
        val compact = code(read(release))
        val body =
            Regex(SIGNATURE + BODY_UNTIL_NEXT_DECLARATION)
                .find(compact)
                ?.groupValues
                ?.get(1)
        assertTrue("release 须有表达式体的 currentStreamBaseUrl(context, cleaners): String? = …", body != null)
        assertTrue(
            "release 的 currentStreamBaseUrl 只能是 ProdEndpoints.endpoints.streamBaseUrl 或 null，不得兜底（?:、字面量、别的来源）：$body",
            body in setOf("ProdEndpoints.endpoints.streamBaseUrl", "null"),
        )
    }

    @Test fun r02ReleaseSourceSetHasOneDefinitionAndNoAddressLiteral() {
        val sources =
            File(root, "app/src/release")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()
        val definitions = sources.sumOf { Regex("""fun\s+currentStreamBaseUrl\s*\(""").findAll(it.readText()).count() }
        assertEquals("release 源码集只有一处 currentStreamBaseUrl", 1, definitions)
        val text = read(release)
        assertFalse("release 的 VariantEnvironment.kt 不得出现任何地址字面量", Regex(""""[^"\n]*://""").containsMatchIn(text))
        assertFalse("release 的 VariantEnvironment.kt 不得出现 stream / stg 主机", Regex("""stream\.|-stg\.""").containsMatchIn(code(text)))
    }

    @Test fun r03ProdEndpointsLeavesStreamBaseUnset() {
        val compact = code(read("core/config/src/main/kotlin/com/couli/core/config/AppEnvironment.kt"))
        val prod = compact.substringAfter("objectProdEndpoints{", missingDelimiterValue = "")
        assertTrue("找到 ProdEndpoints", prod.isNotEmpty())
        assertFalse("ProdEndpoints 不给 streamBaseUrl 赋值（PROD 不设 stream，D10-598 ①）", prod.contains("streamBaseUrl"))
        assertTrue("streamBaseUrl 缺省为 null", compact.contains("valstreamBaseUrl:String?=null"))
    }
}
