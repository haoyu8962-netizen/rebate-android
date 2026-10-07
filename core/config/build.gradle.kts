// core:config —— 运行环境（AppEnvironment）、环境切换与清理入口、调试能力边界（03 §3.6）；
// 强更检查的纯逻辑与持久化 com.couli.core.update（APP-02c UpdateGate，三端同名）。
// APP-03b：客户端纯内核 com.couli.core.clientcore（DeviceIdPolicy、ConfigResolver、ConfigStore、TrackerQueue、
// PermissionRequester；三端测试接线约定），事件构造函数、设备哈希种子与包内默认配置由 core:generated 生成。
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
    // ClientEvents、DeviceHashSeeds、BundledConfig 与 kotlinx-serialization-json（APP-03b）
    api(project(":core:generated"))

    // UpdateGateVectorTest、ClientCoreVectorTest 读 specs/*.json（kotlinx-serialization-json 由 core:generated 带来）
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
