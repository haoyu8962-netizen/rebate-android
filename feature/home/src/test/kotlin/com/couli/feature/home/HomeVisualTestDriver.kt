package com.couli.feature.home

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.robolectric.Shadows.shadowOf

/** 仍经真实控制器加载 sectionItems；默认读画板数据，r1 压力输入与参照渲染仅由测试参数提供。 */
internal class HomeVisualTestDriver(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    lateinit var controller: HomeController
        private set

    fun show(
        section: String,
        scale: Float = 1f,
        fixtureName: String = "main_v3",
        fixture: JsonObject = HomeTestKit.golden(fixtureName),
        wrap: @Composable (@Composable () -> Unit) -> Unit = { it() },
    ) {
        val page =
            JsonObject(
                fixture + (
                    "sections" to
                        JsonArray(
                            fixture.getValue("sections").jsonArray.filter {
                                it.jsonObject
                                    .getValue("id")
                                    .jsonPrimitive.content == section
                            },
                        )
                ),
            )
        val port =
            FakePort { HomeTestKit.ok(page) }.apply {
                items = { id, cursor ->
                    check(cursor == null)
                    HomeTestKit.ok(fixture.getValue("sectionItems").jsonObject.getValue(id))
                }
            }
        val signals = FakeSignals()
        controller = HomeController(FakeHost.IDENTITY, testDeps(port = port, signals = signals))
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, scale),
                LocalProductImageLoader provides HomeTestKit.images,
            ) {
                CouliTheme(darkTheme = false) { wrap { HomeSduiScreen(controller, signals) } }
            }
        }
        rule.runOnUiThread { controller.start() }
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.waitForIdle()
        }
    }

    override fun close() {
        if (::controller.isInitialized) rule.runOnUiThread { controller.invalidate() }
    }
}
