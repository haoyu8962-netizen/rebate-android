// 错误码是契约取值（contracts/error-codes.yaml），按码直接写
@file:Suppress("MagicNumber")

package com.couli.feature.trade

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.network.percentEncode
import com.couli.core.tradejump.BaichuanAdapter
import com.couli.core.tradejump.BaichuanAuthResult
import kotlinx.serialization.json.JsonObject

/** 一次淘宝授权（含 30104 恢复）的结局。 */
sealed interface TaobaoAuthOutcome {
    /** bindings 返回 active。 */
    data object Bound : TaobaoAuthOutcome

    /** 用户在授权页 / SDK 里取消。 */
    data object Cancelled : TaobaoAuthOutcome

    /** 授权失败（BR-ID-18）：本端执行不了下发的任何方式、授权步骤失败，或 30104 之后没有可换的方式。 */
    data object Failed : TaobaoAuthOutcome

    /** 请求失败（bindings、auth-url 的其它业务码、42901、5xxxx、传输失败）：调用方按码处理，不能当成用户没授权（A-R2-06）。 */
    class RequestFailed(
        val result: ApiResult,
    ) : TaobaoAuthOutcome

    /** 页面已离开，或第一次拉起授权前授权许可已不在（不在前台，没有外跳过）：什么都不做 / 按钮复原。 */
    data object Abandoned : TaobaoAuthOutcome

    /**
     * 已拉起过授权、30104 恢复后要再次拉起时，用户点击发出的授权许可已撤销（进过后台、被覆盖；APP-06b 共性第 31 条）：不再自动拉起，
     * 调用方回到前台后向服务端查询授权状态，没授权就交用户再点。
     */
    data object Interrupted : TaobaoAuthOutcome
}

/**
 * 淘宝授权的执行与 30104 恢复（BR-ID-17 细则「凭证无效」「幂等键」、契约 30104 action；A-R2-05，APP-06b 共性第 18 条）。购买流程的 AuthSheet
 * 与授权管理页共用：
 * - 按下发的 auth_methods 取本端能执行的第一种（web_code 要有 auth_url；sdk_token 要百川支持换令牌），执行后用 state + 凭证调 bindings，
 *   每次绑定尝试一个新的幂等键；
 * - bindings 返回 30104：重新调 auth-url 取新的 state 与方式列表，这是一次新的绑定尝试（新 state、新凭证、新幂等键）：
 *   - reason=credential_invalid：这种方式记为失效，改用新列表里下一种本端可执行、没失效过的方式；没有就按授权失败；
 *   - reason=method_not_allowed：只按新下发的 auth_methods 执行（仍排除已失效的方式）；
 *   - 没有 reason（state 过期或已用）：按新列表重新发起；
 *   恢复最多 [MAX_RECOVERIES] 次（服务端反复返回 30104 时不无限循环），之后按授权失败；
 * - 其它失败（bindings、auth-url 的业务码、42901、5xxxx、传输失败）原样交给调用方（[TaobaoAuthOutcome.RequestFailed]）。
 * 每次等待之后复核 [alive]（页面离开即 [TaobaoAuthOutcome.Abandoned]）。每次拉起授权（含 30104 恢复后的再次授权）之前复核 [canJump]：
 * 用户点击发出的授权许可仍有效、页面在前台（进过后台即撤销，回前台也不恢复，APP-06b 共性第 25、31 条）；不满足就不拉起——还没拉起过
 * 返回 [TaobaoAuthOutcome.Abandoned]，30104 恢复中返回 [TaobaoAuthOutcome.Interrupted]。
 */
