package com.couli.core.orders

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorActionMapper
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.PageApi
import com.couli.core.network.ResultSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** 一张待跟单卡（界面）。[linkId] / [attemptId] 取自渲染时的卡片项：关闭请求只带这张卡的 attempt_id（BR-ATTR-21「完成与关闭按尝试」）。 */
data class PendingTrackCardView(
    val platform: String,
    val attemptId: String,
    val linkId: String,
    val claimEntry: Boolean,
    val notificationRow: Boolean,
) {
    /** 一次尝试的标识（link_id + attempt_id）：忙碌状态与「已关闭」记录都按它（APP-08 共性第 14、15 条）。 */
    val key: String get() = PendingClosedAttempts.key(linkId, attemptId)
}

/**
 * 已关闭成功的尝试（link_id + attempt_id），会话级一份（APP-08 共性第 15 条）：订单列表、商品详情、搜索页各显示位置的待跟单卡共用；任一位置关闭
 * 成功后，各位置立即隐藏它，此前发出、之后才到的旧快照（服务端读到的还是 dismissed=false）也不得让它重新出现；同一 link 的新 attempt_id 照常
 * 显示。会话换代时清空。只在主线程使用。
 */
class PendingClosedAttempts {
    private val mutable = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = mutable.asStateFlow()

    fun add(
        linkId: String,
        attemptId: String,
    ) {
        mutable.value = mutable.value + key(linkId, attemptId)
    }

    fun clear() {
        mutable.value = emptySet()
    }

    companion object {
        fun key(
            linkId: String,
            attemptId: String,
        ): String = "$linkId\u0000$attemptId"
    }
}

/** 会话与通知权限的取值（三端向量名）。 */
object PendingTrackRaw {
    const val MEMBER = "member"
    const val GUEST = "guest"
    const val NOT_DETERMINED = "not_determined"
    const val GRANTED = "granted"
    const val DENIED = "denied"

    /** 系统默认允许（Android 13 以下没有通知运行时权限，鸿蒙已开启等）：不出通知行（G22）。 */
    const val SYSTEM_DEFAULT = "system_default"
}

/**
 * 待跟单卡的显示规则（BR-ATTR-21 ①②③、BR-ID-13 细则「通知权限的申请时机」、G22）：只显示 dismissed=false 的项；同平台多项只显示响应里的
 * 第一项并记日志（服务端已保证每个平台最多 1 张）；找回入口只看 show_claim_entry（客户端不用本机时钟算 30 分钟、72 小时）；通知行只在已登录、
 * 系统当前未开启（未决定或被关）且本机没有申请记录（PermissionRequester 的记录 + 本卡通知行点过的记录）时出现。
 */
object PendingTrackCards {
    fun of(
        items: List<PendingTrack>,
        session: String,
        notification: String,
        requestedBefore: Boolean,
    ): List<PendingTrackCardView> {
        val row =
            session == PendingTrackRaw.MEMBER &&
                (notification == PendingTrackRaw.NOT_DETERMINED || notification == PendingTrackRaw.DENIED) &&
                !requestedBefore
        val seen = mutableSetOf<String>()
        return items.filter { !it.dismissed }.mapNotNull { item ->
            if (!seen.add(item.platform)) {
                OrderLog.warn("pending-tracks: duplicate platform card ignored")
                return@mapNotNull null
            }
            PendingTrackCardView(item.platform, item.attemptId, item.linkId, item.showClaimEntry, row)
        }
    }

    /** 三端向量的 JSON 出口（cards 精确比较：只有这五个字段）。 */
    @JvmName("ofJson")
    fun of(
        items: List<JsonObject>,
        session: String,
        notification: String,
        requestedBefore: Boolean,
    ): List<JsonObject> =
        of(items.mapNotNull(OrderJson::pendingTrack), session, notification, requestedBefore).map {
            obj(
                "platform" to JsonPrimitive(it.platform),
                "attemptId" to JsonPrimitive(it.attemptId),
                "linkId" to JsonPrimitive(it.linkId),
                "claimEntry" to JsonPrimitive(it.claimEntry),
                "notificationRow" to JsonPrimitive(it.notificationRow),
            )
        }
}

/** 关闭的结果 → 动作（三端同名）。 */
object PendingDismissAction {
    const val HIDE = "hide"
    const val REFETCH = "refetch"
    const val KEEP_AND_TOAST = "keep_and_toast"
    const val DISABLE = "disable"
    const val FORCE_UPDATE = "force_update"

