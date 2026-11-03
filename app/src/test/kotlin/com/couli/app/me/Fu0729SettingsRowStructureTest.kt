package com.couli.app.me

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.ClipboardScreens
import com.couli.core.designsystem.CouliTheme
import com.couli.feature.agent.AgentTags
import com.couli.feature.agent.AgentWithdrawRow
import com.couli.feature.me.MeTags
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-0729 ①（brief §1、§1.5；编排 11-02 14:45「三端以画板为准」、11-03 13:39 认可只在设置主页传 52）：
 * - 正：设置主页（已登录公开版 / 内测版 / 游客 / 基本模式）每个导航行与动作行「重新显示下单须知」视觉高 52，同卡相邻行节距 53、只隔 1px 分隔线，
 *   账号卡 219；行高 = max(52, 行内容 + 上下 4)（content-box：放大字号随文字长高，不裁切），200% 相邻行命中不重叠。
 * - 反（防误伤，底版与实现后都绿）：隐私中心清单四行仍 48、节距 49；协议半屏两行仍 48（画板 44 是 border-box，Android 命中区）；剪贴板开关行仍是
 *   max(48, 文字 + 16)；通知设置服务卡标题行仍 min 44；撤回 AI 服务同意行仍是 max(48, 文字 + 24)（= 49.5，无稿、另模块）。
 * - 行为不变：行的上下边缘（新增的 4dp 带内）真实触摸各恰一次原动作。
 * 底版 c8eac4a：正向判据全红（行 48、节距 49、账号卡 203），反向与行为全绿。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1039dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions") // 一项判据一个用例
class Fu0729SettingsRowStructureTest {
    @get:Rule val rule = createComposeRule()
    private var settingsHost: SettingsVisualTestHost? = null
    private var privacyHost: PrivacyVisualTestHost? = null
    private val empty = JsonObject(emptyMap())

    private val host: SettingsVisualTestHost
        get() = settingsHost ?: SettingsVisualTestHost(rule).also { settingsHost = it }

    private val privacy: PrivacyVisualTestHost
        get() = privacyHost ?: PrivacyVisualTestHost(rule).also { privacyHost = it }

    @After fun tearDown() {
        settingsHost?.close()
        privacyHost?.close()
    }

    // ---------------------------------------------------------------- 正：52 / 53 / 219

    @Test fun memberPublicNavAndActionRowsAre52() {
        host.launch(public = true)
        Fu0729Rows.assertRows52(host, Fu0729Rows.member, "已登录公开版")
        assertTrue("先正：动作行「重新显示下单须知」在清单里", SettingsRows.RESET_TIP in Fu0729Rows.member)
    }

    @Test fun resetTipActionRowIs52Too() {
        host.launch()
        val reset = host.row(SettingsRows.RESET_TIP)
        val d = reset.density()
        assertDp("动作行「重新显示下单须知」= 52（11-02 14:45，同卡节距统一；画板像素 44 是 <button> 盒模型）", 52f, Fu0729Rows.frame(reset).height, d, 0.5f)
        assertTrue("动作行仍不画 ›（S5 不变）", reset.iconBoxes().isEmpty())
    }

    @Test fun betaRowsAre52() {
        host.launch(public = false)
        Fu0729Rows.assertRows52(host, Fu0729Rows.beta, "内测版")
    }

    @Test fun guestRowsAre52() {
        host.launch(session = "guest_consented")
        Fu0729Rows.assertRows52(host, Fu0729Rows.guest, "游客")
    }

    @Test fun basicRowsAre52() {
        host.launch(session = "basic")
        Fu0729Rows.assertRows52(host, Fu0729Rows.basic, "基本模式")
    }

