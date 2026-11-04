// 生成物，禁止手改；来源 contract.lock（tools/codegen/gen.py 由 contracts/snapshot/ 生成）
package com.couli.generated

/** 路由类型。 */
enum class RouteKind { NATIVE, H5 }

/** 路由参数的取值类型（params JSON Schema 的 type）。 */
enum class RouteParamType { STRING, OBJECT, INTEGER }

/** 路由的一个参数（params JSON Schema 的一项 property；schema 一律 additionalProperties=false）。 */
data class RouteParamSpec(
    val name: kotlin.String,
    val type: RouteParamType,
    val required: kotlin.Boolean,
    val enumValues: kotlin.collections.List<kotlin.String>?,
    val minLength: kotlin.Int?,
    val maxLength: kotlin.Int?,
    /** ECMA 正则（契约当前只有 ^https://）。 */
    val pattern: kotlin.String?,
    /** 整数参数的下限（含）；null 表示没有（CT-LOCK-02：Agreement.version minimum 1）。 */
    val minimum: kotlin.Long? = null,
)

/** 一条路由（contracts/routes.json）。 */
data class RouteSpec(
    val name: kotlin.String,
    val kind: RouteKind,
    val auth: kotlin.String,
    /** since.android；null 表示本端尚未上线。 */
    val sinceAndroid: kotlin.String?,
    val entries: kotlin.collections.List<RouteEntry>,
    val h5Path: kotlin.String?,
    val debugOnly: kotlin.Boolean,
    /** Agent 页面引导卡能否打开（agent_guide，缺省 false；03 §4.4、BR-ID-10）。 */
    val agentGuide: kotlin.Boolean,
    /** agent_guide_account（phone / delete / fund）：Agent 从不引导、改答文案的账号安全页；null 表示没有。 */
    val agentGuideAccount: kotlin.String?,
    /** params schema 的 properties（additionalProperties 一律 false）。 */
    val params: kotlin.collections.List<RouteParamSpec>,
)

/**
 * 路由表，只含非 debug_only 路由。
 * debug_only 路由在 DebugRoutes（src/debugFeatures，只被 debug、staging 变体加入；03 §3.6）。
 */
