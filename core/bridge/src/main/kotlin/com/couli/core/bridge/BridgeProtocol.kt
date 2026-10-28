package com.couli.core.bridge

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 桥错误码（contracts/bridge.schema.json errors；含义原文见 [BridgeProtocol.message]）。 */
object BridgeCode {
    const val OK = 0
    const val NOT_SUPPORTED = 90001
    const val INVALID_PARAMS = 90002
    const val TIMEOUT = 90003
    const val CANCELLED = 90004
    const val NOT_LOGGED_IN = 90401
    const val NOT_ALLOWED = 90403
    const val GESTURE_MISSING = 90404
    const val NATIVE_ERROR = 90500
}

/** 隐私状态的 raw（与 Router 的 PrivacyState 同名取值）。 */
object BridgePrivacy {
    const val CONSENTED = "consented"
    const val BASIC_MODE = "basic_mode"
    const val NOT_DECIDED = "not_decided"
}

/**
 * 一次信封解析（三端测试接线约定）：[valid] 为假时 [errorCode] 为 90002；未知方法名能通过结构校验（由 [BridgeProtocol.decide]
 * 返回 90001）；缺省 params 为 {}。
 * [structureValid]：只看信封结构（v、id、method、params 的形状），不看方法参数——宿主按 login → gesture → params 的顺序判定时用它。
 */
data class EnvelopeParse(
    val valid: Boolean,
    val errorCode: Int?,
    val method: String?,
    val id: String?,
    val params: JsonObject?,
    val structureValid: Boolean = valid,
)

/**
 * 一次调用的判定输入（三端测试接线约定）。[privacy] 取 [BridgePrivacy]。
 * [documentIsolated]：发起调用的文档处于切号隔离（F-X-25 I1）——任何方法（含匿名、只读、取令牌）一律 90500，处理器不执行。
 */
data class BridgeContext(
    val method: String,
    val origin: String,
    val isMainFrame: Boolean,
    val userGesture: Boolean,
    val privacy: String,
    val loggedIn: Boolean,
    val params: JsonObject,
    val documentIsolated: Boolean = false,
)

/** 判定结果：放行时 [errorCode] 为 null。[data] 是拒绝响应附带的 data（远程禁用的 `{reason: method_disabled}`），其余为 null。 */
data class BridgeDecision(
    val allow: Boolean,
    val errorCode: Int?,
    val data: JsonObject? = null,
) {
    companion object {
        val ALLOW = BridgeDecision(true, null)

        fun deny(code: Int) = BridgeDecision(false, code)

        /** 方法被远程禁用（03 §5.3「方法远程禁用（试行）」）：90403，data.reason = method_disabled。 */
        val METHOD_DISABLED =
            BridgeDecision(false, BridgeCode.NOT_ALLOWED, buildJsonObject { put("reason", "method_disabled") })
    }
}

/**
 * JSBridge 协议的纯逻辑（03 §5.2、§5.3；contracts/bridge.schema.json）：信封校验、级别判定、白名单、事件白名单与标准响应体。
 * 没有 Android 依赖，配置全部由构造参数注入（不读生产域与全局状态）。
 *
 * [decide] 的判定顺序（同一处判定，H5 不能绕开）：
 * 0. 文档隔离（F-X-25 I1，[BridgeContext.documentIsolated]）：切号后被隔离的旧文档（随后拆除销毁）的一切调用 90500，排在所有判定之前；
 * 1. origin 与框架：只认白名单里的完整 origin（https、主机转小写逐字相等、不通配子域、443 规范化）且是主框架，否则 90403；
 *    [loopbackOrigins] 是 Debug 构建另加的本机一致性测试源（允许 http，逐字相等），Release 传空；
 * 1a. 远程禁用：方法名逐字在 [disabledMethods]（生效配置的 bridge_disabled_methods）里 → 90403 + data.reason=method_disabled，
 *    排在 origin 之后、其余判定之前，处理器（含 net.signedRequest 代发）不执行；不核对名字是否在契约里，列表里的未知名同样按禁用拒绝；
 * 2. 方法：契约里没有的（含 not_exposed 的类别标签、大小写不同的名字）90001；
 * 3. 隐私：未同意或基本模式时只放行 [PRIVACY_LIMITED_METHODS]，其余拒绝。TODO(N-22)：契约没有逐方法的基本模式白名单，也没有
 *    隐私拒绝专用的桥码；这里保守地只放行不读写账号、不外跳、不发写请求的方法，拒绝暂用 90403；
 * 4. 级别：L1、L2 未登录 90401；
 * 5. 手势：L2 与 gesture_required 缺最近 1 秒内的用户点击 90404；
 * 6. 参数：不符合方法的 params schema 90002（cases.ts：login → gesture → params）；
 * 7. 方法自身的白名单（whitelist_90403）：net.signedRequest 的 signed_paths、ext.openApp 的 trade_only 目标、
 *    ext.openBrowser 命中链接形态表、share.open 的链接范围，命中即 90403。
 *
 * 只是协议准入：since、强更守卫、能力是否实现由宿主（[BridgeHost]）另判。
 */
