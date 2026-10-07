package com.couli.core.clientcore

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        startFetch()
    }

    /** 回前台：带本进程最近一次成功响应的 ETag 拉取。 */
    fun onForeground() = startFetch()

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

    @Suppress("ReturnCount") // 作废、非成功、内容不是对象逐项提前结束
    private fun complete(
        id: Long,
        gen: Long,
        reply: ConfigReply,
    ) {
        synchronized(lock) {
            if (inFlight != id || generation != gen) return
            inFlight = null
            if (reply.kindRaw != ConfigReply.OK) return
            val body = ConfigResolver.parseObject(reply.bodyJson) ?: return
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
            publishLocked()
        }
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
    }
}
