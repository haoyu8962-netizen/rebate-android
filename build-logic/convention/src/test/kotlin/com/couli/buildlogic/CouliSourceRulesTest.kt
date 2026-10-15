package com.couli.buildlogic

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * FU-AND-0715 第 1 轮评审 R1-04 / R1-05：规则本体 [CouliSourceRules.check]（couliRules 任务直接调用它）在真实目录上的 fail-closed。
 * - R1-04：`.java` 用 Java 词法（块注释不嵌套）：`/* /* */ SentryAndroid.init(context); // */` 里的调用是代码，必须报 SDK 违规；
 * - R1-05：一个正常源码文件 + 一个列不出内容的源码目录 → `[scan-incomplete]`（不静默跳过子树）。
 * - R2-02：单独的回车（U+000D）在 Java / Kotlin 里都是行终止符，结束行注释、普通字符串、字符字面量；
 * - R2-03：目录能列出名字但条目属性查不了（chmod 444：有读、没有执行 / 搜索权限）→ `[scan-incomplete]`，不当成「不是文件」丢掉。
 */
class CouliSourceRulesTest {
    private val root: File = Files.createTempDirectory("fuand0715-r1-rules").toFile()
    private val locked = mutableListOf<File>()

    @After
    fun cleanUp() {
        locked.forEach {
            it.setReadable(true, false)
            it.setExecutable(true, false)
        }
        root.deleteRecursively()
    }

    private fun write(
        relative: String,
        text: String,
    ): File =
        File(root, relative).apply {
            parentFile.mkdirs()
            writeText(text)
        }

    @Test
    fun javaBlockCommentsDoNotNestSoTheSdkInitAfterTheFirstCloseIsReported() {
        write(
            "app/src/main/java/com/couli/app/Init.java",
            "package com.couli.app;\n\nclass Init {\n    void run(Object context) {\n" +
                "        /* /* */ SentryAndroid.init(context); // */\n    }\n}\n",
        )
        val result = CouliSourceRules.check(root, scanningRepository = false)
        assertEquals(1, result.size)
        assertTrue(result.single(), result.single().contains("[sdk-init-only-in-privacy-gate]"))
        assertTrue(result.single(), result.single().startsWith("app/src/main/java/com/couli/app/Init.java:5:"))
    }

    @Test
    fun javaLexerKeepsKotlinOnlyRulesApart() {
        // Java 没有字符串模板：${...} 是文字；Kotlin 的嵌套块注释照旧
        val java = scanJavaSource("String s = \"\${SentryAndroid.init(c)}\"; /* a /* b */ int x = 1;")
        assertFalse(java.code.contains("SentryAndroid.init"))
        assertTrue(java.code.contains("int x = 1;"))
        assertEquals(emptyList<ScanProblem>(), java.problems)
        assertFalse(scanKotlinSource("/* /* */ SentryAndroid.init(context); // */").code.contains("SentryAndroid"))
        // 文本块：转义的引号不结束；内容里的调用不是代码
        val q = "\"\"\""
        val block = scanJavaSource("String t = $q\n  a \\$q SentryAndroid.init(c)\n  $q; Sentry.init(o);")
        assertFalse(block.code.contains("SentryAndroid.init"))
        assertTrue(block.code.contains("Sentry.init(o);"))
        assertEquals(emptyList<ScanProblem>(), block.problems)
    }

    @Test
    fun unsupportedJavaConstructsAndLanguagesAreIncomplete() {
        assertTrue(scanJavaSource("char c = '\\u0027'; SentryAndroid.init(c);").problems.any { "Unicode" in it.reason })
        assertTrue(scanJavaSource("/* never closed\nSentryAndroid.init(c);").problems.any { it.reason == "未闭合的块注释" })
        assertTrue(scanJavaSource("String t = \"\"\"inline\"\"\";").problems.isNotEmpty())
        assertTrue(scanJavaSource("int `x` = 1;").problems.isNotEmpty())
        assertEquals(null, scanSource("scala", "object A"))
        // 转义的反斜杠之后的 u 不是 Unicode 转义
        assertEquals(emptyList<ScanProblem>(), scanJavaSource("String s = \"\\\\u\";").problems)
    }

    @Test
    fun anUntraversableSourceDirectoryNextToANormalFileIsScanIncomplete() {
        write("feature/home/src/main/kotlin/Ok.kt", "package ok\n\nval ok = 1\n")
        val hidden = File(root, "feature/search/src/main/kotlin/hidden")
        write("feature/search/src/main/kotlin/hidden/Init.kt", "fun f(c: Any) = SentryAndroid.init(c)\n")
        assertTrue(hidden.setReadable(false, false))
        assertTrue(hidden.setExecutable(false, false))
        locked += hidden
        assumeTrue("以 root 运行时权限位不生效，此用例不适用", hidden.listFiles() == null)

        val result = CouliSourceRules.check(root, scanningRepository = false)
        assertTrue("目录列不出内容必须报扫不完整：$result", result.any { it.contains("[scan-incomplete]") && "hidden" in it })
        // 恢复可读后照常扫到里面的违规
        hidden.setReadable(true, false)
        hidden.setExecutable(true, false)
        assertTrue(CouliSourceRules.check(root, scanningRepository = false).single().contains("[sdk-init-only-in-privacy-gate]"))
    }

