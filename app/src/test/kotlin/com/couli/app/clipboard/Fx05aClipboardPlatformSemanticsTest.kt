package com.couli.app.clipboard

import android.app.Application
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.couli.core.clipboard.ClipboardPromptBar
import com.couli.core.clipboard.ClipboardUi
import com.couli.core.designsystem.CouliTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** r1 S2-2：使用真实剪贴板父级合并语义，不能用测试自造的 Row 代替集成场景。 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class Fx05aClipboardPlatformSemanticsTest(
    private val name: String,
    private val dark: Boolean,
) {
    @get:Rule val rule = createComposeRule()

    @Test
    fun clipboardPromptKeepsItsMergedAnnouncementAndHidesTheBadgeTextChild() {
        val label = "检测到${name}商品链接，查返利"
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                ClipboardPromptBar(
                    message = "检测到商品链接",
                    detail = "${name}商品链接 example.invalid",
                    badge = name,
                    messageLabel = label,
                    actionText = "查返利",
                    actionLabel = "查返利",
                    closeLabel = "关闭提示",
                    onAction = {},
                    onClose = {},
                )
            }
        }
        val message = rule.onNodeWithTag(ClipboardUi.PROMPT_MESSAGE)
        message.assertIsDisplayed().assert(hasContentDescription(label))
        message.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertTrue("提示文字区仍合并成父级读屏单元", message.fetchSemanticsNode().config.isMergingSemanticsOfDescendants)
        rule.onAllNodes(hasContentDescription(label)).assertCountEquals(1)
        // 未合并树必须检查文字本身的隐藏标记；父级存在描述 / 合并并不等于子文字已隐藏。
        rule
            .onNodeWithText(name, useUnmergedTree = true)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "name={0}, dark={1}")
        fun parameters(): List<Array<Any>> =
            listOf("淘宝", "天猫", "未知平台").flatMap { name ->
                listOf(false, true).map { dark -> arrayOf(name, dark) }
            }
    }
}
