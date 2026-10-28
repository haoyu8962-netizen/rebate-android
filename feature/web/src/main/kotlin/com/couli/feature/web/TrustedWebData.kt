package com.couli.feature.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.core.content.ContextCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import com.couli.core.bridge.ClearingTrustedWebStore
import com.couli.core.bridge.TrustedWebDataCleaner
import com.couli.core.bridge.TrustedWebDataKind
import com.couli.core.bridge.TrustedWebStore
import com.couli.core.bridge.WebAuthIdentity
import com.couli.core.bridge.WebDataJournal
import com.couli.core.bridge.WebDataOwner
import com.couli.core.bridge.WebDataOwnerRecord
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * App 私有、不备份目录（noBackupFilesDir）下的小文件持久写入（F-X-25 I3）：先写临时文件并 fsync，再原子改名，最后 fsync 目录
 * （掉电也不丢改名，评审 r1 S2-04）。任一步失败抛 [IOException]。
 */
internal object DurableFile {
    fun write(
        file: File,
        body: String,
    ) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(temporary).use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!temporary.renameTo(file) || !file.exists()) throw IOException("rename failed: ${file.name}")
            syncDirectory(file.parentFile)
        } catch (error: IOException) {
            temporary.delete()
            throw error
        }
    }

    /** 目录 fsync：Android 上以只读打开目录取 FileDescriptor 不可行时退化为不做（改名本身已原子）。 */
    @Suppress("SwallowedException") // 部分文件系统不支持目录 fsync：不影响改名的原子性
    private fun syncDirectory(directory: File?) {
        directory ?: return
        try {
            android.system.Os
                .open(directory.path, android.system.OsConstants.O_RDONLY, 0)
                .let { fd ->
                    try {
                        android.system.Os.fsync(fd)
                    } finally {
                        android.system.Os.close(fd)
                    }
                }
        } catch (error: android.system.ErrnoException) {
            // 目录不支持 fsync（极少数文件系统）：改名已原子，按已落盘处理
        }
    }

    /** 读文本；不存在为 null。 */
    fun read(file: File): String? = if (file.exists()) file.readText() else null
}

/**
 * 「可信 Web 数据待清理」的持久标记（F-X-25 I3）：noBackupFilesDir 下的一个小文件，内容只有旧身份的归属（known、account、本进程登录世代），
 * 不含任何令牌。
 * - [readPending]：文件不存在 = 确实没有待清理（null）；读不出、内容损坏抛 [IOException]（不能当成没有标记）；
 * - [markPending]：[DurableFile] 持久写入；任一步失败返回 false；
 * - [clearPending]：删除，确认文件已不存在才返回 true。
 */
