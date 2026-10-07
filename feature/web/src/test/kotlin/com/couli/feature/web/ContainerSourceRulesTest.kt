package com.couli.feature.web

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 容器封装的源码约束（03 §5.2、CSB-03、CSB-10）：
 * - ExternalWebView 的封装里不出现执行脚本、文档启动脚本、消息通道、addJavascriptInterface 的调用；
 * - 整个 feature:web 不用 addJavascriptInterface，不出现放行证书错误的调用；
 * - 可信容器的启动脚本只拼入包内契约的版本与方法名（不含任何地址）。
 */
class ContainerSourceRulesTest {
    private val sources: File by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        File(root, "feature/web/src/main/kotlin/com/couli/feature/web")
    }

    private fun code(name: String): String =
        File(sources, name)
            .readLines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
            .joinToString("\n")

    @Test
    fun externalContainerInjectsNothing() {
        val external = code("ExternalWebViewController.kt")
        for (forbidden in listOf(
            "evaluateJavascript",
            "addDocumentStartJavaScript",
            "addWebMessageListener",
            "addJavascriptInterface",
            "loadUrl(\"javascript",
            "postWebMessage",
            "createWebMessageChannel",
        )) {
            assertFalse("ExternalWebView wrapper calls $forbidden", forbidden in external)
        }
    }

    @Test
    fun noJavascriptInterfaceAndNoProceedOnCertificateErrors() {
        sources.listFiles().orEmpty().filter { it.extension == "kt" }.forEach { file ->
            val text = code(file.name)
            assertFalse("${file.name} uses addJavascriptInterface", "addJavascriptInterface" in text)
            assertFalse("${file.name} proceeds on certificate errors", "handler.proceed" in text || ".proceed()" in text)
        }
    }

    @Test
    fun bootstrapScriptCarriesOnlyBundledContractData() {
        val script = BridgeTransport.bootstrapScript(1, listOf("app.getEnv", "ui.toast"))
        assertTrue(script.contains("__REBATE_BRIDGE__"))
        assertTrue(script.contains("[\"app.getEnv\",\"ui.toast\"]"))
        assertFalse("bootstrap script carries no address", Regex("https?://").containsMatchIn(script))
    }

    @Test
    fun userAgentKeepsTheSystemDefaultAndOnlyAppendsTheAppSuffix() {
        val system = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"
        assertEquals("$system Couli/1.0.0", userAgent(system, "1.0.0"))
    }
}
