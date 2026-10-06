// core:network —— 网络核心（APP-03a；03 §4.2）：请求管线 ApiClient（公共头 → 签名 → 发送 → 解包 → ErrorActionMapper）、
// 请求签名（BR-ID-09）、令牌与单飞刷新、超时重试、未决幂等键（BR-ID-10）。
// 操作登记表 ApiOperations 由 tools/codegen/gen.py 生成在 core:generated（包 com.couli.core.generated）。
plugins {
    alias(libs.plugins.couli.android.library)
}

android {
    namespace = "com.couli.core.network"
}

dependencies {
    // ApiOperations、ErrorCodes、DefaultTexts 与 kotlinx-serialization-json（解包外壳、JsonObject）
    api(project(":core:generated"))
    // SharedPreferences 命名空间（CouliPreferenceNamespaces）、环境切换清理入口
    api(project(":core:config"))
    implementation(libs.okhttp)

    // NetworkVectorTest 读 specs/network-vectors.json；集成测试用 MockWebServer 当本地假服务
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
