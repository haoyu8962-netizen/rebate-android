@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.clipboard

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.ManualPasteAction
import com.couli.core.router.SessionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class PendingInviteClipboard17cTest {
    @Test
    fun automaticAndManualGuestConfirmOnlyWriteCodeAfterAllScheduledWorkSettles() =
        runTest {
            listOf(false, true).forEach { manual ->
                Clipboard0724Fixture(this, enabled = !manual).use { f ->
                    f.clipboard.pendingInvite.set("AB23CD")
                    f.system.content = ClipRead.Text("邀请码 K7M2QX")
                    f.foreground()
                    if (manual) {
                        f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                        f.settle()
                        f.clipboard.confirmManualInvite(f.clipboard.result.value as ClipboardResult.InviteConfirm)
                    } else {
                        f.clipboard.onPromptAction(checkNotNull(f.clipboard.prompt.value).id)
                    }
                    f.settle() // 与生产 workScope 同一 TestScheduler，执行至队列无可执行任务；不猜等待时长。
                    assertEquals("K7M2QX", f.clipboard.pendingInvite.peek())
                    assertEquals("peek 不取走", "K7M2QX", f.clipboard.pendingInvite.peek())
                    assertEquals("观察点是实际 PageApi.send 入口", 0, f.requests.size)
                    assertEquals("确认后不打开 Login 或其他页面", emptyList<Any>(), f.navigations)
                    assertEquals(0, f.system.writes)
                    assertNull(f.clipboard.prompt.value)
                }
            }
        }

    @Test
    fun closingAnotherPromptKeepsConfirmedCodeAcrossSessionAndBackgroundChanges() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                f.system.content = ClipRead.Text("邀请码 K7M2QX")
                f.foreground()
                f.clipboard.onPromptAction(checkNotNull(f.clipboard.prompt.value).id)
                f.settle()
                f.nextRound("邀请码 P8N3RY")
                f.clipboard.onPromptClose(checkNotNull(f.clipboard.prompt.value).id)
                f.settle()
                assertEquals("K7M2QX", f.clipboard.pendingInvite.peek())
                f.sessionEpoch += 1
                f.routing.session.value = SessionState.PHONE_BOUND
                f.clipboard.onSessionStored()
                f.settle()
                f.background()
                f.foreground()
                assertEquals("会话变化和进出后台不清", "K7M2QX", f.clipboard.pendingInvite.peek())
                assertEquals(0, f.requests.size)
                assertEquals(emptyList<Any>(), f.navigations)
            }
        }
}
