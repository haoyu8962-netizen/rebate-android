package com.couli.app.clipboard

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.config.CouliPreferenceNamespaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * C6 升级迁移（变更记录 §3 第 1 条，brief §2.3）：两代旧数据（2026-10-17 前的 device_auto_detect 两态开关；FU-CLIP 版的 import.enabled /
 * import.consent_version / import.notice_seen_version）→ 新开关；每个映射一条；迁移幂等、用户之后的选择不被重算；C3 遗留待上报清掉。
 * 旧键一律用字面量（产品常量可随实现删除）。键名出处见 tests-android-handback §0。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ClipDefaultOnMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val raw get() = context.getSharedPreferences(CouliPreferenceNamespaces.CLIPBOARD, Context.MODE_PRIVATE)
    private val now = 1_800_000_000_123L

    private fun open() = ClipboardPreferences(context, SampleCipher)

    private fun store(prefs: ClipboardPreferences = open()) = prefs.importPreferences { now }

    private fun seed(block: SharedPreferences.Editor.() -> Unit) {
        val editor = raw.edit().clear()
        editor.block()
        assertTrue(editor.commit())
    }

    private fun assertOn(
        message: String,
        expected: Boolean,
    ) {
        val prefs = open()
        assertEquals("$message：autoDetect", expected, prefs.autoDetect)
        assertEquals("$message：isAutoSwitchOn", expected, store(prefs).isAutoSwitchOn())
    }

    @Before
    fun clear() {
        assertTrue(raw.edit().clear().commit())
    }

    /** C6 ①：什么都没有（全新或从未作出选择）→ 开启。 */
    @Test
    fun c6_1_neverChosenIsOn() {
        seed { }
        assertOn("全新", true)
    }

    /** C6 ①（第一代）：2026-10-17 前旧开关 device_auto_detect=true（缺键也当开启）→ 开启。 */
    @Test
    fun c6_1_legacyDeviceAutoDetectTrueIsOn() {
        seed { putBoolean("device_auto_detect", true) }
        assertOn("device_auto_detect=true", true)
    }

    /** C6 ③（第一代）：旧版在设置里关闭过 device_auto_detect=false → 关闭。 */
    @Test
    fun c6_3_legacyDeviceAutoDetectFalseIsOff() {
        seed { putBoolean("device_auto_detect", false) }
        assertOn("device_auto_detect=false", false)
    }

    /** C6 ④（FU-CLIP）：旧版已开启（有同意版本）→ 开启。 */
    @Test
    fun c6_4_fuClipEnabledWithConsentIsOn() {
        seed {
            putString("import.enabled", "on")
            putString("import.consent_version", "2026-10-01")
            putString("import.consented_at", "123")
            putString("import.notice_seen_version", "2026-10-01")
        }
        assertOn("enabled=on", true)
    }

    /** C6 ④（FU-CLIP）：配置缺版本时打开过（只有 enabled=on，无同意版本）→ 开启。 */
    @Test
    fun c6_4_fuClipEnabledWithoutConsentVersionIsOn() {
        seed { putString("import.enabled", "on") }
        assertOn("enabled=on 无版本", true)
    }

    /** C6 ③（FU-CLIP）：旧版设置里关闭（import.enabled=off）→ 关闭。 */
    @Test
    fun c6_3_fuClipExplicitlyOffIsOff() {
        seed {
            putString("import.enabled", "off")
            putString("import.consent_version", "2026-10-01")
            putString("import.notice_seen_version", "2026-10-01")
        }
        assertOn("enabled=off", false)
    }

    /** C6 ②（FU-CLIP）：旧弹层选【暂不】（只记过看过，未开启）→ 关闭。 */
    @Test
    fun c6_2_fuClipNoticeSeenButNeverEnabledIsOff() {
        seed { putString("import.notice_seen_version", "2026-10-01") }
        assertOn("已看过未开启", false)
    }

    /** C6 ①（FU-CLIP）：装了 FU-CLIP 版但没看过弹层、没选择（enabled 缺键、无 seen）→ 开启。 */
    @Test
    fun c6_1_fuClipUntouchedIsOn() {
        seed { putString("hash.something", "x") }
        assertOn("FU-CLIP 未选择", true)
    }

    /** 迁移幂等：连续多次打开结果不变，且迁移后用户的选择不被旧键重算。 */
    @Test
    fun c6_idempotentAndUserChoiceAfterMigrationWins() {
        for ((name, seedBlock, expected) in listOf(
            Triple("device_auto_detect=false", { e: SharedPreferences.Editor -> e.putBoolean("device_auto_detect", false) }, false),
            Triple("notice_seen 未开启", { e: SharedPreferences.Editor -> e.putString("import.notice_seen_version", "1") }, false),
            Triple("enabled=off", { e: SharedPreferences.Editor -> e.putString("import.enabled", "off") }, false),
            Triple("从未选择", { _: SharedPreferences.Editor -> }, true),
            Triple("enabled=on", { e: SharedPreferences.Editor -> e.putString("import.enabled", "on") }, true),
        )) {
            seed { seedBlock(this) }
            repeat(3) { assertOn("$name 第 ${it + 1} 次", expected) }
            assertTrue("$name：改成相反的选择", if (expected) store().setOff() else store().setOn())
            repeat(3) { assertOn("$name 选择后重启第 ${it + 1} 次", !expected) }
            assertTrue("$name：改回", if (expected) store().setOn() else store().setOff())
            repeat(3) { assertOn("$name 改回后重启第 ${it + 1} 次", expected) }
        }
    }

    /** C3：FU-CLIP 版遗留的待上报 clipboard_auto_import 记录随迁移清掉，不再补报。 */
    @Test
    fun c3_leftoverPendingConsentsAreClearedByMigration() {
        seed {
            putString("import.enabled", "on")
            putString(
                "import.pending_consents",
                """[{"type":"clipboard_auto_import","version":"1","accepted_at_ms":1,"subject":"device"}]""",
            )
        }
        open()
        assertFalse("遗留待上报已清", raw.contains("import.pending_consents"))
    }

    /** 迁移的第一步写失败（commit 返回 false）：不能把用户的「关闭」弄丢；恢复后下次启动仍是关闭。 */
    @Test
    fun c6_commitFailureDuringMigrationKeepsUserOff() {
        for ((name, seedBlock) in listOf(
            "device_auto_detect=false" to { e: SharedPreferences.Editor -> e.putBoolean("device_auto_detect", false) },
            "notice_seen 未开启" to { e: SharedPreferences.Editor -> e.putString("import.notice_seen_version", "1") },
            "enabled=off" to { e: SharedPreferences.Editor -> e.putString("import.enabled", "off") },
        )) {
            val volatile = context.getSharedPreferences("clip-default-on-v-${name.hashCode()}", Context.MODE_PRIVATE)
            val durable = context.getSharedPreferences("clip-default-on-d-${name.hashCode()}", Context.MODE_PRIVATE)
            listOf(volatile, durable).forEach {
                val editor = it.edit().clear()
                seedBlock(editor)
                assertTrue(editor.commit())
            }
            val flaky = CommitFailingPreferences0722(volatile, durable) {}
            flaky.failFromNextCommit()
            val first = ClipboardPreferences(flaky, SampleCipher)
            assertFalse("$name：本次运行不得读取", first.autoDetect)
            flaky.recover()
            val restarted = ClipboardPreferences(durable, SampleCipher)
            assertFalse("$name：恢复后重启仍关闭", restarted.autoDetect)
            context.deleteSharedPreferences("clip-default-on-v-${name.hashCode()}")
            context.deleteSharedPreferences("clip-default-on-d-${name.hashCode()}")
        }
    }

    /** C7：真实撤回清理（CredentialCleanerRegistry 登记的 clearHashes）不清关闭态；从未选择清完仍是开启。 */
    @Test
    fun c7_withdrawalCleanerKeepsOffStateAndNeverChosenDefaultsOn() {
        val prefs = open()
        assertTrue(store(prefs).setOff())
        prefs.clearHashes()
        assertOn("关闭态撤回清理后", false)
        // 从未选择
        seed { }
        open().clearHashes()
        assertOn("从未选择撤回清理后", true)
    }

    /** 旧键残留（C4）：FU-CLIP 版同意字段不作判定：有开启记录但同意版本过期或缺失也算开启。 */
    @Test
    fun c4_staleConsentRecordsDoNotAffectTheSwitch() {
        seed {
            putString("import.enabled", "on")
            putString("import.consent_version", "old-version")
        }
        assertOn("同意版本旧值不影响", true)
        assertTrue("开启后不写入同意记录", raw.getString("import.pending_consents", null) == null)
    }

    /**
     * C5（Codex 评审 r1 S1-2，杀 M10）：用户关闭后立即从持久层重建——不回前台、不推进重试、不做其他提交——仍是关闭。
     * 持久层是夹具的磁盘文件（只有成功 commit 才写），不是 SharedPreferences 的进程缓存。
     */
    @Test
    fun c5_switchOffIsOnTheDurableLayerBeforeAnyForegroundOrRetry() {
        for (start in listOf("从未选择", "已开启")) {
            ProductionImportPreferences0722Harness(context).use { h ->
                val view = h.preferences.importPreferences { now }
                if (start == "已开启") assertTrue(view.setOn())
                view.setOff()
                val restarted = h.restartFromDisk()
                assertEquals("$start：重建后仍关闭", false, restarted.importPreferences { now }.snapshot().enabled)
                assertFalse("$start：重建后不读", restarted.autoDetect)
            }
        }
    }

    /**
     * C6（Codex 评审 r1 S2，杀 M21）：迁移首次提交失败、之后存储恢复；不重建、不回前台，只推进重试时间：关闭落到持久层，
     * 遗留队列与旧键删掉。持久层同上（只有成功 commit 才写的 durable 实例）。
     */
    @Test
    fun c6_failedMigrationLandsByRetryAfterRecoveryWithoutRestartOrForeground() {
        val queue = """[{"type":"clipboard_auto_import","version":"1","accepted_at_ms":1,"subject":"device"}]"""
        for ((name, seedBlock) in listOf(
            "device_auto_detect=false" to { e: SharedPreferences.Editor -> e.putBoolean("device_auto_detect", false) },
            "notice_seen 未开启" to { e: SharedPreferences.Editor -> e.putString("import.notice_seen_version", "1") },
        )) {
            val volatileName = "clip-s2-v-${name.hashCode()}"
            val durableName = "clip-s2-d-${name.hashCode()}"
            val volatile = context.getSharedPreferences(volatileName, Context.MODE_PRIVATE)
            val durable = context.getSharedPreferences(durableName, Context.MODE_PRIVATE)
            listOf(volatile, durable).forEach {
                val editor = it.edit().clear()
                seedBlock(editor)
                editor.putString("import.pending_consents", queue)
                assertTrue(editor.commit())
            }
            val flaky = CommitFailingPreferences0722(volatile, durable) {}
            flaky.failFromNextCommit()
            val prefs = ClipboardPreferences(flaky, SampleCipher)
            assertFalse("$name：本次运行不读", prefs.autoDetect)
            flaky.recover()
            settleDefaultImportRetries0722(prefs)
            assertEquals("$name：关闭已落持久层", "off", durable.getString("import.enabled", null))
            assertFalse("$name：遗留待上报已从持久层删除", durable.contains("import.pending_consents"))
            assertFalse("$name：旧键已从持久层删除", durable.contains("device_auto_detect") || durable.contains("import.notice_seen_version"))
            context.deleteSharedPreferences(volatileName)
            context.deleteSharedPreferences(durableName)
        }
    }
}
