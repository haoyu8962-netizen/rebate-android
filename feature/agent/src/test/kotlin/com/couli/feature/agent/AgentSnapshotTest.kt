package com.couli.feature.agent

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.agent.AgentController
import com.couli.core.agent.AgentPhase
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 界面快照（G32 ②，brief §2 第 14 条；APP-11 第 15 条：直接用不可改的 fixture 逐个驱动，fixture 与截图一一对应）：锁定契约的 15 个流 fixture 经真实控制器、
 * StreamClient、Reducer 与渲染器，每个 fixture 一张（文件名带 fixture 名）；另加向量里的构造形态（login_required、grid、relaxed、spec_unconfirmed、price_compare_risk、
 * off_shelf、coupon_gone，标「构造」）、错误 / 额度 / 同意 / 未开放、金额隐藏、200% 字号、320 宽。
 * 宽 390dp（与设计稿同宽）；大屏另见 [AgentLargeScreenSnapshotTest]（窗口真为 840dp）。基准图在 src/test/snapshots
 * （recordRoborazziDebug 录制，verifyRoborazziDebug 比对），另拷到 NATIVE/APP-10/shots/android/。数据全是契约 fixture 与合成数据。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentSnapshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var frame by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var contentSet = false

    private fun render(
        kit: AgentUiKit,
        controller: AgentController,
        widthDp: Int = 390,
        fontScale: Float = 1f,
    ) {
        if (!contentSet) {
            contentSet = true
            composeRule.setContent { frame?.invoke() }
        }
        composeRule.runOnIdle {
            frame = { key(controller.serial) { Frame(kit, controller, widthDp, fontScale) } }
        }
        idle()
    }

    @Composable
    private fun Frame(
        kit: AgentUiKit,
        controller: AgentController,
        widthDp: Int,
        fontScale: Float,
    ) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale),
            LocalProductImageLoader provides AgentUiKit.images,
        ) {
            CouliTheme {
                Box(modifier = Modifier.width(widthDp.dp).fillMaxHeight().background(CouliColors.backgroundCanvas)) {
                    AgentScreen(controller, kit.ui())
                }
            }
        }
    }

    private fun idle() {
        repeat(ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
        }
    }

    private fun shot(name: String) = composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")

    /** 一个 fixture 一张图：名字 = 截图名（7 类 + duplicate，G04）。 */
    private fun fixtureShot(
        name: String,
        fixture: String,
        text: String = "帮我找纯牛奶，24 盒装",
        after: (AgentUiKit) -> Unit = {},
    ) {
        val kit = AgentUiKit()
        val c = composeRule.runOnIdle { kit.controller() }
        composeRule.runOnIdle {
            kit.play(c, fixture, text, end = fixture != "disconnected")
            after(kit)
        }
        render(kit, c)
        shot(name)
    }

    @Test
    fun everyContractFixtureHasOneSnapshot() {
        fixtureShot("01-normal", "normal")
        fixtureShot("02-tool-failed", "tool-failed")
        fixtureShot("03-cancelled", "cancelled")
        // 最后一行后挂起：30 秒没有任何一行 → 连接中断 +【重试】
        fixtureShot("04-disconnected", "disconnected") { it.advance(30_000) }
        fixtureShot("05-unknown-card", "unknown-card", "返利什么时候到账")
        fixtureShot("06-unknown-card-page-guide", "unknown-card-page-guide", "设置在哪里")
        fixtureShot("07-error", "error")
        fixtureShot("08-error-search-fallback", "error-search-fallback")
        fixtureShot("09-fallback", "fallback")
        fixtureShot("10-normal-page-guide", "normal-page-guide", "提现进度在哪看")
        fixtureShot("11-normal-earnings", "normal-earnings", "我赚了多少")
        fixtureShot("13-duplicate-ended-done", "duplicate-ended-done")
        fixtureShot("13b-duplicate-ended-error", "duplicate-ended-error")
        fixtureShot("14-duplicate-in-progress", "duplicate-in-progress")
    }

    @Test
    fun historyEarningsShapeShowsNoAmounts() {
        // normal-earnings-history 只有一行 card 帧：包成一轮流（meta + card + done），按 SSE 来源「恰好合历史形状 → 历史渲染」（G13，鸿蒙 H12）
        val kit = AgentUiKit()
        val c = composeRule.runOnIdle { kit.controller() }
        val meta = AgentUiKit.fixtureLines("normal-earnings").first()
        val done = """{"event":"done","id":9,"data":{"finish_reason":"stop","quota_left":20}}"""
        composeRule.runOnIdle { kit.playLines(c, listOf(meta) + AgentUiKit.fixtureLines("normal-earnings-history") + done, "之前查的收益") }
        render(kit, c)
        shot("12-normal-earnings-history")
    }

    @Test
    fun constructedProductShapes() {
        listOf(
            "15-login-required" to "login_required",
            "16-grid" to "grid",
            "17-relaxed" to "relaxed",
            "18-spec-unconfirmed" to "spec_unconfirmed",
            "19-price-compare-risk" to "price_compare_risk",
            "20-off-shelf" to "off_shelf",
            "20b-coupon-gone" to "coupon_gone",
        ).forEach { (name, shape) ->
            val kit = AgentUiKit()
            val c = composeRule.runOnIdle { kit.controller() }
            composeRule.runOnIdle { kit.playLines(c, AgentUiKit.constructed(shape)) }
            render(kit, c)
            shot(name)
        }
    }

    @Test
    fun errorsQuotaUnavailableAndEmpty() {
        val quotaKit = AgentUiKit().apply { identity = identity.copy(level = com.couli.core.agent.AgentIdentity.GUEST, account = null) }
        val quota = composeRule.runOnIdle { quotaKit.controller() }
        composeRule.runOnIdle { quotaKit.json(quota, 30502) }
        render(quotaKit, quota)
        shot("21-json-30502-quota")

        val timeoutKit = AgentUiKit()
        val timeout = composeRule.runOnIdle { timeoutKit.controller() }
        composeRule.runOnIdle { timeoutKit.json(timeout, 50401, status = 504) }
        render(timeoutKit, timeout)
        shot("22-json-50401")

        val unavailableKit = AgentUiKit()
        val unavailable = composeRule.runOnIdle { unavailableKit.controller() }
        composeRule.runOnIdle { unavailableKit.json(unavailable, 30501) }
        render(unavailableKit, unavailable)
        assertEquals(AgentPhase.UNAVAILABLE, unavailable.currentPhase)
        shot("23-json-30501-unavailable")

        val emptyKit = AgentUiKit()
        val empty = composeRule.runOnIdle { emptyKit.controller() }
        render(emptyKit, empty)
        shot("26-empty")

        // Release：没有端口（G01）
        val releaseKit = AgentUiKit()
        val release = composeRule.runOnIdle { releaseKit.controller(contract = null) }
        render(releaseKit, release)
        shot("29-release-unavailable")

        // 生成中：工具进行中、已收到部分卡片，显示【停止】
        val streamingKit = AgentUiKit()
        val streaming = composeRule.runOnIdle { streamingKit.controller() }
        composeRule.runOnIdle { streamingKit.play(streaming, "normal", upTo = 7, end = false) }
        render(streamingKit, streaming)
        shot("32-streaming")
    }

    @Test
    fun consentSheetDefaultUncheckedThenChecked() {
        val kit2 = AgentUiKit()
        val c2 = composeRule.runOnIdle { kit2.controller() }
        composeRule.runOnIdle {
            check(c2.send(c2.snapshot.value.page, "帮我找纯牛奶"))
            val s = kit2.port.streams.last()
            s.sink.onHeaders(403, mapOf("Content-Type" to "application/json"))
            s.sink.onData("""{"code":10004,"msg":"m","data":{"consent_type":"ai_third_party"},"trace_id":"t"}""")
            s.sink.onEnd()
        }
        render(kit2, c2)
        captureScreenRoboImage("src/test/snapshots/24-consent.png")
        composeRule.onNodeWithTag(AgentTags.CONSENT_CHECK).performClick()
        idle()
        captureScreenRoboImage("src/test/snapshots/24b-consent-checked.png")
        composeRule.runOnIdle { c2.consentDecline(c2.snapshot.value.consent!!) }
        idle()
        shot("31-consent-declined")
    }

    @Test
    fun maskedFontAndWidth() {
        val maskedKit = AgentUiKit()
        val masked = composeRule.runOnIdle { maskedKit.controller() }
        composeRule.runOnIdle {
            maskedKit.play(masked, "normal-earnings", "我赚了多少")
            maskedKit.mask.value = true
        }
        render(maskedKit, masked)
        shot("25-earnings-masked")

        val fontKit = AgentUiKit()
        val font = composeRule.runOnIdle { fontKit.controller() }
        composeRule.runOnIdle { fontKit.play(font, "normal") }
        render(fontKit, font, fontScale = 2f)
        shot("27-font200")

        val narrowKit = AgentUiKit()
        val narrow = composeRule.runOnIdle { narrowKit.controller() }
        composeRule.runOnIdle { narrowKit.play(narrow, "normal-earnings", "我赚了多少") }
        render(narrowKit, narrow, widthDp = 320)
        shot("28-width320")
        render(narrowKit, narrow, widthDp = 320, fontScale = 2f)
        shot("28b-width320-font200")
        // 大屏 30-large 在 AgentLargeScreenSnapshotTest：类级窗口是 390dp，在这里传 840 会被父窗口约束成 390（R3-01）
    }

    private companion object {
        const val ROUNDS = 4
    }
}
