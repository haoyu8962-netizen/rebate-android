package com.couli.app.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import com.couli.core.clipboard.ClipboardFingerprint
import com.couli.core.clipboard.ClipboardHashStore
import com.couli.core.clipboard.ClipboardRecords
import com.couli.core.clipboard.ClipboardSaltRead
import com.couli.core.clipboard.ClipboardSaltStore
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.core.network.AndroidKeystoreCipher
import com.couli.core.network.EncryptedPreferences
import com.couli.core.network.SecretCipher
import com.couli.core.network.StoredValue
import kotlinx.serialization.json.JsonObject

/**
 * 剪贴板的本机记录（SharedPreferences [CouliPreferenceNamespaces.CLIPBOARD]，设备级：切换环境、登录、退出、换账号都不清）：
 * - 设备级开关「打开 App 自动识别剪贴板」[autoDetect]（BR-ID-16：默认开启，存本机、不随账号变）。旧版桥 clipboard.setAutoDetect 写在同一个文件的
 *   `auto_detect` 键，那是网页的临时抑制、不是用户的选择（G06），这里不读它；
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

    var autoDetect: Boolean
        get() = prefs.getBoolean(KEY_DEVICE_AUTO_DETECT, true)
        set(value) = prefs.edit { putBoolean(KEY_DEVICE_AUTO_DETECT, value) }

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
     * 撤回同意的本地清理（CredentialCleanerRegistry 登记）：只删 hash 记录，设备级开关保留（BR-ID-16）。同步落盘并核对 commit() 的返回值
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
        check(editor.commit()) { "剪贴板 hash 记录删除未落盘" }
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
