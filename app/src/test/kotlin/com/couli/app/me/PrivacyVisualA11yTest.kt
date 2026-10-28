package com.couli.app.me

import android.app.Application
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * brief-b §4 第 2 条读屏（合并树遍历，不代替实机 TalkBack）：隐私中心 返回 → 标题 → 清单四行 → 个性化 → 系统权限（标题）→ 说明 → 撤回同意（标题）→ 说明 →
 * 【撤回同意】；基本模式 提示句 → … → 开启完整功能（标题）→ 按钮；确认框打开焦点进标题；关于 标题 → 凑狸（标题）→ 版本 → 公示（标题）→ 正文 →【复制诊断信息】→
 * 说明；客服半屏 标题 → 关闭 → 说明 → 安全提示 → 按钮 → 链接。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivacyVisualA11yTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PrivacyVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    private fun t(key: String) = AppMeTexts.text(key)

    @Test fun privacyCenterReadingOrder() {
        host.launchPrivacy()
        val nodes = rule.onRoot().fetchSemanticsNode().pvFlatten()
        assertOrder(
            nodes,
            t("me.back"),
            t("privacy_center.title"),
            t("privacy_center.collect_list"),
            t("privacy_center.share_list"),
            t("privacy_center.privacy_policy"),
            t("privacy_center.agreement"),
            t("privacy_center.personalization"),
            t("privacy.system_permissions.title"),
            t("privacy.system_permissions.hint"),
            t("privacy_center.withdraw_desc"),
        )
        val heading = nodes.indexOfFirst { it.isHeading() && t("privacy_center.withdraw_title") in it.spokenParts() }
        val desc = nodes.indexOfFirst { t("privacy_center.withdraw_desc") in it.spokenParts() }
        val button =
            nodes.indexOfFirst {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag] == MeTags.PRIVACY_WITHDRAW
            }
        assertTrue("撤回同意（标题）→ 说明 →【撤回同意】：$heading / $desc / $button", heading in 0 until desc && desc < button)
        assertTrue("系统权限标题是读屏标题", nodes.any { it.isHeading() && t("privacy.system_permissions.title") in it.spokenParts() })
    }

    @Test fun basicReadsNoticeThenEnableHeadingThenButton() {
        host.launchPrivacy(session = "privacy_basic")
        val nodes = rule.onRoot().fetchSemanticsNode().pvFlatten()
        val notice = nodes.indexOfFirst { t("privacy.basic_mode.notice") in it.spokenParts() }
        val heading = nodes.indexOfFirst { it.isHeading() && t("privacy_center.basic_title") in it.spokenParts() }
        val button =
            nodes.indexOfFirst {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag] == MeTags.PRIVACY_ENABLE_FULL
            }
        assertTrue("提示句 → 开启完整功能（标题）→ 按钮：$notice / $heading / $button", notice in 0 until heading && heading < button)
        assertEquals("提示整句只读一次", 1, nodes.count { node -> node.spokenParts().any { it == t("privacy.basic_mode.notice") } })
    }

    @Test fun withdrawDialogFocusEntersTheTitle() {
        host.launchPrivacy()
        host.tap(MeTags.PRIVACY_WITHDRAW)
        // v2（T5）：CouliDialog 打开后 FocusOnAppear 延时 150ms 才请求焦点；断言前推进时钟 200ms，断言本身不变。
        host.settleFocus()
        host.dialogText(t("privacy.withdraw.confirm.title")).assertIsFocused()
    }

    @Test fun aboutReadingOrder() {
        host.launchAbout()
        val nodes = rule.onRoot().fetchSemanticsNode().pvFlatten()
        assertOrder(
            nodes,
            t("me.back"),
            t("about.title"),
            t("about.app_name"),
            AppMeTexts.format("about.version", mapOf("version" to PrivacyVisualFixtures.version, "build" to PrivacyVisualFixtures.build)),
            t("about.filing_title"),
            PrivacyVisualFixtures.filed,
            t("about.copy_diagnostics"),
            t("about.copy_desc"),
        )
        listOf(t("about.app_name"), t("about.filing_title")).forEach { name ->
            assertTrue("读屏标题：$name", nodes.any { it.isHeading() && name in it.spokenParts() })
        }
    }

    @Test fun csSheetReadingOrder() {
        host.launchCs("cs_open_failed")
        val nodes = host.rootOf(host.sheetText(t("cs.open_failed.title"))).pvFlatten()
        assertOrder(
            nodes,
            t("cs.open_failed.title"),
            t("me.close"),
            t("cs.open_failed.desc"),
            PrivacyVisualFixtures.safetyDraft,
            t("cs.retry"),
            t("cs.copy_link"),
            t("cs.go_help"),
        )
    }

    private fun SemanticsNode.isHeading() = config.contains(SemanticsProperties.Heading)

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
}
