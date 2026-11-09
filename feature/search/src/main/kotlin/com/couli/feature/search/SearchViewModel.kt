package com.couli.feature.search

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couli.core.designsystem.BuyButtonOverride
import com.couli.core.designsystem.ProductCardTexts
import com.couli.core.network.PageApi
import com.couli.generated.Sort
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** 搜索页的文案（App 给：契约字典 → 草稿表 SearchDraftTexts）。 */
interface SearchTexts : ProductCardTexts {
    /** 带 {变量} 的文案；变量为空或缺失时用 fallback（BR-TEXT-14）。 */
    override fun format(
        key: String,
        vars: Map<String, String>,
    ): String
}

/** 搜索页的出口（App 给：经 Router 打开别的页、关闭本页）。 */
class SearchPageActions(
    val onBack: () -> Unit = {},
    /** 点结果卡片：经 Router 打开 ProductDetail（routes.json：platform、product_key 必填，item_ref 原样透传）。 */
    val onOpenProduct: (platform: String, productKey: String, itemRef: String?) -> Unit = { _, _, _ -> },
    /** 卡片【购买】：交给 App 层的购买流程（APP-06b：feature:trade 的 OpenPurchaseController）。[spm] 为 search.result_list.<坑位>。 */
    val onBuyIntent: (card: JsonObject, spm: String) -> Unit = { _, _ -> },
    /** 卡片比价无返利原因行的【看看相似商品】（BR-PRICE-08：同平台、原样标题，经 Router 打开 Search）；参数是那张卡。 */
    val onSimilar: (card: JsonObject) -> Unit = {},
)

/** 购买流程的界面插槽（App 用 feature:trade 的组件填；feature 之间不互相依赖）。 */
class SearchPurchaseSlots(
    /** 浮层（加载层、确认框、半屏、轻提示）。 */
    val overlay: @Composable () -> Unit = {},
    /** 页底提示条（超时【重试】）。 */
    val banner: @Composable () -> Unit = {},
    /** 某张卡的购买按钮覆盖（只对正在购买的那张卡）；没有为 null。 */
    val button: @Composable (card: JsonObject) -> BuyButtonOverride? = { null },
)

/**
 * 搜索历史（本进程内，新的在前，最多 [MAX] 条；App 里一份，进程级服务）。TODO(N-27)：是否持久化、条数上限未规定。
 * 关键词请求的写出通知经 [recorder] 记账（FU-AND-SEARCH-LINK-OWNER）：去重、在主线程（[onMain]，App 给主线程投递）记，不挂在页面作用域上——
 * 已写出的搜索在离页、ViewModel 清理之后照样记下。[add] / [clear] / [items] 只在主线程用。
 */
class SearchHistory(
    private val onMain: (() -> Unit) -> Unit = { it() },
) {
    private val words = ArrayDeque<String>()
    private val mutableState = MutableStateFlow<List<String>>(emptyList())

    /** 历史的可观察版本（页面显示用，与 [items] 同步）。 */
    val state: StateFlow<List<String>> = mutableState.asStateFlow()
    val items: List<String> get() = words.toList()

    fun add(word: String) {
        words.remove(word)
        words.addFirst(word)
        while (words.size > MAX) words.removeLast()
        mutableState.value = items
    }

    fun clear() {
        words.clear()
        mutableState.value = items
    }

    /**
     * 一次请求的写出通知（网络层 onWritten，可能在网络线程、重试时会再调）→ 记 [word] 一次：只在 written、[owned]（识别回落时是剪贴板的单一谓词）
     * 时投递，同一请求只记一次（Z13），切回主线程再记（Z14）。主线程真正记下之前再核 [valid]（评审 r1 S0-3：识别回落时是提交时的登录 / 隐私 /
     * 环境身份仍在——投递之后切号、切环境、撤回隐私就不记；单纯离页照记）。空词不记。
     */
    fun recorder(
        word: String,
        owned: () -> Boolean = { true },
        valid: () -> Boolean = { true },
    ): (Boolean) -> Unit {
        // 空词视同已记（不记）
        val fired = AtomicBoolean(word.isEmpty())
        return { written ->
            if (written && owned() && fired.compareAndSet(false, true)) onMain { if (valid()) add(word) }
        }
    }

    private companion object {
        const val MAX = 10
    }
}

