package com.couli.core.wallet

import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.ReplayMode
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 向量之外、本端实现补的规则（错误消息 trace6、输入过滤、草稿表、未定义组合的保守做法）。 */
class WalletLogicTest {
    private fun failure(
        code: Int,
        trace: String? = "trace-abc123",
    ) = ApiResult.Failure(code, "", JsonObject(emptyMap()), trace, ErrorEffect(code, ErrorEffectKind.TOAST, ReplayMode.NONE, false), null)

    @Test
    fun trace6IsAppendedEvenWhenTextHasNoPlaceholder() {
        // 50301 的文案没有 {trace6}（「{platform_name}维护中」），统一错误消息函数独立追加
        val error = WalletErrors.of(failure(50301))
        assertEquals(WalletError.SERVER, error.kind)
        val text = WalletErrors.message(WalletDraftTexts, error)
        assertTrue(text, text.endsWith("（abc123）"))
        // 未收录的 5xxxx 回落 error.50001，trace6 只出现一次
        val unknown = WalletErrors.message(WalletDraftTexts, WalletErrors.of(failure(59999)))
        assertEquals(1, Regex("abc123").findAll(unknown).count())
        assertTrue(unknown.startsWith("出了点问题"))
    }

    @Test
    fun transportFailureIsNotDisguisedAsZero() {
        val error = WalletErrors.of(ApiResult.TransportFailure("timeout", null, null, false))
        assertEquals(WalletError.TRANSPORT, error.kind)
        assertEquals("网络不可用，请检查网络后重试", WalletDraftTexts.text(error.textKey))
    }

    @Test
    fun amountFilterRejectsWholePasteWithOtherCharacters() {
        assertEquals("12.", WithdrawAmountInput.accept("12", "12."))
        assertEquals("12", WithdrawAmountInput.accept("12", "¥12.5"))
        assertEquals("12", WithdrawAmountInput.accept("12", "12.345"))
        assertEquals("12.5", WithdrawAmountInput.accept("12", " 12.5 "))
        assertEquals("", WithdrawAmountInput.accept("12", ""))
        assertEquals("12", WithdrawAmountInput.accept("12", "1234567890"))
        assertNull("半截输入不是合法金额", WithdrawAmountInput.parse("12."))
    }

    @Test
    fun submitBusyEndsNeverUnknownKeyAndDoesNotAutoResend() {
        val out =
            SensitiveOutcome.of(
                SensitiveOutcome.PAYOUT_ACCOUNT,
                SensitiveOutcome.SUBMIT,
                false,
                OutcomeInput(OutcomeInput.ERROR, 40901),
            )
        assertEquals(SensitiveOutcome.BUSY_RETRY, out.actionRaw)
        // 共性第 12 条：从未结果未知的键收到明确 40901 时结束，下次点击用新键
        assertTrue(out.keyEnds)
        assertTrue(out.nextSubmitNewKey)
        assertEquals("error.40901", out.textKey)
    }

    @Test
    fun abandon20903EndsTheKey() {
        val out = SensitiveOutcome.of(SensitiveOutcome.WITHDRAW, SensitiveOutcome.ABANDON, true, OutcomeInput(OutcomeInput.ERROR, 20903))
        assertEquals(SensitiveOutcome.ABANDONED, out.actionRaw)
        assertTrue(out.keyEnds)
    }

    @Test
    fun riskBannerUsesReasonTextOnlyWhenDictionaryHasIt() {
        val summary = WalletSummary(1, 1, 0, 0, 1, 1, 0, 0, null, false, 0, "abnormal_trade", true)
        val view = WalletSummaryView.of(summary, false, 0L)
        assertEquals(WalletSummaryView.RISK_PAUSED_KEY, view.riskBanner.textKey)
        assertNull("不显示原因码原文", view.riskBanner.reasonText)
        // G11 编排者更正（R1-11）：风控冻结不置灰，点了由提现页按 rules 的 30303 account_frozen 提示
        assertTrue(view.withdrawEnabled)
    }

    @Test
    fun unknownPhoneShapeHasNoLocalMaskAndFallbackIsUsed() {
        val view = PayoutPendingSummary.summarize(PayoutForm(alipayLogonId = "+86 138 0013 8000", payeeName = "测试三"))
        assertNull("不猜成 138****8000，也不输出完整登录号", view.account)
        assertEquals("**三", view.payee)
        assertEquals("上一次提交还没有收到结果，请先确认", WalletDraftTexts.text("pending_confirm.payout_account.desc"))
    }

