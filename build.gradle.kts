// 根项目：只声明插件版本（各模块经 build-logic 的 convention 插件应用）。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.couli.lint)
}

// lint 违反样例（tools/ci/verify.sh 用）：给 :feature:home 加一条对 :feature:search 的依赖，
// 证明 feature 互相依赖会让构建失败。平时不生效。
providers.gradleProperty("couli.fixture.featureDependency").orNull?.let { fixture ->
    project(":feature:home") {
        afterEvaluate {
            apply(from = rootProject.file(fixture))
        }
    }
}
