package com.couli.core.agent

import com.couli.generated.RouteSpec
import com.couli.generated.Routes
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// APP-10b §2 第 5 条（F-X-04）：会话历史（listAgentSessionMessages）的解码与合并（纯函数，Core）。历史只在内存（brief §0 第 4 条）。三端同名。
// - AgentHistory：外壳 data → items（message_id、role、created_at、text、cards、finish_reason）、next_cursor、expired。坏项（缺必填、类型不符）与
//   未知 role 整项丢弃、只计数（本机日志不写内容）。卡片按历史形状（AgentHistoryCard：card_id、type、schema_version、data、fallback_text，没有
//   seq）：外壳不合整卡丢弃；schema_version 高于本端支持 → 整卡只显示 fallback_text（G12，不放宽，不上报被拦路由）；其余交 APP-10 的
//   AgentCardDecoder（history 来源：收益卡一律历史形状、金额字段不保留；page_guide 照包内 agent_guide 判按钮；未注册类型只显示 fallback_text）。
// - 状态映射（G42 + 编排者补充第 3 条）：stop / fallback / auth_required / safety / limit / budget / timeout → done；cancelled → cancelled；
//   error → error（通用出错条目，历史没有错误码，不带 trace6、不给重试）；null（历史项没有 run_id）→ 按已结束（done）渲染、不给【停止】。
// - AgentHistoryMerge：断线、缺号、duplicate、cancel 30505 的重载只替换发起重载的那一轮（G06）。对齐：① 有可信的回复 id（meta.message_id，且在
//   历史里是 assistant）按 id；② 否则按「最后一条 role=user、created_at 不早于本地发送时刻 −5 分钟、文本逐字相等」（G05），并排除已明确属于其他
//   本地轮次的候选（鸿蒙 R3-02，F-X-06 ①）；对不上不猜，按编排者补充第 3 条把历史里本地没有的轮追加在后面（同 message_id 已在本地的整轮不重复）。

object AgentRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

/** 一条历史消息（不可变；只在内存）。toString 不带正文。 */
class AgentHistoryItem(
    val messageId: String,
    val role: String,
    val createdAt: String,
    val text: String,
    val cards: List<AgentCard>,
    val finishReason: String?,
) {
    val createdAtMs: Long? = runCatching { OffsetDateTime.parse(createdAt).toInstant().toEpochMilli() }.getOrNull()

    val isUser: Boolean get() = role == AgentRole.USER

    /** assistant 的规范化状态（G42 + 编排者补充第 3 条）；user 为空串。 */
    val status: String get() = if (isUser) "" else AgentHistory.statusOf(finishReason)

    override fun toString(): String = "AgentHistoryItem($role, cards=${cards.size})"
}

/** 一页历史。 */
class AgentHistoryPage(
    val items: List<AgentHistoryItem>,
    val nextCursor: String?,
    val expired: Boolean,
    /** 整项丢弃的条数（坏项、未知 role）。 */
    val dropped: Int,
) {
    fun has(messageId: String): Boolean = items.any { it.messageId == messageId }
}

/** 一轮（user + 其后的 assistant；页首没有 user 的 assistant 自成一轮，user 为 null）。 */
class AgentHistoryGroup(
    val user: AgentHistoryItem?,
    val replies: List<AgentHistoryItem>,
) {
    val items: List<AgentHistoryItem> get() = listOfNotNull(user) + replies

    val ids: List<String> get() = items.map { it.messageId }
}

object AgentHistory {
    /** 首页与翻页都显式传契约缺省值 20（G08）。 */
    const val LIMIT = 20

    private val FINISH_REASONS = listOf("stop", "cancelled", "limit", "budget", "error", "auth_required", "safety", "fallback", "timeout")
    private const val FINISH_CANCELLED = "cancelled"
    private const val FINISH_ERROR = "error"

    fun statusOf(finishReason: String?): String =
        when (finishReason) {
            FINISH_CANCELLED -> AgentStatus.CANCELLED
            FINISH_ERROR -> AgentStatus.ERROR
            else -> AgentStatus.DONE
        }

