// core:orders —— 订单（APP-08；01 F-ORD-07 / 09 / 15 / 16；BR-TEXT-02 / 03 / 04 / 05 / 10、BR-ATTR-17 / 21、BR-ID-13 / 30）：
// - 纯逻辑（三端同一套向量 specs/order-vectors.json）：手写解析 OrderJson、列表查询 OrderQuery 与月份 / 平台选项、显示规则
//   OrderPresentation（状态文案、金额格、订单号、标题与图、预计结算月份、时间线、原因区、「查看说明」、详情按钮、「查看商品」）、
//   待跟单卡 PendingTrackCards / PendingTrackDismiss、错误 → 动作 OrderErrorAction、缓存策略与落盘 OrderStaleCache；
// - 执行器 PendingTrackService（订单列表自购子 Tab 与发起购买的页面共用，feature 之间不能互相依赖，所以放在 Core）；
// - 界面组件 PendingTrackCard；文案草稿表 OrderDraftTexts（TODO(N-31)，契约补键后删除）。
plugins {
    alias(libs.plugins.couli.android.library)
    alias(libs.plugins.couli.android.compose)
}

android {
    namespace = "com.couli.core.orders"
}

dependencies {
    // ApiCall、PageApi、CallOwnership、ErrorActionMapper、ResultSignals；Routes、DefaultTexts 与 kotlinx-serialization-json 经它传递
    api(project(":core:network"))
    // 路由参数按 routes.json 校验（RouteParams：「查看说明」的配置目标）
    api(project(":core:router"))
    // 金额与时间格式化（CouliFormat）、待跟单卡的设计令牌与基础组件
    api(project(":core:designsystem"))
    implementation(libs.androidx.lifecycle.runtime.compose)

    // OrderVectorTest 读 specs/order-vectors.json（JUnit4 由 convention 插件给）；缓存落盘测试用临时目录
    testImplementation(libs.kotlinx.coroutines.test)
}
