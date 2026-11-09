@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.clipboard

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.Clipboard0724Fixture.Companion.LINK
import com.couli.app.clipboard.Clipboard0724Fixture.Companion.json
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.ManualPasteAction
import com.couli.core.network.ApiResult
import com.couli.core.router.PrivacyState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-X-CLIP-DEFAULT-ON（BR-ID-16 2026-10-30，docs/changes/20261030-剪贴板默认开启.md，brief.md C1–C7）：
 * 真实 AppClipboard + 真实 ClipboardPreferences 的流程用例。
 * 夹具 enabled = null 即「从未选择」（新版默认开启）；其余见 tests-android-handback §1。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("TooManyFunctions") // 验收 §3 各条独立成例
class ClipDefaultOnFlowTest {
    private fun restartedPrefs(f: Clipboard0724Fixture) = ClipboardPreferences(f.disk, SampleCipher)

    /** 验收 §3-1 / C1 / C2 / C3：全新安装同意隐私政策，回前台直接读并出商品卡，无弹层，无 clipboard_auto_import 上报，不写旧同意字段。 */
    @Test
    fun c1_freshInstallConsentedReadsOnForegroundShowsCardWithoutNoticeOrConsentReport() =
        runTest {
            Clipboard0724Fixture(this, enabled = null).use { f ->
                assertTrue("从未选择按开启", f.clipboard.autoSwitch.value)
                f.foreground()
                assertEquals("回前台直接读取", 1, f.system.reads)
                f.card()
                assertEquals(listOf("parseInput"), f.requests.map { it.operationId })
                assertFalse("不写功能同意待上报（C3）", f.disk.contains("import.pending_consents"))
                assertFalse("不写同意版本（C2）", f.disk.contains("import.consent_version"))
                assertFalse("不写已看过（C2）", f.disk.contains("import.notice_seen_version"))
            }
        }

    @Test
    fun c1_basicModeNeverReadsEvenThoughSwitchDefaultsOn() =
        runTest {
            Clipboard0724Fixture(this, enabled = null).use { f ->
                f.privacy = PrivacyState.BASIC_MODE
                f.clipboard.onPrivacyChanged(PrivacyState.BASIC_MODE)
                f.foreground()
                f.noEffects()
            }
        }

    /** C2 手动门槛②作废：第一次点粘贴直接读，不先出弹层；不依赖开关。 */
    @Test
    fun c2_firstManualPasteReadsImmediatelyEvenWithAutoBlocked() =
        runTest {
            Clipboard0724Fixture(this, enabled = null).use { f ->
                f.clipboard.otherDialogShowing = { true } // 普通互斥弹窗挡住回前台自动读，单独证明手动来源
                f.foreground()
                f.clipboard.otherDialogShowing = { false }
                f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                f.settle()
                assertEquals("第一次点击即读", 1, f.system.reads)
                f.card()
            }
        }

    /** C2：邀请码框粘贴第一次点击直接交付，不等用途弹层。 */
    @Test
    fun c2_firstInviteFillDeliversOnFirstTap() =
        runTest {
            Clipboard0724Fixture(this, enabled = null).use { f ->
                f.clipboard.onBackStack("InviteBackfill", "invite", setOf("invite"))
                f.system.content = ClipRead.Text("邀请码 K7M2QX")
                f.foreground()
                val filled = mutableListOf<String?>()
                val direct = f.clipboard.pasteInviteCode { filled += it }
                f.settle()
                assertTrue("返回值与回调互斥：direct=$direct filled=$filled", (direct != null) != filled.isNotEmpty())
                assertEquals("总共恰好交付一次", listOf<String?>("K7M2QX"), listOfNotNull(direct) + filled)
                assertEquals(1, f.system.reads)
                assertEquals("不上传", 0, f.parseBodies().size)
                assertNull("邀请码填入不出商品结果", f.clipboard.result.value)
                f.clipboard.onActivityResumed()
                f.settle()
                assertEquals("不重复交付", listOf<String?>("K7M2QX"), listOfNotNull(direct) + filled)
                assertEquals("不重复读取", 1, f.system.reads)
            }
        }

    /** C3：本机遗留待上报记录（FU-CLIP 版留下的）不会被发送。 */
    @Test
    fun c3_leftoverPendingConsentRecordIsNeverSent() =
        runTest {
            Clipboard0724Fixture(this, enabled = null).use { f ->
                f.disk
                    .edit()
                    .putString(
                        "import.pending_consents",
                        """[{"type":"clipboard_auto_import","version":"1","accepted_at_ms":1,"subject":"device"}]""",
                    ).commit()
                f.foreground()
                f.settle()
                assertEquals(listOf("parseInput"), f.requests.map { it.operationId })
            }
        }

