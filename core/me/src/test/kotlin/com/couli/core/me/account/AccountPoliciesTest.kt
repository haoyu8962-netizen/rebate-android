package com.couli.core.me.account

import com.couli.core.designsystem.CouliFormat
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingKeyStore
import com.couli.generated.DefaultTexts
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-13a 纯逻辑里向量表达不了的部分（rule-gaps G34–G39、G35 资金区渲染、共性第 8、10、15、18 条）。号码只用测试值 13900001234。
 */
class AccountPoliciesTest {
    private val now = ms("2026-10-14T10:00:00+08:00")
    private val cooling =
        obj(
            """{"status":"cooling","apply_at":"2026-10-14T10:00:00+08:00","cooling_until":"2026-10-21T10:00:00+08:00",""" +
                """"can_cancel":true,"cancelled_at":null,"cancel_reason":null,"processed_at":null}""",
        )

    // ---------------------------------------------------------------- G35 资金区

    @Test
    fun g35_fundsRenderedOnlyThroughCouliFormat() {
        val v = DeletionPresentation.view(DeletionRead.None, WalletFunds(1234, 5678, 0), true, "none", "full", "settings", now)
        assertEquals(DeletionView.INITIAL, v.stateRaw)
        assertEquals(CouliFormat.money(1234), v.withdrawableText)
        assertEquals(CouliFormat.money(5678), v.estimatedText)
        assertNull(v.negativeText)
        assertTrue(v.waiverEnabled)
        assertTrue(v.showGoWithdraw)
    }

    @Test
    fun g35_negativeShowsDeductionAndContactService() {
        val v = DeletionPresentation.view(DeletionRead.None, WalletFunds(0, 9876, 200), true, "none", "full", "settings", now)
        assertEquals(DeletionView.NEGATIVE, v.stateRaw)
        assertEquals(200L, v.negativeAmountFen)
        assertEquals(CouliFormat.money(200), v.negativeText)
        assertTrue(v.showContactService)
        assertFalse(v.waiverEnabled)
        assertEquals("账户有待扣回金额 ¥2，抵扣回正后才能注销", AccountDraftTexts.format("error.30416", mapOf("amount" to CouliFormat.money(200))))
    }

    @Test
    fun g35_30416WithoutAmountUsesFallbackSentence() {
        val o = DeletionOutcomePolicy.of(AccountRaw.APPLY, AccountResult(422, 30416, obj("{}")), AccountRaw.SETTINGS, false)
        assertNull(o.negativeAmountFen)
        assertEquals(DefaultTexts.fallback("error.30416"), AccountDraftTexts.format("error.30416", emptyMap()))
        // 共性第 10 条：本次 30416 的金额来自本次响应，不来自钱包摘要
        val with =
            DeletionOutcomePolicy.of(
                AccountRaw.APPLY,
                AccountResult(422, 30416, obj("""{"amount_fen":567}""")),
                AccountRaw.SETTINGS,
                false,
            )
        assertEquals(567L, with.negativeAmountFen)
    }

    @Test
    fun g24_deletionSummaryIsTwoFixedLines() {
        assertEquals(2, DeletionSummary.LINES.size)
        assertEquals("提交的操作", AccountDraftTexts.text(DeletionSummary.LINES[0].first))
        assertEquals("申请注销账号", AccountDraftTexts.text(DeletionSummary.LINES[0].second))
        assertEquals("已勾选", AccountDraftTexts.text(DeletionSummary.LINES[1].first))
        assertEquals("放弃未提现余额和预估收益", AccountDraftTexts.text(DeletionSummary.LINES[1].second))
        assertNull(PhoneMask.summary(DeletionSummary.RECORD))
    }

    // ---------------------------------------------------------------- G34 / G37 入口与手机号未知

