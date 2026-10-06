// core:config —— 运行环境（AppEnvironment）、环境切换与清理入口、调试能力边界（03 §3.6）；
// 强更检查的纯逻辑与持久化 com.couli.core.update（APP-02c UpdateGate，三端同名）。
// 非 prod 地址与切换逻辑在 src/debugFeatures，只被 debug、staging 变体加入。
plugins {
    alias(libs.plugins.couli.android.library)
}

android {
    namespace = "com.couli.core.config"
}

dependencies {
    // EnvironmentGate.state（StateFlow）
    api(libs.kotlinx.coroutines.android)

    // UpdateGateVectorTest 读 specs/update-vectors.json
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
