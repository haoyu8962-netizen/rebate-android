package com.couli.core.clientcore

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 一次 /v1/config 拉取的结果（三端测试接线约定）。
 * @property kindRaw [OK] / [NOT_MODIFIED] / [FAILURE]。
 * @property bodyJson [OK] 时为响应的 data 对象（JSON 文本）。
 * @property etag 响应头 ETag（不透明字符串，原样保存，不拆引号或 W/ 前缀）。
 */
data class ConfigReply(
    val kindRaw: String,
    val bodyJson: String?,
    val etag: String?,
) {
    companion object {
        const val OK = "ok"
        const val NOT_MODIFIED = "not_modified"
        const val FAILURE = "failure"
    }
}

/** 配置来自哪一层（sourceRaw）。 */
object ConfigSourceRaw {
    const val CURRENT = "current"
    const val LKG = "lkg"
    const val BUNDLED = "bundled"
}

/** 可观察的配置快照：当前生效的 data 与它来自哪一层。 */
data class ConfigSnapshot(
    val data: JsonObject,
    val sourceRaw: String,
)

/**
 * 配置与 LKG（03 §4.3）：
 * - 冷启动（[onColdStart]）：同步读 LKG 文件（最近一次成功的 /v1/config data），再后台拉取；没有 LKG 用包内配置 [bundled]。
 * - 回前台（[onForeground]）：带 If-None-Match（本进程最近一次成功响应的 ETag）拉取；304 时内容不变。
 * - 拉取成功：先写 LKG 文件，再换当前配置与 ETag；拉取失败、304 都不动当前配置与 LKG。
 * - 同一时刻最多一个在途拉取（在途时再回前台不另发）；作废后（[clearForEnvironmentSwitch]）晚到的结果丢弃。
 * - 可观察流 [snapshots]；按层取值见 [searchStatus]、[linkPatterns]（[ConfigResolver]）。
 *
 * LKG 文件内容（[readFile] / [writeFile] 存取的字节对调用方不透明）：
 * `{"format":1,"saved_at":…,"data":{…},"link_patterns":{…},"search_status":{…}}`，link_patterns 是上一次成功的完整链接形态表
 * （某次成功响应缺它时沿用，03 §4.3「当前版本、上一次成功的版本、包内」）；search_status 是逐平台最近一次成功的取值。
 * 文件读不出或内容损坏：本次冷启动按没有 LKG（包内配置），下一次成功拉取覆盖它。
 * TODO(N-21): getConfig 在契约里还没有声明 ETag / If-None-Match / 304；ETag 不跨进程保存（冷启动一律完整拉取），
 * 没有缓存时收到的 304 不改任何内容（rule-gaps 第 3 条）。
 *
 * 「清除缓存」不清这里（03 §4.3）；切换环境时由清理入口调 [clearForEnvironmentSwitch]（03 §3.6）。线程安全。
 */
