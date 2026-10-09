package com.couli.core.update

/**
 * 强更检查的纯逻辑与持久化（03 §4.1「强更」、§4.4；BR-ID-01 细则「最低支持版本的接口层拦截」「【去更新】打开哪家商店」）。
 * 三端同名（APP-02c 任务书 §2.1）；向量 specs/update-vectors.json。时间一律毫秒。
 *
 * - 冷启动必查；回前台距上次**成功**检查严格超过 recheck_interval_sec 才查（失败与发起请求不重置）；本进程还没有成功检查，
 *   或最近一次检查失败时，下次回前台再试（03 §4.1「失败后下次回前台再试」，不受间隔限制）。
 * - 已知强更（落盘的 below_min）在冷启动直接 below_min（不等），之后只有新的成功检查能解除（03 §4.4「保留已知低于」）。
 * - 缓存的「不低于」（ok / below_recommended）在冷启动不作数（03 §4.4；向量 cached_ok_must_recheck_and_failure_is_unknown、
 *   cached_below_recommended_cold_failure_is_unknown）：本进程取得成功结果之前，检查进行中为 checking，没有在查（失败、
 *   基本模式不发检查）为 unknown。同一进程里成功过之后，失败保持本进程的已知结论（失败不拦用户自己的操作）。
 *   「暂不更新」记录不随之失效（向量 dismissal_survives_cold_start_failure）。
 * - 10405（[on10405]）直接进 below_min，不算一次成功检查；之后的成功检查可以解除。
 * - 服务端已判定强更（[onServerForced]）不按缓存最低版本比较，落盘保留到成功检查或本机升级（APP-07 共性第 24 条）。
 * - 本机版本升级后（rule-gaps G15，编排者裁定）：落盘的 below_min（成功检查或 10405 带来的）按新的 appVersion 与缓存的
 *   最低版本重比，已不低于就不再是已知强更（冷启动按上一条：checking → 失败为 unknown）；10405 没带最低版本时，
 *   appVersion 与记下 10405 时的版本不同即失效。
 * - 「暂不更新」按提示时的 latest_version 记录（03 §4.1）：同一目标版本不再提示，更高版本再提示（rule-gaps G02）。
 * - 可关闭提示只看来源商店（读不到来源时看默认商店），且该店上架版本须达到 recommended_version（BR-ID-01）。
 *
 * 等待 5 秒、外部来源排队、只读放行属于入口守卫与启动排队器（rule-gaps G06），不在这里。
 * 非线程安全：在主线程使用。
 *
 * @param appVersion 本机版本（逐段整数，如 1.0.0；构建类型后缀由调用方去掉）。非法版本串一律 unknown。
 * @param platform、[channel] X-Platform / X-Channel（android / official 等），只用于请求与日志，不参与判定。
 * @param installSource 已核对并归一化的安装来源商店键（读不到或不在商店对照表里为 null，rule-gaps G03）。
 */
