// 假数据的金额、编号都是演示值，按值直接写
@file:Suppress("MagicNumber")

package com.couli.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Debug 专用的本机假购买服务（APP-06b 设计稿比对与冒烟用；src/debugFeatures，Release 不编译，release-scan 查 [FAKE_TRADE_SERVER_MARKER]）。
 * 与假登录服务同一个开关文件（[DebugFakeLoginServer] 接管时才回答），全部在本机回答，不连任何地址。不做真实付款、不拉起任何第三方 App
 * （方案里的 scheme 都是不存在的演示 scheme）。
 *
 * openLink 按 link_id 里的场景名回答（搜索关键词 trade 列出全部场景卡）：
 * - t-ok：未变，方案 sdk（百川不可用 → sdk_init_failed）→ 复制口令；t-price：改价；t-pricegone：改价且返利归零；t-gone：返利归零；
 *   t-coupon：券失效；t-compare（拼多多）：比价无返利；t-compareprice（拼多多）：改价 + 比价无返利，之后 getProduct 回答仍有返利的通用报价
 *   （A-R2-02）；t-restore：原卡暂无返利，open 返回正数返利、不给口径（返利临时展示，A-R2-01）；t-requote：复核失败用缓存；
 * - t-slow：第一次 10 秒后才回答（> 8 秒超时），同一个幂等键再发立即回答；
 * - t-auth：淘宝未授权 30101（auth_methods web_code）→ 授权后（bindings 成功）同一 link 再 open 正常；t-authsdk：只下发 sdk_token（本端
 *   执行不了 → 授权失败二选一）；t-unavail：30101 auth_unavailable；t-pdd（拼多多）：30111 auth_jump（h5 → 系统浏览器），回来后
 *   listUnionBindings 返回 pdd active；
 * - t-sdk：只有 sdk 一步；t-noinst：只有不存在的 scheme 一步（全部失败：安装状态 unknown → 通用跳转失败提示；状态开关文件
 *   installed.taobao=false → 未安装提示，A-R1-13）；t-browser（京东）：h5 一步；
 * - t-maint：50301 maintenance；t-notlaunched：50301 not_launched；t-busy：42901（Retry-After 10）；t-blocked：30153；t-conflict：30151；
 *   t-503：50303；t-offshelf：30141；t-login：游客 10001（已登录则正常）。
 * 其余：getTips / markTipRead（已读记在本进程）、listUnionBindings、getUnionAuthUrl、bindUnion、商品图（img.example.test，按 id 画一张渐变图）。
 * 状态开关文件 files/[FAKE_TRADE_STATE_FILE]（每行 key=value）：platform_status.<平台>=on|maintenance|not_launched、taobao_binding=blocked、
 * installed.<平台>=true|false|unknown（安装检测覆盖，见 variantJumpPorts）、bindings=busy|error（listUnionBindings 回答 42901
 * Retry-After 30 / 50001，A-R2-06）。复核过价格 / 返利的场景之后 getProduct 按新报价回答（[requoted]）。
 */
