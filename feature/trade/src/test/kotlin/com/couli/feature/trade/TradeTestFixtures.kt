package com.couli.feature.trade

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PageApi
import com.couli.core.network.ReplayMode
import com.couli.core.tradejump.BaichuanAdapter
import com.couli.core.tradejump.BaichuanAuthResult
import com.couli.core.tradejump.BaichuanOpenResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 本地假服务（[PageApi] 替身）：记下每次调用，由测试逐个回答；归属失效的调用记为已取消。 */
internal class FakeTradeApi : PageApi {
    class Pending(
        val call: ApiCall,
        val result: CompletableDeferred<ApiResult>,
    )

    val calls = mutableListOf<Pending>()
    val cancelled = mutableListOf<CallOwnership>()

    override suspend fun send(call: ApiCall): ApiResult {
        val pending = Pending(call, CompletableDeferred())
        calls += pending
        return pending.result.await()
    }

    override fun cancelOwned(owner: CallOwnership) {
        cancelled += owner
    }

    override fun epochs(): CallEpochs = CallEpochs(0, 0)

    fun ops(): List<String> = calls.map { it.call.operationId }

    fun last(op: String): Pending = calls.last { it.call.operationId == op }

    fun succeed(
        pending: Pending,
        data: String,
    ) {
        pending.result.complete(ApiResult.Success(Json.parseToJsonElement(data), TRACE, pending.call.idempotencyKey))
    }

    fun fail(
        pending: Pending,
        code: Int,
        data: String = "{}",
        disableMillis: Long? = null,
    ) {
        val kind = if (disableMillis != null) ErrorEffectKind.DISABLE_BUTTON else ErrorEffectKind.PAGE_ACTION
        val effect = ErrorEffect(code, kind, ReplayMode.NONE, registerPending = false, disableMillis = disableMillis)
        pending.result.complete(
            ApiResult.Failure(code, "", Json.parseToJsonElement(data).jsonObject, TRACE, effect, pending.call.idempotencyKey),
        )
    }

    companion object {
        const val TRACE = "trace-000321"
    }
}

/** 外跳端口替身：记下每次拉起，按设定返回。 */
internal class FakePorts(
    var installed: String = "unknown",
    var appLaunches: Boolean = false,
    var browserOpens: Boolean = true,
) : JumpPorts {
    val launched = mutableListOf<String>()

    override fun installed(platform: String): String = installed

    override fun launchApp(
        type: String,
        value: String,
    ): Boolean {
        launched += "$type:$value"
        return appLaunches
    }

    override fun openBrowser(url: String): Boolean {
        launched += "browser:$url"
        return browserOpens
    }

    override fun copy(text: String): Boolean {
        launched += "copy:$text"
        return true
    }
}

/** 百川替身：默认不可用（与生产的不可用实现同样，初始化失败）；[sdkToken] 时支持换令牌，[auth] 为授权结果。 */
internal class FakeBaichuan(
    var available: Boolean = false,
    var sdkToken: Boolean = false,
    var auth: BaichuanAuthResult = BaichuanAuthResult.Failed,
) : BaichuanAdapter {
    var authorizations = 0

    var retries = 0

    override val ready: Boolean get() = available

    override fun initialize(): Boolean = available

    override fun retryInitIfFailed(): Boolean {
        retries += 1
        return available
    }

    override fun open(value: String): BaichuanOpenResult = if (available) BaichuanOpenResult.LAUNCHED else BaichuanOpenResult.INIT_FAILED

    override val supportsSdkToken: Boolean get() = sdkToken

    override suspend fun authorize(): BaichuanAuthResult {
        authorizations += 1
        return auth
    }
}

/** web_code 授权替身。 */
internal class FakeWebCode(
    override var available: Boolean = true,
    var result: AuthStepResult = AuthStepResult.Credential(AuthCredential.WebCode("fixture-web-code")),
) : WebCodeAuthorizer {
    val urls = mutableListOf<String>()

    override suspend fun authorize(
        authUrl: String,
        state: String,
    ): AuthStepResult {
        urls += authUrl
        return result
    }
}

