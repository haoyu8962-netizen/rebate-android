package com.couli.core.clientcore

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
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
 * 验签通过的可信 H5 源白名单（N-22「上次可用白名单」，bridge_origins 的 payload.version 与 payload.origins）。
 * ConfigStore 不依赖 core:bridge：验签由 App 注入的闭包做（BridgeOriginsVerifier），这里只存结果。
 */
data class BridgeOriginsLastGood(
    val version: Long,
    val origins: List<String>,
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
 * `{"format":1,"saved_at":…,"data":{…},"link_patterns":{…},"search_status":{…},"bridge_origins_last_good":{"version":n,"origins":[…]}}`，
 * link_patterns 是上一次成功的完整链接形态表（某次成功响应缺它时沿用，03 §4.3「当前版本、上一次成功的版本、包内」）；search_status 是
 * 逐平台最近一次成功的取值；bridge_origins_last_good 是上次可用白名单（N-22，只写验签通过的值，没有就不写这个键）。
 *
 * 上次可用白名单（N-22，D10-2539；契约 ConfigBridgeOrigins「Client」段）：
 * - 200 新配置：用当前上次可用的 version 调 [verifyBridgeOrigins] 验新文档；通过 → 成为上次可用并随 LKG 落盘；不通过 → 保留原值，
 *   LKG 文件照旧写原上次可用（失败值不写成上次可用，AC-S1-180 ②）。
 * - 冷启动：LKG 文件带 bridge_origins_last_good 且形状完整 → 直接用；没有该字段（旧 LKG）→ 用 LKG 的 data 现验一次（lastGoodVersion
 *   = null），通过才用，否则为空。
 * - 304 不变；[clearForEnvironmentSwitch] 清空。包内默认配置的 jws 是占位串、必然验不过 → 没有服务端配置时白名单为空。
 * - [verifyBridgeOrigins] 由 App 注入，每次调用现取运行环境 id（切环境后用新环境）；缺省一律拒。
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
    /**
     * bridge_origins 验签（N-22）：入参为配置文档（/v1/config 的 data）与上次可用白名单的 version（没有为 null），通过返回白名单，
     * 不通过返回 null。缺省一律拒（白名单恒空）。
     */
    private val verifyBridgeOrigins: (config: JsonObject, lastGoodVersion: Long?) -> BridgeOriginsLastGood? = { _, _ -> null },
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

    /** 上次可用白名单（N-22）：只放验签通过的值；没有为 null。随 LKG 文件保存（bridge_origins_last_good）。 */
    private var lastGoodOrigins: BridgeOriginsLastGood? = null

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

    /** 逐个快照的同步监听（[onEachSnapshot]，FU-AND-0714 第 2 轮评审 R2-05）。 */
    private val snapshotListeners = CopyOnWriteArrayList<(ConfigSnapshot) -> Unit>()

    private val mutableSnapshots = MutableStateFlow(ConfigSnapshot(bundledObject, ConfigSourceRaw.BUNDLED))

    /** 当前生效的配置（每次换层或换内容时发出）。 */
    val snapshots: StateFlow<ConfigSnapshot> = mutableSnapshots.asStateFlow()

    /** 当前生效的 data（JSON 文本）。 */
    val snapshotJson: String get() = synchronized(lock) { effective().toString() }

    /** 当前生效的配置来自哪一层：current / lkg / bundled。 */
    val sourceRaw: String get() = synchronized(lock) { sourceLocked() }

    /** 可信 H5 源（N-22）：上次可用白名单的 origins；没有为空（WebPage 不打开）。 */
    val trustedOrigins: List<String> get() = synchronized(lock) { lastGoodOrigins?.origins.orEmpty() }

    /** 上次可用白名单的 version；没有为 null。 */
    val trustedOriginsVersion: Long? get() = synchronized(lock) { lastGoodOrigins?.version }

    /** 冷启动：同步读 LKG，再开始后台拉取（不带 If-None-Match）。 */
    fun onColdStart() {
        synchronized(lock) {
            val stored = runCatching { readFile() }.getOrNull()?.let(::decode)
            lkg = stored?.data
            lastPatterns = stored?.patterns ?: stored?.data?.let(ConfigResolver::linkPatternsIn)
            lastSearch = stored?.let { it.search ?: searchIn(it.data) }.orEmpty()
            // 带字段的 LKG 是本 App 验过后才写的，直接用；升级前的旧 LKG（没有该字段）用它的 data 现验一次
            lastGoodOrigins = stored?.let { it.bridgeOrigins ?: verifyLocked(it.data, null) }
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
     * 逐个快照的同步监听（FU-AND-0714 第 2 轮评审 R2-05）：每发布一份生效快照，就在发布它的线程上、发布的同一把锁里按发布顺序回调一次，**不合并**
     * （[snapshots] 是 StateFlow，界面线程来不及收时「关 → 开」会被合并成「开」）。只用于记下规则收紧这类不能丢的变化：回调必须很短、不阻塞、
     * 不回调进本类（它在锁内执行），切线程由接收方自己做。返回取消订阅的函数。
     */
    fun onEachSnapshot(listener: (ConfigSnapshot) -> Unit): () -> Unit {
        snapshotListeners += listener
        return { snapshotListeners -= listener }
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
                lastGoodOrigins = null
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
        // 上次可用白名单（N-22）：每次 200 都用当前上次可用的 version 验新文档（缺字段、旧数组形状也交给验签方判拒）；
        // 通过才替换，不通过保留原值——LKG 里照旧写原值，失败值不写成上次可用（AC-S1-180 ②）
        val bridgeOrigins = verifyLocked(body, lastGoodOrigins?.version) ?: lastGoodOrigins
        // 先写 LKG 再换内存。写盘失败时文件仍是上一份成功配置；本次响应本身有效，照样作为当前配置与最近一次成功配置使用
        runCatching { writeFile(encode(body, patterns, search, bridgeOrigins)) }
        current = body
        lkg = body
        etag = reply.etag
        lastPatterns = patterns
        lastSearch = search
        lastGoodOrigins = bridgeOrigins
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

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 监听方出错不影响配置本身与其他监听方
    private fun publishLocked() {
        val snapshot = ConfigSnapshot(effective(), sourceLocked())
        mutableSnapshots.value = snapshot
        snapshotListeners.forEach { listener ->
            try {
                listener(snapshot)
            } catch (error: Exception) {
                // 忽略
            }
        }
    }

    /**
     * 调注入的验签闭包（在锁内）：通过且 version 不低于 [lastGoodVersion]、origins 非空才算数（闭包自身已判，这里兜底不让回退或空表
     * 顶掉上次可用）；闭包抛错按不通过。
     */
    private fun verifyLocked(
        data: JsonObject,
        lastGoodVersion: Long?,
    ): BridgeOriginsLastGood? =
        runCatching { verifyBridgeOrigins(data, lastGoodVersion) }
            .getOrNull()
            ?.takeIf { it.origins.isNotEmpty() && it.version >= (lastGoodVersion ?: 0L) }

    private class Stored(
        val data: JsonObject,
        val patterns: JsonObject?,
        val search: Map<String, String>?,
        val bridgeOrigins: BridgeOriginsLastGood?,
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
        bridgeOrigins: BridgeOriginsLastGood?,
    ): String {
        val fields =
            linkedMapOf(
                KEY_FORMAT to JsonPrimitive(FORMAT),
                KEY_SAVED_AT to JsonPrimitive(clock()),
                KEY_DATA to data,
            )
        patterns?.let { fields[KEY_PATTERNS] = it }
        fields[KEY_SEARCH] = JsonObject(search.mapValues { JsonPrimitive(it.value) })
        bridgeOrigins?.let {
            fields[KEY_BRIDGE_ORIGINS] =
                JsonObject(mapOf(KEY_VERSION to JsonPrimitive(it.version), KEY_ORIGINS to JsonArray(it.origins.map(::JsonPrimitive))))
        }
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
        return Stored(data, patterns, search, (root[KEY_BRIDGE_ORIGINS] as? JsonObject)?.let(::decodeLastGood))
    }

    /** LKG 里的上次可用白名单：version 为非负整数、origins 为非空字符串数组才算形状完整，否则按没有（冷启动改用 data 现验）。 */
    private fun decodeLastGood(stored: JsonObject): BridgeOriginsLastGood? {
        val version = (stored[KEY_VERSION] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()?.takeIf { it >= 0 }
        val items = stored[KEY_ORIGINS] as? JsonArray
        val origins =
            items
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.takeIf { it.isNotEmpty() && it.size == items.size }
        return if (version != null && origins != null) BridgeOriginsLastGood(version, origins) else null
    }

    private companion object {
        const val FORMAT = 1
        const val KEY_FORMAT = "format"
        const val KEY_SAVED_AT = "saved_at"
        const val KEY_DATA = "data"
        const val KEY_PATTERNS = "link_patterns"
        const val KEY_SEARCH = "search_status"
        const val KEY_BRIDGE_ORIGINS = "bridge_origins_last_good"
        const val KEY_VERSION = "version"
        const val KEY_ORIGINS = "origins"
        const val LAYER_CURRENT = "current"
        const val LAYER_LKG = "lkg"
        const val LAYER_BUNDLED = "bundled"
    }
}
