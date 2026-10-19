package com.couli.core.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** §3.3 / §3.7 / G6：一个可读元素、双向切换、禁用不回调；名称、角色、状态独立断言。 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class Ds02SelectionTest(
    private val component: String,
    private val dark: Boolean,
    private val initiallyChecked: Boolean,
    private val enabled: Boolean,
) {
    @get:Rule val rule = createComposeRule()

    @Test
    fun semanticsTouchTargetAndControlledState() {
        var checked by mutableStateOf(initiallyChecked)
        val changes = mutableListOf<Boolean>()
        val label = "我已阅读并同意用户协议和隐私政策"
        val role = if (component == "CouliSwitch") Role.Switch else Role.Checkbox
        val roleMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                val change: (Boolean) -> Unit = {
                    changes += it
                    checked = it
                }
                when (component) {
                    "CouliCheckbox" -> {
                        CouliCheckbox(
                            checked = checked,
                            onCheckedChange = change,
                            contentDescription = label,
                            enabled = enabled,
                            modifier = Modifier.testTag("control"),
                        )
                    }

                    "AgreementCheckRow" -> {
                        AgreementCheckRow(
                            checked = checked,
                            onCheckedChange = change,
                            text = AnnotatedString(label),
                            enabled = enabled,
                            modifier = Modifier.testTag("control"),
                        )
                    }

                    "CouliSwitch" -> {
                        CouliSwitch(
                            checked = checked,
                            onCheckedChange = change,
                            label = label,
                            enabled = enabled,
                            modifier = Modifier.testTag("control"),
                        )
                    }
                }
            }
        }
        val node = assertAccessibleControl(roleMatcher, label)
        if (initiallyChecked) node.assertIsOn() else node.assertIsOff()
        if (enabled) {
            node.assertIsEnabled().performClick()
            if (initiallyChecked) node.assertIsOff() else node.assertIsOn()
            node.performTouchInput { click() }
            if (initiallyChecked) node.assertIsOn() else node.assertIsOff()
            rule.runOnIdle { assertEquals(listOf(!initiallyChecked, initiallyChecked), changes) }
        } else {
            node.assertIsNotEnabled().performTouchInput { click() }
            rule.runOnIdle { assertEquals(emptyList<Boolean>(), changes) }
            if (initiallyChecked) node.assertIsOn() else node.assertIsOff()
        }
        // 外部确认框同意后更新受控值，组件不能缓存旧值。
        rule.runOnIdle { checked = !initiallyChecked }
        if (initiallyChecked) node.assertIsOff() else node.assertIsOn()
    }

    private fun assertAccessibleControl(
        roleMatcher: SemanticsMatcher,
        label: String,
    ): SemanticsNodeInteraction {
        rule.onAllNodes(roleMatcher).assertCountEquals(1)
        val node = rule.onNode(roleMatcher)
        node.assert(
            SemanticsMatcher("整句读屏名称") { semantics ->
                val descriptions = semantics.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                val texts = semantics.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
                label in descriptions + texts
            },
        )
        if (component != "AgreementCheckRow") node.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        return node
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}/dark={1}/checked={2}/enabled={3}")
        fun parameters(): List<Array<Any>> =
            listOf("CouliCheckbox", "AgreementCheckRow", "CouliSwitch").flatMap { name ->
                listOf(false, true).flatMap { dark ->
                    listOf(false, true).flatMap { checked ->
                        listOf(false, true).map { enabled -> arrayOf(name, dark, checked, enabled) }
                    }
                }
            }
    }
}
