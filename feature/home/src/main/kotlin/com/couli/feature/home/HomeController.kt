package com.couli.feature.home

import androidx.compose.ui.graphics.ImageBitmap
import com.couli.core.designsystem.DisclaimerKeys
import com.couli.core.designsystem.ProductCardMapper
import com.couli.core.home.HomeAdLabel
import com.couli.core.home.HomeCall
import com.couli.core.home.HomeCard
import com.couli.core.home.HomeDraftTexts
import com.couli.core.home.HomeErrors
import com.couli.core.home.HomeFailKind
import com.couli.core.home.HomeFailure
import com.couli.core.home.HomeIdentity
import com.couli.core.home.HomeJson
import com.couli.core.home.HomeLevel
import com.couli.core.home.HomePage
import com.couli.core.home.HomeShowing
import com.couli.core.home.HomeSourceEvent
import com.couli.core.home.HomeSourcePolicy
import com.couli.core.home.HomeSourceStep
import com.couli.core.home.HomeSpm
import com.couli.core.home.NoticeFilter
import com.couli.core.home.NoticeItem
import com.couli.core.home.PopupPolicy
import com.couli.core.home.RouteTarget
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.ResultSignals
import com.couli.core.wallet.SendProbe
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 首页控制器（03 §6.2 渲染流程，TECH-10 / 12，G14、G16）：一个身份一个实例。
 *
 * - **身份**在构造时固定（[identity]：身份档 + 账号 + 登录世代 + 隐私 / 环境世代 + 环境）；拉取、写 LKG、写关闭记录、写弹窗计数、渲染回调都只用这一份，
 *   读「当前身份」只用于比较。归属变化时 App 同步调 [invalidate] 作废本实例，并按新身份**重建**（不是只刷新展示）。
 * - **三级兜底**按 [HomeSourcePolicy]：有 LKG 立即显示；无 LKG 等网络 1.5 秒，超时先显示包内默认；Release 没有网络来源（[HomeDeps.port] 为 null）时不等。
 *   同一首页的页面拉取按请求序号只收最新一次；下拉刷新发出后，之前在途的冷启动拉取结果只写 LKG、不替换当前屏。
 * - 每次异步等待（网络、读 LKG、图片、计时）结束后先复核本实例仍有效、身份未变、渲染世代未变。
 * - **42901**：三个首页接口共享 [HomeDeps.gate]；响应到达先写限流（复核归属之后），再决定隐藏卡片；执行层经发送前闸门同样拦截。
 * - **弹窗**只在冷启动的渲染里安排（下拉刷新不安排，替换页面时清掉旧候选与图片）；图片加载成功、首页可见、没有阻挡项才弹；真正显示后才计数。
 * - 回调一律带渲染时的票据（[HomeRenderTicket] 等），执行时只比较。
 * 只在主线程使用。
 */