/**
 * 搜索页配置。[query] / [platform] 是路由参数（routes.json Search：可选 q、platform）。[bindLeave]：页面一确定出栈（回退栈移除的同步出口）
 * 由 App 调用的作废入口（APP-05a 共性第 9 条）；ViewModel 清理时也会调一次（兜底）。
 */
@Suppress("LongParameterList") // 执行器依赖、文案、路由参数与出口逐项给
class SearchPageConfig(
    val api: PageApi,
    val config: SearchConfigPort,
    val texts: SearchTexts,
    val history: SearchHistory,
    val query: String? = null,
    val platform: String? = null,
    val actions: SearchPageActions = SearchPageActions(),
    val bindLeave: (leave: () -> Unit) -> Unit = {},
    val clock: () -> Long = System::currentTimeMillis,
    val purchase: SearchPurchaseSlots = SearchPurchaseSlots(),
    /** 执行器建好后交给 App（按 open 响应更新卡片）。 */
    val bindController: (SearchController) -> Unit = {},
    /**
     * 自动恢复页面（进程被回收后重开）属于自动执行的动作：先等本轮版本检查结果（最长 5 秒，03 §4.4，APP-06a 共性第 28 条）再按保存的条件
     * 重搜；用户主动打开不等。没有给（单测）时不等。
     */
    val restoreGate: (suspend () -> Unit)? = null,
    /**
     * 「粘贴查返利」入口（APP-07，SearchTyping；App 给剪贴板的 PasteEntry）：输入态（还没搜）、无可搜索平台时显示；有 search_disabled 提示时
     * 不论搜索处于哪个阶段都独立显示（BR-TEXT-14：50304.search_disabled 给【粘贴链接查返利】，A-R2-03）。
     */
    val pasteEntry: (@Composable () -> Unit)? = null,
    /**
     * 粘贴结果（APP-07：30132 的提示与【用商品名搜索】）：挂在搜索栏下面、不依赖搜索阶段（粘贴后已开始关键词搜索也看得见，A-R2-02）；
     * 是否属于本页由 App 按发起页核对。参数是本页搜索框的「填入并搜索」。
     */
    val pasteResult: (@Composable (fill: (String) -> Unit) -> Unit)? = null,
    /** 输入框提交先识别链接 / 口令（FU-AND-SEARCH-LINK-SUBMIT，App 给）；没有给时一律关键词搜。 */
    val linkSubmit: SearchLinkPort? = null,
)

/** 搜索页的弹层。 */
sealed interface SearchOverlay {
    /** 卡片口径说明（SearchDisclaimerNote）：[cardKey] 用来把焦点还给那张卡的 i。 */
    data class Disclaimer(
        val cardKey: String,
        val lines: List<String>,
    ) : SearchOverlay

    /** 价格区间面板（SearchFilterPrice）。 */
    data object PriceFilter : SearchOverlay
}

/**
 * 价格区间面板里还没提交的输入（元的原文）。放在 ViewModel 里并写进 SavedStateHandle：折叠屏展开、窗口变化引起的配置重建，以及进程被
 * 系统回收后的恢复都不丢（03 §10.4，A-R1-07、A-R2-03）。
 * 生命周期：打开面板时按已应用的条件初始化；【重置】清空；【确定】成功或关闭面板时丢弃；【确定】不合法时保留并标 [invalid]。
 */
data class PriceDraft(
    val min: String = "",
    val max: String = "",
    val invalid: Boolean = false,
)

/**
 * 列表的滚动锚点：第一个可见项的下标与偏移（像素），以及当时最后一个可见项的下标（恢复时要把它也加载出来，否则在已加载项的末尾滚不到
 * 原偏移，会被截到更靠上的位置）。
 */
