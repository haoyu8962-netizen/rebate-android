// core:designsystem —— 设计令牌 → Compose 主题常量、组件与格式化（03 §10；DS-01）。
plugins {
    alias(libs.plugins.couli.android.library)
    alias(libs.plugins.couli.android.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.couli.core.designsystem"
}

// 快照基准图入库：src/test/snapshots（recordRoborazziDebug 生成，verifyRoborazziDebug 比对；tools/ci/verify.sh 跑后者）。
roborazzi {
    outputDir.set(file("src/test/snapshots"))
}

dependencies {
    api(project(":core:generated"))
    // DS-02：NavBar 接系统返回 / 预测性返回（BackHandler）；版本目录里已有的库，App 已在用
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    // Robolectric 下 Compose 测试要的空 Activity（只进 debug 变体的测试清单）
    debugImplementation(libs.compose.ui.test.manifest)
}
