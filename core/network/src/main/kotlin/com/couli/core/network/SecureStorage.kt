package com.couli.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.couli.core.config.CouliPreferenceNamespaces
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.ProviderException
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * 安全存储此刻读不出（Keystore 暂时不可用等），不是内容损坏：调用方不能当成「没有记录」，也不删除（lessons 38、APP-03b
 * session_unreadable）。[SecretCipher] 遇到这类失败时抛它；其余解密失败（密钥已删、标签不符、数据损坏）按内容损坏处理。
 */
class SecureStorageReadException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 对称加解密（生产：[AndroidKeystoreCipher]；单测注入假实现，Robolectric 没有 AndroidKeyStore）。 */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(sealed: ByteArray): ByteArray

    /** 删除密钥（切换环境、撤回同意）。之后旧密文都解不开。 */
    fun destroyKey() = Unit
}

/**
 * Android Keystore 里的 AES-256-GCM 密钥（不可导出）。EncryptedSharedPreferences 不在依赖白名单里，这里自写最小实现：
 * 密文 = 12 字节 IV + GCM 密文（含 16 字节标签）。
 */
class AndroidKeystoreCipher(
    private val alias: String = DEFAULT_ALIAS,
) : SecretCipher {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    @Synchronized
    private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    /**
     * 密文过短、标签不符（密钥已删后重新生成、数据损坏）、密钥永久失效 → 原样抛出（内容损坏）；Keystore 本身出错（取不到密钥、
     * 服务暂时不可用）→ [SecureStorageReadException]（读取失败，APP-03b session_unreadable）。
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount") // Keystore 的错误类型分散，按「损坏 / 读取失败」两类重新抛出
    override fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "密文过短" }
        val key =
            try {
                key()
            } catch (error: GeneralSecurityException) {
                throw SecureStorageReadException("取不到 Keystore 密钥", error)
            } catch (error: ProviderException) {
                throw SecureStorageReadException("Keystore 不可用", error)
            }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        } catch (error: KeyPermanentlyInvalidatedException) {
            throw error
        } catch (error: InvalidKeyException) {
            throw SecureStorageReadException("Keystore 密钥暂时不可用", error)
        } catch (error: ProviderException) {
            throw SecureStorageReadException("Keystore 不可用", error)
        }
        return try {
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (error: ProviderException) {
            throw SecureStorageReadException("Keystore 不可用", error)
        }
    }

    @Synchronized
    override fun destroyKey() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    companion object {
        const val DEFAULT_ALIAS = "couli.session.aes"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12
    }
}

/** 加密存储里有值，但解不开或不能解析（密钥不符、数据损坏）。不当成「没有记录」（lessons 38）。 */
class UnreadableRecordException(
    message: String,
) : IllegalStateException(message)

/** 一次读取的结果：不存在、明文、内容损坏（解不开或不能解码）、读取失败（[SecureStorageReadException]）。 */
sealed interface StoredValue {
    data object Absent : StoredValue

    data class Present(
        val text: String,
    ) : StoredValue

    data object Corrupt : StoredValue

    data object Failed : StoredValue
}

/**
 * 加密的键值存储：值用 [cipher] 加密后 Base64 存进 SharedPreferences（[CouliPreferenceNamespaces.SESSION]，切换环境时随命名空间清）。
 * [get] 对解不开的值（密钥已删、数据损坏）按不存在处理并删除；[getStrict] 不删、抛 [UnreadableRecordException]；
 * [read] 区分不存在、内容损坏与读取失败（APP-03b）。
 *
 * 写入一律 commit。Android 的 SharedPreferences 在 commit 写盘之前就已改了内存值：commit 返回 false 时这里先把本次改过的键
 * 写回上一次成功提交的值，并把这些键的「最后成功提交值」记在 [committed] 里，之后的读取以它为准（R3-05：写盘失败不让
 * 已经改掉的内存值误导后续判断），然后抛错。之后对这些键的写入成功提交后解除。
 */
class EncryptedPreferences(
    context: Context,
    private val cipher: SecretCipher,
    name: String = CouliPreferenceNamespaces.SESSION,
    /** 底层 SharedPreferences（单测注入能模拟写盘失败的实现）；缺省按 [name] 取。 */
    backing: SharedPreferences? = null,
) {
    private val preferences: SharedPreferences =
        backing ?: context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    /**
     * 写盘失败后被隔离的键 → 最后成功提交的原始值（null = 不存在）。值保留底层的原始类型（第 1 轮评审 R1-01：类型不对的撤销标记也是「存在」，
     * 不能记成不存在）；不是字符串的读取时同样按类型错（[ClassCastException]）。
     */
    private val committed = mutableMapOf<String, Any?>()

    private fun raw(key: String): String? = if (key in committed) committed[key] as String? else preferences.getString(key, null)

    /** 宽松读取：内容损坏的删除后按不存在；读取失败（[StoredValue.Failed]）按不存在返回但不删除。需要区分时用 [read]。 */
    @Synchronized
    fun get(key: String): String? =
        when (val value = read(key)) {
            is StoredValue.Present -> {
                value.text
            }

            StoredValue.Corrupt -> {
                remove(key)
                null
            }

            StoredValue.Absent, StoredValue.Failed -> {
                null
            }
        }

    /** 读出明文，区分不存在、内容损坏与读取失败（APP-03b：读取失败不当成没有，也不删除）。 */
    @Synchronized
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount") // 解密失败一律归入损坏，读取失败单独返回
    fun read(key: String): StoredValue {
        val stored =
            try {
                raw(key)
            } catch (error: ClassCastException) {
                return StoredValue.Corrupt
            } ?: return StoredValue.Absent
        val bytes = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull() ?: return StoredValue.Corrupt
        return try {
            StoredValue.Present(cipher.decrypt(bytes).toString(Charsets.UTF_8))
        } catch (error: SecureStorageReadException) {
            StoredValue.Failed
        } catch (error: Exception) {
            StoredValue.Corrupt
        }
    }

    /** 读出明文；不存在为 null；解不开抛 [UnreadableRecordException]（不删除，lessons 38）。 */
    @Synchronized
    fun getStrict(key: String): String? {
        val stored = runCatching { raw(key) }.getOrElse { throw UnreadableRecordException("加密存储的值类型不对") } ?: return null
        val plain =
            runCatching { cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)) }.getOrNull()
                ?: throw UnreadableRecordException("加密存储的值解不开")
        return plain.toString(Charsets.UTF_8)
    }

    @Synchronized
    fun putAll(values: Map<String, String?>) {
        write(
            values.mapValues { (_, value) ->
                value?.let { Base64.encodeToString(cipher.encrypt(it.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) }
            },
        )
    }

    @Synchronized
    fun keys(): Set<String> {
        val present = preferences.all.keys.toMutableSet()
        committed.forEach { (key, value) -> if (value == null) present -= key else present += key }
        return present
    }

    @Synchronized
    fun remove(vararg keys: String) {
        write(keys.associateWith { null })
    }

    /** 整组删除（同 [remove]，一次提交）。 */
    @Synchronized
    fun removeAll(keys: Collection<String>) {
        write(keys.associateWith { null })
    }

    /** 按原始（已加密）值整组写入；写盘失败恢复内存值并隔离这些键（R3-05）。 */
    @SuppressLint("ApplySharedPref") // 凭据与未决记录必须同步落盘
    private fun write(changes: Map<String, String?>) {
        // 旧值按底层原始类型记下（第 1 轮评审 R1-01）：类型不对的值（如整数的撤销标记）回滚时原样写回，不当成「不存在」删掉
        val stored = preferences.all
        val previous = changes.keys.associateWith { if (it in committed) committed[it] else stored[it] }
        if (preferences.edit().put(changes).commit()) {
            committed.keys.removeAll(changes.keys)
            return
        }
        // 写盘失败：SharedPreferences 的内存值可能已改。写回上一次成功提交的值；不论这次写回是否落盘，读取都以 committed 为准
        val restored = runCatching { preferences.edit().put(previous).commit() }.getOrDefault(false)
        if (restored) committed.keys.removeAll(changes.keys) else committed.putAll(previous)
        error("写入加密存储失败")
    }

    /** 按原始类型写入；认不出的类型抛错（回滚按失败处理，旧值留在 [committed] 里，不会被当成不存在）。 */
    private fun SharedPreferences.Editor.put(values: Map<String, Any?>): SharedPreferences.Editor =
        apply {
            values.forEach { (key, value) ->
                when (value) {
                    null -> remove(key)
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Set<*> -> putStringSet(key, value.map { it as String }.toSet())
                    else -> error("加密存储里有认不出类型的值：$key")
                }
            }
        }
}

