package com.couli.core.home

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** G-V04/G-V10/G-V11：两张视觉 fixture 独立验证；不往既有跨端判定向量添特殊口径。 */
class HomeVisualFixtureTest {
    private val pages: JsonObject by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").canonicalFile
        val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        Json
            .parseToJsonElement(File(root, "specs/home-sdui-vectors.json").readText())
            .jsonObject
            .getValue("goldenPages")
            .jsonObject
    }

    @Test
    fun bothVisualPagesDecodeOnAllPlatformsWithoutDroppedCards() {
        listOf("main_v3", "guest_v3").forEach { name ->
            listOf("android", "ios", "harmony").forEach { platform ->
                val page = HomeJson.parse(pages.getValue(name).jsonObject, platform, "1.10.0")
                assertTrue("$name/$platform", page.valid)
                assertEquals(listOf("icons", "scroll", "activities", "grid", "feed"), page.kept.map { it.id })
                assertTrue(page.dropped.isEmpty())
                assertTrue(page.skipped.isEmpty())
            }
        }
    }

    @Test
    fun guestUsesSameProductsAndOnlyServerSuppliedActivityCountDiffers() {
        val main = pages.getValue("main_v3").jsonObject
        val guest = pages.getValue("guest_v3").jsonObject
        assertEquals(main.getValue("sectionItems"), guest.getValue("sectionItems"))
        val mainSections = main.getValue("sections").jsonArray
        val guestSections = guest.getValue("sections").jsonArray
        assertEquals(mainSections.filterIndexed { index, _ -> index != 2 }, guestSections.filterIndexed { index, _ -> index != 2 })
        assertEquals(2, activities("main_v3").size)
        assertEquals(listOf(activities("main_v3").first()), activities("guest_v3"))
    }

    @Test
    fun defaultKeepsBothActivitiesAndAddsOnlyTheirExistingSubtitleFields() {
        val items = activities("default_home")
        assertEquals(
            listOf("三步看懂", "填订单号找回"),
            items.map {
                it.jsonObject
                    .getValue("subtitle")
                    .jsonPrimitive.content
            },
        )
        assertEquals(
            listOf("Help", "FindOrder"),
            items.map {
                it.jsonObject
                    .getValue("action")
                    .jsonObject
                    .getValue("route")
                    .jsonPrimitive.content
            },
        )
    }

    @Test
    fun addingLoginConditionToMainDoesNotRemoveItsActivityOnTheClient() {
        val main = pages.getValue("main_v3").jsonObject
        val sections =
            main.getValue("sections").jsonArray.map { element ->
                val section = element.jsonObject
                JsonObject(section + ("condition" to JsonObject(mapOf("login" to JsonPrimitive(true)))))
            }
        val decoded = HomeJson.parse(JsonObject(main + ("sections" to JsonArray(sections))), "android", "1.10.0")
        assertEquals(5, decoded.kept.size)
        assertEquals(2, (decoded.kept.filterIsInstance<HomeCard.ActivityEntry>().single()).items.size)
    }

    private fun activities(name: String): List<kotlinx.serialization.json.JsonElement> =
        pages
            .getValue(name)
            .jsonObject
            .getValue("sections")
            .jsonArray
            .single {
                it.jsonObject
                    .getValue("type")
                    .jsonPrimitive.content == "activity_entry"
            }.jsonObject
            .getValue("data_source")
            .jsonObject
            .getValue("items")
            .jsonArray
}
