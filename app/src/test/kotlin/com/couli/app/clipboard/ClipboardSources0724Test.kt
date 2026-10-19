@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.clipboard

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.ClipboardFingerprint
import com.couli.core.clipboard.ClipboardSaltRead
import com.couli.core.clipboard.ClipboardText
import com.couli.core.router.PrivacyState
import com.couli.core.router.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** G28：替换的是生产来源的状态流；映射、订阅、关闭补读均调用生产函数，绝不写 overlay。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ClipboardSources0724Test {
    @Test fun loginTaskOnHomeWaitsThenFinishResumes() = assertSource(1, "login")

    @Test fun tradeJumpSourceWaitsThenFinishResumes() = assertSource(2, "trade_jump")

    @Test fun updatePromptSourceWaitsThenCloseResumes() = assertSource(4, "update_prompt")

    @Test
    fun g29_absentServicesAndAndroidUnimplementedModalsDoNotBlockHome() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                // 使用完整生产绑定函数的可空服务路径。支付、回跳确认本轮无状态，恒 false（F-AND-55）。
                f.clipboard.connectModalSources(trade = null, login = null, update = null)
                f.foreground()
                assertEquals(1, f.system.reads)
                assertEquals(1, f.parseBodies().size)
                f.card()
            }
        }

    private fun assertSource(
        index: Int,
        expected: String,
    ) = runTest {
        Clipboard0724Fixture(this).use { f ->
            val states = List(5) { MutableStateFlow(false) }
            states[index].value = true
            val sources =
                ClipboardModalSources(
                    payment = states[0],
                    loginTask = states[1],
                    tradeJump = states[2],
                    tradeReturnConfirmation = states[3],
                    updatePrompt = states[4],
                )
            // 生产 provider 必须调用这个同一绑定函数；三路真实事件到服务属性的行为另由 ClipboardServiceSources0724Test 锁定。
            f.clipboard.bindModalSources(sources)
            f.settle()
            assertEquals(expected, sources.current())
            f.foreground()
            f.noEffects()
            val epoch =
                f.clipboard.importCoordinator
                    .snapshot()
                    .epoch
            states[index].value = false
            // 不手动通知 onDialogsChanged，更不补 focus/pageReady；生产订阅负责转发关闭事件。
            f.settle()
            assertNull(sources.current())
            assertEquals(1, f.system.reads)
            assertEquals(1, f.parseBodies().size)
            f.card()
            assertEquals(
                epoch,
                f.clipboard.importCoordinator
                    .snapshot()
                    .epoch,
            )
            states[index].value = true
            f.settle()
            states[index].value = false
            f.settle()
            assertEquals("已消费轮次不因第二次关闭重读", 1, f.system.reads)
        }
    }

    @Test
    fun resumedRetriesAWaitingRoundButCannotReadAnAlreadyConsumedRound() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                var dialog = true
                f.clipboard.otherDialogShowing = { dialog }
                f.foreground()
                f.noEffects()
                val epoch =
                    f.clipboard.importCoordinator
                        .snapshot()
                        .epoch
                dialog = false // 故意不发模态通知：本条只验 Activity resumed 的转发。
                f.clipboard.onActivityResumed()
                f.settle()
                assertEquals(1, f.system.reads)
                f.card()
                repeat(3) { f.clipboard.onActivityResumed() }
                f.settle()
                assertEquals(1, f.system.reads)
                assertEquals(1, f.parseBodies().size)
                assertEquals(
                    epoch,
                    f.clipboard.importCoordinator
                        .snapshot()
                        .epoch,
                )
            }
        }

    @Test
    fun dialogsChangedAloneResumesWaitingRound() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                var dialog = true
                f.clipboard.otherDialogShowing = { dialog }
                f.foreground()
                f.noEffects()
                dialog = false
                f.clipboard.onDialogsChanged()
                f.settle()
                assertEquals(1, f.system.reads)
                f.card()
            }
        }

    @Test
    fun h5RestoreReevaluatesCurrentWaitingHomeWithoutFocusEvent() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                // 网页实例仍存活但 Home 已在前台；恢复事件必须重查当前页，不能把旧网页当自动目标。
                f.clipboard.onBackStack(null, null, setOf("web-alive"))
                f.clipboard.setWebAutoDetect("web-alive", false)
                var dialog = true
                f.clipboard.otherDialogShowing = { dialog }
                f.foreground()
                f.noEffects()
                dialog = false
                f.clipboard.setWebAutoDetect("web-alive", true)
                f.settle()
                assertEquals(1, f.system.reads)
                f.card()
                f.clipboard.setWebAutoDetect("web-alive", true)
                f.settle()
                assertEquals(1, f.parseBodies().size)
            }
        }

    @Test
    fun g27_agentNeverReadsOrUploadsAndReturningHomeDoesNotCatchUpUntilNextRound() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                f.clipboard.onShell("AgentChat", false)
                f.foreground()
                f.noEffects()
                f.clipboard.onActivityResumed()
                f.clipboard.onShell("Home", false)
                f.noEffects()
                f.nextRound()
                assertEquals(1, f.parseBodies().size)
            }
        }

    @Test
    fun unavailableSaltNeverUploadsAutomaticallyButManualStillUploadsExactlyOnce() =
        runTest {
            for (persisted in listOf(false, true)) {
                val cipher = SampleCipherImpl()
                Clipboard0724Fixture(this, cipher = cipher).use { f ->
                    if (persisted) assertTrue(f.prefs.salt.write(SAMPLE_SALT))
                    cipher.failing = true
                    f.foreground()
                    f.settle()
                    val reads = f.system.reads
                    val uploads = f.parseBodies().size
                    val result = f.clipboard.result.value
                    assertTrue(reads in 0..1)
                    assertEquals("新建盐失败或已有盐解密失败都不能自动上传", 0, uploads)
                    assertNull(result)
                    f.clipboard.paste(com.couli.core.clipboard.ManualPasteAction.ENTRY_HOME)
                    f.settle()
                    assertEquals(reads + 1, f.system.reads)
                    assertEquals(1, f.parseBodies().size)
                    f.card()
                }
            }
        }

    @Test
    fun g07_rotatingPersistedSaltDeletesOldProductSummaryAndWritesNewHmac() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                val link = Clipboard0724Fixture.LINK
                f.foreground()
                assertEquals(1, f.parseBodies().size)

                fun stored() =
                    f.disk.all
                        .filterKeys { it.startsWith("hash.") }
                        .values
                        .joinToString("|")
                val oldSalt = (f.prefs.salt.read() as ClipboardSaltRead.Present).salt
                assertArrayEquals(SAMPLE_SALT, oldSalt)
                val old = ClipboardFingerprint(oldSalt).hash(link)
                assertTrue(stored().contains(old))
                assertTrue(f.prefs.salt.write(OTHER_SAMPLE_SALT))
                f.nextRound()
                assertEquals(2, f.parseBodies().size)
                val newSalt = (f.prefs.salt.read() as ClipboardSaltRead.Present).salt
                assertArrayEquals(OTHER_SAMPLE_SALT, newSalt)
                assertTrue(stored().contains(ClipboardFingerprint(newSalt).hash(link)))
                assertTrue("旧商品摘要必须失效", !stored().contains(old))
                assertTrue("不能用无盐 hash", !stored().contains(ClipboardText.sha256(link)))
            }
        }

    @Test
    fun g07_accountAToBAlsoClearsProductSummaryWithoutClearingSaltOrSelfWrites() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                f.routing.session.value = SessionState.PHONE_BOUND
                val own = "https://u.jd.com/account-owned"
                assertTrue(f.clipboard.writer.write(own))
                f.nextRound()
                assertEquals(1, f.parseBodies().size)
                val salt = f.prefs.salt.read()
                // A/B 都是 PHONE_BOUND；不能只靠 session 枚举是否改变判断切换。
                f.sessionEpoch += 1
                f.clipboard.onSessionChanged()
                f.settle()
                assertEquals(SessionState.PHONE_BOUND, f.routing.session.value)
                assertEquals(salt, f.prefs.salt.read())
                assertTrue(f.clipboard.autoSwitch.value)
                assertTrue(f.clipboard.ledger.contains(own))
                f.nextRound()
                assertEquals(2, f.parseBodies().size)
            }
        }

    @Test
    fun inviteFillInBasicModeDoesNotReadUploadOrShowPurposeNotice() =
        runTest {
            Clipboard0724Fixture(this, enabled = null).use { f ->
                f.privacy = PrivacyState.BASIC_MODE
                f.clipboard.onPrivacyChanged(PrivacyState.BASIC_MODE)
                f.clipboard.onBackStack("InviteBackfill", "invite", setOf("invite"))
                f.foreground()
                assertNull(f.clipboard.pasteInviteCode())
                f.noEffects()
                assertNull(f.clipboard.consentNotice.value)
            }
        }

    @Test
    fun inviteFirstTapWaitsForNoticeAndDeliversThatTapExactlyOnceForEitherChoice() =
        runTest {
            for (enable in listOf(false, true)) {
                Clipboard0724Fixture(this, enabled = null).use { f ->
                    f.clipboard.onBackStack("InviteBackfill", "invite", setOf("invite"))
                    f.system.content = ClipRead.Text("邀请码 K7M2QX")
                    f.foreground()
                    val filled = mutableListOf<String?>()
                    assertNull(f.clipboard.pasteInviteCode { filled += it })
                    f.noEffects()
                    assertTrue(filled.isEmpty())
                    assertTrue(f.clipboard.consentNotice.value != null)
                    if (enable) f.clipboard.onNoticeEnable() else f.clipboard.onNoticeNotNow()
                    f.settle()
                    assertEquals(listOf("K7M2QX"), filled)
                    assertEquals(1, f.system.reads)
                    assertEquals(0, f.parseBodies().size)
                    assertNull(f.clipboard.result.value)
                    // G21：手动用途弹层也已消费本轮；同轮开启不会补一次自动读。
                    f.clipboard.onActivityResumed()
                    f.clipboard.onNoticeNotNow()
                    f.settle()
                    assertEquals(listOf("K7M2QX"), filled)
                    assertEquals(1, f.system.reads)
                }
            }
        }
}
