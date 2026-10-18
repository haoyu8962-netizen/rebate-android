package com.couli.core.clipboard

import android.os.Handler
import android.os.Looper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 「打开 App 自动识别剪贴板」的本机状态（BR-ID-16 新版，couli #124；FU-AND-0722）：设备级，登录、退出、换账号都不改。
 * [enabled]：null = 未选择（默认关，G01）、true = 开、false = 关。[consentVersion]：点【开启】时的 config.clipboard.consent_version（字符串，
 * "02" 与 "2" 不同）；[consentedAtMillis]：点击时刻。[noticeSeenVersion]：看过首次用途弹层时的版本（版本一变就失效）。[deniedAtMillis]：Android
 * 不生成，只留字段。
 */
data class ImportPreferenceSnapshot(
    val enabled: Boolean?,
    val consentVersion: String?,
    val consentedAtMillis: Long?,
    val noticeSeenVersion: String?,
    val deniedAtMillis: Long?,
)

/** 设备级功能同意记录（POST /v1/consents，type=clipboard_auto_import；以当前设备为主体，不参与 login_merge）。 */
data class ImportConsentRecord(
    val type: String,
    val version: String,
    val acceptedAtMillis: Long,
    val subject: String = ImportPreferenceStore.SUBJECT_DEVICE,
)

/**
 * 同意上报端口（G03）：0722 只接持久的本机待上报队列，不发请求；契约补上 clipboard_auto_import 与字符串版本后，再由上报器按 BR-ID-12 取出发送、
 * 失败重试直至成功（G26：撤回隐私同意时队列不清）。
 */
interface ImportConsentQueue {
    /** 入队并返回是否确认落盘；没落盘时记录仍算本机意图（读得到），并自动重试落盘（G35）。 */
    fun enqueue(record: ImportConsentRecord): Boolean

    fun pending(): List<ImportConsentRecord>
}

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

    /** 存储外部（撤回清理 ClipboardPreferences.clearHashes）已确认删掉了这些键：对应的未落盘意图作废，免得之后的重试把它们写回来。 */
    fun forget(keys: Collection<String>) {
        keys.forEach(intended::remove)
    }
}

