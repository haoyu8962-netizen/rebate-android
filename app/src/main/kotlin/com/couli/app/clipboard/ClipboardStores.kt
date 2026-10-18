package com.couli.app.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.core.content.edit
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
 * - 设备级开关「打开 App 自动识别剪贴板」与功能同意（[importPreferences]，BR-ID-16 新版 couli #124：默认关、三态，存本机、不随账号变；FU-AND-0722）。
 *   旧版的 `device_auto_detect`（缺键当开启的两态开关）一律不认：构造时删除，老用户也要重新做用途同意（R01）。旧版桥 clipboard.setAutoDetect
 *   写在同一个文件的 `auto_detect` 键，那是网页的临时抑制、不是用户的选择（G06），这里不读它；
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

    init {
        // FU-AND-0722 R01：旧两态开关不迁移（true / false 都不算用途同意），直接删；没落盘时撤回清理（[clearHashes]）与下次构造再删
        if (prefs.contains(KEY_DEVICE_AUTO_DETECT)) prefs.edit { remove(KEY_DEVICE_AUTO_DETECT) }
    }

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

    /** 开关此刻是否为「开」（未选择、关都为 false；不看功能同意版本是否仍有效）。 */
    val autoDetect: Boolean get() = importPreferences().snapshot().enabled == true

    /**
     * 开关与功能同意的本机存储（同一偏好文件，键见 [ImportPreferenceStore]）：同步 commit() 落盘，核对返回值（G35）。每次调用给一个新的视图；
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

    /** 已排队、还没执行完的开关 / 功能同意写盘重试（主线程 Looper；测试与诊断观测用，只读）。 */
    internal var pendingImportWriteRetries: Int = 0
        private set

    private val importRetryHandler by lazy { Handler(Looper.getMainLooper()) }

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
     * 撤回同意的本地清理（CredentialCleanerRegistry 登记）：删 hash 记录，并清掉开关、功能同意与「已看过弹层」（FU-AND-0722 R06）与旧开关键；
     * 功能同意的待上报队列保留、继续补报（G26）。同步落盘并核对 commit() 的返回值
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
        // 同一次提交：开关、功能同意、看过弹层（R06）与旧两态开关键
        (ImportPreferenceStore.LOCAL_STATE_KEYS + KEY_DEVICE_AUTO_DETECT).forEach(editor::remove)
        check(editor.commit()) { "剪贴板 hash 记录删除未落盘" }
        // 已确认删除：之前没落盘的开关 / 同意意图作废，排队的重试不能再把它们写回来（G35）
        importWrites.forget(ImportPreferenceStore.LOCAL_STATE_KEYS)
    }

    companion object {
        private const val KEY_DEVICE_AUTO_DETECT = "device_auto_detect"
        private const val HASH_PREFIX = "hash."

        /** 撤回时一定要删的记录键（加盐的两份记录与 saltId、旧版未加盐的两份记录）。 */
        private val KNOWN_RECORD_KEYS =
            listOf(ClipboardRecords.KEY_SELF, ClipboardRecords.KEY_PROMPTED, ClipboardRecords.KEY_SALT_ID) + ClipboardRecords.LEGACY_KEYS

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

/**
 * 游客邀请预填（brief §2.7；G16）：游客在邀请提示条点确认，码放进这里，经 Router 打开 Login；进入验证码页时填一次（之后以用户输入为准），
 * 取走即丢弃。只在进程内，不持久化、不上传（随登录提交 LoginBySmsRequest.invite_code）。
 */
class InvitePrefill {
    private var code: String? = null

    fun set(value: String) {
        code = value
    }

    fun take(): String? = code.also { code = null }

    val pending: Boolean get() = code != null
}
