package com.couli.feature.agent

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.agent.AgentController
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.LocalProductImageLoader
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Agent 页（测试 v2，Codex 复审 S1 ⑤⑧）：
 * - 判据 1：找回草稿卡（AgentCards.kt:628 InfoCard 正文行「订单号 {order_no}」，footnote）在 w320 / w390 × 200% 下订单号整段不断（只改卡内数字行，
 *   不改 Agent 整体布局）。
 * - FU-AND-ADAPT-CHECK ②：真机高度测量布置（w320 / w390 × h640 / h800 × 200%，normal 流），量消息区（agent_list）占页面高度的比例，
 *   打印并写到 feature/agent/build/numwrap/agent-ratio.txt，只记录、不判定（由编排再判）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumWrapAgentTest {
    @get:Rule val rule = createComposeRule()
    private val kit = AgentUiKit()

    @Test
    @Config(qualifiers = "w320dp-h1400dp-xhdpi")
    fun claimCardOrderNoW320Font200() = claimCase("w320")

    @Test
    @Config(qualifiers = "w390dp-h1400dp-xhdpi")
    fun claimCardOrderNoW390Font200() = claimCase("w390")

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun measureMessageAreaW320H640() = measure("w320-h640")

    @Test
    @Config(qualifiers = "w320dp-h800dp-xhdpi")
    fun measureMessageAreaW320H800() = measure("w320-h800")

    @Test
    @Config(qualifiers = "w390dp-h640dp-xhdpi")
    fun measureMessageAreaW390H640() = measure("w390-h640")

    @Test
    @Config(qualifiers = "w390dp-h800dp-xhdpi")
    fun measureMessageAreaW390H800() = measure("w390-h800")

    private fun claimCase(width: String) {
        val c = rule.runOnIdle { kit.controller() }
        show(c)
        val meta = AgentUiKit.fixtureLines("normal").first()
        val claim =
            """{"event":"card","id":2,"data":{"seq":2,"card_id":"c1","type":"claim_draft","schema_version":1,""" +
                """"data":{"platform":"jd","order_no":"$ORDER_NO",""" +
                """"click_times":["2026-10-06T10:00:00+08:00"],"evidence_summary":null,""" +
                """"submit_action":{"text_key":"agent.rule.not_found","action":"submit_claim"}},""" +
                """"fallback_text":"当前版本暂不支持显示这张卡片，请升级后查看。"}}"""
        val done = """{"event":"done","id":3,"data":{"finish_reason":"stop","quota_left":1}}"""
        rule.runOnIdle { kit.playLines(c, listOf(meta, claim, done), "订单找不到了") }
        idle()
        rule.onNodeWithTag(AgentTags.card("c1"), useUnmergedTree = true).performScrollTo()
        val field = NumField(ORDER_NO, "Agent 找回草稿卡订单号（$width×200%）", NumWrap.within(AgentTags.card("c1")), TOKEN)
        NumWrap.assertField(rule, field)
    }

    private fun measure(size: String) {
        val c = rule.runOnIdle { kit.controller() }
        show(c)
        rule.runOnIdle { kit.play(c, "normal") }
        idle()
        val page = rule.onNodeWithTag(AgentTags.PAGE, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val list = rule.onNodeWithTag(AgentTags.LIST, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("页面与消息区都要量到", page.height > 0f && list.height > 0f)
        val d = rule.density.density
        val ratio = list.height / page.height
        val top = ((list.top - page.top) / d).toInt()
        val bottom = ((page.bottom - list.bottom) / d).toInt()
        val line =
            "agent-ratio $size font200 page=${(page.height / d).toInt()}dp list=${(list.height / d).toInt()}dp " +
                "ratio=${"%.3f".format(ratio)} top=${top}dp bottom=${bottom}dp"
        println(line)
        val out = File(root(), "feature/agent/build/numwrap/agent-ratio.txt")
        out.parentFile.mkdirs()
        out.appendText(line + "\n")
    }

    private fun show(c: AgentController) {
        rule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, FONT_SCALE),
                LocalProductImageLoader provides AgentUiKit.images,
            ) {
                CouliTheme {
                    Box(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) { AgentScreen(c, kit.ui()) }
                }
            }
        }
        idle()
    }

    private fun idle() {
        repeat(ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.waitForIdle()
        }
    }

    private fun root(): File {
        val start = File(System.getProperty("user.dir") ?: ".").canonicalFile
        return generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
    }

    private companion object {
        const val FONT_SCALE = 2f
        const val ROUNDS = 4
        const val ORDER_NO = "374123456789012345678901"
        val TOKEN = NumToken(CouliTypography.footnote.fontSize, FONT_SCALE)
    }
}
