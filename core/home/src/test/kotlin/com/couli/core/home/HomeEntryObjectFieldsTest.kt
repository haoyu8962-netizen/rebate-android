package com.couli.core.home

import java.lang.reflect.Method
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 金刚区条目的对象字段（FU-AND-HOME-ENTRY-OBJECT-FIELDS，D10-2998；契约 rebate-platform 6c97062 home-schema.json
 * $defs/icon_grid_item、$defs/badge、$defs/icon_asset、$defs/instant）。只钉解析层：
 * - badge 对象：kind = text 显示 text；none 不显示；dot 只钉解析（条目照常、不带文字）；不合法只丢徽标、保留条目；
 *   旧的字符串 badge 契约已不允许，同样只丢徽标（(a)）；expire_at 解析成绝对时刻（到点隐藏在 feature/home 的控制器测试）。
 * - icon 对象：url 合 pattern 且 sha256 合格 → 远程图取 url（首页不做摘要校验）；.svg 只丢这一条（(e)）；不合法只丢这一条。
 * - image（(c)）：没有 icon 时取 image 作同一图片位（https）；icon 与 image 都有时以 icon 为准，icon 不合法不回落 image。
 *
 * 徽标的模型形状由实现定：测试经反射读 `badge`（字符串即显示文字；对象读 kind / text，或按类名判 text / dot / none）
 * 与名字含 expire 的字段，不引用新符号，保证在 3fd6a5c 上能编译、靠断言变红。
 */
@Suppress("TooManyFunctions") // 每条裁定一个用例，加几个反射小工具
class HomeEntryObjectFieldsTest {
    // ---------------------------------------------------------------- badge 对象

    @Test
    fun textBadgeObjectShowsItsTextAndKeepsTheItem() {
        val items = items(entry("甲", "badge" to """{"kind":"text","text":"测试"}"""), entry("乙"))
        assertEquals("带 text 徽标对象的条目保留", listOf("甲", "乙"), items.map { it.title })
        assertEquals("kind = text 显示 text", "测试", shown(items[0]))
        assertNull(shown(items[1]))
    }

    @Test
    fun textOfTwoCodePointsIsShownEvenWhenItTakesThreeUtf16Units() {
        // 契约 maxLength 按 Unicode 码点数：🔥 是一个码点（两个 UTF-16 单元），「🔥新」共 2 个码点，合法
        val items =
            items(
                entry("甲", "badge" to """{"kind":"text","text":"🔥新"}"""),
                entry("乙", "badge" to """{"kind":"text","text":"新"}"""),
            )
        assertEquals(listOf("甲", "乙"), items.map { it.title })
        assertEquals("🔥新", shown(items[0]))
        assertEquals("1 个字符也合法", "新", shown(items[1]))
    }

    @Test
    fun noneBadgeKeepsTheItemWithoutBadge() {
        val items = items(entry("甲", "badge" to """{"kind":"none"}"""))
        assertEquals("kind = none 的条目保留", listOf("甲"), items.map { it.title })
        assertNull("kind = none 不显示", shown(items[0]))
    }

    @Test
    fun dotBadgeIsParsedAsDotAndKeepsTheItemWithoutText() {
        val items = items(entry("甲", "badge" to """{"kind":"dot"}"""))
        assertEquals("kind = dot 的条目照常保留", listOf("甲"), items.map { it.title })
        assertNull("本单不显示红点，也没有文字", shown(items[0]))
        // 只钉解析：dot 是合法徽标（与「不合法只丢徽标」区分开），留给 FU-AND-HOME-BADGE-DOT 出样式
        val badge = badgeOf(items[0])
        assertNotNull("kind = dot 解析成徽标，不是丢掉", badge)
        assertEquals("dot", kindOf(badge!!))
    }

    @Test
    fun legacyStringBadgeDropsOnlyTheBadge() {
        // (a)：契约已不允许字符串 badge。旧口径（3fd6a5c）是原样显示；新口径只丢徽标、保留条目（不是整条丢）
        val items = items(entry("甲", "badge" to "\"测试\""), entry("乙"))
        assertEquals("字符串 badge 的条目保留（不是整条丢）", listOf("甲", "乙"), items.map { it.title })
        assertNull("字符串 badge 只丢徽标", badgeOf(items[0]))
        assertNull(shown(items[0]))
    }

