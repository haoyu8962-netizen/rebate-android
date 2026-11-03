package com.couli.app.me

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.couli.feature.me.MeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * FU-AND-0729 ①（APP-12v / 12w 三端口径对齐，编排 11-02 14:45、11-03 13:39）的量具：设置主页导航行 / 动作行视觉高 52（画板 min-height 44 + 上下 4，
 * content-box）、相邻行节距 53（52 + 1px 分隔线）；隐私中心清单行、协议半屏行仍是 Android 命中区 48（画板 border-box 44，不跟改）。
 *
 * 行框用合并语义节点的未裁切外框（positionInRoot + size，= 点击 / 读屏所在那层，即 heightIn 之后、padding 之前的整行），不量被滚动容器裁过的可见区，
 * 也不量 padding 之后的内容盒。行内容高 = 行下全部文字节点排版盒高与 chevron 20 中的最大者。
 */
internal object Fu0729Rows {
    /** 设置主页行高（画板 Settings-v2 实量 52，iOS tests-a-ios-v2 §2 同口径）。 */
    const val ROW = 52f

    /** 相邻导航行节距 = 行高 + 1px 分隔线。 */
    const val PITCH = 53f

    /** Android 命中区（隐私中心清单四行、协议半屏两行保持现值，不跟改）。 */
    const val HIT = 48f

    /** 设置一族导航行上下内边距（content-box 写法：行高 = max(52, 内容 + 8)）。 */
    const val PAD = 4f

    const val CHEVRON = 20f

    /** 账号卡（公开版 4 行）= 卡内上 4 + 52 × 4 + 3 条分隔线 + 卡内下 4。 */
    const val ACCOUNT_CARD = 219f

    /** 已登录公开版的全部导航 / 动作行（剪贴板开关行是 app 模块的 ClipboardSettingsRow，不在本口径）。 */
    val member: List<String> = SettingsRows.account + SettingsRows.privacy + SettingsRows.general - SettingsRows.CLIPBOARD

    /** 内测版：没有注销行。 */
    val beta: List<String> = member - SettingsRows.DELETE

    val guest: List<String> = SettingsRows.guest - SettingsRows.CLIPBOARD

    val basic: List<String> = SettingsRows.basic

    /** 同一张卡里上下相邻、两行都是设置导航行的对（中间只隔 1px 分隔线）。 */
    val memberPairs: List<Pair<String, String>> =
        listOf(
            SettingsRows.account,
            SettingsRows.privacy,
            listOf(SettingsRows.NOTIFY, SettingsRows.RESET_TIP),
            listOf(SettingsRows.CLEAR_CACHE, SettingsRows.HELP, SettingsRows.ABOUT),
        ).flatMap { it.zipWithNext() }

    fun tag(key: String): String = MeTags.SETTINGS_ROW_PREFIX + key

    /** 合并语义节点的未裁切外框（不受滚动视口裁切）。 */
    fun frame(node: SemanticsNodeInteraction): Rect {
        val semantics = node.fetchSemanticsNode()
        val origin = semantics.positionInRoot
        return Rect(origin.x, origin.y, origin.x + semantics.size.width, origin.y + semantics.size.height)
    }

    /** [tag] 之下全部文字节点排版盒的最大高度（px）。 */
    fun textHeight(
        rule: ComposeContentTestRule,
        tag: String,
    ): Float {
        val boxes = textBoxes(rule, tag)
        assertTrue("$tag 之下没有文字节点", boxes.isNotEmpty())
        return boxes.maxOf { it.height }
    }

    private fun textBoxes(
        rule: ComposeContentTestRule,
        tag: String,
    ): List<Rect> {
        val texts = rule.onAllNodes(TEXT and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
        return (0 until texts.fetchSemanticsNodes().size).map { texts[it].textBox() }
    }

    private val TEXT = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)

    /** [tag] 之下文字节点的纵向跨度（最上文字上沿 → 最下文字下沿，px）。 */
    fun textSpan(
        rule: ComposeContentTestRule,
        tag: String,
    ): Float {
        val boxes = textBoxes(rule, tag)
        assertTrue("$tag 之下没有文字节点", boxes.isNotEmpty())
        return boxes.maxOf { it.bottom } - boxes.minOf { it.top }
    }

    /** 设置主页每个导航 / 动作行视觉高 52 ± 0.5（100% 字号；量外框）。 */
    fun assertRows52(
        host: SettingsVisualTestHost,
        keys: List<String>,
        what: String,
    ) {
        keys.forEach { key ->
            val node = host.node(tag(key))
            val d = node.density()
            assertEquals("FU-AND-0729 ① $what：$key 行高 = 52（画板 min-height 44 + 上下 4；现为 48 即未跟改）", ROW, frame(node).height / d, 0.5f)
        }
    }
}
