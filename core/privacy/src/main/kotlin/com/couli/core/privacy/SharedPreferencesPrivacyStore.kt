package com.couli.core.privacy

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.couli.core.config.CouliPreferenceNamespaces

/**
 * 「本机隐私决定」存在 SharedPreferences [CouliPreferenceNamespaces.PRIVACY_DECISION]（`couli_privacy`）。
 *
 * 哪些键属于本机隐私决定、切换环境时是否保留（03 §3.6、BR-ID-13）：
 * - 这里有五个键：决定、同意时的包内政策版本、同意的点击时间、基本模式，以及撤回清理待完成标记（R1-01）。它们是本机用户对隐私政策的决定，
 *   **切换环境时保留**（文件名不以 `couli.` 开头，SharedPreferencesNamespaceCleaner 不碰它）；
 * - 本地令牌、device_id、install_secret 不在这里：它们属于会话（`couli.session`），切换环境与撤回同意都要清，
 *   撤回同意经 [CredentialCleanerRegistry] 清，切换环境经 EnvironmentSwitchCleanerRegistry 清；
 * - 撤回同意不删本文件，把决定改写为基本模式（否则下次冷启动会当成首次安装重新弹窗）。
 *
 * 每次写入都用 commit()（同步落盘）：撤回同意、进入基本模式后进程立刻被杀，也不能在下次启动时恢复成「已同意」并初始化 SDK。
 */
class SharedPreferencesPrivacyStore internal constructor(
    private val prefs: SharedPreferences,
) : PrivacyStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(CouliPreferenceNamespaces.PRIVACY_DECISION, Context.MODE_PRIVATE),
    )

    override var decisionRaw: String
        get() = prefs.getString(KEY_DECISION, null) ?: PrivacyDecision.NOT_DECIDED.raw
        set(value) = write { putString(KEY_DECISION, value) }

    override var consentedVersion: String?
        get() = prefs.getString(KEY_CONSENTED_VERSION, null)
        set(value) = write { if (value == null) remove(KEY_CONSENTED_VERSION) else putString(KEY_CONSENTED_VERSION, value) }

    override var consentedAtMillis: Long?
        get() = if (prefs.contains(KEY_CONSENTED_AT)) prefs.getLong(KEY_CONSENTED_AT, 0L) else null
        set(value) = write { if (value == null) remove(KEY_CONSENTED_AT) else putLong(KEY_CONSENTED_AT, value) }

    override var basicMode: Boolean
        get() = prefs.getBoolean(KEY_BASIC_MODE, false)
        set(value) = write { putBoolean(KEY_BASIC_MODE, value) }

    override var withdrawalCleanupPending: Boolean
        get() = prefs.getBoolean(KEY_WITHDRAWAL_CLEANUP_PENDING, false)
        set(value) = write { putBoolean(KEY_WITHDRAWAL_CLEANUP_PENDING, value) }

    /** 撤回事务（R2-01）：标记、基本模式、清同意记录在同一个 Editor 的一次 commit() 里原子落盘。 */
    override fun commitWithdrawal() =
        write {
            putBoolean(KEY_WITHDRAWAL_CLEANUP_PENDING, true)
            putString(KEY_DECISION, PrivacyDecision.BASIC_MODE.raw)
            putBoolean(KEY_BASIC_MODE, true)
            remove(KEY_CONSENTED_VERSION)
            remove(KEY_CONSENTED_AT)
        }

    @SuppressLint("ApplySharedPref") // 隐私决定必须同步落盘，见类注释
    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        val saved = prefs.edit().apply(block).commit()
        check(saved) { "保存隐私决定失败" }
    }

    companion object {
        const val KEY_DECISION = "decision"
        const val KEY_CONSENTED_VERSION = "consented_version"
        const val KEY_CONSENTED_AT = "consented_at_millis"
        const val KEY_BASIC_MODE = "basic_mode"

        /** 撤回同意的本地清理还没完成（R1-01）。与隐私决定同存：切换环境不清它，清理完成前一直重试。 */
        const val KEY_WITHDRAWAL_CLEANUP_PENDING = "withdrawal_cleanup_pending"
    }
}