    @Test
    fun invalidBadgeObjectsDropOnlyTheBadge() {
        val cases =
            linkedMapOf(
                "缺 kind" to """{}""",
                "缺 kind、只有 text" to """{"text":"测试"}""",
                "kind 不在枚举" to """{"kind":"red"}""",
                "kind 大写" to """{"kind":"TEXT","text":"测试"}""",
                "kind 不是字符串" to """{"kind":1}""",
                "kind = text 缺 text" to """{"kind":"text"}""",
                "text 为空" to """{"kind":"text","text":""}""",
                "text 3 个字" to """{"kind":"text","text":"三个字"}""",
                "text 3 个码点" to """{"kind":"text","text":"🔥🔥新"}""",
                "text 不是字符串" to """{"kind":"text","text":1}""",
                "none 带 text" to """{"kind":"none","text":"测试"}""",
                "dot 带 text" to """{"kind":"dot","text":"新"}""",
                "expire_at 用 Z" to """{"kind":"text","text":"新","expire_at":"2099-01-01T00:00:00Z"}""",
                "expire_at 用别的偏移" to """{"kind":"text","text":"新","expire_at":"2099-01-01T00:00:00+09:00"}""",
                "expire_at 没有偏移" to """{"kind":"text","text":"新","expire_at":"2099-01-01T00:00:00"}""",
                "expire_at 用空格分隔" to """{"kind":"text","text":"新","expire_at":"2099-01-01 00:00:00+08:00"}""",
                "expire_at 不是字符串" to """{"kind":"text","text":"新","expire_at":4070880000000}""",
                "badge 是数字" to "1",
                "badge 是布尔" to "true",
                "badge 是数组" to """["测试"]""",
            )
        val entries = cases.entries.mapIndexed { index, case -> entry("格$index", "badge" to case.value) }
        val items = items(*entries.toTypedArray())
        assertEquals("不合法徽标只丢徽标、条目全部保留", cases.keys.indices.map { "格$it" }, items.map { it.title })
        cases.keys.forEachIndexed { index, name ->
            assertNull("$name：徽标丢掉", badgeOf(items[index]))
        }
    }

    @Test
    fun nullOrAbsentBadgeKeepsTheItemWithoutBadge() {
        val items = items(entry("甲", "badge" to "null"), entry("乙"))
        assertEquals(listOf("甲", "乙"), items.map { it.title })
        assertNull(badgeOf(items[0]))
        assertNull(badgeOf(items[1]))
    }

    @Test
    fun expireAtIsDecodedToTheAbsoluteMomentAndTheBadgeIsStillShown() {
        val items =
            items(
                entry("甲", "badge" to """{"kind":"text","text":"新","expire_at":"2099-01-01T00:00:00+08:00"}"""),
                entry("乙", "badge" to """{"kind":"text","text":"热","expire_at":"2099-01-01T00:00:00.123456+08:00"}"""),
                entry("丙", "badge" to """{"kind":"dot","expire_at":"2099-01-01T08:00:00+08:00"}"""),
                entry("丁", "badge" to """{"kind":"text","text":"惠"}"""),
            )
        assertEquals("带 expire_at 的条目保留", listOf("甲", "乙", "丙", "丁"), items.map { it.title })
        // 解析不读时钟：到点隐藏由界面层按本机时钟做
        assertEquals("新", shown(items[0]))
        assertEquals("热", shown(items[1]))
        assertEquals(ms("2099-01-01T00:00:00+08:00"), expireMs(items[0]))
        assertEquals("小数秒（1–6 位）合法，取到毫秒", ms("2099-01-01T00:00:00.123+08:00"), expireMs(items[1]))
        assertEquals("dot 的 expire_at 也解析", ms("2099-01-01T08:00:00+08:00"), expireMs(items[2]))
        assertNull("没有 expire_at 不过期", expireMs(items[3]))
    }

    // ---------------------------------------------------------------- icon 对象

    @Test
    fun iconAssetObjectUsesItsUrlAsRemotePicture() {
        val nested = "https://cdn.example.com/icons/grid/$HEX_A.png"
        val flat = "https://cdn.example.com/$HEX_A.png"
        val items =
            items(
                entry("甲", "icon" to asset(nested, HEX_A)),
                entry("乙", "icon" to asset(flat, HEX_A)),
                // 首页不做摘要校验（icon_grid_item：BR-TEXT-30 摘要绑定只约束 me）：文件名与 sha256 不同也照用 url
                entry("丙", "icon" to asset(nested, HEX_B)),
            )
        assertEquals("icon 对象的条目保留", listOf("甲", "乙", "丙"), items.map { it.title })
        assertEquals(HomeIcon.Remote(nested), items[0].icon)
        assertEquals(HomeIcon.Remote(flat), items[1].icon)
        assertEquals(HomeIcon.Remote(nested), items[2].icon)
    }

