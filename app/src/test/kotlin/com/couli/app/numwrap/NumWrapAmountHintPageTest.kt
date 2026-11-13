package com.couli.app.numwrap

import android.app.Application
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.me.MeVisualFixtures
import com.couli.app.me.MeVisualTestHost
import com.couli.app.wallet.WalletVisualFixtures
import com.couli.app.wallet.WalletVisualTestHost
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
import com.couli.feature.me.MeTags
import com.couli.feature.wallet.WalletTags
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * D10-1265（三端同口径，本单做）真实页面接线：共用金额件的横滑口径（[NumHint]）——极端值独占一行仍放不下才框内横滑，横滑态从开头露出、末端渐隐、
 * 有横向滚动指示器；常规金额不横滑、没有提示；读屏读完整金额。之后照常做字段断言（不断、不缩字、首尾可达）。w320 × 200%。
 * 覆盖：钱包余额卡、我的余额卡、提现页头、提现记录详情「申请金额」、放弃上一笔弹窗金额（注销页金额两列在 [NumWrapAccountPageTest]）。
 * 「七处都经同一个共用金额件」由语义契约 AmountOverflow 钉住：每处（含常规值）都必须在金额上声明它，缺了就红。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w320dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumWrapAmountHintPageTest {
    @get:Rule val rule = createComposeRule()
    private val closers = mutableListOf<AutoCloseable>()

    @After fun close() = closers.forEach { it.close() }

    @Test fun walletExtremeLight() = wallet(EXTREME, dark = false, expect = true)

    @Test fun walletExtremeDark() = wallet(EXTREME, dark = true, expect = true)

    @Test fun walletRegular() = wallet(REGULAR, dark = false, expect = false)

    @Test fun meExtreme() = me(EXTREME, expect = true)

    @Test fun meRegular() = me(REGULAR, expect = false)

    @Test fun withdrawHeaderExtreme() {
        val host = WithdrawVisualTestHost(rule).also { closers += it }
        WithdrawVisualScenes(rule, host).show("stress", fontScale = FONT_SCALE)
        withdrawHeader(EXTREME, expect = true)
    }

    @Test fun withdrawHeaderRegular() {
        val host = WithdrawVisualTestHost(rule).also { closers += it }
        host.launch(fontScale = FONT_SCALE, shell = true)
        val rules = WithdrawVisualFixtures.rules()
        withdrawHeader(rules.getValue("withdrawable_fen").jsonPrimitive.long, expect = false)
    }

    @Test fun recordAmountExtreme() = record(HUGE, expect = true)

    @Test fun recordAmountRegular() = record(REGULAR, expect = false)

    /** 中等长度：原窄列（标签右侧）放不下、整行放得下——必须先换布局（上下排），不准横滑。 */
    @Test fun recordAmountMedium() = record(RECORD_MEDIUM, expect = false)

    @Test fun abandonDialogAmountExtreme() = abandon(HUGE, expect = true)

    @Test fun abandonDialogAmountRegular() = abandon(REGULAR, expect = false)

    /** 中等长度：明细窄列放不下、弹窗整行放得下——必须先换布局，不准横滑。 */
    @Test fun abandonDialogAmountMedium() = abandon(DIALOG_MEDIUM, expect = false)

    private fun wallet(
        fen: Long,
        dark: Boolean,
        expect: Boolean,
    ) {
        val host = WalletVisualTestHost(rule).also { closers += it }
        val summary = WalletVisualFixtures.summary("long_amounts")
        host.api.answer =
            WalletVisualFixtures.ok(
                JsonObject(summary + ("withdrawable_fen" to JsonPrimitive(fen)) + ("available_fen" to JsonPrimitive(fen))),
            )
        host.launch("long_amounts", dark = dark, fontScale = FONT_SCALE, statusBar = true)
        val card = NumWrap.within(WalletTags.WALLET_HERO)
        judge(Place(fen, "钱包余额卡（${if (dark) "深" else "浅"}）", card, card), DISPLAY, expect)
    }

    private fun me(
        fen: Long,
        expect: Boolean,
    ) {
        val host = MeVisualTestHost(rule).also { closers += it }
        val profile = MeVisualFixtures.profile("me_long")
        val balance = profile.getValue("balance").jsonObject
        val changed = JsonObject(balance + ("withdrawable_fen" to JsonPrimitive(fen)))
        host.api.answer = MeVisualFixtures.ok(JsonObject(profile + ("balance" to changed)))
        host.launch("me_long", fontScale = FONT_SCALE, statusBar = true)
        val card = NumWrap.within(MeTags.ME_BALANCE)
        judge(Place(fen, "我的余额卡", card, card), DISPLAY, expect)
    }

    private fun withdrawHeader(
        fen: Long,
        expect: Boolean,
    ) {
        rule.onNodeWithTag(WalletTags.WITHDRAW_BALANCE, useUnmergedTree = true).performScrollTo()
        val header = NumWrap.within(WalletTags.WITHDRAW_BALANCE)
        judge(Place(fen, "提现页头", header, header), DISPLAY, expect)
    }

    private fun record(
        fen: Long,
        expect: Boolean,
    ) {
        val host = WithdrawRecordsVisualTestHost(rule).also { closers += it }
        val detail = WithdrawRecordsVisualFixtures.detail("stress")
        host.api.detailAnswers[STRESS] = WithdrawRecordsVisualFixtures.ok(JsonObject(detail + ("amount_fen" to JsonPrimitive(fen))))
        host.launch("stress", fontScale = FONT_SCALE, shell = true)
        host.toggle(STRESS)
        rule.onNodeWithTag(WalletTags.RECORD_DETAIL_PREFIX + STRESS, useUnmergedTree = true).performScrollTo()
        // 字段定位与所在行检查都用整个明细块：「申请金额」标签与值同一行时须被行检查看到
        val rows = NumWrap.within(WalletTags.RECORD_DETAIL_PREFIX + STRESS)
        judge(Place(fen, "提现记录详情申请金额", rows, rows), FOOTNOTE, expect)
    }

    private fun abandon(
        fen: Long,
        expect: Boolean,
    ) {
        val host = WithdrawVisualTestHost(rule).also { closers += it }
        val body = """{"amount_fen":$fen}"""
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
        host.launch(fontScale = FONT_SCALE, shell = true)
        rule.onNodeWithTag(WalletTags.PENDING_ABANDON).performScrollTo().performClick()
        rule.waitForIdle()
        val dialog = NumWrap.within(WalletTags.ABANDON_DIALOG)
        val amountLabel = WalletDraftTexts.text("pending_confirm.withdraw.amount_label")
        val payoutLabel = WalletDraftTexts.text("pending_confirm.payout_label")
        // 字段定位用标签区间；所在行检查用整个弹窗（含「上一笔金额」标签本身）
        judge(Place(fen, "放弃上一笔弹窗金额", NumWrap.segment(rule, dialog, amountLabel, payoutLabel), dialog), FOOTNOTE, expect)
    }

    /** 一处金额：[field] 字段定位范围，[row] 所在行检查范围。 */
    private data class Place(
        val fen: Long,
        val where: String,
        val field: SemanticsMatcher,
        val row: SemanticsMatcher,
    )

    private fun judge(
        place: Place,
        token: NumToken,
        expect: Boolean,
    ) {
        val field = NumField(CouliFormat.money(place.fen), "${place.where}（w320×200%）", place.field, token, exact = true)
        NumHint.assertHint(rule, NumHintSpec(field, row = place.row, spoken = CouliFormat.moneySpoken(place.fen), expect = expect))
        NumWrap.assertField(rule, field)
    }

    private companion object {
        const val FONT_SCALE = 2f
        const val STRESS = "STRESS"

        /** ¥999999999.99：余额卡、提现页头 34/700 在 w320×200% 下独占一行也放不下。 */
        const val EXTREME = 99_999_999_999L

        /** ¥9999999999999999.99：记录明细、放弃弹窗（footnote）独占一行也放不下。 */
        const val HUGE = 999_999_999_999_999_999L

        /** ¥12.34：常规金额。 */
        const val REGULAR = 1_234L

        /** ¥99999999.99：记录明细 footnote 下标签右侧窄列放不下、整行放得下。 */
        const val RECORD_MEDIUM = 9_999_999_999L

        /** ¥9999999.99：放弃弹窗明细窄列放不下、弹窗整行放得下。 */
        const val DIALOG_MEDIUM = 999_999_999L
        val DISPLAY = NumToken(CouliTextStyles.amountDisplay.fontSize, FONT_SCALE)
        val FOOTNOTE = NumToken(CouliTypography.footnote.fontSize, FONT_SCALE)
    }
}
