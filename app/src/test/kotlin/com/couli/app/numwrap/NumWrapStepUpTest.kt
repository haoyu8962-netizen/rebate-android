package com.couli.app.numwrap

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.wallet.visual.StepUpVisualKit
import com.couli.app.wallet.visual.WithdrawVisualFixtures
import com.couli.app.wallet.visual.WithdrawVisualTestHost
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliTypography
import com.couli.feature.wallet.WalletTags
import java.io.File
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
 * 二次验证半屏（core/wallet StepUpSheet.kt:181 CodeStatusRow，提现 / 收款账号 / 注销 / 换号共用）：
 * - FU-AND-ADAPT-CHECK ①：发码失败后的可点【重新获取】在 200% 下要像按钮（只看入口节点自身）。
 * - D10-1223 口径（v7.1）：「N 秒后可重新获取」也属于按钮——200% 下倒计时态同样要有按钮外观，不可点时用**禁用按钮**表示
 *   （Role.Button 带 Disabled）。默认字号的倒计时仍是非可点次文字（StepUpVisualRowTest:50 钉住，不变）。
 * - brief §2.5 ③ 回归：StepUpVisualSnapshotTest 的三张 font200 截图必须把 200% 传给宿主页（kit.open(fontScale = 2f)）；修正后的布置下宿主页
 *   文字确实按 200% 排版。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumWrapStepUpTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }
    private val kit by lazy { StepUpVisualKit(rule, host) }

    @After fun close() = host.close()

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi", fontScale = 2f)
    fun resendReadyW320Font200Light() = resend(dark = false)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi", fontScale = 2f)
    fun resendReadyW390Font200Dark() = resend(dark = true)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi", fontScale = 2f)
    fun countdownW320Font200Light() = countdown(dark = false)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi", fontScale = 2f)
    fun countdownW390Font200Dark() = countdown(dark = true)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi", fontScale = 2f)
    fun fixedArrangementRendersTheHostPageAt200Percent() {
        kit.open(fontScale = 2f)
        val amount = NumWrap.nodesFor(rule, CouliFormat.money(WITHDRAWABLE_FEN), NumWrap.within(WalletTags.WITHDRAW_BALANCE))
        val layout = NumWrap.layout(amount)
        assertEquals("宿主页（提现页头余额）按 200% 排版", 2f, layout.layoutInput.density.fontScale, 0.001f)
    }

    @Test fun snapshotFont200ShotsPass200PercentToTheHostPage() {
        val start = File(System.getProperty("user.dir") ?: ".").canonicalFile
        val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        val source = File(root, "app/src/test/kotlin/com/couli/app/wallet/visual/StepUpVisualSnapshotTest.kt").readText()
        assertTrue("shot() 要把字号传给 kit.open（现为默认 1.0，宿主页按 1.0 渲染）", Regex("""kit\.open\([^)]*fontScale""").containsMatchIn(source))
        listOf("font200W390", "font200W320", "font200Short").forEach { name ->
            val match = Regex("""fun $name\(\) = shot\(([^\n]*)""").find(source)
            val call = match?.groupValues?.get(1).orEmpty()
            assertTrue("$name 的 shot(...) 要传 fontScale = 2f（与 @Config fontScale 一致）", call.contains("fontScale = 2f"))
        }
    }

    private fun resend(dark: Boolean) {
        host.api.smsAnswers += WithdrawVisualFixtures.failure()
        kit.open(dark = dark, fontScale = 2f)
        val where = "二次验证半屏【重新获取】（200%，${if (dark) "深" else "浅"}）"
        val tagged = rule.onNodeWithTag(WalletTags.STEP_UP_RESEND, useUnmergedTree = true).fetchSemanticsNode()
        SendCodeLook.assertLooksLikeButton(rule, tagged, where)
        val text = rule.onAllNodes(NumWrap.HAS_LAYOUT and NumWrap.within(WalletTags.STEP_UP_RESEND), useUnmergedTree = true)[0]
        val style = NumWrap.layout(text).layoutInput.style
        assertEquals("$where：不许缩字（footnote 令牌）", CouliTypography.footnote.fontSize, style.fontSize)
    }

    private fun countdown(dark: Boolean) {
        kit.open(dark = dark, fontScale = 2f)
        val where = "二次验证半屏倒计时「n 秒后可重新获取」（200%，${if (dark) "深" else "浅"}）"
        val tagged = rule.onNodeWithTag(WalletTags.STEP_UP_RESEND, useUnmergedTree = true).fetchSemanticsNode()
        val text = rule.onAllNodes(NumWrap.HAS_LAYOUT and NumWrap.within(WalletTags.STEP_UP_RESEND), useUnmergedTree = true)[0]
        val layout = NumWrap.layout(text)
        val shown = layout.layoutInput.text.text
        assertTrue("$where：入口应处在倒计时态（实际「$shown」）", shown.contains(COUNTDOWN_TAIL))
        SendCodeLook.assertDisabledButton(rule, tagged, where)
        assertEquals("$where：不许缩字（footnote 令牌）", CouliTypography.footnote.fontSize, layout.layoutInput.style.fontSize)
    }

    private companion object {
        /** login.code.countdown「{n} 秒后可重新获取」的固定尾巴。 */
        const val COUNTDOWN_TAIL = "秒后可重新获取"

        /** 默认规则（specs visualWithdraw.rules.normal）的可提现余额。 */
        private val RULES = WithdrawVisualFixtures.rules()
        val WITHDRAWABLE_FEN: Long = RULES.getValue("withdrawable_fen").jsonPrimitive.long
    }
}
