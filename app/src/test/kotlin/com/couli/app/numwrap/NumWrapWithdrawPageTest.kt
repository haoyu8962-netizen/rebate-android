package com.couli.app.numwrap

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.wallet.visual.WithdrawRecordsVisualFixtures
import com.couli.app.wallet.visual.WithdrawRecordsVisualTestHost
import com.couli.app.wallet.visual.WithdrawVisualFixtures
import com.couli.app.wallet.visual.WithdrawVisualScenes
import com.couli.app.wallet.visual.WithdrawVisualTestHost
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.network.PendingKeyRecord
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 缺陷 #3（提现页头余额）、#4（提现记录详情 DetailRows + 交易流水号）与同类自查（brief §2.4，Codex 复审 S1 ⑤）真实页面接线：
 * - 提现页头：stress 场景（¥999999999.99），字段容器 `withdraw_balance` 之下恰好 1 处，amountDisplay 令牌。
 * - 提现页收款账号行（`withdraw_payout`，WithdrawScreen.kt:612）与收款账号页当前账号（`payout_bound`，PayoutAccountScreen.kt:358）：脱敏账号不断。
 * - 放弃上一笔确认弹窗（WalletUi.kt:964 DetailBlock，`abandon_dialog`）：金额与收款账号不断。
 * - 记录详情：DetailRows 是 private，经单条记录的展开卡走真实页面；金额、手续费、个税、到账、脱敏账号在 `record_detail_STRESS` 之下各恰好 1 处，footnote 令牌。
 * - 流水号：自然单行宽放得下整行就必须一行；放不下才允许独占整行后按字符折行（左右与整行对齐、不与【复制】重叠、每个字符可见）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumWrapWithdrawPageTest {
    @get:Rule val rule = createComposeRule()
    private var withdraw: WithdrawVisualTestHost? = null
    private var records: WithdrawRecordsVisualTestHost? = null

    @After fun close() {
        withdraw?.close()
        records?.close()
    }

    // ---------------------------------------------------------------- 提现页

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun withdrawW320Font200Light() = withdrawCase(dark = false)

    @Test fun withdrawW390Font200Light() = withdrawCase(dark = false)

    @Test fun withdrawW390Font200Dark() = withdrawCase(dark = true)

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun withdrawPayoutRowW320Font200Light() {
        launchWithdraw(dark = false)
        rule.onNodeWithTag(WalletTags.WITHDRAW_PAYOUT, useUnmergedTree = true).performScrollTo()
        NumWrap.assertField(rule, NumField(MASKED, "提现页收款账号行", NumWrap.within(WalletTags.WITHDRAW_PAYOUT), FOOTNOTE))
    }

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun withdrawW320Font200WholePageScan() {
        launchWithdraw(dark = false)
        assertTrue("全页扫描至少要扫到余额", NumWrap.scanPage(rule, "提现页 w320×200%") >= 1)
    }

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun abandonDialogW320Font200Light() = abandonCase(dark = false)

    @Test fun abandonDialogW390Font200Dark() = abandonCase(dark = true)

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun payoutPageW320Font200Light() = payoutCase(dark = false)

    @Test fun payoutPageW390Font200Dark() = payoutCase(dark = true)

    // ---------------------------------------------------------------- 提现记录详情

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun recordW320Font200LightDetailRows() = detailRowsCase(dark = false)

    @Test fun recordW390Font200LightDetailRows() = detailRowsCase(dark = false)

    @Test fun recordW390Font200DarkDetailRows() = detailRowsCase(dark = true)

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun recordW320Font200Light32DigitSerial() = serialCase(SERIAL_32, dark = false)

    @Test fun recordW390Font200Dark32DigitSerial() = serialCase(SERIAL_32, dark = true)

    @Test fun recordW390Font200Light20DigitSerial() = serialCase(SERIAL_20, dark = false)

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun recordW320Font200Light20DigitSerial() = serialCase(SERIAL_20, dark = false)

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun recordW320Font200WholePageScan() {
        launchRecord(SERIAL_32, dark = false)
        assertTrue("全页扫描至少要扫到金额", NumWrap.scanPage(rule, "提现记录 w320×200%", setOf(SERIAL_32)) >= 1)
    }

    private fun withdrawCase(dark: Boolean) {
        launchWithdraw(dark)
        rule.onNodeWithTag(WalletTags.WITHDRAW_BALANCE, useUnmergedTree = true).performScrollTo()
        val where = "提现页头可提现余额（${mode(dark)}）"
        val field = NumField(CouliFormat.money(BALANCE_FEN), where, NumWrap.within(WalletTags.WITHDRAW_BALANCE), DISPLAY, exact = true)
        NumWrap.assertField(rule, field)
    }

    private fun abandonCase(dark: Boolean) {
        val host = WithdrawVisualTestHost(rule).also { withdraw = it }
        val body = """{"amount_fen":$BALANCE_FEN}"""
        val base = WithdrawVisualFixtures.pending()
        host.api.pending =
            PendingKeyRecord(
                base.key,
                base.operationId,
                body,
                base.account,
                body,
                base.stateRaw,
                everUnknown = true,
                createdAtMillis = WithdrawVisualFixtures.now,
            )
        host.launch(dark = dark, fontScale = FONT_SCALE, shell = true)
        rule.onNodeWithTag(WalletTags.PENDING_ABANDON).performScrollTo().performClick()
        rule.waitForIdle()
        val dialog = NumWrap.within(WalletTags.ABANDON_DIALOG)
        val amountLabel = WalletDraftTexts.text("pending_confirm.withdraw.amount_label")
        val payoutLabel = WalletDraftTexts.text("pending_confirm.payout_label")
        val where = "放弃上一笔确认弹窗（${mode(dark)}）"
        // 明细块按标签取字段容器：金额行（「上一笔金额」之后、「收款账号」之前）与收款账号行（「收款账号」之后）
        val amount = NumWrap.segment(rule, dialog, amountLabel, payoutLabel)
        NumWrap.assertField(rule, NumField(CouliFormat.money(BALANCE_FEN), "$where 金额", amount, FOOTNOTE, exact = true))
        val payout = NumWrap.segment(rule, dialog, payoutLabel, null)
        NumWrap.assertField(rule, NumField(MASKED, "$where 收款账号", payout, FOOTNOTE))
    }

    private fun payoutCase(dark: Boolean) {
        val host = WithdrawVisualTestHost(rule).also { withdraw = it }
        host.launch(payout = true, dark = dark, fontScale = FONT_SCALE, shell = true)
        rule.onNodeWithTag(WalletTags.PAYOUT_BOUND, useUnmergedTree = true).performScrollTo()
        val where = "收款账号页当前账号（${mode(dark)}）"
        val token = NumToken(CouliTextStyles.amountTitle.fontSize, FONT_SCALE)
        NumWrap.assertField(rule, NumField(MASKED, where, NumWrap.within(WalletTags.PAYOUT_BOUND), token))
    }

    private fun detailRowsCase(dark: Boolean) {
        launchRecord(SERIAL_32, dark)
        val scope = NumWrap.within(WalletTags.RECORD_DETAIL_PREFIX + STRESS)
        val where = "记录详情明细（${mode(dark)}）"
        listOf(AMOUNT_FEN, FEE_FEN, TAX_FEN, NET_FEN).forEach { fen ->
            NumWrap.assertField(rule, NumField(CouliFormat.money(fen), where, scope, FOOTNOTE, exact = true))
        }
        NumWrap.assertField(rule, NumField(MASKED, where, scope, FOOTNOTE))
    }

    private fun serialCase(
        serial: String,
        dark: Boolean,
    ) {
        launchRecord(serial, dark)
        val node = NumWrap.nodesFor(rule, serial, NumWrap.within(WalletTags.RECORD_PREFIX + STRESS))
        node.performScrollTo()
        val row = rule.onNodeWithTag(WalletTags.RECORD_DETAIL_PREFIX + STRESS, useUnmergedTree = true).fetchSemanticsNode()
        val copy = rule.onNodeWithTag(WalletTags.RECORD_COPY, useUnmergedTree = true).fetchSemanticsNode()
        NumWrap.checkSerial(rule, node, NumSerialSpec(serial, row, copy, "交易流水号 ${serial.length} 位（${mode(dark)}）", FOOTNOTE))
    }

    private fun launchWithdraw(dark: Boolean) {
        val host = WithdrawVisualTestHost(rule).also { withdraw = it }
        WithdrawVisualScenes(rule, host).show("stress", dark = dark, fontScale = FONT_SCALE)
    }

    private fun launchRecord(
        serial: String,
        dark: Boolean,
    ) {
        val host = WithdrawRecordsVisualTestHost(rule).also { records = it }
        val detail = WithdrawRecordsVisualFixtures.detail("stress")
        host.api.detailAnswers[STRESS] =
            WithdrawRecordsVisualFixtures.ok(JsonObject(detail + ("channel_order_id" to JsonPrimitive(serial))))
        host.launch("stress", dark = dark, fontScale = FONT_SCALE, shell = true)
        host.toggle(STRESS)
        rule.onNodeWithTag(WalletTags.RECORD_DETAIL_PREFIX + STRESS, useUnmergedTree = true).performScrollTo()
    }

    private fun mode(dark: Boolean) = if (dark) "深" else "浅"

    private companion object {
        const val FONT_SCALE = 2f
        const val STRESS = "STRESS"

        /** specs visualWithdraw.rules.stress.withdrawable_fen → ¥999999999.99（也用作放弃弹窗的金额压力值）。 */
        const val BALANCE_FEN = 99_999_999_999L

        /** specs visualWithdrawRecords.details.stress。 */
        const val AMOUNT_FEN = 999_999_999_999L
        const val FEE_FEN = 123_456_789L
        const val TAX_FEN = 987_654_321L
        const val NET_FEN = 876_543_210_987L
        const val MASKED = "138****5678"
        const val SERIAL_32 = "12345678901234567890123456789012"
        const val SERIAL_20 = "20261012100000123456"
        val FOOTNOTE = NumToken(CouliTypography.footnote.fontSize, FONT_SCALE)
        val DISPLAY = NumToken(CouliTextStyles.amountDisplay.fontSize, FONT_SCALE)
    }
}
