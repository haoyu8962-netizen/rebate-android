package com.couli.buildlogic

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** G1 独立警告通道；旧硬规则仍走 check()，不能为了 DS-02 把全局失败吞掉。 */
class Ds02ComponentGuardTest {
    private val root = Files.createTempDirectory("ds02-component-guard").toFile()
    private val path = "feature/demo/src/main/kotlin/demo/Screen.kt"
    private val forbidden =
        listOf(
            "androidx.compose.material3.Checkbox",
            "androidx.compose.material3.Switch",
            "androidx.compose.material3.RadioButton",
            "androidx.compose.material3.TextField",
            "androidx.compose.material3.OutlinedTextField",
            "androidx.compose.material3.AlertDialog",
            "androidx.compose.material3.ModalBottomSheet",
            "androidx.compose.material3.TopAppBar",
            "androidx.compose.material3.CenterAlignedTopAppBar",
            "androidx.compose.material3.NavigationBar",
            "androidx.compose.material3.FilterChip",
            "androidx.compose.material3.SegmentedButton",
            "androidx.compose.foundation.text.BasicTextField",
        )

    @After fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun allThirteenSystemControlsWarnForDirectAliasQualifiedAndWildcardUse() {
        forbidden.forEach { name ->
            val short = name.substringAfterLast('.')
            listOf(
                "import $name\nfun screen() { $short() }",
                "import $name as PlatformControl\nfun screen() { PlatformControl() }",
                "fun screen() { $name() }",
                "import ${name.substringBeforeLast('.')}.*\nfun screen() { $short() }",
            ).forEach { source ->
                write(path, "package demo\n$source\n")
                val warnings = CouliSourceRules.warnings(root, scanningRepository = false)
                assertTrue(
                    "未警告 $name：$source\n$warnings",
                    warnings.any {
                        "[design-system-required]" in it && short in it && path in it
                    },
                )
                assertEquals("G1 现在只警告，不得进入失败列表", emptyList<String>(), CouliSourceRules.check(root, false))
            }
        }
    }

    @Test
    fun commentsStringsLocalNamesAndDesignSystemWrappersAreLegal() {
        write(
            path,
            """
            package demo
            import com.couli.core.designsystem.CouliSwitch
            // androidx.compose.material3.Switch(false, {})
            /* import androidx.compose.material3.Checkbox */
            val example = "androidx.compose.material3.TextField()"
            fun Switch() = Unit
            fun screen() { Switch(); CouliSwitch() }
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), CouliSourceRules.warnings(root, false))
    }

    @Test
    fun designSystemAndTestsMayCallPlatformControls() {
        listOf(
            "core/designsystem/src/main/kotlin/demo/Control.kt",
            "core/network/src/main/kotlin/demo/Control.kt",
            "app/src/main/kotlin/demo/Control.kt",
            "app/src/debugFeatures/kotlin/demo/Control.kt",
            "feature/demo/src/test/kotlin/demo/ControlTest.kt",
            "feature/demo/src/testDebug/kotlin/demo/ControlTest.kt",
            "feature/demo/src/androidTest/kotlin/demo/ControlTest.kt",
        ).forEach { write(it, "package demo\nimport androidx.compose.material3.Switch\nfun sample() { Switch() }\n") }
        assertEquals(emptyList<String>(), CouliSourceRules.warnings(root, false))
    }

    @Test
    fun warningDoesNotDowngradeExistingHardErrors() {
        write(path, "package demo\nimport androidx.compose.material3.Switch\nfun sample() { Switch(); val text = \"硬编码中文\" }\n")
        assertTrue(CouliSourceRules.warnings(root, false).any { "[design-system-required]" in it })
        assertTrue(CouliSourceRules.check(root, false).any { "[no-cjk-string-in-feature]" in it })
    }

    private fun write(
        path: String,
        source: String,
    ) {
        File(root, path).apply {
            parentFile.mkdirs()
            writeText(source)
        }
    }
}