object Routes {
    val all: kotlin.collections.Map<kotlin.String, RouteSpec> =
        linkedMapOf(
            "Launch" to
                RouteSpec(
                    name = "Launch",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "BasicMode" to
                RouteSpec(
                    name = "BasicMode",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "Login" to
                RouteSpec(
                    name = "Login",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "BindPhone" to
                RouteSpec(
                    name = "BindPhone",
                    kind = RouteKind.NATIVE,
                    auth = "login",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = "phone",
                    params = emptyList(),
                ),
            "Home" to
                RouteSpec(
                    name = "Home",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH, RouteEntry.DEEPLINK),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "Search" to
                RouteSpec(
                    name = "Search",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH, RouteEntry.DEEPLINK),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "q",
                                type = RouteParamType.STRING,
                                required = false,
                                enumValues = null,
                                minLength = null,
                                maxLength = 100,
                                pattern = null,
                            ),
                            RouteParamSpec(
                                name = "platform",
                                type = RouteParamType.STRING,
                                required = false,
                                enumValues = listOf("taobao", "jd", "pdd", "meituan", "vip", "douyin", "eleme", "kuaishou", "suning"),
                                minLength = null,
                                maxLength = null,
                                pattern = null,
                            ),
                        ),
                ),
            "HotRankList" to
                RouteSpec(
                    name = "HotRankList",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = null,
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH, RouteEntry.DEEPLINK),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "platform",
                                type = RouteParamType.STRING,
                                required = false,
                                enumValues = listOf("taobao", "jd", "pdd", "meituan", "vip", "douyin", "eleme", "kuaishou", "suning"),
                                minLength = null,
                                maxLength = null,
                                pattern = null,
                            ),
                        ),
                ),
            "ProductDetail" to
                RouteSpec(
                    name = "ProductDetail",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH, RouteEntry.DEEPLINK),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "platform",
                                type = RouteParamType.STRING,
                                required = true,
                                enumValues = listOf("taobao", "jd", "pdd", "meituan", "vip", "douyin", "eleme", "kuaishou", "suning"),
                                minLength = null,
                                maxLength = null,
                                pattern = null,
                            ),
                            RouteParamSpec(
                                name = "product_key",
                                type = RouteParamType.STRING,
                                required = true,
                                enumValues = null,
                                minLength = 3,
                                maxLength = 128,
                                pattern = null,
                            ),
                            RouteParamSpec(
                                name = "item_ref",
                                type = RouteParamType.STRING,
                                required = false,
                                enumValues = null,
                                minLength = 1,
                                maxLength = 1024,
                                pattern = null,
                            ),
                        ),
                ),
            "ProductShare" to
                RouteSpec(
                    name = "ProductShare",
                    kind = RouteKind.NATIVE,
                    auth = "phone",
                    sinceAndroid = null,
                    entries = listOf(RouteEntry.IN_APP),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "platform",
                                type = RouteParamType.STRING,
                                required = true,
                                enumValues = listOf("taobao", "jd", "pdd", "meituan", "vip", "douyin", "eleme", "kuaishou", "suning"),
                                minLength = null,
                                maxLength = null,
                                pattern = null,
                            ),
                            RouteParamSpec(
                                name = "product_key",
                                type = RouteParamType.STRING,
                                required = true,
                                enumValues = null,
                                minLength = 3,
                                maxLength = 128,
                                pattern = null,
                            ),
                            RouteParamSpec(
                                name = "link_id",
                                type = RouteParamType.STRING,
                                required = false,
                                enumValues = null,
                                minLength = 1,
                                maxLength = null,
                                pattern = null,
                            ),
                        ),
                ),
            "ShareHub" to
                RouteSpec(
                    name = "ShareHub",
                    kind = RouteKind.NATIVE,
                    auth = "phone",
                    sinceAndroid = null,
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "Favorites" to
                RouteSpec(
                    name = "Favorites",
                    kind = RouteKind.NATIVE,
                    auth = "login",
                    sinceAndroid = null,
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "AuthSheet" to
                RouteSpec(
                    name = "AuthSheet",
                    kind = RouteKind.NATIVE,
                    auth = "login",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "platform",
                                type = RouteParamType.STRING,
                                required = true,
                                enumValues = listOf("taobao", "jd", "pdd", "meituan", "vip", "douyin", "eleme", "kuaishou", "suning"),
                                minLength = null,
                                maxLength = null,
                                pattern = null,
                            ),
                        ),
                ),
            "JumpTip" to
                RouteSpec(
                    name = "JumpTip",
                    kind = RouteKind.NATIVE,
                    auth = "login",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "platform",
                                type = RouteParamType.STRING,
                                required = true,
                                enumValues = listOf("taobao", "jd", "pdd", "meituan", "vip", "douyin", "eleme", "kuaishou", "suning"),
                                minLength = null,
                                maxLength = null,
                                pattern = null,
                            ),
                        ),
                ),
            "AgentChat" to
                RouteSpec(
                    name = "AgentChat",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "context",
                                type = RouteParamType.OBJECT,
                                required = false,
                                enumValues = null,
                                minLength = null,
                                maxLength = null,
                                pattern = null,
                            ),
                        ),
                ),
            "AgentConsent" to
                RouteSpec(
                    name = "AgentConsent",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "OrderList" to
                RouteSpec(
                    name = "OrderList",
                    kind = RouteKind.NATIVE,
                    auth = "login",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = true,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "scope",
                                type = RouteParamType.STRING,
                                required = false,
                                enumValues = listOf("self", "share"),
                                minLength = null,
                                maxLength = null,
                                pattern = null,
                            ),
                        ),
                ),
            "OrderDetail" to
                RouteSpec(
                    name = "OrderDetail",
                    kind = RouteKind.NATIVE,
                    auth = "login",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "order_id",
                                type = RouteParamType.STRING,
                                required = true,
                                enumValues = null,
                                minLength = 1,
                                maxLength = null,
                                pattern = null,
                            ),
                        ),
                ),
            "FindOrder" to
                RouteSpec(
                    name = "FindOrder",
                    kind = RouteKind.H5,
                    auth = "login",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = "/find-order",
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "Me" to
                RouteSpec(
                    name = "Me",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "Wallet" to
                RouteSpec(
                    name = "Wallet",
                    kind = RouteKind.NATIVE,
                    auth = "login",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = true,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "Withdraw" to
                RouteSpec(
                    name = "Withdraw",
                    kind = RouteKind.NATIVE,
                    auth = "realname",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = "fund",
                    params = emptyList(),
                ),
            "WithdrawRecords" to
                RouteSpec(
                    name = "WithdrawRecords",
                    kind = RouteKind.NATIVE,
                    auth = "login",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = true,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "withdrawal_id",
                                type = RouteParamType.STRING,
                                required = false,
                                enumValues = null,
                                minLength = 1,
                                maxLength = null,
                                pattern = null,
                            ),
                        ),
                ),
            "Ledger" to
                RouteSpec(
                    name = "Ledger",
                    kind = RouteKind.H5,
                    auth = "login",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = "/ledger",
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "RealName" to
                RouteSpec(
                    name = "RealName",
                    kind = RouteKind.NATIVE,
                    auth = "phone",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = "fund",
                    params = emptyList(),
                ),
            "Reverify" to
                RouteSpec(
                    name = "Reverify",
                    kind = RouteKind.NATIVE,
                    auth = "login",
                    sinceAndroid = null,
                    entries = listOf(RouteEntry.IN_APP),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "PayoutAccount" to
                RouteSpec(
                    name = "PayoutAccount",
                    kind = RouteKind.NATIVE,
                    auth = "realname",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = "fund",
                    params = emptyList(),
                ),
            "LaborAgreement" to
                RouteSpec(
                    name = "LaborAgreement",
                    kind = RouteKind.NATIVE,
                    auth = "realname",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = "fund",
                    params = emptyList(),
                ),
            "Settings" to
                RouteSpec(
                    name = "Settings",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "PrivacyCenter" to
                RouteSpec(
                    name = "PrivacyCenter",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "DeleteAccount" to
                RouteSpec(
                    name = "DeleteAccount",
                    kind = RouteKind.NATIVE,
                    auth = "login",
                    sinceAndroid = null,
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = "delete",
                    params = emptyList(),
                ),
            "RiskNotice" to
                RouteSpec(
                    name = "RiskNotice",
                    kind = RouteKind.NATIVE,
                    auth = "login",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "Appeal" to
                RouteSpec(
                    name = "Appeal",
                    kind = RouteKind.NATIVE,
                    auth = "login",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "DataCopy" to
                RouteSpec(
                    name = "DataCopy",
                    kind = RouteKind.NATIVE,
                    auth = "login",
                    sinceAndroid = null,
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "ForceUpdate" to
                RouteSpec(
                    name = "ForceUpdate",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "About" to
                RouteSpec(
                    name = "About",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "Messages" to
                RouteSpec(
                    name = "Messages",
                    kind = RouteKind.H5,
                    auth = "login",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = "/messages",
                    debugOnly = false,
                    agentGuide = true,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "InviteShare" to
                RouteSpec(
                    name = "InviteShare",
                    kind = RouteKind.H5,
                    auth = "phone",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = "/invite",
                    debugOnly = false,
                    agentGuide = true,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "InviteBackfill" to
                RouteSpec(
                    name = "InviteBackfill",
                    kind = RouteKind.NATIVE,
                    auth = "phone",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "source",
                                type = RouteParamType.STRING,
                                required = false,
                                enumValues = listOf("me", "login"),
                                minLength = null,
                                maxLength = null,
                                pattern = null,
                            ),
                        ),
                ),
            "InviteLink" to
                RouteSpec(
                    name = "InviteLink",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.DEEPLINK),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "code",
                                type = RouteParamType.STRING,
                                required = true,
                                enumValues = null,
                                minLength = 1,
                                maxLength = 16,
                                pattern = null,
                            ),
                        ),
                ),
            "Rules" to
                RouteSpec(
                    name = "Rules",
                    kind = RouteKind.H5,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH, RouteEntry.DEEPLINK),
                    h5Path = "/rules",
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "Help" to
                RouteSpec(
                    name = "Help",
                    kind = RouteKind.H5,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH, RouteEntry.DEEPLINK),
                    h5Path = "/help",
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "article_id",
                                type = RouteParamType.STRING,
                                required = false,
                                enumValues = null,
                                minLength = 1,
                                maxLength = null,
                                pattern = null,
                            ),
                        ),
                ),
            "Notice" to
                RouteSpec(
                    name = "Notice",
                    kind = RouteKind.H5,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH, RouteEntry.DEEPLINK),
                    h5Path = "/notice",
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "article_id",
                                type = RouteParamType.STRING,
                                required = false,
                                enumValues = null,
                                minLength = 1,
                                maxLength = null,
                                pattern = null,
                            ),
                        ),
                ),
            "Agreement" to
                RouteSpec(
                    name = "Agreement",
                    kind = RouteKind.H5,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH, RouteEntry.DEEPLINK),
                    h5Path = "/agreement",
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "type",
                                type = RouteParamType.STRING,
                                required = false,
                                enumValues = listOf("agreement", "privacy", "sdk_list", "labor_agreement", "collect_list"),
                                minLength = null,
                                maxLength = null,
                                pattern = null,
                            ),
                            RouteParamSpec(
                                name = "version",
                                type = RouteParamType.INTEGER,
                                required = false,
                                enumValues = null,
                                minLength = null,
                                maxLength = null,
                                pattern = null,
                                minimum = 1L,
                            ),
                        ),
                ),
            "WebPage" to
                RouteSpec(
                    name = "WebPage",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "url",
                                type = RouteParamType.STRING,
                                required = true,
                                enumValues = null,
                                minLength = null,
                                maxLength = 2048,
                                pattern = "^https://",
                            ),
                        ),
                ),
            "ExternalPage" to
                RouteSpec(
                    name = "ExternalPage",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH, RouteEntry.DEEPLINK),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "url",
                                type = RouteParamType.STRING,
                                required = true,
                                enumValues = null,
                                minLength = null,
                                maxLength = 2048,
                                pattern = "^https://",
                            ),
                            RouteParamSpec(
                                name = "title",
                                type = RouteParamType.STRING,
                                required = false,
                                enumValues = null,
                                minLength = null,
                                maxLength = 20,
                                pattern = null,
                            ),
                            RouteParamSpec(
                                name = "nav_style",
                                type = RouteParamType.STRING,
                                required = false,
                                enumValues = listOf("light", "dark"),
                                minLength = null,
                                maxLength = null,
                                pattern = null,
                            ),
                        ),
                ),
            "InvitedFriends" to
                RouteSpec(
                    name = "InvitedFriends",
                    kind = RouteKind.NATIVE,
                    auth = "phone",
                    sinceAndroid = null,
                    entries = listOf(RouteEntry.IN_APP),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "LevelUpgrade" to
                RouteSpec(
                    name = "LevelUpgrade",
                    kind = RouteKind.NATIVE,
                    auth = "phone",
                    sinceAndroid = null,
                    entries = listOf(RouteEntry.IN_APP),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
            "LinkLanding" to
                RouteSpec(
                    name = "LinkLanding",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = null,
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH, RouteEntry.DEEPLINK),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "link_id",
                                type = RouteParamType.STRING,
                                required = true,
                                enumValues = null,
                                minLength = 1,
                                maxLength = null,
                                pattern = null,
                            ),
                        ),
                ),
            "Earnings" to
                RouteSpec(
                    name = "Earnings",
                    kind = RouteKind.H5,
                    auth = "login",
                    sinceAndroid = null,
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = "/earnings",
                    debugOnly = false,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "platform",
                                type = RouteParamType.STRING,
                                required = false,
                                enumValues = listOf("taobao", "jd", "pdd", "meituan", "vip", "douyin", "eleme", "kuaishou", "suning"),
                                minLength = null,
                                maxLength = null,
                                pattern = null,
                            ),
                        ),
                ),
            "AuthManage" to
                RouteSpec(
                    name = "AuthManage",
                    kind = RouteKind.NATIVE,
                    auth = "login",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.PUSH),
                    h5Path = null,
                    debugOnly = false,
                    agentGuide = true,
                    agentGuideAccount = null,
                    params = emptyList(),
                ),
        )

    fun spec(name: kotlin.String): RouteSpec? = all[name]
}
