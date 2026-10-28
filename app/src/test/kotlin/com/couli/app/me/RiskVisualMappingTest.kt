package com.couli.app.me

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * brief-c §5「视图层纯逻辑单测」——按渲染结果黑盒核对映射（不依赖实现方的私有函数名，app 模块也看不到 feature/me 的 internal）：
 * - 胶囊色调只由本次 risk.state 一一映射：banned → error + 三角；frozen → warning + 三角；appealing / normal → pending、无图标（G-R11）；
 * - 底部栏与按钮主次只由版式映射：提交 = 白底栏主按钮；处理中 = 无底部按钮；解除 = 非白底、首个主其余链接；维持 = 白底栏次按钮 + 出口行（G-R11 / G-R13）。
 * 文字仍是 AppealCopy.currentStateKey 原键（不另读状态）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1600dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class RiskVisualMappingTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { RiskVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    private fun t(key: String) = AppMeTexts.text(key)

    private val page get() = host.node(MeTags.APPEAL_PAGE)

    // ---------------------------------------------------------------- 胶囊色调 ← risk.state

    @Test fun toneBannedIsError() = tone("risk_banned", t("risk.banned.title"), Tone.ERROR)

    @Test fun toneFrozenIsWarning() = tone("risk_frozen", t("risk.frozen.title"), Tone.WARNING)

    @Test fun toneAppealingIsPendingWithoutIcon() = tone("risk_appealing_banned", t("appeal.state_appealing"), Tone.PENDING)

    @Test fun toneNormalIsPendingWithoutIcon() = tone("risk_normal", t("appeal.state_normal"), Tone.PENDING)

    @Test fun toneFollowsGetMeNotTheEntryFlag() {
        // 进入标记（G10）只选文案组，不影响胶囊色：appealing + 进入标记 true 仍是 pending
        tone("risk_appealing_banned", t("appeal.state_appealing"), Tone.PENDING, entered10006 = true)
    }

    // ---------------------------------------------------------------- 底部栏 / 按钮主次 ← 版式

    @Test fun submitLayoutHasAPrimaryButtonInTheWhiteBar() {
        host.launchAppeal()
        // 测试 v2 T5：空输入时 enabled = false（红线条件，组件库禁用色）；先输入有效正文、按钮可用后再量「主按钮品牌填充」
        host.input(RiskVisualFixtures.input)
        bar(MeTags.APPEAL_SUBMIT, surface = true, primary = true)
    }

    @Test fun progressLayoutHasNoBottomButton() {
        host.launchAppeal(risk = "risk_appealing_banned", appeals = "appeals_processing_banned")
        assertFalse(host.has(MeTags.APPEAL_SUBMIT) || host.has(MeTags.APPEAL_AGAIN))
        assertTrue("只有底部句的联系客服链接", host.has(MeTags.APPEAL_CS))
    }

    @Test fun liftedLayoutHasPrimaryThenLinkWithoutWhiteBar() {
        // 测试 v2 T2：按 fixture 画板 AppealResultLifted-v2 带 10006 进入标记
        host.launchAppeal(risk = "risk_normal", appeals = "appeals_revoked_banned", entered10006 = true)
        bar(MeTags.APPEAL_ACTION_PREFIX + "start", surface = false, primary = true)
        assertFalse("第二个动作不是按钮", buttonFilled(MeTags.APPEAL_ACTION_PREFIX + "view_messages"))
    }

    @Test fun liftedFrozenLayoutHasOnlyGoWallet() {
        host.launchAppeal(risk = "risk_normal", appeals = "appeals_revoked_frozen")
        bar(MeTags.APPEAL_ACTION_PREFIX + "go_wallet", surface = false, primary = true)
        assertFalse(host.has(MeTags.APPEAL_ACTION_PREFIX + "view_messages"))
    }

    @Test fun liftedHistoryLayoutStillRestrictedOffersContactCsAsPrimary() {
        host.launchAppeal(risk = "risk_banned", appeals = "appeals_revoked_banned")
        assertEquals("历史版（仍受限）标题只按本次 risk.state", 1, host.count(t("appeal.lifted.title.history")))
        bar(MeTags.APPEAL_ACTION_PREFIX + "contact_cs", surface = false, primary = true)
    }

    @Test fun upheldLayoutHasASecondaryButtonInTheWhiteBar() {
        host.launchAppeal(appeals = "appeals_upheld_banned")
        bar(MeTags.APPEAL_AGAIN, surface = true, primary = false)
    }

    // ---------------------------------------------------------------- 辅助

    private enum class Tone { ERROR, WARNING, PENDING }

    private fun tone(
        risk: String,
        text: String,
        tone: Tone,
        entered10006: Boolean? = null,
    ) {
        host.launchAppeal(risk = risk, entered10006 = entered10006)
        assertTrue("提交版式", host.has(MeTags.APPEAL_INPUT))
        val node = host.pageTexts(text).first()
        val px = host.pixels(page)
        val (bg, fg) =
            when (tone) {
                Tone.ERROR -> host.palette.errorBackground to host.palette.errorText
                Tone.WARNING -> host.palette.warningBackground to host.palette.warningText
                Tone.PENDING -> host.palette.pendingBackground to host.palette.pendingText
            }
        val pill = assertBox("胶囊 $tone", px.tintAround(node, bg))
        assertColor("胶囊字色 $tone", fg, node.color())
        assertEquals("图标：${if (tone == Tone.PENDING) "无" else "三角"}", if (tone == Tone.PENDING) 0 else 1, page.iconsIn(pill, 12f, 16f).size)
        others(bg).forEach { assertNull("其余色调不出现", px.tintAround(node, it, 4f, 4f)) }
    }

    private fun others(bg: Color): List<Color> =
        listOf(host.palette.errorBackground, host.palette.warningBackground, host.palette.pendingBackground).filter { it != bg }

    private fun bar(
        tag: String,
        surface: Boolean,
        primary: Boolean,
    ) {
        val button = host.node(tag).box()
        val px = host.pixels(page)
        val d = page.density()
        val beside = px.at(4f * d, button.center.y)
        assertEquals("白底固定栏：$surface", surface, sameColor(beside, host.palette.surface, 2))
        assertEquals("主按钮（品牌填充）：$primary", primary, buttonFilled(tag))
    }

    private fun buttonFilled(tag: String): Boolean {
        val box: Rect = host.node(tag).box()
        return host.pixels(page).countNear(box, host.palette.buttonPrimary, 4) > 0
    }
}
