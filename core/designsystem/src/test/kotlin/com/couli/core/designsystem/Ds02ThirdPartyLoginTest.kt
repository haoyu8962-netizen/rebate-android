package com.couli.core.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class Ds02ThirdPartyLoginTest(
    private val huawei: Boolean,
    private val wechat: Boolean,
) {
    @get:Rule val rule = createComposeRule()
    private val texts = ThirdPartyLoginTexts("微信登录", "华为账号登录", "登录遇到问题", "其他登录方式", "上次登录")
    private val expected get() =
        buildList {
            if (huawei) add(OAuthProvider.HUAWEI)
            if (wechat) add(OAuthProvider.WECHAT)
        }

    @Test
    fun availabilityOrderCircleSizeExpandedTargetsRippleAndCallbacks() {
        val selected = mutableListOf<OAuthProvider>()
        var helps = 0
        rule.setContent {
            CouliTheme { ThirdPartyLoginRow(wechat, huawei, texts, { selected += it }, { helps++ }) }
        }
        val targets =
            expected.map { provider ->
                val node = rule.onNodeWithTag("oauth-button/${provider.name}")
                node
                    .assertWidthIsAtLeast(64.dp)
                    .assertHeightIsAtLeast(48.dp)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    .assert(accessibleName(label(provider)))
                assertTrue("圆钮缺少 ripple：$provider", node.ds02HasIndication())
                node.performClick()
                node.fetchSemanticsNode().boundsInRoot
            }
        val circles =
            expected.map { provider ->
                val node = rule.onNodeWithTag("oauth-circle/${provider.name}", useUnmergedTree = true)
                node.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
                node.fetchSemanticsNode().boundsInRoot
            }
        if (targets.size == 2) {
            assertTrue("命中区重叠或顺序反了", targets[0].right <= targets[1].left)
            assertEquals(32f, with(rule.density) { (circles[1].left - circles[0].right).toDp().value }, 0.5f)
        }
        if (!wechat) rule.onNodeWithTag("oauth-button/WECHAT").assertDoesNotExist()
        if (!huawei) rule.onNodeWithTag("oauth-button/HUAWEI").assertDoesNotExist()
        rule.onNodeWithTag("oauth-button/APPLE").assertDoesNotExist()
        if (expected.isEmpty()) rule.onNodeWithTag("oauth-divider").assertDoesNotExist()
        rule
            .onNode(hasText(texts.helpLabel) or hasContentDescription(texts.helpLabel))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        rule.runOnIdle {
            assertEquals(expected, selected)
            assertEquals(1, helps)
        }
    }

    @Test
    fun authorizingAloneDisablesEveryProviderAndAnnouncesProgress() {
        // 单独开启授权态；不能让 enabled=false 掩盖漏处理 authorizing 的实现。
        var calls = 0
        rule.setContent {
            CouliTheme { ThirdPartyLoginRow(true, true, texts, { calls++ }, {}, enabled = true, authorizing = OAuthProvider.WECHAT) }
        }
        listOf("HUAWEI", "WECHAT").forEach {
            rule.onNodeWithTag("oauth-button/$it").assertIsNotEnabled().performTouchInput { click() }
        }
        rule.onNodeWithTag("oauth-button/WECHAT").assert(
            SemanticsMatcher("授权中可读状态或加载语义") { node ->
                node.config.contains(SemanticsProperties.ProgressBarRangeInfo) ||
                    node.config.getOrElse(SemanticsProperties.StateDescription) { "" }.contains("授权中")
            },
        )
        rule.runOnIdle { assertEquals(0, calls) }
    }

    @Test
    fun disabledAloneDoesNotCallLogin() {
        var calls = 0
        rule.setContent {
            CouliTheme { ThirdPartyLoginRow(wechat, huawei, texts, { calls++ }, {}, enabled = false, authorizing = null) }
        }
        expected.forEach { rule.onNodeWithTag("oauth-button/${it.name}").assertIsNotEnabled().performTouchInput { click() } }
        rule.runOnIdle { assertEquals(0, calls) }
    }

    @Test
    fun lastLoginBelongsOnlyToTheChosenProviderAndItsAccessibleName() {
        var last by androidx.compose.runtime.mutableStateOf<OAuthProvider?>(null)
        rule.setContent { CouliTheme { ThirdPartyLoginRow(wechat, huawei, texts, {}, {}, lastLogin = last) } }
        // 包含隐藏平台：不能把提示错误地挂到仍可见的另一颗钮上。
        listOf(null, OAuthProvider.HUAWEI, OAuthProvider.WECHAT).forEach { provider ->
            rule.runOnIdle { last = provider }
            expected.forEach { visible ->
                val node = rule.onNodeWithTag("oauth-button/${visible.name}")
                node.assert(accessibleName(label(visible)))
                node.assert(
                    SemanticsMatcher("上次登录只属于 $provider") { semantics ->
                        val name =
                            semantics.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString() +
                                semantics.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }
                        name.contains(texts.lastLoginLabel) == (provider == visible)
                    },
                )
                val badge = rule.onNodeWithTag("oauth-last-login/${visible.name}", useUnmergedTree = true)
                if (provider == visible) badge.assertExists() else badge.assertDoesNotExist()
            }
        }
    }

    private fun label(provider: OAuthProvider) = if (provider == OAuthProvider.WECHAT) texts.wechatLabel else texts.huaweiLabel

    private fun accessibleName(label: String) =
        SemanticsMatcher("读屏名包含 $label") { node ->
            (
                node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } +
                    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
            ).any { label in it }
        }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "huawei={0}/wechat={1}")
        fun parameters(): List<Array<Any>> = listOf(false, true).flatMap { h -> listOf(false, true).map { w -> arrayOf(h, w) } }
    }
}