@Suppress("LongParameterList") // 依赖逐项注入
class TaobaoAuthFlow(
    private val api: PageApi,
    private val webCode: WebCodeAuthorizer,
    private val baichuan: BaichuanAdapter,
    private val keyGenerator: () -> String,
    private val owner: () -> CallOwnership,
    private val alive: () -> Boolean,
    private val installed: (String) -> String,
    private val canJump: () -> Boolean = { true },
) {
    /** 本端能执行的方式（按本端能力，不看下发顺序）。 */
    private fun supported(): List<String> =
        listOfNotNull(
            AuthMethodPicker.WEB_CODE.takeIf { webCode.available },
            AuthMethodPicker.SDK_TOKEN.takeIf { baichuan.supportsSdkToken },
        )

    /** [data] 里本端此刻能执行的第一种方式（排除 [excluded]）；state、auth_url 不全也算不能执行。 */
    fun pick(
        platform: String,
        data: JsonObject,
        excluded: Set<String> = emptySet(),
    ): String? {
        val methods = data.texts("auth_methods").filter { it !in excluded }
        val method = AuthMethodPicker.pick(platform, methods, supported()) ?: return null
        val ready = data.text("state") != null && (method != AuthMethodPicker.WEB_CODE || data.text("auth_url") != null)
        return method.takeIf { ready }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements") // 每一步等待后的出口逐项
    suspend fun run(
        platform: String,
        initial: JsonObject,
    ): TaobaoAuthOutcome {
        var data = initial
        val invalid = mutableSetOf<String>()
        var recoveries = 0
        var jumped = false
        while (true) {
            if (!alive()) return TaobaoAuthOutcome.Abandoned
            val method = pick(platform, data, invalid) ?: return TaobaoAuthOutcome.Failed
            val state = data.text("state") ?: return TaobaoAuthOutcome.Failed
            // 许可只认用户那一下（共性第 31 条）：已拉起过授权（30104 恢复中）就交调用方回前台查询，不凭「此刻在前台」再拉起
            if (!canJump()) return if (jumped) TaobaoAuthOutcome.Interrupted else TaobaoAuthOutcome.Abandoned
            jumped = true
            val step = authorizeWith(method, data.text("auth_url").orEmpty(), state)
            if (!alive()) return TaobaoAuthOutcome.Abandoned
            val credential =
                when (step) {
                    is AuthStepResult.Credential -> step.value
                    AuthStepResult.Cancelled -> return TaobaoAuthOutcome.Cancelled
                    AuthStepResult.Failed -> return TaobaoAuthOutcome.Failed
                }
            val result = bind(platform, state, credential)
            if (!alive()) return TaobaoAuthOutcome.Abandoned
            if (result is ApiResult.Success) {
                val status = (result.data as? JsonObject)?.text("status")
                return if (status == ACTIVE) TaobaoAuthOutcome.Bound else TaobaoAuthOutcome.Failed
            }
            if (result !is ApiResult.Failure || result.code != STATE_OR_CREDENTIAL) return TaobaoAuthOutcome.RequestFailed(result)
            // 30104：重新取授权上下文，一次新的绑定尝试（BR-ID-17 细则、契约 30104 action）
            recoveries += 1
            if (recoveries > MAX_RECOVERIES) return TaobaoAuthOutcome.Failed
            if (result.data.text("reason") == CREDENTIAL_INVALID) invalid += method
            val refreshed = authUrl(platform)
            if (!alive()) return TaobaoAuthOutcome.Abandoned
            data = (refreshed as? ApiResult.Success)?.data as? JsonObject ?: return TaobaoAuthOutcome.RequestFailed(refreshed)
        }
    }

    private suspend fun authorizeWith(
        method: String,
        authUrl: String,
        state: String,
    ): AuthStepResult =
        if (method == AuthMethodPicker.WEB_CODE) {
            webCode.authorize(authUrl, state)
        } else {
            when (val r = baichuan.authorize()) {
                is BaichuanAuthResult.Token -> AuthStepResult.Credential(AuthCredential.SdkToken(r.accessToken, r.expiresIn))
                BaichuanAuthResult.Cancelled -> AuthStepResult.Cancelled
                BaichuanAuthResult.Failed -> AuthStepResult.Failed
            }
        }

    /** bindings：每次绑定尝试一个新的幂等键（BR-ID-17 细则「幂等键」）。 */
    private suspend fun bind(
        platform: String,
        state: String,
        credential: AuthCredential,
    ): ApiResult =
        api.send(
            ApiCall(
                TradeOperations.BIND_UNION,
                pathParams = mapOf("platform" to platform),
                body = bindBody(state, credential),
                idempotencyKey = keyGenerator(),
                ownership = owner(),
            ),
        )

    /** auth-url：重新取 state 与方式列表。 */
    suspend fun authUrl(platform: String): ApiResult =
        api.send(
            ApiCall(
                TradeOperations.GET_UNION_AUTH_URL,
                pathParams = mapOf("platform" to platform),
                query = "installed=${percentEncode(installed(platform))}",
                ownership = owner(),
            ),
        )

    companion object {
        /** 30104 之后重新发起的上限（每次都要用户重新完成授权；服务端反复返回 30104 时停下按授权失败）。 */
        const val MAX_RECOVERIES = 3
        const val STATE_OR_CREDENTIAL = 30104
        const val CREDENTIAL_INVALID = "credential_invalid"
        const val METHOD_NOT_ALLOWED = "method_not_allowed"
        private const val ACTIVE = "active"
    }
}
