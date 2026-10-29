@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.hasText as textMatcher
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.toSize
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.login.BindPhoneTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-0728 U10（F-AND-77 ①②，APP-13v-b 评审 r2 R2-01 / R2-02）。
 *
 * ① 字段错误不改变让位档位，且要让「只去掉字段错误排除」的变异也红：既有 fieldErrorDoesNotChangeTheIntroTier 在 320×640 / 200% 下档位本来已收到底，
 * 错误行出现与否都不会再让位，守不住。这里改用「出错时可视区变小」：常规 390×844 / 100% 先确认顶部区全在上（档位 0），出 20002 错误行后注入合成导航条
 * 底部 inset（不改窗口尺寸与字号，所以不重置档位），使格下一行超出可视区——没有字段错误时这会让位（对照用例证明注入有效），有字段错误时必须不让位。
 * 底版：两条都绿（现行条件正确）；变异 M27′「measurable 去掉 codeError / phoneError 排除」→ codeError… 红。
 *
 * ② 键盘动画期间表单不随每帧重组：BindPhoneParts.imeOpen() 在组合期读 WindowInsets.ime 的底部值，键盘动画每帧 inset 一变，整个 BindPhoneBody 就重组。
 * 可观测量：Compose 运行时的组合追踪钩子（Composer.setTracer；编译器默认生成追踪标记，函数名形如 "com.couli.feature.login.BindPhoneBody (BindPhoneScreen.kt:…)"）
 * 数 BindPhoneBody 的重组次数。逐帧注入 IME 底部 inset 0 → 336dp 共 [STEPS] 帧，只许开 / 关切换时重组（≤ 2 次）。先正：输入改状态时能数到重组（钩子有效）。
 * 底版：红（每帧重组，约 [STEPS] 次）；删掉组合期读取或改成只在开关时变化的派生状态后绿。键盘首帧档位规则由既有 keyboardDoesNotChange… 两条守。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Fu0728BindPhoneTierTest : AccountVisualPhoneHarness() {
    @OptIn(InternalComposeTracingApi::class)
    @After
    fun resetTracer() = Composer.setTracer(null)

    /** 对照（先正）：没有字段错误时，可视区变小（合成导航条 inset）会让位——证明注入法能触发让位量取。 */
    @Test fun controlShrinkingTheViewportWithoutErrorsFoldsTheIntro() {
        enterBind(sent = true)
        settle()
        assertEquals("先正：常规机型全部在顶部区", Tier(TOP, TOP), tier())
        shrinkViewport()
        assertNotEquals("无字段错误：可视区变小后让位", Tier(TOP, TOP), tier())
    }

    @Test fun codeErrorKeepsTheTierEvenWhenTheViewportShrinks() {
        enterBind(sent = true)
        settle()
        assertEquals("先正：常规机型全部在顶部区", Tier(TOP, TOP), tier())
        type(BindPhoneTags.CODE, CODE_OK)
        enqueueCode("POST", BIND, 20002, "null")
        idleUntil("【绑定】可点") { enabled(BindPhoneTags.SUBMIT) }
        tap(BindPhoneTags.SUBMIT)
        idleUntil("验证码错误行") { has(BindPhoneTags.CODE_ERROR) }
        settle()
        assertEquals("先正：错误行出现后（可视区未变）仍全部在顶部区", Tier(TOP, TOP), tier())
        shrinkViewport()
        assertTrue("先正：错误行仍在", has(BindPhoneTags.CODE_ERROR))
        assertEquals("字段错误期间可视区变小也不让位（档位不随错误行变化）", Tier(TOP, TOP), tier())
    }

    @OptIn(InternalComposeTracingApi::class)
    @Test
    fun keyboardAnimationFramesDoNotRecomposeTheWholeForm() {
        enterBind(typed = true)
        settle()
        var bodies = 0
        Composer.setTracer(
            object : CompositionTracer {
                override fun isTraceInProgress(): Boolean = true

                override fun traceEventStart(
                    key: Int,
                    dirty1: Int,
                    dirty2: Int,
                    info: String,
                ) {
                    if (info.startsWith(BODY)) bodies += 1
                }

                override fun traceEventEnd() = Unit
            },
        )
        val number = phone("bind")
        type(BindPhoneTags.PHONE, number.dropLast(1))
        settle()
        type(BindPhoneTags.PHONE, number)
        settle()
        assertTrue("先正：重组计数可用（输入改状态 → BindPhoneBody 重组，计到 $bodies 次）", bodies >= 1)
        val before = bodies
        (1..STEPS).forEach { step ->
            inset(WindowInsetsCompat.Type.ime(), IME_DP * step / STEPS)
            composeRule.mainClock.advanceTimeByFrame()
        }
        settle()
        val during = bodies - before
        assertTrue("键盘动画 $STEPS 帧：BindPhoneBody 只许在开 / 关切换时重组（≤ $MAX_DURING 次），实际 $during 次", during <= MAX_DURING)
    }

    // ---------------------------------------------------------------- 辅助

    private data class Tier(
        val benefit: String,
        val hint: String,
    )

    /** 副标题 / 并号提示条各在哪：顶部区（手机号框之上）或卡片下方（格下一行之下）；同 AccountVisualPhoneAdaptationTest.introTier 的量法。 */
    private fun tier(): Tier {
        val phoneTop = unclipped(BindPhoneTags.PHONE).top
        val rowBottom = unclipped(BindPhoneTags.GET_CODE).bottom

        fun where(key: String): String {
            val nodes =
                composeRule
                    .onAllNodes(textMatcher(draftB(key)), useUnmergedTree = true)
                    .fetchSemanticsNodes()
            assertEquals("$key 恰一处（收起不删除）", 1, nodes.size)
            val r = Rect(nodes.single().positionInRoot, nodes.single().size.toSize())
            return when {
                r.bottom <= phoneTop + 1f -> TOP
                r.top >= rowBottom - 1f -> BELOW
                else -> "夹在表单中间：$r"
            }
        }
        return Tier(where("invite.bind_phone_guide.benefit"), where("invite.bind_phone_guide.merge_hint"))
    }

    /** 注入合成导航条底部 inset（不改窗口尺寸与字号）；先正：【绑定】随之上移。 */
    private fun shrinkViewport() {
        val before = unclipped(BindPhoneTags.SUBMIT).bottom
        inset(WindowInsetsCompat.Type.navigationBars(), SHRINK_DP)
        settle()
        val d = densityOf(BindPhoneTags.PAGE)
        assertTrue(
            "先正：导航条注入生效（【绑定】上移 ≥ ${SHRINK_DP * 0.8f}dp）",
            (before - unclipped(BindPhoneTags.SUBMIT).bottom) / d >= SHRINK_DP * 0.8f,
        )
    }

    /** 照 AccountVisualPhoneAdaptationTest.imeInset / ProductVisualTestHost.navigationInset 的写法注入底部 inset。 */
    private fun inset(
        type: Int,
        bottomDp: Int,
    ) {
        val node = composeRule.onRoot().fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        val pixels = (bottomDp * node.layoutInfo.density.density).toInt()
        val insets =
            WindowInsetsCompat
                .Builder()
                .setInsets(type, Insets.of(0, 0, 0, pixels))
                .setVisible(type, bottomDp > 0)
                .build()
        composeRule.runOnUiThread { view.rootView.dispatchApplyWindowInsets(checkNotNull(insets.toWindowInsets())) }
        composeRule.waitForIdle()
    }

    private fun unclipped(tag: String): Rect {
        val n = node(tag).fetchSemanticsNode()
        return Rect(n.positionInRoot, n.size.toSize())
    }

    private companion object {
        const val TOP = "顶部区"
        const val BELOW = "卡片下方"
        const val SHRINK_DP = 440
        const val IME_DP = 336
        const val STEPS = 10
        const val MAX_DURING = 2
        const val BODY = "com.couli.feature.login.BindPhoneBody ("
    }
}
