package com.couli.core.clipboard

import android.os.Handler
import android.os.Looper

/**
 * 「打开 App 自动识别剪贴板」的本机状态（BR-ID-16 2026-10-30 版；设备级，登录、退出、换账号、撤回隐私同意都不改）。
 * [enabled]：null = 从未选择（按开启）、true = 开、false = 关。
 */
data class ImportPreferenceSnapshot(
    val enabled: Boolean?,
)

/** [ImportPreferenceStore] 的键值存储端口（生产为 SharedPreferences，App 给）：值一律存字符串，[write] 一次提交多项（null = 删除）。 */
interface ImportPreferenceBackend {
    fun read(key: String): String?

    /**
     * 一次提交多项并返回是否确认落盘（生产为同步 commit() 的返回值）。返回 false 时实现的进程内缓存可能已经是新值（SharedPreferences 就是这样，
     * 下一次成功提交会把整份缓存写盘），也可能没变；[ImportPreferenceStore] 两种都按「没落盘」处理。
     */
    fun write(changes: Map<String, String?>): Boolean
}

/**
 * 还没确认落盘的本机意图（G35）：同一份存储的所有 [ImportPreferenceStore] 视图共用一个（App 的 ClipboardPreferences 持有），这样任何一个视图
 * 读到的都是「保守生效后」的状态，排队的重试也只有一份、执行时只写此刻最新的意图（用户之后的操作直接改这里，旧意图随之作废）。
 * 只在主线程使用。
 */
class ImportPreferenceWrites {
    /** 键 → 应有的值（null = 应删除）；每次成功提交都把这里的内容一并写入，然后清空。 */
    internal val intended = LinkedHashMap<String, String?>()
    internal var retryArmed = false
    internal var failedRetries = 0

    /**
     * 升级迁移（C6）的提交还没落盘：本次运行一律不自动读（[ImportPreferenceStore.canAutoRead] 为 false），但不改用户的开关值——这是运行期的临时阻断，
     * 不是用户关闭。之后任何一次成功提交（重试、回前台补写、用户操作）都会把迁移一并写盘，阻断随之解除，按原选择生效。
     */
    internal var migrationPending = false
}

/**
 * 剪贴板自动识别的本机开关（BR-ID-16 2026-10-30 版，默认开启；decision G20 / G35 / G42）：
 * - [isAutoSwitchOn]：没有明确关闭就是开（从未选择 = 开，C1）；
 * - [setOn]：写开，不写任何同意记录（C3）。写盘失败：回滚为点击前的状态并返回 false，不自动重试，由用户再点（G35）；
 * - [setOff]：只停用本机开关，不写撤回记录（G20）。写盘失败：立即按关闭生效（不许读）、返回 false，并自动重试落盘（G35）；
 * - [clearOnPrivacyWithdraw]：撤回隐私同意时补写还没落盘的意图，不清开关（C7：用户的关闭要留到重新同意之后）；
 * - [migrate]：升级迁移的一次提交（C6，判定在 App 的 ClipboardPreferences）；没落盘前 [canAutoRead] 为 false（运行期阻断，不改开关值）。
 * 只在主线程使用。
 *
 * 重试不设放弃上限（G42）：进程存活期间一直重试到落盘为止，间隔按连续失败次数指数退避（[retryDelayMillis]：从 [WRITE_RETRY_DELAY_MS] 起翻倍，
 * 封顶 [MAX_WRITE_RETRY_DELAY_MS]，不加随机抖动）；App 每次回前台另经 [flushPendingWrites] 立即补试一次。任何一次成功提交都会把还没落盘的意图
 * 一并写入（[writes]），重试与补试都只写此刻最新的意图（G35）。
 *
 * 调度：给了 [scheduleWriteRetry]（旧签名，测试夹具用）就由它决定何时执行、忽略退避延时；否则走 [scheduleDelayedWriteRetry]（默认主线程 Looper
 * 按退避延时执行；构造时不碰 Looper）。
 */
