package com.couli.feature.search

import com.couli.core.designsystem.ProductCardMapper
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.network.ResultSignals
import com.couli.core.network.RetryAfterGate
import com.couli.core.network.percentEncode
import com.couli.generated.Sort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 搜索页用到的配置能力（生产为 ConfigStore；测试用假实现）。回调都在主线程。 */
interface SearchConfigPort {
    /** 搜索 Tab 的三层原始取值（ConfigStore.searchStatusLayers）。 */
    fun statusLayers(): JsonObject

    /**
     * 重新拉取配置（search_disabled 时，error-codes 50304「刷新配置」）：保证之后有一次在此刻之后才发出的拉取
     * （ConfigStore.requestFresh）。
     */
    fun refresh()

    /** 已发出的配置拉取的最大编号；在 [refresh] 之前取，作为「之后的配置」的分界。 */
    fun issuedMark(): Long

    /** 当前配置最近一次被确认（200 或有效的 304）的拉取编号。大于分界即为分界之后发出、成功取得或确认的配置。 */
    fun appliedMark(): Long

    /** 订阅配置变化（主线程回调）；返回取消订阅的函数。 */
    fun subscribe(listener: () -> Unit): () -> Unit

    /**
     * 平台购买状态 features.platform_status.<platform>（on / maintenance / not_launched，缺失为 null 按 on；APP-06a 共性第 27、31 条）。
     */
    fun platformStatus(platform: String): String? = null
}

/**
 * 输入框提交时的链接 / 口令识别（FU-AND-SEARCH-LINK-SUBMIT，D10-640 / 651 / 656；App 给，feature 不依赖剪贴板模块）。
 * [recognizer] 是本地判断、不发请求：不识别（基本模式、输入里没有链接 / 口令候选）返回 null，提交照关键词搜；输入是候选但识别在共享禁用期
 * 返回 [SearchLinkStart.Blocked]；否则返回这次识别 [SearchLinkStart.Recognize]。
 */
fun interface SearchLinkPort {
    fun recognizer(text: String): SearchLinkStart?
}

/** 输入框提交的识别怎么开始（[SearchLinkPort.recognizer]；null 是「没有候选」，照关键词搜）。 */
sealed interface SearchLinkStart {
    /** 输入是候选，但识别在 42901 共享禁用期中（评审 S1-3）：停止本次提交——不发识别、不改走关键词、不写历史；到期后再提交照常识别。 */
    data object Blocked : SearchLinkStart

    /**
     * 发起 1 次识别。执行器先作废本页的旧提交，再调 [begin]：App 让剪贴板发号（D10-768），返回这次提交（[SearchSubmit]）。归属的唯一来源是
     * 剪贴板，执行器不自建、不自拼。
     */
    class Recognize(
        val begin: () -> SearchSubmit,
    ) : SearchLinkStart
}

/**
 * 一次输入框识别提交（FU-AND-SEARCH-LINK-OWNER，D10-768；App 给，剪贴板发号并统一管理，feature 只消费）。整次提交的生命周期——识别请求、
 * 识别失败的关键词回落、配置驱动的自动续搜、呈现、预填、历史记账——都挂在这一个号下，只问 [owned]。
 */
interface SearchSubmit {
    /** 单一谓词：这次提交仍被持有（剪贴板 stillOwned）。会在网络线程上求值，只读线程安全发布的状态。 */
    fun owned(): Boolean

    /**
     * 这次提交名下的一个请求归属（回落、续搜的关键词请求）：剪贴板发放并登记，作废时一并取消。归属 = [owned] 且 [live]（本页这个请求自身的
     * 序号仍是当前、页面没离开）；[onWritten] 是网络层的写出通知。
     */
    fun ownership(
        live: () -> Boolean,
        onWritten: ((Boolean) -> Unit)?,
    ): CallOwnership

    /** 搜索侧的作废出口（再提交、新关键词、离页、放弃）：交剪贴板清槽。 */
    fun drop()

    /**
     * 提交时的登录 / 隐私 / 环境身份仍在（评审 r1 S0-3）：只比世代与隐私结论、不看槽——单纯离页清了槽仍为真，切号、切环境、撤回隐私为假。
     * 历史记账在主线程真正记下之前用它再核一次。
     */
    fun sameIdentity(): Boolean

    /** 发 1 次识别并按剪贴板出口呈现；结局见 [SearchLinkOutcome]（作废了的只丢弃，为 [SearchLinkOutcome.Handled]）。 */
    suspend fun recognize(): SearchLinkOutcome
}

/** 一次输入框识别的结局（识别了就不写搜索历史，D10-651 ③；只有 [Fallback] 照普通关键词搜、照普通关键词写）。 */
sealed interface SearchLinkOutcome {
    /** 已处理：进详情、出卡片列表 / 提示（30132、30141 等，与粘贴同组件）、强更 / 限流照现有处理、归属失效丢弃。 */
    data object Handled : SearchLinkOutcome

    /** 已处理（30131 提示），并把 [text]（请求前抽出的「」标题）预填进本页搜索框，不自动搜。 */
    data class Prefill(
        val text: String,
    ) : SearchLinkOutcome

    /** 识别失败（5xxxx、网络异常）：回落关键词搜 1 次。 */
    data object Fallback : SearchLinkOutcome
}

