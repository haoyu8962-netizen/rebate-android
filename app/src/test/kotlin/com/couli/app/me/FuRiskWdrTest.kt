package com.couli.app.me

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.assertNoTextOverflow
import com.couli.core.designsystem.CouliFormat
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.me.MeTags
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-RISK-WDR（共性第 36 条；brief §4.2；D10-594 J1–J3）：封禁 / 冻结页「进行中的提现」行 = 状态标题值 + 与提现记录页同源的 WithdrawalText 副文案。
 * - 期望值一律由产品纯函数 WithdrawalText.render(WithdrawalText.of(单, SLA), WalletDraftTexts) 现算（同源），不在测试里另写副文案；
 * - SLA 句来源 deps.env.configData() texts["withdraw.sla_text"]：测试环境不注入时 = BR-TEXT-07 原文整句；注入时逐字 = 注入值，且与记录页同一单同值（J2）；
 * - 请求的发起、次数、过滤、读取闸门、重试不变（真实触摸 + 调用计数；这几条在底版上就绿，是「零改动」的证据）；
 * - 本文件不做禁用字样检查（红线 ③ 判据在 RiskVisualRedLineTest，T1）。
 * 底版 5819b11 上：副文案 / 同源 / 读屏 / 版式类应红，计数类应绿（交回 §1）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1600dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions", "LargeClass")
class FuRiskWdrTest {
    @get:Rule val rule = createComposeRule()
    private val fixtureHost = lazy { RiskVisualTestHost(rule) }
    private val wdrHost = lazy { FuRiskWdrHost(rule) }
    private val host by fixtureHost
    private val wdr by wdrHost

    @After fun tearDown() {
        if (fixtureHost.isInitialized()) host.close()
        if (wdrHost.isInitialized()) {
            val unexpected = wdr.api.unexpected.toList()
            wdr.close()
            assertTrue("没有未登记的请求：$unexpected", unexpected.isEmpty())
        }
    }

    private fun t(key: String) = AppMeTexts.text(key)

    // ---------------------------------------------------------------- 1 文案与同形（fixture、不注入配置）

    @Test fun bannedPendingRowIsTitleValueThenOriginalSlaThenFrozenLine() {
        host.launchRisk()
        val item = FuRiskWdr.fixturePending
        val sub = FuRiskWdr.sublines(item, null)
        assertEquals("先正：同源期望 = [原文整句, ¥10 已冻结]", listOf(FuRiskWdr.ORIGINAL_SLA, "${CouliFormat.money(1_000)} 已冻结"), sub)
        assertEquals("原文常量与草稿表同源", FuRiskWdr.ORIGINAL_SLA, WalletDraftTexts.text("withdraw.sla_default"))
        val rows = rule.wdrRows()
        assertEquals("只一笔在途（PAID_API 不显示）", 1, rows.size)
        assertEquals("行内依次：标签、值、SLA、已冻结", listOf(FuRiskWdr.label, FuRiskWdr.value(item)) + sub, rows.single().wdrTexts())
        assertEquals("原文整句恰 1 处，且在提现行内", 1, rule.wdrText(FuRiskWdr.ORIGINAL_SLA).size)
        assertEquals("整页原文整句恰 1 处", 1, rule.wdrCountExact(FuRiskWdr.ORIGINAL_SLA))
        assertEquals("「¥10 已冻结」恰 1 处", 1, rule.wdrCountExact(sub[1]))
        val terminal = CouliFormat.money(RiskVisualFixtures.expectFen("terminalWithdrawalAmount"))
        assertEquals("PAID_API 那笔的任何文字 0 处（含副文案「已转入…」）", 0, rule.wdrCountContaining(terminal))
        assertEquals("截止时刻永不显示", 0, rule.wdrCountContaining("前完成审核"))
    }

    @Test fun frozenPageShowsTheSameSublines() {
        host.launchRisk(risk = "risk_frozen")
        val item = FuRiskWdr.fixturePending
        assertEquals(
            "冻结页同样：标签、值、SLA、已冻结",
            listOf(FuRiskWdr.label, FuRiskWdr.value(item)) + FuRiskWdr.sublines(item, null),
            rule.wdrRows().single().wdrTexts(),
        )
    }

