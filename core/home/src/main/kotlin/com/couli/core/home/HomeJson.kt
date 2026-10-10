package com.couli.core.home

import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 首页页面配置的解码（03 §6.1、§6.2 第 2 步，G01、G05、G07、G08、G12–G14、G21）。TODO(N-35)：首页 schema 不在锁定契约里，字段按 03 摘要与 rule-gaps 代拟。
 *
 * 两段解码：页面级先把 `sections` 解成通用 JSON 数组（不是数组或 `version` 不是正整数 → 整页无效），再逐项按 `type` 解成强类型卡片。每项结果三选一：
 * - 保留（[HomePage.cards] / [HomePage.popup]）；
 * - 静默跳过（未知 type、`min_app_version` 不满足或缺本端键；不记事件）；
 * - 丢弃（G21 的 reason，记 `sdui_card_error`）。
 * 条目级坏数据只丢条目（G08，不记事件）；丢完为空的卡照常保留，界面按「空」隐藏。重复 section id 保留第一个解码成功的，其余 duplicate_id（H05 同口径）。
 * 只认 03 §6.3 的 8 种 type 与这里列出的字段；`condition` 一律不读（G09）；不执行任何下发的脚本、表达式或模板。
 */
@Suppress("TooManyFunctions") // 每种卡片、每种条目一个解码函数
object HomeJson {
    private val lenient = Json { ignoreUnknownKeys = true }

