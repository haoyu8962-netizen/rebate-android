@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.clipboard

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.Clipboard0724Fixture.Companion.DAY
import com.couli.app.clipboard.Clipboard0724Fixture.Companion.LINK
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.ManualPasteAction
import com.couli.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ClipboardResults0724Test {
    @Test
    fun offPlatformStillReadsButDoesNotUploadAndManualExplainsWhy() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                f.config =
                    Clipboard0724Fixture.json(
                        """{"clipboard":{"consent_version":"1"},"features":{"flags":{"clipboard.enabled.taobao":false}}}""",
                    )
                f.foreground()
                f.noEffects(reads = 1)
                f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                f.settle()
                assertEquals(2, f.system.reads)
                assertEquals(0, f.parseBodies().size)
                val notice = f.clipboard.result.value as ClipboardResult.Notice
                val expected = ClipboardDraftTexts.text("clipboard.platform_off")
                assertTrue("平台关闭文案不能是空串", expected.isNotBlank())
                assertEquals(expected, notice.text)
                f.nextRound("https://u.jd.com/allowed0724")
                assertEquals(1, f.parseBodies().size)
            }
        }

    @Test
    fun fAnd54_manualOnAnotherPageIsNotBlockedAndOldResponseCannotReplaceItsCard() =
        runTest {
            Clipboard0724Fixture(this, enabled = false).use { f ->
                f.foreground()
                val old = CompletableDeferred<ApiResult>()
                f.held += old
                f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                f.settle()
                assertEquals(1, f.parseBodies().size)
                f.clipboard.onBackStack("Search", "search-B", setOf("search-B"))
                f.response = Clipboard0724Fixture.success("tb:new-page-B")
                f.clipboard.paste(ManualPasteAction.ENTRY_SEARCH_TYPING)
                f.settle()
                assertEquals("跨页有效点击不受 A 页 busy 阻挡", 2, f.parseBodies().size)
                val fresh = f.card()
                assertTrue(fresh.toString().contains("tb:new-page-B"))
                old.complete(Clipboard0724Fixture.success("tb:obsolete-page-A"))
                f.settle()
                assertEquals(fresh, f.card())
                assertEquals(2, f.system.reads)
            }
        }

    @Test
    fun ac03_6_readButNotSubmittedIsDiscardedAndDoesNotSeedDedup() =
        runTest {
            for (background in listOf(false, true)) {
                Clipboard0724Fixture(this).use { f ->
                    f.system.afterRead = {
                        if (background) {
                            f.process.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
                        } else {
                            f.clipboard.setAutoSwitch(false)
                        }
                    }
                    f.foreground()
                    f.noEffects(reads = 1)
                    f.system.afterRead = {}
                    f.clipboard.setAutoSwitch(true)
                    f.nextRound()
                    assertEquals("取消前没有发 parse 的候选不能记 TTL", 1, f.parseBodies().size)
                    f.card()
                }
            }
        }

    @Test
    fun r18_emptyAndReadExceptionsEndTheRoundWithoutPollingAndManualCanRecover() =
        runTest {
            for (mode in listOf("empty", "unavailable", "once", "persistent")) {
                Clipboard0724Fixture(this).use { f ->
                    f.system.content =
                        when (mode) {
                            "empty" -> ClipRead.Text("")
                            "unavailable" -> ClipRead.Unavailable
                            else -> ClipRead.Text(LINK)
                        }
                    f.system.failure = {
                        if (mode == "persistent" || (mode == "once" && f.system.reads == 1)) SecurityException("private-canary") else null
                    }
                    f.foreground()
                    f.noEffects(reads = 1)
                    assertNull(
                        f.clipboard.importCoordinator
                            .snapshot()
                            .activeAutoTask,
                    )
                    assertTrue(
                        f.clipboard.importCoordinator
                            .snapshot()
                            .consumed,
                    )
                    f.now += DAY
                    repeat(3) {
                        f.clipboard.onWindowFocus(true)
                        f.settle()
                    }
                    f.noEffects(reads = 1)
                    f.system.failure = null
                    f.system.content = ClipRead.Text(LINK)
                    f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                    f.settle()
                    assertEquals(2, f.system.reads)
                    assertEquals(1, f.parseBodies().size)
                    f.card()
                    // 恢复后的再次失败：下一轮结束，不覆盖成旧内容、不轮询。
                    f.system.failure = { SecurityException("second-private-canary") }
                    f.nextRound()
                    val logs = ShadowLog.getLogs().joinToString { it.msg + it.throwable?.stackTraceToString().orEmpty() }
                    assertFalse(logs.contains("private-canary"))
                    assertEquals(3, f.system.reads)
                    assertEquals(1, f.parseBodies().size)
                    assertNull(f.clipboard.result.value)
                    assertFalse(
                        f.disk.all
                            .toString()
                            .contains("canary"),
                    )
                }
            }
        }

    @Test
    fun r24_explicitRetryUsesApprovedCandidatesAndDoesNotReadNewClipboardContent() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                f.response = ApiResult.TransportFailure("io", null, null, false)
                f.foreground()
                assertEquals(1, f.parseBodies().size)
                val failure = checkNotNull(f.clipboard.result.value)
                val approved = f.parseBodies().single()
                repeat(3) {
                    f.clipboard.onWindowFocus(true)
                    f.settle()
                }
                assertEquals(1, f.parseBodies().size)
                f.system.content = ClipRead.Text("新剪贴板内容不允许随旧重试上传 https://u.jd.com/new")
                f.response = Clipboard0724Fixture.success("tb:retry-fresh-result")
                f.clipboard.retryResult(failure)
                f.settle()
                assertEquals(listOf(approved, approved), f.parseBodies())
                assertEquals(1, f.system.reads)
                assertTrue(f.card().toString().contains("tb:retry-fresh-result"))
                f.clipboard.retryResult(failure)
                f.settle()
                assertEquals("旧重试按钮失效", 2, f.parseBodies().size)
            }
        }

    @Test
    fun r25_inviteDedupIsIndependentAndAppliesToAutomaticAndManual() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                val invite = "邀请码 K7M2QX"
                f.system.content = ClipRead.Text(invite)
                f.foreground()
                assertEquals(
                    "K7M2QX",
                    f.clipboard.prompt.value
                        ?.inviteCode,
                )
                assertEquals(0, f.requests.size)
                assertFalse((f.clipboard.pendingInvite.peek() != null))
                f.clipboard.onPromptClose(checkNotNull(f.clipboard.prompt.value).id)
                f.settle()
                f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                f.settle()
                assertNull(f.clipboard.prompt.value)
                // FU-X-INVITE-MANUAL-DEDUP（BR-INV-04 ④、D10-593 J4）：自动出过条的同一原文，24 小时内手动粘贴也静默结束
                assertEquals("手动粘贴确实读了剪贴板", 2, f.system.reads)
                assertNull("手动粘贴同样受邀请去重记录阻挡：不出确认条", f.clipboard.result.value)
                assertEquals("被挡下不上传", 0, f.requests.size)
                f.now += DAY - 1
                f.nextRound(invite)
                assertNull(f.clipboard.prompt.value)
                f.now += 1
                f.nextRound(invite)
                assertEquals(
                    "K7M2QX",
                    f.clipboard.prompt.value
                        ?.inviteCode,
                )
                assertEquals(0, f.requests.size)
                f.clipboard.onPromptAction(checkNotNull(f.clipboard.prompt.value).id)
                f.settle()
                assertTrue("确认后才保存待绑定码", (f.clipboard.pendingInvite.peek() != null))
                assertEquals(0, f.parseBodies().size)
                f.nextRound("$LINK 邀请码 K7M2QX")
                f.card()
                assertNull(f.clipboard.prompt.value)
                assertFalse(
                    f
                        .parseBodies()
                        .single()
                        .toString()
                        .contains("K7M2QX"),
                )
            }
        }
}
