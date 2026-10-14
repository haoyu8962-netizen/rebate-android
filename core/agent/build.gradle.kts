// core:agent —— Agent 页（APP-10；03 §7 全节；04 §8；BR-AI-01 / 06 / 13 / 15 / 23 / 24、BR-ID-14、BR-TEXT-10 / 22）的纯逻辑与页面状态机：
// - 流协议（三端同一套向量 specs/agent-vectors.json）：AgentSse（SSE 文本 → 帧）、AgentFrameDecoder / AgentCardDecoder（按 agent-stream.schema.json 校验）、
//   StreamReducer（纯函数 (state, input) → state，fixture 驱动）、AgentText（受限渲染与兜底剥离）、各策略（页面引导、收益卡、票据、错误、duplicate、
//   client_msg_id、同意）；
// - StreamClient（界面无关：Content-Type 分流、增量喂 AgentSse、30 秒断线计时、cancel 同步作废）与待定 operation 的端口 AgentContractPort
//   （G01：锁定契约没有 /v1/agent/*，只由 debug / staging 的本机假服务实现，Release 没有端口）；
// - AgentController（身份构造时一次取得、之后只比较；对话世代、run 票据、卡片动作世代、AI 同意闸门、可见性与进入世代）；
// - 文案草稿表 AgentDraftTexts（TODO(N-36)）。feature 之间不能互相依赖，页面在 feature:agent，组合在 app。
plugins {
    alias(libs.plugins.couli.android.library)
}

android {
    namespace = "com.couli.core.agent"
}

dependencies {
    // ApiResult、ResultSignals；DefaultTexts、Routes、ClientEvents 与 kotlinx-serialization-json 经它传递
    api(project(":core:network"))
    // RouteSource（agent_card / in_app）
    api(project(":core:router"))
    // 提现主状态标题与收益卡字段标签（WalletDraftTexts，G11、G12）、统一前置路由（SensitiveOutcome.prereqRoute）、金额格式化（CouliFormat，经它传递）
    api(project(":core:wallet"))
    // 订单状态卡复用订单展示规则（OrderStatusText、CreditPeriodText、OrderDraftTexts，R1-14 / R1-15）
    api(project(":core:orders"))
    // 口令形态复用 FU-CLIP 的 TpwdExtractor（G23，不另写正则）
    implementation(project(":core:clipboard"))

    // AgentVectorTest 读 specs/agent-vectors.json 与 15 个 fixture（JUnit4 由 convention 插件给；kotlinx-serialization-json 经 core:network 传递）
    testImplementation(libs.kotlinx.coroutines.test)
}
