package com.couli.core.home

import com.couli.core.update.UpdateGate
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 按端门槛（G09，F-HOME-06，AC-S1-89）：客户端只做「当前版本 ≥ 门槛」一种比较（单调）；`condition` 一律不读。
 * 字段缺失 → 不限制；字段存在（含显式 null）却不是对象、没有本端键、值不是合法版本号 → 跳过。版本比较复用 UpdateGate.compareOrNull。
 */
object HomeSectionFilter {
    @Suppress("ReturnCount") // 缺失、形状、比较三个出口
    fun keep(
        minAppVersion: JsonElement?,
        absent: Boolean,
        platform: String,
        appVersion: String,
    ): Boolean {
        if (absent) return true
        val threshold = ((minAppVersion as? JsonObject)?.get(platform) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        val compared = UpdateGate.compareOrNull(appVersion, threshold) ?: return false
        return compared >= 0
    }
}

/**
 * 动作白名单（G05，F-HOME-09，03 §4.4）：只认 `{route: 非空字符串, params?: 对象}`；其他字段（url、scheme、js、script、html、deeplink……）出现即忽略，
 * 不当作动作；形状不对 → 无效（bad_action）。解码不查路由表：未知路由、since、debug_only 由 Router 处理。
 */
object RouteTargetCheck {
    @Suppress("ReturnCount") // 形状逐项提前返回
    fun parse(json: JsonObject?): RouteTarget? {
        json ?: return null
        val route = (json["route"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content ?: return null
        if (!json.containsKey("params")) return RouteTarget(route, null)
        val params = json["params"] as? JsonObject ?: return null
        return RouteTarget(route, params)
    }

    /** 向量接线：成功时只输出 route 与（给了的）params，不输出任何别的字段。 */
    fun decode(json: JsonObject?): JsonObject? {
        val target = parse(json) ?: return null
        val map = linkedMapOf<String, JsonElement>("route" to JsonPrimitive(target.route))
        target.params?.let { map["params"] = it }
        return JsonObject(map)
    }
}

/** spm（G06，03 §4.7）：`home.<section id>.<坑位>`，坑位从 1 起（代拟）；只作旁路上下文，不塞进路由参数。 */
object HomeSpm {
    fun of(
        sectionId: String,
        slot: Int,
    ): String = "home.$sectionId.$slot"
}

/** 推广标识（G22，BR-TEXT-17）：条目上非 null 字符串优先，否则卡片级；原样显示，不替换、不判断业务条件。 */
object HomeAdLabel {
    fun of(
        sectionLabel: JsonElement?,
        itemLabel: JsonElement?,
    ): String? = of(sectionLabel.stringOrNull(), itemLabel.stringOrNull())

    fun of(
        sectionLabel: String?,
        itemLabel: String?,
    ): String? = itemLabel ?: sectionLabel

    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** 首页的身份档（LKG 分区、弹窗与埋点判定）。 */
object HomeLevel {
    const val MEMBER = "member"
    const val GUEST = "guest"
    const val BASIC = "basic"
}

/** LKG 分区（G16 ②）：环境 + 账号作用域（已登录 user_id、游客 guest、基本模式 basic）。序列化写法是代拟，只比较相等。 */
object HomeLkgScope {
    fun of(
        level: String,
        userId: String?,
        env: String,
    ): String =
        when (level) {
            HomeLevel.MEMBER -> if (userId == null) "$env:member-unknown" else "$env:user:$userId"
            HomeLevel.BASIC -> "$env:basic"
            else -> "$env:guest"
        }

    /**
     * [scope] 是会员作用域（[of] 的 member 两种写法：已知账号、账号未知）：LKG 存储按持久化登录世代再分区（F-AND-25，FU-AND-0714 R1-07）。
     * 只识别，不改 [of] 的写法（三端共用向量不变）。
     */
    fun isMember(scope: String): Boolean = scope.contains(":user:") || scope.endsWith(":member-unknown")
}

/**
 * 本机数据的清理事件（G16 ②、G11）：撤回同意、切换环境 → 全清；退出登录、换账号、会话失效 → 只清旧账号作用域的 LKG；
 * 清除缓存、回后台 → 不清。公告关闭记录与弹窗计数是设备级。
 */
object HomeLocalData {
    const val LKG_CURRENT = "lkg_current_scope"
    const val LKG_OTHER = "lkg_other_scope"
    const val NOTICE_CLOSED = "notice_closed"
    const val POPUP_COUNT = "popup_count"

    fun clearsOn(
        event: String,
        item: String,
    ): Boolean =
        when (event) {
            "consent_revoked", "env_switch" -> true
            "logout", "account_switch", "session_invalid" -> item == LKG_CURRENT
            else -> false
        }
}

/** 公告条的可见条目（G10）。 */
data class NoticeView(
    val items: List<NoticeItem>,
    val nextExpiryMs: Long?,
) {
    val hidden: Boolean get() = items.isEmpty()
}

/**
 * 公告条过滤（G10、G11，BR-TEXT-13 细则「首页公告条的关闭与内容版本」，AC-S1-90 ⑧⑨）：按本机时刻（绝对时刻比较）过滤 `start_at ≤ now < end_at`
 * （end_at 为 null 不限上界）；跳过关闭记录里的「卡片 id + item_id + 内容版本」；全空 → 整卡隐藏。[nextExpiryMs] 是可见条目里最近的 end_at：
 * 页面停留时在那一刻重算，不轮询网络。
 */
object NoticeFilter {
    fun view(
        sectionId: String,
        items: List<NoticeItem>,
        closed: Set<String>,
        nowMs: Long,
    ): NoticeView {
        val visible =
            items.filter { item ->
                item.startMs <= nowMs &&
                    (item.endMs == null || nowMs < item.endMs) &&
                    closeKey(sectionId, item.itemId, item.contentVersion) !in closed
            }
        return NoticeView(visible, visible.mapNotNull { it.endMs }.minOrNull())
    }

    /** 向量接线：`{visibleItemIds, closable, cardHidden, nextExpiryMs}`。 */
    fun visible(
        sectionId: String,
        itemsJson: List<JsonObject>,
        closedKeys: List<String>,
        nowMs: Long,
    ): JsonObject {
        val view = view(sectionId, itemsJson.mapNotNull { HomeJson.noticeItem(it) }, closedKeys.toSet(), nowMs)
        return JsonObject(
            linkedMapOf(
                "visibleItemIds" to JsonArray(view.items.map { JsonPrimitive(it.itemId) }),
                "closable" to JsonObject(view.items.associate { it.itemId to JsonPrimitive(it.closable) }),
                "cardHidden" to JsonPrimitive(view.hidden),
                "nextExpiryMs" to (view.nextExpiryMs?.let(::JsonPrimitive) ?: JsonNull),
            ),
        )
    }

    /** 关闭记录键：卡片 id + item_id + 内容版本（分隔写法代拟；带长度前缀，任何 id 里含分隔符都不会撞键）。 */
    fun closeKey(
        sectionId: String,
        itemId: String,
        contentVersion: Int,
    ): String = "${sectionId.length}:$sectionId|${itemId.length}:$itemId|$contentVersion"
}

/** 弹窗决策结果。 */
data class PopupDecision(
    val show: Boolean,
    val reason: String,
)

/**
 * 首页弹窗决策（G14，F-HOME-08，TECH-12）：基本模式不弹；图片没加载成功不弹；有阻挡项（隐私弹窗、强更、更新提示、剪贴板提示条、半屏 / 对话框、首页
 * 不在前台）不弹；本次冷启动已弹过不弹；同一 section id 当天（上海自然日，调用方给 [shownToday]）达到 daily_max 不弹；不在时间段 [起点, 终点) 内不弹。
 * 显示后才 +1（调用方在真正显示后落盘）。
 */
object PopupPolicy {
    const val SHOW = "show"
    const val SKIP = "skip"

    @Suppress("LongParameterList", "ReturnCount") // 决策输入逐项给；各条件依次提前返回
    fun decide(
        popup: HomeCard.Popup,
        level: String,
        nowMs: Long,
        shownToday: Int,
        coldStartShown: Boolean,
        blockers: Collection<String>,
        imageLoaded: Boolean,
    ): PopupDecision {
        if (level == HomeLevel.BASIC) return PopupDecision(false, "basic_mode")
        if (!imageLoaded) return PopupDecision(false, "image_not_loaded")
        if (blockers.isNotEmpty()) return PopupDecision(false, "blocked")
        if (coldStartShown) return PopupDecision(false, "cold_start_shown")
        if (shownToday >= popup.dailyMax) return PopupDecision(false, "daily_max")
        val minute = minuteOfDay(nowMs)
        if (minute < popup.windowStartMinute || minute >= popup.windowEndMinute) return PopupDecision(false, "time_window")
        return PopupDecision(true, SHOW)
    }

    /** 向量接线：popupJson 是完整 section；返回 `{decisionRaw, countAfter}`。 */
    @Suppress("LongParameterList") // 与向量接线约定一致
    fun decide(
        popupJson: JsonObject,
        level: String,
        nowMs: Long,
        shownToday: Int,
        coldStartShown: Boolean,
        blockers: List<String>,
        imageLoaded: Boolean,
    ): JsonObject {
        val page =
            JsonObject(
                mapOf(
                    "page_key" to JsonPrimitive("home"),
                    "version" to JsonPrimitive(1),
                    "sections" to JsonArray(listOf(popupJson)),
                ),
            )
        val popup = HomeJson.parse(page, "android", "1.0.0").popup
        val decision =
            popup?.let { decide(it, level, nowMs, shownToday, coldStartShown, blockers, imageLoaded) } ?: PopupDecision(false, "invalid")
        return JsonObject(
            mapOf(
                "decisionRaw" to JsonPrimitive(if (decision.show) SHOW else SKIP),
                "countAfter" to JsonPrimitive(if (decision.show) shownToday + 1 else shownToday),
            ),
        )
    }

    /** 上海时区（BR-TEXT-11）的当天第几分钟。 */
    fun minuteOfDay(nowMs: Long): Int {
        val time = Instant.ofEpochMilli(nowMs).atZone(SHANGHAI)
        return time.hour * MINUTES + time.minute
    }

    /** 上海时区的自然日（弹窗每日次数按它分日，G14 ①）。 */
    fun shanghaiDay(nowMs: Long): String =
        Instant
            .ofEpochMilli(nowMs)
            .atZone(SHANGHAI)
            .toLocalDate()
            .toString()

    private val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
    private const val MINUTES = 60
}

/**
 * 曝光判定（G20，纯逻辑，不接埋点）：可见 ≥50% 且连续 ≥500 毫秒判一次；去重键 = 页面停留编号 + section id + 坑位（一个实例对应一个 section 坑位）。
 * 切 Tab 或离开时调用方换新的停留编号：计时重置、可以再判一次。
 */
class ExposureTracker(
    val sectionId: String,
    val slot: Int,
) {
    private var stay: Int? = null
    private var since: Long? = null
    private var fired = false

    @Suppress("ReturnCount") // 已判过、不够可见、计时中三个出口
    fun onVisible(
        tMs: Long,
        ratio: Double,
        stay: Int,
    ): Boolean {
        if (this.stay != stay) {
            this.stay = stay
            since = null
            fired = false
        }
        if (fired) return false
        if (ratio < VISIBLE_RATIO) {
            since = null
            return false
        }
        val start = since ?: tMs.also { since = it }
        if (tMs - start >= VISIBLE_MS) {
            fired = true
            return true
        }
        return false
    }

    private companion object {
        const val VISIBLE_RATIO = 0.5
        const val VISIBLE_MS = 500L
    }
}

/** `sdui_card_error` 去重（G20）：同一进程内同一（page_version、section id、reason）只发一次。线程安全。 */
class CardErrorDedupe {
    private val sent = mutableSetOf<Triple<Int, String, String>>()

    @Synchronized
    fun shouldSend(
        pageVersion: Int,
        sectionId: String,
        reason: String,
    ): Boolean = sent.add(Triple(pageVersion, sectionId, reason))
}
