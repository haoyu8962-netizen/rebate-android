package com.couli.core.designsystem

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** 独立圆钮的授权态不能依赖 ThirdPartyLoginRow 在外层传 enabled=false。 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36])
class Ds02OAuthButtonTest(
    private val provider: OAuthProvider,
    private val dark: Boolean,
) {
    @get:Rule val rule = createComposeRule()

    @Test
    fun authorizingAloneBlocksTouchAndExposesDisabledSemantics() {
        var calls = 0
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                OAuthButton(provider = provider, label = "登录", onClick = { calls++ }, authorizing = true)
            }
        }
        val button = rule.onNodeWithTag("oauth-button/${provider.name}")
        // 不传 enabled，默认 true；先真实触摸验证零回调，再验证读屏禁用语义。
        button.performTouchInput { click() }
        rule.runOnIdle { assertEquals("独立 $provider 授权中不能重复触发登录", 0, calls) }
        button.assertIsNotEnabled()
    }

    @Test
    fun ordinaryButtonRemainsEnabledAndCallsOnce() {
        var calls = 0
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                OAuthButton(provider = provider, label = "登录", onClick = { calls++ })
            }
        }
        rule.onNodeWithTag("oauth-button/${provider.name}").assertIsEnabled().performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, calls) }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "provider={0}/dark={1}")
        fun parameters(): List<Array<Any>> =
            listOf(OAuthProvider.HUAWEI, OAuthProvider.WECHAT).flatMap { provider ->
                listOf(false, true).map { arrayOf(provider, it) }
            }
    }
}
