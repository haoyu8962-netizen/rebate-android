package com.couli.core.home

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 首页草稿预览的策略（G24，TECH-11，03 §3.6，AC-S1-86 ③④）：只在 debug / staging 编译（src/debugFeatures；Release 制品里没有这个类名，release-scan
 * 按 debug_only 路由名 HomePreview 检查）。用当前环境调 previewPage：成功 → 草稿；30701 → 过期 / 环境不符页；其余 → 错误态（统一错误消息 + 【重试】）。
 * 预览只读：不写 LKG、弹窗计数、公告关闭记录，不发埋点，不切换环境。
 */
object HomePreviewPolicy {
    const val DRAFT = "draft"
    const val EXPIRED = "expired"
    const val ERROR = "error"
    private const val EXPIRED_CODE = 30701

    /** previewPage 的结果 → 版式。 */
    fun screen(code: Int?): String =
        when (code) {
            null -> DRAFT
            EXPIRED_CODE -> EXPIRED
            else -> ERROR
        }

    /** 向量接线：routeParams 只有 page_key 与 token（Router 已丢弃多余参数）；result 是 `{kind: ok|error, code?}`。 */
    fun of(
        routeParams: JsonObject,
        result: JsonObject,
    ): JsonObject {
        val ok = (result["kind"] as? JsonPrimitive)?.content == "ok"
        val code = (result["code"] as? JsonPrimitive)?.intOrNull
        return JsonObject(
            linkedMapOf(
                "screenRaw" to JsonPrimitive(screen(if (ok) null else code ?: 0)),
                "pageKey" to (routeParams["page_key"] ?: JsonPrimitive("")),
                "token" to (routeParams["token"] ?: JsonPrimitive("")),
                "writesLkg" to JsonPrimitive(false),
                "writesPopupCount" to JsonPrimitive(false),
                "writesNoticeClosed" to JsonPrimitive(false),
                "sendsEvents" to JsonPrimitive(false),
                "switchesEnv" to JsonPrimitive(false),
            ),
        )
    }
}
