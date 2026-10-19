package com.couli.buildlogic

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 补足像素换肤的边界：颜色常量只准留在主题 / 令牌定义，组件不自己写主题分支。 */
class Ds02ColorDisciplineTest {
    private val forbiddenColors =
        Regex(
            """\bColor\s*\(|\bColor\s*\.\s*(?!Transparent\b|Unspecified\b)(?:[A-Z]\w*|parseColor|valueOf)""" +
                """|\b(?:colorResource|isSystemInDarkTheme)\s*\(""",
        )

    @Test
    fun allColorSpellingsAreCaughtWhileCommentsAndStringsAreIgnored() {
        listOf(
            "Color(0xff112233)",
            "Color.Red",
            "colorResource(R.color.brand)",
            "android.graphics.Color.parseColor(\"#112233\")",
            "Color.valueOf(1f, 0f, 0f)",
            "android.graphics.Color . valueOf(1f)",
            "isSystemInDarkTheme()",
        ).forEach { source ->
            assertTrue("漏查：$source", forbiddenColors.containsMatchIn(scanKotlinSource(source).code))
        }
        listOf(
            "Color.Transparent",
            "Color.Unspecified",
            "// colorResource(R.color.red)",
            "/* android.graphics.Color.parseColor(\"red\") */",
            "val text = \"Color.Red\"",
        ).forEach { source ->
            assertTrue("误报：$source", !forbiddenColors.containsMatchIn(scanKotlinSource(source).code))
        }
    }

    @Test
    fun componentSourcesDoNotConstructColorsOrPickTheirOwnTheme() {
        val repository =
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .first { File(it, "contract.lock").isFile }
        val directory = File(repository, "core/designsystem/src/main/kotlin/com/couli/core/designsystem")
        val tokenDefinitions = setOf("CouliColors.kt", "CouliTheme.kt", "CouliThemeTokens.kt", "OAuthAssets.kt")
        val files = directory.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in tokenDefinitions }.toList()
        assertTrue(files.isNotEmpty())
        val offenders =
            files.flatMap { file ->
                val scanned = scanKotlinSource(file.readText())
                assertEquals("必须完整解析：$file", emptyList<ScanProblem>(), scanned.problems)
                forbiddenColors
                    .findAll(scanned.code)
                    .map { "${file.name}: ${it.value}" }
                    .toList()
            }
        assertEquals("组件内硬编码颜色 / 自行分主题：$offenders", emptyList<String>(), offenders)
    }
}
