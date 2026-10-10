package com.couli.core.me

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本任务新增的设备级本机偏好（G03、G28、G29）：SharedPreferences [NAMESPACE]（`couli_me_prefs`），按设备、不按账号。
 * - 通知引导条最近一次关闭的时刻（G28）；
 * - 个性化推荐开关与最近一次切换时刻（G03，缺省打开）。
 *
 * 只在撤回同意时随 CredentialCleanerRegistry 清空（[clearAll]，AppModule 生产注册）；退出、换账号、会话失效、切换环境、回后台都不清（[MeLocalData]）。
 * 名字故意不以 `couli.` 开头，切换环境的命名空间清理不会抹掉它，也不登记进 preservedAcrossEnvironmentSwitch（brief §3）。
 *
 * 删除要确认落盘（commit 返回 true）；失败时在独立的 [PENDING_NAMESPACE] 里记「待清理」并抛错（撤回清理名单据此保留待清理、下次重试，
 * APP-03a 第 33、38 条，APP-08 第 28、40 条）；有「待清理」时先重试删除，删不掉一律按缺省值读（不读回旧数据）。写入一律 commit。
 */
@SuppressLint("ApplySharedPref", "UseKtx") // 必须 commit 并拿到返回值确认写入（KTX 的 edit(commit = true) 丢掉返回值）
class MeLocalStore(
    private val prefs: SharedPreferences,
    private val pending: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(NAMESPACE, Context.MODE_PRIVATE),
        context.applicationContext.getSharedPreferences(PENDING_NAMESPACE, Context.MODE_PRIVATE),
    )

    private val mutableOn = MutableStateFlow(readPersonalization())

    /** 个性化推荐开关（缺省打开）。网络线程经 [personalizationOffNow] 读。 */
    val personalizationOn: StateFlow<Boolean> = mutableOn.asStateFlow()

    @Volatile
    private var offNow: Boolean = !mutableOn.value

    /** 网络层每个请求读（线程安全，不碰磁盘）。 */
    fun personalizationOffNow(): Boolean = offNow

    /** 有「待清理」时先重试；仍删不掉为 false（按缺省值读）。 */
    private fun readable(): Boolean = !pending.getBoolean(KEY_PENDING, false) || runCatching { clearAll() }.isSuccess

    fun guideClosedAt(): Long? = if (readable() && prefs.contains(KEY_GUIDE_CLOSED)) prefs.getLong(KEY_GUIDE_CLOSED, 0L) else null

    /** 记下关闭引导条的时刻；写不进返回 false（本次照样收起，下次按没关过）。 */
    fun markGuideClosed(now: Long): Boolean = readable() && prefs.edit().putLong(KEY_GUIDE_CLOSED, now).commit()

    private fun readPersonalization(): Boolean = !readable() || prefs.getBoolean(KEY_PERSONALIZATION, true)

    fun personalizationChangedAt(): Long? =
        if (readable() && prefs.contains(KEY_PERSONALIZATION_AT)) prefs.getLong(KEY_PERSONALIZATION_AT, 0L) else null

    /** 切换个性化（本机先保存，用户意愿优先，brief §2.5）；返回是否落盘。内存与网络头按新值生效（落盘失败也生效，本进程内）。 */
    fun setPersonalization(
        on: Boolean,
        now: Long,
    ): Boolean {
        mutableOn.value = on
        offNow = !on
        return readable() &&
            prefs
                .edit()
                .putBoolean(KEY_PERSONALIZATION, on)
                .putLong(KEY_PERSONALIZATION_AT, now)
                .commit()
    }

    /** 撤回同意：全部删除并确认落盘；失败记「待清理」并抛错。内存值立即回到缺省（开）。 */
    fun clearAll() {
        mutableOn.value = true
        offNow = false
        val cleared = prefs.edit().clear().commit()
        if (!cleared) {
            pending.edit().putBoolean(KEY_PENDING, true).commit()
            error("couli_me_prefs not cleared")
        }
        if (pending.getBoolean(KEY_PENDING, false) &&
            !pending.edit().remove(KEY_PENDING).commit()
        ) {
            error("couli_me_prefs pending flag not cleared")
        }
    }

    companion object {
        const val NAMESPACE = "couli_me_prefs"
        const val PENDING_NAMESPACE = "couli_me_prefs_pending"
        const val KEY_GUIDE_CLOSED = "guide_bar_closed_at"
        const val KEY_PERSONALIZATION = "personalization_on"
        const val KEY_PERSONALIZATION_AT = "personalization_at"
        private const val KEY_PENDING = "pending_clear"

        /** 撤回清理名单里的名字（AppModule 生产注册）。 */
        const val CLEANER = "me_local_prefs"
    }
}
