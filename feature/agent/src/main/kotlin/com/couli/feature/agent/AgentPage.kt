package com.couli.feature.agent

import androidx.compose.runtime.Composable
import com.couli.core.agent.AgentDraftTexts
import com.couli.core.agent.AgentPageTicket
import com.couli.core.agent.AgentProductView
import com.couli.core.agent.AgentTexts
import com.couli.core.designsystem.BuyButtonOverride
import com.couli.core.designsystem.MappedProductCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** 测试与读屏用的标记（不是文案）。 */
@Suppress("TooManyFunctions") // 每类可点控件一个标记
object AgentTags {
    const val PAGE = "agent_page"
    const val NEW_CHAT = "agent_new_chat"
    const val FILING = "agent_filing"
    const val WELCOME = "agent_welcome"
    const val EXAMPLES = "agent_examples"
    const val LIST = "agent_list"
    const val INPUT = "agent_input"
    const val SEND = "agent_send"
    const val STOP = "agent_stop"
    const val PASTE = "agent_paste"
    const val ORDERS_CHIP = "agent_orders_chip"
    const val CONTEXT = "agent_context"
    const val BACK_TO_BOTTOM = "agent_back_to_bottom"
    const val UNAVAILABLE = "agent_unavailable"
    const val LOADING = "agent_loading"
    const val LOAD_FAILED = "agent_load_failed"
    const val CONSENT_DECLINED = "agent_consent_declined"
    const val REAGREE = "agent_reagree"
    const val CONSENT_DIALOG = "agent_consent_dialog"
    const val CONSENT_CHECK = "agent_consent_check"
    const val CONSENT_AGREE = "agent_consent_agree"
    const val CONSENT_DECLINE = "agent_consent_decline"
    const val TOAST = "agent_toast"
    const val WITHDRAW_ROW = "agent_withdraw_row"
    const val WITHDRAW_CONFIRM = "agent_withdraw_confirm"
    const val AI_LABEL = "agent_ai_label"

    fun turn(key: String): String = "agent_turn_$key"

    fun card(cardId: String): String = "agent_card_$cardId"

    fun guide(cardId: String): String = "agent_guide_$cardId"

    fun earningsButton(
        cardId: String,
        route: String,
    ): String = "agent_earnings_${cardId}_$route"

    fun earnings(cardId: String): String = "agent_earnings_$cardId"

    fun buy(
        cardId: String,
        itemCardId: String,
    ): String = "agent_buy_${cardId}_$itemCardId"

    fun product(
        cardId: String,
        itemCardId: String,
    ): String = "agent_product_${cardId}_$itemCardId"

    fun refresh(
        cardId: String,
        itemCardId: String,
    ): String = "agent_refresh_${cardId}_$itemCardId"

    fun rule(cardId: String): String = "agent_rule_$cardId"

    fun order(cardId: String): String = "agent_order_$cardId"

    fun auth(cardId: String): String = "agent_auth_$cardId"

    fun handoff(cardId: String): String = "agent_handoff_$cardId"

    fun chip(
        scope: String,
        index: Int,
    ): String = "agent_chip_${scope}_$index"

    fun retry(key: String): String = "agent_retry_$key"

    fun search(key: String): String = "agent_search_$key"

    fun newChatError(key: String): String = "agent_error_new_chat_$key"

    fun upgrade(key: String): String = "agent_upgrade_$key"

    // ---- APP-10b
    const val EARLIER = "agent_earlier"
    const val EARLIER_NONE = "agent_earlier_none"
    const val SESSION_ENDED = "agent_session_ended"
    const val SESSION_ENDED_NEW_CHAT = "agent_session_ended_new_chat"
    const val REPORT_SHEET = "agent_report_sheet"
    const val REPORT_SUBMIT = "agent_report_submit"
    const val REPORT_SUPPLEMENT = "agent_report_supplement"

    fun reportReason(index: Int): String = "agent_report_reason_$index"

    fun feedbackUp(key: String): String = "agent_feedback_up_$key"

    fun feedbackDown(key: String): String = "agent_feedback_down_$key"

    fun reported(key: String): String = "agent_reported_$key"

    fun reply(key: String): String = "agent_reply_$key"

    /** 举报的操作宿主（长按 + 读屏「更多操作 → 举报」；有文字为正文、只有卡片为卡片区，R2-06）。 */
    fun reportHost(key: String): String = "agent_report_host_$key"

    fun status(key: String): String = "agent_status_$key"
}

