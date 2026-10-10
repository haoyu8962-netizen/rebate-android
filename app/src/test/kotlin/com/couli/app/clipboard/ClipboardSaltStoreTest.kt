package com.couli.app.clipboard

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.clipboard.ClipboardRecords
import com.couli.core.clipboard.ClipboardSaltRead
import com.couli.core.config.CouliPreferenceNamespaces
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-CLIP C3 生产存储：盐经 Keystore 包裹（这里是样例加密）存进剪贴板偏好文件；撤回清理项（AppModule 登记的 clearHashes）先删盐再删记录；
 * Keystore 暂时不可用按「不可用」，密文解不开按「没有」（AS07）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ClipboardSaltStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun raw() = context.getSharedPreferences(CouliPreferenceNamespaces.CLIPBOARD, Context.MODE_PRIVATE)

    @Before
    fun clear() {
        raw().edit().clear().commit()
    }

    @Test
    fun theSaltRoundTripsEncryptedAndTheWithdrawalCleanerDeletesItWithTheRecords() {
        val cipher = SampleCipherImpl()
        val prefs = ClipboardPreferences(context, cipher)
        assertEquals(ClipboardSaltRead.Absent, prefs.salt.read())
        assertTrue(prefs.salt.write(SAMPLE_SALT))
        assertArrayEquals(SAMPLE_SALT, (prefs.salt.read() as ClipboardSaltRead.Present).salt)
        assertTrue(prefs.hashes.write(ClipboardRecords.KEY_SELF, "样例指纹"))
        assertTrue(prefs.hashes.write(ClipboardRecords.KEY_SALT_ID, "样例盐编号"))
        prefs.autoDetect = false
        // AppModule 的撤回清理项（不带密钥也能删）
        ClipboardPreferences(context).clearHashes()
        assertFalse("盐已删", raw().contains(ClipboardPreferences.KEY_SALT))
        assertTrue("记录与 saltId 已删", raw().all.keys.none { it.startsWith("hash.") })
        assertFalse("设备级开关不在清理范围", ClipboardPreferences(context).autoDetect)
    }

    @Test
    fun as07_keystoreUnavailableIsNotAbsentAndAnUndecryptableSaltIsAbsent() {
        val cipher = SampleCipherImpl()
        val prefs = ClipboardPreferences(context, cipher)
        assertTrue(prefs.salt.write(SAMPLE_SALT))
        cipher.failing = true
        assertEquals("暂时不可用", ClipboardSaltRead.Failed, prefs.salt.read())
        assertFalse("写也失败", prefs.salt.write(OTHER_SAMPLE_SALT))
        cipher.failing = false
        val encoder = Base64.getEncoder()
        val notASalt = encoder.encodeToString("not-a-salt".toByteArray())
        raw().edit().putString(ClipboardPreferences.KEY_SALT, notASalt).commit()
        assertEquals("解出来不是 32 字节盐 → 没有", ClipboardSaltRead.Absent, prefs.salt.read())
    }
}
