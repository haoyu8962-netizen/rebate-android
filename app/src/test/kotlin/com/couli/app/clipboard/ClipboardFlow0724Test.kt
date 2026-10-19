@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.clipboard

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.Clipboard0724Fixture.Companion.DAY
import com.couli.app.clipboard.Clipboard0724Fixture.Companion.LINK
import com.couli.app.clipboard.Clipboard0724Fixture.Companion.json
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.ManualPasteAction
import com.couli.core.network.ApiResult
import com.couli.core.router.PrivacyState
import com.couli.core.router.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** AC-S1-03 Android：所有负向断言先排空有限工作；网络替身在 send 入口记录。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("TooManyFunctions") // AC-S1-03 各步骤与裁定分别保留独立用例。
class ClipboardFlow0724Test {
    @Test
    fun ac03_1_unselectedAndNotNowNeverAutoReadButOneManualClickWorks() =
        runTest {
            Clipboard0724Fixture(this, enabled = null).use { f ->
                assertFalse(f.clipboard.autoSwitch.value)
                f.foreground()
                f.noEffects()
                assertNotNull(f.clipboard.consentNotice.value)
                f.clipboard.onNoticeNotNow()
                f.settle()
                f.nextRound()
                f.noEffects()
                assertNull(f.clipboard.consentNotice.value)
                f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                f.settle()
                assertEquals(1, f.system.reads)
                assertEquals(1, f.parseBodies().size)
                f.card()
            }
        }

    @Test
    fun g15_firstManualTapShowsNoticeThenContinuesThatSameTapForEitherChoice() =
        runTest {
            for (enable in listOf(false, true)) {
                Clipboard0724Fixture(this, enabled = null).use { f ->
                    // 用普通互斥弹窗阻止回前台来源，以单独证明手动来源。
                    f.clipboard.otherDialogShowing = { true }
                    f.foreground()
                    f.clipboard.otherDialogShowing = { false }
                    f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                    f.settle()
                    f.noEffects()
                    assertNotNull(f.clipboard.consentNotice.value)
                    if (enable) f.clipboard.onNoticeEnable() else f.clipboard.onNoticeNotNow()
                    f.settle()
                    assertEquals(1, f.system.reads)
                    assertEquals(1, f.parseBodies().size)
                    assertEquals(enable, f.clipboard.autoSwitch.value)
                    f.card()
                }
            }
        }