    @Test fun adjacentRowsInOneCardHavePitch53WithOnlyADivider() {
        host.launch()
        Fu0729Rows.memberPairs.forEach { (upper, lower) ->
            val a = Fu0729Rows.frame(host.row(upper))
            val b = Fu0729Rows.frame(host.row(lower))
            val d = host.row(upper).density()
            assertDp("节距 53（52 + 分隔线 1）：$upper → $lower", Fu0729Rows.PITCH, b.top - a.top, d, 1f)
            assertDp("两行之间只有 1px 分隔线：$upper → $lower", 1f, b.top - a.bottom, d, 0.5f)
        }
    }

    @Test fun accountCardIs219() {
        host.launch(public = true)
        val first = Fu0729Rows.frame(host.row(SettingsRows.NICKNAME))
        val last = Fu0729Rows.frame(host.row(SettingsRows.DELETE))
        val d = host.row(SettingsRows.NICKNAME).density()
        val px = rule.pixels()
        val cx = px.width / 2f
        val cardTop = px.edgeUp(cx, first.top - 1, host.palette.surface)
        val cardBottom = px.edgeDown(cx, last.bottom, host.palette.surface)
        assertDp("先正：卡内上 4（卡片外框按 surface 像素扫描）", 4f, first.top - cardTop, d, 1f)
        assertDp("账号卡 = 4 + 52 × 4 + 3 + 4 = 219（现为 203）", Fu0729Rows.ACCOUNT_CARD, cardBottom - cardTop, d, 1.5f)
    }

    @Test fun rowHeightIsMax52OrContentPlus8At100Percent() = assertRowsFollowContent()