/** 页面环境替身。 */
internal class FakeEnv(
    var loggedIn: Boolean = true,
    var status: String? = "on",
    var basic: Boolean = false,
) : PurchaseEnv {
    val opened = mutableListOf<Pair<String, JsonObject>>()
    var card: JsonObject? = null

    /** 其它卡（同页的别的商品）。 */
    val others = mutableListOf<JsonObject>()

    /** 重取过的卡（按调用顺序）。 */
    val refetchedCards = mutableListOf<JsonObject>()
    val refetched: Int get() = refetchedCards.size

    /** 每次重取给的落卡方式（补齐 / 重新加载）。 */
    val merges = mutableListOf<(JsonObject, JsonObject) -> JsonObject>()

    /** 模拟重取结果到达：按那次重取给的落卡方式把 [fresh] 落到页面上的卡。 */
    fun answerRefetch(
        fresh: JsonObject,
        index: Int = merges.lastIndex,
    ) {
        val target = refetchedCards[index]
        val merge = merges[index]
        card = card?.let { if (CardPatch.sameCard(it, target)) merge(it, fresh) else it }
        others.replaceAll { if (CardPatch.sameCard(it, target)) merge(it, fresh) else it }
    }

    /** 点击结果确认的平台状态（50301）。 */
    val marked = mutableMapOf<String, String>()
    var consentRequests = 0

    override fun loggedIn(): Boolean = loggedIn

    override fun basicMode(): Boolean = basic

    override fun requestConsent() {
        consentRequests += 1
    }

    override fun platformStatus(platform: String): String? = marked[platform] ?: status

    override fun markPlatformStatus(
        platform: String,
        status: String,
    ) {
        marked[platform] = status
    }

    override fun updateCard(
        target: JsonObject,
        transform: (JsonObject) -> JsonObject,
    ) {
        card = card?.let { if (CardPatch.sameCard(it, target)) transform(it) else it }
        others.replaceAll { if (CardPatch.sameCard(it, target)) transform(it) else it }
    }

    override fun open(
        route: String,
        params: JsonObject,
    ) {
        opened += route to params
    }

    override fun refetchCard(
        target: JsonObject,
        merge: (JsonObject, JsonObject) -> JsonObject,
    ) {
        refetchedCards += target
        merges += merge
    }

    override fun helpLink(key: String): Pair<String, JsonObject>? = null
}

/** 测试数据。 */
internal object TradeFixtures {
    fun card(
        platform: String = "taobao",
        linkId: String = "link-1",
        rebateMax: Long = 234,
        id: String = "c1",
    ): JsonObject =
        Json
            .parseToJsonElement(
                """{"card_id":"$id","product_key":"tb:$id","item_ref":"v1.r","platform":"$platform","title":"milk","price_fen":5678,""" +
                    """"coupon_fen":1000,"final_price_fen":4678,"est_net_price_fen":4555,""" +
                    """"rebate_min_fen":123,"rebate_max_fen":$rebateMax,""" +
                    """"rebate_basis":"normal","link_id":"$linkId","cta":{"text_key":"btn.buy.coupon"},"benefit_tags":["有券"],""" +
                    """"disclaimer_keys":["price_basis","rebate_estimate"],"availability":"ok"}""",
            ).jsonObject

    /** 方案；[expireAt] 缺省为测试时钟（[NOW]）之后很久。 */
    fun plan(
        vararg steps: Pair<String, String>,
        expireAt: String = "2026-10-02T09:45:00+08:00",
    ): String {
        val all = steps.map { (type, value) -> """{"type":"$type","value":"$value"}""" }
        return """{"primary":${all.first()},"fallbacks":[${all.drop(1).joinToString(",")}],"expire_at":"$expireAt"}"""
    }

    /** 测试墙上时钟：2026-10-02T09:30:00+08:00（方案 expire_at 09:45 之前）。 */
    val NOW: Long =
        java.time.OffsetDateTime
            .parse("2026-10-02T09:30:00+08:00")
            .toInstant()
            .toEpochMilli()

    @Suppress("LongParameterList") // open 响应的字段逐项
    fun openResult(
        attempt: String = "attempt-1",
        plan: String = plan("scheme" to "demo-app://item"),
        priceChanged: Boolean = false,
        newFinal: Long = 4678,
        newLink: String? = null,
        newMax: Long = 234,
        newMin: Long = 123,
        cause: String? = null,
        availability: String = "ok",
        requoteFailed: Boolean = false,
    ): String {
        val link = newLink?.let { "\"$it\"" } ?: "null"
        val c = cause?.let { "\"$it\"" } ?: "null"
        return """{"attempt_id":"$attempt","jump":$plan,"price_changed":$priceChanged,"old_final_price_fen":4678,""" +
            """"new_final_price_fen":$newFinal,"new_link_id":$link,"requote_failed":$requoteFailed,"new_rebate_min_fen":$newMin,""" +
            """"new_rebate_max_fen":$newMax,"no_rebate_cause":$c,"availability":"$availability","quoted_at":"2026-10-02T09:30:05+08:00"}"""
    }
}
