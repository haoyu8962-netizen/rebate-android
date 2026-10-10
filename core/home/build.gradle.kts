// core:home —— 首页 SDUI 的纯逻辑（APP-11；01 F-HOME-01…09、TECH-10 / 11 / 12；03 §6 全节；BR-TEXT-13 / 17、BR-INV-23、BR-ID-11 / 33）：
// - 手写解析 HomeJson（两段解码：sections 先解成通用 JSON 数组，再逐项按 type 解成 8 种强类型卡片；未知 type 静默跳过、单卡隔离、条目级坏数据只丢条目）；
// - 判定（三端同一套向量 specs/home-sdui-vectors.json）：HomeSectionFilter（min_app_version 只比「不低于」）、HomeSourcePolicy（三级兜底）、
//   HomeLkgScope / HomeLocalData（LKG 分区与清理事件）、NoticeFilter（公告生效区间与关闭记录键）、PopupPolicy（弹窗决策）、RouteTargetCheck（动作白名单）、
//   HomeSpm、HomeAdLabel、ExposureTracker（只做判定、不发埋点）、CardErrorDedupe（sdui_card_error 去重）；HomePreviewPolicy 只在 debug / staging（src/debugFeatures）；
// - 本机存储：首页 LKG（HomeLkgStore，noBackupFilesDir/couli_home_lkg，原子写，待清理标记在目录外）、公告关闭记录与弹窗计数（HomePrefsStore，couli_home_prefs）；
// - 待定 operation 的端口 HomeContractPort（getPage、listSectionItems，G02 / G03：只由 debug / staging 的本机假服务实现，Release 为 null）；
// - 文案草稿表 HomeDraftTexts（TODO(N-35)）。首页与 HomePreview（app 的调试源码集）共用这些规则：feature 之间不能互相依赖，所以放在 Core。
plugins {
    alias(libs.plugins.couli.android.library)
}

android {
    namespace = "com.couli.core.home"
}

dependencies {
    // ApiResult、PageApi、CallOwnership、ResultSignals；DefaultTexts、Routes 与 kotlinx-serialization-json 经它传递
    api(project(":core:network"))
    // RouteTarget 交给 Router（RouteRequest / RouteSource）
    api(project(":core:router"))
    // 版本比较复用 UpdateGate.compareOrNull（G09）；AtomicTextFile（LKG 原子写）
    api(project(":core:config"))
    // 42901 禁用期与错误分类复用 APP-09 抽出的共用实现（WalletRateGate / SendProbe / WalletErrors）
    api(project(":core:wallet"))
    // 时间文案（CouliFormat，BR-TEXT-11）、商品卡映射（ProductCardMapper）
    api(project(":core:designsystem"))

    // HomeVectorTest 读 specs/home-sdui-vectors.json（JUnit4 由 convention 插件给；kotlinx-serialization-json 经 core:network 传递）；本机存储测试用 Robolectric
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
