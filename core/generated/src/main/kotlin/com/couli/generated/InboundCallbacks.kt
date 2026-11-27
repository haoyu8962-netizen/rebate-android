// 生成物，禁止手改；来源 contract.lock（tools/codegen/gen.py 由 contracts/snapshot/ 生成）
package com.couli.generated

/**
 * apps.json inbound 里本端（android）登记的自定义 scheme（FU-AND-TB-WEBCODE-A）：入站中转 Activity 只处理这些 scheme 的 data。
 * intent-filter 在同一次生成的 core/generated/src/main/AndroidManifest.xml 里，两处同源。
 */
object InboundCallbacks {
    val ANDROID_CUSTOM_SCHEMES: kotlin.collections.List<kotlin.String> = kotlin.collections.listOf("couli")
}
