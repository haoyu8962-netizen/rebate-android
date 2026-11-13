package com.couli.feature.orders

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTheme
import com.couli.core.orders.OrderDraftTexts
import com.couli.core.orders.OrderNumberView
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 缺陷 #1 组件级：列表卡与详情共用的 [OrderNumberInline]（订单号 + 【复制】）。字段断言（[NumWrap.assertField]）：组件容器之下完整订单号恰好 1 处、
 * 整段不断、captionAmount 令牌、不缩字、首尾可见；【复制】按可视窗口判断（[NumCopy]）：一行、可见、不压在可见号码上、命中区 ≥48dp。
 * 列宽取真实页面三档：列表卡文字列（约 160dp）、w320 详情整列（约 240dp）、w390 整列（约 300dp）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumWrapOrderNumberComponentTest {
    @get:Rule val rule = createComposeRule()

    @Test fun narrowColumn19DigitsLight() = case(NO_19, 160, dark = false)

    @Test fun narrowColumn24DigitsLight() = case(NO_24, 160, dark = false)

    @Test fun w320Column19DigitsLight() = case(NO_19, 240, dark = false)

    @Test fun w320Column24DigitsDark() = case(NO_24, 240, dark = true)

    @Test fun w390Column19DigitsLight() = case(NO_19, 300, dark = false)

    @Test fun w390Column19DigitsDark() = case(NO_19, 300, dark = true)

    @Test fun w390Column24DigitsDark() = case(NO_24, 300, dark = true)

    /** 分享单脱敏号（不可复制）同样整段不断。 */
    @Test fun maskedShareNumberNarrowColumn() = case(MASKED, 160, dark = false, copyable = false)

    private fun case(
        no: String,
        widthDp: Int,
        dark: Boolean,
        copyable: Boolean = true,
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, FONT_SCALE)) {
                CouliTheme(darkTheme = dark) {
                    Column(Modifier.width(widthDp.dp).background(CouliColors.backgroundSurface).testTag(BOX)) {
                        OrderNumberInline(
                            number = OrderNumberView(no, copyable = copyable, valid = true),
                            texts = OrderDraftTexts,
                            onCopy = {},
                            textTag = TEXT_TAG,
                            copyTag = COPY_TAG,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val where = "OrderNumberInline（列宽 ${widthDp}dp，${no.length} 位，${if (dark) "深" else "浅"}）"
        val scope = NumWrap.within(BOX)
        NumWrap.assertField(rule, NumField(no, where, scope, NumToken(CouliTextStyles.captionAmount.fontSize, FONT_SCALE)))
        if (copyable) NumCopy.assertCopyClear(rule, NumWrap.nodesFor(rule, no, scope), no, COPY_TAG, where)
    }

    private companion object {
        const val FONT_SCALE = 2f
        const val TEXT_TAG = "numwrap_order_no"
        const val COPY_TAG = "numwrap_order_copy"
        const val BOX = "numwrap_order_box"
        const val NO_19 = "3741234567890123456"
        const val NO_24 = "374123456789012345678901"
        const val MASKED = "3741****3456"
    }
}
