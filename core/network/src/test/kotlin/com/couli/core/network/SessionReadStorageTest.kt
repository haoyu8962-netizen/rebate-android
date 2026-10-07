package com.couli.core.network

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.config.CouliPreferenceNamespaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 会话与设备凭据的读取区分三种情况（APP-03b session_unreadable，与 iOS 同一裁定）：不存在 → 没有会话；读取失败（Keystore 暂时
 * 不可用，[SecureStorageReadException]）→ Unreadable，不删除；内容损坏 → 按没有并删除。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SessionReadStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 按字节取反的假加密；[mode] 控制解密：正常、读取失败、损坏。 */
    private class SwitchableCipher : SecretCipher {
        var mode = Mode.OK

        enum class Mode { OK, FAILED, CORRUPT }

        override fun encrypt(plain: ByteArray): ByteArray = plain.map { it.toInt().inv().toByte() }.toByteArray()

        override fun decrypt(sealed: ByteArray): ByteArray =
            when (mode) {
                Mode.OK -> encrypt(sealed)
                Mode.FAILED -> throw SecureStorageReadException("keystore busy")
                Mode.CORRUPT -> error("tag mismatch")
            }
    }

    private val raw get() = context.getSharedPreferences(CouliPreferenceNamespaces.SESSION, Context.MODE_PRIVATE)
    private val cipher = SwitchableCipher()

    @Before
    fun clean() {
        raw.edit().clear().commit()
    }

    @Test
    fun absentSessionReadsAsNoSession() {
        val read = KeystoreSessionTokens(EncryptedPreferences(context, cipher)).readSession() as SessionRead.Present
        assertNull(read.snapshot.accessToken)
    }

    @Test
    fun readFailureIsUnreadableAndKeepsTheStoredSession() {
        val tokens = KeystoreSessionTokens(EncryptedPreferences(context, cipher))
        tokens.save("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A")

        cipher.mode = SwitchableCipher.Mode.FAILED
        assertTrue(tokens.readSession() is SessionRead.Unreadable)
        assertNotNull("读取失败不删除", raw.getString(KeystoreSessionTokens.KEY_ACCESS, null))

        cipher.mode = SwitchableCipher.Mode.OK
        assertEquals("access-A", (tokens.readSession() as SessionRead.Present).snapshot.accessToken)
    }

    @Test
    fun corruptSessionReadsAsNoSessionAndIsDeleted() {
        val tokens = KeystoreSessionTokens(EncryptedPreferences(context, cipher))
        tokens.save("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A")

        cipher.mode = SwitchableCipher.Mode.CORRUPT
        val read = tokens.readSession() as SessionRead.Present
        assertNull(read.snapshot.accessToken)
        assertNull("损坏的条目删除", raw.getString(KeystoreSessionTokens.KEY_ACCESS, null))
    }

    @Test
    fun deviceCredentialsDistinguishTheSameThreeCases() {
        val store = KeystoreDeviceCredentialStore(EncryptedPreferences(context, cipher))
        assertEquals(StoredDevice.None, store.read())
        store.save("device-1", "test-only.install.device-a")
        assertEquals(StoredDevice.Present("device-1", "test-only.install.device-a"), store.read())

        cipher.mode = SwitchableCipher.Mode.FAILED
        assertEquals(StoredDevice.Unreadable, store.read())
        assertNotNull(raw.getString(KeystoreDeviceCredentialStore.KEY_INSTALL_SECRET, null))

        cipher.mode = SwitchableCipher.Mode.CORRUPT
        assertEquals(StoredDevice.None, store.read())
        assertNull(raw.getString(KeystoreDeviceCredentialStore.KEY_INSTALL_SECRET, null))
    }
}