    @Test fun everyInFlightStateRendersItsOwnRecordsPageSublinesOnItsOwnRow() {
        wdr.launch(FuRiskWdr.mixed)
        val rows = rule.wdrRows()
        assertEquals("只显示在途三态（PENDING_REVIEW / APPROVED / PAYING），顺序照报文", FuRiskWdr.mixedShown.size, rows.size)
        FuRiskWdr.mixedShown.zip(rows).forEach { (item, row) ->
            assertEquals(
                "${item["withdrawal_id"]}：行内 = 标签、值 + 同源副文案（各行不串）",
                listOf(FuRiskWdr.label, FuRiskWdr.value(item)) + FuRiskWdr.sublines(item, null),
                row.wdrTexts(),
            )
        }
        // 先正：五态的期望确实各不相同（manual / null → SLA；auto → 几分钟；PAYING → 处理中；wechat → 通道名 + 脱敏串）
        val expected = FuRiskWdr.mixedShown.map { FuRiskWdr.sublines(it, null) }
        assertEquals("manual", FuRiskWdr.ORIGINAL_SLA, expected[0].first())
        assertEquals("auto", WalletDraftTexts.text("withdraw.sla_text_auto"), expected[1].first())
        assertEquals("PAYING", listOf(WalletDraftTexts.text("withdrawal.draft.processing")), expected[2])
        assertEquals("不认识的通道", listOf("微信 wx****0000"), expected[3])
        assertEquals("review_mode=null 按人工审核", FuRiskWdr.ORIGINAL_SLA, expected[4].first())
        assertEquals("SLA 只出现在 manual 与 null 两行", 2, rule.wdrCountExact(FuRiskWdr.ORIGINAL_SLA))
        FuRiskWdr.mixedHidden.forEach { item ->
            assertEquals("终态 / 表外状态整单不显示：${item["status"]}", 0, rule.wdrCountContaining(CouliFormat.money(FuRiskWdr.fen(item))))
        }
    }

    @Test fun anUnknownDeadlineFieldIsNeverShown() {
        val deadline = mapOf("review_deadline_at" to JsonPrimitive("2026-11-06T10:00:00+08:00"))
        val item = FuRiskWdr.item("d1", "PENDING_REVIEW", "manual", 1_000, extra = deadline)
        wdr.launch(listOf(item))
        assertEquals(
            "先正：有副文案",
            FuRiskWdr.sublines(item, null),
            rule
                .wdrRows()
                .single()
                .wdrTexts()
                .drop(2),
        )
        assertEquals("契约外的审核截止字段不显示", 0, rule.wdrCountContaining("11-06"))
        assertEquals(0, rule.wdrCountContaining("前完成审核"))
    }

    // ---------------------------------------------------------------- 2 同源（D10-594 J2）

    @Test fun injectedSlaTextIsShownVerbatimAndTheOriginalIsGone() {
        val item = FuRiskWdr.fixturePending
        wdr.launch(listOf(item), FuRiskWdr.config(FuRiskWdr.CONFIG_SLA))
        val expected = FuRiskWdr.sublines(item, FuRiskWdr.CONFIG_SLA)
        assertEquals("先正：同源期望首行 = 注入值", FuRiskWdr.CONFIG_SLA, expected.first())
        assertEquals(listOf(FuRiskWdr.label, FuRiskWdr.value(item)) + expected, rule.wdrRows().single().wdrTexts())
        assertEquals("注入值逐字恰 1 处", 1, rule.wdrText(FuRiskWdr.CONFIG_SLA).size)
        assertEquals("原文 0 处", 0, rule.wdrCountContaining(FuRiskWdr.ORIGINAL_SLA))
        assertEquals("读配置不产生任何请求", listOf(RiskVisualApi.GET_ME, RiskVisualApi.LIST_APPEALS, RiskVisualApi.LIST_WITHDRAWALS), wdr.api.ops())
    }

