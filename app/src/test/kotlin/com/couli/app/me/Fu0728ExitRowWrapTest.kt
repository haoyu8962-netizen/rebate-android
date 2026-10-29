package com.couli.app.me

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.AccountTags
import com.couli.feature.me.MeTags
import kotlin.math.abs
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
 * FU-AND-0728 U9（F-AND-75 ②，APP-12w-c 评审 r1 S2-3）：出口行「联系客服 | 注销账号 | 退出登录」在 200% 下折行时，分隔符「|」不得挂在行尾
 * （与其后的链接同组不分离，或折行时不画行尾分隔符）；去向、顺序不变（既有用例守），分隔符仍不进读屏。
 *
 * 量法（不依赖实现结构）：在出口行纵向范围内、三个链接命中盒之外，找 border.subtle 色的竖向墨迹（≥ 6dp）——即画出来的「|」；
 * 每根「|」右侧 24dp 内必须有一个与它同一行（纵向重叠）的链接盒。链接盒用标记（RISK_CS / RISK_DELETE / RISK_LOGOUT，申诉维持页 APPEAL_*）。
 * 底版：200% × w320 / w390 封禁页（「联系客服 | 注销账号 |」换行「退出登录」）红；申诉维持页 200% × w320 推算同样红（同文案、同 RiskExitRow）；
 * 100% 对照与读屏条款绿。修正后全绿。变异：把「| 链接」组拆回独立子项（或恢复行尾分隔符）→ 红。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w320dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Fu0728ExitRowWrapTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { RiskVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    @Test fun bannedAt200PercentW320NoSeparatorHangsAtLineEnd() {
        host.launchRisk(fontScale = 2f, public = true)
        assertNoHangingSeparator(RISK_LINKS, wrapped = true)
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun bannedAt200PercentW390NoSeparatorHangsAtLineEnd() {
        host.launchRisk(fontScale = 2f, public = true)
        assertNoHangingSeparator(RISK_LINKS, wrapped = true)
    }

    @Test fun upheldAt200PercentW320NoSeparatorHangsAtLineEnd() {
        host.launchAppeal(appeals = "appeals_upheld_banned", fontScale = 2f, public = true)
        assertNoHangingSeparator(APPEAL_LINKS, wrapped = true)
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun controlAt100PercentOneLineWithTwoSeparators() {
        host.launchRisk(public = true)
        val separators = assertNoHangingSeparator(RISK_LINKS, wrapped = false)
        assertEquals("100% 一行：两根分隔符都画出", 2, separators)
    }

    @Test fun separatorsStayOutOfTheReader() {
        host.launchRisk(fontScale = 2f, public = true)
        val merged =
            rule
                .onAllNodes(SemanticsMatcher("任意节点") { true }, useUnmergedTree = false)
                .fetchSemanticsNodes()
        val spoken = merged.flatMap { it.spokenTexts() }
        assertTrue("先正：链接读出", spoken.any { it.contains(AppMeTexts.text("risk.contact_cs")) })
        assertFalse("分隔符「|」不读：$spoken", spoken.any { it.trim() == "|" })
    }

    // ---------------------------------------------------------------- 辅助

    /** 返回找到的分隔符根数。[wrapped] 为真时先正：三个链接确实不在同一行（真的折行了）。 */
    private fun assertNoHangingSeparator(
        tags: List<String>,
        wrapped: Boolean,
    ): Int {
        val nodes = tags.map { host.node(it) }
        val links = nodes.map { it.box() }
        val d = nodes.first().density()
        if (wrapped) {
            assertEquals("先正：真 200%", 2f, nodes.first().fontScale(), 0.01f)
            assertTrue("先正：出口行折成多行：$links", links.maxOf { it.top } >= links.minOf { it.bottom } - 1f)
        }
        val image = host.pixels(nodes.first())
        val separators = separators(image, links, d)
        assertTrue("先正：至少画出一根分隔符（border.subtle 竖向墨迹）", separators.isNotEmpty())
        separators.forEach { sep ->
            val follower =
                links.firstOrNull { link ->
                    link.left >= sep.right - 1f &&
                        link.left - sep.right <= FOLLOW_DP * d &&
                        link.top < sep.bottom &&
                        link.bottom > sep.top
                }
            assertTrue(
                "分隔符不得挂在行尾：$sep 右侧 ${FOLLOW_DP}dp 内同一行没有链接（链接盒 $links）",
                follower != null,
            )
        }
        return separators.size
    }

    /** 出口行纵向范围内、链接盒之外 border.subtle 色的竖向墨迹列，相邻列归成一根「|」。 */
    private fun separators(
        image: Bitmap,
        links: List<Rect>,
        d: Float,
    ): List<Rect> {
        val top = links.minOf { it.top }.toInt().coerceAtLeast(0)
        val bottom = links.maxOf { it.bottom }.toInt().coerceAtMost(image.height)
        val minRun = (MIN_RUN_DP * d).toInt()
        val groups = mutableListOf<Rect>()
        for (x in 0 until image.width) {
            val run = longestRun(image, links, x, top until bottom)?.takeIf { it.last + 1 - it.first >= minRun } ?: continue
            val last = groups.lastOrNull()
            groups +=
                if (last != null && x - last.right <= 1f) {
                    groups.removeAt(groups.lastIndex)
                    Rect(last.left, minOf(last.top, run.first.toFloat()), x + 1f, maxOf(last.bottom, run.last + 1f))
                } else {
                    Rect(x.toFloat(), run.first.toFloat(), x + 1f, run.last + 1f)
                }
        }
        return groups
    }

    /** 第 [x] 列在 [rows] 内、链接盒之外最长一段 border.subtle 像素（含两端的行号区间）；没有为 null。 */
    private fun longestRun(
        image: Bitmap,
        links: List<Rect>,
        x: Int,
        rows: IntRange,
    ): IntRange? {
        val subtle = host.palette.subtle.toArgb()
        val marks = rows.map { y -> links.none { it.contains(Offset(x + .5f, y + .5f)) } && near(image.getPixel(x, y), subtle) }
        var best: IntRange? = null
        var start = -1
        marks.forEachIndexed { i, ink ->
            if (ink && start < 0) start = i
            val closes = start >= 0 && (!ink || i == marks.lastIndex)
            if (closes) {
                val end = if (ink) i else i - 1
                val run = (rows.first + start)..(rows.first + end)
                if (best == null || run.count() > best!!.count()) best = run
                start = -1
            }
        }
        return best
    }

    private fun near(
        a: Int,
        b: Int,
    ): Boolean = listOf(16, 8, 0).all { abs((a ushr it and 255) - (b ushr it and 255)) <= SUBTLE_TOLERANCE }

    private fun SemanticsNode.spokenTexts(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private companion object {
        val RISK_LINKS = listOf(MeTags.RISK_CS, AccountTags.RISK_DELETE, MeTags.RISK_LOGOUT)
        val APPEAL_LINKS = listOf(MeTags.APPEAL_CS, AccountTags.APPEAL_DELETE, MeTags.APPEAL_LOGOUT)
        const val FOLLOW_DP = 24
        const val MIN_RUN_DP = 6f
        const val SUBTLE_TOLERANCE = 16
    }
}
