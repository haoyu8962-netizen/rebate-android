package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.network.ApiResult
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 只登记截图入口（测试作者不录基准）：§1.1 E 有效 20 张中 19 张（LaborAgreementResign 待 FU，G-W24）+ 深色 + 200% w390 / w320 + w840 + 无稿状态。
 * 每个入口先经真实点击 / 真实回复到达状态并核对关键节点，再截图；半屏、弹窗、轻提示用 captureScreenRoboImage（含独立窗口与遮罩）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions") // 每张基准一个入口
class PayoutIdentityVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PayoutIdentityVisualHost(rule) }
    private val p by lazy { PayoutIdentityProbe(rule) }

    @After fun close() = host.close()

    @Test fun payoutAccountLight() = payout("unbound", "PayoutAccount-light")

    @Test fun payoutAccountDark() = payout("unbound", "PayoutAccount-dark", dark = true)

    @Test fun payoutAccountBankLight() = payout("bank_form", "PayoutAccountBank-v2-light")

    @Test fun payoutAccountBoundLight() = payout("boundAlipay", "PayoutAccountBound-light")

    @Test fun payoutAccountBoundBankLight() = payout("boundBank", "PayoutAccountBoundBank-light")

    @Test fun payoutAccountBoundDark() = payout("boundAlipay", "PayoutAccountBoundDark-dark", dark = true)

    @Test fun payoutAccountBoundExhaustedLight() = payout("boundExhausted", "PayoutAccountBound-exhausted-light")

    @Test fun payoutAccountBlockedLight() = payout("blocked", "PayoutAccountBlocked-light")

    @Test fun payoutAccountBlockedDark() = payout("blocked", "PayoutAccountBlocked-dark", dark = true)

    @Test fun payoutErrorsChangeLimit() = payout("changeLimit", "PayoutAccountErrors-1-change_limit-light")

    @Test fun payoutErrorsVerifyLimit() = payout("verifyLimit", "PayoutAccountErrors-2-verify_limit-light")

    @Test fun payoutErrorsPayeeMismatch() = payout("payeeMismatch", "PayoutAccountErrors-3-payee_mismatch-light")

    @Test fun payoutErrorsAccountTaken() = payout("accountTaken", "PayoutAccountErrors-4-account_taken-light")

    @Test fun payoutErrorsBusy() = payout("busy", "PayoutAccountErrors-5-busy-light")

    @Test fun payoutPendingConfirmLight() = payout("pending", "PayoutAccountPendingConfirm-light")

    @Test fun payoutPendingBusyLight() = payout("pending_busy", "PayoutAccountPendingBusy-light")

    @Test fun payoutPendingAbandonConfirmLight() = payout("abandon", "PayoutAccountPendingAbandonConfirm-light")

    @Test fun payoutPendingStillLight() = payout("still", "PayoutAccountPendingStill-light")

    @Test fun payoutPendingAbandonedLight() = payout("abandoned", "PayoutAccountPendingAbandoned-light")

    @Test fun payoutPendingCompletedLight() = payout("completed", "PayoutAccountPendingCompleted-light")

    @Test fun payoutValidationLight() = payout("validation", "PayoutAccount-validation-light")

    @Test fun payoutLoadingLight() = payout("loading", "state-payout-loading-light")

    @Test fun payoutErrorLight() = payout("load_error", "state-payout-error-light")

    @Test
    @Config(fontScale = 2f)
    fun payoutBoundFont200W390() = payout("boundAlipay", "PayoutAccountBound-font200-w390", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi", fontScale = 2f)
    fun payoutBoundFont200W320() = payout("boundAlipay", "PayoutAccountBound-font200-w320", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi", fontScale = 2f)
    fun payoutFormFont200W320() = payout("unbound", "PayoutAccount-font200-w320", fontScale = 2f)

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun payoutW840() = payout("unbound", "PayoutAccount-w840")

    @Test fun realNameLight() = realname("form", "RealName-light")

    @Test fun realNameDark() = realname("form", "RealNameDark-dark", dark = true)

    @Test fun realNameConsentLight() = realname("consent", "RealNameConsent-v2-light")

    @Test fun realNameConsentDark() = realname("consent", "RealNameConsent-v2-dark", dark = true)

    @Test
    @Config(fontScale = 2f)
    fun realNameConsentFont200() = realname("consent", "RealNameConsent-v2-font200", fontScale = 2f)

    @Test fun realNameErrorsVerifyFailed() = realname("verifyFailed", "RealNameErrors-1-verify_failed-light")

    @Test fun realNameErrorsIdTaken() = realname("idTaken", "RealNameErrors-2-id_taken-light")

    @Test fun realNameErrorsAge() = realname("age", "RealNameErrors-3-age-light")

    @Test fun realNameErrorsDailyLimit() = realname("dailyLimit", "RealNameErrors-4-daily_limit-light")

    @Test fun realNameErrorsDark() = realname("verifyFailed", "RealNameErrors-1-verify_failed-dark", dark = true)

    @Test fun realNameServerError() = realname("server", "state-realname-50301-light")

    @Test fun realNameFieldErrors() = realname("fields", "state-realname-validation-light")

    @Test fun realNameSubmitting() = realname("submitting", "state-realname-submitting-light")

    @Test fun realNameUnavailable() = realname("release", "state-realname-unavailable-light")

    @Test
    @Config(fontScale = 2f)
    fun realNameFont200W390() = realname("form", "RealName-font200-w390", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi", fontScale = 2f)
    fun realNameFont200W320() = realname("form", "RealName-font200-w320", fontScale = 2f)

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun realNameW840() = realname("form", "RealName-w840")

    @Test fun laborLight() = labor("content", "LaborAgreement-v2-light")

    @Test fun laborDark() = labor("content", "LaborAgreementDark-dark", dark = true)

    @Test fun laborCheckedLight() = labor("checked", "LaborAgreement-v2-checked-light")

    @Test fun laborLoading() = labor("loading", "state-labor-loading-light")

    @Test fun laborLoadError() = labor("load_error", "state-labor-error-light")

    @Test fun laborSigning() = labor("signing", "state-labor-signing-light")

    @Test fun laborSignFailed() = labor("sign_failed", "state-labor-sign-failed-light")

    @Test fun laborSignFailedDark() = labor("sign_failed", "state-labor-sign-failed-dark", dark = true)

    @Test fun laborUnavailable() = labor("release", "state-labor-unavailable-light")

    @Test
    @Config(fontScale = 2f)
    fun laborFont200W390() = labor("checked", "LaborAgreement-font200-w390", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi", fontScale = 2f)
    fun laborFont200W320() = labor("checked", "LaborAgreement-font200-w320", fontScale = 2f)

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun laborW840() = labor("content", "LaborAgreement-w840")

    private fun payout(
        scene: String,
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val bound = scene.startsWith("bound")
        host.api.payout =
            when {
                bound -> PayoutIdentityVisualFixtures.ok(PayoutIdentityVisualFixtures.payout(scene))
                scene in FORM_SCENES -> PayoutIdentityVisualFixtures.ok(PayoutIdentityVisualFixtures.payout("unbound"))
                scene == "load_error" -> WithdrawVisualFixtures.failure()
                else -> PayoutIdentityVisualFixtures.ok(PayoutIdentityVisualFixtures.payout("boundAlipay"))
            }
        if (scene in SAVE_ERROR_SCENES) {
            host.api.write = PayoutIdentityVisualFixtures.saveError(scene)
        }
        if (scene.startsWith("pending") || scene in PENDING_SCENES) {
            host.api.pending = WithdrawVisualFixtures.pending(payout = true, busy = scene == "pending_busy")
        }
        if (scene == "loading") host.api.readHold = CompletableDeferred()
        host.launch(PayoutIdentityPage.PAYOUT, dark = dark, fontScale = fontScale, shell = true)
        val overlay = payoutAct(scene, bound)
        capture("payout-v", name, overlay)
    }

    @Suppress("CyclomaticComplexMethod") // 每个画板一个状态分支
    private fun payoutAct(
        scene: String,
        bound: Boolean,
    ): Boolean =
        when (scene) {
            "bank_form" -> {
                tap(WalletTags.PAYOUT_METHOD_BANK)
                host.type(WalletTags.PAYOUT_CARD, PayoutIdentityVisualFixtures.form("bank", "cardNo"))
                host.type(WalletTags.PAYOUT_BANK_NAME, PayoutIdentityVisualFixtures.form("bank", "bankName"))
                false
            }

            "validation" -> {
                tap(WalletTags.PAYOUT_SAVE)
                p.tag(WalletTags.PAYOUT_FIELD_ERROR_PREFIX + "payee_name").assertExists()
                false
            }

            "blocked", "changeLimit", "verifyLimit", "payeeMismatch", "accountTaken", "busy" -> {
                if (scene == "blocked") host.api.write = WithdrawVisualFixtures.failure(44001)
                saveChanged()
                assertEquals(1, host.api.writes().size)
                scene == "blocked"
            }

            "still" -> {
                tap(WalletTags.PENDING_CONFIRM)
                false
            }

            "abandon" -> {
                tap(WalletTags.PENDING_ABANDON)
                true
            }

            "abandoned", "completed" -> {
                abandon(scene)
                true
            }

            "loading" -> {
                p.tag(WalletTags.PAYOUT_PAGE).assertExists()
                false
            }

            "load_error" -> {
                p.tag(WalletTags.PAYOUT_LOAD_ERROR).assertExists()
                false
            }

            else -> {
                if (bound) p.tag(WalletTags.PAYOUT_BOUND).assertExists()
                false
            }
        }

    private fun realname(
        scene: String,
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        host.release = scene == "release"
        if (scene == "submitting") host.backends.submitHold = CompletableDeferred()
        val failure = scene in setOf("verifyFailed", "idTaken", "age", "dailyLimit", "server", "fields")
        if (failure) host.backends.submitAnswer = PayoutIdentityVisualFixtures.realnameError(scene)
        host.launch(PayoutIdentityPage.REALNAME, dark = dark, fontScale = fontScale, shell = true)
        host.fillRealName()
        val overlay = scene == "consent"
        if (scene != "form" && scene != "release") {
            tap(WalletTags.REALNAME_SUBMIT)
            p.tag(WalletTags.CONSENT_SHEET).assertExists()
        }
        if (failure || scene == "submitting") {
            tap(WalletTags.CONSENT_CHECK)
            tap(WalletTags.CONSENT_OK)
            assertEquals(1, host.backends.submits.size)
        }
        if (failure && scene != "fields") p.tag(WalletTags.REALNAME_ERROR).assertExists()
        if (scene == "release") p.tag(WalletTags.REALNAME_UNAVAILABLE).assertExists()
        capture("identity-v", name, overlay)
    }

    private fun labor(
        scene: String,
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        host.release = scene == "release"
        when (scene) {
            "loading" -> host.backends.readHold = CompletableDeferred()
            "load_error" -> host.backends.currentAnswer = WithdrawVisualFixtures.failure()
            "signing" -> host.backends.signHold = CompletableDeferred()
            "sign_failed" -> host.backends.signAnswer = PayoutIdentityVisualFixtures.laborSignFailure()
        }
        host.launch(PayoutIdentityPage.LABOR, dark = dark, fontScale = fontScale, shell = true)
        if (scene in setOf("checked", "signing", "sign_failed")) tap(WalletTags.LABOR_CHECK)
        if (scene == "signing" || scene == "sign_failed") {
            tap(WalletTags.LABOR_SIGN)
            assertEquals(1, host.backends.signs.size)
        }
        if (scene == "sign_failed") p.tag(WalletTags.LABOR_ERROR).assertExists()
        if (scene == "content") p.tag(WalletTags.LABOR_TEXT).assertExists()
        if (scene == "release") p.tag(WalletTags.LABOR_UNAVAILABLE).assertExists()
        capture("identity-v", name, false)
    }

    private fun saveChanged() {
        tap(WalletTags.PAYOUT_CHANGE)
        host.type(WalletTags.PAYOUT_LOGON, PayoutIdentityVisualFixtures.form("alipay", "alipayAccount"))
        host.type(WalletTags.PAYOUT_PAYEE, PayoutIdentityVisualFixtures.form("alipay", "payeeName"))
        tap(WalletTags.PAYOUT_SAVE)
    }

    private fun abandon(scene: String) {
        tap(WalletTags.PENDING_ABANDON)
        val key = checkNotNull(host.api.pending).key
        host.api.pendingHold = CompletableDeferred()
        host.api.pendingAnswer =
            if (scene == "abandoned") {
                ApiResult.Abandoned(key, PayoutIdentityVisualFixtures.TRACE)
            } else {
                PayoutIdentityVisualFixtures.ok()
            }
        rule.onNodeWithText(WalletDraftTexts.text("pending_confirm.abandon.ok")).performTouchInput { click(center) }
        // 网络层已结算该原键：仅测试夹具显式清场（共性第 68 条）
        rule.runOnIdle {
            host.api.pending = null
            host.api.pendingHold!!.complete(Unit)
        }
        rule.waitForIdle()
        assertEquals("真实点击确定放弃恰一次", 1, host.api.abandons.size)
        assertEquals(0, host.api.writes().size)
    }

    private fun tap(tag: String) {
        p.tag(tag).scrollToIfScrollable().performTouchInput { click(center) }
        rule.waitForIdle()
    }

    private fun capture(
        dir: String,
        name: String,
        overlay: Boolean,
    ) {
        val path = "src/test/snapshots/$dir/$name.png"
        if (overlay) captureScreenRoboImage(path) else rule.onRoot().captureRoboImage(path)
    }

    private companion object {
        val FORM_SCENES = setOf("unbound", "bank_form", "validation")
        val SAVE_ERROR_SCENES = setOf("changeLimit", "verifyLimit", "payeeMismatch", "accountTaken", "busy")
        val PENDING_SCENES = setOf("abandon", "still", "abandoned", "completed")
    }
}
