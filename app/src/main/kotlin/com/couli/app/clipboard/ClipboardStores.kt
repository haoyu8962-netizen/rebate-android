package com.couli.app.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.couli.core.clipboard.ClipboardFingerprint
import com.couli.core.clipboard.ClipboardHashStore
import com.couli.core.clipboard.ClipboardRecords
import com.couli.core.clipboard.ClipboardSaltRead
import com.couli.core.clipboard.ClipboardSaltStore
import com.couli.core.clipboard.ImportPreferenceBackend
import com.couli.core.clipboard.ImportPreferenceStore
import com.couli.core.clipboard.ImportPreferenceWrites
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.core.network.AndroidKeystoreCipher
import com.couli.core.network.EncryptedPreferences
import com.couli.core.network.SecretCipher
import com.couli.core.network.StoredValue
import kotlinx.serialization.json.JsonObject

/**
 * 剪贴板的本机记录（SharedPreferences [CouliPreferenceNamespaces.CLIPBOARD]，设备级：切换环境、登录、退出、换账号都不清）：
 * - 设备级开关「打开 App 自动识别剪贴板」（[importPreferences]，BR-ID-16 2026-10-30 版：默认开启、三态，存本机、不随账号变）。
 *   构造时做 C6 升级迁移（[migrateLegacySwitch]）。旧版桥 clipboard.setAutoDetect 写在同一个文件的 `auto_detect` 键，那是网页的临时抑制、不是用户的
 *   选择（G06），这里不读它；
 * - hash 记录（[hashes]：自写记录与 24 小时提示记录，FU-CLIP 起只存本机盐的 HMAC 指纹与时间，另记 saltId）与本机盐（[salt]，Keystore 包裹）。
 *   撤回同意时先删盐再删记录（[clearHashes]）；切换环境不清（与记录同一登记，K10）。
 */
