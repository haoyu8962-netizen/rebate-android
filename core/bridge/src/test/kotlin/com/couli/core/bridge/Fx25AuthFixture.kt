package com.couli.core.bridge

import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** 存储路径：P = 每次切号换新配置文件；F = 不支持多配置文件时先拆后清默认配置文件（产品 [ClearingTrustedWebStore]）。 */
enum class Fx25StoreMode { PROFILE, CLEARING }

/**
 * v3（D10-309 / D10-310 方案）：只模拟文件 I/O、配置文件端口、逐类清理回调与页面（WebView）生命周期，不在测试侧判身份、排队、
 * 登记新页或解除隔离。F 路径用真实产品 [ClearingTrustedWebStore]（宽限期、逐类完成信号），只把逐类清理换成可注入的 [Fx25Cleaner]。
 * 每次 rotate / 清理请求入口都断言「全部旧页已拆」，每次 rebuild 入口都断言「标记已删、归属已写、存储已换、无旧页存活」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions", "LongParameterList") // 事务各步逐项一个推进入口；端口逐个注入，便于「重启」复用同一份持久对象
internal class Fx25AuthFixture(
    val scope: TestScope,
    val journal: Fx25Journal,
    val owner: Fx25OwnerRecord,
    val mode: Fx25StoreMode = Fx25StoreMode.PROFILE,
    val profiles: Fx25ProfileStore = Fx25ProfileStore(journal.trace),
    seedOwner: Boolean = true,
    ownerUnreadableAtStart: Boolean = false,
) : AutoCloseable {
    val trace = journal.trace
    val cleaner = Fx25Cleaner(trace)
    val metrics = mutableListOf<String>()
    val pages = mutableListOf<Fx25Document>()
    private val clearing = ClearingTrustedWebStore(scope, cleaner, TIMEOUT_MS)
    val store: TrustedWebStore = if (mode == Fx25StoreMode.PROFILE) profiles else clearing

    /** 测试侧记下的「此刻会话身份」，只用于 rebuild 入口核对归属记录，不参与产品判定。 */
    var identity: WebAuthIdentity = A
        private set

    /** 已发生过账号变化 / 明确清除：此后每次 rebuild 前 F 路径四类必须已确认清除。 */
    var transaction = false
        private set

    val sync: TrustedAuthSync

    init {
        if (seedOwner && owner.peek() == null) owner.write(WebDataOwner(A.account))
        owner.failRead = ownerUnreadableAtStart
        profiles.beforeRotate = { assertAllRetired("rotate") }
        cleaner.beforeClear = { assertAllRetired("clear") }
        sync = TrustedAuthSync(scope, journal, owner, store, TIMEOUT_MS) { metrics += it }
    }

    /** 新开一个可信页：登记后未隔离即视为它自己加载了入口（在当前存储上、令牌已交付）；隔离中则不加载，等协调器 rebuild。 */
    fun page(name: String = "foreground"): Fx25Document =
        Fx25Document(name, trace, ::beforeRebuild).also {
            pages += it
            it.registration = sync.attach(it)
            if (!it.isolated) it.selfLoad(store.current())
        }

    /** 首次确定身份且与归属记录一致：无事件、无拆、无换存储、无隔离。 */
    fun baseline(next: WebAuthIdentity = A) {
        val rotations = profiles.rotations.size + cleaner.requests.size
        identity = next
        sync.observe(next)
        scope.runCurrent()
        rebuildAll()
        assertTrue("首次确定身份无事件", pages.all { it.events.isEmpty() })
        assertTrue("与归属一致的冷启动不拆", pages.all { it.retires.isEmpty() })
        assertEquals("与归属一致的冷启动不换存储", rotations, profiles.rotations.size + cleaner.requests.size)
        assertTrue("与归属一致不隔离", pages.none { it.isolated })
    }

    fun change(next: WebAuthIdentity = B) {
        if (next.known && next.account != identity.account) transaction = true
        if (next.known) identity = next
        sync.observe(next)
    }

    fun cleared(reason: String) {
        transaction = true
        identity = WebAuthIdentity(true, null, identity.loginEpoch + 1)
        sync.sessionCleared(reason)
    }

    fun acknowledgeEvents(success: Boolean = true) {
        scope.runCurrent()
        pages.forEach { page -> page.eventCompletions.filter { !it.completed }.forEach { it.complete(success) } }
        scope.runCurrent()
    }

    /** 完成全部在途的拆除；返回是否真的完成了至少一个。 */
    fun retireAll(success: Boolean = true): Boolean {
        scope.runCurrent()
        val pending = pages.flatMap { page -> page.retires.filter { !it.completed } }
        pending.forEach { it.complete(success) }
        scope.runCurrent()
        return pending.isNotEmpty()
    }

    /** 完成在途的换存储：P 结算配置文件端口；F 先过宽限期再逐类回调（每次回调都可能发起下一类，不预设串行或并行）。 */
    fun rotate(success: Boolean = true) {
        scope.runCurrent()
        if (mode == Fx25StoreMode.PROFILE) {
            profiles.rotations.filter { !it.completed }.forEach { it.complete(success) }
        } else {
            scope.advanceTimeBy(ClearingTrustedWebStore.GRACE_MS + 1)
            scope.runCurrent()
            repeat(TrustedWebDataKind.entries.size + 1) {
                cleaner.requests
                    .filter { !it.completed }
                    .toList()
                    .forEach { cleaner.complete(it, success) }
                scope.runCurrent()
            }
        }
        scope.runCurrent()
    }

    fun rebuildAll(success: Boolean = true) {
        scope.runCurrent()
        pages.flatMap { page -> page.rebuilds.filter { !it.completion.completed } }.forEach { it.completion.complete(success) }
        scope.runCurrent()
    }

    /** 全部正常推进到结算：A 确认 → 拆 → 换存储 → 重建。上限内推进不完只能报错，不能假装成功。 */
    fun completeTransaction() {
        repeat(ROUNDS) {
            acknowledgeEvents()
            retireAll()
            rotate()
            rebuildAll()
        }
        assertTrue("事务已推进完：无在途拆除 / 重建", pages.none { p -> p.retires.any { !it.completed } || p.rebuilds.any { !it.completion.completed } })
    }

    fun settled(): Boolean? {
        var result: Boolean? = null
        sync.whenSettled { result = it }
        scope.runCurrent()
        return result
    }

    private fun assertAllRetired(step: String) {
        val alive = pages.filter { it.alive != null && !it.destroyed }.map { it.name }
        assertTrue("$step 之前全部可信页必须已拆并确认销毁，仍存活：$alive", alive.isEmpty())
    }

    private fun beforeRebuild(
        page: Fx25Document,
        storeId: String,
    ) {
        assertNull("rebuild 前待清标记已删", journal.peek())
        assertEquals("rebuild 前归属记录已写为当前身份", identity.account, owner.peek()?.account)
        assertEquals("rebuild 用当前活动存储", store.current(), storeId)
        val stale = pages.filter { it !== page && it.alive != null && it.alive != storeId && !it.destroyed }.map { it.name }
        assertTrue("新存储上建页时不能有旧存储上的页存活：$stale", stale.isEmpty())
        if (mode == Fx25StoreMode.CLEARING && transaction) {
            assertTrue("F 路径 rebuild 前四类均已确认清除", cleaner.values.values.all { it == null })
        }
        if (mode == Fx25StoreMode.PROFILE && transaction) {
            assertNull("P 路径新配置文件是空的", profiles.data[storeId])
        }
    }

    override fun close() = sync.close()

    companion object {
        const val TIMEOUT_MS = 3_000L
        private const val ROUNDS = 6
        val A = WebAuthIdentity(true, "user-a", 1L)
        val B = WebAuthIdentity(true, "user-b", 2L)
        val GUEST = WebAuthIdentity(true, null, 3L)
        val UNKNOWN = WebAuthIdentity(false, null, 0L)

        /** 超过协调器对任一步（含 F 路径整轮清理、rebuild）的等待上限。 */
        val BEYOND_ANY_STEP_MS =
            TIMEOUT_MS * (maxOf(TrustedAuthSync.ROTATE_TIMEOUT_FACTOR, TrustedAuthSync.REBUILD_TIMEOUT_FACTOR) + 1) +
                ClearingTrustedWebStore.GRACE_MS

        @Suppress("LongParameterList") // 同上：每个端口都可单独复用或注入故障
        fun create(
            scope: TestScope,
            dir: File,
            mode: Fx25StoreMode = Fx25StoreMode.PROFILE,
            profiles: Fx25ProfileStore? = null,
            seedOwner: Boolean = true,
            ownerUnreadableAtStart: Boolean = false,
        ): Fx25AuthFixture {
            val journal = Fx25Journal(File(dir, "pending.json"))
            return Fx25AuthFixture(
                scope,
                journal,
                Fx25OwnerRecord(File(dir, "owner.json"), journal.trace),
                mode,
                profiles ?: Fx25ProfileStore(journal.trace),
                seedOwner,
                ownerUnreadableAtStart,
            )
        }
    }
}

