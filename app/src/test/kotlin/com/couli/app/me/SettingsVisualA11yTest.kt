package com.couli.app.me

import android.app.Application
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.ClipboardDraftTexts
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
 * brief §4 第 2 条读屏（合并树遍历，不代替实机 TalkBack）：返回 → 标题 → 分组名（标题）→ 每行「标题，值，按钮」→ 剪贴板「名称，开关，状态」+ 说明 →
 * 【退出登录】；游客说明 + 去登录；基本模式提示 + 【开启完整功能】；通知：提示句 → 去设置 → 服务类推送开关 → 说明。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1039dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsVisualA11yTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SettingsVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    @Test fun memberPageReadingOrder() {
        host.launch()
        assertOrder(
            "返回",
            "设置",
            "账号与安全",
            "昵称",
            "手机号",
            "授权管理",
            "注销账号",
            "隐私",
            "隐私中心",
            "用户协议与隐私政策",
            "通用",
            "通知设置",
            "重新显示下单须知",
            ClipboardDraftTexts.text("settings.clipboard_auto"),
            "清除缓存",
            "帮助中心",
            "关于凑狸",
            "退出登录",
        )
        listOf("账号与安全", "隐私", "通用").forEach { name ->
            assertTrue("分组名是读屏标题：$name", nodes().any { it.config.contains(SemanticsProperties.Heading) && it.spokenParts().contains(name) })
        }
    }

    @Test fun eachNavRowIsOneButtonReadingTitleThenValue() {
        host.launch()
        val nickname = rule.onNodeWithTag(MeTags.SETTINGS_ROW_PREFIX + SettingsRows.NICKNAME).fetchSemanticsNode()
        assertEquals(Role.Button, nickname.config.getOrNull(SemanticsProperties.Role))
        assertEquals("「标题，值」", listOf("昵称", SettingsVisualFixtures.original), nickname.spokenParts())
        val cache = rule.onNodeWithTag(MeTags.SETTINGS_ROW_PREFIX + SettingsRows.CLEAR_CACHE).fetchSemanticsNode()
        assertEquals(listOf("清除缓存", "12 MB"), cache.spokenParts())
        val reset = rule.onNodeWithTag(MeTags.SETTINGS_ROW_PREFIX + SettingsRows.RESET_TIP).fetchSemanticsNode()
        assertEquals("动作行仍是一个按钮", Role.Button, reset.config.getOrNull(SemanticsProperties.Role))
        assertEquals(listOf("重新显示下单须知"), reset.spokenParts())
        SettingsRows.account.forEach { key ->
            val node = rule.onNodeWithTag(MeTags.SETTINGS_ROW_PREFIX + key).fetchSemanticsNode()
            assertTrue("整行一个可点目标：$key", node.config.contains(SemanticsActions.OnClick))
        }
    }

    @Test fun guestReadsNoteThenLoginLink() {
        host.launch(session = "guest_consented")
        assertOrder("隐私中心", "帮助中心", "登录后可以管理账号、授权和通知", "去登录")
        val link = rule.onNodeWithTag(MeTags.SETTINGS_LOGIN).fetchSemanticsNode()
        assertEquals("文字链接读作按钮（G-S08 ①）", Role.Button, link.config.getOrNull(SemanticsProperties.Role))
        assertEquals(listOf("去登录"), link.spokenParts())
    }

    @Test fun basicReadsNoticeOnceThenEnableButton() {
        host.launch(session = "basic")
        val notice = "当前为基本模式，只能浏览。开启完整功能后可以登录、查返利"
        assertOrder("帮助中心", notice, "开启完整功能")
        assertEquals("提示条整句读一次", 1, nodes().count { node -> node.spokenParts().any { it == notice } })
    }

    @Test fun notifyReadsBarThenLinkThenSwitchThenItsDescription() {
        host.env.status = SettingsVisualFixtures.notify("denied")
        host.launch()
        host.openNotify()
        assertOrder("通知权限已关闭，可以在系统设置中开启", "去设置", "服务类推送")
        val link = rule.onNodeWithTag(MeTags.NOTIFY_GO_SETTINGS).fetchSemanticsNode()
        assertEquals("「去设置，按钮」", Role.Button, link.config.getOrNull(SemanticsProperties.Role))
        assertEquals(listOf("去设置"), link.spokenParts())
    }

    private fun nodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()

        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        walk(rule.onRoot().fetchSemanticsNode())
        return out
    }

    private fun assertOrder(vararg expected: String) {
        val spoken = nodes().map { it.spokenParts() }
        val positions =
            expected.map { phrase ->
                spoken.indexOfFirst { parts -> parts.any { it == phrase } }.also { assertTrue("读屏里找不到「$phrase」：$spoken", it >= 0) }
            }
        assertEquals("读屏顺序：${expected.toList()} 实际位置 $positions", positions.sorted(), positions)
    }
}
