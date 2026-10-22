package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.orders.OrderDraftTexts
import com.couli.feature.orders.OrderTags
import kotlinx.serialization.json.JsonPrimitive
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
class OrderDetailVisualReasonTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderDetailVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun pendingRefundKeepsOriginalDescription() = pendingReason("REFUND")

    @Test fun pendingRightsKeepsOriginalDescription() = pendingReason("RIGHTS")

    // 合法字典说明含「提交」；不能再以整张状态卡禁词误伤它（r1 S2-1 / T2）。
    @Test fun pendingRelationInvalidKeepsLegalSubmissionWording() = pendingReason("RELATION_INVALID")

    private fun pendingReason(reason: String) {
        host.launch(
            OrderDetailVisualFixtures.change(
                OrderDetailVisualFixtures.detail("detail_appeal_pending"),
                "reason" to JsonPrimitive(reason),
            ),
        )
        rule.onNodeWithTag(OrderTags.DETAIL_APPEAL).assertExists()
        assertReasonTexts("order_detail.why_no_rebate", "order_reason.$reason.title", "order_reason.$reason.desc")
        assertEquals(1, host.api.calls.size)
    }

    /** OrderDetailAppealPending-v2.dc.html:32–36 / 同名 PNG：INVALID + BLACKLIST + 申诉中只留原因标题。 */
    @Test fun onlyIllustratedPendingInvalidBlacklistOmitsDescription() {
        host.launch(OrderDetailVisualFixtures.detail("detail_appeal_pending"))
        rule.onNodeWithTag(OrderTags.DETAIL_APPEAL).assertExists()
        assertReasonTexts("order_detail.why_no_rebate", "order_reason.BLACKLIST.title")
    }

    @Test fun blacklistWithoutPendingKeepsDescription() {
        host.launch(OrderDetailVisualFixtures.detail("detail_blacklist"))
        rule.onNodeWithTag(OrderTags.DETAIL_APPEAL).assertDoesNotExist()
        assertReasonTexts("order_detail.why_no_rebate", "order_reason.BLACKLIST.title", "order_reason.BLACKLIST.desc")
    }

    @Test fun pendingClawedBlacklistKeepsDescriptionAndDeduction() {
        host.launch(
            OrderDetailVisualFixtures.change(
                OrderDetailVisualFixtures.detail("detail_clawed_back"),
                "reason" to JsonPrimitive("BLACKLIST"),
                "appeal_pending" to JsonPrimitive(true),
            ),
        )
        rule.onNodeWithTag(OrderTags.DETAIL_APPEAL).assertExists()
        assertEquals(
            listOf(
                OrderDraftTexts.text("order_reason.BLACKLIST.title"),
                listOf("order_reason.BLACKLIST.desc", "order_reason.clawed_back_extra")
                    .joinToString(OrderDraftTexts.text("order_detail.desc_join")) { OrderDraftTexts.text(it) },
            ),
            reasonTexts(),
        )
    }

    /** G-O03 / r1 S2-4：②缺 order_reason_sub，只显示标题，不能代拟说明。 */
    @Test fun invalidPunishOnlyShowsHeadingAndTitleWithoutSubreasonDescription() {
        host.launch(OrderDetailVisualFixtures.detail("detail_reason_02"))
        assertReasonTexts("order_detail.why_no_rebate", "order_reason.PUNISH.title")
    }

    /** G-O03 / r1 S2-4：⑧同样不能代拟说明，但扣回状态的既有说明必须保留。 */
    @Test fun clawedPunishKeepsDeductionButOmitsSubreasonDescription() {
        host.launch(OrderDetailVisualFixtures.detail("detail_reason_08"))
        assertReasonTexts("order_reason.PUNISH.title", "order_reason.clawed_back_extra")
    }

    private fun assertReasonTexts(vararg keys: String) {
        assertEquals("原因区真实绘制文本必须精确匹配字典；不增造缺字段说明", keys.map { OrderDraftTexts.text(it) }, reasonTexts())
    }

    private fun reasonTexts() = rule.detailDrawn(OrderTags.DETAIL_REASON).map { it.result.layoutInput.text.text }
}