/** 一轮查询的条件（系统回收后按它恢复，A-R2-03）：已提交的关键词、平台、排序、筛选（整数分）与查询轮次。 */
data class SearchConditions(
    val keyword: String,
    val platform: String?,
    val sort: String,
    val hasCoupon: Boolean,
    val priceMinFen: Long?,
    val priceMaxFen: Long?,
    val round: Long,
    /** 这个关键词来自一次识别提交（回落、续搜）：归属只活在本次页面实例里，恢复时不得当普通词重搜（评审 r2 S0-1）。 */
    val fromRecognition: Boolean = false,
)

/** 搜索页的阶段。 */
object SearchPhase {
    /** 还没搜（输入态 SearchTyping）。 */
    const val IDLE = "idle"
    const val LOADING = "loading"
    const val CONTENT = "content"
    const val EMPTY = "empty"
    const val ERROR = "error"

    /** 没有 search_status=on 的平台（不发请求）。 */
    const val UNAVAILABLE = "unavailable"
}

/** 搜索页的一次状态快照（不可变；执行器每次变化发一份新的）。 */
data class SearchSnapshot(
    val keyword: String = "",
    val platform: String? = null,
    val tabs: List<String> = emptyList(),
    val sort: String = Sort.RELEVANCE.raw,
    val hasCoupon: Boolean = false,
    val priceMinFen: Long? = null,
    val priceMaxFen: Long? = null,
    val phase: String = SearchPhase.IDLE,
    val items: List<JsonObject> = emptyList(),
    val fallbackItems: List<JsonObject> = emptyList(),
    val endReached: Boolean = true,
    val loadingMore: Boolean = false,
    /**
     * 翻页失败的错误（H-R2-03：保留已展示的结果，列表底部先显示错误文案与 trace6，再给【重试】）。search_disabled 不在这里（按平台
     * 关闭处理，见 [notice]）。翻页成功、重新搜索时清除。
     */
    val loadMoreError: SearchResultView? = null,
    val error: SearchResultView? = null,
    /** 当前展示的结果来自哪个请求序号（测试核对迟到响应）。 */
    val shownSeq: Long? = null,
    /** 50304 search_disabled 的提示（不带重试）：该平台 Tab 已移除、已按可用平台回落。用户下一次主动换条件时清除。 */
    val notice: SearchResultView? = null,
    /** 结果里有缓存降级的报价（stale=true 或 age_sec > 300）：结果顶部提示价格可能已变化（BR-PROD-07、BR-PRICE-11）。 */
    val stale: Boolean = false,
    /** 42901 限时禁用中：搜索、换条件、翻页、重试入口都不可用，到期自动恢复。 */
    val blocked: Boolean = false,
    /**
     * 第几轮查询（每次开始搜第一页加 1；翻页不变）。页面只在它变了时把列表滚回顶部（03 §10.4：返回本页、配置重建都保留滚动位置）。
     */
    val round: Long = 0,
    /** [keyword] 来自一次识别提交：与关键词在同一次状态更新里写入、一起保存（评审 r2 S0-1）。 */
    val keywordFromRecognition: Boolean = false,
) {
    val loadMoreFailed: Boolean get() = loadMoreError != null

    /** 当前条件（保存到 SavedStateHandle 用）。 */
    val conditions: SearchConditions
        get() = SearchConditions(keyword, platform, sort, hasCoupon, priceMinFen, priceMaxFen, round, keywordFromRecognition)
}

/**
 * 搜索页执行器（APP-06a）。请求归属与页面世代（APP-05a 共性 1、9，APP-04 共性 22、47）：
 * - 每个请求 begin 一个序号，归属 [CallOwnership]（「序号仍是当前、页面没离开」）交给 ApiClient：写出前、重试前、恢复前都核对；
 * - 换关键词、换 Tab、换排序、换筛选、翻页、离开页面时，先让旧序号失效，再 cancelOwned 取消旧请求的传输；
 * - 响应到达（挂起之后）再经 [SearchSession.accept] 复核，旧响应只丢弃，不改界面。
 * 只在主线程使用（[scope] 为主线程作用域）。
 */