@Suppress("TooManyFunctions") // 每个本机动作一个入口，另有读盘与重试的私有步骤
class ImportPreferenceStore(
    private val backend: ImportPreferenceBackend,
    @Suppress("UnusedPrivateProperty") clock: () -> Long, // 保留签名：开关不再记时刻，调用方沿用注入点
    private val scheduleWriteRetry: ((() -> Unit) -> Unit)? = null,
    private val writes: ImportPreferenceWrites = ImportPreferenceWrites(),
    private val scheduleDelayedWriteRetry: (Long, () -> Unit) -> Unit = ::postWriteRetryOnMainLooper,
) {
    /** 保守生效后的本机状态：还没落盘的关闭 / 清除也按已生效算（G35）。 */
    fun snapshot(): ImportPreferenceSnapshot =
        ImportPreferenceSnapshot(
            enabled =
                when (read(KEY_ENABLED)) {
                    VALUE_ON -> true
                    VALUE_OFF -> false
                    else -> null
                },
        )

    /** 开关此刻是否为开：只有明确关闭才算关（未选择 = 开，C1）。这是用户的选择，设置页按它显示。 */
    fun isAutoSwitchOn(): Boolean = read(KEY_ENABLED) != VALUE_OFF

    /** 自动任务的本机门槛：开关开，且没有还没落盘的升级迁移（迁移提交失败时本次按关，C6；不写成用户关闭）。 */
    fun canAutoRead(): Boolean = isAutoSwitchOn() && !writes.migrationPending

    /** 打开开关（设置页直接生效，C8）；返回是否确认落盘。失败时已回滚，不自动重试（G35：由用户再点）。 */
    fun setOn(): Boolean {
        val changes = mapOf(KEY_ENABLED to VALUE_ON)
        val before = changes.keys.associateWith(::read)
        if (commit(changes)) return true
        // 回滚：SharedPreferences 提交失败时缓存可能已是新值，下一次成功提交会把它整份写盘——立即把点击前的值写回缓存；这次也没落盘就记成本机
        // 意图，随之后任何一次成功提交一并写入。不排重试：磁盘上本来就没有这次开启
        if (!commit(before)) writes.intended.putAll(before)
        return false
    }

    /** 关闭开关；返回是否确认落盘。失败时内存里已按关闭生效（不许读），并自动重试落盘（G35）。 */
    fun setOff(): Boolean = commitOrRetry(mapOf(KEY_ENABLED to VALUE_OFF))

    /**
     * 撤回隐私同意（C7）：开关值不动（用户的关闭要留到重新同意之后），只补写一次还没落盘的意图。名字沿用旧入口；返回此刻是否已无未落盘的意图。
     */
    fun clearOnPrivacyWithdraw(): Boolean = flushPendingWrites()

    /**
     * 升级迁移的一次提交（C6）：删掉 [retiredKeys]，[userOff] 时同一次提交写下关闭。失败时按已生效记进本机意图并自动重试；落盘之前不论迁移目标是开还是关，
     * 本次运行都不自动读（[ImportPreferenceWrites.migrationPending]），目标为开时也不把这次阻断写成关闭。下次启动旧键还在就重新迁移。返回是否确认落盘。
     */
    fun migrate(
        retiredKeys: Collection<String>,
        userOff: Boolean,
    ): Boolean {
        val changes = retiredKeys.associateWith<String, String?> { null }
        writes.migrationPending = true
        return commitOrRetry(if (userOff) changes + (KEY_ENABLED to VALUE_OFF) else changes)
    }

    /**
     * 立即补试一次还没落盘的意图（G42：App 每次回前台调用）：只写此刻最新的意图；没有就什么都不做。成功则清空意图、退避归零，已排队的重试到点后
     * 发现无事可做；失败不另排任务，原有退避重试照常进行。返回此刻是否已无未落盘的意图。
     */
    fun flushPendingWrites(): Boolean = writes.intended.isEmpty() || commit(emptyMap())

    private fun read(key: String): String? = if (key in writes.intended) writes.intended[key] else backend.read(key)

    /** 把还没落盘的意图与这次改动一起提交；成功则清空意图。 */
    private fun commit(changes: Map<String, String?>): Boolean {
        val ok = backend.write(writes.intended + changes)
        if (ok) {
            writes.intended.clear()
            writes.failedRetries = 0
            writes.migrationPending = false
        }
        return ok
    }

    /** 关闭、迁移：失败时按已生效记进本机意图，并排一次重试。 */
    private fun commitOrRetry(changes: Map<String, String?>): Boolean {
        if (commit(changes)) return true
        writes.intended.putAll(changes)
        armRetry()
        return false
    }

    private fun armRetry() {
        if (writes.retryArmed) return
        writes.retryArmed = true
        val immediate = scheduleWriteRetry
        if (immediate != null) {
            immediate(::retryWrites)
        } else {
            scheduleDelayedWriteRetry(retryDelayMillis(writes.failedRetries), ::retryWrites)
        }
    }

    /** 执行时只写此刻的意图（用户之后的操作已改写或清空它）；已被别的成功提交带走就什么都不做。 */
    private fun retryWrites() {
        writes.retryArmed = false
        if (writes.intended.isEmpty()) return
        if (commit(emptyMap())) return
        // 不设放弃上限（G42）：一直重试到落盘；计数只用于退避，封顶后不再增长
        if (writes.failedRetries < MAX_BACKOFF_STEPS) writes.failedRetries += 1
        armRetry()
    }

    companion object {
        const val KEY_ENABLED = "import.enabled"

        /** 写盘重试：首次延时与退避封顶（G42：间隔封顶 5 分钟，不设次数 / 时间放弃上限）。 */
        const val WRITE_RETRY_DELAY_MS = 2_000L
        const val MAX_WRITE_RETRY_DELAY_MS = 300_000L

        /** 退避翻倍的次数上限（2 秒 × 2^8 已超过封顶；只防移位溢出，不是放弃上限）。 */
        private const val MAX_BACKOFF_STEPS = 8

        /** 连续失败 [failures] 次后的下一次重试延时：2 秒起翻倍，封顶 5 分钟，不加抖动。 */
        fun retryDelayMillis(failures: Int): Long =
            (WRITE_RETRY_DELAY_MS shl failures.coerceIn(0, MAX_BACKOFF_STEPS)).coerceAtMost(MAX_WRITE_RETRY_DELAY_MS)

        private const val VALUE_ON = "on"
        private const val VALUE_OFF = "off"
    }
}

/** 默认重试调度：主线程 Looper 按退避延时执行；用到时才取 Looper（构造 store 不碰，纯 JVM 单测可构造）。 */
private fun postWriteRetryOnMainLooper(
    delayMillis: Long,
    task: () -> Unit,
) {
    Handler(Looper.getMainLooper()).postDelayed(task, delayMillis)
}
