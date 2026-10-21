package com.couli.core.designsystem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** V3：读取 Android AssetManager 合并后的包内资源；测试源清单独立锁定 couli 提交，不能自证哈希。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class Fx05aPlatformAssetTest {
    private val assets get() = ApplicationProvider.getApplicationContext<Context>().assets

    @Test
    fun manifestAndEveryPackagedImageMatchThePinnedOfficialSources() {
        val sources =
            requireNotNull(javaClass.getResourceAsStream("/fx05a/couli-platform-sources.json"))
                .bufferedReader()
                .use { Json.parseToJsonElement(it.readText()).jsonObject }
        val manifest = assets.open("platform/manifest.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        assertEquals(1, manifest.number("schema_version"))
        assertEquals(sources.string("source_commit"), manifest.string("source_commit"))
        val official = sources.rows("files").associateBy { it.string("file") }
        val entries = manifest.rows("files")
        val names = entries.map { it.string("file") }
        assertEquals("manifest 不得重复登记", names.size, names.toSet().size)
        assertTrue("APP-11v 首批九个平台必须入包", names.containsAll(requiredOriginals))
        assertEquals("不得夹带未登记图片、凑狸品牌图或子目录", names.toSet() + "manifest.json", packagedFiles() - "README.md")
        entries.forEach { entry -> checkEntry(entry, official, entries) }
    }

    private fun packagedFiles(directory: String = "platform"): Set<String> =
        requireNotNull(assets.list(directory))
            .flatMap { name ->
                val path = "$directory/$name"
                if (requireNotNull(assets.list(path)).isNotEmpty()) {
                    packagedFiles(path)
                } else {
                    listOf(path.removePrefix("platform/"))
                }
            }.toSet()

    private fun checkEntry(
        entry: JsonObject,
        official: Map<String, JsonObject>,
        entries: List<JsonObject>,
    ) {
        val file = entry.string("file")
        assertTrue("非法包内路径 $file", file.matches(Regex("(?:[a-z0-9_-]+/)*[a-z0-9_-]+\\.png")))
        val source = requireNotNull(official[entry.string("source_file")]) { "非官方来源：$file" }
        assertEquals(source.string("sha256"), entry.string("source_sha256"))
        val bytes = assets.open("platform/$file").use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("包内字节：$file", entry.string("sha256"), digest)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        assertEquals(entry.number("width"), bitmap.width)
        assertEquals(entry.number("height"), bitmap.height)
        assertTrue("不得放大：$file", bitmap.width <= source.number("width") && bitmap.height <= source.number("height"))
        when (entry.string("transform")) {
            "identity" -> {
                assertEquals(source.string("file"), file)
                assertEquals("不改像素、不重压缩：$file", source.string("sha256"), digest)
                assertEquals(source.number("bytes"), bytes.size)
            }

            "android-bilinear" -> {
                checkDownscale(entry, source, entries, bitmap)
            }

            else -> {
                error("未知派生规则，不能只凭新哈希放行：$file")
            }
        }
    }

    private fun checkDownscale(
        entry: JsonObject,
        source: JsonObject,
        entries: List<JsonObject>,
        actual: Bitmap,
    ) {
        val original = entries.single { it.string("file") == entry.string("derived_from") }
        assertEquals("必须保留逐字节原图", "identity", original.string("transform"))
        assertEquals(source.string("file"), original.string("file"))
        assertTrue("派生必须严格缩小", actual.width < source.number("width"))
        assertEquals("不得拉伸", actual.width * source.number("height"), actual.height * source.number("width"))
        assertTrue(entry.number("target_dp") in setOf(14, 44))
        assertTrue(entry.number("density_dpi") in setOf(120, 160, 213, 240, 320, 480, 640))
        val pixels = (entry.number("target_dp") * entry.number("density_dpi") / 160f + 0.5f).toInt()
        assertEquals("density 派生尺寸必须登记并符合规则", pixels, actual.width)
        val bitmap = assets.open("platform/${original.string("file")}").use { requireNotNull(BitmapFactory.decodeStream(it)) }
        val expected = Bitmap.createScaledBitmap(bitmap, actual.width, actual.height, true)
        assertTrue("派生像素必须等于登记的双线性缩小，不能重画、改色或裁切", expected.sameAs(actual))
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.rows(key: String): List<JsonObject> = (getValue(key) as JsonArray).map { it.jsonObject }

    companion object {
        private val requiredOriginals =
            setOf("taobao.png", "jd.png", "pinduoduo.png", "vip.png", "douyin.png", "meituan.png", "eleme.png", "fliggy.png", "didi.png")
    }
}