    @Test
    fun g34_phoneKnownDecidesPhoneChangeEntry() {
        val known = AccountEntryPolicy.entries("member", false, true, true, null, phoneKnown = true)
        val unknown = AccountEntryPolicy.entries("member", false, true, true, null, phoneKnown = false)
        assertEquals(AccountEntries.PHONE_CHANGE, known.phoneRowRaw)
        assertEquals(AccountEntries.DISABLED, unknown.phoneRowRaw)
        // 未绑手机不看 phoneKnown
        assertEquals(AccountEntries.BIND_PHONE, AccountEntryPolicy.entries("member", false, false, true, null, true).phoneRowRaw)
        // 强更与受限会话一律隐藏
        assertEquals(AccountEntries.HIDDEN, AccountEntryPolicy.entries("member", true, true, true, null, true).phoneRowRaw)
        assertEquals(AccountEntries.HIDDEN, AccountEntryPolicy.entries("deletion_only", false, true, true, null, true).phoneRowRaw)
    }

    @Test
    fun g48_basicForcedHasNoLabelAndRestrictedSettingsHasNoDeletionRow() {
        assertNull(AccountEntryPolicy.entries("basic", true, true, true, null, false).forceUpdateLabelKey)
        assertFalse(AccountEntryPolicy.entries("deletion_only", false, true, true, null, false).deletionRowShown)
    }

    @Test
    fun g37_unknownPhoneIsNotNoPhoneAndNotSuccess() {
        val v = DeletionPresentation.view(DeletionRead.None, WalletFunds(0, 0, 0), null, "none", "full", "settings", now)
        assertEquals(DeletionView.INITIAL, v.stateRaw)
        assertFalse("未知不按未绑手机拦", "deletion.step_up_unsupported" in v.textKeys)
        assertFalse(v.showBindPhone)
        val noPhone =
            DeletionPresentation.view(
                DeletionRead.None,
                WalletFunds(0, 0, 0),
                false,
                "none",
                "deletion_only",
                "force_update",
                now,
            )
        assertFalse("受限会话没有去绑定手机号", noPhone.showBindPhone)
        // G51：负余额排在未绑手机前
        val both = DeletionPresentation.view(DeletionRead.None, WalletFunds(0, 0, 100), false, "none", "full", "settings", now)
        assertEquals(DeletionView.NEGATIVE, both.stateRaw)
    }

    @Test
    fun cancelledNegativeBalanceIsNotUserCancel() {
        val negative =
            AccountJson.read(
                obj(
                    """{"status":"cancelled","apply_at":"2026-10-01T10:00:00+08:00",""" +
                        """"cooling_until":"2026-10-08T10:00:00+08:00","can_cancel":false,""" +
                        """"cancelled_at":"2026-10-08T10:00:00+08:00","cancel_reason":"negative_balance","processed_at":null}""",
                ),
            )
        assertFalse(DeletionCancelReconcile.cancelledByUser(negative))
        assertFalse(DeletionCancelReconcile.cancelledByUser(AccountJson.read(cooling)))
    }

    // ---------------------------------------------------------------- G38 Retry-After 与 trace

    @Test
    fun g38_retryAfterCustomAndTrace() {
        val custom = DeletionCancelPolicy.of(429, 42901, JsonNull, "settings", "17", null)
        assertEquals(17_000L, custom.disableMs)
        val bad = DeletionCancelPolicy.of(429, 42901, JsonNull, "settings", "-3", null)
        assertEquals(5_000L, bad.disableMs)
        val trace = DeletionCancelPolicy.of(504, 50401, JsonNull, "settings", null, "test-trace-778899")
        assertEquals("778899", trace.trace6)
        val step1 = PhoneChangeStep1Policy.of(PhoneChangeStep1Policy.SEND_CODE, AccountResult(429, 42901, JsonNull, 30_000L), true)
        assertEquals(30_000L, step1.disableMs)
        assertFalse("发码失败不清码", step1.clearsCode)
    }

    // ---------------------------------------------------------------- G39 completed + 3xxxx

