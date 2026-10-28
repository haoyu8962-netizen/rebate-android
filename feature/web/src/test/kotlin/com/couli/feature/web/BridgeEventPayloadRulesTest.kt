package com.couli.feature.web

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桥事件不带剪贴板内容（APP-07 brief §2.9；BR-ID-16 细则「不把剪贴板推给 H5」、bridge.schema.json events）：
 * - 可信容器下发的事件只有 app.resume / app.pause（无数据）、page.visible（只带 visible）、auth.changed（只带 logged_in）；
 * - feature:web 的源码不碰剪贴板（读剪贴板只有用户手势下的 clipboard.read，在 App 层，行为不变）。
 */
class BridgeEventPayloadRulesTest {
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
    fun eventsCarryOnlyContractFields() {
        val controller = code("TrustedWebViewController.kt")
        val emits = Regex("""transport\?\.emit\(([^\n]*)""").findAll(controller).map { it.groupValues[1].trim() }.toList()
        // auth.changed 不再经通用 emit 下发（F-X-25：只走切号协调器的专用投递 emitAuthChanged，载荷由协调器只放 logged_in）。
        // 测试 v3 独立确认（D10-206 / 编排 2289）：原期望末项 "" 对应旧 onAuthChanged 的多行 transport?.emit(EVENT_AUTH, …)，该方法
        // 无调用者且绕过文档令牌门控；F-X-25 设计「onAuthChanged 不再通用 emit」后删去末项是正当的，正向断言见下一个用例。
        assertEquals(
            listOf("EVENT_RESUME)", "EVENT_VISIBLE, visible(true))", "EVENT_VISIBLE, visible(false))", "EVENT_PAUSE)"),
            emits,
        )
        // auth.changed 的数据只有 logged_in；page.visible 的数据只有 visible
        assertFalse(Regex("""mapOf\("(?!logged_in"|visible")""").containsMatchIn(controller))
    }

    /**
     * 正向（D10-206 / 编排 2289 要求）：auth.changed 只经 BridgeTransport.emitAuthChanged 的专用投递发出——它按 EVENT_AUTH 组事件、
     * 要求文档令牌已绑定，并把协调器给的 data 原样交出（载荷只有 logged_in 由 core:bridge Fx25AuthInvariantsTest 逐字段比对）；
     * feature:web 任何源码都不再把 EVENT_AUTH 交给通用 emit，也不自己拼 logged_in 载荷。
     */
    @Test
    fun authChangedLeavesOnlyThroughTheDedicatedTokenGatedChannel() {
        val all =
            sources
                .listFiles()
                .orEmpty()
                .filter { it.extension == "kt" }
                .associate { it.name to code(it.name) }
        all.forEach { (name, text) ->
            assertFalse(
                "$name 把 auth.changed 交给通用 emit",
                Regex("""\.emit\(\s*(TrustedWebViewController\.)?EVENT_AUTH\b""").containsMatchIn(text),
            )
            assertFalse("$name 自己拼 auth.changed 载荷", Regex(""""logged_in"""").containsMatchIn(text))
        }
        val transport = all.getValue("BridgeTransport.kt")
        val dedicated = Regex("""fun emitAuthChanged\(([\s\S]*?)\n {4}}""").find(transport)
        assertTrue("BridgeTransport 必须有专用投递 emitAuthChanged", dedicated != null)
        val body = checkNotNull(dedicated).value
        assertTrue(
            "专用投递按 EVENT_AUTH 组事件",
            Regex("""BridgeProtocol\.event\(\s*(TrustedWebViewController\.)?EVENT_AUTH\s*,\s*data\s*\)""").containsMatchIn(body),
        )
        assertTrue("专用投递要求令牌已绑定当前文档", "documentBound" in body || "tokenDelivered" in body)
        val callers = all.filterValues { Regex("""\.emitAuthChanged\(\s*data\s*,""").containsMatchIn(it) }.keys
        assertTrue("文档的 notifyAuthChanged 把协调器给的 data 原样交给专用投递：$callers", callers.isNotEmpty())
        assertEquals(
            "每一处调用都原样转交协调器的 data",
            all.values.sumOf { Regex("""\.emitAuthChanged\(\s*data\s*,""").findAll(it).count() },
            all.values.sumOf { Regex("""\.emitAuthChanged\(""").findAll(it).count() },
        )
    }

    @Test
    fun webModuleNeverTouchesTheClipboard() {
        sources.listFiles().orEmpty().filter { it.extension == "kt" }.forEach { file ->
            val text = code(file.name)
            assertFalse("${file.name} 读写剪贴板", "ClipboardManager" in text || "primaryClip" in text || "setPrimaryClip" in text)
        }
    }
}
