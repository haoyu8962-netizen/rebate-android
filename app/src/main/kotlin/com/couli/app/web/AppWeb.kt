package com.couli.app.web

import android.content.Context
import android.os.Build
import com.couli.app.BuildConfig
import com.couli.app.clientcore.AppConfig
import com.couli.app.clientcore.clientPrivacyRaw
import com.couli.app.clipboard.AppClipboard
import com.couli.app.login.AppLogin
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.update.AppUpdate
import com.couli.core.bridge.BridgeCallValidity
import com.couli.core.bridge.BridgeCallerState
import com.couli.core.bridge.BridgeCode
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.BridgeOutcome
import com.couli.core.bridge.BridgePrivacy
import com.couli.core.bridge.BridgeProtocol
import com.couli.core.bridge.BridgeServices
import com.couli.core.bridge.BridgeUi
import com.couli.core.bridge.BridgeUpdateState
import com.couli.core.bridge.ExternalNavPolicy
import com.couli.core.bridge.LinkPatterns
import com.couli.core.bridge.WebConfigReader
import com.couli.core.clientcore.BundledConfig
import com.couli.core.clientcore.ClientEvent
import com.couli.core.clientcore.ClientEvents
import com.couli.core.clientcore.ConfigResolver
import com.couli.core.clientcore.PermissionStatusRaw
import com.couli.core.clientcore.PermissionTypeRaw
import com.couli.core.clientcore.PrivacyStateRaw
import com.couli.core.clientcore.TrackerQueue
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.InFlightCalls
import com.couli.core.network.SessionTokens
import com.couli.core.privacy.AndroidPermissionRequester
import com.couli.core.router.UpdateCheck
import com.couli.feature.login.MeReadLimit
import com.couli.feature.web.ExternalNavFlags
import com.couli.feature.web.TrustedWebConfig
import com.couli.generated.ClientPlatform
import com.couli.generated.InstallChannel
import java.util.Collections
import java.util.WeakHashMap
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 两类 WebView 容器与 JSBridge 的 App 接线（APP-04）：配置（可信源、链接形态表、开关）、BridgeHost 的业务能力
 * （Router、ApiClient、Tracker、PermissionRequester）、调用方状态（隐私、会话、强更）。
 *
 * 新建的宿主登记进 [BridgeHostRegistry]：撤回同意与切换环境时（AppModule 登记进两个清理入口）所有容器里在途的桥调用作废，
 * 晚到的结果不交回。
 */
