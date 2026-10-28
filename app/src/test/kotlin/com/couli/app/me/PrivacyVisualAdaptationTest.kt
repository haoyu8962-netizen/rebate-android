package com.couli.app.me

import android.app.Application
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollTo
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
 * brief-b §4 第 6、7 条：200% × w390 / w320 文字不截断不缩小（裁切判据同时比宽度与位置，量之前先滚到可见）、弹窗 / 半屏接续页面字号、命中区不重叠；
 * ≥ 600（840 档）内容限宽 600 居中。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w320dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class PrivacyVisualAdaptationTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PrivacyVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    private fun t(key: String) = AppMeTexts.text(key)

    @Test fun privacyCenterAt200PercentW320() = privacyFull()

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun privacyCenterAt200PercentW390() = privacyFull()

    @Test fun basicAt200PercentW320() {
        host.launchPrivacy(session = "privacy_basic", fontScale = 2f)
        listOf(t("privacy.basic_mode.notice"), t("privacy.system_permissions.hint")).forEach { full(host.pageText(it)) }
        full(host.pageHeading(t("privacy_center.basic_title")))
        full(rule.textUnder(MeTags.PRIVACY_ENABLE_FULL, t("privacy.basic_mode.enable")))
    }

    @Test fun withdrawDialogAt200PercentW320CarriesTheFontScaleAndKeepsButtonsVisible() {
        host.launchPrivacy(fontScale = 2f)
        host.tap(MeTags.PRIVACY_WITHDRAW)
        val title = host.dialogText(t("privacy.withdraw.confirm.title"))
        assertEquals("确认框接续 200%", 2f, title.fontScale(), 0.01f)
        title.assertTextFully()
        val cancel = rule.dialogButton(t("privacy.withdraw.confirm.cancel"))
        val confirm = rule.dialogButton(t("privacy.withdraw.confirm.ok"))
        assertSeparate("两按钮命中不重叠（放不下整块换行）", cancel.touch(), confirm.touch())
        listOf(cancel, confirm).forEach { assertTrue("按钮在窗口内可见", it.box().height > 0f && it.box().bottom <= host.pixels(it).height + 1f) }
    }

    @Test fun aboutAt200PercentW320() {
        host.launchAbout(fontScale = 2f)
        full(
            host.pageText(
                AppMeTexts.format(
                    "about.version",
                    mapOf(
                        "version" to PrivacyVisualFixtures.version,
                        "build" to PrivacyVisualFixtures.build,
                    ),
                ),
            ),
        )
        full(host.pageText(PrivacyVisualFixtures.filed))
        full(rule.textUnder(MeTags.ABOUT_COPY, t("about.copy_diagnostics")))
        full(host.pageText(t("about.copy_desc")))
        assertEquals("真放大", 2f, host.pageText(t("about.copy_desc")).fontScale(), 0.01f)
    }

    @Test
    @Config(qualifiers = "w320dp-h1032dp-xhdpi")
    fun csSheetAt200PercentW320() {
        host.launchCs("cs_open_failed", fontScale = 2f)
        val title = host.sheetText(t("cs.open_failed.title"))
        assertEquals("半屏接续 200%", 2f, title.fontScale(), 0.01f)
        listOf(title, host.sheetText(t("cs.open_failed.desc")), host.sheetText(PrivacyVisualFixtures.safetyDraft)).forEach(::full)
        listOf(MeTags.CS_RETRY, MeTags.CS_COPY, MeTags.CS_HELP).forEach { assertTrue("按钮固定可见：$it", host.node(it).box().height > 0f) }
        assertSeparate("复制 / 帮助链接命中不重叠", host.node(MeTags.CS_COPY).touch(), host.node(MeTags.CS_HELP).touch())
        assertSeparate("重试 / 复制命中不重叠", host.node(MeTags.CS_RETRY).touch(), host.node(MeTags.CS_COPY).touch())
    }

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun privacyCenterAt840IsCappedAt600AndCentered() {
        host.launchPrivacy()
        val row = host.node(MeTags.PRIVACY_LIST_PREFIX + "collect")
        val d = row.density()
        val box = row.box()
        assertTrue("限宽 ≤ 600：${box.width / d}", box.width / d <= 600.5f)
        assertEquals("居中", 420f, box.center.x / d, 1f)
        val button = host.node(MeTags.PRIVACY_WITHDRAW).box()
        assertEquals("撤回按钮同样居中", 420f, button.center.x / d, 1f)
    }

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun aboutAt840IsCappedAt600AndCentered() {
        host.launchAbout()
        val button = host.node(MeTags.ABOUT_COPY)
        val d = button.density()
        assertTrue("限宽 ≤ 600", button.box().width / d <= 600.5f)
        assertEquals("居中", 420f, button.box().center.x / d, 1f)
    }

    private fun privacyFull() {
        host.launchPrivacy(fontScale = 2f)
        listOf(
            "privacy_center.collect_list" to MeTags.PRIVACY_LIST_PREFIX + "collect",
            "privacy_center.share_list" to MeTags.PRIVACY_LIST_PREFIX + "share",
            "privacy_center.privacy_policy" to MeTags.PRIVACY_POLICY,
            "privacy_center.agreement" to MeTags.PRIVACY_AGREEMENT,
        ).forEach { (key, tag) -> full(rule.textUnder(tag, t(key))) }
        full(rule.textUnder(MeTags.PRIVACY_SWITCH, t("privacy_center.personalization_desc")))
        full(rule.textUnder(MeTags.PRIVACY_PERMISSIONS, t("privacy.system_permissions.title")))
        full(rule.textUnder(MeTags.PRIVACY_PERMISSIONS, t("privacy.system_permissions.hint")))
        full(host.pageHeading(t("privacy_center.withdraw_title")))
        full(host.pageText(t("privacy_center.withdraw_desc")))
        full(rule.textUnder(MeTags.PRIVACY_WITHDRAW, t("privacy_center.withdraw")))
        val rows =
            listOf(
                MeTags.PRIVACY_LIST_PREFIX + "collect",
                MeTags.PRIVACY_LIST_PREFIX + "share",
                MeTags.PRIVACY_POLICY,
                MeTags.PRIVACY_AGREEMENT,
            )
        rows.zipWithNext().forEach { (a, b) -> assertSeparate("相邻行命中不重叠：$a / $b", host.node(a).touch(), host.node(b).touch()) }
        assertEquals("真放大", 2f, rule.textUnder(rows.first(), t("privacy_center.collect_list")).fontScale(), 0.01f)
    }

    private fun full(node: SemanticsNodeInteraction) {
        if (node.insideScroll()) node.performScrollTo()
        host.idle()
        node.assertTextFully()
        assertTrue("不缩小字号", node.textStyle().fontSize.value >= 12f)
    }
}
