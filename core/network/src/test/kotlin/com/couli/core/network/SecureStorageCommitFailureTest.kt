package com.couli.core.network

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * R3-05：SharedPreferences 的 commit 在写盘之前就改了内存值，返回 false 时这里模拟同样的行为（先改内存、再返回 false），
 * 验证加密存储保留最后成功提交的值，未决键继续可观察为 unknown。lessons 38：记录解不开不当成「没有记录」，阻止新的提交。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SecureStorageCommitFailureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 按字节取反的假加密。 */
    private class FakeCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain.map { it.toInt().inv().toByte() }.toByteArray()

        override fun decrypt(sealed: ByteArray): ByteArray = encrypt(sealed)
    }

    /**
     * 内存版 SharedPreferences：commit 先把改动写进内存，[failing] 时再返回 false（与 Android 的 SharedPreferencesImpl 一样，
     * 写盘失败时内存值已经改了）。[memoryWritesWhileFailing] 限定失败期间还能改内存的次数（0 = 之后的写回也改不了内存，
     * 用来验证不依赖写回的隔离）。
     */
    private class FlakyPreferences : SharedPreferences {
        val memory = linkedMapOf<String, Any?>()

        @Volatile
        var failing = false

        @Volatile
        var memoryWritesWhileFailing = Int.MAX_VALUE

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(memory)

        override fun getString(
            key: String,
            defValue: String?,
        ): String? = memory[key] as? String ?: defValue

        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = defValues

        override fun getInt(
            key: String,
            defValue: Int,
        ): Int = defValue

        override fun getLong(
            key: String,
            defValue: Long,
        ): Long = defValue

        override fun getFloat(
            key: String,
            defValue: Float,
        ): Float = defValue

        override fun getBoolean(
            key: String,
            defValue: Boolean,
        ): Boolean = defValue

        override fun contains(key: String): Boolean = key in memory

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = FlakyEditor()

        private inner class FlakyEditor : SharedPreferences.Editor {
            private val changes = linkedMapOf<String, String?>()
            private var clear = false

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

            override fun clear() = apply { clear = true }

            override fun commit(): Boolean {
                val touchMemory = !failing || memoryWritesWhileFailing-- > 0
                if (touchMemory) {
                    if (clear) memory.clear()
                    changes.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value }
                }
                return !failing
            }

            override fun apply() {
                commit()
            }
        }
    }

    private fun storage(preferences: FlakyPreferences) = EncryptedPreferences(context, FakeCipher(), backing = preferences)

    @Test
    fun r305_failedCommitKeepsTheLastCommittedValueAfterMemoryWasChanged() {
        val preferences = FlakyPreferences()
        val storage = storage(preferences)
        storage.putAll(mapOf("k" to "v1"))
        preferences.failing = true

        assertThrows(IllegalStateException::class.java) { storage.putAll(mapOf("k" to "v2", "new" to "x")) }
        assertEquals("v1", storage.get("k"))
        assertFalse("new" in storage.keys())
        assertThrows(IllegalStateException::class.java) { storage.remove("k") }
        assertEquals("v1", storage.get("k"))
        assertTrue("k" in storage.keys())

        preferences.failing = false
        storage.putAll(mapOf("k" to "v3"))
        assertEquals("v3", storage.get("k"))
    }

    @Test
    fun r305_writeBackThatCannotTouchMemoryIsStillIsolated() {
        val preferences = FlakyPreferences()
        val storage = storage(preferences)
        storage.putAll(mapOf("k" to "v1"))
        // 失败的那次写入改了内存，之后的写回连内存也改不了
        preferences.failing = true
        preferences.memoryWritesWhileFailing = 1

        assertThrows(IllegalStateException::class.java) { storage.putAll(mapOf("k" to "v2", "new" to "x")) }
        assertTrue("memory really holds the uncommitted value", preferences.memory["new"] != null)
        assertEquals("v1", storage.get("k"))
        assertFalse("new" in storage.keys())
    }

    private fun unknownRecord() =
        PendingKeyRecord(
            key = "key-K",
            operationId = "createWithdrawal",
            body = """{"amount_cent":1000}""",
            account = "account-A",
            stateRaw = PendingKeyStore.UNKNOWN,
            everUnknown = true,
        )

    @Test
    fun r305_confirmWhoseInFlightCommitFailsStaysObservableAsUnknown() {
        val preferences = FlakyPreferences()
        val backend = SecurePendingKeyBackend(storage(preferences))
        backend.write(unknownRecord())
        val store = PendingKeyStore(backend) { 0L }
        preferences.failing = true
        preferences.memoryWritesWhileFailing = 1

        assertThrows(IllegalStateException::class.java) { store.confirm("key-K", "click-1", "account-A") }
        assertEquals(PendingKeyStore.UNKNOWN, store.state("key-K", "account-A").stateRaw)
        // 确认、放弃都还能点（没有卡在 in_flight）
        preferences.failing = false
        assertTrue(store.abandon("key-K", "click-2", "account-A"))
    }

    private fun apiClient(
        backend: PendingKeyBackend,
        tokens: SessionTokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A"),
    ) = ApiClient(
        identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
        // 不应有任何请求发出（发出也只会连不上）
        baseUrl = { "http://127.0.0.1:9/" },
        context = {
            NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED)
        },
        device =
            object : DeviceCredentials {
                override val deviceId: String? = "device-fixture"
                override val installSecret: String? = "test-only.install.device-a"
            },
        tokens = tokens,
        pendingKeys = PendingKeyStore(backend) { 0L },
        sleeper = {},
    )

    @Test
    fun r305_apiConfirmReturnsPendingStoreFailedAndTheKeyIsStillUnknownAfterwards() {
        val preferences = FlakyPreferences()
        val backend = SecurePendingKeyBackend(storage(preferences))
        backend.write(unknownRecord())
        val api = apiClient(backend)
        preferences.failing = true
        preferences.memoryWritesWhileFailing = 1

        assertEquals(ApiResult.LocalReject(LocalRejectReason.PENDING_STORE_FAILED), api.confirmPending("createWithdrawal"))
        assertEquals(PendingKeyStore.UNKNOWN, api.pendingFor("createWithdrawal")?.stateRaw)
    }

    @Test
    fun lesson38_unreadablePendingRecordBlocksNewSubmissionsAndIsNotDeleted() {
        val preferences = FlakyPreferences()
        val backend = SecurePendingKeyBackend(storage(preferences))
        backend.write(unknownRecord())
        val stored = preferences.memory.keys.single { it.startsWith(SecurePendingKeyBackend.PREFIX) }
        preferences.memory[stored] = "!!corrupt!!"
        val api = apiClient(backend)

        assertThrows(UnreadableRecordException::class.java) { backend.list("account-A") }
        assertEquals(
            ApiResult.LocalReject(LocalRejectReason.PENDING_STORE_FAILED),
            api.execute(ApiCall("createWithdrawal", body = """{"amount_cent":5}""")),
        )
        assertEquals(ApiResult.LocalReject(LocalRejectReason.PENDING_STORE_FAILED), api.confirmPending("createWithdrawal"))
        assertEquals(ApiResult.LocalReject(LocalRejectReason.PENDING_STORE_FAILED), api.abandonPending("createWithdrawal"))
        assertEquals("the unreadable record is kept, not treated as absent", "!!corrupt!!", preferences.memory[stored])
    }
}