    @Test
    fun ac03_2_and_5_directMinimalUploadOneCurrentPageCardAndClipboardUnchanged() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                val original = "私密前缀 $LINK 「耳机」 私密尾巴"
                f.system.content = ClipRead.Text(original)
                f.foreground()
                val request = f.parseBodies().single()
                assertEquals(setOf("text", "scene"), request.keys)
                assertEquals(json("""{"text":"$LINK\n耳机","scene":"clipboard"}"""), request)
                assertNull(f.clipboard.prompt.value)
                val result = f.card()
                assertEquals(1, result.rows.size)
                assertTrue("出卡不得直接跳详情", f.navigations.isEmpty())
                val coordinator = f.clipboard.importCoordinator
                repeat(3) {
                    f.clipboard.onShell("Home", false)
                    f.clipboard.onBackStack(null, null, emptySet())
                    f.clipboard.onWindowFocus(false)
                    f.clipboard.onWindowFocus(true)
                }
                f.settle()
                assertSame("同一进程重组不换协调器", coordinator, f.clipboard.importCoordinator)
                assertEquals(result.id, f.card().id)
                assertEquals(1, f.system.reads)
                assertEquals(1, f.parseBodies().size)
                assertEquals(ClipRead.Text(original), f.system.content)
                assertEquals(0, f.system.writes)
                assertFalse(
                    f.disk.all
                        .toString()
                        .contains("私密"),
                )
                assertFalse(
                    f.disk.all
                        .toString()
                        .contains(LINK),
                )
                assertFalse(f.requests.toString().contains("私密"))
                assertFalse(f.navigations.toString().contains("私密"))
                val logs = ShadowLog.getLogs().joinToString { it.msg + it.throwable?.stackTraceToString().orEmpty() }
                assertFalse(logs.contains("私密"))
            }
        }

    @Test
    fun ac03_2_b_c_andG08_emptyCandidateDoesNotMarkButFailedParseMarksBeforeSend() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                val before = f.disk.all.filterKeys { it.startsWith("hash.") }
                f.nextRound("今天只是普通聊天没有商品")
                f.noEffects(reads = 1)
                assertEquals(before, f.disk.all.filterKeys { it.startsWith("hash.") })
                var recordedAtEntry = false
                f.atSend = { recordedAtEntry = f.disk.all.filterKeys { it.startsWith("hash.") } != before }
                f.response = ApiResult.TransportFailure("io", null, null, false)
                f.nextRound()
                assertTrue("G08 必须在 send 入口前已记候选摘要", recordedAtEntry)
                assertEquals(1, f.parseBodies().size)
                f.now += DAY - 1
                f.nextRound("另一个私密包装 $LINK")
                assertEquals("按候选摘要去重，不按整段原文", 1, f.parseBodies().size)
                f.now += 1
                f.response = Clipboard0724Fixture.success("tb:after-ttl")
                f.nextRound()
                assertEquals(2, f.parseBodies().size)
                assertTrue(f.card().toString().contains("tb:after-ttl"))
            }
        }

    @Test
    fun g14_manualDoesNotReadOrWriteDedupAndIgnoresTheLastTenSelfWrites() =
        runTest {
            Clipboard0724Fixture(this, enabled = false).use { f ->
                f.foreground()
                assertTrue(f.clipboard.writer.write(LINK))
                val before = f.disk.all.toMap()
                val hashReadsBefore = f.hashReads
                repeat(2) {
                    f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                    f.settle()
                    assertEquals(it + 1, f.parseBodies().size)
                    f.clipboard.consumeResult(f.card())
                }
                assertEquals("手动不读写商品缓存，不更换盐", before, f.disk.all)
                assertEquals("手动连缓存读取也不能做", hashReadsBefore, f.hashReads)
                f.clipboard.setAutoSwitch(true)
                f.nextRound()
                assertEquals("自动仍排除自写", 2, f.parseBodies().size)
                repeat(10) { assertTrue(f.clipboard.writer.write("https://u.jd.com/write$it")) }
                f.nextRound()
                assertEquals("第 11 次写入挤掉旧自写记录", 3, f.parseBodies().size)
                assertEquals(11, f.system.writes)
            }
        }

    @Test
    fun manualDoesNotSeedAutomaticTtlAndAutomaticTtlDoesNotBlockManual() =
        runTest {
            Clipboard0724Fixture(this, enabled = false).use { f ->
                f.foreground()
                f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                f.settle()
                f.clipboard.consumeResult(f.card())
                f.clipboard.setAutoSwitch(true)
                f.nextRound()
                assertEquals(2, f.parseBodies().size)
                f.clipboard.consumeResult(f.card())
                f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                f.settle()
                assertEquals(3, f.parseBodies().size)
                f.nextRound()
                assertEquals(3, f.parseBodies().size)
            }
        }

    @Test
    fun g07_logoutClearsProductSummaryButKeepsSwitchSaltAndSelfWrites() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                f.routing.session.value = SessionState.PHONE_BOUND
                val own = "https://u.jd.com/own0724"
                assertTrue(f.clipboard.writer.write(own))
                f.nextRound()
                assertEquals(1, f.parseBodies().size)
                val salt = f.prefs.salt.read()
                f.sessionEpoch += 1
                f.routing.session.value = SessionState.GUEST
                f.clipboard.onSessionChanged()
                f.settle()
                assertTrue(f.clipboard.autoSwitch.value)
                assertEquals(salt, f.prefs.salt.read())
                assertTrue(f.clipboard.ledger.contains(own))
                f.nextRound()
                assertEquals(2, f.parseBodies().size)
                f.nextRound(own)
                assertEquals(2, f.parseBodies().size)
            }
        }

    @Test
    fun ac03_3_switchOffStopsAutoButManualStillParses() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                f.clipboard.setAutoSwitch(false)
                f.foreground()
                f.noEffects()
                f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                f.settle()
                assertEquals(1, f.system.reads)
                assertEquals(1, f.parseBodies().size)
                f.card()
            }
        }

    @Test
    fun ac03_4_sensitiveRoundNeverCatchesUpAtHome() =
        runTest {
            for (route in listOf("Withdraw", "Login", "FindOrder", "InviteBackfill")) {
                Clipboard0724Fixture(this).use { f ->
                    f.clipboard.onBackStack(route, "sensitive-page", setOf("sensitive-page"))
                    f.foreground()
                    f.noEffects()
                    f.clipboard.onBackStack(null, null, emptySet())
                    f.clipboard.onWindowFocus(true)
                    f.noEffects()
                    f.nextRound()
                    assertEquals(1, f.parseBodies().size)
                }
            }
        }

    @Test
    fun ac03_6_switchHookCancelsQueuedReadEvenAfterImmediateReenable() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                f.process.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                assertEquals(
                    "G29 生命周期观察者同步建轮，不能二次 launch",
                    1L,
                    f.clipboard.importCoordinator
                        .snapshot()
                        .epoch,
                )
                f.clipboard.setAutoSwitch(false)
                f.clipboard.setAutoSwitch(true) // G29：重开明确不调 onPageReady，覆盖 0723 旧接线说明。
                f.noEffects() // 执行时开关已开启：只能靠撤销旧队列拦住。
                f.nextRound()
                assertEquals(1, f.system.reads)
                assertEquals(1, f.parseBodies().size)
            }
        }

    @Test
    fun ac03_6_switchHookCancelsInFlightResponseEvenAfterImmediateReenable() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                val delayed = CompletableDeferred<ApiResult>()
                f.held += delayed
                f.foreground()
                assertEquals(1, f.parseBodies().size)
                f.clipboard.setAutoSwitch(false)
                f.clipboard.setAutoSwitch(true) // G29：重开不调 onPageReady，也不恢复旧 parse。
                delayed.complete(Clipboard0724Fixture.success("tb:late"))
                f.settle()
                assertNull(f.clipboard.result.value)
                assertNull(
                    f.clipboard.importCoordinator
                        .snapshot()
                        .activeAutoTask,
                )
                assertEquals(1, f.system.reads)
                assertEquals(1, f.parseBodies().size)
                f.nextRound("https://u.jd.com/after-reenable")
                assertEquals(2, f.parseBodies().size)
                f.card()
            }
        }

    @Test
    fun ac03_basicModeAndWithdrawalHookCancelBeforeReading() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                f.process.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                assertEquals(
                    "G29 撤回发生前轮次已同步建立",
                    1L,
                    f.clipboard.importCoordinator
                        .snapshot()
                        .epoch,
                )
                f.privacy = PrivacyState.BASIC_MODE
                f.clipboard.onPrivacyChanged(PrivacyState.BASIC_MODE, withdrawn = true)
                assertFalse(f.clipboard.autoSwitch.value)
                f.privacy = PrivacyState.CONSENTED
                f.clipboard.onPrivacyChanged(PrivacyState.CONSENTED)
                f.preferences.enable("1")
                f.clipboard.setAutoSwitch(true)
                f.noEffects() // 同轮恢复前提：没有新 pageReady，旧排队尝试不能复活。
                f.nextRound()
                assertEquals("下一轮的有效动作仍可读取", 1, f.parseBodies().size)
                assertNull(
                    f.clipboard.importCoordinator
                        .snapshot()
                        .activeAutoTask,
                )
            }
        }

    @Test
    fun g19_shareStillWaitsForConfirmationAndSuppressesClipboardForThatRound() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                assertTrue(f.clipboard.receiveShare("text/plain", LINK))
                f.foreground()
                assertEquals(0, f.system.reads)
                assertEquals(0, f.parseBodies().size)
                assertTrue(
                    f.clipboard.prompt.value
                        ?.share == true,
                )
                f.clipboard.onPromptAction(checkNotNull(f.clipboard.prompt.value).id)
                f.settle()
                assertEquals("\"share_ext\"", f.parseBodies().single()["scene"].toString())
                f.nextRound()
                assertEquals("分享不占商品自动去重", 2, f.parseBodies().size)
                assertEquals("\"clipboard\"", f.parseBodies().last()["scene"].toString())
            }
        }

    @Test
    fun g25_searchTypingButtonLooksUpButInviteFieldOnlyFills() =
        runTest {
            Clipboard0724Fixture(this, enabled = false).use { f ->
                f.foreground()
                f.clipboard.onBackStack("Search", "search-1", setOf("search-1"))
                f.clipboard.paste(ManualPasteAction.ENTRY_SEARCH_TYPING)
                f.settle()
                assertEquals(1, f.parseBodies().size)
                f.card()
                f.clipboard.onBackStack("InviteBackfill", "invite-1", setOf("invite-1"))
                f.system.content = ClipRead.Text("邀请码 K7M2QX")
                assertEquals("K7M2QX", f.clipboard.pasteInviteCode())
                f.settle()
                assertEquals("fill_input 不上传", 1, f.parseBodies().size)
                assertEquals(2, f.system.reads)
                assertEquals(0, f.system.writes)
            }
        }
}