    @Test
    fun formRejectsOverLongFieldsAndCardCharacters() {
        val long = "a".repeat(PayoutAccountForm.LOGON_MAX + 1)
        assertEquals(
            listOf(PayoutAccountForm.ALIPAY_LOGON_ID),
            PayoutAccountForm.build(PayoutForm(alipayLogonId = long, payeeName = "测试三")).fieldErrors,
        )
        assertEquals("6222 00-1", PayoutAccountForm.acceptCardNo("6222 00", "6222 00-1"))
        assertEquals("6222 00", PayoutAccountForm.acceptCardNo("6222 00", "6222 00x"))
        assertEquals("form 不打印完整内容", "PayoutForm(method=alipay)", PayoutForm(alipayLogonId = "fixture@example.com").toString())
    }

    @Test
    fun withdrawalLinesJoinReasonAndReturnWithSemicolon() {
        val item =
            Withdrawal(
                "w1",
                "FAILED",
                "manual",
                800,
                0,
                0,
                800,
                "alipay",
                "支付宝",
                "138****5678",
                "PAYEE_ACCOUNT_STATUS_ERROR",
                "change_payout_account",
                "2026-10-08T11:30:00+08:00",
                0L,
            )
        val lines = WithdrawalText.render(WithdrawalText.of(item, null), WalletDraftTexts)
        assertEquals(listOf("收款支付宝账号状态异常；¥8 已退回可提现余额，可修改收款账号后重新提现"), lines)
    }

    @Test
    fun idNumberFilterKeepsDigitsAndUppercaseX() {
        assertEquals("1234X", RealNameForm.acceptIdNo("", "1234x"))
        assertEquals("123", RealNameForm.acceptIdNo("123", "123a"))
        assertEquals("RealNameInput(submitEnabled=false)", RealNameForm.normalize("测试三", "1").toString())
    }

    @Test
    fun helpLinkOnlyAcceptsHelpRouteWithArticle() {
        val routes = com.couli.generated.Routes.all

        fun links(json: String) =
            kotlinx.serialization.json.Json
                .parseToJsonElement(json) as JsonObject
        assertEquals(
            "Help",
            HelpLink
                .target(
                    links("""{"withdraw_rules":{"route":"Help","params":{"article_id":"withdraw-rules"}}}"""),
                    HelpLink.WITHDRAW_RULES,
                    routes,
                )?.route,
        )
        assertNull(HelpLink.target(links("""{"withdraw_rules":{"route":"Wallet","params":{}}}"""), HelpLink.WITHDRAW_RULES, routes))
        assertNull(HelpLink.target(null, HelpLink.WITHDRAW_RULES, routes))
    }

    /** R1-05（APP-09 共性第 18 条）：放弃得到 10004 / 10005 与确认一样引导补办，打开对应页、保留未决键。 */
    @Test
    fun r1_05_abandonPrerequisiteCodesGuideAndKeepPendingKey() {
        fun abandon(
            code: Int,
            consent: String? = null,
        ) = SensitiveOutcome.of(
            SensitiveOutcome.WITHDRAW,
            SensitiveOutcome.ABANDON,
            true,
            OutcomeInput(
                OutcomeInput.ERROR,
                code,
                data =
                    JsonObject(
                        consent?.let { mapOf("consent_type" to kotlinx.serialization.json.JsonPrimitive(it)) }.orEmpty(),
                    ),
            ),
        )
        val labor = abandon(10004, "labor_agreement")
        assertEquals(SensitiveOutcome.GUIDE, labor.actionRaw)
        assertEquals(WalletRoutes.LABOR_AGREEMENT, labor.route)
        assertEquals("error.10004", labor.textKey)
        assertFalse(labor.keyEnds)
        assertFalse(labor.nextSubmitNewKey)
        val id = abandon(10004, "id_verification")
        assertEquals(WalletRoutes.REAL_NAME, id.route)
        assertFalse(id.keyEnds)
        val phone = abandon(10005)
        assertEquals(SensitiveOutcome.GUIDE, phone.actionRaw)
        assertEquals(WalletRoutes.BIND_PHONE, phone.route)
        assertFalse(phone.keyEnds)
        // privacy / agreement：没有已有组件（A02），只提示、不开页，键仍保留
        val privacy = abandon(10004, "privacy")
        assertEquals(SensitiveOutcome.GUIDE, privacy.actionRaw)
        assertNull(privacy.route)
        assertFalse(privacy.keyEnds)
    }
}
