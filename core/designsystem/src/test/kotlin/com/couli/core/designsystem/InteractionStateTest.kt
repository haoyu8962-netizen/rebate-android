package com.couli.core.designsystem

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 交互状态：键盘焦点环（R1-04，adaptation-a11y-spec §8）与金额隐藏（R1-03，BR-TEXT-10）。
 * 焦点快照走真实的焦点系统：切到键盘输入模式后按 Tab 的同一路径 moveFocus(Next)，再断言目标控件确实有焦点。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class InteractionStateTest {
    @get:Rule
    val rule = createComposeRule()

    private val single = RebateTagModel.make("normal", 269, 269, false)

    private var inputModeManager: InputModeManager? = null
    private var composeView: View? = null

    /**
     * 键盘导航第 [times] 下：切到键盘输入模式（外接键盘按键时 Compose 做的同一件事），宿主视图取得焦点时焦点进入第一个控件
     * （第 1 下），之后每下走 Tab 的同一路径 moveFocus(Next)。
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun pressTab(times: Int) {
        rule.runOnIdle {
            composeView?.requestFocus()
            inputModeManager?.requestInputMode(InputMode.Keyboard)
        }
        repeat(times - 1) { rule.runOnIdle { focusManager?.moveFocus(FocusDirection.Next) } }
        rule.waitForIdle()
    }

    private var focusManager: FocusManager? = null

    private fun host(content: @Composable () -> Unit) {
        rule.setContent {
            inputModeManager = LocalInputModeManager.current
            focusManager = LocalFocusManager.current
            composeView = LocalView.current
            CouliTheme {
                Column(
                    modifier = Modifier.width(411.dp).background(CouliColors.backgroundCanvas).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { content() }
            }
        }
        rule.waitForIdle()
    }

    private fun focusedLabel(): String {
        val nodes = rule.onAllNodes(isFocused()).fetchSemanticsNodes()
        assertEquals("应有且只有一个控件获得键盘焦点", 1, nodes.size)
        val node = nodes.single()
        return node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config
                .getOrNull(SemanticsProperties.Text)
                ?.joinToString()
                .orEmpty()
    }

    /** 主按钮获得键盘焦点：外侧 2 + 2 的 focus.ring 环；旁边的次按钮、文字按钮无焦点作对比。 */
    @Test
    fun buttonFocusRing() {
        host {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CouliButton(text = "提现", onClick = {})
                CouliButton(text = "取消", onClick = {}, variant = CouliButtonVariant.SECONDARY)
                CouliButton(text = "提现规则", onClick = {}, variant = CouliButtonVariant.TEXT)
            }
        }
        pressTab(1)
        assertEquals("提现", focusedLabel())
        rule.onRoot().captureRoboImage("src/test/snapshots/FocusRing_Button.png")
    }

    /** 列表卡底行：Tab 依次经过口径 i 按钮、购买按钮；购买按钮贴近卡片边缘，焦点环仍完整（卡片不裁切）。 */
    @Test
    fun buyButtonFocusRingInCard() {
        host {
            ProductCardList(
                data =
                    ProductCardData(
                        platformName = "淘宝",
                        title = "抽纸 3 层 120 抽 × 24 包 家用整箱装",
                        price = PriceTagModel.make("taobao", 1734, 500, 1234, false),
                        rebate = single,
                        buyBase = "领券购买",
                        imageLabel = "商品图",
                        disclaimer = listOf(PriceTerms.PRICE_BASIS, PriceTerms.REBATE_ESTIMATE),
                        disclaimerKeys = listOf(DisclaimerKeys.PRICE_BASIS, DisclaimerKeys.REBATE_ESTIMATE),
                        infoLabel = "口径说明",
                    ),
                onBuy = {},
            )
        }
        pressTab(2)
        assertTrue("焦点应在购买按钮上：${focusedLabel()}", focusedLabel().startsWith("领券购买"))
        rule.onRoot().captureRoboImage("src/test/snapshots/FocusRing_BuyButtonInCard.png")
    }

    /** 触摸模式（默认）下即使控件被程序请求焦点也不画环：这里不切键盘模式，图里不应有绿色环。 */
    @Test
    fun noFocusRingInTouchMode() {
        host {
            CouliButton(text = "提现", onClick = {})
        }
        assertEquals(0, rule.onAllNodes(isFocused()).fetchSemanticsNodes().size)
    }

    /** 语义树（未合并）里所有节点的读屏名称与文字。 */
    private fun spokenAndVisibleTexts(): List<String> =
        rule
            .onAllNodes(SemanticsMatcher("任意节点") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .map { it.text }
            }

    /** 金额隐藏：主卡、分项、说明行、说明条都只显示遮罩，语义树里（读屏读到的文字与名称）不含任何金额。 */
    @Test
    fun walletAmountHiddenLeaksNothing() {
        val mask = AmountMask(hidden = true, mask = "****", spoken = "金额已隐藏")
        host {
            WalletHeroCard(
                texts =
                    WalletHeroTexts(
                        "可提现余额",
                        "¥12.34",
                        "可提现余额，12.34 元",
                        "提现",
                        "提现记录",
                        "隐藏金额",
                        "显示金额",
                        hiddenSpoken = "可提现余额，金额已隐藏",
                    ),
                onWithdraw = {},
                amountHidden = true,
                hiddenMask = "****",
                onToggleHidden = {},
                onRecords = {},
            ) {
                AmountGrid(
                    tone = AmountGridTone.ON_SOLID,
                    valueSize = AmountValueSize.BODY,
                    mask = mask,
                    cells = listOf(AmountCell("冻结中", "¥0", "冻结中，0 元"), AmountCell("已提现", "¥5", "已提现，5 元")),
                )
            }
            AmountGrid(
                mask = mask,
                cells =
                    listOf(
                        AmountCell(
                            "预估收益",
                            "¥56.78",
                            "预估收益，56.78 元",
                            note = "其中已收货 ¥34.56",
                            hiddenNote = "其中已收货 ****",
                            emphasis = true,
                            labelIcon = CouliIcons.clock,
                            helpLabel = "预估收益说明",
                            onHelp = {},
                        ),
                        AmountCell("最早预计", "10 月结算", "最早预计 10 月结算", isAmount = false),
                    ),
            )
            InfoStrip(text = "其中 **** 暂缓入账", spoken = "暂缓入账，金额已隐藏", helpLabel = "暂缓入账说明", onHelp = {})
        }
        val exposed = spokenAndVisibleTexts()
        val amounts = listOf("¥", "12.34", "56.78", "34.56", "4.56", "0 元", "5 元")
        val leaks = exposed.filter { text -> amounts.any { it in text } }
        assertTrue("隐藏后仍暴露金额：$leaks", leaks.isEmpty())
        assertTrue("应读出「金额已隐藏」：$exposed", exposed.count { "金额已隐藏" in it } >= 5)
        rule.onRoot().captureRoboImage("src/test/snapshots/WalletAmountHidden.png")
    }
}
