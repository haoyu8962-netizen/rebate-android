package com.couli.feature.web

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(
            listOf("EVENT_RESUME)", "EVENT_VISIBLE, visible(true))", "EVENT_VISIBLE, visible(false))", "EVENT_PAUSE)", ""),
            emits,
        )
        // auth.changed 的数据只有 logged_in；page.visible 的数据只有 visible
        assertFalse(Regex("""mapOf\("(?!logged_in"|visible")""").containsMatchIn(controller))
    }

    @Test
    fun webModuleNeverTouchesTheClipboard() {
        sources.listFiles().orEmpty().filter { it.extension == "kt" }.forEach { file ->
            val text = code(file.name)
            assertFalse("${file.name} 读写剪贴板", "ClipboardManager" in text || "primaryClip" in text || "setPrimaryClip" in text)
        }
    }
}
