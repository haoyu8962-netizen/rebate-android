package com.couli.app.numwrap

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.orders.OrderDetailVisualFixtures
import com.couli.app.orders.OrderDetailVisualTestHost
import com.couli.app.orders.OrderListVisualFixtures
import com.couli.app.orders.OrderListVisualTestHost
import com.couli.core.designsystem.CouliTextStyles
import com.couli.feature.orders.OrderTags
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 缺陷 #1（QA-11 Android）真实页面接线：订单列表卡与订单详情共用的「订单号 + 复制」行（OrderListVisualTestHost / OrderDetailVisualTestHost，
 * 真实 Screen / ViewModel / JSON 解析，只换订单号压力值）。
 * - 字段断言：在订单号字段（列表 `orders_no_STRESS`、详情 `order_detail_no`；实现若拆开「订单号」标签与号码，这个 tag 要挂在含号码的节点或其外层）
 *   之下完整订单号恰好 1 处、整段不断、captionAmount 令牌、
 *   不缩字、首尾可见（横向滚动须真能滚到）。
 * - 【复制】按可视窗口判断：一行、在可视范围内、不压在可见号码上、命中区 ≥48dp；不再要求「紧跟号码末端 ≤16dp」（旧断言列入 #24）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumWrapOrdersPageTest {
    @get:Rule val rule = createComposeRule()
    private var list: OrderListVisualTestHost? = null
    private var detail: OrderDetailVisualTestHost? = null

    @After fun close() {
        list?.close()
        detail?.close()
    }

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun listW320Font200Light19Digits() = listCase(NO_19, dark = false)

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun listW320Font200Light24Digits() = listCase(NO_24, dark = false)

    @Test fun listW390Font200Light19Digits() = listCase(NO_19, dark = false)

    @Test fun listW390Font200Dark19Digits() = listCase(NO_19, dark = true)

    @Test fun listW390Font200Dark24Digits() = listCase(NO_24, dark = true)

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun listW320Font200WholePageScan() {
        launchList(NO_24, dark = false)
        assertTrue("全页扫描至少要扫到订单号", NumWrap.scanPage(rule, "订单列表 w320×200%") >= 1)
    }

    @Test
    @Config(qualifiers = "w320dp-h1400dp-xhdpi")
    fun detailW320Font200Light19Digits() = detailCase(NO_19, dark = false)

    @Test
    @Config(qualifiers = "w320dp-h1400dp-xhdpi")
    fun detailW320Font200Light24Digits() = detailCase(NO_24, dark = false)

    @Test
    @Config(qualifiers = "w390dp-h1400dp-xhdpi")
    fun detailW390Font200Light19Digits() = detailCase(NO_19, dark = false)

    @Test
    @Config(qualifiers = "w390dp-h1400dp-xhdpi")
    fun detailW390Font200Dark24Digits() = detailCase(NO_24, dark = true)

    @Test
    @Config(qualifiers = "w320dp-h1400dp-xhdpi")
    fun detailW320Font200WholePageScan() {
        launchDetail(NO_24, dark = false)
        assertTrue("全页扫描至少要扫到订单号", NumWrap.scanPage(rule, "订单详情 w320×200%") >= 1)
    }

    private fun listCase(
        no: String,
        dark: Boolean,
    ) {
        launchList(no, dark)
        val where = "订单列表卡（${mode(dark)}，${no.length} 位）"
        assertOrderNo(no, OrderTags.ORDER_NO_PREFIX + STRESS, OrderTags.COPY_PREFIX + STRESS, where)
    }

    private fun detailCase(
        no: String,
        dark: Boolean,
    ) {
        launchDetail(no, dark)
        rule.onNodeWithTag(OrderTags.DETAIL_COPY, useUnmergedTree = true).performScrollTo()
        assertOrderNo(no, OrderTags.DETAIL_ORDER_NO, OrderTags.DETAIL_COPY, "订单详情（${mode(dark)}，${no.length} 位）")
    }

    private fun assertOrderNo(
        no: String,
        container: String,
        copyTag: String,
        where: String,
    ) {
        val scope = NumWrap.within(container)
        NumWrap.assertField(rule, NumField(no, where, scope, TOKEN))
        NumCopy.assertCopyClear(rule, NumWrap.nodesFor(rule, no, scope), no, copyTag, where)
    }

    private fun launchList(
        no: String,
        dark: Boolean,
    ) {
        val host = OrderListVisualTestHost(rule).also { list = it }
        host.launch(withOrderNo(OrderListVisualFixtures.page("stress"), no), dark = dark, fontScale = FONT_SCALE, shell = true)
        rule.onNodeWithTag(OrderTags.CARD_PREFIX + STRESS, useUnmergedTree = true).performScrollTo()
    }

    private fun launchDetail(
        no: String,
        dark: Boolean,
    ) {
        val host = OrderDetailVisualTestHost(rule).also { detail = it }
        val source = OrderDetailVisualFixtures.detail("detail_stress")
        val changed = OrderDetailVisualFixtures.change(source, "order_no" to JsonPrimitive(no))
        host.launch(changed, dark = dark, fontScale = FONT_SCALE, shell = true)
    }

    private fun withOrderNo(
        page: JsonObject,
        no: String,
    ): JsonObject {
        val items = page.getValue("items").jsonArray.map { JsonObject(it.jsonObject + ("order_no" to JsonPrimitive(no))) }
        return JsonObject(page + ("items" to JsonArray(items)))
    }

    private fun mode(dark: Boolean) = if (dark) "深" else "浅"

    private companion object {
        const val FONT_SCALE = 2f
        const val STRESS = "STRESS"

        /** QA-11 截图里断开的那串（19 位，specs visualOrders.stress）。 */
        const val NO_19 = "3741234567890123456"

        /** 超长压力值（24 位）。 */
        const val NO_24 = "374123456789012345678901"
        val TOKEN = NumToken(CouliTextStyles.captionAmount.fontSize, FONT_SCALE)
    }
}