    /** 5xxxx 等服务端错误：卡片保留，轻提示对应错误文案 + trace6（未收录的 5xxxx 回落 error.50001；APP-08 共性第 9 条）。 */
    const val ERROR_TOAST = "error_toast"
}

/** 关闭的一次结果。[disableMs]：42901 的禁用时长（Retry-After，缺省或非法 5 秒）。 */
data class PendingDismissOutcome(
    val actionRaw: String,
    val disableMs: Long? = null,
)

/**
 * 待跟单卡的关闭（04 §6.4 `POST /v1/orders/pending-tracks/{link_id}/dismiss`，body 只带这张卡的 attempt_id）：
 * 成功 → 隐藏这张卡；20001 → 重新拉一次 pending-tracks，按服务端结果显示；传输失败 → 卡片保留并轻提示网络错误；42901 → 按 Retry-After 禁用
 * 关闭按钮；10405 → 强制进入强更（接已有分流，APP-07 第 20、21、24、27 条）。
 */
object PendingTrackDismiss {
    const val OPERATION = "dismissPendingTrack"
    const val METHOD = "POST"
    private const val FIELD_ERROR = 20001
    private const val FORCE_UPDATE = 10405

    fun path(linkId: String): String = "/v1/orders/pending-tracks/$linkId/dismiss"

    fun body(attemptId: String): JsonObject = obj("attempt_id" to JsonPrimitive(attemptId))

    fun request(card: JsonObject): JsonObject {
        val link = (card["link_id"] as? JsonPrimitive)?.content.orEmpty()
        val attempt = (card["attempt_id"] as? JsonPrimitive)?.content.orEmpty()
        return obj("method" to JsonPrimitive(METHOD), "path" to JsonPrimitive(path(link)), "body" to body(attempt))
    }

    fun onOutcome(
        kind: String,
        code: Int?,
        headers: Map<String, String>,
    ): PendingDismissOutcome =
        when {
            kind == KIND_OK -> {
                PendingDismissOutcome(PendingDismissAction.HIDE)
            }

            kind == KIND_TRANSPORT -> {
                PendingDismissOutcome(PendingDismissAction.KEEP_AND_TOAST)
            }

            code == ResultSignals.RATE_LIMITED -> {
                PendingDismissOutcome(
                    PendingDismissAction.DISABLE,
                    ErrorActionMapper.retryAfterMillis(headers),
                )
            }

            code == FIELD_ERROR -> {
                PendingDismissOutcome(PendingDismissAction.REFETCH)
            }

            code == FORCE_UPDATE -> {
                PendingDismissOutcome(PendingDismissAction.FORCE_UPDATE)
            }

            // 服务端错误（含未收录的 5xxxx 与其他业务码）不是断网：按错误码出文案 + trace6，只有传输失败才是网络错误
            code != null -> {
                PendingDismissOutcome(PendingDismissAction.ERROR_TOAST)
            }

            else -> {
                PendingDismissOutcome(PendingDismissAction.KEEP_AND_TOAST)
            }
        }

