package com.couli.feature.product

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliAnnouncer
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.ErrorStateFocus
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.designsystem.ProductDetailState
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PageApi
import com.couli.core.network.ReplayMode
import com.couli.generated.DefaultTexts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 第 1 轮评审（android-review-r1.md）的详情页测试，整页 [ProductDetailScreen] + 本地假服务：
 * - R1-08 入账说明「确认收货后随联盟月度结算入账」：有返利时显示，入账开关明确关闭、无返利时不显示（BR-TEXT-04、F-PROD-05）；
 * - R1-09 加载失败：立即播报（带 trace6），读完后焦点到【重试】；下架只播报；
 * - 共性第 12 条：键里没有 {trace6} 的错误（30141）也带追踪编号。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class ProductDetailScreenReviewR1Test {
    @get:Rule
    val rule = createComposeRule()

    private val spoken = mutableListOf<String>()

    private fun show(
        answer: ApiResult,
        credit: Boolean? = null,
    ) {
        val config =
            ProductPageConfig(
                api = OneAnswerApi(answer),
                texts = ScreenTexts,
                platform = "taobao",
                productKey = "tb:1",
                itemRef = null,
                context = { ctx() },
                creditEnabled = { credit },
            )
        rule.setContent {
            CompositionLocalProvider(LocalCouliAnnouncer provides CouliAnnouncer { spoken += it }) {
                CouliTheme { ProductDetailScreen(config) }
            }
        }
    }

    @Test
    fun r1_08_creditNoteRules() {
        val normal = obj(card("normal", 269))
        assertTrue("开关拿不到（契约未下发）时显示", CreditNote.visible(normal, null))
        assertTrue(CreditNote.visible(normal, true))
        assertFalse("credit.enabled=off 时不显示", CreditNote.visible(normal, false))
        assertTrue("比价区间", CreditNote.visible(obj(card("price_compare_risk", 112)), null))
        assertTrue("金额未知（金额为空）", CreditNote.visible(obj(card("amount_unknown", null)), null))
        assertFalse("无返利", CreditNote.visible(obj(card("no_rebate", 0)), null))
        assertFalse("返利确定为 0", CreditNote.visible(obj(card("normal", 0)), null))
    }

    @Test
    fun r1_08_detailShowsTheCreditNote() {
        show(success(card("normal", 269)))
        rule.waitForIdle()
        rule.onNodeWithText(CREDIT_NOTE).performScrollTo()
        rule.onNodeWithText(CREDIT_NOTE).assertExists()
    }

    @Test
    fun r1_08_creditSwitchOffHidesTheCreditNote() {
        show(success(card("normal", 269)), credit = false)
        rule.waitForIdle()
        rule.onNodeWithTag(ProductTags.DISCLAIMER).assertExists()
        rule.onNodeWithText(CREDIT_NOTE).assertDoesNotExist()
    }

    @Test
    fun r1_09_loadFailureIsAnnouncedThenRetryGetsFocus() {
        rule.mainClock.autoAdvance = false
        show(failure(50401))
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag(ProductTags.RETRY).assertIsNotFocused()
        rule.mainClock.advanceTimeBy(ErrorStateFocus.MAX_MILLIS)
        rule.onNodeWithTag(ProductTags.RETRY).assertIsFocused()
        assertEquals(listOf("出了点问题，请稍后再试（000123）"), spoken)
    }

    @Test
    fun r1_09_offShelfIsAnnouncedWithTrace6() {
        rule.mainClock.autoAdvance = false
        show(failure(30141))
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(ErrorStateFocus.MAX_MILLIS)
        assertEquals(listOf("商品已下架（000123）"), spoken)
        rule.onNodeWithTag(ProductTags.RETRY).assertDoesNotExist()
    }

    // ---- 本地假服务

    private class OneAnswerApi(
        private val answer: ApiResult,
    ) : PageApi {
        override suspend fun send(call: ApiCall): ApiResult = answer

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs(): CallEpochs = CallEpochs(0, 0)
    }

    private object ScreenTexts : ProductTexts {
        private val drafts =
            mapOf(
                "platform.taobao" to "淘宝",
                "external_page.retry" to "重试",
                CreditNote.TEXT_KEY to CREDIT_NOTE,
            )
        private val placeholder = Regex("\\{[a-z0-9_]+\\}")

        override fun text(key: String): String = DefaultTexts.text(key) ?: drafts[key] ?: DefaultTexts.fallback(key) ?: ""

        override fun format(
            key: String,
            vars: Map<String, String>,
        ): String {
            var text = DefaultTexts.text(key) ?: drafts[key] ?: return text(key)
            vars.forEach { (name, value) -> if (value.isNotEmpty()) text = text.replace("{$name}", value) }
            return if (placeholder.containsMatchIn(text)) DefaultTexts.fallback(key) ?: text.replace(placeholder, "") else text
        }
    }

    private companion object {
        const val TRACE = "0199a3b4-5c6d-7e8f-9a0b-1c2d3e000123"
        const val CREDIT_NOTE = "确认收货后随联盟月度结算入账"

        fun ctx(): JsonObject =
            JsonObject(mapOf("loggedIn" to JsonPrimitive(true), "privacy" to JsonPrimitive(ProductDetailState.PRIVACY_CONSENTED)))

        fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

        fun success(data: String): ApiResult = ApiResult.Success(Json.parseToJsonElement(data), TRACE, null)

        fun failure(code: Int): ApiResult {
            val effect = ErrorEffect(code, ErrorEffectKind.GENERIC_ERROR, ReplayMode.NONE, registerPending = false, disableMillis = null)
            return ApiResult.Failure(code, "", JsonObject(emptyMap()), TRACE, effect, null)
        }

        fun card(
            basis: String,
            rebate: Long?,
        ): String {
            val r = rebate?.toString() ?: "null"
            val keys = if (basis == "no_rebate") """["price_basis"]""" else """["price_basis","rebate_estimate"]"""
            return """{"product_key":"tb:1","platform":"taobao","title":"t","price_fen":3990,"coupon_fen":1000,"final_price_fen":2990,""" +
                """"est_net_price_fen":null,"rebate_min_fen":$r,"rebate_max_fen":$r,"rebate_basis":"$basis","is_presale":false,""" +
                """"cta":{"text_key":"btn.buy.coupon"},"stale":false,"age_sec":12,"quoted_at":"2026-10-02T09:30:00+08:00",""" +
                """"disclaimer_keys":$keys,"availability":"ok"}"""
        }
    }
}
