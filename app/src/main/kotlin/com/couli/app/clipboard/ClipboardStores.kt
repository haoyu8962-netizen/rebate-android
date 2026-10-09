package com.couli.app.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.couli.core.clipboard.ClipboardHashStore
import com.couli.core.config.CouliPreferenceNamespaces
import kotlinx.serialization.json.JsonObject

/**
 * 剪贴板的本机记录（SharedPreferences [CouliPreferenceNamespaces.CLIPBOARD]，设备级：切换环境、登录、退出、换账号都不清）：
 * - 设备级开关「打开 App 自动识别剪贴板」[autoDetect]（BR-ID-16：默认开启，存本机、不随账号变）。旧版桥 clipboard.setAutoDetect 写在同一个文件的
 *   `auto_detect` 键，那是网页的临时抑制、不是用户的选择（G06），这里不读它；
 * - hash 记录（[hashes]：自写记录与 24 小时提示记录，只有 hash 与时间）。撤回同意时由 AppClipboard 清空。
 */
class ClipboardPreferences internal constructor(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(CouliPreferenceNamespaces.CLIPBOARD, Context.MODE_PRIVATE),
    )

    var autoDetect: Boolean
        get() = prefs.getBoolean(KEY_DEVICE_AUTO_DETECT, true)
        set(value) = prefs.edit { putBoolean(KEY_DEVICE_AUTO_DETECT, value) }

    val hashes: ClipboardHashStore =
        object : ClipboardHashStore {
            override fun read(key: String): String? = runCatching { prefs.getString(HASH_PREFIX + key, null) }.getOrNull()

            override fun write(
                key: String,
                value: String?,
            ) {
                prefs.edit { if (value == null) remove(HASH_PREFIX + key) else putString(HASH_PREFIX + key, value) }
            }
        }

    /**
     * 撤回同意的本地清理（CredentialCleanerRegistry 登记）：只删 hash 记录，设备级开关保留（BR-ID-16）。同步落盘并核对 commit() 的返回值
     * （A-R3-02，APP-03b 共性第 11 条）：没落盘就抛错，撤回清理据此保留「待清理」标记，冷启动、读凭据前再重试；只有确认删除落盘才算成功。
     * 不用 KTX 的 edit(commit = true)：它丢掉 commit() 的返回值，所以直接用 Editor（UseKtx 不适用）。
     */
    @SuppressLint("ApplySharedPref", "UseKtx")
    fun clearHashes() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(HASH_PREFIX) }
            .forEach(editor::remove)
        check(editor.commit()) { "剪贴板 hash 记录删除未落盘" }
    }

    private companion object {
        const val KEY_DEVICE_AUTO_DETECT = "device_auto_detect"
        const val HASH_PREFIX = "hash."
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
