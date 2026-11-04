package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText as textMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.PriceTerms
import com.couli.feature.wallet.WalletTags
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-0730 K1 / K2（F-AND-81 / F-AND-82，native 主线 2026-10-30 三端口径）：提现记录展开卡。
 *
 * K1：明细字段表（W26）——有到账行时手续费 0 不写、>0 照写；代扣个税 0 不写、>0 照写（回归）；其余行的顺序、显示值、读屏值逐项不变。
 * 依据 iOS origin/main `Packages/Features/Sources/Wallet/RecordDetailCard.swift:119-134`（fields：feeFen != 0 才写）与
 * `AppTests/RecordsVisualLayoutTests.swift:221-226`（testW26FieldTable「手续费 0 不写」「代扣个税 0 不写」）。
 * Android 现行「没有到账行（未通过 / 打款未成功 / 未知状态）就不写手续费」的条件保持（任务书 K1「其余不变」）。
 *
 * K2：客服行（W29）——认识的通道（支付宝 / 银行卡）未通过（REJECTED）也出【联系客服】行，说明键 `withdraw_detail.doubt`
 * （iOS `RecordDetailCard.swift:205-215` serviceNote；画板 WithdrawRecordsStates-v2 ⑬「操作：联系客服」，说明句与 Paying / Failed-v2 画板同为
 * 「对结果有疑问可以联系客服」）；不认识的通道（微信零钱）未通过不出；其余状态的现行为逐项回归。
 *
 * 只经真实页面与控制器（[WithdrawRecordsVisualTestHost]）；列表单与详情单用同一份 JSON，金额都取服务端原值。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Fu0730RecordsDisplayTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawRecordsVisualTestHost(rule) }

    @After fun close() = host.close()

    // ------------------------------------------------------------------ K1 明细字段表

    /** K1-1（先红）：已到账、手续费 0、个税 0 → 申请金额 / 实际到账 / 收款账号 / 申请时间，没有「手续费」。 */
    @Test fun k1PaidFeeZeroOmitsFeeRow() {
        val json = custom("paid", "K1_PAID_FEE0", fee = 0, tax = 0)
        open(json)
        assertRows(json, listOf(AMOUNT, NET_ACTUAL, PAYOUT, CREATED))
    }

    /** K1-2（回归）：已到账、手续费 >0、个税 0 → 手续费照写，位置在申请金额之后。 */
    @Test fun k1PaidFeePositiveKeepsFeeRow() {
        val json = custom("paid", "K1_PAID_FEE", fee = 123, tax = 0, net = 377)
        open(json)
        assertRows(json, listOf(AMOUNT, FEE, NET_ACTUAL, PAYOUT, CREATED))
    }

    /** K1-3（先红）：已到账、手续费 0、个税 >0（tax 样例）→ 个税照写，手续费不写。 */
    @Test fun k1PaidTaxOnlyOmitsFeeKeepsTax() {
        val json = WithdrawRecordsVisualFixtures.detail("tax")
        open(json)
        assertRows(json, listOf(AMOUNT, TAX, NET_ACTUAL, PAYOUT, CREATED))
    }

    /** K1-4（回归）：手续费、个税都 >0（independent 样例）→ 六行全写，顺序：申请金额、手续费、代扣个税、实际到账、收款账号、申请时间。 */
    @Test fun k1FeeAndTaxPositiveKeepAllRowsInOrder() {
        val json = WithdrawRecordsVisualFixtures.detail("independent")
        open(json)
        assertRows(json, listOf(AMOUNT, FEE, TAX, NET_ACTUAL, PAYOUT, CREATED))
    }

    /** K1-5（先红）：审核中（预计到账）、手续费 0 → 不写手续费，到账行标签取「预计到账」。 */
    @Test fun k1PendingFeeZeroOmitsFeeRow() {
        val json = WithdrawRecordsVisualFixtures.detail("pending")
        open(json)
        assertRows(json, listOf(AMOUNT, NET_EXPECTED, PAYOUT, CREATED))
    }

    /** K1-6（先红）：打款中（预计到账）、手续费 0、个税 >0 → 个税照写，手续费不写。 */
    @Test fun k1PayingFeeZeroTaxPositive() {
        val json = custom("paying", "K1_PAYING_TAX", fee = 0, tax = 80, net = 920)
        open(json)
        assertRows(json, listOf(AMOUNT, TAX, NET_EXPECTED, PAYOUT, CREATED))
    }

    /** K1-7（先红）：人工打款（银行卡，PAID_MANUAL）手续费 0 → 不写；收款账号「招商银行 尾号 1234」原样。 */
    @Test fun k1ManualFeeZeroOmitsFeeRow() {
        val json = WithdrawRecordsVisualFixtures.detail("manual")
        open(json)
        assertRows(json, listOf(AMOUNT, NET_ACTUAL, PAYOUT, CREATED))
    }

    /** K1-8（回归）：没有到账行的打款未成功，手续费 >0 也不写（Android 现行条件，任务书 K1「其余不变」）；个税 0 不写。 */
    @Test fun k1FailedWithoutNetRowNeverWritesFee() {
        val json = custom("failed_other", "K1_FAILED_FEE", fee = 123, tax = 0)
        open(json)
        assertRows(json, listOf(AMOUNT, PAYOUT, CREATED))
    }

    /** K1-9（回归）：未通过（没有到账行），手续费 0、个税 >0 → 申请金额、代扣个税、收款账号、申请时间。 */
    @Test fun k1RejectedTaxPositiveWithoutNetRow() {
        val json = custom("rejected", "K1_REJECTED_TAX", fee = 0, tax = 50)
        open(json)
        assertRows(json, listOf(AMOUNT, TAX, PAYOUT, CREATED))
    }

    // ------------------------------------------------------------------ K2 客服行

    /** K2-1（先红）：认识的通道（支付宝）未通过——各原因样例展开卡都有【联系客服】行，说明取 withdraw_detail.doubt，位于明细之后。 */
    @Test fun k2KnownChannelRejectedShowsServiceRow() {
        val names =
            listOf(
                "rejected",
                "reject_negative_balance",
                "reject_risk_suspect",
                "reject_order_abnormal",
                "reject_payee_info_invalid",
                "reject_user_request",
                "reject_other",
            )
        val items = names.map(WithdrawRecordsVisualFixtures::detail)
        openMany(items)
        items.zip(names).forEach { (json, name) ->
            expand(json.id())
            assertService(json.id(), "withdraw_detail.doubt", name)
        }
    }

    /** K2-2（先红）：银行卡（认识的通道）未通过同样出客服行。 */
    @Test fun k2BankCardRejectedShowsServiceRow() {
        val base = WithdrawRecordsVisualFixtures.detail("rejected")
        val json =
            JsonObject(
                base +
                    mapOf(
                        "withdrawal_id" to JsonPrimitive("K2_BANK_REJECTED"),
                        "payout_channel" to JsonPrimitive("bank_card"),
                        "payout_channel_name" to JsonPrimitive("招商银行"),
                        "masked_account" to JsonPrimitive("尾号 1234"),
                    ),
            )
        open(json)
        assertService("K2_BANK_REJECTED", "withdraw_detail.doubt", "bank_card REJECTED")
    }

    /** K2-3（先红）：未通过卡【联系客服】点一次 = 一次客服出口（来源 WithdrawRecords），零打开其他页、零额外请求。 */
    @Test fun k2RejectedServiceClickIsOneContactZeroRequests() {
        open(WithdrawRecordsVisualFixtures.detail("rejected"))
        rule.onNodeWithTagIn("REJECTED", WalletTags.RECORD_CS).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("WithdrawRecords"), host.env.contacts)
        assertTrue("不打开其他页", host.env.opened.isEmpty())
        host.assertCalls("listWithdrawals", "getWithdrawal")
    }

    /** K2-4（回归）：不认识的通道（微信零钱）未通过不出客服行。 */
    @Test fun k2UnknownChannelRejectedHasNoServiceRow() {
        val base = WithdrawRecordsVisualFixtures.detail("rejected")
        val json =
            JsonObject(
                base +
                    mapOf(
                        "withdrawal_id" to JsonPrimitive("K2_WECHAT_REJECTED"),
                        "payout_channel" to JsonPrimitive("wechat"),
                        "payout_channel_name" to JsonPrimitive("微信零钱"),
                        "masked_account" to JsonPrimitive("**三"),
                    ),
            )
        open(json)
        assertNoService("K2_WECHAT_REJECTED", "wechat REJECTED")
    }

    /** K2-5（回归）：其余状态的现行为逐项不变——审核中 / 已审核 → withdraw.irrevocable；打款中 / 打款未成功 → withdraw_detail.doubt；已到账、未知状态、微信零钱 → 无。 */
    @Test fun k2OtherStatusesKeepCurrentServiceRow() {
        val expected =
            linkedMapOf(
                "pending" to "withdraw.irrevocable",
                "auto_review" to "withdraw.irrevocable",
                "auto_deciding" to "withdraw.irrevocable",
                "approved" to "withdraw.irrevocable",
                "paying" to "withdraw_detail.doubt",
                "auto_waiting" to "withdraw_detail.doubt",
                "failed" to "withdraw_detail.doubt",
                "failed_other" to "withdraw_detail.doubt",
                "fail_payee_not_exist" to "withdraw_detail.doubt",
                "paid" to null,
                "tax" to null,
                "manual" to null,
                "unknown" to null,
                "wechat" to null,
            )
        val items = expected.keys.map(WithdrawRecordsVisualFixtures::detail)
        openMany(items)
        items.zip(expected.entries).forEach { (json, entry) ->
            val (name, key) = entry
            expand(json.id())
            if (key == null) assertNoService(json.id(), name) else assertService(json.id(), key, name)
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 一张记录：列表与详情同一份 JSON，按定位单打开（自动展开、恰一次详情读取）。 */
    private fun open(json: JsonObject) {
        val id = json.id()
        host.api.answer = WithdrawRecordsVisualFixtures.ok(WithdrawRecordsVisualFixtures.list(json))
        host.api.detailAnswers[id] = WithdrawRecordsVisualFixtures.ok(json)
        host.launch(focus = id)
        assertExpanded(id)
    }

    /** 多张记录同一页（列表 / 详情同一份 JSON），不定位；逐张由 [expand] 真点展开。 */
    private fun openMany(items: List<JsonObject>) {
        host.api.answer =
            WithdrawRecordsVisualFixtures.ok(
                JsonObject(mapOf("items" to JsonArray(items), "next_cursor" to JsonNull)),
            )
        items.forEach { host.api.detailAnswers[it.id()] = WithdrawRecordsVisualFixtures.ok(it) }
        host.launch()
    }

    private fun expand(id: String) {
        host.toggle(id)
        assertExpanded(id)
        rule.onNodeWithTagIn(id, WalletTags.RECORD_DETAIL_PREFIX + id).performScrollTo()
        rule.waitForIdle()
    }

    private fun assertExpanded(id: String) {
        rule.waitForIdle()
        assertTrue("前置：$id 已展开", id in host.controller.state.value.expanded)
        assertTrue("前置：$id 已有详情", host.controller.state.value.details[id] != null)
    }

    private fun custom(
        name: String,
        id: String,
        fee: Long,
        tax: Long,
        net: Long? = null,
    ): JsonObject {
        val base = WithdrawRecordsVisualFixtures.detail(name)
        val extra =
            buildMap {
                put("withdrawal_id", JsonPrimitive(id))
                put("fee_fen", JsonPrimitive(fee))
                put("tax_fen", JsonPrimitive(tax))
                if (net != null) put("net_fen", JsonPrimitive(net))
            }
        return JsonObject(base + extra)
    }

    private fun assertRows(
        json: JsonObject,
        rows: List<String>,
    ) {
        val id = json.id()
        val detailTag = WalletTags.RECORD_DETAIL_PREFIX + id
        val nodes =
            rule
                .onAllNodes(hasParent(hasTestTag(detailTag)))
                .fetchSemanticsNodes()
                .sortedBy { it.positionInRoot.y }
        val actual = nodes.map { it.row() }
        val expected = rows.map { expectedRow(json, it) }
        assertEquals("$id 明细行（标签 / 显示值 / 读屏值，按位置自上而下）", expected, actual)
        if (FEE !in rows) {
            val fee = host.texts.text("withdraw_detail.fee")
            assertTrue(
                "$id 卡内不得出现「$fee」字样",
                rule
                    .onAllNodes(textMatcher(fee) and hasAnyAncestor(hasTestTag(WalletTags.RECORD_PREFIX + id)), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isEmpty(),
            )
        }
    }

    private fun expectedRow(
        json: JsonObject,
        row: String,
    ): Triple<String, String, String> {
        fun money(
            key: String,
            field: String,
        ): Triple<String, String, String> {
            val fen = json.getValue(field).jsonPrimitive.long
            return Triple(host.texts.text(key), CouliFormat.money(fen), CouliFormat.moneySpoken(fen))
        }
        return when (row) {
            AMOUNT -> {
                money("withdraw_detail.amount", "amount_fen")
            }

            FEE -> {
                money("withdraw_detail.fee", "fee_fen")
            }

            TAX -> {
                money("withdraw_detail.tax", "tax_fen")
            }

            NET_ACTUAL -> {
                money("withdraw_detail.net_label.actual", "net_fen")
            }

            NET_EXPECTED -> {
                money("withdraw_detail.net_label.expected", "net_fen")
            }

            PAYOUT -> {
                val account = json.str("payout_channel_name") + " " + json.str("masked_account")
                val star = host.texts.text("wallet.star_spoken")
                Triple(host.texts.text("withdraw_detail.payout"), account, if (star.isEmpty()) account else account.replace("*", star))
            }

            else -> {
                val time = CouliFormat.timeMinute(OffsetDateTime.parse(json.str("created_at")).toInstant().toEpochMilli())
                Triple(host.texts.text("withdraw_detail.created"), time, time)
            }
        }
    }

    /** 一行明细（合并节点）：(标签, 显示值, 读屏值)。读屏描述为「标签，读屏值」。 */
    private fun SemanticsNode.row(): Triple<String, String, String> {
        val texts =
            config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .map { it.text }
        val description =
            config
                .getOrNull(SemanticsProperties.ContentDescription)
                .orEmpty()
                .joinToString("")
        val label = texts.firstOrNull().orEmpty()
        val value = texts.drop(1).joinToString("")
        val spoken = description.removePrefix(label + PriceTerms.SPOKEN_PAUSE)
        return Triple(label, value, if (description.startsWith(label + PriceTerms.SPOKEN_PAUSE)) spoken else "<读屏描述不以「标签，」开头：$description>")
    }

    private fun assertService(
        id: String,
        key: String,
        what: String,
    ) {
        val cs = rule.onAllNodes(hasTestTag(WalletTags.RECORD_CS) and hasAnyAncestor(hasTestTag(WalletTags.RECORD_PREFIX + id)), true)
        assertEquals("$what：展开卡恰一个【联系客服】", 1, cs.fetchSemanticsNodes().size)
        val hint = host.texts.text(key)
        val hints =
            rule
                .onAllNodes(textMatcher(hint) and hasAnyAncestor(hasTestTag(WalletTags.RECORD_PREFIX + id)), useUnmergedTree = true)
                .fetchSemanticsNodes()
        assertEquals("$what：客服行说明 = $key（$hint）", 1, hints.size)
        val other = if (key == "withdraw.irrevocable") "withdraw_detail.doubt" else "withdraw.irrevocable"
        assertTrue(
            "$what：不得同时出现 $other",
            rule
                .onAllNodes(
                    textMatcher(host.texts.text(other)) and hasAnyAncestor(hasTestTag(WalletTags.RECORD_PREFIX + id)),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .isEmpty(),
        )
        val detail =
            rule
                .onAllNodes(hasTestTag(WalletTags.RECORD_DETAIL_PREFIX + id), true)
                .fetchSemanticsNodes()
                .single()
        assertTrue(
            "$what：客服行在明细之后",
            hints.single().positionInRoot.y >= detail.positionInRoot.y + detail.size.height - 1f,
        )
        assertEquals(
            "$what：链接文字 = withdraw_detail.cs",
            1,
            rule
                .onAllNodes(
                    textMatcher(host.texts.text("withdraw_detail.cs")) and hasAnyAncestor(hasTestTag(WalletTags.RECORD_CS)) and
                        hasAnyAncestor(hasTestTag(WalletTags.RECORD_PREFIX + id)),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .size,
        )
    }

    private fun assertNoService(
        id: String,
        what: String,
    ) {
        assertTrue(
            "$what：前置——展开卡已画出",
            rule.onAllNodes(hasTestTag(WalletTags.RECORD_DETAIL_PREFIX + id), true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "$what：没有【联系客服】",
            rule
                .onAllNodes(hasTestTag(WalletTags.RECORD_CS) and hasAnyAncestor(hasTestTag(WalletTags.RECORD_PREFIX + id)), true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        listOf("withdraw.irrevocable", "withdraw_detail.doubt").forEach { key ->
            assertTrue(
                "$what：没有客服说明 $key",
                rule
                    .onAllNodes(
                        textMatcher(host.texts.text(key)) and hasAnyAncestor(hasTestTag(WalletTags.RECORD_PREFIX + id)),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
                    .isEmpty(),
            )
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithTagIn(
        id: String,
        tag: String,
    ) = onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(WalletTags.RECORD_PREFIX + id)), useUnmergedTree = true)

    private fun JsonObject.id(): String = str("withdrawal_id")

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

    private companion object {
        const val AMOUNT = "amount"
        const val FEE = "fee"
        const val TAX = "tax"
        const val NET_ACTUAL = "net_actual"
        const val NET_EXPECTED = "net_expected"
        const val PAYOUT = "payout"
        const val CREATED = "created"
    }
}