    @Test
    fun svgIconAssetDropsOnlyThatItem() {
        // (e)：首页远程图按位图解码，渲染不了 svg（FU-AND-HOME-ICON-SVG）
        val items = items(entry("甲", "icon" to asset("https://cdn.example.com/icons/$HEX_A.svg", HEX_A)), entry("乙"))
        assertEquals(listOf("乙"), items.map { it.title })
    }

    @Test
    fun invalidIconAssetDropsOnlyThatItem() {
        val png = "https://cdn.example.com/icons/$HEX_A.png"
        val cases =
            linkedMapOf(
                "http" to asset("http://cdn.example.com/icons/$HEX_A.png", HEX_A),
                "文件名不是 64 位 hex" to asset("https://cdn.example.com/icons/icon-a.png", HEX_A),
                "文件名 hex 大写" to asset("https://cdn.example.com/icons/${HEX_A.uppercase()}.png", HEX_A),
                "扩展名 jpg" to asset("https://cdn.example.com/icons/$HEX_A.jpg", HEX_A),
                "带查询串" to asset("$png?v=1", HEX_A),
                "带片段" to asset("$png#a", HEX_A),
                "没有主机" to asset("https:///$HEX_A.png", HEX_A),
                "url 超过 2048" to asset("https://cdn.example.com/" + "d/".repeat(LONG_DIRS) + "$HEX_A.png", HEX_A),
                "sha256 63 位" to asset(png, HEX_A.drop(1)),
                "sha256 大写" to asset(png, HEX_A.uppercase()),
                "url 不是字符串" to """{"url":1,"sha256":"$HEX_A"}""",
                "sha256 不是字符串" to """{"url":"$png","sha256":1}""",
                "缺 url" to """{"sha256":"$HEX_A"}""",
                "缺 sha256" to """{"url":"$png"}""",
                "多出键" to """{"url":"$png","sha256":"$HEX_A","width":48}""",
                "空对象" to """{}""",
            )
        val entries = cases.entries.mapIndexed { index, case -> entry("格$index", "icon" to case.value) }
        val items = items(*(entries + entry("好格")).toTypedArray())
        assertEquals("不合法 icon 对象只丢那一条（${cases.keys}）", listOf("好格"), items.map { it.title })
    }

    // ---------------------------------------------------------------- image

    @Test
    fun imageWithoutIconFillsThePictureSlot() {
        val items = items(entry("甲", "icon" to null, "image" to "\"$IMAGE\""), entry("乙"))
        assertEquals("只给 image 的条目保留", listOf("甲", "乙"), items.map { it.title })
        assertEquals(HomeIcon.Remote(IMAGE), items[0].icon)
    }

    @Test
    fun iconObjectWinsOverImage() {
        val url = "https://cdn.example.com/icons/$HEX_A.png"
        val items = items(entry("甲", "icon" to asset(url, HEX_A), "image" to "\"$IMAGE\""))
        assertEquals(listOf("甲"), items.map { it.title })
        assertEquals("icon 与 image 都有时以 icon 为准", HomeIcon.Remote(url), items[0].icon)
    }

    @Test
    fun iconStringWinsOverImage() {
        val items = items(entry("甲", "image" to "\"$IMAGE\""), entry("乙", "icon" to "\"builtin:guide\"", "image" to "\"$IMAGE\""))
        assertEquals(listOf("甲", "乙"), items.map { it.title })
        assertEquals(HomeIcon.Remote(ICON), items[0].icon)
        assertEquals(HomeIcon.Builtin("guide"), items[1].icon)
    }

    @Test
    fun invalidIconDoesNotFallBackToImage() {
        val items =
            items(
                entry("坏串", "icon" to "\"ftp://img.example.com/i.png\"", "image" to "\"$IMAGE\""),
                entry("坏名", "icon" to "\"builtin:nope\"", "image" to "\"$IMAGE\""),
                entry("svg", "icon" to asset("https://cdn.example.com/$HEX_A.svg", HEX_A), "image" to "\"$IMAGE\""),
                entry("坏对象", "icon" to asset("https://cdn.example.com/$HEX_A.png", "x"), "image" to "\"$IMAGE\""),
                entry("类型", "icon" to "1", "image" to "\"$IMAGE\""),
                entry("好格"),
            )
        assertEquals("icon 不合法丢这一条，不回落 image", listOf("好格"), items.map { it.title })
    }

