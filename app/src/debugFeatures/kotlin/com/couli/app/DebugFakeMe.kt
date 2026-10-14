// 假数据的昵称、邀请码、金额、时刻都是演示值（设计稿 sample-data §0、§7、§11、§12.3），按值直接写
@file:Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod") // 演示值按值写；场景逐项提前返回

package com.couli.app

import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.ReplayMode
import com.couli.feature.me.NicknameBackend
import com.couli.feature.me.NotifyBackend
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Debug 专用的本机假「我的」服务（APP-12 设计稿比对与冒烟用；src/debugFeatures，Release 不编译，release-scan 查 [FAKE_ME_SERVER_MARKER]）。与假登录服务同一个开关文件
 * （[DebugFakeLoginServer] 接管时才回答），全部在本机回答，不连任何地址，不做真实账号操作。样例数据照 sample-data：凑狸用户 / 小狸爱买买、K7M3QX、可提现 ¥12.34、
 * 预估 ¥56.78。
 *
 * 状态开关文件 files/[FAKE_ME_STATE_FILE]（每行 key=value）：
 * - me=normal|frozen|frozen_open|frozen_appealing|banned|appealing|minor|unbound
 *   （frozen_appealing：申诉中但不回 10006，给冻结组的「申诉处理中」截图）（risk.state 与 invite_code / inviter_bound 场景；minor 为 invite_code=null）；
 * - reason=<category>（缺省 malicious_rights，frozen 缺省 abnormal_trade）；appeals=none|processing|upheld|revoked（账户申诉最新一条）；
 * - wechat=no|yes（微信已装检测的覆盖，只给截图；缺省 unknown）；cs_open=fail（打开微信失败）；kf=none（不下发客服链接）；
 * - push_denied_hours=<小时>（通知被拒于多少小时前，给 MeHidden 截图造引导条；缺省不覆盖）；push_token=<测试令牌>（缺省 test-push-token-0001）。
 * banned / appealing 场景下白名单（BR-ID-31：/v1/me、申诉、提现记录、配置、设备、退出、同意记录、推送上报）之外的接口回 10006。
 * 待定 operation（G01 改昵称、G02 通知设置）只在进程内实现：每月 3 次后 30415；昵称含「敏感」或 mingan（adb 输不了中文）→ 20001
 * nickname_sensitive。
 */
