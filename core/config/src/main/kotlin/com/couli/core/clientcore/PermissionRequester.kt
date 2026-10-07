package com.couli.core.clientcore

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 权限状态与申请结果（三端测试接线名）。 */
object PermissionStatusRaw {
    const val NOT_ASKED = "not_asked"
    const val GRANTED = "granted"
    const val DENIED = "denied"
    const val PERMANENTLY_DENIED = "permanently_denied"
    const val THROTTLED = "throttled"
    const val UNAVAILABLE = "unavailable"
}

/** 权限类型（MVP：push 通知、photos 保存图片到相册；03 §4.9）。 */
object PermissionTypeRaw {
    const val PUSH = "push"
    const val PHOTOS = "photos"
}

/** 一次申请的结果：granted / denied / permanently_denied / throttled / unavailable。 */
data class PermissionResult(
    val resultRaw: String,
) {
    val granted: Boolean get() = resultRaw == PermissionStatusRaw.GRANTED
}

/**
 * 系统权限申请的唯一入口（03 §4.9；BR-ID-13；BR-TEXT-14 表 D）。原生页面与以后的桥方法 perm.request 都只调它，系统调用经可注入的
 * [status] / [systemRequest]（Android 实现在 core:privacy），界面经 [showRationale] / [showSettingsGuide]。
 *
 * 流程：查当前状态 → 已授权直接返回 → 系统已不再弹框（永久拒绝）→ 不调系统接口，显示 `perm.<type>.denied_forever` 与【去设置】
 * → 节流：上次被拒距今不满 48 小时（BR-ID-13）→ 不弹说明与系统框，返回 throttled 并给【去设置】（用户从设置入口主动点击的
 * [request] fromSettings = true 不受此限）→ 显示用途说明（push 用 perm.push.card_hint，photos 用 perm.photos.purpose；组件不拼键名）
 * → 弹系统框 → 记下结果：被拒（含永久拒绝）记下被拒时刻（持久化，「清除缓存」不清），授权后删除该记录。
 *
 * - 用途说明的继续按钮：Android 在系统框弹出的同时于顶部显示说明浮层，没有继续按钮（null）；iOS、鸿蒙为 perm.btn.continue。
 * - 被节流的点击不延长窗口（只有新的系统拒绝才更新被拒时刻）。
 * - 节流记录读不出（读取失败）时按节流处理、不改记录；内容损坏时把各类型都记为此刻被拒（保守：最多 48 小时不主动弹）。
 * - 节流记录写不进时仍在内存里节流（本进程内）。
 * - TODO(N-21): unavailable 的具体映射、说明页取消、时钟回拨未定（rule-gaps 第 7 条）：系统返回不认识的结果按 unavailable，
 *   不记被拒；时钟回拨（此刻早于被拒时刻）按仍在节流期内。
 *
 * 同一时刻只处理一个申请（Mutex），并发申请排队。
 */
