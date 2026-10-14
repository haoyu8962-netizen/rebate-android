// feature:agent —— Agent 页（APP-10；03 §7；01 F-AGENT-01…21）：页面骨架（标题与【新对话】、公示、空态与示例、消息列表、快捷胶囊、输入框、【停止】、
// 回到底部）、卡片渲染器（10 种已注册卡 + 兜底文字，含页面引导卡 page_guide 与收益卡 earnings_summary）、AgentConsent 系统模态、设置页【撤回 AI 服务同意】一行。
// 流协议、票据、状态机、草稿表都在 core:agent（本模块只展示与转交渲染时的票据，不写中文）。feature 之间不得互相依赖：购买按钮与浮层由 App 经槽位注入。
plugins {
    alias(libs.plugins.couli.android.library)
    alias(libs.plugins.couli.android.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.couli.feature.agent"
}

// 界面快照基准入库：src/test/snapshots（recordRoborazziDebug 生成，verifyRoborazziDebug 比对；tools/ci/verify.sh 跑后者，G32 ②）。
roborazzi {
    outputDir.set(file("src/test/snapshots"))
}

dependencies {
    // 流协议、控制器、票据、草稿表；core:wallet、core:orders、core:router、core:designsystem 经它传递
    api(project(":core:agent"))
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.compose.ui.test.manifest)
}