@Suppress("TooManyFunctions", "LargeClass") // 三级兜底、数据源、公告、弹窗、下拉刷新的入口集中在一个控制器
class HomeController(
    val identity: HomeIdentity,
    private val deps: HomeDeps,
    /** HomePreview：只读（不写 LKG、计数、关闭记录，不发埋点），草稿 popup 立即弹一次（G24 ⑤⑥）。 */
    val preview: Boolean = false,
) {
    val id: Long = NEXT.incrementAndGet()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val policy = HomeSourcePolicy()

    /** 本实例仍有效（网络线程的发送前闸门也读它）。 */
    @Volatile
    var alive: Boolean = true
        private set

    /**
     * 本机写入的世代票据（共性第 23 条，R1-01）：构造时（身份固定时）取一次；写 LKG、关闭记录、弹窗计数都带它，存储在与清理共用的锁里核对。
     * 撤回同意、切换环境的清理推进世代后，本实例已经排进 IO 的写入也会被拒（只取消协程挡不住已在等锁的同步写入）。
     */
    private val lkgTicket = deps.lkg?.ticket(identity.scope, identity.partition)
    private val prefsTicket = deps.prefs?.ticket()

    private var renderGen = 0L
    private var page: HomePage? = null
    private val mutable = MutableStateFlow(HomeUiState(HomeRenderTicket(id, 0L)))
    val state: StateFlow<HomeUiState> = mutable.asStateFlow()

    /** 页面拉取的请求序号（只收最新一次）与最近一次写进 LKG 的序号。 */
    private var pageSeq = 0L
    private var writtenSeq = 0L
    private var started = false

    /** 冷启动阶段（还没下拉刷新）：只在这一阶段的渲染里安排弹窗（共性第 2 条）。 */
    private var coldPhase = true
    private var interacted = false
    private val owners = mutableSetOf<CallOwnership>()
    private var coldTimer: (() -> Unit)? = null
    private var noticeTimer: (() -> Unit)? = null
    private var popupTimer: (() -> Unit)? = null
    private val products = mutableMapOf<String, ProductState>()
    private val cardSeq = mutableMapOf<String, Long>()
    private val apiNotices = mutableMapOf<String, List<NoticeItem>>()
    private val memoryClosed = mutableSetOf<String>()
    private var candidate: PopupCandidate? = null

    /** 本页自己打开、还在显示的弹层（[overlayShown] / [overlayGone]）。 */
    private val overlays = mutableSetOf<Any>()
    private var popupSerial = 0L
    private var popupCounted = false
    private var shownThisColdStart = false
    private var toastSerial = 0L

    /** 列表滚动位置（返回首页、切 Tab 回来保持，G23；内容替换后回到顶部）。 */
    var scrollIndex: Int = 0
    var scrollOffset: Int = 0

    /** 商品卡口径说明弹层的文案（界面取）。 */
    val cardTexts: com.couli.core.designsystem.ProductCardTexts get() = deps.cardTexts

    /** banner 当前帧（切 Tab、从子页返回保持，G23）。 */
    val bannerFrames: MutableMap<String, Int> = mutableMapOf()

    private class ProductState(
        val items: List<HomeProductUi> = emptyList(),
        val cursor: String? = null,
        val loading: Boolean = false,
        /** 实际失败（错误码与 trace_id，R2-04）；没失败为 null。 */
        val error: HomeFailure? = null,
        val loaded: Boolean = false,
    ) {
        val failed: Boolean get() = error != null
    }

    /** 公告 api 来源的失败（卡隐藏、不提示，G10；只留实际错误码，R2-04）。 */
    private val noticeErrors = mutableMapOf<String, HomeFailure>()

    /**
     * 冷启动或替换页面后被 42901 拦下、到期要自动重做的首屏加载（R2-03）：商品池 / 信息流的 section id 与公告的 section id。
     * 商品池 / 信息流记的是「尚未取得商品的加载链」被拦下的那一次请求：原游标与已用掉的空页续取次数（D10-607 D4，r1 S1），到期原样续上。
     */
    private val resumeProducts = mutableMapOf<String, PendingLoad>()
    private val resumeNotices = mutableSetOf<String>()

    /** 尚未取得商品的加载链被 42901 拦下的那次请求：[cursor] 原游标（首取为 null），[emptyRefetches] 这次请求已占用的空页续取次数。 */
    private class PendingLoad(
        val cursor: String?,
        val emptyRefetches: Int,
    )

    /** 共享禁用期（R2-03）：[HomeDeps.gate] 的 blocked 订阅结果；[wake] 每次到期 +1。 */
    private var rateLimited = false
    private var wake = 0L

    /** 触发 StateLoadFailed 的那次页面失败（R2-04）。 */
    private var pageFailure: HomeFailure? = null

    /** 候选与图片可以复用；[shownSerial] 是当前这一次显示申请的实例编号（每次重新申请都换新的，R1-06），没有显示中的为 0。 */
    private class PopupCandidate(
        val card: HomeCard.Popup,
        val renderGen: Long,
        var image: ImageBitmap? = null,
        var loading: Boolean = false,
        var shownSerial: Long = 0L,
    )

    init {
        // 可见性与阻挡项持续订阅（作用域随本实例作废而取消，共性第 18、20、21 条）
        scope.launch {
            deps.signals.visible
                .drop(1)
                .collect { onSignals() }
        }
        scope.launch {
            deps.signals.blockerChanges
                .drop(1)
                .collect { onSignals() }
        }
        // 共享禁用期（R2-03，共性第 34 条）：页面订阅；到期时把被拦下的自动动作恢复（首屏加载直接重做，触底加载由仍在组合中的触底项按原票据再试）
        scope.launch {
            deps.gate.blocked.collect { blocked ->
                val was = rateLimited
                rateLimited = blocked
                when {
                    was && !blocked -> resumeGated()
                    was != blocked -> publish()
                }
            }
        }
    }

    /** 本实例仍有效、身份未变（主线程核对；网络线程只读 [alive]）。 */
    fun owned(): Boolean = alive && deps.host.identity() == identity

    // ---------------------------------------------------------------- 冷启动

    /** 首页冷启动（本身份第一次显示）：读 LKG → 三级兜底。HomePreview 不调它（用 [showDraft]）。 */
    fun start() {
        if (started || preview) return
        started = true
        coldStart()
    }

    private fun coldStart() {
        pageFailure = null
        coldPhase = true
        interacted = false
        shownThisColdStart = false
        scope.launch {
            val text = withContext(deps.io) { runCatching { deps.lkg?.read(identity.scope) }.getOrNull() }
            if (!owned()) return@launch
            val lkgPage = text?.let { HomeJson.parseText(it, deps.platform, deps.appVersion) }
            apply(policy.on(HomeSourceEvent.ColdStart(hasLkg = text != null)), lkgPage, null)
            if (text != null && lkgPage?.renderable != true) {
                // LKG 文件损坏：当作没有 LKG（G16），删掉。只删读到的这一份，带票据与接受顺序（R2-01：期间落盘的较新结果不被删）
                val store = deps.lkg
                val ticket = lkgTicket
                if (!preview && store != null && ticket != null) {
                    val order = store.reserve()
                    withContext(deps.io) { runCatching { store.discard(ticket, order, text) } }
                }
                if (!owned()) return@launch
                apply(policy.on(HomeSourceEvent.LkgLoaded(valid = false)), null, null)
            }
            if (deps.port == null) {
                // Release 没有网络来源（G02）：不白等 1.5 秒（H03 同口径）
                apply(policy.on(HomeSourceEvent.Timer1500), null, null)
                return@launch
            }
            if (policy.showing == HomeShowing.NONE) {
                coldTimer =
                    deps.timers.after(COLD_WAIT_MS) {
                        if (owned()) apply(policy.on(HomeSourceEvent.Timer1500), null, null)
                    }
            }
            fetchPage(pull = false)
        }
    }

    /** StateLoadFailed 的【重试】：按同一身份重新走三级兜底。 */
    fun retryLoadFailed(ticket: HomeRenderTicket) {
        if (!current(ticket) || !state.value.loadFailed) return
        if (deps.gate.active) {
            // 禁用期内【重试】置灰；仍被调到（旧帧）时只提示一次 42901（R2-03）
            showToast(HomeErrors.message(HomeErrors.RATE_LIMITED, null))
            return
        }
        coldTimer?.invoke()
        coldStart()
    }

    /** HomePreview：用当前环境换来的草稿渲染（只读）；草稿 popup 立即弹一次。 */
    fun showDraft(page: HomePage) {
        if (!preview || !owned()) return
        render(page, HomeShowing.NETWORK)
    }

    // ---------------------------------------------------------------- 页面拉取

    private fun fetchPage(pull: Boolean) {
        val port = deps.port ?: return
        val seq = ++pageSeq
        val probe = deps.gate.probe()
        val call = homeCall(probe)
        scope.launch {
            val result = port.getPage(PAGE_KEY, call)
            if (!owned()) return@launch
            settlePage(seq, pull, result, probe)
        }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // 结算按结果逐项
    private fun settlePage(
        seq: Long,
        pull: Boolean,
        result: ApiResult,
        probe: SendProbe,
    ) {
        // 先处理这个响应自己的限流（共性第 22、34 条：已复核归属）
        val disable = ResultSignals.disableMillis(result)
        if (disable > 0) deps.gate.start(disable)
        val parsed =
            (result as? ApiResult.Success)?.data?.let { it as? JsonObject }?.let {
                HomeJson.parse(
                    it,
                    deps.platform,
                    deps.appVersion,
                )
            }
        if (seq != pageSeq) {
            // 被更新的拉取（下拉刷新）取代：只写 LKG，不替换当前屏；服务端判定的 10405 照样进强更（R1-04）
            if (parsed?.renderable == true && seq > writtenSeq) writeLkg(parsed, seq)
            if ((result as? ApiResult.Failure)?.code == FORCE_UPDATE_CODE) deps.host.forceUpdate()
            return
        }
        if (pull) mutable.value = mutable.value.copy(refreshing = false)
        if (parsed?.renderable != true) pageFailure = HomeErrors.failure(result, probe.gated(result))
        val event =
            when {
                parsed?.renderable == true -> HomeSourceEvent.NetworkOk(valid = true)

                result is ApiResult.Success -> HomeSourceEvent.NetworkFail(HomeFailKind.INVALID_PAGE)

                probe.gated(
                    result,
                ) -> HomeSourceEvent.NetworkFail(HomeFailKind.HTTP_42901, (deps.gate.remainingMillis() / MILLIS).toString())

                else -> HomeSourceEvent.NetworkFail(failKind(result), (disable / MILLIS).takeIf { it > 0 }?.toString())
            }
        val step = policy.on(event)
        apply(step, parsed, result, seq)
    }

    private fun failKind(result: ApiResult): String =
        when (result) {
            is ApiResult.Failure -> {
                when {
                    result.code == ResultSignals.RATE_LIMITED -> HomeFailKind.HTTP_42901
                    result.code == FORCE_UPDATE_CODE -> HomeFailKind.CODE_10405
                    result.code / SERVER_DIVISOR == SERVER_CLASS -> HomeFailKind.HTTP_5XX
                    else -> HomeFailKind.OTHER
                }
            }

            is ApiResult.TransportFailure -> {
                if (result.kind == TIMEOUT) HomeFailKind.TIMEOUT else HomeFailKind.OFFLINE
            }

            else -> {
                HomeFailKind.OTHER
            }
        }

    /** 执行策略的动作（[candidatePage] 是这一步可能要显示的页面，[result] 是触发这一步的结果）。 */
    @Suppress("CyclomaticComplexMethod") // 动作逐项
    private fun apply(
        step: HomeSourceStep,
        candidatePage: HomePage?,
        result: ApiResult?,
        seq: Long = 0L,
    ) {
        for (action in step.actions) {
            when {
                action == HomeSourcePolicy.SHOW_LKG && candidatePage?.renderable == true -> {
                    render(candidatePage, HomeShowing.LKG)
                }

                action == HomeSourcePolicy.SHOW_NETWORK && candidatePage != null -> {
                    render(candidatePage, HomeShowing.NETWORK)
                }

                action == HomeSourcePolicy.WRITE_LKG && candidatePage != null -> {
                    writeLkg(candidatePage, seq)
                }

                action == HomeSourcePolicy.SHOW_DEFAULT -> {
                    showDefault()
                }

                action == HomeSourcePolicy.SHOW_LOAD_FAILED -> {
                    coldTimer?.invoke()
                    page = null
                    renderGen += 1
                    clearSectionState()
                    publish(showing = HomeShowing.LOAD_FAILED)
                }

                action == HomeSourcePolicy.FORCE_UPDATE -> {
                    deps.host.forceUpdate()
                }

                action.startsWith(HomeSourcePolicy.TOAST_ERROR) -> {
                    toast(result)
                }
            }
        }
    }

    private fun showDefault() {
        val bundled = HomeJson.parseText(deps.bundled(), deps.platform, deps.appVersion)
        if (bundled?.renderable == true) {
            render(bundled, HomeShowing.DEFAULT)
        } else {
            apply(policy.on(HomeSourceEvent.DefaultLoaded(valid = false)), null, null)
        }
    }

    @Suppress("ReturnCount") // 预览、归属、序号、没有存储依次提前返回
    private fun writeLkg(
        target: HomePage,
        seq: Long,
    ) {
        // 只写服务端下发的页面 JSON；预览、写之前复核（A 的在途响应不写 B 的 LKG）
        if (preview || !owned() || seq < writtenSeq) return
        val store = deps.lkg ?: return
        val ticket = lkgTicket ?: return
        writtenSeq = seq
        val text = target.raw.toString()
        // 接受顺序在接受结果的这一刻（主线程）取（R2-01，共性第 32 条）：两次写入逆序拿到存储锁时，存储在锁内拒绝较早接受的那一次
        val order = store.reserve()
        scope.launch {
            withContext(deps.io) { runCatching { store.write(ticket, order, text) } }
        }
    }

    // ---------------------------------------------------------------- 渲染

    private fun render(
        target: HomePage,
        level: String,
    ) {
        renderGen += 1
        page = target
        clearSectionState()
        scrollIndex = 0
        scrollOffset = 0
        bannerFrames.clear()
        // 替换页面：清掉旧的候选弹窗与图片就绪状态；只在冷启动阶段安排新的（共性第 2 条）
        closePopupUi()
        candidate = target.popup?.takeIf { coldPhase && !shownThisColdStart }?.let { PopupCandidate(it, renderGen) }
        reportDropped(target)
        publish(showing = level)
        target.cards.forEach { card ->
            when (card) {
                is HomeCard.ProductScroll, is HomeCard.ProductGrid, is HomeCard.FeedInfinite -> loadProducts(card.id, cursor = null)
                is HomeCard.NoticeBar -> if (card.apiSource) loadNotices(card.id)
                else -> Unit
            }
        }
        scheduleNoticeExpiry()
        preparePopup()
    }

    /** 换页（或进 StateLoadFailed）：各卡的数据、失败与待恢复的加载都属于旧渲染世代，一并清掉。 */
    private fun clearSectionState() {
        products.clear()
        cardSeq.clear()
        apiNotices.clear()
        noticeErrors.clear()
        resumeProducts.clear()
        resumeNotices.clear()
    }

    private fun reportDropped(target: HomePage) {
        if (preview || identity.basic) return
        target.dropped.forEach { drop ->
            if (deps.dedupe.shouldSend(target.version, drop.id.orEmpty(), drop.reason)) {
                deps.host.reportCardError(drop.type.orEmpty(), drop.version, target.version, drop.reason)
            }
        }
    }

    private fun publish(showing: String = mutable.value.showing) {
        val ticket = HomeRenderTicket(id, renderGen)
        val current = page
        val cards = if (current == null) emptyList() else cardsUi(current, ticket)
        val productKeys = cards.flatMap { ui -> productsOf(ui).map { it.data.disclaimerKeys } }
        val errors = products.mapNotNull { (id, data) -> data.error?.let { id to it } }.toMap() + noticeErrors
        mutable.value =
            mutable.value.copy(
                render = ticket,
                showing = showing,
                pageVersion = current?.version,
                cards = cards,
                disclaimers = disclaimers(productKeys),
                dropped = current?.dropped.orEmpty(),
                rateLimited = rateLimited,
                loadFailedMessage = if (showing == HomeShowing.LOAD_FAILED) (pageFailure ?: HomeErrors.failure(null)).message else null,
                sectionErrors = errors,
            )
    }

    private fun productsOf(ui: HomeCardUi): List<HomeProductUi> =
        when (ui) {
            is HomeCardUi.ProductScroll -> ui.items
            is HomeCardUi.ProductGrid -> ui.items
            is HomeCardUi.Feed -> ui.items
            else -> emptyList()
        }

    private fun disclaimers(keys: List<List<String>>): List<String> {
        if (keys.isEmpty()) return emptyList()
        val price = DisclaimerKeys.listFooterPriceKey(keys)
        val rebate = keys.flatten().filter { it == DisclaimerKeys.REBATE_ESTIMATE || it == DisclaimerKeys.REBATE_COMPARE }.distinct()
        return (listOfNotNull(price) + rebate).mapNotNull { DisclaimerKeys.text(it) }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // 8 种卡逐项映射
    private fun cardsUi(
        target: HomePage,
        ticket: HomeRenderTicket,
    ): List<HomeCardUi> {
        val now = deps.clock()
        val closed = closedKeys()
        return target.cards.mapNotNull { card ->
            when (card) {
                is HomeCard.Banner -> {
                    card.items.takeIf { it.isNotEmpty() }?.let { items ->
                        HomeCardUi.Banner(
                            card.id,
                            card.heightRatio.toFloat(),
                            card.autoplayMs,
                            items.mapIndexed { index, item ->
                                HomeBannerFrame(
                                    image = item.image,
                                    adLabel = HomeAdLabel.of(card.adLabel, item.adLabel),
                                    description = item.alt?.takeIf { it.isNotEmpty() } ?: HomeDraftTexts.text(HomeDraftTexts.BANNER_IMAGE),
                                    tap = HomeTap(ticket, card.id, index + 1, item.action),
                                )
                            },
                        )
                    }
                }

                is HomeCard.IconGrid -> {
                    card.items.takeIf { it.isNotEmpty() }?.let { items ->
                        HomeCardUi.IconGrid(
                            card.id,
                            card.columns,
                            items.mapIndexed { index, item ->
                                HomeIconCell(
                                    item.icon,
                                    item.title,
                                    item.badge,
                                    HomeTap(
                                        ticket,
                                        card.id,
                                        index + 1,
                                        item.action,
                                    ),
                                    HomeAdLabel.of(card.adLabel, item.adLabel),
                                )
                            },
                        )
                    }
                }

                is HomeCard.ActivityEntry -> {
                    card.items.takeIf { it.isNotEmpty() }?.let { items ->
                        HomeCardUi.Activity(
                            card.id,
                            items.mapIndexed { index, item ->
                                HomeActivityCell(
                                    item.image,
                                    item.title,
                                    item.subtitle,
                                    HomeTap(ticket, card.id, index + 1, item.action),
                                    HomeAdLabel.of(card.adLabel, item.adLabel),
                                )
                            },
                        )
                    }
                }

                is HomeCard.NoticeBar -> {
                    val items = if (card.apiSource) apiNotices[card.id].orEmpty() else card.items
                    val view = NoticeFilter.view(card.id, items, closed, now)
                    if (view.hidden) {
                        null
                    } else {
                        HomeCardUi.Notice(
                            card.id,
                            card.scroll,
                            view.items.mapIndexed { index, item ->
                                HomeNoticeLine(
                                    itemId = item.itemId,
                                    text = item.text,
                                    close =
                                        if (item.closable) {
                                            HomeNoticeClose(
                                                ticket,
                                                card.id,
                                                item.itemId,
                                                item.contentVersion,
                                                identity.epochs,
                                                persistCloses(),
                                            )
                                        } else {
                                            null
                                        },
                                    tap = item.target?.let { HomeTap(ticket, card.id, index + 1, it) },
                                )
                            },
                            card.adLabel,
                        )
                    }
                }

                is HomeCard.ProductScroll -> {
                    products[card.id]?.items?.takeIf { it.isNotEmpty() }?.let { items ->
                        HomeCardUi.ProductScroll(card.id, card.title, card.moreAction?.let { HomeTap(ticket, card.id, 0, it) }, items)
                    }
                }

                is HomeCard.ProductGrid -> {
                    products[card.id]?.items?.takeIf { it.isNotEmpty() }?.let { items ->
                        HomeCardUi.ProductGrid(card.id, card.title, card.columns, items)
                    }
                }

                is HomeCard.FeedInfinite -> {
                    val data = products[card.id]
                    if (data == null || (data.items.isEmpty() && !data.failed)) {
                        null
                    } else {
                        HomeCardUi.Feed(
                            card.id,
                            data.items,
                            data.failed,
                            data.loading,
                            data.cursor != null,
                            HomeFeedTicket(ticket, card.id),
                            error = data.error?.message,
                            rateLimited = rateLimited,
                            wake = wake,
                        )
                    }
                }

                is HomeCard.Popup -> {
                    null
                }
            }
        }
    }

    /** 关闭记录只在完整模式的正式首页落盘；基本模式（只读）与预览只在本次页面内存里隐藏（H10 同口径）。 */
    private fun persistCloses(): Boolean = !preview && !identity.basic

    /** 预览不套用设备级关闭记录（运营核对草稿要看到全部公告，只读）；基本模式照常读、不写。 */
    private fun closedKeys(): Set<String> = memoryClosed + (if (preview) emptySet() else deps.prefs?.closedKeys().orEmpty())

    // ---------------------------------------------------------------- 商品池与信息流（G03、G13）

    @Suppress("LongMethod", "CyclomaticComplexMethod") // 结果逐项结算（成功、本地限流、实际失败、强更、到期恢复）
    private fun loadProducts(
        sectionId: String,
        cursor: String?,
        emptyRefetches: Int = 0,
    ) {
        val port = deps.port
        val previous = products[sectionId]
        if (port == null) {
            // 没有端口（HomePreview 等调用方未给）：不发，卡隐藏（G03）
            products[sectionId] = ProductState(loaded = true)
            return
        }
        val seq = (cardSeq[sectionId] ?: 0L) + 1
        cardSeq[sectionId] = seq
        val gen = renderGen
        resumeProducts.remove(sectionId)
        products[sectionId] =
            ProductState(previous?.items.orEmpty(), previous?.cursor, loading = true, loaded = previous?.loaded == true)
        val probe = deps.gate.probe()
        val call = homeCall(probe)
        scope.launch {
            val result = port.listSectionItems(PAGE_KEY, sectionId, cursor, call)
            if (!owned() || gen != renderGen || cardSeq[sectionId] != seq) return@launch
            // 先处理 42901（共性第 4 条），再决定隐藏
            val disable = ResultSignals.disableMillis(result)
            if (disable > 0) deps.gate.start(disable)
            // 10405（R1-04，共性第 26 条）：归属复核之后进强更（正式首页与预览同一入口）
            if ((result as? ApiResult.Failure)?.code == FORCE_UPDATE_CODE) deps.host.forceUpdate()
            val data = (result as? ApiResult.Success)?.data as? JsonObject
            val items = data?.get(ITEMS) as? JsonArray
            // 尚未取得商品的加载链（r1 S1）：首取，或 D4 空页续取（emptyRefetches > 0，带游标但卡上仍没有商品）
            val chain = previous?.items.isNullOrEmpty() && (cursor == null || emptyRefetches > 0)
            val gated = probe.gated(result)
            products[sectionId] =
                when {
                    items != null -> {
                        val mapped =
                            items.mapIndexedNotNull { index, element ->
                                product(
                                    sectionId,
                                    element,
                                    (previous?.items?.size ?: 0) + index + 1,
                                )
                            }
                        val next = (data[NEXT_CURSOR] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        val all = previous?.items.orEmpty() + mapped
                        // D10-607 D4：这张卡还没有任何商品、这页空但带游标 → 不等触底接着取，最多 EMPTY_PAGE_REFETCH_MAX 次（之后按空卡隐藏）；
                        // 已有商品时照常等触底。商品池卡同样适用，取到一页商品后不翻页（D6）。
                        if (all.isEmpty() && next != null && emptyRefetches < EMPTY_PAGE_REFETCH_MAX) {
                            loadProducts(sectionId, next, emptyRefetches + 1)
                            return@launch
                        }
                        ProductState(all, next, loaded = true)
                    }

                    gated -> {
                        // 被本地限流拦下（没有发出，R2-03）：不算失败、不显示错误；首屏到期由控制器重做，翻页保留游标，到期由仍在组合中的触底项再试
                        if (chain) resumeProducts[sectionId] = PendingLoad(cursor, emptyRefetches)
                        ProductState(previous?.items.orEmpty(), previous?.cursor, loaded = previous?.loaded == true)
                    }

                    else -> {
                        // 实际失败：保留错误码与 trace_id（R2-04）。商品池首屏被服务端 42901 拦下也在到期后重做（卡片没有别的入口）
                        // 空页续取中途被服务端 42901 拦下（r1 S1）：两种数据源都按原游标与已用次数到期续上；信息流这时不当失败显示（卡仍隐藏、没有触底入口）
                        val failure = HomeErrors.failure(result)
                        val refetch = chain && failure.rateLimited && emptyRefetches > 0
                        val resume = refetch || (chain && failure.rateLimited && !isFeed(sectionId))
                        if (resume) resumeProducts[sectionId] = PendingLoad(cursor, emptyRefetches)
                        val shown = failure.takeUnless { refetch && isFeed(sectionId) }
                        ProductState(previous?.items.orEmpty(), cursor, error = shown, loaded = true)
                    }
                }
            publish()
            // 拦下之后、结算之前禁用期已经到期（订阅已经发过到期通知）：现在就恢复
            if (gated && !deps.gate.active) resumeGated()
        }
    }

    private fun isFeed(sectionId: String): Boolean = page?.cards?.firstOrNull { it.id == sectionId } is HomeCard.FeedInfinite

    /**
     * 禁用期到期（R2-03，共性第 34 条）：被拦下的首屏加载（商品池、信息流、公告）在本渲染世代内重做；[wake] +1 让仍在组合中的信息流触底项用原渲染票据再试
     * （[loadMore] 照旧核对票据、禁用期、在途与失败）。【重试】随 [rateLimited] 恢复可点。
     */
    private fun resumeGated() {
        if (!alive || deps.gate.active) return
        wake += 1
        resumeProducts.toList().forEach { (id, pending) ->
            resumeProducts.remove(id)
            if (products[id]?.loading != true) loadProducts(id, pending.cursor, pending.emptyRefetches)
        }
        resumeNotices.toList().forEach { id ->
            resumeNotices -= id
            loadNotices(id)
        }
        publish()
    }

    @Suppress("ReturnCount") // 形状与金额未知卡
    private fun product(
        sectionId: String,
        element: kotlinx.serialization.json.JsonElement,
        slot: Int,
    ): HomeProductUi? {
        val json = element as? JsonObject ?: return null
        val platform = (json["platform"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val mapped = ProductCardMapper.map(json, deps.cardTexts, deps.clock(), purchaseStatus = deps.purchaseStatus(platform))
        val key = (json["product_key"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val itemRef = (json["item_ref"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val card = page?.cards?.firstOrNull { it.id == sectionId } ?: return null
        val itemLabel = (json["ad_label"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val tap =
            key?.let {
                val params =
                    linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
                        "platform" to JsonPrimitive(platform),
                        "product_key" to JsonPrimitive(it),
                    )
                itemRef?.let { ref -> params["item_ref"] = JsonPrimitive(ref) }
                HomeTap(HomeRenderTicket(id, renderGen), sectionId, slot, RouteTarget(PRODUCT_DETAIL, JsonObject(params)))
            }
        val stable = key ?: (json["link_id"] as? JsonPrimitive)?.content ?: "$sectionId-$slot"
        return HomeProductUi(stable, mapped.data, HomeAdLabel.of(card.adLabel, itemLabel), tap)
    }

    /** 信息流触底：加载下一页（一页失败只停在【重试】）。 */
    @Suppress("ReturnCount") // 条件依次提前返回
    fun loadMore(ticket: HomeFeedTicket) {
        // 禁用期内触底：不发；到期时 [resumeGated] 让仍在组合中的触底项按原票据再调一次（R2-03）
        if (!current(ticket.render) || deps.gate.active) return
        val data = products[ticket.sectionId] ?: return
        if (data.loading || data.failed || data.cursor == null) return
        loadProducts(ticket.sectionId, data.cursor)
        publish()
    }

    /** 信息流【重试】：受同一截止时间约束（被限流时提示一次 42901）。 */
    @Suppress("ReturnCount") // 条件依次提前返回
    fun retryFeed(ticket: HomeFeedTicket) {
        if (!current(ticket.render)) return
        val data = products[ticket.sectionId] ?: return
        if (data.loading || !data.failed) return
        if (deps.gate.active) {
            showToast(HomeErrors.message(HomeErrors.RATE_LIMITED, null))
            return
        }
        loadProducts(ticket.sectionId, data.cursor.takeIf { data.items.isNotEmpty() })
        publish()
    }

    // ---------------------------------------------------------------- 公告（G10、G11）

    private fun loadNotices(sectionId: String) {
        val gen = renderGen
        val probe = deps.gate.probe()
        val owner = CallOwnership(identity.epochs, sendAllowed = { probe.allowed() && alive }, stillOwned = { alive })
        owners += owner
        scope.launch {
            val result =
                deps.api.send(
                    ApiCall(LIST_ARTICLES, query = NOTICE_QUERY, ownership = owner, expectedAccount = identity.account),
                )
            owners -= owner
            if (!owned() || gen != renderGen) return@launch
            // 42901 先写进共享闸门，再按失败隐藏（共性第 4 条）
            val disable = ResultSignals.disableMillis(result)
            if (disable > 0) deps.gate.start(disable)
            val items = ((result as? ApiResult.Success)?.data as? JsonObject)?.get(ITEMS) as? JsonArray
            val gated = probe.gated(result)
            if (items == null) {
                // 失败：卡隐藏、不提示（G10），保留实际错误码与 trace_id（R2-04）；被 42901（本地或服务端）拦下的到期重做（R2-03）
                val failure = HomeErrors.failure(result, gated)
                if (gated) noticeErrors -= sectionId else noticeErrors[sectionId] = failure
                if (failure.rateLimited) resumeNotices += sectionId
            } else {
                noticeErrors -= sectionId
            }
            apiNotices[sectionId] =
                items.orEmpty().mapNotNull { element ->
                    val summary = element as? JsonObject ?: return@mapNotNull null
                    if ((summary["category"] as? JsonPrimitive)?.content != NOTICE) return@mapNotNull null
                    HomeJson.noticeItem(summary["notice"])
                }
            publish()
            scheduleNoticeExpiry()
            if (gated && !deps.gate.active) resumeGated()
        }
    }

    /** 关闭一条公告：按渲染时的三元组记录（设备级，不受账号影响；撤回同意、切换环境之后的旧回调不落盘）。 */
    fun closeNotice(close: HomeNoticeClose) {
        if (close.render.controllerId != id) return
        val key = NoticeFilter.closeKey(close.sectionId, close.itemId, close.contentVersion)
        if (close.persist) {
            val ticket = prefsTicket
            val store = deps.prefs
            if (ticket != null && store != null &&
                deps.api.epochs() == close.epochs
            ) {
                store.addClosed(ticket, store.reserve(), key, deps.clock())
            }
        } else {
            memoryClosed += key
        }
        if (alive && close.render.renderGen == renderGen) {
            memoryClosed += key
            publish()
        }
    }

    /** 页面停留时在最近的 end_at 到期时重算（不轮询网络）。 */
    private fun scheduleNoticeExpiry() {
        noticeTimer?.invoke()
        noticeTimer = null
        val current = page ?: return
        val now = deps.clock()
        val next =
            current.cards
                .filterIsInstance<HomeCard.NoticeBar>()
                .mapNotNull { card ->
                    val items = if (card.apiSource) apiNotices[card.id].orEmpty() else card.items
                    NoticeFilter.view(card.id, items, closedKeys(), now).nextExpiryMs
                }.minOrNull() ?: return
        val gen = renderGen
        noticeTimer =
            deps.timers.after((next - now).coerceAtLeast(0L)) {
                if (owned() && gen == renderGen) {
                    publish()
                    scheduleNoticeExpiry()
                }
            }
    }

    /** 测试与时钟推进后的立即重算（例如页面恢复可见）。 */
    fun recomputeNotices() {
        if (!alive) return
        publish()
        scheduleNoticeExpiry()
    }

    // ---------------------------------------------------------------- 弹窗（G14）

    @Suppress("ReturnCount") // 条件依次提前返回
    private fun preparePopup() {
        val c = candidate ?: return
        if (identity.basic) {
            candidate = null
            return
        }
        if (c.image != null || c.loading) {
            recheckPopup()
            return
        }
        val loader = deps.images
        if (loader == null) {
            candidate = null
            return
        }
        c.loading = true
        scope.launch {
            val bitmap = runCatching { loader.load(c.card.image) }.getOrNull()
            c.loading = false
            // 图片回来后复核：仍是这一个候选、渲染世代未变、本实例仍有效
            if (!owned() || candidate !== c || c.renderGen != renderGen) return@launch
            if (bitmap == null) {
                // 图片加载失败：不弹、不计数（G14 ⑨）
                candidate = null
                return@launch
            }
            c.image = bitmap
            recheckPopup()
        }
    }

    private fun blockersNow(): Set<String> {
        val blockers = deps.signals.blockers().toMutableSet()
        if (!deps.signals.visible.value) blockers += HOME_NOT_VISIBLE
        if (overlays.isNotEmpty()) blockers += HOME_OVERLAY
        return blockers
    }

    /** 本页自己打开的弹层（商品口径说明等，R1-05）：出现时登记，消失时注销；都立即复核弹窗（显示中的收起，等待中的继续等）。 */
    fun overlayShown(token: Any) {
        if (overlays.add(token)) onSignals()
    }

    fun overlayGone(token: Any) {
        if (overlays.remove(token)) onSignals()
    }

    @Suppress("ReturnCount") // 复核条件依次提前返回
    private fun recheckPopup() {
        popupTimer?.invoke()
        popupTimer = null
        val c = candidate ?: return
        val image = c.image ?: return
        if (mutable.value.popup != null || !owned() || c.renderGen != renderGen) return
        val blockers = blockersNow()
        val now = deps.clock()
        val decision =
            if (preview) {
                // 草稿 popup 立即弹一次（忽略每日次数与时间段），仍要求可见、没有阻挡项
                blockers.isEmpty()
            } else {
                PopupPolicy
                    .decide(
                        c.card,
                        identity.level,
                        now,
                        deps.prefs?.shownToday(c.card.id, now) ?: 0,
                        shownThisColdStart,
                        blockers,
                        imageLoaded = true,
                    ).show
            }
        if (decision) {
            popupCounted = false
            // 每次申请显示都是一个新的弹窗实例：旧实例迟到的关闭、显示、点击回调都对不上（R1-06，共性第 17、28 条）
            popupSerial += 1
            c.shownSerial = popupSerial
            mutable.value =
                mutable.value.copy(
                    popup =
                        HomePopupUi(
                            HomePopupTicket(HomeRenderTicket(id, renderGen), popupSerial, c.card.id),
                            image,
                            c.card.action != null,
                            c.card.adLabel,
                        ),
                )
            return
        }
        if (blockers.isNotEmpty()) {
            // 阻挡项还在：等它们结束（变化通知 + 每秒复核一次），不排队到后台补弹
            popupTimer = deps.timers.after(POPUP_RECHECK_MS) { if (owned()) recheckPopup() }
        } else {
            // 次数、时间段、本次冷启动已弹：这一次冷启动不再弹
            candidate = null
        }
    }

    /** 可见性或阻挡项变化：显示中的弹窗遇到「不可见 / 其他弹层」收起；等待中的复核。 */
    private fun onSignals() {
        if (!alive) return
        val shown = mutable.value.popup
        if (shown != null && blockersNow().isNotEmpty()) {
            closePopupUi()
            if (!popupCounted) {
                // 还没真正显示（未计数）：退回「图片已就绪、等阻挡项结束」（H28 同口径）
                popupTimer = deps.timers.after(POPUP_RECHECK_MS) { if (owned()) recheckPopup() }
            } else {
                candidate = null
            }
            return
        }
        recheckPopup()
    }

    /** 弹窗界面真正出现（对话框内容已组合）：这时才 +1（G14 ⑤）并记下本次冷启动已弹。 */
    @Suppress("ReturnCount") // 票据、复核依次提前返回
    fun popupShown(ticket: HomePopupTicket) {
        val shown = mutable.value.popup ?: return
        if (shown.ticket != ticket || popupCounted || !owned()) return
        if (blockersNow().isNotEmpty()) {
            // 真正显示之前再复核一次（R1-05）：其间出现了阻挡（首页自己的说明弹层等），收起、不计数，等阻挡结束再申请新实例
            onSignals()
            return
        }
        popupCounted = true
        shownThisColdStart = true
        val write = prefsTicket
        val store = deps.prefs
        if (!preview && write != null && store != null) store.markShown(write, store.reserve(), ticket.sectionId, deps.clock())
    }

    /** 关闭按钮、返回键、点遮罩：只关这一个弹窗实例（共性第 17 条）。 */
    fun closePopup(ticket: HomePopupTicket) {
        if (mutable.value.popup?.ticket != ticket) return
        closePopupUi()
        candidate = null
    }

    /** 点图片：执行 items[0].action（Router，in_app），执行即关闭。 */
    fun tapPopup(ticket: HomePopupTicket) {
        val shown = mutable.value.popup ?: return
        if (shown.ticket != ticket || !owned()) return
        val action = candidate?.takeIf { it.shownSerial == ticket.serial }?.card?.action
        closePopup(ticket)
        action?.let { deps.host.open(it, HomeSpm.of(ticket.sectionId, 1)) }
    }

    private fun closePopupUi() {
        popupTimer?.invoke()
        popupTimer = null
        candidate?.shownSerial = 0L
        if (mutable.value.popup != null) mutable.value = mutable.value.copy(popup = null)
    }

    // ---------------------------------------------------------------- 交互

    /** 卡片点击：经 Router（in_app）；捕获的是渲染时的票据，执行时只比较。 */
    fun open(tap: HomeTap) {
        if (!current(tap.render)) return
        interacted(tap.render)
        deps.host.open(tap.target, HomeSpm.of(tap.sectionId, tap.slot))
    }

    /** 用户在首页滚动或点击过（G16 ③：之后到达的网络版不再替换当前屏）。 */
    fun interacted(ticket: HomeRenderTicket) {
        if (!current(ticket) || interacted) return
        interacted = true
        policy.on(HomeSourceEvent.UserInteracted)
    }

    /** 下拉刷新：被本地限流拦下时结束刷新动画并提示一次 42901；Release 没有网络来源时只结束动画。 */
    @Suppress("ReturnCount") // 条件依次提前返回
    fun refresh(ticket: HomeRenderTicket) {
        if (!current(ticket) || preview) return
        if (deps.port == null) return
        if (deps.gate.active) {
            showToast(HomeErrors.message(HomeErrors.RATE_LIMITED, null))
            return
        }
        coldPhase = false
        policy.on(HomeSourceEvent.PullRefresh)
        mutable.value = mutable.value.copy(refreshing = true)
        fetchPage(pull = true)
    }

    private fun toast(result: ApiResult?) {
        showToast(HomeErrors.failure(result).message)
    }

    private fun showToast(message: String) {
        toastSerial += 1
        mutable.value = mutable.value.copy(toast = HomeToast(toastSerial, message), refreshing = false)
    }

    fun consumeToast(toastId: Long) {
        if (mutable.value.toast?.id == toastId) mutable.value = mutable.value.copy(toast = null)
    }

    private fun current(ticket: HomeRenderTicket): Boolean = ticket.controllerId == id && ticket.renderGen == renderGen && owned()

    private fun homeCall(probe: SendProbe): HomeCall =
        HomeCall(
            anonymous = identity.basic,
            expectedAccount = identity.account,
            allowed = { probe.allowed() && alive },
            stillOwned = { alive },
            loginEpoch = identity.partition,
            epochs = identity.epochs,
        )

    // ---------------------------------------------------------------- 作废

    /**
     * 作废本实例（会话失效、登出、换账号、撤回同意、切换环境、预览出栈的同步出口）：在途的首页与数据源请求、已排程的弹窗与计时、数据源分页一并作废；
     * 正在显示的弹窗立即关闭且不计数（还没 +1 的不再 +1）。
     */
    fun invalidate() {
        if (!alive) return
        alive = false
        policy.on(HomeSourceEvent.IdentityChanged)
        coldTimer?.invoke()
        noticeTimer?.invoke()
        popupTimer?.invoke()
        candidate = null
        owners.toList().forEach { deps.api.cancelOwned(it) }
        owners.clear()
        scope.cancel()
        mutable.value = mutable.value.copy(popup = null, refreshing = false, toast = null)
    }

    /** 当前显示的页面版本与来源级别（G31：诊断信息「首页版本」）；没有显示中的页面为 null。 */
    fun currentVersion(): Pair<Int, String>? {
        val shown = mutable.value
        val version = shown.pageVersion ?: return null
        return if (shown.showing in setOf(HomeShowing.NETWORK, HomeShowing.LKG, HomeShowing.DEFAULT)) version to shown.showing else null
    }

    companion object {
        private val NEXT = AtomicLong(0L)
        const val PAGE_KEY = "home"

        /** D10-607 D4：空页带游标时自己接着取的上限（首取 1 次 + 至多 3 次）。 */
        const val EMPTY_PAGE_REFETCH_MAX = 3
        const val COLD_WAIT_MS = 1_500L
        const val POPUP_RECHECK_MS = 1_000L
        const val HOME_NOT_VISIBLE = "home_not_visible"
        const val HOME_OVERLAY = "home_overlay"
        const val LIST_ARTICLES = "listArticles"
        const val NOTICE_QUERY = "category=notice"
        const val PRODUCT_DETAIL = "ProductDetail"
        private const val NOTICE = "notice"
        private const val ITEMS = "items"
        private const val NEXT_CURSOR = "next_cursor"
        private const val MILLIS = 1_000L
        private const val FORCE_UPDATE_CODE = 10405
        private const val SERVER_DIVISOR = 10_000
        private const val SERVER_CLASS = 5
        private const val TIMEOUT = "timeout"
    }
}
