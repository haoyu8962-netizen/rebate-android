package com.couli.buildlogic

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ds02GuardBaselineTest {
    @Test
    fun existingViolationBaselineExistsAndCanOnlyShrinkFromFrozenCeiling() {
        val root =
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .first { File(it, "contract.lock").isFile }
        val baseline = File(root, "tools/ci/design-system-required-baseline.txt")
        assertTrue("由实现方生成现有 G1 违规基线；格式 path|ControlName，重复行表示多个调用", baseline.isFile)
        val ceiling =
            requireNotNull(javaClass.getResourceAsStream("/ds02-guard-baseline-ceiling.txt"))
                .bufferedReader()
                .use { lines(it.readText()) }
        assertTrue("基线不能新增路径、控件或同类调用次数", onlyShrinks(lines(baseline.readText()), ceiling))
    }

    @Test
    fun ceilingRejectsNewEntriesAndDuplicatesButAllowsMigration() {
        val ceiling = listOf("feature/a/A.kt|Switch", "feature/a/A.kt|Checkbox")
        assertTrue(onlyShrinks(ceiling.take(1), ceiling))
        assertTrue(onlyShrinks(emptyList(), ceiling))
        assertFalse(onlyShrinks(ceiling + "feature/b/B.kt|Switch", ceiling))
        assertFalse(onlyShrinks(ceiling + ceiling.first(), ceiling))
    }

    private fun lines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun onlyShrinks(
        current: List<String>,
        ceiling: List<String>,
    ): Boolean {
        val allowed = ceiling.groupingBy { it }.eachCount()
        return current.groupingBy { it }.eachCount().all { (entry, count) -> count <= (allowed[entry] ?: 0) }
    }
}
