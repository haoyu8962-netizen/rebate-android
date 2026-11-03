package com.couli.app.me

import com.couli.core.me.MeJson
import com.couli.core.me.NicknameInput
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G-S18：specs/me-vectors.json 只在末尾新增 visualSettings（三端同一份）；入场时的全部字节（含 visualMe 与 25 个业务组）原样作为前缀。
 * fixture 覆盖画板上出现的每类元素需要的数据；键名避开 token / secret / key / sentry / password（gitleaks）。
 */
class SettingsVisualFixtureTest {
    private val file = File(SettingsVisualFixtures.root, "specs/me-vectors.json")

    @Test fun existingGroupsAreAByteExactPrefixAndVisualSettingsIsLast() {
        val text = file.readText()
        val at = text.indexOf(",\n  \"visualSettings\": ")
        assertTrue("visualSettings 只能追加在末尾", at > 0 && text.endsWith("\n}\n"))
        assertEquals("入场 5a795b5 的 me-vectors 全部字节不变（只追加）", PREFIX_SHA, sha(text.substring(0, at).toByteArray()))
        assertEquals("visualSettings 之后没有别的根级组", 1, Regex("\n  \"").findAll(text.substring(at)).count())
    }

    @Test fun visualProfilesAreLockedContractMeShapes() {
        listOf("settings_member", "settings_long_nickname", "settings_unbound").forEach { name ->
            val me = MeJson.me(SettingsVisualFixtures.profile(name))
            assertNotNull("锁定契约形状的 Me：$name", me)
        }
        assertEquals(SettingsVisualFixtures.original, MeJson.me(SettingsVisualFixtures.profile())!!.nickname)
        assertEquals(false, MeJson.me(SettingsVisualFixtures.profile("settings_unbound"))!!.phoneBound)
        assertEquals("200% 压力值 16 字", 16, MeJson.me(SettingsVisualFixtures.profile("settings_long_nickname"))!!.nickname.length)
    }

    @Test fun nicknameInputsCoverEmptyLimitSensitiveTooLongAndGraphemes() {
        val original = SettingsVisualFixtures.original
        assertTrue(NicknameInput.model(original, SettingsVisualFixtures.limitInput).saveEnabled)
        assertTrue(NicknameInput.model(original, SettingsVisualFixtures.sensitiveInput).saveEnabled)
        assertEquals(17, NicknameInput.model(original, SettingsVisualFixtures.tooLong).length)
        assertFalse(NicknameInput.model(original, SettingsVisualFixtures.tooLong).saveEnabled)
        assertEquals(16, NicknameInput.model(original, SettingsVisualFixtures.stress).length)
        assertEquals("ZWJ 一家人算 1 个字素", 16, NicknameInput.model(original, SettingsVisualFixtures.grapheme).length)
        assertTrue("字素串的 UTF-16 长度超过 16（截断会出错）", SettingsVisualFixtures.grapheme.length > 16)
        assertEquals(30415, SettingsVisualFixtures.limitCode)
        assertEquals("nickname_sensitive", SettingsVisualFixtures.sensitiveReason)
    }

    @Test fun sessionsRoutesClockAndBoardsMatchTheBrief() {
        assertEquals(12L * 1024 * 1024, SettingsVisualFixtures.cacheBytes())
        assertEquals("member", SettingsVisualFixtures.level("settings_member"))
        assertEquals("guest", SettingsVisualFixtures.level("guest_consented"))
        assertEquals("basic", SettingsVisualFixtures.level("basic"))
        assertTrue(SettingsVisualFixtures.consented("guest_consented"))
        assertFalse(SettingsVisualFixtures.consented("basic"))
        assertEquals(
            "公开版只多 DeleteAccount（G-S05）",
            setOf("DeleteAccount"),
            SettingsVisualFixtures.routes(true) - SettingsVisualFixtures.routes(false),
        )
        assertEquals("a1b2c3", SettingsVisualFixtures.trace.takeLast(6))
        assertEquals(
            "purpose-02",
            (SettingsVisualFixtures.clipboardConfig["clipboard"] as JsonObject)["consent_version"].toString().trim('"'),
        )
        val boards = SettingsVisualFixtures.boards()
        assertEquals("§1.1 有效对照稿 17 张（NotAsked 只 iOS、AuthManage 按 G-S01、通用状态按 G-M11 另列）", 17, boards.size)
        assertEquals("d076d94490d7", boards.getValue("Settings-v2")["sha12"].toString().trim('"'))
    }

    @Test fun fixtureNamesAvoidGitleaksWords() {
        val names = mutableListOf<String>()

        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (k, v) -> names += k.lowercase().also { walk(v) } }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(SettingsVisualFixtures.group)
        listOf("token", "secret", "key", "sentry", "password").forEach { word ->
            assertTrue("键名不得含 $word：${names.filter { it.contains(word) }}", names.none { it.contains(word) })
        }
        assertTrue(SettingsVisualFixtures.group.jsonObject.isNotEmpty())
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val PREFIX_SHA = "26e66903b619c47f75aab726f933e3f40429d48a1cf61a88a6b3b52f6186a2e8"
    }
}
