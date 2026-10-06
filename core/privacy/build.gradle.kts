// core:privacy —— 隐私闸门：同意状态与持久化、撤回同意的清理入口、第三方 SDK 初始化的唯一入口（03 §4.1；APP-02b）。
plugins {
    alias(libs.plugins.couli.android.library)
}

android {
    namespace = "com.couli.core.privacy"
}

dependencies {
    // SharedPreferences 名的集中登记（CouliPreferenceNamespaces）
    api(project(":core:config"))

    // PrivacyGateVectorTest 读 specs/privacy-gate-vectors.json
    testImplementation(libs.kotlinx.serialization.json)
    // SharedPreferencesPrivacyStore 与清理入口的 Robolectric 测试
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