data class ScrollAnchor(
    val index: Int,
    val offset: Int,
    val lastVisible: Int = index,
)

/**
 * 搜索页的 ViewModel（03 §3.3）：状态来自 [SearchController]；输入框文字、历史、弹层在这里。
 *
 * 页面状态写进 [saved]（SavedStateHandle，导航回退栈项的保存状态；APP-06a 共性第 17、20 条，A-R2-03）：输入框文字、已提交的关键词、平台、
 * 排序、筛选、查询轮次、已为哪一轮滚回过顶部、价格面板是否打开与草稿、列表滚动锚点、是否已自动聚焦过。进程被系统回收后新建的 ViewModel
 * 拿到同一份保存状态时，按保存的条件恢复（不看路由参数 q、platform），重搜第一页但不算新一轮查询，再按滚动锚点（必要时继续翻页）回到原位置。
 * 搜索历史只在本进程内（rule-gaps：是否持久化未规定），口径说明弹层不保存（关掉即可重开）。
 */
@Suppress("TooManyFunctions") // 页面每个操作一个入口
class SearchViewModel(
    initial: SearchPageConfig,
    private val saved: SavedStateHandle,
) : ViewModel() {
    var config: SearchPageConfig = initial
        private set
    val controller = SearchController(initial.api, initial.config, viewModelScope, links = initial.linkSubmit, clock = initial.clock)
    val state: StateFlow<SearchSnapshot> = controller.state

    /** 本 ViewModel 是按保存状态恢复出来的（之前的 ViewModel 已被销毁，如进程被系统回收）。 */
    val restored: Boolean = saved.contains(K_KEYWORD)

    /** 输入框已经自动聚焦过（返回本页、恢复后不再弹键盘）。 */
    var autoFocused: Boolean = saved[K_AUTO_FOCUSED] ?: false
        set(value) {
            field = value
            persist { saved[K_AUTO_FOCUSED] = value }
        }

    /**
     * 列表已经为哪一轮查询（[SearchSnapshot.round]）滚回过顶部。返回本页、配置重建、系统回收后恢复时轮次没变，不再滚回顶部，保留恢复的滚动
     * 位置（03 §10.4、适配约定 §6，A-R1-06、A-R2-03）。
     */
    var scrolledRound: Long = saved[K_SCROLLED_ROUND] ?: -1L
        set(value) {
            field = value
            persist { saved[K_SCROLLED_ROUND] = value }
        }

    /**
     * 恢复后还没回到的滚动锚点（只在 [restored] 时有）：结果重新加载到锚点所在项之后滚过去；不够就继续翻页，到底或翻页失败就停在最后。
     * 恢复任务绑定查询轮次（[pendingScrollRound]）与页面实例（A-R1-17、A-R2-03 / 04，APP-06a 共性第 32、33、38、39 条）：列表被别人移动
     * （[restoreTakenOver]）、开始新一轮查询、页面出栈即取消；页面被覆盖（不在前台）时暂停，回到前台继续。是可观察状态：取消后页面上的恢复任务
     * 随之结束。
     */
    private val mutablePendingScroll = MutableStateFlow(if (restored) savedAnchor() else null)
    val pendingScrollFlow: StateFlow<ScrollAnchor?> = mutablePendingScroll.asStateFlow()
    val pendingScroll: ScrollAnchor? get() = mutablePendingScroll.value

    /** 一次性恢复是怎么结束的（F-X-18 观测：aligned / not_confirmed / user_took_over）。 */
    var restoreEnd: String? = null
        internal set

    /** 恢复锚点所属的查询轮次（保存时的轮次）；当前轮次不再是它就放弃恢复。 */
    val pendingScrollRound: Long = saved[K_ROUND] ?: 0L

    /** 页面被覆盖或进入后台（ON_STOP 之后）：冻结位置记录，回到前台（ON_START）解冻（共性第 32 条）。 */
    var recordingFrozen: Boolean = false
        private set

    private fun savedAnchor(): ScrollAnchor? {
        val index = saved.get<Int>(K_SCROLL_INDEX) ?: return null
        return ScrollAnchor(index, saved[K_SCROLL_OFFSET] ?: 0, saved[K_SCROLL_LAST] ?: index)
    }

    private val mutablePriceDraft =
        MutableStateFlow(PriceDraft(saved[K_DRAFT_MIN] ?: "", saved[K_DRAFT_MAX] ?: "", saved[K_DRAFT_INVALID] ?: false))
    val priceDraft: StateFlow<PriceDraft> = mutablePriceDraft.asStateFlow()

    private val mutableInput = MutableStateFlow(if (restored) saved[K_INPUT] ?: "" else initial.query.orEmpty())
    val input: StateFlow<String> = mutableInput.asStateFlow()

    /** 输入版本：输入框内容每变一次加 1。异步的识别结局（回落、30131 预填）只在版本仍是提交时的才回写输入框（评审 S2-7）。 */
    private var inputVersion = 0L

    /** 搜索历史：直接显示进程级历史服务（写出通知在那里记账，离页也不漏）。 */
    val history: StateFlow<List<String>> = initial.history.state

    private val mutableOverlay =
        MutableStateFlow<SearchOverlay?>(if (saved.get<Boolean>(K_SHEET_OPEN) == true) SearchOverlay.PriceFilter else null)
    val overlay: StateFlow<SearchOverlay?> = mutableOverlay.asStateFlow()

    /** 页面已出栈（等待恢复期间离开的，不再开始）。 */
    private var gone = false

    /**
     * 页面实例仍有效（还没出栈）。恢复任务每次滚动、翻页前，以及所有持久化入口（位置记录、条件、输入、草稿、弹层）都先看它（A-R2-04，APP-06a
     * 共性第 39 条）：出栈后组合可能还在（退出动画期间生命周期仍为 STARTED），迟到的布局、滚动、ON_STOP 回调一律不写。
     */
    val alive: Boolean get() = !gone

    init {
        // 页面出栈的同步出口（App 在回退栈移除该条目的同一条主线程消息里调）：作废执行器、恢复任务与位置记录（A-R2-04）
        initial.bindLeave(::leavePage)
        initial.bindController(controller)
        val conditions = savedConditions()

        fun start() {
            // 路由 q 与输入框提交同一条路（评审 S2-5）：带链接 / 口令候选的 q 先识别，历史与预填交同样的回调，不在这里直接记历史
            val version = inputVersion
            val onKeyword = inputNormalized(version)
            val resumed = controller.open(initial.query, initial.platform, conditions, onKeyword, titlePrefill(version), config.history)
            if (conditions != null && !resumed) forgetRestoredScroll()
            saveInput(mutableInput.value)
            saveConditions(state.value.conditions)
            viewModelScope.launch { controller.state.collect { saveConditions(it.conditions) } }
        }
        val gate = initial.restoreGate
        if (conditions != null && gate != null) {
            // 进程回收后的自动恢复：等版本检查有结果再重搜；等待期间保存的条件不动
            viewModelScope.launch {
                gate()
                if (!gone) start()
            }
        } else {
            start()
        }
    }

    private fun savedConditions(): SearchConditions? {
        if (!restored) return null
        return SearchConditions(
            keyword = saved[K_KEYWORD] ?: "",
            // 没有来源标记的旧保存状态：本页能识别时无法证明它是普通词，按识别来源处理（回输入态，安全一侧）；没有识别入口的页面只可能是普通词
            fromRecognition = saved.get<Boolean>(K_FROM_RECOGNITION) ?: (config.linkSubmit != null),
            platform = saved[K_PLATFORM],
            sort = saved[K_SORT] ?: Sort.RELEVANCE.raw,
            hasCoupon = saved[K_COUPON] ?: false,
            priceMinFen = saved[K_PRICE_MIN],
            priceMaxFen = saved[K_PRICE_MAX],
            round = saved[K_ROUND] ?: 0L,
        )
    }

    /**
     * 页面出栈（同步出口；ViewModel 清理时兜底再调一次，无副作用）：一次性作废执行器（在途请求）、恢复任务（清除待恢复锚点，页面上的恢复
     * 协程下一步就结束，且每次滚动前复核 [alive]）并冻结位置记录；之后所有持久化入口拒绝写入（A-R2-04，APP-06a 共性第 39 条）。
     */
    private fun leavePage() {
        if (gone) return
        gone = true
        mutablePendingScroll.value = null
        recordingFrozen = true
        controller.leave()
    }

    /** 持久化入口统一校验页面实例仍有效（已出栈的页面迟到的回调不写保存状态）。 */
    private inline fun persist(block: () -> Unit) {
        if (!gone) block()
    }

    private fun saveConditions(c: SearchConditions) = persist { writeConditions(c) }

    /** 恢复没有按保存的条件继续（识别来源的查询回了输入态、恢复门内用户已有新提交）：待恢复的滚动位置作废，保存的锚点一并清掉。 */
    private fun forgetRestoredScroll() {
        mutablePendingScroll.value = null
        persist {
            saved[K_SCROLL_INDEX] = 0
            saved[K_SCROLL_OFFSET] = 0
            saved[K_SCROLL_LAST] = 0
        }
    }

    private fun writeConditions(c: SearchConditions) {
        // F-X-18 补充（FU-AND-0717）：开始了新一轮查询 → 同步放弃待执行的恢复、清掉旧锚点，第一次持久化就是「新条件 + 空锚点」（进程此刻被杀，
        // 重建后也不会把旧锚点套到新一轮的结果上）
        if (saved.get<Long>(K_ROUND) != c.round && saved.contains(K_ROUND)) {
            mutablePendingScroll.value = null
            saved[K_SCROLL_INDEX] = 0
            saved[K_SCROLL_OFFSET] = 0
            saved[K_SCROLL_LAST] = 0
        }
        saved[K_KEYWORD] = c.keyword
        saved[K_FROM_RECOGNITION] = c.fromRecognition
        saved[K_PLATFORM] = c.platform
        saved[K_SORT] = c.sort
        saved[K_COUPON] = c.hasCoupon
        saved[K_PRICE_MIN] = c.priceMinFen
        saved[K_PRICE_MAX] = c.priceMaxFen
        saved[K_ROUND] = c.round
    }

    private fun saveInput(text: String) {
        if (text != mutableInput.value) inputVersion += 1
        mutableInput.value = text
        persist { saved[K_INPUT] = text }
    }

    private fun setDraft(draft: PriceDraft) {
        mutablePriceDraft.value = draft
        persist {
            saved[K_DRAFT_MIN] = draft.min
            saved[K_DRAFT_MAX] = draft.max
            saved[K_DRAFT_INVALID] = draft.invalid
        }
    }

    private fun setOverlay(next: SearchOverlay?) {
        mutableOverlay.value = next
        persist { saved[K_SHEET_OPEN] = next == SearchOverlay.PriceFilter }
    }

    /** 列表滚动后记下锚点（恢复中的锚点还没回到时不覆盖；页面被覆盖、在后台时冻结不记；页面已出栈不记）。 */
    fun onScrolled(anchor: ScrollAnchor) {
        if (pendingScroll != null || recordingFrozen) return
        write(anchor)
    }

    /**
     * 恢复任务发现列表被别人移动（实际位置偏离任务的预期、或有不属于任务的滚动在进行：触摸、惯性、读屏滚动、键盘 / 焦点移动都算，A-R2-03，
     * APP-06a 共性第 38 条）：放弃恢复，按用户此刻的视口记下位置（页面已出栈、被覆盖时不记）。
     */
    fun restoreTakenOver(anchor: ScrollAnchor?) {
        if (mutablePendingScroll.value != null) restoreEnd = RESTORE_TAKEN_OVER
        mutablePendingScroll.value = null
        if (anchor != null) onScrolled(anchor)
    }

    /**
     * 页面即将被覆盖或进入后台（ON_STOP，同步）：先把此刻视口的有效锚点写进保存状态，再冻结记录（共性第 33 条：进后台前同步保存最新位置）。
     * 恢复中的锚点还没回到时保留它，不用恢复过程中的中间位置覆盖。
     */
    fun onPageStopped(anchor: ScrollAnchor?) {
        if (pendingScroll == null && !recordingFrozen && anchor != null) write(anchor)
        recordingFrozen = true
    }

    /** 回到前台（ON_START）：解冻位置记录（页面已出栈时保持冻结）。 */
    fun onPageStarted() {
        if (gone) return
        recordingFrozen = false
    }

    private fun write(anchor: ScrollAnchor) =
        persist {
            saved[K_SCROLL_INDEX] = anchor.index
            saved[K_SCROLL_OFFSET] = anchor.offset
            saved[K_SCROLL_LAST] = anchor.lastVisible
        }

    /** 恢复的滚动锚点已经回到（或已放弃：用户拖动、新一轮查询、没有可显示的结果）。 */
    fun scrollRestored() {
        mutablePendingScroll.value = null
    }

    /** 每次组合给最新的配置（出口回调随导航宿主变化）；执行器不变。 */
    fun update(next: SearchPageConfig) {
        config = next
    }

    fun onInput(text: String) {
        saveInput(text)
    }

    fun onClear() {
        saveInput("")
    }

    /**
     * 【搜索】或键盘搜索键：关键词请求实际写出才记入历史（评审 r2 S2-3：执行器在网络层的写出通知之后、切回主线程才交历史；写出前被取消、本地
     * 拒绝、没有可搜平台都不记）。输入框识别（FU-AND-SEARCH-LINK-SUBMIT）识别了的不记（D10-651 ③），回落关键词搜时按原提交记；输入框的规范化
     * 回写与历史分开：受理时回写，回落与 30131 的标题预填只在输入版本仍是提交时的才写，用户已改字就不覆盖（评审 S2-7）。
     */
    fun onSubmit(): Boolean {
        val version = inputVersion
        return controller.submit(mutableInput.value, inputNormalized(version), titlePrefill(version), config.history)
    }

    /** 关键词搜索已受理（当场或回落）：输入框只在版本仍是 [version] 时回写规范化后的关键词（历史另由实际写出触发）。 */
    private fun inputNormalized(version: Long): (String) -> Unit = { normalized -> if (inputVersion == version) saveInput(normalized) }

    /** 30131 的标题预填（不自动搜）：页面仍在、输入版本仍是 [version] 时才填。 */
    private fun titlePrefill(version: Long): (String) -> Unit = { title -> if (alive && inputVersion == version) saveInput(title) }

    fun onHistory(word: String) {
        saveInput(word)
        onSubmit()
    }

    fun onClearHistory() {
        config.history.clear()
    }

    fun onCouponToggle() {
        val s = state.value
        controller.setFilters(!s.hasCoupon, s.priceMinFen, s.priceMaxFen)
    }

    fun openPriceFilter() {
        val s = state.value
        if (s.blocked) return
        setDraft(PriceDraft(s.priceMinFen?.let(PriceInput::yuanText).orEmpty(), s.priceMaxFen?.let(PriceInput::yuanText).orEmpty()))
        setOverlay(SearchOverlay.PriceFilter)
    }

    fun onPriceMin(text: String) {
        setDraft(mutablePriceDraft.value.copy(min = text, invalid = false))
    }

    fun onPriceMax(text: String) {
        setDraft(mutablePriceDraft.value.copy(max = text, invalid = false))
    }

    fun resetPriceDraft() {
        setDraft(PriceDraft())
    }

    /** 价格区间面板【确定】：按草稿换算成整数分；不合法返回 false（面板内提示，保留草稿，不发请求）。 */
    fun applyPriceDraft(): Boolean {
        val draft = mutablePriceDraft.value
        val lo = PriceInput.toFen(draft.min)
        val hi = PriceInput.toFen(draft.max)
        val ok = lo != PriceInput.INVALID_FEN && hi != PriceInput.INVALID_FEN && applyPrice(lo, hi)
        if (!ok) setDraft(draft.copy(invalid = true))
        return ok
    }

    /** 价格区间：区间不合法返回 false（不发请求）；成功时关闭面板、丢弃草稿。 */
    fun applyPrice(
        minFen: Long?,
        maxFen: Long?,
    ): Boolean {
        val ok = controller.setFilters(state.value.hasCoupon, minFen, maxFen)
        if (ok) closePriceFilter()
        return ok
    }

    private fun closePriceFilter() {
        setOverlay(null)
        setDraft(PriceDraft())
    }

    fun showDisclaimer(
        cardKey: String,
        lines: List<String>,
    ) {
        setOverlay(SearchOverlay.Disclaimer(cardKey, lines))
    }

    fun dismissOverlay() {
        if (mutableOverlay.value == SearchOverlay.PriceFilter) closePriceFilter() else setOverlay(null)
    }

    override fun onCleared() {
        leavePage()
        super.onCleared()
    }

    private companion object {
        const val K_INPUT = "search.input"
        const val K_KEYWORD = "search.keyword"
        const val K_FROM_RECOGNITION = "search.keyword_from_recognition"
        const val K_PLATFORM = "search.platform"
        const val K_SORT = "search.sort"
        const val K_COUPON = "search.has_coupon"
        const val K_PRICE_MIN = "search.price_min_fen"
        const val K_PRICE_MAX = "search.price_max_fen"
        const val K_ROUND = "search.round"
        const val K_SCROLLED_ROUND = "search.scrolled_round"
        const val K_AUTO_FOCUSED = "search.auto_focused"
        const val K_SHEET_OPEN = "search.price_sheet_open"
        const val K_DRAFT_MIN = "search.price_draft_min"
        const val K_DRAFT_MAX = "search.price_draft_max"
        const val K_DRAFT_INVALID = "search.price_draft_invalid"
        const val K_SCROLL_INDEX = "search.scroll_index"
        const val K_SCROLL_OFFSET = "search.scroll_offset"
        const val K_SCROLL_LAST = "search.scroll_last_visible"
    }
}