@Suppress("TooManyFunctions")
internal class DebugFakeMe(
    private val stateFile: File,
) {
    @Volatile private var nickname: String? = null

    @Volatile private var nicknameChanges = 0

    @Volatile private var service = true

    @Volatile private var submitted: JsonObject? = null

    var switchOn: () -> Boolean = { true }

    fun state(): Map<String, String> =
        runCatching {
            stateFile
                .takeIf(File::exists)
                ?.readLines()
                ?.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
                ?.toMap()
        }.getOrNull().orEmpty()

    private fun scene(): String = state()["me"] ?: "normal"

    /** banned / appealing（封禁后申诉中）场景下白名单之外的请求回 10006。 */
    fun blocks(path: String): Boolean {
        val scene = scene()
        if (scene != "banned" && scene != "appealing") return false
        return WHITELIST.none { path == it || (it != ME_PATH && path.startsWith("$it/")) }
    }

    /** 回答：(code, data JSON, 响应头)；不是本服务的路径为 null。 */
    fun answer(
        method: String,
        path: String,
        body: JsonObject,
        realname: String,
        phoneBound: Boolean,
    ): Triple<Int, String, Map<String, String>>? =
        when {
            path == "/v1/me" && method == "GET" -> ok(me(realname, phoneBound).toString())
            path == "/v1/me/appeals" && method == "POST" -> submit(body)
            path == "/v1/me/appeals" -> ok(appeals().toString())
            path == "/v1/config" -> ok(config())
            path.startsWith("/v1/me/tips/") && method == "DELETE" -> ok("{}")
            path == "/v1/consents" || path == "/v1/devices/push-token" -> ok("{}")
            else -> null
        }

    private fun me(
        realname: String,
        phoneBound: Boolean,
    ): JsonObject {
        val scene = scene()
        val state =
            when (scene) {
                "frozen", "frozen_open" -> "frozen"
                "banned" -> "banned"
                "appealing", "frozen_appealing" -> "appealing"
                else -> "normal"
            }
        val reason =
            when (state) {
                "normal" -> null
                "frozen" -> state()["reason"] ?: "abnormal_trade"
                else -> state()["reason"] ?: "malicious_rights"
            }
        val until = if (scene == "frozen") JsonPrimitive("2026-10-15T00:00:00+08:00") else null
        return JsonObject(
            linkedMapOf(
                "user_id" to JsonPrimitive("fake-user-0001"),
                "nickname" to JsonPrimitive(nickname ?: "凑狸用户"),
                "avatar_url" to JsonPrimitive(""),
                "invite_code" to if (scene == "minor") kotlinx.serialization.json.JsonNull else JsonPrimitive("K7M3QX"),
                "inviter_bound" to JsonPrimitive(scene != "unbound"),
                "invite_backfill" to
                    JsonObject(mapOf("eligible" to JsonPrimitive(false), "deadline_at" to kotlinx.serialization.json.JsonNull)),
                "identity_level" to JsonPrimitive("normal"),
                "phone_bound" to JsonPrimitive(phoneBound),
                "balance" to
                    JsonObject(
                        mapOf(
                            "available_fen" to JsonPrimitive(1234),
                            "withdrawable_fen" to JsonPrimitive(1234),
                            "estimated_total_fen" to JsonPrimitive(5678),
                        ),
                    ),
                "union_bindings" to JsonArray(emptyList()),
                "realname_status" to JsonPrimitive(realname),
                "need_reconsent" to JsonPrimitive(false),
                "risk" to
                    JsonObject(
                        mapOf(
                            "state" to JsonPrimitive(state),
                            "reason_category" to (reason?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull),
                            "frozen_until" to (until ?: kotlinx.serialization.json.JsonNull),
                        ),
                    ),
            ),
        )
    }

    private fun appeal(
        id: String,
        status: String,
        closed: Boolean,
    ): JsonObject =
        JsonObject(
            linkedMapOf(
                "appeal_id" to JsonPrimitive(id),
                "target_type" to JsonPrimitive("account"),
                "target_id" to kotlinx.serialization.json.JsonNull,
                "status" to JsonPrimitive(status),
                "content" to JsonPrimitive("测试申诉，请核查。"),
                "created_at" to JsonPrimitive("2026-10-10T10:00:00+08:00"),
                "closed_at" to if (closed) JsonPrimitive("2026-10-11T15:00:00+08:00") else kotlinx.serialization.json.JsonNull,
            ),
        )

    private fun appeals(): JsonObject {
        val items =
            submitted?.let { listOf(it) } ?: when (state()["appeals"]) {
                "processing" -> listOf(appeal("fake-appeal-1", "processing", closed = false))
                "upheld" -> listOf(appeal("fake-appeal-1", "upheld", closed = true))
                "revoked" -> listOf(appeal("fake-appeal-1", "revoked", closed = true))
                else -> emptyList()
            }
        return JsonObject(mapOf("items" to JsonArray(items), "next_cursor" to kotlinx.serialization.json.JsonNull))
    }

    private fun submit(body: JsonObject): Triple<Int, String, Map<String, String>> {
        val content = (body["content"] as? JsonPrimitive)?.content.orEmpty()
        if (content.isBlank()) return Triple(20001, """{"fields":["content"]}""", emptyMap())
        // 处理中再次提交返回原单（BR-ID-36）
        val existing = submitted
        if (existing != null && (existing["status"] as? JsonPrimitive)?.content == "processing") return ok(existing.toString())
        val created = appeal("fake-appeal-2", "processing", closed = false)
        submitted = JsonObject(created + ("content" to JsonPrimitive(content)))
        return ok(submitted.toString())
    }

    /** 生效配置（包内默认 + 测试用客服链接与 AI 公示；值都是明显的测试值）。 */
    private fun config(): String {
        val base =
            runCatching {
                kotlinx.serialization.json.Json
                    .parseToJsonElement(
                        com.couli.core.clientcore.BundledConfig.dataJson,
                    ) as JsonObject
            }.getOrNull() ?: JsonObject(emptyMap())
        val kf =
            if (state()["kf"] ==
                "none"
            ) {
                kotlinx.serialization.json.JsonNull
            } else {
                JsonObject(mapOf("url" to JsonPrimitive(TEST_KF_URL)))
            }
        val agent = (base["agent"] as? JsonObject).orEmpty() + ("filing_text" to JsonPrimitive(TEST_FILING))
        return JsonObject(base + mapOf("kf" to kf, "agent" to JsonObject(agent))).toString()
    }

    private fun JsonObject?.orEmpty(): Map<String, kotlinx.serialization.json.JsonElement> = this ?: emptyMap()

    // ---------------------------------------------------------------- 待定 operation（G01、G02），进程内

    val nicknameBackend: NicknameBackend =
        object : NicknameBackend {
            override suspend fun save(
                account: String,
                nickname: String,
                allowed: () -> Boolean,
            ): ApiResult {
                if (!allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
                if (!switchOn()) return ApiResult.TransportFailure("disconnect", null, null, false)
                return when {
                    nickname.contains("敏感") || nickname.contains("mingan") -> {
                        failure(20001, JsonObject(mapOf("reason" to JsonPrimitive("nickname_sensitive"))))
                    }

                    nicknameChanges >= MONTHLY_LIMIT -> {
                        failure(30415)
                    }

                    else -> {
                        nicknameChanges += 1
                        this@DebugFakeMe.nickname = nickname
                        ApiResult.Success(JsonObject(mapOf("nickname" to JsonPrimitive(nickname))), "fake-trace-nickname", null)
                    }
                }
            }
        }

    val notifyBackend: NotifyBackend =
        object : NotifyBackend {
            override suspend fun get(
                account: String,
                allowed: () -> Boolean,
            ): ApiResult {
                if (!allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
                if (!switchOn()) return ApiResult.TransportFailure("disconnect", null, null, false)
                return ApiResult.Success(JsonObject(mapOf("service" to JsonPrimitive(service))), "fake-trace-notify", null)
            }

            override suspend fun put(
                account: String,
                service: Boolean,
                allowed: () -> Boolean,
            ): ApiResult {
                if (!allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
                if (!switchOn()) return ApiResult.TransportFailure("disconnect", null, null, false)
                this@DebugFakeMe.service = service
                return ApiResult.Success(JsonObject(mapOf("service" to JsonPrimitive(service))), "fake-trace-notify", null)
            }
        }

    private fun failure(
        code: Int,
        data: JsonObject = JsonObject(emptyMap()),
    ): ApiResult =
        ApiResult.Failure(
            code,
            "",
            data,
            "fake-trace-${"%06d".format(code)}",
            ErrorEffect(code, ErrorEffectKind.TOAST, ReplayMode.NONE, false),
            null,
        )

    private fun ok(data: String) = Triple(0, data, mapOf(MARKER_HEADER to FAKE_ME_SERVER_MARKER))

    companion object {
        private const val MARKER_HEADER = "X-Couli-Debug-Fake-Me"
        private const val MONTHLY_LIMIT = 3
        private const val ME_PATH = "/v1/me"
        const val TEST_KF_URL = "https://work.weixin.qq.com/kfid/kfc-test-0001"
        const val TEST_FILING = "测试模型名称：演示模型\n测试登记编号：TEST-0001"
        val WHITELIST: List<String> =
            listOf(
                "/v1/me",
                "/v1/me/appeals",
                "/v1/withdrawals",
                "/v1/config",
                "/v1/devices",
                "/v1/auth/logout",
                "/v1/auth/refresh",
                "/v1/consents",
                // BR-ID-31：注销三接口、作废、二次验证与发码（account_deletion）也在 10006 白名单（APP-13a AC-S2-62 ㉓ 冒烟）
                "/v1/me/deletion",
                "/v1/idempotency-keys/abandon",
                "/v1/auth/step-up",
                "/v1/auth/sms-codes",
            )

        private val instances = mutableMapOf<String, DebugFakeMe>()

        fun of(stateFile: File): DebugFakeMe =
            synchronized(instances) { instances.getOrPut(stateFile.absolutePath) { DebugFakeMe(stateFile) } }
    }
}

/** 假「我的」服务的状态开关文件名（与假登录服务的开关同目录）。 */
internal const val FAKE_ME_STATE_FILE = "couli_fake_me_state.txt"

/** release-scan 查的唯一标记（Release 制品里不得出现）。 */
internal const val FAKE_ME_SERVER_MARKER = "COULI_FAKE_ME_SERVER_APP12_3e91b7"