@Suppress("TooManyFunctions") // 每个接口一个场景函数
internal class DebugFakeTrade(
    private val stateFile: File,
) {
    private val tipsRead = ConcurrentHashMap.newKeySet<String>()
    private val bindings = ConcurrentHashMap<String, String>()
    private val seenKeys = ConcurrentHashMap.newKeySet<String>()
    private val requotes = ConcurrentHashMap<String, Change>()
    private var attempts = 0

    /** 回答：(code, data JSON, 响应头)；不是本服务的路径为 null。 */
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongParameterList") // 路径逐项；方法、路径、请求体、请求头、登录态与取消逐项
    fun answer(
        method: String,
        path: String,
        body: JsonObject,
        headers: Map<String, String>,
        loggedIn: Boolean,
        canceled: () -> Boolean,
    ): Triple<Int, String, Map<String, String>>? {
        val segments = path.trim('/').split('/')
        return when {
            path == "/v1/me/tips" -> {
                ok("""{"jump_tip":{${tipsRead.joinToString(",") { "\"$it\":\"2026-10-08T10:00:00+08:00\"" }}},"inviter_before_buy":null}""")
            }

            path == "/v1/me/tips/jump_tip/read" -> {
                (body["platform"] as? JsonPrimitive)?.content?.let(tipsRead::add)
                ok("{}")
            }

            path == "/v1/unions/bindings" -> {
                // 状态开关 bindings=busy|error：授权状态查询限流 / 服务端故障（A-R2-06 演示）
                when (state()[BINDINGS_QUERY]) {
                    "busy" -> Triple(42901, "null", mapOf(MARKER_HEADER to FAKE_TRADE_SERVER_MARKER, "Retry-After" to "30"))
                    "error" -> fail(50001)
                    else -> ok(bindingsJson())
                }
            }

            segments.size == 4 && segments[1] == "unions" && segments[3] == "auth-url" -> {
                authUrl(segments[2])
            }

            segments.size == 4 && segments[1] == "unions" && segments[3] == "bindings" && method == "POST" -> {
                bind(segments[2])
            }

            segments.size == 4 && segments[1] == "links" && segments[3] == "open" -> {
                open(segments[2], headers["Idempotency-Key"], body, loggedIn, canceled)
            }

            else -> {
                null
            }
        }
    }

    private fun state(): Map<String, String> =
        runCatching {
            stateFile
                .takeIf { it.exists() }
                ?.readLines()
                ?.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
                ?.toMap()
        }.getOrNull().orEmpty()

    /** features.platform_status 的覆盖（状态开关文件）。 */
    fun platformStatus(): Map<String, String> =
        state()
            .filterKeys {
                it.startsWith(STATUS_PREFIX)
            }.mapKeys { it.key.removePrefix(STATUS_PREFIX) }

    private fun bindingsJson(): String {
        val taobao = if (state()[TAOBAO_BINDING] == BLOCKED) BLOCKED else bindings["taobao"] ?: "active"
        val pdd = bindings["pdd"] ?: "unbound"
        return """{"items":[{"platform":"taobao","status":"$taobao"},{"platform":"jd","status":"unbound"},""" +
            """{"platform":"pdd","status":"$pdd"}]}"""
    }

    private fun authUrl(platform: String): Triple<Int, String, Map<String, String>> {
        if (platform == "taobao" && state()[TAOBAO_BINDING] == BLOCKED) return fail(30153)
        return if (platform == "pdd") {
            bindings["pdd"] = "active"
            ok("""{"auth_url":"https://auth.example.test/pdd/authorize?state=st_02","state":"st_02","auth_jump":${PDD_AUTH_JUMP}}""")
        } else {
            ok("""{"auth_url":"https://oauth.example.test/authorize?state=st_01","state":"st_01","auth_methods":["web_code"]}""")
        }
    }

    private fun bind(platform: String): Triple<Int, String, Map<String, String>> {
        bindings[platform] = "active"
        return ok("""{"platform":"$platform","status":"active"}""")
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList", "ReturnCount") // 场景逐项；场景逐项直接回答
    private fun open(
        linkId: String,
        key: String?,
        body: JsonObject,
        loggedIn: Boolean,
        canceled: () -> Boolean,
    ): Triple<Int, String, Map<String, String>> {
        val scene = linkId.substringAfterLast('.', "")
        val noRebate = (body["no_rebate"] as? JsonPrimitive)?.content == "true"
        if (!loggedIn && scene == "t-login") return fail(10001)
        when (scene) {
            "t-slow" -> if (key == null || seenKeys.add(key)) waitCancellable(SLOW_MILLIS, canceled)
            "t-auth" -> if (!noRebate && bindings["taobao"] != "active") return authRequired()
            "t-authsdk" -> if (!noRebate) return Triple(30101, AUTH_SDK_ONLY, emptyMap())
            "t-unavail" -> return Triple(30101, """{"reason":"auth_unavailable"}""", emptyMap())
            "t-pdd" -> if (!noRebate && bindings["pdd"] != "active") return Triple(30111, """{"auth_jump":$PDD_AUTH_JUMP}""", emptyMap())
            "t-maint" -> return Triple(50301, """{"platform":"taobao","reason":"maintenance"}""", emptyMap())
            "t-notlaunched" -> return Triple(50301, """{"platform":"taobao","reason":"not_launched"}""", emptyMap())
            "t-busy" -> return Triple(42901, "null", mapOf("Retry-After" to "10"))
            "t-blocked" -> if (!noRebate) return fail(30153)
            "t-conflict" -> if (!noRebate) return fail(30151)
            "t-503" -> return fail(50303)
            "t-offshelf" -> return fail(30141)
        }
        attempts += 1
        val attempt = "fake-attempt-${key?.takeLast(6) ?: attempts}"
        val (plan, change) =
            when (scene) {
                "t-price" -> {
                    TAOBAO_PLAN to Change(newFinal = 4990, newMin = 131, newMax = 250, priceChanged = true)
                }

                "t-pricegone" -> {
                    TAOBAO_PLAN to Change(newFinal = 4990, newMin = 0, newMax = 0, priceChanged = true)
                }

                "t-gone" -> {
                    TAOBAO_PLAN to Change(newMin = 0, newMax = 0)
                }

                "t-coupon" -> {
                    TAOBAO_PLAN to Change(newFinal = 5678, availability = "coupon_gone")
                }

                "t-compare" -> {
                    PDD_PLAN to Change(newMin = 0, newMax = 0, cause = "price_compare")
                }

                "t-compareprice" -> {
                    PDD_PLAN to
                        Change(newFinal = 4990, newMin = 0, newMax = 0, priceChanged = true, cause = "price_compare", genericQuote = true)
                }

                "t-requote" -> {
                    TAOBAO_PLAN to Change(requoteFailed = true)
                }

                "t-sdk" -> {
                    SDK_ONLY_PLAN to Change()
                }

                "t-noinst" -> {
                    NO_INSTALL_PLAN to Change()
                }

                "t-browser" -> {
                    BROWSER_PLAN to Change()
                }

                "t-pdd" -> {
                    PDD_PLAN to Change()
                }

                else -> {
                    TAOBAO_PLAN to Change()
                }
            }
        // 复核后的报价记下来：之后 getProduct（客户端重取权威卡片，A-R1-07 / 08）按新报价回答
        if (scene in REQUOTED_SCENES) requotes[scene] = change
        return ok(openResult(attempt, plan, change))
    }

    /**
     * 商品详情（getProduct）按 open 复核后的报价回答（演示服务端重取的权威卡片：券前、券额、预估返后、返利口径都是服务端给的完整值）。
     * 不是详情路径或没有复核过时原样返回。
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // 路径、场景、解析三个出口；演示报价逐字段
    fun requoted(
        path: String,
        data: String,
    ): String {
        if (!path.startsWith(PRODUCT_PATH) || path == SEARCH_PATH) return data
        val id = URLDecoder.decode(path.removePrefix(PRODUCT_PATH), "UTF-8").substringAfter(':')
        val change = requotes[id] ?: return data
        val card = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return data
        val out = LinkedHashMap<String, JsonElement>(card)
        val couponGone = change.availability == "coupon_gone"
        val coupon = if (couponGone) 0L else (card["coupon_fen"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        out["final_price_fen"] = JsonPrimitive(change.newFinal)
        out["coupon_fen"] = JsonPrimitive(coupon)
        out["price_fen"] = JsonPrimitive(change.newFinal + coupon)
        // 通用报价（详情接口不带本次 open 的比价预判，BR-PRICE-07 / 13）：仍有返利（A-R2-02 演示）
        val max = if (change.genericQuote) GENERIC_MAX else change.newMax ?: 0L
        val min = if (change.genericQuote) GENERIC_MIN else change.newMin ?: 0L
        out["rebate_min_fen"] = JsonPrimitive(min)
        out["rebate_max_fen"] = JsonPrimitive(max)
        if (max == 0L) {
            out["rebate_basis"] = JsonPrimitive("no_rebate")
            out["est_net_price_fen"] = JsonPrimitive(change.newFinal)
            out["no_rebate_cause"] = change.cause?.let(::JsonPrimitive) ?: JsonNull
            out["cta"] = JsonObject(mapOf("text_key" to JsonPrimitive("btn.buy.no_rebate")))
            out["disclaimer_keys"] = JsonArray(listOf(JsonPrimitive(if (coupon > 0) "price_basis" else "price_basis.general")))
        } else {
            out["est_net_price_fen"] = JsonPrimitive(change.newFinal - min)
            if (couponGone) out["cta"] = JsonObject(mapOf("text_key" to JsonPrimitive("btn.buy")))
        }
        if (couponGone) {
            out["benefit_tags"] = JsonArray(emptyList())
            out["disclaimer_keys"] =
                JsonArray(listOfNotNull(JsonPrimitive("price_basis.general"), JsonPrimitive("rebate_estimate").takeIf { max > 0L }))
        }
        out["quoted_at"] = JsonPrimitive("2026-10-08T10:32:00+08:00")
        return JsonObject(out).toString()
    }

    private fun authRequired() =
        Triple(
            30101,
            """{"auth_url":"https://oauth.example.test/authorize?state=st_01","state":"st_01","auth_methods":["web_code","sdk_token"]}""",
            emptyMap<String, String>(),
        )

    private data class Change(
        val newFinal: Long = 4678,
        val newMin: Long? = 123,
        val newMax: Long? = 234,
        val priceChanged: Boolean = false,
        val availability: String = "ok",
        val cause: String? = null,
        val requoteFailed: Boolean = false,
        val genericQuote: Boolean = false,
    )

    private fun openResult(
        attempt: String,
        plan: String,
        change: Change,
    ): String {
        val newLink = if (change.priceChanged || change.availability == "coupon_gone") "\"fake-link-new-$attempt\"" else "null"
        val cause = change.cause?.let { "\"$it\"" } ?: "null"
        return """{"attempt_id":"$attempt","jump":$plan,"price_changed":${change.priceChanged},"old_final_price_fen":4678,""" +
            """"new_final_price_fen":${change.newFinal},"new_link_id":$newLink,"requote_failed":${change.requoteFailed},""" +
            """"new_rebate_min_fen":${change.newMin},"new_rebate_max_fen":${change.newMax},"no_rebate_cause":$cause,""" +
            """"availability":"${change.availability}","quoted_at":"2026-10-08T10:32:00+08:00"}"""
    }

    private fun waitCancellable(
        millis: Long,
        canceled: () -> Boolean,
    ) {
        var waited = 0L
        while (waited < millis) {
            if (canceled()) throw IOException("Canceled")
            Thread.sleep(STEP_MILLIS)
            waited += STEP_MILLIS
        }
    }

    private fun ok(data: String) = Triple(0, data, mapOf(MARKER_HEADER to FAKE_TRADE_SERVER_MARKER))

    private fun fail(code: Int) = Triple(code, "null", mapOf(MARKER_HEADER to FAKE_TRADE_SERVER_MARKER))

    companion object {
        private const val STATUS_PREFIX = "platform_status."
        private const val TAOBAO_BINDING = "taobao_binding"
        private const val BLOCKED = "blocked"
        private const val SLOW_MILLIS = 10_000L
        private const val STEP_MILLIS = 100L
        private const val MARKER_HEADER = "X-Couli-Debug-Fake"
        private const val PRODUCT_PATH = "/v1/products/"
        private const val SEARCH_PATH = "/v1/products/search"
        private val REQUOTED_SCENES = setOf("t-price", "t-pricegone", "t-gone", "t-coupon", "t-compare", "t-compareprice")
        private const val BINDINGS_QUERY = "bindings"
        private const val GENERIC_MIN = 131L
        private const val GENERIC_MAX = 250L
        private const val EXPIRE = "\"expire_at\":\"2099-01-01T00:00:00+08:00\""
        private const val TAOBAO_PLAN =
            """{"primary":{"type":"sdk","value":"https://s.click.example.test/t?e=demo"},""" +
                """"fallbacks":[{"type":"copy_tpwd","value":"DEMO-TPWD-0001"}],$EXPIRE}"""
        private const val SDK_ONLY_PLAN =
            """{"primary":{"type":"sdk","value":"https://s.click.example.test/t?e=demo"},"fallbacks":[],$EXPIRE}"""
        private const val NO_INSTALL_PLAN = """{"primary":{"type":"scheme","value":"couli-demo-missing://item"},"fallbacks":[],$EXPIRE}"""
        private const val BROWSER_PLAN = """{"primary":{"type":"h5","value":"https://u.example.test/demo"},"fallbacks":[],$EXPIRE}"""
        private const val PDD_PLAN =
            """{"primary":{"type":"scheme","value":"couli-demo-missing://pdd"},""" +
                """"fallbacks":[{"type":"h5","value":"https://u.example.test/pdd"}],$EXPIRE}"""
        private const val PDD_AUTH_JUMP =
            """{"primary":{"type":"h5","value":"https://auth.example.test/pdd/authorize?state=st_02"},"fallbacks":[],$EXPIRE}"""
        private const val AUTH_SDK_ONLY =
            """{"auth_url":"https://oauth.example.test/authorize?state=st_01","state":"st_01","auth_methods":["sdk_token"]}"""
        const val IMAGE_HOST = "img.example.test"

        /** 商品图：按 id 画一张 600×428 的渐变图（PNG）。 */
        fun image(id: String): ByteArray {
            val bitmap = createBitmap(IMAGE_W, IMAGE_H)
            val canvas = Canvas(bitmap)
            val hue = (id.hashCode() and 0xff) / 255f * 360f
            val start = Color.HSVToColor(floatArrayOf(hue, 0.25f, 0.95f))
            val end = Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.45f, 0.75f))
            val paint =
                Paint().apply {
                    shader =
                        LinearGradient(0f, 0f, IMAGE_W.toFloat(), IMAGE_H.toFloat(), start, end, Shader.TileMode.CLAMP)
                }
            canvas.drawRect(0f, 0f, IMAGE_W.toFloat(), IMAGE_H.toFloat(), paint)
            val box = Paint().apply { color = Color.argb(200, 255, 255, 255) }
            canvas.drawRoundRect(IMAGE_W * 0.3f, IMAGE_H * 0.2f, IMAGE_W * 0.7f, IMAGE_H * 0.8f, 24f, 24f, box)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            return out.toByteArray()
        }

        private const val IMAGE_W = 600
        private const val IMAGE_H = 428
    }
}

/** 假购买服务的唯一标记（release-scan 查；Release 制品里不得出现）。 */
internal const val FAKE_TRADE_SERVER_MARKER = "COULI_FAKE_TRADE_SERVER_APP06B_9b2e71"

/** 假购买服务的状态开关文件名（files/ 下，每行 key=value）。 */
internal const val FAKE_TRADE_STATE_FILE = "couli_fake_trade_state.txt"
