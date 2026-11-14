package com.couli.core.me

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LOCK-android（D10-1606 ①）：隐私中心「系统权限」一节只列向用户申请的权限。锁定契约 system-permissions v3 起 Android 有
 * android.permission.INTERNET（type network）等安装即授予的条目，它们不得出现在这一节。
 */
class LockPrivacyPermissionRowsTest {
    private val root: File by lazy {
        val start = File(System.getProperty("user.dir") ?: throw AssertionError("找不到 user.dir")).canonicalFile
        generateSequence(start) { it.parentFile }.firstOrNull { File(it, "contract.lock").isFile }
            ?: throw AssertionError("找不到 contract.lock")
    }

    private val entries: List<JsonObject> by lazy {
        Json
            .parseToJsonElement(File(root, "contracts/snapshot/specs/system-permissions.json").readText(Charsets.UTF_8))
            .jsonObject
            .getValue("permissions")
            .jsonArray
            .map { it.jsonObject }
    }

    @Test
    fun internetIsInTheSnapshotButNotInTheSystemPermissionSection() {
        val internet =
            entries.filter {
                it["platform"]?.jsonPrimitive?.content == MePlatform.ANDROID &&
                    it["system_key"]?.jsonPrimitive?.content == "android.permission.INTERNET"
            }
        assertEquals("快照里有 Android 的 INTERNET 条目（前提）", 1, internet.size)
        val internetType =
            internet
                .single()
                .getValue("type")
                .jsonPrimitive.content
        for (level in listOf(MeLevel.BASIC, MeLevel.GUEST, MeLevel.MEMBER)) {
            val rows = PrivacyCenterView.model(level, MePlatform.ANDROID, entries, emptyMap()).permissionRows
            assertTrue("$level：系统权限一节不含 INTERNET（$internetType）", rows.none { it.type == internetType })
            assertTrue("$level：只列向用户申请的类型", rows.all { it.type in PrivacyCenterView.USER_REQUESTED_TYPES })
        }
    }
}
