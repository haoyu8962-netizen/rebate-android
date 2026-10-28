package com.couli.app.me

import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.ReplayMode
import java.io.File
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * APP-12w-a 设置一族的截图 / 视图数据：只读 specs/me-vectors.json 末尾新增的 visualSettings 组（G-S18：只新增、三端同一份）。
 * 报文是锁定契约形状的 Me；本地状态（身份档、本机是否知道号码、缓存大小、剪贴板开关与同意）按 sessions 给。不在这里实现任何产品规则。
 */
internal object SettingsVisualFixtures {
    val root: File = MeVisualFixtures.root
    val group: JsonObject =
        Json
            .parseToJsonElement(File(root, "specs/me-vectors.json").readText())
            .jsonObject
            .getValue("visualSettings")
            .jsonObject
    val now: Long = OffsetDateTime.parse(text("clock")).toInstant().toEpochMilli()
    val trace: String = text("trace")
    val trace6: String = text("trace6")
    val localPhone: String = text("localPhone")

    private val nicknameGroup: JsonObject get() = group.getValue("nickname").jsonObject
    val original: String get() = text("original", nicknameGroup)
    val limitInput: String get() = text("input", nicknameGroup.getValue("limit").jsonObject)
    val limitCode: Int get() =
        nicknameGroup
            .getValue("limit")
            .jsonObject
            .getValue("code")
            .jsonPrimitive.int
    val sensitiveInput: String get() = text("input", nicknameGroup.getValue("sensitive").jsonObject)
    val sensitiveReason: String get() = text("reason", nicknameGroup.getValue("sensitive").jsonObject)
    val tooLong: String get() = text("tooLong", nicknameGroup)
    val stress: String get() = text("stress", nicknameGroup)
    val grapheme: String get() = text("grapheme", nicknameGroup)
    val savedName: String get() = text("saved", nicknameGroup)

    fun text(
        name: String,
        from: JsonObject = group,
    ): String = from.getValue(name).jsonPrimitive.content

    fun profile(name: String = "settings_member"): JsonObject =
        group
            .getValue("profiles")
            .jsonObject
            .getValue(name)
            .jsonObject

    fun session(name: String): JsonObject =
        group
            .getValue("sessions")
            .jsonObject
            .getValue(name)
            .jsonObject

    fun level(session: String): String = text("level", session(session))

    fun consented(session: String): Boolean = text("privacy", session(session)) == "consented"

    fun cacheBytes(session: String = "settings_member"): Long = session(session).getValue("cacheBytes").jsonPrimitive.long

    fun routes(public: Boolean): Set<String> =
        group
            .getValue(if (public) "routes_public" else "routes_beta")
            .jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()

    val clipboardConfig: JsonObject get() = group.getValue("clipboardConfig").jsonObject

    fun notify(name: String): String = text(name, group.getValue("notify").jsonObject)

    val notifyService: Boolean get() =
        group
            .getValue("notify")
            .jsonObject
            .getValue("service")
            .jsonPrimitive.boolean

    /** 待显示轻提示：文案键 → 是否成功类（带对勾，G-S06）。 */
    fun notices(): List<Pair<String, Boolean>> =
        group.getValue("notices").jsonArray.map {
            val row = it.jsonObject
            text("text", row) to row.getValue("check").jsonPrimitive.boolean
        }

    fun boards(): Map<String, JsonObject> = group.getValue("boards").jsonObject.mapValues { it.value.jsonObject }

    fun ok(data: JsonObject = JsonObject(emptyMap())): ApiResult = ApiResult.Success(data, trace, null)

    fun me(name: String = "settings_member"): ApiResult = ok(profile(name))

    fun failure(
        code: Int = 50001,
        data: JsonObject = JsonObject(emptyMap()),
    ): ApiResult =
        ApiResult.Failure(
            code,
            "",
            data,
            trace,
            ErrorEffect(code, ErrorEffectKind.GENERIC_ERROR, ReplayMode.NONE, false, null),
            null,
        )

    fun sensitive(): ApiResult =
        failure(
            nicknameGroup
                .getValue("sensitive")
                .jsonObject
                .getValue("code")
                .jsonPrimitive.int,
            JsonObject(
                mapOf(
                    "reason" to JsonPrimitive(sensitiveReason),
                    "fields" to Json.parseToJsonElement("""["nickname"]"""),
                ),
            ),
        )
}

/** 设置行的文案键（标记 = MeTags.SETTINGS_ROW_PREFIX + 键；剪贴板行用 ClipboardScreens.SETTINGS_ROW）。 */
internal object SettingsRows {
    const val NICKNAME = "settings.nickname"
    const val PHONE = "settings.phone"
    const val AUTH = "me.grid.auth"
    const val DELETE = "settings.delete_account"
    const val PRIVACY = "settings.privacy_center"
    const val AGREEMENT = "settings.agreement"
    const val NOTIFY = "settings.notify"
    const val RESET_TIP = "settings.reset_tip"
    const val CLEAR_CACHE = "settings.clear_cache"
    const val HELP = "settings.help"
    const val ABOUT = "settings.about"
    const val CLIPBOARD = "settings.clipboard_auto"

    /** 已登录公开版三组（条目与顺序由 SettingsItems 决定，本任务不改）。 */
    val account = listOf(NICKNAME, PHONE, AUTH, DELETE)
    val privacy = listOf(PRIVACY, AGREEMENT)
    val general = listOf(NOTIFY, RESET_TIP, CLIPBOARD, CLEAR_CACHE, HELP, ABOUT)

    /** 游客、基本模式的现有顺序（G-S03 推荐不改，compare 记差异）。 */
    val guest = listOf(PRIVACY, AGREEMENT, ABOUT, CLIPBOARD, CLEAR_CACHE, HELP)
    val basic = listOf(PRIVACY, AGREEMENT, ABOUT, HELP)

    fun tag(key: String): String =
        if (key ==
            CLIPBOARD
        ) {
            com.couli.app.clipboard.ClipboardScreens.SETTINGS_ROW
        } else {
            com.couli.feature.me.MeTags.SETTINGS_ROW_PREFIX + key
        }
}
