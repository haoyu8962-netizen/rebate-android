package com.couli.feature.home

import android.app.Application
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.Method
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 金刚区徽标的显示与到期（FU-AND-HOME-ENTRY-OBJECT-FIELDS，D10-2998）：控制器把徽标算成格子上的显示文字，视图只做纯展示。
 * - kind = text 显示 text；none / dot / 不合法 / 旧的字符串 badge 都不显示，格子照常保留；
 * - expire_at 到点（本机时钟）隐藏徽标、保留格子：照公告条的做法在 expire_at 排一次重算（只靠计时，不重拉页面）。
 *
 * 格子上的徽标经反射读 `badge`（字符串即显示文字；对象只有 kind = text 时取 text），不引用新符号，保证在 3fd6a5c 上能编译、靠断言变红。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class HomeEntryBadgeControllerTest {
    private var now = HomeTestKit.NOON
    private val clock: () -> Long = { now }
    private val timers = ManualTimers(clock)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun textBadgeObjectIsShownOnTheCell() {
        val controller = render(entry("甲", """{"kind":"text","text":"新"}"""), entry("乙", null))
        assertEquals("带 text 徽标对象的格子保留", listOf("甲", "乙"), titles(controller))
        assertEquals("新", badge(controller, "甲"))
        assertNull(badge(controller, "乙"))
    }

    @Test
    fun legacyStringBadgeKeepsTheCellWithoutBadge() {
        val controller = render(entry("甲", "\"测试\""), entry("乙", null))
        assertEquals("字符串 badge 的格子保留（不是整条丢）", listOf("甲", "乙"), titles(controller))
        assertNull("字符串 badge 契约已不允许：只丢徽标", badge(controller, "甲"))
    }

    @Test
    fun dotNoneAndInvalidBadgesKeepTheCellsWithoutBadge() {
        val controller =
            render(
                entry("点", """{"kind":"dot"}"""),
                entry("无", """{"kind":"none"}"""),
                entry("坏", """{"kind":"red","text":"新"}"""),
                entry("长", """{"kind":"text","text":"三个字"}"""),
            )
        assertEquals(listOf("点", "无", "坏", "长"), titles(controller))
        listOf("点", "无", "坏", "长").forEach { assertNull("$it：不显示徽标", badge(controller, it)) }
    }

    @Test
    fun badgeHidesAtExpireAtWhileTheCellStays() {
        val expire = "2026-10-10T12:01:00+08:00"
        val at = OffsetDateTime.parse(expire).toInstant().toEpochMilli()
        val entries = arrayOf(entry("甲", """{"kind":"text","text":"新","expire_at":"$expire"}"""), entry("乙", null))
        val port = FakePort(page = { HomeTestKit.ok(page(*entries)) })
        val controller = start(port)
        assertEquals(listOf("甲", "乙"), titles(controller))
        assertEquals("到点前显示", "新", badge(controller, "甲"))
        val fetches = port.pageCalls.size
        now = at - 1
        timers.fire(now)
        idle()
        assertEquals("差 1 毫秒仍显示", "新", badge(controller, "甲"))
        now = at
        timers.fire(now)
        idle()
        assertNull("到点（本机时钟）由排好的重算隐藏徽标", badge(controller, "甲"))
        assertEquals("格子保留", listOf("甲", "乙"), titles(controller))
        assertEquals("到期重算不重拉页面", fetches, port.pageCalls.size)
    }

    @Test
    fun badgeAlreadyPastExpireAtIsHiddenOnFirstRender() {
        val controller =
            render(
                entry("甲", """{"kind":"text","text":"新","expire_at":"2026-10-10T11:59:59+08:00"}"""),
                entry("乙", """{"kind":"text","text":"热","expire_at":"2026-10-10T12:00:00+08:00"}"""),
                entry("丙", """{"kind":"text","text":"惠","expire_at":"2026-10-10T12:00:00.001+08:00"}"""),
            )
        assertEquals("过期徽标的格子保留", listOf("甲", "乙", "丙"), titles(controller))
        assertNull("已过 expire_at", badge(controller, "甲"))
        assertNull("正好到点也隐藏", badge(controller, "乙"))
        assertEquals("还差 1 毫秒", "惠", badge(controller, "丙"))
    }

    // ---------------------------------------------------------------- 工具

    private fun render(vararg entries: String): HomeController = start(FakePort(page = { HomeTestKit.ok(page(*entries)) }))

    private fun start(port: FakePort): HomeController {
        val controller = HomeController(FakeHost.IDENTITY, testDeps(port = port, clock = clock, timers = timers))
        controller.start()
        idle()
        return controller
    }

    private fun page(vararg entries: String) =
        Json
            .parseToJsonElement(
                """{"page_key":"home","version":7,"sections":[{"id":"icons","type":"icon_grid","version":1,""" +
                    """"props":{"columns":4},"data_source":{"kind":"static","items":[${entries.joinToString(",")}]}}]}""",
            ).jsonObject

    private fun entry(
        title: String,
        badge: String?,
    ): String =
        """{"icon":"https://img.example.com/$title.png","title":"$title","action":{"route":"Help"}""" +
            (badge?.let { ""","badge":$it""" } ?: "") + "}"

    private fun cells(controller: HomeController): List<HomeIconCell> =
        controller.state.value.cards
            .filterIsInstance<HomeCardUi.IconGrid>()
            .singleOrNull()
            ?.cells
            .orEmpty()

    private fun titles(controller: HomeController): List<String> = cells(controller).map { it.title }

    /** 格子上显示的徽标文字。 */
    private fun badge(
        controller: HomeController,
        title: String,
    ): String? {
        val cell = cells(controller).firstOrNull { it.title == title } ?: throw AssertionError("没有格子 $title")
        return when (val value = (getter(cell, "badge") ?: throw AssertionError("HomeIconCell 读不到 badge")).invoke(cell)) {
            null -> null
            is String -> value
            else -> if ("text" in kindOf(value)) getter(value, "text")?.invoke(value) as String? else null
        }
    }

    private fun kindOf(value: Any): String = (getter(value, "kind")?.invoke(value) ?: value.javaClass.simpleName).toString().lowercase()

    private fun getter(
        target: Any,
        name: String,
    ): Method? {
        val cap = name.replaceFirstChar { it.uppercaseChar() }
        return target.javaClass.methods.firstOrNull { it.parameterCount == 0 && (it.name == "get$cap" || it.name == "is$cap") }
    }
}