@Suppress("LongParameterList", "TooManyFunctions") // 各能力的来源逐项注入
class AppWeb(
    // 构造签名保留（AppModule 与各集成测试按位置传入）；FU-CLIP 起 clipboard.read 经 AppClipboard 读，不再直接取系统服务
    @Suppress("UnusedPrivateProperty")
    private val context: Context,
    private val config: AppConfig,
    private val client: Provider<ApiClient>,
    internal val routing: AppRouting,
    private val privacy: AppPrivacy,
    private val update: AppUpdate,
    private val tracker: Provider<TrackerQueue>,
    private val permissions: AndroidPermissionRequester,
    private val hosts: BridgeHostRegistry,
    private val tokens: SessionTokens,
    private val inFlight: InFlightCalls,
    /** 登录（APP-05a）：桥调用 auth.login 等登录页结束再结算（APP-04 共性第 66 条）。 */
    private val login: Provider<AppLogin>,
    /** 剪贴板（APP-07）：clipboard.write 经唯一写入口记 hash；clipboard.setAutoDetect 只对发起调用的容器实例临时抑制（G06）。 */
    private val clipboard: Provider<AppClipboard>,
) {
    private val bridgeLogin = BridgeLogin({ login.get() }, routing) { update.checkRound }

    val appVersion: String = BuildConfig.VERSION_NAME.substringBefore('-')

    private fun data(): JsonObject = config.store.snapshots.value.data

    /**
     * 当前链接形态表：current → 上一次成功 → 包内，取第一张完整有效的（坏表整表跳过，APP-04 三端共性第 18 条）；都不可用为 null
     * （ExternalNavPolicy 再回落到包内快照，仍不可用时拒绝加载）。
     */
    fun linkPatterns(): JsonObject? =
        config.store
            .linkPatternLayers()
            .asSequence()
            .mapNotNull { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            .firstOrNull { LinkPatterns.isValid(it) }

    /**
     * 可信容器的来源配置；[loopbackOrigins] 只有 Debug 构建的一致性页入口传入（src/debug 的 CONFORMANCE_SOURCE）。
     * 除 debug 以外的构建一律丢弃（APP-04 三端共性第 2 条：回环支持不进 Release；R8 会把这条分支整个删掉）。
     */
    fun trustedConfig(loopbackOrigins: Set<String> = emptySet()): TrustedWebConfig =
        TrustedWebConfig(WebConfigReader.trustedOrigins(data()), loopbackOrigins.onlyInDebug(), appVersion)

    /** H5 路由 [spec] 的页面地址（生效配置的 h5_base_url + h5_path + 参数；[paramsJson] 为路由参数）；拼不出为 null。 */
    fun h5Url(
        spec: com.couli.generated.RouteSpec,
        paramsJson: String,
    ): String? {
        val base = (data()["h5_base_url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val params = runCatching { Json.parseToJsonElement(paramsJson) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        return H5RouteUrl.of(base, spec.h5Path, params)
    }

    /** 第三方页容器的判定（包内快照来自 BundledConfig；TODO(N-21) 包内规则目前为空）。 */
    val externalPolicy: ExternalNavPolicy by lazy {
        val bundled = ConfigResolver.parseObject(BundledConfig.dataJson)?.let(ConfigResolver::linkPatternsIn)
        ExternalNavPolicy(bundled ?: JsonObject(emptyMap()))
    }

    fun externalFlags(): ExternalNavFlags =
        ExternalNavFlags(
            consented = privacyRaw() == BridgePrivacy.CONSENTED,
            productIntercept = WebConfigReader.flag(data(), PRODUCT_INTERCEPT, default = false),
            navHostSampleBp = WebConfigReader.navHostSampleBp(data()),
        )

    fun track(event: ClientEvent) {
        tracker.get().track(event)
    }

    /**
     * 新建一个 BridgeHost（每个可信容器一个）。[patternsOverride]、[shareDomainsOverride]：Debug 一致性页入口用契约示例的规则
     * （包内规则为空，TODO(N-21)），生产不传。[callerOverride] 同样只给 Debug 一致性页：本机没有登录能力（APP-05），用它以
     * 「已登录」身份验证 L1 / L2 的手势与白名单判定（业务能力仍走真实 ApiClient，没有会话时照常失败）。
     */
    fun newHost(
        ui: BridgeUi,
        scope: CoroutineScope,
        loopbackOrigins: Set<String> = emptySet(),
        patternsOverride: JsonObject? = null,
        shareDomainsOverride: Set<String>? = null,
        callerOverride: ((BridgeCallerState) -> BridgeCallerState)? = null,
        /** 容器所在的回退栈条目（clipboard.setAutoDetect 的临时抑制只对这个容器实例生效，出栈即失效）。 */
        entryId: String? = null,
    ): BridgeHost {
        // 调试替换（一致性页的示例规则、假定已登录）只在 debug / staging 生效，回环源只在 debug；其余构建一律丢弃（三端共性第 2 条）
        val patterns = patternsOverride?.takeIf { BuildConfig.DEBUG_FEATURES }
        val shareDomains = shareDomainsOverride?.takeIf { BuildConfig.DEBUG_FEATURES }
        val loopback = loopbackOrigins.onlyInDebug()
        val protocol = {
            val current = data()
            BridgeProtocol(
                trustedOrigins = WebConfigReader.trustedOrigins(current),
                linkPatterns = patterns ?: linkPatterns() ?: JsonObject(emptyMap()),
                shareDomains = shareDomains ?: WebConfigReader.shareDomains(current),
                loopbackOrigins = loopback,
            )
        }
        val override = callerOverride?.takeIf { BuildConfig.DEBUG_FEATURES }
        val caller = override?.let { adjust -> { adjust(callerState()) } } ?: ::callerState
        return BridgeHost(protocol, caller, Services(entryId), ui, ::awaitUpdate, scope).also(hosts::add)
    }

    private fun privacyRaw(): String =
        when (clientPrivacyRaw(privacy.conclusion, privacy.credentialAccess.cleanupPending)) {
            PrivacyStateRaw.CONSENTED -> BridgePrivacy.CONSENTED
            PrivacyStateRaw.BASIC_MODE -> BridgePrivacy.BASIC_MODE
            else -> BridgePrivacy.NOT_DECIDED
        }

    private fun callerState(): BridgeCallerState =
        BridgeCallerState(
            privacy = privacyRaw(),
            accountKey = if (privacyRaw() == BridgePrivacy.CONSENTED) client.get().activeUserId() else null,
            update = update.routerUpdate.toBridgeUpdate(),
            sessionEpoch = tokens.loginEpoch,
        )

    /** 检查中最多等 5 秒（03 §4.4），到时按未知。 */
    private suspend fun awaitUpdate(): String =
        withTimeoutOrNull(UPDATE_WAIT_MS) {
            while (update.routerUpdate == UpdateCheck.CHECKING) delay(UPDATE_POLL_MS)
            update.routerUpdate.toBridgeUpdate()
        } ?: BridgeUpdateState.UNKNOWN

    private inner class Services(
        private val entryId: String?,
    ) : BridgeServices {
        override fun env(): JsonObject =
            buildJsonObject {
                put("platform", ClientPlatform.ANDROID.raw)
                put("app_version", appVersion)
                put("build", BuildConfig.VERSION_CODE.toString())
                put("os_version", Build.VERSION.RELEASE.orEmpty())
                put("bridge_version", BridgeProtocol.contractVersion)
                // 容器自己避开系统栏（safeDrawingPadding），页面区域内没有需要再让的安全区
                put("safe_area", buildJsonObject { listOf("top", "bottom", "left", "right").forEach { put(it, 0) } })
                // TODO(N-17)：渠道包接入后按构建渠道取值
                put("channel", InstallChannel.OFFICIAL.raw)
            }

        override fun config(keys: List<String>): BridgeOutcome =
            BridgeOutcome.Ok(
                JsonObject(
                    mapOf("values" to WebConfigReader.h5Values(data(), keys)),
                ),
            )

        override suspend fun user(validity: BridgeCallValidity): BridgeOutcome {
            // 禁用期内被拦下（本地拒绝）按 90500 结束，与服务端 42901 相同
            val result = bridgeGetMe(client.get(), login.get().meReadGate, tokens, inFlight, validity)
            return when (result) {
                is ApiResult.Success -> {
                    (result.data as? JsonObject)?.let { BridgeOutcome.Ok(userView(it)) }
                        ?: fail(BridgeCode.NATIVE_ERROR)
                }

                else -> {
                    failure(result)
                }
            }
        }

        override suspend fun h5Token(
            readOnly: Boolean,
            validity: BridgeCallValidity,
        ): BridgeOutcome {
            // 作用域由原生按强更状态决定（H5 不能选）；令牌不缓存、不落盘，只交给这次调用（03 §5.4）
            val body = buildJsonObject { put("scope", if (readOnly) SCOPE_READ_ONLY else SCOPE_STANDARD) }.toString()
            return when (val result = client.get().send(ApiCall(ISSUE_H5_TOKEN, body = body, ownership = owned(validity)))) {
                is ApiResult.Success -> {
                    val data = result.data as? JsonObject ?: return fail(BridgeCode.NATIVE_ERROR)
                    BridgeOutcome.Ok(JsonObject(data.filterKeys { it in H5_TOKEN_FIELDS }))
                }

                else -> {
                    failure(result)
                }
            }
        }

        /** 只打开登录页、不等待（BridgeHost 走带等待的重载 [login]，这里是它的「打开」一步）。 */
        override suspend fun login(update: String): BridgeOutcome = bridgeLogin.open(update).first

        override suspend fun login(
            update: String,
            validity: BridgeCallValidity,
            opening: suspend (suspend () -> BridgeOutcome) -> BridgeOutcome,
        ): BridgeOutcome = bridgeLogin.run(update, validity, opening)

        override suspend fun signedRequest(
            method: String,
            path: String,
            body: JsonElement?,
            validity: BridgeCallValidity,
        ): BridgeOutcome {
            // signed_paths 已在协议层核对；按 method + path 找 openapi 的签名接口，由 ApiClient 签名代发。
            // 契约即白名单（CT-LOCK-02 编排裁定）；找不到签名接口时按「本端不支持」结束、不臆造请求。
            val operation = BridgeSignedOperations.operationFor(method, path) ?: return fail(BridgeCode.NOT_SUPPORTED)
            val call =
                ApiCall(
                    operation.operationId,
                    query = path.substringAfter('?', "").ifEmpty { null },
                    body = body?.toString(),
                    ownership = owned(validity),
                )
            return when (val result = client.get().send(call)) {
                is ApiResult.Success -> BridgeOutcome.Ok(envelope(0, "", result.data, result.traceId))
                is ApiResult.Failure -> BridgeOutcome.Ok(envelope(result.code, result.msg, result.data, result.traceId))
                is ApiResult.TransportFailure -> fail(BridgeCode.TIMEOUT)
                else -> fail(BridgeCode.NATIVE_ERROR)
            }
        }

        override suspend fun navOpen(
            route: String,
            params: JsonObject,
            update: String,
        ): BridgeOutcome =
            BridgeRouting.open(routing.router, routing::guardState, route, params, update, round = { this@AppWeb.update.checkRound })

        override fun clipboardWrite(text: String): BridgeOutcome {
            // 经 App 写剪贴板的唯一入口：写入前先记 hash，之后识别不再提示（03 §4.6，F-CLIP-02）
            if (!clipboard.get().writer.write(text, CLIP_LABEL)) return fail(BridgeCode.NATIVE_ERROR)
            return BridgeOutcome.Ok()
        }

        override suspend fun clipboardRead(): BridgeOutcome {
            // FU-CLIP C4：只回本地抽出的商品候选（白名单链接 / 口令最多 3 个 + 最小化标题），抽不到回 ""；读之前核对隐私（K12）。
            // 读到的原文只在 AppClipboard.bridgeRead 这一次调用里经过，不进日志、不进桥事件；调用期间失效由 BridgeHost 以 90500 结算、不交回文本
            val text = clipboard.get().bridgeRead()
            return BridgeOutcome.Ok(JsonObject(mapOf("text" to JsonPrimitive(text))))
        }

        override fun setClipboardAutoDetect(enabled: Boolean): BridgeOutcome {
            // G06：只对发起调用的这个网页容器实例生效的临时抑制（容器在栈顶时回前台不读，出栈即失效）；不写设备级开关，不改设置页
            clipboard.get().setWebAutoDetect(entryId, enabled)
            return BridgeOutcome.Ok()
        }

        override suspend fun pushStatus(): BridgeOutcome =
            BridgeOutcome.Ok(
                JsonObject(
                    mapOf("enabled" to JsonPrimitive(permissions.status(PermissionTypeRaw.PUSH) == PermissionStatusRaw.GRANTED)),
                ),
            )

        override suspend fun requestPermission(
            type: String,
            validity: BridgeCallValidity,
        ): BridgeOutcome {
            // 调用有效性一路带进 PermissionRequester：出队、说明、调系统接口前各查一次（APP-04 三端共性第 14 条）；
            // 失效时不弹任何界面、不调系统接口，结果由 BridgeHost 按归属失效以 90500 结束
            val result = permissions.request(type, isValid = validity::stillValid)
            if (!validity.stillValid()) return fail(BridgeCode.NATIVE_ERROR)
            return BridgeOutcome.Ok(JsonObject(mapOf("granted" to JsonPrimitive(result.granted))))
        }

        override fun track(
            name: String,
            props: JsonObject,
        ): BridgeOutcome {
            // 只接受 Tracker 生成函数里给 H5 的事件（字段待定的页面类事件，props 暂不带）；其余丢弃。TODO(N-22)：H5 可报事件清单
            H5_EVENTS[name]?.invoke(PAGE)?.let { tracker.get().track(it) }
            return BridgeOutcome.Ok()
        }

        override fun reportRejected(
            method: String,
            code: Int,
        ) {
            tracker.get().track(ClientEvents.bridgeCall(PAGE, method, code.toLong()))
        }

        private fun owned(validity: BridgeCallValidity): CallOwnership = bridgeOwnership(inFlight, validity)

        private fun failure(result: ApiResult): BridgeOutcome =
            when (result) {
                is ApiResult.Failure -> fail(if (result.code in LOGIN_CODES) BridgeCode.NOT_LOGGED_IN else BridgeCode.NATIVE_ERROR)
                is ApiResult.TransportFailure -> fail(BridgeCode.TIMEOUT)
                else -> fail(BridgeCode.NATIVE_ERROR)
            }
    }

    companion object {
        private const val PAGE = "WebPage"
        private const val ISSUE_H5_TOKEN = "issueH5Token"
        private const val SCOPE_READ_ONLY = "read_only"
        private const val SCOPE_STANDARD = "standard"
        private const val PRODUCT_INTERCEPT = "external_page.product_intercept"
        private const val CLIP_LABEL = "couli"
        private const val UPDATE_WAIT_MS = 5_000L
        private const val UPDATE_POLL_MS = 100L
        private val H5_TOKEN_FIELDS = setOf("token", "expire_at", "scope")

        /** 10001 请先登录、10404 登录已过期：桥按未登录结束。 */
        private val LOGIN_CODES = setOf(10001, 10404)

        private val H5_EVENTS: Map<String, (String) -> ClientEvent> =
            mapOf(
                "page_view" to { page -> ClientEvents.pageView(page) },
                "exposure" to { page -> ClientEvents.exposure(page) },
                "click" to { page -> ClientEvents.click(page) },
                "search" to { page -> ClientEvents.search(page) },
                "convert" to { page -> ClientEvents.convert(page) },
                "h5_white_screen" to { page -> ClientEvents.h5WhiteScreen(page) },
            )

        private fun fail(code: Int): BridgeOutcome = BridgeOutcome.Fail(code)

        /** getMe → auth.getUser 的 result（不带任何令牌，BR-ID-32）。 */
        internal fun userView(me: JsonObject): JsonObject {
            val bindings =
                (me["union_bindings"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.associate { it.text("platform") to it.text("status") }
                    .orEmpty()
            return buildJsonObject {
                put("uid", me.text("user_id").orEmpty())
                put("nickname", me.text("nickname").orEmpty())
                put("avatar", me.text("avatar_url").orEmpty())
                put("phone_bound", (me["phone_bound"] as? JsonPrimitive)?.content == "true")
                put("realname_status", me.text("realname_status") ?: "none")
                put(
                    "bindings",
                    buildJsonObject {
                        put("taobao", bindings["taobao"] ?: UNBOUND)
                        put("pdd", bindings["pdd"] ?: UNBOUND)
                    },
                )
            }
        }

        private const val UNBOUND = "unbound"

        private fun envelope(
            code: Int,
            msg: String,
            data: JsonElement?,
            traceId: String?,
        ): JsonObject =
            buildJsonObject {
                // TODO(N-22)：ApiClient 不暴露 HTTP 状态；拿到统一外壳时按 200 交回，业务码看 body.code（03 §5.3）
                put("status", HTTP_OK)
                put(
                    "body",
                    buildJsonObject {
                        put("code", code)
                        put("msg", msg)
                        data?.let { put("data", it) }
                        traceId?.let { put("trace_id", it) }
                    },
                )
            }

        private const val HTTP_OK = 200

        private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/**
 * 桥发出的 ApiRequest 绑定这次桥调用的有效性（APP-04 三端共性第 22 条）：ApiClient 在绑定归属、等待结束、重试与每次写出之前
 * 都核对；页面关闭、换了文档、撤回同意、切换环境或会话换代之后不再发出。截止时刻一并交给 ApiClient（R2-01、共性第 45 条）：
 * OkHttp 的 Call 超时截到剩余预算，到期即取消，之后不退避、不续发。
 */
internal fun bridgeOwnership(
    inFlight: InFlightCalls,
    validity: BridgeCallValidity,
): CallOwnership = CallOwnership(inFlight.epochs(), validity.deadlineNanos(), stillOwned = validity::stillValid)

/**
 * 桥 auth.getUser 的 getMe（APP-12 Android R3-01 / R3-02，共性第 33、34 条）：与登录校验、「我的」、钱包实名查询共用一份 Me 读取截止时间 [gate]——
 * 禁用期内不发（本地拒绝 call_cancelled）；每次实际写出前（含重试、重放）核对同一截止时间；收到 42901 时在这次桥调用仍有效、会话世代与隐私 / 环境世代
 * 都没变的前提下写入（[MeReadLimit.settle]）。请求归属仍是这次桥调用（截止时间一并交给 ApiClient）。
 */
internal suspend fun bridgeGetMe(
    client: ApiClient,
    gate: com.couli.core.wallet.WalletRateGate,
    tokens: SessionTokens,
    inFlight: InFlightCalls,
    validity: BridgeCallValidity,
): ApiResult {
    if (gate.active) return ApiResult.LocalReject(com.couli.core.network.LocalRejectReason.CALL_CANCELLED)
    val epoch = tokens.loginEpoch
    val owner =
        CallOwnership(inFlight.epochs(), validity.deadlineNanos(), sendAllowed = { !gate.active }) {
            validity.stillValid() && tokens.loginEpoch == epoch
        }
    val result = client.send(ApiCall(BRIDGE_GET_ME, ownership = owner))
    MeReadLimit.settle(result, owner, inFlight::epochs, gate::start)
    return result
}

private const val BRIDGE_GET_ME = "getMe"

/** 回环源只在 debug 构建保留。 */
private fun Set<String>.onlyInDebug(): Set<String> = if (BuildConfig.BUILD_KIND == DEBUG_BUILD_KIND) this else emptySet()

private const val DEBUG_BUILD_KIND = "debug"

/** Router 的强更进度 → 桥的强更状态。 */
internal fun UpdateCheck.toBridgeUpdate(): String =
    when (this) {
        UpdateCheck.OK, UpdateCheck.BELOW_RECOMMENDED -> BridgeUpdateState.SUPPORTED
        UpdateCheck.BELOW_MIN -> BridgeUpdateState.BELOW
        UpdateCheck.CHECKING -> BridgeUpdateState.CHECKING
        UpdateCheck.UNKNOWN -> BridgeUpdateState.UNKNOWN
    }

/**
 * 活着的 BridgeHost（弱引用，容器销毁后自动消失）。撤回同意、切换环境时 [invalidateAll]：作废全部在途桥调用（lessons 1、22）。
 * 不依赖任何其他对象，清理入口可以直接持有它（不经 AppWeb，避免依赖环）。
 */
class BridgeHostRegistry {
    private val hosts: MutableSet<BridgeHost> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    fun add(host: BridgeHost) {
        hosts += host
    }

    fun invalidateAll() {
        synchronized(hosts) { hosts.toList() }.forEach { it.invalidate() }
    }
}
