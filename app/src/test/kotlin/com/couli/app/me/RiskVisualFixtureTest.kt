package com.couli.app.me

import com.couli.core.designsystem.CouliFormat
import com.couli.core.me.AppealForm
import com.couli.core.me.AppealView
import com.couli.core.me.MeDraftTexts
import com.couli.core.me.MeJson
import com.couli.core.me.PendingWithdrawals
import com.couli.core.me.RiskLayout
import com.couli.core.me.RiskNoticeView
import com.couli.core.me.RiskReasonText
import com.couli.core.wallet.WalletJson
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G-R15：fixture 是新文件 specs/risk-appeal-visual-vectors.json（三端同一份），me-vectors.json 不追加（由 RiskVisualGuardTest 整文件冻结）。
 * 报文按产品解析器校验（不合契约的报文不能拿来截图）；版式与显示值由产品纯规则复算（fixture 不实现规则）；不带 CT-26e 字段；键名避开 gitleaks 敏感词。
 */
class RiskVisualFixtureTest {
    @Test fun boardsMatchTheBriefRegister() {
        val boards = RiskVisualFixtures.boards()
        assertEquals("账号状态 7 + 申诉 10", 17, boards.size)
        assertTrue("17 张全对照（RiskNoticeStates 只对 ①）", boards.values.all { it.getValue("compare").jsonPrimitive.content == "true" })
        assertEquals("8c6270eba567", RiskVisualFixtures.text("sha12", RiskVisualFixtures.board("RiskNoticeBanned-v2")))
        assertEquals("e083d8b577ba", RiskVisualFixtures.text("sha12", RiskVisualFixtures.board("AppealResultUpheld-v2")))
        assertEquals(setOf("RiskNoticeDark", "AppealDark", "AppealProgressDark"), boards.keys.filter(RiskVisualFixtures::dark).toSet())
        assertEquals(1180, RiskVisualFixtures.height("AppealResultUpheld-v2"))
        assertEquals(1240, RiskVisualFixtures.height("AppealResultUpheldFrozen-v2"))
        assertEquals("作废旧稿 9 张登记为不对照", 9, RiskVisualFixtures.obj("superseded").size)
    }

    @Test fun riskBoardsResolveToTheirLayoutsThroughTheProductRule() {
        val expected =
            mapOf(
                "RiskNoticeBanned-v2" to RiskLayout.BANNED,
                "RiskNoticeBannedAppealing-v2" to RiskLayout.BANNED_APPEALING,
                "RiskNoticeBannedUpheld-v2" to RiskLayout.BANNED_UPHELD,
                "RiskNoticeFrozen" to RiskLayout.FROZEN,
                "RiskNoticeFrozenAppealing" to RiskLayout.FROZEN_APPEALING,
                "RiskNoticeStates" to RiskLayout.FROZEN,
                "RiskNoticeDark" to RiskLayout.FROZEN,
            )
        expected.forEach { (name, layout) ->
            val b = RiskVisualFixtures.board(name)
            val risk = RiskVisualFixtures.text("risk", b)
            val model =
                RiskNoticeView.model(
                    RiskVisualFixtures.risk(risk),
                    RiskVisualFixtures.text("entered10006", b).toBoolean(),
                    AppealView.latestAccount(RiskVisualFixtures.appealItems(RiskVisualFixtures.text("appeals", b))),
                    RiskVisualFixtures.now,
                )
            assertEquals("画板状态 → 版式（G10 进入标记选写法）：$name", layout, model.layoutRaw)
        }
    }

    @Test fun appealBoardsResolveToTheirLayouts() {
        val expected =
            mapOf(
                "appeals_empty" to AppealView.SUBMIT,
                "appeals_order_only" to AppealView.SUBMIT,
                "appeals_processing_banned" to AppealView.PROGRESS,
                "appeals_processing_frozen" to AppealView.PROGRESS,
                "appeals_upheld_banned" to AppealView.RESULT_UPHELD,
                "appeals_upheld_frozen" to AppealView.RESULT_UPHELD,
                "appeals_revoked_banned" to AppealView.RESULT_LIFTED,
                "appeals_revoked_frozen" to AppealView.RESULT_LIFTED,
            )
        expected.forEach { (name, layout) ->
            val parsed = assertParsed(name)
            assertEquals("列表 → 版式（G15 只看账户申诉）：$name", layout, AppealView.layout(parsed))
        }
        val first = RiskVisualFixtures.appealItems("appeals_upheld_banned").first()
        assertEquals("干扰项：订单申诉排在最前（验证只看账户申诉）", "order", RiskVisualFixtures.text("target_type", first))
    }

