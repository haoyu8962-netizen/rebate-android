package com.couli.core.network

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-0715 第 1 轮评审 R1-01 / R1-02（F-AND-32 补充）。真实 [KeystoreSessionTokens] + [EncryptedPreferences]（可逆假加密）。
 * - R1-01：盘上的同文件撤销标记 session_revoked 类型不对（整数），其余字段有效 → 读会话判损坏、整组删除 → 删除提交失败、回滚提交成功 → 未重新登录即重建：
 *   回滚必须原样写回整数标记（不是当成不存在删掉），且删除之前已在独立介质落了撤销记录；重建不交出旧 access、世代为 null。两套存储：真实
 *   SharedPreferences（Robolectric）+ 真实 [FileRevocationMarks]；纯内存 SharedPreferences（失败提交改了内存、盘不变，与 Android 一致）+ 内存介质。
 * - R1-02：独立撤销文件所在目录查不了（路径里一段不是目录、目录没有权限）→ 不当成「没有标记」：会话读不出、世代 null、不缓存为已确认没有；目录恢复
 *   可读之后同一个实例与重建的实例都按已撤销。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FollowupAnd0715ReviewR1Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val temp: File = Files.createTempDirectory("fuand0715-r1-marks").toFile()
    private var serial = 0

    @After
    fun cleanUp() {
        temp.walkTopDown().forEach {
            it.setReadable(true, false)
            it.setExecutable(true, false)
            it.setWritable(true, false)
        }
        temp.deleteRecursively()
    }

    private object FlipCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain.map { it.toInt().inv().toByte() }.toByteArray()

        override fun decrypt(sealed: ByteArray): ByteArray = sealed.map { it.toInt().inv().toByte() }.toByteArray()
    }

    /**
     * 包一层真实 SharedPreferences：按类型记录改动，[failWhen] 命中的那次提交失败（盘与内存都不变）；其余照常提交。[onCommit] 在每次提交前回调。
     */
    private class GatedPreferences(
        val inner: SharedPreferences,
    ) : SharedPreferences by inner {
        var failWhen: (Map<String, Any?>) -> Boolean = { false }
        var onCommit: (Map<String, Any?>, Boolean) -> Unit = { _, _ -> }

        override fun edit(): SharedPreferences.Editor = GatedEditor()

        private inner class GatedEditor : SharedPreferences.Editor {
            private val changes = linkedMapOf<String, Any?>()

            override fun putString(
                key: String,
                value: String?,
            ) = apply { changes[key] = value }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ) = apply { changes[key] = values }

            override fun putInt(
                key: String,
                value: Int,
            ) = apply { changes[key] = value }

            override fun putLong(
                key: String,
                value: Long,
            ) = apply { changes[key] = value }

            override fun putFloat(
                key: String,
                value: Float,
            ) = apply { changes[key] = value }

            override fun putBoolean(
                key: String,
                value: Boolean,
            ) = apply { changes[key] = value }

            override fun remove(key: String) = apply { changes[key] = null }

            override fun clear(): SharedPreferences.Editor = error("not used")

            override fun commit(): Boolean {
                val failing = failWhen(changes)
                onCommit(changes, failing)
                if (failing) return false
                val out = inner.edit()
                changes.forEach { (k, v) -> out.putTyped(k, v) }
                return out.commit()
            }

            override fun apply() {
                commit()
            }
        }
    }

    /** 纯内存 SharedPreferences：[memory] 是进程里的值，[disk] 是落盘的值。失败的提交已改内存、盘不变（同 Android commitToMemory → 写盘失败）。 */
    private class MemoryPreferences(
        val disk: MutableMap<String, Any?> = linkedMapOf(),
    ) : SharedPreferences {
        val memory: MutableMap<String, Any?> = LinkedHashMap(disk)
        var failWhen: (Map<String, Any?>) -> Boolean = { false }
        var onCommit: (Map<String, Any?>, Boolean) -> Unit = { _, _ -> }

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(memory)

        override fun getString(
            key: String,
            defValue: String?,
        ): String? = if (key in memory) memory[key] as String? else defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = if (key in memory) memory[key] as MutableSet<String>? else defValues

        override fun getInt(
            key: String,
            defValue: Int,
        ): Int = memory[key] as Int? ?: defValue

        override fun getLong(
            key: String,
            defValue: Long,
        ): Long = memory[key] as Long? ?: defValue

        override fun getFloat(
            key: String,
            defValue: Float,
        ): Float = memory[key] as Float? ?: defValue

        override fun getBoolean(
            key: String,
            defValue: Boolean,
        ): Boolean = memory[key] as Boolean? ?: defValue

        override fun contains(key: String): Boolean = key in memory

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor =
            object : SharedPreferences.Editor {
                private val changes = linkedMapOf<String, Any?>()

                override fun putString(
                    key: String,
                    value: String?,
                ) = apply { changes[key] = value }

                override fun putStringSet(
                    key: String,
                    values: MutableSet<String>?,
                ) = apply { changes[key] = values }

                override fun putInt(
                    key: String,
                    value: Int,
                ) = apply { changes[key] = value }

                override fun putLong(
                    key: String,
                    value: Long,
                ) = apply { changes[key] = value }

                override fun putFloat(
                    key: String,
                    value: Float,
                ) = apply { changes[key] = value }

                override fun putBoolean(
                    key: String,
                    value: Boolean,
                ) = apply { changes[key] = value }

                override fun remove(key: String) = apply { changes[key] = null }

                override fun clear(): SharedPreferences.Editor = error("not used")

                override fun commit(): Boolean {
                    val failing = failWhen(changes)
                    onCommit(changes, failing)
                    changes.forEach { (k, v) -> if (v == null) memory.remove(k) else memory[k] = v }
                    if (failing) return false
                    disk.clear()
                    disk.putAll(memory)
                    return true
                }

                override fun apply() {
                    commit()
                }
            }
    }

    /** 内存介质：[writes] 计数写入次数。 */
    private class MemoryMarks : RevocationMarks {
        var formal: String? = null
        var writes = 0

        override fun read(): List<String> = listOfNotNull(formal)

        override fun write(text: String) {
            writes += 1
            formal = text
        }

        override fun delete() {
            formal = null
        }
    }

    private fun tokens(
        backing: SharedPreferences,
        marks: RevocationMarks?,
    ) = KeystoreSessionTokens(EncryptedPreferences(context, FlipCipher, backing = backing), marks) { "epoch-${++serial}" }

    private fun KeystoreSessionTokens.login() = save("old-access", "old-refresh", SessionScopeRaw.FULL, "user-a")

    /** 令牌整组删除的那次提交（含 access_token 的删除）。 */
    private fun Map<String, Any?>.isTokenDelete(): Boolean =
        KeystoreSessionTokens.KEY_ACCESS in this && this[KeystoreSessionTokens.KEY_ACCESS] == null

    // ---------------------------------------------------------------- R1-01

    @Test
    fun r1_01_corrupt_type_mark_delete_fails_rollback_succeeds_rebuild_without_relogin_real_preferences_and_file() {
        val raw = context.getSharedPreferences("fuand0715-r1-01-real", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        val markFile = File(temp, KeystoreSessionTokens.REVOCATION_FILE)
        val gated = GatedPreferences(raw)
        tokens(gated, FileRevocationMarks(markFile)).login()
        // 前提：上一个进程清除时落下的同文件标记被写成了整数（类型损坏），其余会话字段完整有效
        raw.edit().putInt(KeystoreSessionTokens.KEY_REVOKED, 7).commit()

        val markPresentAtDelete = mutableListOf<Boolean>()
        gated.failWhen = { it.isTokenDelete() }
        gated.onCommit = { changes, failing -> if (failing && changes.isTokenDelete()) markPresentAtDelete += markFile.exists() }
        val rebuilt = tokens(gated, FileRevocationMarks(markFile))
        assertNull("类型损坏的撤销标记：不交出旧会话", rebuilt.accessToken)
        assertNull(rebuilt.persistedLoginEpoch())
        assertEquals("前提：令牌整组删除确实提交失败过", listOf(true), markPresentAtDelete.distinct())
        assertEquals("回滚原样写回整数标记（不当成不存在删掉）", 7, raw.all[KeystoreSessionTokens.KEY_REVOKED])
        assertEquals("前提：旧令牌仍在盘上", "old-access", raw.getString(KeystoreSessionTokens.KEY_ACCESS, null)?.let { decode(it) })
        assertTrue("删除之前已在独立介质落撤销记录", markFile.exists())

        // 未重新登录即重建（删除仍失败）：不交出旧 access、世代 null
        val again = tokens(gated, FileRevocationMarks(markFile))
        assertNull("重建不交出旧 access", again.accessToken)
        assertNull("重建世代为 null", again.persistedLoginEpoch())
        // 只靠独立介质（同文件标记被别的路径冲掉）也读不回
        raw.edit().remove(KeystoreSessionTokens.KEY_REVOKED).commit()
        assertNull("只剩独立介质标记也读不回", tokens(gated, FileRevocationMarks(markFile)).accessToken)

        // 存储恢复：读一次清干净
        gated.failWhen = { false }
        assertNull(tokens(gated, FileRevocationMarks(markFile)).accessToken)
        assertNull(raw.getString(KeystoreSessionTokens.KEY_ACCESS, null))
        assertTrue("清干净后独立介质标记删除", !markFile.exists())
    }

    @Test
    fun r1_01_corrupt_type_mark_delete_fails_rollback_succeeds_rebuild_without_relogin_memory_storage() {
        val disk = linkedMapOf<String, Any?>()
        val marks = MemoryMarks()
        MemoryPreferences(disk).let { tokens(it, marks).login() }
        disk[KeystoreSessionTokens.KEY_REVOKED] = 7

        val prefs = MemoryPreferences(disk)
        val writesAtDelete = mutableListOf<Int>()
        prefs.failWhen = { it.isTokenDelete() }
        prefs.onCommit = { changes, failing -> if (failing && changes.isTokenDelete()) writesAtDelete += marks.writes }
        val rebuilt = tokens(prefs, marks)
        assertNull(rebuilt.accessToken)
        assertNull(rebuilt.persistedLoginEpoch())
        assertTrue("前提：令牌整组删除确实提交失败过", writesAtDelete.isNotEmpty())
        assertTrue("令牌删除提交之前独立介质已写入：$writesAtDelete", writesAtDelete.all { it >= 1 })
        assertEquals("回滚成功落盘：整数标记原样在盘上", 7, disk[KeystoreSessionTokens.KEY_REVOKED])
        assertNotNull("前提：旧令牌仍在盘上", disk[KeystoreSessionTokens.KEY_ACCESS])
        assertEquals(SessionRevocation.encode(SessionRevocation.ANY), marks.formal)

        // 未重新登录即重建（进程被杀，按盘上的值）
        val again = MemoryPreferences(LinkedHashMap(disk)).apply { failWhen = { it.isTokenDelete() } }
        val second = tokens(again, marks)
        assertNull("重建不交出旧 access", second.accessToken)
        assertNull("重建世代为 null", second.persistedLoginEpoch())
        assertNull("再次读取也一样", second.snapshot().accessToken)
    }

    // ---------------------------------------------------------------- R1-02

    @Test
    fun r1_02_file_marks_treat_only_explicit_absence_as_no_mark() {
        val missingDir = FileRevocationMarks(File(temp, "no-such-dir/${KeystoreSessionTokens.REVOCATION_FILE}"))
        assertEquals("目录与文件都明确不存在：没有标记", emptyList<String>(), missingDir.read())
        missingDir.delete()
        val devNull = FileRevocationMarks(File("/dev/null/${KeystoreSessionTokens.REVOCATION_FILE}"))
        assertThrows("路径里一段不是目录：查询失败上抛") { devNull.read() }
        assertThrows("删除同样不把查询失败当成已删") { devNull.delete() }
        val isDir = File(temp, "dir-as-file").apply { mkdirs() }
        File(isDir, KeystoreSessionTokens.REVOCATION_FILE).mkdirs()
        assertThrows("标记位置是个目录：结构损坏上抛") { FileRevocationMarks(File(isDir, KeystoreSessionTokens.REVOCATION_FILE)).read() }
    }

    @Test
    fun r1_02_unqueryable_mark_directory_never_releases_the_revoked_session_before_or_after_recovery() {
        val raw = context.getSharedPreferences("fuand0715-r1-02", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        val dir = File(temp, "nobackup").apply { mkdirs() }
        val markFile = File(dir, KeystoreSessionTokens.REVOCATION_FILE)
        val gated = GatedPreferences(raw)
        val store = tokens(gated, FileRevocationMarks(markFile))
        store.login()
        val epoch = checkNotNull(store.persistedLoginEpoch())
        // 令牌文件整体写不进：撤销只落在独立文件
        gated.failWhen = { true }
        assertTrue(runCatching { store.clear() }.isFailure)
        assertEquals(listOf(SessionRevocation.encode("e:$epoch")), FileRevocationMarks(markFile).read())
        assertNull("前提：同文件标记没写进", raw.getString(KeystoreSessionTokens.KEY_REVOKED, null))

        // 一：路径里那段目录被换成了普通文件（目录结构损坏，File.exists() 为 false）
        val aside = File(temp, "nobackup.aside")
        assertTrue(dir.renameTo(aside))
        dir.writeText("not a directory")
        assertRevokedWhileUnqueryable(gated, markFile) {
            dir.delete()
            assertTrue(aside.renameTo(dir))
        }

        // 二：目录没有权限（以 root 运行时权限位不生效，跳过这一段）
        dir.setReadable(false, false)
        dir.setExecutable(false, false)
        if (dir.listFiles() == null) {
            assertRevokedWhileUnqueryable(gated, markFile) {
                dir.setReadable(true, false)
                dir.setExecutable(true, false)
            }
        } else {
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
        }
    }

    /** 查不了时：读不出、世代 null；恢复可读后，同一个实例（没有缓存成「确认没有」）与重建的实例都按已撤销。 */
    private fun assertRevokedWhileUnqueryable(
        gated: GatedPreferences,
        markFile: File,
        restore: () -> Unit,
    ) {
        assertTrue("前提：File.exists() 吞成 false", !markFile.exists())
        val blind = tokens(gated, FileRevocationMarks(markFile))
        assertTrue("查询失败：会话读不出（不是读到旧会话）", blind.readSession() is SessionRead.Unreadable)
        assertNull("查询失败：不交出旧 access", blind.accessToken)
        assertNull("查询失败：世代 null", blind.persistedLoginEpoch())
        assertNull("再读一次也一样（没有缓存为无标记）", blind.accessToken)
        restore()
        assertNull("恢复可读后同一个实例：按已撤销", blind.accessToken)
        assertNull(blind.persistedLoginEpoch())
        assertNull("恢复可读后重建：按已撤销", tokens(gated, FileRevocationMarks(markFile)).accessToken)
        assertNotNull("前提：旧令牌仍在盘上（令牌文件写不进）", gated.inner.getString(KeystoreSessionTokens.KEY_ACCESS, null))
    }

    private fun assertThrows(
        message: String,
        block: () -> Unit,
    ) {
        try {
            block()
            fail(message)
        } catch (expected: IOException) {
            Unit
        }
    }

    private fun decode(stored: String): String =
        FlipCipher.decrypt(android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)).toString(Charsets.UTF_8)
}

private fun SharedPreferences.Editor.putTyped(
    key: String,
    value: Any?,
) {
    @Suppress("UNCHECKED_CAST")
    when (value) {
        null -> remove(key)
        is String -> putString(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is Boolean -> putBoolean(key, value)
        is Set<*> -> putStringSet(key, value as Set<String>)
        else -> error("unsupported $value")
    }
}