/** 一次读出的会话（同一把锁下读，[generation] 与各字段一致）。 */
data class SessionSnapshot(
    val accessToken: String?,
    val refreshToken: String?,
    val scope: String?,
    val userId: String?,
    val generation: Long,
)

/**
 * 一次完整的会话读取（APP-03b，与 iOS 同一裁定）：[Present] 读到了（[SessionSnapshot] 四项可能都为空 = 没有会话；内容损坏的条目
 * 按没有并尽力删除）；[Unreadable] 读取失败（可能有会话）：不能当成没有会话匿名发出，需要会话的接口本地拒绝（session_unreadable）。
 */
sealed interface SessionRead {
    data class Present(
        val snapshot: SessionSnapshot,
    ) : SessionRead

    data class Unreadable(
        val generation: Long,
    ) : SessionRead
}

/**
 * 能区分「读取失败」的会话存储（APP-03b session_unreadable）：[KeystoreSessionTokens] 实现它。没实现的 [SessionTokens]（内存实现、
 * 测试替身）按 [SessionTokens.snapshot] 读到处理（[readSessionOf]）。
 */
interface SessionReader {
    /** 一次读出完整会话，区分读取失败。 */
    fun readSession(): SessionRead
}

/** 一次读出完整会话：实现了 [SessionReader] 的按它，否则按 [SessionTokens.snapshot] 读到。 */
fun SessionTokens.readSessionOf(): SessionRead = (this as? SessionReader)?.readSession() ?: SessionRead.Present(snapshot())

