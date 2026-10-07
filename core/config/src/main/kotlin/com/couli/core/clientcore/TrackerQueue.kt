package com.couli.core.clientcore

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 周期调度（Tracker 每 10 秒上报，APP-03b R1-08）：返回的句柄 close 即停止。可注入，便于单测。 */
fun interface PeriodicScheduler {
    fun every(
        intervalMillis: Long,
        action: () -> Unit,
    ): AutoCloseable
}

/** 生产的周期调度：单个后台守护线程，固定间隔（上一次结束后再等一个间隔）。 */
class ExecutorPeriodicScheduler(
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "couli-tracker-flush").apply { isDaemon = true } },
) : PeriodicScheduler {
    override fun every(
        intervalMillis: Long,
        action: () -> Unit,
    ): AutoCloseable {
        val future = executor.scheduleWithFixedDelay({ runCatching(action) }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS)
        return AutoCloseable { future.cancel(false) }
    }
}

/**
 * 一个上报批次的归属（APP-03b R2-05）：取出批次时绑定隐私世代、清理世代（[TrackerQueue.clear]，切换环境）与外部的隐私 / 环境世代
 * （App 接 InFlightCalls）。撤回同意、切换环境时作废并取消：[onCancel] 登记的动作（如取消网络请求）立即执行。
 * 真正发出数据的 sink 在自己的发送守卫里还要核对 [isValid]。
 *
 * 交接（R3-01）：批次在队列锁里经 [accept] 登记到 ticket（只登记、不做 IO），与撤回 / clear 的作废在同一把锁里串行；作废时登记的批次
 * 随之丢弃。真正发送前经 [takeForSend] 核对归属并取走批次，作废之后取不到，sink 收不到撤回前的数据。
 */
class UploadTicket internal constructor(
    internal val outer: Any?,
    internal val privacyEpoch: Long,
    internal val clearEpoch: Long,
    private val check: (UploadTicket) -> Boolean,
) {
    private val cancelLock = Any()
    private var cancelled = false
    private val cancellers = mutableListOf<() -> Unit>()

    /** 已交接、还没发送的批次；作废时丢弃。 */
    private var pending: List<String>? = null

    /** 归属仍有效（没有被撤回、切换环境作废，仍已同意）。 */
    fun isValid(): Boolean = !isCancelled() && check(this)

    internal fun isCancelled(): Boolean = synchronized(cancelLock) { cancelled }

    /** 登记取消动作；已取消的立即执行。 */
    fun onCancel(action: () -> Unit) {
        synchronized(cancelLock) {
            if (!cancelled) {
                cancellers += action
                return
            }
        }
        runCatching(action)
    }

    /** 在队列锁里调用（归属检查刚通过）：登记要交给 sink 的批次，不做 IO。 */
    internal fun accept(events: List<String>) {
        synchronized(cancelLock) { if (!cancelled) pending = events }
    }

    /** 发送前（锁外）：归属仍有效才取走登记的批次（只能取一次）；已作废返回 null。 */
    internal fun takeForSend(): List<String>? {
        if (!isValid()) return null
        return synchronized(cancelLock) { if (cancelled) null else pending.also { pending = null } }
    }

    /** 标记已取消（在队列锁里调用）；丢弃已交接未发送的批次，返回要在锁外执行的取消动作。 */
    internal fun markCancelled(): List<() -> Unit> =
        synchronized(cancelLock) {
            if (cancelled) return emptyList()
            cancelled = true
            pending = null
            cancellers.toList().also { cancellers.clear() }
        }
}

/** 上报 sink：[ticket] 是这一批的归属，真正发出前要核对 [UploadTicket.isValid]，可用 [UploadTicket.onCancel] 登记取消。 */
fun interface TrackerSink {
    fun upload(
        events: List<String>,
        ticket: UploadTicket,
    ): Boolean

    companion object {
        /** 只收事件的 sink（三端测试接线约定）：调用 [sink] 之前核对 ticket，撤回、切换环境之后不再交出（R3-01）。 */
        fun of(sink: (List<String>) -> Boolean): TrackerSink = TrackerSink { events, ticket -> ticket.isValid() && sink(events) }
    }
}

