package com.couli.core.network

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.config.CouliPreferenceNamespaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 令牌与未决记录的加密存储（Robolectric 没有 AndroidKeyStore，注入可逆的假加密；生产用 AndroidKeystoreCipher）：
 * 盘上没有明文；密钥删除后旧密文按不存在处理；清除只删令牌三项；未决记录按账号隔离、整条加密。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SecureStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 按字节取反的假加密；destroyKey 之后解密失败。 */
    private class FakeCipher : SecretCipher {
        var destroyed = false

        override fun encrypt(plain: ByteArray): ByteArray = plain.map { it.toInt().inv().toByte() }.toByteArray()

        override fun decrypt(sealed: ByteArray): ByteArray {
            check(!destroyed) { "key destroyed" }
            return encrypt(sealed)
        }

        override fun destroyKey() {
            destroyed = true
        }
    }

    private val raw get() = context.getSharedPreferences(CouliPreferenceNamespaces.SESSION, Context.MODE_PRIVATE)

    @Before
    fun clean() {
        raw.edit().clear().commit()
    }

    @Test
    fun tokensRoundTripWithoutPlaintextOnDisk() {
        val tokens = KeystoreSessionTokens(EncryptedPreferences(context, FakeCipher()))
        tokens.save("access-secret", "refresh-secret", SessionScopeRaw.DELETION_ONLY)
        raw.edit().putString("unrelated", "kept").commit()

        assertEquals("access-secret", tokens.accessToken)
        assertEquals("refresh-secret", tokens.refreshToken)
        assertEquals(SessionScopeRaw.DELETION_ONLY, tokens.scope)
        val disk = raw.all.values.joinToString()
        assertFalse(disk, "access-secret" in disk || "refresh-secret" in disk)

        tokens.clear()
        assertNull(tokens.accessToken)
        assertNull(tokens.refreshToken)
        assertNull(tokens.scope)
        assertEquals("kept", raw.getString("unrelated", null))
    }

    @Test
    fun destroyedKeyMeansNoSession() {
        val cipher = FakeCipher()
        val tokens = KeystoreSessionTokens(EncryptedPreferences(context, cipher))
        tokens.save("a", "r", SessionScopeRaw.FULL)
        cipher.destroyKey()
        assertNull(tokens.accessToken)
        assertFalse(raw.contains(KeystoreSessionTokens.KEY_ACCESS))
    }

    @Test
    fun pendingRecordsAreEncryptedAndIsolatedByAccount() {
        val backend = SecurePendingKeyBackend(EncryptedPreferences(context, FakeCipher()))
        val record =
            PendingKeyRecord(
                key = "key-1",
                operationId = "bindPhone",
                body = """{"phone":"13800000000"}""",
                account = "account-A",
                summary = "fixture",
                stateRaw = PendingKeyStore.UNKNOWN,
                everUnknown = true,
                createdAtMillis = 42L,
            )
        backend.write(record)
        backend.write(record.copy(key = "key-2", account = "account-B"))

        assertEquals(record, backend.read("account-A", "key-1"))
        assertEquals(listOf(record), backend.list("account-A"))
        assertNull(backend.read("account-B", "key-1"))
        assertFalse(
            raw.all.values
                .joinToString()
                .contains("13800000000"),
        )

        // 冷启动恢复：新的 Store 读同一个后端，只恢复本账号
        val store = PendingKeyStore(backend) { 0L }
        store.coldStart("account-A")
        assertEquals(PendingKeyStore.UNKNOWN, store.state("key-1").stateRaw)
        assertEquals(PendingKeyStore.NONE, store.state("key-2").stateRaw)
        assertTrue(store.confirm("key-1"))
        store.onResponse("key-1", PendingKeyStore.ENVELOPE_SUCCESS)
        assertNull(backend.read("account-A", "key-1"))
        assertEquals(1, backend.list("account-B").size)
    }

    /** 记下每次解密的明文（判断读出了哪些账号的记录）。 */
    private class RecordingCipher : SecretCipher {
        private val inner = FakeCipher()
        val decrypted = mutableListOf<String>()

        override fun encrypt(plain: ByteArray): ByteArray = inner.encrypt(plain)

        override fun decrypt(sealed: ByteArray): ByteArray = inner.decrypt(sealed).also { decrypted += it.toString(Charsets.UTF_8) }
    }

    /** lessons 33：按账号查询在存储层按键名前缀过滤，别的账号的请求体不解密、不读进内存。 */
    @Test
    fun listingOneAccountNeverDecryptsAnotherAccountsRecords() {
        val cipher = RecordingCipher()
        val backend = SecurePendingKeyBackend(EncryptedPreferences(context, cipher))
        val record = PendingKeyRecord("key-1", "bindPhone", """{"phone":"13800000000"}""", "account-A")
        backend.write(record)
        backend.write(record.copy(key = "key-2", body = """{"phone":"13900000000"}""", account = "account-B"))
        backend.write(record.copy(key = "key-3", body = """{"phone":"13700000000"}""", account = "account-AB"))
        cipher.decrypted.clear()

        assertEquals(listOf(record), backend.list("account-A"))
        assertEquals(1, cipher.decrypted.size)
        assertTrue(cipher.decrypted.none { "13900000000" in it || "13700000000" in it })
    }
}
