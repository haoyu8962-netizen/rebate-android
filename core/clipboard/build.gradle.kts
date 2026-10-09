// core:clipboard —— 剪贴板识别（APP-07；03 §4.6；BR-ID-16、BR-INV-04）：
// - 纯逻辑（三端同一套向量 specs/clipboard-vectors.json）：读取判定 ClipboardReadPolicy、本地分类 ClipboardClassifier、邀请码抽取
//   InviteCodeExtractor、自写记录 ClipboardWriteLedger、24 小时提示记录 PromptHistory、parse 结果映射 ParseResultAction、手动粘贴
//   ManualPasteAction、邀请确认去向 InviteConfirmAction；
// - Android 读取时机 ClipboardWatcher（回前台后第一次获得窗口焦点读 1 次）、系统剪贴板端口 SystemClipboard、ParseInputService；
// - 界面组件 ClipboardPromptBar、PasteEntry、HomePasteCard（文案由 App 给，本模块不写中文）。
plugins {
    alias(libs.plugins.couli.android.library)
    alias(libs.plugins.couli.android.compose)
}

android {
    namespace = "com.couli.core.clipboard"
}

dependencies {
    // ApiCall、PageApi、CallOwnership、ErrorActionMapper；DefaultTexts 与 kotlinx-serialization-json 经它传递
    api(project(":core:network"))
    // 提示条、粘贴入口的设计令牌与基础组件
    api(project(":core:designsystem"))

    // ClipboardVectorTest 读 specs/clipboard-vectors.json；执行层测试用 Robolectric（读写计数的假剪贴板）
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
