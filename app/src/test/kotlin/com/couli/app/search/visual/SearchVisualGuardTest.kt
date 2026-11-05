package com.couli.app.search.visual

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 新守卫；只读 63934c8，不修改任何已合并测试 / 基线。Q11 仅放开 AppSearch.searchConfig 的 texts 取值行。 */
class SearchVisualGuardTest {
    private val root = SearchVisualFixtures.root
    private val base =
        Json
            .parseToJsonElement(
                checkNotNull(javaClass.classLoader?.getResource("search-visual-a/guard-baseline.json")).readText(),
            ).jsonObject

    @Test fun baselineCoversExecutorsAndFrozenSearchBehaviorTests() {
        assertEquals("63934c8487a32b3af3a485e583f9943eb811f957", base.text("base"))
        val paths = base.getValue("files").jsonArray.map { it.jsonObject.text("p") }
        listOf("SearchViewModel.kt", "SearchController.kt", "SearchProductFlow.kt", "ProductCardMapper.kt").forEach { name ->
            assertTrue(name, paths.any { it.endsWith("/$name") })
        }
        assertEquals(11, paths.count { it.startsWith("feature/search/src/test/") })
        assertFalse(paths.any { it.endsWith("/SearchScreen.kt") || it.endsWith("/AppSearch.kt") })
    }

    @Test fun executorsOwnershipPurchaseDesignSystemContractsAndOldTestsAreByteIdentical() {
        base.getValue("files").jsonArray.forEach { entry ->
            val row = entry.jsonObject
            assertEquals(setOf("p", "h"), row.keys)
            val file = File(root, row.text("p"))
            assertTrue("文件仍在：$file", file.isFile)
            assertEquals(row.text("p"), row.text("h"), sha(file.readBytes()))
        }
    }

    @Test fun immutableDirectoriesCannotGainOrLoseFiles() {
        val expected = base.getValue("files").jsonArray.map { it.jsonObject.text("p") }
        base.getValue("directories").jsonArray.forEach { entry ->
            val dir = entry.jsonPrimitive.content
            val actual =
                File(root, dir)
                    .walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(root).invariantSeparatorsPath }
                    .toSet()
            assertEquals(dir, expected.filter { it.startsWith("$dir/") }.toSet(), actual)
        }
    }

    @Test fun redLine7ListKeysLeadingCountsRestoreAndReservedHeightAreByteIdentical() {
        base.getValue("spans").jsonArray.forEach { entry ->
            val row = entry.jsonObject
            val src = File(root, row.text("p")).readText()
            val start = src.indexOf(row.text("start"))
            assertTrue("起点必须保留：${row.text("n")}", start >= 0)
            val end = src.indexOf(row.text("end"), start)
            assertTrue("终点必须保留：${row.text("n")}", end > start)
            assertEquals("逐字冻结：${row.text("n")}", row.text("h"), sha(src.substring(start, end).toByteArray()))
        }
    }

    @Test fun callbackExpressionsAndExecutorReferenceSetsAreUnchangedAcrossAllViewFiles() {
        val source = viewSource()
        val squeezed = squeeze(source)
        base.getValue("callbacks").jsonArray.forEach { entry ->
            val row = entry.jsonObject
            val needle = squeeze(row.text("s"))
            assertEquals(row.text("s"), row.getValue("count").jsonPrimitive.int, squeezed.windowed(needle.length).count { it == needle })
        }
        val refs = Regex("\\b(?:model|config\\.actions|model\\.config\\.actions)(?:(?:::|\\.)\\w+)+")
        val expected =
            base
                .getValue("viewReferences")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()
        assertEquals(expected, refs.findAll(noComments(source)).map { it.value }.toSet())
        assertFalse("视图不另发请求", Regex("\\b(?:send|searchProducts|parseInput)\\s*\\(").containsMatchIn(noComments(source)))
    }

    @Test fun appWiringOnlyAllowsQ11TextProviderExpression() {
        val src = File(root, "app/src/main/kotlin/com/couli/app/search/AppSearch.kt").readText()
        val start = src.indexOf("    fun searchConfig(")
        val end = src.indexOf("    private fun searchSlots(", start)
        assertTrue(start >= 0 && end > start)
        val region = src.substring(start, end)
        val pattern = Regex("^            texts = [^\\n]+,\\n", RegexOption.MULTILINE)
        assertEquals("唯一可换的接线行", 1, pattern.findAll(region).count())
        val normalized = src.take(start) + region.replace(pattern, "            texts = <Q11>,\n") + src.substring(end)
        assertEquals("请求、购买、归属、路由与剪贴板接线原样", base.text("appSearchQ11Normalized"), sha(normalized.toByteArray()))
    }

    @Test fun draftAndContractKeysCannotBeAddedOrChanged() {
        val draft = File(root, "app/src/main/kotlin/com/couli/app/search/SearchDraftTexts.kt").readText()
        val keys =
            Regex("\"([^\"\\n]+)\" to ")
                .findAll(draft)
                .map { it.groupValues[1] }
                .sorted()
                .toList()
        assertEquals(base.getValue("draftKeys").jsonArray.map { it.jsonPrimitive.content }, keys)
        // 整份草稿与 contracts/snapshot 另有字节守卫；新视图只能取现有字典键。
        val known = keys.toSet() + com.couli.generated.DefaultTexts.allKeys
        val literals = Regex("(?:text|format)\\(\"([^\"]+)\"").findAll(noComments(viewSource())).map { it.groupValues[1] }
        literals.forEach { assertTrue("不得臆造键：$it", it in known) }
    }

    private fun viewSource(): String =
        File(root, "feature/search/src/main/kotlin/com/couli/feature/search")
            .walkTopDown()
            .filter {
                it.extension == "kt" &&
                    it.name !in setOf("SearchViewModel.kt", "SearchController.kt", "SearchProductFlow.kt", "SearchFeature.kt")
            }.sortedBy { it.name }
            .joinToString("\n") { it.readText() }

    private fun noComments(s: String): String = s.replace(Regex("/\\*[\\s\\S]*?\\*/|//[^\\n]*"), "")

    private fun squeeze(s: String): String = noComments(s).replace(Regex("\\s+"), "")

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
}
