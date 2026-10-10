// core:me —— 「我的」、设置、隐私中心、关于、封禁 / 冻结说明与申诉、统一客服出口、推送上报策略的纯逻辑（APP-12；01 §4.2、F-ACC-05 / 13、
// F-PRIV-04 / 06 / 07、F-MSG-01 / 03 / 04 / 07、F-OBS-06、F-RISK-04；BR-ID-02 / 04 / 07 / 11 / 13 / 15 / 16 / 31 / 33 / 36 / 39、BR-TEXT-10 / 11 / 14 / 23）：
// - 手写解析 MeJson（Me 13 个字段、Appeal、AppealListData，坏数据整条按失败）；
// - 显示与判定（三端同一套向量 specs/me-vectors.json）：MeView、NotifyGuideBar、SettingsItems、PrivacyCenterView、Personalization、
//   PrivacyWithdrawFlow、NicknameInput / NicknameOutcome、RiskNoticeView、AppealView / AppealForm / AppealOutcome、PendingWithdrawals、
//   CsEntryDecision / CsLink、Diagnostics、AboutView、Route10006、MeLocalData、PushReportPolicy；
// - 统一客服出口的状态机 CustomerServiceEntry（界面由 App 给）、本机偏好 MeLocalStore（couli_me_prefs）、文案草稿表 MeDraftTexts（TODO(N-33)）。
// 「我的」与设置、隐私中心、钱包、订单、登录等多个 feature 都要用客服出口与这些规则：feature 之间不能互相依赖，所以放在 Core。
plugins {
    alias(libs.plugins.couli.android.library)
}

android {
    namespace = "com.couli.core.me"
}

dependencies {
    // ApiResult、ResultSignals、ErrorEffect；DefaultTexts、Routes 与 kotlinx-serialization-json 经它传递
    api(project(":core:network"))
    // 冻结恢复时间（CouliFormat.timeListFuture，BR-TEXT-11）；MeVectorTest 直接比对同一函数
    api(project(":core:designsystem"))

    // MeVectorTest 读 specs/me-vectors.json（JUnit4 由 convention 插件给；kotlinx-serialization-json 经 core:network 传递）；本机偏好测试用 Robolectric
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
