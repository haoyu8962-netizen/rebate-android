package com.couli.core.me

import com.couli.core.clientcore.PermissionTypeRaw
import com.couli.core.me.PrivacyCenterView.PermissionKind
import java.io.File
import java.lang.reflect.Modifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LOCK-android（D10-1612 2）：权限类型归类是完整的显式表（[PrivacyCenterView.PERMISSION_KINDS]），
 * 系统权限一节的判据看类型、不看 purpose_key。
 */
class LockPermissionKindsTest {
    private val root: File by lazy {
        val start = File(System.getProperty("user.dir") ?: throw AssertionError("找不到 user.dir")).canonicalFile
        generateSequence(start) { it.parentFile }.firstOrNull { File(it, "contract.lock").isFile }
            ?: throw AssertionError("找不到 contract.lock")
    }

    private val snapshotTypes: Set<String> by lazy {
        Json
            .parseToJsonElement(File(root, "contracts/snapshot/specs/system-permissions.json").readText(Charsets.UTF_8))
            .jsonObject
            .getValue("permissions")
            .jsonArray
            .map { it.jsonObject.getValue("type") }
            .map { it.jsonPrimitive.content }
            .toSet()
    }

    /** PermissionTypeRaw 的全部常量（反射取 static String 字段，新增常量也会被覆盖）。 */
    private val requesterTypes: Set<String> by lazy {
        PermissionTypeRaw::class
            .java
            .declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
            .toSet()
    }

    @Test fun everySnapshotPermissionTypeIsClassified() {
        val unclassified = snapshotTypes - PrivacyCenterView.PERMISSION_KINDS.keys
        assertTrue("快照权限清单有未归类的类型：$unclassified（在 PrivacyCenterView.PERMISSION_KINDS 补归类）", unclassified.isEmpty())
    }

    @Test fun everyRequesterTypeIsUserRequested() {
        assertTrue("PermissionTypeRaw 不为空", requesterTypes.isNotEmpty())
        requesterTypes.forEach { type ->
            assertEquals("PermissionTypeRaw.$type 归为向用户申请", PermissionKind.USER_REQUESTED, PrivacyCenterView.PERMISSION_KINDS[type])
        }
    }

    @Test fun userRequestedTypesAlignWithTheTable() {
        assertEquals(
            PrivacyCenterView.PERMISSION_KINDS.filterValues { it == PermissionKind.USER_REQUESTED }.keys,
            PrivacyCenterView.USER_REQUESTED_TYPES,
        )
        assertEquals("向用户申请的就是 PermissionRequester 能申请的类型", requesterTypes, PrivacyCenterView.USER_REQUESTED_TYPES)
    }

    @Test fun criterionIsTheTypeNotThePurposeKey() {
        val entries =
            listOf(
                entry(PermissionTypeRaw.PUSH, purposeKey = null),
                entry("network", purposeKey = "perm.network.system_purpose"),
            )
        val rows = PrivacyCenterView.model(MeLevel.MEMBER, MePlatform.ANDROID, entries, emptyMap()).permissionRows
        assertEquals("向用户申请 + 空 purpose_key 仍列出；安装即授予 + 非空 purpose_key 仍不列", listOf(PermissionTypeRaw.PUSH), rows.map { it.type })
        assertEquals(null, rows.single().purposeKey)
    }

    private fun entry(
        type: String,
        purposeKey: String?,
    ): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive(type),
                "platform" to JsonPrimitive(MePlatform.ANDROID),
                "system_key" to JsonPrimitive("synthetic.$type"),
                "purpose_key" to (purposeKey?.let(::JsonPrimitive) ?: JsonNull),
            ),
        )
}