    /** 外壳 data → 一页；data 形状不对（items 不是数组、expired 不是布尔、next_cursor 不是字符串或 null）为 null（按加载失败）。 */
    @Suppress("ReturnCount") // 三个顶层字段依次校验
    fun page(
        data: JsonObject,
        routes: Map<String, RouteSpec> = Routes.all,
    ): AgentHistoryPage? {
        val items = AgentJson.arrayOf(data["items"]) ?: return null
        val expired = AgentJson.boolOf(data["expired"]) ?: return null
        val cursor = data["next_cursor"]
        if (cursor == null || !(AgentJson.isNull(cursor) || AgentJson.stringOf(cursor) != null)) return null
        val out = mutableListOf<AgentHistoryItem>()
        var dropped = 0
        for (v in items) {
            val item = item(v, routes)
            if (item == null) dropped += 1 else out += item
        }
        return AgentHistoryPage(out, AgentJson.stringOf(cursor), expired, dropped)
    }

    @Suppress("ReturnCount", "ComplexCondition") // 必填与类型逐项校验
    private fun item(
        v: JsonElement,
        routes: Map<String, RouteSpec>,
    ): AgentHistoryItem? {
        val o = AgentJson.objectOf(v) ?: return null
        if (!AgentJson.entityId(o["message_id"]) || !AgentJson.oneOf(o["role"], listOf(AgentRole.USER, AgentRole.ASSISTANT)) ||
            !AgentJson.timestamp(o["created_at"]) || AgentJson.stringOf(o["text"]) == null
        ) {
            return null
        }
        val rawCards = AgentJson.arrayOf(o["cards"]) ?: return null
        val finish = o["finish_reason"] ?: return null
        if (!AgentJson.isNull(finish) && !AgentJson.oneOf(finish, FINISH_REASONS)) return null
        return AgentHistoryItem(
            AgentJson.stringOf(o["message_id"]).orEmpty(),
            AgentJson.stringOf(o["role"]).orEmpty(),
            AgentJson.stringOf(o["created_at"]).orEmpty(),
            AgentJson.stringOf(o["text"]).orEmpty(),
            rawCards.mapNotNull { card(it, routes) },
            AgentJson.stringOf(finish),
        )
    }

    /** 历史卡（没有 seq 的外壳）→ 卡片；外壳不合为 null（整卡丢弃）。 */
    @Suppress("ReturnCount", "ComplexCondition") // 外壳、高版本、交解码器依次返回
    fun card(
        v: JsonElement,
        routes: Map<String, RouteSpec> = Routes.all,
    ): AgentCard? {
        val c = AgentJson.objectOf(v) ?: return null
        if (!AgentJson.exactKeys(
                c,
                listOf("card_id", "type", "schema_version", "data", "fallback_text"),
            ) || !AgentJson.cardId(c["card_id"]) ||
            !AgentJson.str(c["type"], 1) || !AgentJson.int32(c["schema_version"], 1) || c["data"] !is JsonObject ||
            !AgentJson.str(c["fallback_text"], 1)
        ) {
            return null
        }
        val type = AgentJson.stringOf(c["type"]).orEmpty()
        val cardId = AgentJson.stringOf(c["card_id"]).orEmpty()
        val fallback = AgentJson.stringOf(c["fallback_text"]).orEmpty()
        if ((AgentJson.intOf(c["schema_version"]) ?: 1L) > AgentCardType.MAX_SCHEMA_VERSION) {
            // G12：高版本整卡只显示 fallback_text（与 APP-10 G09 同口径），不按已知字段放宽、不上报被拦路由
            return AgentCard(AgentCardKind.FALLBACK, type, cardId, fallback, null, null, AgentCardOrigin.HISTORY, AgentJson.EMPTY)
        }
        val envelope = JsonObject(linkedMapOf<String, JsonElement>("seq" to JsonPrimitive(1)) + c)
        val card = AgentCardDecoder.parse(envelope, AgentCardOrigin.HISTORY, routes)
        return card.takeIf { it.kind != AgentCardKind.INVALID }
    }

    /** 按轮分组（user + 其后的 assistant）。 */
    fun groups(items: List<AgentHistoryItem>): List<AgentHistoryGroup> {
        val groups = mutableListOf<AgentHistoryGroup>()
        var user: AgentHistoryItem? = null
        var replies = mutableListOf<AgentHistoryItem>()
        var open = false
        for (item in items) {
            if (item.isUser) {
                if (open) groups += AgentHistoryGroup(user, replies)
                user = item
                replies = mutableListOf()
                open = true
            } else {
                replies += item
                open = true
            }
        }
        if (open) groups += AgentHistoryGroup(user, replies)
        return groups
    }

    /** 卡片投影（与 agent-vectors.json 同一形状）。 */
    fun cardJson(c: AgentCard): JsonObject =
        AgentJson.obj(
            "cardId" to c.cardId,
            "type" to c.type,
            "kind" to c.kind,
            "fallbackText" to if (c.kind == AgentCardKind.FALLBACK) c.fallbackText else null,
            "blockedRoute" to c.blockedRoute,
        )

