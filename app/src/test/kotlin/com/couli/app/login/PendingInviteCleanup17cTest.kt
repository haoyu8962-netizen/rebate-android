package com.couli.app.login

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.AppModule
import com.couli.app.privacy.AppPrivacy
import com.couli.core.config.AppEnvironment
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.PrivacyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 不创建 AppClipboard：容器自己的生产提供函数必须登记清理，不能借剪贴板 connect 偷渡。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class PendingInviteCleanup17cTest {
    @Test
    fun withdrawalToBasicModeClearsThroughProductionPrivacyEntry() {
        val rig = CleanupRig()
        val pending = AppModule.pendingInviteCode(rig.privacy, rig.cleaners)
        pending.set("K7M2QX")
        rig.privacy.withdraw()
        assertEquals(PrivacyState.BASIC_MODE, rig.privacy.conclusion)
        assertNull(pending.peek())
        assertNull(pending.state.value)
    }

    @Test
    fun policyUpgradeToNotDecidedClearsWithoutClipboardOrWithdrawal() {
        val rig = CleanupRig()
        val pending = AppModule.pendingInviteCode(rig.privacy, rig.cleaners)
        pending.set("K7M2QX")
        rig.store.consentedVersion = "1" // 当前包内政策为 2；从持久化旧版本启动的生产入口发布新结论。
        rig.privacy.coldStart()
        assertEquals(PrivacyState.NOT_DECIDED, rig.privacy.conclusion)
        assertNull(pending.peek())
        assertNull(pending.state.value)
    }

    @Test
    fun environmentSwitchClearsViaTheRegisteredProductionCleanerBeforeCommit() {
        val rig = CleanupRig()
        val pending = AppModule.pendingInviteCode(rig.privacy, rig.cleaners)
        pending.set("K7M2QX")
        assertTrue("生产提供函数已自行注册", rig.cleaners.registered().isNotEmpty())
        var committed = false
        rig.cleaners.switching(AppEnvironment.TEST, AppEnvironment.STAGING) {
            assertNull("新环境提交前清理完成", pending.peek())
            committed = true
        }
        assertTrue(committed)
        assertNull(pending.state.value)
    }

    @Test
    fun ordinaryPrivacyRefreshKeepsCodeAndANewContainerDoesNotRestoreIt() {
        val rig = CleanupRig()
        val pending = AppModule.pendingInviteCode(rig.privacy, rig.cleaners)
        pending.set("K7M2QX")
        rig.privacy.coldStart()
        assertEquals(PrivacyState.CONSENTED, rig.privacy.conclusion)
        assertEquals("K7M2QX", pending.peek())
        // 重用同一份存储与同意状态，再组装代表新进程的容器。
        val restarted = AppModule.pendingInviteCode(rig.privacy, EnvironmentSwitchCleanerRegistry())
        assertNull(restarted.peek())
    }

    @Test
    fun draftTextsUseTheRealFormatterAndContainOnlyTheAgreedSentenceAndRemoveLabel() {
        assertEquals("已识别好友邀请码 K7M2QX", LoginDraftTexts.format("login.pending_invite", mapOf("code" to "K7M2QX")))
        assertEquals("已识别好友邀请码 P8N3RY", LoginDraftTexts.format("login.pending_invite", mapOf("code" to "P8N3RY")))
        assertEquals("移除", LoginDraftTexts.format("login.pending_invite.remove", emptyMap()))
    }
}

private class CleanupRig {
    val store = CleanupStore()
    val cleaners = EnvironmentSwitchCleanerRegistry()
    val privacy =
        AppPrivacy(
            PrivacyGate(store, { 1_700_000_000_000L }, "2").apply {
                withdrawalCleanup = {}
                retryScheduler = RetryScheduler { _, _ -> }
            },
            { true },
        ).also { it.coldStart() }
}

private class CleanupStore : PrivacyStore {
    override var decisionRaw = "consented"
    override var consentedVersion: String? = "2"
    override var consentedAtMillis: Long? = 1_700_000_000_000L
    override var basicMode = false
}
