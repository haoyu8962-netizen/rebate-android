package com.couli.app.me

import android.app.Application
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * brief-c §4 第 2 条读屏（合并树遍历，不代替实机 TalkBack）：账号状态（封禁）标题 → 状态标题 → 说明 → 各行「标签，值」→ 提示句 → 主按钮 → 联系客服 →
 * 注销账号（有时）→ 退出登录；冻结多一个返回在最前；申诉提交 返回 → 标题 → 申诉对象（标题）→ 各行 → 申诉说明（标题）→ 输入框 → 提示句 →【提交申诉】；
 * 处理中 状态标题 → 说明 → 时间线三步 → 申诉内容（标题）→ 底部句 → 联系客服；图标块、胶囊图标、时间线图标、分隔符不读；确认框焦点进标题；提交失败不双播。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1600dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class RiskVisualA11yTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { RiskVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    private fun t(key: String) = AppMeTexts.text(key)

    private fun nodes(): List<SemanticsNode> = rule.onRoot().fetchSemanticsNode().pvFlatten()

    @Test fun bannedReadingOrder() {
        host.launchRisk(public = true)
        val nodes = nodes()
        assertOrder(
            nodes,
            t("risk.title"),
            t("risk.banned.title"),
            t("risk.banned.desc"),
            t("risk.reason"),
            t("risk.limit"),
            t("risk.withdrawals"),
            t("risk.banned.note"),
            t("risk.submit"),
            t("risk.contact_cs"),
            DELETE,
            t("risk.logout"),
        )
        assertFalse("封禁无返回", nodes.spokenAll().contains(t("me.back")))
        listOf(t("risk.title"), t("risk.banned.title")).forEach { name ->
            assertTrue("读屏标题：$name", nodes.any { it.isHeadingNode() && name in it.spokenParts() })
        }
    }

    @Test fun frozenReadsBackFirst() {
        host.launchRisk(risk = "risk_frozen", withdrawals = "withdrawals_none")
        assertOrder(nodes(), t("me.back"), t("risk.title"), t("risk.frozen.title"), t("risk.frozen_until"), t("risk.submit"))
    }

    @Test fun eachInfoRowIsOneMergedLabelValueNode() {
        host.launchRisk()
        val row = nodes().filter { t("risk.limit") in it.spokenParts() }
        assertEquals("「限制期限」一个合并节点", 1, row.size)
        assertTrue("同节点读值", row.single().spokenParts().contains(t("risk.limit.long")))
    }

    @Test fun pillTextIsReadOnceAndItsIconIsSilent() {
        host.launchRisk(risk = "risk_appealing_banned", appeals = "appeals_processing_banned")
        val row = nodes().filter { t("risk.appeal") in it.spokenParts() }
        assertEquals(1, row.size)
        assertEquals("「申诉，处理中」胶囊文字只读一次", 1, row.single().spokenParts().count { it == t("risk.appeal.processing") })
        assertNoDecorativeDescriptions()
    }

    @Test fun separatorsAndIconBlockAreNotSpoken() {
        host.launchRisk(public = true)
        val spoken = nodes().spokenAll()
        assertTrue("先正：链接读出", spoken.contains(t("risk.contact_cs")))
        assertFalse("分隔符「|」不读", spoken.any { it.trim() == "|" })
        assertNoDecorativeDescriptions()
    }

    @Test fun appealSubmitReadingOrder() {
        host.launchAppeal()
        val nodes = nodes()
        assertOrder(
            nodes,
            t("me.back"),
            t("appeal.title"),
            t("appeal.target"),
            t("appeal.type"),
            t("appeal.current_state"),
            t("appeal.content_label"),
            t("appeal.note.banned"),
            t("appeal.submit"),
        )
        listOf(t("appeal.target"), t("appeal.content_label")).forEach { name ->
            assertTrue("卡内标题是读屏标题：$name", nodes.any { it.isHeadingNode() && name in it.spokenParts() })
        }
    }

    @Test fun progressReadingOrderAndOneNodePerTimelineStep() {
        host.launchAppeal(risk = "risk_appealing_banned", appeals = "appeals_processing_banned")
        val nodes = nodes()
        assertOrder(
            nodes,
            t("appeal.progress.heading"),
            t("appeal.progress.desc.banned"),
            t("appeal.timeline.submitted"),
            t("appeal.timeline.result"),
            t("appeal.content_section"),
            t("appeal.footer"),
            t("appeal.contact_cs"),
        )
        val created = RiskVisualFixtures.expect("bannedCreatedText")
        assertEquals(
            "已提交一步一个节点（标题 + 时间）",
            1,
            nodes.count { t("appeal.timeline.submitted") in it.spokenParts() && created in it.spokenParts() },
        )
        assertTrue("申诉内容是读屏标题", nodes.any { it.isHeadingNode() && t("appeal.content_section") in it.spokenParts() })
        assertNoDecorativeDescriptions()
    }

    @Test fun upheldReadsCardsThenAgainThenExits() {
        host.launchAppeal(appeals = "appeals_upheld_banned", public = true)
        assertOrder(
            nodes(),
            t("appeal.upheld.title"),
            t("appeal.current_account_state"),
            t("appeal.next"),
            t("appeal.content_section"),
            t("appeal.again"),
            t("appeal.contact_cs"),
            DELETE,
            t("risk.logout"),
        )
    }

    @Test fun logoutDialogFocusEntersTheTitle() {
        host.launchRisk()
        host.tap(MeTags.RISK_LOGOUT)
        host.settleFocus()
        host.dialogText(t("settings.logout.confirm")).assertIsFocused()
    }

    @Test fun fieldErrorIsOnTheInputAndAnnouncedOnceByTheFocusTarget() {
        host.api.answers[RiskVisualApi.SUBMIT] = RiskVisualFixtures.failure(RiskVisualFixtures.code("fieldCode"))
        host.launchAppeal()
        host.input(RiskVisualFixtures.input)
        host.tap(MeTags.APPEAL_SUBMIT)
        host.settle("字段错误") { host.has(MeTags.APPEAL_ERROR) }
        val error =
            host
                .node(MeTags.APPEAL_ERROR)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString("") { it.text }
        val field = host.editable().fetchSemanticsNode()
        val carried = generateSequence(field) { it.parent }.any { it.config.getOrElseNullable(SemanticsProperties.Error) { null } == error }
        assertTrue("输入框带 error 语义（读屏「申诉说明，编辑框，错误：…」）", carried)
        host.idle()
        host.node(MeTags.APPEAL_ERROR).assertIsFocused()
        assertFalse("不叠加轻提示", host.has(MeTags.TOAST))
        // 测试 v2 T6：量具把 assertive live region 也记一次播报；ErrorFocusText（焦点目标 + assertive）原样恰一次是硬约束，
        // 所以口径是「错误句恰一次，且来自焦点目标的 assertive live region」，不另行主动播报（announcer）、不双播
        val deliveries = host.announcements.sample().filter { it.text.contains(error) }
        assertEquals("错误句恰播报一次（不双播）：$deliveries", 1, deliveries.size)
        assertEquals("来自焦点目标的 live region，不是另行主动播报", "liveRegion", deliveries.single().channel)
        assertEquals("assertive", LiveRegionMode.Assertive, deliveries.single().priority)
        assertEquals("保留输入", 1, host.api.count(RiskVisualApi.SUBMIT))
    }

    private fun assertNoDecorativeDescriptions() {
        val descriptions = nodes().flatMap { it.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty() }
        assertTrue("图标块 / 胶囊图标 / 时间线图标不读：$descriptions", descriptions.all { it == t("me.back") })
    }

    private fun assertOrder(
        nodes: List<SemanticsNode>,
        vararg expected: String,
    ) {
        val spoken = nodes.map { it.spokenParts() }
        val positions =
            expected.map { phrase ->
                spoken.indexOfFirst { parts -> parts.any { it == phrase } }.also { assertTrue("读屏里找不到「$phrase」：$spoken", it >= 0) }
            }
        assertEquals("读屏顺序：${expected.toList()} 实际位置 $positions", positions.sorted(), positions)
    }

    private companion object {
        val DELETE = AppMeTexts.text("settings.delete_account")
    }
}
