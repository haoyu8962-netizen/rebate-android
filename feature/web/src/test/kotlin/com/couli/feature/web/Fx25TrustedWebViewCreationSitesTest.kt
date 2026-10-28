package com.couli.feature.web

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D10-310 (6)：setProfile 只能在 WebView 首次使用前调用，所以所有可信 WebView 的创建路径（WebScreens、DebugConformance、测试夹具、
 * 设置页清缓存的临时实例）必须经同一个工厂 [TrustedWebViewFactory]；漏一处就落回默认配置文件（变异 5 之外再守一道）。
 * 源码枚举创建点：控制器只由 TrustedWebPage 建，TrustedWebPage 只经工厂取 WebView，App 侧每个 TrustedWebScreen 都交协调器与工厂。
 * 设备侧另有注入计数（Fx25WebIsolationTest.i6EveryTrustedWebViewComesFromTheFactoryOnTheActiveStore）。
 */
class Fx25TrustedWebViewCreationSitesTest {
    private val root: File by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
    }

    private val web: File get() = File(root, "feature/web/src/main/kotlin/com/couli/feature/web")

    private fun kotlinFiles(relative: String): List<File> =
        File(root, relative)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /** 去掉注释行，避免说明文字里的类名被当成调用。 */
    private fun code(file: File): String =
        file
            .readLines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
            .joinToString("\n")

    private val production: List<File> by lazy {
        kotlinFiles("feature/web/src/main") + kotlinFiles("app/src/main") + kotlinFiles("app/src/debugFeatures")
    }

    private val controllerConstruction = Regex("""(?<![\w.])TrustedWebViewController\(""")
    private val rawWebView = Regex("""(?<![\w.])WebView\((?!\))""")

    @Test
    fun i6ControllersAreBuiltOnlyByTheTrustedPage() {
        val sites =
            production
                .filter { file -> controllerConstruction.findAll(code(file)).any { !isDeclaration(code(file), it.range.first) } }
                .map { it.name }
                .toSet()
        assertEquals("可信控制器只能由 TrustedWebPage（其 WebView 来自工厂）构造", setOf("TrustedWebPage.kt"), sites)
    }

    @Test
    fun i6TrustedPageAndSettingsCacheObtainWebViewsOnlyFromTheFactory() {
        val factory = File(web, "TrustedWebViewFactory.kt")
        assertTrue("唯一工厂 TrustedWebViewFactory.kt", factory.isFile)
        val factoryCode = code(factory)
        assertTrue(Regex("""class TrustedWebViewFactory\(""").containsMatchIn(factoryCode))
        assertTrue("工厂在交出 WebView 前 setProfile", "WebViewCompat.setProfile(" in factoryCode)
        for (name in listOf("TrustedWebPage.kt", "WebViewCaches.kt")) {
            val text = code(File(web, name))
            assertFalse("$name 不得自己 new WebView（会落回默认配置文件）", rawWebView.containsMatchIn(text))
            assertTrue("$name 经工厂取 WebView", "TrustedWebViewFactory" in text || Regex("""\.create\(""").containsMatchIn(text))
        }
        val setProfileSites =
            production
                .filter { "WebViewCompat.setProfile(" in code(it) }
                .map { it.name }
                .toSet()
        assertEquals(
            "可信侧只有工厂设配置文件（外部容器用自己的 couli_external）",
            setOf("TrustedWebViewFactory.kt", "ExternalWebViewController.kt"),
            setProfileSites,
        )
    }

    @Test
    fun i6EveryAppTrustedScreenPassesTheCoordinatorAndTheFactory() {
        val calls =
            (kotlinFiles("app/src/main") + kotlinFiles("app/src/debugFeatures")).flatMap { file ->
                val text = code(file)
                Regex("""(?<![\w.])TrustedWebScreen\(""")
                    .findAll(text)
                    .map { match ->
                        file.name to argumentsOf(text, match.range.last)
                    }.toList()
            }
        assertTrue("App 至少有路由与一致性页两处可信容器", calls.size >= 2)
        calls.forEach { (file, args) ->
            assertTrue("$file 的 TrustedWebScreen 必须交切号协调器", Regex("""\bauthSync\s*=""").containsMatchIn(args))
            assertTrue("$file 的 TrustedWebScreen 必须交可信 WebView 工厂", Regex("""\bwebViews\s*=""").containsMatchIn(args))
        }
    }

    @Test
    fun i6Fx25DeviceFixturesNeverBypassTheFactory() {
        val fixtures =
            kotlinFiles("feature/web/src/androidTest")
                .filter { it.name.startsWith("Fx25") }
        assertTrue(fixtures.isNotEmpty())
        fixtures.forEach { file ->
            assertFalse("${file.name} 直接构造可信控制器", controllerConstruction.containsMatchIn(code(file)))
        }
    }

    private fun isDeclaration(
        text: String,
        index: Int,
    ): Boolean = text.substring(maxOf(0, index - CLASS_KEYWORD_WINDOW), index).trimEnd().endsWith("class")

    /** 从左括号起按括号配对取出整个实参列表（含多行）。 */
    private fun argumentsOf(
        text: String,
        open: Int,
    ): String {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> {
                    depth += 1
                }

                ')' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
        }
        return text.substring(open)
    }

    private companion object {
        const val CLASS_KEYWORD_WINDOW = 12
    }
}
