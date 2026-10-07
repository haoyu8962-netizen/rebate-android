package com.couli.feature.web

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** APP-04 三端共性第 23 条：ui.setNavBar 按契约的全部参数生效；本端做不到的参数（bg_image）整次不生效并返回失败。 */
class NavBarStateTest {
    private fun params(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun everyContractParameterTakesEffect() {
        val state = NavBarState()
        val applied =
            state.apply(
                params(
                    """{"visible":true,"title":"Orders","bg_gradient":["#112233","#445566CC"],"text_color":"#FFFFFF",""" +
                        """"status_bar_style":"light","immersive":true,"hide_close":true,"hide_bottom_safe_area":true,"bounce":false}""",
                ),
            )
        assertTrue(applied)
        assertEquals("Orders", state.title)
        assertEquals(listOf(Color(0xFF112233), Color(0xCC445566)), state.background)
        assertEquals(Color.White, state.textColor)
        assertEquals("light", state.statusBarStyle)
        assertTrue(state.immersive)
        assertTrue(state.hideClose)
        assertTrue(state.hideBottomSafeArea)
        assertEquals(false, state.bounce)

        assertTrue(state.apply(params("""{"visible":false,"bg_color":"#000000"}""")))
        assertFalse(state.visible)
        assertEquals(listOf(Color.Black), state.background)
        assertEquals("unspecified parameters keep their value", "Orders", state.title)
    }

    @Test
    fun unsupportedBackgroundImageFailsAndChangesNothing() {
        val state = NavBarState()
        assertFalse(state.apply(params("""{"title":"Changed","bg_image":"https://img.example.test/a.png"}""")))
        assertNull(state.title)
        assertTrue(state.visible)
    }

    @Test
    fun unparsableValuesFailWithoutPartialEffect() {
        val state = NavBarState()
        assertFalse(state.apply(params("""{"title":"Changed","bg_color":"red"}""")))
        assertFalse(state.apply(params("""{"title":"Changed","unknown":1}""")))
        assertNull(state.title)
    }
}
