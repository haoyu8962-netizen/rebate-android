package com.couli.app.product

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import com.couli.core.designsystem.ProductImageLoader
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** 三端共享 G-D16 fixture。宽图的红、绿、蓝三段用于抓 Crop；不复制画板或请求网络。 */
internal object ProductVisualFixtures {
    val root: File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").canonicalFile) { it.parentFile }
            .first { File(it, "contract.lock").isFile }
    private val group =
        Json
            .parseToJsonElement(File(root, "specs/search-product-vectors.json").readText())
            .jsonObject
            .getValue("visualCards")
            .jsonObject
    val now: Long =
        group
            .getValue("clock")
            .jsonObject
            .getValue("nowMillis")
            .jsonPrimitive.long
    val cards: JsonObject = group.getValue("cards").jsonObject

    fun card(name: String = "tb_coupon_range"): JsonObject = cards.getValue(name).jsonObject

    val images =
        ProductImageLoader { url ->
            check(url.startsWith("https://img.example.test/product-v/")) { "未知 fixture 图片：$url" }
            val bitmap = Bitmap.createBitmap(400, 120, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.GREEN)
            val paint = Paint().apply { color = Color.RED }
            canvas.drawRect(0f, 0f, 80f, 120f, paint)
            paint.color = Color.BLUE
            canvas.drawRect(320f, 0f, 400f, 120f, paint)
            bitmap.asImageBitmap()
        }
}
