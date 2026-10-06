// 生成物，禁止手改；来源 contract.lock（tools/codegen/gen.py 由 contracts/snapshot/ 生成）
package com.couli.generated

/**
 * 商店对照表的一行（specs/app-stores.yaml）。值为 null 的商店一律视为「本机打不开」（APP-02c；needs N-17）。
 * installSourcePackages：Android 安装来源包名（本机读到的安装来源与它相等即认作这家商店）。
 */
data class AppStoreSpec(
    val key: kotlin.String?,
    val name: kotlin.String,
    val platform: kotlin.String,
    val channels: kotlin.collections.List<kotlin.String>,
    val installSourcePackages: kotlin.collections.List<kotlin.String>,
    val openMethod: kotlin.String?,
    val target: kotlin.String?,
)

/** 商店对照表（specs/app-stores.yaml；规划/03 §4.1、BR-ID-01）。代码里不写死商店键与打开方式。 */
object AppStores {
    /** version */
    const val version: kotlin.String = "0"

    val all: kotlin.collections.List<AppStoreSpec> =
        listOf(
            AppStoreSpec(
                key = null,
                name = "App Store",
                platform = "ios",
                channels = listOf("appstore"),
                installSourcePackages = listOf(),
                openMethod = null,
                target = null,
            ),
            AppStoreSpec(
                key = null,
                name = "华为应用市场",
                platform = "android",
                channels = listOf("huawei"),
                installSourcePackages = listOf(),
                openMethod = null,
                target = null,
            ),
            AppStoreSpec(
                key = null,
                name = "鸿蒙系统应用市场",
                platform = "harmony",
                channels = listOf("agc"),
                installSourcePackages = listOf(),
                openMethod = null,
                target = null,
            ),
        )

    /** 某一端（client_platform：ios / android / harmony）的商店。 */
    fun forPlatform(platform: kotlin.String): kotlin.collections.List<AppStoreSpec> = all.filter { it.platform == platform }
}