    /** 三端向量的 JSON 出口：outcome = {kind: ok | error | transport, code?, headers?}。 */
    fun onOutcome(outcome: JsonObject): JsonObject {
        val kind = (outcome["kind"] as? JsonPrimitive)?.content.orEmpty()
        val code = (outcome["code"] as? JsonPrimitive)?.intOrNull
        val headers = (outcome["headers"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
        val result = onOutcome(kind, code, headers)
        val out = linkedMapOf<String, kotlinx.serialization.json.JsonElement>("actionRaw" to JsonPrimitive(result.actionRaw))
        result.disableMs?.let { out["disableMs"] = JsonPrimitive(it) }
        return JsonObject(out)
    }

    const val KIND_OK = "ok"
    const val KIND_ERROR = "error"
    const val KIND_TRANSPORT = "transport"
}

/** 待跟单卡执行器要的环境（App 给；都在主线程取值）。 */
interface PendingTrackEnv {
    /** 已登录（member）或游客（guest）。 */
    fun session(): String

    /** 系统通知权限：not_determined / granted / denied / system_default。 */
    fun notification(): String

    /** 本机有没有通知权限的申请记录（PermissionRequester 的记录，或点过卡内【开启通知】）。 */
    fun requestedBefore(): Boolean

    /** 会话世代（登录、退出、换账号都会变）：请求绑定它，响应回来时不是同一代就丢弃。 */
    fun sessionEpoch(): Long

    /**
     * 点了卡内【开启通知】：先记下「点过」（之后不再出这一行，无论结果），再经 PermissionRequester 申请（只在用户点了才弹系统框）。
     * [stillValid] 是点击时那张卡的有效性（页面没离开、会话 / 隐私 / 环境没换代、卡片版本没变；APP-08 共性第 31、42 条）：记「点过」之前、
     * 排队期间、调系统接口之前都核对，失效就什么都不做（不记、不弹）。
     */
    suspend fun requestNotification(stillValid: () -> Boolean = { true })

    /** 10405：强制进入已有的强更分流（服务端已判定强更，进闸门并落盘，没有检查结果时立即补查）。 */
    fun forceUpdate()
}

/**
 * 待跟单卡区域的状态。[busy] 是关闭在途的尝试（[PendingTrackCardView.key]：link_id + attempt_id）。[toastId] 每次新提示加 1
 * （同一句再提示也重新播报）。[toastError] 不为 null 时提示正文按统一的错误消息生成（带 trace6，APP-08 共性第 8 条），[toastKey] 只是兜底。
 */
data class PendingTrackState(
    val cards: List<PendingTrackCardView> = emptyList(),
    val busy: Set<String> = emptySet(),
    val blocked: Boolean = false,
    val toastKey: String? = null,
    val toastId: Long = 0,
    val toastError: OrderErrorView? = null,
    /**
     * 这份卡片的票据（APP-08 共性第 42 条、APP-09 第 63 条）：渲染时取出这个普通值交给关闭与【开启通知】的回调；执行前与当前票据比较，
     * 会话换代、清理（撤回 / 切环境）之后旧画面上的回调失效（同账号重新登录、重新显示同一张卡也一样）。
     */
    val ticket: PendingTicket = PendingTicket(0L, 0L),
)

/**
 * 待跟单卡的不可变票据（APP-08 共性第 42、46 条）：[epoch] 发布时的会话世代，[serial] 执行器的清理代次（会话换代、撤回、切环境都加 1），
 * [placement] 显示位置（订单列表为全部平台 [PendingTicket.ALL]；发起页为同平台那一张的平台），[lifetime] 卡片有效期（[PendingTrackService.clear]、
 * 换显示位置、离开都加 1：切走再切回、取回相同 link_id / attempt_id 的卡也不同于旧票据，旧回调不复活；FU-AND-0710 R1-04）。
 */
data class PendingTicket(
    val epoch: Long,
    val serial: Long,
    val placement: String = ALL,
    val lifetime: Long = 0L,
) {
    companion object {
        /** 显示全部平台（订单列表自购子 Tab）。 */
        const val ALL = "*"
    }
}

/**
 * 待跟单卡执行器（订单列表自购子 Tab 顶部、发起购买的页面外跳回前台后，G07）。一个页面一份，只在主线程使用。
 * - 拉取 `GET /v1/orders/pending-tracks`：客户端不缓存到磁盘，不显示 jumped_at 推算出的任何时长；[platform] 不为 null 时只显示同平台那一张；
 * - 请求归属：会话世代（[PendingTrackEnv.sessionEpoch]）+ 隐私 / 环境世代（PageApi.epochs）+ 页面仍在；每次等待之后复核，迟到的只丢弃；
 * - 关闭：按渲染时的卡片项（不取「当前最新」的卡）；在途时按钮忙碌、不重复发；关闭成功的 attempt_id 记下来，之后到达的旧快照不得让它重新出现
 *   （APP-08 共性第 3 条；同一 link 的新 attempt_id 照常显示）；
 * - 42901：与订单列表、详情共用会话级的 [OrderRateGate]（[rateGate]；没给时本页一份）：拉取与关闭发送前都拦，卡片关闭按钮随之禁用；
 * - 【开启通知】：只有用户点了才申请，点过之后这一行不再出现。
 */
@Suppress("TooManyFunctions") // 拉取、关闭、通知行、作废出口各一个入口
class PendingTrackService(
    private val api: PageApi,
    private val env: PendingTrackEnv,
    private val scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
    rateGate: OrderRateGate? = null,
    closedAttempts: PendingClosedAttempts? = null,
) {
    private val mutable = MutableStateFlow(PendingTrackState())
    val state: StateFlow<PendingTrackState> = mutable.asStateFlow()

    private val gate = rateGate ?: OrderRateGate(scope, clock)
    private var left = false
    private var fetchSeq = 0L

    /** 清理代次：会话换代、撤回同意、切换环境时加 1（票据的一部分，旧画面上的回调随之失效）。 */
    private var serial = 0L

    /** 卡片有效期：清掉显示（切到分享子 Tab 等）、换显示位置、离开时加 1（票据的一部分，R1-04）。 */
    private var lifetime = 0L
    private var items: List<PendingTrack> = emptyList()

    /** 已显示的卡片属于哪个会话世代（拉取请求开始时取的那一代；结果到达、重算时都不再读当前会话来赋值，R1-01 排查）。 */
    private var itemsEpoch: Long = env.sessionEpoch()

    /** 已关闭成功的尝试（会话级共享：App 给的那一份；没给时本页一份）：迟到的旧快照里 dismissed=false 也不再显示。 */
    private val closed = closedAttempts ?: PendingClosedAttempts()
    private val owners = mutableListOf<CallOwnership>()

    init {
        // 禁用状态跟会话级的那一份（别的订单请求触发的 42901 也让关闭按钮置灰）
        scope.launch { gate.blocked.collect { b -> if (!left) mutable.value = mutable.value.copy(blocked = b) } }
        // 别的显示位置关闭成功：本页立即隐藏同一尝试
        scope.launch { closed.keys.collect { if (!left) publish() } }
    }

    /** 只显示这个平台的卡（发起购买的页面）；null 为全部（订单列表）。 */
    var platform: String? = null
        private set

    /** 拉一次（[onlyPlatform] 只给发起购买的页面：外跳回到前台后，只显示同平台那一张）。 */
    fun refresh(onlyPlatform: String? = platform) {
        if (left) return
        // 换显示位置（同一页换了平台）：旧位置上的卡片回调随之失效
        if (onlyPlatform != platform) lifetime += 1
        platform = onlyPlatform
        // 42901 禁用期内不发（执行层拦截；会话级共享，重进页面也一样）
        if (gate.active) return
        val seq = ++fetchSeq
        val epoch = env.sessionEpoch()
        // 禁用期是独立的发送前闸门（不进归属）：网络层每次实际写出前（含自动重试、重放）核对，禁用期内不再发包；已发出的照常收结果（共性第 13、27 条）
        val owner = CallOwnership(api.epochs(), sendAllowed = { !gate.active }) { !left && seq == fetchSeq && env.sessionEpoch() == epoch }
        owners += owner
        scope.launch {
            val result = api.send(ApiCall(LIST_OPERATION, ownership = owner))
            owners -= owner
            // 等待之后复核：页面已离开、又发了新一次拉取、会话已换代，都只丢弃
            if (left || seq != fetchSeq || env.sessionEpoch() != epoch) return@launch
            when (result) {
                is ApiResult.Success -> {
                    items = OrderJson.pendingTracks(result.data) ?: return@launch
                    itemsEpoch = epoch
                    publish()
                }

                is ApiResult.Failure -> {
                    startBlock(ResultSignals.disableMillis(result))
                }

                // 拉不到时保持原样（不清掉已显示的卡，也不显示推算出的卡）
                else -> {
                    Unit
                }
            }
        }
    }

    /** 通知权限或会话变了（回到前台、申请结束）：按新状态重算通知行。 */
    fun recompute() {
        if (!left) publish()
    }

    private fun publish() {
        val all =
            PendingTrackCards
                .of(items, env.session(), env.notification(), env.requestedBefore())
                .filterNot { it.key in closed.keys.value }
        val shown = platform?.let { p -> all.filter { it.platform == p } } ?: all
        // 忙碌不随重新发布丢掉：关闭在途期间另一轮查询暂时去掉又重建了同一尝试的卡片，按钮仍是忙碌；结果回来时统一清掉（共性第 14 条）
        // 票据的会话世代是这批卡片的世代（会话已换代而通知还没到时重算，旧卡片拿不到新会话的票据）
        mutable.value = mutable.value.copy(cards = shown, ticket = currentTicket().copy(epoch = itemsEpoch))
    }

    /** 此刻的票据（会话世代 + 清理代次 + 显示位置 + 卡片有效期）。 */
    fun currentTicket(): PendingTicket = PendingTicket(env.sessionEpoch(), serial, platform ?: PendingTicket.ALL, lifetime)

    /**
     * 关闭 [card]（渲染时的卡片项）。[ticket] 是渲染时取出的票据（APP-08 共性第 42 条）：与当前票据不同（会话换代、清理之后的旧画面）、42901
     * 禁用中、在途中、卡片已不是当前显示的那一张都不发，返回 false。缺省按此刻的票据（只给没有渲染值的直接调用）。
     */
    @Suppress("ReturnCount") // 逐项拦截
    fun dismiss(
        card: PendingTrackCardView,
        ticket: PendingTicket = currentTicket(),
    ): Boolean {
        if (left || gate.active || ticket != currentTicket()) return false
        val current = mutable.value
        if (card.key in current.busy) return false
        if (current.cards.none { it.attemptId == card.attemptId && it.linkId == card.linkId }) return false
        mutable.value = current.copy(busy = current.busy + card.key)
        // 归属沿用渲染时的票据（已与当前会话比较过），不再读当前会话来绑定
        val epoch = ticket.epoch
        // 禁用期只拦写出（发送前闸门），不进归属：关闭已发出时别处的 42901 不取消它，成功照常隐藏并记下、10405 照常进强更（R3-01）
        val owner = CallOwnership(api.epochs(), sendAllowed = { !gate.active }) { !left && env.sessionEpoch() == epoch }
        owners += owner
        scope.launch {
            val result =
                api.send(
                    ApiCall(
                        DISMISS_OPERATION,
                        pathParams = mapOf("link_id" to card.linkId),
                        body = PendingTrackDismiss.body(card.attemptId).toString(),
                        ownership = owner,
                    ),
                )
            owners -= owner
            if (left || env.sessionEpoch() != epoch) return@launch
            // 结果回来一律清掉这次尝试的忙碌（期间另一轮查询重建了同一尝试的卡片也一样），失败后用户能再点
            mutable.value = mutable.value.copy(busy = mutable.value.busy - card.key)
            onDismissed(card, result)
        }
        return true
    }

    @Suppress("CyclomaticComplexMethod") // 结果种类 × 动作逐项
    private fun onDismissed(
        card: PendingTrackCardView,
        result: ApiResult,
    ) {
        val outcome =
            when (result) {
                is ApiResult.Success -> {
                    PendingTrackDismiss.onOutcome(PendingTrackDismiss.KIND_OK, null, emptyMap())
                }

                is ApiResult.Failure -> {
                    PendingTrackDismiss
                        .onOutcome(PendingTrackDismiss.KIND_ERROR, result.code, emptyMap())
                        .let {
                            if (it.actionRaw ==
                                PendingDismissAction.DISABLE
                            ) {
                                it.copy(disableMs = ResultSignals.disableMillis(result))
                            } else {
                                it
                            }
                        }
                }

                is ApiResult.TransportFailure -> {
                    PendingTrackDismiss.onOutcome(PendingTrackDismiss.KIND_TRANSPORT, null, emptyMap())
                }

                // 本地拒绝（作废）不提示；其余（会话结束等）由网络层与外壳处理
                is ApiResult.LocalReject -> {
                    if (result.reason == LocalRejectReason.CALL_CANCELLED) return
                    PendingDismissOutcome(PendingDismissAction.KEEP_AND_TOAST)
                }

                else -> {
                    return
                }
            }
        when (outcome.actionRaw) {
            PendingDismissAction.HIDE -> {
                // 只隐藏这一张（按尝试）；不影响其他平台，也不在本地「退回」显示更早的尝试。记下这个 attempt_id：关闭之前发出、之后才到的旧快照
                // （服务端读到的还是 dismissed=false）不得让它重新出现
                closed.add(card.linkId, card.attemptId)
                items = items.filterNot { it.attemptId == card.attemptId }
                publish()
            }

            PendingDismissAction.REFETCH -> {
                refresh()
            }

            PendingDismissAction.DISABLE -> {
                startBlock(outcome.disableMs ?: ErrorActionMapper.DEFAULT_RETRY_AFTER_MILLIS)
            }

            PendingDismissAction.FORCE_UPDATE -> {
                env.forceUpdate()
            }

            PendingDismissAction.ERROR_TOAST -> {
                val failure = result as? ApiResult.Failure
                toast(NETWORK_ERROR_KEY, failure?.let { errorView(it) })
            }

            else -> {
                toast(NETWORK_ERROR_KEY)
            }
        }
    }

    /** 服务端错误 → 统一的错误视图（5xxxx 带 trace6，未收录的回落 error.50001；其余按 error.<code>，没有则通用）。 */
    private fun errorView(failure: ApiResult.Failure): OrderErrorView =
        OrderErrorAction.of(OrderErrorAction.LIST_FIRST, failure.code, false, 0L, failure.traceId, false, false)

    /**
     * 卡内【开启通知】：先记下点过（这一行随即消失），只有用户点了才经 PermissionRequester 弹系统框；结束后按新状态重算。
     * [ticket] 是渲染时取出的票据（APP-08 共性第 42 条）：与当前票据不同就不记「点过」、不申请；之后的等待沿用点击时的有效性（页面、会话、隐私
     * 与环境世代、票据，共性第 31 条），失效即取消待执行的申请。缺省按此刻的票据（只给没有渲染值的直接调用）。
     */
    @Suppress("ReturnCount") // 票据、页面、卡片逐项拦截
    fun requestNotification(
        card: PendingTrackCardView,
        ticket: PendingTicket = currentTicket(),
    ) {
        if (left || ticket != currentTicket()) return
        if (mutable.value.cards.none { it.attemptId == card.attemptId && it.notificationRow }) return
        // 执行前按最新的本机申请记录与系统权限状态再判一次（别的显示位置刚点过、或系统里已开启：只重算、不申请；共性第 25 条）
        val stillShown =
            PendingTrackCards.of(items, env.session(), env.notification(), env.requestedBefore()).any {
                it.attemptId == card.attemptId && it.notificationRow
            }
        if (!stillShown) {
            publish()
            return
        }
        val epochs = api.epochs()
        val stillValid = { !left && ticket == currentTicket() && api.epochs() == epochs }
        scope.launch {
            env.requestNotification(stillValid)
            if (!left) publish()
        }
        publish()
    }

    fun consumeToast() {
        mutable.value = mutable.value.copy(toastKey = null, toastError = null)
    }

    private fun toast(
        key: String,
        error: OrderErrorView? = null,
    ) {
        mutable.value = mutable.value.copy(toastKey = key, toastError = error, toastId = mutable.value.toastId + 1)
    }

    private fun startBlock(millis: Long) {
        gate.start(millis)
    }

    /** 会话换代（登录、退出、换账号）：作废在途请求，清掉旧账号的卡（不把旧账号的卡显示给新账号）。 */
    fun onSessionChanged() {
        fetchSeq += 1
        serial += 1
        cancelAll()
        items = emptyList()
        // 会话清理出口：卡片已清空，按此刻的会话重新起算
        itemsEpoch = env.sessionEpoch()
        closed.clear()
        mutable.value = PendingTrackState(blocked = gate.blocked.value, toastId = mutable.value.toastId, ticket = currentTicket())
    }

    /**
     * 清掉显示（订单列表切到分享子 Tab、发起页离开外跳场景时不留旧卡）：卡片有效期同步加 1，旧画面上的关闭与【开启通知】回调、排队中的通知申请
     * 一并失效；之后再显示（切回自购、取回相同 attempt）用新票据（R1-04）。
     */
    fun clear() {
        fetchSeq += 1
        lifetime += 1
        cancelAll()
        items = emptyList()
        mutable.value = mutable.value.copy(cards = emptyList(), busy = emptySet(), ticket = currentTicket())
    }

    /** 页面出栈（同步出口）：作废并取消在途请求、卡片有效期加 1，之后迟到的响应一律丢弃。 */
    fun leave() {
        left = true
        lifetime += 1
        cancelAll()
    }

    private fun cancelAll() {
        val cancelled = owners.toList()
        owners.clear()
        cancelled.forEach(api::cancelOwned)
    }

    companion object {
        const val LIST_OPERATION = "listPendingTracks"
        const val DISMISS_OPERATION = PendingTrackDismiss.OPERATION
        const val NETWORK_ERROR_KEY = "order_list.network_error"
        const val CLOSE_KEY = "pending_track_card.close"
        const val CLAIM_KEY = "pending_track_card.claim"
        const val TITLE_KEY = "pending_track_card.title"
        const val DESC_KEY = "pending_track_card.desc"
        const val HINT_KEY = "perm.push.card_hint"
        const val CTA_KEY = "perm.push.cta"
    }
}
