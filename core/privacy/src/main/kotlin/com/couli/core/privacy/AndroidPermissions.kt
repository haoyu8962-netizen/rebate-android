package com.couli.core.privacy

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import com.couli.core.clientcore.ClientPlatformRaw
import com.couli.core.clientcore.PermissionRequester
import com.couli.core.clientcore.PermissionResult
import com.couli.core.clientcore.PermissionStatusRaw
import com.couli.core.clientcore.PermissionTypeRaw
import com.couli.core.config.CouliPreferenceNamespaces
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 当前 Activity 提供的系统权限能力（MainActivity 实现：ActivityResult 申请、shouldShowRequestPermissionRationale）。
 * 业务代码不直接调系统申请接口，只经 [AndroidPermissionRequester]（03 §4.9）。
 */
interface PermissionActivityHost {
    /** 系统此刻是否会对这个权限再次弹框之前给出说明（被拒过一次、还没勾「不再询问」）。 */
    fun shouldShowRationale(androidPermission: String): Boolean

    /** 弹系统授权框，返回是否授权。 */
    suspend fun request(androidPermission: String): Boolean
}

/**
 * Android 的系统权限状态与申请（PermissionRequester 的 status / systemRequest）。
 *
 * 权限类型 → 系统权限（TODO(N-21)：specs/system-permissions.yaml 还没有填 Android 的权限名，rule-gaps 第 7 条；工程里不手写清单声明）：
 * - push：Android 13 起是运行时权限 POST_NOTIFICATIONS；更早的系统没有授权框，通知被关时只能去系统设置（按 permanently_denied）。
 * - photos：Android 10 起经 MediaStore 写入相册不需要权限；更早是 WRITE_EXTERNAL_STORAGE。
 * 需要的系统权限没在清单里声明（还没登记进 system-permissions）、或当前没有 Activity 能弹框 → unavailable，不弹任何框。
 *
 * 「拒绝且不再询问」的判断：申请过（记在 [CouliPreferenceNamespaces.PERMISSION]）、仍未授权、且系统不再给说明 → permanently_denied。
 */