/** 新建端口对象仍读同一个文件；重启用例不复用调度器、页面或 journal 对象。 */
internal class Fx25Journal(
    private val file: File,
    val trace: MutableList<String> = mutableListOf(),
) : WebDataJournal {
    var failMark = false
    var failClear = false
    var failRead = false
    var beforeMark: () -> Unit = {}

    override fun readPending(): WebAuthIdentity? {
        if (failRead) throw IOException("injected journal read failure")
        return peek()
    }

    /** 测试侧观察（不受注入的读失败影响）。 */
    fun peek(): WebAuthIdentity? {
        if (!file.exists()) return null
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        return WebAuthIdentity(
            json.getValue("known").jsonPrimitive.boolean,
            json.getValue("account").jsonPrimitive.contentOrNull,
            json.getValue("epoch").jsonPrimitive.long,
        )
    }

    override fun markPending(owner: WebAuthIdentity): Boolean {
        beforeMark()
        trace += "journal:mark"
        if (failMark) return false
        file.writeText(
            buildJsonObject {
                put("known", owner.known)
                put("account", owner.account)
                put("epoch", owner.loginEpoch)
            }.toString(),
        )
        return true
    }

    override fun clearPending(): Boolean {
        trace += "journal:clear"
        if (failClear) return false
        return !file.exists() || file.delete()
    }
}