/**
 * 会话令牌（03 §4.2；BR-ID-07）：access、refresh、当前会话的作用域（session_scope）与登录时记下的账号（LoginData.user_id）。
 * 本任务只做读写与清除，登录流程在 APP-05。
 *
 * [generation] 是本进程里的会话世代：每次 [save]、[clear]（含撤回同意、切换环境的清理）都加 1。刷新令牌在发出前记下世代，
 * 响应回来时用 [replaceIf] 只在世代未变时写入（R1-07：迟到的刷新结果不能复活已清除的会话），失败用 [clearIf] 只清那一次的会话
 * （R1-06：同一批 10002 只清一次，也不误清之后新登录的会话）。
 */
interface SessionTokens {
    val accessToken: String?
    val refreshToken: String?

    /** full / deletion_only；没有会话为 null。 */
    val scope: String?

    /** 登录时记下的账号（user_id）；四个 step-up 操作的未决记录按它隔离（R1-11）。没有会话或没有记下为 null。 */
    val userId: String?

    /** 会话世代（本进程内单调递增）。 */
    val generation: Long

    /**
     * 登录世代（本进程内单调递增）：每次登录（[save] / [saveIf]）与清除（[clear] / [clearIf]）加 1，刷新令牌（[replaceIf]）不变。
     * 桥调用按它绑定会话（APP-04 三端共性第 15 条：同一账号退出后重新登录也是新的世代；刷新不算换会话）。缺省用 [generation]（保守：
     * 刷新也算换代）。
     */
    val loginEpoch: Long get() = generation

    fun snapshot(): SessionSnapshot

    /** 登录：整组替换（[userId] 为登录响应的 data.user_id）。 */
    fun save(
        accessToken: String,
        refreshToken: String,
        scope: String,
        userId: String? = null,
    )

    fun clear()

    /** 刷新：世代仍是 [expectedGeneration] 时才替换令牌与作用域（账号沿用），返回是否写入。 */
    fun replaceIf(
        expectedGeneration: Long,
        accessToken: String,
        refreshToken: String,
        scope: String,
    ): Boolean

    /** 世代仍是 [expectedGeneration] 时才清除，返回是否清除。 */
    fun clearIf(expectedGeneration: Long): Boolean

    /**
     * 登录：世代仍是 [expectedGeneration]（登录请求发出时的那一代）时才整组替换，返回是否写入（A-R2-02：迟到的登录响应不覆盖
     * 已清除或新建立的会话）。缺省实现按「读世代 → 保存」，两个内置实现在同一把锁里原子完成。
     */
    fun saveIf(
        expectedGeneration: Long,
        accessToken: String,
        refreshToken: String,
        scope: String,
        userId: String?,
    ): Boolean {
        if (generation != expectedGeneration) return false
        save(accessToken, refreshToken, scope, userId)
        return true
    }
}

/**
 * 持久化登录世代（F-AND-25 / F-AND-29，FU-AND-0714；三端口径见 FU-HM-0711c decision.md）：一个不透明标识，**与令牌整组同一次原子写入**令牌存储——
 * 每次登录（[SessionTokens.save] / [SessionTokens.saveIf]）换新，刷新令牌（[SessionTokens.replaceIf]）沿用，清除（[SessionTokens.clear]）随令牌一起删。
 * 同一账号退出后重新登录也是新的世代。订单缓存、钱包摘要缓存、订单页恢复记录按「账号 + 它」绑定归属：清理标记与删除都失败、残留的旧数据，在同账号
 * 重新登录（或进程重建）之后也因世代不同而永久读不回。
 *
 * 不是凭据（不发给服务端、不参与签名）；只用于本机缓存的归属比较。
 */
interface PersistedLoginEpochs {
    /**
     * 当前会话的持久化登录世代；没有会话、这份会话没有记下世代（升级前登录）、读不出或内容损坏都为 null（调用方按未知：不读缓存、不恢复）。
     * 口径与**整份会话的可用性**相同（第 2 轮评审 R2-02）：任一会话字段读不出、损坏（含损坏清理失败）、会话隔离或已撤销（R2-03）都为 null。
     */
    fun persistedLoginEpoch(): String?

    /**
     * 交给凭据读取屏障（第 2 轮评审 R2-01 / R2-02）：之后每次 [persistedLoginEpoch] 都包在 [guard] 里（ApiClient 构造时接：屏障内现判凭据可读、
     * 会话没隔离才读，否则按未知 null）。整份会话可用性要解密令牌，所以它也是令牌读取点。缺省不接（内存实现、单测）。
     */
    fun guardLoginEpochReads(guard: (read: () -> String?) -> String?) = Unit
}

