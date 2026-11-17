package com.couli.app.wallet

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText as textMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTheme
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.PageApi
import com.couli.core.network.SessionIdentity
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletDraftTexts
import com.couli.core.wallet.WalletErrors
import com.couli.core.wallet.WalletRateGate
import com.couli.core.wallet.stepup.FormOwner
import com.couli.core.wallet.stepup.SmsDeadlines
import com.couli.core.wallet.stepup.StepUpController
import com.couli.core.wallet.stepup.StepUpDeps
import com.couli.core.wallet.stepup.StepUpEnv
import com.couli.core.wallet.stepup.StepUpSheet
import com.couli.core.wallet.stepup.StepUpTags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-STEPUP-SEND-STALE-HANG 口径 3 的界面半边（补 Codex 交回的缺口：StepUpUiState 没有说明位显隐字段）。
 * 渲染真实 [StepUpSheet] 与真实 [StepUpController]，替身只管 PageApi 与身份源；不伪造 StepUpUiState。
 * 说明位 = 标题下一行（StepUpSheet.kt StepUpNotice）：现在只按 phase 选句子，READY 一律显示 step_up.sent。
 * 口径 3：发码失败态（stale / 本地拒，一条没发或结果不消费）说明位隐藏、格下错误提示用 wallet.network_error；正常成功说明位照常显示。
 * 成功发码后验证码输错仍显示说明（StepUpVisualBehaviorTest:165），不在本文件改口径。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StepUpStaleHangNoticeTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val env = Env()
    private val api = HoldingApi()
    private val controller by lazy {
        val deps =
            StepUpDeps(
                api = api,
                env = env,
                smsGate = WalletRateGate(scope),
                verifyGate = WalletRateGate(scope),
                sms = SmsDeadlines(),
                tokens = StepUpTokenCache(),
                clock = System::currentTimeMillis,
                service = scope,
            )
        StepUpController(deps, scope) { true }
    }

    @After fun close() = scope.cancel()

    /** 口径 1、3：发码在途时 access 消失（同账号同世代），迟到成功不消费 → 失败态不显示「已发送」，提示 network_error。 */
    @Test
    fun inFlightStaleSuccessHidesSentNoticeAndShowsNetworkError() {
        open()
        assertEquals(1, api.pending.size)
        rule.runOnIdle {
            env.accessAvailable = false
            api.pending.single().complete(success())
        }
        rule.waitForIdle()
        assertFailedSheet()
    }

    /** 口径 2、3：网络边界按 account_mismatch 本地拒绝（一条没发）→ 说明位隐藏、提示 network_error。 */
    @Test
    fun accountMismatchRejectHidesSentNotice() = localReject(LocalRejectReason.ACCOUNT_MISMATCH)

    /** 口径 2、3：网络边界按 session_unreadable 本地拒绝（一条没发）→ 说明位隐藏、提示 network_error。 */
    @Test
    fun sessionUnreadableRejectHidesSentNotice() = localReject(LocalRejectReason.SESSION_UNREADABLE)

    /** 口径 3、5：打开那一刻就判 stale，零发送 → 不悬在「正在发送」，也不显示「已发送」，提示 network_error。 */
    @Test
    fun openingStaleHidesSentNoticeAndShowsNetworkError() {
        env.accessAvailable = false
        open()
        assertTrue("零发送", api.calls.isEmpty())
        assertFailedSheet()
        assertTrue("不悬在发送中", sheetNodes(WalletDraftTexts.text("step_up.sending")).isEmpty())
    }

    /** 对照：正常成功说明位显示、没有错误行、显示倒计时。 */
    @Test
    fun normalSuccessShowsSentNotice() {
        open()
        rule.runOnIdle { api.pending.single().complete(success()) }
        rule.waitForIdle()
        assertEquals("成功发码显示说明位", 1, sheetNodes(sentText()).size)
        assertTrue("成功无错误行", errorNodes().isEmpty())
        assertTrue("成功起倒计时", sheetNodes(COUNTDOWN_TAIL, substring = true).isNotEmpty())
    }

    /** S2-01：本地拒绝隐藏说明位后，点击重新获取并成功回包，说明位和倒计时恢复、错误行消失。 */
    @Test
    fun localRejectThenManualRetrySuccessRestoresSentNotice() {
        api.reject = LocalRejectReason.ACCOUNT_MISMATCH
        open()
        assertEquals(1, api.calls.size)
        assertTrue(api.pending.isEmpty())
        assertFailedSheet()

        rule.runOnIdle { api.reject = null }
        rule.onNode(inSheet(textMatcher(WalletDraftTexts.text("step_up.resend"))), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals("手动重新获取才再次调用", 2, api.calls.size)
        rule.runOnIdle { api.pending.single().complete(success()) }
        rule.waitForIdle()
        assertEquals("重发成功恢复说明位", 1, sheetNodes(sentText()).size)
        assertTrue("重发成功清空错误行", errorNodes().isEmpty())
        assertTrue("重发成功起倒计时", sheetNodes(COUNTDOWN_TAIL, substring = true).isNotEmpty())
    }

    private fun localReject(reason: String) {
        api.reject = reason
        open()
        assertEquals("一次逻辑调用由网络边界本地拒绝", 1, api.calls.size)
        assertTrue("零传输", api.pending.isEmpty())
        assertFailedSheet()
    }

    private fun assertFailedSheet() {
        val sheets = rule.onAllNodes(hasTestTag(StepUpTags.STEP_UP_SHEET), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("先正：半屏仍在", sheets.isNotEmpty())
        assertTrue("口径 3：发送失败态隐藏「验证码已发送至…」说明位", sheetNodes(sentText()).isEmpty())
        val expected = WalletDraftTexts.text(WalletErrors.NETWORK_KEY)
        assertNotEquals("先正：wallet.network_error 有文案", "", expected)
        val errors = errorNodes().map { it.drawnText() }
        assertEquals("口径 3：错误提示用 wallet.network_error（D10-1792）", listOf(expected), errors)
    }

    private fun open() {
        rule.setContent {
            CouliTheme(darkTheme = false) {
                val panel by controller.state.collectAsState()
                panel?.let { StepUpSheet(controller, it, WalletDraftTexts, smsBlocked = false, verifyBlocked = false, restore = null) }
            }
        }
        rule.runOnIdle { controller.open(ACTION, OWNER, onToken = {}, onClosed = {}) }
        rule.waitForIdle()
    }

    private fun sentText(): String = WalletDraftTexts.text("step_up.sent")

    private fun sheetNodes(
        text: String,
        substring: Boolean = false,
    ): List<SemanticsNode> =
        rule.onAllNodes(inSheet(textMatcher(text, substring = substring)), useUnmergedTree = true).fetchSemanticsNodes()

    private fun errorNodes(): List<SemanticsNode> =
        rule.onAllNodes(hasTestTag(StepUpTags.STEP_UP_ERROR), useUnmergedTree = true).fetchSemanticsNodes()

    private fun inSheet(matcher: SemanticsMatcher): SemanticsMatcher = matcher and hasAnyAncestor(hasTestTag(StepUpTags.STEP_UP_SHEET))

    private fun SemanticsNode.drawnText(): String =
        config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .joinToString("") { it.text }

    private class Env : StepUpEnv {
        var accessAvailable = true

        override fun sessionEpoch(): Long = EPOCH

        override fun account(): String? = ACCOUNT.takeIf { accessAvailable }

        override fun identitySnapshot(): SessionIdentity =
            if (accessAvailable) {
                SessionIdentity(known = true, account = ACCOUNT, loginEpoch = EPOCH, partition = null)
            } else {
                SessionIdentity.unknown(EPOCH)
            }

        override fun stepUpPhone(account: String): String = PHONE

        override fun forceUpdate() = Unit
    }

    private class HoldingApi : PageApi {
        val calls = mutableListOf<ApiCall>()
        val pending = mutableListOf<CompletableDeferred<ApiResult>>()
        var reject: String? = null

        override suspend fun send(call: ApiCall): ApiResult {
            calls += call
            reject?.let { return ApiResult.LocalReject(it) }
            val answer = CompletableDeferred<ApiResult>()
            pending += answer
            return answer.await()
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs(): CallEpochs = CallEpochs(1, 1)
    }

    private companion object {
        const val ACCOUNT = "u-stale-hang"
        const val EPOCH = 3L
        const val PHONE = "13800005678"
        const val ACTION = "withdraw"
        const val COUNTDOWN_TAIL = "秒后可重新获取"
        val OWNER = FormOwner(ACCOUNT, EPOCH)

        fun success(): ApiResult =
            ApiResult.Success(Json.parseToJsonElement("""{"resend_after_sec":60,"expires_in_sec":300}"""), "trace-stale-hang-01", null)
    }
}