    /** 一条消息的规范化投影（向量与诊断用；不含任何金额字段）。 */
    fun messageJson(i: AgentHistoryItem): JsonObject {
        val o = linkedMapOf<String, JsonElement>()
        o["messageId"] = JsonPrimitive(i.messageId)
        o["roleRaw"] = JsonPrimitive(i.role)
        o["text"] = JsonPrimitive(i.text)
        o["cards"] = JsonArray(i.cards.map(::cardJson))
        if (i.isUser) {
            // G41：用户原文原样纯文本显示，不剥离、不识别链接、不可点；没有反馈与举报
            o["renderRaw"] = JsonPrimitive(RENDER_PLAIN)
            o["links"] = JsonArray(emptyList())
            o["feedbackVisible"] = JsonPrimitive(false)
            o["reportVisible"] = JsonPrimitive(false)
            return JsonObject(o)
        }
        val status = i.status
        o["statusRaw"] = JsonPrimitive(status)
        o["finishReasonRaw"] = i.finishReason?.let(::JsonPrimitive) ?: JsonNull
        o["renderRaw"] = JsonPrimitive(RENDER_RESTRICTED)
        o["canStop"] = JsonPrimitive(false)
        o["retryVisible"] = JsonPrimitive(false)
        o["genericError"] = JsonPrimitive(status == AgentStatus.ERROR)
        o["trace6"] = JsonNull
        o["displayText"] = JsonPrimitive(displayText(i))
        // 历史回复都已结束且有 message_id（role=assistant）：显示【赞】【踩】与举报入口（G28、G29）
        o["feedbackVisible"] = JsonPrimitive(true)
        o["reportVisible"] = JsonPrimitive(true)
        i.cards.firstOrNull { it.type == AgentCardType.EARNINGS_SUMMARY && it.known }?.let { c ->
            o["earningsShapeRaw"] = c.shape?.let(::JsonPrimitive) ?: JsonNull
            o["earningsTextKey"] = JsonPrimitive(EarningsCardView.HISTORY_KEY)
        }
        return JsonObject(o)
    }

    /** 回复的显示文字：已停止且没有文字时显示「已停止」草稿。 */
    fun displayText(i: AgentHistoryItem): String =
        if (i.text.isEmpty() && i.status == AgentStatus.CANCELLED) AgentDraftTexts.text(STOPPED_KEY) else i.text

    /** 向量接线（history 组）：原始外壳 → 规范化消息列表与页面状态。 */
    @Suppress("UnusedParameter") // nowMs 与向量入口同形
    fun decode(
        response: JsonObject,
        routes: Map<String, RouteSpec>,
        nowMs: Long,
    ): JsonObject {
        val o = linkedMapOf<String, JsonElement>()
        val data = AgentJson.objectOf(response["data"])
        val page = if (AgentJson.intOf(response["code"]) == 0L && data != null) page(data, routes) else null
        o["updatesQuota"] = JsonPrimitive(false)
        o["analytics"] = JsonArray(emptyList())
        o["automaticRoutes"] = JsonArray(emptyList())
        o["walletRequests"] = JsonPrimitive(0)
        o["withdrawalRequests"] = JsonPrimitive(0)
        if (page == null) {
            o["resultRaw"] = JsonPrimitive("load_failed")
            o["messages"] = JsonArray(emptyList())
            return JsonObject(o)
        }
        o["messages"] = JsonArray(page.items.map(::messageJson))
        o["nextCursor"] = page.nextCursor?.let(::JsonPrimitive) ?: JsonNull
        o["expired"] = JsonPrimitive(page.expired)
        o["readOnly"] = JsonPrimitive(page.expired)
        o["inputEnabled"] = JsonPrimitive(!page.expired)
        o["capsulesEnabled"] = JsonPrimitive(!page.expired)
        // G09：过期只读时卡片按钮照常（用户点击的 B 级动作，不依赖会话）
        o["cardActionsEnabled"] = JsonPrimitive(true)
        o["droppedItems"] = JsonPrimitive(page.dropped)
        return JsonObject(o)
    }

    private const val RENDER_PLAIN = "plain"
    private const val RENDER_RESTRICTED = "restricted"
    const val STOPPED_KEY = "agent.draft.stopped"
}