    /** C4：config 没有 consent_version、版本变化都不影响读取。 */
    @Test
    fun c4_consentVersionAbsenceAndChangeDoNotAffectReading() =
        runTest {
            Clipboard0724Fixture(this, enabled = null).use { f ->
                f.config = json("""{"share_domains":["share.example.test"]}""")
                f.foreground()
                assertEquals("无 consent_version 也读", 1, f.system.reads)
                f.config = json("""{"clipboard":{"consent_version":"changed"},"share_domains":["share.example.test"]}""")
                f.nextRound(text = "今天下午三点在楼下见面")
                assertEquals("版本变化仍读", 2, f.system.reads)
            }
        }

    /** C5 / 验收 §3-2：关闭后零读取零上传，手动照常；杀进程重启（读磁盘）后仍关闭。 */
    @Test
    fun c5_switchOffZeroReadZeroUploadManualStillWorksSurvivesRestart() =
        runTest {
            Clipboard0724Fixture(this, enabled = null).use { f ->
                f.clipboard.setAutoSwitch(false)
                assertFalse(f.clipboard.autoSwitch.value)
                f.foreground()
                f.noEffects()
                repeat(3) {
                    f.nextRound()
                    f.noEffects()
                }
                f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                f.settle()
                assertEquals("手动粘贴照常读", 1, f.system.reads)
                f.card()
                val reopened = restartedPrefs(f)
                assertFalse("杀进程重启仍关闭（C5）", reopened.autoDetect)
                assertEquals(false, reopened.importPreferences { f.now }.snapshot().enabled)
                assertFalse(f.clipboard.autoSwitch.value)
            }
        }

    /** C5：关闭立即取消在途自动任务，迟到的 parse 结果丢弃，不出卡。 */
    @Test
    fun c5_turningOffCancelsInFlightAutoTaskAndDropsLateResult() =
        runTest {
            Clipboard0724Fixture(this, enabled = null).use { f ->
                val reply = CompletableDeferred<ApiResult>()
                f.held.addLast(reply)
                f.foreground()
                assertEquals("自动读取后 parse 在途", 1, f.parseBodies().size)
                f.clipboard.setAutoSwitch(false)
                reply.complete(Clipboard0724Fixture.success())
                f.settle()
                assertNull("关闭后已读未提交的候选丢弃，不出卡", f.clipboard.result.value)
                assertEquals(1, f.system.reads)
            }
        }

    /** C5 / C8：设置页打开直接生效，不出弹层，下一轮读取。 */
    @Test
    fun c5_turningBackOnTakesEffectNextRoundWithoutNotice() =
        runTest {
            Clipboard0724Fixture(this, enabled = false).use { f ->
                f.foreground()
                f.noEffects()
                f.clipboard.setAutoSwitch(true)
                assertTrue("打开直接生效", f.clipboard.autoSwitch.value)
                f.settle()
                f.noEffects() // 同轮不补读（G29）
                f.nextRound()
                assertEquals(1, f.system.reads)
                f.card()
                assertTrue(restartedPrefs(f).autoDetect)
            }
        }

    /** C5：auto_read.android=false 只停读取、不改用户开关；用户关闭后配置开启也不能打开。 */
    @Test
    fun c5_serverConfigCanOnlyDisableNeverEnableOrRewriteTheSwitch() =
        runTest {
            val disabled = json("""{"features":{"clipboard":{"auto_read":{"android":false}}},"share_domains":["share.example.test"]}""")
            val enabledConfig = json("""{"features":{"clipboard":{"auto_read":{"android":true}}},"share_domains":["share.example.test"]}""")
            Clipboard0724Fixture(this, enabled = null).use { f ->
                f.config = disabled
                f.foreground()
                f.noEffects()
                assertTrue("配置不改用户开关", f.clipboard.autoSwitch.value)
                f.config = enabledConfig
                f.nextRound(text = "今天下午三点在楼下见面")
                assertEquals("配置恢复后下一轮读取", 1, f.system.reads)
            }
            Clipboard0724Fixture(this, enabled = false).use { f ->
                f.config = enabledConfig
                f.foreground()
                f.noEffects()
                assertFalse("配置不能打开用户的关闭", f.clipboard.autoSwitch.value)
                assertEquals(false, f.preferences.snapshot().enabled)
            }
        }

