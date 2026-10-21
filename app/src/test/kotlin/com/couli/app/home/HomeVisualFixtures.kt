package com.couli.app.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.SystemClipboard
import com.couli.core.designsystem.ProductCardTexts
import com.couli.core.designsystem.ProductImageLoader
import com.couli.core.home.HomeCall
import com.couli.core.home.HomeContractPort
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.PageApi
import com.couli.core.privacy.PrivacyStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** 只读共用向量；sectionItems 是截图服务的响应，不交给生产解码器当新协议。图片为本地色块，不复制设计稿。 */
internal object HomeVisualFixtures {
    const val NOW = 1_791_604_800_000L
    const val GUEST_CARD = "home_guest_value"
    const val GUEST_LOGIN = "home_guest_login"
    const val PASTE_ILLUSTRATION = "home_paste_illustration"

    fun page(name: String): JsonObject {
        val start = File(System.getProperty("user.dir") ?: ".").canonicalFile
        val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        return Json
            .parseToJsonElement(File(root, "specs/home-sdui-vectors.json").readText())
            .jsonObject
            .getValue("goldenPages")
            .jsonObject
            .getValue(name)
            .jsonObject
    }

    /** MainRankEntry-v3 只换服务端第九格；不增加第三张 golden，也不添加 HotRankList 客户端分支。 */
    fun rankPage(): JsonObject {
        val main = page("main_v3")
        val sections = main.getValue("sections").jsonArray.toMutableList()
        val icons = sections.first().jsonObject
        val source = icons.getValue("data_source").jsonObject
        val items = source.getValue("items").jsonArray.toMutableList()
        items[8] =
            JsonObject(
                mapOf(
                    "title" to JsonPrimitive("热销榜"),
                    "icon" to JsonPrimitive("https://img.example.test/home-v3/rank.png"),
                    "action" to JsonObject(mapOf("route" to JsonPrimitive("HotRankList"))),
                ),
            )
        sections[0] = JsonObject(icons + ("data_source" to JsonObject(source + ("items" to JsonArray(items)))))
        return JsonObject(main + ("sections" to JsonArray(sections)))
    }

    val config: JsonObject =
        Json
            .parseToJsonElement(
                """{"clipboard":{"consent_version":"1"},"features":{"flags":{},"platform_status":{"taobao":"on"},""" +
                    """"search_status":{"taobao":"on"}}}""",
            ).jsonObject

    val cardTexts =
        ProductCardTexts {
            com.couli.app.search.SearchDraftTexts
                .text(it)
        }
    val images =
        ProductImageLoader { url ->
            if (!url.startsWith("https://")) {
                null
            } else {
                val bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
                val hue = (url.hashCode() and 0xff) / 255f * 360f
                Canvas(bitmap).drawColor(Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.9f)))
                bitmap.asImageBitmap()
            }
        }
}

internal class HomeVisualPort(
    var page: JsonObject,
) : HomeContractPort {
    var hold: CompletableDeferred<Unit>? = null
    val pageCalls = mutableListOf<HomeCall>()
    val itemCalls = mutableListOf<Pair<String, HomeCall>>()

    override suspend fun getPage(
        pageKey: String,
        call: HomeCall,
    ): ApiResult {
        if (!call.allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
        pageCalls += call
        hold?.await()
        return ApiResult.Success(JsonObject(page - "sectionItems"), "visual-page", null)
    }

    override suspend fun listSectionItems(
        pageKey: String,
        sectionId: String,
        cursor: String?,
        call: HomeCall,
    ): ApiResult {
        if (!call.allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
        check(cursor == null) { "画板样例只有一页" }
        itemCalls += sectionId to call
        return ApiResult.Success(page.getValue("sectionItems").jsonObject.getValue(sectionId), "visual-items", null)
    }
}

internal class HomeVisualApi : PageApi {
    val calls = mutableListOf<ApiCall>()

    override suspend fun send(call: ApiCall): ApiResult {
        calls += call
        return ApiResult.Success(JsonObject(emptyMap()), "visual-api", null)
    }

    override fun cancelOwned(owner: CallOwnership) = Unit

    override fun epochs(): CallEpochs = CallEpochs(0, 0)
}

internal class HomeVisualClipboard : SystemClipboard {
    var reads = 0
    var content: ClipRead = ClipRead.Unavailable

    override fun read(): ClipRead {
        reads += 1
        return content
    }

    override fun write(
        text: String,
        label: String,
    ): Boolean = error("首页视觉测试不应写剪贴板")
}

internal class HomeVisualPrivacyStore(
    decision: String,
) : PrivacyStore {
    override var decisionRaw: String = decision
    override var consentedVersion: String? = "3"
    override var consentedAtMillis: Long? = 0L
    override var basicMode: Boolean = decision == "basic_mode"
    override var withdrawalCleanupPending: Boolean = false
}