/**
 * 剪贴板自动识别的本机开关与功能同意（BR-ID-16 细则「首次用途说明弹层」「功能同意版本与隐私政策版本分开记录」；decision G01/G03/G20/G26/G35/G47）：
 * - [enable]：首次或版本失效后写当次版本、点击时刻、开、看过，并把设备级同意记录入待上报队列（同一次提交）；版本仍有效时（关 → 开）只改开关，
 *   保留原同意时刻、不重复入队（G20）。写盘失败：回滚为点击前的状态（不许读、不入队）并返回 false，不自动重试开启，由用户再点（G35）；
 * - [markNoticeSeen]：【暂不开启】或返回键关闭弹层（G27），只记看过，不把未选择改成关、不入队；
 * - [setOff]：只停用本机开关，不写撤回记录（G20）。写盘失败：立即按关闭生效（不许读）、返回 false，并自动重试落盘（G35）；
 * - [clearOnPrivacyWithdraw]：撤回隐私同意时清本机开关、功能同意与看过记录；待上报队列保留并继续补报（G26）。写盘失败同样立即生效并重试；
 * - 入队（[consentQueue]）写盘失败：记录留在本机意图里并自动重试（G35）。
 * 判定一律按当前版本（[isAutoReadAllowed] / [hasSeenNotice]）：版本一变，同意与看过都失效，开关原值保留（R05）。只在主线程使用。
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
    private val clock: () -> Long,
    private val scheduleWriteRetry: ((() -> Unit) -> Unit)? = null,
    private val writes: ImportPreferenceWrites = ImportPreferenceWrites(),
    private val scheduleDelayedWriteRetry: (Long, () -> Unit) -> Unit = ::postWriteRetryOnMainLooper,
) {
    val consentQueue: ImportConsentQueue =
        object : ImportConsentQueue {
            override fun enqueue(record: ImportConsentRecord): Boolean =
                commitOrRetry(mapOf(KEY_PENDING_CONSENTS to encode(pending() + record)))

            override fun pending(): List<ImportConsentRecord> = decode(read(KEY_PENDING_CONSENTS))
        }

    /** 保守生效后的本机状态：还没落盘的关闭 / 清除也按已生效算（G35）。 */
    fun snapshot(): ImportPreferenceSnapshot =
        ImportPreferenceSnapshot(
            enabled =
                when (read(KEY_ENABLED)) {
                    VALUE_ON -> true
                    VALUE_OFF -> false
                    else -> null
                },
            consentVersion = read(KEY_CONSENT_VERSION),
            consentedAtMillis = read(KEY_CONSENTED_AT)?.toLongOrNull(),
            noticeSeenVersion = read(KEY_NOTICE_SEEN_VERSION),
            deniedAtMillis = read(KEY_DENIED_AT)?.toLongOrNull(),
        )

    /**
     * 当前版本 [currentConsentVersion] 下允许自动读：开关开、且功能同意版本就是当前版本。当前版本缺失（配置没下发 consent_version）一律不许读，
     * 本机已有任何版本的同意都不算数（G47，BR-ID-16「没有 consent_version……门槛②按不满足处理」）。
     */
    fun isAutoReadAllowed(currentConsentVersion: String?): Boolean =
        currentConsentVersion != null && read(KEY_ENABLED) == VALUE_ON && hasValidConsent(currentConsentVersion)

    /** 功能同意版本仍有效（不看开关）：设置页关 → 开据此直接打开、不再弹层（G20）。 */
    fun hasValidConsent(currentConsentVersion: String): Boolean = read(KEY_CONSENT_VERSION) == currentConsentVersion

    fun hasSeenNotice(currentConsentVersion: String): Boolean = read(KEY_NOTICE_SEEN_VERSION) == currentConsentVersion

    /** 打开开关；返回是否确认落盘。失败时已回滚，不许读、不入队，也不自动重试（G35：由用户再点）。 */
    fun enable(v: String): Boolean {
        val changes =
            if (hasValidConsent(v)) {
                mapOf(KEY_ENABLED to VALUE_ON, KEY_NOTICE_SEEN_VERSION to v)
            } else {
                val at = clock()
                val record = ImportConsentRecord(type = CONSENT_TYPE, version = v, acceptedAtMillis = at)
                mapOf(
                    KEY_ENABLED to VALUE_ON,
                    KEY_CONSENT_VERSION to v,
                    KEY_CONSENTED_AT to at.toString(),
                    KEY_NOTICE_SEEN_VERSION to v,
                    KEY_PENDING_CONSENTS to encode(consentQueue.pending() + record),
                )
            }
        val before = changes.keys.associateWith(::read)
        if (commit(changes)) return true
        // 回滚：SharedPreferences 提交失败时缓存可能已是新值，下一次成功提交会把它整份写盘——立即把点击前的值写回缓存；这次也没落盘就记成本机
        // 意图，随之后任何一次成功提交一并写入。不排重试：磁盘上本来就没有这次开启
        if (!commit(before)) writes.intended.putAll(before)
        return false
    }

    /**
     * 配置缺 consent_version 时设置页打开开关（G47）：只把本机开关记成开，不写功能同意、不记看过、不入队；返回是否确认落盘。门槛②仍不满足，
     * 不许读；版本下发后按正常流程出用途弹层。写盘失败与 [enable] 一样回滚、不自动重试（G35）。
     */
    fun switchOnWithoutConsent(): Boolean {
        val changes = mapOf(KEY_ENABLED to VALUE_ON)
        val before = changes.keys.associateWith(::read)
        if (commit(changes)) return true
        if (!commit(before)) writes.intended.putAll(before)
        return false
    }

    fun markNoticeSeen(v: String): Boolean = commitOrRetry(mapOf(KEY_NOTICE_SEEN_VERSION to v))

    /** 关闭开关；返回是否确认落盘。失败时内存里已按关闭生效（不许读），并自动重试落盘（G35）。 */
    fun setOff(): Boolean = commitOrRetry(mapOf(KEY_ENABLED to VALUE_OFF))

    /** 撤回隐私同意（R06）：清本机状态（不清待上报队列，G26）；返回是否确认落盘，失败时内存里已清（不许读）并自动重试。 */
    fun clearOnPrivacyWithdraw(): Boolean = commitOrRetry(LOCAL_STATE_KEYS.associateWith { null })

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
        }
        return ok
    }

    /** 关闭、清除、入队、看过：失败时按已生效记进本机意图，并排一次重试。 */
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
        const val CONSENT_TYPE = "clipboard_auto_import"
        const val SUBJECT_DEVICE = "device"

        const val KEY_ENABLED = "import.enabled"
        const val KEY_CONSENT_VERSION = "import.consent_version"
        const val KEY_CONSENTED_AT = "import.consented_at"
        const val KEY_NOTICE_SEEN_VERSION = "import.notice_seen_version"
        const val KEY_DENIED_AT = "import.denied_at"
        const val KEY_PENDING_CONSENTS = "import.pending_consents"

        /** 撤回隐私同意时要清的本机状态键（不含待上报队列）。 */
        val LOCAL_STATE_KEYS: List<String> =
            listOf(KEY_ENABLED, KEY_CONSENT_VERSION, KEY_CONSENTED_AT, KEY_NOTICE_SEEN_VERSION, KEY_DENIED_AT)

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
        private const val FIELD_TYPE = "type"
        private const val FIELD_VERSION = "version"
        private const val FIELD_AT = "accepted_at_ms"
        private const val FIELD_SUBJECT = "subject"

        /**
         * 当前功能同意版本：config data 的 clipboard.consent_version；缺键（或整节缺失、为 null、为空串）返回 null——不用包内常量兜底（G47 推翻 G04）。
         */
        fun currentConsentVersion(configData: JsonObject?): String? =
            ((configData?.get("clipboard") as? JsonObject)?.get("consent_version") as? JsonPrimitive)
                ?.takeUnless { it is JsonNull }
                ?.content
                ?.takeIf(String::isNotEmpty)

        private fun encode(records: List<ImportConsentRecord>): String =
            JsonArray(
                records.map {
                    buildJsonObject {
                        put(FIELD_TYPE, it.type)
                        put(FIELD_VERSION, it.version)
                        put(FIELD_AT, it.acceptedAtMillis)
                        put(FIELD_SUBJECT, it.subject)
                    }
                },
            ).toString()

        /** 读不出（缺键、内容损坏）按空队列；损坏的单条跳过。 */
        private fun decode(raw: String?): List<ImportConsentRecord> {
            val array =
                raw?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
                    ?: return emptyList()
            return array.mapNotNull(::record)
        }

        private fun record(element: JsonElement): ImportConsentRecord? {
            val obj = element as? JsonObject

            fun text(name: String): String? = (obj?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
            val type = text(FIELD_TYPE)
            val version = text(FIELD_VERSION)
            val at = (obj?.get(FIELD_AT) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            return if (type != null && version != null && at != null) {
                ImportConsentRecord(type, version, at, text(FIELD_SUBJECT) ?: SUBJECT_DEVICE)
            } else {
                null
            }
        }
    }
}

/** 默认重试调度：主线程 Looper 按退避延时执行；用到时才取 Looper（构造 store 不碰，纯 JVM 单测可构造）。 */
private fun postWriteRetryOnMainLooper(
    delayMillis: Long,
    task: () -> Unit,
) {
    Handler(Looper.getMainLooper()).postDelayed(task, delayMillis)
}
