package com.couli.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class Ds02AgreementHitTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun invisible48DpExtensionTogglesWithoutMakingTheSentence48DpTall() {
        var checked by mutableStateOf(false)
        var calls = 0
        val label = "同意用户协议"
        rule.setContent {
            CouliTheme {
                Box(Modifier.testTag("host").padding(32.dp)) {
                    AgreementCheckRow(checked, {
                        checked = it
                        calls++
                    }, AnnotatedString(label), Modifier.testTag("agreement"))
                }
            }
        }
        val row = rule.onNodeWithTag("agreement").fetchSemanticsNode().boundsInRoot
        val text = rule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val circle = rule.onNodeWithTag("agreement-circle", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val tolerance = with(rule.density) { 4.dp.toPx() }
        assertTrue("不得靠整行 48dp 高实现命中区", row.height <= text.height + tolerance)
        val host = rule.onNodeWithTag("host")
        val hostBounds = host.fetchSemanticsNode().boundsInRoot
        val dy = with(rule.density) { 20.dp.toPx() }
        val target = Offset(circle.center.x, circle.center.y - dy)
        assertTrue("测试点必须在可见圆之外", target.y < circle.top)
        // 在圆心 48dp 方形扩展区内，离中心 20dp；用父层注入真实触摸，不调用语义点击作弊。
        host.performTouchInput { click(target - hostBounds.topLeft) }
        rule.onNodeWithTag("agreement").assertIsOn()
        rule.runOnIdle { assertEquals(1, calls) }
    }
}
