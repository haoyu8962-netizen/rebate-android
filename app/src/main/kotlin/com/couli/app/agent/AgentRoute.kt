package com.couli.app.agent

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.app.search.SearchDraftTexts
import com.couli.app.trade.TradeDraftTexts
import com.couli.core.agent.AgentDraftTexts
import com.couli.core.agent.AgentJson
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.ProductCardMapper
import com.couli.feature.agent.AgentPageUi
import com.couli.feature.agent.AgentScreen
import com.couli.feature.agent.AgentWithdrawRow
import com.couli.feature.trade.PurchaseOverlay
import com.couli.feature.trade.PurchaseTimeoutBanner
import com.couli.feature.trade.searchPurchaseButton
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/**
 * AgentChat Tab 根（APP-10）：按此刻的身份取（没有就建）Agent 控制器并渲染。控制器按身份重建时整块按控制器编号重建，旧回调随旧节点销毁（打到的是已作废的
 * 旧控制器，被它拒绝）。商品卡与搜索列表同一个映射（SearchDraftTexts + 此刻的平台购买状态，F-AGENT-11）；购买按钮与弹层来自本页的购买执行器（feature:trade，
 * feature 之间不互相依赖，所以在这里注入）。[readonly]：只读打开（强更未知 / 基本模式）时不发起任何请求。
 */
@Composable
fun AgentRoute(
    agent: AppAgent,
    readonly: Boolean,
) {
    val observed by agent.controller.collectAsStateWithLifecycle()
    val controller = observed ?: agent.ensure()
    // 本页购买执行器（绑定控制器与卡片动作世代，被释放时为 null：弹层、超时条随之消失，Android R1-02）
    val purchaseNow by agent.purchaseState.collectAsStateWithLifecycle()
    // 渲染时取出的普通不可变值（APP-09 第 63 条）：按钮、弹层、超时条只操作这一份执行器
    val purchase = purchaseNow
    val trade = agent.trade
    val texts = trade?.texts ?: TradeDraftTexts
    val ui =
        remember(agent, purchase, readonly) {
            AgentPageUi(
                texts = AgentDraftTexts,
                mapCard = { card ->
                    ProductCardMapper.map(
                        card,
                        SearchDraftTexts,
                        nowMillis = System.currentTimeMillis(),
                        purchaseStatus = AgentJson.stringOf(card["platform"])?.let { trade?.platformStatus(it) },
                    )
                },
                buyButton = { view ->
                    purchase?.let { c ->
                        val state by c.state.collectAsStateWithLifecycle()
                        // 42901 置灰本页每一张卡；加载、未安装提示只给正在购买的那张卡（与搜索结果同一规则）。按购买票据认逻辑卡片，
                        // 不按 card_id：跨轮重复的 card_id 不会让别的轮次的卡也显示加载（Android R2-01）
                        searchPurchaseButton(state, texts, agent.purchaseCardOf(c, view), c.currentCard)
                    }
                },
                purchaseOverlay = { purchase?.let { PurchaseOverlay(it, texts, buyFocus = null) } },
                // open 超过 8 秒：输入区上方的超时提示条，【重试】走同一执行器的 retryAfterTimeout（原尝试、原幂等键，BR-PRICE-13，Android R1-03）
                purchaseBanner = { purchase?.let { PurchaseTimeoutBanner(it, texts) } },
                paste = agent.paste,
                visible = agent.visible,
                blocked = agent.blocked,
                screenReader = agent.screenReader,
                toast = agent.toast,
                consumeToast = agent::consumeToast,
                memory = agent.memory,
                readonly = readonly,
            )
        }
    key(controller.serial) { AgentScreen(controller, ui) }
}

/**
 * 设置页【撤回 AI 服务同意】一行（G18）：只在 Agent 可用的构建与环境、已同意隐私政策时显示（Release 不显示）。确认框实例由 [AppAgent] 持有：打开时固定身份、
 * 本行的页面许可与一次性编号（Android R1-01，共性第 25 条），确认时把这一个实例交给 [AppAgent.withdrawConsent]，提交前只比较；身份变化时 App 销毁它。
 * 撤回结果的前置路由打开前核对确认时固定的导航许可（发起页仍在且没被覆盖、内部子页没有切换过，共性第 23、24 条）；离页后到达的结果照常结算（作废 Agent
 * 控制器、42901 禁用期），只是不导航、不提示。
 */
@Composable
fun AgentWithdrawSettingsRow(agent: AppAgent) {
    if (!agent.withdrawAvailable()) return
    val busy by agent.withdrawing.collectAsStateWithLifecycle()
    val limited by agent.withdrawGate.blocked.collectAsStateWithLifecycle()
    val confirm by agent.withdrawConfirm.collectAsStateWithLifecycle()
    // 设置页这一次显示的页面许可（鸿蒙 RC-01，共性第 24 条）：进入设置内部子页（昵称、通知设置）时本行离开组合，许可立即作废、本行打开的确认框一并销毁
    val permit = remember { AtomicBoolean(true) }
    DisposableEffect(permit) {
        onDispose {
            permit.set(false)
            agent.dropWithdrawConfirm()
        }
    }
    var notice by remember { mutableStateOf<Pair<Long, String>?>(null) }
    // 渲染时取出的普通不可变值（APP-09 第 63 条）：回调只提交这一个确认实例
    val shown = confirm
    Column {
        AgentWithdrawRow(
            confirmId = shown?.id,
            onOpen = { agent.openWithdrawConfirm { permit.get() } },
            onConfirm = { id ->
                if (shown != null && shown.id == id) {
                    // 确认时固定导航票据（经 Router 的导航次数、栈顶、Tab、进后台次数，APP-12 第 8、14 条）：结果到达时仍是这一张才打开前置页
                    val ticket = agent.navTicket()
                    agent.withdrawConsent(shown, { permit.get() && ticket != null && agent.navTicket() == ticket }) { text ->
                        if (permit.get()) notice = System.nanoTime() to text
                    }
                }
            },
            onCancel = { id -> if (shown != null && shown.id == id) agent.cancelWithdrawConfirm(shown) },
            enabled = !busy && !limited,
        )
        notice?.let { (id, text) ->
            LaunchedEffect(id) {
                delay(NOTICE_MILLIS)
                if (notice?.first == id) notice = null
            }
            CouliToast(message = text, icon = null, announcementId = id, modifier = Modifier.padding(CouliSpacing.space2))
        }
    }
}

private const val NOTICE_MILLIS = 2_000L

/** 读屏状态（触摸浏览）：进程级注册一次，页面持续订阅 [AppAgent.screenReader]（APP-11 第 20 条）。 */
internal fun connectAgentScreenReader(
    context: Context,
    agent: AppAgent,
) {
    val manager = context.applicationContext.getSystemService(AccessibilityManager::class.java) ?: return
    agent.setScreenReader(manager.isTouchExplorationEnabled)
    manager.addTouchExplorationStateChangeListener { enabled -> agent.setScreenReader(enabled) }
}