    /** C7 / 验收 §3-4：从未选择的设备，撤回不读；重新同意后默认开启回前台读。 */
    @Test
    fun c7_withdrawNeverTouchedStopsThenReconsentReadsDefaultOn() =
        runTest {
            Clipboard0724Fixture(this, enabled = null).use { f ->
                f.foreground()
                assertEquals(1, f.system.reads)
                f.privacy = PrivacyState.BASIC_MODE
                f.clipboard.onPrivacyChanged(PrivacyState.BASIC_MODE, withdrawn = true)
                f.prefs.clearHashes() // 真实撤回清理项
                f.nextRound(text = LINK)
                assertEquals("基本模式不读", 1, f.system.reads)
                f.privacy = PrivacyState.CONSENTED
                f.clipboard.onPrivacyChanged(PrivacyState.CONSENTED)
                f.nextRound(text = "今天下午三点在楼下见面")
                assertEquals("重新同意后默认开启", 2, f.system.reads)
                assertTrue(f.clipboard.autoSwitch.value)
            }
        }

    /** C7 / 验收 §3-4：用户已关闭的，撤回 + 清理 + 重新同意后仍关闭（关闭态不随撤回清除）。 */
    @Test
    fun c7_userOffSurvivesWithdrawalCleanerAndReconsent() =
        runTest {
            Clipboard0724Fixture(this, enabled = false).use { f ->
                f.privacy = PrivacyState.BASIC_MODE
                f.clipboard.onPrivacyChanged(PrivacyState.BASIC_MODE, withdrawn = true)
                f.prefs.clearHashes() // 真实撤回清理项不得清掉关闭态
                assertFalse("撤回清理后仍关闭", restartedPrefs(f).autoDetect)
                f.privacy = PrivacyState.CONSENTED
                f.clipboard.onPrivacyChanged(PrivacyState.CONSENTED)
                f.nextRound()
                f.noEffects()
                assertFalse(f.clipboard.autoSwitch.value)
                assertFalse("重启仍关闭", restartedPrefs(f).autoDetect)
            }
        }

    /**
     * C6 × C5（Codex 评审 r1 S1-1）：迁移目标为开启（第一代 device_auto_detect=true，或 FU-CLIP 版 enabled=on 带旧同意字段），
     * 迁移提交持续失败：本次运行回前台零读取零上传；临时阻断不得写成用户关闭；恢复存储后按原选择（开启）。
     */
    @Test
    fun c6_migrationCommitFailingWithTargetOnBlocksThisRunWithoutPersistingOff() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            for ((name, seedBlock) in listOf(
                "device_auto_detect=true" to { e: SharedPreferences.Editor -> e.putBoolean("device_auto_detect", true) },
                "enabled=on 带旧字段" to { e: SharedPreferences.Editor -> seedFuClipEnabled(e) },
            )) {
                val durableName = "clip-s11-durable-${name.hashCode()}"
                val durable = context.getSharedPreferences(durableName, Context.MODE_PRIVATE)
                var flaky: CommitFailingPreferences0722? = null
                val wrap = { volatile: SharedPreferences ->
                    listOf(volatile, durable).forEach {
                        val editor = it.edit().clear()
                        seedBlock(editor)
                        assertTrue(editor.commit())
                    }
                    CommitFailingPreferences0722(volatile, durable) {}.also {
                        it.failFromNextCommit()
                        flaky = it
                    }
                }
                Clipboard0724Fixture(this, enabled = null, wrapPrefs = wrap).use { f ->
                    f.foreground()
                    f.noEffects()
                    f.nextRound()
                    f.noEffects()
                    assertTrue("$name：零上传", f.requests.isEmpty())
                    assertNotEquals("$name：临时阻断不得写成用户关闭", "off", durable.getString("import.enabled", null))
                    checkNotNull(flaky).recover()
                    settleDefaultImportRetries0722(f.prefs)
                    assertNotEquals("$name：恢复后磁盘不得是关闭", "off", durable.getString("import.enabled", null))
                    assertTrue("$name：恢复存储后按原选择（开启）", ClipboardPreferences(durable, SampleCipher).autoDetect)
                }
                context.deleteSharedPreferences(durableName)
            }
        }

    private fun seedFuClipEnabled(editor: SharedPreferences.Editor) {
        editor.putString("import.enabled", "on")
        editor.putString("import.consent_version", "2026-10-01")
        editor.putString("import.notice_seen_version", "2026-10-01")
    }
}
