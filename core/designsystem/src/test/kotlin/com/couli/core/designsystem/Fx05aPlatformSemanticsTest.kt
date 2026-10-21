package com.couli.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** V2 / V4：断言真实 Compose 合并与未合并语义树，不以测试 tag 或源码字符串冒充读屏。 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class Fx05aPlatformSemanticsTest(
    private val code: String?,
    private val name: String,
    private val dark: Boolean,
) {
    @get:Rule val rule = createComposeRule()

    @Test
    fun badgeAnnouncesOnlyOneChineseNameAndHasNoFocusableImage() {
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                PlatformBadge(name = name, platformCode = code, modifier = Modifier.testTag("badge"))
            }
        }
        rule.onNodeWithTag("badge").assertIsDisplayed().assertContentDescriptionEquals(name)
        rule.onNodeWithTag("badge").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
        val hasDescription = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)
        rule.onAllNodes(hasDescription).assertCountEquals(1)
        rule.onAllNodes(hasDescription, useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused), useUnmergedTree = true).assertCountEquals(0)
        assertBadgeTextIsHiddenFromAccessibility()
    }

    @Test
    fun inlineTitleKeepsItsCombinedDescriptionAndHidesTheBadgeTextChild() {
        val title = "读屏回归商品标题"
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                BadgedTitle(platformName = name, title = title, platformCode = code)
            }
        }
        rule.onNodeWithTag(TITLE_ELLIPSIS_TAG).assertIsDisplayed().assertContentDescriptionEquals("$name $title")
        assertBadgeTextIsHiddenFromAccessibility()
    }

    @Test
    fun publicIconIsDecorativeInBothSizesAndWithARemoteAddress() {
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                Column {
                    listOf(PlatformIconSize.BADGE, PlatformIconSize.HOME).forEach { size ->
                        PlatformIcon(
                            platformCode = code ?: "taobao",
                            size = size,
                            remoteUrl = "https://unused.invalid/official.png",
                        )
                    }
                }
            }
        }
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true).assertCountEquals(0)
    }

    private fun assertBadgeTextIsHiddenFromAccessibility() {
        // Text 仍可见，但不能成为第二个读屏文本；不以 Focused / 描述数量代替隐藏语义。
        rule
            .onNodeWithText(name, useUnmergedTree = true)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "code={0}, name={1}, dark={2}")
        fun parameters(): List<Array<Any?>> =
            listOf(
                "taobao" to "淘宝",
                "jd" to "京东",
                "pdd" to "拼多多",
                "tmall" to "天猫",
                "wechat" to "微信",
                "future_shop" to "future_shop",
                "" to "未知平台",
                null to "淘宝",
                null to "京东",
                null to "拼多多",
            ).flatMap { (code, name) -> listOf(false, true).map { dark -> arrayOf(code, name, dark) } }
    }
}