/**
 * 埋点队列（03 §4.7；BR-ID-11）：采集 → 本地持久化队列 → 批量交给可注入的上报 [sink]。
 *
 * - 采集（[track]）：只在已同意（consented）时接受；基本模式、未同意一律不采集、不上报（BR-ID-11）。补齐公共字段：event_id、name、
 *   client_at（ISO 8601 带时区）、session_id、device_id、user_id（游客不带）、platform、app_version、channel、page、spm、trace_id。
 *   已采集的事件不回写（游客事件登录后不补 user_id，由服务端关联）。还没有 device_id（设备未注册）时不采集：device_id 是必填公共字段。
 * - 持久化：先写队列文件，再改内存；写入失败不接受这条事件。离线队列最多 [CAPACITY] 条，超出丢最旧的
 *   （TODO(N-21)：丢旧还是丢新未定，rule-gaps 第 5 条）。
 * - 上报：每 [BATCH_SIZE] 条触发一次；另经 [startPeriodicFlush] 每 [FLUSH_INTERVAL_MILLIS] 调一次 [flush]（R1-08）。定时器只在已同意
 *   时运行，撤回同意、进入基本模式、清空（切换环境）时停止，重新同意时再启动；每次触发仍走 [flush] 的同一套状态检查（隐私、强更结论、
 *   在途上报的作废世代）。强更检查还没有结果（[canUpload] 为 false）时只入队、不上报。
 *   sink 返回 true 表示整批成功，才从队列删除；失败的留在队列里下次再试（TODO(N-21)：确认协议未定）。
 * - 撤回同意、进入基本模式（[onPrivacyChanged]）：立即清空内存与文件里的队列，之后不再采集、不再上报；重建对象再同意也不会复活
 *   撤回前的事件。以未同意状态构造时不读旧队列，并删除残留的文件。
 * - 清空失败（删不掉队列文件，lessons 11）：记下「待清空」，之后采集、上报、重新同意之前都先重试删除，确认删掉之前不采集、不上报、
 *   不读回旧队列。持久的待清理标记是撤回同意的待清理标记（PrivacyGate，清理完成前隐私状态不是 consented，重启后不读旧队列）；
 *   切换环境的清理失败时环境闸门保持阻断。
 *
 * - **批次归属**（R2-05）：每批取出时绑定 [UploadTicket]（隐私世代、清理世代、外部的隐私 / 环境世代 [epochs]）。交给 sink 之前、
 *   写回结果之前都在队列锁（与 [clear]、[onPrivacyChanged] 共用）里核对；撤回同意、切换环境时作废在途批次并执行它登记的取消动作。
 *   最后一次核对与交接是原子的（R3-01）：核对通过即在同一个同步区里把批次登记到 ticket（[UploadTicket.accept]），锁外发送前再经
 *   [UploadTicket.takeForSend] 取出；撤回、clear 作废 ticket 时登记的批次随之丢弃。已取出的批次在撤回之后不会再交给 sink。
 *
 * 线程安全；sink 在锁外调用。
 */
