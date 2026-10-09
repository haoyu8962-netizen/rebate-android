package com.couli.core.clipboard

/**
 * 本机 hash 记录的存储端口（只存 hash 与时间，不存原文；BR-ID-16「本机只存 hash」）。生产为 SharedPreferences（App 给），测试用内存。
 * 读失败或内容损坏按空处理：自写记录与提示记录丢了只会多出一次提示，不会少读或多上传。
 */
interface ClipboardHashStore {
    fun read(key: String): String?

    fun write(
        key: String,
        value: String?,
    )
}

/** 内存实现（测试与没有持久化时）。 */
class MemoryClipboardHashStore : ClipboardHashStore {
    private val values = mutableMapOf<String, String>()

    override fun read(key: String): String? = synchronized(values) { values[key] }

    override fun write(
        key: String,
        value: String?,
    ) {
        synchronized(values) { if (value == null) values.remove(key) else values[key] = value }
    }
}

/**
 * App 自己写入剪贴板的内容（F-CLIP-02；brief §2.3）：桥 clipboard.write、复制口令 copy_tpwd、各原生复制按钮都经同一个写入口，写入前先
 * [record]。保留最近 [capacity] 条 hash（重复写入同一内容移到最新），商品与邀请两种识别都排除。撤回同意时 [clear]。
 */
class ClipboardWriteLedger(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val hash: (String) -> String = ClipboardText::sha256,
    private val store: ClipboardHashStore = MemoryClipboardHashStore(),
) {
    private val lock = Any()

    fun record(text: String) = recordHash(hash(text))

    fun contains(text: String): Boolean = hash(text) in hashes()

    fun recordHash(value: String) {
        synchronized(lock) {
            val next = (hashes() - value + value).takeLast(capacity)
            store.write(KEY, next.joinToString(SEPARATOR))
        }
    }

    /** 最近 [capacity] 条（旧的在前）。 */
    fun hashes(): List<String> =
        synchronized(lock) {
            store
                .read(KEY)
                ?.split(SEPARATOR)
                ?.filter { it.isNotEmpty() }
                .orEmpty()
                .takeLast(capacity)
        }

    fun clear() {
        synchronized(lock) { store.write(KEY, null) }
    }

    companion object {
        const val DEFAULT_CAPACITY = 10
        private const val KEY = "self_written"
        private const val SEPARATOR = ","
    }
}

/**
 * 出过提示条的内容（G08）：出了提示条就记录（点了或关了都算），同一 hash 严格小于 24 小时不再提示。只存 hash 与时间；时钟可注入。
 * 只按 24 小时到期清理，不设条数上限（A-R1-07：容量淘汰会让仍在 24 小时内的内容再次提示）；每次前台最多出 1 条提示，24 小时内的条数
 * 本来就有限。撤回同意时 [clear]。
 */
class PromptHistory(
    private val clock: () -> Long = System::currentTimeMillis,
    private val store: ClipboardHashStore = MemoryClipboardHashStore(),
) {
    private val lock = Any()

    fun record(hash: String) {
        synchronized(lock) {
            val now = clock()
            val kept = stamps().filter { it.hash != hash && now - it.atMs in 0 until ClipboardClassifier.PROMPT_WINDOW_MS }
            val next = kept + ClipboardPromptStamp(hash, now)
            store.write(KEY, next.joinToString(SEPARATOR) { "${it.hash}$FIELD${it.atMs}" })
        }
    }

    fun stamps(): List<ClipboardPromptStamp> =
        synchronized(lock) {
            store
                .read(KEY)
                ?.split(SEPARATOR)
                ?.mapNotNull { entry ->
                    val hash = entry.substringBefore(FIELD, "")
                    val at = entry.substringAfter(FIELD, "").toLongOrNull()
                    if (hash.isEmpty() || at == null) null else ClipboardPromptStamp(hash, at)
                }.orEmpty()
        }

    fun clear() {
        synchronized(lock) { store.write(KEY, null) }
    }

    private companion object {
        const val KEY = "prompted"
        const val SEPARATOR = ","
        const val FIELD = "@"
    }
}
