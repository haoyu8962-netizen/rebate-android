package com.couli.core.designsystem

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** r1 S2-1：兼容的非空旧 icon 参数不能绕过缺图只显示文字的规则。 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class Fx05aPlatformLegacyIconTest(
    private val code: String,
    private val name: String,
    private val dark: Boolean,
) {
    @get:Rule val rule = createComposeRule()

    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun missingPlatformWithLegacyIconMatchesTheTextOnlyBadgeAtTheSamePosition() {
        val withLegacyIcon = mutableStateOf(false)
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                PlatformBadge(
                    name = name,
                    platformCode = code,
                    icon = if (withLegacyIcon.value) CouliIcons.image else null,
                    modifier = Modifier.testTag("badge"),
                )
            }
        }
        val badge = rule.onNodeWithTag("badge", useUnmergedTree = true)
        val referencePosition = badge.fetchSemanticsNode().boundsInRoot.topLeft
        val reference = rule.fx05aPixels("badge", temporary.root)
        rule.runOnIdle { withLegacyIcon.value = true }
        rule.waitForIdle()
        badge.assertContentDescriptionEquals(name)
        assertEquals("同一绝对位置截图，避免圆角抗锯齿差异", referencePosition, badge.fetchSemanticsNode().boundsInRoot.topLeft)
        assertFx05aPixelsEqual("code=$code, dark=$dark：非空旧 icon 仍只显示文字", reference, rule.fx05aPixels("badge", temporary.root))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "code={0}, name={1}, dark={2}")
        fun parameters(): List<Array<Any>> =
            listOf(
                "tmall" to "天猫",
                // 空码 / 未知码故意配已知名称，不能靠名称回退到淘宝图标。
                "" to "淘宝",
                "future_shop" to "淘宝",
            ).flatMap { (code, name) -> listOf(false, true).map { dark -> arrayOf(code, name, dark) } }
    }
}