    @Test
    fun invalidImageWithoutIconDropsOnlyThatItem() {
        val items =
            items(
                entry("http", "icon" to null, "image" to "\"http://img.example.com/m.png\""),
                entry("空串", "icon" to null, "image" to "\"\""),
                entry("只有协议", "icon" to null, "image" to "\"https://\""),
                entry("数字", "icon" to null, "image" to "1"),
                entry("都没有", "icon" to null),
                entry("好格"),
            )
        assertEquals(listOf("好格"), items.map { it.title })
    }

    // ---------------------------------------------------------------- 工具

    private fun items(vararg entries: String): List<IconItem> {
        val page =
            Json
                .parseToJsonElement(
                    """{"page_key":"home","version":1,"sections":[{"id":"icons","type":"icon_grid","version":1,""" +
                        """"props":{"columns":4},"data_source":{"kind":"static","items":[${entries.joinToString(",")}]}}]}""",
                ).jsonObject
        val parsed = HomeJson.parse(page, "android", "1.10.0")
        assertEquals("卡片本身不受条目影响", listOf("icons"), parsed.kept.map { it.id })
        return parsed.cards
            .filterIsInstance<HomeCard.IconGrid>()
            .single()
            .items
    }

    /** 一个条目：默认 icon / title / action；fields 里值为 null 的键去掉，其余按原始 JSON 覆盖或追加。 */
    private fun entry(
        title: String,
        vararg fields: Pair<String, String?>,
    ): String {
        val map = linkedMapOf<String, String?>("icon" to "\"$ICON\"", "title" to "\"$title\"", "action" to """{"route":"Help"}""")
        fields.forEach { (key, value) -> map[key] = value }
        val body = map.entries.filter { it.value != null }.joinToString(",") { "\"${it.key}\":${it.value}" }
        return "{$body}".also { check(Json.parseToJsonElement(it) is JsonObject) { "测试数据不是 JSON 对象：$it" } }
    }

    private fun asset(
        url: String,
        sha: String,
    ): String = """{"url":"$url","sha256":"$sha"}"""

    private fun ms(text: String): Long = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    private fun getter(
        target: Any,
        name: String,
    ): Method? {
        val cap = name.replaceFirstChar { it.uppercaseChar() }
        return target.javaClass.methods.firstOrNull { it.parameterCount == 0 && (it.name == "get$cap" || it.name == "is$cap") }
    }

    private fun badgeOf(item: IconItem): Any? {
        val method = getter(item, "badge") ?: throw AssertionError("IconItem 读不到 badge")
        return method.invoke(item)
    }

    /** 徽标种类：读 kind（字符串或枚举），没有 kind 时按类名（如 Text / Dot / None）。 */
    private fun kindOf(badge: Any): String {
        if (badge is String) return KIND_TEXT
        val raw = (getter(badge, "kind")?.invoke(badge) ?: badge.javaClass.simpleName).toString().lowercase()
        return listOf(KIND_TEXT, KIND_DOT, KIND_NONE).firstOrNull { it in raw } ?: raw
    }

    /** 显示的徽标文字：字符串模型即文字；对象模型只有 kind = text 时取 text。 */
    private fun shown(item: IconItem): String? =
        when (val badge = badgeOf(item)) {
            null -> null
            is String -> badge
            else -> if (kindOf(badge) == KIND_TEXT) getter(badge, "text")?.invoke(badge) as String? else null
        }

    /** 到期时刻（绝对毫秒）：先找徽标上名字含 expire 的字段，再找条目上的。 */
    private fun expireMs(item: IconItem): Long? {
        val holders = listOfNotNull(badgeOf(item)?.takeIf { it !is String }, item)
        holders.forEach { holder ->
            val method =
                holder.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && it.name.startsWith("get") && it.name.contains("expire", ignoreCase = true)
                } ?: return@forEach
            return when (val value = method.invoke(holder)) {
                null -> null
                is Long -> value
                is Number -> value.toLong()
                is Instant -> value.toEpochMilli()
                is OffsetDateTime -> value.toInstant().toEpochMilli()
                else -> throw AssertionError("到期字段类型认不出：${value.javaClass}")
            }
        }
        throw AssertionError("徽标与条目上都找不到名字含 expire 的字段")
    }

    private companion object {
        const val ICON = "https://img.example.com/i.png"
        const val IMAGE = "https://img.example.com/m.png"
        const val HEX_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val HEX_B = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
        const val LONG_DIRS = 1020
        const val KIND_TEXT = "text"
        const val KIND_DOT = "dot"
        const val KIND_NONE = "none"
    }
}
