package com.couli.core.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w320dp-h914dp-xxhdpi", fontScale = 2f)
class Ds02AgreementLinksTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun threeInlineLinksWrapAsOneSentenceAndDoNotToggleConsent() {
        val opened = mutableListOf<String>()
        val toggles = mutableListOf<Boolean>()
        val offsets = mutableListOf<Int>()
        val text =
            buildAnnotatedString {
                append("我已阅读并同意")
                listOf("用户协议", "隐私政策", "运营商认证协议").forEach { label ->
                    offsets += length
                    withLink(LinkAnnotation.Clickable(label) { opened += label }) { append(label) }
                    append(" ")
                }
                append("未注册的手机号验证后将自动注册凑狸账号")
            }
        rule.setContent {
            CouliTheme {
                AgreementCheckRow(checked = false, onCheckedChange = { toggles += it }, text = text)
            }
        }
        val sentence = rule.onNodeWithText(text.text, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        sentence.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            org.junit.Assert.assertTrue(action(layouts))
        }
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        assertFalse("200% 不能截断协议句子", layout.hasVisualOverflow)
        val semantics = rule.onAllNodes(SemanticsMatcher("all") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        listOf("用户协议", "隐私政策", "运营商认证协议").forEach { label ->
            org.junit.Assert.assertTrue(
                "读屏不能独立访问链接：$label",
                semantics.any { node ->
                    val name =
                        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text } +
                            node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString()
                    (name == label && node.config.contains(SemanticsActions.OnClick)) ||
                        node.config.getOrElse(SemanticsActions.CustomActions) { emptyList() }.any { it.label == label }
                },
            )
        }
        offsets.forEach { offset ->
            val box = layout.getBoundingBox(offset)
            sentence.performTouchInput { click(Offset(box.center.x, box.center.y)) }
        }
        rule.runOnIdle {
            assertEquals(listOf("用户协议", "隐私政策", "运营商认证协议"), opened)
            assertEquals(emptyList<Boolean>(), toggles)
        }
    }
}
