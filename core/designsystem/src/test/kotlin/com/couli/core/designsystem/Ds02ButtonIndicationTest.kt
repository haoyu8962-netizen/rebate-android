package com.couli.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** 同时接受 M3 Button 和 clickable + 系统 indication；不强迫某种按钮布局。 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36])
class Ds02ButtonIndicationTest(
    private val variant: CouliButtonVariant,
) {
    @get:Rule val rule = createComposeRule()

    @Test
    fun clickableOwnsAnIndicationAndPressReleaseClicksOnce() {
        var clicks = 0
        rule.setContent {
            CouliTheme { CouliButton(text = "继续", onClick = { clicks++ }, variant = variant, modifier = Modifier.testTag("button")) }
        }
        val button = rule.onNodeWithTag("button")
        assertTrue("按钮缺少 ripple/indication", hasIndication("button"))
        button.performTouchInput { down(center) }
        rule.runOnIdle { assertEquals(0, clicks) }
        button.performTouchInput { up() }
        rule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun inspectionAcceptsRealMaterialButton() {
        rule.setContent {
            CouliTheme {
                Box(Modifier.testTag("reference")) {
                    Button(onClick = {}) { Text("继续") }
                }
            }
        }
        assertTrue("合法 M3 系统 ripple 不能误拒", hasIndication("reference"))
    }

    @Test
    fun inspectionRejectsExplicitlyMissingIndication() {
        rule.setContent {
            CouliTheme {
                Box(
                    Modifier.size(48.dp).testTag("bad-reference").clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
                )
            }
        }
        assertFalse("indication=null 必须被识别", hasIndication("bad-reference"))
    }

    @Test
    fun iconButtonAlsoRetainsIndication() {
        rule.setContent {
            CouliTheme {
                CouliIconButton(
                    icon = CouliIcons.close,
                    label = "关闭",
                    onClick = {},
                    modifier = Modifier.testTag("icon"),
                )
            }
        }
        assertTrue(rule.onNodeWithTag("icon").ds02HasIndication())
    }

    private fun hasIndication(tag: String): Boolean = rule.onNodeWithTag(tag).ds02HasIndication()

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = CouliButtonVariant.entries.map { arrayOf(it) }
    }
}
