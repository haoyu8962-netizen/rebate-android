package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.orders.OrderDraftTexts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderDetailVisualDictionaryTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderDetailVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun statusReasonHintAndActionAllUseInjectedDictionary() {
        host.texts =
            object : com.couli.core.orders.OrderTexts {
                override fun text(key: String): String = if (key in keys) "DICT:$key" else OrderDraftTexts.text(key)

                override fun format(
                    key: String,
                    vars: Map<String, String>,
                ): String =
                    if (key in
                        keys
                    ) {
                        "DICT:$key"
                    } else {
                        OrderDraftTexts.format(key, vars)
                    }
            }
        host.launch(OrderDetailVisualFixtures.detail("detail_blacklist"))
        keys.forEach { rule.onNodeWithText("DICT:$it", useUnmergedTree = true).assertExists() }
    }

    @Test fun refreshMustReadNewDraftKey() {
        assertEquals("刷新", OrderDraftTexts.text("order_detail.refresh"))
        host.texts =
            object : com.couli.core.orders.OrderTexts {
                override fun text(key: String): String = if (key == "order_detail.refresh") "RELOAD-DICT" else OrderDraftTexts.text(key)

                override fun format(
                    key: String,
                    vars: Map<String, String>,
                ): String = OrderDraftTexts.format(key, vars)
            }
        host.launch(OrderDetailVisualFixtures.detail("detail_unknown"))
        rule.onNodeWithText("RELOAD-DICT").assertExists()
    }

    private val keys =
        setOf(
            "order_status.INVALID.label",
            "order_detail.why_no_rebate",
            "order_reason.BLACKLIST.title",
            "order_reason.BLACKLIST.desc",
            "order_action.appeal",
        )
}