/** 内存实现（测试、不落盘的场景）。 */
class MemorySessionTokens(
    accessToken: String? = null,
    refreshToken: String? = null,
    scope: String? = null,
    userId: String? = null,
    /** 新的持久化登录世代（[PersistedLoginEpochs]）。 */
    private val newLoginEpoch: () -> String = { UUID.randomUUID().toString() },
) : SessionTokens,
    PersistedLoginEpochs {
    private var current = SessionSnapshot(accessToken, refreshToken, scope, userId, 0L)
    private var epoch = 0L

    /** 持久化登录世代（内存实现：随会话一起换）；构造时带了令牌的视为一次登录。 */
    private var persisted: String? = if (accessToken != null || refreshToken != null) newLoginEpoch() else null

    @Synchronized
    override fun persistedLoginEpoch(): String? = persisted

    override val loginEpoch: Long
        @Synchronized get() = epoch

    override val accessToken: String? get() = snapshot().accessToken
    override val refreshToken: String? get() = snapshot().refreshToken
    override val scope: String? get() = snapshot().scope
    override val userId: String? get() = snapshot().userId
    override val generation: Long get() = snapshot().generation

    @Synchronized
    override fun snapshot(): SessionSnapshot = current

    @Synchronized
    override fun save(
        accessToken: String,
        refreshToken: String,
        scope: String,
        userId: String?,
    ) {
        current = SessionSnapshot(accessToken, refreshToken, scope, userId, current.generation + 1)
        epoch += 1
        persisted = newLoginEpoch()
    }

    @Synchronized
    override fun clear() {
        current = SessionSnapshot(null, null, null, null, current.generation + 1)
        epoch += 1
        persisted = null
    }

    @Synchronized
    override fun replaceIf(
        expectedGeneration: Long,
        accessToken: String,
        refreshToken: String,
        scope: String,
    ): Boolean {
        if (current.generation != expectedGeneration) return false
        current = SessionSnapshot(accessToken, refreshToken, scope, current.userId, expectedGeneration + 1)
        return true
    }

    @Synchronized
    override fun clearIf(expectedGeneration: Long): Boolean {
        if (current.generation != expectedGeneration) return false
        clear()
        return true
    }

    @Synchronized
    override fun saveIf(
        expectedGeneration: Long,
        accessToken: String,
        refreshToken: String,
        scope: String,
        userId: String?,
    ): Boolean {
        if (current.generation != expectedGeneration) return false
        save(accessToken, refreshToken, scope, userId)
        return true
    }
}

/**
 * 令牌的安全存储：Android Keystore 的 AES 密钥加密后存 couli.session（[EncryptedPreferences]）。
 * 键名沿用 APP-02b 约定的 access_token、refresh_token（撤回同意的占位清理按键名删，切换环境时整个命名空间清）。
 * 世代只在内存里（在途的刷新也只在本进程）；写入失败时世代照样前进（落盘结果不可信，旧刷新结果一律作废）。
 *
 * FU-AND-0715（F-AND-32 + 补充，对照鸿蒙 FU-HM-0715 R1-01..04）：
 * - 持久化会话完整校验（[invalid]）：令牌非空、作用域属合法枚举、字段类型正确、整组齐全；任一不满足整组按内容损坏隔离（不加载、世代 null）。
 * - 撤销标记在删除令牌之前落盘：同文件标记 [KEY_REVOKED] 提交失败时，先把独立介质 [marks] 的标记落盘再删令牌；独立介质的标记带完整性校验
 *   （[SessionRevocation]：空、截断、摘要不符一律按撤销），冷启动读到令牌后核对。
 * - 清会话路径不读令牌：撤销的是哪份会话取自装载 / 保存时记下的世代（[knownEpoch]），没有就写「任何会话」标记。
 * - 自己发现会话不能用并丢弃时推进会话世代并同步通知（[SessionSelfDiscards]，F-AND-33）。
 */