    @Test fun bundledDefaultConfigSlaIsShownAsTheWithdrawalSla() {
        val item = FuRiskWdr.fixturePending
        wdr.launch(listOf(item), FuRiskWdr.bundledConfig)
        val sla = FuRiskWdr.bundledSla
        assertEquals(listOf(FuRiskWdr.label, FuRiskWdr.value(item)) + FuRiskWdr.sublines(item, sla), rule.wdrRows().single().wdrTexts())
        assertEquals("包内默认那句逐字恰 1 处（J2：按提现时效处理）", 1, rule.wdrText(sla).size)
    }

    @Test fun riskAndRecordsPagesShowTheSameSlaForTheSameWithdrawal() {
        val item = FuRiskWdr.fixturePending
        val id = (item.getValue("withdrawal_id") as JsonPrimitive).content
        wdr.launch(listOf(item), FuRiskWdr.config(FuRiskWdr.CONFIG_SLA), withRecords = true)
        wdr.settle("提现记录页已显示这一单") { wdr.recordTextCount(id, FuRiskWdr.CONFIG_SLA) > 0 }
        assertEquals("记录页（收起态首行）= 注入值", 1, wdr.recordTextCount(id, FuRiskWdr.CONFIG_SLA))
        assertEquals("封禁页同一单 = 同一注入值（同源同值）", 1, rule.wdrText(FuRiskWdr.CONFIG_SLA).size)
        assertEquals("两页都没有原文", 0, rule.wdrCountContaining(FuRiskWdr.ORIGINAL_SLA))
        assertEquals(
            "封禁页整组副文案 = 记录页同一纯函数结果",
            FuRiskWdr.sublines(item, FuRiskWdr.CONFIG_SLA),
            rule
                .wdrRows()
                .single()
                .wdrTexts()
                .drop(2),
        )
    }

    @Test fun nonStringConfigValueFallsBackToTheOriginal() {
        val item = FuRiskWdr.fixturePending
        wdr.launch(listOf(item), FuRiskWdr.config(JsonPrimitive(NUMBER_SLA)))
        assertEquals("非字符串不认（取法同 WithdrawRecordsScreen：只认 isString）", 1, rule.wdrText(FuRiskWdr.ORIGINAL_SLA).size)
        assertEquals(0, rule.wdrCountExact(NUMBER_SLA.toString()))
    }

    @Test fun blankConfigValueFallsBackToTheOriginal() {
        val item = FuRiskWdr.fixturePending
        wdr.launch(listOf(item), FuRiskWdr.config("   "))
        assertEquals(
            "空白按没下发（WithdrawalText 兜底原文）",
            FuRiskWdr.sublines(item, "   "),
            rule
                .wdrRows()
                .single()
                .wdrTexts()
                .drop(2),
        )
        assertEquals(1, rule.wdrText(FuRiskWdr.ORIGINAL_SLA).size)
    }

    @Test fun slaIsReadWhenTheWithdrawalsResponseArrives() {
        val item = FuRiskWdr.fixturePending
        wdr.api.once(RiskVisualApi.LIST_WITHDRAWALS, RiskVisualFixtures.failure())
        wdr.launch(listOf(item), FuRiskWdr.config(FuRiskWdr.CONFIG_SLA))
        wdr.settle("提现分区错误") { wdr.has(MeTags.RISK_WITHDRAWALS_RETRY) }
        wdr.env.config = FuRiskWdr.config(FuRiskWdr.CONFIG_SLA_LATER)
        wdr.tap(MeTags.RISK_WITHDRAWALS_RETRY)
        wdr.settle("提现重取") { wdr.has(MeTags.RISK_WITHDRAWALS) }
        assertEquals("响应到达时读配置（与 iOS 同）：取改后的值", 1, rule.wdrText(FuRiskWdr.CONFIG_SLA_LATER).size)
        assertEquals("不是页面建立时的旧值", 0, rule.wdrCountExact(FuRiskWdr.CONFIG_SLA))
    }

    // ---------------------------------------------------------------- 3 请求零改动（底版即绿）

