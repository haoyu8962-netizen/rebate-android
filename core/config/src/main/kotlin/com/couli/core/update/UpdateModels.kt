package com.couli.core.update

/**
 * 一家商店的已知上架版本（GET /v1/app-versions/check 的 stores[]）。
 * 契约里 listed_version 可以是 null（不知道上架了哪个版本）：适配器丢弃这样的条目，不补 0 或 latest（rule-gaps G04）。
 */
data class UpdateStoreListing(
    val store: String,
    val version: String,
)

/**
 * 一次成功的版本检查（check.data 的投影，APP-02c 任务书 §2.1）。
 *
 * @property recheckIntervalSec 回前台复查间隔：契约在 /v1/config.app_update，不在 check 响应里（rule-gaps G01）；
 *   没有配置时用包内默认 [UpdateGate.DEFAULT_RECHECK_INTERVAL_SEC]（[UpdateConfigReader]，F-X-01）。
 * @property updateTitle、[updateNotes] 只给界面显示（强更页标题与更新内容、可关闭提示），不参与判定。
 */
data class UpdateCheckResult(
    val minSupported: String?,
    val recommended: String?,
    val latest: String?,
    val defaultStore: String?,
    val stores: List<UpdateStoreListing>,
    val recheckIntervalSec: Int = UpdateGate.DEFAULT_RECHECK_INTERVAL_SEC,
    val updateTitle: String? = null,
    val updateNotes: String? = null,
)

/**
 * 当前结论（任务书 §2.1）。
 * @property statusRaw checking | ok | below_recommended | below_min | unknown（[UpdateStatus] 的 raw）。
 * @property showPrompt 现在该弹可关闭更新提示（below_recommended、来源商店已上架到建议版本、没被「暂不更新」记过）。
 * @property targetVersion below_min 时是最低支持版本；below_recommended 时是提示时的 latest_version（「暂不更新」按它记录，
 *   rule-gaps G02）；其余为 null。
 */
data class UpdateState(
    val statusRaw: String,
    val showPrompt: Boolean,
    val targetVersion: String?,
) {
    val status: UpdateStatus get() = UpdateStatus.fromRaw(statusRaw)
}

/** [UpdateState.statusRaw] 的取值（03 §4.4「统一入口守卫」表的强更状态 + 检查中）。 */
enum class UpdateStatus(
    val raw: String,
) {
    CHECKING("checking"),
    OK("ok"),
    BELOW_RECOMMENDED("below_recommended"),
    BELOW_MIN("below_min"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromRaw(raw: String): UpdateStatus = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

/**
 * 【去更新】选店的结论。[storeKey] 为 null 时 [unavailable] 为 true：显示 app_update.unavailable 与【重试】，
 * 不给任何下载地址（BR-ID-01）。
 */
data class StoreChoice(
    val storeKey: String?,
    val unavailable: Boolean,
)

/** 一次版本检查的结果：成功（check.data）或失败（网络、解析、服务端错误一律算失败，不拦用户自己的操作）。 */
sealed interface UpdateCheckOutcome {
    data class Success(
        val result: UpdateCheckResult,
    ) : UpdateCheckOutcome

    data object Failure : UpdateCheckOutcome

    /**
     * 检查开始后归属已失效（撤回同意、进入基本模式、切换环境；FU-AND-0710 R1-02）：结果作废。调用方什么都不应用——不落盘、不改强更结论、
     * 不选店，只结束「检查中」。
     */
    data object Invalidated : UpdateCheckOutcome
}

/**
 * 版本检查器（GET /v1/app-versions/check）。debug / staging 用读契约示例的假实现（调试面板切换场景）；Release 用经 ApiClient 发请求的
 * 真实检查器（core:network ApiUpdateChecker，F-X-01）。失败一律按 unknown 处理，不会误拦用户。
 */
fun interface UpdateChecker {
    suspend fun check(): UpdateCheckOutcome
}