/** 价格输入（元，最多两位小数）→ 整数分，不经浮点（BR-PRICE-15 按分校验）。空为 null（不限）；格式不对返回 [INVALID_FEN]。 */
object PriceInput {
    const val INVALID_FEN = -1L
    private val YUAN = Regex("^(\\d{1,8})(?:\\.(\\d{1,2}))?$")
    private const val FEN_PER_YUAN = 100L
    private const val TEN = 10L

    @Suppress("ReturnCount") // 空、格式不对、换算三个出口
    fun toFen(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val match = YUAN.matchEntire(trimmed) ?: return INVALID_FEN
        val yuan = match.groupValues[1].toLong()
        val decimals = match.groupValues[2]
        val cents =
            when (decimals.length) {
                0 -> 0L
                1 -> decimals.toLong() * TEN
                else -> decimals.toLong()
            }
        return yuan * FEN_PER_YUAN + cents
    }

    /** 整数分 → 输入框里的元（去末尾 0）。 */
    fun yuanText(fen: Long): String {
        val yuan = fen / FEN_PER_YUAN
        val cents = fen % FEN_PER_YUAN
        return if (cents == 0L) yuan.toString() else "$yuan.${cents.toString().padStart(2, '0').trimEnd('0')}"
    }
}

/** 一次性滚动恢复的结束原因（[SearchViewModel.restoreEnd]，F-X-18）。 */
internal const val RESTORE_LANDED = "aligned"
internal const val RESTORE_NOT_CONFIRMED = "not_confirmed"
internal const val RESTORE_TAKEN_OVER = "user_took_over"