    /** 文本 → 页面（读 LKG、包内默认、假服务响应用）；不是 JSON 对象为 null。 */
    fun parseText(
        text: String?,
        platform: String,
        appVersion: String,
    ): HomePage? {
        val root = text?.let { runCatching { lenient.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
        return parse(root, platform, appVersion)
    }

    @Suppress("ReturnCount", "NestedBlockDepth") // 页面级两处失败各自提前返回；逐项三选一
    fun parse(
        page: JsonObject,
        platform: String,
        appVersion: String,
    ): HomePage {
        val version = positiveInt(page["version"])
        val sections = page["sections"] as? JsonArray
        val pageKey = (page["page_key"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val ttl = (page["ttl_sec"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (version == null || sections == null) {
            return HomePage(false, pageKey, version ?: 0, ttl, emptyList(), emptyList(), emptyList(), page)
        }
        val kept = mutableListOf<HomeCard>()
        val skipped = mutableListOf<HomeSkipped>()
        val dropped = mutableListOf<HomeDropped>()
        val seen = mutableSetOf<String>()
        var popup: HomeCard.Popup? = null
        sections.forEach { element ->
            when (val outcome = section(element, platform, appVersion)) {
                is SectionOutcome.Skip -> {
                    skipped += HomeSkipped(outcome.id, outcome.reason)
                }

                is SectionOutcome.Drop -> {
                    dropped += HomeDropped(outcome.id, outcome.type, outcome.version, outcome.reason)
                }

                is SectionOutcome.Keep -> {
                    val card = outcome.card
                    when {
                        card.id in seen -> {
                            dropped += HomeDropped(card.id, card.type, card.version, HomeDropReason.DUPLICATE_ID)
                        }

                        card is HomeCard.Popup -> {
                            // 每页最多 1 个：第一个解码成功的 popup，其余静默不用（G14 ③）
                            if (popup == null) {
                                popup = card
                                seen += card.id
                                kept += card
                            }
                        }

                        else -> {
                            seen += card.id
                            kept += card
                        }
                    }
                }
            }
        }
        return HomePage(true, pageKey, version, ttl, kept, skipped, dropped, page)
    }

    /**
     * 向量接线（HomeVectorTest）：`{pageValid, kept[{id, type, itemCount}], skipped[{id, reasonRaw}],
     * dropped[{id, type, version, reasonRaw}], popupId}`。
     * 只转换类型，不计算期望。
     */
    fun decode(
        pageJson: JsonObject,
        platform: String,
        appVersion: String,
    ): JsonObject {
        val page = parse(pageJson, platform, appVersion)
        return JsonObject(
            linkedMapOf(
                "pageValid" to JsonPrimitive(page.valid),
                "kept" to
                    JsonArray(
                        page.kept.map {
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive(it.id),
                                    "type" to JsonPrimitive(it.type),
                                    "itemCount" to JsonPrimitive(it.itemCount),
                                ),
                            )
                        },
                    ),
                "skipped" to
                    JsonArray(page.skipped.map { JsonObject(mapOf("id" to nullable(it.id), "reasonRaw" to JsonPrimitive(it.reason))) }),
                "dropped" to
                    JsonArray(
                        page.dropped.map {
                            JsonObject(
                                mapOf(
                                    "id" to nullable(it.id),
                                    "type" to nullable(it.type),
                                    "version" to (it.version?.let(::JsonPrimitive) ?: JsonNull),
                                    "reasonRaw" to JsonPrimitive(it.reason),
                                ),
                            )
                        },
                    ),
                "popupId" to nullable(page.popup?.id),
            ),
        )
    }

    private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

    // ---------------------------------------------------------------- section

    private sealed interface SectionOutcome {
        data class Keep(
            val card: HomeCard,
        ) : SectionOutcome

        data class Skip(
            val id: String?,
            val reason: String,
        ) : SectionOutcome

        data class Drop(
            val id: String?,
            val type: String?,
            val version: Int?,
            val reason: String,
        ) : SectionOutcome
    }

    /** 卡片内的解码失败（只在本文件内用作提前结束；reason 是 G21 的取值）。 */
    private class Failure(
        val reason: String,
    ) : RuntimeException(reason)

    private fun fail(reason: String): Nothing = throw Failure(reason)

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // 先后判定 id、type、跳过条件、version，各自提前返回
    private fun section(
        element: JsonElement,
        platform: String,
        appVersion: String,
    ): SectionOutcome {
        val json = element as? JsonObject ?: return SectionOutcome.Drop(null, null, null, HomeDropReason.BAD_TYPE)
        val idValue = json["id"]
        val id = (idValue as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
        val typeValue = json["type"]
        val type = (typeValue as? JsonPrimitive)?.takeIf { it.isString }?.content
        val versionValue = json["version"]
        val version = (versionValue as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (id == null) {
            val reason = if (idValue == null || idValue == JsonNull) HomeDropReason.MISSING_FIELD else HomeDropReason.BAD_TYPE
            return SectionOutcome.Drop(null, type, version, reason)
        }
        if (type == null) {
            val reason = if (typeValue == null || typeValue == JsonNull) HomeDropReason.MISSING_FIELD else HomeDropReason.BAD_TYPE
            return SectionOutcome.Drop(id, null, version, reason)
        }
        // 未知 type 静默跳过（03 §6.2，不记 sdui_card_error）
        if (type !in HomeCardType.ALL) return SectionOutcome.Skip(id, HomeSkipReason.UNKNOWN_TYPE)
        // 按端门槛：只做「当前版本 ≥ 门槛」；字段存在却没有本端键或不合法 → 跳过（G09）
        val absent = !json.containsKey("min_app_version")
        if (!HomeSectionFilter.keep(json["min_app_version"], absent, platform, appVersion)) {
            return SectionOutcome.Skip(id, HomeSkipReason.MIN_APP_VERSION)
        }
        when {
            versionValue == null || versionValue == JsonNull -> {
                return SectionOutcome.Drop(id, type, null, HomeDropReason.MISSING_VERSION)
            }

            version == null -> {
                return SectionOutcome.Drop(id, type, null, HomeDropReason.BAD_TYPE)
            }

            version < 1 -> {
                return SectionOutcome.Drop(id, type, version, HomeDropReason.BAD_VALUE)
            }

            version > HomeCardType.MAX_VERSION -> {
                return SectionOutcome.Drop(id, type, version, HomeDropReason.UNSUPPORTED_VERSION)
            }
        }
        return try {
            SectionOutcome.Keep(card(json, id, type, version))
        } catch (failure: Failure) {
            SectionOutcome.Drop(id, type, version, failure.reason)
        }
    }

    private fun card(
        json: JsonObject,
        id: String,
        type: String,
        version: Int,
    ): HomeCard {
        val label = optionalText(json, "ad_label")
        return when (type) {
            HomeCardType.BANNER -> banner(json, id, version, label)
            HomeCardType.ICON_GRID -> iconGrid(json, id, version, label)
            HomeCardType.PRODUCT_SCROLL -> productScroll(json, id, version, label)
            HomeCardType.PRODUCT_GRID -> productGrid(json, id, version, label)
            HomeCardType.ACTIVITY_ENTRY -> activity(json, id, version, label)
            HomeCardType.NOTICE_BAR -> notice(json, id, version, label)
            HomeCardType.FEED_INFINITE -> feed(json, id, version, label)
            else -> popup(json, id, version, label)
        }
    }

    private fun banner(
        json: JsonObject,
        id: String,
        version: Int,
        label: String?,
    ): HomeCard.Banner {
        val props = props(json)
        val ratio = number(props, "height_ratio")
        if (ratio <= 0.0 || ratio > 1.0) fail(HomeDropReason.BAD_VALUE)
        val autoplay = optionalLong(props, "autoplay_ms") ?: 0L
        if (autoplay < 0) fail(HomeDropReason.BAD_VALUE)
        val items = staticItems(json).mapNotNull(::bannerItem)
        return HomeCard.Banner(id, version, label, ratio, autoplay, items)
    }

    private fun iconGrid(
        json: JsonObject,
        id: String,
        version: Int,
        label: String?,
    ): HomeCard.IconGrid {
        val props = props(json)
        val columns = requiredInt(props, "columns")
        if (columns != GRID_FOUR && columns != GRID_FIVE) fail(HomeDropReason.BAD_VALUE)
        val rows = optionalLong(props, "rows")?.toInt()
        if (rows != null && rows < 1) fail(HomeDropReason.BAD_VALUE)
        val items = staticItems(json).mapNotNull(::iconItem)
        return HomeCard.IconGrid(id, version, label, columns, rows, items)
    }

    private fun productScroll(
        json: JsonObject,
        id: String,
        version: Int,
        label: String?,
    ): HomeCard.ProductScroll {
        val props = props(json)
        val title = requiredText(props, "title")
        val more =
            when (val value = props["more_action"]) {
                null, JsonNull -> null
                else -> RouteTargetCheck.parse(value as? JsonObject) ?: fail(HomeDropReason.BAD_ACTION)
            }
        return HomeCard.ProductScroll(id, version, label, title, more, productSource(json))
    }

    private fun productGrid(
        json: JsonObject,
        id: String,
        version: Int,
        label: String?,
    ): HomeCard.ProductGrid {
        val props = props(json)
        val columns = requiredInt(props, "columns")
        if (columns != 1 && columns != 2) fail(HomeDropReason.BAD_VALUE)
        return HomeCard.ProductGrid(id, version, label, columns, optionalText(props, "title"), productSource(json))
    }

    private fun activity(
        json: JsonObject,
        id: String,
        version: Int,
        label: String?,
    ): HomeCard.ActivityEntry {
        val props =
            when (val value = json["props"]) {
                null, JsonNull -> JsonObject(emptyMap())
                is JsonObject -> value
                else -> fail(HomeDropReason.BAD_TYPE)
            }
        val raw = staticItems(json)
        if (raw.size > MAX_ACTIVITY_ITEMS) fail(HomeDropReason.BAD_VALUE)
        return HomeCard.ActivityEntry(id, version, label, optionalText(props, "layout"), raw.mapNotNull(::activityItem))
    }

    private fun notice(
        json: JsonObject,
        id: String,
        version: Int,
        label: String?,
    ): HomeCard.NoticeBar {
        val props = props(json)
        val scroll =
            when (optionalText(props, "mode")) {
                null, MODE_STATIC -> false
                MODE_SCROLL -> true
                else -> fail(HomeDropReason.BAD_VALUE)
            }
        // api 来源只调 listArticles(category=notice)，data_source 里别的路径与参数一律忽略（G10，03 §6.3「不接受任意接口路径」）
        return when (sourceKind(json)) {
            HomeSourceKind.API -> {
                HomeCard.NoticeBar(id, version, label, scroll, apiSource = true, items = emptyList())
            }

            HomeSourceKind.STATIC -> {
                val items = requiredArray(props, "items")
                HomeCard.NoticeBar(id, version, label, scroll, apiSource = false, items = items.mapNotNull { noticeItem(it) })
            }

            else -> {
                fail(HomeDropReason.BAD_VALUE)
            }
        }
    }

    private fun feed(
        json: JsonObject,
        id: String,
        version: Int,
        label: String?,
    ): HomeCard.FeedInfinite {
        val source = dataSource(json)
        if (text(source, "kind") != HomeSourceKind.UNION_MATERIAL) fail(HomeDropReason.BAD_VALUE)
        val platforms =
            requiredArray(source, "platforms").map {
                val value = (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: fail(HomeDropReason.BAD_TYPE)
                if (value !in FEED_PLATFORMS) fail(HomeDropReason.BAD_VALUE)
                value
            }
        if (platforms.isEmpty()) fail(HomeDropReason.BAD_VALUE)
        optionalText(source, "mix")
        return HomeCard.FeedInfinite(id, version, label, platforms, ProductSource(HomeSourceKind.UNION_MATERIAL))
    }

    private fun popup(
        json: JsonObject,
        id: String,
        version: Int,
        label: String?,
    ): HomeCard.Popup {
        val props = props(json)
        val image = requiredText(props, "image")
        if (!https(image)) fail(HomeDropReason.BAD_VALUE)
        val daily = requiredInt(props, "daily_max")
        if (daily < 1) fail(HomeDropReason.BAD_VALUE)
        val window = requiredArray(props, "time_window")
        if (window.size != 2) fail(HomeDropReason.BAD_VALUE)
        val start = window[0].clockMinute() ?: fail(HomeDropReason.BAD_VALUE)
        val end = window[1].clockMinute() ?: fail(HomeDropReason.BAD_VALUE)
        // 起点 ≥ 终点（含跨零点）按解码失败处理（G14 ①）
        if (start >= end) fail(HomeDropReason.BAD_VALUE)
        if (sourceKind(json) != HomeSourceKind.STATIC) fail(HomeDropReason.BAD_VALUE)
        val items = (dataSource(json)["items"] as? JsonArray).orEmpty()
        // 点图片执行 items[0].action；缺失或形状不对则图片不可点（条目级，G08）
        val action = (items.firstOrNull() as? JsonObject)?.let { RouteTargetCheck.parse(it["action"] as? JsonObject) }
        return HomeCard.Popup(id, version, label, image, daily, start, end, action)
    }

    // ---------------------------------------------------------------- 条目（坏条目只丢这一条，不记事件）

    @Suppress("ReturnCount") // 每个字段不合格各自丢弃
    private fun bannerItem(element: JsonElement): BannerItem? {
        val json = element as? JsonObject ?: return null
        val image = (json["image"] as? JsonPrimitive)?.takeIf { it.isString && https(it.content) }?.content ?: return null
        val action = RouteTargetCheck.parse(json["action"] as? JsonObject) ?: return null
        val label = itemText(json, "ad_label") ?: return null
        val alt = itemText(json, "alt") ?: return null
        return BannerItem(image, action, label.value, alt.value)
    }

    @Suppress("ReturnCount") // 每个字段不合格各自丢弃
    private fun iconItem(element: JsonElement): IconItem? {
        val json = element as? JsonObject ?: return null
        val raw = (json["icon"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val icon =
            when {
                https(raw) -> {
                    HomeIcon.Remote(raw)
                }

                raw.startsWith(HomeBuiltinIcons.PREFIX) && raw.removePrefix(HomeBuiltinIcons.PREFIX) in HomeBuiltinIcons.NAMES -> {
                    HomeIcon.Builtin(raw.removePrefix(HomeBuiltinIcons.PREFIX))
                }

                else -> {
                    return null
                }
            }
        val title = (json["title"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content ?: return null
        val action = RouteTargetCheck.parse(json["action"] as? JsonObject) ?: return null
        val badge = itemText(json, "badge") ?: return null
        // 条目级推广标识（G22：条目上非 null 优先于卡片级；类型不符只丢这一条，与 banner 同口径）
        val label = itemText(json, "ad_label") ?: return null
        return IconItem(icon, title, action, badge.value, label.value)
    }

    @Suppress("ReturnCount") // 每个字段不合格各自丢弃
    private fun activityItem(element: JsonElement): ActivityItem? {
        val json = element as? JsonObject ?: return null
        val action = RouteTargetCheck.parse(json["action"] as? JsonObject) ?: return null
        val title = itemText(json, "title") ?: return null
        val subtitle = itemText(json, "subtitle") ?: return null
        val image = itemText(json, "image") ?: return null
        if (image.value != null && !https(image.value)) return null
        if (title.value.isNullOrEmpty() && image.value == null) return null
        val label = itemText(json, "ad_label") ?: return null
        return ActivityItem(image.value, title.value?.takeIf { it.isNotEmpty() }, subtitle.value, action, label.value)
    }

    /**
     * 契约 NoticeItem（additionalProperties: false）：多出字段、缺键（含 `target` 缺键而不是 null）、类型不符、`closable=false` 且 `end_at=null`
     * （违反 `not` 约束）都只丢这一条（G10、H07）。公告列表接口（listArticles）的条目也经这里。
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // 逐字段校验
    fun noticeItem(element: JsonElement?): NoticeItem? {
        val json = element as? JsonObject ?: return null
        if (json.keys != NOTICE_KEYS) return null
        val itemId = (json["item_id"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content ?: return null
        val contentVersion = positiveInt(json["content_version"]) ?: return null
        val text = (json["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val target =
            when (val value = json["target"]) {
                JsonNull -> null
                is JsonObject -> RouteTargetCheck.parse(value) ?: return null
                else -> return null
            }
        val closable = (json["closable"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
        val start = instant(json["start_at"]) ?: return null
        val end =
            when (val value = json["end_at"]) {
                JsonNull -> null
                else -> instant(value) ?: return null
            }
        if (!closable && end == null) return null
        return NoticeItem(itemId, contentVersion, text, target, closable, start, end)
    }

    // ---------------------------------------------------------------- 字段读取

    private class Present(
        val value: String?,
    )

    /** 条目上的可选字符串：缺失或 null → Present(null)；类型不符 → null（丢这一条）。 */
    private fun itemText(
        json: JsonObject,
        key: String,
    ): Present? =
        when (val value = json[key]) {
            null, JsonNull -> Present(null)
            is JsonPrimitive -> if (value.isString) Present(value.content) else null
            else -> null
        }

    private fun props(json: JsonObject): JsonObject =
        when (val value = json["props"]) {
            null, JsonNull -> fail(HomeDropReason.MISSING_FIELD)
            is JsonObject -> value
            else -> fail(HomeDropReason.BAD_TYPE)
        }

    private fun dataSource(json: JsonObject): JsonObject =
        when (val value = json["data_source"]) {
            null, JsonNull -> fail(HomeDropReason.MISSING_FIELD)
            is JsonObject -> value
            else -> fail(HomeDropReason.BAD_TYPE)
        }

    private fun sourceKind(json: JsonObject): String = text(dataSource(json), "kind")

    /** 静态来源的条目：`data_source = {kind: static, items: [...]}`；别的来源 → bad_value（03 §6.3：只有公告条有 api 来源）。 */
    private fun staticItems(json: JsonObject): JsonArray {
        val source = dataSource(json)
        if (text(source, "kind") != HomeSourceKind.STATIC) fail(HomeDropReason.BAD_VALUE)
        return requiredArray(source, "items")
    }

    private fun productSource(json: JsonObject): ProductSource {
        val kind = text(dataSource(json), "kind")
        if (kind != HomeSourceKind.PRODUCT_POOL && kind != HomeSourceKind.UNION_MATERIAL) fail(HomeDropReason.BAD_VALUE)
        return ProductSource(kind)
    }

    private fun text(
        json: JsonObject,
        key: String,
    ): String =
        when (val value = json[key]) {
            null, JsonNull -> fail(HomeDropReason.MISSING_FIELD)
            is JsonPrimitive -> if (value.isString) value.content else fail(HomeDropReason.BAD_TYPE)
            else -> fail(HomeDropReason.BAD_TYPE)
        }

    private fun requiredText(
        json: JsonObject,
        key: String,
    ): String = text(json, key)

    private fun optionalText(
        json: JsonObject,
        key: String,
    ): String? =
        when (val value = json[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> if (value.isString) value.content else fail(HomeDropReason.BAD_TYPE)
            else -> fail(HomeDropReason.BAD_TYPE)
        }

    private fun requiredArray(
        json: JsonObject,
        key: String,
    ): JsonArray =
        when (val value = json[key]) {
            null, JsonNull -> fail(HomeDropReason.MISSING_FIELD)
            is JsonArray -> value
            else -> fail(HomeDropReason.BAD_TYPE)
        }

    private fun requiredInt(
        json: JsonObject,
        key: String,
    ): Int =
        when (val value = json[key]) {
            null, JsonNull -> fail(HomeDropReason.MISSING_FIELD)
            is JsonPrimitive -> value.takeIf { !it.isString }?.intOrNull ?: fail(HomeDropReason.BAD_TYPE)
            else -> fail(HomeDropReason.BAD_TYPE)
        }

    private fun optionalLong(
        json: JsonObject,
        key: String,
    ): Long? =
        when (val value = json[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.takeIf { !it.isString }?.longOrNull ?: fail(HomeDropReason.BAD_TYPE)
            else -> fail(HomeDropReason.BAD_TYPE)
        }

    private fun number(
        json: JsonObject,
        key: String,
    ): Double =
        when (val value = json[key]) {
            null, JsonNull -> fail(HomeDropReason.MISSING_FIELD)
            is JsonPrimitive -> value.takeIf { !it.isString }?.doubleOrNull ?: fail(HomeDropReason.BAD_TYPE)
            else -> fail(HomeDropReason.BAD_TYPE)
        }

    /** 正整数（JSON 数字、不带小数）；否则 null。 */
    fun positiveInt(value: JsonElement?): Int? = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 1 }

    /** RFC 3339 时刻（必须带时区偏移）→ 绝对毫秒；不合法为 null。 */
    fun instant(value: JsonElement?): Long? {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    }

    /** 图片地址只收 https（解码阶段校验，03 §10.2）。 */
    fun https(url: String): Boolean = url.startsWith(HTTPS) && url.length > HTTPS.length

    /** `"HH:mm"` → 当天第几分钟（00:00–23:59）；不合法为 null。 */
    @Suppress("ReturnCount")
    private fun JsonElement.clockMinute(): Int? {
        val text = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val match = CLOCK.matchEntire(text) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        return if (hour < HOURS && minute < MINUTES) hour * MINUTES + minute else null
    }

    private const val HTTPS = "https://"
    private const val GRID_FOUR = 4
    private const val GRID_FIVE = 5
    private const val MAX_ACTIVITY_ITEMS = 3
    private const val MODE_STATIC = "static"
    private const val MODE_SCROLL = "scroll"
    private const val HOURS = 24
    private const val MINUTES = 60
    private val CLOCK = Regex("([0-9]{2}):([0-9]{2})")
    private val FEED_PLATFORMS = setOf("taobao", "jd", "pdd")
    private val NOTICE_KEYS = setOf("item_id", "content_version", "text", "target", "closable", "start_at", "end_at")
}