/**
 * 页面级的内存记忆（APP-12 第 24、27 条：配置重建、切 Tab 回来后保留输入框草稿与滚动位置）：**只在内存里**（brief §3 Android：消息、卡片、金额不进
 * SavedStateHandle / rememberSaveable / Bundle），进程被杀后回到空对话。按渲染时的对话票据记：票据变了（换号重建、新对话）就清空，旧回调写不进新对话
 * （APP-09 第 59 条）。只在主线程使用。
 */
class AgentPageMemory {
    private var owner: AgentPageTicket? = null
    private var draft: String = ""
    var scrollIndex: Int = 0
        private set
    var scrollOffset: Int = 0
        private set

    /** 是否跟随新内容（随阅读位置一起保存恢复，Android R1-08）：没保存过为 true；用户上滑离开底部后保存为 false，恢复时不重新开启。 */
    var follow: Boolean = true
        private set

    /** 这张票据的草稿（票据不是记忆里那一张 → 换代了：清空草稿与滚动位置）。 */
    fun draftFor(t: AgentPageTicket): String {
        adopt(t)
        return draft
    }

    /** 只写进发起时那一个对话票据（旧回调写不进换代后的输入框）。 */
    fun setDraft(
        t: AgentPageTicket,
        text: String,
    ) {
        if (owner == t) draft = text
    }

    fun saveScroll(
        t: AgentPageTicket,
        index: Int,
        offset: Int,
        follow: Boolean,
    ) {
        if (owner != t) return
        scrollIndex = index
        scrollOffset = offset
        this.follow = follow
    }

    /** 这张票据的跟随状态（票据换代 → 清空，重新跟随）。 */
    fun followFor(t: AgentPageTicket): Boolean {
        adopt(t)
        return follow
    }

    fun scrollFor(t: AgentPageTicket): Pair<Int, Int> {
        adopt(t)
        return scrollIndex to scrollOffset
    }

    /** 作废（归属变化、撤回、页面销毁）：草稿与滚动位置一并清空。 */
    fun clear() {
        owner = null
        draft = ""
        scrollIndex = 0
        scrollOffset = 0
        follow = true
    }

    private fun adopt(t: AgentPageTicket) {
        if (owner == t) return
        owner = t
        draft = ""
        scrollIndex = 0
        scrollOffset = 0
        follow = true
    }
}

/** 一条轻提示（编号用于去重与消费）。 */
data class AgentToast(
    val id: Long,
    val text: String,
)

/**
 * Agent 页的外部能力（App 给；feature 之间不能互相依赖）：
 * - [mapCard]：接口商品卡 → DesignSystem 卡片数据（与搜索列表同一个映射，带此刻的平台购买状态，F-AGENT-11）；
 * - [buyButton]：本页购买执行器给这张卡的按钮状态（加载、42901 置灰；按渲染时的购买票据认逻辑卡片，不按 card_id，Android R2-01）；[purchaseOverlay]：购买流程的弹层（确认框、AuthSheet、JumpTip……）；
 *   [purchaseBanner]：购买超时提示条（输入区上方，【重试】沿用原幂等键）；
 * - [paste]：「粘贴查返利」：用户点击时读一次剪贴板文字（FU-CLIP 已有的读取接口与敏感页规则；没读到为 null），填进输入框，由用户点发送（G29）；
 * - [visible] / [blocked] / [screenReader]：同步更新过的页面可见性、阻挡层、读屏状态（APP-11 第 18、20–22 条）；
 * - [toast] / [consumeToast]：轻提示；[memory]：草稿与滚动位置（只在内存）；[readonly]：只读打开（不发起写请求或授权）。
 */
@Suppress("LongParameterList") // 页面的外部能力逐项注入
class AgentPageUi(
    val texts: AgentTexts = AgentDraftTexts,
    val mapCard: (JsonObject) -> MappedProductCard,
    val buyButton: @Composable (AgentProductView) -> BuyButtonOverride? = { null },
    val purchaseOverlay: @Composable () -> Unit = {},
    /** 购买超时提示条（输入区上方，Android R1-03）。 */
    val purchaseBanner: @Composable () -> Unit = {},
    val paste: () -> String? = { null },
    val visible: StateFlow<Boolean> = MutableStateFlow(true),
    val blocked: StateFlow<Boolean> = MutableStateFlow(false),
    val screenReader: StateFlow<Boolean> = MutableStateFlow(false),
    val toast: StateFlow<AgentToast?> = MutableStateFlow(null),
    val consumeToast: (Long) -> Unit = {},
    val memory: AgentPageMemory = AgentPageMemory(),
    val readonly: Boolean = false,
)
