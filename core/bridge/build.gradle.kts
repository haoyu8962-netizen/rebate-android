// core:bridge —— JSBridge 的纯逻辑与宿主（APP-04；03 §5）：BridgeProtocol（信封、级别、白名单、事件）、ExternalNavPolicy
// （第三方页容器的导航判定）与 BridgeHost（方法分派、强更守卫、在途 id）。不依赖 WebView：传输层与容器在 feature:web。
plugins {
    alias(libs.plugins.couli.android.library)
}

android {
    namespace = "com.couli.core.bridge"
}

dependencies {
    // 桥契约资源 bridge.schema.json、BridgeAppTargets、ClientEvents 与 kotlinx-serialization-json
    api(project(":core:generated"))
    // BridgeHost 的异步方法（超时、取消）
    api(libs.kotlinx.coroutines.android)

    // BridgeVectorTest 读 specs/bridge-vectors.json（kotlinx-serialization-json 由 core:generated 带来）
    testImplementation(libs.kotlinx.coroutines.test)
    // F-X-25：用真实 SessionTokens.replaceIf 轮换凭据，验证刷新不等于身份变化。
    testImplementation(project(":core:network"))
}
