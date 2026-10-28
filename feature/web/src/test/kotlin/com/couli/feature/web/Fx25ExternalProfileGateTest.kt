package com.couli.feature.web

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 裁定 D10-402 条件①：F 路径（系统 WebView 不支持 MULTI_PROFILE）对默认配置文件 deleteBrowsingData，前提是
 * 「F 设备不在默认配置文件里打开外部容器」。设备测试都 assumeTrue(cookieIsolationSupported()) 跳过了这一面，
 * 而 cookieIsolationSupported() 是直接读 WebViewFeature 的静态函数、没有可注入的接缝，所以这里用源码规则钉住整条链路（不跳过）：
 * - 隔离只在 MULTI_PROFILE 可用、且 setProfile 到独立配置文件成功时为真；任何异常都算失败，配置文件名不是默认的；
 * - 隔离失败时控制器不装任何 client、不改设置，available = false，open 在任何 loadUrl 之前走 onUnavailable 并返回；
 * - 控制器里所有 loadUrl 只在 open（已过闸）与 onNewWindow（只由隔离成功后才装上的 Chrome 调用）里；没有别的加载入口；
 * - 新窗口探针是本文件唯一的 new WebView，先隔离、失败即销毁返回 false；
 * - 外部页面入口（WebScreens.createExternalWebView）把新 WebView 原样交给控制器，构造前不碰它，不可用回调只显示错误层。
 */