/** 「可信 Web 数据归属」的持久记录（只存账号，不存令牌）。读不出抛错；写失败返回 false。 */
internal class Fx25OwnerRecord(
    private val file: File,
    private val trace: MutableList<String>,
) : WebDataOwnerRecord {
    var failRead = false
    var failWrite = false

    override fun read(): WebDataOwner? {
        if (failRead) throw IOException("injected owner record read failure")
        return peek()
    }

    fun peek(): WebDataOwner? {
        if (!file.exists()) return null
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        return WebDataOwner(json.getValue("account").jsonPrimitive.contentOrNull)
    }

    override fun write(owner: WebDataOwner): Boolean {
        trace += "owner:write:${owner.account}"
        if (failWrite) return false
        file.writeText(buildJsonObject { put("account", owner.account) }.toString())
        return true
    }
}

/**
 * P 路径配置文件端口：rotate 的结果由测试结算；成功才换活动名（新配置文件为空）并把旧名放进待删。discardPending 删除不在用的待删名，
 * [inUse] 模拟 deleteProfile 抛「仍在使用」。同一个对象跨「重启」复用 = 产品把 {active, retired[]} 持久化。
 */
internal class Fx25ProfileStore(
    private val trace: MutableList<String>,
) : TrustedWebStore {
    var active = "couli_trusted_0"
        private set
    val data = linkedMapOf<String, String?>(active to "user-a")
    val retired = mutableListOf<String>()
    val rotations = mutableListOf<Fx25Completion>()
    val discards = mutableListOf<Boolean>()
    val inUse = mutableSetOf<String>()
    var throwing = false
    var beforeRotate: () -> Unit = {}
    private var next = 1

    override fun current(): String = active

    override fun rotate(completed: (Boolean) -> Unit) {
        beforeRotate()
        trace += "store:rotate"
        check(!throwing) { "injected getOrCreateProfile failure" }
        rotations +=
            Fx25Completion { ok ->
                if (ok) {
                    retired += active
                    active = "couli_trusted_${next++}"
                    data[active] = null
                    trace += "store:rotated:$active"
                }
                completed(ok)
            }
    }

    override fun discardPending(completed: (Boolean) -> Unit) {
        trace += "store:discard"
        val gone = retired.filter { it !in inUse }
        gone.forEach { data.remove(it) }
        retired.removeAll(gone)
        discards += retired.isEmpty()
        completed(retired.isEmpty())
    }
}