class BridgeProtocol(
    trustedOrigins: Set<String>,
    linkPatterns: JsonObject,
    shareDomains: Set<String>,
    loopbackOrigins: Set<String> = emptySet(),
    /** 远程禁用的方法名（已按整键校验，见 ConfigResolver.bridgeDisabledMethodsIn）；能力声明（[BridgeHost.declaredMethods]）同样排除。 */
    val disabledMethods: Set<String> = emptySet(),
) {
    private val trusted: Set<String> = trustedOrigins.mapNotNull { normalizeOrigin(it, allowHttp = false) }.toSet()
    private val loopback: Set<String> = loopbackOrigins.mapNotNull { normalizeOrigin(it, allowHttp = true) }.toSet()

    /** 规则表；坏表或缺表为 null：凡要按链接形态表放行的地址一律拒绝（APP-04 三端共性第 18 条，保守）。 */
    private val patterns: LinkPatterns? = LinkPatterns.parse(linkPatterns)
    private val shareHosts: Set<String> = shareDomains.map { it.lowercase().trimEnd('.') }.toSet()

    /** origin 是否为可信源（白名单或 Debug 本机源）。 */
    fun isTrustedOrigin(origin: String): Boolean {
        val https = normalizeOrigin(origin, allowHttp = false)
        val any = normalizeOrigin(origin, allowHttp = true)
        return (https != null && https in trusted) || (any != null && any in loopback)
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // 判定顺序逐步返回（见类说明）
    fun decide(ctx: BridgeContext): BridgeDecision {
        if (ctx.documentIsolated) return BridgeDecision.deny(BridgeCode.NATIVE_ERROR)
        if (!ctx.isMainFrame || !isTrustedOrigin(ctx.origin)) return BridgeDecision.deny(BridgeCode.NOT_ALLOWED)
        if (ctx.method in disabledMethods) return BridgeDecision.METHOD_DISABLED
        val method = BridgeContract.methods[ctx.method] ?: return BridgeDecision.deny(BridgeCode.NOT_SUPPORTED)
        val denied =
            when {
                ctx.privacy != BridgePrivacy.CONSENTED && method.name !in PRIVACY_LIMITED_METHODS -> BridgeCode.NOT_ALLOWED
                method.needsLogin && !ctx.loggedIn -> BridgeCode.NOT_LOGGED_IN
                method.needsGesture && !ctx.userGesture -> BridgeCode.GESTURE_MISSING
                !paramsValid(method, ctx.params) -> BridgeCode.INVALID_PARAMS
                !methodWhitelistAllows(method.name, ctx.params) -> BridgeCode.NOT_ALLOWED
                else -> null
            }
        return denied?.let(BridgeDecision::deny) ?: BridgeDecision.ALLOW
    }

    /** ext.openBrowser、share.open 共用的地址判定：规范化后命中链接形态表任一类别即拒绝（BR-ATTR-29）。 */
    fun linkAllowedForBrowser(url: String): Boolean {
        val normalized = NormalizedUrl.parse(url) ?: return false
        return patterns?.matches(normalized)?.isEmpty() == true
    }

    private fun methodWhitelistAllows(
        method: String,
        params: JsonObject,
    ): Boolean =
        when (method) {
            SIGNED_REQUEST -> signedPathAllowed(params.text("method").orEmpty(), params.text("path").orEmpty())
            OPEN_APP -> params.text("target") !in com.couli.generated.BridgeAppTargets.tradeOnly
            OPEN_BROWSER -> linkAllowedForBrowser(params.text("url").orEmpty())
            SHARE_OPEN -> shareAllowed(params["content"] as? JsonObject)
            else -> true
        }

    /**
     * share.open（BR-ATTR-29 细则，判定顺序固定、每步即终）：只判 content.type=link；规范化失败、非 https、带用户信息 → 拒绝；
     * ① 命中链接形态表任一类别 → 拒绝；② 主机在 share_domains（整主机相等）→ 只放行分享页路径，非默认端口或其他路径拒绝
     * （share_page_paths 目前全为 null，TODO(N-22)：分享域上一律拒绝）；③ 其他 https 放行。
     */
    @Suppress("ReturnCount")
    private fun shareAllowed(content: JsonObject?): Boolean {
        if (content?.text("type") != LINK_CONTENT) return true
        val url = NormalizedUrl.parse(content.text("url").orEmpty()) ?: return false
        if (patterns?.matches(url)?.isEmpty() != true) return false
        if (url.host in shareHosts) {
            return url.port == null && BridgeContract.sharePagePatterns.any { it.containsMatchIn(url.path) }
        }
        return true
    }

    @Suppress("TooManyFunctions") // 三端测试接线约定的静态入口与规范化工具
    companion object {
        private const val SIGNED_REQUEST = "net.signedRequest"
        private const val OPEN_APP = "ext.openApp"
        private const val OPEN_BROWSER = "ext.openBrowser"
        private const val SHARE_OPEN = "share.open"
        private const val LINK_CONTENT = "link"
        private const val ENVELOPE_VERSION = 1
        private const val ID_MAX = 64
        private val ENVELOPE_KEYS = setOf("v", "id", "method", "params")

        /**
         * 未同意或基本模式下放行的方法（TODO(N-22)，保守）：环境与公开配置、容器自身的界面、打开 / 关闭页面（目标页由 Router 按
         * 基本模式守卫，03 §4.4）、预览图片、只读的推送权限状态。不放行登录、令牌、代发、剪贴板、埋点、外跳、分享与权限申请。
         */
        val PRIVACY_LIMITED_METHODS: Set<String> =
            setOf(
                "app.getEnv",
                "app.getConfig",
                "ui.toast",
                "ui.showLoading",
                "ui.hideLoading",
                "ui.setNavBar",
                "nav.open",
                "nav.close",
                "media.previewImage",
                "perm.getPushStatus",
            )

        /** 桥契约版本（`__REBATE_BRIDGE__.version`、app.getEnv 的 bridge_version）。 */
        val contractVersion: Int get() = BridgeContract.version

        /** 解析并校验信封（结构 + 已登记方法的参数）。非法输入不抛异常。 */
        fun parseEnvelope(raw: String): EnvelopeParse {
            val structure = parseStructure(raw)
            if (!structure.structureValid) return structure
            val method = BridgeContract.methods[structure.method]
            val paramsOk = method == null || paramsValid(method, structure.params ?: JsonObject(emptyMap()))
            return if (paramsOk) structure else structure.copy(valid = false, errorCode = BridgeCode.INVALID_PARAMS)
        }

        /** 只校验信封结构（宿主先用它取 id 与 method，参数留到级别、手势之后再判）。 */
        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        fun parseStructure(raw: String): EnvelopeParse {
            val invalid = EnvelopeParse(false, BridgeCode.INVALID_PARAMS, null, null, null, structureValid = false)
            val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return invalid
            val id = (root["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val method = (root["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val version = root["v"] as? JsonPrimitive
            val params = root["params"]
            val versionOk = version != null && !version.isString && version.content == ENVELOPE_VERSION.toString()
            val idOk = id != null && id.isNotEmpty() && id.codePointCount(0, id.length) <= ID_MAX
            val ok =
                root.keys.all { it in ENVELOPE_KEYS } &&
                    versionOk &&
                    idOk &&
                    !method.isNullOrEmpty() &&
                    (params == null || params is JsonObject)
            if (!ok) return invalid.copy(id = id?.takeIf { idOk })
            val body = if (params is JsonObject) params else JsonObject(emptyMap())
            return EnvelopeParse(true, null, method, id, body, structureValid = true)
        }

        /** method + path（去掉查询串后的路径）逐字等于 signed_paths 的一项。 */
        fun signedPathAllowed(
            method: String,
            path: String,
        ): Boolean {
            val bare = path.substringBefore('?')
            return BridgeContract.signedPaths.any { it.method == method && it.path == bare }
        }

        /** 事件名在契约 events 里（逐字匹配）。 */
        fun eventAllowed(name: String): Boolean = name in BridgeContract.events

        /** 错误码的含义原文（契约 errors）；成功为空串。 */
        fun message(code: Int): String = if (code == BridgeCode.OK) "" else BridgeContract.errors[code].orEmpty()

        /** 标准响应体：`{id, code, msg, data?}`。 */
        fun response(
            id: String,
            code: Int,
            data: JsonObject? = null,
        ): String =
            buildJsonObject {
                put("id", id)
                put("code", code)
                put("msg", message(code))
                if (data != null) put("data", data)
            }.toString()

        /** 标准事件体 `{event, data}`；不在 events 白名单里的为 null（不下发）。 */
        fun event(
            name: String,
            data: JsonObject = JsonObject(emptyMap()),
        ): String? =
            if (!eventAllowed(name)) {
                null
            } else {
                buildJsonObject {
                    put("event", name)
                    put("data", data)
                }.toString()
            }

        internal fun paramsValid(
            method: BridgeMethodContract,
            params: JsonElement,
        ): Boolean = JsonSchemaLite(BridgeContract.defs).valid(params, method.params)

        /**
         * 完整 origin 的规范化：`scheme://host[:port]`，主机转小写、去末尾点，默认端口去掉；带路径（除单个 /）、查询、片段、
         * 用户信息的不是 origin。[allowHttp] 只给 Debug 本机源用。
         */
        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        internal fun normalizeOrigin(
            origin: String,
            allowHttp: Boolean,
        ): String? {
            val uri = runCatching { URI(origin.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "https" && !(allowHttp && scheme == "http")) return null
            val host =
                uri.host
                    ?.lowercase()
                    ?.trimEnd('.')
                    ?.takeIf { it.isNotEmpty() } ?: return null
            val extra = uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null
            if (extra || (uri.rawPath.orEmpty() != "" && uri.rawPath != "/")) return null
            val defaultPort = if (scheme == "https") HTTPS_PORT else HTTP_PORT
            val port =
                uri.port
                    .takeIf { it != -1 && it != defaultPort }
                    ?.let { ":$it" }
                    .orEmpty()
            return "$scheme://$host$port"
        }

        /** 完整 origin 的规范化（容器的 allowedOriginRules 用）；不是合法 origin 为 null。 */
        fun normalizeOriginForRules(
            origin: String,
            allowHttp: Boolean,
        ): String? = normalizeOrigin(origin, allowHttp)

        /** 任意 http / https 地址的 origin（`scheme://host[:port]`，规则同 [normalizeOrigin]）；带用户信息或解析不了为 null。 */
        @Suppress("ReturnCount")
        fun originOf(url: String): String? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            if (uri.rawUserInfo != null) return null
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return normalizeOrigin("$scheme://$host$port", allowHttp = true)
        }

        private const val HTTPS_PORT = 443
        private const val HTTP_PORT = 80

        private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
