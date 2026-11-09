@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.core.clipboard

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ImportGate0723VectorTest(
    private val name: String,
    private val row: JsonObject,
) {
    @Suppress("LongMethod") // 一条向量的布置、结算和同轮恢复放在一起，避免隐藏验收步骤。
    @Test
    fun gateAndSameEpochRecovery() =
        runTest {
            val state = row.getValue("state").jsonObject
            val f = ImportCoordinator0723Fixture(this, enabled = null)
            seedPreferences(f, state)
            f.environment =
                ImportEnvironment(
                    privacyAgreed = state.flag("privacyAgreed"),
                    configData = state.getValue("config").jsonObject,
                    page =
                        ImportPageScope(
                            pageInstanceId = "vector-page",
                            route = state.getValue("route").jsonPrimitive.content,
                            homeRoot = state.flag("homeRoot"),
                            ready = state.flag("pageReady"),
                            interactive = state.flag("interactive"),
                            focused = state.flag("focused"),
                        ),
                    modal = state.getValue("modal").jsonPrimitive.content,
                    webSuppressed = state.flag("webSuppressed"),
                )
            if (state.flag("foreground")) {
                f.coordinator.onLifecycle(ImportLifecycleEvent.PROCESS_FOREGROUND)
            }
            if (state.flag("consumed")) {
                // 本轮已消费的等价入口（2026-10-30 起原 onNoticeShown 随弹层取消，向量 fields.consumed）
                f.coordinator.markSkipped(requireNotNull(f.coordinator.snapshot().epoch))
            }
            repeat(3) { f.coordinator.onPageReady() }
            f.settle()
            val reads = f.reads.size
            val snapshot = f.coordinator.snapshot()
            val expect = row.getValue("expect").jsonObject
            assertEquals(name, expect.getValue("reads").jsonPrimitive.int, reads)
            // 用途说明弹层入口已移除（C2）；向量 notices 恒为 0，作回归守卫
            assertEquals(name, 0, expect.getValue("notices").jsonPrimitive.int)
            expect["pending"]?.let { assertEquals(name, it.jsonPrimitive.boolean, snapshot.pending) }
            expect["consumed"]?.let { assertEquals(name, it.jsonPrimitive.boolean, snapshot.consumed) }
            // 门槛不通过不能偷偷探测内容；通过时仍只走 read，端口未回调不许提取或上传。
            val extracted = f.extractions.size
            val parsed = f.parses.size
            val cards = f.cards.size
            assertEquals(name, 0, extracted)
            assertEquals(name, 0, parsed)
            assertEquals(name, 0, cards)
            row["afterReady"]?.jsonObject?.let { recovery ->
                f.environment =
                    f.environment.copy(
                        page =
                            ImportPageScope(
                                "vector-page",
                                recovery.getValue("route").jsonPrimitive.content,
                                recovery.flag("homeRoot"),
                                true,
                                true,
                                true,
                            ),
                        modal = "none",
                    )
                f.coordinator.onPageReady()
                f.settle()
                val after = f.reads.size
                assertEquals(name, recovery.getValue("reads").jsonPrimitive.int, after)
                assertEquals(name, 0, recovery.getValue("notices").jsonPrimitive.int)
            }
        }

    /**
     * 经真实入口建立 enabled 三态（向量 fields.preferenceSetup）；storedConsentVersion / seenVersion 已不经任何入口建立，这里把向量里的
     * 非 null 残留值直接写进旧键，端上残留也不得改变 reads（fields.storedConsentVersion/seenVersion、C4）。
     */
    private fun seedPreferences(
        f: ImportCoordinator0723Fixture,
        state: JsonObject,
    ) {
        state.textOrNull("storedConsentVersion")?.let { assertTrue(f.backend.write(mapOf("import.consent_version" to it))) }
        state.textOrNull("seenVersion")?.let { assertTrue(f.backend.write(mapOf("import.notice_seen_version" to it))) }
        val enabled =
            state
                .getValue("enabled")
                .takeUnless { it == JsonNull }
                ?.jsonPrimitive
                ?.boolean
        when (enabled) {
            true -> assertTrue(f.prefs.setOn())
            false -> assertTrue(f.prefs.setOff())
            null -> Unit
        }
        assertEquals("$name.enabled precondition", enabled, f.prefs.snapshot().enabled)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun vectors(): List<Array<Any>> {
            val root =
                generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
                    .first { File(it, "contract.lock").isFile }
            val document = Json.parseToJsonElement(File(root, "specs/clipboard-vectors.json").readText()).jsonObject
            val group = document.getValue("importGate").jsonObject
            assertEquals(2, group.getValue("schemaVersion").jsonPrimitive.int)
            val rows = group.getValue("cases").jsonArray.map { it.jsonObject }
            assertTrue(rows.isNotEmpty())
            val names = rows.map { it.getValue("name").jsonPrimitive.content }
            assertEquals(names.size, names.toSet().size)
            return rows
                .filter { r -> r.getValue("platforms").jsonArray.any { it.jsonPrimitive.content == "android" } }
                .map { arrayOf(it.getValue("name").jsonPrimitive.content, it) }
        }

        private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean

        private fun JsonObject.textOrNull(key: String): String? = getValue(key).takeUnless { it == JsonNull }?.jsonPrimitive?.content
    }
}