@Suppress("TooManyFunctions") // 三端同一 API（任务书 §2.1）加少量私有判定，不拆类
class UpdateGate(
    private val store: UpdateStore,
    private val clock: () -> Long,
    val appVersion: String,
    val platform: String,
    val channel: String,
    val installSource: String?,
) {
    /** 本进程里发起了检查、结果还没回来。 */
    private var inFlight = false

    /** 本进程（本个 Gate）已经收到过一次成功的检查结果。冷启动重建 Gate 时为 false：缓存的「不低于」不作数。 */
    private var succeededThisProcess = false

    /** 本进程最近一次检查失败（下次回前台再试，不看间隔）。 */
    private var lastAttemptFailed = false

    /** 冷启动：必查。 */
    @Suppress("UnusedParameter") // 三端同一 API；冷启动不看时刻
    fun onColdStart(now: Long = clock()): Boolean {
        inFlight = true
        return true
    }

    /** 回前台：本进程还没成功过、最近一次失败，或距上次成功检查严格超过复查间隔才查。 */
    fun onForeground(now: Long = clock()): Boolean {
        val persisted = store.load()
        val last = persisted.lastSuccessAtMillis
        val intervalMillis = (persisted.result?.recheckIntervalSec ?: DEFAULT_RECHECK_INTERVAL_SEC) * MILLIS_PER_SECOND
        val due = last == null || !succeededThisProcess || lastAttemptFailed || now - last > intervalMillis
        if (due) inFlight = true
        return due
    }

    /** 一次成功检查：覆盖上次结果（含 10405 带来的强更），记下成功时刻。 */
    fun apply(
        result: UpdateCheckResult,
        now: Long = clock(),
    ) {
        val persisted = store.load()
        store.save(
            persisted.copy(
                result = result,
                lastSuccessAtMillis = now,
                forcedBy10405 = false,
                forcedMinimum = null,
                forcedAppVersion = null,
                forcedByServer = false,
            ),
        )
        inFlight = false
        succeededThisProcess = true
        lastAttemptFailed = false
    }

    /**
     * 检查失败：已知强更保持 below_min；本进程成功过则保持本进程的结论；否则 unknown（缓存的「不低于」不作数）。
     * 不重置复查计时。
     */
    @Suppress("UnusedParameter") // 三端同一 API；失败不改任何时刻
    fun applyFailure(now: Long = clock()) {
        inFlight = false
        lastAttemptFailed = true
    }

    /**
     * 接口层收到 10405（BR-ID-01）：直接进 below_min。调用方（APP-03 网络层）只把确需强更的 10405 送进来
     * （受限令牌、本机已受支持而 min 为空的情况先刷新令牌，rule-gaps G07）；防御：带回的 min 合法且本机不低于它时不改结论。
     */
    @Suppress("UnusedParameter") // 三端同一 API；不算成功检查，不改复查计时
    fun on10405(
        minSupported: String?,
        now: Long = clock(),
    ) {
        if (minSupported != null && compareOrNull(appVersion, minSupported)?.let { it >= 0 } == true) return
        val persisted = store.load()
        store.save(
            persisted.copy(
                forcedBy10405 = true,
                forcedMinimum = minSupported ?: persisted.forcedMinimum,
                forcedAppVersion = appVersion,
            ),
        )
    }

    /**
     * 服务端已判定强更（APP-07 共性第 21、24 条：parse 外层成功、结果行 error_code=10405；登录 / 刷新拿到受限会话；网络层整体
     * 10405 没带最低版本）：强制进 below_min，不交给「按缓存最低版本比较」——缓存的最低版本可能不高于本机，会把这次结论放掉。
     * 落盘，冷启动检查失败仍保留；只对收到时的 appVersion 成立（本机升级后失效，G15）；之后一次成功检查（[apply]）解除。
     * 与鸿蒙 UpdateGate.onServerForced 同义。
     */
    @Suppress("UnusedParameter") // 三端同一 API；不算成功检查，不改复查计时
    fun onServerForced(now: Long = clock()) {
        val persisted = store.load()
        store.save(persisted.copy(forcedBy10405 = true, forcedByServer = true, forcedAppVersion = appVersion))
    }

    /** 当前结论。 */
    @Suppress("UnusedParameter") // 三端同一 API；结论只依赖已落盘的结果与本进程的检查进度
    fun state(now: Long = clock()): UpdateState {
        val persisted = store.load()
        val known = knownState(persisted)
        return when {
            known?.status == UpdateStatus.BELOW_MIN -> known

            // 本进程还没有成功结果：缓存的「不低于」不作数（03 §4.4）
            !succeededThisProcess -> if (inFlight) CHECKING else UNKNOWN

            else -> known ?: UNKNOWN
        }
    }

    /** 「暂不更新」：只在可关闭提示（below_recommended）时记录目标版本；强更没有「暂不更新」。 */
    fun dismissPrompt(now: Long = clock()) {
        val current = state(now)
        val target = current.targetVersion
        if (current.status != UpdateStatus.BELOW_RECOMMENDED || target == null) return
        store.save(store.load().copy(dismissedLatest = target))
    }

    /**
     * 【去更新】选店（BR-ID-01 细则）：
     * - 强更（below_min）：候选顺序 安装来源 → default_store → stores 其余（下发顺序），同一家只看一次；
     *   逐家核对已上架版本 ≥ 最低支持版本且本机能打开；
     * - 可关闭提示（below_recommended）：只看安装来源（读不到时看 default_store），已上架版本 ≥ recommended_version
     *   且能打开，否则 unavailable，不改去别家；
     * - 其余状态没有可去的商店：unavailable。
     * 上架版本未知（listed_version 为 null，适配器已丢弃）或非法的商店不可选。[canOpen] 每家最多调一次，只对版本合格的调。
     */
    @Suppress("ReturnCount") // 逐家核对：先排除没有可去商店的状态，再逐家返回第一家
    fun pickStore(
        result: UpdateCheckResult,
        now: Long = clock(),
        canOpen: (storeKey: String) -> Boolean,
    ): StoreChoice {
        val current = state(now)
        val (candidates, target) =
            when (current.status) {
                UpdateStatus.BELOW_MIN -> {
                    listOfNotNull(installSource, result.defaultStore) + result.stores.map { it.store } to current.targetVersion
                }

                UpdateStatus.BELOW_RECOMMENDED -> {
                    listOfNotNull(installSource ?: result.defaultStore) to result.recommended
                }

                else -> {
                    return UNAVAILABLE
                }
            }
        for (key in candidates.distinct()) {
            if (listingMeets(result, key, target) && canOpen(key)) return StoreChoice(storeKey = key, unavailable = false)
        }
        return UNAVAILABLE
    }

    /** 已上架版本达到 [target]（[target] 为 null 时须高于本机版本）。 */
    @Suppress("ReturnCount") // 版本未知、非法均不可选，各自提前返回
    private fun listingMeets(
        result: UpdateCheckResult,
        key: String,
        target: String?,
    ): Boolean {
        val listed = result.stores.firstOrNull { it.store == key }?.version ?: return false
        val bar = target ?: appVersion
        val compared = compareOrNull(listed, bar) ?: return false
        return if (target == null) compared > 0 else compared >= 0
    }

    /** 不看检查进度的已知结论；什么都不知道为 null。 */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // 按 03 §4.1 的判定顺序逐条提前返回，拆开反而难对照规则
    private fun knownState(persisted: UpdatePersisted): UpdateState? {
        if (parseVersion(appVersion) == null) {
            // TODO(N-14): 本机版本串非法时的处理规则未定（rule-gaps G05），按 unknown。
            return UNKNOWN
        }
        val result = persisted.result
        if (persisted.forcedBy10405) {
            val minimum = highest(persisted.forcedMinimum, result?.minSupported)
            if (persisted.forcedByServer && persisted.forcedAppVersion == appVersion) {
                // 服务端已判定强更：不按缓存最低版本比较。目标版本只取高于本机的最低版本，否则为 null（选店要求商店版本高于本机）
                return belowMin(minimum?.takeIf { compareOrNull(appVersion, it)?.let { c -> c < 0 } == true })
            }
            if (stillBelow10405Minimum(persisted, minimum)) return belowMin(minimum)
        }
        if (result == null) return null
        val belowMinimum = result.minSupported?.let { compareOrNull(appVersion, it) ?: return UNKNOWN }?.let { it < 0 } == true
        if (belowMinimum) return belowMin(result.minSupported)
        val belowRecommended = result.recommended?.let { compareOrNull(appVersion, it) ?: return UNKNOWN }?.let { it < 0 } == true
        if (!belowRecommended) return OK
        val target = result.latest ?: result.recommended
        val dismissed = persisted.dismissedLatest
        val notDismissed = dismissed == null || (compareOrNull(target, dismissed) ?: 1) > 0
        val showPrompt = notDismissed && promptStoreReady(result)
        return UpdateState(UpdateStatus.BELOW_RECOMMENDED.raw, showPrompt = showPrompt, targetVersion = target)
    }

    /**
     * 10405 带来的强更对当前 appVersion 是否仍成立（R1-04 / rule-gaps G15）：有最低版本时按当前版本重比（最低版本非法时
     * 保守地仍按强更）；没有最低版本时只对记下 10405 时的那个 appVersion 成立（升级后失效，旧数据没记版本的按仍成立）。
     */
    private fun stillBelow10405Minimum(
        persisted: UpdatePersisted,
        minimum: String?,
    ): Boolean =
        if (minimum != null) {
            compareOrNull(appVersion, minimum)?.let { it < 0 } ?: true
        } else {
            persisted.forcedAppVersion == null || persisted.forcedAppVersion == appVersion
        }

    /** 可关闭提示只看来源商店（读不到来源时看默认商店）：已上架版本须达到 recommended_version（BR-ID-01）。 */
    private fun promptStoreReady(result: UpdateCheckResult): Boolean {
        val key = installSource ?: result.defaultStore ?: return false
        return listingMeets(result, key, result.recommended)
    }

    private fun belowMin(target: String?): UpdateState = UpdateState(UpdateStatus.BELOW_MIN.raw, showPrompt = false, targetVersion = target)

    private fun highest(
        a: String?,
        b: String?,
    ): String? =
        when {
            a == null -> b
            b == null -> a
            (compareOrNull(a, b) ?: 0) >= 0 -> a
            else -> b
        }

    companion object {
        /** 回前台复查间隔的包内默认（BR-ID-01 细则；/v1/config.app_update.recheck_interval_sec 下发前用它）。 */
        const val DEFAULT_RECHECK_INTERVAL_SEC = 1800

        private const val MILLIS_PER_SECOND = 1000L

        private val CHECKING = UpdateState(UpdateStatus.CHECKING.raw, showPrompt = false, targetVersion = null)
        private val UNKNOWN = UpdateState(UpdateStatus.UNKNOWN.raw, showPrompt = false, targetVersion = null)
        private val OK = UpdateState(UpdateStatus.OK.raw, showPrompt = false, targetVersion = null)
        private val UNAVAILABLE = StoreChoice(storeKey = null, unavailable = true)

        private val SEGMENT = Regex("[0-9]+")

        /**
         * 逐段整数比较（1.0.0 < 1.0.10；前导零不影响，01.002 = 1.2）；段数不同时缺的段按 0 补。返回 -1 / 0 / 1。
         * 非法版本串（空段、非数字）抛 [IllegalArgumentException]：TODO(N-14) 非法输入的表达方式三端未定（rule-gaps G05），
         * Gate 内部用 [compareOrNull] 把它当作 unknown。
         */
        fun compareVersions(
            a: String,
            b: String,
        ): Int = requireNotNull(compareOrNull(a, b)) { "非法版本号：$a / $b" }

        /** 同 [compareVersions]，任一非法返回 null。 */
        @Suppress("ReturnCount") // 两侧非法、首个不同段各自提前返回
        fun compareOrNull(
            a: String,
            b: String,
        ): Int? {
            val left = parseVersion(a) ?: return null
            val right = parseVersion(b) ?: return null
            for (index in 0 until maxOf(left.size, right.size)) {
                val compared = compareSegment(left.getOrElse(index) { "0" }, right.getOrElse(index) { "0" })
                if (compared != 0) return compared
            }
            return 0
        }

        /** 拆段并去掉前导零；非法返回 null。不转成整数，任意长的段也不会溢出。 */
        private fun parseVersion(version: String): List<String>? {
            val parts = version.split('.')
            if (parts.any { !SEGMENT.matches(it) }) return null
            return parts.map { it.trimStart('0').ifEmpty { "0" } }
        }

        private fun compareSegment(
            a: String,
            b: String,
        ): Int =
            when {
                a.length != b.length -> a.length.compareTo(b.length).coerceIn(-1, 1)
                else -> a.compareTo(b).coerceIn(-1, 1)
            }
    }
}