/** 本地这一轮的对齐依据（发送时刻、发送原文、可信的回复 id）。 */
class AgentTurnKey(
    val userText: String,
    val sentAtMs: Long,
    /** meta.message_id（只在它确实是回复时可用，G03）；没有为 null。 */
    val replyId: String?,
) {
    override fun toString(): String = "AgentTurnKey(sentAt=$sentAtMs)"
}

object AgentMatchBy {
    const val MESSAGE_ID = "message_id"
    const val TEXT_TIME = "text_time"
    const val NONE = "none"
}

class AgentTurnMatch(
    val by: String,
    val group: AgentHistoryGroup?,
    /** meta.message_id 被当作回复 id 用了（指向 user 时为 false，G03）。 */
    val usedMetaAsReplyId: Boolean,
) {
    val matched: Boolean get() = group != null

    /** 对上的回复（第一条 assistant；没有为 null）。 */
    val reply: AgentHistoryItem? get() = group?.replies?.firstOrNull()
}

/** 本地一条消息的扁平投影（向量接线用）。 */
class AgentLocalMessage(
    val json: JsonObject,
) {
    val turnId: String = AgentJson.stringOf(json["turnId"]).orEmpty()
    val messageId: String? = AgentJson.stringOf(json["messageId"])
    val role: String = AgentJson.stringOf(json["roleRaw"]).orEmpty()
}

object AgentHistoryMerge {
    /** G05：发送时刻向前 5 分钟（代拟下界）。 */
    const val WINDOW_MS = 5 * 60 * 1000L

    /**
     * 在一页历史里找本地这一轮（G03、G05）。[claimed]：本地其他轮次已确认的消息归属（回复 id、历史里的用户消息 id；鸿蒙 R3-02、F-X-06 ①）——按文本与
     * 时间匹配时排除含有这些 id 的候选（已明确属于其他本地轮次的回复不得复制到目标轮）；都被排除时按对不上处理。
     */
    @Suppress("LoopWithTooManyJumpStatements", "ReturnCount") // 逐字节扫描；逐项提前返回
    fun match(
        page: AgentHistoryPage,
        key: AgentTurnKey,
        claimed: Set<String> = emptySet(),
    ): AgentTurnMatch {
        val groups = AgentHistory.groups(page.items)
        key.replyId?.let { id ->
            groups
                .firstOrNull { g ->
                    g.replies.any { it.messageId == id }
                }?.let { return AgentTurnMatch(AgentMatchBy.MESSAGE_ID, it, true) }
            // id 在历史里是 user（G03 示例冲突）或不在这一页：不当回复 id 用，改按文本与时间
        }
        val lower = key.sentAtMs - WINDOW_MS
        for (g in groups.asReversed()) {
            val u = g.user ?: continue
            val at = u.createdAtMs ?: continue
            if (u.text == key.userText && at >= lower &&
                g.ids.none { it in claimed }
            ) {
                return AgentTurnMatch(AgentMatchBy.TEXT_TIME, g, false)
            }
        }
        return AgentTurnMatch(AgentMatchBy.NONE, null, false)
    }

    /** 对不上时要追加的轮：本地没有任何一条 message_id 的整轮（G06：已明确属于本地其他轮的不插入）。 */
    fun appendable(
        page: AgentHistoryPage,
        localIds: Set<String>,
    ): List<AgentHistoryGroup> = AgentHistory.groups(page.items).filter { g -> g.ids.none { it in localIds } }