    @Test fun rowHeightIsMax52OrContentPlus8At200PercentW390() = assertRowsFollowContent(fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h1039dp-xhdpi")
    fun rowHeightIsMax52OrContentPlus8At200PercentW320() = assertRowsFollowContent(fontScale = 2f)

    // ---------------------------------------------------------------- 反：不跟改的行

    @Test fun privacyCenterListRowsStay48WithPitch49() {
        privacy.launchPrivacy()
        val tags =
            listOf(
                MeTags.PRIVACY_LIST_PREFIX + "collect",
                MeTags.PRIVACY_LIST_PREFIX + "share",
                MeTags.PRIVACY_POLICY,
                MeTags.PRIVACY_AGREEMENT,
            )
        val frames = tags.map { Fu0729Rows.frame(privacy.node(it)) }
        val d = privacy.node(tags.first()).density()
        tags.zip(frames).forEach { (tag, frame) ->
            assertDp("隐私中心清单行保持 48（画板 border-box 44，Android 命中区；不跟设置主页改 52）：$tag", Fu0729Rows.HIT, frame.height, d, 0.5f)
        }
        frames.zipWithNext().forEach { (a, b) -> assertDp("隐私中心清单节距 49", Fu0729Rows.HIT + 1f, b.top - a.top, d, 1f) }
    }

    @Test fun agreementSheetRowsStay48() {
        host.launch()
        host.tapRow(SettingsRows.AGREEMENT)
        assertTrue(host.has(MeTags.AGREEMENT_SHEET))
        listOf(MeTags.AGREEMENT_USER, MeTags.AGREEMENT_PRIVACY).forEach { tag ->
            val node = host.node(tag)
            assertDp("协议半屏行保持 48（「44 不动」在 Android 即保持命中区 48）：$tag", Fu0729Rows.HIT, Fu0729Rows.frame(node).height, node.density(), 0.5f)
        }
    }

    @Test fun clipboardSwitchRowKeepsContentPlus16() {
        host.launch()
        val node = host.node(ClipboardScreens.SETTINGS_ROW)
        val d = node.density()
        val span = Fu0729Rows.textSpan(rule, ClipboardScreens.SETTINGS_ROW)
        val expected = maxOf(Fu0729Rows.HIT * d, span + 16 * d)
        assertEquals("剪贴板开关行不变：max(48, 文字 + 上下 8)", expected / d, Fu0729Rows.frame(node).height / d, 0.5f)
    }

    @Test fun notifyServiceCardTitleRowStaysMin44() {
        host.launch()
        host.openNotify()
        val card = host.node(MeTags.NOTIFY_SWITCH)
        val d = card.density()
        val frame = Fu0729Rows.frame(card)
        val desc = rule.textUnder(MeTags.NOTIFY_SWITCH, AppMeTexts.text("notify.service_desc")).textBox()
        assertDp("通知服务卡下内边距 12 不变", 12f, frame.bottom - desc.bottom, d, 0.5f)
        assertDp("通知服务卡标题行仍 min 44（不跟改 52）", 44f, desc.top - 4 * d - (frame.top + 12 * d), d, 0.5f)
    }

    @Test fun agentWithdrawRowStaysMax48OrTextPlus24() {
        rule.setContent {
            CouliTheme(darkTheme = false) {
                AgentWithdrawRow(confirmId = null, onOpen = {}, onConfirm = {}, onCancel = {}, enabled = true)
            }
        }
        rule.waitForIdle()
        val node = rule.onNodeWithTag(AgentTags.WITHDRAW_ROW, useUnmergedTree = true)
        val d = node.density()
        val text = Fu0729Rows.textHeight(rule, AgentTags.WITHDRAW_ROW)
        val expected = maxOf(Fu0729Rows.HIT * d, text + 24 * d)
        assertEquals("撤回 AI 服务同意行不变：max(48, 文字 + 上下 12)（= 49.5，无稿、feature/agent 不改）", expected / d, Fu0729Rows.frame(node).height / d, 0.5f)
    }

    // ---------------------------------------------------------------- 行为不变：新增的边缘带内触摸恰一次

    @Test fun routeRowEdgesOpenTheirRouteOncePerTouch() {
        host.launch()
        val row = host.row(SettingsRows.HELP)
        val d = row.density()
        val frame = Fu0729Rows.frame(row)
        listOf(Offset(frame.center.x, frame.top + d), Offset(frame.center.x, frame.bottom - d)).forEachIndexed { index, point ->
            rule.meTouch(point)
            host.idle()
            assertEquals("帮助中心行边缘 $point → Help 恰一次", List(index + 1) { "Help" to empty }, host.env.opened)
        }
        assertTrue("没有经账号环境另开路由", host.accountEnv.opened.isEmpty())
    }

    @Test fun resetTipRowEdgeSendsExactlyOneRequest() {
        host.launch()
        val row = host.row(SettingsRows.RESET_TIP)
        val frame = Fu0729Rows.frame(row)
        rule.meTouch(Offset(frame.center.x, frame.bottom - row.density()))
        host.settle("resetTip 结束") { host.has(MeTags.TOAST) }
        assertEquals("动作行点一次 → resetTip 恰一次", 1, host.api.count(SettingsVisualApi.RESET_TIP))
        assertTrue("动作行不跳页", host.env.opened.isEmpty())
    }

    // ---------------------------------------------------------------- 工具

    /** 每行：行高 = max(52, 行内文字 / chevron 最大高 + 8)；同卡相邻行不重叠。200% 时先滚到可见再量。 */
    private fun assertRowsFollowContent(fontScale: Float = 1f) {
        host.launch(fontScale = fontScale)
        Fu0729Rows.member.forEach { key ->
            val row = host.row(key)
            if (row.insideScroll()) row.performScrollTo()
            host.idle()
            val d = row.density()
            val content = maxOf(Fu0729Rows.textHeight(rule, Fu0729Rows.tag(key)), Fu0729Rows.CHEVRON * d)
            val expected = maxOf(Fu0729Rows.ROW * d, content + 2 * Fu0729Rows.PAD * d)
            assertEquals("${fontScale}x：$key 行高 = max(52, 内容 + 上下 4)", expected / d, Fu0729Rows.frame(row).height / d, 0.5f)
        }
        Fu0729Rows.memberPairs.forEach { (upper, lower) ->
            val a = Fu0729Rows.frame(host.row(upper))
            val b = Fu0729Rows.frame(host.row(lower))
            assertTrue("${fontScale}x：$upper / $lower 命中不重叠：$a / $b", b.top >= a.bottom - 0.5f)
        }
    }
}