    @Test
    fun g39_completedBusinessErrorCarriesBothTexts() {
        val data = obj("""{"outcome":"completed","original":{"code":30411,"msg":"","data":{}}}""")
        val o = PhoneChangeOutcomePolicy.of(AccountRaw.ABANDON, AccountResult(200, 0, data))
        assertTrue(o.alreadyDone)
        assertEquals("error.30411", o.errorKey)
        val d =
            DeletionOutcomePolicy.of(
                AccountRaw.ABANDON,
                AccountResult(200, 0, obj("""{"outcome":"completed","original":{"code":30412,"msg":""}}""")),
                AccountRaw.SETTINGS,
                true,
            )
        assertTrue(d.alreadyDone)
        assertEquals("error.30412", d.textKey)
    }

    @Test
    fun g26_unlistedBusinessCodeFallsBackToServerMsg() {
        val o = DeletionOutcomePolicy.of(AccountRaw.APPLY, AccountResult(422, 39999, JsonNull, msg = "服务端原话"), AccountRaw.SETTINGS, false)
        assertEquals(AccountRaw.ENDS, o.keyEndsRaw)
        assertNull(o.textKey)
        assertEquals("服务端原话", o.serverMsg)
    }

    @Test
    fun prerequisitesGuideWithoutReplay() {
        val o = DeletionOutcomePolicy.of(AccountRaw.APPLY, AccountResult(403, 10005, JsonNull), AccountRaw.SETTINGS, false)
        assertEquals("BindPhone", o.guideRoute)
        assertFalse(o.autoResendSameKey)
        val p = PhoneChangeOutcomePolicy.of(AccountRaw.SUBMIT, AccountResult(401, 10001, JsonNull))
        assertEquals("Login", p.guideRoute)
    }

    // ---------------------------------------------------------------- BindPhone 恢复（G07）

    @Test
    fun g07_onlyUnknownOwnBindPhoneRecordShowsCard() {
        fun rec(
            state: String,
            account: String = "test-account-a",
            op: String = "bindPhone",
        ) = PendingKeyRecord("k-$state", op, "{}", account, "139****1234", state)
        val unknown = rec(PendingKeyStore.UNKNOWN)
        assertTrue(BindPhonePendingPolicy.restoreRecords(listOf(unknown), "test-account-a", "bind_phone").showPendingConfirm)
        assertTrue(BindPhonePendingPolicy.restoreRecords(listOf(unknown), "test-account-a", "phone_change").skipsStep1)
        assertFalse(BindPhonePendingPolicy.restoreRecords(listOf(rec("held")), "test-account-a", "bind_phone").showPendingConfirm)
        assertFalse(
            BindPhonePendingPolicy
                .restoreRecords(
                    listOf(rec(PendingKeyStore.IN_FLIGHT)),
                    "test-account-a",
                    "bind_phone",
                ).showPendingConfirm,
        )
        assertFalse(BindPhonePendingPolicy.restoreRecords(listOf(unknown), "test-account-b", "bind_phone").showPendingConfirm)
    }

    // ---------------------------------------------------------------- AccountShared（共性第 8、15、18 条）

    @Test
    fun sharedGatesAndSettlement() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var clock = 1_000L
        var current = true
        val shared = AccountShared(scope, { clock }) { _, _ -> current }
        val calls = mutableListOf<String>()
        shared.ports =
            object : BindPhoneSettlePorts {
                override fun clearAllStepUpTokens() {
                    calls += "tokens"
                }