@Suppress("TooManyFunctions") // 冷启动、回前台、结果处理、按层取值与环境清理逐项一个入口
class ConfigStore(
    private val readFile: () -> String?,
    private val writeFile: (String?) -> Unit,
    private val fetcher: (String?, (ConfigReply) -> Unit) -> Unit,
    private val clock: () -> Long,
    private val bundled: String,
) {
    private val lock = Any()
    private val bundledObject: JsonObject = ConfigResolver.parseObject(bundled) ?: JsonObject(emptyMap())

    private var current: JsonObject? = null
    private var etag: String? = null
    private var lkg: JsonObject? = null
    private var lastPatterns: JsonObject? = null

    /**
     * 按平台的搜索开关在「上一次成功」这一层的取值（APP-03b R1-07）：每次成功响应带了某个平台的 on / off 就更新那个平台，缺字段、
     * 缺平台的沿用更早一次成功里的值；随 LKG 文件一起保存。冷启动从文件读（旧格式没有它时取 LKG data 里的）。
     */
    private var lastSearch: Map<String, String> = emptyMap()

    /** 在途拉取的编号；没有为 null。 */
    private var inFlight: Long? = null
    private var sequence = 0L

    /** 环境切换清理加 1：之前发出的拉取结果作废。 */
    private var generation = 0L

    /**
     * 当前配置（current 层）最近一次被服务端确认的拉取编号（APP-06a，H-R3-01）：200 成功换内容时、有效的 304 重新确认当前配置时
     * 都推进；还没有成功拉取、环境切换后为 0。
     */
    private var appliedId = 0L

    /** [requestFresh] 时有拉取在途：在途的这次结算后再发一次（保证有一次拉取在请求之后才发出）。 */
    private var pendingFresh = false

    /** 配置被确认或换内容时的监听（搜索页订阅，APP-06a）。 */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val mutableSnapshots = MutableStateFlow(ConfigSnapshot(bundledObject, ConfigSourceRaw.BUNDLED))

    /** 当前生效的配置（每次换层或换内容时发出）。 */
    val snapshots: StateFlow<ConfigSnapshot> = mutableSnapshots.asStateFlow()

    /** 当前生效的 data（JSON 文本）。 */
    val snapshotJson: String get() = synchronized(lock) { effective().toString() }

    /** 当前生效的配置来自哪一层：current / lkg / bundled。 */
    val sourceRaw: String get() = synchronized(lock) { sourceLocked() }

    /** 冷启动：同步读 LKG，再开始后台拉取（不带 If-None-Match）。 */
    fun onColdStart() {
        synchronized(lock) {
            val stored = runCatching { readFile() }.getOrNull()?.let(::decode)
            lkg = stored?.data
            lastPatterns = stored?.patterns ?: stored?.data?.let(ConfigResolver::linkPatternsIn)
            lastSearch = stored?.let { it.search ?: searchIn(it.data) }.orEmpty()
            publishLocked()
        }
        notifyListeners()
        startFetch()
    }

    /** 回前台：带本进程最近一次成功响应的 ETag 拉取。 */
    fun onForeground() = startFetch()

    /**
     * 要一份「此刻之后才发出」的配置（APP-06a：50304 search_disabled 刷新配置，BR-PROD-10）：没有在途拉取就立即拉取；有在途拉取时，
     * 它结算（成功、304 或失败）后再发一次——在途的那次是此刻之前发出的，结果不能当作更新的配置。
     */
    fun requestFresh() {
        synchronized(lock) {
            if (inFlight != null) {
                pendingFresh = true
                return
            }
        }
        startFetch()
    }

    /** 已发出的拉取的最大编号（单调递增）：编号大于它的拉取一定在此刻之后才发出。 */
    val issuedFetchId: Long get() = synchronized(lock) { sequence }

    /** 当前配置最近一次被确认（200 或有效的 304）的拉取编号；还没有为 0。 */
    val appliedFetchId: Long get() = synchronized(lock) { appliedId }

    /**
     * 订阅配置变化：换层、换内容、有效的 304 确认、环境清理之后各通知一次（不立即回调）。在拉取线程或调用方线程上调用，接收方自己
     * 切线程。返回取消订阅的函数。
     */
    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /**
     * 搜索 Tab 用的三层原始取值（APP-06a SearchTabs.resolve 的输入，JSON 对象 {current?, lkg, bundled?}）：current 为本进程最近一次
     * 成功响应的 features.search_status 原样对象（未知取值保留，由 SearchTabs 按 off 处理、不回落）；lkg 为历次成功响应按平台合并的
     * on / off；bundled 为包内原样对象。某层没有 search_status 对象时不放该层。
     * TODO(N-27)：LKG 只存 on / off，历史响应里的未知取值不会留到 lkg 层（未知值三层口径待补）。
     */
    fun searchStatusLayers(): JsonObject =
        synchronized(lock) {
            val layers = linkedMapOf<String, JsonElement>()
            current?.let(::rawSearchStatus)?.let { layers[LAYER_CURRENT] = it }
            layers[LAYER_LKG] = JsonObject(lastSearch.mapValues { JsonPrimitive(it.value) })
            rawSearchStatus(bundledObject)?.let { layers[LAYER_BUNDLED] = it }
            JsonObject(layers)
        }

    private fun rawSearchStatus(layer: JsonObject): JsonObject? = (layer["features"] as? JsonObject)?.get("search_status") as? JsonObject

    /**
     * 按平台取搜索 Tab 状态：当前 → 上一次成功（逐平台取最近一次带了该平台取值的成功配置，R1-07）→ 包内（明确的 off 有效）；
     * 都没有为 null。
     */
    fun searchStatus(platformKey: String): String? =
        synchronized(lock) {
            current?.let { ConfigResolver.searchStatusIn(it, platformKey) }
                ?: lastSearch[platformKey]
                ?: ConfigResolver.searchStatusIn(bundledObject, platformKey)
        }

    /**
     * 各层的链接形态表，按优先级排列：当前 → 上一次成功 → 包内（JSON 文本，缺层不列）。调用方按自己的口径整表校验，取第一张有效的
     * （APP-04 三端共性第 18 条：坏表整表回落，不逐条合并）。
     */
    fun linkPatternLayers(): List<String> =
        synchronized(lock) {
            listOfNotNull(current?.let(ConfigResolver::linkPatternsIn), lastPatterns, ConfigResolver.linkPatternsIn(bundledObject))
                .map { it.toString() }
        }

    /** 带版本的链接形态表：当前 → 上一次成功 → 包内（JSON 文本）。 */
    fun linkPatterns(): String? =
        synchronized(lock) {
            listOfNotNull(current?.let(ConfigResolver::linkPatternsIn), lastPatterns, ConfigResolver.linkPatternsIn(bundledObject))
                .firstOrNull()
                ?.toString()
        }

    /**
     * 切换环境（03 §3.6）：作废在途拉取，删除 LKG 文件，回到包内配置。删除失败照样清内存（不再用旧环境的配置）并把错误抛给
     * 清理入口（环境闸门保持阻断、下次重试）。
     */
    fun clearForEnvironmentSwitch() {
        synchronized(lock) {
            generation += 1
            inFlight = null
            pendingFresh = false
            appliedId = 0L
            try {
                writeFile(null)
            } finally {
                current = null
                etag = null
                lkg = null
                lastPatterns = null
                lastSearch = emptyMap()
                publishLocked()
            }
        }
        notifyListeners()
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 监听方出错不影响配置本身与其他监听方
    private fun notifyListeners() {
        listeners.forEach { listener ->
            try {
                listener()
            } catch (error: Exception) {
                // 忽略
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 拉取器同步抛错按拉取失败结算，不让在途标记卡住
    private fun startFetch() {
        val id: Long
        val gen: Long
        val ifNoneMatch: String?
        synchronized(lock) {
            if (inFlight != null) return
            sequence += 1
            id = sequence
            gen = generation
            inFlight = id
            ifNoneMatch = etag.takeIf { current != null }
        }
        try {
            fetcher(ifNoneMatch) { reply -> complete(id, gen, reply) }
        } catch (error: Exception) {
            complete(id, gen, ConfigReply(ConfigReply.FAILURE, null, null))
        }
    }

    private fun complete(
        id: Long,
        gen: Long,
        reply: ConfigReply,
    ) {
        val changed: Boolean
        val again: Boolean
        synchronized(lock) {
            if (inFlight != id || generation != gen) return
            inFlight = null
            changed = applyLocked(id, reply)
            again = pendingFresh
            pendingFresh = false
        }
        if (changed) notifyListeners()
        if (again) startFetch()
    }

    /**
     * 结算一次拉取（在锁内）：200 换内容并推进确认编号；有效的 304（本进程已有当前配置时才会带 If-None-Match，同一时刻只有一个在途
     * 拉取、环境切换会作废在途结果，所以结算时 current 仍是发出时那一份）只推进确认编号，内容、LKG、ETag 都不变（APP-06a H-R3-01：
     * search_disabled 分界之后的确认据此恢复明确为 on 的平台）；没有当前配置时的 304 与失败不改任何内容。返回是否要通知监听方。
     */
    @Suppress("ReturnCount") // 304、非成功、内容不是对象逐项提前结束
    private fun applyLocked(
        id: Long,
        reply: ConfigReply,
    ): Boolean {
        if (reply.kindRaw == ConfigReply.NOT_MODIFIED) {
            if (current == null) return false
            appliedId = id
            return true
        }
        if (reply.kindRaw != ConfigReply.OK) return false
        val body = ConfigResolver.parseObject(reply.bodyJson) ?: return false
        val patterns = ConfigResolver.linkPatternsIn(body) ?: lastPatterns
        // 每次成功都推进「上一次成功」这一层（R1-07）：带了取值的平台更新，缺的沿用更早一次成功的
        val search = lastSearch + searchIn(body)
        // 先写 LKG 再换内存。写盘失败时文件仍是上一份成功配置；本次响应本身有效，照样作为当前配置与最近一次成功配置使用
        runCatching { writeFile(encode(body, patterns, search)) }
        current = body
        lkg = body
        etag = reply.etag
        lastPatterns = patterns
        lastSearch = search
        appliedId = id
        publishLocked()
        return true
    }

    private fun effective(): JsonObject = current ?: lkg ?: bundledObject

    private fun sourceLocked(): String =
        when {
            current != null -> ConfigSourceRaw.CURRENT
            lkg != null -> ConfigSourceRaw.LKG
            else -> ConfigSourceRaw.BUNDLED
        }

    private fun publishLocked() {
        mutableSnapshots.value = ConfigSnapshot(effective(), sourceLocked())
    }

    private class Stored(
        val data: JsonObject,
        val patterns: JsonObject?,
        val search: Map<String, String>?,
    )

    /** 一层里 features.search_status 的有效取值（只认 on / off）。 */
    private fun searchIn(layer: JsonObject): Map<String, String> {
        val status = (layer["features"] as? JsonObject)?.get("search_status") as? JsonObject ?: return emptyMap()
        return status.keys.mapNotNull { key -> ConfigResolver.searchStatusIn(layer, key)?.let { key to it } }.toMap()
    }

    private fun encode(
        data: JsonObject,
        patterns: JsonObject?,
        search: Map<String, String>,
    ): String {
        val fields =
            linkedMapOf(
                KEY_FORMAT to JsonPrimitive(FORMAT),
                KEY_SAVED_AT to JsonPrimitive(clock()),
                KEY_DATA to data,
            )
        patterns?.let { fields[KEY_PATTERNS] = it }
        fields[KEY_SEARCH] = JsonObject(search.mapValues { JsonPrimitive(it.value) })
        return JsonObject(fields).toString()
    }

    @Suppress("ReturnCount") // 格式不符逐项按没有 LKG
    private fun decode(text: String): Stored? {
        val root = ConfigResolver.parseObject(text) ?: return null
        if ((root[KEY_FORMAT] as? JsonPrimitive)?.content != FORMAT.toString()) return null
        val data = root[KEY_DATA] as? JsonObject ?: return null
        val patterns = (root[KEY_PATTERNS] as? JsonObject)?.let { ConfigResolver.linkPatternsIn(JsonObject(mapOf(KEY_PATTERNS to it))) }
        val search =
            (root[KEY_SEARCH] as? JsonObject)?.let { stored ->
                searchIn(JsonObject(mapOf("features" to JsonObject(mapOf("search_status" to stored)))))
            }
        return Stored(data, patterns, search)
    }

    private companion object {
        const val FORMAT = 1
        const val KEY_FORMAT = "format"
        const val KEY_SAVED_AT = "saved_at"
        const val KEY_DATA = "data"
        const val KEY_PATTERNS = "link_patterns"
        const val KEY_SEARCH = "search_status"
        const val LAYER_CURRENT = "current"
        const val LAYER_LKG = "lkg"
        const val LAYER_BUNDLED = "bundled"
    }
}
