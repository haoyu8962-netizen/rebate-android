@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.core.clipboard

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-X-CLIP-DEFAULT-ON（BR-ID-16 2026-10-30，变更记录 §2 第 1、2、4 行）：门控与开关的纯逻辑。
 * 签名：ImportPreferenceStore.isAutoSwitchOn() / setOn()；见 tests-android-handback §4。
 */
class ClipDefaultOnCoreTest {
    private fun configJson(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun c1_neverChosenIsOnAndExplicitChoicesRoundTrip() =
        runTest {
            val f = ImportCoordinator0723Fixture(this, enabled = null)
            assertTrue("从未选择按开启（C1）", f.prefs.isAutoSwitchOn())
            assertEquals(null, f.prefs.snapshot().enabled)
            assertTrue(f.prefs.setOff())
            assertFalse(f.prefs.isAutoSwitchOn())
            assertEquals(false, f.prefs.snapshot().enabled)
            assertTrue(f.prefs.setOn())
            assertTrue(f.prefs.isAutoSwitchOn())
            assertEquals(true, f.prefs.snapshot().enabled)
        }

    @Test
    fun c1_neverChosenDeviceReadsOnTheFirstForegroundWithoutNotice() =
        runTest {
            val f = ImportCoordinator0723Fixture(this, enabled = null)
            f.foreground()
            f.settle()
            assertEquals("默认开启：直接读取，不经用途弹层（C1 / C2）", 1, f.reads.size)
            assertTrue(f.coordinator.snapshot().consumed)
        }

    @Test
    fun c4_gateAndTaskIgnoreConsentVersionPresenceAndValue() =
        runTest {
            for (config in listOf(
                """{}""",
                """{"clipboard":{}}""",
                """{"clipboard":{"consent_version":null}}""",
                """{"clipboard":{"consent_version":""}}""",
                """{"clipboard":{"consent_version":"anything"}}""",
            )) {
                val f = ImportCoordinator0723Fixture(this, enabled = null)
                f.environment = f.environment.copy(configData = configJson(config))
                f.foreground()
                f.settle()
                assertEquals("config=$config 不影响读取（C4）", 1, f.reads.size)
            }
        }

    @Test
    fun c4_inFlightAutoTaskSurvivesConsentVersionChangeOrDisappearance() =
        runTest {
            for (stage in listOf("read", "parse")) {
                for (version in listOf("v2", null)) {
                    val f = ImportCoordinator0723Fixture(this)
                    val task = if (stage == "read") f.start() else f.parsing()
                    f.environment = f.environment.copy(configData = ImportCoordinator0723Fixture.config(version))
                    if (stage == "read") {
                        f.readText(task)
                        assertEquals("$stage/$version 提取照常发生", 1, f.extractions.size)
                        f.candidates(task)
                        assertEquals("$stage/$version 候选照常上传", listOf("old-candidate"), f.parses.single().second)
                    }
                    f.parsed(task)
                    assertEquals("$stage/$version 照常出卡", 1, f.cards.size)
                    f.assertFinished(task, "success")
                }
            }
        }

    @Test
    fun c5_configOnlySuppressesReadingAndNeverChangesTheUserSwitch() =
        runTest {
            val autoReadOff = configJson("""{"features":{"clipboard":{"auto_read":{"android":false}}}}""")
            val autoReadOn = configJson("""{"features":{"clipboard":{"auto_read":{"android":true}}}}""")
            val f = ImportCoordinator0723Fixture(this, enabled = null)
            f.environment = f.environment.copy(configData = autoReadOff)
            f.foreground()
            f.settle()
            assertEquals("服务端按端停用：不读", 0, f.reads.size)
            assertEquals("配置不改开关值", null, f.prefs.snapshot().enabled)
            assertTrue(f.prefs.isAutoSwitchOn())
            f.environment = f.environment.copy(configData = autoReadOn)
            f.coordinator.onPageReady()
            f.settle()
            assertEquals("配置恢复后同轮可读（pending 保留）", 1, f.reads.size)

            val off = ImportCoordinator0723Fixture(this, enabled = false)
            off.environment = off.environment.copy(configData = autoReadOn)
            off.foreground()
            off.settle()
            assertEquals("配置开不能打开用户的关闭（只能关不能开）", 0, off.reads.size)
            assertEquals(false, off.prefs.snapshot().enabled)
        }
}
