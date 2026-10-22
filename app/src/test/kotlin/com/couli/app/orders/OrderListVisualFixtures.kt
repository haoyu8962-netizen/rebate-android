package com.couli.app.orders

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import com.couli.core.designsystem.ProductImageLoader
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** G-O11：列表报文唯一来源是共享 visualOrders；无网络、无设计稿副本。 */
internal object OrderListVisualFixtures {
    val root: File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").canonicalFile) { it.parentFile }
            .first { File(it, "contract.lock").isFile }
    val group: JsonObject =
        Json
            .parseToJsonElement(File(root, "specs/order-vectors.json").readText())
            .jsonObject
            .getValue("visualOrders")
            .jsonObject
    private val clock = group.getValue("clock").jsonObject
    val now = clock.getValue("nowMillis").jsonPrimitive.long
    val staleAt = clock.getValue("staleAtMillis").jsonPrimitive.long

    fun page(name: String = "list_self"): JsonObject =
        group
            .getValue("pages")
            .jsonObject
            .getValue(name)
            .jsonObject

    fun statusPage(status: String): JsonObject =
        group
            .getValue("statusPages")
            .jsonObject
            .getValue(status)
            .jsonObject

    fun one(id: String = "A"): JsonObject {
        val source = page(if (id in setOf("E", "OTHER", "F", "G")) "list_share" else "list_self")
        val item =
            source.getValue("items").jsonArray.single {
                it.jsonObject
                    .getValue("order_id")
                    .jsonPrimitive.content == id
            }
        return JsonObject(source + ("items" to JsonArray(listOf(item))) + ("next_cursor" to JsonNull))
    }

    fun pinned(
        page: JsonObject,
        value: Boolean,
    ): JsonObject = JsonObject(page + ("claim_entry_pinned" to JsonPrimitive(value)))

    val images =
        ProductImageLoader { url ->
            check(url.startsWith("https://img.example.test/order-v/")) { "未知订单测试图片：$url" }
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (url.endsWith("A.png")) Color.rgb(222, 236, 216) else Color.rgb(219, 238, 241))
            val paint = Paint().apply { color = Color.rgb(32, 151, 153) }
            canvas.drawRect(32f, 20f, 96f, 112f, paint)
            paint.color = Color.WHITE
            canvas.drawRect(40f, 48f, 88f, 96f, paint)
            bitmap.asImageBitmap()
        }
}
