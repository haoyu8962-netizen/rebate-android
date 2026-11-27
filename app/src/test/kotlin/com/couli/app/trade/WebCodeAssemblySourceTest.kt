package com.couli.app.trade

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-TB-WEBCODE-A 测试：web_code 授权器的装配（设计稿 §3.2、§7）。CI 只跑 testDebugUnitTest，编不到 release 源码集，release 按源码核对
 * （同 StagingStreamReleaseSourceTest 的做法）：
 * - release 的 variantWebCodeAuthorizer(context, real) 直接返回 real，release 源码集只剩这一处定义、不再引用 UnavailableWebCodeAuthorizer；
 * - AppModule 构造 BrowserWebCodeAuthorizer 并把它交给 variantWebCodeAuthorizer(context, real)；
 * - CouliApplication 把入站回跳交给收件箱（实现 WebCodeCallbackSinkProvider）。
 * 真实授权器 available 恒为 true 的行为见 BrowserWebCodeAuthorizerTest.a01。
 */
class WebCodeAssemblySourceTest {
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

    @Test
    fun v01_releaseReturnsTheRealAuthorizer() {
        val compact = code(read(RELEASE))
        val body =
            Regex(SIGNATURE + BODY_UNTIL_NEXT_DECLARATION)
                .find(compact)
                ?.groupValues
                ?.get(1)
        assertTrue(
            "release 须有表达式体的 variantWebCodeAuthorizer(context: Context, real: WebCodeAuthorizer): WebCodeAuthorizer = …",
            body != null,
        )
        assertEquals("release 直接用真实授权器（设计稿 §7）", "real", body)
    }

    @Test
    fun v02_releaseHasOneDefinitionAndNoUnavailableAuthorizer() {
        val sources =
            File(root, "app/src/release")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()
        val definitions = sources.sumOf { Regex("""fun\s+variantWebCodeAuthorizer\s*\(""").findAll(it.readText()).count() }
        assertEquals("release 源码集只有一处 variantWebCodeAuthorizer（旧的单参数装配删掉）", 1, definitions)
        assertFalse(
            "release 不再引用 UnavailableWebCodeAuthorizer",
            sources.any { code(it.readText()).contains("UnavailableWebCodeAuthorizer") },
        )
    }

    @Test
    fun v03_appModuleHandsTheRealAuthorizerToTheVariant() {
        val module = code(read("app/src/main/kotlin/com/couli/app/AppModule.kt"))
        assertTrue(
            "AppModule 用两参数的 variantWebCodeAuthorizer(context, real)",
            Regex("""variantWebCodeAuthorizer\(\w+,""").containsMatchIn(module),
        )
        assertFalse("不再用单参数的旧装配", Regex("""variantWebCodeAuthorizer\(\w+\)""").containsMatchIn(module))
        val constructs =
            File(root, "app/src/main/kotlin")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .any { code(it.readText()).contains("BrowserWebCodeAuthorizer(") && !it.name.startsWith("BrowserWebCodeAuthorizer") }
        assertTrue("app/src/main 里有地方构造 BrowserWebCodeAuthorizer", constructs)
    }

    @Test
    fun v04_applicationProvidesTheInboxSink() {
        val application = code(read("app/src/main/kotlin/com/couli/app/CouliApplication.kt"))
        assertTrue("CouliApplication 实现 WebCodeCallbackSinkProvider", application.contains("WebCodeCallbackSinkProvider"))
        assertTrue("CouliApplication 给出 webCodeCallbackSink()", application.contains("overridefunwebCodeCallbackSink()"))
    }

    private companion object {
        const val RELEASE = "app/src/release/kotlin/com/couli/app/trade/VariantTrade.kt"

        /** 去空白后的签名（尾逗号可有可无），后接表达式体。 */
        const val SIGNATURE = """internalfunvariantWebCodeAuthorizer\(context:Context,real:WebCodeAuthorizer,?\):WebCodeAuthorizer="""
        const val BODY_UNTIL_NEXT_DECLARATION = """(.*?)(?=@Suppress|internalfun|privatefun|fun|$)"""
    }
}
