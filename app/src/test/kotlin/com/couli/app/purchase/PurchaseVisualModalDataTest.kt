package com.couli.app.purchase

import android.app.Application
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 资金页数据红线：浮层只用既有价格/提示，不向加载层或确认框塞商品详情、账号或推算返利。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
class PurchaseVisualModalDataTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PurchaseVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun priceModalOnlyUsesResponseAmounts() {
        host.launch()
        host.buy()
        host.answer("price")
        val text = modalText()
        assertTrue(text.contains("¥46.78") && text.contains("¥49.9"))
        assertFalse(text.contains("1.23") || text.contains("2.34") || text.contains("预估返后"))
        assertNoProductData(text)
        host.counts(open = 1)
    }

    @Test fun noticeDoesNotExposeAccountOrProductDataAndKeepsTrace6() {
        host.launch()
        host.buy()
        host.fail(50303)
        val text = modalText()
        assertTrue("错误追踪后六位保留", text.contains("000321"))
        assertFalse(text.contains("¥"))
        assertNoProductData(text)
        host.counts(open = 1)
    }

    private fun assertNoProductData(text: String) {
        assertFalse(text.contains("纯牛奶") || text.contains("乳品生活") || text.contains("visual-link"))
        assertFalse(text.contains("fixture-state") || text.contains("fixture-code"))
    }

    private fun modalText(): String =
        nodes(rule.onNode(isDialog(), useUnmergedTree = true).fetchSemanticsNode())
            .flatMap {
                it.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .map { text -> text.text }
            }.joinToString(" ")

    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
}