    @Test fun enteringWithConfigReadsMeAppealsWithdrawalsOnceEach() {
        wdr.launch(FuRiskWdr.mixed, FuRiskWdr.config(FuRiskWdr.CONFIG_SLA))
        assertEquals(listOf(RiskVisualApi.GET_ME, RiskVisualApi.LIST_APPEALS, RiskVisualApi.LIST_WITHDRAWALS), wdr.api.ops())
        assertTrue("都按本账号读", wdr.api.calls.all { it.expectedAccount == RiskVisualEnv.ACCOUNT })
        assertEquals("过滤不变：只留 PENDING_REVIEW / APPROVED / PAYING", FuRiskWdr.mixedShown.size, rule.wdrRows().size)
        assertEquals(
            "各行的值照报文顺序",
            FuRiskWdr.mixedShown.map(FuRiskWdr::value),
            rule.wdrRows().map { row -> row.wdrTexts()[1] },
        )
    }

    @Test fun withdrawalsRetryRereadsOnlyWithdrawalsOnce() {
        wdr.api.once(RiskVisualApi.LIST_WITHDRAWALS, RiskVisualFixtures.failure())
        wdr.launch(listOf(FuRiskWdr.fixturePending), FuRiskWdr.config(FuRiskWdr.CONFIG_SLA))
        wdr.settle("提现分区错误") { wdr.has(MeTags.RISK_WITHDRAWALS_RETRY) }
        val before = wdr.api.ops()
        wdr.tap(MeTags.RISK_WITHDRAWALS_RETRY)
        wdr.settle("提现重取") { wdr.has(MeTags.RISK_WITHDRAWALS) }
        assertEquals("【重试】只重取提现一次（G16）", before + RiskVisualApi.LIST_WITHDRAWALS, wdr.api.ops())
        assertEquals(1, rule.wdrRows().size)
    }

    @Test fun readGateBlocksWithdrawalsUntilItExpires() {
        wdr.api.once(RiskVisualApi.LIST_WITHDRAWALS, RiskVisualFixtures.failure(RiskVisualFixtures.code("rateCode")))
        wdr.launch(listOf(FuRiskWdr.fixturePending), FuRiskWdr.config(FuRiskWdr.CONFIG_SLA))
        wdr.settle("提现分区限流") { wdr.has(MeTags.RISK_WITHDRAWALS_RETRY) }
        assertTrue("42901 启动读取禁用期", wdr.deps.gates.read.active)
        wdr.tap(MeTags.RISK_WITHDRAWALS_RETRY)
        assertEquals("禁用期内点【重试】0 次", 1, wdr.api.count(RiskVisualApi.LIST_WITHDRAWALS))
        assertFalse(wdr.has(MeTags.RISK_WITHDRAWALS))
        wdr.advance(RiskVisualFixtures.retryAfterSeconds * MILLIS + MILLIS)
        assertFalse("禁用期已过", wdr.deps.gates.read.active)
        wdr.tap(MeTags.RISK_WITHDRAWALS_RETRY)
        wdr.settle("提现重取") { wdr.has(MeTags.RISK_WITHDRAWALS) }
        assertEquals("到期后点一次 = 一次", 2, wdr.api.count(RiskVisualApi.LIST_WITHDRAWALS))
        assertEquals("其余读取不变", listOf(1, 1), listOf(RiskVisualApi.GET_ME, RiskVisualApi.LIST_APPEALS).map(wdr.api::count))
    }

    // ---------------------------------------------------------------- 4 读屏与命中区

    @Test fun rowIsOneMergedNodeReadLabelValueThenSublines() {
        host.launchRisk()
        val item = FuRiskWdr.fixturePending
        val merged = rule.wdrRows(merged = true)
        assertEquals("提现行一个合并节点", 1, merged.size)
        assertEquals(
            "读作「进行中的提现，¥10 · 审核中，<SLA>，¥10 已冻结」",
            listOf(FuRiskWdr.label, FuRiskWdr.value(item)) + FuRiskWdr.sublines(item, null),
            merged.single().spokenParts(),
        )
        val all = rule.onRoot().fetchSemanticsNode().pvFlatten()
        val order = listOf(t("risk.limit"), FuRiskWdr.label, FuRiskWdr.ORIGINAL_SLA, t("risk.banned.note"), t("risk.submit"))
        val positions = order.map { phrase -> all.indexOfFirst { phrase in it.spokenParts() } }
        assertTrue("读屏里都找得到：$order → $positions", positions.none { it < 0 })
        assertEquals("读屏顺序：限制期限 → 提现行（含副文案）→ 提示句 → 主按钮", positions.sorted(), positions)
    }

