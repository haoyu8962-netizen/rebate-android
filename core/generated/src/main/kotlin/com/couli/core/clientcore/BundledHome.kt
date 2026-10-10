// 生成物，禁止手改；来源 contract.lock（tools/codegen/gen.py 由 contracts/snapshot/ 生成）
package com.couli.core.clientcore

/**
 * 包内默认首页（APP-11 G17；03 §6.2 第 4 步三级兜底的最后一级）：读取 Java 资源 default_home.json（由 specs/home-sdui-vectors.json 的
 * goldenPages.default_home 逐项生成，三端同一来源）。只含不依赖数据源的金刚区与双入口卡；没有 banner、公告、商品卡、popup，不引用网络图片。
 */
object BundledHome {
    const val RESOURCE: kotlin.String = "default_home.json"

    /** 页面 JSON 文本；读不出为 null（首页按包内默认解码失败处理，显示 StateLoadFailed）。 */
    fun pageJson(): kotlin.String? =
        runCatching {
            BundledHome::class.java.classLoader?.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
}
