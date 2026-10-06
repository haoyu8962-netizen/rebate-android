package com.couli.core.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.couli.core.config.CouliPreferenceNamespaces

/**
 * [UpdateGate] 落盘的内容（不是业务契约，rule-gaps G12）。
 * @property result 最近一次成功检查的结果（冷启动在新结果回来之前按它判断，已知强更直接拦）。
 * @property lastSuccessAtMillis 最近一次成功检查的时刻（回前台复查间隔从它算起；失败、发起请求都不改它）。
 * @property forcedBy10405 接口层收到 10405 后进入的强更（还没有新的成功检查推翻它）。
 * @property forcedMinimum 10405 带回的 min_supported_version（可能为空）。
 * @property forcedAppVersion 收到 10405 时的本机版本（10405 没带最低版本时，升级后据此失效，rule-gaps G15）。
 * @property dismissedLatest 「暂不更新」时记下的目标版本（提示时的 latest_version）。
 */
data class UpdatePersisted(
    val result: UpdateCheckResult? = null,
    val lastSuccessAtMillis: Long? = null,
    val forcedBy10405: Boolean = false,
    val forcedMinimum: String? = null,
    val forcedAppVersion: String? = null,
    val dismissedLatest: String? = null,
)

/** [UpdateGate] 的持久化。 */
interface UpdateStore {
    fun load(): UpdatePersisted

    fun save(value: UpdatePersisted)
}

/** 内存实现（单测；重建 Gate 时复用同一个实例模拟冷启动）。 */
class MemoryUpdateStore : UpdateStore {
    private var value = UpdatePersisted()

    override fun load(): UpdatePersisted = value

    override fun save(value: UpdatePersisted) {
        this.value = value
    }
}

/**
 * SharedPreferences 实现：`couli.update`（[CouliPreferenceNamespaces.UPDATE]），切换环境时随命名空间清理
 * （检查结果属于连接的那套服务器）。每次写入 commit()：强更结论写下后进程被杀，下次冷启动仍要直接拦。
 */
class SharedPreferencesUpdateStore internal constructor(
    private val prefs: SharedPreferences,
) : UpdateStore {
    /**
     * @param namespace Release 用 [CouliPreferenceNamespaces.UPDATE]；debug / staging 的调试假数据用
     *   [CouliPreferenceNamespaces.UPDATE_DEBUG]，不写进 Release 读取的存储（R1-03：覆盖安装 Release 后不会被假结果误拦）。
     */
    constructor(context: Context, namespace: String = CouliPreferenceNamespaces.UPDATE) : this(
        context.applicationContext.getSharedPreferences(namespace, Context.MODE_PRIVATE),
    )

    override fun load(): UpdatePersisted =
        UpdatePersisted(
            result = loadResult(),
            lastSuccessAtMillis = if (prefs.contains(KEY_LAST_SUCCESS)) prefs.getLong(KEY_LAST_SUCCESS, 0L) else null,
            forcedBy10405 = prefs.getBoolean(KEY_FORCED, false),
            forcedMinimum = prefs.getString(KEY_FORCED_MIN, null),
            forcedAppVersion = prefs.getString(KEY_FORCED_APP_VERSION, null),
            dismissedLatest = prefs.getString(KEY_DISMISSED, null),
        )

    @SuppressLint("ApplySharedPref") // 强更结论必须同步落盘，见类注释
    override fun save(value: UpdatePersisted) {
        val editor = prefs.edit().clear()
        value.result?.let { result ->
            editor.putBoolean(KEY_HAS_RESULT, true)
            editor.putNullable(KEY_MIN, result.minSupported)
            editor.putNullable(KEY_RECOMMENDED, result.recommended)
            editor.putNullable(KEY_LATEST, result.latest)
            editor.putNullable(KEY_DEFAULT_STORE, result.defaultStore)
            editor.putString(KEY_STORES, encodeStores(result.stores))
            editor.putInt(KEY_INTERVAL, result.recheckIntervalSec)
            editor.putNullable(KEY_TITLE, result.updateTitle)
            editor.putNullable(KEY_NOTES, result.updateNotes)
        }
        value.lastSuccessAtMillis?.let { editor.putLong(KEY_LAST_SUCCESS, it) }
        editor.putBoolean(KEY_FORCED, value.forcedBy10405)
        editor.putNullable(KEY_FORCED_MIN, value.forcedMinimum)
        editor.putNullable(KEY_FORCED_APP_VERSION, value.forcedAppVersion)
        editor.putNullable(KEY_DISMISSED, value.dismissedLatest)
        check(editor.commit()) { "保存版本检查结论失败" }
    }

    private fun loadResult(): UpdateCheckResult? {
        if (!prefs.getBoolean(KEY_HAS_RESULT, false)) return null
        return UpdateCheckResult(
            minSupported = prefs.getString(KEY_MIN, null),
            recommended = prefs.getString(KEY_RECOMMENDED, null),
            latest = prefs.getString(KEY_LATEST, null),
            defaultStore = prefs.getString(KEY_DEFAULT_STORE, null),
            stores = decodeStores(prefs.getString(KEY_STORES, null).orEmpty()),
            recheckIntervalSec = prefs.getInt(KEY_INTERVAL, UpdateGate.DEFAULT_RECHECK_INTERVAL_SEC),
            updateTitle = prefs.getString(KEY_TITLE, null),
            updateNotes = prefs.getString(KEY_NOTES, null),
        )
    }

    private fun SharedPreferences.Editor.putNullable(
        key: String,
        value: String?,
    ) {
        if (value != null) putString(key, value)
    }

    private companion object {
        const val KEY_HAS_RESULT = "has_result"
        const val KEY_MIN = "min_supported_version"
        const val KEY_RECOMMENDED = "recommended_version"
        const val KEY_LATEST = "latest_version"
        const val KEY_DEFAULT_STORE = "default_store"
        const val KEY_STORES = "stores"
        const val KEY_INTERVAL = "recheck_interval_sec"
        const val KEY_TITLE = "update_title"
        const val KEY_NOTES = "update_notes"
        const val KEY_LAST_SUCCESS = "last_success_at"
        const val KEY_FORCED = "forced_by_10405"
        const val KEY_FORCED_MIN = "forced_min_supported_version"
        const val KEY_FORCED_APP_VERSION = "forced_app_version"
        const val KEY_DISMISSED = "dismissed_latest_version"

        // 商店键是小写字母、数字、下划线（specs/app-stores.yaml），版本号是数字与点：用制表符与换行分隔不会冲突
        const val FIELD_SEPARATOR = "\t"
        const val ROW_SEPARATOR = "\n"

        fun encodeStores(stores: List<UpdateStoreListing>): String =
            stores.joinToString(ROW_SEPARATOR) { it.store + FIELD_SEPARATOR + it.version }

        fun decodeStores(text: String): List<UpdateStoreListing> =
            text
                .split(ROW_SEPARATOR)
                .mapNotNull { row ->
                    val parts = row.split(FIELD_SEPARATOR)
                    if (parts.size == 2) UpdateStoreListing(parts[0], parts[1]) else null
                }
    }
}