@Suppress("TooManyFunctions") // 页面每个入口一个函数
class SearchController(
    private val api: PageApi,
    private val config: SearchConfigPort,
    private val scope: CoroutineScope,
    /** 输入框提交的链接 / 口令识别；没有给时提交一律关键词搜（放在 [clock] 之前：既有调用以尾随 lambda 传 clock）。 */
    private val links: SearchLinkPort? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val session = SearchSession()

    /**
     * 本页最新一次识别提交（剪贴板发号，D10-768）：识别在途或已呈现，回落时与 [keywordOwner] 是同一个。再提交、新关键词、离页时交还剪贴板作废
     * （[dropSubmit]）；关键词上的请求不看它，只看 [keywordOwner]。只在主线程读写。
     */
    private var current: SearchSubmit? = null

    /**
     * 本页关键词（[SearchSnapshot.keyword]）所属的识别提交（评审 r1 S0-1）：识别失败回落时与关键词同时写入，查询与归属成对——第一页、自动续搜、
     * 翻页的请求都只挂在它名下、只问它是否仍被持有，不借 [current]（再提交后 current 已是新提交，不能替旧关键词发请求）。它作废之后，下一次
     * 自动或用户触发的请求一律放弃（回输入态）。用户直接搜的关键词为 null。只在主线程读写。
     */
    private var keywordOwner: SearchSubmit? = null
    private val gate = RetryAfterGate(clock)
    private var gateTimer: Job? = null
    private var pager = SearchPager()
    private val owners = mutableListOf<CallOwnership>()
    private val refetchOwners = mutableListOf<CallOwnership>()

    /** 页面已离开。只在主线程写；请求归属在网络线程上读，volatile 发布（评审 r2 S1-2）。 */
    @Volatile private var left = false

    /** 当前第一页 / 翻页请求的序号（与 [session] 同步推进，离开为 null）：关键词请求的归属在网络线程上读它，volatile 发布（评审 r2 S1-2）。 */
    @Volatile private var liveSeq: Long? = null
    private var unsubscribe: (() -> Unit)? = null

    /**
     * 本页收到 search_disabled 的平台 → 收到时配置拉取的分界（[SearchConfigPort.issuedMark]）。在本页隐藏、不再请求；只有分界之后发出、
     * 成功取得或确认（含 304）的配置里该平台明确为 on 时才恢复（H-R2-02、H-R3-01）；禁用前或同一次在途拉取的旧配置不能提前恢复。
     */
    private val disabled = mutableMapOf<String, Long>()

    /**
     * 本轮（用户最近一次主动搜索 / 换条件 / 重试以来）已经返回 search_disabled 的平台（APP-06a 共性第 18 条，A-R1-01）。配置恢复了它的
     * Tab 也不在同一轮里自动重搜它（配置中心与联盟搜索开关不同步时会「隐藏 → 恢复 → 再 50304」循环）；下一次由用户触发。
     */
    private val disabledThisRound = mutableSetOf<String>()

    /** 最近一次 42901 的错误（配置变化在禁用期间换了平台时，页面仍显示它并保持入口禁用，A-R1-02）。 */
    private var blockedView: SearchResultView? = null

    private val mutableState = MutableStateFlow(SearchSnapshot())
    val state: StateFlow<SearchSnapshot> = mutableState.asStateFlow()
    private val s: SearchSnapshot get() = mutableState.value

    /**
     * 页面打开：按路由参数 q、platform（routes.json Search）。有 q 时直接搜。
     * [restored]：进程被系统回收后、按 SavedStateHandle 里保存的条件重建本页（APP-06a 共性第 17、20 条，A-R2-03）。这时不看路由参数，按
     * 保存的关键词、平台、排序、筛选重新搜第一页，查询轮次沿用保存的值（不算新一轮，页面不把滚动位置清零）。来源是识别提交的查询不重搜（见 [restore]）。
     * 返回页面是否按保存的条件继续（false 时待恢复的滚动位置已没有意义）。
     */
    @Suppress("LongParameterList") // 路由参数、恢复条件与 submit 相同的三个回调
    fun open(
        q: String?,
        platform: String?,
        restored: SearchConditions? = null,
        /** 路由 q 与输入框提交走同一条路（评审 S2-5）：三个回调与 [submit] 相同。 */
        onKeyword: (normalized: String) -> Unit = {},
        onPrefill: (text: String) -> Unit = {},
        history: SearchHistory? = null,
    ): Boolean {
        left = false
        if (unsubscribe == null) unsubscribe = config.subscribe(::configChanged)
        if (restored != null) return restore(restored)
        refreshTabs(platform)
        if (q != null && SearchQuery.normalizeKeyword(q).isNotEmpty()) submit(q, onKeyword, onPrefill, history) else idleOrUnavailable()
        return true
    }

    /**
     * 还没搜（无关键词打开、输入态）时按 Tab 立即定页面状态（APP-06a 共性第 26 条）：没有任何可搜索平台 → 「无可搜索平台」，不等用户点搜索；
     * 平台恢复 → 回到输入态。
     */
    private fun idleOrUnavailable() {
        val phase =
            when {
                s.tabs.isEmpty() -> SearchPhase.UNAVAILABLE
                s.phase == SearchPhase.UNAVAILABLE -> SearchPhase.IDLE
                else -> return
            }
        update { it.copy(phase = phase) }
    }

    /**
     * 按保存的条件恢复（评审 r2 S0-1）。三条路：
     * - 恢复门等待期间用户已经提交了新的（在途或已呈现的识别、已受理的关键词）：不覆盖、不作废，只刷新 Tab；
     * - 来源是识别提交的查询：归属活在上一个页面实例里，新实例无法证明它仍有效——不重搜、不当普通词、不补票，回输入态（关键词清空，输入框由页面保留），
     *   保存状态随之落成输入态，再回收也不复活；
     * - 普通词照常重搜第一页。
     * 返回页面是否按保存的条件继续。Tab 刷新与配置订阅三条路都照常。
     */
    private fun restore(saved: SearchConditions): Boolean {
        if (current != null || s.keyword.isNotEmpty()) {
            refreshTabs(s.platform ?: saved.platform)
            if (s.keyword.isEmpty()) idleOrUnavailable()
            return false
        }
        val sort = saved.sort.takeIf { Sort.fromRaw(it) != Sort.UNKNOWN } ?: Sort.RELEVANCE.raw
        val filtersOk = SearchQuery.filtersValid(saved.priceMinFen, saved.priceMaxFen)
        update {
            it.copy(
                keyword = if (saved.fromRecognition) "" else SearchQuery.normalizeKeyword(saved.keyword),
                sort = sort,
                hasCoupon = saved.hasCoupon,
                priceMinFen = saved.priceMinFen.takeIf { filtersOk },
                priceMaxFen = saved.priceMaxFen.takeIf { filtersOk },
                round = saved.round,
            )
        }
        refreshTabs(saved.platform)
        if (s.keyword.isNotEmpty()) run(newRound = false) else idleOrUnavailable()
        return !saved.fromRecognition
    }

    /**
     * 输入框【搜索】：关键词不合法（空、超长）或 42901 禁用中返回 false，不发请求。
     * 接了 [links] 且输入是链接 / 口令候选时先识别（FU-AND-SEARCH-LINK-SUBMIT）：识别在途不发关键词请求、不改页面；已处理即结束（30131 另把
     * 标题交 [onPrefill]），识别失败才按原文走一次关键词搜（同一个关键词入口，规范化与长度校验不变）。识别在共享禁用期（[SearchLinkStart.Blocked]）
     * 返回 false：不发任何请求。[onKeyword] 在关键词搜索被受理时调（当场或回落），页面据此回写规范化后的输入；识别了的不调。
     * [history]：关键词请求实际写出（网络层 [CallOwnership.onWritten]，评审 r2 S2-3）时由进程级历史服务记账（[SearchHistory.recorder]：去重、
     * 切回主线程，不挂在 [scope] 上）；写出前被取消、被本地拒绝、没有可搜平台都不记。
     * 识别提交的归属全部来自剪贴板（D10-768，[SearchSubmit]）：识别、回落、续搜的请求，呈现、预填、回落与历史，都只问 [SearchSubmit.owned]。
     */
    @Suppress("ReturnCount") // 禁用 / 离开、不识别走关键词、识别禁用期、识别已发起四个出口
    fun submit(
        keyword: String,
        onKeyword: (normalized: String) -> Unit = {},
        onPrefill: (text: String) -> Unit = {},
        history: SearchHistory? = null,
    ): Boolean {
        if (gate.active || left) return false
        val begin =
            when (val start = links?.recognizer(keyword)) {
                null -> return keywordSubmitted(keyword, onKeyword, history, submit = null)
                SearchLinkStart.Blocked -> return false
                is SearchLinkStart.Recognize -> start.begin
            }
        // 再提交：旧提交先作废（清槽、取消名下请求），再由剪贴板发新号
        dropSubmit()
        val submit = begin()
        current = submit
        scope.launch {
            val outcome = submit.recognize()
            if (!submit.owned()) return@launch
            when (outcome) {
                SearchLinkOutcome.Fallback -> {
                    keywordSubmitted(keyword, onKeyword, history, submit)
                }

                // 已呈现：槽留着（仍是本页最新一次提交），由下一个作废出口清
                is SearchLinkOutcome.Prefill -> {
                    onPrefill(outcome.text)
                }

                SearchLinkOutcome.Handled -> {
                    Unit
                }
            }
        }
        return true
    }

    /**
     * 关键词搜索一次；受理了把规范化后的关键词交 [onKeyword]，返回是否已受理。[submit] 是识别回落时原来的识别提交（用户直接搜的关键词为
     * null）：回落请求挂在它名下，历史记账也只在它仍被持有时记。
     */
    private fun keywordSubmitted(
        keyword: String,
        onKeyword: (String) -> Unit,
        history: SearchHistory?,
        submit: SearchSubmit?,
    ): Boolean {
        val normalized = SearchQuery.normalizeKeyword(keyword)
        val owned: () -> Boolean = submit?.let { it::owned } ?: { true }
        val valid: () -> Boolean = submit?.let { it::sameIdentity } ?: { true }
        val record = history?.recorder(normalized, owned, valid)
        if (!submitKeyword(keyword, submit, record)) {
            submit?.let(::release)
            return false
        }
        onKeyword(normalized)
        return true
    }

    /** 搜索侧作废出口：本页当前的识别提交交还剪贴板清槽（再提交、新关键词、换条件、离页）。 */
    private fun dropSubmit() {
        val submit = current ?: return
        current = null
        submit.drop()
    }

    /** 识别提交 [submit] 结束（回落未受理、放弃）：交还剪贴板；本页当前 / 关键词所属若已是更新的提交，不动它们。 */
    private fun release(submit: SearchSubmit) {
        if (current === submit) current = null
        if (keywordOwner === submit) keywordOwner = null
        submit.drop()
    }

    /**
     * 关键词搜索的入口：禁用、离开、条件不合法返回 false（未受理、不改界面）。受理不等于发出：没有可搜索平台时 [run] 只转「无可搜索平台」、
     * 不发请求；发出与否以网络层的实际写出为准（[record]，评审 r2 S2-3）。
     */
    @Suppress("ReturnCount") // 禁用 / 离开、条件不合法逐项提前返回
    private fun submitKeyword(
        keyword: String,
        submit: SearchSubmit?,
        record: ((Boolean) -> Unit)?,
    ): Boolean {
        if (gate.active || left) return false
        val normalized = SearchQuery.normalizeKeyword(keyword)
        val previous = s
        refreshTabs(s.platform)
        update { it.copy(keyword = normalized, keywordFromRecognition = submit != null) }
        if (!keywordRun(submit, record)) {
            mutableState.value = previous
            return false
        }
        update { it.copy(notice = null) }
        return true
    }

    /** 换 Tab；42901 禁用中、Tab 不可用、已选中时返回 false。 */
    fun selectTab(platform: String): Boolean {
        if (gate.active || platform !in s.tabs || platform == s.platform) return false
        update { it.copy(platform = platform, notice = null) }
        userRerun()
        return true
    }

    fun selectSort(sort: String): Boolean {
        if (gate.active || sort == s.sort || Sort.fromRaw(sort) == Sort.UNKNOWN) return false
        update { it.copy(sort = sort, notice = null) }
        userRerun()
        return true
    }

    /** 只看有券、价格区间（整数分，null 为不限）。区间不合法（BR-PRICE-15）返回 false，不改筛选。 */
    fun setFilters(
        hasCoupon: Boolean,
        minFen: Long?,
        maxFen: Long?,
    ): Boolean {
        if (gate.active || !SearchQuery.filtersValid(minFen, maxFen)) return false
        update { it.copy(hasCoupon = hasCoupon, priceMinFen = minFen, priceMaxFen = maxFen, notice = null) }
        userRerun()
        return true
    }

    /** 【重试】：42901 禁用中不发请求（返回 false）。 */
    fun retry(): Boolean =
        when {
            gate.active -> false
            s.phase == SearchPhase.ERROR -> userRun()
            s.loadMoreFailed -> loadMore()
            else -> false
        }

    /** 加载更多：已到底、正在加载、没有结果页、42901 禁用中时不做（返回 false）。 */
    @Suppress("ReturnCount")
    fun loadMore(): Boolean {
        val cursor = pager.nextCursor?.takeIf { canLoadMore() } ?: return false
        // 回落页的翻页也挂在关键词所属的提交下（评审 r1 S0-2）：作废之后的翻页（含 AutoLoadMore 自动触发）不写出，页面回输入态
        val owner = keywordOwner
        if (abandonedBefore(owner)) return false
        val requested = s.platform ?: return false
        val query = SearchQuery.build(input(cursor))
        if (!query.valid) return false
        val seq = beginRequest("loadMore")
        update { it.copy(loadingMore = true, loadMoreError = null) }
        scope.launch {
            val result = send(seq, query, owner)
            if (!session.accept(seq)) return@launch
            owners.clear()
            if (lost(owner)) {
                abandon(checkNotNull(owner))
                return@launch
            }
            val view = viewOf(result)
            when {
                view.isSearchDisabled() -> {
                    // H-R2-01：与首屏同一处理（BR-PROD-10）：刷新配置、隐藏该平台、作废其请求、回落到可用平台；不给重试
                    update { it.copy(loadingMore = false) }
                    searchDisabled(view, requested)
                }

                result !is ApiResult.Success -> {
                    // TODO(N-27)：翻页失败的保留 / 重试策略未规定：保留已展示的结果；底部先给错误文案（带 trace6，03 §4.2），再给【重试】
                    val shown = view.copy(platform = view.platform ?: requested)
                    update { it.copy(loadingMore = false, loadMoreError = shown) }
                    startBlock(result, shown)
                }

                else -> {
                    pager.append(envelopeOf(result))
                    applyPager(seq)
                }
            }
        }
        return true
    }

    private fun canLoadMore(): Boolean {
        val idle = !left && !gate.active && !s.loadingMore
        return idle && !s.endReached && (s.phase == SearchPhase.CONTENT || s.phase == SearchPhase.EMPTY)
    }

    /**
     * 用 open 响应更新一张卡（APP-06b：新价格、new_link_id、无返利状态，BR-PRICE-12）：按 [match] 找到结果或推荐里的那张卡替换。
     */
    fun updateItem(
        match: (JsonObject) -> Boolean,
        transform: (JsonObject) -> JsonObject,
    ) {
        update { snapshot ->
            snapshot.copy(
                items = snapshot.items.map { if (match(it)) transform(it) else it },
                fallbackItems = snapshot.fallbackItems.map { if (match(it)) transform(it) else it },
            )
        }
    }

    /**
     * 按 [card] 的 product_key + item_ref 重取权威商品卡（getProduct），用 [merge]（此刻列表里的那张卡, 重取到的卡）→ 新卡 落到结果或推荐里
     * （APP-06b：30144 链接失效、open 响应给不全价格 / 返利字段时，03 §4.2，A-R1-07 / 09）。[merge] 由购买流程给：补齐价格只补未知字段、
     * 保留本次 open 确认的 link_id 与返利状态，重新加载带卡片版本校验（A-R2-02）；缺省整卡替换（card_id 沿用原卡）。之后仍须用户再点购买；
     * 新一轮查询开始、页面离开之后到达的丢弃，失败时保留原卡。
     */
    fun refetchItem(
        card: JsonObject,
        merge: (current: JsonObject, fresh: JsonObject) -> JsonObject = ::replaceKeepingId,
    ) {
        val key = card.string("product_key") ?: return
        if (left) return
        val round = s.round
        val owner = CallOwnership(api.epochs()) { !left && s.round == round }
        refetchOwners += owner
        val query = card.string("item_ref")?.let { "item_ref=${percentEncode(it)}" }
        scope.launch {
            val result = api.send(ApiCall(GET_PRODUCT, pathParams = mapOf("product_key" to key), query = query, ownership = owner))
            refetchOwners -= owner
            if (left || s.round != round) return@launch
            val fresh = (result as? ApiResult.Success)?.data as? JsonObject ?: return@launch
            updateItem({ it.string("product_key") == key }) { old -> merge(old, fresh) }
        }
    }

    /** 整卡替换：列表认卡用的 card_id 沿用原卡（详情接口的卡可能不带）。 */
    private fun replaceKeepingId(
        old: JsonObject,
        fresh: JsonObject,
    ): JsonObject {
        val id = old["card_id"]
        return if (id != null && fresh["card_id"] == null) JsonObject(fresh + ("card_id" to id)) else fresh
    }

    /** 页面出栈（回退栈移除的同步出口；ViewModel 清理兜底）：作废当前请求并取消传输；之后迟到的响应一律丢弃。 */
    fun leave() {
        left = true
        session.leave()
        liveSeq = null
        dropSubmit()
        cancelAll()
        refetchOwners.toList().also { refetchOwners.clear() }.forEach(api::cancelOwned)
        gateTimer?.cancel()
        gateTimer = null
        unsubscribe?.invoke()
        unsubscribe = null
    }

    /**
     * 配置变化（ConfigStore 订阅，主线程）：重算 Tab。当前平台仍可用时只更新 Tab（不打断在途请求、不重复搜索）；当前平台不再可搜（或
     * 之前没有可搜平台、现在有了）时交给自动回落的唯一入口 [fallBack]。
     */
    @Suppress("ReturnCount") // 离开、平台未变、还没搜逐项提前返回
    fun configChanged() {
        if (left) return
        val before = s.platform
        refreshTabs(before)
        if (s.keyword.isEmpty()) {
            // 还没搜：没有请求可作废，只换选中 Tab；全部平台关闭立即进入「无可搜索平台」（共性第 26 条）
            if (s.platform != before) beginRequest("config")
            idleOrUnavailable()
            return
        }
        if (s.platform == before) return
        fallBack()
    }

    /**
     * 自动回落的唯一入口（配置变化、search_disabled 都走这里；APP-06a 共性第 18、19、21 条，A-R2-01、A-R2-02）。先做不受限流影响的事：
     * 作废并取消旧平台的请求、清掉分页、切换页面状态；最后才决定要不要发回落搜索（限流只限制发送）。
     * - 没有任何可用平台：立即进入「无可搜索平台」（不管是否在 42901 禁用中）；
     * - 可用平台都在本轮返回过 search_disabled（配置恢复了它们的 Tab）：停在输入态并保留提示，下一次搜索由用户触发；
     * - 42901 禁用中：选中回落平台，页面保持限流错误与禁用，到期后由用户【重试】在新平台上搜；
     * - 否则在第一个本轮没关闭过的平台上重搜。
     */
    private fun fallBack() {
        beginRequest("fallback")
        pager = SearchPager()
        val usable = s.tabs.firstOrNull { it !in disabledThisRound }
        when {
            s.tabs.isEmpty() -> {
                update { it.cleared().copy(platform = null, phase = SearchPhase.UNAVAILABLE) }
            }

            usable == null -> {
                update { it.cleared().copy(phase = SearchPhase.IDLE) }
            }

            gate.active -> {
                update { it.cleared().copy(platform = usable, phase = SearchPhase.ERROR, error = blockedView, blocked = true) }
            }

            else -> {
                update { it.copy(platform = usable) }
                run()
            }
        }
    }

    private fun SearchResultView.isSearchDisabled(): Boolean = code == DISABLED_CODE && reason == REASON_SEARCH_DISABLED

    private fun refreshTabs(wanted: String?) {
        val layers = config.statusLayers()
        restoreReopened(layers)
        val tabs = SearchTabs.resolve(layers).filter { it !in disabled }
        update { it.copy(tabs = tabs, platform = SearchTabs.select(tabs, wanted)) }
    }

    /**
     * H-R2-02 / H-R3-01：因 search_disabled 隐藏的平台，在分界之后取得或确认的配置（current 层）里明确为 on 时恢复。分界之前或同一次
     * 在途拉取的配置、没有 current 层、取值不是 on（off、缺省、未知值）都继续隐藏。
     */
    private fun restoreReopened(layers: JsonObject) {
        if (disabled.isEmpty()) return
        val applied = config.appliedMark()
        val current = layers["current"] as? JsonObject ?: return
        disabled.entries.removeAll { (platform, mark) -> applied > mark && current.string(platform) == ON }
    }

    /** 42901：按 ErrorEffect.disableMillis 禁用，到期恢复并通知页面。 */
    private fun startBlock(
        result: ApiResult,
        view: SearchResultView,
    ) {
        val millis = ResultSignals.disableMillis(result)
        if (millis <= 0) return
        blockedView = view
        gate.start(millis)
        update { it.copy(blocked = true) }
        gateTimer?.cancel()
        gateTimer =
            scope.launch {
                while (gate.active) delay(gate.remainingMillis())
                if (!left) update { it.copy(blocked = false) }
            }
    }

    /**
     * 50304 + search_disabled（error-codes：不显示重试，刷新配置并隐藏该平台 Tab；BR-PROD-10）：本页先移除该平台，请求配置刷新，按可用
     * 平台回落（统一入口 [fallBack]：本轮已关闭过的平台不再自动重搜，A-R2-01）；没有可用平台就进入「无可搜索平台」。提示保留到用户下一次
     * 换条件。
     * 「粘贴商品链接查返利」入口（APP-07）：提示在时页面不论处于哪个阶段都显示 App 给的粘贴入口（SearchPageConfig.pasteEntry，A-R2-03）。
     */
    private fun searchDisabled(
        view: SearchResultView,
        requested: String,
    ) {
        val platform = view.platform ?: requested
        disabledThisRound += platform
        // 分界在刷新之前取：之后发出的拉取编号都大于它（重复收到时以最新的分界为准）
        disabled[platform] = config.issuedMark()
        config.refresh()
        refreshTabs(null)
        fallBack()
        update { it.copy(notice = view.copy(platform = platform)) }
    }

    /** 已有关键词时按当前条件重搜（用户换 Tab / 排序 / 筛选）；还没搜过只记下条件。 */
    private fun userRerun() {
        if (s.keyword.isNotEmpty()) userRun()
    }

    /**
     * 用户在当前关键词上重搜（换 Tab / 排序 / 筛选、重试）：清空本轮记下的 search_disabled 平台。在途的另一次识别（不是本页关键词所属的提交）
     * 作废（用户已换了要做的事）；关键词仍属原提交，请求照旧挂在它名下，它已作废则放弃（[run]）。
     */
    private fun userRun(): Boolean {
        if (current !== keywordOwner) dropSubmit()
        disabledThisRound.clear()
        return run()
    }

    /**
     * 输入框 / 路由 q 的关键词受理：本页关键词换成新的一次，所属提交同时换成 [submit]（识别回落时是原提交，用户直接搜为 null），查询与归属成对
     * （评审 r1 S0-1）。受理了才把旧的当前提交与旧关键词所属提交交还剪贴板作废；没受理（条件不合法）原样恢复、不作废。[record] 是历史记账。
     */
    private fun keywordRun(
        submit: SearchSubmit?,
        record: ((Boolean) -> Unit)?,
    ): Boolean {
        val previousCurrent = current
        val previousOwner = keywordOwner
        current = submit
        keywordOwner = submit
        disabledThisRound.clear()
        if (!run(record = record)) {
            current = previousCurrent
            keywordOwner = previousOwner
            return false
        }
        if (previousCurrent !== submit) previousCurrent?.drop()
        if (previousOwner !== submit && previousOwner !== previousCurrent) previousOwner?.drop()
        return true
    }

    /**
     * 搜第一页。条件不合法返回 false（不发请求、不改界面）。这是所有第一页请求的统一发送入口：42901 禁用期间一律不发（A-R1-02，
     * 共性第 11 条；翻页的入口 [loadMore] 同样检查）。[newRound] 为 false 只在系统回收后恢复时用：轮次不变，页面不把滚动位置清零。
     * 本页关键词属于一次识别提交（[keywordOwner]：回落，以及配置变化、search_disabled 驱动的自动续搜）时，请求挂在它名下（[SearchSubmit.ownership]），
     * 发送前与结果消费前都只问 [SearchSubmit.owned]：失效（切号、切环境、撤回隐私、发起页被覆盖或离开、再提交）就不发、不展示，页面回到输入态。
     * [record] 是这次提交的历史记账（只给用户提交的那一次，续搜不记）。
     */
    @Suppress("ReturnCount")
    private fun run(
        newRound: Boolean = true,
        record: ((Boolean) -> Unit)? = null,
    ): Boolean {
        if (left || gate.active) return false
        val submit = keywordOwner
        if (abandonedBefore(submit)) return false
        val requested = s.platform
        if (requested == null) {
            // 没有可搜索的平台：不发请求（BR-PROD-10 细则）。旧请求照样作废
            beginRequest("search")
            pager = SearchPager()
            update { it.cleared().copy(phase = SearchPhase.UNAVAILABLE) }
            return true
        }
        val query = SearchQuery.build(input(null))
        if (!query.valid) return false
        val seq = beginRequest("search")
        pager = SearchPager()
        // TODO(N-27)：begin 时是否清除已展示的旧结果未规定；这里清掉并显示加载态，避免新条件下仍显示旧条件的结果
        update { it.cleared().copy(phase = SearchPhase.LOADING, round = if (newRound) it.round + 1 else it.round) }
        scope.launch {
            val result = send(seq, query, submit, record)
            if (!session.accept(seq)) return@launch
            owners.clear()
            if (lost(submit)) {
                abandon(checkNotNull(submit))
                return@launch
            }
            val view = viewOf(result)
            when {
                view.isSearchDisabled() -> {
                    searchDisabled(view, requested)
                }

                result !is ApiResult.Success -> {
                    update { it.copy(phase = SearchPhase.ERROR, error = view, shownSeq = seq) }
                    startBlock(result, view)
                }

                else -> {
                    pager.append(envelopeOf(result))
                    applyPager(seq)
                }
            }
        }
        return true
    }

    /** 关键词所属的识别提交 [owner] 已不再被持有（用户直接搜的关键词为 null，不受约束）。 */
    private fun lost(owner: SearchSubmit?): Boolean = owner != null && !owner.owned()

    /** 发送前：[owner] 已作废就作废在途请求并放弃（回输入态），返回 true——不得再以它的关键词写出（评审 r1 S0-1、S0-2）。 */
    private fun abandonedBefore(owner: SearchSubmit?): Boolean {
        if (!lost(owner)) return false
        beginRequest("abandon")
        abandon(checkNotNull(owner))
        return true
    }

    /**
     * 识别提交 [submit] 已失效（评审 r2 S1-1、r3 S1）：结果不展示（不记 shownSeq、不当成本页的结果或错误态），交还剪贴板，清掉关键词回到输入态——
     * 之后的配置变化不会按旧提交自动重搜（输入框由页面保留）。
     */
    private fun abandon(submit: SearchSubmit) {
        release(submit)
        pager = SearchPager()
        update {
            it.cleared().copy(
                keyword = "",
                keywordFromRecognition = false,
                phase = if (it.tabs.isEmpty()) SearchPhase.UNAVAILABLE else SearchPhase.IDLE,
            )
        }
    }

    private fun SearchSnapshot.cleared(): SearchSnapshot =
        copy(
            items = emptyList(),
            fallbackItems = emptyList(),
            endReached = true,
            loadingMore = false,
            loadMoreError = null,
            error = null,
            stale = false,
        )

    private fun applyPager(seq: Long) {
        val items = pager.items.toList()
        val fallback = pager.fallbackItems
        update {
            it.copy(
                items = items,
                fallbackItems = fallback,
                endReached = pager.endReached,
                loadingMore = false,
                phase = if (pager.stateRaw == SearchStateRaw.CONTENT) SearchPhase.CONTENT else SearchPhase.EMPTY,
                stale = (items + fallback).any(ProductCardMapper::stale),
                error = null,
                loadMoreError = null,
                shownSeq = seq,
            )
        }
    }

    private fun input(cursor: String?): JsonObject =
        SearchQuery.input(s.keyword, s.platform.orEmpty(), s.sort, s.hasCoupon, s.priceMinFen, s.priceMaxFen, cursor)

    /** 新请求开始：旧序号立即失效，并取消旧请求的传输。 */
    private fun beginRequest(kind: String): Long {
        val seq = session.begin(kind)
        liveSeq = seq
        cancelAll()
        return seq
    }

    /**
     * 发一次搜索请求。本页请求自身的归属在网络线程（写出前守卫）上求值，只读 volatile 的 [left]、[liveSeq]（评审 r2 S1-2）。属于识别提交
     * [submit] 的请求（回落、续搜）由剪贴板发放归属（[SearchSubmit.ownership]，D10-768）：再并上剪贴板的单一谓词，作废时由剪贴板一并取消。
     * [record] 是网络层的写出通知（历史记账，[SearchHistory.recorder]）。
     */
    private suspend fun send(
        seq: Long,
        query: SearchQueryResult,
        submit: SearchSubmit? = null,
        record: ((Boolean) -> Unit)? = null,
    ): ApiResult {
        val live = { !left && liveSeq == seq }
        val owner = submit?.ownership(live, record) ?: CallOwnership(api.epochs(), onWritten = record, stillOwned = live)
        owners += owner
        return api.send(ApiCall(SEARCH_PRODUCTS, query = query.toQueryString(), ownership = owner))
    }

    private fun cancelAll() {
        val cancelled = owners.toList()
        owners.clear()
        cancelled.forEach(api::cancelOwned)
    }

    private inline fun update(change: (SearchSnapshot) -> SearchSnapshot) {
        mutableState.value = change(mutableState.value)
    }

    companion object {
        const val SEARCH_PRODUCTS = "searchProducts"
        const val GET_PRODUCT = "getProduct"
        const val REASON_SEARCH_DISABLED = "search_disabled"
        const val DISABLED_CODE = 50304
        private const val ON = "on"
    }
}