    /**
     * 向量接线（historyMerge 组）。paging / entry 两种模式接同一套分页与入口逻辑（[AgentHistoryPager]、[AgentEntryPlan]）。重载合并按控制器口径传入
     * 其他本地轮次已确认的消息归属（F-X-06 ①：目标轮之外的本地消息 id）。
     */
    @Suppress("UnusedParameter", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    fun of(
        input: JsonObject,
        nowMs: Long,
    ): JsonObject {
        when (AgentJson.stringOf(input["modeRaw"])) {
            MODE_PAGING -> return AgentHistoryPager.of(input)
            MODE_ENTRY -> return AgentEntryPlan.of(input)
        }
        val o = linkedMapOf<String, JsonElement>()
        val envelope = AgentJson.objectOf(input["history"]) ?: AgentJson.EMPTY
        val page = AgentHistory.page(AgentJson.objectOf(envelope["data"]) ?: AgentJson.EMPTY, Routes.all)
        val local = AgentJson.arrayOf(input["local"]).orEmpty().map { AgentLocalMessage(AgentJson.objectOf(it) ?: AgentJson.EMPTY) }
        val reason = AgentJson.stringOf(input["reasonRaw"]).orEmpty()
        o["persisted"] = JsonPrimitive(false)
        o["walletRequests"] = JsonPrimitive(0)
        o["updatesQuota"] = JsonPrimitive(false)
        o["analytics"] = JsonArray(emptyList())
        o["automaticRoutes"] = JsonArray(emptyList())
        if (reason == REASON_COLD_START) {
            // 冷启动 / 页面实例新建：历史首页整体作为初始对话（只读，不建会话、不发消息，G39）
            o["messages"] = JsonArray(page?.items.orEmpty().map(::historyJson))
            o["createSessionRequests"] = JsonPrimitive(0)
            o["sendMessageRequests"] = JsonPrimitive(0)
            return JsonObject(o)
        }
        if (AgentJson.boolOf(input["ticketMatches"]) != true || page == null) {
            // 迟到的历史（换号、新对话、旧会话、旧 run、旧页面）：整页丢弃，什么都不改
            o["messages"] = JsonArray(local.map { it.json })
            o["accepted"] = JsonPrimitive(false)
            o["quotaMutations"] = JsonPrimitive(0)
            o["cardMutations"] = JsonPrimitive(0)
            return JsonObject(o)
        }
        val target = AgentJson.stringOf(input["targetTurnId"]).orEmpty()
        val userLocal = local.firstOrNull { it.turnId == target && it.role == AgentRole.USER }
        val replyLocal = local.firstOrNull { it.turnId == target && it.role == AgentRole.ASSISTANT }
        val meta = AgentJson.stringOf(input["metaMessageId"]) ?: replyLocal?.messageId
        val sentAt =
            userLocal
                ?.let { AgentJson.stringOf(it.json["createdAtIso"]) }
                ?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L
        val key = AgentTurnKey(userLocal?.let { AgentJson.stringOf(it.json["text"]) }.orEmpty(), sentAt, meta)
        // 控制器口径（F-X-06 ①）：目标轮之外的本地消息已确认的 id
        val claimed = local.filter { it.turnId != target }.mapNotNull { it.messageId }.toSet()
        val m = match(page, key, claimed)
        o["accepted"] = JsonPrimitive(true)
        o["matched"] = JsonPrimitive(m.matched)
        o["matchedByRaw"] = JsonPrimitive(m.by)
        o["usedMetaAsReplyId"] = JsonPrimitive(m.usedMetaAsReplyId)
        val messages = mutableListOf<JsonElement>()
        val group = m.group
        if (group != null) {
            var replaced = false
            for (msg in local) {
                if (msg.turnId != target) {
                    messages += msg.json
                    continue
                }
                if (!replaced) {
                    replaced = true
                    group.items.forEach { messages += historyJson(it) }
                }
            }
            // 替换的这一轮：卡片动作世代 +1（旧回调失效、在途购买只挡外跳），新卡重新发票据；用户已上滑不滚动；不查钱包、不更新额度
            o["cardActionGeneration"] = JsonPrimitive((AgentJson.intOf(input["oldCardActionGeneration"]) ?: 0L) + 1)
            o["oldCardCallbacksAccepted"] = JsonPrimitive(false)
            o["purchaseExternalOpen"] = JsonPrimitive(false)
            o["automaticScrolls"] = JsonPrimitive(0)
        } else {
            local.forEach { messages += it.json }
            val ids = local.mapNotNull { it.messageId }.toSet()
            appendable(page, ids).forEach { g -> g.items.forEach { messages += historyJson(it) } }
        }
        o["messages"] = JsonArray(messages)
        o["messageCount"] = JsonPrimitive(messages.size)
        return JsonObject(o)
    }

    private fun historyJson(i: AgentHistoryItem): JsonObject {
        val o = linkedMapOf<String, JsonElement>()
        o["messageId"] = JsonPrimitive(i.messageId)
        o["roleRaw"] = JsonPrimitive(i.role)
        o["text"] = JsonPrimitive(i.text)
        o["cards"] = JsonArray(i.cards.map(AgentHistory::cardJson))
        if (!i.isUser) o["statusRaw"] = JsonPrimitive(i.status)
        return JsonObject(o)
    }

    private const val MODE_PAGING = "paging"
    private const val MODE_ENTRY = "entry"
    private const val REASON_COLD_START = "cold_start"
}

/**
 * 向上翻页（G08、G35）：滚到顶部时按 next_cursor 取更早一页，一次只在途一页；页结果比票据后插在前面；next_cursor=null 不再取。控制器与向量接线共用。
 * 翻页属于「进入页面与翻页」的读取，ApiClient 的 GET 自动重试 1 次照旧（G35）。只在调用方线程使用。
 */
class AgentHistoryPager(
    private var cursor: String?,
) {
    private var inFlight = false
    private var exhausted = cursor == null

    val hasEarlier: Boolean get() = !exhausted

    val loading: Boolean get() = inFlight

    /** 第一条消息进入可见区：返回要取的游标（在途或已经没有更早的为 null）。 */
    fun top(): String? {
        val c = cursor
        if (inFlight || exhausted || c == null) return null
        inFlight = true
        return c
    }

    /** 一页到达（已比过票据）。 */
    fun arrived(nextCursor: String?) {
        inFlight = false
        cursor = nextCursor
        exhausted = nextCursor == null
    }

    /** 失败：可以再触发（读取闸门另管）。 */
    fun failed() {
        inFlight = false
    }

    companion object {
        fun of(input: JsonObject): JsonObject {
            val pager = AgentHistoryPager(AgentJson.stringOf(input["nextCursor"]))
            var ids = AgentJson.arrayOf(input["localMessageIds"]).orEmpty().mapNotNull { AgentJson.stringOf(it) }
            val requests = mutableListOf<JsonElement>()
            for (v in AgentJson.arrayOf(input["events"]).orEmpty()) {
                val e = AgentJson.objectOf(v) ?: AgentJson.EMPTY
                when (AgentJson.stringOf(e["kindRaw"])) {
                    "top" -> {
                        pager.top()?.let { requests += AgentJson.obj("cursor" to it, "limit" to AgentHistory.LIMIT) }
                    }

                    "page" -> {
                        val older = AgentJson.arrayOf(e["messageIds"]).orEmpty().mapNotNull { AgentJson.stringOf(it) }
                        ids = older.filter { it !in ids } + ids
                        pager.arrived(AgentJson.stringOf(e["nextCursor"]))
                    }
                }
            }
            return AgentJson.obj(
                "requests" to JsonArray(requests),
                "messageIds" to ids,
                "hasEarlier" to pager.hasEarlier,
                "apiGetRetries" to AgentEntryPlan.API_GET_RETRIES,
                "apiGetRetryDelayMs" to AgentEntryPlan.API_GET_RETRY_DELAY_MS,
            )
        }
    }
}

/** 进入页面（G39）：只读——取当前会话，有会话再取历史首页；不建会话、不发消息。基本模式、会员但账号未知一律不取（FU-AND-0714 第 4 条）。 */
object AgentEntryPlan {
    /** 03 §4.2：GET 自动重试 1 次、退避 500 毫秒（ApiClient RetryPolicy；进入页面与翻页照旧）。 */
    const val API_GET_RETRIES = 1
    const val API_GET_RETRY_DELAY_MS = 500

