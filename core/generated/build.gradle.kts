// 契约生成物（tools/codegen/gen.py 生成，禁止手改）。
// debug_only 路由在 src/debugFeatures，只被 debug、staging 变体加入（couli.android.library 插件）。
plugins {
    alias(libs.plugins.couli.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.couli.generated"
}

androidComponents {
    // src/test 的一致性测试引用 DebugRoutes，它不在 release 变体里：release 不建单测。
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