@Suppress("TooManyFunctions", "LargeClass") // 会话读写四组 + 持久化登录世代与其屏障接线 + 独立介质撤销标记
class KeystoreSessionTokens(
    private val storage: EncryptedPreferences,
    /** 令牌文件之外的撤销标记介质（F-AND-32；App 里是 noBackupFilesDir 下的文件）；null = 只用同文件标记（单测、旧用法）。 */
    private val marks: RevocationMarks? = null,
    /** 新的持久化登录世代（[PersistedLoginEpochs]；单测注入固定序列）。 */
    private val newLoginEpoch: () -> String = { UUID.randomUUID().toString() },
) : SessionTokens,
    SessionReader,
    PersistedLoginEpochs,
    SessionSelfDiscards {
    private val lock = Any()

    @Volatile
    private var counter = 0L

    @Volatile
    private var epoch = 0L

    /**
     * 持久化登录世代暂不可用（F-AND-25，第 1 轮评审 R1-02）：与会话可用性共用状态——清除会话（含按世代清除）一开始就置上，删除提交失败
     * （会话被隔离，EncryptedPreferences 里还是旧值）、刷新写入失败（ApiClient 随之隔离并清除）、会话内容损坏而删除失败（R2-02）时保持；只有成功
     * 保存一份新会话（登录）才解除。置上期间 [persistedLoginEpoch] 一律为 null（订单缓存、钱包摘要缓存、订单页恢复记录、会员首页 LKG 不读不写）。
     * 它只在本进程内；跨进程由落盘的撤销标记 [KEY_REVOKED] 与独立介质 [marks] 承担（R2-03、F-AND-32）。
     */
    @Volatile
    private var epochWithheld = false

    /** 本进程已经结束了当前这份会话（清除、刷新写入失败、自己丢弃）：之后再读到撤销 / 损坏不重复推进世代。只有保存新会话解除。只在 [lock] 下读写。 */
    private var ended = false

    /**
     * 令牌项里此刻是哪一次登录（F-AND-32 补充，对照鸿蒙 R1-04 storedFingerprint）：装载读到完整会话、保存成功、确认删除时记下；读不出、损坏、
     * 保存抛错时为未知（[epochKnown] = false）。清除时据此写独立介质的标记，不为它再读令牌。只在 [lock] 下读写。
     */
    private var epochKnown = false
    private var knownEpoch: String? = null

    /** 独立介质里可能还有标记（未核对过、写过、删不掉）。false = 已确认没有。只在 [lock] 下读写。 */
    private var marksMaybePresent = true

    /** 凭据读取屏障（[guardLoginEpochReads]）；没接时直接读。 */
    @Volatile
    private var epochGuard: ((() -> String?) -> String?)? = null

    @Volatile
    private var discardListener: (() -> Unit)? = null

    override fun guardLoginEpochReads(guard: (read: () -> String?) -> String?) {
        epochGuard = guard
    }

    override fun onSelfDiscarded(listener: () -> Unit) {
        discardListener = listener
    }

    override val loginEpoch: Long get() = epoch

    override val accessToken: String? get() = snapshot().accessToken
    override val refreshToken: String? get() = snapshot().refreshToken
    override val scope: String? get() = snapshot().scope
    override val userId: String? get() = snapshot().userId
    override val generation: Long get() = counter

    /** 读取失败时按没有会话给出（不删除）；需要区分读取失败的地方用 [readSession]。 */
    override fun snapshot(): SessionSnapshot =
        when (val read = readSession()) {
            is SessionRead.Present -> read.snapshot
            is SessionRead.Unreadable -> SessionSnapshot(null, null, null, null, read.generation)
        }

    /**
     * 同一把锁里读会话各项：任何一项读取失败 → [SessionRead.Unreadable]（不删除）；任何一项内容损坏或校验不通过（[invalid]）→ 整组不能用，尽力删除
     * 后按没有会话（删不掉下次读还会再试，本进程登录世代保持禁用并落撤销标记）；否则读到的会话。
     *
     * 落盘的撤销标记在先：同文件标记 [KEY_REVOKED] 存在（含损坏、空值）= 这份会话已被清除、只是删除没落盘（可能是上一个进程），按没有会话
     * 并重试删除（连同标记，成功即清干净）；读到令牌后再核对独立介质 [marks]（F-AND-32：令牌文件整体写不进时的撤销）。任一标记读取失败按读不出。
     */
    override fun readSession(): SessionRead = synchronized(lock) { readLocked().read }

    /** 一次完整读取：[read] 给调用方；[epoch] 是会话可用时它的持久化登录世代（升级前登录为 null）。 */
    private class Loaded(
        val read: SessionRead,
        val epoch: String? = null,
    )

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "SwallowedException") // 撤销标记、读取失败、损坏、独立介质标记逐项；标记读不出按会话读不出
    private fun readLocked(): Loaded {
        when (storage.read(KEY_REVOKED)) {
            StoredValue.Absent -> {
                Unit
            }

            StoredValue.Failed -> {
                return Loaded(SessionRead.Unreadable(counter))
            }

            else -> {
                return Loaded(discardLocked(revokedAlready = true, byLocalMark = true))
            }
        }
        val values = STORED_KEYS.associateWith(storage::read)
        if (values.values.any { it == StoredValue.Failed }) return Loaded(SessionRead.Unreadable(counter))

        fun text(key: String) = (values[key] as? StoredValue.Present)?.text
        if (values.values.any { it == StoredValue.Corrupt } || invalid(values)) {
            // 损坏 / 校验不通过：整组隔离（不加载）。删除提交失败时登录世代保持禁用（本进程），并在删除之前落撤销标记（重建后同样按已撤销）
            epochKnown = false
            return Loaded(discardLocked(revokedAlready = false))
        }
        val access = text(KEY_ACCESS)
        val loginEpoch = text(KEY_LOGIN_EPOCH)
        if (access != null) {
            // 读到令牌：核对独立介质的撤销标记（F-AND-32）；读不出 → 会话读不出（不交出旧令牌）
            val blocked =
                try {
                    marksBlock(loginEpoch)
                } catch (error: IOException) {
                    return Loaded(SessionRead.Unreadable(counter))
                }
            if (blocked) return Loaded(discardLocked(revokedAlready = true))
        }
        epochKnown = true
        knownEpoch = loginEpoch.takeIf { access != null }
        val snapshot = SessionSnapshot(access, text(KEY_REFRESH), text(KEY_SCOPE), text(KEY_USER_ID), counter)
        return Loaded(SessionRead.Present(snapshot), knownEpoch)
    }

    /**
     * 持久化会话的完整校验（F-AND-32 补充，鸿蒙 R1-01）：access / refresh / session_scope 要么都不在（没有会话；此时账号与世代也不能残留），要么都在；
     * 令牌非空，作用域只能是 full / deletion_only，账号与世代在时非空。类型不对（不是字符串）在 [EncryptedPreferences.read] 已按损坏。
     */
    private fun invalid(values: Map<String, StoredValue>): Boolean {
        fun text(key: String) = (values[key] as? StoredValue.Present)?.text
        val core = listOf(KEY_ACCESS, KEY_REFRESH, KEY_SCOPE).map(::text)
        if (core.all { it == null }) return text(KEY_USER_ID) != null || text(KEY_LOGIN_EPOCH) != null
        return core.any { it.isNullOrEmpty() } ||
            text(KEY_SCOPE) !in VALID_SCOPES ||
            text(KEY_USER_ID)?.isEmpty() == true ||
            text(KEY_LOGIN_EPOCH)?.isEmpty() == true
    }

    /** 独立介质里有没有阻断这份会话（世代 [sessionEpoch]）的标记；读不出抛 [IOException]。确认没有时记下，之后不再读文件。 */
    @Suppress("ReturnCount") // 没接介质、已确认没有、读到空三个出口
    private fun marksBlock(sessionEpoch: String?): Boolean {
        val source = marks ?: return false
        if (!marksMaybePresent) return false
        val texts = source.read()
        if (texts.isEmpty()) {
            marksMaybePresent = false
            return false
        }
        return texts.any { SessionRevocation.blocks(it, sessionEpoch) }
    }

    /**
     * 自己丢弃这份会话（被撤销标记阻断、内容损坏或校验不通过）：登录世代禁用，第一次丢弃时推进会话世代与登录世代并同步通知（F-AND-33）；
     * 尽力整组删除——删除失败且不是已经有撤销标记的，先落撤销标记（删除之前已经失败了一次，标记兜住重建）。返回「没有会话」。
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun discardLocked(
        revokedAlready: Boolean,
        byLocalMark: Boolean = false,
    ): SessionRead {
        epochWithheld = true
        if (!ended) {
            ended = true
            counter += 1
            epoch += 1
            runCatching { discardListener?.invoke() }
        }
        // 阻断这份会话的是同文件标记（任何内容、任何类型）：下面的整组删除会连它一起删，删除失败的回滚若没能原样写回它，令牌就失去撤销记录
        // （第 1 轮评审 R1-01）。所以在删除之前先在独立介质落一份「任何会话」标记（不读令牌）；删除确认成功后随之删掉
        if (byLocalMark) writeMarkLocked(SessionRevocation.ANY)
        val deleted = runCatching { storage.removeAll(ALL_KEYS) }.isSuccess
        if (deleted) {
            confirmDeletedLocked()
        } else if (!revokedAlready) {
            markRevokedLocked(SessionRevocation.ANY)
        }
        return SessionRead.Present(SessionSnapshot(null, null, null, null, counter))
    }

    /** 令牌项确认已删：记下「没有会话」，尽力删独立介质的标记（已不需要保护任何旧会话）。 */
    private fun confirmDeletedLocked() {
        epochKnown = true
        knownEpoch = null
        val source = marks ?: return
        if (runCatching { source.delete() }.isSuccess) marksMaybePresent = false
    }

    /**
     * 在删除令牌之前落撤销标记（鸿蒙 R1-02）：先提交同文件标记；它提交失败（令牌文件整体写不进）时先把独立介质的标记落盘。返回同文件标记是否落盘。
     */
    private fun markRevokedLocked(target: String): Boolean {
        val local = runCatching { storage.putAll(mapOf(KEY_REVOKED to REVOKED)) }.isSuccess
        if (!local) writeMarkLocked(target)
        return local
    }

    private fun writeMarkLocked(target: String) {
        val source = marks ?: return
        marksMaybePresent = true
        runCatching { source.write(SessionRevocation.encode(target)) }
    }

    /** 清除时撤销的是哪份会话：取自记下的世代（不读令牌，鸿蒙 R1-04）；不知道就是「任何会话」。 */
    private fun revocationTargetLocked(): String = if (epochKnown) SessionRevocation.revoking(knownEpoch) else SessionRevocation.ANY

    /**
     * 持久化登录世代（F-AND-25）：读 [KEY_LOGIN_EPOCH]（与令牌同一次原子写入）。口径与整份会话可用性统一（第 2 轮评审 R2-02）：在同一把锁里
     * 完整读一次会话（[readLocked]）——任一字段读不出、损坏或校验不通过（删除失败同样保持禁用）、已撤销（落盘标记，R2-03 / F-AND-32）、没有令牌、
     * 本进程隔离中（[epochWithheld]）都为 null；会话可用时才给出世代，它不存在（升级前登录）也为 null。读不出不删除。
     * 接了凭据读取屏障时整个读取在屏障里（[guardLoginEpochReads]）。
     */
    override fun persistedLoginEpoch(): String? {
        val guard = epochGuard ?: return epochNow()
        return guard(::epochNow)
    }

    @Suppress("ReturnCount") // 禁用、会话不可用、没有令牌逐项
    private fun epochNow(): String? =
        synchronized(lock) {
            if (epochWithheld) return null
            val loaded = runCatching { readLocked() }.getOrNull() ?: return null
            val read = loaded.read as? SessionRead.Present ?: return null
            if (epochWithheld) return null
            val session = read.snapshot
            if (session.accessToken == null && session.refreshToken == null) return null
            loaded.epoch?.takeIf { it.isNotEmpty() }
        }

    override fun save(
        accessToken: String,
        refreshToken: String,
        scope: String,
        userId: String?,
    ) = synchronized(lock) {
        val renewed = newLoginEpoch()
        // 保存没确认落盘之前，令牌项里是哪份会话未知（失败时 EncryptedPreferences 恢复旧值，但不再据此写精确标记）
        epochKnown = false
        try {
            // 登录：令牌整组与新的持久化登录世代同一次 commit（原子：要么都落盘、要么都恢复旧值，见 [EncryptedPreferences]）
            // 同一次提交里删掉撤销标记（R2-03：新会话确认落盘才解除）
            storage.putAll(
                mapOf(
                    KEY_ACCESS to accessToken,
                    KEY_REFRESH to refreshToken,
                    KEY_SCOPE to scope,
                    KEY_USER_ID to userId,
                    KEY_LOGIN_EPOCH to renewed,
                    KEY_REVOKED to null,
                ),
            )
            // 新会话（连同新世代）确认落盘：解除暂不可用
            epochWithheld = false
            ended = false
            epochKnown = true
            knownEpoch = renewed
            settleMarksAfterLoginLocked(renewed)
        } finally {
            counter += 1
            epoch += 1
        }
    }

    /**
     * 新登录落盘之后的独立介质标记（F-AND-32；鸿蒙 FU-HM-0717 第 3 项）：先尽力删掉；删不掉时，会阻断新登录的（`*`、不完整、`s:` 别的世代）改写成
     * 「除了这次登录一律撤销」（`s:<新世代>`），对不上新登录的精确旧标记不动；改写也失败就保持原样（偏保守：重建后需要重新登录）。
     */
    @Suppress("ReturnCount") // 没接介质、已确认没有、删掉了、读不出逐项
    private fun settleMarksAfterLoginLocked(renewed: String) {
        val source = marks ?: return
        if (!marksMaybePresent) return
        if (runCatching { source.delete() }.isSuccess) {
            marksMaybePresent = false
            return
        }
        val texts = runCatching { source.read() }.getOrNull() ?: return
        if (texts.any { SessionRevocation.blocks(it, renewed) }) {
            runCatching { source.write(SessionRevocation.encode(SessionRevocation.supersededBy(renewed))) }
        }
    }

    override fun clear() =
        synchronized(lock) {
            // 清除一开始就让登录世代不可用（删除提交失败时旧值还在存储里，也不再交给缓存归属判断）
            epochWithheld = true
            ended = true
            try {
                // 跨进程撤销（第 2 轮评审 R2-03、F-AND-32）：删除令牌之前先落盘撤销标记——同文件标记提交失败（令牌文件整体写不进）时先落独立介质的标记；
                // 撤销的是哪份会话取自记下的世代，不为它读令牌。再整组删除（连同同文件标记）
                val target = revocationTargetLocked()
                val local = markRevokedLocked(target)
                runCatching { storage.removeAll(ALL_KEYS) }.exceptionOrNull()?.let { error ->
                    // 删除失败：同文件标记已落盘时独立介质也补一份（同文件标记之后可能被别的写入路径冲掉）
                    if (local) writeMarkLocked(target)
                    throw error
                }
                confirmDeletedLocked()
            } finally {
                counter += 1
                epoch += 1
            }
        }

    override fun replaceIf(
        expectedGeneration: Long,
        accessToken: String,
        refreshToken: String,
        scope: String,
    ): Boolean =
        synchronized(lock) {
            if (counter != expectedGeneration) return false
            var written = false
            try {
                storage.putAll(mapOf(KEY_ACCESS to accessToken, KEY_REFRESH to refreshToken, KEY_SCOPE to scope))
                written = true
            } finally {
                // 刷新写入失败：ApiClient 隔离并清除这一代会话；登录世代同步不可用（R1-02），并尽力落撤销标记（R2-03：ApiClient 的清除重试可能
                // 因凭据此刻不可读而不执行，重建后也不再用这份会话的缓存）
                if (!written) {
                    epochWithheld = true
                    ended = true
                    markRevokedLocked(revocationTargetLocked())
                }
                counter += 1
            }
            true
        }

    override fun clearIf(expectedGeneration: Long): Boolean =
        synchronized(lock) {
            if (counter != expectedGeneration) return false
            clear()
            true
        }

    override fun saveIf(
        expectedGeneration: Long,
        accessToken: String,
        refreshToken: String,
        scope: String,
        userId: String?,
    ): Boolean =
        synchronized(lock) {
            if (counter != expectedGeneration) return false
            save(accessToken, refreshToken, scope, userId)
            true
        }

    companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_SCOPE = "session_scope"
        const val KEY_USER_ID = "session_user_id"

        /** 持久化登录世代（F-AND-25）：与令牌同一次原子写入；刷新不动它。 */
        const val KEY_LOGIN_EPOCH = "session_login_epoch"

        /**
         * 落盘的会话撤销标记（第 2 轮评审 R2-03）：清除会话时先单独提交，整组删除成功时随之删掉；新会话保存时同一次提交删掉。存在（不论内容，
         * 含空值、损坏）= 这份会话已被清除（删除没落盘），读会话按没有、登录世代为 null。不是凭据。
         */
        const val KEY_REVOKED = "session_revoked"

        /** 独立介质撤销标记的文件名（F-AND-32；App 放在 noBackupFilesDir）。 */
        const val REVOCATION_FILE = "couli_session.revoked"
        private const val REVOKED = "1"
        private val VALID_SCOPES = setOf(SessionScopeRaw.FULL, SessionScopeRaw.DELETION_ONLY)
        private val STORED_KEYS = listOf(KEY_ACCESS, KEY_REFRESH, KEY_SCOPE, KEY_USER_ID, KEY_LOGIN_EPOCH)
        private val ALL_KEYS = listOf(KEY_ACCESS, KEY_REFRESH, KEY_SCOPE, KEY_USER_ID, KEY_LOGIN_EPOCH, KEY_REVOKED)
    }
}