    @Test
    fun aNormalTreePassesAndTheEmptyOrMissingRootIsIncomplete() {
        write("feature/home/src/main/kotlin/Ok.kt", "package ok\n\nval ok = 1\n")
        write("app/src/main/java/Ok.java", "class Ok { String s = \"/* not a comment */\"; }\n")
        assertEquals(emptyList<String>(), CouliSourceRules.check(root, scanningRepository = false))
        assertTrue(CouliSourceRules.check(File(root, "missing"), scanningRepository = false).single().contains("[scan-incomplete]"))
        val empty = File(root, "empty").apply { mkdirs() }
        assertTrue(CouliSourceRules.check(empty, scanningRepository = false).single().contains("[scan-incomplete]"))
    }

    // ---------------------------------------------------------------- R2-02：单独回车

    @Test
    fun aLoneCarriageReturnEndsAJavaLineCommentSoTheSdkInitAfterItIsReported() {
        val file =
            write(
                "app/src/main/java/com/couli/app/Init.java",
                "package com.couli.app;\n\nclass Init {\n    void run(Object c) {\n        // comment\rSentryAndroid.init(c);\n    }\n}\n",
            )
        val bytes = String(file.readBytes(), Charsets.UTF_8)
        assertTrue("样例真的含单独回车字节", Regex("\r(?!\n)").containsMatchIn(bytes))
        val result = CouliSourceRules.check(root, scanningRepository = false)
        assertEquals(result.toString(), 1, result.size)
        // 行号按 Java 行终止符算：回车之后是第 6 行
        val expected = "app/src/main/java/com/couli/app/Init.java:6: [sdk-init-only-in-privacy-gate]"
        assertTrue(result.single(), result.single().startsWith(expected))
    }

    @Test
    fun loneCarriageReturnsAndCrlfAreLineTerminatorsForEveryJavaAndKotlinToken() {
        val cr = "\r"
        // 行注释：Java 与 Kotlin 都在单独回车处结束
        val java = scanJavaSource("// a${cr}SentryAndroid.init(c);")
        assertTrue(java.code.contains("SentryAndroid.init(c);"))
        assertEquals(emptyList<ScanProblem>(), java.problems)
        assertTrue(scanKotlinSource("// a${cr}SentryAndroid.init(c)").code.contains("SentryAndroid.init(c)"))
        // CRLF 只算一次换行；行注释照旧到行尾结束
        val crlf = scanJavaSource("// a$cr\nSentryAndroid.init(c);$cr\n// b$cr\nint x;")
        assertTrue(crlf.code.contains("SentryAndroid.init(c);"))
        assertEquals("与源码等长", "// a$cr\nSentryAndroid.init(c);$cr\n// b$cr\nint x;".length, crlf.code.length)
        // 普通字符串、字符字面量：遇单独回车未闭合（扫不完整），回车之后是代码
        val string = scanJavaSource("String s = \"abc${cr}SentryAndroid.init(c);")
        assertTrue(string.problems.any { it.reason == "未闭合的字符串" && it.line == 1 })
        assertTrue(string.code.contains("SentryAndroid.init(c);"))
        val char = scanJavaSource("char c = 'a${cr}SentryAndroid.init(c);")
        assertTrue(char.problems.any { it.reason == "未闭合的字符字面量" })
        assertTrue(char.code.contains("SentryAndroid.init(c);"))
        assertTrue(scanKotlinSource("val s = \"abc${cr}SentryAndroid.init(c)").problems.isNotEmpty())
        // 文本块：开头三引号后单独回车算换行（合法），内容跨单独回车，结束后的调用是代码
        val q = "\"\"\""
        val block = scanJavaSource("String t = $q$cr  SentryAndroid.init(a)$cr  $q; Sentry.init(o);")
        assertEquals(emptyList<ScanProblem>(), block.problems)
        assertFalse(block.code.contains("SentryAndroid.init"))
        assertTrue(block.code.contains("Sentry.init(o);"))
        // 块注释里的单独回车只计行号；行号与 LF 版一致
        assertEquals(
            scanJavaSource("/* a\n b */ x\n'").problems.map { it.line },
            scanJavaSource("/* a$cr b */ x$cr'").problems.map { it.line },
        )
    }

    // ---------------------------------------------------------------- R2-03：可列名但查不了条目属性

    @Test
    fun anEntryThatCanBeListedButWhoseAttributesCannotBeQueriedIsScanIncomplete() {
        write("feature/home/src/main/kotlin/Ok.kt", "package ok\n\nval ok = 1\n")
        val dir = File(root, "feature/search/src/main/kotlin/locked")
        write("feature/search/src/main/kotlin/locked/Hidden.kt", "fun f(c: Any) = SentryAndroid.init(c)\n")
        write("feature/search/src/main/kotlin/locked/nested/Deeper.kt", "fun g(c: Any) = Sentry.init(c)\n")
        // chmod 444：保留读、去掉执行 / 搜索权限
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("r--r--r--"))
        locked += dir
        assumeTrue(
            "以 root 运行时权限位不生效，此用例不适用",
            dir.list()?.toSet() == setOf("Hidden.kt", "nested") && !File(dir, "Hidden.kt").isFile && !File(dir, "nested").isDirectory,
        )

        val result = CouliSourceRules.check(root, scanningRepository = false)
        assertTrue("可列名但属性查不了的源码文件必须报扫不完整：$result", result.any { "[scan-incomplete]" in it && "locked/Hidden.kt" in it })
        assertTrue("可列名但属性查不了的子目录也必须报：$result", result.any { "[scan-incomplete]" in it && "locked/nested" in it })
        assertFalse(result.any { "Ok.kt" in it })
        // 恢复权限后照常扫到里面的两处违规
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        val restored = CouliSourceRules.check(root, scanningRepository = false)
        assertEquals(restored.toString(), 2, restored.count { "[sdk-init-only-in-privacy-gate]" in it })
        assertFalse(restored.any { "[scan-incomplete]" in it })
    }
}
