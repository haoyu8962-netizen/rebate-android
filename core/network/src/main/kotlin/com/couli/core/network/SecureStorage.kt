package com.couli.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.couli.core.config.CouliPreferenceNamespaces
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.ProviderException
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

    /** 写盘失败后被隔离的键 → 最后成功提交的原始值（null = 不存在）。 */
    private val committed = mutableMapOf<String, String?>()

    private fun raw(key: String): String? = if (key in committed) committed[key] else preferences.getString(key, null)

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
        val stored = raw(key) ?: return StoredValue.Absent
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
        val stored = raw(key) ?: return null
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

    /** 按原始（已加密）值整组写入；写盘失败恢复内存值并隔离这些键（R3-05）。 */
    @SuppressLint("ApplySharedPref") // 凭据与未决记录必须同步落盘
    private fun write(changes: Map<String, String?>) {
        val previous = changes.keys.associateWith(::raw)
        if (preferences.edit().put(changes).commit()) {
            committed.keys.removeAll(changes.keys)
            return
        }
        // 写盘失败：SharedPreferences 的内存值可能已改。写回上一次成功提交的值；不论这次写回是否落盘，读取都以 committed 为准
        val restored = runCatching { preferences.edit().put(previous).commit() }.getOrDefault(false)
        if (restored) committed.keys.removeAll(changes.keys) else committed.putAll(previous)
        error("写入加密存储失败")
    }

    private fun SharedPreferences.Editor.put(values: Map<String, String?>): SharedPreferences.Editor =
        apply { values.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) } }
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

/** 内存实现（测试、不落盘的场景）。 */
class MemorySessionTokens(
    accessToken: String? = null,
    refreshToken: String? = null,
    scope: String? = null,
    userId: String? = null,
) : SessionTokens {
    private var current = SessionSnapshot(accessToken, refreshToken, scope, userId, 0L)
    private var epoch = 0L

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
    }

    @Synchronized
    override fun clear() {
        current = SessionSnapshot(null, null, null, null, current.generation + 1)
        epoch += 1
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
 */
class KeystoreSessionTokens(
    private val storage: EncryptedPreferences,
) : SessionTokens,
    SessionReader {
    private val lock = Any()

    @Volatile
    private var counter = 0L

    @Volatile
    private var epoch = 0L

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
     * 同一把锁里读四项：任何一项读取失败 → [SessionRead.Unreadable]（不删除）；任何一项内容损坏 → 整组不能用，尽力删除四项后按
     * 没有会话（删不掉下次读还会再试）；否则读到的会话。
     */
    override fun readSession(): SessionRead =
        synchronized(lock) {
            val values = SESSION_KEYS.associateWith(storage::read)
            when {
                values.values.any { it == StoredValue.Failed } -> {
                    SessionRead.Unreadable(counter)
                }

                values.values.any { it == StoredValue.Corrupt } -> {
                    runCatching { storage.remove(KEY_ACCESS, KEY_REFRESH, KEY_SCOPE, KEY_USER_ID) }
                    SessionRead.Present(SessionSnapshot(null, null, null, null, counter))
                }

                else -> {
                    fun text(key: String) = (values[key] as? StoredValue.Present)?.text
                    SessionRead.Present(SessionSnapshot(text(KEY_ACCESS), text(KEY_REFRESH), text(KEY_SCOPE), text(KEY_USER_ID), counter))
                }
            }
        }

    override fun save(
        accessToken: String,
        refreshToken: String,
        scope: String,
        userId: String?,
    ) = synchronized(lock) {
        try {
            storage.putAll(
                mapOf(KEY_ACCESS to accessToken, KEY_REFRESH to refreshToken, KEY_SCOPE to scope, KEY_USER_ID to userId),
            )
        } finally {
            counter += 1
            epoch += 1
        }
    }

    override fun clear() =
        synchronized(lock) {
            try {
                storage.remove(KEY_ACCESS, KEY_REFRESH, KEY_SCOPE, KEY_USER_ID)
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
            try {
                storage.putAll(mapOf(KEY_ACCESS to accessToken, KEY_REFRESH to refreshToken, KEY_SCOPE to scope))
            } finally {
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
        private val SESSION_KEYS = listOf(KEY_ACCESS, KEY_REFRESH, KEY_SCOPE, KEY_USER_ID)
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
