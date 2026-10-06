package com.couli.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DS 后续项（followups F-AND-05、F-AND-06）与编排者三端统一裁定「商品卡金额隐藏」：
 * 弹层焦点进出、Toast 重播、ErrorState 移焦、预估返后跟随返利标签、AmountGrid 区间类型、无返利不用橙、口径 i 跟价格走。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class FollowupA11yPriceTest {
    @get:Rule
    val rule = createComposeRule()

    private val mask = AmountMask(hidden = true, mask = "****", spoken = "金额已隐藏")

    @Suppress("LongParameterList") // 样例卡：返利两端 + 隐藏 + 返后价 + 说明 + 金额未知逐项给出
    private fun card(
        basis: String,
        minFen: Long?,
        maxFen: Long?,
        hidden: Boolean = false,
        estNet: Long? = 2000,
        disclaimer: List<String> = listOf(PriceTerms.PRICE_BASIS_GENERAL),
        unknown: Boolean = false,
    ) = ProductCardData(
        platformName = "淘宝",
        title = "样例商品",
        price = if (unknown) null else PriceTagModel.make("taobao", 2990, 0, 2990, false),
        rebate = RebateTagModel.make(basis, minFen, maxFen, false),
        buyBase = "去购买",
        imageLabel = "商品图",
        estNet = EstNetModel.make(basis, minFen, estNet),
        disclaimer = disclaimer,
        infoLabel = "口径说明",
        amountMask = if (hidden) mask else null,
    )

    // ---------- 纯逻辑 ----------

    @Test
    fun modalFocusTransitions() {
        assertEquals(ModalFocus.FOCUS_PANEL_TITLE, ModalFocus.transition(wasPresented = false, isPresented = true))
        assertEquals(ModalFocus.RESTORE_TRIGGER, ModalFocus.transition(wasPresented = true, isPresented = false))
        assertEquals(ModalFocus.NONE, ModalFocus.transition(wasPresented = true, isPresented = true))
        assertEquals(ModalFocus.NONE, ModalFocus.transition(wasPresented = false, isPresented = false))
    }

    @Test
    fun errorStateAnnouncementAndDelay() {
        assertEquals("加载失败，网络不太好，请重试", ErrorStateFocus.announcement("加载失败", "网络不太好，请重试"))
        assertEquals("加载失败", ErrorStateFocus.announcement("加载失败", null))
        assertTrue(ErrorStateFocus.focusDelayMillis("加载失败") >= ErrorStateFocus.BASE_MILLIS)
        assertEquals(ErrorStateFocus.MAX_MILLIS, ErrorStateFocus.focusDelayMillis("长".repeat(1000)))
    }

    @Test
    fun rebateAccentOnlyForRebateWithValue() {
        assertTrue(RebateTagModel.make("normal", 45, 45, false).usesRebateAccent)
        assertTrue(RebateTagModel.make("price_compare_risk", 0, 112, false).usesRebateAccent)
        assertTrue(RebateTagModel.make("amount_unknown", null, null, false).usesRebateAccent)
        assertTrue(RebateTagModel.make("login_required", null, null, true).usesRebateAccent)
        assertFalse(RebateTagModel.make("normal", 0, 0, false).usesRebateAccent)
        assertFalse(RebateTagModel.make("normal", 200, 100, false).usesRebateAccent)
        assertFalse(RebateTagModel.make("no_rebate", 100, 100, false).usesRebateAccent)
    }

    @Test
    fun amountValueRangeTypeAndAccent() {
        val range = AmountCell.of("预估返", AmountValue.Range(135, 269), isRebate = true)
        assertEquals("¥1.35–¥2.69", range.value)
        assertEquals("预估返，1.35 元到2.69 元", range.spoken)
        assertEquals(listOf("¥1.35", "–¥2.69"), range.segments(masked = false))
        assertTrue(range.usesRebateAccent(amountHidden = false))

        val single = AmountCell.of("预估返", AmountValue.Range(45, 45), isRebate = true)
        assertEquals("¥0.45", single.value)

        // 零、异常区间、纯文字：不用橙；异常区间显示「暂无返利」
        val zero = AmountCell.of("预估返", AmountValue.Amount(0), isRebate = true)
        assertFalse(zero.usesRebateAccent(amountHidden = false))
        val bad = AmountCell.of("预估返", AmountValue.Range(269, 135), isRebate = true)
        assertEquals(PriceTerms.NO_REBATE, bad.value)
        assertFalse(bad.usesRebateAccent(amountHidden = false))
        assertFalse(AmountCell.of("预估返", AmountValue.Range(0, 0), isRebate = true).usesRebateAccent(false))
        val text = AmountCell.of("返利", AmountValue.Text(PriceTerms.NO_REBATE), isRebate = true)
        assertFalse(text.usesRebateAccent(amountHidden = false))
        assertFalse(text.isAmount)
        // 隐藏时按种类，不让颜色泄露是否为 0；非返利类从不用
        assertTrue(zero.usesRebateAccent(amountHidden = true))
        assertFalse(text.usesRebateAccent(amountHidden = true))
        assertFalse(AmountCell.of("已提现", AmountValue.Amount(500)).usesRebateAccent(amountHidden = false))
    }

    @Test
    fun estNetFollowsRebateAmountVisibility() {
        assertEquals("${PriceTerms.EST_NET} ¥20", card("normal", 45, 45).visibleEstNet)
        assertNull("金额隐藏：不出预估返后（能反推返利）", card("normal", 45, 45, hidden = true).visibleEstNet)
        assertNull("暂无返利：不出", card("no_rebate", null, null).visibleEstNet)
    }

    @Test
    fun disclaimerButtonFollowsPriceNotRebate() {
        assertTrue(card("normal", 45, 45).showsDisclaimerButton)
        assertTrue("暂无返利仍有 i", card("no_rebate", null, null).showsDisclaimerButton)
        assertTrue("金额隐藏仍有 i", card("normal", 45, 45, hidden = true).showsDisclaimerButton)
        assertFalse("没有价格不显示", card("amount_unknown", null, null, unknown = true).showsDisclaimerButton)
        assertFalse("没有说明全文不显示", card("normal", 45, 45, disclaimer = emptyList()).showsDisclaimerButton)
    }

    @Test
    fun hiddenRebateTagShowsMaskAndHidesNothingElse() {
        val hidden = card("price_compare_risk", 135, 269, hidden = true).displayedRebate
        assertEquals("预估返 ****", hidden.text)
        assertEquals("预估返，金额已隐藏", hidden.spoken)
        assertEquals(RebateKind.RANGE, hidden.kind)
        assertNull("遮罩不能当金额拼进按钮", hidden.singleAmount)
        assertTrue(hidden.usesRebateAccent)
        val single = RebateTagModel.make("normal", 269, 269, false)
        assertEquals("去购买", BuyButtonModel.text("去购买", single.masked(mask), amountHidden = true, BuyButtonModel.CONTEXT_LIST))
        // 没有金额的种类不变
        val none = RebateTagModel.make("no_rebate", null, null, false)
        assertEquals(none, none.masked(mask))
    }

    // ---------- 组件 ----------

    @Test
    fun hiddenAmountCardsReadMaskAndVerbOnly() {
        rule.setContent {
            CouliTheme {
                Column {
                    ProductCardList(data = card("price_compare_risk", 135, 269, hidden = true), onBuy = {})
                    ProductCardGridLayout(items = listOf(card("price_compare_risk", 135, 269, hidden = true)))
                    ProductCardLarge(data = card("normal", 269, 269, hidden = true))
                }
            }
        }
        rule.onAllNodes(hasContentDescription("预估返，金额已隐藏")).fetchSemanticsNodes().let { assertEquals(3, it.size) }
        rule.onAllNodes(hasText(PriceTerms.EST_NET, substring = true)).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        rule.onAllNodes(hasText("¥2.69", substring = true)).fetchSemanticsNodes().let { assertTrue("金额不出现", it.isEmpty()) }
        rule.onNodeWithText("去购买").assertExists()
        rule.onAllNodes(hasText(PriceTerms.SETTLE_BASIS)).fetchSemanticsNodes().let { assertTrue("以结算为准照常", it.isNotEmpty()) }
    }

    @Test
    fun gridKeepsDisclaimerButtonWhenRebateHidden() {
        rule.setContent {
            CouliTheme {
                ProductCardGrid(data = card("no_rebate", null, null), modifier = Modifier.width(200.dp), onInfo = {})
            }
        }
        rule.onNode(hasContentDescription("口径说明")).assertExists()
    }

    @Test
    fun toastReannouncesOnChangeAndOnRepeat() {
        val spoken = mutableListOf<String>()
        var message by mutableStateOf("已复制")
        var shown by mutableIntStateOf(0)
        rule.setContent {
            CompositionLocalProvider(LocalCouliAnnouncer provides CouliAnnouncer { spoken += it }) {
                CouliTheme { CouliToast(message = message, announcementId = shown) }
            }
        }
        rule.waitForIdle()
        message = "已收藏"
        rule.waitForIdle()
        shown++
        rule.waitForIdle()
        assertEquals(listOf("已复制", "已收藏", "已收藏"), spoken)
    }

    @Test
    fun errorStateAnnouncesThenFocusesRetry() {
        val spoken = mutableListOf<String>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalCouliAnnouncer provides CouliAnnouncer { spoken += it }) {
                CouliTheme { ErrorState(title = "加载失败", retry = StateAction("重试") {}, message = "网络不太好") }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("重试").assertIsNotFocused()
        rule.mainClock.advanceTimeBy(ErrorStateFocus.MAX_MILLIS)
        rule.onNodeWithText("重试").assertIsFocused()
        assertEquals(listOf("加载失败，网络不太好"), spoken)
    }

    @Test
    fun dialogFocusesTitleAndRestoresTrigger() {
        var open by mutableStateOf(true)
        rule.setContent {
            CouliTheme {
                val trigger = remember { FocusRequester() }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CouliButton(text = "打开", onClick = { open = true }, modifier = Modifier.couliFocusTarget(trigger).testTag("trigger"))
                }
                if (open) {
                    CouliDialog(
                        title = "提示标题",
                        confirmText = "知道了",
                        onConfirm = { open = false },
                        onDismissRequest = { open = false },
                        restoreFocusTo = trigger,
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(ModalFocus.TITLE_FOCUS_DELAY_MILLIS + 50)
        rule.onNodeWithText("提示标题").assertIsFocused()
        open = false
        rule.waitForIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper(
            ModalFocus.RESTORE_DELAY_MILLIS + 50,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        rule.waitForIdle()
        rule.onNodeWithTag("trigger").assertIsFocused()
    }

    @Test
    fun hiddenAmountCardsSnapshot() {
        rule.setContent {
            CouliTheme {
                Column(Modifier.width(411.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AmountGrid(
                        cells =
                            listOf(
                                AmountCell.of("预估返", AmountValue.Range(135, 269), isRebate = true),
                                AmountCell.of("返利", AmountValue.Amount(0), isRebate = true),
                            ),
                    )
                    ProductCardList(data = card("normal", 269, 269, hidden = true), onBuy = {})
                    ProductCardGridLayout(
                        items = listOf(card("price_compare_risk", 135, 269, hidden = true), card("no_rebate", null, null)),
                        onInfo = {},
                    )
                    ProductCardLarge(data = card("normal", 269, 269, hidden = true))
                }
            }
        }
        rule.onRoot().captureRoboImage("src/test/snapshots/ProductCardAmountHiddenFollowups.png")
    }
}
