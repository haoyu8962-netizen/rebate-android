package com.couli.app.clipboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-X-CLIP-DEFAULT-ON 源码守卫（C2 / C3 / C4，变更记录 §2 第 1、4、5 行）：行为由其他用例覆盖，这里防回归。
 * 扫描范围：app/src/main 与 core/clipboard/src/main 的 .kt（去掉注释），不扫生成物（core/generated、tools/codegen）。
 */
class ClipDefaultOnSourceGuardTest {
    private val root: File by lazy {
        generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
            .first { File(it, "contract.lock").isFile }
    }

    private fun sources(): List<File> =
        listOf("app/src/main", "core/clipboard/src/main")
            .map { File(root, it) }
            .flatMap { dir ->
                assertTrue("缺目录 $dir", dir.isDirectory)
                dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }

    private fun stripComments(text: String): String =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(?<!:)//[^\n]*"""), "")

    private fun assertAbsent(
        reason: String,
        pattern: Regex,
    ) {
        val hits =
            sources().flatMap { file ->
                val text = stripComments(file.readText())
                pattern.findAll(text).map { "${file.relativeTo(root)}: ${it.value}" }.toList()
            }
        assertEquals("$reason：$hits", emptyList<String>(), hits)
    }

    @Test
    fun c2_consentNoticeSheetAndHostAreRemoved() {
        assertAbsent(
            "用途说明弹层与宿主已移除（C2）",
            Regex(
                """\b(?:ClipboardConsentHost|ClipboardConsentNotice|consentNotice|showNotice|onNoticeShown|onNoticeEnable""" +
                    """|onNoticeNotNow|manualNoticeNeeded|hasSeenNotice|markNoticeSeen)\b|\bClipboardNotice\s*\(""",
            ),
        )
        assertFalse(
            "ClipboardNotice.kt 已删除",
            File(root, "core/clipboard/src/main/kotlin/com/couli/core/clipboard/ClipboardNotice.kt").exists(),
        )
    }

    @Test
    fun c2_consentTextKeysAreUnreferenced() {
        assertAbsent("clipboard.consent.* 不再被引用（C2）", Regex("""clipboard\.consent\."""))
    }

    @Test
    fun c3_clipboardAutoImportIsNeverWritten() {
        assertAbsent("客户端不再写 clipboard_auto_import（C3）", Regex("""clipboard_auto_import"""))
    }

    @Test
    fun c4_consentVersionIsNotAGate() {
        assertAbsent("consent_version 不作门槛（C4）", Regex("""consent_version|currentConsentVersion"""))
    }
}
