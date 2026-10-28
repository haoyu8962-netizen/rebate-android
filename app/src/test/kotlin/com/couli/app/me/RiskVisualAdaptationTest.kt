package com.couli.app.me

import android.app.Application
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.AccountTags
import com.couli.feature.me.MeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * brief-c §4 第 3、6、7 条与 §2 第 17 条：200% × w390 / w320 文字不截断不缩小（裁切判据同时比宽度与位置，量之前先滚到可见）、胶囊不截断、出口行可换行、
 * 相邻命中区不重叠、确认框接续字号；≥ 600（840 档）内容限宽 600 居中。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w320dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class RiskVisualAdaptationTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { RiskVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    private fun t(key: String) = AppMeTexts.text(key)

    @Test fun bannedAt200PercentW320() = banned()

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun bannedAt200PercentW390() = banned()

    @Test fun bannedAppealingPillAt200PercentW320() {
        host.launchRisk(risk = "risk_appealing_banned", appeals = "appeals_processing_banned", fontScale = 2f)
        full(host.pageTexts(t("risk.appeal.processing")).first())
        full(host.pageText(t("risk.view_progress")))
    }

    @Test fun frozenAt200PercentW320() {
        host.launchRisk(risk = "risk_frozen", withdrawals = "withdrawals_none", fontScale = 2f)
        listOf(t("risk.frozen.title"), t("risk.frozen.desc"), t("risk.frozen.note")).forEach { full(host.pageText(it)) }
        full(host.pageText(RiskVisualFixtures.expect("frozenUntilText")))
    }

    @Test fun appealSubmitTypedAt200PercentW320() {
        host.launchAppeal(fontScale = 2f)
        host.input(RiskVisualFixtures.input)
        listOf(t("appeal.target"), t("appeal.content_label")).forEach { full(host.pageHeading(it)) }
        full(host.pageText(t("risk.banned.title")))
        full(host.pageText(t("appeal.note.banned")))
        assertTrue("提交按钮固定可见", host.node(MeTags.APPEAL_SUBMIT).box().height > 0f)
        assertEquals("真放大", 2f, host.pageHeading(t("appeal.target")).fontScale(), 0.01f)
    }

    @Test fun progressAt200PercentW320() {
        host.launchAppeal(risk = "risk_appealing_banned", appeals = "appeals_processing_banned", fontScale = 2f)
        listOf(
            t("appeal.progress.desc.banned"),
            t("appeal.timeline.processing_desc.banned"),
            t("appeal.timeline.result_pending.banned"),
            RiskVisualFixtures.text("content", RiskVisualFixtures.latestAccount("appeals_processing_banned")!!),
            t("appeal.footer"),
        ).forEach { full(host.pageText(it)) }
        full(host.pageHeading(t("appeal.progress.heading")))
        val link = host.node(MeTags.APPEAL_CS)
        // 测试 v2 T7：上一步把页面滚回顶部，底部句链接已在视口外（触摸盒被裁成 0）；量之前先滚到可见（与 full() 同口径）
        if (link.insideScroll()) link.performScrollTo()
        host.idle()
        assertAtLeastDp("链接命中 ≥ 48", 48f, link.touch().height, link.density())
    }

    @Test fun upheldAt200PercentW320() {
        host.launchAppeal(appeals = "appeals_upheld_banned", fontScale = 2f, public = true)
        listOf(t("appeal.next.cs"), t("appeal.upheld.desc")).forEach { full(host.pageText(it)) }
        val exits = listOf(MeTags.APPEAL_AGAIN, MeTags.APPEAL_CS, AccountTags.APPEAL_DELETE, MeTags.APPEAL_LOGOUT).map { host.node(it) }
        exits.forEach { assertTrue("底栏控件可见", it.box().height > 0f) }
        exits.forEachIndexed { i, a ->
            exits.drop(i + 1).forEach { b -> assertSeparate("底栏命中不重叠", a.touch(), b.touch()) }
        }
    }

    @Test fun logoutDialogAt200PercentW320CarriesTheFontScale() {
        host.launchRisk(fontScale = 2f)
        host.tap(MeTags.RISK_LOGOUT)
        val title = host.dialogText(t("settings.logout.confirm"))
        assertEquals("确认框接续 200%", 2f, title.fontScale(), 0.01f)
        title.assertTextFully()
        val ok = rule.dialogButton(t("settings.logout.confirm_ok"))
        val cancel = rule.dialogButton(t("settings.logout.confirm_cancel"))
        assertSeparate("两按钮命中不重叠（放不下整块换行）", ok.touch(), cancel.touch())
    }

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun bannedAt840IsCappedAt600AndCentered() {
        host.launchRisk()
        capped(host.node(MeTags.RISK_PRIMARY))
        capped(host.node(MeTags.RISK_REASON))
    }

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun appealSubmitAt840IsCappedAt600AndCentered() {
        host.launchAppeal()
        capped(host.node(MeTags.APPEAL_SUBMIT))
    }

    @Test
    @Config(qualifiers = "w840dp-h1400dp-xhdpi")
    fun upheldAt840IsCappedAt600AndCentered() {
        host.launchAppeal(appeals = "appeals_upheld_banned")
        capped(host.node(MeTags.APPEAL_AGAIN))
    }

    private fun banned() {
        host.launchRisk(fontScale = 2f, public = true)
        listOf(t("risk.banned.desc"), t("risk.banned.note")).forEach { full(host.pageText(it)) }
        full(host.node(MeTags.RISK_TITLE))
        listOf(t("risk.reason"), t("risk.limit"), t("risk.withdrawals"), t("risk.limit.long")).forEach { full(host.pageTexts(it).first()) }
        listOf(MeTags.RISK_CS to t("risk.contact_cs"), MeTags.RISK_LOGOUT to t("risk.logout")).forEach { (tag, text) ->
            full(rule.textUnder(tag, text))
        }
        val links = listOf(MeTags.RISK_CS, AccountTags.RISK_DELETE, MeTags.RISK_LOGOUT, MeTags.RISK_PRIMARY).map { host.node(it) }
        links.forEachIndexed { i, a -> links.drop(i + 1).forEach { b -> assertSeparate("出口 / 主按钮命中不重叠", a.touch(), b.touch()) } }
        assertEquals("真放大", 2f, host.node(MeTags.RISK_TITLE).fontScale(), 0.01f)
    }

    private fun capped(node: SemanticsNodeInteraction) {
        val d = node.density()
        val box = node.box()
        assertTrue("限宽 ≤ 600：${box.width / d}", box.width / d <= 600.5f)
        assertEquals("居中", 420f, box.center.x / d, 1f)
    }

    private fun full(node: SemanticsNodeInteraction) {
        if (node.insideScroll()) node.performScrollTo()
        host.idle()
        node.assertTextFully()
        assertTrue("不缩小字号", node.textStyle().fontSize.value >= 12f)
    }
}
