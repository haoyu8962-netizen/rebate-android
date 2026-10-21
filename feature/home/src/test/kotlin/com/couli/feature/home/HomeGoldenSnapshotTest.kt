package com.couli.feature.home

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * golden 样例与截图（F-HOME-02「每种卡有 golden 样例和截图」，AC-HOME-02，G29）：直接用不可改动的 specs/home-sdui-vectors.json 的 goldenPages 逐页驱动
 * （fixture 名 = 截图名 golden-<名字>），经同一个控制器与渲染器（本机替身，商品项与图片是测试数据），宽 390dp（与设计稿同宽）。另截 Main 组合页的字号、窄屏、大屏、
 * 短视口、公告关闭前后、置灰态。基准图在 src/test/snapshots（recordRoborazziDebug 录制，verifyRoborazziDebug 比对）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeGoldenSnapshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** 同一个测试里多次渲染：内容只设置一次，之后换掉槽位里的画面（setContent 每个测试只能调一次）。 */
    private var frame by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var contentSet = false
    private var activeController: HomeController? = null

    @After
    fun close() {
        composeRule.runOnUiThread { activeController?.invalidate() }
    }

    private fun render(
        page: JsonObject,
        widthDp: Int = 390,
        fontScale: Float = 1f,
        grayscale: Boolean = false,
        dark: Boolean = false,
    ): HomeController {
        val port = FakePort(page = { HomeTestKit.ok(page) })
        if ("sectionItems" in page) {
            port.items = { section, cursor ->
                check(cursor == null) { "视觉 fixture 不应翻页" }
                HomeTestKit.ok(page.getValue("sectionItems").jsonObject.getValue(section))
            }
        }
        val signals = FakeSignals()
        val controller = HomeController(FakeHost.IDENTITY, testDeps(signals = signals, port = port))
        if (!contentSet) {
            contentSet = true
            composeRule.setContent { frame?.invoke() }
        }
        composeRule.runOnIdle {
            activeController?.invalidate()
            activeController = controller
            frame = { key(controller.id) { Frame(controller, signals, widthDp, fontScale, grayscale, dark) } }
            controller.start()
        }
        idle()
        return controller
    }

    @Composable
    private fun Frame(
        controller: HomeController,
        signals: HomeSignals,
        widthDp: Int,
        fontScale: Float,
        grayscale: Boolean,
        dark: Boolean,
    ) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale),
            LocalProductImageLoader provides HomeTestKit.images,
        ) {
            CouliTheme(darkTheme = dark) {
                Box(
                    modifier =
                        Modifier
                            .width(widthDp.dp)
                            .fillMaxHeight()
                            .background(CouliColors.backgroundCanvas)
                            .homeGrayscale(grayscale),
                ) {
                    HomeSduiScreen(controller = controller, signals = signals)
                }
            }
        }
    }

    private fun idle() {
        repeat(ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
        }
    }

    private fun shot(name: String) = composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")

    @Test
    fun everyGoldenPageRendersThroughTheSameRenderer() {
        val pages = HomeTestKit.vectors().getValue("goldenPages").jsonObject
        assertEquals(17, pages.size)
        pages.keys.filter { it != "popup" }.forEach { name ->
            val controller = render(pages.getValue(name).jsonObject)
            assertTrue(name, controller.state.value.render.renderGen > 0)
            shot("golden-$name")
        }
    }

    @Test
    fun goldenPopupShowsCloseAboveImage() {
        val controller = render(HomeTestKit.golden("popup"))
        assertTrue(controller.state.value.popup != null)
        captureScreenRoboImage("src/test/snapshots/golden-popup.png")
    }

    @Test
    fun mainAtDesignFontScalesAndWidths() {
        listOf(false, true).forEach { dark ->
            val suffix = if (dark) "-dark" else ""
            render(MAIN, dark = dark)
            shot("Main$suffix")
            render(MAIN, fontScale = 1.3f, dark = dark)
            shot("Main130$suffix")
            render(MAIN, fontScale = 2f, dark = dark)
            shot("Main200$suffix")
            render(MAIN, widthDp = 320, dark = dark)
            shot("Main320$suffix")
            render(MAIN, widthDp = 320, fontScale = 2f, dark = dark)
            shot("Main320-200$suffix")
        }
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp-xhdpi")
    fun largeScreenKeepsContentWidthAndColumns() {
        render(MAIN, widthDp = 840)
        shot("MainLarge")
        render(MAIN, widthDp = 840, dark = true)
        shot("MainLarge-dark")
    }

    @Test
    fun noticeBeforeAndAfterClose() {
        render(LEGACY_MAIN)
        shot("NoticeBar-before")
        composeRule.onNodeWithTag("${HomeTags.NOTICE_CLOSE_PREFIX}notice_main_n1", useUnmergedTree = true).performClick()
        idle()
        shot("NoticeBar-after")
    }

    @Test
    fun bannerAndNoticesUseExplicitDarkTokens() {
        listOf("banner", "notice_static", "notice_api").forEach { name ->
            render(HomeTestKit.golden(name), dark = true)
            shot("golden-$name-dark")
        }
    }

    @Test
    fun grayscaleOnlyOnTheGivenRoot() {
        render(MAIN, grayscale = true)
        shot("Grayscale")
    }

    @Test
    @Config(qualifiers = "w390dp-h480dp-xhdpi")
    fun popupOnShortViewportKeepsCloseVisible() {
        listOf(false, true).forEach { dark ->
            val controller = render(MAIN_WITH_POPUP, dark = dark)
            assertTrue(controller.state.value.popup != null)
            composeRule.onNodeWithTag(HomeTags.POPUP_CLOSE, useUnmergedTree = true).assertExistsAndDisplayed()
            val suffix = if (dark) "-dark" else ""
            captureScreenRoboImage("src/test/snapshots/MainPopup-short$suffix.png")
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsAndDisplayed() {
        assertExists()
        assertTrue(fetchSemanticsNode().boundsInRoot.height > 0f)
    }

    companion object {
        private const val ROUNDS = 6

        /** Main-v3 的共用视觉页；旧组合仅用于保留公告关闭与弹窗回归。 */
        val MAIN: JsonObject get() = HomeTestKit.golden("main_v3")

        val LEGACY_MAIN: JsonObject =
            Json
                .parseToJsonElement(
                    """{"page_key":"home","version":31,"sections":[""" +
                        """{"id":"notice_main","type":"notice_bar","version":1,"props":{"items":[{"item_id":"n1","content_version":1,""" +
                        """"text":"确认收货后，返利随联盟月度结算入账","target":null,"closable":true,"start_at":"2026-10-01T00:00:00+08:00",""" +
                        """"end_at":null}]},"data_source":{"kind":"static"}},""" +
                        HomeTestKit
                            .golden("banner")
                            .getValue("sections")
                            .toString()
                            .removeSurrounding("[", "]") + "," +
                        HomeTestKit
                            .golden("default_home")
                            .getValue("sections")
                            .toString()
                            .removeSurrounding("[", "]") + "," +
                        HomeTestKit
                            .golden("product_scroll")
                            .getValue("sections")
                            .toString()
                            .removeSurrounding("[", "]") + "," +
                        HomeTestKit
                            .golden("product_grid")
                            .getValue("sections")
                            .toString()
                            .removeSurrounding("[", "]") + "," +
                        HomeTestKit
                            .golden("feed_infinite")
                            .getValue("sections")
                            .toString()
                            .removeSurrounding("[", "]") +
                        "]}",
                ).jsonObject

        val MAIN_WITH_POPUP: JsonObject =
            JsonObject(
                MAIN +
                    mapOf(
                        "sections" to
                            kotlinx.serialization.json.JsonArray(
                                MAIN.getValue("sections").let { it as kotlinx.serialization.json.JsonArray } +
                                    HomeTestKit.golden("popup").getValue("sections").let { it as kotlinx.serialization.json.JsonArray },
                            ),
                    ),
            )
    }
}
