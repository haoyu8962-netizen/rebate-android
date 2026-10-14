// 假数据的金额、码号、号码都是演示值（sample-data §0，测试号 13800005678 / 13900001234），按值直接写
@file:Suppress("MagicNumber")

package com.couli.app

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Debug 专用的本机假账号服务（APP-13a：注销账号、换手机号、BindPhone 待确认的冒烟与设计稿截图；src/debugFeatures，Release 不编译，release-scan 查
 * [FAKE_ACCOUNT_SERVER_MARKER]）。由 [DebugFakeLoginServer] 在统一闸门 [DebugMockGate] 放行时调用，排在钱包之前应答；不连任何地址，不做真实注销、换号与短信。
 * 签名、step-up、幂等、受限会话走网络层真实管线，只把传输层换成本服务：
 * - requestDeletion / bindPhone（更换）没带有效的 X-Step-Up-Token（本服务签发、action 相符）回 10003；同一 Idempotency-Key 回同一结果；
 * - 二次验证码 123456 通过（签发 fake-step-up-<action>-<n>），200030 → 20003，其余 → 20002；新号验证码同样 123456；
 * - 作废（action=phone_change / account_deletion）：没受理过 → abandoned（之后同键 20903）；受理过 → completed + 原结果。
 *
 * 状态按账号保存在内存（注销进度、绑定号码、已受理的键），撤回同意、切换环境时 [reset] 同步清掉；换号成功后作废该账号的全部 step-up token。
 *
 * 场景开关文件 files/[FAKE_ACCOUNT_STATE_FILE]（每行 key=value）：
 * - deletion=none|cooling|cooling_locked|processing|cancelled_negative|load_fail（getDeletion 初值；cooling_locked = can_cancel=false）；
 * - funds=negative200|negative567|fail（getWalletSummary；不写由假钱包服务回答）；phone_bound=true|false（getMe）；
 * - apply=ok|timeout|40901|42901|50401|20903|30412|30416|30416_noamount|10007_cooling|10007_processing|10405（requestDeletion）；
 * - cancel=ok|timeout|10007|42901|50401（cancelDeletion）；
 * - bind=ok|timeout|20002|20003|30411|30411_mergeable|30414|44001|20903|10405|42901|50401（bindPhone，更换与首次绑定共用）；
 * - verify=20002|20003|42901（stepUp）；sms=42901|50401（step_up 与 bind 发码）；step_up=always（每次都要二次验证）；
 * - abandon=busy|10405|completed_ok|completed_3xxxx（作废；不写按真实键结果）；
 * - risk_msg_code=<code>（44001 的 data）；retry_after=<秒>（42901 的 Retry-After，默认 30）；refresh_scope=deletion_only（刷新令牌返回受限会话）；
 * - late=before|after（「原请求迟到」夹具：超时的原请求在作废之前 / 之后才被服务端受理，AC-S2-62 ㉑）。
 */
