package com.couli.core.router

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** pending_action 的持久化后端：只存 1 条原始记录（BR-ID-10「按 app 维度只存 1 条」）。 */
interface PendingBackend {
    fun read(): String?

    /** 写入；失败返回 false。 */
    fun write(value: String): Boolean

    /** 删除；失败返回 false。 */
    fun delete(): Boolean
}

/** 内存后端（单测与不需要跨进程恢复的场合）。 */
class MemoryPendingBackend : PendingBackend {
    @Volatile
    private var value: String? = null

    override fun read(): String? = value

    override fun write(value: String): Boolean {
        this.value = value
        return true
    }

    override fun delete(): Boolean {
        value = null
        return true
    }
}

/** 一条待恢复的动作。[action] 是不透明的动作名（如 [PendingActionStore.OPEN_ROUTE]）。 */
data class PendingAction(
    val action: String,
    val params: JsonObject,
    /** 计时起点（毫秒）：保存时刻，或最近一次完成前置步骤的时刻。 */
    val timerStartedAtMillis: Long,
    /** 登记它的请求来源（[RouteSource.raw]，如 agent_card）；旧记录或未给时为 null。 */
    val origin: String? = null,
)

/**
 * pending_action（BR-ID-10、03 §4.4；验收 AC-ACC-pending）：
 * - 只存 1 条，新的覆盖旧的；
 * - 有效期 [TTL_MILLIS]（10 分钟）：距计时起点 ≤ 10 分钟保留，> 10 分钟读取时删除；读取本身不续时；
 * - 每完成一个前置步骤（[markStepDone]）把计时起点重置为当前时刻；已过期的不能被续活；
 * - params 不得含令牌、验证码：检测到敏感键（任意层级）拒绝保存，返回 false；
 * - 退出登录、切换账号时调用方调 [clear]；切换环境时随 SharedPreferences 命名空间一并清理。
 *
 * 每次操作都从 [backend] 读，不缓存：进程被回收后重建的 Store 读到同一条记录。
 */
class PendingActionStore(
    private val backend: PendingBackend,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun save(
        action: String,
        params: JsonObject,
        now: Long = clock(),
        origin: String? = null,
    ): Boolean {
        if (action.isBlank() || containsSensitiveKey(params)) return false
        return backend.write(encode(PendingAction(action, params, now, origin)))
    }

    /** 读取有效记录；过期、损坏或时间倒退的记录删除后返回 null。 */
    @Synchronized
    fun load(now: Long = clock()): PendingAction? {
        val raw = backend.read() ?: return null
        val action = decode(raw)
        val elapsed = action?.let { now - it.timerStartedAtMillis }
        // TODO(N-14): 时间回拨（now 早于计时起点）未规定（rule-gaps G14），保守按失效删除。
        val valid = elapsed != null && elapsed in 0..TTL_MILLIS
        if (!valid) backend.delete()
        return if (valid) action else null
    }

    /** 完成了一个前置步骤：有效记录的计时起点重置为 [now]。返回是否有记录被续时。 */
    @Synchronized
    fun markStepDone(now: Long = clock()): Boolean {
        val current = load(now) ?: return false
        return backend.write(encode(current.copy(timerStartedAtMillis = now)))
    }

    @Synchronized
    fun clear() {
        backend.delete()
    }

    private fun encode(action: PendingAction): String =
        JsonObject(
            buildMap {
                put(KEY_ACTION, JsonPrimitive(action.action))
                put(KEY_PARAMS, action.params)
                put(KEY_TIMER, JsonPrimitive(action.timerStartedAtMillis))
                action.origin?.let { put(KEY_ORIGIN, JsonPrimitive(it)) }
            },
        ).toString()

    private fun decode(raw: String): PendingAction? =
        try {
            val root = Json.parseToJsonElement(raw).jsonObject
            val name = root[KEY_ACTION]?.jsonPrimitive?.takeIf { it.isString }?.content
            val params = root[KEY_PARAMS] as? JsonObject
            val timer = root[KEY_TIMER]?.jsonPrimitive?.longOrNull
            val origin = (root[KEY_ORIGIN] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (name != null && params != null && timer != null) PendingAction(name, params, timer, origin) else null
        } catch (
            @Suppress("SwallowedException") malformed: SerializationException,
        ) {
            null
        } catch (
            @Suppress("SwallowedException") malformed: IllegalArgumentException,
        ) {
            null
        }

    companion object {
        /** 10 分钟（BR-ID-10）。 */
        const val TTL_MILLIS: Long = 10 * 60 * 1000L

        /** 动作名：打开一个路由，params = {route, params}（BR-ID-10「深链与推送打开的页面」）。 */
        const val OPEN_ROUTE = "open_route"

        private const val KEY_ACTION = "action"
        private const val KEY_PARAMS = "params"
        private const val KEY_TIMER = "timer_started_at"
        private const val KEY_ORIGIN = "origin"

        /**
         * 敏感键（小写比较）：令牌、验证码、密码类。原文只明确令牌与验证码（BR-ID-10）；别名清单是本端的保守补全，
         * TODO(N-14): rule-gaps G14 定完整别名后对齐三端。
         */
        private val SENSITIVE_KEYS =
            setOf(
                "token",
                "access_token",
                "refresh_token",
                "step_up_token",
                "h5_token",
                "id_token",
                "authorization",
                "verification_code",
                "sms_code",
                "captcha",
                "otp",
                "password",
                "install_secret",
            )

        fun isSensitiveKey(key: String): Boolean {
            val lower = key.lowercase()
            return lower in SENSITIVE_KEYS || lower.endsWith("_token")
        }

        fun containsSensitiveKey(element: JsonElement): Boolean =
            when (element) {
                is JsonObject -> element.any { (key, value) -> isSensitiveKey(key) || containsSensitiveKey(value) }
                is JsonArray -> element.any(::containsSensitiveKey)
                is JsonPrimitive -> false
            }
    }
}