/** F 路径逐类清理：每类值和每类 callback 独立。 */
internal class Fx25Cleaner(
    private val trace: MutableList<String>,
) : TrustedWebDataCleaner {
    val values = TrustedWebDataKind.entries.associateWith { "user-a" as String? }.toMutableMap()
    val requests = mutableListOf<Request>()
    var throwing: TrustedWebDataKind? = null
    var beforeClear: () -> Unit = {}

    override fun clear(
        kind: TrustedWebDataKind,
        completed: (Boolean) -> Unit,
    ) {
        beforeClear()
        trace += "clear:start:$kind"
        if (kind == throwing) throw IOException("injected $kind failure")
        requests += Request(kind, completed)
    }

    fun complete(
        request: Request,
        success: Boolean,
    ) {
        assertFalse("同一 callback 不由正常夹具重复结算", request.completed)
        if (success) values[request.kind] = null
        trace += "clear:done:${request.kind}:$success"
        request.completed = true
        request.callback(success)
    }

    class Request(
        val kind: TrustedWebDataKind,
        val callback: (Boolean) -> Unit,
        var completed: Boolean = false,
    )
}

/**
 * 一个可信页面（跨多次 rebuild 的同一槽位）。[alive] = 当前文档所在存储（null：没有 WebView——从未加载、已拆或 rebuild 未成）。
 * retire 成功即旧 WebView 已销毁；rebuild 成功即新 WebView 在给定存储上、新文档令牌已绑定。
 */
internal class Fx25Document(
    val name: String,
    private val trace: MutableList<String>,
    private val beforeRebuild: (Fx25Document, String) -> Unit,
) : TrustedAuthDocument {
    override var currentTrusted = true
    override var tokenDelivered = false
    override var destroyed = false
    var isolated = false
    var alive: String? = null
        private set
    var registration: AutoCloseable? = null
    var releases = 0
        private set
    var throwOnRetire = false
    var throwOnRebuild = false
    val events = mutableListOf<JsonObject>()
    val eventCompletions = mutableListOf<Fx25Completion>()
    val retires = mutableListOf<Fx25Completion>()
    val rebuilds = mutableListOf<Fx25Rebuild>()

    fun selfLoad(store: String) {
        alive = store
        tokenDelivered = true
    }

    /** 测试侧关闭页面（出栈）：注销并销毁。 */
    fun closeByUser() {
        destroyed = true
        alive = null
        tokenDelivered = false
        registration?.close()
    }

    override fun isolate() {
        isolated = true
        trace += "$name:isolate"
    }

    override fun notifyAuthChanged(
        data: JsonObject,
        completed: (Boolean) -> Unit,
    ) {
        assertTrue("只向仍可信、已交付令牌、未销毁的存活文档发事件", currentTrusted && tokenDelivered && !destroyed && alive != null)
        events += data
        trace += "$name:event"
        eventCompletions += Fx25Completion(completed)
    }

    override fun retire(completed: (Boolean) -> Unit) {
        assertTrue("只在隔离状态下拆", isolated)
        trace += "$name:retire"
        check(!throwOnRetire) { "injected retire failure" }
        retires +=
            Fx25Completion { ok ->
                if (ok) {
                    alive = null
                    tokenDelivered = false
                    trace += "$name:retired"
                }
                completed(ok)
            }
    }

    override fun rebuild(
        store: String,
        completed: (Boolean) -> Unit,
    ) {
        assertTrue("只在隔离状态下重建", isolated)
        assertNull("rebuild 必须新建 WebView：该页旧 WebView 已拆（或从未加载）", alive)
        beforeRebuild(this, store)
        trace += "$name:rebuild:$store"
        check(!throwOnRebuild) { "injected setProfile / WebView creation failure" }
        rebuilds +=
            Fx25Rebuild(
                store,
                Fx25Completion { ok ->
                    if (ok) selfLoad(store)
                    completed(ok)
                },
            )
    }

    override fun releaseAfterRebuild() {
        assertTrue("不能在新文档完成前恢复", rebuilds.lastOrNull()?.completion?.let { it.completed && it.success } == true)
        assertTrue("解除时新文档存活", alive != null)
        isolated = false
        releases += 1
        trace += "$name:release"
    }
}

internal class Fx25Rebuild(
    val store: String,
    val completion: Fx25Completion,
)

internal class Fx25Completion(
    private val callback: (Boolean) -> Unit,
) {
    var completed = false
        private set
    var success = false
        private set

    fun complete(value: Boolean) {
        completed = true
        success = value
        callback(value)
    }
}
