// core:router —— 路由：统一入口守卫、深链解析、pending_action、推送落点、启动意图排队（APP-02a；03 §4.4）。
// 纯逻辑不依赖界面框架；页面映射由 App 的注册表实现（Router 不 import Feature）。
// debug_only 路由表在 src/debugFeatures（debug、staging），release 在 src/release 给空表。
plugins {
    alias(libs.plugins.couli.android.library)
}

android {
    namespace = "com.couli.core.router"
}

androidComponents {
    // RouterVectorTest 含 debug_only 路由（HomePreview）的放行用例，release 变体没有调试路由表：release 不建单测
    // （与 core:generated 相同；verify.sh 跑 testDebugUnitTest）。
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
}

dependencies {
    // RouteSpec、Routes、RouteEntry 与 kotlinx-serialization-json（JsonObject）
    api(project(":core:generated"))
    // SharedPreferences 命名空间登记；kotlinx-coroutines（StateFlow、推送回查超时）
    api(project(":core:config"))

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