    @Test fun sublinesAreNotHeadingsNotClickableAndAddNoClickables() {
        host.launchRisk()
        val sub = FuRiskWdr.sublines(FuRiskWdr.fixturePending, null)
        sub.forEach { line ->
            val node = rule.wdrText(line).single().fetchSemanticsNode()
            assertFalse("副文案不是标题：$line", node.config.contains(SemanticsProperties.Heading))
            generateSequence(node) { it.parent }.forEach { assertFalse("副文案及其祖先不可点：$line", it.hasClick()) }
        }
        val clickable =
            rule
                .onAllNodes(hasClickAction(), useUnmergedTree = false)
                .fetchSemanticsNodes()
                .map { it.spokenParts().joinToString(" ") }
                .toSet()
        assertEquals(
            "整页可点节点 = 改前集合（同 redLine6 判法）",
            setOf(t("risk.submit"), t("risk.contact_cs"), AppMeTexts.text("settings.delete_account"), t("risk.logout")),
            clickable,
        )
    }

    @Test fun everyWithdrawalRowKeepsA48HitAreaAndRowsDoNotOverlap() {
        wdr.launch(FuRiskWdr.fourStates)
        val rows = rule.wdrRows()
        val d =
            rows
                .first()
                .layoutInfo.density.density
        rows.forEach { assertAtLeastDp("提现行高 ≥ 48", 48f, it.boundsInRoot.height, d) }
        rows.zipWithNext().forEach { (a, b) -> assertTrue("行不重叠", a.boundsInRoot.bottom <= b.boundsInRoot.top + 1f) }
    }

    // ---------------------------------------------------------------- 5 版式

    @Test fun sublinesAre12SecondaryRightAlignedAndSpaced2() = layout(dark = false)

    @Test fun sublinesUseTheDarkSecondaryToken() = layout(dark = true)

    @Test
    @Config(qualifiers = "w320dp-h1600dp-xhdpi")
    fun font200W320SublinesWrapWithoutClipping() {
        host.launchRisk(fontScale = 2f)
        val row = rule.wdrRows().single().boundsInRoot
        FuRiskWdr.sublines(FuRiskWdr.fixturePending, null).forEach { line ->
            val node = rule.wdrText(line).single()
            node.assertNoTextOverflow()
            val glyph = node.glyphBox()
            assertTrue("「$line」不出行左右（$glyph ⊂ $row）", glyph.left >= row.left - 1f && glyph.right <= row.right + 1f)
        }
        assertTrue("先正：200% × w320 原文整句折行", rule.wdrText(FuRiskWdr.ORIGINAL_SLA).single().lines() > 1)
    }

    private fun layout(dark: Boolean) {
        host.launchRisk(dark = dark)
        val item = FuRiskWdr.fixturePending
        val value = rule.wdrText(FuRiskWdr.value(item)).single()
        val subs = FuRiskWdr.sublines(item, null).map { rule.wdrText(it).single() }
        val d = value.density()
        var above = value.textBox()
        subs.forEach { sub ->
            assertFont("副文案 12（caption）", 12f, sub)
            assertColor("副文案次文字色（${if (dark) "深" else "浅"}）", host.palette.secondary, sub.color())
            assertNotEquals("先正：次文字色 ≠ 主文字色", host.palette.primary, host.palette.secondary)
            assertDp("右缘齐值右缘", value.glyphBox().right / d, sub.glyphBox().right / d, 1f, 1.5f)
            assertDp("上一行 → 本行间距 2", 2f, sub.textBox().top - above.bottom, d)
            above = sub.textBox()
        }
        assertColor("值仍主文字色", host.palette.primary, value.color())
        assertFont("值仍 14", 14f, value)
    }

    private companion object {
        const val NUMBER_SLA = 24
        const val MILLIS = 1_000L
    }
}