    /** 此刻能不能取当前会话（基本模式、会员但账号未知都不取）。 */
    fun allowed(
        basicMode: Boolean,
        memberUnknown: Boolean,
    ): Boolean = !basicMode && !memberUnknown

    @Suppress("ReturnCount") // 不允许、网络失败、空会话依次返回
    fun of(input: JsonObject): JsonObject {
        val ops = mutableListOf<String>()
        val o = linkedMapOf<String, JsonElement>("createSessionRequests" to JsonPrimitive(0))
        if (!allowed(AgentJson.boolOf(input["basicMode"]) == true, AgentJson.stringOf(input["identityRaw"]) == MEMBER_UNKNOWN)) {
            o["operationsRaw"] = JsonArray(emptyList())
            return JsonObject(o)
        }
        ops += AgentOperation.CURRENT
        if (AgentJson.stringOf(input["currentResultRaw"]) == "network_error") {
            o["operationsRaw"] = AgentJson.element(ops)
            o["pageStateRaw"] = JsonPrimitive("load_failed")
            return JsonObject(o)
        }
        if (AgentJson.stringOf(input["sessionId"]) == null) {
            o["operationsRaw"] = AgentJson.element(ops)
            o["pageStateRaw"] = JsonPrimitive("empty")
            return JsonObject(o)
        }
        ops += AgentOperation.HISTORY
        o["operationsRaw"] = AgentJson.element(ops)
        o["limit"] = JsonPrimitive(AgentHistory.LIMIT)
        o["pageStateRaw"] = JsonPrimitive("ready")
        return JsonObject(o)
    }

    const val MEMBER_UNKNOWN = "member_unknown"
}
