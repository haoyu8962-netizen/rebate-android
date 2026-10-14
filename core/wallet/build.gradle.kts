// core:wallet —— 钱包、提现、实名、收款账号、劳务协议与二次验证的纯逻辑（APP-09；01 F-WDR-01…05、08、09、13、14；BR-WDR、BR-ID-08 / 10 / 25 / 33、
// BR-FUND-18、BR-TEXT-01 / 06 / 07 / 08 / 10）：
// - 手写解析 WalletJson（WalletSummary、WithdrawRules、WithdrawalSummary / Withdrawal、WithdrawalListData、PayoutAccount、StepUpData、
//   SendSmsCodeData、AbandonIdempotencyKeyData）；
// - 显示与判定（三端同一套向量 specs/wallet-vectors.json）：WalletSummaryView、WithdrawAmountInput、WithdrawRulesView、WithdrawEstimateGate、
//   WithdrawBlockGuide、WithdrawConditionText、SensitiveOutcome、StepUpTokenCache、WithdrawalText、TradeNoRow、PayoutAccountView、
//   PayoutPendingSummary、PayoutAccountForm、RealNameForm、EarningsEntry、WalletStaleCache、FundRouteParams；
// - 文案草稿表 WalletDraftTexts（TODO(N-32)，契约补键后删除）；钱包摘要断网缓存的落盘 WalletStaleCache.Store。
// 收款账号与实名状态以后也给「我的」、设置页用：feature 之间不能互相依赖，所以放在 Core。
plugins {
    alias(libs.plugins.couli.android.library)
    // 二次验证面板（StepUpSheet，APP-13a 从 feature:wallet 挪来，钱包与「我的」共用）
    alias(libs.plugins.couli.android.compose)
}

android {
    namespace = "com.couli.core.wallet"
}

dependencies {
    // ApiResult、ErrorActionMapper、ResultSignals；DefaultTexts、Routes 与 kotlinx-serialization-json 经它传递
    api(project(":core:network"))
    // 资金与实名四页的参数丢弃（RouteParams）、版本比较（AppVersion）、帮助文章目标校验
    api(project(":core:router"))
    // 金额与时间格式化（CouliFormat，整数分）
    api(project(":core:designsystem"))
    // 预计结算月份的同年 / 跨年写法（CreditPeriodText，APP-08）
    implementation(project(":core:orders"))

    // WalletVectorTest 读 specs/wallet-vectors.json（JUnit4 由 convention 插件给；kotlinx-serialization-json 经 core:network 传递）；缓存落盘测试用临时目录
    testImplementation(libs.kotlinx.coroutines.test)
}