class AndroidPermissionSystem(
    context: Context,
    private val preferences: SharedPreferences,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val declared: (String) -> Boolean = defaultDeclared(context),
    private val granted: (String) -> Boolean = { context.applicationContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED },
    private val notificationsEnabled: () -> Boolean = defaultNotificationsEnabled(context),
) {
    /** 当前前台 Activity（MainActivity onCreate 设置、onDestroy 清除）。 */
    @Volatile
    var host: PermissionActivityHost? = null

    /** 权限类型对应的系统权限；这个系统版本上不需要运行时权限为 null。不认识的类型抛错（调用方先判）。 */
    fun androidPermission(type: String): String? =
        when (type) {
            PermissionTypeRaw.PUSH -> if (sdkInt >= NOTIFICATION_RUNTIME_SDK) POST_NOTIFICATIONS else null
            PermissionTypeRaw.PHOTOS -> if (sdkInt >= SCOPED_STORAGE_SDK) null else WRITE_EXTERNAL_STORAGE
            else -> error("unknown permission type $type")
        }

    @Suppress("ReturnCount") // 按类型、声明、授权、是否申请过逐步判定
    fun status(type: String): String {
        if (type !in KNOWN_TYPES) return PermissionStatusRaw.UNAVAILABLE
        val permission =
            androidPermission(type) ?: return when {
                type == PermissionTypeRaw.PHOTOS -> PermissionStatusRaw.GRANTED
                notificationsEnabled() -> PermissionStatusRaw.GRANTED
                else -> PermissionStatusRaw.PERMANENTLY_DENIED
            }
        if (!declared(permission)) return PermissionStatusRaw.UNAVAILABLE
        if (granted(permission)) return PermissionStatusRaw.GRANTED
        val activity = host ?: return PermissionStatusRaw.UNAVAILABLE
        return when {
            activity.shouldShowRationale(permission) -> PermissionStatusRaw.DENIED
            asked(permission) -> PermissionStatusRaw.PERMANENTLY_DENIED
            else -> PermissionStatusRaw.NOT_ASKED
        }
    }

    /**
     * 弹系统授权框（只在 [status] 为 not_asked / denied 之后调用）。[isValid] 在真正调系统接口之前最后查一次（APP-04 三端共性
     * 第 14 条）：调用已失效时不弹框、不记「申请过」，按 unavailable 结束。
     */
    @Suppress("ReturnCount") // 不需要运行时权限、没有 Activity、没声明、已失效逐项返回
    suspend fun request(
        type: String,
        isValid: () -> Boolean = { true },
    ): String {
        val permission = androidPermission(type) ?: return status(type)
        val activity = host ?: return PermissionStatusRaw.UNAVAILABLE
        if (!declared(permission)) return PermissionStatusRaw.UNAVAILABLE
        if (!isValid()) return PermissionStatusRaw.UNAVAILABLE
        markAsked(permission)
        return when {
            activity.request(permission) -> PermissionStatusRaw.GRANTED
            activity.shouldShowRationale(permission) -> PermissionStatusRaw.DENIED
            else -> PermissionStatusRaw.PERMANENTLY_DENIED
        }
    }

    private fun asked(permission: String): Boolean = preferences.getBoolean(ASKED_PREFIX + permission, false)

    /** 弹过 [type] 的系统授权框（本机记录；这个系统版本不需要运行时权限时为 false）。 */
    @Suppress("ReturnCount") // 不认识的类型、不需要运行时权限、已申请三个出口
    fun askedBefore(type: String): Boolean {
        if (type !in KNOWN_TYPES) return false
        val permission = androidPermission(type) ?: return false
        return asked(permission)
    }

    private fun markAsked(permission: String) {
        preferences.edit().putBoolean(ASKED_PREFIX + permission, true).apply()
    }

    companion object {
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"
        private const val NOTIFICATION_RUNTIME_SDK = 33
        private const val SCOPED_STORAGE_SDK = 29
        private const val ASKED_PREFIX = "asked."
        private val KNOWN_TYPES = setOf(PermissionTypeRaw.PUSH, PermissionTypeRaw.PHOTOS)

        @Suppress("DEPRECATION") // GET_PERMISSIONS 的旧签名在 minSdk 26 上仍是唯一写法
        private fun defaultDeclared(context: Context): (String) -> Boolean {
            val app = context.applicationContext
            return { permission ->
                runCatching {
                    app.packageManager
                        .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
                        .requestedPermissions
                        ?.contains(permission) == true
                }.getOrDefault(false)
            }
        }

        private fun defaultNotificationsEnabled(context: Context): () -> Boolean {
            val app = context.applicationContext
            return { app.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true }
        }
    }
}

/** 正在显示的权限界面（App 的 PermissionPromptHost 渲染）。 */
sealed interface PermissionPrompt {
    /** Android：系统框弹出的同时在顶部显示的用途说明浮层（没有按钮，03 §4.9）。 */
    data class Rationale(
        val purposeKey: String,
    ) : PermissionPrompt

    /**
     * 引导去系统设置（CouliDialog）：[messageKey] 为 perm.<type>.denied_forever（永久拒绝）；节流时为 null，界面用该类型的用途键
     * [purposeKey]；按钮 [buttonKey] = perm.go_settings，点了跳系统里本 App 的设置页。
     */
    data class SettingsGuide(
        val messageKey: String?,
        val purposeKey: String,
        val buttonKey: String,
    ) : PermissionPrompt
}

/** 权限界面的状态源（同一时刻最多一个）。 */
class PermissionPrompts {
    private val mutable = MutableStateFlow<PermissionPrompt?>(null)
    val current: StateFlow<PermissionPrompt?> = mutable.asStateFlow()

    fun show(prompt: PermissionPrompt) {
        mutable.value = prompt
    }

    /** 收起 [prompt]（已被别的界面替换的不动）。 */
    fun dismiss(prompt: PermissionPrompt? = mutable.value) {
        mutable.compareAndSet(prompt, null)
    }
}

/**
 * Android 上申请系统权限的唯一入口（03 §4.9）：原生页面与以后的桥方法 perm.request 都只调 [request]。流程、节流（BR-ID-13，节流
 * 时刻持久化在 [CouliPreferenceNamespaces.PERMISSION]，「清除缓存」与切换环境都不清）与结果状态由三端共用的 [PermissionRequester]
 * 决定；这里接 Android 的系统调用（[AndroidPermissionSystem]）与界面（[PermissionPrompts]）：用途说明浮层与系统框同时出现，
 * 系统返回后收起。
 */
