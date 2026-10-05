package com.couli.core.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedPreferencesNamespaceCleanerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.applicationInfo.dataDir, "shared_prefs")

    @Test
    fun removesOnlyAppNamespacedPreferences() {
        write("couli.session", "token", "a")
        write("couli.cache", "page", "b")
        write("couli.unregistered", "k", "d")
        write("third.party", "k", "c")
        write(SharedPreferencesNamespaceCleaner.DEBUG_SELECTION_PREFERENCES, "environment", "test")
        write(EnvironmentStartupReconciler.STATE_PREFERENCES, "last_applied_environment", "test")
        val cleaner = SharedPreferencesNamespaceCleaner(context)
        assertEquals(
            listOf("couli.cache", "couli.session", "couli.unregistered"),
            cleaner.namespacedPreferenceNamesOnDisk(),
        )

        cleaner.clean(AppEnvironment.TEST, AppEnvironment.STAGING)

        assertTrue(cleaner.namespacedPreferenceNamesOnDisk().isEmpty())
        assertTrue(prefs("couli.session").all.isEmpty())
        assertTrue(prefs("couli.unregistered").all.isEmpty())
        assertEquals("c", prefs("third.party").getString("k", null))
        assertEquals(
            "test",
            prefs(SharedPreferencesNamespaceCleaner.DEBUG_SELECTION_PREFERENCES).getString("environment", null),
        )
        assertEquals(
            "test",
            prefs(EnvironmentStartupReconciler.STATE_PREFERENCES).getString("last_applied_environment", null),
        )
    }

    @Test
    fun registeredNamespaceThatIsOnlyInMemoryIsCleaned() {
        // 构造「内存里有值、磁盘上没有文件」：等价于 apply() 之后尚未落盘就切换环境。
        val held = prefs(CouliPreferenceNamespaces.SESSION)
        held.edit().putString("token", "old").apply()
        assertTrue(held.edit().commit()) // 同一实例上的 commit 排在 apply 之后：返回时 apply 已写完
        deleteFiles(CouliPreferenceNamespaces.SESSION)
        val cleaner = SharedPreferencesNamespaceCleaner(context)
        assertFalse(cleaner.namespacedPreferenceNamesOnDisk().contains(CouliPreferenceNamespaces.SESSION))
        assertEquals("old", held.getString("token", null))

        cleaner.clean(AppEnvironment.TEST, AppEnvironment.STAGING)

        assertNull("已持有的实例也被清空", held.getString("token", null))
        assertTrue(prefs(CouliPreferenceNamespaces.SESSION).all.isEmpty())
        assertNoFiles(CouliPreferenceNamespaces.SESSION)
    }

    @Test
    fun applyImmediatelyFollowedBySwitchLeavesNothingBehind() {
        val held = prefs(CouliPreferenceNamespaces.CACHE)
        held.edit().putString("page", "old").apply()
        val cleaner = SharedPreferencesNamespaceCleaner(context)

        cleaner.clean(AppEnvironment.TEST, AppEnvironment.PROD)

        // 排队中的 apply 写入不能在清理之后把旧值写回磁盘
        assertNull(held.getString("page", null))
        assertNoFiles(CouliPreferenceNamespaces.CACHE)
        assertTrue(prefs(CouliPreferenceNamespaces.CACHE).all.isEmpty())
        assertNoFiles(CouliPreferenceNamespaces.CACHE)
    }

    @Test
    fun namespaceWithOnlyBackupFileIsCleaned() {
        // 只剩 .xml.bak 时，下次 getSharedPreferences 会用备份恢复旧值：登记过的与未登记的都要清掉。
        writeBackupOnly(CouliPreferenceNamespaces.SESSION, "token", "old-session")
        writeBackupOnly("couli.legacy", "token", "old-legacy")
        val cleaner = SharedPreferencesNamespaceCleaner(context)
        assertEquals(
            listOf(CouliPreferenceNamespaces.SESSION, "couli.legacy").sorted(),
            cleaner.namespacedPreferenceNamesOnDisk(),
        )

        cleaner.clean(AppEnvironment.STAGING, AppEnvironment.TEST)

        assertNoFiles(CouliPreferenceNamespaces.SESSION)
        assertNoFiles("couli.legacy")
        assertNull(prefs(CouliPreferenceNamespaces.SESSION).getString("token", null))
        assertNull(prefs("couli.legacy").getString("token", null))
    }

    @Test
    fun backupFileIsReallyRestoredWithoutCleaning() {
        // 对照：证明上一条测试里的 .bak 确实会被系统恢复成旧值（测试本身有牙齿）。
        writeBackupOnly("couli.legacy", "token", "old-legacy")

        assertEquals("old-legacy", prefs("couli.legacy").getString("token", null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun registeredNamesMustUseTheAppPrefix() {
        SharedPreferencesNamespaceCleaner(context, registeredNames = listOf("session"))
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun write(
        name: String,
        key: String,
        value: String,
    ) {
        assertTrue(prefs(name).edit().putString(key, value).commit())
    }

    private fun writeBackupOnly(
        name: String,
        key: String,
        value: String,
    ) {
        directory.mkdirs()
        File(directory, "$name.xml.bak").writeText(
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
                "<map>\n    <string name=\"$key\">$value</string>\n</map>\n",
        )
        assertFalse(File(directory, "$name.xml").exists())
    }

    private fun deleteFiles(name: String) {
        File(directory, "$name.xml").delete()
        File(directory, "$name.xml.bak").delete()
        assertNoFiles(name)
    }

    private fun assertNoFiles(name: String) {
        assertFalse("$name.xml 不应存在", File(directory, "$name.xml").exists())
        assertFalse("$name.xml.bak 不应存在", File(directory, "$name.xml.bak").exists())
    }
}
