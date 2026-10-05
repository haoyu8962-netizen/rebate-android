// 生成物，禁止手改；来源 contract.lock（tools/codegen/gen.py 由 contracts/snapshot/ 生成）
package com.couli.generated

/** 路由类型。 */
enum class RouteKind { NATIVE, H5 }

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
                ),
        )

    fun spec(name: kotlin.String): RouteSpec? = all[name]
}