    @Test fun mePayloadsParseAndRisksAreContractShaped() {
        RiskVisualFixtures.obj("risks").keys.forEach { name ->
            assertNotNull("getMe 报文合契约：$name", MeJson.me(RiskVisualFixtures.me(name)))
            assertNotNull("risk 合契约：$name", MeJson.risk(RiskVisualFixtures.risk(name)))
        }
        assertFalse(
            "反例：表外类别",
            RiskVisualFixtures.reasonCategory("risk_unknown_reason") in RiskReasonText.CATEGORIES,
        )
        assertTrue(RiskVisualFixtures.reasonCategory("risk_banned") in RiskReasonText.CATEGORIES)
        assertNull("冻结不定期", RiskVisualFixtures.risk("risk_frozen_no_until")["frozen_until"]?.jsonPrimitive?.takeIf { it.isString })
    }

    @Test fun withdrawalsKeepOnlyNonTerminalRows() {
        val list = WalletJson.withdrawalList(RiskVisualFixtures.withdrawals("withdrawals_pending"))
        assertNotNull("listWithdrawals 报文合契约", list)
        val kept = list!!.items.filter { it.status in PendingWithdrawals.STATUSES }
        assertEquals("一笔审核中（PAID_API 反例不显示）", 1, kept.size)
        assertEquals(RiskVisualFixtures.expectFen("pendingWithdrawalAmount"), kept.single().amountFen)
        assertEquals("没有坏条目", 0, list.dropped)
    }

    @Test fun expectedTextsComeFromTheProductFormatter() {
        val until = MeJson.instant(RiskVisualFixtures.text("frozen_until", RiskVisualFixtures.risk("risk_frozen")))!!
        assertEquals(RiskVisualFixtures.expect("frozenUntilText"), CouliFormat.timeListFuture(until, RiskVisualFixtures.now))
        listOf(
            "appeals_upheld_banned" to ("bannedCreatedText" to "bannedClosedText"),
            "appeals_upheld_frozen" to ("frozenCreatedText" to "frozenClosedText"),
        ).forEach { (name, keys) ->
            val latest = RiskVisualFixtures.latestAccount(name)!!
            assertEquals(
                RiskVisualFixtures.expect(keys.first),
                CouliFormat.timeMinute(MeJson.instant(RiskVisualFixtures.text("created_at", latest))!!),
            )
            assertEquals(
                RiskVisualFixtures.expect(keys.second),
                CouliFormat.timeMinute(MeJson.instant(RiskVisualFixtures.text("closed_at", latest))!!),
            )
        }
    }

    @Test fun submitInputsMatchTheFormRule() {
        val body = AppealForm.body(RiskVisualFixtures.inputPadded)!!
        assertEquals("去首尾空白后提交", RiskVisualFixtures.input, body.getValue("content").jsonPrimitive.content)
        assertNull("只有空白不可提交", AppealForm.body(RiskVisualFixtures.blank))
        assertNotNull("成功回包合契约", MeJson.appeal(RiskVisualFixtures.submitted(RiskVisualFixtures.input)))
        assertEquals(setOf(20001, 50001, 42901), setOf("fieldCode", "serverCode", "rateCode").map(RiskVisualFixtures::code).toSet())
    }

    @Test fun keptDraftsMatchTheCurrentTable() {
        listOf("risk.banned.desc", "appeal.next.logout", "appeal.lifted.note.banned", "appeal.withdraw_ok").forEach {
            assertEquals("fixture 记的现值 = 草稿表：$it", MeDraftTexts.text(it), RiskVisualFixtures.kept(it))
        }
        assertTrue("禁用字样含「截止」「工作日」", RiskVisualFixtures.forbidden.containsAll(listOf("截止", "工作日", "小时内", "天内")))
    }

    @Test fun fixtureCarriesNoCt26eFieldsAndAvoidsGitleaksWords() {
        val names = mutableListOf<String>()

        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (k, v) -> names += k.lowercase().also { walk(v) } }
                is JsonArray -> element.forEach(::walk)
                is JsonPrimitive -> Unit
            }
        }
        walk(RiskVisualFixtures.data)
        listOf("prev_risk_state", "attempt_no", "can_resubmit").forEach { assertFalse("不带 CT-26e 字段：$it", it in names) }
        listOf("token", "secret", "key", "sentry", "password", "auth").forEach { word ->
            assertTrue("键名不得含 $word：${names.filter { it.contains(word) }}", names.none { it.contains(word) })
        }
        assertFalse(
            "不往 me-vectors.json 追加（G-R15）",
            File(RiskVisualFixtures.root, "specs/me-vectors.json").readText().contains("visualRisk"),
        )
    }

    private fun assertParsed(name: String): List<JsonObject> {
        val list = MeJson.appealList(RiskVisualFixtures.appeals(name))
        assertNotNull("listAppeals 报文合契约：$name", list)
        assertEquals("没有坏条目：$name", 0, list!!.dropped)
        return list.items
    }
}
