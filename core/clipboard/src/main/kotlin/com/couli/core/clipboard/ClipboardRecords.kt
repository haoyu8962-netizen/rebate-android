package com.couli.core.clipboard

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 本机加盐指纹（FU-CLIP §2.2 第 1 条；K08）：HMAC-SHA256(key = 本机盐 32 字节, msg = 原文 UTF-8)，小写十六进制；原文不规范化（G08）。
 * [saltId] = SHA-256(盐) 前 8 字节的十六进制（只用来识别记录属于哪个盐，不可逆推盐）。
 */
class ClipboardFingerprint(
    salt: ByteArray,
) {
    private val key = salt.copyOf()

    val saltId: String = hex(MessageDigest.getInstance("SHA-256").digest(key).copyOf(SALT_ID_BYTES))

    fun hash(text: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        return hex(mac.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    companion object {
        const val SALT_BYTES = 32
        private const val SALT_ID_BYTES = 8
        private const val ALGORITHM = "HmacSHA256"

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

        private const val BYTE_MASK = 0xff
    }
}

/** 一次读盐的结果：有、没有、读取失败（安全存储暂时不可用，不当成没有）。 */
sealed interface ClipboardSaltRead {
    data class Present(
        val salt: ByteArray,
    ) : ClipboardSaltRead {
        override fun equals(other: Any?): Boolean = other is Present && other.salt.contentEquals(salt)

        override fun hashCode(): Int = salt.contentHashCode()
    }

    data object Absent : ClipboardSaltRead

    data object Failed : ClipboardSaltRead
}

/**
 * 本机盐的存储端口（FU-CLIP §2.2 第 2 条）：生产为 App 给的 Keystore 包裹的加密存储（core/network 的 EncryptedPreferences，经这个端口注入，
 * 不加新依赖），测试用 [MemoryClipboardSaltStore]。写入与删除返回是否确认落盘。
 */
interface ClipboardSaltStore {
    fun read(): ClipboardSaltRead

    fun write(salt: ByteArray): Boolean

    fun delete(): Boolean
}

/** 内存实现（测试）：可直接换盐、可置读写失败、可查是否存在。 */
class MemoryClipboardSaltStore : ClipboardSaltStore {
    private var salt: ByteArray? = null

    var failReads: Boolean = false
    var failWrites: Boolean = false

    /** 存储里有没有盐（无副作用的观察）。 */
    val hasSalt: Boolean get() = salt != null

    fun setSalt(value: ByteArray) {
        salt = value.copyOf()
    }

    override fun read(): ClipboardSaltRead {
        if (failReads) return ClipboardSaltRead.Failed
        return salt?.let { ClipboardSaltRead.Present(it.copyOf()) } ?: ClipboardSaltRead.Absent
    }

    override fun write(salt: ByteArray): Boolean {
        if (failWrites) return false
        this.salt = salt.copyOf()
        return true
    }

    override fun delete(): Boolean {
        if (failWrites) return false
        salt = null
        return true
    }
}

/** 分类时用的加盐视图：本次的指纹、自写记录与 24 小时提示记录（都已按盐核对过）。 */
class ClipboardRecordsView(
    val fingerprint: ClipboardFingerprint,
    val selfWritten: List<String>,
    val prompted: List<ClipboardPromptStamp>,
)

/**
 * 去重与自写记录（FU-CLIP §2.2；K08～K11）：自写（最近 10 条）与 24 小时提示记录只存加盐指纹与时间，并记一份 saltId；不存原文、不存未加盐 hash。
 * - 盐在首次需要写记录时生成（[randomSalt]，生产为系统 CSPRNG），调用方保证已同意当前版本隐私政策（基本模式不调写入口，HS02 同口径）；
 * - 读记录时 saltId 与当前盐不一致（换盐、盐丢失后新建）→ 整份记录作废，下一次写时清掉；
 * - 盐不可用（安全存储读写失败）：自写记不上（[recordSelfWritten] 返回 false，调用方不写系统剪贴板）；24 小时判断不了（[promptedWithin24h]
 *   为 null，调用方这一次不自动提示）；都不回落到未加盐 hash；
 * - 撤回同意（[withdraw]）：先删盐、再删两份记录与 saltId，任一步没确认就返回 false（调用方保留待清理并重试）。
 */
@Suppress("TooManyFunctions") // 自写、提示、视图、撤回、迁移各一个入口（三端同名）
class ClipboardRecords(
    private val store: ClipboardHashStore,
    private val saltStore: ClipboardSaltStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val randomSalt: () -> ByteArray,
    capacity: Int = ClipboardWriteLedger.DEFAULT_CAPACITY,
) : ClipboardSelfWriteLedger {
    private val lock = Any()

    /** 加盐的自写记录（只读观察：读当前盐，不生成）。 */
    val ledger = ClipboardWriteLedger(capacity, hash = { readHash(it) }, store = store, key = KEY_SELF)

    /** 加盐的 24 小时提示记录。 */
    val history = PromptHistory(clock, store, key = KEY_PROMPTED)

    private fun readHash(text: String): String = (fingerprint(create = false) as? Salt.Ready)?.fingerprint?.hash(text).orEmpty()

    private sealed interface Salt {
        class Ready(
            val fingerprint: ClipboardFingerprint,
        ) : Salt

        data object Missing : Salt

        data object Unavailable : Salt
    }

    @Suppress("ReturnCount") // 读失败、已有、不生成、生成四个出口
    private fun fingerprint(create: Boolean): Salt {
        when (val read = runCatching { saltStore.read() }.getOrDefault(ClipboardSaltRead.Failed)) {
            ClipboardSaltRead.Failed -> return Salt.Unavailable
            is ClipboardSaltRead.Present -> return Salt.Ready(ClipboardFingerprint(read.salt))
            ClipboardSaltRead.Absent -> if (!create) return Salt.Missing
        }
        val salt = randomSalt()
        if (salt.size != ClipboardFingerprint.SALT_BYTES || !runCatching { saltStore.write(salt) }.getOrDefault(false)) {
            return Salt.Unavailable
        }
        return Salt.Ready(ClipboardFingerprint(salt))
    }

    /**
     * 上一次「换盐清记录 + 记 saltId」没有确认落盘（R1-04 同类）：存储实现可能已把内存改掉、磁盘没改，读回的 saltId 不能当依据；在确认落盘之前
     * 一律按不匹配处理，下一次写时重新提交整套清理。
     */
    private var saltIdUnconfirmed = false

    private fun recordsMatch(fingerprint: ClipboardFingerprint): Boolean =
        !saltIdUnconfirmed && runCatching { store.read(KEY_SALT_ID) }.getOrNull() == fingerprint.saltId

    /** 写之前：记录属于别的盐（或还没有 saltId）→ 整份清掉，再记当前 saltId；以确认落盘为准。 */
    private fun prepareWrite(fingerprint: ClipboardFingerprint): Boolean {
        if (recordsMatch(fingerprint)) return true
        val done = write(KEY_SELF, null) && write(KEY_PROMPTED, null) && write(KEY_SALT_ID, fingerprint.saltId)
        saltIdUnconfirmed = !done
        return done
    }

    private fun write(
        key: String,
        value: String?,
    ): Boolean = runCatching { store.write(key, value) }.getOrDefault(false)

    /** 记下自写内容（写系统剪贴板之前）；返回是否确认落盘。 */
    fun recordSelfWritten(text: String): Boolean =
        synchronized(lock) {
            val salt = fingerprint(create = true) as? Salt.Ready ?: return false
            prepareWrite(salt.fingerprint) && ledger.recordHash(salt.fingerprint.hash(text))
        }

    override fun record(text: String): Boolean = recordSelfWritten(text)

    override fun contains(text: String): Boolean = isSelfWritten(text)

    /** 是不是 App 自写的内容；盐没有或不可用时为 false（手动粘贴照常）。 */
    fun isSelfWritten(text: String): Boolean =
        synchronized(lock) {
            val salt = fingerprint(create = false) as? Salt.Ready ?: return false
            recordsMatch(salt.fingerprint) && salt.fingerprint.hash(text) in ledger.hashes()
        }

    /** 记下出过提示条的内容；返回是否确认落盘。 */
    fun recordPrompted(text: String): Boolean =
        synchronized(lock) {
            val salt = fingerprint(create = true) as? Salt.Ready ?: return false
            recordPromptedHash(salt.fingerprint, salt.fingerprint.hash(text))
        }

    /** 按分类时取得的指纹记下提示（[view] 的指纹；盐此后被换掉时不写，避免把旧盐的指纹记到新盐名下）。 */
    fun recordPromptedHash(
        fingerprint: ClipboardFingerprint,
        hash: String,
    ): Boolean =
        synchronized(lock) {
            val current = fingerprint(create = false) as? Salt.Ready ?: return false
            if (current.fingerprint.saltId != fingerprint.saltId) return false
            prepareWrite(fingerprint) && history.record(hash)
        }

    /** 24 小时内（严格小于）提示过；盐不可用为 null（判断不了）。 */
    fun promptedWithin24h(text: String): Boolean? =
        synchronized(lock) {
            when (val salt = fingerprint(create = false)) {
                Salt.Unavailable -> {
                    null
                }

                Salt.Missing -> {
                    false
                }

                is Salt.Ready -> {
                    val hash = salt.fingerprint.hash(text)
                    val now = clock()
                    recordsMatch(salt.fingerprint) &&
                        history.stamps().any { it.hash == hash && now - it.atMs in 0 until ClipboardClassifier.PROMPT_WINDOW_MS }
                }
            }
        }

    /**
     * 分类前取加盐视图（自动识别用；已同意时调）：必要时生成盐。盐不可用 → null（这一次不自动提示，§2.2 第 5 条）。记录属于别的盐 → 视为空。
     */
    fun view(): ClipboardRecordsView? =
        synchronized(lock) {
            val salt = fingerprint(create = true) as? Salt.Ready ?: return null
            val valid = recordsMatch(salt.fingerprint)
            ClipboardRecordsView(
                salt.fingerprint,
                if (valid) ledger.hashes() else emptyList(),
                if (valid) history.stamps() else emptyList(),
            )
        }

    /** 撤回同意：同一个清理步骤里先删盐、再删两份记录（§2.2 第 4 条）；任一步没确认就返回 false。 */
    fun withdraw(): Boolean =
        synchronized(lock) {
            val done =
                runCatching { saltStore.delete() }.getOrDefault(false) &&
                    write(KEY_SELF, null) &&
                    write(KEY_PROMPTED, null) &&
                    write(KEY_SALT_ID, null)
            // 没确认落盘：读回的 saltId 不可信，之后写记录前重新清理（R1-04 同类）
            if (!done) saltIdUnconfirmed = true
            done
        }

    /**
     * 升级迁移（§2.2 第 6 条；K09）：删掉旧版未加盐的记录键；返回是否确认落盘（失败调用方保留待清理并重试）。
     * 完成只以删除确认落盘为依据（R1-04）：每次都对每个旧键重新提交删除，不先读「键还在不在」——上一次提交失败时 SharedPreferences 已把
     * 内存里的键删掉、磁盘上还在，读内存会误报完成。每个键都提交（不因前一个失败短路），全部确认才算完成。
     */
    fun dropLegacy(): Boolean =
        synchronized(lock) {
            LEGACY_KEYS.map { write(it, null) }.all { it }
        }

    companion object {
        const val KEY_SELF = "fp.self_written"
        const val KEY_PROMPTED = "fp.prompted"
        const val KEY_SALT_ID = "fp.salt_id"

        /** 旧版（APP-07）未加盐 SHA-256 记录的键。 */
        val LEGACY_KEYS: List<String> = listOf(ClipboardWriteLedger.KEY, PromptHistory.KEY)
    }
}