@Suppress("TooManyFunctions", "CyclomaticComplexMethod", "ReturnCount", "LargeClass") // 每个接口一个场景函数；场景逐项
internal class DebugFakeAccount(
    private val stateFile: File,
) {
    /** 账号 → 注销进度（JSON 文本；null = 从未申请）。 */
    private val deletions = ConcurrentHashMap<String, String>()

    /** 账号 → 绑定的号码（换号成功后改成新号）。 */
    private val phones = ConcurrentHashMap<String, String>()

    /** (账号, 键) → (code, data)：同一 Idempotency-Key 回同一结果；作废后为 20903。 */
    private val results = ConcurrentHashMap<String, Pair<Int, String>>()

    /** 账号 → 有效的 step-up token。 */
    private val tokens = ConcurrentHashMap<String, MutableSet<String>>()

    /** 「原请求迟到」夹具：超时的原请求挂起的闩（作废前 / 后放行）。 */
    private val lateGates = ConcurrentHashMap<String, CountDownLatch>()

    private var serial = 0

    /** 统一闸门（F-X-02）：关着时不回答。 */
    var switchOn: () -> Boolean = { true }

    /** 撤回同意、切换环境：清掉全部按账号保存的状态（APP-10 第 11 条）。 */
    fun reset() {
        deletions.clear()
        phones.clear()
        results.clear()
        tokens.clear()
        lateGates.values.forEach(CountDownLatch::countDown)
        lateGates.clear()
    }

    /** getMe 的 phone_bound 覆盖（场景 phone_bound=）；没写为 null。 */
    fun phoneBoundOverride(): Boolean? = state()["phone_bound"]?.toBooleanStrictOrNull()

    /** 刷新令牌返回的作用域（场景 refresh_scope=deletion_only）。 */
    fun refreshScope(): String = state()["refresh_scope"] ?: "full"

    /** 回答：(code, data JSON, 响应头)；不是本服务的请求为 null。[account] 是本服务按会话记下的账号。 */
    @Suppress("LongParameterList") // 请求的方法、路径、体、头加会话的账号与是否已绑手机
    fun answer(
        method: String,
        path: String,
        body: JsonObject,
        headers: Map<String, String>,
        account: String,
        phoneBound: Boolean,
    ): Triple<Int, String, Map<String, String>>? {
        if (!switchOn()) return null
        val state = state()
        val action = body.text("action")
        return when {
            path == DELETION && method == "GET" -> {
                getDeletion(account, state)
            }

            path == DELETION && method == "POST" -> {
                requestDeletion(account, headers, state)
            }

            path == "$DELETION/cancel" -> {
                cancelDeletion(account, state)
            }

            // 首次绑定没有场景时交给假登录服务（APP-05b 的并号等场景照旧）；更换与带场景的首次绑定由本服务回答
            path == PHONE && method == "POST" && (phoneBound || state["bind"] != null) -> {
                bindPhone(
                    account,
                    body,
                    headers,
                    state,
                    phoneBound,
                )
            }

            path == "/v1/auth/step-up" && action in ACTIONS -> {
                stepUp(account, body, state)
            }

            path == "/v1/auth/sms-codes" && (body.text("purpose") == "bind" || action in ACTIONS) -> {
                sms(state)
            }

            path == "/v1/idempotency-keys/abandon" && action in ACTIONS -> {
                abandon(account, body, state)
            }

            path == "/v1/wallet/summary" && state["funds"] != null -> {
                funds(state["funds"])
            }

            else -> {
                null
            }
        }
    }

    // ---------------------------------------------------------------- 注销

    private fun getDeletion(
        account: String,
        state: Map<String, String>,
    ): Triple<Int, String, Map<String, String>> {
        if (state["deletion"] == "load_fail") return Triple(50401, "null", emptyMap())
        val current =
            deletions[account] ?: when (state["deletion"]) {
                "cooling" -> deletion("cooling", canCancel = true)
                "cooling_locked" -> deletion("cooling", canCancel = false)
                "processing" -> deletion("processing", canCancel = false)
                "cancelled_negative" -> deletion("cancelled", canCancel = false, reason = "negative_balance")
                else -> "null"
            }
        return ok(current)
    }

    private fun requestDeletion(
        account: String,
        headers: Map<String, String>,
        state: Map<String, String>,
    ): Triple<Int, String, Map<String, String>> {
        val key = header(headers, IDEMPOTENCY) ?: return fail(20001)
        results[account + key]?.let { return Triple(it.first, it.second, emptyMap()) }
        if (!stepUpPassed(account, headers, ACCOUNT_DELETION, state)) return fail(10003)
        val result =
            when (state["apply"]) {
                "timeout" -> return late(account, key, ok(deletion("cooling", canCancel = true))) { deletions[account] = it }
                "40901" -> return fail(40901)
                "42901" -> return rateLimited(state)
                "50401" -> return Triple(50401, "null", emptyMap())
                "20903" -> 20903 to "null"
                "30412" -> 30412 to "null"
                "30416" -> 30416 to """{"amount_fen":${if (state["funds"] == "negative567") 567 else 200}}"""
                "30416_noamount" -> 30416 to "{}"
                "10007_cooling" -> return Triple(10007, """{"stage":"cooling"}""", emptyMap())
                "10007_processing" -> return Triple(10007, """{"stage":"processing"}""", emptyMap())
                "10405" -> return Triple(10405, """{"min_supported_version":"9.0.0"}""", emptyMap())
                else -> 0 to deletion("cooling", canCancel = true)
            }
        results[account + key] = result
        if (result.first == 0) deletions[account] = result.second
        return Triple(result.first, result.second, emptyMap())
    }

    private fun cancelDeletion(
        account: String,
        state: Map<String, String>,
    ): Triple<Int, String, Map<String, String>> =
        when (state["cancel"]) {
            "timeout" -> {
                deletions[account] = deletion("cancelled", canCancel = false, reason = "user")
                throw IOException("fake timeout")
            }

            "10007" -> {
                Triple(10007, """{"stage":"processing"}""", emptyMap())
            }

            "42901" -> {
                rateLimited(state)
            }

            "50401" -> {
                Triple(50401, "null", emptyMap())
            }

            else -> {
                val cancelled = deletion("cancelled", canCancel = false, reason = "user")
                deletions[account] = cancelled
                ok(cancelled)
            }
        }

    private fun deletion(
        status: String,
        canCancel: Boolean,
        reason: String? = null,
    ): String {
        val cancelledAt = if (status == "cancelled") "\"2026-10-15T09:00:00+08:00\"" else "null"
        val cancelReason = reason?.let { "\"$it\"" } ?: "null"
        return """{"status":"$status","apply_at":"2026-10-14T10:00:00+08:00","cooling_until":"2026-10-21T10:00:00+08:00",""" +
            """"can_cancel":$canCancel,"cancelled_at":$cancelledAt,"cancel_reason":$cancelReason,"processed_at":null}"""
    }

    private fun funds(scene: String?): Triple<Int, String, Map<String, String>> {
        if (scene == "fail") return Triple(50401, "null", emptyMap())
        val negative = if (scene == "negative567") 567 else 200
        return ok(
            """{"available_fen":${-negative},"withdrawable_fen":0,"negative_fen":$negative,"frozen_fen":0,"estimated_total_fen":5678,""" +
                """"estimated_fen":2222,"pending_credit_fen":3456,"pending_credit_paused_fen":0,"next_credit_period":"2026-10",""" +
                """"credit_overdue":false,"withdrawn_fen":500,"risk_paused_reason":null,"withdraw_enabled":true}""",
        )
    }

    // ---------------------------------------------------------------- 换号与首次绑定

    private fun bindPhone(
        account: String,
        body: JsonObject,
        headers: Map<String, String>,
        state: Map<String, String>,
        changing: Boolean,
    ): Triple<Int, String, Map<String, String>> {
        val key = header(headers, IDEMPOTENCY) ?: return fail(20001)
        results[account + key]?.let { return Triple(it.first, it.second, emptyMap()) }
        // 更换要 step-up（phone_change）；首次绑定不要（契约 x-step-up 只在更换时校验）
        if (changing && !stepUpPassed(account, headers, PHONE_CHANGE, state)) return fail(10003)
        val phone = body.text("phone").orEmpty().trim()
        val code = body.text("code")
        val result =
            when (state["bind"]) {
                "timeout" -> {
                    return late(account, key, ok("{}")) { bound(account, phone) }
                }

                "20002" -> {
                    return fail(20002)
                }

                "20003" -> {
                    return fail(20003)
                }

                "10405" -> {
                    return Triple(10405, """{"min_supported_version":"9.0.0"}""", emptyMap())
                }

                "42901" -> {
                    return rateLimited(state)
                }

                "50401" -> {
                    return Triple(50401, "null", emptyMap())
                }

                "44001" -> {
                    return riskBlocked(state)
                }

                "30411" -> {
                    30411 to "null"
                }

                "30411_mergeable" -> {
                    30411 to """{"reason":"mergeable","merge_ticket":"fake-merge-ticket"}"""
                }

                "30414" -> {
                    30414 to "null"
                }

                "20903" -> {
                    20903 to "null"
                }

                else -> {
                    when {
                        phone
                            .filter(
                                Char::isDigit,
                            ).length < 11 -> return Triple(20001, """{"fields":["phone"],"reason":"phone_invalid"}""", emptyMap())

                        code == "123456" -> 0 to "{}"

                        code == "200030" -> return fail(20003)

                        else -> return fail(20002)
                    }
                }
            }
        results[account + key] = result
        if (result.first == 0) bound(account, phone)
        return Triple(result.first, result.second, emptyMap())
    }

    /** 绑定 / 换号成功：记下号码，该账号的全部 step-up token 作废（G04 的服务端对照），通知假登录服务已绑手机。 */
    private fun bound(
        account: String,
        phone: String,
    ) {
        phones[account] = phone
        tokens.remove(account)
        onBound()
    }

    /** 绑定成功的回调（假登录服务的 phone_bound 改为 true）。 */
    var onBound: () -> Unit = {}

    private fun riskBlocked(state: Map<String, String>): Triple<Int, String, Map<String, String>> {
        val code = state["risk_msg_code"]
        return Triple(44001, if (code != null) """{"risk_msg_code":"$code"}""" else "{}", emptyMap())
    }

    // ---------------------------------------------------------------- 二次验证、发码、作废

    private fun stepUpPassed(
        account: String,
        headers: Map<String, String>,
        action: String,
        state: Map<String, String>,
    ): Boolean {
        val token = header(headers, "X-Step-Up-Token") ?: return false
        val set = tokens[account] ?: return false
        val passed = token in set && token.startsWith("fake-step-up-$action-")
        // 成功才核销（BR-ID-08）；step_up=always 时每次都要重新验证
        if (passed && state["step_up"] == "always") set.remove(token)
        return passed
    }

    private fun stepUp(
        account: String,
        body: JsonObject,
        state: Map<String, String>,
    ): Triple<Int, String, Map<String, String>> {
        when (state["verify"]) {
            "20002" -> return fail(20002)
            "20003" -> return fail(20003)
            "42901" -> return rateLimited(state)
        }
        val action = body.text("action").orEmpty()
        return when (body.text("code")) {
            "123456" -> {
                serial += 1
                val token = "fake-step-up-$action-$serial"
                tokens.getOrPut(account) { ConcurrentHashMap.newKeySet() } += token
                ok("""{"step_up_token":"$token","expire_at":"2099-01-01T00:05:00+08:00"}""")
            }

            "200030" -> {
                fail(20003)
            }

            else -> {
                fail(20002)
            }
        }
    }

    private fun sms(state: Map<String, String>): Triple<Int, String, Map<String, String>> =
        when (state["sms"]) {
            "42901" -> rateLimited(state)
            "50401" -> Triple(50401, "null", emptyMap())
            else -> ok("""{"resend_after_sec":60,"expires_in_sec":300}""")
        }

    private fun abandon(
        account: String,
        body: JsonObject,
        state: Map<String, String>,
    ): Triple<Int, String, Map<String, String>> {
        val key = body.text("idempotency_key") ?: return fail(20001)
        when (state["abandon"]) {
            "busy" -> return fail(40901)
            "10405" -> return Triple(10405, """{"min_supported_version":"9.0.0"}""", emptyMap())
            "completed_ok" -> return ok("""{"outcome":"completed","original":{"code":0,"msg":"","data":${completedData(body)}}}""")
            "completed_3xxxx" -> return ok("""{"outcome":"completed","original":{"code":${completedCode(body)},"msg":"","data":null}}""")
        }
        // 「原请求迟到」作废之后放行：先作废，再放行挂起的原请求（它随后得到 20903）
        val done = results[account + key]
        if (done == null) {
            results[account + key] = 20903 to "null"
            lateGates.remove(account + key)?.countDown()
            return ok("""{"outcome":"abandoned","original":null}""")
        }
        return ok("""{"outcome":"completed","original":{"code":${done.first},"msg":"","data":${done.second}}}""")
    }

    private fun completedData(body: JsonObject): String =
        if (body.text("action") == ACCOUNT_DELETION) deletion("cooling", canCancel = true) else "{}"

    private fun completedCode(body: JsonObject): Int = if (body.text("action") == ACCOUNT_DELETION) 30412 else 30411

    /**
     * 结果未知的原请求（场景 timeout）：传输层断开（客户端按结果未知进待确认）；late=before 时服务端在断开前已受理（之后作废得到 completed）；
     * late=after 时挂起到作废之后才处理，先查到已作废就按 20903 不受理（AC-S2-62 ㉑ 的两种结局）。
     */
    private fun late(
        account: String,
        key: String,
        success: Triple<Int, String, Map<String, String>>,
        apply: (String) -> Unit,
    ): Triple<Int, String, Map<String, String>> {
        when (state()["late"]) {
            "before" -> {
                results[account + key] = success.first to success.second
                apply(success.second)
            }

            "after" -> {
                val gate = CountDownLatch(1).also { lateGates[account + key] = it }
                gate.await(LATE_WAIT_S, TimeUnit.SECONDS)
                if (results[account + key] == null) {
                    results[account + key] = success.first to success.second
                    apply(success.second)
                }
            }
        }
        throw IOException("fake timeout")
    }

    private fun rateLimited(state: Map<String, String>): Triple<Int, String, Map<String, String>> =
        Triple(42901, "null", mapOf("Retry-After" to (state["retry_after"] ?: "30")))

    // ---------------------------------------------------------------- 工具

    private fun state(): Map<String, String> =
        runCatching {
            stateFile
                .takeIf(File::exists)
                ?.readLines()
                ?.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
                ?.toMap()
        }.getOrNull().orEmpty()

    private fun header(
        headers: Map<String, String>,
        name: String,
    ): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** 本服务的回答带标记头（release-scan 的标记随之进入 debug / staging 制品，同假钱包、假「我的」服务的做法）。 */
    private fun ok(data: String) = Triple(0, data, marker())

    private fun fail(code: Int) = Triple(code, "null", marker())

    private fun marker() = mapOf(DebugFakeWallet.MARKER_HEADER to FAKE_ACCOUNT_SERVER_MARKER)

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        private const val DELETION = "/v1/me/deletion"
        private const val PHONE = "/v1/me/phone"
        private const val IDEMPOTENCY = "Idempotency-Key"
        private const val PHONE_CHANGE = "phone_change"
        private const val ACCOUNT_DELETION = "account_deletion"
        private val ACTIONS = setOf(PHONE_CHANGE, ACCOUNT_DELETION)
        private const val LATE_WAIT_S = 30L

        private val instances = mutableMapOf<String, DebugFakeAccount>()

        fun of(stateFile: File): DebugFakeAccount =
            synchronized(instances) {
                instances.getOrPut(stateFile.absolutePath) { DebugFakeAccount(stateFile) }
            }
    }
}

/** 假账号服务的场景开关文件名（与假服务开关文件同目录 files/）。 */
internal const val FAKE_ACCOUNT_STATE_FILE = "couli_fake_account_state.txt"

/** release-scan 查的唯一标记（Release 制品里不得出现）。 */
internal const val FAKE_ACCOUNT_SERVER_MARKER = "COULI_FAKE_ACCOUNT_SERVER_APP13A_3e8d21"