class Fx25ExternalProfileGateTest {
    private val root: File by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
    }

    private val web: File get() = File(root, "feature/web/src/main/kotlin/com/couli/feature/web")

    /** 去掉注释行，避免说明文字被当成调用。 */
    private fun code(file: File): String =
        file
            .readLines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
            .joinToString("\n")

    private val external: String by lazy { code(File(web, "ExternalWebViewController.kt")) }

    @Test
    fun d10402IsolationNeedsMultiProfileAndASuccessfulSetProfileOntoTheExternalProfile() {
        val supported = bodyAfter(external, "fun cookieIsolationSupported()", expression = true)
        assertTrue("cookieIsolationSupported 必须按 MULTI_PROFILE 判定", "WebViewFeature.MULTI_PROFILE" in supported)
        assertFalse("cookieIsolationSupported 不得写死为真", Regex("""^\s*=\s*true\b""").containsMatchIn(supported))

        val isolate = blockAfter(external, "private fun isolateCookies(")
        assertTrue(
            "isolateCookies 的第一句必须是：不支持 MULTI_PROFILE 就返回 false",
            Regex("""^\{\s*if\s*\(\s*!\s*cookieIsolationSupported\(\)\s*\)\s*return\s+false\b""").containsMatchIn(isolate),
        )
        val attempt = Regex("""runCatching\s*\{([^}]*)\}\s*\.isSuccess""").find(isolate)
        assertTrue("setProfile 必须包在 runCatching 里、以 isSuccess 作结果（任何异常都算隔离失败）", attempt != null)
        val setProfileCall = Regex("""WebViewCompat\.setProfile\(\s*webView\s*,\s*PROFILE\s*\)""")
        assertTrue("runCatching 里必须 setProfile 到 PROFILE", setProfileCall.containsMatchIn(attempt!!.groupValues[1]))
        assertFalse("隔离失败不得被当作成功", Regex("""return\s+true\b|getOrDefault\(\s*true|\?:\s*true""").containsMatchIn(isolate))

        val profile = Regex("""const\s+val\s+PROFILE\s*=\s*"([^"]*)"""").find(external)?.groupValues?.get(1)
        assertTrue("外部配置文件名必须非空", !profile.isNullOrEmpty())
        // androidx.webkit 的默认配置文件名是 "Default"（Profile.DEFAULT_PROFILE_NAME）
        assertNotEquals("外部配置文件不得就是默认配置文件", "Default", profile)
        val setProfileArgs = Regex("""setProfile\(\s*\w+\s*,\s*([^)]*)\)""").findAll(external).map { it.groupValues[1].trim() }.toList()
        assertTrue("本文件至少有一处 setProfile", setProfileArgs.isNotEmpty())
        assertEquals("本文件只把 WebView 放进 PROFILE", setOf("PROFILE"), setProfileArgs.toSet())
    }

    @Test
    fun d10402ControllerWiresNothingWhenIsolationFails() {
        val classStart = external.indexOf("class ExternalWebViewController(")
        val firstIsolate = external.indexOf("isolateCookies(webView)")
        assertTrue(classStart >= 0 && firstIsolate > classStart)
        assertFalse(
            "构造到 isolateCookies(webView) 之前不得使用 webView（setProfile 只能在首次使用前调用，否则落回默认配置文件）",
            Regex("""\bwebView\s*\.""").containsMatchIn(external.substring(classStart, firstIsolate)),
        )
        val init = blockAfter(external, "init ")
        assertTrue("init 先取隔离结果", Regex("""^\{\s*val\s+isolated\s*=\s*isolateCookies\(webView\)""").containsMatchIn(init))
        assertTrue("available 只等于隔离结果", Regex("""\bavailable\s*=\s*isolated\s*$""", RegexOption.MULTILINE).containsMatchIn(init))
        assertEquals("available 只在 init 里赋值一次", 1, Regex("""(?<![\w.])available\s*=(?!=)""").findAll(external).count())

        val gated = blockAfter(init, "if (isolated) ")
        val wirings =
            listOf(
                "webView.webViewClient",
                "webView.webChromeClient",
                "setDownloadListener",
                "applyContainerSettings",
                "Chrome()",
                "Client()",
            )
        for (wiring in wirings) {
            // 只认调用本身（Chrome()、Client() 前不能是标识符字符或点，排除 WebChromeClient() 之类的父类构造）
            val guard = if (wiring.first().isUpperCase()) """(?<![\w.])""" else ""
            val pattern = Regex(guard + Regex.escape(wiring))
            val total = pattern.findAll(external).count()
            val inside = pattern.findAll(gated).count()
            assertTrue("$wiring 必须出现", total > 0)
            assertEquals("$wiring 只能在 if (isolated) 里（隔离失败时不装任何 client、不改设置）", total, inside)
        }
    }

    @Test
    fun d10402OpenReportsUnavailableBeforeAnyLoadWhenIsolationFailed() {
        val open = blockAfter(external, "fun open(")
        val gateAt = open.indexOf("if (")
        assertTrue("open 第一句就是可用性闸门", gateAt >= 0 && open.substring(1, gateAt).isBlank())
        val condition = open.substring(gateAt, open.indexOf('{', gateAt))
        assertTrue("闸门条件含 !available（隔离失败即不可用）", Regex("""!\s*available\b""").containsMatchIn(condition))
        assertTrue("!available 必须是或条件的一支，不能被 && 削弱", "&&" !in condition)
        val gate = blockAfter(open, condition)
        assertTrue("不可用时走 onUnavailable（错误层）", "callbacks.onUnavailable()" in gate)
        assertTrue("不可用时 onUnavailable 之后立即返回", Regex("""callbacks\.onUnavailable\(\)\s*return\b""").containsMatchIn(gate))
        assertFalse("闸门里不得加载", "loadUrl" in gate || "reload" in gate)
        val firstLoad = open.indexOf("loadUrl")
        assertTrue("open 的 loadUrl 在闸门之后", firstLoad > open.indexOf(gate) + gate.length)
    }

    @Test
    fun d10402EveryLoadIsBehindTheGateOrInsideTheIsolatedChrome() {
        for (other in listOf("loadData", "postUrl", "loadDataWithBaseURL", "evaluateJavascript")) {
            assertFalse("外部容器不得有 $other 加载入口", "$other(" in external)
        }
        val loadSites = Regex("""\.loadUrl\(""").findAll(external).map { enclosingFunction(external, it.range.first) }.toList()
        assertTrue("至少有 open 的加载", "open" in loadSites)
        assertEquals("loadUrl 只能在 open 与 onNewWindow 里", emptySet<String>(), loadSites.toSet() - setOf("open", "onNewWindow"))

        val chrome = blockAfter(external, "private inner class Chrome")
        val callers = Regex("""(?<!fun )\bonNewWindow\(""").findAll(external).toList()
        assertTrue(callers.isNotEmpty())
        val chromeStart = external.indexOf(chrome)
        callers.forEach { call ->
            assertTrue(
                "onNewWindow 只能由 Chrome（隔离成功后才装上）调用",
                call.range.first > chromeStart && call.range.first < chromeStart + chrome.length,
            )
        }
        val retry = blockAfter(external, "fun retry(")
        assertFalse("retry 不得绕过 open 直接加载", "loadUrl" in retry)
        assertTrue("retry 在没加载过页面时重走 open（重新过闸）", Regex("""\bopen\(url\)""").containsMatchIn(retry))
    }

    @Test
    fun d10402NewWindowProbeIsIsolatedFirstOrDestroyed() {
        val raw = Regex("""(?<![\w.])WebView\((?!\))""").findAll(external).toList()
        assertEquals("本文件只有探针一处 new WebView", 1, raw.size)
        val window = blockAfter(external, "override fun onCreateWindow(")
        val created = window.indexOf("WebView(view.context)")
        val check = window.indexOf("if (!isolateCookies(probe))")
        assertTrue("探针建好后先隔离", created >= 0 && check > created)
        assertFalse("隔离前不得使用探针", Regex("""\bprobe\s*\.""").containsMatchIn(window.substring(created, check)))
        val failed = blockAfter(window, "if (!isolateCookies(probe)) ")
        assertTrue("探针隔离失败：销毁并返回 false（不开新窗口）", Regex("""probe\.destroy\(\)\s*return\s+false\b""").containsMatchIn(failed))
    }

    @Test
    fun d10402ExternalScreenHandsAFreshWebViewToTheControllerAndShowsErrorWhenUnavailable() {
        val screens = code(File(web, "WebScreens.kt"))
        val create = blockAfter(screens, "private fun createExternalWebView(")
        val construct = create.indexOf("ExternalWebViewController(webView")
        assertTrue("外部入口用新 WebView 构造控制器", construct >= 0)
        assertFalse(
            "构造控制器（setProfile）之前不得使用这个 WebView",
            Regex("""\bwebView\s*\.""").containsMatchIn(create.substring(0, construct)),
        )
        assertFalse("外部入口不得自己加载", "loadUrl" in create)
        assertTrue("加载只经 controller.open", create.indexOf("controller.open(url)") > construct)
        val unavailable = blockAfter(create, "override fun onUnavailable()")
        assertTrue("不可用 → 错误层", Regex("""holder\.failed\s*=\s*true""").containsMatchIn(unavailable))
        assertFalse("不可用回调里不得再打开或加载", Regex("""open\(|loadUrl|reload\(""").containsMatchIn(unavailable))

        val sites =
            root
                .walkTopDown()
                .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.invariantSeparatorsPath.contains("/src/main/") || it.invariantSeparatorsPath.contains("/src/debugFeatures/") }
                .filter { file -> Regex("""(?<![\w.]|class )ExternalWebViewController\(""").containsMatchIn(code(file)) }
                .map { it.name }
                .toSet()
        assertEquals("生产代码只有外部页面入口构造外部控制器", setOf("WebScreens.kt"), sites)
    }

    /** [marker] 之后第一个左花括号起的整个块（含花括号）。 */
    private fun blockAfter(
        text: String,
        marker: String,
    ): String {
        val at = text.indexOf(marker)
        assertTrue("找不到 $marker", at >= 0)
        val open = text.indexOf('{', at + marker.length)
        assertTrue("$marker 之后没有块", open >= 0)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> {
                    depth += 1
                }

                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
        }
        return text.substring(open)
    }

    /** 表达式体函数 `fun x(): T = …` 的 `= …` 部分（到行尾）。 */
    @Suppress("SameParameterValue")
    private fun bodyAfter(
        text: String,
        marker: String,
        expression: Boolean,
    ): String {
        val at = text.indexOf(marker)
        assertTrue("找不到 $marker", at >= 0)
        val line = text.substring(at, text.indexOf('\n', at).takeIf { it >= 0 } ?: text.length)
        val eq = line.indexOf('=')
        assertTrue("$marker 应是表达式体", !expression || eq >= 0)
        return line.substring(eq)
    }

    /** 位置 [index] 所在的最内层具名函数。 */
    private fun enclosingFunction(
        text: String,
        index: Int,
    ): String =
        Regex("""\bfun\s+(\w+)\s*\(""")
            .findAll(text.substring(0, index))
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            .orEmpty()
}
