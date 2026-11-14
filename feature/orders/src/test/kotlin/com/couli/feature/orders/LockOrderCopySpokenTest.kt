package com.couli.feature.orders

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTheme
import com.couli.core.orders.OrderDraftTexts
import com.couli.core.orders.OrderNumberView
import com.couli.generated.DefaultTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * LOCK-android（D10-1609，评审 S1-1）：订单号旁【复制】的读屏名称取契约 order_list.copy_spoken「复制订单号 {no}」并填完整单号，
 * 不再是只有「复制」两个字的 order_list.copy_label（那是可见文字）。列表卡（[OrderNumberInline]）与详情行（[OrderNumberRow]）都钉住。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class LockOrderCopySpokenTest {
    @get:Rule val rule = createComposeRule()

    private val expected: String
        get() = requireNotNull(DefaultTexts.text("order_list.copy_spoken")) { "契约缺 order_list.copy_spoken" }.replace("{no}", NO)

    @Test fun contractSpokenTemplateCarriesTheOrderNumber() {
        assertTrue("模板带 {no}", DefaultTexts.text("order_list.copy_spoken").orEmpty().contains("{no}"))
        assertEquals(expected, orderCopySpoken(OrderDraftTexts, NO))
        assertTrue("读屏名称含完整单号", orderCopySpoken(OrderDraftTexts, NO).contains(NO))
    }

    @Test fun inlineCopyIsSpokenWithTheOrderNumber() {
        rule.setContent {
            CouliTheme {
                Column {
                    OrderNumberInline(
                        number = OrderNumberView(NO, copyable = true, valid = true),
                        texts = OrderDraftTexts,
                        onCopy = {},
                        textTag = TEXT_TAG,
                        copyTag = COPY_TAG,
                    )
                }
            }
        }
        rule.onNodeWithTag(COPY_TAG, useUnmergedTree = true).assert(spokenAs(expected))
    }

    @Test fun rowCopyIsSpokenWithTheOrderNumber() {
        rule.setContent {
            CouliTheme {
                Column {
                    OrderNumberRow(
                        number = OrderNumberView(NO, copyable = true, valid = true),
                        texts = OrderDraftTexts,
                        onCopy = {},
                        textTag = TEXT_TAG,
                        copyTag = COPY_TAG,
                    )
                }
            }
        }
        rule.onNodeWithTag(COPY_TAG, useUnmergedTree = true).assert(spokenAs(expected))
    }

    private fun spokenAs(text: String): SemanticsMatcher =
        SemanticsMatcher("contentDescription 含「$text」") { node ->
            node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { it == text }
        }

    private companion object {
        const val NO = "2345678901234567890"
        const val TEXT_TAG = "lock-copy-text"
        const val COPY_TAG = "lock-copy-button"
    }
}