class ClipboardPreferences internal constructor(
    private val prefs: SharedPreferences,
    /**
     * 盐的加密（FU-CLIP C3：Keystore 包裹，独立别名 [SALT_KEY_ALIAS]，切换环境销毁会话密钥时不受影响）。生产为 [AndroidKeystoreCipher]；
     * 单测注入假实现（Robolectric 没有 AndroidKeyStore）。
     */
    private val saltCipher: SecretCipher? = null,
) {
    constructor(context: Context) : this(context, AndroidKeystoreCipher(SALT_KEY_ALIAS))

    constructor(context: Context, saltCipher: SecretCipher) : this(
        context.applicationContext.getSharedPreferences(CouliPreferenceNamespaces.CLIPBOARD, Context.MODE_PRIVATE),
        saltCipher,
    )

    private val encrypted: EncryptedPreferences? =
        saltCipher?.let { cipher ->
            EncryptedPreferences(ContextWrapper(null), cipher, CouliPreferenceNamespaces.CLIPBOARD, backing = prefs)
        }

    /**
     * 本机随机盐（FU-CLIP §2.2 第 2 条）：32 字节，Base64 后经 Keystore 加密存进同一个偏好文件（键 [KEY_SALT]）。读失败（Keystore 暂时不可用）
     * 按不可用，不当成没有；内容损坏（密钥已删、数据坏）按没有（之后重新生成，旧记录按 saltId 作废）。
     */
    val salt: ClipboardSaltStore =
        object : ClipboardSaltStore {
            override fun read(): ClipboardSaltRead {
                val store = encrypted ?: return ClipboardSaltRead.Failed
                return when (val value = runCatching { store.read(KEY_SALT) }.getOrDefault(StoredValue.Failed)) {
                    is StoredValue.Present -> {
                        runCatching { Base64.decode(value.text, Base64.NO_WRAP) }
                            .getOrNull()
                            ?.takeIf { it.size == ClipboardFingerprint.SALT_BYTES }
                            ?.let { ClipboardSaltRead.Present(it) } ?: ClipboardSaltRead.Absent
                    }

                    StoredValue.Absent, StoredValue.Corrupt -> {
                        ClipboardSaltRead.Absent
                    }

                    StoredValue.Failed -> {
                        ClipboardSaltRead.Failed
                    }
                }
            }

            override fun write(salt: ByteArray): Boolean {
                val store = encrypted ?: return false
                return runCatching { store.putAll(mapOf(KEY_SALT to Base64.encodeToString(salt, Base64.NO_WRAP))) }.isSuccess
            }

            override fun delete(): Boolean = deleteSalt()
        }

    /** 删盐（撤回同意的清理第一步；不需要密钥）：同步落盘并核对 commit()。 */
    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun deleteSalt(): Boolean = runCatching { prefs.edit().remove(KEY_SALT).commit() }.getOrDefault(false)

    /** 开关此刻是否为「开」（未选择 = 开）。 */
    val autoDetect: Boolean get() = importPreferences().isAutoSwitchOn()

    /**
     * 开关的本机存储（同一偏好文件，键见 [ImportPreferenceStore]）：同步 commit() 落盘，核对返回值（G35）。每次调用给一个新的视图；
     * 所有视图共用同一份「还没落盘的意图」与重试（[importWrites]），读到的一致，排队的重试也只有一份。
     */
    fun importPreferences(clock: () -> Long = System::currentTimeMillis): ImportPreferenceStore =
        ImportPreferenceStore(importBackend, clock, writes = importWrites, scheduleDelayedWriteRetry = ::scheduleImportRetry)

    private val importWrites = ImportPreferenceWrites()

    private val importBackend =
        object : ImportPreferenceBackend {
            override fun read(key: String): String? = runCatching { prefs.getString(key, null) }.getOrNull()

            @SuppressLint("ApplySharedPref", "UseKtx")
            override fun write(changes: Map<String, String?>): Boolean {
                val editor = prefs.edit()
                changes.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
                return runCatching { editor.commit() }.getOrDefault(false)
            }
        }

    /** 已排队、还没执行完的开关写盘重试（主线程 Looper；测试与诊断观测用，只读）。 */
    internal var pendingImportWriteRetries: Int = 0
        private set

    private val importRetryHandler by lazy { Handler(Looper.getMainLooper()) }

    init {
        migrateLegacySwitch()
    }

    /**
     * C6 升级迁移（变更记录 §3，BR-ID-16 2026-10-30 版）：两代旧数据映射到新开关，一次提交、旧键随之删掉，所以天然只做一次、不写「已迁移」标记。
     * 已有 `import.enabled`（on / off，FU-CLIP 版）→ 原样保留（③ ④）；缺它时 `device_auto_detect=false`（2026-10-17 前的开关，③）或
     * `import.notice_seen_version` 有值（FU-CLIP 版旧弹层选过【暂不】，②）→ 写关闭；其余 → 保持未选择（①，按开启）。除 `import.enabled` 外的
     * `import.*`（同意版本与时刻、看过、遗留待上报队列，C3 / C4）和旧两态开关键一律清掉，不再作判定依据。提交失败时本次运行不自动读（运行期阻断，
     * 不把它写成用户关闭；迁移目标为关闭时关闭也不会被弄丢），store 自动重试，落盘后按原选择；下次启动旧键还在就重新迁移。
     */
    private fun migrateLegacySwitch() {
        val store = importPreferences()
        val legacyOff = runCatching { !prefs.getBoolean(KEY_DEVICE_AUTO_DETECT, true) }.getOrDefault(false)
        val dismissedNotice = importBackend.read(KEY_LEGACY_NOTICE_SEEN) != null
        val userOff = store.snapshot().enabled == null && (legacyOff || dismissedNotice)
        val retired =
            prefs.all.keys.filter {
                it == KEY_DEVICE_AUTO_DETECT || (it.startsWith(IMPORT_KEY_PREFIX) && it != ImportPreferenceStore.KEY_ENABLED)
            }
        if (userOff || retired.isNotEmpty()) store.migrate(retired, userOff)
    }

    /** 按 store 给的退避延时（G42：2 秒起翻倍、封顶 5 分钟）排到主线程。 */
    private fun scheduleImportRetry(
        delayMillis: Long,
        task: () -> Unit,
    ) {
        pendingImportWriteRetries += 1
        importRetryHandler.postDelayed(
            {
                try {
                    task()
                } finally {
                    pendingImportWriteRetries -= 1
                }
            },
            delayMillis,
        )
    }

    val hashes: ClipboardHashStore =
        object : ClipboardHashStore {
            override fun read(key: String): String? = runCatching { prefs.getString(HASH_PREFIX + key, null) }.getOrNull()

            /**
             * 同步落盘并返回 commit() 的结果（APP-07 共性第 25 条）：异步 apply 时内存先更新，磁盘写失败也读得回，
             * 自写记录会在重启后丢失。不用 KTX 的 edit(commit = true)：它丢掉返回值（UseKtx 不适用）。
             */
            @SuppressLint("ApplySharedPref", "UseKtx")
            override fun write(
                key: String,
                value: String?,
            ): Boolean {
                val editor = prefs.edit()
                if (value == null) editor.remove(HASH_PREFIX + key) else editor.putString(HASH_PREFIX + key, value)
                return editor.commit()
            }
        }

    /**
     * 撤回同意的本地清理（CredentialCleanerRegistry 登记）：删 hash 记录与旧两态开关键；用户的开关值保留（C7：撤回不清关闭态）。同步落盘并核对 commit() 的返回值
     * （A-R3-02，APP-03b 共性第 11 条）：没落盘就抛错，撤回清理据此保留「待清理」标记，冷启动、读凭据前再重试；只有确认删除落盘才算成功。
     * 不用 KTX 的 edit(commit = true)：它丢掉 commit() 的返回值，所以直接用 Editor（UseKtx 不适用）。
     */
    @SuppressLint("ApplySharedPref", "UseKtx")
    fun clearHashes() {
        // FU-CLIP §2.2 第 4 条：同一个清理步骤里先删盐、再删两份记录（含 saltId）；任一步没确认落盘就抛错，撤回清理保留待清理并重试
        check(deleteSalt()) { "剪贴板盐删除未落盘" }
        val editor = prefs.edit()
        // 已知的记录键每次都显式提交删除（R1-04 同类）：上一次提交失败时内存里的键已删、磁盘还在，只按内存（prefs.all）列键会漏掉它们
        (KNOWN_RECORD_KEYS.map { HASH_PREFIX + it } + prefs.all.keys.filter { it.startsWith(HASH_PREFIX) })
            .toSet()
            .forEach(editor::remove)
        // 旧两态开关键一并删（没迁移完的关闭意图另由 importWrites 保留，不受影响，C7）
        editor.remove(KEY_DEVICE_AUTO_DETECT)
        check(editor.commit()) { "剪贴板 hash 记录删除未落盘" }
    }

    companion object {
        private const val KEY_DEVICE_AUTO_DETECT = "device_auto_detect"
        private const val KEY_LEGACY_NOTICE_SEEN = "import.notice_seen_version"
        private const val IMPORT_KEY_PREFIX = "import."
        private const val HASH_PREFIX = "hash."

        /** 撤回时一定要删的记录键（加盐的自写、邀请提示、商品去重摘要三份记录与 saltId，旧版未加盐的两份记录）。 */
        private val KNOWN_RECORD_KEYS =
            listOf(ClipboardRecords.KEY_SELF, ClipboardRecords.KEY_PROMPTED, ClipboardRecords.KEY_PRODUCTS, ClipboardRecords.KEY_SALT_ID) +
                ClipboardRecords.LEGACY_KEYS

        /** 盐在偏好文件里的键（加密后的值；不以 [HASH_PREFIX] 开头，撤回时先单独删）。 */
        const val KEY_SALT = "salt.v1"

        /** 盐的 Keystore 密钥别名（与会话密钥分开）。 */
        const val SALT_KEY_ALIAS = "couli.clipboard.salt"
    }
}

/**
 * 进程内的商品卡种子（G09）：parse 只有 1 张卡时以这张卡打开 ProductDetail，详情与购买沿用 parse 卡的 link_id（路由参数只有 platform、
 * product_key、item_ref）。种子按回退栈条目认领一次：认领后这一页一直用它；页面出栈或进程结束即丢弃，不持久化。
 */
class ProductSeeds {
    private val waiting = mutableMapOf<String, JsonObject>()
    private val claimed = mutableMapOf<String, JsonObject>()

    fun put(
        productKey: String,
        card: JsonObject,
    ) {
        waiting[productKey] = card
    }

    /** 详情页（[entryId]）认领 [productKey] 的种子；已认领过的返回同一张。 */
    @Suppress("ReturnCount") // 没有条目、已认领、没有种子、认领四个出口
    fun claim(
        entryId: String?,
        productKey: String,
    ): JsonObject? {
        val id = entryId ?: return null
        claimed[id]?.let { return it }
        val seed = waiting.remove(productKey) ?: return null
        claimed[id] = seed
        return seed
    }

    /** 回退栈里已不存在的条目：丢弃它认领的种子。 */
    fun retain(ids: Set<String>) {
        claimed.keys.retainAll(ids)
    }
}