class AndroidPermissionRequester(
    private val system: AndroidPermissionSystem,
    val prompts: PermissionPrompts,
    private val preferences: SharedPreferences,
    clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    @Volatile
    private var currentType: String = PermissionTypeRaw.PUSH

    /** 正在处理的申请的有效性（桥调用带进来的；原生页面恒为有效）。只在 [mutex] 内换。 */
    @Volatile
    private var currentValid: () -> Boolean = { true }

    private val core =
        PermissionRequester(
            readFile = { preferences.getString(KEY_THROTTLE, null) },
            writeFile = { value ->
                val editor = preferences.edit()
                if (value == null) editor.remove(KEY_THROTTLE) else editor.putString(KEY_THROTTLE, value)
                check(editor.commit()) { "permission throttle record not saved" }
            },
            clock = clock,
            platform = ClientPlatformRaw.ANDROID,
            status = system::status,
            showRationale = { purpose, _ -> if (currentValid()) prompts.show(PermissionPrompt.Rationale(purpose)) },
            systemRequest = { type ->
                try {
                    // 用途说明之后、调系统接口之前再查一次（Android 的说明浮层与系统框同时出现，两处检查落在同一时刻）
                    if (currentValid()) system.request(type, currentValid) else PermissionStatusRaw.UNAVAILABLE
                } finally {
                    // 系统返回结果后收起用途说明
                    (prompts.current.value as? PermissionPrompt.Rationale)?.let(prompts::dismiss)
                }
            },
            showSettingsGuide = { message, button ->
                if (!currentValid()) return@PermissionRequester
                val purpose = PermissionRequester.PURPOSE_KEYS[currentType] ?: return@PermissionRequester
                prompts.show(PermissionPrompt.SettingsGuide(message, purpose, button))
            },
        )

    /**
     * 申请 [type]（push / photos）。[fromSettings]：用户从设置类入口主动点击，不受节流（BR-ID-13）。
     * [isValid]：调用方的有效性（桥调用的隐私、环境、会话、页面世代；APP-04 三端共性第 14 条）。排队轮到时（出队）、用途说明之后、
     * 调系统接口前各查一次；失效就不显示任何界面、不调系统接口、不记被拒，按 unavailable 结束。[purposeShown]：入口本身就是用途说明（待跟单卡的
     * 通知行），不再显示说明浮层（APP-08 共性第 4 条）。
     */
    suspend fun request(
        type: String,
        fromSettings: Boolean = false,
        isValid: () -> Boolean = { true },
        purposeShown: Boolean = false,
    ): PermissionResult =
        mutex.withLock {
            // 出队：排队期间已失效的申请不再处理
            if (!isValid()) return@withLock PermissionResult(PermissionStatusRaw.UNAVAILABLE)
            currentType = type
            currentValid = isValid
            try {
                core.request(type, fromSettings, purposeShown)
            } finally {
                currentValid = { true }
            }
        }

    /** 只读状态（perm.getPushStatus、隐私中心的系统权限一节），不触发申请。 */
    fun status(type: String): String = system.status(type)

    /** 本机记下的最近一次被拒时刻（只读，APP-12 G28）；没有为 null，读不出按此刻。 */
    fun lastDeniedAt(type: String): Long? = core.lastDeniedAt(type)

    /**
     * 本机有没有 [type] 的申请记录（APP-08 G22「从未决定过」只认本机记录）：弹过系统框、被拒的节流记录，或用户点过页面上的申请入口（[noteTapped]，
     * 如待跟单卡的【开启通知】——点过之后那一行不再出现，无论结果）。
     */
    fun hasLocalRecord(type: String): Boolean =
        system.askedBefore(type) || core.hasRecord(type) || preferences.getBoolean(TAPPED_PREFIX + type, false)

    /** 用户点了页面上的申请入口（之后 [hasLocalRecord] 为 true）。同步落盘。 */
    @SuppressLint("ApplySharedPref", "UseKtx")
    fun noteTapped(type: String) {
        preferences.edit().putBoolean(TAPPED_PREFIX + type, true).commit()
    }

    private companion object {
        const val KEY_THROTTLE = "throttle"
        const val TAPPED_PREFIX = "tapped."
    }
}

/** 权限记录的 SharedPreferences（切换环境与「清除缓存」都不清）。 */
fun permissionPreferences(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(CouliPreferenceNamespaces.PERMISSION, Context.MODE_PRIVATE)