/** 统一错误文案（非强更 10405 等，F-X-09）。 */
internal const val GENERIC_ERROR_KEY = "error.50001"

/** 成功结果 → 完整 envelope（[SearchPager.append] / [SearchResultState.map] 的输入）。 */
internal fun envelopeOf(result: ApiResult.Success): JsonObject =
    JsonObject(
        mapOf<String, JsonElement>(
            "code" to JsonPrimitive(0),
            "data" to (result.data ?: JsonObject(emptyMap())),
            "trace_id" to JsonPrimitive(result.traceId),
        ),
    )

/**
 * 任一结果 → 结果判定：成功、业务错误按外壳映射（[SearchResultState.map]）；没有外壳的失败（断网、超时、本地拒绝）为错误态，文案键为 null
 * （页面用通用失败键 error.50001，没有 trace_id 时按无编号的 fallback）。
 *
 * 10405 按网络层 Effect 判（F-X-09，FU-AND-0715 第 1 轮评审 R1-03）：重新包装成外壳会丢掉 Effect，所以在这里先消费——不是强更（REFRESH_ONLY、
 * 刷新失败清会话）→ 统一错误文案 error.50001（附 trace 末 6 位）+【重试】，不显示「版本过低」；强更照旧按 error.10405（强更页由网络层打开）。
 */
internal fun viewOf(result: ApiResult): SearchResultView =
    when (result) {
        is ApiResult.Success -> {
            SearchResultState.map(envelopeOf(result))
        }

        is ApiResult.Failure -> {
            if (ResultSignals.nonForcedUpdate(result)) {
                SearchResultView(SearchStateRaw.ERROR, GENERIC_ERROR_KEY, result.code, null, result.traceId)
            } else {
                SearchResultState.map(
                    JsonObject(
                        mapOf(
                            "code" to JsonPrimitive(result.code),
                            "data" to result.data,
                            "trace_id" to JsonPrimitive(result.traceId),
                        ),
                    ),
                )
            }
        }

        else -> {
            SearchResultView(SearchStateRaw.ERROR, null, -1, null, null)
        }
    }