/**
 * 未决记录的安全存储（请求体可能含账号或手机号，BR-ID-10）：每条记录整体加密，存 couli.session 的 `pending_key.<账号>.<键>`。
 * 切换环境时随命名空间清；撤回同意清令牌时不删（记录按账号隔离，不读不发别的账号的记录）。
 * 读取失败（解不开、损坏）抛 [UnreadableRecordException]：调用方按「无法确认未决状态」阻止新的提交（lessons 38）。
 */
class SecurePendingKeyBackend(
    private val storage: EncryptedPreferences,
) : PendingKeyBackend {
    override fun read(
        account: String,
        key: String,
    ): PendingKeyRecord? = storage.getStrict(storageKey(account, key))?.let(::decodeStrict)

    override fun list(account: String): List<PendingKeyRecord> {
        val prefix = "$PREFIX${encode(account)}."
        // 只解密本账号前缀下的记录（lessons 33）；任何一条解不开或不能解析都抛错，不当成「没有记录」（lessons 38）
        return storage
            .keys()
            .filter { it.startsWith(prefix) }
            .mapNotNull { storage.getStrict(it)?.let(::decodeStrict) }
            .filter { it.account == account }
    }

    override fun write(record: PendingKeyRecord) {
        val json =
            JsonObject(
                mapOf(
                    "key" to JsonPrimitive(record.key),
                    "operation_id" to JsonPrimitive(record.operationId),
                    "body" to JsonPrimitive(record.body),
                    "account" to JsonPrimitive(record.account),
                    "summary" to JsonPrimitive(record.summary),
                    "state" to JsonPrimitive(record.stateRaw),
                    "ever_unknown" to JsonPrimitive(record.everUnknown),
                    "created_at" to JsonPrimitive(record.createdAtMillis),
                ),
            )
        storage.putAll(mapOf(storageKey(record.account, record.key) to json.toString()))
    }

    override fun delete(
        account: String,
        key: String,
    ) = storage.remove(storageKey(account, key))

    private fun storageKey(
        account: String,
        key: String,
    ) = "$PREFIX${encode(account)}.${encode(key)}"

    private fun encode(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)

    private fun decodeStrict(text: String): PendingKeyRecord = decode(text) ?: throw UnreadableRecordException("未决记录不能解析")

    private fun decode(text: String): PendingKeyRecord? =
        runCatching {
            val json = Json.parseToJsonElement(text).jsonObject

            fun string(name: String) = json.getValue(name).jsonPrimitive.content
            PendingKeyRecord(
                key = string("key"),
                operationId = string("operation_id"),
                body = string("body"),
                account = string("account"),
                summary = string("summary"),
                stateRaw = string("state"),
                everUnknown = json.getValue("ever_unknown").jsonPrimitive.boolean,
                createdAtMillis = json.getValue("created_at").jsonPrimitive.long,
            )
        }.getOrNull()

    companion object {
        const val PREFIX = "pending_key."
    }
}
