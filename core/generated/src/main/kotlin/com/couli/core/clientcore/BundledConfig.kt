// 生成物，禁止手改；来源 contract.lock（tools/codegen/gen.py 由 contracts/snapshot/ 生成）
package com.couli.core.clientcore

/**
 * 包内默认配置（03 §4.3）：没有当前配置与 LKG 时使用。读取 Java 资源 default_config.json（由 openapi GET /v1/config 的
 * 200 响应示例的 data 生成，去掉示例里的地址类字段，link_patterns 只留版本、规则为空）。
 * TODO(N-21): 发布构建时按生产配置当时的值生成（features.search_status、link_patterns 等，03 §4.3），不用契约示例。
 */
object BundledConfig {
    private const val RESOURCE = "default_config.json"

    /** data 对象的 JSON 文本。 */
    val dataJson: kotlin.String by lazy {
        val stream =
            BundledConfig::class.java.classLoader?.getResourceAsStream(RESOURCE)
                ?: error("missing resource $RESOURCE")
        stream.use { it.readBytes().decodeToString() }
    }
}