@Suppress("LongParameterList") // 存储、时钟、端别与四个系统 / 界面回调逐项注入（三端测试接线约定）
class PermissionRequester(
    private val readFile: () -> String?,
    private val writeFile: (String?) -> Unit,
    private val clock: () -> Long,
    private val platform: String,
    private val status: (String) -> String,
    private val showRationale: suspend (String, String?) -> Unit,
    private val systemRequest: suspend (String) -> String,
    private val showSettingsGuide: (String?, String) -> Unit,
) {
    private val mutex = Mutex()

    /** 本进程内的被拒时刻（写盘失败时仍按它节流）。 */
    private val memory = mutableMapOf<String, Long>()

    suspend fun request(
        permission: String,
        fromSettings: Boolean = false,
    ): PermissionResult = mutex.withLock { requestLocked(permission, fromSettings) }

    @Suppress("ReturnCount") // 按流程逐步返回
    private suspend fun requestLocked(
        permission: String,
        fromSettings: Boolean,
    ): PermissionResult {
        val current = status(permission)
        val purpose = PURPOSE_KEYS[permission] ?: return result(PermissionStatusRaw.UNAVAILABLE)
        when (current) {
            PermissionStatusRaw.GRANTED -> {
                return result(PermissionStatusRaw.GRANTED)
            }

            PermissionStatusRaw.PERMANENTLY_DENIED -> {
                showSettingsGuide(deniedForeverKey(permission), GO_SETTINGS)
                return result(PermissionStatusRaw.PERMANENTLY_DENIED)
            }

            PermissionStatusRaw.UNAVAILABLE -> {
                return result(PermissionStatusRaw.UNAVAILABLE)
            }
        }
        if (!fromSettings && throttled(permission)) {
            showSettingsGuide(null, GO_SETTINGS)
            return result(PermissionStatusRaw.THROTTLED)
        }
        showRationale(purpose, continueKey())
        return when (val outcome = systemRequest(permission)) {
            PermissionStatusRaw.GRANTED -> {
                forgetDenial(permission)
                result(PermissionStatusRaw.GRANTED)
            }

            PermissionStatusRaw.DENIED, PermissionStatusRaw.PERMANENTLY_DENIED -> {
                recordDenial(permission)
                result(outcome)
            }

            else -> {
                result(PermissionStatusRaw.UNAVAILABLE)
            }
        }
    }

    /** Android 用顶部说明浮层，没有继续按钮；iOS、鸿蒙先出一屏说明，点【继续】后弹系统框。 */
    private fun continueKey(): String? = if (platform == ClientPlatformRaw.ANDROID) null else CONTINUE

    @Suppress("ReturnCount") // 读不出、没有记录逐项提前返回
    private fun throttled(permission: String): Boolean {
        val deniedAt =
            when (val stored = readRecords()) {
                Records.Unreadable -> {
                    return true
                }

                Records.Corrupt -> {
                    val now = clock()
                    PURPOSE_KEYS.keys.forEach { memory[it] = now }
                    persist(PURPOSE_KEYS.keys.associateWith { now })
                    now
                }

                is Records.Loaded -> {
                    maxOfNullable(stored.values[permission], memory[permission])
                }
            } ?: return false
        val elapsed = clock() - deniedAt
        return elapsed < THROTTLE_MILLIS
    }

    private fun recordDenial(permission: String) {
        val now = clock()
        memory[permission] = now
        val base = (readRecords() as? Records.Loaded)?.values.orEmpty()
        persist(base + (permission to now))
    }

    private fun forgetDenial(permission: String) {
        memory.remove(permission)
        val base = (readRecords() as? Records.Loaded)?.values ?: return
        if (permission in base) persist(base - permission)
    }

    private sealed interface Records {
        data object Unreadable : Records

        data object Corrupt : Records

        class Loaded(
            val values: Map<String, Long>,
        ) : Records
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount") // 读取失败与没有记录分开（lessons 38）；逐项判损坏
    private fun readRecords(): Records {
        val text =
            try {
                readFile()
            } catch (error: Exception) {
                return Records.Unreadable
            } ?: return Records.Loaded(emptyMap())
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return Records.Corrupt
        val values = mutableMapOf<String, Long>()
        root.forEach { (key, value) ->
            values[key] = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return Records.Corrupt
        }
        return Records.Loaded(values)
    }

    private fun persist(values: Map<String, Long>) {
        runCatching { writeFile(if (values.isEmpty()) null else JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString()) }
    }

    private fun maxOfNullable(
        a: Long?,
        b: Long?,
    ): Long? =
        if (a == null) {
            b
        } else if (b == null) {
            a
        } else {
            maxOf(a, b)
        }

    private fun result(raw: String) = PermissionResult(raw)

    companion object {
        /** BR-ID-13：被拒后 48 小时内不再主动弹申请。 */
        const val THROTTLE_MILLIS = 48L * 60 * 60 * 1000

        const val GO_SETTINGS = "perm.go_settings"
        const val CONTINUE = "perm.btn.continue"

        /** 权限类型 → 用途说明键（BR-TEXT-14 表 D；03 §4.9 按类型映射，不拼键名）。 */
        val PURPOSE_KEYS: Map<String, String> =
            mapOf(
                PermissionTypeRaw.PUSH to "perm.push.card_hint",
                PermissionTypeRaw.PHOTOS to "perm.photos.purpose",
            )

        /** 永久拒绝后的引导文案键（perm.<type>.denied_forever）。 */
        fun deniedForeverKey(permission: String): String? =
            when (permission) {
                PermissionTypeRaw.PUSH -> "perm.push.denied_forever"
                PermissionTypeRaw.PHOTOS -> "perm.photos.denied_forever"
                else -> null
            }
    }
}
