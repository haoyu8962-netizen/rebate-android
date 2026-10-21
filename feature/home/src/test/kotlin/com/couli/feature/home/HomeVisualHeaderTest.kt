package com.couli.feature.home

import android.app.Application
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A1：新增 header 槽与 key 接口的红测；不得把客户端头部塞进服务端 card 命名空间。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
class HomeVisualHeaderTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun nativeKeysCannotCollideWithAnyCardOrFooterEvenForAdversarialIds() {
        val names = listOf("prompt", "guest", "paste", "", "disclaimer", "card:5:paste", "a:b#0", "头部")
        val headers = names.map(HomeListKeys::header)
        assertEquals(names.size, headers.toSet().size)
        (names + headers).forEach { id ->
            listOf(HomeListKeys.card(id), HomeListKeys.feedRow(id, 0), HomeListKeys.feedRetry(id), HomeListKeys.extra(id)).forEach {
                assertFalse("头部不能与 $it 碰撞", it in headers)
            }
        }
    }

    @Test
    fun headerSlotIsAnItemOfTheSameListAsAnAdversarialSduiCard() {
        val page = HomeTestKit.golden("activity_entry")
        val id = HomeListKeys.header("paste")
        val section =
            JsonObject(
                page
                    .getValue("sections")
                    .jsonArray
                    .single()
                    .jsonObject + ("id" to JsonPrimitive(id)),
            )
        val port = FakePort { HomeTestKit.ok(JsonObject(page + ("sections" to JsonArray(listOf(section))))) }
        val signals = FakeSignals()
        val controller = HomeController(FakeHost.IDENTITY, testDeps(port = port, signals = signals))
        try {
            rule.setContent {
                CouliTheme(darkTheme = false) {
                    HomeSduiScreen(controller, signals, header = {
                        item(key = HomeListKeys.header("paste")) { Text("原生头部样例", Modifier.testTag("native-header")) }
                    })
                }
            }
            rule.runOnUiThread { controller.start() }
            repeat(6) {
                shadowOf(Looper.getMainLooper()).idle()
                rule.waitForIdle()
            }
            val inList = hasAnyAncestor(hasTestTag(HomeTags.LIST))
            rule.onNodeWithTag("native-header").assert(inList)
            rule.onNodeWithTag(HomeTags.CARD_PREFIX + id).assert(inList)
        } finally {
            rule.runOnUiThread { controller.invalidate() }
        }
    }
}