@Suppress("LongParameterList", "TooManyFunctions") // 公共字段的来源逐项注入（三端测试接线约定）
class TrackerQueue(
    private val readFile: () -> String?,
    private val writeFile: (String?) -> Unit,
    private val sink: TrackerSink,
    privacy: String,
    private val userId: () -> String?,
    private val deviceId: () -> String?,
    private val platform: String,
    private val appVersion: String,
    private val sessionId: () -> String,
    private val clock: () -> Long,
    private val canUpload: () -> Boolean,
    private val channel: () -> String? = { null },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** 外部的隐私 / 环境世代（App 接 InFlightCalls.epochs）：批次开始后变了即作废。 */
    private val epochs: () -> Any? = { null },
) {
    /** 三端测试接线约定的构造：device_id、session_id 为固定值。 */
    constructor(
        readFile: () -> String?,
        writeFile: (String?) -> Unit,
        sink: (List<String>) -> Boolean,
        privacy: String,
        userId: () -> String?,
        deviceId: String,
        platform: String,
        appVersion: String,
        sessionId: String,
        clock: () -> Long,
        canUpload: () -> Boolean,
    ) : this(
        readFile,
        writeFile,
        TrackerSink.of(sink),
        privacy,
        userId,
        { deviceId },
        platform,
        appVersion,
        { sessionId },
        clock,
        canUpload,
    )

    private val lock = Any()
    private var privacy: String = privacy
    private var queue: List<Queued> = emptyList()

    /** 撤回同意、进入基本模式加 1。 */
    private var privacyEpoch = 0L

    /** [clear]（切换环境、撤回清理）加 1。 */
    private var clearEpoch = 0L
    private var uploading = false

    /** 正在上报的批次（撤回、切换环境时取消）。 */
    private var active: UploadTicket? = null

    /** 测试用的时序钩子：批次取出之后、交给 sink 之前（锁外）。 */
    internal var afterBatchTaken: () -> Unit = {}

    /** 测试用的时序钩子：最后一次归属核对通过、批次已登记到 ticket 之后，发送之前（锁外）。 */
    internal var afterHandOver: () -> Unit = {}

    /** 清空时文件没删掉：确认删掉之前不采集、不上报（lessons 11）。 */
    private var clearPending = false

    private var scheduler: PeriodicScheduler? = null
    private var timer: AutoCloseable? = null

    /** [id] 即 event_id（服务端按它去重）。 */
    private class Queued(
        val id: String,
        val json: String,
    )

    init {
        synchronized(lock) {
            if (privacy == PrivacyStateRaw.CONSENTED) {
                queue =
                    runCatching { readFile() }
                        .getOrNull()
                        ?.let(::decode)
                        .orEmpty()
                        .takeLast(CAPACITY)
            } else {
                // 未同意：不读旧队列；残留文件（上次撤回没删掉）尽力删除，删不掉记下待清空
                clearPending = runCatching { writeFile(null) }.isFailure
            }
        }
    }

    /** 队列里的事件数。 */
    val queueSize: Int get() = synchronized(lock) { queue.size }

    /** 采集一个事件（只能来自 [ClientEvents] 的生成函数）。返回是否接受。 */
    @Suppress("ReturnCount") // 未同意、没有 device_id、写不进逐项不接受
    fun track(event: ClientEvent): Boolean {
        val due: Boolean
        synchronized(lock) {
            if (privacy != PrivacyStateRaw.CONSENTED || !settledLocked()) return false
            val device = deviceId()?.takeIf { it.isNotEmpty() } ?: return false
            val eventId = newId()
            val item = Queued(eventId, encodeEvent(event, device, eventId))
            val next = (queue + item).takeLast(CAPACITY)
            if (!persist(next)) return false
            queue = next
            due = queue.size >= BATCH_SIZE
        }
        if (due) flush()
        return true
    }

    /**
     * 上报队列里的事件（按批，直到队列空或某批失败）。未同意、强更检查还没有结果时不上报。每批取出时绑定归属（R2-05），交给 sink
     * 前、写回结果前都在队列锁里核对；已作废的批次不交给 sink、不写回。
     */
    @Suppress("ReturnCount")
    fun flush() {
        while (true) {
            val batch: List<Queued>
            val ticket: UploadTicket
            synchronized(lock) {
                if (uploading || !uploadAllowedLocked()) return
                uploading = true
                batch = queue.take(BATCH_SIZE)
                ticket = UploadTicket(epochs(), privacyEpoch, clearEpoch) { synchronized(lock) { ownsLocked(it) } }
                active = ticket
            }
            val succeeded =
                try {
                    afterBatchTaken()
                    // 归属核对与交接在同一个同步区（与撤回、clear 共用）：核对通过即把批次登记到 ticket，不做 IO
                    val accepted =
                        synchronized(lock) {
                            (ownsLocked(ticket) && canUpload()).also { if (it) ticket.accept(batch.map { item -> item.json }) }
                        }
                    if (accepted) afterHandOver()
                    // 锁外发送：ticket 是可取消的守卫，作废之后取不到批次，不交给 sink
                    val events = if (accepted) ticket.takeForSend() else null
                    events != null && runCatching { sink.upload(events, ticket) }.getOrDefault(false)
                } finally {
                    synchronized(lock) {
                        uploading = false
                        if (active === ticket) active = null
                    }
                }
            synchronized(lock) {
                if (!succeeded || !ownsLocked(ticket)) return
                val sent = batch.map { it.id }.toSet()
                val next = queue.filterNot { it.id in sent }
                // 先写文件再改内存；写不进时内存照样删掉（重启后重发的由服务端按 event_id 去重）
                persist(next)
                queue = next
            }
        }
    }

    /** 在 [lock] 下：批次归属仍有效（世代都没变、没取消）且仍已同意。 */
    private fun ownsLocked(ticket: UploadTicket): Boolean =
        privacy == PrivacyStateRaw.CONSENTED &&
            !ticket.isCancelled() &&
            ticket.privacyEpoch == privacyEpoch &&
            ticket.clearEpoch == clearEpoch &&
            runCatching { epochs() }.getOrNull() == ticket.outer

    private fun uploadAllowedLocked(): Boolean =
        privacy == PrivacyStateRaw.CONSENTED && settledLocked() && queue.isNotEmpty() && canUpload()

    /** 没有待清空的旧队列；有就先重试删除（删掉才算）。 */
    private fun settledLocked(): Boolean {
        if (!clearPending) return true
        clearPending = runCatching { writeFile(null) }.isFailure
        return !clearPending
    }

    /**
     * 开始每 [FLUSH_INTERVAL_MILLIS] 上报一次（App 启动时调一次）。定时器只在已同意时运行：现在已同意就启动，之后随 [onPrivacyChanged]
     * 启停；[clear] 不停（切换环境后仍已同意，队列为空时触发什么都不做）。
     */
    fun startPeriodicFlush(scheduler: PeriodicScheduler) {
        synchronized(lock) {
            this.scheduler = scheduler
            syncTimerLocked()
        }
    }

    private fun syncTimerLocked() {
        val wanted = privacy == PrivacyStateRaw.CONSENTED
        if (wanted && timer == null) {
            timer = scheduler?.every(FLUSH_INTERVAL_MILLIS) { flush() }
        } else if (!wanted) {
            timer?.let { runCatching { it.close() } }
            timer = null
        }
    }

    /** 隐私状态变化：不是已同意时立即清空队列（内存与文件），作废并取消在途批次，之后不采集、不上报；定时上报随之启停。 */
    fun onPrivacyChanged(state: String) {
        var cancel: List<() -> Unit> = emptyList()
        try {
            synchronized(lock) {
                privacy = state
                syncTimerLocked()
                if (state == PrivacyStateRaw.CONSENTED) {
                    settledLocked()
                    return
                }
                privacyEpoch += 1
                cancel = cancelActiveLocked()
                clearLocked()
            }
        } finally {
            cancel.forEach { runCatching(it) }
        }
    }

    /**
     * 清空队列（撤回同意、切换环境）。先在内存里清空并作废、取消在途上报，再删文件；删除失败记下待清空并把错误抛给清理入口（撤回清理
     * 保持待完成、下次重试），内存里已经是空的。
     */
    fun clear() {
        var cancel: List<() -> Unit> = emptyList()
        try {
            synchronized(lock) {
                clearEpoch += 1
                cancel = cancelActiveLocked()
                clearLocked()
            }
        } finally {
            cancel.forEach { runCatching(it) }
        }
    }

    private fun cancelActiveLocked(): List<() -> Unit> {
        val ticket = active ?: return emptyList()
        active = null
        return ticket.markCancelled()
    }

    private fun clearLocked() {
        queue = emptyList()
        clearPending = true
        writeFile(null)
        clearPending = false
    }

    private fun persist(next: List<Queued>): Boolean = runCatching { writeFile(if (next.isEmpty()) null else encode(next)) }.isSuccess

    private fun encodeEvent(
        event: ClientEvent,
        device: String,
        eventId: String,
    ): String {
        val fields = linkedMapOf<String, JsonElement>()
        fields["event_id"] = JsonPrimitive(eventId)
        fields["name"] = JsonPrimitive(event.name)
        fields["client_at"] = JsonPrimitive(ISO.format(Instant.ofEpochMilli(clock()).atOffset(ZoneOffset.UTC)))
        fields["session_id"] = JsonPrimitive(sessionId())
        fields["device_id"] = JsonPrimitive(device)
        userId()?.takeIf { it.isNotEmpty() }?.let { fields["user_id"] = JsonPrimitive(it) }
        fields["platform"] = JsonPrimitive(platform)
        fields["app_version"] = JsonPrimitive(appVersion)
        channel()?.takeIf { it.isNotEmpty() }?.let { fields["channel"] = JsonPrimitive(it) }
        fields["page"] = JsonPrimitive(event.page)
        event.spm?.let { fields["spm"] = JsonPrimitive(it) }
        event.traceId?.let { fields["trace_id"] = JsonPrimitive(it) }
        event.fields.forEach { (key, value) -> if (key !in fields) fields[key] = value }
        return JsonObject(fields).toString()
    }

    private fun encode(items: List<Queued>): String =
        JsonObject(
            mapOf(
                KEY_FORMAT to JsonPrimitive(FORMAT),
                KEY_EVENTS to
                    JsonArray(items.map { JsonObject(mapOf(KEY_ID to JsonPrimitive(it.id), KEY_EVENT to JsonPrimitive(it.json))) }),
            ),
        ).toString()

    private fun decode(text: String): List<Queued>? =
        runCatching {
            val root = Json.parseToJsonElement(text) as JsonObject
            require((root[KEY_FORMAT] as JsonPrimitive).content == FORMAT.toString())
            (root[KEY_EVENTS] as JsonArray).map { item ->
                val obj = item as JsonObject
                Queued((obj[KEY_ID] as JsonPrimitive).content, (obj[KEY_EVENT] as JsonPrimitive).content)
            }
        }.getOrNull()

    companion object {
        /** 离线缓存上限（03 §4.7）。 */
        const val CAPACITY = 500

        /** 每 10 条触发一次上报（03 §4.7）。 */
        const val BATCH_SIZE = 10

        /** 定时上报间隔（03 §4.7：每 10 条或 10 秒）。 */
        const val FLUSH_INTERVAL_MILLIS = 10_000L

        private const val FORMAT = 1
        private const val KEY_FORMAT = "format"
        private const val KEY_EVENTS = "events"
        private const val KEY_ID = "id"
        private const val KEY_EVENT = "event"
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