                override fun refreshMe(
                    account: String,
                    epoch: Long,
                ) {
                    calls += "me:$account:$epoch"
                }
            }
        val seen = mutableListOf<PendingSettlement>()
        val collector = scope.launch { shared.pendingSignals.collect { seen += it } }
        // 同一份 phoneGate：BindPhone 与换号看到同一个截止时间
        shared.phoneGate.start(8_000)
        assertTrue(shared.phoneGate.active)
        clock += 8_000
        assertFalse(shared.phoneGate.active)
        // bind 发码按「账号 + 新号 + purpose」
        val a = KeyedDeadlines.bindKey("test-account-a", "139 0000-1234", "bind")
        val b = KeyedDeadlines.bindKey("test-account-a", "13900005678", "bind")
        shared.bindSms.start(a, 60_000)
        assertTrue(shared.bindSms.remaining(a) > 0)
        assertEquals(0L, shared.bindSms.remaining(b))
        // 成功结算：身份仍是当前的才清 token、刷新 getMe
        assertTrue(shared.onBindPhoneSucceeded("test-account-a", 3L, "k1", "139****1234"))
        assertEquals(listOf("tokens", "me:test-account-a:3"), calls)
        current = false
        calls.clear()
        assertFalse(shared.onBindPhoneSucceeded("test-account-a", 3L, "k2", null))
        assertTrue("旧会话的迟到成功不改共享状态", calls.isEmpty())
        assertEquals(listOf("k1", "k2"), seen.map { it.key })
        shared.reset()
        assertEquals(0L, shared.bindSms.remaining(a))
        collector.cancel()
        scope.cancel()
    }

    // ---------------------------------------------------------------- 文案

    @Test
    @Suppress("LongMethod") // 键清单逐条列出
    fun g29_draftKeysPresentAndFallbackChain() {
        val required =
            listOf(
                "deletion.title",
                "deletion.intro",
                "deletion.funds.title",
                "deletion.withdrawable",
                "deletion.estimated",
                "deletion.negative",
                "deletion.negative.note",
                "deletion.withdraw_hint",
                "deletion.go_withdraw",
                "deletion.notice.title",
                "deletion.waiver",
                "deletion.submit",
                "deletion.submit_hint",
                "deletion.pending_hint",
                "deletion.cooling.title",
                "deletion.cooling.deadline",
                "deletion.cooling.restore",
                "deletion.cooling.timeline.apply",
                "deletion.cooling.timeline.cooling",
                "deletion.cooling.timeline.process",
                "deletion.cooling.readonly",
                "deletion.cooling.cancel",
                "deletion.cooling.cancel_expired",
                "deletion.cooling.cancel_unknown",
                "deletion.cancelled_toast",
                "deletion.step_up_unsupported",
                "pending_confirm.summary.operation",
                "pending_confirm.summary.waiver",
                "pending_confirm.summary.submitted_at",
                "pending_confirm.summary.new_phone",
                "phone_change.title",
                "phone_change.step1",
                "phone_change.step2",
                "phone_change.current_hint",
                "phone_change.code_sent",
                "phone_change.next",
                "phone_change.cant_use_old",
                "phone_change.new_phone",
                "phone_change.new_hint",
                "phone_change.submit_hint",
                "phone_change.submit",
                "phone_change.done.title",
                "phone_change.done.body",
                "phone_change.done.ok",
                "phone_change.pending_hint",
                "phone_change.risk_blocked.desc",
                "step_up.title.phone_change",
                "step_up.title.account_deletion",
                "pending_confirm.abandon.note.phone_change",
                "pending_confirm.abandon.note.deletion",
            ) + DeletionPresentation.NOTICE_KEYS
        required.forEach { assertTrue(it, AccountDraftTexts.text(it).isNotEmpty()) }
        // 已有键不重建：契约、钱包草稿表、我的草稿表照样取得到
        listOf("pending_confirm.title", "app_update.delete_account", "error.10007", "step_up.no_phone", "cs.appeal_entry").forEach {
            assertTrue(it, AccountDraftTexts.has(it))
            assertFalse(it, it in AccountDraftTexts.DRAFTS)
        }
        assertEquals("", AccountDraftTexts.text("no.such.key"))
        // 换号待确认取不到号码：契约 fallbacks 不带号码的句子
        assertEquals(
            DefaultTexts.fallback("pending_confirm.phone_change.desc"),
            AccountDraftTexts.format("pending_confirm.phone_change.desc", emptyMap()),
        )
        assertTrue(
            AccountDraftTexts
                .format("pending_confirm.phone_change.desc", mapOf("phone" to "139****1234"))
                .contains("139****1234"),
        )
    }

    private fun ms(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
}