class AndroidWebDataJournal(
    context: Context,
    name: String,
) : WebDataJournal {
    private val file = File(context.applicationContext.noBackupFilesDir, "$PREFIX$name.json")

    override fun readPending(): WebAuthIdentity? {
        val text = DurableFile.read(file) ?: return null
        return parse(text) ?: throw IOException("web data journal unreadable")
    }

    /** 内容损坏（JSON 解析失败、缺键、类型不对）为 null：调用方按读不出处理（有待清理）。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 解析库对各种损坏抛不同的运行时异常，一律按损坏
    private fun parse(text: String): WebAuthIdentity? =
        try {
            val json = Json.parseToJsonElement(text).jsonObject
            WebAuthIdentity(
                json.getValue(KNOWN).jsonPrimitive.boolean,
                json.getValue(ACCOUNT).jsonPrimitive.contentOrNull,
                json.getValue(EPOCH).jsonPrimitive.long,
            )
        } catch (error: RuntimeException) {
            null
        }

    @Suppress("SwallowedException") // 写入失败的结论就是返回 false（不是吞成成功）
    override fun markPending(owner: WebAuthIdentity): Boolean {
        val body =
            buildJsonObject {
                put(KNOWN, owner.known)
                put(ACCOUNT, owner.account)
                put(EPOCH, owner.loginEpoch)
            }.toString()
        return try {
            DurableFile.write(file, body)
            true
        } catch (error: IOException) {
            // 写不进按「没写成」返回 false：协调器保持隔离并在下一轮补写（I3）
            false
        }
    }

    override fun clearPending(): Boolean = !file.exists() || (file.delete() && !file.exists())

    private companion object {
        const val PREFIX = "couli_trusted_web_pending_"
        const val KNOWN = "known"
        const val ACCOUNT = "account"
        const val EPOCH = "epoch"
    }
}

/**
 * 「可信 Web 数据归属」的持久记录（S0-02 启动基线、S1-01 兜底）：noBackupFilesDir 下的小文件，只存账号（游客为 null）与持久化登录世代
 * 分区，不存令牌。不存在 = 首次安装（null）；损坏、读不出抛 [IOException]（协调器按不符处理）；写入经 [DurableFile]，失败返回 false。
 */
class AndroidWebDataOwnerRecord(
    context: Context,
    name: String,
) : WebDataOwnerRecord {
    private val file = File(context.applicationContext.noBackupFilesDir, "$PREFIX$name.json")

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 解析库对各种损坏抛不同的运行时异常，一律按读不出
    override fun read(): WebDataOwner? {
        val text = DurableFile.read(file) ?: return null
        return try {
            val json = Json.parseToJsonElement(text).jsonObject
            WebDataOwner(json.getValue(ACCOUNT).jsonPrimitive.contentOrNull, json[PARTITION]?.jsonPrimitive?.contentOrNull)
        } catch (error: RuntimeException) {
            throw IOException("web data owner record unreadable")
        }
    }

    @Suppress("SwallowedException") // 写入失败的结论就是返回 false
    override fun write(owner: WebDataOwner): Boolean =
        try {
            DurableFile.write(
                file,
                buildJsonObject {
                    put(ACCOUNT, owner.account)
                    put(PARTITION, owner.partition)
                }.toString(),
            )
            true
        } catch (error: IOException) {
            false
        }

    private companion object {
        const val PREFIX = "couli_trusted_web_owner_"
        const val ACCOUNT = "account"
        const val PARTITION = "partition"
    }
}

/**
 * F 路径（不支持多配置文件）与 P 路径首次启用时的一次性迁移所用的默认配置文件清理（F-X-25 I2、I6）。可信容器在 F 路径用 WebView 的
 * **默认配置文件**；ExternalWebView 固定用独立的 [ExternalWebViewController.PROFILE]（不支持多配置文件时外部容器不开），所以这里只调用
 * 默认配置文件的实例级接口，绝不调用跨配置文件的全局清理（I6）。只在全部可信 WebView 已销毁之后调用（旧文档已不在，不跑任何页面脚本；
 * sessionStorage 随 WebView 实例消失）。每类的成功都要有真实完成信号，拿不到就给 false：
 * - Cookie：CookieManager.removeAllCookies 的回调兑现、flush 后 hasCookies() 为 false；
 * - localStorage / HTTP 缓存：默认配置文件 deleteBrowsingData 的完成回调；
 * - IndexedDB：deleteBrowsingData 完成后，默认配置文件的 getOrigins 不再有占用。
 * 系统 WebView 不支持 DELETE_BROWSING_DATA 时除 Cookie 外一律失败（不退回 deleteAllData / clearCache）。只在主线程上执行。
 *
 * 配置文件整体删除每轮只做一次：[ClearingTrustedWebStore] 按 [TrustedWebDataKind] 顺序逐类调用、每轮从 Cookie 开始，Cookie 一类发起时
 * 同时新开一次删除，其余三类等这一次的完成回调后再做各自的核对（上一轮的删除不会被复用）。
 */
class AndroidTrustedWebDataCleaner(
    context: Context,
) : TrustedWebDataCleaner {
    private val main = Handler(Looper.getMainLooper())
    private val executor = ContextCompat.getMainExecutor(context.applicationContext)

    /** 本轮的配置文件整体删除（只在主线程读写）。 */
    private var wipe: Wipe? = null

    private class Wipe {
        var finished = false
        val waiters = mutableListOf<() -> Unit>()
    }

    override fun clear(
        kind: TrustedWebDataKind,
        completed: (Boolean) -> Unit,
    ) {
        val once = AtomicBoolean(false)
        val done = { ok: Boolean -> if (once.compareAndSet(false, true)) completed(ok) }
        if (Looper.myLooper() == Looper.getMainLooper()) start(kind, done) else main.post { start(kind, done) }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 系统 WebView 缺失、更新中：一律按这一类没清成
    private fun start(
        kind: TrustedWebDataKind,
        done: (Boolean) -> Unit,
    ) {
        try {
            when (kind) {
                TrustedWebDataKind.COOKIE -> {
                    startWipe()
                    clearCookies(done)
                }

                TrustedWebDataKind.LOCAL_STORAGE, TrustedWebDataKind.HTTP_CACHE -> {
                    wipeProfile(done) { done(true) }
                }

                TrustedWebDataKind.INDEXED_DB -> {
                    wipeProfile(done) { originsEmpty(done) }
                }
            }
        } catch (error: Exception) {
            done(false)
        }
    }

    private fun clearCookies(done: (Boolean) -> Unit) {
        val cookies = CookieManager.getInstance()
        // 回调的布尔值是「有没有删到」，不是成败：以回调兑现 + 落盘 + 默认配置文件里确实不再有 Cookie 为成功
        cookies.removeAllCookies {
            cookies.flush()
            done(!cookies.hasCookies())
        }
    }

    /** 新开一次默认配置文件的整体删除；不支持时为 null。 */
    private fun startWipe(): Wipe? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) return null
        val started = Wipe()
        wipe = started
        WebStorageCompat.deleteBrowsingData(WebStorage.getInstance(), executor) {
            started.finished = true
            started.waiters.toList().forEach { it() }
            started.waiters.clear()
        }
        return started
    }

    /** 等本轮的整体删除完成（还没有就新开一次），拿到完成回调后再做各类自己的核对。 */
    private fun wipeProfile(
        done: (Boolean) -> Unit,
        then: () -> Unit,
    ) {
        val current = wipe ?: startWipe() ?: return done(false)
        if (current.finished) guarded(done, then) else current.waiters += { guarded(done, then) }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 核对阶段抛错同样按失败
    private fun guarded(
        done: (Boolean) -> Unit,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (error: Exception) {
            done(false)
        }
    }

    private fun originsEmpty(done: (Boolean) -> Unit) {
        WebStorage.getInstance().getOrigins { origins ->
            done(origins.values.all { (it as? WebStorage.Origin)?.usage == 0L })
        }
    }
}

/**
 * 可信容器的存储（F-X-25 v2，D10-309 / D10-310）。路径在本安装第一次创建时判一次并写盘（[profiles]），之后不再调用
 * [profilesSupported]、不中途换路径（变异 21）：
 * - **P**（系统 WebView 支持 MULTI_PROFILE）：可信 WebView 一律建在活动配置文件 `couli_trusted_<随机16hex>` 上（经
 *   [TrustedWebViewFactory] 在首次使用前 setProfile）。[rotate] 先把新名与退役名持久写进状态文件，再 getOrCreateProfile（新配置文件为空）；
 *   [discardPending] 删除退役名单与 getAllProfileNames 里所有不是活动名的 `couli_trusted_*`——deleteProfile 抛「仍在使用」时保留、稍后
 *   再试几次，仍删不掉就返回 false 留待冷启动（D10-310 (5)）。首次启用 P 时还把默认配置文件里升级前的旧可信数据按 F 清一次，成功才记
 *   legacyCleared（D10-310 (3)）。外部容器的 [ExternalWebViewController.PROFILE] 从不在这里。
 * - **F**（不支持）：[current] 为 [ClearingTrustedWebStore.DEFAULT_STORE]；[rotate] = [ClearingTrustedWebStore]（2 s 宽限期 +
 *   [AndroidTrustedWebDataCleaner] 四类，每类 [stepTimeoutMillis]）；没有可删的退役存储。
 * ProfileStore 只在主线程调用（从别的线程进来的调用转到主线程）。
 */
@Suppress("TooManyFunctions") // 状态文件读写、两条路径的换 / 删、主线程转交逐项一个入口
class AndroidTrustedWebStore(
    context: Context,
    name: String,
    private val stepTimeoutMillis: Long,
    profilesSupported: () -> Boolean = { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) },
) : TrustedWebStore {
    private val app = context.applicationContext
    private val file = File(app.noBackupFilesDir, "$STATE_PREFIX$name.json")
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private var state: State

    /** 本安装的路径：true = P（每次切号换新配置文件），false = F（先拆后清默认配置文件）。 */
    val profiles: Boolean

    private val clearing by lazy { ClearingTrustedWebStore(scope, AndroidTrustedWebDataCleaner(app), stepTimeoutMillis) }

    /** 首次启用 P 的一次性迁移：没有宽限期（此时没有任何可信 WebView 用默认配置文件）。 */
    private val legacy by lazy { ClearingTrustedWebStore(scope, AndroidTrustedWebDataCleaner(app), stepTimeoutMillis, graceMillis = 0L) }

    private class State(
        val profiles: Boolean,
        val active: String,
        val retired: List<String>,
        val legacyCleared: Boolean,
    )

    init {
        val loaded = load()
        state =
            loaded ?: run {
                val supported = profilesSupported()
                State(supported, if (supported) newProfileName() else ClearingTrustedWebStore.DEFAULT_STORE, emptyList(), !supported)
                    .also { persistQuietly(it) }
            }
        profiles = state.profiles
    }

    override fun current(): String = synchronized(lock) { state.active }

    override fun rotate(completed: (Boolean) -> Unit) {
        if (!profiles) return clearing.rotate(completed)
        onMain { completed(rotateProfile()) }
    }

    override fun discardPending(completed: (Boolean) -> Unit) {
        if (!profiles) return completed(true)
        val once = OnceResult(completed)
        onMain {
            if (state.legacyCleared) {
                discardProfiles(DISCARD_ATTEMPTS) { gone -> once(gone) }
            } else {
                legacy.rotate { cleared ->
                    if (cleared) update { State(it.profiles, it.active, it.retired, legacyCleared = true) }
                    discardProfiles(DISCARD_ATTEMPTS) { gone -> once(gone && cleared) }
                }
            }
        }
    }

    /** P：新名与退役名先持久化，再建新配置文件（新建即空）。持久化或创建失败为 false。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // getOrCreateProfile 抛任何异常都按换存储失败（I2）
    private fun rotateProfile(): Boolean {
        val fresh = newProfileName()
        val previous = current()
        val next = State(true, fresh, (state.retired + previous).distinct(), state.legacyCleared)
        if (!persistQuietly(next)) return false
        synchronized(lock) { state = next }
        return try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().getOrCreateProfile(fresh).name == fresh
            } else {
                false
            }
        } catch (error: Exception) {
            false
        }
    }

    /** P：删退役名单 + 所有不是活动名的 couli_trusted_*；仍在使用的稍后再试，次数用完仍有残留为 false。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // deleteProfile 抛「仍在使用」或其他异常：保留，下次再删
    private fun discardProfiles(
        attempts: Int,
        completed: (Boolean) -> Unit,
    ) {
        val remaining =
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                try {
                    val store = ProfileStore.getInstance()
                    val active = current()
                    val candidates = (state.retired + store.allProfileNames.filter { it.startsWith(PROFILE_PREFIX) }).distinct() - active
                    candidates.filterNot { candidate ->
                        try {
                            store.deleteProfile(candidate)
                            true
                        } catch (error: IllegalStateException) {
                            false
                        }
                    }
                } catch (error: Exception) {
                    null
                }
            } else {
                null
            }
        if (remaining != null) update { State(it.profiles, it.active, remaining, it.legacyCleared) }
        when {
            remaining?.isEmpty() == true -> completed(true)
            attempts > 1 -> main.postDelayed({ discardProfiles(attempts - 1, completed) }, DISCARD_RETRY_MS)
            else -> completed(false)
        }
    }

    private fun update(change: (State) -> State) {
        val next = synchronized(lock) { change(state).also { state = it } }
        persistQuietly(next)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 状态文件读不出、损坏：按没有处理（P 下随后的残留清理会删掉孤儿配置文件）
    private fun load(): State? =
        try {
            DurableFile.read(file)?.let { text ->
                val json = Json.parseToJsonElement(text).jsonObject
                State(
                    json.getValue(PROFILES).jsonPrimitive.boolean,
                    json.getValue(ACTIVE).jsonPrimitive.content,
                    json.getValue(RETIRED).jsonArray.map { it.jsonPrimitive.content },
                    json.getValue(LEGACY).jsonPrimitive.boolean,
                )
            }
        } catch (error: Exception) {
            null
        }

    @Suppress("SwallowedException") // 写不进按 false：调用方据此判失败
    private fun persistQuietly(value: State): Boolean =
        try {
            DurableFile.write(
                file,
                JsonObject(
                    mapOf(
                        PROFILES to JsonPrimitive(value.profiles),
                        ACTIVE to JsonPrimitive(value.active),
                        RETIRED to JsonArray(value.retired.map(::JsonPrimitive)),
                        LEGACY to JsonPrimitive(value.legacyCleared),
                    ),
                ).toString(),
            )
            true
        } catch (error: IOException) {
            false
        }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private class OnceResult(
        private val completed: (Boolean) -> Unit,
    ) {
        private val done = AtomicBoolean(false)

        operator fun invoke(value: Boolean) {
            if (done.compareAndSet(false, true)) completed(value)
        }
    }

    companion object {
        /** 可信配置文件名前缀（外部容器用 [ExternalWebViewController.PROFILE]，不在此列）。 */
        const val PROFILE_PREFIX = "couli_trusted_"
        private const val STATE_PREFIX = "couli_trusted_store_"
        private const val PROFILES = "profiles"
        private const val ACTIVE = "active"
        private const val RETIRED = "retired"
        private const val LEGACY = "legacyCleared"
        private const val NAME_BYTES = 8
        private const val DISCARD_ATTEMPTS = 5
        private const val DISCARD_RETRY_MS = 500L
        private val random = SecureRandom()

        private fun newProfileName(): String {
            val bytes = ByteArray(NAME_BYTES).also(random::nextBytes)
            return PROFILE_PREFIX + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
