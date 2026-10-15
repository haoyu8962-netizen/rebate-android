package com.couli.core.network

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-0714 第 3、5 项（F-AND-25 / F-AND-29）：令牌存储里的持久化登录世代。与令牌整组同一次原子写入；登录换新（同账号重新登录也换）、刷新沿用、
 * 清除随令牌删除；读不出、损坏、升级前的会话都为 null。写入失败时与令牌一起恢复旧值；失败写入之后被同命名空间的另一次提交顺带落盘时，令牌与世代
 * 也一起落盘——不会出现「新令牌 + 旧世代」（旧缓存借旧世代复活）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FollowupAnd0714LoginEpochTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeCipher : SecretCipher {
        @Volatile var failing = false

        override fun encrypt(plain: ByteArray): ByteArray = plain.map { it.toInt().inv().toByte() }.toByteArray()

        override fun decrypt(sealed: ByteArray): ByteArray {
            if (failing) throw SecureStorageReadException("keystore busy")
            return sealed.map { it.toInt().inv().toByte() }.toByteArray()
        }
    }

    /**
     * 盘与内存分开的 SharedPreferences（Android 的 SharedPreferencesImpl：commit 先改内存，写盘失败返回 false 但内存已改；下一次成功的 commit 把
     * 整个内存映像写盘——失败那次的改动随之「顺带落盘」）。
     */
    private class DiskPreferences(
        val disk: MutableMap<String, String> = linkedMapOf(),
    ) : SharedPreferences {
        val memory = LinkedHashMap(disk)

        @Volatile var failing = false

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(memory)

        override fun getString(
            key: String,
            defValue: String?,
        ): String? = memory[key] ?: defValue

        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ) = defValues

        override fun getInt(
            key: String,
            defValue: Int,
        ) = defValue

        override fun getLong(
            key: String,
            defValue: Long,
        ) = defValue

        override fun getFloat(
            key: String,
            defValue: Float,
        ) = defValue

        override fun getBoolean(
            key: String,
            defValue: Boolean,
        ) = defValue

        override fun contains(key: String) = key in memory

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor =
            object : SharedPreferences.Editor {
                private val changes = linkedMapOf<String, String?>()

                override fun putString(
                    key: String,
                    value: String?,
                ) = apply { changes[key] = value }

                override fun putStringSet(
                    key: String,
                    values: MutableSet<String>?,
                ) = this

                override fun putInt(
                    key: String,
                    value: Int,
                ) = this

                override fun putLong(
                    key: String,
                    value: Long,
                ) = this

                override fun putFloat(
                    key: String,
                    value: Float,
                ) = this

                override fun putBoolean(
                    key: String,
                    value: Boolean,
                ) = this

                override fun remove(key: String) = apply { changes[key] = null }

                override fun clear() = this

                override fun commit(): Boolean {
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

    private var serial = 0

    private fun tokens(
        prefs: DiskPreferences,
        cipher: SecretCipher = FakeCipher(),
    ) = KeystoreSessionTokens(EncryptedPreferences(context, cipher, backing = prefs)) { "epoch-${++serial}" }

    @Test
    fun fand25_login_epoch_is_written_with_tokens_renewed_on_login_kept_on_refresh_removed_on_clear() {
        val prefs = DiskPreferences()
        val store = tokens(prefs)
        assertNull("没有会话", store.persistedLoginEpoch())
        store.save("access-1", "refresh-1", SessionScopeRaw.FULL, "user-a")
        val first = store.persistedLoginEpoch()
        assertNotNull(first)
        assertTrue("与令牌同一次提交落盘", KeystoreSessionTokens.KEY_LOGIN_EPOCH in prefs.disk && KeystoreSessionTokens.KEY_ACCESS in prefs.disk)
        assertTrue(store.replaceIf(store.generation, "access-2", "refresh-2", SessionScopeRaw.FULL))
        assertEquals("刷新令牌沿用登录世代", first, store.persistedLoginEpoch())
        assertEquals("进程重建读得到同一份", first, tokens(DiskPreferences(LinkedHashMap(prefs.disk))).persistedLoginEpoch())
        store.clear()
        assertNull("清除随令牌一起删", store.persistedLoginEpoch())
        store.save("access-3", "refresh-3", SessionScopeRaw.FULL, "user-a")
        assertNotEquals("同一账号重新登录也换新", first, store.persistedLoginEpoch())
        assertTrue(store.saveIf(store.generation, "access-4", "refresh-4", SessionScopeRaw.FULL, "user-a"))
        assertNotEquals(first, store.persistedLoginEpoch())
    }

    @Test
    fun fand25_unreadable_corrupt_or_pre_upgrade_session_has_no_login_epoch() {
        val prefs = DiskPreferences()
        val cipher = FakeCipher()
        val store = tokens(prefs, cipher)
        store.save("access-1", "refresh-1", SessionScopeRaw.FULL, "user-a")
        cipher.failing = true
        assertNull("读不出：未知", store.persistedLoginEpoch())
        cipher.failing = false
        prefs.memory[KeystoreSessionTokens.KEY_LOGIN_EPOCH] = "%%%not-base64%%%"
        assertNull("损坏：未知", store.persistedLoginEpoch())
        // FU-AND-0715（F-AND-32 补充，鸿蒙 R1-01）：世代键损坏是会话字段损坏，整组隔离（不再只让世代未知）；升级前登录另起一份会话
        assertNull("世代键损坏：整组会话不加载", store.accessToken)
        store.save("access-1", "refresh-1", SessionScopeRaw.FULL, "user-a")
        prefs.memory.remove(KeystoreSessionTokens.KEY_LOGIN_EPOCH)
        assertEquals("升级前登录的会话（没有世代）令牌照常可用", "access-1", store.accessToken)
        assertNull("但登录世代未知（缓存、恢复记录要等下次登录）", store.persistedLoginEpoch())
    }

    @Test
    fun fand25_failed_relogin_write_keeps_old_pair_and_piggybacked_flush_lands_tokens_with_new_epoch_together() {
        val prefs = DiskPreferences()
        val store = tokens(prefs)
        store.save("access-1", "refresh-1", SessionScopeRaw.FULL, "user-a")
        val first = store.persistedLoginEpoch()
        prefs.failing = true
        assertTrue("重新登录写盘失败", runCatching { store.save("access-2", "refresh-2", SessionScopeRaw.FULL, "user-a") }.isFailure)
        assertEquals("本进程按最后成功提交的值：旧令牌 + 旧世代", "access-1" to first, store.accessToken to store.persistedLoginEpoch())
        // 同一命名空间的另一次提交（未决记录）成功：失败那次留在内存里的改动顺带落盘
        prefs.failing = false
        EncryptedPreferences(context, FakeCipher(), backing = prefs).putAll(mapOf("pending_key.x" to "y"))
        val rebuilt = tokens(DiskPreferences(LinkedHashMap(prefs.disk)))
        val pair = rebuilt.accessToken to rebuilt.persistedLoginEpoch()
        assertTrue(
            "令牌与登录世代成对落盘（旧+旧 或 新+新），不会出现新令牌配旧世代：$pair",
            pair == ("access-1" to first) || (pair.first == "access-2" && pair.second != null && pair.second != first),
        )
    }
}
