// 生成物，禁止手改；来源 contract.lock（tools/codegen/gen.py 由 contracts/snapshot/ 生成）
package com.couli.generated

/** 一个桥方法（contracts/bridge.schema.json methods）。缺省：timeoutMs、sinceAndroid 为 null，gestureRequired 为 false。 */
data class BridgeMethodSpec(
    val name: kotlin.String,
    val level: kotlin.String,
    val timeoutMs: kotlin.Int?,
    val sinceAndroid: kotlin.String?,
    val gestureRequired: kotlin.Boolean,
)

object BridgeMethods {
    val all: kotlin.collections.Map<kotlin.String, BridgeMethodSpec> =
        linkedMapOf(
            "app.getEnv" to
                BridgeMethodSpec(
                    name = "app.getEnv",
                    level = "L0",
                    timeoutMs = null,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "app.getConfig" to
                BridgeMethodSpec(
                    name = "app.getConfig",
                    level = "L0",
                    timeoutMs = null,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "auth.getUser" to
                BridgeMethodSpec(
                    name = "auth.getUser",
                    level = "L1",
                    timeoutMs = 10000,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "auth.login" to
                BridgeMethodSpec(
                    name = "auth.login",
                    level = "L0",
                    timeoutMs = null,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "auth.getH5Token" to
                BridgeMethodSpec(
                    name = "auth.getH5Token",
                    level = "L1",
                    timeoutMs = 10000,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "net.signedRequest" to
                BridgeMethodSpec(
                    name = "net.signedRequest",
                    level = "L2",
                    timeoutMs = 15000,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "ui.toast" to
                BridgeMethodSpec(
                    name = "ui.toast",
                    level = "L0",
                    timeoutMs = null,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "ui.showLoading" to
                BridgeMethodSpec(
                    name = "ui.showLoading",
                    level = "L0",
                    timeoutMs = null,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "ui.hideLoading" to
                BridgeMethodSpec(
                    name = "ui.hideLoading",
                    level = "L0",
                    timeoutMs = null,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "ui.setNavBar" to
                BridgeMethodSpec(
                    name = "ui.setNavBar",
                    level = "L0",
                    timeoutMs = null,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "nav.open" to
                BridgeMethodSpec(
                    name = "nav.open",
                    level = "L0",
                    timeoutMs = 10000,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "nav.close" to
                BridgeMethodSpec(
                    name = "nav.close",
                    level = "L0",
                    timeoutMs = null,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "trade.openProduct" to
                BridgeMethodSpec(
                    name = "trade.openProduct",
                    level = "L0",
                    timeoutMs = 10000,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "trade.convertAndOpen" to
                BridgeMethodSpec(
                    name = "trade.convertAndOpen",
                    level = "L2",
                    timeoutMs = null,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "trade.authorize" to
                BridgeMethodSpec(
                    name = "trade.authorize",
                    level = "L2",
                    timeoutMs = null,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "trade.openUnionActivity" to
                BridgeMethodSpec(
                    name = "trade.openUnionActivity",
                    level = "L2",
                    timeoutMs = null,
                    sinceAndroid = null,
                    gestureRequired = false,
                ),
            "share.open" to
                BridgeMethodSpec(
                    name = "share.open",
                    level = "L1",
                    timeoutMs = null,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "media.saveImage" to
                BridgeMethodSpec(
                    name = "media.saveImage",
                    level = "L1",
                    timeoutMs = null,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "media.previewImage" to
                BridgeMethodSpec(
                    name = "media.previewImage",
                    level = "L0",
                    timeoutMs = null,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "clipboard.write" to
                BridgeMethodSpec(
                    name = "clipboard.write",
                    level = "L0",
                    timeoutMs = null,
                    sinceAndroid = "1.0.0",
                    gestureRequired = true,
                ),
            "clipboard.read" to
                BridgeMethodSpec(
                    name = "clipboard.read",
                    level = "L2",
                    timeoutMs = 5000,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "clipboard.setAutoDetect" to
                BridgeMethodSpec(
                    name = "clipboard.setAutoDetect",
                    level = "L0",
                    timeoutMs = null,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "ext.openApp" to
                BridgeMethodSpec(
                    name = "ext.openApp",
                    level = "L1",
                    timeoutMs = 10000,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "ext.openBrowser" to
                BridgeMethodSpec(
                    name = "ext.openBrowser",
                    level = "L0",
                    timeoutMs = null,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "ext.openMiniProgram" to
                BridgeMethodSpec(
                    name = "ext.openMiniProgram",
                    level = "L1",
                    timeoutMs = 10000,
                    sinceAndroid = null,
                    gestureRequired = false,
                ),
            "cs.open" to
                BridgeMethodSpec(
                    name = "cs.open",
                    level = "L0",
                    timeoutMs = 10000,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "perm.getPushStatus" to
                BridgeMethodSpec(
                    name = "perm.getPushStatus",
                    level = "L0",
                    timeoutMs = 5000,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "perm.request" to
                BridgeMethodSpec(
                    name = "perm.request",
                    level = "L1",
                    timeoutMs = null,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "track.event" to
                BridgeMethodSpec(
                    name = "track.event",
                    level = "L0",
                    timeoutMs = null,
                    sinceAndroid = "1.0.0",
                    gestureRequired = false,
                ),
            "media.scan" to
                BridgeMethodSpec(
                    name = "media.scan",
                    level = "L1",
                    timeoutMs = null,
                    sinceAndroid = null,
                    gestureRequired = false,
                ),
            "media.uploadImage" to
                BridgeMethodSpec(
                    name = "media.uploadImage",
                    level = "L1",
                    timeoutMs = null,
                    sinceAndroid = null,
                    gestureRequired = false,
                ),
        )

    fun spec(name: kotlin.String): BridgeMethodSpec? = all[name]
}

/** 桥错误码 → 含义原文（contracts/bridge.schema.json errors）。 */
object BridgeErrors {
    val all: kotlin.collections.Map<kotlin.Int, kotlin.String> =
        linkedMapOf(
            90001 to "Method does not exist or is not supported",
            90002 to "Invalid params",
            90003 to "Timeout",
            90004 to "Cancelled by the user",
            90401 to "Not logged in",
            90403 to "Origin or frame not allowed, method + path outside signed_paths, or the method's own whitelist rejection (methods with whitelist_90403: ext.openApp trade_only targets, ext.openBrowser addresses matching link_patterns, share.open links outside the allowed range; 03 §5.3)",
            90404 to "User gesture missing",
            90500 to "Native internal error; also for calls not executed under the force-update guard (03 §4.4)",
        )
}
